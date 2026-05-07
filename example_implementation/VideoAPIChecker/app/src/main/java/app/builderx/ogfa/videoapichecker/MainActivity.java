package app.builderx.ogfa.videoapichecker;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.builderx.ogfa.videoapichecker.Util.ComponentManager;
import app.builderx.ogfa.videoapichecker.Util.InternetSpeedRecorderUtil;
import app.builderx.ogfa.videoapichecker.agora.AgoraActivity;
import app.builderx.ogfa.videoapichecker.conversion.ImageConversionActivity;
import app.builderx.ogfa.videoapichecker.conversion.JpegBitmapJxlActivity;
import app.builderx.ogfa.videoapichecker.conversion.VideoConversionActivity;
import app.builderx.ogfa.videoapichecker.getstream.GetStreamActivity;
import app.builderx.ogfa.videoapichecker.hundredms.HundredMsActivity;
import app.builderx.ogfa.videoapichecker.videosdk.VideoSdkActivity;
import app.builderx.ogfa.videoapichecker.webstream.WebStreamActivity;

public class MainActivity extends AppCompatActivity {
    private static final String APK_URL = "https://www.bitaimplus.com/bitaim_v99.apk";
    private static final String APK_FILE_NAME = "bitaim_v99.apk";
    private static final String REPORT_FILE_NAME = "internet_report.txt";
    private static final int BUFFER_SIZE = 8 * 1024;

    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private Button downloadApkButton;
    private TextView downloadStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Button openAgoraButton = findViewById(R.id.openAgoraButton);
        openAgoraButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, AgoraActivity.class)));

        Button openHundredMsButton = findViewById(R.id.openHundredMsButton);
        openHundredMsButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, HundredMsActivity.class)));

        Button openGetStreamButton = findViewById(R.id.openGetStreamButton);
        openGetStreamButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, GetStreamActivity.class)));

        Button openVideoSdkButton = findViewById(R.id.openVideoSdkButton);
        openVideoSdkButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, VideoSdkActivity.class)));

        Button openWebStreamButton = findViewById(R.id.openWebStreamButton);
        openWebStreamButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, WebStreamActivity.class)));

        downloadApkButton = findViewById(R.id.downloadApkButton);
        downloadStatusText = findViewById(R.id.downloadStatusText);
        downloadApkButton.setOnClickListener(v -> downloadApkWithInternetRecord());

        Button convertVideoButton = findViewById(R.id.convertVideoButton);
        convertVideoButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, VideoConversionActivity.class)));

        Button convertImageButton = findViewById(R.id.convertImageButton);
        convertImageButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, ImageConversionActivity.class)));

        Button jpegBitmapJxlButton = findViewById(R.id.jpegBitmapJxlButton);
        jpegBitmapJxlButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, JpegBitmapJxlActivity.class)));
    }

    private void downloadApkWithInternetRecord() {
        downloadApkButton.setEnabled(false);
        setDownloadStatus("Downloading APK...");

        downloadExecutor.execute(() -> {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            InternetSpeedRecorderUtil internetSpeedRecorderUtil = new InternetSpeedRecorderUtil();
            InternetSpeedRecorderUtil.Result internetResult = InternetSpeedRecorderUtil.Result.empty();

            try {
                Uri apkUri = createDownloadUri(timestamp, APK_FILE_NAME, "application/vnd.android.package-archive");
                internetSpeedRecorderUtil.start();
                downloadFile(APK_URL, apkUri);
                internetResult = internetSpeedRecorderUtil.stop();

                String report = "Download URL: " + APK_URL + '\n'
                        + "File Name: " + APK_FILE_NAME + '\n'
                        + ComponentManager.buildInternetReport("download", internetResult);
                Uri reportUri = createDownloadUri(timestamp, REPORT_FILE_NAME, "text/plain");
                writeText(reportUri, report);

                InternetSpeedRecorderUtil.Result finalInternetResult = internetResult;
                runOnUiThread(() -> {
                    setDownloadStatus("Downloaded. " + finalInternetResult.toReadableText());
                    downloadApkButton.setEnabled(true);
                });
            } catch (Exception e) {
                if (internetSpeedRecorderUtil.isRecording()) {
                    internetSpeedRecorderUtil.stop();
                }
                runOnUiThread(() -> {
                    setDownloadStatus("Download failed: " + e.getMessage());
                    downloadApkButton.setEnabled(true);
                });
            }
        });
    }

    private void downloadFile(String urlValue, Uri outputUri) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlValue);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode < HttpURLConnection.HTTP_OK || responseCode >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IOException("Server returned " + responseCode);
            }

            try (InputStream inputStream = connection.getInputStream();
                 OutputStream outputStream = openOutputStream(outputUri)) {
                copy(inputStream, outputStream);
            }
            finishDownloadWrite(outputUri, true);
        } catch (IOException e) {
            finishDownloadWrite(outputUri, false);
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Uri createDownloadUri(String timestamp, String fileName, String mimeType) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/VideoAPIChecker/download/" + timestamp);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Could not create Downloads file: " + fileName);
            }
            return uri;
        }

        File folder = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VideoAPIChecker/download/" + timestamp);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Could not create download folder: " + folder.getAbsolutePath());
        }
        return Uri.fromFile(new File(folder, fileName));
    }

    private OutputStream openOutputStream(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            OutputStream outputStream = getContentResolver().openOutputStream(uri);
            if (outputStream == null) {
                throw new IOException("Could not open download output stream.");
            }
            return outputStream;
        }
        return new FileOutputStream(new File(uri.getPath()));
    }

    private void writeText(Uri uri, String text) throws IOException {
        boolean success = false;
        try (OutputStream outputStream = openOutputStream(uri)) {
            outputStream.write(text.getBytes(StandardCharsets.UTF_8));
            success = true;
        } finally {
            finishDownloadWrite(uri, success);
        }
    }

    private void finishDownloadWrite(Uri uri, boolean success) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        ContentResolver resolver = getContentResolver();
        if (success) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } else {
            resolver.delete(uri, null, null);
        }
    }

    private void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
    }

    private void setDownloadStatus(String message) {
        downloadStatusText.setText(message);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloadExecutor.shutdownNow();
    }
}
