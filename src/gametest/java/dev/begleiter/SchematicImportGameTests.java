package dev.begleiter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Rotation;

/**
 * Echte Farm-Dateien aus run/schematics: jede muss sich laden und platzieren lassen und darf nicht leer sein.
 * Fehlende Dateien werden übersprungen (der Ordner gehört dem Spieler und liegt nicht im Repository).
 */
public class SchematicImportGameTests {
    private static final String[] FILES = {
            "Bonemeal Farm.litematic", "MidIronFarm.litematic", "Mob Farm-from-abfielder.litematic",
            "Villager-Breeding-Farm.schem", "Villagers-Farm.schem", "ALL Mob Farm.schem",
            "Automatic Bamboo Farm Flying Machine.schem", "Automatic Sugarcane Farm Flying Machine.schem",
            "Compact Automatic Bamboo Farm.schem"};

    private static Path find(String name) {
        for (var dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            var f = dir.resolve("run").resolve("schematics").resolve(name);
            if (Files.exists(f)) return f;
        }
        return null;
    }

    /**
     * Wirklich bauen: Bot im Kreativ-Bau mit Geist-Bewegung (Blöcke sofort), danach muss der Auftrag abgeschlossen sein
     * und fast jeder Block stimmen (Kolben/Wasser dürfen sich bewegt haben, daher 2 % Toleranz).
     */
    private static void buildCheck(GameTestHelper h, String name) {
        Path file = find(name);
        if (file == null) { LogUtils.getLogger().info("SCHEM-BAU {}: Datei fehlt, übersprungen", name); h.succeed(); return; }
        CompanionSchematics.Schematic plan;
        try { plan = CompanionSchematics.load(h.getLevel(), file); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
        for (int x = 0; x < 40; x++) for (int z = 0; z < 40; z++) h.setBlock(x, 0, z, net.minecraft.world.level.block.Blocks.SMOOTH_STONE);
        var c = h.spawn(CompanionMod.COMPANION, new net.minecraft.core.BlockPos(3, 1, 3));
        c.setPersistenceRequired();
        c.stay();
        c.buildModeOverride = true;
        c.buildMovementOverride = CompanionEntity.BuildMovement.GHOST;
        var placed = CompanionSchematics.placeFull(plan, h.absolutePos(new net.minecraft.core.BlockPos(48, 1, 2)),
                net.minecraft.core.Direction.SOUTH, Rotation.NONE, false);
        // Wie im Spiel auf festem Boden bauen: sonst fällt Redstone in der untersten Lage mangels Unterlage ab
        int bottom = placed.blocks().keySet().stream().mapToInt(net.minecraft.core.BlockPos::getY).min().orElse(0);
        for (var pos : placed.blocks().keySet()) {
            var below = new net.minecraft.core.BlockPos(pos.getX(), bottom - 1, pos.getZ());
            if (h.getLevel().getBlockState(below).isAir()) h.getLevel().setBlockAndUpdate(below, net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState());
        }
        c.build = CompanionBuilder.create(h.getLevel(), name, placed.blocks());
        c.build.entities.addAll(placed.entities());
        c.addTask(CompanionEntity.Task.build());
        var logged = new java.util.concurrent.atomic.AtomicBoolean();
        h.succeedWhen(() -> {
            h.assertTrue(c.build == null, "Bau nicht fertig: " + name);
            int wrong = 0;
            Map<String, Integer> diff = new TreeMap<>();
            for (var en : placed.blocks().entrySet()) {
                var now = h.getLevel().getBlockState(en.getKey());
                if (CompanionBuilder.matches(now, en.getValue())) continue;
                wrong++;
                diff.merge(BuiltInRegistries.BLOCK.getKey(en.getValue().getBlock()).getPath() + " -> "
                        + BuiltInRegistries.BLOCK.getKey(now.getBlock()).getPath(), 1, Integer::sum);
            }
            if (logged.compareAndSet(false, true))
                LogUtils.getLogger().info("SCHEM-BAU {}: {} von {} Blöcken weichen ab {}", name, wrong, placed.blocks().size(), diff);
            h.assertTrue(wrong * 50 <= placed.blocks().size(), name + ": " + wrong + " von " + placed.blocks().size() + " Blöcken falsch");
        });
    }

    @GameTest(maxTicks = 12000, structure = "begleiter-gametest:huge")
    public void baut_Bonemeal(GameTestHelper h) { buildCheck(h, "Bonemeal Farm.litematic"); }
    @GameTest(maxTicks = 12000, structure = "begleiter-gametest:huge")
    public void baut_MidIron(GameTestHelper h) { buildCheck(h, "MidIronFarm.litematic"); }
    @GameTest(maxTicks = 12000, structure = "begleiter-gametest:huge")
    public void baut_MobFarmAbfielder(GameTestHelper h) { buildCheck(h, "Mob Farm-from-abfielder.litematic"); }
    @GameTest(maxTicks = 12000, structure = "begleiter-gametest:huge")
    public void baut_VillagerBreeding(GameTestHelper h) { buildCheck(h, "Villager-Breeding-Farm.schem"); }
    @GameTest(maxTicks = 12000, structure = "begleiter-gametest:huge")
    public void baut_VillagersFarm(GameTestHelper h) { buildCheck(h, "Villagers-Farm.schem"); }
    @GameTest(maxTicks = 12000, structure = "begleiter-gametest:huge")
    public void baut_AllMobFarm(GameTestHelper h) { buildCheck(h, "ALL Mob Farm.schem"); }
    @GameTest(maxTicks = 12000, structure = "begleiter-gametest:huge")
    public void baut_BambooFlyingMachine(GameTestHelper h) { buildCheck(h, "Automatic Bamboo Farm Flying Machine.schem"); }
    @GameTest(maxTicks = 12000, structure = "begleiter-gametest:huge")
    public void baut_SugarcaneFlyingMachine(GameTestHelper h) { buildCheck(h, "Automatic Sugarcane Farm Flying Machine.schem"); }
    @GameTest(maxTicks = 12000, structure = "begleiter-gametest:huge")
    public void baut_CompactBamboo(GameTestHelper h) { buildCheck(h, "Compact Automatic Bamboo Farm.schem"); }

    @GameTest
    public void alleFarmDateienLassenSichLadenUndPlatzieren(GameTestHelper h) {
        var log = LogUtils.getLogger();
        for (String name : FILES) {
            Path file = find(name);
            if (file == null) { log.info("SCHEM {}: Datei fehlt, übersprungen", name); continue; }
            CompanionSchematics.Schematic plan;
            try { plan = CompanionSchematics.load(h.getLevel(), file); }
            catch (Exception e) { h.fail(name + " lässt sich nicht laden: " + e); return; }
            Map<String, Integer> types = new TreeMap<>();
            int solid = 0;
            for (var state : plan.blocks().values()) {
                if (state.isAir()) continue;
                solid++;
                types.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), 1, Integer::sum);
            }
            log.info("SCHEM {}: {}x{}x{}, {} Blöcke, {} Lebewesen, {} Block-Daten, Typen {}", name, plan.sizeX(), plan.sizeY(),
                    plan.sizeZ(), solid, plan.entities().size(), plan.data().size(), types);
            h.assertTrue(solid > 0, name + " ist leer (alle Blöcke unbekannt?)");
            var placed = CompanionSchematics.placeFull(plan, h.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2)),
                    net.minecraft.core.Direction.SOUTH, Rotation.NONE, false);
            h.assertTrue(placed.blocks().size() >= solid - 5, name + ": Platzierung verliert Blöcke " + placed.blocks().size() + "/" + solid);
        }
        h.succeed();
    }
}
