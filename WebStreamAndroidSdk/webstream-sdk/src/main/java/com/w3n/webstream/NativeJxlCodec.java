package com.w3n.webstream;

import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import java.nio.ByteBuffer;

public final class NativeJxlCodec {
    private static final String LIBRARY_NAME = "webstream_jxl";
    private static final boolean LIBRARY_LOADED = loadLibrary();

    private NativeJxlCodec() {
    }

    public static boolean isAvailable() {
        return LIBRARY_LOADED && nativeIsAvailable();
    }

    public static byte[] encode(Bitmap bitmap, int quality) {
        if (bitmap == null || bitmap.isRecycled() || !isAvailable()) {
            return null;
        }
        try {
            byte[] encodedData = nativeEncode(bitmap, clampQuality(quality));
            if (encodedData == null || encodedData.length == 0) {
                logDebug("Native JXL encode returned no frame bytes. Reason: " + lastError());
            }
            return encodedData;
        } catch (RuntimeException error) {
            logDebug("Native JXL encode failed: " + error.getMessage());
            return null;
        }
    }

    public static Bitmap decode(byte[] encodedData) {
        if (encodedData == null || encodedData.length == 0 || !isAvailable()) {
            return null;
        }
        try {
            DecodedImage image = nativeDecode(encodedData);
            if (image == null || image.rgba == null || image.rgba.length == 0
                    || image.width <= 0 || image.height <= 0) {
                logDebug("Native JXL decode returned no bitmap. Reason: " + lastError());
                return null;
            }
            Bitmap bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(image.rgba));
            return bitmap;
        } catch (RuntimeException error) {
            logDebug("Native JXL decode failed: " + error.getMessage());
            return null;
        }
    }

    public static String unavailableReason() {
        if (!LIBRARY_LOADED) {
            return "native library " + LIBRARY_NAME + " could not be loaded";
        }
        if (!nativeIsAvailable()) {
            return "native library " + LIBRARY_NAME + " was built without libjxl. "
                    + "Add libjxl at webstream-sdk/src/main/cpp/third_party/libjxl "
                    + "or build with -PwebstreamLibjxlRoot=/absolute/path/to/libjxl";
        }
        if (Build.VERSION.SDK_INT < 24) {
            return "Android API level is below the SDK minimum supported native path";
        }
        return null;
    }

    public static String lastError() {
        if (!LIBRARY_LOADED) {
            return "native library " + LIBRARY_NAME + " could not be loaded";
        }
        try {
            String error = nativeLastError();
            return error == null || error.isEmpty() ? "unknown native JXL error" : error;
        } catch (RuntimeException error) {
            return error.getMessage();
        }
    }

    private static boolean loadLibrary() {
        try {
            System.loadLibrary(LIBRARY_NAME);
            return true;
        } catch (UnsatisfiedLinkError error) {
            logDebug("Native JXL library load failed: " + error.getMessage());
            return false;
        }
    }

    private static void logDebug(String message) {
        try {
            Log.d(SdkConstants.TAG, message);
        } catch (RuntimeException ignored) {
            // Local JVM tests use android.jar stubs where Log.d is not implemented.
        }
    }

    private static int clampQuality(int quality) {
        if (quality < 0) {
            return 0;
        }
        return Math.min(quality, 100);
    }

    private static native boolean nativeIsAvailable();

    private static native byte[] nativeEncode(Bitmap bitmap, int quality);

    private static native DecodedImage nativeDecode(byte[] encodedData);

    private static native String nativeLastError();

    static final class DecodedImage {
        final int width;
        final int height;
        final byte[] rgba;

        DecodedImage(int width, int height, byte[] rgba) {
            this.width = width;
            this.height = height;
            this.rgba = rgba;
        }
    }
}
