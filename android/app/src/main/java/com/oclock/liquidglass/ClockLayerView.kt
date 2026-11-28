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
import kotlin.math.abs
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
    private var lastCommittedSx = 1.0f
    private var lastCommittedSy = 1.22f
    private var lastMinuteKey = ""
    private var committedBootKey = ""
    private var committedAvailHDp = 0f
    private var inFlightBootKey = ""
    private var inFlightAvailHDp = 0f
    private val applyVersion = AtomicLong(0L)
    @Volatile
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
    fun committedSx(): Float = lastCommittedSx
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

    private fun commitArtifactOnUi(art: ClockArtifact, snapCfg: LockConfig, bootKey: String, availH: Float) {
        activeArtifact = art
        lastMinuteKey = art.minuteKey
        lastCommittedSize = snapCfg.size
        lastCommittedSx = snapCfg.sx
        lastCommittedSy = snapCfg.sy
        committedBootKey = bootKey
        committedAvailHDp = availH
        if (!art.isDraft && inFlightBootKey == bootKey) {
            inFlightBootKey = ""
        }
        previewScaleX = 1f
        previewScaleY = 1f
        scaleX = 1f
        scaleY = 1f
        cpuFallbackKey = ""
        requestLayout()
        invalidate()
        onArtifactCommitted?.invoke(art)
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
        val bootKey = "${cfg.font}|${"%.2f".format(cfg.size)}|${"%.3f".format(cfg.sx)}|${"%.3f".format(cfg.sy)}|${"%.1f".format(cfg.tracking)}|${hostW.roundToInt()}|${cfg.music}|${cfg.notifications}|$minuteKey"

        val curArt = activeArtifact
        if (curArt != null && !curArt.isDraft && committedBootKey == bootKey &&
            (!force || abs(committedAvailHDp - availH) < 18f)
        ) {
            if (previewScaleX != 1f || previewScaleY != 1f) clearPreviewTransform()
            return
        }
        if (inFlightBootKey == bootKey && (!force || abs(inFlightAvailHDp - availH) < 18f)) {
            return
        }

        val dens = resources.displayMetrics.density
        val snapCfg = cfg.copy()

        // Zero-latency synchronous RAM cache hit (e.g. reopening after Back/finish)
        val memHit = renderer.peekFastMemoryArtifact(lines, snapCfg, hostW, availH, dens)
        if (memHit != null) {
            inFlightBootKey = ""
            ++latestScheduledSeq
            commitArtifactOnUi(memHit, snapCfg, bootKey, availH)
            return
        }

        val needFastBootstrap = curArt == null || minuteKey != lastMinuteKey
        inFlightBootKey = bootKey
        inFlightAvailHDp = availH
        val seq = ++latestScheduledSeq
        val ver = applyVersion.incrementAndGet()
        NativeGlass.setLatestVersion(ver)

        worker.execute {
            if (seq != latestScheduledSeq) return@execute

            val ramArt = renderer.peekCachedArtifact(lines, snapCfg, hostW, availH, dens)
            if (ramArt != null) {
                perf.setCacheBytes(renderer.getCacheBytes())
                mainHandler.post {
                    if (seq != latestScheduledSeq) return@post
                    commitArtifactOnUi(ramArt, snapCfg, bootKey, availH)
                }
                return@execute
            }

            if (needFastBootstrap) {
                val bootArt = renderer.tryLoadBootArtifact(bootKey)
                if (bootArt != null) {
                    perf.setCacheBytes(renderer.getCacheBytes())
                    mainHandler.post {
                        if (seq != latestScheduledSeq) return@post
                        commitArtifactOnUi(bootArt, snapCfg, bootKey, availH)
                    }
                    return@execute
                }

                // Stage 1: ultra-fast (~15-25ms) Liquid Glass draft so clock appears immediately
                val draftArt = renderer.buildArtifact(lines, snapCfg, hostW, availH, dens, ver, ssOverride = 1.25f)
                if (draftArt != null && seq == latestScheduledSeq) {
                    mainHandler.post {
                        if (seq != latestScheduledSeq) return@post
                        commitArtifactOnUi(draftArt, snapCfg, bootKey, availH)
                    }
                }
                if (seq != latestScheduledSeq) return@execute
            }

            // Stage 2: full native-resolution Liquid Glass
            val t0 = SystemClock.elapsedRealtimeNanos()
            val art = renderer.buildArtifact(lines, snapCfg, hostW, availH, dens, ver, ssOverride = null) ?: return@execute
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            perf.recordWork("clockFieldCpp", elapsedMs)
            perf.setCacheBytes(renderer.getCacheBytes())

            mainHandler.post {
                if (seq != latestScheduledSeq) return@post
                commitArtifactOnUi(art, snapCfg, bootKey, availH)
            }
            if (seq == latestScheduledSeq) {
                renderer.saveBootArtifact(bootKey, art)
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

        // Compute top-left in parent lock-screen coordinates (clockContainer.y already includes pullOffset)
        val originX = (parent as? View)?.x?.plus(x) ?: x
        val originY = (parent as? View)?.y?.plus(y) ?: y

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
            sh.setFloatUniform("uInnerFade", (config.innerFade / 100f).coerceIn(0f, 1f))
            val rad = Math.toRadians(config.innerAngle.toDouble())
            sh.setFloatUniform("uInnerDir", cos(rad).toFloat(), sin(rad).toFloat())

            paint.shader = sh
            canvas.drawRect(0f, 0f, dstW, dstH, paint)
            paint.shader = null
            perf.recordWork("clockShaderDraw", (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0)
        } else {
            val wallBmp = backdrop.wallBitmap ?: return
            val key = "${art.maskKey}|${originX.roundToInt()}|${originY.roundToInt()}|${cols.clockTintTop}|${cols.clockTintBot}|${config.innerOpacity}|${config.innerThickness}|${config.innerAngle}|${config.innerFade}|${config.innerColor}"
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
                    innerCol, config.innerThickness, (config.innerOpacity / 100f).coerceIn(0f, 1f), config.innerAngle, (config.innerFade / 100f).coerceIn(0f, 1f)
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
    private val onClockTap: () -> Unit,
    private val onHaptic: (String) -> Unit
) : FrameLayout(context) {

    private val dens = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val hitPadX = (22f * dens).roundToInt()
    private val hitPadTop = (22f * dens).roundToInt()
    private val hitPadBot = (94f * dens).roundToInt()
    private val boxFrame: FrameLayout
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
        isClickable = false
        isFocusable = false

        boxFrame = FrameLayout(context).apply {
            layoutDirection = LAYOUT_DIRECTION_LTR
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = false
            background = GradientDrawable().apply {
                setColor(Color.argb(14, 255, 255, 255))
                setStroke((1.2f * dens).roundToInt(), Color.argb(132, 255, 255, 255))
                cornerRadius = 22f * dens
            }
            layoutParams = LayoutParams((200f * dens).roundToInt(), (160f * dens).roundToInt(), Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = hitPadX
                topMargin = hitPadTop
            }
        }
        val boxSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        var boxTapDownX = 0f
        var boxTapDownY = 0f
        var boxTapPtr = MotionEvent.INVALID_POINTER_ID
        boxFrame.setOnTouchListener { _, ev ->
            if (!isOpen) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(autoCloseRunnable)
                    boxTapPtr = ev.getPointerId(0)
                    boxTapDownX = ev.rawX
                    boxTapDownY = ev.rawY
                    boxFrame.animate().cancel()
                    boxFrame.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90L).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val idx = ev.findPointerIndex(boxTapPtr)
                    val isTap = idx >= 0 && hypot(ev.rawX - boxTapDownX, ev.rawY - boxTapDownY) < boxSlop * 1.4f
                    boxTapPtr = MotionEvent.INVALID_POINTER_ID
                    boxFrame.animate().cancel()
                    boxFrame.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
                    if (isTap) {
                        onHaptic("pop")
                        onClockTap()
                    } else {
                        scheduleAutoClose()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    boxTapPtr = MotionEvent.INVALID_POINTER_ID
                    boxFrame.animate().cancel()
                    boxFrame.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                    scheduleAutoClose()
                    true
                }
                else -> true
            }
        }
        addView(boxFrame)

        // 4 visible corner handles + invisible generous touch targets (all inside overlay bounds)
        val corners = listOf(
            "nw" to (Gravity.TOP or Gravity.LEFT),
            "ne" to (Gravity.TOP or Gravity.RIGHT),
            "sw" to (Gravity.BOTTOM or Gravity.LEFT),
            "se" to (Gravity.BOTTOM or Gravity.RIGHT)
        )
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
            boxFrame.addView(hit)
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
            boxFrame.addView(edge)
        }

        labelPill = TextView(context).apply {
            text = "اضغط الساعة للتخصيص"
            setTextColor(Color.argb(235, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding((14f * dens).roundToInt(), (6f * dens).roundToInt(), (14f * dens).roundToInt(), (6f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(198, 14, 17, 26))
                cornerRadius = 999f
                setStroke((0.9f * dens).roundToInt(), Color.argb(56, 255, 255, 255))
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = hitPadTop + (172f * dens).roundToInt()
            }
            setOnClickListener {
                if (isOpen) {
                    onHaptic("pop")
                    onClockTap()
                }
            }
        }
        addView(labelPill)

        actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = hitPadTop + (206f * dens).roundToInt()
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
                setColor(Color.argb(192, 14, 17, 26))
                cornerRadius = 999f
                setStroke((0.9f * dens).roundToInt(), Color.argb(66, 255, 255, 255))
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
                        labelPill.text = "اضغط الساعة للتخصيص"
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

        val baseUnitH = startH / max(0.5f, startSy)
        val maxSizeAtUnitSy = (fitSz * (maxH / max(40f, baseUnitH))).coerceIn(56f, fullWidthSize)

        if (horiz) {
            val dxOut = dx * if (dir.contains('w')) -2f else 2f
            val nextW = (startW + dxOut).coerceIn(80f, maxW)
            cfg.size = (fitSz * (nextW / max(40f, startW))).roundToInt().toFloat().coerceIn(56f, maxSizeAtUnitSy)
        }
        val baseHAtSize = baseUnitH * (cfg.size / fitSz)
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

        val art = clockView.activeArtifact
        val baseSize = max(40f, art?.sizeDp ?: clockView.committedSize())
        val baseSx = max(0.4f, art?.sx ?: clockView.committedSx())
        val baseSy = max(0.5f, clockView.committedSy())
        val curTextW = max(40f, art?.textWDp ?: (clockView.width / dens))
        val curTextH = max(40f, art?.textHDp ?: (clockView.height / dens))
        val rawScaleX = (cfg.size / baseSize) * (cfg.sx / baseSx)
        val rawScaleY = (cfg.size / baseSize) * (cfg.sy / baseSy)
        val previewFit = min(1f, min(maxW / max(1f, curTextW * rawScaleX), maxH / max(1f, curTextH * rawScaleY)))
        clockView.updatePreviewTransform(rawScaleX * previewFit, rawScaleY * previewFit)
        layoutAroundClock()
        onConfigChanged(true)
    }

    fun layoutAroundClock() {
        val parentV = clockView.parent as? View ?: return
        val art = clockView.activeArtifact
        val viewW = if (clockView.width > 0) clockView.width.toFloat() else (art?.wDp ?: 260f) * dens
        val parentW = if (parentV.width > 0) parentV.width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
        val viewX = if (clockView.width > 0) clockView.x else (parentW - viewW) * 0.5f

        val inkL = (art?.inkLeftDp ?: 18f) * dens
        val inkT = (art?.inkTopDp ?: 18f) * dens
        val inkR = (art?.inkRightDp ?: (viewW / dens - 18f)) * dens
        val inkB = (art?.inkBottomDp ?: ((if (clockView.height > 0) clockView.height / dens else (art?.hDp ?: 210f)) - 18f)) * dens

        val pivX = viewW * 0.5f
        val sL = pivX + (inkL - pivX) * clockView.scaleX
        val sR = pivX + (inkR - pivX) * clockView.scaleX
        val sT = inkT * clockView.scaleY
        val sB = inkB * clockView.scaleY

        val boxPadX = 10f * dens
        val boxPadY = 10f * dens
        var boxLeft = parentV.x + viewX + sL - boxPadX
        var boxRight = parentV.x + viewX + sR + boxPadX
        val boxTop = parentV.y + clockView.y + sT - boxPadY
        val boxBottom = parentV.y + clockView.y + sB + boxPadY

        val shellW = (parent as? View)?.width?.toFloat() ?: 0f
        if (shellW > 90f * dens) {
            val edgeMargin = 12f * dens
            boxLeft = boxLeft.coerceIn(edgeMargin, shellW - 72f * dens)
            boxRight = boxRight.coerceIn(boxLeft + 60f * dens, shellW - edgeMargin)
        }

        val boxW = (boxRight - boxLeft).roundToInt().coerceAtLeast((80f * dens).roundToInt())
        val boxH = (boxBottom - boxTop).roundToInt().coerceAtLeast((60f * dens).roundToInt())

        val boxLp = boxFrame.layoutParams as LayoutParams
        boxLp.width = boxW
        boxLp.height = boxH
        boxLp.leftMargin = hitPadX
        boxLp.topMargin = hitPadTop
        boxFrame.layoutParams = boxLp

        val pillLp = labelPill.layoutParams as LayoutParams
        pillLp.topMargin = hitPadTop + boxH + (12f * dens).roundToInt()
        labelPill.layoutParams = pillLp

        val actLp = actionsRow.layoutParams as LayoutParams
        actLp.topMargin = hitPadTop + boxH + (46f * dens).roundToInt()
        actionsRow.layoutParams = actLp

        val lp = (layoutParams as? LayoutParams) ?: LayoutParams(
            boxW + hitPadX * 2,
            boxH + hitPadTop + hitPadBot,
            Gravity.TOP or Gravity.LEFT
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = boxW + hitPadX * 2
        lp.height = boxH + hitPadTop + hitPadBot
        lp.leftMargin = (boxLeft - hitPadX).roundToInt()
        lp.topMargin = (boxTop - hitPadTop).roundToInt()
        layoutParams = lp
    }

    fun open() {
        isOpen = true
        animate().cancel()
        layoutAroundClock()
        alpha = 0f
        scaleX = 0.94f
        scaleY = 0.94f
        visibility = VISIBLE
        labelPill.text = "اضغط الساعة للتخصيص"
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240L)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.05f))
            .start()
        pulseAnim?.cancel()
        pulseAnim = ValueAnimator.ofFloat(1f, 1.16f, 1f).apply {
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
        pulseAnim?.cancel()
        pulseAnim = null
        handler.removeCallbacks(autoCloseRunnable)
        if (dragDir != null) {
            dragDir = null
            clockView.clearPreviewTransform()
            onCommit()
        }
        animate().cancel()
        animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(170L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .withEndAction {
                if (!isOpen) {
                    visibility = GONE
                    alpha = 1f
                    scaleX = 1f
                    scaleY = 1f
                }
            }
            .start()
    }

    fun scheduleAutoClose() {
        handler.removeCallbacks(autoCloseRunnable)
        handler.postDelayed(autoCloseRunnable, 9000L)
    }
}
