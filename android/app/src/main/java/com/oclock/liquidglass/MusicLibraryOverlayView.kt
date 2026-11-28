package com.oclock.liquidglass

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ListView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
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
    private val listStage: FrameLayout
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
    private val tmpVisRect = Rect()
    private var permRequestCount = 0

    private var cachedActiveUri: Uri? = null
    private var cachedIsPlaying = false

    private var safeTopPx = (36f * dens).roundToInt()
    private var safeBottomPx = (14f * dens).roundToInt()
    private var imeBottomPx = 0
    private var imeLiftPx = 0f
    private val baseListBottomPadPx = (82f * dens).roundToInt()

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
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, 30f * dens)
                }
            }
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
        panelCard.contentCol.addView(grabberWrap)

        // Sticky Liquid Glass Header (unified identity with EditorSheetView)
        headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((2f * dens).roundToInt(), 0, (2f * dens).roundToInt(), 0)
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
        panelCard.contentCol.addView(headerRow)

        // Liquid Glass Action Pills: Shuffle + Sequential + Refresh
        val actRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((2f * dens).roundToInt(), 0, (2f * dens).roundToInt(), 0)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8f * dens).roundToInt()
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
        panelCard.contentCol.addView(actRow)

        // Stage hosting scrolling list + detached floating Liquid Glass search capsule
        listStage = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            clipChildren = true
            clipToPadding = false
        }

        // Liquid Glass Permission / Empty Card
        permissionBox = GlassSectionCard(context, padHdp = 16f, padVdp = 20f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                topMargin = (6f * dens).roundToInt()
                leftMargin = (2f * dens).roundToInt()
                rightMargin = (2f * dens).roundToInt()
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
        listStage.addView(permissionBox)

        // Silky 120fps Track List with elastic rubber-band overscroll & fling-paused I/O
        listView = RubberMusicListView(context).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setFriction(ViewConfiguration.getScrollFriction() * 0.72f)
            setPadding(0, (4f * dens).roundToInt(), 0, baseListBottomPadPx)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            adapter = trackAdapter
            onScrollOrRubber = { searchBox.markBackdropDirty() }
            onFlingSettled = { loadVisibleRowPosters() }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                searchBox.markBackdropDirty()
            }
            setOnItemClickListener { _, view, position, _ ->
                if (position !in filteredIndices.indices) return@setOnItemClickListener
                val realIdx = filteredIndices[position]
                bounceView(view)
                onHaptic("pop")
                hideKeyboard()
                media.playDeviceTrack(realIdx, keepShuffle = false)
                syncPlaybackState()
                close()
            }
        }
        listStage.addView(listView)

        // Detached Floating Liquid Glass Search Capsule with live refraction + blur + rubbery gel
        searchBox = GlassSearchBar(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (54f * dens).roundToInt(), Gravity.BOTTOM).apply {
                leftMargin = (8f * dens).roundToInt()
                rightMargin = (8f * dens).roundToInt()
                bottomMargin = (12f * dens).roundToInt()
            }
        }
        val searchIcon = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                drawLibraryGlyph(canvas, "search", width * 0.5f, height * 0.5f, min(width, height) * 0.64f, Color.argb(215, 255, 255, 255))
            }
        }.apply {
            val sz = (22f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = (10f * dens).roundToInt() }
        }
        searchInput = EditText(context).apply {
            hint = "ابحث عن أغنية أو فنان..."
            setHintTextColor(Color.argb(165, 255, 255, 255))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(2.5f * dens, 0f, 1f * dens, Color.argb(130, 0, 0, 0))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = null
            setPadding(0, (6f * dens).roundToInt(), 0, (6f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnFocusChangeListener { _, hasFocus ->
                searchBox.setFocusActive(hasFocus)
            }
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
        listStage.addView(searchBox)

        panelCard.contentCol.addView(listStage)
        addView(panelCard)
    }

    fun refreshGlass() {
        if (visibility == VISIBLE) {
            panelCard.invalidateGlass()
            searchBox.markBackdropDirty()
            invalidateVisibleGlass(panelCard)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        springRunning = false
        Choreographer.getInstance().removeFrameCallback(sheetSpringCallback)
        scrimAnim?.cancel()
        listView.abortScrolling()
        searchBox.abortSpring()
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

    private fun computeRestShellOrigin(view: View): Pair<Float, Float> {
        val rootShell = getRootShell()
        var ox = view.left.toFloat()
        var oy = view.top.toFloat()
        var p = view.parent as? View
        while (p != null && p !== rootShell) {
            ox += p.left - p.scrollX
            oy += p.top - p.scrollY
            p = p.parent as? View
        }
        return ox to oy
    }

    private fun invalidateVisibleGlass(v: View) {
        if (v.visibility != View.VISIBLE) return
        if (v is GlassPillButton || v is GlassActionButton ||
            v is GlassSearchBar || v is GlassSectionCard
        ) {
            if (v.getLocalVisibleRect(tmpVisRect)) {
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
        v.scaleX = 0.96f
        v.scaleY = 0.96f
        v.animate().scaleX(1f).scaleY(1f).setDuration(240L)
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
        updateImeLift()
    }

    fun setImeBottomPx(imeBottom: Int) {
        if (imeBottomPx == imeBottom) return
        imeBottomPx = max(0, imeBottom)
        updateImeLift()
    }

    private fun updateImeLift() {
        val panelBottomMargin = max((14f * dens).roundToInt(), safeBottomPx + (6f * dens).roundToInt())
        val newLift = max(0f, imeBottomPx - panelBottomMargin.toFloat())
        if (abs(imeLiftPx - newLift) > 0.5f) {
            imeLiftPx = newLift
            val liftInt = newLift.roundToInt()
            listView.setPadding(0, (4f * dens).roundToInt(), 0, baseListBottomPadPx + liftInt)
            searchBox.applyImeLift(imeLiftPx)
        }
    }

    fun open() {
        if (isOpen && !sheetClosing) return
        isOpen = true
        sheetClosing = false
        listView.abortScrolling()
        searchBox.abortSpring()
        panelCard.requestFocus()
        syncPlaybackState(notify = false)
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
        panelCard.invalidateGlass()
        applySheetVisualState()
        startSheetSpring()
        animateScrimTo(64, 230L)
        post { searchBox.markBackdropDirty() }
    }

    fun close() {
        if (!isOpen && !sheetClosing) return
        isOpen = false
        sheetClosing = true
        hideKeyboard()
        listView.abortScrolling()
        searchBox.abortSpring()
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

    fun syncPlaybackState(notify: Boolean = true) {
        val uri = media.currentTrack().contentUri
        val playing = media.isPlaying()
        if (uri != cachedActiveUri || playing != cachedIsPlaying) {
            cachedActiveUri = uri
            cachedIsPlaying = playing
            if (notify && isOpen) {
                trackAdapter.notifyDataSetChanged()
                searchBox.markBackdropDirty()
            }
        }
    }

    fun syncTracks(tracks: List<TrackInfo>) {
        cachedActiveUri = media.currentTrack().contentUri
        cachedIsPlaying = media.isPlaying()
        allTracks.clear()
        allTracks.addAll(tracks)
        applyFilter(searchInput.text?.toString() ?: "")
        updateHeaderState()
    }

    private fun loadVisibleRowPosters() {
        if (!isOpen || listView.isFlinging) return
        var anyUpdated = false
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i) ?: continue
            val holder = child.tag as? RowHolder ?: continue
            val track = holder.boundTrack ?: continue
            if (holder.artHasBitmap) continue
            val targetUri = track.contentUri ?: continue
            val cached = media.getCachedPoster(track, 112)
            if (cached != null) {
                holder.art.bind(cached, track.c1, track.c2, track.c3)
                holder.artHasBitmap = true
                holder.rowSnapDirty = true
                anyUpdated = true
            } else {
                media.loadTrackPoster(
                    track,
                    112,
                    shouldDecode = { isOpen && !listView.isFlinging && holder.boundUri == targetUri }
                ) { bmp ->
                    if (bmp != null && holder.boundUri == targetUri) {
                        holder.art.bind(bmp, track.c1, track.c2, track.c3)
                        holder.artHasBitmap = true
                        holder.rowSnapDirty = true
                        searchBox.markBackdropDirty()
                    }
                }
            }
        }
        if (anyUpdated) searchBox.markBackdropDirty()
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
        searchBox.markBackdropDirty()
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
        searchBox.markBackdropDirty()
    }

    private fun focusSearchAndShowKeyboard() {
        searchInput.requestFocus()
        searchInput.setSelection(searchInput.text?.length ?: 0)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
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

    // ─────────────── Hardware-Cached Sheet Backdrop (Zero Shader Re-Execution During Scroll) ───────────────
    private inner class GlassSheetCard(context: Context) : FrameLayout(context) {
        private val glassHelper = LiquidGlassCardHelper(
            backdrop, perf, 30f, 24f, 16f, 1.44f, 0.24f, innerShadow = 0.16f, useBlurredBackdrop = true
        )
        private val sheetBgView: View = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                val (ox, oy) = computeRestShellOrigin(this)
                glassHelper.draw(this, canvas, Color.argb(198, 12, 14, 22), ox, oy)
            }
        }.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
        val contentCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LAYOUT_DIRECTION_RTL
            val padH = (12f * dens).roundToInt()
            setPadding(padH, (6f * dens).roundToInt(), padH, 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        init {
            addView(sheetBgView)
            addView(contentCol)
        }

        fun invalidateGlass() {
            sheetBgView.invalidate()
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

    // ─────────────── Detached Floating Liquid Glass Search Capsule (Row-Cached Live Blur + Refraction + Gel Spring) ───────────────
    private inner class GlassSearchBar(context: Context) : LinearLayout(context) {
        private val touchSlop = min(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), 8f * dens)
        private val dragSuppressClickDist = 14f * dens
        private val maxRubberX = 14f * dens
        private val maxRubberY = 10f * dens
        private val padDp = 16f
        private val capScale = (0.78f / dens).coerceIn(0.22f, 0.34f)

        private var runtimeShader: RuntimeShader? =
            if (Build.VERSION.SDK_INT >= 33) LiquidGlassShaders.createCardShader() else null
        private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val wallCapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val shaderMatrix = Matrix()
        private val cpuDstRect = RectF()

        private var rawCapBmp: Bitmap? = null
        private var blurredCapBmp: Bitmap? = null
        private var capCanvas: Canvas? = null
        private var pixBuf = IntArray(0)
        private var tmpBuf = IntArray(0)
        private var liveShader: BitmapShader? = null
        private var cpuOutBmp: Bitmap? = null
        private var cpuKey = ""
        private var capW = 0
        private var capH = 0
        private var backdropDirty = true
        private var lastCaptureMs = 0L

        // Rubbery Choreographer gel spring
        private var rubberX = 0f
        private var rubberY = 0f
        private var velX = 0f
        private var velY = 0f
        private var targetRubberX = 0f
        private var targetRubberY = 0f

        private var curScaleX = 1f
        private var curScaleY = 1f
        private var velScaleX = 0f
        private var velScaleY = 0f
        private var targetScaleX = 1f
        private var targetScaleY = 1f

        private var touchEnergy = 0f
        private var targetEnergy = 0f
        private var pressed = false
        private var focused = false
        private var imeLift = 0f

        private var downRawX = 0f
        private var downRawY = 0f
        private var maxDragDist = 0f
        private var didSuppressChildren = false
        private var springActive = false
        private var lastFrameNs = 0L

        private val gelSpringCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!springActive) return
                val dt = if (lastFrameNs == 0L) 0.016f
                else ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(0.004f, 0.032f)
                lastFrameNs = frameTimeNanos

                val omegaPos = if (pressed) 34f else 23.5f
                val zetaPos = if (pressed) 0.88f else 0.56f
                val kPos = omegaPos * omegaPos
                val dPos = 2f * zetaPos * omegaPos

                velX += (kPos * (targetRubberX - rubberX) - dPos * velX) * dt
                rubberX += velX * dt
                velY += (kPos * (targetRubberY - rubberY) - dPos * velY) * dt
                rubberY += velY * dt

                val omegaSc = if (pressed) 32f else 24.5f
                val zetaSc = if (pressed) 0.86f else 0.54f
                val kSc = omegaSc * omegaSc
                val dSc = 2f * zetaSc * omegaSc

                velScaleX += (kSc * (targetScaleX - curScaleX) - dSc * velScaleX) * dt
                curScaleX = (curScaleX + velScaleX * dt).coerceIn(0.90f, 1.09f)
                velScaleY += (kSc * (targetScaleY - curScaleY) - dSc * velScaleY) * dt
                curScaleY = (curScaleY + velScaleY * dt).coerceIn(0.88f, 1.09f)

                touchEnergy += (targetEnergy - touchEnergy) * min(1f, dt * 14f)

                applyTransformAndRefresh()

                val settled = !pressed &&
                    abs(targetRubberX - rubberX) < 0.25f && abs(velX) < 4f &&
                    abs(targetRubberY - rubberY) < 0.25f && abs(velY) < 4f &&
                    abs(targetScaleX - curScaleX) < 0.0015f && abs(velScaleX) < 0.015f &&
                    abs(targetScaleY - curScaleY) < 0.0015f && abs(velScaleY) < 0.015f &&
                    abs(targetEnergy - touchEnergy) < 0.01f

                if (settled) {
                    rubberX = targetRubberX
                    rubberY = targetRubberY
                    curScaleX = targetScaleX
                    curScaleY = targetScaleY
                    touchEnergy = targetEnergy
                    velX = 0f
                    velY = 0f
                    velScaleX = 0f
                    velScaleY = 0f
                    springActive = false
                    applyTransformAndRefresh()
                } else {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = LAYOUT_DIRECTION_RTL
            setWillNotDraw(false)
            isClickable = true
            elevation = 10f * dens
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    val r = min(v.width, v.height) * 0.5f
                    outline.setRoundRect(0, 0, v.width, v.height, r)
                }
            }
            setPadding((14f * dens).roundToInt(), 0, (12f * dens).roundToInt(), 0)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            pivotX = w * 0.5f
            pivotY = h * 0.5f
            markBackdropDirty()
        }

        fun applyImeLift(liftPx: Float) {
            imeLift = liftPx
            translationY = -imeLift + rubberY
            markBackdropDirty()
        }

        fun setFocusActive(active: Boolean) {
            focused = active
            targetEnergy = if (pressed || focused) 1f else 0f
            startGelSpring()
            invalidate()
        }

        fun markBackdropDirty() {
            backdropDirty = true
            if (visibility == VISIBLE) invalidate()
        }

        fun abortSpring() {
            springActive = false
            Choreographer.getInstance().removeFrameCallback(gelSpringCallback)
            pressed = false
            rubberX = 0f
            rubberY = 0f
            targetRubberX = 0f
            targetRubberY = 0f
            velX = 0f
            velY = 0f
            curScaleX = 1f
            curScaleY = 1f
            targetScaleX = 1f
            targetScaleY = 1f
            velScaleX = 0f
            velScaleY = 0f
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = -imeLift
        }

        private fun applyTransformAndRefresh() {
            translationX = rubberX
            translationY = -imeLift + rubberY
            scaleX = curScaleX
            scaleY = curScaleY
            backdropDirty = true
            invalidate()
        }

        private fun startGelSpring() {
            if (!springActive) {
                springActive = true
                lastFrameNs = 0L
                Choreographer.getInstance().postFrameCallback(gelSpringCallback)
            }
        }

        private fun pullRubber(raw: Float, maxDist: Float): Float {
            val a = abs(raw)
            return sign(raw) * maxDist * (1f - 1f / (1f + a / (maxDist * 1.25f)))
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    maxDragDist = 0f
                    didSuppressChildren = false
                    targetRubberX = 0f
                    targetRubberY = 0f
                    targetScaleX = 1.036f
                    targetScaleY = 0.942f
                    targetEnergy = 1f
                    onHaptic("tick")
                    startGelSpring()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    val dist = hypot(dx, dy)
                    if (dist > maxDragDist) maxDragDist = dist
                    if (dist > touchSlop) {
                        val rx = pullRubber(dx * 0.45f, maxRubberX)
                        val ry = pullRubber(dy * 0.40f, maxRubberY)
                        targetRubberX = rx
                        targetRubberY = ry
                        val stretch = (abs(rx) / maxRubberX) * 0.025f
                        val compress = (abs(ry) / maxRubberY) * 0.024f
                        targetScaleX = 1.036f + stretch
                        targetScaleY = 0.942f - compress
                        startGelSpring()
                    }
                    if (!didSuppressChildren && maxDragDist > dragSuppressClickDist) {
                        didSuppressChildren = true
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDrag = didSuppressChildren || maxDragDist > dragSuppressClickDist
                    pressed = false
                    targetRubberX = 0f
                    targetRubberY = 0f
                    targetScaleX = 1f
                    targetScaleY = 1f
                    targetEnergy = if (focused) 1f else 0f
                    velScaleX += if (wasDrag) 0.14f else 0.22f
                    velScaleY -= if (wasDrag) 0.14f else 0.18f
                    startGelSpring()
                    if (wasDrag) {
                        didSuppressChildren = false
                        return true
                    }
                    val handledByChild = super.dispatchTouchEvent(ev)
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        val hitClear = searchClearBtn.visibility == VISIBLE &&
                            ev.x >= searchClearBtn.left - 8f * dens && ev.x <= searchClearBtn.right + 8f * dens
                        if (!hitClear) {
                            val inEdit = ev.x >= searchInput.left && ev.x <= searchInput.right
                            if (!inEdit || !searchInput.hasFocus()) {
                                focusSearchAndShowKeyboard()
                            } else {
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                    }
                    return handledByChild || true
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        private fun boxBlurPass(src: IntArray, tmp: IntArray, w: Int, h: Int, radius: Int) {
            if (w <= 1 || h <= 1 || radius <= 0) return
            val wm1 = w - 1
            val hm1 = h - 1
            val win = 2 * radius + 1
            val half = radius
            for (y in 0 until h) {
                val row = y * w
                var sr = 0
                var sg = 0
                var sb = 0
                for (k in -radius..radius) {
                    val p = src[row + k.coerceIn(0, wm1)]
                    sr += (p ushr 16) and 0xff
                    sg += (p ushr 8) and 0xff
                    sb += p and 0xff
                }
                for (x in 0 until w) {
                    tmp[row + x] = (0xff shl 24) or
                        (((sr + half) / win) shl 16) or
                        (((sg + half) / win) shl 8) or
                        ((sb + half) / win)
                    val addP = src[row + min(wm1, x + radius + 1)]
                    val subP = src[row + max(0, x - radius)]
                    sr += ((addP ushr 16) and 0xff) - ((subP ushr 16) and 0xff)
                    sg += ((addP ushr 8) and 0xff) - ((subP ushr 8) and 0xff)
                    sb += (addP and 0xff) - (subP and 0xff)
                }
            }
            for (x in 0 until w) {
                var sr = 0
                var sg = 0
                var sb = 0
                for (k in -radius..radius) {
                    val p = tmp[k.coerceIn(0, hm1) * w + x]
                    sr += (p ushr 16) and 0xff
                    sg += (p ushr 8) and 0xff
                    sb += p and 0xff
                }
                for (y in 0 until h) {
                    src[y * w + x] = (0xff shl 24) or
                        (((sr + half) / win) shl 16) or
                        (((sg + half) / win) shl 8) or
                        ((sb + half) / win)
                    val addP = tmp[min(hm1, y + radius + 1) * w + x]
                    val subP = tmp[max(0, y - radius) * w + x]
                    sr += ((addP ushr 16) and 0xff) - ((subP ushr 16) and 0xff)
                    sg += ((addP ushr 8) and 0xff) - ((subP ushr 8) and 0xff)
                    sb += (addP and 0xff) - (subP and 0xff)
                }
            }
        }

        private fun ensureCaptureAndBlur(w: Float, h: Float) {
            val padPx = padDp * dens
            val targetW = max(24, ((w + padPx * 2f) * capScale).roundToInt())
            val targetH = max(24, ((h + padPx * 2f) * capScale).roundToInt())
            if (rawCapBmp == null || capW != targetW || capH != targetH) {
                rawCapBmp?.recycle()
                blurredCapBmp?.recycle()
                capW = targetW
                capH = targetH
                rawCapBmp = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888)
                blurredCapBmp = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888)
                capCanvas = Canvas(rawCapBmp!!)
                val count = capW * capH
                pixBuf = IntArray(count)
                tmpBuf = IntArray(count)
                backdropDirty = true
            }

            if (!backdropDirty && liveShader != null) return
            backdropDirty = false

            val rawBmp = rawCapBmp ?: return
            val blurBmp = blurredCapBmp ?: return
            val c = capCanvas ?: return

            // 1. Base sheet frosted wallpaper tint behind listStage
            c.drawColor(Color.rgb(12, 14, 22))
            val bBmp = backdrop.blurredBitmap
            val wBmp = bBmp ?: backdrop.wallBitmap
            if (wBmp != null && !wBmp.isRecycled) {
                try {
                    val (ox, oy) = computeShellOrigin(this)
                    val wl = if (bBmp != null) backdrop.blurredLeft else backdrop.wallLeft
                    val wt = if (bBmp != null) backdrop.blurredTop else backdrop.wallTop
                    val wsx = if (bBmp != null) backdrop.blurredScaleX else backdrop.wallScaleX
                    val wsy = if (bBmp != null) backdrop.blurredScaleY else backdrop.wallScaleY
                    c.save()
                    c.scale(capScale, capScale)
                    c.translate(padPx - ox, padPx - oy)
                    c.translate(wl, wt)
                    c.scale(wsx, wsy)
                    c.drawBitmap(wBmp, 0f, 0f, wallCapPaint)
                    c.restore()
                    c.drawColor(Color.argb(198, 12, 14, 22))
                } catch (_: Throwable) {}
            }

            // 2. Blit cached 0.26x snapshots ONLY for the 1-2 rows behind the floating search pill
            if (listView.visibility == VISIBLE && listView.width > 0 && listView.height > 0) {
                try {
                    val relX = this.x - listView.x
                    val relY = this.y - listView.y
                    val visTop = relY - padPx - 4f
                    val visBot = relY + h + padPx + 4f
                    for (i in 0 until listView.childCount) {
                        val child = listView.getChildAt(i) ?: continue
                        if (child.visibility != VISIBLE) continue
                        val cTop = child.top + child.translationY
                        val cBot = child.bottom + child.translationY
                        if (cBot < visTop || cTop > visBot) continue
                        val holder = child.tag as? RowHolder ?: continue
                        val snap = holder.ensureSnapshot(child, capScale) ?: continue
                        val dstX = (padPx - relX + child.left + child.translationX) * capScale
                        val dstY = (padPx - relY + cTop) * capScale
                        c.drawBitmap(snap, dstX, dstY, wallCapPaint)
                    }
                } catch (_: Throwable) {}
            }

            // 3. Single ultra-fast 1-pass separable box blur (~21k px -> <0.1ms)
            rawBmp.getPixels(pixBuf, 0, capW, 0, 0, capW, capH)
            boxBlurPass(pixBuf, tmpBuf, capW, capH, 2)
            blurBmp.setPixels(pixBuf, 0, capW, 0, 0, capW, capH)

            val sh = BitmapShader(blurBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shaderMatrix.reset()
            shaderMatrix.setScale(1f / capScale, 1f / capScale)
            shaderMatrix.postTranslate(-padPx, -padPx)
            sh.setLocalMatrix(shaderMatrix)
            liveShader = sh
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return

            ensureCaptureAndBlur(w, h)

            val padPx = padDp * dens
            val radPx = min(w, h) * 0.5f
            val bzPx = 24f * dens
            val thPx = 18f * dens
            val ior = 1.52f
            val bgA = (72f + 16f * touchEnergy).roundToInt()
            val bgR = (18f + 8f * touchEnergy).roundToInt()
            val bgG = (23f + 13f * touchEnergy).roundToInt()
            val bgB = (36f + 20f * touchEnergy).roundToInt()
            val bgCol = Color.argb(bgA, bgR, bgG, bgB)
            val rimSh = 0.34f + 0.10f * touchEnergy
            val innerGlow = 0.30f + 0.14f * touchEnergy

            val sh = runtimeShader
            val lShader = liveShader
            if (Build.VERSION.SDK_INT >= 33 && sh != null && lShader != null) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                LiquidGlassShaders.configureCardShader(
                    sh, lShader, 0f, 0f, w, h, radPx, bzPx, thPx, ior, bgCol, rimSh, innerGlow
                )
                glassPaint.shader = sh
                canvas.drawRect(0f, 0f, w, h, glassPaint)
                glassPaint.shader = null
                perf.recordWork("cardsDraw", (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0)
            } else {
                val blurBmp = blurredCapBmp ?: return
                val bw = max(16, (w * 0.5f).roundToInt())
                val bh = max(16, (h * 0.5f).roundToInt())
                val key = "$bw|$bh|$bgCol|${(rimSh * 100).roundToInt()}|${liveShader.hashCode()}"
                var out = cpuOutBmp?.takeIf { it.width == bw && it.height == bh }
                if (out == null || cpuKey != key) {
                    if (out == null) {
                        out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        cpuOutBmp = out
                    }
                    NativeGlass.renderCardCpuFallback(
                        blurBmp, out, 0f, 0f, w, h, radPx,
                        -padPx, -padPx, 1f / capScale, 1f / capScale,
                        bzPx, thPx, ior, bgCol, max(rimSh, innerGlow * 0.85f)
                    )
                    cpuKey = key
                }
                cpuDstRect.set(0f, 0f, w, h)
                canvas.drawBitmap(out, null, cpuDstRect, glassPaint)
            }
        }
    }

    private inner class GlassClearButton(
        context: Context,
        private val onClick: () -> Unit
    ) : View(context) {
        private val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(52, 255, 255, 255) }
        private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.4f, 1.5f * dens)
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(230, 255, 255, 255)
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

    // Zero-onDraw GPU GradientDrawable row card (zero per-row allocation or shader work during scroll)
    private inner class GlassTrackRowCard(context: Context) : LinearLayout(context) {
        private val cornerPx = 18f * dens
        private val strokePx = max(1, (0.9f * dens).roundToInt())
        private val normalBg = GradientDrawable().apply {
            cornerRadius = cornerPx
            setColor(Color.argb(114, 22, 27, 41))
            setStroke(strokePx, Color.argb(38, 255, 255, 255))
        }
        private val pressedBg = GradientDrawable().apply {
            cornerRadius = cornerPx
            setColor(Color.argb(148, 36, 44, 66))
            setStroke(strokePx, Color.argb(64, 255, 255, 255))
        }
        private val activeBg = GradientDrawable().apply {
            cornerRadius = cornerPx
            setColor(Color.argb(148, 24, 74, 132))
            setStroke(strokePx, Color.argb(110, 125, 211, 252))
        }
        private val activePressedBg = GradientDrawable().apply {
            cornerRadius = cornerPx
            setColor(Color.argb(176, 32, 92, 156))
            setStroke(strokePx, Color.argb(136, 125, 211, 252))
        }

        var isActiveTrack = false
            set(value) {
                if (field != value) {
                    field = value
                    syncDrawable()
                }
            }

        var isRowPressed = false
            set(value) {
                if (field != value) {
                    field = value
                    syncDrawable()
                }
            }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = LAYOUT_DIRECTION_LTR
            setPadding((10f * dens).roundToInt(), (7f * dens).roundToInt(), (12f * dens).roundToInt(), (7f * dens).roundToInt())
            background = normalBg
        }

        private fun syncDrawable() {
            background = when {
                isActiveTrack && isRowPressed -> activePressedBg
                isActiveTrack -> activeBg
                isRowPressed -> pressedBg
                else -> normalBg
            }
        }
    }

    // Fixed-bounds GPU rounded album thumbnail view (zero ImageView requestLayout or clipToOutline overhead)
    private inner class ArtThumbView(context: Context) : View(context) {
        private val cornerPx = 11f * dens
        private val rect = RectF()
        private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(195, 255, 255, 255)
            textAlign = Paint.Align.CENTER
        }
        private val bmpMatrix = Matrix()
        private var curBmp: Bitmap? = null
        private var c1 = 0
        private var c2 = 0
        private var c3 = 0
        private var shaderDirty = false

        fun bind(bmp: Bitmap?, col1: Int, col2: Int, col3: Int) {
            val bmpChanged = curBmp !== bmp
            val colChanged = c1 != col1 || c2 != col2 || c3 != col3
            if (!bmpChanged && !colChanged) return
            curBmp = bmp
            c1 = col1
            c2 = col2
            c3 = col3
            shaderDirty = true
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rect.set(0f, 0f, w.toFloat(), h.toFloat())
            notePaint.textSize = min(w, h) * 0.44f
            shaderDirty = true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val bmp = curBmp
            if (bmp != null && !bmp.isRecycled) {
                if (shaderDirty) {
                    shaderDirty = false
                    val bw = max(1f, bmp.width.toFloat())
                    val bh = max(1f, bmp.height.toFloat())
                    val scale = max(w / bw, h / bh)
                    val dx = (w - bw * scale) * 0.5f
                    val dy = (h - bh * scale) * 0.5f
                    bmpMatrix.reset()
                    bmpMatrix.setScale(scale, scale)
                    bmpMatrix.postTranslate(dx, dy)
                    val sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    sh.setLocalMatrix(bmpMatrix)
                    artPaint.shader = sh
                }
                canvas.drawRoundRect(rect, cornerPx, cornerPx, artPaint)
            } else {
                if (shaderDirty) {
                    shaderDirty = false
                    gradPaint.shader = LinearGradient(
                        0f, 0f, w, h,
                        intArrayOf(c1, c2, c3),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(rect, cornerPx, cornerPx, gradPaint)
                val cy = h * 0.5f - (notePaint.descent() + notePaint.ascent()) * 0.5f
                canvas.drawText("♪", w * 0.5f, cy, notePaint)
            }
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

    // ─────────────── Elastic Rubber-Band ListView with Fling-Edge Rebound ───────────────
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
        private var pressedHolder: RowHolder? = null

        private var curScrollState = OnScrollListener.SCROLL_STATE_IDLE
        private var lastFlingVelY = 0f
        private var flingStartMs = 0L
        private var didScrollDuringFling = false
        private var flingBounceUsed = false
        val isFlinging: Boolean
            get() = curScrollState == OnScrollListener.SCROLL_STATE_FLING
        var onScrollOrRubber: (() -> Unit)? = null
        var onFlingSettled: (() -> Unit)? = null

        private val reboundCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!springReboundRunning) return
                val dt = if (reboundLastNs == 0L) 0.016f
                else ((frameTimeNanos - reboundLastNs) / 1_000_000_000f).coerceIn(0.004f, 0.032f)
                reboundLastNs = frameTimeNanos

                val omega = 22.0f
                val zeta = 0.78f
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
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                    val wasFling = curScrollState == OnScrollListener.SCROLL_STATE_FLING
                    curScrollState = scrollState
                    if (wasFling && scrollState != OnScrollListener.SCROLL_STATE_FLING) {
                        onFlingSettled?.invoke()
                    }
                    if (scrollState != OnScrollListener.SCROLL_STATE_IDLE) {
                        onScrollOrRubber?.invoke()
                    }
                }
                override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                    if (curScrollState == OnScrollListener.SCROLL_STATE_FLING && !flingBounceUsed) {
                        val elapsed = SystemClock.uptimeMillis() - flingStartMs
                        if (elapsed < 1200L) {
                            val canUp = canScrollVertically(-1)
                            val canDown = canScrollVertically(1)
                            if (canUp && canDown) {
                                didScrollDuringFling = true
                            } else if (didScrollDuringFling && abs(lastFlingVelY) > 220f * dens) {
                                val decay = max(0.18f, 1f - elapsed / 1200f)
                                val atTop = !canUp && lastFlingVelY > 0f
                                val atBot = !canDown && lastFlingVelY < 0f
                                if (atTop || atBot) {
                                    flingBounceUsed = true
                                    startRubberSpring(lastFlingVelY * 0.22f * decay)
                                }
                            }
                        }
                    }
                    onScrollOrRubber?.invoke()
                }
            })
        }

        fun abortScrolling() {
            springReboundRunning = false
            Choreographer.getInstance().removeFrameCallback(reboundCallback)
            curScrollState = OnScrollListener.SCROLL_STATE_IDLE
            rawPull = 0f
            velRubber = 0f
            flingBounceUsed = true
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

        private fun releasePressedCard() {
            val h = pressedHolder ?: return
            pressedHolder = null
            h.card.isRowPressed = false
            h.rowSnapDirty = true
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
                    didScrollDuringFling = canScrollVertically(-1) && canScrollVertically(1)
                    flingBounceUsed = false
                    lastFlingVelY = 0f
                    vt?.recycle()
                    vt = VelocityTracker.obtain()
                    trackRaw(ev)
                    val pos = pointToPosition(ev.x.roundToInt(), ev.y.roundToInt())
                    if (pos != INVALID_POSITION) {
                        val idx = pos - firstVisiblePosition
                        val row = if (idx in 0 until childCount) getChildAt(idx) else null
                        val holder = row?.tag as? RowHolder
                        if (holder != null) {
                            pressedHolder = holder
                            holder.card.isRowPressed = true
                            holder.rowSnapDirty = true
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
                        if (abs(curY - startRawY) > touchSlop) releasePressedCard()
                        val atTop = !canScrollVertically(-1)
                        val atBot = !canScrollVertically(1)
                        if (abs(rawPull) > 0.5f || abs(rubberY) > 0.5f) {
                            val nextRaw = rawPull + dy
                            if (sign(nextRaw) != sign(rawPull) && rawPull != 0f) {
                                rawPull = 0f
                                setRubberY(0f)
                                pullingRubber = false
                                val resync = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_DOWN }
                                super.onTouchEvent(resync)
                                resync.recycle()
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
                    releasePressedCard()
                    activePtr = MotionEvent.INVALID_POINTER_ID
                    trackRaw(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    lastFlingVelY = vy
                    flingStartMs = SystemClock.uptimeMillis()
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
            releasePressedCard()
            vt?.recycle()
            vt = null
        }
    }

    private inner class RowHolder(
        val card: GlassTrackRowCard,
        val art: ArtThumbView,
        val title: TextView,
        val artist: TextView,
        val duration: TextView,
        var boundUri: Uri? = null,
        var boundTrack: TrackInfo? = null,
        var boundActive: Boolean = false,
        var boundPlaying: Boolean = false,
        var artHasBitmap: Boolean = false,
        var rowSnapDirty: Boolean = true,
        var rowSnapBmp: Bitmap? = null,
        var rowSnapCanvas: Canvas? = null
    ) {
        fun ensureSnapshot(rowView: View, capScale: Float): Bitmap? {
            val rw = rowView.width
            val rh = rowView.height
            if (rw <= 0 || rh <= 0) return null
            val sw = max(8, (rw * capScale).roundToInt())
            val sh = max(4, (rh * capScale).roundToInt())
            if (rowSnapBmp == null || rowSnapBmp!!.width != sw || rowSnapBmp!!.height != sh) {
                rowSnapBmp?.recycle()
                rowSnapBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                rowSnapCanvas = Canvas(rowSnapBmp!!)
                rowSnapDirty = true
            }
            if (rowSnapDirty) {
                rowSnapDirty = false
                val bmp = rowSnapBmp!!
                bmp.eraseColor(Color.TRANSPARENT)
                val sc = rowSnapCanvas!!
                sc.save()
                sc.scale(capScale, capScale)
                rowView.draw(sc)
                sc.restore()
            }
            return rowSnapBmp
        }
    }

    private inner class TrackAdapter : BaseAdapter() {
        private val artPx = (48f * dens).roundToInt()
        private val cardHeightPx = (62f * dens).roundToInt()

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
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, cardHeightPx)
                }
                val art = ArtThumbView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(artPx, artPx).apply { marginEnd = (12f * dens).roundToInt() }
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
                    setTextColor(Color.argb(182, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
                meta.addView(title)
                meta.addView(artist)

                val duration = TextView(context).apply {
                    setTextColor(Color.argb(185, 255, 255, 255))
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

            if (rowView.translationY != listView.rubberY) {
                rowView.translationY = listView.rubberY
            }

            val track = getItem(position)
            val targetUri = track.contentUri
            val isActive = cachedActiveUri != null && cachedActiveUri == targetUri
            val trackChanged = holder.boundTrack !== track
            val stateChanged = holder.boundActive != isActive || holder.boundPlaying != cachedIsPlaying

            holder.card.isActiveTrack = isActive
            holder.card.isRowPressed = false

            if (trackChanged || stateChanged) {
                holder.boundTrack = track
                holder.boundUri = targetUri
                holder.boundActive = isActive
                holder.boundPlaying = cachedIsPlaying
                holder.rowSnapDirty = true

                if (trackChanged) {
                    holder.title.text = track.title
                    holder.artist.text = if (track.albumName.isNotBlank()) "${track.artist} · ${track.albumName}" else track.artist
                }
                holder.title.setTextColor(if (isActive) 0xFFE0F2FE.toInt() else Color.WHITE)
                holder.duration.text = if (isActive && cachedIsPlaying) "▶ ${fmtSec(track.durationSec)}" else fmtSec(track.durationSec)
                holder.duration.setTextColor(if (isActive) 0xFF7DD3FC.toInt() else Color.argb(178, 255, 255, 255))
            }

            if (trackChanged || !holder.artHasBitmap) {
                val cached = media.getCachedPoster(track, 112)
                if (cached != null) {
                    holder.art.bind(cached, track.c1, track.c2, track.c3)
                    holder.artHasBitmap = true
                    holder.rowSnapDirty = true
                } else {
                    holder.art.bind(null, track.c1, track.c2, track.c3)
                    holder.artHasBitmap = false
                    if (!listView.isFlinging && targetUri != null) {
                        media.loadTrackPoster(
                            track,
                            112,
                            shouldDecode = { isOpen && !listView.isFlinging && holder.boundUri == targetUri }
                        ) { bmp ->
                            if (bmp != null && holder.boundUri == targetUri) {
                                holder.art.bind(bmp, track.c1, track.c2, track.c3)
                                holder.artHasBitmap = true
                                holder.rowSnapDirty = true
                                searchBox.markBackdropDirty()
                            }
                        }
                    }
                }
            }
            return rowView
        }
    }
}
