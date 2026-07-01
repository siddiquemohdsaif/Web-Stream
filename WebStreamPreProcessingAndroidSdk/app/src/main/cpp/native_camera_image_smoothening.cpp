#include <android/log.h>
#include <android/native_window_jni.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <jni.h>
#include <limits>
#include <mutex>
#include <vector>

#define LOG_TAG "NativeCameraImageSmooth"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM* g_vm = nullptr;
jobject g_activity = nullptr;
jmethodID g_on_native_frame = nullptr;
jmethodID g_on_native_captured_preview = nullptr;
jmethodID g_on_native_smoothening_complete = nullptr;

std::mutex g_camera_mutex;
ACameraManager* g_camera_manager = nullptr;
ACameraDevice* g_camera_device = nullptr;
ACameraCaptureSession* g_capture_session = nullptr;
ACaptureRequest* g_capture_request = nullptr;
ACameraOutputTarget* g_preview_target = nullptr;
ACameraOutputTarget* g_image_target = nullptr;
ACaptureSessionOutput* g_preview_output = nullptr;
ACaptureSessionOutput* g_image_output = nullptr;
ACaptureSessionOutputContainer* g_output_container = nullptr;
AImageReader* g_image_reader = nullptr;
ANativeWindow* g_preview_window = nullptr;
ANativeWindow* g_image_window = nullptr;
std::atomic<bool> g_capture_next_frame(false);
std::atomic<long> g_frame_count(0);
std::mutex g_captured_frame_mutex;
std::vector<uint8_t> g_latest_captured_yuv420;
int g_latest_captured_width = 0;
int g_latest_captured_height = 0;

struct CameraSize {
    int width = 0;
    int height = 0;
};

JNIEnv* GetEnv() {
    JNIEnv* env = nullptr;
    if (g_vm == nullptr) {
        return nullptr;
    }

    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }

    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return nullptr;
    }

    return env;
}

jbyteArray NewByteArray(JNIEnv* env, const std::vector<uint8_t>& data) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
    if (result == nullptr) {
        return nullptr;
    }

    env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(data.size()),
            reinterpret_cast<const jbyte*>(data.data()));
    return result;
}

jintArray NewIntArray(JNIEnv* env, const std::vector<int32_t>& data) {
    jintArray result = env->NewIntArray(static_cast<jsize>(data.size()));
    if (result == nullptr) {
        return nullptr;
    }

    env->SetIntArrayRegion(
            result,
            0,
            static_cast<jsize>(data.size()),
            reinterpret_cast<const jint*>(data.data()));
    return result;
}

jlongArray NewLongArray(JNIEnv* env, const std::vector<int64_t>& data) {
    jlongArray result = env->NewLongArray(static_cast<jsize>(data.size()));
    if (result == nullptr) {
        return nullptr;
    }

    env->SetLongArrayRegion(
            result,
            0,
            static_cast<jsize>(data.size()),
            reinterpret_cast<const jlong*>(data.data()));
    return result;
}

int64_t ElapsedNs(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - start).count();
}

int ClipToByte(int value) {
    return std::max(0, std::min(255, value));
}

void CopyPlane(
        const uint8_t* source,
        int rowStride,
        int pixelStride,
        int width,
        int height,
        std::vector<uint8_t>& output,
        int outputOffset,
        int outputRowStride) {
    for (int y = 0; y < height; ++y) {
        const int sourceRow = y * rowStride;
        const int outputRow = outputOffset + (y * outputRowStride);
        for (int x = 0; x < width; ++x) {
            output[outputRow + x] = source[sourceRow + (x * pixelStride)];
        }
    }
}

void ResizePlaneNearest(
        const uint8_t* source,
        int sourceWidth,
        int sourceHeight,
        uint8_t* target,
        int targetWidth,
        int targetHeight) {
    for (int y = 0; y < targetHeight; ++y) {
        const int sourceY = (y * sourceHeight) / targetHeight;
        for (int x = 0; x < targetWidth; ++x) {
            const int sourceX = (x * sourceWidth) / targetWidth;
            target[(y * targetWidth) + x] = source[(sourceY * sourceWidth) + sourceX];
        }
    }
}

