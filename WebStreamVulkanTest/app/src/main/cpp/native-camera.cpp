#include <jni.h>
#include <android/log.h>
#include <camera/NdkCameraManager.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include "native-vulkan-renderer.h"
#include "camera_transformation_evaluator.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NativeCamera", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NativeCamera", __VA_ARGS__)

namespace {

class NativeYuvCamera {
public:
    ~NativeYuvCamera() { stop(); }

    std::string start(bool front, int requestedWidth, int requestedHeight, bool renderPreview = true) {
        stop();
        width_ = requestedWidth;
        height_ = requestedHeight;
        useFront_ = front;
        renderPreview_ = renderPreview;
        running_ = true;

        manager_ = ACameraManager_create();
        if (!manager_) return fail("ACameraManager_create failed");

        std::string cameraId;
        std::string cameraList = findCameraId(front, cameraId);
        if (cameraId.empty()) {
            return fail("No " + std::string(front ? "front" : "back") + " camera found. " + cameraList);
        }
        cameraId_ = cameraId;
        chooseSupportedYuvSize(cameraId_, requestedWidth, requestedHeight);

        media_status_t readerStatus = AImageReader_new(
                width_, height_, AIMAGE_FORMAT_YUV_420_888, 4, &reader_);
        if (readerStatus != AMEDIA_OK || !reader_) {
            return fail("AImageReader_new YUV_420_888 failed: " + std::to_string(readerStatus));
        }
        AImageReader_ImageListener listener{};
        listener.context = this;
        listener.onImageAvailable = &NativeYuvCamera::onImageAvailable;
        AImageReader_setImageListener(reader_, &listener);

        media_status_t windowStatus = AImageReader_getWindow(reader_, &readerWindow_);
        if (windowStatus != AMEDIA_OK || !readerWindow_) {
            return fail("AImageReader_getWindow failed: " + std::to_string(windowStatus));
        }

        cameraCallbacks_.context = this;
        cameraCallbacks_.onDisconnected = &NativeYuvCamera::onDisconnected;
        cameraCallbacks_.onError = &NativeYuvCamera::onError;
        camera_status_t openStatus = ACameraManager_openCamera(
                manager_, cameraId_.c_str(), &cameraCallbacks_, &device_);
        if (openStatus != ACAMERA_OK || !device_) {
            return fail("ACameraManager_openCamera failed: " + std::to_string(openStatus));
        }

        camera_status_t outputContainerStatus = ACaptureSessionOutputContainer_create(&outputs_);
        if (outputContainerStatus != ACAMERA_OK || !outputs_) {
            return fail("ACaptureSessionOutputContainer_create failed: " + std::to_string(outputContainerStatus));
        }
        camera_status_t outputStatus = ACaptureSessionOutput_create(readerWindow_, &readerOutput_);
        if (outputStatus != ACAMERA_OK || !readerOutput_) {
            return fail("ACaptureSessionOutput_create failed: " + std::to_string(outputStatus));
        }
        ACaptureSessionOutputContainer_add(outputs_, readerOutput_);

        camera_status_t requestStatus = ACameraDevice_createCaptureRequest(
                device_, TEMPLATE_RECORD, &request_);
        if (requestStatus != ACAMERA_OK || !request_) {
            requestStatus = ACameraDevice_createCaptureRequest(device_, TEMPLATE_PREVIEW, &request_);
        }
        if (requestStatus != ACAMERA_OK || !request_) {
            return fail("ACameraDevice_createCaptureRequest failed: " + std::to_string(requestStatus));
        }

        camera_status_t targetStatus = ACameraOutputTarget_create(readerWindow_, &target_);
        if (targetStatus != ACAMERA_OK || !target_) {
            return fail("ACameraOutputTarget_create failed: " + std::to_string(targetStatus));
        }
        ACaptureRequest_addTarget(request_, target_);

        sessionCallbacks_.context = this;
        sessionCallbacks_.onClosed = &NativeYuvCamera::onSessionClosed;
        sessionCallbacks_.onReady = &NativeYuvCamera::onSessionReady;
        sessionCallbacks_.onActive = &NativeYuvCamera::onSessionActive;
        camera_status_t sessionStatus = ACameraDevice_createCaptureSession(
                device_, outputs_, &sessionCallbacks_, &session_);
        if (sessionStatus != ACAMERA_OK || !session_) {
            return fail("ACameraDevice_createCaptureSession failed: " + std::to_string(sessionStatus));
        }

        camera_status_t repeatStatus = ACameraCaptureSession_setRepeatingRequest(
                session_, nullptr, 1, &request_, nullptr);
        if (repeatStatus != ACAMERA_OK) {
            return fail("ACameraCaptureSession_setRepeatingRequest failed: " + std::to_string(repeatStatus));
        }

        camera_transform_set_camera(sensorOrientation_, front);
        camera_transform_start_logging();

        std::ostringstream out;
        out << "Native NDK camera ON | " << (front ? "front" : "back")
            << " id=" << cameraId_ << " | YUV_420_888 " << width_ << "x" << height_
            << " requested=" << requestedWidth << "x" << requestedHeight;
        LOGI("%s", out.str().c_str());
        return out.str();
    }

