package com.w3n.webstreamandroidsdk;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
import com.w3n.webstream.Util.H264FrameBatchDecoder;
import com.w3n.webstream.Util.H264FrameBatchEncoder;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;

public class CameraRecordingActivity extends AppCompatActivity {
    private static final String TAG = "CameraRecordingActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;
    private static final int FRAME_RATE_FPS = 60;
    private static final int BATCH_FRAME_COUNT = 5;

    private TextView statusText;
    private TextView detailsText;
    private TextView elapsedTimeText;
    private ImageView decodedPreviewImage;
    private Button encodeButton;
    private Button decodeButton;

    private CameraController cameraController;
    private H264FrameBatchEncoder encoder;
    private H264FrameBatchDecoder decoder;

    private long cameraFrames;
    private long encodedBatches;
    private long encodedBytes;
    private long decodedFrames;

    private long totalEncodeFrameCallTimeNs;
    private long totalBatchEncodeTimeNs;

    private long decodedBatches;
    private long totalBatchDecodeTimeNs;

    private long rawDecodedFrames;
    private long totalRawMediaCodecDecodeTimeNs;

    private long encodingStartTimeMs;
    private final Handler elapsedTimeHandler = new Handler(Looper.getMainLooper());

    private final Runnable elapsedTimeRunnable = new Runnable() {
        @Override
        public void run() {
            updateElapsedTime();

            if (encoding) {
                elapsedTimeHandler.postDelayed(this, 1000L);
            }
        }
    };

    private final Queue<DecodeBatchTiming> pendingDecodeBatches = new ArrayDeque<>();

