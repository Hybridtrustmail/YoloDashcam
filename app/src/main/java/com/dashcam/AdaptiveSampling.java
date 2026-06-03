package com.dashcam;

/**
 * Shared adaptive logging policy.
 *
 * The recorder already starts/stops clips from motion logic. This sampler only
 * decides how densely CSV rows should be written while a clip exists.
 */
public final class AdaptiveSampling {
    public static final long BURST_INTERVAL_MS = 1_000L;
    public static final long ACTIVE_INTERVAL_MS = 5_000L;
    public static final long QUIET_INTERVAL_MS = 10_000L;
    public static final long BURST_HOLD_MS = 8_000L;

    public static final float SPEED_ACTIVE_KMH = 5.0f;
    public static final float SPEED_DELTA_EVENT_KMH = 4.0f;
    public static final float ACCEL_EVENT_MS2 = 0.8f;
    public static final float BEARING_DELTA_EVENT_DEG = 20.0f;
    public static final float VEHICLE_SPEED_DELTA_EVENT_KMH = 8.0f;

    private AdaptiveSampling() {
    }
}
