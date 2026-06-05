package com.dashcam;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings manager for the app
 */
public class Settings {
    private static final String PREFS_NAME = "VehicleDetectorPrefs";
    
    // Keys
    private static final String KEY_MODEL_TYPE = "model_type";
    private static final String KEY_CAMERA_HEIGHT = "camera_height";
    private static final String KEY_DISTANCE_TO_ROAD = "distance_to_road";
    private static final String KEY_CAMERA_ANGLE = "camera_angle";
    private static final String KEY_CONFIDENCE_THRESHOLD = "confidence_threshold";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_ALLOW_ROTATION = "allow_rotation";
    private static final String KEY_OUTPUT_FOLDER = "output_folder";
    private static final String KEY_APP_MODE = "app_mode";
    private static final String KEY_AUDIO_ENABLED = "audio_enabled";
    private static final String KEY_AUTO_RECORD_ON_LAUNCH = "auto_record_on_launch";
    private static final String KEY_SENSITIVITY_PRESET = "sensitivity_preset";
    private static final String KEY_PARKING_MODE_ENABLED = "parking_mode_enabled";
    private static final String KEY_RECORDING_QUALITY = "recording_quality";
    private static final String KEY_QUALITY_FHD_MIGRATED = "quality_fhd_migrated";
    private static final String KEY_MOTION_TRIGGER = "motion_trigger";

    public static final int MODE_RECORD_ONLY = 0;
    public static final int MODE_DETECT_ONLY = 1;
    public static final int MODE_RECORD_ANALYZE_PREVIOUS = 2;

    public static final int QUALITY_HD = 0;
    public static final int QUALITY_FHD = 1;
    public static final int QUALITY_UHD = 2;

    // Auto-record trigger source. GPS is the default: the dashcam is USB-powered
    // by the car, which only supplies power while the engine/vehicle is running
    // (i.e. while GPS shows movement), so GPS speed is the most reliable "we are
    // actually driving" signal. The accelerometer ("motion sensor") and the
    // combined option remain available.
    public static final int TRIGGER_GPS = 0;
    public static final int TRIGGER_SENSOR = 1;
    public static final int TRIGGER_BOTH = 2;
    
    private SharedPreferences prefs;
    
    public Settings(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        migrateUhdToFhd();
    }

    /**
     * One-time migration: UHD (4K) recording is too heavy for the target hardware
     * and contributed to freezes, so FHD is now the default. Any device that had
     * UHD stored from an earlier build is lowered to FHD once. Users can still
     * choose UHD manually afterwards.
     */
    private void migrateUhdToFhd() {
        if (prefs.getBoolean(KEY_QUALITY_FHD_MIGRATED, false)) {
            return;
        }
        if (prefs.getInt(KEY_RECORDING_QUALITY, QUALITY_FHD) == QUALITY_UHD) {
            prefs.edit()
                    .putInt(KEY_RECORDING_QUALITY, QUALITY_FHD)
                    .putBoolean(KEY_QUALITY_FHD_MIGRATED, true)
                    .apply();
        } else {
            prefs.edit().putBoolean(KEY_QUALITY_FHD_MIGRATED, true).apply();
        }
    }
    
    // Model type (YOLO11 only)
    public int getModelType() {
        return YoloDetector.MODEL_YOLO11;
    }
    
    public void setModelType(int modelType) {
        prefs.edit().putInt(KEY_MODEL_TYPE, YoloDetector.MODEL_YOLO11).apply();
    }
    
    // Camera parameters
    public float getCameraHeight() {
        return prefs.getFloat(KEY_CAMERA_HEIGHT, Config.CAMERA_HEIGHT);
    }
    
    public void setCameraHeight(float height) {
        prefs.edit().putFloat(KEY_CAMERA_HEIGHT, height).apply();
    }
    
    public float getDistanceToRoad() {
        return prefs.getFloat(KEY_DISTANCE_TO_ROAD, Config.DISTANCE_TO_ROAD);
    }
    
    public void setDistanceToRoad(float distance) {
        prefs.edit().putFloat(KEY_DISTANCE_TO_ROAD, distance).apply();
    }
    
    public float getCameraAngle() {
        return prefs.getFloat(KEY_CAMERA_ANGLE, Config.CAMERA_ANGLE);
    }
    
    public void setCameraAngle(float angle) {
        prefs.edit().putFloat(KEY_CAMERA_ANGLE, angle).apply();
    }
    
    // Detection settings
    public float getConfidenceThreshold() {
        return prefs.getFloat(KEY_CONFIDENCE_THRESHOLD, Config.DETECTION_THRESHOLD);
    }
    
    public void setConfidenceThreshold(float threshold) {
        prefs.edit().putFloat(KEY_CONFIDENCE_THRESHOLD, threshold).apply();
    }
    
    // Screen settings
    public boolean getKeepScreenOn() {
        return prefs.getBoolean(KEY_KEEP_SCREEN_ON, true);
    }
    
    public void setKeepScreenOn(boolean keepOn) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, keepOn).apply();
    }
    
    public boolean getAllowRotation() {
        return prefs.getBoolean(KEY_ALLOW_ROTATION, false);
    }
    
    public void setAllowRotation(boolean allow) {
        prefs.edit().putBoolean(KEY_ALLOW_ROTATION, allow).apply();
    }

    public String getOutputFolder() {
        return prefs.getString(KEY_OUTPUT_FOLDER, "Downloads/DashCAM");
    }

    public void setOutputFolder(String outputFolder) {
        prefs.edit().putString(KEY_OUTPUT_FOLDER, outputFolder).apply();
    }

    public int getAppMode() {
        return prefs.getInt(KEY_APP_MODE, MODE_RECORD_ONLY);
    }

    public void setAppMode(int appMode) {
        prefs.edit().putInt(KEY_APP_MODE, appMode).apply();
    }

    public boolean getAudioEnabled() {
        return prefs.getBoolean(KEY_AUDIO_ENABLED, false);
    }

    public void setAudioEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUDIO_ENABLED, enabled).apply();
    }

    public boolean getAutoRecordOnLaunch() {
        return prefs.getBoolean(KEY_AUTO_RECORD_ON_LAUNCH, false);
    }

    public void setAutoRecordOnLaunch(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_RECORD_ON_LAUNCH, enabled).apply();
    }

    public int getSensitivityPreset() {
        return prefs.getInt(KEY_SENSITIVITY_PRESET, Config.SENSITIVITY_MEDIUM);
    }

    public void setSensitivityPreset(int preset) {
        prefs.edit().putInt(KEY_SENSITIVITY_PRESET, preset).apply();
    }

    public boolean getParkingModeEnabled() {
        return prefs.getBoolean(KEY_PARKING_MODE_ENABLED, false);
    }

    public void setParkingModeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PARKING_MODE_ENABLED, enabled).apply();
    }

    public int getRecordingQuality() {
        return prefs.getInt(KEY_RECORDING_QUALITY, QUALITY_FHD);
    }

    public void setRecordingQuality(int quality) {
        prefs.edit().putInt(KEY_RECORDING_QUALITY, quality).apply();
    }

    public int getMotionTrigger() {
        return prefs.getInt(KEY_MOTION_TRIGGER, TRIGGER_GPS);
    }

    public void setMotionTrigger(int trigger) {
        prefs.edit().putInt(KEY_MOTION_TRIGGER, trigger).apply();
    }
}
