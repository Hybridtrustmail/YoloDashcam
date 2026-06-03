package com.dashcam;

import android.graphics.RectF;
import android.util.Log;

import java.util.List;

/**
 * Calculates vehicle speed based on movement between frames
 * Uses camera calibration parameters to convert pixel movement to real-world speed
 */
public class SpeedCalculator {
    private static final String TAG = "SpeedCalculator";
    
    /**
     * Calculate speed for a tracked vehicle
     * Returns speed in km/h, or -1 if cannot calculate
     */
    public float calculateSpeed(TrackedVehicle vehicle) {
        // Need minimum frames to calculate speed
        if (!vehicle.isConfirmed()) {
            return -1;
        }
        
        List<RectF> positions = vehicle.positions;
        List<Long> timestamps = vehicle.timestamps;
        
        if (positions.size() < 2) {
            return -1;
        }
        
        // Get first and last positions for average speed
        // You could also use sliding window for instantaneous speed
        RectF firstPos = positions.get(0);
        RectF lastPos = positions.get(positions.size() - 1);
        Long firstTime = timestamps.get(0);
        Long lastTime = timestamps.get(timestamps.size() - 1);
        
        // Calculate time difference in seconds
        float timeDiff = (lastTime - firstTime) / 1000.0f;
        if (timeDiff <= 0) {
            return -1;
        }
        
        // Calculate pixel displacement
        float pixelDx = lastPos.centerX() - firstPos.centerX();
        float pixelDy = lastPos.centerY() - firstPos.centerY();
        float pixelDistance = (float) Math.sqrt(pixelDx * pixelDx + pixelDy * pixelDy);
        
        // Convert pixel distance to real-world distance
        float realDistance = pixelToRealDistance(pixelDistance, 
                                                (firstPos.centerY() + lastPos.centerY()) / 2);
        
        // Calculate speed (m/s to km/h)
        float speedMs = realDistance / timeDiff;
        float speedKmh = speedMs * 3.6f;
        
        // Apply calibration factor
        speedKmh *= Config.CALIBRATION_FACTOR;
        
        // Sanity check
        if (speedKmh < 0 || speedKmh > Config.MAX_REASONABLE_SPEED) {
            return -1;
        }
        
        Log.d(TAG, String.format("Speed calculated: %.1f km/h (%.1f pixels in %.2fs)", 
                                speedKmh, pixelDistance, timeDiff));
        
        return speedKmh;
    }
    
    /**
     * Convert pixel distance to real-world distance in meters
     * This is a simplified calculation - in production you'd want proper camera calibration
     */
    private float pixelToRealDistance(float pixelDistance, float yPosition) {
        // Get image dimensions (assuming 640x480 for now)
        float imageWidth = Config.MODEL_INPUT_SIZE;
        float imageHeight = Config.MODEL_INPUT_SIZE;
        
        // Calculate distance from camera to vehicle
        // This uses simplified perspective projection
        float distanceToVehicle = calculateDistanceToVehicle(yPosition, imageHeight);
        
        // Calculate horizontal field of view at vehicle distance
        // Assuming ~60 degree horizontal FOV (typical for phone cameras)
        float horizontalFOV = 60.0f;
        float fovRadians = (float) Math.toRadians(horizontalFOV);
        float widthAtVehicle = 2 * distanceToVehicle * (float) Math.tan(fovRadians / 2);
        
        // Calculate meters per pixel at vehicle distance
        float metersPerPixel = widthAtVehicle / imageWidth;
        
        // Convert pixel distance to meters
        float realDistance = pixelDistance * metersPerPixel;
        
        return realDistance;
    }
    
    /**
     * Calculate approximate distance from camera to vehicle
     * Based on camera height, angle, and vehicle position in frame
     */
    private float calculateDistanceToVehicle(float yPosition, float imageHeight) {
        // Normalize y position (0 = top, 1 = bottom)
        float normalizedY = yPosition / imageHeight;
        
        // Simple approximation based on camera setup
        // In reality, this would use proper camera calibration matrix
        float baseDistance = Config.DISTANCE_TO_ROAD;
        
        // Adjust based on position in frame
        // Vehicles at bottom of frame are closer
        float distanceMultiplier = 1.0f + (0.5f - normalizedY);
        
        return baseDistance * distanceMultiplier;
    }
    
    /**
     * Alternative method: Calculate instantaneous speed using last few frames
     * More responsive but potentially noisier
     */
    public float calculateInstantSpeed(TrackedVehicle vehicle) {
        List<RectF> positions = vehicle.positions;
        List<Long> timestamps = vehicle.timestamps;
        
        // Use last 3-5 frames for instant speed
        int framesToUse = Math.min(5, positions.size());
        if (framesToUse < 2) {
            return -1;
        }
        
        RectF oldPos = positions.get(positions.size() - framesToUse);
        RectF newPos = positions.get(positions.size() - 1);
        Long oldTime = timestamps.get(timestamps.size() - framesToUse);
        Long newTime = timestamps.get(timestamps.size() - 1);
        
        // Calculate time difference
        float timeDiff = (newTime - oldTime) / 1000.0f;
        if (timeDiff <= 0) {
            return -1;
        }
        
        // Calculate movement
        float dx = newPos.centerX() - oldPos.centerX();
        float dy = newPos.centerY() - oldPos.centerY();
        float pixelDistance = (float) Math.sqrt(dx * dx + dy * dy);
        
        // Convert to real distance
        float realDistance = pixelToRealDistance(pixelDistance, newPos.centerY());
        
        // Calculate speed
        float speedMs = realDistance / timeDiff;
        float speedKmh = speedMs * 3.6f * Config.CALIBRATION_FACTOR;
        
        return speedKmh;
    }
    
    /**
     * Get movement direction (useful for lane detection)
     * Returns angle in degrees (0 = right, 90 = down, 180 = left, 270 = up)
     */
    public float getMovementDirection(TrackedVehicle vehicle) {
        List<RectF> positions = vehicle.positions;
        if (positions.size() < 2) {
            return -1;
        }
        
        RectF firstPos = positions.get(0);
        RectF lastPos = positions.get(positions.size() - 1);
        
        float dx = lastPos.centerX() - firstPos.centerX();
        float dy = lastPos.centerY() - firstPos.centerY();
        
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) {
            angle += 360;
        }
        
        return angle;
    }
}
