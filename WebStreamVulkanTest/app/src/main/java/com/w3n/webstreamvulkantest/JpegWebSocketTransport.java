package com.w3n.webstreamvulkantest;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.w3n.webstreamvulkantest.WebStreamJpegFrame;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

final class JpegWebSocketTransport {
    interface Listener {
        void onConnected();

        void onJpegReceived(WebStreamJpegFrame frame);

        void onClosed();

        void onError(Throwable error);
    }

    private static final String TAG = "JpegWebSocket";
    private static final int NORMAL_CLOSE = 1000;
    private static final int VIDEO_PACKET_TYPE = 1;
    private static final int VIDEO_PACKET_HEADER_BYTES = 33;
    private static final int JPEG_FORMAT_VALUE = 1;

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
    private final String serverUrl;
    private final String callId;
    private final String userId;
    private final String displayName;
    private final String authToken;
    private final Listener listener;
    private final Map<Integer, String> participantIdsByCUuid = new HashMap<>();

    private WebSocket webSocket;
    private boolean closed;
    private boolean joined;
    private int cUuid;

    JpegWebSocketTransport(
            String serverUrl,
            String callId,
            String userId,
            String displayName,
            String authToken,
            Listener listener) {
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
        webSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                sendJoin();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleTextMessage(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                handleBinaryMessage(bytes);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (!closed && listener != null) {
                    listener.onClosed();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (!closed && listener != null) {
                    listener.onError(t);
                }
            }
        });
    }

    void sendJpeg(
            byte[] jpegData,
            int width,
            int height,
            int frameRateFps,
            int bitrateKbps,
            long timestampMs,
            long sequence) {
        if (webSocket == null || closed || !joined || cUuid == 0
                || jpegData == null || jpegData.length == 0) {
            return;
        }

        ByteBuffer packet = ByteBuffer
                .allocate(VIDEO_PACKET_HEADER_BYTES + jpegData.length)
                .order(ByteOrder.BIG_ENDIAN);
        packet.putShort((short) VIDEO_PACKET_TYPE);
        packet.putInt(cUuid);
        packet.putLong(timestampMs);
        packet.put((byte) JPEG_FORMAT_VALUE);
        packet.putShort((short) clampUnsignedShort(width));
        packet.putShort((short) clampUnsignedShort(height));
        packet.putShort((short) clampUnsignedShort(frameRateFps));
        packet.putInt(Math.max(0, bitrateKbps));
        packet.putInt((int) sequence);
        packet.putInt(jpegData.length);
        packet.put(jpegData);
        webSocket.send(ByteString.of(packet.array()));
    }

    void close() {
        if (webSocket != null) {
            sendLeave();
            closed = true;
            if (!webSocket.close(NORMAL_CLOSE, "jpeg transport closed")) {
                webSocket.cancel();
            }
            webSocket = null;
        } else {
            closed = true;
        }
        okHttpClient.dispatcher().executorService().shutdown();
        okHttpClient.connectionPool().evictAll();
    }

    private void sendJoin() {
        try {
            JSONObject message = new JSONObject()
                    .put("type", "client.join")
                    .put("callId", callId)
                    .put("userId", userId)
                    .put("mediaCapabilities", new JSONObject()
                            .put("preferredImageFormat", "jpeg")
                            .put("imageFormats", new JSONArray().put("jpeg")));
            if (!TextUtils.isEmpty(displayName)) {
                message.put("displayName", displayName);
            }
            if (!TextUtils.isEmpty(authToken)) {
                message.put("authToken", authToken);
            }
            sendJson(message);
        } catch (JSONException error) {
            reportError(error);
        }
    }

    private void sendLeave() {
        try {
            sendJson(new JSONObject()
                    .put("type", "client.leave")
                    .put("callId", callId)
                    .put("userId", userId));
        } catch (JSONException error) {
            Log.d(TAG, "Unable to build leave message: " + error.getMessage());
        }
    }

    private void sendJson(JSONObject message) {
        if (webSocket != null && !closed) {
            webSocket.send(message.toString());
        }
    }

    private void handleTextMessage(String text) {
        try {
            JSONObject message = new JSONObject(text);
            String type = message.optString("type");
            if ("server.joined".equals(type)) {
                joined = true;
                cUuid = message.optInt("cUuid", 0);
                if (cUuid != 0) {
                    participantIdsByCUuid.put(cUuid, userId);
                }
                rememberParticipantMappings(message.optJSONArray("participants"));
                if (listener != null) {
                    listener.onConnected();
                }
            } else if ("server.participant_joined".equals(type)) {
                rememberParticipantMapping(message.optJSONObject("participant"));
            } else if ("server.participant_left".equals(type)) {
                removeParticipantMapping(message.optJSONObject("participant"));
            } else if ("server.media.video".equals(type)) {
                handleJsonJpegFrame(message);
            } else if ("server.left".equals(type) && listener != null) {
                listener.onClosed();
            } else if ("server.error".equals(type)) {
                reportError(new IllegalStateException(
                        message.optString("message", "Server rejected JPEG transport request.")));
            }
        } catch (JSONException error) {
            reportError(error);
        }
    }

    private void handleBinaryMessage(ByteString bytes) {
        if (bytes == null || bytes.size() < VIDEO_PACKET_HEADER_BYTES) {
            return;
        }
        ByteBuffer packet = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.BIG_ENDIAN);
        while (packet.remaining() >= VIDEO_PACKET_HEADER_BYTES) {
            int packetType = Short.toUnsignedInt(packet.getShort());
            int frameCUuid = packet.getInt();
            long timestampMs = packet.getLong();
            int format = Byte.toUnsignedInt(packet.get());
            int width = Short.toUnsignedInt(packet.getShort());
            int height = Short.toUnsignedInt(packet.getShort());
            int frameRateFps = Short.toUnsignedInt(packet.getShort());
            packet.getInt();
            long sequence = Integer.toUnsignedLong(packet.getInt());
            int payloadLength = packet.getInt();

            if (packetType != VIDEO_PACKET_TYPE
                    || format != JPEG_FORMAT_VALUE
                    || payloadLength <= 0
                    || payloadLength > packet.remaining()) {
                return;
            }

            byte[] jpegData = new byte[payloadLength];
            packet.get(jpegData);
            dispatchJpegFrame(frameCUuid, jpegData, width, height, frameRateFps, timestampMs, sequence);
        }
    }

    private void handleJsonJpegFrame(JSONObject message) {
        String participantId = message.optString("userId");
        if (TextUtils.isEmpty(participantId) || userId.equals(participantId)) {
            return;
        }
        if (!"jpeg".equalsIgnoreCase(message.optString("format", "jpeg"))) {
            return;
        }
        String data = message.optString("data");
        if (TextUtils.isEmpty(data)) {
            return;
        }
        try {
            byte[] jpegData = Base64.decode(data, Base64.DEFAULT);
            if (listener != null) {
                listener.onJpegReceived(new WebStreamJpegFrame(
                        participantId,
                        jpegData,
                        message.optInt("width", 0),
                        message.optInt("height", 0),
                        message.optInt("frameRateFps", 15),
                        message.optLong("timestampMs", 0L),
                        message.optLong("sequence", 0L)));
            }
        } catch (IllegalArgumentException error) {
            reportError(error);
        }
    }

    private void dispatchJpegFrame(
            int frameCUuid,
            byte[] jpegData,
            int width,
            int height,
            int frameRateFps,
            long timestampMs,
            long sequence) {
        if (frameCUuid == cUuid || listener == null) {
            return;
        }
        String participantId = participantIdsByCUuid.get(frameCUuid);
        if (TextUtils.isEmpty(participantId)) {
            return;
        }
        listener.onJpegReceived(new WebStreamJpegFrame(
                participantId,
                jpegData,
                width,
                height,
                frameRateFps,
                timestampMs,
                sequence));
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
        }
    }

    private void removeParticipantMapping(JSONObject participant) {
        if (participant == null) {
            return;
        }
        String participantId = participant.optString("userId", null);
        Integer cUuidToRemove = null;
        for (Map.Entry<Integer, String> entry : participantIdsByCUuid.entrySet()) {
            if (entry.getValue().equals(participantId)) {
                cUuidToRemove = entry.getKey();
                break;
            }
        }
        if (cUuidToRemove != null) {
            participantIdsByCUuid.remove(cUuidToRemove);
        }
    }

    private int clampUnsignedShort(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 0xffff);
    }

    private void reportError(Throwable error) {
        if (listener != null) {
            listener.onError(error);
        }
    }
}
