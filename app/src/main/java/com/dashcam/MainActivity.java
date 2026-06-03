package com.dashcam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;
import android.view.Menu;
import android.view.MenuItem;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity 
        implements DeviceSensorManager.SensorDataListener {
    
    private static final String TAG = "VehicleDetector";
    private static final int MODE_ACTIVE_DETECT = Color.parseColor("#00C853");
    private static final int MODE_ACTIVE_ANALYZE = Color.parseColor("#0288D1");
    private static final int MODE_ACTIVE_RECORD = Color.parseColor("#D32F2F");
    private static final int MODE_INACTIVE = Color.parseColor("#424242");
    private static final int MODE_TEXT = Color.WHITE;
    private static final long DETECT_FRAME_INTERVAL_MS = 4000L;
    private static final float LIVE_AI_MIN_THRESHOLD = 0.10f;
    private static final boolean LOW_POWER_UI = true;
    private static final boolean SHOW_LIVE_DETECT_OVERLAY = true;
    private static final boolean SHOW_REC_PULSE = false;
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final int REQUEST_CODE_AUDIO_PERMISSION = 11;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    // UI Components
    private PreviewView previewView;
    private ImageView overlayView;
    private View rootContainer;
    private TextView infoTextView;
    private TextView debugTextView;
    private TextView statusTextView;
    private Button recordButton;
    private Button detectModeButton;
    private Button analyzeModeButton;
    private Button recordModeButton;
    private Button exitButton;
    private CheckBox audioCheckBox;
    private Button screenToggleButton;
    private Button hudToggleButton;
    private ProgressBar clipProgressBar;
    private TextView clipTimerText;
    private TextView analyzeStatusText;
    private TextView gpsSpeedHud;
    private View bottomStrip;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Handler clipTickHandler = new Handler(Looper.getMainLooper());
    private final Runnable clipTickRunnable = this::tickClipProgress;
    private final Runnable recPulseRunnable = this::pulseRecButton;
    private final AtomicBoolean detectFrameInFlight = new AtomicBoolean(false);
    private long lastDetectFrameMs = 0L;
    private Bitmap overlayBitmap;
    private Canvas overlayCanvas;
    private boolean exitingApp = false;
    private String lastProgressBaseName = null;

    // Incident lock
    private String currentRecordingBaseName = null;

    // HUD visibility toggle (tap preview to hide/show info overlays)
    private boolean hudInfoVisible = true;
    private boolean displayedStationary = true;
    private Boolean pendingStationary = null;
    private final Runnable motionLabelCommit = () -> {
        if (pendingStationary != null) {
            displayedStationary = pendingStationary;
            pendingStationary = null;
            updateInfoText();
        }
    };

    // Camera and Processing
    private ExecutorService cameraExecutor;
    private YoloDetector yoloDetector;
    private VehicleTracker vehicleTracker;
    private SpeedCalculator speedCalculator;
    private DataLogger dataLogger;
    private DeviceSensorManager sensorManager;

    // Dashcam recording
    private ClipStorage clipStorage;
    private RecordingWorker recordingWorker;
    private MotionTriggerWorker motionTrigger;
    private VisualMotionDetector visualMotion;
    private ClipAnalysisWorker clipAnalysisWorker;

    // For drawing
    private Paint boxPaint;
    private Paint textPaint;
    private Paint speedPaint;
    private Paint parkedPaint;

    // Metrics
    private long frameCount = 0;
    private long lastFpsTime = 0;
    private int currentFps = 0;
    private int analysisLogCounter = 0;
    
    // Recording state
    private boolean isRecording = false;
    private boolean videoCaptureBound = false;
    private boolean analysisBound = false;
    private boolean hasShownVideoUnavailable = false;
    private boolean isSwitchingMode = false;
    private boolean detectCameraStarting = false;
    private int pendingAppMode = -1;
    private ProcessCameraProvider detectCameraProvider;
    
    // Settings
    private Settings settings;
    private DashcamService dashcamService;
    private boolean serviceBound = false;

    private final ServiceConnection dashcamConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            dashcamService = ((DashcamService.LocalBinder) service).getService();
            serviceBound = true;
            dashcamService.setListener((monitoring, recording, status) ->
                    runOnUiThread(() -> {
                        if (!isDetectOnlyMode()) {
                            recordButton.setText(monitoring ? "STOP" : "REC");
                            recordButton.setBackgroundTintList(
                                    android.content.res.ColorStateList.valueOf(
                                            monitoring ? Color.GRAY : Color.parseColor("#F44336")));
                            statusTextView.setText(status + " | Screen-off OK");

                            boolean wasRecording = isRecording;
                            isRecording = recording;

                            // Pulsing REC dot — start/stop via service state changes
                            if (recording && !wasRecording) {
                                clipProgressBar.setVisibility(hudInfoVisible ? View.VISIBLE : View.INVISIBLE);
                                clipProgressBar.setProgress(0);
                                clipTimerText.setVisibility(hudInfoVisible ? View.VISIBLE : View.INVISIBLE);
                                clipTickHandler.removeCallbacks(clipTickRunnable);
                                clipTickHandler.post(clipTickRunnable);
                                if (SHOW_REC_PULSE) {
                                    uiHandler.removeCallbacks(recPulseRunnable);
                                    uiHandler.post(recPulseRunnable);
                                }
                            } else if (!recording && wasRecording) {
                                clipTickHandler.removeCallbacks(clipTickRunnable);
                                uiHandler.removeCallbacks(recPulseRunnable);
                                recordButton.animate().alpha(1.0f).setDuration(200).start();
                                clipProgressBar.setProgress(0);
                                clipProgressBar.setVisibility(View.INVISIBLE);
                                clipTimerText.setText("");
                                clipTimerText.setVisibility(View.INVISIBLE);
                            }

                            updateInfoText();

                            if (pendingAppMode != -1 && !monitoring && !recording) {
                                int modeToApply = pendingAppMode;
                                pendingAppMode = -1;
                                completeAppModeSwitch(modeToApply);
                            }
                        }
                    }));
            dashcamService.setAnalyzePrevious(isAnalyzePreviousMode());
            dashcamService.setParkingModeEnabled(settings.getParkingModeEnabled());
            if (!isDetectOnlyMode()) {
                previewView.post(() -> {
                    if (dashcamService == null) {
                        return;
                    }
                    // Hand over the surface. While idle this shows the aiming
                    // preview; while monitoring it's stored but the pipeline
                    // stays headless (no camera poke = stable screen on/off).
                    dashcamService.setPreviewSurface(previewView.getSurfaceProvider());
                    if (!dashcamService.getMonitoring() && settings.getAutoRecordOnLaunch()) {
                        ContextCompat.startForegroundService(
                                MainActivity.this, new Intent(MainActivity.this, DashcamService.class));
                        dashcamService.startMonitoring(true);
                    }
                });
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            dashcamService = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate started");
        
        try {
            setContentView(R.layout.activity_main);
            Log.d(TAG, "Layout set successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error setting content view", e);
        }

        Log.d(TAG, "Finding UI components...");
        // Initialize UI components
        rootContainer = findViewById(R.id.rootContainer);
        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        infoTextView = findViewById(R.id.infoText);
        debugTextView = findViewById(R.id.debugText);
        statusTextView = findViewById(R.id.statusText);
        recordButton = findViewById(R.id.recordButton);
        audioCheckBox = findViewById(R.id.audioCheckBox);
        screenToggleButton = findViewById(R.id.screenToggleButton);
        hudToggleButton = findViewById(R.id.hudToggleButton);
        clipProgressBar = findViewById(R.id.clipProgressBar);
        clipTimerText = findViewById(R.id.clipTimerText);
        analyzeStatusText = findViewById(R.id.analyzeStatusText);
        gpsSpeedHud = findViewById(R.id.gpsSpeedHud);
        bottomStrip = findViewById(R.id.bottomStrip);
        Log.d(TAG, "UI components found");

        // Keep the preview passive, but prefer the normal fast preview path.
        // The controls are outside the preview area now, so the slower
        // compatibility compositor is not worth the FPS penalty on-device.
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        previewView.setClickable(false);
        previewView.setFocusable(false);
        previewView.setFocusableInTouchMode(false);
        overlayView.setClickable(false);
        overlayView.setFocusable(false);
        overlayView.setFocusableInTouchMode(false);
        rootContainer.setClickable(true);
        rootContainer.setFocusable(false);
        bottomStrip.setClickable(true);
        bottomStrip.setFocusable(true);
        recordButton.setClickable(true);
        recordButton.setFocusable(true);
        audioCheckBox.setClickable(true);
        audioCheckBox.setFocusable(true);
        screenToggleButton.setClickable(true);
        screenToggleButton.setFocusable(true);
        hudToggleButton.setClickable(true);
        hudToggleButton.setFocusable(true);

        // Keep controls above the preview layer on devices where PreviewView uses SurfaceView.
        overlayView.bringToFront();
        infoTextView.bringToFront();
        debugTextView.bringToFront();
        gpsSpeedHud.bringToFront();
        bottomStrip.bringToFront();

        // Only explicit controls toggle UI; the preview itself is passive.
        hudToggleButton.setOnClickListener(v -> toggleHudVisibility());

        setupActionBarControls();
        
        // Set up record button: tap = manual override, long-press = resume auto
        recordButton.setOnClickListener(v -> {
            Log.d(TAG, "Record button clicked");
            toggleRecording();
        });
        recordButton.setOnLongClickListener(v -> {
            // Incident lock: long-press while recording protects the current clip
            if (isRecording) {
                String baseName = currentRecordingBaseName;
                if (baseName == null && serviceBound && dashcamService != null) {
                    baseName = dashcamService.getCurrentBaseName();
                }
                if (baseName != null) {
                    clipStorage.protectClip(baseName);
                    showToast("Clip locked 🔒");
                    recordButton.setBackgroundTintList(
                            ColorStateList.valueOf(Color.parseColor("#FF9800")));
                    final String finalBase = baseName;
                    uiHandler.postDelayed(() -> {
                        if (isRecording) {
                            recordButton.setBackgroundTintList(
                                    ColorStateList.valueOf(Color.GRAY));
                        }
                    }, 2000);
                    return true;
                }
            }
            if (!isDetectOnlyMode()) {
                if (motionTrigger.isAutoEnabled()) {
                    showToast("Motion-triggered monitoring is the default");
                } else {
                    motionTrigger.setAutoEnabled(true);
                    showToast("Auto mode: recording follows motion");
                }
                return true;
            }
            return false;
        });
        detectModeButton.setOnClickListener(v -> {
            Log.d(TAG, "Detect mode button clicked");
            switchAppMode(Settings.MODE_DETECT_ONLY);
        });
        analyzeModeButton.setOnClickListener(v -> {
            Log.d(TAG, "Analyze mode button clicked");
            switchAppMode(Settings.MODE_RECORD_ANALYZE_PREVIOUS);
        });
        recordModeButton.setOnClickListener(v -> {
            Log.d(TAG, "Record mode button clicked");
            switchAppMode(Settings.MODE_RECORD_ONLY);
        });
        exitButton.setOnClickListener(v -> {
            Log.d(TAG, "Exit button clicked");
            exitingApp = true;
            Intent serviceIntent = new Intent(this, DashcamService.class);
            if (dashcamService != null) {
                dashcamService.stopAndShutdown();
            }
            stopService(serviceIntent);
            if (recordingWorker.isRecording()) {
                recordingWorker.stop();
            }
            finishAndRemoveTask();
        });

        // Initialize paints for drawing
        initializePaints();

        // Initialize settings before components that depend on defaults.
        settings = new Settings(this);
        setupQuickControls();

        Log.d(TAG, "Initializing components...");
        // Initialize components.
        // Run YOLO inference on a BACKGROUND-priority thread so the OS scheduler
        // favors the camera/render thread. Keeps the Live AI preview fluid during
        // inference bursts (inference is a little slower, boxes lag a bit more,
        // which is the accepted trade-off on this CPU-only device).
        cameraExecutor = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "yolo-analyzer"));
        
        Log.d(TAG, "Initializing YOLO detector...");
        yoloDetector = new YoloDetector(this, settings.getModelType());
        
        Log.d(TAG, "Initializing vehicle tracker...");
        vehicleTracker = new VehicleTracker();
        speedCalculator = new SpeedCalculator();
        // Don't start logger until recording starts
        // dataLogger = new DataLogger(this);
        
        Log.d(TAG, "Initializing dashcam recorder...");
        clipStorage = new ClipStorage(this);
        recordingWorker = new RecordingWorker(this, clipStorage);
        recreateRecordingWorkerIfNeeded();
        recordingWorker.setAudioEnabled(settings.getAudioEnabled());
        recordingWorker.setListener(recordingListener);
        clipAnalysisWorker = new ClipAnalysisWorker(this);
        clipAnalysisWorker.setListener((pending, clipName) -> {
            if (pending > 0) {
                String msg = pending == 1 ? "⚙ Analyzing..." : "⚙ Analyzing (" + pending + " queued)";
                analyzeStatusText.setText(msg);
            } else {
                analyzeStatusText.setText("");
            }
        });

        Log.d(TAG, "Initializing motion trigger...");
        visualMotion = new VisualMotionDetector();
        motionTrigger = new MotionTriggerWorker();
        motionTrigger.setListener(motionTriggerListener);

        Log.d(TAG, "Initializing sensor manager...");
        sensorManager = new DeviceSensorManager(this);
        sensorManager.setListener(this);
        displayedStationary = sensorManager.isStationary();
        
        Log.d(TAG, "All components initialized");
        
        applySettings();
        updateModeUi();

        // Check permissions
        if (allPermissionsGranted()) {
            startCamera();
            sensorManager.startSensors();
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        // Show initial info
        updateInfoText();
        requestAudioPermissionIfMissing();
    }

    private void setupActionBarControls() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        View controls = getLayoutInflater().inflate(R.layout.action_bar_controls, null);
        detectModeButton = controls.findViewById(R.id.detectModeButton);
        analyzeModeButton = controls.findViewById(R.id.analyzeModeButton);
        recordModeButton = controls.findViewById(R.id.recordModeButton);
        exitButton = controls.findViewById(R.id.exitButton);
        actionBar.setDisplayShowCustomEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        ActionBar.LayoutParams params = new ActionBar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        actionBar.setCustomView(controls, params);
    }

    private void initializePaints() {
        // Paint for bounding boxes
        boxPaint = new Paint();
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3f);

        // Paint for labels
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40f);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setShadowLayer(2f, 2f, 2f, Color.BLACK);

        // Paint for speed display
        speedPaint = new Paint();
        speedPaint.setColor(Color.YELLOW);
        speedPaint.setTextSize(50f);
        speedPaint.setStyle(Paint.Style.FILL);
        speedPaint.setFakeBoldText(true);
        speedPaint.setShadowLayer(2f, 2f, 2f, Color.BLACK);

        // Paint for parked vehicles
        parkedPaint = new Paint();
        parkedPaint.setColor(Color.RED);
        parkedPaint.setStyle(Paint.Style.STROKE);
        parkedPaint.setStrokeWidth(2f);
        parkedPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{10, 5}, 0));
    }

    private boolean isRecordOnlyMode() {
        return settings.getAppMode() == Settings.MODE_RECORD_ONLY;
    }

    private boolean isDetectOnlyMode() {
        return settings.getAppMode() == Settings.MODE_DETECT_ONLY;
    }

    private boolean isAnalyzePreviousMode() {
        return settings.getAppMode() == Settings.MODE_RECORD_ANALYZE_PREVIOUS;
    }

    private void updateModeUi() {
        boolean detectMode = isDetectOnlyMode();
        boolean analyzeMode = isAnalyzePreviousMode();
        styleModeButton(detectModeButton, detectMode, MODE_ACTIVE_DETECT);
        styleModeButton(analyzeModeButton, analyzeMode, MODE_ACTIVE_ANALYZE);
        styleModeButton(recordModeButton, !detectMode && !analyzeMode, MODE_ACTIVE_RECORD);
        recordButton.setEnabled(!detectMode);
        recordButton.setAlpha(detectMode ? 0.5f : 1.0f);
        overlayView.setVisibility(detectMode ? View.VISIBLE : View.INVISIBLE);
    }

    private void styleModeButton(Button button, boolean active, int activeColor) {
        button.setEnabled(true);
        button.setAlpha(1.0f);
        button.setTextColor(MODE_TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(active ? activeColor : MODE_INACTIVE));
    }

    private void clearOverlayAndTracking() {
        overlayView.setImageBitmap(null);
        overlayView.setImageDrawable(null);
        vehicleTracker.reset();
        debugTextView.setText("");
        detectFrameInFlight.set(false);
    }

    private void switchAppMode(int appMode) {
        if (settings.getAppMode() == appMode || isSwitchingMode) {
            return;
        }
        isSwitchingMode = true;
        Log.d(TAG, "Switching mode to " + appMode);
        if (dashcamService != null && dashcamService.getMonitoring()) {
            pendingAppMode = appMode;
            dashcamService.stopAndShutdown();
            showToast("Stopping current mode...");
            return;
        }
        completeAppModeSwitch(appMode);
    }

    private void completeAppModeSwitch(int appMode) {
        if (dashcamService != null && appMode == Settings.MODE_DETECT_ONLY) {
            dashcamService.shutdownIdleSession();
        }
        settings.setAppMode(appMode);
        if (appMode == Settings.MODE_DETECT_ONLY && recordingWorker.isRecording()) {
            recordingWorker.stop();
        }
        applySettings();
        updateModeUi();
        clearOverlayAndTracking();
        if (allPermissionsGranted()) {
            startCamera();
        }
        showToast(appMode == Settings.MODE_DETECT_ONLY
                ? "Live AI mode"
                : appMode == Settings.MODE_RECORD_ANALYZE_PREVIOUS
                ? "Record + AI log mode"
                : "Record-only mode");
    }

    private void startCamera() {
        Log.d(TAG, "startCamera mode=" + settings.getAppMode());
        if (!isDetectOnlyMode()) {
            stopDetectCamera();
            if (dashcamService != null) {
                dashcamService.setAnalyzePrevious(isAnalyzePreviousMode());
                // Real surface -> aiming preview while idle; headless while recording.
                dashcamService.setPreviewSurface(previewView.getSurfaceProvider());
                if (dashcamService.getMonitoring()) {
                    dashcamService.ensureCamera();
                }
            }
            runOnUiThread(() -> statusTextView.setText(
                    isAnalyzePreviousMode()
                            ? "Log+AI mode | Headless recording, AI after clip"
                            : "Record-only mode | Headless recording"));
            isSwitchingMode = false;
            return;
        }
        if (detectCameraStarting) {
            Log.d(TAG, "Detect camera start already in progress; skipping duplicate request");
            return;
        }
        if (analysisBound && detectCameraProvider != null) {
            Log.d(TAG, "Detect camera already bound; skipping duplicate request");
            isSwitchingMode = false;
            return;
        }
        detectCameraStarting = true;
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                detectCameraProvider = cameraProvider;

                // Preview use case
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Image analysis use case
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new android.util.Size(224, 126))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                    @Override
                    public void analyze(@NonNull ImageProxy image) {
                        processImage(image);
                    }
                });

                // Select back camera
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                try {
                    cameraProvider.unbindAll();

                    if (isDetectOnlyMode()) {
                        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                        videoCaptureBound = false;
                        analysisBound = true;
                        motionTrigger.stop();
                        Log.d(TAG, "Bound detect-only camera pipeline");
                    } else {
                        cameraProvider.bindToLifecycle(this, cameraSelector,
                                preview, recordingWorker.getVideoCapture());
                        videoCaptureBound = true;
                        analysisBound = false;
                        clearOverlayAndTracking();
                        motionTrigger.start();
                        Log.d(TAG, isAnalyzePreviousMode()
                                ? "Bound analyze-previous camera pipeline"
                                : "Bound record-only camera pipeline");
                    }

                    runOnUiThread(() -> statusTextView.setText(
                            analysisBound
                                    ? "Live AI mode | Model: " + yoloDetector.getModelInfo()
                                    : isAnalyzePreviousMode()
                                    ? "Record + AI log mode | Video ready"
                                    : "Record-only mode | Video ready"));

                } catch (Exception exc) {
                    Log.e(TAG, "Use case binding failed", exc);
                    videoCaptureBound = false;
                    analysisBound = false;
                    showToast(isDetectOnlyMode()
                            ? "Live AI mode failed on this device"
                            : "Record-only mode failed on this device");
                } finally {
                    detectCameraStarting = false;
                    isSwitchingMode = false;
                }

            } catch (ExecutionException | InterruptedException e) {
                detectCameraStarting = false;
                isSwitchingMode = false;
                Log.e(TAG, "Camera provider failed", e);
                showToast("Camera provider failed");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopDetectCamera() {
        detectCameraStarting = false;
        analysisBound = false;
        videoCaptureBound = false;
        if (detectCameraProvider != null) {
            detectCameraProvider.unbindAll();
        }
        clearOverlayAndTracking();
    }

    private void processImage(ImageProxy image) {
        boolean processingThisFrame = false;
        try {
            if (!analysisBound) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastDetectFrameMs < DETECT_FRAME_INTERVAL_MS) {
                return;
            }
            if (!detectFrameInFlight.compareAndSet(false, true)) {
                return;
            }
            processingThisFrame = true;
            lastDetectFrameMs = now;

            // Update FPS
            updateFps();

            if (!isDetectOnlyMode()) {
                // Visual-motion keep-alive is only needed for recording modes.
                final float motionScore = visualMotion.score(image);
                runOnUiThread(() -> motionTrigger.onVisualMotion(motionScore));
            }

            // Run YOLO detection
            List<Detection> detections = yoloDetector.detectVehicles(image);
            if ((analysisLogCounter++ % 30) == 0) {
                Log.d(TAG, "Analysis frame: detections=" + detections.size()
                        + ", mode=" + settings.getAppMode());
            }
            
            Map<Integer, TrackedVehicle> trackedVehicles;
            Map<Integer, Float> speeds = new HashMap<>();
            if (isDetectOnlyMode()) {
                // Live AI is the weakest mode on this phone. Keep only detection
                // boxes live and skip the extra tracking/speed work.
                trackedVehicles = new HashMap<>();
            } else {
                trackedVehicles = vehicleTracker.updateTracks(detections);
                for (Map.Entry<Integer, TrackedVehicle> entry : trackedVehicles.entrySet()) {
                    float speed = speedCalculator.calculateSpeed(entry.getValue());
                    if (speed > 0 && speed < Config.MAX_REASONABLE_SPEED) {
                        speeds.put(entry.getKey(), speed);
                    }
                }
            }
            
            // Update data logger if recording
            if (dataLogger != null && isRecording) {
                for (Detection detection : detections) {
                    Integer trackId = vehicleTracker.getTrackId(detection);
                    dataLogger.updateVehicleDetection(detection, trackId);
                }
                dataLogger.updateSpeed(speeds);
            }
            
            // Draw results on UI thread
            runOnUiThread(() -> {
                if (SHOW_LIVE_DETECT_OVERLAY) {
                    drawResults(detections, trackedVehicles, speeds);
                } else {
                    overlayView.setImageBitmap(null);
                    overlayView.setImageDrawable(null);
                }
                updateDebugInfo(detections.size(), trackedVehicles.size());
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing image", e);
        } finally {
            if (processingThisFrame) {
                detectFrameInFlight.set(false);
            }
            image.close();
        }
    }

    private void updateFps() {
        frameCount++;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFpsTime > 1000) {
            currentFps = (int) (frameCount * 1000 / (currentTime - lastFpsTime));
            frameCount = 0;
            lastFpsTime = currentTime;
        }
    }

    private void drawResults(List<Detection> detections, 
                           Map<Integer, TrackedVehicle> trackedVehicles,
                           Map<Integer, Float> speeds) {
        int width = previewView.getWidth();
        int height = previewView.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (overlayBitmap == null || overlayBitmap.getWidth() != width || overlayBitmap.getHeight() != height) {
            if (overlayBitmap != null) {
                overlayBitmap.recycle();
            }
            overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            overlayCanvas = new Canvas(overlayBitmap);
        }
        overlayBitmap.eraseColor(Color.TRANSPARENT);
        Canvas canvas = overlayCanvas;

        boolean liveDetectMode = isDetectOnlyMode();
        int maxBoxes = liveDetectMode ? Math.min(detections.size(), 3) : detections.size();

        // Draw each detection
        for (int i = 0; i < maxBoxes; i++) {
            Detection detection = detections.get(i);
            // Get tracked ID if available
            Integer trackId = vehicleTracker.getTrackId(detection);

            RectF scaledBox = mapDetectionToPreview(detection, width, height);
            
            // Choose paint based on whether vehicle is moving
            Paint currentBoxPaint = boxPaint;
            if (trackId != null && trackedVehicles.containsKey(trackId)) {
                TrackedVehicle tracked = trackedVehicles.get(trackId);
                if (tracked.isParked()) {
                    currentBoxPaint = parkedPaint;
                }
            }
            
            // Draw bounding box
            canvas.drawRect(scaledBox, currentBoxPaint);
            
            if (!liveDetectMode) {
                // Draw label with confidence
                String label = detection.className + " " +
                        String.format("%.0f%%", detection.confidence * 100);
                if (trackId != null) {
                    label += " #" + trackId;
                }
                canvas.drawText(label, scaledBox.left, scaledBox.top - 10, textPaint);

                // Draw speed if available
                if (trackId != null && speeds.containsKey(trackId)) {
                    String speedText = Config.formatSpeed(speeds.get(trackId));
                    canvas.drawText(speedText,
                            scaledBox.left,
                            scaledBox.bottom + 50,
                            speedPaint);
                }
            }
        }

        // Update overlay
        overlayView.setImageBitmap(overlayBitmap);
    }

    private RectF mapDetectionToPreview(Detection detection, int previewWidth, int previewHeight) {
        int modelInputSize = detection.modelInputSize > 0
                ? detection.modelInputSize
                : yoloDetector.getInputSize();
        int sourceWidth = detection.sourceWidth > 0 ? detection.sourceWidth : modelInputSize;
        int sourceHeight = detection.sourceHeight > 0 ? detection.sourceHeight : modelInputSize;

        RectF src = detection.boundingBox;
        RectF sourceBox = new RectF(
                src.left * sourceWidth / modelInputSize,
                src.top * sourceHeight / modelInputSize,
                src.right * sourceWidth / modelInputSize,
                src.bottom * sourceHeight / modelInputSize
        );

        float scale = Math.max(
                (float) previewWidth / sourceWidth,
                (float) previewHeight / sourceHeight
        );
        float dx = (previewWidth - sourceWidth * scale) / 2f;
        float dy = (previewHeight - sourceHeight * scale) / 2f;

        return new RectF(
                sourceBox.left * scale + dx,
                sourceBox.top * scale + dy,
                sourceBox.right * scale + dx,
                sourceBox.bottom * scale + dy
        );
    }

    private void updateInfoText() {
        long freeBytes = clipStorage.getFreeSpaceBytes();
        String freeStr = freeBytes > 1_000_000_000L
                ? String.format(java.util.Locale.US, "%.1f GB free", freeBytes / 1e9)
                : String.format(java.util.Locale.US, "%d MB free", freeBytes / 1_048_576);
        int clips = clipStorage.getClipCount();

        boolean parking = serviceBound && dashcamService != null && dashcamService.isParkingMode();
        String modeStr = parking ? "PARKING" : (displayedStationary ? "Stationary" : "Moving");
        String recStr  = isRecording ? (parking ? "REC (bump)" : "RECORDING") : "Idle";

        String info = String.format(java.util.Locale.US,
                "%.1fm %.0f° | %s | %s | %s · %d clips",
                Config.CAMERA_HEIGHT, Config.CAMERA_ANGLE,
                modeStr, recStr, freeStr, clips);
        infoTextView.setText(info);
    }

    private void updateDebugInfo(int detections, int tracked) {
        if (Config.SHOW_DEBUG_INFO) {
            String debug = String.format(
                    "Model: %s | Det: %d | Track: %d | FPS: %d | GPS: %.1f km/h",
                    yoloDetector.getModelInfo(),
                    detections, tracked, currentFps, sensorManager.getGpsSpeed()
            );
            debugTextView.setText(debug);
            
            // Update status text with log file info
            if (isRecording && dataLogger != null) {
                String logFile = dataLogger.getLogFilePath();
                if (logFile != null && !logFile.isEmpty()) {
                    statusTextView.setText("Recording to: " + new java.io.File(logFile).getName());
                }
            } else {
                        statusTextView.setText(
                                analysisBound
                                        ? String.format(java.util.Locale.US,
                                "Live AI | %d objects | %d tracks", detections, tracked)
                                : isAnalyzePreviousMode()
                                ? "Record + AI log mode | Video ready"
                                : "Record-only mode | Video ready");
            }
        }
    }

    @Override
    public void onSensorDataUpdate(double lat, double lon, float gpsSpeed,
                                  float deviceSpeed, float bearing, float acceleration) {
        if (dataLogger != null && isRecording) {
            dataLogger.updateSensorData(lat, lon, gpsSpeed, deviceSpeed, bearing, acceleration);
        }
        motionTrigger.onAcceleration(acceleration);
        motionTrigger.onGpsSpeed(gpsSpeed);

        runOnUiThread(() -> {
            // GPS speed HUD
            if (gpsSpeedHud != null) {
                if (gpsSpeed > 1f) {
                    gpsSpeedHud.setText(String.format(java.util.Locale.US, "%.0f km/h", gpsSpeed));
                    gpsSpeedHud.setTextColor(Color.parseColor("#FFD600"));
                } else {
                    gpsSpeedHud.setText("-- km/h");
                    gpsSpeedHud.setTextColor(Color.parseColor("#80FFD600"));
                }
            }
            updateInfoText();
        });
    }

    @Override
    public void onStationaryStatusChanged(boolean isStationary) {
        runOnUiThread(() -> {
            if (displayedStationary == isStationary) {
                pendingStationary = null;
                uiHandler.removeCallbacks(motionLabelCommit);
                return;
            }
            pendingStationary = isStationary;
            uiHandler.removeCallbacks(motionLabelCommit);
            uiHandler.postDelayed(motionLabelCommit, 3000);
        });
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            showSettingsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_settings, null);
        styleSettingsDialog(dialogView);
        
        // Get views
        RadioGroup modeGroup = dialogView.findViewById(R.id.modeRadioGroup);
        RadioGroup qualityGroup = dialogView.findViewById(R.id.qualityRadioGroup);
        RadioGroup sensGroup = dialogView.findViewById(R.id.sensitivityGroup);
        EditText editHeight = dialogView.findViewById(R.id.editCameraHeight);
        EditText editDistance = dialogView.findViewById(R.id.editDistanceToRoad);
        EditText editAngle = dialogView.findViewById(R.id.editCameraAngle);
        EditText editOutputFolder = dialogView.findViewById(R.id.editOutputFolder);
        SeekBar seekBarConfidence = dialogView.findViewById(R.id.seekBarConfidence);
        TextView textConfidenceValue = dialogView.findViewById(R.id.textConfidenceValue);
        CheckBox checkAutoRecord = dialogView.findViewById(R.id.checkAutoRecordOnLaunch);
        CheckBox checkParking = dialogView.findViewById(R.id.checkParkingMode);
        CheckBox checkKeepScreenOn = dialogView.findViewById(R.id.checkKeepScreenOn);
        CheckBox checkAllowRotation = dialogView.findViewById(R.id.checkAllowRotation);

        if (isDetectOnlyMode()) {
            modeGroup.check(R.id.radioDetectOnly);
        } else if (isAnalyzePreviousMode()) {
            modeGroup.check(R.id.radioAnalyzePrevious);
        } else {
            modeGroup.check(R.id.radioRecordOnly);
        }

        switch (settings.getRecordingQuality()) {
            case Settings.QUALITY_HD:
                qualityGroup.check(R.id.radioQualityHD);
                break;
            case Settings.QUALITY_UHD:
                qualityGroup.check(R.id.radioQualityUHD);
                break;
            case Settings.QUALITY_FHD:
            default:
                qualityGroup.check(R.id.radioQualityFHD);
                break;
        }

        switch (settings.getSensitivityPreset()) {
            case Config.SENSITIVITY_LOW:  sensGroup.check(R.id.radioSensLow);  break;
            case Config.SENSITIVITY_HIGH: sensGroup.check(R.id.radioSensHigh); break;
            default:                      sensGroup.check(R.id.radioSensMed);  break;
        }

        editHeight.setText(String.valueOf(settings.getCameraHeight()));
        editDistance.setText(String.valueOf(settings.getDistanceToRoad()));
        editAngle.setText(String.valueOf(settings.getCameraAngle()));
        editOutputFolder.setText(settings.getOutputFolder());

        int confidencePercent = (int)(settings.getConfidenceThreshold() * 100);
        seekBarConfidence.setProgress(confidencePercent);
        textConfidenceValue.setText(String.format("%.2f", settings.getConfidenceThreshold()));

        checkAutoRecord.setChecked(settings.getAutoRecordOnLaunch());
        checkParking.setChecked(settings.getParkingModeEnabled());
        checkKeepScreenOn.setChecked(settings.getKeepScreenOn());
        checkAllowRotation.setChecked(settings.getAllowRotation());

        seekBarConfidence.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = progress / 100.0f;
                textConfidenceValue.setText(String.format("%.2f", value));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        builder.setTitle("Settings")
            .setView(dialogView)
            .setPositiveButton("Apply", (dialog, which) -> {
                int checkedModeId = modeGroup.getCheckedRadioButtonId();
                int appMode = checkedModeId == R.id.radioDetectOnly
                        ? Settings.MODE_DETECT_ONLY
                        : checkedModeId == R.id.radioAnalyzePrevious
                        ? Settings.MODE_RECORD_ANALYZE_PREVIOUS
                        : Settings.MODE_RECORD_ONLY;
                settings.setModelType(YoloDetector.MODEL_YOLO11);
                settings.setAppMode(appMode);

                int checkedQualityId = qualityGroup.getCheckedRadioButtonId();
                int recordingQuality = checkedQualityId == R.id.radioQualityHD
                        ? Settings.QUALITY_HD
                        : checkedQualityId == R.id.radioQualityUHD
                        ? Settings.QUALITY_UHD
                        : Settings.QUALITY_FHD;
                settings.setRecordingQuality(recordingQuality);

                int sensId = sensGroup.getCheckedRadioButtonId();
                int preset = sensId == R.id.radioSensLow  ? Config.SENSITIVITY_LOW
                           : sensId == R.id.radioSensHigh ? Config.SENSITIVITY_HIGH
                           : Config.SENSITIVITY_MEDIUM;
                settings.setSensitivityPreset(preset);
                // Sync confidence threshold to the chosen preset so seekbar and
                // preset stay consistent (seekbar shows the current value on reopen).
                settings.setConfidenceThreshold(Config.DETECTION_THRESHOLD_FOR_PRESET[preset]);

                try {
                    settings.setCameraHeight(Float.parseFloat(editHeight.getText().toString()));
                    settings.setDistanceToRoad(Float.parseFloat(editDistance.getText().toString()));
                    settings.setCameraAngle(Float.parseFloat(editAngle.getText().toString()));
                } catch (NumberFormatException e) {
                    showToast("Invalid number entered");
                }

                settings.setConfidenceThreshold(seekBarConfidence.getProgress() / 100.0f);
                settings.setOutputFolder(editOutputFolder.getText().toString().trim());
                settings.setAutoRecordOnLaunch(checkAutoRecord.isChecked());
                settings.setParkingModeEnabled(checkParking.isChecked());
                settings.setKeepScreenOn(checkKeepScreenOn.isChecked());
                settings.setAllowRotation(checkAllowRotation.isChecked());

                if (serviceBound && dashcamService != null) {
                    dashcamService.setParkingModeEnabled(checkParking.isChecked());
                }

                applySettings();
                updateModeUi();
                clearOverlayAndTracking();
                if (allPermissionsGranted()) {
                    startCamera();
                }
                showToast("Settings applied");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void applySettings() {
        // Update Config values from settings
        Config.CAMERA_HEIGHT = settings.getCameraHeight();
        Config.DISTANCE_TO_ROAD = settings.getDistanceToRoad();
        Config.CAMERA_ANGLE = settings.getCameraAngle();
        Config.DETECTION_THRESHOLD = isDetectOnlyMode()
                ? Math.min(settings.getConfidenceThreshold(), LIVE_AI_MIN_THRESHOLD)
                : settings.getConfidenceThreshold();
        clipStorage.updateDirectory(settings.getOutputFolder());
        
        // Apply screen settings
        if (settings.getKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (screenToggleButton != null) {
            updateScreenToggleButton();
        }
        
        // Update orientation setting
        if (settings.getAllowRotation()) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        } else {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        if (isDetectOnlyMode() && recordingWorker.isRecording()) {
            recordingWorker.stop();
        }

        recreateRecordingWorkerIfNeeded();
    }

    private void recreateRecordingWorkerIfNeeded() {
        if (recordingWorker == null || recordingWorker.isRecording()) {
            return;
        }
        int desiredQuality = settings.getRecordingQuality();
        if (recordingWorker.getSelectedQuality() == desiredQuality) {
            return;
        }
        recordingWorker.close();
        recordingWorker = new RecordingWorker(this, clipStorage, desiredQuality);
        recordingWorker.setAudioEnabled(settings.getAudioEnabled());
        recordingWorker.setListener(recordingListener);
    }
    
    private void restartDetector() {
        // Close current detector
        if (yoloDetector != null) {
            yoloDetector.close();
        }
        
        // Create new detector with updated model preference
        yoloDetector = new YoloDetector(this, settings.getModelType());
        clearOverlayAndTracking();
        updateInfoText();
    }
    
    /** Auto start/stop driven by the motion trigger. */
    private final MotionTriggerWorker.Listener motionTriggerListener =
            new MotionTriggerWorker.Listener() {
        @Override
        public void onShouldStartRecording() {
            if (videoCaptureBound) {
                recordingWorker.start();
            }
        }

        @Override
        public void onShouldStopRecording() {
            recordingWorker.stop();
        }
    };

    /** Manual record override button (tap). Takes control from the auto trigger. */
    private void toggleRecording() {
        if (isDetectOnlyMode()) {
            showToast("Live AI mode selected");
            return;
        }
        if (dashcamService == null) {
            showToast("Dashcam service not ready");
            return;
        }
        if (dashcamService.getMonitoring()) {
            dashcamService.stopMonitoring();
            showToast("Monitoring stopped");
        } else {
            ContextCompat.startForegroundService(this, new Intent(this, DashcamService.class));
            dashcamService.setAnalyzePrevious(isAnalyzePreviousMode());
            dashcamService.startMonitoring(true);
            showToast(isAnalyzePreviousMode()
                    ? "Recording clip + AI log"
                    : "Recording clip now");
        }
    }

    /** Reflects RecordingWorker state in the UI and per-clip logging. */
    private final RecordingWorker.Listener recordingListener = new RecordingWorker.Listener() {
        @Override
        public void onSegmentStarted(String baseName, java.io.File csvFile) {
            if (dataLogger != null) {
                dataLogger.close();
                dataLogger = null;
            }
            if (!isAnalyzePreviousMode()) {
                dataLogger = new DataLogger(csvFile);
            }
            isRecording = true;
            currentRecordingBaseName = baseName;
            recordButton.setText("STOP");
            recordButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.GRAY));
            clipProgressBar.setVisibility(View.VISIBLE);
            clipProgressBar.setVisibility(hudInfoVisible ? View.VISIBLE : View.INVISIBLE);
            clipTimerText.setVisibility(hudInfoVisible ? View.VISIBLE : View.INVISIBLE);
            clipTickHandler.removeCallbacks(clipTickRunnable);
            clipTickHandler.post(clipTickRunnable);
            if (SHOW_REC_PULSE) {
                uiHandler.removeCallbacks(recPulseRunnable);
                uiHandler.post(recPulseRunnable);
            }
            lastProgressBaseName = baseName;
            updateInfoText();
        }

        @Override
        public void onSegmentFinalized(java.io.File videoFile) {
            Log.d(TAG, "Saved clip: " + videoFile.getName());
            clipTimerText.setText("Saving...");
            if (isAnalyzePreviousMode()) {
                String name = videoFile.getName();
                int dot = name.lastIndexOf('.');
                String baseName = dot > 0 ? name.substring(0, dot) : name;
                clipAnalysisWorker.enqueue(videoFile, clipStorage.csvFileFor(baseName));
            }
        }

        @Override
        public void onRecordingStopped() {
            isRecording = false;
            currentRecordingBaseName = null;
            lastProgressBaseName = null;
            if (dataLogger != null) {
                dataLogger.close();
                dataLogger = null;
            }
            recordButton.setText("REC");
            recordButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336")));
            clipTickHandler.removeCallbacks(clipTickRunnable);
            uiHandler.removeCallbacks(recPulseRunnable);
            recordButton.animate().alpha(1.0f).setDuration(200).start();
            clipProgressBar.setProgress(0);
            clipProgressBar.setVisibility(View.INVISIBLE);
            clipTimerText.setText("");
            clipTimerText.setVisibility(View.INVISIBLE);
            showToast("Recording stopped");
            updateInfoText();
        }

        @Override
        public void onError(String message) {
            isRecording = false;
            currentRecordingBaseName = null;
            lastProgressBaseName = null;
            clipTickHandler.removeCallbacks(clipTickRunnable);
            uiHandler.removeCallbacks(recPulseRunnable);
            recordButton.animate().alpha(1.0f).setDuration(200).start();
            clipProgressBar.setVisibility(View.INVISIBLE);
            clipTimerText.setText("");
            clipTimerText.setVisibility(View.INVISIBLE);
            showToast(message);
            updateInfoText();
        }
    };

    private void setupQuickControls() {
        // Audio checkbox
        audioCheckBox.setEnabled(hasAudioPermission());
        audioCheckBox.setChecked(settings.getAudioEnabled() && hasAudioPermission());
        audioCheckBox.setOnCheckedChangeListener((v, checked) -> {
            if (checked && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_CODE_AUDIO_PERMISSION);
                audioCheckBox.setChecked(false);
                return;
            }
            settings.setAudioEnabled(checked);
            recordingWorker.setAudioEnabled(checked);
            if (isRecording) showToast(checked ? "Mic on (next clip)" : "Mic off (next clip)");
        });

        // Screen-on toggle
        updateScreenToggleButton();
        screenToggleButton.setOnClickListener(v -> {
            boolean nowOn = !settings.getKeepScreenOn();
            settings.setKeepScreenOn(nowOn);
            applySettings();
            updateScreenToggleButton();
        });

        updateHudToggleButton();
        hudToggleButton.setOnClickListener(v -> toggleHudVisibility());
    }

    private void updateScreenToggleButton() {
        boolean keepOn = settings.getKeepScreenOn();
        screenToggleButton.setText(keepOn ? "☀ ON" : "◑ Auto");
        screenToggleButton.setBackgroundTintList(ColorStateList.valueOf(
                keepOn ? Color.parseColor("#F57F17") : Color.parseColor("#424242")));
    }

    private void toggleHudVisibility() {
        setHudVisibility(!hudInfoVisible);
    }

    private void setHudVisibility(boolean visible) {
        hudInfoVisible = visible;
        int vis = hudInfoVisible ? View.VISIBLE : View.INVISIBLE;
        infoTextView.setVisibility(vis);
        debugTextView.setVisibility(vis);
        gpsSpeedHud.setVisibility(vis);
        clipProgressBar.setVisibility(hudInfoVisible && isRecording ? View.VISIBLE : View.INVISIBLE);
        clipTimerText.setVisibility(hudInfoVisible && isRecording ? View.VISIBLE : View.INVISIBLE);
        analyzeStatusText.setVisibility(hudInfoVisible ? View.VISIBLE : View.INVISIBLE);
        updateHudToggleButton();
    }

    private void updateHudToggleButton() {
        if (hudToggleButton == null) {
            return;
        }
        hudToggleButton.setText(hudInfoVisible ? "Info ON" : "Info OFF");
        hudToggleButton.setBackgroundTintList(ColorStateList.valueOf(
                hudInfoVisible ? Color.parseColor("#455A64") : Color.parseColor("#263238")));
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermissionIfMissing() {
        if (!hasAudioPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_CODE_AUDIO_PERMISSION);
        }
    }

    private void pulseRecButton() {
        if (!SHOW_REC_PULSE) {
            recordButton.setAlpha(1.0f);
            return;
        }
        if (!isRecording) {
            recordButton.animate().alpha(1.0f).setDuration(200).start();
            return;
        }
        float target = recordButton.getAlpha() > 0.6f ? 0.25f : 1.0f;
        recordButton.animate().alpha(target).setDuration(500).start();
        uiHandler.postDelayed(recPulseRunnable, 700);
    }

    private void tickClipProgress() {
        if (!isRecording) {
            clipProgressBar.setVisibility(View.INVISIBLE);
            clipTimerText.setText("");
            clipTimerText.setVisibility(View.INVISIBLE);
            return;
        }
        // In service mode, read from service; in detect-only mode from local worker.
        int elapsed, total;
        if (serviceBound && dashcamService != null && !isDetectOnlyMode()) {
            String serviceBaseName = dashcamService.getCurrentBaseName();
            if (serviceBaseName != null && !serviceBaseName.equals(lastProgressBaseName)) {
                lastProgressBaseName = serviceBaseName;
                clipProgressBar.setProgress(0);
            }
            elapsed = dashcamService.getElapsedSegmentSeconds();
            total   = dashcamService.getSegmentSeconds();
        } else {
            String localBaseName = recordingWorker.getCurrentBaseName();
            if (localBaseName != null && !localBaseName.equals(lastProgressBaseName)) {
                lastProgressBaseName = localBaseName;
                clipProgressBar.setProgress(0);
            }
            elapsed = recordingWorker.getElapsedSegmentSeconds();
            total   = recordingWorker.getSegmentSeconds();
        }
        if (total > 0) {
            elapsed = Math.min(elapsed, total);
            clipProgressBar.setMax(total);
            clipProgressBar.setProgress(elapsed);
            clipProgressBar.setVisibility(hudInfoVisible ? View.VISIBLE : View.INVISIBLE);
            clipTimerText.setVisibility(hudInfoVisible ? View.VISIBLE : View.INVISIBLE);
            clipTimerText.setText(elapsed >= total
                    ? "Saving..."
                    : String.format(java.util.Locale.US,
                    "%d:%02d / %d:%02d", elapsed / 60, elapsed % 60, total / 60, total % 60));
        }
        clipTickHandler.postDelayed(clipTickRunnable, 1000);
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                         @NonNull String[] permissions,
                                         @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
                sensorManager.startSensors();
            } else {
                showToast("Permissions not granted");
                finish();
            }
        } else if (requestCode == REQUEST_CODE_AUDIO_PERMISSION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                audioCheckBox.setEnabled(true);
                audioCheckBox.setChecked(true);
                settings.setAudioEnabled(true);
                recordingWorker.setAudioEnabled(true);
                showToast("Mic enabled — takes effect on next clip");
            } else {
                audioCheckBox.setEnabled(false);
                settings.setAudioEnabled(false);
                recordingWorker.setAudioEnabled(false);
                showToast("Microphone permission denied");
            }
        }
    }

    private void styleSettingsDialog(View view) {
        view.setBackgroundColor(Color.WHITE);
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(Color.BLACK);
        }
        if (view instanceof EditText) {
            ((EditText) view).setTextColor(Color.BLACK);
            ((EditText) view).setHintTextColor(Color.DKGRAY);
        }
        if (view instanceof CheckBox) {
            ((CheckBox) view).setTextColor(Color.BLACK);
        }
        if (view instanceof android.widget.RadioButton) {
            ((android.widget.RadioButton) view).setTextColor(Color.BLACK);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleSettingsDialog(group.getChildAt(i));
            }
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            Log.d(TAG, "Touch down x=" + ev.getX() + " y=" + ev.getY());
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (motionTrigger != null) {
            motionTrigger.stop();
        }
        uiHandler.removeCallbacks(motionLabelCommit);
        uiHandler.removeCallbacks(recPulseRunnable);
        clipTickHandler.removeCallbacks(clipTickRunnable);
        if (recordingWorker != null) {
            recordingWorker.close();
        }
        if (clipAnalysisWorker != null) {
            clipAnalysisWorker.close();
        }
        if (overlayBitmap != null) {
            overlayBitmap.recycle();
            overlayBitmap = null;
            overlayCanvas = null;
        }
        yoloDetector.close();
        if (dataLogger != null) {
            dataLogger.close();
        }
        sensorManager.stopSensors();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.stopSensors();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (allPermissionsGranted()) {
            sensorManager.startSensors();
            if (isDetectOnlyMode() && !analysisBound && !detectCameraStarting) {
                startCamera();
            } else if (dashcamService != null) {
                previewView.post(() -> {
                    if (dashcamService == null) {
                        return;
                    }
                    // Re-show the aiming preview when returning while idle.
                    // While monitoring this is a no-op (headless, untouched).
                    if (!dashcamService.getMonitoring()) {
                        dashcamService.setPreviewSurface(previewView.getSurfaceProvider());
                    }
                });
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, DashcamService.class), dashcamConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isDetectOnlyMode()) {
            stopDetectCamera();
        }
        if (dashcamService != null) {
            dashcamService.setListener(null);
        }
        if (serviceBound) {
            unbindService(dashcamConnection);
            serviceBound = false;
        }
        if (exitingApp) {
            stopService(new Intent(this, DashcamService.class));
        }
    }
}
