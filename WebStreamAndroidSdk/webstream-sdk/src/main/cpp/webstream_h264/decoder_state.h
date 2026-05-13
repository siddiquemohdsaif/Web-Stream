#pragma once

#include <jni.h>
#include <media/NdkMediaCodec.h>

#include <cstdint>
#include <string>
#include <vector>

namespace webstream::h264 {

struct DecodedNativeFrame {
    int width = 0;
    int height = 0;
    int stride = 0;
    int sliceHeight = 0;
    int colorFormat = 0;
    int64_t timestampNs = 0;
    int64_t sequence = 0;
    uint8_t* data = nullptr;
    int size = 0;
};

class DecoderState {
public:
    DecoderState(int width, int height, int frameRateFps, int maxQueuedFrames);
    ~DecoderState();

    bool Start(std::string* error);
    void Stop();
    void Release();
    bool Decode(JNIEnv* env, jbyteArray encodedChunk, jobjectArray* result, std::string* error);

private:
    bool QueueAccessUnit(const uint8_t* data, size_t size, int64_t presentationTimeUs, std::string* error);
    bool Drain(
            JNIEnv* env,
            int expectedOutputFrames,
            std::vector<DecodedNativeFrame>* frames,
            std::string* error);
    jobjectArray ToJavaFrames(JNIEnv* env, std::vector<DecodedNativeFrame>* frames);
    jobject CreateJavaFrame(JNIEnv* env, DecodedNativeFrame* frame);
    jobject CreateJavaMediaFormat(JNIEnv* env) const;
    void ReadOutputFormat();
    std::vector<std::vector<uint8_t>> SplitAnnexBAccessUnits(const uint8_t* data, size_t size) const;

    int width_;
    int height_;
    int frameRateFps_;
    int maxQueuedFrames_;
    int stride_;
    int sliceHeight_;
    int colorFormat_;
    int64_t decodedFrameSequence_ = 0;
    bool started_ = false;
    bool released_ = false;
    AMediaCodec* codec_ = nullptr;
};

DecoderState* DecoderFromHandle(jlong handle);

}  // namespace webstream::h264
