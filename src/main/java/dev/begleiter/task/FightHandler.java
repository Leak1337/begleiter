package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import dev.begleiter.debug.BotDebug.EventKind;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.Mob;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;

/**
 * FIGHT: Mobs einer Art bekämpfen, bis genug Beute da ist (Art, Beute und Menge, optionaler Sammelpunkt wie
 * ein Spawner). Ist keiner da, wartet er am Sammelpunkt bzw. sieht sich um (tries zählt Runden).
 */
final class FightHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Fight fight;
    /** Wartezeit ohne Gegner, bevor er woanders sucht. */
    private int waited;

    FightHandler(Task task) {
        this.task = task;
        this.fight = (TaskSpec.Fight) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        String id = fight.mob();
        String loot = fight.lootItem();
        if (loot != null && CompanionCraft.count(mob, loot) >= fight.lootCount()) {
            mob.huntTarget = null;
            return Status.DONE;
        }
        Mob h = mob.huntTarget;
        if (h != null && h.isAlive()) {
            mob.setTarget(h);
            return Status.RUNNING;
        }
        if (h != null) { // erlegt: Beute einsammeln, weiter kämpfen
            mob.huntTarget = null;
            mob.replaceCurrent(List.of(Task.collect(true),
                    new Task(fight)));
            return Status.REPLACED;
        }
        boolean dragon = id.equals("ender_dragon");
        Mob next = mob.nearestOfType(id, dragon ? 200 : 32, fight.pos());
        if (next != null && !dragon && HuntHandler.walkCloser(ctx, next)) return Status.RUNNING;
        if (next != null) {
            mob.huntTarget = next;
            mob.setTarget(next);
            mob.logInfo(EventKind.DANGER, CompanionTexts.tr("handler.fight.target", next.getName(), next.blockPosition().toShortString()));
            return Status.RUNNING;
        }
        // keiner da: am Sammelpunkt warten bzw. umsehen
        if (fight.pos() != null && !ctx.approach(fight.pos(), 25)) return Status.RUNNING;
        if (++waited < 100) return Status.RUNNING;
        if (task.tries() >= 12) {
            mob.logInfo(CompanionTexts.tr("handler.fight.none", CompanionTexts.animal(id)));
            return Status.FAILED;
        }
        Task ex = fight.pos() == null ? mob.explorePoint() : null;
        List<Task> next2 = new ArrayList<>();
        if (ex != null) next2.add(ex);
        next2.add(task.retry());
        mob.replaceCurrent(next2);
        return Status.REPLACED;
    }
}
