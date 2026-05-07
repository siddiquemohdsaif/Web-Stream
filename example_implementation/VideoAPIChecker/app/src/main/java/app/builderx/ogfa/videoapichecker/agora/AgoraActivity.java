package app.builderx.ogfa.videoapichecker.agora;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.SurfaceView;
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

import app.builderx.ogfa.videoapichecker.R;
import app.builderx.ogfa.videoapichecker.Util.CallAutoCutConfig;
import app.builderx.ogfa.videoapichecker.Util.ComponentManager;
import app.builderx.ogfa.videoapichecker.Util.InternetSpeedRecorderUtil;
import io.agora.rtc2.ChannelMediaOptions;
import io.agora.rtc2.Constants;
import io.agora.rtc2.IRtcEngineEventHandler;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.RtcEngineConfig;
import io.agora.rtc2.video.VideoCanvas;
import io.agora.rtc2.video.VideoEncoderConfiguration;

public class AgoraActivity extends AppCompatActivity {
    private static final int PERMISSION_REQ_ID = 11;
    private static final String[] CALL_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };

    private EditText appIdInput;
    private EditText tokenInput;
    private EditText channelInput;
    private FrameLayout localVideoContainer;
    private FrameLayout remoteVideoContainer;
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

    private RtcEngine rtcEngine;
    private ComponentManager.CallSession componentSession;
    private final Handler callDurationHandler = new Handler(Looper.getMainLooper());
    private long callConnectedAtMs;
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

    private final IRtcEngineEventHandler rtcEventHandler = new IRtcEngineEventHandler() {
        @Override
        public void onJoinChannelSuccess(String channel, int uid, int elapsed) {
            runOnUiThread(() -> {
                joined = true;
                componentSession = ComponentManager.startAgoraComponentSession(getApplicationContext());
                updateCallControls();
                setStatus("Joined " + channel + " as uid " + uid);
            });
        }

        @Override
        public void onUserJoined(int uid, int elapsed) {
            runOnUiThread(() -> setupRemoteVideo(uid));
        }

        @Override
        public void onUserOffline(int uid, int reason) {
            runOnUiThread(() -> {
                if (componentSession != null) {
                    componentSession.stopRemoteVideoStorage();
                }
                stopCallDurationTimer();
                remoteVideoContainer.removeAllViews();
                remotePlaceholder.setVisibility(View.VISIBLE);
                remoteVideoContainer.addView(remotePlaceholder);
                setStatus("Remote user left: " + uid);
            });
        }

        @Override
        public void onError(int err) {
            runOnUiThread(() -> setStatus("Agora error: " + err));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_agora);

        applySystemInsets();
        bindViews();
        localVideoContainer.setClipToOutline(true);
        enableFloatingPreviewDrag(localVideoContainer, localPlaceholder);
        joinButton.setOnClickListener(v -> joinCall());
        leaveButton.setOnClickListener(v -> leaveCall());
        muteButton.setOnClickListener(v -> toggleMute());
        switchCameraButton.setOnClickListener(v -> {
            if (rtcEngine != null) {
                rtcEngine.switchCamera();
            }
        });
    }

    private void bindViews() {
        appIdInput = findViewById(R.id.appIdInput);
        tokenInput = findViewById(R.id.tokenInput);
        channelInput = findViewById(R.id.channelInput);
        localVideoContainer = findViewById(R.id.localVideoContainer);
        remoteVideoContainer = findViewById(R.id.remoteVideoContainer);
        localPlaceholder = findViewById(R.id.localPlaceholder);
        remotePlaceholder = findViewById(R.id.remotePlaceholder);
        statusText = findViewById(R.id.statusText);
        callDurationText = findViewById(R.id.agoraCallDurationText);
        setupPanel = findViewById(R.id.agoraSetupPanel);
        callHeader = findViewById(R.id.agoraCallHeader);
        callControls = findViewById(R.id.agoraCallControls);
        joinButton = findViewById(R.id.joinButton);
        leaveButton = findViewById(R.id.leaveButton);
        muteButton = findViewById(R.id.muteButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);
    }

    private void applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.agoraRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            setTopMargin(findViewById(R.id.agoraSetupPanel), systemBars.top + dp(24));
            setTopMargin(findViewById(R.id.agoraCallHeader), systemBars.top + dp(28));
            setTopMargin(findViewById(R.id.localVideoContainer), systemBars.top + dp(92));

            View controls = findViewById(R.id.agoraCallControls);
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
            ActivityCompat.requestPermissions(this, CALL_PERMISSIONS, PERMISSION_REQ_ID);
            return;
        }

        String appId = appIdInput.getText().toString().trim();
        String channelName = channelInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();

        if (TextUtils.isEmpty(appId)) {
            appIdInput.setError("Required");
            return;
        }

        if (TextUtils.isEmpty(channelName)) {
            channelInput.setError("Required");
            return;
        }

        try {
            initializeEngine(appId);
            setupLocalVideo();
            joinAgoraChannel(TextUtils.isEmpty(token) ? null : token, channelName);
        } catch (Exception e) {
            setStatus("Failed to start Agora: " + e.getMessage());
        }
    }

    private void initializeEngine(String appId) throws Exception {
        if (rtcEngine != null) {
            return;
        }

        RtcEngineConfig config = new RtcEngineConfig();
        config.mContext = getApplicationContext();
        config.mAppId = appId;
        config.mEventHandler = rtcEventHandler;
        rtcEngine = RtcEngine.create(config);
        rtcEngine.enableVideo();
        rtcEngine.setVideoEncoderConfiguration(new VideoEncoderConfiguration(
                VideoEncoderConfiguration.VD_1280x720,
                VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                VideoEncoderConfiguration.STANDARD_BITRATE,
                VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE));
    }

    private void setupLocalVideo() {
        localVideoContainer.removeAllViews();
        localPlaceholder.setVisibility(View.GONE);

        SurfaceView localView = new SurfaceView(getBaseContext());
        localView.setZOrderMediaOverlay(true);
        localView.setOnTouchListener(localPreviewDragListener);
        localVideoContainer.addView(localView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rtcEngine.setupLocalVideo(new VideoCanvas(localView, VideoCanvas.RENDER_MODE_HIDDEN, 0));
        rtcEngine.startPreview();
    }

    private void joinAgoraChannel(String token, String channelName) {
        setStatus("Joining " + channelName + "...");

        ChannelMediaOptions options = new ChannelMediaOptions();
        options.channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION;
        options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER;
        options.publishCameraTrack = true;
        options.publishMicrophoneTrack = true;
        options.autoSubscribeAudio = true;
        options.autoSubscribeVideo = true;

        int result = rtcEngine.joinChannel(token, channelName, 0, options);
        if (result != 0) {
            setStatus("Join failed: " + result);
        }
    }

    private void setupRemoteVideo(int uid) {
        remoteVideoContainer.removeAllViews();
        remotePlaceholder.setVisibility(View.GONE);

        SurfaceView remoteView = new SurfaceView(getBaseContext());
        remoteVideoContainer.addView(remoteView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rtcEngine.setupRemoteVideo(new VideoCanvas(remoteView, VideoCanvas.RENDER_MODE_HIDDEN, uid));
        if (componentSession != null) {
            componentSession.startRemoteVideoStorage(remoteView);
        }
        startCallDurationTimer();
        setStatus("Remote user joined: " + uid);
    }

    private void toggleMute() {
        if (rtcEngine == null) {
            return;
        }

        muted = !muted;
        rtcEngine.muteLocalAudioStream(muted);
        muteButton.setText(muted ? "Unmute" : "Mute");
    }

    private void leaveCall() {
        InternetSpeedRecorderUtil.Result internetResult = ComponentManager.finishComponentSession(componentSession);
        componentSession = null;
        stopCallDurationTimer();
        if (rtcEngine != null) {
            rtcEngine.leaveChannel();
            rtcEngine.stopPreview();
        }

        joined = false;
        muted = false;
        localVideoContainer.removeAllViews();
        remoteVideoContainer.removeAllViews();
        localPlaceholder.setVisibility(View.VISIBLE);
        remotePlaceholder.setVisibility(View.VISIBLE);
        localVideoContainer.addView(localPlaceholder);
        remoteVideoContainer.addView(remotePlaceholder);
        setStatus("Left call. " + internetResult.toReadableText());
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

    private boolean hasCallPermissions() {
        for (String permission : CALL_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
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
            Toast.makeText(this, "Camera and microphone permissions are required.", Toast.LENGTH_LONG).show();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCallDurationTimer();
        if (rtcEngine != null) {
            ComponentManager.finishComponentSession(componentSession);
            componentSession = null;
            rtcEngine.leaveChannel();
            rtcEngine.stopPreview();
            RtcEngine.destroy();
            rtcEngine = null;
        }
    }
}
