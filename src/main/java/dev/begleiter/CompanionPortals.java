package dev.begleiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.begleiter.debug.BotDebug.EventKind;
import dev.begleiter.world.SafeLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

/**
 * Baustein Wasser, Lava, Obsidian und Netherportal: Quellen finden, Eimer füllen, Lava zu Obsidian löschen,
 * Portalrahmen und Anzünden, Portale finden. Kein eigener gespeicherter Zustand; der Portalplatz liegt an der
 * Entity ({@code getPortalPos}). Alle Weltänderungen laufen über die bestätigten Wege der Entity.
 */
public final class CompanionPortals {
    private final CompanionEntity e;

    CompanionPortals(CompanionEntity e) { this.e = e; }

    /** Sichtbare, erreichbare Lava-Quellen, höchstens drei, nächste zuerst. */
    public List<BlockPos> lavaSources(int r) {
        List<BlockPos> out = new ArrayList<>();
        if (!(e.level() instanceof ServerLevel sl)) return out;
        BlockPos c = e.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -8, -r), c.offset(r, 5, r))) {
            if (sl.hasChunkAt(p) && sl.getFluidState(p).isSourceOfType(Fluids.LAVA) && CompanionGathering.exposed(sl, p)) out.add(p.immutable());
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(c)));
        List<BlockPos> ok = new ArrayList<>();
        for (BlockPos p : out) {
            if (ok.size() >= 3) break;
            if (e.seesBlock(sl, p) && e.gathering.reachable(p)) ok.add(p);
        }
        return ok;
    }

    /** Nächste erreichbare Wasserquelle mit freier Seite (die ersten 20 Kandidaten werden geprüft). */
    public BlockPos findWaterSource(int r) {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        BlockPos c = e.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -6, -r), c.offset(r, 4, r))) {
            if (sl.hasChunkAt(p) && sl.getFluidState(p).isSourceOfType(Fluids.WATER) && CompanionGathering.exposed(sl, p)) found.add(p.immutable());
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(c)));
        for (int i = 0; i < Math.min(found.size(), 20); i++) if (e.gathering.reachable(found.get(i))) return found.get(i);
        return null;
    }

    /** Wassereimer füllen: Quelle wird zu Luft, Eimer zu Wassereimer. */
    public boolean fillBucket(ServerLevel sl, BlockPos water) {
        int slot = e.findItem(Items.BUCKET);
        if (slot < 0 || !sl.getFluidState(water).isSourceOfType(Fluids.WATER)) return false;
        // erst die Quelle nehmen, dann den Eimer tauschen: abgelehnt -> kein Wasser aus dem Nichts
        if (!e.placeConfirmed(sl, water, Blocks.AIR.defaultBlockState())) return false;
        e.getInventory().getItem(slot).shrink(1);
        e.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        sl.playSound(null, water, net.minecraft.sounds.SoundEvents.BUCKET_FILL, net.minecraft.sounds.SoundSource.NEUTRAL, 1f, 1f);
        e.swingArm();
        return true;
    }

    /**
     * Wasser auf Lava gießen und wieder aufnehmen (wie ein Spieler): die Quelle und angrenzende Quellen werden
     * Obsidian (Rückgabe), fließende Lava daneben Bruchstein. Der Wassereimer bleibt voll.
     */
    public List<BlockPos> pourWater(ServerLevel sl, BlockPos lava) {
        List<BlockPos> made = new ArrayList<>();
        if (e.findItem(Items.WATER_BUCKET) < 0 || !sl.getFluidState(lava).isSourceOfType(Fluids.LAVA)) return made;
        List<BlockPos> cells = new ArrayList<>(List.of(lava));
        for (Direction d : Direction.values()) cells.add(lava.relative(d));
        for (BlockPos p : cells) {
            var fs = sl.getFluidState(p);
            if (fs.isSourceOfType(Fluids.LAVA)) { if (e.placeConfirmed(sl, p, Blocks.OBSIDIAN.defaultBlockState())) made.add(p.immutable()); }
            else if (fs.is(net.minecraft.tags.FluidTags.LAVA)) e.placeConfirmed(sl, p, Blocks.COBBLESTONE.defaultBlockState());
        }
        sl.playSound(null, lava, net.minecraft.sounds.SoundEvents.LAVA_EXTINGUISH, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
        e.swingArm();
        e.logInfo(EventKind.PORTAL, CompanionTexts.tr("log.portal.obsidian", lava.toShortString(), made.size()));
        return made;
    }

    /** Rahmen eines 4x5-Portals (ohne Ecken, 10 Obsidian) an der Bauplatz-Ecke o, in x-Richtung bei z+2. */
    public static List<BlockPos> frame(BlockPos o) {
        BlockPos b = o.offset(0, 0, 2);
        List<BlockPos> l = new ArrayList<>();
        l.add(b.offset(1, 0, 0)); l.add(b.offset(2, 0, 0));
        for (int y = 1; y <= 3; y++) { l.add(b.offset(0, y, 0)); l.add(b.offset(3, y, 0)); }
        l.add(b.offset(1, 4, 0)); l.add(b.offset(2, 4, 0));
        return l;
    }

    /** Die sechs Portalblöcke innerhalb des Rahmens an der Bauplatz-Ecke o. */
    public static List<BlockPos> inside(BlockPos o) {
        BlockPos b = o.offset(0, 0, 2);
        List<BlockPos> l = new ArrayList<>();
        for (int y = 1; y <= 3; y++) for (int x = 1; x <= 2; x++) l.add(b.offset(x, y, 0));
        return l;
    }

    /** Mit dem Feuerzeug anzünden; true, wenn danach Portalblöcke da sind. */
    public boolean light(ServerLevel sl, BlockPos o) {
        int slot = e.findItem(Items.FLINT_AND_STEEL);
        if (slot < 0) return false;
        BlockPos fire = inside(o).getFirst();
        if (!sl.getBlockState(fire).isAir()) return false;
        if (!e.worldSet(sl, fire, net.minecraft.world.level.block.BaseFireBlock.getState(sl, fire), 11)) return false;
        sl.playSound(null, fire, net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
        // Haltbarkeit nur, wenn wirklich etwas brennt (Feuer oder sofort entstandenes Portal)
        if (sl.getBlockState(fire).is(Blocks.NETHER_PORTAL) || sl.getBlockState(fire).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock)
            e.getInventory().getItem(slot).hurtAndBreak(1, sl, null, item -> {});
        e.swingArm();
        return sl.getBlockState(fire).is(Blocks.NETHER_PORTAL);
    }

    /** Nächster Portalblock in der Nähe (bekanntes Portal zuerst); ungeladene Chunks werden übersprungen. */
    public BlockPos nearest(int r) {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        BlockPos known = e.getPortalPos();
        if (known != null) {
            for (BlockPos p : inside(known))
                if (sl.hasChunkAt(p) && sl.getBlockState(p).is(Blocks.NETHER_PORTAL)) return p.immutable();
        }
        BlockPos c = e.blockPosition(), best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -r / 2, -r), c.offset(r, r / 2, r))) {
            if (!SafeLevel.is(sl, p, Blocks.NETHER_PORTAL) || sl.getBlockState(p.below()).is(Blocks.NETHER_PORTAL)) continue;
            double d = p.distSqr(c);
            if (d < bd) { bd = d; best = p.immutable(); }
        }
        return best;
    }
}
