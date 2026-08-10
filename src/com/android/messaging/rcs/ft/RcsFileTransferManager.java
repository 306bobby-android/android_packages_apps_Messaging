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

package com.android.messaging.rcs.ft;

import android.content.Context;
import android.net.Uri;

import com.android.messaging.util.LogUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GSMA RCC.07 HTTP File Transfer Manager for uploading/downloading rich attachments over HTTPS.
 */
public class RcsFileTransferManager {
    private static final String TAG = "RcsFileTransfer";

    public interface FileTransferCallback {
        void onSuccess(String fileUrl);
        void onError(String error);
    }

    /**
     * Uploads media file to Carrier HTTP File Transfer Server.
     */
    public static void uploadFile(final Context context, final String serverUrl, final Uri fileUri,
            final String contentType, final FileTransferCallback callback) {
        new Thread(() -> {
            try {
                LogUtil.i(TAG, "Uploading media attachment to Carrier FT Server: " + serverUrl);
                final URL url = new URL(serverUrl);
                final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", contentType);
                conn.setRequestProperty("User-Agent", "GSMA-Universal-Profile/2.4");

                final InputStream is = context.getContentResolver().openInputStream(fileUri);
                final OutputStream os = conn.getOutputStream();

                final byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.flush();
                os.close();
                is.close();

                final int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                    final String downloadUrl = conn.getHeaderField("Location");
                    LogUtil.i(TAG, "File upload successful. Download URL: " + downloadUrl);
                    callback.onSuccess(downloadUrl != null ? downloadUrl : serverUrl);
                } else {
                    callback.onError("HTTP Upload returned code: " + responseCode);
                }
                conn.disconnect();
            } catch (Exception e) {
                LogUtil.e(TAG, "HTTP File Transfer upload error", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }
}
