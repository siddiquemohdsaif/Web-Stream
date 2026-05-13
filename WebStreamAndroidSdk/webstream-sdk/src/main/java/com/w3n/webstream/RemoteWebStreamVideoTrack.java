package com.w3n.webstream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.w3n.webstream.Util.H264FrameBatchDecoder;

final class RemoteWebStreamVideoTrack implements WebStreamVideoTrack {
    private static final int H264_MAX_QUEUED_FRAMES = 5;

    private final String participantId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread decodeThread = new HandlerThread("webstream-remote-decode");
    private final Handler decodeHandler;

    private WebStreamVideoView attachedView;
    private ImageView imageView;
    private GLSurfaceView h264GlSurfaceView;
    private YuvFrameRenderer h264Renderer;
    private H264FrameBatchDecoder h264Decoder;
    private int h264DecoderWidth;
    private int h264DecoderHeight;
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

        h264Renderer = new YuvFrameRenderer(1, 1);
        h264GlSurfaceView = new GLSurfaceView(view.getContext());
        h264GlSurfaceView.setEGLContextClientVersion(2);
        h264GlSurfaceView.setRenderer(h264Renderer);
        h264GlSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        h264GlSurfaceView.setBackgroundColor(0xFF050606);
        view.addView(h264GlSurfaceView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        if (latestBitmap != null) {
            imageView.setImageBitmap(latestBitmap);
        }
        imageView.setVisibility(enabled ? ImageView.VISIBLE : ImageView.INVISIBLE);
        h264GlSurfaceView.setVisibility(ImageView.INVISIBLE);
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
        if (h264GlSurfaceView != null) {
            h264GlSurfaceView.onPause();
            view.removeView(h264GlSurfaceView);
            h264GlSurfaceView = null;
            h264Renderer = null;
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
        if (h264GlSurfaceView != null && !enabled) {
            h264GlSurfaceView.setVisibility(ImageView.INVISIBLE);
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

    private void updateH264Frame(RemoteVideoFrame frame) {
        if (!ImageFormatSupport.canDecode(WebStreamCallOptions.ImageFormat.H264)) {
            Log.d(SdkConstants.TAG, "Skipping remote H.264 packet; decoder is not available.");
            return;
        }

        try {
            ensureH264Decoder(frame.getWidth(), frame.getHeight(), frame.getFrameRateFps());
            H264FrameBatchDecoder decoder = h264Decoder;
            if (decoder != null && decoder.isStarted()) {
                decoder.onDecodeChunk(frame.getEncodedData());
            }
        } catch (RuntimeException error) {
            Log.d(SdkConstants.TAG, "Remote H.264 decode failed. participantId="
                    + participantId + ", error=" + error.getMessage());
            releaseH264Decoder();
        }
    }

    private void ensureH264Decoder(int width, int height, int frameRateFps) {
        int resolvedWidth = Math.max(2, width);
        int resolvedHeight = Math.max(2, height);
        if ((resolvedWidth % 2) != 0) {
            resolvedWidth += 1;
        }
        if ((resolvedHeight % 2) != 0) {
            resolvedHeight += 1;
        }

        if (h264Decoder != null
                && h264Decoder.isStarted()
                && h264DecoderWidth == resolvedWidth
                && h264DecoderHeight == resolvedHeight) {
            return;
        }

        releaseH264Decoder();
        h264DecoderWidth = resolvedWidth;
        h264DecoderHeight = resolvedHeight;
        h264Decoder = new H264FrameBatchDecoder(
                new H264FrameBatchDecoder.Config.Builder()
                        .setSize(h264DecoderWidth, h264DecoderHeight)
                        .setFrameRateFps(frameRateFps)
                        .setMaxQueuedFrames(H264_MAX_QUEUED_FRAMES)
                        .build(),
                h264DecoderCallback);
        h264Decoder.start();
    }

    private final H264FrameBatchDecoder.Callback h264DecoderCallback =
            new H264FrameBatchDecoder.Callback() {
                @Override
                public void onImageAvailable(H264FrameBatchDecoder.DecodedFrame frame) {
                    YuvFrameRenderer renderer = h264Renderer;
                    GLSurfaceView surfaceView = h264GlSurfaceView;
                    if (released || renderer == null || surfaceView == null) {
                        if (frame != null) {
                            frame.release();
                        }
                        return;
                    }
                    renderer.updateFrame(frame);
                    surfaceView.requestRender();
                    if (imageView != null) {
                        imageView.setVisibility(ImageView.INVISIBLE);
                    }
                    if (enabled) {
                        surfaceView.setVisibility(ImageView.VISIBLE);
                    }
                }

                @Override
                public void onRawMediaCodecDecodeTimingAvailable(
                        long frameSequence,
                        long decodeDurationNs) {
                }

                @Override
                public void onDecoderError(Exception error) {
                    Log.d(SdkConstants.TAG, "Remote H.264 batch decoder error. participantId="
                            + participantId + ", error="
                            + (error == null ? "unknown" : error.getMessage()));
                    releaseH264Decoder();
                }
            };

    private void releaseH264Decoder() {
        if (h264Decoder != null) {
            h264Decoder.release();
            h264Decoder = null;
        }
        h264DecoderWidth = 0;
        h264DecoderHeight = 0;
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
        if (h264GlSurfaceView != null) {
            h264GlSurfaceView.setVisibility(ImageView.INVISIBLE);
        }
        if (previousBitmap != null && previousBitmap != bitmap) {
            previousBitmap.recycle();
        }
    }
}
