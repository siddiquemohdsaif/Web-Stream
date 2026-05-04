package com.w3n.webstream;

/**
 * Represents one webStream call session.
 */
public interface WebStreamCall {
    String getCallId();

    CallState getState();

    void muteMicrophone(boolean muted);

    void enableCamera(boolean enabled);

    void switchCamera();

    void leave();
}
