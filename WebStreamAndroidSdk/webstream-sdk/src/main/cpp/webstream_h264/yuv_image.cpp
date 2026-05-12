#include "yuv_image.h"

#include <algorithm>
#include <cstring>

namespace webstream::h264 {

namespace {

bool ReadImagePlane(JNIEnv* env, jobject plane, PlaneData* output) {
    jobject buffer = CallObject(env, plane, "getBuffer", "()Ljava/nio/ByteBuffer;");
    if (buffer == nullptr) {
        return false;
    }

    output->data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    output->rowStride = CallInt(env, plane, "getRowStride");
    output->pixelStride = CallInt(env, plane, "getPixelStride");
    return output->data != nullptr;
}

}  // namespace

bool ReadImagePlanes(JNIEnv* env, jobject image, PlaneData planes[3]) {
    jobject planeArrayObject = CallObject(env, image, "getPlanes", "()[Landroid/media/Image$Plane;");
    if (planeArrayObject == nullptr) {
        return false;
    }

    auto planeArray = static_cast<jobjectArray>(planeArrayObject);
    if (env->GetArrayLength(planeArray) < 3) {
        return false;
    }

    for (jsize i = 0; i < 3; ++i) {
        jobject plane = env->GetObjectArrayElement(planeArray, i);
        if (plane == nullptr || !ReadImagePlane(env, plane, &planes[i])) {
            return false;
        }
    }

    return true;
}

std::vector<uint8_t> PackYuv420888(
        int width,
        int height,
        const PlaneData planes[3],
        CameraFrameType outputType) {
    int frameSize = width * height;
    int chromaSize = frameSize / 4;
    int totalSize = frameSize * 3 / 2;
    std::vector<uint8_t> output(static_cast<size_t>(totalSize));

    int position = 0;
    const PlaneData& yPlane = planes[0];
    const PlaneData& uPlane = planes[1];
    const PlaneData& vPlane = planes[2];

    if (yPlane.pixelStride == 1 && yPlane.rowStride == width) {
        std::memcpy(output.data(), yPlane.data, static_cast<size_t>(frameSize));
        position = frameSize;
    } else {
        for (int row = 0; row < height; ++row) {
            const uint8_t* yRow = yPlane.data + row * yPlane.rowStride;
            if (yPlane.pixelStride == 1) {
                std::memcpy(output.data() + position, yRow, static_cast<size_t>(width));
                position += width;
            } else {
                for (int col = 0; col < width; ++col) {
                    output[position++] = yRow[col * yPlane.pixelStride];
                }
            }
        }
    }

    int uvHeight = height / 2;
    int uvWidth = width / 2;

    if (outputType == CAMERA_FRAME_I420) {
        int uOut = frameSize;
        int vOut = frameSize + chromaSize;

        for (int row = 0; row < uvHeight; ++row) {
            const uint8_t* uRow = uPlane.data + row * uPlane.rowStride;
            const uint8_t* vRow = vPlane.data + row * vPlane.rowStride;

            for (int col = 0; col < uvWidth; ++col) {
                output[uOut++] = uRow[col * uPlane.pixelStride];
                output[vOut++] = vRow[col * vPlane.pixelStride];
            }
        }
        return output;
    }

    for (int row = 0; row < uvHeight; ++row) {
        const uint8_t* uRow = uPlane.data + row * uPlane.rowStride;
        const uint8_t* vRow = vPlane.data + row * vPlane.rowStride;

        for (int col = 0; col < uvWidth; ++col) {
            uint8_t uValue = uRow[col * uPlane.pixelStride];
            uint8_t vValue = vRow[col * vPlane.pixelStride];

            if (outputType == CAMERA_FRAME_NV21) {
                output[position++] = vValue;
                output[position++] = uValue;
            } else {
                output[position++] = uValue;
                output[position++] = vValue;
            }
        }
    }

    return output;
}

jobject CreateYuvImageBitmap(
        JNIEnv* env,
        const std::vector<uint8_t>& nv21,
        int width,
        int height,
        int jpegQuality) {
    jbyteArray nv21Array = ToJavaByteArray(env, nv21);
    if (nv21Array == nullptr) {
        return nullptr;
    }

    jclass yuvImageClass = env->FindClass("android/graphics/YuvImage");
    jmethodID yuvImageConstructor = env->GetMethodID(yuvImageClass, "<init>", "([BIII[I)V");
    jobject yuvImage = env->NewObject(
            yuvImageClass,
            yuvImageConstructor,
            nv21Array,
            17,
            width,
            height,
            static_cast<jintArray>(nullptr));
    if (yuvImage == nullptr) {
        return nullptr;
    }

    jclass rectClass = env->FindClass("android/graphics/Rect");
    jmethodID rectConstructor = env->GetMethodID(rectClass, "<init>", "(IIII)V");
    jobject rect = env->NewObject(rectClass, rectConstructor, 0, 0, width, height);
    if (rect == nullptr) {
        return nullptr;
    }

    jclass outputClass = env->FindClass("java/io/ByteArrayOutputStream");
    jmethodID outputConstructor = env->GetMethodID(outputClass, "<init>", "()V");
    jobject outputStream = env->NewObject(outputClass, outputConstructor);
    if (outputStream == nullptr) {
        return nullptr;
    }

    jmethodID compressToJpeg = env->GetMethodID(
            yuvImageClass,
            "compressToJpeg",
            "(Landroid/graphics/Rect;ILjava/io/OutputStream;)Z");
    jboolean compressed = env->CallBooleanMethod(
            yuvImage,
            compressToJpeg,
            rect,
            std::max(1, std::min(100, jpegQuality)),
            outputStream);
    if (compressed != JNI_TRUE) {
        return nullptr;
    }

    jmethodID toByteArray = env->GetMethodID(outputClass, "toByteArray", "()[B");
    auto jpegBytes = static_cast<jbyteArray>(env->CallObjectMethod(outputStream, toByteArray));
    if (jpegBytes == nullptr) {
        return nullptr;
    }

    jclass bitmapFactoryClass = env->FindClass("android/graphics/BitmapFactory");
    jmethodID decodeByteArray = env->GetStaticMethodID(
            bitmapFactoryClass,
            "decodeByteArray",
            "([BII)Landroid/graphics/Bitmap;");
    return env->CallStaticObjectMethod(
            bitmapFactoryClass,
            decodeByteArray,
            jpegBytes,
            0,
            env->GetArrayLength(jpegBytes));
}

jobject CreateCameraFrame(
        JNIEnv* env,
        int width,
        int height,
        int64_t timestampNs,
        int64_t sequence,
        jobject frameType,
        jobject yuvFormat,
        jbyteArray yuvData,
        jobject bitmap) {
    jclass frameClass = env->FindClass("com/w3n/webstream/Util/CameraController$CameraFrame");
    if (frameClass == nullptr) {
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(
            frameClass,
            "<init>",
            "(IIJJLcom/w3n/webstream/Util/CameraController$FrameType;"
            "Lcom/w3n/webstream/Util/CameraController$YuvFormat;"
            "[BLandroid/graphics/Bitmap;)V");
    if (constructor == nullptr) {
        return nullptr;
    }

    return env->NewObject(
            frameClass,
            constructor,
            width,
            height,
            static_cast<jlong>(timestampNs),
            static_cast<jlong>(sequence),
            frameType,
            yuvFormat,
            yuvData,
            bitmap);
}

}  // namespace webstream::h264
