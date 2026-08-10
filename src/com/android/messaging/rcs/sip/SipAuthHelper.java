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

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Helper utility for SIP Digest (RFC 2617 / RFC 3261) authentication response generation.
 */
public class SipAuthHelper {

    /**
     * Generates Digest response string for Authorization header.
     */
    public static String calculateDigestResponse(String username, String password, String realm,
            String method, String uri, String nonce, String cnonce, String nc) {
        try {
            final String ha1 = md5Hex(username + ":" + realm + ":" + password);
            final String ha2 = md5Hex(method + ":" + uri);

            final String response;
            if (cnonce != null && !cnonce.isEmpty() && nc != null && !nc.isEmpty()) {
                response = md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            } else {
                response = md5Hex(ha1 + ":" + nonce + ":" + ha2);
            }
            return response;
        } catch (Exception e) {
            return "";
        }
    }

    private static String md5Hex(String input) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("MD5");
        final byte[] digest = md.digest(input.getBytes("UTF-8"));
        final StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }
}
