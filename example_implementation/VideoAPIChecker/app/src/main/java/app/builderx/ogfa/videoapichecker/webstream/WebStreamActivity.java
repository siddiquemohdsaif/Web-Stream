package app.builderx.ogfa.videoapichecker.webstream;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import app.builderx.ogfa.videoapichecker.R;
import app.builderx.ogfa.videoapichecker.Util.ComponentManager;
import app.builderx.ogfa.videoapichecker.Util.InternetSpeedRecorderUtil;

import com.w3n.webstream.WebStreamCall;
import com.w3n.webstream.WebStreamCallListener;
import com.w3n.webstream.WebStreamCallOptions;
import com.w3n.webstream.WebStreamClient;
import com.w3n.webstream.WebStreamVideoTrack;
import com.w3n.webstream.WebStreamVideoView;

public class WebStreamActivity extends AppCompatActivity {
    private static final int TARGET_VIDEO_FPS = 15;

    private EditText userIdInput;
    private EditText callIdInput;
    private View localVideoContainer;
    private WebStreamVideoView localVideoView;
    private WebStreamVideoView remoteVideoView;
    private TextView localPlaceholder;
    private TextView remotePlaceholder;
    private TextView statusText;
    private View setupPanel;
    private View callHeader;
    private View callControls;
    private Button joinButton;
    private Button leaveButton;
    private Button muteButton;
    private Button cameraButton;
    private Button switchCameraButton;

