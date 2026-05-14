package com.w3n.webstream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.w3n.webstream.Util.H264FrameBatchDecoder;

final class RemoteWebStreamVideoTrack implements WebStreamVideoTrack {
    private static final int H264_MAX_QUEUED_FRAMES = 5;
    private static final int REMOTE_FRAME_ROTATION_DEGREES = 270;

    private final String participantId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread decodeThread = new HandlerThread("webstream-remote-decode");
    private final Handler decodeHandler;

    private WebStreamVideoView attachedView;
    private RemoteVideoTextureView remoteTextureView;
    private H264FrameBatchDecoder h264Decoder;
    private int h264DecoderWidth;
    private int h264DecoderHeight;
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

        remoteTextureView = new RemoteVideoTextureView(view.getContext(), REMOTE_FRAME_ROTATION_DEGREES);
        view.addView(remoteTextureView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        remoteTextureView.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void detach(WebStreamVideoView view) {
        if (view == null || view != attachedView) {
            return;
        }
        Log.d(SdkConstants.TAG, "Detaching remote video track. participantId=" + participantId);
        if (remoteTextureView != null) {
            remoteTextureView.releaseRenderer();
            view.removeView(remoteTextureView);
            remoteTextureView = null;
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
        if (remoteTextureView != null) {
            remoteTextureView.setVisibility(enabled
                    ? View.VISIBLE
                    : View.INVISIBLE);
            if (!enabled) {
                remoteTextureView.clearFrame();
            }
        }
    }

    void release() {
        released = true;
        Log.d(SdkConstants.TAG, "Releasing remote video track. participantId=" + participantId);
        detach(attachedView);
        decodeThread.quitSafely();
        releaseH264Decoder();
    }

    private void updateH264Frame(RemoteVideoFrame frame) {
        if (!ImageFormatSupport.canDecode(WebStreamCallOptions.ImageFormat.H264)) {
            Log.d("DECODER_PARVEZ", "Skipping remote H.264 packet; decoder is not available.");
            return;
        }
        if (frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            Log.d("DECODER_PARVEZ", "Skipping remote H.264 packet with missing dimensions. participantId="
                    + participantId + ", width=" + frame.getWidth() + ", height=" + frame.getHeight());
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
                    Log.d("DECODER_PARVEZ", "onImageAvailable: " + frame);
                    RemoteVideoTextureView textureView = remoteTextureView;
                    if (released || textureView == null) {
                        if (frame != null) {
                            frame.release();
                        }
                        return;
                    }
                    if (enabled) {
                        textureView.setVisibility(View.VISIBLE);
                        textureView.renderDecodedFrame(frame);
                    } else if (frame != null) {
                        frame.release();
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
        RemoteVideoTextureView textureView = remoteTextureView;
        if (textureView == null) {
            bitmap.recycle();
            return;
        }

        if (enabled) {
            textureView.setVisibility(View.VISIBLE);
            textureView.renderBitmap(bitmap, REMOTE_FRAME_ROTATION_DEGREES);
        } else {
            bitmap.recycle();
        }
    }
}
