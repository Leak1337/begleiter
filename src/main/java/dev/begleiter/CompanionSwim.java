package dev.begleiter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Schwimmen wie ein Spieler: kräftig zum Ziel schwimmen (auch gegen die Strömung), zu Zielen unter Wasser
 * tauchen, rechtzeitig zum Luftholen auftauchen (notfalls den Block über sich abbauen). Kommt er in einer
 * Strömung nicht voran, gräbt er sich durch die Wand Richtung Ziel/Land oder baut sich mit Blöcken heraus.
 */
public final class CompanionSwim {

    /** Luft (max. 300): darunter wird aufgetaucht. */
    static final int AIR_SURFACE = 90;

    private final CompanionEntity e;
    private BlockPos goal;
    private int goalTick = -1000;
    private int waterTicks;
    private Vec3 checkPos;
    private int checkTick;
    private int stuckRounds;
    private boolean diving;
    private int drivenAt = Integer.MIN_VALUE;
    private BlockPos shore;
    private int shoreTick;

    public CompanionSwim(CompanionEntity e) { this.e = e; }

    /** Wohin er gerade will (von Aufgaben und Folgen gemeldet). */
    public void noteGoal(BlockPos p) {
        goal = p == null ? null : p.immutable();
        goalTick = e.tickCount;
    }

    public boolean isDiving() { return diving; }
    public int stuckRounds() { return stuckRounds; }

    /** FloatGoal, das beim Tauchen nicht nach oben treibt. */
    public static final class Float extends FloatGoal {
        private final CompanionEntity mob;
        public Float(CompanionEntity mob) { super(mob); this.mob = mob; }
        @Override public boolean canUse() { return super.canUse() && !mob.swim.isDiving(); }
        @Override public boolean canContinueToUse() { return canUse(); }
    }

    public void tick(ServerLevel sl) {
        if (drivenAt == e.tickCount) return;
        drivenAt = e.tickCount;
        if (!e.isInWater()) {
            waterTicks = 0;
            diving = false;
            if (e.onGround()) stuckRounds = 0;
            return;
        }
        waterTicks++;
        BlockPos pathGoal = e.motion.pathWaterGoal();
        BlockPos target = pathGoal == null ? currentGoal(sl) : pathGoal;
        boolean underwater = e.isEyeInFluid(FluidTags.WATER);
        int air = e.getAirSupply();
        // Im Wasser angegriffen und angeschlagen (Ertrunkener, den er nicht erreicht): ans nächste Ufer statt weiter
        // zum Ziel (beobachtet: drei Tode durch Ertrunkene, während er „zurückwich" und trotzdem im Fluss blieb)
        if (e.getHealth() < 14 && e.getLastHurtByMob() != null && e.tickCount - e.getLastHurtByMobTimestamp() < 80) {
            if (shore == null || e.tickCount - shoreTick > 40) {
                shore = e.hazards.landSpot(sl, 12);
                shoreTick = e.tickCount;
            }
            if (shore != null) target = shore;
        }

        // Tauchen: Ziel liegt unter der Oberfläche und Luft reicht
        diving = target != null && air > AIR_SURFACE && target.getY() + 0.5 < e.getY() - 0.3
                && sl.getFluidState(target.above()).is(FluidTags.WATER);
        if (diving) {
            // Nicht zu tief, wenn die Luft für den Rückweg nicht reicht
            double depth = surfaceY(sl, e.blockPosition()) - e.getY();
            if (air < AIR_SURFACE + depth * 12) diving = false;
        }

        Vec3 v = e.getDeltaMovement();
        double vx = 0, vy = 0, vz = 0;
        if (target != null) {
            Vec3 to = Vec3.atCenterOf(target).subtract(e.position());
            double h = Math.sqrt(to.x * to.x + to.z * to.z);
            if (h > 0.3 && (!e.onGround() || underwater)) { // im flachen Wasser läuft er normal
                double push = underwater ? 0.045 : 0.035; // wie Sprint-Schwimmen
                vx = to.x / h * push;
                vz = to.z / h * push;
                e.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            }
            if (diving) vy = Math.max(-0.05, Math.min(0.0, to.y * 0.02)) - 0.01;
            else if (target.getY() >= e.getY() && h < 1.6) vy = 0.06; // am Ufer hochziehen
            // Wie ein Spieler mit gedrückter Sprungtaste: schwimmt er gegen eine Kante, aus dem Wasser hüpfen
            if (!diving && e.horizontalCollision && !underwater && v.y < 0.2) vy = Math.max(vy, 0.3);
        }
        // Luft holen
        if (underwater && (air < AIR_SURFACE || !diving)) {
            vy = Math.max(vy, air < AIR_SURFACE ? 0.07 : 0.04);
            if (air < AIR_SURFACE) clearHeadroom(sl);
        }
        e.motion.swim(new Vec3(v.x + vx, Math.max(-0.3, Math.min(0.4, v.y + vy)), v.z + vz), air < AIR_SURFACE);

        // Kommt er voran? Normal 60 Ticks Fenster; in Not (Luft knapp oder angegriffen) schon nach 20 Ticks
        boolean urgent = air < AIR_SURFACE * 2 || e.getLastHurtByMob() != null && e.tickCount - e.getLastHurtByMobTimestamp() < 60;
        int window = urgent ? 20 : 60;
        if (checkPos == null || e.tickCount - checkTick >= window) {
            // Nur echte Wassernot: schwimmend (kein Boden) oder in fließendem Wasser – nicht in einer Pfütze/Farm-Quelle
            boolean trouble = !e.onGround() || !sl.getFluidState(e.blockPosition()).isSource();
            // nur waagerecht messen: auf und ab wippen ist kein Vorankommen
            double moved = Math.hypot(e.getX() - (checkPos == null ? 0 : checkPos.x), e.getZ() - (checkPos == null ? 0 : checkPos.z));
            if (checkPos != null && waterTicks >= window && target != null && !diving && trouble
                    && moved < (urgent ? 0.5 : 1.2) && e.position().distanceToSqr(Vec3.atCenterOf(target)) > 4) {
                escape(sl, target);
            }
            checkPos = e.position();
            checkTick = e.tickCount;
        }
    }

