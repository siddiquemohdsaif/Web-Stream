package app.builderx.ogfa.videoapichecker.hundredms;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
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
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

import app.builderx.ogfa.videoapichecker.R;
import app.builderx.ogfa.videoapichecker.Util.CallAutoCutConfig;
import app.builderx.ogfa.videoapichecker.Util.ComponentManager;
import app.builderx.ogfa.videoapichecker.Util.InternetSpeedRecorderUtil;
import hms.webrtc.RendererCommon;
import live.hms.video.error.HMSException;
import live.hms.video.media.settings.HMSTrackSettings;
import live.hms.video.media.settings.HMSVideoResolution;
import live.hms.video.media.settings.HMSVideoTrackSettings;
import live.hms.video.media.tracks.HMSAudioTrack;
import live.hms.video.media.tracks.HMSLocalAudioTrack;
import live.hms.video.media.tracks.HMSLocalVideoTrack;
import live.hms.video.media.tracks.HMSTrack;
import live.hms.video.media.tracks.HMSTrackType;
import live.hms.video.media.tracks.HMSVideoTrack;
import live.hms.video.sdk.HMSActionResultListener;
import live.hms.video.sdk.HMSSDK;
import live.hms.video.sdk.HMSUpdateListener;
import live.hms.video.sdk.models.DegradationPreference;
import live.hms.video.sdk.models.HMSMessage;
import live.hms.video.sdk.models.HMSPeer;
import live.hms.video.sdk.models.HMSRemovedFromRoom;
import live.hms.video.sdk.models.HMSRoleChangeRequest;
import live.hms.video.sdk.models.HMSRoom;
import live.hms.video.sdk.models.enums.HMSPeerUpdate;
import live.hms.video.sdk.models.enums.HMSRoomUpdate;
import live.hms.video.sdk.models.enums.HMSTrackUpdate;
import live.hms.video.sdk.models.HMSConfig;
import live.hms.video.sdk.models.trackchangerequest.HMSChangeTrackStateRequest;
import live.hms.video.sdk.transcripts.HmsTranscripts;
import live.hms.video.sessionstore.HmsSessionStore;
import live.hms.video.signal.init.HMSTokenListener;
import live.hms.video.signal.init.TokenRequest;
import live.hms.videoview.HMSVideoView;

public class HundredMsActivity extends AppCompatActivity {
    private static final int PERMISSION_REQ_ID = 12;
    private static final int TARGET_VIDEO_FPS = 15;

    private EditText userNameInput;
    private EditText roomCodeInput;
    private EditText authTokenInput;
    private FrameLayout localVideoContainer;
    private HMSVideoView localVideoView;
    private HMSVideoView remoteVideoView;
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

    private HMSSDK hmsSDK;
    private ComponentManager.CallSession componentSession;
    private final Handler callDurationHandler = new Handler(Looper.getMainLooper());
    private long callConnectedAtMs;
    private HMSPeer renderedRemotePeer;
    private boolean joined;
    private boolean muted;

    private final Runnable callDurationRunnable = new Runnable() {
        @Override
        public void run() {
            updateCallDuration();
            if (callConnectedAtMs > 0L) {
                callDurationHandler.postDelayed(this, 1000L);
            }
        }
    };

