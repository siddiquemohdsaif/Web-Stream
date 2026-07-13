package com.w3n.webstreamvulkantest.Server;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

public final class ReceivedJpegVulkanView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "RenderJPEG_X";

    static {
        System.loadLibrary("webstream_vulkan_test");
    }

    private final Object frameLock = new Object();
    private HandlerThread renderThread;
    private Handler renderHandler;
    private boolean surfaceReady;
    private Surface configuredSurface;
    private int configuredSurfaceWidth;
    private int configuredSurfaceHeight;
    private byte[] pendingYuv420;
    private int pendingWidth;
    private int pendingHeight;

    public ReceivedJpegVulkanView(Context context) {
        super(context);
        init();
    }

    public ReceivedJpegVulkanView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ReceivedJpegVulkanView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public void renderJpeg(byte[] jpegData) {
        if (jpegData == null || jpegData.length == 0) {
            Log.w(TAG, "renderJpeg skipped: empty jpegData");
            return;
        }
        Log.d(TAG, "renderJpeg received bytes=" + jpegData.length
                + " surfaceReady=" + surfaceReady
                + " viewSize=" + getWidth() + "x" + getHeight()
                + " visibility=" + getVisibility());
        ensureRenderThread();
        renderHandler.post(() -> {
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (bitmap == null) {
                Log.e(TAG, "JPEG decode failed bytes=" + jpegData.length);
                return;
            }
            Log.d(TAG, "JPEG decoded bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());
            try {
                YuvFrame frame = bitmapToYuv420(bitmap);
                if (frame != null) {
                    Log.d(TAG, "Bitmap converted to I420 width=" + frame.width
                            + " height=" + frame.height
                            + " bytes=" + frame.yuv420.length);
                    submitOrStore(frame);
                } else {
                    Log.e(TAG, "Bitmap to I420 returned null");
                }
            } finally {
                bitmap.recycle();
            }
        });
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated surface=" + holder.getSurface()
                + " viewSize=" + getWidth() + "x" + getHeight());
        setSurface(holder.getSurface(), getWidth(), getHeight());
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged format=" + format + " size=" + width + "x" + height);
        setSurface(holder.getSurface(), width, height);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed");
        surfaceReady = false;
        configuredSurface = null;
        configuredSurfaceWidth = 0;
        configuredSurfaceHeight = 0;
        nativeSetSurface(null, getContext().getAssets());
    }

    @Override
    protected void onDetachedFromWindow() {
        Log.d(TAG, "onDetachedFromWindow");
        surfaceReady = false;
        configuredSurface = null;
        configuredSurfaceWidth = 0;
        configuredSurfaceHeight = 0;
        nativeSetSurface(null, getContext().getAssets());
        nativeDestroy();
        stopRenderThread();
        super.onDetachedFromWindow();
    }

    private void init() {
        setZOrderOnTop(false);
        getHolder().addCallback(this);
    }

    private void setSurface(Surface surface, int width, int height) {
        if (surface == configuredSurface
                && width == configuredSurfaceWidth
                && height == configuredSurfaceHeight) {
            Log.d(TAG, "setSurface skipped duplicate surface size=" + width + "x" + height);
            return;
        }
        configuredSurface = surface;
        configuredSurfaceWidth = width;
        configuredSurfaceHeight = height;
        ensureRenderThread();
        renderHandler.post(() -> {
            String result = nativeSetSurface(surface, getContext().getAssets());
            surfaceReady = !result.startsWith("Error:");
            Log.d(TAG, "nativeSetSurface result=" + result + " surfaceReady=" + surfaceReady);
            submitPendingIfReady();
        });
    }

    private void submitOrStore(YuvFrame frame) {
        synchronized (frameLock) {
            pendingYuv420 = frame.yuv420;
            pendingWidth = frame.width;
            pendingHeight = frame.height;
        }
        Log.d(TAG, "Stored pending I420 frame=" + frame.width + "x" + frame.height
                + " surfaceReady=" + surfaceReady);
        submitPendingIfReady();
    }

    private void submitPendingIfReady() {
        if (!surfaceReady) {
            Log.d(TAG, "submitPendingIfReady waiting: surface not ready");
            return;
        }
        byte[] yuv420;
        int width;
        int height;
        synchronized (frameLock) {
            yuv420 = pendingYuv420;
            width = pendingWidth;
            height = pendingHeight;
            pendingYuv420 = null;
            pendingWidth = 0;
            pendingHeight = 0;
        }
        if (yuv420 != null && width > 0 && height > 0) {
            Log.d(TAG, "Submitting I420 to native width=" + width
                    + " height=" + height
                    + " bytes=" + yuv420.length);
            nativeSubmitYuv420(yuv420, width, height, 0, false);
        } else {
            Log.d(TAG, "submitPendingIfReady no pending frame");
        }
    }

    private void ensureRenderThread() {
        if (renderThread != null) {
            return;
        }
        renderThread = new HandlerThread("received-jpeg-vulkan-renderer");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
        Log.d(TAG, "Render thread started");
    }

    private void stopRenderThread() {
        HandlerThread thread = renderThread;
        renderThread = null;
        renderHandler = null;
        if (thread != null) {
            thread.quitSafely();
            Log.d(TAG, "Render thread stopped");
        }
    }

    private static YuvFrame bitmapToYuv420(Bitmap bitmap) {
        int width = bitmap.getWidth() & ~1;
        int height = bitmap.getHeight() & ~1;
        if (width <= 0 || height <= 0) {
            return null;
        }

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int ySize = width * height;
        int chromaSize = ySize / 4;
        byte[] yuv420 = new byte[ySize + chromaSize * 2];
        int uOffset = ySize;
        int vOffset = ySize + chromaSize;

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int argb = pixels[row * width + col];
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;

                int y = Math.round(0.299f * r + 0.587f * g + 0.114f * b);
                yuv420[row * width + col] = (byte) clamp(y);

                if ((row & 1) == 0 && (col & 1) == 0) {
                    int u = Math.round(-0.168736f * r - 0.331264f * g + 0.5f * b + 128f);
                    int v = Math.round(0.5f * r - 0.418688f * g - 0.081312f * b + 128f);
                    int chromaIndex = (row / 2) * (width / 2) + (col / 2);
                    yuv420[uOffset + chromaIndex] = (byte) clamp(u);
                    yuv420[vOffset + chromaIndex] = (byte) clamp(v);
                }
            }
        }
        return new YuvFrame(yuv420, width, height);
    }

    private static int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }

    private static native String nativeSetSurface(Surface surface, android.content.res.AssetManager assets);

    private static native void nativeSubmitYuv420(
            byte[] yuv420,
            int width,
            int height,
            int rotationDegrees,
            boolean mirror);

    private static native void nativeDestroy();

    private static final class YuvFrame {
        final byte[] yuv420;
        final int width;
        final int height;

        YuvFrame(byte[] yuv420, int width, int height) {
            this.yuv420 = yuv420;
            this.width = width;
            this.height = height;
        }
    }
}
