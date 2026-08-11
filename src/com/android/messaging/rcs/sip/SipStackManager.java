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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.android.messaging.rcs.acs.AcsConfig;
import com.android.messaging.util.LogUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocketFactory;

/**
 * Socket-level SIP Stack Manager for P-CSCF registration, signaling, and message transport.
 */
public class SipStackManager {
    private static final String TAG = "SipStackManager";

    private final Context mContext;
    private final AcsConfig mConfig;
    private Socket mSocket;
    private InputStream mInputStream;
    private OutputStream mOutputStream;
    private final AtomicBoolean mIsConnected = new AtomicBoolean(false);
    private long mCSeq = 1;

    public SipStackManager(Context context, AcsConfig config) {
        mContext = context.getApplicationContext();
        mConfig = config;
    }

    /**
     * Connects socket to P-CSCF and initiates SIP REGISTER flow.
     */
    public void connectAndRegister() {
        new Thread(() -> {
            try {
                final String host = mConfig.getPCscfAddress();
                final int port = mConfig.getPCscfPort();
                LogUtil.i(TAG, "Resolving P-CSCF address: " + host + ":" + port);

                InetAddress[] addresses = null;
                try {
                    addresses = InetAddress.getAllByName(host);
                } catch (Exception e) {
                    LogUtil.w(TAG, "Failed DNS resolution over default network, checking cellular...", e);
                    addresses = resolveOverCellular(mContext, host);
                }

                if (addresses == null || addresses.length == 0) {
                    LogUtil.e(TAG, "Unable to resolve P-CSCF host: " + host);
                    mIsConnected.set(false);
                    return;
                }

                final InetAddress targetAddress = addresses[0];
                LogUtil.i(TAG, "Connecting socket to target IP: " + targetAddress.getHostAddress() + ":" + port);

                if (port == 5061) {
                    // TLS Port 5061
                    final SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    mSocket = factory.createSocket();
                    mSocket.connect(new InetSocketAddress(targetAddress, port), 10000);
                } else {
                    // Standard TCP/SIP Port 5060
                    mSocket = new Socket();
                    mSocket.connect(new InetSocketAddress(targetAddress, port), 10000);
                }

                mInputStream = mSocket.getInputStream();
                mOutputStream = mSocket.getOutputStream();
                mIsConnected.set(true);

                LogUtil.i(TAG, "Socket connected successfully to P-CSCF!");

                // Start listening thread
                startIncomingReaderThread();

                // Send initial REGISTER request
                sendRegister(null, null);
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to connect socket to P-CSCF", e);
                mIsConnected.set(false);
            }
        }).start();
    }

    private static InetAddress[] resolveOverCellular(Context context, String host) {
        try {
            final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;

            final Network[] networks = cm.getAllNetworks();
            for (Network network : networks) {
                final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return network.getAllByName(host);
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "Cellular DNS resolution failed", e);
        }
        return null;
    }

    /**
     * Constructs and transmits a SIP REGISTER request.
     */
    public void sendRegister(String nonce, String realm) {
        try {
            if (mSocket == null || !mSocket.isConnected()) {
                LogUtil.e(TAG, "Cannot send REGISTER: Socket is not connected");
                return;
            }

            final String callId = UUID.randomUUID().toString();
            final String branch = "z9hG4bK" + UUID.randomUUID().toString().replace("-", "");
            final String sipUri = "sip:" + mConfig.getSipDomain();
            final String localIp = mSocket.getLocalAddress() != null ? mSocket.getLocalAddress().getHostAddress() : "127.0.0.1";
            final int localPort = mSocket.getLocalPort();

            final StringBuilder sb = new StringBuilder();
            sb.append("REGISTER ").append(sipUri).append(" SIP/2.0\r\n");
            sb.append("Via: SIP/2.0/TCP ").append(localIp).append(":").append(localPort).append(";branch=").append(branch).append("\r\n");
            sb.append("From: <sip:").append(mConfig.getDigestUsername()).append("@").append(mConfig.getSipDomain())
                    .append(">;tag=").append(UUID.randomUUID().toString().substring(0, 8)).append("\r\n");
            sb.append("To: <sip:").append(mConfig.getDigestUsername()).append("@").append(mConfig.getSipDomain()).append(">\r\n");
            sb.append("Call-ID: ").append(callId).append("\r\n");
            sb.append("CSeq: ").append(mCSeq++).append(" REGISTER\r\n");
            sb.append("Contact: <sip:").append(mConfig.getDigestUsername()).append("@")
                    .append(localIp).append(">;+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcse.dp\"\r\n");
            sb.append("Expires: ").append(mConfig.getRegExpireSeconds()).append("\r\n");

            if (nonce != null && realm != null) {
                final String response = SipAuthHelper.calculateDigestResponse(
                        mConfig.getDigestUsername(), mConfig.getDigestPassword(), realm, "REGISTER", sipUri, nonce, null, null);
                sb.append("Authorization: Digest username=\"").append(mConfig.getDigestUsername()).append("\", ")
                        .append("realm=\"").append(realm).append("\", ")
                        .append("nonce=\"").append(nonce).append("\", ")
                        .append("uri=\"").append(sipUri).append("\", ")
                        .append("response=\"").append(response).append("\"\r\n");
            }

            sb.append("Content-Length: 0\r\n\r\n");

            final byte[] bytes = sb.toString().getBytes("UTF-8");
            mOutputStream.write(bytes);
            mOutputStream.flush();
            LogUtil.i(TAG, "Sent SIP REGISTER to P-CSCF:\n" + sb.toString());
        } catch (Exception e) {
            LogUtil.e(TAG, "Error transmitting SIP REGISTER", e);
        }
    }

    private void startIncomingReaderThread() {
        new Thread(() -> {
            final byte[] buffer = new byte[4096];
            try {
                int bytesRead;
                while (mIsConnected.get() && mInputStream != null && (bytesRead = mInputStream.read(buffer)) != -1) {
                    final String message = new String(buffer, 0, bytesRead, "UTF-8");
                    LogUtil.i(TAG, "SIP Message Received:\n" + message);
                    handleIncomingSipMessage(message);
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "Error in SIP reader loop", e);
                mIsConnected.set(false);
            }
        }).start();
    }

    private void handleIncomingSipMessage(String message) {
        if (message.startsWith("SIP/2.0 401")) {
            // Extract Nonce and Realm for Digest challenge
            final String nonce = extractHeaderValue(message, "nonce=\"", "\"");
            final String realm = extractHeaderValue(message, "realm=\"", "\"");
            if (nonce != null && realm != null) {
                LogUtil.i(TAG, "Handling 401 Unauthorized Digest challenge...");
                sendRegister(nonce, realm);
            }
        } else if (message.startsWith("SIP/2.0 200")) {
            LogUtil.i(TAG, "SIP Registration Successful (200 OK)");
        }
    }

    private String extractHeaderValue(String text, String startDelimiter, String endDelimiter) {
        int start = text.indexOf(startDelimiter);
        if (start != -1) {
            start += startDelimiter.length();
            int end = text.indexOf(endDelimiter, start);
            if (end != -1) {
                return text.substring(start, end);
            }
        }
        return null;
    }

    public void disconnect() {
        mIsConnected.set(false);
        try {
            if (mSocket != null) mSocket.close();
        } catch (Exception ignored) {}
    }
}
