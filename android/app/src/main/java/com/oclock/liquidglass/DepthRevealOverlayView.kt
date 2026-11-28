package com.oclock.liquidglass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class DepthRevealOverlayView(context: Context) : View(context) {
    private val dens = resources.displayMetrics.density
    private val prepExecutor = Executors.newSingleThreadExecutor()

    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val xferSrcIn = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    private val drawMatrix = Matrix()

    private var scanAnim: ValueAnimator? = null
    private var revealAnim: ValueAnimator? = null
    private var isScanning = false
    private var scanPhase = 0f

    private var revealActive = false
    private var revealPhase = 0f
    private var revealToken = 0
    private var scaledSubjectBmp: Bitmap? = null
    private var contourHaloBmp: Bitmap? = null
    private var clockMidY = 0f

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    fun startScanning() {
        stopReveal()
        isScanning = true
        visibility = VISIBLE
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
        if (scanAnim?.isRunning == true) return
        scanAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1750L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                scanPhase = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopScanning() {
        isScanning = false
        scanAnim?.cancel()
        scanAnim = null
        if (!revealActive) {
            visibility = GONE
        } else {
            invalidate()
        }
    }

    fun playSeparationReveal(
        subjectBmp: Bitmap,
        baseScale: Float,
        clockCenterY: Float,
        onFrameProgress: (t: Float, liftAlpha: Float, liftScale: Float, liftTyPx: Float, wallDip: Float, clockDip: Float) -> Unit,
        onFinished: () -> Unit
    ) {
        val myToken = ++revealToken
        stopScanning()
        revealAnim?.cancel()
        revealAnim = null
        recyclePreparedBitmaps()

        clockMidY = clockCenterY

        prepExecutor.execute {
            val prepared = prepareCutoutArtifacts(subjectBmp)
            post {
                if (myToken != revealToken || !isAttachedToWindow) {
                    prepared.first?.recycle()
                    prepared.second?.recycle()
                    return@post
                }
                scaledSubjectBmp = prepared.first
                contourHaloBmp = prepared.second
                revealActive = true
                revealPhase = 0f
                visibility = VISIBLE
                alpha = 1f

                revealAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1080L
                    interpolator = LinearInterpolator()
                    addUpdateListener { a ->
                        if (myToken != revealToken) return@addUpdateListener
                        val t = (a.animatedValue as Float).coerceIn(0f, 1f)
                        revealPhase = t

                        val liftWave = sin((t / 0.56f).coerceIn(0f, 1f) * PI).toFloat()
                        val liftAlpha = (t / 0.24f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                        val liftScale = baseScale * (1f + 0.024f * liftWave)
                        val liftTy = -5.5f * dens * liftWave
                        val wallDip = baseScale * (1f - 0.014f * sin((t / 0.62f).coerceIn(0f, 1f) * PI).toFloat())
                        val clockDip = 1f - 0.013f * sin((t / 0.58f).coerceIn(0f, 1f) * PI).toFloat()

                        pivotX = width * 0.5f
                        pivotY = height * 0.5f
                        scaleX = liftScale
                        scaleY = liftScale
                        translationY = liftTy

                        onFrameProgress(t, liftAlpha, liftScale, liftTy, wallDip, clockDip)
                        invalidate()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (myToken != revealToken) return
                            revealActive = false
                            revealAnim = null
                            scaleX = 1f
                            scaleY = 1f
                            translationY = 0f
                            visibility = GONE
                            recyclePreparedBitmaps()
                            onFinished()
                        }
                    })
                    start()
                }
            }
        }
    }

    fun stopAll() {
        revealToken++
        isScanning = false
        revealActive = false
        scanAnim?.cancel()
        scanAnim = null
        revealAnim?.cancel()
        revealAnim = null
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
        visibility = GONE
        recyclePreparedBitmaps()
    }

    private fun stopReveal() {
        revealToken++
        revealActive = false
        revealAnim?.cancel()
        revealAnim = null
        recyclePreparedBitmaps()
    }

    private fun recyclePreparedBitmaps() {
        scaledSubjectBmp?.recycle()
        scaledSubjectBmp = null
        contourHaloBmp?.recycle()
        contourHaloBmp = null
    }

    private fun prepareCutoutArtifacts(src: Bitmap): Pair<Bitmap?, Bitmap?> {
        return try {
            val maxSide = 512f
            val scale = min(1f, maxSide / max(1, max(src.width, src.height)).toFloat())
            val tw = max(16, (src.width * scale).roundToInt())
            val th = max(16, (src.height * scale).roundToInt())
            val small = if (tw == src.width && th == src.height) {
                src.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createScaledBitmap(src, tw, th, true)
            }

            val alphaMask = small.extractAlpha()
            val halo = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val hc = Canvas(halo)

            val outerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF9EE3FF.toInt()
                maskFilter = BlurMaskFilter(4.2f, BlurMaskFilter.Blur.OUTER)
            }
            hc.drawBitmap(alphaMask, 0f, 0f, outerGlow)

            val crispRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = 215
                maskFilter = BlurMaskFilter(1.6f, BlurMaskFilter.Blur.OUTER)
            }
            hc.drawBitmap(alphaMask, 0f, 0f, crispRim)

            alphaMask.recycle()
            small to halo
        } catch (_: Throwable) {
            null to null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 1f || vh <= 1f) return

        if (isScanning) {
            val p = scanPhase
            val env = sin(p * PI).toFloat()
            val bandY = -vh * 0.15f + p * vh * 1.30f
            val bandHalf = vh * 0.18f
            scanPaint.shader = LinearGradient(
                0f, bandY - bandHalf, vw * 0.18f, bandY + bandHalf,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb((26 * env).roundToInt().coerceIn(0, 48), 165, 224, 255),
                    Color.argb((42 * env).roundToInt().coerceIn(0, 68), 255, 255, 255),
                    Color.argb((26 * env).roundToInt().coerceIn(0, 48), 165, 224, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.28f, 0.50f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, vw, vh, scanPaint)
            scanPaint.shader = null
        }

        if (!revealActive) return
        val subj = scaledSubjectBmp ?: return
        val halo = contourHaloBmp
        val t = revealPhase

        // Match ImageView CENTER_CROP transform identically so halo & shimmer lock pixel-perfect onto cutout over clock
        val bw = subj.width.toFloat()
        val bh = subj.height.toFloat()
        val cropScale = max(vw / max(1f, bw), vh / max(1f, bh))
        val dx = (vw - bw * cropScale) * 0.5f
        val dy = (vh - bh * cropScale) * 0.5f
        drawMatrix.setScale(cropScale, cropScale)
        drawMatrix.postTranslate(dx, dy)

        // 1. Feather-soft expanding depth ring centered near the subject/clock intersection
        if (t in 0.05f..0.72f) {
            val rt = ((t - 0.05f) / 0.67f).coerceIn(0f, 1f)
            val rEase = 1f - (1f - rt) * (1f - rt)
            val rAlpha = (sin(rt * PI).toFloat() * 0.16f).coerceIn(0f, 0.20f)
            val cy = if (clockMidY > 1f) clockMidY else vh * 0.34f
            val maxR = max(vw, vh) * 0.56f
            val curR = max(40f * dens, maxR * (0.24f + 0.76f * rEase))
            ripplePaint.shader = RadialGradient(
                vw * 0.5f, cy, curR,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb((rAlpha * 255f).roundToInt(), 180, 232, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0.42f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(vw * 0.5f, cy, curR, ripplePaint)
            ripplePaint.shader = null
        }

        // 2. Signature iOS luminous outer contour trace around the separated subject over the clock
        if (halo != null && t in 0.03f..0.82f) {
            val ht = ((t - 0.03f) / 0.79f).coerceIn(0f, 1f)
            val haloAlpha = sin(ht * PI).toFloat()
            val smoothHalo = haloAlpha * haloAlpha * 0.76f
            haloPaint.alpha = (smoothHalo * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(halo, drawMatrix, haloPaint)
        }

        // 3. Soft diagonal iOS glass sheen gliding strictly across the separated subject pixels
        if (t in 0.05f..0.78f) {
            val st = ((t - 0.05f) / 0.73f).coerceIn(0f, 1f)
            val sheenEnv = sin(st * PI).toFloat()
            val sheenPeak = (sheenEnv * 110f).roundToInt().coerceIn(0, 125)
            if (sheenPeak > 2) {
                val saved = canvas.saveLayer(0f, 0f, vw, vh, null)
                val bandSpan = vw * 0.75f
                val travelX = -bandSpan + (vw + bandSpan * 2f) * st
                val travelY = vh * 0.85f - vh * 0.70f * st
                shimmerPaint.shader = LinearGradient(
                    travelX - bandSpan * 0.45f,
                    travelY + bandSpan * 0.25f,
                    travelX + bandSpan * 0.45f,
                    travelY - bandSpan * 0.25f,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.argb((sheenPeak * 0.55f).roundToInt(), 160, 224, 255),
                        Color.argb(sheenPeak, 255, 255, 255),
                        Color.argb((sheenPeak * 0.55f).roundToInt(), 160, 224, 255),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.30f, 0.50f, 0.70f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, vw, vh, shimmerPaint)
                shimmerPaint.shader = null
                maskBitmapPaint.xfermode = xferSrcIn
                canvas.drawBitmap(subj, drawMatrix, maskBitmapPaint)
                maskBitmapPaint.xfermode = null
                canvas.restoreToCount(saved)
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopAll()
        prepExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }
}

class DepthLoadingPill(context: Context) : FrameLayout(context) {
    private val dens = resources.displayMetrics.density
    private val ease = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val spinnerView: RingSpinnerView
    private val labelView: TextView
    private var shown = false

    init {
        visibility = GONE
        alpha = 0f
        scaleX = 0.88f
        scaleY = 0.88f
        isClickable = false
        isFocusable = false

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16f * dens).roundToInt(), (10f * dens).roundToInt(), (16f * dens).roundToInt(), (10f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(148, 16, 20, 30))
                cornerRadius = 999f
                setStroke((0.9f * dens).roundToInt(), Color.argb(72, 255, 255, 255))
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }

        spinnerView = RingSpinnerView(context).apply {
            val sz = (18f * dens).roundToInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (10f * dens).roundToInt()
            }
        }
        labelView = TextView(context).apply {
            text = "جارٍ عزل الشخصية عن الخلفية…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(4f * dens, 0f, 1f * dens, Color.argb(70, 0, 0, 0))
        }

        row.addView(spinnerView)
        row.addView(labelView)
        addView(row)
    }

    fun show(msg: String = "جارٍ عزل الشخصية عن الخلفية…") {
        labelView.text = msg
        if (!shown) {
            shown = true
            visibility = VISIBLE
            animate().cancel()
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            translationY = 10f * dens
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(ease)
                .start()
            spinnerView.startSpin()
        }
    }

    fun updateStatus(msg: String) {
        labelView.text = msg
    }

    fun hide(immediate: Boolean = false) {
        shown = false
        spinnerView.stopSpin()
        animate().cancel()
        if (immediate || visibility != VISIBLE) {
            visibility = GONE
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            return
        }
        animate()
            .alpha(0f)
            .scaleX(0.90f)
            .scaleY(0.90f)
            .translationY(-6f * dens)
            .setDuration(210L)
            .setInterpolator(ease)
            .withEndAction {
                if (!shown) visibility = GONE
            }
            .start()
    }

    private class RingSpinnerView(context: Context) : View(context) {
        private val dens = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * dens
            strokeCap = Paint.Cap.ROUND
        }
        private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * dens
            color = Color.argb(46, 255, 255, 255)
        }
        private val oval = RectF()
        private var angle = 0f
        private var spinAnim: ValueAnimator? = null

        fun startSpin() {
            if (spinAnim?.isRunning == true) return
            spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 920L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    angle = a.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopSpin() {
            spinAnim?.cancel()
            spinAnim = null
        }

        override fun onDraw(canvas: Canvas) {
            val pad = 2f * dens
            oval.set(pad, pad, width - pad, height - pad)
            val cx = width * 0.5f
            val cy = height * 0.5f
            canvas.drawOval(oval, trackP)
            p.shader = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, 0xFF7DD3FC.toInt(), Color.WHITE),
                floatArrayOf(0.15f, 0.65f, 1.0f)
            )
            canvas.save()
            canvas.rotate(angle, cx, cy)
            canvas.drawArc(oval, -90f, 250f, false, p)
            canvas.restore()
            p.shader = null
        }

        override fun onDetachedFromWindow() {
            stopSpin()
            super.onDetachedFromWindow()
        }
    }
}
