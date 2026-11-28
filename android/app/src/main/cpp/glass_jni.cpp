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
   zero PNG compression.
   Writes to outFieldBmp: (R=dispX, G=dispY, B=(depth+1)*5, A=255).
   Also stores smooth double-precision SDF normal (nx, ny) into alphaBmp (G, B)
   while keeping crisp anti-aliased alpha in alphaBmp.R and A=255. */
extern "C" JNIEXPORT jdouble JNICALL
Java_com_oclock_liquidglass_NativeGlass_computeClockField(
        JNIEnv* env, jclass, jobject alphaBmp, jobject outFieldBmp,
        jdouble ss, jdouble ior, jdouble thickness, jdouble bezel, jint surfaceKind, jlong version) {
    if (!alphaBmp || !outFieldBmp) return -1.0;
    AndroidBitmapInfo aInfo{}, fInfo{};
    if (AndroidBitmap_getInfo(env, alphaBmp, &aInfo) != ANDROID_BITMAP_RESULT_SUCCESS) return -1.0;
    if (AndroidBitmap_getInfo(env, outFieldBmp, &fInfo) != ANDROID_BITMAP_RESULT_SUCCESS) return -1.0;
    if (aInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || fInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -1.0;
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
        auto* aBytes = static_cast<uint8_t*>(aPtr);
        for (int y = 0; y < h; y++) {
            const uint8_t* srcRow = aBytes + size_t(y) * aInfo.stride;
            uint8_t* dstRow = alpha.data() + size_t(y) * w;
            for (int x = 0; x < w; x++) {
                dstRow[x] = srcRow[size_t(x) * 4];
            }
        }

        const uint64_t expected = static_cast<uint64_t>(version);
        auto sdf = glass::edt(alpha.data(), w, h, true, &g_latest_version, expected);
        if (!sdf.empty()) {
            auto inside = glass::edt(alpha.data(), w, h, false, &g_latest_version, expected);
            if (!inside.empty()) {
                double maxInside = 0.0;
                const size_t count = size_t(w) * h;
                auto sampleAlpha = [&](int sx, int sy) -> double {
                    const int cx = std::max(0, std::min(w - 1, sx));
                    const int cy = std::max(0, std::min(h - 1, sy));
                    return double(alpha[size_t(cy) * w + cx]) / 255.0;
                };
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        const size_t i = size_t(y) * w + x;
                        double s = sdf[i] > 0.f ? double(sdf[i]) - 0.5 : -(double(inside[i]) - 0.5);
                        maxInside = std::max(maxInside, -s);
                        if (std::abs(s) <= 1.35) {
                            const double agx = (sampleAlpha(x + 1, y - 1) + 2.0 * sampleAlpha(x + 1, y) + sampleAlpha(x + 1, y + 1)
                                              - sampleAlpha(x - 1, y - 1) - 2.0 * sampleAlpha(x - 1, y) - sampleAlpha(x - 1, y + 1)) * 0.25;
                            const double agy = (sampleAlpha(x - 1, y + 1) + 2.0 * sampleAlpha(x, y + 1) + sampleAlpha(x + 1, y + 1)
                                              - sampleAlpha(x - 1, y - 1) - 2.0 * sampleAlpha(x, y - 1) - sampleAlpha(x + 1, y - 1)) * 0.25;
                            const double alen = std::hypot(agx, agy);
                            if (alen > 0.12) {
                                const double edgeS = (0.5 - double(alpha[i]) / 255.0) / std::max(0.45, alen);
                                const double blend = clamp01f(float((1.35 - std::abs(s)) / 0.65));
                                s = s * (1.0 - blend) + edgeS * blend;
                            } else if (std::abs(s) <= 0.65) {
                                s = (127.5 - double(alpha[i])) / 255.0;
                            }
                        }
                        sdf[i] = float(s);
                    }
                }
                std::vector<float>().swap(inside);

                // 1 gentle [1,2,1]/4 pass keeps crisp edge distance without shifting contour
                std::vector<float> depthSdf(count);
                std::vector<float> tmpSdf(count);
                bool cancelled = false;
                for (int y = 0; y < h; y++) {
                    const float* src = sdf.data() + size_t(y) * w;
                    float* dst = tmpSdf.data() + size_t(y) * w;
                    for (int x = 0; x < w; x++) {
                        dst[x] = (src[std::max(0, x - 1)] + 2.f * src[x] + src[std::min(w - 1, x + 1)]) * 0.25f;
                    }
                }
                for (int y = 0; y < h; y++) {
                    const float* rm1 = tmpSdf.data() + size_t(std::max(0, y - 1)) * w;
                    const float* r0  = tmpSdf.data() + size_t(y) * w;
                    const float* rp1 = tmpSdf.data() + size_t(std::min(h - 1, y + 1)) * w;
                    float* dst = depthSdf.data() + size_t(y) * w;
                    for (int x = 0; x < w; x++) {
                        dst[x] = (rm1[x] + 2.f * r0[x] + rp1[x]) * 0.25f;
                    }
                }

                // O(1) 3-pass separable sliding-window box blur approximates wide Gaussian (sigma ~ 5.5..7.5 texels)
                // completely eliminating binary EDT staircase ripples along curved digit flanks
                const int blurR = std::max(3, std::min(9, int(std::round(ss * 1.45))));
                const double invWin = 1.0 / double(2 * blurR + 1);
                std::vector<float> smoothSdf = std::move(sdf);
                for (int pass = 0; pass < 3 && !cancelled; pass++) {
                    for (int y = 0; y < h; y++) {
                        if ((y & 63) == 0 && glass::isCancelled(&g_latest_version, expected)) {
                            cancelled = true;
                            break;
                        }
                        const float* src = smoothSdf.data() + size_t(y) * w;
                        float* dst = tmpSdf.data() + size_t(y) * w;
                        double acc = double(src[0]) * (blurR + 1);
                        for (int k = 1; k <= blurR; k++) acc += src[std::min(w - 1, k)];
                        for (int x = 0; x < w; x++) {
                            dst[x] = float(acc * invWin);
                            acc += double(src[std::min(w - 1, x + blurR + 1)]) - double(src[std::max(0, x - blurR)]);
                        }
                    }
                    if (cancelled) break;
                    for (int x = 0; x < w; x++) {
                        if ((x & 63) == 0 && glass::isCancelled(&g_latest_version, expected)) {
                            cancelled = true;
                            break;
                        }
                        double acc = double(tmpSdf[x]) * (blurR + 1);
                        for (int k = 1; k <= blurR; k++) acc += tmpSdf[size_t(std::min(h - 1, k)) * w + x];
                        for (int y = 0; y < h; y++) {
                            smoothSdf[size_t(y) * w + x] = float(acc * invWin);
                            const int addY = std::min(h - 1, y + blurR + 1);
                            const int subY = std::max(0, y - blurR);
                            acc += double(tmpSdf[size_t(addY) * w + x]) - double(tmpSdf[size_t(subY) * w + x]);
                        }
                    }
                }

                if (!cancelled) {
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

                    auto* fBytes = static_cast<uint8_t*>(fPtr);
                    const bool flatCenter = std::abs(profile[126]) < 1e-9;

                    for (int y = 0; y < h; y++) {
                        if ((y & 31) == 0 && glass::isCancelled(&g_latest_version, expected)) {
                            maxAbsCss = -1.0;
                            break;
                        }
                        const int ym = std::max(0, y - 1), yp = std::min(h - 1, y + 1);
                        const float* sm = smoothSdf.data() + size_t(ym) * w;
                        const float* sc = smoothSdf.data() + size_t(y) * w;
                        const float* sp = smoothSdf.data() + size_t(yp) * w;
                        const float* dc = depthSdf.data() + size_t(y) * w;
                        uint8_t* aRow = aBytes + size_t(y) * aInfo.stride;
                        uint8_t* outRow = fBytes + size_t(y) * fInfo.stride;

                        for (int x = 0; x < w; x++) {
                            const int xm = std::max(0, x - 1), xp = std::min(w - 1, x + 1);
                            const double dd = dc[x];
                            const double wSmooth = clamp01f(float(-dd) / std::max(1.5f, float(ss) * 1.1f));
                            const double ddSmooth = dd * (1.0 - wSmooth) + double(sc[x]) * wSmooth;
                            const double gx = (double(sc[xp]) - sc[xm]) * 0.5;
                            const double gy = (double(sp[x]) - sm[x]) * 0.5;
                            const double len = std::hypot(gx, gy);
                            // Keep normal bounded smoothly at medial ridges instead of snapping
                            const double div = std::max(1.0, len);
                            const double rnx = len > 1e-4 ? gx / div : 0.0;
                            const double rny = len > 1e-4 ? gy / div : 0.0;

                            // Write smooth normal into alphaBmp G/B while leaving R (alpha) & A=255 intact
                            uint8_t* ad = aRow + size_t(x) * 4;
                            ad[1] = glass::byte(128.0 + rnx * 127.0);
                            ad[2] = glass::byte(128.0 + rny * 127.0);
                            ad[3] = 255;

                            uint8_t* d = outRow + size_t(x) * 4;
                            d[0] = 128; d[1] = 128;
                            // Wide unclipped depth (0..169 texels) blending subpixel contour into Gaussian SDF
                            d[2] = glass::byte((-ddSmooth + 1.0) * 1.5);
                            d[3] = 255;
                            if (dd > 0.0) continue;
                            if (flatCenter && dd <= -effBezel) continue;

                            const double uLen = len > 1e-4 ? len : 1.0;
                            const double nx = gx / uLen, ny = gy / uLen;
                            const double pos = glass::clamp(std::min(-dd, effBezel) / effBezel) * 126.0;
                            const int a = int(std::floor(pos)), b = std::min(126, a + 1);
                            const double t = pos - a, disp = profile[a] * (1.0 - t) + profile[b] * t;
                            d[0] = glass::byte(128.0 - nx * (disp / maxAbs) * 127.0);
                            d[1] = glass::byte(128.0 - ny * (disp / maxAbs) * 127.0);
                        }
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
        jint innerColor, jfloat innerThick, jfloat innerOp, jfloat innerAngleDeg, jfloat innerFade, jfloat glassAmt, jfloat glassMode, jfloat bevelWidth) {
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
    const float gAmt = clamp01f(glassAmt);
    const float gMode = clamp01f(glassMode);
    const float bevelW = std::max(0.f, std::min(2.2f, bevelWidth));
    const float bevelNorm = bevelW / 2.2f;
    const float bevelSpan = (3.0f + (22.5f - 3.0f) * bevelNorm) * ss;
    const float wideMag = std::max(0.f, bevelW - 0.55f) * 15.5f * ss;
    const float dispScale = 1.0f + 1.85f * gMode;
    const float satBoost = 1.0f + (0.28f - 0.04f * gMode) * gAmt;
    const float brightBoost = 1.0f + (0.055f + 0.005f * gMode) * gAmt;
    const float rad = innerAngleDeg * 3.14159265f / 180.f, ax = std::cos(rad), ay = std::sin(rad);
    const float fallLimit = innerThick > 0.01f ? std::max(0.75f * ss, (innerThick * 4.8f + 0.45f) * ss) : 0.0f;
    const float scaleX = dstW / std::max(1.f, float(w)), scaleY = dstH / std::max(1.f, float(h));

    for (int y = 0; y < h; y++) {
        const uint8_t* aRow = aB + size_t(y) * aI.stride;
        const uint8_t* fRow = fB + size_t(y) * fI.stride;
        uint8_t* oRow = oB + size_t(y) * oI.stride;
        const float v = float(y) / std::max(1, h - 1);
        const float tR = trR + (brR - trR) * v, tG = trG + (brG - trG) * v, tB = trB + (brB - trB) * v, tA = trA + (brA - trA) * v;

        for (int x = 0; x < w; x++) {
            uint8_t alphaByte = aRow[size_t(x) * 4];
            uint8_t* out = oRow + size_t(x) * 4;
            if (alphaByte == 0) {
                out[0] = out[1] = out[2] = out[3] = 0;
                continue;
            }
            const uint8_t* fd = fRow + size_t(x) * 4;
            float gx = (float(aRow[size_t(x) * 4 + 1]) - 128.f) / 127.f;
            float gy = (float(aRow[size_t(x) * 4 + 2]) - 128.f) / 127.f;
            float gl = std::hypot(gx, gy);
            float depthPx = std::max(0.f, float(fd[2]) / 1.5f - 1.f);
            float bU = clamp01f(depthPx / std::max(1.f, bevelSpan));
            float bDomeSlope = (1.f - bU) * std::sqrt(std::max(0.01f, 1.f - bU * 0.52f));
            float bShiftMask = clamp01f(depthPx / std::max(1.f, 7.0f * ss));
            float dx = (float(fd[0]) - 128.f) / 127.f * (maxAbsPx * dispScale) - gx * (bDomeSlope * wideMag + 8.5f * ss * bShiftMask * gMode);
            float dy = (float(fd[1]) - 128.f) / 127.f * (maxAbsPx * dispScale) - gy * (bDomeSlope * wideMag + 8.5f * ss * bShiftMask * gMode);
            float scrX = dstX + x * scaleX + dx;
            float scrY = dstY + y * scaleY + dy;
            float wx = (scrX - wallLeft) / std::max(0.001f, wallScaleX);
            float wy = (scrY - wallTop) / std::max(0.001f, wallScaleY);
            float r, g, b;
            if (gMode > 0.01f) {
                float pOffX = (dx * 0.072f * gMode) / std::max(0.001f, wallScaleX);
                float pOffY = (dy * 0.072f * gMode) / std::max(0.001f, wallScaleY);
                float rr, rg_u, rb_u, gr, gg, gb_u, br_u, bg_u, bb;
                sampleBilinearWall(wB, ww, wh, wI.stride, wx + pOffX, wy + pOffY, rr, rg_u, rb_u);
                sampleBilinearWall(wB, ww, wh, wI.stride, wx, wy, gr, gg, gb_u);
                sampleBilinearWall(wB, ww, wh, wI.stride, wx - pOffX, wy - pOffY, br_u, bg_u, bb);
                r = rr; g = gg; b = bb;
                // Wide creamy frosted interior crystal blur separation vs curved bevelSpan
                float bLo = bevelSpan * 0.16f;
                float bHi = std::max(2.0f * ss, bevelSpan * 0.78f);
                float it = clamp01f((depthPx - bLo) / std::max(0.01f, bHi - bLo));
                float interior = it * it * (3.f - 2.f * it);
                float blurW = (0.20f + 0.76f * interior) * (0.60f + 0.40f * gAmt) * gMode;
                if (blurW > 0.01f) {
                    float bSpr = 14.0f / std::max(0.001f, wallScaleX);
                    float dSpr = bSpr * 0.72f;
                    float sumR = r * 0.28f, sumG = g * 0.28f, sumB = b * 0.28f;
                    const float ox[8] = { bSpr, -bSpr, 0.f, 0.f, dSpr, -dSpr, dSpr, -dSpr };
                    const float oy[8] = { 0.f, 0.f, bSpr, -bSpr, dSpr, dSpr, -dSpr, -dSpr };
                    const float ow[8] = { 0.11f, 0.11f, 0.11f, 0.11f, 0.07f, 0.07f, 0.07f, 0.07f };
                    for (int k = 0; k < 8; k++) {
                        float sr, sg, sb;
                        sampleBilinearWall(wB, ww, wh, wI.stride, wx + ox[k], wy + oy[k], sr, sg, sb);
                        sumR += sr * ow[k]; sumG += sg * ow[k]; sumB += sb * ow[k];
                    }
                    r = r * (1.f - blurW) + sumR * blurW;
                    g = g * (1.f - blurW) + sumG * blurW;
                    b = b * (1.f - blurW) + sumB * blurW;
                }
            } else {
                sampleBilinearWall(wB, ww, wh, wI.stride, wx, wy, r, g, b);
            }

            // Liquid Glass crystal vibrancy & edge depth scaled by gAmt + gMode
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = std::min(255.f, std::max(0.f, (lum + (r - lum) * satBoost) * brightBoost));
            g = std::min(255.f, std::max(0.f, (lum + (g - lum) * satBoost) * brightBoost));
            b = std::min(255.f, std::max(0.f, (lum + (b - lum) * satBoost) * brightBoost));

            if (gMode > 0.01f) {
                r = r * (1.f - 0.36f * gMode) + std::max(r, 22.f) * (0.36f * gMode);
                g = g * (1.f - 0.36f * gMode) + std::max(g, 24.f) * (0.36f * gMode);
                b = b * (1.f - 0.36f * gMode) + std::max(b, 30.f) * (0.36f * gMode);
                float topGlow = std::pow(std::max(0.0001f, 1.f - clamp01f(v * 1.15f)), 1.6f) * 0.10f * gMode;
                r = r * (1.f - topGlow) + 255.f * topGlow;
                g = g * (1.f - topGlow) + 255.f * topGlow;
                b = b * (1.f - topGlow) + 255.f * topGlow;
                if (gl > 0.01f) {
                    float nx = gx / gl, ny = gy / gl;
                    float kLit = std::fabs(nx * ax + ny * ay);
                    float upFace = clamp01f(-ny * 0.68f - nx * ax * 0.32f);
                    float litStrength = clamp01f(kLit * 0.70f + upFace * 0.55f);
                    float wallBand = std::max(0.0001f, 1.f - clamp01f(depthPx / std::max(1.f, bevelSpan * 1.05f)));
                    float lipW = (1.5f + (2.5f - 1.5f) * bevelNorm) * ss;
                    float outerDarkLip = std::max(0.0001f, 1.f - clamp01f(depthPx / std::max(1.f, lipW)));
                    float shadowUndercut = (std::pow(wallBand, 1.45f) * (1.f - litStrength) * 0.42f + std::pow(outerDarkLip, 1.6f) * (1.f - litStrength * 0.65f) * 0.24f) * gMode;
                    r *= (1.f - shadowUndercut);
                    g *= (1.f - shadowUndercut);
                    b *= (1.f - shadowUndercut);
                }
            }

            // clock tint + shadow-op top tint
            r = r * (1.f - tA) + tR * tA;
            g = g * (1.f - tA) + tG * tA;
            b = b * (1.f - tA) + tB * tA;
            if (shadowOp > 0.f) {
                r = r * (1.f - shadowOp) + shR * shadowOp;
                g = g * (1.f - shadowOp) + shG * shadowOp;
                b = b * (1.f - shadowOp) + shB * shadowOp;
            }
            // TOPMOST LAYER: Single unified inner shadow strictly driven by user Inner Shadow controls (both Glass & standard modes)
            if (innerOp > 0.001f && innerThick > 0.01f && depthPx < fallLimit) {
                if (gl > 0.01f) {
                    float ridgeFade = clamp01f(gl * 1.40f);
                    float nx = gx / gl, ny = gy / gl;
                    float k = std::fabs(nx * ax + ny * ay);
                    float t = clamp01f(depthPx / fallLimit);
                    float fillOp = clamp01f(std::max(tA, shadowOp));
                    float decay = std::pow(std::max(0.0001f, 1.f - t), 2.40f + (1.72f - 2.40f) * fillOp);
                    float fadeAmt = clamp01f(innerFade);
                    float lit = std::pow(std::max(0.0001f, clamp01f((k - 0.24f) / 0.76f)), 1.75f);
                    float dirTerm = (1.f - fadeAmt) + fadeAmt * lit;
                    float rimBase = 0.76f + (0.96f - 0.76f) * gMode;
                    float rimStrength = rimBase + (1.00f - rimBase) * fillOp;
                    float modVal = std::max(0.25f, std::min(1.0f, 0.32f + 0.68f * clamp01f(-ny * 0.68f - nx * ax * 0.32f + k * 0.42f)));
                    float glassBevelMod = 1.0f + (modVal - 1.0f) * (gMode * fadeAmt);
                    float rimA = clamp01f(dirTerm * decay * ridgeFade * innerOp * rimStrength * glassBevelMod);
                    rimA = std::min(rimA, 0.96f);
                    r = r * (1.f - rimA) + inR * rimA;
                    g = g * (1.f - rimA) + inG * rimA;
                    b = b * (1.f - rimA) + inB * rimA;
                    if (fillOp > 0.12f || gMode > 0.01f) {
                        float pop = rimA * std::max(fillOp, gMode * 0.45f) * 0.22f;
                        r = std::min(255.f, r + inR * pop);
                        g = std::min(255.f, g + inG * pop);
                        b = std::min(255.f, b + inB * pop);
                    }
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
