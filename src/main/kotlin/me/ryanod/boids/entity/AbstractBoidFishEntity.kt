package me.ryanod.boids.entity

import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.animal.AbstractFish
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level

abstract class AbstractBoidFishEntity(
    entityType: EntityType<out AbstractBoidFishEntity>,
    level: Level,
) : AbstractFish(entityType, level) {
    override fun aiStep() {
        if (!level().isClientSide && isInWater) {
            updateBoidMovement()
        }

        super.aiStep()
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (player.getItemInHand(hand).`is`(Items.WATER_BUCKET)) {
            return InteractionResult.PASS
        }

        return super.mobInteract(player, hand)
    }

    protected open fun updateBoidMovement() = Unit
}
