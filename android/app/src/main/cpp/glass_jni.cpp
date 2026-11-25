#include <atomic>
#include <jni.h>
#include "glass_core.h"

namespace {
std::atomic<uint64_t> g_latest_version{0};
}

extern "C" JNIEXPORT void JNICALL
Java_com_oclock_liquidglass_NativeGlass_setLatestVersion(JNIEnv*, jclass, jlong version) {
    g_latest_version.store(static_cast<uint64_t>(version), std::memory_order_relaxed);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_oclock_liquidglass_NativeGlass_compute(JNIEnv* env, jclass, jbyteArray packet, jint offset, jint w, jint h, jdoubleArray values, jlong version) {
    try {
        if (!packet || !values || env->GetArrayLength(values) != 7) return nullptr;
        const jsize totalLen = env->GetArrayLength(packet);
        if (w < 4 || h < 4 || int64_t(w) * h > 3000000) return nullptr;
        const size_t alphaSize = size_t(w) * size_t(h);
        if (offset < 0 || int64_t(offset) + int64_t(alphaSize) > totalLen) return nullptr;

        jdouble p[7];
        env->GetDoubleArrayRegion(values, 0, 7, p);
        std::vector<uint8_t> input(alphaSize);
        env->GetByteArrayRegion(packet, offset, jsize(alphaSize), reinterpret_cast<jbyte*>(input.data()));

        glass::Params params;
        params.ss = p[0]; params.ior = p[1]; params.thickness = p[2]; params.bezel = p[3];
        params.shadowThickness = p[4]; params.shadowAngle = p[5]; params.surface = int(p[6]);

        auto result = glass::compute(input.data(), input.size(), w, h, params, false,
                                     &g_latest_version, static_cast<uint64_t>(version));
        if (result.cancelled) return nullptr;

        auto cls = env->FindClass("[B");
        auto output = env->NewObjectArray(3, cls, nullptr);
        auto add = [&](int i, const uint8_t* data, size_t size) {
            auto a = env->NewByteArray(jsize(size));
            env->SetByteArrayRegion(a, 0, jsize(size), reinterpret_cast<const jbyte*>(data));
            env->SetObjectArrayElement(output, i, a);
            env->DeleteLocalRef(a);
        };
        add(0, result.displacement.data(), result.displacement.size());
        add(1, result.shadow.data(), result.shadow.size());
        add(2, reinterpret_cast<const uint8_t*>(&result.maxAbsCss), sizeof(double));
        return output;
    } catch (const std::exception&) {
        return nullptr;
    }
}
