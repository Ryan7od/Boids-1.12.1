package me.ryanod.boids.entity

import net.minecraft.sounds.SoundEvent
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.animal.WaterAnimal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

abstract class AbstractBoidFishEntity(
    entityType: EntityType<out AbstractBoidFishEntity>,
    level: Level,
) : WaterAnimal(entityType, level) {
    override fun registerGoals() = Unit

    override fun aiStep() {
        if (!isInWater && onGround() && verticalCollision) {
            val flopX = (random.nextFloat() * 2.0f - 1.0f).toDouble() * 0.05
            val flopZ = (random.nextFloat() * 2.0f - 1.0f).toDouble() * 0.05

            setDeltaMovement(deltaMovement.add(flopX, 0.4, flopZ))
            setOnGround(false)
            hasImpulse = true
            makeSound(getFlopSound())
        }

        if (!level().isClientSide && isInWater) {
            updateMovementSystem()
        }

        super.aiStep()
    }

    override fun travel(travelVector: Vec3) {
        if (isEffectiveAi && isInWater) {
            move(MoverType.SELF, deltaMovement)

            var adjustedVelocity = deltaMovement

            if (horizontalCollision) {
                adjustedVelocity = adjustedVelocity.multiply(0.35, 1.0, 0.35)
            }

            if (verticalCollision) {
                adjustedVelocity = adjustedVelocity.multiply(1.0, 0.35, 1.0)
            }

            setDeltaMovement(adjustedVelocity)
            return
        }

        super.travel(travelVector)
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (player.getItemInHand(hand).`is`(Items.WATER_BUCKET)) {
            return InteractionResult.PASS
        }

        return super.mobInteract(player, hand)
    }

    protected open fun updateMovementSystem() = Unit

    protected abstract fun getFlopSound(): SoundEvent
}
