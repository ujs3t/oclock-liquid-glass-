package com.oclock.liquidglass

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    private val titleView: TextView
    private val switchModeBtn: GlassPillButton
    private val doneBtn: GlassPillButton
    private val scrollView: ScrollView
    private val clockBody: LinearLayout
    private val wallBody: LinearLayout

    // Clock customizer controls
    private lateinit var fontScroll: HorizontalScrollView
    private val fontBtns = HashMap<String, GlassFontChip>()
    private lateinit var sizeSeek: SeekBar
    private lateinit var sizeVal: TextView
    private lateinit var stretchSeek: SeekBar
    private lateinit var stretchVal: TextView
    private lateinit var widthSeek: SeekBar
    private lateinit var widthVal: TextView
    private lateinit var ySeek: SeekBar
    private lateinit var yVal: TextView
    private lateinit var fmt12Btn: GlassPillButton
    private lateinit var fmt24Btn: GlassPillButton

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
    private lateinit var aiDepthBtn: GlassPillButton
    private lateinit var aiDepthStatus: TextView

    private lateinit var togWeather: GlassPillButton
    private lateinit var togMusic: GlassPillButton
    private lateinit var togNotif: GlassPillButton
    private lateinit var togDepth: GlassPillButton
    private lateinit var togDnd: GlassPillButton
    private lateinit var togPasscode: GlassPillButton

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
    private var heightAnim: ValueAnimator? = null
    private var scrimAnim: ValueAnimator? = null
    private var currentScrimAlpha = 0

    init {
        visibility = GONE
        layoutDirection = LAYOUT_DIRECTION_RTL
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { close() }

        sheetCard = GlassSheetCard(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            layoutDirection = LAYOUT_DIRECTION_RTL
            val padH = (14f * dens).roundToInt()
            setPadding(padH, (6f * dens).roundToInt(), padH, (12f * dens).roundToInt())
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, 30f * dens)
                }
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, sheetHeightFor(Mode.WALLPAPER), Gravity.BOTTOM).apply {
                val side = (8f * dens).roundToInt()
                leftMargin = side
                rightMargin = side
                bottomMargin = safeBottomPx + (6f * dens).roundToInt()
            }
        }

        // Grabber + swipe down dismiss
        val grabberWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (18f * dens).roundToInt())
        }
        val grabber = View(context).apply {
            layoutParams = LayoutParams((36f * dens).roundToInt(), (4f * dens).roundToInt(), Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(Color.argb(96, 255, 255, 255))
                cornerRadius = 99f
            }
        }
        grabberWrap.addView(grabber)
        attachSwipeDismiss(grabberWrap)

        // Sticky Liquid Glass Header
        val hdr = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8f * dens).roundToInt()
            }
        }
        titleView = TextView(context).apply {
            text = "تخصيص الخلفية"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        switchModeBtn = GlassPillButton(context, "الساعة", radiusDp = 999f, isAccent = false) {
            val next = if (currentMode == Mode.CLOCK) Mode.WALLPAPER else Mode.CLOCK
            switchMode(next)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (32f * dens).roundToInt()).apply {
                marginEnd = (6f * dens).roundToInt()
            }
        }
        doneBtn = GlassPillButton(context, "تم", radiusDp = 999f, isAccent = true) {
            close()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (32f * dens).roundToInt())
        }
        hdr.addView(titleView)
        hdr.addView(switchModeBtn)
        hdr.addView(doneBtn)
        attachSwipeDismiss(hdr)

        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
        }
        val bodyHost = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        clockBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (12f * dens).roundToInt())
            visibility = GONE
        }
        wallBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (12f * dens).roundToInt())
            visibility = VISIBLE
        }

        buildClockBody(clockBody)
        buildWallpaperBody(wallBody)

        bodyHost.addView(clockBody)
        bodyHost.addView(wallBody)
        scrollView.addView(bodyHost)
        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            invalidateGlassTree(sheetCard)
        }

        sheetCard.addView(grabberWrap)
        sheetCard.addView(hdr)
        sheetCard.addView(scrollView)
        addView(sheetCard)

        syncFromConfig()
    }

    fun applySafeInsets(bottomPx: Int) {
        safeBottomPx = bottomPx
        val lp = sheetCard.layoutParams as LayoutParams
        val targetBottom = safeBottomPx + (6f * dens).roundToInt()
        if (lp.bottomMargin != targetBottom) {
            lp.bottomMargin = targetBottom
            sheetCard.layoutParams = lp
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

    private fun invalidateGlassTree(v: View) {
        if (v is GlassPillButton || v is GlassFontChip || v is GlassSectionCard || v is GlassSheetCard) {
            v.invalidate()
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                invalidateGlassTree(v.getChildAt(i))
            }
        }
    }

    private inner class GlassSheetCard(context: Context) : LinearLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, 30f, 24f, 16f, 1.44f, 0.24f, innerShadow = 0.16f, useBlurredBackdrop = true
        )
        init {
            setWillNotDraw(false)
        }
        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            glassHelper.draw(this, canvas, Color.argb(198, 12, 14, 22), ox, oy)
        }
    }

    private inner class GlassSectionCard(context: Context) : LinearLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, 18f, 14f, 10f, 1.38f, 0.16f, innerShadow = 0.12f, useBlurredBackdrop = true
        )
        init {
            orientation = VERTICAL
            setWillNotDraw(false)
            val p = (12f * dens).roundToInt()
            setPadding(p, p, p, p)
        }
        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            glassHelper.draw(this, canvas, Color.argb(166, 18, 22, 34), ox, oy)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class GlassPillButton(
        context: Context,
        label: String,
        radiusDp: Float = 13f,
        private val isAccent: Boolean = false,
        private val onClick: () -> Unit
    ) : FrameLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, radiusDp, 12f, 10f, 1.44f, 0.22f, innerShadow = 0.18f, useBlurredBackdrop = true
        )
        val tv: TextView
        var selectedState = false
            set(value) {
                if (field == value) return
                field = value
                applyVisualState()
            }

        init {
            setWillNotDraw(false)
            isClickable = true
            tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setPadding((12f * dens).roundToInt(), (7f * dens).roundToInt(), (12f * dens).roundToInt(), (7f * dens).roundToInt())
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            }
            addView(tv)
            applyVisualState()

            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        animate().cancel()
                        animate().scaleX(0.93f).scaleY(0.93f).setDuration(85L).start()
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(280L)
                            .setInterpolator(OvershootInterpolator(1.7f)).start()
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
            setOnClickListener { onClick() }
        }

        private fun applyVisualState() {
            val active = selectedState || isAccent
            tv.setTextColor(if (active) 0xFFE0F2FE.toInt() else Color.argb(232, 255, 255, 255))
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            val bg = when {
                selectedState -> Color.argb(166, 32, 92, 152)
                isAccent -> Color.argb(160, 26, 84, 142)
                else -> Color.argb(150, 24, 28, 42)
            }
            val sh = if (selectedState || isAccent) 0.34f else 0.22f
            val inn = if (selectedState || isAccent) 0.28f else 0.17f
            glassHelper.draw(this, canvas, bg, ox, oy, shadowOverride = sh, innerOverride = inn)
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
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = ev.rawY; dy = 0f; false }
                MotionEvent.ACTION_MOVE -> {
                    dy = max(0f, ev.rawY - y0)
                    if (dy > 6f * dens) {
                        sheetCard.translationY = dy
                        sheetCard.invalidate()
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dy > 64f * dens) {
                        close()
                        true
                    } else {
                        sheetCard.animate().translationY(0f).setDuration(250L)
                            .setInterpolator(OvershootInterpolator(0.9f))
                            .setUpdateListener { sheetCard.invalidate() }
                            .start()
                        false
                    }
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
        fontScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setOnScrollChangeListener { _, _, _, _, _ -> invalidateGlassTree(this) }
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
        val colScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
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

        // 3. Time Format (12h / 24h)
        body.addView(secHeader("نظام الساعة"))
        val fmtRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        fmt12Btn = GlassPillButton(context, "12 ساعة", radiusDp = 12f) {
            cfg.hour24 = false
            cfg.leadingZero = false
            callbacks.onFormatToggled()
            syncFromConfig()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8f * dens).roundToInt()
            }
        }
        fmt24Btn = GlassPillButton(context, "24 ساعة", radiusDp = 12f) {
            cfg.hour24 = true
            cfg.leadingZero = true
            callbacks.onFormatToggled()
            syncFromConfig()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        fmtRow.addView(fmt12Btn)
        fmtRow.addView(fmt24Btn)
        body.addView(fmtRow)

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

        val inColScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
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
        // 1. Wallpapers + Depth
        body.addView(secHeader("الخلفية والعمق"))
        val wallCard = GlassSectionCard(context)
        val wallScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setOnScrollChangeListener { _, _, _, _, _ -> invalidateGlassTree(this) }
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
                            outline.setRoundRect(0, 0, v.width, v.height, 12f * dens)
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
        wallRow.addView(makeChipBtn("صور جهازي") { callbacks.onPickCustomWall() })
        aiDepthBtn = makeChipBtn("عزل AI", accent = true) { callbacks.onExtractAiDepth() }
        wallRow.addView(aiDepthBtn)
        wallRow.addView(makeChipBtn("صورة أمام الساعة") { callbacks.onPickManualSubject() })
        wallScroll.addView(wallRow)
        wallCard.addView(wallScroll)

        togDepth = GlassPillButton(context, "تأثير العمق", radiusDp = 12f) {
            callbacks.onToggleDepth()
            syncFromConfig()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10f * dens).roundToInt()
            }
        }
        wallCard.addView(togDepth)

        aiDepthStatus = TextView(context).apply {
            text = ""
            visibility = View.GONE
            setTextColor(Color.argb(175, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, (6f * dens).roundToInt(), 0, 0)
        }
        wallCard.addView(aiDepthStatus)
        body.addView(wallCard)

        // 2. Lock-Screen Widgets & Toggles
        body.addView(secHeader("تخصيصات أخرى"))
        val optsCard = GlassSectionCard(context)
        val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        togWeather = makeTogglePill("الطقس") { callbacks.onToggleWeather(); syncFromConfig() }
        togMusic = makeTogglePill("الموسيقى") { callbacks.onToggleMusic(); syncFromConfig() }
        togNotif = makeTogglePill("الإشعارات", isLast = true) { callbacks.onToggleNotifications(); syncFromConfig() }
        r1.addView(togWeather); r1.addView(togMusic); r1.addView(togNotif)

        val r2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8f * dens).roundToInt()
            }
        }
        togDnd = makeTogglePill("عدم الإزعاج") { callbacks.onToggleDnd(); syncFromConfig() }
        togPasscode = makeTogglePill("رمز القفل") { callbacks.onTogglePasscode(); syncFromConfig() }
        val changePinBtn = makeTogglePill("تغيير الرمز", isLast = true) { callbacks.onChangePasscodePin() }
        r2.addView(togDnd); r2.addView(togPasscode); r2.addView(changePinBtn)

        val r3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8f * dens).roundToInt()
            }
        }
        val testNotifBtn = makeTogglePill("إشعار تجريبي") { callbacks.onTestNotification() }
        val sysNotifBtn = makeTogglePill("إشعارات النظام", isLast = true) { callbacks.onOpenNotificationAccess() }
        r3.addView(testNotifBtn); r3.addView(sysNotifBtn)

        val r4 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8f * dens).roundToInt()
            }
        }
        val audioBtn = makeTogglePill("ملف صوتي") { callbacks.onPickAudio() }
        val artBtn = makeTogglePill("غلاف الألبوم", isLast = true) { callbacks.onPickAlbumArt() }
        r4.addView(audioBtn); r4.addView(artBtn)

        optsCard.addView(r1); optsCard.addView(r2); optsCard.addView(r3); optsCard.addView(r4)
        body.addView(optsCard)

        // 3. Performance Monitor Card
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
        actRow.addView(makePerfBtn("تصدير JSON", primary = true) { callbacks.onDownloadPerf() })
        actRow.addView(makePerfBtn("مشاركة") { callbacks.onSharePerf() })
        actRow.addView(makePerfBtn("تصفير", weight = 0.7f, isLast = true) { callbacks.onResetPerf() })
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

        fmt12Btn.selectedState = !cfg.hour24
        fmt24Btn.selectedState = cfg.hour24

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
                cornerRadius = 12f * dens
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

        togWeather.selectedState = cfg.weather
        togMusic.selectedState = cfg.music
        togNotif.selectedState = cfg.notifications
        togDepth.selectedState = cfg.depth
        togDnd.selectedState = cfg.dnd
        togPasscode.selectedState = cfg.passcodeEnabled
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
            switchModeBtn.tv.text = "الخلفية"
            clockBody.visibility = VISIBLE
            wallBody.visibility = GONE
        } else {
            titleView.text = "تخصيص الخلفية"
            switchModeBtn.tv.text = "الساعة"
            wallBody.visibility = VISIBLE
            clockBody.visibility = GONE
        }
    }

    fun open(mode: Mode = Mode.WALLPAPER) {
        if (isOpen) {
            if (currentMode != mode) switchMode(mode)
            return
        }
        isOpen = true
        heightAnim?.cancel()
        applyModeHeaderAndVisibility(mode)

        val targetH = sheetHeightFor(mode)
        val lp = sheetCard.layoutParams as LayoutParams
        lp.height = targetH
        lp.bottomMargin = safeBottomPx + (6f * dens).roundToInt()
        sheetCard.layoutParams = lp

        scrollView.scrollTo(0, 0)
        syncFromConfig()
        updatePerfUi(perf.snapshot())
        if (mode == Mode.CLOCK) {
            fontScroll.post {
                val activeChip = fontBtns[cfg.font]
                if (activeChip != null) {
                    val targetX = (activeChip.left - (fontScroll.width - activeChip.width) / 2).coerceAtLeast(0)
                    fontScroll.smoothScrollTo(targetX, 0)
                }
            }
        }

        visibility = VISIBLE
        alpha = 0f
        sheetCard.animate().cancel()
        sheetCard.translationY = targetH * 0.55f + 40f * dens
        sheetCard.scaleX = 0.965f
        sheetCard.scaleY = 0.965f

        animateScrimTo(if (mode == Mode.CLOCK) 0 else 44, 260L)
        animate().alpha(1f).setDuration(220L).start()
        sheetCard.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400L)
            .setInterpolator(DecelerateInterpolator(2.2f))
            .setUpdateListener { invalidateGlassTree(sheetCard) }
            .start()

        callbacks.onModeChanged(mode)
        callbacks.onHaptic("tick")
    }

    fun switchMode(mode: Mode) {
        if (!isOpen) {
            open(mode)
            return
        }
        if (currentMode == mode) return
        val outBody = if (currentMode == Mode.CLOCK) clockBody else wallBody
        val inBody = if (mode == Mode.CLOCK) clockBody else wallBody
        currentMode = mode
        callbacks.onModeChanged(mode)
        callbacks.onHaptic("tick")

        val startH = sheetCard.height.takeIf { it > 0 } ?: (sheetCard.layoutParams as LayoutParams).height
        val targetH = sheetHeightFor(mode)
        heightAnim?.cancel()
        heightAnim = ValueAnimator.ofInt(startH, targetH).apply {
            duration = 320L
            interpolator = DecelerateInterpolator(2.1f)
            addUpdateListener { a ->
                val lp = sheetCard.layoutParams as LayoutParams
                lp.height = a.animatedValue as Int
                sheetCard.layoutParams = lp
                invalidateGlassTree(sheetCard)
            }
            start()
        }
        animateScrimTo(if (mode == Mode.CLOCK) 0 else 44, 240L)

        titleView.text = if (mode == Mode.CLOCK) "تخصيص الساعة" else "تخصيص الخلفية"
        switchModeBtn.tv.text = if (mode == Mode.CLOCK) "الخلفية" else "الساعة"

        outBody.animate().cancel()
        inBody.animate().cancel()
        outBody.animate()
            .alpha(0f)
            .translationY(8f * dens)
            .setDuration(120L)
            .withEndAction {
                outBody.visibility = GONE
                outBody.alpha = 1f
                outBody.translationY = 0f
                scrollView.scrollTo(0, 0)
                inBody.visibility = VISIBLE
                inBody.alpha = 0f
                inBody.translationY = 10f * dens
                inBody.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator(1.9f))
                    .setUpdateListener { invalidateGlassTree(inBody) }
                    .start()
                if (mode == Mode.CLOCK) {
                    fontScroll.post {
                        val activeChip = fontBtns[cfg.font]
                        if (activeChip != null) {
                            val targetX = (activeChip.left - (fontScroll.width - activeChip.width) / 2).coerceAtLeast(0)
                            fontScroll.smoothScrollTo(targetX, 0)
                        }
                    }
                }
            }
            .start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        heightAnim?.cancel()
        animateScrimTo(0, 200L)
        animate().alpha(0f).setDuration(210L).start()
        val slideOut = (sheetCard.height.takeIf { it > 0 } ?: sheetHeightFor(currentMode)) * 0.72f + 44f * dens
        sheetCard.animate().cancel()
        sheetCard.animate()
            .translationY(slideOut)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator(1.9f))
            .setUpdateListener { sheetCard.invalidate() }
            .withEndAction {
                if (!isOpen) {
                    visibility = GONE
                    sheetCard.translationY = 0f
                    sheetCard.scaleX = 1f
                    sheetCard.scaleY = 1f
                    callbacks.onClose()
                }
            }
            .start()
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
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize((18f * dens).roundToInt(), (18f * dens).roundToInt())
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

    private fun makeChipBtn(label: String, accent: Boolean = false, onClick: () -> Unit): GlassPillButton {
        return GlassPillButton(context, label, radiusDp = 12f, isAccent = accent, onClick = onClick).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (68f * dens).roundToInt()).apply {
                marginEnd = (8f * dens).roundToInt()
            }
            tv.setPadding((14f * dens).roundToInt(), 0, (14f * dens).roundToInt(), 0)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
    }

    private fun makeTogglePill(label: String, isLast: Boolean = false, onClick: () -> Unit): GlassPillButton {
        return GlassPillButton(context, label, radiusDp = 12f, isAccent = false, onClick = onClick).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                if (!isLast) marginEnd = (6f * dens).roundToInt()
            }
            tv.setPadding((6f * dens).roundToInt(), (10f * dens).roundToInt(), (6f * dens).roundToInt(), (10f * dens).roundToInt())
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
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

    private fun makePerfBtn(label: String, primary: Boolean = false, weight: Float = 1f, isLast: Boolean = false, onClick: () -> Unit): GlassPillButton {
        return GlassPillButton(context, label, radiusDp = 11f, isAccent = primary, onClick = onClick).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, weight).apply {
                if (!isLast) marginEnd = (6f * dens).roundToInt()
            }
            tv.setPadding((6f * dens).roundToInt(), (9f * dens).roundToInt(), (6f * dens).roundToInt(), (9f * dens).roundToInt())
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
    }
}
