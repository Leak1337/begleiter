package dev.begleiter.mixin;

import dev.begleiter.CompanionEntity;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EnderDragon.class)
abstract class EnderDragonDamageMixin {
    @Redirect(method = "hurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/damagesource/DamageSource;is(Lnet/minecraft/tags/TagKey;)Z"), remap = false)
    private boolean begleiter$acceptSurvivalAttack(DamageSource damage, TagKey<DamageType> tag) {
        return damage.is(tag) || damage.getEntity() instanceof CompanionEntity companion
                && companion.journey.fightingDragon()
                && (damage.is(DamageTypes.MOB_ATTACK) || damage.is(DamageTypes.ARROW));
    }
}
