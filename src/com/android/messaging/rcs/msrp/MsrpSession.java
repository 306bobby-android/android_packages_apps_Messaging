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

import com.android.messaging.util.LogUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages MSRP session transport over socket connection.
 */
public class MsrpSession {
    private static final String TAG = "MsrpSession";

    private final String mRemoteHost;
    private final int mRemotePort;
    private final String mFromMsrpPath;
    private final String mToMsrpPath;

    private Socket mSocket;
    private InputStream mInputStream;
    private OutputStream mOutputStream;
    private final AtomicBoolean mIsActive = new AtomicBoolean(false);

    public MsrpSession(String remoteHost, int remotePort, String fromMsrpPath, String toMsrpPath) {
        mRemoteHost = remoteHost;
        mRemotePort = remotePort;
        mFromMsrpPath = fromMsrpPath;
        mToMsrpPath = toMsrpPath;
    }

    public void openSession() {
        new Thread(() -> {
            try {
                LogUtil.i(TAG, "Opening MSRP session to: " + mRemoteHost + ":" + mRemotePort);
                mSocket = new Socket(mRemoteHost, mRemotePort);
                mInputStream = mSocket.getInputStream();
                mOutputStream = mSocket.getOutputStream();
                mIsActive.set(true);

                startReaderLoop();
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to connect MSRP session socket", e);
                mIsActive.set(false);
            }
        }).start();
    }

    public void sendTextMessage(String messageId, String textContent) {
        if (!mIsActive.get()) return;
        new Thread(() -> {
            try {
                final String frame = MsrpChunk.buildSendChunk(mFromMsrpPath, mToMsrpPath, messageId, "text/plain", textContent);
                mOutputStream.write(frame.getBytes("UTF-8"));
                mOutputStream.flush();
                LogUtil.i(TAG, "Transmitted MSRP text frame ID: " + messageId);
            } catch (Exception e) {
                LogUtil.e(TAG, "Error sending MSRP frame", e);
            }
        }).start();
    }

    public void sendIsComposing(boolean isTyping) {
        if (!mIsActive.get()) return;
        new Thread(() -> {
            try {
                final String payload = MsrpChunk.buildIsComposingPayload(isTyping);
                final String frame = MsrpChunk.buildSendChunk(mFromMsrpPath, mToMsrpPath, UUID.randomUUID().toString(),
                        "application/im-iscomposing+xml", payload);
                mOutputStream.write(frame.getBytes("UTF-8"));
                mOutputStream.flush();
            } catch (Exception e) {
                LogUtil.e(TAG, "Error sending MSRP IS-COMPOSING frame", e);
            }
        }).start();
    }

    private void startReaderLoop() {
        final byte[] buffer = new byte[4096];
        try {
            int len;
            while (mIsActive.get() && (len = mInputStream.read(buffer)) != -1) {
                final String incoming = new String(buffer, 0, len, "UTF-8");
                LogUtil.i(TAG, "MSRP Frame Received:\n" + incoming);
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "MSRP reader loop closed", e);
            mIsActive.set(false);
        }
    }

    public void closeSession() {
        mIsActive.set(false);
        try {
            if (mSocket != null) mSocket.close();
        } catch (Exception ignored) {}
    }
}
