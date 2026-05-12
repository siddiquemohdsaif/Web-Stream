#include <jni.h>
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace {

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

int64_t NowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

void ThrowJava(JNIEnv* env, const char* className, const std::string& message) {
    jclass errorClass = env->FindClass(className);
    if (errorClass != nullptr) {
        env->ThrowNew(errorClass, message.c_str());
    }
}

int FindStartCode(const std::vector<uint8_t>& data, int fromIndex) {
    for (int i = std::max(0, fromIndex); i <= static_cast<int>(data.size()) - 3; ++i) {
        if (data[i] == 0 && data[i + 1] == 0) {
            if (data[i + 2] == 1) {
                return i;
            }
            if (i <= static_cast<int>(data.size()) - 4 && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
    }
    return -1;
}

int StartCodeLength(const std::vector<uint8_t>& data, int start) {
    return data[start + 2] == 1 ? 3 : 4;
}

bool StartsWithCodecConfig(const std::vector<uint8_t>& encoded) {
    int start = FindStartCode(encoded, 0);
    if (start < 0) {
        return false;
    }
    int nalStart = start + StartCodeLength(encoded, start);
    if (nalStart >= static_cast<int>(encoded.size())) {
        return false;
    }
    int nalType = encoded[nalStart] & 0x1f;
    return nalType == 7 || nalType == 8;
}

void Append(std::vector<uint8_t>* target, const uint8_t* data, size_t size) {
    if (data == nullptr || size == 0) {
        return;
    }
    target->insert(target->end(), data, data + size);
}

jobject CallObject(JNIEnv* env, jobject object, const char* name, const char* signature) {
    jclass objectClass = env->GetObjectClass(object);
    jmethodID method = env->GetMethodID(objectClass, name, signature);
    return method == nullptr ? nullptr : env->CallObjectMethod(object, method);
}

jint CallInt(JNIEnv* env, jobject object, const char* name) {
    jclass objectClass = env->GetObjectClass(object);
    jmethodID method = env->GetMethodID(objectClass, name, "()I");
    return method == nullptr ? 0 : env->CallIntMethod(object, method);
}

jlong CallLong(JNIEnv* env, jobject object, const char* name) {
    jclass objectClass = env->GetObjectClass(object);
    jmethodID method = env->GetMethodID(objectClass, name, "()J");
    return method == nullptr ? 0 : env->CallLongMethod(object, method);
}

int EnumOrdinal(JNIEnv* env, jobject enumObject) {
    jclass enumClass = env->GetObjectClass(enumObject);
    jmethodID ordinal = env->GetMethodID(enumClass, "ordinal", "()I");
    if (ordinal == nullptr) {
        return 0;
    }
    return env->CallIntMethod(enumObject, ordinal);
}

jobject GetStaticEnum(JNIEnv* env, const char* className, const char* fieldName, const char* signature) {
    jclass enumClass = env->FindClass(className);
    if (enumClass == nullptr) {
        return nullptr;
    }
    jfieldID field = env->GetStaticFieldID(enumClass, fieldName, signature);
    if (field == nullptr) {
        return nullptr;
    }
    return env->GetStaticObjectField(enumClass, field);
}

struct PlaneData {
    uint8_t* data = nullptr;
    int rowStride = 0;
    int pixelStride = 0;
};

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

jbyteArray ToJavaByteArray(JNIEnv* env, const std::vector<uint8_t>& data) {
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

class EncoderState {
public:
    EncoderState(
            int width,
            int height,
            int frameRateFps,
            int bitrateKbps,
            int batchFrameCount,
            int iFrameIntervalSeconds,
            int inputYuvFormat,
            bool enableBatchTimingLogs,
            bool requestKeyFrameAtStart,
            bool requestKeyFrameEveryBatch)
            : width_(width),
              height_(height),
              frameRateFps_(std::max(1, frameRateFps)),
              bitrateKbps_(std::max(1, bitrateKbps)),
              batchFrameCount_(std::max(1, batchFrameCount)),
              iFrameIntervalSeconds_(std::max(1, iFrameIntervalSeconds)),
              inputYuvFormat_(inputYuvFormat),
              enableBatchTimingLogs_(enableBatchTimingLogs),
              requestKeyFrameAtStart_(requestKeyFrameAtStart),
              requestKeyFrameEveryBatch_(requestKeyFrameEveryBatch) {
    }

    ~EncoderState() {
        Release();
    }

    bool Start(std::string* error) {
        if (released_) {
            *error = "Encoder is already released.";
            return false;
        }
        if (started_) {
            return true;
        }

        codec_ = AMediaCodec_createEncoderByType(kMimeAvc);
        if (codec_ == nullptr) {
            *error = "No native H.264 encoder is available.";
            return false;
        }

        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, kMimeAvc);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width_);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height_);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, kColorFormatYuv420SemiPlanar);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, bitrateKbps_ * 1000);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, frameRateFps_);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds_);
        AMediaFormat_setInt32(format, "prepend-sps-pps-to-idr-frames", 1);

        media_status_t status = AMediaCodec_configure(
                codec_, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        AMediaFormat_delete(format);
        if (status != AMEDIA_OK) {
            *error = "Failed to configure native H.264 encoder.";
            AMediaCodec_delete(codec_);
            codec_ = nullptr;
            return false;
        }

        status = AMediaCodec_start(codec_);
        if (status != AMEDIA_OK) {
            *error = "Failed to start native H.264 encoder.";
            AMediaCodec_delete(codec_);
            codec_ = nullptr;
            return false;
        }

        inputFramesInBatch_ = 0;
        encodedFramesInBatch_ = 0;
        batchSequence_ = 0;
        totalInputFrames_ = 0;
        currentBatchEncodeComputeTimeNs_ = 0;
        activeEncodeTimingStartNs_ = 0;
        encoderStartTimeNs_ = NowNs();
        lastEncodedTimestampMs_ = 0;
        codecConfig_.clear();
        batchOutput_.clear();
        firstKeyFrameRequested_ = false;
        started_ = true;
        return true;
    }

    void Stop() {
        if (codec_ != nullptr) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
            codec_ = nullptr;
        }
        batchOutput_.clear();
        codecConfig_.clear();
        inputFramesInBatch_ = 0;
        encodedFramesInBatch_ = 0;
        currentBatchEncodeComputeTimeNs_ = 0;
        activeEncodeTimingStartNs_ = 0;
        started_ = false;
    }

    void Release() {
        if (released_) {
            return;
        }
        Stop();
        released_ = true;
    }

    bool Encode(JNIEnv* env, jbyteArray inputYuvData, int64_t timestampMs, jobject* result, std::string* error) {
        *result = nullptr;
        if (!started_ || codec_ == nullptr) {
            return true;
        }
        if (inputYuvData == nullptr) {
            return true;
        }

        jsize inputSize = env->GetArrayLength(inputYuvData);
        int expectedSize = ExpectedYuvSize();
        if (inputSize < expectedSize) {
            *error = "Invalid YUV frame size. Expected at least "
                    + std::to_string(expectedSize)
                    + " bytes, got "
                    + std::to_string(inputSize);
            return false;
        }

        jboolean isCopy = JNI_FALSE;
        jbyte* inputBytes = env->GetByteArrayElements(inputYuvData, &isCopy);
        if (inputBytes == nullptr) {
            *error = "Could not read YUV frame bytes.";
            return false;
        }

        bool ok = EncodeBytes(
                reinterpret_cast<const uint8_t*>(inputBytes),
                static_cast<size_t>(inputSize),
                timestampMs,
                result,
                error,
                env);
        env->ReleaseByteArrayElements(inputYuvData, inputBytes, JNI_ABORT);
        return ok;
    }

    bool Flush(JNIEnv* env, int64_t timestampMs, jobject* result, std::string* error) {
        *result = nullptr;
        if (!started_ || codec_ == nullptr) {
            return true;
        }
        if (!HasActiveBatch()) {
            return true;
        }

        BeginEncodeTiming();
        if (!Drain(false, error)) {
            EndEncodeTiming();
            return false;
        }

        if (inputFramesInBatch_ > 0) {
            if (!WaitForCurrentBatchEncodedAndDispatch(env, true, timestampMs, result, error)) {
                EndEncodeTiming();
                return false;
            }
        } else if (!batchOutput_.empty()) {
            int64_t durationNs = GetAndFreezeCurrentBatchDurationNs();
            *result = DispatchCurrentBatch(env, timestampMs, durationNs, true);
        }

        EndEncodeTiming();
        return true;
    }

    bool IsStarted() const {
        return started_;
    }

    int64_t TotalInputFrames() const {
        return totalInputFrames_;
    }

    int64_t BatchSequence() const {
        return batchSequence_;
    }

