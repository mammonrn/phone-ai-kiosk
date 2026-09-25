package com.mammonrn.phoneaikiosk.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.media.HeatLadder
import com.mammonrn.phoneaikiosk.media.HeatWatch
import com.mammonrn.phoneaikiosk.media.MusicPlayer
import com.mammonrn.phoneaikiosk.media.VideoPlayer
import com.mammonrn.phoneaikiosk.voice.WakePause
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * What the radio's screen sees (0.61.0): which station, its state in words,
 * and play/stop. Main thread for the fields; commands post themselves there.
 */
object RadioPlayer {

    enum class State { STOPPED, CONNECTING, PLAYING, FAILED }

    @Volatile var state = State.STOPPED
        internal set
    /** The station playing, connecting, or last failed; null when none. */
    @Volatile var stationId: String? = null
        internal set
    /** Its name as it was when it was started (the list may be edited meanwhile). */
    @Volatile var stationName: String? = null
        internal set
    /** Why it could not play or stopped by itself, in words for the screen; null when fine. */
    @Volatile var error: String? = null
        internal set
    /** The song the station says is on (ICY "StreamTitle"), when it says; never logged. */
    @Volatile var nowTitle: String? = null
        internal set

    internal var service: RadioService? = null
    private val pending = ArrayList<(RadioService) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    internal fun changed() = main.post { for (l in listeners) l() }

    /** Connecting or playing: what "play/stop" stops. */
    val active: Boolean get() = state == State.CONNECTING || state == State.PLAYING

    private fun run(context: Context, block: (RadioService) -> Unit) {
        main.post {
            // A service that has just stopped itself is on its way out: a new one is started.
            val s = service?.takeUnless { it.stopping }
            if (s != null) block(s)
            else {
                pending.add(block)
                ContextCompat.startForegroundService(context.applicationContext, Intent(context, RadioService::class.java))
            }
        }
    }

    internal fun attach(s: RadioService) {
        service = s
        val waiting = ArrayList(pending)
        pending.clear()
        for (b in waiting) b(s)
    }

    internal fun detach(s: RadioService) { if (service === s) service = null }

    /** Plays [station]. The music and a video pause first: one sound at a time. */
    fun play(context: Context, station: Station) {
        if (HeatWatch.step.pause) {
            stationId = station.id; stationName = station.name
            error = context.getString(R.string.video_heat_pause)
            state = State.FAILED
            changed()
            return
        }
        MusicPlayer.quietForVideo(context)
        VideoPlayer.quietForMusic(context)
        run(context) { it.start(station) }
    }

    fun stop(context: Context) {
        if ((service == null || service?.stopping == true) && pending.isEmpty()) {
            // Nothing running: a failure's words are cleared, nothing is started.
            if (state == State.FAILED) { state = State.STOPPED; error = null; changed() }
            return
        }
        run(context) { it.stopAll(null) }
    }

    /** The station's row was tapped: the same station stops, another one plays. */
    fun toggle(context: Context, station: Station) =
        if (active && stationId == station.id) stop(context) else play(context, station)

    /**
     * Music or a video is starting (they call this, as they pause each other):
     * the radio stops — a live station has no "pause" to come back to.
     */
    fun quietForMedia(context: Context) { if (active || pending.isNotEmpty()) stop(context) }
}

/**
 * The radio itself (0.61.0): ExoPlayer in a foreground service, so it plays on
 * when its screen closes, as the music does (DESIGN.md 12, 5ฏ).
 *
 * JARVIS RESTS WHILE IT PLAYS, through WakePause exactly as MusicService does:
 * a hold while it is meant to play (connecting included), renewed every 30 s,
 * released on stop and on failure. A question asked with the Jarvis button
 * does not stop the radio: it is LOWERED to [DUCK] of its volume and put back
 * when the turn ends (Poom: "จาร์วิสพูดทับแล้ววิทยุลดเสียง").
 *
 * NEVER HANGS, NEVER STORMS: a station that has not started within
 * [CONNECT_MS], or stalls for [STALL_MS], stops with the reason in words. The
 * player retries a dropped load at most twice (its own short back-off); after
 * that the radio stops and waits for a person to press play. No loop.
 *
 * NOTHING ABOUT THE STATION IS LOGGED: `logcat -s KioskRadio:I` has the
 * station's number on the list, states and error kinds — never a name, an
 * address or a song.
 */
@OptIn(UnstableApi::class)
class RadioService : Service(), WakePause.Media {

    private lateinit var player: ExoPlayer
    /** stopSelf was called: commands go to the next service, not this one. */
    internal var stopping = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val resolver = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-radio") }
    private var hold: WakePause.Hold? = null
    private var ducked = false
    /** Each start is a new generation: a late answer for an old one is dropped. */
    private var generation = 0
    private var startedAt = 0L
    private var stalledSince = 0L
    private var reprepared = false

