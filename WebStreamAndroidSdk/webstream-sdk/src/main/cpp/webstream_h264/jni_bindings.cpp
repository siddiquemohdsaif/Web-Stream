#include "common.h"
#include "encoder_state.h"
#include "yuv_image.h"

#include <jni.h>

#include <memory>
#include <string>
#include <vector>

using webstream::h264::CAMERA_FRAME_BITMAP;
using webstream::h264::CAMERA_FRAME_I420;
using webstream::h264::CAMERA_FRAME_NV12;
using webstream::h264::CAMERA_FRAME_NV21;
using webstream::h264::CameraFrameType;
using webstream::h264::CreateCameraFrame;
using webstream::h264::CreateYuvImageBitmap;
using webstream::h264::EncoderState;
using webstream::h264::EnumOrdinal;
using webstream::h264::FromHandle;
using webstream::h264::GetStaticEnum;
using webstream::h264::PackYuv420888;
using webstream::h264::PlaneData;
using webstream::h264::ReadImagePlanes;
using webstream::h264::ThrowJava;
using webstream::h264::ToJavaByteArray;
using webstream::h264::CallInt;
using webstream::h264::CallLong;

extern "C" JNIEXPORT jlong JNICALL
Java_com_w3n_webstream_Util_H264FrameBatchEncoder_nativeCreate(
        JNIEnv* env,
        jclass,
        jint width,
        jint height,
        jint frameRateFps,
        jint bitrateKbps,
        jint batchFrameCount,
        jint iFrameIntervalSeconds,
        jint inputYuvFormat,
        jboolean enableBatchTimingLogs,
        jboolean requestKeyFrameAtStart,
        jboolean requestKeyFrameEveryBatch) {
    if (width <= 0 || height <= 0 || (width % 2) != 0 || (height % 2) != 0) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
                  "H.264 frame dimensions must be positive even values.");
        return 0;
    }

    auto state = std::make_unique<EncoderState>(
            width,
            height,
            frameRateFps,
            bitrateKbps,
            batchFrameCount,
            iFrameIntervalSeconds,
            inputYuvFormat,
            enableBatchTimingLogs == JNI_TRUE,
            requestKeyFrameAtStart == JNI_TRUE,
            requestKeyFrameEveryBatch == JNI_TRUE);
    return reinterpret_cast<jlong>(state.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_w3n_webstream_Util_H264FrameBatchEncoder_nativeStart(JNIEnv* env, jclass, jlong handle) {
    EncoderState* state = FromHandle(handle);
    if (state == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException", "Native encoder handle is null.");
        return;
    }

    std::string error;
    if (!state->Start(&error)) {
        ThrowJava(env, "java/lang/IllegalStateException", error);
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_w3n_webstream_Util_H264FrameBatchEncoder_nativeEncodeFrame(
        JNIEnv* env,
        jclass,
        jlong handle,
        jbyteArray inputYuvData,
        jlong timestampMs) {
    EncoderState* state = FromHandle(handle);
    if (state == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException", "Native encoder handle is null.");
        return nullptr;
    }

    jobject result = nullptr;
    std::string error;
    if (!state->Encode(env, inputYuvData, timestampMs, &result, &error)) {
        ThrowJava(env, "java/lang/IllegalStateException", error);
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_w3n_webstream_Util_H264FrameBatchEncoder_nativeFlush(
        JNIEnv* env,
        jclass,
        jlong handle,
        jlong timestampMs) {
    EncoderState* state = FromHandle(handle);
    if (state == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException", "Native encoder handle is null.");
        return nullptr;
    }

    jobject result = nullptr;
    std::string error;
    if (!state->Flush(env, timestampMs, &result, &error)) {
        ThrowJava(env, "java/lang/IllegalStateException", error);
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_w3n_webstream_Util_H264FrameBatchEncoder_nativeStop(JNIEnv*, jclass, jlong handle) {
    EncoderState* state = FromHandle(handle);
    if (state != nullptr) {
        state->Stop();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_w3n_webstream_Util_H264FrameBatchEncoder_nativeRelease(JNIEnv*, jclass, jlong handle) {
    EncoderState* state = FromHandle(handle);
    delete state;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_w3n_webstream_Util_H264FrameBatchEncoder_nativeIsStarted(JNIEnv*, jclass, jlong handle) {
    EncoderState* state = FromHandle(handle);
    return state != nullptr && state->IsStarted() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_w3n_webstream_Util_H264FrameBatchEncoder_nativeGetTotalInputFrames(
        JNIEnv*,
        jclass,
        jlong handle) {
    EncoderState* state = FromHandle(handle);
    return state == nullptr ? 0 : static_cast<jlong>(state->TotalInputFrames());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_w3n_webstream_Util_H264FrameBatchEncoder_nativeGetBatchSequence(JNIEnv*, jclass, jlong handle) {
    EncoderState* state = FromHandle(handle);
    return state == nullptr ? 0 : static_cast<jlong>(state->BatchSequence());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_w3n_webstream_Util_CameraController_nativeProcessImage(
        JNIEnv* env,
        jclass,
        jobject image,
        jobject frameType,
        jlong sequence,
        jint jpegQuality) {
    if (image == nullptr || frameType == nullptr) {
        return nullptr;
    }

    int width = CallInt(env, image, "getWidth");
    int height = CallInt(env, image, "getHeight");
    int64_t timestampNs = CallLong(env, image, "getTimestamp");
    int frameTypeOrdinal = EnumOrdinal(env, frameType);

    if (width <= 0 || height <= 0 || (width % 2) != 0 || (height % 2) != 0) {
        ThrowJava(env, "java/lang/IllegalArgumentException",
                  "YUV frame dimensions must be positive even values.");
        return nullptr;
    }

    PlaneData planes[3];
    if (!ReadImagePlanes(env, image, planes)) {
        ThrowJava(env, "java/lang/IllegalStateException", "Could not read camera image planes.");
        return nullptr;
    }

    if (frameTypeOrdinal == CAMERA_FRAME_BITMAP) {
        std::vector<uint8_t> nv21 = PackYuv420888(width, height, planes, CAMERA_FRAME_NV21);
        jobject bitmap = CreateYuvImageBitmap(env, nv21, width, height, jpegQuality);
        return CreateCameraFrame(
                env,
                width,
                height,
                timestampNs,
                sequence,
                frameType,
                nullptr,
                nullptr,
                bitmap);
    }

    CameraFrameType outputType = CAMERA_FRAME_I420;
    const char* yuvFormatName = "I420";

    if (frameTypeOrdinal == CAMERA_FRAME_NV21) {
        outputType = CAMERA_FRAME_NV21;
        yuvFormatName = "NV21";
    } else if (frameTypeOrdinal == CAMERA_FRAME_NV12) {
        outputType = CAMERA_FRAME_NV12;
        yuvFormatName = "NV12";
    }

    std::vector<uint8_t> yuv = PackYuv420888(width, height, planes, outputType);
    jbyteArray yuvArray = ToJavaByteArray(env, yuv);
    jobject yuvFormat = GetStaticEnum(
            env,
            "com/w3n/webstream/Util/CameraController$YuvFormat",
            yuvFormatName,
            "Lcom/w3n/webstream/Util/CameraController$YuvFormat;");

    return CreateCameraFrame(
            env,
            width,
            height,
            timestampNs,
            sequence,
            frameType,
            yuvFormat,
            yuvArray,
            nullptr);
}
