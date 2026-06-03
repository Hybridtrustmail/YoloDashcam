package com.dashcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Analyzes finalized clips in the background so recording can continue on the
 * next segment without live CameraX image analysis.
 */
public class ClipAnalysisWorker {
    private static final String TAG = "ClipAnalysisWorker";

    public interface Listener {
        /** Called on the main thread whenever the queue size changes. */
        void onQueueChanged(int pending, String currentClipName);
    }
    private static final long INTERNAL_ANALYSIS_STEP_MS = 250L;
    private static final String CSV_HEADER =
            "wall_time_utc,video_ms,gps_lat,gps_lon,gps_speed_kmh,device_speed_kmh," +
            "device_bearing_deg,device_accel_ms2,total_detected,tracked_moving,cars,buses,trucks," +
            "motorcycles,bicycles,pedestrians,traffic_lights,stop_signs,avg_vehicle_speed_kmh,sample_reason\n";

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ClipStorage clipStorage;
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private Listener listener;

    public ClipAnalysisWorker(Context context) {
        this.appContext = context.getApplicationContext();
        this.clipStorage = new ClipStorage(appContext);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void enqueue(File videoFile, File csvFile) {
        String clipName = videoFile != null ? stripExt(videoFile.getName()) : "";
        int size = queueSize.incrementAndGet();
        notifyQueueChanged(size, clipName);
        executor.execute(() -> {
            analyze(videoFile, csvFile, clipStorage.telemetryFileFor(
                    stripExt(videoFile != null ? videoFile.getName() : "")));
            notifyQueueChanged(queueSize.decrementAndGet(), "");
        });
    }

    public void enqueue(File videoFile, File csvFile, File telemetryFile) {
        String clipName = videoFile != null ? stripExt(videoFile.getName()) : "";
        int size = queueSize.incrementAndGet();
        notifyQueueChanged(size, clipName);
        executor.execute(() -> {
            analyze(videoFile, csvFile, telemetryFile);
            notifyQueueChanged(queueSize.decrementAndGet(), "");
        });
    }

    private void notifyQueueChanged(int size, String clipName) {
        if (listener == null) return;
        new Handler(Looper.getMainLooper()).post(() -> listener.onQueueChanged(size, clipName));
    }

    private void analyze(File videoFile, File csvFile, File telemetryFile) {
        if (videoFile == null || csvFile == null || !videoFile.exists()) {
            return;
        }

        Log.d(TAG, "Analyzing clip: " + videoFile.getName());
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        YoloDetector detector = new YoloDetector(appContext, YoloDetector.MODEL_YOLO11);
        VehicleTracker tracker = new VehicleTracker();
        SpeedCalculator speedCalculator = new SpeedCalculator();
        List<TelemetryRow> telemetryRows = loadTelemetry(telemetryFile);
        ResearchLogger researchLogger = new ResearchLogger(
                clipStorage.researchSummaryFile(),
                clipStorage.researchTracksFile());
        String clipBase = stripExt(videoFile.getName());

        try (FileWriter writer = new FileWriter(csvFile, false)) {
            writer.write(CSV_HEADER);
            retriever.setDataSource(videoFile.getAbsolutePath());

            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0L;
            AnalysisState state = new AnalysisState();

            for (long tMs = 0; tMs < durationMs; tMs += INTERNAL_ANALYSIS_STEP_MS) {
                Bitmap frame = retriever.getFrameAtTime(tMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) {
                    continue;
                }

                List<Detection> detections = detector.detectVehicles(frame);
                for (Detection detection : detections) {
                    detection.timestamp = tMs;
                }
                Map<Integer, TrackedVehicle> trackedVehicles = tracker.updateTracks(detections);
                Map<Integer, Float> speeds = new HashMap<>();
                for (Map.Entry<Integer, TrackedVehicle> entry : trackedVehicles.entrySet()) {
                    float speed = speedCalculator.calculateSpeed(entry.getValue());
                    if (speed > 0 && speed < Config.MAX_REASONABLE_SPEED) {
                        speeds.put(entry.getKey(), speed);
                    }
                }
                Map<String, Integer> counts = new HashMap<>();
                for (Detection detection : detections) {
                    String type = normalizeClassKey(detection.className);
                    counts.put(type, counts.getOrDefault(type, 0) + 1);
                }

                float avgVehicleSpeed = averageSpeed(speeds);
                TelemetryRow telemetry = nearestTelemetry(telemetryRows, tMs);
                String sampleReason = state.sampleReasonFor(
                        tMs,
                        telemetry,
                        detections.size(),
                        trackedVehicles.size(),
                        counts,
                        avgVehicleSpeed
                );
                if (sampleReason == null) {
                    frame.recycle();
                    continue;
                }
                String line = String.format(Locale.US,
                        "%s,%d,%.6f,%.6f,%.2f,%.2f,%.2f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%s\n",
                        telemetry.wallTimeUtc,
                        tMs,
                        telemetry.gpsLat,
                        telemetry.gpsLon,
                        telemetry.gpsSpeedKmh,
                        telemetry.deviceSpeedKmh,
                        telemetry.deviceBearingDeg,
                        telemetry.deviceAccelMs2,
                        detections.size(),
                        trackedVehicles.size(),
                        counts.getOrDefault("car", 0),
                        counts.getOrDefault("bus", 0),
                        counts.getOrDefault("truck", 0),
                        counts.getOrDefault("motorcycle", 0),
                        counts.getOrDefault("bicycle", 0),
                        counts.getOrDefault("person", 0),
                        counts.getOrDefault("traffic_light", 0),
                        counts.getOrDefault("stop_sign", 0),
                        avgVehicleSpeed,
                        sampleReason);
                writer.write(line);
                writer.flush();

                ResearchLogger.ResearchSummaryRow summaryRow = new ResearchLogger.ResearchSummaryRow();
                summaryRow.clipBase = clipBase;
                summaryRow.mode = "analyze";
                summaryRow.videoMs = tMs;
                summaryRow.totalDetected = detections.size();
                summaryRow.trackedMoving = trackedVehicles.size();
                summaryRow.cars = counts.getOrDefault("car", 0);
                summaryRow.buses = counts.getOrDefault("bus", 0);
                summaryRow.trucks = counts.getOrDefault("truck", 0);
                summaryRow.motorcycles = counts.getOrDefault("motorcycle", 0);
                summaryRow.bicycles = counts.getOrDefault("bicycle", 0);
                summaryRow.pedestrians = counts.getOrDefault("person", 0);
                summaryRow.trafficLights = counts.getOrDefault("traffic_light", 0);
                summaryRow.stopSigns = counts.getOrDefault("stop_sign", 0);
                summaryRow.avgVehicleSpeedKmh = avgVehicleSpeed;
                summaryRow.sampleReason = sampleReason;
                researchLogger.appendSummary(summaryRow);

                List<ResearchLogger.ResearchTrackRow> trackRows = new ArrayList<>();
                for (Detection detection : detections) {
                    Integer trackId = tracker.getTrackId(detection);
                    ResearchLogger.ResearchTrackRow row = new ResearchLogger.ResearchTrackRow();
                    row.clipBase = clipBase;
                    row.mode = "analyze";
                    row.videoMs = tMs;
                    row.trackId = trackId != null ? trackId : -1;
                    row.className = detection.className;
                    row.confidence = detection.confidence;
                    row.estSpeedKmh = trackId != null && speeds.containsKey(trackId)
                            ? speeds.get(trackId) : -1f;
                    row.directionDeg = trackId != null && trackedVehicles.containsKey(trackId)
                            ? speedCalculator.getMovementDirection(trackedVehicles.get(trackId))
                            : -1f;
                    RectF box = detection.boundingBox;
                    row.left = box.left;
                    row.top = box.top;
                    row.right = box.right;
                    row.bottom = box.bottom;
                    row.centerX = box.centerX();
                    row.centerY = box.centerY();
                    row.width = box.width();
                    row.height = box.height();
                    row.sampleReason = sampleReason;
                    trackRows.add(row);
                }
                researchLogger.appendTracks(trackRows);
                frame.recycle();
            }

            Log.d(TAG, "Analysis finished: " + csvFile.getName());
        } catch (Exception e) {
            Log.e(TAG, "Failed analyzing clip " + videoFile.getName(), e);
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                Log.w(TAG, "Failed to release retriever", e);
            }
            detector.close();
        }
    }

