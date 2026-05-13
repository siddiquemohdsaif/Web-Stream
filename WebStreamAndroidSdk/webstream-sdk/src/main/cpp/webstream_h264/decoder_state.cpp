#include "decoder_state.h"

#include "common.h"

#include <android/log.h>
#include <media/NdkMediaFormat.h>

#include <algorithm>
#include <cstdlib>
#include <cstring>

namespace webstream::h264 {

namespace {
constexpr const char* kDecoderTag = "H264FrameBatchDecoder";
constexpr int32_t kColorFormatYuv420Flexible = 0x7F420888;
constexpr int64_t kMaxDecodeBatchWaitNs = 2000000000LL;

struct NalUnitView {
    int start = 0;
    int end = 0;
    int nalHeaderIndex = 0;
    int type = 0;
};

int FindStartCode(const uint8_t* data, size_t size, int fromIndex) {
    for (int i = std::max(0, fromIndex); i <= static_cast<int>(size) - 3; ++i) {
        if (data[i] == 0 && data[i + 1] == 0) {
            if (data[i + 2] == 1) {
                return i;
            }
            if (i <= static_cast<int>(size) - 4 && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
    }
    return -1;
}

int StartCodeLength(const uint8_t* data, int start) {
    return data[start + 2] == 1 ? 3 : 4;
}

bool IsFirstSlice(const uint8_t* data, const NalUnitView& nalUnit) {
    int firstSliceByteIndex = nalUnit.nalHeaderIndex + 1;
    return firstSliceByteIndex < nalUnit.end && (data[firstSliceByteIndex] & 0x80) != 0;
}

}  // namespace

DecoderState::DecoderState(int width, int height, int frameRateFps, int maxQueuedFrames)
        : width_(width),
          height_(height),
          frameRateFps_(std::max(1, frameRateFps)),
          maxQueuedFrames_(std::max(1, maxQueuedFrames)),
          stride_(width),
          sliceHeight_(height),
          colorFormat_(kColorFormatYuv420Flexible) {
}

DecoderState::~DecoderState() {
    Release();
}

bool DecoderState::Start(std::string* error) {
    if (released_) {
        *error = "Decoder is already released.";
        return false;
    }
    if (started_) {
        return true;
    }

    codec_ = AMediaCodec_createDecoderByType(kMimeAvc);
    if (codec_ == nullptr) {
        *error = "No native H.264 decoder is available.";
        return false;
    }

    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, kMimeAvc);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width_);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height_);
    AMediaFormat_setInt32(format, "color-format", kColorFormatYuv420Flexible);

