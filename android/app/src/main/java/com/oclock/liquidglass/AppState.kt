package com.oclock.liquidglass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ClockFont(val id: String, val label: String, val assetPath: String?) {
    BEBAS("bebas", "Bebas", "fonts/bebas_neue.ttf"),
    OSWALD("oswald", "Oswald", "fonts/oswald.ttf"),
    INTER("inter", "Inter", "fonts/inter.ttf"),
    SF("sf", "SF", null),
    ROUNDED("rounded", "Rounded", "fonts/sf_rounded.ttf"),
    CLASSIC("classic", "Classic", "fonts/ios_classic.ttf"),
    SLAB("slab", "Slab", "fonts/ios_slab.ttf"),
    STENCIL("stencil", "Stencil", "fonts/ios_stencil.ttf"),
    RAILS("rails", "Rails", "fonts/ios_rails.ttf"),
    HEAVY("heavy", "Heavy", "fonts/ios_heavy.ttf");

    companion object {
        fun fromId(id: String?): ClockFont = entries.firstOrNull { it.id == id } ?: BEBAS
    }
}

enum class PresetWall(val id: String, val title: String) {
    ANGEL("angel", "Angel"),
    OBSIDIAN("obsidian", "Obsidian"),
    DUSK("dusk", "Dusk"),
    AURORA("aurora", "Aurora"),
    DAYLIGHT("daylight", "Daylight");

    companion object {
        fun fromId(id: String?): PresetWall = entries.firstOrNull { it.id == id } ?: ANGEL
    }
}

data class TrackInfo(
    val title: String,
    val artist: String,
    val durationSec: Int,
    val c1: Int,
    val c2: Int,
    val c3: Int,
    val contentUri: Uri? = null,
    val albumId: Long = 0L,
    val albumName: String = ""
)

data class LockNotification(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestampMs: Long,
    val count: Int = 1,
    val accentColor: Int = 0xFF38BDF8.toInt(),
    val isSystem: Boolean = false,
    val isDismissible: Boolean = true
)

data class LockConfig(
    var wall: String = "angel",
    var hasCustomWall: Boolean = false,
    var hasCustomSubject: Boolean = false,
    var font: String = "bebas",
    var size: Float = 208f,
    var sx: Float = 1.0f,
    var sy: Float = 1.68f,
    var tracking: Float = -2f,
    var clockY: Float = 0f,
    var hour24: Boolean = false,
    var leadingZero: Boolean = false,
    var color: String = "auto",
    var clockOpacity: Int = 14,
    var innerOpacity: Int = 100,
    var innerThickness: Float = 0.3f,
    var innerAngle: Float = 59f,
    var innerFade: Int = 100,
    var innerColor: String = "#ffffff",
    var depth: Boolean = false,
    var weather: Boolean = true,
    var music: Boolean = true,
    var notifications: Boolean = true,
    var dnd: Boolean = false,
    var passcodeEnabled: Boolean = true,
    var passcodePin: String = "000000"
) {
    fun clamp() {
        size = size.coerceIn(56f, 420f)
        sx = sx.coerceIn(0.6f, 2.0f)
        sy = sy.coerceIn(1.0f, 3.10f)
        clockY = clockY.coerceIn(-20f, 80f)
        clockOpacity = clockOpacity.coerceIn(0, 100)
        innerOpacity = innerOpacity.coerceIn(0, 100)
        innerThickness = innerThickness.coerceIn(0f, 8f)
        innerAngle = innerAngle.coerceIn(0f, 360f)
        innerFade = innerFade.coerceIn(0, 100)
        if ((passcodePin.length != 4 && passcodePin.length != 6) || !passcodePin.all { it in '0'..'9' }) {
            passcodePin = "000000"
        }
    }

    fun snapshotForUndo() = UndoSnapshot(size, sx, sy)
}

data class UndoSnapshot(val size: Float, val sx: Float, val sy: Float)

