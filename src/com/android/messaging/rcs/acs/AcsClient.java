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

package com.android.messaging.rcs.acs;

import android.content.Context;
import android.telephony.TelephonyManager;
import android.util.Xml;

import com.android.messaging.util.LogUtil;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GSMA RCC.14 / RCC.60 Auto-Configuration Server (ACS) HTTP Provisioning Client.
 */
public class AcsClient {
    private static final String TAG = "AcsClient";
    private static final String USER_AGENT = "GSMA-Universal-Profile/2.4 Android-RCS-Client/1.0";

    public interface AcsCallback {
        void onSuccess(AcsConfig config);
        void onError(String errorReason);
    }

    /**
     * Executes HTTPS GET provisioning request to the Carrier ACS endpoint.
     */
    public static void requestConfiguration(final Context context, final String otpToken, final AcsCallback callback) {
        new Thread(() -> {
            try {
                final TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm == null) {
                    callback.onError("TelephonyManager unavailable");
                    return;
                }

                final String simOperator = tm.getSimOperator(); // e.g. 310260 (MCC 310, MNC 260)
                if (simOperator == null || simOperator.length() < 5) {
                    callback.onError("Invalid SIM Operator");
                    return;
                }

                final String mcc = simOperator.substring(0, 3);
                final String mnc = simOperator.substring(3);

                // Standard GSMA RCC.14 FQDN endpoint: config.rcs.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org
                String acsUrl = String.format("https://config.rcs.mnc%03d.mcc%03d.pub.3gppnetwork.org/rcs/config/v1",
                        Integer.parseInt(mnc), Integer.parseInt(mcc));

                if (otpToken != null && !otpToken.isEmpty()) {
                    acsUrl += "?OTP=" + otpToken;
                }

                LogUtil.i(TAG, "Connecting to ACS endpoint: " + acsUrl);
                final URL url = new URL(acsUrl);
                final HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.setRequestProperty("User-Agent", USER_AGENT);
                urlConnection.setConnectTimeout(10000);
                urlConnection.setReadTimeout(10000);

                final int responseCode = urlConnection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    final InputStream inputStream = urlConnection.getInputStream();
                    final AcsConfig config = parseAcsXml(inputStream);
                    inputStream.close();

                    if (config != null && config.isValid()) {
                        LogUtil.i(TAG, "ACS Provisioning successful for domain: " + config.getSipDomain());
                        callback.onSuccess(config);
                    } else {
                        callback.onError("Failed to parse valid ACS XML payload");
                    }
                } else {
                    callback.onError("ACS HTTP server returned code: " + responseCode);
                }
                urlConnection.disconnect();
            } catch (Exception e) {
                LogUtil.e(TAG, "ACS request failed", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }

    /**
     * Helper XML pull parser for GSMA RCC.14 provisioning document format.
     */
    private static AcsConfig parseAcsXml(InputStream is) {
        final AcsConfig config = new AcsConfig();
        try {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(is, "UTF-8");

            int eventType = parser.getEventType();
            String currentTag = "";

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    currentTag = parser.getName();
                } else if (eventType == XmlPullParser.TEXT) {
                    final String text = parser.getText().trim();
                    if (!text.isEmpty()) {
                        switch (currentTag.toLowerCase()) {
                            case "pcscfaddress":
                            case "outboundproxy":
                                config.setPCscfAddress(text);
                                break;
                            case "pcscfport":
                                try { config.setPCscfPort(Integer.parseInt(text)); } catch (Exception ignored) {}
                                break;
                            case "home_network_domain_name":
                            case "sipdomain":
                                config.setSipDomain(text);
                                break;
                            case "realm":
                                config.setSipRealm(text);
                                break;
                            case "username":
                            case "private_user_identity":
                                config.setDigestUsername(text);
                                break;
                            case "userpassword":
                            case "password":
                                config.setDigestPassword(text);
                                break;
                            case "ft_server":
                            case "fturl":
                                config.setFtServerUrl(text);
                                break;
                            case "rcsstate":
                            case "vers":
                                config.setRcsEnabled(!"0".equals(text));
                                break;
                        }
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "Error parsing ACS XML", e);
            return null;
        }
        return config;
    }
}
