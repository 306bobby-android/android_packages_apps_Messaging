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

package com.android.messaging.rcs.acs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import com.android.messaging.rcs.RcsManager;
import com.android.messaging.util.LogUtil;

/**
 * Intercepts carrier ACS Auto-Configuration SMS OTP messages (GSMA RCC.14 / RCC.60).
 */
public class AcsSmsReceiver extends BroadcastReceiver {
    private static final String TAG = "AcsSmsReceiver";
    private static final String GSMA_ACS_SMS_PATTERN = "WB_TYPE=";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            final SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (messages == null) return;

            for (SmsMessage sms : messages) {
                final String body = sms.getMessageBody();
                if (body != null && body.contains(GSMA_ACS_SMS_PATTERN)) {
                    LogUtil.i(TAG, "Carrier ACS OTP SMS received: " + body);
                    final String otp = extractOtp(body);
                    if (otp != null && !otp.isEmpty()) {
                        RcsManager.getInstance(context).onAcsOtpReceived(otp);
                    }
                }
            }
        }
    }

    private String extractOtp(String smsBody) {
        // Formats typically: OTP=123456 or OTP:123456
        int idx = smsBody.indexOf("OTP=");
        if (idx != -1) {
            String token = smsBody.substring(idx + 4).trim();
            int spaceIdx = token.indexOf(" ");
            if (spaceIdx != -1) {
                token = token.substring(0, spaceIdx);
            }
            return token;
        }
        return null;
    }
}