object AppConstants {
    val CLOCK_COLORS = listOf(
        "auto", "#ffffff", "#000000", "#e5e9f0", "#ffe4ec",
        "#b3dfff", "#ffd8a8", "#c3f0ca", "#e2c6ff",
        "#7dd3fc", "#f472b6", "#fb923c", "#a78bfa"
    )

    val INNER_COLORS = listOf(
        "#ffffff", "#000000", "#e2e8f0", "#b3dfff", "#60a5fa", "#ffd8a8",
        "#fb923c", "#f472b6", "#a78bfa", "#c3f0ca", "#fde047", "#fb7185"
    )

    val QUEUE = listOf(
        TrackInfo("Grown Woman", "Beyoncé", 263, 0xFFEC4899.toInt(), 0xFF7C3AED.toInt(), 0xFF0F172A.toInt()),
        TrackInfo("Midnight City", "M83", 243, 0xFF38BDF8.toInt(), 0xFF1D4ED8.toInt(), 0xFF090D16.toInt()),
        TrackInfo("Nights", "Frank Ocean", 307, 0xFFF97316.toInt(), 0xFFDB2777.toInt(), 0xFF1E1B4B.toInt()),
        TrackInfo("Let It Happen", "Tame Impala", 467, 0xFF34D399.toInt(), 0xFF0284C7.toInt(), 0xFF0F172A.toInt()),
        TrackInfo("Telepatía", "Kali Uchis", 160, 0xFFA78BFA.toInt(), 0xFFF43F5E.toInt(), 0xFF18181B.toInt())
    )

