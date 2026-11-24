package com.primeone.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import java.util.Base64

class PlaybackService : Service() {
    private lateinit var session: MediaSessionCompat
    private var title = "PRIME ONE"
    private var artist = "Media player"
    private var album = "PRIME ONE"
    private var art: Bitmap? = null
    private var duration = 0L
    private var position = 0L
    private var rate = 1f
    private var playing = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "PRIME_ONE_SESSION").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { MainActivity.sendAction("play"); setPlaying(true) }
                override fun onPause() { MainActivity.sendAction("pause"); setPlaying(false) }
                override fun onSkipToNext() { MainActivity.sendAction("nexttrack") }
                override fun onSkipToPrevious() { MainActivity.sendAction("previoustrack") }
                override fun onSeekTo(pos: Long) { position = pos; MainActivity.sendAction("seekto", pos); updateState(); updateNotification() }
                override fun onFastForward() { MainActivity.sendAction("seekforward") }
                override fun onRewind() { MainActivity.sendAction("seekbackward") }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_META -> {
                title = intent.getStringExtra("title") ?: title
                artist = intent.getStringExtra("artist") ?: artist
                album = intent.getStringExtra("album") ?: album
                art = decodeArtwork(intent.getStringExtra("artwork")) ?: art ?: loadBundledCover()
                session.setMetadata(MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                    .apply { art?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) } }
                    .build())
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_STATE -> {
                playing = intent.getStringExtra("state") == "playing"
                updateState(); updateNotification()
                if (playing) startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_POS -> {
                duration = (intent.getDoubleExtra("duration", 0.0) * 1000).toLong()
                position = (intent.getDoubleExtra("position", 0.0) * 1000).toLong()
                rate = intent.getDoubleExtra("rate", 1.0).toFloat()
                updateState()
            }
            ACTION_PREV -> MainActivity.sendAction("previoustrack")
            ACTION_NEXT -> MainActivity.sendAction("nexttrack")
            ACTION_PLAY -> { MainActivity.sendAction("play"); setPlaying(true) }
            ACTION_PAUSE -> { MainActivity.sendAction("pause"); setPlaying(false) }
            ACTION_INIT -> { /* wait until a track starts */ }
            ACTION_DISMISS -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_STICKY
    }

    private fun setPlaying(value: Boolean) { playing = value; updateState(); updateNotification() }

    private fun updateState() {
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, position, if (playing) rate else 0f)
            .build())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val prev = actionIntent(ACTION_PREV, 2)
        val toggle = actionIntent(if (playing) ACTION_PAUSE else ACTION_PLAY, 3)
        val next = actionIntent(ACTION_NEXT, 4)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.primeone.player.R.drawable.ic_stat_music)
            .setContentTitle(title).setContentText(artist).setSubText(album)
            .setLargeIcon(art).setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setOngoing(playing).setShowWhen(false)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prev)
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pause" else "Play", toggle)
            .addAction(android.R.drawable.ic_media_next, "Next", next)
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        return builder.build()
    }

    private fun actionIntent(action: String, request: Int) = PendingIntent.getService(this, request,
        Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun updateNotification() {
        if (!::session.isInitialized) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun loadBundledCover(): Bitmap? = try { assets.open("heman_cover.jpg").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }

    private fun decodeArtwork(data: String?): Bitmap? = try {
        if (data.isNullOrBlank()) null else {
            val raw = data.substringAfter(",", data)
            val bytes = if (Build.VERSION.SDK_INT >= 26) Base64.getDecoder().decode(raw) else android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (_: Exception) { null }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Playback controls shown when PRIME ONE plays music"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { if (!playing) stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { if (::session.isInitialized) { session.isActive = false; session.release() }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "prime_one_playback"
        const val NOTIFICATION_ID = 6101
        const val ACTION_INIT = "prime.init"
        const val ACTION_META = "prime.meta"
        const val ACTION_STATE = "prime.state"
        const val ACTION_POS = "prime.position"
        const val ACTION_PREV = "prime.previous"
        const val ACTION_NEXT = "prime.next"
        const val ACTION_PLAY = "prime.play"
        const val ACTION_PAUSE = "prime.pause"
        const val ACTION_DISMISS = "prime.dismiss"
    }
}
