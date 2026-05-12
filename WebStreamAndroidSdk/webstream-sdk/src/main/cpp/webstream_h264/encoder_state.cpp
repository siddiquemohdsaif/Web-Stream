#include "encoder_state.h"

#include "common.h"

#include <android/log.h>
#include <media/NdkMediaFormat.h>

#include <algorithm>
#include <cstring>

namespace webstream::h264 {

EncoderState::EncoderState(
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

EncoderState::~EncoderState() {
    Release();
}

bool EncoderState::Start(std::string* error) {
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

void EncoderState::Stop() {
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

void EncoderState::Release() {
    if (released_) {
        return;
    }
    Stop();
    released_ = true;
}

bool EncoderState::Encode(
        JNIEnv* env,
        jbyteArray inputYuvData,
        int64_t timestampMs,
        jobject* result,
        std::string* error) {
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

bool EncoderState::Flush(JNIEnv* env, int64_t timestampMs, jobject* result, std::string* error) {
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

bool EncoderState::IsStarted() const {
    return started_;
}

int64_t EncoderState::TotalInputFrames() const {
    return totalInputFrames_;
}

int64_t EncoderState::BatchSequence() const {
    return batchSequence_;
}

bool EncoderState::EncodeBytes(
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

bool EncoderState::Drain(bool waitForOutput, std::string* error) {
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

bool EncoderState::WaitForCurrentBatchEncodedAndDispatch(
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

jobject EncoderState::DispatchCurrentBatch(
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

void EncoderState::RememberCodecConfigFromFormat() {
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

void EncoderState::RememberCodecConfigFromAnnexB(const std::vector<uint8_t>& encoded) {
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

std::vector<uint8_t> EncoderState::ToNv12(const uint8_t* input, size_t inputSize) const {
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

void EncoderState::RequestKeyFrame() {
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

void EncoderState::StartBatchIfNeeded() {
    if (!HasActiveBatch()) {
        inputFramesInBatch_ = 0;
        encodedFramesInBatch_ = 0;
        currentBatchEncodeComputeTimeNs_ = 0;
        activeEncodeTimingStartNs_ = 0;
        lastEncodedTimestampMs_ = 0;
        batchOutput_.clear();
    }
}

bool EncoderState::HasActiveBatch() const {
    return inputFramesInBatch_ > 0
            || encodedFramesInBatch_ > 0
            || !batchOutput_.empty();
}

void EncoderState::BeginEncodeTiming() {
    if (activeEncodeTimingStartNs_ == 0) {
        activeEncodeTimingStartNs_ = NowNs();
    }
}

void EncoderState::EndEncodeTiming() {
    if (activeEncodeTimingStartNs_ > 0 && HasActiveBatch()) {
        int64_t nowNs = NowNs();
        currentBatchEncodeComputeTimeNs_ += nowNs - activeEncodeTimingStartNs_;
    }
    activeEncodeTimingStartNs_ = 0;
}

int64_t EncoderState::GetAndFreezeCurrentBatchDurationNs() {
    if (activeEncodeTimingStartNs_ > 0) {
        int64_t nowNs = NowNs();
        currentBatchEncodeComputeTimeNs_ += nowNs - activeEncodeTimingStartNs_;
        activeEncodeTimingStartNs_ = nowNs;
    }
    return currentBatchEncodeComputeTimeNs_;
}

int EncoderState::ExpectedYuvSize() const {
    return width_ * height_ * 3 / 2;
}

EncoderState* FromHandle(jlong handle) {
    return reinterpret_cast<EncoderState*>(handle);
}

}  // namespace webstream::h264
