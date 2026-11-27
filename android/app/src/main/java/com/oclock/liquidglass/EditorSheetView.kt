package com.oclock.liquidglass

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
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
    private val callbacks: EditorCallbacks
) : FrameLayout(context) {

    interface EditorCallbacks {
        fun onClose()
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
    private val sheetCard: LinearLayout
    private val scrollView: ScrollView

    // Dynamic widgets updated by sync()
    private val fontBtns = HashMap<String, TextView>()
    private lateinit var sizeSeek: SeekBar
    private lateinit var sizeVal: TextView
    private lateinit var stretchSeek: SeekBar
    private lateinit var stretchVal: TextView
    private lateinit var ySeek: SeekBar
    private lateinit var yVal: TextView
    private lateinit var engineBadge: TextView
    private lateinit var fmt12Btn: TextView
    private lateinit var fmt24Btn: TextView

    private val colorSwatches = HashMap<String, View>()
    private lateinit var proSwatch: View
    private lateinit var proHex: TextView
    private lateinit var hueSeek: SeekBar
    private lateinit var toneSeek: SeekBar
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityVal: TextView

    private val wallThumbs = HashMap<String, FrameLayout>()
    private lateinit var aiDepthBtn: TextView
    private lateinit var aiDepthStatus: TextView

    private lateinit var inOpSeek: SeekBar
    private lateinit var inOpVal: TextView
    private lateinit var inThSeek: SeekBar
    private lateinit var inThVal: TextView
    private lateinit var inAngSeek: SeekBar
    private lateinit var inAngVal: TextView
    private lateinit var inFadeSeek: SeekBar
    private lateinit var inFadeVal: TextView
    private val innerSwatches = HashMap<String, View>()

    private lateinit var togWeather: TextView
    private lateinit var togMusic: TextView
    private lateinit var togNotif: TextView
    private lateinit var togDepth: TextView
    private lateinit var togDnd: TextView
    private lateinit var togPasscode: TextView

    private lateinit var perfMeta: TextView
    private lateinit var perfStatus: TextView
    private lateinit var perfFpsVal: TextView
    private lateinit var perfJankVal: TextView
    private lateinit var perfMemVal: TextView
    private lateinit var perfPssVal: TextView

    var isOpen = false
        private set
    private var suppressSeek = false
    private var proHue = 210f
    private var proTone = 50f

    init {
        visibility = GONE
        layoutDirection = LAYOUT_DIRECTION_RTL
        setBackgroundColor(Color.argb(64, 0, 0, 0))
        setOnClickListener { close() }

        sheetCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            layoutDirection = LAYOUT_DIRECTION_RTL
            val padH = (14f * dens).roundToInt()
            setPadding(padH, (8f * dens).roundToInt(), padH, (18f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(0xF0121318.toInt())
                cornerRadii = floatArrayOf(26f * dens, 26f * dens, 26f * dens, 26f * dens, 0f, 0f, 0f, 0f)
                setStroke((1f * dens).roundToInt(), Color.argb(36, 255, 255, 255))
            }
            val maxH = (resources.displayMetrics.heightPixels * 0.72f).roundToInt()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, maxH, Gravity.BOTTOM)
        }

        // Grabber + swipe down dismiss
        val grabberWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (22f * dens).roundToInt())
        }
        val grabber = View(context).apply {
            layoutParams = LayoutParams((36f * dens).roundToInt(), (4f * dens).roundToInt(), Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(Color.argb(82, 255, 255, 255))
                cornerRadius = 99f
            }
        }
        grabberWrap.addView(grabber)
        attachSwipeDismiss(grabberWrap)

        // Header
        val hdr = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6f * dens).roundToInt()
            }
        }
        val title = TextView(context).apply {
            text = "تخصيص شاشة القفل"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val doneBtn = TextView(context).apply {
            text = "تم"
            setTextColor(0xFFB3DFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding((14f * dens).roundToInt(), (6f * dens).roundToInt(), (14f * dens).roundToInt(), (6f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(24, 255, 255, 255))
                cornerRadius = 999f
            }
            setOnClickListener { close() }
        }
        hdr.addView(title)
        hdr.addView(doneBtn)
        attachSwipeDismiss(hdr)

        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (16f * dens).roundToInt())
        }

        buildContent(body)
        scrollView.addView(body)
        sheetCard.addView(grabberWrap)
        sheetCard.addView(hdr)
        sheetCard.addView(scrollView)
        addView(sheetCard)

        syncFromConfig()
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
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dy > 68f * dens) {
                        close()
                        true
                    } else {
                        sheetCard.animate().translationY(0f).setDuration(260L).setInterpolator(OvershootInterpolator(0.9f)).start()
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun buildContent(body: LinearLayout) {
        // 1. Fonts
        body.addView(secHeader("الخط"))
        val fontRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        for (f in ClockFont.entries) {
            val btn = TextView(context).apply {
                text = f.label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setPadding(0, (10f * dens).roundToInt(), 0, (10f * dens).roundToInt())
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (6f * dens).roundToInt()
                }
                setOnClickListener {
                    cfg.font = f.id
                    callbacks.onFontChanged(f.id)
                    syncFromConfig()
                }
            }
            fontBtns[f.id] = btn
            fontRow.addView(btn)
        }
        body.addView(fontRow)

        // 2. Size slider
        val (sHdr, sValTv) = secHeaderWithValue("حجم الساعة", "121%")
        sizeVal = sValTv
        body.addView(sHdr)
        sizeSeek = makeSeekBar(56, 420) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.size = v.toFloat()
            sizeVal.text = "${((cfg.size / 172f) * 100f).roundToInt()}%"
            callbacks.onGeometryPreview(true, false, false)
        }
        body.addView(sizeSeek)

        // 3. Stretch slider (100%..310%)
        val (stHdr, stValTv) = secHeaderWithValue("طول الساعة (بدون تشويه الوزن)", "168%")
        stretchVal = stValTv
        body.addView(stHdr)
        stretchSeek = makeSeekBar(100, 310) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.sy = v / 100f
            stretchVal.text = "$v%"
            callbacks.onGeometryPreview(false, true, false)
        }
        body.addView(stretchSeek)

        // 4. Y Position slider (-20..80) -> SeekBar 0..100 (offset -20)
        val (yHdr, yValTv) = secHeaderWithValue("موضع الساعة", "0px")
        yVal = yValTv
        body.addView(yHdr)
        ySeek = makeSeekBar(0, 100) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.clockY = (v - 20).toFloat()
            yVal.text = "${cfg.clockY.roundToInt()}px"
            callbacks.onGeometryPreview(false, false, true)
        }
        body.addView(ySeek)

        body.addView(TextView(context).apply {
            text = "اسحب للأسفل على الخلفية، أو اضغط مطوّلاً على الساعة لتغيير حجمها وسحب أضلاعها بحرية"
            setTextColor(Color.argb(128, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, (4f * dens).roundToInt(), 0, (4f * dens).roundToInt())
        })
        engineBadge = TextView(context).apply {
            text = if (LiquidGlassShaders.isGpuAgslAvailable()) "محرك حساب الزجاج: AGSL GPU + C++ مباشر" else "محرك حساب الزجاج: Native C++ مباشر"
            setTextColor(0xFFB3DFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        body.addView(engineBadge)

        // 5. Time Format
        body.addView(secHeader("نظام الساعة"))
        val fmtRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        fmt12Btn = TextView(context).apply {
            text = "12 ساعة"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, (9f * dens).roundToInt(), 0, (9f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8f * dens).roundToInt() }
            setOnClickListener {
                cfg.hour24 = false
                cfg.leadingZero = false
                callbacks.onFormatToggled()
                syncFromConfig()
            }
        }
        fmt24Btn = TextView(context).apply {
            text = "24 ساعة"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, (9f * dens).roundToInt(), 0, (9f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                cfg.hour24 = true
                cfg.leadingZero = true
                callbacks.onFormatToggled()
                syncFromConfig()
            }
        }
        fmtRow.addView(fmt12Btn)
        fmtRow.addView(fmt24Btn)
        body.addView(fmtRow)

        // 6. Clock Color & Opacity + Pro Color Card
        body.addView(secHeader("لون الساعة وشفافية الزجاج"))
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

        // Pro Color Palette card
        val proCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (11f * dens).roundToInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(Color.argb(14, 255, 255, 255))
                cornerRadius = 14f * dens
                setStroke((1f * dens).roundToInt(), Color.argb(20, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10f * dens).roundToInt()
            }
        }
        val proTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val proTitle = TextView(context).apply {
            text = "لوحة الألوان الاحترافية"
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
            val s = (28f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { openHexColorDialog() }
        }
        proTop.addView(proTitle)
        proTop.addView(proHex)
        proTop.addView(proSwatch)
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

        proCard.addView(miniSliderLabel("السطوع / النغمة"))
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

        val (opHdr, opTv) = secHeaderWithValue("شفافية لون الساعة", "14%")
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

        // 7. Wallpapers + AI Depth
        body.addView(secHeader("الخلفية وتأثير العمق (AI مجاني)"))
        val wallScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
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
        aiDepthBtn = makeChipBtn("عزل بالذكاء AI", accent = true) { callbacks.onExtractAiDepth() }
        wallRow.addView(aiDepthBtn)
        wallRow.addView(makeChipBtn("صورة أمام الساعة") { callbacks.onPickManualSubject() })
        wallScroll.addView(wallRow)
        body.addView(wallScroll)

        aiDepthStatus = TextView(context).apply {
            text = "جاهز: عزل مجاني غير محدود بالذكاء الاصطناعي"
            setTextColor(Color.argb(145, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, (5f * dens).roundToInt(), 0, 0)
        }
        body.addView(aiDepthStatus)

        // 8. Inner Shadow
        body.addView(secHeader("شادو الساعة الداخلي (الساعة فقط)"))
        val (ioH, ioV) = secHeaderWithValue("الشفافية", "100%")
        inOpVal = ioV
        body.addView(ioH)
        inOpSeek = makeSeekBar(0, 100) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.innerOpacity = v
            inOpVal.text = "$v%"
            callbacks.onInnerShadowChanged()
        }
        body.addView(inOpSeek)

        val (itH, itV) = secHeaderWithValue("السُمك", "0.3")
        inThVal = itV
        body.addView(itH)
        inThSeek = makeSeekBar(0, 80) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.innerThickness = v / 10f
            inThVal.text = "%.1f".format(cfg.innerThickness)
            callbacks.onInnerShadowChanged()
        }
        body.addView(inThSeek)

        val (iaH, iaV) = secHeaderWithValue("اتجاه الظل", "59°")
        inAngVal = iaV
        body.addView(iaH)
        inAngSeek = makeSeekBar(0, 360) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.innerAngle = v.toFloat()
            inAngVal.text = "$v°"
            callbacks.onInnerShadowChanged()
        }
        body.addView(inAngSeek)

        val (ifH, ifV) = secHeaderWithValue("نسبة التلاشي", "100%")
        inFadeVal = ifV
        body.addView(ifH)
        inFadeSeek = makeSeekBar(0, 100) { v, fromUser ->
            if (!fromUser || suppressSeek) return@makeSeekBar
            cfg.innerFade = v
            inFadeVal.text = "$v%"
            callbacks.onInnerShadowChanged()
        }
        body.addView(inFadeSeek)

        val inColScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6f * dens).roundToInt()
            }
        }
        val inColRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = LAYOUT_DIRECTION_LTR
        }
        for (cHex in AppConstants.INNER_COLORS) {
            val dot = View(context).apply {
                val s = (28f * dens).roundToInt()
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
        body.addView(inColScroll)

        // 9. Toggles
        body.addView(secHeader("خيارات"))
        val togGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        togWeather = makeTogglePill("الطقس") { callbacks.onToggleWeather(); syncFromConfig() }
        togMusic = makeTogglePill("الموسيقى") { callbacks.onToggleMusic(); syncFromConfig() }
        togDepth = makeTogglePill("تأثير العمق") { callbacks.onToggleDepth(); syncFromConfig() }
        r1.addView(togWeather); r1.addView(togMusic); r1.addView(togDepth)

        val r2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8f * dens).roundToInt()
            }
        }
        togNotif = makeTogglePill("الإشعارات") { callbacks.onToggleNotifications(); syncFromConfig() }
        togDnd = makeTogglePill("عدم الإزعاج") { callbacks.onToggleDnd(); syncFromConfig() }
        val audioBtn = makeTogglePill("ملف صوتي…") { callbacks.onPickAudio() }
        r2.addView(togNotif); r2.addView(togDnd); r2.addView(audioBtn)

        val r3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8f * dens).roundToInt()
            }
        }
        val artBtn = makeTogglePill("غلاف…") { callbacks.onPickAlbumArt() }
        val testNotifBtn = makeTogglePill("+ إشعار تجريبي") { callbacks.onTestNotification() }
        val sysNotifBtn = makeTogglePill("إشعارات النظام") { callbacks.onOpenNotificationAccess() }
        r3.addView(artBtn); r3.addView(testNotifBtn); r3.addView(sysNotifBtn)

        val r4 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8f * dens).roundToInt()
            }
        }
        togPasscode = makeTogglePill("رمز القفل PIN") { callbacks.onTogglePasscode(); syncFromConfig() }
        val changePinBtn = makeTogglePill("تغيير الرمز…") { callbacks.onChangePasscodePin() }
        r4.addView(togPasscode); r4.addView(changePinBtn)

        togGrid.addView(r1); togGrid.addView(r2); togGrid.addView(r3); togGrid.addView(r4)
        body.addView(togGrid)

        // 10. Continuous Performance Monitor Card
        body.addView(secHeader("مراقب الأداء المستمر (FPS · الذاكرة · الاستهلاك)"))
        val perfCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (11f * dens).roundToInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(Color.argb(14, 255, 255, 255))
                cornerRadius = 14f * dens
                setStroke((1f * dens).roundToInt(), Color.argb(20, 255, 255, 255))
            }
        }
        val perfTop = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        perfMeta = TextView(context).apply {
            text = "تشغيل 0s · محرك AGSL"
            setTextColor(Color.argb(190, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        perfStatus = TextView(context).apply {
            text = "جاهز للتصدير"
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
        val (b1, v1) = makePerfStatBox("FPS متوسط / حالي")
        val (b2, v2) = makePerfStatBox("هبوط >24ms / 50ms")
        val (b3, v3) = makePerfStatBox("ذاكرة الحقول")
        val (b4, v4) = makePerfStatBox("RAM / PSS الكلي")
        perfFpsVal = v1; perfJankVal = v2; perfMemVal = v3; perfPssVal = v4
        grid.addView(b1); grid.addView(b2); grid.addView(b3); grid.addView(b4)
        perfCard.addView(grid)

        val actRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (9f * dens).roundToInt()
            }
        }
        actRow.addView(makePerfBtn("تحميل تقرير الأداء (.json)", primary = true) { callbacks.onDownloadPerf() })
        actRow.addView(makePerfBtn("مشاركة / حفظ باسم") { callbacks.onSharePerf() })
        actRow.addView(makePerfBtn("تصفير", weight = 0.6f) { callbacks.onResetPerf() })
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
            .setTitle("تعديل كود اللون (Hex)")
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
        aiDepthStatus.text = msg
        aiDepthBtn.alpha = if (busy) 0.55f else 1f
    }

    fun setPerfStatus(msg: String) {
        perfStatus.text = msg
    }

    fun updatePerfUi(snap: PerfMonitor.SummarySnapshot) {
        if (!isOpen) return
        perfMeta.text = "تشغيل ${snap.uptimeSec}s · ${snap.engineLabel}"
        perfFpsVal.text = "${snap.avgFps} / ${snap.currentFps}"
        perfJankVal.text = "${snap.dropped24} / ${snap.jank50}"
        perfMemVal.text = "${snap.gpuCacheMb} MB"
        perfPssVal.text = "${snap.totalPssMb} MB"
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
        for ((id, tv) in fontBtns) {
            val active = cfg.font == id
            tv.setTextColor(if (active) 0xFFB3DFFF.toInt() else Color.WHITE)
            tv.background = GradientDrawable().apply {
                setColor(if (active) Color.argb(38, 179, 223, 255) else Color.argb(18, 255, 255, 255))
                cornerRadius = 12f * dens
                setStroke((1.2f * dens).roundToInt(), if (active) 0xFFB3DFFF.toInt() else Color.argb(20, 255, 255, 255))
            }
        }
        sizeSeek.progress = cfg.size.roundToInt().coerceIn(56, 420)
        sizeVal.text = "${((cfg.size / 172f) * 100f).roundToInt()}%"
        val stPct = (cfg.sy * 100f).roundToInt().coerceIn(100, 310)
        stretchSeek.progress = stPct
        stretchVal.text = "$stPct%"
        ySeek.progress = (cfg.clockY.roundToInt() + 20).coerceIn(0, 100)
        yVal.text = "${cfg.clockY.roundToInt()}px"

        fun styleFmt(btn: TextView, active: Boolean) {
            btn.setTextColor(if (active) 0xFFB3DFFF.toInt() else Color.WHITE)
            btn.background = GradientDrawable().apply {
                setColor(if (active) Color.argb(34, 179, 223, 255) else Color.argb(18, 255, 255, 255))
                cornerRadius = 11f * dens
                setStroke((1.1f * dens).roundToInt(), if (active) 0xFFB3DFFF.toInt() else Color.argb(20, 255, 255, 255))
            }
        }
        styleFmt(fmt12Btn, !cfg.hour24)
        styleFmt(fmt24Btn, cfg.hour24)

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

        styleToggle(togWeather, cfg.weather)
        styleToggle(togMusic, cfg.music)
        styleToggle(togNotif, cfg.notifications)
        styleToggle(togDepth, cfg.depth)
        styleToggle(togDnd, cfg.dnd)
        styleToggle(togPasscode, cfg.passcodeEnabled)
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

    private fun styleToggle(tv: TextView, active: Boolean) {
        tv.setTextColor(if (active) 0xFFB3DFFF.toInt() else Color.argb(215, 255, 255, 255))
        tv.background = GradientDrawable().apply {
            setColor(if (active) Color.argb(36, 179, 223, 255) else Color.argb(16, 255, 255, 255))
            cornerRadius = 11f * dens
            setStroke((1f * dens).roundToInt(), if (active) 0xFFB3DFFF.toInt() else Color.argb(24, 255, 255, 255))
        }
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        val lp = sheetCard.layoutParams as LayoutParams
        lp.height = (resources.displayMetrics.heightPixels * 0.72f).roundToInt()
        sheetCard.layoutParams = lp
        syncFromConfig()
        updatePerfUi(perf.snapshot())
        visibility = VISIBLE
        alpha = 0f
        sheetCard.translationY = resources.displayMetrics.heightPixels * 0.45f
        animate().alpha(1f).setDuration(240L).start()
        sheetCard.animate()
            .translationY(0f)
            .setDuration(420L)
            .setInterpolator(DecelerateInterpolator(2.1f))
            .start()
        callbacks.onHaptic("tick")
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        animate().alpha(0f).setDuration(200L).start()
        sheetCard.animate()
            .translationY(resources.displayMetrics.heightPixels * 0.55f)
            .setDuration(240L)
            .withEndAction {
                visibility = GONE
                sheetCard.translationY = 0f
                callbacks.onClose()
            }
            .start()
    }

    private fun secHeader(txt: String): TextView {
        return TextView(context).apply {
            text = txt
            setTextColor(Color.argb(158, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, (14f * dens).roundToInt(), 0, (6f * dens).roundToInt())
        }
    }

    private fun secHeaderWithValue(txt: String, initVal: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12f * dens).roundToInt(), 0, (4f * dens).roundToInt())
        }
        val l = TextView(context).apply {
            text = txt
            setTextColor(Color.argb(158, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val v = TextView(context).apply {
            text = initVal
            setTextColor(Color.argb(210, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            fontFeatureSettings = "tnum"
        }
        row.addView(l); row.addView(v)
        return row to v
    }

    private fun miniSliderLabel(txt: String): TextView {
        return TextView(context).apply {
            text = txt
            setTextColor(Color.argb(145, 255, 255, 255))
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
                setStroke((2f * dens).roundToInt(), Color.argb(60, 0, 0, 0))
            }
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFB3DFFF.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(46, 255, 255, 255))
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val realVal = if (android.os.Build.VERSION.SDK_INT >= 26) p else max(minV, p)
                    onChange(realVal, fromUser)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (sb === sizeSeek || sb === stretchSeek || sb === ySeek) {
                        callbacks.onGeometryCommit()
                    }
                }
            })
        }
    }

    private fun makeChipBtn(label: String, accent: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (accent) 0xFFBAE6FD.toInt() else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding((14f * dens).roundToInt(), 0, (14f * dens).roundToInt(), 0)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (68f * dens).roundToInt()).apply {
                marginEnd = (8f * dens).roundToInt()
            }
            background = GradientDrawable().apply {
                setColor(if (accent) Color.argb(38, 56, 189, 248) else Color.argb(20, 255, 255, 255))
                cornerRadius = 12f * dens
                setStroke((1f * dens).roundToInt(), if (accent) Color.argb(115, 125, 211, 252) else Color.argb(30, 255, 255, 255))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeTogglePill(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding((8f * dens).roundToInt(), (10f * dens).roundToInt(), (8f * dens).roundToInt(), (10f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6f * dens).roundToInt()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makePerfStatBox(label: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((6f * dens).roundToInt(), (7f * dens).roundToInt(), (6f * dens).roundToInt(), (7f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (5f * dens).roundToInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.argb(12, 255, 255, 255))
                cornerRadius = 10f * dens
            }
        }
        val k = TextView(context).apply {
            text = label
            setTextColor(Color.argb(145, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            maxLines = 1
        }
        val v = TextView(context).apply {
            text = "—"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            fontFeatureSettings = "tnum"
        }
        box.addView(k); box.addView(v)
        return box to v
    }

    private fun makePerfBtn(label: String, primary: Boolean = false, weight: Float = 1f, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (primary) 0xFFBAE6FD.toInt() else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding((6f * dens).roundToInt(), (9f * dens).roundToInt(), (6f * dens).roundToInt(), (9f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, weight).apply {
                marginEnd = (6f * dens).roundToInt()
            }
            background = GradientDrawable().apply {
                setColor(if (primary) Color.argb(46, 56, 189, 248) else Color.argb(20, 255, 255, 255))
                cornerRadius = 11f * dens
                setStroke((1f * dens).roundToInt(), if (primary) Color.argb(115, 125, 211, 252) else Color.argb(30, 255, 255, 255))
            }
            setOnClickListener { onClick() }
        }
    }
}
