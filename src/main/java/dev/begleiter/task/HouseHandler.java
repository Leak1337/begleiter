package dev.begleiter.task;

import dev.begleiter.CompanionEntity.Task;

/**
 * HOUSE / EXTEND: Haus bzw. Anbau planen. Die Entity baut dabei selbst die Warteschlange um (Material holen, PLACE-
 * Aufgaben einreihen, bei Fehlschlag sich entfernen) – der Handler meldet deshalb immer REPLACED.
 */
final class HouseHandler implements TaskHandler {
    private final Task task;
    private final boolean extend;

    HouseHandler(Task task, boolean extend) {
        this.task = task;
        this.extend = extend;
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (extend) ctx.mob().extendStep(task);
        else ctx.mob().houseStep(task);
        return Status.REPLACED;
    }
}
