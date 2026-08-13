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

package com.android.messaging.rcs.chat;

import android.content.Context;

import com.android.messaging.rcs.msrp.MsrpSession;
import com.android.messaging.rcs.sip.SipConfigSnapshot;
import com.android.messaging.util.LogUtil;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * One RCS session-mode chat dialog: the SIP INVITE dialog plus the MSRP connection it negotiates.
 *
 * <p>This carrier authorizes session mode only ({@code ChatAuth=1}, {@code standaloneMsgAuth=0}),
 * so a message cannot simply be posted as a SIP MESSAGE. Every message rides an MSRP session that
 * an INVITE established.
 */
public class RcsChatSession {
    private static final String TAG = "RcsChatSession";

    /** Feature tag identifying session-mode CPM chat, required in Contact and Accept-Contact. */
    static final String ICSI_CHAT_SESSION =
            "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"";

    /**
     * Feature tag an endpoint advertises to say it will interwork the session to SMS.
     *
     * <p>The carrier gateway puts this in the Contact of its 200 OK; it is what a Reject-Contact
     * header names to keep the request away from it.
     */
    static final String FEATURE_TAG_MSG_FALLBACK = "+g.gsma.rcs.msgfallback";

    public enum State { IDLE, INVITING, ESTABLISHED, TERMINATING, CLOSED }

    /** A message queued before the MSRP session was ready. */
    private static final class Pending {
        final String messageId;
        final String text;
        Pending(String messageId, String text) {
            this.messageId = messageId;
            this.text = text;
        }
    }

    public interface Callback {
        void onIncomingText(RcsChatSession session, String fromUri, String messageId, String text);
        void onSessionEstablished(RcsChatSession session);
        void onSessionClosed(RcsChatSession session, String reason);
        void onMessageSent(RcsChatSession session, String messageId);
        void onMessageFailed(RcsChatSession session, String messageId, String reason);
    }

    private final Context mContext;
    private final ChatSipTransport mTransport;
    private final Callback mCallback;
    private final boolean mIncoming;

    /**
     * Request-URI forms to try, in order.
     *
     * <p>The vendor stack rejects an INVITE it dislikes with
     * MESSAGE_FAILURE_REASON_INVALID_START_LINE before anything reaches the network, and gives no
     * indication of which part it objected to. Rather than burn a build cycle per guess, a
     * rejected start line advances to the next form and re-sends.
     */
    private final List<String> mRequestUriCandidates = new ArrayList<>();
    private int mRequestUriIndex;

    private String mRemoteUri;
    private final String mCallId;
    private final String mLocalTag;
    private String mRemoteTag;
    private long mLocalCSeq = 1;

    private final String mMsrpSessionId;
    private String mLocalMsrpPath;
    private String mRemoteMsrpPath;
    private ServerSocket mListener;

    private MsrpSession mMsrp;
    private volatile State mState = State.IDLE;
    private final Deque<Pending> mPending = new ArrayDeque<>();

    /**
     * Fails a session that never reaches ESTABLISHED. Without this, an INVITE that draws no
     * response at all leaves the session in INVITING forever, and every later message for that
     * contact is queued onto it and silently swallowed — no send, no failure, no fallback.
     */
    private static final java.util.concurrent.ScheduledExecutorService sTimers =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private static final long INVITE_TIMEOUT_SECONDS = 32;
    private java.util.concurrent.ScheduledFuture<?> mInviteTimeout;

    /** Route set captured from the dialog-establishing exchange. */
    private final List<String> mRouteSet = new ArrayList<>();
    private String mRemoteTarget;

    RcsChatSession(Context context, ChatSipTransport transport, String remoteUri, String callId,
            boolean incoming, Callback callback) {
        mContext = context.getApplicationContext();
        mTransport = transport;
        mRemoteUri = remoteUri;
        mCallId = callId != null ? callId : UUID.randomUUID().toString();
        mIncoming = incoming;
        mCallback = callback;
        mLocalTag = randomToken(8);
        mMsrpSessionId = randomToken(12);
        buildRequestUriCandidates(remoteUri);
    }

