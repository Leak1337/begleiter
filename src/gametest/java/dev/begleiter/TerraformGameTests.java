package dev.begleiter;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;

/** GameTests fürs Terraforming: Planieren, Auffüllen, Grube, Glätten, Hügel, Gebautes schonen, Chat. */
public class TerraformGameTests {

    @GameTest
    public void vanillaTerrainExceptionsKeepTheirNamespace(GameTestHelper h) {
        for (var block : new net.minecraft.world.level.block.Block[] {Blocks.GRAVEL, Blocks.CLAY,
                Blocks.SANDSTONE, Blocks.COBBLESTONE, Blocks.OBSIDIAN, Blocks.MAGMA_BLOCK})
            h.assertTrue(CompanionTerraform.natural(block.defaultBlockState()), "Vanilla terrain no longer removable: " + block);
        h.assertTrue(!CompanionTerraform.natural(RegistryIdTestItems.GRAVEL.defaultBlockState()),
                "Foreign gravel classified by vanilla name");
        h.assertTrue(!CompanionTerraform.natural(RegistryIdTestItems.COPPER_ORE.defaultBlockState()),
                "Foreign ore classified by vanilla suffix");
        h.assertTrue(!CompanionTerraform.natural(Blocks.CHEST.defaultBlockState()), "Built chest became terrain");
        // Baumaterial, das die Welt nicht erzeugt: bleibt beim Terraforming/Schematic-Platzieren stehen
        for (String id : new String[] {"stripped_oak_log", "oak_wood", "stripped_spruce_wood", "crimson_hyphae",
                "white_glazed_terracotta"})
            h.assertTrue(!CompanionTerraform.natural(vanilla(h, id).defaultBlockState()), "Building material became terrain: " + id);
        h.assertTrue(CompanionTerraform.natural(Blocks.OAK_LOG.defaultBlockState())
                && CompanionTerraform.natural(vanilla(h, "white_terracotta").defaultBlockState()), "Natural log/terracotta no longer terrain");
        h.succeed();
    }