    void stop() {
        camera_transform_stop_logging();
        running_ = false;
        {
            std::lock_guard<std::mutex> lock(captureMutex_);
            capturePending_ = false;
            capturedReady_ = false;
        }
        captureCondition_.notify_all();
        if (session_) {
            ACameraCaptureSession_stopRepeating(session_);
            ACameraCaptureSession_close(session_);
            session_ = nullptr;
        }
        if (request_ && target_) {
            ACaptureRequest_removeTarget(request_, target_);
        }
        if (target_) {
            ACameraOutputTarget_free(target_);
            target_ = nullptr;
        }
        if (request_) {
            ACaptureRequest_free(request_);
            request_ = nullptr;
        }
        if (readerOutput_) {
            ACaptureSessionOutput_free(readerOutput_);
            readerOutput_ = nullptr;
        }
        if (outputs_) {
            ACaptureSessionOutputContainer_free(outputs_);
            outputs_ = nullptr;
        }
        if (device_) {
            ACameraDevice_close(device_);
            device_ = nullptr;
        }
        if (reader_) {
            AImageReader_delete(reader_);
            reader_ = nullptr;
            readerWindow_ = nullptr;
        }
        if (manager_) {
            ACameraManager_delete(manager_);
            manager_ = nullptr;
        }
        cameraId_.clear();
    }

    int width() const { return width_; }
    int height() const { return height_; }

    bool captureNextArgb(std::vector<jint>& out, int timeoutMs) {
        std::unique_lock<std::mutex> lock(captureMutex_);
        capturedReady_ = false;
        capturePending_ = true;
        const bool ready = captureCondition_.wait_for(
                lock, std::chrono::milliseconds(timeoutMs), [&] {
                    return capturedReady_ || !capturePending_;
                });
        if (!ready || !capturedReady_) {
            capturePending_ = false;
            return false;
        }
        out = capturedArgb_;
        capturedReady_ = false;
        return !out.empty();
    }

private:
    std::string findCameraId(bool front, std::string& outId) {
        ACameraIdList* list = nullptr;
        camera_status_t status = ACameraManager_getCameraIdList(manager_, &list);
        if (status != ACAMERA_OK || !list) return "camera list unavailable";

        std::ostringstream seen;
        seen << "Seen cameras:";
        for (int i = 0; i < list->numCameras; ++i) {
            const char* id = list->cameraIds[i];
            ACameraMetadata* metadata = nullptr;
            if (ACameraManager_getCameraCharacteristics(manager_, id, &metadata) != ACAMERA_OK || !metadata) {
                continue;
            }
            ACameraMetadata_const_entry facingEntry{};
            int facing = -1;
            if (ACameraMetadata_getConstEntry(metadata, ACAMERA_LENS_FACING, &facingEntry) == ACAMERA_OK &&
                facingEntry.count > 0) {
                facing = facingEntry.data.u8[0];
            }
            seen << " id=" << id << " facing=" << facing;
            const bool matches = front
                    ? facing == ACAMERA_LENS_FACING_FRONT
                    : facing == ACAMERA_LENS_FACING_BACK;
            if (matches && outId.empty()) {
                outId = id;
                ACameraMetadata_const_entry orientationEntry{};
                if (ACameraMetadata_getConstEntry(metadata, ACAMERA_SENSOR_ORIENTATION,
                                                   &orientationEntry) == ACAMERA_OK &&
                    orientationEntry.count > 0) {
                    sensorOrientation_ = orientationEntry.data.i32[0];
                }
            }
            ACameraMetadata_free(metadata);
        }
        ACameraManager_deleteCameraIdList(list);
        return seen.str();
    }


