package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import net.minecraft.world.entity.player.Player;

import dev.begleiter.CompanionEntity.Task;

/** GIVE: zum Besitzer laufen und ihm geben, was {@link TaskSpec.Give} nennt. */
final class GiveHandler implements TaskHandler {
    private final TaskSpec.Give give;

    GiveHandler(Task task) {
        this.give = (TaskSpec.Give) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        Player owner = ctx.mob().getOwner();
        if (owner == null) {
            ctx.mob().logInfo(CompanionTexts.tr("handler.give.no_owner"));
            return Status.FAILED;
        }
        if (!ctx.approachEntity(owner, 6.25)) return Status.RUNNING;
        // giveTo sagt selbst "Davon habe ich nichts dabei."
        int given = ctx.mob().giveTo(owner, give.what(), give.max() > 0 ? give.max() : Integer.MAX_VALUE);
        return given > 0 ? Status.DONE : Status.FAILED;
    }
}
