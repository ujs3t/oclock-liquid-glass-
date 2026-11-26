package com.oclock.liquidglass

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

class OClockNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        instanceRef = WeakReference(this)
        pushSnapshot()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instanceRef?.get() === this) instanceRef = null
        pendingIntents.clear()
        mainHandler.post { listenerCallback?.onSystemChanged(emptyList(), null, null) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (shouldIgnore(sbn)) return
        val item = toLockNotification(sbn)
        sbn.notification.contentIntent?.let { pendingIntents[sbn.key] = it }
        mainHandler.post { listenerCallback?.onSystemPosted(item, buildSnapshot()) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        pendingIntents.remove(sbn.key)
        mainHandler.post { listenerCallback?.onSystemRemoved(sbn.key, buildSnapshot()) }
    }

    private fun shouldIgnore(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == applicationContext.packageName) return true
        val n = sbn.notification ?: return true
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return true
        val cat = n.category
        if (cat == Notification.CATEGORY_TRANSPORT && (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return true
        if ((n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0 && sbn.isOngoing) return true
        return false
    }

    private fun buildSnapshot(): List<LockNotification> {
        val active = try {
            activeNotifications ?: emptyArray()
        } catch (_: Throwable) {
            emptyArray()
        }
        val list = ArrayList<LockNotification>(active.size)
        for (sbn in active) {
            if (shouldIgnore(sbn)) continue
            sbn.notification.contentIntent?.let { pendingIntents[sbn.key] = it }
            list.add(toLockNotification(sbn))
        }
        list.sortByDescending { it.timestampMs }
        return list
    }

    private fun pushSnapshot() {
        val snap = buildSnapshot()
        mainHandler.post { listenerCallback?.onSystemChanged(snap, null, null) }
    }

    private fun toLockNotification(sbn: StatusBarNotification): LockNotification {
        val n = sbn.notification
        val extras = n.extras
        val appName = resolveAppLabel(sbn.packageName)
        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawBig = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val rawText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val rawSub = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val title = if (rawTitle.isNotEmpty()) rawTitle else appName
        val body = when {
            rawBig.isNotEmpty() -> rawBig
            rawText.isNotEmpty() -> rawText
            rawSub.isNotEmpty() -> rawSub
            else -> "إشعار جديد"
        }
        val count = max(1, n.number)
        val col = if (n.color != 0) n.color else 0xFF38BDF8.toInt()
        return LockNotification(
            id = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = if (title == appName) appName else "$appName • $title",
            text = body,
            timestampMs = sbn.postTime,
            count = count,
            accentColor = col,
            isSystem = true,
            isDismissible = sbn.isClearable
        )
    }

    private fun resolveAppLabel(pkg: String): String {
        return labelCache.getOrPut(pkg) {
            try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Throwable) {
                pkg.substringAfterLast('.')
            }
        }
    }

    interface SystemCallback {
        fun onSystemChanged(all: List<LockNotification>, posted: LockNotification?, removedId: String?)
        fun onSystemPosted(item: LockNotification, all: List<LockNotification>)
        fun onSystemRemoved(id: String, all: List<LockNotification>)
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile
        private var instanceRef: WeakReference<OClockNotificationListenerService>? = null
        @Volatile
        var listenerCallback: SystemCallback? = null
        private val pendingIntents = ConcurrentHashMap<String, PendingIntent>()
        private val labelCache = ConcurrentHashMap<String, String>()

        fun cancelById(key: String) {
            pendingIntents.remove(key)
            try {
                instanceRef?.get()?.cancelNotification(key)
            } catch (_: Throwable) {}
        }

        fun cancelAllSystem() {
            val svc = instanceRef?.get() ?: return
            try {
                val active = svc.activeNotifications ?: return
                for (sbn in active) {
                    if (sbn.isClearable && sbn.packageName != svc.packageName) {
                        pendingIntents.remove(sbn.key)
                        svc.cancelNotification(sbn.key)
                    }
                }
            } catch (_: Throwable) {}
        }

        fun getContentIntent(key: String): PendingIntent? = pendingIntents[key]

        fun requestSync() {
            instanceRef?.get()?.pushSnapshot()
        }
    }
}

class NotificationHub(private val context: Context) : OClockNotificationListenerService.SystemCallback {
    interface Listener {
        fun onNotificationsUpdated(items: List<LockNotification>, added: LockNotification?, removedId: String?)
    }

    private val iconCache = LruCache<String, Bitmap>(48)
    private val items = ArrayList<LockNotification>()
    private val sampleSeq = AtomicInteger(100)
    private var listener: Listener? = null
    private var usingSamples = false

    private val sampleTemplates = listOf(
        Triple("com.snapchat.android", "Snapchat", "2 إشعارات جديدة"),
        Triple("com.twitter.android", "X", "تغريدة رائجة قد تهمك الآن"),
        Triple("com.android.dialer", "الهاتف", "مكالمات فائتة (3)"),
        Triple("com.whatsapp", "WhatsApp", "مرحباً! هل جربت التصميم الزجاجي الجديد؟"),
        Triple("org.telegram.messenger", "Telegram", "تم إرسال صورة في القناة"),
        Triple("com.instagram.android", "Instagram", "أعجب بمنشورك وعلّق عليه")
    )

    init {
        OClockNotificationListenerService.listenerCallback = this
        if (hasNotificationAccess()) {
            OClockNotificationListenerService.requestSync()
        } else {
            seedInitialSamples()
        }
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    fun getItems(): List<LockNotification> = ArrayList(items)

    fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, OClockNotificationListenerService::class.java)
        return flat.contains(me.flattenToString()) || flat.contains(context.packageName)
    }

    fun openNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {}
    }

    fun refreshFromSystem() {
        if (hasNotificationAccess()) {
            OClockNotificationListenerService.requestSync()
        }
    }

    fun seedInitialSamples() {
        val now = System.currentTimeMillis()
        usingSamples = true
        items.clear()
        items.add(LockNotification("sample_1", "com.snapchat.android", "Snapchat", "Snapchat", "2 إشعارات جديدة", now - 60_000L, 2, 0xFFFFFC00.toInt(), false, true))
        items.add(LockNotification("sample_2", "com.twitter.android", "X", "X", "إشعار جديد • متابعة النشاط", now - 17 * 60_000L, 1, 0xFFFFFFFF.toInt(), false, true))
        items.add(LockNotification("sample_3", "com.android.dialer", "الهاتف", "الهاتف", "34 إشعارات ومكالمات", now - 3 * 3_600_000L, 34, 0xFF30D158.toInt(), false, true))
        items.add(LockNotification("sample_4", "com.whatsapp", "WhatsApp", "WhatsApp • عمر", "التصميم الزجاجي يبدو رائعاً جداً!", now - 3 * 3_600_000L - 120_000L, 5, 0xFF25D366.toInt(), false, true))
        listener?.onNotificationsUpdated(getItems(), null, null)
    }

    fun addSampleNotification() {
        val idx = sampleSeq.incrementAndGet()
        val tpl = sampleTemplates[idx % sampleTemplates.size]
        val cnt = if (idx % 3 == 0) 2 else 1
        val item = LockNotification(
            id = "sample_$idx",
            packageName = tpl.first,
            appName = tpl.second,
            title = tpl.second,
            text = tpl.third,
            timestampMs = System.currentTimeMillis(),
            count = cnt,
            accentColor = sampleAccent(tpl.first),
            isSystem = false,
            isDismissible = true
        )
        items.add(0, item)
        listener?.onNotificationsUpdated(getItems(), item, null)
    }

    fun dismiss(id: String) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        val removed = items.removeAt(idx)
        if (removed.isSystem) {
            OClockNotificationListenerService.cancelById(id)
        }
        listener?.onNotificationsUpdated(getItems(), null, id)
    }

    fun clearAll() {
        val hadSystem = items.any { it.isSystem }
        items.removeAll { it.isDismissible }
        if (hadSystem) {
            OClockNotificationListenerService.cancelAllSystem()
        }
        listener?.onNotificationsUpdated(getItems(), null, null)
    }

    fun openNotification(item: LockNotification): Boolean {
        if (!item.isSystem) return false
        val pi = OClockNotificationListenerService.getContentIntent(item.id) ?: return false
        return try {
            pi.send()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun getAppIcon(packageName: String, sizePx: Int): Bitmap {
        val key = "$packageName|$sizePx"
        iconCache.get(key)?.let { return it }
        val bmp = decodePackageIcon(packageName, sizePx) ?: renderBrandedBadge(packageName, sizePx)
        iconCache.put(key, bmp)
        return bmp
    }

    private fun decodePackageIcon(packageName: String, sizePx: Int): Bitmap? {
        return try {
            val d: Drawable = context.packageManager.getApplicationIcon(packageName)
            val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            d.setBounds(0, 0, sizePx, sizePx)
            d.draw(c)
            out
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun renderBrandedBadge(pkg: String, sz: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val (cTop, cBot, glyph) = when {
            pkg.contains("snapchat") -> Triple(0xFFFFA8D8.toInt(), 0xFFF472B6.toInt(), "S")
            pkg.contains("twitter") -> Triple(0xFF1E222B.toInt(), 0xFF0A0C10.toInt(), "𝕏")
            pkg.contains("dialer") || pkg.contains("phone") -> Triple(0xFF93C5FD.toInt(), 0xFF34D399.toInt(), "✆")
            pkg.contains("whatsapp") -> Triple(0xFF4ADE80.toInt(), 0xFF16A34A.toInt(), "W")
            pkg.contains("telegram") -> Triple(0xFF38BDF8.toInt(), 0xFF0284C7.toInt(), "✈")
            pkg.contains("instagram") -> Triple(0xFFF472B6.toInt(), 0xFFF59E0B.toInt(), "◎")
            else -> Triple(0xFF60A5FA.toInt(), 0xFF2563EB.toInt(), pkg.substringAfterLast('.').take(1).uppercase())
        }
        p.shader = LinearGradient(0f, 0f, sz.toFloat(), sz.toFloat(), cTop, cBot, Shader.TileMode.CLAMP)
        val rad = sz * 0.24f
        c.drawRoundRect(RectF(0f, 0f, sz.toFloat(), sz.toFloat()), rad, rad, p)
        p.shader = null
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.textSize = sz * 0.50f
        val fm = p.fontMetrics
        val cy = sz * 0.5f - (fm.ascent + fm.descent) * 0.5f
        c.drawText(glyph, sz * 0.5f, cy, p)
        return bmp
    }

    private fun sampleAccent(pkg: String): Int = when {
        pkg.contains("snapchat") -> 0xFFF472B6.toInt()
        pkg.contains("twitter") -> 0xFFFFFFFF.toInt()
        pkg.contains("dialer") -> 0xFF30D158.toInt()
        pkg.contains("whatsapp") -> 0xFF25D366.toInt()
        pkg.contains("telegram") -> 0xFF38BDF8.toInt()
        else -> 0xFFA78BFA.toInt()
    }

    override fun onSystemChanged(all: List<LockNotification>, posted: LockNotification?, removedId: String?) {
        usingSamples = false
        items.clear()
        items.addAll(all)
        listener?.onNotificationsUpdated(getItems(), posted, removedId)
    }

    override fun onSystemPosted(item: LockNotification, all: List<LockNotification>) {
        usingSamples = false
        items.clear()
        items.addAll(all)
        listener?.onNotificationsUpdated(getItems(), item, null)
    }

    override fun onSystemRemoved(id: String, all: List<LockNotification>) {
        usingSamples = false
        items.clear()
        items.addAll(all)
        listener?.onNotificationsUpdated(getItems(), null, id)
    }

    fun shutdown() {
        if (OClockNotificationListenerService.listenerCallback === this) {
            OClockNotificationListenerService.listenerCallback = null
        }
    }

    companion object {
        fun formatRelativeTime(timestampMs: Long): String {
            val diffSec = max(0L, (System.currentTimeMillis() - timestampMs) / 1000L)
            return when {
                diffSec < 45L -> "الآن"
                diffSec < 3600L -> "${diffSec / 60L}m ago"
                diffSec < 86_400L -> "${diffSec / 3600L}h ago"
                else -> "${diffSec / 86_400L}d ago"
            }
        }
    }
}
