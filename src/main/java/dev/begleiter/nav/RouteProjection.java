package dev.begleiter.nav;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionTexts;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Fortsetzbare, rein virtuelle Vorschau des Hinwegs; kein Weltumbau und kein echter Materialverbrauch. */
final class RouteProjection {
    enum Status { RUNNING, COMPLETE, UNPROVEN, UNSAFE }
    static final int MAX_MOVES = 512;
    private static final int MAX_WATCHED = 8192;
    private final CompanionEntity mob;
    private final NavContext original;
    private final Path path;
    private final LongSupplier clock;
    private final Map<Long, BlockState> changes = new HashMap<>(), watched = new HashMap<>();
    private final MoveResult result = new MoveResult();
    private final float[] damage;
    private NavContext preview, projected;
    private int next, placed;
    private boolean moveStarted;
    private Status status = Status.RUNNING;
    private Component reason = Component.empty();

    RouteProjection(CompanionEntity mob, NavContext original, Path path) {
        this(mob, original, path, System::nanoTime);
    }

    RouteProjection(CompanionEntity mob, NavContext original, Path path, LongSupplier clock) {
        this.mob = mob;
        this.original = original;
        this.path = path;
        this.clock = Objects.requireNonNull(clock);
        if (!path.complete || path.length() > MAX_MOVES) {
            damage = new float[0];
            fail(Status.UNPROVEN, CompanionTexts.tr("route.path_budget"));
        } else damage = new float[path.length() + 1];
    }

    Status status() { return status; }
    String reason() { return reason.getString(); }
    Component reasonComponent() { return reason.copy(); }
    int steps() { return next; }
    NavContext context() { return projected; }
    float[] damage() { return damage; }

    private Status fail(Status state, Component message) { status = state; reason = message.copy(); return state; }
    private boolean expired(long deadline) { return deadline != Long.MAX_VALUE && deadline - clock.getAsLong() <= 0; }

    Status step(long deadline) {
        if (status != Status.RUNNING || expired(deadline)) return status;
        if (preview == null)
            preview = new NavContext(mob, new BlockCache(original.level, changes), original.health, original.throwaway, true);
        while (next < path.length()) {
            if (expired(deadline)) return status;
            if (!moveStarted) { preview.blocks.clearCache(); moveStarted = true; }
            BlockPos from = path.positions.get(next), to = path.positions.get(next + 1);
            Move move = path.moves.get(next);
            long start = deadline == Long.MAX_VALUE ? 0 : clock.getAsLong();
            long left = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - start;
            if (left <= 0) return status;
            move.cost(preview, from.getX(), from.getY(), from.getZ(), result,
                    deadline == Long.MAX_VALUE ? null : clock, start, left);
            if (result.yielded) return status;
            if (result.cost >= Costs.INF || result.x != to.getX() || result.y != to.getY() || result.z != to.getZ())
                return fail(Status.UNPROVEN, CompanionTexts.tr("route.outbound_unexecutable"));
            if (!watch(from) || !watch(to) || !watch(to.below())) return status;
            Movement intended = new Movement(move, from, to, path.costs.get(next));
            for (BlockPos p : intended.plannedBreaks()) {
                int flags = preview.blocks.flags(p.getX(), p.getY(), p.getZ());
                if (BlockFlags.has(flags, BlockFlags.PASS | BlockFlags.DOOR | BlockFlags.GATE | BlockFlags.CLIMB
                        | BlockFlags.PORTAL | BlockFlags.END_PORTAL | BlockFlags.LIQUID)) continue;
                if (BlockFlags.has(flags | preview.blocks.flags(p.getX(), p.getY() + 1, p.getZ()), BlockFlags.FALLING))
                    return fail(Status.UNPROVEN, CompanionTexts.tr("route.falling_blocks"));
                if (!watch(p) || !watch(p.above())) return status;
                changes.put(p.asLong(), Blocks.AIR.defaultBlockState());
            }
            placed += result.placed;
            if (placed > original.throwaway) return fail(Status.UNPROVEN, CompanionTexts.tr("route.material"));
            if (result.placed > 0) {
                Block block = original.throwawayItem == null ? Blocks.AIR : Block.byItem(original.throwawayItem);
                BlockPos position = intended.plannedPlacement();
                if (block == Blocks.AIR || position == null
                        || BlockFlags.has(BlockFlags.of(block.defaultBlockState()), BlockFlags.FALLING | BlockFlags.FRAGILE))
                    return fail(Status.UNPROVEN, CompanionTexts.tr("route.unstable_build"));
                if (!watch(position)) return status;
                changes.put(position.asLong(), block.defaultBlockState());
            }
            // Die frischen Zustände dieser Vorschau verwenden, nicht die Flags der älteren Hinwegsuche.
            damage[next + 1] = damage[next] + RoundTripCheck.movementFallDamage(preview, path, next);
            next++;
            moveStarted = false;
        }
        float totalDamage = damage[damage.length - 1];
        if (totalDamage > 0 && original.health - totalDamage < NavContext.MIN_HEALTH_AFTER_FALL)
            return fail(Status.UNSAFE, CompanionTexts.tr("route.outbound_fall"));
        if (expired(deadline)) return status;
        projected = new NavContext(mob, new BlockCache(original.level, Map.copyOf(changes)), original.health - totalDamage,
                Math.max(0, original.throwaway - placed), true);
        status = Status.COMPLETE;
        return status;
    }

    private boolean watch(BlockPos position) {
        long key = position.asLong();
        if (watched.containsKey(key)) return true;
        if (watched.size() >= MAX_WATCHED) { fail(Status.UNPROVEN, CompanionTexts.tr("route.world_budget")); return false; }
        watched.put(key, original.blocks.get(position.getX(), position.getY(), position.getZ()));
        return true;
    }

    /** Unmittelbar vor der abschließenden, frischen Wegprüfung auf demselben Server-Thread verwenden. */
    boolean worldUnchanged() {
        original.blocks.clearCache();
        for (var entry : watched.entrySet()) {
            BlockPos p = BlockPos.of(entry.getKey());
            if (original.blocks.get(p.getX(), p.getY(), p.getZ()) != entry.getValue()) return false;
        }
        return true;
    }
}
