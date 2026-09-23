package com.mammonrn.phoneaikiosk.auth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * SFace on ONNX Runtime: an aligned 112×112 face in (FaceMath.toInput), 128
 * normalised numbers out. The same runtime the wake word already ships, so no
 * new native library. Built from bytes so FaceEmbedderTest runs the real model
 * on the JVM with the same runtime version.
 */
class FaceEmbedder(private val environment: OrtEnvironment, private val session: OrtSession) :
    AutoCloseable {

    private val input = session.inputNames.first()

    fun embed(chw: FloatArray): FloatArray {
        require(chw.size == 3 * FaceMath.SIZE * FaceMath.SIZE)
        val shape = longArrayOf(1, 3, FaceMath.SIZE.toLong(), FaceMath.SIZE.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(chw), shape).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = (result[0].value as Array<FloatArray>)[0]
                return FaceMath.normalize(out)
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val ASSET = "models/face_recognition_sface_2021dec_int8.onnx"

        fun fromBytes(model: ByteArray): FaceEmbedder {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            return FaceEmbedder(environment, environment.createSession(model, options))
        }

        fun fromAssets(context: Context): FaceEmbedder? = runCatching {
            fromBytes(context.assets.open(ASSET).use { it.readBytes() })
        }.getOrNull()
    }
}
