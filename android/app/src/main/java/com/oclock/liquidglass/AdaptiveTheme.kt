package com.oclock.liquidglass

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

data class AdaptiveColors(
    val isLight: Boolean,
    val uiInk: Int,
    val uiDim: Int,
    val glassBg: Int,
    val clockTop: Int,
    val clockTintTop: Int,
    val clockTintBot: Int,
    val clockShadowOp: Float,
    val clockShadowTopColor: Int
)

class AdaptiveTheme {
    private var lastLight: Boolean? = null
    private var sampledKey: String = ""

    fun samplePresetDefaults(cfg: LockConfig): AdaptiveColors {
        val light = cfg.wall == "daylight"
        return buildColors(light, cfg)
    }

    fun sample(wallKey: String, bmp: Bitmap, cfg: LockConfig): AdaptiveColors {
        if (sampledKey != wallKey || lastLight == null) {
            val tiny = Bitmap.createScaledBitmap(bmp, 24, 24, true)
            val px = IntArray(24 * 24)
            tiny.getPixels(px, 0, 24, 0, 0, 24, 24)
            if (tiny !== bmp) tiny.recycle()

            var lum = 0.0
            for (c in px) {
                val r = Color.red(c) / 255.0
                val g = Color.green(c) / 255.0
                val b = Color.blue(c) / 255.0
                lum += 0.2126 * r + 0.7152 * g + 0.0722 * b
            }
            lum /= px.size
            lastLight = when {
                lastLight == null -> lum > 0.52
                lastLight == false && lum > 0.56 -> true
                lastLight == true && lum < 0.44 -> false
                else -> lastLight
            }
            sampledKey = wallKey
        }

        return buildColors(lastLight ?: false, cfg)
    }

    private fun buildColors(light: Boolean, cfg: LockConfig): AdaptiveColors {
        val uiInk = if (light) 0xFF12151C.toInt() else Color.WHITE
        val uiDim = if (light) Color.argb(173, 18, 21, 28) else Color.argb(189, 255, 255, 255)
        val glassBg = if (light) Color.argb(20, 255, 255, 255) else Color.argb(10, 255, 255, 255)
        val clockTop = if (cfg.color == "auto") uiInk else AppConstants.parseHexColor(cfg.color, uiInk)

        val p = (cfg.clockOpacity / 100f).coerceIn(0f, 1f)
        val shadowOp = if (p <= 0f) 0f else p * 0.24f

        val base = if (cfg.color == "auto") {
            if (light) Color.rgb(18, 24, 34) else Color.WHITE
        } else {
            AppConstants.parseHexColor(cfg.color, Color.WHITE)
        }
        val br = Color.red(base)
        val bg = Color.green(base)
        val bb = Color.blue(base)

        val tintTop: Int
        val tintBot: Int
        when {
            p <= 0.001f -> {
                tintTop = Color.TRANSPARENT
                tintBot = Color.TRANSPARENT
            }
            p >= 0.995f -> {
                tintTop = Color.rgb(br, bg, bb)
                tintBot = tintTop
            }
            else -> {
                val a1 = p
                val a2 = if (p < 0.72f) p * 0.45f else p * (0.45f + 0.55f * ((p - 0.72f) / 0.28f))
                tintTop = Color.argb((a1 * 255f).roundToInt().coerceIn(0, 255), br, bg, bb)
                tintBot = Color.argb((a2 * 255f).roundToInt().coerceIn(0, 255), br, bg, bb)
            }
        }

        return AdaptiveColors(
            isLight = light,
            uiInk = uiInk,
            uiDim = uiDim,
            glassBg = glassBg,
            clockTop = clockTop,
            clockTintTop = tintTop,
            clockTintBot = tintBot,
            clockShadowOp = shadowOp,
            clockShadowTopColor = clockTop
        )
    }
}
