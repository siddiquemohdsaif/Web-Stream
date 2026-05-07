package app.builderx.ogfa.videoapichecker.conversion;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.w3n.webstream.NativeJxlCodec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.builderx.ogfa.videoapichecker.R;

public class VideoConversionActivity extends AppCompatActivity {
    private static final String REQUESTED_SOURCE_FOLDER = "WebStreamVideo";
    private static final String DOWNLOAD_ROOT = "VideoApiChecker/VideoConversion";
    private static final String REPORT_FILE_PREFIX = "conversion_report_";
    private static final int DEFAULT_FRAME_RATE = 15;
    private static final int JPEG_QUALITY = 95;
    private static final int JXL_QUALITY = 95;
    private static final int READ_VIDEO_PERMISSION_REQUEST = 2001;

    private final ExecutorService conversionExecutor = Executors.newSingleThreadExecutor();

    private Button convertJpegButton;
    private Button convertJxlButton;
    private TextView sourceText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_video_conversion);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.videoConversionRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left + dp(24), systemBars.top + dp(24),
                    systemBars.right + dp(24), systemBars.bottom + dp(24));
            return insets;
        });

        sourceText = findViewById(R.id.videoConversionSourceText);
        statusText = findViewById(R.id.videoConversionStatusText);
        convertJpegButton = findViewById(R.id.convertJpegButton);
        convertJxlButton = findViewById(R.id.convertJxlButton);

        refreshLatestVideoText();

        convertJpegButton.setOnClickListener(v -> convertLatestVideo(OutputFormat.JPEG));
        convertJxlButton.setOnClickListener(v -> convertLatestVideo(OutputFormat.JXL));
    }

    private void convertLatestVideo(@NonNull OutputFormat outputFormat) {
        if (!hasReadVideoPermission()) {
            requestReadVideoPermission();
            return;
        }

        VideoSource videoSource = findLatestVideo();
        if (videoSource == null) {
            setStatus("No video found in Download/WebStreamVideo.");
            return;
        }

        setConversionEnabled(false);
        setStatus("Converting " + videoSource.displayName + " to " + outputFormat.folderName + "...");

        conversionExecutor.execute(() -> {
            try {
                ConversionResult result = convertVideo(videoSource, outputFormat);
                runOnUiThread(() -> {
                    setStatus("Converted " + result.frameCount + " images in "
                            + result.durationMs + " ms. Saved in Download/"
                            + DOWNLOAD_ROOT + "/" + result.videoFolderName + "/"
                            + outputFormat.folderName + ".");
                    setConversionEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("Conversion failed: " + e.getMessage());
                    setConversionEnabled(true);
                });
            }
        });
    }

    private void refreshLatestVideoText() {
        if (!hasReadVideoPermission()) {
            sourceText.setText("Allow video access to read Download/WebStreamVideo.");
            return;
        }

        VideoSource latestVideo = findLatestVideo();
        sourceText.setText(latestVideo == null
                ? "No video found in Download/WebStreamVideo."
                : "Using: " + latestVideo.displayName);
    }

    private boolean hasReadVideoPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestReadVideoPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        ActivityCompat.requestPermissions(this, new String[]{permission}, READ_VIDEO_PERMISSION_REQUEST);
        setStatus("Video access is needed to read Download/WebStreamVideo.");
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != READ_VIDEO_PERMISSION_REQUEST) {
            return;
        }

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        setStatus(granted
                ? "Video access granted."
                : "Video access denied. Cannot read /WebStreamVideo.");
        refreshLatestVideoText();
    }

    @NonNull
    private ConversionResult convertVideo(
            @NonNull VideoSource videoSource,
            @NonNull OutputFormat outputFormat
    ) throws IOException {
        if (outputFormat == OutputFormat.JXL && !NativeJxlCodec.isAvailable()) {
            String reason = NativeJxlCodec.unavailableReason();
            throw new IOException(reason == null ? "JXL encoder is not available." : reason);
        }

        long startMs = System.currentTimeMillis();
        long startNs = System.nanoTime();
        String videoFolderName = cleanName(stripExtension(videoSource.displayName));
        int frameCount = 0;
        List<FrameTiming> frameTimings = new ArrayList<>();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            videoSource.setDataSource(this, retriever);
            long durationMs = parseLong(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION), 0L);
            int frameRate = readFrameRate(retriever);
            long frameIntervalUs = Math.max(1L, 1_000_000L / frameRate);
            long durationUs = Math.max(0L, durationMs * 1000L);

            for (long timeUs = 0L; timeUs <= durationUs; timeUs += frameIntervalUs) {
                Bitmap bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (bitmap == null) {
                    continue;
                }

                try {
                    frameCount++;
                    String fileName = String.format(
                            Locale.US,
                            "frame_%06d.%s",
                            frameCount,
                            outputFormat.extension);
                    frameTimings.add(saveFrame(bitmap, outputFormat, videoFolderName, fileName));
                } finally {
                    bitmap.recycle();
                }
            }

            if (frameCount == 0) {
                throw new IOException("No frames could be read from the video.");
            }

            long durationTakenMs = (System.nanoTime() - startNs) / 1_000_000L;
            writeReport(videoSource, outputFormat, videoFolderName, frameCount, frameRate,
                    durationMs, startMs, durationTakenMs, frameTimings);
            return new ConversionResult(videoFolderName, frameCount, durationTakenMs);
        } finally {
            retriever.release();
        }
    }

    @NonNull
    private FrameTiming saveFrame(
            @NonNull Bitmap bitmap,
            @NonNull OutputFormat outputFormat,
            @NonNull String videoFolderName,
            @NonNull String fileName
    ) throws IOException {
        EncodedFrame encodedFrame = encodeFrame(bitmap, outputFormat);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = createDownloadUri(
                    videoFolderName,
                    outputFormat.folderName,
                    fileName,
                    outputFormat.mimeType);
            boolean success = false;
            long saveStartNs = System.nanoTime();
            try (OutputStream outputStream = openOutputStream(uri)) {
                outputStream.write(encodedFrame.bytes);
                success = true;
            } finally {
                finishDownloadWrite(uri, success);
            }
            return new FrameTiming(encodedFrame.conversionNs, System.nanoTime() - saveStartNs);
        }

        File outputFile = createLegacyOutputFile(videoFolderName, outputFormat.folderName, fileName);
        long saveStartNs = System.nanoTime();
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(encodedFrame.bytes);
        }
        return new FrameTiming(encodedFrame.conversionNs, System.nanoTime() - saveStartNs);
    }

    @NonNull
    private EncodedFrame encodeFrame(
            @NonNull Bitmap bitmap,
            @NonNull OutputFormat outputFormat
    ) throws IOException {
        long conversionStartNs = System.nanoTime();
        if (outputFormat == OutputFormat.JPEG) {
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)) {
                throw new IOException("Could not encode JPEG frame.");
            }
            return new EncodedFrame(outputStream.toByteArray(), System.nanoTime() - conversionStartNs);
        }

        byte[] encodedData = NativeJxlCodec.encode(bitmap, JXL_QUALITY);
        if (encodedData == null || encodedData.length == 0) {
            throw new IOException("Could not encode JXL frame: " + NativeJxlCodec.lastError());
        }
        return new EncodedFrame(encodedData, System.nanoTime() - conversionStartNs);
    }

    private void writeReport(
            @NonNull VideoSource videoSource,
            @NonNull OutputFormat outputFormat,
            @NonNull String videoFolderName,
            int frameCount,
            int frameRate,
            long videoDurationMs,
            long startedAtMs,
            long durationTakenMs,
            @NonNull List<FrameTiming> frameTimings
    ) throws IOException {
        StringBuilder reportBuilder = new StringBuilder();
        long totalConversionNs = 0L;
        long totalSaveNs = 0L;
        for (FrameTiming timing : frameTimings) {
            totalConversionNs += timing.conversionNs;
            totalSaveNs += timing.saveNs;
        }
        long averageConversionNs = frameTimings.isEmpty()
                ? 0L
                : totalConversionNs / frameTimings.size();
        long averageSaveNs = frameTimings.isEmpty()
                ? 0L
                : totalSaveNs / frameTimings.size();

        reportBuilder.append("Video: ").append(videoSource.displayName).append('\n')
                .append("Source Path: ").append(videoSource.readablePath).append('\n')
                .append("Output Format: ").append(outputFormat.folderName).append('\n')
                .append("Output Folder: /").append(DOWNLOAD_ROOT).append('/')
                .append(videoFolderName).append('/').append(outputFormat.folderName).append('\n')
                .append("Frame Count: ").append(frameCount).append('\n')
                .append("Frame Rate Used: ").append(frameRate).append('\n')
                .append("Video Duration MS: ").append(videoDurationMs).append('\n')
                .append("Conversion Started At: ").append(formatTime(startedAtMs)).append('\n')
                .append("Conversion Time MS: ").append(durationTakenMs).append('\n')
                .append("Conversion Time Seconds: ")
                .append(String.format(Locale.US, "%.2f", durationTakenMs / 1000D)).append('\n')
                .append("Total Frame Conversion NS: ").append(totalConversionNs).append('\n')
                .append("Average Single Frame Conversion NS: ").append(averageConversionNs).append('\n')
                .append("Total Frame Save NS: ").append(totalSaveNs).append('\n')
                .append("Average Single Frame Save NS: ").append(averageSaveNs).append('\n')
                .append("Single Frame Timings NS:").append('\n');

        for (int index = 0; index < frameTimings.size(); index++) {
            FrameTiming timing = frameTimings.get(index);
            reportBuilder.append(String.format(
                    Locale.US,
                    "frame_%06d: conversion=%d, save=%d, total=%d%n",
                    index + 1,
                    timing.conversionNs,
                    timing.saveNs,
                    timing.conversionNs + timing.saveNs));
        }

        String report = reportBuilder.toString();
        String reportFileName = REPORT_FILE_PREFIX + outputFormat.folderName + ".txt";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = createDownloadUri(videoFolderName, null, reportFileName, "text/plain");
            boolean success = false;
            try (OutputStream outputStream = openOutputStream(uri)) {
                outputStream.write(report.getBytes(StandardCharsets.UTF_8));
                success = true;
            } finally {
                finishDownloadWrite(uri, success);
            }
            return;
        }

        File reportFile = createLegacyOutputFile(videoFolderName, null, reportFileName);
        try (FileOutputStream outputStream = new FileOutputStream(reportFile)) {
            outputStream.write(report.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    private VideoSource findLatestVideo() {
        List<VideoSource> candidates = new ArrayList<>();
        addDownloadVideoCandidates(candidates);

        VideoSource latest = null;
        for (VideoSource candidate : candidates) {
            if (latest == null || candidate.lastModifiedMs > latest.lastModifiedMs) {
                latest = candidate;
            }
        }
        return latest;
    }

    private void addDownloadVideoCandidates(@NonNull List<VideoSource> candidates) {
        addVideoCandidates(new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                REQUESTED_SOURCE_FOLDER), candidates);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addMediaStoreVideoCandidates(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, candidates);
            addMediaStoreVideoCandidates(MediaStore.Downloads.EXTERNAL_CONTENT_URI, candidates);
        }
    }

    private void addMediaStoreVideoCandidates(
            @NonNull Uri collection,
            @NonNull List<VideoSource> candidates
    ) {
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] selectionArgs = {"%" + REQUESTED_SOURCE_FOLDER + "%"};

        try (Cursor cursor = getContentResolver().query(
                collection,
                projection,
                selection,
                selectionArgs,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) {
                return;
            }

            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
            int modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);

            while (cursor.moveToNext()) {
                String displayName = cursor.getString(nameColumn);
                if (displayName == null || !isVideoFileName(displayName)) {
                    continue;
                }

                String relativePath = cursor.getString(pathColumn);
                if (relativePath == null || !relativePath.contains(REQUESTED_SOURCE_FOLDER)) {
                    continue;
                }

                long id = cursor.getLong(idColumn);
                long modifiedMs = cursor.getLong(modifiedColumn) * 1000L;
                Uri uri = ContentUris.withAppendedId(collection, id);
                candidates.add(VideoSource.fromUri(
                        uri,
                        displayName,
                        "/" + relativePath + displayName,
                        modifiedMs));
            }
        }
    }

    private void addVideoCandidates(@NonNull File folder, @NonNull List<VideoSource> candidates) {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                addVideoCandidates(file, candidates);
            } else if (isVideoFile(file) && file.length() > 0L) {
                candidates.add(VideoSource.fromFile(file));
            }
        }
    }

    private boolean isVideoFile(@NonNull File file) {
        return isVideoFileName(file.getName());
    }

    private boolean isVideoFileName(@NonNull String fileName) {
        String name = fileName.toLowerCase(Locale.US);
        return name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".3gp")
                || name.endsWith(".webm") || name.endsWith(".mkv");
    }

    @NonNull
    private Uri createDownloadUri(
            @NonNull String videoFolderName,
            @Nullable String formatFolderName,
            @NonNull String fileName,
            @NonNull String mimeType
    ) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS
                + "/" + DOWNLOAD_ROOT
                + "/" + videoFolderName
                + (formatFolderName == null ? "" : "/" + formatFolderName));
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Could not create output file: " + fileName);
        }
        return uri;
    }

    @NonNull
    private OutputStream openOutputStream(@NonNull Uri uri) throws IOException {
        OutputStream outputStream = getContentResolver().openOutputStream(uri);
        if (outputStream == null) {
            throw new IOException("Could not open output stream.");
        }
        return outputStream;
    }

    private void finishDownloadWrite(@NonNull Uri uri, boolean success) {
        ContentResolver resolver = getContentResolver();
        if (success) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } else {
            resolver.delete(uri, null, null);
        }
    }

    @NonNull
    private File createLegacyOutputFile(
            @NonNull String videoFolderName,
            @Nullable String formatFolderName,
            @NonNull String fileName
    ) throws IOException {
        File folder = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_ROOT + "/" + videoFolderName
                        + (formatFolderName == null ? "" : "/" + formatFolderName));
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Could not create output folder: " + folder.getAbsolutePath());
        }
        return new File(folder, fileName);
    }

    private int readFrameRate(@NonNull MediaMetadataRetriever retriever) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String frameRateText = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            if (frameRateText != null) {
                try {
                    int frameRate = Math.round(Float.parseFloat(frameRateText));
                    if (frameRate > 0) {
                        return frameRate;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return DEFAULT_FRAME_RATE;
    }

    private long parseLong(@Nullable String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @NonNull
    private String stripExtension(@NonNull String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    @NonNull
    private String cleanName(@NonNull String value) {
        String cleanValue = value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return cleanValue.isEmpty() ? "video" : cleanValue;
    }

    @NonNull
    private String formatTime(long timeMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timeMs));
    }

    private void setConversionEnabled(boolean enabled) {
        convertJpegButton.setEnabled(enabled);
        convertJxlButton.setEnabled(enabled);
    }

    private void setStatus(@NonNull String message) {
        statusText.setText(message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        conversionExecutor.shutdownNow();
        super.onDestroy();
    }

    private enum OutputFormat {
        JPEG("jpeg", "jpg", "image/jpeg"),
        JXL("jxl", "jxl", "image/jxl");

        final String folderName;
        final String extension;
        final String mimeType;

        OutputFormat(String folderName, String extension, String mimeType) {
            this.folderName = folderName;
            this.extension = extension;
            this.mimeType = mimeType;
        }
    }

    private static final class ConversionResult {
        final String videoFolderName;
        final int frameCount;
        final long durationMs;

        ConversionResult(@NonNull String videoFolderName, int frameCount, long durationMs) {
            this.videoFolderName = videoFolderName;
            this.frameCount = frameCount;
            this.durationMs = durationMs;
        }
    }

    private static final class EncodedFrame {
        final byte[] bytes;
        final long conversionNs;

        EncodedFrame(@NonNull byte[] bytes, long conversionNs) {
            this.bytes = bytes;
            this.conversionNs = conversionNs;
        }
    }

    private static final class FrameTiming {
        final long conversionNs;
        final long saveNs;

        FrameTiming(long conversionNs, long saveNs) {
            this.conversionNs = conversionNs;
            this.saveNs = saveNs;
        }
    }

    private static final class VideoSource {
        final File file;
        final Uri uri;
        final String displayName;
        final String readablePath;
        final long lastModifiedMs;

        private VideoSource(
                @Nullable File file,
                @Nullable Uri uri,
                @NonNull String displayName,
                @NonNull String readablePath,
                long lastModifiedMs
        ) {
            this.file = file;
            this.uri = uri;
            this.displayName = displayName;
            this.readablePath = readablePath;
            this.lastModifiedMs = lastModifiedMs;
        }

        @NonNull
        static VideoSource fromFile(@NonNull File file) {
            return new VideoSource(
                    file,
                    null,
                    file.getName(),
                    file.getAbsolutePath(),
                    file.lastModified());
        }

        @NonNull
        static VideoSource fromUri(
                @NonNull Uri uri,
                @NonNull String displayName,
                @NonNull String readablePath,
                long lastModifiedMs
        ) {
            return new VideoSource(null, uri, displayName, readablePath, lastModifiedMs);
        }

        void setDataSource(
                @NonNull Context context,
                @NonNull MediaMetadataRetriever retriever
        ) throws IOException {
            if (uri != null) {
                retriever.setDataSource(context, uri);
                return;
            }
            if (file == null) {
                throw new IOException("Video source is unavailable.");
            }
            retriever.setDataSource(file.getAbsolutePath());
        }
    }
}
