package me.ryanod.boids.item

import me.ryanod.boids.registry.BoidEntityTypes
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.server.level.ServerLevel

class BoidCodSchoolSpawnEggItem(properties: Item.Properties) :
    SpawnEggItem(BoidEntityTypes.BOID_COD, PRIMARY_COLOR, SECONDARY_COLOR, properties) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val clickedPos = context.clickedPos
        if (!canSpawnSchoolAt(level, clickedPos)) {
            return InteractionResult.FAIL
        }

        if (level.isClientSide) {
            return InteractionResult.sidedSuccess(true)
        }

        val serverLevel = level as? ServerLevel ?: return InteractionResult.FAIL
        val success = spawnSchool(serverLevel, context.clickLocation, context.player, context.itemInHand)

        return if (success) {
            InteractionResult.sidedSuccess(false)
        } else {
            InteractionResult.FAIL
        }
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val itemStack = player.getItemInHand(hand)
        val hitResult = getPlayerPOVHitResult(level, player, ClipContext.Fluid.WATER)
        if (hitResult.type != HitResult.Type.BLOCK) {
            return InteractionResultHolder.pass(itemStack)
        }

        val blockPos = hitResult.blockPos
        if (!canSpawnSchoolAt(level, blockPos)) {
            return InteractionResultHolder.fail(itemStack)
        }

        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(itemStack, true)
        }

        val serverLevel = level as? ServerLevel ?: return InteractionResultHolder.fail(itemStack)
        val success = spawnSchool(serverLevel, hitResult.location, player, itemStack)

        return if (success) {
            InteractionResultHolder.sidedSuccess(itemStack, false)
        } else {
            InteractionResultHolder.fail(itemStack)
        }
    }

    private fun canSpawnSchoolAt(level: Level, blockPos: BlockPos): Boolean =
        level.getFluidState(blockPos).`is`(net.minecraft.tags.FluidTags.WATER) &&
            level.getBiome(blockPos).`is`(Biomes.WARM_OCEAN)

    private fun spawnSchool(
        level: ServerLevel,
        clickLocation: Vec3,
        player: Player?,
        itemStack: ItemStack,
    ): Boolean {
        val anchor = BoidEntityTypes.BOID_COD_PULL.create(level) ?: return false
        val anchorSpawnPos = computeAnchorSpawnPosition(level, clickLocation)
        val initialYaw = player?.yRot ?: level.random.nextFloat() * 360.0f

        anchor.moveTo(anchorSpawnPos.x, anchorSpawnPos.y, anchorSpawnPos.z, initialYaw, 0.0f)
        anchor.initializeHeading(Vec3.directionFromRotation(0.0f, initialYaw))

        if (!level.addFreshEntity(anchor)) {
            return false
        }

        val anchorHeading = anchor.getPullHeading()

        for (index in 0 until SCHOOL_SIZE) {
            val cod = BoidEntityTypes.BOID_COD.create(level) ?: continue
            val spawnOffset = createShellOffset(level, index)
            val spawnX = anchorSpawnPos.x + spawnOffset.x
            val spawnZ = anchorSpawnPos.z + spawnOffset.z
            val spawnY = computeCodSpawnY(level, spawnX, spawnZ, anchorSpawnPos.y + spawnOffset.y)

            cod.moveTo(spawnX, spawnY, spawnZ, initialYaw, 0.0f)
            cod.setPos(spawnX, spawnY, spawnZ)
            cod.setDeltaMovement(
                anchorHeading.scale(COD_INITIAL_SPEED).add(
                    (level.random.nextDouble() - 0.5) * 0.01,
                    (level.random.nextDouble() - 0.5) * 0.004,
                    (level.random.nextDouble() - 0.5) * 0.01,
                ),
            )
            cod.seedPullPreference(anchor, SEEDED_HISTORY_AFFINITY)
            cod.initializeCruiseHeading(anchorHeading)
            level.addFreshEntity(cod)
        }

        if (player == null || !player.isCreative) {
            itemStack.shrink(1)
        }

        return true
    }

    private fun computeAnchorSpawnPosition(level: ServerLevel, clickLocation: Vec3): Vec3 {
        val x = clickLocation.x
        val z = clickLocation.z
        val blockX = kotlin.math.floor(x).toInt()
        val blockZ = kotlin.math.floor(z).toInt()
        val floorY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, blockX, blockZ).toDouble() + 3.0
        val surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, blockX, blockZ).toDouble() - 2.0
        val y = net.minecraft.util.Mth.clamp(clickLocation.y, floorY, surfaceY)

        return Vec3(x, y, z)
    }

    private fun computeCodSpawnY(level: ServerLevel, x: Double, z: Double, preferredY: Double): Double {
        val blockX = kotlin.math.floor(x).toInt()
        val blockZ = kotlin.math.floor(z).toInt()
        val floorY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, blockX, blockZ).toDouble() + 2.0
        val surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, blockX, blockZ).toDouble() - 1.0
        return if (floorY <= surfaceY) {
            net.minecraft.util.Mth.clamp(preferredY, floorY, surfaceY)
        } else {
            preferredY
        }
    }

    private fun createShellOffset(level: ServerLevel, index: Int): Vec3 {
        val angle = (2.0 * Math.PI * index / SCHOOL_SIZE) + (level.random.nextDouble() - 0.5) * 0.35
        val radius = 2.25 + level.random.nextDouble() * 1.75
        val vertical = (level.random.nextDouble() - 0.5) * 1.6
        return Vec3(Math.cos(angle) * radius, vertical, Math.sin(angle) * radius)
    }

    companion object {
        const val PRIMARY_COLOR = 12691306
        const val SECONDARY_COLOR = 15058059
        const val SCHOOL_SIZE = 15
        const val COD_INITIAL_SPEED = 0.09
        const val SEEDED_HISTORY_AFFINITY = 0.75f
    }
}
