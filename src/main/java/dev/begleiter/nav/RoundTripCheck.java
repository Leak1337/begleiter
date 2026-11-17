package dev.begleiter.nav;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionTexts;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Schrittweiser Nachweis für einen gewählten Kistengang (hin und zurück); nicht zum Vergleichen aller Kandidaten. */
public final class RoundTripCheck {
    public enum Status { CHECKING, SAFE, UNSAFE, UNPROVEN }
    private static final int MAX_NODES = 4096, MAX_MOVES = RouteProjection.MAX_MOVES;
    private static final long SLICE = 1_000_000L, MAX_NANOS = 50_000_000L;
    private final CompanionEntity mob;
    private final ServerLevel level;
    private final BlockPos source;
    private final Goal outboundGoal, returnGoal;
    private NavContext outboundContext, returnContext;
    private PathSearch search;
    private Path outbound, returning;
    private RouteProjection projection;
    private RouteVerification verification;
    private Status status = Status.CHECKING;
    private Component reason = Component.empty();
    private int checkedAt;
    private final int nodeLimit;
    private final long timeLimit;
    private final boolean scheduled;
    private java.util.List<net.minecraft.world.item.ItemStack> inventorySnapshot;
    private float[] outboundDamage;
    private net.minecraft.world.phys.Vec3 checkingStart;

    public RoundTripCheck(CompanionEntity mob, BlockPos source, double reachSquared) {
        this(mob, source, reachSquared, MAX_NODES, MAX_NANOS, true);
    }

    /** Synchroner Komponententreiber mit expliziten Testlimits; nicht für den Livepfad. */
    RoundTripCheck(CompanionEntity mob, BlockPos source, double reachSquared, int nodeLimit, long timeLimit) {
        this(mob, source, reachSquared, nodeLimit, timeLimit, false);
    }

    private RoundTripCheck(CompanionEntity mob, BlockPos source, double reachSquared, int nodeLimit, long timeLimit, boolean scheduled) {
        this.mob = mob;
        this.scheduled = scheduled;
        this.nodeLimit = Math.max(0, nodeLimit);
        this.timeLimit = Math.max(1, timeLimit);
        this.level = (ServerLevel) mob.level();
        this.source = source.immutable();
        outboundGoal = new Goal.Near(source.getX(), source.getY(), source.getZ(), Math.max(1, (int) reachSquared));
        BlockPos home = mob.getHome();
        returnGoal = mob.planFeedback.returnGoal() != null ? mob.planFeedback.returnGoal()
                : home == null ? new Goal.Block(mob.blockPosition()) : new Goal.Near(home, 2);
    }

    public Status status() { return status; }
    public String reason() { return reason.getString(); }
    public Component reasonComponent() { return reason.copy(); }
    public Path outbound() { return outbound; }
    public Path returning() { return returning; }
    public NavContext outboundContext() { return outboundContext; }
    public NavContext returnContext() { return returnContext; }
    public Goal outboundGoal() { return outboundGoal; }
    public boolean expired() { return status == Status.SAFE && mob.tickCount - checkedAt >= 100; }

    private Status finish(Status state, Component message) {
        if (verification != null) verification.close();
        status = state;
        reason = message.copy();
        if (state == Status.SAFE) checkedAt = mob.tickCount;
        if (scheduled) NavScheduler.cancel(level, this);
        return status;
    }

    public Status step() {
        if (mob.level() != level) return finish(Status.UNPROVEN, CompanionTexts.tr("route.world_changed"));
        if (mob.memory.dangerous(level, source, 8)) return finish(Status.UNSAFE, CompanionTexts.tr("route.known_danger"));
        float minimumHealth = expectedHealth();
        if (mob.getHealth() < minimumHealth) return finish(Status.UNPROVEN, CompanionTexts.tr("route.health_changed"));
        if (status == Status.CHECKING && inventorySnapshot != null && !sameInventory())
            return finish(Status.UNPROVEN, CompanionTexts.tr("route.equipment_changed"));
        if (status == Status.CHECKING && checkingStart != null && mob.position().distanceToSqr(checkingStart) > 1)
            return finish(Status.UNPROVEN, CompanionTexts.tr("route.start_changed"));
        if (status != Status.CHECKING) return status;
        if (!mob.onGround() && !mob.isInWater()) return status;
        if (scheduled) {
            NavScheduler.run(level, this, NavScheduler.Priority.ACTIVE, SLICE, deadline -> {
                advance(deadline);
                return status != Status.CHECKING;
            });
        } else advance(Long.MAX_VALUE);
        return status;
    }

