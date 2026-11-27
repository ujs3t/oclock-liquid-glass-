package com.oclock.liquidglass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class PasscodeOverlayView(
    context: Context,
    private val backdrop: LiquidGlassShaders.BackdropState,
    private val perf: PerfMonitor,
    private val getRootShell: () -> View?,
    private val getExpectedPin: () -> String,
    private val onUnlocked: () -> Unit,
    private val onNewPinSaved: (String) -> Unit,
    private val onProgressChanged: (Float) -> Unit,
    private val onClosed: () -> Unit,
    private val onHaptic: (String) -> Unit
) : FrameLayout(context) {

    enum class Mode { UNLOCK, SETUP_NEW, SETUP_CONFIRM }

    private val dens = resources.displayMetrics.density
    private val iosEase = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val wallMatrix = Matrix()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val contentCol: LinearLayout
    private val lenSwitchRow: LinearLayout
    private val len4Btn: TextView
    private val len6Btn: TextView
    private val titleLabel: TextView
    private val dotsView: PasscodeDotsView
    private val hintLabel: TextView
    private val keypadGrid: LinearLayout
    private val keypadButtons = ArrayList<GlassKeypadButton>(10)
    private val cancelBtn: TextView
    private val deleteBtn: TextView
    private val bottomBar: FrameLayout

    private var mode = Mode.UNLOCK
    private var targetLen = 6
    private var pendingNewPin = ""
    private val entered = StringBuilder()

    var progress = 0f
        private set
    val isOpen: Boolean
        get() = progress > 0.002f || anim?.isRunning == true

    private var anim: ValueAnimator? = null
    private var safeTopPx = 0
    private var safeBottomPx = 0
    private var uiInk = Color.WHITE
    private var isVerifying = false

    // Swipe-down to dismiss Passcode cleanly
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragStartRawY = 0f
    private var startDragProg = 1f
    private var dismissingDrag = false
    private var vt: VelocityTracker? = null

    init {
        setWillNotDraw(false)
        layoutDirection = LAYOUT_DIRECTION_LTR
        clipChildren = false
        clipToPadding = false
        visibility = GONE
        isClickable = true
        isFocusable = true

        contentCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // Optional length selector (shown only during SETUP_NEW mode)
        lenSwitchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = GONE
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (14f * dens).roundToInt()
            }
        }
        len4Btn = makeLenChip("4 أرقام") { switchSetupLength(4) }
        len6Btn = makeLenChip("6 أرقام") { switchSetupLength(6) }
        lenSwitchRow.addView(len4Btn)
        lenSwitchRow.addView(len6Btn)

        titleLabel = TextView(context).apply {
            text = "أدخل رمز الدخول"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setShadowLayer(6f * dens, 0f, 1.5f * dens, Color.argb(65, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        dotsView = PasscodeDotsView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (24f * dens).roundToInt()).apply {
                topMargin = (18f * dens).roundToInt()
            }
        }

        hintLabel = TextView(context).apply {
            text = "الرمز الافتراضي: 000000"
            setTextColor(Color.argb(165, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            setShadowLayer(4f * dens, 0f, 1f * dens, Color.argb(55, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10f * dens).roundToInt()
            }
        }

        keypadGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        val rowsSpec = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf(null, "0" to "", null)
        )

        for ((rIdx, row) in rowsSpec.withIndex()) {
            val rowLl = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (rIdx > 0) topMargin = (16f * dens).roundToInt()
                }
            }
            for ((cIdx, spec) in row.withIndex()) {
                if (spec == null) {
                    val spacer = View(context).apply {
                        val sz = (76f * dens).roundToInt()
                        layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                            if (cIdx > 0) marginStart = (22f * dens).roundToInt()
                        }
                    }
                    rowLl.addView(spacer)
                } else {
                    val (digit, sub) = spec
                    val keyBtn = GlassKeypadButton(
                        context = context,
                        digit = digit,
                        letters = sub,
                        backdrop = backdrop,
                        perf = perf,
                        getRootShell = getRootShell,
                        onPress = { onDigitTapped(digit) }
                    ).apply {
                        val sz = (76f * dens).roundToInt()
                        layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                            if (cIdx > 0) marginStart = (22f * dens).roundToInt()
                        }
                    }
                    keypadButtons.add(keyBtn)
                    rowLl.addView(keyBtn)
                }
            }
            keypadGrid.addView(rowLl)
        }

        bottomBar = FrameLayout(context).apply {
            layoutDirection = LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        cancelBtn = TextView(context).apply {
            text = "إلغاء"
            setTextColor(Color.argb(235, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(4f * dens, 0f, 1f * dens, Color.argb(55, 0, 0, 0))
            setPadding((14f * dens).roundToInt(), (10f * dens).roundToInt(), (14f * dens).roundToInt(), (10f * dens).roundToInt())
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL)
            setOnClickListener {
                onHaptic("tick")
                closeAnimated()
            }
        }
        deleteBtn = TextView(context).apply {
            text = "حذف"
            setTextColor(Color.argb(235, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(4f * dens, 0f, 1f * dens, Color.argb(55, 0, 0, 0))
            setPadding((14f * dens).roundToInt(), (10f * dens).roundToInt(), (14f * dens).roundToInt(), (10f * dens).roundToInt())
            alpha = 0f
            isEnabled = false
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
            setOnClickListener {
                onBackspaceTapped()
            }
        }
        bottomBar.addView(cancelBtn)
        bottomBar.addView(deleteBtn)

        val topFlex = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 0.95f)
        }
        val midFlex = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 0.72f)
        }
        val botFlex = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 0.65f)
        }

        contentCol.addView(topFlex)
        contentCol.addView(lenSwitchRow)
        contentCol.addView(titleLabel)
        contentCol.addView(dotsView)
        contentCol.addView(hintLabel)
        contentCol.addView(midFlex)
        contentCol.addView(keypadGrid)
        contentCol.addView(botFlex)
        contentCol.addView(bottomBar)
        addView(contentCol)
    }

    private fun makeLenChip(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding((14f * dens).roundToInt(), (6f * dens).roundToInt(), (14f * dens).roundToInt(), (6f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = (5f * dens).roundToInt()
                marginEnd = (5f * dens).roundToInt()
            }
            setOnClickListener {
                onHaptic("tick")
                onClick()
            }
        }
    }

    private fun styleLenChips() {
        fun applyChip(tv: TextView, active: Boolean) {
            tv.setTextColor(if (active) Color.BLACK else Color.argb(225, 255, 255, 255))
            tv.background = GradientDrawable().apply {
                setColor(if (active) Color.argb(235, 255, 255, 255) else Color.argb(42, 255, 255, 255))
                cornerRadius = 999f
                setStroke((0.9f * dens).roundToInt(), Color.argb(if (active) 255 else 75, 255, 255, 255))
            }
        }
        applyChip(len4Btn, targetLen == 4)
        applyChip(len6Btn, targetLen == 6)
    }

    private fun switchSetupLength(len: Int) {
        if (mode != Mode.SETUP_NEW || targetLen == len) return
        targetLen = len
        entered.clear()
        dotsView.configure(targetLen, 0, animateLast = false)
        updateDeleteVisibility()
        styleLenChips()
    }

    fun applySafeInsets(topPx: Int, bottomPx: Int) {
        safeTopPx = topPx
        safeBottomPx = bottomPx
        relayoutResponsive()
    }

    fun applyTheme(ink: Int) {
        uiInk = ink
        // Passcode always sits above softly darkened blurred wallpaper so crisp white typography pops best
        invalidateAllKeys()
    }

    private fun relayoutResponsive() {
        val availW = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        val availH = (height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()
        val usableH = max(360f * dens, availH - safeTopPx - safeBottomPx)
        val btnPx = min(78f * dens, min((availW - 92f * dens) / 3f, usableH * 0.125f)).coerceAtLeast(52f * dens)
        val colGap = (btnPx * 0.29f).roundToInt()
        val rowGap = (btnPx * 0.21f).roundToInt()
        val btnRound = btnPx.roundToInt()
        val btnDp = btnPx / dens

        for (r in 0 until keypadGrid.childCount) {
            val row = keypadGrid.getChildAt(r) as? LinearLayout ?: continue
            val rLp = row.layoutParams as LinearLayout.LayoutParams
            rLp.topMargin = if (r > 0) rowGap else 0
            row.layoutParams = rLp
            for (c in 0 until row.childCount) {
                val child = row.getChildAt(c)
                val lp = child.layoutParams as LinearLayout.LayoutParams
                lp.width = btnRound
                lp.height = btnRound
                lp.marginStart = if (c > 0) colGap else 0
                child.layoutParams = lp
                if (child is GlassKeypadButton) child.updateButtonSizeDp(btnDp)
            }
        }

        val gridW = btnRound * 3 + colGap * 2
        val bLp = bottomBar.layoutParams as LinearLayout.LayoutParams
        bLp.width = min(availW.roundToInt() - (28f * dens).roundToInt(), gridW + (16f * dens).roundToInt())
        bottomBar.layoutParams = bLp

        contentCol.setPadding(
            0,
            safeTopPx + (34f * dens).roundToInt(),
            0,
            safeBottomPx + (18f * dens).roundToInt()
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) post { relayoutResponsive() }
    }

    fun prepareUnlockSession() {
        mode = Mode.UNLOCK
        isVerifying = false
        val pin = getExpectedPin()
        targetLen = if (pin.length == 4) 4 else 6
        entered.clear()
        lenSwitchRow.visibility = GONE
        titleLabel.text = "أدخل رمز الدخول"
        hintLabel.visibility = if (pin == "000000") VISIBLE else GONE
        dotsView.configure(targetLen, 0, animateLast = false)
        updateDeleteVisibility(immediate = true)
    }

    fun openForSetup(initialLength: Int = 6) {
        anim?.cancel()
        mode = Mode.SETUP_NEW
        isVerifying = false
        targetLen = if (initialLength == 4) 4 else 6
        pendingNewPin = ""
        entered.clear()
        lenSwitchRow.visibility = VISIBLE
        styleLenChips()
        titleLabel.text = "أدخل رمز الدخول الجديد"
        hintLabel.visibility = GONE
        dotsView.configure(targetLen, 0, animateLast = false)
        updateDeleteVisibility(immediate = true)
        relayoutResponsive()
        openAnimated()
    }

    fun setInteractiveProgress(p: Float) {
        val clamped = p.coerceIn(0f, 1f)
        if (clamped > 0.001f && visibility != VISIBLE) {
            prepareUnlockSession()
            visibility = VISIBLE
            relayoutResponsive()
        }
        progress = clamped
        val ease = clamped * clamped * (3f - 2f * clamped)
        contentCol.translationY = (1f - ease) * 92f * dens
        val sc = 0.94f + 0.06f * ease
        contentCol.scaleX = sc
        contentCol.scaleY = sc
        contentCol.alpha = ((clamped - 0.06f) / 0.72f).coerceIn(0f, 1f)
        onProgressChanged(clamped)
        invalidate()
        invalidateAllKeys()
        if (clamped <= 0.001f && anim?.isRunning != true) {
            visibility = GONE
        }
    }

    fun settleInteractive(vyPxPerSec: Float) {
        val shouldOpen = when {
            vyPxPerSec < -180f * dens -> true
            vyPxPerSec > 210f * dens -> false
            else -> progress >= 0.24f
        }
        if (shouldOpen) openAnimated(vyPxPerSec) else closeAnimated(vyPxPerSec)
    }

    fun openAnimated(vyPxPerSec: Float = 0f) {
        anim?.cancel()
        if (visibility != VISIBLE) {
            if (mode == Mode.UNLOCK) prepareUnlockSession()
            visibility = VISIBLE
            relayoutResponsive()
        }
        val start = progress
        val dist = abs(1f - start)
        if (dist < 0.005f) {
            setInteractiveProgress(1f)
            return
        }
        val speedBoost = (abs(vyPxPerSec) / (3000f * dens)).coerceIn(0f, 0.35f)
        val dur = ((260f + 160f * dist) * (1f - speedBoost)).roundToInt().toLong().coerceIn(210L, 420L)
        anim = ValueAnimator.ofFloat(start, 1f).apply {
            duration = dur
            interpolator = iosEase
            addUpdateListener { a ->
                setInteractiveProgress((a.animatedValue as Float).coerceIn(0f, 1f))
            }
            start()
        }
    }

    fun closeAnimated(vyPxPerSec: Float = 0f, afterClose: (() -> Unit)? = null) {
        anim?.cancel()
        val start = progress
        if (start <= 0.005f) {
            mode = Mode.UNLOCK
            setInteractiveProgress(0f)
            visibility = GONE
            onClosed()
            afterClose?.invoke()
            return
        }
        val speedBoost = (abs(vyPxPerSec) / (3000f * dens)).coerceIn(0f, 0.35f)
        val dur = ((240f + 140f * start) * (1f - speedBoost)).roundToInt().toLong().coerceIn(190L, 380L)
        anim = ValueAnimator.ofFloat(start, 0f).apply {
            duration = dur
            interpolator = iosEase
            addUpdateListener { a ->
                setInteractiveProgress((a.animatedValue as Float).coerceIn(0f, 1f))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    entered.clear()
                    isVerifying = false
                    mode = Mode.UNLOCK
                    visibility = GONE
                    onClosed()
                    afterClose?.invoke()
                }
            })
            start()
        }
    }

    private fun onDigitTapped(digit: String) {
        if (isVerifying || progress < 0.85f || entered.length >= targetLen) return
        onHaptic("tick")
        entered.append(digit)
        dotsView.configure(targetLen, entered.length, animateLast = true)
        updateDeleteVisibility()

        if (entered.length == targetLen) {
            isVerifying = true
            val code = entered.toString()
            postDelayed({ handleCompleteEntry(code) }, 115L)
        }
    }

    private fun handleCompleteEntry(code: String) {
        when (mode) {
            Mode.UNLOCK -> {
                if (code == getExpectedPin()) {
                    onHaptic("pop")
                    anim?.cancel()
                    val start = progress
                    anim = ValueAnimator.ofFloat(start, 0f).apply {
                        duration = 280L
                        interpolator = iosEase
                        addUpdateListener { a ->
                            val p = (a.animatedValue as Float).coerceIn(0f, 1f)
                            progress = p
                            contentCol.alpha = p
                            val sc = 1f + (1f - p) * 0.08f
                            contentCol.scaleX = sc
                            contentCol.scaleY = sc
                            invalidate()
                            invalidateAllKeys()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                entered.clear()
                                isVerifying = false
                                contentCol.scaleX = 1f
                                contentCol.scaleY = 1f
                                contentCol.translationY = 0f
                                visibility = GONE
                                onUnlocked()
                            }
                        })
                        start()
                    }
                } else {
                    triggerWrongCodeShake()
                }
            }
            Mode.SETUP_NEW -> {
                pendingNewPin = code
                mode = Mode.SETUP_CONFIRM
                isVerifying = false
                entered.clear()
                lenSwitchRow.visibility = GONE
                titleLabel.text = "أعد إدخال رمز الدخول"
                dotsView.configure(targetLen, 0, animateLast = false)
                updateDeleteVisibility(immediate = true)
                titleLabel.animate().cancel()
                titleLabel.translationX = 22f * dens
                titleLabel.alpha = 0.25f
                titleLabel.animate().translationX(0f).alpha(1f).setDuration(260L).setInterpolator(iosEase).start()
            }
            Mode.SETUP_CONFIRM -> {
                if (code == pendingNewPin) {
                    onHaptic("pop")
                    onNewPinSaved(code)
                    closeAnimated()
                } else {
                    triggerWrongCodeShake {
                        mode = Mode.SETUP_NEW
                        pendingNewPin = ""
                        lenSwitchRow.visibility = VISIBLE
                        styleLenChips()
                        titleLabel.text = "الرمزان غير متطابقين • حاول مجدداً"
                    }
                }
            }
        }
    }

    private fun triggerWrongCodeShake(onFinished: (() -> Unit)? = null) {
        onHaptic("heavy")
        dotsView.playShake {
            entered.clear()
            isVerifying = false
            dotsView.configure(targetLen, 0, animateLast = false)
            updateDeleteVisibility()
            onFinished?.invoke()
        }
    }

    private fun onBackspaceTapped() {
        if (isVerifying || entered.isEmpty()) return
        onHaptic("tick")
        entered.deleteCharAt(entered.length - 1)
        dotsView.configure(targetLen, entered.length, animateLast = false)
        updateDeleteVisibility()
    }

    private fun updateDeleteVisibility(immediate: Boolean = false) {
        val show = entered.isNotEmpty()
        deleteBtn.isEnabled = show
        val targetA = if (show) 1f else 0f
        if (immediate) {
            deleteBtn.animate().cancel()
            deleteBtn.alpha = targetA
        } else {
            deleteBtn.animate().alpha(targetA).setDuration(150L).start()
        }
    }

    fun invalidateAllKeys() {
        for (btn in keypadButtons) btn.invalidate()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (progress < 0.85f) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                downRawX = ev.rawX
                downRawY = ev.rawY
                dragStartRawY = ev.rawY
                startDragProg = progress
                dismissingDrag = false
                vt?.recycle()
                vt = VelocityTracker.obtain().apply { addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (dy > touchSlop * 1.4f && abs(dy) > abs(dx) * 1.25f) {
                    dismissingDrag = true
                    dragStartRawY = ev.rawY
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.recycle()
                vt = null
                dismissingDrag = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                downRawX = ev.rawX
                downRawY = ev.rawY
                dragStartRawY = ev.rawY
                startDragProg = progress
                dismissingDrag = false
                vt?.recycle()
                vt = VelocityTracker.obtain().apply { addMovement(ev) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dismissingDrag && dy > touchSlop * 1.3f && abs(dy) > abs(dx) * 1.2f) {
                    dismissingDrag = true
                    dragStartRawY = ev.rawY
                }
                if (dismissingDrag) {
                    val travel = max(240f * dens, height * 0.42f)
                    val dragDy = max(0f, ev.rawY - dragStartRawY)
                    val p = (startDragProg - dragDy / travel).coerceIn(0f, 1f)
                    setInteractiveProgress(p)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(ev)
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle()
                vt = null
                if (dismissingDrag) {
                    dismissingDrag = false
                    val shouldDismiss = ev.actionMasked == MotionEvent.ACTION_UP &&
                        (vy > 190f * dens || progress < 0.72f)
                    if (shouldDismiss) closeAnimated(vy) else openAnimated(vy)
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0.002f || width <= 0 || height <= 0) return
        val ease = progress * progress * (3f - 2f * progress)
        val blurBmp = backdrop.blurredBitmap ?: backdrop.wallBitmap
        if (blurBmp != null) {
            val bw = blurBmp.width.toFloat().coerceAtLeast(1f)
            val bh = blurBmp.height.toFloat().coerceAtLeast(1f)
            val sc = max(width / bw, height / bh)
            wallMatrix.reset()
            wallMatrix.setScale(sc, sc)
            wallMatrix.postTranslate((width - bw * sc) * 0.5f, (height - bh * sc) * 0.5f)
            bgPaint.alpha = (ease * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(blurBmp, wallMatrix, bgPaint)
            bgPaint.alpha = 255
        }
        // Soft adaptive darkened veil so glass circles & typography read effortlessly on bright wallpapers
        val scrimA = ((if (backdrop.blurredBitmap != null) 58f else 118f) * ease).roundToInt().coerceIn(0, 220)
        canvas.drawColor(Color.argb(scrimA, 8, 10, 16))
    }

    private class PasscodeDotsView(context: Context) : View(context) {
        private val dens = resources.displayMetrics.density
        private var count = 6
        private var filledCount = 0
        private val dotRadius = 6.0f * dens
        private val dotGap = 21.0f * dens
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.65f * dens
            color = Color.WHITE
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val dotScales = FloatArray(6) { 1f }
        private var shakeOffsetX = 0f
        private var shakeAnim: ValueAnimator? = null
        private var popAnim: ValueAnimator? = null

        fun configure(total: Int, filled: Int, animateLast: Boolean) {
            val prevFilled = filledCount
            count = total.coerceIn(4, 6)
            filledCount = filled.coerceIn(0, count)
            requestLayout()
            if (animateLast && filledCount > prevFilled && filledCount in 1..count) {
                val idx = filledCount - 1
                popAnim?.cancel()
                popAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 260L
                    interpolator = OvershootInterpolator(2.4f)
                    addUpdateListener { a ->
                        val t = a.animatedValue as Float
                        dotScales[idx] = 0.72f + 0.28f * t
                        invalidate()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            dotScales[idx] = 1f
                            invalidate()
                        }
                    })
                    start()
                }
            } else {
                for (i in dotScales.indices) dotScales[i] = 1f
                invalidate()
            }
        }

        fun playShake(onDone: () -> Unit) {
            shakeAnim?.cancel()
            shakeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 460L
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    val env = exp(-4.8f * t)
                    shakeOffsetX = (sin(t * PI * 7.0) * env * 18.0 * dens).toFloat()
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        shakeOffsetX = 0f
                        invalidate()
                        onDone()
                    }
                })
                start()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val totalW = ((count - 1) * (dotRadius * 2f + dotGap) + dotRadius * 2f + 42f * dens).roundToInt()
            val totalH = (24f * dens).roundToInt()
            setMeasuredDimension(totalW, totalH)
        }

        override fun onDraw(canvas: Canvas) {
            val step = dotRadius * 2f + dotGap
            val spanW = (count - 1) * step
            val startX = (width - spanW) * 0.5f + shakeOffsetX
            val cy = height * 0.5f
            for (i in 0 until count) {
                val cx = startX + i * step
                val r = dotRadius * dotScales[i]
                if (i < filledCount) {
                    canvas.drawCircle(cx, cy, r, fillPaint)
                } else {
                    canvas.drawCircle(cx, cy, dotRadius - strokePaint.strokeWidth * 0.35f, strokePaint)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private class GlassKeypadButton(
        context: Context,
        private val digit: String,
        private val letters: String,
        backdrop: LiquidGlassShaders.BackdropState,
        perf: PerfMonitor,
        private val getRootShell: () -> View?,
        private val onPress: () -> Unit
    ) : View(context) {

        private val dens = resources.displayMetrics.density
        private var btnDp = 76f
        private var glassHelper = LiquidGlassCardHelper(
            backdrop = backdrop,
            perf = perf,
            radiusDp = 38f,
            bezelDp = 26f,
            thickDp = 22f,
            ior = 1.50f,
            shadowInt = 0.50f,
            innerShadow = 0.44f,
            useBlurredBackdrop = true
        )
        private val helperBackdrop = backdrop
        private val helperPerf = perf

        private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 33f * dens
            fontFeatureSettings = "tnum"
            setShadowLayer(2.5f * dens, 0f, 0.8f * dens, Color.argb(46, 0, 0, 0))
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(236, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 9.2f * dens
            letterSpacing = 0.16f
            setShadowLayer(2f * dens, 0f, 0.6f * dens, Color.argb(42, 0, 0, 0))
        }

        private var pressFrac = 0f
        private var pressAnim: ValueAnimator? = null
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        init {
            setWillNotDraw(false)
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        animatePress(1f, 75L)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop) {
                            animatePress(0f, 220L)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val isTap = abs(ev.rawX - downX) <= slop && abs(ev.rawY - downY) <= slop
                        animatePress(0f, 300L, bouncy = true)
                        if (isTap) onPress()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        animatePress(0f, 220L)
                        true
                    }
                    else -> false
                }
            }
        }

        fun updateButtonSizeDp(newDp: Float) {
            if (abs(newDp - btnDp) < 0.5f) return
            btnDp = newDp
            val ratio = (btnDp / 76f).coerceIn(0.68f, 1.12f)
            digitPaint.textSize = 33f * ratio * dens
            subPaint.textSize = 9.2f * ratio * dens
            glassHelper = LiquidGlassCardHelper(
                backdrop = helperBackdrop,
                perf = helperPerf,
                radiusDp = btnDp * 0.5f,
                bezelDp = 26f * ratio,
                thickDp = 22f * ratio,
                ior = 1.50f,
                shadowInt = 0.50f,
                innerShadow = 0.44f,
                useBlurredBackdrop = true
            )
            invalidate()
        }

        private fun animatePress(target: Float, dur: Long, bouncy: Boolean = false) {
            pressAnim?.cancel()
            val start = pressFrac
            pressAnim = ValueAnimator.ofFloat(start, target).apply {
                duration = dur
                if (bouncy) interpolator = OvershootInterpolator(1.6f)
                addUpdateListener { a ->
                    pressFrac = (a.animatedValue as Float).coerceIn(0f, 1f)
                    val sc = 1f - 0.055f * pressFrac
                    scaleX = sc
                    scaleY = sc
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return

            val rootShell = getRootShell()
            var ox = x
            var oy = y
            var p = parent as? View
            while (p != null && p !== rootShell) {
                ox += p.x
                oy += p.y
                p = p.parent as? View
            }

            // Glass circle lens + press flare
            val tintA = (32f + pressFrac * 82f).roundToInt().coerceIn(0, 200)
            val glassTint = Color.argb(tintA, 245, 247, 252)
            glassHelper.draw(
                view = this,
                canvas = canvas,
                glassBgColor = glassTint,
                originX = ox,
                originY = oy,
                shadowOverride = 0.50f + pressFrac * 0.28f,
                innerOverride = 0.44f + pressFrac * 0.34f
            )

            val cx = w * 0.5f
            val cy = h * 0.5f
            val ratio = (btnDp / 76f).coerceIn(0.68f, 1.12f)
            val dFm = digitPaint.fontMetrics
            val dHalfHeight = (dFm.ascent + dFm.descent) * 0.5f

            if (digit == "0") {
                // Zero sits vertically centered cleanly inside its circle (matches reference photo)
                canvas.drawText(digit, cx, cy - dHalfHeight, digitPaint)
            } else {
                // 1..9 share identical upper digit baseline; 2..9 show tracked uppercase sublabel below
                val digitCenterY = cy - 6.8f * ratio * dens
                canvas.drawText(digit, cx, digitCenterY - dHalfHeight, digitPaint)
                if (letters.isNotEmpty()) {
                    val sFm = subPaint.fontMetrics
                    val sHalfHeight = (sFm.ascent + sFm.descent) * 0.5f
                    val subCenterY = cy + 16.5f * ratio * dens
                    canvas.drawText(letters, cx, subCenterY - sHalfHeight, subPaint)
                }
            }
        }
    }
}
