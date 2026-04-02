package me.ryanod.boids.client

import me.ryanod.boids.client.render.entity.BoidCodEntityRenderer
import me.ryanod.boids.client.render.entity.BoidCodPullEntityRenderer
import me.ryanod.boids.registry.BoidEntityTypes
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry

object BoidsClient : ClientModInitializer {
    override fun onInitializeClient() {
        EntityRendererRegistry.register(BoidEntityTypes.BOID_COD) { context ->
            BoidCodEntityRenderer(context)
        }
        EntityRendererRegistry.register(BoidEntityTypes.BOID_COD_PULL) { context ->
            BoidCodPullEntityRenderer(context)
        }
    }
}
