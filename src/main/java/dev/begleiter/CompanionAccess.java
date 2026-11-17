package dev.begleiter;

import net.minecraft.world.entity.player.Player;

/** Gemeinsame serverseitige Zugriffsgrenze für das Kontrollmenü. */
public final class CompanionAccess {
    private static final double MAX_DISTANCE_SQUARED = 144.0;

    private CompanionAccess() {}

    public static boolean canManage(Player player, CompanionEntity companion) {
        return canControl(player, companion) && player.distanceToSqr(companion) < MAX_DISTANCE_SQUARED;
    }
    public static boolean canUseMenu(Player player, CompanionEntity companion) {
        return canManage(player, companion) || player.containerMenu instanceof CompanionMenu menu
                && menu.controlsRemotely(player, companion);
    }
    public static boolean canControl(Player player, CompanionEntity companion) {
        return !companion.level().isClientSide()
                && player.isAlive()
                && companion.isAlive()
                && !companion.isRemoved()
                && player.level() == companion.level()
                && companion.isOwner(player);
    }
}
