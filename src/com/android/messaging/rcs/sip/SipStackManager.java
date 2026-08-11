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
import android.telephony.TelephonyManager;

import com.android.messaging.rcs.acs.AcsConfig;
import com.android.messaging.util.LogUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocketFactory;

/**
 * Socket-level SIP Stack Manager for P-CSCF registration, signaling, and message transport.
 * Supports both UDP (SIPoUDP) and TCP/TLS transports.
 */
public class SipStackManager {
    private static final String TAG = "SipStackManager";

    private final Context mContext;
    private final AcsConfig mConfig;
    private InetAddress mTargetAddress;
    private int mTargetPort;

    // UDP Transport
    private DatagramSocket mUdpSocket;

    // TCP/TLS Transport
    private Socket mTcpSocket;
    private InputStream mInputStream;
    private OutputStream mOutputStream;

    private boolean mUseUdp = true; // Default for carrier SIPoUDP
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
                mTargetPort = mConfig.getPCscfPort();
                LogUtil.i(TAG, "Resolving P-CSCF address: " + host + ":" + mTargetPort);

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

                mTargetAddress = addresses[0];
                LogUtil.i(TAG, "Target P-CSCF IP: " + mTargetAddress.getHostAddress() + ":" + mTargetPort);

                mUseUdp = (mTargetPort == 5060); // Use UDP for port 5060 (SIPoUDP)

                if (mUseUdp) {
                    LogUtil.i(TAG, "Initializing SIPoUDP DatagramSocket to " + mTargetAddress.getHostAddress());
                    mUdpSocket = new DatagramSocket();
                    bindSocketToCellularOrIms(mUdpSocket);
                    mUdpSocket.setSoTimeout(15000);
                    mIsConnected.set(true);
                    startUdpReaderThread();
                } else {
                    LogUtil.i(TAG, "Initializing SIP TCP/TLS Socket to " + mTargetAddress.getHostAddress());
                    if (mTargetPort == 5061) {
                        final SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                        mTcpSocket = factory.createSocket();
                    } else {
                        mTcpSocket = new Socket();
                    }
                    bindSocketToCellularOrIms(mTcpSocket);
                    mTcpSocket.connect(new InetSocketAddress(mTargetAddress, mTargetPort), 10000);
                    mInputStream = mTcpSocket.getInputStream();
                    mOutputStream = mTcpSocket.getOutputStream();
                    mIsConnected.set(true);
                    startTcpReaderThread();
                }

                LogUtil.i(TAG, "Socket connected successfully to P-CSCF!");

