#include <android/bitmap.h>
#include <jni.h>
#include <jxl/codestream_header.h>
#include <jxl/color_encoding.h>
#include <jxl/decode.h>
#include <jxl/encode.h>
#include <jxl/thread_parallel_runner.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <string>
#include <vector>

namespace {

struct JxlEncoderDeleter {
    void operator()(JxlEncoder* encoder) const {
        JxlEncoderDestroy(encoder);
    }
};

struct JxlDecoderDeleter {
    void operator()(JxlDecoder* decoder) const {
        JxlDecoderDestroy(decoder);
    }
};

struct JxlRunnerDeleter {
    void operator()(void* runner) const {
        JxlThreadParallelRunnerDestroy(runner);
    }
};

using EncoderPtr = std::unique_ptr<JxlEncoder, JxlEncoderDeleter>;
using DecoderPtr = std::unique_ptr<JxlDecoder, JxlDecoderDeleter>;
using RunnerPtr = std::unique_ptr<void, JxlRunnerDeleter>;

thread_local std::string last_error;

void SetLastError(const char* message) {
    last_error = message == nullptr ? "" : message;
}

bool CopyBitmapToRgb(JNIEnv* env, jobject bitmap, std::vector<uint8_t>* rgb,
        uint32_t* width, uint32_t* height) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        SetLastError("AndroidBitmap_getInfo failed");
        return false;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        SetLastError("bitmap format is not RGBA_8888");
        return false;
    }
    if (info.width == 0 || info.height == 0) {
        SetLastError("bitmap has empty dimensions");
        return false;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
            pixels == nullptr) {
        SetLastError("AndroidBitmap_lockPixels failed");
        return false;
    }

    const size_t rgba_row_bytes = static_cast<size_t>(info.width) * 4;
    const size_t rgb_row_bytes = static_cast<size_t>(info.width) * 3;
    rgb->resize(rgb_row_bytes * info.height);
    const auto* source = static_cast<const uint8_t*>(pixels);
    for (uint32_t y = 0; y < info.height; ++y) {
        const uint8_t* source_row = source + static_cast<size_t>(info.stride) * y;
        uint8_t* output_row = rgb->data() + rgb_row_bytes * y;
        for (uint32_t x = 0; x < info.width; ++x) {
            output_row[x * 3] = source_row[x * 4];
            output_row[x * 3 + 1] = source_row[x * 4 + 1];
            output_row[x * 3 + 2] = source_row[x * 4 + 2];
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    *width = info.width;
    *height = info.height;
    return true;
}

jbyteArray ToByteArray(JNIEnv* env, const std::vector<uint8_t>& data, size_t size) {
    if (size == 0 || size > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        SetLastError("encoded output is empty or too large");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    if (result == nullptr) {
        SetLastError("NewByteArray failed");
        return nullptr;
    }
    env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(size),
            reinterpret_cast<const jbyte*>(data.data()));
    return result;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_w3n_webstream_NativeJxlCodec_nativeIsAvailable(JNIEnv*, jclass) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_w3n_webstream_NativeJxlCodec_nativeEncode(
        JNIEnv* env,
        jclass,
        jobject bitmap,
        jint quality) {
    SetLastError("");
    if (bitmap == nullptr) {
        SetLastError("bitmap is null");
        return nullptr;
    }

    std::vector<uint8_t> rgb;
    uint32_t width = 0;
    uint32_t height = 0;
    if (!CopyBitmapToRgb(env, bitmap, &rgb, &width, &height)) {
        return nullptr;
    }

    EncoderPtr encoder(JxlEncoderCreate(nullptr));
    RunnerPtr runner(JxlThreadParallelRunnerCreate(
            nullptr,
            JxlThreadParallelRunnerDefaultNumWorkerThreads()));
    if (!encoder || !runner) {
        SetLastError("JxlEncoderCreate or JxlThreadParallelRunnerCreate failed");
        return nullptr;
    }
    if (JxlEncoderSetParallelRunner(
            encoder.get(),
            JxlThreadParallelRunner,
            runner.get()) != JXL_ENC_SUCCESS) {
        SetLastError("JxlEncoderSetParallelRunner failed");
        return nullptr;
    }

    JxlBasicInfo basic_info;
    JxlEncoderInitBasicInfo(&basic_info);
    basic_info.xsize = width;
    basic_info.ysize = height;
    basic_info.bits_per_sample = 8;
    basic_info.exponent_bits_per_sample = 0;
    basic_info.num_color_channels = 3;
    basic_info.uses_original_profile = JXL_FALSE;

    if (JxlEncoderSetBasicInfo(encoder.get(), &basic_info) != JXL_ENC_SUCCESS) {
        SetLastError("JxlEncoderSetBasicInfo failed");
        return nullptr;
    }

    JxlColorEncoding color_encoding;
    JxlColorEncodingSetToSRGB(&color_encoding, JXL_FALSE);
    if (JxlEncoderSetColorEncoding(encoder.get(), &color_encoding) != JXL_ENC_SUCCESS) {
        SetLastError("JxlEncoderSetColorEncoding failed");
        return nullptr;
    }

    JxlPixelFormat pixel_format = {
            3,
            JXL_TYPE_UINT8,
            JXL_NATIVE_ENDIAN,
            0,
    };
    JxlEncoderFrameSettings* frame_settings =
            JxlEncoderFrameSettingsCreate(encoder.get(), nullptr);
    if (frame_settings == nullptr) {
        SetLastError("JxlEncoderFrameSettingsCreate failed");
        return nullptr;
    }

    const int clamped_quality = std::max(0, std::min(100, static_cast<int>(quality)));
    if (JxlEncoderSetFrameDistance(
            frame_settings,
            JxlEncoderDistanceFromQuality(static_cast<float>(clamped_quality))) != JXL_ENC_SUCCESS) {
        SetLastError("JxlEncoderSetFrameDistance failed");
        return nullptr;
    }
    if (JxlEncoderFrameSettingsSetOption(
            frame_settings,
            JXL_ENC_FRAME_SETTING_EFFORT,
            1) != JXL_ENC_SUCCESS) {
        SetLastError("JxlEncoderFrameSettingsSetOption effort failed");
        return nullptr;
    }

    if (JxlEncoderAddImageFrame(
            frame_settings,
            &pixel_format,
            rgb.data(),
            rgb.size()) != JXL_ENC_SUCCESS) {
        SetLastError("JxlEncoderAddImageFrame failed");
        return nullptr;
    }
    JxlEncoderCloseInput(encoder.get());

    std::vector<uint8_t> output(64 * 1024);
    uint8_t* next_out = output.data();
    size_t avail_out = output.size();
    JxlEncoderStatus status = JXL_ENC_NEED_MORE_OUTPUT;
    while (status == JXL_ENC_NEED_MORE_OUTPUT) {
        status = JxlEncoderProcessOutput(encoder.get(), &next_out, &avail_out);
        if (status == JXL_ENC_NEED_MORE_OUTPUT) {
            const size_t used = static_cast<size_t>(next_out - output.data());
            output.resize(output.size() * 2);
            next_out = output.data() + used;
            avail_out = output.size() - used;
        }
    }

    if (status != JXL_ENC_SUCCESS) {
        SetLastError("JxlEncoderProcessOutput failed");
        return nullptr;
    }

    const size_t used = static_cast<size_t>(next_out - output.data());
    return ToByteArray(env, output, used);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_w3n_webstream_NativeJxlCodec_nativeDecode(
        JNIEnv* env,
        jclass,
        jbyteArray encoded_data) {
    SetLastError("");
    if (encoded_data == nullptr) {
        SetLastError("encoded data is null");
        return nullptr;
    }

    const jsize encoded_size = env->GetArrayLength(encoded_data);
    if (encoded_size <= 0) {
        SetLastError("encoded data is empty");
        return nullptr;
    }

    std::vector<uint8_t> input(static_cast<size_t>(encoded_size));
    env->GetByteArrayRegion(
            encoded_data,
            0,
            encoded_size,
            reinterpret_cast<jbyte*>(input.data()));

    DecoderPtr decoder(JxlDecoderCreate(nullptr));
    RunnerPtr runner(JxlThreadParallelRunnerCreate(
            nullptr,
            JxlThreadParallelRunnerDefaultNumWorkerThreads()));
    if (!decoder || !runner) {
        SetLastError("JxlDecoderCreate or JxlThreadParallelRunnerCreate failed");
        return nullptr;
    }
    if (JxlDecoderSetParallelRunner(
            decoder.get(),
            JxlThreadParallelRunner,
            runner.get()) != JXL_DEC_SUCCESS) {
        SetLastError("JxlDecoderSetParallelRunner failed");
        return nullptr;
    }
    if (JxlDecoderSubscribeEvents(
            decoder.get(),
            JXL_DEC_BASIC_INFO | JXL_DEC_FULL_IMAGE) != JXL_DEC_SUCCESS) {
        SetLastError("JxlDecoderSubscribeEvents failed");
        return nullptr;
    }

    JxlDecoderSetInput(decoder.get(), input.data(), input.size());
    JxlDecoderCloseInput(decoder.get());

    JxlBasicInfo basic_info;
    bool has_basic_info = false;
    std::vector<uint8_t> rgba;
    JxlPixelFormat pixel_format = {
            4,
            JXL_TYPE_UINT8,
            JXL_NATIVE_ENDIAN,
            0,
    };

    while (true) {
        JxlDecoderStatus status = JxlDecoderProcessInput(decoder.get());
        if (status == JXL_DEC_ERROR || status == JXL_DEC_NEED_MORE_INPUT) {
            SetLastError("JxlDecoderProcessInput failed");
            return nullptr;
        }
        if (status == JXL_DEC_BASIC_INFO) {
            if (JxlDecoderGetBasicInfo(decoder.get(), &basic_info) != JXL_DEC_SUCCESS) {
                SetLastError("JxlDecoderGetBasicInfo failed");
                return nullptr;
            }
            has_basic_info = true;
        } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            size_t buffer_size = 0;
            if (JxlDecoderImageOutBufferSize(
                    decoder.get(),
                    &pixel_format,
                    &buffer_size) != JXL_DEC_SUCCESS ||
                    buffer_size == 0) {
                SetLastError("JxlDecoderImageOutBufferSize failed");
                return nullptr;
            }
            rgba.resize(buffer_size);
            if (JxlDecoderSetImageOutBuffer(
                    decoder.get(),
                    &pixel_format,
                    rgba.data(),
                    rgba.size()) != JXL_DEC_SUCCESS) {
                SetLastError("JxlDecoderSetImageOutBuffer failed");
                return nullptr;
            }
        } else if (status == JXL_DEC_FULL_IMAGE) {
            break;
        } else if (status == JXL_DEC_SUCCESS) {
            break;
        }
    }

    if (!has_basic_info || rgba.empty() ||
            basic_info.xsize > static_cast<uint32_t>(std::numeric_limits<jint>::max()) ||
            basic_info.ysize > static_cast<uint32_t>(std::numeric_limits<jint>::max())) {
        SetLastError("decoded image is empty or too large");
        return nullptr;
    }

    jbyteArray pixels = ToByteArray(env, rgba, rgba.size());
    if (pixels == nullptr) {
        return nullptr;
    }

    jclass decoded_class = env->FindClass("com/w3n/webstream/NativeJxlCodec$DecodedImage");
    if (decoded_class == nullptr) {
        SetLastError("DecodedImage class not found");
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(decoded_class, "<init>", "(II[B)V");
    if (constructor == nullptr) {
        SetLastError("DecodedImage constructor not found");
        return nullptr;
    }
    return env->NewObject(
            decoded_class,
            constructor,
            static_cast<jint>(basic_info.xsize),
            static_cast<jint>(basic_info.ysize),
            pixels);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_w3n_webstream_NativeJxlCodec_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(last_error.c_str());
}
