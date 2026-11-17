package dev.begleiter;

import dev.begleiter.nav.BlockFlags;
import dev.begleiter.nav.MotionController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded, read-only landing checks. Missing chunks always mean an unconfirmed destination. */
final class CompanionSafeTeleport {
    private CompanionSafeTeleport() {}

    static Vec3 nearOwner(CompanionEntity mob, ServerLevel level, Vec3 owner) {
        return find(mob, level, owner, 3, 24, 4);
    }

    static Vec3 find(CompanionEntity mob, ServerLevel level, Vec3 around, int radius, int down, int up) {
        if (!finite(around)) return null;
        radius = Math.clamp(radius, 0, 12);
        down = Math.clamp(down, 0, 24);
        up = Math.clamp(up, 0, 4);
        BlockPos center = BlockPos.containing(around);
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -down; dy <= up; dy++) {
            int y = center.getY() + dy;
            if (y <= level.getMinY() || y + mob.getBbHeight() > (double) level.getMaxY() + 1) continue;
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                Vec3 at = new Vec3((double) center.getX() + dx + 0.5, y, (double) center.getZ() + dz + 0.5);
                double distance = at.distanceToSqr(around);
                if (distance >= bestDistance || !safe(mob, level, at)) continue;
                best = at;
                bestDistance = distance;
            }
        }
        return best;
    }

    static boolean safe(CompanionEntity mob, ServerLevel level, Vec3 at) {
        if (!finite(at) || at.y != Math.floor(at.y)) return false;
        double half = mob.getBbWidth() / 2.0;
        AABB body = new AABB(at.x - half, at.y, at.z - half, at.x + half, at.y + mob.getBbHeight(), at.z + half);
        var border = level.getWorldBorder();
        if (body.minY <= level.getMinY() || body.maxY > (double) level.getMaxY() + 1
                || body.minX < border.getMinX() || body.maxX > border.getMaxX()
                || body.minZ < border.getMinZ() || body.maxZ > border.getMaxZ()) return false;
        // Collision shapes can inspect neighboring blocks. Confirm their chunks before any world read.
        int minX = Mth.floor(body.minX) - 1, maxX = Mth.floor(body.maxX) + 1;
        int minZ = Mth.floor(body.minZ) - 1, maxZ = Mth.floor(body.maxZ) + 1;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++)
            if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
        int floorY = Mth.floor(at.y) - 1;
        for (int x = Mth.floor(body.minX); x <= Mth.floor(body.maxX - 1.0e-7); x++)
            for (int z = Mth.floor(body.minZ); z <= Mth.floor(body.maxZ - 1.0e-7); z++) {
                BlockPos floor = new BlockPos(x, floorY, z);
                var state = level.getBlockState(floor);
                if (!state.getFluidState().isEmpty() || !state.isFaceSturdy(level, floor, Direction.UP)) return false;
            }
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            for (int y = floorY; y <= Math.min(level.getMaxY(), Mth.floor(body.maxY)); y++) {
                var state = level.getBlockState(new BlockPos(x, y, z));
                if (!state.getFluidState().isEmpty()
                        || BlockFlags.has(BlockFlags.of(state), BlockFlags.AVOID_INTO | BlockFlags.PORTAL)) return false;
            }
        return level.noCollision(mob, body)
                && level.getEntitiesOfClass(LivingEntity.class, body, other -> other != mob && other.isAlive()).isEmpty();
    }

    static boolean move(CompanionEntity mob, ServerLevel level, Vec3 at) {
        if (mob.level() != level || !safe(mob, level, at)) return false;
        mob.snapTo(at.x, at.y, at.z, mob.getYRot(), mob.getXRot());
        resetMotion(mob);
        return true;
    }

    static void resetMotion(CompanionEntity mob) {
        mob.nav.stop();
        mob.getNavigation().stop();
        mob.motion.move(MotionController.Owner.RESCUE, mob.getX(), mob.getY(), mob.getZ(), 0, false);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.resetFallDistance();
        mob.setOnGround(true);
    }

    private static boolean finite(Vec3 p) {
        return p != null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z)
                && Math.abs(p.x) < 30_000_000 && Math.abs(p.z) < 30_000_000
                && Math.abs(p.y) < 30_000_000;
    }
}
