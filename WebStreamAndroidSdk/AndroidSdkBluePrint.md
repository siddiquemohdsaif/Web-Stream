# Android SDK Blueprint

This document defines the first build plan for `WebStreamAndroidSdk`.

The SDK should provide a simple video-call API for Android apps. For the first version, it will not use WebRTC. All call data, media frames, and control events will be relayed through the fixed internal `WebStreamNodeServer`.

## Goal

Build an Android SDK that lets an app developer:

- initialize a webStream client with user identity;
- join a one-to-one call by call ID;
- receive a local video track for preview;
- receive a remote video track for the other participant;
- mute and unmute microphone;
- enable and disable camera;
- switch camera;
- leave and clean up the call;
- listen to call state, participant state, and error events.

Target usage:

```java
WebStreamClient client = new WebStreamClient.Builder(context)
        .userId("user-a")
        .build();

client.joinCall("call-123", new WebStreamCallListener() {
    @Override
    public void onConnected() {
        // Call connected.
    }

    @Override
    public void onLocalVideoAvailable(WebStreamVideoTrack track) {
        // Render local preview.
    }

    @Override
    public void onRemoteVideoAvailable(WebStreamVideoTrack track) {
        // Render remote video.
    }
});
```

## Module Structure

Current project:

```text
WebStreamAndroidSdk\
`-- app\
```

Required SDK structure:

```text
WebStreamAndroidSdk\
|-- webstream-sdk\
|   `-- reusable Android library module
|
`-- app\
    `-- small demo/test app for SDK development
```

The SDK code should live in `webstream-sdk`. The `app` module should only be used for local SDK testing.

## Package Plan

```text
com.w3n.webstream
|-- WebStreamClient
|-- WebStreamCall
|-- WebStreamCallListener
|-- WebStreamConfig
|-- WebStreamException
|
|-- media
|   |-- WebStreamVideoTrack
|   |-- WebStreamAudioTrack
|   |-- WebStreamVideoView
|   |-- CameraController
|   |-- MicrophoneController
|   `-- FrameEncoder
|
|-- transport
|   |-- WebStreamTransport
|   |-- WebSocketTransport
|   |-- WebStreamMessage
|   |-- MessageEncoder
|   `-- MessageHandler
|
|-- call
|   |-- CallSession
|   |-- CallState
|   |-- Participant
|   `-- ParticipantState
|
`-- internal
    |-- Threading
    |-- Logger
    `-- SdkConstants
```

## Public API

### WebStreamClient

Main SDK entry point.

```java
public final class WebStreamClient {
    public static final class Builder {
        public Builder(Context context);
        public Builder userId(String userId);
        public Builder displayName(String displayName);
        public Builder authToken(String authToken);
        public WebStreamClient build();
    }

    public WebStreamCall joinCall(String callId, WebStreamCallListener listener);
    public void release();
}
```

Notes:

- Server URL is internal and fixed.
- `userId` is required.
- `displayName` and `authToken` can be optional in the first local version, but the API should allow them.

### WebStreamCall

Represents one active call.

```java
public interface WebStreamCall {
    String getCallId();
    CallState getState();

    void muteMicrophone(boolean muted);
    void enableCamera(boolean enabled);
    void switchCamera();
    void leave();
}
```

### WebStreamCallListener

App receives call lifecycle and media events here.

```java
public interface WebStreamCallListener {
    void onConnecting();
    void onConnected();
    void onDisconnected();

    void onLocalVideoAvailable(WebStreamVideoTrack track);
    void onRemoteVideoAvailable(WebStreamVideoTrack track);

