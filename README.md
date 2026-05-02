# webStream

webStream is a video-call platform project inspired by products such as Agora, GetStream, 100ms, and VideoSDK. The goal is to build a simple, developer-friendly API for Android apps, backed by a Node.js server that relays call data between two mobile clients.

The repository also includes an existing Android comparison app, `VideoAPIChecker`, where Agora, GetStream, 100ms, and VideoSDK are already implemented. webStream will be integrated into that app as the fifth provider so its API, connection flow, call quality, and developer experience can be tested against the existing implementations.

## Project Aim

The aim of webStream is to provide a controlled, self-owned video calling stack with:

- an Android SDK that exposes a clean video-call API to app developers;
- a Node.js server that handles call coordination and data relay between users;
- a sample implementation app that validates the SDK against real provider-style flows;
- a comparable API surface to popular video platforms, while keeping the internal system simple enough to understand, test, and extend.

The first milestone is server-relayed one-to-one video calling. For simplicity, the initial version will not use WebRTC. Later milestones can add rooms, group calls, recording, screen sharing, chat, presence, push notifications, WebRTC, TURN support, analytics, and production-grade scaling.

## Repository Structure

```text
D:\Web-Stream
|-- WebStreamAndroidSdk\
|   `-- Android SDK project for the webStream client API.
|
|-- WebStreamNodeServer\
|   `-- Node.js server project for call coordination and data relay.
|
`-- example_implementation\
    `-- VideoAPIChecker\
        `-- Android sample app used to compare Agora, GetStream, 100ms,
            VideoSDK, and the upcoming webStream integration.
```

## Main Components

### 1. WebStreamAndroidSdk

The Android SDK will be the developer-facing client library. Android apps should be able to use it without knowing the full internal transport and server relay details.

Expected responsibilities:

- initialize the SDK with user identity and auth/session data;
- create or join a call;
- publish local camera and microphone streams;
- expose the local video track for preview rendering;
- receive and render the remote participant stream;
- expose call controls such as mute, unmute, camera switch, join, and leave;
- report call state changes through callbacks/listeners;
- send and receive call media/control data through the Node.js server.

Target developer experience:

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

This API is only the intended direction. The exact classes and method names should be finalized while building the SDK.

Like the 100ms integration, webStream should provide both local and remote tracks to the app layer:

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

### API Comparison

The current webStream target API is intentionally smaller than the existing providers because the server endpoint is fixed internally and the first version relays all call data through the Node.js server.

webStream target API:

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

Agora connection style:

```java
RtcEngineConfig config = new RtcEngineConfig();
config.mContext = getApplicationContext();
config.mAppId = appId;
config.mEventHandler = rtcEventHandler;

RtcEngine rtcEngine = RtcEngine.create(config);
rtcEngine.enableVideo();

ChannelMediaOptions options = new ChannelMediaOptions();
options.channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION;
options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER;
options.publishCameraTrack = true;
options.publishMicrophoneTrack = true;
options.autoSubscribeAudio = true;
options.autoSubscribeVideo = true;

rtcEngine.joinChannel(token, channelName, 0, options);
```

100ms connection style:

```java
HMSTrackSettings trackSettings = new HMSTrackSettings.Builder()
        .video(videoTrackSettings)
        .build();

HMSSDK hmsSDK = new HMSSDK.Builder(getApplication())
        .setTrackSettings(trackSettings)
        .build();

HMSConfig config = new HMSConfig(userName, authToken);
hmsSDK.join(config, hmsUpdateListener);
```

GetStream connection style:

```kotlin
val client = StreamVideoBuilder(
    context = appContext,
    apiKey = apiKey,
    geo = GEO.GlobalEdgeNetwork,
    user = User(
        id = userId,
        name = userName,
    ),
    token = userToken,
).build()

val call = client.call(type = "default", id = callId)
call.join(create = true)
```

VideoSDK connection style:

```java
VideoSDK.config(token);

Meeting meeting = VideoSDK.initMeeting(
        context,
        meetingId,
        userName,
        false,
        false,
        null,
        null,
        false,
        null,
        null);

