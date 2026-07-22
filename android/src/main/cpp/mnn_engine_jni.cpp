#include <jni.h>

#include <string>

#include "MNN/Interpreter.hpp"
#include "mnn_llm_session_adapter.hpp"
#include "nlohmann/json.hpp"

#ifndef MNN_COMMIT
#define MNN_COMMIT "unknown"
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_com_arkanefans_mnn_1engine_runtime_MnnNativeBridge_nativeGetVersion(
        JNIEnv* env,
        jobject /* thiz */) {
    const std::string version =
            std::string(MNN::getVersion()) + " (" + MNN_COMMIT + ")";
    return env->NewStringUTF(version.c_str());
}

namespace {

void throwJava(JNIEnv* env, const char* className, const std::string& message) {
    jclass clazz = env->FindClass(className);
    if (clazz != nullptr) env->ThrowNew(clazz, message.c_str());
}

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

MnnLlmSessionAdapter* sessionFrom(jlong handle) {
    return reinterpret_cast<MnnLlmSessionAdapter*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeCreate(
        JNIEnv* env,
        jobject /* thiz */,
        jstring configPath,
        jstring configJson) {
    try {
        auto session = std::make_unique<MnnLlmSessionAdapter>(
                toString(env, configPath),
                toString(env, configJson));
        std::string error;
        if (!session->load(&error)) {
            throw std::runtime_error(error);
        }
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeGenerate(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jstring messagesJson,
        jstring requestConfigJson,
        jint maxTokens,
        jobject callback) {
    auto* session = sessionFrom(handle);
    if (session == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "Native session is null");
        return nullptr;
    }
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
    if (onToken == nullptr) return nullptr;
    try {
        const auto metrics = session->generate(
                toString(env, messagesJson),
                toString(env, requestConfigJson),
                maxTokens,
                [env, callback, onToken](const std::string& token) {
                    jstring value = env->NewStringUTF(token.c_str());
                    const jboolean stop = env->CallBooleanMethod(callback, onToken, value);
                    env->DeleteLocalRef(value);
                    if (env->ExceptionCheck()) return true;
                    return stop == JNI_TRUE;
                });
        nlohmann::json result = {
            {"prompt_tokens", metrics.promptTokens},
            {"completion_tokens", metrics.completionTokens},
            {"prefill_us", metrics.prefillUs},
            {"decode_us", metrics.decodeUs},
            {"sample_us", metrics.sampleUs},
            {"finish_reason", metrics.finishReason},
        };
        return env->NewStringUTF(result.dump().c_str());
    } catch (const std::exception& error) {
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeCancel(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    if (auto* session = sessionFrom(handle)) session->cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeReset(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    if (auto* session = sessionFrom(handle)) session->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_arkanefans_mnn_1engine_runtime_MnnNativeSession_nativeRelease(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    delete sessionFrom(handle);
}
