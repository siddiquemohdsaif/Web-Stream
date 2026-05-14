package com.w3n.webstream;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * SDK-owned container for rendering a webStream video track.
 */
public class WebStreamVideoView extends FrameLayout {
    private static final int DEFAULT_LOCAL_PREVIEW_ROTATION_DEGREES = 270;

    private WebStreamVideoTrack currentTrack;
    private int localPreviewRotationDegrees = DEFAULT_LOCAL_PREVIEW_ROTATION_DEGREES;

    public WebStreamVideoView(Context context) {
        super(context);
    }

    public WebStreamVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public WebStreamVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void addTrack(WebStreamVideoTrack track) {
        removeTrack();
        currentTrack = track;
        if (currentTrack != null) {
            currentTrack.attach(this);
        }
    }

    public void removeTrack() {
        if (currentTrack != null) {
            WebStreamVideoTrack track = currentTrack;
            currentTrack = null;
            track.detach(this);
        }
        removeAllViews();
    }

    public WebStreamVideoTrack getCurrentTrack() {
        return currentTrack;
    }

    public void setLocalPreviewRotationDegrees(int rotationDegrees) {
        localPreviewRotationDegrees = normalizeRotationDegrees(rotationDegrees);
        if (currentTrack instanceof LocalWebStreamVideoTrack) {
            ((LocalWebStreamVideoTrack) currentTrack)
                    .setPreviewRotationDegrees(localPreviewRotationDegrees);
        }
    }

    public int getLocalPreviewRotationDegrees() {
        return localPreviewRotationDegrees;
    }

    private static int normalizeRotationDegrees(int rotationDegrees) {
        int normalized = ((rotationDegrees % 360) + 360) % 360;
        if (normalized % 90 != 0) {
            throw new IllegalArgumentException(
                    "Local preview rotation must be 0, 90, 180, or 270 degrees.");
        }
        return normalized;
    }
}
