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

package com.android.messaging.rcs;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsRcsManager;
import android.telephony.ims.ImsStateCallback;
import android.telephony.ims.RcsUceAdapter;

import com.android.messaging.rcs.chat.RcsChatSessionManager;
import com.android.messaging.rcs.sip.SipDelegateTransport;
import com.android.messaging.rcs.uce.GroupSubscribeConfigurator;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for RCS service state.
 *
 * <p>Two independent platform facilities back this:
 * <ul>
 *   <li>{@link RcsUceAdapter} for capability discovery, which works whenever the carrier enables
 *       presence exchange.</li>
 *   <li>A {@code SipDelegate} for the actual messaging transport, which requires IMS Single
 *       Registration to be enabled for the subscription.</li>
 * </ul>
 *
 * <p>There is deliberately no ACS client or app-owned SIP stack here. The carrier's provisioning
 * document is delivered by the platform, and IMS registration belongs to the modem.
 */
public class RcsManager {
    private static final String TAG = "RcsManager";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_REGISTERED = 2;

    public interface RcsStateListener {
        void onRcsStateChanged(int newState, String errorReason);
    }

    private static RcsManager sInstance;

    private final Context mContext;
    private int mState = STATE_DISCONNECTED;
    private String mLastErrorReason;
    private RcsUceAdapter mPlatformUceAdapter;
    private final List<RcsStateListener> mListeners = new ArrayList<>();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private RcsManager(Context context) {
        mContext = context.getApplicationContext();
        checkPlatformImsService();
    }

    public static synchronized RcsManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new RcsManager(context);
        }
        return sInstance;
    }

    public synchronized int getState() {
        return mState;
    }

    public synchronized String getLastErrorReason() {
        return mLastErrorReason;
    }

    public synchronized void addListener(RcsStateListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public synchronized void removeListener(RcsStateListener listener) {
        mListeners.remove(listener);
    }

    private synchronized void notifyStateChanged(final int newState, final String errorReason) {
        mState = newState;
        mLastErrorReason = errorReason;
        mMainHandler.post(() -> {
            synchronized (RcsManager.this) {
                for (RcsStateListener listener : new ArrayList<>(mListeners)) {
                    listener.onRcsStateChanged(newState, errorReason);
                }
            }
        });
    }

    /**
     * True when a message can actually be sent over RCS right now. This deliberately requires a
     * usable transport rather than merely a discovered capability — reporting availability without
     * one is what previously let messages be marked sent when nothing had been transmitted.
     */
    public boolean isRcsAvailable() {
        return SipDelegateTransport.getInstance(mContext).isChatReady();
    }

    /** True when contact capability discovery is usable, independent of the messaging transport. */
    public boolean isCapabilityDiscoveryAvailable() {
        return mPlatformUceAdapter != null;
    }

    /**
     * Brings up the RCS transport. Safe to call repeatedly.
     */
    public void initialize() {
        LogUtil.i(TAG, "Initializing RCS transport...");
        // Reach the vendor's resource-list subscription path, which individual per-contact
        // subscription never exercises.
        GroupSubscribeConfigurator.applyIfNeeded(mContext);
        notifyStateChanged(STATE_CONNECTING, null);

        final SipDelegateTransport transport = SipDelegateTransport.getInstance(mContext);
        RcsChatSessionManager.getInstance(mContext).attach(transport);
        transport.ensureStarted();

        mMainHandler.postDelayed(this::publishTransportState, 8000);
    }

    private void publishTransportState() {
        if (SipDelegateTransport.getInstance(mContext).isChatReady()) {
            LogUtil.i(TAG, "RCS chat transport ready");
            notifyStateChanged(STATE_REGISTERED, null);
        } else if (mPlatformUceAdapter != null) {
            LogUtil.i(TAG, "No chat transport; capability discovery only");
            notifyStateChanged(STATE_DISCONNECTED, "No SIP delegate (single registration off?)");
        } else {
            notifyStateChanged(STATE_DISCONNECTED, "RCS unavailable");
        }
    }

    private void checkPlatformImsService() {
        try {
            final ImsManager imsManager =
                    (ImsManager) mContext.getSystemService(Context.TELEPHONY_IMS_SERVICE);
            if (imsManager == null) return;

            int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subId = SubscriptionManager.getDefaultDataSubscriptionId();
            }
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                final SubscriptionManager subMgr = mContext.getSystemService(SubscriptionManager.class);
                if (subMgr != null && subMgr.getActiveSubscriptionInfoList() != null
                        && !subMgr.getActiveSubscriptionInfoList().isEmpty()) {
                    subId = subMgr.getActiveSubscriptionInfoList().get(0).getSubscriptionId();
                }
            }

            final ImsRcsManager rcsManager = imsManager.getImsRcsManager(subId);
            if (rcsManager == null) return;
            LogUtil.i(TAG, "Platform ImsRcsManager detected for subId: " + subId);

            final RcsUceAdapter uceAdapter = rcsManager.getUceAdapter();
            if (uceAdapter != null) {
                LogUtil.i(TAG, "RcsUceAdapter active for capability exchange");
                mPlatformUceAdapter = uceAdapter;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                rcsManager.registerImsStateCallback(mContext.getMainExecutor(), new ImsStateCallback() {
                    @Override
                    public void onUnavailable(int reason) {
                        LogUtil.w(TAG, "Platform IMS RCS unavailable, reason: " + reason);
                    }

                    @Override
                    public void onAvailable() {
                        LogUtil.i(TAG, "Platform IMS RCS available; starting transport");
                        SipDelegateTransport.getInstance(mContext).ensureStarted();
                    }

                    @Override
                    public void onError() {
                        LogUtil.e(TAG, "Platform IMS RCS error");
                    }
                });
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "Platform ImsRcsManager check failed: " + e.getMessage());
        }
    }

    public RcsUceAdapter getPlatformUceAdapter() {
        return mPlatformUceAdapter;
    }
}
