package com.oclock.liquidglass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

data class SmartClockStyle(
    val colorHex: String,
    val colorInt: Int,
    val clockOpacity: Int,
    val innerColorHex: String,
    val innerColorInt: Int,
    val innerOpacity: Int,
    val innerThickness: Float,
    val innerAngle: Float,
    val innerFade: Int,
    val clockGlass: Int,
    val clockRefraction: Int,
    val clockBevel: Int,
    val paletteDots: IntArray,
    val profileLabel: String
)

object WallpaperThemeAI {

    fun analyze(bmp: Bitmap, baseCfg: LockConfig = LockConfig(), glassPreset: Boolean = baseCfg.clockGlassPreset): SmartClockStyle {
        val w = 48
        val h = 88
        val safeBmp = if (bmp.config == Bitmap.Config.HARDWARE) {
            bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
        } else bmp
        val tiny = try {
            Bitmap.createScaledBitmap(safeBmp, w, h, true)
        } catch (_: Throwable) {
            safeBmp
        }
        val tw = tiny.width.coerceAtLeast(1)
        val th = tiny.height.coerceAtLeast(1)
        val px = IntArray(tw * th)
        tiny.getPixels(px, 0, tw, 0, 0, tw, th)
        if (tiny !== safeBmp) tiny.recycle()
        if (safeBmp !== bmp) safeBmp.recycle()

        val lums = FloatArray(tw * th)
        val clockZoneLums = ArrayList<Float>(tw * th / 2)
        val bins = 24
        val binWeight = FloatArray(bins)
        val binCos = FloatArray(bins)
        val binSin = FloatArray(bins)
        val binSat = FloatArray(bins)
        val binLit = FloatArray(bins)
        val binChroma = FloatArray(bins)

        var clockLumSum = 0f
        var clockWeightSum = 0f
        var globalLumSum = 0f
        var brightCount = 0f
        var darkCount = 0f
        var satSum = 0f

        var lightDx = 0f
        var lightDy = 0f
        var lightW = 0f

        var totalHueWeight = 0f

        for (y in 0 until th) {
            val ny = y.toFloat() / max(1, th - 1)
            val inClockY = ny in 0.05f..0.52f
            val inHeroY = ny in 0.16f..0.86f
            for (x in 0 until tw) {
                val nx = x.toFloat() / max(1, tw - 1)
                val c = px[y * tw + x]
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                lums[y * tw + x] = lum

                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val chroma = maxC - minC
                val lit = (maxC + minC) * 0.5f
                val sat = if (chroma <= 0.0001f) 0f else chroma / max(0.001f, 1f - abs(2f * lit - 1f))

                globalLumSum += lum
                satSum += sat
                if (lum > 0.72f) brightCount += 1f
                if (lum < 0.16f) darkCount += 1f

                if (inClockY && nx in 0.08f..0.92f) {
                    val cw = if (ny in 0.10f..0.44f && nx in 0.18f..0.82f) 1.6f else 1.0f
                    clockLumSum += lum * cw
                    clockWeightSum += cw
                    clockZoneLums.add(lum)
                }

                if (ny < 0.62f) {
                    val lw = lum * lum
                    lightDx += (nx - 0.5f) * lw
                    lightDy += (ny - 0.32f) * lw
                    lightW += lw
                }

                if (chroma > 0.055f) {
                    var hue = when (maxC) {
                        r -> (g - b) / chroma + (if (g < b) 6f else 0f)
                        g -> (b - r) / chroma + 2f
                        else -> (r - g) / chroma + 4f
                    } * 60f
                    if (hue < 0f) hue += 360f
                    if (hue >= 360f) hue -= 360f

                    val centerBias = if (inHeroY && nx in 0.14f..0.86f) 1.65f else 0.90f
                    val clockBias = if (inClockY) 1.15f else 1.0f
                    val midToneBias = 1f - abs(lit - 0.52f) * 0.38f
                    val hw = chroma * chroma * (0.35f + 0.65f * sat) * centerBias * clockBias * midToneBias

                    val bin = ((hue / 15f).toInt()).coerceIn(0, bins - 1)
                    val rad = hue * (PI.toFloat() / 180f)
                    binWeight[bin] += hw
                    binCos[bin] += cos(rad) * hw
                    binSin[bin] += sin(rad) * hw
                    binSat[bin] += sat * hw
                    binLit[bin] += lit * hw
                    binChroma[bin] += chroma * hw
                    totalHueWeight += hw
                }
            }
        }

        // Local clock-zone texture/edge energy
        var detailSum = 0f
        var detailN = 0
        val yStart = (th * 0.07f).roundToInt().coerceIn(0, th - 2)
        val yEnd = (th * 0.52f).roundToInt().coerceIn(yStart + 1, th - 1)
        val xStart = (tw * 0.12f).roundToInt().coerceIn(0, tw - 2)
        val xEnd = (tw * 0.88f).roundToInt().coerceIn(xStart + 1, tw - 1)
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val idx = y * tw + x
                detailSum += abs(lums[idx] - lums[idx + 1]) + abs(lums[idx] - lums[idx + tw])
                detailN++
            }
        }

        clockZoneLums.sort()
        val czSize = clockZoneLums.size
        val clockLumP15 = if (czSize > 0) clockZoneLums[(czSize * 0.15f).toInt().coerceIn(0, czSize - 1)] else 0.3f
        val clockLumP85 = if (czSize > 0) clockZoneLums[(czSize * 0.85f).toInt().coerceIn(0, czSize - 1)] else 0.7f
        val clockSpread = (clockLumP85 - clockLumP15).coerceIn(0f, 1f)

        val pixelCount = (tw * th).toFloat().coerceAtLeast(1f)
        val clockLum = if (clockWeightSum > 0f) clockLumSum / clockWeightSum else globalLumSum / pixelCount
        val globalLum = globalLumSum / pixelCount
        val brightFrac = brightCount / pixelCount
        val meanSat = satSum / pixelCount
        val clockDetail = if (detailN > 0) detailSum / detailN else 0f

        // Smooth 3-neighbor circular histogram to locate hero dominant & secondary hues
        val smoothW = FloatArray(bins)
        for (i in 0 until bins) {
            val prev = (i - 1 + bins) % bins
            val next = (i + 1) % bins
            smoothW[i] = binWeight[i] + 0.45f * (binWeight[prev] + binWeight[next])
        }

        var peakBin = 0
        for (i in 1 until bins) {
            if (smoothW[i] > smoothW[peakBin]) peakBin = i
        }
        val peakHasColor = totalHueWeight > 0.35f && smoothW[peakBin] > 0.01f

        var heroHue = peakBin * 15f + 7.5f
        var heroSat = 0f
        var heroLit = 0.5f
        var heroChroma = 0f
        if (peakHasColor) {
            var sumW = 0f
            var sumCos = 0f
            var sumSin = 0f
            var sumS = 0f
            var sumL = 0f
            var sumC = 0f
            for (off in -1..1) {
                val bIdx = (peakBin + off + bins) % bins
                val k = if (off == 0) 1f else 0.6f
                val wBin = binWeight[bIdx] * k
                sumW += wBin
                sumCos += binCos[bIdx] * k
                sumSin += binSin[bIdx] * k
                sumS += binSat[bIdx] * k
                sumL += binLit[bIdx] * k
                sumC += binChroma[bIdx] * k
            }
            if (sumW > 0.0001f) {
                var ang = atan2(sumSin, sumCos) * (180f / PI.toFloat())
                if (ang < 0f) ang += 360f
                heroHue = ang
                heroSat = (sumS / sumW).coerceIn(0f, 1f)
                heroLit = (sumL / sumW).coerceIn(0f, 1f)
                heroChroma = (sumC / sumW).coerceIn(0f, 1f)
            }
        }

        // Secondary accent hue (>= 45 deg away from heroHue) for harmony dots
        var secHue = (heroHue + 32f) % 360f
        var secSat = heroSat
        var secLit = (heroLit + 0.16f).coerceIn(0.28f, 0.82f)
        var secPeakVal = 0f
        if (peakHasColor) {
            for (i in 0 until bins) {
                val candH = i * 15f + 7.5f
                var diff = abs(candH - heroHue)
                if (diff > 180f) diff = 360f - diff
                if (diff >= 45f && smoothW[i] > secPeakVal && binWeight[i] > 0.001f) {
                    secPeakVal = smoothW[i]
                    secHue = candH
                    secSat = (binSat[i] / binWeight[i]).coerceIn(0f, 1f)
                    secLit = (binLit[i] / binWeight[i]).coerceIn(0.22f, 0.82f)
                }
            }
        }

        // Light-source angle in upper wallpaper mapped smoothly onto crisp diagonal spec bevel (36..144 deg)
        var innerAngle = 59f
        if (lightW > 0.04f) {
            val lnx = (lightDx / lightW).coerceIn(-0.48f, 0.48f)
            val lny = (lightDy / lightW).coerceIn(-0.48f, 0.48f)
            if (hypot(lnx, lny) > 0.04f) {
                var rawDeg = atan2(lny, lnx) * (180f / PI.toFloat())
                if (rawDeg < 0f) rawDeg += 360f
                val normDeg = ((rawDeg % 180f) + 180f) % 180f
                // Keep away from dead 90 deg perpendicular and extremes so every glyph stem & arch gleams
                innerAngle = if (normDeg in 84f..96f) {
                    if (lnx >= 0f) 66f else 114f
                } else {
                    normDeg.coerceIn(36f, 144f)
                }
            }
        }

        // Preserve default/user inner shadow thickness, fade, and pure crisp white intensity; ONLY adapt angle!
        val innerThickness = baseCfg.innerThickness
        val innerFade = baseCfg.innerFade
        val innerColorInt = Color.WHITE
        val innerOpacity = 100

        // Choose harmonious translucent crystal tint & refraction that NEVER blends invisibly into background
        var targetHue = heroHue
        var targetSatPct: Float
        var targetLitPct: Float
        var clockOpacity: Int
        var clockGlass: Int
        var clockRefraction: Int
        var clockBevel: Int
        val profileLabel: String

        val isTrueDarkHeroScene = clockLum < 0.25f && clockLumP85 < 0.34f && heroChroma > 0.16f && heroSat > 0.38f && peakHasColor
        val isUniformBrightClockBg = clockLumP15 > 0.54f && clockLum > 0.66f
        val isColorfulOrSplitLight = peakHasColor && (meanSat >= 0.15f || heroChroma > 0.11f || clockSpread > 0.28f)

        val detailBoost = (clockDetail * 145f + clockSpread * 16f).coerceIn(0f, 26f)

        when {
            glassPreset -> {
                // Ultra-refractive 3D Crystal "Glass" mode (high Snell displacement + very wide bevel refraction + frosted blur separation)
                if (isUniformBrightClockBg) {
                    targetHue = if (peakHasColor) heroHue else 214f
                    targetSatPct = if (peakHasColor) (heroSat * 52f + 14f).coerceIn(22f, 58f) else 20f
                    targetLitPct = 44f
                    clockOpacity = (14f + (clockLum - 0.66f).coerceIn(0f, 0.34f) * 16f).roundToInt().coerceIn(14, 22)
                } else if (peakHasColor && (meanSat >= 0.14f || heroChroma > 0.10f)) {
                    targetHue = heroHue
                    targetSatPct = (heroSat * 46f + 16f).coerceIn(20f, 62f)
                    targetLitPct = (84f + clockSpread * 6f).coerceIn(82f, 92f)
                    clockOpacity = (7f + meanSat * 8f).roundToInt().coerceIn(7, 11)
                } else {
                    targetHue = if (peakHasColor && heroChroma > 0.03f) heroHue else 214f
                    targetSatPct = if (peakHasColor) (heroSat * 18f).coerceIn(6f, 16f) else 10f
                    targetLitPct = 88f
                    clockOpacity = 8
                }
                clockGlass = (94f + detailBoost * 0.18f).roundToInt().coerceIn(94, 98)
                clockRefraction = (146f + detailBoost * 0.25f).roundToInt().coerceIn(146, 150)
                clockBevel = (194f + detailBoost * 0.60f).roundToInt().coerceIn(192, 212)
                profileLabel = "مظهر Glass الكريستالي حسب خلفيتك"
            }

            isTrueDarkHeroScene -> {
                // Rich dramatic accent tint over deep dark background (e.g. crimson red in dark forest)
                targetSatPct = (heroSat * 100f).coerceIn(68f, 88f)
                targetLitPct = (heroLit * 82f + 14f).coerceIn(34f, 48f)
                clockOpacity = (46f + (0.25f - clockLum).coerceIn(0f, 0.25f) * 72f).roundToInt().coerceIn(44, 62)
                clockGlass = (64f + detailBoost * 0.65f).roundToInt().coerceIn(62, 82)
                clockRefraction = (98f + detailBoost * 0.95f).roundToInt().coerceIn(96, 126)
                clockBevel = (112f + detailBoost * 0.55f).roundToInt().coerceIn(110, 128)
                profileLabel = "تناغم درامي مع ألوان خلفيتك"
            }

            isUniformBrightClockBg -> {
                // Bright white/snow everywhere behind clock: cool deep crystal contrast so numerals read effortlessly
                targetHue = if (peakHasColor) heroHue else 214f
                targetSatPct = if (peakHasColor) (heroSat * 68f + 18f).coerceIn(28f, 72f) else 22f
                targetLitPct = 36f
                clockOpacity = (30f + (clockLum - 0.66f).coerceIn(0f, 0.34f) * 32f).roundToInt().coerceIn(28, 42)
                clockGlass = (80f + detailBoost * 0.50f).roundToInt().coerceIn(78, 94)
                clockRefraction = (118f + detailBoost * 0.90f).roundToInt().coerceIn(115, 142)
                clockBevel = (128f + detailBoost * 0.50f).roundToInt().coerceIn(126, 144)
                profileLabel = "تباين بلوري واضح مع الخلفية المضيئة"
            }

            isColorfulOrSplitLight -> {
                // Luminous translucent Liquid Glass (e.g. sunlit golden room, cyan glacier sky, angelic rose feathers)
                val isHighKeyPastel = (brightFrac > 0.22f || clockLum > 0.48f) && meanSat < 0.26f
                if (isHighKeyPastel) {
                    targetSatPct = (heroSat * 60f + 16f).coerceIn(26f, 56f)
                    targetLitPct = 76f
                    clockOpacity = (26f + brightFrac * 18f).roundToInt().coerceIn(24, 38)
                } else {
                    targetSatPct = (heroSat * 76f + 22f).coerceIn(52f, 84f)
                    // Bright crisp crystal lift so colored digits pop against shadows AND refract light beams cleanly
                    targetLitPct = (74f + clockSpread * 12f).coerceIn(72f, 86f)
                    clockOpacity = (20f + meanSat * 14f).roundToInt().coerceIn(18, 30)
                }
                clockGlass = (78f + detailBoost * 0.65f).roundToInt().coerceIn(76, 95)
                clockRefraction = (116f + detailBoost * 1.05f).roundToInt().coerceIn(114, 145)
                clockBevel = (122f + detailBoost * 0.65f).roundToInt().coerceIn(120, 140)
                profileLabel = "زجاج سائل متناغم وانكسار واضح"
            }

            else -> {
                // Moody smoky corridor or monochrome minimalism
                targetHue = if (peakHasColor && heroChroma > 0.03f) heroHue else 214f
                targetSatPct = if (peakHasColor) (heroSat * 22f).coerceIn(6f, 18f) else 10f
                targetLitPct = 80f
                clockOpacity = (18f + (0.35f - clockLum).coerceIn(0f, 0.35f) * 26f).roundToInt().coerceIn(16, 28)
                clockGlass = (80f + detailBoost * 0.55f).roundToInt().coerceIn(78, 94)
                clockRefraction = (114f + detailBoost * 0.90f).roundToInt().coerceIn(110, 138)
                clockBevel = (118f + detailBoost * 0.50f).roundToInt().coerceIn(116, 134)
                profileLabel = "زجاج دخاني شفاف بإطار أبيض"
            }
        }

        var chosenColor = AppConstants.hslToRgbInt(targetHue, targetSatPct, targetLitPct)

        // Strict readability guard: never allow chosen clock color to camouflage into background luminance behind digits
        val cLum = relativeLuminance(chosenColor)
        if (abs(cLum - clockLum) < 0.22f && !isTrueDarkHeroScene) {
            if (clockLum < 0.50f) {
                targetLitPct = max(targetLitPct, 82f)
                chosenColor = AppConstants.hslToRgbInt(targetHue, min(targetSatPct, 66f), targetLitPct)
                clockOpacity = max(clockOpacity, if (glassPreset) 10 else 24)
            } else {
                targetLitPct = min(targetLitPct, 34f)
                chosenColor = AppConstants.hslToRgbInt(targetHue, max(targetSatPct, 32f), targetLitPct)
                clockOpacity = max(clockOpacity, if (glassPreset) 16 else 32)
            }
        }

        val dotHero = if (peakHasColor) {
            AppConstants.hslToRgbInt(heroHue, (heroSat * 100f).coerceIn(32f, 92f), (heroLit * 100f).coerceIn(36f, 68f))
        } else {
            AppConstants.hslToRgbInt(215f, 12f, (globalLum * 100f).coerceIn(32f, 64f))
        }
        val dotSecondary = if (secPeakVal > 0.01f) {
            AppConstants.hslToRgbInt(secHue, (secSat * 100f).coerceIn(28f, 88f), (secLit * 100f).coerceIn(38f, 74f))
        } else {
            innerColorInt
        }

        return SmartClockStyle(
            colorHex = AppConstants.toHex(chosenColor),
            colorInt = chosenColor,
            clockOpacity = clockOpacity.coerceIn(0, 100),
            innerColorHex = AppConstants.toHex(innerColorInt),
            innerColorInt = innerColorInt,
            innerOpacity = innerOpacity.coerceIn(0, 100),
            innerThickness = innerThickness,
            innerAngle = innerAngle,
            innerFade = innerFade,
            clockGlass = clockGlass.coerceIn(0, 100),
            clockRefraction = clockRefraction.coerceIn(0, 150),
            clockBevel = clockBevel.coerceIn(0, 220),
            paletteDots = intArrayOf(dotHero, chosenColor, dotSecondary),
            profileLabel = profileLabel
        )
    }

    private fun relativeLuminance(color: Int): Float {
        return (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f
    }

    fun lerpAdaptiveColors(a: AdaptiveColors, b: AdaptiveColors, t: Float): AdaptiveColors {
        val k = t.coerceIn(0f, 1f)
        return AdaptiveColors(
            isLight = if (k < 0.5f) a.isLight else b.isLight,
            uiInk = lerpArgb(a.uiInk, b.uiInk, k),
            uiDim = lerpArgb(a.uiDim, b.uiDim, k),
            glassBg = lerpArgb(a.glassBg, b.glassBg, k),
            clockTop = lerpArgb(a.clockTop, b.clockTop, k),
            clockTintTop = lerpArgb(a.clockTintTop, b.clockTintTop, k),
            clockTintBot = lerpArgb(a.clockTintBot, b.clockTintBot, k),
            clockShadowOp = a.clockShadowOp + (b.clockShadowOp - a.clockShadowOp) * k,
            clockShadowTopColor = lerpArgb(a.clockShadowTopColor, b.clockShadowTopColor, k)
        )
    }

    fun lerpArgb(c0: Int, c1: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val a = (Color.alpha(c0) + (Color.alpha(c1) - Color.alpha(c0)) * k).roundToInt().coerceIn(0, 255)
        val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * k).roundToInt().coerceIn(0, 255)
        val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * k).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * k).roundToInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    fun lerpAngleDeg(a0: Float, a1: Float, t: Float): Float {
        var diff = (((a1 - a0 + 540f) % 360f) + 360f) % 360f - 180f
        if (diff < -180f) diff += 360f
        return ((a0 + diff * t.coerceIn(0f, 1f)) % 360f + 360f) % 360f
    }
}

