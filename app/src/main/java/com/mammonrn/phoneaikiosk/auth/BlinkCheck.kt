package com.mammonrn.phoneaikiosk.auth

/**
 * "Is this a live person, and the right one?" — one camera frame at a time.
 *
 * THE RULE: one face, eyes open and recognised; then both eyes shut; then open
 * and recognised again — all on the SAME tracked face. A printed photo does
 * not blink, and swapping a photo in after a real blink changes the tracking
 * id and starts the check over. It is a basic check, as Poom asked: a video of
 * the enrolled person blinking could still pass (DESIGN.md, "ยืนยันตัวตน").
 *
 * Pure, and fed by VerifyActivity: whether a frame's face "matches" is decided
 * there (the enrolled templates, or during enrolment the first capture) and
 * passed in as [Frame.matches]. BlinkCheckTest drives it with made-up frames.
 */
class BlinkCheck(
    private val openAt: Float = OPEN,
    private val shutAt: Float = SHUT,
    private val mismatchLimit: Int = MISMATCH_LIMIT,
) {

    /** What the camera saw in one frame. Probabilities are ML Kit's, 0–1. */
    data class Frame(
        val faces: Int,
        val trackingId: Int? = null,
        val leftOpen: Float? = null,
        val rightOpen: Float? = null,
        /** Null when this frame was not compared (eyes shut, or no face). */
        val matches: Boolean? = null,
    )

    enum class Step {
        /** No face, or more than one. */
        FIND_FACE,
        /** A face; waiting for a recognised frame with the eyes open. */
        LOOK,
        /** Recognised with eyes open: now blink. */
        BLINK,
        /** Eyes shut seen: open them again. */
        REOPEN,
        /** Live and recognised. */
        PASSED,
        /** The face kept not matching: someone else, or too far from the enrolment. */
        NOT_RECOGNISED,
    }

    var step = Step.FIND_FACE
        private set

    private var track: Int? = null
    private var mismatches = 0

    fun feed(frame: Frame): Step {
        if (step == Step.PASSED || step == Step.NOT_RECOGNISED) return step
        if (frame.faces != 1) {
            restart(); return step
        }
        if (frame.trackingId != track) {
            // A different face, or the tracker lost this one: nothing seen so
            // far counts for the new one.
            restart()
            track = frame.trackingId
            step = Step.LOOK
        }
        if (frame.matches == false) {
            mismatches++
            if (mismatches >= mismatchLimit) step = Step.NOT_RECOGNISED
            else if (step == Step.BLINK || step == Step.REOPEN) step = Step.LOOK
            return step
        }
        val left = frame.leftOpen ?: return step
        val right = frame.rightOpen ?: return step
        val open = left >= openAt && right >= openAt
        val shut = left <= shutAt && right <= shutAt
        step = when (step) {
            Step.LOOK -> if (open && frame.matches == true) Step.BLINK else Step.LOOK
            Step.BLINK -> if (shut) Step.REOPEN else Step.BLINK
            Step.REOPEN -> if (open && frame.matches == true) Step.PASSED else Step.REOPEN
            else -> step
        }
        return step
    }

    private fun restart() {
        track = null
        step = Step.FIND_FACE
    }

    companion object {
        /** ML Kit's eye-open probability counted as open, and as shut. */
        const val OPEN = 0.7f
        const val SHUT = 0.3f
        /** Compared frames that must fail before the face is refused. */
        const val MISMATCH_LIMIT = 8
    }
}
