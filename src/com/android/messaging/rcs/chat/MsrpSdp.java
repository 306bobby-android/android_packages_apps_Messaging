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

package com.android.messaging.rcs.chat;

import java.util.Locale;

/**
 * SDP offer/answer for MSRP session-mode chat (RFC 4975 s8, GSMA RCC.07).
 *
 * <p>Only the message media type is handled — an RCS chat session negotiates exactly one
 * {@code m=message} stream carrying {@code message/cpim}.
 */
public final class MsrpSdp {

    /** Parsed view of the peer's SDP. */
    public static final class Parsed {
        public String path;          // a=path: msrp://host:port/session;tcp
        public String setup;         // a=setup: active | passive | actpass
        public String connectionIp;  // c= address
        public int port = -1;        // m=message port
        public String acceptTypes;
        public String fileSelector;  // a=file-selector, present for file transfer

        public String host() {
            if (path == null) return connectionIp;
            // msrp://[2607:..]:2855/abcd;tcp  or  msrp://10.0.0.1:2855/abcd;tcp
            final int schemeEnd = path.indexOf("://");
            if (schemeEnd < 0) return connectionIp;
            String rest = path.substring(schemeEnd + 3);
            if (rest.startsWith("[")) {
                final int close = rest.indexOf(']');
                return close > 0 ? rest.substring(1, close) : connectionIp;
            }
            final int colon = rest.indexOf(':');
            final int slash = rest.indexOf('/');
            final int end = (colon >= 0) ? colon : (slash >= 0 ? slash : rest.length());
            return rest.substring(0, end);
        }

        public int port() {
            if (path != null) {
                final int schemeEnd = path.indexOf("://");
                if (schemeEnd >= 0) {
                    String rest = path.substring(schemeEnd + 3);
                    if (rest.startsWith("[")) {
                        final int close = rest.indexOf(']');
                        if (close > 0) rest = rest.substring(close + 1);
                    }
                    final int colon = rest.indexOf(':');
                    if (colon >= 0) {
                        final int slash = rest.indexOf('/', colon);
                        final String p = rest.substring(colon + 1, slash > colon ? slash : rest.length());
                        try {
                            return Integer.parseInt(p.trim());
                        } catch (NumberFormatException ignored) {
                            // fall through to the m= port
                        }
                    }
                }
            }
            return port;
        }

        /** True when the peer expects us to open the TCP connection. */
        public boolean peerIsPassive() {
            return "passive".equalsIgnoreCase(setup) || "actpass".equalsIgnoreCase(setup);
        }
    }

    private MsrpSdp() {}

    /**
     * Builds an SDP body offering or answering an MSRP chat stream.
     *
     * @param localIp     address to advertise in {@code c=}
     * @param localPort   port to advertise in {@code m=}
     * @param localPath   our {@code msrp://} URI
     * @param active      true to take the active role (we connect out)
     */
    public static String build(String localIp, int localPort, String localPath, boolean active) {
        final boolean v6 = localIp != null && localIp.contains(":");
        final String addrType = v6 ? "IP6" : "IP4";
        final long sessionId = Math.abs(localPath.hashCode());

        final StringBuilder sb = new StringBuilder();
        sb.append("v=0\r\n");
        sb.append("o=- ").append(sessionId).append(' ').append(sessionId)
                .append(" IN ").append(addrType).append(' ').append(localIp).append("\r\n");
        sb.append("s=-\r\n");
        sb.append("c=IN ").append(addrType).append(' ').append(localIp).append("\r\n");
        sb.append("t=0 0\r\n");
        sb.append("m=message ").append(localPort).append(" TCP/MSRP *\r\n");
        sb.append("a=path:").append(localPath).append("\r\n");
        sb.append("a=setup:").append(active ? "active" : "passive").append("\r\n");
        sb.append("a=accept-types:message/cpim\r\n");
        sb.append("a=accept-wrapped-types:text/plain application/im-iscomposing+xml "
                + "message/imdn+xml\r\n");
        sb.append("a=sendrecv\r\n");
        return sb.toString();
    }

    public static Parsed parse(String sdp) {
        final Parsed parsed = new Parsed();
        if (sdp == null) return parsed;
        for (String raw : sdp.split("\r?\n")) {
            final String line = raw.trim();
            if (line.startsWith("c=")) {
                // c=IN IP6 2607:...
                final String[] parts = line.substring(2).trim().split("\\s+");
                if (parts.length >= 3) parsed.connectionIp = parts[2];
            } else if (line.startsWith("m=message")) {
                final String[] parts = line.substring(2).trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        parsed.port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                        // leave as -1; the a=path port is authoritative anyway
                    }
                }
            } else if (startsWithIgnoreCase(line, "a=path:")) {
                parsed.path = line.substring("a=path:".length()).trim();
            } else if (startsWithIgnoreCase(line, "a=setup:")) {
                parsed.setup = line.substring("a=setup:".length()).trim();
            } else if (startsWithIgnoreCase(line, "a=accept-types:")) {
                parsed.acceptTypes = line.substring("a=accept-types:".length()).trim();
            } else if (startsWithIgnoreCase(line, "a=file-selector:")) {
                parsed.fileSelector = line.substring("a=file-selector:".length()).trim();
            }
        }
        return parsed;
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.length() >= prefix.length()
                && s.substring(0, prefix.length()).toLowerCase(Locale.US).equals(prefix);
    }
}
