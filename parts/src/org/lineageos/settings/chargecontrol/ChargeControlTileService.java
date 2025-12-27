/*
 * Copyright (C) 2024 The LineageOS Project
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

package org.lineageos.settings.chargecontrol;

import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import androidx.preference.PreferenceManager;
import org.lineageos.settings.Constants;
import org.lineageos.settings.R;
import org.lineageos.settings.utils.FileUtils;

public class ChargeControlTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = sharedPrefs.getBoolean(Constants.KEY_CHARGE_CONTROL, false);

        sharedPrefs.edit().putBoolean(Constants.KEY_CHARGE_CONTROL, !enabled).apply();

        if (enabled) {
            FileUtils.writeValue(Constants.NODE_STOP_CHARGING, "0");
        }

        updateTile();
    }

    private void updateTile() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = sharedPrefs.getBoolean(Constants.KEY_CHARGE_CONTROL, false);
        int stopLevel = sharedPrefs.getInt(Constants.KEY_STOP_CHARGING, 100);

        Tile tile = getQsTile();
        if (tile != null) {
            if (enabled) {
                tile.setState(Tile.STATE_ACTIVE);
                tile.setSubtitle(getString(R.string.charge_control_tile_subtitle, stopLevel));
            } else {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle(getString(R.string.charge_control_tile_disabled));
            }
            tile.updateTile();
        }
    }
}
