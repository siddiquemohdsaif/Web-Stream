package com.w3n.webstream.Util;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;

public final class H264FrameBatchDecoder {
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

    public enum FrameFormat {
        /**
         * Raw copied MediaCodec output buffer.
         * <p>
         * Usually YUV 420 flexible because decoder is configured with:
         * COLOR_FormatYUV420Flexible.
         * <p>
         * This is NOT RGBA.
         */
        CODEC_OUTPUT_YUV
    }

    public interface Callback {
        /**
         * Called at configured FPS.
         * <p>
         * Example:
         * fps = 15 means this callback fires every ~66.7 ms if decoded frames are available.
         */
        void onImageAvailable(DecodedFrame frame);

        /**
         * Raw MediaCodec decode time.
         * <p>
         * Measures:
         * queueInputBuffer(...)
         * ->
         * dequeueOutputBuffer(...) returns decoded output
         */
        void onRawMediaCodecDecodeTimingAvailable(
                long frameSequence,
                long decodeDurationNs
        );

        default void onDecodeBatchHandled(
                long batchSequence,
                int accessUnitCount,
                long handledDurationNs,
                long decodedFrameCount,
                int queuedDecodedFrameCount
        ) {
        }

        default void onDecodedFrameDropped(
                long frameSequence,
                int queuedDecodedFrameCount
        ) {
        }

        default void onDecoderStarted() {
        }

        default void onDecoderStopped() {
        }

        default void onDecoderError(Exception error) {
        }
    }

    public static final class Config {
        public final int width;
        public final int height;
        public final int frameRateFps;
        public final FrameFormat frameFormat;
        public final int maxQueuedFrames;

        private Config(Builder builder) {
            this.width = builder.width;
            this.height = builder.height;
            this.frameRateFps = Math.max(1, builder.frameRateFps);
            this.frameFormat = builder.frameFormat == null
                    ? FrameFormat.CODEC_OUTPUT_YUV
                    : builder.frameFormat;
            this.maxQueuedFrames = Math.max(1, builder.maxQueuedFrames);
        }

        public static final class Builder {
            private int width = 640;
            private int height = 480;
            private int frameRateFps = 15;
            private FrameFormat frameFormat = FrameFormat.CODEC_OUTPUT_YUV;
            private int maxQueuedFrames = 5;

            public Builder setSize(int width, int height) {
                this.width = width;
                this.height = height;
                return this;
            }

            public Builder setFrameRateFps(int frameRateFps) {
                this.frameRateFps = Math.max(1, frameRateFps);
                return this;
            }

            public Builder setFrameFormat(FrameFormat frameFormat) {
                this.frameFormat = frameFormat == null
                        ? FrameFormat.CODEC_OUTPUT_YUV
                        : frameFormat;
                return this;
            }

            public Builder setMaxQueuedFrames(int maxQueuedFrames) {
                this.maxQueuedFrames = Math.max(1, maxQueuedFrames);
                return this;
            }

            public Config build() {
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException(
                            "Decoder width and height must be positive."
                    );
                }

                if ((width % 2) != 0 || (height % 2) != 0) {
                    throw new IllegalArgumentException(
                            "H.264 width and height must be even values."
                    );
                }

