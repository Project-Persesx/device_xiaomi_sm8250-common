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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.IBinder;
import androidx.preference.PreferenceManager;
import org.lineageos.settings.Constants;
import org.lineageos.settings.utils.FileUtils;

public class ChargeControlService extends Service {

    private BatteryReceiver mBatteryReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        mBatteryReceiver = new BatteryReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(mBatteryReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBatteryReceiver != null) {
            unregisterReceiver(mBatteryReceiver);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            boolean enabled = sharedPrefs.getBoolean(Constants.KEY_CHARGE_CONTROL, false);

            if (!enabled) {
                return;
            }

            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int stopLevel = sharedPrefs.getInt(Constants.KEY_STOP_CHARGING, 100);

            if (level < 0 || scale <= 0) {
                return;
            }

            int percent = (int) ((level / (float) scale) * 100);

            if (percent >= stopLevel) {
                FileUtils.writeValue(Constants.NODE_STOP_CHARGING, "1");
            } else {
                FileUtils.writeValue(Constants.NODE_STOP_CHARGING, "0");
            }
        }
    }
}