std::vector<uint8_t> ResizeYuv420(
        const std::vector<uint8_t>& source,
        int sourceWidth,
        int sourceHeight,
        int targetWidth,
        int targetHeight) {
    const int sourceYSize = sourceWidth * sourceHeight;
    const int sourceChromaWidth = sourceWidth / 2;
    const int sourceChromaHeight = sourceHeight / 2;
    const int sourceChromaSize = sourceChromaWidth * sourceChromaHeight;
    const int targetYSize = targetWidth * targetHeight;
    const int targetChromaWidth = targetWidth / 2;
    const int targetChromaHeight = targetHeight / 2;
    const int targetChromaSize = targetChromaWidth * targetChromaHeight;
    std::vector<uint8_t> resized(targetYSize + (targetChromaSize * 2), 0);

    ResizePlaneNearest(
            source.data(),
            sourceWidth,
            sourceHeight,
            resized.data(),
            targetWidth,
            targetHeight);
    ResizePlaneNearest(
            source.data() + sourceYSize,
            sourceChromaWidth,
            sourceChromaHeight,
            resized.data() + targetYSize,
            targetChromaWidth,
            targetChromaHeight);
    ResizePlaneNearest(
            source.data() + sourceYSize + sourceChromaSize,
            sourceChromaWidth,
            sourceChromaHeight,
            resized.data() + targetYSize + targetChromaSize,
            targetChromaWidth,
            targetChromaHeight);

    return resized;
}

std::vector<int32_t> Yuv420ToArgb(const std::vector<uint8_t>& yuv420, int width, int height) {
    const int ySize = width * height;
    const int chromaSize = ySize / 4;
    const int uOffset = ySize;
    const int vOffset = ySize + chromaSize;
    std::vector<int32_t> argbPixels(ySize);

    for (int y = 0; y < height; ++y) {
        const int yRowOffset = y * width;
        const int chromaRowOffset = (y / 2) * (width / 2);
        for (int x = 0; x < width; ++x) {
            const int yValue = yuv420[yRowOffset + x];
            const int chromaIndex = chromaRowOffset + (x / 2);
            const int uValue = yuv420[uOffset + chromaIndex] - 128;
            const int vValue = yuv420[vOffset + chromaIndex] - 128;
            const int red = ClipToByte(static_cast<int>(yValue + (1.402f * vValue)));
            const int green = ClipToByte(static_cast<int>(
                    yValue - (0.344136f * uValue) - (0.714136f * vValue)));
            const int blue = ClipToByte(static_cast<int>(yValue + (1.772f * uValue)));
            argbPixels[yRowOffset + x] = static_cast<int32_t>(
                    0xFF000000u | (red << 16) | (green << 8) | blue);
        }
    }

    return argbPixels;
}

void SortNine(uint8_t* values) {
    for (int i = 1; i < 9; ++i) {
        uint8_t value = values[i];
        int j = i - 1;
        while (j >= 0 && values[j] > value) {
            values[j + 1] = values[j];
            --j;
        }
        values[j + 1] = value;
    }
}

uint8_t Median3x3(const uint8_t* data, int offset, int width, int x, int y) {
    uint8_t values[9];
    int valueIndex = 0;
    for (int dy = -1; dy <= 1; ++dy) {
        const int rowOffset = offset + ((y + dy) * width);
        for (int dx = -1; dx <= 1; ++dx) {
            values[valueIndex++] = data[rowOffset + x + dx];
        }
    }
    SortNine(values);
    return values[4];
}

std::vector<uint8_t> CreatePlaneEdgeMask(
        const uint8_t* yuv420,
        int offset,
        int width,
        int height,
        int edgeThreshold) {
    std::vector<uint8_t> edgeMask(width * height, 0);
    for (int y = 1; y < height - 1; ++y) {
        const int rowOffset = y * width;
        for (int x = 1; x < width - 1; ++x) {
            const int center = yuv420[offset + rowOffset + x];
            bool isEdge = false;
            for (int dy = -1; dy <= 1 && !isEdge; ++dy) {
                const int neighborRowOffset = (y + dy) * width;
                for (int dx = -1; dx <= 1; ++dx) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    const int neighbor = yuv420[offset + neighborRowOffset + x + dx];
                    if (std::abs(center - neighbor) >= edgeThreshold) {
                        isEdge = true;
                        break;
                    }
                }
            }
            edgeMask[rowOffset + x] = isEdge ? 255 : 0;
        }
    }
    return edgeMask;
}

void ApplyMedianFilterPlane(
        const uint8_t* source,
        const uint8_t* edgeMask,
        int sourceOffset,
        int targetOffset,
        int width,
        int height,
        std::vector<uint8_t>& target) {
    for (int y = 1; y < height - 1; ++y) {
        const int rowOffset = y * width;
        for (int x = 1; x < width - 1; ++x) {
            const int index = rowOffset + x;
            if (edgeMask[index] == 0) {
                target[targetOffset + index] = Median3x3(source, sourceOffset, width, x, y);
            }
        }
    }
}

