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

package com.android.messaging.rcs.binding;

import android.content.Context;
import android.text.TextUtils;

import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.rcs.chat.RcsChatSessionManager;
import com.android.messaging.util.LogUtil;

import java.util.UUID;

/**
 * Routes an outgoing message onto the RCS chat transport.
 *
 * <p>The previous implementation reported success whenever a UDP datagram was handed to the OS,
 * which a socket pointed at an unreachable host always accepts. Messages were therefore marked
 * sent without anything being transmitted. Success is now conditional on a chat session actually
 * accepting the message; anything else returns a failure so the caller falls back to SMS.
 */
public class RcsSendMessageDelegate {
    private static final String TAG = "RcsSendMessageDelegate";

    /**
     * How long to wait for the message to reach the network.
     *
     * <p>A cold session needs an INVITE round trip and an MSRP connection, and this carrier's
     * INVITE has taken close to a second before now. The session's own INVITE timeout is 32s, so
     * this sits just past it: whichever fires first, the message ends up with a real verdict
     * rather than an assumed one.
     */
    private static final long SEND_TIMEOUT_MS = 35_000L;

    public static int sendRcsMessage(Context context, MessageData message, String recipient) {
        final String text = message.getMessageText();
        if (TextUtils.isEmpty(recipient) || TextUtils.isEmpty(text)) {
            LogUtil.w(TAG, "sendRcsMessage: missing recipient or body; falling back");
            return MessageData.BUGLE_STATUS_OUTGOING_FAILED;
        }

        final String rcsMessageId = UUID.randomUUID().toString();
        LogUtil.i(TAG, "sendRcsMessage: recipient=" + recipient + " messageId=" + rcsMessageId);

        final RcsChatSessionManager manager = RcsChatSessionManager.getInstance(context);
        final boolean accepted = manager.sendText(recipient, rcsMessageId, text);
        if (!accepted) {
            LogUtil.w(TAG, "sendRcsMessage: chat transport rejected the message; falling back");
            return MessageData.BUGLE_STATUS_OUTGOING_FAILED;
        }

        // Queuing is not sending. The session may still be negotiating, and the ImsService reports
        // its refusals asynchronously — a rejected INVITE arrives well after this method used to
        // have returned OUTGOING_COMPLETE. Messages the network never accepted were stored as sent
        // and shown without an error, which is how a message that reached nobody looked delivered.
        // SendMessageAction already runs off the main thread, so waiting here is safe.
        final boolean sent = manager.awaitSendOutcome(rcsMessageId, SEND_TIMEOUT_MS);
        if (!sent) {
            LogUtil.w(TAG, "sendRcsMessage: not transmitted; reporting failure so the caller "
                    + "can fall back");
            return MessageData.BUGLE_STATUS_OUTGOING_FAILED;
        }
        LogUtil.i(TAG, "sendRcsMessage: transmitted over MSRP");
        return MessageData.BUGLE_STATUS_OUTGOING_COMPLETE;
    }
}
