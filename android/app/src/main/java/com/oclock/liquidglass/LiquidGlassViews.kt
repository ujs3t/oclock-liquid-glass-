package com.oclock.liquidglass

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class LiquidGlassCardHelper(
    private val backdrop: LiquidGlassShaders.BackdropState,
    private val perf: PerfMonitor,
    private val radiusDp: Float,
    private val bezelDp: Float,
    private val thickDp: Float,
    private val ior: Float,
    private val shadowInt: Float
) {
    private var shader: RuntimeShader? = if (Build.VERSION.SDK_INT >= 33) LiquidGlassShaders.createCardShader() else null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()
    private var cpuBmp: Bitmap? = null
    private var cpuKey = ""

    fun draw(view: View, canvas: Canvas, glassBgColor: Int, originX: Float, originY: Float) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 1f || h <= 1f) return
        val dens = view.resources.displayMetrics.density
        val radPx = radiusDp * dens
        val bzPx = bezelDp * dens
        val thPx = thickDp * dens

        val shaderSrc = backdrop.compositedShader ?: backdrop.wallShader
        if (Build.VERSION.SDK_INT >= 33 && shader != null && shaderSrc != null) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val sh = shader!!
            LiquidGlassShaders.configureCardShader(
                sh, shaderSrc, originX, originY, w, h, radPx, bzPx, thPx, ior, glassBgColor, shadowInt
            )
            paint.shader = sh
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            perf.recordWork("cardsDraw", (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0)
        } else {
            val wall = backdrop.compositedBitmap ?: backdrop.wallBitmap ?: return
            val bw = max(16, (w / 2f).roundToInt())
            val bh = max(16, (h / 2f).roundToInt())
            val key = "${originX.roundToInt()}|${originY.roundToInt()}|$bw|$bh|$glassBgColor|${(backdrop.wallScaleX * 100).roundToInt()}"
            var out = cpuBmp?.takeIf { it.width == bw && it.height == bh }
            if (out == null || cpuKey != key) {
                if (out == null) {
                    out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    cpuBmp = out
                }
                val isComp = backdrop.compositedBitmap != null
                val scX = if (isComp) (backdrop.wallScaleX * (backdrop.wallBitmap!!.width.toFloat() / wall.width)) else backdrop.wallScaleX
                val scY = if (isComp) (backdrop.wallScaleY * (backdrop.wallBitmap!!.height.toFloat() / wall.height)) else backdrop.wallScaleY
                NativeGlass.renderCardCpuFallback(
                    wall, out, originX, originY, w, h, radPx,
                    backdrop.wallLeft, backdrop.wallTop, scX, scY,
                    bzPx, thPx, ior, glassBgColor, shadowInt
                )
                cpuKey = key
            }
            dstRect.set(0f, 0f, w, h)
            canvas.drawBitmap(out, null, dstRect, paint)
        }
    }
}

class DynamicIslandView(context: Context) : FrameLayout(context) {
    private enum class Mode { IDLE, PULSE, COMPACT_MUSIC, COMPACT_FLASH, COMPACT_DND, EXPANDED }

    private val dens = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.IDLE

