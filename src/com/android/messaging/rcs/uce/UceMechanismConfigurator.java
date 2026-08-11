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
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;

import com.android.messaging.util.LogUtil;


/**
 * Switches platform capability discovery from presence to SIP OPTIONS.
 *
 * <p>This carrier's presence server does not hold capabilities for its own RCS users — contacts
 * with working RCS come back NOT_FOUND, or FOUND with an empty document, on live network answers.
 * The only populated presence document this device has ever seen is its own. SIP OPTIONS asks the
 * contact's device instead, which is what GSMA defines for that gap.
 *
 * <p>An app cannot send OPTIONS itself: AOSP's {@code RestrictedOutgoingSipRequestValidator}
 * reserves {@code register}, {@code options} and {@code publish} for the ImsService and rejects
 * them from a SipDelegate with {@code MESSAGE_FAILURE_REASON_INVALID_START_LINE}. Discovery has to
 * go through the platform, and the platform picks its mechanism from carrier config
 * (UceRequestManager#sendRequestInternal):
 *
 * <pre>
 *   if (isPresenceCapExchangeEnabled() && isPresenceSupported()) -> presence SUBSCRIBE
 *   else if (isSipOptionsSupported())                            -> SIP OPTIONS
 * </pre>
 *
 * <p>Presence wins whenever it is enabled, so OPTIONS is only reachable by turning presence
 * capability exchange off. Crucially that does not cost us PUBLISH: RcsFeatureManager gates the
 * PRESENCE capability on {@code KEY_ENABLE_PRESENCE_PUBLISH_BOOL}, a different key, so publishing
 * our own capabilities keeps working while discovery moves to OPTIONS.
 *
 * <p>The values are applied as a carrier config override rather than a framework default because
 * {@code tmobile_us.pb} sets the capability-exchange key explicitly, and an app-supplied override
 * is the only layer that beats the carrier config app.
 */
public final class UceMechanismConfigurator {
    private static final String TAG = "UceMechanismConfig";

    private static final String KEY_PRESENCE_CAP_EXCHANGE =
            "ims.enable_presence_capability_exchange_bool";
    private static final String KEY_PRESENCE_PUBLISH = "ims.enable_presence_publish_bool";
    private static final String KEY_USE_SIP_OPTIONS = "use_rcs_sip_options_bool";

    private static boolean sApplied;

    private UceMechanismConfigurator() {}

    /**
     * Applies the override if it is not already in effect.
     *
     * <p>Reading the current values first is not just an optimisation: overrideConfig triggers
     * CARRIER_CONFIG_CHANGED, which is what re-runs this, so writing unconditionally would loop.
     */
    public static synchronized void applyIfNeeded(Context context) {
        if (sApplied) return;
        try {
            final int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
            if (!SubscriptionManager.isValidSubscriptionId(subId)) return;

            final CarrierConfigManager ccm = context.getSystemService(CarrierConfigManager.class);
            if (ccm == null) return;

            final PersistableBundle current = ccm.getConfigForSubId(subId);
            if (current == null || current.isEmpty()) {
                // Without READ_PRIVILEGED_PHONE_STATE this comes back filtered, and the guard
                // below would never trip.
                LogUtil.w(TAG, "Carrier config unreadable; not overriding");
                return;
            }

            final boolean presenceExchange = current.getBoolean(KEY_PRESENCE_CAP_EXCHANGE, false);
            final boolean sipOptions = current.getBoolean(KEY_USE_SIP_OPTIONS, false);
            LogUtil.i(TAG, "Current UCE config: presenceCapExchange=" + presenceExchange
                    + " sipOptions=" + sipOptions
                    + " presencePublish=" + current.getBoolean(KEY_PRESENCE_PUBLISH, false));

            if (!presenceExchange && sipOptions) {
                LogUtil.i(TAG, "OPTIONS-based discovery already configured");
                sApplied = true;
                return;
            }

            final PersistableBundle override = new PersistableBundle();
            override.putBoolean(KEY_PRESENCE_CAP_EXCHANGE, false);
            override.putBoolean(KEY_USE_SIP_OPTIONS, true);
            // Left explicitly true: this is what keeps our own capabilities published.
            override.putBoolean(KEY_PRESENCE_PUBLISH, true);

            if (overrideConfig(ccm, subId, override)) {
                sApplied = true;
                LogUtil.i(TAG, "Applied UCE override: discovery via SIP OPTIONS, publish retained");
            }
        } catch (Throwable t) {
            LogUtil.e(TAG, "Failed to apply UCE mechanism override", t);
        }
    }

    /**
     * Applies the override.
     *
     * <p>Only the two-argument form is used. The persistent three-argument variant is
     * {@code @hide}, so reflection at it is refused by hidden-API enforcement:
     * "Accessing hidden method ... overrideConfig(ILandroid/os/PersistableBundle;Z)V ... denied".
     * The two-argument form does not survive a carrier config reload, which is why this is
     * re-applied whenever RCS initialises.
     */
    private static boolean overrideConfig(CarrierConfigManager ccm, int subId,
            PersistableBundle override) {
        try {
            ccm.overrideConfig(subId, override);
            return true;
        } catch (SecurityException e) {
            LogUtil.e(TAG, "overrideConfig denied — MODIFY_PHONE_STATE not granted. The privapp "
                    + "allowlist ships in /product/etc/permissions, so it needs a ROM flash "
                    + "rather than an app update.", e);
            return false;
        } catch (Exception e) {
            LogUtil.e(TAG, "overrideConfig failed", e);
            return false;
        }
    }
}
