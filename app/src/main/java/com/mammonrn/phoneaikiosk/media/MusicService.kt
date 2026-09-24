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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.media.fx.AudioFx
import com.mammonrn.phoneaikiosk.media.fx.Eq
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
        VideoPlayer.quietForMusic(context)
        queue.set(tracks, start)
        resumeAtMs = 0
        it.load(queue.current, play = true)
    }

    fun jumpTo(context: Context, index: Int) = run(context) { s -> resumeAtMs = 0; queue.jumpTo(index)?.let { s.load(it, true) } }

    /** Plays on from where it is, or starts the queue again after a stop. */
    fun resume(context: Context) = run(context) { s ->
        if (HeatWatch.step.pause) { error = context.getString(R.string.video_heat_pause); changed(); return@run }
        VideoPlayer.quietForMusic(context)
        if (s.loaded) s.player.play() else s.load(queue.current, true)
    }

    /** A video starting: playing music pauses (one sound at a time); stopped music is not woken. */
    fun quietForVideo(context: Context) { if (state == State.PLAYING) pause(context) }

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

    fun setShuffle(context: Context, on: Boolean) { queue.setShuffle(on); saved(context); changed() }

    fun cycleRepeat(context: Context) { queue.cycleRepeat(); saved(context); changed() }

    internal fun loadVolume(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        volume = prefs.getFloat("volume", 0.8f)
        fx.balance = prefs.getFloat("balance", 0f)
        fx.settings = readEq(prefs)
    }

    // ------------------------------------------------------------ 0.55.0: the equalizer and the balance

    /** In the player's audio path (MusicService's sink): the equalizer, the balance and the bars' samples. */
    @androidx.annotation.OptIn(UnstableApi::class)
    val fx = AudioFx()

    val eq: Eq.Settings get() = fx.settings
    val balance: Float get() = fx.balance

    fun setEq(context: Context, settings: Eq.Settings) {
        fx.settings = settings
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("eq_on", settings.on).putFloat("eq_pre", settings.preampDb)
            .putString("eq_gains", settings.gainsDb.joinToString(",")).putString("eq_preset", settings.preset).apply()
        changed()
    }

    /** −1 left … +1 right; near the centre it snaps to the centre. */
    fun setBalance(context: Context, value: Float) {
        fx.balance = if (kotlin.math.abs(value) < 0.06f) 0f else value.coerceIn(-1f, 1f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("balance", fx.balance).apply()
        changed()
    }

    private fun readEq(prefs: android.content.SharedPreferences): Eq.Settings {
        val gains = prefs.getString("eq_gains", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == Eq.BANDS.size } ?: return Eq.Settings()
        return Eq.Settings(prefs.getBoolean("eq_on", true), prefs.getFloat("eq_pre", 0f), gains.map(Eq::clamp),
                           prefs.getString("eq_preset", "").orEmpty())
    }

    // ------------------------------------------------------------ 0.55.0: what the file is

    /** What the file says it is: kbps, kHz, channels — null for each the file does not say. */
    data class FileInfo(val kbps: Int?, val khz: Int?, val channels: Int?)

    fun fileInfo(): FileInfo {
        val p = service?.player ?: return FileInfo(null, null, null)
        val f = p.audioFormat ?: return FileInfo(null, null, null)
        var bitrate = f.bitrate.takeIf { it > 0 }
        // FLAC, WAV and ALAC seldom carry a bitrate: the average is the file's size over its length.
        val track = queue.current
        if (bitrate == null && track != null && !track.onNas && durationMs > 0) {
            val bytes = java.io.File(track.path).length()
            if (bytes > 0) bitrate = (bytes * 8_000 / durationMs).toInt()
        }
        return FileInfo(bitrate?.let { (it + 500) / 1000 }, f.sampleRate.takeIf { it > 0 }?.let { (it + 500) / 1000 },
                        f.channelCount.takeIf { it > 0 })
    }

    /** The song's own tags, read from the file by the player: title, artist, album, cover. */
    val tags: androidx.media3.common.MediaMetadata? get() = service?.player?.mediaMetadata

    // ------------------------------------------------------------ 0.55.0: editing the list

    fun add(context: Context, tracks: List<Track>) {
        val wasEmpty = queue.isEmpty
        queue.add(tracks)
        if (wasEmpty) run(context) { it.load(queue.current, play = false) }
        saved(context); changed()
    }

    fun remove(context: Context, indices: Set<Int>) {
        val playing = state == State.PLAYING
        val removedCurrent = queue.remove(indices)
        if (queue.isEmpty) stop(context)
        else if (removedCurrent) run(context) { it.load(queue.current, play = playing) }
        saved(context); changed()
    }

    fun clear(context: Context) {
        queue.clear()
        stop(context)
        saved(context); changed()
    }

    fun sort(context: Context, by: Comparator<Track>) {
        queue.sort(by)
        saved(context); changed()
    }

    // ------------------------------------------------------------ 0.55.0: back where it was

    private var restored = false
    /** Where the saved song was, used once when it is played again. */
    internal var resumeAtMs = 0L
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-session") }

    private fun sessionFile(context: Context) = java.io.File(context.filesDir, "music_session.txt")

    /** The list and the place in it, as last saved — once per run, and only into an empty list. */
    fun restore(context: Context) {
        if (restored) return
        restored = true
        if (!queue.isEmpty) return
        val s = runCatching { Session.decode(sessionFile(context).readText()) }.getOrNull() ?: return
        queue.restore(s.tracks, s.index, s.shuffle, s.repeat)
        resumeAtMs = s.positionMs
        changed()
    }

    /** Saves the list and the place in it (off the main thread; the song's name never goes to a log). */
    internal fun saved(context: Context) {
        val app = context.applicationContext
        val s = Session(queue.tracks, queue.currentIndex, if (hasMedia) positionMs else resumeAtMs,
                        queue.shuffle, queue.repeat)
        writer.execute {
            runCatching {
                val file = sessionFile(app)
                val tmp = java.io.File(file.path + ".tmp")
                tmp.writeText(s.encode())
                tmp.renameTo(file)
            }
        }
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

    /** Every few seconds while it plays, the place is saved: an unplugged phone comes back near it. */
    private val saver = object : Runnable {
        override fun run() {
            if (MusicPlayer.state == MusicPlayer.State.PLAYING) MusicPlayer.saved(this@MusicService)
            handler.postDelayed(this, SAVE_MS)
        }
    }

    /** The heat ladder's top steps, for the music: pause, then stop (the bars stop by themselves). */
    private val heat: (HeatLadder.Step) -> Unit = { step ->
        if (step.stop) stopAll()
        else if (step.pause && player.playWhenReady) { player.pause(); MusicPlayer.error = getString(R.string.video_heat_pause) }
    }

    /** A NAS song's length is known once it plays: the list shows it from then on. */
    private fun learnDuration() {
        val ms = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val i = MusicPlayer.queue.currentIndex
        val t = MusicPlayer.queue.current ?: return
        if (t.durationMs == 0L) MusicPlayer.queue.replace(i, t.copy(durationMs = ms))
    }

    private val renew = object : Runnable {
        override fun run() {
            hold?.let { if (!WakePause.renew(it)) hold = null }
            if (hold != null) handler.postDelayed(this, WakePause.RENEW_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        MusicPlayer.loadVolume(this)
        MusicPlayer.restore(this)
        // 0.55.0: our processor in the audio path — the equalizer, the balance and
        // the bars' samples. 16-bit output (no float), the format it takes.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                        enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(MusicPlayer.fx))
                    .build()
        }
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(MediaSources.factory(this)))
            // Audio focus handled by ExoPlayer: another app's sound pauses it.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.volume = MusicPlayer.volume
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = update()

            // A song whose sound the phone cannot decode — ALAC on the A07, which has
            // no ALAC decoder — would otherwise "play" in silence with the clock
            // running. Said in words, and the next song plays.
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isEmpty() || audio.any { g -> (0 until g.length).any { g.isTrackSupported(it) } }) return
                val mime = audio.first().getTrackFormat(0).sampleMimeType.orEmpty()
                val kind = mime.substringAfter('/').uppercase().ifEmpty { "?" }
                Log.w(TAG, "no decoder for $mime")
                MusicPlayer.error = getString(R.string.music_no_decoder, MusicPlayer.queue.current?.title.orEmpty(), kind)
                failures += 1
                val next = if (failures < MusicPlayer.queue.tracks.size) MusicPlayer.queue.next(auto = false) else null
                handler.post { if (next != null) load(next, true) else stopAll() }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    failures = 0
                    learnDuration()
                }
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
        HeatWatch.start(this)
        HeatWatch.listeners.add(heat)
        startInForeground()
        handler.postDelayed(saver, SAVE_MS)
        MusicPlayer.attach(this)
        Log.i(TAG, "music service up")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        HeatWatch.listeners.remove(heat)
        MusicPlayer.saved(this)
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
        // Back where it was left (0.55.0): the saved place, once, for the saved song.
        val at = MusicPlayer.resumeAtMs
        MusicPlayer.resumeAtMs = 0
        player.setMediaItem(MediaItem.fromUri(MediaSources.uriOf(track)), at)
        player.prepare()
        player.playWhenReady = play
        startInForeground()
        update()
    }

    /** Stop, forget the loaded track (the queue stays), and let the wake word back. */
    fun stopAll() {
        // Stopped is stopped: next time the song starts from its beginning (the list stays).
        MusicPlayer.resumeAtMs = 0
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
            if (MusicPlayer.state == MusicPlayer.State.PLAYING) MusicPlayer.saved(this)
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
            .setSmallIcon(R.drawable.ic_pixel_music_light)
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
        private const val SAVE_MS = 5_000L
    }
}
