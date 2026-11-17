package dev.begleiter.nav;

import dev.begleiter.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Örtliche Live-Prüfung für flaches Gehen: lädt nichts nach, ändert nichts, kürzt nicht über Aktionen hinweg ab. */
final class WalkCorridor {
    static final int LOOKAHEAD = 6;
    private static final int FORBIDDEN = BlockFlags.AVOID_INTO | BlockFlags.LIQUID | BlockFlags.CLIMB
            | BlockFlags.DOOR | BlockFlags.GATE | BlockFlags.PORTAL;

    static boolean walking(Path path, int index) {
        if (index >= path.length()) return false;
        Move.Kind kind = path.moves.get(index).kind;
        return (kind == Move.Kind.TRAVERSE || kind == Move.Kind.DIAGONAL)
                && path.positions.get(index).getY() == path.positions.get(index + 1).getY();
    }

    static int end(CompanionEntity mob, NavContext ctx, Path path, int index) {
        // Anlauf und erster Schritt nach der Landung gehören JumpSim. Das Hinterherlaufen darf Tempo und Absprung
        // darunter nicht verändern, auch nicht auf einer breiten Fläche.
        if (index > 0 && path.moves.get(index - 1).kind == Move.Kind.PARKOUR) return index;
        for (int i = index; i < Math.min(path.length(), index + 4); i++) {
            if (path.moves.get(i).kind == Move.Kind.PARKOUR) return index;
            if (!walking(path, i)) break;
        }
        if (!mob.onGround() || mob.isInWater() || mob.onClimbable()
                || Math.abs(mob.getY() - path.positions.get(index).getY()) > 0.05) return index;
        int end = index;
        int y = path.positions.get(index).getY();
        while (end < Math.min(path.length(), index + LOOKAHEAD) && walking(path, end)
                && path.positions.get(end + 1).getY() == y && plain(ctx, path.positions.get(end + 1))) end++;
        return end;
    }

    private static boolean plain(NavContext ctx, BlockPos p) {
        int feet = ctx.blocks.flags(p.getX(), p.getY(), p.getZ());
        int head = ctx.blocks.flags(p.getX(), p.getY() + 1, p.getZ());
        var floor = ctx.blocks.get(p.getX(), p.getY() - 1, p.getZ());
        return BlockFlags.has(feet, BlockFlags.PASS) && BlockFlags.has(head, BlockFlags.PASS)
                && ((feet | head) & FORBIDDEN) == 0 && floor != null
                && floor.isFaceSturdy(ctx.level, p.below(), Direction.UP)
                && !BlockFlags.has(BlockFlags.of(floor), BlockFlags.AVOID_INTO | BlockFlags.SLOW | BlockFlags.FRAGILE);
    }

    /** Vorsichtige Prüfung der überstrichenen Körperboxen, mit vollem Boden unter jeder Probe; nur für nahe Ziele. */
    static boolean clear(CompanionEntity mob, NavContext ctx, Vec3 from, Vec3 to) {
        double length = from.distanceTo(to);
        if (length > 4 || Math.abs(from.y - to.y) > 0.05) return false;
        if (!ctx.safeSegment((int) Math.floor(from.x), (int) Math.floor(from.y), (int) Math.floor(from.z),
                (int) Math.floor(to.x), (int) Math.floor(to.y), (int) Math.floor(to.z))) return false;
        int steps = Math.max(1, (int) Math.ceil(length / 0.2));
        double radius = mob.getBbWidth() / 2.0 + 0.02;
        Vec3 previous = from;
        for (int i = 0; i <= steps; i++) {
            Vec3 p = from.lerp(to, i / (double) steps);
            AABB body = new AABB(p.x - radius, p.y + 0.001, p.z - radius,
                    p.x + radius, p.y + mob.getBbHeight() - 0.001, p.z + radius);
            AABB sweep = body.expandTowards(previous.subtract(p));
            int minX = (int) Math.floor(sweep.minX), maxX = (int) Math.floor(sweep.maxX);
            int minZ = (int) Math.floor(sweep.minZ), maxZ = (int) Math.floor(sweep.maxZ);
            for (int x = minX - 1; x <= maxX + 1; x++) for (int z = minZ - 1; z <= maxZ + 1; z++) {
                if (!ctx.blocks.loaded(x, z)) return false;
                boolean underBody = x >= minX && x <= maxX && z >= minZ && z <= maxZ;
                if (underBody && !ctx.worldBorder.entirelyContains(x, z)) return false;
                int floorY = (int) Math.floor(p.y + 0.01) - 1;
                for (int y = floorY; y <= (int) Math.floor(sweep.maxY); y++) {
                    var block = ctx.blocks.get(x, y, z);
                    if (block == null) return false;
                    int flags = BlockFlags.of(block);
                    if (underBody && (flags & FORBIDDEN) != 0) return false;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (y == floorY && underBody && (!block.isFaceSturdy(ctx.level, pos, Direction.UP)
                            || BlockFlags.has(flags, BlockFlags.SLOW | BlockFlags.FRAGILE))) return false;
                    for (var box : block.getCollisionShape(ctx.level, pos).toAabbs())
                        if (box.move(x, y, z).intersects(sweep)) return false;
                }
            }
            previous = p;
        }
        return true;
    }

    static Vec3 point(Path path, int index) { return Vec3.atBottomCenterOf(path.positions.get(index)); }

    static double projection(Vec3 pos, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        return Math.max(0, Math.min(1, ((pos.x - a.x) * dx + (pos.z - a.z) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
    }
}
