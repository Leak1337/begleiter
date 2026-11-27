package dev.begleiter.world;

import dev.begleiter.CompanionEntity;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Darf der Begleiter diese Weltänderung vornehmen? Er handelt im Auftrag seines Besitzers und darf nie mehr als
 * dieser an derselben Stelle. Geprüft wird je Position und Aktion unmittelbar vor der Änderung, nie pauschal je Chunk.
 *
 * <ul>
 *   <li>Immer: innerhalb der Bauhöhe und der Weltgrenze.</li>
 *   <li>Besitzer im Abenteuermodus: keine Änderungen (Zuschauer- und Kreativmodus ändern nichts).</li>
 *   <li>Spawn-Schutz gilt wie für den Besitzer ({@code ServerLevel.mayInteract}).</li>
 *   <li>Abbauen fragt zusätzlich {@link PlayerBlockBreakEvents#BEFORE} im Namen des Besitzers; so greifen Schutz-Mods,
 *       die sich dort einhängen. Für Setzen gibt es kein entsprechendes Fabric-Ereignis.</li>
 *   <li>Besitzer nicht online: im Einzelspieler erlaubt (Grenzen gelten weiter), auf einem Server verweigert.</li>
 * </ul>
 */
public final class BuildPermission {
    private BuildPermission() { }

    public enum Action { BREAK, PLACE, INTERACT }

    public static boolean mayBreak(CompanionEntity e, ServerLevel sl, BlockPos pos, BlockState state) {
        return allowed(e, sl, pos, Action.BREAK, state);
    }

    public static boolean mayPlace(CompanionEntity e, ServerLevel sl, BlockPos pos) {
        return allowed(e, sl, pos, Action.PLACE, null);
    }

    public static boolean mayInteract(CompanionEntity e, ServerLevel sl, BlockPos pos) {
        return allowed(e, sl, pos, Action.INTERACT, null);
    }

    public static boolean allowed(CompanionEntity e, ServerLevel sl, BlockPos pos, Action action, BlockState state) {
        if (sl.isOutsideBuildHeight(pos) || !sl.getWorldBorder().isWithinBounds(pos)) return false;
        if (e.getOwnerUuid() == null) return true; // herrenlos (Tests, Vorschau): nur die Weltgrenzen
        ServerPlayer owner = e.getOwnerAnywhere();
        if (owner == null) return sl.getServer().isSingleplayer();
        if (!ownerMayBuild(owner)) return false;
        if (!sl.mayInteract(owner, pos)) return false;
        if (action != Action.BREAK) return true;
        BlockState s = state != null ? state : sl.getBlockState(pos);
        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(sl, owner, pos, s, sl.getBlockEntity(pos));
    }

    /** Darf der Besitzer grundsätzlich bauen? Nur Abenteuer verbietet es; Zuschauer zu sein hält ihn nicht auf. */
    public static boolean ownerMayBuild(CompanionEntity e) {
        ServerPlayer owner = e.getOwnerAnywhere();
        return owner == null || ownerMayBuild(owner);
    }

    private static boolean ownerMayBuild(ServerPlayer owner) {
        return owner.gameMode.getGameModeForPlayer() != GameType.ADVENTURE;
    }
}
