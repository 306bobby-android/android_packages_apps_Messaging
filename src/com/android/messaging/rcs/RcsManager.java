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

import com.android.messaging.rcs.acs.AcsClient;
import com.android.messaging.rcs.acs.AcsConfig;
import com.android.messaging.util.LogUtil;

/**
 * Main Singleton Manager for Open-Standard RCS Service operations.
 */
public class RcsManager {
    private static final String TAG = "RcsManager";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_REGISTERED = 2;

    private static RcsManager sInstance;

    private final Context mContext;
    private int mState = STATE_DISCONNECTED;
    private AcsConfig mAcsConfig;

    private RcsManager(Context context) {
        mContext = context.getApplicationContext();
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

    public boolean isRcsAvailable() {
        return mState == STATE_REGISTERED && mAcsConfig != null && mAcsConfig.isRcsEnabled();
    }

    /**
     * Initializes ACS carrier provisioning request.
     */
    public void startProvisioning() {
        LogUtil.i(TAG, "Starting Carrier ACS Provisioning...");
        mState = STATE_CONNECTING;
        AcsClient.requestConfiguration(mContext, null, new AcsClient.AcsCallback() {
            @Override
            public void onSuccess(AcsConfig config) {
                mAcsConfig = config;
                mState = STATE_REGISTERED;
                LogUtil.i(TAG, "RCS Engine registered with P-CSCF: " + config.getPCscfAddress());
            }

            @Override
            public void onError(String errorReason) {
                mState = STATE_DISCONNECTED;
                LogUtil.e(TAG, "ACS Provisioning failed: " + errorReason);
            }
        });
    }

    /**
     * Called when SMS OTP code is intercepted by AcsSmsReceiver.
     */
    public void onAcsOtpReceived(String otp) {
        LogUtil.i(TAG, "Retrying ACS Provisioning with OTP verification code...");
        AcsClient.requestConfiguration(mContext, otp, new AcsClient.AcsCallback() {
            @Override
            public void onSuccess(AcsConfig config) {
                mAcsConfig = config;
                mState = STATE_REGISTERED;
                LogUtil.i(TAG, "RCS Engine successfully registered via OTP with: " + config.getPCscfAddress());
            }

            @Override
            public void onError(String errorReason) {
                mState = STATE_DISCONNECTED;
                LogUtil.e(TAG, "ACS OTP Provisioning failed: " + errorReason);
            }
        });
    }

    public AcsConfig getAcsConfig() {
        return mAcsConfig;
    }
}
