package com.oclock.liquidglass

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Choreographer
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt

class PerfMonitor(private val context: Context) : Choreographer.FrameCallback {
    private val startedWallMs = System.currentTimeMillis()
    private val startedMonoMs = SystemClock.elapsedRealtime()
    private val memExecutor = Executors.newSingleThreadExecutor()

    private var running = false
    private var visible = true
    private var lastFrameNs = 0L
    private var lastMemPollMs = 0L
    private var lastSampleMs = 0L

    private var totalFrames = 0L
    private var dropped24 = 0L
    private var jank50 = 0L
    private var maxFrameGapMs = 0.0
    private var totalFrameSumMs = 0.0
    private var currentFps = 60.0

    private val recentFrameMs = ArrayDeque<Double>(120)
    private val timeline = ArrayDeque<JSONObject>(180)
    private val consumers = LinkedHashMap<String, ConsumerStats>()

    @Volatile private var totalPssMb = 0.0
    @Volatile private var nativeHeapMb = 0.0
    @Volatile private var dalvikPssMb = 0.0
    @Volatile private var availRamMb = 0.0
    @Volatile private var gpuFieldCacheBytes = 0L

    private var listener: ((SummarySnapshot) -> Unit)? = null

    data class ConsumerStats(
        var calls: Long = 0,
        var totalMs: Double = 0.0,
        var maxMs: Double = 0.0,
        var lastMs: Double = 0.0
    )

    data class SummarySnapshot(
        val currentFps: Int,
        val avgFps: Int,
        val low5Fps: Int,
        val dropped24: Long,
        val jank50: Long,
        val totalPssMb: Double,
        val gpuCacheMb: Double,
        val engineLabel: String,
        val uptimeSec: Long
    )

    fun setListener(cb: ((SummarySnapshot) -> Unit)?) {
        listener = cb
    }

    fun setCacheBytes(bytes: Long) {
        gpuFieldCacheBytes = max(0L, bytes)
    }

    fun start() {
        if (running) return
        running = true
        visible = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
        pollMemoryAsync()
    }

