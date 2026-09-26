package com.mammonrn.phoneaikiosk.media

/**
 * Whether the home screen's shared "now playing" card shows, and which of
 * music and radio it shows (Poom): the card is shown while music OR radio is
 * PLAYING, or has been PAUSED for at most [PAUSE_HOLD_MS] — then it hides
 * itself, whether or not anybody touches it. No Android in here, so the
 * timing is unit-tested without a phone (NowPlayingCardTest); MainActivity
 * feeds it MusicPlayer's and RadioPlayer's own state every tick and draws
 * whatever it returns.
 *
 * ONE SHARED CARD, because music and radio never play at once (an existing
 * rule — starting one only PAUSES the other, WakePause-style, rather than
 * stopping it), so at most one of the two is ever actually playing. Radio has
 * no pause of its own (a live stream has nothing to resume from): its
 * [Playback] is PLAYING while it plays or is connecting, and STOPPED the
 * moment it is not — it never reports PAUSED, so [PAUSE_HOLD_MS] never
 * applies to it.
 */
object NowPlayingCard {

    /** How long a paused player still holds the card open before it hides itself. */
    const val PAUSE_HOLD_MS = 10 * 60 * 1000L

    enum class Source { MUSIC, RADIO }

    enum class Playback { PLAYING, PAUSED, STOPPED }

    /**
     * One player's state, as far as this card cares. [pausedAtMs] is the
     * clock reading (elapsedRealtime) the player was LAST SEEN entering
     * PAUSED — null while it is not paused right now, or while a caller has
     * not started tracking it. See [pausedAt].
     */
    data class State(val playback: Playback, val pausedAtMs: Long?)

    /** Whether [state], alone, would keep the card showing at [nowMs]. */
    fun visible(state: State, nowMs: Long): Boolean = when (state.playback) {
        Playback.PLAYING -> true
        Playback.PAUSED -> state.pausedAtMs == null || nowMs - state.pausedAtMs <= PAUSE_HOLD_MS
        Playback.STOPPED -> false
    }

    /**
     * Which source the shared card shows at [nowMs], or null for neither.
     *
     * A source actually PLAYING always wins over one merely paused within its
     * hold: switching to the radio pauses (never stops) the music, so for up
     * to [PAUSE_HOLD_MS] both could independently qualify by [visible] — the
     * card must show only what is really playing, never the one waiting to
     * resume. Between two merely-paused sources (music has no pause partner
     * that plays at the same time as it is paused, so this should not happen
     * in practice) music wins, arbitrarily but deterministically.
     */
    fun pick(music: State, radio: State, nowMs: Long): Source? = when {
        music.playback == Playback.PLAYING -> Source.MUSIC
        radio.playback == Playback.PLAYING -> Source.RADIO
        visible(music, nowMs) -> Source.MUSIC
        visible(radio, nowMs) -> Source.RADIO
        else -> null
    }

    /**
     * The [State.pausedAtMs] to keep for the next tick: [nowMs] the moment
     * [playback] is first seen PAUSED, held steady while it stays PAUSED, and
     * cleared the moment it is not. The caller (MainActivity) keeps the
     * result and passes it back in as [previous] on the next tick — this is
     * the only place "how long has it been paused" is measured from.
     */
    fun pausedAt(playback: Playback, previous: Long?, nowMs: Long): Long? =
        if (playback == Playback.PAUSED) previous ?: nowMs else null
}
