package com.w3n.webstream;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

final class LocalWebStreamCall implements WebStreamCall {
    interface EndListener {
        void onEnded(LocalWebStreamCall call);
    }

    private final String callId;
    private final Context applicationContext;
    private final String userId;
    private final WebStreamCallOptions options;
    private final WebStreamCallListener listener;
    private final Handler mainHandler;
    private final OkHttpClient okHttpClient;
    private final String serverUrl;
    private final String displayName;
    private final String authToken;
    private final EndListener endListener;
    private CallState state = CallState.IDLE;
    private LocalWebStreamVideoTrack localVideoTrack;
    private LocalVideoPacketRecorder localVideoPacketRecorder;
    private WebSocketTransport transport;
    private final Map<String, RemoteWebStreamVideoTrack> remoteVideoTracks = new HashMap<>();
    private boolean cameraEnabled = true;
    private boolean microphoneMuted;
    private boolean loggedFormatFallback;

    LocalWebStreamCall(
            String callId,
            Context applicationContext,
            String userId,
            WebStreamCallOptions options,
            WebStreamCallListener listener,
            Handler mainHandler,
            OkHttpClient okHttpClient,
            String serverUrl,
            String displayName,
            String authToken,
            EndListener endListener) {
        this.callId = callId;
        this.applicationContext = applicationContext;
        this.userId = userId;
        this.options = options == null ? WebStreamCallOptions.defaultOptions() : options;
        this.listener = listener;
        this.mainHandler = mainHandler;
        this.okHttpClient = okHttpClient;
        this.serverUrl = serverUrl;
        this.displayName = displayName;
        this.authToken = authToken;
        this.endListener = endListener;
    }

    void start() {
        if (state != CallState.IDLE) {
            return;
        }
        state = CallState.CONNECTING;
        Log.d(SdkConstants.TAG, "Call connecting. callId=" + callId);
        dispatchConnecting();
        WebStreamPermissionManager.requestCameraPermission(applicationContext, granted -> {
            if (!granted) {
                fail(new WebStreamException(
                        WebStreamException.Code.PERMISSION_MISSING,
                        "Camera permission denied."));
                return;
            }
            mainHandler.post(this::startServerJoin);
        });
    }

    @Override
    public String getCallId() {
        return callId;
    }

    @Override
    public CallState getState() {
        return state;
    }

    @Override
    public void muteMicrophone(boolean muted) {
        microphoneMuted = muted;
        Log.d(SdkConstants.TAG, "Microphone muted changed. muted=" + muted);
        sendMediaState();
    }

    @Override
    public void enableCamera(boolean enabled) {
        cameraEnabled = enabled;
        Log.d(SdkConstants.TAG, "Camera enabled changed. enabled=" + enabled);
        if (localVideoTrack != null) {
            localVideoTrack.setEnabled(enabled);
        } else if (enabled && state == CallState.CONNECTED) {
            localVideoTrack = createLocalVideoTrack();
            dispatchLocalVideoAvailable();
        }
        sendMediaState();
    }

    @Override
    public void switchCamera() {
        Log.d(SdkConstants.TAG, "Switch camera requested.");
        if (localVideoTrack != null) {
            localVideoTrack.switchCamera();
        }
    }

    @Override
    public void leave() {
        if (state == CallState.LEFT) {
            Log.d(SdkConstants.TAG, "leave ignored; call already left. callId=" + callId);
            return;
        }
        Log.d(SdkConstants.TAG, "Leaving call. callId=" + callId);
        state = CallState.LEFT;
        if (endListener != null) {
            endListener.onEnded(this);
        }
        if (transport != null) {
            transport.sendLeave();
            transport.close();
            transport = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.release();
            localVideoTrack = null;
        }
        closeLocalVideoPacketRecorder();
        releaseRemoteVideoTracks();
        dispatchDisconnected();
    }

