package dev.begleiter.task;

import dev.begleiter.CompanionEntity.Task;

/** MOVE_TO: zur Position laufen (Reichweite² 0 = 9). Erze am Ziel noch mitnehmen, dann weiter. */
final class MoveToHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.MoveTo move;

    MoveToHandler(Task task) {
        this.task = task;
        this.move = (TaskSpec.MoveTo) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (!ctx.approach(move.pos(), move.reachSqr() > 0 ? move.reachSqr() : 9.0)) return Status.RUNNING;
        // Erze stehen jetzt vorn; diese Aufgabe bleibt dahinter und gilt erst danach als erledigt
        return ctx.mob().duty() == null && ctx.grabPassingOres(task) ? Status.REPLACED : Status.DONE;
    }
}