std::vector<int32_t> EdgeMaskToArgb(const std::vector<uint8_t>& edgeMask) {
    std::vector<int32_t> argbPixels(edgeMask.size());
    for (size_t i = 0; i < edgeMask.size(); ++i) {
        const int value = edgeMask[i];
        argbPixels[i] = static_cast<int32_t>(
                0xFF000000u | (value << 16) | (value << 8) | value);
    }
    return argbPixels;
}

bool ImageToPlanarYuv420(AImage* image, std::vector<uint8_t>& yuv420, int& width, int& height) {
    int32_t imageWidth = 0;
    int32_t imageHeight = 0;
    AImage_getWidth(image, &imageWidth);
    AImage_getHeight(image, &imageHeight);
    width = imageWidth & ~1;
    height = imageHeight & ~1;
    if (width <= 0 || height <= 0) {
        return false;
    }

    const int ySize = width * height;
    const int chromaWidth = width / 2;
    const int chromaHeight = height / 2;
    const int chromaSize = chromaWidth * chromaHeight;
    yuv420.assign(ySize + (chromaSize * 2), 0);

    for (int plane = 0; plane < 3; ++plane) {
        uint8_t* data = nullptr;
        int dataLength = 0;
        int rowStride = 0;
        int pixelStride = 0;
        if (AImage_getPlaneData(image, plane, &data, &dataLength) != AMEDIA_OK
                || AImage_getPlaneRowStride(image, plane, &rowStride) != AMEDIA_OK
                || AImage_getPlanePixelStride(image, plane, &pixelStride) != AMEDIA_OK
                || data == nullptr) {
            LOGE("Could not read image plane %d", plane);
            return false;
        }

        if (plane == 0) {
            CopyPlane(data, rowStride, pixelStride, width, height, yuv420, 0, width);
        } else if (plane == 1) {
            CopyPlane(data, rowStride, pixelStride, chromaWidth, chromaHeight, yuv420, ySize, chromaWidth);
        } else {
            CopyPlane(data, rowStride, pixelStride, chromaWidth, chromaHeight, yuv420, ySize + chromaSize, chromaWidth);
        }
    }

    return true;
}

void RotateAntiClockwiseAndFlipHorizontalPlane(
        const uint8_t* source,
        int sourceWidth,
        int sourceHeight,
        uint8_t* target) {
    const int targetWidth = sourceHeight;
    for (int sourceY = 0; sourceY < sourceHeight; ++sourceY) {
        for (int sourceX = 0; sourceX < sourceWidth; ++sourceX) {
            const int rotatedX = sourceY;
            const int rotatedY = sourceWidth - 1 - sourceX;
            const int targetX = targetWidth - 1 - rotatedX;
            target[(rotatedY * targetWidth) + targetX] = source[(sourceY * sourceWidth) + sourceX];
        }
    }
}

std::vector<uint8_t> RotateAntiClockwiseAndFlipYuv420(
        const std::vector<uint8_t>& source,
        int sourceWidth,
        int sourceHeight,
        int& targetWidth,
        int& targetHeight) {
    targetWidth = sourceHeight;
    targetHeight = sourceWidth;
    const int sourceYSize = sourceWidth * sourceHeight;
    const int sourceChromaWidth = sourceWidth / 2;
    const int sourceChromaHeight = sourceHeight / 2;
    const int sourceChromaSize = sourceChromaWidth * sourceChromaHeight;
    const int targetYSize = targetWidth * targetHeight;
    const int targetChromaWidth = targetWidth / 2;
    const int targetChromaHeight = targetHeight / 2;
    const int targetChromaSize = targetChromaWidth * targetChromaHeight;
    std::vector<uint8_t> transformed(targetYSize + (targetChromaSize * 2), 0);

    RotateAntiClockwiseAndFlipHorizontalPlane(
            source.data(),
            sourceWidth,
            sourceHeight,
            transformed.data());
    RotateAntiClockwiseAndFlipHorizontalPlane(
            source.data() + sourceYSize,
            sourceChromaWidth,
            sourceChromaHeight,
            transformed.data() + targetYSize);
    RotateAntiClockwiseAndFlipHorizontalPlane(
            source.data() + sourceYSize + sourceChromaSize,
            sourceChromaWidth,
            sourceChromaHeight,
            transformed.data() + targetYSize + targetChromaSize);

    return transformed;
}

