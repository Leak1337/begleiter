package dev.begleiter;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;

/** Exporter für Entwickler, nur auf Anforderung: baut eine eigene flache Ausstellungswelt und speichert sie vor dem Beenden. */
public final class SchematicShowcase {
    private static SchematicShowcase active;
    private final MinecraftServer server;
    private final ServerLevel level;
    private final Iterator<CompanionSchematicCatalogue.Entry> entries;
    private final List<Map<String, Object>> index = new ArrayList<>();
    private final List<int[]> forced = new ArrayList<>();
    private CompanionBuilder.BuildJob job;
    private CompanionSchematicCatalogue.Entry entry;
    private BlockPos center;
    private int plot, cursor, stopping;

    private SchematicShowcase(MinecraftServer server) {
        this.server = server; level = server.overworld();
        String only = System.getProperty("begleiter.showcaseOnly", "");
        entries = CompanionSchematicCatalogue.entries().stream()
                .filter(entry -> only.isBlank() || only.equals(entry.id())).iterator();
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
        level.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, server);
        level.getGameRules().set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, server);
        // Display first, operate deliberately: prevent imported TNT machines damaging the exhibit on arrival.
        level.getGameRules().set(GameRules.TNT_EXPLODES, false, server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "setworldspawn 0 -60 0");
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (Boolean.getBoolean("begleiter.showcaseExport")) active = new SchematicShowcase(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (active == null || active.server != server) return;
            try { active.tick(); }
            catch (Exception error) {
                com.mojang.logging.LogUtils.getLogger().error("SHOWCASE export failed", error);
                active = null; server.halt(false);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> active = null);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> giveGuide(handler.getPlayer()));
    }

    private static void giveGuide(net.minecraft.server.level.ServerPlayer player) {
        var path = player.level().getServer().getWorldPath(LevelResource.ROOT).resolve("schematic-showcase.json");
        if (!Files.isRegularFile(path)) return;
        for (int n = 0; n < player.getInventory().getContainerSize(); n++) {
            var book = player.getInventory().getItem(n).get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
            if (book != null && book.title().raw().equals("Schematic-Katalog")) return;
        }
        try {
            var pages = new ArrayList<net.minecraft.server.network.Filterable<net.minecraft.network.chat.Component>>();
            pages.add(net.minecraft.server.network.Filterable.passThrough(CompanionTexts.tr("showcase.book.intro")));
            for (var value : com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonArray()) {
                var row = value.getAsJsonObject();
                var entry = CompanionSchematicCatalogue.byId(row.get("id").getAsString());
                if (entry == null) continue;
                var link = CompanionTexts.tr("showcase.book.go").withStyle(style -> style.withUnderlined(true)
                        .withColor(net.minecraft.ChatFormatting.BLUE).withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(row.get("teleport").getAsString())));
                var page = net.minecraft.network.chat.Component.empty().append(entry.title()).append("\n\n")
                        .append(CompanionTexts.literal(entry.purpose())).append("\n\n").append(link);
                pages.add(net.minecraft.server.network.Filterable.passThrough(page));
                pages.add(net.minecraft.server.network.Filterable.passThrough(net.minecraft.network.chat.Component.empty()
                        .append(CompanionTexts.literal(entry.operation())).append("\n\n").append(CompanionTexts.literal(entry.requirement()))));
            }
            var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
            stack.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT,
                    new net.minecraft.world.item.component.WrittenBookContent(net.minecraft.server.network.Filterable.passThrough("Schematic-Katalog"),
                            "Begleiter", 0, pages, true));
            player.getInventory().add(stack);
        } catch (Exception error) { com.mojang.logging.LogUtils.getLogger().warn("Cannot create showcase guide", error); }
    }

    private void tick() throws java.io.IOException {
        if (stopping > 0) {
            if (--stopping == 0) { active = null; server.halt(false); }
            return;
        }
        if (job == null) {
            if (!entries.hasNext()) { finishWorld(); return; }
            entry = entries.next();
            var path = FabricLoader.getInstance().getModContainer(CompanionMod.MOD_ID).orElseThrow()
                    .findPath("data/begleiter/bundled_schematics/" + entry.file()).orElseThrow();
            var plan = CompanionSchematics.load(level, path);
            int col = plot % 6, row = plot / 6;
            center = new BlockPos(160 + col * 256, -60, 160 + row * 256);
            var placed = CompanionSchematics.placeAtFull(plan, center, Rotation.NONE, false);
            int minX = center.getX() - plan.sizeX() / 2 - 8, minZ = center.getZ() - plan.sizeZ() / 2 - 8;
            int maxX = center.getX() + (plan.sizeX() + 1) / 2 + 8, maxZ = center.getZ() + (plan.sizeZ() + 1) / 2 + 8;
            for (int x = (minX - 2) >> 4; x <= (maxX + 2) >> 4; x++)
                for (int z = (minZ - 2) >> 4; z <= (maxZ + 2) >> 4; z++) {
                    level.setChunkForced(x, z, true); level.getChunk(x, z); forced.add(new int[] {x, z});
                }
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                level.setBlock(new BlockPos(x, -61, z),
                        (x == minX || x == maxX || z == minZ || z == maxZ ? Blocks.QUARTZ_BLOCK : Blocks.SMOOTH_STONE).defaultBlockState(), CompanionBuilder.PLACE_FLAGS);
            job = CompanionBuilder.create(level, entry.file(), placed.blocks());
            placed.data().forEach((p, tag) -> job.data.put(p, tag.copy())); job.entities.addAll(placed.entities());
            cursor = 0;
            var rowData = new java.util.LinkedHashMap<String, Object>();
            rowData.put("number", plot + 1); rowData.put("file", entry.file()); rowData.put("id", entry.id());
            rowData.put("purpose", entry.purpose()); rowData.put("operation", entry.operation()); rowData.put("requirement", entry.requirement());
            rowData.put("teleport", "/tp @s " + center.getX() + " -58 " + (minZ - 3));
            rowData.put("x", center.getX()); rowData.put("y", -60); rowData.put("z", center.getZ());
            rowData.put("blocks", job.order.size()); rowData.put("entities", placed.entities().size());
            index.add(rowData);
            signs(new BlockPos(minX + 2, -60, minZ - 2), entry);
            com.mojang.logging.LogUtils.getLogger().info("SHOWCASE building {}/{} {} ({} blocks)", plot + 1,
                    CompanionSchematicCatalogue.entries().size(), entry.file(), job.order.size());
            plot++;
        }
        int end = Math.min(cursor + 8192, job.order.size());
        for (; cursor < end; cursor++) {
            var p = job.order.get(cursor);
            level.setBlock(p.pos(), p.state(), CompanionBuilder.PLACE_FLAGS);
            job.placed.add(p.pos()); CompanionBuilder.applyData(level, job, p.pos(), true, false);
        }
        if (cursor < job.order.size()) return;
        int wrong = CompanionBuilder.finish(level, job);
        CompanionBuilder.applyContents(level, job);
        var entities = CompanionBuilder.spawnEntities(level, job, null);
        index.getLast().put("wrongAfterFinish", wrong); index.getLast().put("spawned", entities.spawned());
        index.getLast().put("missingEntities", entities.missing());
        for (int[] p : forced) level.setChunkForced(p[0], p[1], false);
        forced.clear(); job = null;
        server.saveEverything(true, false, true);
    }

    private void signs(BlockPos at, CompanionSchematicCatalogue.Entry entry) {
        String title = entry.file().substring(0, entry.file().lastIndexOf('.'));
        String[] notes = {"Nr. " + (plot + 1) + ": " + title, entry.purpose(), entry.operation(), entry.requirement()};
        for (int n = 0; n < notes.length; n++) {
            BlockPos sign = at.offset(n * 3, 0, 0);
            level.setBlock(sign.below(), Blocks.SMOOTH_STONE.defaultBlockState(), CompanionBuilder.PLACE_FLAGS);
            var words = wrap(notes[n], 22);
            TestMap.sign(level, sign, 8, words);
        }
    }

    private static String[] wrap(String text, int width) {
        String[] lines = {"", "", "", ""}; int line = 0;
        for (String word : text.split(" ")) {
            if (lines[line].length() + word.length() + 1 > width && !lines[line].isEmpty()) {
                if (++line == 4) break;
            }
            lines[line] += (lines[line].isEmpty() ? "" : " ") + word;
        }
        return lines;
    }

    private void finishWorld() throws java.io.IOException {
        var path = server.getWorldPath(LevelResource.ROOT);
        Files.writeString(path.resolve("schematic-showcase.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(index));
        var introduction = FabricLoader.getInstance().getModContainer(CompanionMod.MOD_ID).orElseThrow()
                .findPath("data/begleiter/showcase_guide.txt").orElseThrow();
        StringBuilder guide = new StringBuilder(Files.readString(introduction));
        for (var row : index) guide.append(row.get("number")).append(". **").append(row.get("file")).append("** — ")
                .append(row.get("teleport")).append("\n   ").append(row.get("purpose")).append(" ")
                .append(row.get("operation")).append(" ").append(row.get("requirement")).append("\n\n");
        Files.writeString(path.resolve("SCHEMATICS.md"), guide);
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++)
            level.setBlock(new BlockPos(x, -61, z), Blocks.QUARTZ_BLOCK.defaultBlockState(), CompanionBuilder.PLACE_FLAGS);
        TestMap.sign(level, new BlockPos(0, -60, 3), 8, new String[] {"SCHEMATIC-SCHAU", plot + " Anlagen", "Kreativ + Flugmodus", "6 Spalten, 256 Abstand"});
        TestMap.sign(level, new BlockPos(3, -60, 3), 8, new String[] {"Bedienhinweise", "jeweils am Bauplatz", "Alle Koordinaten:", "SCHEMATICS.md"});
        String focus = System.getProperty("begleiter.showcaseFocus", "");
        for (var row : index) if (focus.equals(row.get("id"))) {
            var target = row.get("teleport").toString().split(" ");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "setworldspawn " + target[2] + " " + target[3] + " " + target[4]);
            break;
        }
        server.saveEverything(true, true, true);
        com.mojang.logging.LogUtils.getLogger().info("SHOWCASE COMPLETE {} plots saved to {}", plot, path);
        stopping = 5;
    }
}
