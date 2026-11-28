package com.oclock.liquidglass

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MediaController(
    private val context: Context,
    private val onStateChanged: (TrackInfo, Bitmap?, Boolean, Double, Double) -> Unit
) {
    private val deviceQueue = ArrayList<TrackInfo>()
    private val shuffleOrder = ArrayList<Int>()
    private var shufflePos = 0
    private var shuffleEnabled = false

    private var queueIndex = 0
    private var playing = false
    private var posSec = 0.0
    private var lastTickMs = 0L
    private var customTrack: TrackInfo? = null
    private var currentPosterArt: Bitmap? = null
    private var manualArtOverride: Bitmap? = null
    private var player: MediaPlayer? = null
    private var playerPrepared = false
    private var playbackSeq = 0L
    private var sheetOpen = false
    private var screenVisible = true
    private var scanInProgress = false

    private val handler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val artExecutor = Executors.newFixedThreadPool(2)

    private val artCache = object : LruCache<String, Bitmap>(160) {}
    private val noArtKeys = Collections.synchronizedSet(HashSet<String>())
    private val pendingArtWaiters = HashMap<String, ArrayList<Pair<(() -> Boolean)?, (Bitmap?) -> Unit>>>()
    private var onLibraryUpdated: ((List<TrackInfo>) -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing || !screenVisible || sheetOpen) return
            val now = SystemClock.elapsedRealtime()
            val cur = currentTrack()
            if (lastTickMs != 0L) {
                if (cur.contentUri != null) {
                    if (player != null && playerPrepared) {
                        try {
                            posSec = (player?.currentPosition ?: 0) / 1000.0
                        } catch (_: Throwable) {}
                    }
                } else {
                    posSec += (now - lastTickMs) / 1000.0
                    if (posSec >= currentDuration()) {
                        next(autoAdvance = true)
                        return
                    }
                }
            }
            lastTickMs = now
            emit()
            handler.postDelayed(this, 140L)
        }
    }

    fun hasAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasMediaThumbPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            hasAudioPermission()
        }
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun setLibraryListener(cb: ((List<TrackInfo>) -> Unit)?) {
        onLibraryUpdated = cb
    }

    fun setSheetOpen(open: Boolean) {
        sheetOpen = open
        updateTicker()
    }

    fun setVisible(v: Boolean) {
        screenVisible = v
        updateTicker()
    }

    fun getLibraryTracks(): List<TrackInfo> = if (deviceQueue.isNotEmpty()) deviceQueue else emptyList()
    fun hasDeviceTracks(): Boolean = deviceQueue.isNotEmpty()
    fun isShuffle(): Boolean = shuffleEnabled
    fun isScanning(): Boolean = scanInProgress

    private fun activeList(): List<TrackInfo> = if (deviceQueue.isNotEmpty()) deviceQueue else AppConstants.QUEUE

    fun currentTrack(): TrackInfo {
        customTrack?.let { return it }
        val list = activeList()
        return list[queueIndex.coerceIn(0, list.lastIndex)]
    }

    fun currentDuration(): Double = currentTrack().durationSec.toDouble().coerceAtLeast(1.0)
    fun isPlaying(): Boolean = playing

    fun scanDeviceMusic(force: Boolean = false, onDone: ((List<TrackInfo>) -> Unit)? = null) {
        if (!hasAudioPermission()) {
            onDone?.invoke(emptyList())
            return
        }
        if (scanInProgress && !force) return
        scanInProgress = true

        scanExecutor.execute {
            val found = ArrayList<TrackInfo>()
            val palettes = arrayOf(
                intArrayOf(0xFF38BDF8.toInt(), 0xFF1D4ED8.toInt(), 0xFF090D16.toInt()),
                intArrayOf(0xFFEC4899.toInt(), 0xFF7C3AED.toInt(), 0xFF0F172A.toInt()),
                intArrayOf(0xFFF97316.toInt(), 0xFFDB2777.toInt(), 0xFF1E1B4B.toInt()),
                intArrayOf(0xFF34D399.toInt(), 0xFF0284C7.toInt(), 0xFF0F172A.toInt()),
                intArrayOf(0xFFA78BFA.toInt(), 0xFFF43F5E.toInt(), 0xFF18181B.toInt())
            )
            try {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION
                )
                val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                fun queryWithSelection(sel: String) {
                    context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection, sel, null, sortOrder
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "ملف صوتي"
                            val rawArtist = cursor.getString(artistCol)
                            val artist = if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") "فنان غير معروف" else rawArtist
                            val album = cursor.getString(albumCol) ?: ""
                            val albumId = cursor.getLong(albumIdCol)
                            val durSec = max(1, (cursor.getLong(durCol) / 1000L).toInt())
                            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                            val pal = palettes[(abs(id) % palettes.size).toInt()]
                            found.add(
                                TrackInfo(
                                    title = title,
                                    artist = artist,
                                    durationSec = durSec,
                                    c1 = pal[0],
                                    c2 = pal[1],
                                    c3 = pal[2],
                                    contentUri = uri,
                                    albumId = albumId,
                                    albumName = album
                                )
                            )
                        }
                    }
                }
                queryWithSelection("${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 5000")
                if (found.isEmpty()) {
                    queryWithSelection("${MediaStore.Audio.Media.DURATION} >= 15000")
                }
            } catch (_: Throwable) {}

            handler.post {
                scanInProgress = false
                val hadDeviceBefore = deviceQueue.isNotEmpty()
                val oldPlayingUri = currentTrack().contentUri
                deviceQueue.clear()
                deviceQueue.addAll(found)

                if (found.isNotEmpty()) {
                    val keepIdx = if (oldPlayingUri != null) found.indexOfFirst { it.contentUri == oldPlayingUri } else -1
                    queueIndex = if (keepIdx >= 0) keepIdx else queueIndex.coerceIn(0, found.lastIndex)
                    rebuildShuffleOrder(keepCurrent = true)
                    if (!hadDeviceBefore && customTrack == null && !playing) {
                        posSec = 0.0
                        prefetchLockPoster(currentTrack())
                        emit()
                    }
                }
                onLibraryUpdated?.invoke(found)
                onDone?.invoke(found)
            }
        }
    }

    fun getCachedPoster(track: TrackInfo, sizePx: Int): Bitmap? {
        val key = artKey(track, sizePx) ?: return null
        return artCache.get(key)
    }

    fun loadTrackPoster(
        track: TrackInfo,
        sizePx: Int,
        shouldDecode: (() -> Boolean)? = null,
        callback: (Bitmap?) -> Unit
    ) {
        val key = artKey(track, sizePx)
        if (key == null) {
            callback(null)
            return
        }
        artCache.get(key)?.let {
            callback(it)
            return
        }
        if (noArtKeys.contains(key)) {
            callback(null)
            return
        }
        val shouldStart = synchronized(pendingArtWaiters) {
            val list = pendingArtWaiters[key]
            if (list != null) {
                list.add(shouldDecode to callback)
                false
            } else {
                pendingArtWaiters[key] = arrayListOf(shouldDecode to callback)
                true
            }
        }
        if (!shouldStart) return
        artExecutor.execute {
            val wantAny = synchronized(pendingArtWaiters) {
                val waiters = pendingArtWaiters[key]
                waiters?.any { it.first == null || it.first!!.invoke() } ?: false
            }
            if (!wantAny) {
                synchronized(pendingArtWaiters) { pendingArtWaiters.remove(key) }
                return@execute
            }
            val bmp = decodeTrackArt(track, sizePx)
            if (bmp != null) {
                artCache.put(key, bmp)
            } else {
                noArtKeys.add(key)
            }
            val callbacks = synchronized(pendingArtWaiters) {
                pendingArtWaiters.remove(key)?.map { it.second } ?: emptyList()
            }
            handler.post {
                for (cb in callbacks) cb(bmp)
            }
        }
    }

    private fun artKey(track: TrackInfo, sizePx: Int): String? {
        val uri = track.contentUri ?: return null
        return "$uri|${track.albumId}|$sizePx"
    }

    private fun decodeTrackArt(track: TrackInfo, sizePx: Int): Bitmap? {
        val uri = track.contentUri ?: return null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val thumb = context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
                if (thumb != null) {
                    if (thumb.config == Bitmap.Config.HARDWARE) {
                        val soft = thumb.copy(Bitmap.Config.ARGB_8888, false)
                        thumb.recycle()
                        if (soft != null) return soft
                    } else {
                        return thumb
                    }
                }
            } catch (_: Throwable) {}
        }
        if (track.albumId > 0L) {
            try {
                val albumUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), track.albumId)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(albumUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    var inSample = 1
                    while (opts.outWidth / (inSample * 2) >= sizePx && opts.outHeight / (inSample * 2) >= sizePx) {
                        inSample *= 2
                    }
                    val decOpts = BitmapFactory.Options().apply {
                        inSampleSize = inSample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    context.contentResolver.openInputStream(albumUri)?.use { stream ->
                        val raw = BitmapFactory.decodeStream(stream, null, decOpts)
                        if (raw != null) return scaleCenterBitmap(raw, sizePx)
                    }
                }
            } catch (_: Throwable) {}
        }
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)
            val bytes = mmr.embeddedPicture
            mmr.release()
            if (bytes != null) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var inSample = 1
                while (opts.outWidth / (inSample * 2) >= sizePx && opts.outHeight / (inSample * 2) >= sizePx) {
                    inSample *= 2
                }
                val decOpts = BitmapFactory.Options().apply { inSampleSize = inSample }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decOpts)
                if (bmp != null) return scaleCenterBitmap(bmp, sizePx)
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun scaleCenterBitmap(src: Bitmap, targetPx: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= targetPx * 1.4f && h <= targetPx * 1.4f) return src
        val scale = min(1f, targetPx / min(w, h).toFloat())
        val nw = max(1, (w * scale).roundToInt())
        val nh = max(1, (h * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    private fun prefetchLockPoster(track: TrackInfo) {
        if (manualArtOverride != null) return
        if (track.contentUri == null) {
            currentPosterArt = null
            return
        }
        val cached = getCachedPoster(track, 192)
        if (cached != null) {
            currentPosterArt = cached
            return
        }
        currentPosterArt = null
        loadTrackPoster(track, 192) { bmp ->
            if (currentTrack().contentUri == track.contentUri && manualArtOverride == null) {
                currentPosterArt = bmp
                emit()
            }
        }
    }

    private fun rebuildShuffleOrder(keepCurrent: Boolean) {
        val list = activeList()
        shuffleOrder.clear()
        if (list.isEmpty()) return
        val indices = (list.indices).toMutableList()
        indices.shuffle()
        if (keepCurrent && queueIndex in list.indices) {
            indices.remove(queueIndex)
            indices.add(0, queueIndex)
        }
        shuffleOrder.addAll(indices)
        shufflePos = 0
    }

    fun playDeviceTrack(index: Int, keepShuffle: Boolean = false) {
        val list = activeList()
        if (index !in list.indices) return
        customTrack = null
        manualArtOverride = null
        if (!keepShuffle) shuffleEnabled = false
        queueIndex = index
        if (shuffleEnabled) {
            val foundPos = shuffleOrder.indexOf(index)
            if (foundPos >= 0) shufflePos = foundPos else rebuildShuffleOrder(keepCurrent = true)
        }
        startTrackPlayback(list[index], autoPlay = true)
    }

    fun startShuffled() {
        val list = activeList()
        if (list.isEmpty()) return
        customTrack = null
        manualArtOverride = null
        shuffleEnabled = true
        rebuildShuffleOrder(keepCurrent = false)
        queueIndex = shuffleOrder[0]
        shufflePos = 0
        startTrackPlayback(list[queueIndex], autoPlay = true)
    }

    fun startSequential(fromIndex: Int = 0) {
        val list = activeList()
        if (list.isEmpty()) return
        customTrack = null
        manualArtOverride = null
        shuffleEnabled = false
        queueIndex = fromIndex.coerceIn(0, list.lastIndex)
        startTrackPlayback(list[queueIndex], autoPlay = true)
    }

    fun toggleShuffle(): Boolean {
        shuffleEnabled = !shuffleEnabled
        if (shuffleEnabled) rebuildShuffleOrder(keepCurrent = true)
        onLibraryUpdated?.invoke(getLibraryTracks())
        emit()
        return shuffleEnabled
    }

    private fun startTrackPlayback(track: TrackInfo, autoPlay: Boolean) {
        releasePlayer()
        posSec = 0.0
        playing = autoPlay
        lastTickMs = SystemClock.elapsedRealtime()
        prefetchLockPoster(track)

        val uri = track.contentUri
        if (uri != null) {
            val mySeq = ++playbackSeq
            val mp = MediaPlayer()
            player = mp
            playerPrepared = false
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setDataSource(context, uri)
                mp.setOnPreparedListener { readyMp ->
                    if (mySeq != playbackSeq || readyMp !== player) return@setOnPreparedListener
                    playerPrepared = true
                    val durMs = readyMp.duration
                    if (durMs > 0 && customTrack != null) {
                        customTrack = customTrack?.copy(durationSec = max(1, durMs / 1000))
                    }
                    if (playing) {
                        try { readyMp.start() } catch (_: Throwable) {}
                    }
                    lastTickMs = SystemClock.elapsedRealtime()
                    updateTicker()
                    emit()
                }
                mp.setOnCompletionListener { doneMp ->
                    if (mySeq != playbackSeq || doneMp !== player) return@setOnCompletionListener
                    next(autoAdvance = true)
                }
                mp.setOnErrorListener { errMp, _, _ ->
                    if (mySeq != playbackSeq || errMp !== player) return@setOnErrorListener true
                    playing = false
                    playerPrepared = false
                    updateTicker()
                    emit()
                    true
                }
                mp.prepareAsync()
            } catch (_: Throwable) {
                mp.release()
                if (player === mp) player = null
                playerPrepared = false
            }
        }
        updateTicker()
        onLibraryUpdated?.invoke(getLibraryTracks())
        emit()
    }

    fun togglePlay() {
        val cur = currentTrack()
        playing = !playing
        if (cur.contentUri != null) {
            if (player == null) {
                startTrackPlayback(cur, autoPlay = playing)
                return
            }
            if (playerPrepared) {
                try {
                    if (playing) player?.start() else player?.pause()
                } catch (_: Throwable) {}
            }
        }
        lastTickMs = SystemClock.elapsedRealtime()
        updateTicker()
        emit()
    }

    fun next(autoAdvance: Boolean = false) {
        val list = activeList()
        if (list.isEmpty()) return
        customTrack = null
        manualArtOverride = null
        if (shuffleEnabled && shuffleOrder.size == list.size) {
            shufflePos = (shufflePos + 1) % shuffleOrder.size
            queueIndex = shuffleOrder[shufflePos]
        } else {
            queueIndex = (queueIndex + 1) % list.size
        }
        val shouldPlay = playing || autoAdvance
        startTrackPlayback(list[queueIndex], autoPlay = shouldPlay)
    }

    fun prev() {
        if (posSec > 3.0) {
            seekRatio(0f)
            return
        }
        val list = activeList()
        if (list.isEmpty()) return
        customTrack = null
        manualArtOverride = null
        if (shuffleEnabled && shuffleOrder.size == list.size) {
            shufflePos = (shufflePos - 1 + shuffleOrder.size) % shuffleOrder.size
            queueIndex = shuffleOrder[shufflePos]
        } else {
            queueIndex = (queueIndex - 1 + list.size) % list.size
        }
        startTrackPlayback(list[queueIndex], autoPlay = playing)
    }

    fun seekRatio(ratio: Float) {
        val target = (ratio.coerceIn(0f, 1f) * currentDuration())
        posSec = target
        if (player != null && playerPrepared) {
            try {
                player?.seekTo((target * 1000.0).toInt())
            } catch (_: Throwable) {}
        }
        emit()
    }

    fun loadLocalAudio(uri: Uri) {
        releasePlayer()
        manualArtOverride = null
        var title = uri.lastPathSegment?.substringAfterLast('/') ?: "ملف صوتي"
        var artist = "صوت محلي"
        var durSec = 180
        var embeddedArt: Bitmap? = null

        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durMs > 0L) durSec = max(1, (durMs / 1000L).toInt())
            mmr.embeddedPicture?.let { bytes ->
                embeddedArt = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { scaleCenterBitmap(it, 192) }
            }
            mmr.release()
        } catch (_: Throwable) {}

        val t = TrackInfo(title, artist, durSec, 0xFF38BDF8.toInt(), 0xFF6366F1.toInt(), 0xFF0F172A.toInt(), contentUri = uri)
        customTrack = t
        currentPosterArt = embeddedArt
        startTrackPlayback(t, autoPlay = true)
    }

    fun setCustomArt(bmp: Bitmap) {
        manualArtOverride = bmp
        emit()
    }

    fun emit() {
        onStateChanged(currentTrack(), manualArtOverride ?: currentPosterArt, playing, posSec, currentDuration())
    }

    private fun updateTicker() {
        handler.removeCallbacks(ticker)
        if (playing && screenVisible && !sheetOpen) {
            lastTickMs = SystemClock.elapsedRealtime()
            handler.postDelayed(ticker, 140L)
        }
    }

    private fun releasePlayer() {
        playbackSeq++
        playerPrepared = false
        try {
            player?.stop()
        } catch (_: Throwable) {}
        try {
            player?.release()
        } catch (_: Throwable) {}
        player = null
    }

    fun shutdown() {
        handler.removeCallbacks(ticker)
        releasePlayer()
        scanExecutor.shutdownNow()
        artExecutor.shutdownNow()
        artCache.evictAll()
    }
}