    private final HMSUpdateListener hmsUpdateListener = new HMSUpdateListener() {
        @Override
        public void onJoin(@NonNull HMSRoom hmsRoom) {
            runOnUiThread(() -> {
                joined = true;
                componentSession = ComponentManager.startHundredMsComponentSession(getApplicationContext());
                updateCallControls();
                setStatus("Joined " + hmsRoom.getName());
                renderPeerVideo(hmsRoom.getLocalPeer());
                scheduleRenderAvailableVideos();
            });
        }

        @Override
        public void onRoomUpdate(@NonNull HMSRoomUpdate hmsRoomUpdate, @NonNull HMSRoom hmsRoom) {
        }

        @Override
        public void onPeerUpdate(@NonNull HMSPeerUpdate hmsPeerUpdate, @NonNull HMSPeer hmsPeer) {
            runOnUiThread(() -> {
                if (hmsPeerUpdate == HMSPeerUpdate.PEER_JOINED) {
                    setStatus(hmsPeer.getName() + " joined");
                } else if (hmsPeerUpdate == HMSPeerUpdate.PEER_LEFT) {
                    if (renderedRemotePeer != null
                            && TextUtils.equals(renderedRemotePeer.getPeerID(), hmsPeer.getPeerID())) {
                        clearRemoteVideo();
                    }
                    setStatus(hmsPeer.getName() + " left");
                }
            });
        }

        @Override
        public void onTrackUpdate(@NonNull HMSTrackUpdate hmsTrackUpdate, @NonNull HMSTrack hmsTrack, @NonNull HMSPeer hmsPeer) {
            runOnUiThread(() -> {
                if (hmsTrack.getType() != HMSTrackType.VIDEO || !(hmsTrack instanceof HMSVideoTrack)) {
                    return;
                }

                if (hmsTrackUpdate == HMSTrackUpdate.TRACK_ADDED
                        || hmsTrackUpdate == HMSTrackUpdate.TRACK_UNMUTED
                        || hmsTrackUpdate == HMSTrackUpdate.TRACK_RESTORED) {
                    renderPeerVideo(hmsPeer, (HMSVideoTrack) hmsTrack);
                } else if (hmsTrackUpdate == HMSTrackUpdate.TRACK_REMOVED
                        || hmsTrackUpdate == HMSTrackUpdate.TRACK_MUTED
                        || hmsTrackUpdate == HMSTrackUpdate.TRACK_DEGRADED) {
                    removePeerVideo(hmsPeer);
                }
            });
        }

        @Override
        public void onRoleChangeRequest(@NonNull HMSRoleChangeRequest hmsRoleChangeRequest) {
        }

        @Override
        public void onMessageReceived(@NonNull HMSMessage hmsMessage) {
        }

        @Override
        public void onReconnecting(@NonNull HMSException e) {
            runOnUiThread(() -> setStatus("Reconnecting: " + errorMessage(e)));
        }

        @Override
        public void onReconnected() {
            runOnUiThread(() -> setStatus("Reconnected"));
        }

        @Override
        public void onRemovedFromRoom(@NonNull HMSRemovedFromRoom hmsRemovedFromRoom) {
            runOnUiThread(() -> {
                InternetSpeedRecorderUtil.Result internetResult = ComponentManager.finishComponentSession(componentSession);
                componentSession = null;
                stopCallDurationTimer();
                setStatus("Removed from room. " + internetResult.toReadableText());
                resetCallUi();
            });
        }

        @Override
        public void onChangeTrackStateRequest(@NonNull HMSChangeTrackStateRequest hmsChangeTrackStateRequest) {
        }

        @Override
        public void peerListUpdated(@Nullable ArrayList<HMSPeer> addedPeers, @Nullable ArrayList<HMSPeer> removedPeers) {
            runOnUiThread(() -> {
                if (addedPeers != null) {
                    scheduleRenderAvailableVideos();
                }
                if (removedPeers != null) {
                    for (HMSPeer peer : removedPeers) {
                        removePeerVideo(peer);
                    }
                }
            });
        }

        @Override
        public void onSessionStoreAvailable(@NonNull HmsSessionStore hmsSessionStore) {
        }

        @Override
        public void onTranscripts(@NonNull HmsTranscripts hmsTranscripts) {
        }

        @Override
        public void onPermissionsRequested(@NonNull List<String> permissions) {
        }

        @Override
        public void onError(@NonNull HMSException e) {
            runOnUiThread(() -> setStatus("100ms error: " + errorMessage(e)));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_hundred_ms);

        applySystemInsets();
        bindViews();
        localVideoContainer.setClipToOutline(true);
        enableFloatingPreviewDrag(localVideoContainer, localVideoView, localPlaceholder);
        localVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);

