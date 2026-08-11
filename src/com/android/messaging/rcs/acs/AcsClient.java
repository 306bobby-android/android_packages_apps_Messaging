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
import android.net.Uri;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Xml;

import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.LogUtil;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

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

                final String customUrl = BuglePrefs.getApplicationPrefs().getString("pref_key_rcs_custom_acs_url", null);
                final String simOperator = tm.getSimOperator(); // e.g. 310260 (MCC 310, MNC 260)

                if (TextUtils.isEmpty(customUrl) && (simOperator == null || simOperator.length() < 5)) {
                    callback.onError("No SIM inserted or invalid operator (MCC/MNC)");
                    return;
                }

                // Build GSMA RCC.14 Query Parameters
                final String query = buildGsmaQueryString(tm, otpToken);

                final List<String> candidateUrls = new ArrayList<>();
                if (!TextUtils.isEmpty(customUrl)) {
                    final String urlWithQuery = customUrl + (customUrl.contains("?") ? "&" : "?") + query;
                    candidateUrls.add(urlWithQuery);
                } else {
                    final String mcc = simOperator.substring(0, 3);
                    final String mnc = simOperator.substring(3);
                    final String baseFqdn = String.format("https://config.rcs.mnc%03d.mcc%03d.pub.3gppnetwork.org",
                            Integer.parseInt(mnc), Integer.parseInt(mcc));

                    candidateUrls.add(baseFqdn + "/rcs/config/v1?" + query);
                    candidateUrls.add(baseFqdn + "/?" + query);
                    candidateUrls.add(baseFqdn + "/rcs/config/v2?" + query);
                }

                int lastResponseCode = -1;
                String lastErrorMessage = null;

                for (String acsUrl : candidateUrls) {
                    LogUtil.i(TAG, "Trying ACS endpoint: " + acsUrl);
                    final URL url = new URL(acsUrl);

                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setRequestProperty("Accept", "application/vnd.gsma.rcs-config+xml, application/xml, text/xml, application/json");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    try {
                        lastResponseCode = conn.getResponseCode();
                    } catch (Exception connEx) {
                        LogUtil.w(TAG, "Connection failed over default network, trying cellular interface for: " + acsUrl, connEx);
                        conn.disconnect();

                        conn = connectOverCellular(context, url);
                        if (conn == null) {
                            lastErrorMessage = "Network error: " + connEx.getMessage();
                            continue;
                        }
                        lastResponseCode = conn.getResponseCode();
                    }

                    LogUtil.i(TAG, "ACS endpoint returned response code: " + lastResponseCode);

                    if (lastResponseCode == HttpURLConnection.HTTP_OK) {
                        final InputStream inputStream = conn.getInputStream();
                        final String rawResponse = readStreamString(inputStream);
                        inputStream.close();
                        conn.disconnect();

                        LogUtil.i(TAG, "Carrier ACS Raw Response:\n" + rawResponse);

                        final AcsConfig config = parseAcsResponse(rawResponse);

                        if (config != null && config.isValid()) {
                            LogUtil.i(TAG, "ACS Provisioning successful for domain: " + config.getSipDomain());
                            callback.onSuccess(config);
                            return;
                        } else {
                            callback.onError("Failed to parse valid ACS configuration payload");
                            return;
                        }
                    } else {
                        lastErrorMessage = "Carrier ACS returned HTTP error " + lastResponseCode;
                        conn.disconnect();
                    }
                }

                callback.onError(lastErrorMessage != null ? lastErrorMessage : "Carrier ACS returned HTTP error " + lastResponseCode);
            } catch (Exception e) {
                LogUtil.e(TAG, "ACS request failed", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }).start();
    }

    private static String readStreamString(InputStream is) throws Exception {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        final StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    private static String buildGsmaQueryString(TelephonyManager tm, String otpToken) {
        final StringBuilder sb = new StringBuilder();
        sb.append("vers=0");
        sb.append("&client_vendor=GSMA");
        sb.append("&client_version=UP_2.4");
        sb.append("&terminal_vendor=").append(Uri.encode(Build.MANUFACTURER));
        sb.append("&terminal_model=").append(Uri.encode(Build.MODEL));
        sb.append("&terminal_sw_version=").append(Uri.encode(Build.DISPLAY));

        try {
            final String imsi = tm.getSubscriberId();
            if (imsi != null && !imsi.isEmpty()) {
                sb.append("&IMSI=").append(Uri.encode(imsi));
            }
        } catch (SecurityException ignored) {}

        try {
            final String imei = tm.getDeviceId();
            if (imei != null && !imei.isEmpty()) {
                sb.append("&IMEI=").append(Uri.encode(imei));
            }
        } catch (SecurityException ignored) {}

        if (otpToken != null && !otpToken.isEmpty()) {
            sb.append("&token=").append(Uri.encode(otpToken));
        }

        return sb.toString();
    }

    private static HttpURLConnection connectOverCellular(Context context, URL url) {
        try {
            final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;

            final Network[] networks = cm.getAllNetworks();
            for (Network network : networks) {
                final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    final HttpURLConnection conn = (HttpURLConnection) network.openConnection(url);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setRequestProperty("Accept", "application/vnd.gsma.rcs-config+xml, application/xml, text/xml, application/json");
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
     * Parses ACS payload (supports standard XML tags, OMA-DM <parm name="..." value="..."/>, and JSON).
     */
    private static AcsConfig parseAcsResponse(String rawResponse) {
        if (TextUtils.isEmpty(rawResponse)) return null;

        final AcsConfig config = new AcsConfig();

        // 1. Try parsing JSON format
        if (rawResponse.startsWith("{")) {
            try {
                final JSONObject json = new JSONObject(rawResponse);
                if (json.has("pcscf")) config.setPCscfAddress(json.getString("pcscf"));
                if (json.has("domain")) config.setSipDomain(json.getString("domain"));
                if (json.has("realm")) config.setSipRealm(json.getString("realm"));
                if (json.has("username")) config.setDigestUsername(json.getString("username"));
                if (json.has("password")) config.setDigestPassword(json.getString("password"));
                if (json.has("ft_url")) config.setFtServerUrl(json.getString("ft_url"));
                config.setRcsEnabled(true);
                return config;
            } catch (Exception ignored) {}
        }

        // 2. Parse XML (Standard XML tags + OMA-DM characteristic/parm format)
        try {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(rawResponse));

            int eventType = parser.getEventType();
            String currentTag = "";
            config.setRcsEnabled(true); // Default to true if valid configuration XML is received

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    currentTag = parser.getName();

                    // OMA-DM <parm name="..." value="..."/> format
                    if ("parm".equalsIgnoreCase(currentTag)) {
                        final String name = parser.getAttributeValue(null, "name");
                        final String value = parser.getAttributeValue(null, "value");
                        if (name != null && value != null) {
                            applyOmaDmParm(config, name, value);
                        }
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    final String text = parser.getText().trim();
                    if (!text.isEmpty()) {
                        switch (currentTag.toLowerCase()) {
                            case "pcscfaddress":
                            case "outboundproxy":
                            case "lbo_p-cscf_address":
                                if (config.getPCscfAddress() == null) config.setPCscfAddress(text);
                                break;
                            case "pcscfport":
                                try { config.setPCscfPort(Integer.parseInt(text)); } catch (Exception ignored) {}
                                break;
                            case "home_network_domain_name":
                            case "sipdomain":
                            case "domain":
                                if (config.getSipDomain() == null) config.setSipDomain(text);
                                break;
                            case "realm":
                                if (config.getSipRealm() == null) config.setSipRealm(text);
                                break;
                            case "username":
                            case "private_user_identity":
                                if (config.getDigestUsername() == null) config.setDigestUsername(text);
                                break;
                            case "userpassword":
                            case "password":
                                if (config.getDigestPassword() == null) config.setDigestPassword(text);
                                break;
                            case "ft_server":
                            case "fturl":
                                config.setFtServerUrl(text);
                                break;
                            case "rcsstate":
                            case "vers":
                                config.setRcsEnabled(!"0".equals(text) && !"-1".equals(text));
                                break;
                        }
                    }
                }
                eventType = parser.next();
            }

            // Fallback: If sipDomain not explicitly set, default to pCscfAddress or carrier domain
            if (config.getSipDomain() == null && config.getPCscfAddress() != null) {
                config.setSipDomain(config.getPCscfAddress());
            }

        } catch (Exception e) {
            LogUtil.e(TAG, "Error parsing ACS XML payload", e);
            return null;
        }

        return config;
    }

    private static void applyOmaDmParm(AcsConfig config, String name, String value) {
        final String nameLower = name.toLowerCase();
        if (nameLower.contains("p-cscf") || nameLower.contains("outboundproxy") || nameLower.equals("address")) {
            if (config.getPCscfAddress() == null) config.setPCscfAddress(value);
        } else if (nameLower.contains("domain") || nameLower.contains("realm")) {
            if (config.getSipDomain() == null) config.setSipDomain(value);
            if (config.getSipRealm() == null) config.setSipRealm(value);
        } else if (nameLower.contains("username") || nameLower.contains("auth")) {
            if (config.getDigestUsername() == null) config.setDigestUsername(value);
        } else if (nameLower.contains("password") || nameLower.contains("secret")) {
            if (config.getDigestPassword() == null) config.setDigestPassword(value);
        } else if (nameLower.contains("ft") || nameLower.contains("url")) {
            if (config.getFtServerUrl() == null) config.setFtServerUrl(value);
        } else if (nameLower.equals("vers") || nameLower.equals("rcsstate")) {
            config.setRcsEnabled(!"0".equals(value) && !"-1".equals(value));
        }
    }
}
