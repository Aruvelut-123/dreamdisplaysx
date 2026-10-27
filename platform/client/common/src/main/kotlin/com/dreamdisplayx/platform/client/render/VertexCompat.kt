package com.dreamdisplayx.platform.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer

/**
 * Emits one vertex with texture coordinates [u], [v] and an RGBA colour.
 *
 * 1.20.1's `VertexConsumer` has no `addVertex` / `setUv` / `setColor`; the older builder API is
 * `vertex(matrix, x, y, z)`, then the attribute setters, then `endVertex()`. Everything after 1.20.1
 * uses the fluent `addVertex(pose, ...)` form. Funnelling both through this one helper keeps the
 * call sites identical across versions.
 */
//? if >=1.20.2 {
fun VertexConsumer.vertexCompat(
    pose: PoseStack.Pose,
    x: Float,
    y: Float,
    z: Float,
    u: Float,
    v: Float,
    r: Int,
    g: Int,
    b: Int,
    a: Int,
) {
    addVertex(pose, x, y, z).setUv(u, v).setColor(r, g, b, a)
}
//?} else
/*fun VertexConsumer.vertexCompat(
    pose: PoseStack.Pose,
    x: Float,
    y: Float,
    z: Float,
    u: Float,
    v: Float,
    r: Int,
    g: Int,
    b: Int,
    a: Int,
) {
    // 1.20.1 declares DefaultVertexFormat.POSITION_TEX_COLOR as Position -> UV0 -> Color, and
    // BufferBuilder.endVertex() rejects a vertex whose elements were not filled in exactly that
    // order ("Not filled all elements of the vertex"). 1.20.2+ needs no ordering because the
    // fluent setUv/setColor setters address their own element slots.
    vertex(pose.pose(), x, y, z).uv(u, v).color(r, g, b, a).endVertex()
}*/