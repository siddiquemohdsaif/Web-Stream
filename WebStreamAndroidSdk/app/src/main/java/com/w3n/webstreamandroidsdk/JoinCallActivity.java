package com.w3n.webstreamandroidsdk;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.w3n.webstream.WebStreamCall;
import com.w3n.webstream.WebStreamCallListener;
import com.w3n.webstream.WebStreamClient;
import com.w3n.webstream.WebStreamCallOptions;
import com.w3n.webstream.WebStreamVideoTrack;
import com.w3n.webstream.WebStreamVideoView;

public class JoinCallActivity extends AppCompatActivity {
    private EditText userIdInput;
    private EditText callIdInput;
    private Button joinButton;
    private Button leaveButton;
    private Button muteButton;
    private Button cameraButton;
    private Button switchCameraButton;
    private WebStreamVideoView localVideoView;
    private WebStreamVideoView remoteVideoView;
    private TextView statusText;

    private WebStreamClient webStreamClient;
    private WebStreamCall webStreamCall;
    private boolean microphoneMuted;
    private boolean cameraEnabled = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_join_call);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.joinCallRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    systemBars.left + v.getPaddingLeft(),
                    systemBars.top + v.getPaddingTop(),
                    systemBars.right + v.getPaddingRight(),
                    systemBars.bottom + v.getPaddingBottom());
            return insets;
        });

        userIdInput = findViewById(R.id.userIdInput);
        callIdInput = findViewById(R.id.callIdInput);
        joinButton = findViewById(R.id.joinButton);
        leaveButton = findViewById(R.id.leaveButton);
        muteButton = findViewById(R.id.muteButton);
        cameraButton = findViewById(R.id.cameraButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);
        localVideoView = findViewById(R.id.localVideoView);
        remoteVideoView = findViewById(R.id.remoteVideoView);
        statusText = findViewById(R.id.statusText);

        joinButton.setOnClickListener(v -> joinCall());
        leaveButton.setOnClickListener(v -> leaveCall());
        muteButton.setOnClickListener(v -> toggleMute());
        cameraButton.setOnClickListener(v -> toggleCamera());
        switchCameraButton.setOnClickListener(v -> switchCamera());
    }

    private void joinCall() {
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

        leaveExistingCall();
        microphoneMuted = false;
        cameraEnabled = true;
        updateControlLabels();
        statusText.setText("Starting call...");

        WebStreamCallOptions options = new WebStreamCallOptions.Builder()
                .videoResolution(960, 540)
                .frameRateFps(15)
                .bitrateKbps(1200)
                .imageFormat(WebStreamCallOptions.ImageFormat.H264)
                .build();

        webStreamClient = new WebStreamClient.Builder(this)
                .userId(userId)
                .defaultCallOptions(options)
                .build();


        webStreamCall = webStreamClient.joinCall(callId, new WebStreamCallListener() {
            @Override
            public void onConnecting() {
                statusText.setText("Connecting to webStream server...");
                updateControls(true);
            }

            @Override
            public void onConnected() {
                statusText.setText("Connected.");
                updateControls(true);
            }

            @Override
            public void onDisconnected() {
                statusText.setText("Disconnected.");
                localVideoView.removeTrack();
                remoteVideoView.removeTrack();
                updateControls(false);
            }

            @Override
            public void onLocalVideoAvailable(WebStreamVideoTrack track) {
                localVideoView.addTrack(track);
            }

            @Override
            public void onRemoteVideoAvailable(WebStreamVideoTrack track) {
                remoteVideoView.addTrack(track);
                statusText.setText("Remote video connected.");
            }

            @Override
            public void onRemoteMediaStateChanged(
                    String participantId,
                    boolean microphoneMuted,
                    boolean cameraEnabled) {
                statusText.setText(participantId
                        + (cameraEnabled ? " camera on" : " camera off")
                        + (microphoneMuted ? ", muted." : ", unmuted."));
            }

            @Override
            public void onRemoteParticipantLeft(String participantId) {
                remoteVideoView.removeTrack();
                statusText.setText(participantId + " left.");
            }

            @Override
            public void onError(Throwable error) {
                statusText.setText(error.getMessage());
                localVideoView.removeTrack();
                remoteVideoView.removeTrack();
                updateControls(false);
            }
        });
    }

    private void leaveCall() {
        statusText.setText("Leaving...");
        leaveExistingCall();
        statusText.setText("Disconnected.");
    }

    private void leaveExistingCall() {
        if (webStreamCall != null) {
            webStreamCall.leave();
            webStreamCall = null;
        }
        if (webStreamClient != null) {
            webStreamClient.release();
            webStreamClient = null;
        }
        localVideoView.removeTrack();
        remoteVideoView.removeTrack();
        microphoneMuted = false;
        cameraEnabled = true;
        updateControlLabels();
        updateControls(false);
    }

    private void toggleMute() {
        if (webStreamCall == null) {
            return;
        }
        microphoneMuted = !microphoneMuted;
        webStreamCall.muteMicrophone(microphoneMuted);
        updateControlLabels();
    }

    private void toggleCamera() {
        if (webStreamCall == null) {
            return;
        }
        cameraEnabled = !cameraEnabled;
        webStreamCall.enableCamera(cameraEnabled);
        updateControlLabels();
    }

    private void switchCamera() {
        if (webStreamCall == null) {
            return;
        }
        webStreamCall.switchCamera();
    }

    private void updateControls(boolean inCall) {
        joinButton.setEnabled(!inCall);
        leaveButton.setEnabled(inCall);
        muteButton.setEnabled(inCall);
        cameraButton.setEnabled(inCall);
        switchCameraButton.setEnabled(inCall && cameraEnabled);
        userIdInput.setEnabled(!inCall);
        callIdInput.setEnabled(!inCall);
    }

    private void updateControlLabels() {
        muteButton.setText(microphoneMuted ? "Unmute" : "Mute");
        cameraButton.setText(cameraEnabled ? "Camera Off" : "Camera On");
        if (webStreamCall != null) {
            switchCameraButton.setEnabled(cameraEnabled);
        }
    }

    @Override
    protected void onDestroy() {
        leaveExistingCall();
        super.onDestroy();
    }
}
