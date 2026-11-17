package dev.begleiter.nav;

import net.minecraft.core.BlockPos;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;

/**
 * Läuft einen {@link Path} ab, jeden Tick. Fertige Bewegung → nächste im selben Tick. Steht er auf einem späteren
 * Wegpunkt, spult er vor, auf einem früheren zurück. Weiter als 2 Blöcke vom Weg zählt er mit; über 3 Blöcke oder
 * zu lange daneben: Weg verworfen (der Aufrufer plant neu). Beim Start jeder Bewegung werden die nächsten 5 gegen die
 * Welt geprüft.
 */
public final class PathExecutor {
    public enum Status { RUNNING, DONE, FAILED }

    /** So viele Bewegungen werden beim Start jeder Bewegung neu geprüft (auch die Länge einer Parkour-Kette). */
    static final int LOOKAHEAD = 6;
    private static final int MAX_OFF_TICKS = 100;
    /** So viele Bewegungen höchstens pro Tick abschließen (nach jedem Erfolg geht es im selben Tick weiter). */
    private static final int MAX_MOVES_PER_TICK = 4;

    public Path path;
    private NavContext ctx;
    private int index;
    private Movement current;
    private int offTicks;
    private int bestIndex, sinceProgress;
    private static final int STALL_TICKS = 100;
    private final GroundSteering steering = new GroundSteering();
    private final boolean allowSteering;
    private double progress, bestProgress;
    private int groundStall;
    public long validationNanos, steeringNanos;
    public boolean steeringActive;
    private Status status = Status.RUNNING;
    public String failReason = "";
    private Component failureText = Component.empty();
    private String failureSource = "";
    public Component failureComponent() {
        return failReason.equals(failureSource) ? failureText.copy() : CompanionTexts.literal(failReason);
    }
    /** Wegpunkt, an dem es scheiterte (für Fehlstellen), oder null. */
    public BlockPos failedAt;

    public PathExecutor(Path path, NavContext ctx) {
        this(path, ctx, true);
    }

    public PathExecutor(Path path, NavContext ctx, boolean allowSteering) {
        this.path = path;
        this.ctx = ctx;
        this.allowSteering = allowSteering;
    }

    public Status status() { return status; }
    public int index() { return index; }
    public Movement current() { return current; }

    /** Restkosten ab der aktuellen Bewegung (für das Vorausplanen). */
    public double remainingCost() {
        return path.remainingCost(index);
    }

    public double progress() { return Math.max(index, progress); }

    public void cancel(CompanionEntity mob) {
        if (current != null) current.cancel(mob);
        mob.motion.stop();
    }

    /** Fortsetzung anhängen, bevor er das Ende erreicht; die laufende Bewegung bleibt unberührt. */
    boolean append(Path next) {
        if (!path.end().equals(next.start())) return false;
        path = path.append(next);
        return true;
    }

    /** Neuen Weg nur an einer gemeinsamen, sicher abbrechbaren Bewegung übernehmen; nie mitten in einer Aktion. */
    boolean replace(Path next, NavContext context, CompanionEntity mob) {
        if (!safeToCancel(mob) || (current != null && current.hasWorked())) return false;
        for (int i = 0; i < next.length(); i++) {
            if (current != null && next.positions.get(i).equals(current.src)
                    && next.positions.get(i + 1).equals(current.dest) && next.moves.get(i) == current.move) {
                path = next; ctx = context; index = i;
                bestIndex = i; sinceProgress = groundStall = 0; bestProgress = progress = i;
                status = Status.RUNNING;
                return true;
            }
        }
        if (!mob.onGround() && !mob.isInWater()) return false;
        for (int i = 0; i < next.length(); i++) {
            if (next.positions.get(i).equals(mob.blockPosition())) {
                if (current != null) current.cancel(mob);
                path = next.suffix(i); ctx = context; index = bestIndex = 0; current = null;
                sinceProgress = groundStall = 0; bestProgress = progress = 0;
                status = Status.RUNNING;
                return true;
            }
        }
        return false;
    }

    public boolean safeToCancel(CompanionEntity mob) {
        return current == null || current.safeToCancel(mob);
    }

    private Status fail(Component why, BlockPos at) {
        failureText = why.copy(); failReason = why.getString(); failureSource = failReason;
        failedAt = at;
        status = Status.FAILED;
        return status;
    }

