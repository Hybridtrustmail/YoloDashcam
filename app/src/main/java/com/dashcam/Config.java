package com.dashcam;

/**
 * Configuration file for Vehicle Detection and Speed Estimation
 * 
 * IMPORTANT: Adjust these values based on your camera setup!
 * All measurements should be in metric units (meters, km/h)
 */
public class Config {
    
    // ========== CAMERA SETUP ==========
    /**
     * Height of camera from ground (in meters)
     * Measure from ground to phone camera lens
     * Example: If mounted on 2nd floor window = ~6 meters
     */
    public static float CAMERA_HEIGHT = 1.3f;
    
    /**
     * Angle of camera from horizontal (in degrees)
     * 0° = looking straight ahead
     * 45° = looking down at 45-degree angle
     * 90° = looking straight down
     */
    public static float CAMERA_ANGLE = 30.0f;
    
    /**
     * Distance from camera to center of road (in meters)
     * Measure the straight-line distance
     */
    public static float DISTANCE_TO_ROAD = 10.0f;
    
    // ========== ROAD INFORMATION ==========
    /**
     * Width of one traffic lane (in meters)
     * Standard lane width is usually 3.5-3.7 meters
     */
    public static final float LANE_WIDTH = 3.5f;
    
    /**
     * Number of lanes visible in camera view
     */
    public static final int NUMBER_OF_LANES = 2;
    
    // ========== DETECTION SETTINGS ==========
    public static final int SENSITIVITY_LOW    = 0;  // fewer detections, fewer false positives
    public static final int SENSITIVITY_MEDIUM = 1;
    public static final int SENSITIVITY_HIGH   = 2;  // more detections, more false positives
    public static final float[] DETECTION_THRESHOLD_FOR_PRESET = { 0.65f, 0.45f, 0.25f };

    /**
     * Minimum confidence for vehicle detection (0.0 to 1.0)
     * Lower = more detections but more false positives
     * Higher = fewer detections but more accurate
     */
    public static float DETECTION_THRESHOLD = 0.35f;  // Lowered for better detection
    
    /**
     * Minimum overlap for tracking same vehicle (0.0 to 1.0)
     * Used to match vehicles between frames
     */
    public static final float TRACKING_OVERLAP_THRESHOLD = 0.3f;
    
    // ========== SPEED CALCULATION ==========
    /**
     * Calibration factor for speed calculation
     * Adjust this if speeds seem consistently too high or too low
     * 1.0 = no adjustment
     * 0.8 = reduce calculated speed by 20%
     * 1.2 = increase calculated speed by 20%
     */
    public static final float CALIBRATION_FACTOR = 1.0f;
    
    /**
     * Minimum frames to track before calculating speed
     * More frames = more accurate but slower to show speed
     */
    public static final int MIN_FRAMES_FOR_SPEED = 5;
    
    /**
     * Maximum reasonable speed (km/h)
     * Readings above this are ignored as errors
     */
    public static final float MAX_REASONABLE_SPEED = 200.0f;
    
    // ========== DISPLAY SETTINGS ==========
    /**
     * Show speed in km/h (true) or mph (false)
     */
    public static final boolean USE_METRIC = true;
    
    /**
     * Show debug information on screen
     */
    public static final boolean SHOW_DEBUG_INFO = true;
    
    /**
     * Frame rate for processing (frames per second)
     * Lower = less CPU usage but less accurate speed
     * Higher = more CPU usage but more accurate speed
     */
    public static final int TARGET_FPS = 15;
    
    // ========== MODEL SETTINGS ==========
    /**
     * YOLO model input size
     * Don't change unless using different model
     */
    public static final int MODEL_INPUT_SIZE = 320;
    
    /**
     * Number of classes in YOLO model
     * Standard COCO dataset has 80 classes
     */
    public static final int NUM_CLASSES = 80;
    
    /**
     * Supported road-scene class IDs in COCO dataset
     * 0 = person, 1 = bicycle, 2 = car, 3 = motorcycle,
     * 5 = bus, 7 = truck, 9 = traffic light, 11 = stop sign
     */
    public static final int[] SUPPORTED_CLASS_IDS = {0, 1, 2, 3, 5, 7, 9, 11};
    
    // ========== HELPER METHODS ==========
    
    /**
     * Convert km/h to mph
     */
    public static float kmhToMph(float kmh) {
        return kmh * 0.621371f;
    }
    
    /**
     * Get speed unit string
     */
    public static String getSpeedUnit() {
        return USE_METRIC ? "km/h" : "mph";
    }
    
    /**
     * Format speed for display
     */
    public static String formatSpeed(float speed) {
        if (!USE_METRIC) {
            speed = kmhToMph(speed);
        }
        return String.format("%.1f %s", speed, getSpeedUnit());
    }
    
    /**
     * Calculate pixels per meter based on camera setup
     */
    public static float calculatePixelsPerMeter(float imageWidth) {
        // Using camera geometry
        float fovRadians = (float) Math.toRadians(60); // Typical phone camera FOV
        float distanceToRoad = DISTANCE_TO_ROAD / (float) Math.cos(Math.toRadians(CAMERA_ANGLE));
        float roadWidthInView = 2 * distanceToRoad * (float) Math.tan(fovRadians / 2);
        return imageWidth / roadWidthInView;
    }
    
    /**
     * Speed calculation factor based on frame rate
     */
    public static float getSpeedFactor(float fps) {
        return 3.6f / fps; // Convert m/frame to km/h
    }
}
