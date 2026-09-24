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
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.voice.WakePause
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's heat, for both players (0.56.0): Android's thermal status goes
 * through [HeatLadder]; each step's actions are taken by whoever is playing.
 * Listened to from the first player that starts, for as long as the app runs.
 */
object HeatWatch {
    val ladder = HeatLadder()
    @Volatile var step = HeatLadder.Step.NORMAL
        private set
    @Volatile var status = 0
        private set
    val listeners = CopyOnWriteArrayList<(HeatLadder.Step) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val power = context.applicationContext.getSystemService(PowerManager::class.java) ?: return
        status = power.currentThermalStatus
        apply()
        power.addThermalStatusListener(ContextCompat.getMainExecutor(context.applicationContext)) { s ->
            status = s
            apply()
        }
    }

    /** Also called every 15 s while above normal, so the ladder can come down after its wait. */
    private fun apply() {
        val next = ladder.update(status, android.os.SystemClock.elapsedRealtime())
        if (next != step) {
            Log.i(VideoService.TAG, "heat status=$status step=${next.name.lowercase()}")
            step = next
            for (l in listeners) l(next)
        }
        main.removeCallbacksAndMessages(null)
        if (step != HeatLadder.Step.NORMAL) main.postDelayed({ apply() }, 15_000)
    }
}

/**
 * What the screens and the home card see of the video (0.56.0), like
 * [MusicPlayer]: the list it came from, the state, and commands that reach
 * [VideoService]. Main thread for the fields.
 */
object VideoPlayer {

    enum class State { STOPPED, PLAYING, PAUSED }

    var list: List<Video> = emptyList()
        private set
    var index = -1
        private set
    val current: Video? get() = list.getOrNull(index)

    @Volatile var state = State.STOPPED
        internal set
    /** Why the video is not playing, in words for the screen; null when fine. */
    @Volatile var error: String? = null
        internal set
    /** The subtitle line now, or "" when none. */
    @Volatile var cue: String = ""
        internal set
    @Volatile var volume = 1f
        private set
    @Volatile var speed = 1f
        private set

    internal var service: VideoService? = null
    private val pending = ArrayList<(VideoService) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    internal fun changed() = main.post { for (l in listeners) l() }

    val hasMedia: Boolean get() = state != State.STOPPED && current != null
    val player: ExoPlayer? get() = service?.player
    val positionMs: Long get() = player?.currentPosition ?: 0
    val durationMs: Long get() = player?.duration?.takeIf { it != C.TIME_UNSET } ?: (current?.track?.durationMs ?: 0)

    private fun run(context: Context, block: (VideoService) -> Unit) {
        main.post {
            val s = service
            if (s != null) block(s)
            else {
                pending.add(block)
                ContextCompat.startForegroundService(context.applicationContext, Intent(context, VideoService::class.java))
            }
        }
    }

    internal fun attach(s: VideoService) {
        service = s
        val waiting = ArrayList(pending); pending.clear()
        for (b in waiting) b(s)
    }

    internal fun detach(s: VideoService) { if (service === s) service = null }

    /** Plays [videos][start], from where it was left last time. Music stops: one sound at a time. */
    fun play(context: Context, videos: List<Video>, start: Int) = run(context) {
        MusicPlayer.quietForVideo(context)
        before = null
        list = videos; index = start.coerceIn(0, videos.lastIndex)
        it.load(current!!, play = true)
    }

    // ------------------------------------------------------------ 0.59.0: one file on its own

    private var before: Pair<List<Video>, Int>? = null

    /** A video from the file manager is playing on its own; the playlist waits to come back. */
    val single: Boolean get() = before != null

    /** Plays [video] alone, never added to a playlist; the list and its place come back after ([endSingle]). */
    fun playSingle(context: Context, video: Video) = run(context) {
        MusicPlayer.quietForVideo(context)
        if (before == null) before = list to index
        list = listOf(video); index = 0
        it.load(video, play = true)
        Log.i(VideoService.TAG, "one file playing on its own")
    }

    /** The playlist and the video in it back as they were (the place in each video is kept by the video). */
    fun endSingle() {
        val b = before ?: return
        before = null
        list = b.first; index = b.second
        changed()
        Log.i(VideoService.TAG, "list back after one file")
    }