void NotifyFrame(int width, int height, int format, long frameCount) {
    JNIEnv* env = GetEnv();
    if (env == nullptr || g_activity == nullptr || g_on_native_frame == nullptr) {
        return;
    }

    env->CallVoidMethod(
            g_activity,
            g_on_native_frame,
            width,
            height,
            format,
            static_cast<jlong>(frameCount));
}

void NotifyCapturedFrame(const std::vector<uint8_t>& yuv420, int width, int height) {
    {
        std::lock_guard<std::mutex> lock(g_captured_frame_mutex);
        g_latest_captured_yuv420 = yuv420;
        g_latest_captured_width = width;
        g_latest_captured_height = height;
    }

    JNIEnv* env = GetEnv();
    if (env == nullptr || g_activity == nullptr || g_on_native_captured_preview == nullptr) {
        return;
    }

    std::vector<int32_t> previewPixels = Yuv420ToArgb(yuv420, width, height);
    jintArray pixels = NewIntArray(env, previewPixels);
    if (pixels == nullptr) {
        return;
    }

    env->CallVoidMethod(
            g_activity,
            g_on_native_captured_preview,
            pixels,
            width,
            height);
    env->DeleteLocalRef(pixels);
}

bool NotifySmootheningComplete(
        const std::vector<int32_t>& beforePixels,
        const std::vector<int32_t>& edgePixels,
        const std::vector<int32_t>& uEdgePixels,
        const std::vector<int32_t>& vEdgePixels,
        const std::vector<int32_t>& noiseReducedPixels,
        int width,
        int height,
        const std::vector<int64_t>& timings,
        int yuv420ByteCount) {
    JNIEnv* env = GetEnv();
    if (env == nullptr || g_activity == nullptr || g_on_native_smoothening_complete == nullptr) {
        return false;
    }

    jclass intArrayClass = env->FindClass("[I");
    if (intArrayClass == nullptr) {
        return false;
    }

    jobjectArray imageArrays = env->NewObjectArray(5, intArrayClass, nullptr);
    if (imageArrays == nullptr) {
        return false;
    }

    jintArray beforeArray = NewIntArray(env, beforePixels);
    jintArray edgeArray = NewIntArray(env, edgePixels);
    jintArray uEdgeArray = NewIntArray(env, uEdgePixels);
    jintArray vEdgeArray = NewIntArray(env, vEdgePixels);
    jintArray noiseArray = NewIntArray(env, noiseReducedPixels);
    if (beforeArray == nullptr || edgeArray == nullptr || uEdgeArray == nullptr
            || vEdgeArray == nullptr || noiseArray == nullptr) {
        return false;
    }

    env->SetObjectArrayElement(imageArrays, 0, beforeArray);
    env->SetObjectArrayElement(imageArrays, 1, edgeArray);
    env->SetObjectArrayElement(imageArrays, 2, uEdgeArray);
    env->SetObjectArrayElement(imageArrays, 3, vEdgeArray);
    env->SetObjectArrayElement(imageArrays, 4, noiseArray);

    std::vector<int32_t> widths = {width, width, width / 2, width / 2, width};
    std::vector<int32_t> heights = {height, height, height / 2, height / 2, height};
    jintArray widthArray = NewIntArray(env, widths);
    jintArray heightArray = NewIntArray(env, heights);
    jlongArray timingArray = NewLongArray(env, timings);
    if (widthArray == nullptr || heightArray == nullptr || timingArray == nullptr) {
        return false;
    }

    env->CallVoidMethod(
            g_activity,
            g_on_native_smoothening_complete,
            imageArrays,
            widthArray,
            heightArray,
            timingArray,
            yuv420ByteCount);

    env->DeleteLocalRef(beforeArray);
    env->DeleteLocalRef(edgeArray);
    env->DeleteLocalRef(uEdgeArray);
    env->DeleteLocalRef(vEdgeArray);
    env->DeleteLocalRef(noiseArray);
    env->DeleteLocalRef(widthArray);
    env->DeleteLocalRef(heightArray);
    env->DeleteLocalRef(timingArray);
    env->DeleteLocalRef(imageArrays);
    return true;
}

