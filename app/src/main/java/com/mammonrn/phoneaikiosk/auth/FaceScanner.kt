package com.mammonrn.phoneaikiosk.auth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

/**
 * One camera frame in, what it shows out: how many faces, which tracked face,
 * how open its eyes are, and — when the eyes are open — its 128 numbers.
 *
 * ML Kit (on the phone, bundled model) finds the face, five landmarks and the
 * eye-open probabilities; the frame is then warped so the landmarks sit on
 * SFace's template (FaceMath) and handed to [embedder]. THE FRAME IS NOT
 * KEPT: the caller recycles it, the aligned 112×112 crop is recycled here, and
 * nothing in this class writes a file or holds a bitmap past [scan].
 *
 * Runs on the camera's analysis thread (ML Kit's Task is awaited there).
 */
class FaceScanner(private val embedder: FaceEmbedder) : AutoCloseable {

    data class Seen(
        val faces: Int,
        val trackingId: Int? = null,
        val leftOpen: Float? = null,
        val rightOpen: Float? = null,
        /** Null unless one face with both eyes open and all five landmarks. */
        val embedding: FloatArray? = null,
    )

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            // A face filling at least a fifth of the frame: close enough to
            // be recognised, and not someone walking past behind.
            .setMinFaceSize(0.2f)
            .enableTracking()
            .build(),
    )

    /** [upright] must already be rotated so the face is the right way up. */
    fun scan(upright: Bitmap): Seen {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(upright, 0)))
        if (faces.size != 1) return Seen(faces.size)
        val face = faces[0]
        val left = face.leftEyeOpenProbability
        val right = face.rightEyeOpenProbability
        val seen = Seen(1, face.trackingId, left, right)
        if (left == null || right == null ||
            left < BlinkCheck.OPEN || right < BlinkCheck.OPEN) return seen

        // OpenCV's order, by position in the image: the eye on the image's
        // left is the subject's RIGHT eye (the camera frame is not mirrored).
        val order = intArrayOf(FaceLandmark.RIGHT_EYE, FaceLandmark.LEFT_EYE, FaceLandmark.NOSE_BASE,
                               FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_LEFT)
        val points = order.map { type ->
            val p = face.getLandmark(type)?.position ?: return seen
            floatArrayOf(p.x, p.y)
        }.toTypedArray()

        val crop = align(upright, points)
        try {
            val pixels = IntArray(FaceMath.SIZE * FaceMath.SIZE)
            crop.getPixels(pixels, 0, FaceMath.SIZE, 0, 0, FaceMath.SIZE, FaceMath.SIZE)
            return seen.copy(embedding = embedder.embed(FaceMath.toInput(pixels)))
        } finally {
            crop.recycle()
        }
    }

    private fun align(source: Bitmap, points: Array<FloatArray>): Bitmap {
        val t = FaceMath.similarity(points)
        val matrix = Matrix().apply {
            setValues(floatArrayOf(t[0], -t[1], t[2], t[1], t[0], t[3], 0f, 0f, 1f))
        }
        val out = Bitmap.createBitmap(FaceMath.SIZE, FaceMath.SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    override fun close() {
        detector.close()
        embedder.close()
    }
}
