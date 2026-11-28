package com.oclock.liquidglass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class NumeralMetrics(val capRatio: Float, val refMidRel: Float)

data class StretchedGlyph(
    val alphaBmp: Bitmap,
    val w: Int,
    val h: Int,
    val xCenterOffset: Float,
    val yCenterRel: Float
)

data class ColonDots(
    val topBmp: Bitmap?,
    val botBmp: Bitmap?,
    val topW: Int,
    val topH: Int,
    val botW: Int,
    val botH: Int,
    val natSep: Float
)

data class ClockArtifact(
    val alphaBmp: Bitmap,
    val fieldBmp: Bitmap,
    val alphaShader: BitmapShader?,
    val fieldShader: BitmapShader?,
    val maxAbsCss: Float,
    val wDp: Float,
    val hDp: Float,
    val wPx: Int,
    val hPx: Int,
    val ss: Float,
    val sizeDp: Float,
    val textWDp: Float,
    val textHDp: Float,
    val lineHDp: Float,
    val padDp: Float,
    val inkLeftDp: Float,
    val inkTopDp: Float,
    val inkRightDp: Float,
    val inkBottomDp: Float,
    val sx: Float,
    val sy: Float,
    val minuteKey: String,
    val maskKey: String
) {
    val byteSize: Long get() = alphaBmp.allocationByteCount.toLong() + fieldBmp.allocationByteCount.toLong()
}

