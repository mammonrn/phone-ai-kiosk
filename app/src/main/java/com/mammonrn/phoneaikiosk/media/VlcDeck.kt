package com.mammonrn.phoneaikiosk.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
 */
class VlcDeck(context: Context, private val events: Events) {

    interface Events {
        fun onPlaying()
        fun onPaused()
        fun onEnded()
        fun onError()
        /** The picture's size is known (video only). */
        fun onVideo(width: Int, height: Int) {}
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
                MediaPlayer.Event.Playing -> { playing = true; events.onPlaying() }
                MediaPlayer.Event.Paused -> { playing = false; events.onPaused() }
                MediaPlayer.Event.EndReached -> { playing = false; main.post { events.onEnded() } }
                MediaPlayer.Event.EncounteredError -> { playing = false; main.post { events.onError() } }
                MediaPlayer.Event.Vout -> if (e.voutCount > 0) videoSize()?.let { (w, h) -> events.onVideo(w, h) }
                MediaPlayer.Event.ESAdded -> videoSize()?.let { (w, h) -> events.onVideo(w, h) }
            }
        }
    }

    /** Opens [track] at [startMs]; plays when [play]. */
    fun load(track: Track, startMs: Long, play: Boolean) {
        stop()
        val m = if (track.onNas) nasMedia(track) else Media(lib(app), Uri.fromFile(java.io.File(track.path)))
        if (startMs > 0) m.addOption(":start-time=" + String.format(java.util.Locale.US, "%.3f", startMs / 1000.0))
        // Kept (and released at stop): the player's getMedia() would take a reference each time it is asked.
        media = m
        player.media = m
        loaded = true
        // Not playing: nothing is started — VLC has no "prepare" — and play() starts it at [startMs].
        if (play) player.play()
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

    fun play() { if (loaded) player.play() }
    fun pause() { if (player.isPlaying) player.pause() }

    fun stop() {
        if (loaded) player.stop()
        loaded = false
        playing = false
        media?.release()
        media = null
    }

    val positionMs: Long get() = if (loaded) player.time.coerceAtLeast(0) else 0
    val durationMs: Long get() = if (loaded) player.length.coerceAtLeast(0) else 0

    fun seekTo(ms: Long) { if (loaded) player.time = ms.coerceAtLeast(0) }

    /** 0..1, as the players' own volume. */
    fun setVolume(v: Float) { player.volume = (v.coerceIn(0f, 1f) * 100).toInt() }

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
            vout.setWindowSize(if (surface.width > 0) surface.width else m.widthPixels,
                               if (surface.height > 0) surface.height else m.widthPixels * 9 / 16)
            vout.attachViews()
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
    fun resized(width: Int, height: Int) { if (viewAttached && width > 0) player.vlcVout.setWindowSize(width, height) }

    /** The picture as it is shown (its sample aspect applied), or null before it is known. */
    fun videoSize(): Pair<Int, Int>? {
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
        @Volatile private var shared: LibVLC? = null

        /** The app's one LibVLC. Subtitles next to a video are picked up; nothing is recorded or streamed out. */
        fun lib(context: Context): LibVLC = shared ?: synchronized(this) {
            shared ?: LibVLC(context.applicationContext, arrayListOf("--no-stats", "--audio-time-stretch")).also { shared = it }
        }
    }
}
