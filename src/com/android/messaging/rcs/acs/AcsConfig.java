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

/**
 * Container for parsed GSMA RCC.14 / RCC.60 ACS (Auto-Configuration Server) configuration.
 */
public class AcsConfig {
    private String mPCscfAddress;
    private int mPCscfPort = 5060;
    private String mSipDomain;
    private String mSipRealm;
    private String mPublicUserIdentity;
    private String mDigestUsername;
    private String mDigestPassword;
    private String mMsrpRelayHost;
    private int mMsrpRelayPort = 2855;
    private String mFtServerUrl;
    private String mFtAuthToken;
    private boolean mIsRcsEnabled = false;
    private int mRegExpireSeconds = 3600;

    public String getPCscfAddress() {
        return mPCscfAddress;
    }

    public void setPCscfAddress(String pCscfAddress) {
        mPCscfAddress = pCscfAddress;
    }

    public int getPCscfPort() {
        return mPCscfPort;
    }

    public void setPCscfPort(int pCscfPort) {
        mPCscfPort = pCscfPort;
    }

    public String getSipDomain() {
        return mSipDomain;
    }

    public void setSipDomain(String sipDomain) {
        mSipDomain = sipDomain;
    }

    public String getSipRealm() {
        return mSipRealm;
    }

    public void setSipRealm(String sipRealm) {
        mSipRealm = sipRealm;
    }

    public String getPublicUserIdentity() {
        return mPublicUserIdentity;
    }

    public void setPublicUserIdentity(String publicUserIdentity) {
        mPublicUserIdentity = publicUserIdentity;
    }

    public String getDigestUsername() {
        return mDigestUsername;
    }

    public void setDigestUsername(String digestUsername) {
        mDigestUsername = digestUsername;
    }

    public String getDigestPassword() {
        return mDigestPassword;
    }

    public void setDigestPassword(String digestPassword) {
        mDigestPassword = digestPassword;
    }

    public String getMsrpRelayHost() {
        return mMsrpRelayHost;
    }

    public void setMsrpRelayHost(String msrpRelayHost) {
        mMsrpRelayHost = msrpRelayHost;
    }

    public int getMsrpRelayPort() {
        return mMsrpRelayPort;
    }

    public void setMsrpRelayPort(int msrpRelayPort) {
        mMsrpRelayPort = msrpRelayPort;
    }

    public String getFtServerUrl() {
        return mFtServerUrl;
    }

    public void setFtServerUrl(String ftServerUrl) {
        mFtServerUrl = ftServerUrl;
    }

    public String getFtAuthToken() {
        return mFtAuthToken;
    }

    public void setFtAuthToken(String ftAuthToken) {
        mFtAuthToken = ftAuthToken;
    }

    public boolean isRcsEnabled() {
        return mIsRcsEnabled;
    }

    public void setRcsEnabled(boolean enabled) {
        mIsRcsEnabled = enabled;
    }

    public int getRegExpireSeconds() {
        return mRegExpireSeconds;
    }

    public void setRegExpireSeconds(int regExpireSeconds) {
        mRegExpireSeconds = regExpireSeconds;
    }

    public boolean isValid() {
        return mIsRcsEnabled && mPCscfAddress != null && !mPCscfAddress.isEmpty()
                && mSipDomain != null && !mSipDomain.isEmpty();
    }
}
