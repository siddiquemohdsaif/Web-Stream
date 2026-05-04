package com.w3n.webstream;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class WebSocketTransport {
    interface Listener {
        void onJoined();

        void onLeft();

        void onRemoteVideoFrame(RemoteVideoFrame frame);

        void onRemoteParticipantLeft(String participantId);

        void onRemoteMediaStateChanged(String participantId, boolean microphoneMuted, boolean cameraEnabled);

        void onTransportError(Throwable error);
    }

    private static final int NORMAL_CLOSE = 1000;

    private final OkHttpClient okHttpClient;
    private final String serverUrl;
    private final String callId;
    private final String userId;
    private final String displayName;
    private final String authToken;
    private final Listener listener;

    private WebSocket webSocket;
    private boolean closed;
    private boolean joined;

    WebSocketTransport(
            OkHttpClient okHttpClient,
            String serverUrl,
            String callId,
            String userId,
            String displayName,
            String authToken,
            Listener listener) {
        this.okHttpClient = okHttpClient;
        this.serverUrl = serverUrl;
        this.callId = callId;
        this.userId = userId;
        this.displayName = displayName;
        this.authToken = authToken;
        this.listener = listener;
    }

    void connect() {
        Request request = new Request.Builder()
                .url(serverUrl)
                .build();
        Log.d(SdkConstants.TAG, "Opening WebSocket. url=" + serverUrl + ", callId=" + callId);
        webSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.d(SdkConstants.TAG, "WebSocket opened. callId=" + callId);
                sendJoin();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.d(SdkConstants.TAG, "WebSocket closed. callId=" + callId + ", code=" + code);
                if (closed || listener == null) {
                    return;
                }
                if (joined) {
                    listener.onLeft();
                } else {
                    listener.onTransportError(new WebStreamException(
                            WebStreamException.Code.TRANSPORT_FAILED,
                            "webStream server closed before join completed."));
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (closed) {
                    return;
                }
                Log.d(SdkConstants.TAG, "WebSocket failed. callId=" + callId + ", error=" + t.getMessage());
                if (listener != null) {
                    listener.onTransportError(new WebStreamException(
                            WebStreamException.Code.TRANSPORT_FAILED,
                            "Unable to connect to webStream server.",
                            t));
                }
            }
        });
    }

    void sendLeave() {
        if (webSocket == null || closed) {
            return;
        }
        try {
            sendJson(new JSONObject()
                    .put("type", "client.leave")
                    .put("callId", callId)
                    .put("userId", userId));
        } catch (JSONException error) {
            Log.d(SdkConstants.TAG, "Unable to build leave message. callId=" + callId);
        }
    }

    void sendVideoFrame(
            byte[] jpegData,
            int width,
            int height,
            int frameRateFps,
            int bitrateKbps,
            long timestampMs,
            long sequence) {
        if (webSocket == null || closed || !joined || jpegData == null || jpegData.length == 0) {
            return;
        }
        try {
            sendJson(new JSONObject()
                    .put("type", "client.media.video")
                    .put("callId", callId)
                    .put("userId", userId)
                    .put("timestampMs", timestampMs)
                    .put("format", "jpeg")
                    .put("width", width)
                    .put("height", height)
                    .put("frameRateFps", frameRateFps)
                    .put("bitrateKbps", bitrateKbps)
                    .put("sequence", sequence)
                    .put("data", Base64.encodeToString(jpegData, Base64.NO_WRAP)));
        } catch (JSONException error) {
            Log.d(SdkConstants.TAG, "Unable to build video frame message. callId=" + callId);
        }
    }

    void sendMediaState(boolean microphoneMuted, boolean cameraEnabled) {
        if (webSocket == null || closed || !joined) {
            return;
        }
        try {
            sendJson(new JSONObject()
                    .put("type", "client.media_state")
                    .put("callId", callId)
                    .put("userId", userId)
                    .put("microphoneMuted", microphoneMuted)
                    .put("cameraEnabled", cameraEnabled));
        } catch (JSONException error) {
            Log.d(SdkConstants.TAG, "Unable to build media state message. callId=" + callId);
        }
    }

    void close() {
        closed = true;
        if (webSocket != null) {
            if (!webSocket.close(NORMAL_CLOSE, "client closed")) {
                webSocket.cancel();
            }
            webSocket = null;
        }
    }

    private void sendJoin() {
        try {
            JSONObject message = new JSONObject()
                    .put("type", "client.join")
                    .put("callId", callId)
                    .put("userId", userId);
            if (!TextUtils.isEmpty(displayName)) {
                message.put("displayName", displayName);
            }
            if (!TextUtils.isEmpty(authToken)) {
                message.put("authToken", authToken);
            }
            sendJson(message);
        } catch (JSONException error) {
            if (listener != null) {
                listener.onTransportError(new WebStreamException(
                        WebStreamException.Code.TRANSPORT_FAILED,
                        "Unable to build webStream join message.",
                        error));
            }
        }
    }

    private void sendJson(JSONObject message) {
        WebSocket socket = webSocket;
        if (socket == null || closed) {
            return;
        }
        socket.send(message.toString());
    }

    private void handleMessage(String text) {
        try {
            JSONObject message = new JSONObject(text);
            String type = message.optString("type");
            if ("server.joined".equals(type)) {
                joined = true;
                Log.d(SdkConstants.TAG, "Server joined confirmed. callId=" + callId);
                if (listener != null) {
                    listener.onJoined();
                }
            } else if ("server.left".equals(type)) {
                Log.d(SdkConstants.TAG, "Server left confirmed. callId=" + callId);
                if (listener != null) {
                    listener.onLeft();
                }
            } else if ("server.participant_left".equals(type)) {
                String participantId = message.optJSONObject("participant") == null
                        ? null
                        : message.optJSONObject("participant").optString("userId", null);
                if (!TextUtils.isEmpty(participantId) && listener != null) {
                    listener.onRemoteParticipantLeft(participantId);
                }
            } else if ("server.media_state".equals(type)) {
                handleRemoteMediaState(message);
            } else if ("server.media.video".equals(type)) {
                handleRemoteVideoFrame(message);
            } else if ("server.error".equals(type)) {
                String messageText = message.optString("message", "webStream server rejected the request.");
                if (listener != null) {
                    listener.onTransportError(new WebStreamException(
                            WebStreamException.Code.SERVER_REJECTED,
                            messageText));
                }
            }
        } catch (JSONException error) {
            if (listener != null) {
                listener.onTransportError(new WebStreamException(
                        WebStreamException.Code.TRANSPORT_FAILED,
                        "Invalid message from webStream server.",
                        error));
            }
        }
    }

    private void handleRemoteMediaState(JSONObject message) {
        String participantId = message.optString("userId");
        if (TextUtils.isEmpty(participantId) || userId.equals(participantId) || listener == null) {
            return;
        }
        listener.onRemoteMediaStateChanged(
                participantId,
                message.optBoolean("microphoneMuted", false),
                message.optBoolean("cameraEnabled", true));
    }

    private void handleRemoteVideoFrame(JSONObject message) throws JSONException {
        String participantId = message.optString("userId");
        if (TextUtils.isEmpty(participantId) || userId.equals(participantId)) {
            return;
        }
        String data = message.optString("data");
        if (TextUtils.isEmpty(data)) {
            return;
        }
        byte[] jpegData;
        try {
            jpegData = Base64.decode(data, Base64.DEFAULT);
        } catch (IllegalArgumentException error) {
            Log.d(SdkConstants.TAG, "Invalid remote video payload. participantId=" + participantId);
            return;
        }
        if (listener != null) {
            listener.onRemoteVideoFrame(new RemoteVideoFrame(
                    participantId,
                    jpegData,
                    message.optInt("width", 0),
                    message.optInt("height", 0),
                    message.optLong("timestampMs", 0L),
                    message.optLong("sequence", 0L)));
        }
    }
}
