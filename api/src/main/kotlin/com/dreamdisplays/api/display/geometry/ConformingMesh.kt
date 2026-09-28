package com.dreamdisplays.api.display.geometry

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.model.property.DisplayFacing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val EPS = 1.0e-4
private const val EPS_F = 1.0e-4f

// S runs to the viewer's right, T up the picture, N into the blocks away from the viewer
private const val S = 0
private const val T = 1
private const val N = 2

/**
 * One box of a block outline, in that block's local coordinates (a full cube is `0..1` on every axis).
 * Stairs and slabs are a union of these boxes.
 */
@Unstable
data class ShapeBox(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    /** Shifts this box by a block offset so it shares a coordinate space with the rest of the selection. */
    fun translated(dx: Int, dy: Int, dz: Int): ShapeBox = ShapeBox(
        minX + dx, minY + dy, minZ + dz,
        maxX + dx, maxY + dy, maxZ + dz,
    )
}

/** A block inside the display volume and the boxes that make up its outline. */
@Unstable
data class ShapedBlock(
    val x: Int,
    val y: Int,
    val z: Int,
    val boxes: List<ShapeBox>,
)

/**
 * One corner of a screen quad, positions relative to the display's minimum corner.
 *
 * [ox], [oy], [oz] is where the corner moves per unit of lift off the blocks. On a fold it is the sum of both
 * faces' normals, so lifted faces still meet on the edge instead of sinking into each other and eating the picture.
 */
@Unstable
data class ConformVertex(
    val x: Float,
    val y: Float,
    val z: Float,
    val u: Float,
    val v: Float,
    val ox: Float = 0f,
    val oy: Float = 0f,
    val oz: Float = 0f,
)

/**
 * A screen quad hugging one face of the display surface. [nx], [ny], [nz] point outward; the vertices
 * are wound so that normal faces the viewer of that face.
 */
@Unstable
data class ConformQuad(
    val v0: ConformVertex,
    val v1: ConformVertex,
    val v2: ConformVertex,
    val v3: ConformVertex,
    val nx: Float,
    val ny: Float,
    val nz: Float,
) {
    /** The four corners in winding order. */
    val vertices: List<ConformVertex> get() = listOf(v0, v1, v2, v3)

    /** Returns a copy with `u` and `v` replaced on every corner. */
    fun mapUv(transform: (u: Float, v: Float) -> Pair<Float, Float>): ConformQuad {
        fun ConformVertex.mapped(): ConformVertex {
            val (nu, nv) = transform(u, v)
            return copy(u = nu, v = nv)
        }
        return copy(v0 = v0.mapped(), v1 = v1.mapped(), v2 = v2.mapped(), v3 = v3.mapped())
    }
}

/**
 * How many blocks the volume extends on each world axis. Width runs across the screen, height runs
 * along it, and depth runs into the blocks, away from the clicked face.
 */
@Unstable
fun blockSpans(width: Int, height: Int, depth: Int, facing: DisplayFacing): Triple<Int, Int, Int> = when (facing) {
    DisplayFacing.NORTH, DisplayFacing.SOUTH -> Triple(width, height, depth)
    DisplayFacing.EAST, DisplayFacing.WEST -> Triple(depth, height, width)
    DisplayFacing.UP, DisplayFacing.DOWN -> Triple(width, depth, height)
}

/**
 * Builds the quads of a screen wrapped onto [blocks]. Coordinates are relative to the minimum corner of the display.
 *
 * Every face the viewer can see from the front gets picture: fronts, risers, treads, side walls of a step,
 * the ceiling of a pocket. Faces on the rim of the volume are the screen's edge and stay bare, same as a flat screen.
 */
@Unstable
fun buildConformingMesh(
    blocks: List<ShapedBlock>,
    width: Int,
    height: Int,
    depth: Int,
    facing: DisplayFacing,
    quarterTurns: Int = 0,
): List<ConformQuad> {
    if (width <= 0 || height <= 0 || depth <= 0 || blocks.isEmpty()) return emptyList()
    val frame = ScreenFrame(facing, width, height, depth)
    val boxes = blocks.flatMap { block -> block.boxes.map { frame.box(it.translated(block.x, block.y, block.z)) } }

    val faces = boxes
        .flatMap(::facesOf)
        .filter { !frame.isRimOrBack(it) }
        .flatMap { visiblePart(it, boxes) }
    if (faces.isEmpty()) return emptyList()

    val folds = arrayOf(Folds(faces, S), Folds(faces, T))
    val pieces = faces.flatMap { face -> splitAtFolds(face, folds) }
    return unfold(pieces, folds, frame, Math.floorMod(quarterTurns, 4))
}

