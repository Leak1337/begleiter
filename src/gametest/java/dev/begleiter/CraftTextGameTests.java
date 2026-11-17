package dev.begleiter;

import static dev.begleiter.HandlerTextGameTests.*;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.task.TaskHandler;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Herstellungsfehler behalten übersetzte Details, ohne Zutatenbuchung oder Wiederholungen zu ändern. */
public class CraftTextGameTests {
    @GameTest
    public void missingIngredientKeepsCountsCooldownAndSuccessfulRetry(GameTestHelper h) throws Exception {
        var c = companion(h); var ctx = new Nearby(c, h.getLevel());
        c.giveToInventory(new ItemStack(Items.COAL)); c.giveToInventory(new ItemStack(Items.STICK, 2));
        var task = new Task(Task.craft("torch", 8).spec(), 3);
        var job = handler(c, task, ctx); var status = TaskHandler.Status.RUNNING;
        for (int tick = 0; tick < 20 && status == TaskHandler.Status.RUNNING; tick++) status = job.tick(ctx);
        job.stop(ctx);
        h.assertTrue(status == TaskHandler.Status.FAILED && c.currentTask() == task && c.craftBlocked("torch")
                && c.plannerCooldownUntil == c.tickCount + 200, "Craft failure changed task/cooldowns");
        h.assertTrue(CompanionCraft.count(c, "coal") == 1 && CompanionCraft.count(c, "stick") == 2
                && CompanionCraft.count(c, "torch") == 0, "Failed craft booked ingredients/output");
        translated(h, c.craftProblemComponent(), "Zutat Kohle oder Holzkohle 1/2", "Ingredient coal or charcoal 1/2");
        translated(h, c.problemComponent(), "Zutat Kohle oder Holzkohle 1/2", "Ingredient coal or charcoal 1/2");
        var failure = c.getLog(0, 1, false).getFirst().textComponent();
        translated(h, failure, "Zutat Kohle oder Holzkohle 1/2", "Ingredient coal or charcoal 1/2");
        c.cancelOrders(); c.giveToInventory(new ItemStack(Items.COAL));
        var retry = handler(c, Task.craft("torch", 8), ctx); status = TaskHandler.Status.RUNNING;
        for (int tick = 0; tick < 20 && status == TaskHandler.Status.RUNNING; tick++) status = retry.tick(ctx);
        retry.stop(ctx);
        h.assertTrue(status == TaskHandler.Status.DONE && CompanionCraft.count(c, "torch") == 8
                && CompanionCraft.count(c, "coal") == 0 && CompanionCraft.count(c, "stick") == 0,
                "Retry changed batch size or ingredient consumption"); h.succeed();
    }

    @GameTest
    public void recipeBookFailureIsTranslatedAndPreservesWholeBatch(GameTestHelper h) throws Exception {
        var c = companion(h); c.giveToInventory(new ItemStack(Items.IRON_INGOT));
        h.assertTrue(!CompanionCraft.craft(c, "iron_nugget", 10), "Incomplete recipe-book batch succeeded");
        h.assertTrue(CompanionCraft.count(c, "iron_ingot") == 1 && CompanionCraft.count(c, "iron_nugget") == 0,
                "Failed recipe-book batch consumed ingredients");
        var detail = c.craftProblemComponent();
        h.assertTrue(detail.getContents() instanceof TranslatableContents tr && tr.getArgs()[0] instanceof Component item
                && item.getContents() instanceof TranslatableContents nativeName && nativeName.getKey().equals("item.minecraft.iron_nugget"),
                "Recipe-book result name flattened");
        translated(h, detail, "keine Zutaten für", "no ingredients for");
        c.giveToInventory(new ItemStack(Items.IRON_INGOT));
        h.assertTrue(CompanionCraft.craft(c, "minecraft:iron_nugget", 10), "Complete recipe-book batch failed");
        h.assertTrue(CompanionCraft.count(c, "iron_ingot") == 0 && CompanionCraft.count(c, "iron_nugget") == 18,
                "Recipe-book retry changed rounded output/consumption"); h.succeed();
    }

    @GameTest
    public void unknownRecipeReplacesStaleReasonAndLegacyWritesRemainLiteral(GameTestHelper h) throws Exception {
        var c = companion(h); c.giveToInventory(new ItemStack(Items.OAK_PLANKS));
        h.assertTrue(!CompanionCraft.craft(c, "stick", 4), "Setup did not produce a missing-ingredient reason");
        translated(h, c.craftProblemComponent(), "Bretter", "planks");
        String unknown = "missing:recipe_%s";
        h.assertTrue(!CompanionCraft.craft(c, unknown, 1), "Unknown recipe succeeded");
        h.assertValueEqual(CompanionCraft.count(c, "oak_planks"), 1, "Unknown recipe consumed material");
        translated(h, c.craftProblemComponent(), "Unbekanntes Rezept/Item", "Unknown recipe/item");
        h.assertTrue(c.craftProblemComponent().getString().contains(unknown), "Unknown ID formatted or truncated incorrectly");
        c.lastCraftProblem = "Custom reason %s Ä";
        ServerTextGameTests.inLanguage("en_us", () -> h.assertValueEqual(c.craftProblemComponent().getString(),
                "Custom reason %s Ä", "Legacy write reused stale translated reason")); h.succeed();
    }
}
