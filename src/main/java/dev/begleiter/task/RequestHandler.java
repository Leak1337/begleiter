package dev.begleiter.task;

import java.util.ArrayList;
import java.util.List;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionPlanner;
import dev.begleiter.CompanionTexts;
import dev.begleiter.ItemIds;
import net.minecraft.world.item.ItemStack;

/** Persistable checkpoints around existing acquisition/crafting tasks; exact inventory-to-owner transfer. */
final class RequestHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Request request;
    private boolean waiting;
    private int retryAt = -1;

    RequestHandler(Task task) { this.task = task; request = (TaskSpec.Request) task.spec(); }

    @Override public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        if (request.phase() == 3) {
            ctx.say(CompanionTexts.tr(request.materials() ? "request.done.materials" : "request.done.craft",
                    request.count(), CompanionTexts.item(request.item())));
            return Status.DONE;
        }
        if (request.phase() == 0) {
            var item = ItemIds.resolve(request.item());
            var plan = CompanionPlanner.request(mob, item, request.count(), request.materials());
            if (plan == null) return fail(ctx, "request.cannot_plan");
            List<Task> work = new ArrayList<>(plan.work().tasks());
            if (request.materials()) {
                for (var delivery : plan.delivery().entrySet()) work.add(new Task(new TaskSpec.Request(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(delivery.getKey()).toString(),
                        delivery.getValue(), true, 2, 0)));
            } else work.add(new Task(new TaskSpec.Request(request.item(), request.count(), false, 1,
                    CompanionCraft.count(mob, request.item()))));
            work.add(new Task(new TaskSpec.Request(request.item(), request.count(), request.materials(), 3, 0)));
            mob.logInfo(CompanionTexts.tr("request.plan", CompanionTexts.item(request.item()), CompanionTexts.plan(plan.work())));
            mob.replaceCurrent(work);
            return Status.REPLACED;
        }
        if (request.phase() == 4) {
            if (CompanionCraft.count(mob, request.item()) >= request.count()) return Status.DONE;
            ctx.capStuck(0);
            if (task.tries() >= 3) return fail(ctx, "request.retry_limit");
            if (retryAt < 0) retryAt = mob.tickCount + 40;
            if (mob.tickCount < retryAt) return Status.RUNNING;
            var plan = CompanionPlanner.refillRequest(mob, ItemIds.resolve(request.item()), request.count(), request.excludedSource());
            if (plan == null || plan.tasks().isEmpty()) return fail(ctx, "request.cannot_plan");
            List<Task> work = new ArrayList<>(plan.tasks());
            work.add(task.retry());
            mob.replaceCurrent(work);
            return Status.REPLACED;
        }
        int held = CompanionCraft.count(mob, request.item());
        int needed = request.count() + (request.phase() == 1 ? request.baseline() : 0);
        if (held < needed) return fail(ctx, "request.missing_delivery");
        var owner = mob.getOwner();
        if (owner == null) {
            ctx.capStuck(0);
            return Status.RUNNING;
        }
        if (!ctx.approachEntity(owner, 6.25)) return Status.RUNNING;
        int left = request.count();
        for (int slot = 0; slot < mob.getInventory().getContainerSize() && left > 0; slot++) {
            ItemStack stack = mob.getInventory().getItem(slot);
            if (!ItemIds.matches(stack, request.item())) continue;
            int amount = Math.min(left, stack.getCount());
            ItemStack transfer = stack.copyWithCount(amount);
            insert(owner.getInventory(), transfer);
            int accepted = amount - transfer.getCount();
            stack.shrink(accepted);
            left -= accepted;
        }
        if (left == 0) {
            ctx.say(CompanionTexts.tr("request.delivered", request.count(), CompanionTexts.item(request.item())));
            return Status.DONE;
        }
        ctx.capStuck(0);
        if (left != request.count()) {
            ctx.say(CompanionTexts.tr("request.delivered", request.count() - left, CompanionTexts.item(request.item())));
            mob.replaceCurrent(List.of(new Task(new TaskSpec.Request(request.item(), left, request.materials(), 2, 0))));
            return Status.REPLACED;
        }
        if (!waiting) { ctx.say(CompanionTexts.tr("request.owner_full")); waiting = true; }
        return Status.RUNNING;
    }

    /** Inventory.add verwirft bei Kreativspielern den Überlauf; jede nicht angenommene Einheit bleibt deshalb hier. */
    private static void insert(net.minecraft.world.entity.player.Inventory inventory, ItemStack input) {
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE && !input.isEmpty(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, input)) continue;
            int n = Math.min(input.getCount(), Math.max(0, stack.getMaxStackSize() - stack.getCount()));
            stack.grow(n); input.shrink(n);
        }
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE && !input.isEmpty(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) continue;
            int n = Math.min(input.getCount(), input.getMaxStackSize());
            inventory.setItem(slot, input.copyWithCount(n)); input.shrink(n);
        }
        inventory.setChanged();
    }

    private Status fail(TaskContext ctx, String message) {
        ctx.mob().problem(CompanionTexts.tr(message, CompanionTexts.item(request.item())));
        // Explicit resume gets a fresh bounded attempt window; never discard delivery/proof checkpoints.
        if (request.phase() == 4) ctx.mob().replaceCurrent(List.of(new Task(request)));
        ctx.mob().pauseRecipeRequest(CompanionTexts.tr("request.retained"));
        return Status.REPLACED;
    }
}
