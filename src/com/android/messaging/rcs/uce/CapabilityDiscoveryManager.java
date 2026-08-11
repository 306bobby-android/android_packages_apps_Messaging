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
import androidx.collection.ArrayMap;

import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.rcs.RcsManager;
import com.android.messaging.rcs.sip.SipStackManager;
import com.android.messaging.util.LogUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * User Capability Exchange (UCE) Manager for contact RCS discovery & caching.
 */
public class CapabilityDiscoveryManager {
    private static final String TAG = "CapabilityDiscovery";

    public static final int CAPABILITY_UNKNOWN = 0;
    public static final int CAPABILITY_RCS_SUPPORTED = 1;
    public static final int CAPABILITY_NOT_SUPPORTED = 2;

    private static final long CAPABILITY_CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L; // 24 hours
    private static final ArrayMap<String, Integer> sCapabilityCache = new ArrayMap<>();
    private static final Executor sAsyncExecutor = Executors.newSingleThreadExecutor();

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
        if (cap == CAPABILITY_UNKNOWN) {
            requestPlatformCapabilityDiscovery(context, destination);
        }
        return false;
    }

    /**
     * Checks cached capability status for destination phone number using fast in-memory lookup.
     */
    public static int getCachedCapability(Context context, String destination) {
        if (TextUtils.isEmpty(destination)) return CAPABILITY_UNKNOWN;
        synchronized (sCapabilityCache) {
            final Integer cached = sCapabilityCache.get(destination);
            if (cached != null) {
                return cached;
            }
        }

        // Query database on background thread if uncached
        sAsyncExecutor.execute(() -> {
            try {
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
                            synchronized (sCapabilityCache) {
                                sCapabilityCache.put(destination, capability);
                            }
                        }
                    }
                } finally {
                    if (cursor != null) cursor.close();
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "Error querying RCS capability cache", e);
            }
        });

        return CAPABILITY_UNKNOWN;
    }

    /**
     * Triggers asynchronous RCS capability discovery via platform Telephony RcsUceAdapter.
     */
    public static void requestPlatformCapabilityDiscovery(Context context, String destination) {
        if (TextUtils.isEmpty(destination)) return;
        sAsyncExecutor.execute(() -> {
            try {
                final Object uceAdapter = RcsManager.getInstance(context).getPlatformUceAdapter();
                if (uceAdapter != null) {
                    final Uri contactUri = Uri.parse("tel:" + destination);
                    final Class<?> callbackClass = Class.forName("android.telephony.ims.RcsUceAdapter$CapabilitiesCallback");

                    final Object callbackProxy = Proxy.newProxyInstance(
                            context.getClassLoader(),
                            new Class<?>[] { callbackClass },
                            (proxy, method, args) -> {
                                final String methodName = method.getName();
                                if ("onCapabilitiesReceived".equals(methodName)) {
                                    final Object capabilities = args[0];
                                    if (capabilities != null) {
                                        try {
                                            final Method isCapableMethod = capabilities.getClass().getMethod("isCapable", int.class);
                                            // FEATURE_TAG_CHAT_IM = 1
                                            final Boolean isCapable = (Boolean) isCapableMethod.invoke(capabilities, 1);
                                            final int resultCap = (isCapable != null && isCapable) ? CAPABILITY_RCS_SUPPORTED : CAPABILITY_NOT_SUPPORTED;
                                            updateCapability(context, destination, resultCap);
                                            LogUtil.i(TAG, "Platform UCE capabilities received for " + destination + ": isCapable=" + isCapable);
                                        } catch (Exception e) {
                                            updateCapability(context, destination, CAPABILITY_RCS_SUPPORTED);
                                        }
                                    }
                                } else if ("onError".equals(methodName)) {
                                    LogUtil.w(TAG, "Platform UCE discovery error for " + destination + ": " + args[0]);
                                }
                                return null;
                            }
                    );

                    final Method requestAvail = uceAdapter.getClass().getMethod("requestAvailability", Uri.class, Executor.class, callbackClass);
                    requestAvail.invoke(uceAdapter, contactUri, context.getMainExecutor(), callbackProxy);
                    LogUtil.i(TAG, "Successfully invoked platform UCE requestAvailability for " + destination);
                } else {
                    final SipStackManager sipManager = RcsManager.getInstance(context).getSipStackManager();
                    if (sipManager != null) {
                        sipManager.sendOptions(destination);
                    }
                }
            } catch (Exception e) {
                LogUtil.w(TAG, "Platform UCE request failed for " + destination + ": " + e.getMessage());
            }
        });
    }

    /**
     * Updates capability cache in database and in-memory map.
     */
    public static void updateCapability(Context context, String destination, int capability) {
        if (TextUtils.isEmpty(destination)) return;
        synchronized (sCapabilityCache) {
            sCapabilityCache.put(destination, capability);
        }

        sAsyncExecutor.execute(() -> {
            try {
                final DatabaseWrapper db = DataModel.get().getDatabase();
                final ContentValues values = new ContentValues();
                values.put(DatabaseHelper.ParticipantColumns.RCS_CAPABILITY, capability);
                values.put(DatabaseHelper.ParticipantColumns.RCS_DISCOVERY_TIMESTAMP, System.currentTimeMillis());

                db.update(DatabaseHelper.PARTICIPANTS_TABLE, values,
                        DatabaseHelper.ParticipantColumns.NORMALIZED_DESTINATION + "=?",
                        new String[] { destination });

                LogUtil.i(TAG, "Updated RCS capability for " + destination + " to: " + capability);
            } catch (Exception e) {
                LogUtil.e(TAG, "Error updating capability cache", e);
            }
        });
    }
}
