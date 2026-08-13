/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.messaging.rcs.sip;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsManager;

import com.android.messaging.util.LogUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Owns the app's {@code SipDelegate}: the SIP transport granted by the carrier's ImsService over
 * the modem's existing IMS registration.
 *
 * <p>This replaces the former socket-based SipStackManager, which could never work. The real
 * P-CSCF lives on the restricted IMS PDN at a ULA address, and registration requires IMS-AKA
 * against the ISIM. Both are out of reach for app-owned sockets. A SipDelegate sidesteps all of
 * it: the ImsService already holds the registration, and we hand it fully-formed SIP messages.
 *
 * <p>The entire SipDelegate API surface is {@code @SystemApi} (and the config accessors partly
 * {@code @hide}), while this app builds against the public SDK, so every call here goes through
 * reflection. See Android.bp {@code sdk_version: "current"}.
 */
public class SipDelegateTransport {
    private static final String TAG = "SipDelegateTransport";

    private static final String CLS_DELEGATE_REQUEST = "android.telephony.ims.DelegateRequest";
    private static final String CLS_STATE_CALLBACK =
            "android.telephony.ims.stub.DelegateConnectionStateCallback";
    private static final String CLS_MESSAGE_CALLBACK =
            "android.telephony.ims.stub.DelegateConnectionMessageCallback";
    private static final String CLS_SIP_MESSAGE = "android.telephony.ims.SipMessage";
    private static final String CLS_DELEGATE_CONNECTION =
            "android.telephony.ims.SipDelegateConnection";

    /**
     * Feature tags we ask for. This carrier grants session-mode chat and file transfer and denies
     * the standalone/pager-mode tags ({@code oma.cpm.msg}, {@code oma.cpm.largemsg},
     * {@code pager-large}) with DENIED_REASON_INVALID, matching {@code standaloneMsgAuth=0} in its
     * provisioning document. We still request them: a carrier that allows them costs us nothing,
     * and the denial set is useful diagnostics.
     */
    private static final String[] REQUESTED_FEATURE_TAGS = new String[] {
            "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"",
            "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.filetransfer\"",
            "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.fthttp\"",
            "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg\"",
            "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.largemsg\"",
            "+g.gsma.rcs.cpm.pager-large",
    };

    /** Feature tag identifying session-mode chat; messaging is only usable once it registers. */
    public static final String FEATURE_TAG_CHAT_SESSION =
            "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"";

    /** Receives inbound SIP traffic and outbound delivery outcomes from the delegate. */
    public interface SipMessageListener {
        /**
         * @return true if this listener consumed the message. The transport acknowledges the
         *         message to the ImsService either way, but an unconsumed message is logged.
         */
        boolean onSipMessage(String startLine, String headerSection, byte[] content);

        /** The ImsService refused to transmit a message we sent. */
        default void onSipSendFailure(String viaBranch, int reason) {}

        /** The ImsService transmitted a message we sent. */
        default void onSipSendSuccess(String viaBranch) {}

        /**
         * The transport became usable again: a configuration arrived, or the chat feature tag
         * returned to the registered set.
         *
         * <p>Sending requires both, and the ImsService rejects requests made without them as
         * {@link #FAILURE_NOT_REGISTERED} or {@link #FAILURE_STALE_IMS_CONFIGURATION}, which it
         * documents as temporary and retryable only once the corresponding callback has fired.
         */
        default void onSipTransportReady() {}
    }

    /**
     * @return true if a request built now stands a chance: the delegate holds a configuration and
     *         the chat feature tag is registered.
     */
    public boolean isSendable() {
        return mDelegateConnection != null && mConfigVersion >= 0 && isChatReady();
    }

