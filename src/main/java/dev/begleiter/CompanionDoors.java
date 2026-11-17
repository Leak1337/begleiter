package dev.begleiter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/** Türen wie ein Spieler: herstellen, einsetzen, öffnen, hinter sich schließen, nachts zumachen. */
public final class CompanionDoors {
    private CompanionDoors() {}

    /** Untere Hälfte einer Tür (bei der oberen Hälfte eins tiefer). */
    public static BlockPos lower(ServerLevel sl, BlockPos p) {
        BlockState s = sl.getBlockState(p);
        if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) return p.below();
        return p;
    }

    public static boolean isDoor(ServerLevel sl, BlockPos p) {
        return sl.getBlockState(p).getBlock() instanceof DoorBlock;
    }

    public static boolean isOpenable(ServerLevel sl, BlockPos p) {
        BlockState s = sl.getBlockState(p);
        return (s.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(s)) || s.getBlock() instanceof FenceGateBlock;
    }

    public static boolean isOpen(ServerLevel sl, BlockPos p) {
        BlockState s = sl.getBlockState(p);
        if (s.getBlock() instanceof DoorBlock) return s.getValue(DoorBlock.OPEN);
        if (s.getBlock() instanceof FenceGateBlock) return s.getValue(FenceGateBlock.OPEN);
        return false;
    }

    /** Holztür oder Zauntor öffnen/schließen (Eisentüren gehen nur mit Redstone). */
    public static boolean setOpen(ServerLevel sl, CompanionEntity e, BlockPos p, boolean open) {
        BlockState s = sl.getBlockState(p);
        if (s.getBlock() instanceof DoorBlock door && DoorBlock.isWoodenDoor(s)) {
            if (s.getValue(DoorBlock.OPEN) == open) return false;
            door.setOpen(e, sl, s, p, open);
            e.swingArm();
            return true;
        }
        if (s.getBlock() instanceof FenceGateBlock) {
            if (s.getValue(FenceGateBlock.OPEN) == open) return false;
            sl.setBlock(p, s.setValue(FenceGateBlock.OPEN, open), 10);
            sl.playSound(null, p, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1f, 1f);
            e.swingArm();
            return true;
        }
        return false;
    }

    /** Slot mit einer Holztür im Inventar, -1 = keine. */
    public static int doorSlot(CompanionEntity e) {
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            Block b = Block.byItem(s.getItem());
            if (b instanceof DoorBlock && DoorBlock.isWoodenDoor(b.defaultBlockState())) return i;
        }
        return -1;
    }

    /** Passt hier eine Tür hin? (zwei freie Blöcke, fester Boden darunter) */
    public static boolean canPlace(ServerLevel sl, BlockPos lower) {
        return sl.getBlockState(lower).canBeReplaced() && sl.getBlockState(lower.above()).canBeReplaced()
                && sl.getBlockState(lower.below()).isFaceSturdy(sl, lower.below(), Direction.UP);
    }

    /** Tür aus dem Slot setzen: beide Hälften, Blickrichtung facing (Seite, von der man hereinkommt). */
    public static boolean place(ServerLevel sl, CompanionEntity e, BlockPos lower, Direction facing, int slot) {
        if (slot < 0 || !canPlace(sl, lower)) return false;
        Block b = Block.byItem(e.getInventory().getItem(slot).getItem());
        if (!(b instanceof DoorBlock)) return false;
        Direction f = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        BlockState base = b.defaultBlockState().setValue(DoorBlock.FACING, f).setValue(DoorBlock.OPEN, false);
        BlockState before = sl.getBlockState(lower);
        if (!e.placeConfirmed(sl, lower, base.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER))) return false;
        if (!e.placeConfirmed(sl, lower.above(), base.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))) {
            e.worldSet(sl, lower, before, 3); // keine halbe Tür stehen lassen, Tür bleibt im Inventar
            return false;
        }
        sl.playSound(null, lower, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1f, 0.9f);
        e.getInventory().getItem(slot).shrink(1);
        e.swingArm();
        return true;
    }

    /** Steht irgendwer (Spieler, Mob, er selbst) in der Tür? Dann nicht zumachen. */
    static boolean occupied(ServerLevel sl, BlockPos lower) {
        return !sl.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new AABB(lower).expandTowards(0, 1, 0).inflate(0.1), le -> le.isAlive()).isEmpty();
    }

    /**
     * Türen, die er auf dem Weg geöffnet hat, wieder schließen, sobald er durch ist – wie ein ordentlicher Spieler.
     * Steht der Besitzer gerade an der Tür, bleibt sie noch offen (höchstens 10 s).
     */
    public static void closeBehind(ServerLevel sl, CompanionEntity e) {
        var it = e.openedDoors.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            BlockPos p = en.getKey();
            int age = e.tickCount - en.getValue();
            if (!sl.hasChunkAt(p) || !isOpenable(sl, p) || !isOpen(sl, p) || age > 1200) { it.remove(); continue; }
            if (age < 15) continue;
            double dx = e.getX() - (p.getX() + 0.5), dz = e.getZ() - (p.getZ() + 0.5);
            // noch in der Tür oder direkt davor
            if (e.getBoundingBox().inflate(0.3).intersects(new AABB(p).expandTowards(0, 1, 0))
                    || (dx * dx + dz * dz < 1.5 * 1.5 && Math.abs(e.getY() - p.getY()) < 2)) continue;
            Player owner = e.getOwner();
            if (owner != null && owner.level() == e.level() && age < 200
                    && owner.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5) < 3.5 * 3.5) continue;
            if (occupied(sl, p)) continue;
            setOpen(sl, e, p, false);
            it.remove();
        }
    }

    public static Direction direction(String name) {
        if (name != null) for (Direction d : Direction.values()) if (d.getSerializedName().equals(name)) return d;
        return Direction.NORTH;
    }
}