    fun resume(context: Context) = run(context) { s ->
        // Too hot: play is refused, and the screen says why (HeatLadder step 3+).
        if (HeatWatch.step.pause) { error = context.getString(R.string.video_heat_pause); changed(); return@run }
        MusicPlayer.quietForVideo(context)
        if (s.loaded) s.player.play() else current?.let { s.load(it, true) }
    }
    /** Paused on purpose (a button, a spoken "หยุด"): not started again when a question ends. */
    fun pause(context: Context) = run(context) { it.pauseOnPurpose() }
    fun toggle(context: Context) = if (state == State.PLAYING) pause(context) else resume(context)
    fun stop(context: Context) = run(context) { it.stopAll() }

    /** Music starting: a playing video pauses (one sound at a time); a stopped one is not woken. */
    fun quietForMusic(context: Context) { if (state == State.PLAYING) pause(context) }
    fun seekTo(context: Context, ms: Long) = run(context) { it.player.seekTo(ms.coerceAtLeast(0)) }
    fun skip(context: Context, deltaMs: Long) = run(context) { it.player.seekTo((it.player.currentPosition + deltaMs).coerceAtLeast(0)) }
    fun next(context: Context) = run(context) { s ->
        if (index + 1 < list.size) { index += 1; s.load(current!!, true) }
    }

    fun setVolume(context: Context, value: Float) {
        volume = value.coerceIn(0f, 1f)
        run(context) { it.player.volume = volume }
        changed()
    }

    fun cycleSpeed(context: Context) {
        speed = VideoRules.nextSpeed(speed)
        run(context) { it.player.playbackParameters = PlaybackParameters(speed) }
        changed()
    }

    /** Audio or subtitle tracks the file has: (group, index in group, words). */
    data class Choice(val group: Tracks.Group, val index: Int, val words: String, val selected: Boolean)

    fun choices(type: Int): List<Choice> {
        val p = player ?: return emptyList()
        val out = ArrayList<Choice>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val words = listOfNotNull(f.label, f.language?.takeIf { it != "und" }, f.sampleMimeType?.substringAfter('/'))
                    .joinToString(" · ").ifEmpty { "${out.size + 1}" }
                out.add(Choice(g, i, words, g.isTrackSelected(i)))
            }
        }
        return out
    }

    @OptIn(UnstableApi::class)
    fun choose(context: Context, type: Int, choice: Choice?) = run(context) { s ->
        val params = s.player.trackSelectionParameters.buildUpon()
        if (choice == null) params.setTrackTypeDisabled(type, true)
        else params.setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
        s.player.trackSelectionParameters = params.build()
    }

    fun attachSurface(view: SurfaceView) { player?.setVideoSurfaceView(view) }
    fun detachSurface(view: SurfaceView) { player?.clearVideoSurfaceView(view) }

    // ------------------------------------------------------------ where each video was left

    private const val PLACES = "video_places"

    /** Kept by a hash of the id, so no file name is stored in the preferences. */
    private fun key(v: Video) = "p" + v.track.id.hashCode().toUInt().toString(16)

    fun savedPlace(context: Context, v: Video): Long =
        context.getSharedPreferences(PLACES, Context.MODE_PRIVATE).getLong(key(v), 0)

    internal fun savePlace(context: Context, v: Video, ms: Long, durationMs: Long) {
        val keep = VideoRules.resumeAt(ms, durationMs)
        context.getSharedPreferences(PLACES, Context.MODE_PRIVATE).edit().apply {
            if (keep == 0L) remove(key(v)) else putLong(key(v), keep)
        }.apply()
    }
}

/**
 * The video player itself (0.56.0): ExoPlayer in a foreground service, like
 * the music (DESIGN.md 12): the picture shows while its screen is open; closed
 * — by Back, the X or Hey Jarvis — the sound plays on, and the picture comes
 * back with the screen. Jarvis rests while it plays (WakePause, VIDEO).
 *
 * LIMITS, Poom's: up to 720p, no 4K, no AV1 — a file over them is not played
 * and the screen says why. The heat ladder dims the screen, then
 * pauses ([HeatWatch]). NOTHING ABOUT THE VIDEO IS LOGGED: states and error
 * kinds, never a title or a path.
 */
@OptIn(UnstableApi::class)
class VideoService : Service(), WakePause.Media {

    lateinit var player: ExoPlayer
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var hold: WakePause.Hold? = null
    private var quieted = false
    private var loadedVideo: Video? = null

