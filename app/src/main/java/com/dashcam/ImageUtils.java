package com.dashcam;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility class for image processing and conversion
 */
public class ImageUtils {
    
    /**
     * Convert ImageProxy to Bitmap
     * Handles YUV_420_888 format from CameraX
     */
    public static Bitmap imageProxyToBitmap(ImageProxy image) {
        if (image.getFormat() == ImageFormat.YUV_420_888) {
            return yuv420ToBitmap(image);
        } else {
            // Handle other formats if needed
            return null;
        }
    }
    
    /**
     * Convert YUV_420_888 ImageProxy to Bitmap
     */
    private static Bitmap yuv420ToBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        int outputIndex = 0;

        // Copy Y plane row-by-row while respecting row/pixel stride.
        for (int row = 0; row < height; row++) {
            int yRowOffset = row * yRowStride;
            for (int col = 0; col < width; col++) {
                nv21[outputIndex++] = yBuffer.get(yRowOffset + col * yPixelStride);
            }
        }

        // Interleave VU for NV21 format, again respecting row/pixel stride.
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        for (int row = 0; row < chromaHeight; row++) {
            int uRowOffset = row * uRowStride;
            int vRowOffset = row * vRowStride;
            for (int col = 0; col < chromaWidth; col++) {
                nv21[outputIndex++] = vBuffer.get(vRowOffset + col * vPixelStride);
                nv21[outputIndex++] = uBuffer.get(uRowOffset + col * uPixelStride);
            }
        }
        
        // Direct NV21 -> ARGB conversion. Avoids the per-frame JPEG encode +
        // decode round-trip, which was the main cause of preview freezing.
        int[] argb = new int[width * height];
        decodeNV21ToArgb(nv21, width, height, argb);
        Bitmap bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);

        // Rotate if needed (CameraX images are often rotated)
        return rotateBitmap(bitmap, image.getImageInfo().getRotationDegrees());
    }

    /**
     * Integer YUV(NV21) -> ARGB_8888 conversion (BT.601). nv21 holds Y plane
     * followed by interleaved V,U. Writes one ARGB pixel per Y sample.
     */
    private static void decodeNV21ToArgb(byte[] yuv, int width, int height, int[] out) {
        final int frameSize = width * height;
        for (int j = 0, yp = 0; j < height; j++) {
            int uvp = frameSize + (j >> 1) * width;
            int u = 0, v = 0;
            for (int i = 0; i < width; i++, yp++) {
                int y = (0xff & yuv[yp]) - 16;
                if (y < 0) y = 0;
                if ((i & 1) == 0) {
                    v = (0xff & yuv[uvp++]) - 128;
                    u = (0xff & yuv[uvp++]) - 128;
                }
                int y1192 = 1192 * y;
                int r = y1192 + 1634 * v;
                int g = y1192 - 833 * v - 400 * u;
                int b = y1192 + 2066 * u;
                if (r < 0) r = 0; else if (r > 262143) r = 262143;
                if (g < 0) g = 0; else if (g > 262143) g = 262143;
                if (b < 0) b = 0; else if (b > 262143) b = 262143;
                out[yp] = 0xff000000
                        | ((r << 6) & 0xff0000)
                        | ((g >> 2) & 0xff00)
                        | ((b >> 10) & 0xff);
            }
        }
    }
    
    /**
     * Rotate bitmap by specified degrees
     */
    private static Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        if (degrees == 0) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);

        Bitmap rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.getWidth(),
            bitmap.getHeight(),
            matrix,
            true
        );
        // Free the pre-rotation bitmap right away; on the live path this runs
        // many times per second and would otherwise pile up until GC/OOM.
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }
    
    /**
     * Resize bitmap to target size
     */
    public static Bitmap resizeBitmap(Bitmap bitmap, int targetWidth, int targetHeight) {
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }
    
    /**
     * Convert Bitmap to byte array for TensorFlow Lite
     */
    public static ByteBuffer bitmapToByteBuffer(Bitmap bitmap, int inputSize) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3);
        byteBuffer.order(ByteOrder.nativeOrder());
        
        int[] intValues = new int[inputSize * inputSize];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        
        // Convert pixel values to float and normalize
        int pixel = 0;
        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                final int val = intValues[pixel++];
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);  // R
                byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);   // G
                byteBuffer.putFloat((val & 0xFF) / 255.0f);          // B
            }
        }
        
        return byteBuffer;
    }
    
    /**
     * Calculate intersection area between two rectangles
     */
    public static float calculateIntersectionArea(
            float x1, float y1, float w1, float h1,
            float x2, float y2, float w2, float h2) {
        
        float left = Math.max(x1, x2);
        float right = Math.min(x1 + w1, x2 + w2);
        float top = Math.max(y1, y2);
        float bottom = Math.min(y1 + h1, y2 + h2);
        
        if (left < right && top < bottom) {
            return (right - left) * (bottom - top);
        }
        
        return 0;
    }
}
