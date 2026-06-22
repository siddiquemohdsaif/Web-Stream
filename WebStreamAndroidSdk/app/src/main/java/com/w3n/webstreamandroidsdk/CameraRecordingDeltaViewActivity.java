package com.w3n.webstreamandroidsdk;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.w3n.webstream.Util.CameraController;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraRecordingDeltaViewActivity extends AppCompatActivity {
    private static final int REQUEST_CAMERA_PERMISSION = 3001;
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;
    private static final int FRAME_RATE_FPS = 15;
    private static final long IMAGE_SAVE_INTERVAL_MS = 3000L;
    private static final String DELTA_METHOD_RELATIVE_DIR =
            Environment.DIRECTORY_DOWNLOADS + "/WebStreamAndroidSdk/Delta Method";

    private TextView statusText;
    private ImageView recordedImageView;
    private Button startButton;

    private CameraController cameraController;
    private Bitmap latestPreviewBitmap;
    private byte[] latestYuvData;
    private int latestYuvWidth;
    private int latestYuvHeight;
    private boolean recording;
    private boolean waitingForFirstSavedImage;
    private String recordingFolderName;
    private int savedImageCount;

    private final Handler imageSaveHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService imageSaveExecutor = Executors.newSingleThreadExecutor();

    private final Runnable imageSaveRunnable = new Runnable() {
        @Override
        public void run() {
            saveLatestImage();

            if (recording) {
                imageSaveHandler.postDelayed(this, IMAGE_SAVE_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_camera_recording_delta_view);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    systemBars.left + v.getPaddingLeft(),
                    systemBars.top + v.getPaddingTop(),
                    systemBars.right + v.getPaddingRight(),
                    systemBars.bottom + v.getPaddingBottom());
            return insets;
        });

        statusText = findViewById(R.id.deltaRecordingStatusText);
        recordedImageView = findViewById(R.id.deltaRecordedImageView);
        startButton = findViewById(R.id.deltaStartButton);

        startButton.setOnClickListener(v -> {
            if (recording) {
                stopRecording();
            } else {
                startRecordingWithPermission();
            }
        });

        updateControls();
    }

    private void startRecordingWithPermission() {
        List<String> missingPermissions = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.CAMERA);
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (missingPermissions.isEmpty()) {
            startRecording();
            return;
        }

        ActivityCompat.requestPermissions(
                this,
                missingPermissions.toArray(new String[0]),
                REQUEST_CAMERA_PERMISSION);
    }

    private void startRecording() {
        if (recording) {
            return;
        }

        try {
            recordingFolderName = getNextRecordingFolderName();
            savedImageCount = 0;

            cameraController = new CameraController(
                    this,
                    new CameraController.Config.Builder()
                            .setSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                            .setFrameRateFps(FRAME_RATE_FPS)
                            .setImageReaderMaxImages(4)
                            .setFrameType(CameraController.FrameType.IMAGE_READER_YUV)
                            .setCameraFacing(CameraController.CameraFacing.FRONT)
                            .build(),
                    cameraCallback);
            cameraController.start();

            recording = true;
            waitingForFirstSavedImage = true;
            showStatus("Camera started. Saving JPEG images to folder " + recordingFolderName + ".");
            updateControls();
        } catch (Exception error) {
            showError(error);
            stopRecording();
        }
    }

    private final CameraController.CameraCallback cameraCallback =
            new CameraController.CameraCallback() {
                @Override
                public void onImageFrameAvailable(CameraController.CameraFrame frame) {
                    byte[] yuvData = frame.yuv420Data;
                    if (yuvData == null || frame.yuvFormat != CameraController.YuvFormat.I420) {
                        return;
                    }

                    runOnUiThread(() -> {
                        latestYuvData = yuvData;
                        latestYuvWidth = frame.width;
                        latestYuvHeight = frame.height;

                        updatePreviewImage(yuvData, frame.width, frame.height);

                        if (recording && waitingForFirstSavedImage) {
                            waitingForFirstSavedImage = false;
                            saveLatestImage();
                            imageSaveHandler.postDelayed(
                                    imageSaveRunnable,
                                    IMAGE_SAVE_INTERVAL_MS);
                        }
                    });
                }

                @Override
                public void onCameraStarted() {
                    showStatus("Camera started.");
                }

                @Override
                public void onCameraStopped() {
                    showStatus("Camera stopped.");
                }

                @Override
                public void onCameraError(Exception error) {
                    showError(error);
                    runOnUiThread(CameraRecordingDeltaViewActivity.this::stopRecording);
                }
            };

    private void stopRecording() {
        imageSaveHandler.removeCallbacks(imageSaveRunnable);

        if (cameraController != null) {
            cameraController.release();
            cameraController = null;
        }

        recording = false;
        waitingForFirstSavedImage = false;
        showStatus("Camera stopped.");
        updateControls();
    }

    private void saveLatestImage() {
        if (latestYuvData == null || latestYuvWidth <= 0 || latestYuvHeight <= 0) {
            showStatus("Waiting for camera frame...");
            return;
        }

        byte[] yuvData = Arrays.copyOf(latestYuvData, latestYuvData.length);
        int width = latestYuvWidth;
        int height = latestYuvHeight;
        int imageNumber = ++savedImageCount;
        String folderName = recordingFolderName;
        String fileName = String.format(Locale.US, "image_%04d.jpg", imageNumber);

        imageSaveExecutor.execute(() -> {
            try {
                byte[] jpegData = convertI420ToJpeg(yuvData, width, height);
                saveJpegImage(jpegData, folderName, fileName);
                runOnUiThread(() -> showStatus(
                        "Saved " + fileName + " in folder " + folderName + "."));
            } catch (Exception error) {
                runOnUiThread(() -> showError(error));
            }
        });
    }

    private void updatePreviewImage(byte[] yuvData, int width, int height) {
        try {
            byte[] jpegData = convertI420ToJpeg(yuvData, width, height);
            Bitmap previewBitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (previewBitmap == null) {
                return;
            }

            Bitmap previousBitmap = latestPreviewBitmap;
            latestPreviewBitmap = previewBitmap;
            recordedImageView.setImageBitmap(previewBitmap);

            if (previousBitmap != null
                    && previousBitmap != previewBitmap
                    && !previousBitmap.isRecycled()) {
                previousBitmap.recycle();
            }
        } catch (IOException error) {
            showError(error);
        }
    }

    private byte[] convertI420ToJpeg(byte[] yuvData, int width, int height) throws IOException {
        byte[] nv21Data = convertI420ToNv21(yuvData, width, height);
        YuvImage yuvImage = new YuvImage(
                nv21Data,
                ImageFormat.NV21,
                width,
                height,
                null);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean compressed = yuvImage.compressToJpeg(
                new Rect(0, 0, width, height),
                80,
                outputStream);

        if (!compressed) {
            throw new IOException("Could not convert YUV frame to JPEG.");
        }

        return outputStream.toByteArray();
    }

    private byte[] convertI420ToNv21(byte[] i420Data, int width, int height) throws IOException {
        int ySize = width * height;
        int chromaSize = ySize / 4;
        int expectedSize = ySize + (chromaSize * 2);

        if (i420Data.length < expectedSize) {
            throw new IOException("Invalid I420 frame size.");
        }

        byte[] nv21Data = new byte[expectedSize];
        System.arraycopy(i420Data, 0, nv21Data, 0, ySize);

        int uOffset = ySize;
        int vOffset = ySize + chromaSize;
        int nv21ChromaOffset = ySize;

        for (int i = 0; i < chromaSize; i++) {
            nv21Data[nv21ChromaOffset++] = i420Data[vOffset + i];
            nv21Data[nv21ChromaOffset++] = i420Data[uOffset + i];
        }

        return nv21Data;
    }

    private void saveJpegImage(
            byte[] jpegData,
            String folderName,
            String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveJpegImageWithMediaStore(jpegData, folderName, fileName);
        } else {
            saveJpegImageWithFile(jpegData, folderName, fileName);
        }
    }

    private void saveJpegImageWithMediaStore(
            byte[] jpegData,
            String folderName,
            String fileName) throws IOException {
        ContentResolver resolver = getContentResolver();
        String relativePath = DELTA_METHOD_RELATIVE_DIR + "/" + folderName + "/";

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Could not create image file.");
        }

        try (OutputStream outputStream = resolver.openOutputStream(uri)) {
            if (outputStream == null) {
                throw new IOException("Could not save JPEG image.");
            }

            outputStream.write(jpegData);
        }

        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }

    private void saveJpegImageWithFile(
            byte[] jpegData,
            String folderName,
            String fileName) throws IOException {
        File folder = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "WebStreamAndroidSdk/Delta Method/" + folderName);

        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Could not create image folder.");
        }

        File imageFile = new File(folder, fileName);
        try (OutputStream outputStream = new FileOutputStream(imageFile)) {
            outputStream.write(jpegData);
        }
    }

    private String getNextRecordingFolderName() {
        int latestFolderNumber = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? getLatestMediaStoreFolderNumber()
                : getLatestFileFolderNumber();

        return String.valueOf(latestFolderNumber + 1);
    }

    private int getLatestMediaStoreFolderNumber() {
        String[] projection = {MediaStore.MediaColumns.RELATIVE_PATH};
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] selectionArgs = {DELTA_METHOD_RELATIVE_DIR + "/%"};
        int latestFolderNumber = 0;

        try (Cursor cursor = getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null)) {
            if (cursor == null) {
                return 0;
            }

            int relativePathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            if (relativePathColumn < 0) {
                return 0;
            }

            while (cursor.moveToNext()) {
                String relativePath = cursor.getString(relativePathColumn);
                latestFolderNumber = Math.max(
                        latestFolderNumber,
                        parseFolderNumber(relativePath));
            }
        } catch (Exception ignored) {
            return 0;
        }

        return latestFolderNumber;
    }

    private int getLatestFileFolderNumber() {
        File deltaMethodFolder = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "WebStreamAndroidSdk/Delta Method");
        File[] folders = deltaMethodFolder.listFiles(File::isDirectory);
        int latestFolderNumber = 0;

        if (folders == null) {
            return 0;
        }

        for (File folder : folders) {
            latestFolderNumber = Math.max(
                    latestFolderNumber,
                    parsePositiveInt(folder.getName()));
        }

        return latestFolderNumber;
    }

    private int parseFolderNumber(String relativePath) {
        if (relativePath == null) {
            return 0;
        }

        String prefix = DELTA_METHOD_RELATIVE_DIR + "/";
        if (!relativePath.startsWith(prefix)) {
            return 0;
        }

        String remainingPath = relativePath.substring(prefix.length());
        int separatorIndex = remainingPath.indexOf('/');
        String folderName = separatorIndex >= 0
                ? remainingPath.substring(0, separatorIndex)
                : remainingPath;

        return parsePositiveInt(folderName);
    }

    private int parsePositiveInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void updateControls() {
        startButton.setText(recording ? "Stop" : "Start");
    }

    private void showStatus(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    private void showError(Exception error) {
        runOnUiThread(() -> statusText.setText(error.getMessage()));
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA_PERMISSION && areAllPermissionsGranted(grantResults)) {
            startRecording();
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            showStatus("Camera and storage permissions are required to start.");
        }
    }

    private boolean areAllPermissionsGranted(@NonNull int[] grantResults) {
        if (grantResults.length == 0) {
            return false;
        }

        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        return true;
    }

    @Override
    protected void onDestroy() {
        stopRecording();
        imageSaveExecutor.shutdownNow();
        recordedImageView.setImageBitmap(null);

        if (latestPreviewBitmap != null && !latestPreviewBitmap.isRecycled()) {
            latestPreviewBitmap.recycle();
            latestPreviewBitmap = null;
        }

        super.onDestroy();
    }
}
