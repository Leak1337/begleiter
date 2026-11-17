package dev.begleiter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Schematics einlesen und platzieren: .nbt (Strukturblock), .schem (WorldEdit/Sponge v2+v3) und .litematic
 * (Litematica). Gesucht wird in "schematics/" im Spielordner (wie Litematica) und in "config/worldedit/schematics/".
 */
public final class CompanionSchematics {
    private CompanionSchematics() {}

    /**
     * Die Grenzen greifen, bevor Blocklisten angelegt, Paletten gelesen oder komprimiertes NBT entpackt wird. MAX_VOLUME
     * begrenzt den angegebenen Quader (nur durchlaufen, nie als Ganzes angelegt), MAX_BLOCKS die gespeicherten festen Blöcke.
     */
    public static final int MAX_BLOCKS = 1_000_000;
    public static final int MAX_VOLUME = 16_777_216;
    /**
     * Bis zu diesem Volumen bleibt Luft im Plan ("mit Luft" räumt den Umriss frei). Größere Schematics speichern nur
     * echte Blöcke: Millionen Lufteinträge würden Hunderte Megabyte kosten.
     */
    public static final int DENSE_VOLUME = 200_000;
    public static final int MAX_SIDE = 1_024;
    private static final int MAX_PALETTE = 4_096;
    private static final long MAX_COMPRESSED_BYTES = 16L * 1024 * 1024;
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;

    private static void checkVolume(long x, long y, long z) {
        if (x <= 0 || y <= 0 || z <= 0 || x > MAX_SIDE || y > MAX_SIDE || z > MAX_SIDE
                || x * y * z > MAX_VOLUME)
            throw new IllegalArgumentException("Schematic dimensions exceed safe limits ("
                    + x + "x" + y + "x" + z + ", max " + MAX_VOLUME + " volume)");
    }

    /** Largest preview payload: a full MAX_BLOCKS plan needs at most ~6 bytes per block plus its palette. */
    public static final int MAX_PREVIEW_PACKET_BYTES = 8 * 1024 * 1024;