    fun parseHexColor(hex: String, fallback: Int = Color.WHITE): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Throwable) {
            fallback
        }
    }

    fun hslToRgbInt(hDeg: Float, sPct: Float, lPct: Float): Int {
        val h = ((hDeg % 360f) + 360f) % 360f / 360f
        val s = (sPct / 100f).coerceIn(0f, 1f)
        val l = (lPct / 100f).coerceIn(0f, 1f)
        if (s == 0f) {
            val v = (l * 255f).roundToInt().coerceIn(0, 255)
            return Color.rgb(v, v, v)
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun hue2rgb(tIn: Float): Float {
            var t = tIn
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 0.5f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        val r = (hue2rgb(h + 1f / 3f) * 255f).roundToInt().coerceIn(0, 255)
        val g = (hue2rgb(h) * 255f).roundToInt().coerceIn(0, 255)
        val b = (hue2rgb(h - 1f / 3f) * 255f).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    fun rgbIntToHsl(color: Int): FloatArray {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) * 0.5f
        if (maxC == minC) return floatArrayOf(0f, 0f, l * 100f)
        val d = maxC - minC
        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        var h = when (maxC) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h /= 6f
        return floatArrayOf(h * 360f, s * 100f, l * 100f)
    }

    fun toHex(color: Int): String {
        return String.format("#%02x%02x%02x", Color.red(color), Color.green(color), Color.blue(color))
    }
}

class StateStore(context: Context) {
    private val prefs = context.getSharedPreferences("lock_glass_native_v1", Context.MODE_PRIVATE)
    private val wallFile = File(context.filesDir, "custom_wall.jpg")
    private val subjectFile = File(context.filesDir, "custom_subject.png")

    fun load(): LockConfig {
        val cfg = LockConfig()
        val raw = prefs.getString("config", null) ?: return cfg
        return try {
            val j = JSONObject(raw)
            cfg.wall = j.optString("wall", cfg.wall)
            cfg.hasCustomWall = j.optBoolean("hasCustomWall", false) && wallFile.exists()
            cfg.hasCustomSubject = j.optBoolean("hasCustomSubject", false) && subjectFile.exists()
            cfg.font = j.optString("font", cfg.font)
            cfg.size = j.optDouble("size", cfg.size.toDouble()).toFloat()
            cfg.sx = j.optDouble("sx", cfg.sx.toDouble()).toFloat()
            cfg.sy = j.optDouble("sy", cfg.sy.toDouble()).toFloat()
            cfg.tracking = j.optDouble("tracking", cfg.tracking.toDouble()).toFloat()
            cfg.clockY = j.optDouble("clockY", cfg.clockY.toDouble()).toFloat()
            cfg.hour24 = j.optBoolean("hour24", cfg.hour24)
            cfg.leadingZero = j.optBoolean("leadingZero", cfg.leadingZero)
            cfg.color = j.optString("color", cfg.color)
            cfg.clockOpacity = j.optInt("clockOpacity", cfg.clockOpacity)
            cfg.innerOpacity = j.optInt("innerOpacity", cfg.innerOpacity)
            cfg.innerThickness = j.optDouble("innerThickness", cfg.innerThickness.toDouble()).toFloat()
            cfg.innerAngle = j.optDouble("innerAngle", cfg.innerAngle.toDouble()).toFloat()
            cfg.innerFade = j.optInt("innerFade", cfg.innerFade)
            cfg.innerColor = j.optString("innerColor", cfg.innerColor)
            cfg.depth = j.optBoolean("depth", cfg.depth)
            cfg.weather = j.optBoolean("weather", cfg.weather)
            cfg.music = j.optBoolean("music", cfg.music)
            cfg.notifications = j.optBoolean("notifications", cfg.notifications)
            cfg.dnd = j.optBoolean("dnd", cfg.dnd)
            cfg.passcodeEnabled = j.optBoolean("passcodeEnabled", cfg.passcodeEnabled)
            cfg.passcodePin = j.optString("passcodePin", cfg.passcodePin)
            cfg.clamp()
            cfg
        } catch (_: Throwable) {
            cfg
        }
    }

    fun save(cfg: LockConfig) {
        cfg.clamp()
        val j = JSONObject().apply {
            put("wall", cfg.wall)
            put("hasCustomWall", cfg.hasCustomWall && wallFile.exists())
            put("hasCustomSubject", cfg.hasCustomSubject && subjectFile.exists())
            put("font", cfg.font)
            put("size", cfg.size.toDouble())
            put("sx", cfg.sx.toDouble())
            put("sy", cfg.sy.toDouble())
            put("tracking", cfg.tracking.toDouble())
            put("clockY", cfg.clockY.toDouble())
            put("hour24", cfg.hour24)
            put("leadingZero", cfg.leadingZero)
            put("color", cfg.color)
            put("clockOpacity", cfg.clockOpacity)
            put("innerOpacity", cfg.innerOpacity)
            put("innerThickness", cfg.innerThickness.toDouble())
            put("innerAngle", cfg.innerAngle.toDouble())
            put("innerFade", cfg.innerFade)
            put("innerColor", cfg.innerColor)
            put("depth", cfg.depth)
            put("weather", cfg.weather)
            put("music", cfg.music)
            put("notifications", cfg.notifications)
            put("dnd", cfg.dnd)
            put("passcodeEnabled", cfg.passcodeEnabled)
            put("passcodePin", cfg.passcodePin)
        }
        prefs.edit().putString("config", j.toString()).apply()
    }

    fun saveCustomWall(bmp: Bitmap): Boolean {
        return try {
            FileOutputStream(wallFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun loadCustomWall(): Bitmap? {
        if (!wallFile.exists()) return null
        return try {
            BitmapFactory.decodeFile(wallFile.absolutePath)
        } catch (_: Throwable) {
            null
        }
    }

    fun saveCustomSubject(bmp: Bitmap): Boolean {
        return try {
            FileOutputStream(subjectFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun loadCustomSubject(): Bitmap? {
        if (!subjectFile.exists()) return null
        return try {
            BitmapFactory.decodeFile(subjectFile.absolutePath)
        } catch (_: Throwable) {
            null
        }
    }

    fun clearCustomSubject() {
        if (subjectFile.exists()) subjectFile.delete()
    }

    fun clearCustomWall() {
        if (wallFile.exists()) wallFile.delete()
    }
}
