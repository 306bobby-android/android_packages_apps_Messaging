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

import com.android.messaging.datamodel.action.InsertNewMessageAction;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.sip.CpimParser;
import com.android.messaging.util.LogUtil;

/**
 * Handles incoming RCS payloads from socket/SIP layers and inserts into Bugle Database.
 */
public class RcsMessageReceiver {
    private static final String TAG = "RcsMessageReceiver";

    public static void onMessageReceived(Context context, String rawCpimMessage) {
        final CpimParser.CpimMessage cpim = CpimParser.parseCpim(rawCpimMessage);
        if (cpim == null || cpim.body == null || cpim.body.isEmpty()) {
            LogUtil.e(TAG, "Failed to parse incoming CPIM payload");
            return;
        }

        LogUtil.i(TAG, "Incoming RCS Message from: " + cpim.fromUri + ", body: " + cpim.body);

        final MessageData message = MessageData.createRcsMessage(
                cpim.fromUri, cpim.body, System.currentTimeMillis(), cpim.messageId);

        InsertNewMessageAction.insertReceivedMessage(message);
    }
}
