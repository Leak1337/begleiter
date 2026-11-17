package dev.begleiter.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import dev.begleiter.CompanionTexts;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Fortsetzbare Schlussprüfung beider Wege; erst ein frischer Gesamtvergleich gibt die Schnappschüsse frei. */
final class RouteVerification implements AutoCloseable {
    enum Status { RUNNING, COMPLETE, UNPROVEN, UNSAFE }
    private final Path[] paths;
    private final NavContext[] contexts;
    private final LongSupplier clock;
    private final MoveResult result = new MoveResult();
    private final List<BlockPos> outwardPlacements = new ArrayList<>(), returnPlacements = new ArrayList<>();
    private NavContext.VerificationPolicy outwardPolicy, returnPolicy;
    private int leg, next, placed;
    private float returnDamage;
    private boolean outwardActive, returnActive, initialized;
    private Status status = Status.RUNNING;
    private Component reason = Component.empty();

    RouteVerification(Path outward, NavContext outContext, Path returning, NavContext returnContext) {
        this(outward, outContext, returning, returnContext, System::nanoTime);
    }

    RouteVerification(Path outward, NavContext outContext, Path returning, NavContext returnContext, LongSupplier clock) {
        paths = new Path[]{outward, returning};
        contexts = new NavContext[]{outContext, returnContext};
        this.clock = Objects.requireNonNull(clock);
        for (Path p : paths)
            if (!p.complete || p.length() > RouteProjection.MAX_MOVES || p.positions.size() != p.length() + 1)
                finish(Status.UNPROVEN, CompanionTexts.tr("route.path_budget"));
    }

    Status status() { return status; }
    String reason() { return reason.getString(); }
    Component reasonComponent() { return reason.copy(); }
    int steps() { return leg == 0 ? next : paths[0].length() + (leg == 2 ? paths[1].length() : next); }
    float returnDamage() { return returnDamage; }
    private boolean expired(long deadline) { return deadline != Long.MAX_VALUE && deadline - clock.getAsLong() <= 0; }

    Status step(long deadline) {
        if (status != Status.RUNNING) return status;
        try {
            if (expired(deadline)) return status;
            if (!initialized) {
                contexts[0].refreshWorldBorder(); contexts[0].refreshDangers();
                contexts[1].refreshWorldBorder(); contexts[1].refreshDangers();
                outwardPolicy = contexts[0].verificationPolicy(); returnPolicy = contexts[1].verificationPolicy();
                contexts[0].blocks.beginVerificationSnapshot(); outwardActive = true;
                if (contexts[1].blocks != contexts[0].blocks) {
                    contexts[1].blocks.beginVerificationSnapshot(); returnActive = true;
                }
                initialized = true;
            }
            while (leg < 2) {
                Path path = paths[leg]; NavContext ctx = contexts[leg];
                if (next == path.length()) { leg++; next = 0; placed = 0; continue; }
                if (expired(deadline)) return status;
                long start = deadline == Long.MAX_VALUE ? 0 : clock.getAsLong();
                long left = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - start;
                if (left <= 0) return status;
                BlockPos from = path.positions.get(next), to = path.positions.get(next + 1);
                Move move = path.moves.get(next);
                move.cost(ctx, from.getX(), from.getY(), from.getZ(), result,
                        deadline == Long.MAX_VALUE ? null : clock, start, left);
                if (result.yielded) return status;
                if (ctx.blocks.verificationOverflowed()) return finish(Status.UNPROVEN, CompanionTexts.tr("route.world_budget"));
                if (result.cost >= Costs.INF || result.x != to.getX() || result.y != to.getY() || result.z != to.getZ())
                    return finish(Status.UNPROVEN, CompanionTexts.tr("route.path_changed"));
                placed += result.placed;
                if (placed > ctx.throwaway) return finish(Status.UNPROVEN, CompanionTexts.tr("route.material"));
                if (result.placed > 0)
                    (leg == 0 ? outwardPlacements : returnPlacements).add(move.kind == Move.Kind.PILLAR ? from : to);
                if (leg == 1) returnDamage += RoundTripCheck.movementFallDamage(ctx, path, next);
                next++;
            }
            if (expired(deadline)) return status;
            // Diese billigen Gesamtvergleiche bleiben bewusst in einem Server-Aufruf.
            contexts[0].refreshDangers(); contexts[1].refreshDangers();
            if (!outwardPolicy.equals(contexts[0].verificationPolicy()) || !returnPolicy.equals(contexts[1].verificationPolicy()))
                return finish(Status.UNPROVEN, CompanionTexts.tr("route.policy_changed"));
            contexts[0].refreshWorldBorder(); contexts[1].refreshWorldBorder();
            if (!inside(paths[0], contexts[0], outwardPlacements) || !inside(paths[1], contexts[1], returnPlacements))
                return finish(Status.UNPROVEN, CompanionTexts.tr("route.border_changed"));
            if (!contexts[0].blocks.verificationUnchanged() || !contexts[1].blocks.verificationUnchanged())
                return finish(Status.UNPROVEN, CompanionTexts.tr("route.world_path_changed"));
            if (returnDamage > 0 && contexts[1].health - returnDamage < NavContext.MIN_HEALTH_AFTER_FALL)
                return finish(Status.UNSAFE, CompanionTexts.tr("route.return_fall"));
            return finish(Status.COMPLETE, CompanionTexts.tr("route.checked"));
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static boolean inside(Path path, NavContext ctx, List<BlockPos> placements) {
        for (BlockPos p : path.positions) if (!ctx.worldBorder.entirelyContains(p.getX(), p.getZ())) return false;
        for (BlockPos p : placements) if (!ctx.worldBorder.canPlaceAt(p.getX(), p.getZ())) return false;
        return true;
    }

    private Status finish(Status state, Component message) { status = state; reason = message.copy(); release(); return state; }
    private void release() {
        if (outwardActive) { contexts[0].blocks.endVerificationSnapshot(); outwardActive = false; }
        if (returnActive) { contexts[1].blocks.endVerificationSnapshot(); returnActive = false; }
    }

    @Override public void close() {
        if (status == Status.RUNNING) { status = Status.UNPROVEN; reason = CompanionTexts.tr("route.cancelled"); }
        release();
    }
}
