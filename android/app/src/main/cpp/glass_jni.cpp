#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <vector>
#include <android/bitmap.h>
#include <jni.h>
#include "glass_core.h"

namespace {
std::atomic<uint64_t> g_latest_version{0};

inline float clamp01f(float x) { return x < 0.f ? 0.f : (x > 1.f ? 1.f : x); }

inline void sampleBilinearWall(const uint8_t* wall, int ww, int wh, uint32_t strideBytes,
                               float sx, float sy, float& r, float& g, float& b) {
    if (sx < 0.f) sx = 0.f; else if (sx > ww - 1.001f) sx = ww - 1.001f;
    if (sy < 0.f) sy = 0.f; else if (sy > wh - 1.001f) sy = wh - 1.001f;
    int x0 = int(sx), y0 = int(sy);
    int x1 = std::min(ww - 1, x0 + 1), y1 = std::min(wh - 1, y0 + 1);
    float fx = sx - x0, fy = sy - y0;
    float w00 = (1.f - fx) * (1.f - fy), w10 = fx * (1.f - fy);
    float w01 = (1.f - fx) * fy,         w11 = fx * fy;
    const uint8_t* p00 = wall + size_t(y0) * strideBytes + size_t(x0) * 4;
    const uint8_t* p10 = wall + size_t(y0) * strideBytes + size_t(x1) * 4;
    const uint8_t* p01 = wall + size_t(y1) * strideBytes + size_t(x0) * 4;
    const uint8_t* p11 = wall + size_t(y1) * strideBytes + size_t(x1) * 4;
    r = p00[0]*w00 + p10[0]*w10 + p01[0]*w01 + p11[0]*w11;
    g = p00[1]*w00 + p10[1]*w10 + p01[1]*w01 + p11[1]*w11;
    b = p00[2]*w00 + p10[2]*w10 + p01[2]*w01 + p11[2]*w11;
}

inline float sdRoundBox(float px, float py, float hw, float hh, float r) {
    float qx = std::fabs(px) - hw + r;
    float qy = std::fabs(py) - hh + r;
    float mx = std::max(qx, 0.f), my = std::max(qy, 0.f);
    return std::hypot(mx, my) + std::min(std::max(qx, qy), 0.f) - r;
}
} // namespace

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
            env->SetByteArrayRegion(a, 0, jsize(size), reinterpret_cast<jbyte*>(const_cast<uint8_t*>(data)));
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

/* Direct Bitmap lock compute for Pure Native Android: zero byte-array copy,
   zero PNG compression, outputs (R=dispX, G=dispY, B=interiorDepth*8, A=255). */