    private Status advance(long deadline) {
        if (verification != null) return advanceVerification(deadline);
        if (projection != null && returnContext == null) return advanceProjection(deadline);
        if (search == null) {
            inventorySnapshot = inventory();
            checkingStart = mob.position();
            outboundContext = new NavContext(mob, true);
            search = new PathSearch(outboundContext, PathSearch.startFor(outboundContext, mob), outboundGoal);
            search.maxNodes = nodeLimit;
        }
        long left = deadline == Long.MAX_VALUE ? SLICE : NavScheduler.remaining(deadline);
        if (left > 0) search.step(left);
        if (search.nanos() > timeLimit && !search.done()) return finish(Status.UNPROVEN, CompanionTexts.tr("route.search_budget"));
        if (!search.done()) return status;
        if (search.status() != PathSearch.Status.FOUND) return finish(Status.UNPROVEN, CompanionTexts.tr("route.path_unproven"));
        Path path = search.path();
        if (path == null || path.length() > MAX_MOVES) return finish(Status.UNPROVEN, CompanionTexts.tr("route.path_budget"));
        if (outbound == null) {
            outbound = path;
            projection = new RouteProjection(mob, outboundContext, outbound);
            search = null;
            return advanceProjection(deadline);
        }
        returning = path;
        verification = new RouteVerification(outbound, outboundContext, returning, returnContext);
        search = null;
        return advanceVerification(deadline);
    }

    private Status advanceVerification(long deadline) {
        var verified = verification.step(deadline);
        if (verified == RouteVerification.Status.RUNNING) return status;
        if (verified != RouteVerification.Status.COMPLETE)
            return finish(verified == RouteVerification.Status.UNSAFE ? Status.UNSAFE : Status.UNPROVEN, verification.reasonComponent());
        if (!projection.worldUnchanged()) return finish(Status.UNPROVEN, CompanionTexts.tr("route.projection_changed"));
        return finish(Status.SAFE, CompanionTexts.tr("route.checked"));
    }

    private Status advanceProjection(long deadline) {
        var projected = projection.step(deadline);
        if (projected == RouteProjection.Status.RUNNING) return status;
        if (projected != RouteProjection.Status.COMPLETE)
            return finish(projected == RouteProjection.Status.UNSAFE ? Status.UNSAFE : Status.UNPROVEN, projection.reasonComponent());
        returnContext = projection.context();
        outboundDamage = projection.damage();
        returnContext.allowBreak = false;
        returnContext.allowPlace = false;
        search = new PathSearch(returnContext, outbound.end(), returnGoal);
        search.maxNodes = nodeLimit;
        return status;
    }

    private java.util.List<net.minecraft.world.item.ItemStack> inventory() {
        var items = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
        for (int i = 0; i < mob.getInventory().getContainerSize(); i++) items.add(mob.getInventory().getItem(i).copy());
        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) items.add(mob.getItemBySlot(slot).copy());
        return items;
    }

    private boolean sameInventory() {
        var now = inventory();
        if (now.size() != inventorySnapshot.size()) return false;
        for (int i = 0; i < now.size(); i++) {
            var a = now.get(i);
            var b = inventorySnapshot.get(i);
            if (a.getCount() != b.getCount() || !net.minecraft.world.item.ItemStack.isSameItemSameComponents(a, b)) return false;
        }
        return true;
    }

    private float expectedHealth() {
        if (outboundContext == null) return 0;
        if (status != Status.SAFE || outboundDamage == null) return outboundContext.health;
        int completed = 0;
        var executor = mob.nav.executor();
        if (executor != null && executor.path == outbound) {
            completed = Math.min(outbound.length(), executor.index());
            // Landeschaden kann kommen, bevor Movement im nächsten KI-Tick die Bewegung als fertig meldet
            if (completed < outbound.length() && mob.onGround()
                    && mob.blockPosition().equals(outbound.positions.get(completed + 1))) completed++;
        } else if (outboundGoal.isInGoal(mob.getBlockX(), mob.getBlockY(), mob.getBlockZ())) completed = outbound.length();
        return outboundContext.health - outboundDamage[completed];
    }

    static float movementFallDamage(NavContext context, Path path, int i) {
        BlockPos from = path.positions.get(i), to = path.positions.get(i + 1);
        int drop = from.getY() - to.getY();
        if (drop <= 1 || BlockFlags.has(context.blocks.flags(to.getX(), to.getY(), to.getZ()), BlockFlags.WATER)) return 0;
        if (path.moves.get(i).kind == Move.Kind.PARKOUR) drop += 2;
        return context.fallDamage(drop);
    }
}
