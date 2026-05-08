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

final class SingleH264PacketMp4Store {
    private static final String BASE_FOLDER = "VideoApiChecker/WebStream/LocalVideoCheck";
    private static final String MIME_TYPE = "video/mp4";

    private SingleH264PacketMp4Store() {
    }

    static boolean save(
            Context context,
            String participantId,
            byte[] encodedData,
            int width,
            int height,
            int frameRateFps,
            long sequence) {
        return save(
                context,
                participantId,
                encodedData,
                width,
                height,
                frameRateFps,
                sequence,
                "sp");
    }

    static boolean save(
            Context context,
            String participantId,
            byte[] encodedData,
            int width,
            int height,
            int frameRateFps,
            long sequence,
            String folderName) {
        if (context == null || encodedData == null || encodedData.length == 0) {
            return false;
        }

        Uri uri = null;
        ParcelFileDescriptor parcelFileDescriptor = null;
        MediaMuxer muxer = null;
        boolean success = false;
        boolean muxerStarted = false;
        long writtenSamples = 0;

        try {
            String cleanFolderName = cleanName(folderName);
            String publicFolder = BASE_FOLDER + "/" + cleanFolderName;
            String relativeFolder = Environment.DIRECTORY_DOWNLOADS + "/" + publicFolder;
            String fileName = "single_"
                    + cleanName(participantId)
                    + "_seq_"
                    + sequence
                    + "_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                    + ".mp4";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                ContentResolver resolver = context.getContentResolver();
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IOException("Could not create Downloads entry for single H.264 MP4.");
                }
                parcelFileDescriptor = resolver.openFileDescriptor(uri, "w");
                if (parcelFileDescriptor == null) {
                    throw new IOException("Could not open Downloads file descriptor.");
                }
                muxer = new MediaMuxer(
                        parcelFileDescriptor.getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                File folder = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        publicFolder);
                if (!folder.exists() && !folder.mkdirs()) {
                    throw new IOException("Could not create Downloads folder: " + folder.getAbsolutePath());
                }
                muxer = new MediaMuxer(
                        new File(folder, fileName).getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }

            H264PacketParts parts = parse(encodedData);
            if (parts.sps == null || parts.pps == null || parts.sampleCount == 0) {
                throw new IOException("H.264 packet did not contain SPS, PPS, and video samples.");
            }

            MediaFormat mediaFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    Math.max(1, width),
                    Math.max(1, height));
            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(parts.sps));
            mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(parts.pps));
            int trackIndex = muxer.addTrack(mediaFormat);
            muxer.start();
            muxerStarted = true;

            int frameIntervalUs = 1_000_000 / Math.max(1, frameRateFps);
            for (H264Sample sample : parts.samples) {
                if (sample == null) {
                    continue;
                }
                ByteBuffer sampleData = ByteBuffer.allocate(sample.length);
                sampleData.put(encodedData, sample.offset, sample.length);
                sampleData.flip();

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                bufferInfo.set(
                        0,
                        sample.length,
                        writtenSamples * frameIntervalUs,
                        sample.keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(trackIndex, sampleData, bufferInfo);
                writtenSamples += 1;
            }

            success = writtenSamples > 0;
            Log.d(SdkConstants.TAG, "Saved single H.264 packet MP4. folder="
                    + cleanFolderName + ", sequence=" + sequence + ", samples=" + writtenSamples);
            return success;
        } catch (IOException | RuntimeException error) {
            Log.d(SdkConstants.TAG, "Unable to save single H.264 packet MP4. sequence="
                    + sequence + ", error=" + error.getMessage());
            return false;
        } finally {
            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        muxer.stop();
                    }
                } catch (RuntimeException ignored) {
                    success = false;
                }
                muxer.release();
            }
            if (parcelFileDescriptor != null) {
                try {
                    parcelFileDescriptor.close();
                } catch (IOException ignored) {
                }
            }
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = context.getContentResolver();
                if (success) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);
                } else {
                    resolver.delete(uri, null, null);
                }
            }
        }
    }

    private static H264PacketParts parse(byte[] data) {
        H264PacketParts parts = new H264PacketParts();
        int offset = 0;
        while (true) {
            int start = findStartCode(data, offset);
            if (start < 0) {
                break;
            }
            int nalStart = start + startCodeLength(data, start);
            int next = findStartCode(data, nalStart);
            int nalEnd = next < 0 ? data.length : next;
            if (nalStart < nalEnd) {
                int nalType = data[nalStart] & 0x1f;
                if (nalType == 7) {
                    parts.sps = copyRange(data, start, nalEnd);
                } else if (nalType == 8) {
                    parts.pps = copyRange(data, start, nalEnd);
                } else if (nalType == 1 || nalType == 5) {
                    parts.addSample(new H264Sample(start, nalEnd - start, nalType == 5));
                }
            }
            if (next < 0) {
                break;
            }
            offset = next;
        }
        return parts;
    }

    private static byte[] copyRange(byte[] data, int start, int end) {
        byte[] output = new byte[end - start];
        System.arraycopy(data, start, output, 0, output.length);
        return output;
    }

    private static int findStartCode(byte[] data, int fromIndex) {
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

    private static int startCodeLength(byte[] data, int start) {
        return data[start + 2] == 1 ? 3 : 4;
    }

    private static String cleanName(String value) {
        if (TextUtils.isEmpty(value)) {
            return "unknown";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static final class H264PacketParts {
        private static final int MAX_SAMPLES = 16;

        byte[] sps;
        byte[] pps;
        H264Sample[] samples = new H264Sample[MAX_SAMPLES];
        int sampleCount;

        void addSample(H264Sample sample) {
            if (sampleCount >= samples.length) {
                return;
            }
            samples[sampleCount] = sample;
            sampleCount += 1;
        }
    }

    private static final class H264Sample {
        final int offset;
        final int length;
        final boolean keyFrame;

        H264Sample(int offset, int length, boolean keyFrame) {
            this.offset = offset;
            this.length = length;
            this.keyFrame = keyFrame;
        }
    }
}
