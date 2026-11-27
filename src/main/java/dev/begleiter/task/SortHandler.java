package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionJobs;

/** SORT: Kisten um pos nach Sorten aufräumen. */
final class SortHandler implements TaskHandler {
    private final Task task;

    SortHandler(Task task) {
        this.task = task;
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (!ctx.approach(task.pos(), 12.25)) return Status.RUNNING;
        ctx.mob().useContainer(ctx.level(), task.pos());
        var r = CompanionJobs.sortByCategory(ctx.mob(), ctx.level(), task.pos());
        if (r.boxes() == 0) {
            ctx.say(CompanionTexts.tr("jobs.sort.none"));
            return Status.FAILED;
        }
        if (r.failed()) {
            ctx.say(r.failure());
            return Status.FAILED;
        }
        if (ctx.mob().duty() != null) ctx.mob().dutyStorageFingerprint = dev.begleiter.CompanionDuties.storageFingerprint(ctx.mob(), ctx.level(), task.pos());
        ctx.mob().work.progress(1);
        ctx.say(CompanionTexts.tr("jobs.sort.done", r.boxes(), CompanionTexts.join(r.lineTexts(), "; ")));
        return Status.DONE;
    }
}
