package me.ryanod.boids

import me.ryanod.boids.registry.BoidEntityTypes
import me.ryanod.boids.registry.BoidItems
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object Boids : ModInitializer {
    const val MOD_ID = "boids"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        BoidEntityTypes.initialize()
        BoidItems.initialize()

        LOGGER.info("Initialized {}", MOD_ID)
    }
}
