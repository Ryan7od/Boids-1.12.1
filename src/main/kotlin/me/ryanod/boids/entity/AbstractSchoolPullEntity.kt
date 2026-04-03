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
    protected enum class SchoolPullState {
        CRUISE,
        LOITER,
    }

    protected data class PullStateProfile(
        val forwardSpeed: Double,
        val maxTurnRateDeg: Double,
        val turnAccelerationDeg: Double,
        val retargetMinTicks: Int,
        val retargetMaxTicks: Int,
        val turnBiasGain: Double,
    )

    protected data class PullMovementProfile(
        val cruise: PullStateProfile,
        val loiter: PullStateProfile,
        val cruiseMinTicks: Int,
        val cruiseMaxTicks: Int,
        val loiterMinTicks: Int,
        val loiterMaxTicks: Int,
        val loiterRadius: Double,
        val loiterSchoolDirectionMultiplier: Float,
        val loiterSchoolTetherMultiplier: Float,
        val biomeProbeDistance: Double,
        val sideProbeAngleDeg: Float,
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
    private var stateInitialized = false
    private var schoolPullState = SchoolPullState.CRUISE
    private var stateTicksRemaining = 0
    private var loiterCenter: Vec3? = null

    init {
        noPhysics = true
        setNoGravity(true)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) = Unit

    override fun tick() {
        super.tick()

        noPhysics = true
        setNoGravity(true)

        if (level().isClientSide) {
            return
        }

        val profile = getPullMovementProfile()

        if (!headingInitialized) {
            initializeHeading(Vec3.directionFromRotation(0.0f, yRot))
        }

        val currentPosition = position()
        if (!stateInitialized) {
            enterState(SchoolPullState.CRUISE, randomStateTicks(profile.cruiseMinTicks, profile.cruiseMaxTicks), currentPosition)
            stateInitialized = true
        } else {
            updateState(profile, currentPosition)
        }

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
            computeBiomeSteeringYaw(currentPosition, profile) != null -> computeBiomeSteeringYaw(currentPosition, profile)
            else -> computeLoiterSteeringYaw(currentPosition, profile)
        }
        val activeStateProfile = getActiveStateProfile(profile)

        if (steeringYaw != null) {
            val yawDelta = Mth.wrapDegrees(steeringYaw - headingYaw).toDouble()
            targetTurnRateDeg = Mth.clamp(
                yawDelta * activeStateProfile.turnBiasGain,
                -activeStateProfile.maxTurnRateDeg,
                activeStateProfile.maxTurnRateDeg,
            )
        } else if (turnRetargetTicks <= 0) {
            targetTurnRateDeg = randomTurnRate(activeStateProfile)
            turnRetargetTicks = randomRetargetTicks(activeStateProfile)
        }

        turnRetargetTicks--
        currentTurnRateDeg = approach(currentTurnRateDeg, targetTurnRateDeg, activeStateProfile.turnAccelerationDeg)
        headingYaw = Mth.wrapDegrees(headingYaw + currentTurnRateDeg.toFloat())

        val horizontalVelocity = getPullHeading().scale(activeStateProfile.forwardSpeed)
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
        tag.putBoolean("StateInitialized", stateInitialized)
        tag.putInt("SchoolPullState", schoolPullState.ordinal)
        tag.putInt("StateTicksRemaining", stateTicksRemaining)

        recoveryTarget?.let {
            tag.putBoolean("HasRecoveryTarget", true)
            tag.putDouble("RecoveryTargetX", it.x)
            tag.putDouble("RecoveryTargetY", it.y)
            tag.putDouble("RecoveryTargetZ", it.z)
        }
        loiterCenter?.let {
            tag.putBoolean("HasLoiterCenter", true)
            tag.putDouble("LoiterCenterX", it.x)
            tag.putDouble("LoiterCenterY", it.y)
            tag.putDouble("LoiterCenterZ", it.z)
        }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        headingYaw = tag.getFloat("HeadingYaw")
        currentTurnRateDeg = tag.getDouble("CurrentTurnRateDeg")
        targetTurnRateDeg = tag.getDouble("TargetTurnRateDeg")
        turnRetargetTicks = tag.getInt("TurnRetargetTicks")
        outsideAllowedBiomeTicks = tag.getInt("OutsideAllowedBiomeTicks")
        headingInitialized = tag.getBoolean("HeadingInitialized")
        stateInitialized = tag.getBoolean("StateInitialized")
        schoolPullState = SchoolPullState.entries.getOrElse(tag.getInt("SchoolPullState")) { SchoolPullState.CRUISE }
        stateTicksRemaining = tag.getInt("StateTicksRemaining")

        recoveryTarget = if (tag.getBoolean("HasRecoveryTarget")) {
            Vec3(tag.getDouble("RecoveryTargetX"), tag.getDouble("RecoveryTargetY"), tag.getDouble("RecoveryTargetZ"))
        } else {
            null
        }
        loiterCenter = if (tag.getBoolean("HasLoiterCenter")) {
            Vec3(tag.getDouble("LoiterCenterX"), tag.getDouble("LoiterCenterY"), tag.getDouble("LoiterCenterZ"))
        } else {
            null
        }

        setYRot(headingYaw)
    }

    protected abstract fun getPullMovementProfile(): PullMovementProfile

    protected abstract fun isAllowedBiome(pos: BlockPos): Boolean

    fun getSchoolDirectionMultiplier(): Float =
        if (schoolPullState == SchoolPullState.LOITER) {
            getPullMovementProfile().loiterSchoolDirectionMultiplier
        } else {
            1.0f
        }

    fun getSchoolTetherMultiplier(): Float =
        if (schoolPullState == SchoolPullState.LOITER) {
            getPullMovementProfile().loiterSchoolTetherMultiplier
        } else {
            1.0f
        }

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

    private fun computeLoiterSteeringYaw(currentPosition: Vec3, profile: PullMovementProfile): Float? {
        if (schoolPullState != SchoolPullState.LOITER) {
            return null
        }

        val loiterOrigin = loiterCenter ?: return null
        val horizontalOffset = Vec3(loiterOrigin.x - currentPosition.x, 0.0, loiterOrigin.z - currentPosition.z)
        val distanceToCenter = horizontalOffset.length()
        if (distanceToCenter <= profile.loiterRadius) {
            return null
        }

        return yawTo(Vec3(loiterOrigin.x, currentPosition.y, loiterOrigin.z))
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

    private fun updateState(profile: PullMovementProfile, currentPosition: Vec3) {
        if (stateTicksRemaining > 0) {
            stateTicksRemaining--
            return
        }

        when (schoolPullState) {
            SchoolPullState.CRUISE -> enterState(
                SchoolPullState.LOITER,
                randomStateTicks(profile.loiterMinTicks, profile.loiterMaxTicks),
                currentPosition,
            )
            SchoolPullState.LOITER -> enterState(
                SchoolPullState.CRUISE,
                randomStateTicks(profile.cruiseMinTicks, profile.cruiseMaxTicks),
                currentPosition,
            )
        }
    }

    private fun enterState(state: SchoolPullState, durationTicks: Int, currentPosition: Vec3) {
        schoolPullState = state
        stateTicksRemaining = durationTicks
        loiterCenter = if (state == SchoolPullState.LOITER) currentPosition else null
        turnRetargetTicks = 0
    }

    private fun getActiveStateProfile(profile: PullMovementProfile): PullStateProfile =
        if (schoolPullState == SchoolPullState.LOITER) profile.loiter else profile.cruise

    private fun randomTurnRate(profile: PullStateProfile): Double =
        (random.nextDouble() * 2.0 - 1.0) * profile.maxTurnRateDeg

    private fun randomRetargetTicks(profile: PullStateProfile): Int =
        profile.retargetMinTicks + random.nextInt(profile.retargetMaxTicks - profile.retargetMinTicks + 1)

    private fun randomStateTicks(minTicks: Int, maxTicks: Int): Int =
        minTicks + random.nextInt(maxTicks - minTicks + 1)

    private fun approach(current: Double, target: Double, maxDelta: Double): Double =
        when {
            current < target -> minOf(current + maxDelta, target)
            current > target -> maxOf(current - maxDelta, target)
            else -> current
        }
}
