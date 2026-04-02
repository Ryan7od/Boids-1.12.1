package me.ryanod.boids.client

import me.ryanod.boids.client.render.entity.BoidCodEntityRenderer
import me.ryanod.boids.client.render.entity.BoidCodPullEntityRenderer
import me.ryanod.boids.item.BoidCodPullSpawnEggItem
import me.ryanod.boids.item.BoidCodSchoolSpawnEggItem
import me.ryanod.boids.registry.BoidEntityTypes
import me.ryanod.boids.registry.BoidItems
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry

object BoidsClient : ClientModInitializer {
    override fun onInitializeClient() {
        EntityRendererRegistry.register(BoidEntityTypes.BOID_COD) { context ->
            BoidCodEntityRenderer(context)
        }
        EntityRendererRegistry.register(BoidEntityTypes.BOID_COD_PULL) { context ->
            BoidCodPullEntityRenderer(context)
        }
        ColorProviderRegistry.ITEM.register(
            { _, tintIndex ->
                when (tintIndex) {
                    0 -> BoidCodSchoolSpawnEggItem.PRIMARY_COLOR
                    else -> BoidCodSchoolSpawnEggItem.SECONDARY_COLOR
                }
            },
            BoidItems.BOID_COD_SCHOOL_SPAWN_EGG,
        )
        ColorProviderRegistry.ITEM.register(
            { _, tintIndex ->
                when (tintIndex) {
                    0 -> BoidCodPullSpawnEggItem.PRIMARY_COLOR
                    else -> BoidCodPullSpawnEggItem.SECONDARY_COLOR
                }
            },
            BoidItems.BOID_COD_PULL_SPAWN_EGG,
        )
    }
}
