/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file me.
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

package com.android.messaging.rcs.binding;

import android.content.Context;

import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.rcs.RcsManager;
import com.android.messaging.rcs.sip.CpimParser;
import com.android.messaging.util.LogUtil;

import java.util.UUID;

/**
 * Delegates outbound RCS message execution and fallback handling.
 */
public class RcsSendMessageDelegate {
    private static final String TAG = "RcsSendMessageDelegate";

    public static int sendRcsMessage(Context context, MessageData message, String recipient) {
        LogUtil.i(TAG, "===== RCS MESSAGE SEND START =====");
        LogUtil.i(TAG, "sendRcsMessage: recipient=" + recipient);
        LogUtil.i(TAG, "sendRcsMessage: messageText=" + (message.getMessageText() != null ? message.getMessageText().substring(0, Math.min(50, message.getMessageText().length())) + "..." : "null"));
        LogUtil.i(TAG, "sendRcsMessage: protocol=" + message.getProtocol() + " conversationId=" + message.getConversationId());

        final RcsManager rcsManager = RcsManager.getInstance(context);
        if (!rcsManager.isRcsAvailable()) {
            LogUtil.w(TAG, "sendRcsMessage: RCS unavailable (isRcsAvailable=false), signaling fallback required");
            LogUtil.i(TAG, "===== RCS MESSAGE SEND END (unavailable) =====");
            return MessageData.BUGLE_STATUS_OUTGOING_FAILED;
        }
        LogUtil.i(TAG, "sendRcsMessage: RCS is available, proceeding");

        try {
            final String rcsMessageId = UUID.randomUUID().toString();
            LogUtil.i(TAG, "sendRcsMessage: generated rcsMessageId=" + rcsMessageId);

            final String cpimPayload = CpimParser.formatCpimMessage("sip:self@ims", "sip:" + recipient + "@ims",
                    rcsMessageId, message.getMessageText());
            LogUtil.i(TAG, "sendRcsMessage: CPIM payload built, length=" + (cpimPayload != null ? cpimPayload.length() : 0));

            // Actually transmit via SIP stack
            final com.android.messaging.rcs.sip.SipStackManager sipManager = rcsManager.getSipStackManager();
            if (sipManager == null) {
                LogUtil.e(TAG, "sendRcsMessage: SipStackManager is null! Cannot transmit.");
                LogUtil.i(TAG, "===== RCS MESSAGE SEND END (no SIP stack) =====");
                return MessageData.BUGLE_STATUS_OUTGOING_FAILED;
            }
            LogUtil.i(TAG, "sendRcsMessage: SipStackManager obtained, attempting SIP MESSAGE send");

            final boolean sent = sipManager.sendSipMessage(recipient, cpimPayload, rcsMessageId);
            if (sent) {
                LogUtil.i(TAG, "sendRcsMessage: SIP MESSAGE transmitted successfully");
                LogUtil.i(TAG, "===== RCS MESSAGE SEND END (sent, awaiting delivery confirmation) =====");
                return MessageData.BUGLE_STATUS_OUTGOING_COMPLETE;
            } else {
                LogUtil.e(TAG, "sendRcsMessage: SIP MESSAGE transmission failed");
                LogUtil.i(TAG, "===== RCS MESSAGE SEND END (transport failure) =====");
                return MessageData.BUGLE_STATUS_OUTGOING_FAILED;
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "sendRcsMessage: RCS transmit error", e);
            LogUtil.i(TAG, "===== RCS MESSAGE SEND END (exception) =====");
            return MessageData.BUGLE_STATUS_OUTGOING_FAILED;
        }
    }
}
