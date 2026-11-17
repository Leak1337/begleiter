package dev.begleiter.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Ein Wasser-Clutch geht nur auf einer geladenen, ersetzbaren Zelle mit stabilem, vollem Untergrund. */
public record ClutchPlan(BlockPos landing, int fallBlocks, double estimatedTicks) {
    public static final double REACH = 4.5;

    public static ClutchPlan water(NavContext ctx, BlockPos landing, int fallBlocks) {
        if (!ctx.waterBucket || !ctx.allowPlace || ctx.nether
                || ctx.level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES, landing)
                || fallBlocks < 1 || fallBlocks > Move.BUCKET_FALL || !validTarget(ctx.blocks, landing)) return null;
        return new ClutchPlan(landing.immutable(), fallBlocks, Costs.FALL[fallBlocks]);
    }

    /** Zustände direkt lesen: das Setzen darf keinen Merkmalen trauen, die vor einer Weltänderung zwischengespeichert wurden. */
    static boolean validTarget(BlockCache blocks, BlockPos p) {
        if (p.getY() <= blocks.minY || p.getY() >= blocks.maxY) return false;
        BlockState cell = blocks.get(p.getX(), p.getY(), p.getZ());
        BlockState head = blocks.get(p.getX(), p.getY() + 1, p.getZ());
        BlockState floor = blocks.get(p.getX(), p.getY() - 1, p.getZ());
        if (cell == null || head == null || floor == null || !cell.canBeReplaced()
                || !cell.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) return false;
        int flags = BlockFlags.of(cell) | BlockFlags.of(head) | BlockFlags.of(floor);
        if (BlockFlags.has(flags, BlockFlags.AVOID_INTO | BlockFlags.FRAGILE | BlockFlags.FALLING)) return false;
        if (floor.is(Blocks.SLIME_BLOCK) || !floor.isFaceSturdy(blocks.level(), p.below(), Direction.UP)) return false;
        return cell.getCollisionShape(blocks.level(), p).isEmpty()
                && head.getCollisionShape(blocks.level(), p.above()).isEmpty();
    }

    /** Es braucht einen ganzen Tick für den Wasserkontakt, und die Landesäule muss erreichbar sein. */
    public boolean inPlacementWindow(Vec3 feet, Vec3 velocity, double eyeHeight, double halfWidth) {
        double height = feet.y - landing.getY();
        if (velocity.y >= 0 || height < 0 || height > Math.max(2.5, -velocity.y * 2 + 0.5)) return false;
        double nextX = feet.x + velocity.x, nextZ = feet.z + velocity.z;
        if (nextX - halfWidth < landing.getX() || nextX + halfWidth > landing.getX() + 1
                || nextZ - halfWidth < landing.getZ() || nextZ + halfWidth > landing.getZ() + 1) return false;
        Vec3 eye = feet.add(0, eyeHeight, 0);
        Vec3 surface = new Vec3(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        return eye.distanceToSqr(surface) <= REACH * REACH;
    }

    static boolean ownSource(BlockCache blocks, BlockPos at) {
        var state = blocks.level().getFluidState(at);
        return state.is(FluidTags.WATER) && state.isSource();
    }
}
