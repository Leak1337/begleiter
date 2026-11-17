package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import dev.begleiter.debug.BotDebug.EventKind;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.ai.DecisionOutcome;

/**
 * STATION: Werkbank / Ofen / Kiste herstellen und aufstellen. Tragbar – nur eine ganz nahe zählt, danach
 * packt er sie wieder ein. Das Item wird erst verbraucht, wenn der Block nachweislich steht.
 */
final class StationHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Station station;
    /** Vorhandene Station nur beim ersten Tick suchen (danach stellt er selbst eine auf). */
    private boolean checked;

    StationHandler(Task task) {
        this.task = task;
        this.station = (TaskSpec.Station) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        String id = station.item();
        boolean portable = station.portable();
        if (!checked) {
            checked = true;
            BlockPos existing = mob.findStation(id, portable ? 8 : 24);
            if (existing != null) {
                if (id.equals("minecraft:chest") && mob.getHome() == null) mob.setHome(existing);
                return Status.DONE;
            }
        }
        Item item = CompanionCraft.item(id);
        if (mob.findItem(item) < 0) {
            if (ctx.guardedSupply()) {
                ctx.abortAutonomousPlan(DecisionOutcome.Reason.MISSING_STATION);
                return Status.REPLACED;
            }
            if (!id.equals("minecraft:crafting_table") && mob.findStation("crafting_table", portable ? 8 : 24) == null) {
                if (task.tries() >= 2) { mob.problem(CompanionTexts.tr("handler.station.no_table", CompanionTexts.item(id))); return Status.FAILED; }
                // tragbar bleibt tragbar: Werkbank hier aufstellen, nicht zurück zum Haus laufen
                mob.replaceCurrent(List.of(Task.station("crafting_table", portable),
                        task.retry()));
                return Status.REPLACED;
            }
            if (!CompanionCraft.craft(mob, id, 1)) {
                mob.problem(CompanionTexts.tr("handler.station.craft_failed", CompanionTexts.item(id), mob.craftProblemComponent()));
                // wie CRAFT: nicht sofort denselben Plan wieder starten (sonst Schleife ohne Bretter)
                mob.reportAutonomousFailure(DecisionOutcome.Reason.MISSING_INPUT, task);
                mob.plannerCooldownUntil = mob.tickCount + 200;
                return Status.FAILED;
            }
        }
        BlockPos spot = mob.stationSpot(portable);
        if (spot == null) {
            if (ctx.guardedSupply()) {
                ctx.abortAutonomousPlan(DecisionOutcome.Reason.MISSING_STATION);
                return Status.REPLACED;
            }
            mob.problem(CompanionTexts.tr("handler.station.no_space", CompanionTexts.item(id)));
            return Status.FAILED;
        }
        if (!ctx.approach(spot, 9.0)) return Status.RUNNING;
        if (mob.getBoundingBox().intersects(new AABB(spot))) {
            mob.getNavigation().moveTo(spot.getX() + 3.5, spot.getY(), spot.getZ() + 0.5, 1.1);
            ctx.trackMove();
            return Status.RUNNING;
        }
        int slot = mob.findItem(item);
        if (slot < 0) {
            mob.logInfo(CompanionTexts.tr("handler.station.missing", CompanionTexts.item(id)));
            return Status.FAILED;
        }
        Block block = Block.byItem(item);
        BlockState st = block.defaultBlockState();
        if (!sl.getBlockState(spot).isAir()) sl.destroyBlock(spot, true, mob); // Platz in der Wand freimachen
        if (!mob.placeConfirmed(sl, spot, st)) {
            mob.logWarn(CompanionTexts.tr("handler.station.unconfirmed", CompanionTexts.item(id), spot.toShortString()));
            return Status.FAILED;
        }
        sl.playSound(null, spot, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 1f);
        mob.getInventory().getItem(slot).shrink(1);
        mob.swingArm();
        mob.rememberStation(id, spot);
        if (portable) mob.portableStations.add(spot.immutable());
        if (id.equals("minecraft:chest") && mob.getHome() == null) mob.setHome(spot);
        mob.logInfo(EventKind.CRAFT, CompanionTexts.tr("handler.station.placed", CompanionTexts.item(id), spot.toShortString()));
        return Status.DONE;
    }
}