/**
 * Cuts [quad] to the video-UV rectangle and returns the pieces. UVs stay in video space; callers
 * that sample a different texture remap them. Pieces with more than four corners are fanned.
 */
@Unstable
fun clipToUvRect(quad: ConformQuad, minU: Float, minV: Float, maxU: Float, maxV: Float): List<ConformQuad> {
    if (maxU - minU <= EPS_F || maxV - minV <= EPS_F) return emptyList()
    var poly = quad.vertices
    poly = clipHalf(poly, { it.u >= minU }) { a, b -> lerp(a, b, (minU - a.u) / (b.u - a.u)) }
    poly = clipHalf(poly, { it.u <= maxU }) { a, b -> lerp(a, b, (maxU - a.u) / (b.u - a.u)) }
    poly = clipHalf(poly, { it.v >= minV }) { a, b -> lerp(a, b, (minV - a.v) / (b.v - a.v)) }
    poly = clipHalf(poly, { it.v <= maxV }) { a, b -> lerp(a, b, (maxV - a.v) / (b.v - a.v)) }
    if (poly.size < 3) return emptyList()
    if (poly.size == 4) return listOf(quad.copy(v0 = poly[0], v1 = poly[1], v2 = poly[2], v3 = poly[3]))
    return (1 until poly.size - 1).map { i ->
        quad.copy(v0 = poly[0], v1 = poly[i], v2 = poly[i + 1], v3 = poly[i + 1])
    }
}

private class Span(val lo: DoubleArray, val hi: DoubleArray) {
    fun with(axis: Int, from: Double, to: Double): Span {
        val nextLo = lo.copyOf()
        val nextHi = hi.copyOf()
        nextLo[axis] = from
        nextHi[axis] = to
        return Span(nextLo, nextHi)
    }
}

private class Face(val axis: Int, val sign: Int, val span: Span) {
    val plane: Double get() = span.lo[axis]
    val tangents: IntArray get() = TANGENTS[axis]

    fun with(tangent: Int, from: Double, to: Double): Face = Face(axis, sign, span.with(tangent, from, to))

    fun touches(p: DoubleArray): Boolean = (0..2).all { p[it] >= span.lo[it] - EPS && p[it] <= span.hi[it] + EPS }
}

private val TANGENTS = arrayOf(intArrayOf(T, N), intArrayOf(S, N), intArrayOf(S, T))

private class ScreenFrame(val facing: DisplayFacing, val width: Int, val height: Int, val depth: Int) {
    fun toScreen(x: Double, y: Double, z: Double): DoubleArray = when (facing) {
        DisplayFacing.NORTH -> doubleArrayOf(width - x, y, z)
        DisplayFacing.SOUTH -> doubleArrayOf(x, y, depth - z)
        DisplayFacing.EAST -> doubleArrayOf(width - z, y, depth - x)
        DisplayFacing.WEST -> doubleArrayOf(z, y, x)
        DisplayFacing.UP -> doubleArrayOf(x, height - z, depth - y)
        DisplayFacing.DOWN -> doubleArrayOf(x, z, y)
    }

    fun toWorld(p: DoubleArray): DoubleArray {
        val s = p[S]
        val t = p[T]
        val n = p[N]
        return when (facing) {
            DisplayFacing.NORTH -> doubleArrayOf(width - s, t, n)
            DisplayFacing.SOUTH -> doubleArrayOf(s, t, depth - n)
            DisplayFacing.EAST -> doubleArrayOf(depth - n, t, width - s)
            DisplayFacing.WEST -> doubleArrayOf(n, t, s)
            DisplayFacing.UP -> doubleArrayOf(s, depth - n, height - t)
            DisplayFacing.DOWN -> doubleArrayOf(s, n, t)
        }
    }

    fun normal(axis: Int, sign: Int): DoubleArray {
        val tip = DoubleArray(3).also { it[axis] = sign.toDouble() }
        val origin = toWorld(DoubleArray(3))
        val end = toWorld(tip)
        return DoubleArray(3) { end[it] - origin[it] }
    }

    fun box(world: ShapeBox): Span {
        val a = toScreen(world.minX, world.minY, world.minZ)
        val b = toScreen(world.maxX, world.maxY, world.maxZ)
        return Span(DoubleArray(3) { min(a[it], b[it]) }, DoubleArray(3) { max(a[it], b[it]) })
    }

    fun lift(face: Face, p: DoubleArray, faces: List<Face>): DoubleArray {
        val out = normal(face.axis, face.sign)
        val seen = HashSet<Int>()
        for (other in faces) {
            if (other.axis == face.axis || !other.touches(p)) continue
            if (!seen.add(other.axis * 2 + (other.sign + 1) / 2)) continue
            val n = normal(other.axis, other.sign)
            for (i in 0..2) out[i] += n[i]
        }
        return out
    }

