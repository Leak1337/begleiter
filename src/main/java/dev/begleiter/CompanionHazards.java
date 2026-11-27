package dev.begleiter;

import dev.begleiter.world.SafeLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Baustein Gefahren: Lava/Wasser neben Blöcken, nächste Lava und nächstes Wasser merken, sicher sprinten und sich
 * aus Lava, Feuer und tiefem Wasser retten. Nur Laufzeit; die gemerkten Stellen stehen an der Entity
 * ({@code nearestLava}, {@code nearestWater}), weil Menü und Texte sie lesen.
 */
public final class CompanionHazards {
    /** Englisch: geht nur in den LLM-Kontext und ins Bot-Trace, nie direkt an Spieler. */
    public static final String NO_HAZARD = "none nearby";

    private final CompanionEntity e;

    CompanionHazards(CompanionEntity e) { this.e = e; }

    /** Lava (immer) oder Wasser (wenn nicht erlaubt) direkt neben dem Block? */
    static boolean around(ServerLevel sl, BlockPos p, boolean allowWater) {
        for (Direction d : Direction.values()) {
            BlockState st = SafeLevel.state(sl, p.relative(d));
            if (st == null) return true; // ungeladener Nachbar: vorsichtshalber Gefahr
            if (st.getFluidState().isEmpty()) continue;
            if (st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
            if (!allowWater) return true;
        }
        return false;
    }

    /**
     * Wie ein Spieler sprinten: auf freien, längeren Wegstücken und wenn ein Gegner noch weg ist. Nicht mit wenig
     * Hunger/Leben, nicht im Wasser und nicht, wenn auf den nächsten Metern Lava oder ein Abgrund kommt.
     */
    void sprintTick(ServerLevel sl) {
        if (!e.onGround()) return; // in der Luft bleibt es, wie es ist (Sprint-Sprung)
        if (e.tickCount <= e.navControlUntil) return; // die neue Wegfindung steuert den Sprint selbst
        boolean want = false;
        if (!e.isInWater() && !e.isInLava() && e.food.level() > 6 && e.getHealth() > 6 && !e.scaffolding.active() && !e.isPaused()) {
            LivingEntity target = e.getTarget();
            if (target != null) {
                want = e.distanceToSqr(target) > 5 * 5;
            } else if (!e.getNavigation().isDone() && e.getNavigation().getPath() != null) {
                var path = e.getNavigation().getPath();
                int next = path.getNextNodeIndex(), left = path.getNodeCount() - next;
                want = left >= 5;
                for (int i = next; want && i < Math.min(path.getNodeCount(), next + 5); i++) {
                    BlockPos q = path.getNodePos(i);
                    if (e.hazardAround(sl, q, false) || sl.getBlockState(q.below()).isAir() && sl.getBlockState(q.below(2)).isAir())
                        want = false;
                }
            }
        }
        if (want != e.isSprinting()) e.setSprinting(want);
    }

    /** Merkt sich die nächste Lava / das nächste Wasser in der Nähe (für Menü und Warnungen). */
    void scan() {
        if (!(e.level() instanceof ServerLevel sl)) return;
        BlockPos c = e.blockPosition(), lava = null, water = null;
        double ld = Double.MAX_VALUE, wd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-5, -3, -5), c.offset(5, 3, 5))) {
            BlockState st = SafeLevel.state(sl, p);
            if (st == null || st.getFluidState().isEmpty()) continue;
            double d = p.distSqr(c);
            if (CompanionStations.isBlockId(st, "lava")) { if (d < ld) { ld = d; lava = p.immutable(); } }
            else if (d < wd) { wd = d; water = p.immutable(); }
        }
        e.nearestLava = lava;
        e.nearestWater = water;
        if (lava != null && ld <= 16) e.logWarn(CompanionTexts.tr("log.lava.near", lava.toShortString()));
    }

    public String text() {
        BlockPos c = e.blockPosition();
        String lava = e.nearestLava == null ? null
                : "lava " + (int) Math.sqrt(e.nearestLava.distSqr(c)) + " blocks (" + e.nearestLava.toShortString() + ")";
        String water = e.nearestWater == null ? null
                : "water " + (int) Math.sqrt(e.nearestWater.distSqr(c)) + " blocks";
        if (lava == null && water == null) return NO_HAZARD;
        return (lava == null ? "" : lava) + (lava != null && water != null ? ", " : "") + (water == null ? "" : water);
    }

    /** Nächste sichere Stelle an Land (fester Boden, kein Wasser/Lava), Umkreis r. */
    public BlockPos landSpot(ServerLevel sl, int r) {
        Vec3 safe = CompanionSafeTeleport.find(e, sl, e.position(), r, 3, 3);
        return safe == null ? null : BlockPos.containing(safe);
    }

    /** Notfall: aus Lava/Feuer retten, aus tiefem Wasser an Land schwimmen. */
    void tick() {
        if (!(e.level() instanceof ServerLevel sl)) return;
        boolean lava = e.isInLava();
        // Magma brennt von unten, ohne dass er Feuer fängt: genauso schnell runter wie aus dem Feuer
        // Nur Brennen an Ort und Stelle (Feuer/Lava direkt an den Füßen, Magma): nach dem Herausklettern brennt er noch
        // Sekunden nach, und jede Flucht hat Weg und Plan abgebrochen (beobachtet: 11 s neben dem Lavasee am Portal
        // "ich brenne" ohne voranzukommen, dann doch hineingefallen)
        boolean burning = e.isOnFire() && !e.isInWater() && hotAtFeet(sl)
                || e.onGround() && e.getBlockStateOn().is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK);
        boolean drowning = e.isInWater() && e.getAirSupply() < 100;
        if (!(lava || burning || drowning)) return;
        // Aus Lava/Feuer: eine Stelle mit Abstand zur Lava, nicht die nächste am Rand, über die ein Lavastrom fließt
        // (beobachtet: viermal im selben Lavasee am Ankunftsportal verbrannt, immer an den Rand gerettet)
        BlockPos spot = lava || burning ? awayFromLava(sl) : null;
        if (spot == null) spot = landSpot(sl, 8);
        if (spot == null && (lava || burning)) {
            // Im Nether ist am Lavasee oft nichts in 8 Blöcken sicher: weiter und tiefer suchen statt zu verbrennen
            Vec3 far = CompanionSafeTeleport.find(e, sl, e.position(), 12, 8, 4);
            spot = far == null ? null : BlockPos.containing(far);
        }
        if (spot == null) {
            // Nichts Sicheres in Reichweite: wenigstens nach oben schwimmen wie ein Spieler mit gedrückter Sprungtaste
            if (lava) e.setDeltaMovement(e.getDeltaMovement().x, Math.max(e.getDeltaMovement().y, 0.3), e.getDeltaMovement().z);
            return;
        }
        if (lava || burning) {
            BlockPos hot = e.blockPosition();
            if (!CompanionSafeTeleport.move(e, sl, Vec3.atBottomCenterOf(spot))) return;
            // Die Stelle als Gefahr merken: die Wegfindung meidet sie dann, statt dass Kampf/Weg und Feuerflucht sich
            // gegenseitig abwechseln (beobachtet: 15-mal pro Sekunde an derselben Lava)
            e.memory.remember(CompanionMemory.Kind.DANGER, lava ? "lava" : "fire", sl, hot);
            e.plannerCooldownUntil = Math.max(e.plannerCooldownUntil, e.tickCount + 60);
            e.planFeedback.finish(e, true); // die Notrettung bricht den bisherigen Plan ab
            e.queue().clear();
            e.setTarget(null);
            e.problem(CompanionTexts.tr(lava ? "entity.hazard.lava" : "entity.hazard.fire"));
        } else if (e.getNavigation().isDone()) {
            e.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.2);
        }
    }

    /** Feuer oder Lava in den Füßen, darunter oder direkt daneben. */
    private boolean hotAtFeet(ServerLevel sl) {
        BlockPos f = e.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(f.offset(-1, -1, -1), f.offset(1, 1, 1))) {
            BlockState st = SafeLevel.state(sl, p);
            if (st == null) continue;
            if (st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) || st.is(net.minecraft.tags.BlockTags.FIRE)
                    || st.is(net.minecraft.world.level.block.Blocks.CAMPFIRE) || st.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE))
                return true;
        }
        return false;
    }

    /** Nächste sichere Stelle (Umkreis 12), an der in 3 Blöcken keine Lava ist; null = keine. */
    private BlockPos awayFromLava(ServerLevel sl) {
        BlockPos c = e.blockPosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-12, -4, -12), c.offset(12, 4, 12))) {
            double d = p.distSqr(c);
            if (d >= bd) continue;
            Vec3 at = Vec3.atBottomCenterOf(p);
            if (!CompanionSafeTeleport.safe(e, sl, at) || lavaWithin(sl, p, 3)) continue;
            best = p.immutable();
            bd = d;
        }
        return best;
    }

    private static boolean lavaWithin(ServerLevel sl, BlockPos p, int r) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-r, -1, -r), p.offset(r, r, r))) {
            BlockState st = SafeLevel.state(sl, q);
            if (st != null && st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
        }
        return false;
    }
}
