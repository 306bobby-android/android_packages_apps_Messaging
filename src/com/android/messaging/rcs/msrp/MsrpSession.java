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

package com.android.messaging.rcs.msrp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;

import com.android.messaging.util.LogUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One MSRP connection carrying a chat session's messages (RFC 4975).
 *
 * <p>Either end may own the TCP connection; the SDP {@code a=setup} attribute decides. We take the
 * active role whenever the peer will accept it, since an outbound connection traverses NAT and
 * carrier firewalls far more reliably than a listener.
 *
 * <p><b>Network selection.</b> RCS media belongs on the IMS PDN, which is a restricted network:
 * binding to it requires {@code CONNECTIVITY_USE_RESTRICTED_NETWORKS}, a privileged permission.
 * The app requests it but is not granted it unless installed as a privileged app. When the IMS
 * network is unavailable we fall back to the default network and say so loudly, because a session
 * that negotiates successfully and then silently fails to carry bytes is the most confusing
 * failure mode available.
 */
public class MsrpSession {
    private static final String TAG = "MsrpSession";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int ACCEPT_TIMEOUT_MS = 20000;
    private static final int READ_BUFFER_BYTES = 16 * 1024;

    /** Receives complete messages reassembled from MSRP chunks. */
    public interface MsrpListener {
        void onMessage(String contentType, byte[] body);
        void onSessionOpened();
        void onSessionClosed(String reason);
    }

    private final Context mContext;
    private final String mLocalPath;
    private final String mRemotePath;
    private final String mRemoteHost;
    private final int mRemotePort;
    private final boolean mActive;
    private final MsrpListener mListener;
    /**
     * The address the SIP delegate is registered from.
     *
     * <p>A dual-SIM device has one IMS PDN per subscription, so "the IMS network" is ambiguous.
     * Media has to leave from the same PDN the session was signalled on; the other one has no
     * route to the peer's MSRP endpoint and the connection simply times out. This address
     * identifies which of them is ours.
     */
    private final InetAddress mLocalAddress;

    private Socket mSocket;
    private ServerSocket mServerSocket;
    private InputStream mInput;
    private OutputStream mOutput;
    private final AtomicBoolean mActiveFlag = new AtomicBoolean(false);

    public MsrpSession(Context context, String localPath, String remotePath, String remoteHost,
            int remotePort, boolean active, InetAddress localAddress, MsrpListener listener) {
        mContext = context.getApplicationContext();
        mLocalAddress = localAddress;
        mLocalPath = localPath;
        mRemotePath = remotePath;
        mRemoteHost = remoteHost;
        mRemotePort = remotePort;
        mActive = active;
        mListener = listener;
    }

    public boolean isOpen() {
        return mActiveFlag.get();
    }

    /** Opens the connection on a background thread and begins reading. */
    public void open(final ServerSocket preboundListener) {
        new Thread(() -> {
            try {
                if (mActive) {
                    connectOut();
                } else {
                    acceptIn(preboundListener);
                }
                mInput = mSocket.getInputStream();
                mOutput = mSocket.getOutputStream();
                mActiveFlag.set(true);
                LogUtil.i(TAG, "MSRP session open (" + (mActive ? "active" : "passive") + ") to "
                        + mSocket.getRemoteSocketAddress());

                if (mActive) {
                    // RFC 4975 s7.1: the active endpoint sends an empty SEND to bind the session
                    // before any content flows.
                    final String tx = MsrpChunk.newTransactionId();
                    write(MsrpChunk.buildEmptySend(tx, mRemotePath, mLocalPath));
                }
                if (mListener != null) mListener.onSessionOpened();
                readLoop();
            } catch (Exception e) {
                LogUtil.e(TAG, "MSRP session failed to open", e);
                mActiveFlag.set(false);
                if (mListener != null) mListener.onSessionClosed(String.valueOf(e.getMessage()));
            }
        }, "MsrpSession").start();
    }

    private void connectOut() throws Exception {
        mSocket = new Socket();
        final Network ims = findImsNetwork();
        if (ims != null) {
            ims.bindSocket(mSocket);
            // Pin the source address too, so the socket cannot pick a different address on a
            // PDN that happens to carry more than one.
            if (mLocalAddress != null) {
                try {
                    mSocket.bind(new InetSocketAddress(mLocalAddress, 0));
                } catch (Exception e) {
                    LogUtil.w(TAG, "Could not pin MSRP source address " + mLocalAddress + ": " + e);
                }
            }
            LogUtil.i(TAG, "MSRP socket bound to IMS network, source=" + mLocalAddress);
        } else {
            LogUtil.w(TAG, "IMS network unavailable to this app; MSRP will use the default "
                    + "network. This usually fails on carrier RCS. Grant "
                    + "CONNECTIVITY_USE_RESTRICTED_NETWORKS by installing as a privileged app.");
        }
        LogUtil.i(TAG, "Connecting MSRP to " + mRemoteHost + ":" + mRemotePort);
        mSocket.connect(new InetSocketAddress(mRemoteHost, mRemotePort), CONNECT_TIMEOUT_MS);
    }

    private void acceptIn(ServerSocket preboundListener) throws Exception {
        mServerSocket = preboundListener != null ? preboundListener : new ServerSocket(0);
        mServerSocket.setSoTimeout(ACCEPT_TIMEOUT_MS);
        LogUtil.i(TAG, "Awaiting inbound MSRP on port " + mServerSocket.getLocalPort());
        mSocket = mServerSocket.accept();
    }

