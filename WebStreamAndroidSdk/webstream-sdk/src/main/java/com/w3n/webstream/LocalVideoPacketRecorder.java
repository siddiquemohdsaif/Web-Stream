package com.w3n.webstream;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class LocalVideoPacketRecorder {
    private static final String RELATIVE_FOLDER =
            Environment.DIRECTORY_DOWNLOADS + "/VideoApiChecker/WebStream/LocalVideoCheck/ts";
    private static final String PUBLIC_FOLDER = "VideoApiChecker/WebStream/LocalVideoCheck/ts";
    private static final String MIME_TYPE = "video/mp4";

    private final Context applicationContext;
    private final String callId;
    private final String userId;

    private MediaMuxer muxer;
    private ParcelFileDescriptor parcelFileDescriptor;
    private Uri pendingUri;
    private int videoTrackIndex = -1;
    private int videoWidth;
    private int videoHeight;
    private int frameIntervalUs;
    private long writtenSamples;
    private byte[] sps;
    private byte[] pps;
    private boolean initialized;
    private boolean muxerStarted;
    private boolean failed;
    private boolean skippedUnsupportedFormat;
    private boolean loggedWaitingForCodecConfig;
    private boolean loggedNoSamples;

    LocalVideoPacketRecorder(Context context, String callId, String userId, int frameRateFps) {
        this.applicationContext = context.getApplicationContext();
        this.callId = callId;
        this.userId = userId;
        this.frameIntervalUs = 1_000_000 / Math.max(1, frameRateFps);
    }

    synchronized void writeFrame(
            byte[] encodedData,
            WebStreamCallOptions.ImageFormat format,
            int width,
            int height,
            long timestampMs,
            long sequence) {
        if (failed || encodedData == null || encodedData.length == 0) {
            return;
        }
        if (format != WebStreamCallOptions.ImageFormat.H264) {
            if (!skippedUnsupportedFormat) {
                skippedUnsupportedFormat = true;
                Log.d(SdkConstants.TAG, "Local video recording currently writes playable MP4 only for H.264.");
            }
            return;
        }
        try {
            ensureOpen(width, height);
            writeH264Packet(encodedData);
        } catch (IOException | RuntimeException error) {
            failed = true;
            Log.d(SdkConstants.TAG, "Unable to record local MP4. callId="
                    + callId + ", error=" + error.getMessage());
            close(false);
        }
    }

    synchronized void close() {
        close(true);
    }

    private void ensureOpen(int width, int height) throws IOException {
        if (initialized) {
            return;
        }
        initialized = true;
        videoWidth = Math.max(1, width);
        videoHeight = Math.max(1, height);
        String fileName = "local_"
                + cleanName(userId)
                + "_"
                + cleanName(callId)
                + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + ".mp4";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver resolver = applicationContext.getContentResolver();
            pendingUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (pendingUri == null) {
                throw new IOException("Could not create Downloads entry for local MP4.");
            }
            parcelFileDescriptor = resolver.openFileDescriptor(pendingUri, "w");
            if (parcelFileDescriptor == null) {
                throw new IOException("Could not open Downloads file descriptor.");
            }
            muxer = new MediaMuxer(
                    parcelFileDescriptor.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } else {
            File folder = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    PUBLIC_FOLDER);
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("Could not create Downloads folder: " + folder.getAbsolutePath());
            }
            muxer = new MediaMuxer(
                    new File(folder, fileName).getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
        Log.d(SdkConstants.TAG, "Recording local outgoing H.264 as MP4 in Download/"
                + PUBLIC_FOLDER + ".");
    }

    private void writeH264Packet(byte[] encodedData) {
        int offset = 0;
        while (true) {
            int start = findStartCode(encodedData, offset);
            if (start < 0) {
                break;
            }
            int nalStart = start + startCodeLength(encodedData, start);
            int next = findStartCode(encodedData, nalStart);
            int nalEnd = next < 0 ? encodedData.length : next;
            if (nalStart < nalEnd) {
                int nalType = encodedData[nalStart] & 0x1f;
                if (nalType == 7 || nalType == 8) {
                    rememberCodecConfigNal(encodedData, start, nalEnd, nalType);
                    startMuxerIfReady();
                } else if (isVideoSlice(nalType) && muxerStarted) {
                    writeSample(encodedData, start, nalEnd - start, nalType);
                } else if (isVideoSlice(nalType) && !loggedWaitingForCodecConfig) {
                    loggedWaitingForCodecConfig = true;
                    Log.d(SdkConstants.TAG, "Local MP4 recorder is waiting for H.264 SPS/PPS before writing samples.");
                }
            }
            if (next < 0) {
                break;
            }
            offset = next;
        }
    }

    private void rememberCodecConfigNal(byte[] packet, int start, int end, int nalType) {
        byte[] nal = new byte[end - start];
        System.arraycopy(packet, start, nal, 0, nal.length);
        if (nalType == 7) {
            sps = nal;
        } else if (nalType == 8) {
            pps = nal;
        }
    }

    private void startMuxerIfReady() {
        if (muxerStarted || muxer == null) {
            return;
        }
        if (sps == null || pps == null) {
            return;
        }
        MediaFormat mediaFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                videoWidth,
                videoHeight);
        mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
        mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        videoTrackIndex = muxer.addTrack(mediaFormat);
        muxer.start();
        muxerStarted = true;
        Log.d(SdkConstants.TAG, "Local MP4 recorder started. width="
                + videoWidth + ", height=" + videoHeight);
    }

    private void writeSample(byte[] data, int offset, int length, int nalType) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        bufferInfo.set(
                0,
                length,
                writtenSamples * frameIntervalUs,
                nalType == 5 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        ByteBuffer sampleData = ByteBuffer.allocate(length);
        sampleData.put(data, offset, length);
        sampleData.flip();
        muxer.writeSampleData(videoTrackIndex, sampleData, bufferInfo);
        writtenSamples += 1;
    }

    private boolean isVideoSlice(int nalType) {
        return nalType == 1 || nalType == 5;
    }

    private int findStartCode(byte[] data, int fromIndex) {
        if (data == null) {
            return -1;
        }
        for (int i = Math.max(0, fromIndex); i <= data.length - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i;
                }
                if (i <= data.length - 4 && data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int startCodeLength(byte[] data, int start) {
        return data[start + 2] == 1 ? 3 : 4;
    }

    private void close(boolean success) {
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
            } catch (RuntimeException ignored) {
                success = false;
            }
            muxer.release();
            muxer = null;
        }
        if (parcelFileDescriptor != null) {
            try {
                parcelFileDescriptor.close();
            } catch (IOException ignored) {
            }
            parcelFileDescriptor = null;
        }
        if (pendingUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = applicationContext.getContentResolver();
            if (success && muxerStarted && writtenSamples > 0) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(pendingUri, values, null, null);
                Log.d(SdkConstants.TAG, "Local MP4 recorder saved "
                        + writtenSamples + " samples to Download/" + PUBLIC_FOLDER + ".");
            } else {
                logNoSamplesIfNeeded();
                resolver.delete(pendingUri, null, null);
            }
            pendingUri = null;
        }
    }

    private void logNoSamplesIfNeeded() {
        if (loggedNoSamples) {
            return;
        }
        loggedNoSamples = true;
        Log.d(SdkConstants.TAG, "Local MP4 recorder did not save a file because no playable H.264 samples were written.");
    }

    private String cleanName(String value) {
        if (TextUtils.isEmpty(value)) {
            return "unknown";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
