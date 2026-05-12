package com.w3n.webstream.Util;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class H264FrameBatchEncoder {
    private static final String LIBRARY_NAME = "webstream_h264";
    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary(LIBRARY_NAME);
            loaded = true;
        } catch (UnsatisfiedLinkError ignored) {
            loaded = false;
        }
        LIBRARY_LOADED = loaded;
    }

    public interface Callback {
        void onEncodedFrameBatchAvailable(
                byte[] encodedBatch,
                int width,
                int height,
                long timestampMs,
                long batchSequence
        );

        default void onBatchEncodeTimingAvailable(
                long batchSequence,
                int frameCount,
                long batchDurationNs,
                boolean partialBatch,
                int encodedByteCount
        ) {
        }

        default void onEncoderStarted() {
        }

        default void onEncoderStopped() {
        }

        default void onEncoderError(Exception error) {
        }
    }

    public enum InputYuvFormat {
        NV21,
        NV12,
        I420
    }

    public static final class Config {
        public final int width;
        public final int height;
        public final int frameRateFps;
        public final int bitrateKbps;
        public final int batchFrameCount;
        public final int iFrameIntervalSeconds;
        public final InputYuvFormat inputYuvFormat;
        public final boolean enableBatchTimingLogs;
        public final boolean requestKeyFrameAtStart;
        public final boolean requestKeyFrameEveryBatch;

        private Config(Builder builder) {
            this.width = builder.width;
            this.height = builder.height;
            this.frameRateFps = Math.max(1, builder.frameRateFps);
            this.bitrateKbps = Math.max(1, builder.bitrateKbps);
            this.batchFrameCount = Math.max(1, builder.batchFrameCount);
            this.iFrameIntervalSeconds = Math.max(1, builder.iFrameIntervalSeconds);
            this.inputYuvFormat = builder.inputYuvFormat == null
                    ? InputYuvFormat.NV21
                    : builder.inputYuvFormat;
            this.enableBatchTimingLogs = builder.enableBatchTimingLogs;
            this.requestKeyFrameAtStart = builder.requestKeyFrameAtStart;
            this.requestKeyFrameEveryBatch = builder.requestKeyFrameEveryBatch;
        }

        public static final class Builder {
            private int width = 640;
            private int height = 480;
            private int frameRateFps = 30;
            private int bitrateKbps = 800;
            private int batchFrameCount = 5;
            private int iFrameIntervalSeconds = 1;
            private InputYuvFormat inputYuvFormat = InputYuvFormat.NV21;
            private boolean enableBatchTimingLogs = true;
            private boolean requestKeyFrameAtStart = true;
            private boolean requestKeyFrameEveryBatch = false;

            public Builder setSize(int width, int height) {
                this.width = width;
                this.height = height;
                return this;
            }

            public Builder setFrameRateFps(int frameRateFps) {
                this.frameRateFps = frameRateFps;
                return this;
            }

            public Builder setBitrateKbps(int bitrateKbps) {
                this.bitrateKbps = bitrateKbps;
                return this;
            }

            public Builder setBatchFrameCount(int batchFrameCount) {
                this.batchFrameCount = batchFrameCount;
                return this;
            }

            public Builder setIFrameIntervalSeconds(int iFrameIntervalSeconds) {
                this.iFrameIntervalSeconds = iFrameIntervalSeconds;
                return this;
            }

            public Builder setInputYuvFormat(InputYuvFormat inputYuvFormat) {
                this.inputYuvFormat = inputYuvFormat;
                return this;
            }

            public Builder setEnableBatchTimingLogs(boolean enableBatchTimingLogs) {
                this.enableBatchTimingLogs = enableBatchTimingLogs;
                return this;
            }

            public Builder setRequestKeyFrameAtStart(boolean requestKeyFrameAtStart) {
                this.requestKeyFrameAtStart = requestKeyFrameAtStart;
                return this;
            }

            public Builder setRequestKeyFrameEveryBatch(boolean requestKeyFrameEveryBatch) {
                this.requestKeyFrameEveryBatch = requestKeyFrameEveryBatch;
                return this;
            }

            public Config build() {
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("H.264 width and height must be positive.");
                }

                if ((width % 2) != 0 || (height % 2) != 0) {
                    throw new IllegalArgumentException("H.264 width and height must be even values.");
                }

                return new Config(this);
            }
        }
    }

    private final Config config;
    private final Callback callback;

    private HandlerThread encoderThread;
    private Handler encoderHandler;
    private volatile long nativeHandle;
    private volatile boolean initialized;
    private volatile boolean started;
    private volatile boolean released;

    public H264FrameBatchEncoder(Config config, Callback callback) {
        if (config == null) {
            throw new IllegalArgumentException("H264FrameBatchEncoder.Config cannot be null.");
        }

        if (callback == null) {
            throw new IllegalArgumentException("H264FrameBatchEncoder.Callback cannot be null.");
        }

        this.config = config;
        this.callback = callback;
    }

    public void initialize() {
        if (released) {
            throw new IllegalStateException("Encoder is already released.");
        }

        if (initialized) {
            return;
        }

        if (nativeHandle != 0L) {
            initialized = true;
            return;
        }

        if (!LIBRARY_LOADED) {
            callback.onEncoderError(new IllegalStateException(
                    "Native H.264 library " + LIBRARY_NAME + " could not be loaded."));
            return;
        }

        runOnEncoderThreadBlocking(() -> {
            if (nativeHandle != 0L) {
                initialized = true;
                return null;
            }

            try {
                nativeHandle = nativeCreate(
                        config.width,
                        config.height,
                        config.frameRateFps,
                        config.bitrateKbps,
                        config.batchFrameCount,
                        config.iFrameIntervalSeconds,
                        config.inputYuvFormat.ordinal(),
                        config.enableBatchTimingLogs,
                        config.requestKeyFrameAtStart,
                        config.requestKeyFrameEveryBatch);
                initialized = true;
            } catch (Exception error) {
                callback.onEncoderError(error);
            }
            return null;
        });
    }

    public void start() {
        if (released) {
            callback.onEncoderError(new IllegalStateException("Encoder is already released."));
            return;
        }

        if (started) {
            return;
        }

        if (!initialized) {
            initialize();
        }

        if (nativeHandle == 0L) {
            callback.onEncoderError(new IllegalStateException("Encoder initialization failed."));
            return;
        }

        runOnEncoderThreadBlocking(() -> {
            if (released || nativeHandle == 0L || started) {
                return null;
            }

            try {
                if (!nativeIsStarted(nativeHandle)) {
                    nativeStart(nativeHandle);
                }
                started = true;
                callback.onEncoderStarted();
            } catch (Exception error) {
                callback.onEncoderError(error);
            }
            return null;
        });
    }

    public void encodeFrame(byte[] inputYuvData, long timestampMs) {
        if (released || !started || nativeHandle == 0L) {
            return;
        }

        if (inputYuvData == null || inputYuvData.length == 0) {
            return;
        }

        byte[] frameData = inputYuvData;

        getEncoderHandler().post(() -> {
            if (released || !started || nativeHandle == 0L) {
                return;
            }

            try {
                dispatchNativeBatch(nativeEncodeFrame(nativeHandle, frameData, timestampMs));
            } catch (Exception error) {
                callback.onEncoderError(error);
            }
        });
    }

    public void flush(long timestampMs) {
        if (released || !started || nativeHandle == 0L) {
            return;
        }

        runOnEncoderThreadBlocking(() -> {
            if (released || !started || nativeHandle == 0L) {
                return null;
            }

            try {
                dispatchNativeBatch(nativeFlush(nativeHandle, timestampMs));
            } catch (Exception error) {
                callback.onEncoderError(error);
            }
            return null;
        });
    }

    public void stop() {
        if (nativeHandle == 0L) {
            return;
        }

        runOnEncoderThreadBlocking(() -> {
            if (nativeHandle == 0L) {
                return null;
            }

            try {
                if (started || nativeIsStarted(nativeHandle)) {
                    dispatchNativeBatch(nativeFlush(nativeHandle, System.currentTimeMillis()));
                    nativeStop(nativeHandle);
                    started = false;
                    callback.onEncoderStopped();
                }
            } catch (Exception error) {
                callback.onEncoderError(error);
            } finally {
                initialized = nativeHandle != 0L && !released;
            }
            return null;
        });
    }

    public void release() {
        if (released) {
            return;
        }

        stop();

        if (nativeHandle != 0L) {
            runOnEncoderThreadBlocking(() -> {
                if (nativeHandle != 0L) {
                    nativeRelease(nativeHandle);
                    nativeHandle = 0L;
                }
                return null;
            });
        }

        released = true;
        started = false;
        initialized = false;
        stopEncoderThread();
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isReleased() {
        return released;
    }

    public long getTotalInputFrames() {
        if (nativeHandle == 0L) {
            return 0L;
        }
        Long value = runOnEncoderThreadBlocking(() ->
                nativeHandle == 0L ? 0L : nativeGetTotalInputFrames(nativeHandle));
        return value == null ? 0L : value;
    }

    public long getBatchSequence() {
        if (nativeHandle == 0L) {
            return 0L;
        }
        Long value = runOnEncoderThreadBlocking(() ->
                nativeHandle == 0L ? 0L : nativeGetBatchSequence(nativeHandle));
        return value == null ? 0L : value;
    }

    private void dispatchNativeBatch(NativeBatchResult batch) {
        if (batch == null || batch.encodedBatch == null || batch.encodedBatch.length == 0) {
            return;
        }

        callback.onBatchEncodeTimingAvailable(
                batch.batchSequence,
                batch.frameCount,
                batch.batchDurationNs,
                batch.partialBatch,
                batch.encodedBatch.length);

        callback.onEncodedFrameBatchAvailable(
                batch.encodedBatch,
                config.width,
                config.height,
                batch.timestampMs,
                batch.batchSequence);
    }

    private Handler getEncoderHandler() {
        synchronized (this) {
            if (encoderThread == null) {
                encoderThread = new HandlerThread("h264-encoder-thread");
                encoderThread.start();
                encoderHandler = new Handler(encoderThread.getLooper());
            }
            return encoderHandler;
        }
    }

    private <T> T runOnEncoderThreadBlocking(EncoderTask<T> task) {
        Handler handler = getEncoderHandler();
        Looper looper = handler.getLooper();

        if (Looper.myLooper() == looper) {
            return task.run();
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> runtimeError = new AtomicReference<>();

        handler.post(() -> {
            try {
                result.set(task.run());
            } catch (RuntimeException error) {
                runtimeError.set(error);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            callback.onEncoderError(error);
        }

        RuntimeException error = runtimeError.get();
        if (error != null) {
            throw error;
        }

        return result.get();
    }

    private void stopEncoderThread() {
        HandlerThread threadToStop;

        synchronized (this) {
            threadToStop = encoderThread;
            encoderThread = null;
            encoderHandler = null;
        }

        if (threadToStop == null) {
            return;
        }

        threadToStop.quitSafely();

        if (Looper.myLooper() == threadToStop.getLooper()) {
            return;
        }

        try {
            threadToStop.join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private interface EncoderTask<T> {
        T run();
    }

    private static final class NativeBatchResult {
        final byte[] encodedBatch;
        final long timestampMs;
        final long batchSequence;
        final int frameCount;
        final long batchDurationNs;
        final boolean partialBatch;

        NativeBatchResult(
                byte[] encodedBatch,
                long timestampMs,
                long batchSequence,
                int frameCount,
                long batchDurationNs,
                boolean partialBatch
        ) {
            this.encodedBatch = encodedBatch;
            this.timestampMs = timestampMs;
            this.batchSequence = batchSequence;
            this.frameCount = frameCount;
            this.batchDurationNs = batchDurationNs;
            this.partialBatch = partialBatch;
        }
    }

    private static native long nativeCreate(
            int width,
            int height,
            int frameRateFps,
            int bitrateKbps,
            int batchFrameCount,
            int iFrameIntervalSeconds,
            int inputYuvFormat,
            boolean enableBatchTimingLogs,
            boolean requestKeyFrameAtStart,
            boolean requestKeyFrameEveryBatch);

    private static native void nativeStart(long handle);

    private static native NativeBatchResult nativeEncodeFrame(
            long handle,
            byte[] inputYuvData,
            long timestampMs);

    private static native NativeBatchResult nativeFlush(long handle, long timestampMs);

    private static native void nativeStop(long handle);

    private static native void nativeRelease(long handle);

    private static native boolean nativeIsStarted(long handle);

    private static native long nativeGetTotalInputFrames(long handle);

    private static native long nativeGetBatchSequence(long handle);
}
