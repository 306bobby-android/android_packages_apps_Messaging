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

package com.android.messaging.rcs.uce;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ProvisioningManager;

import com.android.messaging.util.LogUtil;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Re-provisions the RCS configuration so the vendor stack performs capability discovery with SIP
 * OPTIONS instead of presence.
 *
 * <p>Switching the AOSP carrier config alone is not enough. The vendor's
 * {@code ShannonRcsCapabilityExchange.sendOptionsCapabilityRequest} refuses the request outright
 * unless its own discovery technology is OPTIONS:
 *
 * <pre>
 *   "sendOptionsCapabilityRequest, CapabilityDiscoveryTech is not selected OPTIONS"
 *   -> OptionsResponseCallback.onCommandError(...)
 * </pre>
 *
 * <p>That technology comes from the provisioning document rather than carrier config:
 *
 * <pre>
 *   int v = rule.getDefaultDiscValue();
 *   if (v == 0)      tech = OPTIONS;        // "Provisioned Capability Discovery Tech is OPTIONS"
 *   else if (v == 1) tech = VIA_PRESENCE;
 *   else             tech = UNSPECIFIED;
 * </pre>
 *
 * <p>The carrier provisions {@code defaultDisc = 1} under
 * {@code APPLICATION/PRESENCE/MESSAGING/CAPDISCOVERY}, which is where the spec puts it — but the
 * vendor never looks there. Its own logs name the two paths it consults, and falls back to a
 * hardcoded 1 when neither exists, which is how presence gets selected regardless of the document.
 * See {@link #SERVICES_OPEN}.
 *
 * <p>Rather than patch the vendor APK, the configuration it reads is amended: the parameter is
 * written into the node it actually consults and the document handed back through
 * {@link ProvisioningManager#notifyRcsAutoConfigurationReceived}. Everything else stays as the
 * carrier sent it.
 */
public final class RcsProvisioningTweaker {
    private static final String TAG = "RcsProvisioningTweaker";

    /** Any existing defaultDisc parameter, wherever it appears. */
    private static final Pattern DEFAULT_DISC = Pattern.compile(
            "<parm\\s+name=\"defaultDisc\"\\s+value=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    /**
     * Opening tag of the SERVICES characteristic.
     *
     * <p>The vendor does not read defaultDisc from where the spec puts it. Its own logs show the
     * two paths it consults, neither of which is the CAPDISCOVERY node the carrier populates:
     *
     * <pre>
     *   getIntegerValue, not exist /ShannonRcs/310/260/defaultDisc
     *   getIntegerValue, not exist /ap2008/SERVICES/defaultDisc
     *   getDefaultDiscValue: 1
     * </pre>
     *
     * <p>{@code ap2008} is the document's AppID, so the node it wants is APPLICATION/SERVICES.
     * With neither present it falls back to a hardcoded 1, i.e. presence. The parameter is
     * therefore inserted there.
     */
    private static final Pattern SERVICES_OPEN = Pattern.compile(
            "<characteristic\\s+type=\"SERVICES\"\\s*>", Pattern.CASE_INSENSITIVE);

    /** Value that selects OPTIONS in the vendor's rule evaluation. */
    private static final String DISC_OPTIONS = "0";

    private static boolean sRegistered;
    private static ProvisioningManager sManager;
    private static ProvisioningManager.RcsProvisioningCallback sCallback;

    private RcsProvisioningTweaker() {}

    /**
     * Watches the RCS configuration and rewrites it whenever the carrier provisions presence-based
     * discovery. Safe to call repeatedly.
     */
    public static synchronized void startWatching(Context context) {
        if (sRegistered) return;
        final int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            LogUtil.w(TAG, "No default SMS subscription; not watching provisioning");
            return;
        }

        try {
            sManager = ProvisioningManager.createForSubscriptionId(subId);
            sCallback = new ProvisioningManager.RcsProvisioningCallback() {
                @Override
                public void onConfigurationChanged(byte[] configXml) {
                    handleConfiguration(configXml);
                }

                @Override
                public void onConfigurationReset() {
                    LogUtil.i(TAG, "RCS configuration reset by the network");
                }

                @Override
                public void onRemoved() {
                    LogUtil.i(TAG, "RCS configuration removed");
                }
            };
            sManager.registerRcsProvisioningCallback(context.getMainExecutor(), sCallback);
            sRegistered = true;
            LogUtil.i(TAG, "Watching RCS provisioning for subId=" + subId);
        } catch (Exception e) {
            LogUtil.e(TAG, "Could not watch RCS provisioning", e);
        }
    }

    private static void handleConfiguration(byte[] configBytes) {
        if (configBytes == null || configBytes.length == 0) return;
        try {
            final String xml = new String(configBytes, StandardCharsets.UTF_8);
            final String patched = withOptionsDiscovery(xml);
            if (patched == null) {
                LogUtil.i(TAG, "Discovery already provisioned as OPTIONS");
                return;
            }
            LogUtil.i(TAG, "Re-provisioning " + patched.length() + " bytes with "
                    + "SERVICES/defaultDisc=" + DISC_OPTIONS);
            sManager.notifyRcsAutoConfigurationReceived(
                    patched.getBytes(StandardCharsets.UTF_8), false /* isCompressed */);
        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to re-provision RCS configuration", e);
        }
    }

    /**
     * Returns the document with OPTIONS-based discovery provisioned, or null if it already is.
     *
     * <p>The parameter is written under SERVICES because that is where the vendor looks. Any
     * existing occurrence elsewhere is corrected too, so the document does not contradict itself.
     */
    private static String withOptionsDiscovery(String xml) {
        final Matcher services = SERVICES_OPEN.matcher(xml);
        if (!services.find()) {
            LogUtil.w(TAG, "No SERVICES characteristic in the provisioning document");
            return null;
        }

        // Is there already a defaultDisc inside SERVICES, before that characteristic closes?
        final int servicesEnd = xml.indexOf("</characteristic>", services.end());
        final Matcher any = DEFAULT_DISC.matcher(xml);
        String result = xml;
        boolean inServices = false;
        int changes = 0;

        while (any.find()) {
            final String value = any.group(1);
            if (any.start() > services.end() && (servicesEnd < 0 || any.start() < servicesEnd)) {
                inServices = true;
                if (DISC_OPTIONS.equals(value)) {
                    LogUtil.i(TAG, "SERVICES/defaultDisc is already " + DISC_OPTIONS);
                    return null;
                }
            }
            LogUtil.i(TAG, "Found defaultDisc=" + value
                    + (any.start() > services.end() && (servicesEnd < 0 || any.start() < servicesEnd)
                            ? " (in SERVICES)" : " (elsewhere)"));
            changes++;
        }

        // Correct every occurrence, wherever it sits.
        result = DEFAULT_DISC.matcher(result).replaceAll(
                Matcher.quoteReplacement("<parm name=\"defaultDisc\" value=\"" + DISC_OPTIONS + "\""));

        if (!inServices) {
            // Insert it where the vendor actually reads it.
            final Matcher m2 = SERVICES_OPEN.matcher(result);
            if (!m2.find()) return null;
            result = result.substring(0, m2.end())
                    + "<parm name=\"defaultDisc\" value=\"" + DISC_OPTIONS + "\"/>"
                    + result.substring(m2.end());
            LogUtil.i(TAG, "Inserted defaultDisc into SERVICES (" + changes
                    + " other occurrence(s) corrected)");
        }
        return result;
    }
}
