package dev.begleiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Wie teuer ist eine Planung auf dem Serverthread? Fünf Begleiter planen schwierige Rezeptketten mit wenig, etwas
 * und viel Vorrat. Gemessen werden Zeit je Planung (nach Aufwärmen) und ob wiederholte Planungen dasselbe Ergebnis
 * liefern. Die Zeilen {@code [planning-cost]} im Protokoll sind die Messwerte; harte Zeitgrenzen gibt es bewusst
 * nicht, weil sie von der Maschine abhängen.
 */
public class PlanningCostGameTests {
    private static final Item[] GOALS = {Items.ENDER_EYE, Items.DIAMOND_PICKAXE, Items.SHIELD, Items.IRON_CHESTPLATE,
            Items.BUCKET, Items.CHEST, Items.TORCH, Items.FURNACE, Items.BOW, Items.CAKE};

    private static CompanionEntity companion(GameTestHelper h, int index) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(1 + index, 1, 2));
        c.setNoAi(true);
        c.stay();
        c.getInventory().clearContent();
        c.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        switch (index) {
            case 0 -> { } // nichts
            case 1 -> c.giveToInventory(new ItemStack(Items.OAK_LOG, 16));
            case 2 -> { c.giveToInventory(new ItemStack(Items.OAK_LOG, 32)); c.giveToInventory(new ItemStack(Items.COBBLESTONE, 32)); }
            case 3 -> { c.giveToInventory(new ItemStack(Items.IRON_INGOT, 12)); c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 20));
                        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE)); }
            default -> { c.giveToInventory(new ItemStack(Items.RAW_IRON, 9)); c.giveToInventory(new ItemStack(Items.COAL, 8));
                         c.giveToInventory(new ItemStack(Items.STICK, 8)); c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE)); }
        }
        return c;
    }

    private static String describe(CompanionPlanner.Plan p) {
        return p == null ? "none" : p.tasks().size() + "|" + String.format(Locale.ROOT, "%.1f", p.cost()) + "|" + p.steps();
    }

    @GameTest(maxTicks = 400)
    public void fiveCompanionsPlanHardRecipeChains(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 5; z++) h.setBlock(x, 0, z, Blocks.STONE);
        List<CompanionEntity> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) all.add(companion(h, i));
        // Aufwärmen: Rezeptindex, Klassen, JIT
        for (CompanionEntity c : all) for (Item goal : GOALS) CompanionPlanner.obtain(c, goal, 1);

        long worst = 0, total = 0;
        int plans = 0, found = 0;
        String worstName = "";
        for (CompanionEntity c : all) {
            for (Item goal : GOALS) {
                String first = null;
                for (int round = 0; round < 3; round++) {
                    long t0 = System.nanoTime();
                    var plan = CompanionPlanner.obtain(c, goal, 1);
                    long dt = System.nanoTime() - t0;
                    String now = describe(plan);
                    if (first == null) { first = now; if (plan != null) found++; }
                    else h.assertValueEqual(now, first, "Plan for " + goal + " changed between identical runs");
                    total += dt;
                    plans++;
                    if (dt > worst) { worst = dt; worstName = goal + "#" + all.indexOf(c); }
                }
            }
        }
        // Einzelkosten der Umgebungssuchen, die eine Planung auslösen kann
        CompanionEntity probe = all.getFirst();
        long tc = System.nanoTime();
        for (int i = 0; i < 20; i++) probe.storage.containerCandidates(16);
        long containers = (System.nanoTime() - tc) / 20;
        long ts = System.nanoTime();
        for (int i = 0; i < 20; i++) probe.findStation("crafting_table", 24);
        long station = (System.nanoTime() - ts) / 20;
        com.mojang.logging.LogUtils.getLogger().info(String.format(Locale.ROOT,
                "[planning-cost] containerScan=%.3fms stationScan=%.3fms", containers / 1e6, station / 1e6));
        // Gleichzeitig: alle fünf planen dasselbe schwierigste Ziel im selben Tick
        long t0 = System.nanoTime();
        for (CompanionEntity c : all) CompanionPlanner.obtain(c, Items.ENDER_EYE, 1);
        long sameTick = System.nanoTime() - t0;
        com.mojang.logging.LogUtils.getLogger().info(String.format(Locale.ROOT,
                "[planning-cost] plans=%d found=%d avg=%.3fms worst=%.3fms (%s) fiveInOneTick=%.3fms",
                plans, found, total / 1e6 / plans, worst / 1e6, worstName, sameTick / 1e6));
        h.assertTrue(found > 0, "No plan found at all");
        h.succeed();
    }
}
