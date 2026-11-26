package com.oclock.liquidglass

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class ClockGlassView(
    context: Context,
    private val renderer: ClockGlyphRenderer,
    private val backdrop: LiquidGlassShaders.BackdropState,
    private val perf: PerfMonitor
) : View(context) {

    var activeArtifact: ClockArtifact? = null
        private set

    var colors: AdaptiveColors? = null
    var config: LockConfig = LockConfig()
    var previewScaleX: Float = 1f
    var previewScaleY: Float = 1f
    var pullOffsetYPx: Float = 0f

    private var clockShader: RuntimeShader? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()
    private var cpuFallbackBmp: Bitmap? = null
    private var cpuFallbackKey: String = ""

    private var lastCommittedSize = 172f
    private var lastCommittedSy = 1.22f
    private var lastMinuteKey = ""
    private val applyVersion = AtomicLong(0L)
    private var latestScheduledSeq = 0L
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var hostWDp = 390f
    private var availableHDp = 400f
    private var onArtifactCommitted: ((ClockArtifact) -> Unit)? = null

    init {
        if (Build.VERSION.SDK_INT >= 33) {
            clockShader = LiquidGlassShaders.createClockShader()
        }
    }

    fun setOnArtifactCommitted(cb: ((ClockArtifact) -> Unit)?) {
        onArtifactCommitted = cb
    }

    fun committedSize(): Float = lastCommittedSize
    fun committedSy(): Float = lastCommittedSy

    fun updatePreviewTransform(sx: Float, sy: Float) {
        previewScaleX = sx
        previewScaleY = sy
        pivotX = width * 0.5f
        pivotY = 0f
        scaleX = sx
        scaleY = sy
        invalidate()
    }

    fun clearPreviewTransform() {
        previewScaleX = 1f
        previewScaleY = 1f
        scaleX = 1f
        scaleY = 1f
        invalidate()
    }

    fun scheduleRender(
        cfg: LockConfig,
        hostW: Float,
        availH: Float,
        force: Boolean = false
    ) {
        config = cfg
        hostWDp = hostW
        availableHDp = availH
        val lines = currentTimeLines(cfg)
        val minuteKey = lines.joinToString("|")
        if (!force && minuteKey == lastMinuteKey && activeArtifact != null) return

        val seq = ++latestScheduledSeq
        val ver = applyVersion.incrementAndGet()
        NativeGlass.setLatestVersion(ver)
        val snapCfg = cfg.copy()
        val dens = resources.displayMetrics.density

        worker.execute {
            if (seq != latestScheduledSeq) return@execute
            val t0 = SystemClock.elapsedRealtimeNanos()
            val art = renderer.buildArtifact(lines, snapCfg, hostW, availH, dens, ver) ?: return@execute
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            perf.recordWork("clockFieldCpp", elapsedMs)
            perf.setCacheBytes(renderer.getCacheBytes())

            mainHandler.post {
                if (seq != latestScheduledSeq) return@post
                activeArtifact = art
                lastMinuteKey = art.minuteKey
                lastCommittedSize = snapCfg.size
                lastCommittedSy = snapCfg.sy
                previewScaleX = 1f
                previewScaleY = 1f
                scaleX = 1f
                scaleY = 1f
                cpuFallbackKey = ""
                requestLayout()
                invalidate()
                onArtifactCommitted?.invoke(art)
            }
        }
    }

    fun currentTimeLines(cfg: LockConfig): List<String> {
        val cal = Calendar.getInstance()
        val m = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        var h = cal.get(Calendar.HOUR_OF_DAY)
        if (!cfg.hour24) {
            h %= 12
            if (h == 0) h = 12
        }
        val hs = if (cfg.leadingZero) h.toString().padStart(2, '0') else h.toString()
        return listOf("$hs:$m")
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dens = resources.displayMetrics.density
        val art = activeArtifact
        val w = ((art?.wDp ?: 260f) * dens).roundToInt().coerceAtLeast(8)
        val h = ((art?.hDp ?: 210f) * dens).roundToInt().coerceAtLeast(8)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val art = activeArtifact ?: return
        val cols = colors ?: return
        val wallShader = backdrop.wallShader ?: return
        val dstW = width.toFloat().coerceAtLeast(1f)
        val dstH = height.toFloat().coerceAtLeast(1f)
        val dens = resources.displayMetrics.density

        // Compute top-left in parent lock-screen coordinates
        val originX = (parent as? View)?.x?.plus(x) ?: x
        val originY = ((parent as? View)?.y?.plus(y) ?: y) + pullOffsetYPx

        if (Build.VERSION.SDK_INT >= 33 && clockShader != null && art.fieldShader != null && art.alphaShader != null) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val sh = clockShader!!
            sh.setInputShader("uBackdrop", wallShader)
            sh.setInputShader("uField", art.fieldShader)
            sh.setInputShader("uMask", art.alphaShader)
            sh.setFloatUniform("uClockOrigin", originX, originY)
            sh.setFloatUniform("uDstSize", dstW, dstH)
            sh.setFloatUniform("uClockScale", previewScaleX, previewScaleY)
            sh.setFloatUniform("uFieldSize", art.wPx.toFloat(), art.hPx.toFloat())
            sh.setFloatUniform("uMaxAbsPx", art.maxAbsCss * dens)
            sh.setFloatUniform("uSS", art.ss)
            setColorUniform(sh, "uTintTop", cols.clockTintTop)
            setColorUniform(sh, "uTintBot", cols.clockTintBot)
            val shTop = Color.argb(
                (cols.clockShadowOp * 255f).roundToInt().coerceIn(0, 255),
                Color.red(cols.clockShadowTopColor),
                Color.green(cols.clockShadowTopColor),
                Color.blue(cols.clockShadowTopColor)
            )
            setColorUniform(sh, "uShadowTop", shTop)
            val innerCol = AppConstants.parseHexColor(config.innerColor, Color.WHITE)
            setColorUniform(sh, "uInnerColor", innerCol)
            sh.setFloatUniform("uInnerThick", config.innerThickness)
            sh.setFloatUniform("uInnerOp", (config.innerOpacity / 100f).coerceIn(0f, 1f))
            val rad = Math.toRadians(config.innerAngle.toDouble())
            sh.setFloatUniform("uInnerDir", cos(rad).toFloat(), sin(rad).toFloat())

            paint.shader = sh
            canvas.drawRect(0f, 0f, dstW, dstH, paint)
            paint.shader = null
            perf.recordWork("clockShaderDraw", (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0)
        } else {
            val wallBmp = backdrop.wallBitmap ?: return
            val key = "${art.maskKey}|${originX.roundToInt()}|${originY.roundToInt()}|${cols.clockTintTop}|${cols.clockTintBot}|${config.innerOpacity}|${config.innerThickness}|${config.innerAngle}|${config.innerColor}"
            var out = cpuFallbackBmp?.takeIf { it.width == art.wPx && it.height == art.hPx }
            if (out == null || cpuFallbackKey != key) {
                if (out == null) {
                    out = Bitmap.createBitmap(art.wPx, art.hPx, Bitmap.Config.ARGB_8888)
                    cpuFallbackBmp = out
                }
                val innerCol = AppConstants.parseHexColor(config.innerColor, Color.WHITE)
                NativeGlass.renderClockCpuFallback(
                    art.alphaBmp, art.fieldBmp, wallBmp, out,
                    originX, originY, dstW, dstH,
                    backdrop.wallLeft, backdrop.wallTop, backdrop.wallScaleX, backdrop.wallScaleY,
                    art.maxAbsCss * dens, art.ss,
                    cols.clockTintTop, cols.clockTintBot, cols.clockShadowOp, cols.clockShadowTopColor,
                    innerCol, config.innerThickness, (config.innerOpacity / 100f).coerceIn(0f, 1f), config.innerAngle
                )
                cpuFallbackKey = key
            }
            dstRect.set(0f, 0f, dstW, dstH)
            canvas.drawBitmap(out, null, dstRect, paint)
        }
    }

    @androidx.annotation.RequiresApi(33)
    private fun setColorUniform(shader: RuntimeShader, name: String, color: Int) {
        shader.setFloatUniform(
            name,
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f,
            Color.alpha(color) / 255f
        )
    }
}

