package com.mammonrn.phoneaikiosk.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import com.mammonrn.phoneaikiosk.files.NasStore
import com.mammonrn.phoneaikiosk.media.fx.Eq
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/**
 * LibVLC, for what Media3 cannot play (0.60.0, Poom; PlayerChoice decides).
 * Built by us under the LGPL only (tools/libvlc/build-lgpl.sh) and fetched by
 * checksum; see licenses/ and the "ที่มาข้อมูล" page.
 *
 * One LibVLC for the app ([lib]); a [VlcDeck] is one player of it, owned by a
 * service, on the main thread. It says what happens through [Events], in the
 * same words the services already use for Media3: playing, paused, ended,
 * failed, and the picture's size once known.
 *
 * WHAT IT DOES NOT HAVE: our own equalizer and bars (they live in Media3's
 * audio path). Poom: the file uses VLC's 10-band equalizer, set from the same
 * settings ([equalize]), and the bars say there is no graph for this file.
 * Nothing about the file is logged.
 *
 * AUDIO FOCUS (0.61.0, Poom: "ทำให้ครบ" — as Media3 does it for its files):
 * playing asks the phone for focus as media ([movie]: a film's sound). Another
 * sound that takes it for good, or headphones pulled out, pauses the file and
 * the service is told ([Events.onOutsidePause]) as if paused by hand; a short
 * one (a navigation prompt, a call) pauses it and it plays on after; one that
 * lets others duck lowers it to [DUCK] of its volume. Jarvis's own voice
 * pauses it through WakePause, the same way as Media3's files.
 */
class VlcDeck(context: Context, private val events: Events, private val movie: Boolean = false) {

    interface Events {
        fun onPlaying()
        fun onPaused()
        fun onEnded()
        fun onError()
        /** The picture's size is known (video only). */
        fun onVideo(width: Int, height: Int) {}
        /** Paused from outside the app (focus lost for good, headphones out): as a pause by hand. */
        fun onOutsidePause() {}
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val player = MediaPlayer(lib(app))
    private var media: Media? = null
    private var view: SurfaceView? = null
    private var viewAttached = false

    /** A file is in the player (not stopped). */
    var loaded = false
        private set
    var playing = false
        private set

