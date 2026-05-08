package com.w3n.webstream;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WebStreamCallOptionsTest {
    @Test
    public void defaultOptionsPreferH264() {
        WebStreamCallOptions options = WebStreamCallOptions.defaultOptions();

        assertEquals(WebStreamCallOptions.ImageFormat.H264, options.getImageFormat());
    }

    @Test
    public void builderCanSelectJpegCompatibilityFormat() {
        WebStreamCallOptions options = new WebStreamCallOptions.Builder()
                .imageFormat(WebStreamCallOptions.ImageFormat.JPEG)
                .build();

        assertEquals(WebStreamCallOptions.ImageFormat.JPEG, options.getImageFormat());
    }

    @Test
    public void currentBuildFallsBackToJpegEncodingWhenJxlCodecIsUnavailable() {
        WebStreamCallOptions options = new WebStreamCallOptions.Builder()
                .imageFormat(WebStreamCallOptions.ImageFormat.JXL)
                .build();

        assertEquals(
                WebStreamCallOptions.ImageFormat.JPEG,
                ImageFormatSupport.resolveEncodableFormat(options.getImageFormat()));
    }
}
