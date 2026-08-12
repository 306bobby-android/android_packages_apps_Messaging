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

import android.net.Uri;

import com.android.messaging.util.LogUtil;

import java.net.InetSocketAddress;

/**
 * Plain-field view of {@code android.telephony.ims.SipDelegateConfiguration}.
 *
 * <p>The configuration object is {@code @SystemApi} with several {@code @hide} accessors, so it is
 * read reflectively once and snapshotted here. Everything needed to build a well-formed in-dialog
 * SIP request comes from the ImsService, which is the point: the values the old socket stack had
 * to invent (public identity, home domain, Service-Route, Contact) are authoritative here.
 */
public final class SipConfigSnapshot {
    private static final String TAG = "SipConfigSnapshot";

    public static final int SIP_TRANSPORT_UDP = 0;
    public static final int SIP_TRANSPORT_TCP = 1;

    public final long version;
    public final int transportType;
    public final String publicUserIdentifier;
    public final String privateUserIdentifier;
    public final String homeDomain;
    public final String imei;
    public final String serviceRouteHeader;
    public final String pathHeader;
    public final String userAgentHeader;
    public final String contactUserParameter;
    public final Uri publicGruuUri;
    public final InetSocketAddress localAddress;
    public final InetSocketAddress sipServerAddress;
    public final int maxUdpPayloadSizeBytes;

    /**
     * Value for the {@code Security-Verify} header (RFC 3329).
     *
     * <p>The P-CSCF negotiated an IPsec security association during registration and rejects any
     * request that does not echo it, with {@code 494 Security Agreement Required}. The association
     * belongs to the ImsService, so this value has to be taken from the delegate configuration
     * rather than reconstructed.
     */
    public final String securityVerifyHeader;

    private SipConfigSnapshot(Object config) {
        version = readLong(config, "getVersion", -1);
        transportType = (int) readLong(config, "getTransportType", SIP_TRANSPORT_TCP);
        publicUserIdentifier = readString(config, "getPublicUserIdentifier");
        privateUserIdentifier = readString(config, "getPrivateUserIdentifier");
        homeDomain = readString(config, "getHomeDomain");
        imei = readString(config, "getImei");
        serviceRouteHeader = readString(config, "getSipServiceRouteHeader");
        pathHeader = readString(config, "getSipPathHeader");
        userAgentHeader = readString(config, "getSipUserAgentHeader");
        contactUserParameter = readString(config, "getSipContactUserParameter");
        publicGruuUri = (Uri) read(config, "getPublicGruuUri");
        localAddress = (InetSocketAddress) read(config, "getLocalAddress");
        sipServerAddress = (InetSocketAddress) read(config, "getSipServerAddress");
        maxUdpPayloadSizeBytes = (int) readLong(config, "getMaxUdpPayloadSizeBytes", 0);
        securityVerifyHeader = readSecurityVerifyHeader(config);
    }

    private static String readSecurityVerifyHeader(Object config) {
        final Object ipSec = read(config, "getIpSecConfiguration");
        if (ipSec == null) return null;
        try {
            final Object header = ipSec.getClass()
                    .getMethod("getSipSecurityVerifyHeader").invoke(ipSec);
            return (header instanceof String) ? (String) header : null;
        } catch (Throwable t) {
            LogUtil.w(TAG, "getSipSecurityVerifyHeader unavailable: " + t);
            return null;
        }
    }

    /** @return a snapshot, or null if {@code config} is null or unreadable. */
    public static SipConfigSnapshot from(Object config) {
        if (config == null) return null;
        try {
            return new SipConfigSnapshot(config);
        } catch (Throwable t) {
            LogUtil.e(TAG, "Failed to snapshot SipDelegateConfiguration", t);
            return null;
        }
    }

    /**
     * The URI this device is reachable at, preferring the GRUU when the network issued one.
     *
     * <p>A Contact is {@code user@host:port} where the host is this UA's own address, so only the
     * user part of the public identity is used. Appending the local address to the whole identity
     * produces {@code sip:user@domain@host}, which is not a valid SIP URI and is discarded by the
     * P-CSCF without any response at all.
     */
    public String localContactUri() {
        if (publicGruuUri != null) {
            return publicGruuUri.toString();
        }
        final StringBuilder sb = new StringBuilder("sip:");
        sb.append(userPart(publicUserIdentifier));
        if (localAddress != null && localAddress.getAddress() != null) {
            sb.append('@').append(formatHost(localAddress.getAddress().getHostAddress()));
            if (localAddress.getPort() > 0) {
                sb.append(':').append(localAddress.getPort());
            }
        } else if (homeDomain != null) {
            sb.append('@').append(homeDomain);
        }
        sb.append(";transport=").append(transportName().toLowerCase(java.util.Locale.US));
        return sb.toString();
    }

