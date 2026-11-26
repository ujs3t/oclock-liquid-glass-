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
    private val dens = resources.displayMetrics.density
    private val dot = View(context)
    private val label = TextView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var collapseRunnable = Runnable { collapse() }
    private var expanded = false

    init {
        val wCollapsed = (122f * dens).roundToInt()
        val hCollapsed = (34f * dens).roundToInt()
        layoutParams = LayoutParams(wCollapsed, hCollapsed, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 999f
            setStroke((0.8f * dens).roundToInt(), Color.argb(20, 255, 255, 255))
        }
        elevation = 10f * dens

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = LAYOUT_DIRECTION_RTL
            alpha = 0f
            setPadding((14f * dens).roundToInt(), 0, (14f * dens).roundToInt(), 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        dot.apply {
            val ds = (8f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(ds, ds).apply { marginEnd = (8f * dens).roundToInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF30D158.toInt())
            }
        }
        label.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        row.addView(dot)
        row.addView(label)
        addView(row)
        tag = row
    }

    fun pulse(text: String, dotColor: Int = 0xFF30D158.toInt(), ms: Long = 1800L) {
        handler.removeCallbacks(collapseRunnable)
        label.text = text
        (dot.background as? GradientDrawable)?.setColor(dotColor)
        val row = tag as View
        val targetW = (max(216f, min(330f, 54f + text.length * 7.6f)) * dens).roundToInt()
        val targetH = (36f * dens).roundToInt()
        animateSize(targetW, targetH, 1f)
        row.animate().alpha(1f).setDuration(220L).start()
        expanded = true
        handler.postDelayed(collapseRunnable, ms)
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        val row = tag as View
        row.animate().alpha(0f).setDuration(150L).start()
        animateSize((122f * dens).roundToInt(), (34f * dens).roundToInt(), 0f)
    }

    private fun animateSize(wTarget: Int, hTarget: Int, alphaTarget: Float) {
        val wStart = layoutParams.width
        val hStart = layoutParams.height
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (alphaTarget > 0.5f) 460L else 360L
            interpolator = if (alphaTarget > 0.5f) OvershootInterpolator(1.1f) else DecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                layoutParams.width = (wStart + (wTarget - wStart) * f).roundToInt()
                layoutParams.height = (hStart + (hTarget - hStart) * f).roundToInt()
                requestLayout()
            }
            start()
        }
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
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.85f * dens
    }

    var uiInk = Color.WHITE
    var glassBg = Color.argb(10, 255, 255, 255)
    var isActive = false
        set(value) {
            field = value
            animate().scaleX(if (value) 1.06f else 1f).scaleY(if (value) 1.06f else 1f).setDuration(240L).start()
            invalidate()
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
                    animate().scaleX(0.90f).scaleY(0.90f).setDuration(110L).start()
                    handler.postDelayed(holdRunnable, 350L)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(holdRunnable)
                    val held = SystemClock.uptimeMillis() - downTs
                    if (!holdFired && held < 350L) {
                        onHaptic("pop")
                        pop()
                        onTapAction()
                    } else {
                        animate().scaleX(if (isActive) 1.06f else 1f).scaleY(if (isActive) 1.06f else 1f).setDuration(240L).start()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdRunnable)
                    animate().scaleX(if (isActive) 1.06f else 1f).scaleY(if (isActive) 1.06f else 1f).setDuration(200L).start()
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
            .scaleX(if (isActive) 1.06f else 1f)
            .scaleY(if (isActive) 1.06f else 1f)
            .setDuration(420L)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        val parentV = parent as? View
        val ox = (parentV?.x ?: 0f) + x
        val oy = (parentV?.y ?: 0f) + y
        glassHelper.draw(this, canvas, glassBg, ox, oy)

        val cx = width * 0.5f
        val cy = height * 0.5f
        strokePaint.color = if (isActive && kind == "flash") Color.WHITE else uiInk
        iconPaint.color = strokePaint.color

        if (kind == "flash") {
            // Exact iOS Flashlight silhouette (flared head + slim handle + power dot)
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
            canvas.drawPath(path, strokePaint)
            canvas.drawLine(cx - 4.2f * s, cy - 4.8f * s, cx + 4.2f * s, cy - 4.8f * s, strokePaint)
            canvas.drawCircle(cx, cy + 1.6f * s, 1.3f * s, iconPaint)
        } else {
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
    private val glassHelper = LiquidGlassCardHelper(backdrop, perf, 26f, 22f, 18f, 1.50f, 0.30f)

    private val artView: ImageView
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
    private var lpDownX = 0f
    private var lpDownY = 0f
    private var lpFired = false
    private val lpRunnable = Runnable {
        if (!scrubbing && onLongPress != null) {
            lpFired = true
            animate().cancel()
            scaleX = 0.955f
            scaleY = 0.955f
            animate().scaleX(1f).scaleY(1f).setDuration(380L).setInterpolator(OvershootInterpolator(1.6f)).start()
            onLongPress?.invoke()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lpDownX = ev.x
                lpDownY = ev.y
                lpFired = false
                lpHandler.removeCallbacks(lpRunnable)
                lpHandler.postDelayed(lpRunnable, 360L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrubbing || hypot(ev.x - lpDownX, ev.y - lpDownY) > touchSlop) {
                    lpHandler.removeCallbacks(lpRunnable)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lpHandler.removeCallbacks(lpRunnable)
                if (lpFired) {
                    val cancelEv = MotionEvent.obtain(ev)
                    cancelEv.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancelEv)
                    cancelEv.recycle()
                    lpFired = false
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    init {
        setWillNotDraw(false)
        layoutDirection = LAYOUT_DIRECTION_LTR
        setPadding((14f * dens).roundToInt(), (13f * dens).roundToInt(), (14f * dens).roundToInt(), (11f * dens).roundToInt())

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
            val s = (44f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (12f * dens).roundToInt() }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, 9f * dens)
                }
            }
        }
        val metaCol = LinearLayout(context).apply {
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        metaCol.addView(titleView)
        metaCol.addView(artistView)

        val eqRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (16f * dens).roundToInt()).apply {
                marginStart = (10f * dens).roundToInt()
            }
        }
        for (i in 0 until 4) {
            val bar = View(context).apply {
                layoutParams = LinearLayout.LayoutParams((2.5f * dens).roundToInt(), (14f * dens).roundToInt()).apply {
                    marginStart = if (i > 0) (2.5f * dens).roundToInt() else 0
                }
                background = GradientDrawable().apply {
                    setColor(uiInk)
                    cornerRadius = 99f
                }
                pivotY = 14f * dens
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
                val trackH = 5f * dens
                val cy = height * 0.5f
                trackRect.set(0f, cy - trackH * 0.5f, width.toFloat(), cy + trackH * 0.5f)
                p.color = Color.argb(56, Color.red(uiInk), Color.green(uiInk), Color.blue(uiInk))
                canvas.drawRoundRect(trackRect, trackH * 0.5f, trackH * 0.5f, p)

                val fillW = width * progressRatio.coerceIn(0f, 1f)
                fillRect.set(0f, cy - trackH * 0.5f, max(trackH, fillW), cy + trackH * 0.5f)
                p.color = uiInk
                canvas.drawRoundRect(fillRect, trackH * 0.5f, trackH * 0.5f, p)

                val thumbR = (if (scrubbing) 7.5f else 4.5f) * dens
                p.color = Color.WHITE
                canvas.drawCircle(fillW.coerceIn(thumbR, width - thumbR), cy, thumbR, p)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (20f * dens).roundToInt()).apply {
                topMargin = (6f * dens).roundToInt()
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        remTimeText = TextView(context).apply {
            text = "-4:23"
            setTextColor(uiDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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
                topMargin = (4f * dens).roundToInt()
            }
        }

        fun makeCtrlIcon(wDp: Float, hDp: Float, alphaVal: Float = 1f, onClick: (View) -> Unit): ImageView {
            return ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, (hDp * dens).roundToInt(), 1f)
                scaleType = ImageView.ScaleType.CENTER
                alpha = alphaVal
                setOnClickListener { v ->
                    v.animate().cancel()
                    v.scaleX = 0.84f; v.scaleY = 0.84f
                    v.animate().scaleX(1f).scaleY(1f).setDuration(380L).setInterpolator(OvershootInterpolator(1.6f)).start()
                    onClick(v)
                }
            }
        }

        starBtn = makeCtrlIcon(38f, 38f, 0.65f) {
            starred = !starred
            updateControlIcons()
            onFavorite()
        }
        prevBtn = makeCtrlIcon(44f, 40f, 1f) { onPrev() }
        playBtn = makeCtrlIcon(52f, 44f, 1f) { onPlayToggle() }
        nextBtn = makeCtrlIcon(44f, 40f, 1f) { onNext() }
        airBtn = makeCtrlIcon(38f, 38f, 0.65f) { onAirPlay() }

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
        trackInitialized = true
        currentTrack = track
        customArtBmp = customArt
        titleView.text = track.title
        artistView.text = track.artist
        if (customArt != null) {
            artView.setImageBitmap(customArt)
        } else {
            val s = (44f * dens).roundToInt()
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
        artView.animate().cancel()
        artView.scaleX = 1f
        artView.scaleY = 1f
        artView.alpha = 1f
    }

    fun setPlaying(playing: Boolean) {
        if (playingInitialized && playing == playingState) return
        playingInitialized = true
        playingState = playing
        artView.animate().cancel()
        artView.scaleX = 1f
        artView.scaleY = 1f
        artView.alpha = 1f
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
        val sz = (26f * dens).roundToInt()
        val sm = (18f * dens).roundToInt()
        starBtn.setImageBitmap(drawStarIcon(sm, if (starred) 0xFFFFD60A.toInt() else uiInk, starred))
        starBtn.alpha = if (starred) 1f else 0.65f
        prevBtn.setImageBitmap(drawSkipIcon(sz, uiInk, forward = false))
        playBtn.setImageBitmap(if (playingState) drawPauseIcon((30f * dens).roundToInt(), uiInk) else drawPlayIcon((30f * dens).roundToInt(), uiInk))
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
