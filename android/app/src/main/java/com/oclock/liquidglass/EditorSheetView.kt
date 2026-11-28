package com.oclock.liquidglass

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

class EditorSheetView(
    context: Context,
    private val cfg: LockConfig,
    private val perf: PerfMonitor,
    private val backdrop: LiquidGlassShaders.BackdropState,
    private val getRootShell: () -> View?,
    private val getFontTypeface: (String) -> Typeface,
    private val callbacks: EditorCallbacks
) : FrameLayout(context) {

    enum class Mode { CLOCK, WALLPAPER }

    interface EditorCallbacks {
        fun onClose()
        fun onModeChanged(mode: Mode)
        fun onFontChanged(fontId: String)
        fun onGeometryPreview(sizeChanged: Boolean, stretchChanged: Boolean, yChanged: Boolean)
        fun onGeometryCommit()
        fun onFormatToggled()
        fun onColorOrOpacityChanged()
        fun onWallSelected(wallId: String)
        fun onPickCustomWall()
        fun onExtractAiDepth()
        fun onPickManualSubject()
        fun onInnerShadowChanged()
        fun onToggleWeather()
        fun onToggleMusic()
        fun onToggleNotifications()
        fun onToggleDepth()
        fun onToggleDnd()
        fun onTogglePasscode()
        fun onChangePasscodePin()
        fun onTestNotification()
        fun onOpenNotificationAccess()
        fun onPickAudio()
        fun onPickAlbumArt()
        fun onDownloadPerf()
        fun onSharePerf()
        fun onResetPerf()
        fun onHaptic(kind: String)
    }

    private val dens = resources.displayMetrics.density
    private val sheetCard: GlassSheetCard
    private val grabberWrap: FrameLayout
    private val hdr: LinearLayout
    private val titleView: TextView
    private val switchModeBtn: GlassPillButton
    private val doneBtn: GlassPillButton
    private val scrollHost: FrameLayout
    private val clockScrollView: EditorRubberScrollView
    private val wallScrollView: EditorRubberScrollView
    private val clockBody: LinearLayout
    private val wallBody: LinearLayout

    // Clock customizer controls
    private lateinit var fontScroll: RubberHorizontalScrollView
    private val fontBtns = HashMap<String, GlassFontChip>()
    private lateinit var sizeSeek: SeekBar
    private lateinit var sizeVal: TextView
    private lateinit var stretchSeek: SeekBar
    private lateinit var stretchVal: TextView
    private lateinit var widthSeek: SeekBar
    private lateinit var widthVal: TextView
    private lateinit var ySeek: SeekBar
    private lateinit var yVal: TextView
    private lateinit var fmtSegment: IosSegmentedControl

    private val colorSwatches = HashMap<String, View>()
    private lateinit var proSwatch: View
    private lateinit var proHex: TextView
    private lateinit var hueSeek: SeekBar
    private lateinit var toneSeek: SeekBar
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityVal: TextView

    private lateinit var inOpSeek: SeekBar
    private lateinit var inOpVal: TextView
    private lateinit var inThSeek: SeekBar
    private lateinit var inThVal: TextView
    private lateinit var inAngSeek: SeekBar
    private lateinit var inAngVal: TextView
    private lateinit var inFadeSeek: SeekBar
    private lateinit var inFadeVal: TextView
    private val innerSwatches = HashMap<String, View>()

    // Wallpaper & Lock customizer controls
    private val wallThumbs = HashMap<String, FrameLayout>()
    private lateinit var aiDepthBtn: IosWallActionTile
    private lateinit var aiDepthStatus: TextView

    private lateinit var togWeather: IosToggleRow
    private lateinit var togMusic: IosToggleRow
    private lateinit var togNotif: IosToggleRow
    private lateinit var togDepth: IosToggleRow
    private lateinit var togDnd: IosToggleRow
    private lateinit var togPasscode: IosToggleRow

    private lateinit var perfMeta: TextView
    private lateinit var perfStatus: TextView
    private lateinit var perfFpsVal: TextView
    private lateinit var perfJankVal: TextView
    private lateinit var perfMemVal: TextView
    private lateinit var perfPssVal: TextView

    var isOpen = false
        private set
    var currentMode: Mode = Mode.WALLPAPER
        private set

    private var safeBottomPx = (14f * dens).roundToInt()
    private var suppressSeek = false
    private var proHue = 210f
    private var proTone = 50f
    private var scrimAnim: ValueAnimator? = null
    private var currentScrimAlpha = 0

    // Unified Choreographer spring state for buttery 60/120fps open / close / height-morph
    private var morphSpanPx = 0f
    private var morphTopPx = 0f
    private var targetMorphTop = 0f
    private var velMorphTop = 0f
    private var sheetSlideY = 0f
    private var targetSlideY = 0f
    private var velSlideY = 0f
    private var sheetScale = 1f
    private var targetScale = 1f
    private var velScale = 0f
    private var sheetClosing = false
    private var springRunning = false
    private var springLastNs = 0L
    private var isDraggingSheet = false
    private val tmpVisRect = Rect()
    private val crossSlideEase = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private val sheetSpringCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!springRunning) return
            val dt = if (springLastNs == 0L) 0.016f
            else ((frameTimeNanos - springLastNs) / 1_000_000_000f).coerceIn(0.004f, 0.032f)
            springLastNs = frameTimeNanos

            val omegaSlide = if (sheetClosing) 26.0f else 23.5f
            val zetaSlide = if (sheetClosing) 0.94f else 0.86f
            val kSlide = omegaSlide * omegaSlide
            val dSlide = 2f * zetaSlide * omegaSlide

            val aSlide = kSlide * (targetSlideY - sheetSlideY) - dSlide * velSlideY
            velSlideY += aSlide * dt
            sheetSlideY += velSlideY * dt

            val omegaMorph = 22.0f
            val zetaMorph = 0.87f
            val kMorph = omegaMorph * omegaMorph
            val dMorph = 2f * zetaMorph * omegaMorph

            val aMorph = kMorph * (targetMorphTop - morphTopPx) - dMorph * velMorphTop
            velMorphTop += aMorph * dt
            morphTopPx = (morphTopPx + velMorphTop * dt).coerceIn(0f, max(0f, morphSpanPx + 18f * dens))

            val aScale = kSlide * (targetScale - sheetScale) - dSlide * velScale
            velScale += aScale * dt
            sheetScale = (sheetScale + velScale * dt).coerceIn(0.92f, 1.04f)

            applySheetVisualState()

            val slideOutLimit = max(120f, sheetHeightFor(currentMode) * 0.72f)
            if (sheetClosing && (sheetSlideY >= slideOutLimit || sheetCard.alpha <= 0.03f)) {
                springRunning = false
                sheetClosing = false
                velSlideY = 0f
                velMorphTop = 0f
                velScale = 0f
                visibility = GONE
                sheetSlideY = 0f
                sheetScale = 1f
                sheetCard.translationY = 0f
                sheetCard.scaleX = 1f
                sheetCard.scaleY = 1f
                sheetCard.alpha = 1f
                callbacks.onClose()
                return
            }

            val settled = !sheetClosing &&
                abs(targetSlideY - sheetSlideY) < 0.45f && abs(velSlideY) < 6f &&
                abs(targetMorphTop - morphTopPx) < 0.45f && abs(velMorphTop) < 6f &&
                abs(targetScale - sheetScale) < 0.002f && abs(velScale) < 0.02f

            if (settled) {
                sheetSlideY = targetSlideY
                morphTopPx = targetMorphTop
                sheetScale = targetScale
                velSlideY = 0f
                velMorphTop = 0f
                velScale = 0f
                springRunning = false
                applySheetVisualState()
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    init {
        visibility = GONE
        layoutDirection = LAYOUT_DIRECTION_RTL
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { close() }

        val maxSheetH = sheetHeightFor(Mode.WALLPAPER)
        morphSpanPx = (maxSheetH - sheetHeightFor(Mode.CLOCK)).toFloat().coerceAtLeast(0f)

        sheetCard = GlassSheetCard(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            layoutDirection = LAYOUT_DIRECTION_RTL
            val padH = (14f * dens).roundToInt()
            setPadding(padH, (6f * dens).roundToInt(), padH, (12f * dens).roundToInt())
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    val topY = topInsetPx.roundToInt().coerceIn(0, max(0, v.height - 8))
                    outline.setRoundRect(0, topY, v.width, v.height, 30f * dens)
                }
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, maxSheetH, Gravity.BOTTOM).apply {
                val side = (8f * dens).roundToInt()
                leftMargin = side
                rightMargin = side
                bottomMargin = safeBottomPx + (6f * dens).roundToInt()
            }
        }

        // Grabber + swipe down dismiss
        grabberWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (18f * dens).roundToInt())
        }
        val grabber = View(context).apply {
            layoutParams = LayoutParams((36f * dens).roundToInt(), (4f * dens).roundToInt(), Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(Color.argb(104, 255, 255, 255))
                cornerRadius = 99f
            }
        }
        grabberWrap.addView(grabber)
        attachSwipeDismiss(grabberWrap)

        // Sticky Liquid Glass Header (keeps "تم" and full unclipped "الساعة"/"الخلفية" pill)
        hdr = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8f * dens).roundToInt()
            }
        }
        titleView = TextView(context).apply {
            text = "تخصيص الخلفية"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6f * dens).roundToInt()
            }
        }
        switchModeBtn = GlassPillButton(context, "الساعة", radiusDp = 999f, isAccent = false, minWidthDp = 76f) {
            val next = if (currentMode == Mode.CLOCK) Mode.WALLPAPER else Mode.CLOCK
            switchMode(next)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (32f * dens).roundToInt()).apply {
                marginEnd = (6f * dens).roundToInt()
            }
        }
        doneBtn = GlassPillButton(context, "تم", radiusDp = 999f, isAccent = true, minWidthDp = 52f) {
            close()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (32f * dens).roundToInt())
        }
        hdr.addView(titleView)
        hdr.addView(switchModeBtn)
        hdr.addView(doneBtn)
        attachSwipeDismiss(hdr)

        scrollHost = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        clockScrollView = EditorRubberScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = GONE
            onScrollOrRubber = { invalidateVisibleGlass(this@EditorSheetView.sheetCard) }
        }
        wallScrollView = EditorRubberScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = VISIBLE
            onScrollOrRubber = { invalidateVisibleGlass(this@EditorSheetView.sheetCard) }
        }
        clockBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, morphSpanPx.roundToInt() + (18f * dens).roundToInt())
        }
        wallBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (16f * dens).roundToInt())
        }

        buildClockBody(clockBody)
        buildWallpaperBody(wallBody)

        clockScrollView.addView(clockBody)
        wallScrollView.addView(wallBody)
        scrollHost.addView(clockScrollView)
        scrollHost.addView(wallScrollView)

        sheetCard.addView(grabberWrap)
        sheetCard.addView(hdr)
        sheetCard.addView(scrollHost)
        addView(sheetCard)

        syncFromConfig()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        springRunning = false
        Choreographer.getInstance().removeFrameCallback(sheetSpringCallback)
        scrimAnim?.cancel()
        clockScrollView.abortScrolling()
        wallScrollView.abortScrolling()
    }

    fun applySafeInsets(bottomPx: Int) {
        safeBottomPx = bottomPx
        val maxSheetH = sheetHeightFor(Mode.WALLPAPER)
        morphSpanPx = (maxSheetH - sheetHeightFor(Mode.CLOCK)).toFloat().coerceAtLeast(0f)
        val lp = sheetCard.layoutParams as LayoutParams
        val targetBottom = safeBottomPx + (6f * dens).roundToInt()
        var layoutDirty = false
        if (lp.bottomMargin != targetBottom) {
            lp.bottomMargin = targetBottom
            layoutDirty = true
        }
        if (lp.height != maxSheetH) {
            lp.height = maxSheetH
            layoutDirty = true
        }
        if (layoutDirty) sheetCard.layoutParams = lp
        val targetClockPad = morphSpanPx.roundToInt() + (18f * dens).roundToInt()
        if (clockBody.paddingBottom != targetClockPad) {
            clockBody.setPadding(0, 0, 0, targetClockPad)
        }
        if (isOpen && !springRunning) {
            targetMorphTop = if (currentMode == Mode.CLOCK) morphSpanPx else 0f
            morphTopPx = targetMorphTop
            applySheetVisualState()
        }
    }

    fun currentSheetHeightPx(): Int = sheetHeightFor(currentMode)

    private fun sheetHeightFor(mode: Mode): Int {
        val hPx = resources.displayMetrics.heightPixels
        val minH = (if (mode == Mode.CLOCK) 295f else 345f) * dens
        val maxH = (if (mode == Mode.CLOCK) 395f else 520f) * dens
        val frac = if (mode == Mode.CLOCK) 0.44f else 0.55f
        return (hPx * frac).coerceIn(minH, maxOf(minH, min(maxH, hPx * 0.70f))).roundToInt()
    }

    private fun computeShellOrigin(view: View): Pair<Float, Float> {
        val rootShell = getRootShell()
        var ox = view.x
        var oy = view.y
        var p = view.parent as? View
        while (p != null && p !== rootShell) {
            ox += p.x - p.scrollX
            oy += p.y - p.scrollY
            p = p.parent as? View
        }
        return ox to oy
    }

    private fun invalidateVisibleGlass(v: View) {
        if (v.visibility != View.VISIBLE) return
        if (v is GlassSheetCard || v is GlassPillButton || v is GlassSectionCard ||
            v is GlassFontChip || v is IosWallActionTile || v is IosSegmentedControl ||
            v is IosCapsuleButton
        ) {
            if (v === sheetCard || v.getLocalVisibleRect(tmpVisRect)) {
                v.invalidate()
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                if (child.visibility == View.VISIBLE) invalidateVisibleGlass(child)
            }
        }
    }

    private fun applySheetVisualState() {
        val visH = max(120f, sheetHeightFor(currentMode).toFloat())
        sheetCard.topInsetPx = morphTopPx
        sheetCard.invalidateOutline()
        grabberWrap.translationY = morphTopPx
        hdr.translationY = morphTopPx
        scrollHost.translationY = morphTopPx

        sheetCard.pivotX = sheetCard.width * 0.5f
        sheetCard.pivotY = sheetCard.height.toFloat()
        sheetCard.translationY = sheetSlideY
        sheetCard.scaleX = sheetScale
        sheetCard.scaleY = sheetScale
        val slideFade = (1f - (sheetSlideY / (visH * 0.78f)).coerceIn(0f, 1f)).let { it * it }
        sheetCard.alpha = if (sheetClosing || sheetSlideY > 0.5f) slideFade else 1f
        invalidateVisibleGlass(sheetCard)
    }

    private fun startSheetSpring() {
        if (!springRunning) {
            springRunning = true
            springLastNs = 0L
            Choreographer.getInstance().postFrameCallback(sheetSpringCallback)
        }
    }

    // ─────────────── Liquid Glass Outer Sheet & Inset Cards ───────────────
    private inner class GlassSheetCard(context: Context) : LinearLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, 30f, 24f, 16f, 1.44f, 0.24f, innerShadow = 0.16f, useBlurredBackdrop = true
        )
        var topInsetPx: Float = 0f
        init {
            setWillNotDraw(false)
        }
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.y < topInsetPx) {
                return false
            }
            return super.dispatchTouchEvent(ev)
        }
        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            glassHelper.draw(this, canvas, Color.argb(198, 12, 14, 22), ox, oy, drawTopOffsetPx = topInsetPx)
        }
    }

    // ─────────────── Elastic Rubber-Band Vertical & Horizontal Scrollers ───────────────
    private inner class EditorRubberScrollView(context: Context) : ScrollView(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val vertSlop = min(touchSlop, 8f * dens)
        private val scroller = OverScroller(context)
        private val maxPullPx = 68f * dens
        private var activePtrId = MotionEvent.INVALID_POINTER_ID
        private var startRawX = 0f
        private var startRawY = 0f
        private var lastRawY = 0f
        private var dragging = false
        private var disallowFromChild = false
        private var vt: VelocityTracker? = null
        private var rawPull = 0f
        private var rubberY = 0f
        private var velRubber = 0f
        private var springReboundRunning = false
        private var reboundLastNs = 0L
        var onScrollOrRubber: (() -> Unit)? = null

        private val reboundCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!springReboundRunning) return
                val dt = if (reboundLastNs == 0L) 0.016f
                else ((frameTimeNanos - reboundLastNs) / 1_000_000_000f).coerceIn(0.004f, 0.032f)
                reboundLastNs = frameTimeNanos

                val omega = 21.5f
                val zeta = 0.78f
                val acc = -(omega * omega) * rubberY - (2f * zeta * omega) * velRubber
                velRubber += acc * dt
                val next = rubberY + velRubber * dt
                setRubberY(next)
                if (abs(rubberY) < 0.35f && abs(velRubber) < 4f) {
                    setRubberY(0f)
                    rawPull = 0f
                    velRubber = 0f
                    springReboundRunning = false
                } else {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        init {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            setOnScrollChangeListener { _, _, _, _, _ -> onScrollOrRubber?.invoke() }
        }

        private fun ptrRawX(ev: MotionEvent, idx: Int): Float = ev.getX(idx) + (ev.rawX - ev.x)
        private fun ptrRawY(ev: MotionEvent, idx: Int): Float = ev.getY(idx) + (ev.rawY - ev.y)

        private fun trackRaw(ev: MotionEvent) {
            val copy = MotionEvent.obtain(ev)
            copy.setLocation(ev.rawX, ev.rawY)
            vt?.addMovement(copy)
            copy.recycle()
        }

        private fun setRubberY(y: Float) {
            rubberY = y
            getChildAt(0)?.translationY = y
            onScrollOrRubber?.invoke()
        }

        private fun pullToRubber(raw: Float): Float {
            val a = abs(raw)
            val mapped = maxPullPx * (1f - 1f / (1f + a / (maxPullPx * 1.25f)))
            return sign(raw) * mapped
        }

        private fun startRubberSpring(initVel: Float = 0f) {
            velRubber = initVel
            if (abs(rubberY) < 0.4f && abs(velRubber) < 12f) {
                setRubberY(0f)
                rawPull = 0f
                velRubber = 0f
                springReboundRunning = false
                return
            }
            if (!springReboundRunning) {
                springReboundRunning = true
                reboundLastNs = 0L
                Choreographer.getInstance().postFrameCallback(reboundCallback)
            }
        }

        fun abortScrolling() {
            if (!scroller.isFinished) scroller.abortAnimation()
            springReboundRunning = false
            Choreographer.getInstance().removeFrameCallback(reboundCallback)
            if (rubberY != 0f || rawPull != 0f) {
                rawPull = 0f
                velRubber = 0f
                setRubberY(0f)
            }
        }

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            disallowFromChild = disallowIntercept
            if (disallowIntercept) {
                dragging = false
                activePtrId = MotionEvent.INVALID_POINTER_ID
                vt?.recycle()
                vt = null
            }
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    disallowFromChild = false
                    if (!scroller.isFinished) scroller.abortAnimation()
                    springReboundRunning = false
                    Choreographer.getInstance().removeFrameCallback(reboundCallback)
                    activePtrId = ev.getPointerId(0)
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    lastRawY = ev.rawY
                    dragging = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain()
                    trackRaw(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (disallowFromChild) return false
                    val idx = ev.findPointerIndex(activePtrId)
                    if (idx < 0) return false
                    trackRaw(ev)
                    val curX = ptrRawX(ev, idx)
                    val curY = ptrRawY(ev, idx)
                    val dx = curX - startRawX
                    val dy = curY - startRawY
                    if (abs(dy) > vertSlop && abs(dy) > abs(dx) * 1.25f) {
                        dragging = true
                        lastRawY = curY
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePtrId = MotionEvent.INVALID_POINTER_ID
                    dragging = false
                    disallowFromChild = false
                    vt?.recycle()
                    vt = null
                    if (abs(rubberY) > 0.5f) startRubberSpring()
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val child = getChildAt(0)
            val maxScroll = max(0, (child?.height ?: 0) - height)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!scroller.isFinished) scroller.abortAnimation()
                    springReboundRunning = false
                    Choreographer.getInstance().removeFrameCallback(reboundCallback)
                    activePtrId = ev.getPointerId(0)
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    lastRawY = ev.rawY
                    dragging = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain()
                    trackRaw(ev)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = ev.findPointerIndex(activePtrId)
                    if (idx < 0) return true
                    trackRaw(ev)
                    val curY = ptrRawY(ev, idx)
                    val dy = curY - lastRawY
                    lastRawY = curY
                    if (!dragging) {
                        if (abs(curY - startRawY) > vertSlop) dragging = true else return true
                    }
                    if (abs(rawPull) > 0.5f) {
                        if ((rawPull > 0f && dy < 0f) || (rawPull < 0f && dy > 0f)) {
                            rawPull += dy
                            if (sign(rawPull) != sign(rubberY)) {
                                val rem = -rawPull
                                rawPull = 0f
                                setRubberY(0f)
                                scrollBy(0, rem.roundToInt())
                            } else {
                                setRubberY(pullToRubber(rawPull))
                            }
                        } else {
                            rawPull += dy
                            setRubberY(pullToRubber(rawPull))
                        }
                        return true
                    }

                    val targetScroll = scrollY - dy.roundToInt()
                    when {
                        targetScroll < 0 -> {
                            val overflow = (-targetScroll).toFloat()
                            if (scrollY != 0) scrollTo(0, 0)
                            rawPull += overflow
                            setRubberY(pullToRubber(rawPull))
                        }
                        targetScroll > maxScroll -> {
                            val overflow = (targetScroll - maxScroll).toFloat()
                            if (scrollY != maxScroll) scrollTo(0, maxScroll)
                            rawPull -= overflow
                            setRubberY(pullToRubber(rawPull))
                        }
                        else -> scrollTo(0, targetScroll)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePtrId = MotionEvent.INVALID_POINTER_ID
                    trackRaw(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    dragging = false
                    if (abs(rawPull) > 0.5f || abs(rubberY) > 0.5f) {
                        startRubberSpring(vy * 0.24f)
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP && abs(vy) > 90f * dens && maxScroll > 0) {
                        val over = (54f * dens).roundToInt()
                        scroller.fling(0, scrollY, 0, (-vy).roundToInt(), 0, 0, -over, maxScroll + over)
                        postInvalidateOnAnimation()
                    }
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            abortScrolling()
            vt?.recycle()
            vt = null
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                val maxScroll = max(0, (getChildAt(0)?.height ?: 0) - height)
                val cy = scroller.currY
                when {
                    cy < 0 -> {
                        val currVel = scroller.currVelocity
                        scroller.abortAnimation()
                        if (scrollY != 0) scrollTo(0, 0)
                        startRubberSpring(min(currVel * 0.42f, 900f * dens))
                        return
                    }
                    cy > maxScroll -> {
                        val currVel = scroller.currVelocity
                        scroller.abortAnimation()
                        if (scrollY != maxScroll) scrollTo(0, maxScroll)
                        startRubberSpring(-min(currVel * 0.42f, 900f * dens))
                        return
                    }
                    else -> {
                        if (scrollY != cy) scrollTo(0, cy)
                    }
                }
                postInvalidateOnAnimation()
            }
        }
    }

    private inner class RubberHorizontalScrollView(context: Context) : HorizontalScrollView(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val horizSlop = min(touchSlop, 7f * dens)
        private val scroller = OverScroller(context)
        private val maxPullPx = 46f * dens
        private var activePtrId = MotionEvent.INVALID_POINTER_ID
        private var startRawX = 0f
        private var startRawY = 0f
        private var lastRawX = 0f
        private var dragging = false
        private var vt: VelocityTracker? = null
        private var rawPull = 0f
        private var rubberX = 0f
        private var velRubber = 0f
        private var springReboundRunning = false
        private var reboundLastNs = 0L
        var onScrollOrRubber: (() -> Unit)? = null

        private val reboundCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!springReboundRunning) return
                val dt = if (reboundLastNs == 0L) 0.016f
                else ((frameTimeNanos - reboundLastNs) / 1_000_000_000f).coerceIn(0.004f, 0.032f)
                reboundLastNs = frameTimeNanos

                val omega = 22.5f
                val zeta = 0.80f
                val acc = -(omega * omega) * rubberX - (2f * zeta * omega) * velRubber
                velRubber += acc * dt
                setRubberX(rubberX + velRubber * dt)
                if (abs(rubberX) < 0.35f && abs(velRubber) < 4f) {
                    setRubberX(0f)
                    rawPull = 0f
                    velRubber = 0f
                    springReboundRunning = false
                } else {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        init {
            layoutDirection = LAYOUT_DIRECTION_LTR
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnScrollChangeListener { _, _, _, _, _ -> onScrollOrRubber?.invoke() }
        }

        private fun ptrRawX(ev: MotionEvent, idx: Int): Float = ev.getX(idx) + (ev.rawX - ev.x)
        private fun ptrRawY(ev: MotionEvent, idx: Int): Float = ev.getY(idx) + (ev.rawY - ev.y)

        private fun trackRaw(ev: MotionEvent) {
            val copy = MotionEvent.obtain(ev)
            copy.setLocation(ev.rawX, ev.rawY)
            vt?.addMovement(copy)
            copy.recycle()
        }

        private fun setRubberX(x: Float) {
            rubberX = x
            getChildAt(0)?.translationX = x
            onScrollOrRubber?.invoke()
        }

        private fun pullToRubber(raw: Float): Float {
            val a = abs(raw)
            val mapped = maxPullPx * (1f - 1f / (1f + a / (maxPullPx * 1.25f)))
            return sign(raw) * mapped
        }

        private fun startRubberSpring(initVel: Float = 0f) {
            velRubber = initVel
            if (abs(rubberX) < 0.4f && abs(velRubber) < 12f) {
                setRubberX(0f)
                rawPull = 0f
                velRubber = 0f
                springReboundRunning = false
                return
            }
            if (!springReboundRunning) {
                springReboundRunning = true
                reboundLastNs = 0L
                Choreographer.getInstance().postFrameCallback(reboundCallback)
            }
        }

        fun smoothScrollToX(targetX: Int) {
            if (!scroller.isFinished) scroller.abortAnimation()
            val maxScroll = max(0, (getChildAt(0)?.width ?: 0) - width)
            val clamped = targetX.coerceIn(0, maxScroll)
            if (clamped != scrollX) {
                scroller.startScroll(scrollX, 0, clamped - scrollX, 0, 320)
                postInvalidateOnAnimation()
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!scroller.isFinished) scroller.abortAnimation()
                    springReboundRunning = false
                    Choreographer.getInstance().removeFrameCallback(reboundCallback)
                    activePtrId = ev.getPointerId(0)
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    lastRawX = ev.rawX
                    dragging = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain()
                    trackRaw(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = ev.findPointerIndex(activePtrId)
                    if (idx < 0) return false
                    trackRaw(ev)
                    val curX = ptrRawX(ev, idx)
                    val curY = ptrRawY(ev, idx)
                    val dx = curX - startRawX
                    val dy = curY - startRawY
                    if (abs(dx) > horizSlop && abs(dx) >= abs(dy) * 0.9f) {
                        dragging = true
                        lastRawX = curX
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePtrId = MotionEvent.INVALID_POINTER_ID
                    dragging = false
                    vt?.recycle()
                    vt = null
                    if (abs(rubberX) > 0.5f) startRubberSpring()
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val child = getChildAt(0)
            val maxScroll = max(0, (child?.width ?: 0) - width)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!scroller.isFinished) scroller.abortAnimation()
                    springReboundRunning = false
                    Choreographer.getInstance().removeFrameCallback(reboundCallback)
                    activePtrId = ev.getPointerId(0)
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    lastRawX = ev.rawX
                    dragging = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain()
                    trackRaw(ev)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = ev.findPointerIndex(activePtrId)
                    if (idx < 0) return true
                    trackRaw(ev)
                    val curX = ptrRawX(ev, idx)
                    val dx = curX - lastRawX
                    lastRawX = curX
                    if (!dragging) {
                        if (abs(curX - startRawX) > horizSlop) {
                            dragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        } else return true
                    }
                    if (abs(rawPull) > 0.5f) {
                        if ((rawPull > 0f && dx < 0f) || (rawPull < 0f && dx > 0f)) {
                            rawPull += dx
                            if (sign(rawPull) != sign(rubberX)) {
                                val rem = -rawPull
                                rawPull = 0f
                                setRubberX(0f)
                                scrollBy(rem.roundToInt(), 0)
                            } else {
                                setRubberX(pullToRubber(rawPull))
                            }
                        } else {
                            rawPull += dx
                            setRubberX(pullToRubber(rawPull))
                        }
                        return true
                    }

                    val targetScroll = scrollX - dx.roundToInt()
                    when {
                        targetScroll < 0 -> {
                            val overflow = (-targetScroll).toFloat()
                            if (scrollX != 0) scrollTo(0, 0)
                            rawPull += overflow
                            setRubberX(pullToRubber(rawPull))
                        }
                        targetScroll > maxScroll -> {
                            val overflow = (targetScroll - maxScroll).toFloat()
                            if (scrollX != maxScroll) scrollTo(0, maxScroll)
                            rawPull -= overflow
                            setRubberX(pullToRubber(rawPull))
                        }
                        else -> scrollTo(targetScroll, 0)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePtrId = MotionEvent.INVALID_POINTER_ID
                    trackRaw(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    dragging = false
                    if (abs(rawPull) > 0.5f || abs(rubberX) > 0.5f) {
                        startRubberSpring(vx * 0.24f)
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP && abs(vx) > 90f * dens && maxScroll > 0) {
                        val over = (44f * dens).roundToInt()
                        scroller.fling(scrollX, 0, (-vx).roundToInt(), 0, -over, maxScroll + over, 0, 0)
                        postInvalidateOnAnimation()
                    }
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            if (!scroller.isFinished) scroller.abortAnimation()
            springReboundRunning = false
            Choreographer.getInstance().removeFrameCallback(reboundCallback)
            vt?.recycle()
            vt = null
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                val maxScroll = max(0, (getChildAt(0)?.width ?: 0) - width)
                val cx = scroller.currX
                when {
                    cx < 0 -> {
                        val currVel = scroller.currVelocity
                        scroller.abortAnimation()
                        if (scrollX != 0) scrollTo(0, 0)
                        startRubberSpring(min(currVel * 0.42f, 800f * dens))
                        return
                    }
                    cx > maxScroll -> {
                        val currVel = scroller.currVelocity
                        scroller.abortAnimation()
                        if (scrollX != maxScroll) scrollTo(0, maxScroll)
                        startRubberSpring(-min(currVel * 0.42f, 800f * dens))
                        return
                    }
                    else -> {
                        if (scrollX != cx) scrollTo(cx, 0)
                    }
                }
                postInvalidateOnAnimation()
            }
        }
    }

    private inner class GlassSectionCard(
        context: Context,
        padHdp: Float = 12f,
        padVdp: Float = 10f
    ) : LinearLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, 20f, 14f, 9f, 1.38f, 0.14f, innerShadow = 0.11f, useBlurredBackdrop = true
        )
        init {
            orientation = VERTICAL
            setWillNotDraw(false)
            setPadding(
                (padHdp * dens).roundToInt(),
                (padVdp * dens).roundToInt(),
                (padHdp * dens).roundToInt(),
                (padVdp * dens).roundToInt()
            )
        }
        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            glassHelper.draw(this, canvas, Color.argb(172, 17, 20, 31), ox, oy)
        }
    }

    // Header pill ("تم", "الساعة"/"الخلفية") kept clean & never truncated
    @SuppressLint("ClickableViewAccessibility")
    private inner class GlassPillButton(
        context: Context,
        label: String,
        radiusDp: Float = 999f,
        private val isAccent: Boolean = false,
        minWidthDp: Float = 0f,
        private val onClick: () -> Unit
    ) : FrameLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, radiusDp, 12f, 10f, 1.44f, 0.22f, innerShadow = 0.18f, useBlurredBackdrop = true
        )
        val tv: TextView
        init {
            setWillNotDraw(false)
            isClickable = true
            if (minWidthDp > 0f) minimumWidth = (minWidthDp * dens).roundToInt()
            tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                maxLines = 1
                isSingleLine = true
                setTextColor(if (isAccent) 0xFFE0F2FE.toInt() else Color.argb(240, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setPadding((14f * dens).roundToInt(), (6f * dens).roundToInt(), (14f * dens).roundToInt(), (6f * dens).roundToInt())
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            }
            addView(tv)
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        animate().cancel()
                        animate().scaleX(0.92f).scaleY(0.92f).setDuration(80L).start()
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(260L)
                            .setInterpolator(OvershootInterpolator(1.7f)).start()
                        false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
                        false
                    }
                    else -> false
                }
            }
            setOnClickListener { onClick() }
        }
        fun setLabel(txt: String) {
            if (tv.text.toString() == txt) return
            tv.text = txt
            tv.requestLayout()
            requestLayout()
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            val bg = if (isAccent) Color.argb(166, 26, 86, 146) else Color.argb(148, 26, 30, 44)
            val sh = if (isAccent) 0.34f else 0.22f
            val inn = if (isAccent) 0.28f else 0.17f
            glassHelper.draw(this, canvas, bg, ox, oy, shadowOverride = sh, innerOverride = inn)
        }
    }

    // ─────────────── SF-Style Vector Glyph Helper ───────────────
    private fun drawIosGlyph(canvas: Canvas, iconId: String, cx: Float, cy: Float, sz: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, sz * 0.11f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val r = sz * 0.42f
        when (iconId) {
            "depth" -> {
                // Overlapping layered frames + subtle front subject curve
                val backRect = RectF(cx - r * 0.95f, cy - r * 0.95f, cx + r * 0.45f, cy + r * 0.45f)
                canvas.drawRoundRect(backRect, r * 0.28f, r * 0.28f, p)
                val frontRect = RectF(cx - r * 0.32f, cy - r * 0.32f, cx + r * 0.98f, cy + r * 0.98f)
                p.alpha = 215
                canvas.drawRoundRect(frontRect, r * 0.28f, r * 0.28f, p)
                canvas.drawCircle(cx + r * 0.33f, cy + r * 0.15f, r * 0.22f, fillP)
            }
            "gallery" -> {
                val frame = RectF(cx - r, cy - r * 0.82f, cx + r, cy + r * 0.82f)
                canvas.drawRoundRect(frame, r * 0.26f, r * 0.26f, p)
                canvas.drawCircle(cx - r * 0.38f, cy - r * 0.28f, r * 0.16f, fillP)
                val path = Path().apply {
                    moveTo(cx - r * 0.72f, cy + r * 0.52f)
                    lineTo(cx - r * 0.18f, cy + r * 0.04f)
                    lineTo(cx + r * 0.14f, cy + r * 0.34f)
                    lineTo(cx + r * 0.46f, cy - r * 0.02f)
                    lineTo(cx + r * 0.76f, cy + r * 0.52f)
                }
                canvas.drawPath(path, p)
            }
            "ai" -> {
                // 4-point iOS Intelligence sparkle + corner dot
                val star = Path().apply {
                    moveTo(cx, cy - r * 1.02f)
                    quadTo(cx + r * 0.12f, cy - r * 0.12f, cx + r * 1.02f, cy)
                    quadTo(cx + r * 0.12f, cy + r * 0.12f, cx, cy + r * 1.02f)
                    quadTo(cx - r * 0.12f, cy + r * 0.12f, cx - r * 1.02f, cy)
                    quadTo(cx - r * 0.12f, cy - r * 0.12f, cx, cy - r * 1.02f)
                    close()
                }
                canvas.drawPath(star, fillP)
                canvas.drawCircle(cx + r * 0.72f, cy - r * 0.68f, r * 0.15f, fillP)
            }
            "subject" -> {
                // Person silhouette over layered cutout card
                canvas.drawCircle(cx, cy - r * 0.38f, r * 0.32f, fillP)
                val body = RectF(cx - r * 0.68f, cy + r * 0.06f, cx + r * 0.68f, cy + r * 0.88f)
                canvas.drawArc(body, 180f, 180f, true, fillP)
                val card = RectF(cx - r * 0.95f, cy - r * 0.92f, cx + r * 0.95f, cy + r * 0.92f)
                p.alpha = 140
                canvas.drawRoundRect(card, r * 0.3f, r * 0.3f, p)
            }
            "weather" -> {
                canvas.drawCircle(cx - r * 0.25f, cy - r * 0.22f, r * 0.34f, p)
                for (a in 0 until 6) {
                    val ang = Math.toRadians(a * 60.0)
                    val sx = (cx - r * 0.25f) + cos(ang).toFloat() * (r * 0.52f)
                    val sy = (cy - r * 0.22f) + sin(ang).toFloat() * (r * 0.52f)
                    val ex = (cx - r * 0.25f) + cos(ang).toFloat() * (r * 0.72f)
                    val ey = (cy - r * 0.22f) + sin(ang).toFloat() * (r * 0.72f)
                    canvas.drawLine(sx, sy, ex, ey, p)
                }
                val cloud = RectF(cx - r * 0.18f, cy + r * 0.02f, cx + r * 0.88f, cy + r * 0.68f)
                canvas.drawRoundRect(cloud, r * 0.33f, r * 0.33f, fillP)
            }
            "music" -> {
                val beam = Path().apply {
                    moveTo(cx - r * 0.28f, cy + r * 0.52f)
                    lineTo(cx - r * 0.28f, cy - r * 0.62f)
                    lineTo(cx + r * 0.62f, cy - r * 0.82f)
                    lineTo(cx + r * 0.62f, cy + r * 0.34f)
                }
                canvas.drawPath(beam, p)
                canvas.drawCircle(cx - r * 0.48f, cy + r * 0.58f, r * 0.24f, fillP)
                canvas.drawCircle(cx + r * 0.42f, cy + r * 0.40f, r * 0.24f, fillP)
            }
            "notif" -> {
                val bell = Path().apply {
                    moveTo(cx - r * 0.68f, cy + r * 0.38f)
                    lineTo(cx - r * 0.52f, cy - r * 0.28f)
                    quadTo(cx, cy - r * 0.96f, cx + r * 0.52f, cy - r * 0.28f)
                    lineTo(cx + r * 0.68f, cy + r * 0.38f)
                    close()
                }
                canvas.drawPath(bell, fillP)
                canvas.drawCircle(cx, cy + r * 0.70f, r * 0.18f, fillP)
            }
            "dnd" -> {
                val moon = Path().apply {
                    addCircle(cx, cy, r * 0.78f, Path.Direction.CW)
                }
                val cut = Path().apply {
                    addCircle(cx + r * 0.42f, cy - r * 0.26f, r * 0.64f, Path.Direction.CW)
                }
                moon.op(cut, Path.Op.DIFFERENCE)
                canvas.drawPath(moon, fillP)
            }
            "lock" -> {
                val arch = RectF(cx - r * 0.42f, cy - r * 0.88f, cx + r * 0.42f, cy - r * 0.02f)
                canvas.drawArc(arch, 180f, 180f, false, p)
                val body = RectF(cx - r * 0.66f, cy - r * 0.24f, cx + r * 0.66f, cy + r * 0.82f)
                canvas.drawRoundRect(body, r * 0.26f, r * 0.26f, fillP)
            }
            "pin" -> {
                val off = r * 0.46f
                for (row in -1..1 step 2) {
                    for (col in -1..1 step 2) {
                        canvas.drawCircle(cx + col * off, cy + row * off, r * 0.20f, fillP)
                    }
                }
            }
            "test_notif" -> {
                val card = RectF(cx - r * 0.82f, cy - r * 0.58f, cx + r * 0.82f, cy + r * 0.58f)
                canvas.drawRoundRect(card, r * 0.30f, r * 0.30f, p)
                canvas.drawLine(cx - r * 0.32f, cy, cx + r * 0.32f, cy, p)
                canvas.drawLine(cx, cy - r * 0.32f, cx, cy + r * 0.32f, p)
            }
            "sys_notif" -> {
                for (i in -1..1) {
                    val y = cy + i * r * 0.54f
                    canvas.drawLine(cx - r * 0.76f, y, cx + r * 0.76f, y, p)
                    canvas.drawCircle(cx + (if (i == 0) 0.28f else -0.28f) * r, y, r * 0.16f, fillP)
                }
            }
            "audio" -> {
                val heights = floatArrayOf(0.36f, 0.78f, 0.96f, 0.62f, 0.38f)
                for (i in heights.indices) {
                    val x = cx + (i - 2) * r * 0.38f
                    val hh = r * heights[i]
                    canvas.drawLine(x, cy - hh, x, cy + hh, p)
                }
            }
            "art" -> {
                canvas.drawCircle(cx, cy, r * 0.82f, p)
                canvas.drawCircle(cx, cy, r * 0.24f, fillP)
            }
            "export" -> {
                canvas.drawLine(cx, cy - r * 0.78f, cx, cy + r * 0.28f, p)
                canvas.drawLine(cx - r * 0.38f, cy - r * 0.08f, cx, cy + r * 0.32f, p)
                canvas.drawLine(cx + r * 0.38f, cy - r * 0.08f, cx, cy + r * 0.32f, p)
                canvas.drawLine(cx - r * 0.68f, cy + r * 0.72f, cx + r * 0.68f, cy + r * 0.72f, p)
            }
            "share" -> {
                canvas.drawLine(cx, cy + r * 0.28f, cx, cy - r * 0.80f, p)
                canvas.drawLine(cx - r * 0.36f, cy - r * 0.42f, cx, cy - r * 0.82f, p)
                canvas.drawLine(cx + r * 0.36f, cy - r * 0.42f, cx, cy - r * 0.82f, p)
                val box = RectF(cx - r * 0.64f, cy - r * 0.14f, cx + r * 0.64f, cy + r * 0.82f)
                canvas.drawRoundRect(box, r * 0.22f, r * 0.22f, p)
            }
            "reset" -> {
                val arc = RectF(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f)
                canvas.drawArc(arc, -45f, 270f, false, p)
                canvas.drawLine(cx - r * 0.56f, cy - r * 0.52f, cx - r * 0.16f, cy - r * 0.56f, p)
                canvas.drawLine(cx - r * 0.56f, cy - r * 0.52f, cx - r * 0.56f, cy - r * 0.12f, p)
            }
        }
    }

    // ─────────────── iPhone Wallpaper Gallery Action Tile (matches thumb proportions) ───────────────
    @SuppressLint("ClickableViewAccessibility")
    private inner class IosWallActionTile(
        context: Context,
        private val iconId: String,
        label: String,
        private val accentBg: Boolean = false,
        private val onClick: () -> Unit
    ) : FrameLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, 13f, 12f, 9f, 1.42f, 0.20f, innerShadow = 0.16f, useBlurredBackdrop = true
        )
        private val iconCircle: View

        init {
            setWillNotDraw(false)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams((64f * dens).roundToInt(), (68f * dens).roundToInt()).apply {
                marginEnd = (8f * dens).roundToInt()
            }

            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                setPadding((4f * dens).roundToInt(), (7f * dens).roundToInt(), (4f * dens).roundToInt(), (6f * dens).roundToInt())
            }
            val iconColor = if (accentBg) 0xFF7DD3FC.toInt() else Color.WHITE
            val circleColor = if (accentBg) Color.argb(46, 56, 189, 248) else Color.argb(26, 255, 255, 255)
            iconCircle = object : View(context) {
                private val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = circleColor }
                override fun onDraw(canvas: Canvas) {
                    val cx = width * 0.5f
                    val cy = height * 0.5f
                    canvas.drawCircle(cx, cy, min(width, height) * 0.48f, bgP)
                    drawIosGlyph(canvas, iconId, cx, cy, min(width, height) * 0.56f, iconColor)
                }
            }.apply {
                val sz = (29f * dens).roundToInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { bottomMargin = (4f * dens).roundToInt() }
            }
            val tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (accentBg) 0xFFE0F2FE.toInt() else Color.argb(235, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setLineSpacing(0f, 0.92f)
            }
            col.addView(iconCircle)
            col.addView(tv)
            addView(col)

            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        animate().cancel()
                        animate().scaleX(0.92f).scaleY(0.92f).setDuration(85L).start()
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(260L)
                            .setInterpolator(OvershootInterpolator(1.7f)).start()
                        false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
                        false
                    }
                    else -> false
                }
            }
            setOnClickListener { onClick() }
        }

        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            val bg = if (accentBg) Color.argb(148, 22, 68, 118) else Color.argb(142, 24, 28, 42)
            val sh = if (accentBg) 0.28f else 0.18f
            val inn = if (accentBg) 0.22f else 0.15f
            glassHelper.draw(this, canvas, bg, ox, oy, shadowOverride = sh, innerOverride = inn)
        }
    }

    // ─────────────── Authentic iOS Sliding Glass Segmented Control (12h / 24h) ───────────────
    @SuppressLint("ClickableViewAccessibility")
    private inner class IosSegmentedControl(
        context: Context,
        leftLabel: String,
        rightLabel: String,
        private val onSelectIndex: (Int) -> Unit
    ) : FrameLayout(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * dens
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * dens
        }
        private val trackRect = RectF()
        private val thumbRect = RectF()
        private val labels = arrayOf(
            makeSegLabel(leftLabel, 0),
            makeSegLabel(rightLabel, 1)
        )
        private var selectedIdx = 0
        private var animFrac = 0f
        private var thumbAnim: ValueAnimator? = null

        init {
            setWillNotDraw(false)
            layoutDirection = LAYOUT_DIRECTION_LTR
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = LAYOUT_DIRECTION_LTR
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (38f * dens).roundToInt())
                setPadding((3f * dens).roundToInt(), (3f * dens).roundToInt(), (3f * dens).roundToInt(), (3f * dens).roundToInt())
            }
            labels.forEach { row.addView(it) }
            addView(row)
        }

        private fun makeSegLabel(txt: String, idx: Int): TextView {
            return TextView(context).apply {
                text = txt
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    if (selectedIdx != idx) {
                        setSelectedIndex(idx, animated = true)
                        onSelectIndex(idx)
                    }
                }
            }
        }

        fun setSelectedIndex(idx: Int, animated: Boolean = false) {
            val clamped = idx.coerceIn(0, 1)
            selectedIdx = clamped
            labels.forEachIndexed { i, tv ->
                tv.setTextColor(if (i == clamped) 0xFFFFFFFF.toInt() else Color.argb(175, 255, 255, 255))
            }
            val target = clamped.toFloat()
            if (!animated) {
                thumbAnim?.cancel()
                animFrac = target
                invalidate()
            } else {
                thumbAnim?.cancel()
                val start = animFrac
                thumbAnim = ValueAnimator.ofFloat(start, target).apply {
                    duration = 250L
                    interpolator = DecelerateInterpolator(2.1f)
                    addUpdateListener { a ->
                        animFrac = a.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 4f || h <= 4f) return
            val pad = 3f * dens
            trackRect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
            trackPaint.color = Color.argb(135, 11, 13, 21)
            canvas.drawRoundRect(trackRect, h * 0.5f, h * 0.5f, trackPaint)
            trackStrokePaint.color = Color.argb(34, 255, 255, 255)
            canvas.drawRoundRect(trackRect, h * 0.5f, h * 0.5f, trackStrokePaint)

            val segW = (w - pad * 2f) * 0.5f
            val tx = pad + segW * animFrac
            thumbRect.set(tx, pad, tx + segW, h - pad)
            val tr = (h - pad * 2f) * 0.5f
            thumbPaint.shader = android.graphics.LinearGradient(
                0f, thumbRect.top, 0f, thumbRect.bottom,
                Color.argb(190, 74, 122, 178),
                Color.argb(148, 34, 72, 124),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(thumbRect, tr, tr, thumbPaint)
            thumbPaint.shader = null
            thumbRimPaint.color = Color.argb(118, 198, 232, 255)
            canvas.drawRoundRect(thumbRect, tr, tr, thumbRimPaint)
        }
    }

    // ─────────────── Authentic Animated iOS Liquid Glass Switch ───────────────
    private inner class IosSwitchView(
        context: Context,
        private val onColor: Int = 0xFF30D158.toInt()
    ) : View(context) {
        private var checked = false
        private var frac = 0f
        private var anim: ValueAnimator? = null
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * dens
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(4f * dens, 0f, 1.5f * dens, Color.argb(72, 0, 0, 0))
        }
        private val trackRect = RectF()
        private val thumbRect = RectF()

        init {
            val w = (48f * dens).roundToInt()
            val h = (28f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(w, h)
        }

        fun setChecked(on: Boolean, animated: Boolean) {
            if (checked == on && anim == null) return
            checked = on
            val target = if (on) 1f else 0f
            if (!animated) {
                anim?.cancel()
                anim = null
                frac = target
                invalidate()
            } else {
                anim?.cancel()
                val start = frac
                anim = ValueAnimator.ofFloat(start, target).apply {
                    duration = 230L
                    interpolator = OvershootInterpolator(1.1f)
                    addUpdateListener { a ->
                        frac = (a.animatedValue as Float).coerceIn(-0.08f, 1.08f)
                        invalidate()
                    }
                    start()
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 4f || h <= 4f) return
            val t = frac.coerceIn(0f, 1f)
            val offCol = Color.argb(92, 118, 120, 134)
            trackPaint.color = blendColors(offCol, onColor, t)
            trackRect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
            val rad = h * 0.5f
            canvas.drawRoundRect(trackRect, rad, rad, trackPaint)
            rimPaint.color = Color.argb((34 + 34 * t).roundToInt(), 255, 255, 255)
            canvas.drawRoundRect(trackRect, rad, rad, rimPaint)

            // iOS 26 pill-elastic thumb widens gently mid-transition
            val pad = 2.6f * dens
            val baseThumb = h - pad * 2f
            val stretch = sin(t * Math.PI).toFloat() * (4.2f * dens)
            val thumbW = baseThumb + stretch
            val travel = w - pad * 2f - thumbW
            val left = pad + travel * frac.coerceIn(0f, 1f)
            thumbRect.set(left, pad, left + thumbW, h - pad)
            canvas.drawRoundRect(thumbRect, baseThumb * 0.5f, baseThumb * 0.5f, thumbPaint)
        }
    }

    private fun blendColors(c0: Int, c1: Int, t: Float): Int {
        val a = (Color.alpha(c0) + (Color.alpha(c1) - Color.alpha(c0)) * t).roundToInt().coerceIn(0, 255)
        val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * t).roundToInt().coerceIn(0, 255)
        val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * t).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * t).roundToInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    // ─────────────── iOS Settings Icon Badge + Toggle Row ───────────────
    private fun makeIconBadge(iconId: String, badgeTint: Int): View {
        return object : View(context) {
            private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(62, Color.red(badgeTint), Color.green(badgeTint), Color.blue(badgeTint))
            }
            private val rimP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.8f * dens
                color = Color.argb(128, Color.red(badgeTint), Color.green(badgeTint), Color.blue(badgeTint))
            }
            private val rect = RectF()
            override fun onDraw(canvas: Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
                val r = 8.5f * dens
                canvas.drawRoundRect(rect, r, r, fillP)
                canvas.drawRoundRect(rect, r, r, rimP)
                drawIosGlyph(canvas, iconId, w * 0.5f, h * 0.5f, min(w, h) * 0.62f, badgeTint)
            }
        }.apply {
            val sz = (28f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (10f * dens).roundToInt() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class IosToggleRow(
        context: Context,
        iconId: String,
        badgeTint: Int,
        label: String,
        switchColor: Int = 0xFF30D158.toInt(),
        private val onToggle: () -> Unit
    ) : LinearLayout(context) {
        private val switchView = IosSwitchView(context, switchColor)
        private var initDone = false
        private val pressBg = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 12f * dens
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = pressBg
            setPadding((4f * dens).roundToInt(), (7f * dens).roundToInt(), (4f * dens).roundToInt(), (7f * dens).roundToInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

            addView(makeIconBadge(iconId, badgeTint))
            val tv = TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(tv)
            addView(switchView)

            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressBg.setColor(Color.argb(26, 255, 255, 255))
                        animate().cancel()
                        animate().scaleX(0.985f).scaleY(0.985f).setDuration(80L).start()
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        pressBg.setColor(Color.TRANSPARENT)
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(200L).start()
                        false
                    }
                    else -> false
                }
            }
            setOnClickListener { onToggle() }
        }

        fun setChecked(on: Boolean) {
            switchView.setChecked(on, animated = initDone && isOpen)
            initDone = true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeIosActionRow(
        iconId: String,
        badgeTint: Int,
        label: String,
        onClick: () -> Unit
    ): LinearLayout {
        val pressBg = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 12f * dens
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = pressBg
            setPadding((4f * dens).roundToInt(), (7f * dens).roundToInt(), (4f * dens).roundToInt(), (7f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            addView(makeIconBadge(iconId, badgeTint))
            addView(TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Crisp RTL iOS chevron pointing left
            addView(object : View(context) {
                private val chevP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.7f * dens
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = Color.argb(115, 255, 255, 255)
                }
                override fun onDraw(canvas: Canvas) {
                    val cx = width * 0.5f
                    val cy = height * 0.5f
                    val s = 3.8f * dens
                    canvas.drawLine(cx + s * 0.5f, cy - s, cx - s * 0.5f, cy, chevP)
                    canvas.drawLine(cx - s * 0.5f, cy, cx + s * 0.5f, cy + s, chevP)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams((18f * dens).roundToInt(), (18f * dens).roundToInt())
            })

            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressBg.setColor(Color.argb(26, 255, 255, 255))
                        animate().cancel()
                        animate().scaleX(0.985f).scaleY(0.985f).setDuration(80L).start()
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        pressBg.setColor(Color.TRANSPARENT)
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(200L).start()
                        false
                    }
                    else -> false
                }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeHairlineDivider(indented: Boolean = true): View {
        return View(context).apply {
            setBackgroundColor(Color.argb(24, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, max(1, (0.6f * dens).roundToInt())).apply {
                if (indented) marginStart = (42f * dens).roundToInt()
                topMargin = (2f * dens).roundToInt()
                bottomMargin = (2f * dens).roundToInt()
            }
        }
    }

    // Sleek iPhone capsule action buttons (for Performance tools)
    @SuppressLint("ClickableViewAccessibility")
    private inner class IosCapsuleButton(
        context: Context,
        private val iconId: String,
        label: String,
        private val primary: Boolean = false,
        private val onClick: () -> Unit
    ) : FrameLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, 999f, 11f, 8f, 1.40f, 0.18f, innerShadow = 0.14f, useBlurredBackdrop = true
        )
        init {
            setWillNotDraw(false)
            isClickable = true
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (36f * dens).roundToInt())
                setPadding((8f * dens).roundToInt(), 0, (8f * dens).roundToInt(), 0)
            }
            val tint = if (primary) 0xFF7DD3FC.toInt() else Color.WHITE
            row.addView(object : View(context) {
                override fun onDraw(canvas: Canvas) {
                    drawIosGlyph(canvas, iconId, width * 0.5f, height * 0.5f, min(width, height) * 0.86f, tint)
                }
            }.apply {
                val sz = (14f * dens).roundToInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (5f * dens).roundToInt() }
            })
            row.addView(TextView(context).apply {
                text = label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (primary) 0xFFE0F2FE.toInt() else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
            addView(row)

            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        animate().cancel()
                        animate().scaleX(0.94f).scaleY(0.94f).setDuration(85L).start()
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(260L)
                            .setInterpolator(OvershootInterpolator(1.6f)).start()
                        false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
                        false
                    }
                    else -> false
                }
            }
            setOnClickListener { onClick() }
        }
        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            val bg = if (primary) Color.argb(154, 24, 76, 132) else Color.argb(136, 24, 28, 42)
            val sh = if (primary) 0.28f else 0.16f
            glassHelper.draw(this, canvas, bg, ox, oy, shadowOverride = sh, innerOverride = 0.14f)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class GlassFontChip(
        context: Context,
        font: ClockFont,
        private val onSelect: () -> Unit
    ) : FrameLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, 16f, 14f, 11f, 1.44f, 0.24f, innerShadow = 0.20f, useBlurredBackdrop = true
        )
        private val sampleTv: TextView
        private val nameTv: TextView
        var selectedState = false
            set(value) {
                if (field == value) return
                field = value
                applyFontVisuals()
            }

        init {
            setWillNotDraw(false)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams((72f * dens).roundToInt(), (66f * dens).roundToInt()).apply {
                marginEnd = (8f * dens).roundToInt()
            }
            val stack = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                setPadding((4f * dens).roundToInt(), (4f * dens).roundToInt(), (4f * dens).roundToInt(), (5f * dens).roundToInt())
            }
            sampleTv = TextView(context).apply {
                text = "09"
                gravity = Gravity.CENTER
                typeface = getFontTypeface(font.id)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            }
            nameTv = TextView(context).apply {
                text = font.label
                gravity = Gravity.CENTER
                maxLines = 1
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            stack.addView(sampleTv)
            stack.addView(nameTv)
            addView(stack)
            applyFontVisuals()

            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        animate().cancel()
                        animate().scaleX(0.92f).scaleY(0.92f).setDuration(85L).start()
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(280L)
                            .setInterpolator(OvershootInterpolator(1.8f)).start()
                        false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(200L).start()
                        false
                    }
                    else -> false
                }
            }
            setOnClickListener { onSelect() }
        }

        private fun applyFontVisuals() {
            sampleTv.setTextColor(if (selectedState) 0xFFE0F2FE.toInt() else Color.WHITE)
            nameTv.setTextColor(if (selectedState) 0xFFB3DFFF.toInt() else Color.argb(165, 255, 255, 255))
            foreground = if (selectedState) GradientDrawable().apply {
                cornerRadius = 16f * dens
                setStroke((1.8f * dens).roundToInt(), 0xFFB3DFFF.toInt())
            } else null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            val bg = if (selectedState) Color.argb(166, 32, 92, 152) else Color.argb(150, 24, 28, 42)
            val sh = if (selectedState) 0.34f else 0.22f
            val inn = if (selectedState) 0.28f else 0.16f
            glassHelper.draw(this, canvas, bg, ox, oy, shadowOverride = sh, innerOverride = inn)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeDismiss(v: View) {
        var y0 = 0f
        var dy = 0f
        var dragVt: VelocityTracker? = null
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    springRunning = false
                    Choreographer.getInstance().removeFrameCallback(sheetSpringCallback)
                    y0 = ev.rawY
                    dy = 0f
                    isDraggingSheet = false
                    dragVt?.recycle()
                    dragVt = VelocityTracker.obtain()
                    val c = MotionEvent.obtain(ev); c.setLocation(ev.rawX, ev.rawY); dragVt?.addMovement(c); c.recycle()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val c = MotionEvent.obtain(ev); c.setLocation(ev.rawX, ev.rawY); dragVt?.addMovement(c); c.recycle()
                    dy = max(0f, ev.rawY - y0)
                    if (dy > 6f * dens) {
                        isDraggingSheet = true
                        sheetSlideY = dy
                        applySheetVisualState()
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragVt?.computeCurrentVelocity(1000)
                    val vy = dragVt?.yVelocity ?: 0f
                    dragVt?.recycle()
                    dragVt = null
                    val wasDrag = isDraggingSheet
                    isDraggingSheet = false
                    if (wasDrag && (dy > 58f * dens || vy > 520f * dens)) {
                        velSlideY = max(vy * 0.65f, 360f * dens)
                        close()
                        true
                    } else if (wasDrag || sheetSlideY > 0.5f) {
                        sheetClosing = false
                        targetSlideY = 0f
                        targetScale = 1f
                        velSlideY = vy * 0.25f
                        startSheetSpring()
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    // ─────────────── Clock Customizer Sheet Content ───────────────
    private fun buildClockBody(body: LinearLayout) {
        // 1. iPhone Horizontal Font Strip
        body.addView(secHeader("الخط"))
        val fontRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        fontScroll = RubberHorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            onScrollOrRubber = { invalidateVisibleGlass(this@EditorSheetView.sheetCard) }
        }
        for (f in ClockFont.entries) {
            val chip = GlassFontChip(context, f) {
                cfg.font = f.id
                callbacks.onFontChanged(f.id)
                syncFromConfig()
            }
            fontBtns[f.id] = chip
            fontRow.addView(chip)
        }
        fontScroll.addView(fontRow)
        body.addView(fontScroll)

        // 2. Quick Color Swatch Strip
        body.addView(secHeader("اللون"))
        val colRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        val colScroll = RubberHorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        for (cId in AppConstants.CLOCK_COLORS) {
            val dot = View(context).apply {
                val s = (30f * dens).roundToInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (8f * dens).roundToInt() }
                setOnClickListener {
                    cfg.color = cId
                    syncProFromColor()
                    callbacks.onColorOrOpacityChanged()
                    syncFromConfig()
                }
            }
            colorSwatches[cId] = dot
            colRow.addView(dot)
        }
        colScroll.addView(colRow)
        body.addView(colScroll)

        // 3. Time Format (iOS Sliding Segmented Control)
        body.addView(secHeader("نظام الساعة"))
        fmtSegment = IosSegmentedControl(context, "12 ساعة", "24 ساعة") { idx ->
            cfg.hour24 = idx == 1
            cfg.leadingZero = idx == 1
            callbacks.onFormatToggled()
            syncFromConfig()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        body.addView(fmtSegment)

        // 4. Geometry Card (الحجم · الطول · العرض · الموضع)
        body.addView(secHeader("الأبعاد"))
        val dimCard = GlassSectionCard(context)

        val (sHdr, sValTv) = secHeaderWithValue("الحجم", "121%", compact = true)
        sizeVal = sValTv
        dimCard.addView(sHdr)
        sizeSeek = makeSeekBar(56, 420) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.size = v.toFloat()
            sizeVal.text = "${((cfg.size / 172f) * 100f).roundToInt()}%"
            callbacks.onGeometryPreview(true, false, false)
        }
        dimCard.addView(sizeSeek)

        val (stHdr, stValTv) = secHeaderWithValue("الطول", "168%", compact = true)
        stretchVal = stValTv
        dimCard.addView(stHdr)
        stretchSeek = makeSeekBar(100, 310) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.sy = v / 100f
            stretchVal.text = "$v%"
            callbacks.onGeometryPreview(false, true, false)
        }
        dimCard.addView(stretchSeek)

        val (wHdr, wValTv) = secHeaderWithValue("العرض", "100%", compact = true)
        widthVal = wValTv
        dimCard.addView(wHdr)
        widthSeek = makeSeekBar(60, 160) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.sx = v / 100f
            widthVal.text = "$v%"
            callbacks.onGeometryPreview(false, true, false)
        }
        dimCard.addView(widthSeek)

        val (yHdr, yValTv) = secHeaderWithValue("الموضع", "0px", compact = true)
        yVal = yValTv
        dimCard.addView(yHdr)
        ySeek = makeSeekBar(0, 100) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.clockY = (v - 20).toFloat()
            yVal.text = "${cfg.clockY.roundToInt()}px"
            callbacks.onGeometryPreview(false, false, true)
        }
        dimCard.addView(ySeek)
        body.addView(dimCard)

        // 5. Pro Color & Glass Opacity Card
        body.addView(secHeader("ألوان مخصصة والشفافية"))
        val proCard = GlassSectionCard(context)
        val proTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val proTitle = TextView(context).apply {
            text = "اللون المخصص"
            setTextColor(Color.argb(215, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        proHex = TextView(context).apply {
            text = "تلقائي"
            setTextColor(Color.argb(175, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            fontFeatureSettings = "tnum"
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8f * dens).roundToInt()
            }
        }
        proSwatch = View(context).apply {
            val s = (26f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { openHexColorDialog() }
        }
        proTop.addView(proTitle); proTop.addView(proHex); proTop.addView(proSwatch)
        proCard.addView(proTop)

        proCard.addView(miniSliderLabel("درجة اللون"))
        hueSeek = makeSeekBar(0, 360) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            proHue = v.toFloat()
            val rgb = AppConstants.hslToRgbInt(proHue, 86f, proTone)
            cfg.color = AppConstants.toHex(rgb)
            callbacks.onColorOrOpacityChanged()
            syncFromConfig()
        }.apply { layoutDirection = LAYOUT_DIRECTION_LTR }
        proCard.addView(hueSeek)

        proCard.addView(miniSliderLabel("السطوع"))
        toneSeek = makeSeekBar(0, 100) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            proTone = v.toFloat()
            val sat = if (proTone <= 2f || proTone >= 98f) 0f else 86f
            val rgb = AppConstants.hslToRgbInt(proHue, sat, proTone)
            cfg.color = AppConstants.toHex(rgb)
            callbacks.onColorOrOpacityChanged()
            syncFromConfig()
        }.apply { layoutDirection = LAYOUT_DIRECTION_LTR }
        proCard.addView(toneSeek)

        val (opHdr, opTv) = secHeaderWithValue("شفافية لون الساعة", "14%", compact = true)
        opacityVal = opTv
        proCard.addView(opHdr)
        opacitySeek = makeSeekBar(0, 100) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.clockOpacity = v
            opacityVal.text = "$v%"
            callbacks.onColorOrOpacityChanged()
            syncProUi()
        }.apply { layoutDirection = LAYOUT_DIRECTION_LTR }
        proCard.addView(opacitySeek)
        body.addView(proCard)

        // 6. Inner White Shadow Card
        body.addView(secHeader("الظل الداخلي"))
        val innerCard = GlassSectionCard(context)

        val (ioH, ioV) = secHeaderWithValue("الشفافية", "100%", compact = true)
        inOpVal = ioV
        innerCard.addView(ioH)
        inOpSeek = makeSeekBar(0, 100) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.innerOpacity = v
            inOpVal.text = "$v%"
            callbacks.onInnerShadowChanged()
        }
        innerCard.addView(inOpSeek)

        val (itH, itV) = secHeaderWithValue("السُمك", "0.3", compact = true)
        inThVal = itV
        innerCard.addView(itH)
        inThSeek = makeSeekBar(0, 80) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.innerThickness = v / 10f
            inThVal.text = "%.1f".format(cfg.innerThickness)
            callbacks.onInnerShadowChanged()
        }
        innerCard.addView(inThSeek)

        val (iaH, iaV) = secHeaderWithValue("اتجاه الظل", "59°", compact = true)
        inAngVal = iaV
        innerCard.addView(iaH)
        inAngSeek = makeSeekBar(0, 360) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.innerAngle = v.toFloat()
            inAngVal.text = "$v°"
            callbacks.onInnerShadowChanged()
        }
        innerCard.addView(inAngSeek)

        val (ifH, ifV) = secHeaderWithValue("نسبة التلاشي", "100%", compact = true)
        inFadeVal = ifV
        innerCard.addView(ifH)
        inFadeSeek = makeSeekBar(0, 100) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.innerFade = v
            inFadeVal.text = "$v%"
            callbacks.onInnerShadowChanged()
        }
        innerCard.addView(inFadeSeek)

        val inColScroll = RubberHorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8f * dens).roundToInt()
            }
        }
        val inColRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = LAYOUT_DIRECTION_LTR
        }
        for (cHex in AppConstants.INNER_COLORS) {
            val dot = View(context).apply {
                val s = (26f * dens).roundToInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (8f * dens).roundToInt() }
                setOnClickListener {
                    cfg.innerColor = cHex
                    callbacks.onInnerShadowChanged()
                    syncFromConfig()
                }
            }
            innerSwatches[cHex] = dot
            inColRow.addView(dot)
        }
        inColScroll.addView(inColRow)
        innerCard.addView(inColScroll)
        body.addView(innerCard)
    }

    // ─────────────── Wallpaper, Depth & Lock-Screen Sheet Content ───────────────
    private fun buildWallpaperBody(body: LinearLayout) {
        // 1. Wallpapers + Depth iOS Card
        body.addView(secHeader("الخلفية والعمق"))
        val wallCard = GlassSectionCard(context, padHdp = 12f, padVdp = 10f)
        val wallScroll = RubberHorizontalScrollView(context).apply {
            onScrollOrRubber = { invalidateVisibleGlass(this@EditorSheetView.sheetCard) }
        }
        val wallRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        for (pw in PresetWall.entries) {
            val box = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams((54f * dens).roundToInt(), (68f * dens).roundToInt()).apply {
                    marginEnd = (8f * dens).roundToInt()
                }
                val img = ImageView(context).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(WallpaperEngine.getThumb(pw.id, null))
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, v.width, v.height, 13f * dens)
                        }
                    }
                }
                addView(img)
                setOnClickListener {
                    cfg.wall = pw.id
                    callbacks.onWallSelected(pw.id)
                    syncFromConfig()
                }
            }
            wallThumbs[pw.id] = box
            wallRow.addView(box)
        }
        wallRow.addView(IosWallActionTile(context, "gallery", "صور جهازي") { callbacks.onPickCustomWall() })
        aiDepthBtn = IosWallActionTile(context, "ai", "عزل AI", accentBg = true) { callbacks.onExtractAiDepth() }
        wallRow.addView(aiDepthBtn)
        wallRow.addView(IosWallActionTile(context, "subject", "أمام الساعة") { callbacks.onPickManualSubject() })
        wallScroll.addView(wallRow)
        wallCard.addView(wallScroll)

        wallCard.addView(makeHairlineDivider(indented = false).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = (10f * dens).roundToInt()
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = (4f * dens).roundToInt()
        })

        togDepth = IosToggleRow(
            context,
            iconId = "depth",
            badgeTint = 0xFF38BDF8.toInt(),
            label = "تأثير العمق",
            switchColor = 0xFF0A84FF.toInt()
        ) {
            callbacks.onToggleDepth()
            syncFromConfig()
        }
        wallCard.addView(togDepth)

        aiDepthStatus = TextView(context).apply {
            text = ""
            visibility = View.GONE
            setTextColor(Color.argb(175, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding((4f * dens).roundToInt(), (4f * dens).roundToInt(), (4f * dens).roundToInt(), 0)
        }
        wallCard.addView(aiDepthStatus)
        body.addView(wallCard)

        // 2. Grouped iOS Toggle Switch Rows
        body.addView(secHeader("عناصر الشاشة"))
        val togglesCard = GlassSectionCard(context, padHdp = 12f, padVdp = 6f)
        togWeather = IosToggleRow(context, "weather", 0xFFFBBF24.toInt(), "الطقس") { callbacks.onToggleWeather(); syncFromConfig() }
        togMusic = IosToggleRow(context, "music", 0xFFFB7185.toInt(), "الموسيقى") { callbacks.onToggleMusic(); syncFromConfig() }
        togNotif = IosToggleRow(context, "notif", 0xFFEF4444.toInt(), "الإشعارات") { callbacks.onToggleNotifications(); syncFromConfig() }
        togDnd = IosToggleRow(context, "dnd", 0xFFA78BFA.toInt(), "عدم الإزعاج", switchColor = 0xFF8B5CF6.toInt()) { callbacks.onToggleDnd(); syncFromConfig() }
        togPasscode = IosToggleRow(context, "lock", 0xFF60A5FA.toInt(), "رمز القفل") { callbacks.onTogglePasscode(); syncFromConfig() }

        togglesCard.addView(togWeather)
        togglesCard.addView(makeHairlineDivider())
        togglesCard.addView(togMusic)
        togglesCard.addView(makeHairlineDivider())
        togglesCard.addView(togNotif)
        togglesCard.addView(makeHairlineDivider())
        togglesCard.addView(togDnd)
        togglesCard.addView(makeHairlineDivider())
        togglesCard.addView(togPasscode)
        body.addView(togglesCard)

        // 3. Grouped iOS Action Rows
        body.addView(secHeader("أدوات سريعة"))
        val toolsCard = GlassSectionCard(context, padHdp = 12f, padVdp = 6f)
        toolsCard.addView(makeIosActionRow("pin", 0xFF93C5FD.toInt(), "تغيير الرمز") { callbacks.onChangePasscodePin() })
        toolsCard.addView(makeHairlineDivider())
        toolsCard.addView(makeIosActionRow("test_notif", 0xFF38BDF8.toInt(), "إشعار تجريبي") { callbacks.onTestNotification() })
        toolsCard.addView(makeHairlineDivider())
        toolsCard.addView(makeIosActionRow("sys_notif", 0xFF94A3B8.toInt(), "إشعارات النظام") { callbacks.onOpenNotificationAccess() })
        toolsCard.addView(makeHairlineDivider())
        toolsCard.addView(makeIosActionRow("audio", 0xFFF472B6.toInt(), "ملف صوتي") { callbacks.onPickAudio() })
        toolsCard.addView(makeHairlineDivider())
        toolsCard.addView(makeIosActionRow("art", 0xFFC084FC.toInt(), "غلاف الألبوم") { callbacks.onPickAlbumArt() })
        body.addView(toolsCard)

        // 4. Performance Monitor Card
        body.addView(secHeader("الأداء"))
        val perfCard = GlassSectionCard(context)
        val perfTop = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        perfMeta = TextView(context).apply {
            text = "0s · AGSL"
            setTextColor(Color.argb(190, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        perfStatus = TextView(context).apply {
            text = ""
            setTextColor(0xFF93C5FD.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        perfTop.addView(perfMeta); perfTop.addView(perfStatus)
        perfCard.addView(perfTop)

        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8f * dens).roundToInt()
            }
        }
        val (b1, v1) = makePerfStatBox("FPS")
        val (b2, v2) = makePerfStatBox("هبوط")
        val (b3, v3) = makePerfStatBox("الحقول")
        val (b4, v4) = makePerfStatBox("RAM", isLast = true)
        perfFpsVal = v1; perfJankVal = v2; perfMemVal = v3; perfPssVal = v4
        grid.addView(b1); grid.addView(b2); grid.addView(b3); grid.addView(b4)
        perfCard.addView(grid)

        val actRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (9f * dens).roundToInt()
            }
        }
        actRow.addView(makePerfBtn("export", "تصدير", primary = true) { callbacks.onDownloadPerf() })
        actRow.addView(makePerfBtn("share", "مشاركة") { callbacks.onSharePerf() })
        actRow.addView(makePerfBtn("reset", "تصفير", weight = 0.85f, isLast = true) { callbacks.onResetPerf() })
        perfCard.addView(actRow)
        body.addView(perfCard)
    }

    private fun openHexColorDialog() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(if (cfg.color == "auto") "#ffffff" else cfg.color)
            setTextColor(Color.BLACK)
        }
        AlertDialog.Builder(context)
            .setTitle("كود اللون (Hex)")
            .setView(input)
            .setPositiveButton("تطبيق") { _, _ ->
                val raw = input.text.toString().trim()
                val fixed = if (raw.startsWith("#")) raw else "#$raw"
                try {
                    Color.parseColor(fixed)
                    cfg.color = fixed
                    syncProFromColor()
                    callbacks.onColorOrOpacityChanged()
                    syncFromConfig()
                } catch (_: Throwable) {}
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun setAiStatus(msg: String, busy: Boolean) {
        if (msg.isBlank()) {
            aiDepthStatus.text = ""
            aiDepthStatus.visibility = View.GONE
        } else {
            aiDepthStatus.text = msg
            aiDepthStatus.visibility = View.VISIBLE
        }
        aiDepthBtn.alpha = if (busy) 0.55f else 1f
    }

    fun setPerfStatus(msg: String) {
        perfStatus.text = msg
    }

    fun updatePerfUi(snap: PerfMonitor.SummarySnapshot) {
        if (!isOpen) return
        perfMeta.text = "${snap.uptimeSec}s · ${snap.engineLabel}"
        perfFpsVal.text = "${snap.avgFps}/${snap.currentFps}"
        perfJankVal.text = "${snap.dropped24}/${snap.jank50}"
        perfMemVal.text = "${snap.gpuCacheMb}MB"
        perfPssVal.text = "${snap.totalPssMb}MB"
    }

    private fun syncProFromColor() {
        if (cfg.color != "auto") {
            val hsl = AppConstants.rgbIntToHsl(AppConstants.parseHexColor(cfg.color, Color.WHITE))
            proHue = hsl[0]
            proTone = hsl[2]
        }
    }

    fun syncFromConfig() {
        suppressSeek = true
        for ((id, chip) in fontBtns) {
            chip.selectedState = cfg.font == id
        }
        sizeSeek.progress = cfg.size.roundToInt().coerceIn(56, 420)
        sizeVal.text = "${((cfg.size / 172f) * 100f).roundToInt()}%"
        val stPct = (cfg.sy * 100f).roundToInt().coerceIn(100, 310)
        stretchSeek.progress = stPct
        stretchVal.text = "$stPct%"
        val wPct = (cfg.sx * 100f).roundToInt().coerceIn(60, 160)
        widthSeek.progress = wPct
        widthVal.text = "$wPct%"
        ySeek.progress = (cfg.clockY.roundToInt() + 20).coerceIn(0, 100)
        yVal.text = "${cfg.clockY.roundToInt()}px"

        fmtSegment.setSelectedIndex(if (cfg.hour24) 1 else 0, animated = isOpen && currentMode == Mode.CLOCK)

        for ((id, v) in colorSwatches) {
            val active = cfg.color.equals(id, ignoreCase = true)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (id == "auto") {
                    setColor(0xFF475569.toInt())
                } else {
                    setColor(AppConstants.parseHexColor(id))
                }
                setStroke(
                    (if (active) 2.5f else 1f * dens).roundToInt(),
                    if (active) 0xFFB3DFFF.toInt() else Color.argb(80, 255, 255, 255)
                )
            }
        }
        syncProUi()

        for ((id, box) in wallThumbs) {
            val active = cfg.wall == id
            box.foreground = GradientDrawable().apply {
                cornerRadius = 13f * dens
                setStroke((if (active) 2.2f else 0.8f * dens).roundToInt(), if (active) 0xFFB3DFFF.toInt() else Color.argb(40, 255, 255, 255))
            }
        }

        inOpSeek.progress = cfg.innerOpacity
        inOpVal.text = "${cfg.innerOpacity}%"
        inThSeek.progress = (cfg.innerThickness * 10f).roundToInt().coerceIn(0, 80)
        inThVal.text = "%.1f".format(cfg.innerThickness)
        inAngSeek.progress = cfg.innerAngle.roundToInt().coerceIn(0, 360)
        inAngVal.text = "${cfg.innerAngle.roundToInt()}°"
        inFadeSeek.progress = cfg.innerFade.coerceIn(0, 100)
        inFadeVal.text = "${cfg.innerFade}%"

        for ((hex, v) in innerSwatches) {
            val active = cfg.innerColor.equals(hex, ignoreCase = true)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AppConstants.parseHexColor(hex))
                setStroke(
                    (if (active) 2.5f else 1f * dens).roundToInt(),
                    if (active) 0xFFB3DFFF.toInt() else Color.argb(80, 255, 255, 255)
                )
            }
        }

        togWeather.setChecked(cfg.weather)
        togMusic.setChecked(cfg.music)
        togNotif.setChecked(cfg.notifications)
        togDepth.setChecked(cfg.depth)
        togDnd.setChecked(cfg.dnd)
        togPasscode.setChecked(cfg.passcodeEnabled)
        suppressSeek = false
    }

    private fun syncProUi() {
        syncProFromColor()
        val isAuto = cfg.color == "auto"
        val col = if (isAuto) Color.WHITE else AppConstants.parseHexColor(cfg.color, Color.WHITE)
        proHex.text = if (isAuto) "تلقائي" else cfg.color.uppercase()
        proSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(col)
            setStroke((1.5f * dens).roundToInt(), Color.argb(140, 255, 255, 255))
        }
        hueSeek.progress = proHue.roundToInt().coerceIn(0, 360)
        toneSeek.progress = proTone.roundToInt().coerceIn(0, 100)
        opacitySeek.progress = cfg.clockOpacity
        opacityVal.text = "${cfg.clockOpacity}%"

        val barPad = (10f * dens).roundToInt()
        hueSeek.progressDrawable = android.graphics.drawable.InsetDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFFFF3B30.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(), 0xFF00C7BE.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D55.toInt(), 0xFFFF3B30.toInt())
            ).apply { cornerRadius = 99f },
            0, barPad, 0, barPad
        )

        val pureHue = AppConstants.hslToRgbInt(proHue, 86f, 52f)
        toneSeek.progressDrawable = android.graphics.drawable.InsetDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.BLACK, pureHue, Color.WHITE)
            ).apply { cornerRadius = 99f },
            0, barPad, 0, barPad
        )

        val opTarget = if (isAuto) Color.WHITE else col
        opacitySeek.progressDrawable = android.graphics.drawable.InsetDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(18, Color.red(opTarget), Color.green(opTarget), Color.blue(opTarget)), opTarget)
            ).apply { cornerRadius = 99f },
            0, barPad, 0, barPad
        )
    }

    private fun animateScrimTo(targetAlpha: Int, durationMs: Long = 240L) {
        scrimAnim?.cancel()
        val startAlpha = currentScrimAlpha
        if (startAlpha == targetAlpha) {
            setBackgroundColor(Color.argb(targetAlpha, 0, 0, 0))
            return
        }
        scrimAnim = ValueAnimator.ofInt(startAlpha, targetAlpha).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { a ->
                currentScrimAlpha = a.animatedValue as Int
                setBackgroundColor(Color.argb(currentScrimAlpha, 0, 0, 0))
            }
            start()
        }
    }

    private fun applyModeHeaderAndVisibility(mode: Mode) {
        currentMode = mode
        if (mode == Mode.CLOCK) {
            titleView.text = "تخصيص الساعة"
            switchModeBtn.setLabel("الخلفية")
            clockScrollView.visibility = VISIBLE
            clockScrollView.alpha = 1f
            clockScrollView.translationX = 0f
            wallScrollView.visibility = GONE
        } else {
            titleView.text = "تخصيص الخلفية"
            switchModeBtn.setLabel("الساعة")
            wallScrollView.visibility = VISIBLE
            wallScrollView.alpha = 1f
            wallScrollView.translationX = 0f
            clockScrollView.visibility = GONE
        }
    }

    fun open(mode: Mode = Mode.WALLPAPER) {
        if (isOpen && !sheetClosing) {
            if (currentMode != mode) switchMode(mode)
            return
        }
        isOpen = true
        sheetClosing = false
        clockScrollView.animate().cancel()
        wallScrollView.animate().cancel()
        clockScrollView.abortScrolling()
        wallScrollView.abortScrolling()
        applyModeHeaderAndVisibility(mode)

        val maxSheetH = sheetHeightFor(Mode.WALLPAPER)
        morphSpanPx = (maxSheetH - sheetHeightFor(Mode.CLOCK)).toFloat().coerceAtLeast(0f)
        val lp = sheetCard.layoutParams as LayoutParams
        if (lp.height != maxSheetH) {
            lp.height = maxSheetH
            lp.bottomMargin = safeBottomPx + (6f * dens).roundToInt()
            sheetCard.layoutParams = lp
        }

        clockScrollView.scrollTo(0, 0)
        wallScrollView.scrollTo(0, 0)
        syncFromConfig()
        updatePerfUi(perf.snapshot())
        if (mode == Mode.CLOCK) {
            fontScroll.post {
                val activeChip = fontBtns[cfg.font]
                if (activeChip != null) {
                    val maxX = max(0, (fontScroll.getChildAt(0)?.width ?: 0) - fontScroll.width)
                    val targetX = (activeChip.left - (fontScroll.width - activeChip.width) / 2).coerceIn(0, maxX)
                    fontScroll.smoothScrollToX(targetX)
                }
            }
        }

        val visH = sheetHeightFor(mode).toFloat()
        targetMorphTop = if (mode == Mode.CLOCK) morphSpanPx else 0f
        morphTopPx = targetMorphTop
        velMorphTop = 0f

        val wasMidClose = visibility == VISIBLE && sheetSlideY > 0f
        visibility = VISIBLE
        alpha = 1f
        sheetSlideY = if (wasMidClose) sheetSlideY else (visH * 0.44f + 22f * dens)
        targetSlideY = 0f
        velSlideY = if (wasMidClose) min(0f, velSlideY) else (-120f * dens)
        sheetScale = if (wasMidClose) sheetScale else 0.96f
        targetScale = 1f
        velScale = 0f
        applySheetVisualState()
        startSheetSpring()

        animateScrimTo(if (mode == Mode.CLOCK) 0 else 44, 230L)
        callbacks.onModeChanged(mode)
        callbacks.onHaptic("tick")
    }

    fun switchMode(mode: Mode) {
        if (!isOpen || sheetClosing) {
            open(mode)
            return
        }
        if (currentMode == mode) return
        val toClock = mode == Mode.CLOCK
        val outScroll = if (toClock) wallScrollView else clockScrollView
        val inScroll = if (toClock) clockScrollView else wallScrollView
        currentMode = mode
        callbacks.onModeChanged(mode)
        callbacks.onHaptic("tick")

        targetMorphTop = if (toClock) morphSpanPx else 0f
        startSheetSpring()
        animateScrimTo(if (toClock) 0 else 44, 210L)

        titleView.text = if (toClock) "تخصيص الساعة" else "تخصيص الخلفية"
        switchModeBtn.setLabel(if (toClock) "الخلفية" else "الساعة")
        switchModeBtn.animate().cancel()
        switchModeBtn.scaleX = 0.92f
        switchModeBtn.scaleY = 0.92f
        switchModeBtn.animate().scaleX(1f).scaleY(1f).setDuration(240L)
            .setInterpolator(OvershootInterpolator(1.6f)).start()

        val shift = 20f * dens * (if (toClock) -1f else 1f)
        outScroll.animate().cancel()
        inScroll.animate().cancel()
        outScroll.abortScrolling()
        inScroll.abortScrolling()

        outScroll.animate()
            .alpha(0f)
            .translationX(-shift)
            .setDuration(155L)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction {
                if (currentMode != mode) return@withEndAction
                outScroll.visibility = GONE
                outScroll.alpha = 1f
                outScroll.translationX = 0f
            }
            .start()

        inScroll.visibility = VISIBLE
        inScroll.alpha = 0f
        inScroll.translationX = shift
        inScroll.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(235L)
            .setInterpolator(crossSlideEase)
            .setUpdateListener { invalidateVisibleGlass(inScroll) }
            .start()

        if (toClock) {
            fontScroll.post {
                val activeChip = fontBtns[cfg.font]
                if (activeChip != null) {
                    val maxX = max(0, (fontScroll.getChildAt(0)?.width ?: 0) - fontScroll.width)
                    val targetX = (activeChip.left - (fontScroll.width - activeChip.width) / 2).coerceIn(0, maxX)
                    fontScroll.smoothScrollToX(targetX)
                }
            }
        }
    }

    fun close() {
        if (!isOpen && !sheetClosing) return
        isOpen = false
        sheetClosing = true
        animateScrimTo(0, 190L)
        val slideOut = max(160f, sheetHeightFor(currentMode).toFloat() * 0.84f + 36f * dens)
        targetSlideY = slideOut
        targetScale = 0.965f
        if (velSlideY < 160f * dens) velSlideY = 160f * dens
        startSheetSpring()
    }

    private fun secHeader(txt: String): TextView {
        return TextView(context).apply {
            text = txt
            setTextColor(Color.argb(170, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, (12f * dens).roundToInt(), 0, (6f * dens).roundToInt())
        }
    }

    private fun secHeaderWithValue(txt: String, initVal: String, compact: Boolean = false): Pair<LinearLayout, TextView> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ((if (compact) 7f else 11f) * dens).roundToInt(), 0, (3f * dens).roundToInt())
        }
        val l = TextView(context).apply {
            text = txt
            setTextColor(Color.argb(190, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val v = TextView(context).apply {
            text = initVal
            setTextColor(0xFFB3DFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            fontFeatureSettings = "tnum"
        }
        row.addView(l); row.addView(v)
        return row to v
    }

    private fun miniSliderLabel(txt: String): TextView {
        return TextView(context).apply {
            text = txt
            setTextColor(Color.argb(160, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, (8f * dens).roundToInt(), 0, (2f * dens).roundToInt())
        }
    }

    private fun makeSeekBar(minV: Int, maxV: Int, onChange: (Int, Boolean) -> Unit): SeekBar {
        return SeekBar(context).apply {
            if (android.os.Build.VERSION.SDK_INT >= 26) min = minV
            max = maxV
            minimumHeight = (30f * dens).roundToInt()
            val hPad = (10f * dens).roundToInt()
            val vPad = (5f * dens).roundToInt()
            setPadding(hPad, vPad, hPad, vPad)
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize((19f * dens).roundToInt(), (19f * dens).roundToInt())
                setColor(Color.WHITE)
                setStroke((2f * dens).roundToInt(), Color.argb(72, 0, 0, 0))
            }
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFB3DFFF.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(52, 255, 255, 255))
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val realVal = if (android.os.Build.VERSION.SDK_INT >= 26) p else max(minV, p)
                    onChange(realVal, fromUser)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (sb === sizeSeek || sb === stretchSeek || sb === widthSeek || sb === ySeek) {
                        callbacks.onGeometryCommit()
                    }
                }
            })
        }
    }

    private fun makePerfStatBox(label: String, isLast: Boolean = false): Pair<LinearLayout, TextView> {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((6f * dens).roundToInt(), (7f * dens).roundToInt(), (6f * dens).roundToInt(), (7f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                if (!isLast) marginEnd = (5f * dens).roundToInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.argb(18, 255, 255, 255))
                cornerRadius = 10f * dens
                setStroke((0.6f * dens).roundToInt(), Color.argb(26, 255, 255, 255))
            }
        }
        val k = TextView(context).apply {
            text = label
            setTextColor(Color.argb(150, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            maxLines = 1
        }
        val v = TextView(context).apply {
            text = "—"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            fontFeatureSettings = "tnum"
        }
        box.addView(k); box.addView(v)
        return box to v
    }

    private fun makePerfBtn(iconId: String, label: String, primary: Boolean = false, weight: Float = 1f, isLast: Boolean = false, onClick: () -> Unit): IosCapsuleButton {
        return IosCapsuleButton(context, iconId, label, primary = primary, onClick = onClick).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, weight).apply {
                if (!isLast) marginEnd = (6f * dens).roundToInt()
            }
        }
    }
}
