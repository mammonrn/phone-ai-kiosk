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

    val positionMs: Long get() = service?.positionMs() ?: 0
    val durationMs: Long get() = service?.durationMs() ?: 0

    /** 0.60.0: this song plays through LibVLC (PlayerChoice): VLC's equalizer, and no bars. */
    val usingVlc: Boolean get() = service?.usingVlc == true

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

    /**
     * The playlist the list is (0.59.0), or null for a list made by voice: the
     * list's edits are kept in it (add, remove, sort, clear).
     */
    var playlistId: String? = null
        private set

    /** The playlist was deleted: the list plays on, as a list of its own. */
    fun forgetPlaylist() { playlistId = null; changed() }

    /** Plays [tracks] from [start]; [playlist] when they are a playlist's, whose edits are then kept. */
    fun play(context: Context, tracks: List<Track>, start: Int = 0, playlist: String? = null) = run(context) {
        VideoPlayer.quietForMusic(context)
        // Something else asked for: a file playing on its own is let go of, not put back.
        before = null
        playlistId = playlist
        queue.set(tracks, start)
        resumeAtMs = 0
        it.load(queue.current, play = true)
    }

    /** A playlist, from [start]; it becomes the one the player shows. */
    fun playPlaylist(context: Context, list: Playlist, start: Int = 0) {
        PlaylistStore.edit(context) { it.setCurrent(Playlist.Kind.MUSIC, list.id) }
        play(context, list.items, start, list.id)
    }

    fun jumpTo(context: Context, index: Int) = run(context) { s -> resumeAtMs = 0; queue.jumpTo(index)?.let { s.load(it, true) } }

    /** Plays on from where it is, or starts the queue again after a stop. */
    fun resume(context: Context) = run(context) { s ->
        if (HeatWatch.step.pause) { error = context.getString(R.string.video_heat_pause); changed(); return@run }
        VideoPlayer.quietForMusic(context)
        if (s.loaded) s.playNow() else s.load(queue.current, true)
    }

    /** A video starting: playing music pauses (one sound at a time); stopped music is not woken. */
    fun quietForVideo(context: Context) { if (state == State.PLAYING) pause(context) }

    /** Paused on purpose (a button, a spoken "หยุด"): not started again when a question ends. */
    fun pause(context: Context) = run(context) { it.pauseOnPurpose() }

    fun toggle(context: Context) = if (state == State.PLAYING) pause(context) else resume(context)

    fun next(context: Context) = run(context) { s -> queue.next(auto = false)?.let { s.load(it, true) } }

    fun previous(context: Context) = run(context) { s ->
        // Winamp's rule: a few seconds in, "previous" starts this song again.
        if (s.positionMs() > 3_000) s.seek(0)
        else queue.previous()?.let { s.load(it, true) }
    }

    fun stop(context: Context) = run(context) { it.stopAll() }

    fun seekTo(context: Context, ms: Long) = run(context) { it.seek(ms) }

    fun setVolume(context: Context, value: Float) {
        volume = value.coerceIn(0f, 1f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("volume", volume).apply()
        run(context) { it.applyVolume() }
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
        // A song playing through LibVLC takes the same settings on VLC's own equalizer (0.60.0, Poom).
        service?.applyEq()
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
        val svc = service ?: return FileInfo(null, null, null)
        svc.vlcFacts()?.let { (kbps, khz, ch) -> return FileInfo(kbps, khz, ch) }
        val p = svc.player
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
    val tags: androidx.media3.common.MediaMetadata? get() = service?.takeIf { !it.usingVlc }?.player?.mediaMetadata

    /** Title, artist and album as LibVLC read them, for a song playing through it. */
    val vlcTags: Triple<String?, String?, String?>? get() = service?.vlcTags()

    // ------------------------------------------------------------ 0.55.0: editing the list

    fun add(context: Context, tracks: List<Track>) {
        val wasEmpty = queue.isEmpty
        queue.add(tracks)
        if (wasEmpty) run(context) { it.load(queue.current, play = false) }
        kept(context); saved(context); changed()
    }

    fun remove(context: Context, indices: Set<Int>) {
        val playing = state == State.PLAYING
        val removedCurrent = queue.remove(indices)
        // An empty list has nothing to be wrong with (0.59.0: the old message stayed on screen).
        if (queue.isEmpty) { error = null; stop(context) }
        else if (removedCurrent) run(context) { it.load(queue.current, play = playing) }
        kept(context); saved(context); changed()
    }

    fun clear(context: Context) {
        queue.clear()
        error = null
        stop(context)
        kept(context); saved(context); changed()
    }

    fun sort(context: Context, by: Comparator<Track>) {
        queue.sort(by)
        kept(context); saved(context); changed()
    }

    /** The list's edits, kept in its playlist (not while a file plays on its own). */
    private fun kept(context: Context) {
        val id = playlistId ?: return
        if (before != null) return
        val items = queue.tracks
        PlaylistStore.edit(context) { it.setItems(id, items) }
    }

    // ------------------------------------------------------------ 0.59.0: one file on its own

    /** Everything the list was before a file from the file manager played on its own. */
    private class Before(val queue: PlayQueue.Snapshot, val positionMs: Long, val playlistId: String?, val loaded: Boolean)
    private var before: Before? = null

    /** A file from the file manager is playing on its own; the list waits to come back. */
    val single: Boolean get() = before != null

    /**
     * Plays [track] alone (0.59.0, Poom: "เล่นเฉพาะไฟล์นั้น ห้ามเพิ่มเข้า playlist").
     * The list, the song in it, where in that song, shuffle and repeat are
     * kept as they are and come back when this ends ([endSingle]).
     */
    fun playSingle(context: Context, track: Track) = run(context) { s ->
        VideoPlayer.quietForMusic(context)
        if (before == null) {
            before = Before(queue.snapshot(), if (s.loaded) s.positionMs() else resumeAtMs, playlistId, s.loaded)
        }
        playlistId = null
        queue.setShuffle(false)
        queue.set(listOf(track), 0)
        queue.repeat = PlayQueue.Repeat.OFF
        resumeAtMs = 0
        error = null
        s.load(track, play = true)
        Log.i(MusicService.TAG, "one file playing on its own")
    }

    /** The list back as it was: the same song, paused at the same place, the same repeat. */
    fun endSingle(context: Context, keepError: Boolean = false) = run(context) { s -> s.endSingleNow(keepError) }

    internal fun restoreBefore(s: MusicService, keepError: Boolean) {
        val b = before ?: return
        before = null
        queue.restore(b.queue)
        playlistId = b.playlistId
        if (!keepError) error = null
        val track = queue.current
        if (b.loaded && track != null) {
            resumeAtMs = b.positionMs
            s.load(track, play = false, keepError = keepError)
        } else {
            s.stopAll()
            resumeAtMs = b.positionMs
        }
        saved(s)
        changed()
        Log.i(MusicService.TAG, "list back after one file")
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
        // 0.58 kept no playlist: the list is "รายการเดิม", which the first read of the playlists makes from it.
        playlistId = s.playlistId ?: PlaylistStore.read(context) { book ->
            book.of(Playlist.Kind.MUSIC).firstOrNull { it.items == s.tracks }?.id
        }
        changed()
    }

    /** Saves the list and the place in it (off the main thread; the song's name never goes to a log). */
    internal fun saved(context: Context) {
        val app = context.applicationContext
        // While a file plays on its own, what is kept is the list it will go back to.
        val b = before
        val s = if (b != null) Session(b.queue.tracks, b.queue.currentIndex,
                                       b.positionMs, b.queue.shuffle, b.queue.repeat, b.playlistId)
                else Session(queue.tracks, queue.currentIndex, if (hasMedia) positionMs else resumeAtMs,
                             queue.shuffle, queue.repeat, playlistId)
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

    /** A track is in the player (not stopped), whichever engine has it. */
    val loaded: Boolean get() = if (usingVlc) vlc?.loaded == true
        else player.currentMediaItem != null && player.playbackState != Player.STATE_IDLE

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
        else if (step.pause && wantsToPlay()) { pauseNow(); MusicPlayer.error = getString(R.string.video_heat_pause) }
    }

    // ------------------------------------------------------------ 0.60.0: two engines, one choice

    /** LibVLC's player, made the first time a song needs it (PlayerChoice). */
    private var vlc: VlcDeck? = null
    /** The song now loaded plays through LibVLC. */
    var usingVlc = false
        private set
    /** LibVLC is meant to be playing (it has no playWhenReady of its own). */
    private var vlcWant = false
    /** A song Media3 could not decode after all: it goes to LibVLC, once. */
    private var handedOver: String? = null
    /** Whether the song last asked for was to play: kept when one is skipped (0.60.0). */
    private var wantPlay = true

    private val vlcEvents = object : VlcDeck.Events {
        override fun onPlaying() { failures = 0; learnDuration(); update() }
        override fun onPaused() = update()
        override fun onEnded() { vlcWant = false; trackEnded() }
        override fun onError() {
            Log.w(TAG, "track failed: vlc")
            vlcWant = false
            skip(getString(R.string.music_error_track, MusicPlayer.queue.current?.title.orEmpty()))
        }
    }

    fun positionMs(): Long = if (usingVlc) vlc?.positionMs ?: 0 else player.currentPosition
    fun durationMs(): Long = if (usingVlc) vlc?.durationMs ?: 0 else player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
    private fun wantsToPlay(): Boolean = if (usingVlc) vlcWant else player.playWhenReady
    fun playNow() { if (usingVlc) { vlcWant = true; vlc?.play(); update() } else player.play() }
    private fun pauseNow() { if (usingVlc) { vlcWant = false; vlc?.pause() } else player.pause() }
    fun seek(ms: Long) { if (usingVlc) vlc?.seekTo(ms) else player.seekTo(ms) }
    fun applyVolume() { player.volume = MusicPlayer.volume; vlc?.setVolume(MusicPlayer.volume) }
    fun applyEq() { if (usingVlc) vlc?.equalize(MusicPlayer.eq) }
    fun vlcFacts(): Triple<Int?, Int?, Int?>? = if (usingVlc) vlc?.soundFacts() else null
    fun vlcTags(): Triple<String?, String?, String?>? = if (usingVlc) vlc?.tags() else null

    /** Which engine plays [track]: PlayerChoice, looking inside an .m4a on the phone. */
    private fun engineFor(track: Track): PlayerChoice.Engine =
        if (handedOver == track.id) PlayerChoice.Engine.VLC
        else PlayerChoice.forFile(track.path) { if (track.onNas) null else Mp4Sniff.audioCodec(java.io.File(track.path)) }

    /**
     * Media3 opened the song but cannot decode it (an ALAC .m4a it was not told
     * about, say): LibVLC is given it, once, as PlayerChoice promises. Returns
     * false when it already had its turn.
     */
    private fun handOver(): Boolean {
        val t = MusicPlayer.queue.current ?: return false
        if (usingVlc || handedOver == t.id) return false
        handedOver = t.id
        Log.i(TAG, "handed to vlc")
        load(t, wantPlay, keepError = true)
        return true
    }

    /** A song finished by itself: the next one, or the end of the list (Media3's ENDED or VLC's EndReached). */
    private fun trackEnded() {
        val next = MusicPlayer.queue.next(auto = true)
        // A file played on its own that ends: the list comes back (0.59.0).
        if (next != null) load(next, true) else if (MusicPlayer.single) endSingleNow(false) else stopAll()
    }

    /** A NAS song's length is known once it plays: the list shows it from then on. */
    private fun learnDuration() {
        val ms = durationMs().takeIf { it > 0 } ?: return
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
                handler.post { if (!handOver()) skip(getString(R.string.music_no_decoder, MusicPlayer.queue.current?.title.orEmpty(), kind)) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    failures = 0
                    learnDuration()
                }
                if (state == Player.STATE_ENDED) trackEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "track failed: ${error.errorCodeName}")
                val track = MusicPlayer.queue.current
                val title = track?.title.orEmpty()
                // 0.60.0: a file Media3 cannot open or decode is LibVLC's to try.
                if ((error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                     error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) && handOver()) return
                // 0.59.0: said as it is — gone, or a kind not played yet — not "the file may be damaged".
                skip(when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> getString(R.string.music_file_missing, title)
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        getString(R.string.music_not_yet, title, PlayerChoice.typeWord(track?.path.orEmpty()))
                    else -> getString(R.string.music_error_track, title)
                })
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
        vlc?.release()
        vlc = null
        if (MusicPlayer.state != MusicPlayer.State.STOPPED) {
            MusicPlayer.state = MusicPlayer.State.STOPPED
            MusicPlayer.changed()
        }
        Log.i(TAG, "music service down")
        super.onDestroy()
    }

    fun load(track: Track?, play: Boolean, keepError: Boolean = false) {
        if (track == null) return stopAll()
        wantPlay = play
        if (handedOver != track.id) handedOver = null
        // A song skipped because it failed keeps its reason on screen while the next plays.
        if (!keepError) MusicPlayer.error = null
        // 0.59.0: a song no longer on the phone, or of a kind no player here plays yet —
        // said at once, and the next one plays; never a silent clock, never a hang.
        val problem = when {
            !track.onNas && !java.io.File(track.path).exists() -> getString(R.string.music_file_missing, track.title)
            PlayerChoice.forName(track.path) == PlayerChoice.Engine.NOT_YET ->
                getString(R.string.music_not_yet, track.title, PlayerChoice.typeWord(track.path))
            else -> null
        }
        if (problem != null) {
            Log.i(TAG, "not played: " + if (PlayerChoice.forName(track.path) == PlayerChoice.Engine.NOT_YET) "kind not yet" else "file gone")
            player.stop()
            player.clearMediaItems()
            vlc?.stop()
            handler.post { skip(problem) }
            return
        }
        // Back where it was left (0.55.0): the saved place, once, for the saved song.
        val at = MusicPlayer.resumeAtMs
        MusicPlayer.resumeAtMs = 0
        if (engineFor(track) == PlayerChoice.Engine.VLC) {
            // 0.60.0: what Media3 cannot play (PlayerChoice). The song is on LibVLC alone.
            player.stop()
            player.clearMediaItems()
            val deck = vlc ?: VlcDeck(this, vlcEvents).also { vlc = it }
            usingVlc = true
            vlcWant = play
            deck.setVolume(MusicPlayer.volume)
            deck.equalize(MusicPlayer.eq)
            deck.load(track, at, play)
            Log.i(TAG, "engine vlc")
        } else {
            vlc?.stop()
            usingVlc = false
            vlcWant = false
            player.setMediaItem(MediaItem.fromUri(MediaSources.uriOf(track)), at)
            player.prepare()
            player.playWhenReady = play
        }
        startInForeground()
        update()
    }

    /**
     * [why] is said on screen and the next song plays; after a whole list of
     * failures it stops. A file on its own goes back to the list, the reason kept.
     */
    private fun skip(why: String) {
        MusicPlayer.error = why
        failures += 1
        val next = if (failures < MusicPlayer.queue.tracks.size) MusicPlayer.queue.next(auto = false) else null
        when {
            // Plays only if the one it replaces was to play (0.60.0: adding songs to an empty list
            // started the music when the first could not be played, seen in Poom's log).
            next != null -> load(next, wantPlay, keepError = true)
            MusicPlayer.single -> endSingleNow(true)
            else -> stopAll()
        }
    }

    /** A file that played on its own is done: the list comes back as it was (MusicPlayer.restoreBefore). */
    fun endSingleNow(keepError: Boolean) {
        failures = 0
        MusicPlayer.restoreBefore(this, keepError)
    }

    /** Stop, forget the loaded track (the queue stays), and let the wake word back. */
    fun stopAll() {
        // Stopped is stopped: next time the song starts from its beginning (the list stays).
        MusicPlayer.resumeAtMs = 0
        player.stop()
        player.clearMediaItems()
        vlc?.stop()
        usingVlc = false
        vlcWant = false
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
        val meant = if (usingVlc) vlcWant && vlc?.loaded == true
            else player.playWhenReady &&
                (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        val newState = when {
            meant || quieted -> MusicPlayer.State.PLAYING
            loaded -> MusicPlayer.State.PAUSED
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

    /**
     * A pause somebody asked for. Quieted for a question, the player keeps its
     * hold so it can play on after the answer; asked to pause, it lets go, and
     * WakePause.turnEnded leaves a released player alone (0.57.0: "หยุดวิดีโอ"
     * said through the button would otherwise start again after the reply).
     */
    fun pauseOnPurpose() {
        quieted = false
        pauseNow()
        update()
    }

    override fun quietForJarvis() {
        if (!wantsToPlay()) return
        quieted = true
        pauseNow()
    }

    override fun resumeAfterJarvis() {
        if (!quieted) return
        quieted = false
        playNow()
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
