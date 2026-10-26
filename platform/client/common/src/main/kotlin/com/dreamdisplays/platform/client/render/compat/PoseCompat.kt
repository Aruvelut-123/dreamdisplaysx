//? if <1.19.3 {
package com.dreamdisplays.platform.client.render.compat

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Quaternion
import org.joml.Quaternionf

fun PoseStack.translate(x: Float, y: Float, z: Float) = translate(x.toDouble(), y.toDouble(), z.toDouble())

fun PoseStack.mulPose(rotation: Quaternionf) = mulPose(Quaternion(rotation.x, rotation.y, rotation.z, rotation.w))

fun Quaternion.toJoml(): Quaternionf = Quaternionf(i(), j(), k(), r())
//?}
