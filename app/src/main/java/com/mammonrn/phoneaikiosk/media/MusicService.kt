package com.mammonrn.phoneaikiosk.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.voice.WakePause
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the screens, the home card and the voice commands see of the music
 * (0.53.0): the queue, the state, and commands that reach [MusicService].
 * Main thread only for the fields; the commands post themselves there.
 */
object MusicPlayer {

    enum class State { STOPPED, PLAYING, PAUSED }

    val queue = PlayQueue()
    @Volatile var state = State.STOPPED
        internal set
    /** Why the last track could not play, in words for the screen; null when fine. */
    @Volatile var error: String? = null
        internal set
    /** The player's own volume, 0-1: the music only, never Jarvis's voice. */
    @Volatile var volume = 0.8f
        private set

    internal var service: MusicService? = null
    private val pending = ArrayList<(MusicService) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Something on it changed: every screen that shows it redraws. */
    internal fun changed() = main.post { for (l in listeners) l() }

    /** Music is loaded and not stopped: the home card shows. */
    val hasMedia: Boolean get() = state != State.STOPPED && queue.current != null

    val positionMs: Long get() = service?.player?.currentPosition ?: 0
    val durationMs: Long get() = service?.player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0

    /** Runs [block] on the main thread with the service, starting it if it is not running. */
    private fun run(context: Context, block: (MusicService) -> Unit) {
        main.post {
            val s = service
            if (s != null) block(s)
            else {
                pending.add(block)
                ContextCompat.startForegroundService(context.applicationContext, Intent(context, MusicService::class.java))
            }
        }
    }

    internal fun attach(s: MusicService) {
        service = s
        val waiting = ArrayList(pending)
        pending.clear()
        for (block in waiting) block(s)
    }

    internal fun detach(s: MusicService) {
        if (service === s) service = null
    }

    fun play(context: Context, tracks: List<Track>, start: Int = 0) = run(context) {
        queue.set(tracks, start)
        it.load(queue.current, play = true)
    }

    fun jumpTo(context: Context, index: Int) = run(context) { s -> queue.jumpTo(index)?.let { s.load(it, true) } }

    /** Plays on from where it is, or starts the queue again after a stop. */
    fun resume(context: Context) = run(context) { s -> if (s.loaded) s.player.play() else s.load(queue.current, true) }

    fun pause(context: Context) = run(context) { it.player.pause() }

    fun toggle(context: Context) = if (state == State.PLAYING) pause(context) else resume(context)

    fun next(context: Context) = run(context) { s -> queue.next(auto = false)?.let { s.load(it, true) } }

    fun previous(context: Context) = run(context) { s ->
        // Winamp's rule: a few seconds in, "previous" starts this song again.
        if (s.player.currentPosition > 3_000) s.player.seekTo(0)
        else queue.previous()?.let { s.load(it, true) }
    }

    fun stop(context: Context) = run(context) { it.stopAll() }

    fun seekTo(context: Context, ms: Long) = run(context) { it.player.seekTo(ms) }

    fun setVolume(context: Context, value: Float) {
        volume = value.coerceIn(0f, 1f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("volume", volume).apply()
        run(context) { it.player.volume = volume }
        changed()
    }

    fun setShuffle(on: Boolean) { queue.setShuffle(on); changed() }

    fun cycleRepeat() { queue.cycleRepeat(); changed() }

    internal fun loadVolume(context: Context) {
        volume = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("volume", 0.8f)
    }

    private const val PREFS = "music"
}

/**
 * The music player itself (0.53.0): ExoPlayer in a foreground service, so the
 * music plays on when its screen is closed — by Back, the X, or Hey Jarvis
 * (DESIGN.md 12). The queue is [MusicPlayer.queue]; one track is loaded at a
 * time and the next is loaded when it ends.
 *
 * JARVIS RESTS WHILE IT PLAYS, through the existing WakePause: a hold while
 * music is meant to be playing (including the moments it buffers between
 * songs), renewed every 30 s, released on pause and stop. Quieted for a
 * question asked with the button, it pauses but KEEPS the hold, as WakePause
 * requires, and plays on when the turn ends.
 *
 * NOTHING ABOUT THE MUSIC IS LOGGED: states and error kinds, never a title or
 * a path.
 */
@OptIn(UnstableApi::class)
class MusicService : Service(), WakePause.Media {