    void chooseSupportedYuvSize(const std::string& cameraId, int requestedWidth, int requestedHeight) {
        ACameraMetadata* metadata = nullptr;
        if (ACameraManager_getCameraCharacteristics(manager_, cameraId.c_str(), &metadata) != ACAMERA_OK || !metadata) {
            width_ = requestedWidth;
            height_ = requestedHeight;
            return;
        }

        ACameraMetadata_const_entry configs{};
        if (ACameraMetadata_getConstEntry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &configs) != ACAMERA_OK ||
            configs.count < 4) {
            ACameraMetadata_free(metadata);
            width_ = requestedWidth;
            height_ = requestedHeight;
            return;
        }

        int exactW = 0;
        int exactH = 0;
        int bestSameAspectW = 0;
        int bestSameAspectH = 0;
        int64_t bestSameAspectArea = -1;
        int bestUnderW = 0;
        int bestUnderH = 0;
        int64_t bestUnderScore = INT64_MAX;
        int bestAnyW = 0;
        int bestAnyH = 0;
        int64_t bestAnyScore = INT64_MAX;
        std::ostringstream sizes;
        sizes << "YUV sizes:";

        auto aspectError = [&](int w, int h) -> int64_t {
            return std::llabs(static_cast<int64_t>(w) * requestedHeight -
                              static_cast<int64_t>(h) * requestedWidth);
        };
        auto isSameAspect = [&](int w, int h) -> bool {
            return aspectError(w, h) == 0;
        };
        auto score = [&](int w, int h) -> int64_t {
            const int64_t area = static_cast<int64_t>(w) * h;
            const int64_t requestedArea = static_cast<int64_t>(requestedWidth) * requestedHeight;
            // Aspect ratio matters first; area closeness is only tie-breaker.
            return aspectError(w, h) * 1000000000LL + std::llabs(area - requestedArea);
        };

        for (uint32_t i = 0; i + 3 < configs.count; i += 4) {
            const int32_t format = configs.data.i32[i + 0];
            const int32_t w = configs.data.i32[i + 1];
            const int32_t h = configs.data.i32[i + 2];
            const int32_t input = configs.data.i32[i + 3];
            if (input != 0 || format != AIMAGE_FORMAT_YUV_420_888 || w <= 0 || h <= 0) continue;
            sizes << ' ' << w << 'x' << h;

            const int64_t area = static_cast<int64_t>(w) * h;
            if (w == requestedWidth && h == requestedHeight) {
                exactW = w;
                exactH = h;
            }
            if (w <= requestedWidth && h <= requestedHeight && isSameAspect(w, h) && area > bestSameAspectArea) {
                bestSameAspectArea = area;
                bestSameAspectW = w;
                bestSameAspectH = h;
            }
            if (w <= requestedWidth && h <= requestedHeight) {
                const int64_t s = score(w, h);
                if (s < bestUnderScore) {
                    bestUnderScore = s;
                    bestUnderW = w;
                    bestUnderH = h;
                }
            }
            const int64_t anyScore = score(w, h);
            if (anyScore < bestAnyScore) {
                bestAnyScore = anyScore;
                bestAnyW = w;
                bestAnyH = h;
            }
        }

        if (exactW > 0) {
            width_ = exactW;
            height_ = exactH;
        } else if (bestSameAspectW > 0) {
            width_ = bestSameAspectW;
            height_ = bestSameAspectH;
        } else if (bestUnderW > 0) {
            width_ = bestUnderW;
            height_ = bestUnderH;
        } else if (bestAnyW > 0) {
            width_ = bestAnyW;
            height_ = bestAnyH;
        } else {
            width_ = requestedWidth;
            height_ = requestedHeight;
        }
        LOGI("Requested YUV %dx%d, selected %dx%d aspect-preserving-first. %s",
             requestedWidth, requestedHeight, width_, height_, sizes.str().c_str());
        ACameraMetadata_free(metadata);
    }
    std::string fail(const std::string& message) {
        LOGE("%s", message.c_str());
        stop();
        return "Error: " + message;
    }

    static void onDisconnected(void* context, ACameraDevice*) {
        static_cast<NativeYuvCamera*>(context)->running_ = false;
        LOGI("Camera disconnected");
    }

    static void onError(void* context, ACameraDevice*, int error) {
        static_cast<NativeYuvCamera*>(context)->running_ = false;
        LOGE("Camera error %d", error);
    }

