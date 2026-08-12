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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * RFC 3862 Message/CPIM (Common Presence and Instant Messaging) Formatter and Parser.
 */
public class CpimParser {

    public static class CpimMessage {
        public String fromUri;
        public String toUri;
        public String dateTime;
        public String messageId;
        public String contentType;
        public String body;
    }

    /**
     * Formats plain text into RFC 3862 Message/CPIM payload.
     */
    public static String formatCpimMessage(String fromSipUri, String toSipUri, String msgId, String textContent) {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        final String timestamp = sdf.format(new Date());

        final StringBuilder sb = new StringBuilder();
        sb.append("From: <").append(fromSipUri).append(">\r\n");
        sb.append("To: <").append(toSipUri).append(">\r\n");
        sb.append("DateTime: ").append(timestamp).append("\r\n");
        // RFC 5438 defines the IMDN namespace as urn:ietf:params:imdn. The http://www.gsma.com/
        // form previously used here is not a namespace any receiver recognises, so the
        // imdn.* headers below were meaningless to the far end.
        sb.append("NS: imdn <urn:ietf:params:imdn>\r\n");
        sb.append("imdn.Message-ID: ").append(msgId).append("\r\n");
        sb.append("imdn.Disposition-Notification: positive-delivery, display\r\n");
        sb.append("\r\n");
        sb.append("Content-Type: text/plain; charset=utf-8\r\n");
        sb.append("\r\n");
        sb.append(textContent);

        return sb.toString();
    }

    /**
     * Parses raw CPIM text into CpimMessage model.
     */
    public static CpimMessage parseCpim(String rawCpim) {
        if (rawCpim == null || rawCpim.isEmpty()) return null;

        final CpimMessage cpim = new CpimMessage();
        final String[] lines = rawCpim.split("\r\n|\n");

        boolean inMimeHeader = false;
        boolean inBody = false;
        final StringBuilder bodyBuilder = new StringBuilder();

        for (String line : lines) {
            if (inBody) {
                bodyBuilder.append(line).append("\n");
                continue;
            }

            if (line.trim().isEmpty()) {
                if (!inMimeHeader) {
                    inMimeHeader = true;
                } else {
                    inBody = true;
                }
                continue;
            }

            if (!inMimeHeader) {
                if (line.startsWith("From:")) {
                    cpim.fromUri = extractUri(line);
                } else if (line.startsWith("To:")) {
                    cpim.toUri = extractUri(line);
                } else if (line.startsWith("DateTime:")) {
                    cpim.dateTime = line.substring(9).trim();
                } else if (line.startsWith("imdn.Message-ID:")) {
                    cpim.messageId = line.substring(16).trim();
                }
            } else {
                if (line.toLowerCase().startsWith("content-type:")) {
                    cpim.contentType = line.substring(13).trim();
                }
            }
        }

        cpim.body = bodyBuilder.toString().trim();
        return cpim;
    }

    private static String extractUri(String line) {
        int start = line.indexOf("<");
        int end = line.indexOf(">");
        if (start != -1 && end != -1 && end > start) {
            return line.substring(start + 1, end);
        }
        return line.substring(line.indexOf(":") + 1).trim();
    }
}
