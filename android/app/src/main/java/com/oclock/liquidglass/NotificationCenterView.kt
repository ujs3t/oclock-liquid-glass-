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
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class NotificationCenterView(
    context: Context,
    private val hub: NotificationHub,
    private val backdrop: LiquidGlassShaders.BackdropState,
    private val perf: PerfMonitor,
    private val getRootShell: () -> View?,
    private val onDismissNotification: (LockNotification) -> Unit,
    private val onClearAll: () -> Unit,
    private val onCardTapped: (LockNotification) -> Unit,
    private val onRequestAccess: () -> Unit,
    private val onStackModeToggled: (Boolean) -> Unit,
    private val onDragMusicCoupling: (Float) -> Unit,
    private val onHaptic: (String) -> Unit
) : FrameLayout(context) {

    private val dens = resources.displayMetrics.density
    private val containerCol: LinearLayout
    private val headerRow: LinearLayout
    private val headerTitle: TextView
    private val accessPill: TextView
    private val clearBtn: GlassClearCircleButton
    private val scrollView: RubberScrollView
    private val listCol: LinearLayout

    private val cardViews = HashMap<String, NotificationCardRow>()
    private var currentItems: List<LockNotification> = emptyList()
    var stackedCollapsed = false
        private set

    var uiInk = Color.WHITE
    var uiDim = Color.argb(198, 255, 255, 255)
    var glassBg = Color.argb(18, 255, 255, 255)

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        clipChildren = false
        clipToPadding = false
        visibility = GONE
        alpha = 0f

        containerCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // Header Row: "Notification Center" + subtle permission pill + circular Liquid Glass "X"
        headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding((4f * dens).roundToInt(), 0, (4f * dens).roundToInt(), (8f * dens).roundToInt())
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        headerTitle = TextView(context).apply {
            text = "Notification Center"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(8f * dens, 0f, 1.5f * dens, Color.argb(86, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                onHaptic("pop")
                stackedCollapsed = !stackedCollapsed
                applyStackPresentation(animated = true)
                onStackModeToggled(stackedCollapsed)
            }
        }

        accessPill = TextView(context).apply {
            text = "تفعيل الإشعارات"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding((10f * dens).roundToInt(), (4f * dens).roundToInt(), (10f * dens).roundToInt(), (4f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(56, 255, 255, 255))
                cornerRadius = 999f
                setStroke((0.8f * dens).roundToInt(), Color.argb(90, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8f * dens).roundToInt()
            }
            setOnClickListener { v ->
                onHaptic("pop")
                v.animate().cancel()
                v.scaleX = 0.86f; v.scaleY = 0.86f
                v.animate().scaleX(1f).scaleY(1f).setDuration(320L).setInterpolator(OvershootInterpolator(1.8f)).start()
                onRequestAccess()
            }
        }

        val btnSz = (34f * dens).roundToInt()
        clearBtn = GlassClearCircleButton(context, backdrop, perf, getRootShell) {
            onHaptic("pop")
            animateClearCascade()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
        }

        headerRow.addView(headerTitle)
        headerRow.addView(accessPill)
        headerRow.addView(clearBtn)

        scrollView = RubberScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            onPullOverscroll = { pullDy -> onDragMusicCoupling(pullDy) }
        }
        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            updateBottomPerspective()
        }

        listCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, (2f * dens).roundToInt(), 0, (6f * dens).roundToInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        scrollView.addView(listCol)

        containerCol.addView(headerRow)
        containerCol.addView(scrollView)
        addView(containerCol)
        refreshPermissionBadge()
    }

    fun refreshPermissionBadge() {
        accessPill.visibility = if (hub.hasNotificationAccess()) GONE else VISIBLE
    }

    fun applyTheme(ink: Int, dim: Int, gBg: Int) {
        uiInk = ink
        uiDim = dim
        glassBg = gBg
        headerTitle.setTextColor(ink)
        clearBtn.uiInk = ink
        clearBtn.glassBg = gBg
        clearBtn.invalidate()
        for (i in 0 until listCol.childCount) {
            (listCol.getChildAt(i) as? NotificationCardRow)?.applyTheme(ink, dim, gBg)
        }
        invalidate()
    }

    fun nudgeFromMusic() {
        if (visibility != VISIBLE || currentItems.isEmpty()) return
        animate().cancel()
        translationY = 5f * dens
        animate()
            .translationY(0f)
            .setDuration(400L)
            .setInterpolator(OvershootInterpolator(1.9f))
            .start()
    }

    fun setMaxListHeightPx(maxH: Int) {
        val svLp = scrollView.layoutParams as LinearLayout.LayoutParams
        val targetH = if (stackedCollapsed) {
            min(maxH, (108f * dens).roundToInt())
        } else {
            maxH
        }
        if (svLp.height != targetH) {
            svLp.height = max(0, targetH)
            svLp.weight = 0f
            scrollView.layoutParams = svLp
            post { updateBottomPerspective() }
        }
    }

    fun invalidateCards() {
        clearBtn.invalidate()
        for (i in 0 until listCol.childCount) {
            listCol.getChildAt(i)?.invalidate()
        }
    }

    fun setNotifications(items: List<LockNotification>, added: LockNotification?, removedId: String?) {
        refreshPermissionBadge()
        if (added == null && removedId == null && visibility == VISIBLE &&
            items.size == currentItems.size && items.isNotEmpty() &&
            items.indices.all { items[it].id == currentItems[it].id && items[it].count == currentItems[it].count }
        ) {
            currentItems = items
            for (item in items) {
                cardViews[item.id]?.bind(item, hub)
            }
            return
        }
        val prevCount = currentItems.size
        currentItems = items

        if (items.isEmpty()) {
            stackedCollapsed = false
            cardViews.clear()
            listCol.removeAllViews()
            if (visibility == VISIBLE) {
                animate().cancel()
                animate()
                    .alpha(0f)
                    .translationY(18f * dens)
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(240L)
                    .withEndAction {
                        if (currentItems.isEmpty()) visibility = GONE
                    }
                    .start()
            }
            return
        }

        if (visibility != VISIBLE || prevCount == 0) {
            visibility = VISIBLE
            animate().cancel()
            alpha = 0f
            translationY = 22f * dens
            scaleX = 0.94f
            scaleY = 0.94f
            animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(460L)
                .setInterpolator(OvershootInterpolator(1.35f))
                .withEndAction(null)
                .start()
        }

        // Remove stale views cleanly (unless already animating out)
        val keepIds = HashSet<String>(items.size)
        items.forEach { keepIds.add(it.id) }
        val toRemove = cardViews.keys.filter { !keepIds.contains(it) }
        for (id in toRemove) {
            cardViews.remove(id)?.let { row ->
                if (!row.isDismissing) {
                    listCol.removeView(row)
                }
            }
        }

        // Build or reorder active cards
        val existingById = HashMap<String, NotificationCardRow>(cardViews)
        listCol.removeAllViews()
        cardViews.clear()

        for ((index, item) in items.withIndex()) {
            val row = existingById[item.id]?.also { it.bind(item, hub) }
                ?: NotificationCardRow(
                    context = context,
                    hub = hub,
                    backdrop = backdrop,
                    perf = perf,
                    getRootShell = getRootShell,
                    onSwipeProgress = { frac ->
                        onDragMusicCoupling(-abs(frac) * 4f * dens)
                    },
                    onDismiss = { dismissed ->
                        onDismissNotification(dismissed)
                    },
                    onTap = { tapped ->
                        onCardTapped(tapped)
                    },
                    onHaptic = onHaptic
                ).apply {
                    bind(item, hub)
                    applyTheme(uiInk, uiDim, glassBg)
                }

            cardViews[item.id] = row
            listCol.addView(row)

            if (added != null && item.id == added.id) {
                row.playEntrance(delayMs = 0L)
            } else if (added != null && index > 0) {
                row.playShiftDown()
            } else if (prevCount == 0) {
                row.playEntrance(delayMs = (index * 45L).coerceAtMost(200L))
            }
        }

        if (stackedCollapsed) {
            applyStackPresentation(animated = added != null || removedId != null)
        } else {
            scrollView.isScrollLocked = false
            for (i in 0 until listCol.childCount) {
                val r = listCol.getChildAt(i) as? NotificationCardRow ?: continue
                r.visibility = VISIBLE
                if (removedId != null && !r.isDismissing) {
                    r.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(1f)
                        .setDuration(360L).setInterpolator(OvershootInterpolator(1.3f)).start()
                }
            }
        }
        post { updateBottomPerspective() }
    }

    private fun applyStackPresentation(animated: Boolean) {
        val count = listCol.childCount
        for (i in 0 until count) {
            val row = listCol.getChildAt(i) as? NotificationCardRow ?: continue
            if (row.isDismissing) continue
            if (stackedCollapsed) {
                val show = i < 3
                row.visibility = if (show) VISIBLE else GONE
                if (!show) continue
                val targetScale = when (i) {
                    0 -> 1f
                    1 -> 0.94f
                    else -> 0.88f
                }
                val targetTy = when (i) {
                    0 -> 0f
                    1 -> -row.cardHeightEstimate() * 0.78f
                    else -> -row.cardHeightEstimate() * 1.56f
                }
                val targetAlpha = when (i) {
                    0 -> 1f
                    1 -> 0.78f
                    else -> 0.50f
                }
                if (animated) {
                    row.animate().cancel()
                    row.animate()
                        .scaleX(targetScale).scaleY(targetScale)
                        .translationY(targetTy)
                        .alpha(targetAlpha)
                        .setDuration(420L)
                        .setInterpolator(OvershootInterpolator(1.35f))
                        .start()
                } else {
                    row.scaleX = targetScale; row.scaleY = targetScale
                    row.translationY = targetTy
                    row.alpha = targetAlpha
                }
            } else {
                row.visibility = VISIBLE
                if (animated) {
                    row.animate().cancel()
                    row.animate()
                        .scaleX(1f).scaleY(1f)
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(420L)
                        .setInterpolator(OvershootInterpolator(1.3f))
                        .start()
                } else {
                    row.scaleX = 1f; row.scaleY = 1f
                    row.translationY = 0f
                    row.alpha = 1f
                }
            }
        }
        scrollView.isScrollLocked = stackedCollapsed
        if (stackedCollapsed) scrollView.scrollTo(0, 0)
        post { updateBottomPerspective() }
    }

    private fun updateBottomPerspective() {
        if (stackedCollapsed) return
        val svH = scrollView.height
        if (svH <= 0) return
        val scrollY = scrollView.scrollY
        val edgeZone = 74f * dens
        for (i in 0 until listCol.childCount) {
            val row = listCol.getChildAt(i) as? NotificationCardRow ?: continue
            if (row.isDismissing || row.isDragging) continue
            val cardTop = row.top - scrollY
            val cardBot = row.bottom - scrollY
            val overflow = cardBot - svH
            if (overflow > -edgeZone && cardTop < svH) {
                val frac = ((overflow + edgeZone) / (edgeZone + max(1, row.height))).coerceIn(0f, 1f)
                val sc = 1f - 0.08f * frac
                row.scaleX = sc
                row.scaleY = sc
                row.translationY = -frac * 8f * dens
                row.alpha = (1f - 0.55f * frac).coerceIn(0.32f, 1f)
            } else if (cardBot <= svH - edgeZone) {
                if (row.scaleX != 1f || row.alpha != 1f || row.translationY != 0f) {
                    row.scaleX = 1f
                    row.scaleY = 1f
                    row.translationY = 0f
                    row.alpha = 1f
                }
            }
        }
    }

    private fun animateClearCascade() {
        val count = listCol.childCount
        if (count == 0) {
            onClearAll()
            return
        }
        val rows = ArrayList<NotificationCardRow>(count)
        for (i in 0 until count) {
            (listCol.getChildAt(i) as? NotificationCardRow)?.let { rows.add(it) }
        }
        rows.forEachIndexed { idx, row ->
            row.isDismissing = true
            row.animate().cancel()
            val dir = if (idx % 2 == 0) 1f else -1f
            row.animate()
                .translationX(dir * (width.coerceAtLeast(320) * 0.95f))
                .rotation(dir * 7f)
                .alpha(0f)
                .scaleX(0.86f)
                .scaleY(0.86f)
                .setStartDelay((idx * 38L).coerceAtMost(180L))
                .setDuration(230L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        postDelayed({
            onClearAll()
        }, (min(rows.size, 5) * 38L + 200L))
    }

    private class RubberScrollView(context: Context) : ScrollView(context) {
        private var startY = 0f
        var isScrollLocked = false
        var onPullOverscroll: ((Float) -> Unit)? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (isScrollLocked) return false
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) startY = ev.rawY
            return super.onInterceptTouchEvent(ev)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (isScrollLocked) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (scrollY == 0) {
                        val dy = ev.rawY - startY
                        if (dy > 0f) {
                            onPullOverscroll?.invoke(min(18f, dy * 0.14f))
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onPullOverscroll?.invoke(0f)
                }
            }
            return super.onTouchEvent(ev)
        }
    }

    private class GlassClearCircleButton(
        context: Context,
        backdrop: LiquidGlassShaders.BackdropState,
        perf: PerfMonitor,
        private val getRootShell: () -> View?,
        private val onClick: () -> Unit
    ) : View(context) {
        private val dens = resources.displayMetrics.density
        private val glassHelper = LiquidGlassCardHelper(backdrop, perf, 17f, 14f, 10f, 1.44f, 0.24f)
        private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.9f * dens
            strokeCap = Paint.Cap.ROUND
        }
        var uiInk = Color.WHITE
        var glassBg = Color.argb(20, 255, 255, 255)

        init {
            setWillNotDraw(false)
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        animate().cancel()
                        animate().scaleX(0.84f).scaleY(0.84f).setDuration(90L).start()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(360L).setInterpolator(OvershootInterpolator(2.0f)).start()
                        onClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(240L).start()
                        true
                    }
                    else -> false
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val rootShell = getRootShell()
            var ox = x
            var oy = y
            var p = parent as? View
            while (p != null && p !== rootShell) {
                ox += p.x - p.scrollX
                oy += p.y - p.scrollY
                p = p.parent as? View
            }
            glassHelper.draw(this, canvas, glassBg, ox, oy)
            xPaint.color = uiInk
            val cx = width * 0.5f
            val cy = height * 0.5f
            val r = 5.2f * dens
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, xPaint)
            canvas.drawLine(cx + r, cy - r, cx - r, cy + r, xPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private class NotificationCardRow(
        context: Context,
        private var hub: NotificationHub,
        backdrop: LiquidGlassShaders.BackdropState,
        perf: PerfMonitor,
        private val getRootShell: () -> View?,
        private val onSwipeProgress: (Float) -> Unit,
        private val onDismiss: (LockNotification) -> Unit,
        private val onTap: (LockNotification) -> Unit,
        private val onHaptic: (String) -> Unit
    ) : FrameLayout(context) {

        private val dens = resources.displayMetrics.density
        private val glassHelper = LiquidGlassCardHelper(backdrop, perf, 24f, 16f, 11f, 1.44f, 0.22f)
        private val fastPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.05f * dens
        }
        private val rectF = RectF()
        private var cachedW = 0
        private var cachedH = 0
        private var fastFillShader: LinearGradient? = null
        private var fastRimShader: LinearGradient? = null

        private val iconView: ImageView
        private val countBadge: TextView
        private val titleText: TextView
        private val bodyText: TextView
        private val timeText: TextView

        private var item: LockNotification? = null
        private var uiInk = Color.WHITE
        private var uiDim = Color.argb(205, 255, 255, 255)
        private var glassBg = Color.argb(18, 255, 255, 255)

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var downRawX = 0f
        private var downRawY = 0f
        private var vt: VelocityTracker? = null
        var isDragging = false
            private set
        var isDismissing = false

        init {
            setWillNotDraw(false)
            layoutDirection = LAYOUT_DIRECTION_LTR
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8f * dens).roundToInt()
            }
            setPadding((13f * dens).roundToInt(), (11f * dens).roundToInt(), (14f * dens).roundToInt(), (11f * dens).roundToInt())

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }

            // App Icon + optional unread count badge (matches screenshot)
            val iconBox = FrameLayout(context).apply {
                val boxSz = (44f * dens).roundToInt()
                layoutParams = LinearLayout.LayoutParams(boxSz, boxSz).apply { marginEnd = (11f * dens).roundToInt() }
                clipChildren = false
                clipToPadding = false
            }
            iconView = ImageView(context).apply {
                val sz = (39f * dens).roundToInt()
                layoutParams = LayoutParams(sz, sz, Gravity.START or Gravity.CENTER_VERTICAL)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, 10.5f * dens)
                    }
                }
            }
            countBadge = TextView(context).apply {
                setTextColor(Color.argb(235, 16, 20, 28))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                gravity = Gravity.CENTER
                minWidth = (17f * dens).roundToInt()
                setPadding((4f * dens).roundToInt(), 0, (4f * dens).roundToInt(), 0)
                background = GradientDrawable().apply {
                    setColor(Color.argb(242, 244, 246, 250))
                    cornerRadius = 999f
                }
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (17f * dens).roundToInt(), Gravity.TOP or Gravity.END)
                visibility = GONE
            }
            iconBox.addView(iconView)
            iconBox.addView(countBadge)

            // Title + Message column
            val meta = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (8f * dens).roundToInt()
                }
            }
            titleText = TextView(context).apply {
                setTextColor(uiInk)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            bodyText = TextView(context).apply {
                setTextColor(uiDim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            meta.addView(titleText)
            meta.addView(bodyText)

            // Timestamp
            timeText = TextView(context).apply {
                setTextColor(Color.argb(185, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP
                    topMargin = (2f * dens).roundToInt()
                }
            }

            row.addView(iconBox)
            row.addView(meta)
            row.addView(timeText)
            addView(row)
        }

        fun bind(data: LockNotification, hubRef: NotificationHub) {
            hub = hubRef
            item = data
            titleText.text = data.title
            bodyText.text = data.text
            timeText.text = NotificationHub.formatRelativeTime(data.timestampMs)
            val iconSz = (39f * dens).roundToInt()
            iconView.setImageBitmap(hub.getAppIcon(data.packageName, iconSz))
            if (data.count > 1) {
                countBadge.visibility = VISIBLE
                countBadge.text = data.count.toString()
            } else {
                countBadge.visibility = GONE
            }
        }

        fun applyTheme(ink: Int, dim: Int, gBg: Int) {
            uiInk = ink
            uiDim = dim
            glassBg = gBg
            titleText.setTextColor(ink)
            bodyText.setTextColor(dim)
            timeText.setTextColor(Color.argb(180, Color.red(ink), Color.green(ink), Color.blue(ink)))
            cachedW = 0
            invalidate()
        }

        fun cardHeightEstimate(): Float = if (height > 0) height.toFloat() else (68f * dens)

        fun playEntrance(delayMs: Long) {
            animate().cancel()
            alpha = 0f
            scaleX = 0.84f
            scaleY = 0.84f
            translationY = -20f * dens
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(460L)
                .setInterpolator(OvershootInterpolator(1.65f))
                .start()
        }

        fun playShiftDown() {
            animate().cancel()
            translationY = -44f * dens
            animate()
                .translationY(0f)
                .setDuration(420L)
                .setInterpolator(OvershootInterpolator(1.25f))
                .start()
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (isDismissing) return false
            val cur = item ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    isDragging = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain().apply { addMovement(ev) }
                    animate().cancel()
                    animate().scaleX(0.972f).scaleY(0.962f).setDuration(110L).start()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(ev)
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!isDragging && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.1f) {
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isDragging) {
                        val w = max(1f, width.toFloat())
                        val clampedDx = if (cur.isDismissible) dx else (dx * 0.22f)
                        translationX = clampedDx
                        rotation = (clampedDx / w * 6.5f).coerceIn(-6.5f, 6.5f)
                        val dragFrac = abs(clampedDx) / w
                        scaleX = 0.985f + min(0.025f, dragFrac * 0.03f)
                        scaleY = 1f - min(0.05f, dragFrac * 0.07f)
                        alpha = (1f - dragFrac * 0.72f).coerceIn(0.28f, 1f)
                        onSwipeProgress(dragFrac.coerceIn(0f, 1f))
                        invalidate()
                    } else if (abs(dy) > touchSlop) {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.addMovement(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    onSwipeProgress(0f)

                    if (isDragging) {
                        isDragging = false
                        val w = max(1f, width.toFloat())
                        val shouldDismiss = cur.isDismissible &&
                            (abs(translationX) >= w * 0.30f || abs(vx) >= 850f * dens) &&
                            ev.actionMasked == MotionEvent.ACTION_UP
                        if (shouldDismiss) {
                            dismissWithSpring(cur, dir = if (translationX >= 0f || vx > 0f) 1f else -1f)
                        } else {
                            snapBackRubbery()
                        }
                    } else {
                        if (ev.actionMasked == MotionEvent.ACTION_UP &&
                            abs(ev.rawX - downRawX) < touchSlop &&
                            abs(ev.rawY - downRawY) < touchSlop
                        ) {
                            onHaptic("pop")
                            animate().cancel()
                            scaleX = 0.94f; scaleY = 0.94f
                            animate().scaleX(1f).scaleY(1f).setDuration(380L).setInterpolator(OvershootInterpolator(2.0f)).start()
                            onTap(cur)
                        } else {
                            snapBackRubbery()
                        }
                    }
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        private fun snapBackRubbery() {
            animate().cancel()
            animate()
                .translationX(0f)
                .rotation(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(460L)
                .setInterpolator(OvershootInterpolator(2.0f))
                .withEndAction { invalidate() }
                .start()
        }

        private fun dismissWithSpring(cur: LockNotification, dir: Float) {
            isDismissing = true
            onHaptic("pop")
            val w = max(320f, width.toFloat())
            animate().cancel()
            animate()
                .translationX(dir * w * 1.15f)
                .rotation(dir * 9f)
                .alpha(0f)
                .scaleY(0.88f)
                .setDuration(210L)
                .setInterpolator(DecelerateInterpolator())
                .start()

            val startH = max(1, height)
            val lp = layoutParams as LinearLayout.LayoutParams
            val startMargin = lp.bottomMargin
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 230L
                interpolator = DecelerateInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    lp.height = (startH * f).roundToInt()
                    lp.bottomMargin = (startMargin * f).roundToInt()
                    layoutParams = lp
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onDismiss(cur)
                    }
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return
            val rad = 24f * dens

            val useAgsl = Build.VERSION.SDK_INT >= 33 && !isDragging && abs(translationX) < 1.5f && scaleX > 0.96f && alpha > 0.92f
            if (useAgsl) {
                val rootShell = getRootShell()
                var ox = x
                var oy = y
                var p = parent as? View
                while (p != null && p !== rootShell) {
                    ox += p.x - p.scrollX
                    oy += p.y - p.scrollY
                    p = p.parent as? View
                }
                glassHelper.draw(this, canvas, glassBg, ox, oy)
            } else {
                // Lightweight cached frosted Liquid Glass surface during swipe / API 26-32
                if (cachedW != width || cachedH != height || fastFillShader == null) {
                    cachedW = width
                    cachedH = height
                    fastFillShader = LinearGradient(
                        0f, 0f, 0f, h,
                        intArrayOf(Color.argb(88, 36, 40, 50), Color.argb(72, 18, 22, 30)),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    fastRimShader = LinearGradient(
                        0f, 0f, w, h,
                        intArrayOf(
                            Color.argb(145, 255, 255, 255),
                            Color.argb(38, 255, 255, 255),
                            Color.argb(110, 255, 255, 255)
                        ),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                rectF.set(0.6f * dens, 0.6f * dens, w - 0.6f * dens, h - 0.6f * dens)
                fastPaint.shader = fastFillShader
                canvas.drawRoundRect(rectF, rad, rad, fastPaint)
                fastPaint.shader = null
                rimPaint.shader = fastRimShader
                canvas.drawRoundRect(rectF, rad, rad, rimPaint)
                rimPaint.shader = null
            }
        }
    }
}
