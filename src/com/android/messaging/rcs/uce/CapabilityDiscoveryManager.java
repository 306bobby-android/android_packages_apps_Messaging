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
    // The platform serializes UCE SUBSCRIBE requests and lets each one run for up to 180s before
    // timing out, so re-asking aggressively only builds an unservable backlog. Retry no faster
    // than once a minute per contact.
    private static final long MIN_DISCOVERY_INTERVAL_MS = 60 * 1000L;
    // Hard ceiling on requests we allow to be outstanding in the platform UCE queue at once.
    private static final int MAX_IN_FLIGHT_REQUESTS = 4;
    // Safety valve: drop an in-flight entry if the platform never calls back, so a lost callback
    // cannot permanently wedge discovery for that contact.
    private static final long IN_FLIGHT_TIMEOUT_MS = 200 * 1000L;

    // Mirrors android.telephony.ims.RcsContactUceCapability, which is @SystemApi and therefore
    // not linkable from this app's public-SDK build (see Android.bp sdk_version: "current").
    private static final int REQUEST_RESULT_UNKNOWN = 0;
    private static final int REQUEST_RESULT_NOT_ONLINE = 1;
    private static final int REQUEST_RESULT_NOT_FOUND = 2;
    private static final int REQUEST_RESULT_FOUND = 3;

    private static final int CAPABILITY_MECHANISM_PRESENCE = 1;
    private static final int CAPABILITY_MECHANISM_OPTIONS = 2;

    private static final String TUPLE_BASIC_STATUS_OPEN = "open";

    /** Presence service-ids that mean "this contact can receive an RCS chat message". */
    private static final java.util.Set<String> MESSAGING_SERVICE_IDS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "org.openmobilealliance:ChatSession",    // SERVICE_ID_CHAT_V2
                    "org.openmobilealliance:IM-session",     // SERVICE_ID_CHAT_V1
                    "org.openmobilealliance:StandaloneMsg"));// SERVICE_ID_SLM

    /** OPTIONS-mechanism feature tags that indicate messaging support. */
    private static final String[] MESSAGING_FEATURE_TAG_HINTS = new String[] {
            "oma.cpm.msg", "oma.cpm.session", "oma.cpm.largemsg", "oma.cpm.deferred",
            "gsma.rcs.cpm.pager-large", "3gpp-application.ims.iari.rcse.im",
    };

    private static final ArrayMap<String, Integer> sCapabilityCache = new ArrayMap<>();
    private static final ArrayMap<String, Long> sLastDiscoveryMap = new ArrayMap<>();
    private static final ArrayMap<String, Long> sInFlightRequests = new ArrayMap<>();
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
     * Checks if a destination phone number is a cached RCS recipient.
     * Does NOT trigger network discovery (pure cache lookup for UI styling).
     */
    public static boolean isRcsRecipient(Context context, String destination) {
        final String normalized = normalizeDestination(context, destination);
        if (TextUtils.isEmpty(normalized)) return false;

        final int cap = getCachedCapability(context, normalized);
        return (cap == CAPABILITY_RCS_SUPPORTED);
    }

    /**
     * Called ONLY when a contact is selected or added to the recipient input field.
     * Logs the ENTIRE discovery process heavily and triggers network capability discovery if UNKNOWN.
     */
    public static boolean onRecipientSelected(Context context, String destination) {
        final String normalized = normalizeDestination(context, destination);
        final String caller = getCallerSummary();
        LogUtil.i(TAG, "==========================================================================");
        LogUtil.i(TAG, "[RECIPIENT SELECTED] Contact added to recipient field!");
        LogUtil.i(TAG, "[RECIPIENT SELECTED]   - Caller: " + caller);
        LogUtil.i(TAG, "[RECIPIENT SELECTED]   - Raw Destination: '" + destination + "'");
        LogUtil.i(TAG, "[RECIPIENT SELECTED]   - Normalized: '" + normalized + "'");

        if (TextUtils.isEmpty(normalized)) {
            LogUtil.d(TAG, "[RECIPIENT SELECTED]   -> Empty normalized destination, defaulting UI to SMS");
            LogUtil.i(TAG, "==========================================================================");
            return false;
        }

        final int cap = getCachedCapability(context, normalized);
        LogUtil.i(TAG, "[RECIPIENT SELECTED]   - Cached Capability: " + capabilityToString(cap) + " (" + cap + ")");

        if (cap == CAPABILITY_UNKNOWN) {
            boolean shouldDiscover = false;
            long elapsed = -1;
            synchronized (sLastDiscoveryMap) {
                final Long lastTime = sLastDiscoveryMap.get(normalized);
                final long now = System.currentTimeMillis();
                if (lastTime != null) {
                    elapsed = now - lastTime;
                }
                if (lastTime == null || elapsed > MIN_DISCOVERY_INTERVAL_MS) {
                    sLastDiscoveryMap.put(normalized, now);
                    shouldDiscover = true;
                }
            }

            LogUtil.i(TAG, "[RECIPIENT SELECTED]   - Capability is UNKNOWN!");
            LogUtil.i(TAG, "[RECIPIENT SELECTED]   - Debounce Check: lastDiscoveryTime=" + (elapsed >= 0 ? elapsed + "ms ago" : "NEVER")
                    + ", minInterval=" + MIN_DISCOVERY_INTERVAL_MS + "ms -> shouldDiscover=" + shouldDiscover);

            if (shouldDiscover) {
                LogUtil.i(TAG, "===== RECIPIENT DISCOVERY PROCESS START FOR: " + normalized + " =====");
                LogUtil.i(TAG, "[RECIPIENT SELECTED]   -> Triggering network capability discovery for " + normalized + " (defaulting UI to SMS)");
                requestPlatformCapabilityDiscovery(context, normalized);
            } else {
                LogUtil.i(TAG, "[RECIPIENT SELECTED]   -> Discovery DEBOUNCED for " + normalized + " (last request was " + elapsed + "ms ago)");
            }
            LogUtil.i(TAG, "==========================================================================");
            return false;
        }

        final boolean isRcs = (cap == CAPABILITY_RCS_SUPPORTED);
        LogUtil.i(TAG, "[RECIPIENT SELECTED]   -> Selection Result: isRcs=" + isRcs + " for " + normalized);
        LogUtil.i(TAG, "==========================================================================");
        return isRcs;
    }

    /**
     * Pulls the contact Uri out of an {@code RcsContactUceCapability} via its documented
     * {@code getContactUri()} accessor.
     */
    private static String extractContactUri(Object capObj) {
        try {
            final Object uri = capObj.getClass().getMethod("getContactUri").invoke(capObj);
            if (uri instanceof Uri) {
                return ((Uri) uri).getSchemeSpecificPart();
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "extractContactUri: could not read getContactUri() — " + e);
        }
        return null;
    }

    /**
     * Maps an {@code RcsContactUceCapability} onto our tri-state capability.
     *
     * <p>A definitive "yes" requires BOTH a {@code REQUEST_RESULT_FOUND} result AND an actual
     * advertised messaging service. A contact that is merely present in the network (or whose
     * result is unknown) is not treated as RCS-capable, so we never route a message into a
     * transport the recipient cannot receive on.
     */
    private static int evaluateCapability(Object capObj) {
        int requestResult = REQUEST_RESULT_UNKNOWN;
        try {
            final Object res = capObj.getClass().getMethod("getRequestResult").invoke(capObj);
            if (res instanceof Integer) {
                requestResult = (Integer) res;
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "evaluateCapability: could not read getRequestResult() — " + e);
            return CAPABILITY_UNKNOWN;
        }

        switch (requestResult) {
            case REQUEST_RESULT_NOT_FOUND:
            case REQUEST_RESULT_NOT_ONLINE:
                LogUtil.i(TAG, "evaluateCapability: requestResult=" + requestResult
                        + " -> contact is not reachable over RCS");
                return CAPABILITY_NOT_SUPPORTED;
            case REQUEST_RESULT_UNKNOWN:
                // No answer either way. Leave it UNKNOWN so we retry later rather than
                // poisoning the cache with a guess.
                LogUtil.i(TAG, "evaluateCapability: requestResult=UNKNOWN -> leaving undetermined");
                return CAPABILITY_UNKNOWN;
            case REQUEST_RESULT_FOUND:
                break;
            default:
                LogUtil.w(TAG, "evaluateCapability: unrecognized requestResult=" + requestResult);
                return CAPABILITY_UNKNOWN;
        }

        final boolean canMessage = hasMessagingCapability(capObj);
        LogUtil.i(TAG, "evaluateCapability: requestResult=FOUND, messagingAdvertised=" + canMessage);
        return canMessage ? CAPABILITY_RCS_SUPPORTED : CAPABILITY_NOT_SUPPORTED;
    }

    /**
     * Returns true when the capability object advertises a usable RCS messaging service, via
     * either the presence (tuple) or OPTIONS (feature tag) mechanism.
     */
    private static boolean hasMessagingCapability(Object capObj) {
        int mechanism = CAPABILITY_MECHANISM_PRESENCE;
        try {
            final Object mech = capObj.getClass().getMethod("getCapabilityMechanism").invoke(capObj);
            if (mech instanceof Integer) {
                mechanism = (Integer) mech;
            }
        } catch (Exception ignored) {}

        if (mechanism == CAPABILITY_MECHANISM_OPTIONS) {
            return hasMessagingFeatureTag(capObj);
        }
        return hasOpenMessagingTuple(capObj);
    }

    private static boolean hasOpenMessagingTuple(Object capObj) {
        try {
            final Object tuples = capObj.getClass().getMethod("getCapabilityTuples").invoke(capObj);
            if (!(tuples instanceof List)) return false;
            for (Object tuple : (List<?>) tuples) {
                if (tuple == null) continue;
                final Object serviceId = tuple.getClass().getMethod("getServiceId").invoke(tuple);
                if (!(serviceId instanceof String)
                        || !MESSAGING_SERVICE_IDS.contains((String) serviceId)) {
                    continue;
                }
                // A tuple whose basic status is "closed" advertises the service as unavailable.
                final Object status = tuple.getClass().getMethod("getStatus").invoke(tuple);
                final boolean open = !(status instanceof String)
                        || TUPLE_BASIC_STATUS_OPEN.equalsIgnoreCase((String) status);
                LogUtil.i(TAG, "hasOpenMessagingTuple: serviceId=" + serviceId
                        + " status=" + status + " open=" + open);
                if (open) return true;
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "hasOpenMessagingTuple: failed to inspect presence tuples — " + e);
        }
        return false;
    }

    private static boolean hasMessagingFeatureTag(Object capObj) {
        try {
            final Object tags = capObj.getClass().getMethod("getFeatureTags").invoke(capObj);
            if (!(tags instanceof Collection)) return false;
            for (Object tag : (Collection<?>) tags) {
                if (!(tag instanceof String)) continue;
                final String lower = ((String) tag).toLowerCase();
                for (String hint : MESSAGING_FEATURE_TAG_HINTS) {
                    if (lower.contains(hint)) {
                        LogUtil.i(TAG, "hasMessagingFeatureTag: matched '" + hint + "' in " + tag);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "hasMessagingFeatureTag: failed to inspect feature tags — " + e);
        }
        return false;
    }

    private static String getCallerSummary() {
        try {
            final StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 3; i < st.length; i++) {
                final String className = st[i].getClassName();
                if (!className.equals(CapabilityDiscoveryManager.class.getName())) {
                    return st[i].getFileName() + ":" + st[i].getLineNumber() + " (" + st[i].getMethodName() + ")";
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    /**
     * Checks cached capability status for destination phone number using fast in-memory lookup.
     */
    public static int getCachedCapability(Context context, String destination) {
        if (TextUtils.isEmpty(destination)) return CAPABILITY_UNKNOWN;
        final String digits = destination.replaceAll("[^0-9]", "");
        final String suffix = digits.length() >= 10 ? digits.substring(digits.length() - 10) : digits;

        synchronized (sCapabilityCache) {
            LogUtil.d(TAG, "[DEEP LOG] getCachedCapability: Checking in-memory cache (size=" + sCapabilityCache.size() + ") for suffix '" + suffix + "'");
            for (Map.Entry<String, Integer> entry : sCapabilityCache.entrySet()) {
                final String cacheKeyDigits = entry.getKey().replaceAll("[^0-9]", "");
                if (cacheKeyDigits.endsWith(suffix)) {
                    final int cachedCap = entry.getValue();
                    LogUtil.i(TAG, "[DEEP LOG] getCachedCapability: In-memory HIT! Key='" + entry.getKey() + "' suffix='" + suffix + "' -> " + capabilityToString(cachedCap));
                    return cachedCap;
                }
            }
            LogUtil.d(TAG, "[DEEP LOG] getCachedCapability: In-memory MISS for suffix '" + suffix + "'. Current cache keys: " + sCapabilityCache.keySet());
        }

        final String normalized = normalizeDestination(context, destination);
        LogUtil.i(TAG, "[DEEP LOG] getCachedCapability: In-memory MISS for " + normalized + ", performing synchronous database lookup");

        // Query database synchronously so capability is available immediately for UI rendering
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            if (db != null) {
                Cursor cursor = null;
                try {
                    final String dbDigits = normalized.replaceAll("[^0-9]", "");
                    final String dbSuffix = dbDigits.length() >= 10 ? dbDigits.substring(dbDigits.length() - 10) : dbDigits;
                    LogUtil.i(TAG, "[DEEP LOG DB] Querying DB table 'participants' for suffix '%" + dbSuffix + "'");
                    cursor = db.query(DatabaseHelper.PARTICIPANTS_TABLE,
                            new String[] { DatabaseHelper.ParticipantColumns.RCS_CAPABILITY, DatabaseHelper.ParticipantColumns.RCS_DISCOVERY_TIMESTAMP },
                            DatabaseHelper.ParticipantColumns.NORMALIZED_DESTINATION + " LIKE ? OR " + DatabaseHelper.ParticipantColumns.DISPLAY_DESTINATION + " LIKE ?",
                            new String[] { "%" + dbSuffix, "%" + dbSuffix }, null, null, null);

                    if (cursor != null && cursor.moveToFirst()) {
                        final int capability = cursor.getInt(0);
                        final long timestamp = cursor.getLong(1);
                        final long ageMs = timestamp > 0 ? (System.currentTimeMillis() - timestamp) : 0;
                        LogUtil.i(TAG, "[DEEP LOG DB] Found DB row for suffix '%" + dbSuffix + "': rcs_capability="
                                + capabilityToString(capability) + " (" + capability + "), timestamp=" + timestamp + " (age=" + ageMs + "ms)");

                        if (capability != CAPABILITY_UNKNOWN && (timestamp == 0 || ageMs < CAPABILITY_CACHE_VALIDITY_MS)) {
                            synchronized (sCapabilityCache) {
                                sCapabilityCache.put(normalized, capability);
                            }
                            LogUtil.i(TAG, "[DEEP LOG DB] Synchronous cache population SUCCESS for " + normalized + " -> " + capabilityToString(capability));
                            return capability;
                        } else if (capability == CAPABILITY_UNKNOWN) {
                            LogUtil.d(TAG, "[DEEP LOG DB] DB capability is UNKNOWN for " + normalized);
                        } else {
                            LogUtil.w(TAG, "[DEEP LOG DB] DB entry for " + normalized + " is EXPIRED (age " + ageMs + "ms > validity " + CAPABILITY_CACHE_VALIDITY_MS + "ms)");
                        }
                    } else {
                        LogUtil.i(TAG, "[DEEP LOG DB] No matching participant row in DB for suffix '%" + dbSuffix + "'");
                    }
                } finally {
                    if (cursor != null) cursor.close();
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "[DEEP LOG DB] Error querying RCS capability cache in database", e);
        }

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
        synchronized (sInFlightRequests) {
            sInFlightRequests.remove(normalizeKey(normalized));
        }
        requestPlatformCapabilityDiscovery(context, normalized);
    }

    /**
     * Reserves a discovery slot for {@code normalized}, respecting the concurrency ceiling.
     * The platform runs UCE SUBSCRIBEs one at a time with a 180s timeout each, so letting
     * requests pile up produces a backlog that never drains.
     *
     * @return true if the caller may issue the request
     */
    private static boolean tryAcquireInFlight(String normalized) {
        final long now = System.currentTimeMillis();
        synchronized (sInFlightRequests) {
            // Reap entries whose callback never arrived.
            for (int i = sInFlightRequests.size() - 1; i >= 0; i--) {
                if (now - sInFlightRequests.valueAt(i) > IN_FLIGHT_TIMEOUT_MS) {
                    LogUtil.i(TAG, "tryAcquireInFlight: reaping stale request for " + sInFlightRequests.keyAt(i));
                    sInFlightRequests.removeAt(i);
                }
            }
            if (sInFlightRequests.containsKey(normalized)) {
                LogUtil.i(TAG, "tryAcquireInFlight: " + normalized + " already in flight; skipping");
                return false;
            }
            if (sInFlightRequests.size() >= MAX_IN_FLIGHT_REQUESTS) {
                LogUtil.i(TAG, "tryAcquireInFlight: at capacity (" + sInFlightRequests.size()
                        + "/" + MAX_IN_FLIGHT_REQUESTS + "); deferring " + normalized);
                return false;
            }
            sInFlightRequests.put(normalized, now);
            return true;
        }
    }

    private static void clearInFlight(List<Uri> uris) {
        synchronized (sInFlightRequests) {
            for (Uri uri : uris) {
                sInFlightRequests.remove(normalizeKey(uri.getSchemeSpecificPart()));
            }
        }
    }

    private static String normalizeKey(String destination) {
        if (TextUtils.isEmpty(destination)) return "";
        final String digits = destination.replaceAll("[^0-9]", "");
        return digits.length() >= 10 ? digits.substring(digits.length() - 10) : digits;
    }

    public static void requestBatchCapabilityDiscovery(Context context, List<String> destinations) {
        if (destinations == null || destinations.isEmpty()) return;
        sAsyncExecutor.execute(() -> {
            LogUtil.i(TAG, "===== RCS DISCOVERY START =====");
            LogUtil.i(TAG, "Discovery request for " + destinations.size() + " destinations: " + destinations);
            // Declared outside the try so the failure paths below can release in-flight slots.
            final List<Uri> uris = new ArrayList<>();
            try {
                final Object uceAdapter = RcsManager.getInstance(context).getPlatformUceAdapter();
                if (uceAdapter == null) {
                    LogUtil.w(TAG, "Platform UCE adapter is null — discovery cannot proceed. Is IMS registered?");
                    LogUtil.i(TAG, "===== RCS DISCOVERY END (no adapter) =====");
                    return;
                }

                for (String dest : destinations) {
                    final String norm = normalizeDestination(context, dest);
                    if (TextUtils.isEmpty(norm)) continue;
                    final Uri contactUri = Uri.parse("tel:" + norm);
                    if (uris.contains(contactUri)) continue;
                    if (!tryAcquireInFlight(normalizeKey(norm))) continue;
                    uris.add(contactUri);
                }
                LogUtil.i(TAG, "Built " + uris.size() + " tel: URIs for discovery: " + uris);
                if (uris.isEmpty()) {
                    LogUtil.i(TAG, "===== RCS DISCOVERY END (nothing to query) =====");
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
                            LogUtil.i(TAG, "==========================================================================");
                            LogUtil.i(TAG, "[EXACT UCE CALLBACK] Method invoked: " + methodName + ", argsCount=" + (args != null ? args.length : 0));

                            if ("onCapabilitiesReceived".equals(methodName)) {
                                final Object arg = (args != null && args.length > 0) ? args[0] : null;
                                if (arg instanceof List) {
                                    final List<?> capabilitiesList = (List<?>) arg;
                                    LogUtil.i(TAG, "[UCE RESPONSE] Capabilities item count: " + capabilitiesList.size());
                                    for (int idx = 0; idx < capabilitiesList.size(); idx++) {
                                        final Object capObj = capabilitiesList.get(idx);
                                        if (capObj == null) continue;
                                        LogUtil.i(TAG, "[UCE RESPONSE ITEM #" + idx + "] " + capObj);

                                        final String contactDest = extractContactUri(capObj);
                                        if (contactDest == null) {
                                            LogUtil.w(TAG, "[UCE RESPONSE ITEM #" + idx + "] no contact Uri; skipping");
                                            continue;
                                        }
                                        resolvedDestinations.add(contactDest);

                                        final int resCap = evaluateCapability(capObj);
                                        if (resCap == CAPABILITY_UNKNOWN) {
                                            // Nothing conclusive — do not overwrite what we already know.
                                            LogUtil.i(TAG, "[UCE RESULT] " + contactDest + " -> UNKNOWN (cache left untouched)");
                                        } else {
                                            updateCapability(context, contactDest, resCap);
                                            LogUtil.i(TAG, "[UCE RESULT] " + contactDest + " -> " + capabilityToString(resCap));
                                        }
                                    }
                                }
                            } else if ("onComplete".equals(methodName)) {
                                LogUtil.i(TAG, "[UCE COMPLETE] Resolved " + resolvedDestinations.size()
                                        + " of " + uris.size() + " requested URIs");
                                // Contacts the platform never reported on stay UNKNOWN so the next
                                // discovery attempt can retry them. Marking them NOT_SUPPORTED here
                                // would cache a non-answer as a definitive "no" for 24 hours.
                                clearInFlight(uris);
                            } else if ("onError".equals(methodName)) {
                                final Object errArg = (args != null && args.length > 0) ? args[0] : "unknown";
                                LogUtil.w(TAG, "[UCE ERROR] onError(" + errArg + ") — leaving unresolved contacts UNKNOWN for retry");
                                clearInFlight(uris);
                            }
                            return null;
                        }
                );

                Method reqMethod = null;
                boolean useRequestAvailability = false;
                if (uris.size() == 1) {
                    try {
                        reqMethod = uceAdapter.getClass().getMethod("requestAvailability", Uri.class, Executor.class, callbackClass);
                        useRequestAvailability = true;
                        LogUtil.i(TAG, "Single contact query — using RcsUceAdapter.requestAvailability()");
                    } catch (Exception ignored) {}
                }

                if (reqMethod == null) {
                    try {
                        reqMethod = uceAdapter.getClass().getMethod("requestCapabilities", Collection.class, Executor.class, callbackClass);
                    } catch (Exception e1) {
                        try {
                            reqMethod = uceAdapter.getClass().getMethod("requestCapabilities", List.class, Executor.class, callbackClass);
                        } catch (Exception e2) {
                            try {
                                reqMethod = uceAdapter.getClass().getMethod("requestAvailability", Uri.class, Executor.class, callbackClass);
                                useRequestAvailability = true;
                            } catch (Exception ignored) {}
                        }
                    }
                }

                if (reqMethod != null) {
                    if (useRequestAvailability) {
                        for (Uri u : uris) {
                            reqMethod.invoke(uceAdapter, u, context.getMainExecutor(), callbackProxy);
                            LogUtil.i(TAG, "Dispatched UCE requestAvailability for URI: " + u);
                        }
                    } else {
                        reqMethod.invoke(uceAdapter, uris, context.getMainExecutor(), callbackProxy);
                        LogUtil.i(TAG, "Dispatched batch UCE requestCapabilities for " + uris.size() + " URIs");
                    }
                } else {
                    LogUtil.w(TAG, "No compatible UCE method found on RcsUceAdapter");
                    clearInFlight(uris);
                }
                LogUtil.i(TAG, "===== RCS DISCOVERY END =====");
            } catch (Exception e) {
                LogUtil.w(TAG, "Batch UCE discovery failed: " + e.getMessage());
                // Nothing will call back for these, so release their slots immediately.
                clearInFlight(uris);
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
