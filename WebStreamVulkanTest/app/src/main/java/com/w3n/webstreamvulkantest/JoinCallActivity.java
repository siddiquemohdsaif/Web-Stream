package com.w3n.webstreamvulkantest;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.w3n.webstreamvulkantest.ReceivedJpegVulkanView;
import com.w3n.webstreamvulkantest.WebStreamCall;
import com.w3n.webstreamvulkantest.WebStreamClient;
import com.w3n.webstreamvulkantest.WebStreamJpegFrame;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class JoinCallActivity extends AppCompatActivity {
    private static final String RENDER_TAG = "RenderJPEG_X";

    static {
        System.loadLibrary("webstream_vulkan_test");
    }

    private static final String SERVER_URL = "ws://168.144.23.108:8080";
    private static final int CAMERA_PERMISSION_REQUEST = 5010;
    private static final int STORAGE_PERMISSION_REQUEST = 5011;
    private static final int CAMERA_WIDTH = 1280;
    private static final int CAMERA_HEIGHT = 720;
    private static final int CAPTURE_TIMEOUT_MS = 3000;
    private static final int SEND_FRAME_RATE_FPS = 15;
    private static final int SEND_BITRATE_KBPS = 550;
    private static final int JPEG_QUALITY = 72;
    private static final int DOWNSAMPLE_MODE = 3;
    private static final String DOWNSAMPLE_SHADER = "downsample_3to2.comp.spv";
    private static final String SOBEL_SHADER = "sobel_6pixels.comp.spv";
    private static final String MEDIAN_V2_SHADER = "noise_filter_median_v2_packed.comp.spv";
    private static final int Y_THRESHOLD = 80;
    private static final int CHROMA_THRESHOLD = 40;
    private static final String OUTPUT_ROOT = "WebStreamVulkanTest";
    private static final String SEND_OUTPUT_DIR = "Send";
    private static final String RECEIVE_OUTPUT_DIR = "Receive";

    private EditText userIdInput;
    private EditText callIdInput;
    private Button joinButton;
    private TextView statusText;
    private TextView connectedText;
    private TextView callTimerText;
    private LinearLayout callControls;
    private Button flipCameraButton;
    private Button leaveButton;
    private Button microphoneButton;
    private ReceivedJpegVulkanView receivedJpegVulkanView;
    private ImageView localCameraPreview;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger sentJpegCount = new AtomicInteger();
    private final AtomicInteger receivedJpegCount = new AtomicInteger();
    private WebStreamClient webStreamClient;
    private WebStreamCall webStreamCall;
    private volatile boolean autoSending;
    private volatile int cameraSessionVersion;
    private volatile int currentDisplayRotationDegrees;
    private boolean connected;
    private boolean microphoneEnabled = true;
    private boolean useFrontCamera = true;
    private Bitmap latestLocalPreviewBitmap;
    private int saveSessionNumber;
    private long callStartedAtMs;
    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (!connected || callStartedAtMs == 0L) {
                return;
            }
            long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - callStartedAtMs) / 1000L);
            callTimerText.setText(formatDuration(elapsedSeconds));
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private static native String nativeStartCameraCapture(boolean front, int width, int height);
    private static native void nativeStopCameraCapture();
    private static native void nativeSetCameraDisplayRotation(int rotationDegrees);
    private static native int[] nativeCaptureNextFrameArgb(int timeoutMs);
    private static native String nativeRunPreprocessPipeline(
            Bitmap bitmap,
            byte[] output,
            int width,
            int height,
            int mode,
            byte[] downsampleShader,
            byte[] sobelShader,
            byte[] medianShader,
            int yThreshold,
            int chromaThreshold);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_join_call);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        userIdInput = findViewById(R.id.userIdInput);
        callIdInput = findViewById(R.id.callIdInput);
        joinButton = findViewById(R.id.connectButton);
        statusText = findViewById(R.id.statusText);
        connectedText = findViewById(R.id.connectedText);
        callTimerText = findViewById(R.id.callTimerText);
        callControls = findViewById(R.id.callControls);
        flipCameraButton = findViewById(R.id.flipCameraButton);
        leaveButton = findViewById(R.id.leaveButton);
        microphoneButton = findViewById(R.id.microphoneButton);
        receivedJpegVulkanView = findViewById(R.id.receivedJpegVulkanView);
        localCameraPreview = findViewById(R.id.localCameraPreview);
        joinButton.setOnClickListener(v -> joinCall());
        flipCameraButton.setOnClickListener(v -> flipCamera());
        leaveButton.setOnClickListener(v -> leaveCall());
        microphoneButton.setOnClickListener(v -> toggleMicrophone());
        showPreCallUi("");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCameraDisplayRotation();
        if (connected) {
            startAutoCaptureIfPermitted();
        }
    }

    @Override
    protected void onPause() {
        stopAutoCapture();
        super.onPause();
    }

    private void joinCall() {
        String userId = userIdInput.getText().toString().trim();
        String callId = callIdInput.getText().toString().trim();

        if (TextUtils.isEmpty(userId)) {
            userIdInput.setError("Required");
            return;
        }
        if (TextUtils.isEmpty(callId)) {
            callIdInput.setError("Required");
            return;
        }

        if (webStreamCall != null) {
            webStreamCall.leave();
            webStreamCall = null;
        }
        if (webStreamClient != null) {
            webStreamClient.release();
            webStreamClient = null;
        }
        resetSaveSession();

        joinButton.setEnabled(false);
        statusText.setText("Connecting devices...");
        webStreamClient = new WebStreamClient.Builder(this)
                .userId(userId)
                .displayName(userId)
                .serverUrl(SERVER_URL)
                .build();
        webStreamCall = webStreamClient.joinCall(callId, new WebStreamClient.Listener() {
            @Override
            public void onConnecting() {
                statusText.setText("Connecting devices...");
            }

            @Override
            public void onConnected() {
                connected = true;
                Log.d(RENDER_TAG, "Call connected: showing render view");
                showConnectedUi();
                startAutoCaptureIfPermitted();
            }

            @Override
            public void onJpegReceived(WebStreamJpegFrame frame) {
                Log.d(RENDER_TAG, "onJpegReceived participant=" + frame.getParticipantId()
                        + " bytes=" + frame.getJpegData().length
                        + " width=" + frame.getWidth()
                        + " height=" + frame.getHeight()
                        + " sequence=" + frame.getSequence());
                receivedJpegVulkanView.renderJpeg(frame.getJpegData());
                saveJpegAsync(RECEIVE_OUTPUT_DIR, "receive", frame.getJpegData(), receivedJpegCount);
            }

            @Override
            public void onDisconnected() {
                connected = false;
                stopAutoCapture();
                showPreCallUi("Devices disconnected");
            }

            @Override
            public void onError(Throwable error) {
                connected = false;
                stopAutoCapture();
                showPreCallUi(error == null ? "Connection error" : error.getMessage());
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAutoCaptureIfPermitted();
            } else {
                statusText.setText("Camera permission denied");
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAutoCaptureIfPermitted();
            } else {
                statusText.setText("Storage permission denied");
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopAutoCapture();
        mainHandler.removeCallbacks(timerTick);
        if (webStreamCall != null) {
            webStreamCall.leave();
            webStreamCall = null;
        }
        if (webStreamClient != null) {
            webStreamClient.release();
            webStreamClient = null;
        }
        sendExecutor.shutdownNow();
        saveExecutor.shutdownNow();
        recycleLocalPreviewBitmap();
        super.onDestroy();
    }

    private void showPreCallUi(String message) {
        mainHandler.removeCallbacks(timerTick);
        callStartedAtMs = 0L;
        userIdInput.setVisibility(View.VISIBLE);
        callIdInput.setVisibility(View.VISIBLE);
        joinButton.setVisibility(View.VISIBLE);
        joinButton.setEnabled(true);
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(message == null ? "" : message);
        receivedJpegVulkanView.setVisibility(View.INVISIBLE);
        localCameraPreview.setVisibility(View.GONE);
        localCameraPreview.setImageDrawable(null);
        recycleLocalPreviewBitmap();
        connectedText.setVisibility(View.GONE);
        callTimerText.setVisibility(View.GONE);
        callControls.setVisibility(View.GONE);
    }

    private void showConnectedUi() {
        userIdInput.setVisibility(View.GONE);
        callIdInput.setVisibility(View.GONE);
        joinButton.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        receivedJpegVulkanView.setVisibility(View.VISIBLE);
        localCameraPreview.setVisibility(View.VISIBLE);
        localCameraPreview.bringToFront();
        connectedText.setVisibility(View.VISIBLE);
        connectedText.bringToFront();
        connectedText.setText("Connected");
        callTimerText.setVisibility(View.VISIBLE);
        callTimerText.bringToFront();
        callControls.setVisibility(View.VISIBLE);
        callControls.bringToFront();
        Log.d(RENDER_TAG, "Render view visible size="
                + receivedJpegVulkanView.getWidth() + "x" + receivedJpegVulkanView.getHeight());
        callStartedAtMs = System.currentTimeMillis();
        callTimerText.setText("00:00");
        mainHandler.removeCallbacks(timerTick);
        mainHandler.post(timerTick);
    }

    private void leaveCall() {
        connected = false;
        stopAutoCapture();
        if (webStreamCall != null) {
            webStreamCall.leave();
            webStreamCall = null;
        }
        if (webStreamClient != null) {
            webStreamClient.release();
            webStreamClient = null;
        }
        resetSaveSession();
        showPreCallUi("");
    }

    private void flipCamera() {
        useFrontCamera = !useFrontCamera;
        if (!connected) {
            return;
        }
        stopAutoCapture();
        startAutoCaptureIfPermitted();
    }

    private void toggleMicrophone() {
        microphoneEnabled = !microphoneEnabled;
        microphoneButton.setText(microphoneEnabled ? "Mic On" : "Mic Off");
    }

    private String formatDuration(long elapsedSeconds) {
        long minutes = elapsedSeconds / 60L;
        long seconds = elapsedSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void startAutoCaptureIfPermitted() {
        if (!connected || webStreamCall == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST);
            return;
        }
        if (autoSending) {
            return;
        }
        updateCameraDisplayRotation();
        autoSending = true;
        int sessionVersion = ++cameraSessionVersion;
        statusText.setText("Starting native camera capture...");
        sendExecutor.execute(() -> capturePreprocessAndSendLoop(sessionVersion));
    }

    private void stopAutoCapture() {
        autoSending = false;
        cameraSessionVersion++;
        nativeStopCameraCapture();
    }

    private void capturePreprocessAndSendLoop(int sessionVersion) {
        String startResult = nativeStartCameraCapture(useFrontCamera, CAMERA_WIDTH, CAMERA_HEIGHT);
        runOnUiThread(() -> statusText.setText(startResult));
        if (startResult != null && startResult.startsWith("Error:")) {
            autoSending = false;
            return;
        }

        while (autoSending && webStreamCall != null
                && sessionVersion == cameraSessionVersion
                && webStreamCall.getState() == WebStreamCall.State.CONNECTED) {
            try {
                ProcessedJpeg processedJpeg = capturePreprocessAndEncodeJpeg();
                WebStreamCall activeCall = webStreamCall;
                if (activeCall != null && autoSending) {
                    activeCall.sendJpeg(
                            processedJpeg.jpegData,
                            processedJpeg.width,
                            processedJpeg.height,
                            SEND_FRAME_RATE_FPS,
                            SEND_BITRATE_KBPS,
                            System.currentTimeMillis());
                    updateLocalCameraPreview(processedJpeg.jpegData);
                    saveJpegAsync(SEND_OUTPUT_DIR, "send", processedJpeg.jpegData, sentJpegCount);
                    runOnUiThread(() -> statusText.setText(
                            "Sent preprocessed JPEG: "
                                    + processedJpeg.width + "x" + processedJpeg.height
                                    + " (" + processedJpeg.jpegData.length + " bytes)"));
                }
                Thread.sleep(Math.max(1, 1000 / SEND_FRAME_RATE_FPS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception error) {
                runOnUiThread(() -> statusText.setText("Send pipeline error: " + error.getMessage()));
            }
        }
        nativeStopCameraCapture();
    }

    private void updateLocalCameraPreview(byte[] jpegData) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        if (bitmap == null) {
            Log.e(RENDER_TAG, "Local floating preview decode failed bytes=" + jpegData.length);
            return;
        }
        runOnUiThread(() -> {
            if (!connected || localCameraPreview == null) {
                bitmap.recycle();
                return;
            }
            Bitmap previous = latestLocalPreviewBitmap;
            latestLocalPreviewBitmap = bitmap;
            localCameraPreview.setImageBitmap(bitmap);
            localCameraPreview.setVisibility(View.VISIBLE);
            localCameraPreview.bringToFront();
            connectedText.bringToFront();
            callTimerText.bringToFront();
            callControls.bringToFront();
            if (previous != null && !previous.isRecycled()) {
                previous.recycle();
            }
            Log.d(RENDER_TAG, "Local floating preview updated bitmap="
                    + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " bytes=" + jpegData.length);
        });
    }

    private void recycleLocalPreviewBitmap() {
        Bitmap bitmap = latestLocalPreviewBitmap;
        latestLocalPreviewBitmap = null;
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private ProcessedJpeg capturePreprocessAndEncodeJpeg() throws Exception {
        int[] frame = nativeCaptureNextFrameArgb(CAPTURE_TIMEOUT_MS);
        if (frame == null || frame.length < 3) {
            throw new IllegalStateException("No camera frame captured");
        }

        int width = frame[0];
        int height = frame[1];
        int[] pixels = new int[width * height];
        System.arraycopy(frame, 2, pixels, 0, pixels.length);
        Bitmap captured = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        try {
            Bitmap processed = preprocessCapturedBitmap(captured);
            try {
                Bitmap outgoing = orientOutgoingJpegBitmap(processed);
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (!outgoing.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        throw new IllegalStateException("JPEG encode failed");
                    }
                    return new ProcessedJpeg(output.toByteArray(), outgoing.getWidth(), outgoing.getHeight());
                } finally {
                    if (outgoing != processed) {
                        outgoing.recycle();
                    }
                }
            } finally {
                processed.recycle();
            }
        } finally {
            captured.recycle();
        }
    }

    private Bitmap preprocessCapturedBitmap(Bitmap captured) throws Exception {
        int widthMultiple = 12;
        int heightMultiple = 3;
        int srcWidth = captured.getWidth() - captured.getWidth() % widthMultiple;
        int srcHeight = captured.getHeight() - captured.getHeight() % heightMultiple;
        int outWidth = srcWidth / 3 * 2;
        int outHeight = srcHeight / 3 * 2;
        int planeBytes = outWidth * outHeight;
        byte[] filtered = new byte[planeBytes * 3];

        String preprocessReport = nativeRunPreprocessPipeline(
                captured,
                filtered,
                srcWidth,
                srcHeight,
                DOWNSAMPLE_MODE,
                readAsset(DOWNSAMPLE_SHADER),
                readAsset(SOBEL_SHADER),
                readAsset(MEDIAN_V2_SHADER),
                Y_THRESHOLD,
                CHROMA_THRESHOLD);
        throwIfNativeError("preprocess", preprocessReport);

        return yuv444PlanarToBitmap(filtered, outWidth, outHeight);
    }

    private Bitmap orientOutgoingJpegBitmap(Bitmap bitmap) {
        int rotationDegrees = getOutgoingJpegRotationDegrees(bitmap.getWidth(), bitmap.getHeight());
        if (rotationDegrees == 0) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        Log.d(RENDER_TAG, "Outgoing JPEG rotated degrees=" + rotationDegrees
                + " front=" + useFrontCamera
                + " display=" + currentDisplayRotationDegrees
                + " from=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " to=" + rotated.getWidth() + "x" + rotated.getHeight());
        return rotated;
    }

    private int getOutgoingJpegRotationDegrees(int width, int height) {
        if (height >= width) {
            return 0;
        }
        int displayRotation = currentDisplayRotationDegrees;
        int portraitRotation = useFrontCamera ? 270 : 90;
        return (portraitRotation - displayRotation + 360) % 360;
    }

    private void resetSaveSession() {
        saveSessionNumber = 0;
        sentJpegCount.set(0);
        receivedJpegCount.set(0);
    }

    private void saveJpegAsync(
            String directionDir,
            String filePrefix,
            byte[] jpegData,
            AtomicInteger counter) {
        int sessionNumber = ensureSaveSessionNumber();
        int fileNumber = counter.incrementAndGet();
        byte[] jpegCopy = jpegData.clone();
        saveExecutor.execute(() -> {
            try {
                saveJpeg(sessionNumber, directionDir, filePrefix, fileNumber, jpegCopy);
            } catch (IOException error) {
                runOnUiThread(() -> statusText.setText("JPEG save error: " + error.getMessage()));
            }
        });
    }

    private synchronized int ensureSaveSessionNumber() {
        if (saveSessionNumber == 0) {
            saveSessionNumber = findNextSaveSessionNumber();
        }
        return saveSessionNumber;
    }

    private int findNextSaveSessionNumber() {
        int maxSessionNumber = findMaxLegacySaveSessionNumber();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            maxSessionNumber = Math.max(maxSessionNumber, findMaxMediaStoreSaveSessionNumber());
        }
        return maxSessionNumber + 1;
    }

    private int findMaxLegacySaveSessionNumber() {
        File root = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                OUTPUT_ROOT);
        File[] children = root.listFiles();
        if (children == null) {
            return 0;
        }
        int maxSessionNumber = 0;
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            try {
                maxSessionNumber = Math.max(maxSessionNumber, Integer.parseInt(child.getName()));
            } catch (NumberFormatException ignored) {
                // Ignore folders that are not numeric capture sessions.
            }
        }
        return maxSessionNumber;
    }

    private int findMaxMediaStoreSaveSessionNumber() {
        String relativePathColumn = MediaStore.MediaColumns.RELATIVE_PATH;
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{relativePathColumn};
        String selection = relativePathColumn + " LIKE ?";
        String[] selectionArgs = new String[]{
                Environment.DIRECTORY_DOWNLOADS + "/" + OUTPUT_ROOT + "/%"};
        int maxSessionNumber = 0;
        try (android.database.Cursor cursor = getContentResolver().query(
                collection,
                projection,
                selection,
                selectionArgs,
                null)) {
            if (cursor == null) {
                return 0;
            }
            int pathIndex = cursor.getColumnIndexOrThrow(relativePathColumn);
            while (cursor.moveToNext()) {
                maxSessionNumber = Math.max(
                        maxSessionNumber,
                        parseSessionNumber(cursor.getString(pathIndex)));
            }
        } catch (RuntimeException ignored) {
            return 0;
        }
        return maxSessionNumber;
    }

    private int parseSessionNumber(String relativePath) {
        if (relativePath == null) {
            return 0;
        }
        String marker = OUTPUT_ROOT + "/";
        int markerIndex = relativePath.indexOf(marker);
        if (markerIndex < 0) {
            return 0;
        }
        int numberStart = markerIndex + marker.length();
        int numberEnd = relativePath.indexOf('/', numberStart);
        if (numberEnd < 0) {
            numberEnd = relativePath.length();
        }
        try {
            return Integer.parseInt(relativePath.substring(numberStart, numberEnd));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private void saveJpeg(
            int sessionNumber,
            String directionDir,
            String filePrefix,
            int fileNumber,
            byte[] jpegData) throws IOException {
        String fileName = String.format(
                Locale.US,
                "%s_%06d_%d.jpg",
                filePrefix,
                fileNumber,
                System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveJpegWithMediaStore(sessionNumber, directionDir, fileName, jpegData);
        } else {
            saveJpegWithLegacyFile(sessionNumber, directionDir, fileName, jpegData);
        }
    }

    private void saveJpegWithMediaStore(
            int sessionNumber,
            String directionDir,
            String fileName,
            byte[] jpegData) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + OUTPUT_ROOT + "/"
                        + sessionNumber + "/" + directionDir);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Unable to create JPEG file");
        }

        try {
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) {
                    throw new IOException("Unable to open JPEG output stream");
                }
                output.write(jpegData);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } catch (IOException error) {
            resolver.delete(uri, null, null);
            throw error;
        }
    }

    private void saveJpegWithLegacyFile(
            int sessionNumber,
            String directionDir,
            String fileName,
            byte[] jpegData) throws IOException {
        File outputDir = new File(
                new File(
                        new File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                OUTPUT_ROOT),
                        String.valueOf(sessionNumber)),
                directionDir);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Unable to create " + outputDir.getAbsolutePath());
        }
        File outputFile = new File(outputDir, fileName);
        try (FileOutputStream output = new FileOutputStream(outputFile)) {
            output.write(jpegData);
        }
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream stream = getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void throwIfNativeError(String stage, String report) {
        if (report != null && report.startsWith("Error:")) {
            throw new IllegalStateException(stage + " failed: " + report);
        }
    }

    private Bitmap yuv444PlanarToBitmap(byte[] yuv, int width, int height) {
        int pixelCount = width * height;
        int[] argb = new int[pixelCount];
        for (int i = 0; i < pixelCount; i++) {
            int y = yuv[i] & 0xff;
            int cb = (yuv[pixelCount + i] & 0xff) - 128;
            int cr = (yuv[pixelCount * 2 + i] & 0xff) - 128;
            int r = clamp(Math.round(y + 1.402f * cr));
            int g = clamp(Math.round(y - 0.344136f * cb - 0.714136f * cr));
            int b = clamp(Math.round(y + 1.772f * cb));
            argb[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
    }

    private int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }

    private void updateCameraDisplayRotation() {
        currentDisplayRotationDegrees = readDisplayRotationDegrees();
        if (getDisplay() != null) {
            nativeSetCameraDisplayRotation(currentDisplayRotationDegrees);
        }
    }

    private int readDisplayRotationDegrees() {
        if (getDisplay() == null) {
            return 0;
        }
        switch (getDisplay().getRotation()) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    private static final class ProcessedJpeg {
        final byte[] jpegData;
        final int width;
        final int height;

        ProcessedJpeg(byte[] jpegData, int width, int height) {
            this.jpegData = jpegData;
            this.width = width;
            this.height = height;
        }
    }
}