    public Status tick(CompanionEntity mob) {
        validationNanos = steeringNanos = 0;
        steeringActive = false;
        Status result = tickInternal(mob);
        if (result == Status.FAILED) cancel(mob);
        return result;
    }

    private Status tickInternal(CompanionEntity mob) {
        if (status != Status.RUNNING) return status;
        mob.navControlUntil = mob.tickCount + 1;
        // Wächter: hin und her zwischen zwei Wegpunkten (jedes Zurückspulen startet die Bewegung neu) zählt nicht als Fortschritt
        if (index > bestIndex) { bestIndex = index; sinceProgress = 0; }
        else if (++sinceProgress > STALL_TICKS + (bestIndex < path.length() ? path.costs.get(bestIndex) : 0)
                && safeToCancel(mob)) {
            mob.setSprinting(false);
            return fail(CompanionTexts.tr("nav.failure.no_progress"), path.positions.get(Math.min(bestIndex + 1, path.length())));
        }
        for (int n = 0; n < MAX_MOVES_PER_TICK; n++) {
            if (index >= path.length()) {
                mob.setSprinting(false);
                return status = Status.DONE;
            }
            if (current == null || current.safeToCancel(mob)) {
                if (resync(mob)) continue;
                if (offPath(mob)) return status;
            }
            // Laufende, sicher abbrechbare Bewegung neu prüfen (wie Baritone): eine neue Gefahr oder ein
            // abgebauter Boden darf nicht bis zum nächsten Wegpunkt warten.
            if (current != null && current.safeToCancel(mob) && !current.dest.equals(mob.blockPosition()) && !currentWorldOk()) {
                mob.setSprinting(false);
                return status;
            }
            if (current == null) {
                long validationStart = System.nanoTime();
                boolean ok = lookaheadOk();
                validationNanos += System.nanoTime() - validationStart;
                if (!ok) return status;
                current = new Movement(path.moves.get(index), path.positions.get(index), path.positions.get(index + 1),
                        path.costs.get(index));
                if (current.move.kind == Move.Kind.PARKOUR) {
                    // Parkour-Kette: folgt gleich der nächste Sprung, nimmt er den Schwung mit
                    int chain = 1;
                    while (chain < LOOKAHEAD && index + chain < path.length() && path.moves.get(index + chain).kind == Move.Kind.PARKOUR
                            && aligned(path.positions.get(index + chain - 1), path.positions.get(index + chain), path.positions.get(index + chain + 1)))
                        chain++;
                    current.chainLength = chain;
                    current.chained = chain > 1;
                    // geht es danach in dieselbe Richtung weiter (laufen/springen), nicht erst abbremsen
                    if (index + 1 < path.length()) {
                        BlockPos a = path.positions.get(index), b = path.positions.get(index + 1), c = path.positions.get(index + 2);
                        Move.Kind next = path.moves.get(index + 1).kind;
                        current.keepMomentum = aligned(a, b, c) && (next == Move.Kind.TRAVERSE || next == Move.Kind.DIAGONAL
                                || next == Move.Kind.ASCEND || next == Move.Kind.PARKOUR);
                    }
                }
            }
            if (allowSteering && !current.hasWorked() && WalkCorridor.walking(path, index)) {
                long start = System.nanoTime();
                var step = steering.tick(mob, ctx, path, index);
                steeringNanos += System.nanoTime() - start;
                if (step != null) {
                    steeringActive = true;
                    progress = step.progress();
                    if (progress > bestProgress + 0.1) { bestProgress = progress; groundStall = 0; }
                    else if (++groundStall > 45 && mob.onGround()) return fail(CompanionTexts.tr("nav.failure.no_ground_progress"), current.dest);
                    while (index < step.reached()) { mob.routes.noteStep(path.positions.get(++index), RouteMemory.worldKey(ctx.level)); current = null; }
                    if (current == null && index < path.length() && !WalkCorridor.walking(path, index)) continue;
                    return status;
                }
            }
            // Kein Sprint-Springen: beim Mob gemessen langsamer (flache 40-Block-Strecke 154 statt 101 Ticks)
            Movement.Status st = current.tick(mob, ctx);
            if (st == Movement.Status.RUNNING) return status;
            if (st == Movement.Status.FAILED) {
                mob.setSprinting(false);
                return fail(CompanionTexts.tr("nav.failure.movement", current.failureComponent(), current.move.kind.name(),
                        current.src.toShortString(), current.dest.toShortString()), current.dest);
            }
            mob.routes.noteStep(current.dest, RouteMemory.worldKey(ctx.level)); // Trampelpfad
            mob.motion.release(MotionController.Owner.SPECIAL); // nach geglückter Landung schon in diesem Tick abgeben
            index++;
            current = null;
        }
        return status;
    }

