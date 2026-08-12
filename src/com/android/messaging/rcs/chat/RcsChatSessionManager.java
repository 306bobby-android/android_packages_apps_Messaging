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
import android.text.TextUtils;

import com.android.messaging.rcs.binding.RcsMessageReceiver;
import com.android.messaging.rcs.sip.SipConfigSnapshot;
import com.android.messaging.rcs.sip.SipDelegateTransport;
import com.android.messaging.rcs.sip.SipHeaders;
import com.android.messaging.util.LogUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes SIP traffic between the {@link SipDelegateTransport} and per-conversation
 * {@link RcsChatSession}s, and builds the SIP requests and responses those sessions need.
 *
 * <p>This is the piece that finally makes inbound RCS possible: the delegate hands us SIP requests
 * and we deliver reassembled messages into the Bugle database via {@link RcsMessageReceiver}.
 */
public class RcsChatSessionManager
        implements SipDelegateTransport.SipMessageListener, ChatSipTransport {
    private static final String TAG = "RcsChatSessionManager";

    private static RcsChatSessionManager sInstance;

    private final Context mContext;
    private SipDelegateTransport mTransport;

    /** Active dialogs by Call-ID. */
    private final Map<String, RcsChatSession> mSessionsByCallId = new ConcurrentHashMap<>();
    /** Outgoing session per normalized remote URI, so repeated messages reuse one dialog. */
    private final Map<String, RcsChatSession> mSessionsByRemote = new ConcurrentHashMap<>();
    /** Headers of an inbound INVITE, retained so the 200 OK can echo them. */
    private final Map<String, SipHeaders> mInboundInvites = new ConcurrentHashMap<>();
    /** Via branch of each in-flight request, so send outcomes can be routed to their session. */
    private final Map<String, RcsChatSession> mSessionsByBranch = new ConcurrentHashMap<>();

    private final RcsChatSession.Callback mSessionCallback = new RcsChatSession.Callback() {
        @Override
        public void onIncomingText(RcsChatSession session, String fromUri, String messageId,
                String text) {
            LogUtil.i(TAG, "Incoming RCS text from " + fromUri + " (" + text.length() + " chars)");
            RcsMessageReceiver.onTextReceived(mContext, fromUri, messageId, text);
        }

        @Override
        public void onSessionEstablished(RcsChatSession session) {
            LogUtil.i(TAG, "Session established: " + session.getCallId());
        }

        @Override
        public void onSessionClosed(RcsChatSession session, String reason) {
            LogUtil.i(TAG, "Session closed: " + session.getCallId() + " (" + reason + ")");
            mSessionsByCallId.remove(session.getCallId());
            mSessionsByRemote.remove(normalizeUri(session.getRemoteUri()));
            mInboundInvites.remove(session.getCallId());
            mSessionsByBranch.values().remove(session);
            if (mTransport != null) mTransport.cleanupSession(session.getCallId());
        }

        @Override
        public void onMessageSent(RcsChatSession session, String messageId) {
            LogUtil.i(TAG, "Message handed to MSRP: " + messageId);
        }

        @Override
        public void onMessageFailed(RcsChatSession session, String messageId, String reason) {
            LogUtil.w(TAG, "Message failed: " + messageId + " (" + reason + ")");
        }
    };

    private RcsChatSessionManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public static synchronized RcsChatSessionManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new RcsChatSessionManager(context);
        }
        return sInstance;
    }

    public void attach(SipDelegateTransport transport) {
        if (mTransport == transport) return;
        if (mTransport != null) mTransport.removeListener(this);
        mTransport = transport;
        transport.addListener(this);
        LogUtil.i(TAG, "Attached to SIP delegate transport");
    }

    // ---------------------------------------------------------------- public API

    /**
     * Sends a text message, establishing a chat session first if necessary.
     *
     * @return true if the message was accepted for delivery. Delivery itself is asynchronous.
     */
    public boolean sendText(String destination, String messageId, String text) {
        if (mTransport == null || !mTransport.isChatReady()) {
            LogUtil.w(TAG, "sendText: chat transport not ready");
            return false;
        }
        final SipConfigSnapshot config = getConfig();
        if (config == null) {
            LogUtil.w(TAG, "sendText: no delegate configuration");
            return false;
        }
        final String remoteUri = toSipUri(mContext, destination, config.homeDomain);
        if (remoteUri == null) {
            LogUtil.w(TAG, "sendText: cannot build a request URI for " + destination);
            return false;
        }

        RcsChatSession session = mSessionsByRemote.get(normalizeUri(remoteUri));
        if (session == null || session.getState() == RcsChatSession.State.CLOSED) {
            session = new RcsChatSession(mContext, this, remoteUri, UUID.randomUUID().toString(),
                    false, mSessionCallback);
            mSessionsByCallId.put(session.getCallId(), session);
            mSessionsByRemote.put(normalizeUri(remoteUri), session);
        }
        session.enqueueText(messageId, text);
        return true;
    }

    // ---------------------------------------------------------------- inbound SIP

    @Override
    public boolean onSipMessage(String startLine, String headerSection, byte[] content) {
        if (TextUtils.isEmpty(startLine)) return false;
        final SipHeaders headers = SipHeaders.parse(headerSection);
        final String callId = headers.getCallId();
        final String body = content == null ? "" : new String(content, StandardCharsets.UTF_8);

        if (startLine.startsWith("SIP/2.0")) {
            return handleResponse(startLine, headers, body, callId);
        }
        return handleRequest(startLine, headers, body, callId);
    }

    @Override
    public void onSipSendFailure(String viaBranch, int reason) {
        final RcsChatSession session =
                (viaBranch != null) ? mSessionsByBranch.remove(viaBranch) : null;
        if (session == null) return;
        if (session.retryWithNextRequestUri()) {
            // Any refusal is worth retrying in another URI form, not just a malformed start
            // line: the network may simply dislike how the target was addressed.
            return;
        }
        session.terminate("ImsService rejected the request (reason " + reason + ")");
    }

    @Override
    public void onSipSendSuccess(String viaBranch) {
        if (viaBranch != null) mSessionsByBranch.remove(viaBranch);
    }

    private boolean handleResponse(String startLine, SipHeaders headers, String body,
            String callId) {
        final RcsChatSession session = (callId != null) ? mSessionsByCallId.get(callId) : null;
        if (session == null) return false;

        int status = -1;
        final String[] parts = startLine.split("\\s+", 3);
        if (parts.length >= 2) {
            try {
                status = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        final String method = headers.getCSeqMethod();
        if (!"INVITE".equals(method)) {
            LogUtil.i(TAG, "Response " + status + " to " + method + " on " + callId);
            return true;
        }

        if (status >= 200 && status < 300) {
            session.setRemoteTag(headers.getToTag());
            final String contact = SipHeaders.uriOf(headers.getFirst("contact"));
            if (contact != null) session.setRemoteTarget(contact);
            session.setRouteSet(reverse(headers.getAll("record-route")));
            sendAck(session, headers);
        }
        session.onInviteResponse(status, getConfig(), body);
        return true;
    }

    private boolean handleRequest(String startLine, SipHeaders headers, String body,
            String callId) {
        final String method = startLine.split("\\s+", 2)[0].toUpperCase(Locale.US);
        switch (method) {
            case "INVITE":
                return handleIncomingInvite(headers, body, callId);
            case "BYE": {
                final RcsChatSession session =
                        (callId != null) ? mSessionsByCallId.get(callId) : null;
                sendSimpleResponse(headers, 200, "OK");
                if (session != null) {
                    session.onRemoteBye();
                    return true;
                }
                return false;
            }
            case "ACK":
                // Dialog is confirmed; MSRP setup already began when we answered.
                return callId != null && mSessionsByCallId.containsKey(callId);
            case "CANCEL": {
                final RcsChatSession session =
                        (callId != null) ? mSessionsByCallId.get(callId) : null;
                sendSimpleResponse(headers, 200, "OK");
                if (session != null) session.terminate("peer cancelled");
                return session != null;
            }
            case "OPTIONS":
                sendSimpleResponse(headers, 200, "OK");
                return true;
            case "MESSAGE":
                // Pager mode is denied by this carrier, but answer politely if one arrives.
                sendSimpleResponse(headers, 200, "OK");
                LogUtil.i(TAG, "Received standalone SIP MESSAGE; handling as CPIM");
                RcsMessageReceiver.onCpimReceived(mContext, body);
                return true;
            default:
                return false;
        }
    }

    private boolean handleIncomingInvite(SipHeaders headers, String body, String callId) {
        if (callId == null) return false;
        if (mSessionsByCallId.containsKey(callId)) {
            LogUtil.i(TAG, "Re-INVITE on existing dialog " + callId + "; not renegotiating");
            return true;
        }
        final String fromUri = SipHeaders.uriOf(headers.getFirst("from"));
        LogUtil.i(TAG, "Incoming chat INVITE from " + fromUri);

        final RcsChatSession session =
                new RcsChatSession(mContext, this, fromUri, callId, true, mSessionCallback);
        session.setRemoteTag(headers.getFromTag());
        final String contact = SipHeaders.uriOf(headers.getFirst("contact"));
        if (contact != null) session.setRemoteTarget(contact);
        session.setRouteSet(headers.getAll("record-route"));

        mSessionsByCallId.put(callId, session);
        mSessionsByRemote.put(normalizeUri(fromUri), session);
        mInboundInvites.put(callId, headers);

        sendSimpleResponse(headers, 180, "Ringing");
        session.onIncomingInvite(body);
        return true;
    }

    // ---------------------------------------------------------------- outbound SIP

    @Override
    public SipConfigSnapshot getConfig() {
        return mTransport == null ? null : SipConfigSnapshot.from(mTransport.getConfiguration());
    }

    @Override
    public boolean sendInvite(RcsChatSession session, byte[] sdp) {
        final SipConfigSnapshot config = getConfig();
        if (config == null || mTransport == null) return false;

        final String startLine = "INVITE " + session.getRemoteUri() + " SIP/2.0";
        final StringBuilder h = new StringBuilder();
        final String branch = appendVia(h, config);
        mSessionsByBranch.put(branch, session);
        h.append("Max-Forwards: 70\r\n");
        appendRoute(h, config, session);
        appendSecurityVerify(h, config);
        h.append("From: <").append(config.localAor()).append(">;tag=")
                .append(session.getLocalTag()).append("\r\n");
        h.append("To: <").append(session.getRemoteUri()).append(">\r\n");
        h.append("Call-ID: ").append(session.getCallId()).append("\r\n");
        h.append("CSeq: ").append(session.nextCSeq()).append(" INVITE\r\n");
        h.append("Contact: <").append(config.localContactUri()).append(">")
                .append(config.contactHeaderParams())
                .append(';').append(RcsChatSession.ICSI_CHAT_SESSION).append("\r\n");
        h.append("Accept-Contact: *;").append(RcsChatSession.ICSI_CHAT_SESSION)
                .append(";require;explicit\r\n");
        h.append("P-Preferred-Identity: <").append(config.localAor()).append(">\r\n");
        h.append("Allow: INVITE, ACK, CANCEL, BYE, OPTIONS, UPDATE, MESSAGE, NOTIFY\r\n");
        h.append("Supported: timer, gruu, path\r\n");
        h.append("Session-Expires: 1800\r\n");
        appendUserAgent(h, config);
        h.append("Content-Type: application/sdp\r\n");
        h.append("Content-Length: ").append(sdp.length).append("\r\n");

        return mTransport.sendSipMessage(startLine, h.toString(), sdp);
    }

    @Override
    public boolean sendInviteOk(RcsChatSession session, byte[] sdp) {
        final SipConfigSnapshot config = getConfig();
        final SipHeaders request = mInboundInvites.get(session.getCallId());
        if (config == null || request == null || mTransport == null) return false;

        final StringBuilder h = new StringBuilder();
        for (String via : request.getAll("via")) {
            h.append("Via: ").append(via).append("\r\n");
        }
        for (String rr : request.getAll("record-route")) {
            h.append("Record-Route: ").append(rr).append("\r\n");
        }
        h.append("From: ").append(request.getFirst("from")).append("\r\n");
        h.append("To: ").append(withTag(request.getFirst("to"), session.getLocalTag())).append("\r\n");
        h.append("Call-ID: ").append(session.getCallId()).append("\r\n");
        h.append("CSeq: ").append(request.getFirst("cseq")).append("\r\n");
        h.append("Contact: <").append(config.localContactUri()).append(">")
                .append(config.contactHeaderParams())
                .append(';').append(RcsChatSession.ICSI_CHAT_SESSION).append("\r\n");
        h.append("Allow: INVITE, ACK, CANCEL, BYE, OPTIONS, UPDATE, MESSAGE, NOTIFY\r\n");
        appendUserAgent(h, config);
        h.append("Content-Type: application/sdp\r\n");
        h.append("Content-Length: ").append(sdp.length).append("\r\n");

        return mTransport.sendSipMessage("SIP/2.0 200 OK", h.toString(), sdp);
    }

    private void sendAck(RcsChatSession session, SipHeaders response) {
        final SipConfigSnapshot config = getConfig();
        if (config == null || mTransport == null) return;

        final StringBuilder h = new StringBuilder();
        appendVia(h, config);
        h.append("Max-Forwards: 70\r\n");
        appendRoute(h, config, session);
        appendSecurityVerify(h, config);
        h.append("From: <").append(config.localAor()).append(">;tag=")
                .append(session.getLocalTag()).append("\r\n");
        h.append("To: ").append(response.getFirst("to")).append("\r\n");
        h.append("Call-ID: ").append(session.getCallId()).append("\r\n");
        // ACK reuses the INVITE's sequence number.
        h.append("CSeq: ").append(session.currentCSeq() - 1).append(" ACK\r\n");
        h.append("Content-Length: 0\r\n");

        mTransport.sendSipMessage("ACK " + session.getRemoteTarget() + " SIP/2.0", h.toString(),
                new byte[0]);
    }

    @Override
    public boolean sendBye(RcsChatSession session) {
        final SipConfigSnapshot config = getConfig();
        if (config == null || mTransport == null) return false;

        final StringBuilder h = new StringBuilder();
        appendVia(h, config);
        h.append("Max-Forwards: 70\r\n");
        appendRoute(h, config, session);
        appendSecurityVerify(h, config);
        h.append("From: <").append(config.localAor()).append(">;tag=")
                .append(session.getLocalTag()).append("\r\n");
        h.append("To: <").append(session.getRemoteUri()).append(">");
        if (session.getRemoteTag() != null) h.append(";tag=").append(session.getRemoteTag());
        h.append("\r\n");
        h.append("Call-ID: ").append(session.getCallId()).append("\r\n");
        h.append("CSeq: ").append(session.nextCSeq()).append(" BYE\r\n");
        h.append("Content-Length: 0\r\n");

        return mTransport.sendSipMessage("BYE " + session.getRemoteTarget() + " SIP/2.0",
                h.toString(), new byte[0]);
    }

    /** Sends a response that only needs to echo the request's dialog identifiers. */
    private void sendSimpleResponse(SipHeaders request, int status, String reason) {
        if (mTransport == null) return;
        final StringBuilder h = new StringBuilder();
        for (String via : request.getAll("via")) {
            h.append("Via: ").append(via).append("\r\n");
        }
        h.append("From: ").append(request.getFirst("from")).append("\r\n");
        h.append("To: ").append(request.getFirst("to")).append("\r\n");
        h.append("Call-ID: ").append(request.getCallId()).append("\r\n");
        h.append("CSeq: ").append(request.getFirst("cseq")).append("\r\n");
        h.append("Content-Length: 0\r\n");
        mTransport.sendSipMessage("SIP/2.0 " + status + " " + reason, h.toString(), new byte[0]);
    }

    // ---------------------------------------------------------------- header helpers

    /** @return the branch parameter generated for this request */
    private String appendVia(StringBuilder h, SipConfigSnapshot config) {
        final String host = SipConfigSnapshot.formatHost(config.localIpLiteral());
        final int port = config.localAddress != null ? config.localAddress.getPort() : 5060;
        final String branch = "z9hG4bK" + RcsChatSession.randomToken(16);
        h.append("Via: SIP/2.0/").append(config.transportName()).append(' ')
                .append(host).append(':').append(port)
                .append(";branch=").append(branch)
                .append(";rport\r\n");
        return branch;
    }

    /**
     * RFC 3329 security agreement. Omitting it draws 494 Security Agreement Required from the
     * P-CSCF, which rejects the request before it reaches the far end.
     */
    private void appendSecurityVerify(StringBuilder h, SipConfigSnapshot config) {
        if (!TextUtils.isEmpty(config.securityVerifyHeader)) {
            h.append("Security-Verify: ").append(config.securityVerifyHeader).append("\r\n");
        }
    }

    private void appendRoute(StringBuilder h, SipConfigSnapshot config, RcsChatSession session) {
        final List<String> routeSet = session.getRouteSet();
        if (!routeSet.isEmpty()) {
            for (String route : routeSet) {
                h.append("Route: ").append(route).append("\r\n");
            }
        } else if (!TextUtils.isEmpty(config.serviceRouteHeader)) {
            h.append("Route: ").append(config.serviceRouteHeader).append("\r\n");
        }
    }

    private void appendUserAgent(StringBuilder h, SipConfigSnapshot config) {
        if (!TextUtils.isEmpty(config.userAgentHeader)) {
            h.append("User-Agent: ").append(config.userAgentHeader).append("\r\n");
        }
    }

    private static String withTag(String headerValue, String tag) {
        if (headerValue == null) return null;
        return headerValue.contains(";tag=") ? headerValue : headerValue + ";tag=" + tag;
    }

    private static List<String> reverse(List<String> values) {
        java.util.Collections.reverse(values);
        return values;
    }

    /**
     * Builds a request URI from a phone number, keeping any URI it is already given.
     *
     * <p>The number must be E.164. A request URI built from a locally-formatted number such as
     * {@code sip:7653157031@msg.pc.t-mobile.com} is accepted by the ImsService and transmitted,
     * then silently discarded by the network with no response at all — so this normalizes rather
     * than passing the dialled digits through.
     */
    static String toSipUri(Context context, String destination, String homeDomain) {
        if (TextUtils.isEmpty(destination)) return null;
        final String trimmed = destination.trim();
        if (trimmed.startsWith("sip:") || trimmed.startsWith("sips:") || trimmed.startsWith("tel:")) {
            return trimmed;
        }
        final String e164 = com.android.messaging.rcs.uce.CapabilityDiscoveryManager
                .normalizeDestination(context, trimmed);
        if (TextUtils.isEmpty(e164)) return null;
        // A tel URI, not sip:<number>@<our home domain>. The latter asserts the target is a
        // subscriber of our own messaging domain, which is false for anyone on another carrier:
        // the core then resolves them locally, finds no RCS registration, and store-and-forwards
        // to SMS without ever attempting an interworking lookup. Every message so far — on-net
        // and off — came back from the gateway rather than a recipient, which is what that looks
        // like. A tel URI leaves the resolution to the network.
        return "tel:" + e164;
    }

    private static String normalizeUri(String uri) {
        if (uri == null) return "";
        final String digits = uri.replaceAll("[^0-9]", "");
        return digits.length() >= 10 ? digits.substring(digits.length() - 10) : uri;
    }
}
