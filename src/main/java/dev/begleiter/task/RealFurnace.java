package dev.begleiter.task;

import java.util.UUID;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionMod;
import dev.begleiter.CompanionRecipes;
import dev.begleiter.CompanionTexts;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

/** One in-flight input per companion. Only Minecraft ticks cause production. */
public final class RealFurnace {
    private static final long LEASE_TICKS = 6000;
    private static AttachmentType<CompoundTag> operation;
    private static AttachmentType<CompoundTag> lease;

    private RealFurnace() {}

    public static void register() {
        operation = AttachmentRegistry.createPersistent(CompanionMod.id("furnace_operation"), CompoundTag.CODEC);
        lease = AttachmentRegistry.createPersistent(CompanionMod.id("furnace_lease"), CompoundTag.CODEC);
    }

    static CompoundTag pending(TaskContext ctx) { return ctx.mob().getAttached(operation); }

    static TaskHandler.Status resume(TaskContext ctx, CompoundTag pending, TaskSpec.Smelt task) {
        var level = ctx.level();
        var mob = ctx.mob();
        var pos = pending.read("Pos", BlockPos.CODEC).orElse(null);
        if (pos == null || !level.dimension().identifier().toString().equals(pending.getStringOr("Dimension", "")))
            return changed(ctx, null, pending);
        var furnace = level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof FurnaceBlockEntity f ? f : null;
        var token = pending.read("Token", UUIDUtil.CODEC).orElse(null);
        if (token == null || level.getGameTime() >= pending.getLongOr("Until", 0)
                || !itemKey(task).equals(pending.getStringOr("Task", "")) || task.count() != pending.getIntOr("Count", -1))
            return changed(ctx, furnace, pending);
        if (furnace == null) {
            if (level.hasChunkAt(pos)) return changed(ctx, null, pending);
            ctx.approach(pos, 6.25);
            return TaskHandler.Status.RUNNING;
        }
        var marker = furnace.getAttached(lease);
        if (marker == null || !marker.read("Token", UUIDUtil.CODEC).filter(token::equals).isPresent()
                || furnace.isLocked() || !mob.mayUse(level, pos) || someoneUsing(level, furnace))
            return changed(ctx, furnace, pending);
        if (!ctx.approach(pos, 6.25)) return TaskHandler.Status.RUNNING;
        var input = pending.read("Input", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        var result = pending.read("Result", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        var fuel = pending.read("Fuel", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        var output = furnace.getItem(2);
        var waitingInput = furnace.getItem(0);
        if (input.isEmpty() || result.isEmpty() || !furnace.getItem(1).isEmpty() && !matches(fuel, furnace.getItem(1)))
            return changed(ctx, furnace, pending);
        if (!output.isEmpty() && waitingInput.isEmpty() && matches(result, output)) {
            if (!fits(mob.getInventory(), output)) return TaskHandler.Status.RUNNING;
            var remainder = mob.giveToInventory(furnace.removeItem(2, result.getCount()));
            if (!remainder.isEmpty()) furnace.setItem(2, remainder);
            release(ctx, furnace, pending);
            return TaskHandler.Status.RUNNING;
        }
        if (!output.isEmpty() || !matches(input, waitingInput)) return changed(ctx, furnace, pending);
        if (!furnace.getBlockState().getValue(AbstractFurnaceBlock.LIT) && furnace.getItem(1).isEmpty()) {
            int slot = fuelSlot(ctx, furnace, -1);
            if (slot < 0) return changed(ctx, furnace, pending);
            loadFuel(ctx, furnace, pending, slot);
        }
        return TaskHandler.Status.RUNNING;
    }

    static TaskHandler.Status start(TaskContext ctx, BlockPos pos, Item target, TaskSpec.Smelt task) {
        var level = ctx.level();
        var mob = ctx.mob();
        if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace)
                || !furnace.isEmpty() || furnace.isLocked() || !mob.mayUse(level, pos) || someoneUsing(level, furnace)
                || furnace.getAttachedOrElse(lease, new CompoundTag()).getLongOr("Until", 0) > level.getGameTime()) {
            mob.problem(CompanionTexts.tr("handler.smelt.station_busy"));
            return TaskHandler.Status.FAILED;
        }
        var inventory = mob.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.isEmpty() || target == null && !stack.is(CompanionCraft.AUTO_SMELT)) continue;
            var result = CompanionRecipes.smeltResult(level, stack.copyWithCount(1));
            if (result.isEmpty() || target != null && !result.is(target)) continue;
            boolean lit = furnace.getBlockState().getValue(AbstractFurnaceBlock.LIT);
            int fuel = lit ? -1 : fuelSlot(ctx, furnace, stack.getCount() == 1 ? i : -1);
            if (fuel < 0 && !lit) {
                mob.problem(CompanionTexts.tr("handler.smelt.no_fuel"));
                return TaskHandler.Status.FAILED;
            }
            var marker = new CompoundTag();
            marker.store("Token", UUIDUtil.CODEC, UUID.randomUUID());
            marker.putLong("Until", level.getGameTime() + LEASE_TICKS);
            var data = marker.copy();
            data.store("Pos", BlockPos.CODEC, pos);
            data.putString("Dimension", level.dimension().identifier().toString());
            data.putString("Task", itemKey(task));
            data.putInt("Count", task.count());
            var input = stack.copyWithCount(1);
            data.store("Input", ItemStack.CODEC, input);
            data.store("Result", ItemStack.CODEC, result);
            furnace.setAttached(lease, marker);
            furnace.setItem(0, inventory.removeItem(i, 1));
            if (fuel >= 0) loadFuel(ctx, furnace, data, fuel);
            else mob.setAttached(operation, data);
            furnace.setChanged();
            return TaskHandler.Status.RUNNING;
        }
        return TaskHandler.Status.DONE;
    }

    private static String itemKey(TaskSpec.Smelt task) { return task.item() == null ? "" : task.item(); }

    private static boolean someoneUsing(ServerLevel level, FurnaceBlockEntity furnace) {
        return level.players().stream().anyMatch(player -> player.containerMenu instanceof AbstractFurnaceMenu menu
                && menu.getSlot(0).container == furnace);
    }

    private static int fuelSlot(TaskContext ctx, FurnaceBlockEntity furnace, int excluded) {
        var mob = ctx.mob();
        var inventory = mob.getInventory();
        String preferred = mob.orders.preferredOrderFuel();
        int coal = -1, wood = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (i == excluded) continue;
            var stack = inventory.getItem(i);
            if (stack.isEmpty() || stack.getItem().getCraftingRemainder() != null || !furnace.canPlaceItem(1, stack)) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (preferred != null && preferred.equals(id)) return i;
            if (mob.reservedForRecipe(stack)) continue;
            if (coal < 0 && (stack.is(Items.COAL) || stack.is(Items.CHARCOAL))) coal = i;
            if (wood < 0 && (stack.is(ItemTags.PLANKS) || stack.is(ItemTags.LOGS))) wood = i;
        }
        return coal >= 0 ? coal : wood;
    }

    private static void loadFuel(TaskContext ctx, FurnaceBlockEntity furnace, CompoundTag data, int slot) {
        var mob = ctx.mob();
        var fuel = mob.getInventory().removeItem(slot, 1);
        furnace.setItem(1, fuel);
        var updated = data.copy();
        updated.store("Fuel", ItemStack.OPTIONAL_CODEC, fuel.copy());
        mob.setAttached(operation, updated);
        furnace.setChanged();
    }

    private static boolean matches(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
    }

    private static boolean fits(net.minecraft.world.Container inventory, ItemStack output) {
        int remaining = output.getCount();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.isEmpty()) remaining -= output.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(output, stack))
                remaining -= Math.max(0, stack.getMaxStackSize() - stack.getCount());
            if (remaining <= 0) return true;
        }
        return false;
    }

    private static TaskHandler.Status changed(TaskContext ctx, FurnaceBlockEntity furnace, CompoundTag pending) {
        release(ctx, furnace, pending);
        ctx.mob().problem(CompanionTexts.tr("handler.smelt.changed"));
        return TaskHandler.Status.FAILED;
    }

    private static void release(TaskContext ctx, FurnaceBlockEntity furnace, CompoundTag pending) {
        ctx.mob().removeAttached(operation);
        if (furnace == null) return;
        var marker = furnace.getAttached(lease);
        if (marker != null && marker.contains("Token") && marker.get("Token").equals(pending.get("Token"))) {
            furnace.removeAttached(lease);
            furnace.setChanged();
        }
    }
}
