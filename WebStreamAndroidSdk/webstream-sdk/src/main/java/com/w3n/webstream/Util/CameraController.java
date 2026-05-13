package com.w3n.webstream.Util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import java.util.Collections;

public final class CameraController {

    public enum CameraFacing {
        FRONT,
        BACK
    }

    public enum FrameType {
        IMAGE_READER_YUV,
        IMAGE_READER_NV21,
        IMAGE_READER_NV12,
        BITMAP
    }

    public enum YuvFormat {
        I420,
        NV21,
        NV12
    }

    private static final String NATIVE_LIBRARY_NAME = "webstream_h264";
    private static final boolean NATIVE_LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary(NATIVE_LIBRARY_NAME);
            loaded = true;
        } catch (UnsatisfiedLinkError ignored) {
            loaded = false;
        }
        NATIVE_LIBRARY_LOADED = loaded;
    }

    public interface CameraCallback {
        void onImageFrameAvailable(CameraFrame frame);

        default void onCameraStarted() {}

        default void onCameraStopped() {}

        default void onCameraError(Exception error) {}
    }

    public static final class Config {
        public final int width;
        public final int height;
        public final int frameRateFps;
        public final FrameType frameType;
        public final CameraFacing cameraFacing;
        public final int imageReaderMaxImages;
        public final int jpegQuality;

        private Config(Builder builder) {
            this.width = builder.width;
            this.height = builder.height;
            this.frameRateFps = builder.frameRateFps;
            this.frameType = builder.frameType;
            this.cameraFacing = builder.cameraFacing;
            this.imageReaderMaxImages = builder.imageReaderMaxImages;
            this.jpegQuality = builder.jpegQuality;
        }

        public static final class Builder {
            private int width = 640;
            private int height = 480;
            private int frameRateFps = 30;
            private FrameType frameType = FrameType.IMAGE_READER_YUV;
            private CameraFacing cameraFacing = CameraFacing.FRONT;
            private int imageReaderMaxImages = 8;
            private int jpegQuality = 80;

            public Builder setSize(int width, int height) {
                this.width = width;
                this.height = height;
                return this;
            }

            public Builder setFrameRateFps(int frameRateFps) {
                this.frameRateFps = Math.max(1, frameRateFps);
                return this;
            }

            public Builder setFrameType(FrameType frameType) {
                this.frameType = frameType == null
                        ? FrameType.IMAGE_READER_YUV
                        : frameType;
                return this;
            }

            public Builder setCameraFacing(CameraFacing cameraFacing) {
                this.cameraFacing = cameraFacing == null
                        ? CameraFacing.FRONT
                        : cameraFacing;
                return this;
            }

            public Builder setImageReaderMaxImages(int imageReaderMaxImages) {
                this.imageReaderMaxImages = Math.max(2, imageReaderMaxImages);
                return this;
            }

            public Builder setJpegQuality(int jpegQuality) {
                this.jpegQuality = Math.max(1, Math.min(100, jpegQuality));
                return this;
            }

            public Config build() {
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("Camera width and height must be positive.");
                }
                return new Config(this);
            }
        }
    }

    public static final class CameraFrame {
        public final int width;
        public final int height;
        public final long timestampNs;
        public final long sequence;
        public final FrameType frameType;
        public final YuvFormat yuvFormat;

        /**
         * Available when frameType is an image-reader YUV mode.
         * This is copied data, so it is safe to use after Image.close().
         */
        public final byte[] yuv420Data;

        /**
         * Available when frameType == BITMAP.
         */
        public final Bitmap bitmap;

        private CameraFrame(
                int width,
                int height,
                long timestampNs,
                long sequence,
                FrameType frameType,
                YuvFormat yuvFormat,
                byte[] yuv420Data,
                Bitmap bitmap) {
            this.width = width;
            this.height = height;
            this.timestampNs = timestampNs;
            this.sequence = sequence;
            this.frameType = frameType;
            this.yuvFormat = yuvFormat;
            this.yuv420Data = yuv420Data;
            this.bitmap = bitmap;
        }

        static CameraFrame yuv(
                int width,
                int height,
                long timestampNs,
                long sequence,
                FrameType frameType,
                YuvFormat yuvFormat,
                byte[] yuv420Data) {
            return new CameraFrame(
                    width,
                    height,
                    timestampNs,
                    sequence,
                    frameType,
                    yuvFormat,
                    yuv420Data,
                    null);
        }

        static CameraFrame bitmap(
                int width,
                int height,
                long timestampNs,
                long sequence,
                Bitmap bitmap) {
            return new CameraFrame(
                    width,
                    height,
                    timestampNs,
                    sequence,
                    FrameType.BITMAP,
                    null,
                    null,
                    bitmap);
        }

        @Override
        public String toString() {
            return "CameraFrame{" +
                    "width=" + width +
                    ", height=" + height +
                    ", timestampNs=" + timestampNs +
                    ", sequence=" + sequence +
                    ", frameType=" + frameType +
                    ", yuvFormat=" + yuvFormat +
                    ", yuv420Data=" + (yuv420Data != null ? yuv420Data.length + " bytes" : "null") +
                    ", bitmap=" + (bitmap != null
                    ? bitmap.getWidth() + "x" + bitmap.getHeight()
                    : "null") +
                    '}';
        }
    }

    private static final String TAG = "CameraController";

    private final Context applicationContext;
    private final Config config;
    private final CameraCallback callback;

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;

    private String cameraId;
    private long sequence;
    private boolean initialized;
    private boolean started;
    private boolean released;

    public CameraController(
            Context context,
            Config config,
            CameraCallback callback) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null.");
        }
        if (config == null) {
            throw new IllegalArgumentException("CameraController.Config cannot be null.");
        }
        if (callback == null) {
            throw new IllegalArgumentException("CameraCallback cannot be null.");
        }

        this.applicationContext = context.getApplicationContext();
        this.config = config;
        this.callback = callback;
    }

    /**
     * 1. Initialization
     *
     * Finds camera ID and creates ImageReader, but does not open camera yet.
     */
    public synchronized void initialize() {
        if (released) {
            throw new IllegalStateException("CameraController is already released.");
        }
        if (initialized) {
            return;
        }

        try {
            CameraManager cameraManager =
                    (CameraManager) applicationContext.getSystemService(Context.CAMERA_SERVICE);

            cameraId = findCameraId(cameraManager, config.cameraFacing);
            if (cameraId == null) {
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
            }

            imageReader = ImageReader.newInstance(
                    config.width,
                    config.height,
                    ImageFormat.YUV_420_888,
                    config.imageReaderMaxImages);

            imageReader.setOnImageAvailableListener(
                    this::onImageAvailable,
                    getCameraHandler());

            initialized = true;
        } catch (Exception error) {
            callback.onCameraError(error);
        }
    }

    /**
     * 2. Start
     *
     * Opens camera and starts repeating capture request.
     */
    public synchronized void start() {
        if (released) {
            callback.onCameraError(
                    new IllegalStateException("CameraController is already released."));
            return;
        }

        if (started) {
            return;
        }

        if (!initialized) {
            initialize();
        }

        if (applicationContext.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onCameraError(
                    new SecurityException("CAMERA permission is missing."));
            return;
        }

        try {
            CameraManager cameraManager =
                    (CameraManager) applicationContext.getSystemService(Context.CAMERA_SERVICE);

            cameraManager.openCamera(
                    cameraId,
                    cameraStateCallback,
                    getCameraHandler());

            started = true;
        } catch (Exception error) {
            started = false;
            callback.onCameraError(error);
        }
    }

    /**
     * 3. Stop
     *
     * Stops repeating request and closes camera, but keeps controller reusable.
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }

        try {
            if (captureSession != null) {
                try {
                    captureSession.stopRepeating();
                } catch (Exception ignored) {
                }

                try {
                    captureSession.abortCaptures();
                } catch (Exception ignored) {
                }

                captureSession.close();
                captureSession = null;
            }

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } finally {
            started = false;
            callback.onCameraStopped();
        }
    }

    /**
     * 4. Release
     *
     * Fully destroys camera resources.
     */
    public synchronized void release() {
        if (released) {
            return;
        }

        stop();

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        stopCameraThread();

        released = true;
        initialized = false;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized boolean isReleased() {
        return released;
    }

    private final CameraDevice.StateCallback cameraStateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    started = false;
                    callback.onCameraStopped();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    started = false;
                    callback.onCameraError(
                            new RuntimeException("CameraDevice error code: " + error));
                }
            };

    private void createCaptureSession() {
        if (cameraDevice == null || imageReader == null) {
            return;
        }

        try {
            Surface imageSurface = imageReader.getSurface();

            cameraDevice.createCaptureSession(
                    Collections.singletonList(imageSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            startRepeatingRequest(session, imageSurface);
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            callback.onCameraError(
                                    new RuntimeException("Camera capture session configuration failed."));
                        }
                    },
                    getCameraHandler());
        } catch (Exception error) {
            callback.onCameraError(error);
        }
    }

    private void startRepeatingRequest(
            CameraCaptureSession session,
            Surface imageSurface) {
        try {
            CaptureRequest.Builder requestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            requestBuilder.addTarget(imageSurface);

            requestBuilder.set(
                    CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_AUTO);

            requestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            requestBuilder.set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    chooseFpsRange());

            session.setRepeatingRequest(
                    requestBuilder.build(),
                    null,
                    getCameraHandler());

            callback.onCameraStarted();
        } catch (Exception error) {
            callback.onCameraError(error);
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;

        try {
            while ((image = reader.acquireNextImage()) != null) {
                long st = System.currentTimeMillis();
                processImage(image);
                Log.d(TAG, "onImageAvailable: time taken :" + (System.currentTimeMillis()-st));
                image.close();
                image = null;
            }

        } catch (Exception error) {
            callback.onCameraError(error);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private void processImage(Image image) {
        if (!NATIVE_LIBRARY_LOADED) {
            callback.onCameraError(new IllegalStateException(
                    "Native camera frame processor " + NATIVE_LIBRARY_NAME + " could not be loaded."));
            return;
        }

        CameraFrame frame = nativeProcessImage(
                image,
                config.frameType,
                ++sequence,
                config.jpegQuality);

        if (frame != null) {
            callback.onImageFrameAvailable(frame);
        }
    }

    private String findCameraId(
            CameraManager cameraManager,
            CameraFacing facing) throws CameraAccessException {
        int targetFacing = facing == CameraFacing.FRONT
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;

        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(id);

            Integer lensFacing =
                    characteristics.get(CameraCharacteristics.LENS_FACING);

            if (lensFacing != null && lensFacing == targetFacing) {
                return id;
            }
        }

        String[] cameraIds = cameraManager.getCameraIdList();
        return cameraIds.length > 0 ? cameraIds[0] : null;
    }

    private Range<Integer> chooseFpsRange() {
        try {
            CameraManager cameraManager =
                    (CameraManager) applicationContext.getSystemService(Context.CAMERA_SERVICE);

            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);

            Range<Integer>[] ranges =
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            if (ranges == null || ranges.length == 0) {
                return new Range<>(config.frameRateFps, config.frameRateFps);
            }

            int requested = config.frameRateFps;
            Range<Integer> bestRange = null;

            for (Range<Integer> range : ranges) {
                if (range.getLower() == requested && range.getUpper() == requested) {
                    return range;
                }

                if (range.getLower() <= requested && range.getUpper() >= requested) {
                    if (bestRange == null || isBetterFpsRange(range, bestRange, requested)) {
                        bestRange = range;
                    }
                }
            }

            return bestRange != null ? bestRange : ranges[0];
        } catch (Exception ignored) {
            return new Range<>(config.frameRateFps, config.frameRateFps);
        }
    }

    private boolean isBetterFpsRange(
            Range<Integer> candidate,
            Range<Integer> current,
            int requested) {
        int candidateUpperDistance = Math.abs(candidate.getUpper() - requested);
        int currentUpperDistance = Math.abs(current.getUpper() - requested);

        if (candidateUpperDistance != currentUpperDistance) {
            return candidateUpperDistance < currentUpperDistance;
        }

        int candidateLowerDistance = Math.abs(candidate.getLower() - requested);
        int currentLowerDistance = Math.abs(current.getLower() - requested);

        return candidateLowerDistance < currentLowerDistance;
    }

    private Handler getCameraHandler() {
        if (cameraThread == null) {
            cameraThread = new HandlerThread("camera-controller-thread");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
        return cameraHandler;
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();

            try {
                cameraThread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            cameraThread = null;
            cameraHandler = null;
        }
    }

    private static native CameraFrame nativeProcessImage(
            Image image,
            FrameType frameType,
            long sequence,
            int jpegQuality);
}
