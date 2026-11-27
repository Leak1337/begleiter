package dev.begleiter;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Baustein Fackeln: unter Tage im Dunkeln Licht setzen und bei Bedarf Fackeln herstellen. Nur Laufzeit. */
public final class CompanionTorches {
    private final CompanionEntity e;
    private int lastTorchTick = -1000;

    CompanionTorches(CompanionEntity e) { this.e = e; }

    /** Unter Tage im Dunkeln eine Fackel auf den Boden stellen (bei Bedarf aus Kohle + Stock herstellen). */
    void tick(ServerLevel sl) {
        if (e.hasRecipeRequest()) return; // Fackeln nicht aus Zutaten herstellen, die für einen Auftrag vorgesehen sind
        if (e.tickCount - lastTorchTick < 40 || (!e.hasTasks() && !e.isAutonomous())) return;
        // Nicht beim Hochbauen (Fackel im Schacht unter den Füßen) und nicht mitten in einem Sprung/Klettern des Weges
        Task cur = e.currentTask();
        var exec = e.nav.executor();
        var move = exec == null || exec.current() == null ? null : exec.current().move.kind;
        if (e.scaffolding.active() || (cur != null && cur.type() == TaskType.PILLAR) || move == dev.begleiter.nav.Move.Kind.PILLAR
                || move == dev.begleiter.nav.Move.Kind.PARKOUR || !e.onGround()) return;
        BlockPos feet = e.blockPosition();
        if (sl.getBrightness(LightLayer.BLOCK, feet) >= 4 || sl.getBrightness(LightLayer.SKY, feet) >= 4) return;
        if (!sl.getBlockState(feet).isAir() || !sl.getBlockState(feet.below()).isFaceSturdy(sl, feet.below(), Direction.UP)) return;
        if (e.findItem(Items.TORCH) < 0) craft();
        int slot = e.findItem(Items.TORCH);
        if (slot < 0) return;
        // Wie ein Spieler an die Wand (steht nicht im Weg, z.B. beim Hochbauen im Schacht); sonst auf den Boden
        BlockState torch = Blocks.TORCH.defaultBlockState();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos wall = feet.relative(d);
            if (sl.getBlockState(wall).isFaceSturdy(sl, wall, d.getOpposite())) {
                torch = Blocks.WALL_TORCH.defaultBlockState().setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, d.getOpposite());
                break;
            }
        }
        if (!e.placeConfirmed(sl, feet, torch)) return;
        e.getInventory().getItem(slot).shrink(1);
        lastTorchTick = e.tickCount;
        e.logInfo(CompanionTexts.tr("log.torch.placed", feet.toShortString()));
    }

    /** 4 Fackeln, wenn Kohle und Holz (Stock/Bretter/Stamm) da sind. */
    public boolean craft() {
        if (CompanionCraft.count(e, "#coals") < 1) return false;
        if (CompanionCraft.count(e, "stick") < 1 && CompanionCraft.count(e, "#planks") < 2
                && CompanionCraft.count(e, "#logs") < 1) return false;
        return CompanionCraft.craft(e, "torch", 4);
    }
}
