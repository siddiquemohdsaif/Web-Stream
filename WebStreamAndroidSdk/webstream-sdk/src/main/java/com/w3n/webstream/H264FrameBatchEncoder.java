package com.w3n.webstream;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

final class H264FrameBatchEncoder {
    private static final int BATCH_FRAME_COUNT = 5;
    private static final int DEQUEUE_TIMEOUT_US = 0;

    private final int width;
    private final int height;
    private final int frameRateFps;
    private final int bitrateKbps;
    private final ByteArrayOutputStream batchOutput = new ByteArrayOutputStream();
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private MediaCodec encoder;
    private int colorFormat;
    private byte[] codecConfig;
    private int framesInBatch;
    private boolean released;

    H264FrameBatchEncoder(int width, int height, int frameRateFps, int bitrateKbps) throws IOException {
        if (width <= 0 || height <= 0 || (width % 2) != 0 || (height % 2) != 0) {
            throw new IllegalArgumentException("H.264 frame dimensions must be positive even values.");
        }
        this.width = width;
        this.height = height;
        this.frameRateFps = Math.max(1, frameRateFps);
        this.bitrateKbps = Math.max(1, bitrateKbps);
        startEncoder();
    }

    synchronized byte[] encodeFrame(Bitmap bitmap, long timestampMs) {
        if (released || bitmap == null || encoder == null) {
            return null;
        }
        if (framesInBatch == 0) {
            requestKeyFrame();
        }
        drainEncoder();
        int inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
        if (inputIndex < 0) {
            return null;
        }

        ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
        if (inputBuffer == null) {
            return null;
        }
        inputBuffer.clear();
        byte[] yuv = toYuv420(bitmap, width, height, colorFormat);
        if (yuv.length > inputBuffer.remaining()) {
            throw new IllegalStateException("H.264 encoder input buffer is too small.");
        }
        inputBuffer.put(yuv);
        encoder.queueInputBuffer(
                inputIndex,
                0,
                yuv.length,
                timestampMs * 1000L,
                0);
        framesInBatch += 1;
        drainEncoder();

        if (framesInBatch < BATCH_FRAME_COUNT) {
            return null;
        }

        byte[] encodedBatch = batchOutput.toByteArray();
        batchOutput.reset();
        framesInBatch = 0;
        if (codecConfig != null && !startsWithCodecConfig(encodedBatch)) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write(codecConfig, 0, codecConfig.length);
            outputStream.write(encodedBatch, 0, encodedBatch.length);
            encodedBatch = outputStream.toByteArray();
        }
        return encodedBatch.length == 0 ? null : encodedBatch;
    }

    synchronized void release() {
        released = true;
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (RuntimeException ignored) {
            }
            encoder.release();
            encoder = null;
        }
        batchOutput.reset();
    }

    private void startEncoder() throws IOException {
        EncoderSelection selection = selectAvcEncoder();
        MediaCodecInfo codecInfo = selection.codecInfo;
        colorFormat = selection.colorFormat;

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRateFps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
        encoder = MediaCodec.createByCodecName(codecInfo.getName());
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
    }

    private EncoderSelection selectAvcEncoder() throws IOException {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (!codecInfo.isEncoder()) {
                continue;
            }
            for (String type : codecInfo.getSupportedTypes()) {
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                    int supportedColorFormat = selectColorFormat(codecInfo);
                    if (supportedColorFormat != 0) {
                        return new EncoderSelection(codecInfo, supportedColorFormat);
                    }
                }
            }
        }
        throw new IOException("No H.264 encoder with a supported YUV420 color format is available.");
    }

    private int selectColorFormat(MediaCodecInfo codecInfo) {
        MediaCodecInfo.CodecCapabilities capabilities =
                codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
        int[] preferredFormats = new int[] {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        };
        for (int preferredFormat : preferredFormats) {
            for (int supportedFormat : capabilities.colorFormats) {
                if (supportedFormat == preferredFormat) {
                    return preferredFormat;
                }
            }
        }
        return 0;
    }

    private void requestKeyFrame() {
        try {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(parameters);
        } catch (RuntimeException ignored) {
        }
    }

    private void drainEncoder() {
        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rememberCodecConfig(encoder.getOutputFormat());
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }

            ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);
            if (outputBuffer != null && bufferInfo.size > 0) {
                byte[] encoded = new byte[bufferInfo.size];
                outputBuffer.position(bufferInfo.offset);
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                outputBuffer.get(encoded);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    codecConfig = encoded;
                } else {
                    rememberCodecConfigFromAnnexB(encoded);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && codecConfig != null) {
                        batchOutput.write(codecConfig, 0, codecConfig.length);
                    }
                    batchOutput.write(encoded, 0, encoded.length);
                }
            }
            encoder.releaseOutputBuffer(outputIndex, false);
        }
    }

    private void rememberCodecConfig(MediaFormat outputFormat) {
        ByteBuffer csd0 = outputFormat.getByteBuffer("csd-0");
        ByteBuffer csd1 = outputFormat.getByteBuffer("csd-1");
        if (csd0 == null || csd1 == null) {
            return;
        }
        byte[] sps = copyRemaining(csd0);
        byte[] pps = copyRemaining(csd1);
        codecConfig = new byte[sps.length + pps.length];
        System.arraycopy(sps, 0, codecConfig, 0, sps.length);
        System.arraycopy(pps, 0, codecConfig, sps.length, pps.length);
        Log.d(SdkConstants.TAG, "H.264 encoder configured. width=" + width + ", height=" + height);
    }

    private byte[] copyRemaining(ByteBuffer buffer) {
        ByteBuffer duplicate = buffer.duplicate();
        byte[] data = new byte[duplicate.remaining()];
        duplicate.get(data);
        return data;
    }

    private void rememberCodecConfigFromAnnexB(byte[] encoded) {
        if (encoded == null || encoded.length == 0) {
            return;
        }
        int spsStart = -1;
        int ppsStart = -1;
        int configEnd = -1;
        int offset = 0;
        while (true) {
            int start = findStartCode(encoded, offset);
            if (start < 0) {
                break;
            }
            int nalStart = start + startCodeLength(encoded, start);
            int next = findStartCode(encoded, nalStart);
            int nalEnd = next < 0 ? encoded.length : next;
            if (nalStart < nalEnd) {
                int nalType = encoded[nalStart] & 0x1f;
                if (nalType == 7 && spsStart < 0) {
                    spsStart = start;
                } else if (nalType == 8 && spsStart >= 0 && ppsStart < 0) {
                    ppsStart = start;
                    configEnd = nalEnd;
                } else if (nalType != 7 && nalType != 8 && ppsStart >= 0) {
                    configEnd = start;
                    break;
                }
            }
            if (next < 0) {
                break;
            }
            offset = next;
        }
        if (spsStart >= 0 && ppsStart >= 0 && configEnd > spsStart) {
            codecConfig = new byte[configEnd - spsStart];
            System.arraycopy(encoded, spsStart, codecConfig, 0, codecConfig.length);
        }
    }

    private boolean startsWithCodecConfig(byte[] encoded) {
        int start = findStartCode(encoded, 0);
        if (start < 0) {
            return false;
        }
        int nalStart = start + startCodeLength(encoded, start);
        if (nalStart >= encoded.length) {
            return false;
        }
        int nalType = encoded[nalStart] & 0x1f;
        return nalType == 7 || nalType == 8;
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

    private byte[] toYuv420(Bitmap bitmap, int targetWidth, int targetHeight, int outputColorFormat) {
        int[] argb = new int[targetWidth * targetHeight];
        bitmap.getPixels(argb, 0, targetWidth, 0, 0, targetWidth, targetHeight);
        byte[] yuv = new byte[targetWidth * targetHeight * 3 / 2];
        int yIndex = 0;
        int chromaIndex = targetWidth * targetHeight;
        int uIndex = chromaIndex;
        int vIndex = uIndex + ((targetWidth * targetHeight) / 4);
        boolean semiPlanar = isSemiPlanar(outputColorFormat);

        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int color = argb[(y * targetWidth) + x];
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;

                int yValue = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int uValue = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int vValue = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                yuv[yIndex++] = (byte) clampByte(yValue);

                if ((y & 1) == 0 && (x & 1) == 0) {
                    if (semiPlanar) {
                        yuv[chromaIndex++] = (byte) clampByte(uValue);
                        yuv[chromaIndex++] = (byte) clampByte(vValue);
                    } else {
                        yuv[uIndex++] = (byte) clampByte(uValue);
                        yuv[vIndex++] = (byte) clampByte(vValue);
                    }
                }
            }
        }
        return yuv;
    }

    private boolean isSemiPlanar(int outputColorFormat) {
        return outputColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                || outputColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar
                || outputColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar;
    }

    private int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static final class EncoderSelection {
        final MediaCodecInfo codecInfo;
        final int colorFormat;

        EncoderSelection(MediaCodecInfo codecInfo, int colorFormat) {
            this.codecInfo = codecInfo;
            this.colorFormat = colorFormat;
        }
    }
}