    private val renew = object : Runnable {
        override fun run() {
            hold?.let { if (!WakePause.renew(it)) hold = null }
            if (hold != null) handler.postDelayed(this, WakePause.RENEW_MS)
        }
    }

    /** Every second while it is meant to play: a start that never comes, or a stall that never ends. */
    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            when {
                RadioPlayer.state == RadioPlayer.State.CONNECTING && now - startedAt > CONNECT_MS ->
                    return fail("timeout", getString(R.string.radio_err_timeout, CONNECT_MS / 1000))
                RadioPlayer.state == RadioPlayer.State.PLAYING && stalledSince > 0 && now - stalledSince > STALL_MS ->
                    return fail("stalled", getString(R.string.radio_err_stalled))
            }
            if (RadioPlayer.active) handler.postDelayed(this, 1_000)
        }
    }

    private val heat: (HeatLadder.Step) -> Unit = { step ->
        if ((step.stop || step.pause) && RadioPlayer.active) stopAll(getString(R.string.video_heat_pause))
    }

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(RadioBrowser.USER_AGENT)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            // https to http is refused by Android here anyway; said as its own reason (DESIGN.md 5ฏ).
            .setAllowCrossProtocolRedirects(false)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
                                       .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_RETRIES)))
            // Audio focus handled by ExoPlayer: another app's sound, a call, headphones out.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = update()

            override fun onIsPlayingChanged(isPlaying: Boolean) = update()

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady || !RadioPlayer.active) return
                // A live station has nothing to come back to: stopped, and said why.
                when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
                        stopAll(getString(R.string.radio_stopped_focus))
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ->
                        stopAll(getString(R.string.radio_stopped_noisy))
                }
            }

            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is IcyInfo) {
                        val title = entry.title?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
                        if (title != RadioPlayer.nowTitle) {
                            RadioPlayer.nowTitle = title
                            RadioPlayer.changed()
                            refreshNotification()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // HLS that fell behind its live window: back to the live edge, once per start.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && !reprepared) {
                    reprepared = true
                    Log.i(TAG, "behind live window: to the live edge")
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                fail(error.errorCodeName.lowercase(), reason(error))
            }
        })
        HeatWatch.start(this)
        HeatWatch.listeners.add(heat)
        startInForeground()
        RadioPlayer.attach(this)
        Log.i(TAG, "radio service up")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent?.action == ACTION_STOP) stopAll(null)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        HeatWatch.listeners.remove(heat)
        releaseHold()
        handler.removeCallbacksAndMessages(null)
        resolver.shutdownNow()
        RadioPlayer.detach(this)
        player.release()
        if (RadioPlayer.active) {
            RadioPlayer.state = RadioPlayer.State.STOPPED
            RadioPlayer.changed()
        }
        Log.i(TAG, "radio service down")
        super.onDestroy()
    }

    // ------------------------------------------------------------ play, stop, fail

    fun start(station: Station) {
        generation += 1
        val gen = generation
        player.stop()
        player.clearMediaItems()
        reprepared = false
        stalledSince = 0
        startedAt = SystemClock.elapsedRealtime()
        RadioPlayer.stationId = station.id
        RadioPlayer.stationName = station.name
        RadioPlayer.error = null
        RadioPlayer.nowTitle = null
        RadioPlayer.state = RadioPlayer.State.CONNECTING
        Log.i(TAG, "play station #${RadioStore.book(this).number(station.id)} kind=${StreamKind.of(station.url).name.lowercase()}")
        takeHold()
        applyVolume()
        startInForeground()
        RadioPlayer.changed()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 1_000)
        if (StreamKind.of(station.url) == StreamKind.Kind.PLAYLIST) {
            // A .pls/.m3u is a text file naming the stream: read it once, off the main thread.
            resolver.execute {
                val inner = runCatching { readPlaylist(station.url) }.getOrNull()
                handler.post {
                    if (gen != generation || !RadioPlayer.active) return@post
                    if (inner == null || StreamKind.of(inner) == StreamKind.Kind.PLAYLIST) {
                        fail("playlist", getString(R.string.radio_err_playlist))
                    } else open(inner)
                }
            }
        } else open(station.url)
    }

    private fun open(url: String) {
        val item = MediaItem.Builder().setUri(url)
        if (StreamKind.of(url) == StreamKind.Kind.HLS) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(item.build())
        player.prepare()
        player.playWhenReady = true
    }

    /** Stopped by a person (no reason) or by the phone ([why] said on screen). */
    fun stopAll(why: String?) {
        generation += 1
        handler.removeCallbacks(watchdog)
        player.stop()
        player.clearMediaItems()
        ducked = false
        releaseHold()
        RadioPlayer.error = why
        RadioPlayer.nowTitle = null
        RadioPlayer.state = if (why != null) RadioPlayer.State.FAILED else RadioPlayer.State.STOPPED
        RadioPlayer.changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopping = true
        stopSelf()
        Log.i(TAG, if (why != null) "stopped by the phone" else "stopped")
    }

    private fun fail(kind: String, why: String) {
        Log.w(TAG, "station failed: $kind")
        stopAll(why)
    }

    /** The state from the player as it is now; the hold follows it. */
    private fun update() {
        if (!RadioPlayer.active) return
        val now = SystemClock.elapsedRealtime()
        val next = when (player.playbackState) {
            Player.STATE_READY -> if (player.playWhenReady) RadioPlayer.State.PLAYING else RadioPlayer.state
            Player.STATE_ENDED -> {
                // A live stream that "ends" has dropped: said, not restarted.
                fail("ended", getString(R.string.radio_err_ended)); return
            }
            else -> RadioPlayer.state
        }
        stalledSince = if (next == RadioPlayer.State.PLAYING && player.playbackState == Player.STATE_BUFFERING)
            (stalledSince.takeIf { it > 0 } ?: now) else 0
        if (next != RadioPlayer.state) {
            RadioPlayer.state = next
            Log.i(TAG, "state ${next.name.lowercase()}")
            RadioPlayer.changed()
            refreshNotification()
        }
    }

    /** The reason in words, from what the player says went wrong. */
    private fun reason(error: PlaybackException): String {
        val http = generateSequence(error.cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        return when {
            http != null -> getString(R.string.radio_err_http, http.responseCode)
            error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> getString(R.string.radio_err_plain)
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                getString(if (online()) R.string.radio_err_connect else R.string.radio_err_offline)
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                getString(R.string.radio_err_timeout, CONNECT_TIMEOUT_MS / 1000)
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> getString(R.string.radio_err_format)
            else -> getString(R.string.radio_err_generic)
        }
    }

    private fun online(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** A .pls/.m3u, read once with the same timeouts as the stream; at most 64 KB. */
    private fun readPlaylist(url: String): String? {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", RadioBrowser.USER_AGENT)
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                while (out.size() < 64 * 1024) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            }
            return StreamKind.firstInPlaylist(String(bytes, Charsets.UTF_8))
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------ the wake word, and Jarvis over the radio

    private fun takeHold() {
        if (hold != null) return
        hold = WakePause.hold(WakePause.Source.RADIO, this)
        handler.postDelayed(renew, WakePause.RENEW_MS)
    }

    private fun releaseHold() {
        hold?.let { WakePause.release(it) }
        hold = null
        handler.removeCallbacks(renew)
    }

    private fun applyVolume() { player.volume = if (ducked) DUCK else 1f }

    /** A question is starting: lowered, not stopped; the hold stays, as WakePause requires. */
    override fun quietForJarvis() {
        if (!RadioPlayer.active) return
        ducked = true
        applyVolume()
        Log.i(TAG, "ducked for jarvis volume=${player.volume}")
    }

    override fun resumeAfterJarvis() {
        if (!ducked) return
        ducked = false
        applyVolume()
        Log.i(TAG, "volume back after jarvis volume=${player.volume}")
    }

    // ------------------------------------------------------------ notification

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.window_radio),
                                                                  NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, RadioActivity::class.java),
                                             PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP),
                                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val state = getString(if (RadioPlayer.state == RadioPlayer.State.PLAYING) R.string.radio_state_playing
                              else R.string.radio_state_connecting)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pixel_radio_light)
            .setContentTitle(RadioPlayer.stationName ?: getString(R.string.window_radio))
            .setContentText(RadioPlayer.nowTitle?.let { "$state · $it" } ?: state)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_pixel_stop),
                                                   getString(R.string.radio_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun refreshNotification() {
        if (!RadioPlayer.active) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    companion object {
        /** `logcat -s KioskRadio:I` — the station's number, states and error kinds; never a name or an address. */
        const val TAG = "KioskRadio"
        private const val CHANNEL = "radio"
        private const val NOTIFICATION_ID = 61
        private const val ACTION_STOP = "com.mammonrn.phoneaikiosk.radio.STOP"
        /** Jarvis talking over the radio: its volume while a question is asked and answered. */
        const val DUCK = 0.15f
        /** Pressed play and nothing to hear by then: said, and stopped. */
        const val CONNECT_MS = 20_000L
        /** Playing, then buffering this long: the station dropped. */
        const val STALL_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        /** A dropped load is tried again at most this many times by the player, then it is an error. */
        private const val LOAD_RETRIES = 2
    }
}
