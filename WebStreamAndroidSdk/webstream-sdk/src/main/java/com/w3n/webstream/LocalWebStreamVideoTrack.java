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

import com.w3n.webstream.Util.CameraController;

import java.io.ByteArrayOutputStream;

final class LocalWebStreamVideoTrack implements WebStreamVideoTrack {
    private static final int H264_BATCH_FRAME_COUNT = 5;

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
    private LocalVideoPreviewView localPreviewView;
    private HandlerThread frameThread;
    private Handler frameHandler;
    private CameraController cameraController;
    private FrameListener frameListener;
    private com.w3n.webstream.Util.H264FrameBatchEncoder h264FrameBatchEncoder;
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
        localPreviewView = new LocalVideoPreviewView(
                view.getContext(),
                view.getLocalPreviewRotationDegrees());
        view.addView(localPreviewView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        localPreviewView.bringToFront();
        Log.d("ENCODER_PARVEZ", "Local preview view attached. parent="
                + view.getWidth() + "x" + view.getHeight()
                + ", child=" + localPreviewView.getWidth() + "x" + localPreviewView.getHeight());
        localPreviewView.post(() -> Log.d("ENCODER_PARVEZ", "Local preview view laid out. parent="
                + view.getWidth() + "x" + view.getHeight()
                + ", child=" + localPreviewView.getWidth() + "x" + localPreviewView.getHeight()));
        startCamera();
    }

