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

package com.android.messaging.rcs.imdn;

import android.text.TextUtils;

import com.android.messaging.util.BuglePrefs;

/**
 * IMDN (Instant Messaging Disposition Notification - Delivery & Read Receipts) Manager.
 * Supports RFC 5438 / GSMA RCC.07 delivery and display notifications.
 */
public class ImdnManager {
    public static final int READ_RECEIPT_DEFAULT = 0;
    public static final int READ_RECEIPT_ALWAYS = 1;
    public static final int READ_RECEIPT_NEVER = 2;

    public static boolean isReadReceiptsEnabledGlobally() {
        return BuglePrefs.getApplicationPrefs().getBoolean("pref_key_rcs_send_read_receipts", true);
    }

    public static void setReadReceiptsEnabledGlobally(boolean enabled) {
        BuglePrefs.getApplicationPrefs().putBoolean("pref_key_rcs_send_read_receipts", enabled);
    }

    public static int getPerContactReadReceiptSetting(String destination) {
        if (TextUtils.isEmpty(destination)) {
            return READ_RECEIPT_DEFAULT;
        }
        final String norm = destination.replaceAll("[^0-9]", "");
        final String key = norm.length() >= 10 ? norm.substring(norm.length() - 10) : norm;
        return BuglePrefs.getApplicationPrefs().getInt("pref_key_rcs_read_receipts_" + key, READ_RECEIPT_DEFAULT);
    }

    public static void setPerContactReadReceiptSetting(String destination, int setting) {
        if (TextUtils.isEmpty(destination)) return;
        final String norm = destination.replaceAll("[^0-9]", "");
        final String key = norm.length() >= 10 ? norm.substring(norm.length() - 10) : norm;
        BuglePrefs.getApplicationPrefs().putInt("pref_key_rcs_read_receipts_" + key, setting);
    }

    public static boolean shouldSendReadReceiptForContact(String destination) {
        final int perContact = getPerContactReadReceiptSetting(destination);
        if (perContact == READ_RECEIPT_ALWAYS) {
            return true;
        }
        if (perContact == READ_RECEIPT_NEVER) {
            return false;
        }
        return isReadReceiptsEnabledGlobally();
    }

    /**
     * Generates RFC 5438 IMDN delivery-notification XML payload.
     */
    public static String createDeliveryReportXml(String messageId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\n" +
                "  <message-id>" + messageId + "</message-id>\n" +
                "  <datetime>" + System.currentTimeMillis() + "</datetime>\n" +
                "  <status>\n" +
                "    <delivery-notification>\n" +
                "      <status>delivered</status>\n" +
                "    </delivery-notification>\n" +
                "  </status>\n" +
                "</imdn>";
    }

    /**
     * Generates RFC 5438 IMDN display-notification (read receipt) XML payload.
     */
    public static String createDisplayReportXml(String messageId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\n" +
                "  <message-id>" + messageId + "</message-id>\n" +
                "  <datetime>" + System.currentTimeMillis() + "</datetime>\n" +
                "  <status>\n" +
                "    <display-notification>\n" +
                "      <status>displayed</status>\n" +
                "    </display-notification>\n" +
                "  </status>\n" +
                "</imdn>";
    }
}
