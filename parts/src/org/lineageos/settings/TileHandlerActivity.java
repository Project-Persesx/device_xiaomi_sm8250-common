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

package org.lineageos.settings;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.lineageos.settings.chargecontrol.ChargeControlActivity;
import org.lineageos.settings.chargecontrol.ChargeControlTileService;

import java.util.HashMap;
import java.util.Map;

public final class TileHandlerActivity extends Activity {
    private static final String TAG = "TileHandlerActivity";

    private static final Map<String, Class<?>> TILE_ACTIVITY_MAP = new HashMap<>();

    static {
        TILE_ACTIVITY_MAP.put(ChargeControlTileService.class.getName(), ChargeControlActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent == null) {
            Log.e(TAG, "No intent provided");
            finish();
            return;
        }

        ComponentName componentName = intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME);
        if (componentName == null) {
            Log.e(TAG, "No component name in intent");
            finish();
            return;
        }

        String tileClassName = componentName.getClassName();
        Class<?> activityClass = TILE_ACTIVITY_MAP.get(tileClassName);

        if (activityClass == null) {
            Log.e(TAG, "No activity mapped for tile: " + tileClassName);
            finish();
            return;
        }

        Intent activityIntent = new Intent(this, activityClass);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(activityIntent);
        finish();
    }
}
