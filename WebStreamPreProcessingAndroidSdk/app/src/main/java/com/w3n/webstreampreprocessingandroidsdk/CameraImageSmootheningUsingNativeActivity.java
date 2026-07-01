package com.w3n.webstreampreprocessingandroidsdk;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCharacteristics;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraImageSmootheningUsingNativeActivity extends AppCompatActivity {
    private static final int CAMERA_PERMISSION_REQUEST = 2001;
    private static final int EDGE_THRESHOLD = 20;
    private static final String OUTPUT_RELATIVE_DIR =
            Environment.DIRECTORY_DOWNLOADS + "/WebStreamPreProcessingAndroidSdk/Image Smoothening/Native CPU/{number}";
    private static final String OUTPUT_RELATIVE_DIR_PREFIX =
            Environment.DIRECTORY_DOWNLOADS + "/WebStreamPreProcessingAndroidSdk/Image Smoothening/Native CPU";

    static {
        System.loadLibrary("native_camera_smoothening");
    }

    private SurfaceView nativeCameraPreview;
    private ImageView capturedYuvPreview;
    private ImageView beforeImage;
    private ImageView edgeMaskImage;
    private ImageView uEdgeMaskImage;
    private ImageView vEdgeMaskImage;
    private ImageView noiseReducedImage;
    private TextView cameraSizeText;
    private Button openCameraButton;
    private Button flipCameraButton;
    private Button clickButton;
    private Button backButton;
    private Button smoothCpuButton;
    private Spinner requiredResolutionSpinner;
    private View resolutionControls;
    private ScrollView cpuResultScroll;

    private boolean surfaceReady;
    private boolean nativeCameraStarted;
    private int desiredLensFacing = CameraCharacteristics.LENS_FACING_BACK;
    private RequiredResolution requiredResolution = RequiredResolution.HD;
    private long latestYuvFrameCount;
    private int latestCapturedYuv420Width;
    private int latestCapturedYuv420Height;
    private boolean hasNativeCapturedFrame;
    private final ExecutorService nativeCameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageSmootheningExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_camera_image_smoothening_using_native);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int contentPadding = dp(16);
            v.setPadding(
                    systemBars.left + contentPadding,
                    systemBars.top + contentPadding,
                    systemBars.right + contentPadding,
                    systemBars.bottom + contentPadding);
            return insets;
        });

        nativeCameraPreview = findViewById(R.id.nativeCameraPreview);
        capturedYuvPreview = findViewById(R.id.capturedYuvPreview);
        cameraSizeText = findViewById(R.id.cameraSizeText);
        openCameraButton = findViewById(R.id.openCameraButton);
        flipCameraButton = findViewById(R.id.flipCameraButton);
        clickButton = findViewById(R.id.clickButton);
        backButton = findViewById(R.id.backButton);
        smoothCpuButton = findViewById(R.id.smoothCpuButton);
        requiredResolutionSpinner = findViewById(R.id.requiredResolutionSpinner);
        resolutionControls = findViewById(R.id.resolutionControls);
        cpuResultScroll = findViewById(R.id.cpuResultScroll);
        beforeImage = findViewById(R.id.beforeImage);
        edgeMaskImage = findViewById(R.id.edgeMaskImage);
        uEdgeMaskImage = findViewById(R.id.uEdgeMaskImage);
        vEdgeMaskImage = findViewById(R.id.vEdgeMaskImage);
        noiseReducedImage = findViewById(R.id.noiseReducedImage);

        nativeInitController();
        setupRequiredResolutionSpinner();

        nativeCameraPreview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                surfaceReady = true;
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                surfaceReady = true;
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                surfaceReady = false;
                nativeCameraStarted = false;
                stopNativeCameraInBackground();
            }
        });

        openCameraButton.setOnClickListener(v -> openNativeCameraWithPermission());
        flipCameraButton.setOnClickListener(v -> flipCamera());
        clickButton.setOnClickListener(v -> capturePhoto());
        backButton.setOnClickListener(v -> showCameraPreviewMode());
        smoothCpuButton.setOnClickListener(v -> startImageSmootheningBYCpu());
        updateCameraSizeText();
    }

    @Override
    protected void onPause() {
        nativeCameraStarted = false;
        stopNativeCameraInBackground();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseNativeCameraInBackground();
        nativeCameraExecutor.shutdown();
        imageSmootheningExecutor.shutdown();
        super.onDestroy();
    }

    private void setupRequiredResolutionSpinner() {
        RequiredResolution[] resolutions = RequiredResolution.values();
        String[] labels = new String[resolutions.length];
        for (int i = 0; i < resolutions.length; i++) {
            labels[i] = resolutions[i].label;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        requiredResolutionSpinner.setAdapter(adapter);
        requiredResolutionSpinner.setSelection(requiredResolution.ordinal(), false);
        requiredResolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                RequiredResolution selectedResolution = resolutions[position];
                if (requiredResolution == selectedResolution) {
                    return;
                }

                requiredResolution = selectedResolution;
                latestYuvFrameCount = 0;
                latestCapturedYuv420Width = 0;
                latestCapturedYuv420Height = 0;
                hasNativeCapturedFrame = false;
                if (nativeCameraStarted) {
                    openNativeCameraWithPermission();
                } else {
                    updateCameraSizeText();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void openNativeCameraWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }

        if (!surfaceReady) {
            Toast.makeText(this, "Camera surface not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        Surface surface = nativeCameraPreview.getHolder().getSurface();
        int captureTargetWidth = getCaptureTargetWidth();
        int captureTargetHeight = getCaptureTargetHeight();
        int lensFacing = desiredLensFacing;
        flipCameraButton.setEnabled(false);
        clickButton.setEnabled(false);
        cameraSizeText.setText("Opening native camera...");
        nativeCameraExecutor.execute(() -> {
            boolean started = nativeStartNativeCamera(
                    surface,
                    captureTargetWidth,
                    captureTargetHeight,
                    lensFacing);
            runOnUiThread(() -> {
                nativeCameraStarted = started;
                flipCameraButton.setEnabled(started);
                clickButton.setEnabled(started);
                latestYuvFrameCount = 0;
                updateCameraSizeText();
                if (!started) {
                    Toast.makeText(this, "Native camera open failed", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void flipCamera() {
        desiredLensFacing = desiredLensFacing == CameraCharacteristics.LENS_FACING_BACK
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        openNativeCameraWithPermission();
    }

    private void capturePhoto() {
        if (!nativeCameraStarted || latestYuvFrameCount == 0) {
            Toast.makeText(this, "Open camera first", Toast.LENGTH_SHORT).show();
            return;
        }

        nativeCaptureNextNativeFrame();
    }

    public void onNativeFrame(int width, int height, int format, long frameCount) {
        latestYuvFrameCount = frameCount;
        runOnUiThread(() -> {
            if (nativeCameraStarted && capturedYuvPreview.getVisibility() != View.VISIBLE) {
                cameraSizeText.setText(
                        "Required: " + requiredResolution.label
                                + " (" + requiredResolution.width + " x " + requiredResolution.height + ")"
                                + " | Capture target: " + getCaptureTargetWidth()
                                + " x " + getCaptureTargetHeight()
                                + " | Native YUV_420_888: " + width + " x " + height
                );
            }
        });
    }

    public void onNativeCapturedPreview(int[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0) {
            runOnUiThread(() -> Toast.makeText(this, "Native capture failed", Toast.LENGTH_SHORT).show());
            return;
        }

        latestCapturedYuv420Width = width;
        latestCapturedYuv420Height = height;
        hasNativeCapturedFrame = true;
        Bitmap previewBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        runOnUiThread(() -> {
            capturedYuvPreview.setImageBitmap(previewBitmap);
            cameraSizeText.setText(
                    "Required: " + requiredResolution.label
                            + " | Capture target: " + getCaptureTargetWidth()
                            + " x " + getCaptureTargetHeight()
                            + " | Clicked Native YUV_420_888: "
                            + width + " x " + height
            );
            showCapturedPreviewMode();
        });
    }

    private void showCapturedPreviewMode() {
        nativeCameraPreview.setAlpha(0f);
        cameraSizeText.setVisibility(View.GONE);
        resolutionControls.setVisibility(View.GONE);
        openCameraButton.setVisibility(View.GONE);
        flipCameraButton.setVisibility(View.GONE);
        clickButton.setVisibility(View.GONE);
        capturedYuvPreview.setVisibility(View.VISIBLE);
        smoothCpuButton.setVisibility(View.VISIBLE);
        backButton.setVisibility(View.VISIBLE);
        cpuResultScroll.setVisibility(View.GONE);

        ConstraintLayout.LayoutParams imageParams = (ConstraintLayout.LayoutParams) capturedYuvPreview.getLayoutParams();
        imageParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
        imageParams.topToBottom = ConstraintLayout.LayoutParams.UNSET;
        imageParams.bottomToTop = R.id.smoothCpuButton;
        imageParams.setMargins(0, 0, 0, 0);
        capturedYuvPreview.setLayoutParams(imageParams);
    }

    private void showCameraPreviewMode() {
        capturedYuvPreview.setVisibility(View.GONE);
        smoothCpuButton.setVisibility(View.GONE);
        cpuResultScroll.setVisibility(View.GONE);
        backButton.setVisibility(View.GONE);
        nativeCameraPreview.setVisibility(View.VISIBLE);
        nativeCameraPreview.setAlpha(1f);
        cameraSizeText.setVisibility(View.VISIBLE);
        resolutionControls.setVisibility(View.VISIBLE);
        openCameraButton.setVisibility(View.VISIBLE);
        flipCameraButton.setVisibility(View.VISIBLE);
        clickButton.setVisibility(View.VISIBLE);

        updateCameraSizeText();

        ConstraintLayout.LayoutParams imageParams = (ConstraintLayout.LayoutParams) capturedYuvPreview.getLayoutParams();
        imageParams.topToTop = ConstraintLayout.LayoutParams.UNSET;
        imageParams.topToBottom = R.id.openCameraButton;
        imageParams.bottomToTop = R.id.smoothCpuButton;
        imageParams.setMargins(0, dp(12), 0, 0);
        capturedYuvPreview.setLayoutParams(imageParams);
    }

    private void updateCameraSizeText() {
        cameraSizeText.setText(
                "Required: " + requiredResolution.label
                        + " (" + requiredResolution.width + " x " + requiredResolution.height + ")"
                        + " | Capture target: " + getCaptureTargetWidth()
                        + " x " + getCaptureTargetHeight()
                        + (nativeCameraStarted ? " | Native camera opened" : "")
        );
    }

    private int getCaptureTargetWidth() {
        return requiredResolution.width * 2;
    }

    private int getCaptureTargetHeight() {
        return requiredResolution.height * 2;
    }

    private int getSmoothTargetWidth() {
        return isCapturedFramePortrait() ? requiredResolution.height : requiredResolution.width;
    }

    private int getSmoothTargetHeight() {
        return isCapturedFramePortrait() ? requiredResolution.width : requiredResolution.height;
    }

    private boolean isCapturedFramePortrait() {
        return latestCapturedYuv420Height > latestCapturedYuv420Width;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void stopNativeCameraInBackground() {
        if (!nativeCameraExecutor.isShutdown()) {
            nativeCameraExecutor.execute(this::nativeStopNativeCamera);
        }
    }

    private void releaseNativeCameraInBackground() {
        if (!nativeCameraExecutor.isShutdown()) {
            nativeCameraExecutor.execute(() -> {
                nativeStopNativeCamera();
                nativeReleaseController();
            });
        }
    }

    private void startImageSmootheningBYCpu() {
        if (!hasNativeCapturedFrame || latestCapturedYuv420Width <= 0 || latestCapturedYuv420Height <= 0) {
            Toast.makeText(this, "Click image first", Toast.LENGTH_SHORT).show();
            return;
        }

        smoothCpuButton.setEnabled(false);
        Toast.makeText(this, "CPU smoothening started", Toast.LENGTH_SHORT).show();
        imageSmootheningExecutor.execute(() -> {
            boolean started = nativeSmoothCapturedNativeFrame(
                    getSmoothTargetWidth(),
                    getSmoothTargetHeight(),
                    EDGE_THRESHOLD);
            if (!started) {
                runOnUiThread(() -> {
                    smoothCpuButton.setEnabled(true);
                    Toast.makeText(this, "Native smoothening failed", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public void onNativeSmootheningComplete(
            int[][] imagePixels,
            int[] widths,
            int[] heights,
            long[] timings,
            int yuv420ByteCount) {
        try {
            if (imagePixels == null || imagePixels.length < 5
                    || widths == null || widths.length < 5
                    || heights == null || heights.length < 5
                    || timings == null || timings.length < 6) {
                throw new IllegalArgumentException("Native smoothening result is invalid.");
            }

            Bitmap beforeBitmap = Bitmap.createBitmap(imagePixels[0], widths[0], heights[0], Bitmap.Config.ARGB_8888);
            Bitmap edgeMaskBitmap = Bitmap.createBitmap(imagePixels[1], widths[1], heights[1], Bitmap.Config.ARGB_8888);
            Bitmap uEdgeMaskBitmap = Bitmap.createBitmap(imagePixels[2], widths[2], heights[2], Bitmap.Config.ARGB_8888);
            Bitmap vEdgeMaskBitmap = Bitmap.createBitmap(imagePixels[3], widths[3], heights[3], Bitmap.Config.ARGB_8888);
            Bitmap noiseReducedBitmap = Bitmap.createBitmap(imagePixels[4], widths[4], heights[4], Bitmap.Config.ARGB_8888);
            String folderName = getNextOutputFolderName();

            long stepStartTime = System.nanoTime();
            byte[] beforePngData = bitmapToPng(beforeBitmap);
            byte[] edgeMaskPngData = bitmapToPng(edgeMaskBitmap);
            byte[] uEdgeMaskPngData = bitmapToPng(uEdgeMaskBitmap);
            byte[] vEdgeMaskPngData = bitmapToPng(vEdgeMaskBitmap);
            byte[] noiseReducedPngData = bitmapToPng(noiseReducedBitmap);
            long javaPngEncodingNs = elapsedNs(stepStartTime);

            stepStartTime = System.nanoTime();
            savePngData(beforePngData, "before.png", folderName);
            savePngData(edgeMaskPngData, "edge_mask.png", folderName);
            savePngData(uEdgeMaskPngData, "u_edge_mask.png", folderName);
            savePngData(vEdgeMaskPngData, "v_edge_mask.png", folderName);
            savePngData(noiseReducedPngData, "image_smoothened.png", folderName);
            long javaFileSavingNs = elapsedNs(stepStartTime);

            TimingReport timingReport = new TimingReport();
            timingReport.nativeResizeToRequiredNs = timings[0];
            timingReport.nativeYuv420ToBeforeBitmapNs = timings[1];
            timingReport.nativeEdgeMaskNs = timings[2];
            timingReport.nativeMedianFilterNs = timings[3];
            timingReport.nativeEdgeMaskBitmapNs = timings[4];
            timingReport.nativeYuv420ToBitmapNs = timings[5];
            timingReport.javaPngEncodingNs = javaPngEncodingNs;
            timingReport.javaFileSavingNs = javaFileSavingNs;
            timingReport.totalBeforeTextSaveNs = 0L;
            saveTextData(createTimingReportText(
                            timingReport,
                            widths[0],
                            heights[0],
                            yuv420ByteCount,
                            folderName),
                    "calculation_time.txt",
                    folderName);

            runOnUiThread(() -> {
                beforeImage.setImageBitmap(beforeBitmap);
                edgeMaskImage.setImageBitmap(edgeMaskBitmap);
                uEdgeMaskImage.setImageBitmap(uEdgeMaskBitmap);
                vEdgeMaskImage.setImageBitmap(vEdgeMaskBitmap);
                noiseReducedImage.setImageBitmap(noiseReducedBitmap);
                capturedYuvPreview.setVisibility(View.GONE);
                cpuResultScroll.setVisibility(View.VISIBLE);
                smoothCpuButton.setVisibility(View.GONE);
                backButton.setVisibility(View.VISIBLE);
                cpuResultScroll.post(() -> cpuResultScroll.scrollTo(0, 0));
                smoothCpuButton.setEnabled(true);
                Toast.makeText(this, "Saved in Native CPU/" + folderName, Toast.LENGTH_LONG).show();
            });
        } catch (Exception exception) {
            runOnUiThread(() -> {
                smoothCpuButton.setEnabled(true);
                Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }

    private byte[] bitmapToPng(Bitmap bitmap) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
        if (!compressed) {
            throw new IOException("Could not encode PNG.");
        }

        return outputStream.toByteArray();
    }

    private String getNextOutputFolderName() {
        int latestFolderNumber = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? getLatestMediaStoreFolderNumber()
                : getLatestLegacyFolderNumber();

        return String.valueOf(latestFolderNumber + 1);
    }

    private int getLatestMediaStoreFolderNumber() {
        String[] projection = {MediaStore.MediaColumns.RELATIVE_PATH};
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] selectionArgs = {OUTPUT_RELATIVE_DIR_PREFIX + "/%"};
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
                latestFolderNumber = Math.max(
                        latestFolderNumber,
                        parseFolderNumber(cursor.getString(relativePathColumn)));
            }
        } catch (Exception exception) {
            return 0;
        }

        return latestFolderNumber;
    }

    private int getLatestLegacyFolderNumber() {
        File parentFolder = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Test3WebStreamAndroidSdk/Image Smoothening/Native CPU");
        File[] folders = parentFolder.listFiles(File::isDirectory);
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

        String prefix = OUTPUT_RELATIVE_DIR_PREFIX + "/";
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

    private void savePngData(byte[] pngData, String fileName, String folderName) throws IOException {
        saveOutputData(pngData, fileName, "image/png", folderName);
    }

    private void saveTextData(String textData, String fileName, String folderName) throws IOException {
        saveOutputData(textData.getBytes(StandardCharsets.UTF_8), fileName, "text/plain", folderName);
    }

    private void saveOutputData(
            byte[] data,
            String fileName,
            String mimeType,
            String folderName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveOutputDataWithMediaStore(data, fileName, mimeType, folderName);
            return;
        }

        saveOutputDataWithFile(data, fileName, folderName);
    }

    private void saveOutputDataWithMediaStore(
            byte[] data,
            String fileName,
            String mimeType,
            String folderName) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                OUTPUT_RELATIVE_DIR.replace("{number}", folderName) + "/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Could not create output file.");
        }

        try (OutputStream outputStream = resolver.openOutputStream(uri)) {
            if (outputStream == null) {
                throw new IOException("Could not save output file.");
            }

            outputStream.write(data);
        }

        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }

    private void saveOutputDataWithFile(byte[] data, String fileName, String folderName)
            throws IOException {
        File folder = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Test3WebStreamAndroidSdk/Image Smoothening/Native CPU/" + folderName);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Could not create output folder.");
        }

        File outputFile = new File(folder, fileName);
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(data);
        }
    }

    private String createTimingReportText(
            TimingReport timingReport,
            int width,
            int height,
            int yuv420ByteCount,
            String folderName) {
        return "Image Smoothening Native Camera CPU Timing Report\n"
                + "Output folder: "
                + OUTPUT_RELATIVE_DIR.replace("{number}", folderName) + "\n"
                + "Required resolution: " + requiredResolution.label
                + " (" + requiredResolution.width + "x" + requiredResolution.height + ")\n"
                + "Native camera capture target: " + getCaptureTargetWidth()
                + "x" + getCaptureTargetHeight() + "\n"
                + "Image size: " + width + "x" + height + "\n"
                + "YUV420 byte count: " + yuv420ByteCount + "\n"
                + "Edge threshold: " + EDGE_THRESHOLD + "\n"
                + "Median filter window: 3x3\n\n"
                + "Native C resize captured YUV420 to required resolution time: "
                + formatMs(timingReport.nativeResizeToRequiredNs) + " ms\n"
                + "Native C resized YUV420 to before bitmap conversion time: "
                + formatMs(timingReport.nativeYuv420ToBeforeBitmapNs) + " ms\n"
                + "Native C YUV420 edge mask calculation time: "
                + formatMs(timingReport.nativeEdgeMaskNs) + " ms\n"
                + "Native C YUV420 median filter calculation time: "
                + formatMs(timingReport.nativeMedianFilterNs) + " ms\n"
                + "Native C edge mask bitmap conversion time: "
                + formatMs(timingReport.nativeEdgeMaskBitmapNs) + " ms\n"
                + "Native C YUV420 to bitmap conversion time: "
                + formatMs(timingReport.nativeYuv420ToBitmapNs) + " ms\n"
                + "Java PNG encoding time: "
                + formatMs(timingReport.javaPngEncodingNs) + " ms\n"
                + "Java file saving time: "
                + formatMs(timingReport.javaFileSavingNs) + " ms\n"
                + "Total time before writing this text file: "
                + formatMs(timingReport.totalBeforeTextSaveNs) + " ms\n";
    }

    private long elapsedNs(long startTimeNs) {
        return System.nanoTime() - startTimeNs;
    }

    private String formatMs(long nanos) {
        return String.format(Locale.US, "%.3f", nanos / 1_000_000.0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openNativeCameraWithPermission();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
        }
    }

    private native void nativeInitController();

    private native void nativeReleaseController();

    private native boolean nativeStartNativeCamera(Surface previewSurface, int width, int height, int lensFacing);

    private native void nativeCaptureNextNativeFrame();

    private native boolean nativeSmoothCapturedNativeFrame(int targetWidth, int targetHeight, int edgeThreshold);

    private native void nativeStopNativeCamera();

    private static final class TimingReport {
        long nativeResizeToRequiredNs;
        long nativeYuv420ToBeforeBitmapNs;
        long nativeEdgeMaskNs;
        long nativeMedianFilterNs;
        long nativeEdgeMaskBitmapNs;
        long nativeYuv420ToBitmapNs;
        long javaPngEncodingNs;
        long javaFileSavingNs;
        long totalBeforeTextSaveNs;
    }

    private enum RequiredResolution {
        SD("SD", 640, 480),
        HD("HD", 1280, 720),
        FHD("FHD", 1920, 1080),
        QHD("QHD", 2560, 1440),
        TWO_K("2K", 2048, 1080);

        final String label;
        final int width;
        final int height;

        RequiredResolution(String label, int width, int height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }
    }
}
