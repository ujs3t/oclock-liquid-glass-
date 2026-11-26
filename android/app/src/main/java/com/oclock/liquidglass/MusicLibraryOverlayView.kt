package com.oclock.liquidglass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility")
class MusicLibraryOverlayView(
    context: Context,
    private val media: MediaController,
    private val backdrop: LiquidGlassShaders.BackdropState,
    private val perf: PerfMonitor,
    private val onRequestPermission: () -> Unit,
    private val onOpenAppSettings: () -> Unit,
    private val onPickManualAudio: () -> Unit,
    private val onHaptic: (String) -> Unit,
    private val onNotify: (String, Int) -> Unit,
    private val onClosed: () -> Unit
) : FrameLayout(context) {

    private val dens = resources.displayMetrics.density
    private val scrimView: View
    private val panelCard: LinearLayout
    private val titleView: TextView
    private val subTitleView: TextView
    private val shuffleBtn: TextView
    private val sequentialBtn: TextView
    private val refreshBtn: TextView
    private val searchInput: EditText
    private val permissionBox: LinearLayout
    private val permMsgView: TextView
    private val permGrantBtn: TextView
    private val listView: ListView

    private val allTracks = ArrayList<TrackInfo>()
    private val filteredIndices = ArrayList<Int>()
    private val trackAdapter = TrackAdapter()
    private val fallbackArtCache = HashMap<String, Bitmap>()
    private var permRequestCount = 0

    var isOpen = false
        private set

    init {
        visibility = GONE
        layoutDirection = LAYOUT_DIRECTION_RTL
        isClickable = true
        isFocusable = true

        scrimView = View(context).apply {
            setBackgroundColor(Color.argb(214, 5, 7, 12))
            alpha = 0f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setOnClickListener { close() }
        }
        addView(scrimView)

        panelCard = LinearLayout(context).apply {
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
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(236, 20, 24, 36), Color.argb(242, 10, 12, 19))
            ).apply {
                cornerRadius = 30f * dens
                setStroke((1f * dens).roundToInt(), Color.argb(54, 255, 255, 255))
            }
            val padH = (14f * dens).roundToInt()
            setPadding(padH, (10f * dens).roundToInt(), padH, (14f * dens).roundToInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                leftMargin = (10f * dens).roundToInt()
                rightMargin = (10f * dens).roundToInt()
                topMargin = (46f * dens).roundToInt()
                bottomMargin = (24f * dens).roundToInt()
            }
        }

        // Top drag handle + header
        val handleWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (22f * dens).roundToInt())
        }
        val handlePill = View(context).apply {
            layoutParams = LayoutParams((44f * dens).roundToInt(), (4.5f * dens).roundToInt(), Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(Color.argb(95, 255, 255, 255))
                cornerRadius = 99f
            }
        }
        handleWrap.addView(handlePill)
        attachDragDismiss(handleWrap)
        panelCard.addView(handleWrap)

        val headerRow = object : LinearLayout(context) {
            private val glassHelper = LiquidGlassCardHelper(backdrop, perf, 20f, 16f, 12f, 1.45f, 0.22f)
            override fun onDraw(canvas: Canvas) {
                val pView = panelCard
                glassHelper.draw(this, canvas, Color.argb(85, 20, 24, 38), pView.x + x, pView.y + y)
            }
        }.apply {
            setWillNotDraw(false)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12f * dens).roundToInt(), (10f * dens).roundToInt(), (12f * dens).roundToInt(), (10f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (10f * dens).roundToInt()
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, 20f * dens)
                }
            }
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = TextView(context).apply {
            text = "مكتبة موسيقى الجهاز"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        subTitleView = TextView(context).apply {
            text = "اضغط على أي أغنية أو شغّل التبديل العشوائي"
            setTextColor(Color.argb(165, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        titleCol.addView(titleView)
        titleCol.addView(subTitleView)

        val closeBtn = TextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val s = (34f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(28, 255, 255, 255))
                setStroke((0.8f * dens).roundToInt(), Color.argb(44, 255, 255, 255))
            }
            setOnClickListener {
                onHaptic("tick")
                close()
            }
        }
        headerRow.addView(titleCol)
        headerRow.addView(closeBtn)
        panelCard.addView(headerRow)

        // Action row: Shuffle + Sequential + Refresh
        val actRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (10f * dens).roundToInt()
            }
        }
        shuffleBtn = makePillButton("⇄  تشغيل عشوائي", accent = true) { v ->
            bounceView(v)
            onHaptic("pop")
            if (!media.hasAudioPermission()) {
                triggerPermissionPrompt()
                return@makePillButton
            }
            if (allTracks.isEmpty()) {
                media.scanDeviceMusic(force = true)
                return@makePillButton
            }
            media.startShuffled()
            onNotify("تشغيل عشوائي للأغاني", 0xFF38BDF8.toInt())
            close()
        }
        sequentialBtn = makePillButton("▶  بالترتيب") { v ->
            bounceView(v)
            onHaptic("pop")
            if (allTracks.isEmpty()) return@makePillButton
            val startIdx = if (filteredIndices.isNotEmpty()) filteredIndices[0] else 0
            media.startSequential(startIdx)
            onNotify("تشغيل القائمة", 0xFF30D158.toInt())
            close()
        }
        refreshBtn = makePillButton("↻", weight = 0.42f) { v ->
            bounceView(v)
            onHaptic("tick")
            if (!media.hasAudioPermission()) {
                triggerPermissionPrompt()
            } else {
                subTitleView.text = "جارٍ البحث عن الأغاني والصور المصغرة..."
                media.scanDeviceMusic(force = true)
            }
        }
        actRow.addView(shuffleBtn)
        actRow.addView(sequentialBtn)
        actRow.addView(refreshBtn)
        panelCard.addView(actRow)

        // Compact Search Bar
        searchInput = EditText(context).apply {
            hint = "ابحث عن أغنية أو فنان..."
            setHintTextColor(Color.argb(120, 255, 255, 255))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding((14f * dens).roundToInt(), (10f * dens).roundToInt(), (14f * dens).roundToInt(), (10f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(20, 255, 255, 255))
                cornerRadius = 14f * dens
                setStroke((0.8f * dens).roundToInt(), Color.argb(30, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (10f * dens).roundToInt()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    applyFilter(s?.toString() ?: "")
                }
            })
        }
        panelCard.addView(searchInput)

        // Permission / Empty Prompt Box
        permissionBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((16f * dens).roundToInt(), (20f * dens).roundToInt(), (16f * dens).roundToInt(), (20f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(20, 255, 255, 255))
                cornerRadius = 20f * dens
                setStroke((1f * dens).roundToInt(), Color.argb(36, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8f * dens).roundToInt()
            }
            visibility = GONE
        }
        val iconBadge = TextView(context).apply {
            text = "♫"
            gravity = Gravity.CENTER
            setTextColor(0xFF93C5FD.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            val s = (54f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { bottomMargin = (10f * dens).roundToInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(36, 56, 189, 248))
            }
        }
        permMsgView = TextView(context).apply {
            text = "لإظهار أغانيك وبوسترات الألبوم المصغرة من جهازك، امنح صلاحية الوصول للموسيقى والصور."
            gravity = Gravity.CENTER
            setTextColor(Color.argb(225, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, 0, 0, (14f * dens).roundToInt())
        }
        permGrantBtn = TextView(context).apply {
            text = "منح صلاحية الموسيقى والصور"
            gravity = Gravity.CENTER
            setTextColor(0xFF0A101D.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding((18f * dens).roundToInt(), (11f * dens).roundToInt(), (18f * dens).roundToInt(), (11f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(0xFF93C5FD.toInt())
                cornerRadius = 14f * dens
            }
            setOnClickListener { v ->
                bounceView(v)
                onHaptic("pop")
                if (!media.hasAudioPermission()) {
                    if (permRequestCount >= 2) onOpenAppSettings() else triggerPermissionPrompt()
                } else {
                    media.scanDeviceMusic(force = true)
                }
            }
        }
        val manualPickBtn = TextView(context).apply {
            text = "أو اختيار ملف صوتي من الجهاز…"
            gravity = Gravity.CENTER
            setTextColor(Color.argb(185, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding((12f * dens).roundToInt(), (12f * dens).roundToInt(), (12f * dens).roundToInt(), (2f * dens).roundToInt())
            setOnClickListener {
                onHaptic("tick")
                onPickManualAudio()
                close()
            }
        }
        permissionBox.addView(iconBadge)
        permissionBox.addView(permMsgView)
        permissionBox.addView(permGrantBtn)
        permissionBox.addView(manualPickBtn)
        panelCard.addView(permissionBox)

        // Lightweight Virtualized ListView (recycles <10 views regardless of song count)
        listView = ListView(context).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            clipToPadding = false
            setPadding(0, 0, 0, (12f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            adapter = trackAdapter
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

    private fun makePillButton(label: String, accent: Boolean = false, weight: Float = 1f, onClick: (View) -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (accent) 0xFFBAE6FD.toInt() else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding((10f * dens).roundToInt(), (10f * dens).roundToInt(), (10f * dens).roundToInt(), (10f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, weight).apply {
                marginEnd = (6f * dens).roundToInt()
            }
            background = GradientDrawable().apply {
                setColor(if (accent) Color.argb(46, 56, 189, 248) else Color.argb(22, 255, 255, 255))
                cornerRadius = 14f * dens
                setStroke((0.9f * dens).roundToInt(), if (accent) Color.argb(120, 125, 211, 252) else Color.argb(34, 255, 255, 255))
            }
            setOnClickListener { onClick(it) }
        }
    }

    private fun bounceView(v: View) {
        v.animate().cancel()
        v.scaleX = 0.94f
        v.scaleY = 0.94f
        v.animate().scaleX(1f).scaleY(1f).setDuration(320L).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun attachDragDismiss(handle: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        var downY = 0f
        var dragging = false
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (!dragging && dy > slop) dragging = true
                    if (dragging) {
                        val ty = max(0f, dy)
                        panelCard.translationY = ty
                        val p = (1f - ty / max(200f, panelCard.height.toFloat())).coerceIn(0.35f, 1f)
                        scrimView.alpha = p
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && panelCard.translationY > 110f * dens) {
                        close()
                    } else {
                        panelCard.animate().translationY(0f).setDuration(320L).setInterpolator(OvershootInterpolator(1.2f)).start()
                        scrimView.animate().alpha(1f).setDuration(240L).start()
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

    fun applySafeInsets(safeTopPx: Int, safeBottomPx: Int) {
        val lp = panelCard.layoutParams as LayoutParams
        lp.topMargin = max((36f * dens).roundToInt(), safeTopPx + (12f * dens).roundToInt())
        lp.bottomMargin = max((18f * dens).roundToInt(), safeBottomPx + (10f * dens).roundToInt())
        panelCard.layoutParams = lp
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        visibility = VISIBLE
        panelCard.requestFocus()
        syncTracks(media.getLibraryTracks())

        if (!media.hasAudioPermission()) {
            triggerPermissionPrompt()
        } else if (!media.hasDeviceTracks() && !media.isScanning()) {
            subTitleView.text = "جارٍ فحص موسيقى الجهاز..."
            media.scanDeviceMusic(force = true)
        }

        scrimView.animate().cancel()
        panelCard.animate().cancel()
        scrimView.alpha = 0f
        scrimView.animate().alpha(1f).setDuration(240L).start()

        val h = resources.displayMetrics.heightPixels.toFloat()
        panelCard.setLayerType(LAYER_TYPE_HARDWARE, null)
        panelCard.translationY = h * 0.24f
        panelCard.scaleX = 0.90f
        panelCard.scaleY = 0.90f
        panelCard.alpha = 0f
        panelCard.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(440L)
            .setInterpolator(OvershootInterpolator(1.06f))
            .withEndAction {
                panelCard.setLayerType(LAYER_TYPE_NONE, null)
            }
            .start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hideKeyboard()
        val h = resources.displayMetrics.heightPixels.toFloat()
        panelCard.setLayerType(LAYER_TYPE_HARDWARE, null)
        scrimView.animate().alpha(0f).setDuration(220L).start()
        panelCard.animate()
            .translationY(h * 0.28f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .alpha(0f)
            .setDuration(250L)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .withEndAction {
                panelCard.setLayerType(LAYER_TYPE_NONE, null)
                visibility = GONE
                panelCard.translationY = 0f
                panelCard.scaleX = 1f
                panelCard.scaleY = 1f
                panelCard.alpha = 1f
                onClosed()
            }
            .start()
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
        shuffleBtn.background = GradientDrawable().apply {
            setColor(if (shuf) Color.argb(88, 56, 189, 248) else Color.argb(42, 56, 189, 248))
            cornerRadius = 14f * dens
            setStroke((1f * dens).roundToInt(), if (shuf) 0xFF7DD3FC.toInt() else Color.argb(110, 125, 211, 252))
        }
        shuffleBtn.text = if (shuf) "⇄  العشوائي مفعّل" else "⇄  تشغيل عشوائي"

        if (!media.hasAudioPermission()) {
            permissionBox.visibility = VISIBLE
            permMsgView.text = "لإظهار أغانيك وبوسترات الألبوم المصغرة من جهازك، امنح صلاحية الوصول للموسيقى والصور."
            permGrantBtn.text = if (permRequestCount >= 2) "فتح إعدادات الصلاحيات" else "منح صلاحية الموسيقى والصور"
            searchInput.visibility = GONE
            listView.visibility = GONE
            subTitleView.text = "الصلاحية مطلوبة للوصول للموسيقى والصور"
        } else if (allTracks.isEmpty()) {
            permissionBox.visibility = VISIBLE
            permMsgView.text = if (media.isScanning()) "جارٍ فحص الصوتيات على جهازك..." else "لم يتم العثور على أغاني (MP3/M4A/FLAC) في ذاكرة الجهاز."
            permGrantBtn.text = "إعادة الفحص"
            searchInput.visibility = GONE
            listView.visibility = GONE
            subTitleView.text = if (media.isScanning()) "جارٍ الفحص..." else "لا توجد ملفات موسيقى"
        } else {
            permissionBox.visibility = GONE
            searchInput.visibility = VISIBLE
            listView.visibility = VISIBLE
            val suffix = if (shuf) " · تبديل عشوائي" else ""
            subTitleView.text = "${allTracks.size} أغنية على جهازك$suffix"
        }
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

    private class RowHolder(
        val card: LinearLayout,
        val bg: GradientDrawable,
        val art: ImageView,
        val title: TextView,
        val artist: TextView,
        val duration: TextView,
        var boundUri: Uri? = null,
        var wasActive: Boolean? = null
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
                val rowBg = GradientDrawable().apply { cornerRadius = 18f * dens }
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutDirection = LAYOUT_DIRECTION_LTR
                    background = rowBg
                    setPadding((10f * dens).roundToInt(), (8f * dens).roundToInt(), (12f * dens).roundToInt(), (8f * dens).roundToInt())
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
                    setTextColor(Color.argb(165, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
                meta.addView(title)
                meta.addView(artist)

                val duration = TextView(context).apply {
                    setTextColor(Color.argb(175, 255, 255, 255))
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
                holder = RowHolder(card, rowBg, art, title, artist, duration)
                rowView.tag = holder
            } else {
                rowView = convertView as FrameLayout
                holder = rowView.tag as RowHolder
            }

            val track = getItem(position)
            val current = media.currentTrack()
            val isActive = current.contentUri != null && current.contentUri == track.contentUri

            if (holder.wasActive != isActive) {
                holder.wasActive = isActive
                holder.bg.setColor(if (isActive) Color.argb(56, 56, 189, 248) else Color.argb(18, 255, 255, 255))
                holder.bg.setStroke(
                    (if (isActive) 1.2f else 0.7f * dens).roundToInt(),
                    if (isActive) Color.argb(145, 125, 211, 252) else Color.argb(24, 255, 255, 255)
                )
            }
            holder.title.text = track.title
            holder.title.setTextColor(if (isActive) 0xFFBAE6FD.toInt() else Color.WHITE)
            holder.artist.text = if (track.albumName.isNotBlank()) "${track.artist} · ${track.albumName}" else track.artist
            holder.duration.text = if (isActive && media.isPlaying()) "▶ ${fmtSec(track.durationSec)}" else fmtSec(track.durationSec)
            holder.duration.setTextColor(if (isActive) 0xFF7DD3FC.toInt() else Color.argb(165, 255, 255, 255))

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