extern "C" JNIEXPORT jdouble JNICALL
Java_com_oclock_liquidglass_NativeGlass_computeClockField(
        JNIEnv* env, jclass, jobject alphaBmp, jobject outFieldBmp,
        jdouble ss, jdouble ior, jdouble thickness, jdouble bezel, jint surfaceKind, jlong version) {
    if (!alphaBmp || !outFieldBmp) return -1.0;
    AndroidBitmapInfo aInfo{}, fInfo{};
    if (AndroidBitmap_getInfo(env, alphaBmp, &aInfo) != ANDROID_BITMAP_RESULT_SUCCESS) return -1.0;
    if (AndroidBitmap_getInfo(env, outFieldBmp, &fInfo) != ANDROID_BITMAP_RESULT_SUCCESS) return -1.0;
    if (aInfo.format != ANDROID_BITMAP_FORMAT_A_8 || fInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -1.0;
    if (aInfo.width != fInfo.width || aInfo.height != fInfo.height) return -1.0;

    const int w = int(aInfo.width), h = int(aInfo.height);
    if (w < 4 || h < 4 || int64_t(w) * h > 3000000) return -1.0;

    void* aPtr = nullptr;
    void* fPtr = nullptr;
    if (AndroidBitmap_lockPixels(env, alphaBmp, &aPtr) != ANDROID_BITMAP_RESULT_SUCCESS) return -1.0;
    if (AndroidBitmap_lockPixels(env, outFieldBmp, &fPtr) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, alphaBmp);
        return -1.0;
    }

    double maxAbsCss = -1.0;
    try {
        std::vector<uint8_t> alpha(size_t(w) * h);
        const auto* aBytes = static_cast<const uint8_t*>(aPtr);
        for (int y = 0; y < h; y++) {
            const uint8_t* srcRow = aBytes + size_t(y) * aInfo.stride;
            std::copy(srcRow, srcRow + w, alpha.data() + size_t(y) * w);
        }

        const uint64_t expected = static_cast<uint64_t>(version);
        auto sdf = glass::edt(alpha.data(), w, h, true, &g_latest_version, expected);
        if (!sdf.empty()) {
            auto inside = glass::edt(alpha.data(), w, h, false, &g_latest_version, expected);
            if (!inside.empty()) {
                double maxInside = 0.0;
                const size_t count = size_t(w) * h;
                for (size_t i = 0; i < count; i++) {
                    double s = sdf[i] > 0.f ? double(sdf[i]) - 0.5 : -(double(inside[i]) - 0.5);
                    sdf[i] = float(s);
                    maxInside = std::max(maxInside, -s);
                }
                std::vector<float>().swap(inside);

                const double userBezel = std::max(2.0, bezel * ss);
                const double effBezel = std::max(2.0, std::min(userBezel, std::max(2.0, 0.5 * maxInside)));
                const double effThick = thickness * ss * (effBezel / userBezel);
                double profile[127], maxAbs = 0.0;
                for (int i = 0; i < 127; i++) {
                    double x = double(i) / 126.0, x1 = std::max(0.0, x - 0.001), x2 = std::min(1.0, x + 0.001);
                    double height = glass::surface(x, surfaceKind) * effThick;
                    double slope = ((glass::surface(x2, surfaceKind) - glass::surface(x1, surfaceKind)) / (x2 - x1)) * effThick / effBezel;
                    double len = std::hypot(-slope, 1.0), nx = -slope / len, nz = 1.0 / len, eta = 1.0 / ior;
                    double sin2 = eta * eta * (1.0 - nz * nz), disp = 0.0;
                    if (sin2 <= 1.0) {
                        double factor = eta * nz - std::sqrt(1.0 - sin2);
                        double tx = factor * nx, ty = -eta + factor * nz;
                        if (ty != 0.0) disp = (-height / ty) * tx;
                    }
                    profile[i] = disp;
                    maxAbs = std::max(maxAbs, std::abs(disp));
                }
                if (!maxAbs) maxAbs = 1e-6;
                maxAbsCss = maxAbs / ss;

                std::vector<float> smoothRing(size_t(w) * 3);
                auto rowPtr = [&](int slot) -> float* { return smoothRing.data() + size_t(slot) * w; };
                auto computeSmoothRow = [&](int ry, float* dst) {
                    int rym = std::max(0, ry - 1), ryp = std::min(h - 1, ry + 1);
                    const float* r0 = sdf.data() + size_t(rym) * w;
                    const float* r1 = sdf.data() + size_t(ry) * w;
                    const float* r2 = sdf.data() + size_t(ryp) * w;
                    for (int x = 0; x < w; x++) {
                        int xm = std::max(0, x - 1), xp = std::min(w - 1, x + 1);
                        dst[x] = float((double(r0[xm]) + 2.0*r0[x] + r0[xp] + 2.0*r1[xm] + 4.0*r1[x] + 2.0*r1[xp] + r2[xm] + 2.0*r2[x] + r2[xp]) * 0.0625);
                    }
                };
                computeSmoothRow(0, rowPtr(0));
                computeSmoothRow(std::min(h - 1, 1), rowPtr(1));

                auto* fBytes = static_cast<uint8_t*>(fPtr);
                const bool flatCenter = std::abs(profile[126]) < 1e-9;

                for (int y = 0; y < h; y++) {
                    if ((y & 31) == 0 && glass::isCancelled(&g_latest_version, expected)) {
                        maxAbsCss = -1.0;
                        break;
                    }
                    if (y >= 1 && y + 1 < h) computeSmoothRow(y + 1, rowPtr((y + 1) % 3));
                    const float* sm = rowPtr(std::max(0, y - 1) % 3);
                    const float* sc = rowPtr(y % 3);
                    const float* sp = rowPtr(std::min(h - 1, y + 1) % 3);
                    const float* sdfRow = sdf.data() + size_t(y) * w;
                    uint8_t* outRow = fBytes + size_t(y) * fInfo.stride;

                    for (int x = 0; x < w; x++) {
                        uint8_t* d = outRow + size_t(x) * 4;
                        double dd = sdfRow[x];
                        d[0] = 128; d[1] = 128;
                        d[2] = dd < 0.0 ? glass::byte((-dd) * 6.0) : 0;
                        d[3] = 255; // Opaque RGBA avoids any Android driver premultiply corruption
                        if (dd > 0.0) continue;
                        if (flatCenter && dd <= -effBezel) continue;

                        int xm = std::max(0, x - 1), xp = std::min(w - 1, x + 1);
                        double gx = (double(sc[xp]) - sc[xm]) * 0.5, gy = (double(sp[x]) - sm[x]) * 0.5;
                        double len = std::hypot(gx, gy); if (!len) len = 1.0;
                        double nx = gx / len, ny = gy / len;
                        double pos = glass::clamp(std::min(-dd, effBezel) / effBezel) * 126.0;
                        int a = int(std::floor(pos)), b = std::min(126, a + 1);
                        double t = pos - a, disp = profile[a] * (1.0 - t) + profile[b] * t;
                        d[0] = glass::byte(128.0 - nx * (disp / maxAbs) * 127.0);
                        d[1] = glass::byte(128.0 - ny * (disp / maxAbs) * 127.0);
                    }
                }
            }
        }
    } catch (...) {
        maxAbsCss = -1.0;
    }

    AndroidBitmap_unlockPixels(env, outFieldBmp);
    AndroidBitmap_unlockPixels(env, alphaBmp);
    return maxAbsCss;
}

