#include <jni.h>

namespace {
constexpr const char* kUnavailableReason = "native library webstream_jxl was built without libjxl";
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_w3n_webstream_NativeJxlCodec_nativeIsAvailable(JNIEnv*, jclass) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_w3n_webstream_NativeJxlCodec_nativeEncode(JNIEnv*, jclass, jobject, jint) {
    return nullptr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_w3n_webstream_NativeJxlCodec_nativeDecode(JNIEnv*, jclass, jbyteArray) {
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_w3n_webstream_NativeJxlCodec_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(kUnavailableReason);
}