        joinButton.setOnClickListener(v -> joinCall());
        leaveButton.setOnClickListener(v -> leaveCall());
        muteButton.setOnClickListener(v -> toggleMute());
        switchCameraButton.setOnClickListener(v -> switchCamera());
    }

    private void bindViews() {
        userNameInput = findViewById(R.id.userNameInput);
        roomCodeInput = findViewById(R.id.roomCodeInput);
        authTokenInput = findViewById(R.id.authTokenInput);
        localVideoContainer = findViewById(R.id.localHmsVideoContainer);
        localVideoView = findViewById(R.id.localHmsVideoView);
        remoteVideoView = findViewById(R.id.remoteHmsVideoView);
        localPlaceholder = findViewById(R.id.localHmsPlaceholder);
        remotePlaceholder = findViewById(R.id.remoteHmsPlaceholder);
        statusText = findViewById(R.id.hmsStatusText);
        callDurationText = findViewById(R.id.hmsCallDurationText);
        setupPanel = findViewById(R.id.hmsSetupPanel);
        callHeader = findViewById(R.id.hmsCallHeader);
        callControls = findViewById(R.id.hmsCallControls);
        joinButton = findViewById(R.id.hmsJoinButton);
        leaveButton = findViewById(R.id.hmsLeaveButton);
        muteButton = findViewById(R.id.hmsMuteButton);
        switchCameraButton = findViewById(R.id.hmsSwitchCameraButton);
    }

    private void applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.hundredMsRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            setTopMargin(findViewById(R.id.hmsSetupPanel), systemBars.top + dp(24));
            setTopMargin(findViewById(R.id.hmsCallHeader), systemBars.top + dp(28));
            setTopMargin(findViewById(R.id.localHmsVideoContainer), systemBars.top + dp(92));

            View controls = findViewById(R.id.hmsCallControls);
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

    private void enableFloatingPreviewDrag(View preview, View... handles) {
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
        for (View handle : handles) {
            handle.setOnTouchListener(dragListener);
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
        if (!hasCallPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions(), PERMISSION_REQ_ID);
            return;
        }

        String userName = userNameInput.getText().toString().trim();
        String roomCode = roomCodeInput.getText().toString().trim();
        String authToken = authTokenInput.getText().toString().trim();

        if (TextUtils.isEmpty(userName)) {
            userNameInput.setError("Required");
            return;
        }

        if (TextUtils.isEmpty(authToken) && TextUtils.isEmpty(roomCode)) {
            roomCodeInput.setError("Room code or auth token required");
            authTokenInput.setError("Room code or auth token required");
            return;
        }

        ensureHmsSdk();
        if (!TextUtils.isEmpty(authToken)) {
            joinWithToken(userName, authToken);
        } else {
            fetchTokenAndJoin(userName, roomCode);
        }
    }

    private void ensureHmsSdk() {
        if (hmsSDK == null) {
            HMSVideoTrackSettings videoTrackSettings = new HMSVideoTrackSettings.Builder()
                    .resolution(new HMSVideoResolution(1280, 720))
                    .maxBitrate(9000)
                    .maxFrameRate(28)
                    .setDegradationPreference(DegradationPreference.MAINTAIN_RESOLUTION)
                    .build();

            HMSTrackSettings trackSettings = new HMSTrackSettings.Builder()
                    .video(videoTrackSettings)
                    .build();

            hmsSDK = new HMSSDK.Builder(getApplication()).setTrackSettings(trackSettings).build();
        }
    }

    private void fetchTokenAndJoin(String userName, String roomCode) {
        setStatus("Generating token...");
        joinButton.setEnabled(false);
        hmsSDK.getAuthTokenByRoomCode(new TokenRequest(roomCode, null), null, new HMSTokenListener() {
            @Override
            public void onTokenSuccess(@NonNull String token) {
                runOnUiThread(() -> joinWithToken(userName, token));
            }

            @Override
            public void onError(@NonNull HMSException e) {
                runOnUiThread(() -> {
                    setStatus("Token error: " + errorMessage(e));
                    updateCallControls();
                });
            }
        });
    }

    private void joinWithToken(String userName, String authToken) {
        setStatus("Joining...");
        joinButton.setEnabled(false);
        HMSConfig config = new HMSConfig(userName, authToken);
        hmsSDK.join(config, hmsUpdateListener);
    }

    private void renderPeerVideo(HMSPeer peer) {
        if (peer == null) {
            return;
        }

        renderPeerVideo(peer, peer.getVideoTrack());
    }

    private void renderPeerVideo(HMSPeer peer, HMSVideoTrack videoTrack) {
        if (peer == null || videoTrack == null) {
            return;
        }

        if (peer.isLocal()) {
            localVideoView.removeTrack();
            localVideoView.setVisibility(View.VISIBLE);
            localVideoView.addTrack(videoTrack);
            localPlaceholder.setVisibility(View.GONE);
            localVideoContainer.bringToFront();
        } else {
            remoteVideoView.removeTrack();
            remoteVideoView.setVisibility(View.VISIBLE);
            remoteVideoView.addTrack(videoTrack);
            renderedRemotePeer = peer;
            remotePlaceholder.setVisibility(View.GONE);
            if (componentSession != null) {
                componentSession.startRemoteVideoStorage(remoteVideoView);
            }
            startCallDurationTimer();
        }
    }

    private void renderAvailableVideos() {
        if (!joined || hmsSDK == null) {
            return;
        }

        renderPeerVideo(hmsSDK.getLocalPeer());
        List<HMSPeer> peers = hmsSDK.getPeers();
        if (peers != null) {
            for (HMSPeer peer : peers) {
                renderPeerVideo(peer);
            }
        }
    }

    private void scheduleRenderAvailableVideos() {
        callDurationHandler.postDelayed(this::renderAvailableVideos, 500L);
        callDurationHandler.postDelayed(this::renderAvailableVideos, 1500L);
        callDurationHandler.postDelayed(this::renderAvailableVideos, 3000L);
    }

    private void removePeerVideo(HMSPeer peer) {
        if (peer == null) {
            return;
        }

        if (peer.isLocal()) {
            localVideoView.removeTrack();
            localPlaceholder.setVisibility(View.VISIBLE);
        } else if (renderedRemotePeer != null
                && TextUtils.equals(renderedRemotePeer.getPeerID(), peer.getPeerID())) {
            clearRemoteVideo();
        }
    }

    private void clearRemoteVideo() {
        if (componentSession != null) {
            componentSession.stopRemoteVideoStorage();
        }
        stopCallDurationTimer();
        remoteVideoView.removeTrack();
        renderedRemotePeer = null;
        remotePlaceholder.setVisibility(View.VISIBLE);
    }

    private void toggleMute() {
        HMSLocalAudioTrack audioTrack = getLocalAudioTrack();
        if (audioTrack == null) {
            setStatus("Local audio track is not ready yet");
            return;
        }

        muted = !muted;
        audioTrack.setMute(muted);
        muteButton.setText(muted ? "Unmute" : "Mute");
    }

    private void switchCamera() {
        HMSLocalVideoTrack videoTrack = getLocalVideoTrack();
        if (videoTrack == null) {
            setStatus("Local video track is not ready yet");
            return;
        }

        videoTrack.switchCamera(simpleActionListener("Camera switched"));
    }

    private HMSLocalAudioTrack getLocalAudioTrack() {
        if (hmsSDK == null || hmsSDK.getLocalPeer() == null) {
            return null;
        }

        HMSAudioTrack audioTrack = hmsSDK.getLocalPeer().getAudioTrack();
        if (audioTrack instanceof HMSLocalAudioTrack) {
            return (HMSLocalAudioTrack) audioTrack;
        }
        return null;
    }

    private HMSLocalVideoTrack getLocalVideoTrack() {
        if (hmsSDK == null || hmsSDK.getLocalPeer() == null) {
            return null;
        }

        HMSVideoTrack videoTrack = hmsSDK.getLocalPeer().getVideoTrack();
        if (videoTrack instanceof HMSLocalVideoTrack) {
            return (HMSLocalVideoTrack) videoTrack;
        }
        return null;
    }

    private void leaveCall() {
        if (hmsSDK == null) {
            resetCallUi();
            return;
        }

        hmsSDK.leave(new HMSActionResultListener() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    InternetSpeedRecorderUtil.Result internetResult = ComponentManager.finishComponentSession(componentSession);
                    componentSession = null;
                    stopCallDurationTimer();
                    setStatus("Left room. " + internetResult.toReadableText());
                    resetCallUi();
                });
            }

            @Override
            public void onError(@NonNull HMSException e) {
                runOnUiThread(() -> setStatus("Leave error: " + errorMessage(e)));
            }
        });
    }

    private void resetCallUi() {
        joined = false;
        muted = false;
        localVideoView.removeTrack();
        remoteVideoView.removeTrack();
        renderedRemotePeer = null;
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
        callDurationHandler.removeCallbacks(callDurationRunnable);
        callDurationHandler.postDelayed(callDurationRunnable, 1000L);
    }

    private void stopCallDurationTimer() {
        callDurationHandler.removeCallbacks(callDurationRunnable);
        callConnectedAtMs = 0L;
        callDurationText.setText("Connected: 0 sec");
    }

    private void updateCallDuration() {
        long connectedSeconds = 0L;
        if (callConnectedAtMs > 0L) {
            connectedSeconds = Math.max(0L, (SystemClock.elapsedRealtime() - callConnectedAtMs) / 1000L);
        }
        callDurationText.setText("Connected: " + connectedSeconds + " sec");
        if (CallAutoCutConfig.isEnabled() && connectedSeconds >= CallAutoCutConfig.AUTO_CUT_SECONDS) {
            callDurationHandler.removeCallbacks(callDurationRunnable);
            callConnectedAtMs = 0L;
            setStatus("Auto cut after " + CallAutoCutConfig.AUTO_CUT_SECONDS + " sec");
            leaveCall();
        }
    }

    private HMSActionResultListener simpleActionListener(String successMessage) {
        return new HMSActionResultListener() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> setStatus(successMessage));
            }

            @Override
            public void onError(@NonNull HMSException e) {
                runOnUiThread(() -> setStatus("Action error: " + errorMessage(e)));
            }
        };
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
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return permissions.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQ_ID) {
            return;
        }

        if (hasCallPermissions()) {
            joinCall();
        } else {
            Toast.makeText(this, "Camera, microphone, and Bluetooth audio permissions are required.", Toast.LENGTH_LONG).show();
        }
    }

    private void updateCallControls() {
        joinButton.setEnabled(!joined);
        leaveButton.setEnabled(joined);
        muteButton.setEnabled(joined);
        switchCameraButton.setEnabled(joined);
        muteButton.setText(muted ? "Unmute" : "Mute");
        setupPanel.setVisibility(joined ? View.GONE : View.VISIBLE);
        callHeader.setVisibility(joined ? View.VISIBLE : View.GONE);
        callControls.setVisibility(joined ? View.VISIBLE : View.GONE);
    }

    private void setStatus(String message) {
        statusText.setText(message);
    }

    private String errorMessage(HMSException exception) {
        if (exception.getMessage() != null) {
            return exception.getMessage();
        }
        return exception.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCallDurationTimer();
        if (hmsSDK != null) {
            ComponentManager.finishComponentSession(componentSession);
            componentSession = null;
            hmsSDK.leave(null);
            hmsSDK = null;
        }
    }
}
