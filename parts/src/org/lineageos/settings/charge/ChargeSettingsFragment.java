/*
 * Copyright (C) 2025 The LineageOS Project
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

package org.lineageos.settings.charge;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.switchmaterial.SwitchMaterial;
import org.lineageos.settings.R;

public class ChargeSettingsFragment extends Fragment {

    private ChargeUtils chargeUtils;
    private SwitchMaterial bypassSwitch;
    private ImageView illustrationImage;
    private TextView descriptionText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_charge_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        chargeUtils = new ChargeUtils(requireActivity());

        illustrationImage = view.findViewById(R.id.bypass_illustration);
        descriptionText = view.findViewById(R.id.bypass_description);
        bypassSwitch = view.findViewById(R.id.bypass_switch);

        boolean bypassChargeSupported = chargeUtils.isBypassChargeSupported();

        if (!bypassChargeSupported) {
            bypassSwitch.setEnabled(false);
            bypassSwitch.setChecked(false);
            descriptionText.setText(R.string.charge_bypass_unavailable);
            return;
        }

        bypassSwitch.setChecked(chargeUtils.isBypassChargeEnabled());
        bypassSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                ChargeUtils.SafetyCheckResult safetyCheck = chargeUtils.performSafetyChecks();

                if (!safetyCheck.isSafe()) {
                    bypassSwitch.setChecked(false);
                    new AlertDialog.Builder(requireActivity())
                            .setTitle(R.string.charge_bypass_title)
                            .setMessage(getString(R.string.charge_bypass_safety_failed, 
                                    safetyCheck.getReason()))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }

                new AlertDialog.Builder(requireActivity())
                        .setTitle(R.string.charge_bypass_title)
                        .setMessage(R.string.charge_bypass_warning)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            chargeUtils.enableBypassCharge(true);
                            try {
                                BypassChargeTileService.updateTile(requireActivity());
                            } catch (Exception e) {
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                            bypassSwitch.setChecked(false);
                        })
                        .show();
            } else {
                chargeUtils.enableBypassCharge(false);
                try {
                    BypassChargeTileService.updateTile(requireActivity());
                } catch (Exception e) {
                }
            }
        });
    }
}