                return new Config(this);
            }
        }
    }

    public static final class DecodedFrame {
        public final int width;
        public final int height;
        public final long timestampNs;
        public final long sequence;

        /**
         * Safe copied MediaCodec output bytes.
         * <p>
         * This buffer remains valid after decoder.releaseOutputBuffer(...).
         */
        public final ByteBuffer buffer;

        /**
         * Number of valid bytes in buffer.
         */
        public final int size;

        /**
         * Offset inside this copied buffer.
         * <p>
         * Since we copy only the valid region, this is always 0.
         */
        public final int offset;

        /**
         * MediaCodec output format for this frame.
         * <p>
         * Use this to read stride, slice-height, color-format, etc.
         */
        public final MediaFormat mediaFormat;

        public final FrameFormat frameFormat;

        /**
         * Native heap pointer that owns the decoded output bytes.
         * <p>
         * The pointer remains valid until release() is called. This lets a native
         * graphics path consume the frame without copying through a Java byte[].
         */
        public final long nativeBufferPtr;
        public final int nativeBufferSize;

        private volatile boolean nativeBufferReleased;

        private DecodedFrame(
                int width,
                int height,
                long timestampNs,
                long sequence,
                ByteBuffer buffer,
                int offset,
                int size,
                MediaFormat mediaFormat,
                FrameFormat frameFormat
        ) {
            this.width = width;
            this.height = height;
            this.timestampNs = timestampNs;
            this.sequence = sequence;
            this.buffer = buffer;
            this.offset = offset;
            this.size = size;
            this.mediaFormat = mediaFormat;
            this.frameFormat = frameFormat;
            this.nativeBufferPtr = 0L;
            this.nativeBufferSize = 0;
            this.nativeBufferReleased = true;
        }

        private DecodedFrame(
                int width,
                int height,
                long timestampNs,
                long sequence,
                long nativeBufferPtr,
                int size,
                MediaFormat mediaFormat,
                FrameFormat frameFormat
        ) {
            this.width = width;
            this.height = height;
            this.timestampNs = timestampNs;
            this.sequence = sequence;
            this.nativeBufferPtr = nativeBufferPtr;
            this.nativeBufferSize = size;
            this.buffer = nativeBufferPtr == 0L || size <= 0
                    ? null
                    : nativeWrapBuffer(nativeBufferPtr, size);
            this.offset = 0;
            this.size = size;
            this.mediaFormat = mediaFormat;
            this.frameFormat = frameFormat;
            this.nativeBufferReleased = nativeBufferPtr == 0L;
        }

        public void release() {
            if (!nativeBufferReleased && nativeBufferPtr != 0L) {
                nativeBufferReleased = true;
                nativeReleaseFrameBuffer(nativeBufferPtr);
            }
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                release();
            } finally {
                super.finalize();
            }
        }

        @Override
        public String toString() {
            return "DecodedFrame{" +
                    "width=" + width +
                    ", height=" + height +
                    ", timestampNs=" + timestampNs +
                    ", sequence=" + sequence +
                    ", buffer=" + buffer +
                    ", size=" + size +
                    ", offset=" + offset +
                    ", mediaFormat=" + mediaFormat +
                    ", frameFormat=" + frameFormat +
                    ", nativeBufferPtr=" + nativeBufferPtr +
                    ", nativeBufferSize=" + nativeBufferSize +
                    ", nativeBufferReleased=" + nativeBufferReleased +
                    '}';
        }
    }

    private static final String TAG = "H264FrameBatchDecoder";
    private static final int DEQUEUE_TIMEOUT_US = 10_000;

    private final Config config;
    private final Callback callback;

    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private final Queue<DecodedFrame> decodedFrameQueue = new ArrayDeque<>();

    /**
     * Stores raw MediaCodec decode start time for each queued input buffer.
     * <p>
     * Key:
     * presentationTimeUs passed into queueInputBuffer(...)
     * <p>
     * Value:
     * System.nanoTime() immediately before queueInputBuffer(...)
     */
    private final Map<Long, Long> rawDecodeStartTimeByPresentationUs = new HashMap<>();

    private HandlerThread decoderThread;
    private Handler decoderHandler;

    private HandlerThread renderThread;
    private Handler renderHandler;
    private Handler uiRenderHandler;


    private MediaCodec decoder;
    private MediaFormat outputFormat;

    private long inputChunkSequence;
    private long decodedFrameSequence;
    private long renderedFrameSequence;
    private long handledBatchSequence;

    private boolean initialized;
    private boolean started;
    private boolean released;
    private volatile long nativeHandle;
    private final Object nativeDecodeLock = new Object();

    public static volatile long decoderFrameReceived = 0;
    public static volatile long dispatchedFrames = 0;
    public static volatile long imagesOnBuffer = 0;

    private static volatile long frameDecoded = 0;



    // Adaptive playback PID config
    private static final double TARGET_BUFFER_SECONDS = 0.25;

    // Playback speed limits
    private static final double MIN_PLAYBACK_SPEED = 0.90;
    private static final double MAX_PLAYBACK_SPEED = 3;

    // PID gains
    private static final double PID_KP = 0.45;
    private static final double PID_KI = 0.24;
    private static final double PID_KD = 0.08;

    // Prevent integral wind-up
    private static final double PID_INTEGRAL_MIN = -2.0;
    private static final double PID_INTEGRAL_MAX = 2.0;

    private double playbackPidIntegral = 0.0;
    private double playbackPidPreviousError = 0.0;
    private long playbackPidPreviousTimeNs = 0L;


    private final Runnable renderRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started || released) {
                return;
            }

            DecodedFrame frame = null;

            synchronized (H264FrameBatchDecoder.this) {
                if (!decodedFrameQueue.isEmpty()) {
                    frame = decodedFrameQueue.poll();
                }
            }

            if (frame != null) {
                renderedFrameSequence++;
                dispatchedFrames++;

                Log.d(
                        "DECODER_P",
                        "run: decoderFrameReceived " + decoderFrameReceived
                                + " dispatchedFrames " + dispatchedFrames
                                + " imagesOnBuffer " + imagesOnBuffer
                );

                if (uiRenderHandler != null){
                    DecodedFrame finalFrame = frame;
                    uiRenderHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onImageAvailable(finalFrame);
                        }
                    });
                } else {
                    throw new RuntimeException("UI Render Handler is not initialized.");
                }

            }

            if (renderHandler != null) {
                renderHandler.postDelayed(this, getFrameDelayMs());
            }
        }
    };

    public H264FrameBatchDecoder(Config config, Callback callback) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "H264FrameBatchDecoder.Config cannot be null."
            );
        }

        if (callback == null) {
            throw new IllegalArgumentException(
                    "H264FrameBatchDecoder.Callback cannot be null."
            );
        }

        this.config = config;
        this.callback = callback;
    }

    /**
     * 1. Initialization
     * <p>
     * Creates MediaCodec decoder with byte-buffer output.
     */
    public synchronized void initialize() {
        if (released) {
            throw new IllegalStateException("Decoder is already released.");
        }

        if (initialized) {
            return;
        }

        try {
            if (nativeHandle != 0L) {
                uiRenderHandler = new Handler(Looper.getMainLooper());
                initialized = true;
                return;
            }

            if (!LIBRARY_LOADED) {
                throw new IllegalStateException(
                        "Native H.264 library " + LIBRARY_NAME + " could not be loaded."
                );
            }
            nativeHandle = nativeCreate(
                    config.width,
                    config.height,
                    config.frameRateFps,
                    config.maxQueuedFrames
            );
            uiRenderHandler = new Handler(Looper.getMainLooper());
            initialized = true;
        } catch (Exception error) {
            callback.onDecoderError(error);
        }
    }

    /**
     * 2. Start
     * <p>
     * Starts decoder and render clock.
     */
    public synchronized void start() {
        if (released) {
            callback.onDecoderError(
                    new IllegalStateException("Decoder is already released.")
            );
            return;
        }

        if (started) {
            return;
        }

        if (!initialized) {
            initialize();
        }

        if (nativeHandle == 0L) {
            callback.onDecoderError(
                    new IllegalStateException("Decoder initialization failed.")
            );
            return;
        }

        try {
            nativeStart(nativeHandle);

            inputChunkSequence = 0L;
            decodedFrameSequence = 0L;
            renderedFrameSequence = 0L;
            handledBatchSequence = 0L;

            resetPlaybackPid();
            synchronized (this) {
                clearDecodedFrameQueue();
            }

            synchronized (rawDecodeStartTimeByPresentationUs) {
                rawDecodeStartTimeByPresentationUs.clear();
            }

            started = true;

            getRenderHandler().post(renderRunnable);

            callback.onDecoderStarted();
        } catch (Exception error) {
            callback.onDecoderError(error);
        }
    }

    /**
     * 3. onDecodeChunk
     * <p>
     * Called from outside with encoder output.
     * <p>
     * Example:
     * Encoder gives frameRateFps / batchFrameCount chunks per second.
     * Decoder accepts those chunks immediately, decodes all frames,
     * then emits decoded frames at configured FPS.
     */
    public void onDecodeChunk(byte[] encodedChunk) {
        if (released || !started || nativeHandle == 0L) {
            return;
        }

        if (encodedChunk == null || encodedChunk.length == 0) {
            return;
        }

//        decoderFrameReceived += 5;

        Handler handler = getDecoderHandler();

        handler.post(() -> {
            long batchStartMs = System.currentTimeMillis();
            long batchStartNs = System.nanoTime();
            DecodedFrame[] frames;
            synchronized (nativeDecodeLock) {
                frames = nativeDecodeChunk(nativeHandle, encodedChunk);
            }
            int accessUnitCount = frames == null ? 0 : frames.length;

            decoderFrameReceived += frames.length;
            Log.d("DECODER_QQ", "onDecodeChunk:Native Decode Time "+(System.currentTimeMillis() - batchStartMs));

            Log.d("DECODER_P", "onDecodeChunk: frameDecoded " + frameDecoded);

            Log.d("DECODER_QQ", "onDecodeChunk:Batch Decode Time "+(System.currentTimeMillis() - batchStartMs));
            frameDecoded = 0;

            long handledDurationNs = System.nanoTime() - batchStartNs;
            long batchSequence = ++handledBatchSequence;

            int queuedDecodedFrameCount;
            long decodedFrameCount;

            synchronized (H264FrameBatchDecoder.this) {
                if (frames != null) {
                    for (DecodedFrame frame : frames) {
                        if (frame == null) {
                            continue;
                        }
                        queueDecodedFrame(frame);
                    }
                }
                queuedDecodedFrameCount = decodedFrameQueue.size();
                decodedFrameCount = decodedFrameSequence;
            }

            Log.d(
                    TAG,
                    "H.264 decode batch handled. batchSequence="
                            + batchSequence
                            + ", accessUnits=" + accessUnitCount
                            + ", durationMs="
                            + String.format(
                            Locale.US,
                            "%.3f",
                            handledDurationNs / 1_000_000.0
                    )
                            + ", decodedFrames=" + decodedFrameCount
                            + ", queuedDecodedFrames=" + queuedDecodedFrameCount
            );

            callback.onDecodeBatchHandled(
                    batchSequence,
                    accessUnitCount,
                    handledDurationNs,
                    decodedFrameCount,
                    queuedDecodedFrameCount
            );
        });
    }

    /**
     * Synchronous native batch decode.
     * <p>
     * The returned frames own native buffers. The caller must call
     * DecodedFrame.release() after the graphics layer has consumed each frame.
     */
    public DecodedFrame[] decodeChunk(byte[] encodedChunk) {
        if (released || !started || nativeHandle == 0L) {
            return new DecodedFrame[0];
        }

        if (encodedChunk == null || encodedChunk.length == 0) {
            return new DecodedFrame[0];
        }

        DecodedFrame[] frames;
        synchronized (nativeDecodeLock) {
            frames = nativeDecodeChunk(nativeHandle, encodedChunk);
        }
        return frames == null ? new DecodedFrame[0] : frames;
    }

    /**
     * 4. Stop
     * <p>
     * Stops decoder but keeps object reusable.
     */
    public synchronized void stop() {
        if (!started && decoder == null) {
            return;
        }

        started = false;

        if (renderHandler != null) {
            renderHandler.removeCallbacksAndMessages(null);
        }

        try {
            if (nativeHandle != 0L) {
                synchronized (nativeDecodeLock) {
                    nativeStop(nativeHandle);
                }
            }

            synchronized (this) {
                clearDecodedFrameQueue();
            }

            synchronized (rawDecodeStartTimeByPresentationUs) {
                rawDecodeStartTimeByPresentationUs.clear();
            }

            initialized = false;
            outputFormat = null;

            callback.onDecoderStopped();
        } catch (Exception error) {
            callback.onDecoderError(error);
        }
    }

    /**
     * 5. Release
     * <p>
     * Fully destroys decoder.
     */
    public synchronized void release() {
        if (released) {
            return;
        }

        stop();
        stopDecoderThread();
        stopRenderThread();

        if (nativeHandle != 0L) {
            synchronized (nativeDecodeLock) {
                nativeRelease(nativeHandle);
            }
            nativeHandle = 0L;
        }

        released = true;
        started = false;
        initialized = false;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized boolean isReleased() {
        return released;
    }

    public synchronized int getQueuedDecodedFrameCount() {
        return decodedFrameQueue.size();
    }

    public synchronized long getDecodedFrameSequence() {
        return decodedFrameSequence;
    }

    public synchronized long getRenderedFrameSequence() {
        return renderedFrameSequence;
    }


    private void clearDecodedFrameQueue() {
        for (DecodedFrame frame : decodedFrameQueue) {
            if (frame != null) {
                frame.release();
            }
        }
        decodedFrameQueue.clear();
    }

    private void queueDecodedFrame(DecodedFrame frame) {
        frameDecoded++;
        imagesOnBuffer++;
        decodedFrameSequence = Math.max(decodedFrameSequence, frame.sequence);
        decodedFrameQueue.offer(frame);
    }



    private void resetPlaybackPid() {
        playbackPidIntegral = 0.0;
        playbackPidPreviousError = 0.0;
        playbackPidPreviousTimeNs = 0L;
    }

    private long getFrameDelayMs() {
        int queuedFrameCount;

        synchronized (H264FrameBatchDecoder.this) {
            queuedFrameCount = decodedFrameQueue.size();
        }

        double availablePlaybackSec =
                queuedFrameCount / (double) Math.max(1, config.frameRateFps);

        double playbackSpeed =
                getPidPlaybackSpeed(availablePlaybackSec);

        double baseDelayMs =
                1000.0 / Math.max(1, config.frameRateFps);

        double adjustedDelayMs =
                baseDelayMs / playbackSpeed;

        long delayMs =
                Math.max(1L, Math.round(adjustedDelayMs));

        Log.d(
                "DECODER_PLAYBACK",
                "queueSize=" + queuedFrameCount
                        + ", bufferSec=" + String.format(Locale.US, "%.3f", availablePlaybackSec)
                        + ", targetSec=" + String.format(Locale.US, "%.3f", TARGET_BUFFER_SECONDS)
                        + ", speed=" + String.format(Locale.US, "%.3f", playbackSpeed)
                        + ", delayMs=" + delayMs
        );

        return delayMs;
    }

    private double getPidPlaybackSpeed(double availablePlaybackSec) {
        long nowNs = System.nanoTime();

        double dtSec;

        if (playbackPidPreviousTimeNs == 0L) {
            dtSec = 1.0 / Math.max(1, config.frameRateFps);
        } else {
            dtSec = (nowNs - playbackPidPreviousTimeNs) / 1_000_000_000.0;
        }

        if (dtSec <= 0.0) {
            dtSec = 1.0 / Math.max(1, config.frameRateFps);
        }

        playbackPidPreviousTimeNs = nowNs;

        /*
         * Positive error:
         * Buffer is above target, so playback should speed up.
         *
         * Negative error:
         * Buffer is below target, so playback should slow down.
         */
        double error =
                availablePlaybackSec - TARGET_BUFFER_SECONDS;

        playbackPidIntegral += error * dtSec;

        playbackPidIntegral = clamp(
                playbackPidIntegral,
                PID_INTEGRAL_MIN,
                PID_INTEGRAL_MAX
        );

        double derivative =
                (error - playbackPidPreviousError) / dtSec;

        playbackPidPreviousError = error;

        double correction =
                (PID_KP * error)
                        + (PID_KI * playbackPidIntegral)
                        + (PID_KD * derivative);

        double playbackSpeed =
                1.0 + correction;

        return clamp(
                playbackSpeed,
                MIN_PLAYBACK_SPEED,
                MAX_PLAYBACK_SPEED
        );
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Handler getDecoderHandler() {
        if (decoderThread == null) {
            decoderThread = new HandlerThread("h264-decoder-thread");
            decoderThread.start();
            decoderHandler = new Handler(decoderThread.getLooper());
        }

        return decoderHandler;
    }

    private Handler getRenderHandler() {
        if (renderThread == null) {
            renderThread = new HandlerThread("h264-decoder-render-thread");
            renderThread.start();
            renderHandler = new Handler(renderThread.getLooper());
        }

        return renderHandler;
    }

    private void stopDecoderThread() {
        if (decoderThread != null) {
            decoderThread.quitSafely();

            try {
                decoderThread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            decoderThread = null;
            decoderHandler = null;
        }
    }

    private void stopRenderThread() {
        if (renderThread != null) {
            renderThread.quitSafely();

            try {
                renderThread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            renderThread = null;
            renderHandler = null;
        }
    }

    private List<byte[]> splitAnnexBAccessUnits(byte[] encodedChunk) {
        List<NalUnit> nalUnits = findNalUnits(encodedChunk);
        List<byte[]> accessUnits = new ArrayList<>();

        if (nalUnits.isEmpty()) {
            accessUnits.add(encodedChunk);
            return accessUnits;
        }

        ByteArrayOutputStream parameterSets = new ByteArrayOutputStream();
        ByteArrayOutputStream currentAccessUnit = new ByteArrayOutputStream();
        boolean currentAccessUnitHasVcl = false;

        for (NalUnit nalUnit : nalUnits) {
            int nalType = nalUnit.type;
            boolean vclNal = nalType == 1 || nalType == 5;

            if (nalType == 7 || nalType == 8) {
                parameterSets.write(
                        encodedChunk,
                        nalUnit.start,
                        nalUnit.end - nalUnit.start
                );
                continue;
            }

            if (vclNal && currentAccessUnitHasVcl && isFirstSlice(encodedChunk, nalUnit)) {
                accessUnits.add(currentAccessUnit.toByteArray());
                currentAccessUnit.reset();
                currentAccessUnitHasVcl = false;
            }

            if (currentAccessUnit.size() == 0 && parameterSets.size() > 0) {
                byte[] codecConfig = parameterSets.toByteArray();
                currentAccessUnit.write(codecConfig, 0, codecConfig.length);
            }

            currentAccessUnit.write(
                    encodedChunk,
                    nalUnit.start,
                    nalUnit.end - nalUnit.start
            );

            if (vclNal) {
                currentAccessUnitHasVcl = true;
            }
        }

        if (currentAccessUnit.size() > 0) {
            accessUnits.add(currentAccessUnit.toByteArray());
        }

        return accessUnits;
    }

    private List<NalUnit> findNalUnits(byte[] data) {
        List<Integer> startCodes = new ArrayList<>();

        for (int i = 0; i <= data.length - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    startCodes.add(i);
                    i += 2;
                } else if (i <= data.length - 4
                        && data[i + 2] == 0
                        && data[i + 3] == 1) {
                    startCodes.add(i);
                    i += 3;
                }
            }
        }

        List<NalUnit> nalUnits = new ArrayList<>();

        for (int i = 0; i < startCodes.size(); i++) {
            int start = startCodes.get(i);
            int nextStart = i + 1 < startCodes.size()
                    ? startCodes.get(i + 1)
                    : data.length;

            int nalHeaderIndex = start + startCodeLength(data, start);

            if (nalHeaderIndex >= nextStart) {
                continue;
            }

            nalUnits.add(new NalUnit(
                    start,
                    nextStart,
                    nalHeaderIndex,
                    data[nalHeaderIndex] & 0x1f
            ));
        }

        return nalUnits;
    }

    private int startCodeLength(byte[] data, int start) {
        return data[start + 2] == 1 ? 3 : 4;
    }

    private boolean isFirstSlice(byte[] data, NalUnit nalUnit) {
        /**
         * The first bit of first_mb_in_slice is 1 only when the value is 0.
         * That is the common single-slice frame boundary produced by MediaCodec encoders.
         */
        int firstSliceByteIndex = nalUnit.nalHeaderIndex + 1;

        return firstSliceByteIndex < nalUnit.end
                && (data[firstSliceByteIndex] & 0x80) != 0;
    }

    private static final class NalUnit {
        final int start;
        final int end;
        final int nalHeaderIndex;
        final int type;

        NalUnit(
                int start,
                int end,
                int nalHeaderIndex,
                int type
        ) {
            this.start = start;
            this.end = end;
            this.nalHeaderIndex = nalHeaderIndex;
            this.type = type;
        }
    }

    private static native long nativeCreate(
            int width,
            int height,
            int frameRateFps,
            int maxQueuedFrames);

    private static native void nativeStart(long handle);

    public static native DecodedFrame[] nativeDecodeChunk(long handle, byte[] encodedChunk);

    private static native void nativeStop(long handle);

    private static native void nativeRelease(long handle);

    private static native ByteBuffer nativeWrapBuffer(long nativeBufferPtr, int size);

    private static native void nativeReleaseFrameBuffer(long nativeBufferPtr);
}