    /**
     * Contact header parameters that belong outside the URI's angle brackets.
     *
     * <p>The ImsService reports a UUID here. That is the instance identifier used for GRUU and
     * outbound registration, not a {@code user=} URI parameter, so it is emitted as
     * {@code +sip.instance}.
     */
    public String contactHeaderParams() {
        if (contactUserParameter == null || contactUserParameter.isEmpty()) return "";
        final String value = contactUserParameter.startsWith("urn:")
                ? contactUserParameter : "urn:uuid:" + contactUserParameter;
        return ";+sip.instance=\"<" + value + ">\"";
    }

    /** The portion of a SIP identity before the {@code @}. */
    private static String userPart(String uri) {
        final String bare = stripScheme(uri);
        if (bare == null) return "";
        final int at = bare.indexOf('@');
        return at >= 0 ? bare.substring(0, at) : bare;
    }

    /**
     * The originator address for end-to-end headers, as a tel URI.
     *
     * <p>{@link #localAor()} returns the registered public identity, which on this carrier is
     * derived from the IMSI ({@code sip:310260…@ims.mnc260.mcc310.3gppnetwork.org}). That is fine
     * for SIP signalling, where the network asserts the real identity, but it is wrong for CPIM:
     * those headers travel end to end, and both the receiving client and any interworking or
     * SMS-fallback gateway need a dialable originator. A message sent with an IMSI URI is accepted
     * by the network and then has no sender it can attribute or fall back to.
     *
     * @return {@code tel:+1XXXXXXXXXX}, or null if the subscription number is unavailable
     */
    public static String localTelUri(android.content.Context context) {
        try {
            final android.telephony.SubscriptionManager sm =
                    context.getSystemService(android.telephony.SubscriptionManager.class);
            if (sm == null) return null;
            final int subId = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
            if (!android.telephony.SubscriptionManager.isValidSubscriptionId(subId)) return null;

            final String number = sm.getPhoneNumber(subId);
            if (number == null || number.trim().isEmpty()) {
                LogUtil.w(TAG, "Subscription number unavailable; CPIM will fall back to the IMPU");
                return null;
            }
            final String digits = number.replaceAll("[^0-9+]", "");
            if (digits.isEmpty()) return null;
            return digits.startsWith("+") ? "tel:" + digits
                    : (digits.length() == 10 ? "tel:+1" + digits : "tel:+" + digits);
        } catch (Throwable t) {
            LogUtil.w(TAG, "Could not read subscription number: " + t);
            return null;
        }
    }

    /** The address-of-record used in From/To headers. */
    public String localAor() {
        final String id = stripScheme(publicUserIdentifier);
        if (id == null || id.isEmpty()) return null;
        return id.contains("@") ? "sip:" + id : "sip:" + id + "@" + homeDomain;
    }

    public String transportName() {
        return transportType == SIP_TRANSPORT_UDP ? "UDP" : "TCP";
    }

    /** Local IP without brackets, for SDP {@code c=} lines. */
    public String localIpLiteral() {
        if (localAddress == null || localAddress.getAddress() == null) return null;
        final String host = localAddress.getAddress().getHostAddress();
        if (host == null) return null;
        final int pct = host.indexOf('%');
        return pct >= 0 ? host.substring(0, pct) : host;
    }

    public boolean isIpV6() {
        final String host = localIpLiteral();
        return host != null && host.contains(":");
    }

    /** Wraps IPv6 literals in brackets for use inside SIP URIs. */
    public static String formatHost(String host) {
        if (host == null) return null;
        final int pct = host.indexOf('%');
        final String bare = pct >= 0 ? host.substring(0, pct) : host;
        return bare.contains(":") ? "[" + bare + "]" : bare;
    }

    private static String stripScheme(String uri) {
        if (uri == null) return null;
        String s = uri.trim();
        if (s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length() - 1);
        if (s.startsWith("sip:")) s = s.substring(4);
        else if (s.startsWith("sips:")) s = s.substring(5);
        else if (s.startsWith("tel:")) s = s.substring(4);
        return s;
    }

    private static Object read(Object target, String getter) {
        try {
            return target.getClass().getMethod(getter).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readString(Object target, String getter) {
        final Object v = read(target, getter);
        return (v instanceof String) ? (String) v : null;
    }

    private static long readLong(Object target, String getter, long fallback) {
        final Object v = read(target, getter);
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return ((Integer) v).longValue();
        return fallback;
    }

    @Override
    public String toString() {
        return "SipConfigSnapshot{version=" + version
                + ", transport=" + transportName()
                + ", impu=" + publicUserIdentifier
                + ", domain=" + homeDomain
                + ", local=" + localAddress
                + ", server=" + sipServerAddress
                + ", gruu=" + publicGruuUri
                + ", serviceRoute=" + serviceRouteHeader
                + ", securityVerify=" + securityVerifyHeader + "}";
    }
}
