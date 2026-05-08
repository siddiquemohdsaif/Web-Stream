package com.w3n.webstream;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

final class NativeH264Codec {
    private NativeH264Codec() {
    }

    static boolean isEncoderAvailable() {
        return findCodec(true) != null;
    }

    static boolean isDecoderAvailable() {
        return findCodec(false) != null;
    }

    private static MediaCodecInfo findCodec(boolean encoder) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (codecInfo.isEncoder() != encoder) {
                continue;
            }
            for (String type : codecInfo.getSupportedTypes()) {
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }
}