    private void startServerJoin() {
        if (state != CallState.CONNECTING) {
            return;
        }
        logFormatFallbackIfNeeded();
        transport = new WebSocketTransport(
                okHttpClient,
                applicationContext,
                serverUrl,
                callId,
                userId,
                displayName,
                authToken,
                options.getImageFormat(),
                new WebSocketTransport.Listener() {
                    @Override
                    public void onJoined() {
                        mainHandler.post(LocalWebStreamCall.this::connectIfActive);
                    }

                    @Override
                    public void onLeft() {
                        mainHandler.post(LocalWebStreamCall.this::handleServerLeft);
                    }

                    @Override
                    public void onRemoteVideoFrame(RemoteVideoFrame frame) {
                        mainHandler.post(() -> handleRemoteVideoFrame(frame));
                    }

                    @Override
                    public void onRemoteParticipantLeft(String participantId) {
                        mainHandler.post(() -> handleRemoteParticipantLeft(participantId));
                    }

                    @Override
                    public void onRemoteMediaStateChanged(
                            String participantId,
                            boolean microphoneMuted,
                            boolean cameraEnabled) {
                        mainHandler.post(() -> handleRemoteMediaState(
                                participantId,
                                microphoneMuted,
                                cameraEnabled));
                    }

                    @Override
                    public void onTransportError(Throwable error) {
                        mainHandler.post(() -> fail(error));
                    }
                });
        transport.connect();
    }

    private void connectIfActive() {
        if (state != CallState.CONNECTING) {
            return;
        }
        state = CallState.CONNECTED;
        Log.d(SdkConstants.TAG, "Call connected by server. callId=" + callId);
        if (cameraEnabled && localVideoTrack == null) {
            localVideoTrack = createLocalVideoTrack();
        }
        dispatchConnected();
        dispatchLocalVideoAvailable();
        sendMediaState();
    }

    private void handleServerLeft() {
        if (state == CallState.LEFT) {
            return;
        }
        Log.d(SdkConstants.TAG, "Server ended call. callId=" + callId);
        state = CallState.LEFT;
        if (endListener != null) {
            endListener.onEnded(this);
        }
        if (transport != null) {
            transport.close();
            transport = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.release();
            localVideoTrack = null;
        }
        closeLocalVideoPacketRecorder();
        releaseRemoteVideoTracks();
        dispatchDisconnected();
    }

    private void dispatchConnecting() {
        if (listener != null) {
            mainHandler.post(listener::onConnecting);
        }
    }

    private void dispatchConnected() {
        if (listener != null) {
            mainHandler.post(listener::onConnected);
        }
    }

    private void dispatchDisconnected() {
        if (listener != null) {
            mainHandler.post(listener::onDisconnected);
        }
    }

    private void dispatchLocalVideoAvailable() {
        if (listener != null && localVideoTrack != null) {
            Log.d(SdkConstants.TAG, "Dispatching local video track. callId=" + callId);
            mainHandler.post(() -> listener.onLocalVideoAvailable(localVideoTrack));
        }
    }

    private LocalWebStreamVideoTrack createLocalVideoTrack() {
        LocalWebStreamVideoTrack track = new LocalWebStreamVideoTrack(applicationContext, userId, options);
        track.setFrameListener((encodedData, imageFormat, width, height, timestampMs, sequence) -> {
            WebSocketTransport activeTransport = transport;
            if (state == CallState.CONNECTED && activeTransport != null) {
                recordLocalVideoPacket(
                        encodedData,
                        imageFormat,
                        width,
                        height,
                        timestampMs,
                        sequence);
                activeTransport.sendVideoFrame(
                        encodedData,
                        imageFormat,
                        width,
                        height,
                        options.getFrameRateFps(),
                        options.getBitrateKbps(),
                        timestampMs,
                        sequence);
            }
        });
        return track;
    }