    /**
     * Returns the IMS network this session was signalled on.
     *
     * <p>Selection is by local address rather than by taking the first IMS network found: on a
     * dual-SIM device each subscription has its own IMS PDN, and the other one cannot reach our
     * peer. Falling back to the first IMS network would connect from the wrong source address and
     * time out.
     *
     * <p>The IMS network also lacks {@code NOT_RESTRICTED}, so an app without
     * {@code CONNECTIVITY_USE_RESTRICTED_NETWORKS} will not find it here at all.
     */
    private Network findImsNetwork() {
        try {
            final ConnectivityManager cm =
                    (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;

            Network firstImsNetwork = null;
            for (Network network : cm.getAllNetworks()) {
                final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                    continue;
                }
                if (firstImsNetwork == null) firstImsNetwork = network;
                if (mLocalAddress == null) continue;

                final LinkProperties lp = cm.getLinkProperties(network);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (mLocalAddress.equals(la.getAddress())) {
                        LogUtil.i(TAG, "Matched IMS network on " + lp.getInterfaceName()
                                + " for local address " + mLocalAddress);
                        return network;
                    }
                }
            }
            if (firstImsNetwork != null) {
                LogUtil.w(TAG, "No IMS network carries " + mLocalAddress
                        + "; falling back to the first one, which may not reach the peer");
            }
            return firstImsNetwork;
        } catch (Exception e) {
            LogUtil.w(TAG, "IMS network lookup failed: " + e);
        }
        return null;
    }

    /** Sends a message body as a single complete MSRP SEND chunk. */
    public boolean sendMessage(String messageId, String contentType, byte[] body) {
        if (!mActiveFlag.get()) {
            LogUtil.w(TAG, "sendMessage: session not open");
            return false;
        }
        try {
            final String tx = MsrpChunk.newTransactionId();
            write(MsrpChunk.buildSend(tx, mRemotePath, mLocalPath, messageId, contentType, body, true));
            LogUtil.i(TAG, "MSRP SEND dispatched, messageId=" + messageId
                    + " bytes=" + (body == null ? 0 : body.length));
            // The peer here is the carrier's CPM server, not the recipient's device. Logging the
            // body is the only way to see what the far end was actually asked to deliver.
            if (body != null && body.length > 0 && body.length < 4096) {
                LogUtil.i(TAG, "[OUTBOUND CPIM]\n"
                        + new String(body, StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            LogUtil.e(TAG, "MSRP send failed", e);
            return false;
        }
    }

    public boolean sendIsComposing(boolean isTyping) {
        return sendMessage(MsrpChunk.newTransactionId(), "application/im-iscomposing+xml",
                MsrpChunk.buildIsComposingPayload(isTyping).getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void write(byte[] frame) throws Exception {
        mOutput.write(frame);
        mOutput.flush();
    }

    private void readLoop() {
        final byte[] buffer = new byte[READ_BUFFER_BYTES];
        int filled = 0;
        try {
            while (mActiveFlag.get()) {
                final int read = mInput.read(buffer, filled, buffer.length - filled);
                if (read < 0) break;
                filled += read;

                // A single read may contain several frames, or a partial one.
                int consumed;
                while (filled > 0 && (consumed = MsrpChunk.frameLength(buffer, filled)) > 0) {
                    handleChunk(MsrpChunk.parse(buffer, 0, consumed));
                    System.arraycopy(buffer, consumed, buffer, 0, filled - consumed);
                    filled -= consumed;
                }
                if (filled == buffer.length) {
                    LogUtil.e(TAG, "MSRP frame exceeds buffer; dropping connection");
                    break;
                }
            }
        } catch (Exception e) {
            if (mActiveFlag.get()) LogUtil.w(TAG, "MSRP read loop ended: " + e);
        }
        close("read loop ended");
    }

    private void handleChunk(MsrpChunk chunk) {
        if (chunk == null) return;

        if (!chunk.isRequest()) {
            LogUtil.i(TAG, "MSRP response " + chunk.statusCode + " for tx=" + chunk.transactionId);
            return;
        }

        if ("SEND".equals(chunk.method)) {
            // Acknowledge before dispatching so a slow consumer cannot stall the peer.
            try {
                write(MsrpChunk.buildResponse(chunk.transactionId, 200, "OK",
                        mRemotePath, mLocalPath));
            } catch (Exception e) {
                LogUtil.w(TAG, "Failed to acknowledge MSRP SEND: " + e);
            }
            if (chunk.body != null && chunk.body.length > 0 && mListener != null) {
                final String contentType = chunk.header("Content-Type");
                LogUtil.i(TAG, "MSRP SEND received, type=" + contentType
                        + " bytes=" + chunk.body.length);
                mListener.onMessage(contentType, chunk.body);
            }
        } else if ("REPORT".equals(chunk.method)) {
            LogUtil.i(TAG, "MSRP REPORT for messageId=" + chunk.messageId()
                    + " status=" + chunk.header("Status"));
            // REPORT is never acknowledged (RFC 4975 s7.1.2).
        } else {
            LogUtil.i(TAG, "Unhandled MSRP method: " + chunk.method);
        }
    }

    public void close(String reason) {
        if (!mActiveFlag.getAndSet(false)) return;
        LogUtil.i(TAG, "Closing MSRP session: " + reason);
        try { if (mSocket != null) mSocket.close(); } catch (Exception ignored) {}
        try { if (mServerSocket != null) mServerSocket.close(); } catch (Exception ignored) {}
        if (mListener != null) mListener.onSessionClosed(reason);
    }
}