    private boolean encoding;
    private boolean decoding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_camera_recording);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cameraRecordingRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    systemBars.left + v.getPaddingLeft(),
                    systemBars.top + v.getPaddingTop(),
                    systemBars.right + v.getPaddingRight(),
                    systemBars.bottom + v.getPaddingBottom());
            return insets;
        });

        statusText = findViewById(R.id.recordingStatusText);
        detailsText = findViewById(R.id.recordingDetailsText);
        elapsedTimeText = findViewById(R.id.elapsedTimeText);
        decodedPreviewImage = findViewById(R.id.decodedPreviewImage);
        encodeButton = findViewById(R.id.encodeButton);
        decodeButton = findViewById(R.id.decodeButton);

        encodeButton.setOnClickListener(v -> {
            if (encoding) {
                stopEncoding();
            } else {
                startEncodingWithPermission();
            }
        });

        decodeButton.setOnClickListener(v -> {
            if (decoding) {
                stopDecoder();
            } else {
                startDecoder();
            }
        });

        updateControls();
        updateDetails();
        resetElapsedTimer();
    }

    private void startEncodingWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startEncoding();
            return;
        }

        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_CAMERA_PERMISSION);
    }

    private void startEncoding() {
        if (encoding) {
            return;
        }

        try {
            resetCounters();
            startDecoder();

            encoder = new H264FrameBatchEncoder(
                    new H264FrameBatchEncoder.Config.Builder()
                            .setSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                            .setFrameRateFps(FRAME_RATE_FPS)
                            .setBitrateKbps(1200)
                            .setBatchFrameCount(BATCH_FRAME_COUNT)
                            .setInputYuvFormat(H264FrameBatchEncoder.InputYuvFormat.NV21)
                            .build(),
                    encoderCallback);
            encoder.start();

            cameraController = new CameraController(
                    this,
                    new CameraController.Config.Builder()
                            .setSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                            .setFrameRateFps(FRAME_RATE_FPS)
                            .setFrameType(CameraController.FrameType.IMAGE_READER_YUV)
                            .setCameraFacing(CameraController.CameraFacing.FRONT)
                            .build(),
                    cameraCallback);
            cameraController.start();

            encoding = true;
            startElapsedTimer();

            showStatus("Encoding camera frames and rendering decoded output...");
            updateControls();
        } catch (Exception error) {
            showError(error);
            stopEncoding();
        }
    }

    private final CameraController.CameraCallback cameraCallback =
            new CameraController.CameraCallback() {
                @Override
                public void onImageFrameAvailable(CameraController.CameraFrame frame) {
                    cameraFrames++;

                    if (encoder != null && frame.yuv420Data != null) {
                        long encodeStartNs = System.nanoTime();
                        encoder.encodeFrame(frame.yuv420Data, frame.timestampNs / 1_000_000L);
                        totalEncodeFrameCallTimeNs += System.nanoTime() - encodeStartNs;
                    }

                    runOnUiThread(CameraRecordingActivity.this::updateDetails);
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
                    runOnUiThread(CameraRecordingActivity.this::stopEncoding);
                }
            };

    private final H264FrameBatchEncoder.Callback encoderCallback =
            new H264FrameBatchEncoder.Callback() {
                @Override
                public void onEncodedFrameBatchAvailable(
                        byte[] encodedBatch,
                        int width,
                        int height,
                        long timestampMs,
                        long batchSequence) {
                    encodedBatches = batchSequence;
                    encodedBytes += encodedBatch.length;

                    Log.d(TAG,
                            "Video chunk available. batchSequence="
                                    + batchSequence
                                    + ", bytes=" + encodedBatch.length
                                    + ", timestampMs=" + timestampMs);

                    if (decoder != null && decoder.isStarted()) {
                        synchronized (pendingDecodeBatches) {
                            pendingDecodeBatches.offer(new DecodeBatchTiming(
                                    batchSequence,
                                    BATCH_FRAME_COUNT,
                                    System.nanoTime()));
                        }

                        decoder.onDecodeChunk(encodedBatch);
                    }

                    runOnUiThread(CameraRecordingActivity.this::updateDetails);
                }

                @Override
                public void onBatchEncodeTimingAvailable(
                        long batchSequence,
                        int frameCount,
                        long batchDurationNs,
                        boolean partialBatch,
                        int encodedByteCount) {
                    totalBatchEncodeTimeNs += batchDurationNs;

                    Log.d(TAG,
                            "H.264 batch encoded. batchSequence="
                                    + batchSequence
                                    + ", frameCount=" + frameCount
                                    + ", partialBatch=" + partialBatch
                                    + ", bytes=" + encodedByteCount
                                    + ", durationMs="
                                    + String.format(Locale.US, "%.3f", batchDurationNs / 1_000_000.0));
                }

                @Override
                public void onEncoderStarted() {
                    showStatus("Encoder started.");
                }

                @Override
                public void onEncoderStopped() {
                    showStatus("Encoder stopped.");
                }

                @Override
                public void onEncoderError(Exception error) {
                    showError(error);
                    runOnUiThread(CameraRecordingActivity.this::stopEncoding);
                }
            };

    private void startDecoder() {
        if (decoding) {
            return;
        }

        decoder = new H264FrameBatchDecoder(
                new H264FrameBatchDecoder.Config.Builder()
                        .setSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                        .setFrameRateFps(FRAME_RATE_FPS)
                        .build(),
                decoderCallback);
        decoder.start();

        decoding = true;
        updateControls();
        updateDetails();
    }

    private final H264FrameBatchDecoder.Callback decoderCallback =
            new H264FrameBatchDecoder.Callback() {
                @Override
                public void onImageAvailable(H264FrameBatchDecoder.DecodedFrame frame) {
                    decodedFrames = frame.sequence;

                    recordDecodedFrameForBatchTiming();

                    Bitmap bitmap = frame.bitmap;

                    Log.d(TAG,
                            "Decoded image available. sequence="
                                    + frame.sequence
                                    + ", width=" + frame.width
                                    + ", height=" + frame.height
                                    + ", timestampNs=" + frame.timestampNs
                                    + ", decodedBatches=" + decodedBatches);

                    runOnUiThread(() -> {
                        if (bitmap != null) {
                            decodedPreviewImage.setImageBitmap(bitmap);
                        }
                        updateDetails();
                    });
                }

                @Override
                public void onRawMediaCodecDecodeTimingAvailable(
                        long frameSequence,
                        long decodeDurationNs) {
                    rawDecodedFrames++;
                    totalRawMediaCodecDecodeTimeNs += decodeDurationNs;

                    Log.d(TAG,
                            "Raw MediaCodec frame decoded. frameSequence="
                                    + frameSequence
                                    + ", durationMs="
                                    + String.format(Locale.US, "%.3f", decodeDurationNs / 1_000_000.0));

                    runOnUiThread(CameraRecordingActivity.this::updateDetails);
                }

                @Override
                public void onDecoderStarted() {
                    showStatus("Decoder started.");
                }

                @Override
                public void onDecoderStopped() {
                    showStatus("Decoder stopped.");
                }

                @Override
                public void onDecoderError(Exception error) {
                    showError(error);
                    runOnUiThread(CameraRecordingActivity.this::stopDecoder);
                }
            };

    private void stopEncoding() {
        if (cameraController != null) {
            cameraController.release();
            cameraController = null;
        }

        if (encoder != null) {
            encoder.flush(System.currentTimeMillis());
            encoder.release();
            encoder = null;
        }

        encoding = false;
        stopElapsedTimer();

        showStatus("Encoding stopped.");
        updateControls();
        updateDetails();
    }

    private void stopDecoder() {
        if (decoder != null) {
            decoder.release();
            decoder = null;
        }

        decoding = false;
        showStatus("Decoder stopped.");
        updateControls();
        updateDetails();
    }

    private void resetCounters() {
        cameraFrames = 0L;
        encodedBatches = 0L;
        encodedBytes = 0L;
        decodedFrames = 0L;

        totalEncodeFrameCallTimeNs = 0L;
        totalBatchEncodeTimeNs = 0L;

        decodedBatches = 0L;
        totalBatchDecodeTimeNs = 0L;

        rawDecodedFrames = 0L;
        totalRawMediaCodecDecodeTimeNs = 0L;

        synchronized (pendingDecodeBatches) {
            pendingDecodeBatches.clear();
        }

        resetElapsedTimer();
    }

    private void startElapsedTimer() {
        encodingStartTimeMs = System.currentTimeMillis();

        elapsedTimeHandler.removeCallbacks(elapsedTimeRunnable);
        updateElapsedTime();
        elapsedTimeHandler.postDelayed(elapsedTimeRunnable, 1000L);
    }

    private void stopElapsedTimer() {
        elapsedTimeHandler.removeCallbacks(elapsedTimeRunnable);
        updateElapsedTime();
    }

    private void resetElapsedTimer() {
        encodingStartTimeMs = 0L;

        if (elapsedTimeText != null) {
            elapsedTimeText.setText("Elapsed time: 00:00");
        }
    }

    private void updateElapsedTime() {
        if (elapsedTimeText == null) {
            return;
        }

        if (encodingStartTimeMs <= 0L) {
            elapsedTimeText.setText("Elapsed time: 00:00");
            return;
        }

        long elapsedMs = System.currentTimeMillis() - encodingStartTimeMs;
        long totalSeconds = elapsedMs / 1000L;

        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0) {
            elapsedTimeText.setText(String.format(
                    Locale.US,
                    "Elapsed time: %02d:%02d:%02d",
                    hours,
                    minutes,
                    seconds
            ));
        } else {
            elapsedTimeText.setText(String.format(
                    Locale.US,
                    "Elapsed time: %02d:%02d",
                    minutes,
                    seconds
            ));
        }
    }

    private void recordDecodedFrameForBatchTiming() {
        DecodeBatchTiming completedBatch = null;

        synchronized (pendingDecodeBatches) {
            DecodeBatchTiming currentBatch = pendingDecodeBatches.peek();
            if (currentBatch == null) {
                return;
            }

            currentBatch.decodedFrames++;

            if (currentBatch.decodedFrames >= currentBatch.expectedFrames) {
                pendingDecodeBatches.poll();
                completedBatch = currentBatch;
            }
        }

        if (completedBatch == null) {
            return;
        }

        long batchDecodeDurationNs = System.nanoTime() - completedBatch.startTimeNs;
        decodedBatches++;
        totalBatchDecodeTimeNs += batchDecodeDurationNs;

        Log.d(TAG,
                "H.264 batch decoded end-to-end. batchSequence="
                        + completedBatch.batchSequence
                        + ", frameCount=" + completedBatch.decodedFrames
                        + ", durationMs="
                        + String.format(Locale.US, "%.3f", batchDecodeDurationNs / 1_000_000.0));
    }

    private void updateControls() {
        encodeButton.setText(encoding ? "Stop Encoding" : "Encode");
        decodeButton.setText(decoding ? "Stop Decoder" : "Decode");
        decodeButton.setEnabled(!encoding || decoding);
    }

    private void updateDetails() {
        detailsText.setText(String.format(
                Locale.US,
                "Camera frames: %d\n" +
                        "Encoded batches: %d\n" +
                        "Encoded bytes: %.2f KB\n" +
                        "Decoded frames: %d\n" +
                        "Decoded batches: %d\n" +
                        "Raw decoded frames: %d\n" +
                        "Avg encode call: %.3f ms\n" +
                        "Avg batch encode: %.3f ms\n" +
                        "Avg batch decode end-to-end: %.3f ms\n" +
                        "Avg raw MediaCodec decode: %.3f ms",
                cameraFrames,
                encodedBatches,
                encodedBytes / 1024.0,
                decodedFrames,
                decodedBatches,
                rawDecodedFrames,
                cameraFrames > 0
                        ? totalEncodeFrameCallTimeNs / 1_000_000.0 / cameraFrames
                        : 0.0,
                encodedBatches > 0
                        ? totalBatchEncodeTimeNs / 1_000_000.0 / encodedBatches
                        : 0.0,
                decodedBatches > 0
                        ? totalBatchDecodeTimeNs / 1_000_000.0 / decodedBatches
                        : 0.0,
                rawDecodedFrames > 0
                        ? totalRawMediaCodecDecodeTimeNs / 1_000_000.0 / rawDecodedFrames
                        : 0.0));
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

        if (requestCode == REQUEST_CAMERA_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startEncoding();
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            showStatus("Camera permission is required to encode.");
        }
    }

    @Override
    protected void onDestroy() {
        elapsedTimeHandler.removeCallbacks(elapsedTimeRunnable);
        stopEncoding();
        stopDecoder();
        super.onDestroy();
    }

    private static final class DecodeBatchTiming {
        final long batchSequence;
        final int expectedFrames;
        final long startTimeNs;
        int decodedFrames;

        DecodeBatchTiming(
                long batchSequence,
                int expectedFrames,
                long startTimeNs) {
            this.batchSequence = batchSequence;
            this.expectedFrames = expectedFrames;
            this.startTimeNs = startTimeNs;
        }
    }
}