    static void onSessionClosed(void*, ACameraCaptureSession*) {}
    static void onSessionReady(void*, ACameraCaptureSession*) {}
    static void onSessionActive(void*, ACameraCaptureSession*) {}

    static void onImageAvailable(void* context, AImageReader* reader) {
        auto* self = static_cast<NativeYuvCamera*>(context);
        if (!self || !self->running_) return;
        AImage* image = nullptr;
        media_status_t status = AImageReader_acquireLatestImage(reader, &image);
        if (status != AMEDIA_OK || !image) return;

        int32_t width = 0;
        int32_t height = 0;
        AImage_getWidth(image, &width);
        AImage_getHeight(image, &height);

        if (width == self->width_ && height == self->height_) {
            const int planeW[3] = {width, width / 2, width / 2};
            const int planeH[3] = {height, height / 2, height / 2};
            bool valid = true;
            for (int p = 0; p < 3; ++p) {
                uint8_t* data = nullptr;
                int length = 0, rowStride = 0, pixelStride = 1;
                AImage_getPlaneData(image, p, &data, &length);
                AImage_getPlaneRowStride(image, p, &rowStride);
                AImage_getPlanePixelStride(image, p, &pixelStride);
                if (!data) { valid = false; break; }
                self->planes_[p].resize(static_cast<size_t>(planeW[p]) * planeH[p]);
                for (int row = 0; row < planeH[p]; ++row) {
                    const uint8_t* src = data + static_cast<size_t>(row) * rowStride;
                    uint8_t* dst = self->planes_[p].data() + static_cast<size_t>(row) * planeW[p];
                    if (pixelStride == 1) std::memcpy(dst, src, planeW[p]);
                    else for (int x = 0; x < planeW[p]; ++x) dst[x] = src[x * pixelStride];
                }
            }
            if (valid) {
                CameraTransformation transform = camera_transform_evaluate();
                if (self->renderPreview_) {
                    vulkanSubmitYuv420(self->planes_[0].data(), self->planes_[1].data(),
                                       self->planes_[2].data(), width, height,
                                       transform.rotation, transform.mirror);
                }
                self->captureIfRequested(width, height);
            }
        }
        AImage_delete(image);
    }

    void captureIfRequested(int width, int height) {
        std::lock_guard<std::mutex> lock(captureMutex_);
        if (!capturePending_) return;
        const size_t pixelCount = static_cast<size_t>(width) * height;
        capturedArgb_.assign(pixelCount + 2u, 0);
        capturedArgb_[0] = width;
        capturedArgb_[1] = height;
        const uint8_t* yPlane = planes_[0].data();
        const uint8_t* uPlane = planes_[1].data();
        const uint8_t* vPlane = planes_[2].data();
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                const int yy = yPlane[static_cast<size_t>(y) * width + x] & 0xff;
                const int cb = (uPlane[static_cast<size_t>(y / 2) * (width / 2) + (x / 2)] & 0xff) - 128;
                const int cr = (vPlane[static_cast<size_t>(y / 2) * (width / 2) + (x / 2)] & 0xff) - 128;
                const int r = std::clamp(static_cast<int>(yy + 1.402f * cr + 0.5f), 0, 255);
                const int g = std::clamp(static_cast<int>(yy - 0.344136f * cb - 0.714136f * cr + 0.5f), 0, 255);
                const int b = std::clamp(static_cast<int>(yy + 1.772f * cb + 0.5f), 0, 255);
                capturedArgb_[2u + static_cast<size_t>(y) * width + x] =
                        static_cast<jint>(0xff000000u | (r << 16u) | (g << 8u) | b);
            }
        }
        capturedReady_ = true;
        capturePending_ = false;
        captureCondition_.notify_all();
    }

    bool useFront_ = false;
    bool running_ = false;
    bool renderPreview_ = true;
    int width_ = 1280;
    int height_ = 720;
    int sensorOrientation_ = 0;
    std::string cameraId_;
    std::array<std::vector<uint8_t>, 3> planes_;
    std::mutex captureMutex_;
    std::condition_variable captureCondition_;
    bool capturePending_ = false;
    bool capturedReady_ = false;
    std::vector<jint> capturedArgb_;

    ACameraManager* manager_ = nullptr;
    ACameraDevice* device_ = nullptr;
    AImageReader* reader_ = nullptr;
    ANativeWindow* readerWindow_ = nullptr;
    ACaptureSessionOutputContainer* outputs_ = nullptr;
    ACaptureSessionOutput* readerOutput_ = nullptr;
    ACameraOutputTarget* target_ = nullptr;
    ACaptureRequest* request_ = nullptr;
    ACameraCaptureSession* session_ = nullptr;
    ACameraDevice_StateCallbacks cameraCallbacks_{};
    ACameraCaptureSession_stateCallbacks sessionCallbacks_{};
};