bool ProcessCapturedFrame(int targetWidth, int targetHeight, int edgeThreshold) {
    std::unique_lock<std::mutex> lock(g_captured_frame_mutex);
    const int capturedWidth = g_latest_captured_width;
    const int capturedHeight = g_latest_captured_height;

    if (g_latest_captured_yuv420.empty() || capturedWidth <= 0 || capturedHeight <= 0
            || targetWidth <= 0 || targetHeight <= 0
            || (targetWidth & 1) != 0 || (targetHeight & 1) != 0) {
        LOGE("ProcessCapturedFrame: invalid input");
        return false;
    }

    const bool capturedIsPortrait = capturedHeight > capturedWidth;
    const bool targetIsPortrait = targetHeight > targetWidth;
    if (capturedIsPortrait != targetIsPortrait) {
        std::swap(targetWidth, targetHeight);
    }

    std::vector<int64_t> timings(6, 0);
    auto start = std::chrono::steady_clock::now();
    std::vector<uint8_t> yuv420 = ResizeYuv420(
            g_latest_captured_yuv420,
            capturedWidth,
            capturedHeight,
            targetWidth,
            targetHeight);
    timings[0] = ElapsedNs(start);

    start = std::chrono::steady_clock::now();
    std::vector<int32_t> beforePixels = Yuv420ToArgb(yuv420, targetWidth, targetHeight);
    timings[1] = ElapsedNs(start);

    const int ySize = targetWidth * targetHeight;
    const int chromaWidth = targetWidth / 2;
    const int chromaHeight = targetHeight / 2;
    const int chromaSize = chromaWidth * chromaHeight;
    const int uOffset = ySize;
    const int vOffset = ySize + chromaSize;

    start = std::chrono::steady_clock::now();
    std::vector<uint8_t> yMask = CreatePlaneEdgeMask(
            yuv420.data(),
            0,
            targetWidth,
            targetHeight,
            edgeThreshold);
    std::vector<uint8_t> uMask = CreatePlaneEdgeMask(
            yuv420.data(),
            uOffset,
            chromaWidth,
            chromaHeight,
            edgeThreshold);
    std::vector<uint8_t> vMask = CreatePlaneEdgeMask(
            yuv420.data(),
            vOffset,
            chromaWidth,
            chromaHeight,
            edgeThreshold);
    timings[2] = ElapsedNs(start);

    start = std::chrono::steady_clock::now();
    std::vector<uint8_t> smoothedYuv420(yuv420);
    ApplyMedianFilterPlane(yuv420.data(), yMask.data(), 0, 0, targetWidth, targetHeight, smoothedYuv420);
    ApplyMedianFilterPlane(yuv420.data(), uMask.data(), uOffset, uOffset, chromaWidth, chromaHeight, smoothedYuv420);
    ApplyMedianFilterPlane(yuv420.data(), vMask.data(), vOffset, vOffset, chromaWidth, chromaHeight, smoothedYuv420);
    timings[3] = ElapsedNs(start);

    start = std::chrono::steady_clock::now();
    std::vector<int32_t> edgePixels = EdgeMaskToArgb(yMask);
    std::vector<int32_t> uEdgePixels = EdgeMaskToArgb(uMask);
    std::vector<int32_t> vEdgePixels = EdgeMaskToArgb(vMask);
    timings[4] = ElapsedNs(start);

    start = std::chrono::steady_clock::now();
    std::vector<int32_t> noiseReducedPixels = Yuv420ToArgb(
            smoothedYuv420,
            targetWidth,
            targetHeight);
    timings[5] = ElapsedNs(start);

    lock.unlock();
    return NotifySmootheningComplete(
            beforePixels,
            edgePixels,
            uEdgePixels,
            vEdgePixels,
            noiseReducedPixels,
            targetWidth,
            targetHeight,
            timings,
            static_cast<int>(yuv420.size()));
}

void OnImageAvailable(void*, AImageReader* reader) {
    AImage* image = nullptr;
    media_status_t status = AImageReader_acquireLatestImage(reader, &image);
    if (status != AMEDIA_OK || image == nullptr) {
        return;
    }

    int32_t width = 0;
    int32_t height = 0;
    int32_t format = 0;
    AImage_getWidth(image, &width);
    AImage_getHeight(image, &height);
    AImage_getFormat(image, &format);
    const long frameCount = ++g_frame_count;
    if (frameCount == 1 || (frameCount % 15) == 0) {
        NotifyFrame(width, height, format, frameCount);
    }

    if (g_capture_next_frame.exchange(false)) {
        std::vector<uint8_t> yuv420;
        int yuvWidth = 0;
        int yuvHeight = 0;
        if (ImageToPlanarYuv420(image, yuv420, yuvWidth, yuvHeight)) {
            int transformedWidth = 0;
            int transformedHeight = 0;
            std::vector<uint8_t> transformed = RotateAntiClockwiseAndFlipYuv420(
                    yuv420,
                    yuvWidth,
                    yuvHeight,
                    transformedWidth,
                    transformedHeight);
            NotifyCapturedFrame(transformed, transformedWidth, transformedHeight);
        }
    }

    AImage_delete(image);
}

