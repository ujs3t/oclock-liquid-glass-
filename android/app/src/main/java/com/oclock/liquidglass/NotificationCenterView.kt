package com.oclock.liquidglass

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private val onScrollExpandChanged: (Float) -> Unit,
    private val onDragMusicCoupling: (Float) -> Unit,
    private val onHaptic: (String) -> Unit
) : FrameLayout(context) {

    private val dens = resources.displayMetrics.density
    private val containerCol: LinearLayout
    private val headerRow: LinearLayout
    private val headerTitle: TextView
    private val accessPill: TextView
    private val showLessBtn: GlassShowLessPill
    private val clearBtn: GlassClearCircleButton
    private val scrollView: RubberScrollView
    private val listCol: LinearLayout
    private val moreHintPill: GlassMoreHintPill

    private val cardViews = HashMap<String, NotificationCardRow>()
    private var currentItems: List<LockNotification> = emptyList()
    var stackedCollapsed = false
        private set

    var expandFrac = 0f
        private set
    private var restingOffsetPx = 0f
    private var restingVisibleListH = (200f * dens).roundToInt()
    private var isLandscapeCompact = false
    private var expandAnim: ValueAnimator? = null

    var uiInk = Color.WHITE
    var uiDim = Color.argb(205, 255, 255, 255)
    var glassBg = Color.argb(26, 18, 22, 30)

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        clipChildren = true
        clipToPadding = true
        visibility = GONE
        alpha = 0f

        containerCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // Header Row: "Notification Center" + Liquid Glass "Show less" + Circular "X"
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(8f * dens, 0f, 1.5f * dens, Color.argb(92, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6f * dens).roundToInt()
            }
            setOnClickListener {
                onHaptic("pop")
                toggleStackCollapsed()
            }
        }

        accessPill = TextView(context).apply {
            text = "تفعيل إشعارات الهاتف الحقيقية"
            setTextColor(Color.argb(236, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((12f * dens).roundToInt(), (5f * dens).roundToInt(), (12f * dens).roundToInt(), (5f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(56, 18, 22, 32))
                cornerRadius = 999f
                setStroke((0.85f * dens).roundToInt(), Color.argb(85, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START
                bottomMargin = (7f * dens).roundToInt()
            }
            setOnClickListener { v ->
                onHaptic("pop")
                v.animate().cancel()
                v.scaleX = 0.90f; v.scaleY = 0.90f
                v.animate().scaleX(1f).scaleY(1f).setDuration(320L).setInterpolator(OvershootInterpolator(1.8f)).start()
                onRequestAccess()
            }
        }

        val btnSz = (34f * dens).roundToInt()
        showLessBtn = GlassShowLessPill(context, backdrop, perf, getRootShell) {
            onHaptic("pop")
            toggleStackCollapsed()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, btnSz).apply {
                marginEnd = (7f * dens).roundToInt()
            }
        }

        clearBtn = GlassClearCircleButton(context, backdrop, perf, getRootShell) {
            onHaptic("pop")
            animateClearCascade()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
        }

        headerRow.addView(headerTitle)
        headerRow.addView(showLessBtn)
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
            updateCard3DAndPeek()
        }

        listCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, (2f * dens).roundToInt(), 0, (12f * dens).roundToInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        scrollView.addView(listCol)

        moreHintPill = GlassMoreHintPill(context, backdrop, perf, getRootShell) {
            onHaptic("pop")
            if (stackedCollapsed) {
                toggleStackCollapsed()
            } else {
                animateExpandTo(1f)
            }
        }.apply {
            translationZ = 16f * dens
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (27f * dens).roundToInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (4f * dens).roundToInt()
            }
            visibility = GONE
        }

        containerCol.addView(headerRow)
        containerCol.addView(accessPill)
        containerCol.addView(scrollView)
        addView(containerCol)
        addView(moreHintPill)
        refreshPermissionBadge()
    }

    private fun toggleStackCollapsed() {
        stackedCollapsed = !stackedCollapsed
        showLessBtn.setCollapsed(stackedCollapsed)
        if (stackedCollapsed) {
            scrollView.smoothScrollTo(0, 0)
            animateExpandTo(0f)
        }
        applyStackOr3DState(animated = true)
        onStackModeToggled(stackedCollapsed)
    }

    fun configureLayoutBounds(
        expandedTopPx: Int,
        restingTopPx: Int,
        fullHeightPx: Int,
        landscapeCompact: Boolean
    ) {
        isLandscapeCompact = landscapeCompact
        val prevRestingOffset = restingOffsetPx
        restingOffsetPx = if (landscapeCompact) 0f else max(0f, (restingTopPx - expandedTopPx).toFloat())
        val headerEst = headerHeightEstimatePx()
        restingVisibleListH = max((90f * dens).roundToInt(), fullHeightPx - restingOffsetPx.roundToInt() - headerEst)
        if (landscapeCompact) {
            expandFrac = 0f
            containerCol.translationY = 0f
        } else {
            containerCol.translationY = restingOffsetPx * (1f - expandFrac)
        }
        scrollView.setExpandCallbacks(
            canExpand = { !landscapeCompact && !stackedCollapsed && restingOffsetPx > 14f * dens },
            getExpand = { expandFrac },
            setExpand = { f -> applyExpandProgress(f) },
            onRelease = { f, vy -> settleExpandProgress(f, vy) }
        )
        post {
            updateCard3DAndPeek()
            updateMoreHintPill()
        }
    }

    fun collapseScrollIfExpanded() {
        if (expandFrac > 0.02f) {
            scrollView.smoothScrollTo(0, 0)
            animateExpandTo(0f)
        }
    }

    private fun headerHeightEstimatePx(): Int {
        val h = headerRow.height.takeIf { it > 0 } ?: (44f * dens).roundToInt()
        val acc = if (accessPill.visibility == VISIBLE) {
            accessPill.height.takeIf { it > 0 } ?: (32f * dens).roundToInt()
        } else 0
        return h + acc
    }

    private fun applyExpandProgress(frac: Float) {
        val clamped = frac.coerceIn(0f, 1f)
        if (abs(clamped - expandFrac) < 0.0005f) return
        expandFrac = clamped
        containerCol.translationY = restingOffsetPx * (1f - expandFrac)
        onScrollExpandChanged(expandFrac)
        updateCard3DAndPeek()
        updateMoreHintPill()
    }

    private fun settleExpandProgress(curFrac: Float, vyPxPerSec: Float) {
        val target = when {
            vyPxPerSec < -340f * dens -> 1f
            vyPxPerSec > 340f * dens -> 0f
            curFrac >= 0.34f -> 1f
            else -> 0f
        }
        animateExpandTo(target)
    }

    fun animateExpandTo(target: Float) {
        expandAnim?.cancel()
        val start = expandFrac
        if (abs(start - target) < 0.01f) {
            applyExpandProgress(target)
            return
        }
        expandAnim = ValueAnimator.ofFloat(start, target).apply {
            duration = 460L
            interpolator = if (target > start) OvershootInterpolator(1.12f) else DecelerateInterpolator()
            addUpdateListener { a ->
                applyExpandProgress((a.animatedValue as Float).coerceIn(0f, 1f))
            }
            start()
        }
    }

    fun refreshPermissionBadge() {
        accessPill.visibility = if (hub.hasNotificationAccess()) GONE else VISIBLE
    }

    fun applyTheme(ink: Int, dim: Int, gBg: Int) {
        uiInk = ink
        uiDim = dim
        glassBg = gBg
        headerTitle.setTextColor(ink)
        showLessBtn.applyTheme(ink, gBg)
        clearBtn.uiInk = ink
        clearBtn.glassBg = gBg
        clearBtn.invalidate()
        moreHintPill.applyTheme(ink, gBg)
        for (i in 0 until listCol.childCount) {
            (listCol.getChildAt(i) as? NotificationCardRow)?.applyTheme(ink, dim, gBg)
        }
        invalidate()
    }

    fun nudgeFromMusic() {
        if (visibility != VISIBLE || currentItems.isEmpty() || expandFrac > 0.45f) return
        containerCol.animate().cancel()
        val baseTy = restingOffsetPx * (1f - expandFrac)
        containerCol.translationY = baseTy + 5.5f * dens
        containerCol.animate()
            .translationY(baseTy)
            .setDuration(400L)
            .setInterpolator(OvershootInterpolator(1.9f))
            .start()
    }

    fun invalidateCards() {
        showLessBtn.invalidate()
        clearBtn.invalidate()
        moreHintPill.invalidate()
        for (i in 0 until listCol.childCount) {
            listCol.getChildAt(i)?.invalidate()
        }
    }

    fun setNotifications(items: List<LockNotification>, added: LockNotification?, removedId: String?) {
        refreshPermissionBadge()
        showLessBtn.visibility = if (items.size > 1) VISIBLE else GONE
        showLessBtn.setCollapsed(stackedCollapsed)

        if (added == null && removedId == null && visibility == VISIBLE &&
            items.size == currentItems.size && items.isNotEmpty() &&
            items.indices.all { items[it].id == currentItems[it].id && items[it].count == currentItems[it].count }
        ) {
            currentItems = items
            for (item in items) {
                cardViews[item.id]?.bind(item, hub)
            }
            updateMoreHintPill()
            return
        }

        val prevCount = currentItems.size
        currentItems = items

        if (items.isEmpty()) {
            stackedCollapsed = false
            expandFrac = 0f
            containerCol.translationY = 0f
            onScrollExpandChanged(0f)
            moreHintPill.visibility = GONE
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
                        onDragMusicCoupling(-abs(frac) * 4.5f * dens)
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
            row.translationZ = max(0, 32 - index) * 0.35f * dens
            listCol.addView(row)

            if (added != null && item.id == added.id) {
                row.playEntrance3D(delayMs = 0L)
            } else if (added != null && index > 0) {
                row.playShiftDown()
            } else if (prevCount == 0) {
                row.playEntrance3D(delayMs = (index * 50L).coerceAtMost(200L))
            }
        }

        scrollView.isScrollLocked = stackedCollapsed
        if (stackedCollapsed) {
            applyStackOr3DState(animated = added != null || removedId != null)
        } else {
            for (i in 0 until listCol.childCount) {
                val r = listCol.getChildAt(i) as? NotificationCardRow ?: continue
                r.visibility = VISIBLE
            }
            post {
                updateCard3DAndPeek()
                updateMoreHintPill()
            }
        }
    }

    private fun applyStackOr3DState(animated: Boolean) {
        val count = listCol.childCount
        scrollView.isScrollLocked = stackedCollapsed
        if (stackedCollapsed) {
            scrollView.scrollTo(0, 0)
            for (i in 0 until count) {
                val row = listCol.getChildAt(i) as? NotificationCardRow ?: continue
                if (row.isDismissing) continue
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
                val targetRotX = when (i) {
                    0 -> 0f
                    1 -> -7f
                    else -> -13f
                }
                val targetAlpha = when (i) {
                    0 -> 1f
                    1 -> 0.86f
                    else -> 0.62f
                }
                if (animated) {
                    row.animate().cancel()
                    row.animate()
                        .scaleX(targetScale).scaleY(targetScale)
                        .translationY(targetTy)
                        .rotationX(targetRotX)
                        .alpha(targetAlpha)
                        .setDuration(420L)
                        .setInterpolator(OvershootInterpolator(1.35f))
                        .setUpdateListener { row.invalidate() }
                        .start()
                } else {
                    row.scaleX = targetScale; row.scaleY = targetScale
                    row.translationY = targetTy
                    row.rotationX = targetRotX
                    row.alpha = targetAlpha
                    row.invalidate()
                }
            }
        } else {
            for (i in 0 until count) {
                val row = listCol.getChildAt(i) as? NotificationCardRow ?: continue
                if (row.isDismissing) continue
                row.visibility = VISIBLE
            }
            if (animated) {
                post { updateCard3DAndPeek() }
            } else {
                updateCard3DAndPeek()
            }
        }
        updateMoreHintPill()
    }

    private fun updateMoreHintPill() {
        val extra = currentItems.size - 2
        if (isLandscapeCompact || currentItems.size <= 2 || expandFrac > 0.35f) {
            moreHintPill.visibility = GONE
            return
        }
        moreHintPill.visibility = VISIBLE
        moreHintPill.alpha = (1f - expandFrac * 2.8f).coerceIn(0f, 1f)
        moreHintPill.setCount(if (stackedCollapsed) currentItems.size - 1 else extra, stackedCollapsed)
        moreHintPill.invalidate()
    }

    private fun updateCard3DAndPeek() {
        if (stackedCollapsed) return
        val count = listCol.childCount
        if (count == 0) return

        val svW = scrollView.width
        val svH = scrollView.height
        if (svW <= 0 || svH <= 0) return

        val visH = max(
            1f,
            if (isLandscapeCompact) svH.toFloat()
            else (height - containerCol.translationY - scrollView.top - (if (moreHintPill.visibility == VISIBLE) 24f * dens else 4f * dens))
        )
        val scrollY = scrollView.scrollY.toFloat()
        val topZone = 62f * dens
        val botZone = 74f * dens
        val restBlend = (1f - expandFrac * 1.45f).coerceIn(0f, 1f)

        for (i in 0 until count) {
            val row = listCol.getChildAt(i) as? NotificationCardRow ?: continue
            if (row.isDismissing || row.isDragging || row.isEntranceRunning) continue

            // Resting state (expandFrac -> 0): first 2 cards sit cleanly; card 3..n form a 3D stacked peek!
            var restSc = 1f
            var restTy = 0f
            var restRx = 0f
            var restAlpha = 1f
            if (!isLandscapeCompact && restBlend > 0.01f && i >= 2) {
                val depthIdx = (i - 2).coerceAtMost(3)
                val targetSc = when (depthIdx) {
                    0 -> 0.935f
                    1 -> 0.875f
                    else -> 0.82f
                }
                val hEst = row.cardHeightEstimate()
                val targetTy = when (depthIdx) {
                    0 -> -hEst * 0.52f
                    1 -> -hEst * 1.24f
                    else -> -hEst * 1.90f
                }
                val targetRx = when (depthIdx) {
                    0 -> -9f
                    1 -> -14f
                    else -> -18f
                }
                val targetA = when (depthIdx) {
                    0 -> 0.86f
                    1 -> 0.56f
                    else -> 0f
                }
                restSc = 1f + (targetSc - 1f) * restBlend
                restTy = targetTy * restBlend
                restRx = targetRx * restBlend
                restAlpha = 1f + (targetA - 1f) * restBlend
            }

            // Live 3D scroll carousel perspective at top & bottom viewport edges
            val cardTop = row.top - scrollY
            val cardBot = row.bottom - scrollY
            var scrSc = 1f
            var scrRx = 0f
            var scrAlpha = 1f

            if (cardTop < topZone && cardBot > 0f && scrollY > 2f) {
                val tf = ((topZone - cardTop) / max(1f, row.height + topZone)).coerceIn(0f, 1f)
                scrSc = 1f - 0.08f * tf
                scrRx = 14f * tf
                scrAlpha = (1f - 0.65f * tf).coerceIn(0.25f, 1f)
            } else if (expandFrac > 0.1f && cardBot > visH - botZone && cardTop < visH) {
                val bf = ((cardBot - (visH - botZone)) / max(1f, row.height + botZone)).coerceIn(0f, 1f)
                scrSc = 1f - 0.09f * bf
                scrRx = -13f * bf
                scrAlpha = (1f - 0.58f * bf).coerceIn(0.32f, 1f)
            }

            val finalSc = min(restSc, scrSc)
            val finalRx = restRx + scrRx * (1f - restBlend * 0.5f)
            val finalAlpha = min(restAlpha, scrAlpha)

            row.pivotX = row.width * 0.5f
            row.pivotY = row.height * 0.5f
            row.scaleX = finalSc
            row.scaleY = finalSc
            row.translationY = restTy
            row.rotationX = finalRx
            row.alpha = finalAlpha
            if (finalAlpha > 0.03f && cardBot >= -40f && cardTop <= visH + 80f) {
                row.invalidate()
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
                .rotationY(dir * 16f)
                .rotation(dir * 7f)
                .alpha(0f)
                .scaleX(0.86f)
                .scaleY(0.86f)
                .setStartDelay((idx * 38L).coerceAtMost(180L))
                .setDuration(240L)
                .setInterpolator(DecelerateInterpolator())
                .setUpdateListener { row.invalidate() }
                .start()
        }
        postDelayed({
            onClearAll()
        }, (min(rows.size, 5) * 38L + 210L))
    }

    private class RubberScrollView(context: Context) : ScrollView(context) {
        private val dens = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var startRawX = 0f
        private var startRawY = 0f
        private var lastRawY = 0f
        private var dragActive = false
        private var vt: VelocityTracker? = null
        var isScrollLocked = false
        var onPullOverscroll: ((Float) -> Unit)? = null

        private var canExpandFn: () -> Boolean = { false }
        private var getExpandFn: () -> Float = { 0f }
        private var setExpandFn: (Float) -> Unit = {}
        private var onReleaseFn: (Float, Float) -> Unit = { _, _ -> }

        fun setExpandCallbacks(
            canExpand: () -> Boolean,
            getExpand: () -> Float,
            setExpand: (Float) -> Unit,
            onRelease: (Float, Float) -> Unit
        ) {
            canExpandFn = canExpand
            getExpandFn = getExpand
            setExpandFn = setExpand
            onReleaseFn = onRelease
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (isScrollLocked) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    lastRawY = ev.rawY
                    dragActive = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain().apply { addMovement(ev) }
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(ev)
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    val curExp = getExpandFn()
                    if (canExpandFn() && abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.15f) {
                        if ((dy < 0f && curExp < 0.999f) || (dy > 0f && scrollY <= 0 && curExp > 0.001f)) {
                            dragActive = true
                            lastRawY = ev.rawY
                            return true
                        }
                    }
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (isScrollLocked) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawY = ev.rawY
                    lastRawY = ev.rawY
                    dragActive = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain().apply { addMovement(ev) }
                    return super.onTouchEvent(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(ev)
                    val dy = ev.rawY - lastRawY
                    lastRawY = ev.rawY
                    val curExp = getExpandFn()
                    val canExp = canExpandFn()

                    if (canExp && dy < 0f && curExp < 0.999f) {
                        dragActive = true
                        val next = (curExp + (-dy) / (155f * dens)).coerceIn(0f, 1f)
                        setExpandFn(next)
                        return true
                    }
                    if (canExp && dy > 0f && scrollY <= 0 && curExp > 0.001f) {
                        dragActive = true
                        val next = (curExp - dy / (155f * dens)).coerceIn(0f, 1f)
                        setExpandFn(next)
                        return true
                    }
                    if (scrollY <= 0 && curExp <= 0.001f && ev.rawY > startRawY) {
                        onPullOverscroll?.invoke(min(18f * dens, (ev.rawY - startRawY) * 0.14f))
                    }
                    if (dragActive) {
                        dragActive = false
                        val fakeDown = MotionEvent.obtain(ev)
                        fakeDown.action = MotionEvent.ACTION_DOWN
                        super.onTouchEvent(fakeDown)
                        fakeDown.recycle()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.addMovement(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    onPullOverscroll?.invoke(0f)
                    val curExp = getExpandFn()
                    if (canExpandFn() && (dragActive || (curExp > 0.01f && curExp < 0.99f))) {
                        dragActive = false
                        onReleaseFn(curExp, vy)
                        return true
                    }
                    dragActive = false
                }
            }
            return super.onTouchEvent(ev)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private class GlassMoreHintPill(
        context: Context,
        backdrop: LiquidGlassShaders.BackdropState,
        perf: PerfMonitor,
        private val getRootShell: () -> View?,
        private val onClick: () -> Unit
    ) : LinearLayout(context) {
        private val dens = resources.displayMetrics.density
        private val glassHelper = LiquidGlassCardHelper(backdrop, perf, 14f, 12f, 9f, 1.44f, 0.24f)
        private val label: TextView
        var uiInk = Color.WHITE
        var glassBg = Color.argb(30, 18, 22, 30)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setWillNotDraw(false)
            setPadding((12f * dens).roundToInt(), 0, (12f * dens).roundToInt(), 0)
            label = TextView(context).apply {
                setTextColor(uiInk)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                text = "↑ اسحب للأعلى لمزيد"
            }
            addView(label)
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        animate().cancel()
                        animate().scaleX(0.90f).scaleY(0.90f).setDuration(90L).start()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(360L).setInterpolator(OvershootInterpolator(1.9f)).start()
                        onClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        animate().cancel()
                        animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
                        true
                    }
                    else -> false
                }
            }
        }

        fun setCount(extra: Int, collapsed: Boolean) {
            label.text = if (collapsed) {
                "↑ +$extra إشعارات مطوية"
            } else {
                "↑ +$extra إشعارات أخرى • اسحب للأعلى"
            }
        }

        fun applyTheme(ink: Int, gBg: Int) {
            uiInk = ink
            glassBg = gBg
            label.setTextColor(ink)
            invalidate()
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
            val smoked = Color.argb(
                max(36, Color.alpha(glassBg)),
                (Color.red(glassBg) * 0.45f).roundToInt(),
                (Color.green(glassBg) * 0.45f).roundToInt(),
                (Color.blue(glassBg) * 0.50f).roundToInt()
            )
            glassHelper.draw(this, canvas, smoked, ox, oy)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private class GlassShowLessPill(
        context: Context,
        backdrop: LiquidGlassShaders.BackdropState,
        perf: PerfMonitor,
        private val getRootShell: () -> View?,
        private val onClick: () -> Unit
    ) : LinearLayout(context) {
        private val dens = resources.displayMetrics.density
        private val glassHelper = LiquidGlassCardHelper(backdrop, perf, 17f, 14f, 10f, 1.44f, 0.24f)
        private val chevron: TextView
        private val text: TextView
        var uiInk = Color.WHITE
        var glassBg = Color.argb(20, 255, 255, 255)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setWillNotDraw(false)
            setPadding((11f * dens).roundToInt(), 0, (11f * dens).roundToInt(), 0)
            chevron = TextView(context).apply {
                this.text = "⌄"
                setTextColor(uiInk)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (5f * dens).roundToInt()
                    topMargin = (-2f * dens).roundToInt()
                }
            }
            text = TextView(context).apply {
                this.text = "Show less"
                setTextColor(uiInk)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            addView(chevron)
            addView(text)
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        animate().cancel()
                        animate().scaleX(0.88f).scaleY(0.88f).setDuration(90L).start()
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

        fun setCollapsed(collapsed: Boolean) {
            text.text = if (collapsed) "Show more" else "Show less"
            chevron.animate().rotation(if (collapsed) 180f else 0f).setDuration(300L).setInterpolator(OvershootInterpolator(1.6f)).start()
        }

        fun applyTheme(ink: Int, gBg: Int) {
            uiInk = ink
            glassBg = gBg
            chevron.setTextColor(ink)
            text.setTextColor(ink)
            invalidate()
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
        }
    }

    @SuppressLint("ClickableViewAccessibility")
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
        private val glassHelper = LiquidGlassCardHelper(backdrop, perf, 24f, 18f, 13f, 1.46f, 0.25f)
        private val specularRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.85f * dens
        }
        private val rectF = RectF()
        private var cachedW = 0
        private var rimShader: LinearGradient? = null

        private val iconView: ImageView
        private val countBadge: TextView
        private val titleText: TextView
        private val bodyText: TextView
        private val timeText: TextView

        private var item: LockNotification? = null
        private var uiInk = Color.WHITE
        private var uiDim = Color.argb(212, 255, 255, 255)
        private var glassBg = Color.argb(26, 18, 22, 30)

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var downRawX = 0f
        private var downRawY = 0f
        private var vt: VelocityTracker? = null
        var isDragging = false
            private set
        var isDismissing = false
        var isEntranceRunning = false
            private set

        init {
            setWillNotDraw(false)
            layoutDirection = LAYOUT_DIRECTION_LTR
            clipChildren = false
            clipToPadding = false
            cameraDistance = 12000f * dens
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8f * dens).roundToInt()
            }
            setPadding((13f * dens).roundToInt(), (11f * dens).roundToInt(), (14f * dens).roundToInt(), (11f * dens).roundToInt())

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }

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
            timeText.setTextColor(Color.argb(185, Color.red(ink), Color.green(ink), Color.blue(ink)))
            cachedW = 0
            invalidate()
        }

        fun cardHeightEstimate(): Float = if (height > 0) height.toFloat() else (68f * dens)

        fun playEntrance3D(delayMs: Long) {
            isEntranceRunning = true
            animate().cancel()
            alpha = 0f
            scaleX = 0.80f
            scaleY = 0.80f
            rotationX = -22f
            translationY = -18f * dens
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .rotationX(0f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(490L)
                .setInterpolator(OvershootInterpolator(1.75f))
                .setUpdateListener { invalidate() }
                .withEndAction {
                    isEntranceRunning = false
                    invalidate()
                }
                .start()
        }

        fun playShiftDown() {
            animate().cancel()
            translationY = -44f * dens
            animate()
                .translationY(0f)
                .setDuration(420L)
                .setInterpolator(OvershootInterpolator(1.25f))
                .setUpdateListener { invalidate() }
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
                    pivotX = width * 0.5f
                    pivotY = height * 0.5f
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
                        val dragFrac = (abs(clampedDx) / w).coerceIn(0f, 1.25f)
                        // Stretch horizontally from trailing edge like stretchy Liquid Glass with 3D tilt!
                        pivotX = if (clampedDx >= 0f) w * 0.15f else w * 0.85f
                        pivotY = height * 0.5f
                        translationX = clampedDx
                        rotationY = (clampedDx / w * 13f).coerceIn(-13f, 13f)
                        rotation = (clampedDx / w * 4.5f).coerceIn(-4.5f, 4.5f)
                        scaleX = 1f + min(0.14f, dragFrac * 0.22f)
                        scaleY = 1f - min(0.06f, dragFrac * 0.09f)
                        alpha = (1f - dragFrac * 0.62f).coerceIn(0.32f, 1f)
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
                            dismissWithSpring3D(cur, dir = if (translationX >= 0f || vx > 0f) 1f else -1f)
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
                            pivotX = width * 0.5f
                            pivotY = height * 0.5f
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
                .rotationY(0f)
                .rotation(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(480L)
                .setInterpolator(OvershootInterpolator(2.15f))
                .setUpdateListener { invalidate() }
                .withEndAction {
                    pivotX = width * 0.5f
                    invalidate()
                }
                .start()
        }

        private fun dismissWithSpring3D(cur: LockNotification, dir: Float) {
            isDismissing = true
            onHaptic("pop")
            val w = max(320f, width.toFloat())
            animate().cancel()
            animate()
                .translationX(dir * w * 1.18f)
                .rotationY(dir * 20f)
                .rotation(dir * 7f)
                .scaleX(1.14f)
                .scaleY(0.84f)
                .alpha(0f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .setUpdateListener { invalidate() }
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

            // Always render genuine Liquid Glass refraction + bezel on every state (scroll, 3D stack, swipe)
            val rootShell = getRootShell()
            var ox = x
            var oy = y
            var p = parent as? View
            while (p != null && p !== rootShell) {
                ox += p.x - p.scrollX
                oy += p.y - p.scrollY
                p = p.parent as? View
            }

            val smokedTint = Color.argb(
                max(42, Color.alpha(glassBg)),
                (Color.red(glassBg) * 0.40f).roundToInt(),
                (Color.green(glassBg) * 0.42f).roundToInt(),
                (Color.blue(glassBg) * 0.48f).roundToInt()
            )
            glassHelper.draw(this, canvas, smokedTint, ox, oy)

            // Crisp specular edge highlight so stacked 3D cards separate sharply
            if (cachedW != width || rimShader == null) {
                cachedW = width
                rimShader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(
                        Color.argb(122, 255, 255, 255),
                        Color.argb(26, 255, 255, 255),
                        Color.argb(88, 255, 255, 255)
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            val rad = 24f * dens
            rectF.set(0.55f * dens, 0.55f * dens, w - 0.55f * dens, h - 0.55f * dens)
            specularRim.shader = rimShader
            canvas.drawRoundRect(rectF, rad, rad, specularRim)
            specularRim.shader = null
        }
    }
}
