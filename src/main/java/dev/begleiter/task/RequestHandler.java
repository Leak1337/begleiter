package dev.begleiter.task;

import java.util.ArrayList;
import java.util.List;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionPlanner;
import dev.begleiter.CompanionTexts;
import dev.begleiter.CompanionTripPreparation;
import dev.begleiter.ItemIds;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Persistable checkpoints around existing acquisition/crafting tasks; exact inventory-to-owner transfer. */
final class RequestHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Request request;
    private boolean waiting;
    private int retryAt = -1;

    RequestHandler(Task task) { this.task = task; request = (TaskSpec.Request) task.spec(); }

    @Override public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        if (request.homeOrder()) return tickHomeOrder(ctx);
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
        if (held < needed) {
            if (request.phase() == 1 && mob.recoverRecipeProduction(
                    CompanionTexts.tr("request.missing_delivery", CompanionTexts.item(request.item())))) return Status.REPLACED;
            return fail(ctx, "request.missing_delivery");
        }
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
            mob.replaceCurrent(List.of(new Task(new TaskSpec.Request(request.item(), left, request.materials(),
                    request.phase(), request.baseline()), task.tries())));
            return Status.REPLACED;
        }
        if (!waiting) { ctx.say(CompanionTexts.tr("request.owner_full")); waiting = true; }
        return Status.RUNNING;
    }

    private Status tickHomeOrder(TaskContext ctx) {
        var mob = ctx.mob();
        var home = request.pos();
        var name = CompanionTexts.item(request.item());
        if (!home.equals(mob.getHome())) return fail(ctx, "order.home_changed");
        if (mob.memory.dangerous(ctx.level(), home, 8)) return fail(ctx, "order.home_inaccessible");
        if (!ctx.approach(home, 12.25)) return Status.RUNNING;
        var box = CompanionTripPreparation.homeOrderStorage(mob, ctx.level(), home);
        if (box == null) return fail(ctx, "order.home_inaccessible");
        var item = ItemIds.resolve(request.item());
        int before = box.countItem(item);
        int missing = request.count() - before;
        if (missing <= 0) {
            ctx.say(CompanionTexts.tr("order.home_done", request.count(), name));
            return Status.DONE;
        }
        var inventory = mob.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && missing > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(item)) continue;
            int amount = Math.min(missing, stack.getCount());
            int moved = amount - HopperBlockEntity.addItem(null, box, stack.copyWithCount(amount), null).getCount();
            stack.shrink(moved);
            missing -= moved;
        }
        if (missing < request.count() - before) {
            inventory.setChanged();
            box.setChanged();
            mob.useContainer(ctx.level(), home);
            mob.memory.noteChest(ctx.level(), home, box);
        }
        if (missing <= 0) {
            ctx.say(CompanionTexts.tr("order.home_done", request.count(), name));
            return Status.DONE;
        }
        if (inventory.countItem(item) > 0) return fail(ctx, "order.home_full");
        if (task.tries() >= 3) return fail(ctx, "request.retry_limit");
        // Sized batches leave room for intermediate inputs and make partial deliveries durable.
        var plan = CompanionPlanner.refillHomeOrder(mob, item, Math.min(missing, 64), home, request.fuel());
        if (plan == null || plan.tasks().isEmpty()) return fail(ctx, "request.cannot_plan");
        var work = new ArrayList<>(plan.tasks());
        var extraPickup = CompanionPlanner.bundleHomePickup(mob, ctx.level(), plan, request, box);
        if (extraPickup != null) work.add(extraPickup);
        work.add(new Task(new TaskSpec.Request(request.item(), request.count(), false, 6,
                request.count() - missing, null, home, request.fuel()), missing < request.count() - request.baseline() ? 0 : task.tries() + 1));
        mob.logInfo(CompanionTexts.tr("request.plan", name, CompanionTexts.plan(plan)));
        mob.replaceCurrent(work);
        return Status.REPLACED;
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
        var reason = CompanionTexts.tr(message, CompanionTexts.item(request.item()));
        ctx.mob().problem(reason);
        // Explicit resume gets a fresh bounded attempt window; never discard delivery/proof checkpoints.
        if (request.phase() == 4) ctx.mob().replaceCurrent(List.of(new Task(request)));
        ctx.mob().pauseRecipeRequest(request.homeOrder() ? reason : CompanionTexts.tr("request.retained"));
        return Status.REPLACED;
    }
}
