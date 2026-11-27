package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.Resources;

/**
 * TREE: einen Baum (pos) komplett fällen. Stämme in Reichweite abbauen, sonst näher ran oder mit Gerüst hochbauen; am
 * Ende das Gerüst wieder abbauen (DESCEND).
 */
final class TreeHandler implements TaskHandler {
    private static final double LOG_REACH_SQR = 4.3 * 4.3;

    private final Task task;
    /** Stämme des Baums, alle 10 Ticks neu gelesen; null = noch nie (kein Tick-Sentinel: tickCount - MIN_VALUE läuft über). */
    private List<BlockPos> treeCache;
    private int treeCacheTick;

    TreeHandler(Task task) {
        this.task = task;
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        if (mob.isPillarActive()) {
            mob.pillarStep(sl, CompanionEntity.Pillar.TREE);
            return Status.RUNNING;
        }
        if (treeCache == null || mob.tickCount - treeCacheTick >= 10) {
            treeCache = mob.gathering.treeLogs(sl, task.pos());
            treeCacheTick = mob.tickCount;
        }
        List<BlockPos> logs = new ArrayList<>();
        for (BlockPos p : treeCache) {
            BlockState state = sl.getBlockState(p);
            if (state.is(BlockTags.LOGS) && mob.storage.harvestAllowed(sl, p, state) && !mob.hazardAround(sl, p, true)) logs.add(p);
        }
        if (logs.isEmpty()) {
            if (!mob.hasScaffold()) return Status.DONE;
            mob.replaceCurrent(List.of(Task.descend()));
            return Status.REPLACED;
        }
        Vec3 eye = mob.getEyePosition();
        BlockPos best = null;
        double bd = LOG_REACH_SQR;
        for (BlockPos p : logs) {
            double d = eye.distanceToSqr(Vec3.atCenterOf(p));
            if (d < bd) { bd = d; best = p; }
        }
        if (best != null) {
            mob.pushFront(Task.breakAt(best, sl.getBlockState(best).getBlock()));
            return Status.REPLACED;
        }
        BlockPos low = logs.get(0);
        for (BlockPos p : logs) if (p.getY() < low.getY()) low = p;
        BlockPos column = new BlockPos(low.getX(), mob.blockPosition().getY(), low.getZ());
        // Wegfindung "angekommen" zählt: ihr Ziel rechnet in Blockkoordinaten, der Abstand hier zur Blockmitte – sonst
        // steht er knapp daneben, bis die Stillstand-Erkennung den Baum überspringt
        if (mob.distanceToSqr(Vec3.atCenterOf(column)) > 6.25 && !ctx.approach(column, 6.25)) return Status.RUNNING;
        // am Stamm, aber höher gelegene Stämme nicht erreichbar -> hochbauen (erst Erde besorgen, falls nötig – auch wenn
        // er nur Blöcke hat, die er ohne Spitzhacke nicht wieder abbauen kann)
        if (mob.scaffoldItem(CompanionEntity.Pillar.TREE) == null && task.tries() < 2) {
            mob.logInfo(CompanionTexts.tr("handler.tree.need_dirt"));
            mob.replaceCurrent(List.of(Task.gather(Resources.DIRT.key(), 6),
                    task.retry()));
            return Status.REPLACED;
        }
        return climbStep(mob, sl);
    }

    /**
     * Ein Schritt Säule zum Baum/hohen Ziel (wird danach wieder abgebaut). FAILED = geht nicht (Problem gemeldet),
     * REPLACED = Hindernis über dem Kopf wird zuerst abgebaut.
     */
    static Status climbStep(CompanionEntity mob, ServerLevel sl) {
        if (mob.scaffoldItem(CompanionEntity.Pillar.TREE) == null) {
            mob.problem(CompanionTexts.tr("handler.tree.no_blocks"));
            return Status.FAILED;
        }
        int r = mob.pillarStep(sl, CompanionEntity.Pillar.TREE);
        if (r == -1) {
            mob.problem(CompanionTexts.tr("handler.tree.cannot_climb"));
            return Status.FAILED;
        }
        if (r == 2 && mob.scaffolding.obstacle() != null) {
            BlockState st = sl.getBlockState(mob.scaffolding.obstacle());
            mob.pushFront(Task.breakAt(mob.scaffolding.obstacle(), st.getBlock()));
            return Status.REPLACED;
        }
        return Status.RUNNING;
    }
}
