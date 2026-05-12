#pragma once

#include "common.h"

#include <jni.h>

#include <cstdint>
#include <vector>

namespace webstream::h264 {

bool ReadImagePlanes(JNIEnv* env, jobject image, PlaneData planes[3]);
std::vector<uint8_t> PackYuv420888(
        int width,
        int height,
        const PlaneData planes[3],
        CameraFrameType outputType);
jobject CreateYuvImageBitmap(
        JNIEnv* env,
        const std::vector<uint8_t>& nv21,
        int width,
        int height,
        int jpegQuality);
jobject CreateCameraFrame(
        JNIEnv* env,
        int width,
        int height,
        int64_t timestampNs,
        int64_t sequence,
        jobject frameType,
        jobject yuvFormat,
        jbyteArray yuvData,
        jobject bitmap);

}  // namespace webstream::h264
