package com.w3n.webstreamandroidsdk;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
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
import java.util.Random;

public class CameraRecordingGLViewActivity extends AppCompatActivity {
    private static final String TAG = "CameraRecordingGLViewActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 2001;
    private static final int VIDEO_WIDTH = 1280;
    private static final int VIDEO_HEIGHT = 720;
    private static final int FRAME_RATE_FPS = 15;
    private static final int BATCH_FRAME_COUNT = 5;
    private static final int MAX_PENDING_RENDER_TIMING_BATCHES = 2;

    private TextView statusText;
    private TextView detailsText;
    private TextView elapsedTimeText;
    private Button encodeButton;
    private Button decodeButton;
    private Bitmap latestDecodedPreviewBitmap;

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
    private long decoderHandledBatches;
    private long totalDecodeBatchHandleTimeNs;
    private long totalDecodeAccessUnits;
    private long decoderHandledWindowStartMs;
    private long decoderHandledBatchesInWindow;
    private long decoderHandledBatchesLastSecond;
    private int queuedDecodedFrames;

    private long rawDecodedFrames;
    private long totalRawMediaCodecDecodeTimeNs;
    private long droppedDecodedFrames;

    private long encodingStartTimeMs;
    private final Handler elapsedTimeHandler = new Handler(Looper.getMainLooper());

    private GLSurfaceView glSurfaceView;
    private YuvFrameRenderer renderer;

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
        setContentView(R.layout.activity_camera_recording_glview);

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
        //decodedPreviewImage = findViewById(R.id.decodedPreviewImage);
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


        glSurfaceView = findViewById(R.id.decodedPreviewImage);

        glSurfaceView.setEGLContextClientVersion(2);

        renderer = new YuvFrameRenderer(VIDEO_WIDTH, VIDEO_HEIGHT);

        glSurfaceView.setRenderer(renderer);