    /** Derives the alternative Request-URI spellings from the primary one. */
    private void buildRequestUriCandidates(String primary) {
        mRequestUriCandidates.add(primary);
        // Derive the sip: spellings as fallbacks when the primary is a tel URI.
        if (primary != null && primary.startsWith("tel:")) {
            final SipConfigSnapshot cfg = mTransport.getConfig();
            final String domain = (cfg != null) ? cfg.homeDomain : null;
            if (domain != null) {
                final String user = primary.substring(4);
                addCandidate("sip:" + user + "@" + domain + ";user=phone");
                addCandidate("sip:" + user + "@" + domain);
            }
            return;
        }
        if (primary == null || !primary.startsWith("sip:")) return;

        final String withoutScheme = primary.substring(4);
        final int at = withoutScheme.indexOf('@');
        if (at < 0) return;
        final String user = withoutScheme.substring(0, at);
        String domain = withoutScheme.substring(at + 1);
        final int semi = domain.indexOf(';');
        if (semi >= 0) domain = domain.substring(0, semi);

        addCandidate("sip:" + user + "@" + domain + ";user=phone");
        addCandidate("sip:" + user + "@" + domain);
    }

    private void addCandidate(String uri) {
        if (uri != null && !mRequestUriCandidates.contains(uri)) {
            mRequestUriCandidates.add(uri);
        }
    }

    /** How many times a session will wait out a temporary transport failure before giving up. */
    static final int MAX_TRANSPORT_RETRIES = 3;

    private int mTransportRetries;

    /**
     * Records that the request was refused for a passing reason and the session should wait.
     *
     * @return true if another attempt is allowed once the transport recovers
     */
    boolean deferUntilTransportReady() {
        cancelInviteTimeout();
        if (mTransportRetries >= MAX_TRANSPORT_RETRIES) return false;
        mTransportRetries++;
        mState = State.IDLE;
        return true;
    }

    /** Re-sends the INVITE unchanged, for a failure that was about the transport, not the request. */
    void retrySameRequestUri() {
        cancelInviteTimeout();
        mState = State.IDLE;
        LogUtil.i(TAG, "Resending INVITE to " + mRemoteUri + " (transport retry "
                + mTransportRetries + "/" + MAX_TRANSPORT_RETRIES + ")");
        start();
    }

    /**
     * Switches to the next Request-URI spelling after a rejected start line.
     *
     * @return true if another form was available and a fresh INVITE was sent
     */
    boolean retryWithNextRequestUri() {
        cancelInviteTimeout();
        if (mRequestUriIndex + 1 >= mRequestUriCandidates.size()) {
            LogUtil.w(TAG, "No Request-URI forms left to try for " + mRemoteUri);
            return false;
        }
        mRequestUriIndex++;
        mRemoteUri = mRequestUriCandidates.get(mRequestUriIndex);
        // The sequence number must keep climbing. Restarting it at 1 reused the Call-ID, From tag
        // and CSeq of the attempt that just failed, which is precisely a retransmission of that
        // INVITE — the proxy matches the completed transaction and replays its rejection instead
        // of routing the new Request-URI. Harmless while this path was only reached for a start
        // line the ImsService never transmitted; not harmless now that a 480 reaches it.
        mState = State.IDLE;
        LogUtil.i(TAG, "Retrying with Request-URI form "
                + (mRequestUriIndex + 1) + "/" + mRequestUriCandidates.size() + ": " + mRemoteUri);
        start();
        return true;
    }

    public String getCallId() {
        return mCallId;
    }

    public String getRemoteUri() {
        return mRemoteUri;
    }

    public State getState() {
        return mState;
    }

    public boolean isEstablished() {
        return mState == State.ESTABLISHED;
    }

    String getLocalTag() {
        return mLocalTag;
    }

    void setRemoteTag(String tag) {
        mRemoteTag = tag;
    }

    String getRemoteTag() {
        return mRemoteTag;
    }

