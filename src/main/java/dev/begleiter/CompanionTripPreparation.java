package dev.begleiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.begleiter.ai.DecisionOutcome;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Begrenzte Prüfungen vor dem Aufbruch. Liest Inventar und erinnerte Gefahren, nie ferne Weltblöcke. */
public final class CompanionTripPreparation {
    private static final int MIN_HUNGER = 12;
    private static final int FOOD_RESERVE = 4;
    private static final int FREE_SLOTS = 2;

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
        int requiredFood = foodReserve(mob, target);
        if (mob.food.level() < MIN_HUNGER || food < requiredFood) {
            issues.add(CompanionTexts.tr("trip.food", mob.food.level(), food, requiredFood));
            reason = DecisionOutcome.Reason.MISSING_INPUT;
        }
        if (empty < FREE_SLOTS) {
            issues.add(CompanionTexts.tr("trip.space", empty));
            reason = DecisionOutcome.Reason.INVENTORY_FULL;
        }
        ToolNeed tool = missingTool(mob);
        if (tool != null) {
            issues.add(CompanionTexts.tr("trip.tool", CompanionTexts.resource(tool.resource())));
            if (reason == null) reason = DecisionOutcome.Reason.MISSING_TOOL;
        }
        if (mob.level() instanceof ServerLevel world) {
            // Rückweg: in der Oberwelt das Lager, sonst das Ankunftsportal (Oberwelt-Koordinaten passen nicht zum Nether)
            BlockPos back = mob.inOverworld() && mob.getHome() != null ? mob.getHome()
                    : mob.netherPortal != null ? mob.netherPortal : mob.blockPosition();
            // Heimweg ist kein Ausflug: ein alter Todesort neben dem eigenen Haus darf ihn nicht draußen festhalten
            boolean goingHome = mob.getHome() != null && target.distSqr(mob.getHome()) <= 24 * 24
                    || mob.houseInside() != null && target.distSqr(mob.houseInside()) <= 24 * 24;
            if (!goingHome && (mob.memory.dangerous(world, target, 8) || mob.memory.dangerous(world, back, 8))) {
                issues.add(CompanionTexts.tr("trip.return_danger"));
                if (reason == null) reason = DecisionOutcome.Reason.ROUTE_UNPROVEN;
            }
        }
        return new Check(issues, reason);
    }

    /** Bereits in Reichweite stehendes Heimatlager nutzen, ohne den Reiseweg zu unterbrechen. */
    public static boolean resupplyFood(CompanionEntity mob, ServerLevel world) {
        return resupplyFood(mob, world, null);
    }

    public static boolean resupplyFood(CompanionEntity mob, ServerLevel world, BlockPos target) {
        return resupplyMeals(mob, world, foodReserve(mob, target));
    }

    static boolean resupplyMeals(CompanionEntity mob, ServerLevel world, int desired) {
        BlockPos home = mob.getHome();
        Container storage = reachableHomeStorage(mob, world, home);
        if (storage == null) return false;

        int taken = 0;
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            ItemStack stock = storage.getItem(slot);
            var food = stock.get(DataComponents.FOOD);
            if (food == null || food.nutrition() <= 0 || stock.is(Items.GOLDEN_APPLE)
                    || stock.is(Items.ENCHANTED_GOLDEN_APPLE)) continue;
            int gap = Math.max(0, MIN_HUNGER - mob.food.level());
            int servings = (gap + food.nutrition() - 1) / food.nutrition();
            int missing = desired + servings - mob.foodCount();
            if (missing <= 0) break;
            int surplus = storage.countItem(stock.getItem()) - mob.stockReserveAt(world, home, stock.getItem());
            int amount = Math.min(surplus, Math.min(missing, stock.getCount()));
            if (amount <= 0) continue;
            ItemStack portion = stock.copyWithCount(amount);
            var preview = new CompanionPrerequisites.Inventory(mob, true);
            if (!preview.add(portion) || preview.freeSlots() < FREE_SLOTS) continue;
            int moved = portion.getCount() - mob.giveToInventory(portion.copy()).getCount();
            if (moved <= 0) continue;
            stock.shrink(moved);
            taken += moved;
            while (mob.food.level() < MIN_HUNGER) {
                int before = mob.food.level();
                mob.eatBeforeTrip();
                if (mob.food.level() <= before) break;
            }
        }
        if (taken == 0) return false;
        recordStorageUse(mob, world, home, storage);
        mob.logInfo(CompanionTexts.tr("trip.food_from_home", taken));
        return true;
    }

    static int foodReserve(CompanionEntity mob, BlockPos target) {
        if (target == null) return FOOD_RESERVE;
        return foodReserveFor(Math.sqrt(target.distSqr(mob.blockPosition())));
    }

    /** Essensvorrat für eine Reise dieser Länge (dieselbe Regel wie die Reiseprüfung). */
    static int foodReserveFor(double distance) {
        return FOOD_RESERVE + Math.clamp((int) (distance / 128) - 1, 0, 8);
    }

    private record ToolNeed(String resource, int tier, int missingUses) {}

    private static ToolNeed missingTool(CompanionEntity mob) {
        for (var task : mob.peekTasks(64)) {
            // Eine Holztour nicht blockieren: die Warteschlange stellt danach die erste Spitzhacke her.
            if (task.spec() instanceof TaskSpec.Craft craft && CompanionPrerequisites.pickTier(ItemIds.resolve(craft.item())) > 0
                    || task.spec() instanceof TaskSpec.Process process && CompanionPrerequisites.pickTier(ItemIds.resolve(process.item())) > 0)
                return null;
            if (!(task.spec() instanceof TaskSpec.ResourceGoal resource)) continue;
            int tier = CompanionPrerequisites.requiredTier(resource.resource());
            if (tier == 0) return null;
            var res = Resources.byKey(resource.resource());
            int held = resource instanceof TaskSpec.Gather gather && gather.exactItem() != null
                    ? CompanionCraft.count(mob, gather.exactItem()) : res == null ? 0 : Resources.count(mob, res);
            int units = resource.targetTotal() > 0 && res != null ? resource.targetTotal() - held : resource.count();
            int missing = units - CompanionPrerequisites.availablePickUses(mob, tier);
            return missing > 0 ? new ToolNeed(resource.resource(), tier, missing) : null;
        }
        return null;
    }

    private record StoredPickaxe(int slot, int tier, int uses) {}

    /** Nur Werkzeug mitnehmen, das den gesamten fehlenden Haltbarkeitsbedarf abdeckt. */
    public static boolean resupplyTools(CompanionEntity mob, ServerLevel world) {
        ToolNeed need = missingTool(mob);
        if (need == null) return false;
        BlockPos home = mob.getHome();
        Container storage = reachableHomeStorage(mob, world, home);
        if (storage == null) return false;

        List<StoredPickaxe> candidates = new ArrayList<>();
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            ItemStack stack = storage.getItem(slot);
            int tier = CompanionPrerequisites.pickTier(stack.getItem());
            int uses = CompanionPrerequisites.uses(stack);
            if (tier >= need.tier() && uses > 0
                    && storage.countItem(stack.getItem()) > mob.stockReserveAt(world, home, stack.getItem()))
                candidates.add(new StoredPickaxe(slot, tier, uses));
        }
        var adequate = candidates.stream().filter(pick -> pick.uses() >= need.missingUses())
                .min(Comparator.comparingInt(StoredPickaxe::tier).thenComparingInt(StoredPickaxe::uses));
        if (adequate.isPresent()) {
            candidates = List.of(adequate.get());
        } else {
            candidates.sort(Comparator.comparingInt(StoredPickaxe::uses).reversed().thenComparingInt(StoredPickaxe::tier));
        }
        var preview = new CompanionPrerequisites.Inventory(mob, true);
        int remaining = need.missingUses();
        List<StoredPickaxe> selectedPicks = new ArrayList<>();
        var selectedByItem = new java.util.HashMap<net.minecraft.world.item.Item, Integer>();
        for (StoredPickaxe pick : candidates) {
            var stack = storage.getItem(pick.slot());
            int selected = selectedByItem.merge(stack.getItem(), 1, Integer::sum);
            if (storage.countItem(stack.getItem()) - selected < mob.stockReserveAt(world, home, stack.getItem())) {
                selectedByItem.merge(stack.getItem(), -1, Integer::sum);
                continue;
            }
            if (!preview.add(stack.copyWithCount(1)) || preview.freeSlots() < FREE_SLOTS) return false;
            selectedPicks.add(pick);
            remaining -= pick.uses();
            if (remaining <= 0) break;
        }
        if (remaining > 0) return false;

        int taken = 0;
        for (StoredPickaxe pick : selectedPicks) {
            ItemStack stock = storage.getItem(pick.slot());
            if (mob.giveToInventory(stock.copyWithCount(1)).isEmpty()) {
                stock.shrink(1);
                taken++;
            }
        }
        if (taken == 0) return false;
        recordStorageUse(mob, world, home, storage);
        mob.logInfo(CompanionTexts.tr("trip.tools_from_home", taken, CompanionTexts.resource(need.resource())));
        return true;
    }

    static void recordStorageUse(CompanionEntity mob, ServerLevel world, BlockPos home, Container storage) {
        storage.setChanged();
        mob.useContainer(world, home);
        mob.memory.noteChest(world, home, storage);
    }

    static Container reachableHomeStorage(CompanionEntity mob, ServerLevel world, BlockPos home) {
        return mob.hasRecipeRequest() ? null : homeOrderStorage(mob, world, home);
    }

    public static Container homeOrderStorage(CompanionEntity mob, ServerLevel world, BlockPos home) {
        var executor = mob.nav.executor();
        if (home == null || !home.equals(mob.getHome()) || mob.getTarget() != null || mob.isRetreating()
                || executor != null && !executor.safeToCancel(mob)
                || mob.distanceToSqr(Vec3.atCenterOf(home)) > 4.5 * 4.5
                || !world.hasChunkAt(home) || !mob.seesBlock(world, home) || mob.hazardAround(world, home, true)
                || mob.memory.dangerous(world, home, 8)) return null;
        return mob.work.active() ? mob.workStorage(world, home) : mob.orderStorage(world, home);
    }

    static boolean availableStorage(ServerLevel world, BlockPos pos) {
        return world.hasChunkAt(pos) && world.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity box
                && !box.isLocked() && box.getLootTable() == null;
    }
}
