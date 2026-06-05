package com.dashcam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Manages device sensors for speed compensation and rotation detection
 * Uses GPS, accelerometer, gyroscope, and magnetometer
 */
public class DeviceSensorManager implements SensorEventListener, LocationListener {
    private static final String TAG = "DeviceSensorManager";
    
    private Context context;
    private SensorManager sensorManager;
    private LocationManager locationManager;

    // Sensor + GPS callbacks are delivered on this background thread, NOT the
    // main thread. Listeners do real work on each update (telemetry file writes
    // in the service, HUD math in the activity); keeping it off the UI thread
    // prevents those callbacks from ever contributing to an ANR.
    private android.os.HandlerThread sensorThread;
    private android.os.Handler sensorHandler;

    // Sensors
    private Sensor accelerometer;
    private Sensor gyroscope;
    private Sensor magnetometer;
    
    // Sensor data
    private float[] accelerometerValues = new float[3];
    private float[] magnetometerValues = new float[3];
    private float[] gyroscopeValues = new float[3];
    private float[] gravity = new float[3];
    private float[] rotationMatrix = new float[9];
    private float[] orientationAngles = new float[3];
    
    // GPS data
    private Location lastLocation;
    private float gpsSpeed = 0; // m/s
    private double latitude = 0;
    private double longitude = 0;
    private float bearing = 0;
    
    // Calculated values
    private float deviceSpeed = 0; // m/s from accelerometer integration
    private float deviceAcceleration = 0; // m/s²
    private boolean isStationary = true;
    private long lastUpdateTime = 0;
    
    // Listeners
    public interface SensorDataListener {
        void onSensorDataUpdate(double lat, double lon, float gpsSpeed, 
                              float deviceSpeed, float bearing, float acceleration);
        void onStationaryStatusChanged(boolean isStationary);
    }
    
    private SensorDataListener listener;
    
    public DeviceSensorManager(Context context) {
        this.context = context;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        
        initializeSensors();
    }
    
    private void initializeSensors() {
        // Initialize sensors
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        
        // Check sensor availability
        if (accelerometer == null) {
            Log.w(TAG, "Accelerometer not available");
        }
        if (gyroscope == null) {
            Log.w(TAG, "Gyroscope not available");
        }
        if (magnetometer == null) {
            Log.w(TAG, "Magnetometer not available");
        }
    }
    
