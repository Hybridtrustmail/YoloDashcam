package com.dashcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * YOLO11 detector (TFLite).
 *
 * Output format is auto-detected from the model's output tensor shape, so the
 * same code path handles any input resolution in the YOLO11 family
 * (channels-first [1, 84, N] layout).
 */
public class YoloDetector {
    private static final String TAG = "YoloDetector";

    public static final int MODEL_YOLO11 = 1;

    private static final String ASSET_YOLO11 = "yolo11n_f16.tflite";

    private Interpreter interpreter;
    private ImageProcessor imageProcessor;

    // Resolved at load time from the model's tensors (not hardcoded).
    private int inputSize = Config.MODEL_INPUT_SIZE;
    private int numElements;      // 84 for YOLO11
    private int numDetections;    // anchor count, depends on input resolution


    // Which model actually loaded, for display/settings comparison.
    public int loadedModelType = MODEL_YOLO11;
    private String loadedAsset = "";

    // Output buffer, sized to the model's real output shape.
    private float[][][] outputBuffer;

    public YoloDetector(Context context) {
        this(context, MODEL_YOLO11);
    }

    public YoloDetector(Context context, int modelType) {
        try {
            loadModel(context, modelType);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize YOLO detector", e);
            interpreter = null;
        }
    }

    private String[] orderedAssetsFor(int modelType) {
        return new String[]{ASSET_YOLO11};
    }

    private int typeForAsset(String asset) {
        return MODEL_YOLO11;
    }