    init {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { playing = true; startWhereAsked(); events.onPlaying() }
                MediaPlayer.Event.SeekableChanged -> if (e.seekable) startWhereAsked()
                MediaPlayer.Event.Paused -> { playing = false; events.onPaused() }
                MediaPlayer.Event.TimeChanged -> {
                    lastTimeMs = e.timeChanged
                    if (startAtMs > 0) {
                        if (e.timeChanged >= startAtMs - START_NEAR_MS) { startAtMs = 0; Log.i(TAG, "vlc: started where asked") }
                        else startWhereAsked()
                    }
                }
                MediaPlayer.Event.LengthChanged -> if (e.lengthChanged > 0) lengthMs = e.lengthChanged
                MediaPlayer.Event.EndReached -> {
                    playing = false
                    val at = lastTimeMs
                    main.post { if (!rescue(at)) events.onEnded() }
                }
                MediaPlayer.Event.EncounteredError -> { playing = false; main.post { events.onError() } }
                MediaPlayer.Event.Vout -> if (e.voutCount > 0) videoSize()?.let { (w, h) -> events.onVideo(w, h) }
                MediaPlayer.Event.ESAdded -> videoSize()?.let { (w, h) -> events.onVideo(w, h) }
            }
        }
    }

    /** Where the file is, as VLC last said, and its length: kept for an end that comes too early. */
    private var lastTimeMs = 0L
    private var lengthMs = 0L
    private var track: Track? = null
    private var rescues = 0
    /** Where the last rescue went on from: an end that is not past it made no progress. */
    private var rescuedFromMs = 0L
    private var streak = 0
    /**
     * Where the file is to start, until VLC can seek. Not VLC's ":start-time":
     * a VCD ignores it and starts at 0:00 (0.60.0, found on the A07), so the
     * file is sought to once it plays and can seek.
     */
    private var startAtMs = 0L
    /**
     * A seek asked as the file opens can be lost (0.60.0: the VCD went on from
     * 0:00 after a rescue), so it is asked again until VLC's time shows it
     * took, a few times at most.
     */
    private var seekTries = 0
    private var lastTryAt = 0L

    private fun startWhereAsked() {
        val at = startAtMs
        if (at <= 0 || !player.isSeekable) return
        // A seek takes a moment to show in VLC's time: asked again only after a second.
        val now = android.os.SystemClock.elapsedRealtime()
        if (seekTries > 0 && now - lastTryAt < 1_000) return
        lastTryAt = now
        if (seekTries >= MAX_SEEK_TRIES) { startAtMs = 0; Log.w(TAG, "vlc: could not start where asked"); return }
        seekTries++
        player.time = at
    }

    /** Opens [track] at [startMs]; plays when [play]. */
    fun load(track: Track, startMs: Long, play: Boolean) {
        rescues = 0
        rescuedFromMs = 0
        streak = 0
        open(track, startMs, play)
    }

    /** An end before the length, from a damaged spot ([EarlyEnd]): the file plays on past it. */
    private fun rescue(atMs: Long): Boolean {
        val t = track ?: return false
        if (!loaded) return false
        streak = if (EarlyEnd.isNewSpot(atMs, rescuedFromMs)) 0 else streak + 1
        val from = EarlyEnd.goOnAt(atMs, lengthMs.takeIf { it > 0 } ?: player.length, rescues, rescuedFromMs, streak) ?: return false
        rescues++
        rescuedFromMs = from
        Log.w(TAG, "vlc: early end, going on past it ($rescues)")
        open(t, from, play = true)
        return true
    }

    private fun open(track: Track, startMs: Long, play: Boolean) {
        stop()
        this.track = track
        lastTimeMs = startMs
        lengthMs = 0
        startAtMs = startMs
        seekTries = 0
        shownSize = null
        if (!track.onNas && MediaKinds.extension(track.path) in MPEG_KINDS) sniffSize(track)
        val m = if (track.onNas) nasMedia(track) else Media(lib(app), Uri.fromFile(java.io.File(track.path)))
        // Kept (and released at stop): the player's getMedia() would take a reference each time it is asked.
        media = m
        player.media = m
        loaded = true
        // Not playing: nothing is started — VLC has no "prepare" — and play() starts it at [startMs].
        if (play) start()
    }

    /**
     * A NAS file through VLC's own SMB2 access (libsmb2, LGPL). The password
     * goes as an option, never in the address; nothing of it is logged.
     */
    private fun nasMedia(track: Track): Media {
        val c = NasStore.load(app) ?: throw IllegalStateException("no nas settings")
        val path = track.path.replace('\\', '/').split('/').joinToString("/") { Uri.encode(it) }
        val host = if (c.port == com.mammonrn.phoneaikiosk.files.NasConfig.DEFAULT_PORT) c.host else "${c.host}:${c.port}"
        val m = Media(lib(app), Uri.parse("smb://$host/${Uri.encode(c.share)}/$path"))
        if (c.user.isNotEmpty()) m.addOption(":smb-user=${c.user}")
        if (c.password.isNotEmpty()) m.addOption(":smb-pwd=${c.password}")
        if (c.domain.isNotEmpty()) m.addOption(":smb-domain=${c.domain}")
        return m
    }

    fun play() { if (loaded) start() }
    fun pause() {
        letFocusGo()
        if (player.isPlaying) player.pause()
    }

    fun stop() {
        letFocusGo()
        if (loaded) player.stop()
        loaded = false
        playing = false
        startAtMs = 0
        media?.release()
        media = null
    }

    val positionMs: Long get() = if (!loaded) 0 else if (startAtMs > 0) startAtMs else player.time.coerceAtLeast(0)
    val durationMs: Long get() = if (loaded) player.length.coerceAtLeast(0) else 0

    fun seekTo(ms: Long) {
        if (!loaded) return
        // Before it can seek, the place is kept for when it can.
        if (startAtMs > 0 || !player.isSeekable) { startAtMs = ms.coerceAtLeast(0); seekTries = 0 } else player.time = ms.coerceAtLeast(0)
    }

    /** 0..1, as the players' own volume. */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        applyVolume()
    }

    private var volume = 1f
    private fun applyVolume() { player.volume = ((if (ducked) volume * DUCK else volume) * 100).toInt() }

    // ------------------------------------------------------------ audio focus (0.61.0)

    private val audio = app.getSystemService(AudioManager::class.java)
    private var hasFocus = false
    /** Paused for a short sound of another app; plays on when focus comes back. */
    private var pausedForOther = false
    private var ducked = false

    private val onFocus = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "vlc: focus lost")
                letFocusGo()
                if (player.isPlaying) player.pause()
                events.onOutsidePause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "vlc: focus lost for a moment")
                if (player.isPlaying) { pausedForOther = true; player.pause() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { Log.i(TAG, "vlc: ducked"); ducked = true; applyVolume() }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "vlc: focus back")
                if (ducked) { ducked = false; applyVolume() }
                if (pausedForOther) { pausedForOther = false; if (loaded) player.play() }
            }
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(if (movie) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener(onFocus, main)
        .build()

    /** Headphones pulled out: paused, as Media3's setHandleAudioBecomingNoisy does. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY || !player.isPlaying) return
            Log.i(TAG, "vlc: headphones out")
            letFocusGo()
            player.pause()
            events.onOutsidePause()
        }
    }
    private var noisyOn = false

    /** Plays once the phone gives focus; refused (a call on), it stays paused and the service is told. */
    private fun start() {
        if (!hasFocus) {
            if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i(TAG, "vlc: focus refused")
                events.onOutsidePause()
                return
            }
            hasFocus = true
        }
        pausedForOther = false
        if (!noisyOn) {
            androidx.core.content.ContextCompat.registerReceiver(app, noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyOn = true
        }
        player.play()
    }

    private fun letFocusGo() {
        pausedForOther = false
        if (ducked) { ducked = false; applyVolume() }
        if (hasFocus) { audio.abandonAudioFocusRequest(focusRequest); hasFocus = false }
        if (noisyOn) { runCatching { app.unregisterReceiver(noisy) }; noisyOn = false }
    }

    fun setRate(r: Float) { player.rate = r }

    // ------------------------------------------------------------ the equalizer (Poom: VLC's, from the same settings)

    /**
     * The user's settings on VLC's 10-band equalizer. The bands are the same
     * frequencies (60 Hz … 16 kHz, Winamp's) and matched by nearest frequency
     * all the same; gains and preamp in dB as they are. Off: no equalizer.
     */
    fun equalize(s: Eq.Settings) {
        if (!s.on) { player.setEqualizer(null); return }
        val eq = MediaPlayer.Equalizer.create()
        eq.setPreAmp(s.preampDb)
        for (band in 0 until MediaPlayer.Equalizer.getBandCount()) {
            val hz = MediaPlayer.Equalizer.getBandFrequency(band).toDouble()
            val nearest = Eq.BANDS.indices.minByOrNull { kotlin.math.abs(Eq.BANDS[it] - hz) } ?: continue
            eq.setAmp(band, s.gainsDb[nearest])
        }
        player.setEqualizer(eq)
    }

    // ------------------------------------------------------------ the picture

    /** Draws on [surface] from now on (one at a time); null lets go of it. */
    fun attach(surface: SurfaceView?) {
        if (view === surface && viewAttached) return
        val vout = player.vlcVout
        if (viewAttached) { vout.detachViews(); viewAttached = false }
        view = surface
        if (surface != null) {
            // The order that showed a picture on the A07 (codecprobe): the view, its size AT ONCE,
            // then attach — VLC opens no picture before it knows the size (0.60.0: a size posted
            // for later left the VCD black with its sound playing).
            vout.setVideoView(surface)
            val m = surface.resources.displayMetrics
            val w = if (surface.width > 0) surface.width else m.widthPixels
            val h = if (surface.height > 0) surface.height else m.widthPixels * 9 / 16
            vout.setWindowSize(w, h)
            Log.i(TAG, "vlc: window ${w}x$h at attach")
            // VLC says the picture's size here; the track list of a VCD has none (0.60.0: the
            // screen stayed 16:9 and the 4:3 picture was drawn small inside it).
            vout.attachViews { _, _, _, visibleW, visibleH, sarNum, sarDen ->
                if (visibleW > 0 && visibleH > 0) {
                    val sar = if (sarNum > 0 && sarDen > 0) sarNum.toDouble() / sarDen else 1.0
                    shownSize = (visibleW * sar).toInt() to visibleH
                    events.onVideo(shownSize!!.first, visibleH)
                }
            }
            viewAttached = true
        }
    }

    /** The picture's output opened again (a surface given after the video started had none). */
    fun reopenVideo() {
        if (!loaded) return
        val t = player.videoTrack.takeIf { it >= 0 } ?: player.videoTracks?.firstOrNull { it.id >= 0 }?.id ?: return
        player.setVideoTrack(-1)
        player.setVideoTrack(t)
    }

    /** The surface was laid out again (turned, full screen): VLC draws to its new size. */
    fun resized(width: Int, height: Int) {
        Log.i(TAG, "vlc: window ${width}x$height (attached=$viewAttached)")
        if (viewAttached && width > 0) player.vlcVout.setWindowSize(width, height)
    }

    /**
     * VLC's own counts for the file playing (0.63.0, the ".DAT: sound but a black
     * screen" bug): pictures decoded, shown and lost. A picture that is decoded
     * but never shown is a surface problem, not a file problem — and a count,
     * unlike a screenshot, cannot be fooled by how SurfaceView is captured.
     */
    fun frameStats(): Triple<Int, Int, Int>? {
        val s = player.media?.stats ?: return null
        return Triple(s.decodedVideo, s.displayedPictures, s.lostPictures)
    }

    /** Whether a view is attached to VLC's video output now. */
    fun hasView(): Boolean = viewAttached

    /** The picture's size as VLC laid it out (sample aspect applied); null before it is known. */
    private var shownSize: Pair<Int, Int>? = null

    /** An MPEG file's shape from its own header ([MpegSniff]), off the main thread. */
    private fun sniffSize(t: Track) {
        sniffer.execute {
            val size = MpegSniff.shownSize(java.io.File(t.path)) ?: return@execute
            main.post {
                if (track !== t || !loaded || shownSize != null) return@post
                shownSize = size
                events.onVideo(size.first, size.second)
            }
        }
    }

    /** The picture as it is shown (its sample aspect applied), or null before it is known. */
    fun videoSize(): Pair<Int, Int>? {
        shownSize?.let { return it }
        val t = player.currentVideoTrack ?: return null
        if (t.width <= 0 || t.height <= 0) return null
        val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toDouble() / t.sarDen else 1.0
        return (t.width * sar).toInt() to t.height
    }

    /** The picture's codec, four letters ("av01", "mpgv"…), for the no-AV1 rule. */
    fun videoCodec(): String? = media?.let { m ->
        (0 until m.trackCount).mapNotNull { m.getTrack(it) }.firstOrNull { it.type == IMedia.Track.Type.Video }?.codec
    }

    // ------------------------------------------------------------ tracks

    /** Sound tracks or subtitles: VLC's id and its name; -1 is "off" for subtitles. */
    data class Choice(val id: Int, val words: String, val selected: Boolean)

    fun audioTracks(): List<Choice> = (player.audioTracks ?: emptyArray())
        .filter { it.id >= 0 }.map { Choice(it.id, it.name, it.id == player.audioTrack) }

    fun subtitleTracks(): List<Choice> = (player.spuTracks ?: emptyArray())
        .filter { it.id >= 0 }.map { Choice(it.id, it.name, it.id == player.spuTrack) }

    fun chooseAudio(id: Int) { player.audioTrack = id }
    fun chooseSubtitles(id: Int) { player.spuTrack = id }

    // ------------------------------------------------------------ what the file is

    /** kbps, kHz and channels of the sound track, as far as VLC says. */
    fun soundFacts(): Triple<Int?, Int?, Int?> {
        val m = media ?: return Triple(null, null, null)
        val a = (0 until m.trackCount).mapNotNull { m.getTrack(it) }.filterIsInstance<IMedia.AudioTrack>().firstOrNull()
            ?: return Triple(null, null, null)
        return Triple(a.bitrate.takeIf { it > 0 }?.let { (it + 500) / 1000 }, a.rate.takeIf { it > 0 }?.let { (it + 500) / 1000 },
                      a.channels.takeIf { it > 0 })
    }

    /** The file's own title, artist and album, as far as VLC read them. */
    fun tags(): Triple<String?, String?, String?> {
        val m = media ?: return Triple(null, null, null)
        return Triple(m.getMeta(IMedia.Meta.Title), m.getMeta(IMedia.Meta.Artist), m.getMeta(IMedia.Meta.Album))
    }

    fun release() {
        attach(null)
        stop()
        player.setEventListener(null)
        player.release()
    }

    companion object {
        private const val TAG = "KioskVlc"
        /** MPEG-1/2 program streams, whose shape [MpegSniff] reads. */
        private val MPEG_KINDS = setOf("dat", "mpg", "mpeg", "vob")
        private val sniffer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-vlc-sniff") }
        /** Another app's sound that lets others duck: VLC plays at this share of its volume (Media3's is 0.2). */
        private const val DUCK = 0.2f
        /** A start this close to where it was asked counts as there. */
        private const val START_NEAR_MS = 3_000L
        private const val MAX_SEEK_TRIES = 5

        @Volatile private var shared: LibVLC? = null

        /** The app's one LibVLC. Subtitles next to a video are picked up; nothing is recorded or streamed out. */
        fun lib(context: Context): LibVLC = shared ?: synchronized(this) {
            shared ?: LibVLC(context.applicationContext, arrayListOf("--no-stats", "--audio-time-stretch")).also { shared = it }
        }
    }
}
