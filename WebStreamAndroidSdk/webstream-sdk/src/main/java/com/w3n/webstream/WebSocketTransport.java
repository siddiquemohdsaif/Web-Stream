package com.w3n.webstream;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

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
    private static final int VIDEO_PACKET_TYPE = 1;
    private static final int VIDEO_PACKET_HEADER_BYTES = 33;

    private final OkHttpClient okHttpClient;
    private final String serverUrl;
    private final String callId;
    private final String userId;
    private final String displayName;
    private final String authToken;
    private final WebStreamCallOptions.ImageFormat preferredImageFormat;
    private final Listener listener;

    private WebSocket webSocket;
    private boolean closed;
    private boolean joined;
    private int cUuid;
    private Set<WebStreamCallOptions.ImageFormat> serverSupportedImageFormats =
            EnumSet.of(WebStreamCallOptions.ImageFormat.JPEG);
    private final Map<Integer, String> participantIdsByCUuid = new HashMap<>();
    private final Set<String> loggedParticipantsWithoutJxl = new java.util.HashSet<>();

    WebSocketTransport(
            OkHttpClient okHttpClient,
            String serverUrl,
            String callId,
            String userId,
            String displayName,
            String authToken,
            WebStreamCallOptions.ImageFormat preferredImageFormat,
            Listener listener) {
        this.okHttpClient = okHttpClient;
        this.serverUrl = serverUrl;
        this.callId = callId;
        this.userId = userId;
        this.displayName = displayName;
        this.authToken = authToken;
        this.preferredImageFormat = preferredImageFormat == null
                ? WebStreamCallOptions.ImageFormat.JXL
                : preferredImageFormat;
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
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                handleBinaryMessage(bytes);
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
            byte[] encodedData,
            WebStreamCallOptions.ImageFormat imageFormat,
            int width,
            int height,
            int frameRateFps,
            int bitrateKbps,
            long timestampMs,
            long sequence) {
        if (webSocket == null || closed || !joined || encodedData == null || encodedData.length == 0) {
            return;
        }
        if (cUuid == 0) {
            Log.d(SdkConstants.TAG, "Skipping binary video frame before c_uuid assignment. callId=" + callId);
            return;
        }
        WebStreamCallOptions.ImageFormat packetFormat = imageFormat == null
                ? WebStreamCallOptions.ImageFormat.JPEG
                : imageFormat;
        if (!serverSupportedImageFormats.contains(packetFormat)) {
            Log.d(SdkConstants.TAG, "Skipping " + packetFormat.getWireName()
                    + " frame; server did not advertise support. callId=" + callId);
            return;
        }
        ByteBuffer packet = ByteBuffer
                .allocate(VIDEO_PACKET_HEADER_BYTES + encodedData.length)
                .order(ByteOrder.BIG_ENDIAN);
        packet.putShort((short) VIDEO_PACKET_TYPE);
        packet.putInt(cUuid);
        packet.putLong(timestampMs);
        packet.put((byte) packetFormat.getBinaryValue());
        packet.putShort((short) clampUnsignedShort(width));
        packet.putShort((short) clampUnsignedShort(height));
        packet.putShort((short) clampUnsignedShort(frameRateFps));
        packet.putInt(Math.max(0, bitrateKbps));
        packet.putInt((int) sequence);
        packet.putInt(encodedData.length);
        packet.put(encodedData);
        webSocket.send(ByteString.of(packet.array()));
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
            message.put("mediaCapabilities", buildMediaCapabilities());
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
                cUuid = message.optInt("cUuid", 0);
                rememberServerMediaCapabilities(message.optJSONObject("mediaCapabilities"));
                if (cUuid != 0) {
                    participantIdsByCUuid.put(cUuid, userId);
                }
                rememberParticipantMappings(message.optJSONArray("participants"));
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
                if (!TextUtils.isEmpty(participantId)) {
                    removeParticipantMapping(participantId);
                    if (listener != null) {
                        listener.onRemoteParticipantLeft(participantId);
                    }
                }
            } else if ("server.participant_joined".equals(type)) {
                rememberParticipantMapping(message.optJSONObject("participant"));
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

    private void handleBinaryMessage(ByteString bytes) {
        if (bytes == null || bytes.size() < VIDEO_PACKET_HEADER_BYTES) {
            return;
        }
        ByteBuffer packet = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.BIG_ENDIAN);
        while (packet.remaining() >= VIDEO_PACKET_HEADER_BYTES) {
            int type = Short.toUnsignedInt(packet.getShort());
            int frameCUuid = packet.getInt();
            long timestampMs = packet.getLong();
            WebStreamCallOptions.ImageFormat imageFormat =
                    WebStreamCallOptions.ImageFormat.fromBinaryValue(Byte.toUnsignedInt(packet.get()));
            int width = Short.toUnsignedInt(packet.getShort());
            int height = Short.toUnsignedInt(packet.getShort());
            int frameRateFps = Short.toUnsignedInt(packet.getShort());
            int bitrateKbps = packet.getInt();
            long sequence = Integer.toUnsignedLong(packet.getInt());
            int payloadLength = packet.getInt();

            if (type != VIDEO_PACKET_TYPE || imageFormat == null || payloadLength <= 0
                    || payloadLength > packet.remaining()) {
                Log.d(SdkConstants.TAG, "Invalid binary video packet. callId=" + callId);
                return;
            }

            byte[] encodedData = new byte[payloadLength];
            packet.get(encodedData);
            handleRemoteBinaryVideoFrame(
                    frameCUuid,
                    encodedData,
                    imageFormat,
                    width,
                    height,
                    frameRateFps,
                    bitrateKbps,
                    timestampMs,
                    sequence);
        }
    }

    private void handleRemoteBinaryVideoFrame(
            int frameCUuid,
            byte[] encodedData,
            WebStreamCallOptions.ImageFormat imageFormat,
            int width,
            int height,
            int frameRateFps,
            int bitrateKbps,
            long timestampMs,
            long sequence) {
        if (frameCUuid == cUuid) {
            return;
        }
        String participantId = participantIdsByCUuid.get(frameCUuid);
        if (TextUtils.isEmpty(participantId)) {
            Log.d(SdkConstants.TAG, "Unknown binary video sender. c_uuid=" + frameCUuid);
            return;
        }
        if (listener != null) {
            listener.onRemoteVideoFrame(new RemoteVideoFrame(
                    participantId,
                    encodedData,
                    imageFormat,
                    width,
                    height,
                    timestampMs,
                    sequence));
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
        byte[] encodedData;
        try {
            encodedData = Base64.decode(data, Base64.DEFAULT);
        } catch (IllegalArgumentException error) {
            Log.d(SdkConstants.TAG, "Invalid remote video payload. participantId=" + participantId);
            return;
        }
        WebStreamCallOptions.ImageFormat imageFormat =
                WebStreamCallOptions.ImageFormat.fromWireName(message.optString("format", "jpeg"));
        if (imageFormat == null) {
            imageFormat = WebStreamCallOptions.ImageFormat.JPEG;
        }
        if (listener != null) {
            listener.onRemoteVideoFrame(new RemoteVideoFrame(
                    participantId,
                    encodedData,
                    imageFormat,
                    message.optInt("width", 0),
                    message.optInt("height", 0),
                    message.optLong("timestampMs", 0L),
                    message.optLong("sequence", 0L)));
        }
    }

    private void rememberParticipantMappings(JSONArray participants) {
        if (participants == null) {
            return;
        }
        for (int i = 0; i < participants.length(); i++) {
            rememberParticipantMapping(participants.optJSONObject(i));
        }
    }

    private void rememberParticipantMapping(JSONObject participant) {
        if (participant == null) {
            return;
        }
        String participantId = participant.optString("userId", null);
        int participantCUuid = participant.optInt("cUuid", 0);
        if (!TextUtils.isEmpty(participantId) && participantCUuid != 0) {
            participantIdsByCUuid.put(participantCUuid, participantId);
            logRemoteJxlUnsupportedIfNeeded(participantId, participant);
        }
    }

    private void removeParticipantMapping(String participantId) {
        Integer cUuidToRemove = null;
        for (Map.Entry<Integer, String> entry : participantIdsByCUuid.entrySet()) {
            if (participantId.equals(entry.getValue())) {
                cUuidToRemove = entry.getKey();
                break;
            }
        }
        if (cUuidToRemove != null) {
            participantIdsByCUuid.remove(cUuidToRemove);
        }
        loggedParticipantsWithoutJxl.remove(participantId);
    }

    private int clampUnsignedShort(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 0xffff);
    }

    private JSONObject buildMediaCapabilities() throws JSONException {
        JSONObject capabilities = new JSONObject();
        capabilities.put("preferredImageFormat", preferredImageFormat.getWireName());
        JSONArray imageFormats = new JSONArray();
        List<WebStreamCallOptions.ImageFormat> encodableFormats = ImageFormatSupport.encodableFormats();
        for (WebStreamCallOptions.ImageFormat format : encodableFormats) {
            imageFormats.put(format.getWireName());
        }
        capabilities.put("imageFormats", imageFormats);
        return capabilities;
    }

    private void rememberServerMediaCapabilities(JSONObject capabilities) {
        if (capabilities == null) {
            return;
        }
        JSONArray imageFormats = capabilities.optJSONArray("imageFormats");
        if (imageFormats == null) {
            return;
        }
        EnumSet<WebStreamCallOptions.ImageFormat> parsedFormats =
                EnumSet.noneOf(WebStreamCallOptions.ImageFormat.class);
        for (int i = 0; i < imageFormats.length(); i++) {
            WebStreamCallOptions.ImageFormat format =
                    WebStreamCallOptions.ImageFormat.fromWireName(imageFormats.optString(i));
            if (format != null) {
                parsedFormats.add(format);
            }
        }
        if (!parsedFormats.isEmpty()) {
            serverSupportedImageFormats = parsedFormats;
        }
    }

    private void logRemoteJxlUnsupportedIfNeeded(String participantId, JSONObject participant) {
        if (participantId.equals(userId) || loggedParticipantsWithoutJxl.contains(participantId)) {
            return;
        }
        JSONArray imageFormats = participant.optJSONArray("imageFormats");
        if (imageFormats == null) {
            return;
        }
        boolean supportsJxl = false;
        for (int i = 0; i < imageFormats.length(); i++) {
            WebStreamCallOptions.ImageFormat format =
                    WebStreamCallOptions.ImageFormat.fromWireName(imageFormats.optString(i));
            if (format == WebStreamCallOptions.ImageFormat.JXL) {
                supportsJxl = true;
                break;
            }
        }
        if (!supportsJxl) {
            loggedParticipantsWithoutJxl.add(participantId);
            Log.d(SdkConstants.TAG, "JXL image format is not supported by remote participant "
                    + participantId + ". Reason: opposite phone/client did not advertise JXL in "
                    + "mediaCapabilities.imageFormats. JPEG compatibility may be required.");
        }
    }
}
