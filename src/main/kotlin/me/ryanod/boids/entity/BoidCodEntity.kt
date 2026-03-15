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

class BoidCodEntity(
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

    override fun getAmbientSound(): SoundEvent = SoundEvents.COD_AMBIENT

    override fun getDeathSound(): SoundEvent = SoundEvents.COD_DEATH

    override fun getHurtSound(damageSource: DamageSource): SoundEvent = SoundEvents.COD_HURT

    override fun getFlopSound(): SoundEvent = SoundEvents.COD_FLOP

    override fun updateMovementSystem() {
        val neighbours = getOtherBoidCods(PERCEPTION_RADIUS, K_NEAREST)
        updateMovementState()

        val movementProfile = getMovementProfile()
        updateWanderOffset(movementProfile)

        val currentVelocity = getBoidVelocity()
        val stateDrive = computeStateDrive(movementProfile, currentVelocity)
        val boidSteering = updateBoidMovement(neighbours)
        val preliminaryVelocity = stateDrive.add(boidSteering)
        val obstacleAvoidance = computeObstacleAvoidance(preliminaryVelocity, movementProfile)
        val desiredVelocity = preliminaryVelocity.add(obstacleAvoidance)
        val finalVelocity = clampVelocity(currentVelocity, desiredVelocity, movementProfile)

        setDeltaMovement(finalVelocity)
        hasImpulse = true
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
        val predatorThreat = detectPredatorThreat()

        if (predatorThreat != null) {
            enterMovementState(MovementState.FLEE_PREDATOR, randomTicks(30, 50))
            return
        }

        if (stateTicksRemaining > 0) {
            stateTicksRemaining--
        }

        when (movementState) {
            MovementState.CRUISE -> {
                if (random.nextInt(160) == 0) {
                    enterMovementState(MovementState.STAGNANT, randomTicks(30, 60))
                }
            }
            MovementState.STAGNANT -> {
                if (stateTicksRemaining <= 0) {
                    enterMovementState(MovementState.CRUISE, 0)
                }
            }
            MovementState.FLEE_PREDATOR -> {
                if (stateTicksRemaining <= 0) {
                    enterMovementState(MovementState.CRUISE, 0)
                }
            }
        }
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
                preferredSpeed = 0.085,
                minSpeed = 0.05,
                maxSpeed = 0.12,
                turnResponse = 0.12,
                wanderStrength = 0.014,
                wanderRefreshMinTicks = 25,
                wanderRefreshMaxTicks = 50,
                wanderHorizontalJitter = 1.0,
                wanderVerticalJitter = 0.18,
                probeDistance = 1.15,
                avoidanceStrength = 0.05,
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

    private fun computeStateDrive(profile: MovementProfile, currentVelocity: Vec3): Vec3 {
        val heading = when (movementState) {
            MovementState.FLEE_PREDATOR -> getPredatorEscapeHeading(currentVelocity)
            MovementState.CRUISE, MovementState.STAGNANT -> getMovementHeading(currentVelocity)
        }

        return heading.scale(profile.preferredSpeed)
            .add(currentWanderOffset.scale(profile.wanderStrength))
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
        private const val PERCEPTION_RADIUS = 4.0
        private const val K_NEAREST = 12
        private const val SEPARATION_RADIUS = 0.75
        private const val SEPARATION_WEIGHT = 0.05
        private const val ALIGNMENT_WEIGHT = 0.035
        private const val COHESION_WEIGHT = 0.012

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
