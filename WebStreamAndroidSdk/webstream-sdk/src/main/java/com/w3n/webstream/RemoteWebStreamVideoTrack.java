package com.w3n.webstream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;

final class RemoteWebStreamVideoTrack implements WebStreamVideoTrack {
    private final String participantId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread decodeThread = new HandlerThread("webstream-remote-decode");
    private final Handler decodeHandler;

    private WebStreamVideoView attachedView;
    private ImageView imageView;
    private Bitmap latestBitmap;
    private boolean enabled = true;
    private boolean released;

    RemoteWebStreamVideoTrack(String participantId) {
        this.participantId = participantId;
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public String getParticipantId() {
        return participantId;
    }

    @Override
    public void attach(WebStreamVideoView view) {
        if (released) {
            return;
        }
        Log.d(SdkConstants.TAG, "Attaching remote video track. participantId=" + participantId);
        detach(attachedView);
        attachedView = view;
        imageView = new ImageView(view.getContext());
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setAdjustViewBounds(false);
        imageView.setBackgroundColor(0xFF050606);
        view.addView(imageView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (latestBitmap != null) {
            imageView.setImageBitmap(latestBitmap);
        }
        imageView.setVisibility(enabled ? ImageView.VISIBLE : ImageView.INVISIBLE);
    }

    @Override
    public void detach(WebStreamVideoView view) {
        if (view == null || view != attachedView) {
            return;
        }
        Log.d(SdkConstants.TAG, "Detaching remote video track. participantId=" + participantId);
        if (imageView != null) {
            imageView.setImageBitmap(null);
            view.removeView(imageView);
            imageView = null;
        }
        attachedView = null;
    }

    void updateFrame(byte[] jpegData) {
        if (released || !enabled || jpegData == null || jpegData.length == 0) {
            return;
        }
        decodeHandler.post(() -> {
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (bitmap == null) {
                return;
            }
            mainHandler.post(() -> applyBitmap(bitmap));
        });
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (imageView != null) {
            imageView.setVisibility(enabled ? ImageView.VISIBLE : ImageView.INVISIBLE);
        }
        if (!enabled && imageView != null) {
            imageView.setImageBitmap(null);
        } else if (enabled && imageView != null && latestBitmap != null) {
            imageView.setImageBitmap(latestBitmap);
        }
    }

    void release() {
        released = true;
        Log.d(SdkConstants.TAG, "Releasing remote video track. participantId=" + participantId);
        detach(attachedView);
        decodeThread.quitSafely();
        if (latestBitmap != null) {
            latestBitmap.recycle();
            latestBitmap = null;
        }
    }

    private void applyBitmap(Bitmap bitmap) {
        if (released) {
            bitmap.recycle();
            return;
        }
        Bitmap previousBitmap = latestBitmap;
        latestBitmap = bitmap;
        if (imageView != null) {
            imageView.setImageBitmap(bitmap);
        }
        if (previousBitmap != null && previousBitmap != bitmap) {
            previousBitmap.recycle();
        }
    }
}
