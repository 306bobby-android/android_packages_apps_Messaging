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

import java.util.UUID;

/**
 * RFC 4975 / RFC 4976 MSRP (Message Session Relay Protocol) Frame Builder.
 */
public class MsrpChunk {

    /**
     * Constructs MSRP SEND frame for real-time text payload or CPIM wrapped content.
     */
    public static String buildSendChunk(String fromMsrpPath, String toMsrpPath, String messageId, String contentType, String payload) {
        final String transactionId = UUID.randomUUID().toString().substring(0, 8);
        final StringBuilder sb = new StringBuilder();

        sb.append("MSRP ").append(transactionId).append(" SEND\r\n");
        sb.append("To-Path: ").append(toMsrpPath).append("\r\n");
        sb.append("From-Path: ").append(fromMsrpPath).append("\r\n");
        sb.append("Message-ID: ").append(messageId).append("\r\n");
        sb.append("Byte-Range: 1-").append(payload.getBytes().length).append("/").append(payload.getBytes().length).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("\r\n");
        sb.append(payload);
        sb.append("\r\n-------").append(transactionId).append("$\r\n");

        return sb.toString();
    }

    /**
     * Constructs MSRP IS-COMPOSING payload (GSMA Universal Profile typing indicator).
     */
    public static String buildIsComposingPayload(boolean isTyping) {
        final StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n");
        sb.append("<isComposing xmlns=\"urn:ietf:params:xml:ns:im-iscomposing\">\r\n");
        sb.append("  <state>").append(isTyping ? "active" : "idle").append("</state>\r\n");
        sb.append("  <contenttype>text/plain</contenttype>\r\n");
        sb.append("  <lastactive>").append(System.currentTimeMillis() / 1000).append("</lastactive>\r\n");
        sb.append("</isComposing>\r\n");
        return sb.toString();
    }
}
