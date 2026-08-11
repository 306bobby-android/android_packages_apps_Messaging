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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Xml;

import com.android.messaging.util.BuglePrefs;
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

                // Check for custom override URL in preferences
                final String customUrl = BuglePrefs.getApplicationPrefs().getString("pref_key_rcs_custom_acs_url", null);
                String acsUrl;

                if (!TextUtils.isEmpty(customUrl)) {
                    acsUrl = customUrl;
                    LogUtil.i(TAG, "Using custom ACS endpoint override: " + acsUrl);
                } else {
                    final String simOperator = tm.getSimOperator(); // e.g. 310260 (MCC 310, MNC 260)
                    if (simOperator == null || simOperator.length() < 5) {
                        callback.onError("No SIM inserted or invalid operator (MCC/MNC)");
                        return;
                    }

                    final String mcc = simOperator.substring(0, 3);
                    final String mnc = simOperator.substring(3);

                    // Standard GSMA RCC.14 FQDN endpoint: config.rcs.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org
                    acsUrl = String.format("https://config.rcs.mnc%03d.mcc%03d.pub.3gppnetwork.org/rcs/config/v1",
                            Integer.parseInt(mnc), Integer.parseInt(mcc));
                }

                if (otpToken != null && !otpToken.isEmpty()) {
                    acsUrl += (acsUrl.contains("?") ? "&" : "?") + "OTP=" + otpToken;
                }

                LogUtil.i(TAG, "Connecting to ACS endpoint: " + acsUrl);
                final URL url = new URL(acsUrl);

                // Attempt HTTP connection over default network first
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.setRequestProperty("User-Agent", USER_AGENT);
                urlConnection.setConnectTimeout(10000);
                urlConnection.setReadTimeout(10000);

                int responseCode;
                try {
                    responseCode = urlConnection.getResponseCode();
                } catch (Exception connEx) {
                    LogUtil.w(TAG, "Connection failed over default network, trying cellular interface...", connEx);
                    urlConnection.disconnect();

                    // Try binding to cellular network for IMS / ACS routing
                    urlConnection = connectOverCellular(context, url);
                    if (urlConnection == null) {
                        callback.onError("Network error: " + connEx.getMessage());
                        return;
                    }
                    responseCode = urlConnection.getResponseCode();
                }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    final InputStream inputStream = urlConnection.getInputStream();
                    final AcsConfig config = parseAcsXml(inputStream);
                    inputStream.close();

                    if (config != null && config.isValid()) {
                        LogUtil.i(TAG, "ACS Provisioning successful for domain: " + config.getSipDomain());
                        callback.onSuccess(config);
                    } else {
                        callback.onError("Failed to parse valid ACS XML configuration");
                    }
                } else {
                    callback.onError("Carrier ACS returned HTTP error " + responseCode);
                }
                urlConnection.disconnect();
            } catch (Exception e) {
                LogUtil.e(TAG, "ACS request failed", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }).start();
    }

    private static HttpURLConnection connectOverCellular(Context context, URL url) {
        try {
            final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;

            final NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            final Network[] networks = cm.getAllNetworks();
            for (Network network : networks) {
                final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    final HttpURLConnection conn = (HttpURLConnection) network.openConnection(url);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    return conn;
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "Cellular socket binding failed", e);
        }
        return null;
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
