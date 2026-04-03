package me.ryanod.boids.entity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
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
import java.util.UUID

open class BoidCodEntity(
    entityType: EntityType<out BoidCodEntity>,
    level: Level,
) : AbstractBoidFishEntity(entityType, level) {
    private data class MovementProfile(
        val minSpeed: Double,
        val maxSpeed: Double,
        val turnResponse: Double,
        val probeDistance: Double,
        val avoidanceStrength: Double,
    )

    private var lastCruiseHeading = Vec3(0.0, 0.0, 1.0)
    private var currentPreferredPullUuid: UUID? = null
    private var candidatePullEntityUuid: UUID? = null
    private var candidatePreferredPullRescoreCount = 0
    private var currentPreferredPullMissingRescoreCount = 0
    private var pullRescoreCooldownTicks = 0
    private val pullAffinityHistory = mutableMapOf<UUID, Float>()

    private data class ScoredPull(
        val pullEntity: BoidCodPullEntity,
        val score: Double,
    )

    override fun getAmbientSound(): SoundEvent = SoundEvents.COD_AMBIENT

    override fun getDeathSound(): SoundEvent = SoundEvents.COD_DEATH

    override fun getHurtSound(damageSource: DamageSource): SoundEvent = SoundEvents.COD_HURT

    override fun getFlopSound(): SoundEvent = SoundEvents.COD_FLOP

    fun seedPullPreference(anchor: BoidCodPullEntity, historyAffinity: Float) {
        currentPreferredPullUuid = anchor.uuid
        candidatePullEntityUuid = null
        candidatePreferredPullRescoreCount = 0
        currentPreferredPullMissingRescoreCount = 0
        pullRescoreCooldownTicks = PULL_RESCORE_INTERVAL
        pullAffinityHistory[anchor.uuid] = Mth.clamp(historyAffinity, 0.0f, 1.0f)
        prunePullAffinityHistory()
    }

    fun initializeCruiseHeading(heading: Vec3) {
        val normalizedHeading = normalizeOrFallback(heading, lastCruiseHeading)
        lastCruiseHeading = normalizedHeading
        val initialVelocity = normalizedHeading.scale(CRUISE_INITIAL_SPEED)
        setDeltaMovement(initialVelocity)
        updateYawFromVelocity(initialVelocity, getMovementProfile().turnResponse)
    }

    fun getCurrentPreferredPullUuid(): UUID? = currentPreferredPullUuid

    override fun updateMovementSystem() {
        val movementProfile = getMovementProfile()

        val currentPosition = getBoidPosition()
        val currentVelocity = getBoidVelocity()
        val farNeighbours = getOtherBoidCods(CRUISE_FAR_RADIUS, CRUISE_FAR_K)
        val nearNeighbours = farNeighbours
            .asSequence()
            .filter { getBoidPosition(it).distanceToSqr(currentPosition) <= CRUISE_NEAR_RADIUS * CRUISE_NEAR_RADIUS }
            .take(CRUISE_NEAR_K)
            .toList()

        val schoolInfluence = computeSchoolInfluence(currentPosition, currentVelocity, nearNeighbours, farNeighbours)
        val boidInfluence = updateBoidMovement(nearNeighbours)
        val objectAvoidanceInfluence = computeObstacleAvoidance(
            schoolInfluence
                .scale(SCHOOL_WEIGHT)
                .add(boidInfluence.scale(BOID_WEIGHT)),
            movementProfile,
        )
        val fleeInfluence = computeFleeInfluence(currentPosition, currentVelocity)
        val fleeWeight = getFleeWeight()
        var desiredVelocity = schoolInfluence
            .scale(SCHOOL_WEIGHT)
            .add(boidInfluence.scale(BOID_WEIGHT))
            .add(objectAvoidanceInfluence.scale(OBJECT_AVOIDANCE_WEIGHT))
            .add(fleeInfluence.scale(fleeWeight))

        if (nearNeighbours.isNotEmpty()) {
            val averageNeighbourVelocity = averageVector(nearNeighbours.map(::getBoidVelocity))
            desiredVelocity = Vec3(
                desiredVelocity.x,
                Mth.lerp(FISH_VERTICAL_NEIGHBOUR_BLEND, desiredVelocity.y, averageNeighbourVelocity.y),
                desiredVelocity.z,
            )
        }

        desiredVelocity = desiredVelocity.multiply(1.0, FISH_VERTICAL_DAMPING, 1.0)

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

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)

        currentPreferredPullUuid?.let { tag.putUUID("CurrentPreferredPullUuid", it) }
        candidatePullEntityUuid?.let { tag.putUUID("CandidatePreferredPullUuid", it) }
        tag.putInt("CandidatePreferredPullRescoreCount", candidatePreferredPullRescoreCount)
        tag.putInt("CurrentPreferredPullMissingRescoreCount", currentPreferredPullMissingRescoreCount)
        tag.putInt("PullRescoreCooldownTicks", pullRescoreCooldownTicks)

        val affinityTag = CompoundTag()
        pullAffinityHistory.forEach { (uuid, affinity) ->
            affinityTag.putFloat(uuid.toString(), affinity)
        }
        tag.put("PullAffinityHistory", affinityTag)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)

        currentPreferredPullUuid = if (tag.hasUUID("CurrentPreferredPullUuid")) tag.getUUID("CurrentPreferredPullUuid") else null
        candidatePullEntityUuid = if (tag.hasUUID("CandidatePreferredPullUuid")) tag.getUUID("CandidatePreferredPullUuid") else null
        candidatePreferredPullRescoreCount = tag.getInt("CandidatePreferredPullRescoreCount")
        currentPreferredPullMissingRescoreCount = tag.getInt("CurrentPreferredPullMissingRescoreCount")
        pullRescoreCooldownTicks = tag.getInt("PullRescoreCooldownTicks")

        pullAffinityHistory.clear()
        val affinityTag = tag.getCompound("PullAffinityHistory")
        affinityTag.getAllKeys().forEach { key ->
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
            pullAffinityHistory[uuid] = Mth.clamp(affinityTag.getFloat(key), 0.0f, 1.0f)
        }
        prunePullAffinityHistory()
    }

    private fun getMovementProfile(): MovementProfile =
        MovementProfile(
            minSpeed = 0.06,
            maxSpeed = 0.115,
            turnResponse = 0.1,
            probeDistance = 1.15,
            avoidanceStrength = 0.035,
        )

    private fun computeSchoolInfluence(
        currentPosition: Vec3,
        currentVelocity: Vec3,
        nearNeighbours: List<BoidCodEntity>,
        farNeighbours: List<BoidCodEntity>,
    ): Vec3 {
        val preferredPull = resolvePreferredPullEntity(currentPosition, currentVelocity)
        val schoolHeading = preferredPull?.getPullHeading() ?: lastCruiseHeading
        val headingBlend = normalizeOrFallback(
            lastCruiseHeading.scale(FISH_HEADING_MEMORY_WEIGHT)
                .add(schoolHeading.scale(1.0 - FISH_HEADING_MEMORY_WEIGHT)),
            schoolHeading,
        )

        val directionAlignmentMultiplier = preferredPull?.getSchoolDirectionMultiplier()?.toDouble() ?: 1.0
        val forwardDrive = headingBlend.scale(FISH_BASE_SCHOOL_SPEED * directionAlignmentMultiplier)

        val nearCentroid = averageVector(nearNeighbours.map(::getBoidPosition))
        val centroidFollow = if (nearNeighbours.isEmpty()) {
            Vec3.ZERO
        } else {
            nearCentroid.subtract(currentPosition).scale(CENTROID_FOLLOW_WEIGHT)
        }
        val rejoinInfluence = computeRejoinInfluence(currentPosition, nearNeighbours, farNeighbours)
        val externalInfluence = computeExternalSchoolInfluence(currentPosition, currentVelocity)

        return forwardDrive
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

    protected open fun computeExternalSchoolInfluence(currentPosition: Vec3, currentVelocity: Vec3): Vec3 {
        val pullEntity = resolvePreferredPullEntity(currentPosition, currentVelocity) ?: return Vec3.ZERO
        if (pullEntity.position().distanceToSqr(currentPosition) > PULL_SEARCH_RADIUS * PULL_SEARCH_RADIUS) {
            return Vec3.ZERO
        }

        val schoolDirectionMultiplier = pullEntity.getSchoolDirectionMultiplier().toDouble()
        val schoolTetherMultiplier = pullEntity.getSchoolTetherMultiplier().toDouble()
        val headingInfluence = normalizeOrFallback(
            pullEntity.getPullHeading(),
            getMovementHeading(currentVelocity),
        ).scale(PULL_HEADING_WEIGHT * schoolDirectionMultiplier)
        val tetherInfluence = pullEntity.position()
            .subtract(currentPosition)
            .scale(PULL_TETHER_WEIGHT * schoolTetherMultiplier)

        return headingInfluence.add(tetherInfluence)
    }

    private fun detectPredatorThreat(): Vec3? = null

    private fun computeFleeInfluence(currentPosition: Vec3, currentVelocity: Vec3): Vec3 = Vec3.ZERO

    private fun getFleeWeight(): Double = 0.0

    private fun resolvePreferredPullEntity(currentPosition: Vec3, currentVelocity: Vec3): BoidCodPullEntity? {
        rescorePreferredPullIfNeeded(currentPosition, currentVelocity)
        return resolvePullEntity(currentPreferredPullUuid)
    }

    private fun rescorePreferredPullIfNeeded(currentPosition: Vec3, currentVelocity: Vec3) {
        if (pullRescoreCooldownTicks > 0) {
            pullRescoreCooldownTicks--
            return
        }

        pullRescoreCooldownTicks = PULL_RESCORE_INTERVAL - 1
        val codHeading = getMovementHeading(currentVelocity)
        val nearbyPulls = getNearbyPullEntities(currentPosition, PULL_SEARCH_RADIUS)

        if (nearbyPulls.isEmpty()) {
            if (currentPreferredPullUuid != null) {
                currentPreferredPullMissingRescoreCount++
                if (currentPreferredPullMissingRescoreCount >= PULL_MISSING_CLEAR_RESCORING) {
                    clearCurrentPreferredPull()
                }
            }
            clearCandidatePullPreference()
            updatePullAffinityHistory()
            return
        }

        val scoredPulls = nearbyPulls
            .map { pullEntity -> ScoredPull(pullEntity, computePullScore(pullEntity, currentPosition, codHeading)) }
        val bestCandidate = scoredPulls.maxByOrNull { it.score } ?: run {
            updatePullAffinityHistory()
            return
        }
        val currentPreferredScore = currentPreferredPullUuid?.let { preferredUuid ->
            scoredPulls.firstOrNull { it.pullEntity.uuid == preferredUuid }
        }

        when {
            currentPreferredPullUuid == null -> {
                currentPreferredPullMissingRescoreCount = 0
                clearCandidatePullPreference()

                if (bestCandidate.score >= PULL_ADOPT_SCORE_THRESHOLD) {
                    currentPreferredPullUuid = bestCandidate.pullEntity.uuid
                }
            }

            currentPreferredScore == null -> {
                currentPreferredPullMissingRescoreCount++
                clearCandidatePullPreference()

                if (currentPreferredPullMissingRescoreCount >= PULL_MISSING_CLEAR_RESCORING) {
                    clearCurrentPreferredPull()
                    if (bestCandidate.score >= PULL_ADOPT_SCORE_THRESHOLD) {
                        currentPreferredPullUuid = bestCandidate.pullEntity.uuid
                    }
                }
            }

            bestCandidate.pullEntity.uuid == currentPreferredPullUuid -> {
                currentPreferredPullMissingRescoreCount = 0
                clearCandidatePullPreference()
            }

            bestCandidate.score >= currentPreferredScore.score + PULL_SWITCH_SCORE_MARGIN -> {
                currentPreferredPullMissingRescoreCount = 0
                if (candidatePullEntityUuid == bestCandidate.pullEntity.uuid) {
                    candidatePreferredPullRescoreCount++
                } else {
                    candidatePullEntityUuid = bestCandidate.pullEntity.uuid
                    candidatePreferredPullRescoreCount = 1
                }

                if (candidatePreferredPullRescoreCount >= PULL_CANDIDATE_DWELL_RESCORING) {
                    currentPreferredPullUuid = bestCandidate.pullEntity.uuid
                    currentPreferredPullMissingRescoreCount = 0
                    clearCandidatePullPreference()
                }
            }

            else -> {
                currentPreferredPullMissingRescoreCount = 0
                clearCandidatePullPreference()
            }
        }

        updatePullAffinityHistory()
    }

    private fun computePullScore(
        pullEntity: BoidCodPullEntity,
        currentPosition: Vec3,
        codHeading: Vec3,
    ): Double {
        val distance = pullEntity.position().distanceTo(currentPosition)
        val distanceScore = 1.0 - Mth.clamp(distance / PULL_SEARCH_RADIUS, 0.0, 1.0)
        val headingDot = Mth.clamp(codHeading.dot(pullEntity.getPullHeading()), -1.0, 1.0)
        val headingScore = (headingDot + 1.0) * 0.5
        val sizeScore = Mth.clamp(pullEntity.getSmoothedFollowerCount() / PULL_SIZE_SCORE_DIVISOR, 0.0f, 1.0f).toDouble()
        val historyScore = Mth.clamp(pullAffinityHistory[pullEntity.uuid] ?: 0.0f, 0.0f, 1.0f).toDouble()

        return distanceScore * PULL_DISTANCE_SCORE_WEIGHT +
            headingScore * PULL_HEADING_SCORE_WEIGHT +
            sizeScore * PULL_SIZE_SCORE_WEIGHT +
            historyScore * PULL_HISTORY_SCORE_WEIGHT
    }

    private fun resolvePullEntity(uuid: UUID?): BoidCodPullEntity? {
        val pullUuid = uuid ?: return null
        val serverLevel = level() as? net.minecraft.server.level.ServerLevel ?: return null
        return serverLevel.getEntity(pullUuid) as? BoidCodPullEntity
    }

    private fun getNearbyPullEntities(currentPosition: Vec3, radius: Double): List<BoidCodPullEntity> {
        val searchBox = AABB.ofSize(currentPosition, radius * 2.0, radius * 2.0, radius * 2.0)
        return level().getEntitiesOfClass(BoidCodPullEntity::class.java, searchBox)
            .asSequence()
            .filter { it.isAlive }
            .sortedBy { it.position().distanceToSqr(currentPosition) }
            .toList()
    }

    private fun clearCurrentPreferredPull() {
        currentPreferredPullUuid = null
        currentPreferredPullMissingRescoreCount = 0
        clearCandidatePullPreference()
    }

    private fun clearCandidatePullPreference() {
        candidatePullEntityUuid = null
        candidatePreferredPullRescoreCount = 0
    }

    private fun updatePullAffinityHistory() {
        pullAffinityHistory.entries.toList().forEach { (uuid, affinity) ->
            val decayedAffinity = affinity * PULL_HISTORY_DECAY
            if (decayedAffinity < PULL_HISTORY_PRUNE_THRESHOLD) {
                pullAffinityHistory.remove(uuid)
            } else {
                pullAffinityHistory[uuid] = decayedAffinity
            }
        }

        currentPreferredPullUuid?.let { preferredUuid ->
            val seededAffinity = pullAffinityHistory.getOrDefault(preferredUuid, 0.0f) + PULL_HISTORY_GAIN
            pullAffinityHistory[preferredUuid] = Mth.clamp(seededAffinity, 0.0f, 1.0f)
        }

        prunePullAffinityHistory()
    }

    private fun prunePullAffinityHistory() {
        val retainedEntries = pullAffinityHistory.entries
            .filter { it.value >= PULL_HISTORY_PRUNE_THRESHOLD }
            .sortedByDescending { it.value }
            .take(PULL_HISTORY_MAX_ENTRIES)

        pullAffinityHistory.clear()
        retainedEntries.forEach { entry ->
            pullAffinityHistory[entry.key] = entry.value
        }
    }

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
        private const val SCHOOL_WEIGHT = 1.0
        private const val BOID_WEIGHT = 1.0
        private const val OBJECT_AVOIDANCE_WEIGHT = 1.0
        private const val FISH_HEADING_MEMORY_WEIGHT = 0.35
        private const val FISH_VERTICAL_DAMPING = 0.35
        private const val FISH_VERTICAL_NEIGHBOUR_BLEND = 0.15
        private const val FISH_BASE_SCHOOL_SPEED = 0.09
        private const val PULL_SEARCH_RADIUS = 48.0
        private const val PULL_HEADING_WEIGHT = 0.065
        private const val PULL_TETHER_WEIGHT = 0.018
        private const val PULL_RESCORE_INTERVAL = 10
        private const val PULL_ADOPT_SCORE_THRESHOLD = 0.20
        private const val PULL_SWITCH_SCORE_MARGIN = 0.12
        private const val PULL_CANDIDATE_DWELL_RESCORING = 3
        private const val PULL_MISSING_CLEAR_RESCORING = 6
        private const val PULL_SIZE_SCORE_DIVISOR = 16.0f
        private const val PULL_DISTANCE_SCORE_WEIGHT = 0.45
        private const val PULL_HEADING_SCORE_WEIGHT = 0.20
        private const val PULL_SIZE_SCORE_WEIGHT = 0.10
        private const val PULL_HISTORY_SCORE_WEIGHT = 0.25
        private const val PULL_HISTORY_DECAY = 0.96f
        private const val PULL_HISTORY_GAIN = 0.08f
        private const val PULL_HISTORY_PRUNE_THRESHOLD = 0.05f
        private const val PULL_HISTORY_MAX_ENTRIES = 4
        private const val CRUISE_INITIAL_SPEED = 0.09

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
