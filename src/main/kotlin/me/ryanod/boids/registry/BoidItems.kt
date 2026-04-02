package me.ryanod.boids.registry

import me.ryanod.boids.Boids
import me.ryanod.boids.item.BoidCodPullSpawnEggItem
import me.ryanod.boids.item.BoidCodSchoolSpawnEggItem
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.item.SpawnEggItem

object BoidItems {
    val BOID_COD_SPAWN_EGG: Item = Registry.register(
        BuiltInRegistries.ITEM,
        id("boid_cod_spawn_egg"),
        SpawnEggItem(BoidEntityTypes.BOID_COD, 12691306, 15058059, Item.Properties()),
    )

    val BOID_COD_SCHOOL_SPAWN_EGG: Item = Registry.register(
        BuiltInRegistries.ITEM,
        id("boid_cod_school_spawn_egg"),
        BoidCodSchoolSpawnEggItem(Item.Properties()),
    )

    val BOID_COD_PULL_SPAWN_EGG: Item = Registry.register(
        BuiltInRegistries.ITEM,
        id("boid_cod_pull_spawn_egg"),
        BoidCodPullSpawnEggItem(Item.Properties()),
    )

    init {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register { entries ->
            entries.accept(BOID_COD_SPAWN_EGG)
            entries.accept(BOID_COD_SCHOOL_SPAWN_EGG)
            entries.accept(BOID_COD_PULL_SPAWN_EGG)
        }
    }

    fun initialize() = Unit

    private fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Boids.MOD_ID, path)
}
