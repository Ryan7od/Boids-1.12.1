package me.ryanod.boids.registry

import me.ryanod.boids.Boids
import me.ryanod.boids.entity.BoidCodEntity
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.SpawnPlacementTypes
import net.minecraft.world.entity.SpawnPlacements
import net.minecraft.world.level.levelgen.Heightmap

object BoidEntityTypes {
    val BOID_COD: EntityType<BoidCodEntity> = Registry.register(
        BuiltInRegistries.ENTITY_TYPE,
        id("boid_cod"),
        EntityType.Builder.of({ entityType, level -> BoidCodEntity(entityType, level) }, MobCategory.WATER_AMBIENT)
            .sized(0.5f, 0.3f)
            .eyeHeight(0.195f)
            .clientTrackingRange(4)
            .build("boid_cod"),
    )

    init {
        FabricDefaultAttributeRegistry.register(BOID_COD, BoidCodEntity.createAttributes())
        SpawnPlacements.register(
            BOID_COD,
            SpawnPlacementTypes.IN_WATER,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            BoidCodEntity::canSpawn,
        )
    }

    fun initialize() = Unit

    private fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Boids.MOD_ID, path)
}
