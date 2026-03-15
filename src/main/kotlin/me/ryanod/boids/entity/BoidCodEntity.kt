package me.ryanod.boids.entity

import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.RandomSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.animal.AbstractFish
import net.minecraft.world.entity.animal.WaterAnimal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.jvm.JvmName

class BoidCodEntity(
    entityType: EntityType<out BoidCodEntity>,
    level: Level,
) : AbstractBoidFishEntity(entityType, level) {
    override fun getBucketItemStack(): ItemStack = Items.COD_BUCKET.defaultInstance

    override fun getAmbientSound(): SoundEvent = SoundEvents.COD_AMBIENT

    override fun getDeathSound(): SoundEvent = SoundEvents.COD_DEATH

    override fun getHurtSound(damageSource: DamageSource): SoundEvent = SoundEvents.COD_HURT

    override fun getFlopSound(): SoundEvent = SoundEvents.COD_FLOP

    override fun updateBoidMovement() {
        val perceptionRadius = 4.0
        val kNearest = 6

        val otherBoids = getOtherBoidCods(perceptionRadius, kNearest)
        if (otherBoids.isEmpty()) {
            return
        }

        val currentPosition = getBoidPosition()
        val currentVelocity = getBoidVelocity()

        // Rule 1 - Average poistion
        val neighbourPullScaleFactor = 0.01

        val averageNeighbourPosition = otherBoids
            .map(::getBoidPosition)
            .reduce { total, position -> total.add(position) }
            .scale(1.0 / otherBoids.size.toDouble())

        val pullTowardNeighbours = averageNeighbourPosition.subtract(currentPosition).scale(neighbourPullScaleFactor)

        // Rule 2 - Keep small distance
        val closeRadius = 0.3

        val pushFromCloseNeighbours = otherBoids
            .map(::getBoidPosition)
            .map { currentPosition.subtract(it) }
            .filter { it.lengthSqr() < closeRadius * closeRadius }
            .fold(Vec3.ZERO) { total, offset -> total.add(offset) }


        // Rule 3 - Match velocity
        val averageVelocityScaleFactor = 0.125

        val averageNeighbourVelocity = otherBoids
            .map(::getBoidVelocity)
            .reduce { total, velocity -> total.add(velocity) }
            .scale(1.0 / otherBoids.size.toDouble())

        val pullTowardAverageVelocity = averageNeighbourVelocity.subtract(currentVelocity).scale(averageVelocityScaleFactor)

        
        // Update velocity
        val inertia = 0.9

        val updatedVelocity = currentVelocity
            .scale(inertia)
            .add(pullTowardAverageVelocity)
            .add(pullTowardNeighbours)
            .add(pushFromCloseNeighbours)

        // Clamp speed
        val maxSpeed = 0.08

        val clampedVelocity =
            if (updatedVelocity.lengthSqr() > maxSpeed * maxSpeed) {
                updatedVelocity.normalize().scale(maxSpeed)
            } else {
                updatedVelocity
            }

        setDeltaMovement(updatedVelocity)

        // Change model yaw
        if (updatedVelocity.horizontalDistanceSqr() > 1.0e-6) {
            val targetYaw = (-Math.toDegrees(kotlin.math.atan2(updatedVelocity.x, updatedVelocity.z))).toFloat()
            val yaw = lerpAngle(getYRot(), targetYaw, 0.15f)
            setYRot(yaw)
            yBodyRot = yaw
            yHeadRot = yaw
        }
    }

    protected fun getOtherBoidCods(radius: Double, kNearest: Int): List<BoidCodEntity> {
        val searchBox = AABB.ofSize(getBoidPosition(), radius * 2.0, radius * 2.0, radius * 2.0)

        return level().getEntitiesOfClass(BoidCodEntity::class.java, searchBox).filterNot { it === this }.take(kNearest)
    }

    private fun lerpAngle(current: Float, target: Float, alpha: Float): Float {
        var delta = target - current
        while (delta < -180.0f) delta += 360.0f
        while (delta >= 180.0f) delta -= 360.0f
        return current + alpha * delta
    }

    protected fun getBoidPosition(boid: BoidCodEntity = this): Vec3 = boid.position()

    protected fun getBoidVelocity(boid: BoidCodEntity = this): Vec3 = boid.deltaMovement

    companion object {
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
