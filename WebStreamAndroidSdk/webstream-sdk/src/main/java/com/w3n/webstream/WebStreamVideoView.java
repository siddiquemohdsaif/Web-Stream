package com.w3n.webstream;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * SDK-owned container for rendering a webStream video track.
 */
public class WebStreamVideoView extends FrameLayout {
    private WebStreamVideoTrack currentTrack;

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
}
