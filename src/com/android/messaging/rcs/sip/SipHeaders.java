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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal reader for a SIP header section.
 *
 * <p>Only what the chat session state machine needs: look up headers case-insensitively, keep
 * repeated headers in order (Via, Record-Route, Route), and pull the tag/branch parameters and
 * URIs out of the ones that matter. Line folding is unfolded per RFC 3261 s7.3.1.
 */
public final class SipHeaders {

    private final Map<String, List<String>> mHeaders = new LinkedHashMap<>();

    private SipHeaders() {}

    public static SipHeaders parse(String headerSection) {
        final SipHeaders headers = new SipHeaders();
        if (headerSection == null || headerSection.isEmpty()) return headers;

        // Unfold continuation lines before splitting.
        final String unfolded = headerSection.replaceAll("\r?\n[ \t]+", " ");
        for (String line : unfolded.split("\r?\n")) {
            if (line.trim().isEmpty()) continue;
            final int colon = line.indexOf(':');
            if (colon <= 0) continue;
            final String name = normalize(line.substring(0, colon).trim());
            final String value = line.substring(colon + 1).trim();
            headers.mHeaders.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return headers;
    }

    /** Expands the compact forms SIP allows so lookups can use one spelling. */
    private static String normalize(String name) {
        final String lower = name.toLowerCase(Locale.US);
        switch (lower) {
            case "i": return "call-id";
            case "f": return "from";
            case "t": return "to";
            case "v": return "via";
            case "m": return "contact";
            case "c": return "content-type";
            case "l": return "content-length";
            case "s": return "subject";
            case "k": return "supported";
            default: return lower;
        }
    }

    public String getFirst(String name) {
        final List<String> values = mHeaders.get(normalize(name));
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    public List<String> getAll(String name) {
        final List<String> values = mHeaders.get(normalize(name));
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    public boolean has(String name) {
        return mHeaders.containsKey(normalize(name));
    }

    public String getCallId() {
        return getFirst("call-id");
    }

    /** The numeric part of CSeq, or -1. */
    public long getCSeqNumber() {
        final String cseq = getFirst("cseq");
        if (cseq == null) return -1;
        final String[] parts = cseq.trim().split("\\s+");
        try {
            return Long.parseLong(parts[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    /** The method part of CSeq, uppercased, or null. */
    public String getCSeqMethod() {
        final String cseq = getFirst("cseq");
        if (cseq == null) return null;
        final String[] parts = cseq.trim().split("\\s+");
        return parts.length > 1 ? parts[1].toUpperCase(Locale.US) : null;
    }

    public String getFromTag() {
        return parameter(getFirst("from"), "tag");
    }

    public String getToTag() {
        return parameter(getFirst("to"), "tag");
    }

    /** The URI inside angle brackets, else the bare value up to the first parameter. */
    public static String uriOf(String headerValue) {
        if (headerValue == null) return null;
        final int lt = headerValue.indexOf('<');
        final int gt = headerValue.indexOf('>', lt + 1);
        if (lt >= 0 && gt > lt) {
            return headerValue.substring(lt + 1, gt);
        }
        final int semi = headerValue.indexOf(';');
        return (semi >= 0 ? headerValue.substring(0, semi) : headerValue).trim();
    }

    /** Reads {@code ;name=value} from a header value, tolerating quotes. */
    public static String parameter(String headerValue, String name) {
        if (headerValue == null) return null;
        // Skip parameters that live inside the angle-bracketed URI.
        int searchFrom = 0;
        final int gt = headerValue.indexOf('>');
        if (gt >= 0) searchFrom = gt;

        final String needle = ";" + name.toLowerCase(Locale.US) + "=";
        final String haystack = headerValue.toLowerCase(Locale.US);
        final int idx = haystack.indexOf(needle, searchFrom);
        if (idx < 0) return null;

        int start = idx + needle.length();
        boolean quoted = start < headerValue.length() && headerValue.charAt(start) == '"';
        if (quoted) start++;
        int end = start;
        while (end < headerValue.length()) {
            final char c = headerValue.charAt(end);
            if (quoted ? c == '"' : (c == ';' || c == ',' || c == ' ')) break;
            end++;
        }
        return headerValue.substring(start, end);
    }
}
