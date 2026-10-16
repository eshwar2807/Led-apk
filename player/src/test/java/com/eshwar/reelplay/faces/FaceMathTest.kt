package com.eshwar.reelplay.faces

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FaceMathTest {

    private fun apply(m: FloatArray, x: Float, y: Float) =
        floatArrayOf(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])

    @Test
    fun similarity_undoesARotatedScaledShiftedFace() {
        // The template, turned 20°, made 3× bigger and moved: as a tilted face in a photo.
        val angle = Math.toRadians(20.0)
        val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
        val photo = FloatArray(10)
        for (i in 0 until 5) {
            val x = FaceMath.TEMPLATE[2 * i]; val y = FaceMath.TEMPLATE[2 * i + 1]
            photo[2 * i] = 3 * (c * x - s * y) + 400
            photo[2 * i + 1] = 3 * (s * x + c * y) + 250
        }
        val m = FaceMath.similarity(photo, FaceMath.TEMPLATE)
        for (i in 0 until 5) {
            val p = apply(m, photo[2 * i], photo[2 * i + 1])
            assertEquals(FaceMath.TEMPLATE[2 * i], p[0], 0.01f)
            assertEquals(FaceMath.TEMPLATE[2 * i + 1], p[1], 0.01f)
        }
    }

    private fun unit(rnd: Random, dim: Int = 128) = FaceMath.normalize(FloatArray(dim) { rnd.nextFloat() * 2 - 1 })

    /** A face of the person [base]: their look plus [noise] worth of lighting, angle, expression. */
    private fun faceOf(base: FloatArray, rnd: Random, noise: Float) =
        FaceMath.normalize(FloatArray(base.size) { base[it] + (rnd.nextFloat() * 2 - 1) * noise })

    @Test
    fun cluster_separatesThreePeople() {
        val rnd = Random(7)
        val people = List(3) { unit(rnd) }
        val faces = ArrayList<FloatArray>(); val who = ArrayList<Int>()
        repeat(60) { val p = it % 3; faces += faceOf(people[p], rnd, 0.12f); who += p }
        val groups = FaceMath.cluster(faces, List(faces.size) { 1f })
        assertEquals(3, groups.size)
        for (g in groups) {
            assertEquals(20, g.size)
            assertEquals(1, g.map { who[it] }.distinct().size)
        }
    }

    @Test
    fun cluster_emptyAndSingle() {
        assertEquals(emptyList<List<Int>>(), FaceMath.cluster(emptyList(), emptyList()))
        assertEquals(listOf(listOf(0)), FaceMath.cluster(listOf(unit(Random(1))), listOf(1f)))
    }

    @Test
    fun dedupe_keepsTheClearestFaceOfEachPerson() {
        val rnd = Random(3)
        val a = unit(rnd); val b = unit(rnd)
        val faces = listOf(faceOf(a, rnd, 0.05f), faceOf(b, rnd, 0.05f), faceOf(a, rnd, 0.05f))
        assertEquals(listOf(2, 1), FaceMath.dedupe(faces, listOf(1f, 2f, 5f)))
    }

    @Test
    fun bytes_roundTrip() {
        val v = floatArrayOf(0.5f, -1.25f, 3e-7f, 0f)
        assertArrayEquals(v, FaceMath.fromBytes(FaceMath.toBytes(v)), 0f)
    }
}
