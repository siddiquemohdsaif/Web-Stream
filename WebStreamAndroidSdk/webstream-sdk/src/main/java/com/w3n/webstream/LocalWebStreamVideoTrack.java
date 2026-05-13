package com.w3n.webstream;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.w3n.webstream.Util.CameraController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class LocalWebStreamVideoTrack implements WebStreamVideoTrack {
    interface FrameListener {
        void onFrame(
                byte[] encodedData,
                WebStreamCallOptions.ImageFormat imageFormat,
                int width,
                int height,
                long timestampMs,
                long sequence);
    }

    private final Context applicationContext;
    private final String participantId;
    private final WebStreamCallOptions options;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebStreamVideoView attachedView;
    private ImageView localPreviewImageView;
    private Bitmap latestLocalPreviewBitmap;
    private HandlerThread frameThread;
    private Handler frameHandler;
    private CameraController cameraController;
    private FrameListener frameListener;
    private H264FrameBatchEncoder h264FrameBatchEncoder;
    private boolean enabled = true;
    private boolean released;
    private boolean useFrontCamera = true;
    private boolean loggedImageFormatFallback;
    private boolean forceJpegFallback;
    private int singlePacketStoreCount;
    private volatile boolean frameEncodeInProgress;

    LocalWebStreamVideoTrack(
            Context applicationContext,
            String participantId,
            WebStreamCallOptions options) {
        this.applicationContext = applicationContext.getApplicationContext();
        this.participantId = participantId;
        this.options = options == null ? WebStreamCallOptions.defaultOptions() : options;
    }

    @Override
    public boolean isLocal() {
        return true;
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
        Log.d(SdkConstants.TAG, "Attaching local video track. participantId=" + participantId);
        detach(attachedView);
        attachedView = view;
        localPreviewImageView = new ImageView(view.getContext());
        localPreviewImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        localPreviewImageView.setAdjustViewBounds(false);
        localPreviewImageView.setBackgroundColor(0xFF050606);
        view.addView(localPreviewImageView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        startCamera();
    }

    @Override
    public void detach(WebStreamVideoView view) {
        if (view == null || view != attachedView) {
            return;
        }
        Log.d(SdkConstants.TAG, "Detaching local video track. participantId=" + participantId);
        stopCamera();
        if (localPreviewImageView != null) {
            view.removeView(localPreviewImageView);
            localPreviewImageView.setImageBitmap(null);
            localPreviewImageView = null;
        }
        recycleLatestLocalPreviewBitmap();
        releaseH264Encoder();
        attachedView = null;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.d(SdkConstants.TAG, "Local video track enabled=" + enabled);
        if (!enabled) {
            stopCamera();
            if (localPreviewImageView != null) {
                localPreviewImageView.setImageBitmap(null);
            }
            recycleLatestLocalPreviewBitmap();
            return;
        }
        startCamera();
    }

    void switchCamera() {
        if (released) {
            return;
        }
        useFrontCamera = !useFrontCamera;
        Log.d(SdkConstants.TAG, "Switching camera. useFrontCamera=" + useFrontCamera);
        stopCamera();
        if (enabled && attachedView != null) {
            startCamera();
        }
    }

    void release() {
        released = true;
        Log.d(SdkConstants.TAG, "Releasing local video track. participantId=" + participantId);
        detach(attachedView);
    }

    void setFrameListener(FrameListener frameListener) {
        this.frameListener = frameListener;
    }

    private void startCamera() {
        if (!enabled || released || attachedView == null || cameraController != null) {
            return;
        }
        if (applicationContext.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(SdkConstants.TAG, "Camera start skipped; permission missing.");
            return;
        }

        Log.d(SdkConstants.TAG, "Starting camera controller. useFrontCamera=" + useFrontCamera);
        startFrameThread();
        cameraController = new CameraController(
                applicationContext,
                new CameraController.Config.Builder()
                        .setSize(options.getVideoWidth(), options.getVideoHeight())
                        .setFrameRateFps(options.getFrameRateFps())
                        .setFrameType(CameraController.FrameType.BITMAP)
                        .setCameraFacing(useFrontCamera
                                ? CameraController.CameraFacing.FRONT
                                : CameraController.CameraFacing.BACK)
                        .setJpegQuality(options.getJpegQuality())
                        .build(),
                cameraCallback);
        cameraController.start();
    }

    private void stopCamera() {
        CameraController controller = cameraController;
        cameraController = null;
        if (controller != null) {
            Log.d(SdkConstants.TAG, "Releasing camera controller.");
            controller.release();
        }
        frameEncodeInProgress = false;
        if (frameThread != null) {
            Log.d(SdkConstants.TAG, "Stopping frame relay thread.");
            frameThread.quitSafely();
            frameThread = null;
            frameHandler = null;
        }
    }

    private final CameraController.CameraCallback cameraCallback =
            new CameraController.CameraCallback() {
                @Override
                public void onImageFrameAvailable(CameraController.CameraFrame frame) {
                    handleCameraFrame(frame);
                }

                @Override
                public void onCameraError(Exception error) {
                    Log.d(SdkConstants.TAG, "Camera controller error: " + error.getMessage());
                }
            };

    private void handleCameraFrame(CameraController.CameraFrame frame) {
        if (frame == null || frame.bitmap == null) {
            return;
        }
        if (!enabled || released) {
            frame.bitmap.recycle();
            return;
        }
        if (frameEncodeInProgress || frameHandler == null || frameListener == null) {
            frame.bitmap.recycle();
            return;
        }

        long timestampMs = frame.timestampNs > 0
                ? frame.timestampNs / 1_000_000L
                : System.currentTimeMillis();
        Bitmap orientedBitmap = rotateBitmap(frame.bitmap, -90f);
        frameEncodeInProgress = true;
        frameHandler.post(() -> encodeAndDispatchFrame(
                orientedBitmap,
                options.getVideoWidth(),
                options.getVideoHeight(),
                timestampMs,
                frame.sequence));
    }

    private Bitmap rotateBitmap(Bitmap source, float degrees) {
        if (source == null || degrees == 0f) {
            return source;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(
                source,
                0,
                0,
                source.getWidth(),
                source.getHeight(),
                matrix,
                true);
        if (rotated != source) {
            source.recycle();
        }
        return rotated;
    }

    private void encodeAndDispatchFrame(
            Bitmap bitmap,
            int outputWidth,
            int outputHeight,
            long timestampMs,
            long sequence) {
        Bitmap encodedBitmap = centerCropScale(bitmap, outputWidth, outputHeight);
        try {
            WebStreamCallOptions.ImageFormat requestedFormat = options.getImageFormat();
            WebStreamCallOptions.ImageFormat encodedFormat =
                    forceJpegFallback
                            ? WebStreamCallOptions.ImageFormat.JPEG
                            : ImageFormatSupport.resolveEncodableFormat(requestedFormat);
            if (requestedFormat != encodedFormat && !loggedImageFormatFallback) {
                loggedImageFormatFallback = true;
                Log.d(SdkConstants.TAG, "Image format " + requestedFormat.getWireName()
                        + " is not supported by this phone/build. "
                        + "Reason: " + ImageFormatSupport.unsupportedReason(requestedFormat) + ". "
                        + "Using " + encodedFormat.getWireName()
                        + " fallback for outgoing video frames.");
            }
            if (encodedFormat == WebStreamCallOptions.ImageFormat.H264) {
                byte[] encodedData = encodeH264Batch(encodedBitmap, timestampMs);
                updateLocalPreview(encodedBitmap);
                FrameListener listener = frameListener;
                if (listener != null && !released && enabled
                        && encodedData != null && encodedData.length > 0) {
                    storeSingleH264Packet(
                            encodedData,
                            encodedBitmap.getWidth(),
                            encodedBitmap.getHeight(),
                            sequence);
                    listener.onFrame(
                            encodedData,
                            WebStreamCallOptions.ImageFormat.H264,
                            encodedBitmap.getWidth(),
                            encodedBitmap.getHeight(),
                            timestampMs,
                            sequence);
                }
                if (encodedData != null && encodedData.length > 0) {
                    return;
                }
                if (!forceJpegFallback) {
                    return;
                }
                encodedFormat = WebStreamCallOptions.ImageFormat.JPEG;
            }
            byte[] encodedData = encodeBitmap(encodedBitmap, encodedFormat);
            if (encodedData == null || encodedData.length == 0) {
                WebStreamCallOptions.ImageFormat failedFormat = encodedFormat;
                encodedFormat = WebStreamCallOptions.ImageFormat.JPEG;
                if (failedFormat != encodedFormat && !loggedImageFormatFallback) {
                    loggedImageFormatFallback = true;
                    Log.d(SdkConstants.TAG, "Image format " + failedFormat.getWireName()
                            + " failed while encoding on this phone/build. Reason: "
                            + NativeJxlCodec.lastError() + ". "
                            + "Using JPEG fallback for outgoing video frames.");
                }
                encodedData = encodeBitmap(encodedBitmap, encodedFormat);
                if ((encodedData == null || encodedData.length == 0) && !loggedImageFormatFallback) {
                    loggedImageFormatFallback = true;
                    Log.d(SdkConstants.TAG, "Unable to encode outgoing video frame; no image "
                            + "format produced usable bytes.");
                }
            }
            updateLocalPreview(encodedBitmap);
            FrameListener listener = frameListener;
            if (listener != null && !released && enabled && encodedData != null && encodedData.length > 0) {
                listener.onFrame(
                        encodedData,
                        encodedFormat,
                        encodedBitmap.getWidth(),
                        encodedBitmap.getHeight(),
                        timestampMs,
                        sequence);
            }
        } finally {
            frameEncodeInProgress = false;
            if (encodedBitmap != bitmap) {
                encodedBitmap.recycle();
            }
            bitmap.recycle();
        }
    }

    private byte[] encodeH264Batch(Bitmap bitmap, long timestampMs) {
        try {
            if (h264FrameBatchEncoder == null) {
                h264FrameBatchEncoder = new H264FrameBatchEncoder(
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        options.getFrameRateFps(),
                        options.getBitrateKbps());
            }
            return h264FrameBatchEncoder.encodeFrame(bitmap, timestampMs);
        } catch (IOException | RuntimeException error) {
            releaseH264Encoder();
            forceJpegFallback = true;
            if (!loggedImageFormatFallback) {
                loggedImageFormatFallback = true;
                Log.d(SdkConstants.TAG, "H.264 encoding failed on this phone/build. Reason: "
                        + error.getMessage() + ". Using image fallback for outgoing video frames.");
            }
            return null;
        }
    }

    private void storeSingleH264Packet(byte[] encodedData, int width, int height, long sequence) {
        if (singlePacketStoreCount >= 10) {
            return;
        }
        singlePacketStoreCount += 1;
        SingleH264PacketMp4Store.save(
                applicationContext,
                participantId,
                encodedData,
                width,
                height,
                options.getFrameRateFps(),
                sequence);
    }

    private byte[] encodeBitmap(
            Bitmap bitmap,
            WebStreamCallOptions.ImageFormat imageFormat) {
        if (imageFormat == WebStreamCallOptions.ImageFormat.JXL) {
            return NativeJxlCodec.encode(bitmap, options.getJpegQuality());
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, options.getJpegQuality(), outputStream)) {
            return null;
        }
        return outputStream.toByteArray();
    }

    private void updateLocalPreview(Bitmap bitmap) {
        if (bitmap == null || released || !enabled || localPreviewImageView == null) {
            return;
        }

        Bitmap previewBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        mainHandler.post(() -> {
            if (released || localPreviewImageView == null) {
                previewBitmap.recycle();
                return;
            }

            Bitmap previousBitmap = latestLocalPreviewBitmap;
            latestLocalPreviewBitmap = previewBitmap;
            localPreviewImageView.setImageBitmap(previewBitmap);
            if (previousBitmap != null && previousBitmap != previewBitmap) {
                previousBitmap.recycle();
            }
        });
    }

    private void recycleLatestLocalPreviewBitmap() {
        Bitmap bitmap = latestLocalPreviewBitmap;
        latestLocalPreviewBitmap = null;
        if (bitmap != null) {
            bitmap.recycle();
        }
    }

    private void releaseH264Encoder() {
        if (h264FrameBatchEncoder != null) {
            h264FrameBatchEncoder.release();
            h264FrameBatchEncoder = null;
        }
    }

    private Bitmap centerCropScale(Bitmap source, int outputWidth, int outputHeight) {
        if (outputWidth <= 0 || outputHeight <= 0) {
            return source;
        }

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth == outputWidth && sourceHeight == outputHeight) {
            return source;
        }

        float sourceAspect = sourceWidth / (float) sourceHeight;
        float outputAspect = outputWidth / (float) outputHeight;
        int cropWidth = sourceWidth;
        int cropHeight = sourceHeight;
        int cropLeft = 0;
        int cropTop = 0;

        if (sourceAspect > outputAspect) {
            cropWidth = Math.round(sourceHeight * outputAspect);
            cropLeft = Math.max(0, (sourceWidth - cropWidth) / 2);
        } else if (sourceAspect < outputAspect) {
            cropHeight = Math.round(sourceWidth / outputAspect);
            cropTop = Math.max(0, (sourceHeight - cropHeight) / 2);
        }

        Bitmap output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Rect sourceRect = new Rect(
                cropLeft,
                cropTop,
                cropLeft + cropWidth,
                cropTop + cropHeight);
        RectF outputRect = new RectF(0, 0, outputWidth, outputHeight);
        canvas.drawBitmap(source, sourceRect, outputRect, null);
        return output;
    }

    private void startFrameThread() {
        if (frameThread != null) {
            return;
        }
        Log.d(SdkConstants.TAG, "Starting frame relay thread.");
        frameThread = new HandlerThread("webstream-frame-relay");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
    }
}
