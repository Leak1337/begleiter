package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Bei eigener Beschaffung prüft er Hin- und Rückweg zu Erz und Jagdtier, bevor er losgeht. */
public class GuardedTripGameTests {
    private static CompanionEntity guarded(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 5));
        c.setPersistenceRequired();
        c.stay();
        c.getInventory().clearContent();
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        c.giveToInventory(new ItemStack(Items.STONE_SWORD));
        c.goal = CompanionNeeds.Need.SUPPLY; // eigener Beschaffungsplan = geschützter Modus
        h.assertTrue(dev.begleiter.ai.CompanionDecisionEngine.enabled(), "Fixture: decision engine off");
        return c;
    }

    private static boolean logged(CompanionEntity c, String key) {
        for (var entry : c.getLog(0, 40, false))
            if (entry.textComponent().getContents() instanceof TranslatableContents t && t.getKey().endsWith(key)) return true;
        return false;
    }

    @GameTest(maxTicks = 300, structure = "begleiter-gametest:bench")
    public void oreWithAKnownDangerOnTheWayIsSkippedNotThePlan(GameTestHelper h) {
        var c = guarded(h);
        BlockPos ore = h.absolutePos(new BlockPos(8, 1, 5));
        h.setBlock(8, 1, 5, Blocks.IRON_ORE);
        c.memory.remember(CompanionMemory.Kind.DANGER, "test", h.getLevel(), ore);
        c.addTask(CompanionEntity.Task.mine(Resources.IRON.key(), 1));
        h.succeedWhen(() -> {
            h.assertTrue(logged(c, "handler.mine.route_unsafe"), "No unsafe-route decision for the ore: " + c.getLog(0, 8, false).stream().map(e -> e.textComponent().getString()).toList() + " task " + c.currentTaskText());
            h.assertTrue(h.getLevel().getBlockState(ore).is(Blocks.IRON_ORE), "Ore mined despite the unsafe way");
        });
    }

    @GameTest(maxTicks = 400, structure = "begleiter-gametest:bench")
    public void oreWithASafeRoundTripIsMined(GameTestHelper h) {
        var c = guarded(h);
        BlockPos ore = h.absolutePos(new BlockPos(8, 1, 5));
        h.setBlock(8, 1, 5, Blocks.IRON_ORE);
        c.addTask(CompanionEntity.Task.mine(Resources.IRON.key(), 1));
        h.succeedWhen(() -> {
            h.assertTrue(!h.getLevel().getBlockState(ore).is(Blocks.IRON_ORE), "Ore not mined");
            h.assertTrue(!logged(c, "handler.mine.route_unsafe") && !logged(c, "handler.mine.route_unproven"),
                    "Safe ore rejected");
        });
    }

    @GameTest(maxTicks = 300, structure = "begleiter-gametest:bench")
    public void animalBehindAKnownDangerIsLeftAlone(GameTestHelper h) {
        var c = guarded(h);
        var cow = h.spawn(EntityTypes.COW, new BlockPos(8, 1, 5));
        cow.setNoAi(true);
        c.memory.remember(CompanionMemory.Kind.DANGER, "test", h.getLevel(), cow.blockPosition());
        c.addTask(CompanionEntity.Task.hunt(4));
        h.succeedWhen(() -> {
            h.assertTrue(logged(c, "handler.hunt.route_unsafe"), "No unsafe-route decision for the animal");
            h.assertTrue(c.isIgnoredTarget(cow) && cow.isAlive(), "Animal still hunted");
        });
    }

    @GameTest(maxTicks = 400, structure = "begleiter-gametest:bench")
    public void animalWithASafeRoundTripIsHunted(GameTestHelper h) {
        var c = guarded(h);
        var cow = h.spawn(EntityTypes.COW, new BlockPos(8, 1, 5));
        cow.setNoAi(true);
        c.addTask(CompanionEntity.Task.hunt(4));
        h.succeedWhen(() -> h.assertTrue(!cow.isAlive(), "Cow not hunted"));
    }
}
