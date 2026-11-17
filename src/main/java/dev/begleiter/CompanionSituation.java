package dev.begleiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.ai.CompanionDecisionEngine;
import dev.begleiter.ai.CompanionDecisionEngine.SupplyOption;
import dev.begleiter.ai.SituationalAssessment;
import dev.begleiter.ai.StrategyCandidate;
import dev.begleiter.ai.StrategyEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Liest vorhandenes Inventar und Wahrnehmung; ruft nie den Ressourcenplaner auf und lädt keine fernen Chunks. */
public final class CompanionSituation {
    private CompanionSituation() {}

    public static StrategyCandidate candidate(CompanionEntity e, CompanionNeeds.Score score) {
        if (score.need() == Need.SUPPLY) {
            SupplyOption chosen = chooseSupply(e, supplyOptions(e));
            if (chosen != null) return supplyCandidate(e, score, chosen);
            return assess(e, score, null, 0, 0, 100, null, 0);
        }
        if (score.need() != Need.ORDER) throw new IllegalArgumentException("Protected need has no alternative ranking");
        if (e.hasScaffold()) {
            BlockPos target = e.scaffold.stream().min(Comparator.comparingDouble(p -> p.distSqr(e.blockPosition()))).orElse(null);
            return assess(e, score, target, e.scaffold.size(), 100, 100, "shovel", 0);
        }
        if (e.looseItemsNearby()) {
            var drops = e.level().getEntitiesOfClass(ItemEntity.class, e.getBoundingBox().inflate(12),
                    item -> item.isAlive() && item.getAge() < 6000 && e.collectible(item));
            ItemEntity nearest = drops.stream().min(Comparator.comparingDouble(e::distanceToSqr)).orElse(null);
            int capacity = nearest == null ? 0 : capacity(e, nearest.getItem());
            return assess(e, score, nearest == null ? null : nearest.blockPosition(), drops.size(),
                    100, capacity, null, 0);
        }
        // Abliefern schafft Platz; ein volles Inventar spricht dafür, nicht dagegen.
        return assess(e, score, e.getHome(), e.depositableSlots(), 100, 100, null, 0);
    }

    public static List<SupplyOption> supplyOptions(CompanionEntity e) {
        List<SupplyOption> options = new ArrayList<>();
        int wood = CompanionCraft.count(e, "#logs") + CompanionCraft.count(e, "#planks") / 4;
        int stone = CompanionCraft.count(e, "#stone"), coal = CompanionCraft.count(e, "#coals");
        if (wood < 16) options.add(new SupplyOption("holz", 16 - wood));
        if (stone < 32) options.add(new SupplyOption("stein", 32 - stone));
        if (coal < 8) options.add(new SupplyOption("kohle", 8 - coal));
        return options;
    }

    /** Strafen für jüngste Fehlschläge behalten ihr Veto und werden gegen die aktuelle Lage abgewogen. */
    public static SupplyOption chooseSupply(CompanionEntity e, List<SupplyOption> options) {
        return CompanionDecisionEngine.chooseSupply(options,
                option -> e.experiences.penalty(Need.SUPPLY, option.resourceKey(), e.tickCount),
                option -> StrategyEvaluator.score(supplyCandidate(e, supplyScore(option), option))
                        + e.experiences.bestLearnedPreference(Need.SUPPLY, option.resourceKey()));
    }

    private static CompanionNeeds.Score supplyScore(SupplyOption option) {
        int urgency = switch (option.resourceKey()) { case "holz" -> 26; case "stein" -> 22; default -> 20; };
        return new CompanionNeeds.Score(Need.SUPPLY, urgency, CompanionTexts.tr("planner.reason.situation"));
    }

    public static StrategyCandidate supplyCandidate(CompanionEntity e, CompanionNeeds.Score score, SupplyOption option) {
        Resources.Res resource = Resources.byKey(option.resourceKey());
        if (resource == null) throw new IllegalArgumentException("Unknown supply resource");
        BlockPos target = null;
        if (e.level() instanceof ServerLevel sl) {
            var known = e.memory.nearest(resource == Resources.WOOD ? CompanionMemory.Kind.TREE : CompanionMemory.Kind.ORE,
                    resource == Resources.WOOD ? null : resource.key(), sl, e.blockPosition(), 128);
            if (known != null) target = known.pos;
        }
        int wanted = switch (option.resourceKey()) { case "holz" -> 16; case "stein" -> 32; default -> 8; };
        int missing = Math.max(0, option.missing());
        int capacity = 0;
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack stack = e.getInventory().getItem(i);
            if (stack.isEmpty()) capacity += 64;
            else if (resource.item().test(stack)) capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return assess(e, score, target, missing, percent(missing, wanted), percent(capacity, Math.max(1, missing)),
                resource == Resources.WOOD ? "axe" : "pickaxe", resource == Resources.WOOD ? 0 : 1);
    }

    private static int capacity(CompanionEntity e, ItemStack wanted) {
        int space = 0;
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack stack = e.getInventory().getItem(i);
            if (stack.isEmpty()) space += wanted.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(stack, wanted))
                space += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return percent(space, Math.max(1, wanted.getCount()));
    }

    private static StrategyCandidate assess(CompanionEntity e, CompanionNeeds.Score score, BlockPos target,
                                            int work, int deficit, int capacity, String toolKind, int requiredTier) {
        ItemStack tool = toolKind == null ? ItemStack.EMPTY : CompanionCraft.bestTool(e, toolKind);
        int tier = toolKind == null ? 0 : CompanionCraft.tier(e, toolKind);
        int durability = tool.isEmpty() || !tool.isDamageableItem() ? 100
                : percent(tool.getMaxDamage() - tool.getDamageValue(), tool.getMaxDamage());
        boolean danger = false, exposed = false;
        if (e.level() instanceof ServerLevel sl) {
            danger = target != null && e.memory.dangerous(sl, target, 8);
            exposed = sl.isDarkOutside() && (target == null ? !e.isInsideHouse()
                    : !sl.hasChunkAt(target) || sl.canSeeSky(target));
        }
        var context = new SituationalAssessment.Context(
                percent((int) Math.ceil(e.getHealth() * 100), (int) Math.ceil(e.getMaxHealth() * 100)),
                e.hunger, 36 - e.usedSlots(), capacity, deficit, work, tier, requiredTier, durability,
                target == null ? 64 : Math.sqrt(target.distSqr(e.blockPosition())), target != null, danger, exposed);
        return SituationalAssessment.assess(score, context, e.goal == score.need());
    }

    private static int percent(int n, int total) {
        return (int) Math.max(0, Math.min(100, (long) n * 100 / Math.max(1, total)));
    }
}
