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

import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.builderx.ogfa.videoapichecker.R;

public class ImageConversionActivity extends AppCompatActivity {
    private static final String SOURCE_FOLDER = "WebStreamPhoto";
    private static final String DOWNLOAD_ROOT = "VideoApiChecker/PhotoConversion";
    private static final String REPORT_FILE_PREFIX = "conversion_report_";
    private static final int JPEG_QUALITY = 70;
    private static final int JXL_QUALITY = 70;
    private static final int READ_IMAGE_PERMISSION_REQUEST = 2101;
    private static final int IMAGE_THREAD_COUNT = 8;

    private final ExecutorService conversionExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(IMAGE_THREAD_COUNT);

    private Button convertJpegButton;
    private Button convertJxlButton;
    private TextView sourceText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_image_conversion);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.imageConversionRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left + dp(24), systemBars.top + dp(24),
                    systemBars.right + dp(24), systemBars.bottom + dp(24));
            return insets;
        });

        sourceText = findViewById(R.id.imageConversionSourceText);
        statusText = findViewById(R.id.imageConversionStatusText);
        convertJpegButton = findViewById(R.id.convertImageJpegButton);
        convertJxlButton = findViewById(R.id.convertImageJxlButton);

        refreshImageCountText();

        convertJpegButton.setOnClickListener(v -> convertImages(OutputFormat.JPEG));
        convertJxlButton.setOnClickListener(v -> convertImages(OutputFormat.JXL));
    }

    private void convertImages(@NonNull OutputFormat outputFormat) {
        if (!hasReadImagePermission()) {
            requestReadImagePermission();
            return;
        }

        List<ImageSource> images = findInputImages();
        if (images.isEmpty()) {
            setStatus("No JPEG images found in Download/WebStreamPhoto.");
            return;
        }

        if (outputFormat == OutputFormat.JXL && !NativeJxlCodec.isAvailable()) {
            String reason = NativeJxlCodec.unavailableReason();
            setStatus(reason == null ? "JXL encoder is not available." : reason);
            return;
        }

        setConversionEnabled(false);
        setStatus("Converting " + images.size() + " images to " + outputFormat.folderName + "...");

        conversionExecutor.execute(() -> {
            try {
                ConversionResult result = convertImageList(images, outputFormat);
                runOnUiThread(() -> {
                    setStatus("Converted " + result.imageCount + " images in "
                            + result.durationMs + " ms. Saved in Download/"
                            + DOWNLOAD_ROOT + "/" + SOURCE_FOLDER + "/"
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

    @NonNull
    private ConversionResult convertImageList(
            @NonNull List<ImageSource> images,
            @NonNull OutputFormat outputFormat
    ) throws IOException {
        long startedAtMs = System.currentTimeMillis();
        long startNs = System.nanoTime();
        List<ImageTiming> imageTimings = new ArrayList<>();
        List<Future<ImageTiming>> futures = new ArrayList<>();

        for (ImageSource imageSource : images) {
            futures.add(imageExecutor.submit(new ImageConversionTask(imageSource, outputFormat)));
        }

        for (Future<ImageTiming> future : futures) {
            try {
                ImageTiming timing = future.get();
                if (timing != null) {
                    imageTimings.add(timing);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Image conversion was interrupted.", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("Image conversion failed.", cause);
            }
        }

        if (imageTimings.isEmpty()) {
            throw new IOException("No JPEG images could be decoded.");
        }

        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        writeReport(outputFormat, images.size(), imageTimings.size(), startedAtMs, durationMs, imageTimings);
        return new ConversionResult(imageTimings.size(), durationMs);
    }

    @NonNull
    private ImageTiming saveImage(
            @NonNull Bitmap bitmap,
            @NonNull OutputFormat outputFormat,
            @NonNull String fileName,
            @NonNull String sourceName
    ) throws IOException {
        EncodedImage encodedImage = encodeImage(bitmap, outputFormat);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = createDownloadUri(outputFormat.folderName, fileName, outputFormat.mimeType);
            boolean success = false;
            long saveStartNs = System.nanoTime();
            try (OutputStream outputStream = openOutputStream(uri)) {
                outputStream.write(encodedImage.bytes);
                success = true;
            } finally {
                finishDownloadWrite(uri, success);
            }
            return new ImageTiming(sourceName, encodedImage.conversionNs, System.nanoTime() - saveStartNs);
        }

        File outputFile = createLegacyOutputFile(outputFormat.folderName, fileName);
        long saveStartNs = System.nanoTime();
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(encodedImage.bytes);
        }
        return new ImageTiming(sourceName, encodedImage.conversionNs, System.nanoTime() - saveStartNs);
    }

    @NonNull
    private EncodedImage encodeImage(
            @NonNull Bitmap bitmap,
            @NonNull OutputFormat outputFormat
    ) throws IOException {
        long conversionStartNs = System.nanoTime();
        if (outputFormat == OutputFormat.JPEG) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)) {
                throw new IOException("Could not encode JPEG image.");
            }
            return new EncodedImage(outputStream.toByteArray(), System.nanoTime() - conversionStartNs);
        }

        byte[] encodedData = NativeJxlCodec.encode(bitmap, JXL_QUALITY);
        if (encodedData == null || encodedData.length == 0) {
            throw new IOException("Could not encode JXL image: " + NativeJxlCodec.lastError());
        }
        return new EncodedImage(encodedData, System.nanoTime() - conversionStartNs);
    }

    private void writeReport(
            @NonNull OutputFormat outputFormat,
            int inputCount,
            int convertedCount,
            long startedAtMs,
            long durationMs,
            @NonNull List<ImageTiming> imageTimings
    ) throws IOException {
        long totalConversionNs = 0L;
        long totalSaveNs = 0L;
        for (ImageTiming timing : imageTimings) {
            totalConversionNs += timing.conversionNs;
            totalSaveNs += timing.saveNs;
        }
        long averageConversionNs = imageTimings.isEmpty() ? 0L : totalConversionNs / imageTimings.size();
        long averageSaveNs = imageTimings.isEmpty() ? 0L : totalSaveNs / imageTimings.size();

        StringBuilder reportBuilder = new StringBuilder();
        reportBuilder.append("Input Folder: Download/").append(SOURCE_FOLDER).append('\n')
                .append("Output Format: ").append(outputFormat.folderName).append('\n')
                .append("Output Folder: Download/").append(DOWNLOAD_ROOT).append('/')
                .append(SOURCE_FOLDER).append('/').append(outputFormat.folderName).append('\n')
                .append("Input Image Count: ").append(inputCount).append('\n')
                .append("Converted Image Count: ").append(convertedCount).append('\n')
                .append("Conversion Started At: ").append(formatTime(startedAtMs)).append('\n')
                .append("Conversion Time MS: ").append(durationMs).append('\n')
                .append("Conversion Time Seconds: ")
                .append(String.format(Locale.US, "%.2f", durationMs / 1000D)).append('\n')
                .append("Total Image Conversion NS: ").append(totalConversionNs).append('\n')
                .append("Average Single Image Conversion NS: ").append(averageConversionNs).append('\n')
                .append("Total Image Save NS: ").append(totalSaveNs).append('\n')
                .append("Average Single Image Save NS: ").append(averageSaveNs).append('\n')
                .append("Single Image Timings NS:").append('\n');

        for (ImageTiming timing : imageTimings) {
            reportBuilder.append(timing.sourceName)
                    .append(": conversion=").append(timing.conversionNs)
                    .append(", save=").append(timing.saveNs)
                    .append(", total=").append(timing.conversionNs + timing.saveNs)
                    .append('\n');
        }

        String reportFileName = REPORT_FILE_PREFIX + outputFormat.folderName + ".txt";
        String report = reportBuilder.toString();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = createDownloadUri(null, reportFileName, "text/plain");
            boolean success = false;
            try (OutputStream outputStream = openOutputStream(uri)) {
                outputStream.write(report.getBytes(StandardCharsets.UTF_8));
                success = true;
            } finally {
                finishDownloadWrite(uri, success);
            }
            return;
        }

        File reportFile = createLegacyOutputFile(null, reportFileName);
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

                long id = cursor.getLong(idColumn);
                long modifiedMs = cursor.getLong(modifiedColumn) * 1000L;
                Uri uri = ContentUris.withAppendedId(collection, id);
                candidates.add(ImageSource.fromUri(
                        uri,
                        displayName,
                        "/" + relativePath + displayName,
                        modifiedMs));
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
        imageExecutor.shutdownNow();
        super.onDestroy();
    }

    private final class ImageConversionTask implements Callable<ImageTiming> {
        private final ImageSource imageSource;
        private final OutputFormat outputFormat;

        private ImageConversionTask(
                @NonNull ImageSource imageSource,
                @NonNull OutputFormat outputFormat
        ) {
            this.imageSource = imageSource;
            this.outputFormat = outputFormat;
        }

        @Override
        public ImageTiming call() throws IOException {
            Bitmap bitmap = imageSource.decodeBitmap(ImageConversionActivity.this);
            if (bitmap == null) {
                return null;
            }

            try {
                String fileName = cleanName(stripExtension(imageSource.displayName))
                        + "." + outputFormat.extension;
                return saveImage(bitmap, outputFormat, fileName, imageSource.displayName);
            } finally {
                bitmap.recycle();
            }
        }
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
        final int imageCount;
        final long durationMs;

        ConversionResult(int imageCount, long durationMs) {
            this.imageCount = imageCount;
            this.durationMs = durationMs;
        }
    }

    private static final class EncodedImage {
        final byte[] bytes;
        final long conversionNs;

        EncodedImage(@NonNull byte[] bytes, long conversionNs) {
            this.bytes = bytes;
            this.conversionNs = conversionNs;
        }
    }

    private static final class ImageTiming {
        final String sourceName;
        final long conversionNs;
        final long saveNs;

        ImageTiming(@NonNull String sourceName, long conversionNs, long saveNs) {
            this.sourceName = sourceName;
            this.conversionNs = conversionNs;
            this.saveNs = saveNs;
        }
    }

    private static final class ImageSource {
        final File file;
        final Uri uri;
        final String displayName;
        final String readablePath;
        final long lastModifiedMs;

        private ImageSource(
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
        static ImageSource fromFile(@NonNull File file) {
            return new ImageSource(
                    file,
                    null,
                    file.getName(),
                    file.getAbsolutePath(),
                    file.lastModified());
        }

        @NonNull
        static ImageSource fromUri(
                @NonNull Uri uri,
                @NonNull String displayName,
                @NonNull String readablePath,
                long lastModifiedMs
        ) {
            return new ImageSource(null, uri, displayName, readablePath, lastModifiedMs);
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
