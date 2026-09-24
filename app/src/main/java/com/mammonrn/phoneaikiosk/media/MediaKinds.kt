package com.mammonrn.phoneaikiosk.media

import java.util.Locale

/**
 * What a file is, by its extension (0.59.0, Poom): the one list the file
 * manager, the folder picker and both players read. A file is known as music
 * or video here even when no player on the phone plays it yet — the file
 * manager shows it as what it is, a playlist may hold it, and playing it says
 * "not supported yet" ([PlayerChoice]) instead of hanging.
 *
 * Plain Kotlin; MediaKindsTest.
 */
object MediaKinds {

    enum class Kind { AUDIO, VIDEO, IMAGE, OTHER }

    /** Poom's list for 0.59.0, with what 0.60.0 will add. ALAC is m4a on disk; "alac" is its rare own extension. */
    val AUDIO = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "alac")
    val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "mpg", "mpeg", "dat", "vob", "ts", "wmv", "flv")
    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

    /** "flac" from "10 ทิ้งรักลงแม่น้ำ .flac": a space before the dot is part of the name, not the extension. */
    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun kindOf(name: String): Kind = when (extension(name)) {
        in AUDIO -> Kind.AUDIO
        in VIDEO -> Kind.VIDEO
        in IMAGE -> Kind.IMAGE
        else -> Kind.OTHER
    }

    fun isAudio(name: String) = kindOf(name) == Kind.AUDIO
    fun isVideo(name: String) = kindOf(name) == Kind.VIDEO
}

/**
 * THE ONE PLACE A PLAYER IS CHOSEN FOR A FILE (0.59.0). Both services ask
 * here before they load anything; 0.60.0 adds LibVLC as [Engine] for what
 * Media3 cannot play, here and nowhere else.
 *
 * What Media3 plays on the A07 was measured (codecprobe, 2026-09-24):
 * no MPEG-1/2 video decoder on the phone, no ASF (WMV/WMA) reader in Media3.
 * So .mpg .mpeg .dat .vob .wmv and .wma are "not yet" by name, before anything
 * is opened. .ts, .flv and .avi hold many codecs: Media3 is given them, and a
 * file whose picture or sound it cannot decode is caught when its tracks are
 * known (VideoService / MusicService) and said to be not supported — never a
 * silent clock, never a hang.
 */
object PlayerChoice {

    enum class Engine { MEDIA3, NOT_YET }

    private val MEDIA3_AUDIO = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "alac")
    private val MEDIA3_VIDEO = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "ts", "flv")

    fun forName(name: String): Engine = when (MediaKinds.extension(name)) {
        in MEDIA3_AUDIO, in MEDIA3_VIDEO -> Engine.MEDIA3
        else -> Engine.NOT_YET
    }

    fun playsAudio(name: String) = MediaKinds.isAudio(name) && forName(name) == Engine.MEDIA3
    fun playsVideo(name: String) = MediaKinds.isVideo(name) && forName(name) == Engine.MEDIA3

    /** "DAT", "WMA": the word a person knows the file by, for "not supported yet". */
    fun typeWord(name: String): String = MediaKinds.extension(name).uppercase(Locale.ROOT).ifEmpty { "?" }
}