        // Render only when new frame arrives.
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
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
                            .setInputYuvFormat(H264FrameBatchEncoder.InputYuvFormat.NV12)
                            .build(),
                    encoderCallback);
            encoder.start();

            cameraController = new CameraController(
                    this,
                    new CameraController.Config.Builder()
                            .setSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                            .setFrameRateFps(FRAME_RATE_FPS)
                            .setImageReaderMaxImages(BATCH_FRAME_COUNT * 2)
                            .setFrameType(CameraController.FrameType.IMAGE_READER_NV12)
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

                    runOnUiThread(CameraRecordingGLViewActivity.this::updateDetails);
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
                    runOnUiThread(CameraRecordingGLViewActivity.this::stopEncoding);
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
                            while (pendingDecodeBatches.size() > MAX_PENDING_RENDER_TIMING_BATCHES) {
                                pendingDecodeBatches.poll();
                            }
                        }

                        decoder.onDecodeChunk(encodedBatch);
                    }

                    runOnUiThread(CameraRecordingGLViewActivity.this::updateDetails);
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
                    runOnUiThread(CameraRecordingGLViewActivity.this::stopEncoding);
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
                        .setMaxQueuedFrames(BATCH_FRAME_COUNT)
                        .build(),
                decoderCallback);
        decoder.start();

        decoding = true;
        updateControls();
        updateDetails();
    }

    private final H264FrameBatchDecoder.Callback decoderCallback =
            new H264FrameBatchDecoder.Callback() {

//                @Override
//                public void onImageAvailable(H264FrameBatchDecoder.DecodedFrame frame) {
//                    decodedFrames = frame.sequence;
//
//                    recordDecodedFrameForBatchTiming();
//
//                    Bitmap bitmap = frame.bitmap;
//
//                    Log.d(TAG,
//                            "Decoded image available. sequence="
//                                    + frame.sequence
//                                    + ", width=" + frame.width
//                                    + ", height=" + frame.height
//                                    + ", timestampNs=" + frame.timestampNs
//                                    + ", decodedBatches=" + decodedBatches);
//
//                    runOnUiThread(() -> {
//                        if (bitmap != null) {
//                            Bitmap previousBitmap = latestDecodedPreviewBitmap;
//                            latestDecodedPreviewBitmap = bitmap;
//                            decodedPreviewImage.setImageBitmap(bitmap);
//                            if (previousBitmap != null
//                                    && previousBitmap != bitmap
//                                    && !previousBitmap.isRecycled()) {
//                                previousBitmap.recycle();
//                            }
//                        }
//                        updateDetails();
//                    });
//                }

                @Override
                public void onImageAvailable(H264FrameBatchDecoder.DecodedFrame frame) {
                    renderer.updateFrame(frame);
                    glSurfaceView.requestRender();
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

                    runOnUiThread(CameraRecordingGLViewActivity.this::updateDetails);
                }

                @Override
                public void onDecodeBatchHandled(
                        long batchSequence,
                        int accessUnitCount,
                        long handledDurationNs,
                        long decodedFrameCount,
                        int queuedDecodedFrameCount) {
                    decoderHandledBatches = batchSequence;
                    totalDecodeBatchHandleTimeNs += handledDurationNs;
                    totalDecodeAccessUnits += accessUnitCount;
                    queuedDecodedFrames = queuedDecodedFrameCount;
                    recordDecoderHandledBatchForWindow();

                    Log.d(TAG,
                            "Decoder handled batch. batchSequence="
                                    + batchSequence
                                    + ", accessUnits=" + accessUnitCount
                                    + ", handledBatchesLastSecond=" + decoderHandledBatchesLastSecond
                                    + ", handledBatchesPerSec="
                                    + String.format(Locale.US, "%.2f", getDecoderHandledBatchesPerSecond())
                                    + ", durationMs="
                                    + String.format(Locale.US, "%.3f", handledDurationNs / 1_000_000.0)
                                    + ", decodedFrameCount=" + decodedFrameCount
                                    + ", queuedDecodedFrames=" + queuedDecodedFrameCount);

                    runOnUiThread(CameraRecordingGLViewActivity.this::updateDetails);
                }

                @Override
                public void onDecodedFrameDropped(
                        long frameSequence,
                        int queuedDecodedFrameCount) {
                    droppedDecodedFrames++;
                    queuedDecodedFrames = queuedDecodedFrameCount;

                    Log.d(TAG,
                            "Decoded frame dropped to keep live render. frameSequence="
                                    + frameSequence
                                    + ", queuedDecodedFrames=" + queuedDecodedFrameCount
                                    + ", droppedDecodedFrames=" + droppedDecodedFrames);

                    runOnUiThread(CameraRecordingGLViewActivity.this::updateDetails);
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
                    runOnUiThread(CameraRecordingGLViewActivity.this::stopDecoder);
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

        recycleLatestDecodedPreviewBitmap();

        decoding = false;
        showStatus("Decoder stopped.");
        updateControls();
        updateDetails();
    }

    private void recycleLatestDecodedPreviewBitmap() {

        if (latestDecodedPreviewBitmap != null
                && !latestDecodedPreviewBitmap.isRecycled()) {
            latestDecodedPreviewBitmap.recycle();
        }

        latestDecodedPreviewBitmap = null;
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
        decoderHandledBatches = 0L;
        totalDecodeBatchHandleTimeNs = 0L;
        totalDecodeAccessUnits = 0L;
        decoderHandledWindowStartMs = 0L;
        decoderHandledBatchesInWindow = 0L;
        decoderHandledBatchesLastSecond = 0L;
        queuedDecodedFrames = 0;

        rawDecodedFrames = 0L;
        totalRawMediaCodecDecodeTimeNs = 0L;
        droppedDecodedFrames = 0L;

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

    private double getElapsedSeconds() {
        if (encodingStartTimeMs <= 0L) {
            return 0.0;
        }

        return Math.max(0.001, (System.currentTimeMillis() - encodingStartTimeMs) / 1000.0);
    }

    private double getDecoderHandledBatchesPerSecond() {
        double elapsedSeconds = getElapsedSeconds();
        return elapsedSeconds > 0.0 ? decoderHandledBatches / elapsedSeconds : 0.0;
    }

    private void recordDecoderHandledBatchForWindow() {
        long nowMs = System.currentTimeMillis();

        if (decoderHandledWindowStartMs <= 0L) {
            decoderHandledWindowStartMs = nowMs;
        }

        if (nowMs - decoderHandledWindowStartMs >= 1000L) {
            decoderHandledBatchesLastSecond = decoderHandledBatchesInWindow;
            decoderHandledBatchesInWindow = 0L;
            decoderHandledWindowStartMs = nowMs;
        }

        decoderHandledBatchesInWindow++;
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
                        "Encoded batches/sec: %.2f\n" +
                        "Encoded bytes: %.2f KB\n" +
                        "Decoder handled batches: %d\n" +
                        "Decoder handled last sec: %d\n" +
                        "Decoder handled batches/sec: %.2f\n" +
                        "Avg decoder batch handle: %.3f ms\n" +
                        "Avg access units/batch: %.2f\n" +
                        "Queued decoded frames: %d\n" +
                        "Dropped decoded frames: %d\n" +
                        "Decoded frames: %d\n" +
                        "Rendered decoded batches: %d\n" +
                        "Raw decoded frames: %d\n" +
                        "Avg encode call: %.3f ms\n" +
                        "Avg batch encode: %.3f ms\n" +
                        "Avg batch decode end-to-end: %.3f ms\n" +
                        "Avg raw MediaCodec decode: %.3f ms",
                cameraFrames,
                encodedBatches,
                getElapsedSeconds() > 0.0
                        ? encodedBatches / getElapsedSeconds()
                        : 0.0,
                encodedBytes / 1024.0,
                decoderHandledBatches,
                decoderHandledBatchesLastSecond,
                getDecoderHandledBatchesPerSecond(),
                decoderHandledBatches > 0
                        ? totalDecodeBatchHandleTimeNs / 1_000_000.0 / decoderHandledBatches
                        : 0.0,
                decoderHandledBatches > 0
                        ? (double) totalDecodeAccessUnits / decoderHandledBatches
                        : 0.0,
                queuedDecodedFrames,
                droppedDecodedFrames,
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
    protected void onPause() {
        super.onPause();
        glSurfaceView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glSurfaceView.onResume();
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