    /** Spulen: steht er schon auf einem späteren Wegpunkt (ab +2) oder wieder auf einem früheren. */
    private boolean resync(CompanionEntity mob) {
        if (!mob.onGround() && !mob.isInWater()) return false;
        BlockPos feet = mob.blockPosition();
        for (int j = Math.min(path.length(), index + 12); j >= index + 2; j--) {
            if (path.positions.get(j).equals(feet)) {
                index = j;
                current = null;
                return true;
            }
        }
        for (int j = index - 1; j >= Math.max(0, index - 8); j--) {
            if (path.positions.get(j).equals(feet)) {
                index = j;
                current = null;
                return true;
            }
        }
        return false;
    }

    /** Vom Weg abgekommen? Abstand zu aktuellem und nächstem Wegpunkt; im Fallen nur waagerecht. */
    private boolean offPath(CompanionEntity mob) {
        double best = Double.MAX_VALUE;
        for (int j = index; j <= Math.min(index + 1, path.length()); j++) {
            BlockPos p = path.positions.get(j);
            double dx = p.getX() + 0.5 - mob.getX(), dy = mob.onGround() ? p.getY() - mob.getY() : 0, dz = p.getZ() + 0.5 - mob.getZ();
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        if (best > 3 * 3) {
            fail(CompanionTexts.tr("nav.failure.off_path"), null);
            return true;
        }
        if (best > 2 * 2) {
            if (++offTicks > MAX_OFF_TICKS) {
                fail(CompanionTexts.tr("nav.failure.off_path_timeout"), null);
                return true;
            }
        } else offTicks = 0;
        return false;
    }

    /** Die nächsten Bewegungen noch möglich? (Spieler hat etwas gebaut, Lava ist geflossen …) */
    private boolean lookaheadOk() {
        ctx.blocks.clearCache();
        ctx.refreshWorldBorder();
        ctx.refreshDangers();
        MoveResult r = new MoveResult();
        for (int i = index; i < Math.min(path.length(), index + LOOKAHEAD); i++) {
            BlockPos from = path.positions.get(i), to = path.positions.get(i + 1);
            path.moves.get(i).cost(ctx, from.getX(), from.getY(), from.getZ(), r);
            if (r.cost >= Costs.INF || r.x != to.getX() || r.y != to.getY() || r.z != to.getZ()) {
                fail(CompanionTexts.tr("nav.failure.blocked", to.toShortString()), to);
                return false;
            }
        }
        return true;
    }

    private boolean currentWorldOk() {
        long started = System.nanoTime();
        try { return checkCurrentWorld(); }
        finally { validationNanos += System.nanoTime() - started; }
    }

    private boolean checkCurrentWorld() {
        ctx.blocks.clearCache();
        ctx.refreshWorldBorder();
        ctx.refreshDangers();
        MoveResult r = new MoveResult();
        current.move.cost(ctx, current.src.getX(), current.src.getY(), current.src.getZ(), r);
        if (r.cost >= Costs.INF || r.x != current.dest.getX() || r.y != current.dest.getY() || r.z != current.dest.getZ()) {
            fail(CompanionTexts.tr("nav.failure.unsafe", current.dest.toShortString()), current.dest);
            return false;
        }
        return true;
    }

    static boolean aligned(BlockPos a, BlockPos b, BlockPos c) {
        double ax = b.getX() - a.getX(), az = b.getZ() - a.getZ(), bx = c.getX() - b.getX(), bz = c.getZ() - b.getZ();
        double length = Math.sqrt((ax * ax + az * az) * (bx * bx + bz * bz));
        return length > 0 && (ax * bx + az * bz) / length > 0.95;
    }
}
