package com.w3n.webstreamandroidsdk;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
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
import android.util.Range;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

public final class CameraController {

    public enum CameraFacing {
        FRONT,
        BACK
    }

    public enum FrameType {
        IMAGE_READER_YUV,
        BITMAP
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

        /**
         * Available when frameType == IMAGE_READER_YUV.
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
                byte[] yuv420Data,
                Bitmap bitmap) {
            this.width = width;
            this.height = height;
            this.timestampNs = timestampNs;
            this.sequence = sequence;
            this.frameType = frameType;
            this.yuv420Data = yuv420Data;
            this.bitmap = bitmap;
        }

        static CameraFrame yuv(
                int width,
                int height,
                long timestampNs,
                long sequence,
                byte[] yuv420Data) {
            return new CameraFrame(
                    width,
                    height,
                    timestampNs,
                    sequence,
                    FrameType.IMAGE_READER_YUV,
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
                    bitmap);
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
                processImage(image);
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
        long currentSequence = ++sequence;
        long timestampNs = image.getTimestamp();

        if (config.frameType == FrameType.IMAGE_READER_YUV) {
            byte[] yuvData = imageToNv21(image);

            CameraFrame frame = CameraFrame.yuv(
                    image.getWidth(),
                    image.getHeight(),
                    timestampNs,
                    currentSequence,
                    yuvData);

            callback.onImageFrameAvailable(frame);
            return;
        }

        if (config.frameType == FrameType.BITMAP) {
            byte[] nv21 = imageToNv21(image);
            Bitmap bitmap = nv21ToBitmap(
                    nv21,
                    image.getWidth(),
                    image.getHeight(),
                    config.jpegQuality);

            CameraFrame frame = CameraFrame.bitmap(
                    image.getWidth(),
                    image.getHeight(),
                    timestampNs,
                    currentSequence,
                    bitmap);

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

    /**
     * Converts Android ImageFormat.YUV_420_888 into NV21.
     *
     * NV21 layout:
     *
     * Y plane first, then interleaved VU.
     */
    private static byte[] imageToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();

        Image.Plane[] planes = image.getPlanes();

        byte[] nv21 = new byte[width * height * 3 / 2];

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();

        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        int position = 0;

        for (int row = 0; row < height; row++) {
            int yRowStart = row * yRowStride;

            for (int col = 0; col < width; col++) {
                nv21[position++] =
                        yBuffer.get(yRowStart + col * yPixelStride);
            }
        }

        int uvHeight = height / 2;
        int uvWidth = width / 2;

        for (int row = 0; row < uvHeight; row++) {
            int uvRowStart = row * uvRowStride;

            for (int col = 0; col < uvWidth; col++) {
                int uvIndex = uvRowStart + col * uvPixelStride;

                byte u = uBuffer.get(uvIndex);
                byte v = vBuffer.get(uvIndex);

                nv21[position++] = v;
                nv21[position++] = u;
            }
        }

        return nv21;
    }

    private static Bitmap nv21ToBitmap(
            byte[] nv21,
            int width,
            int height,
            int jpegQuality) {
        YuvImage yuvImage = new YuvImage(
                nv21,
                ImageFormat.NV21,
                width,
                height,
                null);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        yuvImage.compressToJpeg(
                new Rect(0, 0, width, height),
                jpegQuality,
                outputStream);

        byte[] jpegBytes = outputStream.toByteArray();

        return BitmapFactory.decodeByteArray(
                jpegBytes,
                0,
                jpegBytes.length);
    }
}
