package com.w3n.webstreamandroidsdk;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Button joinCallButton = findViewById(R.id.joinCallButton);
        joinCallButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, JoinCallActivity.class)));

        Button cameraRecordingButton = findViewById(R.id.cameraRecordingButton);
        cameraRecordingButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, CameraRecordingActivity.class)));
    }
}
