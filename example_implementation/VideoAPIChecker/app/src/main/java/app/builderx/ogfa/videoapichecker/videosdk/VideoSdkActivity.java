package app.builderx.ogfa.videoapichecker.videosdk;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.builderx.ogfa.videoapichecker.R;
import app.builderx.ogfa.videoapichecker.Util.CallAutoCutConfig;
import app.builderx.ogfa.videoapichecker.Util.ComponentManager;
import app.builderx.ogfa.videoapichecker.Util.InternetSpeedRecorderUtil;
import live.videosdk.rtc.android.Meeting;
import live.videosdk.rtc.android.Participant;
import live.videosdk.rtc.android.Stream;
import live.videosdk.rtc.android.VideoSDK;
import live.videosdk.rtc.android.VideoView;
import live.videosdk.rtc.android.lib.BitrateMode;
import live.videosdk.rtc.android.listeners.MeetingEventListener;
import live.videosdk.rtc.android.listeners.ParticipantEventListener;
import live.videosdk.rtc.android.CustomStreamTrack;
import org.webrtc.VideoTrack;

public class VideoSdkActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_ID = 14;
    private static final int TARGET_VIDEO_FPS = 15;
    private static final String TAG = "VideoSdkActivity";
    private static final String VIDEO_SDK_DEBUG_TAG = "VideoSDK_DEBUG";

    private EditText apiKeyInput;
    private EditText apiSecretInput;
    private EditText meetingIdInput;
    private EditText nameInput;
    private FrameLayout localVideoContainer;
    private VideoView localVideoView;
    private VideoView remoteVideoView;
    private TextView localPlaceholder;
    private TextView remotePlaceholder;
    private TextView statusText;
    private TextView callDurationText;
    private View setupPanel;
    private View callHeader;
    private View callControls;
    private Button joinButton;
    private Button leaveButton;
    private Button muteButton;
    private Button switchCameraButton;
    private View.OnTouchListener localPreviewDragListener;

    private Meeting meeting;
    private ComponentManager.CallSession componentSession;
    private VideoTrack localTrack;
    private VideoTrack remoteTrack;
    private Participant renderedRemoteParticipant;
    private boolean joined;
    private boolean muted = true;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long callConnectedAtMs;
    private int remoteScanAttempts;
    private Runnable remoteScanRunnable;
    private final Runnable callDurationRunnable = new Runnable() {
        @Override
        public void run() {
            updateCallDuration();
            if (callConnectedAtMs > 0L) {
                mainHandler.postDelayed(this, 1000L);
            }
        }
    };

    private CustomStreamTrack customLocalVideoTrack;

    private CustomStreamTrack create15FpsCameraTrack() {
        return VideoSDK.createCameraVideoTrack(
                "h720p_w1280p",                       // 320x240 @ 15 fps
                "front",
                CustomStreamTrack.VideoMode.MOTION,
                false,                               // single stream; true only if you want simulcast
                this,
                null,                                // default camera
                1,                                   // maxLayer; only meaningful with simulcast
                BitrateMode.HIGH_QUALITY
        );
    }
    private final MeetingEventListener meetingEventListener = new MeetingEventListener() {
        @Override
        public void onMeetingJoined() {
            runOnUiThread(() -> {
                joined = true;
                componentSession = ComponentManager.startVideoSdkComponentSession(getApplicationContext());
                debugLog("onMeetingJoined meetingId="
                        + (meeting != null ? meeting.getMeetingId() : "null")
                        + " local="
                        + describeParticipant(meeting != null ? meeting.getLocalParticipant() : null)
                        + " remotes="
                        + getRemoteParticipantCount());
                setStatus("Joined " + (meeting != null ? meeting.getMeetingId() : "meeting"));
                updateCallControls();

                customLocalVideoTrack = create15FpsCameraTrack();
                if (customLocalVideoTrack != null && meeting != null) {
                    meeting.enableWebcam(customLocalVideoTrack);
                    debugLog("Enabled custom 15fps webcam track");
                } else {
                    debugLog("Custom 15fps webcam track creation failed");
                }

                setRemoteListeners();
                renderCurrentRemoteVideo();
                startRemoteVideoScan();
            });
        }

        @Override
        public void onMeetingLeft() {
            runOnUiThread(() -> {
                debugLog("onMeetingLeft");
                InternetSpeedRecorderUtil.Result internetResult = ComponentManager.finishComponentSession(componentSession);
                componentSession = null;
                setStatus("Left meeting. " + internetResult.toReadableText());
                resetCallUi();
            });
        }

        @Override
        public void onParticipantJoined(Participant participant) {
            debugLog("onParticipantJoined " + describeParticipant(participant));
            participant.addEventListener(createRemoteParticipantListener(participant));
            runOnUiThread(() -> {
                setStatus(participant.getDisplayName() + " joined. " + describeParticipantStreams(participant));
                renderParticipantVideo(participant);
                startRemoteVideoScan();
            });
        }

        @Override
        public void onParticipantLeft(Participant participant) {
            debugLog("onParticipantLeft " + describeParticipant(participant));
            participant.removeAllListeners();
            runOnUiThread(() -> {
                if (renderedRemoteParticipant != null
                        && TextUtils.equals(renderedRemoteParticipant.getId(), participant.getId())) {
                    clearRemoteVideo();
                }
                setStatus(participant.getDisplayName() + " left");
            });
        }

        @Override
        public void onError(JSONObject error) {
            runOnUiThread(() -> {
                String message = error.optString("message", error.toString());
                Log.e(TAG, "VideoSDK error: " + error);
                debugLog("onError " + error);
                setStatus("VideoSDK error: " + message);
                Toast.makeText(VideoSdkActivity.this, message, Toast.LENGTH_LONG).show();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_video_sdk);

        VideoSDK.initialize(getApplicationContext());

        applySystemInsets();
        bindViews();
        localVideoContainer.setClipToOutline(true);
        enableFloatingPreviewDrag(localVideoContainer, localVideoView, localPlaceholder);

        joinButton.setOnClickListener(v -> joinCall());
        leaveButton.setOnClickListener(v -> leaveCall());
        muteButton.setOnClickListener(v -> toggleMute());
        switchCameraButton.setOnClickListener(v -> {
            if (meeting != null) {
                meeting.changeWebcam();
            }
        });
    }

    private void bindViews() {
        apiKeyInput = findViewById(R.id.videoSdkTokenInput);
        apiSecretInput = findViewById(R.id.videoSdkSecretInput);
        meetingIdInput = findViewById(R.id.videoSdkMeetingIdInput);
        nameInput = findViewById(R.id.videoSdkNameInput);
        localVideoContainer = findViewById(R.id.localVideoSdkVideoContainer);
        localVideoView = (VideoView) findViewById(R.id.localVideoSdkVideoView);
        remoteVideoView = (VideoView) findViewById(R.id.remoteVideoSdkVideoView);
        localPlaceholder = findViewById(R.id.localVideoSdkPlaceholder);
        remotePlaceholder = findViewById(R.id.remoteVideoSdkPlaceholder);
        statusText = findViewById(R.id.videoSdkStatusText);
        callDurationText = findViewById(R.id.videoSdkCallDurationText);
        setupPanel = findViewById(R.id.videoSdkSetupPanel);
        callHeader = findViewById(R.id.videoSdkCallHeader);
        callControls = findViewById(R.id.videoSdkCallControls);
        joinButton = findViewById(R.id.videoSdkJoinButton);
        leaveButton = findViewById(R.id.videoSdkLeaveButton);
        muteButton = findViewById(R.id.videoSdkMuteButton);
        switchCameraButton = findViewById(R.id.videoSdkSwitchCameraButton);
    }

    private void applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.videoSdkRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            setTopMargin(findViewById(R.id.videoSdkSetupPanel), systemBars.top + dp(24));
            setTopMargin(findViewById(R.id.videoSdkCallHeader), systemBars.top + dp(28));
            setTopMargin(findViewById(R.id.localVideoSdkVideoContainer), systemBars.top + dp(92));

            View controls = findViewById(R.id.videoSdkCallControls);
            controls.setPadding(
                    systemBars.left + dp(18),
                    dp(18),
                    systemBars.right + dp(18),
                    systemBars.bottom + dp(28));
            return insets;
        });
    }

    private void setTopMargin(View view, int marginTop) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) layoutParams;
            frameParams.topMargin = marginTop;
            view.setLayoutParams(frameParams);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void enableFloatingPreviewDrag(View preview, Object... handles) {
        View.OnTouchListener dragListener = new View.OnTouchListener() {
            private float touchOffsetX;
            private float touchOffsetY;
            private final int[] parentLocation = new int[2];
            private final int[] previewLocation = new int[2];

            @Override
            public boolean onTouch(View touchedView, MotionEvent event) {
                View parent = (View) preview.getParent();
                if (parent == null) {
                    return false;
                }

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        parent.getLocationOnScreen(parentLocation);
                        preview.getLocationOnScreen(previewLocation);
                        touchOffsetX = event.getRawX() - previewLocation[0];
                        touchOffsetY = event.getRawY() - previewLocation[1];
                        preview.bringToFront();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        parent.getLocationOnScreen(parentLocation);
                        float nextX = event.getRawX() - parentLocation[0] - touchOffsetX;
                        float nextY = event.getRawY() - parentLocation[1] - touchOffsetY;
                        preview.setX(clamp(nextX, dp(12), parent.getWidth() - preview.getWidth() - dp(12)));
                        preview.setY(clamp(nextY, dp(12), parent.getHeight() - preview.getHeight() - dp(12)));
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        touchedView.performClick();
                        return true;

                    default:
                        return false;
                }
            }
        };

        preview.setOnTouchListener(dragListener);
        for (Object handle : handles) {
            if (handle instanceof View) {
                ((View) handle).setOnTouchListener(dragListener);
            }
        }
        localPreviewDragListener = dragListener;
    }

    private float clamp(float value, float min, float max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(value, max));
    }

    private void joinCall() {
        debugLog("joinCall clicked");
        if (!hasCallPermissions()) {
            debugLog("joinCall missing permissions; requesting");
            ActivityCompat.requestPermissions(this, requiredPermissions(), PERMISSION_REQUEST_ID);
            return;
        }

        String apiKey = apiKeyInput.getText().toString().trim();
        String apiSecret = apiSecretInput.getText().toString().trim();
        String meetingId = meetingIdInput.getText().toString().trim();
        String name = nameInput.getText().toString().trim();

        if (TextUtils.isEmpty(apiKey)) {
            apiKeyInput.setError("Required");
            return;
        }
        if (TextUtils.isEmpty(apiSecret)) {
            apiSecretInput.setError("Required");
            return;
        }
        if (TextUtils.isEmpty(name)) {
            nameInput.setError("Required");
            return;
        }

        String token = generateVideoSdkToken(apiKey, apiSecret, 24 * 60 * 60L);
        setStatus(TextUtils.isEmpty(meetingId) ? "Creating room..." : "Joining...");
        joinButton.setEnabled(false);
        debugLog("joinCall starting room=" + (TextUtils.isEmpty(meetingId) ? "<new>" : meetingId)
                + " name=" + name);

        new Thread(() -> {
            try {
                String roomId = TextUtils.isEmpty(meetingId) ? createVideoSdkRoom(token) : meetingId;
                debugLog("room ready roomId=" + roomId);
                runOnUiThread(() -> {
                    if (TextUtils.isEmpty(meetingIdInput.getText().toString().trim())) {
                        meetingIdInput.setText(roomId);
                    }
                    startMeeting(token, roomId, name);
                });
            } catch (Exception e) {
                debugLog("joinCall failed " + e.getClass().getSimpleName() + ": " + e.getMessage());
                runOnUiThread(() -> {
                    setStatus("VideoSDK room failed: " + e.getMessage());
                    Toast.makeText(this, "VideoSDK room failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    joinButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void startMeeting(String token, String meetingId, String name) {
        try {
            debugLog("startMeeting roomId=" + meetingId + " name=" + name);
            setStatus("Joining...");
            VideoSDK.config(token);

            meeting = VideoSDK.initMeeting(
                    this,
                    meetingId,
                    name,
                    false,
                    false,   // webcam disabled; we will enable custom 15-fps track after join
                    null,
                    null,
                    false,   // multiStream false to avoid extra layers
                    null,
                    null);
            debugLog("initMeeting complete local=" + describeParticipant(meeting.getLocalParticipant()));
            meeting.addEventListener(meetingEventListener);
            setLocalListeners();
            debugLog("calling meeting.join()");
            meeting.join();
        } catch (Exception e) {
            debugLog("startMeeting failed " + e.getClass().getSimpleName() + ": " + e.getMessage());
            setStatus("VideoSDK start failed: " + e.getMessage());
            joinButton.setEnabled(true);
        }
    }

    private String generateVideoSdkToken(String apiKey, String apiSecret, long validitySeconds) {
        try {
            long issuedAtSeconds = System.currentTimeMillis() / 1000L;
            long expiresAtSeconds = issuedAtSeconds + validitySeconds;
            String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            String payload = "{\"apikey\":\""
                    + escapeJson(apiKey)
                    + "\",\"permissions\":[\"allow_join\",\"allow_mod\"],\"version\":2,\"iat\":"
                    + issuedAtSeconds
                    + ",\"exp\":"
                    + expiresAtSeconds
                    + "}";
            String unsignedToken = base64Url(header) + "." + base64Url(payload);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.encodeToString(
                    mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8)),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return unsignedToken + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate token", e);
        }
    }

    private String createVideoSdkRoom(String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.videosdk.live/v2/rooms").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", token);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));

        int responseCode = connection.getResponseCode();
        String responseBody;
        try {
            InputStream stream = responseCode >= 200 && responseCode <= 299
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            responseBody = readAll(stream);
        } finally {
            connection.disconnect();
        }

        if (responseCode < 200 || responseCode > 299) {
            throw new IllegalStateException("HTTP " + responseCode + ": " + responseBody);
        }

        JSONObject response = new JSONObject(responseBody);
        String roomId = response.optString("roomId", response.optString("meetingId"));
        if (TextUtils.isEmpty(roomId)) {
            throw new IllegalStateException("Room id missing");
        }
        return roomId;
    }

    private String readAll(InputStream stream) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        }
    }

    private String base64Url(String value) {
        return Base64.encodeToString(
                value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private String escapeJson(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    builder.append(character);
                    break;
            }
        }
        return builder.toString();
    }

    private void setLocalListeners() {
        if (meeting == null || meeting.getLocalParticipant() == null) {
            debugLog("setLocalListeners skipped meeting/local null");
            return;
        }

        debugLog("setLocalListeners " + describeParticipant(meeting.getLocalParticipant()));
        meeting.getLocalParticipant().addEventListener(new ParticipantEventListener() {
            @Override
            public void onStreamEnabled(Stream stream) {
                debugLog("local onStreamEnabled " + describeStream(stream));
                if ("video".equalsIgnoreCase(stream.getKind())) {
                    runOnUiThread(() -> {
                        localTrack = stream.getTrack() instanceof VideoTrack ? (VideoTrack) stream.getTrack() : null;
                        renderLocalVideo();
                    });
                }
            }

            @Override
            public void onStreamDisabled(Stream stream) {
                debugLog("local onStreamDisabled " + describeStream(stream));
                if ("video".equalsIgnoreCase(stream.getKind())) {
                    runOnUiThread(() -> {
                        localTrack = null;
                        localVideoView.removeTrack();
                        localPlaceholder.setVisibility(View.VISIBLE);
                    });
                }
            }
        });
    }

    private void setRemoteListeners() {
        if (meeting == null || meeting.getParticipants() == null) {
            debugLog("setRemoteListeners skipped meeting/participants null");
            return;
        }
        debugLog("setRemoteListeners count=" + meeting.getParticipants().size()
                + " details=" + describeAllRemoteStreams());
        for (Participant participant : meeting.getParticipants().values()) {
            participant.addEventListener(createRemoteParticipantListener(participant));
        }
    }

    private ParticipantEventListener createRemoteParticipantListener(Participant participant) {
        return new ParticipantEventListener() {
            @Override
            public void onStreamEnabled(Stream stream) {
                debugLog("remote onStreamEnabled participant="
                        + describeParticipant(participant)
                        + " stream="
                        + describeStream(stream));
                if ("video".equalsIgnoreCase(stream.getKind())) {
                    runOnUiThread(() -> renderRemoteStream(stream, participant));
                }
            }

            @Override
            public void onStreamDisabled(Stream stream) {
                debugLog("remote onStreamDisabled participant="
                        + describeParticipant(participant)
                        + " stream="
                        + describeStream(stream));
                if ("video".equalsIgnoreCase(stream.getKind())) {
                    runOnUiThread(() -> {
                        if (renderedRemoteParticipant == null
                                || TextUtils.equals(renderedRemoteParticipant.getId(), participant.getId())) {
                            clearRemoteVideo();
                        }
                    });
                }
            }
        };
    }

    private void renderCurrentRemoteVideo() {
        if (meeting == null || meeting.getParticipants() == null) {
            debugLog("renderCurrentRemoteVideo skipped meeting/participants null");
            return;
        }
        debugLog("renderCurrentRemoteVideo count=" + meeting.getParticipants().size()
                + " details=" + describeAllRemoteStreams());
        for (Participant participant : meeting.getParticipants().values()) {
            if (renderParticipantVideo(participant)) {
                return;
            }
        }
        updateWaitingStatus();
    }

    private void startRemoteVideoScan() {
        stopRemoteVideoScan();
        remoteScanAttempts = 0;
        debugLog("startRemoteVideoScan");
        remoteScanRunnable = new Runnable() {
            @Override
            public void run() {
                if (!joined || remoteTrack != null) {
                    debugLog("remote scan stopped joined=" + joined
                            + " remoteTrack=" + describeTrack(remoteTrack));
                    return;
                }
                debugLog("remote scan attempt=" + (remoteScanAttempts + 1));
                renderCurrentRemoteVideo();
                remoteScanAttempts++;
                if (remoteTrack == null && remoteScanAttempts < 15) {
                    mainHandler.postDelayed(this, 1000);
                }
            }
        };
        mainHandler.post(remoteScanRunnable);
    }

    private void stopRemoteVideoScan() {
        if (remoteScanRunnable != null) {
            debugLog("stopRemoteVideoScan");
            mainHandler.removeCallbacks(remoteScanRunnable);
            remoteScanRunnable = null;
        }
    }

    private void renderLocalVideo() {
        localVideoView.removeTrack();
        if (localTrack != null) {
            debugLog("renderLocalVideo track=" + describeTrack(localTrack));
            localVideoView.setVisibility(View.VISIBLE);
            localVideoView.setZOrderMediaOverlay(true);
            localVideoView.addTrack(localTrack);
            localPlaceholder.setVisibility(View.GONE);
            localVideoContainer.bringToFront();
        }
    }

    private boolean renderParticipantVideo(Participant participant) {
        if (participant.getStreams() == null) {
            debugLog("renderParticipantVideo no stream map participant=" + describeParticipant(participant));
            return false;
        }
        debugLog("renderParticipantVideo participant=" + describeParticipant(participant)
                + " streams=" + describeParticipantStreams(participant));
        for (Stream stream : participant.getStreams().values()) {
            if (renderRemoteStream(stream, participant)) {
                return true;
            }
        }
        return false;
    }

    private boolean renderRemoteStream(Stream stream, Participant participant) {
        debugLog("renderRemoteStream participant="
                + describeParticipant(participant)
                + " stream="
                + describeStream(stream));
        if ("video".equalsIgnoreCase(stream.getKind()) && stream.getTrack() instanceof VideoTrack) {
            remoteTrack = (VideoTrack) stream.getTrack();
            if (participant != null) {
                renderedRemoteParticipant = participant;
                participant.setQuality("high");
                remoteVideoView.post(() -> participant.setViewPort(remoteVideoView.getWidth(), remoteVideoView.getHeight()));
            }
            remoteVideoView.removeTrack();
            remoteVideoView.setVisibility(View.VISIBLE);
            remoteVideoView.addTrack(remoteTrack);
            remotePlaceholder.setVisibility(View.GONE);
            if (componentSession != null) {
                componentSession.startRemoteVideoStorage(remoteVideoView, TARGET_VIDEO_FPS);
            }
            startCallDurationTimer();
            localVideoView.setZOrderMediaOverlay(true);
            localVideoContainer.bringToFront();
            setStatus(participant != null
                    ? "Showing " + participant.getDisplayName()
                    : "Remote video connected");
            debugLog("renderRemoteStream SUCCESS remoteTrack=" + describeTrack(remoteTrack));
            return true;
        }
        debugLog("renderRemoteStream skipped: kind/track mismatch");
        return false;
    }

    private void clearRemoteVideo() {
        debugLog("clearRemoteVideo previousTrack=" + describeTrack(remoteTrack));
        if (componentSession != null) {
            componentSession.stopRemoteVideoStorage();
        }
        stopCallDurationTimer();
        remoteVideoView.removeTrack();
        remoteTrack = null;
        renderedRemoteParticipant = null;
        remotePlaceholder.setVisibility(View.VISIBLE);
        updateWaitingStatus();
    }

    private void updateWaitingStatus() {
        if (!joined || meeting == null || meeting.getParticipants() == null) {
            return;
        }
        int participantCount = meeting.getParticipants().size();
        if (participantCount == 0) {
            setStatus("Joined. Waiting for another device in this meeting ID.");
            return;
        }
        setStatus("Remote joined, waiting for video stream. " + describeAllRemoteStreams());
    }

    private String describeAllRemoteStreams() {
        if (meeting == null || meeting.getParticipants() == null || meeting.getParticipants().isEmpty()) {
            return "No remote participants.";
        }
        StringBuilder builder = new StringBuilder();
        for (Participant participant : meeting.getParticipants().values()) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(participant.getDisplayName()).append(": ");
            builder.append(describeParticipantStreams(participant));
        }
        return builder.toString();
    }

    private String describeParticipantStreams(Participant participant) {
        if (participant.getStreams() == null || participant.getStreams().isEmpty()) {
            return "no streams";
        }
        StringBuilder builder = new StringBuilder();
        for (Stream stream : participant.getStreams().values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(describeStream(stream));
        }
        return builder.toString();
    }

    private String describeStream(Stream stream) {
        if (stream == null) {
            return "null stream";
        }
        return "id="
                + stream.getId()
                + " kind="
                + stream.getKind()
                + " track="
                + describeTrack(stream.getTrack());
    }

    private String describeParticipant(Participant participant) {
        if (participant == null) {
            return "null participant";
        }
        return "id="
                + participant.getId()
                + " name="
                + participant.getDisplayName()
                + " mode="
                + participant.getMode()
                + " streams="
                + describeParticipantStreams(participant);
    }

    private int getRemoteParticipantCount() {
        if (meeting == null || meeting.getParticipants() == null) {
            return 0;
        }
        return meeting.getParticipants().size();
    }

    private String describeTrack(Object track) {
        if (track == null) {
            return "null";
        }
        if (track instanceof org.webrtc.MediaStreamTrack) {
            org.webrtc.MediaStreamTrack mediaTrack = (org.webrtc.MediaStreamTrack) track;
            return track.getClass().getSimpleName()
                    + "{id="
                    + mediaTrack.id()
                    + ", kind="
                    + mediaTrack.kind()
                    + ", enabled="
                    + mediaTrack.enabled()
                    + ", state="
                    + mediaTrack.state()
                    + "}";
        }
        return "unknown{class="
                + track.getClass().getSimpleName()
                + "}";
    }

    private void debugLog(String message) {
        Log.d(VIDEO_SDK_DEBUG_TAG, message);
    }

    private void toggleMute() {
        setStatus("VideoSDK mic is disabled in this multi-provider build.");
    }

    private void leaveCall() {
        InternetSpeedRecorderUtil.Result internetResult = ComponentManager.finishComponentSession(componentSession);
        componentSession = null;
        stopCallDurationTimer();
        if (meeting != null) {
            meeting.leave();
        }
        setStatus("Left meeting. " + internetResult.toReadableText());
        resetCallUi();
    }

    private void resetCallUi() {
        joined = false;
        muted = true;
        if (componentSession != null) {
            ComponentManager.finishComponentSession(componentSession);
            componentSession = null;
        }
        stopCallDurationTimer();
        if (meeting != null) {
            meeting.removeAllListeners();
            if (meeting.getLocalParticipant() != null) {
                meeting.getLocalParticipant().removeAllListeners();
            }
        }
        meeting = null;
        stopRemoteVideoScan();
        localTrack = null;
        remoteTrack = null;
        renderedRemoteParticipant = null;
        localVideoView.removeTrack();
        remoteVideoView.removeTrack();
        localPlaceholder.setVisibility(View.VISIBLE);
        remotePlaceholder.setVisibility(View.VISIBLE);
        updateCallControls();
    }

    private void startCallDurationTimer() {
        if (callConnectedAtMs > 0L) {
            return;
        }

        callConnectedAtMs = SystemClock.elapsedRealtime();
        updateCallDuration();
        mainHandler.removeCallbacks(callDurationRunnable);
        mainHandler.postDelayed(callDurationRunnable, 1000L);
    }

    private void stopCallDurationTimer() {
        mainHandler.removeCallbacks(callDurationRunnable);
        callConnectedAtMs = 0L;
        if (callDurationText != null) {
            callDurationText.setText("Connected: 0 sec");
        }
    }

    private void updateCallDuration() {
        long connectedSeconds = 0L;
        if (callConnectedAtMs > 0L) {
            connectedSeconds = Math.max(0L, (SystemClock.elapsedRealtime() - callConnectedAtMs) / 1000L);
        }
        callDurationText.setText("Connected: " + connectedSeconds + " sec");
        if (CallAutoCutConfig.isEnabled() && connectedSeconds >= CallAutoCutConfig.AUTO_CUT_SECONDS) {
            mainHandler.removeCallbacks(callDurationRunnable);
            callConnectedAtMs = 0L;
            setStatus("Auto cut after " + CallAutoCutConfig.AUTO_CUT_SECONDS + " sec");
            leaveCall();
        }
    }

    private boolean hasCallPermissions() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] requiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        return permissions.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_ID) {
            return;
        }
        if (hasCallPermissions()) {
            joinCall();
        } else {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
        }
    }

    private void updateCallControls() {
        joinButton.setEnabled(!joined);
        leaveButton.setEnabled(joined);
        muteButton.setEnabled(false);
        switchCameraButton.setEnabled(joined);
        muteButton.setText("Mic off");
        setupPanel.setVisibility(joined ? View.GONE : View.VISIBLE);
        callHeader.setVisibility(joined ? View.VISIBLE : View.GONE);
        callControls.setVisibility(joined ? View.VISIBLE : View.GONE);
    }

    private void setStatus(String message) {
        statusText.setText(message);
    }

    @Override
    protected void onDestroy() {
        stopCallDurationTimer();
        if (meeting != null) {
            ComponentManager.finishComponentSession(componentSession);
            componentSession = null;
            meeting.leave();
            meeting.removeAllListeners();
            if (meeting.getLocalParticipant() != null) {
                meeting.getLocalParticipant().removeAllListeners();
            }
        }
        ComponentManager.finishComponentSession(componentSession);
        componentSession = null;
        stopRemoteVideoScan();
        localVideoView.releaseSurfaceViewRenderer();
        remoteVideoView.releaseSurfaceViewRenderer();
        super.onDestroy();
    }
}