std::mutex gMutex;
std::unique_ptr<NativeYuvCamera> gCamera;

NativeYuvCamera* camera() {
    if (!gCamera) gCamera = std::make_unique<NativeYuvCamera>();
    return gCamera.get();
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_builderx_ogfa_camerapipelinetest_CameraActivity_nativeStart(
        JNIEnv* env, jclass, jboolean front, jint width, jint height) {
    std::lock_guard<std::mutex> lock(gMutex);
    std::string result = camera()->start(front == JNI_TRUE, width, height);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_builderx_ogfa_camerapipelinetest_CameraActivity_nativeStop(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gCamera) gCamera->stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_builderx_ogfa_camerapipelinetest_CameraActivity_nativeSetSurface(
        JNIEnv* env, jclass, jobject surface, jobject assetManager) {
    if (!surface) {
        vulkanDestroy();
        return env->NewStringUTF("Vulkan surface released");
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    AAssetManager* assets = AAssetManager_fromJava(env, assetManager);
    std::string result = vulkanSetWindow(window, assets);
    ANativeWindow_release(window);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_builderx_ogfa_camerapipelinetest_CameraActivity_nativeSetDisplayRotation(
        JNIEnv*, jclass, jint rotationDegrees) {
    camera_transform_set_display_rotation(rotationDegrees);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_builderx_ogfa_camerapipelinetest_LivePreprocessingActivity_nativeStart(
        JNIEnv* env, jclass, jboolean front, jint width, jint height) {
    std::lock_guard<std::mutex> lock(gMutex);
    std::string result = camera()->start(front == JNI_TRUE, width, height);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_builderx_ogfa_camerapipelinetest_LivePreprocessingActivity_nativeStop(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gCamera) gCamera->stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_builderx_ogfa_camerapipelinetest_LivePreprocessingActivity_nativeSetSurface(
        JNIEnv* env, jclass, jobject surface, jobject assetManager) {
    if (!surface) {
        vulkanDestroy();
        return env->NewStringUTF("Vulkan surface released");
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    AAssetManager* assets = AAssetManager_fromJava(env, assetManager);
    std::string result = vulkanSetWindow(window, assets);
    ANativeWindow_release(window);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_builderx_ogfa_camerapipelinetest_LivePreprocessingActivity_nativeSetDisplayRotation(
        JNIEnv*, jclass, jint rotationDegrees) {
    camera_transform_set_display_rotation(rotationDegrees);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_builderx_ogfa_camerapipelinetest_LivePreprocessingActivity_nativeCaptureNextFrameArgb(
        JNIEnv* env, jclass, jint timeoutMs) {
    NativeYuvCamera* activeCamera = nullptr;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        activeCamera = camera();
    }
    std::vector<jint> frame;
    if (!activeCamera->captureNextArgb(frame, std::max(1, static_cast<int>(timeoutMs)))) {
        return nullptr;
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(frame.size()));
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(frame.size()), frame.data());
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_w3n_webstreamvulkantest_JoinCallActivity_nativeStartCameraCapture(
        JNIEnv* env, jclass, jboolean front, jint width, jint height) {
    std::lock_guard<std::mutex> lock(gMutex);
    std::string result = camera()->start(front == JNI_TRUE, width, height, false);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_w3n_webstreamvulkantest_JoinCallActivity_nativeStopCameraCapture(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gCamera) gCamera->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_w3n_webstreamvulkantest_JoinCallActivity_nativeSetCameraDisplayRotation(
        JNIEnv*, jclass, jint rotationDegrees) {
    camera_transform_set_display_rotation(rotationDegrees);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_w3n_webstreamvulkantest_JoinCallActivity_nativeCaptureNextFrameArgb(
        JNIEnv* env, jclass, jint timeoutMs) {
    NativeYuvCamera* activeCamera = nullptr;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        activeCamera = camera();
    }
    std::vector<jint> frame;
    if (!activeCamera->captureNextArgb(frame, std::max(1, static_cast<int>(timeoutMs)))) {
        return nullptr;
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(frame.size()));
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(frame.size()), frame.data());
    return result;
}
