package com.dashcam;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.Arrays;

/**
 * Records segmented dashcam clips with CameraX VideoCapture.
 *
 * - One clip per {@code segmentMillis} (default 60s), seamless restart on finalize.
 * - Highest available quality (UHD -> FHD -> HD), no audio.
 * - Loop retention enforced via {@link ClipStorage} as each segment finalizes.
 * - start()/stop() are driven by the Motion/Trigger worker (Phase 3) or the
 *   manual record button. All methods must be called on the main thread.
 *
 * The owner (MainActivity) must bind {@link #getVideoCapture()} into the
 * CameraX use-case set alongside Preview + ImageAnalysis.
 */
public class RecordingWorker {
    private static final String TAG = "RecordingWorker";

    public static final int QUALITY_HD = Settings.QUALITY_HD;
    public static final int QUALITY_FHD = Settings.QUALITY_FHD;
    public static final int QUALITY_UHD = Settings.QUALITY_UHD;

    /** Lifecycle callbacks, delivered on the main thread. */
    public interface Listener {
        /** A new segment began; logging should open the paired CSV. */
        void onSegmentStarted(String baseName, File csvFile);
        /** A segment finished writing to disk. */
        void onSegmentFinalized(File videoFile);
        /** Recording fully stopped (after stop() or a fatal error). */
        void onRecordingStopped();
        void onError(String message);
    }

    private final Context appContext;
    private final ClipStorage storage;
    private final VideoCapture<Recorder> videoCapture;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Listener listener;
    private Recording activeRecording;
    private String activeBaseName;

    private boolean recording = false;     // user/trigger intent
    private long segmentMillis = 60_000L;   // 1-minute segments
    private boolean audioEnabled = false;
    private long segmentStartMs = 0L;
    private int selectedQuality = QUALITY_FHD;

    // Posted to end the current segment; finalize then starts the next one.
    private final Runnable rolloverRunnable = this::rollSegment;

    public RecordingWorker(Context context, ClipStorage storage) {
        this(context, storage, QUALITY_FHD);
    }

    public RecordingWorker(Context context, ClipStorage storage, int quality) {
        this.appContext = context.getApplicationContext();
        this.storage = storage;
        this.selectedQuality = quality;

        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.fromOrderedList(
                        qualityOrderFor(quality),
                        androidx.camera.video.FallbackStrategy
                                .lowerQualityOrHigherThan(Quality.HD)))
                .build();
        this.videoCapture = VideoCapture.withOutput(recorder);
    }

    private java.util.List<Quality> qualityOrderFor(int quality) {
        switch (quality) {
            case QUALITY_HD:
                return Arrays.asList(Quality.HD, Quality.FHD, Quality.UHD);
            case QUALITY_UHD:
                return Arrays.asList(Quality.UHD, Quality.FHD, Quality.HD);
            case QUALITY_FHD:
            default:
                return Arrays.asList(Quality.FHD, Quality.HD, Quality.UHD);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setSegmentMillis(long millis) {
        this.segmentMillis = Math.max(5_000L, millis);
    }

    public void setAudioEnabled(boolean enabled) {
        this.audioEnabled = enabled;
    }

    /** Seconds elapsed in the current segment, 0 if not recording. */
    public int getElapsedSegmentSeconds() {
        if (!recording || segmentStartMs == 0) return 0;
        return (int) ((System.currentTimeMillis() - segmentStartMs) / 1000);
    }

    /** Total seconds per segment. */
    public int getSegmentSeconds() {
        return (int) (segmentMillis / 1000);
    }

    /** The use case the Activity must bind into CameraX. */
    public VideoCapture<Recorder> getVideoCapture() {
        return videoCapture;
    }

    /** Basename of the segment currently being recorded, null when not recording. */
    public String getCurrentBaseName() {
        return recording ? activeBaseName : null;
    }

    public boolean isRecording() {
        return recording;
    }

    public int getSelectedQuality() {
        return selectedQuality;
    }

    /** Begin segmented recording (no-op if already recording). */
    public void start() {
        if (recording) return;
        recording = true;
        startSegment();
    }

    /** Stop recording and cancel the rollover timer. */
    public void stop() {
        if (!recording) return;
        recording = false;
        mainHandler.removeCallbacks(rolloverRunnable);
        if (activeRecording != null) {
            activeRecording.stop(); // triggers Finalize; we won't restart
        }
    }

    /** End the current segment; onFinalize will chain the next one. */
    private void rollSegment() {
        if (activeRecording != null) {
            activeRecording.stop();
        }
    }

    /** Gracefully end the current segment and continue with the next one. */
    public void requestSegmentRollover() {
        if (!recording || activeRecording == null) return;
        mainHandler.removeCallbacks(rolloverRunnable);
        activeRecording.stop();
    }

    @SuppressLint("MissingPermission") // audio disabled; only CAMERA needed
    private void startSegment() {
        String baseName = storage.newBaseName();
        File videoFile = storage.videoFileFor(baseName);
        File csvFile = storage.csvFileFor(baseName);

        FileOutputOptions outputOptions =
                new FileOutputOptions.Builder(videoFile).build();

        try {
            androidx.camera.video.PendingRecording pending =
                    videoCapture.getOutput().prepareRecording(appContext, outputOptions);
            if (audioEnabled) {
                pending.withAudioEnabled();
            }
            activeRecording = pending.start(ContextCompat.getMainExecutor(appContext),
                    this::onVideoRecordEvent);
            activeBaseName = baseName;
            segmentStartMs = System.currentTimeMillis();

            if (listener != null) listener.onSegmentStarted(baseName, csvFile);
            mainHandler.postDelayed(rolloverRunnable, segmentMillis);
            Log.d(TAG, "Segment started: " + baseName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start segment", e);
            recording = false;
            if (listener != null) listener.onError("Failed to start recording: " + e.getMessage());
        }
    }

    private void onVideoRecordEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
            File finished = storage.videoFileFor(activeBaseName);
            mainHandler.removeCallbacks(rolloverRunnable);

            if (finalize.hasError()) {
                Log.e(TAG, "Segment finalize error code=" + finalize.getError());
                activeRecording = null;
                boolean wasRecording = recording;
                recording = false;
                if (listener != null) {
                    listener.onError("Recording stopped due to camera error (" + finalize.getError() + ")");
                    if (wasRecording) {
                        listener.onRecordingStopped();
                    }
                }
                return;
            }
            if (listener != null && finished.exists() && finished.length() > 0) {
                listener.onSegmentFinalized(finished);
            }

            // Retention runs off the main thread to avoid blocking the UI.
            new Thread(storage::enforceRetention, "clip-retention").start();

            activeRecording = null;

            if (recording) {
                startSegment();              // chain next segment
            } else if (listener != null) {
                listener.onRecordingStopped();
            }
        }
    }

    /** Release resources. */
    public void close() {
        stop();
    }
}