/**
 * Lightweight prism aura wave sweeping upward across the clock when colors are extracted from the wallpaper.
 */
class WallpaperHarmonyAuraView(context: Context) : View(context) {
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim: ValueAnimator? = null
    private var progress = 0f
    private var topY = 0f
    private var botY = 0f
    private var dots = intArrayOf(0xFF38BDF8.toInt(), 0xFFA78BFA.toInt(), Color.WHITE)

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    fun playSweep(palette: IntArray, clockTopPx: Float, clockBotPx: Float) {
        if (palette.size >= 3) {
            dots = palette.copyOf(3)
        }
        topY = clockTopPx
        botY = max(clockTopPx + 40f, clockBotPx)
        anim?.cancel()
        progress = 0f
        visibility = VISIBLE
        alpha = 1f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 760L
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                progress = (a.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                    anim = null
                }
            })
            start()
        }
    }

    fun cancelSweep() {
        anim?.cancel()
        anim = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 1f || vh <= 1f) return
        val p = progress
        val env = sin(p * PI).toFloat().coerceIn(0f, 1f)
        if (env <= 0.005f) return

        val span = max(120f, botY - topY)
        val bandCy = botY + span * 0.25f - p * span * 1.55f
        val halfH = span * 0.52f

        val c0 = Color.argb((env * 66f).roundToInt().coerceIn(0, 255), Color.red(dots[0]), Color.green(dots[0]), Color.blue(dots[0]))
        val c1 = Color.argb((env * 92f).roundToInt().coerceIn(0, 255), Color.red(dots[1]), Color.green(dots[1]), Color.blue(dots[1]))
        val c2 = Color.argb((env * 62f).roundToInt().coerceIn(0, 255), Color.red(dots[2]), Color.green(dots[2]), Color.blue(dots[2]))

        ribbonPaint.shader = LinearGradient(
            vw * 0.14f, bandCy + halfH,
            vw * 0.86f, bandCy - halfH,
            intArrayOf(0, c0, c1, c2, 0),
            floatArrayOf(0f, 0.28f, 0.52f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, max(0f, topY - span * 0.35f), vw, min(vh, botY + span * 0.35f), ribbonPaint)

        // Two delicate chromatic orb glints along the clock horizon
        val orbAlpha = (env * 105f).roundToInt().coerceIn(0, 255)
        for (i in 0 until 2) {
            val ox = vw * (if (i == 0) 0.34f + p * 0.12f else 0.66f - p * 0.12f)
            val oy = bandCy + (if (i == 0) -12f else 14f)
            val rad = span * 0.28f
            val col = dots[i]
            val ca = Color.argb(orbAlpha, Color.red(col), Color.green(col), Color.blue(col))
            sparkPaint.shader = RadialGradient(ox, oy, rad, intArrayOf(ca, 0), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(ox, oy, rad, sparkPaint)
        }
    }
}

