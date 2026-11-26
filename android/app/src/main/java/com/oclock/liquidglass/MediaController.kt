package com.oclock.liquidglass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import kotlin.math.max

class MediaController(
    private val context: Context,
    private val onStateChanged: (TrackInfo, Bitmap?, Boolean, Double, Double) -> Unit
) {
    private var queueIndex = 0
    private var playing = false
    private var posSec = 0.0
    private var lastTickMs = 0L
    private var customTrack: TrackInfo? = null
    private var customArt: Bitmap? = null
    private var player: MediaPlayer? = null
    private var sheetOpen = false
    private var screenVisible = true

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!playing || !screenVisible || sheetOpen) return
            val now = SystemClock.elapsedRealtime()
            if (lastTickMs != 0L) {
                if (player != null && customTrack != null) {
                    try {
                        posSec = (player?.currentPosition ?: 0) / 1000.0
                    } catch (_: Throwable) {}
                } else {
                    posSec += (now - lastTickMs) / 1000.0
                    if (posSec >= currentDuration()) {
                        next()
                        return
                    }
                }
            }
            lastTickMs = now
            emit()
            handler.postDelayed(this, 140L)
        }
    }

    fun setSheetOpen(open: Boolean) {
        sheetOpen = open
        updateTicker()
    }

    fun setVisible(v: Boolean) {
        screenVisible = v
        updateTicker()
    }

    fun currentTrack(): TrackInfo = customTrack ?: AppConstants.QUEUE[queueIndex]
    fun currentDuration(): Double = currentTrack().durationSec.toDouble().coerceAtLeast(1.0)
    fun isPlaying(): Boolean = playing

    fun togglePlay() {
        playing = !playing
        if (player != null && customTrack != null) {
            try {
                if (playing) player?.start() else player?.pause()
            } catch (_: Throwable) {}
        }
        lastTickMs = SystemClock.elapsedRealtime()
        updateTicker()
        emit()
    }

    fun next() {
        customTrack = null
        releasePlayer()
        queueIndex = (queueIndex + 1) % AppConstants.QUEUE.size
        posSec = 0.0
        lastTickMs = SystemClock.elapsedRealtime()
        emit()
    }

    fun prev() {
        if (posSec > 3.0) {
            seekRatio(0f)
            return
        }
        customTrack = null
        releasePlayer()
        queueIndex = (queueIndex - 1 + AppConstants.QUEUE.size) % AppConstants.QUEUE.size
        posSec = 0.0
        lastTickMs = SystemClock.elapsedRealtime()
        emit()
    }

    fun seekRatio(ratio: Float) {
        val target = (ratio.coerceIn(0f, 1f) * currentDuration())
        posSec = target
        if (player != null && customTrack != null) {
            try {
                player?.seekTo((target * 1000.0).toInt())
            } catch (_: Throwable) {}
        }
        emit()
    }

    fun loadLocalAudio(uri: Uri) {
        releasePlayer()
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
                embeddedArt = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            mmr.release()
        } catch (_: Throwable) {}

        val mp = MediaPlayer()
        try {
            mp.setDataSource(context, uri)
            mp.prepare()
            durSec = max(1, mp.duration / 1000)
            mp.setOnCompletionListener {
                playing = false
                posSec = 0.0
                updateTicker()
                emit()
            }
            player = mp
        } catch (_: Throwable) {
            mp.release()
        }

        if (embeddedArt != null) customArt = embeddedArt
        customTrack = TrackInfo(title, artist, durSec, 0xFF38BDF8.toInt(), 0xFF6366F1.toInt(), 0xFF0F172A.toInt())
        posSec = 0.0
        playing = true
        try { player?.start() } catch (_: Throwable) {}
        lastTickMs = SystemClock.elapsedRealtime()
        updateTicker()
        emit()
    }

    fun setCustomArt(bmp: Bitmap) {
        customArt = bmp
        emit()
    }

    fun emit() {
        onStateChanged(currentTrack(), customArt, playing, posSec, currentDuration())
    }

    private fun updateTicker() {
        handler.removeCallbacks(ticker)
        if (playing && screenVisible && !sheetOpen) {
            lastTickMs = SystemClock.elapsedRealtime()
            handler.postDelayed(ticker, 140L)
        }
    }

    private fun releasePlayer() {
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
    }
}