/* API 26-32 fallback: direct native C++ clock refraction + tint + inner rim into locked ARGB Bitmap. */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_oclock_liquidglass_NativeGlass_renderClockCpuFallback(
        JNIEnv* env, jclass, jobject alphaBmp, jobject fieldBmp, jobject wallBmp, jobject outBmp,
        jfloat dstX, jfloat dstY, jfloat dstW, jfloat dstH, jfloat wallLeft, jfloat wallTop, jfloat wallScaleX, jfloat wallScaleY,
        jfloat maxAbsPx, jfloat ss,
        jint topColor, jint botColor, jfloat shadowOp, jint shadowTopColor,
        jint innerColor, jfloat innerThick, jfloat innerOp, jfloat innerAngleDeg) {
    if (!alphaBmp || !fieldBmp || !wallBmp || !outBmp) return JNI_FALSE;
    AndroidBitmapInfo aI{}, fI{}, wI{}, oI{};
    if (AndroidBitmap_getInfo(env, alphaBmp, &aI) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, fieldBmp, &fI) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, wallBmp, &wI) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outBmp, &oI) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    const int w = int(fI.width), h = int(fI.height);
    if (int(aI.width) != w || int(aI.height) != h || int(oI.width) != w || int(oI.height) != h) return JNI_FALSE;

    void *ap = nullptr, *fp = nullptr, *wp = nullptr, *op = nullptr;
    if (AndroidBitmap_lockPixels(env, alphaBmp, &ap) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, fieldBmp, &fp) != ANDROID_BITMAP_RESULT_SUCCESS) { AndroidBitmap_unlockPixels(env, alphaBmp); return JNI_FALSE; }
    if (AndroidBitmap_lockPixels(env, wallBmp, &wp) != ANDROID_BITMAP_RESULT_SUCCESS) { AndroidBitmap_unlockPixels(env, fieldBmp); AndroidBitmap_unlockPixels(env, alphaBmp); return JNI_FALSE; }
    if (AndroidBitmap_lockPixels(env, outBmp, &op) != ANDROID_BITMAP_RESULT_SUCCESS) { AndroidBitmap_unlockPixels(env, wallBmp); AndroidBitmap_unlockPixels(env, fieldBmp); AndroidBitmap_unlockPixels(env, alphaBmp); return JNI_FALSE; }

    const auto* aB = static_cast<const uint8_t*>(ap);
    const auto* fB = static_cast<const uint8_t*>(fp);
    const auto* wB = static_cast<const uint8_t*>(wp);
    auto* oB = static_cast<uint8_t*>(op);
    const int ww = int(wI.width), wh = int(wI.height);

    const float trA = ((topColor >> 24) & 0xff) / 255.f, trR = float((topColor >> 16) & 0xff), trG = float((topColor >> 8) & 0xff), trB = float(topColor & 0xff);
    const float brA = ((botColor >> 24) & 0xff) / 255.f, brR = float((botColor >> 16) & 0xff), brG = float((botColor >> 8) & 0xff), brB = float(botColor & 0xff);
    const float shR = float((shadowTopColor >> 16) & 0xff), shG = float((shadowTopColor >> 8) & 0xff), shB = float(shadowTopColor & 0xff);
    const float inR = float((innerColor >> 16) & 0xff), inG = float((innerColor >> 8) & 0xff), inB = float(innerColor & 0xff);
    const float rad = innerAngleDeg * 3.14159265f / 180.f, ax = std::cos(rad), ay = std::sin(rad);
    const float thickDev = std::max(0.75f, innerThick * ss * 0.5f);
    const float fallLimit = std::max(1.2f, thickDev * 2.2f);
    const float scaleX = dstW / std::max(1.f, float(w)), scaleY = dstH / std::max(1.f, float(h));

    for (int y = 0; y < h; y++) {
        const uint8_t* aRow = aB + size_t(y) * aI.stride;
        const uint8_t* fRow = fB + size_t(y) * fI.stride;
        uint8_t* oRow = oB + size_t(y) * oI.stride;
        const float v = float(y) / std::max(1, h - 1);
        const float tR = trR + (brR - trR) * v, tG = trG + (brG - trG) * v, tB = trB + (brB - trB) * v, tA = trA + (brA - trA) * v;
        const int ym = std::max(0, y - 1), yp = std::min(h - 1, y + 1);
        const uint8_t* fRm = fB + size_t(ym) * fI.stride;
        const uint8_t* fRp = fB + size_t(yp) * fI.stride;

        for (int x = 0; x < w; x++) {
            uint8_t alphaByte = aRow[x];
            uint8_t* out = oRow + size_t(x) * 4;
            if (alphaByte == 0) {
                out[0] = out[1] = out[2] = out[3] = 0;
                continue;
            }
            const uint8_t* fd = fRow + size_t(x) * 4;
            float dx = (float(fd[0]) - 128.f) / 127.f * maxAbsPx;
            float dy = (float(fd[1]) - 128.f) / 127.f * maxAbsPx;
            float scrX = dstX + x * scaleX + dx;
            float scrY = dstY + y * scaleY + dy;
            float wx = (scrX - wallLeft) / std::max(0.001f, wallScaleX);
            float wy = (scrY - wallTop) / std::max(0.001f, wallScaleY);
            float r, g, b;
            sampleBilinearWall(wB, ww, wh, wI.stride, wx, wy, r, g, b);

            // saturate(1.22) brightness(1.04)
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = std::min(255.f, std::max(0.f, (lum + (r - lum) * 1.22f) * 1.04f));
            g = std::min(255.f, std::max(0.f, (lum + (g - lum) * 1.22f) * 1.04f));
            b = std::min(255.f, std::max(0.f, (lum + (b - lum) * 1.22f) * 1.04f));

            // clock tint + shadow-op top tint
            r = r * (1.f - tA) + tR * tA;
            g = g * (1.f - tA) + tG * tA;
            b = b * (1.f - tA) + tB * tA;
            if (shadowOp > 0.f) {
                r = r * (1.f - shadowOp) + shR * shadowOp;
                g = g * (1.f - shadowOp) + shG * shadowOp;
                b = b * (1.f - shadowOp) + shB * shadowOp;
            }
            // directional inner rim shadow
            float depthPx = float(fd[2]) * 0.125f;
            if (innerOp > 0.001f && innerThick > 0.01f && depthPx < fallLimit) {
                int xm = std::max(0, x - 1), xp = std::min(w - 1, x + 1);
                float gx = float(fRow[xp * 4 + 2]) - float(fRow[xm * 4 + 2]);
                float gy = float(fRp[x * 4 + 2]) - float(fRm[x * 4 + 2]);
                float gl = std::hypot(gx, gy);
                if (gl > 0.01f) {
                    float k = std::fabs((gx / gl) * ax + (gy / gl) * ay);
                    float fall = std::pow(clamp01f(1.f - depthPx / fallLimit), 0.85f);
                    float rimA = std::pow(k, 1.6f) * fall * innerOp;
                    r = r * (1.f - rimA) + inR * rimA;
                    g = g * (1.f - rimA) + inG * rimA;
                    b = b * (1.f - rimA) + inB * rimA;
                }
            }
            float a = alphaByte / 255.f;
            out[0] = glass::byte(r * a);
            out[1] = glass::byte(g * a);
            out[2] = glass::byte(b * a);
            out[3] = alphaByte;
        }
    }

    AndroidBitmap_unlockPixels(env, outBmp);
    AndroidBitmap_unlockPixels(env, wallBmp);
    AndroidBitmap_unlockPixels(env, fieldBmp);
    AndroidBitmap_unlockPixels(env, alphaBmp);
    return JNI_TRUE;
}