/**
 * Floating Liquid Glass capsule displaying the 3 wallpaper-extracted palette orbs and optical balance.
 */
class WallpaperHarmonyPill(context: Context) : LinearLayout(context) {
    private val dens = resources.displayMetrics.density
    private val orbs = Array(3) { View(context) }
    private val titleView = TextView(context)
    private val badgeView = TextView(context)
    private var hideJob: Runnable? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        visibility = GONE
        alpha = 0f
        isClickable = false
        isFocusable = false
        setPadding(
            (12f * dens).roundToInt(),
            (7f * dens).roundToInt(),
            (13f * dens).roundToInt(),
            (7f * dens).roundToInt()
        )
        background = GradientDrawable().apply {
            setColor(Color.argb(214, 18, 20, 28))
            cornerRadius = 999f
            setStroke((0.85f * dens).roundToInt(), Color.argb(56, 255, 255, 255))
        }
        elevation = 8f * dens

        val orbRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sz = (11f * dens).roundToInt()
        val gap = (4f * dens).roundToInt()
        for (i in 0 until 3) {
            val v = orbs[i]
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke((1f * dens).roundToInt(), Color.argb(180, 255, 255, 255))
            }
            v.layoutParams = LayoutParams(sz, sz).apply {
                if (i < 2) marginEnd = gap
            }
            orbRow.addView(v)
        }

        titleView.apply {
            text = "ألوان الساعة من خلفيتك"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = (8f * dens).roundToInt()
            }
        }

        badgeView.apply {
            maxLines = 1
            setTextColor(0xFFD8E8FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding((7f * dens).roundToInt(), (2f * dens).roundToInt(), (7f * dens).roundToInt(), (2f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(38, 255, 255, 255))
                cornerRadius = 999f
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = (7f * dens).roundToInt()
            }
        }

        addView(orbRow)
        addView(titleView)
        addView(badgeView)
    }

    fun showPalette(palette: IntArray, glassPct: Int, refractPct: Int, label: String) {
        hideJob?.let { removeCallbacks(it) }
        animate().cancel()

        for (i in 0 until 3) {
            val col = palette.getOrElse(i) { Color.WHITE }
            (orbs[i].background as? GradientDrawable)?.setColor(col)
            orbs[i].animate().cancel()
            orbs[i].scaleX = 0.25f
            orbs[i].scaleY = 0.25f
            orbs[i].animate()
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(i * 65L)
                .setDuration(340L)
                .setInterpolator(OvershootInterpolator(2.1f))
                .start()
        }

        titleView.text = label
        badgeView.text = "زجاج $glassPct% · انكسار $refractPct%"
        (background as? GradientDrawable)?.setStroke(
            (0.95f * dens).roundToInt(),
            Color.argb(95, Color.red(palette[0]), Color.green(palette[0]), Color.blue(palette[0]))
        )

        visibility = VISIBLE
        alpha = 0f
        translationY = -10f * dens
        scaleX = 0.88f
        scaleY = 0.88f
        animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(310L)
            .setInterpolator(OvershootInterpolator(1.35f))
            .start()

        hideJob = Runnable { hide() }.also { postDelayed(it, 2350L) }
    }

    fun hide(immediate: Boolean = false) {
        hideJob?.let { removeCallbacks(it) }
        hideJob = null
        animate().cancel()
        if (immediate) {
            alpha = 0f
            visibility = GONE
            return
        }
        animate()
            .alpha(0f)
            .translationY(-8f * dens)
            .scaleX(0.93f)
            .scaleY(0.93f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator(1.7f))
            .withEndAction { visibility = GONE }
            .start()
    }
}