void CloseNativeCameraLocked() {
    if (g_capture_session != nullptr) {
        ACameraCaptureSession_stopRepeating(g_capture_session);
        ACameraCaptureSession_close(g_capture_session);
        g_capture_session = nullptr;
    }
    if (g_capture_request != nullptr) {
        ACaptureRequest_free(g_capture_request);
        g_capture_request = nullptr;
    }
    if (g_preview_target != nullptr) {
        ACameraOutputTarget_free(g_preview_target);
        g_preview_target = nullptr;
    }
    if (g_image_target != nullptr) {
        ACameraOutputTarget_free(g_image_target);
        g_image_target = nullptr;
    }
    if (g_output_container != nullptr) {
        ACaptureSessionOutputContainer_free(g_output_container);
        g_output_container = nullptr;
    }
    if (g_preview_output != nullptr) {
        ACaptureSessionOutput_free(g_preview_output);
        g_preview_output = nullptr;
    }
    if (g_image_output != nullptr) {
        ACaptureSessionOutput_free(g_image_output);
        g_image_output = nullptr;
    }
    if (g_camera_device != nullptr) {
        ACameraDevice_close(g_camera_device);
        g_camera_device = nullptr;
    }
    if (g_image_reader != nullptr) {
        AImageReader_delete(g_image_reader);
        g_image_reader = nullptr;
    }
    if (g_camera_manager != nullptr) {
        ACameraManager_delete(g_camera_manager);
        g_camera_manager = nullptr;
    }
    if (g_preview_window != nullptr) {
        ANativeWindow_release(g_preview_window);
        g_preview_window = nullptr;
    }
    g_image_window = nullptr;
    g_capture_next_frame = false;
}

void ClearCapturedFrame() {
    {
        std::lock_guard<std::mutex> capturedLock(g_captured_frame_mutex);
        g_latest_captured_yuv420.clear();
        g_latest_captured_width = 0;
        g_latest_captured_height = 0;
    }
}

void CloseNativeCamera() {
    std::lock_guard<std::mutex> lock(g_camera_mutex);
    CloseNativeCameraLocked();
}

void OnCameraDisconnected(void*, ACameraDevice*) {
    CloseNativeCamera();
}

void OnCameraError(void*, ACameraDevice*, int error) {
    LOGE("Camera error: %d", error);
    CloseNativeCamera();
}

void OnSessionClosed(void*, ACameraCaptureSession*) {
}

void OnSessionReady(void*, ACameraCaptureSession*) {
}

void OnSessionActive(void*, ACameraCaptureSession*) {
}

bool CreateCaptureSessionLocked() {
    if (g_camera_device == nullptr || g_preview_window == nullptr || g_image_window == nullptr) {
        return false;
    }

    camera_status_t status = ACameraDevice_createCaptureRequest(
            g_camera_device,
            TEMPLATE_PREVIEW,
            &g_capture_request);
    if (status != ACAMERA_OK) {
        LOGE("createCaptureRequest failed: %d", status);
        return false;
    }

    status = ACameraOutputTarget_create(g_preview_window, &g_preview_target);
    if (status != ACAMERA_OK) {
        LOGE("preview target failed: %d", status);
        return false;
    }
    status = ACameraOutputTarget_create(g_image_window, &g_image_target);
    if (status != ACAMERA_OK) {
        LOGE("image target failed: %d", status);
        return false;
    }

    ACaptureRequest_addTarget(g_capture_request, g_preview_target);
    ACaptureRequest_addTarget(g_capture_request, g_image_target);

    ACaptureSessionOutputContainer_create(&g_output_container);
    ACaptureSessionOutput_create(g_preview_window, &g_preview_output);
    ACaptureSessionOutput_create(g_image_window, &g_image_output);
    ACaptureSessionOutputContainer_add(g_output_container, g_preview_output);
    ACaptureSessionOutputContainer_add(g_output_container, g_image_output);

    ACameraCaptureSession_stateCallbacks sessionCallbacks{};
    sessionCallbacks.context = nullptr;
    sessionCallbacks.onClosed = OnSessionClosed;
    sessionCallbacks.onReady = OnSessionReady;
    sessionCallbacks.onActive = OnSessionActive;

    status = ACameraDevice_createCaptureSession(
            g_camera_device,
            g_output_container,
            &sessionCallbacks,
            &g_capture_session);
    if (status != ACAMERA_OK) {
        LOGE("createCaptureSession failed: %d", status);
        return false;
    }

    int sequenceId = 0;
    status = ACameraCaptureSession_setRepeatingRequest(
            g_capture_session,
            nullptr,
            1,
            &g_capture_request,
            &sequenceId);
    if (status != ACAMERA_OK) {
        LOGE("setRepeatingRequest failed: %d", status);
        return false;
    }

    return true;
}

