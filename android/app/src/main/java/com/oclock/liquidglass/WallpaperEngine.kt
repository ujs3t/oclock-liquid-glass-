package com.oclock.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object WallpaperEngine {
    private val cache = HashMap<String, Bitmap>(6)
    private val thumbCache = HashMap<String, Bitmap>(6)

    fun makePlaceholderDrawable(name: String): GradientDrawable {
        val cols = when (PresetWall.fromId(name)) {
            PresetWall.ANGEL -> intArrayOf(0xFFA9C1DD.toInt(), 0xFF9DB0C9.toInt(), 0xFF333E51.toInt())
            PresetWall.OBSIDIAN -> intArrayOf(0xFF171B24.toInt(), 0xFF0A0C11.toInt(), 0xFF000000.toInt())
            PresetWall.DUSK -> intArrayOf(0xFF2A1C46.toInt(), 0xFFD9705C.toInt(), 0xFF2B2039.toInt())
            PresetWall.AURORA -> intArrayOf(0xFF0B1030.toInt(), 0xFF1C4D8C.toInt(), 0xFF101A34.toInt())
            PresetWall.DAYLIGHT -> intArrayOf(0xFFF2F6FB.toInt(), 0xFFE2EBF4.toInt(), 0xFFBFD0E2.toInt())
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, cols)
    }

    fun peekPreset(name: String): Bitmap? {
        val key = PresetWall.fromId(name).id
        return synchronized(cache) { cache[key] }
    }

    fun drawPreset(name: String, w: Int = 900, h: Int = 1800): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(false)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sx = { v: Float -> v * (w / 900f) }
        val sy = { v: Float -> v * (h / 1800f) }

        fun sky(colors: IntArray, stops: FloatArray) {
            p.shader = LinearGradient(0f, 0f, w * 0.35f, h.toFloat(), colors, stops, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
        }

        fun blob(cx: Float, cy: Float, r: Float, color: Int) {
            val rr = (r * min(w / 900f, h / 1800f)).coerceAtLeast(1f)
            p.shader = RadialGradient(cx, cy, rr, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, rr, p)
            p.shader = null
        }

        when (name) {
            "angel" -> {
                sky(
                    intArrayOf(
                        0xFFA9C1DD.toInt(), 0xFFDFCBD7.toInt(), 0xFF9DB0C9.toInt(),
                        0xFF5A6A81.toInt(), 0xFF333E51.toInt()
                    ),
                    floatArrayOf(0f, 0.34f, 0.64f, 0.86f, 1f)
                )
                blob(sx(220f), sy(400f), 420f, Color.argb(140, 255, 255, 255))
                blob(sx(660f), sy(300f), 380f, Color.argb(107, 255, 255, 255))
                blob(sx(450f), sy(980f), 520f, Color.argb(56, 255, 255, 255))
                val step = sx(40f).roundToInt().coerceAtLeast(4)
                for (i in 0 until 5) {
                    val y0 = sy(760f + i * 110f)
                    val a = ((0.26f - i * 0.04f) * 255f).roundToInt().coerceIn(0, 255)
                    p.shader = LinearGradient(
                        0f, y0 - sy(70f), 0f, y0 + sy(70f),
                        intArrayOf(0, Color.argb(a, 255, 255, 255), 0),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    val path = Path().apply {
                        moveTo(0f, y0)
                        var px = 0
                        while (px <= w) {
                            lineTo(px.toFloat(), y0 + sin((px / w.toFloat()) * 5f + i) * sy(28f))
                            px += step
                        }
                        lineTo(w.toFloat(), y0 + sy(150f))
                        lineTo(0f, y0 + sy(150f))
                        close()
                    }
                    c.drawPath(path, p)
                }
                p.shader = RadialGradient(
                    w * 0.5f, sy(1100f), sx(620f).coerceAtLeast(1f),
                    Color.argb(87, 255, 250, 242), Color.argb(0, 255, 250, 242),
                    Shader.TileMode.CLAMP
                )
                c.drawRect(0f, sy(400f), w.toFloat(), h.toFloat(), p)

                p.shader = RadialGradient(
                    w * 0.5f, h * 0.5f, (h * 0.8f).coerceAtLeast(1f),
                    intArrayOf(0, 0, Color.argb(87, 20, 26, 38)),
                    floatArrayOf(0f, 0.25f, 1f),
                    Shader.TileMode.CLAMP
                )
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null
            }

            "obsidian" -> {
                sky(
                    intArrayOf(0xFF171B24.toInt(), 0xFF0A0C11.toInt(), 0xFF000000.toInt()),
                    floatArrayOf(0f, 0.5f, 1f)
                )
                blob(w * 0.5f, h * 0.34f, 900f * 0.9f, Color.argb(51, 90, 110, 150))
            }

            "dusk" -> {
                sky(
                    intArrayOf(
                        0xFF2A1C46.toInt(), 0xFF7C3F6B.toInt(), 0xFFD9705C.toInt(),
                        0xFFF2B07A.toInt(), 0xFF2B2039.toInt()
                    ),
                    floatArrayOf(0f, 0.3f, 0.58f, 0.78f, 1f)
                )
                blob(w * 0.7f, h * 0.66f, 300f, Color.argb(166, 255, 215, 160))
                blob(w * 0.2f, h * 0.2f, 420f, Color.argb(89, 120, 90, 200))
            }

            "aurora" -> {
                sky(
                    intArrayOf(
                        0xFF0B1030.toInt(), 0xFF1C4D8C.toInt(), 0xFF2AA88A.toInt(), 0xFF101A34.toInt()
                    ),
                    floatArrayOf(0f, 0.42f, 0.72f, 1f)
                )
                blob(sx(240f), sy(620f), 420f, Color.argb(115, 90, 255, 190))
                blob(sx(700f), sy(900f), 460f, Color.argb(115, 120, 90, 255))
                blob(sx(430f), sy(380f), 360f, Color.argb(89, 70, 200, 255))
            }

            "daylight" -> {
                sky(
                    intArrayOf(0xFFF2F6FB.toInt(), 0xFFE2EBF4.toInt(), 0xFFBFD0E2.toInt()),
                    floatArrayOf(0f, 0.46f, 1f)
                )
                blob(w * 0.7f, sy(380f), 420f, Color.argb(217, 255, 255, 255))
            }

            else -> {
                sky(intArrayOf(0xFF242A36.toInt(), 0xFF0C1016.toInt()), floatArrayOf(0f, 1f))
                blob(w * 0.5f, h * 0.3f, 900f * 0.8f, Color.argb(56, 120, 140, 180))
            }
        }
        return bmp
    }

    fun getWallpaper(name: String, customWall: Bitmap?): Bitmap {
        if (name == "custom" && customWall != null) return customWall
        val key = PresetWall.fromId(name).id
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val bmp = drawPreset(key, 720, 1440)
        synchronized(cache) {
            return cache.getOrPut(key) { bmp }
        }
    }

    fun getThumb(name: String, customWall: Bitmap?): Bitmap {
        if (name == "custom" && customWall != null) {
            return Bitmap.createScaledBitmap(customWall, 84, 108, true)
        }
        val key = PresetWall.fromId(name).id
        synchronized(thumbCache) {
            thumbCache[key]?.let { return it }
        }
        val bmp = drawPreset(key, 84, 108)
        synchronized(thumbCache) {
            return thumbCache.getOrPut(key) { bmp }
        }
    }
}
