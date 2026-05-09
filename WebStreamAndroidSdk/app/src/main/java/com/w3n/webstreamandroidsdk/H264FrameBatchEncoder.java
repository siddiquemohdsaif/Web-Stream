package com.w3n.webstreamandroidsdk;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

public final class H264FrameBatchEncoder {

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
        /**
         * Android NV21 layout.
         *
         * Y plane first, then interleaved VU.
         *
         * Layout:
         * YYYYYYYY VUVUVUVU
         */
        NV21,

        /**
         * NV12 layout.
         *
         * Y plane first, then interleaved UV.
         *
         * Layout:
         * YYYYYYYY UVUVUVUV
         */
        NV12,

        /**
         * I420 / YUV420 planar layout.
         *
         * Y plane, then U plane, then V plane.
         *
         * Layout:
         * YYYYYYYY UUUU VVVV
         */
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

            /**
             * Keep false for better compression.
             *
             * Set true only if each batch must be independently decodable.
             */
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

    private static final String TAG = "H264FrameBatchEncoder";
    private static final int DEQUEUE_TIMEOUT_US = 0;

    private final Config config;
    private final Callback callback;

    private final ByteArrayOutputStream batchOutput = new ByteArrayOutputStream();
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private MediaCodec encoder;
    private int encoderColorFormat;
    private byte[] codecConfig;

    private int framesInBatch;
    private long batchSequence;
    private long totalInputFrames;

    private long currentBatchStartTimeNs;
    private long encoderStartTimeNs;

    private boolean initialized;
    private boolean started;
    private boolean released;
    private boolean firstKeyFrameRequested;

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

    /**
     * 1. initialization
     *
     * Selects hardware AVC/H.264 encoder and supported YUV420 color format.
     */
    public synchronized void initialize() {
        if (released) {
            throw new IllegalStateException("Encoder is already released.");
        }

        if (initialized) {
            return;
        }

        try {
            EncoderSelection selection = selectAvcEncoder();
            encoderColorFormat = selection.colorFormat;
            encoder = MediaCodec.createByCodecName(selection.codecInfo.getName());
            initialized = true;
        } catch (Exception error) {
            callback.onEncoderError(error);
        }
    }

    /**
     * 2. start
     *
     * Configures and starts MediaCodec.
     */
    public synchronized void start() {
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

        if (encoder == null) {
            callback.onEncoderError(new IllegalStateException("Encoder initialization failed."));
            return;
        }

        try {
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    config.width,
                    config.height
            );

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, encoderColorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRateFps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds);

            try {
                format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
            } catch (RuntimeException ignored) {
                // Some devices/builds may not support this key.
            }

            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            framesInBatch = 0;
            batchSequence = 0;
            totalInputFrames = 0;
            currentBatchStartTimeNs = 0L;
            encoderStartTimeNs = System.nanoTime();
            codecConfig = null;
            firstKeyFrameRequested = false;
            batchOutput.reset();

            started = true;

