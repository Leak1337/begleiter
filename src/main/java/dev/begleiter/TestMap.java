package dev.begleiter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * Testgelände (/companion testmap, nur Administratoren): 5 × 5 Stationen zu je 12 × 12 Blöcken (2 Blöcke Wiese
 * dazwischen), in der Mitte der Start. Jede Station hat ein Schild und lässt sich einzeln neu aufbauen – darauf
 * setzen die Szenarien in {@link TestScenarios} auf. Ursprung (Mitte) steht in der Welt in {@value #ORIGIN_FILE}.
 * <pre>
 *            West ............................................ Ost
 *  Nord  Wald       Erzwand    Tiere      Wasser     Lava
 *        Farm       Lager      Werkstatt  Hausplatz  Wasserfall
 *        Klettern   Höhle      START      Hindernis  Sprünge
 *        Schlucht   Labyrinth  Turm       Tunnel     Arena
 *  Süd   Klippe     Sand/Kies  Portal     Dorf       Freifläche   (+ Spalte 6: Abgrund Eis Fluss Kakteen Zucht Türhaus)
 *        Tiefmine   Eimer      Kiste      Dunkelwald Schacht      (Reihe 6)
 *        Redstone   Eisentür   Schematics MLG-Turm                (Reihe 7)
 * </pre>
 * Mit -Dbegleiter.testmap=true (Gradle: runTestmap) baut es sich beim ersten Betreten der Welt von selbst.
 */
public final class TestMap {
    private TestMap() {}

    static final int CELL = 12, STEP = 14, GRID = 5, AIR = 30, DEPTH = 15;
    /** Halbe Kantenlänge des ganzen Geländes. */
    static final int HALF = GRID * STEP / 2;
    static final String ORIGIN_FILE = "begleiter-testmap.txt";

    /** Eine Station: Name, Gitterplatz (Spalte von West, Reihe von Nord), Schildzeilen, Aufbau. */
    record Station(String id, int col, int row, String[] sign, Builder builder) {}

    @FunctionalInterface
    interface Builder { void build(ServerLevel sl, BlockPos c); }

    static final List<Station> STATIONS = List.of(
            new Station("wald", 0, 0, new String[]{"Wald", "Holz sammeln", "hoher Baum:", "Gerüst"}, TestMap::forest),
            new Station("erze", 1, 0, new String[]{"Erzwand", "Kohle Kupfer Eisen", "Gold Redstone", "Lapis Diamant"}, TestMap::ores),
            new Station("tiere", 2, 0, new String[]{"Tiere", "Kühe Schweine", "Schafe Hühner", "jagen / Essen"}, TestMap::animals),
            new Station("wasser", 3, 0, new String[]{"Wasser", "Becken 3 tief", "schwimmen oder", "herumlaufen"}, TestMap::water),
            new Station("lava", 4, 0, new String[]{"Lava", "Graben quer", "nicht hinein!", ""}, TestMap::lava),
            new Station("farm", 0, 1, new String[]{"Farm", "reifer Weizen", "ernten und", "neu säen"}, TestMap::farm),
            new Station("lager", 1, 1, new String[]{"Lager", "3 Kisten", "durcheinander", "sortieren"}, TestMap::storage),
            new Station("werkstatt", 2, 1, new String[]{"Werkstatt", "Werkbank, Ofen", "craften und", "schmelzen"}, TestMap::workshop),
            new Station("hausplatz", 3, 1, new String[]{"Hausplatz", "eben und frei", "Haus bauen", ""}, TestMap::houseSite),
            new Station("wasserfall", 4, 1, new String[]{"Wasserfall", "Klippe 8 hoch", "hoch kommen", ""}, TestMap::waterfall),
            new Station("klettern", 0, 2, new String[]{"Klettern", "Treppe bis 6", "Grube 4 tief", "mit Leiter"}, TestMap::climb),
            new Station("hoehle", 1, 2, new String[]{"Höhle", "dunkel, Skelett-", "Spawner unten", "Unterschlupf"}, TestMap::cave),
            new Station("start", 2, 2, new String[]{"Start", "Lager-Kiste", "/companion testmap", "liste"}, TestMap::start),
            new Station("hindernisse", 3, 2, new String[]{"Hindernisse", "Seelensand Netze", "Tür, Kies,", "niedriger Tunnel"}, TestMap::obstacles),
            new Station("spruenge", 4, 2, new String[]{"Sprünge", "Lücke 1 / 2 / 3", "Rinne 3 tief", "groß: parkourtest"}, TestMap::jumps),
            new Station("schlucht", 0, 3, new String[]{"Schlucht", "4 breit", "11 tief", "rüber kommen"}, TestMap::ravine),
            new Station("labyrinth", 1, 3, new String[]{"Labyrinth", "Mauern 3 hoch", "Weg finden", ""}, TestMap::maze),
            new Station("turm", 2, 3, new String[]{"Turm", "Obsidian 8 hoch", "nach oben", "(hochbauen)"}, TestMap::tower),
            new Station("tunnel", 3, 3, new String[]{"Tunnel", "dunkel, Kurve", "Fackeln setzen", ""}, TestMap::tunnel),
            new Station("arena", 4, 3, new String[]{"Arena", "Kämpfe gegen", "Zombies, Skelette", "Creeper"}, TestMap::arena),
            new Station("klippe", 0, 4, new String[]{"Klippe", "10 hoch", "heil runter", "kommen"}, TestMap::cliff),
            new Station("sand", 1, 4, new String[]{"Sand und Kies", "fallende Blöcke", "abbauen", ""}, TestMap::sand),
            new Station("portal", 2, 4, new String[]{"Portal", "Obsidian-Rahmen", "Feuerzeug", "in der Kiste"}, TestMap::portal),
            new Station("dorf", 3, 4, new String[]{"Dorf", "Bauer im Stand", "handeln", ""}, TestMap::village),
            new Station("frei", 4, 4, new String[]{"Freifläche", "bauen, Terrain", "platt machen", ""}, (sl, c) -> {}),
            // --- Spalte 6 (ganz im Osten)
            new Station("abgrund", 5, 0, new String[]{"Abgrund", "7 breit", "14 tief", "Brücke bauen"}, TestMap::abyss),
            new Station("eis", 5, 1, new String[]{"Eis", "Packeis und", "Pulverschnee", ""}, TestMap::ice),
            new Station("fluss", 5, 2, new String[]{"Fluss", "Strömung", "quer drüber", ""}, TestMap::river),
            new Station("kaktus", 5, 3, new String[]{"Kakteen", "und Beeren", "ohne Schaden", "durch"}, TestMap::cactus),
            new Station("zucht", 5, 4, new String[]{"Zucht", "zwei Kühe", "mit Weizen", "vermehren"}, TestMap::breeding),
            new Station("tuerhaus", 5, 5, new String[]{"Türhaus", "Hütte ohne Tür", "Tür einsetzen", "(oder bauen)"}, TestMap::doorHut),
            // --- Reihe 6 (ganz im Süden)
            new Station("tiefmine", 0, 5, new String[]{"Tiefmine", "Diamant 12 tief", "im Stein", ""}, TestMap::deepMine),
            new Station("eimer", 1, 5, new String[]{"Eimer", "Wasser holen,", "Lava löschen", ""}, TestMap::buckets),
            new Station("kiste", 2, 5, new String[]{"Kiste", "Eisen, Gold,", "Brot zum", "Herausnehmen"}, TestMap::supplyChest),
            new Station("dunkelwald", 3, 5, new String[]{"Dunkelwald", "dichtes Laub", "zwischen Stämmen", "durch"}, TestMap::darkForest),
            new Station("schacht", 4, 5, new String[]{"Schacht", "12 tief", "mit Leiter", "rauf kommen"}, TestMap::shaft),
            // --- Reihe 7: Redstone
            new Station("redstone", 0, 6, new String[]{"Redstone 32×32", "Schaltungen", "Farmen und", "Maschinen testen"}, TestMap::largeRedstonePad),
            new Station("eisentuer", 1, 6, new String[]{"Eisentür", "Druckplatten", "beidseitig", "durchgehen"}, TestMap::ironDoor),
            new Station("schematic", 2, 6, new String[]{"Schematics", ".nbt und .schem", "Block für Block", "nachbauen"}, TestMap::redstonePad),
            new Station("mlg", 3, 6, new String[]{"MLG-Turm", "28 hoch, unten", "Wiese und", "Wasserbecken"}, TestMap::mlgTower));

    private static BlockPos origin;

    public static void reset() { origin = null; TestMapExpansion.reset(); }

    public static Station station(String id) {
        for (Station s : STATIONS) if (s.id.equals(id)) return s;
        return null;
    }

    /** Nordwest-Ecke einer Station (Bodenhöhe = Füße). */
    static BlockPos corner(BlockPos o, Station s) {
        if (s.id().equals("redstone")) return TestMapExpansion.workCorner(o);
        return o.offset(-HALF + s.col * STEP + 1, 0, -HALF + s.row * STEP + 1);
    }

    static int stationSize(Station s) { return s.id().equals("redstone") ? TestMapExpansion.WORK_SIZE : CELL; }

    /** Ursprung des gebauten Geländes in dieser Welt, oder null. */
    static BlockPos origin(MinecraftServer server) {
        if (origin != null) return origin;
        Path f = server.getWorldPath(LevelResource.ROOT).resolve(ORIGIN_FILE);
        try {
            String[] p = Files.readString(f).trim().split("\\s+");
            origin = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (Exception e) {
            return null;
        }
        return origin;
    }

    /** Nur im Testmap-Start: beim ersten Betreten der Welt automatisch bauen. */
    public static void register() {
        TestMapExpansion.register();
        if (!Boolean.getBoolean("begleiter.testmap")) return;
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            ServerPlayer p = handler.getPlayer();
            if (origin(server) == null) p.sendSystemMessage(Component.literal(build(p)));
            String expanded = TestMapExpansion.ensure(p);
            if (expanded != null) p.sendSystemMessage(Component.literal(expanded));
            String field = TestMapExpansion.ensureField(p);
            if (field != null) p.sendSystemMessage(Component.literal(field));
            String view = System.getProperty("begleiter.testmap.view", "");
            if (view.equals("ausstellung") || view.equals("redstone"))
                p.sendSystemMessage(Component.literal(TestMapExpansion.visit(p, view.equals("ausstellung"))));
            if (view.equals("farmfeld")) p.sendSystemMessage(Component.literal(TestMapExpansion.visitField(p)));
            // -Dbegleiter.testmap.run=alle | name[,name]: Szenarien gleich nach dem Betreten starten
            String run = System.getProperty("begleiter.testmap.run", "").trim();
            if (run.isEmpty()) return;
            List<String> unknown = new java.util.ArrayList<>();
            List<TestScenarios.Scenario> picked = TestScenarios.pick(run, unknown);
            if (!unknown.isEmpty()) p.sendSystemMessage(Component.literal("Unbekannte Szenarien: " + String.join(", ", unknown)));
            if (!picked.isEmpty()) p.sendSystemMessage(Component.literal(TestScenarios.start(p, picked)));
        }));
    }

    // ------------------------------------------------------------------ Bauen

    /** Ganzes Gelände um den Spieler bauen, ihn auf den Start stellen und ihm einen Begleiter-Kern geben. */
    public static String build(ServerPlayer p) {
        ServerLevel sl = p.level();
        BlockPos o = p.blockPosition();
        TestScenarios.stop(null);
        int cols = STATIONS.stream().mapToInt(Station::col).max().orElse(0) + 1;
        int rows = STATIONS.stream().mapToInt(Station::row).max().orElse(0) + 1;
        ground(sl, o.offset(-HALF - 2, 0, -HALF - 2), o.offset(-HALF + cols * STEP + 1, 0, -HALF + rows * STEP + 1));
        for (Station s : STATIONS) rebuild(sl, o, s);
        origin = o.immutable();
        TestMapExpansion.reset();
        try {
            Files.writeString(sl.getServer().getWorldPath(LevelResource.ROOT).resolve(ORIGIN_FILE),
                    o.getX() + " " + o.getY() + " " + o.getZ() + "\n");
        } catch (java.io.IOException ignored) {}
        TestMapExpansion.ensure(p);
        TestMapExpansion.ensureField(p);
        p.teleportTo(sl, o.getX() + 0.5, o.getY(), o.getZ() + 0.5, java.util.Set.of(), 180f, 10f, true);
        if (!p.getInventory().contains(new ItemStack(CompanionMod.COMPANION_CORE)))
            p.getInventory().add(new ItemStack(CompanionMod.COMPANION_CORE));
        return "Testgelände gebaut: " + STATIONS.size() + " Stationen mit Schildern. Szenarien: /companion testmap liste, "
                + "/companion testmap szenario <name>, alle durch: /companion testmap alle. Kern im Inventar.";
    }

    /** Eine Station leeren (auch Mobs, Items, Pfeile darin) und neu aufbauen. */
    static void rebuild(ServerLevel sl, BlockPos o, Station s) {
        BlockPos c = corner(o, s);
        int size = stationSize(s);
        AABB box = new AABB(c.getX() - 1, c.getY() - DEPTH, c.getZ() - 1, c.getX() + size + 1, c.getY() + AIR, c.getZ() + size + 1);
        for (Entity e : sl.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player) && !(e instanceof CompanionEntity)))
            e.discard();
        ground(sl, c, c.offset(size - 1, 0, size - 1));
        // Abgerissene Kisten werfen ihren Inhalt aus: der gehört nicht zur neuen Station
        for (Entity e : sl.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) e.discard();
        s.builder.build(sl, c);
        String[] t = s.sign;
        // Schild an der Seite zur Mitte hin, Schrift nach außen zum Weg
        if (s.row < 2) sign(sl, c.offset(size / 2, 0, size), 0, t);
        else if (s.row > 2) sign(sl, c.offset(size / 2, 0, -1), 8, t);
        else if (s.col < 2) sign(sl, c.offset(CELL, 0, CELL / 2), 12, t);
        else if (s.col > 2) sign(sl, c.offset(-1, 0, CELL / 2), 4, t);
    }

    /** Luft darüber, Stein, Erde, Gras – wie flaches Land (a/b = Ecken auf Fußhöhe). */
    static void ground(ServerLevel sl, BlockPos a, BlockPos b) {
        fill(sl, a, b.above(AIR), Blocks.AIR.defaultBlockState());
        fill(sl, a.below(DEPTH), b.below(4), Blocks.STONE.defaultBlockState());
        fill(sl, a.below(3), b.below(2), Blocks.DIRT.defaultBlockState());
        fill(sl, a.below(), b.below(), Blocks.GRASS_BLOCK.defaultBlockState());
    }

    // ------------------------------------------------------------------ Stationen (c = Nordwest-Ecke, x/z 0..11)

    /** Mitte: Lager-Kiste mit Grundausstattung, Laternen. */
    private static void start(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(2, -1, 2), c.offset(9, -1, 9), Blocks.SMOOTH_STONE.defaultBlockState());
        chest(sl, c.offset(4, 0, 2), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIRT, 64),
                new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.COAL, 16), new ItemStack(Items.IRON_INGOT, 16),
                new ItemStack(Items.BREAD, 16), new ItemStack(Items.TORCH, 32), new ItemStack(Items.STICK, 16),
                new ItemStack(Items.WHEAT_SEEDS, 8));
        for (int[] l : new int[][]{{2, 2}, {9, 2}, {2, 9}, {9, 9}})
            sl.setBlock(c.offset(l[0], 0, l[1]), Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** Vier normale Bäume und einer 10 hoch (Gerüst). */
    private static void forest(ServerLevel sl, BlockPos c) {
        tree(sl, c.offset(2, 0, 2), Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5);
        tree(sl, c.offset(9, 0, 2), Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, 6);
        tree(sl, c.offset(2, 0, 9), Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, 5);
        tree(sl, c.offset(9, 0, 9), Blocks.OAK_LOG, Blocks.OAK_LEAVES, 5);
        tree(sl, c.offset(5, 0, 5), Blocks.OAK_LOG, Blocks.OAK_LEAVES, 10);
    }

    static BlockPos tallTree(BlockPos o) { return corner(o, station("wald")).offset(5, 0, 5); }

    private static void tree(ServerLevel sl, BlockPos base, Block log, Block leaves, int h) {
        BlockState lv = leaves.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        fill(sl, base.offset(-2, h - 2, -2), base.offset(2, h - 1, 2), lv);
        fill(sl, base.offset(-1, h, -1), base.offset(1, h, 1), lv);
        fill(sl, base, base.offset(0, h - 1, 0), log.defaultBlockState());
    }

    /** Steinwand mit allen Erzen, je drei übereinander. */
    private static void ores(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, 0, 2), c.offset(11, 5, 5), Blocks.STONE.defaultBlockState());
        Block[] ores = {Blocks.COAL_ORE, Blocks.COPPER_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.REDSTONE_ORE,
                Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE};
        for (int i = 0; i < ores.length; i++)
            for (int y = 0; y < 3; y++) sl.setBlock(c.offset(2 + i, y, 5), ores[i].defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void animals(ServerLevel sl, BlockPos c) {
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        for (int i = 0; i <= 11; i++) {
            sl.setBlock(c.offset(i, 0, 0), fence, Block.UPDATE_ALL); sl.setBlock(c.offset(i, 0, 11), fence, Block.UPDATE_ALL);
            sl.setBlock(c.offset(0, 0, i), fence, Block.UPDATE_ALL); sl.setBlock(c.offset(11, 0, i), fence, Block.UPDATE_ALL);
        }
        sl.setBlock(c.offset(5, 0, 11), Blocks.OAK_FENCE_GATE.defaultBlockState(), Block.UPDATE_ALL);
        spawn(sl, EntityTypes.COW, c.offset(3, 0, 3), 2);
        spawn(sl, EntityTypes.PIG, c.offset(8, 0, 3), 2);
        spawn(sl, EntityTypes.SHEEP, c.offset(3, 0, 8), 2);
        spawn(sl, EntityTypes.CHICKEN, c.offset(8, 0, 8), 3);
    }

    /** Becken über die ganze Breite, 3 tief (z 3..8). */
    private static void water(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, -3, 3), c.offset(11, -1, 8), Blocks.WATER.defaultBlockState());
    }

    /** Lavagraben über die ganze Breite, 2 tief (z 4..7) – nur außen herum. */
    private static void lava(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, -3, 4), c.offset(11, -3, 7), Blocks.STONE.defaultBlockState());
        fill(sl, c.offset(0, -2, 4), c.offset(11, -1, 7), Blocks.LAVA.defaultBlockState());
    }

    /** 9 × 9 Ackerland mit Wasser in der Mitte und reifem Weizen. */
    private static void farm(ServerLevel sl, BlockPos c) {
        BlockState wheat = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
        for (int x = 1; x <= 9; x++)
            for (int z = 1; z <= 9; z++) {
                if (x == 5 && z == 5) { sl.setBlock(c.offset(x, -1, z), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL); continue; }
                sl.setBlock(c.offset(x, -1, z), Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
                sl.setBlock(c.offset(x, 0, z), wheat, Block.UPDATE_ALL);
            }
    }

    static BlockPos farmCenter(BlockPos o) { return corner(o, station("farm")).offset(5, 0, 5); }

    /** Drei Kisten mit wild gemischtem Inhalt. */
    private static void storage(ServerLevel sl, BlockPos c) {
        chest(sl, c.offset(3, 0, 5), new ItemStack(Items.COBBLESTONE, 40), new ItemStack(Items.ROTTEN_FLESH, 7),
                new ItemStack(Items.OAK_LOG, 12), new ItemStack(Items.BONE, 5), new ItemStack(Items.DIRT, 30));
        chest(sl, c.offset(5, 0, 5), new ItemStack(Items.IRON_INGOT, 9), new ItemStack(Items.DIRT, 20),
                new ItemStack(Items.STRING, 4), new ItemStack(Items.COBBLESTONE, 25), new ItemStack(Items.WHEAT_SEEDS, 11));
        chest(sl, c.offset(7, 0, 5), new ItemStack(Items.COAL, 14), new ItemStack(Items.OAK_LOG, 6),
                new ItemStack(Items.ROTTEN_FLESH, 3), new ItemStack(Items.GRAVEL, 16), new ItemStack(Items.RAW_IRON, 5));
    }

    static BlockPos storageChest(BlockPos o) { return corner(o, station("lager")).offset(3, 0, 5); }

    private static void workshop(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(2, -1, 2), c.offset(9, -1, 9), Blocks.OAK_PLANKS.defaultBlockState());
        sl.setBlock(c.offset(4, 0, 3), Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
        sl.setBlock(c.offset(6, 0, 3), Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);
        chest(sl, c.offset(8, 0, 3), new ItemStack(Items.RAW_IRON, 8), new ItemStack(Items.COAL, 8),
                new ItemStack(Items.OAK_PLANKS, 16), new ItemStack(Items.COBBLESTONE, 16));
    }

    private static void houseSite(ServerLevel sl, BlockPos c) {
        BlockState w = Blocks.WOOL.pick(DyeColor.RED).defaultBlockState();
        for (int[] e : new int[][]{{0, 0}, {11, 0}, {0, 11}, {11, 11}}) sl.setBlock(c.offset(e[0], -1, e[1]), w, Block.UPDATE_ALL);
    }

    static BlockPos houseSite(BlockPos o) { return corner(o, station("hausplatz")).offset(3, 0, 3); }

    /** Steinklippe 8 hoch (x 0..5), Wasserfall an der Kante. */
    private static void waterfall(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, 0, 0), c.offset(5, 7, 11), Blocks.STONE.defaultBlockState());
        sl.setBlock(c.offset(6, 7, 5), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** Treppe aus 1er-Stufen bis 6 hoch (Goldblock oben), Mauer 2 hoch, Grube 4 tief mit Leiter. */
    private static void climb(ServerLevel sl, BlockPos c) {
        BlockState st = Blocks.COBBLESTONE.defaultBlockState();
        for (int i = 0; i < 6; i++) fill(sl, c.offset(1 + i, 0, 1), c.offset(1 + i, i, 3), st);
        fill(sl, c.offset(7, 0, 1), c.offset(9, 5, 3), st);
        sl.setBlock(c.offset(8, 6, 2), Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        fill(sl, c.offset(1, 0, 6), c.offset(3, 1, 6), Blocks.STONE_BRICKS.defaultBlockState());
        fill(sl, c.offset(6, -4, 6), c.offset(8, -1, 8), Blocks.AIR.defaultBlockState());
        fill(sl, c.offset(9, -4, 6), c.offset(10, -1, 8), Blocks.STONE.defaultBlockState());
        for (int y = -4; y <= -1; y++)
            sl.setBlock(c.offset(9, y, 7), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST), Block.UPDATE_ALL);
    }

    /** Treppe 12 tief in einen dunklen Raum mit Skelett-Spawner, Eisen und Diamant. */
    private static void cave(ServerLevel sl, BlockPos c) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int i = 0; i < 11; i++) fill(sl, c.offset(1, -1 - i, 1 + i), c.offset(2, 1 - i, 1 + i), air);
        fill(sl, c.offset(1, -12, 9), c.offset(11, -9, 11), air);
        fill(sl, c.offset(3, -12, 3), c.offset(11, -9, 11), air);
        for (int i = 8; i < 11; i++) fill(sl, c.offset(1, -12, 1 + i), c.offset(2, -2 - i, 1 + i), Blocks.COBBLESTONE.defaultBlockState());
        BlockPos spawner = c.offset(10, -12, 4);
        sl.setBlock(spawner, Blocks.SPAWNER.defaultBlockState(), Block.UPDATE_ALL);
        if (sl.getBlockEntity(spawner) instanceof SpawnerBlockEntity s) {
            s.setEntityId(EntityTypes.SKELETON, sl.getRandom());
            s.setChanged();
        }
        sl.setBlock(c.offset(11, -12, 8), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        sl.setBlock(c.offset(11, -11, 8), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        sl.setBlock(c.offset(3, -13, 3), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
    }

    static BlockPos caveRoom(BlockPos o) { return corner(o, station("hoehle")).offset(6, -12, 7); }

    /** Seelensand, Spinnweben, Kies-Säule, Bretterwand mit Tür, Tunnel nur 1 hoch. Ziel hinter allem (z 11). */
    private static void obstacles(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, -1, 1), c.offset(11, -1, 1), Blocks.SOUL_SAND.defaultBlockState());
        for (int x = 2; x <= 9; x += 3) sl.setBlock(c.offset(x, 0, 3), Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
        fill(sl, c.offset(11, 0, 3), c.offset(11, 3, 3), Blocks.GRAVEL.defaultBlockState());
        fill(sl, c.offset(0, 0, 5), c.offset(11, 2, 5), Blocks.OAK_PLANKS.defaultBlockState());
        BlockPos door = c.offset(5, 0, 5);
        sl.setBlock(door, Blocks.OAK_DOOR.defaultBlockState(), Block.UPDATE_ALL);
        sl.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        // Tunnel 1 hoch quer über die Breite: graben oder durch die Lücke ganz rechts
        fill(sl, c.offset(0, 0, 8), c.offset(10, 2, 8), Blocks.STONE.defaultBlockState());
        sl.setBlock(c.offset(5, 0, 8), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** Rinnen 3 tief quer über die Breite: Lücke 1 (z 2), 2 (z 5..6), 3 (z 9..11). */
    private static void jumps(ServerLevel sl, BlockPos c) {
        BlockState air = Blocks.AIR.defaultBlockState();
        fill(sl, c.offset(0, -3, 2), c.offset(11, -1, 2), air);
        fill(sl, c.offset(0, -3, 5), c.offset(11, -1, 6), air);
        fill(sl, c.offset(0, -3, 8), c.offset(11, -1, 10), air);
    }

    private static void ravine(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, -11, 4), c.offset(11, -1, 7), Blocks.AIR.defaultBlockState());
    }

    /** Schlangenlinie: Außenmauer, Querwände bei z 3, 6, 9 mit Lücke abwechselnd rechts/links. Start (1,1), Ziel (1,10). */
    private static void maze(ServerLevel sl, BlockPos c) {
        BlockState w = Blocks.STONE_BRICKS.defaultBlockState();
        fill(sl, c.offset(0, 0, 0), c.offset(11, 2, 0), w);
        fill(sl, c.offset(0, 0, 11), c.offset(11, 2, 11), w);
        fill(sl, c.offset(0, 0, 0), c.offset(0, 2, 11), w);
        fill(sl, c.offset(11, 0, 0), c.offset(11, 2, 11), w);
        fill(sl, c.offset(1, 0, 3), c.offset(9, 2, 3), w);
        fill(sl, c.offset(2, 0, 6), c.offset(10, 2, 6), w);
        fill(sl, c.offset(1, 0, 9), c.offset(9, 2, 9), w);
    }

    /** Obsidian-Säule 8 hoch mit 3 × 3 Plattform oben. */
    private static void tower(ServerLevel sl, BlockPos c) {
        BlockState ob = Blocks.OBSIDIAN.defaultBlockState();
        fill(sl, c.offset(5, 0, 5), c.offset(5, 7, 5), ob);
        fill(sl, c.offset(4, 7, 4), c.offset(6, 7, 6), ob);
        sl.setBlock(c.offset(5, 7, 5), Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** Steinhügel mit dunklem Gang (1 breit, 2 hoch) und einer Kurve. */
    private static void tunnel(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, 0, 0), c.offset(11, 4, 11), Blocks.STONE.defaultBlockState());
        BlockState air = Blocks.AIR.defaultBlockState();
        fill(sl, c.offset(0, 0, 2), c.offset(8, 1, 2), air);
        fill(sl, c.offset(8, 0, 2), c.offset(8, 1, 9), air);
        fill(sl, c.offset(8, 0, 9), c.offset(11, 1, 9), air);
    }

    /** Bruchstein-Mauer 3 hoch rundum, Fackeln (Mobs setzt das Szenario hinein). */
    private static void arena(ServerLevel sl, BlockPos c) {
        BlockState w = Blocks.COBBLESTONE.defaultBlockState();
        fill(sl, c.offset(0, 0, 0), c.offset(11, 2, 0), w);
        fill(sl, c.offset(0, 0, 11), c.offset(11, 2, 11), w);
        fill(sl, c.offset(0, 0, 0), c.offset(0, 2, 11), w);
        fill(sl, c.offset(11, 0, 0), c.offset(11, 2, 11), w);
        for (int i = 2; i <= 9; i += 7)
            for (int[] t : new int[][]{{i, 3, 0}, {i, 3, 11}, {0, 3, i}, {11, 3, i}})
                sl.setBlock(c.offset(t[0], t[1], t[2]), Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** Steinblock 10 hoch über die westliche Hälfte. */
    private static void cliff(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, 0, 0), c.offset(5, 9, 11), Blocks.STONE.defaultBlockState());
    }

    private static void sand(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(2, 0, 2), c.offset(9, 1, 9), Blocks.SAND.defaultBlockState());
        fill(sl, c.offset(3, 2, 3), c.offset(8, 2, 8), Blocks.GRAVEL.defaultBlockState());
    }

    private static void portal(ServerLevel sl, BlockPos c) {
        BlockState ob = Blocks.OBSIDIAN.defaultBlockState();
        fill(sl, c.offset(4, 0, 6), c.offset(7, 0, 6), ob);
        fill(sl, c.offset(4, 4, 6), c.offset(7, 4, 6), ob);
        fill(sl, c.offset(4, 1, 6), c.offset(4, 3, 6), ob);
        fill(sl, c.offset(7, 1, 6), c.offset(7, 3, 6), ob);
        chest(sl, c.offset(2, 0, 4), new ItemStack(Items.FLINT_AND_STEEL), new ItemStack(Items.OBSIDIAN, 10));
    }

    /** Stand mit Dach, darin ein Bauer mit Komposter. */
    private static void village(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(3, -1, 3), c.offset(8, -1, 8), Blocks.OAK_PLANKS.defaultBlockState());
        for (int[] p : new int[][]{{3, 3}, {8, 3}, {3, 8}, {8, 8}}) fill(sl, c.offset(p[0], 0, p[1]), c.offset(p[0], 2, p[1]), Blocks.OAK_LOG.defaultBlockState());
        fill(sl, c.offset(3, 3, 3), c.offset(8, 3, 8), Blocks.OAK_SLAB.defaultBlockState());
        sl.setBlock(c.offset(5, 0, 5), Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
        spawn(sl, EntityTypes.VILLAGER, c.offset(6, 0, 6), 1);
    }

    /** Grube quer über die ganze Breite, 7 breit (z 3..9) und 14 tief. */
    private static void abyss(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, -14, 3), c.offset(11, -1, 9), Blocks.AIR.defaultBlockState());
    }

    /** Packeis-Boden, quer ein Streifen Pulverschnee 2 tief (z 5..6). */
    private static void ice(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, -1, 1), c.offset(11, -1, 10), Blocks.PACKED_ICE.defaultBlockState());
        fill(sl, c.offset(0, -2, 5), c.offset(11, -1, 6), Blocks.POWDER_SNOW.defaultBlockState());
    }

    /** Rinne 4 breit (z 4..7), 1 tief; Quellen im Westen, das Wasser fließt nach Osten (Strömung). */
    private static void river(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, -2, 4), c.offset(11, -2, 7), Blocks.STONE.defaultBlockState());
        fill(sl, c.offset(0, -1, 4), c.offset(11, -1, 7), Blocks.AIR.defaultBlockState());
        fill(sl, c.offset(12, -1, 4), c.offset(12, -1, 7), Blocks.STONE.defaultBlockState());
        for (int z = 4; z <= 7; z++) sl.setBlock(c.offset(0, -1, z), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** Sandfeld mit Kakteen (2 hoch) im Raster und reifen Beerensträuchern dazwischen. */
    private static void cactus(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, -1, 2), c.offset(11, -1, 9), Blocks.SAND.defaultBlockState());
        BlockState berry = Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3);
        for (int x = 0; x <= 11; x++)
            for (int z = 2; z <= 9; z++) {
                if (x % 2 == 0 && z % 2 == 0) fill(sl, c.offset(x, 0, z), c.offset(x, 1, z), Blocks.CACTUS.defaultBlockState());
                else if ((x + z) % 4 == 1) {
                    sl.setBlock(c.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                    sl.setBlock(c.offset(x, 0, z), berry, Block.UPDATE_CLIENTS);
                }
            }
    }

    private static void breeding(ServerLevel sl, BlockPos c) {
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        for (int i = 1; i <= 10; i++) {
            sl.setBlock(c.offset(i, 0, 1), fence, Block.UPDATE_ALL); sl.setBlock(c.offset(i, 0, 10), fence, Block.UPDATE_ALL);
            sl.setBlock(c.offset(1, 0, i), fence, Block.UPDATE_ALL); sl.setBlock(c.offset(10, 0, i), fence, Block.UPDATE_ALL);
        }
        sl.setBlock(c.offset(5, 0, 10), Blocks.OAK_FENCE_GATE.defaultBlockState(), Block.UPDATE_ALL);
        spawn(sl, EntityTypes.COW, c.offset(4, 0, 4), 2);
    }

    /** Hütte 5 × 5 aus Bruchstein mit Türöffnung im Norden (ohne Tür). */
    private static void doorHut(ServerLevel sl, BlockPos c) {
        BlockState w = Blocks.COBBLESTONE.defaultBlockState();
        fill(sl, c.offset(3, -1, 3), c.offset(7, -1, 7), w);
        fill(sl, c.offset(3, 0, 3), c.offset(7, 2, 7), w);
        fill(sl, c.offset(4, 0, 4), c.offset(6, 2, 6), Blocks.AIR.defaultBlockState());
        fill(sl, c.offset(3, 3, 3), c.offset(7, 3, 7), w);
        fill(sl, c.offset(5, 0, 3), c.offset(5, 1, 3), Blocks.AIR.defaultBlockState());
        sl.setBlock(c.offset(5, 2, 5), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), Block.UPDATE_ALL);
    }

    static BlockPos hutDoor(BlockPos o) { return corner(o, station("tuerhaus")).offset(5, 0, 3); }

    /** Nur Stein: Diamant 12 tief, darüber Eisen auf halber Höhe. */
    private static void deepMine(ServerLevel sl, BlockPos c) {
        sl.setBlock(c.offset(6, -12, 6), Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        sl.setBlock(c.offset(6, -7, 6), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** Wasserbecken 3 × 3 (Quellen) und eine einzelne Lavaquelle mit Steinrand. */
    private static void buckets(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(2, -2, 2), c.offset(4, -2, 4), Blocks.STONE.defaultBlockState());
        fill(sl, c.offset(2, -1, 2), c.offset(4, -1, 4), Blocks.WATER.defaultBlockState());
        fill(sl, c.offset(7, -2, 7), c.offset(9, -1, 9), Blocks.STONE.defaultBlockState());
        sl.setBlock(c.offset(8, -1, 8), Blocks.LAVA.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    static BlockPos waterSource(BlockPos o) { return corner(o, station("eimer")).offset(3, -1, 3); }
    static BlockPos lavaSource(BlockPos o) { return corner(o, station("eimer")).offset(8, -1, 8); }

    private static void supplyChest(ServerLevel sl, BlockPos c) {
        chest(sl, c.offset(6, 0, 6), new ItemStack(Items.IRON_INGOT, 12), new ItemStack(Items.GOLD_INGOT, 6),
                new ItemStack(Items.BREAD, 10), new ItemStack(Items.COAL, 20));
    }

    static BlockPos supplyChest(BlockPos o) { return corner(o, station("kiste")).offset(6, 0, 6); }

    /** Stämme im 3er-Raster unter einem geschlossenen Laubdach (2 dick). */
    private static void darkForest(ServerLevel sl, BlockPos c) {
        BlockState lv = Blocks.DARK_OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        fill(sl, c.offset(0, 3, 0), c.offset(11, 4, 11), lv);
        for (int x = 1; x <= 10; x += 3)
            for (int z = 1; z <= 10; z += 3) fill(sl, c.offset(x, 0, z), c.offset(x, 4, z), Blocks.DARK_OAK_LOG.defaultBlockState());
    }

    /** Schacht 1 × 1, 12 tief, Leiter an der Nordwand. */
    private static void shaft(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(6, -12, 6), c.offset(6, -1, 6), Blocks.AIR.defaultBlockState());
        for (int y = -12; y <= -1; y++)
            sl.setBlock(c.offset(6, y, 6), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
    }

    /** Glatte Bauplatte: hier bauen die Redstone-Szenarien ihre Schaltungen (Reihe z 5, von x 1 nach Osten). */
    private static void redstonePad(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, -1, 0), c.offset(11, -1, 11), Blocks.SMOOTH_STONE.defaultBlockState());
    }

    private static void largeRedstonePad(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, -1, 0), c.offset(TestMapExpansion.WORK_SIZE - 1, -1, TestMapExpansion.WORK_SIZE - 1), Blocks.SMOOTH_STONE.defaultBlockState());
    }

    /** Mauer quer (z 5, 3 hoch) mit Eisentür in der Mitte, Druckplatten auf beiden Seiten. */
    private static void ironDoor(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(0, 0, 5), c.offset(11, 2, 5), Blocks.STONE_BRICKS.defaultBlockState());
        BlockPos door = c.offset(5, 0, 5);
        BlockState d = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        sl.setBlock(door, d, Block.UPDATE_CLIENTS);
        sl.setBlock(door.above(), d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
        sl.setBlock(door.north(), Blocks.STONE_PRESSURE_PLATE.defaultBlockState(), Block.UPDATE_ALL);
        sl.setBlock(door.south(), Blocks.STONE_PRESSURE_PLATE.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** Steinturm 3 × 3, oben auf Höhe 28 (Füße); östlich Wiese, westlich ein Wasserbecken 2 tief (x 0..2, z 4..6). */
    private static void mlgTower(ServerLevel sl, BlockPos c) {
        fill(sl, c.offset(4, 0, 4), c.offset(6, 27, 6), Blocks.STONE.defaultBlockState());
        // Becken direkt am Turmfuß (x 3 grenzt an die Westwand): wer an der Kante abspringt, landet im Wasser
        fill(sl, c.offset(1, -3, 3), c.offset(3, -3, 7), Blocks.STONE.defaultBlockState());
        fill(sl, c.offset(1, -2, 4), c.offset(3, -1, 6), Blocks.WATER.defaultBlockState());
    }

    static final int MLG_TOP = 28;

    // ------------------------------------------------------------------ Helfer

    private static void chest(ServerLevel sl, BlockPos at, ItemStack... items) {
        sl.setBlock(at, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        if (sl.getBlockEntity(at) instanceof ChestBlockEntity ch)
            for (int i = 0; i < items.length; i++) ch.setItem(i, items[i]);
    }

    static void spawn(ServerLevel sl, EntityType<? extends Mob> type, BlockPos at, int n) {
        for (int i = 0; i < n; i++) {
            Mob m = type.spawn(sl, at.offset(i % 2, 0, i / 2), EntitySpawnReason.COMMAND);
            if (m != null) m.setPersistenceRequired();
        }
    }

    /** Schild mit vier Zeilen; rotation 0 = Schrift nach Süden, 4 = Westen, 8 = Norden, 12 = Osten. */
    static void sign(ServerLevel sl, BlockPos at, int rotation, String[] t) {
        sl.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation), Block.UPDATE_ALL);
        if (sl.getBlockEntity(at) instanceof SignBlockEntity sign) {
            List<Component> lines = List.of(Component.literal(t[0]).withStyle(ChatFormatting.BOLD),
                    Component.literal(t[1]), Component.literal(t[2]), Component.literal(t[3]));
            sign.setText(new SignText(lines, lines, DyeColor.BLACK, false), SignTextSlot.FRONT);
            sign.setWaxed(true);
            sign.setChanged();
            sl.sendBlockUpdated(at, sign.getBlockState(), sign.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    static void fill(ServerLevel sl, BlockPos a, BlockPos b, BlockState st) {
        for (BlockPos q : BlockPos.betweenClosed(a, b)) sl.setBlock(q, st, Block.UPDATE_CLIENTS);
    }
}
