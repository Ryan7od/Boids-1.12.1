package me.ryanod.boids.entity

import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.animal.AbstractFish
import net.minecraft.world.entity.animal.WaterAnimal
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.jvm.JvmName

open class BoidCodEntity(
    entityType: EntityType<out BoidCodEntity>,
    level: Level,
) : AbstractBoidFishEntity(entityType, level) {
    private enum class MovementState {
        CRUISE,
        STAGNANT,
        FLEE_PREDATOR,
    }

    private data class MovementProfile(
        val preferredSpeed: Double,
        val minSpeed: Double,
        val maxSpeed: Double,
        val turnResponse: Double,
        val wanderStrength: Double,
        val wanderRefreshMinTicks: Int,
        val wanderRefreshMaxTicks: Int,
        val wanderHorizontalJitter: Double,
        val wanderVerticalJitter: Double,
        val probeDistance: Double,
        val avoidanceStrength: Double,
    )

    private var movementState = MovementState.CRUISE
    private var stateTicksRemaining = 0
    private var wanderTicksRemaining = 0
    private var currentWanderOffset = Vec3.ZERO
    private var lastCruiseHeading = Vec3(0.0, 0.0, 1.0)

    override fun getAmbientSound(): SoundEvent = SoundEvents.COD_AMBIENT

    override fun getDeathSound(): SoundEvent = SoundEvents.COD_DEATH

    override fun getHurtSound(damageSource: DamageSource): SoundEvent = SoundEvents.COD_HURT

    override fun getFlopSound(): SoundEvent = SoundEvents.COD_FLOP

    override fun updateMovementSystem() {
        updateMovementState()

        val movementProfile = getMovementProfile()
        updateWanderOffset(movementProfile)

        val currentPosition = getBoidPosition()
        val currentVelocity = getBoidVelocity()
        val farNeighbours = getOtherBoidCods(CRUISE_FAR_RADIUS, CRUISE_FAR_K)
        val nearNeighbours = farNeighbours
            .asSequence()
            .filter { getBoidPosition(it).distanceToSqr(currentPosition) <= CRUISE_NEAR_RADIUS * CRUISE_NEAR_RADIUS }
            .take(CRUISE_NEAR_K)
            .toList()

        val cruiseDrive = computeCruiseDrive(currentPosition, currentVelocity, nearNeighbours, farNeighbours, movementProfile)
        val boidSteering = updateBoidMovement(nearNeighbours)
        val combinedVelocity = cruiseDrive.add(boidSteering)
        val obstacleAvoidance = computeObstacleAvoidance(combinedVelocity, movementProfile)
        var desiredVelocity = combinedVelocity.add(obstacleAvoidance)

        if (nearNeighbours.isNotEmpty()) {
            val averageNeighbourVelocity = averageVector(nearNeighbours.map(::getBoidVelocity))
            desiredVelocity = Vec3(
                desiredVelocity.x,
                Mth.lerp(CRUISE_VERTICAL_NEIGHBOUR_BLEND, desiredVelocity.y, averageNeighbourVelocity.y),
                desiredVelocity.z,
            )
        }

        desiredVelocity = desiredVelocity.multiply(1.0, CRUISE_VERTICAL_DAMPING, 1.0)

        val finalVelocity = clampVelocity(currentVelocity, desiredVelocity, movementProfile)

        setDeltaMovement(finalVelocity)
        hasImpulse = true
        lastCruiseHeading = normalizeOrFallback(finalVelocity, lastCruiseHeading)
        updateYawFromVelocity(finalVelocity, movementProfile.turnResponse)
    }

    protected fun updateBoidMovement(neighbours: List<BoidCodEntity>): Vec3 {
        if (neighbours.isEmpty()) {
            return Vec3.ZERO
        }

        val currentPosition = getBoidPosition()
        val currentVelocity = getBoidVelocity()
        val averageNeighbourPosition = averageVector(neighbours.map(::getBoidPosition))
        val averageNeighbourVelocity = averageVector(neighbours.map(::getBoidVelocity))

        val separation = neighbours
            .map { neighbour ->
                val offset = currentPosition.subtract(getBoidPosition(neighbour))
                val distance = offset.length()
                if (distance <= 1.0e-6 || distance > SEPARATION_RADIUS) {
                    Vec3.ZERO
                } else {
                    offset.normalize().scale((SEPARATION_RADIUS - distance) / SEPARATION_RADIUS)
                }
            }
            .fold(Vec3.ZERO) { total, offset -> total.add(offset) }
            .scale(SEPARATION_WEIGHT)

        val alignment = averageNeighbourVelocity
            .subtract(currentVelocity)
            .scale(ALIGNMENT_WEIGHT)

        val cohesion = averageNeighbourPosition
            .subtract(currentPosition)
            .scale(COHESION_WEIGHT)

        return separation
            .add(alignment)
            .add(cohesion)
    }

    protected fun getOtherBoidCods(radius: Double, kNearest: Int): List<BoidCodEntity> {
        val currentPosition = getBoidPosition()
        val searchBox = AABB.ofSize(currentPosition, radius * 2.0, radius * 2.0, radius * 2.0)

        return level().getEntitiesOfClass(BoidCodEntity::class.java, searchBox)
            .asSequence()
            .filterNot { it === this }
            .sortedBy { getBoidPosition(it).distanceToSqr(currentPosition) }
            .take(kNearest)
            .toList()
    }

    protected fun getBoidPosition(boid: BoidCodEntity = this): Vec3 = boid.position()

    protected fun getBoidVelocity(boid: BoidCodEntity = this): Vec3 = boid.deltaMovement

    private fun updateMovementState() {
        if (movementState != MovementState.CRUISE) {
            movementState = MovementState.CRUISE
            currentWanderOffset = Vec3.ZERO
            wanderTicksRemaining = 0
        }

        stateTicksRemaining = 0
    }

    private fun enterMovementState(newState: MovementState, durationTicks: Int) {
        if (movementState != newState) {
            movementState = newState
            currentWanderOffset = Vec3.ZERO
            wanderTicksRemaining = 0
        }

        stateTicksRemaining = durationTicks
    }

    private fun getMovementProfile(): MovementProfile =
        when (movementState) {
            MovementState.CRUISE -> MovementProfile(
                preferredSpeed = 0.09,
                minSpeed = 0.06,
                maxSpeed = 0.115,
                turnResponse = 0.1,
                wanderStrength = 0.0035,
                wanderRefreshMinTicks = 40,
                wanderRefreshMaxTicks = 80,
                wanderHorizontalJitter = 0.35,
                wanderVerticalJitter = 0.05,
                probeDistance = 1.15,
                avoidanceStrength = 0.035,
            )
            MovementState.STAGNANT -> MovementProfile(
                preferredSpeed = 0.03,
                minSpeed = 0.015,
                maxSpeed = 0.05,
                turnResponse = 0.08,
                wanderStrength = 0.01,
                wanderRefreshMinTicks = 12,
                wanderRefreshMaxTicks = 24,
                wanderHorizontalJitter = 0.65,
                wanderVerticalJitter = 0.12,
                probeDistance = 0.9,
                avoidanceStrength = 0.04,
            )
            MovementState.FLEE_PREDATOR -> MovementProfile(
                preferredSpeed = 0.14,
                minSpeed = 0.11,
                maxSpeed = 0.19,
                turnResponse = 0.22,
                wanderStrength = 0.004,
                wanderRefreshMinTicks = 8,
                wanderRefreshMaxTicks = 16,
                wanderHorizontalJitter = 0.4,
                wanderVerticalJitter = 0.1,
                probeDistance = 1.5,
                avoidanceStrength = 0.08,
            )
        }

    private fun updateWanderOffset(profile: MovementProfile) {
        if (wanderTicksRemaining > 0) {
            wanderTicksRemaining--
            return
        }

        currentWanderOffset = Vec3(
            (random.nextDouble() - 0.5) * profile.wanderHorizontalJitter,
            (random.nextDouble() - 0.5) * profile.wanderVerticalJitter,
            (random.nextDouble() - 0.5) * profile.wanderHorizontalJitter,
        )
        wanderTicksRemaining = randomTicks(profile.wanderRefreshMinTicks, profile.wanderRefreshMaxTicks)
    }

    private fun computeCruiseDrive(
        currentPosition: Vec3,
        currentVelocity: Vec3,
        nearNeighbours: List<BoidCodEntity>,
        farNeighbours: List<BoidCodEntity>,
        profile: MovementProfile,
    ): Vec3 {
        val baseHeading = if (nearNeighbours.size >= REJOIN_MIN_NEIGHBOURS) {
            val averageNeighbourHeading = normalizeOrFallback(averageVector(nearNeighbours.map(::getBoidVelocity)), lastCruiseHeading)
            normalizeOrFallback(
                lastCruiseHeading.scale(CRUISE_MEMORY_WEIGHT)
                    .add(averageNeighbourHeading.scale(1.0 - CRUISE_MEMORY_WEIGHT)),
                averageNeighbourHeading,
            )
        } else {
            lastCruiseHeading
        }

        val wanderedHeading = normalizeOrFallback(
            baseHeading.add(currentWanderOffset.scale(profile.wanderStrength)),
            baseHeading,
        )
        val nearCentroid = averageVector(nearNeighbours.map(::getBoidPosition))
        val centroidFollow = if (nearNeighbours.isEmpty()) {
            Vec3.ZERO
        } else {
            nearCentroid.subtract(currentPosition).scale(CENTROID_FOLLOW_WEIGHT)
        }
        val rejoinInfluence = computeRejoinInfluence(currentPosition, nearNeighbours, farNeighbours)
        val externalInfluence = computeExternalSchoolInfluence(currentPosition, currentVelocity)

        return wanderedHeading.scale(profile.preferredSpeed)
            .add(centroidFollow)
            .add(rejoinInfluence)
            .add(externalInfluence)
    }

    private fun computeObstacleAvoidance(
        desiredVelocity: Vec3,
        profile: MovementProfile,
    ): Vec3 {
        val heading = getMovementHeading(desiredVelocity)
        val horizontalHeading = normalizeOrFallback(Vec3(heading.x, 0.0, heading.z), heading)
        val side = Vec3(-horizontalHeading.z, 0.0, horizontalHeading.x)
        val probeOrigin = getProbeOrigin()
        val probeDirections = listOf(
            heading to 1.0,
            normalizeOrFallback(heading.add(side.scale(0.65)), heading) to 0.75,
            normalizeOrFallback(heading.add(side.scale(-0.65)), heading) to 0.75,
            normalizeOrFallback(heading.add(0.0, 0.35, 0.0), heading) to 0.5,
            normalizeOrFallback(heading.add(0.0, -0.35, 0.0), heading) to 0.5,
        )

        val avoidance = probeDirections.fold(Vec3.ZERO) { total, (direction, weight) ->
            val probePosition = probeOrigin.add(direction.scale(profile.probeDistance))
            if (isProbeBlocked(probePosition)) {
                total.add(direction.reverse().multiply(1.0, 0.35, 1.0).scale(weight))
            } else {
                total
            }
        }

        return normalizeOrZero(avoidance).scale(profile.avoidanceStrength)
    }

    private fun isProbeBlocked(probePosition: Vec3): Boolean {
        val blockPos = BlockPos.containing(probePosition)
        val fluidState = level().getFluidState(blockPos)
        if (!fluidState.`is`(FluidTags.WATER)) {
            return true
        }

        val blockState = level().getBlockState(blockPos)
        return !blockState.getCollisionShape(level(), blockPos).isEmpty
    }

    private fun clampVelocity(
        currentVelocity: Vec3,
        desiredVelocity: Vec3,
        profile: MovementProfile,
    ): Vec3 {
        val fallbackDirection = getMovementHeading(currentVelocity)
        val currentDirection = normalizeOrFallback(currentVelocity, fallbackDirection)
        val desiredDirection = normalizeOrFallback(desiredVelocity, fallbackDirection)
        val blendedDirection = normalizeOrFallback(
            currentDirection.scale(1.0 - profile.turnResponse).add(desiredDirection.scale(profile.turnResponse)),
            desiredDirection,
        )
        val desiredSpeed = Mth.clamp(desiredVelocity.length(), profile.minSpeed, profile.maxSpeed)

        return blendedDirection.scale(desiredSpeed)
    }

    private fun computeRejoinInfluence(
        currentPosition: Vec3,
        nearNeighbours: List<BoidCodEntity>,
        farNeighbours: List<BoidCodEntity>,
    ): Vec3 {
        if (nearNeighbours.size >= REJOIN_MIN_NEIGHBOURS || farNeighbours.isEmpty()) {
            return Vec3.ZERO
        }

        val farCentroid = averageVector(farNeighbours.map(::getBoidPosition))
        return farCentroid.subtract(currentPosition).scale(REJOIN_WEIGHT)
    }

    private fun updateYawFromVelocity(velocity: Vec3, turnResponse: Double) {
        if (velocity.horizontalDistanceSqr() <= 1.0e-6) {
            return
        }

        val targetYaw = (-Math.toDegrees(Mth.atan2(velocity.x, velocity.z))).toFloat()
        val yaw = lerpAngle(yRot, targetYaw, turnResponse.toFloat())
        setYRot(yaw)
        yBodyRot = yaw
        yHeadRot = yaw
    }

    private fun averageVector(vectors: List<Vec3>): Vec3 {
        if (vectors.isEmpty()) {
            return Vec3.ZERO
        }

        return vectors
            .fold(Vec3.ZERO) { total, vector -> total.add(vector) }
            .scale(1.0 / vectors.size.toDouble())
    }

    private fun getMovementHeading(referenceVelocity: Vec3): Vec3 {
        if (referenceVelocity.lengthSqr() > 1.0e-6) {
            return referenceVelocity.normalize()
        }

        if (lastCruiseHeading.lengthSqr() > 1.0e-6) {
            return lastCruiseHeading
        }

        return Vec3.directionFromRotation(0.0f, yRot)
    }

    private fun getPredatorEscapeHeading(currentVelocity: Vec3): Vec3 {
        val predatorThreat = detectPredatorThreat()
        if (predatorThreat != null && predatorThreat.lengthSqr() > 1.0e-6) {
            return predatorThreat.reverse().normalize()
        }

        return getMovementHeading(currentVelocity)
    }

    private fun getProbeOrigin(): Vec3 = position().add(0.0, bbHeight * 0.5, 0.0)

    protected open fun computeExternalSchoolInfluence(currentPosition: Vec3, currentVelocity: Vec3): Vec3 = Vec3.ZERO

    private fun detectPredatorThreat(): Vec3? = null

    private fun normalizeOrFallback(vector: Vec3, fallback: Vec3): Vec3 {
        if (vector.lengthSqr() <= 1.0e-6) {
            return normalizeOrZero(fallback)
        }

        return vector.normalize()
    }

    private fun normalizeOrZero(vector: Vec3): Vec3 =
        if (vector.lengthSqr() <= 1.0e-6) {
            Vec3.ZERO
        } else {
            vector.normalize()
        }

    private fun randomTicks(minTicks: Int, maxTicks: Int): Int =
        minTicks + random.nextInt(maxTicks - minTicks + 1)

    private fun lerpAngle(current: Float, target: Float, alpha: Float): Float {
        val delta = Mth.wrapDegrees(target - current)
        return current + alpha * delta
    }

    companion object {
        private const val CRUISE_NEAR_RADIUS = 5.5
        private const val CRUISE_NEAR_K = 12
        private const val CRUISE_FAR_RADIUS = 12.0
        private const val CRUISE_FAR_K = 24
        private const val REJOIN_MIN_NEIGHBOURS = 3
        private const val SEPARATION_RADIUS = 0.6
        private const val SEPARATION_WEIGHT = 0.03
        private const val ALIGNMENT_WEIGHT = 0.09
        private const val COHESION_WEIGHT = 0.045
        private const val CENTROID_FOLLOW_WEIGHT = 0.018
        private const val REJOIN_WEIGHT = 0.035
        private const val CRUISE_MEMORY_WEIGHT = 0.35
        private const val CRUISE_VERTICAL_DAMPING = 0.35
        private const val CRUISE_VERTICAL_NEIGHBOUR_BLEND = 0.15

        @JvmStatic
        @JvmName("createBoidCodAttributes")
        fun createAttributes(): AttributeSupplier.Builder = AbstractFish.createAttributes()

        @JvmStatic
        fun canSpawn(
            entityType: EntityType<BoidCodEntity>,
            level: ServerLevelAccessor,
            spawnType: MobSpawnType,
            pos: BlockPos,
            random: RandomSource,
        ): Boolean = WaterAnimal.checkSurfaceWaterAnimalSpawnRules(entityType, level, spawnType, pos, random)
    }
}
