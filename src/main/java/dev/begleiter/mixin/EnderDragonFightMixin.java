package dev.begleiter.mixin;

import java.util.Collection;

import dev.begleiter.CompanionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EnderDragonFight.class)
abstract class EnderDragonFightMixin {
    @Shadow @Final private ServerLevel level;
    @Unique private long begleiter$nextScan;
    @Unique private boolean begleiter$companionPresent;

    @Redirect(method = "tick()V", at = @At(value = "INVOKE", target = "Ljava/util/Collection;isEmpty()Z"), remap = false)
    private boolean begleiter$arenaEmpty(Collection<ServerPlayer> players) {
        if (!players.isEmpty()) return false;
        long now = level.getGameTime();
        if (now >= begleiter$nextScan) {
            begleiter$nextScan = now + 20;
            var arena = new AABB(-192, level.getMinY(), -192, 192, level.getMaxY(), 192);
            begleiter$companionPresent = !level.getEntitiesOfClass(CompanionEntity.class, arena,
                    companion -> companion.isAlive() && companion.journey.fightingDragon()
                            && companion.getOwnerAnywhere() != null).isEmpty();
        }
        return !begleiter$companionPresent;
    }
}