private:
    bool EncodeBytes(
            const uint8_t* input,
            size_t inputSize,
            int64_t timestampMs,
            jobject* result,
            std::string* error,
            JNIEnv* env) {
        StartBatchIfNeeded();
        BeginEncodeTiming();

        if (requestKeyFrameAtStart_ && !firstKeyFrameRequested_) {
            RequestKeyFrame();
            firstKeyFrameRequested_ = true;
        }
        if (inputFramesInBatch_ == 0 && requestKeyFrameEveryBatch_) {
            RequestKeyFrame();
        }

        if (!Drain(false, error)) {
            EndEncodeTiming();
            return false;
        }

        ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, kInputDequeueTimeoutUs);
        if (inputIndex < 0) {
            EndEncodeTiming();
            return true;
        }

        size_t capacity = 0;
        uint8_t* inputBuffer = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(inputIndex), &capacity);
        if (inputBuffer == nullptr) {
            EndEncodeTiming();
            return true;
        }

        std::vector<uint8_t> encoderReadyYuv = ToNv12(input, inputSize);
        if (encoderReadyYuv.size() > capacity) {
            *error = "Native H.264 encoder input buffer is too small.";
            EndEncodeTiming();
            return false;
        }

        std::memcpy(inputBuffer, encoderReadyYuv.data(), encoderReadyYuv.size());
        media_status_t status = AMediaCodec_queueInputBuffer(
                codec_,
                static_cast<size_t>(inputIndex),
                0,
                encoderReadyYuv.size(),
                timestampMs * 1000LL,
                0);
        if (status != AMEDIA_OK) {
            *error = "Failed to queue native H.264 input buffer.";
            EndEncodeTiming();
            return false;
        }

        inputFramesInBatch_++;
        totalInputFrames_++;

        if (!Drain(false, error)) {
            EndEncodeTiming();
            return false;
        }

        if (inputFramesInBatch_ >= batchFrameCount_) {
            if (!WaitForCurrentBatchEncodedAndDispatch(env, false, timestampMs, result, error)) {
                EndEncodeTiming();
                return false;
            }
        }

        EndEncodeTiming();
        return true;
    }

    bool Drain(bool waitForOutput, std::string* error) {
        while (true) {
            AMediaCodecBufferInfo bufferInfo{};
            ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(
                    codec_,
                    &bufferInfo,
                    waitForOutput ? kOutputDequeueTimeoutUs : 0);

            if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                return true;
            }
            if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                RememberCodecConfigFromFormat();
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }

            size_t outputSize = 0;
            uint8_t* outputBuffer = AMediaCodec_getOutputBuffer(
                    codec_, static_cast<size_t>(outputIndex), &outputSize);

            if (outputBuffer != nullptr && bufferInfo.size > 0) {
                size_t offset = static_cast<size_t>(std::max(0, bufferInfo.offset));
                size_t size = static_cast<size_t>(bufferInfo.size);
                if (offset + size > outputSize) {
                    *error = "Native H.264 output buffer bounds are invalid.";
                    AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(outputIndex), false);
                    return false;
                }

                std::vector<uint8_t> encoded(size);
                std::memcpy(encoded.data(), outputBuffer + offset, size);

                if ((static_cast<uint32_t>(bufferInfo.flags) & kBufferFlagCodecConfig) != 0) {
                    codecConfig_ = std::move(encoded);
                } else {
                    RememberCodecConfigFromAnnexB(encoded);
                    if ((static_cast<uint32_t>(bufferInfo.flags) & kBufferFlagKeyFrame) != 0
                            && !codecConfig_.empty()) {
                        Append(&batchOutput_, codecConfig_.data(), codecConfig_.size());
                    }
                    Append(&batchOutput_, encoded.data(), encoded.size());
                    encodedFramesInBatch_++;

                    if (bufferInfo.presentationTimeUs > 0) {
                        lastEncodedTimestampMs_ = bufferInfo.presentationTimeUs / 1000LL;
                    }
                }
            }

            AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(outputIndex), false);

            if (waitForOutput && inputFramesInBatch_ > 0
                    && encodedFramesInBatch_ >= inputFramesInBatch_) {
                return true;
            }
        }
    }

    bool WaitForCurrentBatchEncodedAndDispatch(
            JNIEnv* env,
            bool partialBatch,
            int64_t fallbackTimestampMs,
            jobject* result,
            std::string* error) {
        int64_t deadlineNs = NowNs() + kMaxBatchOutputWaitNs;
        int targetFrameCount = partialBatch ? inputFramesInBatch_ : batchFrameCount_;

        while (encodedFramesInBatch_ < targetFrameCount && NowNs() < deadlineNs) {
            if (!Drain(true, error)) {
                return false;
            }
        }

        if (batchOutput_.empty()) {
            return true;
        }

        int64_t timestampForBatch = lastEncodedTimestampMs_ > 0
                ? lastEncodedTimestampMs_
                : fallbackTimestampMs;
        int64_t batchDurationNs = GetAndFreezeCurrentBatchDurationNs();
        *result = DispatchCurrentBatch(env, timestampForBatch, batchDurationNs, partialBatch);
        return true;
    }

    jobject DispatchCurrentBatch(
            JNIEnv* env,
            int64_t timestampMs,
            int64_t batchDurationNs,
            bool partialBatch) {
        std::vector<uint8_t> encodedBatch = std::move(batchOutput_);
        batchOutput_.clear();

        int frameCountForThisBatch = encodedFramesInBatch_ > 0
                ? encodedFramesInBatch_
                : inputFramesInBatch_;

        inputFramesInBatch_ = 0;
        encodedFramesInBatch_ = 0;
        currentBatchEncodeComputeTimeNs_ = 0;
        activeEncodeTimingStartNs_ = 0;
        lastEncodedTimestampMs_ = 0;

        if (encodedBatch.empty()) {
            return nullptr;
        }

        if (!codecConfig_.empty() && !StartsWithCodecConfig(encodedBatch)) {
            std::vector<uint8_t> withConfig;
            withConfig.reserve(codecConfig_.size() + encodedBatch.size());
            Append(&withConfig, codecConfig_.data(), codecConfig_.size());
            Append(&withConfig, encodedBatch.data(), encodedBatch.size());
            encodedBatch = std::move(withConfig);
        }

        batchSequence_++;

        if (enableBatchTimingLogs_) {
            double batchDurationMs = batchDurationNs / 1000000.0;
            double averageFrameTimeMs = frameCountForThisBatch > 0
                    ? batchDurationMs / frameCountForThisBatch
                    : 0.0;
            double outputKb = encodedBatch.size() / 1024.0;
            double runningSeconds = (NowNs() - encoderStartTimeNs_) / 1000000000.0;
            double effectiveInputFps = runningSeconds > 0.0
                    ? totalInputFrames_ / runningSeconds
                    : 0.0;
            __android_log_print(
                    ANDROID_LOG_DEBUG,
                    kTag,
                    "Native H.264 batch encoded. batchSequence=%lld, frames=%d, "
                    "configuredBatchFrames=%d, partialBatch=%d, encodeComputeDurationMs=%.2f, "
                    "avgEncodeComputeFrameMs=%.2f, outputKb=%.2f, width=%d, height=%d, "
                    "bitrateKbps=%d, configuredFps=%d, effectiveInputFps=%.2f, totalInputFrames=%lld",
                    static_cast<long long>(batchSequence_),
                    frameCountForThisBatch,
                    batchFrameCount_,
                    partialBatch ? 1 : 0,
                    batchDurationMs,
                    averageFrameTimeMs,
                    outputKb,
                    width_,
                    height_,
                    bitrateKbps_,
                    frameRateFps_,
                    effectiveInputFps,
                    static_cast<long long>(totalInputFrames_));
        }

        jbyteArray encodedArray = env->NewByteArray(static_cast<jsize>(encodedBatch.size()));
        if (encodedArray == nullptr) {
            return nullptr;
        }
        env->SetByteArrayRegion(
                encodedArray,
                0,
                static_cast<jsize>(encodedBatch.size()),
                reinterpret_cast<const jbyte*>(encodedBatch.data()));

        jclass resultClass = env->FindClass(
                "com/w3n/webstream/Util/H264FrameBatchEncoder$NativeBatchResult");
        if (resultClass == nullptr) {
            return nullptr;
        }

        jmethodID constructor = env->GetMethodID(resultClass, "<init>", "([BJJIJZ)V");
        if (constructor == nullptr) {
            return nullptr;
        }

        return env->NewObject(
                resultClass,
                constructor,
                encodedArray,
                static_cast<jlong>(timestampMs),
                static_cast<jlong>(batchSequence_),
                static_cast<jint>(frameCountForThisBatch),
                static_cast<jlong>(batchDurationNs),
                static_cast<jboolean>(partialBatch));
    }

    void RememberCodecConfigFromFormat() {
        AMediaFormat* outputFormat = AMediaCodec_getOutputFormat(codec_);
        if (outputFormat == nullptr) {
            return;
        }

        void* csd0 = nullptr;
        void* csd1 = nullptr;
        size_t csd0Size = 0;
        size_t csd1Size = 0;
        bool hasCsd0 = AMediaFormat_getBuffer(outputFormat, "csd-0", &csd0, &csd0Size);
        bool hasCsd1 = AMediaFormat_getBuffer(outputFormat, "csd-1", &csd1, &csd1Size);

        if (hasCsd0 && hasCsd1 && csd0 != nullptr && csd1 != nullptr) {
            codecConfig_.clear();
            Append(&codecConfig_, static_cast<uint8_t*>(csd0), csd0Size);
            Append(&codecConfig_, static_cast<uint8_t*>(csd1), csd1Size);
            __android_log_print(
                    ANDROID_LOG_DEBUG,
                    kTag,
                    "Native H.264 encoder configured. width=%d, height=%d, colorFormat=%d",
                    width_,
                    height_,
                    kColorFormatYuv420SemiPlanar);
        }

        AMediaFormat_delete(outputFormat);
    }

    void RememberCodecConfigFromAnnexB(const std::vector<uint8_t>& encoded) {
        if (encoded.empty()) {
            return;
        }

        int spsStart = -1;
        int ppsStart = -1;
        int configEnd = -1;
        int offset = 0;

        while (true) {
            int start = FindStartCode(encoded, offset);
            if (start < 0) {
                break;
            }

            int nalStart = start + StartCodeLength(encoded, start);
            int next = FindStartCode(encoded, nalStart);
            int nalEnd = next < 0 ? static_cast<int>(encoded.size()) : next;

            if (nalStart < nalEnd) {
                int nalType = encoded[nalStart] & 0x1f;
                if (nalType == 7 && spsStart < 0) {
                    spsStart = start;
                } else if (nalType == 8 && spsStart >= 0 && ppsStart < 0) {
                    ppsStart = start;
                    configEnd = nalEnd;
                } else if (nalType != 7 && nalType != 8 && ppsStart >= 0) {
                    configEnd = start;
                    break;
                }
            }

            if (next < 0) {
                break;
            }
            offset = next;
        }

        if (spsStart >= 0 && ppsStart >= 0 && configEnd > spsStart) {
            codecConfig_.assign(encoded.begin() + spsStart, encoded.begin() + configEnd);
        }
    }

    std::vector<uint8_t> ToNv12(const uint8_t* input, size_t inputSize) const {
        int frameSize = width_ * height_;
        int chromaSize = frameSize / 4;
        int totalSize = frameSize * 3 / 2;
        std::vector<uint8_t> output(static_cast<size_t>(totalSize));

        std::memcpy(output.data(), input, std::min(inputSize, static_cast<size_t>(frameSize)));

        if (inputYuvFormat_ == INPUT_NV12) {
            std::memcpy(
                    output.data() + frameSize,
                    input + frameSize,
                    std::min(inputSize - frameSize, static_cast<size_t>(totalSize - frameSize)));
            return output;
        }

        if (inputYuvFormat_ == INPUT_NV21) {
            for (int i = frameSize; i < totalSize; i += 2) {
                output[i] = input[i + 1];
                output[i + 1] = input[i];
            }
            return output;
        }

        if (inputYuvFormat_ == INPUT_I420) {
            int uStart = frameSize;
            int vStart = frameSize + chromaSize;
            int out = frameSize;
            for (int i = 0; i < chromaSize; ++i) {
                output[out++] = input[uStart + i];
                output[out++] = input[vStart + i];
            }
        }

        return output;
    }

    void RequestKeyFrame() {
        if (codec_ == nullptr) {
            return;
        }
#if __ANDROID_API__ >= 26
        AMediaFormat* parameters = AMediaFormat_new();
        AMediaFormat_setInt32(parameters, "request-sync", 0);
        AMediaCodec_setParameters(codec_, parameters);
        AMediaFormat_delete(parameters);
#endif
    }

    void StartBatchIfNeeded() {
        if (!HasActiveBatch()) {
            inputFramesInBatch_ = 0;
            encodedFramesInBatch_ = 0;
            currentBatchEncodeComputeTimeNs_ = 0;
            activeEncodeTimingStartNs_ = 0;
            lastEncodedTimestampMs_ = 0;
            batchOutput_.clear();
        }
    }

    bool HasActiveBatch() const {
        return inputFramesInBatch_ > 0
                || encodedFramesInBatch_ > 0
                || !batchOutput_.empty();
    }

    void BeginEncodeTiming() {
        if (activeEncodeTimingStartNs_ == 0) {
            activeEncodeTimingStartNs_ = NowNs();
        }
    }

    void EndEncodeTiming() {
        if (activeEncodeTimingStartNs_ > 0 && HasActiveBatch()) {
            int64_t nowNs = NowNs();
            currentBatchEncodeComputeTimeNs_ += nowNs - activeEncodeTimingStartNs_;
        }
        activeEncodeTimingStartNs_ = 0;
    }

    int64_t GetAndFreezeCurrentBatchDurationNs() {
        if (activeEncodeTimingStartNs_ > 0) {
            int64_t nowNs = NowNs();
            currentBatchEncodeComputeTimeNs_ += nowNs - activeEncodeTimingStartNs_;
            activeEncodeTimingStartNs_ = nowNs;
        }
        return currentBatchEncodeComputeTimeNs_;
    }

    int ExpectedYuvSize() const {
        return width_ * height_ * 3 / 2;
    }

    int width_;
    int height_;
    int frameRateFps_;
    int bitrateKbps_;
    int batchFrameCount_;
    int iFrameIntervalSeconds_;
    int inputYuvFormat_;
    bool enableBatchTimingLogs_;
    bool requestKeyFrameAtStart_;
    bool requestKeyFrameEveryBatch_;

    AMediaCodec* codec_ = nullptr;
    std::vector<uint8_t> batchOutput_;
    std::vector<uint8_t> codecConfig_;
    int inputFramesInBatch_ = 0;
    int encodedFramesInBatch_ = 0;
    int64_t batchSequence_ = 0;
    int64_t totalInputFrames_ = 0;
    int64_t currentBatchEncodeComputeTimeNs_ = 0;
    int64_t activeEncodeTimingStartNs_ = 0;
    int64_t encoderStartTimeNs_ = 0;
    int64_t lastEncodedTimestampMs_ = 0;
    bool firstKeyFrameRequested_ = false;
    bool started_ = false;
    bool released_ = false;
};

EncoderState* FromHandle(jlong handle) {
    return reinterpret_cast<EncoderState*>(handle);
}

}  // namespace

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
Java_com_w3n_webstream_Util_H264FrameBatchEncoder_nativeGetTotalInputFrames(JNIEnv*, jclass, jlong handle) {
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