    // Mirrors SipDelegateManager.MESSAGE_FAILURE_REASON_*.
    public static final int FAILURE_INVALID_START_LINE = 3;
    public static final int FAILURE_INVALID_HEADER_FIELDS = 4;
    public static final int FAILURE_INVALID_BODY_CONTENT = 5;
    public static final int FAILURE_NETWORK_NOT_AVAILABLE = 8;
    public static final int FAILURE_NOT_REGISTERED = 9;
    public static final int FAILURE_STALE_IMS_CONFIGURATION = 10;
    public static final int FAILURE_INTERNAL_STATE_TRANSITION = 11;

    /**
     * Whether a rejection describes a passing condition rather than a bad request.
     *
     * <p>The platform documents each of these as temporary and says the request should be retried
     * once the matching state callback has fired — not reformulated. Treating them like a malformed
     * request instead, which this client used to do, spends the Request-URI fallbacks on a problem
     * none of them addresses and then abandons a message the network would have accepted a second
     * later.
     */
    public static boolean isTemporaryFailure(int reason) {
        switch (reason) {
            case FAILURE_NETWORK_NOT_AVAILABLE:
            case FAILURE_NOT_REGISTERED:
            case FAILURE_STALE_IMS_CONFIGURATION:
            case FAILURE_INTERNAL_STATE_TRANSITION:
                return true;
            default:
                return false;
        }
    }

    private static String failureReasonToString(int reason) {
        switch (reason) {
            case 1: return "DELEGATE_DEAD";
            case 2: return "DELEGATE_CLOSED";
            case FAILURE_INVALID_START_LINE: return "INVALID_START_LINE";
            case FAILURE_INVALID_HEADER_FIELDS: return "INVALID_HEADER_FIELDS";
            case FAILURE_INVALID_BODY_CONTENT: return "INVALID_BODY_CONTENT";
            case 6: return "INVALID_FEATURE_TAG";
            case 7: return "TAG_NOT_ENABLED_FOR_DELEGATE";
            case 8: return "NETWORK_NOT_AVAILABLE";
            case 9: return "NOT_REGISTERED";
            case 10: return "STALE_IMS_CONFIGURATION";
            case 11: return "INTERNAL_DELEGATE_STATE_TRANSITION";
            default: return "UNKNOWN(" + reason + ")";
        }
    }

    private static SipDelegateTransport sInstance;

    /** Single thread so delegate callbacks stay ordered and off the main thread. */
    private static final java.util.concurrent.ExecutorService sCallbackExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private final Context mContext;
    private final CopyOnWriteArrayList<SipMessageListener> mListeners = new CopyOnWriteArrayList<>();

    private Object mSipDelegateManager;
    private volatile Object mDelegateConnection;
    private volatile Object mConfiguration;
    private volatile long mConfigVersion = -1;
    private volatile Set<String> mRegisteredTags = Collections.emptySet();
    private volatile boolean mCreatePending;
    private volatile boolean mConfigRecoveryAttempted;

    /** Grace period for a configuration to arrive on its own before forcing a re-registration. */
    private static final long CONFIG_WAIT_BEFORE_RECOVERY_MS = 6_000L;
    /** Gap between destroying and recreating, so the ImsService sees the tags actually leave. */
    private static final long RECREATE_DELAY_MS = 1_500L;

    public static final int SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP = 2;

    private SipDelegateTransport(Context context) {
        mContext = context.getApplicationContext();
    }

