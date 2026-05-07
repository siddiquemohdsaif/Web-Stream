package app.builderx.ogfa.videoapichecker.conversion;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.w3n.webstream.NativeJxlCodec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

public class JpegBitmapJxlActivity extends AppCompatActivity {
    private static final String SOURCE_FOLDER = "WebStreamPhoto";
    private static final String DOWNLOAD_ROOT = "VideoApiChecker/BitmapConversion";
    private static final String OUTPUT_FOLDER = "jpeg_bitmap_jxl";
    private static final String REPORT_FILE_NAME = "conversion_report_jpeg_bitmap_jxl.txt";
    private static final int JXL_QUALITY = 95;
    private static final int READ_IMAGE_PERMISSION_REQUEST = 2201;

    private final ExecutorService conversionExecutor = Executors.newSingleThreadExecutor();

    private Button convertButton;
    private TextView sourceText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_jpeg_bitmap_jxl);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.jpegBitmapJxlRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left + dp(24), systemBars.top + dp(24),
                    systemBars.right + dp(24), systemBars.bottom + dp(24));
            return insets;
        });

        sourceText = findViewById(R.id.jpegBitmapJxlSourceText);
        statusText = findViewById(R.id.jpegBitmapJxlStatusText);
        convertButton = findViewById(R.id.convertJpegBitmapJxlButton);

        refreshImageCountText();
        convertButton.setOnClickListener(v -> convertJpegBitmapToJxl());
    }

    private void convertJpegBitmapToJxl() {
        if (!hasReadImagePermission()) {
            requestReadImagePermission();
            return;
        }

        if (!NativeJxlCodec.isAvailable()) {
            String reason = NativeJxlCodec.unavailableReason();
            setStatus(reason == null ? "JXL encoder is not available." : reason);
            return;
        }

        List<ImageSource> images = findInputImages();
        if (images.isEmpty()) {
            setStatus("No JPEG images found in Download/WebStreamPhoto.");
            return;
        }

        convertButton.setEnabled(false);
        setStatus("Converting " + images.size() + " JPEG images through Bitmap to JXL...");

        conversionExecutor.execute(() -> {
            try {
                ConversionResult result = convertImages(images);
                runOnUiThread(() -> {
                    setStatus("Converted " + result.imageCount + " images in "
                            + result.durationMs + " ms. Saved in Download/"
                            + DOWNLOAD_ROOT + "/" + SOURCE_FOLDER + "/" + OUTPUT_FOLDER + ".");
                    convertButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("Conversion failed: " + e.getMessage());
                    convertButton.setEnabled(true);
                });
            }
        });
    }

    @NonNull
    private ConversionResult convertImages(@NonNull List<ImageSource> images) throws IOException {
        long startedAtMs = System.currentTimeMillis();
        long startNs = System.nanoTime();
        List<ImageTiming> timings = new ArrayList<>();

        for (ImageSource imageSource : images) {
            long decodeStartNs = System.nanoTime();
            Bitmap bitmap = imageSource.decodeBitmap(this);
            long decodeNs = System.nanoTime() - decodeStartNs;
            if (bitmap == null) {
                continue;
            }

            try {
                long encodeStartNs = System.nanoTime();
                byte[] jxlBytes = NativeJxlCodec.encode(bitmap, JXL_QUALITY);
                long encodeNs = System.nanoTime() - encodeStartNs;
                if (jxlBytes == null || jxlBytes.length == 0) {
                    throw new IOException("Could not encode JXL image: " + NativeJxlCodec.lastError());
                }

                String fileName = cleanName(stripExtension(imageSource.displayName)) + ".jxl";
                long saveNs = saveJxl(fileName, jxlBytes);
                timings.add(new ImageTiming(
                        imageSource.displayName,
                        decodeNs,
                        encodeNs,
                        saveNs,
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        jxlBytes.length));
            } finally {
                bitmap.recycle();
            }
        }

        if (timings.isEmpty()) {
            throw new IOException("No JPEG images could be decoded.");
        }

        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        writeReport(images.size(), startedAtMs, durationMs, timings);
        return new ConversionResult(timings.size(), durationMs);
    }

    private long saveJxl(@NonNull String fileName, @NonNull byte[] jxlBytes) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = createDownloadUri(OUTPUT_FOLDER, fileName, "image/jxl");
            boolean success = false;
            long saveStartNs = System.nanoTime();
            try (OutputStream outputStream = openOutputStream(uri)) {
                outputStream.write(jxlBytes);
                success = true;
            } finally {
                finishDownloadWrite(uri, success);
            }
            return System.nanoTime() - saveStartNs;
        }

        File outputFile = createLegacyOutputFile(OUTPUT_FOLDER, fileName);
        long saveStartNs = System.nanoTime();
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(jxlBytes);
        }
        return System.nanoTime() - saveStartNs;
    }

    private void writeReport(
            int inputCount,
            long startedAtMs,
            long durationMs,
            @NonNull List<ImageTiming> timings
    ) throws IOException {
        long totalDecodeNs = 0L;
        long totalEncodeNs = 0L;
        long totalSaveNs = 0L;
        for (ImageTiming timing : timings) {
            totalDecodeNs += timing.decodeNs;
            totalEncodeNs += timing.encodeNs;
            totalSaveNs += timing.saveNs;
        }

        StringBuilder reportBuilder = new StringBuilder();
        reportBuilder.append("Pipeline: JPEG -> Bitmap -> JXL").append('\n')
                .append("Input Folder: Download/").append(SOURCE_FOLDER).append('\n')
                .append("Output Folder: Download/").append(DOWNLOAD_ROOT).append('/')
                .append(SOURCE_FOLDER).append('/').append(OUTPUT_FOLDER).append('\n')
                .append("Input Image Count: ").append(inputCount).append('\n')
                .append("Converted Image Count: ").append(timings.size()).append('\n')
                .append("Conversion Started At: ").append(formatTime(startedAtMs)).append('\n')
                .append("Total Pipeline Time MS: ").append(durationMs).append('\n')
                .append("Total Decode JPEG To Bitmap NS: ").append(totalDecodeNs).append('\n')
                .append("Average Decode JPEG To Bitmap NS: ").append(totalDecodeNs / timings.size()).append('\n')
                .append("Total Encode Bitmap To JXL NS: ").append(totalEncodeNs).append('\n')
                .append("Average Encode Bitmap To JXL NS: ").append(totalEncodeNs / timings.size()).append('\n')
                .append("Total Save JXL NS: ").append(totalSaveNs).append('\n')
                .append("Average Save JXL NS: ").append(totalSaveNs / timings.size()).append('\n')
                .append("Single Image Timings NS:").append('\n');

        for (ImageTiming timing : timings) {
            reportBuilder.append(timing.sourceName)
                    .append(": width=").append(timing.width)
                    .append(", height=").append(timing.height)
                    .append(", jxl_bytes=").append(timing.jxlBytes)
                    .append(", decode=").append(timing.decodeNs)
                    .append(", encode=").append(timing.encodeNs)
                    .append(", save=").append(timing.saveNs)
                    .append(", total=").append(timing.decodeNs + timing.encodeNs + timing.saveNs)
                    .append('\n');
        }

        String report = reportBuilder.toString();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = createDownloadUri(null, REPORT_FILE_NAME, "text/plain");
            boolean success = false;
            try (OutputStream outputStream = openOutputStream(uri)) {
                outputStream.write(report.getBytes(StandardCharsets.UTF_8));
                success = true;
            } finally {
                finishDownloadWrite(uri, success);
            }
            return;
        }

        File reportFile = createLegacyOutputFile(null, REPORT_FILE_NAME);
        try (FileOutputStream outputStream = new FileOutputStream(reportFile)) {
            outputStream.write(report.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NonNull
    private List<ImageSource> findInputImages() {
        List<ImageSource> candidates = new ArrayList<>();
        addFileImageCandidates(new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                SOURCE_FOLDER), candidates);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addMediaStoreImageCandidates(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, candidates);
            addMediaStoreImageCandidates(MediaStore.Downloads.EXTERNAL_CONTENT_URI, candidates);
        }
        return candidates;
    }

    private void addMediaStoreImageCandidates(
            @NonNull Uri collection,
            @NonNull List<ImageSource> candidates
    ) {
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] selectionArgs = {"%" + SOURCE_FOLDER + "%"};

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
                String relativePath = cursor.getString(pathColumn);
                if (displayName == null || relativePath == null
                        || !relativePath.contains(SOURCE_FOLDER)
                        || !isJpegFileName(displayName)
                        || containsSource(candidates, displayName)) {
                    continue;
                }

                Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn));
                candidates.add(ImageSource.fromUri(
                        uri,
                        displayName,
                        "/" + relativePath + displayName,
                        cursor.getLong(modifiedColumn) * 1000L));
            }
        }
    }

    private void addFileImageCandidates(@NonNull File folder, @NonNull List<ImageSource> candidates) {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                addFileImageCandidates(file, candidates);
            } else if (isJpegFileName(file.getName()) && file.length() > 0L
                    && !containsSource(candidates, file.getName())) {
                candidates.add(ImageSource.fromFile(file));
            }
        }
    }

    private boolean containsSource(@NonNull List<ImageSource> sources, @NonNull String displayName) {
        for (ImageSource source : sources) {
            if (source.displayName.equals(displayName)) {
                return true;
            }
        }
        return false;
    }

    private void refreshImageCountText() {
        if (!hasReadImagePermission()) {
            sourceText.setText("Allow image access to read Download/WebStreamPhoto.");
            return;
        }

        int count = findInputImages().size();
        sourceText.setText(count == 0
                ? "No JPEG images found in Download/WebStreamPhoto."
                : "Found " + count + " JPEG images in Download/WebStreamPhoto.");
    }

    private boolean hasReadImagePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestReadImagePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        ActivityCompat.requestPermissions(this, new String[]{permission}, READ_IMAGE_PERMISSION_REQUEST);
        setStatus("Image access is needed to read Download/WebStreamPhoto.");
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != READ_IMAGE_PERMISSION_REQUEST) {
            return;
        }

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        setStatus(granted
                ? "Image access granted."
                : "Image access denied. Cannot read Download/WebStreamPhoto.");
        refreshImageCountText();
    }

    @NonNull
    private Uri createDownloadUri(
            @Nullable String formatFolderName,
            @NonNull String fileName,
            @NonNull String mimeType
    ) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS
                + "/" + DOWNLOAD_ROOT
                + "/" + SOURCE_FOLDER
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
            @Nullable String formatFolderName,
            @NonNull String fileName
    ) throws IOException {
        File folder = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_ROOT + "/" + SOURCE_FOLDER
                        + (formatFolderName == null ? "" : "/" + formatFolderName));
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Could not create output folder: " + folder.getAbsolutePath());
        }
        return new File(folder, fileName);
    }

    private boolean isJpegFileName(@NonNull String fileName) {
        String lowerName = fileName.toLowerCase(Locale.US);
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg");
    }

    @NonNull
    private String stripExtension(@NonNull String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    @NonNull
    private String cleanName(@NonNull String value) {
        String cleanValue = value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return cleanValue.isEmpty() ? "image" : cleanValue;
    }

    @NonNull
    private String formatTime(long timeMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timeMs));
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

    private static final class ConversionResult {
        final int imageCount;
        final long durationMs;

        ConversionResult(int imageCount, long durationMs) {
            this.imageCount = imageCount;
            this.durationMs = durationMs;
        }
    }

    private static final class ImageTiming {
        final String sourceName;
        final long decodeNs;
        final long encodeNs;
        final long saveNs;
        final int width;
        final int height;
        final int jxlBytes;

        ImageTiming(
                @NonNull String sourceName,
                long decodeNs,
                long encodeNs,
                long saveNs,
                int width,
                int height,
                int jxlBytes
        ) {
            this.sourceName = sourceName;
            this.decodeNs = decodeNs;
            this.encodeNs = encodeNs;
            this.saveNs = saveNs;
            this.width = width;
            this.height = height;
            this.jxlBytes = jxlBytes;
        }
    }

    private static final class ImageSource {
        final File file;
        final Uri uri;
        final String displayName;

        private ImageSource(
                @Nullable File file,
                @Nullable Uri uri,
                @NonNull String displayName
        ) {
            this.file = file;
            this.uri = uri;
            this.displayName = displayName;
        }

        @NonNull
        static ImageSource fromFile(@NonNull File file) {
            return new ImageSource(file, null, file.getName());
        }

        @NonNull
        static ImageSource fromUri(
                @NonNull Uri uri,
                @NonNull String displayName,
                @NonNull String readablePath,
                long lastModifiedMs
        ) {
            return new ImageSource(null, uri, displayName);
        }

        @Nullable
        Bitmap decodeBitmap(@NonNull Context context) throws IOException {
            if (uri != null) {
                try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                    return BitmapFactory.decodeStream(inputStream);
                }
            }
            if (file == null) {
                throw new IOException("Image source is unavailable.");
            }
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        }
    }
}