    fun setVisible(v: Boolean) {
        visible = v
        lastFrameNs = 0L
        if (running && v) {
            Choreographer.getInstance().removeFrameCallback(this)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun shutdown() {
        stop()
        memExecutor.shutdownNow()
    }

    fun recordWork(name: String, ms: Double) {
        synchronized(consumers) {
            val s = consumers.getOrPut(name) { ConsumerStats() }
            s.calls += 1
            s.totalMs += ms
            s.lastMs = ms
            if (ms > s.maxMs) s.maxMs = ms
        }
    }

    fun reset() {
        synchronized(this) {
            totalFrames = 0
            dropped24 = 0
            jank50 = 0
            maxFrameGapMs = 0.0
            totalFrameSumMs = 0.0
            currentFps = 60.0
            recentFrameMs.clear()
            timeline.clear()
        }
        synchronized(consumers) {
            consumers.clear()
        }
        lastFrameNs = 0L
        notifyListener()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !visible) return
        if (lastFrameNs != 0L) {
            val dtMs = (frameTimeNanos - lastFrameNs) / 1_000_000.0
            if (dtMs in 2.0..1000.0) {
                synchronized(this) {
                    totalFrames += 1
                    totalFrameSumMs += dtMs
                    if (dtMs > maxFrameGapMs) maxFrameGapMs = dtMs
                    if (dtMs > 24.0) dropped24 += 1
                    if (dtMs > 50.0) jank50 += 1
                    if (recentFrameMs.size >= 120) recentFrameMs.removeFirst()
                    recentFrameMs.addLast(dtMs)
                    val recentAvg = recentFrameMs.average()
                    currentFps = if (recentAvg > 0.0) (1000.0 / recentAvg).coerceAtMost(120.0) else 60.0
                }
            }
        }
        lastFrameNs = frameTimeNanos

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastMemPollMs >= 5000L) {
            lastMemPollMs = nowMs
            pollMemoryAsync()
        }
        if (nowMs - lastSampleMs >= 1000L) {
            lastSampleMs = nowMs
            appendTimelinePoint()
            notifyListener()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun pollMemoryAsync() {
        memExecutor.execute {
            try {
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                totalPssMb = round1(mi.totalPss / 1024.0)
                nativeHeapMb = round1(Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0))
                dalvikPssMb = round1(mi.dalvikPss / 1024.0)
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    val sys = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(sys)
                    availRamMb = round1(sys.availMem / (1024.0 * 1024.0))
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun snapshot(): SummarySnapshot {
        synchronized(this) {
            val avg = if (totalFrames > 0 && totalFrameSumMs > 0.0) {
                (1000.0 / (totalFrameSumMs / totalFrames)).coerceAtMost(120.0).roundToInt()
            } else {
                60
            }
            val low5 = if (recentFrameMs.isNotEmpty()) {
                val sorted = recentFrameMs.sortedDescending()
                val p95 = sorted[(sorted.size * 0.05).toInt().coerceIn(0, sorted.lastIndex)]
                if (p95 > 0.0) (1000.0 / p95).coerceAtMost(120.0).roundToInt() else avg
            } else {
                avg
            }
            val engine = if (Build.VERSION.SDK_INT >= 33) "AGSL GPU + C++ مباشر" else "Native C++ مباشر"
            return SummarySnapshot(
                currentFps = currentFps.roundToInt(),
                avgFps = avg,
                low5Fps = low5,
                dropped24 = dropped24,
                jank50 = jank50,
                totalPssMb = totalPssMb,
                gpuCacheMb = round1(gpuFieldCacheBytes / (1024.0 * 1024.0)),
                engineLabel = engine,
                uptimeSec = max(0L, (SystemClock.elapsedRealtime() - startedMonoMs) / 1000L)
            )
        }
    }

    private fun appendTimelinePoint() {
        val snap = snapshot()
        val obj = JSONObject().apply {
            put("t", snap.uptimeSec)
            put("fps", snap.currentFps)
            put("dropped", snap.dropped24)
            put("jank", snap.jank50)
            put("pssMb", snap.totalPssMb)
            put("nativeMb", nativeHeapMb)
            put("cacheMb", snap.gpuCacheMb)
        }
        synchronized(this) {
            if (timeline.size >= 180) timeline.removeFirst()
            timeline.addLast(obj)
        }
    }

    private fun notifyListener() {
        listener?.invoke(snapshot())
    }

    fun buildReportJson(cfg: LockConfig): String {
        val snap = snapshot()
        val root = JSONObject()
        root.put("schema", "oclock.liquidglass.perf.v1")
        root.put("runtime", "pure-native-kotlin-agsl-cpp")
        root.put("sdkInt", Build.VERSION.SDK_INT)
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        root.put("startedAt", startedWallMs)
        root.put("generatedAt", System.currentTimeMillis())

        val sum = JSONObject().apply {
            put("uptimeSec", snap.uptimeSec)
            put("totalFrames", totalFrames)
            put("currentFps", snap.currentFps)
            put("avgFps", snap.avgFps)
            put("low5PctFps", snap.low5Fps)
            put("droppedOver24ms", snap.dropped24)
            put("jankOver50ms", snap.jank50)
            put("maxFrameGapMs", round1(maxFrameGapMs))
            put("totalPssMb", snap.totalPssMb)
            put("dalvikPssMb", dalvikPssMb)
            put("nativeHeapMb", nativeHeapMb)
            put("availSystemRamMb", availRamMb)
            put("gpuFieldCacheMb", snap.gpuCacheMb)
            put("engine", snap.engineLabel)
            put("font", cfg.font)
            put("size", cfg.size.toDouble())
            put("sy", cfg.sy.toDouble())
            put("clockOpacity", cfg.clockOpacity)
        }
        root.put("summary", sum)

        val topArr = JSONArray()
        synchronized(consumers) {
            consumers.entries
                .sortedByDescending { it.value.totalMs }
                .forEach { (name, st) ->
                    topArr.put(JSONObject().apply {
                        put("name", name)
                        put("calls", st.calls)
                        put("totalMs", round1(st.totalMs))
                        put("avgMs", round1(if (st.calls > 0) st.totalMs / st.calls else 0.0))
                        put("maxMs", round1(st.maxMs))
                        put("lastMs", round1(st.lastMs))
                    })
                }
        }
        root.put("topConsumers", topArr)

        val tlArr = JSONArray()
        synchronized(this) {
            timeline.forEach { tlArr.put(it) }
        }
        root.put("timeline", tlArr)
        return root.toString(2)
    }

    fun saveReportToDownloads(cfg: LockConfig, fixedName: String? = null): String? {
        val json = buildReportJson(cfg)
        val fileName = fixedName ?: ("oclock-perf-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json")
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                if (fixedName != null) {
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                        arrayOf(fixedName)
                    )
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OClock")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Downloads/OClock/$fileName"
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "OClock").apply { mkdirs() }
                val out = File(dir, fileName)
                FileOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
                out.absolutePath
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun shareReport(cfg: LockConfig): Intent? {
        return try {
            val dir = File(context.cacheDir, "perf").apply { mkdirs() }
            val name = "oclock-perf-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
            val f = File(dir, name)
            FileOutputStream(f).use { it.write(buildReportJson(cfg).toByteArray(Charsets.UTF_8)) }
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OClock Performance Report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0
}
