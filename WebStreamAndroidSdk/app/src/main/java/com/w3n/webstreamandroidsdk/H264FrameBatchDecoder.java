package com.w3n.webstreamandroidsdk;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
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

        private Config(Builder builder) {
            this.width = builder.width;
            this.height = builder.height;
            this.frameRateFps = Math.max(1, builder.frameRateFps);
            this.bitmapType = builder.bitmapType == null
                    ? BitmapType.RGB_888
                    : builder.bitmapType;
            this.imageReaderMaxImages = Math.max(2, builder.imageReaderMaxImages);
            this.jpegQuality = Math.max(1, Math.min(100, builder.jpegQuality));
        }

        public static final class Builder {
            private int width = 640;
            private int height = 480;
            private int frameRateFps = 15;
            private BitmapType bitmapType = BitmapType.RGB_888;
            private int imageReaderMaxImages = 4;
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
    private static final int DEQUEUE_TIMEOUT_US = 0;

    private final Config config;
    private final Callback callback;

    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private final Queue<DecodedFrame> decodedFrameQueue = new ArrayDeque<>();

    private HandlerThread decoderThread;
    private Handler decoderHandler;

    private HandlerThread renderThread;
    private Handler renderHandler;

    private MediaCodec decoder;
    private ImageReader imageReader;

    private long inputChunkSequence;
    private long decodedFrameSequence;
    private long renderedFrameSequence;

    private boolean initialized;
    private boolean started;
    private boolean released;

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
     * Creates MediaCodec decoder and ImageReader output.
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

            imageReader = ImageReader.newInstance(
                    config.width,
                    config.height,
                    ImageFormat.YUV_420_888,
                    config.imageReaderMaxImages
            );

            imageReader.setOnImageAvailableListener(
                    this::handleDecodedImageAvailable,
                    getDecoderHandler()
            );

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

        if (decoder == null || imageReader == null) {
            callback.onDecoderError(new IllegalStateException("Decoder initialization failed."));
            return;
        }

        try {
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    config.width,
                    config.height
            );

            decoder.configure(
                    format,
                    imageReader.getSurface(),
                    null,
                    0
            );

            decoder.start();

            inputChunkSequence = 0L;
            decodedFrameSequence = 0L;
            renderedFrameSequence = 0L;

            synchronized (this) {
                decodedFrameQueue.clear();
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
     * Encoder gives 3 chunks per second.
     * Decoder accepts those chunks immediately, decodes all frames,
     * then emits decoded images at configured FPS.
     */
    public void onDecodeChunk(byte[] encodedChunk) {
        if (released || !started || decoder == null) {
            return;
        }

        if (encodedChunk == null || encodedChunk.length == 0) {
            return;
        }

        Handler handler = getDecoderHandler();

        handler.post(() -> decodeChunkInternal(encodedChunk));
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
                decodedFrameQueue.clear();
            }

            initialized = false;

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

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

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

    private void drainDecoder() {
        if (decoder == null) {
            return;
        }

        while (true) {
            int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US);

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outputFormat = decoder.getOutputFormat();

                Log.d(TAG,
                        "H.264 decoder output format changed. format="
                                + outputFormat);

                continue;
            }

            if (outputIndex < 0) {
                continue;
            }

            boolean renderToImageReader = bufferInfo.size > 0;

            decoder.releaseOutputBuffer(
                    outputIndex,
                    renderToImageReader
            );
        }
    }

    /**
     * 6. onImageAvailable
     *
     * Internal ImageReader callback.
     *
     * MediaCodec renders decoded frames into ImageReader.
     * We convert them into Bitmap and queue them.
     * Public callback is fired later by renderRunnable at configured FPS.
     */
    private void handleDecodedImageAvailable(ImageReader reader) {
        Image image = null;

        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            byte[] nv21 = imageToNv21(image);

            Bitmap bitmap = nv21ToBitmap(
                    nv21,
                    image.getWidth(),
                    image.getHeight(),
                    config.jpegQuality
            );

            DecodedFrame frame = new DecodedFrame(
                    image.getWidth(),
                    image.getHeight(),
                    image.getTimestamp(),
                    ++decodedFrameSequence,
                    bitmap
            );

            synchronized (this) {
                decodedFrameQueue.offer(frame);
            }

        } catch (Exception error) {
            callback.onDecoderError(error);
        } finally {
            if (image != null) {
                image.close();
            }
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

    /**
     * Converts Android ImageFormat.YUV_420_888 into NV21.
     *
     * Same idea as CameraController imageToNv21().
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
}