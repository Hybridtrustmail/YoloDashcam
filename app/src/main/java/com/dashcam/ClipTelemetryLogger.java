package com.dashcam;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Records per-segment device telemetry while video is being captured. This is
 * later merged with offline YOLO analysis so clip CSV rows retain GPS and host
 * vehicle context even when detection is done after recording.
 */
public class ClipTelemetryLogger {
    private static final String TAG = "ClipTelemetryLogger";
    private static final String CSV_HEADER =
            "wall_time_utc,video_ms,gps_lat,gps_lon,gps_speed_kmh,device_speed_kmh," +
            "device_bearing_deg,device_accel_ms2,sample_reason\n";

    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private FileWriter writer;
    private long clipStartWallMs;
    private boolean active = false;
    private long lastWriteWallMs = 0L;
    private long burstUntilWallMs = 0L;
    private String pendingReason = "startup";

    private double lastGpsLat = 0;
    private double lastGpsLon = 0;
    private float lastGpsSpeed = 0;
    private float lastDeviceSpeed = 0;
    private float lastBearing = 0;
    private float lastAcceleration = 0;

    private float lastLoggedGpsSpeed = 0;
    private float lastLoggedDeviceSpeed = 0;
    private float lastLoggedBearing = 0;
    private float lastLoggedAcceleration = 0;
    private boolean lastLoggedMoving = false;

    public ClipTelemetryLogger() {
        scheduler.scheduleAtFixedRate(this::writeSnapshot, 1, 1, TimeUnit.SECONDS);
    }

    public void start(File telemetryFile) {
        synchronized (lock) {
            stopLocked();
            if (telemetryFile == null) {
                return;
            }
            try {
                File parent = telemetryFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Failed to create telemetry dir: " + parent);
                }
                writer = new FileWriter(telemetryFile, false);
                writer.write(CSV_HEADER);
                writer.flush();
                clipStartWallMs = System.currentTimeMillis();
                lastWriteWallMs = 0L;
                burstUntilWallMs = clipStartWallMs + AdaptiveSampling.BURST_HOLD_MS;
                pendingReason = "startup";
                lastLoggedGpsSpeed = 0f;
                lastLoggedDeviceSpeed = 0f;
                lastLoggedBearing = 0f;
                lastLoggedAcceleration = 0f;
                lastLoggedMoving = false;
                active = true;
                writeSnapshotLocked();
            } catch (IOException e) {
                Log.e(TAG, "Failed to start telemetry logger", e);
                stopLocked();
            }
        }
    }

    public void updateSensorData(double lat, double lon, float gpsSpeed,
                                 float deviceSpeed, float bearing, float acceleration) {
        synchronized (lock) {
            lastGpsLat = lat;
            lastGpsLon = lon;
            lastGpsSpeed = gpsSpeed;
            lastDeviceSpeed = deviceSpeed;
            lastBearing = bearing;
            lastAcceleration = acceleration;

            boolean moving = Math.max(gpsSpeed, deviceSpeed) >= AdaptiveSampling.SPEED_ACTIVE_KMH;
            boolean speedJump = Math.abs(gpsSpeed - lastLoggedGpsSpeed) >= AdaptiveSampling.SPEED_DELTA_EVENT_KMH
                    || Math.abs(deviceSpeed - lastLoggedDeviceSpeed) >= AdaptiveSampling.SPEED_DELTA_EVENT_KMH;
            boolean accelEvent = Math.abs(acceleration) >= AdaptiveSampling.ACCEL_EVENT_MS2
                    && Math.abs(acceleration - lastLoggedAcceleration) >= 0.3f;
            boolean bearingEvent = angleDelta(bearing, lastLoggedBearing) >= AdaptiveSampling.BEARING_DELTA_EVENT_DEG;
            boolean stopStart = moving != lastLoggedMoving;

            if (stopStart) {
                triggerBurstLocked(moving ? "start_motion" : "stop_motion");
            } else if (speedJump) {
                triggerBurstLocked("speed_change");
            } else if (accelEvent) {
                triggerBurstLocked("accel_event");
            } else if (bearingEvent && moving) {
                triggerBurstLocked("bearing_change");
            } else if (moving && "startup".equals(pendingReason)) {
                pendingReason = "moving_5s";
            } else if (!moving && "startup".equals(pendingReason)) {
                pendingReason = "quiet_10s";
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    public void close() {
        stop();
        scheduler.shutdownNow();
    }

    private void writeSnapshot() {
        synchronized (lock) {
            writeSnapshotLocked();
        }
    }

    private void writeSnapshotLocked() {
        if (!active || writer == null) {
            return;
        }
        long wallTimeMs = System.currentTimeMillis();
        if (lastWriteWallMs != 0L && wallTimeMs - lastWriteWallMs < currentIntervalLocked(wallTimeMs)) {
            return;
        }
        long videoMs = Math.max(0L, wallTimeMs - clipStartWallMs);
        String wallTimeUtc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .format(new Date(wallTimeMs));
        String reason = currentReasonLocked(wallTimeMs);
        String line = String.format(Locale.US,
                "%s,%d,%.6f,%.6f,%.2f,%.2f,%.2f,%.3f,%s\n",
                wallTimeUtc,
                videoMs,
                lastGpsLat,
                lastGpsLon,
                lastGpsSpeed,
                lastDeviceSpeed,
                lastBearing,
                lastAcceleration,
                reason);
        try {
            writer.write(line);
            writer.flush();
            lastWriteWallMs = wallTimeMs;
            lastLoggedGpsSpeed = lastGpsSpeed;
            lastLoggedDeviceSpeed = lastDeviceSpeed;
            lastLoggedBearing = lastBearing;
            lastLoggedAcceleration = lastAcceleration;
            lastLoggedMoving = Math.max(lastGpsSpeed, lastDeviceSpeed) >= AdaptiveSampling.SPEED_ACTIVE_KMH;
            if (wallTimeMs >= burstUntilWallMs) {
                pendingReason = lastLoggedMoving ? "moving_5s" : "quiet_10s";
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed writing telemetry row", e);
            stopLocked();
        }
    }

    private long currentIntervalLocked(long nowMs) {
        if (nowMs < burstUntilWallMs) {
            return AdaptiveSampling.BURST_INTERVAL_MS;
        }
        boolean moving = Math.max(lastGpsSpeed, lastDeviceSpeed) >= AdaptiveSampling.SPEED_ACTIVE_KMH;
        return moving ? AdaptiveSampling.ACTIVE_INTERVAL_MS : AdaptiveSampling.QUIET_INTERVAL_MS;
    }

    private String currentReasonLocked(long nowMs) {
        if (nowMs < burstUntilWallMs) {
            return pendingReason;
        }
        boolean moving = Math.max(lastGpsSpeed, lastDeviceSpeed) >= AdaptiveSampling.SPEED_ACTIVE_KMH;
        return moving ? "moving_5s" : "quiet_10s";
    }

    private void triggerBurstLocked(String reason) {
        pendingReason = reason;
        burstUntilWallMs = System.currentTimeMillis() + AdaptiveSampling.BURST_HOLD_MS;
    }

    private static float angleDelta(float a, float b) {
        float delta = Math.abs(a - b) % 360f;
        return delta > 180f ? 360f - delta : delta;
    }

    private void stopLocked() {
        active = false;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close telemetry file", e);
            }
            writer = null;
        }
    }
}
