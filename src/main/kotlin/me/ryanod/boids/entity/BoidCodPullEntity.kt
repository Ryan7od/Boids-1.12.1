package me.ryanod.boids.entity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.phys.AABB

class BoidCodPullEntity(
    entityType: EntityType<out BoidCodPullEntity>,
    level: Level,
) : AbstractSchoolPullEntity(entityType, level) {
    private var currentFollowerVotes = 0
    private var smoothedFollowerCount = 0.0f

    override fun getPullMovementProfile(): PullMovementProfile = PULL_PROFILE

    override fun isAllowedBiome(pos: BlockPos): Boolean = level().getBiome(pos).`is`(Biomes.WARM_OCEAN)

    override fun tick() {
        super.tick()

        if (level().isClientSide) {
            return
        }

        if (tickCount % FOLLOWER_RECOUNT_INTERVAL == 0) {
            updateFollowerSupport()
        }
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putInt("CurrentFollowerVotes", currentFollowerVotes)
        tag.putFloat("SmoothedFollowerCount", smoothedFollowerCount)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        currentFollowerVotes = tag.getInt("CurrentFollowerVotes")
        smoothedFollowerCount = tag.getFloat("SmoothedFollowerCount")
    }

    fun getSmoothedFollowerCount(): Float = smoothedFollowerCount

    private fun updateFollowerSupport() {
        val searchBox = AABB.ofSize(position(), FOLLOWER_SCAN_RADIUS * 2.0, FOLLOWER_SCAN_RADIUS * 2.0, FOLLOWER_SCAN_RADIUS * 2.0)
        currentFollowerVotes = level().getEntitiesOfClass(BoidCodEntity::class.java, searchBox)
            .count { it.getCurrentPreferredPullUuid() == uuid }
        smoothedFollowerCount = Mth.lerp(FOLLOWER_SMOOTHING_ALPHA, smoothedFollowerCount, currentFollowerVotes.toFloat())
    }

    private companion object {
        const val FOLLOWER_SCAN_RADIUS = 64.0
        const val FOLLOWER_RECOUNT_INTERVAL = 10
        const val FOLLOWER_SMOOTHING_ALPHA = 0.2f

        val PULL_PROFILE = PullMovementProfile(
            forwardSpeed = 0.10,
            maxTurnRateDeg = 1.25,
            turnAccelerationDeg = 0.08,
            retargetMinTicks = 80,
            retargetMaxTicks = 160,
            biomeProbeDistance = 10.0,
            sideProbeAngleDeg = 40.0f,
            turnBiasGain = 0.08,
            verticalCorrectionStrength = 0.08,
            verticalDamping = 0.65,
            maxVerticalSpeed = 0.05,
            recoveryStartTicks = 100,
            recoverySearchRadius = 40,
            recoverySearchStep = 4,
            recoverySampleCount = 24,
        )
    }
}