    private WebStreamClient webStreamClient;
    private WebStreamCall webStreamCall;
    private ComponentManager.CallSession componentSession;
    private boolean muted;
    private boolean cameraEnabled = true;
    private View.OnTouchListener localPreviewDragListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_web_stream);

        bindViews();
        applySystemInsets();
        enableFloatingPreviewDrag(localVideoContainer, localVideoView, localPlaceholder);

        joinButton.setOnClickListener(v -> joinLocalCall());
        leaveButton.setOnClickListener(v -> leaveLocalCall());
        muteButton.setOnClickListener(v -> toggleMute());
        cameraButton.setOnClickListener(v -> toggleCamera());
        switchCameraButton.setOnClickListener(v -> {
            if (webStreamCall != null) {
                webStreamCall.switchCamera();
            }
        });
    }

    private void bindViews() {
        userIdInput = findViewById(R.id.webStreamUserIdInput);
        callIdInput = findViewById(R.id.webStreamCallIdInput);
        localVideoContainer = findViewById(R.id.localWebStreamVideoContainer);
        localVideoView = findViewById(R.id.localWebStreamVideoView);
        remoteVideoView = findViewById(R.id.remoteWebStreamVideoView);
        localPlaceholder = findViewById(R.id.localWebStreamPlaceholder);
        remotePlaceholder = findViewById(R.id.remoteWebStreamPlaceholder);
        statusText = findViewById(R.id.webStreamStatusText);
        setupPanel = findViewById(R.id.webStreamSetupPanel);
        callHeader = findViewById(R.id.webStreamCallHeader);
        callControls = findViewById(R.id.webStreamCallControls);
        joinButton = findViewById(R.id.webStreamJoinButton);
        leaveButton = findViewById(R.id.webStreamLeaveButton);
        muteButton = findViewById(R.id.webStreamMuteButton);
        cameraButton = findViewById(R.id.webStreamCameraButton);
        switchCameraButton = findViewById(R.id.webStreamSwitchCameraButton);
    }

    private void applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.webStreamRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            setTopMargin(findViewById(R.id.webStreamSetupPanel), systemBars.top + dp(24));
            setTopMargin(findViewById(R.id.webStreamCallHeader), systemBars.top + dp(28));
            setTopMargin(findViewById(R.id.localWebStreamVideoContainer), systemBars.top + dp(92));

            View controls = findViewById(R.id.webStreamCallControls);
            controls.setPadding(
                    systemBars.left + dp(18),
                    dp(18),
                    systemBars.right + dp(18),
                    systemBars.bottom + dp(28));
            return insets;
        });
    }

    private void setTopMargin(View view, int marginTop) {
        android.view.ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams frameParams =
                    (android.widget.FrameLayout.LayoutParams) layoutParams;
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

    private void joinLocalCall() {
        String userId = userIdInput.getText().toString().trim();
        String callId = callIdInput.getText().toString().trim();

        if (TextUtils.isEmpty(userId)) {
            userIdInput.setError("Required");
            return;
        }
        if (TextUtils.isEmpty(callId)) {
            callIdInput.setError("Required");
            return;
        }

        leaveExistingCall(false);
        muted = false;
        cameraEnabled = true;
        updateControlLabels();
        setStatus("Starting webStream call...");

        WebStreamCallOptions options = new WebStreamCallOptions.Builder()
                .videoResolution(720, 1280)
                .frameRateFps(15)
                .bitrateKbps(1200)
                .imageFormat(WebStreamCallOptions.ImageFormat.JXL)
                .build();

        webStreamClient = new WebStreamClient.Builder(this)
                .userId(userId)
                .defaultCallOptions(options)
                .build();

        webStreamCall = webStreamClient.joinCall(callId, new WebStreamCallListener() {
            @Override
            public void onConnecting() {
                setStatus("Connecting to webStream server...");
                updateCallControls(true);
            }

            @Override
            public void onConnected() {
                if (componentSession == null) {
                    componentSession = ComponentManager.startWebStreamComponentSession(getApplicationContext());
                }
                setStatus("Connected.");
                updateCallControls(true);
            }

            @Override
            public void onDisconnected() {
                if (componentSession != null) {
                    InternetSpeedRecorderUtil.Result internetResult =
                            ComponentManager.finishComponentSession(componentSession);
                    componentSession = null;
                    setStatus("Disconnected. " + internetResult.toReadableText());
                } else {
                    setStatus("Disconnected.");
                }
                clearLocalVideo();
                updateCallControls(false);
            }

            @Override
            public void onLocalVideoAvailable(WebStreamVideoTrack track) {
                localVideoView.addTrack(track);
                localPlaceholder.setVisibility(View.GONE);
            }

            @Override
            public void onRemoteVideoAvailable(WebStreamVideoTrack track) {
                remoteVideoView.addTrack(track);
                remotePlaceholder.setVisibility(View.GONE);
                if (componentSession == null) {
                    componentSession = ComponentManager.startWebStreamComponentSession(getApplicationContext());
                }
                if (componentSession != null) {
                    componentSession.startRemoteVideoStorage(remoteVideoView, TARGET_VIDEO_FPS);
                }
                setStatus("Remote video connected.");
            }

            @Override
            public void onRemoteMediaStateChanged(
                    String participantId,
                    boolean microphoneMuted,
                    boolean cameraEnabled) {
                remotePlaceholder.setVisibility(cameraEnabled ? View.GONE : View.VISIBLE);
                if (componentSession != null) {
                    if (cameraEnabled) {
                        componentSession.startRemoteVideoStorage(remoteVideoView, TARGET_VIDEO_FPS);
                    } else {
                        componentSession.stopRemoteVideoStorage();
                    }
                }
                setStatus(participantId
                        + (cameraEnabled ? " camera on" : " camera off")
                        + (microphoneMuted ? ", muted." : ", unmuted."));
            }

            @Override
            public void onRemoteParticipantLeft(String participantId) {
                if (componentSession != null) {
                    componentSession.stopRemoteVideoStorage();
                }
                remoteVideoView.removeTrack();
                remotePlaceholder.setVisibility(View.VISIBLE);
                setStatus(participantId + " left.");
            }

            @Override
            public void onError(Throwable error) {
                ComponentManager.finishComponentSession(componentSession);
                componentSession = null;
                setStatus(error.getMessage());
                clearLocalVideo();
                updateCallControls(false);
            }
        });
    }

    private void leaveLocalCall() {
        leaveExistingCall(true);
    }

    private void leaveExistingCall(boolean showDisconnected) {
        InternetSpeedRecorderUtil.Result internetResult = ComponentManager.finishComponentSession(componentSession);
        componentSession = null;
        if (webStreamCall != null) {
            webStreamCall.leave();
            webStreamCall = null;
        }
        if (webStreamClient != null) {
            webStreamClient.release();
            webStreamClient = null;
        }
        clearLocalVideo();
        updateCallControls(false);
        if (showDisconnected) {
            setStatus("Disconnected. " + internetResult.toReadableText());
        }
    }

    private void toggleMute() {
        if (webStreamCall == null) {
            return;
        }
        muted = !muted;
        webStreamCall.muteMicrophone(muted);
        updateControlLabels();
    }

    private void toggleCamera() {
        if (webStreamCall == null) {
            return;
        }
        cameraEnabled = !cameraEnabled;
        webStreamCall.enableCamera(cameraEnabled);
        if (!cameraEnabled) {
            localPlaceholder.setVisibility(View.VISIBLE);
        }
        updateControlLabels();
        updateCallControls(true);
    }

    private void clearLocalVideo() {
        localVideoView.removeTrack();
        remoteVideoView.removeTrack();
        localPlaceholder.setVisibility(View.VISIBLE);
        remotePlaceholder.setVisibility(View.VISIBLE);
    }

    private void updateCallControls(boolean inCall) {
        joinButton.setEnabled(!inCall);
        leaveButton.setEnabled(inCall);
        muteButton.setEnabled(inCall);
        cameraButton.setEnabled(inCall);
        switchCameraButton.setEnabled(inCall && cameraEnabled);
        setupPanel.setVisibility(inCall ? View.GONE : View.VISIBLE);
        callHeader.setVisibility(inCall ? View.VISIBLE : View.GONE);
        callControls.setVisibility(inCall ? View.VISIBLE : View.GONE);
    }

    private void updateControlLabels() {
        muteButton.setText(muted ? "Unmute" : "Mute");
        cameraButton.setText(cameraEnabled ? "Camera Off" : "Camera On");
    }

    private void setStatus(String message) {
        statusText.setText(message);
    }

    @Override
    protected void onDestroy() {
        leaveExistingCall(false);
        super.onDestroy();
    }
}
