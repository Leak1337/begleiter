package dev.begleiter.nav;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMod;

import static dev.begleiter.nav.BlockFlags.*;

/** Wegfindung: Block-Eigenschaften, Abbauzeiten wie Vanilla, Fall-Physik, Blockzugriff. */
public class NavBasicsTests {

    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, new BlockPos(1, 1, 1));
        c.setPersistenceRequired();
        c.stay();
        c.getInventory().clearContent();
        c.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return c;
    }

    private record Expect(Block block, int must, int mustNot) {}

    /** Wege graben nicht durch Gebautes: Blockhütten-Stämme, Holzblöcke, entrindete Stämme, gesetztes Laub. */
    @GameTest
    public void handGesetzteStaemmeUndLaubBleibenStehen(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockState placedLeaves = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        h.assertTrue(!has(BlockFlags.of(placedLeaves), NATURAL), "Gesetztes Laub gilt als natürlich");
        h.assertTrue(has(BlockFlags.of(Blocks.OAK_LEAVES.defaultBlockState()), NATURAL), "Baumlaub gilt nicht als natürlich");
        Block glazed = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace("white_glazed_terracotta"));
        h.assertTrue(glazed != Blocks.AIR, "white_glazed_terracotta fehlt in dieser Version");
        for (Block b : List.of(Blocks.STRIPPED_OAK_LOG, Blocks.OAK_WOOD, Blocks.CRIMSON_HYPHAE, glazed))
            h.assertTrue(!has(BlockFlags.of(b.defaultBlockState()), NATURAL), b + " gilt als natürlich");
        BlockPos wall = new BlockPos(4, 1, 4), tree = new BlockPos(6, 1, 6);
        h.setBlock(wall, Blocks.OAK_LOG);
        h.setBlock(wall.above(), Blocks.OAK_LOG);
        h.setBlock(tree, Blocks.OAK_LOG);
        h.setBlock(tree.above(), Blocks.OAK_LEAVES);
        NavContext ctx = new NavContext(c);
        BlockPos w = h.absolutePos(wall), t = h.absolutePos(tree);
        h.assertTrue(ctx.breakCost(w.getX(), w.getY(), w.getZ()) >= Costs.INF, "Wand aus Stämmen würde abgebaut");
        h.assertTrue(ctx.breakCost(t.getX(), t.getY(), t.getZ()) < Costs.INF, "Baumstamm mit Laub muss abbaubar bleiben");
        h.succeed();
    }

    @GameTest
    public void blockEigenschaftenStimmen(GameTestHelper h) {
        List<Expect> all = List.of(
                new Expect(Blocks.AIR, PASS | AIR, STAND),
                new Expect(Blocks.STONE, STAND | NATURAL, PASS),
                new Expect(Blocks.DEEPSLATE, STAND | NATURAL, PASS),
                new Expect(Blocks.DIRT, STAND | NATURAL, PASS),
                new Expect(Blocks.GRASS_BLOCK, STAND | NATURAL, PASS),
                new Expect(Blocks.DIAMOND_ORE, STAND | NATURAL, 0),
                new Expect(Blocks.SAND, STAND | NATURAL | FALLING, 0),
                new Expect(Blocks.GRAVEL, STAND | NATURAL | FALLING, 0),
                new Expect(Blocks.OAK_PLANKS, STAND, NATURAL),
                new Expect(Blocks.OAK_LEAVES, STAND | NATURAL, PASS),
                new Expect(Blocks.GLASS, STAND, NATURAL),
                new Expect(Blocks.CHEST, STAND, NATURAL),
                new Expect(Blocks.SHORT_GRASS, PASS, STAND),
                new Expect(Blocks.DANDELION, PASS, STAND),
                new Expect(Blocks.TORCH, PASS, STAND),
                new Expect(Blocks.OAK_SIGN, PASS, STAND),
                new Expect(Blocks.STONE_BUTTON, PASS, STAND),
                new Expect(Blocks.RAIL, PASS, STAND),
                new Expect(Blocks.REDSTONE_WIRE, PASS, STAND),
                new Expect(Blocks.MOSS_CARPET, PASS, STAND),
                new Expect(Blocks.SNOW, PASS | NATURAL, STAND),
                new Expect(Blocks.WATER, WATER, PASS | STAND),
                new Expect(Blocks.LAVA, LAVA | AVOID_INTO, PASS | STAND),
                new Expect(Blocks.FIRE, AVOID_INTO, PASS),
                new Expect(Blocks.SOUL_FIRE, AVOID_INTO, PASS),
                new Expect(Blocks.CAMPFIRE, AVOID_INTO, PASS | STAND),
                new Expect(Blocks.MAGMA_BLOCK, AVOID_INTO, STAND),
                new Expect(Blocks.CACTUS, AVOID_INTO, PASS | STAND),
                new Expect(Blocks.SWEET_BERRY_BUSH, AVOID_INTO, PASS),
                new Expect(Blocks.COBWEB, AVOID_INTO, PASS),
                new Expect(Blocks.POWDER_SNOW, AVOID_INTO, PASS | STAND),
                new Expect(Blocks.WITHER_ROSE, AVOID_INTO, PASS),
                new Expect(Blocks.POINTED_DRIPSTONE, AVOID_INTO | FALLING, STAND),
                new Expect(Blocks.HONEY_BLOCK, AVOID_INTO, STAND),
                new Expect(Blocks.LADDER, CLIMB, 0),
                new Expect(Blocks.VINE, CLIMB | PASS, 0),
                new Expect(Blocks.OAK_DOOR, DOOR, 0),
                new Expect(Blocks.IRON_DOOR, 0, DOOR),
                new Expect(Blocks.OAK_FENCE_GATE, GATE, 0),
                new Expect(Blocks.OAK_STAIRS, STAND, PASS),
                new Expect(Blocks.STONE_SLAB, STAND | SLAB_BOTTOM, PASS),
                new Expect(Blocks.FARMLAND, STAND | FRAGILE, 0),
                new Expect(Blocks.DIRT_PATH, STAND, 0),
                new Expect(Blocks.SOUL_SAND, STAND | SLOW | NATURAL, 0),
                new Expect(Blocks.BEDROCK, AVOID_BREAK | STAND, NATURAL),
                new Expect(Blocks.ICE, AVOID_BREAK, 0),
                new Expect(Blocks.INFESTED_STONE, AVOID_BREAK, 0),
                new Expect(Blocks.SPAWNER, AVOID_BREAK, 0),
                new Expect(Blocks.STONE_BRICKS, STRONGHOLD | STAND, NATURAL));
        StringBuilder wrong = new StringBuilder();
        for (Expect e : all) {
            int f = BlockFlags.of(e.block.defaultBlockState());
            if ((f & e.must) != e.must || (f & e.mustNot) != 0)
                wrong.append(' ').append(e.block.getName().getString()).append('=').append(Integer.toBinaryString(f));
        }
        h.assertTrue(wrong.isEmpty(), "Falsche Flags:" + wrong);
        h.succeed();
    }

    @GameTest
    public void abbauZeitenWieVanilla(GameTestHelper h) {
        CompanionEntity c = companion(h);
        var player = h.makeMockServerPlayerInLevel();
        player.setOnGround(true);
        record Case(Item tool, Block block) {}
        List<Case> cases = List.of(new Case(Items.AIR, Blocks.DIRT), new Case(Items.AIR, Blocks.STONE),
                new Case(Items.AIR, Blocks.OAK_LEAVES), new Case(Items.WOODEN_PICKAXE, Blocks.STONE),
                new Case(Items.GOLDEN_PICKAXE, Blocks.STONE), new Case(Items.DIAMOND_PICKAXE, Blocks.STONE),
                new Case(Items.STONE_PICKAXE, Blocks.IRON_ORE), new Case(Items.IRON_PICKAXE, Blocks.DEEPSLATE),
                new Case(Items.DIAMOND_PICKAXE, Blocks.OBSIDIAN), new Case(Items.IRON_SHOVEL, Blocks.GRAVEL),
                new Case(Items.IRON_AXE, Blocks.OAK_LOG), new Case(Items.IRON_SWORD, Blocks.COBWEB),
                new Case(Items.WOODEN_PICKAXE, Blocks.DIAMOND_ORE));
        BlockPos rel = new BlockPos(4, 1, 4);
        StringBuilder wrong = new StringBuilder();
        for (Case k : cases) {
            h.setBlock(rel, k.block);
            BlockState s = h.getBlockState(rel);
            ItemStack tool = k.tool == Items.AIR ? ItemStack.EMPTY : new ItemStack(k.tool);
            player.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
            c.getInventory().clearContent();
            if (!tool.isEmpty()) c.giveToInventory(tool.copy());
            int vanilla = NavContext.ticksFor(s.getDestroyProgress(player, h.getLevel(), h.absolutePos(rel)));
            int ours = new NavContext(c).breakTicks(s);
            if (vanilla != ours) wrong.append(' ').append(k.tool).append('/').append(k.block.getName().getString())
                    .append(": Vanilla ").append(vanilla).append(", wir ").append(ours);
        }
        h.assertTrue(wrong.isEmpty(), "Abbauzeit weicht ab:" + wrong);
        h.succeed();
    }

    @GameTest
    public void bestesWerkzeugMitEffizienz(GameTestHelper h) {
        CompanionEntity c = companion(h);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 5);
        c.giveToInventory(new ItemStack(Items.WOODEN_SHOVEL));
        c.giveToInventory(pick);
        c.giveToInventory(new ItemStack(Items.IRON_AXE));
        NavContext ctx = new NavContext(c);
        // Diamant 8 + Effizienz V (25+1) = 34 → 34 / 1,5 / 30 = 0,756 pro Tick → 2 Ticks
        h.assertValueEqual(ctx.breakTicks(Blocks.STONE.defaultBlockState()), 2, "Stein mit Effizienz V");
        // Holzschaufel 2 / 0,5 / 30 → 8 Ticks (Spitzhacke wirkt nicht auf Erde, Effizienz zählt dann nicht)
        h.assertValueEqual(ctx.breakTicks(Blocks.DIRT.defaultBlockState()), 8, "Erde mit Schaufel");
        // Eisenaxt 6 / 2 / 30 → 10 Ticks
        h.assertValueEqual(ctx.breakTicks(Blocks.OAK_LOG.defaultBlockState()), 10, "Stamm mit Axt");
        // in der Luft 5x langsamer, unter Wasser auch
        h.assertTrue(ctx.breakTicks(Blocks.STONE.defaultBlockState(), true, false) > 20, "Luft/Wasser nicht langsamer");
        h.succeed();
    }

    @GameTest
    public void abbauRegeln(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.giveToInventory(new ItemStack(Items.WOODEN_PICKAXE));
        c.giveToInventory(new ItemStack(Items.WOODEN_SHOVEL));
        h.setBlock(1, 1, 5, Blocks.STONE);
        h.setBlock(3, 1, 1, Blocks.STONE);
        h.setBlock(3, 2, 1, Blocks.WATER);
        h.setBlock(3, 1, 5, Blocks.STONE);
        h.setBlock(3, 2, 5, Blocks.GRAVEL);
        h.setBlock(3, 3, 5, Blocks.GRAVEL);
        h.setBlock(4, 1, 5, Blocks.OAK_PLANKS);
        h.setBlock(5, 1, 5, Blocks.IRON_ORE);
        h.setBlock(6, 1, 5, Blocks.STONE_BRICKS);
        h.setBlock(5, 1, 3, Blocks.GRAVEL);
        NavContext ctx = new NavContext(c);
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        java.util.function.BiFunction<Integer, Integer, Double> cost = (x, y) -> ctx.breakCost(o.getX() + x, o.getY() + y, o.getZ() + 5);
        double stone = cost.apply(1, 1);
        double gravel = ctx.breakCost(o.getX() + 5, o.getY() + 1, o.getZ() + 3);
        h.assertTrue(stone > 0 && stone < Costs.INF, "Stein nicht abbaubar: " + stone);
        h.assertValueEqual(cost.apply(1, 2), 0.0, "Luft kostet etwas");
        h.assertValueEqual(ctx.breakCost(o.getX() + 3, o.getY() + 1, o.getZ() + 1), Costs.INF, "Stein unter Wasser");
        h.assertTrue(Math.abs(cost.apply(3, 1) - (stone + 2 * gravel)) < 1e-6, "Kies darüber nicht mitgezählt: " + cost.apply(3, 1));
        h.assertValueEqual(cost.apply(4, 1), Costs.INF, "Bretter (gebaut)");
        h.assertValueEqual(cost.apply(5, 1), Costs.INF, "Eisenerz mit Holzhacke");
        h.assertValueEqual(cost.apply(6, 1), Costs.INF, "Steinziegel außerhalb der Festung");
        h.assertValueEqual(ctx.breakCost(o.getX() + 2, o.getY() + 0, o.getZ() + 2), stone, "Boden");
        h.assertValueEqual(ctx.breakCost(o.getX(), h.getLevel().getMinY() - 1, o.getZ()), Costs.INF, "unter der Welt");
        h.succeed();
    }

    @GameTest
    public void fallPhysik(GameTestHelper h) {
        h.assertValueEqual(Costs.FALL[0], 0.0, "0 Blöcke");
        for (int n = 1; n <= Costs.MAX_FALL_TABLE; n++)
            h.assertTrue(Costs.FALL[n] > Costs.FALL[n - 1], "Tabelle nicht steigend bei " + n);
        h.assertTrue(Costs.FALL[1] > 4 && Costs.FALL[1] < 6, "1 Block: " + Costs.FALL[1]);
        h.assertTrue(Costs.JUMP > 3 && Costs.JUMP < 3.4, "Sprung: " + Costs.JUMP);
        h.assertTrue(Costs.FALL[Costs.MAX_FALL_TABLE] < 100, "64 Blöcke: " + Costs.FALL[Costs.MAX_FALL_TABLE]);
        h.assertTrue(Math.abs(Costs.fallTicks(2.5) - (Costs.FALL[2] + Costs.FALL[3]) / 2) < 0.5, "Zwischenwert");
        h.assertTrue(Costs.SPRINT < Costs.WALK && Costs.WALK < Costs.SNEAK, "Tempo-Reihenfolge");
        h.succeed();
    }

    @GameTest
    public void fallSchadenAusLebenUndFederfall(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.setHealth(c.getMaxHealth()); // 40
        NavContext full = new NavContext(c);
        h.assertValueEqual(full.fallDamage(3), 0f, "3 Blöcke ohne Schaden");
        h.assertValueEqual(full.fallDamage(5), 2f, "5 Blöcke");
        h.assertValueEqual(full.maxFall, 35, "volles Leben: 40 - 32 = 8 bleiben");
        h.assertTrue(full.fallCost(4) > Costs.FALL[4], "Schaden nicht als Strafe");
        h.assertValueEqual(full.fallCost(36), Costs.INF, "zu tief");

        ItemStack boots = new ItemStack(Items.IRON_BOOTS);
        boots.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FEATHER_FALLING), 4);
        c.setItemSlot(EquipmentSlot.FEET, boots);
        h.assertTrue(new NavContext(c).maxFall > full.maxFall, "Federfall hilft nicht");

        c.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        c.setHealth(10);
        h.assertValueEqual(new NavContext(c).maxFall, 5, "10 Leben: höchstens 2 Schaden");
        h.succeed();
    }

    @GameTest
    public void blockZugriffLiestWelt(GameTestHelper h) {
        CompanionEntity c = companion(h);
        h.setBlock(3, 2, 3, Blocks.GOLD_BLOCK);
        h.setBlock(6, 5, 1, Blocks.WATER);
        BlockCache cache = new NavContext(c).blocks;
        StringBuilder wrong = new StringBuilder();
        for (int x = -2; x < 10; x++)
            for (int y = 0; y < 8; y++)
                for (int z = -2; z < 10; z++) {
                    BlockPos p = h.absolutePos(new BlockPos(x, y, z));
                    if (cache.get(p.getX(), p.getY(), p.getZ()) != h.getLevel().getBlockState(p)) wrong.append(' ').append(p.toShortString());
                }
        h.assertTrue(wrong.isEmpty(), "Abweichung bei" + wrong);
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        h.assertTrue(cache.get(o.getX() + 1_000_000, o.getY(), o.getZ()) == null, "Ungeladener Chunk liefert einen Block");
        h.assertTrue(BlockFlags.has(cache.flags(o.getX() + 1_000_000, o.getY(), o.getZ()), UNLOADED), "Ungeladen nicht markiert");
        h.assertTrue(BlockFlags.has(cache.flags(o.getX(), cache.minY - 1, o.getZ()), AVOID_INTO), "Unter der Welt nicht gemieden");
        h.assertTrue(BlockFlags.has(cache.flags(o.getX() + 6, o.getY() + 5, o.getZ() + 1), WATER), "Wasser nicht erkannt");
        h.succeed();
    }

    @GameTest
    public void kontextAusInventar(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 20));
        c.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        c.food.set(5);
        NavContext ctx = new NavContext(c);
        h.assertValueEqual(ctx.throwaway, 20, "Müllblöcke");
        h.assertTrue(ctx.waterBucket, "Wassereimer nicht erkannt");
        h.assertTrue(!ctx.canSprint, "Sprint trotz Hunger");
        h.assertTrue(!ctx.nether, "Oberwelt als Nether");
        c.food.set(20);
        BlockPos home = h.absolutePos(new BlockPos(4, 1, 4));
        c.setHome(home);
        ctx = new NavContext(c);
        h.assertTrue(ctx.canSprint, "kein Sprint satt");
        h.assertTrue(ctx.isProtected(home.getX() + 2, home.getY(), home.getZ()), "Lager nicht geschützt");
        h.assertTrue(!ctx.isProtected(home.getX(), home.getY() - 2, home.getZ()), "unter dem Lager geschützt");
        h.assertValueEqual(ctx.favor(home.getX(), home.getY(), home.getZ()), 1.0, "Standard-Faktor");
        h.succeed();
    }
}
