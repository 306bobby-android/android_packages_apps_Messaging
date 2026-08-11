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
import com.android.messaging.util.PhoneUtils;

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
    private static final long MIN_DISCOVERY_INTERVAL_MS = 30 * 1000L; // 30 seconds debounce

    private static final ArrayMap<String, Integer> sCapabilityCache = new ArrayMap<>();
    private static final ArrayMap<String, Long> sLastDiscoveryMap = new ArrayMap<>();
    private static final Executor sAsyncExecutor = Executors.newSingleThreadExecutor();

    /**
     * Normalizes destination phone number to E.164 standard (+1XXXXXXXXXX).
     */
    private static String normalizeDestination(Context context, String destination) {
        if (TextUtils.isEmpty(destination)) return "";
        final String digits = destination.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+")) return digits;
        if (digits.length() == 10) return "+1" + digits;
        if (digits.length() == 11 && digits.startsWith("1")) return "+" + digits;
        return PhoneUtils.get(com.android.messaging.datamodel.data.ParticipantData.DEFAULT_SELF_SUB_ID).getCanonicalBySimLocale(destination);
    }

    /**
     * Checks if a destination phone number is an RCS recipient.
     */
    public static boolean isRcsRecipient(Context context, String destination) {
        final String normalized = normalizeDestination(context, destination);
        if (TextUtils.isEmpty(normalized)) {
            return false;
        }
        final int cap = getCachedCapability(context, normalized);
        if (cap == CAPABILITY_RCS_SUPPORTED) {
            return true;
        }
        if (cap == CAPABILITY_UNKNOWN) {
            requestPlatformCapabilityDiscovery(context, normalized);
        }
        return false;
    }

    /**
     * Checks cached capability status for destination phone number using fast in-memory lookup.
     */
    public static int getCachedCapability(Context context, String destination) {
        final String normalized = normalizeDestination(context, destination);
        if (TextUtils.isEmpty(normalized)) return CAPABILITY_UNKNOWN;

        synchronized (sCapabilityCache) {
            final Integer cached = sCapabilityCache.get(normalized);
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
                            DatabaseHelper.ParticipantColumns.NORMALIZED_DESTINATION + "=? OR " + DatabaseHelper.ParticipantColumns.DISPLAY_DESTINATION + "=?",
                            new String[] { normalized, destination }, null, null, null);

                    if (cursor != null && cursor.moveToFirst()) {
                        final int capability = cursor.getInt(0);
                        final long timestamp = cursor.getLong(1);

                        if (System.currentTimeMillis() - timestamp < CAPABILITY_CACHE_VALIDITY_MS) {
                            synchronized (sCapabilityCache) {
                                sCapabilityCache.put(normalized, capability);
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
        final String normalized = normalizeDestination(context, destination);
        if (TextUtils.isEmpty(normalized)) return;

        synchronized (sLastDiscoveryMap) {
            final Long lastDiscovery = sLastDiscoveryMap.get(normalized);
            if (lastDiscovery != null && (System.currentTimeMillis() - lastDiscovery < MIN_DISCOVERY_INTERVAL_MS)) {
                return; // Debounced
            }
            sLastDiscoveryMap.put(normalized, System.currentTimeMillis());
        }

        sAsyncExecutor.execute(() -> {
            try {
                final Object uceAdapter = RcsManager.getInstance(context).getPlatformUceAdapter();
                if (uceAdapter != null) {
                    final Uri contactUri = Uri.parse("tel:" + normalized);
                    final Class<?> callbackClass = Class.forName("android.telephony.ims.RcsUceAdapter$CapabilitiesCallback");

                    final Object callbackProxy = Proxy.newProxyInstance(
                            context.getClassLoader(),
                            new Class<?>[] { callbackClass },
                            (proxy, method, args) -> {
                                final String methodName = method.getName();
                                LogUtil.i(TAG, "Platform UCE proxy callback invoked: " + methodName + " for " + normalized);

                                if ("onCapabilitiesReceived".equals(methodName)) {
                                    final Object arg = (args != null && args.length > 0) ? args[0] : null;
                                    LogUtil.i(TAG, "Platform UCE capabilities payload for " + normalized + ": " + arg);
                                    int resultCap = CAPABILITY_RCS_SUPPORTED;
                                    if (arg instanceof java.util.List) {
                                        final java.util.List<?> list = (java.util.List<?>) arg;
                                        if (list.isEmpty()) {
                                            resultCap = CAPABILITY_NOT_SUPPORTED;
                                        }
                                    }
                                    updateCapability(context, normalized, resultCap);
                                    LogUtil.i(TAG, "Platform UCE capability resolved for " + normalized + " -> " + resultCap);
                                } else if ("onError".equals(methodName)) {
                                    LogUtil.w(TAG, "Platform UCE discovery error for " + normalized + ": " + (args != null && args.length > 0 ? args[0] : "unknown"));
                                }
                                return null;
                            }
                    );

                    try {
                        final Method reqAvail = uceAdapter.getClass().getMethod("requestAvailability", Uri.class, Executor.class, callbackClass);
                        reqAvail.invoke(uceAdapter, contactUri, context.getMainExecutor(), callbackProxy);
                        LogUtil.i(TAG, "Successfully requested UCE requestAvailability for " + normalized);
                    } catch (Exception e) {
                        try {
                            final Method reqCaps = uceAdapter.getClass().getMethod("requestCapabilities", java.util.List.class, Executor.class, callbackClass);
                            reqCaps.invoke(uceAdapter, java.util.Collections.singletonList(contactUri), context.getMainExecutor(), callbackProxy);
                            LogUtil.i(TAG, "Successfully requested UCE requestCapabilities for " + normalized);
                        } catch (Exception ex) {
                            LogUtil.w(TAG, "Platform UCE method invocation failed for " + normalized + ": " + ex.getMessage());
                        }
                    }
                } else {
                    final SipStackManager sipManager = RcsManager.getInstance(context).getSipStackManager();
                    if (sipManager != null) {
                        sipManager.sendOptions(normalized);
                    }
                }
            } catch (Exception e) {
                LogUtil.w(TAG, "Platform UCE request failed for " + normalized + ": " + e.getMessage());
            }
        });
    }

    /**
     * Updates capability cache in database and in-memory map.
     */
    public static void updateCapability(Context context, String destination, int capability) {
        final String normalized = normalizeDestination(context, destination);
        if (TextUtils.isEmpty(normalized)) return;

        synchronized (sCapabilityCache) {
            sCapabilityCache.put(normalized, capability);
        }

        sAsyncExecutor.execute(() -> {
            try {
                final DatabaseWrapper db = DataModel.get().getDatabase();
                final ContentValues values = new ContentValues();
                values.put(DatabaseHelper.ParticipantColumns.RCS_CAPABILITY, capability);
                values.put(DatabaseHelper.ParticipantColumns.RCS_DISCOVERY_TIMESTAMP, System.currentTimeMillis());

                final int updatedRows = db.update(DatabaseHelper.PARTICIPANTS_TABLE, values,
                        DatabaseHelper.ParticipantColumns.NORMALIZED_DESTINATION + "=? OR " + DatabaseHelper.ParticipantColumns.DISPLAY_DESTINATION + "=?",
                        new String[] { normalized, destination });

                LogUtil.i(TAG, "Updated RCS capability in database for " + normalized + " to: " + capability + " (rows updated=" + updatedRows + ")");
            } catch (Exception e) {
                LogUtil.e(TAG, "Error updating capability cache in DB", e);
            }
        });
    }
}
