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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    val maskKey: String,
    val isDraft: Boolean = false
) {
    val byteSize: Long get() = alphaBmp.allocationByteCount.toLong() + fieldBmp.allocationByteCount.toLong()
}

class ClockGlyphRenderer(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        private const val BOOT_CACHE_FILE = "clock_boot_v2.bin"
        private const val BOOT_MAGIC = 0x4F434C4B
        private const val BOOT_VER = 2
        private val bootLock = Any()

        private val typefaceCache = HashMap<String, Typeface>(24)
        private val metricsCache = HashMap<String, NumeralMetrics>(24)
        private val glyphCache = object : LinkedHashMap<String, StretchedGlyph>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StretchedGlyph>?): Boolean {
                return size > 28
            }
        }
        private val colonCache = object : LinkedHashMap<String, ColonDots>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ColonDots>?): Boolean {
                return size > 8
            }
        }
        private val artifactCache = object : LinkedHashMap<String, ClockArtifact>(6, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClockArtifact>?): Boolean {
                return size > 5
            }
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
                        Typeface.createFromAsset(appContext.assets, f.assetPath)
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
            val sz = 144
            val textPx = 72f
            val baseY = 104f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf
                textSize = textPx
                textAlign = Paint.Align.CENTER
                color = Color.WHITE
            }
            val tb = android.graphics.Rect()
            var tbTop = 0
            var tbBot = 0
            for (d in '0'..'9') {
                p.getTextBounds(d.toString(), 0, 1, tb)
                if (tb.top < tbTop) tbTop = tb.top
                if (tb.bottom > tbBot) tbBot = tb.bottom
            }
            val rowStart = max(0, floor(baseY + tbTop - 4f).toInt())
            val rowEnd = min(sz - 1, ceil(baseY + tbBot + 4f).toInt())
            val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            val buf = ByteArray(sz * sz)
            var yMin = sz
            var yMax = -1
            for (d in '0'..'9') {
                bmp.eraseColor(Color.TRANSPARENT)
                c.drawText(d.toString(), sz * 0.5f, baseY, p)
                ByteBuffer.wrap(buf).also {
                    it.rewind()
                    bmp.copyPixelsToBuffer(it)
                }
                for (y in rowStart..rowEnd) {
                    val row = y * sz
                    for (x in 0 until sz) {
                        if ((buf[row + x].toInt() and 0xFF) > 10) {
                            if (y < yMin) yMin = y
                            if (y > yMax) yMax = y
                        }
                    }
                }
            }
            bmp.recycle()
            val capRatio = if (yMax >= yMin) ((yMax - yMin + 1) / textPx).coerceIn(0.50f, 1.25f) else 0.74f
            // Baseline offset relative to 1px so optical numeral center lands at centerY
            val refMidRel = if (yMax >= yMin) (((yMin + yMax + 1) * 0.5f) - baseY) / textPx else 0f
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

        val vSub = if (sy > 2.25f) 3 else 2
        val srcPx = fontPx * ss
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = getTypeface(fontId)
            textSize = srcPx
            textAlign = Paint.Align.LEFT
            color = Color.WHITE
        }
        val tb = android.graphics.Rect()
        paint.getTextBounds(ch.toString(), 0, 1, tb)
        val adv = paint.measureText(ch.toString())
        val tbW = max(8, tb.width())
        val tbH = max(8, tb.height())
        val padBox = 8
        val sw = ceil((tbW + padBox * 2) * sx).toInt().coerceAtLeast(16)
        val sh = ((tbH + padBox * 2) * vSub).coerceAtLeast(16)
        val tbMidX = (tb.left + tb.right) * 0.5f
        val tbMidY = (tb.top + tb.bottom) * 0.5f
        val drawX = (sw * 0.5f / sx) - tbMidX
        val baseY = (sh * 0.5f / vSub) - tbMidY

        val src = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
        val sc = Canvas(src)
        sc.save()
        sc.scale(sx, vSub.toFloat())
        sc.drawText(ch.toString(), drawX, baseY, paint)
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

        val refOriginX = (sw * 0.5f - tbMidX * sx) + (adv * 0.5f * sx)
        val refMidYSrc = (baseY + refMidRel * srcPx) * vSub
        val digitCenterYSrc = (yMin + yMax + 1) * 0.5f
        val res = StretchedGlyph(
            alphaBmp = outBmp,
            w = outW,
            h = outH,
            xCenterOffset = ((xMin + xMax + 1) * 0.5f) - refOriginX,
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = getTypeface(fontId)
            textSize = srcPx
            textAlign = Paint.Align.LEFT
            color = Color.WHITE
        }
        val tb = android.graphics.Rect()
        paint.getTextBounds(":", 0, 1, tb)
        val sw = max(24, tb.width() + 24)
        val sh = max(32, tb.height() + 24)
        val src = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
        val sc = Canvas(src)
        sc.drawText(":", sw * 0.5f - (tb.left + tb.right) * 0.5f, sh * 0.5f - (tb.top + tb.bottom) * 0.5f, paint)

        val sData = ByteArray(sw * sh)
        ByteBuffer.wrap(sData).also {
            it.rewind()
            src.copyPixelsToBuffer(it)
        }
        src.recycle()

        // Locate actual vertical gap between upper & lower colon dots (never slice a dot at sh/2)
        val rowPeak = IntArray(sh)
        var firstInkY = -1
        var lastInkY = -1
        for (y in 0 until sh) {
            val row = y * sw
            var mx = 0
            for (x in 0 until sw) {
                val a = sData[row + x].toInt() and 0xFF
                if (a > mx) mx = a
            }
            rowPeak[y] = mx
            if (mx > 8) {
                if (firstInkY < 0) firstInkY = y
                lastInkY = y
            }
        }

        var splitY = sh / 2
        if (firstInkY >= 0 && lastInkY > firstInkY + 4) {
            var bestGapStart = -1
            var bestGapLen = 0
            var curGapStart = -1
            for (y in firstInkY..lastInkY) {
                if (rowPeak[y] <= 8) {
                    if (curGapStart < 0) curGapStart = y
                } else if (curGapStart >= 0) {
                    val len = y - curGapStart
                    if (len > bestGapLen) {
                        bestGapLen = len
                        bestGapStart = curGapStart
                    }
                    curGapStart = -1
                }
            }
            if (bestGapLen > 0) {
                splitY = bestGapStart + bestGapLen / 2
            } else {
                val lo = firstInkY + ((lastInkY - firstInkY) * 0.25f).roundToInt()
                val hi = lastInkY - ((lastInkY - firstInkY) * 0.25f).roundToInt()
                var minA = Int.MAX_VALUE
                for (y in lo..hi) {
                    if (rowPeak[y] < minA) {
                        minA = rowPeak[y]
                        splitY = y
                    }
                }
            }
        }

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

        val top = crop(0, splitY)
        val bot = crop(splitY, sh)
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

    private sealed class GlyphDrawOp {
        class ColonOp(val topBmp: Bitmap, val topRect: RectF, val botBmp: Bitmap, val botRect: RectF) : GlyphDrawOp()
        class BitmapOp(val bmp: Bitmap, val x: Float, val y: Float) : GlyphDrawOp()
        class TextOp(val ch: String, val cx: Float, val cy: Float, val baseOffset: Float) : GlyphDrawOp()
    }

    private data class PreparedSpec(
        val size: Float,
        val ss: Float,
        val capRatio: Float,
        val refMidRel: Float,
        val tf: Typeface,
        val maxLineW: Float,
        val estWDp: Float,
        val estHDp: Float,
        val pad: Float,
        val minuteKey: String,
        val fullKey: String
    )

    private fun prepareSpec(
        lines: List<String>,
        cfg: LockConfig,
        hostWDp: Float,
        availableHDp: Float,
        density: Float,
        ssOverride: Float? = null
    ): PreparedSpec {
        var size = cfg.size
        val maxW = max(80f, hostWDp - 12f)
        var ss = ssOverride ?: min(3.0f, max(2.1f, density))

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

        val pad = 20f
        val rawW = max(40f, maxLineW * cfg.sx)
        val rawH = max(40f, lines.size * size * capRatio * cfg.sy)
        val fit = min(1f, min((maxW - pad * 2f) / rawW, (availableHDp - pad * 2f) / rawH))
        size *= max(0.1f, fit)

        probe.textSize = size
        maxLineW = 0f
        for (ln in lines) maxLineW = max(maxLineW, measureLineW(ln))

        val capH = size * capRatio
        val estWDp = ceil(max(40f, maxLineW * cfg.sx) + pad * 2f)
        val estHDp = ceil(max(40f, lines.size * capH * cfg.sy) + pad * 2f)

        val rawArea = max(1f, estWDp * estHDp)
        val maxArea = if (ssOverride != null) 600_000f else 1_900_000f
        if (rawArea * ss * ss > maxArea) {
            val minSs = if (ssOverride != null) 1.0f else 1.7f
            ss = max(minSs, floor(sqrt(maxArea / rawArea) * 10f) / 10f)
        }
        val longSide = max(estWDp, estHDp)
        if (longSide * ss > 2048f) {
            val minSs = if (ssOverride != null) 1.0f else 1.7f
            ss = max(minSs, floor((2048f / longSide) * 10f) / 10f)
        }

        val minuteKey = lines.joinToString("|")
        val fullKey = "${"%.2f".format(size)}|${"%.2f".format(ss)}|${cfg.font}|${cfg.sx}|${cfg.sy}|${cfg.tracking}|$minuteKey"
        return PreparedSpec(size, ss, capRatio, refMidRel, tf, maxLineW, estWDp, estHDp, pad, minuteKey, fullKey)
    }

    fun peekFastMemoryArtifact(
        lines: List<String>,
        cfg: LockConfig,
        hostWDp: Float,
        availableHDp: Float,
        density: Float
    ): ClockArtifact? {
        val hasMetrics = synchronized(metricsCache) { metricsCache.containsKey(cfg.font) }
        if (!hasMetrics) return null
        return peekCachedArtifact(lines, cfg, hostWDp, availableHDp, density)
    }

    fun peekCachedArtifact(
        lines: List<String>,
        cfg: LockConfig,
        hostWDp: Float,
        availableHDp: Float,
        density: Float
    ): ClockArtifact? {
        val spec = prepareSpec(lines, cfg, hostWDp, availableHDp, density, ssOverride = null)
        return synchronized(artifactCache) { artifactCache[spec.fullKey] }
    }

    fun tryLoadBootArtifact(expectedRenderKey: String): ClockArtifact? {
        val file = File(appContext.cacheDir, BOOT_CACHE_FILE)
        if (!file.exists()) return null
        return synchronized(bootLock) {
            try {
                DataInputStream(BufferedInputStream(FileInputStream(file), 32768)).use { dis ->
                    if (dis.readInt() != BOOT_MAGIC || dis.readInt() != BOOT_VER) return@synchronized null
                    val storedRenderKey = dis.readUTF()
                    if (storedRenderKey != expectedRenderKey) return@synchronized null
                    val maxAbsCss = dis.readFloat()
                    val wDp = dis.readFloat()
                    val hDp = dis.readFloat()
                    val wPx = dis.readInt()
                    val hPx = dis.readInt()
                    if (wPx < 4 || hPx < 4 || wPx.toLong() * hPx > 2_500_000L) return@synchronized null
                    val ss = dis.readFloat()
                    val sizeDp = dis.readFloat()
                    val textWDp = dis.readFloat()
                    val textHDp = dis.readFloat()
                    val lineHDp = dis.readFloat()
                    val padDp = dis.readFloat()
                    val inkLeftDp = dis.readFloat()
                    val inkTopDp = dis.readFloat()
                    val inkRightDp = dis.readFloat()
                    val inkBottomDp = dis.readFloat()
                    val sx = dis.readFloat()
                    val sy = dis.readFloat()
                    val minuteKey = dis.readUTF()
                    val maskKey = dis.readUTF()

                    val byteCount = wPx * hPx * 4
                    val bytes = ByteArray(byteCount)
                    dis.readFully(bytes)
                    val alphaBmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888).apply {
                        setHasAlpha(false)
                        copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                    }
                    dis.readFully(bytes)
                    val fieldBmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888).apply {
                        setHasAlpha(false)
                        copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
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

                    val art = ClockArtifact(
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
                        sizeDp = sizeDp,
                        textWDp = textWDp,
                        textHDp = textHDp,
                        lineHDp = lineHDp,
                        padDp = padDp,
                        inkLeftDp = inkLeftDp,
                        inkTopDp = inkTopDp,
                        inkRightDp = inkRightDp,
                        inkBottomDp = inkBottomDp,
                        sx = sx,
                        sy = sy,
                        minuteKey = minuteKey,
                        maskKey = maskKey,
                        isDraft = false
                    )
                    synchronized(artifactCache) { artifactCache[maskKey] = art }
                    art
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun saveBootArtifact(renderKey: String, art: ClockArtifact) {
        if (art.isDraft || art.alphaBmp.isRecycled || art.fieldBmp.isRecycled) return
        val dir = appContext.cacheDir
        val tmp = File(dir, "$BOOT_CACHE_FILE.tmp")
        val dst = File(dir, BOOT_CACHE_FILE)
        synchronized(bootLock) {
            try {
                val byteCount = art.wPx * art.hPx * 4
                if (art.alphaBmp.byteCount != byteCount || art.fieldBmp.byteCount != byteCount) return
                val buf = ByteArray(byteCount)
                DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 32768)).use { dos ->
                    dos.writeInt(BOOT_MAGIC)
                    dos.writeInt(BOOT_VER)
                    dos.writeUTF(renderKey)
                    dos.writeFloat(art.maxAbsCss)
                    dos.writeFloat(art.wDp)
                    dos.writeFloat(art.hDp)
                    dos.writeInt(art.wPx)
                    dos.writeInt(art.hPx)
                    dos.writeFloat(art.ss)
                    dos.writeFloat(art.sizeDp)
                    dos.writeFloat(art.textWDp)
                    dos.writeFloat(art.textHDp)
                    dos.writeFloat(art.lineHDp)
                    dos.writeFloat(art.padDp)
                    dos.writeFloat(art.inkLeftDp)
                    dos.writeFloat(art.inkTopDp)
                    dos.writeFloat(art.inkRightDp)
                    dos.writeFloat(art.inkBottomDp)
                    dos.writeFloat(art.sx)
                    dos.writeFloat(art.sy)
                    dos.writeUTF(art.minuteKey)
                    dos.writeUTF(art.maskKey)

                    ByteBuffer.wrap(buf).also {
                        it.rewind()
                        art.alphaBmp.copyPixelsToBuffer(it)
                    }
                    dos.write(buf)
                    ByteBuffer.wrap(buf).also {
                        it.rewind()
                        art.fieldBmp.copyPixelsToBuffer(it)
                    }
                    dos.write(buf)
                }
                if (dst.exists()) dst.delete()
                tmp.renameTo(dst)
            } catch (_: Throwable) {
                try { if (tmp.exists()) tmp.delete() } catch (_: Throwable) {}
            }
        }
    }

    fun buildArtifact(
        lines: List<String>,
        cfg: LockConfig,
        hostWDp: Float,
        availableHDp: Float,
        density: Float,
        version: Long,
        ssOverride: Float? = null
    ): ClockArtifact? {
        // Return full-resolution cached artifact immediately even if a draft was requested
        val fullSpec = prepareSpec(lines, cfg, hostWDp, availableHDp, density, ssOverride = null)
        synchronized(artifactCache) {
            artifactCache[fullSpec.fullKey]?.let { return it }
        }

        val spec = if (ssOverride != null) {
            prepareSpec(lines, cfg, hostWDp, availableHDp, density, ssOverride = ssOverride)
        } else {
            fullSpec
        }
        val size = spec.size
        val ss = spec.ss
        val capRatio = spec.capRatio
        val refMidRel = spec.refMidRel
        val tf = spec.tf
        val maxLineW = spec.maxLineW
        val estWDp = spec.estWDp
        val estHDp = spec.estHDp
        val pad = spec.pad
        val minuteKey = spec.minuteKey
        val estKey = spec.fullKey
        val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = size
        }
        val capH = size * capRatio

        // Phase 1: collect all draw ops & measure true ink bounding box so NO ascender/edge can ever clip
        val digitTargetH = capH * cfg.sy * ss
        val lineStep = digitTargetH * 1.06f
        val ops = ArrayList<GlyphDrawOp>(8)
        var rawMinX = Float.POSITIVE_INFINITY
        var rawMinY = Float.POSITIVE_INFINITY
        var rawMaxX = Float.NEGATIVE_INFINITY
        var rawMaxY = Float.NEGATIVE_INFINITY
        val tb = android.graphics.Rect()
        fun expandRaw(l: Float, t: Float, r: Float, b: Float) {
            if (r > l && b > t) {
                if (l < rawMinX) rawMinX = l
                if (t < rawMinY) rawMinY = t
                if (r > rawMaxX) rawMaxX = r
                if (b > rawMaxY) rawMaxY = b
            }
        }

        for ((li, ln) in lines.withIndex()) {
            val advs = FloatArray(ln.length) { i -> probe.measureText(ln[i].toString()) }
            val totalW = advs.sum() + max(0, ln.length - 1) * cfg.tracking
            val lineCenterY = (li - (lines.size - 1) * 0.5f) * lineStep
            var curX = -totalW * 0.5f

            for (i in ln.indices) {
                val ch = ln[i]
                val adv = advs[i]
                val charCenterX = (curX + adv * 0.5f) * cfg.sx * ss
                curX += adv + cfg.tracking

                if (ch == ':') {
                    val dots = getColonDots(size, cfg.font, ss, refMidRel)
                    if (dots.topBmp != null && dots.botBmp != null) {
                        val dotScale = (min(1.28f, 1f + max(0f, cfg.sy - 1f) * 0.14f) * (0.58f + 0.42f * cfg.sx)).coerceIn(0.76f, 1.28f)
                        val sep = max(max(dots.natSep, (dots.topH + dots.botH) * 0.52f * dotScale), digitTargetH * 0.158f)
                        val tw = dots.topW * dotScale
                        val th = dots.topH * dotScale
                        val bw = dots.botW * dotScale
                        val bh = dots.botH * dotScale
                        val topRect = RectF(charCenterX - tw * 0.5f, lineCenterY - sep - th * 0.5f, charCenterX + tw * 0.5f, lineCenterY - sep + th * 0.5f)
                        val botRect = RectF(charCenterX - bw * 0.5f, lineCenterY + sep - bh * 0.5f, charCenterX + bw * 0.5f, lineCenterY + sep + bh * 0.5f)
                        ops.add(GlyphDrawOp.ColonOp(dots.topBmp, topRect, dots.botBmp, botRect))
                        expandRaw(topRect.left, topRect.top, topRect.right, topRect.bottom)
                        expandRaw(botRect.left, botRect.top, botRect.right, botRect.bottom)
                        continue
                    }
                }

                if (ch in '0'..'9' && cfg.sy >= 1.03f) {
                    val g = stretchDigitBitmap(ch, size, cfg.font, cfg.sx, cfg.sy, ss, refMidRel)
                    if (g != null) {
                        val gl = charCenterX - g.w * 0.5f + g.xCenterOffset
                        val gt = lineCenterY - g.h * 0.5f + g.yCenterRel
                        ops.add(GlyphDrawOp.BitmapOp(g.alphaBmp, gl, gt))
                        expandRaw(gl, gt, gl + g.w, gt + g.h)
                        continue
                    }
                }

                probe.getTextBounds(ch.toString(), 0, 1, tb)
                val baseOffset = -refMidRel * size
                if (!tb.isEmpty) {
                    val gl = charCenterX + (tb.left - adv * 0.5f - 2f) * ss * cfg.sx
                    val gr = charCenterX + (tb.right - adv * 0.5f + 2f) * ss * cfg.sx
                    val gt = lineCenterY + (baseOffset + tb.top - 2f) * ss * cfg.sy
                    val gb = lineCenterY + (baseOffset + tb.bottom + 2f) * ss * cfg.sy
                    expandRaw(gl, gt, gr, gb)
                }
                ops.add(GlyphDrawOp.TextOp(ch.toString(), charCenterX, lineCenterY, baseOffset))
            }
        }

        if (!rawMaxX.isFinite() || rawMaxX <= rawMinX) {
            rawMinX = -estWDp * 0.4f * ss
            rawMaxX = estWDp * 0.4f * ss
            rawMinY = -estHDp * 0.4f * ss
            rawMaxY = estHDp * 0.4f * ss
        }

        val padPx = pad * ss
        val wPx = max(4, ceil(rawMaxX - rawMinX + padPx * 2f).toInt())
        val hPx = max(4, ceil(rawMaxY - rawMinY + padPx * 2f).toInt())
        val wDp = wPx / ss
        val hDp = hPx / ss
        val shiftX = padPx - rawMinX
        val shiftY = padPx - rawMinY

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

        for (op in ops) {
            when (op) {
                is GlyphDrawOp.ColonOp -> {
                    val tr = RectF(op.topRect).apply { offset(shiftX, shiftY) }
                    val br = RectF(op.botRect).apply { offset(shiftX, shiftY) }
                    c.drawBitmap(op.topBmp, null, tr, paint)
                    c.drawBitmap(op.botBmp, null, br, paint)
                }
                is GlyphDrawOp.BitmapOp -> {
                    c.drawBitmap(op.bmp, op.x + shiftX, op.y + shiftY, paint)
                }
                is GlyphDrawOp.TextOp -> {
                    c.save()
                    c.translate(op.cx + shiftX, op.cy + shiftY)
                    c.scale(ss * cfg.sx, ss * cfg.sy)
                    c.drawText(op.ch, 0f, op.baseOffset, paint)
                    c.restore()
                }
            }
        }

        val inkLeftDp = ((rawMinX + shiftX) / ss).coerceIn(0f, wDp * 0.45f)
        val inkTopDp = ((rawMinY + shiftY) / ss).coerceIn(0f, hDp * 0.45f)
        val inkRightDp = ((rawMaxX + shiftX) / ss).coerceIn(inkLeftDp + 20f, wDp)
        val inkBottomDp = ((rawMaxY + shiftY) / ss).coerceIn(inkTopDp + 20f, hDp)

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
            maskKey = estKey,
            isDraft = ssOverride != null
        )

        if (ssOverride == null) {
            synchronized(artifactCache) { artifactCache[estKey] = artifact }
        }
        return artifact
    }
}
