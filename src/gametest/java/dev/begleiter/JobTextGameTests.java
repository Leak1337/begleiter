package dev.begleiter;

import static dev.begleiter.HandlerTextGameTests.*;

import java.util.Optional;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.task.TaskHandler;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;

/** Echte Arbeits-Handler: übersetzte Ausgaben müssen Item-Identitäten und Arbeitsergebnisse erhalten. */
public class JobTextGameTests {
    private static boolean hasKey(Component text, String key) {
        if (text.getContents() instanceof TranslatableContents tr) {
            if (tr.getKey().equals(key)) return true;
            for (Object arg : tr.getArgs()) if (arg instanceof Component c && hasKey(c, key)) return true;
        }
        return text.getSiblings().stream().anyMatch(c -> hasKey(c, key));
    }

    @GameTest
    public void sortingPreservesNamespacesAndTranslatedCategories(GameTestHelper h) throws Exception {
        var c = companion(h); var ctx = new Nearby(c, h.getLevel());
        h.setBlock(2, 1, 4, Blocks.CHEST); h.setBlock(5, 1, 4, Blocks.CHEST);
        var a = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 1, 4)));
        var b = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(5, 1, 4)));
        var foreign = new ItemStack(RegistryIdTestItems.DIAMOND, 5);
        foreign.set(DataComponents.CUSTOM_NAME, Component.literal("Foreign %s Ä"));
        a.setItem(0, foreign); a.setItem(1, new ItemStack(Items.IRON_INGOT, 7));
        var job = handler(c, Task.sort(h.absolutePos(new BlockPos(3, 1, 4))), ctx);
        h.assertTrue(job.tick(ctx) == TaskHandler.Status.DONE, "Sorting did not finish"); job.stop(ctx);
        int foreignCount = 0, ironCount = 0, vanillaDiamonds = 0;
        for (var box : new Container[]{a, b}) for (int i = 0; i < box.getContainerSize(); i++) {
            var s = box.getItem(i);
            if (s.is(RegistryIdTestItems.DIAMOND)) {
                foreignCount += s.getCount(); h.assertValueEqual(s.getHoverName().getString(), "Foreign %s Ä", "Custom name lost");
            }
            if (s.is(Items.IRON_INGOT)) ironCount += s.getCount();
            if (s.is(Items.DIAMOND)) vanillaDiamonds += s.getCount();
        }
        h.assertTrue(foreignCount == 5 && ironCount == 7 && vanillaDiamonds == 0, "Sorting changed identities/counts");
        var message = c.messages.getLast();
        h.assertTrue(hasKey(message, "server.begleiter.jobs.category.ores")
                && hasKey(message, "server.begleiter.jobs.category.other"), "Category labels flattened or misclassified");
        translated(h, message, "nach Sorten", "by category"); h.succeed();
    }

    @GameTest
    public void breedingConsumesOnlyFeedForTheRequestedSpecies(GameTestHelper h) throws Exception {
        var c = companion(h); var ctx = new Nearby(c, h.getLevel());
        var a = h.spawn(EntityTypes.COW, new BlockPos(4, 1, 2));
        var b = h.spawn(EntityTypes.COW, new BlockPos(4, 1, 3));
        var foreign = h.spawn(RegistryIdTestItems.COW, new BlockPos(3, 1, 2));
        for (var cow : new net.minecraft.world.entity.animal.cow.Cow[]{a, b, foreign}) { cow.setNoAi(true); cow.setAge(0); }
        c.giveToInventory(new ItemStack(Items.WHEAT, 2));
        var job = handler(c, Task.breed("cow", 1), ctx); var status = TaskHandler.Status.RUNNING;
        for (int tick = 0; tick < 4 && status == TaskHandler.Status.RUNNING; tick++) status = job.tick(ctx);
        job.stop(ctx);
        h.assertTrue(status == TaskHandler.Status.DONE && a.isInLove() && b.isInLove() && !foreign.isInLove()
                && c.herdKeeping, "Breeding changed species matching or herd state");
        h.assertValueEqual(CompanionJobs.countOf(c, Items.WHEAT), 0, "Breeding consumed the wrong feed amount");
        h.assertTrue(hasKey(CompanionJobs.foodText("minecraft:cow"), "item.minecraft.wheat")
                && hasKey(CompanionJobs.foodText(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(RegistryIdTestItems.COW).toString()),
                        "server.begleiter.jobs.food.appropriate"), "Feed labels ignored namespaces");
        translated(h, c.messages.getLast(), "gefüttert", "I fed 2 cows"); h.succeed();
    }

    @GameTest
    public void repairPreservesCustomNamesAndExactMaterialConsumption(GameTestHelper h) throws Exception {
        var c = companion(h); var ctx = new Nearby(c, h.getLevel()); h.setBlock(4, 1, 2, Blocks.ANVIL);
        var pick = new ItemStack(Items.IRON_PICKAXE); pick.setDamageValue((pick.getMaxDamage() / 4) * 2);
        pick.set(DataComponents.CUSTOM_NAME, Component.literal("Berta %s Ä"));
        c.giveToInventory(pick); c.giveToInventory(new ItemStack(Items.IRON_INGOT, 3));
        var job = handler(c, Task.repair(90), ctx); var status = TaskHandler.Status.RUNNING;
        for (int tick = 0; tick < 25 && status == TaskHandler.Status.RUNNING; tick++) status = job.tick(ctx);
        job.stop(ctx);
        h.assertTrue(status == TaskHandler.Status.DONE, "Repair did not finish");
        h.assertValueEqual(CompanionJobs.countOf(c, Items.IRON_INGOT), 1, "Repair consumed the wrong material amount");
        boolean repaired = false;
        for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
            var stack = c.getInventory().getItem(i);
            if (stack.is(Items.IRON_PICKAXE)) repaired = stack.getDamageValue() == 0 && stack.getHoverName().getString().equals("Berta %s Ä");
        }
        h.assertTrue(repaired, "Repair lost the tool name or left damage");
        var message = c.messages.getLast();
        h.assertTrue(hasKey(message, "server.begleiter.jobs.repair.item"), "Repair detail flattened");
        translated(h, message, "Werkzeug gepflegt", "Tools maintained");
        translated(h, message, "Berta %s Ä", "Berta %s Ä"); h.succeed();
    }

    @GameTest
    public void equipmentRetryAndTradeKeepTheirCountersAndPayments(GameTestHelper h) throws Exception {
        var c = companion(h); var ctx = new Nearby(c, h.getLevel());
        var gear = handler(c, new Task(new TaskSpec.GearUp(3, "iron_sword", 2, 0), 0), ctx);
        h.assertTrue(gear.tick(ctx) == TaskHandler.Status.REPLACED, "Unavailable gear did not advance"); gear.stop(ctx);
        var retry = c.currentTask(); var spec = (TaskSpec.GearUp) retry.spec();
        h.assertTrue(retry.tries() == 1 && spec.skipMask() == 1 && spec.lastPiece() == null && c.usedSlots() == 0,
                "Translated gear output changed retries/skip mask/inventory");
        h.assertTrue(hasKey(c.messages.getLast(), "item.minecraft.iron_sword"), "Gear name flattened");
        translated(h, c.messages.getLast(), "lasse ich erst mal aus", "skipping it for now");
        c.cancelOrders(); c.messages.clear();
        var villager = h.spawn(EntityTypes.VILLAGER, new BlockPos(4, 1, 2)); villager.setNoAi(true);
        var offers = villager.getOffers(); offers.clear();
        var offer = new MerchantOffer(new ItemCost(Items.EMERALD, 1), Optional.empty(), new ItemStack(Items.BOOK), 12, 1, .05f);
        offers.add(offer); c.giveToInventory(new ItemStack(Items.EMERALD, 2));
        var trade = handler(c, Task.trade("book", 2), ctx); var status = TaskHandler.Status.RUNNING;
        for (int tick = 0; tick < 25 && status == TaskHandler.Status.RUNNING; tick++) { c.tickCount++; status = trade.tick(ctx); }
        trade.stop(ctx);
        h.assertTrue(status == TaskHandler.Status.DONE && CompanionJobs.countOf(c, Items.BOOK) == 2
                && CompanionJobs.countOf(c, Items.EMERALD) == 0 && offer.getUses() == 2, "Trade changed transactions or payment");
        h.assertTrue(hasKey(c.messages.getLast(), "item.minecraft.book"), "Trade item name flattened");
        translated(h, c.messages.getLast(), "Eingekauft: 2x", "Purchased: 2x"); h.succeed();
    }
}
