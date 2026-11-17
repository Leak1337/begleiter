package dev.begleiter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;

/**
 * Prüft einen ganzen Schematic-Ordner wie einen Kreativ-Bau des Begleiters: laden, gedreht platzieren (Blöcke in
 * Bau-Reihenfolge ohne Updates, Einstellungen, Abschluss-Updates, Behälterinhalte, Entities) und danach mit dem
 * Plan vergleichen. Nur mit -PschematicAudit=<Ordner>, sonst übersprungen. Ergebnis als "AUDIT"-Zeilen im Log.
 * Entities werden erst nach einigen Ticks gezählt: frisch geladene Chunks melden ihre Entities verzögert.
 */
public class SchematicFolderAudit {
    private record Built(String name, String blocks, AABB box, Map<String, Integer> planned, int plannedFramesWithItem,
                         CompanionBuilder.EntityResult spawn, List<long[]> forced, CompanionBuilder.BuildJob job,
                         int initialWrong, int initialRedstone, Map<String, Integer> dataDiff, java.util.Set<String> unknown) {}

    private static final int RUN = (int) (System.currentTimeMillis() / 1000 % 5_000);

    @GameTest(maxTicks = 400)
    public void ordnerWieEinKreativBauPruefen(GameTestHelper h) {
        String dir = System.getProperty("begleiter.schematicAudit");
        if (dir == null || dir.isBlank()) { h.succeed(); return; }
        var log = LogUtils.getLogger();
        CompanionSchematics.DEBUG_AUDIT = true;
        List<Path> files;
        try (var s = Files.list(Path.of(dir))) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        } catch (java.io.IOException e) { h.fail("Ordner nicht lesbar: " + e); return; }
        List<Built> built = new ArrayList<>();
        int slot = 0;
        for (Path file : files) {
            try {
                var b = build(h, file, slot++);
                if (b != null) built.add(b);
            } catch (Throwable t) { log.info("AUDIT {} | FEHLER {}", file.getFileName(), t.toString()); }
        }
        h.runAfterDelay(60, () -> {
            for (Built b : built) report(h, b);
            CompanionSchematics.DEBUG_AUDIT = false;
            h.succeed();
        });
    }

    private static Built build(GameTestHelper h, Path file, int slot) {
        var log = LogUtils.getLogger();
        var sl = h.getLevel();
        String name = file.getFileName().toString();
        long t0 = System.nanoTime();
        CompanionSchematics.Schematic plan;
        try { plan = CompanionSchematics.load(sl, file); }
        catch (Exception e) { log.info("AUDIT {} | LADEN FEHLGESCHLAGEN {}", name, e.toString()); return null; }
        long loadMs = (System.nanoTime() - t0) / 1_000_000;
        var unknown = CompanionSchematics.lastUnknownBlocks();
        // jeder Lauf an einer neuen Stelle: die Testwelt behält Chunks (samt Entities) früherer Läufe
        BlockPos origin = new BlockPos(40_000 + slot * 1_200, Math.min(100, sl.getMaxY() - plan.sizeY() - 2), 40_000 + RUN * 1_200);
        int half = Math.max(plan.sizeX(), plan.sizeZ()) / 2 + 2;
        var placed = CompanionSchematics.placeAtFull(plan, origin.offset(half, 0, half), Rotation.CLOCKWISE_90, false);
        if (placed.blocks().isEmpty()) { log.info("AUDIT {} | LEER (unbekannt: {})", name, unknown); return null; }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BlockPos p : placed.blocks().keySet()) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
        }
        List<long[]> forced = new ArrayList<>();
        for (int cx = (minX - 2) >> 4; cx <= (maxX + 2) >> 4; cx++)
            for (int cz = (minZ - 2) >> 4; cz <= (maxZ + 2) >> 4; cz++) {
                sl.setChunkForced(cx, cz, true);
                sl.getChunk(cx, cz);
                forced.add(new long[] {cx, cz});
            }
        // Boden wie bei einem Bau auf der Erde
        for (int x = minX - 1; x <= maxX + 1; x++)
            for (int z = minZ - 1; z <= maxZ + 1; z++)
                sl.setBlock(new BlockPos(x, minY - 1, z), Blocks.STONE.defaultBlockState(), CompanionBuilder.PLACE_FLAGS);
        var job = CompanionBuilder.create(sl, name, placed.blocks());
        placed.data().forEach((p, tag) -> job.data.put(p, tag.copy()));
        job.entities.addAll(placed.entities());
        for (var p : job.order) {
            if (p.state().isAir()) continue;
            sl.setBlock(p.pos(), p.state(), CompanionBuilder.PLACE_FLAGS);
            job.placed.add(p.pos());
            CompanionBuilder.applyData(sl, job, p.pos(), true, false);
        }
        // schon während des Setzens verlorene Blöcke (abgefallen, weil der Halt noch fehlte)
        int lostWhilePlacing = 0;
        for (var en : placed.blocks().entrySet())
            if (!en.getValue().isAir() && sl.getBlockState(en.getKey()).isAir()) lostWhilePlacing++;
        int wrongAfterFinish = CompanionBuilder.finish(sl, job);
        int filled = CompanionBuilder.applyContents(sl, job);
        var redstone = CompanionBuilder.redstoneMismatches(sl, job);
        Map<String, Integer> planned = new TreeMap<>();
        int plannedFramesWithItem = 0;
        for (var e : placed.entities()) {
            countEntities(e.tag(), planned);
            if (e.tag().getStringOr("id", "").endsWith("item_frame") && !e.tag().getCompoundOrEmpty("Item").isEmpty()) plannedFramesWithItem++;
        }
        var spawn = CompanionBuilder.spawnEntities(sl, job, null);

        Map<String, Integer> blockDiff = new TreeMap<>();
        int wrong = 0;
        for (var en : placed.blocks().entrySet()) {
            var now = sl.getBlockState(en.getKey());
            if (CompanionBuilder.matches(now, en.getValue())) continue;
            wrong++;
            blockDiff.merge(BuiltInRegistries.BLOCK.getKey(en.getValue().getBlock()).getPath() + "->"
                    + BuiltInRegistries.BLOCK.getKey(now.getBlock()).getPath(), 1, Integer::sum);
        }
        Map<String, Integer> dataDiff = new TreeMap<>();
        int dataChecked = 0, withItems = 0, itemsOk = 0, signs = 0;
        for (var en : placed.data().entrySet()) {
            var be = sl.getBlockEntity(en.getKey());
            String type = BuiltInRegistries.BLOCK.getKey(sl.getBlockState(en.getKey()).getBlock()).getPath();
            if (be == null) { dataDiff.merge(type + ":kein-BlockEntity", 1, Integer::sum); continue; }
            dataChecked++;
            CompoundTag want = CompanionSchematics.blockData(en.getValue());
            CompoundTag have = be.saveWithoutMetadata(sl.registryAccess());
            if (want.contains("front_text")) signs++;
            for (String key : want.keySet()) {
                if (key.equals("Items")) {
                    withItems++;
                    if (items(want.getListOrEmpty("Items")).equals(items(have.getListOrEmpty("Items")))) itemsOk++;
                    else dataDiff.merge(type + ":Items", 1, Integer::sum);
                    continue;
                }
                if (volatileKey(key)) continue;
                var a = want.get(key);
                var b = have.get(key);
                if (b == null || !a.equals(b)) dataDiff.merge(type + ":" + key, 1, Integer::sum);
            }
        }
        String blocks = String.format("%dx%dx%d %d Blöcke, laden %d ms | falsche Blöcke %d (beim Setzen abgefallen %d, Abschluss %d) %s | Redstone falsch %d | "
                        + "Block-Daten %d geprüft, Inhalte %d/%d ok, beschriftete Schilder %d, im Abschluss befüllt %d | Abweichungen %s | unbekannt %s",
                plan.sizeX(), plan.sizeY(), plan.sizeZ(), placed.blocks().size(), loadMs, wrong, lostWhilePlacing, wrongAfterFinish, top(blockDiff),
                redstone.size(), dataChecked, itemsOk, withItems, signs, filled, dataDiff, unknown);
        var box = new AABB(minX - 2, minY - 2, minZ - 2, maxX + 3, maxY + 3, maxZ + 3);
        return new Built(name, blocks, box, planned, plannedFramesWithItem, spawn, forced, job,
                wrong, redstone.size(), dataDiff, unknown);
    }

    private static void report(GameTestHelper h, Built b) {
        var sl = h.getLevel();
        Map<String, Integer> world = new TreeMap<>();
        int framesWithItem = 0;
        List<Entity> inBox = new ArrayList<>();
        for (Entity e : sl.getAllEntities())
            if (e != null && b.box().contains(e.position()) && !(e instanceof net.minecraft.world.entity.player.Player)) inBox.add(e);
        Map<String, Integer> nearby = new TreeMap<>();
        List<String> displaced = new ArrayList<>();
        for (Entity e : sl.getAllEntities()) if (e != null && b.box().inflate(256).contains(e.position())) {
            nearby.merge(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), 1, Integer::sum);
            if (!b.box().contains(e.position()) && displaced.size() < 8) displaced.add(e.getType() + " at " + e.position());
        }
        for (Entity e : inBox) {
            world.merge(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), 1, Integer::sum);
            if (e instanceof net.minecraft.world.entity.decoration.ItemFrame f && !f.getItem().isEmpty()) framesWithItem++;
        }
        LogUtils.getLogger().info("AUDIT {} | {} | Entities Plan {} -> Welt nach 3 s {} (gesetzt {}, fehlend {}), Rahmen mit Inhalt {}/{}",
                b.name(), b.blocks(), b.planned(), world, b.spawn().spawned(), b.spawn().missing(), framesWithItem, b.plannedFramesWithItem());
        Map<String, Integer> after = new TreeMap<>();
        int stableWrong = 0, controls = 0, controlFailures = 0;
        var movable = CompanionBuilder.pistonMovementPositions(b.job());
        for (var p : b.job().order) {
            var state = sl.getBlockState(p.pos());
            if (!p.state().isAir() && !movable.contains(p.pos()) && !CompanionBuilder.matches(state, p.state())) {
                stableWrong++;
                after.merge(p.state() + "->" + state, 1, Integer::sum);
            }
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.LeverBlock
                    || state.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock)) continue;
            controls++;
            if (!state.canSurvive(sl, p.pos())) { controlFailures++; continue; }
            var powered = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED, true);
            sl.setBlock(p.pos(), powered, 3);
            boolean signal = false;
            for (var face : net.minecraft.core.Direction.values()) if (sl.getSignal(p.pos(), face) > 0) signal = true;
            if (!signal) controlFailures++;
            sl.setBlock(p.pos(), state, 3);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("file", b.name()); result.put("initialWrong", b.initialWrong());
        result.put("initialRedstoneWrong", b.initialRedstone()); result.put("dataDiff", b.dataDiff());
        result.put("unknown", b.unknown()); result.put("expectedEntities", b.planned()); result.put("actualEntities", world);
        result.put("spawned", b.spawn().spawned()); result.put("missingEntities", b.spawn().missing());
        result.put("framesWithItem", framesWithItem); result.put("expectedFramesWithItem", b.plannedFramesWithItem());
        result.put("afterTicks", 60); result.put("postTickDifferences", after); result.put("postTickDifferenceCount", stableWrong);
        result.put("controls", controls); result.put("controlFailures", controlFailures);
        result.put("nearbyEntities", nearby); result.put("displacedExamples", displaced);
        LogUtils.getLogger().info("AUDIT_JSON {}", new com.google.gson.Gson().toJson(result));
        for (Entity e : inBox) e.discard();
        for (long[] c : b.forced()) sl.setChunkForced((int) c[0], (int) c[1], false);
    }

    private static void countEntities(CompoundTag tag, Map<String, Integer> counts) {
        counts.merge(tag.getStringOr("id", "?").replace("minecraft:", ""), 1, Integer::sum);
        for (var passenger : tag.getListOrEmpty("Passengers")) passenger.asCompound().ifPresent(t -> countEntities(t, counts));
    }

    /** Werte, die sich im Betrieb ändern (Brennzeit, Abkühlung ...) oder nur ein Server kennt; keine Einstellung. */
    private static boolean volatileKey(String key) {
        return switch (key) {
            case "TransferCooldown", "cooking_time_spent", "cooking_total_time", "lit_time_remaining", "lit_total_time",
                 "RecipesUsed", "crafting_ticks_remaining", "triggered", "Brewtime", "Fuel", "LootTableSeed",
                 "ticks_since_song_started", "Levels", "primary_effect", "secondary_effect", "flower_pos", "bees",
                 "SpawnPotentials", "SpawnData", "Delay", "MinSpawnDelay", "MaxSpawnDelay", "SpawnCount", "MaxNearbyEntities",
                 "RequiredPlayerRange", "SpawnRange", "components", "Book", "Page", "BrewTime", "BurnTime", "CookTime",
                 "CookTimeTotal", "Lock", "Paper.CookSpeedMultiplier" -> true;
            default -> false;
        };
    }

    /** Inhalt als Slot -> "id x Anzahl" (ohne Komponenten-Feinheiten). */
    private static Map<Integer, String> items(ListTag list) {
        Map<Integer, String> out = new TreeMap<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag s = list.getCompoundOrEmpty(i);
            out.put((int) s.getByteOr("Slot", (byte) -1), s.getStringOr("id", "?") + "x" + s.getIntOr("count", s.getByteOr("Count", (byte) 1)));
        }
        return out;
    }

    private static String top(Map<String, Integer> m) {
        return m.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(8)
                .map(e -> e.getKey() + "=" + e.getValue()).toList().toString();
    }
}