                // Send initial REGISTER request
                sendRegister(null, null);
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to connect socket to P-CSCF", e);
                mIsConnected.set(false);
            }
        }).start();
    }

    private void bindSocketToCellularOrIms(DatagramSocket socket) {
        try {
            final ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            for (Network network : cm.getAllNetworks()) {
                final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                    try {
                        network.bindSocket(socket);
                        LogUtil.i(TAG, "Bound DatagramSocket to IMS network interface: " + network);
                        return;
                    } catch (Exception bindEx) {
                        LogUtil.w(TAG, "Could not bind UDP socket to network " + network + ": " + bindEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "Failed to bind UDP socket to IMS network", e);
        }
    }

    private void bindSocketToCellularOrIms(Socket socket) {
        try {
            final ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            for (Network network : cm.getAllNetworks()) {
                final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                    try {
                        network.bindSocket(socket);
                        LogUtil.i(TAG, "Bound TCP socket to IMS network interface: " + network);
                        return;
                    } catch (Exception bindEx) {
                        LogUtil.w(TAG, "Could not bind TCP socket to network " + network + ": " + bindEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "Failed to bind TCP socket to IMS network", e);
        }
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
            if (!mIsConnected.get()) {
                LogUtil.e(TAG, "Cannot send REGISTER: Socket is not connected");
                return;
            }

            final String callId = UUID.randomUUID().toString();
            final String branch = "z9hG4bK" + UUID.randomUUID().toString().replace("-", "");
            final String sipUri = "sip:" + mConfig.getSipDomain();

            String localIp = "127.0.0.1";
            int localPort = 5060;

            if (mUseUdp && mUdpSocket != null) {
                if (mUdpSocket.getLocalAddress() != null) {
                    localIp = mUdpSocket.getLocalAddress().getHostAddress();
                }
                localPort = mUdpSocket.getLocalPort();
            } else if (mTcpSocket != null) {
                if (mTcpSocket.getLocalAddress() != null) {
                    localIp = mTcpSocket.getLocalAddress().getHostAddress();
                }
                localPort = mTcpSocket.getLocalPort();
            }

            // Format IPv6 address string with brackets if applicable
            final String formattedLocalIp = (localIp != null && localIp.contains(":")) ? "[" + localIp + "]" : localIp;

            // Determine Public User Identity (IMPU)
            String user = mConfig.getPublicUserIdentity();
            if (user == null || user.isEmpty() || "AKA".equalsIgnoreCase(user)) {
                try {
                    final TelephonyManager tm = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
                    if (tm != null) {
                        final String num = tm.getLine1Number();
                        if (num != null && !num.isEmpty()) {
                            user = num.startsWith("+") ? num : "+" + num;
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (user == null || user.isEmpty() || "AKA".equalsIgnoreCase(user)) {
                user = "+19876543210"; // Fallback subscriber ID
            }

            final String transportStr = mUseUdp ? "UDP" : (mTargetPort == 5061 ? "TLS" : "TCP");

            final StringBuilder sb = new StringBuilder();
            sb.append("REGISTER ").append(sipUri).append(" SIP/2.0\r\n");
            sb.append("Via: SIP/2.0/").append(transportStr).append(" ").append(formattedLocalIp).append(":").append(localPort).append(";branch=").append(branch).append("\r\n");
            sb.append("From: <sip:").append(user).append("@").append(mConfig.getSipDomain())
                    .append(">;tag=").append(UUID.randomUUID().toString().substring(0, 8)).append("\r\n");
            sb.append("To: <sip:").append(user).append("@").append(mConfig.getSipDomain()).append(">\r\n");
            sb.append("Call-ID: ").append(callId).append("\r\n");
            sb.append("CSeq: ").append(mCSeq++).append(" REGISTER\r\n");
            sb.append("Contact: <sip:").append(user).append("@")
                    .append(formattedLocalIp).append(":").append(localPort).append(">;+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcse.dp\"\r\n");
            sb.append("Expires: ").append(mConfig.getRegExpireSeconds()).append("\r\n");

            final String usernameForAuth = (mConfig.getDigestUsername() != null && !"AKA".equalsIgnoreCase(mConfig.getDigestUsername()))
                    ? mConfig.getDigestUsername() : user;

            if (nonce != null && realm != null) {
                final String response = SipAuthHelper.calculateDigestResponse(
                        usernameForAuth, mConfig.getDigestPassword(), realm, "REGISTER", sipUri, nonce, null, null);
                sb.append("Authorization: Digest username=\"").append(usernameForAuth).append("\", ")
                        .append("realm=\"").append(realm).append("\", ")
                        .append("nonce=\"").append(nonce).append("\", ")
                        .append("uri=\"").append(sipUri).append("\", ")
                        .append("response=\"").append(response).append("\"\r\n");
            }

            sb.append("Content-Length: 0\r\n\r\n");

            final byte[] bytes = sb.toString().getBytes("UTF-8");

            if (mUseUdp && mUdpSocket != null) {
                final DatagramPacket packet = new DatagramPacket(bytes, bytes.length, mTargetAddress, mTargetPort);
                mUdpSocket.send(packet);
            } else if (mOutputStream != null) {
                mOutputStream.write(bytes);
                mOutputStream.flush();
            }

            LogUtil.i(TAG, "Sent SIP REGISTER to P-CSCF:\n" + sb.toString());
        } catch (Exception e) {
            LogUtil.e(TAG, "Error transmitting SIP REGISTER", e);
        }
    }

    private void startUdpReaderThread() {
        new Thread(() -> {
            final byte[] buffer = new byte[8192];
            while (mIsConnected.get() && mUdpSocket != null && !mUdpSocket.isClosed()) {
                try {
                    final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    mUdpSocket.receive(packet);
                    final String message = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                    LogUtil.i(TAG, "SIP Message Received via UDP:\n" + message);
                    handleIncomingSipMessage(message);
                } catch (Exception e) {
                    if (mIsConnected.get()) {
                        LogUtil.w(TAG, "UDP receive timeout/exception: " + e.getMessage());
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                }
            }
        }).start();
    }

    private void startTcpReaderThread() {
        new Thread(() -> {
            final byte[] buffer = new byte[4096];
            try {
                int bytesRead;
                while (mIsConnected.get() && mInputStream != null && (bytesRead = mInputStream.read(buffer)) != -1) {
                    final String message = new String(buffer, 0, bytesRead, "UTF-8");
                    LogUtil.i(TAG, "SIP Message Received via TCP:\n" + message);
                    handleIncomingSipMessage(message);
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "Error in TCP reader loop", e);
                mIsConnected.set(false);
            }
        }).start();
    }

    private void handleIncomingSipMessage(String message) {
        if (message.startsWith("SIP/2.0 401")) {
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
            if (mUdpSocket != null) mUdpSocket.close();
            if (mTcpSocket != null) mTcpSocket.close();
        } catch (Exception ignored) {}
    }
}
