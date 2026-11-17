package dev.begleiter.nav;

import dev.begleiter.CompanionEntity;
import net.minecraft.world.phys.Vec3;

/** Zielpunkte zum Hinterherlaufen in einem frisch geprüften Korridor; die Bodenphysik bleibt bei Vanillas MoveControl. */
final class GroundSteering {
    record Step(int reached, double progress, boolean waiting) {}
    private double speed = Movement.WALK_SPEED;
    private int lastSprintChange = Integer.MIN_VALUE;
    private boolean sprint;
    private Vec3 avoidance;
    private int avoidanceUntil;

    Step tick(CompanionEntity mob, NavContext ctx, Path path, int index) {
        int end = WalkCorridor.end(mob, ctx, path, index);
        if (end <= index) return null;
        Vec3 position = mob.position();
        double lookahead = Math.max(1.2, Math.min(2.5, 1.2 + mob.getDeltaMovement().horizontalDistance() * 3));
        Vec3 target = position;
        double left = lookahead;
        for (int i = index + 1; i <= end; i++) {
            Vec3 next = WalkCorridor.point(path, i);
            double distance = target.distanceTo(next);
            if (distance >= left) { target = target.lerp(next, left / Math.max(1e-9, distance)); break; }
            target = next; left -= distance;
        }
        // Die Abkürzung darf eine Ecke nur schneiden, wenn die ganze überstrichene Fläche sicher ist
        if (!WalkCorridor.clear(mob, ctx, position, target)) {
            target = WalkCorridor.point(path, index + 1);
            if (!WalkCorridor.clear(mob, ctx, position, target)) return null;
        }
        boolean waiting = false;
        var neighbors = ctx.level.getEntitiesOfClass(CompanionEntity.class, mob.getBoundingBox().inflate(1.8),
                other -> other != mob && other.isAlive());
        if (!neighbors.isEmpty()) {
            if (avoidance != null && mob.tickCount < avoidanceUntil && position.distanceToSqr(avoidance) > 0.2 * 0.2
                    && WalkCorridor.clear(mob, ctx, position, avoidance) && freeOfNeighbors(mob, position, avoidance, neighbors)) target = avoidance;
            else {
                avoidance = null;
                Vec3 forward = target.subtract(position).normalize().scale(0.9);
                if (!freeOfNeighbors(mob, position, position.add(forward), neighbors)) {
                    for (int sign : new int[]{1, -1}) {
                        Vec3 side = position.add(forward.scale(0.5)).add(-forward.z * sign, 0, forward.x * sign);
                        if (WalkCorridor.clear(mob, ctx, position, side) && freeOfNeighbors(mob, position, side, neighbors)) {
                            avoidance = side; avoidanceUntil = mob.tickCount + 8; target = side; break;
                        }
                    }
                    if (avoidance == null) { target = position; waiting = true; }
                }
            }
        }
        Vec3 direction = target.subtract(position);
        double yaw = Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90;
        double error = Math.abs(wrap(yaw - mob.getYRot()));
        double remaining = position.distanceTo(WalkCorridor.point(path, end));
        boolean actionAhead = end < path.length() && !WalkCorridor.walking(path, end);
        boolean flowingTransition = actionAhead && end > index
                && (path.moves.get(end).kind == Move.Kind.PARKOUR || path.moves.get(end).kind == Move.Kind.ASCEND)
                && PathExecutor.aligned(path.positions.get(end - 1), path.positions.get(end), path.positions.get(end + 1));
        double wanted = waiting ? 0 : error > 65 ? 0.65 : error > 35 ? 0.9 : Movement.WALK_SPEED;
        if (!actionAhead && end == path.length() && remaining < 1.3) wanted = Math.min(wanted, Math.max(0.55, remaining));
        speed += Math.max(-0.22, Math.min(0.12, wanted - speed));
        boolean wantSprint = !waiting && avoidance == null && ctx.canSprint && mob.hunger > 6 && error < 40 && (remaining > 1.2 || flowingTransition);
        if (!wantSprint || mob.tickCount - (long) lastSprintChange >= 4) {
            if (sprint != wantSprint) lastSprintChange = mob.tickCount;
            sprint = wantSprint;
        }
        mob.getNavigation().stop();
        mob.motion.move(MotionController.Owner.NAVIGATION, target.x, target.y, target.z, waiting ? 0 : speed, sprint);
        mob.getLookControl().setLookAt(target.x, target.y + mob.getEyeHeight(), target.z, 20, 15);

        int reached = index;
        // Nur örtlich weiterzählen, in einem Abschnitt ohne Aktionen; jeder Wegpunkt wird einzeln gemeldet
        for (int i = index + 1; i <= end; i++) {
            Vec3 point = WalkCorridor.point(path, i), before = WalkCorridor.point(path, i - 1);
            Vec3 delta = point.subtract(before), offset = position.subtract(point);
            boolean crossed = delta.x * offset.x + delta.z * offset.z >= 0 && offset.horizontalDistanceSqr() < 0.8 * 0.8;
            boolean handover = i == end && flowingTransition && mob.blockPosition().equals(path.positions.get(i));
            if (point.distanceToSqr(position) < 0.35 * 0.35 || crossed || handover) reached = i;
            else break;
        }
        double fraction = reached < end ? WalkCorridor.projection(position, WalkCorridor.point(path, reached), WalkCorridor.point(path, reached + 1)) : 0;
        return new Step(reached, reached + fraction, waiting);
    }

    private static boolean freeOfNeighbors(CompanionEntity mob, Vec3 from, Vec3 to, java.util.List<CompanionEntity> neighbors) {
        var box = mob.getBoundingBox().deflate(0.02).expandTowards(to.subtract(from));
        for (var other : neighbors) if (box.intersects(other.getBoundingBox().inflate(0.08))) return false;
        return true;
    }

    static double wrap(double degrees) { return ((degrees % 360) + 540) % 360 - 180; }
}
