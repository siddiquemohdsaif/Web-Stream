package com.w3n.webstream;

/**
 * Receives call lifecycle and media events from the SDK.
 */
public interface WebStreamCallListener {
    void onConnecting();

    void onConnected();

    void onDisconnected();

    void onLocalVideoAvailable(WebStreamVideoTrack track);

    void onRemoteVideoAvailable(WebStreamVideoTrack track);

    default void onRemoteMediaStateChanged(
            String participantId,
            boolean microphoneMuted,
            boolean cameraEnabled) {
    }

    default void onRemoteParticipantLeft(String participantId) {
    }

    void onError(Throwable error);
}
