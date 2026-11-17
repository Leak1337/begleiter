package dev.begleiter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/** Dauerhafte Ausstellung neben der getrennten, zurücksetzbaren Redstone-Arbeitsfläche. */
final class TestMapExpansion {
    private TestMapExpansion() {}
    static final int WORK_SIZE = 32, PLOT_STEP = 16, COLUMNS = 4;
    static final String MARKER = "begleiter-redstone-campus.txt";
    record Exhibit(String id, Supplier<CompanionSchematics.Schematic> plan) {}
    private record Pending(ServerLevel level, CompanionBuilder.BuildJob job,
                           List<CompanionBuilder.Placement> plants, long readyAt) {}
    private static final List<Pending> pending = new ArrayList<>();
    private static Path marker;
    private static String pendingRevision;

    /** Freie Wiese für Bauaufträge von Hand (Farmen aus dem Menü), nördlich der Redstone-Arbeitsfläche. */
    static final int FIELD_SIZE = 128;
    /** Kantenlänge bis Stand "farmfeld-2" (Ecke bei z -32); beim Erweitern bleibt Gebautes darauf stehen. */
    static final int OLD_FIELD_SIZE = 64;
    static final String FIELD_MARKER = "begleiter-farmfeld.txt";

    static BlockPos workCorner(BlockPos origin) { return origin.offset(60, 0, 36); }
    /** Nordwest-Ecke: Das Feld wächst nach Norden und Osten, der Südrand bleibt bei z +31 (Wand und Arbeitsfläche). */
    static BlockPos fieldCorner(BlockPos origin) { return origin.offset(62, 0, 32 - FIELD_SIZE); }
    /** Ganze Fläche samt Weg und Bodenrand (x +58..+193, z -101..+35 bei 128). */
    static BlockPos fieldMin(BlockPos origin) { return fieldCorner(origin).offset(-4, 0, -5); }
    static BlockPos fieldMax(BlockPos origin) { return fieldCorner(origin).offset(FIELD_SIZE + 3, 0, FIELD_SIZE + 3); }
    static BlockPos galleryCorner(BlockPos origin) { return origin.offset(60, 0, 80); }
    static List<Exhibit> exhibits() {
        var result = new ArrayList<Exhibit>();
        for (var blueprint : CompanionMachines.ALL) result.add(new Exhibit(blueprint.id(), blueprint.plan()));
        result.add(new Exhibit("weizenfarm", () -> CompanionStructures.wheatFarm(7)));
        result.add(new Exhibit("weizenfarm_gross", () -> CompanionStructures.wheatFarm(9)));
        return List.copyOf(result);
    }
    static BlockPos plotCorner(BlockPos origin, int index) {
        return galleryCorner(origin).offset(index % COLUMNS * PLOT_STEP + 2, 0, index / COLUMNS * PLOT_STEP + 2);
    }
    static int rows() { return (exhibits().size() + COLUMNS - 1) / COLUMNS; }
    private static String revision(BlockPos origin) {
        return "campus-2:" + origin.toShortString() + ":" + String.join(",", exhibits().stream().map(Exhibit::id).toList());
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var it = pending.iterator();
            while (it.hasNext()) {
                var task = it.next();
                if (task.level().getServer() != server || task.level().getGameTime() < task.readyAt()) continue;
                for (var plant : task.plants()) {
                    var state = plant.state();
                    if (state.getBlock() instanceof CropBlock crop) state = crop.getStateForAge(crop.getMaxAge());
                    task.level().setBlock(plant.pos(), state, CompanionBuilder.PLACE_FLAGS);
                    task.job().placed.add(plant.pos());
                }
                CompanionBuilder.finish(task.level(), task.job());
                it.remove();
            }
            if (pending.isEmpty() && marker != null) {
                try { Files.writeString(marker, pendingRevision); }
                catch (java.io.IOException ex) { com.mojang.logging.LogUtils.getLogger().warn("Testmap-Ausstellung nicht markiert", ex); }
                marker = null; pendingRevision = null;
            }
        });
    }

    static void reset() { pending.clear(); marker = null; pendingRevision = null; }

    /** Einmalige, begrenzte Erweiterung vorhandener Testwelten; vorhandene Stationen bleiben an ihren Orten. */
    static String ensure(ServerPlayer player) {
        var level = player.level();
        var origin = TestMap.origin(level.getServer());
        if (origin == null) return "Erst /companion testmap ausführen.";
        Path file = level.getServer().getWorldPath(LevelResource.ROOT).resolve(MARKER);
        if (file.equals(marker)) return null;
        try { if (Files.exists(file) && Files.readString(file).equals(revision(origin))) return null; }
        catch (java.io.IOException ignored) {}
        TestMap.rebuild(level, origin, TestMap.station("redstone"));
        buildGallery(level, origin);
        marker = file; pendingRevision = revision(origin);
        return "Redstone-Bereich erweitert: 32×32 Baufläche und " + exhibits().size()
                + " beschriftete Ausstellungsplätze. /companion testmap ausstellung";
    }

    /** Farmfeld einmal je Welt anlegen (eigene Marke, damit Ausstellung und Arbeitsfläche unberührt bleiben). */
    static String ensureField(ServerPlayer player) {
        var level = player.level();
        var origin = TestMap.origin(level.getServer());
        if (origin == null) return "Erst /companion testmap ausführen.";
        Path file = level.getServer().getWorldPath(LevelResource.ROOT).resolve(FIELD_MARKER);
        String revision = "farmfeld-3:" + origin.toShortString();
        String old = null;
        try { if (Files.exists(file)) old = Files.readString(file); }
        catch (java.io.IOException ignored) {}
        if (revision.equals(old)) return null;
        // Bestehendes 64×64-Feld (Stand 1 oder 2): nach Norden und Osten erweitern, Gebautes darauf bleibt stehen
        boolean wasOne = ("farmfeld-1:" + origin.toShortString()).equals(old);
        boolean upgrade = wasOne || ("farmfeld-2:" + origin.toShortString()).equals(old);
        if (upgrade) {
            if (wasOne) buildResetWall(level, origin);
            expandField(level, origin);
        } else buildField(level, origin);
        try { Files.writeString(file, revision); }
        catch (java.io.IOException ex) { com.mojang.logging.LogUtils.getLogger().warn("Testmap-Farmfeld nicht markiert", ex); }
        return upgrade ? "Farmfeld auf " + FIELD_SIZE + "×" + FIELD_SIZE + " erweitert (nach Norden und Osten); Gebautes bleibt stehen."
                : "Farmfeld angelegt: " + FIELD_SIZE + "×" + FIELD_SIZE + " flache Wiese östlich vom Gelände. /companion testmap farmfeld";
    }

    // ------------------------------------------------------------------ Rücksetz-Wand am Farmfeld

    /** Was ein Knopf an der Wand zurücksetzt (Befehl {@code /companion testmap farmfeld <id>}). */
    enum FieldReset {
        ALLES("reset", "Feld", "komplett", "zurücksetzen", "Blöcke+Tiere"),
        ITEMS("items", "Gegenstände", "am Boden", "wegräumen", ""),
        TIERE("tiere", "Tiere und", "Fahrzeuge", "entfernen", "");

        final String id;
        final String[] sign;
        FieldReset(String id, String... sign) { this.id = id; this.sign = sign; }

        static FieldReset byId(String id) {
            for (FieldReset r : values()) if (r.id.equals(id)) return r;
            return null;
        }
    }

    /** Wand am Westrand des Felds (x +58, z +2..+8), Knöpfe nach Osten auf Befehlsblöcken (z +3, +5, +7). */
    static BlockPos resetWallButton(BlockPos origin, FieldReset reset) {
        return origin.offset(59, 1, 3 + reset.ordinal() * 2);
    }

    /**
     * Kleine Steinziegelwand mit je einem Knopf direkt auf einem Befehlsblock: Ein Knopf versorgt nur den Block,
     * an dem er hängt, deshalb liegen zwischen den Befehlsblöcken Ziegel (sonst lösten Nachbarn mit aus).
     */
    static void buildResetWall(ServerLevel level, BlockPos origin) {
        BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
        TestMap.fill(level, origin.offset(58, 0, 2), origin.offset(58, 2, 8), bricks);
        TestMap.fill(level, origin.offset(58, 3, 2), origin.offset(58, 3, 8), Blocks.STONE_BRICK_SLAB.defaultBlockState());
        for (FieldReset reset : FieldReset.values()) {
            BlockPos button = resetWallButton(origin, reset);
            BlockPos command = button.west();
            level.setBlock(command, Blocks.COMMAND_BLOCK.defaultBlockState()
                    .setValue(CommandBlock.FACING, net.minecraft.core.Direction.EAST), Block.UPDATE_ALL);
            if (level.getBlockEntity(command) instanceof net.minecraft.world.level.block.entity.CommandBlockEntity cb) {
                cb.getCommandBlock().setCommand("companion testmap farmfeld " + reset.id);
                cb.getCommandBlock().setTrackOutput(false);
                cb.setChanged();
            }
            level.setBlock(button, Blocks.STONE_BUTTON.defaultBlockState()
                    .setValue(ButtonBlock.FACING, net.minecraft.core.Direction.EAST)
                    .setValue(ButtonBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL), Block.UPDATE_ALL);
            wallSign(level, button.above(), reset.sign);
        }
        wallSign(level, origin.offset(59, 1, 8), new String[]{"Farmfeld", "Knopf drücken", "zum", "Zurücksetzen"});
    }

    private static void wallSign(ServerLevel level, BlockPos at, String[] text) {
        level.setBlock(at, Blocks.OAK_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, net.minecraft.core.Direction.EAST), Block.UPDATE_ALL);
        if (level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
            List<net.minecraft.network.chat.Component> lines = java.util.Arrays.stream(text)
                    .map(t -> (net.minecraft.network.chat.Component) net.minecraft.network.chat.Component.literal(t)).toList();
            sign.setText(new net.minecraft.world.level.block.entity.SignText(lines, lines,
                    net.minecraft.world.item.DyeColor.BLACK, false), net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
            sign.setWaxed(true);
            sign.setChanged();
            level.sendBlockUpdated(at, sign.getBlockState(), sign.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Ganze Fläche des Felds samt Weg rundum (wie {@link #buildField}), in der Höhe großzügig für hohe Farmen. */
    static net.minecraft.world.phys.AABB fieldArea(BlockPos origin) {
        BlockPos a = fieldMin(origin).below(TestMap.DEPTH), b = fieldMax(origin).above(TestMap.AIR);
        return new net.minecraft.world.phys.AABB(a.getX(), a.getY(), a.getZ(), b.getX() + 1, b.getY() + 1, b.getZ() + 1);
    }

    /** Wie fieldArea, aber bis zur Bauhöhe: Tiere/Items auf hohen Farmen (Mob-Farm-Plattformen) gehören dazu. */
    static net.minecraft.world.phys.AABB fieldColumn(ServerLevel level, BlockPos origin) {
        var area = fieldArea(origin);
        return new net.minecraft.world.phys.AABB(area.minX, area.minY, area.minZ, area.maxX, level.getMaxY() + 1, area.maxZ);
    }

    /**
     * Alles über der von buildField geleerten Luftschicht ({@link TestMap#AIR}) bis zum höchsten Block jeder
     * Säule wegräumen: hohe Farmen, Türme, Gerüste. Die Höhenkarte begrenzt die Suche, Luft wird nicht angefasst.
     */
    static int clearAbove(ServerLevel level, BlockPos origin) {
        int from = origin.getY() + TestMap.AIR + 1, cleared = 0;
        BlockPos a = fieldMin(origin), b = fieldMax(origin);
        var pos = new BlockPos.MutableBlockPos();
        for (int x = a.getX(); x <= b.getX(); x++) for (int z = a.getZ(); z <= b.getZ(); z++) {
            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
            for (int y = from; y < top; y++) {
                pos.set(x, y, z);
                BlockState s = level.getBlockState(pos);
                if (s.isAir()) continue;
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                cleared++;
            }
        }
        return cleared;
    }

    /**
     * Knopf/Befehl: Farmfeld ganz oder teilweise zurücksetzen. Begleiter bleiben stehen; ein Bauauftrag auf dem
     * Feld wird bei "reset" abgebrochen, sonst baute er das Gelöschte gleich wieder auf.
     */
    static String resetField(ServerLevel level, FieldReset what) {
        var origin = TestMap.origin(level.getServer());
        if (origin == null) return "Erst /companion testmap ausführen.";
        return resetField(level, origin, what);
    }

    static String resetField(ServerLevel level, BlockPos origin, FieldReset what) {
        var area = fieldColumn(level, origin);
        int bots = 0;
        if (what == FieldReset.ALLES) {
            for (var bot : level.getEntitiesOfClass(CompanionEntity.class, area.inflate(32))) {
                var job = bot.build;
                if (job != null && !job.order.isEmpty() && area.contains(net.minecraft.world.phys.Vec3.atCenterOf(job.order.get(0).pos()))) {
                    bot.cancelOrders();
                    bots++;
                }
            }
            // erst oben räumen: sonst fiele Sand/Kies aus der Höhe auf die frische Wiese
            clearAbove(level, origin);
            buildField(level, origin);
        }
        int removed = clearEntities(level, area, what);
        // Begleiter, die beim Rücksetzen im neuen Boden stecken, auf die Wiese stellen
        if (what == FieldReset.ALLES)
            for (var bot : level.getEntitiesOfClass(CompanionEntity.class, area))
                if (bot.getY() < origin.getY()) bot.snapTo(bot.getX(), origin.getY(), bot.getZ(), bot.getYRot(), bot.getXRot());
        return switch (what) {
            case ALLES -> "Farmfeld zurückgesetzt: Wiese neu, " + removed + " Gegenstände/Tiere entfernt"
                    + (bots > 0 ? ", " + bots + " Bauauftrag abgebrochen." : ".");
            case ITEMS -> "Farmfeld: " + removed + " Gegenstände weggeräumt.";
            case TIERE -> "Farmfeld: " + removed + " Tiere/Fahrzeuge entfernt.";
        };
    }

    static int clearEntities(ServerLevel level, net.minecraft.world.phys.AABB area, FieldReset what) {
        int removed = 0;
        for (var e : level.getEntities((net.minecraft.world.entity.Entity) null, area, e -> removable(e, what))) {
            e.discard();
            removed++;
        }
        return removed;
    }

    /** Spieler, Begleiter, zahme oder benannte Tiere bleiben immer. */
    private static boolean removable(net.minecraft.world.entity.Entity e, FieldReset what) {
        if (e instanceof net.minecraft.world.entity.player.Player || e instanceof CompanionEntity) return false;
        boolean loose = e instanceof net.minecraft.world.entity.item.ItemEntity || e instanceof net.minecraft.world.entity.ExperienceOrb
                || e instanceof net.minecraft.world.entity.projectile.Projectile;
        boolean creature = e instanceof net.minecraft.world.entity.Mob mob && !mob.hasCustomName() && !mob.isLeashed()
                && !(mob instanceof net.minecraft.world.entity.TamableAnimal pet && pet.isTame());
        boolean vehicle = e instanceof net.minecraft.world.entity.vehicle.VehicleEntity;
        return switch (what) {
            case ALLES -> loose || creature || vehicle;
            case ITEMS -> loose;
            case TIERE -> creature || vehicle;
        };
    }

    /**
     * Ebene Wiese {@value #FIELD_SIZE}×{@value #FIELD_SIZE} (x +62..+189, z -96..+31) mit Steinweg rundum und
     * Glowstone alle 16 Blöcke am Rand zum Abmessen. Der Boden reicht lückenlos bis an das Raster (x +51) und die
     * Arbeitsfläche (z +36), damit keine Insel über natürlichem Gelände entsteht.
     */
    static void buildField(ServerLevel level, BlockPos origin) {
        BlockPos min = fieldMin(origin), max = fieldMax(origin);
        TestMap.ground(level, min, new BlockPos(max.getX(), max.getY(), max.getZ() - 2));
        // Lücke bis zur Arbeitsfläche schließen (deren Weg bei x +58..+59 ab z +34 bleibt)
        TestMap.ground(level, origin.offset(60, 0, 34), origin.offset(91, 0, 35));
        buildFieldFrame(level, origin);
    }

    /**
     * Altes 64×64-Feld (Ecke x +62, z -32) nach Norden und Osten auf {@value #FIELD_SIZE}×{@value #FIELD_SIZE}
     * erweitern: nur die neuen Streifen neu aufbauen, der alte Weg wird innen zu Wiese. Gebautes auf dem Feld bleibt.
     */
    static void expandField(ServerLevel level, BlockPos origin) {
        BlockPos min = fieldMin(origin), max = fieldMax(origin);
        int oldMaxX = origin.getX() + 62 + OLD_FIELD_SIZE + 3, oldMinZ = origin.getZ() - 37;
        // Nordstreifen über die ganze Breite, dann Oststreifen neben dem alten Feld (z bis +33 wie beim Anlegen)
        TestMap.ground(level, min, new BlockPos(max.getX(), max.getY(), oldMinZ - 1));
        TestMap.ground(level, new BlockPos(oldMaxX + 1, min.getY(), oldMinZ), new BlockPos(max.getX(), max.getY(), max.getZ() - 2));
        // Alter Weg im Norden und Osten liegt jetzt mitten im Feld: zurück zu Gras (Westen und Süden bleiben Rand)
        BlockPos o = origin.offset(62, -1, -32);
        for (int x = -2; x <= OLD_FIELD_SIZE + 1; x++) for (int z = -2; z <= OLD_FIELD_SIZE + 1; z++) {
            boolean oldRing = x < 0 || z < 0 || x >= OLD_FIELD_SIZE || z >= OLD_FIELD_SIZE;
            boolean stillRing = x < 0 || z >= OLD_FIELD_SIZE;
            if (oldRing && !stillRing) level.setBlock(o.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        buildFieldFrame(level, origin);
    }

    /** Weg rundum, Glowstone, Verbindung zum Raster und Schild (alles ohne die Wiese selbst anzufassen). */
    private static void buildFieldFrame(ServerLevel level, BlockPos origin) {
        BlockPos a = fieldCorner(origin);
        int n = FIELD_SIZE;
        // Weg 2 breit rundum (x -2..n+1, z -2..n+1 relativ zur Ecke), innen bleibt Gras
        for (int x = -2; x <= n + 1; x++) for (int z = -2; z <= n + 1; z++)
            if (x < 0 || z < 0 || x >= n || z >= n)
                level.setBlock(a.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        // Glowstone im inneren Wegstreifen alle 16 Blöcke: Abschnitte abzählen ohne Messen
        for (int i = 0; i <= n; i += 16)
            for (BlockPos p : List.of(a.offset(i, -1, -1), a.offset(i, -1, n), a.offset(-1, -1, i), a.offset(n, -1, i)))
                level.setBlock(p, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        // Weg vom Raster (Ostrand x +50) auf Höhe der Mitte herüber
        TestMap.fill(level, origin.offset(51, -1, -1), origin.offset(59, -1, 1), Blocks.SMOOTH_STONE.defaultBlockState());
        TestMap.sign(level, origin.offset(59, 0, 2), 4, new String[]{"Farmfeld", n + "×" + n + " flach",
                "Farmen aus dem", "Menü platzieren"});
        buildResetWall(level, origin);
    }

    static void buildGallery(ServerLevel level, BlockPos origin) {
        BlockPos corner = galleryCorner(origin);
        int width = COLUMNS * PLOT_STEP, depth = rows() * PLOT_STEP;
        TestMap.ground(level, corner.offset(-2, 0, -4), corner.offset(width + 1, 0, depth + 1));
        // Helle Wege zwischen den Parzellen; keine Ausstellung liegt in der Rücksetzfläche der Tests.
        for (int x = 0; x <= width; x++) for (int z = -3; z <= depth; z++)
            if (x % PLOT_STEP < 2 || z < 0 || z % PLOT_STEP < 2)
                level.setBlock(corner.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        var exhibits = exhibits();
        for (int i = 0; i < exhibits.size(); i++) {
            var exhibit = exhibits.get(i);
            var at = plotCorner(origin, i);
            var plan = exhibit.plan().get();
            placeExhibit(level, at, plan);
            primeExhibit(level, at, exhibit.id());
            TestMap.sign(level, at.offset(6, 0, -1), 8, new String[]{exhibit.id(), plan.sizeX() + "×" + plan.sizeZ(),
                    "Bauplan im Menü", "zum Ausprobieren"});
            level.setBlock(at.offset(-1, 0, -1), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        fillGaps(level, origin);
        // Verbindung außerhalb der bisherigen Stationen (deren südlicher Rand liegt bei z +62).
        BlockPos roadStart = origin.offset(-34, 0, 72), roadEnd = origin.offset(60 + width, 0, 74);
        TestMap.ground(level, roadStart, roadEnd);
        TestMap.fill(level, roadStart.below(), roadEnd.below(), Blocks.SMOOTH_STONE.defaultBlockState());
        TestMap.ground(level, origin.offset(58, 0, 34), origin.offset(59, 0, 82));
        TestMap.fill(level, origin.offset(58, -1, 34), origin.offset(59, -1, 82), Blocks.SMOOTH_STONE.defaultBlockState());
        TestMap.sign(level, origin.offset(58, 0, 72), 8, new String[]{"Redstone-Campus", "Norden: Tests", "Süden: Ausstellung", exhibits.size() + " Anlagen"});
    }

    /**
     * Boden zwischen Raster (bis x +50, z +64), Arbeitsfläche (x +60..+91, z +36..+67) und Ausstellung (ab z +76):
     * sonst liegen die drei Teile als Inseln über dem natürlichen Gelände. Der alte 12×12-Redstone-Platz im Raster
     * wird geleert und zeigt per Schild auf die neue Fläche.
     */
    static void fillGaps(ServerLevel level, BlockPos o) {
        TestMap.ground(level, o.offset(51, 0, -37), o.offset(57, 0, 75));
        TestMap.ground(level, o.offset(-37, 0, 65), o.offset(50, 0, 75));
        TestMap.ground(level, o.offset(60, 0, 68), o.offset(125, 0, 75));
        TestMap.ground(level, o.offset(92, 0, 34), o.offset(125, 0, 67));
        BlockPos old = o.offset(-TestMap.HALF + 1, 0, -TestMap.HALF + 6 * TestMap.STEP + 1);
        TestMap.ground(level, old, old.offset(TestMap.CELL - 1, 0, TestMap.CELL - 1));
        TestMap.sign(level, old.offset(TestMap.CELL / 2, 0, -1), 8, new String[]{"Redstone", "umgezogen nach", "Osten (x +60)", "testmap redstone"});
    }

    static void placeExhibit(ServerLevel level, BlockPos at, CompanionSchematics.Schematic plan) {
        var blocks = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        plan.blocks().forEach((pos, state) -> blocks.put(at.offset(pos), state));
        var job = CompanionBuilder.create(level, plan.name(), blocks);
        var plants = new ArrayList<CompanionBuilder.Placement>();
        for (var placement : job.order) {
            Block block = placement.state().getBlock();
            if (block instanceof CropBlock || block instanceof StemBlock || block instanceof SugarCaneBlock || block instanceof BambooStalkBlock || block instanceof CactusBlock)
                plants.add(placement);
            else {
                level.setBlock(placement.pos(), placement.state(), CompanionBuilder.PLACE_FLAGS);
                if (!placement.state().isAir()) job.placed.add(placement.pos());
            }
        }
        CompanionBuilder.finish(level, job);
        pending.add(new Pending(level, job, plants, level.getGameTime() + 40));
    }

    /** Nur die Test-Ausstellung erhält Vorräte; normale Baupläne erzeugen keine kostenlosen Inhalte. */
    static void primeExhibit(ServerLevel level, BlockPos at, String id) {
        switch (id) {
            case "wasserernte", "karottenfarm", "kartoffelfarm" ->
                stock(level, at.offset(4, 2, 9), net.minecraft.world.item.Items.WATER_BUCKET, 1);
            case "schmelzofen", "hochofen", "raeucherofen" -> {
                stock(level, at.offset(4, 4, 3), id.equals("raeucherofen") ? net.minecraft.world.item.Items.BEEF : net.minecraft.world.item.Items.RAW_IRON, 16);
                stock(level, at.offset(3, 3, 3), net.minecraft.world.item.Items.COAL, 4);
            }
            case "komposter" -> stock(level, at.offset(3, 4, 3), net.minecraft.world.item.Items.WHEAT_SEEDS, 64);
            case "schaltlager" -> stock(level, at.offset(3, 2, 3), net.minecraft.world.item.Items.COBBLESTONE, 32);
            case "itemsortierer" -> {
                if (level.getBlockEntity(at.offset(3, 2, 3)) instanceof net.minecraft.world.Container filter) primeSorter(filter);
                if (level.getBlockEntity(at.offset(3, 4, 3)) instanceof net.minecraft.world.Container input) {
                    input.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 16));
                    input.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 16));
                    input.setChanged();
                }
            }
            case "portionierer" -> stock(level, at.offset(3, 3, 3), net.minecraft.world.item.Items.BREAD, 16);
            default -> { }
        }
    }

    /** Filtertrichter des Sortierers: 18 Zielitems (Eisen) und vier Einzelstücke, die nie im Eingang vorkommen. */
    static void primeSorter(net.minecraft.world.Container filter) {
        filter.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 18));
        var fillers = new net.minecraft.world.item.Item[]{net.minecraft.world.item.Items.STICK, net.minecraft.world.item.Items.FEATHER,
                net.minecraft.world.item.Items.STRING, net.minecraft.world.item.Items.BONE};
        for (int i = 0; i < fillers.length; i++) filter.setItem(i + 1, new net.minecraft.world.item.ItemStack(fillers[i]));
        filter.setChanged();
    }

    private static void stock(ServerLevel level, BlockPos at, net.minecraft.world.item.Item item, int count) {
        if (level.getBlockEntity(at) instanceof net.minecraft.world.Container container) {
            container.setItem(0, new net.minecraft.world.item.ItemStack(item, count));
            container.setChanged();
        }
    }

    static String visitField(ServerPlayer player) {
        var origin = TestMap.origin(player.level().getServer());
        if (origin == null) return "Erst /companion testmap ausführen.";
        ensureField(player);
        // Beim Südwest-Rand neben der Rücksetz-Wand (das Feld wächst von dort nach Norden und Osten)
        var at = origin.offset(65, 0, 5);
        player.teleportTo(player.level(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), 0, 20, true);
        return "Farmfeld: " + FIELD_SIZE + "×" + FIELD_SIZE + " flache Wiese, Glowstone am Rand alle 16 Blöcke.";
    }

    static String visit(ServerPlayer player, boolean gallery) {
        var origin = TestMap.origin(player.level().getServer());
        if (origin == null) return "Erst /companion testmap ausführen.";
        ensure(player);
        var at = gallery ? galleryCorner(origin).offset(6, 0, -2) : workCorner(origin).offset(6, 0, -2);
        player.teleportTo(player.level(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
        return gallery ? "Ausstellung: alle eingebauten Farmen und Maschinen stehen auf beschrifteten Parzellen."
                : "Redstone-Testfläche: 32×32 Blöcke. Die Ausstellung bleibt bei Testläufen erhalten.";
    }
}
