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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * One-shot diagnostic for IMS Single Registration (RCS VoLTE Single Registration).
 *
 * <p>Answers, from the device rather than from documentation, whether this app can obtain a
 * {@code SipDelegate} from the bound ImsService and which Universal Profile feature tags the
 * carrier will actually register for us. That is the deciding factor for whether a real RCS
 * messaging transport is reachable from app code at all.
 *
 * <p>Everything here goes through reflection because the SipDelegate API surface is
 * {@code @SystemApi} and this app builds against the public SDK
 * (see Android.bp {@code sdk_version: "current"}).
 */
public final class SipDelegateProbe {
    private static final String TAG = "SipDelegateProbe";

    private static final String CLS_DELEGATE_REQUEST = "android.telephony.ims.DelegateRequest";
    private static final String CLS_STATE_CALLBACK =
            "android.telephony.ims.stub.DelegateConnectionStateCallback";
    private static final String CLS_MESSAGE_CALLBACK =
            "android.telephony.ims.stub.DelegateConnectionMessageCallback";

    /**
     * Universal Profile messaging feature tags, taken verbatim from this carrier's
     * {@code ims.rcs_feature_tag_allowed_string_array}. Tags the carrier refuses come back in
     * the deregistered set with a reason, which is exactly the diagnostic we want.
     */
    private static final String[] REQUESTED_FEATURE_TAGS = new String[] {
            "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg\"",
            "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"",
            "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.largemsg\"",
            "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.filetransfer\"",
            "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.fthttp\"",
            "+g.gsma.rcs.cpm.pager-large",
    };

    /** Held so the delegate is not torn down the moment this method returns. */
    private static volatile Object sDelegateConnection;
    private static volatile boolean sHasRun;

    private SipDelegateProbe() {}

    public static synchronized void runOnce(Context context) {
        if (sHasRun) return;
        sHasRun = true;
        try {
            probe(context.getApplicationContext());
        } catch (Throwable t) {
            LogUtil.e(TAG, "probe threw", t);
        }
    }

    private static void probe(Context context) {
        LogUtil.i(TAG, "===== SIP DELEGATE PROBE START =====");

        final int subId = resolveSubId(context);
        LogUtil.i(TAG, "Using subId=" + subId);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            LogUtil.e(TAG, "No usable subscription; aborting");
            LogUtil.i(TAG, "===== SIP DELEGATE PROBE END (no sub) =====");
            return;
        }

        final ImsManager imsManager =
                (ImsManager) context.getSystemService(Context.TELEPHONY_IMS_SERVICE);
        if (imsManager == null) {
            LogUtil.e(TAG, "TELEPHONY_IMS_SERVICE unavailable; aborting");
            LogUtil.i(TAG, "===== SIP DELEGATE PROBE END (no ImsManager) =====");
            return;
        }

        final Object sipDelegateManager;
        try {
            final Method get = ImsManager.class.getMethod("getSipDelegateManager", int.class);
            sipDelegateManager = get.invoke(imsManager, subId);
        } catch (Throwable t) {
            LogUtil.e(TAG, "getSipDelegateManager() unavailable: " + describe(t));
            LogUtil.i(TAG, "===== SIP DELEGATE PROBE END (no SipDelegateManager) =====");
            return;
        }
        if (sipDelegateManager == null) {
            LogUtil.e(TAG, "getSipDelegateManager() returned null");
            LogUtil.i(TAG, "===== SIP DELEGATE PROBE END (null manager) =====");
            return;
        }

        boolean supported = false;
        try {
            final Object res = sipDelegateManager.getClass().getMethod("isSupported")
                    .invoke(sipDelegateManager);
            supported = (res instanceof Boolean) && (Boolean) res;
            LogUtil.i(TAG, "SipDelegateManager.isSupported() = " + supported);
        } catch (Throwable t) {
            // ImsException carries the useful detail; unwrap it.
            LogUtil.e(TAG, "isSupported() failed: " + describe(t));
            LogUtil.i(TAG, "===== SIP DELEGATE PROBE END (isSupported threw) =====");
            return;
        }

        if (!supported) {
            LogUtil.w(TAG, "Single registration is NOT supported for this sub. "
                    + "Check `cmd phone src get-carrier-enabled -s 0` and the ImsService's "
                    + "CAPABILITY_SIP_DELEGATE_CREATION.");
            LogUtil.i(TAG, "===== SIP DELEGATE PROBE END (unsupported) =====");
            return;
        }

