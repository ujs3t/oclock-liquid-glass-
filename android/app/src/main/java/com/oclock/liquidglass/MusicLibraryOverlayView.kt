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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

@SuppressLint("ClickableViewAccessibility")
class MusicLibraryOverlayView(
    context: Context,
    private val media: MediaController,
    private val backdrop: LiquidGlassShaders.BackdropState,
    private val perf: PerfMonitor,
    private val getRootShell: () -> View?,
    private val onRequestPermission: () -> Unit,
    private val onOpenAppSettings: () -> Unit,
    private val onPickManualAudio: () -> Unit,
    private val onHaptic: (String) -> Unit,
    private val onNotify: (String, Int) -> Unit,
    private val onClosed: () -> Unit
) : FrameLayout(context) {

    private val dens = resources.displayMetrics.density
    private val panelCard: GlassSheetCard
    private val grabberWrap: FrameLayout
    private val headerRow: LinearLayout
    private val titleView: TextView
    private val subTitleView: TextView
    private val doneBtn: GlassPillButton
    private val shuffleBtn: GlassActionButton
    private val sequentialBtn: GlassActionButton
    private val refreshBtn: GlassActionButton
    private val searchBox: GlassSearchBar
    private val searchInput: EditText
    private val searchClearBtn: GlassClearButton
    private val permissionBox: GlassSectionCard
    private val permMsgView: TextView
    private val permGrantBtn: GlassActionButton
    private val manualPickBtn: GlassActionButton
    private val listView: RubberMusicListView

    private val allTracks = ArrayList<TrackInfo>()
    private val filteredIndices = ArrayList<Int>()
    private val trackAdapter = TrackAdapter()
    private val fallbackArtCache = HashMap<String, Bitmap>()
    private val tmpVisRect = Rect()
    private var permRequestCount = 0

    private var safeTopPx = (36f * dens).roundToInt()
    private var safeBottomPx = (14f * dens).roundToInt()
    private var scrimAnim: ValueAnimator? = null
    private var currentScrimAlpha = 0

    // Unified Choreographer spring matching EditorSheetView
    private var sheetSlideY = 0f
    private var targetSlideY = 0f
    private var velSlideY = 0f
    private var sheetScale = 1f
    private var targetScale = 1f
    private var velScale = 0f
    private var sheetClosing = false
    private var springRunning = false
    private var springLastNs = 0L

    var isOpen = false
        private set

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

            val aScale = kSlide * (targetScale - sheetScale) - dSlide * velScale
            velScale += aScale * dt
            sheetScale = (sheetScale + velScale * dt).coerceIn(0.90f, 1.04f)

            applySheetVisualState()

            val panelH = max(240f, panelCard.height.toFloat().takeIf { it > 0f }
                ?: (resources.displayMetrics.heightPixels * 0.82f))
            val slideOutLimit = max(140f, panelH * 0.72f)
            if (sheetClosing && (sheetSlideY >= slideOutLimit || panelCard.alpha <= 0.03f)) {
                springRunning = false
                sheetClosing = false
                velSlideY = 0f
                velScale = 0f
                visibility = GONE
                sheetSlideY = 0f
                sheetScale = 1f
                panelCard.translationY = 0f
                panelCard.scaleX = 1f
                panelCard.scaleY = 1f
                panelCard.alpha = 1f
                onClosed()
                return
            }

            val settled = !sheetClosing &&
                abs(targetSlideY - sheetSlideY) < 0.45f && abs(velSlideY) < 6f &&
                abs(targetScale - sheetScale) < 0.002f && abs(velScale) < 0.02f

            if (settled) {
                sheetSlideY = targetSlideY
                sheetScale = targetScale
                velSlideY = 0f
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
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { close() }

        panelCard = GlassSheetCard(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LAYOUT_DIRECTION_RTL
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, 30f * dens)
                }
            }
            val padH = (14f * dens).roundToInt()
            setPadding(padH, (6f * dens).roundToInt(), padH, (12f * dens).roundToInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                val side = (8f * dens).roundToInt()
                leftMargin = side
                rightMargin = side
                topMargin = (46f * dens).roundToInt()
                bottomMargin = (14f * dens).roundToInt()
            }
        }

        // Top drag grabber (matches EditorSheetView)
        grabberWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (18f * dens).roundToInt())
        }
        val grabberPill = View(context).apply {
            layoutParams = LayoutParams((36f * dens).roundToInt(), (4f * dens).roundToInt(), Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(Color.argb(104, 255, 255, 255))
                cornerRadius = 99f
            }
        }
        grabberWrap.addView(grabberPill)
        attachDragDismiss(grabberWrap)
        panelCard.addView(grabberWrap)

        // Sticky Liquid Glass Header (unified identity with EditorSheetView)
        headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (10f * dens).roundToInt()
            }
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8f * dens).roundToInt()
            }
        }
        titleView = TextView(context).apply {
            text = "مكتبة موسيقى الجهاز"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        subTitleView = TextView(context).apply {
            text = "اضغط على أي أغنية أو شغّل التبديل العشوائي"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.argb(170, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        titleCol.addView(titleView)
        titleCol.addView(subTitleView)

        doneBtn = GlassPillButton(context, "تم", radiusDp = 999f, isAccent = true, minWidthDp = 52f) {
            onHaptic("tick")
            close()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (32f * dens).roundToInt())
        }
        headerRow.addView(titleCol)
        headerRow.addView(doneBtn)
        attachDragDismiss(headerRow)
        panelCard.addView(headerRow)

        // Liquid Glass Action Pills: Shuffle + Sequential + Refresh
        val actRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (10f * dens).roundToInt()
            }
        }
        shuffleBtn = GlassActionButton(context, "shuffle", "تشغيل عشوائي", accentBg = false) {
            onHaptic("pop")
            if (!media.hasAudioPermission()) {
                triggerPermissionPrompt()
                return@GlassActionButton
            }
            if (allTracks.isEmpty()) {
                media.scanDeviceMusic(force = true)
                return@GlassActionButton
            }
            media.startShuffled()
            onNotify("تشغيل عشوائي للأغاني", 0xFF38BDF8.toInt())
            close()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, (40f * dens).roundToInt(), 1.15f).apply {
                marginEnd = (6f * dens).roundToInt()
            }
        }
        sequentialBtn = GlassActionButton(context, "play", "بالترتيب", accentBg = false) {
            onHaptic("pop")
            if (allTracks.isEmpty()) return@GlassActionButton
            val startIdx = if (filteredIndices.isNotEmpty()) filteredIndices[0] else 0
            media.startSequential(startIdx)
            onNotify("تشغيل القائمة", 0xFF30D158.toInt())
            close()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, (40f * dens).roundToInt(), 0.95f).apply {
                marginEnd = (6f * dens).roundToInt()
            }
        }
        refreshBtn = GlassActionButton(context, "refresh", null, accentBg = false) {
            onHaptic("tick")
            if (!media.hasAudioPermission()) {
                triggerPermissionPrompt()
            } else {
                subTitleView.text = "جارٍ البحث عن الأغاني والصور المصغرة..."
                media.scanDeviceMusic(force = true)
            }
        }.apply {
            val sz = (40f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }
        actRow.addView(shuffleBtn)
        actRow.addView(sequentialBtn)
        actRow.addView(refreshBtn)
        panelCard.addView(actRow)

        // Liquid Glass Search Bar with vector magnifier + animated clear pill
        searchBox = GlassSearchBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (42f * dens).roundToInt()).apply {
                bottomMargin = (10f * dens).roundToInt()
            }
        }
        val searchIcon = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                drawLibraryGlyph(canvas, "search", width * 0.5f, height * 0.5f, min(width, height) * 0.62f, Color.argb(175, 255, 255, 255))
            }
        }.apply {
            val sz = (22f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (8f * dens).roundToInt() }
        }
        searchInput = EditText(context).apply {
            hint = "ابحث عن أغنية أو فنان..."
            setHintTextColor(Color.argb(128, 255, 255, 255))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = null
            setPadding(0, (4f * dens).roundToInt(), 0, (4f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val txt = s?.toString() ?: ""
                    searchClearBtn.setShown(txt.isNotEmpty())
                    applyFilter(txt)
                }
            })
        }
        searchClearBtn = GlassClearButton(context) {
            onHaptic("tick")
            searchInput.setText("")
        }
        searchBox.addView(searchIcon)
        searchBox.addView(searchInput)
        searchBox.addView(searchClearBtn)
        panelCard.addView(searchBox)

        // Liquid Glass Permission / Empty Card
        permissionBox = GlassSectionCard(context, padHdp = 16f, padVdp = 20f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6f * dens).roundToInt()
            }
            visibility = GONE
        }
        val iconBadge = object : View(context) {
            private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 56, 189, 248) }
            private val rimP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.9f * dens
                color = Color.argb(110, 125, 211, 252)
            }
            override fun onDraw(canvas: Canvas) {
                val cx = width * 0.5f
                val cy = height * 0.5f
                val rad = min(width, height) * 0.47f
                canvas.drawCircle(cx, cy, rad, fillP)
                canvas.drawCircle(cx, cy, rad, rimP)
                drawLibraryGlyph(canvas, "music", cx, cy, min(width, height) * 0.48f, 0xFF93C5FD.toInt())
            }
        }.apply {
            val s = (54f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { bottomMargin = (12f * dens).roundToInt() }
        }
        permMsgView = TextView(context).apply {
            text = "لإظهار أغانيك وبوسترات الألبوم المصغرة من جهازك، امنح صلاحية الوصول للموسيقى والصور."
            gravity = Gravity.CENTER
            setTextColor(Color.argb(228, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, 0, 0, (14f * dens).roundToInt())
        }
        permGrantBtn = GlassActionButton(context, "music", "منح صلاحية الموسيقى والصور", accentBg = true) {
            onHaptic("pop")
            if (!media.hasAudioPermission()) {
                if (permRequestCount >= 2) onOpenAppSettings() else triggerPermissionPrompt()
            } else {
                media.scanDeviceMusic(force = true)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8f * dens).roundToInt()
            }
        }
        manualPickBtn = GlassActionButton(context, "folder", "اختيار ملف صوتي من الجهاز…", accentBg = false) {
            onHaptic("tick")
            onPickManualAudio()
            close()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        permissionBox.addView(iconBadge)
        permissionBox.addView(permMsgView)
        permissionBox.addView(permGrantBtn)
        permissionBox.addView(manualPickBtn)
        panelCard.addView(permissionBox)

        // Virtualized Liquid Glass Track List with elastic rubber-band overscroll
        listView = RubberMusicListView(context).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, (2f * dens).roundToInt(), 0, (12f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            adapter = trackAdapter
            onScrollOrRubber = { invalidateVisibleGlass(this) }
            setOnItemClickListener { _, view, position, _ ->
                if (position !in filteredIndices.indices) return@setOnItemClickListener
                val realIdx = filteredIndices[position]
                bounceView(view)
                onHaptic("pop")
                hideKeyboard()
                media.playDeviceTrack(realIdx, keepShuffle = false)
                close()
            }
        }
        panelCard.addView(listView)
        addView(panelCard)
    }

    fun refreshGlass() {
        if (visibility == VISIBLE) invalidateVisibleGlass(panelCard)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        springRunning = false
        Choreographer.getInstance().removeFrameCallback(sheetSpringCallback)
        scrimAnim?.cancel()
        listView.abortScrolling()
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
        if (v is GlassSheetCard || v is GlassPillButton || v is GlassActionButton ||
            v is GlassSearchBar || v is GlassSectionCard || v is GlassTrackRowCard
        ) {
            if (v === panelCard || v.getLocalVisibleRect(tmpVisRect)) {
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
        val visH = max(240f, panelCard.height.toFloat().takeIf { it > 0f }
            ?: (resources.displayMetrics.heightPixels * 0.82f))
        panelCard.pivotX = panelCard.width * 0.5f
        panelCard.pivotY = panelCard.height.toFloat()
        panelCard.translationY = sheetSlideY
        panelCard.scaleX = sheetScale
        panelCard.scaleY = sheetScale
        val slideFade = (1f - (sheetSlideY / (visH * 0.78f)).coerceIn(0f, 1f)).let { it * it }
        panelCard.alpha = if (sheetClosing || sheetSlideY > 0.5f) slideFade else 1f
        invalidateVisibleGlass(panelCard)
    }

    private fun startSheetSpring() {
        if (!springRunning) {
            springRunning = true
            springLastNs = 0L
            Choreographer.getInstance().postFrameCallback(sheetSpringCallback)
        }
    }

    private fun animateScrimTo(targetAlpha: Int, durationMs: Long = 230L) {
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

    private fun bounceView(v: View) {
        v.animate().cancel()
        v.scaleX = 0.94f
        v.scaleY = 0.94f
        v.animate().scaleX(1f).scaleY(1f).setDuration(260L)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun attachDragDismiss(handle: View) {
        val slop = min(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), 8f * dens)
        var activePtr = MotionEvent.INVALID_POINTER_ID
        var downY = 0f
        var dragging = false
        var vt: VelocityTracker? = null

        fun ptrRawY(ev: MotionEvent, idx: Int): Float = ev.getY(idx) + (ev.rawY - ev.y)

        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePtr = ev.getPointerId(0)
                    downY = ev.rawY
                    dragging = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain().also {
                        val copy = MotionEvent.obtain(ev)
                        copy.setLocation(ev.rawX, ev.rawY)
                        it.addMovement(copy)
                        copy.recycle()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = ev.findPointerIndex(activePtr)
                    if (idx < 0) return@setOnTouchListener false
                    val copy = MotionEvent.obtain(ev)
                    copy.setLocation(ev.rawX, ev.rawY)
                    vt?.addMovement(copy)
                    copy.recycle()
                    val curY = ptrRawY(ev, idx)
                    val dy = curY - downY
                    if (!dragging && abs(dy) > slop) {
                        dragging = true
                        springRunning = false
                        Choreographer.getInstance().removeFrameCallback(sheetSpringCallback)
                        hideKeyboard()
                    }
                    if (dragging) {
                        sheetSlideY = if (dy >= 0f) dy else dy * 0.16f
                        val h = max(240f, panelCard.height.toFloat())
                        val prog = (sheetSlideY / h).coerceIn(0f, 1f)
                        sheetScale = 1f - 0.03f * prog
                        applySheetVisualState()
                        currentScrimAlpha = (64f * (1f - prog)).roundToInt().coerceIn(0, 64)
                        setBackgroundColor(Color.argb(currentScrimAlpha, 0, 0, 0))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePtr = MotionEvent.INVALID_POINTER_ID
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    if (dragging) {
                        val h = max(240f, panelCard.height.toFloat())
                        val dismissThresh = max(92f * dens, h * 0.22f)
                        if (ev.actionMasked == MotionEvent.ACTION_UP && (sheetSlideY + vy * 0.14f > dismissThresh || vy > 720f * dens)) {
                            velSlideY = max(vy, 160f * dens)
                            close()
                        } else {
                            targetSlideY = 0f
                            targetScale = 1f
                            velSlideY = vy * 0.55f
                            animateScrimTo(64, 180L)
                            startSheetSpring()
                        }
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun triggerPermissionPrompt() {
        permRequestCount++
        onRequestPermission()
    }

    fun applySafeInsets(safeTop: Int, safeBottom: Int) {
        safeTopPx = safeTop
        safeBottomPx = safeBottom
        val lp = panelCard.layoutParams as LayoutParams
        lp.topMargin = max((36f * dens).roundToInt(), safeTop + (12f * dens).roundToInt())
        lp.bottomMargin = max((14f * dens).roundToInt(), safeBottom + (6f * dens).roundToInt())
        panelCard.layoutParams = lp
    }

    fun open() {
        if (isOpen && !sheetClosing) return
        isOpen = true
        sheetClosing = false
        listView.abortScrolling()
        panelCard.requestFocus()
        syncTracks(media.getLibraryTracks())

        if (!media.hasAudioPermission()) {
            triggerPermissionPrompt()
        } else if (!media.hasDeviceTracks() && !media.isScanning()) {
            subTitleView.text = "جارٍ فحص موسيقى الجهاز..."
            media.scanDeviceMusic(force = true)
        }

        val h = resources.displayMetrics.heightPixels.toFloat()
        val wasMidClose = visibility == VISIBLE && sheetSlideY > 0f
        visibility = VISIBLE
        alpha = 1f
        sheetSlideY = if (wasMidClose) sheetSlideY else (h * 0.42f + 22f * dens)
        targetSlideY = 0f
        velSlideY = if (wasMidClose) min(0f, velSlideY) else (-120f * dens)
        sheetScale = if (wasMidClose) sheetScale else 0.96f
        targetScale = 1f
        velScale = 0f
        applySheetVisualState()
        startSheetSpring()
        animateScrimTo(64, 230L)
    }

    fun close() {
        if (!isOpen && !sheetClosing) return
        isOpen = false
        sheetClosing = true
        hideKeyboard()
        listView.abortScrolling()
        animateScrimTo(0, 190L)
        val panelH = max(240f, panelCard.height.toFloat().takeIf { it > 0f }
            ?: (resources.displayMetrics.heightPixels * 0.82f))
        targetSlideY = panelH * 0.84f + 36f * dens
        targetScale = 0.965f
        if (velSlideY < 160f * dens) velSlideY = 160f * dens
        startSheetSpring()
    }

    fun onPermissionResult(granted: Boolean) {
        if (!isOpen) return
        if (granted) {
            subTitleView.text = "جارٍ تحميل الأغاني والصور المصغرة..."
            media.scanDeviceMusic(force = true)
        } else {
            syncTracks(emptyList())
        }
    }

    fun syncTracks(tracks: List<TrackInfo>) {
        allTracks.clear()
        allTracks.addAll(tracks)
        applyFilter(searchInput.text?.toString() ?: "")
        updateHeaderState()
    }

    private fun updateHeaderState() {
        val shuf = media.isShuffle()
        shuffleBtn.setAccent(shuf)
        shuffleBtn.setLabel(if (shuf) "العشوائي مفعّل" else "تشغيل عشوائي")

        if (!media.hasAudioPermission()) {
            permissionBox.visibility = VISIBLE
            permMsgView.text = "لإظهار أغانيك وبوسترات الألبوم المصغرة من جهازك، امنح صلاحية الوصول للموسيقى والصور."
            permGrantBtn.setLabel(if (permRequestCount >= 2) "فتح إعدادات الصلاحيات" else "منح صلاحية الموسيقى والصور")
            searchBox.visibility = GONE
            listView.visibility = GONE
            subTitleView.text = "الصلاحية مطلوبة للوصول للموسيقى والصور"
        } else if (allTracks.isEmpty()) {
            permissionBox.visibility = VISIBLE
            permMsgView.text = if (media.isScanning()) "جارٍ فحص الصوتيات على جهازك..." else "لم يتم العثور على أغاني (MP3/M4A/FLAC) في ذاكرة الجهاز."
            permGrantBtn.setLabel("إعادة الفحص")
            searchBox.visibility = GONE
            listView.visibility = GONE
            subTitleView.text = if (media.isScanning()) "جارٍ الفحص..." else "لا توجد ملفات موسيقى"
        } else {
            permissionBox.visibility = GONE
            searchBox.visibility = VISIBLE
            listView.visibility = VISIBLE
            val suffix = if (shuf) " · تبديل عشوائي" else ""
            subTitleView.text = "${allTracks.size} أغنية على جهازك$suffix"
        }
        invalidateVisibleGlass(panelCard)
    }

    private fun applyFilter(rawQuery: String) {
        val q = rawQuery.trim().lowercase()
        filteredIndices.clear()
        for (i in allTracks.indices) {
            val t = allTracks[i]
            if (q.isEmpty() ||
                t.title.lowercase().contains(q) ||
                t.artist.lowercase().contains(q) ||
                t.albumName.lowercase().contains(q)
            ) {
                filteredIndices.add(i)
            }
        }
        trackAdapter.notifyDataSetChanged()
    }

    private fun hideKeyboard() {
        searchInput.clearFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun fmtSec(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }

    private fun getFallbackArt(track: TrackInfo, sz: Int): Bitmap {
        val k = "${track.c1}|${track.c2}|${track.c3}|$sz"
        return fallbackArtCache.getOrPut(k) {
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
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                textAlign = Paint.Align.CENTER
                textSize = sz * 0.44f
            }
            c.drawText("♪", sz * 0.5f, sz * 0.65f, tp)
            bmp
        }
    }

    // ─────────────── Liquid Glass Outer Sheet & Inset Cards (matches EditorSheetView) ───────────────
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

    private inner class GlassSearchBar(context: Context) : LinearLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, 16f, 12f, 8f, 1.38f, 0.15f, innerShadow = 0.13f, useBlurredBackdrop = true
        )
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = LAYOUT_DIRECTION_RTL
            setWillNotDraw(false)
            setPadding((12f * dens).roundToInt(), 0, (10f * dens).roundToInt(), 0)
        }
        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            glassHelper.draw(this, canvas, Color.argb(156, 14, 17, 27), ox, oy)
        }
    }

    private inner class GlassClearButton(
        context: Context,
        private val onClick: () -> Unit
    ) : View(context) {
        private val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 255, 255, 255) }
        private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.4f, 1.5f * dens)
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(215, 255, 255, 255)
        }
        private var shown = false

        init {
            val sz = (22f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginStart = (6f * dens).roundToInt() }
            visibility = GONE
            alpha = 0f
            scaleX = 0.72f
            scaleY = 0.72f
            setOnClickListener { onClick() }
        }

        fun setShown(show: Boolean) {
            if (shown == show) return
            shown = show
            animate().cancel()
            if (show) {
                visibility = VISIBLE
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190L)
                    .setInterpolator(OvershootInterpolator(1.6f)).start()
            } else {
                animate().alpha(0f).scaleX(0.72f).scaleY(0.72f).setDuration(140L)
                    .withEndAction { if (!shown) visibility = GONE }
                    .start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width * 0.5f
            val cy = height * 0.5f
            val rad = min(width, height) * 0.45f
            canvas.drawCircle(cx, cy, rad, bgP)
            val r = rad * 0.36f
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, strokeP)
            canvas.drawLine(cx + r, cy - r, cx - r, cy + r, strokeP)
        }
    }

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
        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            val bg = if (isAccent) Color.argb(166, 26, 86, 146) else Color.argb(148, 26, 30, 44)
            val sh = if (isAccent) 0.34f else 0.22f
            val inn = if (isAccent) 0.28f else 0.17f
            glassHelper.draw(this, canvas, bg, ox, oy, shadowOverride = sh, innerOverride = inn)
        }
    }

    private inner class GlassActionButton(
        context: Context,
        private val iconId: String,
        label: String?,
        private var accentBg: Boolean = false,
        private val onClick: () -> Unit
    ) : FrameLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, 15f, 12f, 9f, 1.42f, 0.20f, innerShadow = 0.16f, useBlurredBackdrop = true
        )
        private val iconView: View
        private val tv: TextView?

        init {
            setWillNotDraw(false)
            isClickable = true
            val hasText = !label.isNullOrEmpty()
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutDirection = LAYOUT_DIRECTION_RTL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                val hPad = (if (hasText) 10f else 0f) * dens
                val vPad = (if (hasText) 9f else 0f) * dens
                setPadding(hPad.roundToInt(), vPad.roundToInt(), hPad.roundToInt(), vPad.roundToInt())
            }
            iconView = object : View(context) {
                override fun onDraw(canvas: Canvas) {
                    val col = if (accentBg) 0xFF7DD3FC.toInt() else Color.WHITE
                    drawLibraryGlyph(canvas, iconId, width * 0.5f, height * 0.5f, min(width, height) * 0.78f, col)
                }
            }.apply {
                val sz = ((if (hasText) 16.5f else 19f) * dens).roundToInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    if (hasText) marginEnd = (6f * dens).roundToInt()
                }
            }
            row.addView(iconView)
            tv = if (hasText) {
                TextView(context).apply {
                    text = label
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(if (accentBg) 0xFFE0F2FE.toInt() else Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }.also { row.addView(it) }
            } else null
            addView(row)

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

        fun setAccent(on: Boolean) {
            if (accentBg == on) return
            accentBg = on
            tv?.setTextColor(if (on) 0xFFE0F2FE.toInt() else Color.WHITE)
            iconView.invalidate()
            invalidate()
        }

        fun setLabel(label: String) {
            val t = tv ?: return
            if (t.text.toString() == label) return
            t.text = label
            t.requestLayout()
            requestLayout()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            val bg = if (accentBg) Color.argb(156, 22, 68, 118) else Color.argb(142, 24, 28, 42)
            val sh = if (accentBg) 0.30f else 0.18f
            val inn = if (accentBg) 0.24f else 0.15f
            glassHelper.draw(this, canvas, bg, ox, oy, shadowOverride = sh, innerOverride = inn)
        }
    }

    private inner class GlassTrackRowCard(context: Context) : LinearLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, 18f, 12f, 9f, 1.40f, 0.16f, innerShadow = 0.12f, useBlurredBackdrop = true
        )
        var isActiveTrack = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = LAYOUT_DIRECTION_LTR
            setWillNotDraw(false)
            setPadding((10f * dens).roundToInt(), (8f * dens).roundToInt(), (12f * dens).roundToInt(), (8f * dens).roundToInt())
        }
        override fun onDraw(canvas: Canvas) {
            val (ox, oy) = computeShellOrigin(this)
            val bg = if (isActiveTrack) Color.argb(166, 22, 68, 118) else Color.argb(162, 17, 20, 31)
            val sh = if (isActiveTrack) 0.32f else 0.15f
            val inn = if (isActiveTrack) 0.24f else 0.11f
            glassHelper.draw(this, canvas, bg, ox, oy, shadowOverride = sh, innerOverride = inn)
        }
    }

    private fun drawLibraryGlyph(canvas: Canvas, iconId: String, cx: Float, cy: Float, sz: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.4f, sz * 0.12f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val r = sz * 0.42f
        when (iconId) {
            "shuffle" -> {
                val topPath = Path().apply {
                    moveTo(cx - r * 0.85f, cy - r * 0.48f)
                    lineTo(cx - r * 0.25f, cy - r * 0.48f)
                    lineTo(cx + r * 0.30f, cy + r * 0.48f)
                    lineTo(cx + r * 0.82f, cy + r * 0.48f)
                }
                val botPath = Path().apply {
                    moveTo(cx - r * 0.85f, cy + r * 0.48f)
                    lineTo(cx - r * 0.25f, cy + r * 0.48f)
                    lineTo(cx + r * 0.30f, cy - r * 0.48f)
                    lineTo(cx + r * 0.82f, cy - r * 0.48f)
                }
                canvas.drawPath(topPath, p)
                canvas.drawPath(botPath, p)
                canvas.drawLine(cx + r * 0.56f, cy - r * 0.72f, cx + r * 0.86f, cy - r * 0.48f, p)
                canvas.drawLine(cx + r * 0.56f, cy - r * 0.24f, cx + r * 0.86f, cy - r * 0.48f, p)
                canvas.drawLine(cx + r * 0.56f, cy + r * 0.24f, cx + r * 0.86f, cy + r * 0.48f, p)
                canvas.drawLine(cx + r * 0.56f, cy + r * 0.72f, cx + r * 0.86f, cy + r * 0.48f, p)
            }
            "play" -> {
                val tri = Path().apply {
                    moveTo(cx - r * 0.44f, cy - r * 0.68f)
                    lineTo(cx + r * 0.74f, cy)
                    lineTo(cx - r * 0.44f, cy + r * 0.68f)
                    close()
                }
                canvas.drawPath(tri, fillP)
            }
            "refresh" -> {
                val arc = RectF(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f)
                canvas.drawArc(arc, -45f, 275f, false, p)
                canvas.drawLine(cx + r * 0.50f, cy - r * 0.52f, cx + r * 0.14f, cy - r * 0.56f, p)
                canvas.drawLine(cx + r * 0.50f, cy - r * 0.52f, cx + r * 0.52f, cy - r * 0.16f, p)
            }
            "search" -> {
                val cr = r * 0.58f
                canvas.drawCircle(cx - r * 0.14f, cy - r * 0.14f, cr, p)
                val ox = cx - r * 0.14f + cr * cos(Math.toRadians(45.0)).toFloat()
                val oy = cy - r * 0.14f + cr * sin(Math.toRadians(45.0)).toFloat()
                canvas.drawLine(ox, oy, cx + r * 0.78f, cy + r * 0.78f, p)
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
            "folder" -> {
                val box = Path().apply {
                    moveTo(cx - r * 0.82f, cy - r * 0.58f)
                    lineTo(cx - r * 0.18f, cy - r * 0.58f)
                    lineTo(cx + r * 0.04f, cy - r * 0.34f)
                    lineTo(cx + r * 0.82f, cy - r * 0.34f)
                    lineTo(cx + r * 0.82f, cy + r * 0.64f)
                    lineTo(cx - r * 0.82f, cy + r * 0.64f)
                    close()
                }
                canvas.drawPath(box, p)
            }
        }
    }

    // ─────────────── Elastic Rubber-Band ListView with Live Glass Refresh ───────────────
    private inner class RubberMusicListView(context: Context) : ListView(context) {
        private val touchSlop = min(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), 8f * dens)
        private val maxPullPx = 56f * dens
        private var activePtr = MotionEvent.INVALID_POINTER_ID
        private var startRawY = 0f
        private var lastRawY = 0f
        private var pullingRubber = false
        private var vt: VelocityTracker? = null
        private var rawPull = 0f
        var rubberY = 0f
            private set
        private var velRubber = 0f
        private var springReboundRunning = false
        private var reboundLastNs = 0L
        private var pressedRow: View? = null
        var onScrollOrRubber: (() -> Unit)? = null

        private val reboundCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!springReboundRunning) return
                val dt = if (reboundLastNs == 0L) 0.016f
                else ((frameTimeNanos - reboundLastNs) / 1_000_000_000f).coerceIn(0.004f, 0.032f)
                reboundLastNs = frameTimeNanos

                val omega = 22.5f
                val zeta = 0.80f
                val acc = -(omega * omega) * rubberY - (2f * zeta * omega) * velRubber
                velRubber += acc * dt
                setRubberY(rubberY + velRubber * dt)
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
            overScrollMode = OVER_SCROLL_NEVER
            setOnScrollListener(object : OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
                override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                    onScrollOrRubber?.invoke()
                }
            })
        }

        fun abortScrolling() {
            springReboundRunning = false
            Choreographer.getInstance().removeFrameCallback(reboundCallback)
            rawPull = 0f
            velRubber = 0f
            if (rubberY != 0f) setRubberY(0f)
        }

        private fun ptrRawY(ev: MotionEvent, idx: Int): Float = ev.getY(idx) + (ev.rawY - ev.y)

        private fun trackRaw(ev: MotionEvent) {
            val copy = MotionEvent.obtain(ev)
            copy.setLocation(ev.rawX, ev.rawY)
            vt?.addMovement(copy)
            copy.recycle()
        }

        private fun setRubberY(y: Float) {
            rubberY = y
            for (i in 0 until childCount) {
                getChildAt(i).translationY = y
            }
            onScrollOrRubber?.invoke()
        }

        private fun pullToRubber(raw: Float): Float {
            val a = abs(raw)
            val mapped = maxPullPx * (1f - 1f / (1f + a / (maxPullPx * 1.25f)))
            return sign(raw) * mapped
        }

        private fun rubberToPull(r: Float): Float {
            val a = abs(r).coerceAtMost(maxPullPx * 0.96f)
            return sign(r) * (maxPullPx * 1.25f * a / max(1f, maxPullPx - a))
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

        private fun releasePressedRow() {
            val r = pressedRow ?: return
            pressedRow = null
            r.animate().cancel()
            r.animate().scaleX(1f).scaleY(1f).setDuration(240L)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    springReboundRunning = false
                    Choreographer.getInstance().removeFrameCallback(reboundCallback)
                    rawPull = if (abs(rubberY) > 0.5f) rubberToPull(rubberY) else 0f
                    activePtr = ev.getPointerId(0)
                    startRawY = ev.rawY
                    lastRawY = ev.rawY
                    pullingRubber = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain()
                    trackRaw(ev)
                    val pos = pointToPosition(ev.x.roundToInt(), ev.y.roundToInt())
                    if (pos != INVALID_POSITION) {
                        val idx = pos - firstVisiblePosition
                        val row = if (idx in 0 until childCount) getChildAt(idx) else null
                        if (row != null) {
                            pressedRow = row
                            row.animate().cancel()
                            row.animate().scaleX(0.965f).scaleY(0.965f).setDuration(80L).start()
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = ev.findPointerIndex(activePtr)
                    if (idx >= 0) {
                        trackRaw(ev)
                        val curY = ptrRawY(ev, idx)
                        val dy = curY - lastRawY
                        lastRawY = curY
                        if (abs(curY - startRawY) > touchSlop) releasePressedRow()
                        val atTop = !canScrollVertically(-1)
                        val atBot = !canScrollVertically(1)
                        if (abs(rawPull) > 0.5f || abs(rubberY) > 0.5f) {
                            val nextRaw = rawPull + dy
                            if (sign(nextRaw) != sign(rawPull) && rawPull != 0f) {
                                rawPull = 0f
                                setRubberY(0f)
                                pullingRubber = false
                            } else {
                                rawPull = nextRaw
                                setRubberY(pullToRubber(rawPull))
                                return true
                            }
                        } else if (abs(curY - startRawY) > touchSlop &&
                            ((atTop && dy > 0f) || (atBot && dy < 0f))
                        ) {
                            pullingRubber = true
                            rawPull += dy
                            setRubberY(pullToRubber(rawPull))
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    releasePressedRow()
                    activePtr = MotionEvent.INVALID_POINTER_ID
                    trackRaw(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    if (abs(rawPull) > 0.5f || abs(rubberY) > 0.5f) {
                        startRubberSpring(vy * 0.24f)
                        if (pullingRubber) {
                            pullingRubber = false
                            return true
                        }
                    }
                    pullingRubber = false
                }
            }
            return super.onTouchEvent(ev)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            abortScrolling()
            pressedRow = null
            vt?.recycle()
            vt = null
        }
    }

    private class RowHolder(
        val card: GlassTrackRowCard,
        val art: ImageView,
        val title: TextView,
        val artist: TextView,
        val duration: TextView,
        var boundUri: Uri? = null
    )

    private inner class TrackAdapter : BaseAdapter() {
        private val artPx = (48f * dens).roundToInt()

        override fun getCount(): Int = filteredIndices.size
        override fun getItem(position: Int): TrackInfo = allTracks[filteredIndices[position]]
        override fun getItemId(position: Int): Long = filteredIndices[position].toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: RowHolder
            val rowView: FrameLayout
            if (convertView == null) {
                rowView = FrameLayout(context).apply {
                    setPadding(0, (3f * dens).roundToInt(), 0, (3f * dens).roundToInt())
                }
                val card = GlassTrackRowCard(context).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                }
                val art = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(artPx, artPx).apply { marginEnd = (12f * dens).roundToInt() }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, v.width, v.height, 11f * dens)
                        }
                    }
                }
                val meta = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                }
                val title = TextView(context).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
                val artist = TextView(context).apply {
                    setTextColor(Color.argb(170, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
                meta.addView(title)
                meta.addView(artist)

                val duration = TextView(context).apply {
                    setTextColor(Color.argb(178, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    fontFeatureSettings = "tnum"
                    layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        marginStart = (10f * dens).roundToInt()
                    }
                }
                card.addView(art)
                card.addView(meta)
                card.addView(duration)
                rowView.addView(card)
                holder = RowHolder(card, art, title, artist, duration)
                rowView.tag = holder
            } else {
                rowView = convertView as FrameLayout
                holder = rowView.tag as RowHolder
            }

            rowView.translationY = listView.rubberY
            val track = getItem(position)
            val current = media.currentTrack()
            val isActive = current.contentUri != null && current.contentUri == track.contentUri

            holder.card.isActiveTrack = isActive
            holder.title.text = track.title
            holder.title.setTextColor(if (isActive) 0xFFE0F2FE.toInt() else Color.WHITE)
            holder.artist.text = if (track.albumName.isNotBlank()) "${track.artist} · ${track.albumName}" else track.artist
            holder.duration.text = if (isActive && media.isPlaying()) "▶ ${fmtSec(track.durationSec)}" else fmtSec(track.durationSec)
            holder.duration.setTextColor(if (isActive) 0xFF7DD3FC.toInt() else Color.argb(170, 255, 255, 255))

            holder.boundUri = track.contentUri
            val cached = media.getCachedPoster(track, 112)
            if (cached != null) {
                holder.art.setImageBitmap(cached)
            } else {
                holder.art.setImageBitmap(getFallbackArt(track, artPx))
                val targetUri = track.contentUri
                media.loadTrackPoster(track, 112) { bmp ->
                    if (bmp != null && holder.boundUri == targetUri) {
                        holder.art.setImageBitmap(bmp)
                    }
                }
            }
            return rowView
        }
    }
}
