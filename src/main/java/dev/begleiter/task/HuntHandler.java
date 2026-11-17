package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;

/**
 * HUNT: Tiere jagen, bis genug Essen da ist (Zielmenge, mindestens 2). Das Tier steht in mob.huntTarget, weil der
 * Kampf selbst das Aufgabenziel unterbricht; nach jedem erlegten Tier erst einsammeln, dann weiter (tries zählt Runden).
 */
final class HuntHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Hunt hunt;

    HuntHandler(Task task) {
        this.task = task;
        this.hunt = (TaskSpec.Hunt) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        int goal = Math.max(2, hunt.food());
        Mob h = mob.huntTarget;
        if (h != null && h.isAlive()) {
            mob.setTarget(h);
            return Status.RUNNING;
        }
        if (h == null && mob.foodCount() >= goal) return finish(mob, Status.DONE);
        if (h != null) { // erlegt (oder verschwunden): Beute einsammeln, dann weiter
            mob.huntTarget = null;
            mob.replaceCurrent(List.of(Task.collect(false),
                    new Task(new TaskSpec.Hunt(goal), task.tries() + 1)));
            return Status.REPLACED;
        }
        if (task.tries() >= 6) {
            mob.problem(CompanionTexts.tr("handler.hunt.unreachable"));
            return finish(mob, Status.FAILED);
        }
        Mob nearest = mob.nearestFoodAnimal();
        if (nearest == null) {
            mob.logInfo(CompanionTexts.tr("handler.hunt.none"));
            return finish(mob, Status.DONE);
        }
        if (walkCloser(ctx, nearest)) return Status.RUNNING;
        mob.huntTarget = nearest;
        mob.setTarget(nearest);
        mob.logInfo(CompanionTexts.tr("handler.hunt.target", nearest.getName(), nearest.blockPosition().toShortString()));
        return Status.RUNNING;
    }

    /** Jagd vorbei: mit Ofen in der Nähe das rohe Fleisch gleich braten. */
    private static Status finish(CompanionEntity mob, Status status) {
        if (mob.findStation("furnace", 24) == null || !CompanionCraft.hasSmeltable(mob)) return status;
        mob.replaceCurrent(List.of(Task.smeltAnything()));
        return Status.REPLACED;
    }

    /**
     * Weit entferntes Tier/Monster: erst hinlaufen, dann angreifen – sonst wird das Ziel sofort wieder fallen gelassen
     * (Ziele über 24 Blöcke) und er bleibt stehen. true = läuft noch.
     */
    static boolean walkCloser(TaskContext ctx, Mob m) {
        if (ctx.mob().distanceToSqr(m) <= 14 * 14) return false;
        ctx.approach(m.blockPosition(), 10 * 10);
        return true;
    }
}
