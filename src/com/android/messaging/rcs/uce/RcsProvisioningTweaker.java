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
 * <p>and {@code UserCapabilityRule} reads it from the {@code defaultDisc} parameter under
 * {@code APPLICATION/PRESENCE/MESSAGING/CAPDISCOVERY}. This carrier provisions
 * {@code defaultDisc = 1}, selecting the presence mechanism that cannot see its own RCS users.
 *
 * <p>Rather than patch the vendor APK, the configuration it reads is amended: exactly one
 * attribute value is spliced and the document handed back through
 * {@link ProvisioningManager#notifyRcsAutoConfigurationReceived}. Everything else stays byte for
 * byte as the carrier sent it.
 */
public final class RcsProvisioningTweaker {
    private static final String TAG = "RcsProvisioningTweaker";

    /** The CAPDISCOVERY parameter that selects the discovery mechanism. */
    private static final Pattern DEFAULT_DISC = Pattern.compile(
            "<parm\\s+name=\"defaultDisc\"\\s+value=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

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
            final Matcher m = DEFAULT_DISC.matcher(xml);
            if (!m.find()) {
                LogUtil.i(TAG, "Provisioning carries no defaultDisc parameter; leaving it alone");
                return;
            }

            final String current = m.group(1);
            LogUtil.i(TAG, "Provisioned defaultDisc=" + current + " (0=OPTIONS, 1=presence)");
            if (DISC_OPTIONS.equals(current)) {
                // Already OPTIONS. This is also what keeps the re-injection below from looping,
                // since notifying a new configuration fires this callback again.
                LogUtil.i(TAG, "Discovery already provisioned as OPTIONS");
                return;
            }

            // Splice only the value so the rest of the document is untouched; a broader rewrite
            // risks disturbing parameters the carrier depends on.
            final String patched =
                    xml.substring(0, m.start(1)) + DISC_OPTIONS + xml.substring(m.end(1));
            LogUtil.i(TAG, "Rewriting defaultDisc " + current + " -> " + DISC_OPTIONS
                    + "; re-provisioning " + patched.length() + " bytes");

            sManager.notifyRcsAutoConfigurationReceived(
                    patched.getBytes(StandardCharsets.UTF_8), false /* isCompressed */);
        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to re-provision RCS configuration", e);
        }
    }
}
