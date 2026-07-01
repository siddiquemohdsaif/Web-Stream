package com.w3n.test2webstreamandroidsdk;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private Button cameraDeltaView;
    private Button imageNoiseReduction;
    private Button imageNoiseReductionNative;
    private Button vulkanExample;
    private Button yuv420SupportTest;

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
        cameraDeltaView = findViewById(R.id.cameraDelta);
        cameraDeltaView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this,CameraWithDeltaViewActivity.class);
                startActivity(intent);
            }
        });
        imageNoiseReduction = findViewById(R.id.imageNoiseReduction);
        imageNoiseReduction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this,ImageNoiseReductionActivity.class);
                startActivity(intent);
            }
        });
        imageNoiseReductionNative = findViewById(R.id.imageNoiseReductionNative);
        imageNoiseReductionNative.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this,ImageNoiseReductionUsingNativeActivity.class);
                startActivity(intent);
            }
        });
        vulkanExample = findViewById(R.id.vulkanExample);
        vulkanExample.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this,VulkanExampleActivity.class);
                startActivity(intent);
            }
        });
        yuv420SupportTest = findViewById(R.id.yuv420SupportTest);
        yuv420SupportTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this,Yuv420SupportTestActivity.class);
                startActivity(intent);
            }
        });
    }
}