const char* FindCameraId(ACameraManager* manager, ACameraIdList* cameraIds, int desiredLensFacing) {
    for (int i = 0; i < cameraIds->numCameras; i++) {
        const char* id = cameraIds->cameraIds[i];
        ACameraMetadata* metadata = nullptr;
        if (ACameraManager_getCameraCharacteristics(manager, id, &metadata) != ACAMERA_OK) {
            continue;
        }

        ACameraMetadata_const_entry lensFacing{};
        bool isTargetCamera = false;
        if (ACameraMetadata_getConstEntry(metadata, ACAMERA_LENS_FACING, &lensFacing) == ACAMERA_OK
                && lensFacing.count > 0) {
            isTargetCamera = lensFacing.data.u8[0] == desiredLensFacing;
        }
        ACameraMetadata_free(metadata);

        if (isTargetCamera) {
            return id;
        }
    }

    return cameraIds->numCameras > 0 ? cameraIds->cameraIds[0] : nullptr;
}

CameraSize ChooseYuvSize(ACameraManager* manager, const char* cameraId, int targetWidth, int targetHeight) {
    CameraSize selected{targetWidth, targetHeight};
    ACameraMetadata* metadata = nullptr;
    if (ACameraManager_getCameraCharacteristics(manager, cameraId, &metadata) != ACAMERA_OK
            || metadata == nullptr) {
        return selected;
    }

    ACameraMetadata_const_entry configurations{};
    if (ACameraMetadata_getConstEntry(
            metadata,
            ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
            &configurations) != ACAMERA_OK
            || configurations.count < 4) {
        ACameraMetadata_free(metadata);
        return selected;
    }

    const int64_t targetPixels = static_cast<int64_t>(targetWidth) * targetHeight;
    const float targetAspectRatio = static_cast<float>(targetWidth) / targetHeight;
    int64_t bestScore = std::numeric_limits<int64_t>::max();

    for (uint32_t i = 0; i + 3 < configurations.count; i += 4) {
        const int32_t format = configurations.data.i32[i];
        const int32_t width = configurations.data.i32[i + 1];
        const int32_t height = configurations.data.i32[i + 2];
        const int32_t input = configurations.data.i32[i + 3];
        if (input != 0 || format != AIMAGE_FORMAT_YUV_420_888 || width <= 0 || height <= 0) {
            continue;
        }

        const int64_t pixels = static_cast<int64_t>(width) * height;
        const float aspectRatio = static_cast<float>(width) / height;
        const int64_t pixelScore = std::llabs(pixels - targetPixels);
        const int64_t heightScore = std::llabs(static_cast<int64_t>(height) - targetHeight);
        const int64_t widthScore = std::llabs(static_cast<int64_t>(width) - targetWidth);
        const int64_t aspectScore = static_cast<int64_t>(
                std::fabs(aspectRatio - targetAspectRatio) * 10000.0f);
        const int64_t score = (pixelScore * 100000L)
                + (heightScore * 1000L)
                + (widthScore * 10L)
                + aspectScore;
        if (score < bestScore) {
            selected.width = width & ~1;
            selected.height = height & ~1;
            bestScore = score;
        }
    }

    ACameraMetadata_free(metadata);
    LOGD("ChooseYuvSize: target=%dx%d selected=%dx%d",
         targetWidth, targetHeight, selected.width, selected.height);
    return selected;
}

}

extern "C" JNIEXPORT void JNICALL
Java_com_w3n_webstreampreprocessingandroidsdk_CameraImageSmootheningUsingNativeActivity_nativeInitController(
        JNIEnv* env,
        jobject thiz) {
    env->GetJavaVM(&g_vm);
    if (g_activity != nullptr) {
        env->DeleteGlobalRef(g_activity);
    }
    g_activity = env->NewGlobalRef(thiz);
    jclass activityClass = env->GetObjectClass(thiz);
    g_on_native_frame = env->GetMethodID(activityClass, "onNativeFrame", "(IIIJ)V");
    g_on_native_captured_preview = env->GetMethodID(
            activityClass,
            "onNativeCapturedPreview",
            "([III)V");
    g_on_native_smoothening_complete = env->GetMethodID(
            activityClass,
            "onNativeSmootheningComplete",
            "([[I[I[I[JI)V");
}

