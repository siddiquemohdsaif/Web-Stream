package com.w3n.webstream.Util;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
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

    public enum BitmapType {
        RGB_888
    }

    public interface Callback {
        /**
         * Called at configured FPS.
         *
         * Example:
         * fps = 15 means this callback fires every ~66.7 ms if decoded frames are available.
         */
        void onImageAvailable(DecodedFrame frame);

        /**
         * Raw MediaCodec decode time.
         *
         * Measures:
         * queueInputBuffer(...)
         *      ->
         * dequeueOutputBuffer(...) returns decoded output
         */
        void onRawMediaCodecDecodeTimingAvailable(
                long frameSequence,
                long decodeDurationNs);

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
        public final BitmapType bitmapType;
        public final int imageReaderMaxImages;
        public final int jpegQuality;
        public final int maxQueuedFrames;

        private Config(Builder builder) {
            this.width = builder.width;
            this.height = builder.height;
            this.frameRateFps = Math.max(1, builder.frameRateFps);
            this.bitmapType = builder.bitmapType == null
                    ? BitmapType.RGB_888
                    : builder.bitmapType;
            this.imageReaderMaxImages = Math.max(2, builder.imageReaderMaxImages);
            this.jpegQuality = Math.max(1, Math.min(100, builder.jpegQuality));
            this.maxQueuedFrames = Math.max(1, builder.maxQueuedFrames);
        }

        public static final class Builder {
            private int width = 640;
            private int height = 480;
            private int frameRateFps = 15;
            private BitmapType bitmapType = BitmapType.RGB_888;
            private int imageReaderMaxImages = 4;
            private int jpegQuality = 80;
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

            public Builder setBitmapType(BitmapType bitmapType) {
                this.bitmapType = bitmapType == null
                        ? BitmapType.RGB_888
                        : bitmapType;
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

            public Builder setMaxQueuedFrames(int maxQueuedFrames) {
                this.maxQueuedFrames = Math.max(1, maxQueuedFrames);
                return this;
            }

            public Config build() {
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("Decoder width and height must be positive.");
                }

                if ((width % 2) != 0 || (height % 2) != 0) {
                    throw new IllegalArgumentException("H.264 width and height must be even values.");
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
        public final Bitmap bitmap;

        private DecodedFrame(
                int width,
                int height,
                long timestampNs,
                long sequence,
                Bitmap bitmap) {
            this.width = width;
            this.height = height;
            this.timestampNs = timestampNs;
            this.sequence = sequence;
            this.bitmap = bitmap;
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
     *
     * Key:
     * presentationTimeUs passed into queueInputBuffer(...)
     *
     * Value:
     * System.nanoTime() immediately before queueInputBuffer(...)
     */
    private final Map<Long, Long> rawDecodeStartTimeByPresentationUs = new HashMap<>();

    private HandlerThread decoderThread;
    private Handler decoderHandler;

    private HandlerThread renderThread;
    private Handler renderHandler;

    private MediaCodec decoder;
    private MediaFormat outputFormat;

    private long inputChunkSequence;
    private long decodedFrameSequence;
    private long renderedFrameSequence;
    private long handledBatchSequence;

    private boolean initialized;
    private boolean started;
    private boolean released;


    public static volatile long decoderFrameReceived =0;
    public static volatile long dispatchedFrames =0 ;
    public static volatile long imagesOnBuffer =0;

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

                dispatchedFrames ++;
                Log.d("DECODER_P", "run: decoderFrameReceived " +decoderFrameReceived+
                        " dispatchedFrames " +dispatchedFrames+
                        " imagesOnBuffer "+imagesOnBuffer);
                callback.onImageAvailable(frame);
            }

            if (renderHandler != null) {
                renderHandler.postDelayed(this, getFrameDelayMs());
            }
        }
    };

    public H264FrameBatchDecoder(Config config, Callback callback) {
        if (config == null) {
            throw new IllegalArgumentException("H264FrameBatchDecoder.Config cannot be null.");
        }

        if (callback == null) {
            throw new IllegalArgumentException("H264FrameBatchDecoder.Callback cannot be null.");
        }

        this.config = config;
        this.callback = callback;
    }

    /**
     * 1. Initialization
     *
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
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            initialized = true;
        } catch (Exception error) {
            callback.onDecoderError(error);
        }
    }

    /**
     * 2. Start
     *
     * Starts decoder and render clock.
     */
    public synchronized void start() {
        if (released) {
            callback.onDecoderError(new IllegalStateException("Decoder is already released."));
            return;
        }

        if (started) {
            return;
        }

        if (!initialized) {
            initialize();
        }

        if (decoder == null) {
            callback.onDecoderError(new IllegalStateException("Decoder initialization failed."));
            return;
        }

        try {
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    config.width,
                    config.height
            );

            format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            );

            decoder.configure(
                    format,
                    null,
                    null,
                    0
            );

            decoder.start();

            inputChunkSequence = 0L;
            decodedFrameSequence = 0L;
            renderedFrameSequence = 0L;
            handledBatchSequence = 0L;

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
     *
     * Called from outside with encoder output.
     *
     * Example:
     * Encoder gives frameRateFps / batchFrameCount chunks per second.
     * Decoder accepts those chunks immediately, decodes all frames,
     * then emits decoded images at configured FPS.
     */
    private static volatile long frameDecoded = 0;
    public void onDecodeChunk(byte[] encodedChunk) {
        if (released || !started || decoder == null) {
            return;
        }

        if (encodedChunk == null || encodedChunk.length == 0) {
            return;
        }

        decoderFrameReceived += 5;

        Handler handler = getDecoderHandler();

        handler.post(() -> {
            long batchStartNs = System.nanoTime();
            List<byte[]> accessUnits = splitAnnexBAccessUnits(encodedChunk);

            for (byte[] accessUnit : accessUnits) {
                decodeChunkInternal(accessUnit);
            }

            frameDecoded = 0;
            drainDecoder();
            Log.d("DECODER_P", "onDecodeChunk: frameDecoded "+ frameDecoded);
            frameDecoded = 0;

            long handledDurationNs = System.nanoTime() - batchStartNs;
            long batchSequence = ++handledBatchSequence;
            int queuedDecodedFrameCount;
            long decodedFrameCount;

            synchronized (H264FrameBatchDecoder.this) {
                queuedDecodedFrameCount = decodedFrameQueue.size();
                decodedFrameCount = decodedFrameSequence;
            }

            Log.d(TAG,
                    "H.264 decode batch handled. batchSequence="
                            + batchSequence
                            + ", accessUnits=" + accessUnits.size()
                            + ", durationMs="
                            + String.format(
                            Locale.US,
                            "%.3f",
                            handledDurationNs / 1_000_000.0
                    )
                            + ", decodedFrames=" + decodedFrameCount
                            + ", queuedDecodedFrames=" + queuedDecodedFrameCount);

            callback.onDecodeBatchHandled(
                    batchSequence,
                    accessUnits.size(),
                    handledDurationNs,
                    decodedFrameCount,
                    queuedDecodedFrameCount
            );
        });
    }

    /**
     * 4. Stop
     *
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
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (RuntimeException ignored) {
                }

                try {
                    decoder.release();
                } catch (RuntimeException ignored) {
                }

                decoder = null;
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
     *
     * Fully destroys decoder.
     */
    public synchronized void release() {
        if (released) {
            return;
        }

        stop();
        stopDecoderThread();
        stopRenderThread();

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

    private void decodeChunkInternal(byte[] encodedChunk) {
        if (released || !started || decoder == null) {
            return;
        }

        try {
            inputChunkSequence++;

            drainDecoder();

            int inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);

            if (inputIndex < 0) {
                return;
            }

            ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);

            if (inputBuffer == null) {
                return;
            }

            inputBuffer.clear();

            if (encodedChunk.length > inputBuffer.remaining()) {
                throw new IllegalStateException(
                        "H.264 decoder input buffer is too small. required="
                                + encodedChunk.length
                                + ", available="
                                + inputBuffer.remaining()
                );
            }

            inputBuffer.put(encodedChunk);

            long presentationTimeUs = System.nanoTime() / 1000L;

            /**
             * Raw MediaCodec decode start time.
             *
             * This is captured immediately before queueInputBuffer().
             */
            long rawDecodeStartNs = System.nanoTime();

            synchronized (rawDecodeStartTimeByPresentationUs) {
                rawDecodeStartTimeByPresentationUs.put(
                        presentationTimeUs,
                        rawDecodeStartNs
                );
            }

            decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    encodedChunk.length,
                    presentationTimeUs,
                    0
            );

            drainDecoder();

        } catch (Exception error) {
            callback.onDecoderError(error);
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
                parameterSets.write(encodedChunk, nalUnit.start, nalUnit.end - nalUnit.start);
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

            currentAccessUnit.write(encodedChunk, nalUnit.start, nalUnit.end - nalUnit.start);

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
        // The first bit of first_mb_in_slice is 1 only when the value is 0.
        // That is the common single-slice frame boundary produced by MediaCodec encoders.
        int firstSliceByteIndex = nalUnit.nalHeaderIndex + 1;
        return firstSliceByteIndex < nalUnit.end
                && (data[firstSliceByteIndex] & 0x80) != 0;
    }

    private void drainDecoder() {
        if (decoder == null) {
            return;
        }

        while (true) {
            int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US);

            /**
             * Capture end time immediately after dequeueOutputBuffer returns.
             *
             * If outputIndex >= 0, MediaCodec has produced decoded output.
             */
            long rawDecodeEndNs = System.nanoTime();
            long h264DecodeStart = System.currentTimeMillis();
            long rawDecodeStart = System.currentTimeMillis();


            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = decoder.getOutputFormat();

                Log.d(TAG,
                        "H.264 decoder output format changed. format="
                                + outputFormat);

                continue;
            }

            if (outputIndex < 0) {
                continue;
            }

            if (bufferInfo.size > 0) {
                Long rawDecodeStartNs;

                synchronized (rawDecodeStartTimeByPresentationUs) {
                    rawDecodeStartNs = rawDecodeStartTimeByPresentationUs.remove(
                            bufferInfo.presentationTimeUs
                    );
                }

                if (rawDecodeStartNs != null) {
                    long rawDecodeDurationNs = rawDecodeEndNs - rawDecodeStartNs;

                    callback.onRawMediaCodecDecodeTimingAvailable(
                            decodedFrameSequence + 1,
                            rawDecodeDurationNs
                    );

                    Log.d(TAG,
                            "Raw MediaCodec decode timing. presentationTimeUs="
                                    + bufferInfo.presentationTimeUs
                                    + ", durationMs="
                                    + String.format(
                                    Locale.US,
                                    "%.3f",
                                    rawDecodeDurationNs / 1_000_000.0
                            ));
                }

                ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                if (outputBuffer != null) {
                    MediaFormat frameFormat = decoder.getOutputFormat(outputIndex);
                    if (frameFormat == null) {
                        frameFormat = outputFormat;
                    }
                    long h264DecodeEnd = System.currentTimeMillis();
                    Log.d("DECODER_S", "drainDecoder:time taken for h264 decode  "+(h264DecodeEnd-h264DecodeStart));
                    queueDecodedBuffer(outputBuffer, frameFormat);
                }
            }

            long decoderReleaseStart = System.currentTimeMillis();
            decoder.releaseOutputBuffer(
                    outputIndex,
                    false
            );
            Log.d("DECODER_S", "drainDecoder: decoder ReleaseTime "+(System.currentTimeMillis() - decoderReleaseStart));
            Log.d("DECODER_S", "drainDecoder: Drain decoder Time "+(System.currentTimeMillis() - rawDecodeStart));
        }
    }

    private void queueDecodedBuffer(
            ByteBuffer outputBuffer,
            MediaFormat frameFormat) {
        try {
            long nv21Start = System.currentTimeMillis();
            byte[] nv21 = codecOutputToNv21(outputBuffer, frameFormat);

            Bitmap bitmap = nv21ToBitmap(
                    nv21,
                    config.width,
                    config.height,
                    config.jpegQuality
            );

            DecodedFrame frame = new DecodedFrame(
                    config.width,
                    config.height,
                    bufferInfo.presentationTimeUs * 1000L,
                    ++decodedFrameSequence,
                    bitmap
            );
            long nv21End = System.currentTimeMillis();
            Log.d("DECODER_S", "queueDecodedBuffer: time taken to convert nv21  "+(nv21End - nv21Start));

            synchronized (this) {
                long imageOfferStart = System.currentTimeMillis();
                while (decodedFrameQueue.size() >= config.maxQueuedFrames) {
                    DecodedFrame droppedFrame = decodedFrameQueue.poll();
                    if (droppedFrame == null) {
                        break;
                    }

                    recycleFrameBitmap(droppedFrame);
                    callback.onDecodedFrameDropped(
                            droppedFrame.sequence,
                            decodedFrameQueue.size());
                }

                frameDecoded ++ ;
                imagesOnBuffer ++ ;
                decodedFrameQueue.offer(frame);
                Log.d("DECODER_S", "queueDecodedBuffer: ImageOffering time"+(System.currentTimeMillis() - imageOfferStart));
            }

        } catch (Exception error) {
            callback.onDecoderError(error);
        }
    }

    private void recycleFrameBitmap(DecodedFrame frame) {
        if (frame != null && frame.bitmap != null && !frame.bitmap.isRecycled()) {
            frame.bitmap.recycle();
        }
    }

    private void clearDecodedFrameQueue() {
        while (!decodedFrameQueue.isEmpty()) {
            recycleFrameBitmap(decodedFrameQueue.poll());
        }
    }

    private long getFrameDelayMs() {
        return Math.max(1L, Math.round(1000.0 / config.frameRateFps));
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

    private byte[] codecOutputToNv21(
            ByteBuffer outputBuffer,
            MediaFormat frameFormat) {
        ByteBuffer duplicate = outputBuffer.duplicate();
        duplicate.position(bufferInfo.offset);
        duplicate.limit(bufferInfo.offset + bufferInfo.size);

        byte[] data = new byte[duplicate.remaining()];
        duplicate.get(data);

        int width = config.width;
        int height = config.height;
        int stride = getFormatInteger(frameFormat, "stride", width);
        int sliceHeight = getFormatInteger(frameFormat, "slice-height", height);
        int colorFormat = getFormatInteger(
                frameFormat,
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

        byte[] nv21 = new byte[width * height * 3 / 2];
        int yOutput = 0;

        for (int row = 0; row < height; row++) {
            int yInput = row * stride;
            if (yInput + width <= data.length) {
                System.arraycopy(data, yInput, nv21, yOutput, width);
            }
            yOutput += width;
        }

        int frameSize = width * height;
        int yPlaneSize = stride * sliceHeight;
        boolean planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                || colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar;

        if (planar) {
            copyPlanarChromaToNv21(data, nv21, width, height, stride, yPlaneSize, frameSize);
        } else {
            copySemiPlanarChromaToNv21(data, nv21, width, height, stride, yPlaneSize, frameSize);
        }

        return nv21;
    }

    private void copySemiPlanarChromaToNv21(
            byte[] data,
            byte[] nv21,
            int width,
            int height,
            int stride,
            int yPlaneSize,
            int frameSize) {
        int out = frameSize;
        int uvHeight = height / 2;
        int uvWidth = width / 2;

        for (int row = 0; row < uvHeight; row++) {
            int rowStart = yPlaneSize + row * stride;

            for (int col = 0; col < uvWidth; col++) {
                int input = rowStart + col * 2;
                if (input + 1 >= data.length || out + 1 >= nv21.length) {
                    return;
                }

                byte u = data[input];
                byte v = data[input + 1];

                nv21[out++] = v;
                nv21[out++] = u;
            }
        }
    }

    private void copyPlanarChromaToNv21(
            byte[] data,
            byte[] nv21,
            int width,
            int height,
            int stride,
            int yPlaneSize,
            int frameSize) {
        int chromaStride = Math.max(1, stride / 2);
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        int chromaPlaneSize = chromaStride * Math.max(chromaHeight, 1);
        int uPlaneStart = yPlaneSize;
        int vPlaneStart = yPlaneSize + chromaPlaneSize;
        int out = frameSize;

        for (int row = 0; row < chromaHeight; row++) {
            int uRowStart = uPlaneStart + row * chromaStride;
            int vRowStart = vPlaneStart + row * chromaStride;

            for (int col = 0; col < chromaWidth; col++) {
                int uIndex = uRowStart + col;
                int vIndex = vRowStart + col;
                if (uIndex >= data.length || vIndex >= data.length || out + 1 >= nv21.length) {
                    return;
                }

                nv21[out++] = data[vIndex];
                nv21[out++] = data[uIndex];
            }
        }
    }

    private int getFormatInteger(
            MediaFormat format,
            String key,
            int defaultValue) {
        if (format == null || !format.containsKey(key)) {
            return defaultValue;
        }

        try {
            return format.getInteger(key);
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
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
                null
        );

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        yuvImage.compressToJpeg(
                new Rect(0, 0, width, height),
                jpegQuality,
                outputStream
        );

        byte[] jpegBytes = outputStream.toByteArray();

        Bitmap decodedBitmap = android.graphics.BitmapFactory.decodeByteArray(
                jpegBytes,
                0,
                jpegBytes.length
        );

        if (decodedBitmap == null) {
            return null;
        }

        return decodedBitmap.copy(Bitmap.Config.ARGB_8888, false);
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
                int type) {
            this.start = start;
            this.end = end;
            this.nalHeaderIndex = nalHeaderIndex;
            this.type = type;
        }
    }
}