class ClockGlyphRenderer(private val context: Context) {
    private val typefaceCache = HashMap<String, Typeface>(12)
    private val metricsCache = HashMap<String, NumeralMetrics>(12)
    private val glyphCache = object : LinkedHashMap<String, StretchedGlyph>(28, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StretchedGlyph>?): Boolean {
            return size > 24
        }
    }
    private val colonCache = object : LinkedHashMap<String, ColonDots>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ColonDots>?): Boolean {
            return size > 8
        }
    }
    private val artifactCache = object : LinkedHashMap<String, ClockArtifact>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClockArtifact>?): Boolean {
            return size > 3
        }
    }

    fun getCacheBytes(): Long = synchronized(artifactCache) {
        artifactCache.values.sumOf { it.byteSize }
    }

    fun clearTransientGlyphs() {
        synchronized(glyphCache) { glyphCache.clear() }
        synchronized(colonCache) { colonCache.clear() }
    }

    fun getTypeface(fontId: String): Typeface {
        synchronized(typefaceCache) {
            return typefaceCache.getOrPut(fontId) {
                val f = ClockFont.fromId(fontId)
                if (f.assetPath != null) {
                    try {
                        Typeface.createFromAsset(context.assets, f.assetPath)
                    } catch (_: Throwable) {
                        Typeface.DEFAULT_BOLD
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= 28) {
                        Typeface.create(Typeface.SANS_SERIF, 600, false)
                    } else {
                        Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    }
                }
            }
        }
    }

    fun measureNumeralMetrics(fontId: String): NumeralMetrics {
        synchronized(metricsCache) {
            metricsCache[fontId]?.let { return it }
            val tf = getTypeface(fontId)
            val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf
                textSize = 100f
                textAlign = Paint.Align.CENTER
                color = Color.WHITE
            }
            val buf = ByteArray(160 * 160)
            var yMin = 160
            var yMax = -1
            for (sample in arrayOf("0", "8", "6")) {
                bmp.eraseColor(Color.TRANSPARENT)
                c.drawText(sample, 80f, 80f, p)
                ByteBuffer.wrap(buf).also {
                    it.rewind()
                    bmp.copyPixelsToBuffer(it)
                }
                for (y in 0 until 160) {
                    val row = y * 160
                    for (x in 0 until 160) {
                        if ((buf[row + x].toInt() and 0xFF) > 10) {
                            if (y < yMin) yMin = y
                            if (y > yMax) yMax = y
                        }
                    }
                }
            }
            bmp.recycle()
            val capRatio = if (yMax >= yMin) ((yMax - yMin + 1) / 100f).coerceIn(0.55f, 0.92f) else 0.74f
            // Baseline offset relative to 100px so optical numeral center lands at 80f
            val refMidRel = if (yMax >= yMin) (((yMin + yMax + 1) * 0.5f) - 80f) / 100f else 0f
            return NumeralMetrics(capRatio, refMidRel).also { metricsCache[fontId] = it }
        }
    }

    private fun stretchDigitBitmap(
        ch: Char,
        fontPx: Float,
        fontId: String,
        sx: Float,
        sy: Float,
        ss: Float,
        refMidRel: Float
    ): StretchedGlyph? {
        val key = "$ch|${"%.1f".format(fontPx)}|${"%.3f".format(sx)}|${"%.3f".format(sy)}|${"%.2f".format(ss)}|$fontId"
        synchronized(glyphCache) {
            glyphCache[key]?.let { return it }
        }

        val vSub = max(2, ceil(sy * 1.6f).toInt().coerceAtMost(4))
        val srcPx = fontPx * ss
        val sw = ceil(srcPx * sx * 1.25f).toInt() + 16
        val sh = ceil(srcPx * vSub * 1.55f).toInt() + 16
        val src = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
        val sc = Canvas(src)
        sc.save()
        sc.scale(sx, vSub.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = getTypeface(fontId)
            textSize = srcPx
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
        }
        val baseY = (sh / (2f * vSub)) - refMidRel * srcPx
        sc.drawText(ch.toString(), sw / (2f * sx), baseY, paint)
        sc.restore()

        val sData = ByteArray(sw * sh)
        ByteBuffer.wrap(sData).also {
            it.rewind()
            src.copyPixelsToBuffer(it)
        }
        src.recycle()

        var xMin = sw
        var xMax = -1
        var yMin = sh
        var yMax = -1
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until sw) {
                if ((sData[row + x].toInt() and 0xFF) > 10) {
                    if (x < xMin) xMin = x
                    if (x > xMax) xMax = x
                    if (y < yMin) yMin = y
                    if (y > yMax) yMax = y
                }
            }
        }
        if (xMax < xMin || yMax < yMin) return null

        val w = xMax - xMin + 1
        val g = yMax - yMin + 1
        val g0 = g.toFloat() / vSub
        val targetG = max(4, (g0 * sy).roundToInt())

        // Measure max continuous horizontal run per row (two vertical walls stay ~0.33, true horizontal bars reach ~0.60..0.96)
        val run = FloatArray(g)
        for (r in 0 until g) {
            val row = (yMin + r) * sw
            var cur = 0f
            var best = 0f
            for (x in xMin..xMax) {
                val a = (sData[row + x].toInt() and 0xFF) / 255f
                if (a >= 0.45f) {
                    cur += a
                    if (cur > best) best = cur
                } else {
                    cur = 0f
                }
            }
            run[r] = best / w
        }

        val bodyStart = floor(0.16f * g).toInt()
        val bodyEnd = max(bodyStart + 1, ceil(0.84f * g).toInt()).coerceAtMost(g)
        val bodyRun = FloatArray(bodyEnd - bodyStart) { run[bodyStart + it] }
        bodyRun.sort()
        val stemRun = if (bodyRun.isNotEmpty()) bodyRun[(bodyRun.size * 0.25f).toInt().coerceIn(0, bodyRun.lastIndex)] else 0.32f
        val barThresh = (stemRun * 1.48f).coerceIn(0.48f, 0.74f)

        var bar = FloatArray(g) { r ->
            val inValidBand = r < (0.16f * g) || r > (0.84f * g) || (r >= (0.32f * g) && r <= (0.82f * g))
            if (inValidBand && run[r] >= barThresh) 1f else 0f
        }

        val topEnd = floor(0.15f * g).toInt().coerceAtMost(g - 1)
        var lastTop = -1
        for (r in 0..topEnd) if (bar[r] > 0.5f) lastTop = r
        if (lastTop >= 0) for (r in 0..lastTop) bar[r] = 1f

        val botStart = floor(0.85f * g).toInt().coerceAtLeast(0)
        var firstBot = -1
        for (r in botStart until g) {
            if (bar[r] > 0.5f) {
                firstBot = r
                break
            }
        }
        if (firstBot >= 0) for (r in firstBot until g) bar[r] = 1f

        // Bridge small inner curve dips inside a horizontal bar (e.g. middle waist of '3') so bar never oscillates 1->0->1
        val gapMax = max(2, (0.075f * g).roundToInt())
        var ri = 0
        while (ri < g) {
            if (bar[ri] < 0.5f && ri > 0 && bar[ri - 1] > 0.5f) {
                var j = ri
                while (j < g && bar[j] < 0.5f && (j - ri) <= gapMax) j++
                if (j < g && bar[j] > 0.5f) {
                    for (k in ri until j) bar[k] = 1f
                }
                ri = j
            } else {
                ri++
            }
        }

        val rad = max(3, (0.060f * g).roundToInt())
        repeat(4) {
            val next = FloatArray(g)
            for (r in 0 until g) {
                var s = 0f
                var c = 0
                val a = max(0, r - rad)
                val b = min(g - 1, r + rad)
                for (k in a..b) {
                    s += bar[k]
                    c++
                }
                next[r] = s / c
            }
            bar = next
        }

        val maxBar = if (sy > 1.35f) 0.86f else 1.0f
        for (r in 0 until g) bar[r] *= maxBar

        var barMass = 0f
        var stemMass = 0f
        for (r in 0 until g) {
            barMass += bar[r] / vSub
            stemMass += (1f - bar[r]) / vSub
        }

        val localScale = FloatArray(g)
        if (sy >= 1.0f && stemMass > 0.14f * g0) {
            val stemStretch = max(1.0f, (targetG - barMass) / stemMass)
            for (r in 0 until g) {
                localScale[r] = bar[r] + (1f - bar[r]) * stemStretch
            }
        } else {
            for (r in 0 until g) localScale[r] = sy
        }

        val cum = DoubleArray(g + 1)
        for (r in 0 until g) cum[r + 1] = cum[r] + localScale[r] / vSub
        val norm = targetG / max(1e-6, cum[g])
        for (r in 1..g) cum[r] *= norm

        val padOut = 1
        val outW = w + padOut * 2
        val outH = targetG + padOut * 2
        val outBytes = ByteArray(outW * outH)
        var rCur = 0
        for (dy in 0 until targetG) {
            val target = dy + 0.5
            while (rCur < g - 1 && cum[rCur + 1] < target) rCur++
            val span = max(1e-6, cum[rCur + 1] - cum[rCur])
            val frac = rCur + (target - cum[rCur]) / span - 0.5
            val syClamped = frac.coerceIn(0.0, (g - 1).toDouble()).toFloat()
            val f0 = floor(syClamped).toInt()
            val fy = syClamped - f0
            val w0 = 1f - fy
            val y0 = yMin + f0
            val y1 = min(yMax, y0 + 1)
            val rowOut = (dy + padOut) * outW + padOut
            val row0 = y0 * sw
            val row1 = y1 * sw
            for (x in 0 until w) {
                val a0 = sData[row0 + xMin + x].toInt() and 0xFF
                val a1 = sData[row1 + xMin + x].toInt() and 0xFF
                outBytes[rowOut + x] = (a0 * w0 + a1 * fy).roundToInt().coerceIn(0, 255).toByte()
            }
        }

        val outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ALPHA_8)
        outBmp.copyPixelsFromBuffer(ByteBuffer.wrap(outBytes))

        val refMidYSrc = sh * 0.5f
        val digitCenterYSrc = (yMin + yMax + 1) * 0.5f
        val res = StretchedGlyph(
            alphaBmp = outBmp,
            w = outW,
            h = outH,
            xCenterOffset = ((xMin + xMax + 1) * 0.5f) - sw * 0.5f,
            yCenterRel = ((digitCenterYSrc - refMidYSrc) / vSub) * sy
        )
        synchronized(glyphCache) { glyphCache[key] = res }
        return res
    }

    private fun getColonDots(fontPx: Float, fontId: String, ss: Float, refMidRel: Float): ColonDots {
        val key = "colon|${"%.1f".format(fontPx)}|${"%.2f".format(ss)}|$fontId"
        synchronized(colonCache) {
            colonCache[key]?.let { return it }
        }
        val srcPx = fontPx * ss
        val sw = ceil(srcPx * 0.8f).toInt() + 16
        val sh = ceil(srcPx * 1.35f).toInt() + 16
        val src = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
        val sc = Canvas(src)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = getTypeface(fontId)
            textSize = srcPx
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
        }
        sc.drawText(":", sw * 0.5f, sh * 0.5f - refMidRel * srcPx, paint)

        val sData = ByteArray(sw * sh)
        ByteBuffer.wrap(sData).also {
            it.rewind()
            src.copyPixelsToBuffer(it)
        }
        src.recycle()

        val mid = sh / 2
        fun crop(yA: Int, yB: Int): Triple<Bitmap, Int, Float>? {
            var x0 = sw
            var x1 = -1
            var y0 = sh
            var y1 = -1
            for (y in yA until yB) {
                val row = y * sw
                for (x in 0 until sw) {
                    if ((sData[row + x].toInt() and 0xFF) > 10) {
                        if (x < x0) x0 = x
                        if (x > x1) x1 = x
                        if (y < y0) y0 = y
                        if (y > y1) y1 = y
                    }
                }
            }
            if (x1 < x0 || y1 < y0) return null
            val cw = x1 - x0 + 1
            val ch = y1 - y0 + 1
            val bytes = ByteArray(cw * ch)
            for (r in 0 until ch) {
                System.arraycopy(sData, (y0 + r) * sw + x0, bytes, r * cw, cw)
            }
            val b = Bitmap.createBitmap(cw, ch, Bitmap.Config.ALPHA_8)
            b.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            return Triple(b, cw, (y0 + y1 + 1) * 0.5f)
        }

        val top = crop(0, mid)
        val bot = crop(mid, sh)
        val natSep = if (top != null && bot != null) (bot.third - top.third) * 0.5f else srcPx * 0.14f
        val res = ColonDots(
            topBmp = top?.first,
            botBmp = bot?.first,
            topW = top?.second ?: 0,
            topH = top?.first?.height ?: 0,
            botW = bot?.second ?: 0,
            botH = bot?.first?.height ?: 0,
            natSep = natSep
        )
        synchronized(colonCache) { colonCache[key] = res }
        return res
    }

    fun buildArtifact(
        lines: List<String>,
        cfg: LockConfig,
        hostWDp: Float,
        availableHDp: Float,
        density: Float,
        version: Long
    ): ClockArtifact? {
        var size = cfg.size
        val maxW = max(80f, hostWDp - 12f)
        var ss = min(3.8f, max(2.6f, density * 1.35f))

        val (capRatio, refMidRel) = measureNumeralMetrics(cfg.font)
        val tf = getTypeface(cfg.font)
        val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = size
        }

        fun measureLineW(ln: String): Float {
            var sum = 0f
            for (ch in ln) sum += probe.measureText(ch.toString())
            return sum + max(0, ln.length - 1) * cfg.tracking
        }

        var maxLineW = 0f
        for (ln in lines) maxLineW = max(maxLineW, measureLineW(ln))

        val pad = 18f
        val rawW = max(40f, maxLineW * cfg.sx)
        val rawH = max(40f, lines.size * size * capRatio * cfg.sy)
        val fit = min(1f, min((maxW - pad * 2f) / rawW, (availableHDp - pad * 2f) / rawH))
        size *= max(0.1f, fit)

        probe.textSize = size
        maxLineW = 0f
        for (ln in lines) maxLineW = max(maxLineW, measureLineW(ln))

        val capH = size * capRatio
        val wDp = ceil(max(40f, maxLineW * cfg.sx) + pad * 2f)
        val hDp = ceil(max(40f, lines.size * capH * cfg.sy) + pad * 2f)

        val rawArea = max(1f, wDp * hDp)
        val maxArea = 2_600_000f
        if (rawArea * ss * ss > maxArea) {
            ss = max(1.8f, floor(sqrt(maxArea / rawArea) * 10f) / 10f)
        }
        val longSide = max(wDp, hDp)
        if (longSide * ss > 2200f) ss = max(1.8f, floor((2200f / longSide) * 10f) / 10f)

        val wPx = max(4, (wDp * ss).roundToInt())
        val hPx = max(4, (hDp * ss).roundToInt())

        val minuteKey = lines.joinToString("|")
        val maskKey = "${"%.2f".format(size)}|$wPx|$hPx|${cfg.font}|${cfg.sx}|${cfg.sy}|${cfg.tracking}|$minuteKey"

        synchronized(artifactCache) {
            artifactCache[maskKey]?.let { return it }
        }

        val alphaBmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
            eraseColor(Color.BLACK)
        }
        val c = Canvas(alphaBmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            typeface = tf
            textSize = size
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
        }

        val digitTargetH = capH * cfg.sy * ss
        val lineStep = digitTargetH * 1.06f
        val startY = ((hDp * ss) - (lines.size - 1) * lineStep) * 0.5f

        var inkMinX = Float.POSITIVE_INFINITY
        var inkMinY = Float.POSITIVE_INFINITY
        var inkMaxX = Float.NEGATIVE_INFINITY
        var inkMaxY = Float.NEGATIVE_INFINITY
        val tb = android.graphics.Rect()
        fun includeInk(l: Float, t: Float, r: Float, b: Float) {
            if (r > l && b > t) {
                if (l < inkMinX) inkMinX = l
                if (t < inkMinY) inkMinY = t
                if (r > inkMaxX) inkMaxX = r
                if (b > inkMaxY) inkMaxY = b
            }
        }

        for ((li, ln) in lines.withIndex()) {
            val advs = FloatArray(ln.length) { i -> probe.measureText(ln[i].toString()) }
            val totalW = advs.sum() + max(0, ln.length - 1) * cfg.tracking
            val lineCenterPx = startY + li * lineStep
            var curX = -totalW * 0.5f

            for (i in ln.indices) {
                val ch = ln[i]
                val adv = advs[i]
                val charCenterPx = (wDp * 0.5f + (curX + adv * 0.5f) * cfg.sx) * ss
                curX += adv + cfg.tracking

                if (ch == ':') {
                    val dots = getColonDots(size, cfg.font, ss, refMidRel)
                    if (dots.topBmp != null && dots.botBmp != null) {
                        val dotScale = min(1.28f, 1f + max(0f, cfg.sy - 1f) * 0.16f) * (0.5f + 0.5f * cfg.sx)
                        val sep = max(dots.natSep, digitTargetH * 0.158f)
                        val tw = dots.topW * dotScale
                        val th = dots.topH * dotScale
                        val bw = dots.botW * dotScale
                        val bh = dots.botH * dotScale
                        val topRect = RectF(charCenterPx - tw * 0.5f, lineCenterPx - sep - th * 0.5f, charCenterPx + tw * 0.5f, lineCenterPx - sep + th * 0.5f)
                        val botRect = RectF(charCenterPx - bw * 0.5f, lineCenterPx + sep - bh * 0.5f, charCenterPx + bw * 0.5f, lineCenterPx + sep + bh * 0.5f)
                        c.drawBitmap(dots.topBmp, null, topRect, paint)
                        c.drawBitmap(dots.botBmp, null, botRect, paint)
                        includeInk(topRect.left, topRect.top, topRect.right, topRect.bottom)
                        includeInk(botRect.left, botRect.top, botRect.right, botRect.bottom)
                    }
                    continue
                }

                if (ch in '0'..'9' && cfg.sy >= 1.03f) {
                    val g = stretchDigitBitmap(ch, size, cfg.font, cfg.sx, cfg.sy, ss, refMidRel)
                    if (g != null) {
                        val gl = charCenterPx - g.w * 0.5f + g.xCenterOffset
                        val gt = lineCenterPx - g.h * 0.5f + g.yCenterRel
                        c.drawBitmap(g.alphaBmp, gl, gt, paint)
                        includeInk(gl + 1f, gt + 1f, gl + g.w - 1f, gt + g.h - 1f)
                        continue
                    }
                }

                probe.getTextBounds(ch.toString(), 0, 1, tb)
                if (!tb.isEmpty) {
                    val gl = charCenterPx + (tb.left - adv * 0.5f) * ss * cfg.sx
                    val gr = charCenterPx + (tb.right - adv * 0.5f) * ss * cfg.sx
                    val gt = lineCenterPx + (-refMidRel * size + tb.top) * ss * cfg.sy
                    val gb = lineCenterPx + (-refMidRel * size + tb.bottom) * ss * cfg.sy
                    includeInk(gl, gt, gr, gb)
                }

                c.save()
                c.translate(charCenterPx, lineCenterPx)
                c.scale(ss * cfg.sx, ss * cfg.sy)
                c.drawText(ch.toString(), 0f, -refMidRel * size, paint)
                c.restore()
            }
        }

        val inkLeftDp = if (inkMaxX > inkMinX) (inkMinX / ss).coerceIn(0f, wDp * 0.45f) else pad
        val inkTopDp = if (inkMaxY > inkMinY) (inkMinY / ss).coerceIn(0f, hDp * 0.45f) else pad
        val inkRightDp = if (inkMaxX > inkMinX) (inkMaxX / ss).coerceIn(inkLeftDp + 20f, wDp) else (wDp - pad)
        val inkBottomDp = if (inkMaxY > inkMinY) (inkMaxY / ss).coerceIn(inkTopDp + 20f, hDp) else (hDp - pad)

        val fieldBmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
        }
        val maxAbsCss = if (NativeGlass.available) {
            NativeGlass.computeClockField(
                alphaBmp, fieldBmp, ss.toDouble(),
                1.5, 40.0, 50.0, 0, version
            ).toFloat()
        } else {
            16f
        }
        if (maxAbsCss < 0f) {
            alphaBmp.recycle()
            fieldBmp.recycle()
            return null
        }

        val alphaShader = if (LiquidGlassShaders.isGpuAgslAvailable()) {
            BitmapShader(alphaBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                if (Build.VERSION.SDK_INT >= 33) filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
        } else null
        val fieldShader = if (LiquidGlassShaders.isGpuAgslAvailable()) {
            BitmapShader(fieldBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                if (Build.VERSION.SDK_INT >= 33) filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
        } else null

        val artifact = ClockArtifact(
            alphaBmp = alphaBmp,
            fieldBmp = fieldBmp,
            alphaShader = alphaShader,
            fieldShader = fieldShader,
            maxAbsCss = maxAbsCss,
            wDp = wDp,
            hDp = hDp,
            wPx = wPx,
            hPx = hPx,
            ss = ss,
            sizeDp = size,
            textWDp = max(40f, maxLineW * cfg.sx),
            textHDp = max(40f, lines.size * capH * cfg.sy),
            lineHDp = capH,
            padDp = pad * 2f,
            inkLeftDp = inkLeftDp,
            inkTopDp = inkTopDp,
            inkRightDp = inkRightDp,
            inkBottomDp = inkBottomDp,
            sx = cfg.sx,
            sy = cfg.sy,
            minuteKey = minuteKey,
            maskKey = maskKey
        )

        synchronized(artifactCache) { artifactCache[maskKey] = artifact }
        return artifact
    }
}
