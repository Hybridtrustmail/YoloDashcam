package com.dashcam;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Fuses three motion signals into a single moving/stopped state and emits
 * start/stop recording events:
 *
 *   - accelerometer linear acceleration (m/s^2)  -> reacts fastest, before GPS lock
 *   - GPS speed (km/h)                            -> robust "actually driving" signal
 *   - visual-motion score (frame-diff 0..1)       -> keeps recording alive in traffic
 *
 * Movement = ANY signal over its threshold. Recording starts on the first sign of
 * motion and stops only after {@code autoStopMillis} with NO motion from any signal
 * (so it won't cut out at a red light while traffic moves in frame).
 *
 * All interaction is on the main thread.
 */
public class MotionTriggerWorker {
    private static final String TAG = "MotionTrigger";

    public interface Listener {
        void onShouldStartRecording();
        void onShouldStopRecording();
    }

    // Tunable thresholds (Settings can override later).
    private float accelThreshold = 1.5f;   // m/s^2 of linear acceleration
    private float gpsSpeedThreshold = 5.0f; // km/h
    private float visualMotionThreshold = 0.06f; // mean normalized frame delta
    private long autoStopMillis = 15_000L;  // stop after 15s of stillness

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private boolean autoEnabled = true; // false while manual override is in control
    private boolean moving = false;
    private long lastMotionAt = 0;

    // Periodic check for the auto-stop timeout.
    private static final long CHECK_INTERVAL = 1_000L;
    private final Runnable autoStopCheck = new Runnable() {
        @Override
        public void run() {
            if (moving && SystemClock.elapsedRealtime() - lastMotionAt >= autoStopMillis) {
                setMoving(false);
            }
            handler.postDelayed(this, CHECK_INTERVAL);
        }
    };

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setThresholds(float accelMs2, float gpsKmh, float visual, long autoStopMs) {
        this.accelThreshold = accelMs2;
        this.gpsSpeedThreshold = gpsKmh;
        this.visualMotionThreshold = visual;
        this.autoStopMillis = Math.max(2_000L, autoStopMs);
    }

    public void start() {
        handler.removeCallbacks(autoStopCheck);
        handler.postDelayed(autoStopCheck, CHECK_INTERVAL);
    }

    public void stop() {
        handler.removeCallbacks(autoStopCheck);
    }

    /** When false, the worker observes motion but does not drive recording. */
    public void setAutoEnabled(boolean enabled) {
        this.autoEnabled = enabled;
        if (!enabled) {
            moving = false; // manual is in charge; reset internal state
        }
    }

    public boolean isAutoEnabled() {
        return autoEnabled;
    }

    // --- Signal inputs (called from sensor / camera callbacks) ---

    public void onAcceleration(float linearAccelMs2) {
        if (linearAccelMs2 >= accelThreshold) registerMotion();
    }

    public void onGpsSpeed(float speedKmh) {
        if (speedKmh >= gpsSpeedThreshold) registerMotion();
    }

    public void onVisualMotion(float score01) {
        if (score01 >= visualMotionThreshold) registerMotion();
    }

    private void registerMotion() {
        lastMotionAt = SystemClock.elapsedRealtime();
        if (!moving) setMoving(true);
    }

    private void setMoving(boolean nowMoving) {
        if (moving == nowMoving) return;
        moving = nowMoving;
        Log.d(TAG, "moving=" + nowMoving);
        if (!autoEnabled || listener == null) return;
        if (nowMoving) {
            listener.onShouldStartRecording();
        } else {
            listener.onShouldStopRecording();
        }
    }
}
