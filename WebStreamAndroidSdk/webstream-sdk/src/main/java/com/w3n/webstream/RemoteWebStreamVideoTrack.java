package com.w3n.webstream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.IOException;
import java.nio.ByteBuffer;

final class RemoteWebStreamVideoTrack implements WebStreamVideoTrack {
    private final String participantId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread decodeThread = new HandlerThread("webstream-remote-decode");
    private final Handler decodeHandler;

    private WebStreamVideoView attachedView;
    private ImageView imageView;
    private TextureView h264TextureView;
    private Surface h264Surface;
    private MediaCodec h264Decoder;
    private int h264Width;
    private int h264Height;
    private byte[] h264Sps;
    private byte[] h264Pps;
    private long h264RenderedFrames;
    private boolean loggedH264WaitingForSurface;
    private boolean loggedH264WaitingForConfig;
    private Bitmap latestBitmap;
    private boolean enabled = true;
    private boolean released;

    RemoteWebStreamVideoTrack(String participantId) {
        this.participantId = participantId;
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());
    }

    @Override
    public boolean isLocal() {
        return false;
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
        Log.d(SdkConstants.TAG, "Attaching remote video track. participantId=" + participantId);
        detach(attachedView);
        attachedView = view;
        imageView = new ImageView(view.getContext());
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setAdjustViewBounds(false);
        imageView.setBackgroundColor(0xFF050606);
        view.addView(imageView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        h264TextureView = new TextureView(view.getContext());
        h264TextureView.setSurfaceTextureListener(h264SurfaceTextureListener);
        view.addView(h264TextureView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (latestBitmap != null) {
            imageView.setImageBitmap(latestBitmap);
        }
        imageView.setVisibility(enabled ? ImageView.VISIBLE : ImageView.INVISIBLE);
        h264TextureView.setVisibility(enabled ? ImageView.VISIBLE : ImageView.INVISIBLE);
    }

    @Override
    public void detach(WebStreamVideoView view) {
        if (view == null || view != attachedView) {
            return;
        }
        Log.d(SdkConstants.TAG, "Detaching remote video track. participantId=" + participantId);
        if (imageView != null) {
            imageView.setImageBitmap(null);
            view.removeView(imageView);
            imageView = null;
        }
        if (h264TextureView != null) {
            h264TextureView.setSurfaceTextureListener(null);
            view.removeView(h264TextureView);
            h264TextureView = null;
        }
        releaseH264Decoder();
        attachedView = null;
    }

    void updateFrame(RemoteVideoFrame frame) {
        if (released || !enabled || frame == null
                || frame.getEncodedData() == null || frame.getEncodedData().length == 0) {
            return;
        }
        WebStreamCallOptions.ImageFormat imageFormat = frame.getImageFormat();
        if (imageFormat == WebStreamCallOptions.ImageFormat.H264) {
            updateH264Frame(frame);
            return;
        }
        if (!ImageFormatSupport.canDecode(imageFormat)) {
            Log.d(SdkConstants.TAG, "Skipping remote " + imageFormat.getWireName()
                    + " frame; decoder is not available on this phone/build. Reason: "
                    + ImageFormatSupport.unsupportedReason(imageFormat) + ".");
            return;
        }
        byte[] encodedData = frame.getEncodedData();
        decodeHandler.post(() -> {
            Bitmap bitmap;
            if (imageFormat == WebStreamCallOptions.ImageFormat.JXL) {
                bitmap = NativeJxlCodec.decode(encodedData);
            } else {
                bitmap = BitmapFactory.decodeByteArray(encodedData, 0, encodedData.length);
            }
            if (bitmap == null) {
                return;
            }
            mainHandler.post(() -> applyBitmap(bitmap));
        });
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (imageView != null) {
            imageView.setVisibility(enabled ? ImageView.VISIBLE : ImageView.INVISIBLE);
        }
        if (h264TextureView != null) {
            h264TextureView.setVisibility(enabled ? h264TextureView.getVisibility() : ImageView.INVISIBLE);
        }
        if (!enabled && imageView != null) {
            imageView.setImageBitmap(null);
        } else if (enabled && imageView != null && latestBitmap != null) {
            imageView.setImageBitmap(latestBitmap);
        }
    }

    void release() {
        released = true;
        Log.d(SdkConstants.TAG, "Releasing remote video track. participantId=" + participantId);
        detach(attachedView);
        decodeThread.quitSafely();
        releaseH264Decoder();
        if (latestBitmap != null) {
            latestBitmap.recycle();
            latestBitmap = null;
        }
    }

    private final TextureView.SurfaceTextureListener h264SurfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    decodeHandler.post(() -> {
                        releaseH264DecoderOnly();
                        releaseH264Surface();
                        h264Surface = new Surface(surface);
                    });
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    decodeHandler.post(() -> {
                        releaseH264DecoderOnly();
                        releaseH264Surface();
                    });
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

    private void updateH264Frame(RemoteVideoFrame frame) {
        if (!ImageFormatSupport.canDecode(WebStreamCallOptions.ImageFormat.H264)) {
            Log.d(SdkConstants.TAG, "Skipping remote H.264 packet; decoder is not available.");
            return;
        }
        byte[] encodedData = frame.getEncodedData();
        decodeHandler.post(() -> decodeH264Frame(encodedData, frame.getWidth(), frame.getHeight(), frame.getTimestampMs()));
    }

    private void decodeH264Frame(byte[] encodedData, int width, int height, long timestampMs) {
        if (released || h264Surface == null || encodedData == null || encodedData.length == 0) {
            if (h264Surface == null && !loggedH264WaitingForSurface) {
                loggedH264WaitingForSurface = true;
                Log.d(SdkConstants.TAG, "Remote H.264 renderer is waiting for TextureView surface.");
            }
            return;
        }
        try {
            rememberH264CodecConfig(encodedData);
            if (!ensureH264Decoder(width, height)) {
                return;
            }
            feedH264AccessUnits(encodedData, timestampMs);
            mainHandler.post(() -> {
                if (imageView != null) {
                    imageView.setVisibility(ImageView.INVISIBLE);
                }
                if (h264TextureView != null && enabled) {
                    h264TextureView.setVisibility(ImageView.VISIBLE);
                }
            });
        } catch (IOException | RuntimeException error) {
            Log.d(SdkConstants.TAG, "Remote H.264 decode failed. participantId="
                    + participantId + ", error=" + error.getMessage());
            releaseH264Decoder();
        }
    }

    private void feedH264AccessUnits(byte[] encodedData, long timestampMs) {
        int offset = 0;
        boolean queued = false;
        while (true) {
            int start = findStartCode(encodedData, offset);
            if (start < 0) {
                break;
            }
            int nalStart = start + startCodeLength(encodedData, start);
            int next = findStartCode(encodedData, nalStart);
            int nalEnd = next < 0 ? encodedData.length : next;
            if (nalStart < nalEnd) {
                int nalType = nalType(encodedData, nalStart);
                if (isRenderableH264Nal(nalType)) {
                    queueH264Input(encodedData, start, nalEnd - start, timestampMs, nalType);
                    queued = true;
                    drainH264Decoder();
                }
            }
            if (next < 0) {
                break;
            }
            offset = next;
        }
        if (!queued) {
            queueH264Input(encodedData, 0, encodedData.length, timestampMs, -1);
            drainH264Decoder();
        }
    }

    private void queueH264Input(
            byte[] encodedData,
            int offset,
            int length,
            long timestampMs,
            int nalType) {
        if (h264Decoder == null || length <= 0) {
            return;
        }
        int inputIndex = h264Decoder.dequeueInputBuffer(10_000);
        if (inputIndex < 0) {
            return;
        }
        ByteBuffer inputBuffer = h264Decoder.getInputBuffer(inputIndex);
        if (inputBuffer == null) {
            h264Decoder.queueInputBuffer(inputIndex, 0, 0, timestampMs * 1000L, 0);
            return;
        }
        inputBuffer.clear();
        if (length > inputBuffer.remaining()) {
            Log.d(SdkConstants.TAG, "Remote H.264 NAL is too large for decoder input buffer.");
            h264Decoder.queueInputBuffer(inputIndex, 0, 0, timestampMs * 1000L, 0);
            return;
        }
        inputBuffer.put(encodedData, offset, length);
        int flags = nalType == 5 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
        h264Decoder.queueInputBuffer(inputIndex, 0, length, timestampMs * 1000L, flags);
    }

    private void rememberH264CodecConfig(byte[] encodedData) {
        int offset = 0;
        while (true) {
            int start = findStartCode(encodedData, offset);
            if (start < 0) {
                break;
            }
            int nalStart = start + startCodeLength(encodedData, start);
            int next = findStartCode(encodedData, nalStart);
            int nalEnd = next < 0 ? encodedData.length : next;
            if (nalStart < nalEnd) {
                int nalType = nalType(encodedData, nalStart);
                if (nalType == 7) {
                    h264Sps = copyRange(encodedData, start, nalEnd);
                } else if (nalType == 8) {
                    h264Pps = copyRange(encodedData, start, nalEnd);
                }
            }
            if (next < 0) {
                break;
            }
            offset = next;
        }
    }

    private byte[] copyRange(byte[] data, int start, int end) {
        byte[] output = new byte[end - start];
        System.arraycopy(data, start, output, 0, output.length);
        return output;
    }

    private boolean isRenderableH264Nal(int nalType) {
        return nalType == 1 || nalType == 5;
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
                if (i <= data.length - 4 && data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int startCodeLength(byte[] data, int start) {
        return data[start + 2] == 1 ? 3 : 4;
    }

    private int nalType(byte[] data, int nalStart) {
        return nalStart >= 0 && nalStart < data.length ? data[nalStart] & 0x1f : -1;
    }

    private boolean ensureH264Decoder(int width, int height) throws IOException {
        int resolvedWidth = Math.max(1, width);
        int resolvedHeight = Math.max(1, height);
        if (h264Decoder != null && h264Width == resolvedWidth && h264Height == resolvedHeight) {
            return true;
        }
        if (h264Surface == null) {
            return false;
        }
        if (h264Sps == null || h264Pps == null) {
            if (!loggedH264WaitingForConfig) {
                loggedH264WaitingForConfig = true;
                Log.d(SdkConstants.TAG, "Remote H.264 renderer is waiting for SPS/PPS before decoder start.");
            }
            return false;
        }
        releaseH264DecoderOnly();
        h264Width = resolvedWidth;
        h264Height = resolvedHeight;
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                h264Width,
                h264Height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(h264Sps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(h264Pps));
        h264Decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        h264Decoder.configure(format, h264Surface, null, 0);
        h264Decoder.start();
        h264RenderedFrames = 0L;
        Log.d(SdkConstants.TAG, "Remote H.264 decoder started. participantId="
                + participantId + ", width=" + h264Width + ", height=" + h264Height);
        return true;
    }

    private void drainH264Decoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (h264Decoder != null) {
            int outputIndex = h264Decoder.dequeueOutputBuffer(bufferInfo, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (outputIndex >= 0) {
                h264Decoder.releaseOutputBuffer(outputIndex, true);
                h264RenderedFrames += 1;
                if (h264RenderedFrames == 1L) {
                    Log.d(SdkConstants.TAG, "Remote H.264 decoder rendered first frame. participantId="
                            + participantId);
                }
            }
        }
    }

    private void releaseH264Decoder() {
        releaseH264DecoderOnly();
        releaseH264Surface();
        h264Sps = null;
        h264Pps = null;
    }

    private void releaseH264DecoderOnly() {
        if (h264Decoder != null) {
            try {
                h264Decoder.stop();
            } catch (RuntimeException ignored) {
            }
            h264Decoder.release();
            h264Decoder = null;
        }
        h264Width = 0;
        h264Height = 0;
        h264RenderedFrames = 0L;
    }

    private void releaseH264Surface() {
        if (h264Surface != null) {
            h264Surface.release();
            h264Surface = null;
        }
    }

    private void applyBitmap(Bitmap bitmap) {
        if (released) {
            bitmap.recycle();
            return;
        }
        Bitmap previousBitmap = latestBitmap;
        latestBitmap = bitmap;
        if (imageView != null) {
            imageView.setVisibility(enabled ? ImageView.VISIBLE : ImageView.INVISIBLE);
            imageView.setImageBitmap(bitmap);
        }
        if (h264TextureView != null) {
            h264TextureView.setVisibility(ImageView.INVISIBLE);
        }
        if (previousBitmap != null && previousBitmap != bitmap) {
            previousBitmap.recycle();
        }
    }
}
