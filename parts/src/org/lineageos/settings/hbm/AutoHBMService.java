package org.lineageos.settings.hbm;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import org.lineageos.settings.utils.FileUtils;
import org.lineageos.settings.display.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AutoHBMService extends Service {
    private static final String TAG = "AutoHBMService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String HBM = "/sys/class/drm/card0/card0-DSI-1/disp_param";
    private static final String BACKLIGHT = "/sys/class/backlight/panel0-backlight/brightness";
    private static final String BATTERY_TEMP_PATH = "/sys/class/power_supply/battery/temp";

    private ExecutorService mExecutorService;
    private boolean mAutoHBMActive = false;
    private volatile float mCurrentLux = 0f;
    private volatile float mCurrentBatteryTemp = 0f;
    SensorManager mSensorManager;
    Sensor mLightSensor;

    private SharedPreferences mSharedPrefs;
    private boolean dcDimmingEnabled;
    private boolean mThermalLimitActive = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mDisableHBMRunnable = null;
    private Runnable mThermalCheckRunnable = null;

    private long mLastAboveThresholdTime = 0;
    private static final long MIN_TIME_ABOVE_THRESHOLD = 2000;
    private static final float THERMAL_DISABLE_THRESHOLD = 45.0f;
    private static final float THERMAL_ENABLE_THRESHOLD = 42.0f;
    private static final long THERMAL_CHECK_INTERVAL = 5000;

    private BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (DEBUG) Log.d(TAG, "Screen OFF - disabling HBM and sensor");
                if (mAutoHBMActive) {
                    mAutoHBMActive = false;
                    enableHBM(false);
                }
                deactivateLightSensorRead();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                if (DEBUG) Log.d(TAG, "Screen ON - checking if auto HBM enabled");
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                if (prefs.getBoolean(HBMFragment.KEY_AUTO_HBM_SWITCH, false)) {
                    activateLightSensorRead();
                }
            }
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener = 
        (sharedPreferences, key) -> {
            if (DcDimmingTileService.DC_DIMMING_ENABLE_KEY.equals(key)) {
                dcDimmingEnabled = sharedPreferences.getBoolean(key, false);
                if (DEBUG) Log.d(TAG, "DC Dimming changed: " + dcDimmingEnabled);

                if (dcDimmingEnabled && mAutoHBMActive) {
                    if (DEBUG) Log.d(TAG, "Disabling HBM due to DC Dimming activation");
                    mAutoHBMActive = false;
                    enableHBM(false);
                }
            }
        };

    public void activateLightSensorRead() {
        submit(() -> {
            if (DEBUG) Log.d(TAG, "Activating light sensor");
            mSensorManager = (SensorManager) getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            mSensorManager.registerListener(mSensorEventListener, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL);
            startThermalMonitoring();
        });
    }

    public void deactivateLightSensorRead() {
        submit(() -> {
            if (DEBUG) Log.d(TAG, "Deactivating light sensor");
            if (mSensorManager != null) {
                mSensorManager.unregisterListener(mSensorEventListener);
            }
            mAutoHBMActive = false;
            enableHBM(false);

            if (mDisableHBMRunnable != null) {
                mHandler.removeCallbacks(mDisableHBMRunnable);
                mDisableHBMRunnable = null;
            }
            mLastAboveThresholdTime = 0;
            stopThermalMonitoring();
        });
    }

    private void startThermalMonitoring() {
        if (mThermalCheckRunnable == null) {
            mThermalCheckRunnable = new Runnable() {
                @Override
                public void run() {
                    checkBatteryTemperature();
                    mHandler.postDelayed(this, THERMAL_CHECK_INTERVAL);
                }
            };
            mHandler.post(mThermalCheckRunnable);
            if (DEBUG) Log.d(TAG, "Thermal monitoring started");
        }
    }

    private void stopThermalMonitoring() {
        if (mThermalCheckRunnable != null) {
            mHandler.removeCallbacks(mThermalCheckRunnable);
            mThermalCheckRunnable = null;
            mThermalLimitActive = false;
            if (DEBUG) Log.d(TAG, "Thermal monitoring stopped");
        }
    }

    private float readBatteryTemperature() {
        String tempStr = FileUtils.readLine(BATTERY_TEMP_PATH);
        if (tempStr == null) {
            if (DEBUG) Log.w(TAG, "Failed to read battery temperature");
            return 0f;
        }

        try {
            float temp = Float.parseFloat(tempStr.trim());

            if (temp > 1000) {
                temp = temp / 10.0f;
            } else if (temp > 100) {
                temp = temp / 1.0f;
            }

            return temp;
        } catch (NumberFormatException e) {
            if (DEBUG) Log.e(TAG, "Failed to parse battery temperature: " + tempStr, e);
            return 0f;
        }
    }

    private void checkBatteryTemperature() {
        mCurrentBatteryTemp = readBatteryTemperature();

        if (mCurrentBatteryTemp == 0f) {
            return;
        }

        if (DEBUG) Log.d(TAG, "Battery temperature: " + mCurrentBatteryTemp + "°C, " +
            "Thermal limit active: " + mThermalLimitActive + ", HBM active: " + mAutoHBMActive);

        if (mCurrentBatteryTemp >= THERMAL_DISABLE_THRESHOLD) {
            if (!mThermalLimitActive) {
                mThermalLimitActive = true;
                if (DEBUG) Log.w(TAG, "Thermal limit activated at " + mCurrentBatteryTemp + "°C");

                if (mAutoHBMActive) {
                    if (DEBUG) Log.w(TAG, "Forcing HBM disable due to high temperature");
                    mAutoHBMActive = false;
                    enableHBM(false);
                }
            }
        } else if (mCurrentBatteryTemp <= THERMAL_ENABLE_THRESHOLD) {
            if (mThermalLimitActive) {
                mThermalLimitActive = false;
                if (DEBUG) Log.i(TAG, "Thermal limit deactivated at " + mCurrentBatteryTemp + "°C");
            }
        }
    }

    private void enableHBM(boolean enable) {
        if (DEBUG) Log.d(TAG, "enableHBM: " + enable + ", current lux: " + mCurrentLux);

        if (enable) {
            FileUtils.writeLine(HBM, "0x10000");
            FileUtils.writeLine(BACKLIGHT, "2047");
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 255);
        } else {
            FileUtils.writeLine(HBM, "0xF0000");
        }
    }

    private boolean isCurrentlyEnabled() {
        return FileUtils.getFileValueAsBoolean(HBM, false);
    }

    private SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            mCurrentLux = event.values[0];

            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean keyguardShowing = km.inKeyguardRestrictedInputMode();

            float luxThreshold = Float.parseFloat(mSharedPrefs.getString(HBMFragment.KEY_AUTO_HBM_THRESHOLD, "20000"));
            long timeToDisableHBM = Long.parseLong(mSharedPrefs.getString(HBMFragment.KEY_HBM_DISABLE_TIME, "1"));

            if (DEBUG) Log.d(TAG, "Lux: " + mCurrentLux + ", Threshold: " + luxThreshold + 
                ", Active: " + mAutoHBMActive + ", Keyguard: " + keyguardShowing);

            if (mCurrentLux > luxThreshold) {
                long currentTime = System.currentTimeMillis();

                if (mDisableHBMRunnable != null) {
                    mHandler.removeCallbacks(mDisableHBMRunnable);
                    mDisableHBMRunnable = null;
                    if (DEBUG) Log.d(TAG, "Cancelled pending HBM disable");
                }

                if (mLastAboveThresholdTime == 0) {
                    mLastAboveThresholdTime = currentTime;
                    if (DEBUG) Log.d(TAG, "Started tracking time above threshold");
                }

                if (currentTime - mLastAboveThresholdTime >= MIN_TIME_ABOVE_THRESHOLD) {
                    if ((!mAutoHBMActive || !isCurrentlyEnabled()) && !keyguardShowing && !dcDimmingEnabled && !mThermalLimitActive) {
                        if (DEBUG) Log.d(TAG, "Enabling HBM");
                        mAutoHBMActive = true;
                        enableHBM(true);
                    }
                }
            } else if (mCurrentLux < luxThreshold) {
                mLastAboveThresholdTime = 0;

                if (mAutoHBMActive && mDisableHBMRunnable == null) {
                    if (DEBUG) Log.d(TAG, "Scheduling HBM disable in " + timeToDisableHBM + " seconds");

                    mDisableHBMRunnable = () -> {
                        if (DEBUG) Log.d(TAG, "Disable callback executing, current lux: " + mCurrentLux + 
                            ", threshold: " + luxThreshold);

                        if (mCurrentLux < luxThreshold) {
                            if (DEBUG) Log.d(TAG, "Disabling HBM");
                            mAutoHBMActive = false;
                            enableHBM(false);
                        } else {
                            if (DEBUG) Log.d(TAG, "Lux increased again, keeping HBM active");
                        }
                        mDisableHBMRunnable = null;
                    };

                    mHandler.postDelayed(mDisableHBMRunnable, timeToDisableHBM * 1000);
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "onCreate");
        super.onCreate();
        mExecutorService = Executors.newSingleThreadExecutor();

        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        dcDimmingEnabled = mSharedPrefs.getBoolean(DcDimmingTileService.DC_DIMMING_ENABLE_KEY, false);

        mSharedPrefs.registerOnSharedPreferenceChangeListener(mPrefListener);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(mScreenStateReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "onStartCommand");

        boolean shouldStart = mSharedPrefs.getBoolean(HBMFragment.KEY_AUTO_HBM_SWITCH, false);
        if (DEBUG) Log.d(TAG, "Auto HBM switch: " + shouldStart);

        if (shouldStart) {
            activateLightSensorRead();
        } else {
            deactivateLightSensorRead();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "onDestroy");
        super.onDestroy();

        if (mScreenStateReceiver != null) {
            try {
                unregisterReceiver(mScreenStateReceiver);
            } catch (IllegalArgumentException e) {
            }
        }

        if (mSharedPrefs != null) {
            mSharedPrefs.unregisterOnSharedPreferenceChangeListener(mPrefListener);
        }

        if (mSensorManager != null) {
            mSensorManager.unregisterListener(mSensorEventListener);
        }

        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }

        if (mExecutorService != null && !mExecutorService.isShutdown()) {
            mExecutorService.shutdownNow();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Future<?> submit(Runnable runnable) {
        return mExecutorService.submit(runnable);
    }
}
