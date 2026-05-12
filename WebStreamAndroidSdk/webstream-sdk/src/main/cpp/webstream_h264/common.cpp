#include "common.h"

#include <algorithm>
#include <chrono>

namespace webstream::h264 {

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

}  // namespace webstream::h264