    val loaded: Boolean get() = player.currentMediaItem != null && player.playbackState != Player.STATE_IDLE

    private val renew = object : Runnable {
        override fun run() {
            hold?.let { if (!WakePause.renew(it)) hold = null }
            if (hold != null) handler.postDelayed(this, WakePause.RENEW_MS)
        }
    }

    private val saver = object : Runnable {
        override fun run() {
            savePlace()
            handler.postDelayed(this, 5_000)
        }
    }

    private val heat: (HeatLadder.Step) -> Unit = { applyHeat(it) }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(MediaSources.factory(this)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Subtitles on when the file has them: Thai first, then English, then any
        // (the player shows none unless told a language, seen on the A07).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(VideoRules.MAX_W, VideoRules.MAX_H)
            .setPreferredTextLanguages("th", "en")
            .setSelectUndeterminedTextLanguage(true)
            .setPreferredAudioLanguages("th", "en")
            .build()
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = update()
            override fun onTracksChanged(tracks: Tracks) = checkLimits(tracks)
            override fun onCues(cueGroup: CueGroup) {
                VideoPlayer.cue = cueGroup.cues.joinToString("\n") { it.text?.toString().orEmpty() }.trim()
                VideoPlayer.changed()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    loadedVideo?.let { VideoPlayer.savePlace(this@VideoService, it, Long.MAX_VALUE, 0) }
                    stopAll()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "video failed: ${error.errorCodeName}")
                val track = VideoPlayer.current?.track
                val title = track?.title.orEmpty()
                // 0.59.0: said as it is — gone, or a kind not played yet.
                VideoPlayer.error = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> getString(R.string.video_file_missing, title)
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        getString(R.string.video_not_yet, title, PlayerChoice.typeWord(track?.path.orEmpty()))
                    else -> getString(R.string.video_error, title)
                }
                stopAll()
            }
        })
        HeatWatch.start(this)
        HeatWatch.listeners.add(heat)
        applyHeat(HeatWatch.step)
        startInForeground()
        handler.postDelayed(saver, 5_000)
        VideoPlayer.attach(this)
        Log.i(TAG, "video service up")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        savePlace()
        HeatWatch.listeners.remove(heat)
        releaseHold()
        handler.removeCallbacksAndMessages(null)
        VideoPlayer.detach(this)
        player.release()
        if (VideoPlayer.state != VideoPlayer.State.STOPPED) { VideoPlayer.state = VideoPlayer.State.STOPPED; VideoPlayer.changed() }
        Log.i(TAG, "video service down")
        super.onDestroy()
    }

    fun load(video: Video, play: Boolean) {
        VideoPlayer.error = null
        VideoPlayer.cue = ""
        // 0.59.0: a video no longer on the phone, or of a kind no player here plays yet
        // (a VCD's .DAT, .mpg, .wmv): said at once, nothing opened, nothing hangs.
        val t = video.track
        val problem = when {
            !t.onNas && !File(t.path).exists() -> getString(R.string.video_file_missing, t.title)
            PlayerChoice.forName(t.path) == PlayerChoice.Engine.NOT_YET ->
                getString(R.string.video_not_yet, t.title, PlayerChoice.typeWord(t.path))
            else -> null
        }
        if (problem != null) {
            Log.i(TAG, "not played: " + if (PlayerChoice.forName(t.path) == PlayerChoice.Engine.NOT_YET) "kind not yet" else "file gone")
            VideoPlayer.error = problem
            stopAll()
            return
        }
        // Known too big from the phone's index: said, not played.
        VideoRules.refusal(video.width, video.height, video.mime)?.let { refuse(it); return }
        savePlace()
        loadedVideo = video
        val at = VideoRules.resumeAt(VideoPlayer.savedPlace(this, video), video.track.durationMs)
        player.setMediaItem(MediaItem.fromUri(MediaSources.uriOf(video.track)), at)
        player.playbackParameters = PlaybackParameters(VideoPlayer.speed)
        player.volume = VideoPlayer.volume
        player.prepare()
        player.playWhenReady = play && !HeatWatch.step.pause
        startInForeground()
        update()
    }

    /**
     * A NAS file's size is known only once it opens: checked here, and refused the same way.
     * 0.59.0: a file with no picture this phone can show — MPEG-2 in a .ts, or a VCD
     * read as sound only (codecprobe, A07) — is not supported yet, and said so.
     */
    private fun checkLimits(tracks: Tracks) {
        if (tracks.groups.isNotEmpty() && loadedVideo != null) {
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (video.none { g -> (0 until g.length).any { g.isTrackSupported(it) } }) {
                val t = loadedVideo!!.track
                Log.i(TAG, "not played: no picture it can show")
                VideoPlayer.error = getString(R.string.video_not_yet, t.title, PlayerChoice.typeWord(t.path))
                stopAll()
                return
            }
        }
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            val all = (0 until g.length).map { g.getTrackFormat(it) }
            val ok = all.any { VideoRules.refusal(it.width.coerceAtLeast(0), it.height.coerceAtLeast(0), it.sampleMimeType) == null }
            if (!ok && all.isNotEmpty()) {
                val f = all.first()
                refuse(VideoRules.refusal(f.width, f.height, f.sampleMimeType) ?: "")
            }
        }
    }

    private fun refuse(why: String) {
        Log.i(TAG, "refused: over the limit")
        VideoPlayer.error = if (why == "AV1") getString(R.string.video_refused_av1) else getString(R.string.video_refused, why)
        stopAll()
    }

    fun stopAll() {
        savePlace()
        loadedVideo = null
        player.stop()
        player.clearMediaItems()
        quieted = false
        releaseHold()
        VideoPlayer.state = VideoPlayer.State.STOPPED
        VideoPlayer.changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun savePlace() {
        val v = loadedVideo ?: return
        if (player.currentMediaItem == null) return
        VideoPlayer.savePlace(this, v, player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET } ?: 0)
    }

    /** One step of the heat ladder, for the video: its size cap, and pause or stop. */
    private fun applyHeat(step: HeatLadder.Step) {
        val h = step.maxHeight ?: VideoRules.MAX_H
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(VideoRules.MAX_W * h / VideoRules.MAX_H, h).build()
        if (step.stop) { VideoPlayer.error = getString(R.string.video_heat_stop); stopAll(); return }
        if (step.pause && player.playWhenReady) { player.pause(); VideoPlayer.error = getString(R.string.video_heat_pause) }
        VideoPlayer.changed()
    }

    private fun update() {
        val meant = player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        val newState = when {
            meant || quieted -> VideoPlayer.State.PLAYING
            player.currentMediaItem != null && player.playbackState != Player.STATE_IDLE -> VideoPlayer.State.PAUSED
            else -> VideoPlayer.state.takeIf { it == VideoPlayer.State.STOPPED } ?: VideoPlayer.State.PAUSED
        }
        if (meant || quieted) takeHold() else releaseHold()
        if (newState != VideoPlayer.state) {
            if (VideoPlayer.state == VideoPlayer.State.PLAYING) savePlace()
            VideoPlayer.state = newState
            Log.i(TAG, "state ${newState.name.lowercase()}")
        }
        VideoPlayer.changed()
        refreshNotification()
    }

    private fun takeHold() {
        if (hold != null) return
        hold = WakePause.hold(WakePause.Source.VIDEO, this)
        handler.postDelayed(renew, WakePause.RENEW_MS)
    }

    private fun releaseHold() {
        hold?.let { WakePause.release(it) }
        hold = null
        handler.removeCallbacks(renew)
    }

    /**
     * A pause somebody asked for. Quieted for a question, the player keeps its
     * hold so it can play on after the answer; asked to pause, it lets go, and
     * WakePause.turnEnded leaves a released player alone (0.57.0: "หยุดวิดีโอ"
     * said through the button would otherwise start again after the reply).
     */
    fun pauseOnPurpose() {
        quieted = false
        player.pause()
        update()
    }

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

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.window_video),
                                                                  NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, VideoActivity::class.java),
                                             PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pixel_video_light)
            .setContentTitle(VideoPlayer.current?.track?.title ?: getString(R.string.window_video))
            .setContentText(getString(if (VideoPlayer.state == VideoPlayer.State.PAUSED) R.string.music_paused
                                      else R.string.music_playing))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun refreshNotification() {
        if (VideoPlayer.state == VideoPlayer.State.STOPPED) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    companion object {
        /** `logcat -s KioskVideo:I` — states, heat steps and error kinds; never titles or paths. */
        const val TAG = "KioskVideo"
        private const val CHANNEL = "video"
        private const val NOTIFICATION_ID = 56
    }
}
