package dev.begleiter;

import java.util.ArrayList;
import java.util.List;

import dev.begleiter.ai.DecisionOutcome;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Begrenzte Prüfungen vor dem Aufbruch. Liest Inventar und erinnerte Gefahren, nie ferne Weltblöcke. */
public final class CompanionTripPreparation {
    private CompanionTripPreparation() { }
    public record Check(List<Component> issues, DecisionOutcome.Reason reason) {
        public Check { issues = List.copyOf(issues); }
        public boolean ready() { return issues.isEmpty(); }
        public Component message() { return CompanionTexts.tr("trip.blocked", CompanionTexts.join(issues, "; ")); }
    }

    public static Check check(CompanionEntity mob, BlockPos target) {
        if (mob.creativeHelper() || target.distSqr(mob.blockPosition()) <= 64 * 64) return new Check(List.of(), null);
        List<Component> issues = new ArrayList<>();
        DecisionOutcome.Reason reason = null;
        int food = 0, empty = 0;
        for (int i = 0; i < mob.getInventory().getContainerSize(); i++) {
            var stack = mob.getInventory().getItem(i);
            if (stack.isEmpty()) empty++;
            else if (stack.has(DataComponents.FOOD) && !mob.reservedForRecipe(stack)) food += stack.getCount();
        }
        if (mob.hunger < 12 || food < 4) {
            issues.add(CompanionTexts.tr("trip.food", mob.hunger, food));
            reason = DecisionOutcome.Reason.MISSING_INPUT;
        }
        if (empty < 2) {
            issues.add(CompanionTexts.tr("trip.space", empty));
            reason = DecisionOutcome.Reason.INVENTORY_FULL;
        }
        int inspected = 0;
        for (var task : mob.peekTasks(64)) {
            if (++inspected > 64) break;
            // Eine Holztour nicht blockieren: die Warteschlange stellt danach die erste Spitzhacke her.
            if (task.spec() instanceof TaskSpec.Craft craft && CompanionPrerequisites.pickTier(ItemIds.resolve(craft.item())) > 0
                    || task.spec() instanceof TaskSpec.Process process && CompanionPrerequisites.pickTier(ItemIds.resolve(process.item())) > 0)
                break;
            if (task.spec() instanceof TaskSpec.ResourceGoal resource) {
                var res = Resources.byKey(resource.resource());
                int units = resource.targetTotal() > 0 && res != null
                        ? Math.max(0, resource.targetTotal() - Resources.count(mob, res)) : Math.max(0, resource.count());
                if (units > 0 && !CompanionPrerequisites.readyTool(mob, resource.resource(), units)) {
                    issues.add(CompanionTexts.tr("trip.tool", CompanionTexts.resource(resource.resource())));
                    if (reason == null) reason = DecisionOutcome.Reason.MISSING_TOOL;
                }
                break; // This trip's next resource goal; later goals have their own prerequisites.
            }
        }
        if (mob.level() instanceof ServerLevel world) {
            BlockPos back = mob.getHome() == null ? mob.blockPosition() : mob.getHome();
            if (mob.memory.dangerous(world, target, 8) || mob.memory.dangerous(world, back, 8)) {
                issues.add(CompanionTexts.tr("trip.return_danger"));
                if (reason == null) reason = DecisionOutcome.Reason.ROUTE_UNPROVEN;
            }
        }
        return new Check(issues, reason);
    }
}