    /**
     * Kompakter Plan für Vorschaupakete: Größe, eine Palette aus Blockzustands-IDs, dann die Blöcke sortiert nach Index
     * (y*sz + z)*sx + x als (Indexabstand, Paletteneintrag). Große Schematics brauchen etwa 2–3 Byte je Block.
     */
    public static void writePlan(net.minecraft.network.FriendlyByteBuf b, Schematic s, boolean withAir) {
        int sx = s.sizeX(), sy = s.sizeY(), sz = s.sizeZ();
        b.writeVarInt(sx); b.writeVarInt(sy); b.writeVarInt(sz);
        List<Map.Entry<BlockPos, BlockState>> sent = new ArrayList<>();
        for (var e : s.blocks().entrySet()) if (withAir || !e.getValue().isAir()) sent.add(e);
        long[] keyed = new long[sent.size()];
        Map<BlockState, Integer> slots = new HashMap<>();
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < sent.size(); i++) {
            BlockPos p = sent.get(i).getKey();
            long index = ((long) p.getY() * sz + p.getZ()) * sx + p.getX();
            int slot = slots.computeIfAbsent(sent.get(i).getValue(), st -> { palette.add(st); return palette.size() - 1; });
            keyed[i] = index << 20 | slot; // Index < 2^24, Paletten-Platz < 2^20
        }
        java.util.Arrays.sort(keyed);
        b.writeVarInt(palette.size());
        for (BlockState st : palette) b.writeVarInt(Block.getId(st));
        b.writeVarInt(keyed.length);
        long last = -1;
        for (long k : keyed) {
            long index = k >>> 20;
            b.writeVarInt((int) (index - last));
            b.writeVarInt((int) (k & 0xFFFFF));
            last = index;
        }
    }

    /** Gegenstück zu {@link #writePlan}; lehnt Größen, Paletten und Positionen außerhalb der Grenzen ab. */
    public static Schematic readPlan(net.minecraft.network.FriendlyByteBuf b, String name) {
        int sx = b.readVarInt(), sy = b.readVarInt(), sz = b.readVarInt();
        if (sx <= 0 || sy <= 0 || sz <= 0 || sx > MAX_SIDE || sy > MAX_SIDE || sz > MAX_SIDE
                || (long) sx * sy * sz > MAX_VOLUME)
            throw new IllegalArgumentException("Schematic preview exceeds limits");
        long volume = (long) sx * sy * sz;
        int paletteSize = b.readVarInt();
        if (paletteSize < 0 || paletteSize > 1 << 20) throw new IllegalArgumentException("Schematic preview palette");
        BlockState[] palette = new BlockState[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            int id = b.readVarInt();
            if (id < 0 || id >= Block.BLOCK_STATE_REGISTRY.size()) throw new IllegalArgumentException("Invalid preview block state");
            palette[i] = Block.stateById(id);
        }
        int count = b.readVarInt();
        if (count < 0 || count > volume || count > MAX_BLOCKS) throw new IllegalArgumentException("Schematic preview exceeds limits");
        Map<BlockPos, BlockState> blocks = new HashMap<>(Math.max(16, count * 4 / 3));
        long index = -1;
        for (int i = 0; i < count; i++) {
            int delta = b.readVarInt(), slot = b.readVarInt();
            index += delta;
            if (delta <= 0 || index >= volume || slot < 0 || slot >= paletteSize)
                throw new IllegalArgumentException("Invalid schematic preview block");
            int at = (int) index;
            blocks.put(new BlockPos(at % sx, at / (sx * sz), (at / sx) % sz), palette[slot]);
        }
        return new Schematic(name, sx, sy, sz, blocks);
    }

    /** Plan ohne gespeicherte Luft (siehe {@link #DENSE_VOLUME}): "mit Luft" kann seinen Umriss nicht freiräumen. */
    public static boolean sparse(Schematic s) {
        return (long) s.sizeX() * s.sizeY() * s.sizeZ() > DENSE_VOLUME;
    }

    private static void checkBlockCount(int stored) {
        if (stored > MAX_BLOCKS)
            throw new IllegalArgumentException("Schematic has too many blocks (max " + MAX_BLOCKS + ")");
    }

    private static void checkPalette(int entries) {
        if (entries > MAX_PALETTE) throw new IllegalArgumentException("Schematic palette is too large");
    }

    /**
     * Eingelesenes Schematic: relative Positionen (0..size-1) -> Zustände (inkl. Luft). data = gefilterte
     * Block-Entity-Einstellungen (gesperrte Crafter-Slots, Schildtext) an denselben relativen Positionen.
     */
    public record Schematic(String name, int sizeX, int sizeY, int sizeZ, Map<BlockPos, BlockState> blocks,
                            Map<BlockPos, CompoundTag> data, List<EntityPlan> entities) {
        public Schematic(String name, int sizeX, int sizeY, int sizeZ, Map<BlockPos, BlockState> blocks) {
            this(name, sizeX, sizeY, sizeZ, blocks, Map.of(), List.of());
        }
        public Schematic(String name, int sizeX, int sizeY, int sizeZ, Map<BlockPos, BlockState> blocks,
                         Map<BlockPos, CompoundTag> data) {
            this(name, sizeX, sizeY, sizeZ, blocks, data, List.of());
        }
    }

    /**
     * Lebewesen im Plan (Dorfbewohner einer Eisenfarm ...): Position relativ zur kleinsten Ecke (im Schematic) bzw.
     * in der Welt (im Bauauftrag), tag = gefilterte Entity-Daten mit "id". Gesetzt werden sie nur im Kreativ-Bau.
     */
    public record EntityPlan(net.minecraft.world.phys.Vec3 pos, CompoundTag tag) {}

    /** Welt-Plan samt Block-Einstellungen und Lebewesen. */
    public record Placed(Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> data, List<EntityPlan> entities) {
        public Placed(Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> data) { this(blocks, data, List.of()); }
    }

    static final int MAX_ENTITIES = 1_024;

    /**
     * Entity-Daten aus einer fremden Datei: nur die Kennung und harmlose Eigenschaften. Keine UUID (Doppelte),
     * keine fremden UUIDs, Position/Bewegung oder Besitzer-Bindung. Mitfahrer werden rekursiv bereinigt.
     */
    static CompoundTag safeEntityTag(CompoundTag in) {
        return safeEntityTag(in, 0, new int[] {MAX_ENTITIES});
    }

    private static CompoundTag safeEntityTag(CompoundTag in, int depth, int[] remaining) {
        if (depth > 16 || --remaining[0] < 0) throw new IllegalArgumentException("Schematic entity tree exceeds limits");
        String id = in.getStringOr("id", in.getStringOr("Id", ""));
        if (id.isEmpty()) return null;
        var identifier = net.minecraft.resources.Identifier.tryParse(id);
        var type = identifier == null ? null : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(identifier).orElse(null);
        if (type == null || !CompanionBuilder.allowedEntity(type)) return null;
        CompoundTag out = in.copy();
        for (String k : new String[] {"UUID", "Pos", "Motion", "Passengers", "leash", "Leash", "Owner", "Tags",
                "Id", "TileX", "TileY", "TileZ", "block_pos", "data", "Brain"})
            out.remove(k);
        // Reitbeziehungen gehören zur Farm (etwa ein Zombie oder Endermite im Boot).
        // Passagierpositionen in Litematica können noch absolute Koordinaten der Quellwelt sein: ignorieren;
        // das Fahrzeug bestimmt beim Anlegen ihre Zielposition.
        ListTag passengers = new ListTag();
        for (var entry : in.getListOrEmpty("Passengers")) {
            var child = entry.asCompound().map(t -> safeEntityTag(t, depth + 1, remaining)).orElse(null);
            if (child != null) passengers.add(child);
        }
        if (!passengers.isEmpty()) out.put("Passengers", passengers);
        for (String k : new String[] {"Command", "LastOutput", "SuccessCount", "TrackOutput", "UpdateLastExecution"}) out.remove(k);
        out.putString("id", id);
        return out;
    }

    /** Drehung einer Position im Schematic-Kasten (wie {@link #rotate}, aber für Mittelpunkte). */
    static net.minecraft.world.phys.Vec3 rotateVec(net.minecraft.world.phys.Vec3 v, Rotation rot, int sx, int sz) {
        return switch (rot) {
            case CLOCKWISE_90 -> new net.minecraft.world.phys.Vec3(sz - v.z, v.y, v.x);
            case CLOCKWISE_180 -> new net.minecraft.world.phys.Vec3(sx - v.x, v.y, sz - v.z);
            case COUNTERCLOCKWISE_90 -> new net.minecraft.world.phys.Vec3(v.z, v.y, sx - v.x);
            default -> v;
        };
    }

    /**
     * Viele Farm-Dateien (Eisenfarm) speichern ihre Dorfbewohner nicht mit. Enthält der Plan Betten, aber keinen
     * Dorfbewohner, kommt auf jedes Bett (Kopfteil) einer – nur wenn darüber zwei Blöcke frei sind.
     */
    static List<EntityPlan> withBedVillagers(Map<BlockPos, BlockState> blocks, List<EntityPlan> entities) {
        for (EntityPlan e : entities) if ("minecraft:villager".equals(e.tag().getStringOr("id", ""))) return entities;
        List<EntityPlan> out = new ArrayList<>(entities);
        var beds = blocks.entrySet().stream()
                .filter(en -> en.getValue().getBlock() instanceof net.minecraft.world.level.block.BedBlock
                        && en.getValue().getValue(net.minecraft.world.level.block.BedBlock.PART)
                        == net.minecraft.world.level.block.state.properties.BedPart.HEAD)
                .map(Map.Entry::getKey)
                .sorted(java.util.Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ))
                .toList();
        for (BlockPos bed : beds) {
            if (out.size() >= MAX_ENTITIES) break;
            BlockState a1 = blocks.get(bed.above()), a2 = blocks.get(bed.above(2));
            if (a1 != null && !a1.isAir() || a2 != null && !a2.isAir()) continue;
            CompoundTag tag = new CompoundTag();
            tag.putString("id", "minecraft:villager");
            out.add(new EntityPlan(new net.minecraft.world.phys.Vec3(bed.getX() + 0.5, bed.getY() + 0.5625, bed.getZ() + 0.5), tag));
        }
        return out;
    }

    /**
     * Entities aus einer Datei: Position (relativ, beliebige Ecke) + Daten; Sponge v3 legt sie unter "Data".
     * Nur, was ein Bau setzen darf ({@link CompanionBuilder#allowedEntity}): herumliegende Items, Pfeile, XP ...
     * bleiben draußen. Hängende Entities (Rahmen, Gemälde) merken sich ihren Halteblock als Versatz zur Position
     * ({@link #ANCHOR}), damit er beim Drehen und Verschieben mitwandert.
     */
    static void readEntities(ListTag list, int offX, int offY, int offZ, List<EntityPlan> out) {
        readEntities(list, offX, offY, offZ, out, CURRENT_DATA);
    }

    /** version = Datenversion der Datei: ältere Entity-Daten (Rahmen mit TileX, alte Item-Formate) erst umrechnen. */
    static void readEntities(ListTag list, int offX, int offY, int offZ, List<EntityPlan> out, int version) {
        for (int i = 0; i < list.size() && out.size() < MAX_ENTITIES; i++) {
            CompoundTag e = list.getCompoundOrEmpty(i);
            ListTag pos = e.getListOrEmpty("Pos");
            if (pos.size() != 3) continue;
            CompoundTag body = e.getCompound("Data").orElse(e).copy();
            if (!body.contains("id")) body.putString("id", e.getStringOr("Id", e.getStringOr("id", "")));
            body.remove("Id");
            body = upgrade(net.minecraft.util.datafix.fixes.References.ENTITY, body, version);
            var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(
                    net.minecraft.resources.Identifier.tryParse(body.getStringOr("id", ""))).orElse(null);
            if (type == null || !CompanionBuilder.allowedEntity(type)) continue;
            int[] block = body.getIntArray("block_pos").orElse(new int[0]);
            CompoundTag safe = safeEntityTag(body);
            if (safe == null) continue;
            double rx = pos.getDoubleOr(0, Double.NaN), ry = pos.getDoubleOr(1, Double.NaN), rz = pos.getDoubleOr(2, Double.NaN);
            double x = rx + offX, y = ry + offY, z = rz + offZ;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) continue;
            if (CompanionBuilder.hanging(type)) {
                // Halteblock und Position stehen im selben Bezug (Litematica: Region). Passt der Versatz nicht zu
                // einem Rahmen (fremder Bezug, z. B. Weltkoordinaten), gilt der Block, in dem die Position liegt.
                // Versatz zur Blockmitte: dreht als echter Vektor mit (zur Blockecke wäre er nach einer Drehung um
                // einen Block versetzt, weil die Ecke beim Spiegeln zur gegenüberliegenden Ecke wird).
                double ax = block.length == 3 ? block[0] + 0.5 - rx : Double.NaN, ay = block.length == 3 ? block[1] + 0.5 - ry : 0,
                        az = block.length == 3 ? block[2] + 0.5 - rz : 0;
                if (!(Math.abs(ax) <= 2 && Math.abs(ay) <= 2 && Math.abs(az) <= 2)) {
                    ax = Math.floor(x) + 0.5 - x; ay = Math.floor(y) + 0.5 - y; az = Math.floor(z) + 0.5 - z;
                }
                ListTag anchor = new ListTag();
                anchor.add(net.minecraft.nbt.DoubleTag.valueOf(ax));
                anchor.add(net.minecraft.nbt.DoubleTag.valueOf(ay));
                anchor.add(net.minecraft.nbt.DoubleTag.valueOf(az));
                safe.put(ANCHOR, anchor);
            }
            out.add(new EntityPlan(new net.minecraft.world.phys.Vec3(x, y, z), safe));
        }
    }

    /** Versatz Position -> Mitte des Halteblocks (block_pos) hängender Entities, dreht mit dem Plan. */
    static final String ANCHOR = "BegleiterAnchor";

    static final int MAX_DATA_ENTRIES = 16_384;

    /**
     * Sammelt nur Ertrag: Kiste, Fass, Shulkerkiste, Trichter (außer Filtertrichtern, die bewusst mit mindestens
     * {@value #FILTER_ITEMS} Items befüllt sind). Ohne Kennung (schon gefiltert) nichts mehr wegnehmen.
     */
    static boolean outputContainer(CompoundTag te) {
        String id = te.getStringOr("id", te.getStringOr("Id", "")).replace("minecraft:", "");
        if (id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel") || id.endsWith("shulker_box")) return true;
        if (!id.equals("hopper")) return false;
        int items = 0;
        ListTag list = te.getListOrEmpty("Items");
        for (int i = 0; i < list.size(); i++) items += list.getCompoundOrEmpty(i).getIntOr("count", list.getCompoundOrEmpty(i).getByteOr("Count", (byte) 0));
        return items < FILTER_ITEMS;
    }

    static final int FILTER_ITEMS = 5;

    /** Gesperrte Crafter-Slots (nur 0..8) und Schildtext als reiner Text (keine Klick-Befehle). */
    /**
     * Alles (Kisten-/Werferinhalt, gesperrte Crafter-Slots, Komparator-Signal ...) außer Befehlen: Schildtext
     * bleibt reiner Text, Befehlsblöcke bekommen keinen Befehl aus einer fremden Datei. So liegen die Daten im
     * Schematic und im Bauauftrag; kreativ wird das übernommen, im Survival nur safeBlockData daraus.
     */
    static CompoundTag blockData(CompoundTag te) {
        CompoundTag out = te.copy();
        // Kreativ übernimmt jedes gespeicherte Inventar: schon ein einzelnes Item in Trichter oder Kiste kann einen Takt
        // oder eine Komparator-Einstellung kodieren. Survival ruft safeBlockData auf und bekommt nie Inhalte geschenkt.
        // Text1..4/Color/GlowingText: Schildtext vor 1.20, der Umrechner legt daraus front_text an und lässt die alten Felder stehen
        for (String k : new String[] {"id", "Id", "Pos", "x", "y", "z", "keepPacked", "disabled_slots", "Command", "LastOutput", "SuccessCount",
                "TrackOutput", "UpdateLastExecution", "LastExecution", "auto", "front_text", "back_text",
                "Text1", "Text2", "Text3", "Text4", "Color", "GlowingText"})
            out.remove(k);
        out.merge(safeBlockData(te)); // geprüfte Slots und entschärfter Schildtext
        return out;
    }

    static CompoundTag safeBlockData(CompoundTag te) {
        CompoundTag out = new CompoundTag();
        te.getIntArray("disabled_slots").ifPresent(slots -> {
            int[] ok = java.util.Arrays.stream(slots).filter(i -> i >= 0 && i < 9).distinct().toArray();
            if (ok.length > 0) out.putIntArray("disabled_slots", ok);
        });
        for (String side : new String[] {"front_text", "back_text"}) {
            CompoundTag text = te.getCompound(side).orElse(null);
            if (text == null) continue;
            ListTag in = text.getListOrEmpty("messages");
            ListTag lines = new ListTag();
            boolean any = false;
            for (int i = 0; i < 4; i++) {
                var tag = i < in.size() ? in.get(i) : null;
                String line = tag == null ? "" : tag.asString().orElseGet(() ->
                        tag.asCompound().map(c -> c.getStringOr("text", "")).orElse(""));
                if (line.length() > 90) line = line.substring(0, 90);
                any |= !line.isEmpty();
                lines.add(net.minecraft.nbt.StringTag.valueOf(line));
            }
            if (!any) continue;
            CompoundTag clean = new CompoundTag();
            clean.put("messages", lines);
            String color = text.getStringOr("color", "black");
            clean.putString("color", net.minecraft.world.item.DyeColor.byName(color, net.minecraft.world.item.DyeColor.BLACK).getSerializedName());
            clean.putBoolean("has_glowing_text", text.getBooleanOr("has_glowing_text", false));
            out.put(side, clean);
        }
        if (out.contains("front_text") || out.contains("back_text"))
            out.putBoolean("is_waxed", te.getBooleanOr("is_waxed", false));
        return out;
    }

    public static List<Path> folders() {
        Path game = FabricLoader.getInstance().getGameDir();
        return List.of(game.resolve("schematics"), game.resolve("config").resolve("worldedit").resolve("schematics"));
    }

    /**
     * Kopiert die mit der Mod gelieferten Schematics (data/begleiter/bundled_schematics) nach "schematics/" im
     * Spielordner, damit sie ohne Handarbeit im Baumenü stehen. Vorhandene Dateien werden nie überschrieben.
     */
    public static void installBundled() {
        try {
            var mod = FabricLoader.getInstance().getModContainer(CompanionMod.MOD_ID);
            if (mod.isEmpty()) return;
            var src = mod.get().findPath("data/begleiter/bundled_schematics");
            if (src.isEmpty() || !Files.isDirectory(src.get())) return;
            Path target = FabricLoader.getInstance().getGameDir().resolve("schematics");
            Files.createDirectories(target);
            CompanionSchematicCatalogue.archiveLegacy(target);
            try (Stream<Path> s = Files.list(src.get())) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile).filter(CompanionSchematics::supported)::iterator) {
                    Path out = target.resolve(f.getFileName().toString());
                    if (!Files.exists(out)) Files.copy(f, out);
                }
            }
        } catch (IOException | RuntimeException e) {
            com.mojang.logging.LogUtils.getLogger().warn("[Begleiter] Could not install bundled schematics", e);
        }
    }

    private static boolean supported(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".nbt") || n.endsWith(".schem") || n.endsWith(".litematic") || n.endsWith(".schematic");
    }

    public static List<Path> list() {
        List<Path> out = new ArrayList<>();
        for (Path dir : folders()) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir, 3)) {
                s.filter(Files::isRegularFile).filter(CompanionSchematics::supported).forEach(out::add);
            } catch (IOException | java.io.UncheckedIOException unreadable) {
                // Nicht lesbarer Ordner (Rechte, gelöschte Datei während des Durchlaufs): wie leer behandeln.
            }
        }
        return out;
    }

    public static String baseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** Datei zu einem Namen: exakt (ohne Endung) oder enthält den Namen. */
    public static Path find(String name) {
        String want = CompanionBrain.normalize(name).replace(" ", "");
        Path partial = null;
        for (Path p : list()) {
            String b = CompanionBrain.normalize(baseName(p)).replace(" ", "");
            if (b.equals(want)) return p;
            if (partial == null && !want.isEmpty() && b.contains(want)) partial = p;
        }
        return partial;
    }

    /** Blockzustände, die sich beim letzten Laden (dieses Threads) nicht lesen ließen und zu Luft wurden. */
    static final ThreadLocal<java.util.Set<String>> UNKNOWN = ThreadLocal.withInitial(java.util.TreeSet::new);

    public static java.util.Set<String> lastUnknownBlocks() {
        return java.util.Set.copyOf(UNKNOWN.get());
    }

    public static Schematic load(ServerLevel sl, Path file) throws IOException {
        if (Files.size(file) > MAX_COMPRESSED_BYTES)
            throw new IOException("Schematic compressed file exceeds 16 MiB");
        CompoundTag t = readNbt(file);
        HolderLookup.RegistryLookup<Block> blocks = sl.registryAccess().lookupOrThrow(Registries.BLOCK);
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String name = baseName(file);
        UNKNOWN.get().clear();
        Schematic s = n.endsWith(".litematic") ? loadLitematic(blocks, name, t)
                : n.endsWith(".schem") ? loadSponge(blocks, name, t)
                : n.endsWith(".schematic") ? loadLegacy(blocks, name, t) : loadStructure(blocks, name, upgradeStructure(t));
        if (!UNKNOWN.get().isEmpty())
            com.mojang.logging.LogUtils.getLogger().warn("[Begleiter] Schematic {}: skipping unknown blocks: {}",
                    name, UNKNOWN.get());
        return new Schematic(s.name(), s.sizeX(), s.sizeY(), s.sizeZ(), s.blocks(), s.data(),
                withBedVillagers(s.blocks(), s.entities()));
    }

    /** Strukturblock-Datei einer älteren Version: das Spiel hat dafür einen eigenen Gesamt-Fix. */
    static CompoundTag upgradeStructure(CompoundTag t) {
        int version = t.getIntOr("DataVersion", 500); // wie StructureTemplateManager: ohne Angabe uralt
        if (version >= CURRENT_DATA) return t;
        try {
            return net.minecraft.util.datafix.DataFixTypes.STRUCTURE.update(
                    net.minecraft.util.datafix.DataFixers.getDataFixer(), t, version, CURRENT_DATA);
        } catch (RuntimeException broken) {
            return t;
        }
    }

    /**
     * gzip (Normalfall) oder rohes NBT: manche Exporter (z. B. Litematic-Umbauten aus dem Netz) schreiben die
     * Datei unkomprimiert – erkennbar an den fehlenden gzip-Magic-Bytes 1f 8b.
     */
    static CompoundTag readNbt(Path file) throws IOException {
        try (var in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            in.mark(2);
            int b0 = in.read(), b1 = in.read();
            in.reset();
            NbtAccounter budget = NbtAccounter.create(MAX_NBT_BYTES);
            if (b0 == 0x1f && b1 == 0x8b) return NbtIo.readCompressed(in, budget);
            return NbtIo.read(new java.io.DataInputStream(in), budget);
        }
    }

    // ------------------------------------------------------------------ Ältere Minecraft-Versionen

    /** Datenversion dieses Spiels; ältere Dateien laufen durch Minecrafts eigenen DataFixer. */
    static final int CURRENT_DATA = net.minecraft.SharedConstants.WORLD_VERSION;
    /** Nur für die Ordner-Prüfung (SchematicFolderAudit): Gründe für nicht umrechenbare Daten/fehlende Entities loggen. */
    static boolean DEBUG_AUDIT = false;

    /**
     * Daten aus einer älteren Version auf den aktuellen Stand bringen, wie das Spiel es beim Laden alter Welten tut:
     * umbenannte Blöcke (grass -> short_grass), Item-Format (Count/tag -> count/components, seit 1.20.5), Rahmen-
     * Halteblock (TileX -> block_pos) ... Ohne das würden Blöcke zu Luft und Kisten-/Werferinhalte fielen weg.
     */
    static CompoundTag upgrade(com.mojang.datafixers.DSL.TypeReference type, CompoundTag tag, int from) {
        if (from <= 0 || from >= CURRENT_DATA) return tag;
        try {
            var out = net.minecraft.util.datafix.DataFixers.getDataFixer().update(type,
                    new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, (net.minecraft.nbt.Tag) tag),
                    from, CURRENT_DATA).getValue();
            return out instanceof CompoundTag c ? c : tag;
        } catch (RuntimeException broken) {
            com.mojang.logging.LogUtils.getLogger().debug("[Begleiter] Cannot upgrade schematic data {} from version {}: {}",
                    type.typeName(), from, broken.toString());
            if (DEBUG_AUDIT) com.mojang.logging.LogUtils.getLogger().info("AUDIT-DETAIL Umrechnung {} aus {} fehlgeschlagen: {}", type.typeName(), from, broken.toString());
            return tag; // ein kaputter Eintrag soll nicht das ganze Schematic verhindern
        }
    }

    /** Block-Entity-Daten ohne "id" (ältere Litematica-Versionen): Kennung aus dem Block ergänzen, sonst greift kein Fix. */
    static CompoundTag withBlockEntityId(CompoundTag te, BlockState state) {
        if (te.contains("id") || state == null || !(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock eb)) return te;
        var be = eb.newBlockEntity(BlockPos.ZERO, state);
        if (be == null) return te;
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        if (key != null) te.putString("id", key.toString());
        return te;
    }

    /** Sponge-Paletten-Schlüssel "minecraft:x[a=b]" einer älteren Version -> aktueller Zustand. */
    static BlockState parseState(HolderLookup.RegistryLookup<Block> lookup, String s, int version) {
        if (version <= 0 || version >= CURRENT_DATA) return parseState(lookup, s);
        CompoundTag e = new CompoundTag();
        int open = s.indexOf('[');
        e.putString("Name", open < 0 ? s : s.substring(0, open));
        if (open >= 0 && s.endsWith("]")) {
            CompoundTag props = new CompoundTag();
            for (String kv : s.substring(open + 1, s.length() - 1).split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) props.putString(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
            e.put("Properties", props);
        }
        return readPaletteEntry(lookup, upgrade(net.minecraft.util.datafix.fixes.References.BLOCK_STATE, e, version));
    }

    // ------------------------------------------------------------------ .nbt (Strukturblock)

    static Schematic loadStructure(HolderLookup.RegistryLookup<Block> lookup, String name, CompoundTag t) {
        ListTag size = t.getListOrEmpty("size");
        checkVolume(size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0));
        ListTag paletteTag = t.getListOrEmpty("palette");
        if (paletteTag.isEmpty() && !t.getListOrEmpty("palettes").isEmpty())
            paletteTag = t.getListOrEmpty("palettes").getListOrEmpty(0);
        checkPalette(paletteTag.size());
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteTag.size(); i++) palette.add(readPaletteEntry(lookup, paletteTag.getCompoundOrEmpty(i)));
        Map<BlockPos, BlockState> out = new HashMap<>();
        Map<BlockPos, CompoundTag> data = new HashMap<>();
        ListTag list = t.getListOrEmpty("blocks");
        boolean keepAir = (long) size.getIntOr(0, 0) * size.getIntOr(1, 0) * size.getIntOr(2, 0) <= DENSE_VOLUME;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag b = list.getCompoundOrEmpty(i);
            ListTag pos = b.getListOrEmpty("pos");
            int x = pos.getIntOr(0, -1), y = pos.getIntOr(1, -1), z = pos.getIntOr(2, -1);
            if (pos.size() != 3 || x < 0 || y < 0 || z < 0
                    || x >= size.getIntOr(0, 0) || y >= size.getIntOr(1, 0) || z >= size.getIntOr(2, 0))
                throw new IllegalArgumentException("Schematic block lies outside its declared size");
            int s = b.getIntOr("state", 0);
            if (s < 0 || s >= palette.size()) continue;
            BlockState st = palette.get(s);
            if (st.is(Blocks.STRUCTURE_VOID) || !keepAir && st.isAir()) continue;
            out.put(new BlockPos(x, y, z), st);
            checkBlockCount(out.size());
            if (data.size() < MAX_DATA_ENTRIES) b.getCompound("nbt").map(CompanionSchematics::blockData)
                    .filter(d -> !d.isEmpty()).ifPresent(d -> data.put(new BlockPos(x, y, z), d));
        }
        List<EntityPlan> entities = new ArrayList<>();
        // Strukturblock: "pos" (Mitte) relativ zur Ecke, Daten unter "nbt"
        ListTag ents = t.getListOrEmpty("entities");
        ListTag flat = new ListTag();
        for (int i = 0; i < ents.size(); i++) {
            CompoundTag e = ents.getCompoundOrEmpty(i);
            CompoundTag f = e.getCompoundOrEmpty("nbt").copy();
            f.put("Pos", e.getListOrEmpty("pos"));
            // block_pos im nbt ist eine Weltkoordinate; "blockPos" daneben liegt im selben Bezug wie "pos"
            ListTag bp = e.getListOrEmpty("blockPos");
            if (bp.size() == 3) f.putIntArray("block_pos", new int[] {bp.getIntOr(0, 0), bp.getIntOr(1, 0), bp.getIntOr(2, 0)});
            flat.add(f);
        }
        readEntities(flat, 0, 0, 0, entities);
        return new Schematic(name, size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0), out, data, entities);
    }

    // ------------------------------------------------------------------ .schem (Sponge v2/v3, WorldEdit)

    static Schematic loadSponge(HolderLookup.RegistryLookup<Block> lookup, String name, CompoundTag t) {
        CompoundTag s = t.getCompound("Schematic").orElse(t);
        int w = s.getShortOr("Width", (short) 0) & 0xFFFF;
        int h = s.getShortOr("Height", (short) 0) & 0xFFFF;
        int l = s.getShortOr("Length", (short) 0) & 0xFFFF;
        checkVolume(w, h, l);
        CompoundTag paletteTag;
        byte[] data;
        if (s.getIntOr("Version", 2) >= 3) {
            CompoundTag b = s.getCompoundOrEmpty("Blocks");
            paletteTag = b.getCompoundOrEmpty("Palette");
            data = b.getByteArray("Data").orElse(new byte[0]);
        } else {
            paletteTag = s.getCompoundOrEmpty("Palette");
            data = s.getByteArray("BlockData").orElse(new byte[0]);
        }
        checkPalette(paletteTag.size());
        int version = s.getIntOr("DataVersion", CURRENT_DATA);
        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : paletteTag.keySet()) palette.put(paletteTag.getIntOr(key, 0), parseState(lookup, key, version));
        Map<BlockPos, BlockState> out = new HashMap<>();
        boolean keepAir = (long) w * h * l <= DENSE_VOLUME;
        int i = 0, index = 0;
        while (i < data.length && index < (long) w * h * l) {
            int value = 0, shift = 0, b;
            do { b = data[i++]; value |= (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0 && i < data.length);
            BlockState st = palette.getOrDefault(value, Blocks.AIR.defaultBlockState());
            int y = index / (w * l), rem = index % (w * l), z = rem / w, x = rem % w;
            if (keepAir || !st.isAir()) {
                out.put(new BlockPos(x, y, z), st);
                checkBlockCount(out.size());
            }
            index++;
        }
        // Sponge v2/v3: BlockEntities (Pos relativ, v3 mit "Data") und Entities (Pos relativ zur Ecke)
        Map<BlockPos, CompoundTag> beData = new HashMap<>();
        ListTag bes = s.getIntOr("Version", 2) >= 3 ? s.getCompoundOrEmpty("Blocks").getListOrEmpty("BlockEntities")
                : s.getListOrEmpty("BlockEntities");
        for (int k = 0; k < bes.size() && beData.size() < MAX_DATA_ENTRIES; k++) {
            CompoundTag be = bes.getCompoundOrEmpty(k);
            int[] p = be.getIntArray("Pos").orElse(new int[0]);
            if (p.length != 3 || p[0] < 0 || p[1] < 0 || p[2] < 0 || p[0] >= w || p[1] >= h || p[2] >= l) continue;
            BlockPos at = new BlockPos(p[0], p[1], p[2]);
            BlockState st = out.get(at);
            CompoundTag raw = be.getCompound("Data").orElse(be).copy();
            raw.remove("Pos");
            if (!raw.contains("id") && be.contains("Id")) raw.putString("id", be.getStringOr("Id", ""));
            raw.remove("Id");
            CompoundTag safe = blockData(upgrade(net.minecraft.util.datafix.fixes.References.BLOCK_ENTITY,
                    withBlockEntityId(raw, st), version));
            if (!safe.isEmpty() && st != null && st.hasBlockEntity()) beData.put(at, safe);
        }
        List<EntityPlan> entities = new ArrayList<>();
        readEntities(s.getListOrEmpty("Entities"), 0, 0, 0, entities, version);
        return new Schematic(name, w, h, l, out, beData, entities);
    }

    // ------------------------------------------------------------------ .schematic (MCEdit/alter WorldEdit, bis 1.12)

    /** Erste Datenversion nach der Umstellung auf Blocknamen; BlockStateData liefert Zustände in diesem Stand. */
    private static final int FLATTENED = 1451;

    /**
     * Altes Format mit Zahlen-IDs (Blocks/AddBlocks + Data, Index (y*l + z)*w + x). Die Zahlen übersetzt Minecrafts
     * eigene Umstellungstabelle (BlockStateData), danach laufen Blöcke, Block-Entities und Entities durch den
     * DataFixer. Block-Entities mit alten Namen ("Chest") stammen aus der Zeit vor 1.11.
     */
    static Schematic loadLegacy(HolderLookup.RegistryLookup<Block> lookup, String name, CompoundTag t) {
        CompoundTag s = t.getCompound("Schematic").orElse(t);
        int w = s.getShortOr("Width", (short) 0) & 0xFFFF;
        int h = s.getShortOr("Height", (short) 0) & 0xFFFF;
        int l = s.getShortOr("Length", (short) 0) & 0xFFFF;
        checkVolume(w, h, l);
        byte[] ids = s.getByteArray("Blocks").orElse(new byte[0]);
        byte[] meta = s.getByteArray("Data").orElse(new byte[0]);
        byte[] add = s.getByteArray("AddBlocks").orElse(new byte[0]);
        long volume = (long) w * h * l;
        if (ids.length < volume) throw new IllegalArgumentException("Schematic block array is shorter than its size");
        boolean keepAir = volume <= DENSE_VOLUME;
        Map<Integer, BlockState> cache = new HashMap<>();
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (int i = 0; i < volume; i++) {
            int id = ids[i] & 0xFF;
            if ((i >> 1) < add.length) id |= ((i & 1) == 0 ? add[i >> 1] & 0x0F : (add[i >> 1] & 0xF0) >> 4) << 8;
            int key = id << 4 | (i < meta.length ? meta[i] & 0x0F : 0);
            BlockState st = cache.computeIfAbsent(key, k -> legacyState(lookup, k));
            if (!keepAir && st.isAir()) continue;
            int y = (int) (i / ((long) w * l)), rem = (int) (i % ((long) w * l)), z = rem / w, x = rem % w;
            out.put(new BlockPos(x, y, z), st);
            checkBlockCount(out.size());
        }
        Map<BlockPos, CompoundTag> data = new HashMap<>();
        ListTag tes = s.getListOrEmpty("TileEntities");
        for (int i = 0; i < tes.size() && data.size() < MAX_DATA_ENTRIES; i++) {
            CompoundTag te = tes.getCompoundOrEmpty(i).copy();
            int x = te.getIntOr("x", -1), y = te.getIntOr("y", -1), z = te.getIntOr("z", -1);
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l) continue;
            BlockPos at = new BlockPos(x, y, z);
            BlockState st = out.get(at);
            if (st == null || !st.hasBlockEntity()) continue;
            for (String k : new String[] {"x", "y", "z"}) te.remove(k);
            CompoundTag safe = blockData(upgrade(net.minecraft.util.datafix.fixes.References.BLOCK_ENTITY, te, legacyVersion(te)));
            if (!safe.isEmpty()) data.put(at, safe);
        }
        List<EntityPlan> entities = new ArrayList<>();
        ListTag ents = s.getListOrEmpty("Entities");
        // TileX/Y/Z der Rahmen liegen im selben Bezug wie Pos; der Fix macht daraus block_pos
        for (int i = 0; i < ents.size(); i++)
            readEntities(singleton(ents.getCompoundOrEmpty(i)), 0, 0, 0, entities, legacyVersion(ents.getCompoundOrEmpty(i)));
        return new Schematic(name, w, h, l, out, data, entities);
    }

    private static ListTag singleton(CompoundTag tag) {
        ListTag l = new ListTag();
        l.add(tag);
        return l;
    }

    /** Alte Kennungen ohne Namensraum ("Chest", "ItemFrame") gab es bis 1.10; danach "minecraft:chest" (1.12.2). */
    private static int legacyVersion(CompoundTag tag) {
        return tag.getStringOr("id", tag.getStringOr("Id", "")).contains(":") ? 1343 : 99;
    }

    private static BlockState legacyState(HolderLookup.RegistryLookup<Block> lookup, int key) {
        if (key >> 4 == 0) return Blocks.AIR.defaultBlockState();
        var dyn = net.minecraft.util.datafix.fixes.BlockStateData.getTag(key);
        if (dyn == null) return Blocks.AIR.defaultBlockState();
        var tag = dyn.convert(net.minecraft.nbt.NbtOps.INSTANCE).getValue();
        if (!(tag instanceof CompoundTag c)) return Blocks.AIR.defaultBlockState();
        return readPaletteEntry(lookup, upgrade(net.minecraft.util.datafix.fixes.References.BLOCK_STATE, c, FLATTENED));
    }

    /** Paletten-Eintrag {Name, Properties:{...}} (Strukturblock, Litematica) -> Zustand. */
    static BlockState readPaletteEntry(HolderLookup.RegistryLookup<Block> lookup, CompoundTag e) {
        // seit 26.x schreibt das Spiel (Strukturblock) "id"/"properties", ältere Dateien und Litematica "Name"/"Properties"
        StringBuilder sb = new StringBuilder(e.getStringOr("Name", e.getStringOr("id", "minecraft:air")));
        CompoundTag props = e.getCompoundOrEmpty("Properties");
        if (props.isEmpty()) props = e.getCompoundOrEmpty("properties");
        if (!props.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (String k : props.keySet()) {
                if (!first) sb.append(',');
                sb.append(k).append('=').append(props.getStringOr(k, ""));
                first = false;
            }
            sb.append(']');
        }
        return parseState(lookup, sb.toString());
    }

    static BlockState parseState(HolderLookup.RegistryLookup<Block> lookup, String s) {
        try {
            return BlockStateParser.parseForBlock(lookup, s, false).blockState();
        } catch (Exception e) {
            UNKNOWN.get().add(s);
            return Blocks.AIR.defaultBlockState();
        }
    }

    // ------------------------------------------------------------------ .litematic (Litematica)

    static Schematic loadLitematic(HolderLookup.RegistryLookup<Block> lookup, String name, CompoundTag t) {
        CompoundTag regions = t.getCompoundOrEmpty("Regions");
        Map<BlockPos, BlockState> world = new HashMap<>();
        Map<BlockPos, CompoundTag> data = new HashMap<>();
        List<EntityPlan> entities = new ArrayList<>();
        long totalBlocks = 0;
        if (regions.size() > 64) throw new IllegalArgumentException("Schematic has too many regions");
        long declared = 0;
        for (String rn : regions.keySet()) {
            CompoundTag size = regions.getCompoundOrEmpty(rn).getCompoundOrEmpty("Size");
            // je Seite gedeckelt, damit das Produkt nicht überläuft (checkVolume lehnt solche Regionen ohnehin ab)
            declared += Math.min(Math.abs((long) size.getIntOr("x", 0)), MAX_SIDE + 1)
                    * Math.min(Math.abs((long) size.getIntOr("y", 0)), MAX_SIDE + 1)
                    * Math.min(Math.abs((long) size.getIntOr("z", 0)), MAX_SIDE + 1);
        }
        boolean keepAir = declared <= DENSE_VOLUME;
        int version = t.getIntOr("MinecraftDataVersion", CURRENT_DATA);
        for (String rn : regions.keySet()) {
            CompoundTag r = regions.getCompoundOrEmpty(rn);
            CompoundTag pos = r.getCompoundOrEmpty("Position"), size = r.getCompoundOrEmpty("Size");
            int sx = size.getIntOr("x", 0), sy = size.getIntOr("y", 0), sz = size.getIntOr("z", 0);
            long lx = Math.abs((long) sx), ly = Math.abs((long) sy), lz = Math.abs((long) sz);
            checkVolume(lx, ly, lz);
            totalBlocks += lx * ly * lz;
            if (totalBlocks > MAX_VOLUME) throw new IllegalArgumentException("Schematic regions exceed " + MAX_VOLUME + " volume");
            int ax = (int) lx, ay = (int) ly, az = (int) lz;
            int mx = Math.addExact(pos.getIntOr("x", 0), sx < 0 ? sx + 1 : 0);
            int my = Math.addExact(pos.getIntOr("y", 0), sy < 0 ? sy + 1 : 0);
            int mz = Math.addExact(pos.getIntOr("z", 0), sz < 0 ? sz + 1 : 0);
            Math.addExact(mx, ax - 1); Math.addExact(my, ay - 1); Math.addExact(mz, az - 1);
            ListTag pt = r.getListOrEmpty("BlockStatePalette");
            checkPalette(pt.size());
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < pt.size(); i++) palette.add(readPaletteEntry(lookup,
                    upgrade(net.minecraft.util.datafix.fixes.References.BLOCK_STATE, pt.getCompoundOrEmpty(i), version)));
            long[] arr = r.getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            long volume = (long) ax * ay * az;
            for (int i = 0; i < volume; i++) {
                int id = (int) bitAt(arr, i, bits);
                if (id < 0 || id >= palette.size()) continue;
                BlockState st = palette.get(id);
                if (!keepAir && st.isAir()) continue;
                int x = i % ax, z = (i / ax) % az, y = i / (ax * az);
                world.put(new BlockPos(mx + x, my + y, mz + z), st);
                checkBlockCount(world.size());
            }
            // Block-Entity-Positionen sind relativ zur kleinsten Ecke der Region
            ListTag tes = r.getListOrEmpty("TileEntities");
            for (int i = 0; i < tes.size() && data.size() < MAX_DATA_ENTRIES; i++) {
                CompoundTag te = tes.getCompoundOrEmpty(i);
                int x = te.getIntOr("x", -1), y = te.getIntOr("y", -1), z = te.getIntOr("z", -1);
                if (x < 0 || y < 0 || z < 0 || x >= ax || y >= ay || z >= az) continue;
                BlockPos at = new BlockPos(mx + x, my + y, mz + z);
                CompoundTag raw = te.copy();
                for (String k : new String[] {"x", "y", "z"}) raw.remove(k);
                CompoundTag safe = blockData(upgrade(net.minecraft.util.datafix.fixes.References.BLOCK_ENTITY,
                        withBlockEntityId(raw, world.get(at)), version));
                if (!safe.isEmpty()) data.put(at, safe);
            }
            // Entities dagegen relativ zum Ursprung der Region ("Position"), der bei negativer Größe die größte Ecke
            // ist (Litematica misst sie von pos1 aus) – mit der kleinsten Ecke lägen Mobs/Rahmen um |Größe|-1 daneben
            readEntities(r.getListOrEmpty("Entities"), pos.getIntOr("x", 0), pos.getIntOr("y", 0), pos.getIntOr("z", 0),
                    entities, version);
        }
        return normalize(name, world, data, entities);
    }

    /** Wert i aus einem dicht gepackten long-Array (Einträge dürfen über long-Grenzen reichen). */
    static long bitAt(long[] arr, int i, int bits) {
        long start = (long) i * bits;
        int si = (int) (start >> 6), ei = (int) ((start + bits - 1) >> 6), off = (int) (start & 63);
        long mask = (1L << bits) - 1;
        if (si >= arr.length) return -1;
        if (si == ei) return (arr[si] >>> off) & mask;
        if (ei >= arr.length) return -1;
        return ((arr[si] >>> off) | (arr[ei] << (64 - off))) & mask;
    }

    /** Auf 0..size-1 verschieben. */
    static Schematic normalize(String name, Map<BlockPos, BlockState> world) {
        return normalize(name, world, Map.of());
    }

    static Schematic normalize(String name, Map<BlockPos, BlockState> world, Map<BlockPos, CompoundTag> data) {
        return normalize(name, world, data, List.of());
    }

    static Schematic normalize(String name, Map<BlockPos, BlockState> world, Map<BlockPos, CompoundTag> data,
                               List<EntityPlan> entities) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : world.keySet()) {
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
        }
        if (world.isEmpty()) return new Schematic(name, 0, 0, 0, Map.of());
        checkVolume((long) maxX - minX + 1, (long) maxY - minY + 1, (long) maxZ - minZ + 1);
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (var en : world.entrySet()) out.put(en.getKey().offset(-minX, -minY, -minZ), en.getValue());
        Map<BlockPos, CompoundTag> movedData = new HashMap<>();
        for (var en : data.entrySet()) {
            BlockState at = world.get(en.getKey());
            // nur, wo auch wirklich ein Block mit Block-Entity steht
            if (at != null && at.hasBlockEntity()) movedData.put(en.getKey().offset(-minX, -minY, -minZ), en.getValue());
        }
        List<EntityPlan> movedEntities = new ArrayList<>();
        for (EntityPlan e : entities)
            movedEntities.add(new EntityPlan(e.pos().subtract(minX, minY, minZ), e.tag()));
        return new Schematic(name, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, out, movedData, movedEntities);
    }

    // ------------------------------------------------------------------ Platzieren

    /** Drehung so, dass die Vorderseite (+z im Schematic) von dir weg zeigt. */
    public static Rotation rotationFor(Direction facing) {
        return switch (facing) {
            case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    static BlockPos rotate(BlockPos p, Rotation rot, int sx, int sz) {
        return switch (rot) {
            case CLOCKWISE_90 -> new BlockPos(sz - 1 - p.getZ(), p.getY(), p.getX());
            case CLOCKWISE_180 -> new BlockPos(sx - 1 - p.getX(), p.getY(), sz - 1 - p.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(p.getZ(), p.getY(), sx - 1 - p.getX());
            default -> p;
        };
    }

    /**
     * Welt-Positionen: vor dem Spieler (ab 2 Blöcke voraus, seitlich mittig), unterste Schicht auf Fußhöhe.
     * withAir = auch Luft setzen (räumt das Gelände im Umriss frei).
     */
    public static Map<BlockPos, BlockState> place(Schematic s, BlockPos feet, Direction facing, Rotation extra, boolean withAir) {
        return placeFull(s, feet, facing, extra, withAir).blocks();
    }

    /** Wie place, zusätzlich die Block-Einstellungen an ihren Welt-Positionen. */
    public static Placed placeFull(Schematic s, BlockPos feet, Direction facing, Rotation extra, boolean withAir) {
        Rotation rot = rotationFor(facing).getRotated(extra);
        Map<BlockPos, BlockState> rotated = new HashMap<>();
        for (var en : s.blocks().entrySet()) {
            if (!withAir && en.getValue().isAir()) continue;
            rotated.put(rotate(en.getKey(), rot, s.sizeX(), s.sizeZ()), en.getValue().rotate(rot));
        }
        Direction right = facing.getClockWise();
        int minA = Integer.MAX_VALUE, minB = Integer.MAX_VALUE, maxB = Integer.MIN_VALUE;
        for (BlockPos p : rotated.keySet()) {
            int a = p.getX() * facing.getStepX() + p.getZ() * facing.getStepZ();
            int b = p.getX() * right.getStepX() + p.getZ() * right.getStepZ();
            minA = Math.min(minA, a); minB = Math.min(minB, b); maxB = Math.max(maxB, b);
        }
        BlockPos anchor = feet.relative(facing, 2);
        int shiftA = -minA, shiftB = -(minB + maxB) / 2;
        java.util.function.Function<BlockPos, BlockPos> toWorld = p -> {
            long x = (long) anchor.getX() + p.getX() + (long) facing.getStepX() * shiftA + (long) right.getStepX() * shiftB;
            long z = (long) anchor.getZ() + p.getZ() + (long) facing.getStepZ() * shiftA + (long) right.getStepZ() * shiftB;
            return new BlockPos(Math.toIntExact(x), Math.addExact(anchor.getY(), p.getY()), Math.toIntExact(z));
        };
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (var en : rotated.entrySet()) out.put(toWorld.apply(en.getKey()), en.getValue());
        Map<BlockPos, CompoundTag> data = new HashMap<>();
        for (var en : s.data().entrySet()) {
            BlockPos w = toWorld.apply(rotate(en.getKey(), rot, s.sizeX(), s.sizeZ()));
            if (out.containsKey(w)) data.put(w, en.getValue().copy());
        }
        BlockPos shift = toWorld.apply(BlockPos.ZERO); // Verschiebung ist für alle Punkte gleich
        return new Placed(out, data, placeEntities(s, rot, net.minecraft.world.phys.Vec3.atLowerCornerOf(shift)));
    }

    private static List<EntityPlan> placeEntities(Schematic s, Rotation rot, net.minecraft.world.phys.Vec3 shift) {
        List<EntityPlan> out = new ArrayList<>();
        for (EntityPlan e : s.entities()) {
            CompoundTag tag = e.tag().copy();
            tag.putFloat("BegleiterYaw", rotateYaw(tag, rot));
            rotateHanging(tag, rot);
            out.add(new EntityPlan(rotateVec(e.pos(), rot, s.sizeX(), s.sizeZ()).add(shift), tag));
        }
        return out;
    }

    /**
     * Hängende Entities: Halteblock-Versatz und Blickrichtung mitdrehen. Rahmen speichern "Facing" als 3D-Kennung
     * (auch an Decke/Boden, die bleiben), Gemälde "facing" als 2D-Kennung.
     */
    static void rotateHanging(CompoundTag tag, Rotation rot) {
        if (rot == Rotation.NONE) return;
        ListTag a = tag.getListOrEmpty(ANCHOR);
        if (a.size() == 3) {
            double dx = a.getDoubleOr(0, 0), dz = a.getDoubleOr(2, 0);
            double nx = switch (rot) { case CLOCKWISE_90 -> -dz; case CLOCKWISE_180 -> -dx; default -> dz; };
            double nz = switch (rot) { case CLOCKWISE_90 -> dx; case CLOCKWISE_180 -> -dz; default -> -dx; };
            ListTag r = new ListTag();
            r.add(net.minecraft.nbt.DoubleTag.valueOf(nx));
            r.add(a.get(1).copy());
            r.add(net.minecraft.nbt.DoubleTag.valueOf(nz));
            tag.put(ANCHOR, r);
        }
        tag.getByte("Facing").ifPresent(v -> {
            Direction d = Direction.from3DDataValue(v);
            if (d.getAxis().isHorizontal()) tag.putByte("Facing", (byte) rot.rotate(d).get3DDataValue());
        });
        tag.getByte("facing").ifPresent(v -> tag.putByte("facing", (byte) rot.rotate(Direction.from2DDataValue(v)).get2DDataValue()));
    }

    /** Blickrichtung mitdrehen (Rotation[0] im Entity-Tag). */
    private static float rotateYaw(CompoundTag tag, Rotation rot) {
        float yaw = tag.getListOrEmpty("Rotation").getFloatOr(0, 0f);
        return switch (rot) {
            case CLOCKWISE_90 -> yaw + 90f;
            case CLOCKWISE_180 -> yaw + 180f;
            case COUNTERCLOCKWISE_90 -> yaw + 270f;
            default -> yaw;
        };
    }

    /** Exakte Platzierung: die Mitte der untersten Schicht liegt auf dem gewählten Bodenblock. */
    public static Map<BlockPos, BlockState> placeAt(Schematic s, BlockPos center, Rotation rotation, boolean withAir) {
        return placeAtFull(s, center, rotation, withAir).blocks();
    }

    /** Wie placeAt, zusätzlich die Block-Daten an ihren Welt-Positionen. */
    public static Placed placeAtFull(Schematic s, BlockPos center, Rotation rotation, boolean withAir) {
        boolean sideways = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        int width = sideways ? s.sizeZ() : s.sizeX();
        int depth = sideways ? s.sizeX() : s.sizeZ();
        java.util.function.Function<BlockPos, BlockPos> toWorld = p -> {
            BlockPos relative = rotate(p, rotation, s.sizeX(), s.sizeZ());
            return center.offset(relative.getX() - width / 2, relative.getY(), relative.getZ() - depth / 2);
        };
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (var entry : s.blocks().entrySet()) {
            if (!withAir && entry.getValue().isAir()) continue;
            result.put(toWorld.apply(entry.getKey()), entry.getValue().rotate(rotation));
        }
        Map<BlockPos, CompoundTag> data = new HashMap<>();
        for (var entry : s.data().entrySet()) {
            BlockPos w = toWorld.apply(entry.getKey());
            if (result.containsKey(w)) data.put(w, entry.getValue().copy());
        }
        BlockPos shift = center.offset(-width / 2, 0, -depth / 2);
        return new Placed(result, data, placeEntities(s, rotation, net.minecraft.world.phys.Vec3.atLowerCornerOf(shift)));
    }

    /** Erst den gesamten Weltplan freigeben; ungültige Ziele dürfen keine Chunks laden. */
    public static void validatePlacement(ServerLevel level, Map<BlockPos, BlockState> blocks) {
        for (BlockPos p : blocks.keySet()) {
            if (level.isOutsideBuildHeight(p) || !level.getWorldBorder().isWithinBounds(p))
                throw new IllegalArgumentException("Schematic lies outside build height or world border");
        }
    }
}
