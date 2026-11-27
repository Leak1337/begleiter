package dev.begleiter;

import java.util.Comparator;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

final class CompanionWhistleItem extends Item {
    private static final int RANGE = 128;

    CompanionWhistleItem(Properties properties) { super(properties); }

    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(stack)) return InteractionResult.FAIL;
        if (!(player instanceof ServerPlayer owner)) return InteractionResult.SUCCESS;
        var world = owner.level();
        var companion = world.getEntitiesOfClass(CompanionEntity.class, owner.getBoundingBox().inflate(RANGE),
                candidate -> CompanionAccess.canControl(owner, candidate) && owner.distanceToSqr(candidate) <= RANGE * RANGE)
                .stream().min(Comparator.comparingDouble(owner::distanceToSqr)).orElse(null);
        owner.getCooldowns().addCooldown(stack, 40);
        if (companion == null) {
            owner.sendSystemMessage(CompanionTexts.tr("whistle.missing"));
            return InteractionResult.CONSUME;
        }
        boolean stay = owner.isShiftKeyDown() && !companion.isStaying();
        var held = companion.holdOrdersComponent();
        if (stay) companion.stay();
        else companion.follow();
        world.playSound(null, owner.blockPosition(), SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.PLAYERS, .8f, 1.4f);
        if (!owner.isShiftKeyDown()) {
            var landing = CompanionSafeTeleport.find(companion, world, owner.position(), 3, 3, 3);
            if (landing != null && CompanionSafeTeleport.move(companion, world, landing)) {
                owner.sendSystemMessage(CompanionTexts.tr("whistle.arrived").append(held));
                return InteractionResult.CONSUME;
            }
        }
        owner.sendSystemMessage(CompanionTexts.tr(stay ? "whistle.stay" : "whistle.follow").append(held));
        return InteractionResult.CONSUME;
    }
}
