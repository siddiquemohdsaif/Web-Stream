package com.w3n.webstream;

/**
 * Per-call media configuration for local capture and relay encoding.
 */
public final class WebStreamCallOptions {
    public enum QualityPreset {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum ImageFormat {
        JPEG("jpeg", 1),
        JXL("jxl", 2);

        private final String wireName;
        private final int binaryValue;

        ImageFormat(String wireName, int binaryValue) {
            this.wireName = wireName;
            this.binaryValue = binaryValue;
        }

        public String getWireName() {
            return wireName;
        }

        int getBinaryValue() {
            return binaryValue;
        }

        static ImageFormat fromBinaryValue(int value) {
            for (ImageFormat format : values()) {
                if (format.binaryValue == value) {
                    return format;
                }
            }
            return null;
        }

        static ImageFormat fromWireName(String value) {
            if (value == null) {
                return null;
            }
            for (ImageFormat format : values()) {
                if (format.wireName.equalsIgnoreCase(value)) {
                    return format;
                }
            }
            return null;
        }
    }

//    static final int MIN_WIDTH = 160;
//    static final int MAX_WIDTH = 1280;
//    static final int MIN_HEIGHT = 120;
//    static final int MAX_HEIGHT = 720;
//    static final int MIN_FRAME_RATE_FPS = 1;
//    static final int MAX_FRAME_RATE_FPS = 30;
//    static final int MIN_BITRATE_KBPS = 50;
//    static final int MAX_BITRATE_KBPS = 2500;

    private final int videoWidth;
    private final int videoHeight;
    private final int frameRateFps;
    private final int bitrateKbps;
    private final QualityPreset qualityPreset;
    private final ImageFormat imageFormat;

    private WebStreamCallOptions(Builder builder) {
        this.videoWidth = builder.videoWidth;
        this.videoHeight = builder.videoHeight;
        this.frameRateFps = builder.frameRateFps;
        this.bitrateKbps = builder.bitrateKbps;
        this.qualityPreset = builder.qualityPreset;
        this.imageFormat = builder.imageFormat == null ? ImageFormat.JXL : builder.imageFormat;
    }

    public static WebStreamCallOptions lowQuality() {
        return new Builder(QualityPreset.LOW).build();
    }

    public static WebStreamCallOptions mediumQuality() {
        return new Builder(QualityPreset.MEDIUM).build();
    }

    public static WebStreamCallOptions highQuality() {
        return new Builder(QualityPreset.HIGH).build();
    }

    public static WebStreamCallOptions defaultOptions() {
        return mediumQuality();
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    public int getFrameRateFps() {
        return frameRateFps;
    }

    public int getBitrateKbps() {
        return bitrateKbps;
    }

    public QualityPreset getQualityPreset() {
        return qualityPreset;
    }

    public ImageFormat getImageFormat() {
        return imageFormat;
    }

    int getJpegQuality() {
        if (bitrateKbps >= 1800) {
            return 82;
        }
        if (bitrateKbps >= 1200) {
            return 72;
        }
        if (bitrateKbps >= 700) {
            return 64;
        }
        if (bitrateKbps >= 400) {
            return 55;
        }
        if (bitrateKbps >= 180) {
            return 45;
        }
        return 36;
    }

//    private static void validate(int width, int height, int frameRateFps, int bitrateKbps) {
//        if (width < MIN_WIDTH || width > MAX_WIDTH || width % 2 != 0) {
//            throw new IllegalArgumentException(
//                    "video width must be an even value from " + MIN_WIDTH + " to " + MAX_WIDTH + ".");
//        }
//        if (height < MIN_HEIGHT || height > MAX_HEIGHT || height % 2 != 0) {
//            throw new IllegalArgumentException(
//                    "video height must be an even value from " + MIN_HEIGHT + " to " + MAX_HEIGHT + ".");
//        }
//        if (frameRateFps < MIN_FRAME_RATE_FPS || frameRateFps > MAX_FRAME_RATE_FPS) {
//            throw new IllegalArgumentException(
//                    "frameRateFps must be from " + MIN_FRAME_RATE_FPS + " to "
//                            + MAX_FRAME_RATE_FPS + ".");
//        }
//        if (bitrateKbps < MIN_BITRATE_KBPS || bitrateKbps > MAX_BITRATE_KBPS) {
//            throw new IllegalArgumentException(
//                    "bitrateKbps must be from " + MIN_BITRATE_KBPS + " to "
//                            + MAX_BITRATE_KBPS + ".");
//        }
//    }

    public static final class Builder {
        private int videoWidth;
        private int videoHeight;
        private int frameRateFps;
        private int bitrateKbps;
        private QualityPreset qualityPreset;
        private ImageFormat imageFormat = ImageFormat.JXL;

        public Builder() {
            applyPreset(QualityPreset.MEDIUM);
        }

        public Builder(QualityPreset preset) {
            applyPreset(preset);
        }

        public Builder qualityPreset(QualityPreset preset) {
            applyPreset(preset);
            return this;
        }

        public Builder videoResolution(int width, int height) {
            this.videoWidth = width;
            this.videoHeight = height;
            return this;
        }

        public Builder frameRateFps(int frameRateFps) {
            this.frameRateFps = frameRateFps;
            return this;
        }

        public Builder bitrateKbps(int bitrateKbps) {
            this.bitrateKbps = bitrateKbps;
            return this;
        }

        public Builder imageFormat(ImageFormat imageFormat) {
            this.imageFormat = imageFormat == null ? ImageFormat.JXL : imageFormat;
            return this;
        }

        public WebStreamCallOptions build() {
//            validate(videoWidth, videoHeight, frameRateFps, bitrateKbps);
            return new WebStreamCallOptions(this);
        }

        private void applyPreset(QualityPreset preset) {
            qualityPreset = preset == null ? QualityPreset.MEDIUM : preset;
            if (qualityPreset == QualityPreset.LOW) {
                videoWidth = 320;
                videoHeight = 240;
                frameRateFps = 5;
                bitrateKbps = 180;
            } else if (qualityPreset == QualityPreset.HIGH) {
                videoWidth = 960;
                videoHeight = 540;
                frameRateFps = 15;
                bitrateKbps = 1200;
            } else {
                videoWidth = 640;
                videoHeight = 360;
                frameRateFps = 10;
                bitrateKbps = 550;
            }
        }
    }
}
