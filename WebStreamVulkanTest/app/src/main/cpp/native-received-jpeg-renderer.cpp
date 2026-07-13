#include "native-vulkan-renderer.h"

#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "RenderJPEG_X", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RenderJPEG_X", __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_w3n_webstreamvulkantest_ReceivedJpegVulkanView_nativeSetSurface(
        JNIEnv* env,
        jclass,
        jobject surface,
        jobject assetManager) {
    ANativeWindow* window = surface == nullptr
            ? nullptr
            : ANativeWindow_fromSurface(env, surface);
    AAssetManager* assets = assetManager == nullptr
            ? nullptr
            : AAssetManager_fromJava(env, assetManager);

    LOGD("nativeSetSurface surface=%p window=%p assets=%p", surface, window, assets);
    std::string result = vulkanSetWindow(window, assets);
    LOGD("nativeSetSurface result=%s", result.c_str());
    if (window != nullptr) {
        ANativeWindow_release(window);
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_w3n_webstreamvulkantest_ReceivedJpegVulkanView_nativeSubmitYuv420(
        JNIEnv* env,
        jclass,
        jbyteArray yuv420,
        jint width,
        jint height,
        jint rotationDegrees,
        jboolean mirror) {
    if (yuv420 == nullptr || width <= 0 || height <= 0
            || (width % 2) != 0 || (height % 2) != 0) {
        LOGE("nativeSubmitYuv420 rejected invalid input yuv420=%p width=%d height=%d",
             yuv420, width, height);
        return;
    }

    const jsize expected = width * height * 3 / 2;
    const jsize actual = env->GetArrayLength(yuv420);
    if (actual < expected) {
        LOGE("nativeSubmitYuv420 rejected short buffer actual=%d expected=%d",
             actual, expected);
        return;
    }

    jbyte* data = env->GetByteArrayElements(yuv420, nullptr);
    if (data == nullptr) {
        LOGE("nativeSubmitYuv420 GetByteArrayElements returned null");
        return;
    }

    const uint8_t* y = reinterpret_cast<const uint8_t*>(data);
    const uint8_t* u = y + width * height;
    const uint8_t* v = u + (width * height / 4);
    LOGD("nativeSubmitYuv420 width=%d height=%d actual=%d expected=%d y0=%u u0=%u v0=%u rotation=%d mirror=%d",
         width, height, actual, expected, y[0], u[0], v[0], rotationDegrees, mirror == JNI_TRUE);
    vulkanSubmitYuv420(
            y,
            u,
            v,
            width,
            height,
            static_cast<uint16_t>(rotationDegrees),
            mirror == JNI_TRUE);

    env->ReleaseByteArrayElements(yuv420, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_w3n_webstreamvulkantest_ReceivedJpegVulkanView_nativeDestroy(
        JNIEnv*,
        jclass) {
    LOGD("nativeDestroy");
    vulkanDestroy();
}