class ClockResizeOverlayView(
    context: Context,
    private val clockView: ClockGlassView,
    private val getLimits: () -> Pair<Float, Float>,
    private val onConfigChanged: (syncUi: Boolean) -> Unit,
    private val onCommit: () -> Unit,
    private val onReset: () -> Unit,
    private val onUndo: () -> Unit,
    private val onHaptic: (String) -> Unit
) : FrameLayout(context) {

    private val dens = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val labelPill: TextView
    private val actionsRow: LinearLayout
    private val cornerDots = ArrayList<View>(4)
    private var pulseAnim: ValueAnimator? = null
    private var autoCloseRunnable = Runnable { close() }
    var isOpen = false
        private set

    private var dragDir: String? = null
    private var dragX0 = 0f
    private var dragY0 = 0f
    private var startSize = 172f
    private var startFitSize = 172f
    private var startSy = 1.22f
    private var startW = 200f
    private var startH = 200f

    init {
        visibility = GONE
        layoutDirection = LAYOUT_DIRECTION_LTR
        clipChildren = false
        clipToPadding = false
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((1f * dens).roundToInt(), Color.argb(90, 255, 255, 255))
            cornerRadius = 22f * dens
        }

        // 4 visible corner handles + invisible generous touch targets
        val corners = listOf("nw" to (Gravity.TOP or Gravity.LEFT),
            "ne" to (Gravity.TOP or Gravity.RIGHT),
            "sw" to (Gravity.BOTTOM or Gravity.LEFT),
            "se" to (Gravity.BOTTOM or Gravity.RIGHT))
        for ((dir, grav) in corners) {
            val hit = FrameLayout(context).apply {
                val sz = (38f * dens).roundToInt()
                layoutParams = LayoutParams(sz, sz, grav).apply {
                    val off = (-10f * dens).roundToInt()
                    when (dir) {
                        "nw" -> { leftMargin = off; topMargin = off }
                        "ne" -> { rightMargin = off; topMargin = off }
                        "sw" -> { leftMargin = off; bottomMargin = off }
                        "se" -> { rightMargin = off; bottomMargin = off }
                    }
                }
                isClickable = true
            }
            val dot = FrameLayout(context).apply {
                val ds = (20f * dens).roundToInt()
                layoutParams = LayoutParams(ds, ds, Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke((1f * dens).roundToInt(), Color.argb(30, 0, 0, 0))
                }
                elevation = 4f * dens
                val pupil = View(context).apply {
                    val ps = (8f * dens).roundToInt()
                    layoutParams = LayoutParams(ps, ps, Gravity.CENTER)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(140, 20, 22, 28))
                    }
                }
                addView(pupil)
            }
            hit.addView(dot)
            cornerDots.add(dot)
            bindHandle(hit, dir)
            addView(hit)
        }

        // 4 wide invisible edge drag handles (n, s, w, e)
        val edges = listOf(
            Triple("n", LayoutParams(LayoutParams.MATCH_PARENT, (22f * dens).roundToInt(), Gravity.TOP), (24f * dens).roundToInt()),
            Triple("s", LayoutParams(LayoutParams.MATCH_PARENT, (22f * dens).roundToInt(), Gravity.BOTTOM), (24f * dens).roundToInt()),
            Triple("w", LayoutParams((22f * dens).roundToInt(), LayoutParams.MATCH_PARENT, Gravity.LEFT), (24f * dens).roundToInt()),
            Triple("e", LayoutParams((22f * dens).roundToInt(), LayoutParams.MATCH_PARENT, Gravity.RIGHT), (24f * dens).roundToInt())
        )
        for ((dir, lp, inset) in edges) {
            if (dir == "n" || dir == "s") {
                lp.leftMargin = inset; lp.rightMargin = inset
            } else {
                lp.topMargin = inset; lp.bottomMargin = inset
            }
            val edge = View(context).apply {
                layoutParams = lp
                isClickable = true
            }
            bindHandle(edge, dir)
            addView(edge)
        }

        labelPill = TextView(context).apply {
            text = "اسحب المقبض · أو اضغط تصغير"
            setTextColor(Color.argb(230, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding((12f * dens).roundToInt(), (6f * dens).roundToInt(), (12f * dens).roundToInt(), (6f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(184, 24, 24, 28))
                cornerRadius = 12f * dens
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (-40f * dens).roundToInt()
            }
        }
        addView(labelPill)

        actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (-82f * dens).roundToInt()
            }
        }
        val resetBtn = makeActionPill("تصغير") {
            onReset()
            scheduleAutoClose()
            onHaptic("tick")
        }
        val undoBtn = makeActionPill("↺") {
            onUndo()
            scheduleAutoClose()
            onHaptic("tick")
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = (8f * dens).roundToInt()
        }
        actionsRow.addView(resetBtn)
        actionsRow.addView(undoBtn)
        addView(actionsRow)
    }

    private fun makeActionPill(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding((14f * dens).roundToInt(), (6f * dens).roundToInt(), (14f * dens).roundToInt(), (6f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(168, 0, 0, 0))
                cornerRadius = 999f
                setStroke((0.8f * dens).roundToInt(), Color.argb(72, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindHandle(view: View, dir: String) {
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(autoCloseRunnable)
                    val art = clockView.activeArtifact
                    dragDir = dir
                    dragX0 = ev.rawX
                    dragY0 = ev.rawY
                    startSize = clockView.config.size
                    startFitSize = art?.sizeDp ?: clockView.committedSize()
                    startSy = clockView.config.sy
                    startW = art?.textWDp ?: (clockView.width / dens)
                    startH = art?.textHDp ?: (clockView.height / dens)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val d = dragDir ?: return@setOnTouchListener false
                    applyDelta(d, (ev.rawX - dragX0) / dens, (ev.rawY - dragY0) / dens)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragDir != null) {
                        dragDir = null
                        labelPill.text = "اسحب المقبض · أو اضغط تصغير"
                        onCommit()
                        scheduleAutoClose()
                        onHaptic("tick")
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun applyDelta(dir: String, dx: Float, dy: Float) {
        val cfg = clockView.config
        val (maxW, maxH) = getLimits()
        val horiz = dir.contains('e') || dir.contains('w')
        val vert = dir.contains('n') || dir.contains('s')
        val fitSz = max(40f, startFitSize)
        val fullWidthSize = (fitSz * (maxW / max(40f, startW))).roundToInt().toFloat().coerceIn(56f, 420f)

        if (horiz) {
            val dxOut = dx * if (dir.contains('w')) -2f else 2f
            val nextW = (startW + dxOut).coerceIn(80f, maxW)
            cfg.size = (fitSz * (nextW / max(40f, startW))).roundToInt().toFloat().coerceIn(56f, fullWidthSize)
            cfg.sx = 1.0f
        }
        val baseHAtSize = (startH / max(0.5f, startSy)) * (cfg.size / fitSz)
        val maxSyAtSize = (maxH / max(40f, baseHAtSize)).coerceIn(1.0f, 3.10f)

        if (vert) {
            val dyOut = dy * if (dir.contains('n')) -1f else 1f
            if (dyOut >= 0f) {
                val room = max(60f, maxH - startH)
                cfg.sy = (startSy + (dyOut / room) * max(0.35f, maxSyAtSize - startSy)).coerceIn(1.0f, maxSyAtSize)
            } else {
                val shrink = max(70f, startH * 0.65f)
                cfg.sy = (startSy + (dyOut / shrink) * max(0.25f, startSy - 1.0f)).coerceIn(1.0f, maxSyAtSize)
            }
        } else if (cfg.sy > maxSyAtSize) {
            cfg.sy = maxSyAtSize
        }

        val wPct = ((cfg.size / max(56f, fullWidthSize)) * 100f).roundToInt().coerceIn(15, 100)
        val hPct = (((cfg.sy - 1f) / max(0.1f, maxSyAtSize - 1f)) * 100f).roundToInt().coerceIn(0, 100)
        labelPill.text = "الحجم $wPct% · الطول $hPct%"

        val baseSize = max(40f, clockView.activeArtifact?.sizeDp ?: clockView.committedSize())
        val baseSy = max(0.5f, clockView.committedSy())
        val kSize = cfg.size / baseSize
        clockView.updatePreviewTransform(kSize * (cfg.sx / 1f), kSize * (cfg.sy / baseSy))
        layoutAroundClock()
        onConfigChanged(true)
    }

    fun layoutAroundClock() {
        val parentV = clockView.parent as? View ?: return
        val cw = clockView.width * clockView.scaleX
        val ch = clockView.height * clockView.scaleY
        val cx = parentV.x + clockView.x + clockView.width * 0.5f - cw * 0.5f
        val cy = parentV.y + clockView.y
        val lp = layoutParams as? LayoutParams ?: return
        lp.width = cw.roundToInt().coerceAtLeast((80f * dens).roundToInt())
        lp.height = ch.roundToInt().coerceAtLeast((60f * dens).roundToInt())
        lp.leftMargin = cx.roundToInt()
        lp.topMargin = cy.roundToInt()
        layoutParams = lp
    }

    fun open() {
        isOpen = true
        layoutAroundClock()
        visibility = VISIBLE
        labelPill.text = "اسحب المقبض · أو اضغط تصغير"
        pulseAnim?.cancel()
        pulseAnim = ValueAnimator.ofFloat(1f, 1.18f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val s = anim.animatedValue as Float
                cornerDots.forEach { d -> d.scaleX = s; d.scaleY = s }
            }
            start()
        }
        onHaptic("heavy")
        scheduleAutoClose()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        visibility = GONE
        pulseAnim?.cancel()
        pulseAnim = null
        handler.removeCallbacks(autoCloseRunnable)
        if (dragDir != null) {
            dragDir = null
            clockView.clearPreviewTransform()
            onCommit()
        }
    }

    fun scheduleAutoClose() {
        handler.removeCallbacks(autoCloseRunnable)
        handler.postDelayed(autoCloseRunnable, 9000L)
    }
}
