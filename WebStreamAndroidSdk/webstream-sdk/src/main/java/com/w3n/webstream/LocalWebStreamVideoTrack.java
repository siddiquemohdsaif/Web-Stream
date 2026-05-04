package com.w3n.webstream;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;

import java.io.ByteArrayOutputStream;
import java.util.Collections;

final class LocalWebStreamVideoTrack implements WebStreamVideoTrack {
    interface FrameListener {
        void onFrame(byte[] jpegData, int width, int height, long timestampMs, long sequence);
    }

    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;

    private final Context applicationContext;
    private final String participantId;
    private final WebStreamCallOptions options;
    private final Runnable relayFrameRunnable = this::captureRelayFrame;

    private WebStreamVideoView attachedView;
    private TextureView textureView;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private HandlerThread frameThread;
    private Handler frameHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private FrameListener frameListener;
    private long frameSequence;
    private boolean enabled = true;
    private boolean relayingFrames;
    private boolean released;
    private boolean useFrontCamera = true;

    LocalWebStreamVideoTrack(
            Context applicationContext,
            String participantId,
            WebStreamCallOptions options) {
        this.applicationContext = applicationContext.getApplicationContext();
        this.participantId = participantId;
        this.options = options == null ? WebStreamCallOptions.defaultOptions() : options;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public String getParticipantId() {
        return participantId;
    }

    @Override
    public void attach(WebStreamVideoView view) {
        if (released) {
            return;
        }
        Log.d(SdkConstants.TAG, "Attaching local video track. participantId=" + participantId);
        detach(attachedView);
        attachedView = view;
        textureView = new TextureView(view.getContext());
        textureView.setSurfaceTextureListener(surfaceTextureListener);
        view.addView(textureView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (textureView.isAvailable()) {
            startCamera(textureView.getSurfaceTexture());
        }
        startFrameRelay();
    }

    @Override
    public void detach(WebStreamVideoView view) {
        if (view == null || view != attachedView) {
            return;
        }
        Log.d(SdkConstants.TAG, "Detaching local video track. participantId=" + participantId);
        stopFrameRelay();
        stopCamera();
        if (textureView != null) {
            view.removeView(textureView);
            textureView.setSurfaceTextureListener(null);
            textureView = null;
        }
        attachedView = null;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.d(SdkConstants.TAG, "Local video track enabled=" + enabled);
        if (!enabled) {
            stopFrameRelay();
            stopCamera();
            return;
        }
        if (textureView != null && textureView.isAvailable()) {
            startCamera(textureView.getSurfaceTexture());
        }
        startFrameRelay();
    }

    void switchCamera() {
        if (released) {
            return;
        }
        useFrontCamera = !useFrontCamera;
        Log.d(SdkConstants.TAG, "Switching camera. useFrontCamera=" + useFrontCamera);
        stopCamera();
        if (enabled && textureView != null && textureView.isAvailable()) {
            startCamera(textureView.getSurfaceTexture());
        }
    }

    void release() {
        released = true;
        Log.d(SdkConstants.TAG, "Releasing local video track. participantId=" + participantId);
        detach(attachedView);
    }

    void setFrameListener(FrameListener frameListener) {
        this.frameListener = frameListener;
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    startCamera(surface);
                    startFrameRelay();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    stopFrameRelay();
                    stopCamera();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

    private void startCamera(SurfaceTexture surfaceTexture) {
        if (!enabled || released || surfaceTexture == null || cameraDevice != null) {
            return;
        }
        if (applicationContext.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(SdkConstants.TAG, "Camera start skipped; permission missing.");
            return;
        }

        startCameraThread();
        try {
            CameraManager cameraManager =
                    (CameraManager) applicationContext.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = findCameraId(cameraManager, useFrontCamera);
            if (cameraId == null) {
                Log.d(SdkConstants.TAG, "Camera start skipped; no camera found.");
                return;
            }
            Log.d(SdkConstants.TAG, "Opening camera. cameraId=" + cameraId);
            surfaceTexture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
            cameraManager.openCamera(cameraId, cameraStateCallback(surfaceTexture), cameraHandler);
        } catch (CameraAccessException | SecurityException ignored) {
            stopCamera();
        }
    }

    private CameraDevice.StateCallback cameraStateCallback(SurfaceTexture surfaceTexture) {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                Log.d(SdkConstants.TAG, "Camera opened.");
                cameraDevice = camera;
                startPreview(surfaceTexture);
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                Log.d(SdkConstants.TAG, "Camera disconnected.");
                camera.close();
                cameraDevice = null;
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                Log.d(SdkConstants.TAG, "Camera error=" + error);
                camera.close();
                cameraDevice = null;
            }
        };
    }

    private void startPreview(SurfaceTexture surfaceTexture) {
        if (cameraDevice == null || surfaceTexture == null) {
            return;
        }
        Surface surface = new Surface(surfaceTexture);
        try {
            CaptureRequest.Builder requestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(
                    Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            Log.d(SdkConstants.TAG, "Camera preview configured.");
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                            } catch (CameraAccessException ignored) {
                                stopCamera();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.d(SdkConstants.TAG, "Camera preview configuration failed.");
                            stopCamera();
                        }
                    },
                    cameraHandler);
        } catch (CameraAccessException ignored) {
            stopCamera();
        }
    }

    private String findCameraId(CameraManager cameraManager, boolean front) throws CameraAccessException {
        int targetFacing = front
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        for (String cameraId : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == targetFacing) {
                return cameraId;
            }
        }
        String[] cameraIds = cameraManager.getCameraIdList();
        return cameraIds.length > 0 ? cameraIds[0] : null;
    }

    private void startCameraThread() {
        if (cameraThread != null) {
            return;
        }
        Log.d(SdkConstants.TAG, "Starting camera thread.");
        cameraThread = new HandlerThread("webstream-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCamera() {
        if (captureSession != null) {
            Log.d(SdkConstants.TAG, "Closing camera capture session.");
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            Log.d(SdkConstants.TAG, "Closing camera device.");
            cameraDevice.close();
            cameraDevice = null;
        }
        if (cameraThread != null) {
            Log.d(SdkConstants.TAG, "Stopping camera thread.");
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void startFrameRelay() {
        if (relayingFrames || !enabled || released || textureView == null) {
            return;
        }
        relayingFrames = true;
        startFrameThread();
        textureView.postDelayed(relayFrameRunnable, getRelayFrameIntervalMs());
    }

    private void stopFrameRelay() {
        relayingFrames = false;
        if (textureView != null) {
            textureView.removeCallbacks(relayFrameRunnable);
        }
        if (frameThread != null) {
            Log.d(SdkConstants.TAG, "Stopping frame relay thread.");
            frameThread.quitSafely();
            frameThread = null;
            frameHandler = null;
        }
    }

    private void captureRelayFrame() {
        if (!relayingFrames || !enabled || released || textureView == null) {
            return;
        }
        if (textureView.isAvailable() && frameHandler != null && frameListener != null) {
            Bitmap bitmap = textureView.getBitmap(options.getVideoWidth(), options.getVideoHeight());
            if (bitmap != null) {
                long timestampMs = System.currentTimeMillis();
                long sequence = ++frameSequence;
                frameHandler.post(() -> encodeAndDispatchFrame(bitmap, timestampMs, sequence));
            }
        }
        if (textureView != null) {
            textureView.postDelayed(relayFrameRunnable, getRelayFrameIntervalMs());
        }
    }

    private void encodeAndDispatchFrame(Bitmap bitmap, long timestampMs, long sequence) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, options.getJpegQuality(), outputStream);
            FrameListener listener = frameListener;
            if (listener != null && !released && enabled) {
                listener.onFrame(
                        outputStream.toByteArray(),
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        timestampMs,
                        sequence);
            }
        } finally {
            bitmap.recycle();
        }
    }

    private void startFrameThread() {
        if (frameThread != null) {
            return;
        }
        Log.d(SdkConstants.TAG, "Starting frame relay thread.");
        frameThread = new HandlerThread("webstream-frame-relay");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
    }

    private long getRelayFrameIntervalMs() {
        return Math.max(33L, 1000L / Math.max(1, options.getFrameRateFps()));
    }
}
