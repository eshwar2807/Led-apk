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

    /** A face in a photo: the template turned, scaled and moved. */
    private fun photoFace(deg: Double = 15.0): FloatArray {
        val c = cos(Math.toRadians(deg)).toFloat(); val s = sin(Math.toRadians(deg)).toFloat()
        return FloatArray(10) { i ->
            val x = FaceMath.TEMPLATE[i - i % 2]; val y = FaceMath.TEMPLATE[i - i % 2 + 1]
            if (i % 2 == 0) 2.5f * (c * x - s * y) + 300 else 2.5f * (s * x + c * y) + 120
        }
    }

    @Test
    fun align_fitsAFaceWhicheverWayLeftAndRightAreNamed() {
        val face = photoFace()
        // The same points with the eyes and mouth corners named the other way round.
        val mirrored = FloatArray(10)
        for ((to, from) in intArrayOf(1, 0, 2, 4, 3).withIndex()) {
            mirrored[2 * to] = face[2 * from]; mirrored[2 * to + 1] = face[2 * from + 1]
        }
        for (points in listOf(face, mirrored)) {
            val m = FaceMath.align(points)!!
            assertEquals(0f, FaceMath.residual(face, m), 0.05f)
            // Scale back to template size (the face was 2.5× bigger), not shrunk to a blob.
            assertEquals(0.4f, kotlin.math.hypot(m[0], m[3]), 0.01f)
        }
    }

    @Test
    fun align_rejectsPointsThatArentAFace() {
        // The eyes far apart and the "mouth" up between them: no turn or size makes that a face.
        val junk = floatArrayOf(0f, 0f, 200f, 0f, 100f, 100f, 90f, 0f, 110f, 0f)
        assertEquals(null, FaceMath.align(junk))
    }

    /**
     * A library of look-alike people, as the model sees a family's children: different people
     * score about 0.4 against each other, the same person about 0.55. Grouping on raw scores
     * lumped them all together; measured from the library's average face they come apart.
     */
    @Test
    fun cluster_centred_separatesLookAlikes() {
        val rnd = java.util.Random(11)
        fun gauss() = FloatArray(128) { rnd.nextGaussian().toFloat() }
        fun mix(a: FloatArray, wa: Float, b: FloatArray, wb: Float) =
            FaceMath.normalize(FloatArray(128) { wa * a[it] + wb * b[it] })
        val shared = FaceMath.normalize(gauss())
        val looks = List(5) { mix(shared, kotlin.math.sqrt(0.7f), FaceMath.normalize(gauss()), kotlin.math.sqrt(0.3f)) }
        val sizes = listOf(80, 40, 20, 10, 10)
        val faces = ArrayList<FloatArray>(); val who = ArrayList<Int>()
        for ((p, n) in sizes.withIndex()) repeat(n) {
            faces += mix(looks[p], kotlin.math.sqrt(0.55f), FaceMath.normalize(gauss()), kotlin.math.sqrt(0.45f)); who += p
        }
        val quality = List(faces.size) { rnd.nextFloat() }

        // The old way: raw scores, everyone in one group.
        val raw = FaceMath.cluster(faces, quality, join = 0.40f, merge = 0.50f)
        assertEquals(faces.size, raw.maxOf { it.size })

        val groups = FaceMath.people(faces, quality).first
        // Every group is (almost) one person...
        val pure = groups.sumOf { g -> g.groupingBy { who[it] }.eachCount().values.max() }
        assertEquals(1.0, pure.toDouble() / faces.size, 0.03)
        // ...and the person in most of the photos doesn't fall apart. (Thresholds are set on
        // real faces, where people stay much more together than in this random simulation;
        // splits are fixed by giving both parts the same name.)
        val best = groups.maxOf { g -> g.count { who[it] == 0 } }
        assert(best >= sizes[0] / 4) { "the most frequent person's biggest group has $best of ${sizes[0]}" }
    }
}