    void onParticipantJoined(Participant participant);
    void onParticipantLeft(Participant participant);
    void onError(WebStreamException error);
}
```

### WebStreamVideoTrack

The track object should hide internal frame transport details and provide a stable render surface for the host app.

```java
public interface WebStreamVideoTrack {
    boolean isLocal();
    String getParticipantId();
    void attach(WebStreamVideoView view);
    void detach(WebStreamVideoView view);
}
```

### WebStreamVideoView

The SDK should provide a simple Android view similar in spirit to provider SDK video views.

```java
public class WebStreamVideoView extends FrameLayout {
    public void addTrack(WebStreamVideoTrack track);
    public void removeTrack();
}
```

Expected app-side rendering:

```java
private void renderWebStreamVideo(boolean local, WebStreamVideoTrack videoTrack) {
    if (videoTrack == null) {
        return;
    }

    if (local) {
        localVideoView.removeTrack();
        localVideoView.setVisibility(View.VISIBLE);
        localVideoView.addTrack(videoTrack);
        localPlaceholder.setVisibility(View.GONE);
        localVideoContainer.bringToFront();
    } else {
        remoteVideoView.removeTrack();
        remoteVideoView.setVisibility(View.VISIBLE);
        remoteVideoView.addTrack(videoTrack);
        remotePlaceholder.setVisibility(View.GONE);
        startCallDurationTimer();
    }
}
```

## First-Version Transport

The first SDK version uses Node relay transport.

```text
Android SDK A <---- WebSocket binary/text messages ----> Node Server <---- WebSocket binary/text messages ----> Android SDK B
```

The SDK should open one persistent WebSocket per active client or active call.

Recommended transport:

- WebSocket for realtime control and media/data relay;
- JSON for control messages;
- binary messages for encoded media frames when possible;
- fallback base64 media payloads only for early prototype simplicity.

## Message Types

Minimum messages between SDK and server:

```text
client.join
server.joined
server.participant_joined
server.participant_left

client.media.video
server.media.video
client.media.audio
server.media.audio

client.media_state
server.media_state

client.leave
server.left
server.error
```

Example JSON control message:

```json
{
  "type": "client.join",
  "callId": "call-123",
  "userId": "user-a",
  "displayName": "User A"
}
```

Example media metadata:

```json
{
  "type": "client.media.video",
  "callId": "call-123",
  "userId": "user-a",
  "timestampMs": 123456789,
  "format": "jpeg",
  "width": 640,
  "height": 360,
  "sequence": 42
}
```

For binary transport, this metadata can be sent as a header or paired control frame.

## Media Plan

Because the first version does not use WebRTC, the SDK needs a simple custom media path.

### Local Video

Responsibilities:

- request and manage camera permission at app level or document required permissions;
- capture frames from CameraX or Camera2;
- show local preview immediately;
- encode frames to a lightweight transferable format;
- send encoded frames to Node server;
- expose `onLocalVideoAvailable(...)`.

Recommended first prototype:

- CameraX for capture;
- 640x360 or 480x270 resolution;
- 10-15 FPS;
- JPEG frames for simplest proof of concept;
- later replace with H.264 or a more efficient stream format.

### Remote Video

Responsibilities:

- receive encoded frames from Node server;
- decode frames on a background thread;
- render into `WebStreamVideoView`;
- expose `onRemoteVideoAvailable(...)` once the remote stream starts;
- handle missing frames, participant leave, and cleanup.

### Audio

Audio is harder than video-frame relay because latency matters more.

Recommended first prototype:

- use `AudioRecord` for microphone capture;
- use `AudioTrack` for playback;
- encode PCM chunks or a simple compressed format;
- send small chunks through the WebSocket relay;
- keep this behind `WebStreamAudioTrack` and internal audio classes.

Initial video-only proof is acceptable before audio if it speeds up validation, but the public API should already reserve audio controls.

## State Model

```java
public enum CallState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    LEFT,
    FAILED
}
```

State transitions:

```text
IDLE -> CONNECTING -> CONNECTED -> LEFT
IDLE -> CONNECTING -> FAILED
CONNECTED -> RECONNECTING -> CONNECTED
CONNECTED -> RECONNECTING -> FAILED
CONNECTED -> LEFT
```

## Permissions

Host apps must declare:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

The SDK can provide permission helper methods, but the app should own the actual Android runtime permission request UI.

## Threading Rules

- Listener callbacks should be delivered on the main thread.
- Camera capture and frame encoding should run off the main thread.
- WebSocket send/receive work should run off the main thread.
- Video decode/render scheduling should avoid blocking UI.
- `leave()` and `release()` should be safe to call more than once.

## Error Model

```java
public final class WebStreamException extends Exception {
    public enum Code {
        INVALID_CONFIG,
        PERMISSION_MISSING,
        TRANSPORT_FAILED,
        SERVER_REJECTED,
        CALL_FULL,
        MEDIA_CAPTURE_FAILED,
        MEDIA_RENDER_FAILED,
        UNKNOWN
    }
}
```

## Build Milestones

### Milestone 1: Minimal Local SDK Skeleton

- Add `webstream-sdk` Android library module.
- Move SDK package to `com.w3n.webstream`.
- Create the first public API classes:
  - `WebStreamClient`
  - `WebStreamCall`
  - `WebStreamCallListener`
  - `WebStreamVideoTrack`
  - `WebStreamVideoView`
- Keep implementation local-only.
- Do not add server connection code yet.
- Do not add remote video yet.
- Make the SDK compile as a reusable Android library.

### Milestone 2: Initialization API

- Implement `WebStreamClient.Builder`.
- Require `Context` and `userId`.
- Allow optional `displayName` and `authToken`.
- Keep server URL internal and unused in this milestone.
- Validate required fields.
- Add `release()` cleanup behavior.

Expected result:

```java
WebStreamClient client = new WebStreamClient.Builder(context)
        .userId("user-a")
        .build();
