package com.dashcam;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Data logger for vehicle detection results
 * Saves CSV files with timestamp, vehicle count, classification, coordinates
 */
public class DataLogger {
    private static final String TAG = "DataLogger";
    private static final String CSV_HEADER = "timestamp,gps_lat,gps_lon,gps_speed_kmh,device_speed_kmh," +
            "total_detected,cars,buses,trucks,motorcycles,bicycles,pedestrians,traffic_lights,stop_signs," +
            "avg_detected_speed,vehicles_per_minute,device_bearing,device_acceleration\n";
    
    private File logFile;
    private FileWriter fileWriter;
    private ScheduledExecutorService scheduler;
    
    // Vehicle counts by type
    private ConcurrentHashMap<String, Integer> vehicleCounts = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, Long> lastSeenTimes = new ConcurrentHashMap<>();
    
    // Metrics
    private float currentAvgSpeed = 0;
    private int vehiclesPerMinute = 0;
    private long startTime;
    
    // Sensor data
    private double lastGpsLat = 0;
    private double lastGpsLon = 0;
    private float lastGpsSpeed = 0;
    private float lastDeviceSpeed = 0;
    private float lastBearing = 0;
    private float lastAcceleration = 0;
    
    public DataLogger(File logFile) {
        this.logFile = logFile;
        this.startTime = System.currentTimeMillis();

        initializeLogFile();
        startPeriodicLogging();
    }
    
    private void initializeLogFile() {
        try {
            File parentDir = logFile != null ? logFile.getParentFile() : null;
            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                throw new IOException("Failed to create log directory: " + parentDir.getAbsolutePath());
            }

            fileWriter = new FileWriter(logFile, true);

            if (logFile.length() == 0) {
                fileWriter.write(CSV_HEADER);
                fileWriter.flush();
            }

            Log.d(TAG, "Log file created: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to create log file", e);
        }
    }
    
    private void startPeriodicLogging() {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::writeLogEntry, 1, 1, TimeUnit.SECONDS);
    }
    
    public void updateVehicleDetection(Detection detection, Integer trackId) {
        // Update vehicle count
        String vehicleType = normalizeClassKey(detection.className);
        vehicleCounts.compute(vehicleType, (k, v) -> v == null ? 1 : v);
        
        // Track last seen time for unique counting
        if (trackId != null) {
            lastSeenTimes.put(trackId, System.currentTimeMillis());
        }
    }
    
    public void updateSpeed(Map<Integer, Float> speeds) {
        if (speeds.isEmpty()) {
            currentAvgSpeed = 0;
            return;
        }
        
        float sum = 0;
        int count = 0;
        for (Float speed : speeds.values()) {
            if (speed > 0 && speed < Config.MAX_REASONABLE_SPEED) {
                sum += speed;
                count++;
            }
        }
        
        currentAvgSpeed = count > 0 ? sum / count : 0;
    }
    
    public void updateSensorData(double lat, double lon, float gpsSpeed, 
                                float deviceSpeed, float bearing, float acceleration) {
        this.lastGpsLat = lat;
        this.lastGpsLon = lon;
        this.lastGpsSpeed = gpsSpeed;
        this.lastDeviceSpeed = deviceSpeed;
        this.lastBearing = bearing;
        this.lastAcceleration = acceleration;
    }
    
    private void writeLogEntry() {
        try {
            // Clean up old tracks (not seen in last 5 seconds)
            long currentTime = System.currentTimeMillis();
            lastSeenTimes.entrySet().removeIf(entry -> 
                currentTime - entry.getValue() > 5000);
            
            // Calculate vehicles per minute
            long elapsedMinutes = Math.max(1, (currentTime - startTime) / 60000);
            vehiclesPerMinute = (int)(lastSeenTimes.size() / (float)elapsedMinutes);
            
            // Build CSV line
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            
            int totalDetections = vehicleCounts.values().stream().mapToInt(Integer::intValue).sum();
            int cars = vehicleCounts.getOrDefault("car", 0);
            int buses = vehicleCounts.getOrDefault("bus", 0);
            int trucks = vehicleCounts.getOrDefault("truck", 0);
            int motorcycles = vehicleCounts.getOrDefault("motorcycle", 0);
            int bicycles = vehicleCounts.getOrDefault("bicycle", 0);
            int pedestrians = vehicleCounts.getOrDefault("person", 0);
            int trafficLights = vehicleCounts.getOrDefault("traffic_light", 0);
            int stopSigns = vehicleCounts.getOrDefault("stop_sign", 0);
            
            String logLine = String.format(Locale.US,
                    "%s,%.6f,%.6f,%.1f,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%d,%.1f,%.2f\n",
                    timestamp, lastGpsLat, lastGpsLon, lastGpsSpeed, lastDeviceSpeed,
                    totalDetections, cars, buses, trucks, motorcycles, bicycles,
                    pedestrians, trafficLights, stopSigns,
                    currentAvgSpeed, vehiclesPerMinute, lastBearing, lastAcceleration);
            
            if (fileWriter != null) {
                fileWriter.write(logLine);
                fileWriter.flush();
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to write log entry", e);
        }
    }
    
    public void close() {
        try {
            if (scheduler != null) {
                scheduler.shutdown();
            }
            if (fileWriter != null) {
                fileWriter.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to close log file", e);
        }
    }
    
    public String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "";
    }

    private static String normalizeClassKey(String className) {
        if (className == null) {
            return "";
        }
        return className.trim().toLowerCase(Locale.US).replace(' ', '_');
    }
}
