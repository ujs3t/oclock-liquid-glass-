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
        uniform float uInnerShadow;

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
            if (dd > 1.2) return half4(0.0);

            // 4-tap Rotated-Grid Supersampling (RGSS) for glass edge & inner white shadow — eliminates curved stair-steps
            float2 o0 = float2( 0.375,  0.125);
            float2 o1 = float2(-0.375, -0.125);
            float2 o2 = float2( 0.125, -0.375);
            float2 o3 = float2(-0.125,  0.375);
            float d0 = sdRoundBox(p + o0, halfSz, r);
            float d1 = sdRoundBox(p + o1, halfSz, r);
            float d2 = sdRoundBox(p + o2, halfSz, r);
            float d3 = sdRoundBox(p + o3, halfSz, r);
            float m0 = smoothstep(0.65, -0.65, d0);
            float m1 = smoothstep(0.65, -0.65, d1);
            float m2 = smoothstep(0.65, -0.65, d2);
            float m3 = smoothstep(0.65, -0.65, d3);
            float mSum = m0 + m1 + m2 + m3;
            float maskA = mSum * 0.25;
            if (maskA <= 0.0005) return half4(0.0);

            float e = 1.0;
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

            // 4x supersampled silky white inner shadow ONLY (zero dark edge teeth, zero pixelation, zero outer stroke)
            if (uInnerShadow > 0.001) {
                float span = clamp(uBezel * 0.095, 4.2, 9.5);
                float f0 = pow(1.0 - clamp(max(0.0, -d0) / span, 0.0, 1.0), 2.15) * m0;
                float f1 = pow(1.0 - clamp(max(0.0, -d1) / span, 0.0, 1.0), 2.15) * m1;
                float f2 = pow(1.0 - clamp(max(0.0, -d2) / span, 0.0, 1.0), 2.15) * m2;
                float f3 = pow(1.0 - clamp(max(0.0, -d3) / span, 0.0, 1.0), 2.15) * m3;
                float fall = (f0 + f1 + f2 + f3) / max(0.001, mSum);
                float k = abs(dot(n, uLightDir));
                float lit = pow(max(0.0, k), 1.8);
                float innerGlow = clamp(lit * fall * uInnerShadow, 0.0, 0.78);
                col = mix(col, float3(1.0), innerGlow);
            }

            // 4x supersampled Fresnel specular rim shadow
            if (uShadowInt > 0.001) {
                float b0 = clamp((1.5 + d0) / 1.5, 0.0, 1.0) * m0;
                float b1 = clamp((1.5 + d1) / 1.5, 0.0, 1.0) * m1;
                float b2 = clamp((1.5 + d2) / 1.5, 0.0, 1.0) * m2;
                float b3 = clamp((1.5 + d3) / 1.5, 0.0, 1.0) * m3;
                float band = (b0 + b1 + b2 + b3) / max(0.001, mSum);
                if (band > 0.001) {
                    float k = abs(dot(n, uLightDir));
                    float rim = pow(band, 0.6) * pow(max(0.0, k), 1.8) * uShadowInt;
                    col = mix(col, float3(1.0), clamp(rim, 0.0, 1.0));
                }
            }
            return half4(half3(col * maskA), half(maskA));
        }
    """

    // Pure GPU Clock Liquid Glass (reads C++ EDT Field + Mask, computes Snell refraction + tint + dynamic inner rim)
    private const val CLOCK_AGSL = """
        uniform shader uBackdrop;
        uniform shader uBlurBackdrop;
        uniform shader uField;
        uniform shader uMask;
        uniform float2 uClockOrigin;
        uniform float2 uDstSize;
        uniform float2 uClockScale;
        uniform float2 uFieldSize;
        uniform float uMaxAbsPx;
        uniform float uSS;
        uniform float uGlassAmt;
        uniform float uGlassMode;
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

            // 4-tap Rotated-Grid Supersampling (RGSS) eliminates texture-minification aliasing & 8-bit SDF jaggies
            float2 pxScale = uFieldSize / max(float2(1.0), uDstSize);
            float2 rg = max(float2(0.38), pxScale * 0.56);
            float4 m0 = uMask.eval(fp + float2( 0.75 * rg.x,  0.25 * rg.y));
            float4 m1 = uMask.eval(fp + float2(-0.75 * rg.x, -0.25 * rg.y));
            float4 m2 = uMask.eval(fp + float2(-0.25 * rg.x,  0.75 * rg.y));
            float4 m3 = uMask.eval(fp + float2( 0.25 * rg.x, -0.75 * rg.y));
            float alpha = (m0.r + m1.r + m2.r + m3.r) * 0.25;
            if (alpha <= 0.002) return half4(0.0);

            float4 f0 = uField.eval(fp + float2( 0.75 * rg.x,  0.25 * rg.y));
            float4 f1 = uField.eval(fp + float2(-0.75 * rg.x, -0.25 * rg.y));
            float4 f2 = uField.eval(fp + float2(-0.25 * rg.x,  0.75 * rg.y));
            float4 f3 = uField.eval(fp + float2( 0.25 * rg.x, -0.75 * rg.y));
            float4 fld = (f0 + f1 + f2 + f3) * 0.25;
            float2 dispNorm = (fld.rg * 255.0 - 128.0) / 127.0;

            float glassMode = clamp(uGlassMode, 0.0, 1.0);
            float glassAmt = clamp(uGlassAmt, 0.0, 1.0);
            float depthPx = max(0.0, fld.b * 255.0 / 1.5 - 1.0);
            float2 nRaw = ((m0.gb + m1.gb + m2.gb + m3.gb) * 0.25 * 255.0 - 128.0) / 127.0;
            float gl = length(nRaw);

            float dispScale = mix(1.0, 2.85, glassMode);
            float2 bodyShift = -nRaw * (8.5 * uSS * smoothstep(0.0, 7.0 * uSS, depthPx) * glassMode);
            float2 disp = dispNorm * (uMaxAbsPx * dispScale) + bodyShift;
            float2 localScaled = float2((fragCoord.x - uDstSize.x * 0.5) * uClockScale.x + uDstSize.x * 0.5, fragCoord.y * uClockScale.y);
            float2 samplePos = uClockOrigin + localScaled + disp;

            float spread = mix(0.0, mix(2.7, 4.6, glassMode), glassAmt);
            float wC = mix(1.0, 0.44, glassAmt);
            float wT = (1.0 - wC) * 0.25;

            float3 sharpCol;
            if (glassMode > 0.01) {
                float2 prism = disp * (0.072 * glassMode);
                float3 pR = uBackdrop.eval(samplePos + prism).rgb * wC
                    + uBackdrop.eval(samplePos + prism + float2(spread, 0.0)).rgb * wT
                    + uBackdrop.eval(samplePos + prism + float2(-spread, 0.0)).rgb * wT
                    + uBackdrop.eval(samplePos + prism + float2(0.0, spread)).rgb * wT
                    + uBackdrop.eval(samplePos + prism + float2(0.0, -spread)).rgb * wT;
                float3 pG = uBackdrop.eval(samplePos).rgb * wC
                    + uBackdrop.eval(samplePos + float2(spread, 0.0)).rgb * wT
                    + uBackdrop.eval(samplePos + float2(-spread, 0.0)).rgb * wT
                    + uBackdrop.eval(samplePos + float2(0.0, spread)).rgb * wT
                    + uBackdrop.eval(samplePos + float2(0.0, -spread)).rgb * wT;
                float3 pB = uBackdrop.eval(samplePos - prism).rgb * wC
                    + uBackdrop.eval(samplePos - prism + float2(spread, 0.0)).rgb * wT
                    + uBackdrop.eval(samplePos - prism + float2(-spread, 0.0)).rgb * wT
                    + uBackdrop.eval(samplePos - prism + float2(0.0, spread)).rgb * wT
                    + uBackdrop.eval(samplePos - prism + float2(0.0, -spread)).rgb * wT;
                sharpCol = float3(pR.r, pG.g, pB.b);
            } else {
                sharpCol = uBackdrop.eval(samplePos).rgb * wC
                    + uBackdrop.eval(samplePos + float2(spread, 0.0)).rgb * wT
                    + uBackdrop.eval(samplePos + float2(-spread, 0.0)).rgb * wT
                    + uBackdrop.eval(samplePos + float2(0.0, spread)).rgb * wT
                    + uBackdrop.eval(samplePos + float2(0.0, -spread)).rgb * wT;
            }

            float3 col = sharpCol;
            if (glassMode > 0.01) {
                // Wide creamy iOS 26 frosted crystal bokeh inside body vs bent optical caustics on curved outer bezel
                float2 blurPos = uClockOrigin + localScaled + disp * 0.42;
                float bRad = 22.0 * glassMode;
                float3 blurCol = uBlurBackdrop.eval(blurPos).rgb * 0.28
                    + uBlurBackdrop.eval(blurPos + float2(bRad, 0.0)).rgb * 0.11
                    + uBlurBackdrop.eval(blurPos + float2(-bRad, 0.0)).rgb * 0.11
                    + uBlurBackdrop.eval(blurPos + float2(0.0, bRad)).rgb * 0.11
                    + uBlurBackdrop.eval(blurPos + float2(0.0, -bRad)).rgb * 0.11
                    + uBlurBackdrop.eval(blurPos + float2(bRad * 0.72, bRad * 0.72)).rgb * 0.07
                    + uBlurBackdrop.eval(blurPos + float2(-bRad * 0.72, bRad * 0.72)).rgb * 0.07
                    + uBlurBackdrop.eval(blurPos + float2(bRad * 0.72, -bRad * 0.72)).rgb * 0.07
                    + uBlurBackdrop.eval(blurPos + float2(-bRad * 0.72, -bRad * 0.72)).rgb * 0.07;
                float interiorMask = smoothstep(0.45 * uSS, max(1.8 * uSS, 4.6 * uSS), depthPx);
                float blurBlend = mix(0.28, 0.96, interiorMask) * mix(0.60, 1.0, glassAmt) * glassMode;
                col = mix(sharpCol, blurCol, blurBlend);
            }

            // Liquid Glass crystal vibrancy & depth tone mapping
            float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
            float satBoost = mix(1.0, mix(1.28, 1.24, glassMode), glassAmt);
            float brightBoost = mix(1.0, mix(1.055, 1.06, glassMode), glassAmt);
            col = clamp((lum + (col - lum) * satBoost) * brightBoost, 0.0, 1.0);

            if (glassMode > 0.01) {
                // Sleek translucent smoked-crystal floor over pitch-black space (keeps dark areas glassy, not flat brown-grey)
                col = mix(col, max(col, float3(0.085, 0.095, 0.115)), 0.36 * glassMode);
                // Subtle vertical light bloom across top of frosted crystal face (like sunlit glass in 3:47 & 4:58)
                float topGlow = pow(max(0.0001, 1.0 - clamp(uv.y * 1.15, 0.0, 1.0)), 1.6) * 0.10 * glassMode;
                col = mix(col, float3(1.0), topGlow);
                if (gl > 0.01) {
                    float2 nCrystal = nRaw / gl;
                    float kLit = abs(dot(nCrystal, uInnerDir));
                    float upFace = clamp(-nCrystal.y * 0.68 - nCrystal.x * uInnerDir.x * 0.32, 0.0, 1.0);
                    float litStrength = clamp(kLit * 0.70 + upFace * 0.55, 0.0, 1.0);
                    float wallBand = max(0.0001, 1.0 - clamp(depthPx / max(1.0, 10.8 * uSS), 0.0, 1.0));
                    float outerDarkLip = max(0.0001, 1.0 - clamp(depthPx / max(1.0, 1.7 * uSS), 0.0, 1.0));
                    // 3D curved glass bevel: deep smoked refractive shadow on unlit wall + dark outer lip + lush broad white specular bevel on lit curve
                    float shadowUndercut = (pow(wallBand, 1.45) * (1.0 - litStrength) * 0.42 + pow(outerDarkLip, 1.6) * (1.0 - litStrength * 0.65) * 0.24) * glassMode;
                    col *= (1.0 - shadowUndercut);
                    float wallGlint = (pow(wallBand, 1.35) * pow(max(0.0001, litStrength), 1.35) * 0.48 + pow(outerDarkLip, 2.0) * pow(max(0.0001, kLit), 1.6) * 0.26) * glassMode;
                    col = mix(col, float3(1.0), clamp(wallGlint, 0.0, 0.68));
                }
            } else if (glassAmt > 0.01) {
                float edgeBand = max(0.0001, 1.0 - clamp(depthPx / max(1.0, 2.6 * uSS), 0.0, 1.0));
                float fresnel = pow(edgeBand, 2.3) * glassAmt * 0.14;
                col = mix(col, float3(1.0), fresnel);
            }

            // vertical clock tint gradient
            float4 tint = mix(uTintTop, uTintBot, clamp(uv.y, 0.0, 1.0));
            col = mix(col, tint.rgb, tint.a);

            // --clock-shadow-op subtle top ink glaze
            col = mix(col, uShadowTop.rgb, uShadowTop.a);

            // TOPMOST LAYER: Silky white inner glass shadow strictly ABOVE tint, glaze, and glass layers
            // Stays crisp and clearly visible even at high clock color opacity (70-100%)
            float fallLimit = uInnerThick > 0.01 ? max(0.75 * uSS, (uInnerThick * 4.8 + 0.45) * uSS) : 0.0;
            if (uInnerOp > 0.001 && uInnerThick > 0.01 && depthPx < fallLimit) {
                if (gl > 0.01) {
                    float ridgeFade = clamp(gl * 1.40, 0.0, 1.0);
                    float2 n = nRaw / gl;
                    float k = abs(dot(n, uInnerDir));
                    float t = clamp(depthPx / fallLimit, 0.0, 1.0);
                    float fillOp = clamp(max(tint.a, uShadowTop.a), 0.0, 1.0);
                    float decay = pow(max(0.0001, 1.0 - t), mix(2.40, 1.72, fillOp));
                    float fadeAmt = clamp(uInnerFade, 0.0, 1.0);
                    float lit = pow(max(0.0001, clamp((k - 0.24) / 0.76, 0.0, 1.0)), 1.75);
                    float dirTerm = mix(1.0, lit, fadeAmt);
                    float rimStrength = mix(mix(0.76, 0.90, glassMode), 1.00, fillOp);
                    float glassBevelMod = mix(1.0, clamp(0.32 + 0.68 * clamp(-n.y * 0.68 - n.x * uInnerDir.x * 0.32 + k * 0.42, 0.0, 1.0), 0.25, 1.0), glassMode);
                    float rimA = clamp(dirTerm * decay * ridgeFade * uInnerOp * rimStrength * glassBevelMod, 0.0, 0.96);
                    col = mix(col, uInnerColor.rgb, rimA);
                    if (fillOp > 0.12 || glassMode > 0.01) {
                        col = clamp(col + uInnerColor.rgb * (rimA * max(fillOp, glassMode * 0.45) * 0.22), 0.0, 1.0);
                    }
                }
            }

            return half4(half3(col * alpha), half(alpha));
        }
    """

    class BackdropState {
        var wallBitmap: Bitmap? = null
        var compositedBitmap: Bitmap? = null
        var blurredBitmap: Bitmap? = null
        var wallShader: BitmapShader? = null
        var compositedShader: BitmapShader? = null
        var blurredShader: BitmapShader? = null
        var wallLeft = 0f
        var wallTop = 0f
        var wallScaleX = 1f
        var wallScaleY = 1f
        var blurredLeft = 0f
        var blurredTop = 0f
        var blurredScaleX = 1f
        var blurredScaleY = 1f
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
                val filterPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                val m = Matrix()
                val s = max(cw / bw, ch / bh)
                m.setScale(s, s)
                m.postTranslate((cw - bw * s) * 0.5f, (ch - bh * s) * 0.5f)
                c.drawBitmap(wall, m, filterPaint)
                val sw = subject.width.toFloat().coerceAtLeast(1f)
                val sh = subject.height.toFloat().coerceAtLeast(1f)
                val ss = max(cw / sw, ch / sh)
                m.reset()
                m.setScale(ss, ss)
                m.postTranslate((cw - sw * ss) * 0.5f, (ch - sh * ss) * 0.5f)
                c.drawBitmap(subject, m, filterPaint)
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

            blurredBitmap?.let { updateBlurredMatrix(it, screenW, screenH, pullZoom) }
        }

        fun updateBlurredBackdrop(blurBmp: Bitmap, screenW: Int, screenH: Int, pullZoom: Float) {
            if (screenW <= 0 || screenH <= 0) return
            if (blurredBitmap !== blurBmp || blurredShader == null) {
                blurredBitmap = blurBmp
                blurredShader = BitmapShader(blurBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    if (Build.VERSION.SDK_INT >= 33) it.filterMode = BitmapShader.FILTER_MODE_LINEAR
                }
            }
            updateBlurredMatrix(blurBmp, screenW, screenH, pullZoom)
        }

        private fun updateBlurredMatrix(blurBmp: Bitmap, screenW: Int, screenH: Int, pullZoom: Float) {
            val gw = blurBmp.width.toFloat().coerceAtLeast(1f)
            val gh = blurBmp.height.toFloat().coerceAtLeast(1f)
            blurredScaleX = (screenW / gw) * pullZoom
            blurredScaleY = (screenH / gh) * pullZoom
            blurredLeft = (screenW - gw * blurredScaleX) * 0.5f
            blurredTop = (screenH - gh * blurredScaleY) * 0.5f
            matrix.reset()
            matrix.setScale(blurredScaleX, blurredScaleY)
            matrix.postTranslate(blurredLeft, blurredTop)
            blurredShader?.setLocalMatrix(matrix)
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

            blurredBitmap?.let { updateBlurredMatrix(it, screenW, screenH, pullZoom) }
        }
    }

    fun createSoftBlurredBackdrop(
        wall: Bitmap,
        subject: Bitmap?,
        depthEnabled: Boolean,
        screenW: Int,
        screenH: Int
    ): Bitmap {
        val safeW = max(1, screenW)
        val safeH = max(1, screenH)
        val targetW = 240
        val targetH = ((targetW.toFloat() * safeH / safeW).toInt()).coerceIn(140, 560)
        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(Color.BLACK)

        val bw = wall.width.toFloat().coerceAtLeast(1f)
        val bh = wall.height.toFloat().coerceAtLeast(1f)
        val sc = max(targetW / bw, targetH / bh)
        val m = Matrix().apply {
            setScale(sc, sc)
            postTranslate((targetW - bw * sc) * 0.5f, (targetH - bh * sc) * 0.5f)
        }
        c.drawBitmap(wall, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))

        if (depthEnabled && subject != null) {
            val sw = subject.width.toFloat().coerceAtLeast(1f)
            val sh = subject.height.toFloat().coerceAtLeast(1f)
            val ss = max(targetW / sw, targetH / sh)
            m.reset()
            m.setScale(ss, ss)
            m.postTranslate((targetW - sw * ss) * 0.5f, (targetH - sh * ss) * 0.5f)
            c.drawBitmap(subject, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }

        // Fast 3-pass separable sliding box blur (smooth medium iOS 26 bokeh with zero banding)
        val n = targetW * targetH
        val px = IntArray(n)
        val tmp = IntArray(n)
        bmp.getPixels(px, 0, targetW, 0, 0, targetW, targetH)
        val rad = 5
        val win = rad * 2 + 1
        repeat(3) {
            for (y in 0 until targetH) {
                val off = y * targetW
                var sr = 0; var sg = 0; var sb = 0
                for (dx in -rad..rad) {
                    val col = px[off + dx.coerceIn(0, targetW - 1)]
                    sr += (col ushr 16) and 0xff
                    sg += (col ushr 8) and 0xff
                    sb += col and 0xff
                }
                for (x in 0 until targetW) {
                    tmp[off + x] = (0xff shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                    val outCol = px[off + (x - rad).coerceIn(0, targetW - 1)]
                    val inCol = px[off + (x + rad + 1).coerceIn(0, targetW - 1)]
                    sr += ((inCol ushr 16) and 0xff) - ((outCol ushr 16) and 0xff)
                    sg += ((inCol ushr 8) and 0xff) - ((outCol ushr 8) and 0xff)
                    sb += (inCol and 0xff) - (outCol and 0xff)
                }
            }
            for (x in 0 until targetW) {
                var sr = 0; var sg = 0; var sb = 0
                for (dy in -rad..rad) {
                    val col = tmp[dy.coerceIn(0, targetH - 1) * targetW + x]
                    sr += (col ushr 16) and 0xff
                    sg += (col ushr 8) and 0xff
                    sb += col and 0xff
                }
                for (y in 0 until targetH) {
                    px[y * targetW + x] = (0xff shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                    val outCol = tmp[(y - rad).coerceIn(0, targetH - 1) * targetW + x]
                    val inCol = tmp[(y + rad + 1).coerceIn(0, targetH - 1) * targetW + x]
                    sr += ((inCol ushr 16) and 0xff) - ((outCol ushr 16) and 0xff)
                    sg += ((inCol ushr 8) and 0xff) - ((outCol ushr 8) and 0xff)
                    sb += (inCol and 0xff) - (outCol and 0xff)
                }
            }
        }
        bmp.setPixels(px, 0, targetW, 0, 0, targetW, targetH)
        return bmp
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
        shadowInt: Float,
        innerShadow: Float = 0f
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
        shader.setFloatUniform("uInnerShadow", innerShadow)
    }

    fun isGpuAgslAvailable(): Boolean = Build.VERSION.SDK_INT >= 33
}