    /**
     * Stämme zählen nur als Gelände, wenn natürliches Laub an ihnen hängt (gewachsener Baum). Von Hand gesetzte Stämme
     * (Blockhütte) und Stämme mit von Hand gesetztem Laub bleiben stehen – im Plan und beim Platzieren.
     */
    @GameTest
    public void handPlacedLogsAreNotTerrain(GameTestHelper h) {
        var log = Blocks.OAK_LOG.defaultBlockState();
        var naturalLeaves = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false)
                .setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 1);
        var placedLeaves = naturalLeaves.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        // Hütte: zwei Stämme übereinander, kein Laub
        h.setBlock(1, 1, 1, log); h.setBlock(1, 2, 1, log);
        // Baum: Stamm mit natürlichem Laub obendrauf
        h.setBlock(4, 1, 4, log); h.setBlock(4, 2, 4, log); h.setBlock(4, 3, 4, naturalLeaves);
        // Hecke an einem Pfosten: Laub von Hand gesetzt
        h.setBlock(6, 1, 1, log); h.setBlock(6, 2, 1, placedLeaves);
        var sl = h.getLevel();
        BlockPos cabin = h.absolutePos(new BlockPos(1, 1, 1)), tree = h.absolutePos(new BlockPos(4, 1, 4)),
                post = h.absolutePos(new BlockPos(6, 1, 1));
        h.assertTrue(!CompanionTerraform.natural(sl, cabin, log), "Hand-placed cabin log counted as terrain");
        h.assertTrue(CompanionTerraform.natural(sl, tree, log), "Grown tree trunk no longer terrain");
        h.assertTrue(!CompanionTerraform.natural(sl, post, log), "Log under hand-placed leaves counted as terrain");
        h.assertTrue(!CompanionPlacement.replaceable(sl, cabin, log, Blocks.STONE.defaultBlockState()), "Placement may replace a cabin log");
        h.succeed();
    }

    /** Vanilla-Block über seine ID; schlägt fehl, wenn es die ID nicht (mehr) gibt. */
    private static net.minecraft.world.level.block.Block vanilla(GameTestHelper h, String path) {
        var id = net.minecraft.resources.Identifier.withDefaultNamespace(path);
        h.assertTrue(net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id), "Unknown block id " + id);
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
    }

    /** Zwei Lagen Boden (y 0 Stein, y 1 Erde): Oberfläche ist y 1. */
    private static void ground(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) {
                h.setBlock(x, 0, z, Blocks.STONE);
                h.setBlock(x, 1, z, Blocks.DIRT);
            }
    }

    private static CompanionEntity companion(GameTestHelper h, BlockPos rel) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, rel);
        c.setPersistenceRequired();
        return c;
    }

    @GameTest
    public void planierenPlantAbtragenUndAuffuellen(GameTestHelper h) {
        ground(h);
        h.setBlock(2, 2, 2, Blocks.DIRT);   // Buckel
        h.setBlock(2, 3, 2, Blocks.STONE);
        h.setBlock(5, 1, 5, Blocks.AIR);    // Loch
        h.setBlock(6, 2, 6, Blocks.CHEST);  // Gebautes – bleibt
        BlockPos center = h.absolutePos(new BlockPos(4, 2, 4));
        var plan = CompanionTerraform.level(h.getLevel(), CompanionTerraform.Mode.FLATTEN, center, 8, 8,
                h.absolutePos(new BlockPos(0, 1, 0)).getY(), Blocks.DIRT);
        h.assertTrue(plan.blocks().get(h.absolutePos(new BlockPos(2, 2, 2))) != null
                && plan.blocks().get(h.absolutePos(new BlockPos(2, 2, 2))).isAir(), "Buckel nicht abgetragen");
        h.assertTrue(plan.blocks().get(h.absolutePos(new BlockPos(2, 3, 2))) != null, "Buckel oben fehlt");
        var hole = plan.blocks().get(h.absolutePos(new BlockPos(5, 1, 5)));
        h.assertTrue(hole != null && hole.is(Blocks.DIRT), "Loch nicht aufgefüllt");
        h.assertTrue(!plan.blocks().containsKey(h.absolutePos(new BlockPos(6, 2, 6))), "Kiste abgetragen");
        h.assertValueEqual(plan.skipped(), 1, "Gebautes gezählt");
        h.assertValueEqual(plan.cut(), 2, "Abtragen");
        h.assertValueEqual(plan.fill(), 1, "Auffüllen");
        h.succeed();
    }

    @GameTest(maxTicks = 900)
    public void planierenWirdGebaut(GameTestHelper h) {
        ground(h);
        h.setBlock(2, 2, 2, Blocks.DIRT);
        h.setBlock(3, 2, 2, Blocks.DIRT);
        h.setBlock(5, 1, 5, Blocks.AIR);
        h.setBlock(5, 1, 4, Blocks.AIR);
        CompanionEntity c = companion(h, new BlockPos(1, 2, 6));
        c.giveToInventory(new ItemStack(Items.WOODEN_SHOVEL));
        var plan = CompanionTerraform.level(h.getLevel(), CompanionTerraform.Mode.FLATTEN, h.absolutePos(new BlockPos(4, 2, 4)),
                8, 8, h.absolutePos(new BlockPos(0, 1, 0)).getY(), Blocks.DIRT);
        c.build = CompanionBuilder.createTerrain(h.getLevel(), "Terraforming", plan.blocks());
        c.addTask(Task.build());
        h.succeedWhen(() -> {
            h.assertTrue(h.getBlockState(new BlockPos(2, 2, 2)).isAir() && h.getBlockState(new BlockPos(3, 2, 2)).isAir(), "Buckel steht noch");
            h.assertTrue(!h.getBlockState(new BlockPos(5, 1, 5)).isAir() && !h.getBlockState(new BlockPos(5, 1, 4)).isAir(), "Loch offen");
            h.assertTrue(c.build == null, "Bauauftrag läuft noch");
        });
    }

    @GameTest(maxTicks = 900)
    public void nachPlanungGebautesBleibtStehen(GameTestHelper h) {
        ground(h);
        h.setBlock(2, 2, 2, Blocks.DIRT);   // Buckel
        h.setBlock(3, 2, 2, Blocks.DIRT);   // Buckel
        h.setBlock(5, 1, 5, Blocks.AIR);    // Loch
        CompanionEntity c = companion(h, new BlockPos(1, 2, 6));
        c.giveToInventory(new ItemStack(Items.WOODEN_SHOVEL));
        c.giveToInventory(new ItemStack(Items.DIRT, 4));
        var plan = CompanionTerraform.level(h.getLevel(), CompanionTerraform.Mode.FLATTEN, h.absolutePos(new BlockPos(4, 2, 4)),
                8, 8, h.absolutePos(new BlockPos(0, 1, 0)).getY(), Blocks.DIRT);
        h.assertTrue(plan.blocks().containsKey(h.absolutePos(new BlockPos(2, 2, 2))), "Buckel nicht geplant");
        h.assertTrue(plan.blocks().containsKey(h.absolutePos(new BlockPos(5, 1, 5))), "Loch nicht geplant");
        // Zwischen Planung und Ausführung baut der Spieler
        h.setBlock(2, 2, 2, Blocks.CHEST);
        h.setBlock(5, 1, 5, Blocks.STONE_BRICKS);
        c.build = CompanionBuilder.createTerrain(h.getLevel(), "Terraforming", plan.blocks());
        c.addTask(Task.build());
        h.succeedWhen(() -> {
            h.assertTrue(c.build == null, "Bauauftrag läuft noch");
            h.assertBlockPresent(Blocks.CHEST, new BlockPos(2, 2, 2));
            h.assertBlockPresent(Blocks.STONE_BRICKS, new BlockPos(5, 1, 5));
            h.assertTrue(h.getBlockState(new BlockPos(3, 2, 2)).isAir(), "Natürlicher Buckel steht noch");
        });
    }

    @GameTest
    public void auffuellenUndAbtragenNurEineRichtung(GameTestHelper h) {
        ground(h);
        h.setBlock(2, 2, 2, Blocks.DIRT);
        h.setBlock(5, 1, 5, Blocks.WATER);
        int y = h.absolutePos(new BlockPos(0, 1, 0)).getY();
        BlockPos center = h.absolutePos(new BlockPos(4, 2, 4));
        var fill = CompanionTerraform.level(h.getLevel(), CompanionTerraform.Mode.FILL, center, 8, 8, y, Blocks.DIRT);
        h.assertValueEqual(fill.cut(), 0, "Auffüllen trägt ab");
        h.assertTrue(fill.blocks().containsKey(h.absolutePos(new BlockPos(5, 1, 5))), "Wasser nicht aufgefüllt");
        var cut = CompanionTerraform.level(h.getLevel(), CompanionTerraform.Mode.CUT, center, 8, 8, y, Blocks.DIRT);
        h.assertValueEqual(cut.fill(), 0, "Abtragen füllt auf");
        h.assertValueEqual(cut.cut(), 1, "Abtragen");
        h.succeed();
    }

    @GameTest
    public void grubeAusheben(GameTestHelper h) {
        ground(h);
        BlockPos feet = h.absolutePos(new BlockPos(4, 2, 4));
        var plan = CompanionTerraform.pit(h.getLevel(), feet, 3, 3, 2);
        // Oberfläche y1 (Erde) und y0 (Stein) – 3x3x2
        h.assertValueEqual(plan.cut(), 18, "Grubenblöcke");
        h.assertTrue(plan.blocks().containsKey(h.absolutePos(new BlockPos(4, 0, 4))), "Grube nicht tief genug");
        h.assertTrue(!plan.blocks().containsKey(h.absolutePos(new BlockPos(2, 1, 4))), "Grube zu breit");
        h.succeed();
    }

    @GameTest
    public void glaettenUndHuegel(GameTestHelper h) {
        ground(h);
        h.setBlock(4, 2, 4, Blocks.DIRT); // einzelne Zacke
        h.setBlock(4, 3, 4, Blocks.DIRT);
        var smooth = CompanionTerraform.smooth(h.getLevel(), h.absolutePos(new BlockPos(4, 2, 4)), 5, 5, Blocks.DIRT);
        h.assertTrue(smooth.blocks().containsKey(h.absolutePos(new BlockPos(4, 3, 4))), "Zacke nicht geglättet");
        h.assertValueEqual(smooth.fill(), 0, "Glätten füllt hier nichts auf");
        var hill = CompanionTerraform.hill(h.getLevel(), h.absolutePos(new BlockPos(4, 2, 4)), 7, 3, Blocks.DIRT);
        h.assertValueEqual(hill.cut(), 0, "Hügel trägt ab");
        h.assertTrue(hill.blocks().containsKey(h.absolutePos(new BlockPos(4, 4, 4))), "Hügel-Spitze fehlt");
        h.assertTrue(!hill.blocks().containsKey(h.absolutePos(new BlockPos(1, 2, 1))), "Hügel bis in die Ecke");
        h.succeed();
    }

    @GameTest
    public void terraformBefehleImChat(GameTestHelper h) {
        h.assertValueEqual(CompanionTerraform.modeFrom(tok("Planier hier 15x15"), "planier hier 15x15"), CompanionTerraform.Mode.FLATTEN, "Planieren");
        h.assertValueEqual(CompanionTerraform.modeFrom(tok("Mach den Boden eben"), "mach den boden eben"), CompanionTerraform.Mode.FLATTEN, "Eben");
        h.assertValueEqual(CompanionTerraform.modeFrom(tok("Füll das Loch auf"), "fuell das loch auf"), CompanionTerraform.Mode.FILL, "Auffüllen");
        h.assertValueEqual(CompanionTerraform.modeFrom(tok("Trag den Hügel ab"), "trag den huegel ab"), CompanionTerraform.Mode.CUT, "Abtragen");
        h.assertValueEqual(CompanionTerraform.modeFrom(tok("Glätte das Gelände"), "glaette das gelaende"), CompanionTerraform.Mode.SMOOTH, "Glätten");
        h.assertValueEqual(CompanionTerraform.modeFrom(tok("Grab ein Loch 5x5x3"), "grab ein loch 5x5x3"), CompanionTerraform.Mode.PIT, "Grube");
        h.assertValueEqual(CompanionTerraform.modeFrom(tok("Schütte einen Hügel auf"), "schuette einen huegel auf"), CompanionTerraform.Mode.HILL, "Hügel");
        h.assertTrue(CompanionTerraform.modeFrom(tok("Füll die Kiste auf"), "fuell die kiste auf") == null, "Kiste als Terraforming");
        h.assertTrue(CompanionTerraform.modeFrom(tok("Bring mir Holz"), "bring mir holz") == null, "Holz als Terraforming");
        int[] s = CompanionTerraform.size(tok("Grab ein Loch 5x7x3"), 5);
        h.assertTrue(s[0] == 5 && s[1] == 7 && s[2] == 3, "Größe 5x7x3");
        h.assertValueEqual(CompanionTerraform.size(tok("Planier 99"), 11)[0], CompanionTerraform.MAX_SIZE, "Größe begrenzt");
        h.succeed();
    }

    @GameTest
    public void chatStartetPlanieren(GameTestHelper h) {
        ground(h);
        h.setBlock(5, 2, 5, Blocks.DIRT);
        CompanionEntity c = companion(h, new BlockPos(1, 2, 1));
        var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID());
        BlockPos at = h.absolutePos(new BlockPos(4, 2, 4));
        p.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        CompanionBrain.onChat(p, "@ Planier hier 7x7");
        h.assertTrue(c.build != null && c.build.name.startsWith("terraform:flatten:"), "Kein Planier-Auftrag");
        h.assertTrue(CompanionTexts.buildName(c.build.name).getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
                && tc.getKey().equals("server.begleiter.terrain.job"), "Auftragsname wird nicht übersetzt");
        h.assertTrue(c.build.terrainOnly, "Planier-Auftrag schont später Gebautes nicht");
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == TaskType.BUILD, "Bauaufgabe fehlt");
        h.succeed();
    }

    private static List<String> tok(String text) {
        return List.of(CompanionBrain.normalize(text).split(" "));
    }
}