    lateinit var player: ExoPlayer
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var hold: WakePause.Hold? = null
    private var quieted = false
    /** Errors in a row: a list of files that all fail stops, rather than spinning. */
    private var failures = 0

    /** A track is in the player (not stopped). */
    val loaded: Boolean get() = player.currentMediaItem != null && player.playbackState != Player.STATE_IDLE

    private val renew = object : Runnable {
        override fun run() {
            hold?.let { if (!WakePause.renew(it)) hold = null }
            if (hold != null) handler.postDelayed(this, WakePause.RENEW_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        MusicPlayer.loadVolume(this)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(MediaSources.factory(this)))
            // Audio focus handled by ExoPlayer: another app's sound pauses it.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.volume = MusicPlayer.volume
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = update()

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) failures = 0
                if (state == Player.STATE_ENDED) {
                    val next = MusicPlayer.queue.next(auto = true)
                    if (next != null) load(next, true) else stopAll()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "track failed: ${error.errorCodeName}")
                MusicPlayer.error = getString(R.string.music_error_track, MusicPlayer.queue.current?.title.orEmpty())
                failures += 1
                val next = if (failures < MusicPlayer.queue.tracks.size) MusicPlayer.queue.next(auto = false) else null
                if (next != null) load(next, true) else stopAll()
            }
        })
        startInForeground()
        MusicPlayer.attach(this)
        Log.i(TAG, "music service up")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseHold()
        handler.removeCallbacksAndMessages(null)
        MusicPlayer.detach(this)
        player.release()
        if (MusicPlayer.state != MusicPlayer.State.STOPPED) {
            MusicPlayer.state = MusicPlayer.State.STOPPED
            MusicPlayer.changed()
        }
        Log.i(TAG, "music service down")
        super.onDestroy()
    }

    fun load(track: Track?, play: Boolean) {
        if (track == null) return stopAll()
        MusicPlayer.error = null
        player.setMediaItem(MediaItem.fromUri(MediaSources.uriOf(track)))
        player.prepare()
        player.playWhenReady = play
        startInForeground()
        update()
    }

    /** Stop, forget the loaded track (the queue stays), and let the wake word back. */
    fun stopAll() {
        player.stop()
        player.clearMediaItems()
        quieted = false
        releaseHold()
        MusicPlayer.state = MusicPlayer.State.STOPPED
        MusicPlayer.changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "stopped")
    }

    /** The state, the hold and the notification, from the player as it is now. */
    private fun update() {
        val meant = player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        val newState = when {
            meant || quieted -> MusicPlayer.State.PLAYING
            player.currentMediaItem != null && player.playbackState != Player.STATE_IDLE -> MusicPlayer.State.PAUSED
            else -> MusicPlayer.state.takeIf { it == MusicPlayer.State.STOPPED } ?: MusicPlayer.State.PAUSED
        }
        if (meant || quieted) takeHold() else releaseHold()
        if (newState != MusicPlayer.state) {
            MusicPlayer.state = newState
            Log.i(TAG, "state ${newState.name.lowercase()}")
        }
        MusicPlayer.changed()
        refreshNotification()
    }

    private fun takeHold() {
        if (hold != null) return
        hold = WakePause.hold(WakePause.Source.MUSIC, this)
        handler.postDelayed(renew, WakePause.RENEW_MS)
    }

    private fun releaseHold() {
        hold?.let { WakePause.release(it) }
        hold = null
        handler.removeCallbacks(renew)
    }

    // ------------------------------------------------------------ Jarvis

    override fun quietForJarvis() {
        if (!player.playWhenReady) return
        quieted = true
        player.pause()
    }

    override fun resumeAfterJarvis() {
        if (!quieted) return
        quieted = false
        player.play()
    }

    // ------------------------------------------------------------ notification

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.window_music),
                                                                  NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MusicActivity::class.java),
                                             PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pixel_music)
            .setContentTitle(MusicPlayer.queue.current?.title ?: getString(R.string.window_music))
            .setContentText(getString(if (MusicPlayer.state == MusicPlayer.State.PAUSED) R.string.music_paused
                                      else R.string.music_playing))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun refreshNotification() {
        if (MusicPlayer.state == MusicPlayer.State.STOPPED) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    companion object {
        /** `logcat -s KioskMusic:I` — states and error kinds, never titles or paths. */
        const val TAG = "KioskMusic"
        private const val CHANNEL = "music"
        private const val NOTIFICATION_ID = 53
    }
}