    fun isRimOrBack(face: Face): Boolean = when (face.axis) {
        N -> face.sign > 0
        S -> face.plane <= EPS || face.plane >= width - EPS
        else -> face.plane <= EPS || face.plane >= height - EPS
    }
}

private fun facesOf(box: Span): List<Face> {
    val out = ArrayList<Face>(6)
    for (axis in 0..2) {
        val (a, b) = TANGENTS[axis].let { it[0] to it[1] }
        if (box.hi[a] - box.lo[a] <= EPS || box.hi[b] - box.lo[b] <= EPS) continue
        out += Face(axis, -1, box.with(axis, box.lo[axis], box.lo[axis]))
        out += Face(axis, +1, box.with(axis, box.hi[axis], box.hi[axis]))
    }
    return out
}

private fun visiblePart(face: Face, boxes: List<Span>): List<Face> {
    var parts = listOf(face)
    for (box in boxes) {
        val cut = shadowOf(box, face) ?: continue
        parts = parts.flatMap { subtract(it, cut) }
        if (parts.isEmpty()) break
    }
    return parts
}

private fun shadowOf(box: Span, face: Face): Span? {
    val plane = face.plane
    val inTheWay = if (face.axis == N) {
        box.lo[N] < plane - EPS
    } else if (face.sign > 0) {
        box.lo[face.axis] <= plane + EPS && box.hi[face.axis] > plane + EPS
    } else {
        box.hi[face.axis] >= plane - EPS && box.lo[face.axis] < plane - EPS
    }
    if (!inTheWay) return null

    // Along the depth, the box hides everything behind its front
    return if (face.axis == N) box else box.with(N, box.lo[N], Double.POSITIVE_INFINITY)
}

private fun subtract(face: Face, cut: Span): List<Face> {
    val (a, b) = face.tangents.let { it[0] to it[1] }
    val span = face.span
    val a0 = max(span.lo[a], cut.lo[a])
    val a1 = min(span.hi[a], cut.hi[a])
    val b0 = max(span.lo[b], cut.lo[b])
    val b1 = min(span.hi[b], cut.hi[b])
    if (a1 - a0 <= EPS || b1 - b0 <= EPS) return listOf(face)
    val out = ArrayList<Face>(4)
    fun piece(fromA: Double, toA: Double, fromB: Double, toB: Double) {
        if (toA - fromA > EPS && toB - fromB > EPS) out += face.with(a, fromA, toA).with(b, fromB, toB)
    }
    piece(span.lo[a], span.hi[a], span.lo[b], b0)
    piece(span.lo[a], span.hi[a], b1, span.hi[b])
    piece(span.lo[a], a0, b0, b1)
    piece(a1, span.hi[a], b0, b1)
    return out
}

private class Folds(faces: List<Face>, val axis: Int) {
    private class Fold(val plane: Double, var lo: Double, var hi: Double) {
        val step: Double get() = hi - lo
    }

    private val folds: List<Fold> = run {
        val byPlane = sortedMapOf<Long, Fold>()
        for (face in faces) {
            if (face.axis != axis) continue
            val key = Math.round(face.plane / EPS)
            val fold = byPlane.getOrPut(key) { Fold(face.plane, face.span.lo[N], face.span.hi[N]) }
            fold.lo = min(fold.lo, face.span.lo[N])
            fold.hi = max(fold.hi, face.span.hi[N])
        }
        byPlane.values.toList()
    }

    val planes: List<Double> get() = folds.map { it.plane }

    fun along(x: Double, lowEdge: Boolean): Double =
        x + folds.sumOf { if (it.plane < x - EPS || (lowEdge && it.plane <= x + EPS)) it.step else 0.0 }

    fun across(plane: Double, sign: Int, n: Double): Double {
        val fold = folds.first { abs(it.plane - plane) <= EPS }
        val start = along(plane, lowEdge = false)
        return start + if (sign > 0) n - fold.lo else fold.hi - n
    }
}

private fun splitAtFolds(face: Face, folds: Array<Folds>): List<Face> {
    var pieces = listOf(face)
    for (axis in intArrayOf(S, T)) {
        if (axis == face.axis) continue
        for (plane in folds[axis].planes) {
            pieces = pieces.flatMap { piece ->
                if (plane > piece.span.lo[axis] + EPS && plane < piece.span.hi[axis] - EPS) {
                    listOf(piece.with(axis, piece.span.lo[axis], plane), piece.with(axis, plane, piece.span.hi[axis]))
                } else {
                    listOf(piece)
                }
            }
        }
    }
    return pieces
}

