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
 * THE ONE PLACE A PLAYER IS CHOSEN FOR A FILE (0.59.0; LibVLC since 0.60.0,
 * Poom's choice after codecprobe). Both services ask here before they load
 * anything, and nowhere else decides.
 *
 * MEDIA3 IS THE PLAYER; LibVLC plays only what Media3 cannot (Poom: "ไฟล์ที่
 * Media3 เล่นได้อยู่แล้ว ห้ามย้ายไป VLC"), because only Media3 feeds our own
 * equalizer and the bars. Measured on the A07 (codecprobe, 2026-09-24): no
 * MPEG-1/2 video decoder on the phone, no ASF (WMV/WMA) reader in Media3, no
 * ALAC decoder, and FLV/TS with the codecs they usually carry do not play. So:
 *
 *  * by name: .dat .mpg .mpeg .vob .ts .wmv .wma .flv (and the rare .alac) → VLC;
 *  * .m4a by what is inside ([codec], Mp4Sniff): "alac" → VLC, AAC → Media3;
 *  * everything else Media3 — and a file Media3 then turns out not to decode
 *    is handed to LibVLC by the service, once, rather than skipped.
 */
object PlayerChoice {

    enum class Engine { MEDIA3, VLC, NOT_YET }

    private val VLC_BY_NAME = setOf("dat", "mpg", "mpeg", "vob", "ts", "wmv", "wma", "flv", "alac")
    private val MEDIA3 = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi")

    /** [codec] is asked only for an .m4a: the four letters of its sound track ("alac", "mp4a"), or null. */
    fun forFile(name: String, codec: () -> String?): Engine = when (MediaKinds.extension(name)) {
        in VLC_BY_NAME -> Engine.VLC
        "m4a" -> if (codec() == "alac") Engine.VLC else Engine.MEDIA3
        in MEDIA3 -> Engine.MEDIA3
        else -> Engine.NOT_YET
    }

    /** By name alone (an .m4a counts as Media3's until it is looked into). */
    fun forName(name: String): Engine = forFile(name) { null }

    /** A player here plays it, one or the other. */
    fun playsAudio(name: String) = MediaKinds.isAudio(name) && forName(name) != Engine.NOT_YET
    fun playsVideo(name: String) = MediaKinds.isVideo(name) && forName(name) != Engine.NOT_YET

    /** "DAT", "WMA": the word a person knows the file by. */
    fun typeWord(name: String): String = MediaKinds.extension(name).uppercase(Locale.ROOT).ifEmpty { "?" }
}
