package com.w3n.webstream;

/**
 * Public video track handle exposed to host apps.
 */
public interface WebStreamVideoTrack {
    boolean isLocal();

    String getParticipantId();

    void attach(WebStreamVideoView view);

    void detach(WebStreamVideoView view);
}
