package dev.begleiter;

import java.lang.reflect.Modifier;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Jede Planung hat ihren eigenen Kontext; zwei Begleiter vermischen keine Caches. */
public class PlanContextGameTests {
    private static CompanionEntity companion(GameTestHelper h, BlockPos at) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, at);
        c.stay();
        c.getInventory().clearContent();
        c.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return c;
    }

    @GameTest
    public void plannerHasNoMutableStaticState(GameTestHelper h) {
        for (var f : CompanionPlanner.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            h.assertTrue(Modifier.isFinal(f.getModifiers()), "Mutable static field: " + f.getName());
            var type = f.getType();
            h.assertTrue(!java.util.Map.class.isAssignableFrom(type) && !java.util.Collection.class.isAssignableFrom(type),
                    "Static collection keeps planning state: " + f.getName());
        }
        h.succeed();
    }

    @GameTest
    public void alternatingPlansOfTwoCompanionsDoNotShareChestCaches(GameTestHelper h) {
        var a = companion(h, new BlockPos(1, 1, 1));
        var b = companion(h, new BlockPos(7, 1, 7));
        h.setBlock(new BlockPos(2, 1, 1), Blocks.CHEST);
        var chestPos = h.absolutePos(new BlockPos(2, 1, 1));
        var box = a.containerAt(h.getLevel(), chestPos);
        h.assertTrue(box != null, "Test chest missing");
        box.setItem(0, new ItemStack(Items.COAL, 8));
        a.memory.noteChest(h.getLevel(), chestPos, box);
        for (int round = 0; round < 3; round++) {
            box.setItem(0, new ItemStack(Items.COAL, 8));
            var planA = CompanionPlanner.obtainPrepared(a, Items.COAL, 4, CompanionPlanner.RoutePolicy.AVOID_MINING);
            h.assertTrue(planA != null && planA.tasks().stream().anyMatch(t -> chestPos.equals(t.pos())),
                    "Round " + round + ": A did not use the full chest");
            box.clearContent(); // Kiste leer: ein gemerkter Kisten-Cache darf nicht in die nächste Planung reichen
            a.memory.noteChest(h.getLevel(), chestPos, box);
            for (var c : new CompanionEntity[] {b, a}) {
                var plan = CompanionPlanner.obtainPrepared(c, Items.COAL, 4, CompanionPlanner.RoutePolicy.AVOID_MINING);
                h.assertTrue(plan == null || plan.tasks().stream().noneMatch(t -> chestPos.equals(t.pos())),
                        "Round " + round + ": empty chest planned from a stale cache");
            }
        }
        h.succeed();
    }
}
