package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.CompanionPlanner;
import dev.begleiter.ai.DecisionOutcome;

/**
 * CRAFT: Item herstellen (Anzahl; "#planks_from_logs" = Stämme zu Brettern). Braucht das Rezept eine Werkbank, stellt er
 * eine mitgebrachte in der Nähe auf, statt weit zu laufen; fehlende Zutaten besorgt er über den Planer (tries zählt mit).
 */
final class CraftHandler implements TaskHandler {
    private static final String PLANKS_FROM_LOGS = "#planks_from_logs";

    private final Task task;
    private final TaskSpec.Craft craft;
    private int progress;

    CraftHandler(Task task) {
        this.task = task;
        this.craft = (TaskSpec.Craft) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        String id = craft.item();
        if (!PLANKS_FROM_LOGS.equals(id) && CompanionCraft.needsTable(mob, id)) {
            BlockPos table = mob.findStation("crafting_table", 24);
            if (ctx.guardedSupply() && table == null) {
                ctx.abortAutonomousPlan(DecisionOutcome.Reason.MISSING_STATION);
                return Status.REPLACED;
            }
            if (table == null && mob.recoverRecipeProduction(
                    CompanionTexts.tr("handler.craft.no_table", CompanionTexts.item(id)))) return Status.REPLACED;
            // Wie ein Spieler: Werkbank dabei (oder schnell gemacht) -> hier aufstellen statt weit zurückzulaufen
            boolean far = table == null || table.distSqr(mob.blockPosition()) > 12 * 12;
            if (far && !ctx.guardedSupply() && task.tries() < 4 && mob.canCarryStation("crafting_table")) {
                mob.replaceCurrent(List.of(Task.station("crafting_table", true),
                        task.retry()));
                return Status.REPLACED;
            }
            if (table == null) {
                if (task.tries() >= 4) { // Zutaten besorgen zählt mit hoch: genug Versuche für Werkbank danach lassen
                    mob.problem(CompanionTexts.tr("handler.craft.no_table", CompanionTexts.item(id)));
                    mob.reportAutonomousFailure(DecisionOutcome.Reason.MISSING_STATION, task);
                    mob.plannerCooldownUntil = mob.tickCount + 200;
                    return Status.FAILED;
                }
                mob.replaceCurrent(List.of(Task.station("crafting_table", false),
                        task.retry()));
                return Status.REPLACED;
            }
            if (!ctx.approach(table, 9.0)) return Status.RUNNING;
        }
        if (++progress < 20) return Status.RUNNING;
        if (PLANKS_FROM_LOGS.equals(id)) {
            if (ctx.guardedSupply() && CompanionCraft.count(mob, "#logs") * 4 < craft.count()) {
                ctx.abortAutonomousPlan(DecisionOutcome.Reason.MISSING_INPUT);
                return Status.REPLACED;
            }
            CompanionCraft.convertLogsToPlanks(mob, ctx.guardedSupply() ? craft.count() : Integer.MAX_VALUE);
            return Status.DONE;
        }
        boolean crafted = mob.hasRecipeRequest()
                ? dev.begleiter.CompanionRecipes.craft(mob, ctx.level(), CompanionCraft.item(id), Math.max(1, craft.count()))
                : CompanionCraft.craft(mob, id, Math.max(1, craft.count()));
        if (crafted) return Status.DONE;
        if (ctx.guardedSupply()) {
            ctx.abortAutonomousPlan(DecisionOutcome.Reason.MISSING_INPUT);
            return Status.REPLACED;
        }
        // Zutat fehlt (z.B. Bretter/Stöcke): erst besorgen lassen, dann nochmal – wie ein Spieler
        CompanionPlanner.Plan plan = null;
        if (task.tries() < 3) {
            if (mob.hasRecipeRequest()) {
                var requestPlan = CompanionPlanner.request(mob, CompanionCraft.item(id), Math.max(1, craft.count()), false);
                if (requestPlan != null) plan = requestPlan.work();
            } else plan = CompanionPlanner.obtain(mob, CompanionCraft.item(id), Math.max(1, craft.count()));
        }
        if (plan != null && !plan.tasks().isEmpty()) {
            mob.logInfo(CompanionTexts.tr("handler.craft.plan", CompanionTexts.item(id), CompanionTexts.plan(plan)));
            List<Task> pre = new ArrayList<>();
            boolean again = false;
            for (Task q : plan.tasks()) {
                boolean self = q.spec() instanceof TaskSpec.Craft c && id.equals(c.item());
                again |= self;
                pre.add(self ? new Task(q.spec(), task.tries() + 1) : q);
            }
            if (again) { // nur mit gezähltem Wiederholungsversuch, sonst Endlosschleife
                mob.replaceCurrent(pre);
                return Status.REPLACED;
            }
        }
        mob.problem(CompanionTexts.tr("handler.craft.failed", CompanionTexts.item(id), mob.craftProblemComponent()));
        mob.reportAutonomousFailure(DecisionOutcome.Reason.MISSING_INPUT, task);
        mob.plannerCooldownUntil = mob.tickCount + 200;
        mob.craftFailedUntil.put(id, mob.tickCount + 6000); // 5 min nicht wieder als Ziel nehmen
        return Status.FAILED;
    }
}
