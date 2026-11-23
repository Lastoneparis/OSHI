// oshi_codec_jni.cpp - JNI bridge for com.oshi.messenger.service.audio.OshiCodec
//
// This file is intentionally tiny: it forwards each Kotlin method call to the
// corresponding C entry point in oshi_codec.h. The codec context is stored as
// an opaque jlong handle held by the Kotlin wrapper.
//
// This binding lives in liboshi_codec.so. It does NOT modify llama_jni.cpp.

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>

#include "oshi_codec.h"

#define LOG_TAG "OshiCodecJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
inline oshi_codec_ctx* handle_to_ctx(jlong handle) {
    return reinterpret_cast<oshi_codec_ctx*>(static_cast<uintptr_t>(handle));
}
inline jlong ctx_to_handle(oshi_codec_ctx* ctx) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(ctx));
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_oshi_messenger_service_audio_OshiCodec_nativeCreate(
        JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate, jint channels) {
    oshi_codec_ctx* ctx = oshi_codec_create(static_cast<int>(sampleRate),
                                            static_cast<int>(channels));
    if (!ctx) {
        LOGE("oshi_codec_create failed (sr=%d ch=%d)", sampleRate, channels);
        return 0;
    }
    return ctx_to_handle(ctx);
}

JNIEXPORT void JNICALL
Java_com_oshi_messenger_service_audio_OshiCodec_nativeDestroy(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    oshi_codec_ctx* ctx = handle_to_ctx(handle);
    if (ctx) oshi_codec_destroy(ctx);
}

JNIEXPORT void JNICALL
Java_com_oshi_messenger_service_audio_OshiCodec_nativeReset(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    oshi_codec_ctx* ctx = handle_to_ctx(handle);
    if (ctx) oshi_codec_reset(ctx);
}

JNIEXPORT void JNICALL
Java_com_oshi_messenger_service_audio_OshiCodec_nativeSetBitrate(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint bitrateBps) {
    oshi_codec_ctx* ctx = handle_to_ctx(handle);
    if (ctx) oshi_codec_set_bitrate(ctx, static_cast<int>(bitrateBps));
}

JNIEXPORT jbyteArray JNICALL
Java_com_oshi_messenger_service_audio_OshiCodec_nativeEncode(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jshortArray pcmArr) {
    oshi_codec_ctx* ctx = handle_to_ctx(handle);
    if (!ctx || !pcmArr) return nullptr;

    const jsize pcmLen = env->GetArrayLength(pcmArr);
    if (pcmLen != OSHI_FRAME_SIZE) {
        LOGE("encode: expected %d samples, got %d", OSHI_FRAME_SIZE, pcmLen);
        return nullptr;
    }

    jshort* pcm = env->GetShortArrayElements(pcmArr, nullptr);
    if (!pcm) return nullptr;

    uint8_t out[OSHI_MAX_FRAME_BYTES];
    // jshort is guaranteed to be 16 bits signed by JNI spec; safe to cast.
    const int written = oshi_codec_encode(
            ctx,
            reinterpret_cast<const int16_t*>(pcm),
            static_cast<int>(pcmLen),
            out,
            static_cast<int>(sizeof(out)));

    env->ReleaseShortArrayElements(pcmArr, pcm, JNI_ABORT);

    if (written < 0) {
        LOGE("oshi_codec_encode returned %d", written);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(written);
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, written, reinterpret_cast<jbyte*>(out));
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_oshi_messenger_service_audio_OshiCodec_nativeDecode(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray bytesArr) {
    oshi_codec_ctx* ctx = handle_to_ctx(handle);
    if (!ctx || !bytesArr) return nullptr;

    const jsize inLen = env->GetArrayLength(bytesArr);
    jbyte* bytes = env->GetByteArrayElements(bytesArr, nullptr);
    if (!bytes) return nullptr;

    int16_t pcm[OSHI_FRAME_SIZE];
    const int decoded = oshi_codec_decode(
            ctx,
            reinterpret_cast<const uint8_t*>(bytes),
            static_cast<int>(inLen),
            pcm,
            OSHI_FRAME_SIZE);

    env->ReleaseByteArrayElements(bytesArr, bytes, JNI_ABORT);

    if (decoded < 0) {
        LOGE("oshi_codec_decode returned %d", decoded);
        return nullptr;
    }
    if (decoded != OSHI_FRAME_SIZE) {
        LOGE("oshi_codec_decode produced %d samples, expected %d",
             decoded, OSHI_FRAME_SIZE);
        return nullptr;
    }

    jshortArray result = env->NewShortArray(OSHI_FRAME_SIZE);
    if (!result) return nullptr;
    env->SetShortArrayRegion(result, 0, OSHI_FRAME_SIZE,
                             reinterpret_cast<jshort*>(pcm));
    return result;
}

} // extern "C"