    private val pillBg = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = 18f * dens
        setStroke((0.8f * dens).roundToInt(), Color.argb(28, 255, 255, 255))
    }
    private var curCornerPx = 18f * dens
    private var morphAnim: ValueAnimator? = null

    var onPrev: (() -> Unit)? = null
    var onPlayToggle: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onToggleFlash: (() -> Unit)? = null
    var onOpenLibrary: (() -> Unit)? = null
    var onHaptic: ((String) -> Unit)? = null

    private var currentTrack: TrackInfo = AppConstants.QUEUE[0]
    private var currentArt: Bitmap? = null
    private var lastSongId = ""
    private var playingState = false
    private var flashOn = false
    private var dndOn = false
    private var batPct = 92
    private var batCharging = false
    private var accentColor = 0xFF30D158.toInt()

    // 1) Pulse / compact status row
    private val pulseRow: LinearLayout
    private val pulseDot: View
    private val pulseIcon: ImageView
    private val pulseLabel: TextView

    // 2) Compact live music pill row
    private val compactRow: LinearLayout
    private val compactArt: ImageView
    private val compactTitle: TextView
    private val compactFlashBadge: ImageView
    private val compactEq: MiniEqView

    // 3) Expanded interactive island card
    private val expandedBox: LinearLayout
    private val expArt: ImageView
    private val expTitle: TextView
    private val expSub: TextView
    private val expStatusBadge: TextView
    private val expEq: MiniEqView
    private val expPrevBtn: ImageView
    private val expPlayBtn: ImageView
    private val expNextBtn: ImageView
    private val expLibBtn: ImageView
    private val expFlashBtn: ImageView

    private val settleRunnable = Runnable { settleToRestingMode() }

    private class MiniEqView(context: Context) : View(context) {
        private val dens = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var barColor = 0xFF30D158.toInt()
        private var phase = 0f
        var active = false
            set(value) {
                if (field == value) return
                field = value
                if (value && isAttachedToWindow && visibility == VISIBLE) startAnim() else stopAnim()
                invalidate()
            }
        private val anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 880L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                phase = a.animatedValue as Float
                invalidate()
            }
        }
        private fun startAnim() { if (!anim.isStarted) anim.start() }
        private fun stopAnim() { anim.cancel() }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (active && visibility == VISIBLE) startAnim() }
        override fun onDetachedFromWindow() { stopAnim(); super.onDetachedFromWindow() }
        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (active && visibility == VISIBLE && isAttachedToWindow) startAnim() else stopAnim()
        }
        override fun onDraw(canvas: Canvas) {
            val bw = 2.4f * dens
            val gap = 2f * dens
            val totalW = bw * 4f + gap * 3f
            val sx = (width - totalW) * 0.5f
            val h = height.toFloat()
            val phases = floatArrayOf(0f, 1.5f, 3.0f, 4.3f)
            p.color = barColor
            for (i in 0 until 4) {
                val frac = if (active) (0.28f + 0.72f * abs(sin(phase + phases[i]))) else 0.25f
                val bh = max(bw, h * frac)
                val x = sx + i * (bw + gap)
                canvas.drawRoundRect(x, (h - bh) * 0.5f, x + bw, (h + bh) * 0.5f, bw * 0.5f, bw * 0.5f, p)
            }
        }
    }

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        val wCollapsed = (122f * dens).roundToInt()
        val hCollapsed = (34f * dens).roundToInt()
        layoutParams = LayoutParams(wCollapsed, hCollapsed, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        background = pillBg
        elevation = 12f * dens
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, curCornerPx)
            }
        }
        isClickable = true
        isFocusable = true

        // 1) Pulse Row
        pulseRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = LAYOUT_DIRECTION_RTL
            alpha = 0f
            visibility = GONE
            setPadding((14f * dens).roundToInt(), 0, (14f * dens).roundToInt(), 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        pulseDot = View(context).apply {
            val ds = (8f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(ds, ds).apply { marginEnd = (8f * dens).roundToInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF30D158.toInt())
            }
        }
        pulseIcon = ImageView(context).apply {
            val s = (16f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (7f * dens).roundToInt() }
            visibility = GONE
        }
        pulseLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        pulseRow.addView(pulseDot)
        pulseRow.addView(pulseIcon)
        pulseRow.addView(pulseLabel)
        addView(pulseRow)

        // 2) Compact Live Music Row
        compactRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = LAYOUT_DIRECTION_LTR
            alpha = 0f
            visibility = GONE
            setPadding((8f * dens).roundToInt(), 0, (10f * dens).roundToInt(), 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        compactArt = ImageView(context).apply {
            val s = (22f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (8f * dens).roundToInt() }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, 6f * dens)
                }
            }
        }
        compactTitle = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        compactFlashBadge = ImageView(context).apply {
            val s = (15f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginStart = (5f * dens).roundToInt()
                marginEnd = (4f * dens).roundToInt()
            }
            visibility = GONE
            setImageBitmap(drawMiniFlashBitmap(s, Color.WHITE))
        }
        compactEq = MiniEqView(context).apply {
            layoutParams = LinearLayout.LayoutParams((18f * dens).roundToInt(), (14f * dens).roundToInt()).apply {
                marginStart = (6f * dens).roundToInt()
            }
        }
        compactRow.addView(compactArt)
        compactRow.addView(compactTitle)
        compactRow.addView(compactFlashBadge)
        compactRow.addView(compactEq)
        addView(compactRow)

        // 3) Expanded Interactive Box
        expandedBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LAYOUT_DIRECTION_LTR
            alpha = 0f
            visibility = GONE
            setPadding((14f * dens).roundToInt(), (11f * dens).roundToInt(), (14f * dens).roundToInt(), (10f * dens).roundToInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val expTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        expArt = ImageView(context).apply {
            val s = (40f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (11f * dens).roundToInt() }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, 10f * dens)
                }
            }
            setOnClickListener {
                onHaptic?.invoke("pop")
                collapseNow()
                onOpenLibrary?.invoke()
            }
        }
        val expMeta = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                onHaptic?.invoke("pop")
                collapseNow()
                onOpenLibrary?.invoke()
            }
        }
        expTitle = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
        }
        expSub = TextView(context).apply {
            setTextColor(Color.argb(175, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        expMeta.addView(expTitle)
        expMeta.addView(expSub)

        expStatusBadge = TextView(context).apply {
            setTextColor(Color.argb(215, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding((7f * dens).roundToInt(), (2f * dens).roundToInt(), (7f * dens).roundToInt(), (2f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(34, 255, 255, 255))
                cornerRadius = 999f
            }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6f * dens).roundToInt()
            }
        }
        expEq = MiniEqView(context).apply {
            layoutParams = LinearLayout.LayoutParams((18f * dens).roundToInt(), (16f * dens).roundToInt())
        }
        expTop.addView(expArt)
        expTop.addView(expMeta)
        expTop.addView(expStatusBadge)
        expTop.addView(expEq)

        val expCtrlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (9f * dens).roundToInt()
            }
        }
        fun makeIslandBtn(wDp: Float, hDp: Float, bgAlpha: Int = 26, onTap: (View) -> Unit): ImageView {
            return ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams((wDp * dens).roundToInt(), (hDp * dens).roundToInt()).apply {
                    marginStart = (4f * dens).roundToInt()
                    marginEnd = (4f * dens).roundToInt()
                }
                scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.argb(bgAlpha, 255, 255, 255))
                    cornerRadius = 999f
                }
                setOnClickListener { v ->
                    onHaptic?.invoke("pop")
                    v.animate().cancel()
                    v.scaleX = 0.80f; v.scaleY = 0.80f
                    v.animate().scaleX(1f).scaleY(1f).setDuration(360L).setInterpolator(OvershootInterpolator(1.9f)).start()
                    resetExpandTimer()
                    onTap(v)
                }
            }
        }

        expPrevBtn = makeIslandBtn(44f, 32f) { onPrev?.invoke() }
        expPlayBtn = makeIslandBtn(54f, 34f, bgAlpha = 46) { onPlayToggle?.invoke() }
        expNextBtn = makeIslandBtn(44f, 32f) { onNext?.invoke() }
        expLibBtn = makeIslandBtn(40f, 32f) {
            collapseNow()
            onOpenLibrary?.invoke()
        }
        expFlashBtn = makeIslandBtn(40f, 32f) {
            onToggleFlash?.invoke()
        }

        val ic = (17f * dens).roundToInt()
        expPrevBtn.setImageBitmap(drawMiniSkipBitmap(ic, Color.WHITE, false))
        expNextBtn.setImageBitmap(drawMiniSkipBitmap(ic, Color.WHITE, true))
        expLibBtn.setImageBitmap(drawMiniLibraryBitmap(ic, Color.WHITE))
        refreshExpandedControls()

        expCtrlRow.addView(expPrevBtn)
        expCtrlRow.addView(expPlayBtn)
        expCtrlRow.addView(expNextBtn)
        expCtrlRow.addView(expLibBtn)
        expCtrlRow.addView(expFlashBtn)

        expandedBox.addView(expTop)
        expandedBox.addView(expCtrlRow)
        addView(expandedBox)

        setOnClickListener {
            onHaptic?.invoke("pop")
            if (mode == Mode.EXPANDED) {
                settleToRestingMode()
            } else {
                expandInteractive()
            }
        }
        setOnLongClickListener {
            onHaptic?.invoke("heavy")
            expandInteractive()
            true
        }
    }

    fun syncMedia(track: TrackInfo, art: Bitmap?, playing: Boolean) {
        val songId = "${track.contentUri}|${track.title}|${track.artist}"
        val changed = songId != lastSongId || art !== currentArt || playing != playingState
        if (!changed) return
        val songChanged = lastSongId.isNotEmpty() && songId != lastSongId
        val playStarted = !playingState && playing
        val playStopped = playingState && !playing

        lastSongId = songId
        currentTrack = track
        currentArt = art
        playingState = playing

        // Brighten track color so equalizer bars glow cleanly on black island
        val r = min(255, (Color.red(track.c2) * 1.25f + 55f).roundToInt())
        val g = min(255, (Color.green(track.c2) * 1.25f + 55f).roundToInt())
        val b = min(255, (Color.blue(track.c2) * 1.25f + 55f).roundToInt())
        accentColor = Color.rgb(r, g, b)
        compactEq.barColor = accentColor
        expEq.barColor = accentColor
        compactEq.active = playing
        expEq.active = playing

        val artBmp = art ?: makeTrackSwatch(track, (40f * dens).roundToInt())
        compactArt.setImageBitmap(artBmp)
        expArt.setImageBitmap(artBmp)
        compactTitle.text = track.title
        expTitle.text = track.title
        updateExpandedSubtitle()
        refreshExpandedControls()

        if (mode == Mode.EXPANDED) {
            if (songChanged) {
                expArt.animate().cancel()
                expArt.scaleX = 0.74f; expArt.scaleY = 0.74f
                expArt.animate().scaleX(1f).scaleY(1f).setDuration(420L).setInterpolator(OvershootInterpolator(1.8f)).start()
                springBounce(1.03f)
            }
            resetExpandTimer()
        } else if (songChanged) {
            pulse("♪ ${track.title}", accentColor, 1650L, artBmp = artBmp)
        } else if (playStarted || playStopped) {
            settleToRestingMode()
            springBounce(1.045f)
        }
    }

    fun syncFlashlight(on: Boolean) {
        if (flashOn == on) return
        flashOn = on
        compactFlashBadge.visibility = if (on) VISIBLE else GONE
        updateExpandedSubtitle()
        refreshExpandedControls()
        if (mode == Mode.EXPANDED) {
            springBounce(1.03f)
            resetExpandTimer()
        } else if (on) {
            val icon = drawMiniFlashBitmap((16f * dens).roundToInt(), Color.WHITE)
            pulse("المصباح قيد التشغيل", Color.WHITE, 1400L, iconBmp = icon)
        } else {
            val icon = drawMiniFlashBitmap((16f * dens).roundToInt(), Color.argb(165, 255, 255, 255))
            pulse("تم إطفاء المصباح", Color.argb(165, 255, 255, 255), 1100L, iconBmp = icon)
        }
    }

    fun syncDnd(on: Boolean, silent: Boolean = false) {
        if (dndOn == on) return
        dndOn = on
        updateExpandedSubtitle()
        if (!silent) {
            if (mode == Mode.EXPANDED) {
                resetExpandTimer()
            } else {
                val icon = StatusBarView.renderMoonBitmap(0xFFA78BFA.toInt(), (15f * dens).roundToInt())
                pulse(if (on) "عدم الإزعاج مفعّل" else "تم إيقاف عدم الإزعاج", 0xFFA78BFA.toInt(), 1500L, iconBmp = icon)
            }
        } else if (mode != Mode.EXPANDED && mode != Mode.PULSE) {
            settleToRestingMode()
        }
    }

    fun updateBattery(pct: Int, charging: Boolean) {
        val pluggedIn = !batCharging && charging
        batPct = pct
        batCharging = charging
        updateExpandedSubtitle()
        if (pluggedIn) {
            pulse("جاري الشحن • $pct%", 0xFF30D158.toInt(), 2000L)
        }
    }

    fun pulse(
        text: String,
        dotColor: Int = 0xFF30D158.toInt(),
        ms: Long = 1700L,
        iconBmp: Bitmap? = null,
        artBmp: Bitmap? = null
    ) {
        if (mode == Mode.EXPANDED) {
            resetExpandTimer()
            return
        }
        handler.removeCallbacks(settleRunnable)
        mode = Mode.PULSE
        pulseLabel.text = text
        if (artBmp != null) {
            pulseDot.visibility = GONE
            pulseIcon.visibility = VISIBLE
            pulseIcon.setImageBitmap(artBmp)
        } else if (iconBmp != null) {
            pulseDot.visibility = GONE
            pulseIcon.visibility = VISIBLE
            pulseIcon.setImageBitmap(iconBmp)
        } else {
            pulseIcon.visibility = GONE
            pulseDot.visibility = VISIBLE
            (pulseDot.background as? GradientDrawable)?.setColor(dotColor)
        }
        showOnly(pulseRow)
        val targetW = (max(204f, min(326f, 58f + text.length * 7.6f)) * dens).roundToInt()
        val targetH = (36f * dens).roundToInt()
        morphTo(targetW, targetH, 18f * dens, expandCurve = true)
        springBounce(1.045f)
        handler.postDelayed(settleRunnable, ms)
    }

    fun expandInteractive() {
        handler.removeCallbacks(settleRunnable)
        mode = Mode.EXPANDED
        updateExpandedSubtitle()
        refreshExpandedControls()
        showOnly(expandedBox)
        val avail = (parent as? View)?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val targetW = min((326f * dens).roundToInt(), avail - (28f * dens).roundToInt())
        val targetH = (106f * dens).roundToInt()
        morphTo(targetW, targetH, 30f * dens, expandCurve = true)
        springBounce(1.035f)
        resetExpandTimer()
    }

    val isExpanded: Boolean
        get() = mode == Mode.EXPANDED

    fun collapseIfExpanded() {
        if (mode == Mode.EXPANDED) settleToRestingMode()
    }

    private fun collapseNow() {
        settleToRestingMode()
    }

    private fun resetExpandTimer() {
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, 4800L)
    }

    private fun settleToRestingMode() {
        handler.removeCallbacks(settleRunnable)
        when {
            playingState -> {
                mode = Mode.COMPACT_MUSIC
                showOnly(compactRow)
                val avail = (parent as? View)?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                val w = min((232f * dens).roundToInt(), avail - (90f * dens).roundToInt())
                morphTo(w, (36f * dens).roundToInt(), 18f * dens, expandCurve = false)
            }
            flashOn -> {
                mode = Mode.COMPACT_FLASH
                pulseDot.visibility = GONE
                pulseIcon.visibility = VISIBLE
                pulseIcon.setImageBitmap(drawMiniFlashBitmap((15f * dens).roundToInt(), Color.WHITE))
                pulseLabel.text = "المصباح"
                showOnly(pulseRow)
                morphTo((152f * dens).roundToInt(), (35f * dens).roundToInt(), 17.5f * dens, expandCurve = false)
            }
            dndOn -> {
                mode = Mode.COMPACT_DND
                pulseDot.visibility = GONE
                pulseIcon.visibility = VISIBLE
                pulseIcon.setImageBitmap(StatusBarView.renderMoonBitmap(0xFFA78BFA.toInt(), (14f * dens).roundToInt()))
                pulseLabel.text = "عدم الإزعاج"
                showOnly(pulseRow)
                morphTo((164f * dens).roundToInt(), (35f * dens).roundToInt(), 17.5f * dens, expandCurve = false)
            }
            else -> {
                mode = Mode.IDLE
                showOnly(null)
                morphTo((122f * dens).roundToInt(), (34f * dens).roundToInt(), 17f * dens, expandCurve = false)
            }
        }
    }

    private fun showOnly(target: View?) {
        for (v in arrayOf(pulseRow, compactRow, expandedBox)) {
            if (v === target) {
                v.visibility = VISIBLE
                v.animate().cancel()
                v.animate().withEndAction(null).alpha(1f).setDuration(220L).start()
            } else if (v.visibility == VISIBLE) {
                v.animate().cancel()
                v.animate().alpha(0f).setDuration(140L).withEndAction {
                    if ((mode == Mode.IDLE) || (v !== target)) v.visibility = GONE
                }.start()
            }
        }
    }

    private fun morphTo(wTarget: Int, hTarget: Int, rTarget: Float, expandCurve: Boolean) {
        morphAnim?.cancel()
        val wStart = layoutParams.width
        val hStart = layoutParams.height
        val rStart = curCornerPx
        if (wStart == wTarget && hStart == hTarget && abs(rStart - rTarget) < 0.5f) return
        morphAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expandCurve) 440L else 360L
            interpolator = if (expandCurve) OvershootInterpolator(1.18f) else OvershootInterpolator(0.70f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                layoutParams.width = max((96f * dens).roundToInt(), (wStart + (wTarget - wStart) * f).roundToInt())
                layoutParams.height = max((30f * dens).roundToInt(), (hStart + (hTarget - hStart) * f).roundToInt())
                curCornerPx = max(12f * dens, rStart + (rTarget - rStart) * f)
                pillBg.cornerRadius = curCornerPx
                invalidateOutline()
                requestLayout()
            }
            start()
        }
    }

    private fun springBounce(peak: Float) {
        animate().cancel()
        scaleX = peak
        scaleY = peak * 0.985f
        animate().scaleX(1f).scaleY(1f).setDuration(380L).setInterpolator(OvershootInterpolator(1.85f)).start()
    }

    private fun updateExpandedSubtitle() {
        val parts = ArrayList<String>(3)
        parts.add(currentTrack.artist)
        if (flashOn) parts.add("المصباح يعمل")
        if (dndOn) parts.add("عدم الإزعاج")
        expSub.text = parts.joinToString(" • ")
        expStatusBadge.text = if (batCharging) "⚡ $batPct%" else "$batPct%"
        (expStatusBadge.background as? GradientDrawable)?.setColor(
            if (batCharging) Color.argb(65, 48, 209, 88) else Color.argb(34, 255, 255, 255)
        )
    }

    private fun refreshExpandedControls() {
        val playSz = (19f * dens).roundToInt()
        expPlayBtn.setImageBitmap(drawMiniPlayPauseBitmap(playSz, Color.WHITE, playingState))
        val flashCol = if (flashOn) Color.WHITE else Color.argb(150, 255, 255, 255)
        expFlashBtn.setImageBitmap(drawMiniFlashBitmap((17f * dens).roundToInt(), flashCol))
        (expFlashBtn.background as? GradientDrawable)?.setColor(
            if (flashOn) Color.argb(90, 255, 255, 255) else Color.argb(26, 255, 255, 255)
        )
    }

    private fun makeTrackSwatch(track: TrackInfo, sz: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, sz.toFloat(), sz.toFloat(),
                intArrayOf(track.c1, track.c2, track.c3),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, sz.toFloat(), sz.toFloat(), p)
        return bmp
    }

    private fun drawMiniFlashBitmap(sz: Int, col: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = sz * 0.5f
        val cy = sz * 0.5f
        val s = sz / 20f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col
            style = Paint.Style.STROKE
            strokeWidth = 1.7f * s
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path().apply {
            moveTo(cx - 3.6f * s, cy - 6.8f * s)
            lineTo(cx + 3.6f * s, cy - 6.8f * s)
            lineTo(cx + 3.6f * s, cy - 4.0f * s)
            lineTo(cx + 1.9f * s, cy - 1.9f * s)
            lineTo(cx + 1.9f * s, cy + 6.4f * s)
            lineTo(cx - 1.9f * s, cy + 6.4f * s)
            lineTo(cx - 1.9f * s, cy - 1.9f * s)
            lineTo(cx - 3.6f * s, cy - 4.0f * s)
            close()
        }
        c.drawPath(path, p)
        c.drawLine(cx - 3.6f * s, cy - 4.0f * s, cx + 3.6f * s, cy - 4.0f * s, p)
        return bmp
    }

    private fun drawMiniPlayPauseBitmap(sz: Int, col: Int, playing: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        if (playing) {
            c.drawRoundRect(sz * 0.24f, sz * 0.18f, sz * 0.42f, sz * 0.82f, sz * 0.06f, sz * 0.06f, p)
            c.drawRoundRect(sz * 0.58f, sz * 0.18f, sz * 0.76f, sz * 0.82f, sz * 0.06f, sz * 0.06f, p)
        } else {
            val path = Path().apply {
                moveTo(sz * 0.28f, sz * 0.16f)
                lineTo(sz * 0.82f, sz * 0.50f)
                lineTo(sz * 0.28f, sz * 0.84f)
                close()
            }
            c.drawPath(path, p)
        }
        return bmp
    }

    private fun drawMiniSkipBitmap(sz: Int, col: Int, forward: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (!forward) c.scale(-1f, 1f, sz * 0.5f, sz * 0.5f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        fun tri(x0: Float, x1: Float) {
            val path = Path().apply {
                moveTo(sz * x0, sz * 0.24f)
                lineTo(sz * x1, sz * 0.50f)
                lineTo(sz * x0, sz * 0.76f)
                close()
            }
            c.drawPath(path, p)
        }
        tri(0.14f, 0.50f)
        tri(0.48f, 0.84f)
        return bmp
    }

    private fun drawMiniLibraryBitmap(sz: Int, col: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col
            style = Paint.Style.STROKE
            strokeWidth = sz * 0.11f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        c.drawLine(sz * 0.42f, sz * 0.24f, sz * 0.42f, sz * 0.70f, p)
        c.drawLine(sz * 0.76f, sz * 0.18f, sz * 0.76f, sz * 0.62f, p)
        c.drawLine(sz * 0.42f, sz * 0.24f, sz * 0.76f, sz * 0.18f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(sz * 0.32f, sz * 0.72f, sz * 0.13f, p)
        c.drawCircle(sz * 0.66f, sz * 0.64f, sz * 0.13f, p)
        return bmp
    }
}

class StatusBarView(context: Context) : FrameLayout(context) {
    private val dens = resources.displayMetrics.density
    private val netText: TextView
    private val dndIcon: ImageView
    private val sigBars: View
    private val wifiIcon: View
    private val batPct: TextView
    private val batFill: View
    private val batShell: FrameLayout
    private val batTip: View

    private var uiInk = Color.WHITE
    private var batteryLevel = 92
    private var isCharging = false
    private var netLabel = "5G"
    private var isWifi = true
    private var isOffline = false

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        setPadding((22f * dens).roundToInt(), 0, (22f * dens).roundToInt(), 0)

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL)
        }
        netText = TextView(context).apply {
            text = "5G"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(uiInk)
        }
        dndIcon = ImageView(context).apply {
            visibility = GONE
            val s = (13f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = (6f * dens).roundToInt() }
            setImageBitmap(renderMoonBitmap(Color.WHITE, (13f * dens).roundToInt()))
        }
        left.addView(netText)
        left.addView(dndIcon)
        addView(left)

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
        }

        sigBars = object : View(context) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val bw = 2.6f * dens
                val gap = 1.8f * dens
                val heights = floatArrayOf(0.38f, 0.56f, 0.78f, 1.0f)
                for (i in 0 until 4) {
                    val x = i * (bw + gap)
                    val bh = height * heights[i]
                    p.color = uiInk
                    p.alpha = if (isOffline || i == 3) 96 else 255
                    canvas.drawRoundRect(x, height - bh, x + bw, height.toFloat(), 1.3f * dens, 1.3f * dens, p)
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams((16f * dens).roundToInt(), (11f * dens).roundToInt()).apply {
                marginEnd = (5f * dens).roundToInt()
            }
        }

        wifiIcon = object : View(context) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                if (!isWifi || isOffline) return
                p.color = uiInk
                p.strokeWidth = 1.6f * dens
                val cx = width * 0.5f
                val cy = height * 0.85f
                for (r in floatArrayOf(3.5f * dens, 6.2f * dens, 8.8f * dens)) {
                    canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -135f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, 1.3f * dens, p)
                p.style = Paint.Style.STROKE
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams((16f * dens).roundToInt(), (12f * dens).roundToInt()).apply {
                marginEnd = (5f * dens).roundToInt()
            }
        }

        batPct = TextView(context).apply {
            text = "92%"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(uiInk)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (4f * dens).roundToInt()
            }
        }

        batShell = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams((22f * dens).roundToInt(), (11.5f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 3.5f * dens
                setStroke((1.3f * dens).roundToInt(), Color.argb(140, 255, 255, 255))
            }
            val pad = (2f * dens).roundToInt()
            setPadding(pad, pad, pad, pad)
        }
        batFill = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 1.6f * dens
            }
            pivotX = 0f
        }
        batShell.addView(batFill)

        batTip = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((2f * dens).roundToInt(), (4.5f * dens).roundToInt()).apply {
                marginStart = (1.5f * dens).roundToInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.argb(140, 255, 255, 255))
                cornerRadius = 1f * dens
            }
        }

        right.addView(sigBars)
        right.addView(wifiIcon)
        right.addView(batPct)
        right.addView(batShell)
        right.addView(batTip)
        addView(right)
    }

    fun applyTheme(ink: Int, dndOn: Boolean) {
        uiInk = ink
        netText.setTextColor(ink)
        batPct.setTextColor(ink)
        dndIcon.visibility = if (dndOn) VISIBLE else GONE
        dndIcon.setColorFilter(ink)
        (batShell.background as? GradientDrawable)?.setStroke(
            (1.3f * dens).roundToInt(),
            Color.argb(140, Color.red(ink), Color.green(ink), Color.blue(ink))
        )
        (batTip.background as? GradientDrawable)?.setColor(Color.argb(140, Color.red(ink), Color.green(ink), Color.blue(ink)))
        updateBattery(batteryLevel, isCharging)
        sigBars.invalidate()
        wifiIcon.invalidate()
    }

    fun updateNetwork(label: String, wifi: Boolean, offline: Boolean) {
        netLabel = label
        isWifi = wifi
        isOffline = offline
        netText.text = label
        wifiIcon.visibility = if (wifi && !offline) VISIBLE else GONE
        sigBars.invalidate()
        wifiIcon.invalidate()
    }

    fun updateBattery(levelPct: Int, charging: Boolean) {
        batteryLevel = levelPct.coerceIn(1, 100)
        isCharging = charging
        batPct.text = "$batteryLevel%"
        batFill.scaleX = batteryLevel / 100f
        val fillCol = when {
            charging -> 0xFF30D158.toInt()
            batteryLevel <= 20 -> 0xFFFF453A.toInt()
            else -> uiInk
        }
        (batFill.background as? GradientDrawable)?.setColor(fillCol)
    }

    companion object {
        fun renderMoonBitmap(color: Int, sizePx: Int): Bitmap {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            val path = Path().apply {
                val r = sizePx * 0.42f
                val cx = sizePx * 0.5f
                val cy = sizePx * 0.5f
                addCircle(cx, cy, r, Path.Direction.CW)
                val cut = Path().apply { addCircle(cx + r * 0.45f, cy - r * 0.35f, r * 0.78f, Path.Direction.CW) }
                op(cut, Path.Op.DIFFERENCE)
            }
            c.drawPath(path, p)
            return bmp
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
class ShortcutCircleButton(
    context: Context,
    private val kind: String,
    backdrop: LiquidGlassShaders.BackdropState,
    perf: PerfMonitor,
    private val onTapAction: () -> Unit,
    private val onHoldAction: () -> Unit,
    private val onHaptic: (String) -> Unit
) : View(context) {
    private val dens = resources.displayMetrics.density
    private val glassHelper = LiquidGlassCardHelper(backdrop, perf, 26f, 20f, 16f, 1.48f, 0.28f)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.85f * dens
    }

    var uiInk = Color.WHITE
    var glassBg = Color.argb(10, 255, 255, 255)
    private var activeFrac = 0f
    private var activeAnim: ValueAnimator? = null
    var isActive = false
        set(value) {
            if (field == value) return
            field = value
            activeAnim?.cancel()
            val target = if (value) 1f else 0f
            activeAnim = ValueAnimator.ofFloat(activeFrac, target).apply {
                duration = 500L
                interpolator = OvershootInterpolator(1.9f)
                addUpdateListener { a ->
                    activeFrac = a.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    private val handler = Handler(Looper.getMainLooper())
    private var downTs = 0L
    private var holdFired = false
    private val holdRunnable = Runnable {
        if (!holdFired) {
            holdFired = true
            onHaptic("heavy")
            pop()
            onHoldAction()
        }
    }

    init {
        setWillNotDraw(false)
        setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTs = SystemClock.uptimeMillis()
                    holdFired = false
                    animate().cancel()
                    animate().scaleX(0.90f).scaleY(0.90f).setDuration(110L).start()
                    handler.postDelayed(holdRunnable, 360L)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(holdRunnable)
                    val held = SystemClock.uptimeMillis() - downTs
                    if (!holdFired && held < 360L) {
                        onHaptic("pop")
                        pop()
                        onTapAction()
                    } else {
                        animate().scaleX(1f).scaleY(1f).setDuration(260L).setInterpolator(OvershootInterpolator(1.5f)).start()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdRunnable)
                    animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun pop() {
        animate().cancel()
        scaleX = 0.88f
        scaleY = 0.88f
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(420L)
            .setInterpolator(OvershootInterpolator(1.85f))
            .start()
    }

    private fun lerpArgb(c0: Int, c1: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a = (Color.alpha(c0) + (Color.alpha(c1) - Color.alpha(c0)) * f).roundToInt().coerceIn(0, 255)
        val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f).roundToInt().coerceIn(0, 255)
        val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f).roundToInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    override fun onDraw(canvas: Canvas) {
        val parentV = parent as? View
        val ox = (parentV?.x ?: 0f) + x
        val oy = (parentV?.y ?: 0f) + y
        glassHelper.draw(this, canvas, glassBg, ox, oy)

        val cx = width * 0.5f
        val cy = height * 0.5f

        if (kind == "flash") {
            val t = activeFrac.coerceIn(0f, 1f)
            val inkLum = 0.2126f * Color.red(uiInk) + 0.7152f * Color.green(uiInk) + 0.0722f * Color.blue(uiInk)
            val offCol = if (inkLum > 165f) {
                Color.argb(128, 165, 174, 190)
            } else {
                Color.argb(185, Color.red(uiInk), Color.green(uiInk), Color.blue(uiInk))
            }
            val iconCol = lerpArgb(offCol, Color.WHITE, t)
            val iconScale = 1f + 0.11f * activeFrac

            canvas.save()
            canvas.scale(iconScale, iconScale, cx, cy)
            val s = dens
            val path = Path().apply {
                moveTo(cx - 4.2f * s, cy - 8f * s)
                lineTo(cx + 4.2f * s, cy - 8f * s)
                lineTo(cx + 4.2f * s, cy - 4.8f * s)
                lineTo(cx + 2.2f * s, cy - 2.3f * s)
                lineTo(cx + 2.2f * s, cy + 7.2f * s)
                arcTo(cx - 2.2f * s, cy + 5.6f * s, cx + 2.2f * s, cy + 9f * s, 0f, 180f, false)
                lineTo(cx - 2.2f * s, cy - 2.3f * s)
                lineTo(cx - 4.2f * s, cy - 4.8f * s)
                close()
            }
            if (t > 0.02f) {
                glowPaint.color = Color.argb((54 * t).roundToInt(), 255, 255, 255)
                glowPaint.strokeWidth = 5.2f * s
                canvas.drawPath(path, glowPaint)

                glowPaint.color = Color.argb((96 * t).roundToInt(), 255, 255, 255)
                glowPaint.strokeWidth = 3.0f * s
                canvas.drawPath(path, glowPaint)

                iconPaint.color = Color.argb((235 * t).roundToInt(), 255, 255, 255)
                canvas.drawPath(path, iconPaint)
            }
            strokePaint.color = iconCol
            canvas.drawPath(path, strokePaint)
            canvas.drawLine(cx - 4.2f * s, cy - 4.8f * s, cx + 4.2f * s, cy - 4.8f * s, strokePaint)

            // Power switch dot blends cleanly when flashlight icon lights white
            iconPaint.color = lerpArgb(iconCol, Color.argb(210, 22, 26, 34), t)
            canvas.drawCircle(cx, cy + 1.6f * s, 1.35f * s, iconPaint)
            canvas.restore()
        } else {
            strokePaint.color = uiInk
            iconPaint.color = uiInk
            // Exact iOS Camera body + lens circle + viewfinder bump
            val s = dens
            val body = RectF(cx - 8.5f * s, cy - 5.2f * s, cx + 8.5f * s, cy + 6.8f * s)
            canvas.drawRoundRect(body, 2.5f * s, 2.5f * s, strokePaint)
            val bump = Path().apply {
                moveTo(cx - 4.4f * s, cy - 5.2f * s)
                lineTo(cx - 2.6f * s, cy - 7.8f * s)
                lineTo(cx + 2.6f * s, cy - 7.8f * s)
                lineTo(cx + 4.4f * s, cy - 5.2f * s)
            }
            canvas.drawPath(bump, strokePaint)
            canvas.drawCircle(cx, cy + 0.8f * s, 3.3f * s, strokePaint)
        }
    }
}

class DndPillView(
    context: Context,
    backdrop: LiquidGlassShaders.BackdropState,
    perf: PerfMonitor
) : FrameLayout(context) {
    private val dens = resources.displayMetrics.density
    private val glassHelper = LiquidGlassCardHelper(backdrop, perf, 17f, 16f, 14f, 1.48f, 0.24f)
    private val moon: ImageView
    private val text: TextView
    var glassBg = Color.argb(10, 255, 255, 255)
    var uiInk = Color.WHITE

    init {
        setWillNotDraw(false)
        layoutDirection = LAYOUT_DIRECTION_LTR
        setPadding((14f * dens).roundToInt(), 0, (14f * dens).roundToInt(), 0)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        }
        moon = ImageView(context).apply {
            val s = (13f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (7f * dens).roundToInt() }
            setImageBitmap(StatusBarView.renderMoonBitmap(Color.WHITE, s))
        }
        text = TextView(context).apply {
            text = "عدم الإزعاج"
            setTextColor(uiInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        row.addView(moon)
        row.addView(text)
        addView(row)
    }

    fun applyTheme(ink: Int, gBg: Int) {
        uiInk = ink
        glassBg = gBg
        text.setTextColor(ink)
        moon.setColorFilter(ink)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val parentV = parent as? View
        val ox = (parentV?.x ?: 0f) + x
        val oy = (parentV?.y ?: 0f) + y
        glassHelper.draw(this, canvas, glassBg, ox, oy)
    }
}

@SuppressLint("ClickableViewAccessibility")
class NowPlayingCardView(
    context: Context,
    backdrop: LiquidGlassShaders.BackdropState,
    perf: PerfMonitor,
    private val onPrev: () -> Unit,
    private val onPlayToggle: () -> Unit,
    private val onNext: () -> Unit,
    private val onScrub: (Float) -> Unit,
    private val onFavorite: () -> Unit,
    private val onAirPlay: () -> Unit
) : FrameLayout(context) {
    private val dens = resources.displayMetrics.density
    private val glassHelper = LiquidGlassCardHelper(backdrop, perf, 26f, 20f, 15f, 1.48f, 0.27f)

    private val artView: ImageView
    private val metaCol: LinearLayout
    private val titleView: TextView
    private val artistView: TextView
    private val eqBars = ArrayList<View>(4)
    private val eqAnimator: ValueAnimator

    private val scrubBar: View
    private var progressRatio = 0f
    private var scrubbing = false

    private val curTimeText: TextView
    private val remTimeText: TextView

    private val starBtn: ImageView
    private val prevBtn: ImageView
    private val playBtn: ImageView
    private val nextBtn: ImageView
    private val airBtn: ImageView

    private var customArtBmp: Bitmap? = null
    private var currentTrack: TrackInfo = AppConstants.QUEUE[0]
    private var lastSongIdentity: String = ""
    private var playingState = false
    private var trackInitialized = false
    private var playingInitialized = false
    private var starred = false
    var uiInk = Color.WHITE
    var uiDim = Color.argb(189, 255, 255, 255)
    var glassBg = Color.argb(10, 255, 255, 255)
    var onLongPress: (() -> Unit)? = null

    private val lpHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeoutMs = max(480L, ViewConfiguration.getLongPressTimeout().toLong())
    private var lpDownX = 0f
    private var lpDownY = 0f
    private var lpDownTime = 0L
    private var isHolding = false
    private var lpFired = false
    private val lpRunnable = Runnable {
        if (isHolding && !scrubbing && onLongPress != null &&
            (SystemClock.uptimeMillis() - lpDownTime) >= (longPressTimeoutMs - 30L)
        ) {
            isHolding = false
            lpFired = true
            animate().cancel()
            scaleX = 0.945f
            scaleY = 0.945f
            animate().scaleX(1f).scaleY(1f).setDuration(400L).setInterpolator(OvershootInterpolator(1.9f)).start()
            onLongPress?.invoke()
        }
    }

    private fun springCapsule(squash: Float = 0.965f, durationMs: Long = 400L, tension: Float = 1.9f) {
        animate().cancel()
        scaleX = squash
        scaleY = squash
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(durationMs)
            .setInterpolator(OvershootInterpolator(tension))
            .start()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lpDownX = ev.x
                lpDownY = ev.y
                lpDownTime = SystemClock.uptimeMillis()
                lpFired = false
                lpHandler.removeCallbacks(lpRunnable)

                val scrubTop = scrubBar.top + (scrubBar.parent as? View)?.top.let { it ?: 0 }
                val scrubBot = scrubBar.bottom + (scrubBar.parent as? View)?.top.let { it ?: 0 }
                val inScrub = ev.y >= (scrubTop - 6f * dens) && ev.y <= (scrubBot + 6f * dens)
                isHolding = !inScrub
                if (isHolding) {
                    lpHandler.postDelayed(lpRunnable, longPressTimeoutMs)
                    animate().cancel()
                    animate().scaleX(0.982f).scaleY(0.982f).setDuration(140L).setInterpolator(DecelerateInterpolator()).start()
                }
                super.dispatchTouchEvent(ev)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrubbing || hypot(ev.x - lpDownX, ev.y - lpDownY) > touchSlop) {
                    isHolding = false
                    lpHandler.removeCallbacks(lpRunnable)
                    if (!lpFired && scaleX < 0.999f) {
                        springCapsule(squash = scaleX, durationMs = 260L, tension = 1.5f)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHolding = false
                lpHandler.removeCallbacks(lpRunnable)
                if (lpFired) {
                    val cancelEv = MotionEvent.obtain(ev)
                    cancelEv.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancelEv)
                    cancelEv.recycle()
                    lpFired = false
                    return true
                }
                if (scaleX < 0.999f) {
                    springCapsule(squash = scaleX, durationMs = 340L, tension = 1.85f)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    init {
        setWillNotDraw(false)
        layoutDirection = LAYOUT_DIRECTION_LTR
        setPadding((14f * dens).roundToInt(), (9f * dens).roundToInt(), (14f * dens).roundToInt(), (8f * dens).roundToInt())

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // Top row: Art + Title/Artist + 4 Eq Bars
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        artView = ImageView(context).apply {
            val s = (40f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (11f * dens).roundToInt() }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, 10f * dens)
                }
            }
        }
        metaCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = TextView(context).apply {
            text = currentTrack.title
            setTextColor(uiInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
        }
        artistView = TextView(context).apply {
            text = currentTrack.artist
            setTextColor(uiDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        metaCol.addView(titleView)
        metaCol.addView(artistView)

        val eqRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (14f * dens).roundToInt()).apply {
                marginStart = (8f * dens).roundToInt()
            }
        }
        for (i in 0 until 4) {
            val bar = View(context).apply {
                layoutParams = LinearLayout.LayoutParams((2.6f * dens).roundToInt(), (13f * dens).roundToInt()).apply {
                    marginStart = if (i > 0) (2.2f * dens).roundToInt() else 0
                }
                background = GradientDrawable().apply {
                    setColor(uiInk)
                    cornerRadius = 99f
                }
                pivotY = 13f * dens
                scaleY = 0.22f
            }
            eqBars.add(bar)
            eqRow.addView(bar)
        }
        topRow.addView(artView)
        topRow.addView(metaCol)
        topRow.addView(eqRow)

        eqAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 950L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                val ph = a.animatedValue as Float
                val phases = floatArrayOf(0f, 1.6f, 3.1f, 4.4f)
                for (i in 0 until 4) {
                    val s = if (playingState) (0.28f + 0.72f * abs(sin(ph + phases[i]))) else 0.22f
                    eqBars[i].scaleY = s
                }
            }
        }

        // Scrubbing bar
        scrubBar = object : View(context) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            private val trackRect = RectF()
            private val fillRect = RectF()
            override fun onDraw(canvas: Canvas) {
                val trackH = 4.2f * dens
                val cy = height * 0.5f
                trackRect.set(0f, cy - trackH * 0.5f, width.toFloat(), cy + trackH * 0.5f)
                p.color = Color.argb(56, Color.red(uiInk), Color.green(uiInk), Color.blue(uiInk))
                canvas.drawRoundRect(trackRect, trackH * 0.5f, trackH * 0.5f, p)

                val fillW = width * progressRatio.coerceIn(0f, 1f)
                fillRect.set(0f, cy - trackH * 0.5f, max(trackH, fillW), cy + trackH * 0.5f)
                p.color = uiInk
                canvas.drawRoundRect(fillRect, trackH * 0.5f, trackH * 0.5f, p)

                val thumbR = (if (scrubbing) 7.2f else 4.2f) * dens
                p.color = Color.WHITE
                canvas.drawCircle(fillW.coerceIn(thumbR, width - thumbR), cy, thumbR, p)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (15f * dens).roundToInt()).apply {
                topMargin = (3f * dens).roundToInt()
            }
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        scrubbing = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        val r = (ev.x / max(1, v.width)).coerceIn(0f, 1f)
                        progressRatio = r
                        onScrub(r)
                        v.invalidate()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        scrubbing = false
                        v.invalidate()
                        true
                    }
                    else -> false
                }
            }
        }

        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (-3f * dens).roundToInt()
            }
        }
        curTimeText = TextView(context).apply {
            text = "0:00"
            setTextColor(uiDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        remTimeText = TextView(context).apply {
            text = "-4:23"
            setTextColor(uiDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        timeRow.addView(curTimeText)
        timeRow.addView(remTimeText)

        // Controls row
        val ctrlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2f * dens).roundToInt()
            }
        }

        fun makeCtrlIcon(wDp: Float, hDp: Float, alphaVal: Float = 1f, onClick: (View) -> Unit): ImageView {
            return ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, (hDp * dens).roundToInt(), 1f)
                scaleType = ImageView.ScaleType.CENTER
                alpha = alphaVal
                setOnClickListener { v ->
                    v.animate().cancel()
                    v.scaleX = 0.80f; v.scaleY = 0.80f
                    v.animate().scaleX(1f).scaleY(1f).setDuration(380L).setInterpolator(OvershootInterpolator(1.8f)).start()
                    onClick(v)
                }
            }
        }

        starBtn = makeCtrlIcon(34f, 30f, 0.65f) {
            starred = !starred
            updateControlIcons()
            springCapsule(0.975f, 360L, 1.8f)
            onFavorite()
        }
        prevBtn = makeCtrlIcon(40f, 32f, 1f) {
            springCapsule(0.968f, 380L, 1.9f)
            onPrev()
        }
        playBtn = makeCtrlIcon(46f, 34f, 1f) {
            springCapsule(0.968f, 380L, 1.9f)
            onPlayToggle()
        }
        nextBtn = makeCtrlIcon(40f, 32f, 1f) {
            springCapsule(0.968f, 380L, 1.9f)
            onNext()
        }
        airBtn = makeCtrlIcon(34f, 30f, 0.65f) {
            springCapsule(0.975f, 360L, 1.8f)
            onAirPlay()
        }

        ctrlRow.addView(starBtn)
        ctrlRow.addView(prevBtn)
        ctrlRow.addView(playBtn)
        ctrlRow.addView(nextBtn)
        ctrlRow.addView(airBtn)

        root.addView(topRow)
        root.addView(scrubBar)
        root.addView(timeRow)
        root.addView(ctrlRow)
        addView(root)

        setTrack(currentTrack, null)
        updateControlIcons()
    }

    fun setTrack(track: TrackInfo, customArt: Bitmap?) {
        if (trackInitialized && track == currentTrack && customArtBmp === customArt) return
        val songId = "${track.contentUri}|${track.title}|${track.artist}"
        val songChanged = trackInitialized && songId != lastSongIdentity
        val artArrived = trackInitialized && !songChanged && customArt != null && customArtBmp == null
        trackInitialized = true
        lastSongIdentity = songId
        currentTrack = track
        customArtBmp = customArt
        titleView.text = track.title
        artistView.text = track.artist
        if (customArt != null) {
            artView.setImageBitmap(customArt)
        } else {
            val s = (50f * dens).roundToInt()
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, s.toFloat(), s.toFloat(),
                    intArrayOf(track.c1, track.c2, track.c3),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
            artView.setImageBitmap(bmp)
        }

        if (songChanged) {
            artView.animate().cancel()
            artView.scaleX = 0.72f
            artView.scaleY = 0.72f
            artView.alpha = 0.35f
            artView.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(460L)
                .setInterpolator(OvershootInterpolator(1.85f))
                .start()

            metaCol.animate().cancel()
            metaCol.translationY = 10f * dens
            metaCol.scaleX = 0.94f
            metaCol.scaleY = 0.94f
            metaCol.alpha = 0.15f
            metaCol.animate()
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(420L)
                .setInterpolator(OvershootInterpolator(1.55f))
                .start()

            springCapsule(0.962f, 420L, 2.0f)
        } else if (artArrived) {
            artView.animate().cancel()
            artView.scaleX = 1f
            artView.scaleY = 1f
            artView.alpha = 0.65f
            artView.animate().alpha(1f).setDuration(200L).start()
        } else {
            artView.animate().cancel()
            artView.scaleX = 1f
            artView.scaleY = 1f
            artView.alpha = 1f
        }
    }

    fun setPlaying(playing: Boolean) {
        if (playingInitialized && playing == playingState) return
        val toggledAfterInit = playingInitialized
        playingInitialized = true
        playingState = playing
        if (toggledAfterInit) {
            springCapsule(0.974f, 360L, 1.85f)
        }
        if (playing) {
            if (!eqAnimator.isStarted) eqAnimator.start()
        } else {
            eqAnimator.cancel()
            eqBars.forEach { it.scaleY = 0.22f }
        }
        updateControlIcons()
    }

    fun updateTime(posSec: Double, durSec: Double) {
        val dur = max(1.0, durSec)
        val pos = posSec.coerceIn(0.0, dur)
        if (!scrubbing) {
            progressRatio = (pos / dur).toFloat()
            scrubBar.invalidate()
        }
        curTimeText.text = fmtSec(pos.roundToInt())
        remTimeText.text = "-" + fmtSec(max(0, (dur - pos).roundToInt()))
    }

    fun applyTheme(ink: Int, dim: Int, gBg: Int) {
        uiInk = ink
        uiDim = dim
        glassBg = gBg
        titleView.setTextColor(ink)
        artistView.setTextColor(dim)
        curTimeText.setTextColor(dim)
        remTimeText.setTextColor(dim)
        eqBars.forEach { (it.background as? GradientDrawable)?.setColor(ink) }
        updateControlIcons()
        scrubBar.invalidate()
        invalidate()
    }

    private fun updateControlIcons() {
        val sz = (23f * dens).roundToInt()
        val sm = (17f * dens).roundToInt()
        starBtn.setImageBitmap(drawStarIcon(sm, if (starred) 0xFFFFD60A.toInt() else uiInk, starred))
        starBtn.alpha = if (starred) 1f else 0.65f
        prevBtn.setImageBitmap(drawSkipIcon(sz, uiInk, forward = false))
        playBtn.setImageBitmap(if (playingState) drawPauseIcon((26f * dens).roundToInt(), uiInk) else drawPlayIcon((26f * dens).roundToInt(), uiInk))
        nextBtn.setImageBitmap(drawSkipIcon(sz, uiInk, forward = true))
        airBtn.setImageBitmap(drawAirPlayIcon(sm, uiInk))
    }

    private fun fmtSec(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }

    override fun onDraw(canvas: Canvas) {
        val ox = x
        val oy = y
        glassHelper.draw(this, canvas, glassBg, ox, oy)
    }

    private fun drawPlayIcon(sz: Int, col: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        val path = Path().apply {
            moveTo(sz * 0.28f, sz * 0.16f)
            lineTo(sz * 0.84f, sz * 0.50f)
            lineTo(sz * 0.28f, sz * 0.84f)
            close()
        }
        c.drawPath(path, p)
        return bmp
    }

    private fun drawPauseIcon(sz: Int, col: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        c.drawRoundRect(sz * 0.22f, sz * 0.18f, sz * 0.42f, sz * 0.82f, sz * 0.06f, sz * 0.06f, p)
        c.drawRoundRect(sz * 0.58f, sz * 0.18f, sz * 0.78f, sz * 0.82f, sz * 0.06f, sz * 0.06f, p)
        return bmp
    }

    private fun drawSkipIcon(sz: Int, col: Int, forward: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (!forward) {
            c.scale(-1f, 1f, sz * 0.5f, sz * 0.5f)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        fun tri(x0: Float, x1: Float) {
            val path = Path().apply {
                moveTo(sz * x0, sz * 0.24f)
                lineTo(sz * x1, sz * 0.50f)
                lineTo(sz * x0, sz * 0.76f)
                close()
            }
            c.drawPath(path, p)
        }
        tri(0.14f, 0.50f)
        tri(0.48f, 0.84f)
        return bmp
    }

    private fun drawStarIcon(sz: Int, col: Int, filled: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col
            style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            strokeWidth = sz * 0.09f
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        val cx = sz * 0.5f
        val cy = sz * 0.52f
        val rOut = sz * 0.40f
        val rIn = sz * 0.17f
        for (i in 0 until 10) {
            val ang = -Math.PI / 2 + i * Math.PI / 5
            val r = if (i % 2 == 0) rOut else rIn
            val px = (cx + r * kotlin.math.cos(ang)).toFloat()
            val py = (cy + r * kotlin.math.sin(ang)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, p)
        return bmp
    }

    private fun drawAirPlayIcon(sz: Int, col: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col
            style = Paint.Style.STROKE
            strokeWidth = sz * 0.1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val cx = sz * 0.5f
        val cy = sz * 0.65f
        for (r in floatArrayOf(sz * 0.14f, sz * 0.28f, sz * 0.42f)) {
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -135f, 90f, false, p)
        }
        val tri = Path().apply {
            moveTo(sz * 0.34f, sz * 0.86f)
            lineTo(sz * 0.50f, sz * 0.66f)
            lineTo(sz * 0.66f, sz * 0.86f)
            close()
        }
        p.style = Paint.Style.FILL
        c.drawPath(tri, p)
        return bmp
    }
}