    media_status_t status = AMediaCodec_configure(codec_, format, nullptr, nullptr, 0);
    AMediaFormat_delete(format);
    if (status != AMEDIA_OK) {
        *error = "Failed to configure native H.264 decoder.";
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    status = AMediaCodec_start(codec_);
    if (status != AMEDIA_OK) {
        *error = "Failed to start native H.264 decoder.";
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    decodedFrameSequence_ = 0;
    stride_ = width_;
    sliceHeight_ = height_;
    colorFormat_ = kColorFormatYuv420Flexible;
    started_ = true;
    return true;
}

void DecoderState::Stop() {
    if (codec_ != nullptr) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    started_ = false;
}

void DecoderState::Release() {
    if (released_) {
        return;
    }
    Stop();
    released_ = true;
}

bool DecoderState::Decode(
        JNIEnv* env,
        jbyteArray encodedChunk,
        jobjectArray* result,
        std::string* error) {
    *result = nullptr;
    if (!started_ || codec_ == nullptr || encodedChunk == nullptr) {
        return true;
    }

    jsize inputSize = env->GetArrayLength(encodedChunk);
    if (inputSize <= 0) {
        return true;
    }

    jboolean isCopy = JNI_FALSE;
    jbyte* inputBytes = env->GetByteArrayElements(encodedChunk, &isCopy);
    if (inputBytes == nullptr) {
        *error = "Could not read encoded H.264 chunk bytes.";
        return false;
    }

    std::vector<std::vector<uint8_t>> accessUnits = SplitAnnexBAccessUnits(
            reinterpret_cast<const uint8_t*>(inputBytes),
            static_cast<size_t>(inputSize));
    env->ReleaseByteArrayElements(encodedChunk, inputBytes, JNI_ABORT);

    int queuedAccessUnits = 0;
    int64_t basePresentationTimeUs = NowNs() / 1000LL;
    for (const std::vector<uint8_t>& accessUnit : accessUnits) {
        if (accessUnit.empty()) {
            continue;
        }
        int64_t presentationTimeUs =
                basePresentationTimeUs + (queuedAccessUnits * 1000000LL / frameRateFps_);
        if (!QueueAccessUnit(accessUnit.data(), accessUnit.size(), presentationTimeUs, error)) {
            return false;
        }
        queuedAccessUnits++;
    }

    std::vector<DecodedNativeFrame> frames;
    if (!Drain(env, queuedAccessUnits, &frames, error)) {
        for (DecodedNativeFrame& frame : frames) {
            std::free(frame.data);
            frame.data = nullptr;
        }
        return false;
    }

    *result = ToJavaFrames(env, &frames);
    return true;
}

bool DecoderState::QueueAccessUnit(
        const uint8_t* data,
        size_t size,
        int64_t presentationTimeUs,
        std::string* error) {
    ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, kInputDequeueTimeoutUs);
    if (inputIndex < 0) {
        return true;
    }

    size_t capacity = 0;
    uint8_t* inputBuffer = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(inputIndex), &capacity);
    if (inputBuffer == nullptr) {
        return true;
    }
    if (size > capacity) {
        *error = "Native H.264 decoder input buffer is too small.";
        return false;
    }

    std::memcpy(inputBuffer, data, size);
    media_status_t status = AMediaCodec_queueInputBuffer(
            codec_,
            static_cast<size_t>(inputIndex),
            0,
            size,
            presentationTimeUs,
            0);
    if (status != AMEDIA_OK) {
        *error = "Failed to queue native H.264 decoder input buffer.";
        return false;
    }
    return true;
}

bool DecoderState::Drain(
        JNIEnv*,
        int expectedOutputFrames,
        std::vector<DecodedNativeFrame>* frames,
        std::string* error) {
    int64_t deadlineNs = NowNs() + kMaxDecodeBatchWaitNs;

    while (NowNs() < deadlineNs) {
        AMediaCodecBufferInfo bufferInfo{};
        ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(
                codec_,
                &bufferInfo,
                frames->empty() ? kOutputDequeueTimeoutUs : 0);

        if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            return true;
        }
        if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            ReadOutputFormat();
            continue;
        }
        if (outputIndex < 0) {
            continue;
        }

        size_t outputCapacity = 0;
        uint8_t* outputBuffer = AMediaCodec_getOutputBuffer(
                codec_, static_cast<size_t>(outputIndex), &outputCapacity);

        if (outputBuffer != nullptr && bufferInfo.size > 0) {
            size_t offset = static_cast<size_t>(std::max(0, bufferInfo.offset));
            size_t size = static_cast<size_t>(bufferInfo.size);
            if (offset + size > outputCapacity) {
                *error = "Native H.264 decoder output buffer bounds are invalid.";
                AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(outputIndex), false);
                return false;
            }

            uint8_t* copy = static_cast<uint8_t*>(std::malloc(size));
            if (copy == nullptr) {
                *error = "Could not allocate native decoded frame buffer.";
                AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(outputIndex), false);
                return false;
            }

            std::memcpy(copy, outputBuffer + offset, size);

            DecodedNativeFrame frame;
            frame.width = width_;
            frame.height = height_;
            frame.stride = stride_;
            frame.sliceHeight = sliceHeight_;
            frame.colorFormat = colorFormat_;
            frame.timestampNs = bufferInfo.presentationTimeUs * 1000LL;
            frame.sequence = ++decodedFrameSequence_;
            frame.data = copy;
            frame.size = static_cast<int>(size);
            frames->push_back(frame);
        }

        AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(outputIndex), false);

        if (expectedOutputFrames > 0 && static_cast<int>(frames->size()) >= expectedOutputFrames) {
            return true;
        }
    }

    return true;
}