    public static synchronized SipDelegateTransport getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SipDelegateTransport(context);
        }
        return sInstance;
    }

    public void addListener(SipMessageListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(SipMessageListener listener) {
        mListeners.remove(listener);
    }

    /** True once the carrier has registered the session-mode chat feature tag for us. */
    public boolean isChatReady() {
        return mDelegateConnection != null && mRegisteredTags.contains(FEATURE_TAG_CHAT_SESSION);
    }

    public Object getConfiguration() {
        return mConfiguration;
    }

    public Set<String> getRegisteredFeatureTags() {
        return mRegisteredTags;
    }

    /**
     * Creates the delegate if it does not already exist. Safe to call repeatedly; concurrent and
     * redundant calls collapse into the single outstanding request.
     */
    public synchronized void ensureStarted() {
        if (mDelegateConnection != null || mCreatePending) return;
        try {
            if (!resolveManager()) return;
            if (!isSupported()) {
                LogUtil.w(TAG, "Single registration unsupported for this subscription. "
                        + "Gate is carrier config ims.ims_single_registration_required_bool.");
                return;
            }
            mCreatePending = true;
            createDelegate();
        } catch (Throwable t) {
            mCreatePending = false;
            LogUtil.e(TAG, "ensureStarted failed", t);
        }
    }

    private boolean resolveManager() {
        if (mSipDelegateManager != null) return true;
        final int subId = resolveSubId(mContext);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            LogUtil.w(TAG, "No usable subscription");
            return false;
        }
        final ImsManager imsManager =
                (ImsManager) mContext.getSystemService(Context.TELEPHONY_IMS_SERVICE);
        if (imsManager == null) {
            LogUtil.w(TAG, "TELEPHONY_IMS_SERVICE unavailable");
            return false;
        }
        try {
            mSipDelegateManager = ImsManager.class
                    .getMethod("getSipDelegateManager", int.class).invoke(imsManager, subId);
        } catch (Throwable t) {
            LogUtil.e(TAG, "getSipDelegateManager unavailable: " + describe(t));
            return false;
        }
        return mSipDelegateManager != null;
    }

    private boolean isSupported() {
        try {
            final Object res = mSipDelegateManager.getClass().getMethod("isSupported")
                    .invoke(mSipDelegateManager);
            final boolean supported = (res instanceof Boolean) && (Boolean) res;
            LogUtil.i(TAG, "SipDelegateManager.isSupported() = " + supported);
            return supported;
        } catch (Throwable t) {
            LogUtil.e(TAG, "isSupported failed: " + describe(t));
            return false;
        }
    }

    private void createDelegate() throws Exception {
        final Set<String> tags = new LinkedHashSet<>(Arrays.asList(REQUESTED_FEATURE_TAGS));
        final Class<?> requestClass = Class.forName(CLS_DELEGATE_REQUEST);
        final Object request = requestClass.getConstructor(Set.class).newInstance(tags);

        final Class<?> stateCbClass = Class.forName(CLS_STATE_CALLBACK);
        final Class<?> msgCbClass = Class.forName(CLS_MESSAGE_CALLBACK);

        final Object stateCallback = Proxy.newProxyInstance(
                mContext.getClassLoader(), new Class<?>[] { stateCbClass },
                (proxy, method, args) -> {
                    handleStateCallback(method.getName(), args);
                    return defaultReturn(method);
                });

        final Object messageCallback = Proxy.newProxyInstance(
                mContext.getClassLoader(), new Class<?>[] { msgCbClass },
                (proxy, method, args) -> {
                    handleMessageCallback(method.getName(), args);
                    return defaultReturn(method);
                });

        // Deliberately not the main executor: inbound SIP parsing, MSRP setup and the database
        // work that follows all assert they are off the main thread.
        final Executor executor = sCallbackExecutor;
        LogUtil.i(TAG, "Requesting SipDelegate with " + tags.size() + " feature tags");
        mSipDelegateManager.getClass()
                .getMethod("createSipDelegate", requestClass, Executor.class, stateCbClass, msgCbClass)
                .invoke(mSipDelegateManager, request, executor, stateCallback, messageCallback);
    }

    // ---------------------------------------------------------------- state callbacks

    /**
     * Forces a real IMS re-registration when the delegate comes up without a configuration.
     *
     * <p>The ImsService caches the last configuration it saw and hands it to a newly created
     * delegate. That cache is empty after the service or this process restarts, and it is only
     * refilled by an actual re-registration. Whether one happens is decided by comparing the tags
     * our delegate wants against the tags already registered — and when this process is replaced,
     * the dead one's tags are still registered, so the new delegate looks like "no change", no
     * re-registration occurs, and the cache is never refilled:
     *
     * <pre>
     *   updateSipDelegateRegistration, no change in registration bitmask
     *   triggerLatestDelegateConfigUpdate
     *   triggerLatestDelegateConfiguration, missing valid config
     * </pre>
     *
     * <p>The delegate then sits with its feature tags registered and no configuration, which is
     * indistinguishable from being ready and cannot send anything. Destroying it drops the tags
     * from the registration; creating it again re-adds them, and that difference is what makes the
     * ImsService re-register and produce a configuration. Done once per process, and only when a
     * configuration has genuinely failed to arrive.
     */
    private void scheduleConfigRecovery() {
        if (mConfigVersion >= 0 || mConfigRecoveryAttempted || !isChatReady()) return;
        mConfigRecoveryAttempted = true;
        sCallbackExecutor.execute(() -> {
            try {
                Thread.sleep(CONFIG_WAIT_BEFORE_RECOVERY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (mConfigVersion >= 0) return;
            final Object connection = mDelegateConnection;
            if (connection == null) return;
            LogUtil.w(TAG, "No SipDelegateConfiguration " + CONFIG_WAIT_BEFORE_RECOVERY_MS
                    + "ms after registration; recreating the delegate to force one");
            try {
                mSipDelegateManager.getClass()
                        .getMethod("destroySipDelegate", Class.forName(CLS_DELEGATE_CONNECTION),
                                int.class)
                        .invoke(mSipDelegateManager, connection,
                                SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP);
            } catch (Throwable t) {
                LogUtil.e(TAG, "destroySipDelegate failed: " + describe(t));
                return;
            }
            // onDestroyed clears the connection; give the ImsService a moment to deregister the
            // tags before asking for them back, or it sees no net change and skips the register.
            try {
                Thread.sleep(RECREATE_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // Via ensureStarted so this collapses with any concurrent start attempt rather than
            // racing it into two delegates.
            ensureStarted();
        });
    }

    /** Wakes anything that deferred a send because the transport was not usable yet. */
    private void notifyTransportReady() {
        if (!isSendable()) return;
        for (SipMessageListener listener : mListeners) {
            try {
                listener.onSipTransportReady();
            } catch (Throwable t) {
                LogUtil.e(TAG, "onSipTransportReady listener failed", t);
            }
        }
    }

    private void handleStateCallback(String name, Object[] args) {
        switch (name) {
            case "onCreated":
                mCreatePending = false;
                mDelegateConnection = (args != null && args.length > 0) ? args[0] : null;
                LogUtil.i(TAG, "SipDelegate granted: " + mDelegateConnection);
                break;
            case "onFeatureTagStatusChanged":
                if (args != null && args.length > 0 && args[0] != null) {
                    mRegisteredTags = readTagSet(args[0], "getRegisteredFeatureTags");
                    LogUtil.i(TAG, "Registered feature tags: " + mRegisteredTags);
                }
                if (args != null && args.length > 1 && args[1] != null) {
                    LogUtil.i(TAG, "Denied feature tags: " + args[1]);
                }
                LogUtil.i(TAG, "chatReady=" + isChatReady());
                notifyTransportReady();
                scheduleConfigRecovery();
                break;
            case "onConfigurationChanged":
                mConfiguration = (args != null && args.length > 0) ? args[0] : null;
                mConfigVersion = readConfigVersion(mConfiguration);
                LogUtil.i(TAG, "SipDelegateConfiguration updated, version=" + mConfigVersion);
                // The identity and routing values below are what every outbound request is built
                // from; log them once per version so a malformed request can be traced to its
                // source rather than guessed at.
                final SipConfigSnapshot snapshot = SipConfigSnapshot.from(mConfiguration);
                if (snapshot != null) LogUtil.i(TAG, "  " + snapshot);
                notifyTransportReady();
                break;
            case "onDestroyed":
                mCreatePending = false;
                mDelegateConnection = null;
                mConfiguration = null;
                mConfigVersion = -1;
                mRegisteredTags = Collections.emptySet();
                LogUtil.w(TAG, "SipDelegate destroyed, reason="
                        + (args != null && args.length > 0 ? args[0] : "?"));
                break;
            default:
                // onImsConfigurationChanged is a deprecated default method; nothing to do.
                break;
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> readTagSet(Object registrationState, String getter) {
        try {
            final Object res = registrationState.getClass().getMethod(getter).invoke(registrationState);
            if (res instanceof Set) {
                return Collections.unmodifiableSet(new LinkedHashSet<>((Set<String>) res));
            }
        } catch (Throwable t) {
            LogUtil.w(TAG, "readTagSet(" + getter + ") failed: " + t);
        }
        return Collections.emptySet();
    }

    private long readConfigVersion(Object config) {
        if (config == null) return -1;
        try {
            final Object v = config.getClass().getMethod("getVersion").invoke(config);
            if (v instanceof Long) return (Long) v;
            if (v instanceof Integer) return ((Integer) v).longValue();
        } catch (Throwable t) {
            LogUtil.w(TAG, "getVersion() failed: " + t);
        }
        return -1;
    }

    // ---------------------------------------------------------------- inbound messages

    private void handleMessageCallback(String name, Object[] args) {
        // onMessageSent and onMessageSendFailure must be told apart. Logging every non-received
        // callback as "sent" reports outright rejections as successes.
        if ("onMessageSent".equals(name)) {
            final String branch = (args != null && args.length > 0) ? String.valueOf(args[0]) : null;
            LogUtil.i(TAG, "SIP transmitted, branch=" + branch);
            for (SipMessageListener listener : mListeners) {
                try {
                    listener.onSipSendSuccess(branch);
                } catch (Throwable t) {
                    LogUtil.e(TAG, "Listener threw on send success", t);
                }
            }
            return;
        }
        if ("onMessageSendFailure".equals(name)) {
            final String branch = (args != null && args.length > 0) ? String.valueOf(args[0]) : null;
            int reason = 0;
            if (args != null && args.length > 1 && args[1] instanceof Integer) {
                reason = (Integer) args[1];
            }
            LogUtil.e(TAG, "SIP REJECTED by ImsService: reason=" + failureReasonToString(reason)
                    + " branch=" + branch);
            for (SipMessageListener listener : mListeners) {
                try {
                    listener.onSipSendFailure(branch, reason);
                } catch (Throwable t) {
                    LogUtil.e(TAG, "Listener threw on send failure", t);
                }
            }
            return;
        }
        if (!"onMessageReceived".equals(name)) {
            LogUtil.i(TAG, "Unhandled message callback: " + name);
            return;
        }
        final Object sipMessage = (args != null && args.length > 0) ? args[0] : null;
        if (sipMessage == null) return;

        String startLine = "";
        String headerSection = "";
        byte[] content = new byte[0];
        String viaBranch = null;
        try {
            startLine = (String) sipMessage.getClass().getMethod("getStartLine").invoke(sipMessage);
            headerSection = (String) sipMessage.getClass().getMethod("getHeaderSection").invoke(sipMessage);
            content = (byte[]) sipMessage.getClass().getMethod("getContent").invoke(sipMessage);
            viaBranch = (String) sipMessage.getClass().getMethod("getViaBranchParameter").invoke(sipMessage);
        } catch (Throwable t) {
            LogUtil.e(TAG, "Failed to read inbound SipMessage: " + describe(t));
        }

        LogUtil.i(TAG, "Inbound SIP: " + startLine);
        LogUtil.i(TAG, "[INBOUND SIP]\n" + startLine + "\n" + headerSection
                + (content.length > 0
                        ? "\n" + new String(content, java.nio.charset.StandardCharsets.UTF_8)
                        : ""));

        boolean consumed = false;
        for (SipMessageListener listener : mListeners) {
            try {
                consumed |= listener.onSipMessage(startLine, headerSection, content);
            } catch (Throwable t) {
                LogUtil.e(TAG, "Listener threw on inbound SIP message", t);
            }
        }
        if (!consumed) {
            LogUtil.i(TAG, "No listener consumed: " + startLine);
        }

        // The ImsService needs an explicit ack or it will treat delivery as failed and may tear
        // the delegate down. Ack regardless of whether a listener handled it.
        acknowledge(viaBranch);
    }

    private void acknowledge(String viaBranch) {
        final Object connection = mDelegateConnection;
        if (connection == null || viaBranch == null) return;
        try {
            connection.getClass().getMethod("notifyMessageReceived", String.class)
                    .invoke(connection, viaBranch);
        } catch (Throwable t) {
            LogUtil.w(TAG, "notifyMessageReceived failed: " + describe(t));
        }
    }

    // ---------------------------------------------------------------- outbound messages

    /**
     * Hands a SIP message to the ImsService for transmission.
     *
     * @return true if the message was accepted for sending. This is not a delivery confirmation:
     *         success arrives later via {@code onMessageSent}, failure via
     *         {@code onMessageSendFailure}.
     */
    public boolean sendSipMessage(String startLine, String headerSection, byte[] content) {
        final Object connection = mDelegateConnection;
        if (connection == null) {
            LogUtil.w(TAG, "sendSipMessage: no delegate");
            return false;
        }
        if (mConfigVersion < 0) {
            LogUtil.w(TAG, "sendSipMessage: no SipDelegateConfiguration yet");
            return false;
        }
        // SipMessage.toEncodedMessage() concatenates the start line and header section directly,
        // appending only the single CRLF that separates headers from body. The start line must
        // therefore carry its own terminator, or the request line runs into the first header and
        // the message is rejected with MESSAGE_FAILURE_REASON_INVALID_START_LINE before it ever
        // reaches the network.
        final String terminatedStartLine =
                startLine.endsWith("\r\n") ? startLine : startLine + "\r\n";
        try {
            final Class<?> sipMessageClass = Class.forName(CLS_SIP_MESSAGE);
            final Object message = sipMessageClass
                    .getConstructor(String.class, String.class, byte[].class)
                    .newInstance(terminatedStartLine, headerSection,
                            content == null ? new byte[0] : content);
            connection.getClass().getMethod("sendMessage", sipMessageClass, long.class)
                    .invoke(connection, message, mConfigVersion);
            LogUtil.i(TAG, "Sent SIP: " + startLine.trim());
            LogUtil.i(TAG, "[OUTBOUND SIP]\n" + terminatedStartLine + headerSection
                    + (content != null && content.length > 0
                            ? "\n" + new String(content, java.nio.charset.StandardCharsets.UTF_8)
                            : ""));
            return true;
        } catch (Throwable t) {
            LogUtil.e(TAG, "sendSipMessage failed: " + describe(t));
            return false;
        }
    }

    /** Tells the ImsService a dialog is finished so it can release its resources. */
    public void cleanupSession(String callId) {
        final Object connection = mDelegateConnection;
        if (connection == null || callId == null) return;
        try {
            connection.getClass().getMethod("cleanupSession", String.class).invoke(connection, callId);
        } catch (Throwable t) {
            LogUtil.w(TAG, "cleanupSession failed: " + describe(t));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Object defaultReturn(Method method) {
        final Class<?> r = method.getReturnType();
        if (!r.isPrimitive() || r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        return null;
    }

    private static String describe(Throwable t) {
        final Throwable cause =
                (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
        return cause.getClass().getName() + ": " + cause.getMessage();
    }

    private static int resolveSubId(Context context) {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (SubscriptionManager.isValidSubscriptionId(subId)) return subId;
        subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (SubscriptionManager.isValidSubscriptionId(subId)) return subId;
        try {
            final SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
            final List<?> active = (sm != null) ? sm.getActiveSubscriptionInfoList() : null;
            if (active != null && !active.isEmpty()) {
                return (int) active.get(0).getClass().getMethod("getSubscriptionId")
                        .invoke(active.get(0));
            }
        } catch (Exception ignored) {}
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }
}
