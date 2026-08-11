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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MSRP framing per RFC 4975.
 *
 * <p>A chunk is {@code MSRP <transaction-id> <method-or-status>}, a header block, an optional body
 * after a blank line, and an end-line of seven hyphens, the transaction id, and a continuation
 * flag: {@code $} complete, {@code +} more to follow, {@code #} aborted.
 */
public final class MsrpChunk {

    public static final char FLAG_COMPLETE = '$';
    public static final char FLAG_CONTINUED = '+';
    public static final char FLAG_ABORTED = '#';

    public final String transactionId;
    /** SEND, REPORT, NICKNAME for requests; null for responses. */
    public final String method;
    /** 200, 400, 481... for responses; -1 for requests. */
    public final int statusCode;
    public final String statusText;
    public final Map<String, String> headers;
    public final byte[] body;
    public final char continuationFlag;

    private MsrpChunk(String transactionId, String method, int statusCode, String statusText,
            Map<String, String> headers, byte[] body, char continuationFlag) {
        this.transactionId = transactionId;
        this.method = method;
        this.statusCode = statusCode;
        this.statusText = statusText;
        this.headers = headers;
        this.body = body;
        this.continuationFlag = continuationFlag;
    }

    public boolean isRequest() {
        return method != null;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.US));
    }

    public String messageId() {
        return header("Message-ID");
    }

    // ------------------------------------------------------------------ building

    /** Builds a SEND chunk carrying a complete message body. */
    public static byte[] buildSend(String transactionId, String toPath, String fromPath,
            String messageId, String contentType, byte[] content, boolean requestReports) {
        final byte[] payload = content == null ? new byte[0] : content;
        final StringBuilder head = new StringBuilder();
        head.append("MSRP ").append(transactionId).append(" SEND\r\n");
        head.append("To-Path: ").append(toPath).append("\r\n");
        head.append("From-Path: ").append(fromPath).append("\r\n");
        head.append("Message-ID: ").append(messageId).append("\r\n");
        head.append("Byte-Range: 1-").append(payload.length).append('/').append(payload.length)
                .append("\r\n");
        if (requestReports) {
            // Delivery success is reported by the far end; failures always are.
            head.append("Success-Report: yes\r\n");
            head.append("Failure-Report: yes\r\n");
        }
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("\r\n");

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, head.toString());
        out.write(payload, 0, payload.length);
        writeAscii(out, "\r\n-------" + transactionId + FLAG_COMPLETE + "\r\n");
        return out.toByteArray();
    }

    /**
     * Builds the bodiless SEND that binds an MSRP session (RFC 4975 s7.1).
     *
     * <p>A chunk with no body has no blank line: the empty line exists to separate headers from
     * content, and emitting one with nothing after it draws {@code 400 Bad Request}. The end-line
     * follows the last header directly.
     */
    public static byte[] buildEmptySend(String transactionId, String toPath, String fromPath) {
        final StringBuilder head = new StringBuilder();
        head.append("MSRP ").append(transactionId).append(" SEND\r\n");
        head.append("To-Path: ").append(toPath).append("\r\n");
        head.append("From-Path: ").append(fromPath).append("\r\n");
        head.append("Message-ID: ").append(transactionId).append("\r\n");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, head.toString());
        writeAscii(out, "-------" + transactionId + FLAG_COMPLETE + "\r\n");
        return out.toByteArray();
    }

    /** Builds a response to a received request. */
    public static byte[] buildResponse(String transactionId, int status, String text,
            String toPath, String fromPath) {
        final StringBuilder sb = new StringBuilder();
        sb.append("MSRP ").append(transactionId).append(' ').append(status).append(' ')
                .append(text).append("\r\n");
        sb.append("To-Path: ").append(toPath).append("\r\n");
        sb.append("From-Path: ").append(fromPath).append("\r\n");
        sb.append("-------").append(transactionId).append(FLAG_COMPLETE).append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String newTransactionId() {
        // RFC 4975 requires at least 4 and at most 31 alphanumeric characters, unique per
        // connection. A trimmed random UUID satisfies both.
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parses one complete chunk from {@code data}.
     *
     * @return the chunk, or null if {@code data} is not a well-formed MSRP frame.
     */
    public static MsrpChunk parse(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return null;
        final String text = new String(data, offset, length, StandardCharsets.UTF_8);

        final int firstLineEnd = text.indexOf("\r\n");
        if (firstLineEnd < 0) return null;
        final String startLine = text.substring(0, firstLineEnd);
        if (!startLine.startsWith("MSRP ")) return null;

        final String[] startParts = startLine.split("\\s+", 3);
        if (startParts.length < 3) return null;
        final String txId = startParts[1];

        String method = null;
        int status = -1;
        String statusText = null;
        final String third = startParts[2].trim();
        if (third.matches("\\d{3}.*")) {
            try {
                status = Integer.parseInt(third.substring(0, 3));
            } catch (NumberFormatException ignored) {
                return null;
            }
            statusText = third.length() > 3 ? third.substring(3).trim() : "";
        } else {
            method = third.toUpperCase(Locale.US);
        }

        // The end-line terminates the chunk and tells us where the body stops.
        final String endLinePrefix = "-------" + txId;
        final int endLineIdx = text.lastIndexOf(endLinePrefix);
        if (endLineIdx < 0) return null;
        char flag = FLAG_COMPLETE;
        final int flagIdx = endLineIdx + endLinePrefix.length();
        if (flagIdx < text.length()) flag = text.charAt(flagIdx);

        final int headerStart = firstLineEnd + 2;
        final int blankLine = text.indexOf("\r\n\r\n", firstLineEnd);

        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];

        if (blankLine >= 0 && blankLine < endLineIdx) {
            parseHeaders(text.substring(headerStart, blankLine + 2), headers);
            int bodyStart = blankLine + 4;
            // The end-line is preceded by a CRLF that is not part of the body.
            int bodyEnd = endLineIdx;
            if (bodyEnd >= 2 && text.startsWith("\r\n", bodyEnd - 2)) bodyEnd -= 2;
            if (bodyEnd > bodyStart) {
                body = text.substring(bodyStart, bodyEnd).getBytes(StandardCharsets.UTF_8);
            }
        } else {
            final int headerEnd = Math.min(endLineIdx, text.length());
            if (headerEnd > headerStart) {
                parseHeaders(text.substring(headerStart, headerEnd), headers);
            }
        }

        return new MsrpChunk(txId, method, status, statusText, headers, body, flag);
    }

    private static void parseHeaders(String block, Map<String, String> out) {
        for (String line : block.split("\r\n")) {
            if (line.trim().isEmpty()) continue;
            final int colon = line.indexOf(':');
            if (colon <= 0) continue;
            out.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                    line.substring(colon + 1).trim());
        }
    }

    /**
     * Finds the byte offset just past the first complete chunk in {@code buffer}.
     *
     * @return the length of the first complete frame, or -1 if more data is needed.
     */
    public static int frameLength(byte[] buffer, int length) {
        final String text = new String(buffer, 0, length, StandardCharsets.UTF_8);
        if (!text.startsWith("MSRP ")) return -1;
        final int firstLineEnd = text.indexOf("\r\n");
        if (firstLineEnd < 0) return -1;
        final String[] parts = text.substring(0, firstLineEnd).split("\\s+", 3);
        if (parts.length < 2) return -1;

        final int idx = text.indexOf("-------" + parts[1]);
        if (idx < 0) return -1;
        final int afterFlag = idx + ("-------" + parts[1]).length() + 1;
        final int crlf = text.indexOf("\r\n", afterFlag - 1);
        if (crlf < 0) return -1;
        return crlf + 2;
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        final byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }

    /** Body for the RCS "is composing" indication (OMA IM 1.0). */
    public static String buildIsComposingPayload(boolean isTyping) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<isComposing xmlns=\"urn:ietf:params:xml:ns:im-iscomposing\">\r\n"
                + "  <state>" + (isTyping ? "composing" : "idle") + "</state>\r\n"
                + "  <contenttype>text/plain</contenttype>\r\n"
                + "</isComposing>";
    }
}
