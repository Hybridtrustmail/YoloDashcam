package com.dashcam;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * Owns the dashcam clip directory: file naming and loop retention.
 *
 * Each recording produces a matched pair that share a basename:
 *   clip_2026-05-29_14-03-00.mp4   (video, written by RecordingWorker)
 *   clip_2026-05-29_14-03-00.csv   (detections, written by LoggingWorker)
 *
 * Loop retention deletes the oldest clips (video + paired csv together) once
 * the directory exceeds the size budget or free space drops below the floor.
 */
public class ClipStorage {
    private static final String TAG = "ClipStorage";
    private static final String CLIP_PREFIX = "clip_";
    private static final SimpleDateFormat NAME_FMT =
            new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US);

    private final Context appContext;
    private File clipDir;
    private File researchDir;

    // Retention budget (tunable via Settings later).
    private long maxTotalBytes = 4L * 1024 * 1024 * 1024;   // 4 GB of clips
    private long minFreeBytes  = 500L * 1024 * 1024;        // keep 500 MB free

    public ClipStorage(Context context) {
        appContext = context.getApplicationContext();
        updateDirectory(new Settings(appContext).getOutputFolder());
    }

    public File getClipDir() {
        return clipDir;
    }

    public void updateDirectory(String folderSetting) {
        clipDir = resolveDirectory(folderSetting);
        if (!clipDir.exists() && !clipDir.mkdirs()) {
            Log.e(TAG, "Failed to create clip dir: " + clipDir);
        }
        File externalBase = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        researchDir = new File(externalBase != null ? externalBase : appContext.getFilesDir(),
                "DashCAMResearch");
        if (!researchDir.exists() && !researchDir.mkdirs()) {
            Log.e(TAG, "Failed to create research dir: " + researchDir);
        }
    }

    public void setBudget(long maxTotalBytes, long minFreeBytes) {
        this.maxTotalBytes = maxTotalBytes;
        this.minFreeBytes = minFreeBytes;
    }

    /** Basename (no extension) for a clip starting now, e.g. "clip_2026-05-29_14-03-00". */
    public String newBaseName() {
        return CLIP_PREFIX + NAME_FMT.format(new Date());
    }

    public File videoFileFor(String baseName) {
        return new File(clipDir, baseName + ".mp4");
    }

    public File csvFileFor(String baseName) {
        return new File(clipDir, baseName + ".csv");
    }

    public File telemetryFileFor(String baseName) {
        return new File(clipDir, baseName + ".telemetry.csv");
    }

    public File researchSummaryFile() {
        return new File(researchDir, "research_summary.csv");
    }

    public File researchTracksFile() {
        return new File(researchDir, "research_tracks.csv");
    }

    // ---- Incident lock (sidecar .lock file) ----

    /** Protect a clip from loop deletion. Creates a zero-byte sidecar file. */
    public void protectClip(String baseName) {
        if (baseName == null || baseName.isEmpty()) return;
        File lock = lockFileFor(baseName);
        try { lock.createNewFile(); } catch (Exception e) {
            Log.w(TAG, "Could not create lock file for " + baseName);
        }
    }

    public void unprotectClip(String baseName) {
        if (baseName == null) return;
        deleteQuietly(lockFileFor(baseName));
    }

    public boolean isProtected(String baseName) {
        return baseName != null && lockFileFor(baseName).exists();
    }

    private File lockFileFor(String baseName) {
        return new File(clipDir, baseName + ".lock");
    }

    // ---- Storage stats ----

    public int getClipCount() {
        File[] videos = clipDir.listFiles((d, n) -> n.startsWith(CLIP_PREFIX) && n.endsWith(".mp4"));
        return videos == null ? 0 : videos.length;
    }

    public long getTotalSizeBytes() {
        File[] files = clipDir.listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File f : files) total += f.length();
        return total;
    }

    public long getFreeSpaceBytes() {
        return clipDir.getUsableSpace();
    }

    /**
     * Enforce the retention budget. Deletes oldest clip pairs (mp4 + csv) until
     * total size is within budget AND free space is above the floor.
     * Safe to call after each segment finalizes.
     */
    public void enforceRetention() {
        File[] videos = clipDir.listFiles((dir, name) ->
                name.startsWith(CLIP_PREFIX) && name.endsWith(".mp4"));
        if (videos == null || videos.length == 0) return;

        // Oldest first (by last-modified).
        Arrays.sort(videos, Comparator.comparingLong(File::lastModified));

        long totalBytes = 0;
        for (File v : videos) totalBytes += v.length();

        for (File video : videos) {
            boolean overBudget = totalBytes > maxTotalBytes;
            boolean lowSpace = clipDir.getUsableSpace() < minFreeBytes;
            if (!overBudget && !lowSpace) break;

            String base = stripExt(video.getName());
            if (isProtected(base)) {
                Log.d(TAG, "Skipping protected clip: " + video.getName());
                continue;
            }

            long freed = video.length();
            File csv = csvFileFor(base);
            File telemetry = telemetryFileFor(base);

            if (deleteQuietly(video)) {
                totalBytes -= freed;
                Log.d(TAG, "Loop-deleted " + video.getName());
            }
            deleteQuietly(csv); // paired log goes with it (may not exist yet)
            deleteQuietly(telemetry);
        }
    }

    private static String stripExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static boolean deleteQuietly(File f) {
        return f != null && f.exists() && f.delete();
    }

    private File resolveDirectory(String folderSetting) {
        String normalized = folderSetting == null ? "" : folderSetting.trim();
        if (normalized.isEmpty()) {
            normalized = "Downloads/DashCAM";
        }

        File preferred;
        if (normalized.startsWith("/")) {
            preferred = new File(normalized);
        } else if (normalized.startsWith("Downloads/") || normalized.equals("Downloads")) {
            String child = normalized.length() > "Downloads".length()
                    ? normalized.substring("Downloads".length()).replaceFirst("^/+", "")
                    : "";
            preferred = child.isEmpty()
                    ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    : new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), child);
        } else {
            preferred = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), normalized);
        }

        if (preferred.exists() || preferred.mkdirs()) {
            return preferred;
        }

        File fallbackBase = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File fallback = new File(fallbackBase != null ? fallbackBase : appContext.getFilesDir(), "DashCAM");
        Log.w(TAG, "Falling back to app-specific storage: " + fallback);
        return fallback;
    }
}
