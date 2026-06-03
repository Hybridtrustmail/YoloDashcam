package com.dashcam;

import android.graphics.Rect;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

/**
 * Cheap frame-to-frame motion estimate from the camera's luminance (Y) plane.
 *
 * Subsamples a fixed grid of luma pixels and returns the mean absolute
 * difference vs. the previous frame, normalized to 0..1. No bitmap allocation,
 * runs on every analysis frame regardless of whether vehicle detection is on.
 */
public class VisualMotionDetector {
    private static final int GRID = 32; // 32x32 sample points

    private byte[] previous;
    private boolean hasPrevious = false;

    /** @return motion score in 0..1 (0 = identical frame, higher = more change). */
    public float score(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes.length == 0) return 0f;

        ByteBuffer y = planes[0].getBuffer();
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();

        Rect crop = image.getCropRect();
        int width = crop.width();
        int height = crop.height();
        if (width <= 0 || height <= 0) return 0f;

        byte[] current = new byte[GRID * GRID];
        int idx = 0;
        for (int gy = 0; gy < GRID; gy++) {
            int py = crop.top + (int) ((gy + 0.5f) / GRID * height);
            for (int gx = 0; gx < GRID; gx++) {
                int px = crop.left + (int) ((gx + 0.5f) / GRID * width);
                int pos = py * rowStride + px * pixelStride;
                current[idx++] = (pos >= 0 && pos < y.limit()) ? y.get(pos) : 0;
            }
        }

        float score = 0f;
        if (hasPrevious) {
            long sum = 0;
            for (int i = 0; i < current.length; i++) {
                int a = current[i] & 0xFF;
                int b = previous[i] & 0xFF;
                sum += Math.abs(a - b);
            }
            score = (sum / (float) current.length) / 255f;
        }

        previous = current;
        hasPrevious = true;
        return score;
    }

    public void reset() {
        hasPrevious = false;
        previous = null;
    }
}