            callback.onEncoderStarted();
        } catch (Exception error) {
            callback.onEncoderError(error);
        }
    }

    /**
     * Encodes one YUV frame.
     *
     * Input should come from CameraController.CameraFrame.yuv420Data.
     *
     * No Bitmap.
     * No ARGB.
     * No RGB-to-YUV conversion.
     */
    public synchronized void encodeFrame(byte[] inputYuvData, long timestampMs) {
        if (released || !started || encoder == null) {
            return;
        }

        if (inputYuvData == null || inputYuvData.length == 0) {
            return;
        }

        int expectedSize = getExpectedYuvSize();
        if (inputYuvData.length < expectedSize) {
            callback.onEncoderError(new IllegalArgumentException(
                    "Invalid YUV frame size. Expected at least "
                            + expectedSize + " bytes, got " + inputYuvData.length
            ));
            return;
        }

        try {
            if (framesInBatch == 0) {
                currentBatchStartTimeNs = System.nanoTime();

                if (config.requestKeyFrameEveryBatch) {
                    requestKeyFrame();
                }
            }

            if (config.requestKeyFrameAtStart && !firstKeyFrameRequested) {
                requestKeyFrame();
                firstKeyFrameRequested = true;
            }

            drainEncoder();

            int inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (inputIndex < 0) {
                return;
            }

            ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
            if (inputBuffer == null) {
                return;
            }

            inputBuffer.clear();

            byte[] encoderReadyYuv = adaptInputYuvToEncoderColorFormat(
                    inputYuvData,
                    encoderColorFormat,
                    config.inputYuvFormat,
                    config.width,
                    config.height
            );

            if (encoderReadyYuv.length > inputBuffer.remaining()) {
                throw new IllegalStateException("H.264 encoder input buffer is too small. "
                        + "required=" + encoderReadyYuv.length
                        + ", available=" + inputBuffer.remaining());
            }

            inputBuffer.put(encoderReadyYuv);

            encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    encoderReadyYuv.length,
                    timestampMs * 1000L,
                    0
            );

            framesInBatch++;
            totalInputFrames++;

            drainEncoder();

            if (framesInBatch >= config.batchFrameCount) {
                long batchDurationNs = currentBatchStartTimeNs > 0L
                        ? System.nanoTime() - currentBatchStartTimeNs
                        : 0L;

                dispatchCurrentBatch(timestampMs, batchDurationNs, false);
            }

        } catch (Exception error) {
            callback.onEncoderError(error);
        }
    }

    /**
     * Force the current partial batch to be dispatched.
     *
     * Useful before stopping or when you want to send remaining encoded bytes immediately.
     */
    public synchronized void flush(long timestampMs) {
        if (released || !started || encoder == null) {
            return;
        }

        try {
            drainEncoder();

            if (batchOutput.size() > 0) {
                long batchDurationNs = currentBatchStartTimeNs > 0L
                        ? System.nanoTime() - currentBatchStartTimeNs
                        : 0L;

                dispatchCurrentBatch(timestampMs, batchDurationNs, true);
            }
        } catch (Exception error) {
            callback.onEncoderError(error);
        }
    }

    /**
     * 3. stop
     *
     * Stops MediaCodec but keeps the object reusable.
     */
    public synchronized void stop() {
        if (!started && encoder == null) {
            return;
        }

        try {
            if (encoder != null) {
                try {
                    drainEncoder();
                } catch (RuntimeException ignored) {
                }

                if (batchOutput.size() > 0) {
                    try {
                        long batchDurationNs = currentBatchStartTimeNs > 0L
                                ? System.nanoTime() - currentBatchStartTimeNs
                                : 0L;

                        dispatchCurrentBatch(System.currentTimeMillis(), batchDurationNs, true);
                    } catch (IOException ignored) {
                    }
                }

                try {
                    encoder.stop();
                } catch (RuntimeException ignored) {
                }

                try {
                    encoder.release();
                } catch (RuntimeException ignored) {
                }

                encoder = null;
            }

            batchOutput.reset();
            framesInBatch = 0;
            codecConfig = null;
            currentBatchStartTimeNs = 0L;

            started = false;
            initialized = false;

            callback.onEncoderStopped();
        } catch (Exception error) {
            callback.onEncoderError(error);
        }
    }

    /**
     * 4. release
     *
     * Fully destroys encoder.
     */
    public synchronized void release() {
        if (released) {
            return;
        }

        stop();

        released = true;
        started = false;
        initialized = false;

        batchOutput.reset();
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized boolean isReleased() {
        return released;
    }

    public synchronized long getTotalInputFrames() {
        return totalInputFrames;
    }

    public synchronized long getBatchSequence() {
        return batchSequence;
    }

    private void dispatchCurrentBatch(
            long timestampMs,
            long batchDurationNs,
            boolean partialBatch) throws IOException {

        byte[] encodedBatch = batchOutput.toByteArray();

        batchOutput.reset();

        int frameCountForThisBatch = framesInBatch;
        framesInBatch = 0;
        currentBatchStartTimeNs = 0L;

        if (encodedBatch.length == 0) {
            return;
        }

        if (codecConfig != null && !startsWithCodecConfig(encodedBatch)) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write(codecConfig, 0, codecConfig.length);
            outputStream.write(encodedBatch, 0, encodedBatch.length);
            encodedBatch = outputStream.toByteArray();
        }

        batchSequence++;

        if (config.enableBatchTimingLogs) {
            logBatchTiming(
                    encodedBatch,
                    frameCountForThisBatch,
                    batchDurationNs,
                    partialBatch
            );
        }

        callback.onBatchEncodeTimingAvailable(
                batchSequence,
                frameCountForThisBatch,
                batchDurationNs,
                partialBatch,
                encodedBatch.length
        );

        callback.onEncodedFrameBatchAvailable(
                encodedBatch,
                config.width,
                config.height,
                timestampMs,
                batchSequence
        );
    }

    private void logBatchTiming(
            byte[] encodedBatch,
            int frameCountForThisBatch,
            long batchDurationNs,
            boolean partialBatch) {

        double batchDurationMs = batchDurationNs / 1_000_000.0;
        double averageFrameTimeMs = frameCountForThisBatch > 0
                ? batchDurationMs / frameCountForThisBatch
                : 0.0;
        double outputKb = encodedBatch.length / 1024.0;

        long runningDurationNs = encoderStartTimeNs > 0L
                ? System.nanoTime() - encoderStartTimeNs
                : 0L;

        double runningDurationSeconds = runningDurationNs / 1_000_000_000.0;
        double effectiveInputFps = runningDurationSeconds > 0.0
                ? totalInputFrames / runningDurationSeconds
                : 0.0;

        Log.d(TAG,
                "H.264 batch encoded. "
                        + "batchSequence=" + batchSequence
                        + ", frames=" + frameCountForThisBatch
                        + ", configuredBatchFrames=" + config.batchFrameCount
                        + ", partialBatch=" + partialBatch
                        + ", durationMs=" + formatDouble(batchDurationMs)
                        + ", avgFrameMs=" + formatDouble(averageFrameTimeMs)
                        + ", outputKb=" + formatDouble(outputKb)
                        + ", width=" + config.width
                        + ", height=" + config.height
                        + ", inputYuvFormat=" + config.inputYuvFormat
                        + ", encoderColorFormat=" + encoderColorFormat
                        + ", bitrateKbps=" + config.bitrateKbps
                        + ", configuredFps=" + config.frameRateFps
                        + ", effectiveInputFps=" + formatDouble(effectiveInputFps)
                        + ", totalInputFrames=" + totalInputFrames);
    }

    private String formatDouble(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private int getExpectedYuvSize() {
        return config.width * config.height * 3 / 2;
    }

    private EncoderSelection selectAvcEncoder() throws IOException {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);

        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (!codecInfo.isEncoder()) {
                continue;
            }

            for (String type : codecInfo.getSupportedTypes()) {
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                    int supportedColorFormat = selectColorFormat(codecInfo);

                    if (supportedColorFormat != 0) {
                        return new EncoderSelection(codecInfo, supportedColorFormat);
                    }
                }
            }
        }

        throw new IOException("No H.264 encoder with supported YUV420 color format is available.");
    }

    private int selectColorFormat(MediaCodecInfo codecInfo) {
        MediaCodecInfo.CodecCapabilities capabilities =
                codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);

        int[] preferredFormats = new int[]{
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        };

        for (int preferredFormat : preferredFormats) {
            for (int supportedFormat : capabilities.colorFormats) {
                if (supportedFormat == preferredFormat) {
                    return preferredFormat;
                }
            }
        }

        return 0;
    }

    private void requestKeyFrame() {
        if (encoder == null) {
            return;
        }

        try {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(parameters);
        } catch (RuntimeException ignored) {
        }
    }

    private void drainEncoder() {
        if (encoder == null) {
            return;
        }

        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US);

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rememberCodecConfig(encoder.getOutputFormat());
                continue;
            }

            if (outputIndex < 0) {
                continue;
            }

            ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);

            if (outputBuffer != null && bufferInfo.size > 0) {
                byte[] encoded = new byte[bufferInfo.size];

                outputBuffer.position(bufferInfo.offset);
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                outputBuffer.get(encoded);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    codecConfig = encoded;
                } else {
                    rememberCodecConfigFromAnnexB(encoded);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            && codecConfig != null) {
                        batchOutput.write(codecConfig, 0, codecConfig.length);
                    }

                    batchOutput.write(encoded, 0, encoded.length);
                }
            }

            encoder.releaseOutputBuffer(outputIndex, false);
        }
    }

    private void rememberCodecConfig(MediaFormat outputFormat) {
        ByteBuffer csd0 = outputFormat.getByteBuffer("csd-0");
        ByteBuffer csd1 = outputFormat.getByteBuffer("csd-1");

        if (csd0 == null || csd1 == null) {
            return;
        }

        byte[] sps = copyRemaining(csd0);
        byte[] pps = copyRemaining(csd1);

        codecConfig = new byte[sps.length + pps.length];

        System.arraycopy(sps, 0, codecConfig, 0, sps.length);
        System.arraycopy(pps, 0, codecConfig, sps.length, pps.length);

        Log.d(TAG, "H.264 encoder configured. width="
                + config.width
                + ", height=" + config.height
                + ", encoderColorFormat=" + encoderColorFormat);
    }

    private byte[] copyRemaining(ByteBuffer buffer) {
        ByteBuffer duplicate = buffer.duplicate();
        byte[] data = new byte[duplicate.remaining()];
        duplicate.get(data);
        return data;
    }

    private void rememberCodecConfigFromAnnexB(byte[] encoded) {
        if (encoded == null || encoded.length == 0) {
            return;
        }

        int spsStart = -1;
        int ppsStart = -1;
        int configEnd = -1;
        int offset = 0;

        while (true) {
            int start = findStartCode(encoded, offset);
            if (start < 0) {
                break;
            }

            int nalStart = start + startCodeLength(encoded, start);
            int next = findStartCode(encoded, nalStart);
            int nalEnd = next < 0 ? encoded.length : next;

            if (nalStart < nalEnd) {
                int nalType = encoded[nalStart] & 0x1f;

                if (nalType == 7 && spsStart < 0) {
                    spsStart = start;
                } else if (nalType == 8 && spsStart >= 0 && ppsStart < 0) {
                    ppsStart = start;
                    configEnd = nalEnd;
                } else if (nalType != 7 && nalType != 8 && ppsStart >= 0) {
                    configEnd = start;
                    break;
                }
            }

            if (next < 0) {
                break;
            }

            offset = next;
        }

        if (spsStart >= 0 && ppsStart >= 0 && configEnd > spsStart) {
            codecConfig = new byte[configEnd - spsStart];
            System.arraycopy(encoded, spsStart, codecConfig, 0, codecConfig.length);
        }
    }

    private boolean startsWithCodecConfig(byte[] encoded) {
        int start = findStartCode(encoded, 0);

        if (start < 0) {
            return false;
        }

        int nalStart = start + startCodeLength(encoded, start);

        if (nalStart >= encoded.length) {
            return false;
        }

        int nalType = encoded[nalStart] & 0x1f;

        return nalType == 7 || nalType == 8;
    }

    private int findStartCode(byte[] data, int fromIndex) {
        if (data == null) {
            return -1;
        }

        for (int i = Math.max(0, fromIndex); i <= data.length - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i;
                }

                if (i <= data.length - 4
                        && data[i + 2] == 0
                        && data[i + 3] == 1) {
                    return i;
                }
            }
        }

        return -1;
    }

    private int startCodeLength(byte[] data, int start) {
        return data[start + 2] == 1 ? 3 : 4;
    }

    /**
     * Converts the input YUV layout from CameraController into the layout expected by MediaCodec.
     *
     * This is not RGB conversion.
     * This only rearranges U/V plane order when necessary.
     */
    private byte[] adaptInputYuvToEncoderColorFormat(
            byte[] input,
            int encoderColorFormat,
            InputYuvFormat inputFormat,
            int width,
            int height) {

        boolean encoderWantsSemiPlanar = isSemiPlanar(encoderColorFormat);

        if (encoderWantsSemiPlanar) {
            return toNv12(input, inputFormat, width, height);
        }

        return toI420(input, inputFormat, width, height);
    }

    /**
     * Converts input into NV12:
     *
     * YYYYYYYY UVUVUVUV
     */
    private byte[] toNv12(
            byte[] input,
            InputYuvFormat inputFormat,
            int width,
            int height) {

        int frameSize = width * height;
        int totalSize = frameSize * 3 / 2;

        if (inputFormat == InputYuvFormat.NV12) {
            return copyExact(input, totalSize);
        }

        byte[] output = new byte[totalSize];

        System.arraycopy(input, 0, output, 0, frameSize);

        if (inputFormat == InputYuvFormat.NV21) {
            // NV21: VU VU VU
            // NV12: UV UV UV
            for (int i = frameSize; i < totalSize; i += 2) {
                output[i] = input[i + 1];       // U
                output[i + 1] = input[i];       // V
            }
            return output;
        }

        if (inputFormat == InputYuvFormat.I420) {
            // I420: Y + U plane + V plane
            int chromaSize = frameSize / 4;
            int uStart = frameSize;
            int vStart = frameSize + chromaSize;
            int out = frameSize;

            for (int i = 0; i < chromaSize; i++) {
                output[out++] = input[uStart + i];  // U
                output[out++] = input[vStart + i];  // V
            }

            return output;
        }

        return output;
    }

    /**
     * Converts input into I420:
     *
     * YYYYYYYY UUUU VVVV
     */
    private byte[] toI420(
            byte[] input,
            InputYuvFormat inputFormat,
            int width,
            int height) {

        int frameSize = width * height;
        int chromaSize = frameSize / 4;
        int totalSize = frameSize * 3 / 2;

        if (inputFormat == InputYuvFormat.I420) {
            return copyExact(input, totalSize);
        }

        byte[] output = new byte[totalSize];

        System.arraycopy(input, 0, output, 0, frameSize);

        int uStart = frameSize;
        int vStart = frameSize + chromaSize;

        if (inputFormat == InputYuvFormat.NV12) {
            // NV12: UV UV UV
            for (int i = 0; i < chromaSize; i++) {
                output[uStart + i] = input[frameSize + i * 2];       // U
                output[vStart + i] = input[frameSize + i * 2 + 1];   // V
            }
            return output;
        }

        if (inputFormat == InputYuvFormat.NV21) {
            // NV21: VU VU VU
            for (int i = 0; i < chromaSize; i++) {
                output[uStart + i] = input[frameSize + i * 2 + 1];   // U
                output[vStart + i] = input[frameSize + i * 2];       // V
            }
            return output;
        }

        return output;
    }

    private byte[] copyExact(byte[] input, int size) {
        if (input.length == size) {
            return input;
        }

        byte[] output = new byte[size];
        System.arraycopy(input, 0, output, 0, Math.min(input.length, size));
        return output;
    }

    private boolean isSemiPlanar(int outputColorFormat) {
        return outputColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                || outputColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar
                || outputColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
                || outputColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    }

    private static final class EncoderSelection {
        final MediaCodecInfo codecInfo;
        final int colorFormat;

        EncoderSelection(MediaCodecInfo codecInfo, int colorFormat) {
            this.codecInfo = codecInfo;
            this.colorFormat = colorFormat;
        }
    }
}
