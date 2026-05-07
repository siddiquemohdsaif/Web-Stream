package app.builderx.ogfa.videoapichecker.Util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ComponentManager {
    private static final String PROVIDER_AGORA = "agora";
    private static final String PROVIDER_HUNDRED_MS = "100ms";
    private static final String PROVIDER_VIDEO_SDK = "videosdk";
    private static final String PROVIDER_GET_STREAM = "getstream";
    private static final String PROVIDER_WEB_STREAM = "webstream";
    private static final String VIDEO_FILE_NAME = "remote_video.mp4";
    private static final String INTERNET_REPORT_FILE_NAME = "internet_report.txt";
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final int VIDEO_WIDTH = 720;
    private static final int VIDEO_HEIGHT = 1280;
    private static final int DEFAULT_TARGET_VIDEO_FPS = 15;
    private static final int VIDEO_BIT_RATE = 1_500_000;
    private static final int I_FRAME_INTERVAL_SECONDS = 2;
    private static long recordingStartedAtNs;

    private ComponentManager() {
    }

    @NonNull
    public static CallSession startAgoraSession(@NonNull Context context) throws IOException {
        return startCallSession(context, PROVIDER_AGORA);
    }

    @Nullable
    public static CallSession startAgoraComponentSession(@NonNull Context context) {
        return startComponentSession(context, PROVIDER_AGORA);
    }

    @NonNull
    public static CallSession startHundredMsSession(@NonNull Context context) throws IOException {
        return startCallSession(context, PROVIDER_HUNDRED_MS);
    }

    @NonNull
    public static CallSession startWebStreamSession(@NonNull Context context) throws IOException {
        return startCallSession(context, PROVIDER_WEB_STREAM);
    }

    @Nullable
    public static CallSession startHundredMsComponentSession(@NonNull Context context) {
        return startComponentSession(context, PROVIDER_HUNDRED_MS);
    }

    @Nullable
    public static CallSession startVideoSdkComponentSession(@NonNull Context context) {
        return startComponentSession(context, PROVIDER_VIDEO_SDK);
    }

    @Nullable
    public static CallSession startGetStreamComponentSession(@NonNull Context context) {
        return startComponentSession(context, PROVIDER_GET_STREAM);
    }

    @Nullable
    public static CallSession startWebStreamComponentSession(@NonNull Context context) {
        return startComponentSession(context, PROVIDER_WEB_STREAM);
    }

    @Nullable
    public static CallSession startComponentSession(
            @NonNull Context context,
            @NonNull String providerName
    ) {
        try {
            return startCallSession(context, providerName);
        } catch (IOException e) {
            return null;
        }
    }

    @NonNull
    public static InternetSpeedRecorderUtil.Result finishComponentSession(@Nullable CallSession callSession) {
        if (callSession == null) {
            return InternetSpeedRecorderUtil.Result.empty();
        }

        try {
            return callSession.finish();
        } catch (IOException e) {
            return InternetSpeedRecorderUtil.Result.empty();
        }
    }

    @NonNull
    public static CallSession startCallSession(
            @NonNull Context context,
            @NonNull String providerName
    ) throws IOException {
        CallFolder callFolder = createCallFolder(context, providerName);
        CallSession session = new CallSession(context.getApplicationContext(), providerName, callFolder);
        session.startInternetRecord();
        return session;
    }

    @NonNull
    public static CallFolder saveAgoraData(
            @NonNull Context context,
            @NonNull byte[] videoBytes,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        return saveCallData(context, PROVIDER_AGORA, videoBytes, internetResult);
    }

    @NonNull
    public static CallFolder saveHundredMsData(
            @NonNull Context context,
            @NonNull byte[] videoBytes,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        return saveCallData(context, PROVIDER_HUNDRED_MS, videoBytes, internetResult);
    }

    @NonNull
    public static CallFolder saveVideoSdkData(
            @NonNull Context context,
            @NonNull byte[] videoBytes,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        return saveCallData(context, PROVIDER_VIDEO_SDK, videoBytes, internetResult);
    }

    @NonNull
    public static CallFolder saveGetStreamData(
            @NonNull Context context,
            @NonNull byte[] videoBytes,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        return saveCallData(context, PROVIDER_GET_STREAM, videoBytes, internetResult);
    }

    @NonNull
    public static CallFolder saveWebStreamData(
            @NonNull Context context,
            @NonNull byte[] videoBytes,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        return saveCallData(context, PROVIDER_WEB_STREAM, videoBytes, internetResult);
    }

    @NonNull
    public static CallFolder saveAgoraData(
            @NonNull Context context,
            @NonNull InputStream videoInputStream,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        return saveCallData(context, PROVIDER_AGORA, videoInputStream, internetResult);
    }

    @NonNull
    public static CallFolder saveHundredMsData(
            @NonNull Context context,
            @NonNull InputStream videoInputStream,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        return saveCallData(context, PROVIDER_HUNDRED_MS, videoInputStream, internetResult);
    }

    @NonNull
    public static CallFolder saveVideoSdkData(
            @NonNull Context context,
            @NonNull InputStream videoInputStream,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        return saveCallData(context, PROVIDER_VIDEO_SDK, videoInputStream, internetResult);
    }

    @NonNull
    public static CallFolder saveGetStreamData(
            @NonNull Context context,
            @NonNull InputStream videoInputStream,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        return saveCallData(context, PROVIDER_GET_STREAM, videoInputStream, internetResult);
    }

    @NonNull
    public static CallFolder saveWebStreamData(
            @NonNull Context context,
            @NonNull InputStream videoInputStream,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        return saveCallData(context, PROVIDER_WEB_STREAM, videoInputStream, internetResult);
    }

    @NonNull
    public static CallFolder saveCallData(
            @NonNull Context context,
            @NonNull String providerName,
            @NonNull byte[] videoBytes,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        CallFolder callFolder = createCallFolder(context, providerName);
        writeVideo(callFolder.getVideoFile(), videoBytes);
        writeInternetReport(callFolder.getInternetReportFile(), providerName, internetResult);
        return callFolder;
    }

    @NonNull
    public static CallFolder saveCallData(
            @NonNull Context context,
            @NonNull String providerName,
            @NonNull InputStream videoInputStream,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        CallFolder callFolder = createCallFolder(context, providerName);
        writeVideo(callFolder.getVideoFile(), videoInputStream);
        writeInternetReport(callFolder.getInternetReportFile(), providerName, internetResult);
        return callFolder;
    }

    @NonNull
    public static CallFolder createAgoraCallFolder(@NonNull Context context) throws IOException {
        return createCallFolder(context, PROVIDER_AGORA);
    }

    @NonNull
    public static CallFolder createHundredMsCallFolder(@NonNull Context context) throws IOException {
        return createCallFolder(context, PROVIDER_HUNDRED_MS);
    }

    @NonNull
    public static CallFolder createVideoSdkCallFolder(@NonNull Context context) throws IOException {
        return createCallFolder(context, PROVIDER_VIDEO_SDK);
    }

    @NonNull
    public static CallFolder createGetStreamCallFolder(@NonNull Context context) throws IOException {
        return createCallFolder(context, PROVIDER_GET_STREAM);
    }

    @NonNull
    public static CallFolder createWebStreamCallFolder(@NonNull Context context) throws IOException {
        return createCallFolder(context, PROVIDER_WEB_STREAM);
    }

    @NonNull
    public static CallFolder createCallFolder(
            @NonNull Context context,
            @NonNull String providerName
    ) throws IOException {
        File providerFolder = new File(getBaseFolder(context), cleanFolderName(providerName));
        File timestampFolder = new File(providerFolder, createTimestamp());

        if (!timestampFolder.exists() && !timestampFolder.mkdirs()) {
            throw new IOException("Could not create call folder: " + timestampFolder.getAbsolutePath());
        }

        return new CallFolder(
                timestampFolder,
                new File(timestampFolder, VIDEO_FILE_NAME),
                new File(timestampFolder, INTERNET_REPORT_FILE_NAME));
    }

    public static void writeVideo(@NonNull File videoFile, @NonNull byte[] videoBytes) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(videoFile)) {
            outputStream.write(videoBytes);
        }
    }

    public static void writeVideo(
            @NonNull File videoFile,
            @NonNull InputStream videoInputStream
    ) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(videoFile)) {
            copy(videoInputStream, outputStream);
        }
    }

    public static void writeInternetReport(
            @NonNull File reportFile,
            @NonNull String providerName,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) throws IOException {
        String report = buildInternetReport(providerName, internetResult);
        try (FileOutputStream outputStream = new FileOutputStream(reportFile)) {
            outputStream.write(report.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NonNull
    public static String buildInternetReport(
            @NonNull String providerName,
            @Nullable InternetSpeedRecorderUtil.Result internetResult
    ) {
        return buildInternetReport(providerName, internetResult, internetResult == null ? 0L : internetResult.getDurationMs());
    }

    @NonNull
    public static String buildInternetReport(
            @NonNull String providerName,
            @Nullable InternetSpeedRecorderUtil.Result internetResult,
            long videoConnectedDurationMs
    ) {
        StringBuilder report = new StringBuilder();
        report.append("Provider: ").append(providerName).append('\n');
        report.append("Created At: ").append(new Date()).append('\n');

        if (internetResult == null) {
            report.append("Internet report: Not available").append('\n');
            return report.toString();
        }

        report.append("Downloaded Bytes: ").append(internetResult.getDownloadedBytes()).append('\n');
        report.append("Uploaded Bytes: ").append(internetResult.getUploadedBytes()).append('\n');
        report.append("Total Bytes: ").append(internetResult.getTotalBytes()).append('\n');
        report.append("Downloaded MB: ").append(formatDecimal(internetResult.getDownloadedMb())).append('\n');
        report.append("Uploaded MB: ").append(formatDecimal(internetResult.getUploadedMb())).append('\n');
        report.append("Total MB: ").append(formatDecimal(internetResult.getTotalMb())).append('\n');
        report.append("Duration MS: ").append(internetResult.getDurationMs()).append('\n');
        report.append("Video Connected Seconds: ").append(formatDecimal(videoConnectedDurationMs / 1000D)).append('\n');
        report.append("Average Download Mbps: ").append(formatDecimal(internetResult.getAverageDownloadMbps())).append('\n');
        report.append("Average Upload Mbps: ").append(formatDecimal(internetResult.getAverageUploadMbps())).append('\n');
        report.append("Average Total Mbps: ").append(formatDecimal(internetResult.getAverageTotalMbps())).append('\n');
        report.append("Call Start: ").append(formatTime(internetResult.getStartedAtMs())).append('\n');
        report.append("Call Start MS: ").append(internetResult.getStartedAtMs()).append('\n');
        report.append("Call End: ").append(formatTime(internetResult.getEndedAtMs())).append('\n');
        report.append("Call End MS: ").append(internetResult.getEndedAtMs()).append('\n');
        report.append("Summary: ").append(internetResult.toReadableText()).append('\n');
        return report.toString();
    }

    @NonNull
    private static File getBaseFolder(@NonNull Context context) throws IOException {
        File baseFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (baseFolder == null || (!baseFolder.exists() && !baseFolder.mkdirs())) {
            baseFolder = context.getFilesDir();
        }

        File appFolder = new File(baseFolder, "VideoAPIChecker");
        if (!appFolder.exists() && !appFolder.mkdirs()) {
            throw new IOException("Could not create app folder: " + appFolder.getAbsolutePath());
        }
        return appFolder;
    }

    @NonNull
    private static String createTimestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
    }

    @NonNull
    private static String cleanFolderName(@NonNull String value) {
        String cleanValue = value.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9_-]", "_");
        if (cleanValue.isEmpty()) {
            return "unknown";
        }
        return cleanValue;
    }

    @NonNull
    private static String formatDecimal(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    @NonNull
    private static String formatTime(long timeMs) {
        if (timeMs <= 0L) {
            return "Not available";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timeMs));
    }

    private static void copy(@NonNull InputStream inputStream, @NonNull OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
    }

    private static void publishCallFilesToDownloads(
            @NonNull Context context,
            @NonNull String providerName,
            @NonNull CallFolder callFolder,
            @NonNull String report
    ) throws IOException {
        publishTextToDownloads(context, providerName, callFolder, INTERNET_REPORT_FILE_NAME, report);

        File videoFile = callFolder.getVideoFile();
        if (videoFile.exists() && videoFile.length() > 0L) {
            publishFileToDownloads(context, providerName, callFolder, videoFile, VIDEO_FILE_NAME, "video/mp4");
        }
    }

    private static void publishTextToDownloads(
            @NonNull Context context,
            @NonNull String providerName,
            @NonNull CallFolder callFolder,
            @NonNull String fileName,
            @NonNull String text
    ) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = createDownloadUri(context, providerName, callFolder, fileName, "text/plain");
            boolean success = false;
            try (OutputStream outputStream = openOutputStream(context, uri)) {
                outputStream.write(text.getBytes(StandardCharsets.UTF_8));
                success = true;
            } finally {
                finishDownloadWrite(context, uri, success);
            }
            return;
        }

        File publicFile = createLegacyPublicFile(providerName, callFolder, fileName);
        try (FileOutputStream outputStream = new FileOutputStream(publicFile)) {
            outputStream.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void publishFileToDownloads(
            @NonNull Context context,
            @NonNull String providerName,
            @NonNull CallFolder callFolder,
            @NonNull File sourceFile,
            @NonNull String fileName,
            @NonNull String mimeType
    ) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = createDownloadUri(context, providerName, callFolder, fileName, mimeType);
            boolean success = false;
            try (FileInputStream inputStream = new FileInputStream(sourceFile);
                 OutputStream outputStream = openOutputStream(context, uri)) {
                copy(inputStream, outputStream);
                success = true;
            } finally {
                finishDownloadWrite(context, uri, success);
            }
            return;
        }

        File publicFile = createLegacyPublicFile(providerName, callFolder, fileName);
        try (FileInputStream inputStream = new FileInputStream(sourceFile);
             FileOutputStream outputStream = new FileOutputStream(publicFile)) {
            copy(inputStream, outputStream);
        }
    }

    @NonNull
    private static Uri createDownloadUri(
            @NonNull Context context,
            @NonNull String providerName,
             @NonNull CallFolder callFolder,
            @NonNull String fileName,
            @NonNull String mimeType
    ) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS
                        + "/VideoAPIChecker/"
                        + cleanFolderName(providerName)
                        + "/"
                        + callFolder.getFolder().getName());
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Could not create Downloads file: " + fileName);
        }
        return uri;
    }

    @NonNull
    private static OutputStream openOutputStream(@NonNull Context context, @NonNull Uri uri) throws IOException {
        OutputStream outputStream = context.getContentResolver().openOutputStream(uri);
        if (outputStream == null) {
            throw new IOException("Could not open Downloads output stream.");
        }
        return outputStream;
    }

    private static void finishDownloadWrite(
            @NonNull Context context,
            @NonNull Uri uri,
            boolean success
    ) {
        ContentResolver resolver = context.getContentResolver();
        if (success) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } else {
            resolver.delete(uri, null, null);
        }
    }

    @NonNull
    private static File createLegacyPublicFile(
            @NonNull String providerName,
            @NonNull CallFolder callFolder,
            @NonNull String fileName
    ) throws IOException {
        File publicFolder = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VideoAPIChecker/" + cleanFolderName(providerName) + "/" + callFolder.getFolder().getName());
        if (!publicFolder.exists() && !publicFolder.mkdirs()) {
            throw new IOException("Could not create Downloads folder: " + publicFolder.getAbsolutePath());
        }
        return new File(publicFolder, fileName);
    }

    public static final class CallFolder {
        private final File folder;
        private final File videoFile;
        private final File internetReportFile;

        private CallFolder(
                @NonNull File folder,
                @NonNull File videoFile,
                @NonNull File internetReportFile
        ) {
            this.folder = folder;
            this.videoFile = videoFile;
            this.internetReportFile = internetReportFile;
        }

        @NonNull
        public File getFolder() {
            return folder;
        }

        @NonNull
        public File getVideoFile() {
            return videoFile;
        }

        @NonNull
        public File getInternetReportFile() {
            return internetReportFile;
        }
    }

    public static final class CallSession {
        private final Context context;
        private final String providerName;
        private final CallFolder callFolder;
        private final InternetSpeedRecorderUtil internetSpeedRecorderUtil = new InternetSpeedRecorderUtil();
        private ViewRecorder viewRecorder;
        private InternetSpeedRecorderUtil.Result internetResult;
        private long videoConnectedStartedAtMs;
        private long videoConnectedDurationMs;
        private boolean finished;

        private CallSession(
                @NonNull Context context,
                @NonNull String providerName,
                @NonNull CallFolder callFolder
        ) {
            this.context = context;
            this.providerName = providerName;
            this.callFolder = callFolder;
        }

        public void startInternetRecord() {
            if (!internetSpeedRecorderUtil.isRecording()) {
                internetSpeedRecorderUtil.start();
            }
        }

        @NonNull
        public InternetSpeedRecorderUtil.Result stopInternetRecord() {
            internetResult = internetSpeedRecorderUtil.stop();
            return internetResult;
        }

        public void saveVideo(@NonNull byte[] videoBytes) throws IOException {
            writeVideo(callFolder.getVideoFile(), videoBytes);
        }

        public void saveVideo(@NonNull InputStream videoInputStream) throws IOException {
            writeVideo(callFolder.getVideoFile(), videoInputStream);
        }

        public void startRemoteVideoStorage(@NonNull View remoteVideoView) {
            startRemoteVideoStorage(remoteVideoView, DEFAULT_TARGET_VIDEO_FPS);
        }

        public void startRemoteVideoStorage(@NonNull View remoteVideoView, int targetVideoFps) {
            if (viewRecorder != null) {
                return;
            }

            if (videoConnectedStartedAtMs == 0L) {
                videoConnectedStartedAtMs = System.currentTimeMillis();
            }
            viewRecorder = new ViewRecorder(remoteVideoView, callFolder.getVideoFile(), targetVideoFps);
            viewRecorder.start();
        }

        public void stopRemoteVideoStorage() {
            if (videoConnectedStartedAtMs > 0L) {
                videoConnectedDurationMs += Math.max(0L, System.currentTimeMillis() - videoConnectedStartedAtMs);
                videoConnectedStartedAtMs = 0L;
            }

            if (viewRecorder != null) {
                viewRecorder.stop();
                viewRecorder = null;
            }
        }

        @NonNull
        public InternetSpeedRecorderUtil.Result finish() throws IOException {
            if (finished) {
                if (internetResult == null) {
                    internetResult = InternetSpeedRecorderUtil.Result.empty();
                }
                return internetResult;
            }

            stopRemoteVideoStorage();
            if (internetSpeedRecorderUtil.isRecording()) {
                stopInternetRecord();
            } else if (internetResult == null) {
                internetResult = InternetSpeedRecorderUtil.Result.empty();
            }

            String report = buildInternetReport(providerName, internetResult, videoConnectedDurationMs);
            try (FileOutputStream outputStream = new FileOutputStream(callFolder.getInternetReportFile())) {
                outputStream.write(report.getBytes(StandardCharsets.UTF_8));
            }
            publishCallFilesToDownloads(context, providerName, callFolder, report);
            finished = true;
            return internetResult;
        }

        @NonNull
        public CallFolder getCallFolder() {
            return callFolder;
        }

        @NonNull
        public File getVideoFile() {
            return callFolder.getVideoFile();
        }

        @NonNull
        public File getInternetReportFile() {
            return callFolder.getInternetReportFile();
        }

        @NonNull
        public InternetSpeedRecorderUtil getInternetSpeedRecorderUtil() {
            return internetSpeedRecorderUtil;
        }

        @Nullable
        public InternetSpeedRecorderUtil.Result getInternetResult() {
            return internetResult;
        }
    }

    private static final class ViewRecorder {
        private final View sourceView;
        private final File outputFile;
        private final int targetVideoFps;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        private HandlerThread recorderThread;
        private Handler recorderHandler;
        private MediaCodec encoder;
        private MediaMuxer muxer;
        private Surface inputSurface;
        private int trackIndex = -1;
        private boolean muxerStarted;
        private boolean running;
        private long frameIndex;

        private ViewRecorder(@NonNull View sourceView, @NonNull File outputFile, int targetVideoFps) {
            this.sourceView = sourceView;
            this.outputFile = outputFile;
            this.targetVideoFps = Math.max(1, targetVideoFps);
        }

        private void start() {
            recorderThread = new HandlerThread("remote-video-recorder");
            recorderThread.start();
            recorderHandler = new Handler(recorderThread.getLooper());
            recorderHandler.post(() -> {
                try {
                    prepareEncoder();
                    recordingStartedAtNs = System.nanoTime();
                    running = true;
                    scheduleNextFrame();
                } catch (IOException e) {
                    release();
                }
            });
        }

        private void stop() {
            running = false;
            if (recorderHandler != null) {
                CountDownLatch stoppedLatch = new CountDownLatch(1);
                recorderHandler.post(() -> {
                    try {
                        drainEncoder(true);
                    } finally {
                        release();
                        stoppedLatch.countDown();
                    }
                });
                try {
                    stoppedLatch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void prepareEncoder() throws IOException {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, targetVideoFps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        private void scheduleNextFrame() {
            if (!running || recorderHandler == null) {
                return;
            }

            recorderHandler.postDelayed(() -> captureFrame(bitmap -> {
                Handler handler = recorderHandler;
                if (handler == null) {
                    bitmap.recycle();
                    return;
                }

                handler.post(() -> {
                if (!running) {
                    bitmap.recycle();
                    return;
                }

                drawFrame(bitmap);
                bitmap.recycle();
                drainEncoder(false);
                frameIndex++;
                scheduleNextFrame();
                });
            }), 1000L / targetVideoFps);
        }

        private void captureFrame(@NonNull FrameCallback callback) {
            int width = sourceView.getWidth();
            int height = sourceView.getHeight();
            if (width <= 0 || height <= 0) {
                callback.onFrame(Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888));
                return;
            }

            if (sourceView instanceof TextureView) {
                Bitmap bitmap = ((TextureView) sourceView).getBitmap(width, height);
                callback.onFrame(bitmap != null ? bitmap : Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888));
                return;
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && sourceView instanceof SurfaceView) {
                mainHandler.post(() -> PixelCopy.request(
                        (SurfaceView) sourceView,
                        bitmap,
                        result -> {
                            if (result == PixelCopy.SUCCESS) {
                                callback.onFrame(bitmap);
                            } else {
                                bitmap.eraseColor(0xFF000000);
                                callback.onFrame(bitmap);
                            }
                        },
                        mainHandler));
                return;
            }

            mainHandler.post(() -> {
                Canvas canvas = new Canvas(bitmap);
                sourceView.draw(canvas);
                callback.onFrame(bitmap);
            });
        }

        private void drawFrame(@NonNull Bitmap bitmap) {
            if (inputSurface == null) {
                return;
            }

            Canvas canvas = inputSurface.lockCanvas(null);
            try {
                canvas.drawColor(0xFF000000);
                Rect destination = fitCenter(bitmap.getWidth(), bitmap.getHeight(), VIDEO_WIDTH, VIDEO_HEIGHT);
                canvas.drawBitmap(bitmap, null, destination, null);
            } finally {
                inputSurface.unlockCanvasAndPost(canvas);
            }
        }

        private Rect fitCenter(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                return new Rect(0, 0, targetWidth, targetHeight);
            }

            float scale = Math.min((float) targetWidth / sourceWidth, (float) targetHeight / sourceHeight);
            int width = Math.round(sourceWidth * scale);
            int height = Math.round(sourceHeight * scale);
            int left = (targetWidth - width) / 2;
            int top = (targetHeight - height) / 2;
            return new Rect(left, top, left + width, top + height);
        }

        private void drainEncoder(boolean endOfStream) {
            if (encoder == null) {
                return;
            }

            if (endOfStream) {
                encoder.signalEndOfInputStream();
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            while (true) {
                int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0);
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) {
                        break;
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new IllegalStateException("Video format changed twice.");
                    }
                    trackIndex = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferIndex);
                    if (encodedData == null) {
                        throw new IllegalStateException("Encoder output buffer was null.");
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }

                    if (bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        bufferInfo.presentationTimeUs =
                                Math.max(0L, (System.nanoTime() - recordingStartedAtNs) / 1000L);
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                    }

                    encoder.releaseOutputBuffer(outputBufferIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        }

        private void release() {
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
            } catch (Exception ignored) {
            }

            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
            } catch (Exception ignored) {
            }

            if (inputSurface != null) {
                inputSurface.release();
            }

            if (recorderThread != null) {
                recorderThread.quitSafely();
            }

            encoder = null;
            muxer = null;
            inputSurface = null;
            recorderThread = null;
            recorderHandler = null;
            muxerStarted = false;
        }
    }

    private interface FrameCallback {
        void onFrame(@NonNull Bitmap bitmap);
    }
}
