package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionJobs;
import dev.begleiter.CompanionPlanner;

/**
 * GEARUP: Ausrüstungsziel (Stufe, zuletzt angegangenes Teil, Versuche daran, Bitmaske übersprungener Teile; tries = Runde). Besorgt das
 * nächste fehlende Stück über den Zielplaner und stellt sich selbst wieder dahinter; nach 40 Runden ist Schluss.
 */
final class GearUpHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.GearUp gear;

    GearUpHandler(Task task) {
        this.task = task;
        this.gear = (TaskSpec.GearUp) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        int tier = gear.tier();
        int skip = gear.skipMask();
        Component label = CompanionTexts.tier(tier);
        if (task.tries() >= 40) {
            ctx.say(CompanionTexts.tr("jobs.gear.stop", CompanionJobs.gearProgress(mob, tier)));
            return Status.FAILED;
        }
        String next = CompanionJobs.nextGearPiece(mob, tier, skip);
        if (next == null) {
            ctx.say(skip == 0 ? CompanionTexts.tr("jobs.gear.done", label)
                    : CompanionTexts.tr("jobs.gear.partial", CompanionJobs.gearProgress(mob, tier), label));
            return Status.DONE;
        }
        int attempts = dev.begleiter.ItemIds.canonical(next).equals(gear.lastPiece()) ? gear.attempts() + 1 : 0;
        Item item = CompanionCraft.item(next);
        Component name = new ItemStack(item).getHoverName();
        CompanionPlanner.Plan plan = attempts >= 2 || item == Items.AIR ? null : CompanionPlanner.obtain(mob, item, 1);
        if (plan == null) {
            int idx = CompanionJobs.gearIndex(next);
            ctx.say(CompanionTexts.tr("jobs.gear.unavailable", name));
            mob.replaceCurrent(List.of(CompanionJobs.gearTask(tier, skip | (idx < 0 ? 0 : 1 << idx), null, 0, task.tries() + 1)));
            return Status.REPLACED;
        }
        List<Task> steps = plan.tasks();
        Component how = steps.isEmpty() ? Component.empty() : CompanionTexts.tr("jobs.gear.how",
                CompanionTexts.join(steps.stream().limit(4).map(CompanionTexts::task).toList(), " → "),
                steps.size() > 4 ? " …" : "");
        ctx.say(CompanionTexts.tr("jobs.gear.obtain", CompanionJobs.gearProgress(mob, tier), name, how));
        List<Task> list = new ArrayList<>(plan.tasks());
        list.add(CompanionJobs.gearTask(tier, skip, next, attempts, task.tries() + 1));
        mob.replaceCurrent(list);
        return Status.REPLACED;
    }
}
