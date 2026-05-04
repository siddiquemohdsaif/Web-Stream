package com.w3n.webstream;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * Main entry point for the webStream Android SDK.
 */
public final class WebStreamClient {
    private final Context applicationContext;
    private final String userId;
    private final String displayName;
    private final String authToken;
    private final String serverUrl;
    private final WebStreamCallOptions defaultCallOptions;
    private final OkHttpClient okHttpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<LocalWebStreamCall> activeCalls = Collections.synchronizedList(new ArrayList<>());
    private boolean released;

    private WebStreamClient(Builder builder) {
        this.applicationContext = builder.context.getApplicationContext();
        this.userId = builder.userId;
        this.displayName = builder.displayName;
        this.authToken = builder.authToken;
        this.serverUrl = SdkConstants.INTERNAL_SERVER_URL;
        this.defaultCallOptions = builder.defaultCallOptions;
        this.okHttpClient = new OkHttpClient.Builder().build();
        Log.d(SdkConstants.TAG, "WebStreamClient initialized. userId=" + userId);
    }

    public String getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public WebStreamCall joinCall(String callId, WebStreamCallListener listener) {
        return joinCall(callId, defaultCallOptions, listener);
    }

    public WebStreamCall joinCall(
            String callId,
            WebStreamCallOptions options,
            WebStreamCallListener listener) {
        ensureActive();
        String normalizedCallId = normalize(callId);
        if (TextUtils.isEmpty(normalizedCallId)) {
            throw new IllegalArgumentException("callId is required.");
        }
        WebStreamCallOptions callOptions =
                options == null ? WebStreamCallOptions.defaultOptions() : options;
        Log.d(SdkConstants.TAG, "joinCall requested. callId=" + normalizedCallId);
        LocalWebStreamCall call = new LocalWebStreamCall(
                normalizedCallId,
                applicationContext,
                userId,
                callOptions,
                listener,
                mainHandler,
                okHttpClient,
                serverUrl,
                displayName,
                authToken,
                endedCall -> activeCalls.remove(endedCall));
        activeCalls.add(call);
        call.start();
        return call;
    }

    public void release() {
        if (released) {
            Log.d(SdkConstants.TAG, "release ignored; client already released.");
            return;
        }
        Log.d(SdkConstants.TAG, "Releasing WebStreamClient.");
        released = true;
        List<LocalWebStreamCall> callsToLeave;
        synchronized (activeCalls) {
            callsToLeave = new ArrayList<>(activeCalls);
            activeCalls.clear();
        }
        for (LocalWebStreamCall call : callsToLeave) {
            call.leave();
        }
        okHttpClient.dispatcher().executorService().shutdown();
        okHttpClient.connectionPool().evictAll();
    }

    Context getApplicationContext() {
        return applicationContext;
    }

    String getAuthToken() {
        return authToken;
    }

    boolean isReleased() {
        return released;
    }

    private void ensureActive() {
        if (released) {
            throw new IllegalStateException("WebStreamClient has already been released.");
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }

    public static final class Builder {
        private final Context context;
        private String userId;
        private String displayName;
        private String authToken;
        private WebStreamCallOptions defaultCallOptions = WebStreamCallOptions.defaultOptions();

        public Builder(Context context) {
            if (context == null) {
                throw new IllegalArgumentException("context is required.");
            }
            Log.d(SdkConstants.TAG, "WebStreamClient.Builder created.");
            this.context = context;
        }

        public Builder userId(String userId) {
            this.userId = normalize(userId);
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = normalize(displayName);
            return this;
        }

        public Builder authToken(String authToken) {
            this.authToken = normalize(authToken);
            return this;
        }

        public Builder defaultCallOptions(WebStreamCallOptions options) {
            this.defaultCallOptions =
                    options == null ? WebStreamCallOptions.defaultOptions() : options;
            return this;
        }

        public WebStreamClient build() {
            if (TextUtils.isEmpty(userId)) {
                throw new IllegalArgumentException("userId is required.");
            }
            Log.d(SdkConstants.TAG, "Building WebStreamClient.");
            return new WebStreamClient(this);
        }
    }
}
