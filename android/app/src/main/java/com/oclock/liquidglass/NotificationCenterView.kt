package com.oclock.liquidglass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    private val onScrollExpandChanged: (Float) -> Unit,
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
    private val moreHintPill: GlassMoreHintPill
    private val dismissLayer: FrameLayout

    private val cardViews = HashMap<String, NotificationCardRow>()
    private var currentItems: List<LockNotification> = emptyList()

    var expandFrac = 0f
        private set
    var restingOffsetPx = 0f
        private set
    private var restingVisibleListH = (118f * dens).roundToInt()
    private var isLandscapeCompact = false
    private var expandAnim: ValueAnimator? = null
    private val iosEase = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    // Smooth stacked promotion & swipe-react state
    private var activeSwipeIndex = -1
    private var swipePeekAdvance = 0f
    private var swipePeekAnim: ValueAnimator? = null
    private var deckShift = 0f
    private var heightCompOffset = 0f
    private var deckPromoteAnim: ValueAnimator? = null
    private var expandGapShift = 0f
    private var expandGapStartIdx = Int.MAX_VALUE
    private var expandGapAnim: ValueAnimator? = null
    private var hidingOnEmpty = false

    var uiInk = Color.WHITE
    var uiDim = Color.argb(205, 255, 255, 255)
    var glassBg = Color.argb(26, 18, 22, 30)

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
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // Header Row: "Notification Center" + Circular "X" clear button
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
                if (currentItems.size > 1 && restingOffsetPx > 14f * dens) {
                    onHaptic("pop")
                    if (expandFrac > 0.45f) {
                        collapseScrollIfExpanded()
                    } else {
                        animateExpandTo(1f)
                    }
                }
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
        clearBtn = GlassClearCircleButton(context, backdrop, perf, getRootShell) {
            onHaptic("pop")
            animateClearCascade()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(btnSz, btnSz)
        }

        headerRow.addView(headerTitle)
        headerRow.addView(clearBtn)

        scrollView = RubberScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            onPullOverscroll = { pullDy -> onDragMusicCoupling(pullDy) }
            onRubberChanged = { updateCard3DAndPeek() }
            onInteractionStart = { expandAnim?.cancel() }
            getExpandTravelPx = { max(220f * dens, restingOffsetPx) }
        }
        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            updateCard3DAndPeek()
        }
        scrollView.addOnLayoutChangeListener { _, _, top, _, bot, _, oTop, _, oBot ->
            if ((bot - top) != (oBot - oTop)) {
                scrollView.post {
                    updateCard3DAndPeek()
                    updateMoreHintPill()
                }
            }
        }

        listCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, (2f * dens).roundToInt(), 0, (240f * dens).roundToInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        listCol.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateCard3DAndPeek()
            updateMoreHintPill()
        }
        scrollView.addView(listCol)

        moreHintPill = GlassMoreHintPill(context, backdrop, perf, getRootShell) {
            onHaptic("pop")
            animateExpandTo(1f)
        }.apply {
            translationZ = 24f * dens
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (25f * dens).roundToInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            visibility = GONE
        }

        dismissLayer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        containerCol.addView(headerRow)
        containerCol.addView(accessPill)
        containerCol.addView(scrollView)
        addView(containerCol)
        addView(moreHintPill)
        addView(dismissLayer)
        refreshPermissionBadge()
    }

    fun configureLayoutBounds(
        expandedTopPx: Int,
        restingTopPx: Int,
        fullHeightPx: Int,
        landscapeCompact: Boolean
    ) {
        isLandscapeCompact = landscapeCompact
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
            canExpand = { !landscapeCompact && currentItems.isNotEmpty() && restingOffsetPx > 14f * dens },
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
            scrollView.smoothScrollToTop()
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
        if (abs(clamped - expandFrac) < 0.0004f) return
        expandFrac = clamped
        // Strict 1:1 linear mapping with expandFrac so slow finger drags never jump ahead!
        containerCol.translationY = restingOffsetPx * (1f - expandFrac)
        onScrollExpandChanged(expandFrac)
        updateCard3DAndPeek()
        updateMoreHintPill()
        scrollView.invalidate()
    }

    private fun settleExpandProgress(curFrac: Float, vyPxPerSec: Float) {
        val target = when {
            vyPxPerSec < -260f * dens -> 1f
            vyPxPerSec > 260f * dens -> 0f
            curFrac >= 0.34f -> 1f
            else -> 0f
        }
        animateExpandTo(target, vyPxPerSec)
    }

    fun animateExpandTo(target: Float, vyPxPerSec: Float = 0f) {
        expandAnim?.cancel()
        val start = expandFrac
        val dist = abs(target - start)
        if (dist < 0.006f) {
            applyExpandProgress(target)
            return
        }
        val speedBoost = (abs(vyPxPerSec) / (2800f * dens)).coerceIn(0f, 0.38f)
        val dur = ((260f + 260f * dist) * (1f - speedBoost)).roundToInt().toLong().coerceIn(220L, 500L)
        expandAnim = ValueAnimator.ofFloat(start, target).apply {
            duration = dur
            interpolator = iosEase
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
        if (visibility != VISIBLE || currentItems.isEmpty() || expandFrac > 0.35f) return
        containerCol.animate().cancel()
        val baseTy = restingOffsetPx * (1f - expandFrac)
        containerCol.translationY = baseTy + 4.5f * dens
        containerCol.animate()
            .translationY(baseTy)
            .setDuration(420L)
            .setInterpolator(OvershootInterpolator(1.75f))
            .setUpdateListener { updateMoreHintPill() }
            .start()
    }

    fun invalidateCards() {
        clearBtn.invalidate()
        moreHintPill.invalidate()
        for (i in 0 until listCol.childCount) {
            listCol.getChildAt(i)?.invalidate()
        }
        for (i in 0 until dismissLayer.childCount) {
            dismissLayer.getChildAt(i)?.invalidate()
        }
    }

    private fun checkHideWhenEmpty() {
        if (currentItems.isEmpty() && !hidingOnEmpty && dismissLayer.childCount == 0) {
            visibility = GONE
        }
    }

    private fun handleRowSwipeProgress(row: NotificationCardRow, dragFrac: Float) {
        onDragMusicCoupling(-abs(dragFrac) * 3.5f * dens)
        if (!isLandscapeCompact && expandFrac < 0.35f) {
            val idx = listCol.indexOfChild(row)
            if (idx >= 0) {
                swipePeekAnim?.cancel()
                activeSwipeIndex = idx
                swipePeekAdvance = (dragFrac * 0.56f).coerceIn(0f, 0.56f)
                updateCard3DAndPeek()
            }
        }
    }

    private fun handleRowSwipeCancel(row: NotificationCardRow) {
        onDragMusicCoupling(0f)
        val idx = listCol.indexOfChild(row)
        if (activeSwipeIndex == idx && swipePeekAdvance > 0.005f) {
            swipePeekAnim?.cancel()
            val startAdv = swipePeekAdvance
            swipePeekAnim = ValueAnimator.ofFloat(startAdv, 0f).apply {
                duration = 380L
                interpolator = OvershootInterpolator(1.25f)
                addUpdateListener { a ->
                    swipePeekAdvance = a.animatedValue as Float
                    updateCard3DAndPeek()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        swipePeekAdvance = 0f
                        activeSwipeIndex = -1
                        updateCard3DAndPeek()
                    }
                })
                start()
            }
        } else {
            activeSwipeIndex = -1
            swipePeekAdvance = 0f
        }
    }

    private fun detachAndDismissRow(row: NotificationCardRow, cur: LockNotification, dir: Float, vx: Float) {
        val idx = listCol.indexOfChild(row)
        val stackedMode = !isLandscapeCompact && expandFrac < 0.35f
        val curLeft = row.left
        val curTop = (containerCol.translationY + scrollView.top + row.top - scrollView.scrollY + scrollView.rubberBotY + row.translationY).roundToInt()
        val curW = max(1, row.width)
        val curH = max(1, row.height)

        if (idx >= 0) {
            val nextRow = listCol.getChildAt(idx + 1) as? NotificationCardRow
            if (stackedMode) {
                val advanceUsed = if (activeSwipeIndex == idx) swipePeekAdvance else 0f
                swipePeekAnim?.cancel()
                swipePeekAdvance = 0f
                activeSwipeIndex = -1

                val heightDiff = if (idx == 0 && nextRow != null && nextRow.height > 0) {
                    (curH - nextRow.height).toFloat()
                } else 0f

                if (nextRow != null || currentItems.size > 1) {
                    deckPromoteAnim?.cancel()
                    val startShift = (deckShift + (1f - advanceUsed)).coerceIn(0f, 3.0f)
                    val startComp = heightCompOffset + heightDiff
                    deckShift = startShift
                    heightCompOffset = startComp
                    deckPromoteAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 460L
                        interpolator = OvershootInterpolator(1.12f)
                        addUpdateListener { a ->
                            val t = a.animatedValue as Float
                            deckShift = startShift * (1f - t)
                            heightCompOffset = startComp * (1f - t)
                            updateCard3DAndPeek()
                            updateMoreHintPill()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                deckShift = 0f
                                heightCompOffset = 0f
                                updateCard3DAndPeek()
                                updateMoreHintPill()
                            }
                        })
                        start()
                    }
                }
            } else {
                swipePeekAnim?.cancel()
                swipePeekAdvance = 0f
                activeSwipeIndex = -1
                if (nextRow != null) {
                    expandGapAnim?.cancel()
                    val startGap = expandGapShift + curH.toFloat() + 8f * dens
                    expandGapStartIdx = idx
                    expandGapShift = startGap
                    expandGapAnim = ValueAnimator.ofFloat(startGap, 0f).apply {
                        duration = 340L
                        interpolator = iosEase
                        addUpdateListener { a ->
                            expandGapShift = a.animatedValue as Float
                            updateCard3DAndPeek()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                expandGapShift = 0f
                                expandGapStartIdx = Int.MAX_VALUE
                                updateCard3DAndPeek()
                            }
                        })
                        start()
                    }
                }
            }
        }

        // Move dismissing card into unclipped dismissLayer at exact visual coordinates so cards behind never shift via lp.height!
        cardViews.remove(cur.id)
        listCol.removeView(row)
        row.isDismissing = true
        row.isClickable = false
        row.isFocusable = false
        row.translationY = 0f
        row.translationZ = 40f * dens
        dismissLayer.addView(
            row,
            LayoutParams(curW, curH).apply {
                leftMargin = curLeft
                topMargin = curTop
            }
        )

        val speedBoost = (abs(vx) / (3200f * dens)).coerceIn(0f, 0.35f)
        val dur = (275f * (1f - speedBoost * 0.42f)).roundToInt().toLong().coerceIn(190L, 285L)
        val targetTx = dir * max(280f, curW.toFloat()) * (0.50f + speedBoost * 0.14f)
        val targetTy = if (stackedMode) -10f * dens else -4f * dens

        row.animate().cancel()
        row.pivotX = curW * 0.5f
        row.pivotY = curH * 0.5f
        row.animate()
            .translationX(targetTx)
            .translationY(targetTy)
            .rotationY(dir * 16f)
            .rotation(dir * 3.4f)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .alpha(0f)
            .setDuration(dur)
            .setInterpolator(iosEase)
            .setUpdateListener { row.invalidate() }
            .withEndAction {
                dismissLayer.removeView(row)
                checkHideWhenEmpty()
            }
            .start()

        onDismissNotification(cur)
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
            updateMoreHintPill()
            return
        }

        val prevCount = currentItems.size
        currentItems = items

        if (items.isEmpty()) {
            expandFrac = 0f
            swipePeekAnim?.cancel()
            deckPromoteAnim?.cancel()
            expandGapAnim?.cancel()
            swipePeekAdvance = 0f
            activeSwipeIndex = -1
            deckShift = 0f
            heightCompOffset = 0f
            expandGapShift = 0f
            expandGapStartIdx = Int.MAX_VALUE
            onScrollExpandChanged(0f)
            moreHintPill.visibility = GONE
            cardViews.clear()
            listCol.removeAllViews()
            if (visibility == VISIBLE) {
                hidingOnEmpty = true
                containerCol.animate().cancel()
                containerCol.animate()
                    .alpha(0f)
                    .translationY(16f * dens)
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(260L)
                    .setInterpolator(iosEase)
                    .withEndAction {
                        hidingOnEmpty = false
                        if (currentItems.isEmpty()) {
                            containerCol.translationY = 0f
                            containerCol.scaleX = 1f
                            containerCol.scaleY = 1f
                            containerCol.alpha = 1f
                            checkHideWhenEmpty()
                        }
                    }
                    .start()
            }
            return
        }

        if (visibility != VISIBLE || prevCount == 0 || hidingOnEmpty) {
            hidingOnEmpty = false
            visibility = VISIBLE
            containerCol.animate().cancel()
            containerCol.alpha = 1f
            containerCol.scaleX = 1f
            containerCol.scaleY = 1f
            containerCol.translationY = restingOffsetPx * (1f - expandFrac)
            animate().cancel()
            alpha = 0f
            translationY = 20f * dens
            scaleX = 0.94f
            scaleY = 0.94f
            animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(460L)
                .setInterpolator(OvershootInterpolator(1.3f))
                .withEndAction(null)
                .start()
        }

        if (removedId != null) {
            cardViews.remove(removedId)?.let { row ->
                if (!row.isDismissing) {
                    listCol.removeView(row)
                }
            }
        }

        val nextIds = items.map { it.id }.toSet()
        for (id in cardViews.keys.toList()) {
            if (id !in nextIds) {
                cardViews.remove(id)?.let { row ->
                    if (!row.isDismissing) listCol.removeView(row)
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
                    onSwipeProgress = { r, frac ->
                        handleRowSwipeProgress(r, frac)
                    },
                    onSwipeCancel = { r ->
                        handleRowSwipeCancel(r)
                    },
                    onDismiss = { r, dismissed, dir, vx ->
                        detachAndDismissRow(r, dismissed, dir, vx)
                    },
                    onTap = { tapped ->
                        onCardTapped(tapped)
                    },
                    onPressCancelled = {
                        post { updateCard3DAndPeek() }
                    },
                    onHaptic = onHaptic
                ).apply {
                    bind(item, hub)
                    applyTheme(uiInk, uiDim, glassBg)
                }

            row.visibility = VISIBLE
            row.translationZ = max(0, 36 - index) * 0.45f * dens
            cardViews[item.id] = row
            listCol.addView(row)

            if (added != null && item.id == added.id) {
                row.playEntrance3D(delayMs = 0L)
            } else if (added != null && index > 0) {
                row.playShiftDown()
            } else if (prevCount == 0 && index < 3) {
                row.playEntrance3D(delayMs = (index * 48L).coerceAtMost(144L))
            }
        }

        post {
            updateCard3DAndPeek()
            updateMoreHintPill()
        }
    }

    private fun updateMoreHintPill() {
        val hiddenCount = currentItems.size - 1
        if (isLandscapeCompact || hiddenCount <= 0 || expandFrac > 0.24f) {
            moreHintPill.visibility = GONE
            return
        }
        val firstRow = listCol.getChildAt(0)
        val firstBot = (if (firstRow != null && firstRow.height > 0) firstRow.bottom.toFloat() else (70f * dens)) + heightCompOffset
        val peekSpan = if (currentItems.size >= 3) 20f * dens else 11f * dens
        val stackBotInContainer = scrollView.top.toFloat() + firstBot + peekSpan
        moreHintPill.visibility = VISIBLE
        moreHintPill.translationY = containerCol.translationY + stackBotInContainer + 6f * dens
        moreHintPill.alpha = (1f - expandFrac * 4.5f).coerceIn(0f, 1f)
        moreHintPill.setCount(hiddenCount)
        moreHintPill.invalidate()
    }

    private fun sampleDeckSlot(slot: Float): FloatArray {
        // Returns [peekPx, scale, rotX, alpha, transZ] with C1 smoothstep transitions across all slots
        return when {
            slot <= 0f -> {
                val s = slot.coerceAtLeast(-0.08f)
                floatArrayOf(s * 14f * dens, 1f - s * 0.08f, 0f, 1f, 32f * dens)
            }
            slot <= 1f -> {
                val t = slot
                val st = t * t * (3f - 2f * t)
                floatArrayOf(
                    11f * dens * st,
                    1f + (0.945f - 1f) * st,
                    -4f * st,
                    1f + (0.88f - 1f) * st,
                    (32f + (20f - 32f) * st) * dens
                )
            }
            slot <= 2f -> {
                val t = slot - 1f
                val st = t * t * (3f - 2f * t)
                floatArrayOf(
                    (11f + (20f - 11f) * st) * dens,
                    0.945f + (0.89f - 0.945f) * st,
                    -4f + (-7f - (-4f)) * st,
                    0.88f + (0.60f - 0.88f) * st,
                    (20f + (11f - 20f) * st) * dens
                )
            }
            slot <= 3f -> {
                val t = slot - 2f
                val st = t * t * (3f - 2f * t)
                floatArrayOf(
                    (20f + (22f - 20f) * st) * dens,
                    0.89f + (0.85f - 0.89f) * st,
                    -7f + (-9f - (-7f)) * st,
                    0.60f * (1f - st),
                    (11f + (4f - 11f) * st) * dens
                )
            }
            else -> floatArrayOf(22f * dens, 0.85f, -9f, 0f, 2f * dens)
        }
    }

    private fun updateCard3DAndPeek() {
        val count = listCol.childCount
        if (count == 0) return

        val svW = scrollView.width
        val svH = scrollView.height
        if (svW <= 0 || svH <= 0) return

        val visH = max(1f, svH.toFloat())
        val scrollY = scrollView.scrollY.toFloat()
        val rubberY = scrollView.rubberBotY
        val firstRow = listCol.getChildAt(0)
        val firstBot = (if (firstRow != null && firstRow.height > 0) firstRow.bottom.toFloat() else (70f * dens)) + heightCompOffset
        val topBand = 70f * dens
        val botBand = 88f * dens

        for (i in 0 until count) {
            val row = listCol.getChildAt(i) as? NotificationCardRow ?: continue
            if (row.isDismissing || row.isDragging || row.isEntranceRunning) continue

            // Continuous C1 slot evaluation: handles rest stacking, live swipe-peek advance, smooth promotion, and unfolding!
            var restSc = 1f
            var restTy = 0f
            var restRx = 0f
            var restAlpha = 1f
            var restZ = 22f * dens
            var pivotRatioY = 0.5f

            if (!isLandscapeCompact) {
                val swipeBoost = if (activeSwipeIndex in 0 until i && expandFrac < 0.35f) swipePeekAdvance else 0f
                val effSlot = if (expandFrac < 0.35f) {
                    val base = i.toFloat() + deckShift - swipeBoost
                    if (activeSwipeIndex >= 0 && i > activeSwipeIndex) max(activeSwipeIndex.toFloat(), base) else base
                } else {
                    i.toFloat()
                }

                if (effSlot > 0.001f || effSlot < -0.001f || expandFrac < 0.999f) {
                    val slotVal = sampleDeckSlot(effSlot)
                    val dPeek = slotVal[0]
                    val dScale = slotVal[1]
                    val dRx = slotVal[2]
                    val dAlpha = slotVal[3]
                    val dZ = slotVal[4]

                    val staggerIdx = max(0, effSlot.roundToInt() - 1).coerceAtMost(4)
                    val staggerStart = staggerIdx * 0.045f
                    val staggerSpan = max(0.35f, 1f - staggerStart * 0.72f)
                    val cardP = if (effSlot <= 0.001f) {
                        expandFrac.coerceIn(0f, 1f)
                    } else {
                        ((expandFrac - staggerStart) / staggerSpan).coerceIn(0f, 1f)
                    }
                    val unfold = cardP * cardP * (3f - 2f * cardP)
                    val closed = 1f - unfold

                    val rowH = if (row.height > 0) row.height.toFloat() else row.cardHeightEstimate()
                    val collapsedTy = if (row.top > 0 || i == 0) {
                        (firstBot - rowH + dPeek) - row.top.toFloat()
                    } else {
                        -row.cardHeightEstimate() * min(i, 4) * 0.85f
                    }

                    restSc = dScale + (1f - dScale) * unfold
                    restTy = collapsedTy * closed
                    restRx = dRx * closed
                    restAlpha = if (effSlot >= 2.2f) {
                        val revealP = ((cardP - 0.10f) / 0.52f).coerceIn(0f, 1f)
                        val openAlpha = revealP * revealP * (3f - 2f * revealP)
                        dAlpha + (openAlpha - dAlpha) * unfold
                    } else {
                        dAlpha + (1f - dAlpha) * unfold
                    }
                    restZ = dZ * closed + (18f * dens) * unfold
                    if (closed > 0.08f && effSlot > 0.1f) pivotRatioY = 0.35f
                } else {
                    restZ = 18f * dens
                }

                if (expandFrac >= 0.35f && expandGapShift > 0.5f && i >= expandGapStartIdx) {
                    restTy += expandGapShift
                }
            }

            // Gentle 3D depth barrel carousel while scrolling expanded viewport
            val cardTop = row.top - scrollY + rubberY
            val cardBot = row.bottom - scrollY + rubberY
            var scrSc = 1f
            var scrTy = 0f
            var scrRx = 0f
            var scrAlpha = 1f

            if (expandFrac > 0.82f) {
                val active3D = ((expandFrac - 0.82f) / 0.18f).coerceIn(0f, 1f)
                if (cardTop < topBand && (scrollY > 1f || rubberY < -2f)) {
                    val tf = ((topBand - cardTop) / max(1f, row.height + topBand)).coerceIn(0f, 1f)
                    val easeT = tf * tf * (3f - 2f * tf)
                    scrSc = 1f - 0.09f * easeT * active3D
                    scrRx = 14f * easeT * active3D
                    scrTy = 5f * dens * easeT * active3D
                    scrAlpha = 1f - 0.72f * easeT * active3D
                    pivotRatioY = 0.80f
                    restZ *= (1f - 0.45f * easeT * active3D)
                } else if (cardBot > visH - botBand && cardTop < visH) {
                    val bf = ((cardBot - (visH - botBand)) / max(1f, row.height + botBand)).coerceIn(0f, 1f)
                    val easeB = bf * bf * (3f - 2f * bf)
                    scrSc = 1f - 0.09f * easeB * active3D
                    scrRx = -14f * easeB * active3D
                    scrTy = -6f * dens * easeB * active3D
                    scrAlpha = 1f - 0.65f * easeB * active3D
                    pivotRatioY = 0.20f
                    restZ *= (1f - 0.45f * easeB * active3D)
                }
            }

            val finalSc = min(restSc, scrSc)
            val finalRx = restRx + scrRx
            val finalTy = restTy + scrTy
            val finalAlpha = min(restAlpha, scrAlpha).coerceIn(0f, 1f)

            row.cameraDistance = 12500f * dens
            row.pivotX = row.width * 0.5f
            row.pivotY = row.height * pivotRatioY
            row.scaleX = finalSc
            row.scaleY = finalSc
            row.translationY = finalTy
            row.translationZ = restZ
            row.rotationX = finalRx
            row.alpha = finalAlpha
            if (finalAlpha > 0.02f && cardBot >= -80f && cardTop <= visH + 120f) {
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
            val w = max(280f, row.width.toFloat())
            row.animate()
                .translationX(dir * w * 0.42f)
                .rotationY(dir * 14f)
                .alpha(0f)
                .scaleX(0.84f)
                .scaleY(0.84f)
                .setStartDelay((idx * 34L).coerceAtMost(150L))
                .setDuration(250L)
                .setInterpolator(iosEase)
                .setUpdateListener { row.invalidate() }
                .start()
        }
        postDelayed({
            onClearAll()
        }, (min(rows.size, 5) * 34L + 220L))
    }

    private class RubberScrollView(context: Context) : ScrollView(context) {
        private val dens = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val scroller = OverScroller(context)
        private var startRawX = 0f
        private var startRawY = 0f
        private var lastRawY = 0f
        private var dragActive = false
        private var startedExpanded = false
        private var vt: VelocityTracker? = null
        private var rubberAnim: ValueAnimator? = null
        var rubberBotY = 0f
            private set
        var onPullOverscroll: ((Float) -> Unit)? = null
        var onRubberChanged: (() -> Unit)? = null
        var onInteractionStart: (() -> Unit)? = null
        var getExpandTravelPx: () -> Float = { 300f * dens }

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

        fun abortScrolling() {
            if (!scroller.isFinished) scroller.abortAnimation()
            rubberAnim?.cancel()
            if (rubberBotY != 0f) setRubber(0f)
        }

        fun smoothScrollToTop() {
            abortScrolling()
            if (scrollY > 0) {
                scroller.startScroll(0, scrollY, 0, -scrollY, 360)
                postInvalidateOnAnimation()
            }
        }

        private fun setRubber(y: Float) {
            rubberBotY = y
            getChildAt(0)?.translationY = y
            onRubberChanged?.invoke()
            invalidate()
        }

        private fun springBackRubber() {
            if (abs(rubberBotY) < 0.5f) {
                setRubber(0f)
                return
            }
            rubberAnim?.cancel()
            val start = rubberBotY
            rubberAnim = ValueAnimator.ofFloat(start, 0f).apply {
                duration = 380L
                interpolator = OvershootInterpolator(1.35f)
                addUpdateListener { a -> setRubber(a.animatedValue as Float) }
                start()
            }
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                val maxScroll = max(0, (getChildAt(0)?.height ?: 0) - height)
                val cy = scroller.currY
                when {
                    cy < 0 -> {
                        if (scrollY != 0) scrollTo(0, 0)
                        setRubber(min(44f * dens, -cy.toFloat()))
                    }
                    cy > maxScroll -> {
                        if (scrollY != maxScroll) scrollTo(0, maxScroll)
                        setRubber(-min(44f * dens, (cy - maxScroll).toFloat()))
                    }
                    else -> {
                        if (rubberBotY != 0f) setRubber(0f)
                        if (scrollY != cy) scrollTo(0, cy)
                    }
                }
                onRubberChanged?.invoke()
                postInvalidateOnAnimation()
            } else if (!dragActive && abs(rubberBotY) > 0.5f && rubberAnim?.isRunning != true) {
                springBackRubber()
            }
        }

        // Clip ONLY vertically to ScrollView bounds, leaving left & right completely unclipped so swipes fade smoothly!
        override fun dispatchDraw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.clipRect(-width * 2, scrollY, width * 3, scrollY + height)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(saved)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    abortScrolling()
                    onInteractionStart?.invoke()
                    startedExpanded = getExpandFn() >= 0.99f
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
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.15f) {
                        val canExp = canExpandFn()
                        if ((canExp && ((dy < 0f && curExp < 0.999f) || (dy > 0f && scrollY <= 0 && curExp > 0.001f))) ||
                            curExp >= 0.999f ||
                            canScrollVertically(1) ||
                            canScrollVertically(-1)
                        ) {
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
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    abortScrolling()
                    onInteractionStart?.invoke()
                    startedExpanded = getExpandFn() >= 0.99f
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    lastRawY = ev.rawY
                    dragActive = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain().apply { addMovement(ev) }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(ev)
                    if (!dragActive) {
                        val totalDy = ev.rawY - startRawY
                        val totalDx = ev.rawX - startRawX
                        if (abs(totalDy) > touchSlop && abs(totalDy) > abs(totalDx) * 1.1f) {
                            dragActive = true
                            lastRawY = ev.rawY
                        } else {
                            return true
                        }
                    }

                    val dy = ev.rawY - lastRawY
                    lastRawY = ev.rawY
                    val curExp = getExpandFn()
                    val canExp = canExpandFn()
                    val travel = max(220f * dens, getExpandTravelPx())

                    if (canExp && dy < 0f && curExp < 0.999f) {
                        val next = (curExp + (-dy) / travel).coerceIn(0f, 1f)
                        setExpandFn(next)
                        val usedPx = (next - curExp) * travel
                        val leftover = (-dy) - usedPx
                        if (next >= 0.999f && leftover > 0.5f) {
                            val maxScroll = max(0, (getChildAt(0)?.height ?: 0) - height)
                            val avail = max(0, maxScroll - scrollY)
                            if (avail > 0) scrollBy(0, min(avail, leftover.roundToInt()))
                        }
                        return true
                    }
                    if (canExp && dy > 0f && scrollY <= 0 && rubberBotY >= -0.5f && curExp > 0.001f) {
                        val next = (curExp - dy / travel).coerceIn(0f, 1f)
                        setExpandFn(next)
                        return true
                    }
                    if (curExp >= 0.999f || !canExp) {
                        val maxScroll = max(0, (getChildAt(0)?.height ?: 0) - height)
                        if (rubberBotY < -0.5f && dy > 0f) {
                            val absorb = min(-rubberBotY, dy)
                            setRubber(rubberBotY + absorb)
                            val rem = dy - absorb
                            if (rem > 0f && scrollY > 0) scrollBy(0, -min(scrollY, rem.roundToInt()))
                        } else if (dy < 0f) {
                            val avail = max(0, maxScroll - scrollY)
                            if (avail > 0) {
                                val used = min(avail.toFloat(), -dy)
                                scrollBy(0, used.roundToInt())
                                val left = (-dy) - used
                                if (left > 0.5f) setRubber(max(-64f * dens, rubberBotY - left * 0.38f))
                            } else {
                                setRubber(max(-64f * dens, rubberBotY + dy * 0.38f))
                            }
                        } else if (dy > 0f && scrollY > 0) {
                            val used = min(scrollY.toFloat(), dy)
                            scrollBy(0, -used.roundToInt())
                            val left = dy - used
                            if (left > 0.5f && canExp) {
                                setExpandFn((curExp - left / travel).coerceIn(0f, 1f))
                            }
                        }
                        return true
                    }
                    if (scrollY <= 0 && curExp <= 0.001f && ev.rawY > startRawY) {
                        onPullOverscroll?.invoke(min(18f * dens, (ev.rawY - startRawY) * 0.14f))
                        return true
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
                    val canExp = canExpandFn()
                    val wasDrag = dragActive
                    dragActive = false
                    springBackRubber()

                    if (canExp && curExp > 0.005f && curExp < 0.995f) {
                        onReleaseFn(curExp, vy)
                        return true
                    }
                    if (curExp >= 0.995f && wasDrag) {
                        val maxScroll = max(0, (getChildAt(0)?.height ?: 0) - height)
                        if (canExp && scrollY <= 0 && vy > 620f * dens) {
                            onReleaseFn(0.45f, vy)
                        } else if ((startedExpanded || scrollY > (12f * dens).roundToInt()) && abs(vy) > 150f * dens) {
                            scroller.fling(0, scrollY, 0, (-vy).roundToInt(), 0, 0, 0, maxScroll, 0, (42f * dens).roundToInt())
                            postInvalidateOnAnimation()
                        }
                    }
                    return true
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
                text = "↑ اسحب للأعلى"
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

        fun setCount(hidden: Int) {
            label.text = if (hidden == 1) {
                "↑ إشعار إضافي • اسحب للأعلى"
            } else {
                "↑ +$hidden إشعارات مكدّسة • اسحب للأعلى"
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
        private val onSwipeProgress: (NotificationCardRow, Float) -> Unit,
        private val onSwipeCancel: (NotificationCardRow) -> Unit,
        private val onDismiss: (NotificationCardRow, LockNotification, Float, Float) -> Unit,
        private val onTap: (LockNotification) -> Unit,
        private val onPressCancelled: () -> Unit,
        private val onHaptic: (String) -> Unit
    ) : FrameLayout(context) {

        private val dens = resources.displayMetrics.density
        private val glassHelper = LiquidGlassCardHelper(backdrop, perf, 24f, 18f, 13f, 1.46f, 0.0f, 1.0f)
        private val innerFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clipPath = Path()
        private val rectF = RectF()
        private var cachedW = 0
        private var cachedH = 0
        private var fallbackWashShader: LinearGradient? = null
        private val dismissEase = PathInterpolator(0.22f, 1f, 0.36f, 1f)

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
        private var basePressSc = 1f
        private var basePressAlpha = 1f
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
            cameraDistance = 11500f * dens
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
            iconView.setImageBitmap(hubRef.getAppIcon(data.packageName, (39f * dens).roundToInt()))
            if (data.count > 1) {
                countBadge.text = data.count.toString()
                countBadge.visibility = VISIBLE
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
            scaleX = 0.84f
            scaleY = 0.84f
            rotationX = -18f
            translationY = -14f * dens
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .rotationX(0f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(460L)
                .setInterpolator(OvershootInterpolator(1.55f))
                .setUpdateListener { invalidate() }
                .withEndAction {
                    isEntranceRunning = false
                    invalidate()
                }
                .start()
        }

        fun playShiftDown() {
            animate().cancel()
            translationY = -36f * dens
            animate()
                .translationY(0f)
                .setDuration(400L)
                .setInterpolator(dismissEase)
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
                    basePressSc = scaleX.coerceIn(0.80f, 1.05f)
                    basePressAlpha = alpha.coerceIn(0.05f, 1f)
                    vt?.recycle()
                    vt = VelocityTracker.obtain().apply { addMovement(ev) }
                    animate().cancel()
                    pivotX = width * 0.5f
                    pivotY = height * 0.5f
                    animate().scaleX(basePressSc * 0.978f).scaleY(basePressSc * 0.972f).setDuration(105L).start()
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
                        val clampedDx = if (cur.isDismissible) dx else (dx * 0.20f)
                        val dragFrac = (abs(clampedDx) / (w * 0.65f)).coerceIn(0f, 1.3f)
                        // Dissolve softly & tilt in 3D with zero edge scissor clipping
                        pivotX = width * 0.5f
                        pivotY = height * 0.5f
                        translationX = clampedDx
                        rotationY = (clampedDx / w * 11f).coerceIn(-12f, 12f)
                        rotation = (clampedDx / w * 2.4f).coerceIn(-3f, 3f)
                        val sc = basePressSc * (1f - min(0.10f, dragFrac * 0.08f))
                        scaleX = sc
                        scaleY = sc
                        val fade = (1f - dragFrac * 0.90f).coerceIn(0.02f, 1f)
                        alpha = basePressAlpha * fade * sqrt(fade)
                        onSwipeProgress(this, if (cur.isDismissible) dragFrac.coerceIn(0f, 1f) else 0f)
                        invalidate()
                    } else if (abs(dy) > touchSlop) {
                        animate().cancel()
                        onPressCancelled()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.addMovement(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    vt?.recycle()
                    vt = null

                    if (isDragging) {
                        isDragging = false
                        val w = max(1f, width.toFloat())
                        val shouldDismiss = cur.isDismissible &&
                            (abs(translationX) >= w * 0.28f || abs(vx) >= 800f * dens) &&
                            ev.actionMasked == MotionEvent.ACTION_UP
                        if (shouldDismiss) {
                            dismissWithSpring3D(cur, dir = if (translationX >= 0f || vx > 0f) 1f else -1f, vx = vx)
                        } else {
                            onSwipeCancel(this)
                            snapBackRubbery()
                        }
                    } else {
                        animate().cancel()
                        onSwipeCancel(this)
                        if (ev.actionMasked == MotionEvent.ACTION_UP &&
                            abs(ev.rawX - downRawX) < touchSlop &&
                            abs(ev.rawY - downRawY) < touchSlop
                        ) {
                            onHaptic("pop")
                            pivotX = width * 0.5f
                            pivotY = height * 0.5f
                            scaleX = basePressSc * 0.94f; scaleY = basePressSc * 0.94f
                            animate().scaleX(basePressSc).scaleY(basePressSc).setDuration(360L).setInterpolator(OvershootInterpolator(1.9f)).start()
                            onTap(cur)
                        } else {
                            // Vertical scroll intercepted this card — restore stack/3D transforms directly without running a conflicting 440ms animator!
                            onPressCancelled()
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
                .scaleX(basePressSc)
                .scaleY(basePressSc)
                .alpha(basePressAlpha)
                .setDuration(420L)
                .setInterpolator(OvershootInterpolator(1.65f))
                .setUpdateListener { invalidate() }
                .withEndAction { onPressCancelled() }
                .start()
        }

        private fun dismissWithSpring3D(cur: LockNotification, dir: Float, vx: Float) {
            isDismissing = true
            onHaptic("pop")
            onDismiss(this, cur, dir, vx)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return

            // Always render genuine Liquid Glass refraction + soft fading white inner shadow (no full border stroke)
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

            // Fallback fading white inner shadow ONLY when GPU AGSL isn't available (API < 33) — pure FILL gradient wash, zero stroke!
            if (!LiquidGlassShaders.isGpuAgslAvailable()) {
                val rad = 24f * dens
                if (cachedW != width || cachedH != height || fallbackWashShader == null) {
                    cachedW = width
                    cachedH = height
                    rectF.set(0f, 0f, w, h)
                    clipPath.reset()
                    clipPath.addRoundRect(rectF, rad, rad, Path.Direction.CW)
                    fallbackWashShader = LinearGradient(
                        w * 0.28f, 0f, w * 0.56f, h * 0.52f,
                        intArrayOf(
                            Color.argb(86, 255, 255, 255),
                            Color.argb(24, 255, 255, 255),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.35f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                val saved = canvas.save()
                canvas.clipPath(clipPath)
                innerFallbackPaint.shader = fallbackWashShader
                canvas.drawRect(0f, 0f, w, h * 0.55f, innerFallbackPaint)
                innerFallbackPaint.shader = null
                canvas.restoreToCount(saved)
            }
        }
    }
}
