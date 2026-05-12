#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace webstream::h264 {

constexpr const char* kTag = "H264FrameBatchEncoder";
constexpr const char* kMimeAvc = "video/avc";
constexpr int32_t kColorFormatYuv420SemiPlanar = 21;
constexpr int64_t kInputDequeueTimeoutUs = 10000;
constexpr int64_t kOutputDequeueTimeoutUs = 10000;
constexpr int64_t kMaxBatchOutputWaitNs = 2000000000LL;
constexpr uint32_t kBufferFlagKeyFrame = 1;
constexpr uint32_t kBufferFlagCodecConfig = 2;

enum InputYuvFormat {
    INPUT_NV21 = 0,
    INPUT_NV12 = 1,
    INPUT_I420 = 2
};

enum CameraFrameType {
    CAMERA_FRAME_I420 = 0,
    CAMERA_FRAME_NV21 = 1,
    CAMERA_FRAME_NV12 = 2,
    CAMERA_FRAME_BITMAP = 3
};

struct PlaneData {
    uint8_t* data = nullptr;
    int rowStride = 0;
    int pixelStride = 0;
};

int64_t NowNs();
void ThrowJava(JNIEnv* env, const char* className, const std::string& message);
int FindStartCode(const std::vector<uint8_t>& data, int fromIndex);
int StartCodeLength(const std::vector<uint8_t>& data, int start);
bool StartsWithCodecConfig(const std::vector<uint8_t>& encoded);
void Append(std::vector<uint8_t>* target, const uint8_t* data, size_t size);
jobject CallObject(JNIEnv* env, jobject object, const char* name, const char* signature);
jint CallInt(JNIEnv* env, jobject object, const char* name);
jlong CallLong(JNIEnv* env, jobject object, const char* name);
int EnumOrdinal(JNIEnv* env, jobject enumObject);
jobject GetStaticEnum(JNIEnv* env, const char* className, const char* fieldName, const char* signature);
jbyteArray ToJavaByteArray(JNIEnv* env, const std::vector<uint8_t>& data);

}  // namespace webstream::h264