```

### Milestone 3: Fake Join and Call State

- Implement `client.joinCall(callId, listener)`.
- Validate non-empty `callId`.
- Create a `WebStreamCall` instance.
- Simulate the connection flow locally:
  - emit `onConnecting()`;
  - wait briefly or post to main thread;
  - emit `onConnected()`.
- No network request should happen.
- No server dependency should exist.
- Implement `leave()` and state transitions.

Expected result:

```java
WebStreamCall call = client.joinCall("call-123", listener);
```

The listener should receive fake/local lifecycle callbacks so the example app can build the UI flow before server work starts.

### Milestone 4: Local Video Preview

- Add `WebStreamVideoView`.
- Add camera capture.
- Expose `onLocalVideoAvailable(...)`.
- Render local preview.
- Support camera enable/disable.
- Support cleanup when `leave()` is called.

Expected result:

```java
@Override
public void onLocalVideoAvailable(WebStreamVideoTrack track) {
    localVideoView.addTrack(track);
}
```

The app should be able to join a fake call and see its own camera preview.

### Milestone 5: Local SDK Demo App

- Keep or update the `app` module as a demo app.
- Add a simple screen with:
  - user ID input;
  - call ID input;
  - join button;
  - leave button;
  - local video view;
  - status text.
- Use only the local SDK behavior from milestones 1-4.
- Confirm fake connect and local preview work on a device.

### Milestone 6: Local Controls

- Implement mute/unmute.
- Implement switch camera.
- Keep controls local-only.
- Do not send any control events to the server yet.
- Make repeated calls safe:
  - repeated mute/unmute;
  - repeated camera enable/disable;
  - repeated switch camera;
  - repeated leave.

### Milestone 7: VideoAPIChecker Local Integration

- Add `WebStreamActivity` to `VideoAPIChecker`.
- Add "Open webStream" as fifth provider.
- Use the same local/remote video UI style as 100ms.
- For this milestone, show only local preview.
- Do not call `onRemoteVideoAvailable(...)` yet.
- Display status as locally connected/fake connected.
- Compare SDK setup and local preview behavior against Agora, GetStream, 100ms, and VideoSDK.

### Milestone 8: Server Transport Foundation

- Add WebSocket transport.
- Connect to fixed internal server.
- Send `client.join`.
- Receive `server.joined`.
- Replace fake `onConnected()` with server-confirmed connection.
- Keep local video preview working exactly as before.

### Milestone 9: Remote Video Relay

- Encode local video frames.
- Send frames to Node server.
- Receive remote frames.
- Render remote frames through `WebStreamVideoTrack`.
- Start calling `onRemoteVideoAvailable(...)`.

### Milestone 10: Server-Backed Controls

- Send mute/unmute state to server.
- Send camera enable/disable state to server.
- Send participant leave events to server.
- Notify remote participant about state changes.

### Milestone 11: Configurable Encoding Quality

- Add adjustable video framerate.
- Add adjustable video bitrate.
- Add adjustable video resolution.
- Provide safe defaults for low, medium, and high quality presets.
- Allow per-call overrides without changing the global SDK setup.
- Validate unsupported or unsafe values before starting capture/encoding.
- Keep remote rendering compatible with older clients when possible.

Expected result:

```java
WebStreamCallOptions options = new WebStreamCallOptions.Builder()
        .qualityPreset(WebStreamCallOptions.QualityPreset.MEDIUM)
        .videoResolution(640, 360)
        .frameRateFps(10)
        .bitrateKbps(550)
        .build();

