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

package com.android.messaging.rcs.chat;

import com.android.messaging.rcs.sip.SipConfigSnapshot;

/**
 * The SIP operations a {@link RcsChatSession} needs, kept narrow so the session logic can be
 * reasoned about without the delegate plumbing.
 */
interface ChatSipTransport {

    /** Current delegate configuration, or null if none has arrived yet. */
    SipConfigSnapshot getConfig();

    /** Sends the initial INVITE for an outgoing session. */
    boolean sendInvite(RcsChatSession session, byte[] sdp);

    /** Answers an inbound INVITE with 200 OK and our SDP answer. */
    boolean sendInviteOk(RcsChatSession session, byte[] sdp);

    /** Ends an established dialog. */
    boolean sendBye(RcsChatSession session);
}
