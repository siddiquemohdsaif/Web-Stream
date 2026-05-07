package app.builderx.ogfa.videoapichecker.Util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class StorageUtil {
    private static final String APP_FOLDER = "VideoAPIChecker";
    private static final String PROVIDER_AGORA = "Agora";
    private static final String PROVIDER_HUNDRED_MS = "100ms";
    private static final int BUFFER_SIZE = 8 * 1024;

    private StorageUtil() {
    }

    public interface SaveCallback {
        void onSaved(@NonNull Uri uri);

        void onError(@NonNull Exception exception);
    }

    @NonNull
    public static Uri saveAgoraRemoteVideo(
            @NonNull Context context,
            @NonNull byte[] videoBytes
    ) throws IOException {
        return saveRemoteVideo(context, PROVIDER_AGORA, videoBytes, "mp4", "video/mp4");
    }

    @NonNull
    public static Uri saveHundredMsRemoteVideo(
            @NonNull Context context,
            @NonNull byte[] videoBytes
    ) throws IOException {
        return saveRemoteVideo(context, PROVIDER_HUNDRED_MS, videoBytes, "mp4", "video/mp4");
    }

    @NonNull
    public static Uri saveAgoraRemoteVideo(
            @NonNull Context context,
            @NonNull InputStream inputStream
    ) throws IOException {
        return saveRemoteVideo(context, PROVIDER_AGORA, inputStream, "mp4", "video/mp4");
    }

    @NonNull
    public static Uri saveHundredMsRemoteVideo(
            @NonNull Context context,
            @NonNull InputStream inputStream
    ) throws IOException {
        return saveRemoteVideo(context, PROVIDER_HUNDRED_MS, inputStream, "mp4", "video/mp4");
    }

    @NonNull
    public static Uri saveRemoteVideo(
            @NonNull Context context,
            @NonNull String providerName,
            @NonNull byte[] videoBytes,
            @NonNull String extension,
            @NonNull String mimeType
    ) throws IOException {
        Uri uri = createMediaUri(
                context,
                providerName,
                extension,
                mimeType,
                Environment.DIRECTORY_MOVIES);
        boolean success = false;

        try (OutputStream outputStream = openOutputStream(context, uri)) {
            outputStream.write(videoBytes);
            success = true;
            return uri;
        } finally {
            finishMediaWrite(context, uri, success);
        }
    }

    @NonNull
    public static Uri saveRemoteVideo(
            @NonNull Context context,
            @NonNull String providerName,
            @NonNull InputStream inputStream,
            @NonNull String extension,
            @NonNull String mimeType
    ) throws IOException {
        Uri uri = createMediaUri(
                context,
                providerName,
                extension,
                mimeType,
                Environment.DIRECTORY_MOVIES);
        boolean success = false;

        try (OutputStream outputStream = openOutputStream(context, uri)) {
            copy(inputStream, outputStream);
            success = true;
            return uri;
        } finally {
            finishMediaWrite(context, uri, success);
        }
    }

    public static void saveAgoraRemoteFrame(
            @NonNull Context context,
            @NonNull SurfaceView remoteVideoView,
            @NonNull SaveCallback callback
    ) {
        saveRemoteFrame(context, PROVIDER_AGORA, remoteVideoView, callback);
    }

    public static void saveHundredMsRemoteFrame(
            @NonNull Context context,
            @NonNull View remoteVideoView,
            @NonNull SaveCallback callback
    ) {
        saveRemoteFrame(context, PROVIDER_HUNDRED_MS, remoteVideoView, callback);
    }

    public static void saveRemoteFrame(
            @NonNull Context context,
            @NonNull String providerName,
            @NonNull View remoteVideoView,
            @NonNull SaveCallback callback
    ) {
        if (remoteVideoView.getWidth() <= 0 || remoteVideoView.getHeight() <= 0) {
            callback.onError(new IllegalStateException("Remote video view is not ready yet."));
            return;
        }

        if (remoteVideoView instanceof TextureView) {
            Bitmap bitmap = ((TextureView) remoteVideoView).getBitmap();
            if (bitmap == null) {
                callback.onError(new IllegalStateException("Could not capture remote video frame."));
                return;
            }
            saveFrameBitmap(context, providerName, bitmap, callback);
            return;
        }

        Bitmap bitmap = Bitmap.createBitmap(
                remoteVideoView.getWidth(),
                remoteVideoView.getHeight(),
                Bitmap.Config.ARGB_8888);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && remoteVideoView instanceof SurfaceView) {
            PixelCopy.request(
                    (SurfaceView) remoteVideoView,
                    bitmap,
                    result -> {
                        if (result == PixelCopy.SUCCESS) {
                            saveFrameBitmap(context, providerName, bitmap, callback);
                        } else {
                            bitmap.recycle();
                            callback.onError(new IOException("PixelCopy failed with code " + result));
                        }
                    },
                    new Handler(Looper.getMainLooper()));
            return;
        }

        Canvas canvas = new Canvas(bitmap);
        remoteVideoView.draw(canvas);
        saveFrameBitmap(context, providerName, bitmap, callback);
    }

    @NonNull
    public static Uri createRemoteVideoUri(
            @NonNull Context context,
            @NonNull String providerName
    ) throws IOException {
        return createMediaUri(
                context,
                providerName,
                "mp4",
                "video/mp4",
                Environment.DIRECTORY_MOVIES);
    }

    @NonNull
    public static OutputStream openRemoteVideoOutputStream(
            @NonNull Context context,
            @NonNull Uri uri
    ) throws IOException {
        return openOutputStream(context, uri);
    }

    public static void completeRemoteVideoWrite(@NonNull Context context, @NonNull Uri uri) {
        finishMediaWrite(context, uri, true);
    }

    public static void discardRemoteVideoWrite(@NonNull Context context, @NonNull Uri uri) {
        finishMediaWrite(context, uri, false);
    }

    private static void saveFrameBitmap(
            @NonNull Context context,
            @NonNull String providerName,
            @NonNull Bitmap bitmap,
            @NonNull SaveCallback callback
    ) {
        Uri uri = null;
        boolean success = false;

        try {
            uri = createMediaUri(
                    context,
                    providerName + "_Frame",
                    "png",
                    "image/png",
                    Environment.DIRECTORY_PICTURES);

            try (OutputStream outputStream = openOutputStream(context, uri)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                    throw new IOException("Could not encode remote video frame.");
                }
            }

            success = true;
            callback.onSaved(uri);
        } catch (Exception exception) {
            callback.onError(exception);
        } finally {
            bitmap.recycle();
            if (uri != null) {
                finishMediaWrite(context, uri, success);
            }
        }
    }

    @NonNull
    private static Uri createMediaUri(
            @NonNull Context context,
            @NonNull String providerName,
            @NonNull String extension,
            @NonNull String mimeType,
            @NonNull String directory
    ) throws IOException {
        String cleanExtension = extension.startsWith(".") ? extension.substring(1) : extension;
        String fileName = cleanName(providerName)
                + "_remote_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + "."
                + cleanExtension;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory + "/" + APP_FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }

        Uri collection = isVideoMimeType(mimeType)
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = context.getContentResolver().insert(collection, values);

        if (uri == null) {
            throw new IOException("Could not create storage entry for " + fileName);
        }

        return uri;
    }

    @NonNull
    private static OutputStream openOutputStream(@NonNull Context context, @NonNull Uri uri) throws IOException {
        OutputStream outputStream = context.getContentResolver().openOutputStream(uri);
        if (outputStream == null) {
            throw new IOException("Could not open storage stream.");
        }
        return outputStream;
    }

    private static void finishMediaWrite(
            @NonNull Context context,
            @NonNull Uri uri,
            boolean success
    ) {
        ContentResolver resolver = context.getContentResolver();

        if (success) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            }
        } else {
            resolver.delete(uri, null, null);
        }
    }

    private static void copy(@NonNull InputStream inputStream, @NonNull OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
    }

    @NonNull
    private static String cleanName(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return "Remote";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static boolean isVideoMimeType(@NonNull String mimeType) {
        return mimeType.toLowerCase(Locale.US).startsWith("video/");
    }
}
