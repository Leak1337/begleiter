package dev.begleiter.nav;

import dev.begleiter.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Wer in diesem Tick steuert. Ein wichtigerer Besitzer ersetzt einen früheren Schwimm-Impuls, statt ihn zu addieren. */
public final class MotionController {
    public enum Owner { NONE, NAVIGATION, SPECIAL, SWIM, COMBAT, RESCUE }
    private final CompanionEntity mob;
    private int tick = Integer.MIN_VALUE, impulseTick = Integer.MIN_VALUE;
    private Owner owner = Owner.NONE;
    private BlockPos pathWaterGoal;
    private int waterGoalTick = Integer.MIN_VALUE;
    private int commands;

    public MotionController(CompanionEntity mob) { this.mob = mob; }
    private void refresh() {
        if (tick != mob.tickCount) { tick = mob.tickCount; owner = Owner.NONE; commands = 0; }
    }
    public Owner owner() { refresh(); return owner; }
    public int commands() { refresh(); return commands; }
    public boolean claim(Owner next) {
        refresh();
        if (next.ordinal() < owner.ordinal()) return false;
        owner = next;
        return true;
    }
    public void release(Owner previous) { refresh(); if (owner == previous) owner = Owner.NONE; }
    public boolean move(Owner next, double x, double y, double z, double speed, boolean sprint) {
        if (!claim(next)) return false;
        commands = 1;
        mob.getMoveControl().setWantedPosition(x, y, z, speed);
        mob.setSprinting(sprint && mob.hunger > 6 && !mob.isInWater());
        return true;
    }
    public void notePathWaterGoal(BlockPos target) { pathWaterGoal = target; waterGoalTick = mob.tickCount; }
    public BlockPos pathWaterGoal() { return mob.tickCount - waterGoalTick <= 1 ? pathWaterGoal : null; }
    public void swim(Vec3 velocity, boolean rescue) {
        if (!claim(rescue ? Owner.RESCUE : Owner.SWIM)) return;
        // Mehrfache Aufrufe im selben Tick wirken nur einmal; der Schwimmantrieb hat dieselbe Sperre
        if (impulseTick == mob.tickCount) return;
        impulseTick = mob.tickCount;
        mob.setDeltaMovement(velocity);
        commands = 1;
    }
    public void stop() {
        refresh();
        if (owner.ordinal() > Owner.SPECIAL.ordinal()) return;
        mob.setSprinting(false);
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        pathWaterGoal = null; waterGoalTick = Integer.MIN_VALUE;
        commands = 1;
    }
}
