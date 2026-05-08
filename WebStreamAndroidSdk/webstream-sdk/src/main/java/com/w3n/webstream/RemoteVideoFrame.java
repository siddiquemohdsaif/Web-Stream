package com.w3n.webstream;

import java.util.Arrays;

final class RemoteVideoFrame {
    private final String participantId;
    private final byte[] encodedData;
    private final WebStreamCallOptions.ImageFormat imageFormat;
    private final int width;
    private final int height;
    private final long timestampMs;
    private final long sequence;

    RemoteVideoFrame(
            String participantId,
            byte[] encodedData,
            WebStreamCallOptions.ImageFormat imageFormat,
            int width,
            int height,
            long timestampMs,
            long sequence) {
        this.participantId = participantId;
        this.encodedData = encodedData;
        this.imageFormat = imageFormat == null ? WebStreamCallOptions.ImageFormat.JPEG : imageFormat;
        this.width = width;
        this.height = height;
        this.timestampMs = timestampMs;
        this.sequence = sequence;
    }

    String getParticipantId() {
        return participantId;
    }

    byte[] getEncodedData() {
        return encodedData;
    }

    WebStreamCallOptions.ImageFormat getImageFormat() {
        return imageFormat;
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }

    long getTimestampMs() {
        return timestampMs;
    }

    long getSequence() {
        return sequence;
    }

    @Override
    public String toString() {
        return "RemoteVideoFrame{" +
                "participantId='" + participantId + '\'' +
                ", imageFormat=" + imageFormat +
                ", width=" + width +
                ", height=" + height +
                ", timestampMs=" + timestampMs +
                ", sequence=" + sequence +
                ", encodedDataLength=" + (encodedData != null ? encodedData.length : 0) +
                ", encodedData=" + Arrays.toString(encodedData)+
                '}';
    }

}