        createDelegate(context, sipDelegateManager);
    }

    private static void createDelegate(Context context, Object sipDelegateManager) {
        try {
            final Set<String> tags = new LinkedHashSet<>(Arrays.asList(REQUESTED_FEATURE_TAGS));
            LogUtil.i(TAG, "Requesting " + tags.size() + " feature tags:");
            for (String tag : tags) {
                LogUtil.i(TAG, "   " + tag);
            }

            final Class<?> requestClass = Class.forName(CLS_DELEGATE_REQUEST);
            final Object request = requestClass.getConstructor(Set.class).newInstance(tags);

            final Class<?> stateCbClass = Class.forName(CLS_STATE_CALLBACK);
            final Class<?> msgCbClass = Class.forName(CLS_MESSAGE_CALLBACK);

            final Object stateCallback = Proxy.newProxyInstance(
                    context.getClassLoader(), new Class<?>[] { stateCbClass },
                    (proxy, method, args) -> {
                        handleStateCallback(method.getName(), args);
                        return defaultReturn(method);
                    });

            final Object messageCallback = Proxy.newProxyInstance(
                    context.getClassLoader(), new Class<?>[] { msgCbClass },
                    (proxy, method, args) -> {
                        LogUtil.i(TAG, "[MSG CALLBACK] " + method.getName()
                                + " -> " + (args != null && args.length > 0 ? args[0] : "(no arg)"));
                        return defaultReturn(method);
                    });

            final Executor executor = context.getMainExecutor();
            final Method create = sipDelegateManager.getClass().getMethod(
                    "createSipDelegate", requestClass, Executor.class, stateCbClass, msgCbClass);

            LogUtil.i(TAG, "Calling createSipDelegate()...");
            create.invoke(sipDelegateManager, request, executor, stateCallback, messageCallback);
            LogUtil.i(TAG, "createSipDelegate() dispatched; awaiting callbacks");
        } catch (Throwable t) {
            LogUtil.e(TAG, "createSipDelegate() failed: " + describe(t));
        }
        LogUtil.i(TAG, "===== SIP DELEGATE PROBE END (awaiting async callbacks) =====");
    }

    private static void handleStateCallback(String name, Object[] args) {
        switch (name) {
            case "onCreated":
                sDelegateConnection = (args != null && args.length > 0) ? args[0] : null;
                LogUtil.i(TAG, "[STATE] onCreated -> SIP DELEGATE GRANTED: " + sDelegateConnection);
                break;
            case "onFeatureTagStatusChanged":
                LogUtil.i(TAG, "[STATE] onFeatureTagStatusChanged");
                if (args != null && args.length > 0 && args[0] != null) {
                    logRegistrationState(args[0]);
                }
                if (args != null && args.length > 1) {
                    LogUtil.i(TAG, "   deniedTags = " + args[1]);
                }
                break;
            case "onDestroyed":
                sDelegateConnection = null;
                LogUtil.w(TAG, "[STATE] onDestroyed reason="
                        + (args != null && args.length > 0 ? args[0] : "?"));
                break;
            default:
                LogUtil.i(TAG, "[STATE] " + name
                        + (args != null && args.length > 0 ? " -> " + args[0] : ""));
                break;
        }
    }

    private static void logRegistrationState(Object state) {
        for (String getter : new String[] {
                "getRegisteredFeatureTags", "getRegisteringFeatureTags",
                "getDeregisteredFeatureTags", "getDeregisteringFeatureTags" }) {
            try {
                LogUtil.i(TAG, "   " + getter + "() = "
                        + state.getClass().getMethod(getter).invoke(state));
            } catch (Throwable t) {
                LogUtil.w(TAG, "   " + getter + "() unavailable: " + t);
            }
        }
    }

    /** Proxies must return a type-correct value for primitive-returning methods. */
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
        String detail = cause.getClass().getName() + ": " + cause.getMessage();
        try {
            final Object code = cause.getClass().getMethod("getCode").invoke(cause);
            detail += " (code=" + code + ")";
        } catch (Throwable ignored) {
            // Not an ImsException; the message alone is the whole story.
        }
        return detail;
    }

    private static int resolveSubId(Context context) {
        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (SubscriptionManager.isValidSubscriptionId(subId)) return subId;
        subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (SubscriptionManager.isValidSubscriptionId(subId)) return subId;
        try {
            final SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
            if (sm != null && sm.getActiveSubscriptionInfoList() != null
                    && !sm.getActiveSubscriptionInfoList().isEmpty()) {
                return sm.getActiveSubscriptionInfoList().get(0).getSubscriptionId();
            }
        } catch (Exception ignored) {}
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }
}
