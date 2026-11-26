package com.oclock.liquidglass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

object LiquidGlassShaders {
    // Pure GPU card & pill Liquid Glass (Snell convex squircle + optical blur + Fresnel rim)
    private const val CARD_AGSL = """
        uniform shader uBackdrop;
        uniform float2 uCardOrigin;
        uniform float2 uCardSize;
        uniform float uRadius;
        uniform float uBezel;
        uniform float uThick;
        uniform float uIor;
        uniform float4 uGlassBg;
        uniform float uShadowInt;
        uniform float2 uLightDir;

        float sdRoundBox(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        float squircleH(float u) {
            float t = clamp(u, 0.0, 1.0);
            return pow(max(0.0, 1.0 - pow(1.0 - t, 4.0)), 0.25);
        }

        half4 main(float2 fragCoord) {
            float2 halfSz = uCardSize * 0.5;
            float r = min(uRadius, min(halfSz.x, halfSz.y));
            float2 p = fragCoord - halfSz;
            float dd = sdRoundBox(p, halfSz, r);
            if (dd > 1.0) return half4(0.0);
            float maskA = clamp(0.5 - dd, 0.0, 1.0);

            float e = 0.75;
            float2 grad = float2(
                sdRoundBox(p + float2(e, 0.0), halfSz, r) - sdRoundBox(p - float2(e, 0.0), halfSz, r),
                sdRoundBox(p + float2(0.0, e), halfSz, r) - sdRoundBox(p - float2(0.0, e), halfSz, r)
            );
            float glen = length(grad);
            float2 n = glen > 0.0001 ? grad / glen : float2(0.0, -1.0);

            float disp = 0.0;
            if (dd < 0.0 && -dd < uBezel) {
                float u = clamp(-dd / max(1.0, uBezel), 0.0, 1.0);
                float u1 = max(0.0, u - 0.002);
                float u2 = min(1.0, u + 0.002);
                float hVal = squircleH(u) * uThick;
                float slope = ((squircleH(u2) - squircleH(u1)) / (u2 - u1)) * uThick / max(1.0, uBezel);
                float sl = length(float2(-slope, 1.0));
                float snx = -slope / sl;
                float snz = 1.0 / sl;
                float eta = 1.0 / max(1.01, uIor);
                float sin2 = eta * eta * (1.0 - snz * snz);
                if (sin2 <= 1.0) {
                    float fac = eta * snz - sqrt(1.0 - sin2);
                    float tx = fac * snx;
                    float ty = -eta + fac * snz;
                    if (abs(ty) > 0.0001) disp = (-hVal / ty) * tx;
                }
            }

            float2 samplePos = uCardOrigin + fragCoord - n * disp;
            // Subtle 5-tap frosted optical softening matching backdrop blur
            float3 col = uBackdrop.eval(samplePos).rgb * 0.40
                + uBackdrop.eval(samplePos + float2(2.2, 2.2)).rgb * 0.15
                + uBackdrop.eval(samplePos + float2(-2.2, 2.2)).rgb * 0.15
                + uBackdrop.eval(samplePos + float2(2.2, -2.2)).rgb * 0.15
                + uBackdrop.eval(samplePos + float2(-2.2, -2.2)).rgb * 0.15;

            // saturate(1.15) contrast(1.06)
            float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
            col = clamp((lum + (col - lum) * 1.15 - 0.5) * 1.06 + 0.5, 0.0, 1.0);

            // glass-bg tint
            col = mix(col, uGlassBg.rgb, uGlassBg.a);

            // Fresnel specular rim shadow
            if (dd <= 0.45 && -dd <= 1.8) {
                float band = clamp((1.5 + dd) / 1.5, 0.0, 1.0);
                float edgeAA = clamp((0.45 - dd) / 0.9, 0.0, 1.0);
                float k = abs(dot(n, uLightDir));
                float rim = pow(band, 0.6) * edgeAA * pow(max(0.0, k), 1.8) * uShadowInt;
                col = mix(col, float3(1.0), clamp(rim, 0.0, 1.0));
            }
            return half4(half3(col * maskA), half(maskA));
        }
    """

