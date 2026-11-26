package com.oclock.liquidglass

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.roundToInt

class CameraOverlayView(
    context: Context,
    private val onRequestPermission: () -> Unit,
    private val onShutterFired: () -> Unit,
    private val onClosed: () -> Unit
) : FrameLayout(context) {

    private val dens = resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    private val textureView: TextureView
    private val irisView: View
    private val shutterFlash: View
    private val msgText: TextView
    private val shutterBtn: FrameLayout
    private val closeBtn: TextView

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSize: Size = Size(1280, 720)
    private var autoShootPending = false
    private var autoShootRunnable = Runnable { shoot() }
    var isOpen = false
        private set

    init {
        visibility = GONE
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true

        textureView = TextureView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    if (isOpen) openCameraHardware()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    configureTransform(w, h)
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        addView(textureView)

        // Radial iris vignette matching #cam-iris
        irisView = object : View(context) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: android.graphics.Canvas) {
                val r = max(width, height) * 0.65f
                if (r <= 1f) return
                p.shader = RadialGradient(
                    width * 0.5f, height * 0.5f, r,
                    intArrayOf(0, 0, Color.argb(108, 0, 0, 0)),
                    floatArrayOf(0f, 0.58f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            }
        }.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(irisView)

        msgText = TextView(context).apply {
            text = "لا يتوفر إذن الكاميرا في هذا الجهاز"
            setTextColor(Color.argb(210, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            visibility = GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        addView(msgText)

        closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            val s = (40f * dens).roundToInt()
            layoutParams = LayoutParams(s, s, Gravity.TOP or Gravity.END).apply {
                topMargin = (44f * dens).roundToInt()
                marginEnd = (18f * dens).roundToInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(110, 0, 0, 0))
            }
            setOnClickListener { close() }
        }
        addView(closeBtn)

        shutterBtn = FrameLayout(context).apply {
            val outer = (74f * dens).roundToInt()
            layoutParams = LayoutParams(outer, outer, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (44f * dens).roundToInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke((3.2f * dens).roundToInt(), Color.WHITE)
            }
            val inner = View(context).apply {
                val ins = (58f * dens).roundToInt()
                layoutParams = LayoutParams(ins, ins, Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            }
            addView(inner)
            setOnClickListener { shoot() }
        }
        addView(shutterBtn)

        shutterFlash = View(context).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(shutterFlash)
    }

    fun open(autoShoot: Boolean = false) {
        isOpen = true
        autoShootPending = autoShoot
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(260L).start()
        msgText.visibility = GONE
        shutterBtn.isEnabled = false
        shutterBtn.alpha = 0.55f

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            msgText.text = "يلزم السماح بإذن الكاميرا"
            msgText.visibility = VISIBLE
            onRequestPermission()
            return
        }
        if (textureView.isAvailable) openCameraHardware()
    }

    fun onPermissionResult(granted: Boolean) {
        if (!isOpen) return
        if (granted) {
            msgText.visibility = GONE
            if (textureView.isAvailable) openCameraHardware()
        } else {
            msgText.text = "لم يتم منح إذن الكاميرا"
            msgText.visibility = VISIBLE
        }
    }

    private fun startThread() {
        if (camThread == null) {
            camThread = HandlerThread("OClockCam").apply { start() }
            camHandler = Handler(camThread!!.looper)
        }
    }

    private fun stopThread() {
        camThread?.quitSafely()
        camThread = null
        camHandler = null
    }

    private fun openCameraHardware() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        startThread()
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            val camId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: mgr.cameraIdList.firstOrNull() ?: return

            val chars = mgr.getCameraCharacteristics(camId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val choices = map?.getOutputSizes(SurfaceTexture::class.java)
            if (!choices.isNullOrEmpty()) {
                previewSize = choices.minByOrNull { kotlin.math.abs(it.width * 9 - it.height * 16) + kotlin.math.abs(it.height - 1080) }
                    ?: choices[0]
            }

            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    if (!isOpen) { cam.close(); return }
                    cameraDevice = cam
                    startPreviewSession()
                }
                override fun onDisconnected(cam: CameraDevice) {
                    cam.close()
                    cameraDevice = null
                }
                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close()
                    cameraDevice = null
                    mainHandler.post {
                        msgText.text = "تعذر تشغيل الكاميرا"
                        msgText.visibility = VISIBLE
                    }
                }
            }, camHandler)
        } catch (_: Throwable) {
            msgText.text = "الكاميرا غير متوفرة"
            msgText.visibility = VISIBLE
        }
    }

    private fun startPreviewSession() {
        val cam = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(st)
        mainHandler.post { configureTransform(textureView.width, textureView.height) }

        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!isOpen) { session.close(); return }
                    captureSession = session
                    try {
                        session.setRepeatingRequest(req.build(), null, camHandler)
                        mainHandler.post {
                            shutterBtn.isEnabled = true
                            shutterBtn.alpha = 1f
                            if (autoShootPending) {
                                autoShootPending = false
                                mainHandler.postDelayed(autoShootRunnable, 520L)
                            }
                        }
                    } catch (_: Throwable) {}
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, camHandler)
        } catch (_: Throwable) {}
    }

    private fun configureTransform(vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0) return
        val m = Matrix()
        val bw = previewSize.height.toFloat() // Portrait sensor orientation swap
        val bh = previewSize.width.toFloat()
        val scale = max(vw / bw, vh / bh)
        m.setScale((bw * scale) / vw, (bh * scale) / vh, vw * 0.5f, vh * 0.5f)
        textureView.setTransform(m)
    }

    private fun shoot() {
        if (!isOpen || !shutterBtn.isEnabled) return
        shutterFlash.animate().cancel()
        shutterFlash.alpha = 1f
        shutterFlash.animate().alpha(0f).setDuration(460L).start()
        irisView.alpha = 0f
        irisView.animate().alpha(1f).setDuration(450L).start()
        onShutterFired()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        mainHandler.removeCallbacks(autoShootRunnable)
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        stopThread()
        animate().alpha(0f).setDuration(200L).withEndAction {
            visibility = GONE
            onClosed()
        }.start()
    }
}
