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
import java.util.Map;

import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.RcsManager;
import com.android.messaging.rcs.sip.SipStackManager;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

    private static String capabilityToString(int capability) {
        switch (capability) {
            case CAPABILITY_RCS_SUPPORTED: return "RCS_SUPPORTED";
            case CAPABILITY_NOT_SUPPORTED: return "NOT_SUPPORTED";
            case CAPABILITY_UNKNOWN:
            default: return "UNKNOWN";
        }
    }

    /**
     * Normalizes destination phone number to E.164 standard (+1XXXXXXXXXX).
     */
    public static String normalizeDestination(Context context, String destination) {
        if (TextUtils.isEmpty(destination)) return "";
        final String digits = destination.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+")) return digits;
        if (digits.length() == 10) return "+1" + digits;
        if (digits.length() == 11 && digits.startsWith("1")) return "+" + digits;
        return PhoneUtils.get(ParticipantData.DEFAULT_SELF_SUB_ID).getCanonicalBySimLocale(destination);
    }

    /**
     * Checks if a destination phone number is an RCS recipient.
     */
    public static boolean isRcsRecipient(Context context, String destination) {
        final String normalized = normalizeDestination(context, destination);
        if (TextUtils.isEmpty(normalized)) {
            LogUtil.d(TAG, "isRcsRecipient: empty destination, returning false");
            return false;
        }
        final int cap = getCachedCapability(context, normalized);
        LogUtil.i(TAG, "isRcsRecipient: destination=" + normalized + " capability=" + capabilityToString(cap));
        if (cap == CAPABILITY_UNKNOWN) {
            // Debounce: only trigger discovery if we haven't recently requested for this number
            boolean shouldDiscover = false;
            synchronized (sLastDiscoveryMap) {
                final Long lastTime = sLastDiscoveryMap.get(normalized);
                final long now = System.currentTimeMillis();
                if (lastTime == null || (now - lastTime) > MIN_DISCOVERY_INTERVAL_MS) {
                    sLastDiscoveryMap.put(normalized, now);
                    shouldDiscover = true;
                }
            }
            if (shouldDiscover) {
                LogUtil.i(TAG, "isRcsRecipient: UNKNOWN capability for " + normalized + ", triggering discovery (defaulting to SMS)");
                requestPlatformCapabilityDiscovery(context, normalized);
            } else {
                LogUtil.d(TAG, "isRcsRecipient: UNKNOWN capability for " + normalized + ", discovery debounced (defaulting to SMS)");
            }
            return false;
        }
        return cap == CAPABILITY_RCS_SUPPORTED;
    }

    /**
     * Checks cached capability status for destination phone number using fast in-memory lookup.
     */
    public static int getCachedCapability(Context context, String destination) {
        if (TextUtils.isEmpty(destination)) return CAPABILITY_UNKNOWN;
        final String digits = destination.replaceAll("[^0-9]", "");
        final String suffix = digits.length() >= 10 ? digits.substring(digits.length() - 10) : digits;

        synchronized (sCapabilityCache) {
            for (Map.Entry<String, Integer> entry : sCapabilityCache.entrySet()) {
                final String cacheKeyDigits = entry.getKey().replaceAll("[^0-9]", "");
                if (cacheKeyDigits.endsWith(suffix)) {
                    final int cachedCap = entry.getValue();
                    LogUtil.d(TAG, "getCachedCapability: in-memory cache HIT for suffix " + suffix + " -> " + capabilityToString(cachedCap));
                    return cachedCap;
                }
            }
        }

        final String normalized = normalizeDestination(context, destination);

        LogUtil.d(TAG, "getCachedCapability: in-memory cache MISS for " + normalized + ", querying database async");

        // Query database on background thread if uncached
        sAsyncExecutor.execute(() -> {
            try {
                final DatabaseWrapper db = DataModel.get().getDatabase();
                Cursor cursor = null;
                try {
                    final String dbDigits = normalized.replaceAll("[^0-9]", "");
                    final String dbSuffix = dbDigits.length() >= 10 ? dbDigits.substring(dbDigits.length() - 10) : dbDigits;
                    cursor = db.query(DatabaseHelper.PARTICIPANTS_TABLE,
                            new String[] { DatabaseHelper.ParticipantColumns.RCS_CAPABILITY, DatabaseHelper.ParticipantColumns.RCS_DISCOVERY_TIMESTAMP },
                            DatabaseHelper.ParticipantColumns.NORMALIZED_DESTINATION + " LIKE ? OR " + DatabaseHelper.ParticipantColumns.DISPLAY_DESTINATION + " LIKE ?",
                            new String[] { "%" + dbSuffix, "%" + dbSuffix }, null, null, null);

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
     * Forcefully triggers UCE capability discovery bypassing debouncing timers.
     */
    public static void forceRefreshCapability(Context context, String destination) {
        LogUtil.i(TAG, "forceRefreshCapability: forcing rediscovery for " + destination);
        final String normalized = normalizeDestination(context, destination);
        if (TextUtils.isEmpty(normalized)) return;
        synchronized (sLastDiscoveryMap) {
            sLastDiscoveryMap.remove(normalized);
        }
        synchronized (sCapabilityCache) {
            sCapabilityCache.remove(normalized);
        }
        requestPlatformCapabilityDiscovery(context, normalized);

        // Also send SIP OPTIONS query directly via SipStackManager as fallback
        final SipStackManager sipManager = RcsManager.getInstance(context).getSipStackManager();
        if (sipManager != null) {
            sipManager.sendOptions(normalized);
        }
    }
    public static void requestBatchCapabilityDiscovery(Context context, List<String> destinations) {
        if (destinations == null || destinations.isEmpty()) return;
        sAsyncExecutor.execute(() -> {
            LogUtil.i(TAG, "===== RCS DISCOVERY START =====");
            LogUtil.i(TAG, "Discovery request for " + destinations.size() + " destinations: " + destinations);
            try {
                final Object uceAdapter = RcsManager.getInstance(context).getPlatformUceAdapter();
                if (uceAdapter == null) {
                    LogUtil.w(TAG, "Platform UCE adapter is null — discovery cannot proceed. Is IMS registered?");
                    LogUtil.i(TAG, "===== RCS DISCOVERY END (no adapter) =====");
                    return;
                }

                final List<Uri> uris = new ArrayList<>();
                for (String dest : destinations) {
                    final String norm = normalizeDestination(context, dest);
                    if (!TextUtils.isEmpty(norm)) {
                        final Uri contactUri = Uri.parse("tel:" + norm);
                        if (!uris.contains(contactUri)) {
                            uris.add(contactUri);
                        }
                    }
                }
                LogUtil.i(TAG, "Built " + uris.size() + " tel: URIs for discovery: " + uris);
                if (uris.isEmpty()) {
                    LogUtil.i(TAG, "===== RCS DISCOVERY END (no URIs) =====");
                    return;
                }

                LogUtil.i(TAG, "Submitting batch platform UCE capability discovery for " + uris.size() + " contacts");
                final Class<?> callbackClass = Class.forName("android.telephony.ims.RcsUceAdapter$CapabilitiesCallback");

                final java.util.Set<String> resolvedDestinations = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

                final Object callbackProxy = Proxy.newProxyInstance(
                        context.getClassLoader(),
                        new Class<?>[] { callbackClass },
                        (proxy, method, args) -> {
                            final String methodName = method.getName();
                            LogUtil.i(TAG, "Batch UCE proxy callback invoked: " + methodName);

                            if ("onCapabilitiesReceived".equals(methodName)) {
                                final Object arg = (args != null && args.length > 0) ? args[0] : null;
                                if (arg instanceof List) {
                                    final List<?> capabilitiesList = (List<?>) arg;
                                    LogUtil.i(TAG, "onCapabilitiesReceived payload list count: " + capabilitiesList.size());
                                    for (Object capObj : capabilitiesList) {
                                        if (capObj == null) continue;
                                        try {
                                            String contactDest = null;
                                            for (Method m : capObj.getClass().getMethods()) {
                                                if (m.getReturnType().equals(Uri.class) && m.getParameterTypes().length == 0) {
                                                    try {
                                                        final Uri u = (Uri) m.invoke(capObj);
                                                        if (u != null) {
                                                            contactDest = u.getSchemeSpecificPart();
                                                            break;
                                                        }
                                                    } catch (Exception ignored) {}
                                                }
                                            }

                                            boolean isCapable = true;
                                            try {
                                                final Method isCapMethod = capObj.getClass().getMethod("isCapable", int.class);
                                                final Boolean isCapRes = (Boolean) isCapMethod.invoke(capObj, 1);
                                                if (isCapRes != null) isCapable = isCapRes;
                                            } catch (Exception ignored) {}

                                            if (contactDest != null) {
                                                resolvedDestinations.add(contactDest);
                                                final int resCap = isCapable ? CAPABILITY_RCS_SUPPORTED : CAPABILITY_NOT_SUPPORTED;
                                                updateCapability(context, contactDest, resCap);
                                                LogUtil.i(TAG, "Batch resolved RCS capability for " + contactDest + " -> " + resCap + " (isCapable=" + isCapable + ")");
                                            }
                                        } catch (Exception e) {
                                            LogUtil.w(TAG, "Error parsing batch contact capability item: " + e.getMessage());
                                        }
                                    }
                                }
                            } else if ("onComplete".equals(methodName)) {
                                // Mark any requested URIs that were NOT resolved as NOT_SUPPORTED
                                LogUtil.i(TAG, "onComplete: resolved " + resolvedDestinations.size() + " of " + uris.size() + " requested URIs");
                                for (Uri requestedUri : uris) {
                                    final String reqDest = requestedUri.getSchemeSpecificPart();
                                    if (!resolvedDestinations.contains(reqDest)) {
                                        LogUtil.i(TAG, "onComplete: no capability received for " + reqDest + " — marking as NOT_SUPPORTED");
                                        updateCapability(context, reqDest, CAPABILITY_NOT_SUPPORTED);
                                    }
                                }
                            } else if ("onError".equals(methodName)) {
                                LogUtil.w(TAG, "Batch UCE discovery error: " + (args != null && args.length > 0 ? args[0] : "unknown"));
                                // On error, mark all as NOT_SUPPORTED so we don't keep retrying
                                for (Uri requestedUri : uris) {
                                    final String reqDest = requestedUri.getSchemeSpecificPart();
                                    if (!resolvedDestinations.contains(reqDest)) {
                                        LogUtil.i(TAG, "onError: marking " + reqDest + " as NOT_SUPPORTED due to error");
                                        updateCapability(context, reqDest, CAPABILITY_NOT_SUPPORTED);
                                    }
                                }
                            }
                            return null;
                        }
                );

                Method reqMethod = null;
                boolean isBatch = true;
                try {
                    reqMethod = uceAdapter.getClass().getMethod("requestCapabilities", Collection.class, Executor.class, callbackClass);
                } catch (Exception e1) {
                    try {
                        reqMethod = uceAdapter.getClass().getMethod("requestCapabilities", List.class, Executor.class, callbackClass);
                    } catch (Exception e2) {
                        try {
                            reqMethod = uceAdapter.getClass().getMethod("requestAvailability", Uri.class, Executor.class, callbackClass);
                            isBatch = false;
                        } catch (Exception ignored) {}
                    }
                }

                if (reqMethod != null) {
                    if (isBatch) {
                        reqMethod.invoke(uceAdapter, uris, context.getMainExecutor(), callbackProxy);
                        LogUtil.i(TAG, "Successfully executed batch UCE requestCapabilities for " + uris.size() + " URIs");
                    } else {
                        for (Uri u : uris) {
                            reqMethod.invoke(uceAdapter, u, context.getMainExecutor(), callbackProxy);
                        }
                        LogUtil.i(TAG, "Successfully executed UCE requestAvailability for " + uris.size() + " URIs");
                    }
                } else {
                    LogUtil.w(TAG, "No compatible UCE method found on RcsUceAdapter");
                }
                LogUtil.i(TAG, "===== RCS DISCOVERY END =====");
            } catch (Exception e) {
                LogUtil.w(TAG, "Batch UCE discovery failed: " + e.getMessage());
                LogUtil.i(TAG, "===== RCS DISCOVERY END (error) =====");
            }
        });
    }

    /**
     * Triggers asynchronous RCS capability discovery via platform Telephony RcsUceAdapter for single contact.
     */
    public static void requestPlatformCapabilityDiscovery(Context context, String destination) {
        final List<String> singleList = new ArrayList<>();
        singleList.add(destination);
        requestBatchCapabilityDiscovery(context, singleList);
    }

    /**
     * Updates capability cache in database and in-memory map.
     */
    public static void updateCapability(Context context, String destination, int capability) {
        LogUtil.i(TAG, "updateCapability: destination=" + destination + " capability=" + capabilityToString(capability));
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

                final String digits = normalized.replaceAll("[^0-9]", "");
                final String suffix = digits.length() >= 10 ? digits.substring(digits.length() - 10) : digits;
                final int updatedRows = db.update(DatabaseHelper.PARTICIPANTS_TABLE, values,
                        DatabaseHelper.ParticipantColumns.NORMALIZED_DESTINATION + " LIKE ? OR " + DatabaseHelper.ParticipantColumns.DISPLAY_DESTINATION + " LIKE ?",
                        new String[] { "%" + suffix, "%" + suffix });

                LogUtil.i(TAG, "Updated RCS capability in database for " + normalized + " to: " + capability + " (rows updated=" + updatedRows + ")");
            } catch (Exception e) {
                LogUtil.e(TAG, "Error updating capability cache in DB", e);
            }
        });
    }
}