    // Pure GPU Clock Liquid Glass (reads C++ EDT Field + Mask, computes Snell refraction + tint + dynamic inner rim)
    private const val CLOCK_AGSL = """
        uniform shader uBackdrop;
        uniform shader uField;
        uniform shader uMask;
        uniform float2 uClockOrigin;
        uniform float2 uDstSize;
        uniform float2 uClockScale;
        uniform float2 uFieldSize;
        uniform float uMaxAbsPx;
        uniform float uSS;
        uniform float4 uTintTop;
        uniform float4 uTintBot;
        uniform float4 uShadowTop;
        uniform float4 uInnerColor;
        uniform float uInnerThick;
        uniform float uInnerOp;
        uniform float uInnerFade;
        uniform float2 uInnerDir;

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / max(float2(1.0), uDstSize);
            float2 fp = uv * uFieldSize;

            float4 msk = uMask.eval(fp);
            float alpha = msk.r;
            if (alpha <= 0.002) return half4(0.0);

            float4 fld = uField.eval(fp);
            float2 dispNorm = (fld.rg * 255.0 - 128.0) / 127.0;
            float2 disp = dispNorm * uMaxAbsPx;

            float2 localScaled = float2((fragCoord.x - uDstSize.x * 0.5) * uClockScale.x + uDstSize.x * 0.5, fragCoord.y * uClockScale.y);
            float2 samplePos = uClockOrigin + localScaled + disp;
            float3 col = uBackdrop.eval(samplePos).rgb * 0.56
                + uBackdrop.eval(samplePos + float2(1.1, 0.0)).rgb * 0.11
                + uBackdrop.eval(samplePos + float2(-1.1, 0.0)).rgb * 0.11
                + uBackdrop.eval(samplePos + float2(0.0, 1.1)).rgb * 0.11
                + uBackdrop.eval(samplePos + float2(0.0, -1.1)).rgb * 0.11;

            // saturate(1.22) brightness(1.04)
            float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
            col = clamp((lum + (col - lum) * 1.22) * 1.04, 0.0, 1.0);

            // vertical clock tint gradient
            float4 tint = mix(uTintTop, uTintBot, clamp(uv.y, 0.0, 1.0));
            col = mix(col, tint.rgb, tint.a);

            // --clock-shadow-op subtle top ink glaze
            col = mix(col, uShadowTop.rgb, uShadowTop.a);

            // Dynamic GPU directional inner shadow layered cleanly ON TOP of any clock fill (0%..100% opacity)
            float depthPx = max(0.0, fld.b * 255.0 / 5.0 - 1.0);
            float fallLimit = max(2.0, uInnerThick * uSS * 1.65);
            if (uInnerOp > 0.001 && uInnerThick > 0.01 && depthPx < fallLimit) {
                float2 nRaw = (msk.gb * 255.0 - 128.0) / 127.0;
                float gl = length(nRaw);
                if (gl > 0.01) {
                    float ridgeFade = clamp(gl * 1.35, 0.0, 1.0);
                    float2 n = nRaw / gl;
                    float k = abs(dot(n, uInnerDir));
                    float tDepth = clamp(depthPx / fallLimit, 0.0, 1.0);
                    float fall = (1.0 - tDepth) * (1.0 - tDepth) * (1.0 + 0.6 * tDepth);
                    float fadeAmt = clamp(uInnerFade, 0.0, 1.0);
                    float dirExp = mix(1.0, 3.4, fadeAmt);
                    float dirTerm = mix(1.0 - fadeAmt, 1.0, pow(max(0.0, k), dirExp));
                    float rimA = clamp(dirTerm * fall * ridgeFade * uInnerOp, 0.0, 1.0);
                    col = mix(col, uInnerColor.rgb, rimA);
                }
            }

            return half4(half3(col * alpha), half(alpha));
        }
    """

    class BackdropState {
        var wallBitmap: Bitmap? = null
        var compositedBitmap: Bitmap? = null
        var wallShader: BitmapShader? = null
        var compositedShader: BitmapShader? = null
        var wallLeft = 0f
        var wallTop = 0f
        var wallScaleX = 1f
        var wallScaleY = 1f
        private val matrix = Matrix()

