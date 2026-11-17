package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionJobs;

/** REPAIR: gleiche Werkzeuge zusammenlegen, am Amboss mit Material reparieren (unter belowPercent % Haltbarkeit, 0 = 90 %). */
final class RepairHandler implements TaskHandler {
    private final TaskSpec.Repair repair;
    private int progress;
    /** Schon zusammengelegt (einmal pro Aufgabe) und wie viele. */
    private boolean merged;
    private int mergedCount;

    RepairHandler(Task task) {
        this.repair = (TaskSpec.Repair) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        double below = (repair.belowPercent() > 0 ? repair.belowPercent() : 90) / 100.0;
        if (!merged) {
            if (++progress < 20) return Status.RUNNING;
            mergedCount = CompanionJobs.mergeDuplicates(mob);
            merged = true;
        }
        List<Component> fixed = List.of();
        boolean noAnvil = false;
        if (CompanionJobs.hasRepairable(mob, below)) {
            BlockPos anvil = CompanionJobs.findAnvil(mob, 24);
            if (anvil == null) noAnvil = true;
            else {
                if (!ctx.approach(anvil, 9.0)) return Status.RUNNING;
                mob.getLookControl().setLookAt(Vec3.atCenterOf(anvil));
                mob.swingArm();
                fixed = CompanionJobs.repairAtAnvilTexts(ctx.level(), mob, anvil, below);
            }
        }
        List<Component> report = new java.util.ArrayList<>();
        if (mergedCount > 0) report.add(CompanionTexts.tr("jobs.repair.merged", mergedCount));
        if (!fixed.isEmpty()) report.add(CompanionTexts.tr("jobs.repair.anvil", CompanionTexts.join(fixed, ", ")));
        if (!report.isEmpty()) {
            ctx.say(CompanionTexts.tr("jobs.repair.done", CompanionTexts.join(report, ", ")));
            return Status.DONE;
        }
        // nichts getan: 5 min nicht wieder als Pflege-Ziel nehmen
        mob.craftFailedUntil.put("#repair", mob.tickCount + 6000);
        if (CompanionJobs.damaged(mob, below).isEmpty()) {
            ctx.say(CompanionTexts.tr("jobs.repair.ok"));
            return Status.DONE;
        }
        ctx.say(noAnvil ? CompanionTexts.tr("jobs.repair.no_anvil")
                : CompanionTexts.tr("jobs.repair.no_material"));
        return Status.FAILED;
    }
}
