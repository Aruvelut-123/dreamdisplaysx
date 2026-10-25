package com.dreamdisplays.platform.server.utils

/** Where particles go to outline a box: two per block along each of its 12 edges. */
object OutlineGeometry {
    /**
     * Calls [point] for every outline position of the box from ([minX], [minY], [minZ]) to
     * ([maxX], [maxY], [maxZ]).
     */
    fun forEachPoint(
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double,
        point: (x: Double, y: Double, z: Double) -> Unit,
    ) {
        for (y in doubleArrayOf(minY, maxY)) for (z in doubleArrayOf(minZ, maxZ)) along(minX, maxX) { point(it, y, z) }
        for (x in doubleArrayOf(minX, maxX)) for (z in doubleArrayOf(minZ, maxZ)) along(minY, maxY) { point(x, it, z) }
        for (x in doubleArrayOf(minX, maxX)) for (y in doubleArrayOf(minY, maxY)) along(minZ, maxZ) { point(x, y, it) }
    }

    private inline fun along(from: Double, to: Double, at: (Double) -> Unit) {
        val steps = ((to - from) * 2).toInt().coerceAtLeast(1)
        for (i in 0..steps) at(from + (to - from) * i / steps)
    }
}