/* API 26-32 fallback: rounded-box Snell liquid glass card + directional rim shadow. */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_oclock_liquidglass_NativeGlass_renderCardCpuFallback(
        JNIEnv* env, jclass, jobject wallBmp, jobject outBmp,
        jfloat cardX, jfloat cardY, jfloat cardW, jfloat cardH, jfloat radiusPx,
        jfloat wallLeft, jfloat wallTop, jfloat wallScaleX, jfloat wallScaleY,
        jfloat bezelPx, jfloat thickPx, jfloat ior, jint glassBgColor, jfloat shadowInt) {
    if (!wallBmp || !outBmp) return JNI_FALSE;
    AndroidBitmapInfo wI{}, oI{};
    if (AndroidBitmap_getInfo(env, wallBmp, &wI) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outBmp, &oI) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;

    void *wp = nullptr, *op = nullptr;
    if (AndroidBitmap_lockPixels(env, wallBmp, &wp) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, outBmp, &op) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, wallBmp); return JNI_FALSE;
    }

    const auto* wB = static_cast<const uint8_t*>(wp);
    auto* oB = static_cast<uint8_t*>(op);
    const int ow = int(oI.width), oh = int(oI.height);
    const int ww = int(wI.width), wh = int(wI.height);
    const float sx = cardW / std::max(1, ow), sy = cardH / std::max(1, oh);
    const float hw = cardW * 0.5f, hh = cardH * 0.5f;
    const float rRad = std::max(0.f, std::min(radiusPx, std::min(hw, hh)));
    const float eta = 1.f / std::max(1.01f, ior);
    const float bgA = ((glassBgColor >> 24) & 0xff) / 255.f;
    const float bgR = float((glassBgColor >> 16) & 0xff), bgG = float((glassBgColor >> 8) & 0xff), bgB = float(glassBgColor & 0xff);
    const float shRad = 55.f * 3.14159265f / 180.f, ax = std::cos(shRad), ay = std::sin(shRad);

    for (int y = 0; y < oh; y++) {
        uint8_t* oRow = oB + size_t(y) * oI.stride;
        float py = (y + 0.5f) * sy - hh;
        for (int x = 0; x < ow; x++) {
            float px = (x + 0.5f) * sx - hw;
            float dd = sdRoundBox(px, py, hw, hh, rRad);
            uint8_t* out = oRow + size_t(x) * 4;
            if (dd > 1.f) { out[0] = out[1] = out[2] = out[3] = 0; continue; }
            float maskA = clamp01f(0.5f - dd);
            float e = 0.75f;
            float gx = sdRoundBox(px + e, py, hw, hh, rRad) - sdRoundBox(px - e, py, hw, hh, rRad);
            float gy = sdRoundBox(px, py + e, hw, hh, rRad) - sdRoundBox(px, py - e, hw, hh, rRad);
            float gl = std::hypot(gx, gy); if (gl < 1e-4f) gl = 1.f;
            float nx = gx / gl, ny = gy / gl;

            float disp = 0.f;
            if (dd < 0.f && -dd < bezelPx) {
                float u = clamp01f(-dd / std::max(1.f, bezelPx));
                float u1 = std::max(0.f, u - 0.002f), u2 = std::min(1.f, u + 0.002f);
                auto sq = [](float t) { return std::pow(std::max(0.f, 1.f - std::pow(1.f - t, 4.f)), 0.25f); };
                float hVal = sq(u) * thickPx;
                float slope = ((sq(u2) - sq(u1)) / (u2 - u1)) * thickPx / std::max(1.f, bezelPx);
                float sl = std::hypot(-slope, 1.f), snx = -slope / sl, snz = 1.f / sl;
                float sin2 = eta * eta * (1.f - snz * snz);
                if (sin2 <= 1.f) {
                    float fac = eta * snz - std::sqrt(1.f - sin2);
                    float tx = fac * snx, ty = -eta + fac * snz;
                    if (ty != 0.f) disp = (-hVal / ty) * tx;
                }
            }
            float scrX = cardX + (x + 0.5f) * sx - nx * disp;
            float scrY = cardY + (y + 0.5f) * sy - ny * disp;
            float wx = (scrX - wallLeft) / std::max(0.001f, wallScaleX);
            float wy = (scrY - wallTop) / std::max(0.001f, wallScaleY);
            float r, g, b;
            sampleBilinearWall(wB, ww, wh, wI.stride, wx, wy, r, g, b);

            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = std::min(255.f, std::max(0.f, (lum + (r - lum) * 1.15f - 128.f) * 1.06f + 128.f));
            g = std::min(255.f, std::max(0.f, (lum + (g - lum) * 1.15f - 128.f) * 1.06f + 128.f));
            b = std::min(255.f, std::max(0.f, (lum + (b - lum) * 1.15f - 128.f) * 1.06f + 128.f));

            r = r * (1.f - bgA) + bgR * bgA;
            g = g * (1.f - bgA) + bgG * bgA;
            b = b * (1.f - bgA) + bgB * bgA;

            if (dd <= 0.45f && -dd <= 1.8f) {
                float band = clamp01f((1.5f + dd) / 1.5f);
                float edgeAA = clamp01f((0.45f - dd) / 0.9f);
                float k = std::fabs(nx * ax + ny * ay);
                float rim = std::pow(band, 0.6f) * edgeAA * std::pow(k, 1.8f) * shadowInt;
                r = r * (1.f - rim) + 255.f * rim;
                g = g * (1.f - rim) + 255.f * rim;
                b = b * (1.f - rim) + 255.f * rim;
            }
            out[0] = glass::byte(r * maskA);
            out[1] = glass::byte(g * maskA);
            out[2] = glass::byte(b * maskA);
            out[3] = glass::byte(maskA * 255.f);
        }
    }

    AndroidBitmap_unlockPixels(env, outBmp);
    AndroidBitmap_unlockPixels(env, wallBmp);
    return JNI_TRUE;
}
