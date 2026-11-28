package com.oclock.liquidglass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AIDepthManager(private val context: Context) {
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val segmenter by lazy {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        SubjectSegmentation.getClient(options)
    }

    @Volatile var isBusy = false
        private set

    fun extractSubject(
        sourceWall: Bitmap,
        onProgress: (Int, String) -> Unit,
        onReady: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isBusy) return
        isBusy = true
        onProgress(8, "جارٍ تجهيز العزل…")

        val moduleInstallClient = ModuleInstall.getClient(context)
        moduleInstallClient.areModulesAvailable(segmenter)
            .addOnSuccessListener { avail ->
                if (avail.areModulesAvailable()) {
                    runSegmentation(sourceWall, onProgress, onReady, onError)
                } else {
                    onProgress(18, "جارٍ تجهيز نموذج العزل…")
                    val req = ModuleInstallRequest.newBuilder()
                        .addApi(segmenter)
                        .setListener { update ->
                            val info = update.progressInfo
                            if (info != null && info.totalBytesToDownload > 0) {
                                val pct = ((info.bytesDownloaded * 55L) / info.totalBytesToDownload).toInt() + 18
                                onProgress(pct.coerceIn(18, 73), "جارٍ تحميل نموذج العزل…")
                            }
                        }
                        .build()
                    moduleInstallClient.installModules(req)
                        .addOnSuccessListener {
                            runSegmentation(sourceWall, onProgress, onReady, onError)
                        }
                        .addOnFailureListener {
                            // Try direct segmentation anyway (bundled or cached)
                            runSegmentation(sourceWall, onProgress, onReady, onError)
                        }
                }
            }
            .addOnFailureListener {
                runSegmentation(sourceWall, onProgress, onReady, onError)
            }
    }

    private fun runSegmentation(
        sourceWall: Bitmap,
        onProgress: (Int, String) -> Unit,
        onReady: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        onProgress(76, "جارٍ عزل الشخصية…")
        bgExecutor.execute {
            try {
                val maxDim = 960
                val sw = sourceWall.width
                val sh = sourceWall.height
                val scale = min(1f, maxDim / max(sw, sh).toFloat())
                val tw = max(16, (sw * scale).roundToInt())
                val th = max(16, (sh * scale).roundToInt())
                val scaled = if (tw == sw && th == sh) sourceWall else Bitmap.createScaledBitmap(sourceWall, tw, th, true)

                val input = InputImage.fromBitmap(scaled, 0)
                segmenter.process(input)
                    .addOnSuccessListener { result ->
                        bgExecutor.execute {
                            try {
                                val mask = result.foregroundConfidenceMask
                                if (mask == null) {
                                    isBusy = false
                                    onError("لم يُعثر على عنصر مقدمة واضح")
                                    return@execute
                                }
                                mask.rewind()
                                val count = tw * th
                                val srcPx = IntArray(count)
                                scaled.getPixels(srcPx, 0, tw, 0, 0, tw, th)
                                val outPx = IntArray(count)
                                var fgPixels = 0

                                for (i in 0 until count) {
                                    val c = if (mask.hasRemaining()) mask.get() else 0f
                                    // Smoothstep [0.28 .. 0.72] crisp edge feathering without halo
                                    val t = ((c - 0.28f) / 0.44f).coerceIn(0f, 1f)
                                    val alpha = t * t * (3f - 2f * t)
                                    val aByte = (alpha * 255f).roundToInt().coerceIn(0, 255)
                                    if (aByte > 24) fgPixels++
                                    val p = srcPx[i]
                                    outPx[i] = (aByte shl 24) or (p and 0x00FFFFFF)
                                }
                                if (scaled !== sourceWall) scaled.recycle()

                                if (fgPixels < count * 0.015f) {
                                    isBusy = false
                                    onError("لم يُعثر على شخص أو عنصر مقدمة واضح")
                                    return@execute
                                }

                                val cutout = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                                cutout.setPixels(outPx, 0, tw, 0, 0, tw, th)
                                isBusy = false
                                onProgress(100, "تم العزل بنجاح ✓")
                                onReady(cutout)
                            } catch (e: Throwable) {
                                isBusy = false
                                onError("تعذر معالجة قناع العزل")
                            }
                        }
                    }
                    .addOnFailureListener {
                        isBusy = false
                        onError("يلزم تفعيل Google Play Services لأول مرة فقط")
                    }
            } catch (_: Throwable) {
                isBusy = false
                onError("تعذر تجهيز الصورة للعزل")
            }
        }
    }

    fun shutdown() {
        segmenter.close()
        bgExecutor.shutdownNow()
    }
}