extern "C" JNIEXPORT void JNICALL
Java_com_w3n_webstreampreprocessingandroidsdk_CameraImageSmootheningUsingNativeActivity_nativeReleaseController(
        JNIEnv* env,
        jobject) {
    CloseNativeCamera();
    if (g_activity != nullptr) {
        env->DeleteGlobalRef(g_activity);
        g_activity = nullptr;
    }
    g_on_native_frame = nullptr;
    g_on_native_captured_preview = nullptr;
    g_on_native_smoothening_complete = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_w3n_webstreampreprocessingandroidsdk_CameraImageSmootheningUsingNativeActivity_nativeStartNativeCamera(
        JNIEnv* env,
        jobject,
        jobject previewSurface,
        jint width,
        jint height,
        jint lensFacing) {
    std::lock_guard<std::mutex> lock(g_camera_mutex);
    CloseNativeCameraLocked();
    g_frame_count = 0;
    ClearCapturedFrame();

    g_preview_window = ANativeWindow_fromSurface(env, previewSurface);
    if (g_preview_window == nullptr) {
        LOGE("preview window is null");
        return JNI_FALSE;
    }

    g_camera_manager = ACameraManager_create();
    ACameraIdList* cameraIds = nullptr;
    camera_status_t status = ACameraManager_getCameraIdList(g_camera_manager, &cameraIds);
    if (status != ACAMERA_OK || cameraIds == nullptr) {
        LOGE("getCameraIdList failed: %d", status);
        CloseNativeCameraLocked();
        return JNI_FALSE;
    }

    const char* cameraId = FindCameraId(g_camera_manager, cameraIds, lensFacing);
    if (cameraId == nullptr) {
        ACameraManager_deleteCameraIdList(cameraIds);
        CloseNativeCameraLocked();
        return JNI_FALSE;
    }

    const CameraSize captureSize = ChooseYuvSize(g_camera_manager, cameraId, width, height);
    media_status_t mediaStatus = AImageReader_new(
            captureSize.width,
            captureSize.height,
            AIMAGE_FORMAT_YUV_420_888,
            4,
            &g_image_reader);
    if (mediaStatus != AMEDIA_OK || g_image_reader == nullptr) {
        LOGE("AImageReader_new failed: %d", mediaStatus);
        ACameraManager_deleteCameraIdList(cameraIds);
        CloseNativeCameraLocked();
        return JNI_FALSE;
    }

    AImageReader_ImageListener listener{};
    listener.context = nullptr;
    listener.onImageAvailable = OnImageAvailable;
    AImageReader_setImageListener(g_image_reader, &listener);
    AImageReader_getWindow(g_image_reader, &g_image_window);

    ACameraDevice_StateCallbacks callbacks{};
    callbacks.context = nullptr;
    callbacks.onDisconnected = OnCameraDisconnected;
    callbacks.onError = OnCameraError;

    status = ACameraManager_openCamera(g_camera_manager, cameraId, &callbacks, &g_camera_device);
    ACameraManager_deleteCameraIdList(cameraIds);
    if (status != ACAMERA_OK) {
        LOGE("openCamera failed: %d", status);
        CloseNativeCameraLocked();
        return JNI_FALSE;
    }

    if (!CreateCaptureSessionLocked()) {
        CloseNativeCameraLocked();
        return JNI_FALSE;
    }

    LOGD("native camera started: target=%dx%d capture=%dx%d lens=%d",
         width, height, captureSize.width, captureSize.height, lensFacing);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_w3n_webstreampreprocessingandroidsdk_CameraImageSmootheningUsingNativeActivity_nativeCaptureNextNativeFrame(
        JNIEnv*,
        jobject) {
    g_capture_next_frame = true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_w3n_webstreampreprocessingandroidsdk_CameraImageSmootheningUsingNativeActivity_nativeSmoothCapturedNativeFrame(
        JNIEnv*,
        jobject,
        jint targetWidth,
        jint targetHeight,
        jint edgeThreshold) {
    return ProcessCapturedFrame(targetWidth, targetHeight, edgeThreshold) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_w3n_webstreampreprocessingandroidsdk_CameraImageSmootheningUsingNativeActivity_nativeStopNativeCamera(
        JNIEnv*,
        jobject) {
    CloseNativeCamera();
}