    private void handleRemoteVideoFrame(RemoteVideoFrame frame) {
        if (state != CallState.CONNECTED || frame == null) {
            return;
        }
        RemoteWebStreamVideoTrack track = remoteVideoTracks.get(frame.getParticipantId());
        boolean isNewTrack = false;
        if (track == null) {
            track = new RemoteWebStreamVideoTrack(frame.getParticipantId());
            remoteVideoTracks.put(frame.getParticipantId(), track);
            isNewTrack = true;
        }
        track.updateFrame(frame);
        if (isNewTrack && listener != null) {
            Log.d(SdkConstants.TAG, "Dispatching remote video track. participantId="
                    + frame.getParticipantId());
            RemoteWebStreamVideoTrack remoteTrack = track;
            mainHandler.post(() -> listener.onRemoteVideoAvailable(remoteTrack));
        }
    }

    private void removeRemoteVideoTrack(String participantId) {
        RemoteWebStreamVideoTrack track = remoteVideoTracks.remove(participantId);
        if (track != null) {
            track.release();
        }
    }

    private void handleRemoteParticipantLeft(String participantId) {
        removeRemoteVideoTrack(participantId);
        if (listener != null) {
            listener.onRemoteParticipantLeft(participantId);
        }
    }

    private void handleRemoteMediaState(
            String participantId,
            boolean remoteMicrophoneMuted,
            boolean remoteCameraEnabled) {
        RemoteWebStreamVideoTrack track = remoteVideoTracks.get(participantId);
        if (track != null) {
            track.setEnabled(remoteCameraEnabled);
        }
        if (listener != null) {
            listener.onRemoteMediaStateChanged(
                    participantId,
                    remoteMicrophoneMuted,
                    remoteCameraEnabled);
        }
    }

    private void releaseRemoteVideoTracks() {
        for (RemoteWebStreamVideoTrack track : remoteVideoTracks.values()) {
            track.release();
        }
        remoteVideoTracks.clear();
    }

    private void sendMediaState() {
        WebSocketTransport activeTransport = transport;
        if (state == CallState.CONNECTED && activeTransport != null) {
            activeTransport.sendMediaState(microphoneMuted, cameraEnabled);
        }
    }

    private void logFormatFallbackIfNeeded() {
        WebStreamCallOptions.ImageFormat format = options.getImageFormat();
        if (loggedFormatFallback
                || format == WebStreamCallOptions.ImageFormat.JPEG
                || ImageFormatSupport.canEncode(format)) {
            return;
        }
        loggedFormatFallback = true;
        Log.d(SdkConstants.TAG, format.getWireName() + " video format is not supported by this phone/build. "
                + "Reason: " + ImageFormatSupport.unsupportedReason(format) + ". "
                + "Using JPEG fallback for outgoing video frames.");
    }

    private void fail(Throwable error) {
        if (state == CallState.LEFT) {
            return;
        }
        state = CallState.FAILED;
        Log.d(SdkConstants.TAG, "Call failed. callId=" + callId + ", error=" + error.getMessage());
        if (endListener != null) {
            endListener.onEnded(this);
        }
        if (transport != null) {
            transport.close();
            transport = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.release();
            localVideoTrack = null;
        }
        closeLocalVideoPacketRecorder();
        releaseRemoteVideoTracks();
        if (listener != null) {
            mainHandler.post(() -> listener.onError(error));
        }
    }

    private void recordLocalVideoPacket(
            byte[] encodedData,
            WebStreamCallOptions.ImageFormat imageFormat,
            int width,
            int height,
            long timestampMs,
            long sequence) {
        if (localVideoPacketRecorder == null) {
            localVideoPacketRecorder = new LocalVideoPacketRecorder(
                    applicationContext,
                    callId,
                    userId,
                    options.getFrameRateFps());
        }
        localVideoPacketRecorder.writeFrame(
                encodedData,
                imageFormat,
                width,
                height,
                timestampMs,
                sequence);
    }

    private void closeLocalVideoPacketRecorder() {
        if (localVideoPacketRecorder != null) {
            localVideoPacketRecorder.close();
            localVideoPacketRecorder = null;
        }
    }
}