    @Override
    public void detach(WebStreamVideoView view) {
        if (view == null || view != attachedView) {
            return;
        }
        Log.d(SdkConstants.TAG, "Detaching local video track. participantId=" + participantId);
        stopCamera();
        if (localPreviewView != null) {
            localPreviewView.clearFrame();
            view.removeView(localPreviewView);
            localPreviewView = null;
        }
        releaseH264Encoder();
        attachedView = null;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.d(SdkConstants.TAG, "Local video track enabled=" + enabled);
        if (!enabled) {
            stopCamera();
            if (localPreviewView != null) {
                localPreviewView.clearFrame();
            }
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

    void setPreviewRotationDegrees(int rotationDegrees) {
        if (localPreviewView != null) {
            localPreviewView.setPreviewRotationDegrees(rotationDegrees);
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
                        .setImageReaderMaxImages(H264_BATCH_FRAME_COUNT * 2)
                        .setFrameType(shouldUseH264BatchEncoding()
                                ? CameraController.FrameType.IMAGE_READER_NV12
                                : CameraController.FrameType.BITMAP)
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
                    Log.d("ENCODER_PARVEZ", "onImageFrameAvailable:frameType "+frame.toString());
                    handleCameraFrame(frame);
                }

                @Override
                public void onCameraError(Exception error) {
                    Log.d(SdkConstants.TAG, "Camera controller error: " + error.getMessage());
                }
            };

    private void handleCameraFrame(CameraController.CameraFrame frame) {
        if (frame == null) {
            return;
        }
        if (!enabled || released) {
            recycleFrameBitmap(frame);
            return;
        }

        if (shouldUseH264BatchEncoding()) {
            updateLocalPreview(frame);
            encodeAndDispatchH264Frame(frame);
            return;
        }

        if (frame.bitmap == null) {
            return;
        }

        if (frameEncodeInProgress || frameHandler == null || frameListener == null) {
            frame.bitmap.recycle();
            return;
        }

        long timestampMs = frame.timestampNs > 0
                ? frame.timestampNs / 1_000_000L
                : System.currentTimeMillis();
        frameEncodeInProgress = true;
        Log.d("DECODER_QQ", "handleCameraFrame: jpeg frame available");
        frameHandler.post(() -> encodeAndDispatchFrame(
                frame.bitmap,
                options.getVideoWidth(),
                options.getVideoHeight(),
                timestampMs,
                frame.sequence));
    }

    private boolean shouldUseH264BatchEncoding() {
        Log.d("DECODER_QQ", "shouldUseH264BatchEncoding: Image Supported "+ ImageFormatSupport.resolveEncodableFormat(options.getImageFormat())+" match "+(ImageFormatSupport.resolveEncodableFormat(options.getImageFormat())
                == WebStreamCallOptions.ImageFormat.H264)+" forceJpegFallback "+ forceJpegFallback);
        return !forceJpegFallback
                && ImageFormatSupport.resolveEncodableFormat(options.getImageFormat())
                == WebStreamCallOptions.ImageFormat.H264;
    }

    private void encodeAndDispatchH264Frame(CameraController.CameraFrame frame) {
        if (frame.yuv420Data == null || frame.yuv420Data.length == 0) {
            recycleFrameBitmap(frame);
            return;
        }

        try {
            ensureH264BatchEncoder();
            long timestampMs = frame.timestampNs > 0
                    ? frame.timestampNs / 1_000_000L
                    : System.currentTimeMillis();
            h264FrameBatchEncoder.encodeFrame(frame.yuv420Data, timestampMs);
        } catch (RuntimeException error) {
            handleH264EncoderFailure(error);
        } finally {
            recycleFrameBitmap(frame);
        }
    }

    private void ensureH264BatchEncoder() {
        if (h264FrameBatchEncoder != null && h264FrameBatchEncoder.isStarted()) {
            return;
        }

        releaseH264Encoder();
        h264FrameBatchEncoder = new com.w3n.webstream.Util.H264FrameBatchEncoder(
                new com.w3n.webstream.Util.H264FrameBatchEncoder.Config.Builder()
                        .setSize(options.getVideoWidth(), options.getVideoHeight())
                        .setFrameRateFps(options.getFrameRateFps())
                        .setBitrateKbps(options.getBitrateKbps())
                        .setBatchFrameCount(H264_BATCH_FRAME_COUNT)
                        .setInputYuvFormat(
                                com.w3n.webstream.Util.H264FrameBatchEncoder.InputYuvFormat.NV12)
                        .build(),
                h264EncoderCallback);
        h264FrameBatchEncoder.start();
    }

    private final com.w3n.webstream.Util.H264FrameBatchEncoder.Callback h264EncoderCallback =
            new com.w3n.webstream.Util.H264FrameBatchEncoder.Callback() {
                @Override
                public void onEncodedFrameBatchAvailable(
                        byte[] encodedBatch,
                        int width,
                        int height,
                        long timestampMs,
                        long batchSequence) {
                    FrameListener listener = frameListener;
                    if (listener == null || released || !enabled
                            || encodedBatch == null || encodedBatch.length == 0) {
                        return;
                    }
                    storeSingleH264Packet(encodedBatch, width, height, batchSequence);
                    Log.d("DECODER_QQ", "onEncodedFrameBatchAvailable: H264 Encoded and Saved");
                    listener.onFrame(
                            encodedBatch,
                            WebStreamCallOptions.ImageFormat.H264,
                            width,
                            height,
                            timestampMs,
                            batchSequence);
                }

                @Override
                public void onEncoderError(Exception error) {
                    handleH264EncoderFailure(error);
                }
            };

    private void handleH264EncoderFailure(Exception error) {
        releaseH264Encoder();
        forceJpegFallback = true;
        if (!loggedImageFormatFallback) {
            loggedImageFormatFallback = true;
            Log.d(SdkConstants.TAG, "H.264 batch encoding failed on this phone/build. Reason: "
                    + (error == null ? "unknown" : error.getMessage())
                    + ". Using image fallback for outgoing video frames.");
        }
        mainHandler.post(() -> {
            if (!released && enabled && attachedView != null) {
                stopCamera();
                startCamera();
            }
        });
    }

    private void recycleFrameBitmap(CameraController.CameraFrame frame) {
        if (frame != null && frame.bitmap != null) {
            frame.bitmap.recycle();
        }
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
        if (bitmap == null || released || !enabled || localPreviewView == null) {
            return;
        }

        Bitmap previewBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        mainHandler.post(() -> {
            if (released || localPreviewView == null) {
                previewBitmap.recycle();
                return;
            }

            localPreviewView.renderBitmap(previewBitmap);
        });
    }

    private void updateLocalPreview(CameraController.CameraFrame frame) {
        if (frame == null || released || !enabled || localPreviewView == null) {
            return;
        }

        mainHandler.post(() -> {
            if (released || localPreviewView == null) {
                return;
            }

            localPreviewView.renderCameraFrame(frame);
        });
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
