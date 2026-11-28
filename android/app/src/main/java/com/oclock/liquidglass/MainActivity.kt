package com.oclock.liquidglass

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity(), EditorSheetView.EditorCallbacks {

    companion object {
        private const val REQ_WALL = 101
        private const val REQ_SUBJECT = 102
        private const val REQ_AUDIO = 103
        private const val REQ_ART = 104
        private const val REQ_CAM_PERM = 201
        private const val REQ_MEDIA_PERM = 202
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newCachedThreadPool()

    private lateinit var store: StateStore
    private lateinit var cfg: LockConfig
    private lateinit var perf: PerfMonitor
    private lateinit var glyphRenderer: ClockGlyphRenderer
    private lateinit var adaptive: AdaptiveTheme
    private lateinit var aiDepth: AIDepthManager
    private val backdrop = LiquidGlassShaders.BackdropState()

    private lateinit var rootFrame: FrameLayout
    private lateinit var phoneShell: FrameLayout
    private lateinit var wallView: ImageView
    private lateinit var clockContainer: FrameLayout
    private lateinit var clockGhostView: ClockGhostView
    private lateinit var clockView: ClockGlassView
    private lateinit var subjectView: ImageView
    private lateinit var depthRevealView: DepthRevealOverlayView
    private lateinit var depthLoadingPill: DepthLoadingPill
    private lateinit var resizeOverlay: ClockResizeOverlayView
    private lateinit var npCard: NowPlayingCardView
    private lateinit var notificationCenter: NotificationCenterView
    private lateinit var toplineRow: LinearLayout
    private lateinit var dateText: TextView
    private lateinit var wxText: TextView
    private lateinit var bottomRow: FrameLayout
    private lateinit var shLeft: ShortcutCircleButton
    private lateinit var dndPill: DndPillView
    private lateinit var shRight: ShortcutCircleButton
    private lateinit var unlockedBar: LinearLayout
    private lateinit var passcodeOverlay: PasscodeOverlayView
    private lateinit var statusBar: StatusBarView
    private lateinit var homeBar: View
    private lateinit var island: DynamicIslandView
    private lateinit var flashOverlay: FrameLayout
    private lateinit var cameraOverlay: CameraOverlayView
    private lateinit var musicLibraryOverlay: MusicLibraryOverlayView
    private lateinit var editorSheet: EditorSheetView
    private lateinit var harmonyAuraView: WallpaperHarmonyAuraView
    private lateinit var harmonyPillView: WallpaperHarmonyPill
    private lateinit var toastView: TextView

    private lateinit var media: MediaController
    private lateinit var notifHub: NotificationHub
    private var currentWallBmp: Bitmap? = null
    private var customWallBmp: Bitmap? = null
    private var customSubjectBmp: Bitmap? = null
    private var currentColors: AdaptiveColors? = null
    private var undoSnap: UndoSnapshot? = null

    private var safeTopPx = 0
    private var safeBottomPx = 0
    private var npBaseTranslationY = 0f
    private var clockRestOffset = 0f
    private var notifExpandFrac = 0f
    private var notifCouplingY = 0f
    private var pullY = 0f
    private var pullAnimator: ValueAnimator? = null
    private var isWallOrClockPulling = false
    private var clockHoldPendingRunnable: Runnable? = null
    private var wallHoldPendingRunnable: Runnable? = null
    private var clockEditorStageFrac = 0f
    private var wallEditorStageFrac = 0f
    private var editorStageTargetClock = 0f
    private var editorStageTargetWall = 0f
    private var editorStageVelClock = 0f
    private var editorStageVelWall = 0f
    private var editorStageSpringRunning = false
    private var editorStageLastNs = 0L
    private var springTargetClock = 0f
    private var springTargetClockFit = 1f
    private var springTargetNp = 0f
    private var springTargetNc = 0f
    private var clockFitScale = 1f
    private var passcodeProgress = 0f
    private var isUnlocked = false
    private var unlockedFrac = 0f
    private var unlockAnim: ValueAnimator? = null
    private var blurRenderToken = 0
    private var springVelClock = 0f
    private var springVelClockFit = 0f
    private var springVelNp = 0f
    private var springVelNc = 0f
    private var springRunning = false
    private var hadNotifsInLayout = false
    private var springLastNs = 0L
    private var hasInitialLayout = false
    private var depthFlowGen = 0
    private var isDepthRevealAnimating = false
    private var smartWallToken = 0
    private var smartMorphAnim: ValueAnimator? = null
    private var smartBreathScale = 1f
    private var stageGlideAnim: ValueAnimator? = null
    private var stageCurOffsetY = 0f
    private var stageCurFit = 1f
    private var hasStageSmoothState = false

    private val layoutSpringCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!springRunning) return
            val dt = if (springLastNs == 0L) 0.016f
            else ((frameTimeNanos - springLastNs) / 1_000_000_000f).coerceIn(0.004f, 0.032f)
            springLastNs = frameTimeNanos

            val omega = 13.5f
            val zeta = 0.86f
            val stiffness = omega * omega
            val damping = 2f * zeta * omega

            val fC = (springTargetClock - clockRestOffset) * stiffness - springVelClock * damping
            springVelClock += fC * dt
            clockRestOffset += springVelClock * dt

            val fF = (springTargetClockFit - clockFitScale) * stiffness - springVelClockFit * damping
            springVelClockFit += fF * dt
            clockFitScale = (clockFitScale + springVelClockFit * dt).coerceIn(0.35f, 1.05f)

            val fN = (springTargetNp - npBaseTranslationY) * stiffness - springVelNp * damping
            springVelNp += fN * dt
            npBaseTranslationY += springVelNp * dt

            val curNc = if (::notificationCenter.isInitialized) notificationCenter.restingOffsetPx else 0f
            val nextNc = if (hadNotifsInLayout) {
                val fM = (springTargetNc - curNc) * stiffness - springVelNc * damping
                springVelNc += fM * dt
                max(0f, curNc + springVelNc * dt)
            } else {
                springVelNc = 0f
                curNc
            }
            if (hadNotifsInLayout && ::notificationCenter.isInitialized) {
                notificationCenter.setAnimatedRestingOffset(nextNc)
            }

            applyDepthAndLayerTransforms()
            if (::resizeOverlay.isInitialized && resizeOverlay.isOpen) resizeOverlay.layoutAroundClock()

            val ncSettled = !hadNotifsInLayout || (abs(springTargetNc - nextNc) < 0.35f && abs(springVelNc) < 2f)
            val settled = abs(springTargetClock - clockRestOffset) < 0.35f && abs(springVelClock) < 2f &&
                    abs(springTargetClockFit - clockFitScale) < 0.003f && abs(springVelClockFit) < 0.02f &&
                    abs(springTargetNp - npBaseTranslationY) < 0.35f && abs(springVelNp) < 2f &&
                    ncSettled

            if (settled) {
                clockRestOffset = springTargetClock
                clockFitScale = springTargetClockFit
                npBaseTranslationY = springTargetNp
                if (hadNotifsInLayout && ::notificationCenter.isInitialized) {
                    notificationCenter.setAnimatedRestingOffset(springTargetNc)
                }
                springVelClock = 0f
                springVelClockFit = 0f
                springVelNp = 0f
                springVelNc = 0f
                springRunning = false
                springLastNs = 0L
                applyDepthAndLayerTransforms()
                if (::resizeOverlay.isInitialized && resizeOverlay.isOpen) resizeOverlay.layoutAroundClock()
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }
    private var flashOn = false
    private var flashBurstTimer: Runnable? = null
    private var toastTimer: Runnable? = null
    private var geometryDebounce: Runnable? = null
    private var minuteTickRunnable: Runnable? = null
    private var weatherTempStr = "☁ −3°"
    private var backCallback: OnBackInvokedCallback? = null

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateDateLabel()
            scheduleClockRender(force = false)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0 && scale > 0) {
                val pct = ((level * 100f) / scale).roundToInt().coerceIn(1, 100)
                val chg = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                if (::statusBar.isInitialized) statusBar.updateBattery(pct, chg)
                if (::island.isInitialized) island.updateBattery(pct, chg)
            }
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val bootStartNs = SystemClock.elapsedRealtimeNanos()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        store = StateStore(this)
        cfg = store.load()

        perf = PerfMonitor(this)
        glyphRenderer = ClockGlyphRenderer(this)
        adaptive = AdaptiveTheme()
        aiDepth = AIDepthManager(this)
        notifHub = NotificationHub(this)

        media = MediaController(this) { track, art, playing, pos, dur ->
            if (::npCard.isInitialized) {
                npCard.setTrack(track, art)
                npCard.setPlaying(playing)
                npCard.updateTime(pos, dur)
            }
            if (::island.isInitialized) {
                island.syncMedia(track, art, playing)
            }
            if (::musicLibraryOverlay.isInitialized && musicLibraryOverlay.isOpen) {
                musicLibraryOverlay.syncPlaybackState()
            }
        }
        media.setLibraryListener { tracks ->
            if (::musicLibraryOverlay.isInitialized && musicLibraryOverlay.isOpen) {
                musicLibraryOverlay.syncTracks(tracks)
            }
        }

        buildUi()
        setContentView(rootFrame)

        notifHub.setListener(object : NotificationHub.Listener {
            override fun onNotificationsUpdated(items: List<LockNotification>, added: LockNotification?, removedId: String?) {
                if (!::notificationCenter.isInitialized) return
                val appearingFromEmpty = cfg.notifications && items.isNotEmpty() && !hadNotifsInLayout
                if (appearingFromEmpty) {
                    updateMusicAndNotificationsLayout(animated = true)
                    notificationCenter.setNotifications(items, added, removedId)
                } else {
                    notificationCenter.setNotifications(items, added, removedId)
                    updateMusicAndNotificationsLayout(animated = true)
                }
                if (added != null) {
                    val icon = notifHub.getAppIcon(added.packageName, (18f * resources.displayMetrics.density).roundToInt())
                    island.pulse("${added.appName}: ${added.text}", added.accentColor, 1850L, iconBmp = icon)
                    if (cfg.music && npCard.visibility == View.VISIBLE && notifExpandFrac < 0.25f) {
                        npCard.animate().scaleX(0.974f).scaleY(0.974f).setDuration(110L).withEndAction {
                            npCard.animate().scaleX(1f).scaleY(1f).setDuration(380L).setInterpolator(OvershootInterpolator(1.9f)).start()
                        }.start()
                    }
                }
            }
        })
        notificationCenter.setNotifications(notifHub.getItems(), null, null)

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { handleBackNavigation() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback!!
            )
        }

        media.emit()
        if (media.hasAudioPermission()) {
            media.scanDeviceMusic()
        }

        registerReceivers()
        fetchWeatherAsync()

        perf.setListener { snap ->
            mainHandler.post { editorSheet.updatePerfUi(snap) }
        }
        perf.start()

        val initCols = adaptive.samplePresetDefaults(cfg)
        applyThemeColors(initCols)
        applyToggles()
        wallView.background = WallpaperEngine.makePlaceholderDrawable(cfg.wall)
        updateDateLabel()
        layoutScreenPositions()

        // Start clock artifact lookup/Stage-1 render immediately before first measure/layout pass
        scheduleClockRender(force = false)

        val cachedPreset = if (!cfg.hasCustomWall && cfg.wall != "custom") WallpaperEngine.peekPreset(cfg.wall) else null
        val isFirstLaunch = !store.hasSavedConfig()
        if (cachedPreset != null && !cfg.hasCustomSubject) {
            applyWallpaperAndTheme(reRenderClock = false)
            if (isFirstLaunch && cfg.smartWallTheme) {
                currentWallBmp?.let { bmp ->
                    runSmartWallpaperHarmony(bmp, animated = false, playEffect = false)
                }
            }
        } else {
            val needWall = cfg.hasCustomWall
            val needSubj = cfg.hasCustomSubject
            val wallId = cfg.wall
            bgExecutor.execute {
                val cw = if (needWall) store.loadCustomWall() else null
                val cs = if (needSubj) store.loadCustomSubject() else null
                WallpaperEngine.getWallpaper(wallId, cw)
                mainHandler.post {
                    if (isDestroyed) return@post
                    if (cw != null) customWallBmp = cw
                    if (cs != null) customSubjectBmp = cs
                    applyWallpaperAndTheme(reRenderClock = false)
                    if (isFirstLaunch && cfg.smartWallTheme) {
                        currentWallBmp?.let { bmp ->
                            runSmartWallpaperHarmony(bmp, animated = false, playEffect = false)
                        }
                    }
                }
            }
        }

        phoneShell.post {
            updateDateLabel()
            layoutScreenPositions()
            perf.recordWork("boot", (SystemClock.elapsedRealtimeNanos() - bootStartNs) / 1_000_000.0)
        }
        mainHandler.postDelayed({
            if (!isDestroyed) editorSheet.ensureBodiesBuilt()
        }, 280L)
    }

    private fun buildUi() {
        val dens = resources.displayMetrics.density

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            clipChildren = false
            clipToPadding = false
            isMotionEventSplittingEnabled = false
        }

        phoneShell = UnlockSwipeShell(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            clipToPadding = false
            isMotionEventSplittingEnabled = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        rootFrame.addView(phoneShell)

        // 1. Wallpaper Layer (z = 0)
        wallView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        phoneShell.addView(wallView)

        // 2. Clock Layer (z = 2)
        clockContainer = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        clockGhostView = ClockGhostView(this, backdrop).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        }
        clockView = ClockGlassView(this, glyphRenderer, backdrop, perf).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        }
        clockView.attachGhostView(clockGhostView)
        clockView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oTop, _, oBottom ->
            if ((bottom - top) != (oBottom - oTop)) {
                clockView.post {
                    updateMusicAndNotificationsLayout(animated = hasInitialLayout)
                }
            }
            if (resizeOverlay.isOpen) {
                resizeOverlay.post { if (resizeOverlay.isOpen) resizeOverlay.layoutAroundClock() }
            }
        }
        clockView.setOnArtifactCommitted { _, isFontSwitch ->
            if (resizeOverlay.isOpen) {
                resizeOverlay.post { resizeOverlay.layoutAroundClock() }
            }
            if (clockEditorStageFrac > 0.01f && isFontSwitch && hasStageSmoothState) {
                glideEditorStageToLatest()
            } else {
                stageGlideAnim?.cancel()
                if (clockEditorStageFrac > 0.01f) applyDepthAndLayerTransforms()
            }
            updateMusicAndNotificationsLayout(animated = hasInitialLayout)
            refreshAllGlass()
        }
        attachClockLongPressAndPull(clockView)
        clockContainer.addView(clockGhostView)
        clockContainer.addView(clockView)
        phoneShell.addView(clockContainer)

        // 3. Foreground Depth Subject Cutout (z = 3: above clock, below cards & topline!)
        subjectView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        phoneShell.addView(subjectView)

        // 3b. iOS Depth Separation Halo & Shimmer Overlay (z = 3.5: directly over clock & cutout)
        depthRevealView = DepthRevealOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        phoneShell.addView(depthRevealView)

        // 3c. Smart Wallpaper Color Harmony Prism Wave (sweeps over clock when auto-styled)
        harmonyAuraView = WallpaperHarmonyAuraView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        phoneShell.addView(harmonyAuraView)

        // 4. Resize Frame Overlay (z = 4)
        resizeOverlay = ClockResizeOverlayView(
            this,
            clockView,
            getLimits = { computeClockLimitsDp() },
            onConfigChanged = { syncUi ->
                if (syncUi && editorSheet.isOpen) editorSheet.syncFromConfig()
                updateMusicAndNotificationsLayout(animated = true)
            },
            onCommit = {
                store.save(cfg)
                updateMusicAndNotificationsLayout(animated = true)
                scheduleClockRender(force = true)
            },
            onReset = {
                cfg.size = 208f
                cfg.sx = 1.0f
                cfg.sy = 1.68f
                cfg.clockY = 0f
                store.save(cfg)
                layoutScreenPositions(animatedLayout = true)
                scheduleClockRender(force = true)
                if (editorSheet.isOpen) editorSheet.syncFromConfig()
            },
            onUndo = {
                undoSnap?.let { s ->
                    cfg.size = s.size
                    cfg.sx = s.sx
                    cfg.sy = s.sy
                    store.save(cfg)
                    updateMusicAndNotificationsLayout(animated = true)
                    scheduleClockRender(force = true)
                    if (editorSheet.isOpen) editorSheet.syncFromConfig()
                }
            },
            onClockTap = { openClockCustomizerFromResize() },
            onHaptic = { k -> haptic(k) }
        ).apply {
            layoutParams = FrameLayout.LayoutParams(200, 200, Gravity.TOP or Gravity.LEFT)
        }
        phoneShell.addView(resizeOverlay)

        // 5. Now Playing Glass Card (z = 4)
        npCard = NowPlayingCardView(
            this, backdrop, perf,
            onPrev = {
                haptic("pop")
                media.prev()
                if (::notificationCenter.isInitialized) notificationCenter.nudgeFromMusic()
            },
            onPlayToggle = {
                haptic("pop")
                media.togglePlay()
                if (::notificationCenter.isInitialized) notificationCenter.nudgeFromMusic()
            },
            onNext = {
                haptic("pop")
                media.next()
                if (::notificationCenter.isInitialized) notificationCenter.nudgeFromMusic()
            },
            onScrub = { ratio -> media.seekRatio(ratio) },
            onFavorite = {
                haptic("pop")
                island.pulse("تمت الإضافة للمفضلة", 0xFFFFD60A.toInt(), 1500L)
                showToast("مفضلة ✓")
            },
            onAirPlay = {
                haptic("pop")
                island.pulse("AirPlay نشط", 0xFF38BDF8.toInt(), 1600L)
                showToast("مكبر الصوت: الهاتف")
            }
        ).apply {
            onLongPress = {
                if (!editorSheet.isOpen && !cameraOverlay.isOpen) {
                    if (resizeOverlay.isOpen) resizeOverlay.close()
                    haptic("heavy")
                    musicLibraryOverlay.open()
                }
            }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                leftMargin = (14f * dens).roundToInt()
                rightMargin = (14f * dens).roundToInt()
            }
        }
        phoneShell.addView(npCard)

        // 5b. Liquid Glass Notification Center (z = 4)
        notificationCenter = NotificationCenterView(
            this,
            notifHub,
            backdrop,
            perf,
            getRootShell = { phoneShell },
            onDismissNotification = { item ->
                notifHub.dismiss(item.id)
            },
            onClearAll = {
                notifHub.clearAll()
                island.pulse("تم مسح الإشعارات", 0xFF38BDF8.toInt(), 1300L)
            },
            onCardTapped = { item ->
                if (!notifHub.openNotification(item)) {
                    island.pulse("${item.appName}: ${item.text}", item.accentColor, 1800L)
                }
            },
            onRequestAccess = {
                notifHub.openNotificationSettings()
                showToast("فعّل إشعارات OClock زجاج من القائمة")
            },
            onScrollExpandChanged = { frac ->
                notifExpandFrac = frac.coerceIn(0f, 1f)
                if (notifExpandFrac > 0.02f && ::resizeOverlay.isInitialized && resizeOverlay.isOpen) {
                    resizeOverlay.close()
                }
                applyDepthAndLayerTransforms()
                if (cfg.music) {
                    val interactive = notifExpandFrac < 0.50f
                    npCard.isClickable = interactive
                    npCard.isFocusable = interactive
                }
            },
            onDragMusicCoupling = { dyPx ->
                notifCouplingY = dyPx
                if (abs(dyPx) > 3f * dens && ::resizeOverlay.isInitialized && resizeOverlay.isOpen) {
                    resizeOverlay.close()
                }
                applyDepthAndLayerTransforms()
            },
            onHaptic = { k -> haptic(k) }
        ).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            onDisplayedCountChanged = {
                updateMusicAndNotificationsLayout(animated = true)
            }
        }
        phoneShell.addView(notificationCenter)

        // 6. Topline (Date + Weather) (z = 5)
        toplineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        dateText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(8f * dens, 0f, 1f * dens, Color.argb(72, 0, 0, 0))
        }
        wxText = TextView(this).apply {
            text = "  •  $weatherTempStr"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(8f * dens, 0f, 1f * dens, Color.argb(72, 0, 0, 0))
        }
        toplineRow.addView(dateText)
        toplineRow.addView(wxText)
        phoneShell.addView(toplineRow)

        // 7. Bottom Shortcuts + DND Pill (z = 5)
        bottomRow = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = false
            clipToPadding = false
            isMotionEventSplittingEnabled = false
            setPadding((24f * dens).roundToInt(), 0, (24f * dens).roundToInt(), 0)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (54f * dens).roundToInt(), Gravity.BOTTOM)
        }
        val btnSz = (52f * dens).roundToInt()
        shLeft = ShortcutCircleButton(
            this, "flash", backdrop, perf,
            onTapAction = { setFlashlight(!flashOn) },
            onHoldAction = { setFlashlight(!flashOn) },
            onHaptic = { k -> haptic(k) }
        ).apply {
            layoutParams = FrameLayout.LayoutParams(btnSz, btnSz, Gravity.START or Gravity.CENTER_VERTICAL)
        }

        dndPill = DndPillView(this, backdrop, perf).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, (34f * dens).roundToInt(), Gravity.CENTER)
            visibility = if (cfg.dnd) View.VISIBLE else View.GONE
            setOnClickListener {
                cfg.dnd = false
                store.save(cfg)
                applyToggles()
                island.syncDnd(false)
                haptic("tick")
            }
        }

        shRight = ShortcutCircleButton(
            this, "cam", backdrop, perf,
            onTapAction = { openCamera(autoShoot = false) },
            onHoldAction = { openCamera(autoShoot = true) },
            onHaptic = { k -> haptic(k) }
        ).apply {
            layoutParams = FrameLayout.LayoutParams(btnSz, btnSz, Gravity.END or Gravity.CENTER_VERTICAL)
        }
        bottomRow.addView(shLeft)
        bottomRow.addView(dndPill)
        bottomRow.addView(shRight)
        phoneShell.addView(bottomRow)

        // 7b. Unlocked Floating Bar (visible only after PIN unlock)
        unlockedBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        }
        fun makeUnlockedPill(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setPadding((18f * dens).roundToInt(), (10f * dens).roundToInt(), (18f * dens).roundToInt(), (10f * dens).roundToInt())
                background = GradientDrawable().apply {
                    setColor(Color.argb(88, 18, 22, 32))
                    cornerRadius = 999f
                    setStroke((1f * dens).roundToInt(), Color.argb(95, 255, 255, 255))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (6f * dens).roundToInt()
                    marginEnd = (6f * dens).roundToInt()
                }
                setOnClickListener { onClick() }
            }
        }
        unlockedBar.addView(makeUnlockedPill("🔒 قفل الشاشة") { setUnlocked(false) })
        unlockedBar.addView(makeUnlockedPill("⚙ تخصيص") {
            haptic("tick")
            media.setSheetOpen(true)
            editorSheet.open(EditorSheetView.Mode.WALLPAPER)
        })
        phoneShell.addView(unlockedBar)

        // 7c. iOS 26 Liquid Glass Passcode Overlay (below status bar & dynamic island)
        passcodeOverlay = PasscodeOverlayView(
            context = this,
            backdrop = backdrop,
            perf = perf,
            getRootShell = { phoneShell },
            getExpectedPin = { cfg.passcodePin },
            onUnlocked = {
                passcodeProgress = 0f
                setUnlocked(true)
                island.pulse("تم فتح القفل ✓", 0xFF30D158.toInt(), 1800L)
            },
            onNewPinSaved = { newPin ->
                cfg.passcodePin = newPin
                cfg.passcodeEnabled = true
                store.save(cfg)
                if (editorSheet.isOpen) editorSheet.syncFromConfig()
                island.pulse("تم حفظ رمز الدخول ✓", 0xFF30D158.toInt(), 1800L)
                showToast("تم تحديث رمز PIN ✓")
            },
            onProgressChanged = { p ->
                passcodeProgress = p
                applyDepthAndLayerTransforms()
            },
            onClosed = {
                passcodeProgress = 0f
                applyDepthAndLayerTransforms()
            },
            onHaptic = { k -> haptic(k) }
        ).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        phoneShell.addView(passcodeOverlay)

        // 8. Status Bar & Home Indicator & Dynamic Island (z = 6..7)
        statusBar = StatusBarView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (22f * dens).roundToInt(), Gravity.TOP)
        }
        phoneShell.addView(statusBar)

        homeBar = View(this).apply {
            layoutParams = FrameLayout.LayoutParams((134f * dens).roundToInt(), (5f * dens).roundToInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 99f
            }
            alpha = 0.65f
        }
        phoneShell.addView(homeBar)

        island = DynamicIslandView(this).apply {
            onPrev = { media.prev() }
            onPlayToggle = { media.togglePlay() }
            onNext = { media.next() }
            onToggleFlash = { setFlashlight(!flashOn) }
            onOpenLibrary = {
                if (!editorSheet.isOpen && !cameraOverlay.isOpen) {
                    musicLibraryOverlay.open()
                }
            }
            onHaptic = { k -> haptic(k) }
            syncDnd(cfg.dnd, silent = true)
        }
        phoneShell.addView(island)

        // 9. Fullscreen Flashlight Overlay (z = 8)
        flashOverlay = object : FrameLayout(this) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val r = max(width, height).toFloat()
                p.shader = RadialGradient(
                    width * 0.5f, height * 0.82f, r,
                    intArrayOf(0xFFFFFEF4.toInt(), Color.WHITE),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            }
        }.apply {
            setWillNotDraw(false)
            visibility = View.GONE
            alpha = 0f
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            val offBtn = TextView(context).apply {
                text = "إطفاء المصباح"
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setPadding((22f * dens).roundToInt(), (11f * dens).roundToInt(), (22f * dens).roundToInt(), (11f * dens).roundToInt())
                background = GradientDrawable().apply {
                    setColor(Color.argb(36, 0, 0, 0))
                    cornerRadius = 999f
                }
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = (56f * dens).roundToInt()
                }
                setOnClickListener { setFlashlight(false) }
            }
            addView(offBtn)
        }
        phoneShell.addView(flashOverlay)

        // 10. Fullscreen Camera2 Overlay (z = 9)
        cameraOverlay = CameraOverlayView(
            this,
            onRequestPermission = {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM_PERM)
            },
            onShutterFired = {
                haptic("shutter")
                island.pulse("تم التقاط الصورة", Color.WHITE, 1600L)
                showToast("تم التقاط الصورة")
            },
            onClosed = {}
        ).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        phoneShell.addView(cameraOverlay)

        // 11. Editor Bottom Sheet (z = 10)
        editorSheet = EditorSheetView(
            context = this,
            cfg = cfg,
            perf = perf,
            backdrop = backdrop,
            getRootShell = { phoneShell },
            getFontTypeface = { fontId -> glyphRenderer.getTypeface(fontId) },
            callbacks = this
        ).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        phoneShell.addView(editorSheet)

        // 11b. Dark Liquid Glass Music Library Overlay (z = 10.5)
        musicLibraryOverlay = MusicLibraryOverlayView(
            this,
            media,
            backdrop,
            perf,
            getRootShell = { phoneShell },
            onRequestPermission = {
                ActivityCompat.requestPermissions(this, media.requiredPermissions(), REQ_MEDIA_PERM)
            },
            onOpenAppSettings = {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                } catch (_: Throwable) {}
            },
            onPickManualAudio = { launchPhotoPicker(REQ_AUDIO) },
            onHaptic = { k -> haptic(k) },
            onNotify = { msg, col -> island.pulse(msg, col, 1600L) },
            onClosed = {}
        ).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        phoneShell.addView(musicLibraryOverlay)

        // 12. Floating Toast Capsule + Depth Loading Pill + Smart Palette Pill (z = 11)
        depthLoadingPill = DepthLoadingPill(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        }
        phoneShell.addView(depthLoadingPill)

        harmonyPillView = WallpaperHarmonyPill(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        }
        phoneShell.addView(harmonyPillView)

        toastView = TextView(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE
            alpha = 0f
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding((16f * dens).roundToInt(), (8f * dens).roundToInt(), (16f * dens).roundToInt(), (8f * dens).roundToInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(208, 18, 20, 26))
                cornerRadius = 999f
                setStroke((0.8f * dens).roundToInt(), Color.argb(46, 255, 255, 255))
            }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        }
        phoneShell.addView(toastView)

        attachWallpaperPullAndDoubleTap()
        attachInsetsListener()
        phoneShell.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            val newW = r - l
            val newH = b - t
            val oldW = or - ol
            val oldH = ob - ot
            if (newW > 0 && newH > 0 && (newW != oldW || newH != oldH)) {
                phoneShell.post {
                    if (isDestroyed) return@post
                    val bmp = currentWallBmp
                    if (bmp != null) {
                        val subj = if (cfg.depth) customSubjectBmp else null
                        backdrop.updateWallAndSubject(bmp, subj, cfg.depth, max(1, phoneShell.width), max(1, phoneShell.height), wallView.scaleX)
                        refreshAllGlass()
                    }
                    scheduleClockRender(force = false)
                    if (resizeOverlay.isOpen) resizeOverlay.layoutAroundClock()
                }
            }
        }
    }

    private fun attachInsetsListener() {
        val dens = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val newTop = max(sys.top, (14f * dens).roundToInt())
            val newBottom = max(sys.bottom, (14f * dens).roundToInt())
            val sysChanged = !hasInitialLayout || newTop != safeTopPx || newBottom != safeBottomPx
            safeTopPx = newTop
            safeBottomPx = newBottom
            if (sysChanged) {
                layoutScreenPositions()
            }
            if (::musicLibraryOverlay.isInitialized) {
                musicLibraryOverlay.setImeBottomPx(imeBottom)
            }
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            rootFrame,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    if (::musicLibraryOverlay.isInitialized && musicLibraryOverlay.isOpen) {
                        val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        musicLibraryOverlay.setImeBottomPx(imeBottom)
                    }
                    return insets
                }
            }
        )
    }

    private fun layoutScreenPositions(animatedLayout: Boolean = false) {
        val dens = resources.displayMetrics.density
        val isShortLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                (resources.displayMetrics.heightPixels / dens) < 560f

        // Wide tablet frame (mirrors @media (min-width: 560px) desktop/tablet shell)
        val screenWDp = resources.displayMetrics.widthPixels / dens
        val screenHDp = resources.displayMetrics.heightPixels / dens
        val shellLp = phoneShell.layoutParams as FrameLayout.LayoutParams
        if (screenWDp >= 560f && screenHDp >= 600f && resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            shellLp.width = (430f * dens).roundToInt()
            shellLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            phoneShell.clipToOutline = true
            phoneShell.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, 44f * dens)
                }
            }
        } else {
            shellLp.width = FrameLayout.LayoutParams.MATCH_PARENT
            shellLp.height = FrameLayout.LayoutParams.MATCH_PARENT
            phoneShell.clipToOutline = false
        }
        phoneShell.layoutParams = shellLp

        (statusBar.layoutParams as FrameLayout.LayoutParams).topMargin = safeTopPx + (6f * dens).roundToInt()
        (island.layoutParams as FrameLayout.LayoutParams).topMargin = safeTopPx + (7f * dens).roundToInt()
        island.visibility = if (isShortLandscape) View.GONE else View.VISIBLE

        (toplineRow.layoutParams as FrameLayout.LayoutParams).topMargin = safeTopPx + (if (isShortLandscape) 26f else 48f).let { (it * dens).roundToInt() }
        (clockContainer.layoutParams as FrameLayout.LayoutParams).topMargin =
            safeTopPx + ((if (isShortLandscape) 48f else 78f) + cfg.clockY).let { (it * dens).roundToInt() }

        val npLp = npCard.layoutParams as FrameLayout.LayoutParams
        if (isShortLandscape) {
            npLp.width = min((330f * dens).roundToInt(), (resources.displayMetrics.widthPixels * 0.46f).roundToInt())
            npLp.gravity = Gravity.BOTTOM or Gravity.LEFT
            npLp.bottomMargin = safeBottomPx + (20f * dens).roundToInt()
        } else {
            val availW = max(1, phoneShell.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels)
            npLp.width = min((416f * dens).roundToInt(), max(220, availW - (16f * dens).roundToInt()))
            npLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            npLp.bottomMargin = safeBottomPx + (94f * dens).roundToInt()
        }
        npCard.layoutParams = npLp

        (bottomRow.layoutParams as FrameLayout.LayoutParams).bottomMargin = safeBottomPx + (26f * dens).roundToInt()
        (unlockedBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = safeBottomPx + (28f * dens).roundToInt()
        (homeBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = safeBottomPx + (7f * dens).roundToInt()
        (depthLoadingPill.layoutParams as FrameLayout.LayoutParams).bottomMargin = safeBottomPx + (102f * dens).roundToInt()
        (harmonyPillView.layoutParams as FrameLayout.LayoutParams).topMargin = safeTopPx + (42f * dens).roundToInt()
        (toastView.layoutParams as FrameLayout.LayoutParams).topMargin = safeTopPx + (52f * dens).roundToInt()
        passcodeOverlay.applySafeInsets(safeTopPx, safeBottomPx)
        musicLibraryOverlay.applySafeInsets(safeTopPx, safeBottomPx)
        editorSheet.applySafeInsets(safeBottomPx)
        updateMusicAndNotificationsLayout(animated = animatedLayout && hasInitialLayout)
        hasInitialLayout = true
        phoneShell.requestLayout()
    }

    private fun updateMusicAndNotificationsLayout(animated: Boolean) {
        if (!::notificationCenter.isInitialized || !::npCard.isInitialized) return
        val dens = resources.displayMetrics.density
        val isShortLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                (resources.displayMetrics.heightPixels / dens) < 560f
        val shellW = phoneShell.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val shellH = phoneShell.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

        val items = notifHub.getItems()
        val showNotifs = cfg.notifications && items.isNotEmpty()
        val isAppearingFromEmpty = showNotifs && !hadNotifsInLayout
        val visibleNotifCount = if (showNotifs) {
            max(1, notificationCenter.displayedCount().takeIf { it > 0 } ?: items.size)
        } else 0
        val ncLp = notificationCenter.layoutParams as FrameLayout.LayoutParams

        if (isShortLandscape) {
            hadNotifsInLayout = showNotifs
            springRunning = false
            Choreographer.getInstance().removeFrameCallback(layoutSpringCallback)
            clockRestOffset = 0f
            clockFitScale = 1f
            npBaseTranslationY = 0f
            springTargetClock = 0f
            springTargetClockFit = 1f
            springTargetNp = 0f
            springTargetNc = 0f
            springVelClock = 0f
            springVelClockFit = 0f
            springVelNp = 0f
            springVelNc = 0f
            notifExpandFrac = 0f
            ncLp.width = min((340f * dens).roundToInt(), (shellW * 0.46f).roundToInt())
            ncLp.height = max((110f * dens).roundToInt(), shellH - safeTopPx - safeBottomPx - (48f * dens).roundToInt())
            ncLp.gravity = Gravity.TOP or Gravity.RIGHT
            ncLp.topMargin = safeTopPx + (32f * dens).roundToInt()
            ncLp.rightMargin = (14f * dens).roundToInt()
            ncLp.leftMargin = 0
            ncLp.bottomMargin = 0
            notificationCenter.layoutParams = ncLp
            notificationCenter.configureLayoutBounds(
                expandedTopPx = ncLp.topMargin,
                restingTopPx = ncLp.topMargin,
                fullHeightPx = ncLp.height,
                landscapeCompact = true
            )
            applyDepthAndLayerTransforms()
            return
        }

        val cardW = min((400f * dens).roundToInt(), max(220, shellW - (28f * dens).roundToInt()))
        val shortcutsReserve = safeBottomPx + (84f * dens).roundToInt()
        val shortcutsTop = shellH - shortcutsReserve
        val minExpandedTop = safeTopPx + (54f * dens).roundToInt()
        val maxExpandedTop = max(minExpandedTop, shortcutsTop - (280f * dens).roundToInt())
        val expandedTop = (shellH * 0.35f).roundToInt().coerceIn(minExpandedTop, maxExpandedTop)
        val npBaseBottom = safeBottomPx + (94f * dens).roundToInt()
        val npH = npCard.height.takeIf { it > 0 } ?: (110f * dens).roundToInt()
        val headerH = (40f * dens).roundToInt()
        val clockGap = (10f * dens).roundToInt()
        val cardGap = (8f * dens).roundToInt()

        val art = clockView.activeArtifact
        val clockNaturalTop = safeTopPx + ((78f + cfg.clockY) * dens).roundToInt()
        val rawViewH = if (clockView.height > 0) clockView.height.toFloat() else (art?.hDp ?: 210f) * dens
        val cScaleY = max(0.35f, clockView.scaleY)
        val inkTopInView = ((art?.inkTopDp ?: 18f) * dens) * cScaleY
        val inkBotInView = ((art?.inkBottomDp ?: max(48f, rawViewH / dens - 18f)) * dens) * cScaleY
        val clockNaturalInkTop = clockNaturalTop + inkTopInView
        val clockNaturalInkBot = clockNaturalTop + inkBotInView
        val curInkH = max(1f, inkBotInView - inkTopInView)
        val minClockInkTop = safeTopPx + (74f * dens)
        val maxUpLift = max(0f, clockNaturalInkTop - minClockInkTop)

        var targetClockRest = 0f
        var targetClockFit = 1f
        var targetNpTy = 0f
        var targetNcResting = notificationCenter.restingOffsetPx

        if (!showNotifs) {
            notifExpandFrac = 0f
            npCard.isClickable = true
            npCard.isFocusable = true
            // Keep existing restingOffsetPx steady while NotificationCenter fades out & hides cleanly
            targetNcResting = notificationCenter.restingOffsetPx
            if (cfg.music) {
                val npNaturalTop = shellH - npBaseBottom - npH
                val overlap = clockNaturalInkBot - (npNaturalTop - clockGap)
                if (overlap > 0f) {
                    targetClockRest = -min(overlap, maxUpLift)
                }
                val effClockBot = clockNaturalInkBot + targetClockRest
                if (effClockBot > npNaturalTop - clockGap) {
                    val availInkH = max(72f * dens, (npNaturalTop - clockGap) - (clockNaturalInkTop + targetClockRest))
                    targetClockFit = (availInkH / curInkH).coerceIn(0.45f, 1f)
                }
            }
        } else {
            val desiredListH = when {
                visibleNotifCount <= 1 -> (78f * dens).roundToInt()
                visibleNotifCount == 2 -> (96f * dens).roundToInt()
                else -> (106f * dens).roundToInt()
            }
            val desiredTotalNotifH = headerH + desiredListH
            val minTotalNotifH = headerH + (74f * dens).roundToInt()
            val idealRestingTop = max(expandedTop, shortcutsTop - desiredTotalNotifH)
            val maxRestingTop = max(expandedTop, shortcutsTop - minTotalNotifH)

            val restingTop: Int
            if (cfg.music) {
                val idealNpBottom = (shellH - idealRestingTop) + cardGap
                val minNpBottom = max(npBaseBottom, (shellH - maxRestingTop) + cardGap)
                val idealNpTop = shellH - idealNpBottom - npH
                val neededLift = clockNaturalInkBot - (idealNpTop - clockGap)
                targetClockRest = -min(max(0f, neededLift), maxUpLift)
                val effectiveClockBot = clockNaturalInkBot + targetClockRest

                val targetNpBottom = min(idealNpBottom, max(minNpBottom, (shellH - effectiveClockBot - npH - clockGap).roundToInt()))
                targetNpTy = -(targetNpBottom - npBaseBottom).toFloat()

                // Strictly keep notifications below music player bottom + gap (never overlap music card!)
                restingTop = max(expandedTop, (shellH - targetNpBottom) + cardGap)
                val targetNpTop = shellH - targetNpBottom - npH
                if (effectiveClockBot > targetNpTop - clockGap) {
                    val availInkH = max(72f * dens, (targetNpTop - clockGap) - (clockNaturalInkTop + targetClockRest))
                    targetClockFit = (availInkH / curInkH).coerceIn(0.45f, 1f)
                }
            } else {
                val neededLift = clockNaturalInkBot - (idealRestingTop - clockGap)
                targetClockRest = -min(max(0f, neededLift), maxUpLift)
                val effectiveClockBot = clockNaturalInkBot + targetClockRest
                targetNpTy = 0f

                restingTop = max(effectiveClockBot.roundToInt() + clockGap, idealRestingTop)
                    .coerceIn(expandedTop, maxRestingTop)
                if (effectiveClockBot > restingTop - clockGap) {
                    val availInkH = max(72f * dens, (restingTop - clockGap) - (clockNaturalInkTop + targetClockRest))
                    targetClockFit = (availInkH / curInkH).coerceIn(0.45f, 1f)
                }
            }

            val fullH = max((140f * dens).roundToInt(), shortcutsTop - expandedTop)
            val needLayout = ncLp.width != cardW || ncLp.height != fullH ||
                    ncLp.topMargin != expandedTop || ncLp.rightMargin != 0 ||
                    ncLp.gravity != (Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            if (needLayout) {
                ncLp.width = cardW
                ncLp.height = fullH
                ncLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ncLp.leftMargin = 0
                ncLp.rightMargin = 0
                ncLp.bottomMargin = 0
                ncLp.topMargin = expandedTop
                notificationCenter.layoutParams = ncLp
            }
            targetNcResting = max(0f, (restingTop - expandedTop).toFloat())
            notificationCenter.configureLayoutBounds(
                expandedTopPx = expandedTop,
                restingTopPx = restingTop,
                fullHeightPx = fullH,
                landscapeCompact = false,
                preserveCurrentOffset = animated && !isAppearingFromEmpty
            )
        }

        hadNotifsInLayout = showNotifs
        springTargetClock = targetClockRest
        springTargetClockFit = targetClockFit
        springTargetNp = targetNpTy
        springTargetNc = targetNcResting

        val curNc = notificationCenter.restingOffsetPx
        val needsAnim = animated && (
            abs(springTargetNp - npBaseTranslationY) > 0.5f ||
            abs(springTargetClock - clockRestOffset) > 0.5f ||
            abs(springTargetClockFit - clockFitScale) > 0.004f ||
            (showNotifs && !isAppearingFromEmpty && abs(springTargetNc - curNc) > 0.5f)
        )

        if (needsAnim) {
            if (!springRunning) {
                springRunning = true
                springLastNs = 0L
                Choreographer.getInstance().postFrameCallback(layoutSpringCallback)
            }
        } else {
            springRunning = false
            Choreographer.getInstance().removeFrameCallback(layoutSpringCallback)
            clockRestOffset = springTargetClock
            clockFitScale = springTargetClockFit
            npBaseTranslationY = springTargetNp
            if (showNotifs) notificationCenter.setAnimatedRestingOffset(springTargetNc)
            springVelClock = 0f
            springVelClockFit = 0f
            springVelNp = 0f
            springVelNc = 0f
            applyDepthAndLayerTransforms()
        }
    }

    private fun ptrRawX(ev: MotionEvent, idx: Int): Float = ev.getX(idx) + (ev.rawX - ev.x)
    private fun ptrRawY(ev: MotionEvent, idx: Int): Float = ev.getY(idx) + (ev.rawY - ev.y)

    private fun cancelActivePullForPasscode() {
        clockHoldPendingRunnable?.let { mainHandler.removeCallbacks(it) }
        clockHoldPendingRunnable = null
        wallHoldPendingRunnable?.let { mainHandler.removeCallbacks(it) }
        wallHoldPendingRunnable = null
        pullAnimator?.cancel()
        pullAnimator = null
        isWallOrClockPulling = false
        if (pullY > 0.01f) {
            pullY = 0f
        }
    }

    private fun openClockCustomizerFromResize() {
        if (editorSheet.isOpen && editorSheet.currentMode == EditorSheetView.Mode.CLOCK) return
        if (resizeOverlay.isOpen) resizeOverlay.close()
        if (::notificationCenter.isInitialized && notificationCenter.expandFrac > 0.05f) {
            notificationCenter.collapseScrollIfExpanded()
        }
        media.setSheetOpen(true)
        editorSheet.open(EditorSheetView.Mode.CLOCK)
    }

    private fun openWallpaperCustomizer() {
        if (editorSheet.isOpen && editorSheet.currentMode == EditorSheetView.Mode.WALLPAPER) return
        if (resizeOverlay.isOpen) resizeOverlay.close()
        if (::notificationCenter.isInitialized && notificationCenter.expandFrac > 0.05f) {
            notificationCenter.collapseScrollIfExpanded()
        }
        haptic("heavy")
        media.setSheetOpen(true)
        editorSheet.open(EditorSheetView.Mode.WALLPAPER)
    }

    private val editorStageSpringCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!editorStageSpringRunning) return
            val dt = if (editorStageLastNs == 0L) 0.016f
            else ((frameTimeNanos - editorStageLastNs) / 1_000_000_000f).coerceIn(0.004f, 0.032f)
            editorStageLastNs = frameTimeNanos

            val omega = 21.5f
            val zeta = 0.88f
            val stiffness = omega * omega
            val damping = 2f * zeta * omega

            val aClock = stiffness * (editorStageTargetClock - clockEditorStageFrac) - damping * editorStageVelClock
            editorStageVelClock += aClock * dt
            clockEditorStageFrac = (clockEditorStageFrac + editorStageVelClock * dt).coerceIn(0f, 1.05f)

            val aWall = stiffness * (editorStageTargetWall - wallEditorStageFrac) - damping * editorStageVelWall
            editorStageVelWall += aWall * dt
            wallEditorStageFrac = (wallEditorStageFrac + editorStageVelWall * dt).coerceIn(0f, 1.05f)

            applyDepthAndLayerTransforms()

            val settled =
                abs(editorStageTargetClock - clockEditorStageFrac) < 0.002f &&
                abs(editorStageVelClock) < 0.01f &&
                abs(editorStageTargetWall - wallEditorStageFrac) < 0.002f &&
                abs(editorStageVelWall) < 0.01f

            if (settled) {
                clockEditorStageFrac = editorStageTargetClock
                wallEditorStageFrac = editorStageTargetWall
                editorStageVelClock = 0f
                editorStageVelWall = 0f
                editorStageSpringRunning = false
                applyDepthAndLayerTransforms()
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private fun animateEditorStage(clockTarget: Float, wallTarget: Float) {
        editorStageTargetClock = clockTarget
        editorStageTargetWall = wallTarget
        if (abs(clockEditorStageFrac - clockTarget) < 0.003f &&
            abs(wallEditorStageFrac - wallTarget) < 0.003f &&
            abs(editorStageVelClock) < 0.01f && abs(editorStageVelWall) < 0.01f
        ) {
            editorStageSpringRunning = false
            Choreographer.getInstance().removeFrameCallback(editorStageSpringCallback)
            clockEditorStageFrac = clockTarget
            wallEditorStageFrac = wallTarget
            editorStageVelClock = 0f
            editorStageVelWall = 0f
            applyDepthAndLayerTransforms()
            return
        }
        if (!editorStageSpringRunning) {
            editorStageSpringRunning = true
            editorStageLastNs = 0L
            Choreographer.getInstance().postFrameCallback(editorStageSpringCallback)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachClockLongPressAndPull(target: View) {
        val dens = resources.displayMetrics.density
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val wobble = 14f * dens
        var activePtrId = MotionEvent.INVALID_POINTER_ID
        var downX = 0f
        var downY = 0f
        var holdFired = false
        var pulling = false
        var downInResize = false
        val holdRunnable = Runnable {
            if (!holdFired && !pulling && !editorSheet.isOpen && !resizeOverlay.isOpen &&
                !passcodeOverlay.isOpen && passcodeProgress <= 0.01f
            ) {
                holdFired = true
                undoSnap = cfg.snapshotForUndo()
                resizeOverlay.open()
                island.pulse("تخصيص حجم الساعة", 0xFF38BDF8.toInt(), 1500L)
            }
        }
        clockHoldPendingRunnable = holdRunnable

        target.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isUnlocked || passcodeOverlay.isOpen || passcodeProgress > 0.01f || editorSheet.isOpen) {
                        return@setOnTouchListener false
                    }
                    pullAnimator?.cancel()
                    activePtrId = ev.getPointerId(0)
                    downX = ev.rawX
                    downY = ev.rawY
                    holdFired = false
                    pulling = false
                    downInResize = resizeOverlay.isOpen
                    mainHandler.removeCallbacks(holdRunnable)
                    if (!downInResize) {
                        mainHandler.postDelayed(holdRunnable, 360L)
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    mainHandler.removeCallbacks(holdRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (downInResize) return@setOnTouchListener true
                    if (passcodeOverlay.isOpen || passcodeProgress > 0.02f) {
                        mainHandler.removeCallbacks(holdRunnable)
                        if (pulling) {
                            pulling = false
                            isWallOrClockPulling = false
                            pullY = 0f
                            applyDepthAndLayerTransforms()
                        }
                        activePtrId = MotionEvent.INVALID_POINTER_ID
                        return@setOnTouchListener false
                    }
                    val idx = ev.findPointerIndex(activePtrId)
                    if (idx < 0) return@setOnTouchListener false
                    val dx = ptrRawX(ev, idx) - downX
                    val dy = ptrRawY(ev, idx) - downY
                    if (!holdFired && hypot(dx, dy) > wobble) {
                        mainHandler.removeCallbacks(holdRunnable)
                    }
                    if (!holdFired && !pulling && dy > slop && abs(dy) > abs(dx)) {
                        pulling = true
                        isWallOrClockPulling = true
                        mainHandler.removeCallbacks(holdRunnable)
                    }
                    if (pulling) {
                        val posDy = max(0f, dy / dens)
                        val yDp = 150f * (1f - exp(-posDy / 500f))
                        applyPullOffset(yDp * dens)
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.getPointerId(ev.actionIndex) == activePtrId) {
                        mainHandler.removeCallbacks(holdRunnable)
                        activePtrId = MotionEvent.INVALID_POINTER_ID
                        downInResize = false
                        if (pulling) {
                            pulling = false
                            isWallOrClockPulling = false
                            releasePullSpring()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(holdRunnable)
                    val idx = ev.findPointerIndex(activePtrId).let { if (it >= 0) it else 0 }
                    val upX = ptrRawX(ev, idx)
                    val upY = ptrRawY(ev, idx)
                    activePtrId = MotionEvent.INVALID_POINTER_ID
                    val wasResizeTap = downInResize && resizeOverlay.isOpen && hypot(upX - downX, upY - downY) < slop * 1.4f
                    downInResize = false
                    if (pulling) {
                        pulling = false
                        isWallOrClockPulling = false
                        releasePullSpring()
                    } else if (wasResizeTap) {
                        haptic("pop")
                        openClockCustomizerFromResize()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(holdRunnable)
                    activePtrId = MotionEvent.INVALID_POINTER_ID
                    downInResize = false
                    if (pulling) {
                        pulling = false
                        isWallOrClockPulling = false
                        releasePullSpring()
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachWallpaperPullAndDoubleTap() {
        val dens = resources.displayMetrics.density
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val wobble = 14f * dens
        var activePtrId = MotionEvent.INVALID_POINTER_ID
        var dragging = false
        var startY = 0f
        var startX = 0f
        var wallHoldFired = false
        var wasResizeOpenOnDown = false

        val wallHoldRunnable = Runnable {
            if (!wallHoldFired && !dragging && !editorSheet.isOpen && !cameraOverlay.isOpen &&
                !musicLibraryOverlay.isOpen && !passcodeOverlay.isOpen && passcodeProgress <= 0.01f
            ) {
                wallHoldFired = true
                openWallpaperCustomizer()
            }
        }
        wallHoldPendingRunnable = wallHoldRunnable

        wallView.setOnTouchListener { _, ev ->
            if (editorSheet.isOpen || cameraOverlay.isOpen ||
                passcodeOverlay.isOpen || passcodeProgress > 0.01f
            ) {
                mainHandler.removeCallbacks(wallHoldRunnable)
                activePtrId = MotionEvent.INVALID_POINTER_ID
                if (dragging) {
                    dragging = false
                    isWallOrClockPulling = false
                    pullY = 0f
                    applyDepthAndLayerTransforms()
                }
                return@setOnTouchListener false
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (island.isExpanded) island.collapseIfExpanded()
                    pullAnimator?.cancel()
                    activePtrId = ev.getPointerId(0)
                    startX = ev.rawX
                    startY = ev.rawY
                    dragging = false
                    wallHoldFired = false
                    wasResizeOpenOnDown = resizeOverlay.isOpen
                    mainHandler.removeCallbacks(wallHoldRunnable)
                    mainHandler.postDelayed(wallHoldRunnable, 560L)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    mainHandler.removeCallbacks(wallHoldRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = ev.findPointerIndex(activePtrId)
                    if (idx < 0) return@setOnTouchListener false
                    val dy = ptrRawY(ev, idx) - startY
                    val dx = ptrRawX(ev, idx) - startX
                    if (!wallHoldFired && hypot(dx, dy) > wobble) {
                        mainHandler.removeCallbacks(wallHoldRunnable)
                    }
                    if (!wallHoldFired && !dragging && dy > slop && abs(dy) > abs(dx)) {
                        dragging = true
                        isWallOrClockPulling = true
                        mainHandler.removeCallbacks(wallHoldRunnable)
                        if (resizeOverlay.isOpen) resizeOverlay.close()
                    }
                    if (dragging) {
                        val posDy = max(0f, dy / dens)
                        val yDp = 150f * (1f - exp(-posDy / 500f))
                        applyPullOffset(yDp * dens)
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.getPointerId(ev.actionIndex) == activePtrId) {
                        mainHandler.removeCallbacks(wallHoldRunnable)
                        activePtrId = MotionEvent.INVALID_POINTER_ID
                        if (dragging) {
                            dragging = false
                            isWallOrClockPulling = false
                            releasePullSpring()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(wallHoldRunnable)
                    activePtrId = MotionEvent.INVALID_POINTER_ID
                    if (dragging) {
                        dragging = false
                        isWallOrClockPulling = false
                        releasePullSpring()
                    } else if (!wallHoldFired) {
                        if (wasResizeOpenOnDown || resizeOverlay.isOpen) {
                            resizeOverlay.close()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(wallHoldRunnable)
                    activePtrId = MotionEvent.INVALID_POINTER_ID
                    if (dragging) {
                        dragging = false
                        isWallOrClockPulling = false
                        releasePullSpring()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun applyPullOffset(yPx: Float) {
        pullY = yPx
        applyDepthAndLayerTransforms()
    }

    private fun applyDepthAndLayerTransforms() {
        val dens = resources.displayMetrics.density
        val passFadeT = (passcodeProgress / 0.30f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val pullEff = pullY * (1f - passFadeT)
        val yDp = pullEff / dens
        val exp = notifExpandFrac.coerceIn(0f, 1f)
        // liftLead >= exp at all t in (0,1), guaranteeing npCard & clock ALWAYS clear NotificationCenter upward with zero overlap!
        val liftLead = 1f - (1f - exp) * (1f - exp)
        val fastFade = (exp / 0.40f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val clockFade = (exp / 0.52f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val ncTravel = if (::notificationCenter.isInitialized) notificationCenter.restingOffsetPx else 0f
        val clockLiftTravel = max(290f * dens, ncTravel + 64f * dens)
        val npLiftTravel = ncTravel + 52f * dens

        val anyEditStage = max(clockEditorStageFrac, wallEditorStageFrac).coerceIn(0f, 1f)
        val editFadeOut = (1f - (anyEditStage / 0.45f).coerceIn(0f, 1f)).let { it * it * (3f - 2f * it) }
        val baseLockAlpha = if (isUnlocked) 0f else (1f - passFadeT) * (1f - unlockedFrac)
        val lockOverlayAlpha = (baseLockAlpha * editFadeOut).coerceIn(0f, 1f)
        val clockStageAlpha = ((1f - clockFade) * max(baseLockAlpha, anyEditStage) * (1f - passFadeT)).coerceIn(0f, 1f)
        val toplineStageAlpha = ((1f - fastFade) * max(baseLockAlpha, anyEditStage) * (1f - passFadeT)).coerceIn(0f, 1f)
        val lockScaleMult = 1f - 0.055f * passcodeProgress - 0.04f * unlockedFrac
        val fullyUnlocked = (isUnlocked || unlockedFrac >= 0.995f) && anyEditStage < 0.01f
        val hideLockChrome = if (fullyUnlocked || passFadeT >= 0.995f) View.INVISIBLE else View.VISIBLE

        if (::resizeOverlay.isInitialized && resizeOverlay.isOpen &&
            (notifExpandFrac > 0.02f || passcodeProgress > 0.02f || isUnlocked)
        ) {
            resizeOverlay.close()
        }

        // Keep wallpaper & foreground depth subject 100% locked together during notification scroll so depth cutout stays intact!
        val wallZoom = (1f + yDp * 0.00028f + passcodeProgress * 0.015f) * (1f - 0.018f * wallEditorStageFrac)
        if (!isDepthRevealAnimating) {
            wallView.scaleX = wallZoom
            wallView.scaleY = wallZoom
            subjectView.scaleX = wallZoom
            subjectView.scaleY = wallZoom
            subjectView.translationY = 0f
            subjectView.alpha = 1f
        }

        val artClock = clockView.activeArtifact
        val inkTopPx = ((artClock?.inkTopDp ?: 18f) * dens) * max(0.35f, clockView.scaleY)
        var stageOffsetY = 0f
        var stageFit = 1f
        if (clockEditorStageFrac > 0.001f) {
            if (stageGlideAnim?.isRunning == true && hasStageSmoothState) {
                stageOffsetY = stageCurOffsetY
                stageFit = stageCurFit
            } else {
                val (targetY, targetFit) = computeEditorStageTarget()
                stageOffsetY = targetY
                stageFit = targetFit
                stageCurOffsetY = targetY
                stageCurFit = targetFit
                hasStageSmoothState = true
            }
        } else {
            stageGlideAnim?.cancel()
            hasStageSmoothState = false
        }

        val effClockRest = clockRestOffset * (1f - clockEditorStageFrac) + stageOffsetY * clockEditorStageFrac
        val effFitScale = clockFitScale * (1f - clockEditorStageFrac) + stageFit * clockEditorStageFrac
        val clockLift = effClockRest + pullEff - clockLiftTravel * liftLead
        clockContainer.visibility = if (clockStageAlpha > 0.01f && passFadeT < 0.995f) View.VISIBLE else View.INVISIBLE
        clockContainer.cameraDistance = 12000f * dens
        clockContainer.pivotX = clockContainer.width * 0.5f
        clockContainer.pivotY = inkTopPx + (clockContainer.height * 0.5f - inkTopPx) * liftLead
        clockContainer.translationY = clockLift
        clockContainer.rotationX = liftLead * 10f
        val cScale = (1f - 0.11f * liftLead) * effFitScale * lockScaleMult * smartBreathScale
        if (!isDepthRevealAnimating) {
            clockContainer.scaleX = cScale
            clockContainer.scaleY = cScale
            clockContainer.alpha = clockStageAlpha
        }
        clockView.pullOffsetYPx = clockLift

        val toplineRest = if (effClockRest < 0f) max(effClockRest * 0.14f, -(3f * dens)) else effClockRest
        toplineRow.visibility = if (toplineStageAlpha > 0.01f && passFadeT < 0.995f) View.VISIBLE else View.INVISIBLE
        toplineRow.translationY = toplineRest + pullEff - (clockLiftTravel + 18f * dens) * liftLead
        val tScale = (1f - 0.08f * liftLead) * lockScaleMult
        toplineRow.scaleX = tScale
        toplineRow.scaleY = tScale
        toplineRow.alpha = toplineStageAlpha

        npCard.cameraDistance = 12000f * dens
        npCard.translationY = npBaseTranslationY + pullEff * 0.6f + notifCouplingY - npLiftTravel * liftLead
        npCard.rotationX = liftLead * 9f
        val mScale = (1f - 0.11f * liftLead) * lockScaleMult
        npCard.scaleX = mScale
        npCard.scaleY = mScale
        npCard.alpha = ((1f - fastFade) * lockOverlayAlpha).coerceIn(0f, 1f)
        if (npCard.visibility != View.GONE) {
            npCard.visibility = if (hideLockChrome == View.INVISIBLE || !cfg.music || lockOverlayAlpha <= 0.01f) View.INVISIBLE else View.VISIBLE
        }
        npCard.isClickable = cfg.music && exp < 0.12f && !isUnlocked && passcodeProgress < 0.1f && anyEditStage < 0.1f
        npCard.isFocusable = npCard.isClickable

        if (::notificationCenter.isInitialized) {
            notificationCenter.translationY = pullEff * 0.52f
            notificationCenter.scaleX = lockScaleMult
            notificationCenter.scaleY = lockScaleMult
            notificationCenter.alpha = lockOverlayAlpha.coerceIn(0f, 1f)
            if (notificationCenter.visibility != View.GONE) {
                notificationCenter.visibility = if (hideLockChrome == View.INVISIBLE || lockOverlayAlpha <= 0.01f) View.INVISIBLE else View.VISIBLE
            }
        }
        bottomRow.visibility = if (hideLockChrome == View.INVISIBLE || lockOverlayAlpha <= 0.01f) View.INVISIBLE else View.VISIBLE
        bottomRow.translationY = pullEff * 0.4f
        bottomRow.scaleX = lockScaleMult
        bottomRow.scaleY = lockScaleMult
        bottomRow.alpha = lockOverlayAlpha.coerceIn(0f, 1f)

        if (::unlockedBar.isInitialized) {
            val uAlpha = (unlockedFrac * (1f - passcodeProgress) * (1f - anyEditStage)).coerceIn(0f, 1f)
            unlockedBar.visibility = if (uAlpha > 0.01f) View.VISIBLE else View.GONE
            unlockedBar.alpha = uAlpha
            unlockedBar.translationY = (1f - unlockedFrac) * 18f * dens
        }

        backdrop.updateZoomOnly(phoneShell.width, phoneShell.height, wallZoom)
        if (clockContainer.alpha > 0.02f) clockView.invalidate()
        if (npCard.alpha > 0.02f) npCard.invalidate()
        if (::notificationCenter.isInitialized && notificationCenter.alpha > 0.02f) notificationCenter.invalidateCards()
        if (bottomRow.alpha > 0.02f) {
            dndPill.invalidate()
            shLeft.invalidate()
            shRight.invalidate()
        }
    }

    private fun releasePullSpring() {
        val start = pullY
        if (start <= 0.5f) {
            applyPullOffset(0f)
            return
        }
        pullAnimator?.cancel()
        pullAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620L
            addUpdateListener { a ->
                val k = a.animatedValue as Float
                val env = exp(-7f * k) * cos(10f * k)
                val v = if (k >= 0.99f) 0f else start * env
                applyPullOffset(v)
            }
            start()
        }
    }

    private fun applyWallpaperAndTheme(reRenderClock: Boolean) {
        val bmp = WallpaperEngine.getWallpaper(cfg.wall, customWallBmp)
        currentWallBmp = bmp
        wallView.setImageBitmap(bmp)

        val subj = if (cfg.depth) customSubjectBmp else null
        if (subj != null) {
            subjectView.setImageBitmap(subj)
            subjectView.visibility = View.VISIBLE
        } else {
            subjectView.visibility = View.GONE
        }

        val wallKey = if (cfg.wall == "custom") "custom:${bmp.generationId}" else cfg.wall
        val cols = adaptive.sample(wallKey, bmp, cfg)
        currentColors = cols

        val shellW = max(1, phoneShell.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels)
        val shellH = max(1, phoneShell.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels)
        backdrop.updateWallAndSubject(
            bmp, subj, cfg.depth,
            shellW, shellH,
            wallView.scaleX
        )

        val token = ++blurRenderToken
        val depthOn = cfg.depth
        bgExecutor.execute {
            val blurred = LiquidGlassShaders.createSoftBlurredBackdrop(bmp, subj, depthOn, shellW, shellH)
            mainHandler.post {
                if (token == blurRenderToken && !isDestroyed) {
                    val curW = max(1, phoneShell.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels)
                    val curH = max(1, phoneShell.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels)
                    backdrop.updateBlurredBackdrop(blurred, curW, curH, wallView.scaleX)
                    if (::passcodeOverlay.isInitialized && passcodeOverlay.isOpen) {
                        passcodeOverlay.invalidate()
                        passcodeOverlay.invalidateAllKeys()
                    }
                    if (::musicLibraryOverlay.isInitialized && musicLibraryOverlay.isOpen) {
                        musicLibraryOverlay.refreshGlass()
                    }
                }
            }
        }

        applyThemeColors(cols)

        applyToggles()
        refreshAllGlass()
        if (reRenderClock) scheduleClockRender(force = true)
    }

    private fun applyThemeColors(cols: AdaptiveColors) {
        currentColors = cols
        clockView.colors = cols
        clockView.config = cfg
        dateText.setTextColor(cols.uiInk)
        wxText.setTextColor(cols.uiInk)
        (homeBar.background as? GradientDrawable)?.setColor(cols.uiInk)
        statusBar.applyTheme(cols.uiInk, cfg.dnd)
        npCard.applyTheme(cols.uiInk, cols.uiDim, cols.glassBg)
        if (::notificationCenter.isInitialized) notificationCenter.applyTheme(cols.uiInk, cols.uiDim, cols.glassBg)
        if (::passcodeOverlay.isInitialized) passcodeOverlay.applyTheme(cols.uiInk)
        dndPill.applyTheme(cols.uiInk, cols.glassBg)
        shLeft.uiInk = cols.uiInk; shLeft.glassBg = cols.glassBg; shLeft.invalidate()
        shRight.uiInk = cols.uiInk; shRight.glassBg = cols.glassBg; shRight.invalidate()
    }

    private fun runSmartWallpaperHarmony(
        bmp: Bitmap,
        animated: Boolean = true,
        playEffect: Boolean = true,
        prevColorsOverride: AdaptiveColors? = null,
        prevConfigOverride: LockConfig? = null
    ) {
        val myToken = ++smartWallToken
        val wallKey = if (cfg.wall == "custom") "custom:${bmp.generationId}" else cfg.wall
        val startCols = prevColorsOverride ?: currentColors ?: adaptive.sample(wallKey, bmp, cfg)
        val startCfg = prevConfigOverride ?: cfg.copy()

        bgExecutor.execute {
            val style = WallpaperThemeAI.analyze(bmp)
            mainHandler.post {
                if (myToken != smartWallToken || isDestroyed) return@post

                cfg.color = style.colorHex
                cfg.clockOpacity = style.clockOpacity
                cfg.innerColor = style.innerColorHex
                cfg.innerOpacity = style.innerOpacity
                cfg.innerThickness = style.innerThickness
                cfg.innerAngle = style.innerAngle
                cfg.innerFade = style.innerFade
                cfg.clockGlass = style.clockGlass
                cfg.clockRefraction = style.clockRefraction
                store.save(cfg)

                val targetCols = adaptive.sample(wallKey, bmp, cfg)
                if (::editorSheet.isInitialized) editorSheet.syncFromConfig()

                smartMorphAnim?.cancel()
                smartBreathScale = 1f

                if (playEffect) {
                    val topY = clockContainer.y
                    val botY = topY + max(clockContainer.height.toFloat(), 160f * resources.displayMetrics.density)
                    harmonyAuraView.playSweep(style.paletteDots, topY, botY)
                    harmonyPillView.showPalette(style.paletteDots, style.clockGlass, style.clockRefraction, style.profileLabel)
                }

                if (!animated) {
                    applyThemeColors(targetCols)
                    clockView.invalidate()
                    return@post
                }

                // Keep starting look on screen for frame 0 so the transition morphs seamlessly
                val fromInnerInt = AppConstants.parseHexColor(startCfg.innerColor, Color.WHITE)
                val toInnerInt = style.innerColorInt
                val interpCfg = cfg.copy()
                clockView.colors = startCols
                clockView.config = startCfg.copy()
                clockView.invalidate()

                smartMorphAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 440L
                    interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
                    var cancelled = false
                    addUpdateListener { a ->
                        if (myToken != smartWallToken || isDestroyed) return@addUpdateListener
                        val t = (a.animatedValue as Float).coerceIn(0f, 1f)
                        val curCols = WallpaperThemeAI.lerpAdaptiveColors(startCols, targetCols, t)
                        interpCfg.clockOpacity = (startCfg.clockOpacity + (cfg.clockOpacity - startCfg.clockOpacity) * t).roundToInt()
                        interpCfg.innerOpacity = (startCfg.innerOpacity + (cfg.innerOpacity - startCfg.innerOpacity) * t).roundToInt()
                        interpCfg.innerThickness = startCfg.innerThickness + (cfg.innerThickness - startCfg.innerThickness) * t
                        interpCfg.innerAngle = WallpaperThemeAI.lerpAngleDeg(startCfg.innerAngle, cfg.innerAngle, t)
                        interpCfg.innerFade = (startCfg.innerFade + (cfg.innerFade - startCfg.innerFade) * t).roundToInt()
                        interpCfg.clockGlass = (startCfg.clockGlass + (cfg.clockGlass - startCfg.clockGlass) * t).roundToInt()
                        interpCfg.clockRefraction = (startCfg.clockRefraction + (cfg.clockRefraction - startCfg.clockRefraction) * t).roundToInt()
                        interpCfg.innerColor = AppConstants.toHex(WallpaperThemeAI.lerpArgb(fromInnerInt, toInnerInt, t))

                        smartBreathScale = if (playEffect) 1f + 0.018f * kotlin.math.sin(t * kotlin.math.PI).toFloat() else 1f
                        clockView.colors = curCols
                        clockView.config = interpCfg
                        dateText.setTextColor(curCols.uiInk)
                        wxText.setTextColor(curCols.uiInk)
                        applyDepthAndLayerTransforms()
                        clockView.invalidate()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationCancel(animation: android.animation.Animator) {
                            cancelled = true
                        }
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (cancelled || myToken != smartWallToken || isDestroyed) return
                            smartBreathScale = 1f
                            applyThemeColors(targetCols)
                            applyDepthAndLayerTransforms()
                            clockView.invalidate()
                        }
                    })
                    start()
                }
            }
        }
    }

    private fun applyToggles() {
        wxText.visibility = if (cfg.weather) View.VISIBLE else View.GONE
        npCard.visibility = if (cfg.music) View.VISIBLE else View.GONE
        if (::notificationCenter.isInitialized) {
            if (!cfg.notifications) {
                notifExpandFrac = 0f
                notificationCenter.collapseScrollIfExpanded()
                notificationCenter.visibility = View.GONE
                npCard.isClickable = true
                npCard.isFocusable = true
                updateMusicAndNotificationsLayout(animated = true)
            } else {
                val shouldPreparePos = notifHub.getItems().isNotEmpty() && !hadNotifsInLayout
                if (shouldPreparePos) updateMusicAndNotificationsLayout(animated = true)
                notificationCenter.setNotifications(notifHub.getItems(), null, null)
                if (!shouldPreparePos) updateMusicAndNotificationsLayout(animated = true)
            }
        }
        dndPill.visibility = if (cfg.dnd) View.VISIBLE else View.GONE
        subjectView.visibility = if (cfg.depth && customSubjectBmp != null) View.VISIBLE else View.GONE
        statusBar.applyTheme(currentColors?.uiInk ?: Color.WHITE, cfg.dnd)
    }

    private fun computeEditorStageTarget(): Pair<Float, Float> {
        val dens = resources.displayMetrics.density
        val artClock = clockView.activeArtifact
        val inkTopPx = ((artClock?.inkTopDp ?: 18f) * dens) * max(0.35f, clockView.scaleY)
        val shellHPx = (phoneShell.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()
        val sheetHPx = if (::editorSheet.isInitialized) editorSheet.currentSheetHeightPx().toFloat() else shellHPx * 0.44f
        val stageTop = safeTopPx + 74f * dens
        val stageBot = max(stageTop + 110f * dens, shellHPx - sheetHPx - safeBottomPx - 18f * dens)
        val stageH = max(90f * dens, stageBot - stageTop)

        val rawViewH = (artClock?.hDp ?: 210f) * dens
        val inkBotInCont = ((artClock?.inkBottomDp ?: max(48f, rawViewH / dens - 18f)) * dens) * max(0.35f, clockView.scaleY)
        val inkH = max(40f * dens, inkBotInCont - inkTopPx)
        val rawViewW = (artClock?.wDp ?: 260f) * dens
        val inkW = max(60f * dens, (((artClock?.inkRightDp ?: (rawViewW / dens - 18f)) - (artClock?.inkLeftDp ?: 18f)) * dens) * max(0.35f, clockView.scaleX))
        val maxStageW = max(140f * dens, (phoneShell.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - 32f * dens)

        val fit = min(1f, min((stageH * 0.92f) / inkH, maxStageW / inkW)).coerceIn(0.45f, 1f)
        val clockNaturalTop = safeTopPx + ((78f + cfg.clockY) * dens)
        val natInkTop = clockNaturalTop + inkTopPx
        val scaledInkH = inkH * fit
        val clampedInkTop = natInkTop.coerceIn(stageTop, max(stageTop, stageBot - scaledInkH))
        return (clampedInkTop - natInkTop) to fit
    }

    private fun glideEditorStageToLatest() {
        val (targetY, targetFit) = computeEditorStageTarget()
        val startY = stageCurOffsetY
        val startFit = stageCurFit
        if (abs(targetY - startY) < 1f && abs(targetFit - startFit) < 0.004f) {
            stageGlideAnim?.cancel()
            stageCurOffsetY = targetY
            stageCurFit = targetFit
            applyDepthAndLayerTransforms()
            return
        }
        stageGlideAnim?.cancel()
        stageGlideAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { a ->
                val t = (a.animatedValue as Float).coerceIn(0f, 1f)
                stageCurOffsetY = startY + (targetY - startY) * t
                stageCurFit = startFit + (targetFit - startFit) * t
                applyDepthAndLayerTransforms()
                clockView.invalidate()
                if (clockGhostView.visibility == View.VISIBLE) clockGhostView.invalidate()
            }
            start()
        }
    }

    private fun refreshAllGlass() {
        clockView.invalidate()
        if (::clockGhostView.isInitialized && clockGhostView.visibility == View.VISIBLE) {
            clockGhostView.invalidate()
        }
        npCard.invalidate()
        if (::notificationCenter.isInitialized) notificationCenter.invalidateCards()
        dndPill.invalidate()
        shLeft.invalidate()
        shRight.invalidate()
    }

    private fun estimatedShellWidthDp(): Float {
        val dens = resources.displayMetrics.density
        val measured = phoneShell.width
        if (measured > 0) return measured / dens
        val screenWDp = resources.displayMetrics.widthPixels / dens
        val screenHDp = resources.displayMetrics.heightPixels / dens
        return if (screenWDp >= 560f && screenHDp >= 600f &&
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        ) 430f else screenWDp
    }

    private fun computeClockLimitsDp(): Pair<Float, Float> {
        val dens = resources.displayMetrics.density
        val hostW = estimatedShellWidthDp().coerceAtLeast(320f)
        val hostH = (phoneShell.height.takeIf { it > 0 }?.div(dens) ?: (resources.displayMetrics.heightPixels / dens)).coerceAtLeast(480f)
        val shellHPx = (phoneShell.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()
        val minInkTopPx = safeTopPx + 74f * dens
        val clockGapPx = 10f * dens
        val cardGapPx = 8f * dens
        val items = if (::notificationCenter.isInitialized) notifHub.getItems() else emptyList()
        val showNotifs = ::notificationCenter.isInitialized && cfg.notifications && items.isNotEmpty()
        val headerHPx = 40f * dens
        val minListHPx = (when {
            items.size <= 1 -> 74f
            items.size == 2 -> 86f
            else -> 92f
        }) * dens
        val notifNeedPx = if (showNotifs) (headerHPx + minListHPx) else 0f
        val shortcutsTopPx = shellHPx - (safeBottomPx + 84f * dens)
        val npActualHPx = (npCard.height.takeIf { it > 0 } ?: (110f * dens).roundToInt()).toFloat()
        val npBaseBotPx = shellHPx - (safeBottomPx + 94f * dens)

        val obstaclePx = when {
            cfg.music && showNotifs -> shortcutsTopPx - notifNeedPx - cardGapPx - npActualHPx - clockGapPx
            cfg.music -> npBaseBotPx - npActualHPx - clockGapPx
            showNotifs -> shortcutsTopPx - notifNeedPx - clockGapPx
            else -> shellHPx - (safeBottomPx + 96f * dens)
        }
        val maxW = max(140f, hostW - 16f - 36f)
        val maxTextH = max(92f, min((obstaclePx - minInkTopPx) / dens, hostH * 0.74f))
        return maxW to maxTextH
    }

    private fun scheduleClockRender(force: Boolean) {
        val hostW = estimatedShellWidthDp()
        val (_, maxTextH) = computeClockLimitsDp()
        val art = clockView.activeArtifact
        val padEstimate = art?.padDp ?: 52f
        val maxStretchH = max(120f, maxTextH + padEstimate)
        clockView.scheduleRender(cfg, hostW, maxStretchH, force)
    }

    private fun updateDateLabel() {
        val cal = Calendar.getInstance()
        val sdf = SimpleDateFormat("EEE، d MMM", Locale("ar"))
        dateText.text = sdf.format(cal.time)
    }

    private fun scheduleMinuteTicker() {
        minuteTickRunnable?.let { mainHandler.removeCallbacks(it) }
        val cal = Calendar.getInstance()
        val delayMs = (60 - cal.get(Calendar.SECOND)) * 1000L - cal.get(Calendar.MILLISECOND) + 30L
        minuteTickRunnable = Runnable {
            updateDateLabel()
            scheduleClockRender(force = false)
            scheduleMinuteTicker()
        }
        mainHandler.postDelayed(minuteTickRunnable!!, max(500L, delayMs))
    }

    private fun fetchWeatherAsync() {
        bgExecutor.execute {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=33.31&longitude=44.36&current=temperature_2m,weather_code&timezone=auto")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val cur = JSONObject(txt).optJSONObject("current") ?: return@execute
                val temp = cur.optDouble("temperature_2m", -3.0).roundToInt()
                val code = cur.optInt("weather_code", 3)
                val ico = when {
                    code == 0 -> "☀"
                    code in 1..3 -> "⛅"
                    code in 51..67 || code in 80..82 -> "🌧"
                    code in 71..77 -> "❄"
                    code >= 95 -> "⛈"
                    else -> "☁"
                }
                val signTemp = if (temp < 0) "−${abs(temp)}°" else "$temp°"
                weatherTempStr = "$ico $signTemp"
                mainHandler.post { wxText.text = "  •  $weatherTempStr" }
            } catch (_: Throwable) {}
        }
    }

    private fun setFlashlight(on: Boolean) {
        flashBurstTimer?.let { mainHandler.removeCallbacks(it) }
        flashOn = on
        shLeft.isActive = on
        flashOverlay.visibility = View.GONE
        island.syncFlashlight(on)
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            for (id in cm.cameraIdList) {
                val hasFlash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    cm.setTorchMode(id, on)
                    break
                }
            }
        } catch (_: Throwable) {}
    }

    private fun openCamera(autoShoot: Boolean) {
        if (::resizeOverlay.isInitialized && resizeOverlay.isOpen) resizeOverlay.close()
        if (flashOn) setFlashlight(false)
        cameraOverlay.open(autoShoot)
    }

    private fun haptic(kind: String) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vib != null && vib.hasVibrator()) {
                val (ms, amp) = when (kind) {
                    "heavy" -> 22L to 210
                    "shutter" -> 18L to 180
                    "pop" -> 10L to 130
                    else -> 8L to 90
                }
                vib.vibrate(VibrationEffect.createOneShot(ms, amp))
                return
            }
        } catch (_: Throwable) {}
        rootFrame.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun showToast(msg: String) {
        toastTimer?.let { mainHandler.removeCallbacks(it) }
        toastView.text = msg
        toastView.visibility = View.VISIBLE
        toastView.animate().cancel()
        toastView.animate().alpha(1f).setDuration(180L).start()
        toastTimer = Runnable {
            toastView.animate().alpha(0f).setDuration(240L).withEndAction {
                toastView.visibility = View.GONE
            }.start()
        }.also { mainHandler.postDelayed(it, 1900L) }
    }

    private fun setUnlocked(unlocked: Boolean) {
        if (isUnlocked == unlocked && unlockAnim?.isRunning != true) return
        isUnlocked = unlocked
        if (unlocked && ::resizeOverlay.isInitialized && resizeOverlay.isOpen) {
            resizeOverlay.close()
        }
        if (unlocked && ::notificationCenter.isInitialized && notificationCenter.expandFrac > 0.05f) {
            notificationCenter.collapseScrollIfExpanded()
        }
        if (!unlocked) {
            if (::passcodeOverlay.isInitialized) passcodeOverlay.resetToClosedState(notify = false)
            passcodeProgress = 0f
            haptic("tick")
            island.pulse("تم قفل الشاشة", 0xFF38BDF8.toInt(), 1400L)
        }
        unlockAnim?.cancel()
        val start = unlockedFrac
        val target = if (unlocked) 1f else 0f
        unlockAnim = ValueAnimator.ofFloat(start, target).apply {
            duration = 320L
            interpolator = DecelerateInterpolator(1.9f)
            addUpdateListener { a ->
                unlockedFrac = (a.animatedValue as Float).coerceIn(0f, 1f)
                applyDepthAndLayerTransforms()
            }
            start()
        }
    }

    private fun isPointInView(v: View, rawX: Float, rawY: Float, padPx: Float = 0f): Boolean {
        if (v.visibility != View.VISIBLE || v.alpha <= 0.05f || v.width <= 0 || v.height <= 0) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return rawX >= loc[0] - padPx && rawX <= loc[0] + v.width + padPx &&
            rawY >= loc[1] - padPx && rawY <= loc[1] + v.height + padPx
    }

    private fun isEligibleForUnlockSwipe(rawX: Float, rawY: Float): Boolean {
        val dens = resources.displayMetrics.density
        if (isWallOrClockPulling && pullAnimator?.isRunning != true && pullY <= 0.5f && !wallView.isPressed && !clockView.isPressed) {
            isWallOrClockPulling = false
        }
        if (isWallOrClockPulling || pullY > 6f * dens) return false
        val hasVisibleNotifs = ::notificationCenter.isInitialized &&
            notificationCenter.visibility == View.VISIBLE &&
            notificationCenter.displayedCount() > 0
        if (hasVisibleNotifs && notifExpandFrac > 0.04f) return false
        if (::notificationCenter.isInitialized && notificationCenter.isActivelyInteracting()) return false
        if (isPointInView(island, rawX, rawY, 6f * dens)) return false
        if (isPointInView(shLeft, rawX, rawY, 4f * dens)) return false
        if (isPointInView(shRight, rawX, rawY, 4f * dens)) return false
        if (cfg.music && npCard.isTouchInScrubBar(rawX, rawY)) return false
        if (cfg.notifications && ::notificationCenter.isInitialized &&
            notificationCenter.isPointInsideInteractiveDeck(rawX, rawY)
        ) {
            return false
        }
        return true
    }

    private inner class UnlockSwipeShell(context: Context) : FrameLayout(context) {
        private val dens = resources.displayMetrics.density
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var downX = 0f
        private var downY = 0f
        private var startSwipeY = 0f
        private var eligible = false
        private var swipingUp = false
        private var vt: VelocityTracker? = null

        private fun trackRaw(ev: MotionEvent) {
            val copy = MotionEvent.obtain(ev)
            copy.offsetLocation(ev.rawX - ev.x, ev.rawY - ev.y)
            vt?.addMovement(copy)
            copy.recycle()
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (::passcodeOverlay.isInitialized && (passcodeOverlay.isOpen || passcodeProgress > 0.01f)) {
                if (isWallOrClockPulling || pullY > 0.5f || pullAnimator?.isRunning == true) {
                    cancelActivePullForPasscode()
                    applyDepthAndLayerTransforms()
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!cfg.passcodeEnabled || isUnlocked ||
                editorSheet.isOpen || cameraOverlay.isOpen ||
                musicLibraryOverlay.isOpen ||
                passcodeOverlay.isOpen
            ) {
                if (swipingUp || eligible) {
                    vt?.recycle()
                    vt = null
                    swipingUp = false
                    eligible = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                return false
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = ev.getPointerId(0)
                    downX = ev.rawX
                    downY = ev.rawY
                    startSwipeY = ev.rawY
                    swipingUp = false
                    eligible = !(resizeOverlay.isOpen && isPointInView(resizeOverlay, ev.rawX, ev.rawY, 16f * dens)) &&
                        isEligibleForUnlockSwipe(ev.rawX, ev.rawY)
                    vt?.recycle()
                    vt = if (eligible) VelocityTracker.obtain().also { trackRaw(ev) } else null
                    return false
                }
                MotionEvent.ACTION_POINTER_DOWN -> return false
                MotionEvent.ACTION_MOVE -> {
                    if (!eligible) return false
                    if (isWallOrClockPulling || pullY > 6f * dens) {
                        vt?.recycle()
                        vt = null
                        eligible = false
                        swipingUp = false
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        return false
                    }
                    val idx = ev.findPointerIndex(activePointerId)
                    if (idx < 0) return false
                    trackRaw(ev)
                    val curRawX = ptrRawX(ev, idx)
                    val curRawY = ptrRawY(ev, idx)
                    val dx = curRawX - downX
                    val dy = curRawY - downY
                    if (-dy > slop && -dy > abs(dx) * 1.12f) {
                        swipingUp = true
                        startSwipeY = downY + slop * 0.35f
                        cancelActivePullForPasscode()
                        if (island.isExpanded) island.collapseIfExpanded()
                        if (resizeOverlay.isOpen) resizeOverlay.close()
                        val travel = max(220f * dens, height * 0.42f)
                        val p = ((startSwipeY - curRawY) / travel).coerceIn(0.01f, 1f)
                        passcodeOverlay.setInteractiveProgress(p)
                        return true
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.getPointerId(ev.actionIndex) == activePointerId) {
                        vt?.recycle()
                        vt = null
                        eligible = false
                        swipingUp = false
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                    }
                    return false
                }
                MotionEvent.ACTION_UP -> {
                    if (eligible) {
                        val idx = ev.findPointerIndex(activePointerId).let { if (it >= 0) it else 0 }
                        val curRawX = ptrRawX(ev, idx)
                        val curRawY = ptrRawY(ev, idx)
                        trackRaw(ev)
                        vt?.computeCurrentVelocity(1000)
                        val vy = vt?.yVelocity ?: 0f
                        val netDy = curRawY - downY
                        val netDx = curRawX - downX
                        vt?.recycle()
                        vt = null
                        eligible = false
                        swipingUp = false
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        if (!isWallOrClockPulling && pullY <= 6f * dens &&
                            -vy > 280f * dens && -netDy > 12f * dens && abs(netDy) > abs(netDx) * 1.15f
                        ) {
                            cancelActivePullForPasscode()
                            passcodeOverlay.openAnimated(vy)
                            return true
                        }
                    } else {
                        vt?.recycle()
                        vt = null
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    vt?.recycle()
                    vt = null
                    eligible = false
                    swipingUp = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!cfg.passcodeEnabled || isUnlocked) return super.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = ev.getPointerId(0)
                    downX = ev.rawX
                    downY = ev.rawY
                    startSwipeY = ev.rawY
                    swipingUp = false
                    eligible = isEligibleForUnlockSwipe(ev.rawX, ev.rawY)
                    vt?.recycle()
                    vt = if (eligible) VelocityTracker.obtain().also { trackRaw(ev) } else null
                    return eligible
                }
                MotionEvent.ACTION_POINTER_DOWN -> return eligible
                MotionEvent.ACTION_MOVE -> {
                    if (!eligible) return false
                    val idx = ev.findPointerIndex(activePointerId)
                    if (idx < 0) return true
                    trackRaw(ev)
                    val curRawX = ptrRawX(ev, idx)
                    val curRawY = ptrRawY(ev, idx)
                    val dx = curRawX - downX
                    val dy = curRawY - downY
                    if (!swipingUp && -dy > slop && -dy > abs(dx) * 1.12f) {
                        if (isWallOrClockPulling || pullY > 6f * dens) {
                            eligible = false
                            vt?.recycle()
                            vt = null
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            return false
                        }
                        swipingUp = true
                        startSwipeY = downY + slop * 0.35f
                        cancelActivePullForPasscode()
                        if (island.isExpanded) island.collapseIfExpanded()
                        if (resizeOverlay.isOpen) resizeOverlay.close()
                    }
                    if (swipingUp) {
                        val travel = max(220f * dens, height * 0.42f)
                        val p = ((startSwipeY - curRawY) / travel).coerceIn(0f, 1f)
                        passcodeOverlay.setInteractiveProgress(p)
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.getPointerId(ev.actionIndex) == activePointerId) {
                        val upIdx = ev.actionIndex
                        val curRawX = ptrRawX(ev, upIdx)
                        val curRawY = ptrRawY(ev, upIdx)
                        vt?.computeCurrentVelocity(1000)
                        val vy = vt?.yVelocity ?: 0f
                        vt?.recycle()
                        vt = null
                        val wasSwipe = swipingUp
                        val netDy = curRawY - downY
                        val netDx = curRawX - downX
                        swipingUp = false
                        eligible = false
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        if (wasSwipe) {
                            passcodeOverlay.settleInteractive(vy, draggedUpPx = max(0f, -netDy))
                        } else if (!isWallOrClockPulling && pullY <= 6f * dens &&
                            -vy > 280f * dens && -netDy > 12f * dens && abs(netDy) > abs(netDx) * 1.15f
                        ) {
                            cancelActivePullForPasscode()
                            passcodeOverlay.openAnimated(vy)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val idx = ev.findPointerIndex(activePointerId).let { if (it >= 0) it else 0 }
                    val curRawX = ptrRawX(ev, idx)
                    val curRawY = ptrRawY(ev, idx)
                    trackRaw(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    val wasSwipe = swipingUp
                    val netDy = curRawY - downY
                    val netDx = curRawX - downX
                    swipingUp = false
                    eligible = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    if (wasSwipe) {
                        if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                            passcodeOverlay.closeAnimated()
                        } else {
                            passcodeOverlay.settleInteractive(vy, draggedUpPx = max(0f, -netDy))
                        }
                        return true
                    }
                    if (ev.actionMasked == MotionEvent.ACTION_UP &&
                        !isWallOrClockPulling && pullY <= 6f * dens &&
                        -vy > 280f * dens && -netDy > 12f * dens && abs(netDy) > abs(netDx) * 1.15f
                    ) {
                        cancelActivePullForPasscode()
                        passcodeOverlay.openAnimated(vy)
                        return true
                    }
                }
            }
            return super.onTouchEvent(ev)
        }
    }

    private fun handleBackNavigation() {
        when {
            cameraOverlay.isOpen -> cameraOverlay.close()
            musicLibraryOverlay.isOpen -> musicLibraryOverlay.close()
            resizeOverlay.isOpen -> resizeOverlay.close()
            editorSheet.isOpen -> editorSheet.close()
            ::passcodeOverlay.isInitialized && passcodeOverlay.isOpen -> passcodeOverlay.closeAnimated()
            island.isExpanded -> island.collapseIfExpanded()
            ::notificationCenter.isInitialized && notificationCenter.expandFrac > 0.08f -> {
                notificationCenter.collapseScrollIfExpanded()
            }
            isUnlocked -> setUnlocked(false)
            else -> finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    private fun registerReceivers() {
        ContextCompat.registerReceiver(this, timeReceiver, IntentFilter(Intent.ACTION_TIME_TICK), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val updateNet = {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                when {
                    caps == null -> statusBar.updateNetwork("غير متصل", wifi = false, offline = true)
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> statusBar.updateNetwork("Wi-Fi", wifi = true, offline = false)
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> statusBar.updateNetwork("5G", wifi = false, offline = false)
                    else -> statusBar.updateNetwork("متصل", wifi = false, offline = false)
                }
            }
            updateNet()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { mainHandler.post { updateNet() } }
                override fun onLost(network: Network) { mainHandler.post { updateNet() } }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    mainHandler.post { updateNet() }
                }
            }
            networkCallback = cb
            cm.registerDefaultNetworkCallback(cb)
        } catch (_: Throwable) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM_PERM) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            cameraOverlay.onPermissionResult(ok)
        } else if (requestCode == REQ_MEDIA_PERM) {
            musicLibraryOverlay.onPermissionResult(media.hasAudioPermission())
        }
    }

    private fun launchPhotoPicker(reqCode: Int) {
        val intent = when {
            reqCode == REQ_AUDIO -> Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            Build.VERSION.SDK_INT >= 33 && reqCode != REQ_SUBJECT -> {
                Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
            }
            else -> Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, reqCode)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = if (reqCode == REQ_AUDIO) "audio/*" else "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(fallback, reqCode)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_AUDIO -> {
                media.loadLocalAudio(uri)
                island.pulse("تم اختيار مقطع صوتي", 0xFF30D158.toInt(), 1600L)
            }
            REQ_WALL, REQ_SUBJECT, REQ_ART -> {
                bgExecutor.execute {
                    val bmp = decodeUriBitmap(uri, maxSide = if (requestCode == REQ_ART) 512 else 1920) ?: return@execute
                    mainHandler.post {
                        when (requestCode) {
                            REQ_WALL -> {
                                val prevColors = currentColors
                                val prevCfg = cfg.copy()
                                customWallBmp = bmp
                                cfg.wall = "custom"
                                cfg.hasCustomWall = store.saveCustomWall(bmp)
                                cfg.hasCustomSubject = false
                                customSubjectBmp = null
                                store.clearCustomSubject()
                                store.save(cfg)
                                applyWallpaperAndTheme(reRenderClock = true)
                                if (cfg.smartWallTheme) {
                                    runSmartWallpaperHarmony(
                                        bmp = bmp,
                                        animated = true,
                                        playEffect = true,
                                        prevColorsOverride = prevColors,
                                        prevConfigOverride = prevCfg
                                    )
                                }
                                if (cfg.depth) onExtractAiDepth()
                            }
                            REQ_SUBJECT -> {
                                customSubjectBmp = bmp
                                cfg.depth = true
                                cfg.hasCustomSubject = store.saveCustomSubject(bmp)
                                store.save(cfg)
                                applyWallpaperAndTheme(reRenderClock = false)
                                editorSheet.syncFromConfig()
                                editorSheet.setAiStatus("تم تطبيق صورة العمق يدوياً ✓", false)
                                if (editorSheet.isOpen) editorSheet.close()
                                mainHandler.postDelayed({
                                    if (!isDestroyed && cfg.depth && customSubjectBmp === bmp) {
                                        playDepthSeparationReveal(bmp)
                                    }
                                }, 180L)
                            }
                            REQ_ART -> {
                                media.setCustomArt(bmp)
                                showToast("تم تحديث الغلاف ✓")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun decodeUriBitmap(uri: Uri, maxSide: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.isMutableRequired = false
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val scale = min(1f, maxSide / max(w, h).toFloat())
                    if (scale < 1f) {
                        decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                    }
                }
            } else {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val raw = BitmapFactory.decodeStream(stream) ?: return null
                    val scale = min(1f, maxSide / max(raw.width, raw.height).toFloat())
                    if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).roundToInt(), (raw.height * scale).roundToInt(), true) else raw
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ─────────────── EditorCallbacks ───────────────
    override fun onClose() {
        media.setSheetOpen(false)
        animateEditorStage(clockTarget = 0f, wallTarget = 0f)
    }

    override fun onModeChanged(mode: EditorSheetView.Mode) {
        if (mode == EditorSheetView.Mode.CLOCK) {
            animateEditorStage(clockTarget = 1f, wallTarget = 0f)
        } else {
            animateEditorStage(clockTarget = 0f, wallTarget = 1f)
        }
    }

    override fun onFontChanged(fontId: String) {
        haptic("tick")
        store.save(cfg)
        clockView.beginFontTransition()
        scheduleClockRender(force = true)
    }

    override fun onGeometryPreview(sizeChanged: Boolean, stretchChanged: Boolean, yChanged: Boolean) {
        if (yChanged) {
            layoutScreenPositions(animatedLayout = true)
            if (clockEditorStageFrac > 0.01f) applyDepthAndLayerTransforms()
        } else {
            val (maxW, maxTextH) = computeClockLimitsDp()
            val art = clockView.activeArtifact
            val baseSize = max(40f, art?.sizeDp ?: clockView.committedSize())
            val baseSx = max(0.4f, clockView.committedSx())
            val baseSy = max(0.5f, clockView.committedSy())
            val curTextW = max(40f, art?.textWDp ?: (clockView.width / resources.displayMetrics.density))
            val curTextH = max(40f, art?.textHDp ?: (clockView.height / resources.displayMetrics.density))
            val rawScaleX = (cfg.size / baseSize) * (cfg.sx / baseSx)
            val rawScaleY = (cfg.size / baseSize) * (cfg.sy / baseSy)
            val previewFit = min(
                1f,
                min(maxW / max(1f, curTextW * rawScaleX), maxTextH / max(1f, curTextH * rawScaleY))
            )
            clockView.updatePreviewTransform(rawScaleX * previewFit, rawScaleY * previewFit)
            if (sizeChanged || stretchChanged) {
                updateMusicAndNotificationsLayout(animated = true)
            }
            if (clockEditorStageFrac > 0.01f) applyDepthAndLayerTransforms()
        }
        geometryDebounce?.let { mainHandler.removeCallbacks(it) }
        geometryDebounce = Runnable {
            store.save(cfg)
            scheduleClockRender(force = true)
        }.also { mainHandler.postDelayed(it, 110L) }
    }

    override fun onGeometryCommit() {
        store.save(cfg)
        scheduleClockRender(force = true)
    }

    override fun onFormatToggled() {
        haptic("tick")
        store.save(cfg)
        scheduleClockRender(force = true)
    }

    override fun onColorOrOpacityChanged() {
        smartMorphAnim?.cancel()
        smartBreathScale = 1f
        val bmp = currentWallBmp ?: return
        val wallKey = if (cfg.wall == "custom") "custom:${bmp.generationId}" else cfg.wall
        currentColors = adaptive.sample(wallKey, bmp, cfg)
        clockView.colors = currentColors
        clockView.config = cfg
        clockView.invalidate()
        store.save(cfg)
    }

    override fun onWallSelected(wallId: String) {
        haptic("tick")
        cancelDepthActivationFlow()
        val prevColors = currentColors
        val prevCfg = cfg.copy()
        cfg.wall = wallId
        customSubjectBmp = null
        cfg.hasCustomSubject = false
        cfg.depth = false
        store.clearCustomSubject()
        store.save(cfg)
        applyWallpaperAndTheme(reRenderClock = true)
        if (cfg.smartWallTheme) {
            currentWallBmp?.let { bmp ->
                runSmartWallpaperHarmony(
                    bmp = bmp,
                    animated = true,
                    playEffect = true,
                    prevColorsOverride = prevColors,
                    prevConfigOverride = prevCfg
                )
            }
        }
    }

    override fun onPickCustomWall() = launchPhotoPicker(REQ_WALL)

    private fun cancelDepthActivationFlow() {
        depthFlowGen++
        isDepthRevealAnimating = false
        if (::depthRevealView.isInitialized) depthRevealView.stopAll()
        if (::depthLoadingPill.isInitialized) depthLoadingPill.hide(immediate = true)
    }

    private fun playDepthSeparationReveal(cutout: Bitmap) {
        if (isDestroyed) return
        val dens = resources.displayMetrics.density
        val yDp = pullY / dens
        val baseWallZoom = 1f + yDp * 0.00028f + passcodeProgress * 0.015f
        val baseClockScale = clockFitScale
        val clockCenterY = if (clockContainer.height > 0) {
            clockContainer.y + clockContainer.height * 0.5f
        } else {
            phoneShell.height * 0.34f
        }

        isDepthRevealAnimating = true
        subjectView.setImageBitmap(cutout)
        subjectView.visibility = View.VISIBLE
        subjectView.alpha = 0f
        haptic("pop")

        depthRevealView.playSeparationReveal(
            subjectBmp = cutout,
            baseScale = baseWallZoom,
            clockCenterY = clockCenterY,
            onFrameProgress = { t, liftAlpha, liftScale, liftTyPx, wallDip, clockDip ->
                subjectView.alpha = liftAlpha
                subjectView.scaleX = liftScale
                subjectView.scaleY = liftScale
                subjectView.translationY = liftTyPx

                wallView.scaleX = wallDip
                wallView.scaleY = wallDip

                val sc = baseClockScale * clockDip
                clockContainer.scaleX = sc
                clockContainer.scaleY = sc
                clockContainer.alpha = (1f - 0.12f * kotlin.math.sin((t / 0.55f).coerceIn(0f, 1f) * kotlin.math.PI).toFloat()).coerceIn(0f, 1f)
            },
            onFinished = {
                isDepthRevealAnimating = false
                applyDepthAndLayerTransforms()
            }
        )
    }

    private fun runLockScreenDepthActivation(forceAiExtract: Boolean) {
        val wall = currentWallBmp ?: return
        if (aiDepth.isBusy) return
        val myGen = ++depthFlowGen
        val startMs = SystemClock.uptimeMillis()

        if (editorSheet.isOpen) editorSheet.close()
        if (resizeOverlay.isOpen) resizeOverlay.close()
        if (::notificationCenter.isInitialized && notificationCenter.expandFrac > 0.05f) {
            notificationCenter.collapseScrollIfExpanded()
        }

        depthRevealView.startScanning()
        depthLoadingPill.show("جارٍ عزل الشخصية عن الخلفية…")

        val existingSubject = if (!forceAiExtract) customSubjectBmp else null
        if (existingSubject != null) {
            mainHandler.postDelayed({
                if (myGen != depthFlowGen || isDestroyed) return@postDelayed
                depthRevealView.stopScanning()
                depthLoadingPill.hide()
                cfg.depth = true
                store.save(cfg)
                applyWallpaperAndTheme(reRenderClock = false)
                editorSheet.syncFromConfig()
                playDepthSeparationReveal(existingSubject)
                island.pulse("تم تفعيل تأثير العمق ✓", 0xFF38BDF8.toInt(), 1600L)
            }, 680L)
            return
        }

        aiDepth.extractSubject(
            wall,
            onProgress = { pct, msg ->
                mainHandler.post {
                    if (myGen != depthFlowGen || isDestroyed) return@post
                    editorSheet.setAiStatus("$msg ($pct%)", true)
                    depthLoadingPill.updateStatus("جارٍ عزل الشخصية… $pct%")
                }
            },
            onReady = { cutout ->
                val elapsed = SystemClock.uptimeMillis() - startMs
                val remain = (680L - elapsed).coerceAtLeast(0L)
                mainHandler.postDelayed({
                    if (myGen != depthFlowGen || isDestroyed) return@postDelayed
                    depthRevealView.stopScanning()
                    depthLoadingPill.hide()
                    customSubjectBmp = cutout
                    cfg.depth = true
                    cfg.hasCustomSubject = store.saveCustomSubject(cutout)
                    store.save(cfg)
                    applyWallpaperAndTheme(reRenderClock = false)
                    editorSheet.syncFromConfig()
                    editorSheet.setAiStatus("تم عزل العنصر بنجاح ✓", false)
                    playDepthSeparationReveal(cutout)
                    island.pulse("تم عزل العمق بنجاح ✓", 0xFF38BDF8.toInt(), 1800L)
                }, remain)
            },
            onError = { err ->
                mainHandler.post {
                    if (myGen != depthFlowGen || isDestroyed) return@post
                    depthRevealView.stopScanning()
                    depthLoadingPill.hide()
                    cfg.depth = false
                    store.save(cfg)
                    applyWallpaperAndTheme(reRenderClock = false)
                    editorSheet.syncFromConfig()
                    editorSheet.setAiStatus(err, false)
                    val tip = if (cfg.wall != "custom") {
                        "اختر صورة تحتوي على شخصية من «صور جهازي» لعزلها أمام الساعة"
                    } else err
                    showToast(tip)
                }
            }
        )
    }

    override fun onExtractAiDepth() {
        haptic("pop")
        runLockScreenDepthActivation(forceAiExtract = true)
    }

    override fun onPickManualSubject() = launchPhotoPicker(REQ_SUBJECT)

    override fun onInnerShadowChanged() {
        smartMorphAnim?.cancel()
        smartBreathScale = 1f
        clockView.config = cfg
        clockView.invalidate()
        store.save(cfg)
    }

    override fun onToggleSmartWallTheme(enabled: Boolean) {
        haptic("tick")
        cfg.smartWallTheme = enabled
        store.save(cfg)
        if (enabled) {
            currentWallBmp?.let { bmp ->
                runSmartWallpaperHarmony(bmp = bmp, animated = true, playEffect = true)
            }
        } else {
            smartWallToken++
            smartMorphAnim?.cancel()
            smartBreathScale = 1f
            if (::harmonyAuraView.isInitialized) harmonyAuraView.cancelSweep()
            if (::harmonyPillView.isInitialized) harmonyPillView.hide(immediate = false)
            applyDepthAndLayerTransforms()
        }
    }

    override fun onApplySmartWallThemeNow() {
        haptic("pop")
        currentWallBmp?.let { bmp ->
            runSmartWallpaperHarmony(bmp = bmp, animated = true, playEffect = true)
        }
    }

    override fun onToggleWeather() {
        cfg.weather = !cfg.weather
        store.save(cfg)
        applyToggles()
        haptic("tick")
    }

    override fun onToggleMusic() {
        cfg.music = !cfg.music
        store.save(cfg)
        applyToggles()
        scheduleClockRender(force = true)
        haptic("tick")
    }

    override fun onToggleNotifications() {
        cfg.notifications = !cfg.notifications
        store.save(cfg)
        applyToggles()
        scheduleClockRender(force = true)
        haptic("tick")
    }

    override fun onTestNotification() {
        haptic("pop")
        if (!cfg.notifications) {
            cfg.notifications = true
            store.save(cfg)
        }
        notifHub.addSampleNotification()
    }

    override fun onOpenNotificationAccess() {
        haptic("pop")
        notifHub.openNotificationSettings()
        showToast("فعّل صلاحية إشعارات OClock زجاج")
    }

    override fun onToggleDepth() {
        if (!cfg.depth) {
            if (aiDepth.isBusy) return
            haptic("pop")
            cfg.depth = true
            store.save(cfg)
            runLockScreenDepthActivation(forceAiExtract = false)
        } else {
            cancelDepthActivationFlow()
            cfg.depth = false
            store.save(cfg)
            if (subjectView.visibility == View.VISIBLE) {
                subjectView.animate().cancel()
                subjectView.animate().alpha(0f).setDuration(220L).withEndAction {
                    if (!cfg.depth) applyWallpaperAndTheme(reRenderClock = false)
                }.start()
            } else {
                applyWallpaperAndTheme(reRenderClock = false)
            }
            haptic("tick")
        }
    }

    override fun onToggleDnd() {
        cfg.dnd = !cfg.dnd
        store.save(cfg)
        applyToggles()
        island.syncDnd(cfg.dnd)
        haptic("tick")
    }

    override fun onTogglePasscode() {
        cfg.passcodeEnabled = !cfg.passcodeEnabled
        if (!cfg.passcodeEnabled && isUnlocked) {
            setUnlocked(false)
        }
        store.save(cfg)
        showToast(if (cfg.passcodeEnabled) "تم تفعيل رمز القفل" else "تم إيقاف رمز القفل")
        haptic("tick")
    }

    override fun onChangePasscodePin() {
        haptic("pop")
        editorSheet.close()
        mainHandler.postDelayed({
            if (!isDestroyed) {
                passcodeOverlay.openForSetup(cfg.passcodePin.length)
            }
        }, 160L)
    }

    override fun onPickAudio() = launchPhotoPicker(REQ_AUDIO)
    override fun onPickAlbumArt() = launchPhotoPicker(REQ_ART)

    override fun onDownloadPerf() {
        haptic("pop")
        bgExecutor.execute {
            val savedPath = perf.saveReportToDownloads(cfg)
            mainHandler.post {
                if (savedPath != null) {
                    editorSheet.setPerfStatus("تم الحفظ: $savedPath")
                    showToast("تم حفظ تقرير الأداء ✓")
                } else {
                    editorSheet.setPerfStatus("تعذر الحفظ")
                }
            }
        }
    }

    override fun onSharePerf() {
        haptic("pop")
        bgExecutor.execute {
            val intent = perf.shareReport(cfg)
            mainHandler.post {
                if (intent != null) {
                    startActivity(Intent.createChooser(intent, "مشاركة تقرير الأداء"))
                }
            }
        }
    }

    override fun onResetPerf() {
        haptic("tick")
        perf.reset()
        editorSheet.updatePerfUi(perf.snapshot())
        editorSheet.setPerfStatus("تم تصفير العداد")
    }

    override fun onHaptic(kind: String) = haptic(kind)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        phoneShell.post {
            layoutScreenPositions()
            applyWallpaperAndTheme(reRenderClock = true)
        }
    }

    override fun onResume() {
        super.onResume()
        perf.setVisible(true)
        media.setVisible(true)
        if (media.hasAudioPermission() && !media.hasDeviceTracks()) {
            media.scanDeviceMusic()
        }
        if (musicLibraryOverlay.isOpen) {
            musicLibraryOverlay.onPermissionResult(media.hasAudioPermission())
        }
        notifHub.refreshFromSystem()
        if (::notificationCenter.isInitialized) {
            notificationCenter.refreshPermissionBadge()
            updateMusicAndNotificationsLayout(animated = hasInitialLayout)
        }
        updateDateLabel()
        scheduleClockRender(force = false)
        scheduleMinuteTicker()
    }

    override fun onPause() {
        super.onPause()
        perf.setVisible(false)
        media.setVisible(false)
        minuteTickRunnable?.let { mainHandler.removeCallbacks(it) }
        val snapCfg = cfg.copy()
        bgExecutor.execute { perf.saveReportToDownloads(snapCfg, "OClock-Perf-Latest.json") }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelDepthActivationFlow()
        smartWallToken++
        smartMorphAnim?.cancel()
        if (::harmonyAuraView.isInitialized) harmonyAuraView.cancelSweep()
        if (::harmonyPillView.isInitialized) harmonyPillView.hide(immediate = true)
        stageGlideAnim?.cancel()
        editorStageSpringRunning = false
        Choreographer.getInstance().removeFrameCallback(editorStageSpringCallback)
        clockHoldPendingRunnable?.let { mainHandler.removeCallbacks(it) }
        wallHoldPendingRunnable?.let { mainHandler.removeCallbacks(it) }
        springRunning = false
        Choreographer.getInstance().removeFrameCallback(layoutSpringCallback)
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            try { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback!!) } catch (_: Throwable) {}
        }
        try { unregisterReceiver(timeReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Throwable) {}
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        if (flashOn) setFlashlight(false)
        cameraOverlay.close()
        clockView.shutdown()
        media.shutdown()
        notifHub.shutdown()
        aiDepth.shutdown()
        perf.shutdown()
        bgExecutor.shutdownNow()
    }
}
