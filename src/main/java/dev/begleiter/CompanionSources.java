package dev.begleiter;

import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.ai.CompanionSourceMemory.Key;
import dev.begleiter.ai.DecisionOutcome.Reason;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Explicit source identity; no chat text, source labels or inferred path coordinates. */
public final class CompanionSources {
    private CompanionSources() {}

    public static Key key(CompanionEntity e, Need need, Item item, BlockPos position) {
        if (!(e.level() instanceof ServerLevel sl) || need == null || item == null || item == Items.AIR || position == null) return null;
        return new Key(need, "chest", BuiltInRegistries.ITEM.getKey(item).toString(),
                sl.dimension().identifier().toString(), sl.getSeed(), position.asLong());
    }

    public static Key failureSource(CompanionEntity e, Need need, Reason reason, CompanionEntity.Task task) {
        if (need != Need.SUPPLY || reason == null || reason.preparationOnly() || task == null
                || !(task.spec() instanceof TaskSpec.Take take) || take.item() == null) return null;
        return key(e, need, CompanionCraft.item(take.item()), take.pos());
    }

    public static boolean allowed(CompanionEntity e, Item item, BlockPos position) {
        return !e.experiences.sources.blocked(key(e, Need.SUPPLY, item, position), e.tickCount);
    }

    public static boolean hasFailures(CompanionEntity e) {
        return e.level() instanceof ServerLevel sl && e.experiences.sources.hasActive(Need.SUPPLY,
                sl.dimension().identifier().toString(), sl.getSeed(), e.tickCount);
    }
}
