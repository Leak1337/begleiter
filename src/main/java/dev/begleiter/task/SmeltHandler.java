package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.CompanionRecipes;
import dev.begleiter.Resources;
import dev.begleiter.ai.DecisionOutcome;

/**
 * SMELT: am Ofen schmelzen/kochen, ein Item alle 3 s. Ergebnis-Item mit gewünschter Gesamtzahl (gezielt, z.B. Glas
 * für einen Bau); ohne Item: alles Schmelzbare. Ofen dabei (oder 8 Bruchstein): hier aufstellen statt weit zu laufen.
 */
final class SmeltHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Smelt smelt;
    private int progress;
    /** Geschmolzene Stücke in dieser Aufgabe (für die Fortschrittszeile; sonst wirkt langes Schmelzen wie ein Hänger). */
    private int smelted;

    SmeltHandler(Task task) {
        this.task = task;
        this.smelt = (TaskSpec.Smelt) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        var pending = RealFurnace.pending(ctx);
        if (pending != null) return RealFurnace.resume(ctx, pending, smelt);
        boolean real = dev.begleiter.config.BegleiterConfig.get().realFurnaces;
        Item target = smelt.item() == null ? null : CompanionCraft.item(smelt.item());
        // Unter allen Ofen-Rezepten für das Ergebnis das, dessen Zutat er dabei hat (Rohkupfer statt Kupfererz ...)
        CompanionRecipes.Smelt recipe = null;
        if (target != null) {
            for (CompanionRecipes.Smelt r : CompanionRecipes.smeltsFor(ctx.level(), target)) {
                if (recipe == null) recipe = r;
                if (CompanionRecipes.have(mob, r.input()) > 0) { recipe = r; break; }
            }
        }
        if (ctx.guardedSupply() && target != null && CompanionCraft.count(mob, smelt.item()) < smelt.count()) {
            if (recipe == null || CompanionRecipes.have(mob, recipe.input()) == 0) {
                ctx.abortAutonomousPlan(DecisionOutcome.Reason.MISSING_INPUT);
                return Status.REPLACED;
            }
            if (!real && !CompanionCraft.hasFuel(mob)) {
                ctx.abortAutonomousPlan(DecisionOutcome.Reason.MISSING_FUEL);
                return Status.REPLACED;
            }
        }
        // fertig: Ziel erreicht oder nichts (mehr) zu schmelzen
        if (target != null && (recipe == null || CompanionCraft.count(mob, smelt.item()) >= smelt.count()
                || CompanionRecipes.have(mob, recipe.input()) == 0)) return Status.DONE;
        if (target == null && !CompanionCraft.hasSmeltable(mob)) return Status.DONE;
        BlockPos furnace = !real && mob.hasRecipeRequest() ? mob.findOrderStation("furnace", 24) : mob.findStation("furnace", 24);
        if (ctx.guardedSupply() && furnace == null) {
            ctx.abortAutonomousPlan(DecisionOutcome.Reason.MISSING_STATION);
            return Status.REPLACED;
        }
        if (furnace == null && mob.recoverRecipeProduction(
                CompanionTexts.tr("handler.smelt.no_furnace"))) return Status.REPLACED;
        if ((furnace == null || furnace.distSqr(mob.blockPosition()) > 12 * 12) && task.tries() < 2
                && !ctx.guardedSupply() && CompanionCraft.tier(mob, "pickaxe") > 0 && mob.canCarryStation("furnace")) {
            mob.replaceCurrent(List.of(Task.station("furnace", true),
                    task.retry()));
            return Status.REPLACED;
        }
        if (furnace == null) {
            if (CompanionCraft.tier(mob, "pickaxe") == 0) {
                mob.logInfo(CompanionTexts.tr("handler.smelt.no_pickaxe"));
                return Status.FAILED;
            }
            if (task.tries() >= 2) {
                mob.problem(CompanionTexts.tr("handler.smelt.no_furnace"));
                return Status.FAILED;
            }
            List<Task> pre = new ArrayList<>();
            if (CompanionCraft.count(mob, "#stone") < 8)
                pre.add(Task.gather(Resources.STONE.key(), 10));
            pre.add(Task.station("furnace", false));
            pre.add(task.retry());
            mob.replaceCurrent(pre);
            return Status.REPLACED;
        }
        if (!ctx.approach(furnace, 6.25)) return Status.RUNNING;
        if (real) return RealFurnace.start(ctx, furnace, target, smelt);
        boolean fuel = target != null ? CompanionCraft.hasFuel(mob) : CompanionCraft.canSmelt(mob);
        if (!fuel) {
            mob.problem(CompanionTexts.tr("handler.smelt.no_fuel"));
            return Status.FAILED;
        }
        mob.getLookControl().setLookAt(Vec3.atCenterOf(furnace));
        if (progress % 20 == 0) mob.useContainer(ctx.level(), furnace); // sichtbar am Ofen arbeiten
        if (++progress >= 60) {
            if (target != null) CompanionCraft.smeltTarget(mob, target, recipe.input());
            else CompanionCraft.smeltStep(mob, 1);
            progress = 0;
            // etwa alle 15 s ein Lebenszeichen in Log und Debug-Anzeige
            if (++smelted % 5 == 0) mob.logInfo(CompanionTexts.tr("handler.smelt.progress", smelted, target != null ? CompanionTexts.tr("handler.smelt.target", CompanionCraft.count(mob, smelt.item()), smelt.count(), CompanionTexts.item(smelt.item())) : Component.empty()));
        }
        return Status.RUNNING;
    }
}
