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
 * Enables resource-list presence subscription.
 *
 * <p>Individual per-contact subscription returns a populated document for this device's own
 * number and an empty one for everybody else. The vendor supports a second, entirely unexercised
 * path — {@code Subscriber} has exactly two builders and only the first has ever run:
 *
 * <pre>
 *   if (uris.size() == 1) { buildSubscriptionInfo(uris);      subscribeEvents(...); }
 *   else                  { buildListSubscriptionInfo(uris);  subscribeListEvents(...); }
 * </pre>
 *
 * <p>which produces a different request — {@code ContentType: XML_RESLIST} rather than
 * {@code CONTACT_URI}. It stays dead because AOSP hands the ImsService one URI at a time:
 * "When the group subscribe is disabled, each contact is required to be encapsulated into
 * individual UceRequest" (UceRequestManager#createSubscribeRequestCoordinator).
 *
 * <p>Enabling the carrier flag is only half of it; the request must also carry more than one URI,
 * which {@link CapabilityDiscoveryManager} arranges by batching.
 *
 * <p>Note the empty {@code RLS-URI} in the provisioning document is not an obstacle: the vendor
 * never reads it, and nothing in its APK references {@code RLS-URI}, {@code pres-list} or
 * {@code service-uritemplate}. The URI list is passed directly and the modem builds the request.
 *
 * <p>Unlike the earlier switch to OPTIONS, this only changes how outgoing subscriptions are
 * batched. It does not touch the vendor's {@code CapabilityDiscoveryTech}, so PUBLISH is
 * unaffected.
 */
public final class GroupSubscribeConfigurator {
    private static final String TAG = "GroupSubscribeConfig";

    private static final String KEY_GROUP_SUBSCRIBE = "ims.enable_presence_group_subscribe_bool";

    private static boolean sApplied;

    private GroupSubscribeConfigurator() {}

    /** Applies the override unless it is already in effect. */
    public static synchronized void applyIfNeeded(Context context) {
        if (sApplied) return;
        try {
            final int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
            if (!SubscriptionManager.isValidSubscriptionId(subId)) return;

            final CarrierConfigManager ccm = context.getSystemService(CarrierConfigManager.class);
            if (ccm == null) return;

            final PersistableBundle current = ccm.getConfigForSubId(subId);
            if (current == null || current.isEmpty()) {
                LogUtil.w(TAG, "Carrier config unreadable; not overriding");
                return;
            }
            // Read before writing: overrideConfig triggers CARRIER_CONFIG_CHANGED, which is what
            // re-runs this, so an unconditional write would loop.
            if (current.getBoolean(KEY_GROUP_SUBSCRIBE, false)) {
                LogUtil.i(TAG, "Group subscribe already enabled");
                sApplied = true;
                return;
            }

            final PersistableBundle override = new PersistableBundle();
            override.putBoolean(KEY_GROUP_SUBSCRIBE, true);
            ccm.overrideConfig(subId, override);
            sApplied = true;
            LogUtil.i(TAG, "Enabled presence group subscribe (resource-list subscription)");
        } catch (SecurityException e) {
            LogUtil.e(TAG, "overrideConfig denied — MODIFY_PHONE_STATE not granted. That "
                    + "allowlist ships in /product/etc/permissions and needs a ROM flash.", e);
        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to enable group subscribe", e);
        }
    }
}
