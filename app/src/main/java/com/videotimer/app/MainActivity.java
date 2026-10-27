package com.videotimer.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One screen: live camera preview, a stopwatch, and three controls.
 *  - "Video" starts/stops recording only.
 *  - "Timer" starts/stops the stopwatch only.
 *  - "Both" starts both if neither is running, otherwise stops whatever is running.
 * Videos are saved to the phone's Gallery under Movies/VideoTimer.
 */
public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST = 1;

    // UI
    private TextureView preview;
    private TextView timerText;
    private TextView recLabel;
    private Button videoButton;
    private Button timerButton;
    private Button bothButton;

    // Stopwatch state (main thread only)
    private boolean timerRunning = false;
    private long timerStartedAt = 0;   // SystemClock.elapsedRealtime() when last started
    private long timerAccumulated = 0; // ms counted before the last start

    // Recording state (main thread only)
    private boolean recording = false;
    private boolean recorderBusy = false; // true while starting or stopping
    private long recordStartedAt = 0;

    // Camera (used on the camera thread)
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private ParcelFileDescriptor videoFd;
    private Uri videoUri;
    private String cameraId;
    private int sensorOrientation = 90;
    private Size videoSize;
    private Size previewSize;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OrientationEventListener orientationListener;
    private volatile int deviceOrientation = 0;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateTimerText();
            updateRecLabel();
            if (timerRunning || recording) {
                mainHandler.postDelayed(this, 30);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preview = findViewById(R.id.preview);
        timerText = findViewById(R.id.timerText);
        recLabel = findViewById(R.id.recLabel);
        videoButton = findViewById(R.id.videoButton);
        timerButton = findViewById(R.id.timerButton);
        bothButton = findViewById(R.id.bothButton);
        Button resetButton = findViewById(R.id.resetButton);

        videoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) stopRecording(); else startRecording();
            }
        });
        timerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (timerRunning) stopTimer(); else startTimer();
            }
        });
        bothButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording || timerRunning) {
                    if (recording) stopRecording();
                    if (timerRunning) stopTimer();
                } else {
                    startRecording();
                    startTimer();
                }
            }
        });
        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                timerAccumulated = 0;
                timerStartedAt = SystemClock.elapsedRealtime();
                updateTimerText();
            }
        });

        // Track how the phone is held so videos come out the right way up,
        // even though the screen itself stays in portrait.
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                if (degrees == ORIENTATION_UNKNOWN) return;
                deviceOrientation = ((degrees + 45) / 90 * 90) % 360;
            }
        };

        refreshButtons();
        updateTimerText();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        startCameraThread();
        if (!hasPermissions()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST);
            return;
        }
        openCameraWhenReady();
        if (timerRunning) mainHandler.post(ticker);
    }

    @Override
    protected void onPause() {
        // Leaving the app ends any recording in progress and saves it.
        // The timer keeps counting in the background.
        if (recording) stopRecording();
        orientationListener.disable();
        mainHandler.removeCallbacks(ticker);
        if (cameraHandler != null) {
            cameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    closeCamera();
                }
            });
        }
        stopCameraThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != PERMISSION_REQUEST) return;
        if (hasPermissions()) {
            openCameraWhenReady();
        } else {
            recLabel.setText("Camera & microphone permission needed");
            Toast.makeText(this, "Please allow camera and microphone in Settings > Apps > Video Timer",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- Timer

    private long timerElapsed() {
        long ms = timerAccumulated;
        if (timerRunning) ms += SystemClock.elapsedRealtime() - timerStartedAt;
        return ms;
    }

    private void startTimer() {
        if (timerRunning) return;
        timerStartedAt = SystemClock.elapsedRealtime();
        timerRunning = true;
        mainHandler.removeCallbacks(ticker);
        mainHandler.post(ticker);
        refreshButtons();
    }

    private void stopTimer() {
        if (!timerRunning) return;
        timerAccumulated = timerElapsed();
        timerRunning = false;
        updateTimerText();
        refreshButtons();
    }

    private void updateTimerText() {
        timerText.setText(formatTime(timerElapsed(), true));
    }

    private void updateRecLabel() {
        if (recording) {
            recLabel.setText("● REC  " + formatTime(SystemClock.elapsedRealtime() - recordStartedAt, false));
            recLabel.setTextColor(0xFFFF5252);
        }
    }

    private static String formatTime(long ms, boolean hundredths) {
        long totalSeconds = ms / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds / 60) % 60;
        long s = totalSeconds % 60;
        String base = h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%02d:%02d", m, s);
        return hundredths ? base + String.format(Locale.US, ".%02d", (ms % 1000) / 10) : base;
    }

    // ---------------------------------------------------------------- Buttons

    private void refreshButtons() {
        videoButton.setText(recording ? "Stop\nVideo" : "Start\nVideo");
        timerButton.setText(timerRunning ? "Stop\nTimer" : "Start\nTimer");
        boolean anyRunning = recording || timerRunning;
        bothButton.setText(anyRunning ? "Stop Both" : "Start Both");
        bothButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                anyRunning ? 0xFFE65100 : 0xFF2E7D32));
        videoButton.setEnabled(!recorderBusy);
        bothButton.setEnabled(!recorderBusy);
        if (!recording && !recorderBusy) {
            recLabel.setTextColor(0xFFFFFFFF);
            recLabel.setText(camera != null ? "Camera ready" : "Starting camera…");
        }
    }

    // ---------------------------------------------------------------- Camera setup

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException ignored) {
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void openCameraWhenReady() {
        if (preview.isAvailable()) {
            openCamera();
        } else {
            preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            });
        }
    }

    private void openCamera() {
        final CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            chooseCamera(manager);
        } catch (CameraAccessException e) {
            showError("Could not access camera");
            return;
        }
        fitPreviewToAspect();
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                if (camera != null) return;
                try {
                    manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(CameraDevice cd) {
                            camera = cd;
                            startPreviewSession();
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    refreshButtons();
                                }
                            });
                        }

                        @Override
                        public void onDisconnected(CameraDevice cd) {
                            cd.close();
                            camera = null;
                        }

                        @Override
                        public void onError(CameraDevice cd, int error) {
                            cd.close();
                            camera = null;
                            showError("Camera error (" + error + ")");
                        }
                    }, cameraHandler);
                } catch (CameraAccessException | SecurityException e) {
                    showError("Could not open camera");
                }
            }
        });
    }

    /** Picks the main back camera and sizes for video (1080p if possible) and preview. */
    private void chooseCamera(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        cameraId = ids[0];
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = id;
                break;
            }
        }
        CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = so != null ? so : 90;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        videoSize = pickVideoSize(map.getOutputSizes(MediaRecorder.class));
        previewSize = pickPreviewSize(map.getOutputSizes(SurfaceTexture.class), videoSize);
    }

    private static Size pickVideoSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() == 1920 && s.getHeight() == 1080) return s;
        }
        for (Size s : sizes) {
            boolean wide = s.getWidth() * 9 == s.getHeight() * 16;
            if (wide && s.getWidth() <= 1920 && (best == null || s.getWidth() > best.getWidth())) best = s;
        }
        return best != null ? best : sizes[sizes.length - 1];
    }

    private static Size pickPreviewSize(Size[] sizes, Size aspect) {
        Size best = null;
        for (Size s : sizes) {
            boolean sameAspect = (long) s.getWidth() * aspect.getHeight() == (long) s.getHeight() * aspect.getWidth();
            if (sameAspect && s.getWidth() <= 1920 && (best == null || s.getWidth() > best.getWidth())) best = s;
        }
        return best != null ? best : aspect;
    }

    /** Resizes the preview so the picture isn't stretched (screen is portrait, sensor is landscape). */
    private void fitPreviewToAspect() {
        final View root = findViewById(R.id.root);
        root.post(new Runnable() {
            @Override
            public void run() {
                int viewW = root.getWidth();
                int viewH = viewW * previewSize.getWidth() / previewSize.getHeight();
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) preview.getLayoutParams();
                lp.width = viewW;
                lp.height = viewH;
                preview.setLayoutParams(lp);
            }
        });
    }

    /** Camera thread. Shows the live picture without recording. */
    private void startPreviewSession() {
        if (camera == null) return;
        closeSession();
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            final Surface previewSurface = new Surface(st);
            final CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            camera.createCaptureSession(Arrays.asList(previewSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (camera == null) return;
                    session = s;
                    try {
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        s.setRepeatingRequest(builder.build(), null, cameraHandler);
                    } catch (CameraAccessException | IllegalStateException ignored) {
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    showError("Could not start camera preview");
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            showError("Could not start camera preview");
        }
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    /** Camera thread. */
    private void closeCamera() {
        closeSession();
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (recorder != null) {
            boolean ok = true;
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                ok = false;
            }
            recorder.release();
            recorder = null;
            finishVideoFile(ok);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    recording = false;
                    recorderBusy = false;
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    refreshButtons();
                }
            });
        }
    }

    // ---------------------------------------------------------------- Recording

    private void startRecording() {
        if (recording || recorderBusy || cameraHandler == null) return;
        if (camera == null) {
            Toast.makeText(this, "Camera isn't ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        recorderBusy = true;
        refreshButtons();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final int orientationHint = (sensorOrientation + deviceOrientation) % 360;
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                beginRecordingSession(orientationHint);
            }
        });
    }

    /** Camera thread. */
    private void beginRecordingSession(int orientationHint) {
        try {
            createVideoFile();
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(videoFd.getFileDescriptor());
            recorder.setVideoEncodingBitRate(12_000_000);
            recorder.setVideoFrameRate(30);
            recorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioSamplingRate(44_100);
            recorder.setOrientationHint(orientationHint);
            recorder.prepare();

            closeSession();
            SurfaceTexture st = preview.getSurfaceTexture();
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(st);
            Surface recorderSurface = recorder.getSurface();
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(recorderSurface);

            final CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recorderSurface);

            camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        s.setRepeatingRequest(builder.build(), null, cameraHandler);
                        recorder.start();
                    } catch (Exception e) {
                        failRecording("Could not start recording");
                        return;
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            recording = true;
                            recorderBusy = false;
                            recordStartedAt = SystemClock.elapsedRealtime();
                            mainHandler.removeCallbacks(ticker);
                            mainHandler.post(ticker);
                            refreshButtons();
                        }
                    });
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    failRecording("Could not start recording");
                }
            }, cameraHandler);
        } catch (Exception e) {
            failRecording("Could not start recording");
        }
    }

    /** Camera thread. Cleans up after a failed start and returns to preview. */
    private void failRecording(String message) {
        if (recorder != null) {
            recorder.reset();
            recorder.release();
            recorder = null;
        }
        finishVideoFile(false);
        startPreviewSession();
        showError(message);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                recording = false;
                recorderBusy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                refreshButtons();
            }
        });
    }

    private void stopRecording() {
        if (!recording || recorderBusy) return;
        recording = false;
        recorderBusy = true;
        refreshButtons();
        recLabel.setText("Saving…");
        Runnable stop = new Runnable() {
            @Override
            public void run() {
                boolean ok = true;
                try {
                    if (session != null) session.stopRepeating();
                } catch (Exception ignored) {
                }
                try {
                    recorder.stop();
                } catch (RuntimeException e) {
                    ok = false; // happens if the clip was too short to contain any frames
                }
                recorder.reset();
                recorder.release();
                recorder = null;
                finishVideoFile(ok);
                startPreviewSession();
                final boolean saved = ok;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        recorderBusy = false;
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        refreshButtons();
                        Toast.makeText(MainActivity.this,
                                saved ? "Video saved to Gallery (Movies/VideoTimer)" : "Clip too short, not saved",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        };
        if (isFinishing() || cameraHandler == null) {
            stop.run();
        } else {
            cameraHandler.post(stop);
        }
    }

    // ---------------------------------------------------------------- Saving to Gallery

    /** Creates a new, hidden entry in the Gallery that we record into. */
    private void createVideoFile() throws Exception {
        String name = "VideoTimer_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoTimer");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        ContentResolver resolver = getContentResolver();
        videoUri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (videoUri == null) throw new IllegalStateException("No storage");
        videoFd = resolver.openFileDescriptor(videoUri, "rw");
    }

    /** Makes the recorded video visible in the Gallery, or deletes it if recording failed. */
    private void finishVideoFile(boolean keep) {
        try {
            if (videoFd != null) videoFd.close();
        } catch (Exception ignored) {
        }
        videoFd = null;
        if (videoUri == null) return;
        ContentResolver resolver = getContentResolver();
        if (keep) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(videoUri, values, null, null);
        } else {
            resolver.delete(videoUri, null, null);
        }
        videoUri = null;
    }

    private void showError(final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }
}
