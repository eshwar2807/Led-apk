package com.eshwar.reelplay.faces

import kotlin.math.sqrt

/**
 * The maths behind grouping faces, kept free of Android so it can be unit-tested: lining a face
 * up for the recognition model, and sorting face embeddings into people.
 */
object FaceMath {

    /** Model input: a 112×112 face. */
    const val SIZE = 112

    /**
     * Where the face recognition model expects the eyes, nose tip and mouth corners in its
     * 112×112 input (the standard ArcFace layout the model was trained on), as x,y pairs:
     * image-left eye, image-right eye, nose, image-left mouth corner, image-right mouth corner.
     */
    val TEMPLATE = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f,
    )

    /**
     * The rotation + uniform scale + shift that best maps the points [src] onto [dst] (both x,y
     * pairs), by least squares. Returns a row-major 2×3 affine matrix: a, −b, tx, b, a, ty.
     */
    fun similarity(src: FloatArray, dst: FloatArray): FloatArray {
        require(src.size == dst.size && src.size >= 4 && src.size % 2 == 0)
        val n = src.size / 2
        var sx = 0.0; var sy = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until n) {
            sx += src[2 * i]; sy += src[2 * i + 1]; dx += dst[2 * i]; dy += dst[2 * i + 1]
        }
        sx /= n; sy /= n; dx /= n; dy /= n
        var dot = 0.0; var cross = 0.0; var norm = 0.0
        for (i in 0 until n) {
            val x = src[2 * i] - sx; val y = src[2 * i + 1] - sy
            val u = dst[2 * i] - dx; val v = dst[2 * i + 1] - dy
            dot += x * u + y * v
            cross += x * v - y * u
            norm += x * x + y * y
        }
        if (norm == 0.0) return floatArrayOf(1f, 0f, (dx - sx).toFloat(), 0f, 1f, (dy - sy).toFloat())
        val a = dot / norm
        val b = cross / norm
        val tx = dx - (a * sx - b * sy)
        val ty = dy - (b * sx + a * sy)
        return floatArrayOf(a.toFloat(), (-b).toFloat(), tx.toFloat(), b.toFloat(), a.toFloat(), ty.toFloat())
    }

    /**
     * The matrix that lines up a face with [TEMPLATE], from its five points in template order,
     * or null if they don't look like a face. Detectors disagree on whether "left eye" means
     * the person's left or the picture's left; the wrong reading can't be fitted without a
     * mirror, so it shrinks the face to a tiny blob that matches everyone. Both readings are
     * tried and the one that fits is kept.
     */
    fun align(points: FloatArray): FloatArray? {
        val swapped = FloatArray(10)
        for ((to, from) in intArrayOf(1, 0, 2, 4, 3).withIndex()) {
            swapped[2 * to] = points[2 * from]
            swapped[2 * to + 1] = points[2 * from + 1]
        }
        val best = listOf(points, swapped).map { it to similarity(it, TEMPLATE) }
            .minBy { (p, m) -> residual(p, m) }
        return best.second.takeIf { residual(best.first, it) <= MAX_RESIDUAL }
    }

    /** How far, on average, the points land from the template's (in template pixels). */
    fun residual(points: FloatArray, m: FloatArray): Float {
        var sum = 0.0
        for (i in 0 until points.size / 2) {
            val x = points[2 * i]; val y = points[2 * i + 1]
            val dx = m[0] * x + m[1] * y + m[2] - TEMPLATE[2 * i]
            val dy = m[3] * x + m[4] * y + m[5] - TEMPLATE[2 * i + 1]
            sum += dx * dx + dy * dy
        }
        return sqrt(sum / (points.size / 2)).toFloat()
    }

    /** Real faces fit within a few pixels; a mirrored reading misses by 20 or more. */
    private const val MAX_RESIDUAL = 8f

    /** The length of a raw model output: how confidently the model saw a face it can tell apart. */
    fun strength(v: FloatArray): Float = sqrt(dot(v, v))

    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }

    /** Cosine similarity of two unit vectors: 1 for the same direction, about 0 for unrelated. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /**
     * Thresholds for [cluster] on faces measured from the library's average face ([centred]).
     * In raw form every face in one library shares a lot (the same family, children's features,
     * lighting, video quality), so different people often score 0.3–0.45 against each other,
     * barely below the same person's 0.45–0.6, and fixed raw thresholds lumped everyone into one
     * group. With the shared part taken away, different people land around 0 or below and the
     * same person stays clearly positive. Checked on 360 real photos of 30 Indian film actors:
     * at 0.40/0.60 every group was one person (0.30/0.50 let 4–5% in from look-alikes, which
     * showed up on a phone as other children joining a child's group). The cost is that a
     * person can come out as two groups, which naming both the same joins up.
     */
    const val JOIN = 0.40f

    /** Two groups whose average (centred) faces are this alike are the same person. */
    const val MERGE = 0.60f

    /**
     * Sorts a whole library's unit [embeddings] into people: groups of indices, biggest first,
     * plus the average face they were measured from (for matching saved names later).
     *
     * The average is worked out per person, not per face: a plain average leans towards whoever
     * is in most of the photos, and measured from there that person's own faces look unrelated
     * and fall apart into many groups. So it groups once from the plain average, then again
     * from the average of the people found, twice over.
     */
    fun people(embeddings: List<FloatArray>, quality: List<Float>): Pair<List<List<Int>>, FloatArray> {
        if (embeddings.isEmpty()) return emptyList<List<Int>>() to FloatArray(0)
        val plain = mean(embeddings)
        val length = sqrt(dot(plain, plain))
        var average = plain
        repeat(2) {
            val groups = cluster(embeddings.map { centred(it, average) }, quality)
            val faces = groups.filter { it.size >= 3 }.map { g -> centre(g.map { embeddings[it] }) }
            if (faces.size < 2) return@repeat
            val m = mean(faces)
            val l = sqrt(dot(m, m))
            if (l > 0f) average = FloatArray(m.size) { m[it] * length / l }
        }
        return cluster(embeddings.map { centred(it, average) }, quality) to average
    }

    /** The plain average of [faces]: what every face in the library has in common. */
    fun mean(faces: List<FloatArray>): FloatArray {
        val sum = FloatArray(faces.firstOrNull()?.size ?: 0)
        for (f in faces) for (k in sum.indices) sum[k] += f[k]
        for (k in sum.indices) sum[k] /= faces.size
        return sum
    }

    /** [face] with the library's shared part ([mean]) taken out, back to unit length. */
    fun centred(face: FloatArray, mean: FloatArray): FloatArray =
        normalize(FloatArray(face.size) { face[it] - mean[it] })

    /**
     * Sorts unit [embeddings] into people. Clearer faces ([quality], higher is better) go
     * first, so each person starts from a good face. Returns groups of indices into
     * [embeddings], biggest first.
     */
    fun cluster(embeddings: List<FloatArray>, quality: List<Float>, join: Float = JOIN, merge: Float = MERGE): List<List<Int>> {
        if (embeddings.isEmpty()) return emptyList()
        val dim = embeddings[0].size
        val sums = ArrayList<FloatArray>()
        val centres = ArrayList<FloatArray>()
        val members = ArrayList<MutableList<Int>>()
        for (i in embeddings.indices.sortedByDescending { quality[it] }) {
            val e = embeddings[i]
            var best = -1
            var bestScore = join
            for (c in centres.indices) {
                val s = dot(e, centres[c])
                if (s > bestScore) { bestScore = s; best = c }
            }
            if (best < 0) {
                sums += e.copyOf(); centres += e.copyOf(); members += mutableListOf(i)
            } else {
                val sum = sums[best]
                for (k in 0 until dim) sum[k] += e[k]
                centres[best] = normalize(sum)
                members[best] += i
            }
        }
        // A person whose faces were seen from very different angles can start two groups
        // before either has a steady average; join any groups that ended up alike.
        val parent = IntArray(centres.size) { it }
        fun root(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (a in centres.indices) for (b in a + 1 until centres.size) {
            if (dot(centres[a], centres[b]) > merge) {
                val ra = root(a); val rb = root(b)
                if (ra != rb) parent[rb] = ra
            }
        }
        return centres.indices.groupBy(::root)
            .map { (_, groups) -> groups.flatMap { members[it] } }
            .sortedByDescending { it.size }
    }

    /** The unit-length average of some faces: what a person "looks like" to the model. */
    fun centre(faces: List<FloatArray>): FloatArray {
        val sum = FloatArray(faces.first().size)
        for (f in faces) for (k in sum.indices) sum[k] += f[k]
        return normalize(sum)
    }

    /** Within one photo or video, the same person seen several times: keep their clearest face. */
    fun dedupe(embeddings: List<FloatArray>, quality: List<Float>, same: Float = 0.55f): List<Int> {
        val kept = ArrayList<Int>()
        for (i in embeddings.indices.sortedByDescending { quality[it] }) {
            if (kept.none { dot(embeddings[it], embeddings[i]) > same }) kept += i
        }
        return kept
    }

    fun toBytes(v: FloatArray): ByteArray {
        val out = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (x in v) out.putFloat(x)
        return out.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.getFloat() }
    }
}
