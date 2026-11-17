package dev.begleiter;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Folgen-Modus: bleibt nah beim Besitzer. Selbstständig-Modus: spielt frei und kommt nur zurück,
 * wenn er sich zu weit entfernt hat. Lagerverwalter: bleibt am Lager. Daueraufgabe: bleibt an ihrem Ort.
 */
public class CompanionFollowGoal extends Goal {
    private final CompanionEntity mob;

    public CompanionFollowGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private Vec3 target() {
        if (mob.isKeeper() && mob.getHome() != null) return Vec3.atCenterOf(mob.getHome());
        if (mob.duty() != null) {
            var zone = CompanionZones.work(mob, CompanionZones.kindFor(mob.duty()));
            if (zone != null && zone.contains(mob.blockPosition())) return null;
            return mob.dutyPos() == null ? null : Vec3.atCenterOf(mob.dutyPos());
        }
        Player o = mob.getOwner();
        return o == null ? null : o.position();
    }

    /** Lagerverwalter oder Daueraufgabe: bleibt an einem festen Ort statt beim Besitzer. */
    private boolean anchored() {
        return mob.isKeeper() || mob.dutyPos() != null;
    }

    private double startDist() {
        return mob.isKeeper() ? 6 : mob.dutyPos() != null ? 14 : mob.isAutonomous() ? 32 : 7;
    }

    private double stopDist() {
        return mob.isKeeper() ? 3 : mob.dutyPos() != null ? 5 : mob.isAutonomous() ? 12 : 3;
    }

    @Override
    public boolean canUse() {
        if (mob.isStaying() || mob.hasTasks() || mob.isPaused() || mob.getTarget() != null || mob.isRetreating()) return false;
        Vec3 t = target();
        double s = startDist();
        return t != null && mob.position().distanceToSqr(t) > s * s;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.isStaying() || mob.hasTasks() || mob.isPaused() || mob.getTarget() != null || mob.isRetreating()) return false;
        Vec3 t = target();
        double s = stopDist();
        return t != null && mob.position().distanceToSqr(t) > s * s;
    }

    /** Ohne das tickt Minecraft das Ziel nur jeden 2. Tick (je nach Entity-ID) und "tickCount % 10" trifft nie. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** Stelle, zu der gerade geplant wird (neu erst, wenn das Ziel sich mehr als 3 Blöcke bewegt hat). */
    private BlockPos anchor;
    /** Bis dahin die normale Mob-Navigation (unter Wasser oder Wegfindung kam nicht hin). */
    private int vanillaUntil;
    private int nextCatchupTick;

    @Override
    public void tick() {
        Vec3 t = target();
        if (t == null) return;
        Player owner = mob.getOwner();
        if (!anchored() && !mob.isAutonomous() && owner != null
                && dev.begleiter.nav.FollowRecovery.beyond(mob.distanceToSqr(owner), 0)
                && mob.level() instanceof net.minecraft.server.level.ServerLevel level) {
            mob.nav.stop();
            mob.getNavigation().stop();
            if (mob.tickCount < nextCatchupTick) return;
            nextCatchupTick = mob.tickCount + 20;
            Vec3 safe = CompanionSafeTeleport.nearOwner(mob, level, owner.position());
            boolean moved = safe != null && CompanionSafeTeleport.move(mob, level, safe);
            mob.nav.recoveryEvent(CompanionTexts.tr(moved ? "nav.recovery.catchup" : "nav.recovery.wait"));
            return;
        }
        BlockPos at = BlockPos.containing(t);
        mob.swim.noteGoal(at);
        if (mob.isUnderWater() || mob.tickCount < vanillaUntil) {
            mob.nav.stop();
            if (mob.tickCount % 10 == 0) mob.getNavigation().moveTo(t.x, t.y, t.z, 1.15);
            return;
        }
        if (anchor == null || anchor.distSqr(at) > 9) anchor = at.immutable();
        int r = (int) stopDist();
        var res = mob.nav.goToMoving(new dev.begleiter.nav.Goal.Near(anchor.getX(), anchor.getY(), anchor.getZ(), r * r), this);
        if (res == dev.begleiter.nav.Navigator.Result.UNREACHABLE) vanillaUntil = mob.tickCount + 100;
        else if (res == dev.begleiter.nav.Navigator.Result.ARRIVED) anchor = at.immutable(); // weitergegangen: nachziehen
    }

    @Override
    public void stop() {
        anchor = null;
        mob.nav.stop();
        mob.getNavigation().stop();
    }
}
