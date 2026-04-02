package me.ryanod.boids.entity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MoverType
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

abstract class AbstractSchoolPullEntity(
    entityType: EntityType<out AbstractSchoolPullEntity>,
    level: Level,
) : Entity(entityType, level) {
    protected data class PullMovementProfile(
        val forwardSpeed: Double,
        val maxTurnRateDeg: Double,
        val turnAccelerationDeg: Double,
        val retargetMinTicks: Int,
        val retargetMaxTicks: Int,
        val biomeProbeDistance: Double,
        val sideProbeAngleDeg: Float,
        val turnBiasGain: Double,
        val verticalCorrectionStrength: Double,
        val verticalDamping: Double,
        val maxVerticalSpeed: Double,
        val recoveryStartTicks: Int,
        val recoverySearchRadius: Int,
        val recoverySearchStep: Int,
        val recoverySampleCount: Int,
    )

    private var headingYaw = 0.0f
    private var currentTurnRateDeg = 0.0
    private var targetTurnRateDeg = 0.0
    private var turnRetargetTicks = 0
    private var outsideAllowedBiomeTicks = 0
    private var recoveryTarget: Vec3? = null
    private var headingInitialized = false

    init {
        noPhysics = true
        setNoGravity(true)
        setInvisible(true)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) = Unit

    override fun tick() {
        super.tick()

        noPhysics = true
        setNoGravity(true)
        setInvisible(true)

        if (level().isClientSide) {
            return
        }

        val profile = getPullMovementProfile()

        if (!headingInitialized) {
            initializeHeading(Vec3.directionFromRotation(0.0f, yRot))
        }

        val currentPosition = position()
        val currentBlockPos = BlockPos.containing(currentPosition)
        val insideAllowedBiome = isAllowedBiome(currentBlockPos)

        if (insideAllowedBiome) {
            outsideAllowedBiomeTicks = 0
            recoveryTarget = null
        } else {
            outsideAllowedBiomeTicks++
        }

        if (outsideAllowedBiomeTicks >= profile.recoveryStartTicks) {
            if (recoveryTarget == null || currentPosition.distanceToSqr(recoveryTarget!!) < 4.0 || !isAllowedBiome(BlockPos.containing(recoveryTarget!!))) {
                recoveryTarget = findNearestAllowedRecoveryTarget(currentPosition, profile)
            }
        } else {
            recoveryTarget = null
        }

        val steeringYaw = when {
            recoveryTarget != null -> yawTo(recoveryTarget!!)
            else -> computeBiomeSteeringYaw(currentPosition, profile)
        }

        if (steeringYaw != null) {
            val yawDelta = Mth.wrapDegrees(steeringYaw - headingYaw).toDouble()
            targetTurnRateDeg = Mth.clamp(yawDelta * profile.turnBiasGain, -profile.maxTurnRateDeg, profile.maxTurnRateDeg)
        } else if (turnRetargetTicks <= 0) {
            targetTurnRateDeg = randomTurnRate(profile)
            turnRetargetTicks = randomRetargetTicks(profile)
        }

        turnRetargetTicks--
        currentTurnRateDeg = approach(currentTurnRateDeg, targetTurnRateDeg, profile.turnAccelerationDeg)
        headingYaw = Mth.wrapDegrees(headingYaw + currentTurnRateDeg.toFloat())

        val horizontalVelocity = getPullHeading().scale(profile.forwardSpeed)
        val verticalVelocity = computeVerticalVelocity(currentPosition, profile)
        val nextVelocity = Vec3(horizontalVelocity.x, verticalVelocity, horizontalVelocity.z)

        setDeltaMovement(nextVelocity)
        move(MoverType.SELF, nextVelocity)
        setYRot(headingYaw)
    }

    fun initializeHeading(heading: Vec3) {
        val normalized = if (heading.lengthSqr() <= 1.0e-6) {
            Vec3(0.0, 0.0, 1.0)
        } else {
            Vec3(heading.x, 0.0, heading.z).normalize()
        }

        headingYaw = (-Math.toDegrees(Mth.atan2(normalized.x, normalized.z))).toFloat()
        headingInitialized = true
        setYRot(headingYaw)
    }

    fun getPullHeading(): Vec3 = Vec3.directionFromRotation(0.0f, headingYaw)

    override fun addAdditionalSaveData(tag: CompoundTag) {
        tag.putFloat("HeadingYaw", headingYaw)
        tag.putDouble("CurrentTurnRateDeg", currentTurnRateDeg)
        tag.putDouble("TargetTurnRateDeg", targetTurnRateDeg)
        tag.putInt("TurnRetargetTicks", turnRetargetTicks)
        tag.putInt("OutsideAllowedBiomeTicks", outsideAllowedBiomeTicks)
        tag.putBoolean("HeadingInitialized", headingInitialized)

        recoveryTarget?.let {
            tag.putBoolean("HasRecoveryTarget", true)
            tag.putDouble("RecoveryTargetX", it.x)
            tag.putDouble("RecoveryTargetY", it.y)
            tag.putDouble("RecoveryTargetZ", it.z)
        }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        headingYaw = tag.getFloat("HeadingYaw")
        currentTurnRateDeg = tag.getDouble("CurrentTurnRateDeg")
        targetTurnRateDeg = tag.getDouble("TargetTurnRateDeg")
        turnRetargetTicks = tag.getInt("TurnRetargetTicks")
        outsideAllowedBiomeTicks = tag.getInt("OutsideAllowedBiomeTicks")
        headingInitialized = tag.getBoolean("HeadingInitialized")

        recoveryTarget = if (tag.getBoolean("HasRecoveryTarget")) {
            Vec3(tag.getDouble("RecoveryTargetX"), tag.getDouble("RecoveryTargetY"), tag.getDouble("RecoveryTargetZ"))
        } else {
            null
        }

        setYRot(headingYaw)
    }

    protected abstract fun getPullMovementProfile(): PullMovementProfile

    protected abstract fun isAllowedBiome(pos: BlockPos): Boolean

    private fun computeBiomeSteeringYaw(currentPosition: Vec3, profile: PullMovementProfile): Float? {
        val forwardYaw = headingYaw
        val leftYaw = Mth.wrapDegrees(headingYaw + profile.sideProbeAngleDeg)
        val rightYaw = Mth.wrapDegrees(headingYaw - profile.sideProbeAngleDeg)

        val forwardValid = isAllowedProbe(currentPosition, forwardYaw, profile.biomeProbeDistance)
        val leftValid = isAllowedProbe(currentPosition, leftYaw, profile.biomeProbeDistance)
        val rightValid = isAllowedProbe(currentPosition, rightYaw, profile.biomeProbeDistance)

        return when {
            forwardValid && leftValid && rightValid -> null
            !forwardValid && leftValid && !rightValid -> leftYaw
            !forwardValid && !leftValid && rightValid -> rightYaw
            !forwardValid && leftValid && rightValid -> if (currentTurnRateDeg >= 0.0) leftYaw else rightYaw
            !leftValid && rightValid -> rightYaw
            !rightValid && leftValid -> leftYaw
            !forwardValid -> Mth.wrapDegrees(headingYaw + 180.0f)
            else -> null
        }
    }

    private fun isAllowedProbe(origin: Vec3, probeYaw: Float, probeDistance: Double): Boolean {
        val probePosition = origin.add(Vec3.directionFromRotation(0.0f, probeYaw).scale(probeDistance))
        return isAllowedBiome(BlockPos.containing(probePosition))
    }

    private fun computeVerticalVelocity(currentPosition: Vec3, profile: PullMovementProfile): Double {
        val band = getVerticalBand(currentPosition.x, currentPosition.z) ?: return deltaMovement.y * profile.verticalDamping
        var correction = 0.0

        if (currentPosition.y < band.first) {
            correction = (band.first - currentPosition.y) * profile.verticalCorrectionStrength
        } else if (currentPosition.y > band.second) {
            correction = (band.second - currentPosition.y) * profile.verticalCorrectionStrength
        }

        val dampedVelocity = deltaMovement.y * profile.verticalDamping + correction
        return Mth.clamp(dampedVelocity, -profile.maxVerticalSpeed, profile.maxVerticalSpeed)
    }

    private fun getVerticalBand(x: Double, z: Double): Pair<Double, Double>? {
        val blockX = Mth.floor(x)
        val blockZ = Mth.floor(z)
        val floorY = level().getHeight(Heightmap.Types.OCEAN_FLOOR, blockX, blockZ).toDouble() + 3.0
        val surfaceY = level().getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ).toDouble() - 2.0

        return if (floorY <= surfaceY) {
            floorY to surfaceY
        } else {
            null
        }
    }

    private fun findNearestAllowedRecoveryTarget(origin: Vec3, profile: PullMovementProfile): Vec3? {
        for (radius in profile.recoverySearchStep..profile.recoverySearchRadius step profile.recoverySearchStep) {
            var bestCandidate: Vec3? = null
            var bestDistance = Double.POSITIVE_INFINITY

            for (index in 0 until profile.recoverySampleCount) {
                val angle = 2.0 * PI * index / profile.recoverySampleCount
                val candidateX = origin.x + cos(angle) * radius
                val candidateZ = origin.z + sin(angle) * radius
                val candidatePos = BlockPos.containing(candidateX, origin.y, candidateZ)

                if (!isAllowedBiome(candidatePos)) {
                    continue
                }

                val band = getVerticalBand(candidateX, candidateZ) ?: continue
                val candidateY = Mth.clamp(origin.y, band.first, band.second)
                val candidate = Vec3(candidateX, candidateY, candidateZ)
                val distance = candidate.distanceToSqr(origin)

                if (distance < bestDistance) {
                    bestDistance = distance
                    bestCandidate = candidate
                }
            }

            if (bestCandidate != null) {
                return bestCandidate
            }
        }

        return null
    }

    private fun yawTo(target: Vec3): Float {
        val offset = target.subtract(position())
        return (-Math.toDegrees(Mth.atan2(offset.x, offset.z))).toFloat()
    }

    private fun randomTurnRate(profile: PullMovementProfile): Double =
        (random.nextDouble() * 2.0 - 1.0) * profile.maxTurnRateDeg

    private fun randomRetargetTicks(profile: PullMovementProfile): Int =
        profile.retargetMinTicks + random.nextInt(profile.retargetMaxTicks - profile.retargetMinTicks + 1)

    private fun approach(current: Double, target: Double, maxDelta: Double): Double =
        when {
            current < target -> minOf(current + maxDelta, target)
            current > target -> maxOf(current - maxDelta, target)
            else -> current
        }
}