    private void loadModel(Context context, int modelType) {
        for (String asset : orderedAssetsFor(modelType)) {
            try {
                ByteBuffer modelBuffer = FileUtil.loadMappedFile(context, asset);
                Interpreter.Options options = new Interpreter.Options();
                // Prefer a smaller CPU path here; it is slower on paper but
                // much more stable for the live camera mode on this phone.
                options.setNumThreads(2);
                options.setUseNNAPI(false);

                interpreter = new Interpreter(modelBuffer, options);

                // Read the real input resolution from the model.
                int[] inShape = interpreter.getInputTensor(0).shape(); // [1, H, W, 3]
                if (inShape.length == 4) {
                    inputSize = inShape[1];
                }

                // Read the real output shape and decide the layout from it.
                int[] outShape = interpreter.getOutputTensor(0).shape(); // [1, A, B]
                int a = outShape[1];
                int b = outShape[2];
                if (a < b) {
                    numElements = a;
                    numDetections = b;
                } else {
                    numElements = b;
                    numDetections = a;
                }
                outputBuffer = new float[1][outShape[1]][outShape[2]];

                imageProcessor = new ImageProcessor.Builder()
                        .add(new ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                        .add(new NormalizeOp(0f, 255f)) // normalize to [0,1]
                        .build();

                loadedAsset = asset;
                loadedModelType = typeForAsset(asset);
                Log.d(TAG, "Loaded " + getModelInfo() + " from " + asset
                        + " (input=" + inputSize + ", elements=" + numElements
                        + ", detections=" + numDetections + ")");
                return; // success

            } catch (IOException e) {
                Log.w(TAG, "Model asset not available: " + asset);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load model: " + asset, e);
            }
        }
        Log.e(TAG, "Failed to load any YOLO model");
        interpreter = null;
    }

    public List<Detection> detectVehicles(ImageProxy image) {
        if (interpreter == null) {
            Log.w(TAG, "Interpreter is null, returning empty detections");
            return new ArrayList<>();
        }

        Bitmap bitmap = null;
        try {
            bitmap = imageProxyToBitmap(image);
            if (bitmap == null) {
                Log.w(TAG, "Failed to convert image to bitmap");
                return new ArrayList<>();
            }
            return detectVehicles(bitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error during vehicle detection", e);
            return new ArrayList<>();
        } finally {
            // Live path: release the per-frame source bitmap immediately so it
            // doesn't accumulate and OOM-crash on long sessions.
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    public List<Detection> detectVehicles(Bitmap bitmap) {
        if (interpreter == null || bitmap == null) {
            return new ArrayList<>();
        }
        try {
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
            TensorImage tensorImage = new TensorImage();
            tensorImage.load(bitmap);
            tensorImage = imageProcessor.process(tensorImage);
            interpreter.run(tensorImage.getBuffer(), outputBuffer);
            return processModernOutputs(outputBuffer[0], bitmap.getWidth(), bitmap.getHeight());
        } catch (Exception e) {
            Log.e(TAG, "Error during bitmap detection", e);
            return new ArrayList<>();
        }
    }

    /** YOLO11: outputs[element][detection], 84 elements, no objectness. */
    private List<Detection> processModernOutputs(float[][] outputs, int sourceWidth, int sourceHeight) {
        List<Detection> detections = new ArrayList<>();

        for (int i = 0; i < numDetections; i++) {
            int bestClass = -1;
            float bestScore = 0;
            for (int c = 0; c < Config.NUM_CLASSES; c++) {
                float score = outputs[4 + c][i];
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }

            if (bestScore < Config.DETECTION_THRESHOLD) continue;
            if (!isSupportedClass(bestClass)) continue;

            float x = outputs[0][i];
            float y = outputs[1][i];
            float w = outputs[2][i];
            float h = outputs[3][i];

            detections.add(makeDetection(x, y, w, h, bestScore, bestClass, sourceWidth, sourceHeight));
        }

        return applyNMS(detections);
    }

    /** Build a Detection from center-format coords (normalized 0..1) in pixel space. */
    private Detection makeDetection(float x, float y, float w, float h,
                                    float confidence, int classId,
                                    int sourceWidth, int sourceHeight) {
        RectF box = new RectF(
                (x - w / 2) * inputSize,
                (y - h / 2) * inputSize,
                (x + w / 2) * inputSize,
                (y + h / 2) * inputSize
        );
        Detection det = new Detection();
        det.boundingBox = box;
        det.confidence = confidence;
        det.classId = classId;
        det.className = getClassName(classId);
        det.sourceWidth = sourceWidth;
        det.sourceHeight = sourceHeight;
        det.modelInputSize = inputSize;
        return det;
    }

    private List<Detection> applyNMS(List<Detection> detections) {
        List<Detection> result = new ArrayList<>();
        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));
        boolean[] suppressed = new boolean[detections.size()];

        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            Detection current = detections.get(i);
            result.add(current);
            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressed[j]) continue;
                if (calculateIoU(current.boundingBox, detections.get(j).boundingBox) > 0.5f) {
                    suppressed[j] = true;
                }
            }
        }
        return result;
    }

    private float calculateIoU(RectF box1, RectF box2) {
        float x1 = Math.max(box1.left, box2.left);
        float y1 = Math.max(box1.top, box2.top);
        float x2 = Math.min(box1.right, box2.right);
        float y2 = Math.min(box1.bottom, box2.bottom);

        float intersectionArea = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        float box1Area = (box1.right - box1.left) * (box1.bottom - box1.top);
        float box2Area = (box2.right - box2.left) * (box2.bottom - box2.top);
        float unionArea = box1Area + box2Area - intersectionArea;

        return unionArea > 0 ? intersectionArea / unionArea : 0;
    }

    private boolean isSupportedClass(int classId) {
        for (int supportedId : Config.SUPPORTED_CLASS_IDS) {
            if (classId == supportedId) return true;
        }
        return false;
    }

    private String getClassName(int classId) {
        switch (classId) {
            case 0: return "Person";
            case 1: return "Bicycle";
            case 2: return "Car";
            case 3: return "Motorcycle";
            case 5: return "Bus";
            case 7: return "Truck";
            case 9: return "Traffic Light";
            case 11: return "Stop Sign";
            default: return "Road Object";
        }
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        return ImageUtils.imageProxyToBitmap(image);
    }

    /** Input resolution the loaded model actually expects. */
    public int getInputSize() {
        return inputSize;
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }

    public String getModelInfo() {
        return "YOLO11";
    }
}

/**
 * Detection result class
 */
class Detection {
    public RectF boundingBox;
    public float confidence;
    public int classId;
    public String className;
    public long timestamp = System.currentTimeMillis();
    public int sourceWidth;
    public int sourceHeight;
    public int modelInputSize;
}