    /** Ziel aus der laufenden Aufgabe/dem Folgen; sonst (länger im Wasser) das nächste sichere Ufer. */
    private BlockPos currentGoal(ServerLevel sl) {
        if (goal != null && e.tickCount - goalTick < 40) return goal;
        var nav = e.getNavigation().getTargetPos();
        if (nav != null && !e.getNavigation().isDone()) return nav;
        if (waterTicks > 40 || e.getAirSupply() < AIR_SURFACE) return e.hazards.landSpot(sl, 12);
        return null;
    }

    private static double surfaceY(ServerLevel sl, BlockPos p) {
        BlockPos.MutableBlockPos m = p.mutable();
        for (int i = 0; i < 24 && sl.getFluidState(m).is(FluidTags.WATER); i++) m.move(Direction.UP);
        return m.getY();
    }

    /** Beim Auftauchen: Block über dem Kopf weg (Unterwasserhöhle), wenn darüber Wasser oder Luft ist. */
    private void clearHeadroom(ServerLevel sl) {
        BlockPos head = BlockPos.containing(e.getEyePosition()).above();
        BlockState st = sl.getBlockState(head);
        if (st.isAir() || !st.getFluidState().isEmpty() || st.getCollisionShape(sl, head).isEmpty()) return;
        if (st.getDestroySpeed(sl, head) < 0 || e.hazardAround(sl, head, true)) return;
        if (!CompanionGathering.naturalAt(sl, head, st)) return; // Glasdach einer Unterwasserbasis o.ä. bleibt
        if (!e.worldBreak(sl, head, true)) return;
        e.logInfo(CompanionTexts.tr("log.swim.headroom"));
    }

    /**
     * Festgehangen: 1.–2. Versuch durch die Wand Richtung Ziel graben (wenn dort fester Block ist),
     * sonst Block unter sich setzen und daraufsteigen (aus dem Wasser bauen), dann Richtung Ziel weiter.
     */
    void escape(ServerLevel sl, BlockPos target) {
        stuckRounds++;
        BlockPos feet = e.blockPosition();
        Direction dir = Direction.getApproximateNearest(target.getX() + 0.5 - e.getX(), 0, target.getZ() + 0.5 - e.getZ());
        e.logInfo(CompanionTexts.tr("log.swim.stuck", stuckRounds,
                solid(sl, feet.relative(dir)) || solid(sl, feet.relative(dir).above())
                        ? CompanionTexts.tr("log.swim.dig", CompanionTexts.tr("direction." + dir.getName()))
                        : CompanionTexts.tr("log.swim.build")));
        // Rausgraben: Wand in Zielrichtung (Fuß- und Kopfhöhe) öffnen
        BlockPos a = feet.relative(dir), b = a.above();
        if ((solid(sl, a) || solid(sl, b)) && breakable(sl, a) && breakable(sl, b)) {
            if (solid(sl, a)) e.worldBreak(sl, a, true);
            if (solid(sl, b)) e.worldBreak(sl, b, true);
            // Stufe: ist darunter nichts, kommt er so nicht weiter – dann einen Block höher öffnen
            if (solid(sl, b.above()) && breakable(sl, b.above()) && solid(sl, a.below()) == false) e.worldBreak(sl, b.above(), true);
            e.setDeltaMovement(dir.getStepX() * 0.3, 0.35, dir.getStepZ() * 0.3);
            return;
        }
        // Rausbauen: Block in die eigene Wasserzelle setzen und draufspringen
        Item item = e.scaffoldItem();
        if (item == null) {
            if (stuckRounds % 3 == 0) e.problem(CompanionTexts.tr("log.swim.no_blocks"));
            e.setDeltaMovement(dir.getStepX() * 0.3, 0.42, dir.getStepZ() * 0.3);
            return;
        }
        BlockPos place = feet;
        if (!sl.getFluidState(feet.below()).isEmpty() && sl.getBlockState(feet.below()).canBeReplaced()) place = feet.below();
        if (!sl.getBlockState(place.above(2)).getCollisionShape(sl, place.above(2)).isEmpty()) return;
        e.snapTo(e.getX(), place.getY() + 1.0, e.getZ(), e.getYRot(), e.getXRot());
        int slot = e.findItem(item);
        if (slot < 0) return;
        if (!e.placeConfirmed(sl, place, Block.byItem(item).defaultBlockState())) return; // bleibt stehen wie Pillar.PATH
        e.getInventory().getItem(slot).shrink(1);
        e.setDeltaMovement(0, 0, 0);
    }

    private static boolean solid(ServerLevel sl, BlockPos p) {
        return !sl.getBlockState(p).getCollisionShape(sl, p).isEmpty();
    }

    private boolean breakable(ServerLevel sl, BlockPos p) {
        BlockState st = sl.getBlockState(p);
        if (!solid(sl, p)) return true;
        if (e.build != null && e.build.contains(p)) return false; // eigenen Bau nicht kaputt machen
        // nur gewachsenes Gelände: Kanalwände einer Farm, Glas, Kisten des Spielers bleiben stehen
        if (!CompanionGathering.naturalAt(sl, p, st)) return false;
        return st.getDestroySpeed(sl, p) >= 0 && !e.hazardAround(sl, p, true) && st.getFluidState().isEmpty();
    }
}
