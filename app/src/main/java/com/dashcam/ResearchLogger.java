package com.dashcam;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Appends long-lived research logs that are intentionally decoupled from clip
 * retention. Videos may loop, but these CSVs preserve the measurement history.
 */
public class ResearchLogger {
    private static final String SUMMARY_HEADER =
            "clip_base,mode,video_ms,total_detected,tracked_moving,cars,buses,trucks," +
            "motorcycles,bicycles,pedestrians,traffic_lights,stop_signs,avg_vehicle_speed_kmh,sample_reason\n";
    private static final String TRACKS_HEADER =
            "clip_base,mode,video_ms,track_id,class_name,confidence,est_speed_kmh," +
            "direction_deg,bbox_left,bbox_top,bbox_right,bbox_bottom,bbox_center_x,bbox_center_y," +
            "bbox_width,bbox_height,sample_reason\n";

    private final File summaryFile;
    private final File tracksFile;

    public ResearchLogger(File summaryFile, File tracksFile) {
        this.summaryFile = summaryFile;
        this.tracksFile = tracksFile;
    }

    public synchronized void appendSummary(ResearchSummaryRow row) throws IOException {
        appendLine(summaryFile, SUMMARY_HEADER, String.format(Locale.US,
                "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%s\n",
                csv(row.clipBase),
                csv(row.mode),
                row.videoMs,
                row.totalDetected,
                row.trackedMoving,
                row.cars,
                row.buses,
                row.trucks,
                row.motorcycles,
                row.bicycles,
                row.pedestrians,
                row.trafficLights,
                row.stopSigns,
                row.avgVehicleSpeedKmh,
                csv(row.sampleReason)));
    }

    public synchronized void appendTracks(List<ResearchTrackRow> rows) throws IOException {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (ResearchTrackRow row : rows) {
            lines.append(String.format(Locale.US,
                    "%s,%s,%d,%d,%s,%.4f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s\n",
                    csv(row.clipBase),
                    csv(row.mode),
                    row.videoMs,
                    row.trackId,
                    csv(row.className),
                    row.confidence,
                    row.estSpeedKmh,
                    row.directionDeg,
                    row.left,
                    row.top,
                    row.right,
                    row.bottom,
                    row.centerX,
                    row.centerY,
                    row.width,
                    row.height,
                    csv(row.sampleReason)));
        }
        appendLine(tracksFile, TRACKS_HEADER, lines.toString());
    }

    private static void appendLine(File file, String header, String payload) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create log dir: " + parent);
        }
        if (file.exists() && file.isDirectory()) {
            throw new IOException("Log path is a directory: " + file);
        }
        boolean writeHeader = !file.exists() || file.length() == 0;
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            if (writeHeader) {
                writer.write(header);
            }
            writer.write(payload);
        }
    }

    private static String csv(String value) {
        return value == null ? "" : value;
    }

    public static class ResearchSummaryRow {
        public String clipBase;
        public String mode;
        public long videoMs;
        public int totalDetected;
        public int trackedMoving;
        public int cars;
        public int buses;
        public int trucks;
        public int motorcycles;
        public int bicycles;
        public int pedestrians;
        public int trafficLights;
        public int stopSigns;
        public float avgVehicleSpeedKmh;
        public String sampleReason;
    }

    public static class ResearchTrackRow {
        public String clipBase;
        public String mode;
        public long videoMs;
        public int trackId;
        public String className;
        public float confidence;
        public float estSpeedKmh;
        public float directionDeg;
        public float left;
        public float top;
        public float right;
        public float bottom;
        public float centerX;
        public float centerY;
        public float width;
        public float height;
        public String sampleReason;
    }
}
