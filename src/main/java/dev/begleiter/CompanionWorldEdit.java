package dev.begleiter;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Baustein Weltänderungen: Abbauen mit Beute und Verschleiß, Setzen und Entfernen nur mit Erlaubnis (Arbeitsbereich,
 * Schutz-Mods), Fehler der Welt als Ablehnung. Die eigentlichen Weltaufrufe bleiben überschreibbar an der Entity
 * ({@code destroyMiningBlock}, {@code setWorldBlock}), damit Tests Ablehnungen simulieren können.
 */
public final class CompanionWorldEdit {
    private final CompanionEntity e;
    private BlockPos lastDenied;
    private long lastDeniedAt = Long.MIN_VALUE;
    /** Zahl der verweigerten Weltänderungen seit dem Laden (Diagnose, Tests). */
    public int deniedChanges;

    CompanionWorldEdit(CompanionEntity e) { this.e = e; }

    /** Beute und Verschleiß nur nach bestätigtem Entfernen genau des geprüften Blocks buchen. */
    boolean mineBlock(ServerLevel sl, BlockPos p, BlockState s) {
        try {
            if (sl != e.level() || sl.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null
                    || s.isAir() || !sl.getBlockState(p).equals(s) || s.getDestroySpeed(sl, p) < 0
                    || !e.storage.harvestAllowed(sl, p, s) || !e.work.permitsChange(sl, p)
                    || (s.requiresCorrectToolForDrops() && !e.getMainHandItem().isCorrectToolForDrops(s))) return false;
            if (!dev.begleiter.world.BuildPermission.mayBreak(e, sl, p, s)) { noteDenied(p); return false; }
            boolean damages = s.getDestroySpeed(sl, p) != 0;
            BlockEntity be = sl.getBlockEntity(p);
            List<ItemStack> drops = Block.getDrops(s, sl, p, be, e, e.getMainHandItem());
            if (!e.destroyMiningBlock(sl, p) || sl.getBlockState(p).equals(s)) return false;
            for (ItemStack d : drops) {
                ItemStack rest = e.getInventory().addItem(d);
                if (!rest.isEmpty()) e.spawnAtLocation(sl, rest);
            }
            if (damages && !e.getMainHandItem().isEmpty() && e.getMainHandItem().getDestroySpeed(s) > 1.0f) {
                e.getMainHandItem().hurtAndBreak(1, e, EquipmentSlot.MAINHAND);
            }
            return true;
        } finally {
            sl.destroyBlockProgress(e.getId(), p, -1);
        }
    }

    /** Weltänderung ausführen; eine Ausnahme der Welt (Schutz-Mod, Weltfehler) gilt als abgelehnt und wird protokolliert. */
    boolean set(ServerLevel sl, BlockPos p, BlockState st, int flags) {
        if (!e.work.permitsChange(sl, p)) return false;
        if (!dev.begleiter.world.BuildPermission.mayPlace(e, sl, p)) { noteDenied(p); return false; }
        try {
            return e.setWorldBlock(sl, p, st, flags);
        } catch (RuntimeException ex) {
            e.logError(CompanionTexts.tr("handler.place.error", p.toShortString(), CompanionTexts.literal(String.valueOf(ex))));
            return false;
        }
    }

    /**
     * Abbauen ohne Beute-Buchung (Gras, Fackeln, Müll-Schutzraum, Kopffreiheit, Bau-Hindernis im Kreativmodus): nur mit
     * Erlaubnis, Weltfehler gelten als abgelehnt. {@code drop}: Block lässt wie beim Spieler seine Beute fallen.
     */
    boolean breakBlock(ServerLevel sl, BlockPos p, boolean drop) {
        if (!e.work.permitsChange(sl, p)) return false;
        BlockState s = sl.getBlockState(p);
        if (s.isAir()) return false;
        if (!dev.begleiter.world.BuildPermission.mayBreak(e, sl, p, s)) { noteDenied(p); return false; }
        try {
            return sl.destroyBlock(p, drop, e);
        } catch (RuntimeException ex) {
            e.logError(CompanionTexts.tr("handler.place.error", p.toShortString(), CompanionTexts.literal(String.valueOf(ex))));
            return false;
        }
    }

    /** Verweigerung protokollieren; dieselbe Stelle höchstens alle 10 Sekunden, damit Wiederholungen nicht fluten. */
    private void noteDenied(BlockPos p) {
        deniedChanges++;
        long now = e.level().getGameTime();
        if (p.equals(lastDenied) && now - lastDeniedAt < 200) return;
        lastDenied = p.immutable();
        lastDeniedAt = now;
        e.logWarn(CompanionTexts.tr("log.world.denied", p.toShortString()));
    }
}
