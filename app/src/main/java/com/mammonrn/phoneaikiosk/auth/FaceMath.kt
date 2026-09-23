package com.mammonrn.phoneaikiosk.auth

import kotlin.math.sqrt

/**
 * The arithmetic of face recognition, with nothing Android in it, so every
 * piece is tested on the JVM (FaceMathTest).
 *
 * THE MODEL IS SFace (OpenCV Zoo, `face_recognition_sface_2021dec_int8.onnx`,
 * Apache-2.0 — licenses/SFACE-LICENSE). It turns a 112×112 face into 128
 * numbers; two faces of the same person give numbers pointing the same way.
 * The input it expects is exactly OpenCV's FaceRecognizerSF.alignCrop +
 * feature(), read from opencv/modules/objdetect/src/face_recognize.cpp:
 *
 *  * the face warped by a SIMILARITY transform (rotation, one scale, shift) so
 *    five points land on [TEMPLATE] — the eyes, the nose, the mouth corners;
 *  * 112×112, RGB order, values 0–255 as floats, no mean, no scaling, CHW.
 */
object FaceMath {

    const val SIZE = 112
    const val EMBEDDING = 128

    /**
     * Where the five points go in the 112×112 crop, in OpenCV's order: the eye
     * on the image's LEFT, the eye on its right, the nose, the mouth corner on
     * the left, the one on the right (face_recognize.cpp, `dst`).
     */
    val TEMPLATE = arrayOf(
        floatArrayOf(38.2946f, 51.6963f), floatArrayOf(73.5318f, 51.5014f),
        floatArrayOf(56.0252f, 71.7366f),
        floatArrayOf(41.5493f, 92.3655f), floatArrayOf(70.7299f, 92.2041f),
    )

    /**
     * The least-squares similarity transform taking [src] onto [dst]:
     * `x' = a·x − b·y + tx`, `y' = b·x + a·y + ty`, returned as
     * [a, b, tx, ty]. For five points this is Umeyama's answer without a
     * reflection — the one OpenCV computes with an SVD — in closed form.
     */
    fun similarity(src: Array<FloatArray>, dst: Array<FloatArray> = TEMPLATE): FloatArray {
        require(src.size == dst.size && src.size >= 2)
        val n = src.size
        var sx = 0.0; var sy = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until n) { sx += src[i][0]; sy += src[i][1]; dx += dst[i][0]; dy += dst[i][1] }
        sx /= n; sy /= n; dx /= n; dy /= n
        var num1 = 0.0; var num2 = 0.0; var den = 0.0
        for (i in 0 until n) {
            val x = src[i][0] - sx; val y = src[i][1] - sy
            val u = dst[i][0] - dx; val v = dst[i][1] - dy
            num1 += x * u + y * v
            num2 += x * v - y * u
            den += x * x + y * y
        }
        require(den > 1e-9) { "the points are all in one place" }
        val a = num1 / den
        val b = num2 / den
        val tx = dx - (a * sx - b * sy)
        val ty = dy - (b * sx + a * sy)
        return floatArrayOf(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat())
    }

    /** Applies [similarity]'s result to one point. */
    fun apply(t: FloatArray, x: Float, y: Float): FloatArray =
        floatArrayOf(t[0] * x - t[1] * y + t[2], t[1] * x + t[0] * y + t[3])

    /**
     * ARGB pixels of the aligned 112×112 crop into the model's input: CHW,
     * red plane first, 0–255 floats.
     */
    fun toInput(argb: IntArray): FloatArray {
        require(argb.size == SIZE * SIZE)
        val plane = SIZE * SIZE
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = argb[i]
            out[i] = ((p shr 16) and 0xFF).toFloat()
            out[plane + i] = ((p shr 8) and 0xFF).toFloat()
            out[2 * plane + i] = (p and 0xFF).toFloat()
        }
        return out
    }

    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum).toFloat()
        require(norm > 0f) { "an empty embedding" }
        return FloatArray(v.size) { v[it] / norm }
    }

    /** Cosine similarity of two normalised embeddings: 1 is the same face. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    /** The best match of [probe] against every enrolled template. */
    fun bestScore(probe: FloatArray, templates: List<FloatArray>): Float =
        templates.maxOfOrNull { cosine(probe, it) } ?: -1f
}
