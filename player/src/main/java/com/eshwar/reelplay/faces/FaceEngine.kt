package com.eshwar.reelplay.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Finds faces in a picture and describes each as 128 numbers that are close together for the
 * same person and far apart for different people. Everything runs on the phone: ML Kit's
 * bundled detector finds the faces and their eyes, nose and mouth; MobileFaceNet (Apache 2.0,
 * in assets/) turns each lined-up face into its numbers. Not thread-safe: one per worker.
 */
class FaceEngine(context: Context) : Closeable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.06f)
            .build(),
    )

    private val model = Interpreter(loadModel(context), Interpreter.Options().setNumThreads(2))
    private val in1 = model.getInputIndex("img1")
    private val in2 = model.getInputIndex("img2")
    private val inputs = arrayOf<Any>(input(), input())
    private val output = Array(2) { FloatArray(EMBEDDING) }
    private val face = createBitmap(FaceMath.SIZE, FaceMath.SIZE)
    private val canvas = Canvas(face)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pixels = IntArray(FaceMath.SIZE * FaceMath.SIZE)

    /** The usable faces in [picture]; [timeMs] is where in a video it came from. */
    fun faces(picture: Bitmap, timeMs: Long = 0): List<FoundFace> {
        val bmp = if (picture.config == Bitmap.Config.ARGB_8888) picture else picture.copy(Bitmap.Config.ARGB_8888, false)
        val found = try {
            Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
        } catch (_: Exception) {
            return emptyList()
        }
        val usable = found.mapNotNull { f -> landmarks(f)?.let { f to it } }
            .filter { (f, _) -> quality(f) > 0f }
        // The model takes two faces per run; pair them up.
        val out = ArrayList<FoundFace>()
        for (pair in usable.chunked(2)) {
            val crops = pair.mapIndexed { i, (_, points) -> align(bmp, points, inputs[i] as ByteBuffer) }
            if (pair.size == 1) {
                (inputs[1] as ByteBuffer).apply { clear(); put((inputs[0] as ByteBuffer).also { it.rewind() }); rewind() }
                (inputs[0] as ByteBuffer).rewind()
            }
            val ordered = arrayOfNulls<Any>(2).also { it[in1] = inputs[0]; it[in2] = inputs[1] }
            model.runForMultipleInputsOutputs(ordered, mapOf(0 to output))
            pair.forEachIndexed { i, (f, _) ->
                out += FoundFace(FaceMath.normalize(output[i].copyOf()), quality(f), timeMs, crops[i])
            }
        }
        if (bmp !== picture) bmp.recycle()
        return out
    }

    /** Eyes, nose and mouth corners in the template's order, or null if any is missing. */
    private fun landmarks(f: Face): FloatArray? {
        // ML Kit names them from the person's side: their right eye is on the image's left.
        val order = intArrayOf(
            FaceLandmark.RIGHT_EYE, FaceLandmark.LEFT_EYE, FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_LEFT,
        )
        val out = FloatArray(10)
        for ((i, type) in order.withIndex()) {
            val p = f.getLandmark(type)?.position ?: return null
            out[2 * i] = p.x
            out[2 * i + 1] = p.y
        }
        return out
    }

    /**
     * How useful a face is for telling who it is: bigger and more front-on is better. 0 for
     * faces too small or too turned away to recognise reliably.
     */
    private fun quality(f: Face): Float {
        val side = min(f.boundingBox.width(), f.boundingBox.height()).toFloat()
        val yaw = abs(f.headEulerAngleY)
        val pitch = abs(f.headEulerAngleX)
        if (side < MIN_FACE_PX || yaw > 40f || pitch > 30f) return 0f
        return side * (1f - yaw / 60f) * (1f - pitch / 50f)
    }

    /** Draws the face upright and to the template's size into [into]; returns a small JPEG of it. */
    private fun align(src: Bitmap, points: FloatArray, into: ByteBuffer): ByteArray {
        val m = FaceMath.similarity(points, FaceMath.TEMPLATE)
        val matrix = Matrix().apply { setValues(floatArrayOf(m[0], m[1], m[2], m[3], m[4], m[5], 0f, 0f, 1f)) }
        face.eraseColor(0xFF000000.toInt())
        canvas.drawBitmap(src, matrix, paint)
        face.getPixels(pixels, 0, FaceMath.SIZE, 0, 0, FaceMath.SIZE, FaceMath.SIZE)
        // Channels first (RGB planes), 0..1: the model does its own normalising.
        into.clear()
        val plane = FaceMath.SIZE * FaceMath.SIZE
        for (shift in intArrayOf(16, 8, 0)) {
            for (i in 0 until plane) into.putFloat(((pixels[i] shr shift) and 0xFF) / 255f)
        }
        into.rewind()
        return ByteArrayOutputStream().also { face.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    override fun close() {
        try { detector.close() } catch (_: Exception) {}
        model.close()
        face.recycle()
    }

    private companion object {
        const val EMBEDDING = 128
        const val MIN_FACE_PX = 40

        fun input(): ByteBuffer =
            ByteBuffer.allocateDirect(3 * FaceMath.SIZE * FaceMath.SIZE * 4).order(ByteOrder.nativeOrder())

        fun loadModel(context: Context): ByteBuffer {
            val bytes = context.assets.open("mobile_facenet.tflite").use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
    }
}
