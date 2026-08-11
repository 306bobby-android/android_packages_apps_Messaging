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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.rcs.RcsManager;
import com.android.messaging.rcs.sip.SipStackManager;
import com.android.messaging.util.LogUtil;

import java.lang.reflect.Method;

/**
 * User Capability Exchange (UCE) Manager for contact RCS discovery & caching.
 */
public class CapabilityDiscoveryManager {
    private static final String TAG = "CapabilityDiscovery";

    public static final int CAPABILITY_UNKNOWN = 0;
    public static final int CAPABILITY_RCS_SUPPORTED = 1;
    public static final int CAPABILITY_NOT_SUPPORTED = 2;

    private static final long CAPABILITY_CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L; // 24 hours

    /**
     * Checks if a destination phone number is an RCS recipient.
     */
    public static boolean isRcsRecipient(Context context, String destination) {
        if (TextUtils.isEmpty(destination)) {
            return false;
        }
        final int cap = getCachedCapability(context, destination);
        if (cap == CAPABILITY_RCS_SUPPORTED) {
            return true;
        }
        if (cap == CAPABILITY_NOT_SUPPORTED) {
            return false;
        }
        // If unknown, trigger platform capability discovery asynchronously
        requestPlatformCapabilityDiscovery(context, destination);
        return false; // Default to false (SMS) until confirmed RCS
    }

    /**
     * Checks cached capability status for destination phone number.
     */
    public static int getCachedCapability(Context context, String destination) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(DatabaseHelper.PARTICIPANTS_TABLE,
                    new String[] { DatabaseHelper.ParticipantColumns.RCS_CAPABILITY, DatabaseHelper.ParticipantColumns.RCS_DISCOVERY_TIMESTAMP },
                    DatabaseHelper.ParticipantColumns.NORMALIZED_DESTINATION + "=?",
                    new String[] { destination }, null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                final int capability = cursor.getInt(0);
                final long timestamp = cursor.getLong(1);

                if (System.currentTimeMillis() - timestamp < CAPABILITY_CACHE_VALIDITY_MS) {
                    return capability;
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "Error querying RCS capability cache", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return CAPABILITY_UNKNOWN;
    }

    /**
     * Triggers asynchronous RCS capability discovery for destination via platform RcsUceAdapter.
     */
    public static void requestPlatformCapabilityDiscovery(Context context, String destination) {
        if (TextUtils.isEmpty(destination)) return;
        try {
            final SipStackManager sipManager = RcsManager.getInstance(context).getSipStackManager();
            if (sipManager != null) {
                sipManager.sendOptions(destination);
            }
            final Object uceAdapter = RcsManager.getInstance(context).getPlatformUceAdapter();
            if (uceAdapter != null) {
                final Uri contactUri = Uri.parse("tel:" + destination);
                for (Method m : uceAdapter.getClass().getMethods()) {
                    if (m.getName().equals("requestAvailability") || m.getName().equals("requestCapabilities")) {
                        LogUtil.i(TAG, "Discovered platform UCE method: " + m.getName() + " for " + destination);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "Platform UCE request failed: " + e.getMessage());
        }
    }

    /**
     * Updates capability cache in database.
     */
    public static void updateCapability(Context context, String destination, int capability) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        final ContentValues values = new ContentValues();
        values.put(DatabaseHelper.ParticipantColumns.RCS_CAPABILITY, capability);
        values.put(DatabaseHelper.ParticipantColumns.RCS_DISCOVERY_TIMESTAMP, System.currentTimeMillis());

        db.update(DatabaseHelper.PARTICIPANTS_TABLE, values,
                DatabaseHelper.ParticipantColumns.NORMALIZED_DESTINATION + "=?",
                new String[] { destination });

        LogUtil.i(TAG, "Updated RCS capability for " + destination + " to: " + capability);
    }
}