meeting.addEventListener(meetingEventListener);
meeting.join();
```

High-level comparison:

| Provider | Client setup requires | Join target | Join call |
| --- | --- | --- | --- |
| webStream | Context + user ID | Call ID | `client.joinCall(callId, listener)` |
| Agora | App ID + token + engine config | Channel name | `rtcEngine.joinChannel(...)` |
| 100ms | Auth token or room code + user name | Room | `hmsSDK.join(config, listener)` |
| GetStream | API key + user token + user profile | Call type + call ID | `call.join(create = true)` |
| VideoSDK | Token + meeting config + user name | Meeting ID | `meeting.join()` |

### 2. WebStreamNodeServer

The Node.js server will relay call data between two Android apps. In the first version, all call data should pass through the server instead of creating a direct peer-to-peer media connection.

Expected responsibilities:

- authenticate or identify connected users;
- create, join, and leave call sessions;
- match two participants inside the same call;
- relay media packets, call events, and control messages between participants;
- track call lifecycle state;
- notify peers when a participant joins, leaves, disconnects, mutes, or changes media state.

Initial server-relay shape:

```text
Android App A <---- media/data/control ----> Node.js Server <---- media/data/control ----> Android App B
```

The server is the central path for the call. This keeps the first implementation easier to reason about and debug. A later version can move media transport to WebRTC or another optimized real-time media layer.

### 3. VideoAPIChecker Example App

`example_implementation\VideoAPIChecker` is the user-level Android project used to test provider integrations.

Current providers:

- Agora
- GetStream
- 100ms
- VideoSDK

Planned provider:

- webStream

webStream should be added to this app as a fifth option with its own activity, layout, call controls, and provider-specific setup fields. The goal is to make webStream testable in the same style as the existing providers.

## Architecture

The first webStream architecture should stay intentionally small:

```text
+-------------------+     media/data/control   +---------------------+
| Android App A     | <----------------------> | WebStreamNodeServer |
| WebStream SDK     |                          | Relay/session state |
+-------------------+                          +---------------------+
          ^                                                ^
          |                                                |
          | media/data/control                             | media/data/control
          v                                                v
+-------------------+                          +---------------------+
| Android App B     | <----------------------> | Auth / rooms / logs |
| WebStream SDK     |                          | future extensions   |
+-------------------+                          +---------------------+
```

### Client Layer

The Android SDK owns the mobile call experience:

- permissions for camera and microphone;
- local preview;
- remote video rendering;
- microphone and camera controls;
- server-relayed media/data transport;
- reconnect and cleanup behavior;
- SDK callbacks for the host app.

### Server Layer

The Node.js server owns coordination:

- connected user registry;
- call room registry;
- media/data/control relay;
- participant lifecycle events;
- basic validation and logging.

### Example Layer

The VideoAPIChecker app proves whether the API is usable:

- provider selection screen;
- one screen per provider;
- input fields for provider credentials/session values;
- join/leave and media controls;
- call duration and connection status;
- network/call reporting utilities already present in the app.

## Development Roadmap

1. Define the webStream Android SDK public API.
2. Create the Node.js relay server with call/session events.
3. Add server-relayed media/data transport in the Android SDK.
4. Build the webStream sample activity inside `VideoAPIChecker`.
5. Add webStream as the fifth provider on the sample app home screen.
6. Test two Android devices joining the same call.
7. Compare connection flow and call behavior with Agora, GetStream, 100ms, and VideoSDK.

## Success Criteria

webStream reaches the first usable milestone when:

- two Android clients can join the same call ID;
- both clients can see and hear each other;
- users can mute, unmute, switch camera, join, and leave;
- the Node.js server can show active calls and connected participants;
- all first-version call data flows through the Node.js server;
- the `VideoAPIChecker` app can launch webStream from the same provider list as the other SDKs.

## Current Status

- Repository structure exists.
- `VideoAPIChecker` already contains Agora, GetStream, 100ms, and VideoSDK integrations.
- `WebStreamAndroidSdk` exists as the Android SDK workspace.
- `WebStreamNodeServer` exists as the server workspace.
- webStream SDK implementation, server implementation, and sample-app integration are the next development steps.