    public void close() {
        executor.shutdownNow();
    }

    private static float averageSpeed(Map<Integer, Float> speeds) {
        if (speeds.isEmpty()) {
            return 0f;
        }
        float sum = 0f;
        int count = 0;
        for (Float speed : speeds.values()) {
            if (speed != null && speed > 0) {
                sum += speed;
                count++;
            }
        }
        return count == 0 ? 0f : sum / count;
    }

    private static String stripExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static List<TelemetryRow> loadTelemetry(File telemetryFile) {
        List<TelemetryRow> rows = new ArrayList<>();
        if (telemetryFile == null || !telemetryFile.exists()) {
            return rows;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(telemetryFile))) {
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length < 8) {
                    continue;
                }
                TelemetryRow row = new TelemetryRow();
                row.wallTimeUtc = parts[0];
                row.videoMs = parseLong(parts[1]);
                row.gpsLat = parseDouble(parts[2]);
                row.gpsLon = parseDouble(parts[3]);
                row.gpsSpeedKmh = parseFloat(parts[4]);
                row.deviceSpeedKmh = parseFloat(parts[5]);
                row.deviceBearingDeg = parseFloat(parts[6]);
                row.deviceAccelMs2 = parseFloat(parts[7]);
                row.sampleReason = parts.length > 8 ? parts[8] : "";
                rows.add(row);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load telemetry file " + telemetryFile.getName(), e);
        }
        return rows;
    }

    private static TelemetryRow nearestTelemetry(List<TelemetryRow> rows, long videoMs) {
        if (rows == null || rows.isEmpty()) {
            TelemetryRow row = new TelemetryRow();
            row.wallTimeUtc = "";
            return row;
        }
        TelemetryRow best = rows.get(0);
        long bestDist = Math.abs(best.videoMs - videoMs);
        for (TelemetryRow row : rows) {
            long dist = Math.abs(row.videoMs - videoMs);
            if (dist < bestDist) {
                best = row;
                bestDist = dist;
            }
        }
        return best;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static float parseFloat(String value) {
        try {
            return Float.parseFloat(value);
        } catch (Exception ignored) {
            return 0f;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return 0d;
        }
    }

    private static class TelemetryRow {
        String wallTimeUtc = "";
        long videoMs = 0L;
        double gpsLat = 0d;
        double gpsLon = 0d;
        float gpsSpeedKmh = 0f;
        float deviceSpeedKmh = 0f;
        float deviceBearingDeg = 0f;
        float deviceAccelMs2 = 0f;
        String sampleReason = "";
    }

    private static String normalizeClassKey(String className) {
        if (className == null) {
            return "";
        }
        return className.trim().toLowerCase(Locale.US).replace(' ', '_');
    }

    private static class AnalysisState {
        private long lastEmitMs = Long.MIN_VALUE;
        private long burstUntilMs = Long.MIN_VALUE;
        private int lastTotalDetected = -1;
        private int lastTrackedMoving = -1;
        private int lastCars = -1;
        private int lastBuses = -1;
        private int lastTrucks = -1;
        private int lastMotorcycles = -1;
        private int lastBicycles = -1;
        private int lastPedestrians = -1;
        private int lastTrafficLights = -1;
        private int lastStopSigns = -1;
        private float lastAvgVehicleSpeed = 0f;
        private float lastGpsSpeed = 0f;
        private float lastDeviceSpeed = 0f;
        private float lastBearing = 0f;

        String sampleReasonFor(long tMs,
                               TelemetryRow telemetry,
                               int totalDetected,
                               int trackedMoving,
                               Map<String, Integer> counts,
                               float avgVehicleSpeed) {
            if (lastEmitMs == Long.MIN_VALUE) {
                captureState(tMs, telemetry, totalDetected, trackedMoving, counts, avgVehicleSpeed);
                burstUntilMs = tMs + AdaptiveSampling.BURST_HOLD_MS;
                return "startup";
            }

            boolean countChanged = totalDetected != lastTotalDetected
                    || trackedMoving != lastTrackedMoving
                    || counts.getOrDefault("car", 0) != lastCars
                    || counts.getOrDefault("bus", 0) != lastBuses
                    || counts.getOrDefault("truck", 0) != lastTrucks
                    || counts.getOrDefault("motorcycle", 0) != lastMotorcycles
                    || counts.getOrDefault("bicycle", 0) != lastBicycles
                    || counts.getOrDefault("person", 0) != lastPedestrians
                    || counts.getOrDefault("traffic_light", 0) != lastTrafficLights
                    || counts.getOrDefault("stop_sign", 0) != lastStopSigns;
            boolean hostSpeedChanged = Math.abs(telemetry.gpsSpeedKmh - lastGpsSpeed) >= AdaptiveSampling.SPEED_DELTA_EVENT_KMH
                    || Math.abs(telemetry.deviceSpeedKmh - lastDeviceSpeed) >= AdaptiveSampling.SPEED_DELTA_EVENT_KMH;
            boolean hostBearingChanged = angleDelta(telemetry.deviceBearingDeg, lastBearing)
                    >= AdaptiveSampling.BEARING_DELTA_EVENT_DEG;
            boolean accelEvent = Math.abs(telemetry.deviceAccelMs2) >= AdaptiveSampling.ACCEL_EVENT_MS2;
            boolean vehicleSpeedChanged = Math.abs(avgVehicleSpeed - lastAvgVehicleSpeed)
                    >= AdaptiveSampling.VEHICLE_SPEED_DELTA_EVENT_KMH;
            boolean moving = Math.max(telemetry.gpsSpeedKmh, telemetry.deviceSpeedKmh)
                    >= AdaptiveSampling.SPEED_ACTIVE_KMH;

            String reason = null;
            if (countChanged) {
                reason = "count_change";
            } else if (vehicleSpeedChanged && totalDetected > 0) {
                reason = "vehicle_speed_change";
            } else if (hostSpeedChanged) {
                reason = "host_speed_change";
            } else if (accelEvent) {
                reason = "accel_event";
            } else if (hostBearingChanged && moving) {
                reason = "bearing_change";
            }

            if (reason != null) {
                burstUntilMs = tMs + AdaptiveSampling.BURST_HOLD_MS;
            }

            long interval = tMs < burstUntilMs
                    ? AdaptiveSampling.BURST_INTERVAL_MS
                    : moving ? AdaptiveSampling.ACTIVE_INTERVAL_MS : AdaptiveSampling.QUIET_INTERVAL_MS;
            if (tMs - lastEmitMs < interval && reason == null) {
                return null;
            }

            if (reason == null) {
                reason = tMs < burstUntilMs ? "event_followup_1s" : moving ? "moving_5s" : "quiet_10s";
            }

            captureState(tMs, telemetry, totalDetected, trackedMoving, counts, avgVehicleSpeed);
            return reason;
        }

        private void captureState(long tMs,
                                  TelemetryRow telemetry,
                                  int totalDetected,
                                  int trackedMoving,
                                  Map<String, Integer> counts,
                                  float avgVehicleSpeed) {
            lastEmitMs = tMs;
            lastTotalDetected = totalDetected;
            lastTrackedMoving = trackedMoving;
            lastCars = counts.getOrDefault("car", 0);
            lastBuses = counts.getOrDefault("bus", 0);
            lastTrucks = counts.getOrDefault("truck", 0);
            lastMotorcycles = counts.getOrDefault("motorcycle", 0);
            lastBicycles = counts.getOrDefault("bicycle", 0);
            lastPedestrians = counts.getOrDefault("person", 0);
            lastTrafficLights = counts.getOrDefault("traffic_light", 0);
            lastStopSigns = counts.getOrDefault("stop_sign", 0);
            lastAvgVehicleSpeed = avgVehicleSpeed;
            lastGpsSpeed = telemetry.gpsSpeedKmh;
            lastDeviceSpeed = telemetry.deviceSpeedKmh;
            lastBearing = telemetry.deviceBearingDeg;
        }

        private static float angleDelta(float a, float b) {
            float delta = Math.abs(a - b) % 360f;
            return delta > 180f ? 360f - delta : delta;
        }
    }
}
