package com.w3n.webstream;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

final class WebStreamPermissionManager {
    interface PermissionCallback {
        void onResult(boolean granted);
    }

    private static PermissionCallback pendingCameraCallback;

    private WebStreamPermissionManager() {
    }

    static void requestCameraPermission(Context context, PermissionCallback callback) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            Log.d(SdkConstants.TAG, "Camera permission already granted.");
            callback.onResult(true);
            return;
        }

        Log.d(SdkConstants.TAG, "Requesting camera permission from SDK.");
        pendingCameraCallback = callback;
        Intent intent = new Intent(context, WebStreamPermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    static void dispatchCameraResult(boolean granted) {
        Log.d(SdkConstants.TAG, "Camera permission result: " + granted);
        PermissionCallback callback = pendingCameraCallback;
        pendingCameraCallback = null;
        if (callback != null) {
            callback.onResult(granted);
        }
    }
}
