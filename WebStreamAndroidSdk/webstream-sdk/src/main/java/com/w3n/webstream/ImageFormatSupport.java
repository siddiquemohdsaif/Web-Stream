package com.w3n.webstream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ImageFormatSupport {
    private ImageFormatSupport() {
    }

    static WebStreamCallOptions.ImageFormat resolveEncodableFormat(
            WebStreamCallOptions.ImageFormat requestedFormat) {
        WebStreamCallOptions.ImageFormat format = requestedFormat == null
                ? WebStreamCallOptions.ImageFormat.JXL
                : requestedFormat;
        if (canEncode(format)) {
            return format;
        }
        return WebStreamCallOptions.ImageFormat.JPEG;
    }

    static boolean canEncode(WebStreamCallOptions.ImageFormat format) {
        if (format == WebStreamCallOptions.ImageFormat.JPEG) {
            return true;
        }
        return format == WebStreamCallOptions.ImageFormat.JXL && NativeJxlCodec.isAvailable();
    }

    static boolean canDecode(WebStreamCallOptions.ImageFormat format) {
        if (format == WebStreamCallOptions.ImageFormat.JPEG) {
            return true;
        }
        return format == WebStreamCallOptions.ImageFormat.JXL && NativeJxlCodec.isAvailable();
    }

    static String unsupportedReason(WebStreamCallOptions.ImageFormat format) {
        if (format == WebStreamCallOptions.ImageFormat.JXL) {
            return NativeJxlCodec.unavailableReason();
        }
        return null;
    }

    static List<WebStreamCallOptions.ImageFormat> encodableFormats() {
        List<WebStreamCallOptions.ImageFormat> formats = new ArrayList<>();
        for (WebStreamCallOptions.ImageFormat format : WebStreamCallOptions.ImageFormat.values()) {
            if (canEncode(format)) {
                formats.add(format);
            }
        }
        return Collections.unmodifiableList(formats);
    }
}
