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

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.action.BugleActionToasts;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.rcs.sip.CpimParser;
import com.android.messaging.sms.MmsSmsUtils;
import com.android.messaging.util.LogUtil;

/**
 * Inserts inbound RCS messages into the Bugle database.
 *
 * <p>Resolution of thread, conversation and participants mirrors {@code ReceiveSmsMessageAction};
 * an RCS message must land in the same conversation as SMS traffic with the same person, or the
 * two halves of a thread visibly diverge.
 */
public class RcsMessageReceiver {
    private static final String TAG = "RcsMessageReceiver";

    /**
     * Insertion runs here rather than on the caller's thread. The participant and conversation
     * helpers below assert they are off the main thread, and inbound SIP can be delivered on any
     * thread depending on whether it arrived via MSRP or the SIP delegate.
     */
    private static final java.util.concurrent.ExecutorService sExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Entry point for a CPIM payload arriving over MSRP or a standalone SIP MESSAGE. */
    public static void onCpimReceived(Context context, String rawCpim) {
        final CpimParser.CpimMessage cpim = CpimParser.parseCpim(rawCpim);
        if (cpim == null || TextUtils.isEmpty(cpim.body)) {
            LogUtil.e(TAG, "Failed to parse incoming CPIM payload");
            return;
        }
        onTextReceived(context, cpim.fromUri, cpim.messageId, cpim.body);
    }

    /**
     * Inserts a received text message.
     *
     * @param fromUri   sender as a SIP/tel URI, or a bare phone number
     * @param messageId originating RCS message id, used for logging and IMDN correlation
     */
    public static void onTextReceived(Context context, String fromUri, String messageId,
            String text) {
        if (TextUtils.isEmpty(text)) {
            LogUtil.w(TAG, "Ignoring empty inbound RCS message");
            return;
        }
        final String sender = extractPhoneNumber(fromUri);
        if (TextUtils.isEmpty(sender)) {
            LogUtil.e(TAG, "Cannot resolve sender from '" + fromUri + "'; dropping message");
            return;
        }

        LogUtil.i(TAG, "Inbound RCS message from " + sender + " (id=" + messageId + ")");
        final long now = System.currentTimeMillis();
        sExecutor.execute(() -> insert(context, sender, text, now));
    }

    private static void insert(Context context, String sender, String text, long now) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        try {
            // Reuse the telephony thread so RCS and SMS from one contact share a conversation.
            final long threadId = MmsSmsUtils.Threads.getOrCreateThreadId(context, sender);
            final ParticipantData self =
                    ParticipantData.getSelfParticipant(ParticipantData.DEFAULT_SELF_SUB_ID);

            db.beginTransaction();
            try {
                final String participantId =
                        BugleDatabaseOperations.getOrCreateParticipantInTransaction(
                                db, ParticipantData.getFromRawPhoneBySimLocale(
                                        sender, ParticipantData.DEFAULT_SELF_SUB_ID));
                final String selfId =
                        BugleDatabaseOperations.getOrCreateParticipantInTransaction(db, self);
                final String conversationId =
                        BugleDatabaseOperations.getOrCreateConversationFromThreadId(
                                db, threadId, false /* senderBlocked */,
                                self.getSubId());

                final MessageData message = MessageData.createReceivedRcsMessage(
                        conversationId, participantId, selfId, text, now, now);

                BugleDatabaseOperations.insertNewMessageInTransaction(db, message);
                BugleDatabaseOperations.updateConversationMetadataInTransaction(db, conversationId,
                        message.getMessageId(), message.getReceivedTimeStamp(),
                        false /* blocked */, null /* smsServiceCenter */,
                        true /* shouldAutoSwitchSelfId */);

                final ParticipantData senderParticipant =
                        ParticipantData.getFromId(db, participantId);
                BugleActionToasts.onMessageReceived(conversationId, senderParticipant, message);
                db.setTransactionSuccessful();
                LogUtil.i(TAG, "Inserted inbound RCS message into conversation " + conversationId);
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to insert inbound RCS message", e);
        }
    }

    /** Pulls the user part out of {@code sip:+1555...@domain;user=phone} or {@code tel:+1555...}. */
    static String extractPhoneNumber(String uri) {
        if (TextUtils.isEmpty(uri)) return null;
        String s = uri.trim();
        if (s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length() - 1);

        final int scheme = s.indexOf(':');
        if (scheme >= 0 && (s.startsWith("sip:") || s.startsWith("sips:") || s.startsWith("tel:"))) {
            s = s.substring(scheme + 1);
        }
        final int at = s.indexOf('@');
        if (at >= 0) s = s.substring(0, at);
        final int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi);

        final String cleaned = s.replaceAll("[^0-9+]", "");
        return TextUtils.isEmpty(cleaned) ? null : cleaned;
    }
}
