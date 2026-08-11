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

import com.android.messaging.rcs.acs.AcsClient;
import com.android.messaging.rcs.acs.AcsConfig;
import com.android.messaging.rcs.sip.SipStackManager;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Main Singleton Manager for Open-Standard RCS Service operations.
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
    private AcsConfig mAcsConfig;
    private SipStackManager mSipStackManager;
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

    public boolean isRcsAvailable() {
        return mState == STATE_REGISTERED && (mAcsConfig != null && mAcsConfig.isRcsEnabled() || mPlatformUceAdapter != null);
    }

    /**
     * Initializes ACS carrier provisioning request.
     */
    public void startProvisioning() {
        LogUtil.i(TAG, "Starting Carrier ACS Provisioning...");
        notifyStateChanged(STATE_CONNECTING, null);
        AcsClient.requestConfiguration(mContext, null, new AcsClient.AcsCallback() {
            @Override
            public void onSuccess(AcsConfig config) {
                mAcsConfig = config;
                notifyStateChanged(STATE_REGISTERED, null);
                LogUtil.i(TAG, "RCS Engine registered with P-CSCF: " + config.getPCscfAddress());
                startSipRegistration(config);
            }

            @Override
            public void onError(String errorReason) {
                if (mPlatformUceAdapter != null) {
                    LogUtil.i(TAG, "ACS Provisioning error, using platform ImsRcsManager state");
                    notifyStateChanged(STATE_REGISTERED, null);
                } else {
                    notifyStateChanged(STATE_DISCONNECTED, errorReason);
                    LogUtil.e(TAG, "ACS Provisioning failed: " + errorReason);
                }
            }
        });
    }

    /**
     * Called when SMS OTP code is intercepted by AcsSmsReceiver.
     */
    public void onAcsOtpReceived(String otp) {
        LogUtil.i(TAG, "Retrying ACS Provisioning with OTP verification code...");
        notifyStateChanged(STATE_CONNECTING, null);
        AcsClient.requestConfiguration(mContext, otp, new AcsClient.AcsCallback() {
            @Override
            public void onSuccess(AcsConfig config) {
                mAcsConfig = config;
                notifyStateChanged(STATE_REGISTERED, null);
                LogUtil.i(TAG, "RCS Engine successfully registered via OTP with: " + config.getPCscfAddress());
                startSipRegistration(config);
            }

            @Override
            public void onError(String errorReason) {
                notifyStateChanged(STATE_DISCONNECTED, errorReason);
                LogUtil.e(TAG, "ACS OTP Provisioning failed: " + errorReason);
            }
        });
    }

    private synchronized void startSipRegistration(AcsConfig config) {
        if (mSipStackManager != null) {
            mSipStackManager.disconnect();
        }
        mSipStackManager = new SipStackManager(mContext, config);
        mSipStackManager.connectAndRegister();
    }

    private void checkPlatformImsService() {
        try {
            final ImsManager imsManager = (ImsManager) mContext.getSystemService(Context.TELEPHONY_IMS_SERVICE);
            if (imsManager != null) {
                int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
                if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    subId = SubscriptionManager.getDefaultDataSubscriptionId();
                }
                if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    final SubscriptionManager subMgr = mContext.getSystemService(SubscriptionManager.class);
                    if (subMgr != null && subMgr.getActiveSubscriptionInfoList() != null && !subMgr.getActiveSubscriptionInfoList().isEmpty()) {
                        subId = subMgr.getActiveSubscriptionInfoList().get(0).getSubscriptionId();
                    }
                }
                final ImsRcsManager rcsManager = imsManager.getImsRcsManager(subId);
                if (rcsManager != null) {
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
                                LogUtil.w(TAG, "Platform IMS RCS Unavailable reason: " + reason);
                            }

                            @Override
                            public void onAvailable() {
                                LogUtil.i(TAG, "Platform IMS RCS Connected and Available!");
                                notifyStateChanged(STATE_REGISTERED, null);
                            }

                            @Override
                            public void onError() {
                                LogUtil.e(TAG, "Platform IMS RCS error");
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "Platform ImsRcsManager check failed: " + e.getMessage());
        }
    }

    public AcsConfig getAcsConfig() {
        return mAcsConfig;
    }

    public SipStackManager getSipStackManager() {
        return mSipStackManager;
    }

    public RcsUceAdapter getPlatformUceAdapter() {
        return mPlatformUceAdapter;
    }
}
