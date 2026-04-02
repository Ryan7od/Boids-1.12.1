package me.ryanod.boids.client.render.entity

import com.mojang.blaze3d.vertex.PoseStack
import me.ryanod.boids.Boids
import me.ryanod.boids.entity.BoidCodPullEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation

class BoidCodPullEntityRenderer(
    context: EntityRendererProvider.Context,
) : EntityRenderer<BoidCodPullEntity>(context) {
    init {
        shadowRadius = 0.0f
        shadowStrength = 0.0f
    }

    override fun render(
        entity: BoidCodPullEntity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
    ) {
        // Intentionally render no visible model; this renderer only keeps the entity
        // in the normal render/debug path so F3+B can show the hitbox.
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight)
    }

    override fun shouldRender(
        entity: BoidCodPullEntity,
        frustum: Frustum,
        x: Double,
        y: Double,
        z: Double,
    ): Boolean = true

    override fun getTextureLocation(entity: BoidCodPullEntity): ResourceLocation = TEXTURE

    private companion object {
        val TEXTURE: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath(Boids.MOD_ID, "textures/entity/fish/boid_cod_pull.png")
    }
}
