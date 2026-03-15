package me.ryanod.boids.client.render.entity

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import me.ryanod.boids.entity.BoidCodEntity
import net.minecraft.client.model.CodModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth

class BoidCodEntityRenderer(context: EntityRendererProvider.Context) :
    MobRenderer<BoidCodEntity, CodModel<BoidCodEntity>>(
        context,
        CodModel(context.bakeLayer(ModelLayers.COD)),
        0.3f,
    ) {
    override fun getTextureLocation(entity: BoidCodEntity): ResourceLocation = TEXTURE

    override fun setupRotations(
        entity: BoidCodEntity,
        poseStack: PoseStack,
        ageInTicks: Float,
        rotationYaw: Float,
        partialTick: Float,
        scale: Float,
    ) {
        super.setupRotations(entity, poseStack, ageInTicks, rotationYaw, partialTick, scale)

        val sway = 4.3f * Mth.sin(0.6f * ageInTicks)
        poseStack.mulPose(Axis.YP.rotationDegrees(sway))

        if (!entity.isInWater) {
            poseStack.translate(0.1f, 0.1f, -0.1f)
            poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f))
        }
    }

    private companion object {
        val TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/entity/fish/cod.png")
    }
}