jobjectArray DecoderState::ToJavaFrames(JNIEnv* env, std::vector<DecodedNativeFrame>* frames) {
    jclass frameClass = env->FindClass("com/w3n/webstream/Util/H264FrameBatchDecoder$DecodedFrame");
    if (frameClass == nullptr) {
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(
            static_cast<jsize>(frames->size()),
            frameClass,
            nullptr);
    if (result == nullptr) {
        return nullptr;
    }

    for (jsize i = 0; i < static_cast<jsize>(frames->size()); ++i) {
        jobject frame = CreateJavaFrame(env, &(*frames)[i]);
        if (frame == nullptr) {
            std::free((*frames)[i].data);
            (*frames)[i].data = nullptr;
            continue;
        }
        (*frames)[i].data = nullptr;
        env->SetObjectArrayElement(result, i, frame);
        env->DeleteLocalRef(frame);
    }

    return result;
}

jobject DecoderState::CreateJavaFrame(JNIEnv* env, DecodedNativeFrame* frame) {
    jclass frameClass = env->FindClass("com/w3n/webstream/Util/H264FrameBatchDecoder$DecodedFrame");
    if (frameClass == nullptr) {
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(
            frameClass,
            "<init>",
            "(IIJJJILandroid/media/MediaFormat;Lcom/w3n/webstream/Util/H264FrameBatchDecoder$FrameFormat;)V");
    if (constructor == nullptr) {
        return nullptr;
    }

    jobject mediaFormat = CreateJavaMediaFormat(env);
    jobject frameFormat = GetStaticEnum(
            env,
            "com/w3n/webstream/Util/H264FrameBatchDecoder$FrameFormat",
            "CODEC_OUTPUT_YUV",
            "Lcom/w3n/webstream/Util/H264FrameBatchDecoder$FrameFormat;");

    return env->NewObject(
            frameClass,
            constructor,
            static_cast<jint>(frame->width),
            static_cast<jint>(frame->height),
            static_cast<jlong>(frame->timestampNs),
            static_cast<jlong>(frame->sequence),
            reinterpret_cast<jlong>(frame->data),
            static_cast<jint>(frame->size),
            mediaFormat,
            frameFormat);
}

jobject DecoderState::CreateJavaMediaFormat(JNIEnv* env) const {
    jclass mediaFormatClass = env->FindClass("android/media/MediaFormat");
    if (mediaFormatClass == nullptr) {
        return nullptr;
    }

    jmethodID createVideoFormat = env->GetStaticMethodID(
            mediaFormatClass,
            "createVideoFormat",
            "(Ljava/lang/String;II)Landroid/media/MediaFormat;");
    if (createVideoFormat == nullptr) {
        return nullptr;
    }

    jstring mime = env->NewStringUTF(kMimeAvc);
    jobject mediaFormat = env->CallStaticObjectMethod(
            mediaFormatClass,
            createVideoFormat,
            mime,
            static_cast<jint>(width_),
            static_cast<jint>(height_));
    env->DeleteLocalRef(mime);
    if (mediaFormat == nullptr) {
        return nullptr;
    }

    jmethodID setInteger = env->GetMethodID(
            mediaFormatClass,
            "setInteger",
            "(Ljava/lang/String;I)V");
    if (setInteger == nullptr) {
        return mediaFormat;
    }

    const char* keys[] = {
            "stride",
            "slice-height",
            "color-format"
    };
    int values[] = {
            stride_,
            sliceHeight_,
            colorFormat_
    };

    for (int i = 0; i < 3; ++i) {
        jstring key = env->NewStringUTF(keys[i]);
        env->CallVoidMethod(mediaFormat, setInteger, key, static_cast<jint>(values[i]));
        env->DeleteLocalRef(key);
    }

    return mediaFormat;
}

void DecoderState::ReadOutputFormat() {
    AMediaFormat* outputFormat = AMediaCodec_getOutputFormat(codec_);
    if (outputFormat == nullptr) {
        return;
    }

    int32_t value = 0;
    if (AMediaFormat_getInt32(outputFormat, "stride", &value)) {
        stride_ = value;
    }
    if (AMediaFormat_getInt32(outputFormat, "slice-height", &value)) {
        sliceHeight_ = value;
    }
    if (AMediaFormat_getInt32(outputFormat, "color-format", &value)) {
        colorFormat_ = value;
    }

    __android_log_print(
            ANDROID_LOG_DEBUG,
            kDecoderTag,
            "Native H.264 decoder output format. width=%d, height=%d, stride=%d, sliceHeight=%d, colorFormat=%d",
            width_,
            height_,
            stride_,
            sliceHeight_,
            colorFormat_);

    AMediaFormat_delete(outputFormat);
}

std::vector<std::vector<uint8_t>> DecoderState::SplitAnnexBAccessUnits(
        const uint8_t* data,
        size_t size) const {
    std::vector<NalUnitView> nalUnits;
    std::vector<int> startCodes;

    int offset = 0;
    while (true) {
        int start = FindStartCode(data, size, offset);
        if (start < 0) {
            break;
        }
        startCodes.push_back(start);
        offset = start + StartCodeLength(data, start);
    }

    for (size_t i = 0; i < startCodes.size(); ++i) {
        int start = startCodes[i];
        int nextStart = i + 1 < startCodes.size()
                ? startCodes[i + 1]
                : static_cast<int>(size);
        int nalHeaderIndex = start + StartCodeLength(data, start);
        if (nalHeaderIndex >= nextStart) {
            continue;
        }

        NalUnitView nalUnit;
        nalUnit.start = start;
        nalUnit.end = nextStart;
        nalUnit.nalHeaderIndex = nalHeaderIndex;
        nalUnit.type = data[nalHeaderIndex] & 0x1f;
        nalUnits.push_back(nalUnit);
    }

    std::vector<std::vector<uint8_t>> accessUnits;
    if (nalUnits.empty()) {
        accessUnits.emplace_back(data, data + size);
        return accessUnits;
    }

    std::vector<uint8_t> parameterSets;
    std::vector<uint8_t> currentAccessUnit;
    bool currentAccessUnitHasVcl = false;

    for (const NalUnitView& nalUnit : nalUnits) {
        bool vclNal = nalUnit.type == 1 || nalUnit.type == 5;

        if (nalUnit.type == 7 || nalUnit.type == 8) {
            parameterSets.insert(parameterSets.end(), data + nalUnit.start, data + nalUnit.end);
            continue;
        }

        if (vclNal && currentAccessUnitHasVcl && IsFirstSlice(data, nalUnit)) {
            accessUnits.push_back(std::move(currentAccessUnit));
            currentAccessUnit.clear();
            currentAccessUnitHasVcl = false;
        }

        if (currentAccessUnit.empty() && !parameterSets.empty()) {
            currentAccessUnit.insert(currentAccessUnit.end(), parameterSets.begin(), parameterSets.end());
        }

        currentAccessUnit.insert(currentAccessUnit.end(), data + nalUnit.start, data + nalUnit.end);

        if (vclNal) {
            currentAccessUnitHasVcl = true;
        }
    }

    if (!currentAccessUnit.empty()) {
        accessUnits.push_back(std::move(currentAccessUnit));
    }

    return accessUnits;
}

DecoderState* DecoderFromHandle(jlong handle) {
    return reinterpret_cast<DecoderState*>(handle);
}

}  // namespace webstream::h264