    void setRemoteTarget(String target) {
        mRemoteTarget = target;
    }

    void setRouteSet(List<String> routeSet) {
        mRouteSet.clear();
        if (routeSet != null) mRouteSet.addAll(routeSet);
    }

    long nextCSeq() {
        return mLocalCSeq++;
    }

    long currentCSeq() {
        return mLocalCSeq;
    }

    List<String> getRouteSet() {
        return mRouteSet;
    }

    String getRemoteTarget() {
        return mRemoteTarget != null ? mRemoteTarget : mRemoteUri;
    }

    // ---------------------------------------------------------------- outgoing setup

    /**
     * Queues a message and starts the session if it is not already coming up.
     */
    public void enqueueText(String messageId, String text) {
        synchronized (mPending) {
            mPending.add(new Pending(messageId, text));
        }
        if (mState == State.ESTABLISHED) {
            flushPending();
        } else if (mState == State.IDLE) {
            start();
        }
    }

    /** Sends the initial INVITE. */
    public void start() {
        final SipConfigSnapshot config = mTransport.getConfig();
        if (config == null) {
            fail("No SipDelegateConfiguration available");
            return;
        }
        final String localIp = config.localIpLiteral();
        if (localIp == null) {
            fail("No local address in SipDelegateConfiguration");
            return;
        }

        // Bind the listener up front so the SDP can advertise a port we genuinely hold. We offer
        // the active role, but a peer that insists on active needs somewhere to connect.
        int localPort;
        try {
            mListener = new ServerSocket(0);
            localPort = mListener.getLocalPort();
        } catch (Exception e) {
            LogUtil.w(TAG, "Could not bind local MSRP listener; advertising port 9", e);
            localPort = 9;
        }
        mLocalMsrpPath = buildMsrpPath(localIp, localPort, mMsrpSessionId);

        final String sdp = MsrpSdp.build(localIp, localPort, mLocalMsrpPath, true);
        mState = State.INVITING;
        LogUtil.i(TAG, "INVITE -> " + mRemoteUri + " callId=" + mCallId);
        if (!mTransport.sendInvite(this, sdp.getBytes(StandardCharsets.UTF_8))) {
            fail("Failed to hand INVITE to the SIP delegate");
            return;
        }
        mInviteTimeout = sTimers.schedule(() -> {
            if (mState == State.INVITING) {
                fail("No response to INVITE within " + INVITE_TIMEOUT_SECONDS + "s");
            }
        }, INVITE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void cancelInviteTimeout() {
        if (mInviteTimeout != null) {
            mInviteTimeout.cancel(false);
            mInviteTimeout = null;
        }
    }

    // ---------------------------------------------------------------- responses

    /** Handles a final response to our INVITE. */
    void onInviteResponse(int status, SipConfigSnapshot config, String remoteSdp) {
        if (status >= 100 && status < 200) {
            LogUtil.i(TAG, "INVITE provisional " + status);
            return;
        }
        cancelInviteTimeout();
        if (status >= 300) {
            fail("INVITE rejected with " + status);
            return;
        }

        LogUtil.i(TAG, "INVITE 200 OK; negotiating MSRP");
        final MsrpSdp.Parsed answer = MsrpSdp.parse(remoteSdp);
        mRemoteMsrpPath = answer.path;
        if (mRemoteMsrpPath == null) {
            fail("200 OK carried no a=path");
            return;
        }
        openMsrp(answer, /* weAreActive= */ answer.peerIsPassive());
    }

    /** Handles an inbound INVITE that we are accepting. */
    void onIncomingInvite(String remoteSdp) {
        final SipConfigSnapshot config = mTransport.getConfig();
        if (config == null) {
            fail("No SipDelegateConfiguration available");
            return;
        }
        final MsrpSdp.Parsed offer = MsrpSdp.parse(remoteSdp);
        mRemoteMsrpPath = offer.path;

        final String localIp = config.localIpLiteral();
        int localPort = 9;
        // If the peer took the active role we must be reachable, so hold a real port.
        final boolean weAreActive = offer.peerIsPassive();
        if (!weAreActive) {
            try {
                mListener = new ServerSocket(0);
                localPort = mListener.getLocalPort();
            } catch (Exception e) {
                LogUtil.e(TAG, "Cannot bind MSRP listener for inbound session", e);
            }
        }
        mLocalMsrpPath = buildMsrpPath(localIp, localPort, mMsrpSessionId);

        final String sdp = MsrpSdp.build(localIp, localPort, mLocalMsrpPath, weAreActive);
        if (!mTransport.sendInviteOk(this, sdp.getBytes(StandardCharsets.UTF_8))) {
            fail("Failed to answer inbound INVITE");
            return;
        }
        openMsrp(offer, weAreActive);
    }

    private void openMsrp(MsrpSdp.Parsed peer, boolean weAreActive) {
        final String host = peer.host();
        final int port = peer.port();
        if (weAreActive && (host == null || port <= 0)) {
            fail("Peer SDP has no usable MSRP endpoint");
            return;
        }

        final SipConfigSnapshot config = mTransport.getConfig();
        final java.net.InetAddress localAddr =
                (config != null && config.localAddress != null)
                        ? config.localAddress.getAddress() : null;

        mMsrp = new MsrpSession(mContext, mLocalMsrpPath, mRemoteMsrpPath, host, port, weAreActive,
                localAddr,
                new MsrpSession.MsrpListener() {
                    @Override
                    public void onSessionOpened() {
                        mState = State.ESTABLISHED;
                        LogUtil.i(TAG, "Chat session established with " + mRemoteUri);
                        if (mCallback != null) mCallback.onSessionEstablished(RcsChatSession.this);
                        flushPending();
                    }

                    @Override
                    public void onMessage(String contentType, byte[] body) {
                        handleIncomingPayload(contentType, body);
                    }

                    @Override
                    public void onSessionClosed(String reason) {
                        if (mState != State.CLOSED) {
                            mState = State.CLOSED;
                            failPending("MSRP session closed: " + reason);
                            if (mCallback != null) {
                                mCallback.onSessionClosed(RcsChatSession.this, reason);
                            }
                        }
                    }
                });
        mMsrp.open(weAreActive ? null : mListener);
    }

    private void handleIncomingPayload(String contentType, byte[] body) {
        final String raw = new String(body, StandardCharsets.UTF_8);
        if (contentType != null && contentType.toLowerCase().startsWith("message/cpim")) {
            final com.android.messaging.rcs.sip.CpimParser.CpimMessage cpim =
                    com.android.messaging.rcs.sip.CpimParser.parseCpim(raw);
            if (cpim == null || cpim.body == null || cpim.body.isEmpty()) {
                LogUtil.w(TAG, "Unparseable CPIM payload");
                return;
            }
            final String from = cpim.fromUri != null ? cpim.fromUri : mRemoteUri;
            if (mCallback != null) {
                mCallback.onIncomingText(this, from, cpim.messageId, cpim.body);
            }
        } else if (contentType != null && contentType.contains("im-iscomposing")) {
            LogUtil.i(TAG, "Peer is-composing indication");
        } else {
            LogUtil.i(TAG, "Ignoring MSRP payload of type " + contentType);
        }
    }

    // ---------------------------------------------------------------- sending

    private void flushPending() {
        if (mMsrp == null || !mMsrp.isOpen()) return;
        final SipConfigSnapshot config = mTransport.getConfig();
        // Prefer the dialable identity: CPIM headers are end to end, and the registered IMPU on
        // this carrier is IMSI-derived and cannot be attributed or fallen back to.
        String localAor = SipConfigSnapshot.localTelUri(mContext);
        if (localAor == null) {
            // Still not the raw IMPU: originatingAor() picks a dialable associated identity and
            // only falls back to the IMSI form when the network offered nothing better.
            localAor = config != null ? config.originatingAor(mContext) : "sip:anonymous@invalid";
        }
        LogUtil.i(TAG, "CPIM From: " + localAor);

        while (true) {
            final Pending next;
            synchronized (mPending) {
                next = mPending.poll();
            }
            if (next == null) return;

            // The CPIM To must be the recipient's own address. mRemoteUri is a request URI in
            // *our* home domain (sip:+1...@msg.pc.t-mobile.com), which misidentifies anyone on
            // another carrier — the recipient here is on AT&T. A tel URI is carrier-neutral and is
            // what an interworking gateway can route on.
            final String cpimTo = toTelUri(mRemoteUri);
            LogUtil.i(TAG, "CPIM From=" + localAor + " To=" + cpimTo);
            final String cpim = com.android.messaging.rcs.sip.CpimParser.formatCpimMessage(
                    localAor, cpimTo, next.messageId, next.text);
            final boolean ok = mMsrp.sendMessage(next.messageId, "message/cpim",
                    cpim.getBytes(StandardCharsets.UTF_8));
            if (mCallback != null) {
                if (ok) {
                    mCallback.onMessageSent(this, next.messageId);
                } else {
                    mCallback.onMessageFailed(this, next.messageId, "MSRP send failed");
                }
            }
        }
    }

    public boolean sendIsComposing(boolean typing) {
        return mMsrp != null && mMsrp.isOpen() && mMsrp.sendIsComposing(typing);
    }

    // ---------------------------------------------------------------- teardown

    public void terminate(String reason) {
        cancelInviteTimeout();
        if (mState == State.CLOSED || mState == State.TERMINATING) return;
        mState = State.TERMINATING;
        LogUtil.i(TAG, "Terminating session " + mCallId + ": " + reason);
        if (mMsrp != null) mMsrp.close(reason);
        try { if (mListener != null) mListener.close(); } catch (Exception ignored) {}
        mTransport.sendBye(this);
        mState = State.CLOSED;
        failPending(reason);
        if (mCallback != null) mCallback.onSessionClosed(this, reason);
    }

    /** Called when the peer sent BYE; no BYE of our own is owed. */
    void onRemoteBye() {
        cancelInviteTimeout();
        mState = State.CLOSED;
        if (mMsrp != null) mMsrp.close("remote BYE");
        try { if (mListener != null) mListener.close(); } catch (Exception ignored) {}
        failPending("remote hung up");
        if (mCallback != null) mCallback.onSessionClosed(this, "remote BYE");
    }

    private void fail(String reason) {
        cancelInviteTimeout();
        if (mState == State.CLOSED) return;
        LogUtil.e(TAG, "Session " + mCallId + " failed: " + reason);
        mState = State.CLOSED;
        try { if (mListener != null) mListener.close(); } catch (Exception ignored) {}
        failPending(reason);
        if (mCallback != null) mCallback.onSessionClosed(this, reason);
    }

    private void failPending(String reason) {
        while (true) {
            final Pending next;
            synchronized (mPending) {
                next = mPending.poll();
            }
            if (next == null) return;
            if (mCallback != null) mCallback.onMessageFailed(this, next.messageId, reason);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Reduces a SIP or tel URI to a carrier-neutral {@code tel:+E164} form. */
    static String toTelUri(String uri) {
        if (uri == null) return null;
        String s = uri.trim();
        if (s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length() - 1);
        if (s.startsWith("sip:") || s.startsWith("sips:") || s.startsWith("tel:")) {
            s = s.substring(s.indexOf(':') + 1);
        }
        final int at = s.indexOf('@');
        if (at >= 0) s = s.substring(0, at);
        final int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi);
        final String digits = s.replaceAll("[^0-9+]", "");
        if (digits.isEmpty()) return uri;
        return digits.startsWith("+") ? "tel:" + digits : "tel:+" + digits;
    }

    private static String buildMsrpPath(String localIp, int port, String sessionId) {
        final String host = SipConfigSnapshot.formatHost(localIp);
        return "msrp://" + host + ":" + port + "/" + sessionId + ";tcp";
    }

    static String randomToken(int length) {
        final String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.substring(0, Math.min(length, hex.length()));
    }
}