private fun unfold(pieces: List<Face>, folds: Array<Folds>, frame: ScreenFrame, quarterTurns: Int): List<ConformQuad> {
    class Corner(val screen: DoubleArray, val s: Double, val t: Double)

    fun unfolded(face: Face, p: DoubleArray, axis: Int): Double =
        if (face.axis == axis) {
            folds[axis].across(face.plane, face.sign, p[N])
        } else {
            folds[axis].along(p[axis], lowEdge = p[axis] <= face.span.lo[axis] + EPS)
        }

    val corners = pieces.map { face ->
        val (a, b) = face.tangents.let { it[0] to it[1] }
        listOf(
            face.span.lo[a] to face.span.lo[b],
            face.span.hi[a] to face.span.lo[b],
            face.span.hi[a] to face.span.hi[b],
            face.span.lo[a] to face.span.hi[b],
        ).map { (pa, pb) ->
            val p = DoubleArray(3).also { it[face.axis] = face.plane; it[a] = pa; it[b] = pb }
            Corner(p, unfolded(face, p, S), unfolded(face, p, T))
        }
    }
    val all = corners.flatten()
    val sMin = all.minOf { it.s }
    val tMin = all.minOf { it.t }
    val sLen = (all.maxOf { it.s } - sMin).takeIf { it > EPS } ?: 1.0
    val tLen = (all.maxOf { it.t } - tMin).takeIf { it > EPS } ?: 1.0

    return pieces.zip(corners) { face, quad ->
        val normal = frame.normal(face.axis, face.sign)
        val vertices = quad.map { corner ->
            val world = frame.toWorld(corner.screen)
            val lift = frame.lift(face, corner.screen, pieces)
            val (u, v) = textureUv((corner.s - sMin) / sLen, (corner.t - tMin) / tLen, quarterTurns)
            ConformVertex(
                world[0].toFloat(), world[1].toFloat(), world[2].toFloat(), u, v,
                lift[0].toFloat(), lift[1].toFloat(), lift[2].toFloat(),
            )
        }
        val wound = if (facesAlong(vertices, normal)) vertices else vertices.asReversed()
        ConformQuad(
            wound[0], wound[1], wound[2], wound[3],
            normal[0].toFloat(), normal[1].toFloat(), normal[2].toFloat(),
        )
    }
}

private fun facesAlong(v: List<ConformVertex>, normal: DoubleArray): Boolean {
    val e1x = v[1].x - v[0].x
    val e1y = v[1].y - v[0].y
    val e1z = v[1].z - v[0].z
    val e2x = v[2].x - v[0].x
    val e2y = v[2].y - v[0].y
    val e2z = v[2].z - v[0].z
    val cx = e1y * e2z - e1z * e2y
    val cy = e1z * e2x - e1x * e2z
    val cz = e1x * e2y - e1y * e2x
    return cx * normal[0] + cy * normal[1] + cz * normal[2] > 0.0
}

private fun textureUv(s: Double, t: Double, quarterTurns: Int): Pair<Float, Float> {
    val (u, v) = when (quarterTurns) {
        1 -> 1.0 - t to 1.0 - s
        2 -> 1.0 - s to t
        3 -> t to s
        else -> s to 1.0 - t
    }
    return u.toFloat() to v.toFloat()
}

private fun clipHalf(
    poly: List<ConformVertex>,
    inside: (ConformVertex) -> Boolean,
    cross: (ConformVertex, ConformVertex) -> ConformVertex?,
): List<ConformVertex> {
    if (poly.isEmpty()) return emptyList()
    val out = ArrayList<ConformVertex>(poly.size + 1)
    var prev = poly.last()
    var prevIn = inside(prev)
    for (cur in poly) {
        val curIn = inside(cur)
        if (curIn != prevIn) cross(prev, cur)?.let(out::add)
        if (curIn) out += cur
        prev = cur
        prevIn = curIn
    }
    return out
}

private fun lerp(a: ConformVertex, b: ConformVertex, t: Float): ConformVertex? {
    if (t.isNaN() || t.isInfinite()) return null
    val k = t.coerceIn(0f, 1f)
    return ConformVertex(
        a.x + (b.x - a.x) * k,
        a.y + (b.y - a.y) * k,
        a.z + (b.z - a.z) * k,
        a.u + (b.u - a.u) * k,
        a.v + (b.v - a.v) * k,
        a.ox + (b.ox - a.ox) * k,
        a.oy + (b.oy - a.oy) * k,
        a.oz + (b.oz - a.oz) * k,
    )
}