        fun updateWallAndSubject(
            wall: Bitmap,
            subject: Bitmap?,
            depthEnabled: Boolean,
            screenW: Int,
            screenH: Int,
            pullZoom: Float
        ) {
            if (screenW <= 0 || screenH <= 0) return
            val bw = wall.width.toFloat().coerceAtLeast(1f)
            val bh = wall.height.toFloat().coerceAtLeast(1f)
            val baseScale = max(screenW / bw, screenH / bh)
            val finalScale = baseScale * pullZoom
            val drawnW = bw * finalScale
            val drawnH = bh * finalScale
            wallLeft = (screenW - drawnW) * 0.5f
            wallTop = (screenH - drawnH) * 0.5f
            wallScaleX = finalScale
            wallScaleY = finalScale

            if (wallBitmap !== wall || wallShader == null) {
                wallBitmap = wall
                wallShader = BitmapShader(wall, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    if (Build.VERSION.SDK_INT >= 33) it.filterMode = BitmapShader.FILTER_MODE_LINEAR
                }
            }
            matrix.reset()
            matrix.setScale(wallScaleX, wallScaleY)
            matrix.postTranslate(wallLeft, wallTop)
            wallShader?.setLocalMatrix(matrix)

            if (depthEnabled && subject != null) {
                // Keep a lightweight 0.5x screen composited backdrop for cards sitting in front of subject
                val cw = (screenW / 2).coerceAtLeast(64)
                val ch = (screenH / 2).coerceAtLeast(64)
                val comp = compositedBitmap?.takeIf { it.width == cw && it.height == ch }
                    ?: Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888).also { compositedBitmap = it }
                val c = android.graphics.Canvas(comp)
                c.drawColor(Color.BLACK)
                val m = Matrix()
                val s = max(cw / bw, ch / bh)
                m.setScale(s, s)
                m.postTranslate((cw - bw * s) * 0.5f, (ch - bh * s) * 0.5f)
                c.drawBitmap(wall, m, null)
                val sw = subject.width.toFloat().coerceAtLeast(1f)
                val sh = subject.height.toFloat().coerceAtLeast(1f)
                val ss = max(cw / sw, ch / sh)
                m.reset()
                m.setScale(ss, ss)
                m.postTranslate((cw - sw * ss) * 0.5f, (ch - sh * ss) * 0.5f)
                c.drawBitmap(subject, m, null)
                if (compositedShader == null) {
                    compositedShader = BitmapShader(comp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                        if (Build.VERSION.SDK_INT >= 33) it.filterMode = BitmapShader.FILTER_MODE_LINEAR
                    }
                }
                matrix.reset()
                val scX = (screenW.toFloat() / cw) * pullZoom
                val scY = (screenH.toFloat() / ch) * pullZoom
                matrix.setScale(scX, scY)
                matrix.postTranslate((screenW - cw * scX) * 0.5f, (screenH - ch * scY) * 0.5f)
                compositedShader?.setLocalMatrix(matrix)
            } else {
                compositedBitmap = null
                compositedShader = null
            }
        }

        fun updateZoomOnly(screenW: Int, screenH: Int, pullZoom: Float) {
            val wall = wallBitmap ?: return
            val bw = wall.width.toFloat().coerceAtLeast(1f)
            val bh = wall.height.toFloat().coerceAtLeast(1f)
            val finalScale = max(screenW / bw, screenH / bh) * pullZoom
            wallLeft = (screenW - bw * finalScale) * 0.5f
            wallTop = (screenH - bh * finalScale) * 0.5f
            wallScaleX = finalScale
            wallScaleY = finalScale
            matrix.reset()
            matrix.setScale(wallScaleX, wallScaleY)
            matrix.postTranslate(wallLeft, wallTop)
            wallShader?.setLocalMatrix(matrix)

            val comp = compositedBitmap
            if (comp != null && compositedShader != null) {
                val cw = comp.width.toFloat()
                val ch = comp.height.toFloat()
                val scX = (screenW / cw) * pullZoom
                val scY = (screenH / ch) * pullZoom
                matrix.reset()
                matrix.setScale(scX, scY)
                matrix.postTranslate((screenW - cw * scX) * 0.5f, (screenH - ch * scY) * 0.5f)
                compositedShader?.setLocalMatrix(matrix)
            }
        }
    }

    @RequiresApi(33)
    fun createCardShader(): RuntimeShader = RuntimeShader(CARD_AGSL)

    @RequiresApi(33)
    fun createClockShader(): RuntimeShader = RuntimeShader(CLOCK_AGSL)

    @RequiresApi(33)
    fun configureCardShader(
        shader: RuntimeShader,
        backdrop: BitmapShader,
        cardX: Float,
        cardY: Float,
        cardW: Float,
        cardH: Float,
        radiusPx: Float,
        bezelPx: Float,
        thickPx: Float,
        ior: Float,
        glassBg: Int,
        shadowInt: Float
    ) {
        shader.setInputShader("uBackdrop", backdrop)
        shader.setFloatUniform("uCardOrigin", cardX, cardY)
        shader.setFloatUniform("uCardSize", cardW, cardH)
        shader.setFloatUniform("uRadius", radiusPx)
        shader.setFloatUniform("uBezel", bezelPx)
        shader.setFloatUniform("uThick", thickPx)
        shader.setFloatUniform("uIor", ior)
        shader.setFloatUniform(
            "uGlassBg",
            Color.red(glassBg) / 255f,
            Color.green(glassBg) / 255f,
            Color.blue(glassBg) / 255f,
            Color.alpha(glassBg) / 255f
        )
        shader.setFloatUniform("uShadowInt", shadowInt)
        val rad = Math.toRadians(55.0)
        shader.setFloatUniform("uLightDir", cos(rad).toFloat(), sin(rad).toFloat())
    }

    fun isGpuAgslAvailable(): Boolean = Build.VERSION.SDK_INT >= 33
}