WebStreamCall call = client.joinCall("call-123", options, listener);
```

Apps can also set client-level defaults from outside the SDK:

```java
WebStreamClient client = new WebStreamClient.Builder(context)
        .userId("user-a")
        .defaultCallOptions(WebStreamCallOptions.highQuality())
        .build();

WebStreamCall call = client.joinCall("call-123", listener);
```

### Milestone 12: Binary JPEG Packet Transport

- Stop sending video frame bytes as base64 inside JSON.
- Send JPEG frame bytes directly as binary WebSocket packets.
- Keep JSON messages for control events only.
- Add a compact binary packet layout for media metadata and encoded frame bytes.
- Use server-generated `c_uuid` in binary media packets after join; the server creates it from `userId` and `callId`.
- Return `cUuid` in `server.joined` and participant payloads so clients can map binary packets back to participants.
- Allow one binary packet to contain one or more image payloads when batching is useful.
- Keep JPEG as the initial binary payload format, then add JPEG XL (JXL) as the default once image format negotiation exists.
- Decode incoming binary image payloads back into renderable frames for `WebStreamVideoTrack`.
- Keep a temporary compatibility path for older base64/JPEG clients when possible.
- Validate payload size, frame ordering, and decode failures before rendering.
- Document server changes needed to relay binary payloads without base64 conversion.

Current JSON/base64 behavior to replace:

```java
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
```

Binary packet layout for one image payload, using big-endian integer fields:

```text
Byte Offset   Size   Field
--------------------------------
0             2      type integer; client.media.video = 1
2             4      c_uuid integer; generated by server after join
6             8      timestamp
14            1      format integer; jpeg = 1, jxl = 2
15            2      width
17            2      height
19            2      fps
21            4      bitrate
25            4      sequence
29            4      payload_len
33            N      encoded image payload
```

For multi-image packets, repeat the image metadata and payload block after a packet-level header.

Expected result:

```text
client.media.video binary packet with JPEG frame bytes
    -> Node server binary relay
    -> server.media.video binary packet with JPEG frame bytes
```

### Milestone 13: Image Format Support

- Add image format selection for binary media packets.
- Keep JPEG as a supported compatibility format.
- Add JPEG XL (JXL) as the second supported image format.
- Make JXL the default image format for new clients.
- Update binary media packet format metadata so `format` can represent both `jpeg` and `jxl`.
- Negotiate or validate server support before sending JXL frames.
- Fall back to JPEG when JXL is unavailable on the device or unsupported by the server.
- Decode incoming JPEG and JXL payloads back into renderable frames for `WebStreamVideoTrack`.
- Document format tradeoffs for size, decode cost, compatibility, and latency.

Expected result:

```text
client.media.video binary packet with JXL frame bytes by default
    -> Node server binary relay
    -> server.media.video binary packet with JXL frame bytes by default
```

### Milestone 14: Codec Selection

- Add encoder selection for H.264.
- Add encoder selection for AV1.
- Choose a default codec based on device support and server capability.
- Fall back safely when the selected codec is unavailable on the device.
- Include codec metadata in `client.media.video` and `server.media.video`.
- Keep the public API Java-friendly.
- Document latency, CPU, bandwidth, and compatibility tradeoffs for each codec.

## Testing Plan

Minimum tests:

- client builder requires `userId`;
- call cannot join with empty `callId`;
- listener callbacks arrive on main thread;
- repeated `leave()` does not crash;
- transport reconnect changes state correctly;
- video track attach/detach works without leaking views.

Manual tests:

- two Android devices join the same call ID;
- local preview appears on both devices;
- remote video appears on both devices;
- leave from one device clears remote video on the other;
- camera switch updates local and remote video;
- network drop shows reconnect or failure state.

## First Implementation Notes

- Keep public API small.
- Keep server URL internal.
- Prefer Java-friendly APIs because the existing comparison app uses mostly Java.
- Avoid exposing internal frame formats in public classes.
- Treat WebRTC as a future optimization, not as part of version 1.
- Build for clarity first, then optimize media quality and latency.
