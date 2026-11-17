package dev.begleiter;

import static dev.begleiter.HandlerTextGameTests.*;

import dev.begleiter.debug.BotDebug.EventKind;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Echte Flucht-, Abwurf- und Übergabewege: Anzeigeänderungen dürfen Welt- und Inventarwirkung nicht ändern. */
public class EntityTextGameTests {
    @GameTest
    public void waterEscapeKeepsDiggingBuildingAndMissingBlockCadence(GameTestHelper h) throws Exception {
        var c = companion(h); var level = h.getLevel(); var feet = c.blockPosition(); var east = feet.east();
        level.setBlock(feet, Blocks.WATER.defaultBlockState(), 3);
        level.setBlock(east, Blocks.DIRT.defaultBlockState(), 3); level.setBlock(east.above(), Blocks.DIRT.defaultBlockState(), 3);
        c.giveToInventory(new ItemStack(Items.DIRT, 2));
        c.swim.escape(level, feet.east(4));
        h.assertTrue(level.getBlockState(east).isAir() && level.getBlockState(east.above()).isAir()
                && CompanionCraft.count(c, "dirt") == 2 && c.getDeltaMovement().x > 0, "Water digging changed blocks/material/motion");
        translated(h, c.getLog(0, 1, false).getFirst().textComponent(), "Versuch 1", "attempt 1");
        translated(h, c.getLog(0, 1, false).getFirst().textComponent(), "Osten", "east");
        c.swim.escape(level, feet.east(4));
        h.assertTrue(level.getBlockState(feet).is(Blocks.DIRT) && CompanionCraft.count(c, "dirt") == 1
                && c.getY() == feet.getY() + 1 && c.getDeltaMovement().lengthSqr() == 0, "Water pillar changed placement/consumption/motion");
        translated(h, c.getLog(0, 1, false).getFirst().textComponent(), "baue mich raus", "build my way out");
        h.assertTrue(c.messages.isEmpty(), "Water warning emitted before third attempt");
        c.getInventory().clearContent(); c.swim.escape(level, c.blockPosition().east(4));
        h.assertTrue(c.messages.size() == 1 && c.usedSlots() == 0 && level.getBlockState(c.blockPosition()).isAir()
                && c.getDeltaMovement().y > 0, "Missing-block escape changed cadence or invented a block");
        var entry = c.getLog(0, 1, false).getFirst();
        h.assertTrue(entry.kind() == EventKind.NONE, "Escape display changed event identity");
        translated(h, c.problemComponent(), "keine Blöcke", "no blocks"); h.succeed();
    }

    @GameTest
    public void handoverKeepsNamespacedCountsAndPickupDelay(GameTestHelper h) throws Exception {
        var c = companion(h); c.giveToInventory(new ItemStack(RegistryIdTestItems.DIAMOND, 5));
        c.giveToInventory(new ItemStack(Items.DIAMOND, 2));
        int given = c.giveTo(null, "item:begleiter-test:diamond", 3);
        h.assertTrue(given == 3 && CompanionJobs.countOf(c, RegistryIdTestItems.DIAMOND) == 2
                && CompanionJobs.countOf(c, Items.DIAMOND) == 2 && c.collectBlockedUntil == c.tickCount + 300,
                "Handover changed namespaces/amount/pickup delay");
        int dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, c.getBoundingBox().inflate(3)).stream()
                .filter(e -> e.getItem().is(RegistryIdTestItems.DIAMOND)).mapToInt(e -> e.getItem().getCount()).sum();
        h.assertValueEqual(dropped, 3, "Handover dropped the wrong amount");
        translated(h, c.messages.getLast(), "3 Items", "3 items");
        h.assertValueEqual(c.giveTo(null, "item:minecraft:emerald", 1), 0, "Missing-item handover succeeded");
        translated(h, c.messages.getLast(), "nichts dabei", "not carrying any"); h.succeed();
    }

    @GameTest
    public void deathKeepsNativeCauseMemoryAndExactDrops(GameTestHelper h) throws Exception {
        var c = companion(h); var level = h.getLevel(); c.setCustomName(Component.literal("Berta %s Ä"));
        c.giveToInventory(new ItemStack(RegistryIdTestItems.DIAMOND, 5));
        c.dropCustomDeathLoot(level, level.damageSources().generic(), false);
        h.assertValueEqual(c.usedSlots(), 0, "Death did not empty inventory");
        int diamonds = 0, cores = 0;
        for (var drop : level.getEntitiesOfClass(ItemEntity.class, c.getBoundingBox().inflate(3))) {
            if (drop.getItem().is(RegistryIdTestItems.DIAMOND)) diamonds += drop.getItem().getCount();
            if (drop.getItem().is(CompanionMod.COMPANION_CORE)) cores += drop.getItem().getCount();
        }
        h.assertTrue(diamonds == 5 && cores == 1, "Death changed item/core drops");
        var place = c.memory.nearest(CompanionMemory.Kind.DEATH, null, level, c.blockPosition(), 1);
        h.assertTrue(place != null && place.pos.equals(c.blockPosition()), "Death memory lost");
        var message = c.messages.getLast();
        h.assertTrue(message.getContents() instanceof TranslatableContents tr && tr.getArgs()[1] instanceof Component cause
                && cause.getContents() instanceof TranslatableContents nativeCause && nativeCause.getKey().startsWith("death.attack."),
                "Native death cause flattened");
        translated(h, message, "Ich bin gestorben bei", "I died at");
        translated(h, message, "Berta %s Ä", "Berta %s Ä"); h.succeed();
    }
}
