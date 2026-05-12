#pragma once

#include <jni.h>
#include <media/NdkMediaCodec.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace webstream::h264 {

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
            bool requestKeyFrameEveryBatch);
    ~EncoderState();

    bool Start(std::string* error);
    void Stop();
    void Release();
    bool Encode(JNIEnv* env, jbyteArray inputYuvData, int64_t timestampMs, jobject* result, std::string* error);
    bool Flush(JNIEnv* env, int64_t timestampMs, jobject* result, std::string* error);
    bool IsStarted() const;
    int64_t TotalInputFrames() const;
    int64_t BatchSequence() const;

private:
    bool EncodeBytes(
            const uint8_t* input,
            size_t inputSize,
            int64_t timestampMs,
            jobject* result,
            std::string* error,
            JNIEnv* env);
    bool Drain(bool waitForOutput, std::string* error);
    bool WaitForCurrentBatchEncodedAndDispatch(
            JNIEnv* env,
            bool partialBatch,
            int64_t fallbackTimestampMs,
            jobject* result,
            std::string* error);
    jobject DispatchCurrentBatch(
            JNIEnv* env,
            int64_t timestampMs,
            int64_t batchDurationNs,
            bool partialBatch);
    void RememberCodecConfigFromFormat();
    void RememberCodecConfigFromAnnexB(const std::vector<uint8_t>& encoded);
    std::vector<uint8_t> ToNv12(const uint8_t* input, size_t inputSize) const;
    void RequestKeyFrame();
    void StartBatchIfNeeded();
    bool HasActiveBatch() const;
    void BeginEncodeTiming();
    void EndEncodeTiming();
    int64_t GetAndFreezeCurrentBatchDurationNs();
    int ExpectedYuvSize() const;

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

EncoderState* FromHandle(jlong handle);

}  // namespace webstream::h264