    public void startSensors() {
        // Lazily start the background delivery thread.
        if (sensorThread == null) {
            sensorThread = new android.os.HandlerThread("dashcam-sensors");
            sensorThread.start();
            sensorHandler = new android.os.Handler(sensorThread.getLooper());
        }
        // Register sensor listeners (callbacks delivered on sensorHandler's thread)
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI, sensorHandler);
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_UI, sensorHandler);
        }
        if (magnetometer != null) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI, sensorHandler);
        }
        
        // Start GPS updates
        startLocationUpdates();
        
        lastUpdateTime = System.currentTimeMillis();
    }
    
    private void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED) {
            android.os.Looper looper = sensorThread != null
                    ? sensorThread.getLooper() : android.os.Looper.getMainLooper();
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000, // 1 second
                    1,    // 1 meter
                    this,
                    looper
            );

            // Also try network provider for faster initial fix
            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000,
                    1,
                    this,
                    looper
            );
        } else {
            Log.w(TAG, "Location permission not granted");
        }
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(event.values, 0, accelerometerValues, 0, 3);
                updateDeviceMotion();
                break;
                
            case Sensor.TYPE_GYROSCOPE:
                System.arraycopy(event.values, 0, gyroscopeValues, 0, 3);
                break;
                
            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(event.values, 0, magnetometerValues, 0, 3);
                updateOrientation();
                break;
        }
    }
    
    private void updateDeviceMotion() {
        long currentTime = System.currentTimeMillis();
        float dt = (currentTime - lastUpdateTime) / 1000.0f; // Convert to seconds
        
        if (dt > 0) {
            // Remove gravity component (simple high-pass filter)
            float alpha = 0.8f;
            gravity[0] = alpha * gravity[0] + (1 - alpha) * accelerometerValues[0];
            gravity[1] = alpha * gravity[1] + (1 - alpha) * accelerometerValues[1];
            gravity[2] = alpha * gravity[2] + (1 - alpha) * accelerometerValues[2];
            
            // Linear acceleration
            float[] linearAcceleration = new float[3];
            linearAcceleration[0] = accelerometerValues[0] - gravity[0];
            linearAcceleration[1] = accelerometerValues[1] - gravity[1];
            linearAcceleration[2] = accelerometerValues[2] - gravity[2];
            
            // Calculate magnitude
            deviceAcceleration = (float) Math.sqrt(
                    linearAcceleration[0] * linearAcceleration[0] +
                    linearAcceleration[1] * linearAcceleration[1] +
                    linearAcceleration[2] * linearAcceleration[2]
            );
            
            // Detect if device is stationary (low acceleration and rotation)
            float gyroMagnitude = (float) Math.sqrt(
                    gyroscopeValues[0] * gyroscopeValues[0] +
                    gyroscopeValues[1] * gyroscopeValues[1] +
                    gyroscopeValues[2] * gyroscopeValues[2]
            );
            
            boolean wasStationary = isStationary;
            isStationary = deviceAcceleration < 0.5f && gyroMagnitude < 0.1f;
            
            if (wasStationary != isStationary && listener != null) {
                listener.onStationaryStatusChanged(isStationary);
            }
            
            // Simple speed estimation from acceleration (not very accurate)
            if (!isStationary) {
                deviceSpeed += deviceAcceleration * dt;
            } else {
                deviceSpeed *= 0.95f; // Decay when stationary
            }
            
            lastUpdateTime = currentTime;
        }
        
        // Update listener
        if (listener != null) {
            listener.onSensorDataUpdate(latitude, longitude, gpsSpeed * 3.6f, // Convert to km/h
                    deviceSpeed * 3.6f, bearing, deviceAcceleration);
        }
    }
    
    private void updateOrientation() {
        // Calculate rotation matrix
        SensorManager.getRotationMatrix(rotationMatrix, null, 
                accelerometerValues, magnetometerValues);
        
        // Get orientation angles
        SensorManager.getOrientation(rotationMatrix, orientationAngles);
        
        // Convert radians to degrees
        float azimuth = (float) Math.toDegrees(orientationAngles[0]);
        bearing = (azimuth + 360) % 360;
    }
    
    @Override
    public void onLocationChanged(Location location) {
        lastLocation = location;
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        gpsSpeed = location.getSpeed(); // m/s
        
        if (location.hasBearing()) {
            bearing = location.getBearing();
        }
        
        // Update device speed with GPS speed when available
        if (gpsSpeed > 0.5) { // Moving according to GPS
            deviceSpeed = gpsSpeed; // Reset to GPS speed for accuracy
        }
        
        Log.d(TAG, String.format("GPS Update - Lat: %.6f, Lon: %.6f, Speed: %.1f km/h", 
                latitude, longitude, gpsSpeed * 3.6f));
    }
    
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}
    
    @Override
    public void onProviderEnabled(String provider) {}
    
    @Override
    public void onProviderDisabled(String provider) {}
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    
    public void setListener(SensorDataListener listener) {
        this.listener = listener;
    }
    
    public void stopSensors() {
        sensorManager.unregisterListener(this);
        locationManager.removeUpdates(this);
    }
    
    // Getters
    public boolean isStationary() {
        return isStationary;
    }
    
    public float getDeviceSpeed() {
        return deviceSpeed * 3.6f; // Return in km/h
    }
    
    public float getGpsSpeed() {
        return gpsSpeed * 3.6f; // Return in km/h
    }
    
    public Location getLastLocation() {
        return lastLocation;
    }
}
