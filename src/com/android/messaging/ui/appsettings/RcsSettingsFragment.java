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

package com.android.messaging.ui.appsettings;

import android.os.Bundle;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.android.messaging.R;
import com.android.messaging.rcs.RcsManager;

/**
 * Fragment rendering RCS preference toggles and live status.
 */
public class RcsSettingsFragment extends PreferenceFragmentCompat implements RcsManager.RcsStateListener {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.rcs_preferences);

        final Preference enablePref = findPreference("pref_key_enable_rcs");
        if (enablePref != null) {
            enablePref.setOnPreferenceChangeListener((preference, newValue) -> {
                final boolean enabled = (Boolean) newValue;
                if (enabled) {
                    RcsManager.getInstance(requireContext()).startProvisioning();
                }
                updateStatusSummary();
                return true;
            });
        }

        final EditTextPreference customUrlPref = findPreference("pref_key_rcs_custom_acs_url");
        if (customUrlPref != null) {
            customUrlPref.setOnPreferenceChangeListener((preference, newValue) -> {
                RcsManager.getInstance(requireContext()).startProvisioning();
                return true;
            });
        }

        updateStatusSummary();
    }

    @Override
    public void onResume() {
        super.onResume();
        RcsManager.getInstance(requireContext()).addListener(this);
        updateStatusSummary();
    }

    @Override
    public void onPause() {
        super.onPause();
        RcsManager.getInstance(requireContext()).removeListener(this);
    }

    @Override
    public void onRcsStateChanged(int newState, String errorReason) {
        updateStatusSummary();
    }

    private void updateStatusSummary() {
        final Preference statusPref = findPreference("pref_key_rcs_status");
        if (statusPref != null && isAdded()) {
            final RcsManager rcsManager = RcsManager.getInstance(requireContext());
            switch (rcsManager.getState()) {
                case RcsManager.STATE_REGISTERED:
                    statusPref.setSummary(R.string.rcs_status_connected);
                    break;
                case RcsManager.STATE_CONNECTING:
                    statusPref.setSummary(R.string.rcs_status_connecting);
                    break;
                default:
                    final String reason = rcsManager.getLastErrorReason();
                    if (reason != null && !reason.isEmpty()) {
                        statusPref.setSummary(getString(R.string.rcs_status_disconnected) + " (" + reason + ")");
                    } else {
                        statusPref.setSummary(R.string.rcs_status_disconnected);
                    }
                    break;
            }
        }
    }
}
