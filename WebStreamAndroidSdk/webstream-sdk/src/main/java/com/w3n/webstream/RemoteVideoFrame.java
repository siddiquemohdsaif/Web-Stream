package com.w3n.webstream;

final class RemoteVideoFrame {
    private final String participantId;
    private final byte[] jpegData;
    private final int width;
    private final int height;
    private final long timestampMs;
    private final long sequence;

    RemoteVideoFrame(
            String participantId,
            byte[] jpegData,
            int width,
            int height,
            long timestampMs,
            long sequence) {
        this.participantId = participantId;
        this.jpegData = jpegData;
        this.width = width;
        this.height = height;
        this.timestampMs = timestampMs;
        this.sequence = sequence;
    }

    String getParticipantId() {
        return participantId;
    }

    byte[] getJpegData() {
        return jpegData;
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
}
