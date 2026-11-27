package dev.begleiter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.debug.BotDebug.EventKind;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Baustein Haus: das 5x5-Haus des Begleiters mit Anbauten fürs Lager. Hält Bauplatz, Fertig-Status und Zahl der
 * Anbauten und ist allein für deren Speicherung zuständig (Entity-Daten und Todeskern, gleiche Schlüssel wie zuvor).
 */
public final class CompanionHouse {
    public static final int MAX_ANNEXES = 4;

    private final CompanionEntity e;
    private BlockPos origin;
    private boolean built;
    /** Anzahl Lager-Anbauten seitlich am Haus (je 4 Blöcke lang, für mehr Kisten). */
    private int annexes;

    CompanionHouse(CompanionEntity e) { this.e = e; }

    // ------------------------------------------------------------------ Zustand

    public boolean built() { return built; }
    public int annexes() { return annexes; }
    /** Ecke des Hauses (Bodenebene); nur in der Oberwelt, sonst null. */
    public BlockPos origin() { return e.inOverworld() ? origin : null; }

    /** Haus komplett vergessen (Testgelände: jedes Haus-Szenario beginnt ohne Haus). */
    void forget() { origin = null; built = false; annexes = 0; }

    /** Mitte des Hauses (Fußhöhe). */
    public BlockPos inside() { return origin() == null ? null : origin.offset(2, 1, 2); }

    /** Steht p im Innenraum des Hauses samt Anbauten (Fuß- oder Kopfhöhe)? */
    public boolean contains(BlockPos pos) {
        if (origin() == null) return false;
        BlockPos p = pos.subtract(origin);
        return p.getX() >= 1 && p.getX() <= 3 + 4 * annexes && p.getZ() >= 1 && p.getZ() <= 3 && p.getY() >= 1 && p.getY() <= 2;
    }

    /** Die zwei Blöcke der Türöffnung. */
    public List<BlockPos> door() {
        return origin() == null ? List.of() : List.of(origin.offset(2, 1, 0), origin.offset(2, 2, 0));
    }

    /** Haustür fehlt (Öffnung frei, keine Tür, nicht für die Nacht zugemauert)? */
    public boolean needsDoor() {
        if (origin() == null || !(e.level() instanceof ServerLevel sl)) return false;
        BlockPos d = door().getFirst();
        return !CompanionDoors.isDoor(sl, d) && CompanionDoors.canPlace(sl, d);
    }

    /** Innenfläche (Fußhöhe) des ganzen Hauses samt Anbauten, für Lagerscans. */
    public Iterable<BlockPos> floorArea() {
        return BlockPos.betweenClosed(origin.offset(1, 1, 1), origin.offset(4 * annexes + 3, 1, 3));
    }

    /** Kistenplätze im Haus samt Blickrichtung: Seiten des Hauptraums, in Anbauten die Reihen links/rechts vom Mittelgang. */
    Map<BlockPos, Direction> chestSpots() {
        Map<BlockPos, Direction> m = new LinkedHashMap<>();
        if (origin() == null) return m;
        for (int dz : new int[]{3, 2, 1}) m.put(origin.offset(1, 1, dz), Direction.EAST);
        for (int dz : new int[]{3, 1}) m.put(origin.offset(3, 1, dz), Direction.WEST);
        for (int k = 1; k <= annexes; k++)
            for (int dx = 4 * k + 1; dx <= 4 * k + 3; dx++) {
                m.put(origin.offset(dx, 1, 1), Direction.SOUTH);
                m.put(origin.offset(dx, 1, 3), Direction.NORTH);
            }
        return m;
    }

    // ------------------------------------------------------------------ Bauplatz

    /** Sucht einen (fast) ebenen 5x5-Platz in der Nähe des Besitzers. Gibt die Boden-Ebene zurück. */
    public BlockPos findBuildSite() {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        Player o = e.getOwner();
        BlockPos base = o != null ? o.blockPosition() : e.blockPosition();
        for (int i = 0; i < 100; i++) {
            int x = base.getX() + e.getRandom().nextInt(41) - 22;
            int z = base.getZ() + e.getRandom().nextInt(41) - 22;
            BlockPos site = buildSiteAt(sl, x, z);
            if (site != null) return site;
        }
        return null;
    }

    /**
     * Eignet sich die 5x5-Fläche ab (x, z) als Bauplatz? Boden eben, fest und gewachsen (kein Dach, keine Plattform,
     * kein Bretterboden des Spielers), darüber nichts Gebautes. Rückgabe: Boden-Ebene oder null.
     */
    static BlockPos buildSiteAt(ServerLevel sl, int x, int z) {
        if (!sl.hasChunkAt(new BlockPos(x, 64, z)) || !sl.hasChunkAt(new BlockPos(x + 4, 64, z + 4))) return null;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = 0; dx < 5; dx++)
            for (int dz = 0; dz < 5; dz++) {
                int h = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                BlockPos g = new BlockPos(x + dx, h - 1, z + dz);
                BlockState gs = sl.getBlockState(g);
                if (!gs.getFluidState().isEmpty() || gs.is(BlockTags.LEAVES) || gs.is(BlockTags.LOGS)
                        || gs.getCollisionShape(sl, g).isEmpty() || !naturalGround(gs)) return null;
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        if (max - min > 1 || !freeAbove(sl, x, max, z)) return null;
        return new BlockPos(x, max, z);
    }

    /** Gewachsener Boden/Fels (kein Gebautes), siehe {@link dev.begleiter.nav.BlockFlags#NATURAL}. */
    static boolean naturalGround(BlockState s) {
        return dev.begleiter.nav.BlockFlags.has(dev.begleiter.nav.BlockFlags.of(s), dev.begleiter.nav.BlockFlags.NATURAL);
    }

    /** Über dem Platz (5x5, 5 hoch) nur Luft, Gras/Blumen/Schnee oder Natürliches – keine Fackeln, Redstone, Schienen. */
    private static boolean freeAbove(ServerLevel sl, int x, int y, int z) {
        for (int dx = 0; dx < 5; dx++)
            for (int dz = 0; dz < 5; dz++)
                for (int dy = 0; dy < 5; dy++) {
                    BlockPos p = new BlockPos(x + dx, y + dy, z + dz);
                    BlockState s = sl.getBlockState(p);
                    if (!s.isAir() && !s.canBeReplaced() && !naturalGround(s)) return false;
                }
        return true;
    }

    // ------------------------------------------------------------------ Material

    /** Baumaterial im Inventar (Bretter, Bruchstein, Stein ...), größte Menge zuerst. */
    public Map<Item, Integer> buildMaterials() {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            if (s.is(ItemTags.PLANKS) || s.is(Items.COBBLESTONE) || s.is(Items.STONE) || s.is(Items.STONE_BRICKS)
                    || s.is(Items.BRICKS) || s.is(Items.COBBLED_DEEPSLATE)) {
                m.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        List<Map.Entry<Item, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        Map<Item, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> en : es) sorted.put(en.getKey(), en.getValue());
        return sorted;
    }

    /** Gebautes Haus mit mindestens zwei fehlenden Wand-, Boden- oder Dachblöcken (z.B. nach einer Creeper-Explosion). */
    public boolean damaged(ServerLevel sl) {
        BlockPos o = origin();
        if (!built || o == null || !sl.hasChunkAt(o) || !sl.hasChunkAt(o.offset(4, 0, 4))) return false;
        int missing = 0;
        for (BlockPos p : spots(o)) if (!isHouseBlock(sl.getBlockState(p)) && ++missing >= 2) return true;
        return false;
    }

    private static boolean isHouseBlock(BlockState s) {
        if (s.is(BlockTags.PLANKS)) return true;
        return s.is(Blocks.COBBLESTONE) || s.is(Blocks.STONE) || s.is(Blocks.STONE_BRICKS) || s.is(Blocks.BRICKS)
                || s.is(Blocks.COBBLED_DEEPSLATE);
    }

    /** Setz-Aufgaben für die fehlenden Plätze, Material der Reihe nach (größter Vorrat zuerst). */
    private static void placeTasks(List<Task> out, List<BlockPos> need, Map<Item, Integer> mats) {
        List<Map.Entry<Item, Integer>> avail = new ArrayList<>(mats.entrySet());
        int idx = 0, used = 0;
        for (BlockPos p : need) {
            if (idx >= avail.size()) break;
            out.add(Task.place(p, Block.byItem(avail.get(idx).getKey())));
            if (++used >= avail.get(idx).getValue()) { idx++; used = 0; }
        }
    }

    // ------------------------------------------------------------------ Haus bauen

    /** Alle Bauplätze des 5x5-Hauses: Boden, Wände (mit Türöffnung), Dach. */
    private List<BlockPos> spots(BlockPos o) {
        List<BlockPos> spots = new ArrayList<>();
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) spots.add(o.offset(dx, 0, dz));
        for (int dy = 1; dy <= 3; dy++)
            for (int dx = 0; dx < 5; dx++)
                for (int dz = 0; dz < 5; dz++) {
                    boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    boolean doorGap = dz == 0 && dx == 2 && dy <= 2;
                    boolean annexDoor = annexes > 0 && dx == 4 && dz == 2 && dy <= 2;
                    if (edge && !doorGap && !annexDoor) spots.add(o.offset(dx, dy, dz));
                }
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) spots.add(o.offset(dx, 4, dz));
        return spots;
    }

    private List<Task> tasks(ServerLevel sl, BlockPos o, Map<Item, Integer> mats, List<BlockPos> need) {
        List<Task> out = new ArrayList<>();
        for (int dy = 0; dy <= 4; dy++)
            for (int dx = 0; dx < 5; dx++)
                for (int dz = 0; dz < 5; dz++) {
                    BlockPos p = o.offset(dx, dy, dz);
                    BlockState s = sl.getBlockState(p);
                    if (!s.isAir() && !s.canBeReplaced() && !isHouseBlock(s)) out.add(Task.breakAt(p, s.getBlock()));
                }
        placeTasks(out, need, mats);
        return out;
    }

    /** HOUSE-Aufgabe: berechnet, was noch fehlt, besorgt Material und plant dann den Bau. */
    public void step(Task t) {
        if (!(e.level() instanceof ServerLevel sl)) return;
        CompanionCraft.convertLogsToPlanks(e, Integer.MAX_VALUE);
        BlockPos site = t.pos();
        if (site == null) {
            site = findBuildSite();
            if (site == null) { e.problem(CompanionTexts.tr("log.problem.no_house_site")); e.finishTask(); return; }
        }
        List<BlockPos> need = new ArrayList<>();
        for (BlockPos p : spots(site)) if (!isHouseBlock(sl.getBlockState(p))) need.add(p);
        Map<Item, Integer> mats = buildMaterials();
        int have = mats.values().stream().mapToInt(Integer::intValue).sum();
        if (need.isEmpty()) {
            built = true;
            origin = site;
            if (needsDoor()) e.replaceCurrent(List.of(Task.door(door().getFirst(), "north")));
            else e.finishTask();
            e.tell(CompanionTexts.tr("entity.house.done"));
            return;
        }
        if (have >= need.size()) {
            built = true;
            origin = site;
            List<Task> build = new ArrayList<>(tasks(sl, site, mats, need));
            build.add(Task.door(site.offset(2, 1, 0), "north")); // zum Schluss die Tür
            e.replaceCurrent(build);
            e.tell(CompanionTexts.tr("entity.house.start"));
            e.logInfo(EventKind.BUILD, CompanionTexts.tr("log.house.blocks", need.size(), site.toShortString()));
            return;
        }
        if (t.tries() > 6) { e.problem(CompanionTexts.tr("log.problem.house_material", have, need.size())); e.finishTask(); return; }
        int logs = (need.size() - have + 3) / 4;
        e.logInfo(EventKind.BUILD, CompanionTexts.tr("log.house.material", have, need.size(), logs, t.tries() + 1));
        List<Task> list = new ArrayList<>();
        list.add(Task.gather(Resources.WOOD.key(), logs));
        list.add(Task.craft("#planks_from_logs", 0));
        list.add(new Task(new TaskSpec.House(site), t.tries() + 1));
        e.replaceCurrent(list);
    }

    // ------------------------------------------------------------------ Anbauten

    /**
     * Anbau k (1, 2, ...) seitlich am Haus (+x): 4 Blöcke lang, gleiche Tiefe/Höhe, teilt die Wand mit dem Teil davor.
     * Bauplätze: Boden, Seitenwände, Stirnwand, Dach.
     */
    private List<BlockPos> annexSpots(int k) {
        List<BlockPos> spots = new ArrayList<>();
        int x0 = 4 * k + 1, x1 = 4 * k + 4;
        for (int dx = x0; dx <= x1; dx++) for (int dz = 0; dz < 5; dz++) spots.add(origin.offset(dx, 0, dz));
        for (int dy = 1; dy <= 3; dy++)
            for (int dx = x0; dx <= x1; dx++)
                for (int dz = 0; dz < 5; dz++)
                    if (dz == 0 || dz == 4 || dx == x1) spots.add(origin.offset(dx, dy, dz));
        for (int dx = x0; dx <= x1; dx++) for (int dz = 0; dz < 5; dz++) spots.add(origin.offset(dx, 4, dz));
        return spots;
    }

    /**
     * Steht im Bereich von Anbau k etwas Gebautes, das nicht zum Haus gehört (Kiste, Farm, Redstone des Spielers)?
     * Dann nicht dort bauen: Der Anbau dürfte sonst Fremdes abreißen. Natürliches, Pflanzen und Hausblöcke sind
     * in Ordnung; ungeladen zählt als belegt.
     */
    private BlockPos annexBlocked(ServerLevel sl, int k) {
        for (int dy = 0; dy <= 4; dy++)
            for (int dx = 4 * k + 1; dx <= 4 * k + 4; dx++)
                for (int dz = 0; dz < 5; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (!sl.hasChunkAt(p)) return p;
                    BlockState s = sl.getBlockState(p);
                    if (s.isAir() || s.canBeReplaced() || isHouseBlock(s) || CompanionGathering.naturalAt(sl, p, s)) continue;
                    return p;
                }
        return null;
    }

    /** EXTEND-Aufgabe: Lager voll -> Haus um einen Raum für Kisten verlängern (n = Nummer des Anbaus). */
    public void extendStep(Task t) {
        if (!(e.level() instanceof ServerLevel sl) || origin() == null) { e.finishTask(); return; }
        int annex = ((TaskSpec.Extend) t.spec()).annex();
        int k = annex > 0 ? annex : annexes + 1;
        if (k > MAX_ANNEXES) { e.problem(CompanionTexts.tr("log.problem.house_large")); e.finishTask(); return; }
        BlockPos blocked = annexBlocked(sl, k);
        if (blocked != null) { e.problem(CompanionTexts.tr("log.problem.annex_blocked", blocked.toShortString())); e.finishTask(); return; }
        CompanionCraft.convertLogsToPlanks(e, Integer.MAX_VALUE);
        List<BlockPos> need = new ArrayList<>();
        for (BlockPos p : annexSpots(k)) if (!isHouseBlock(sl.getBlockState(p))) need.add(p);
        Map<Item, Integer> mats = buildMaterials();
        int have = mats.values().stream().mapToInt(Integer::intValue).sum();
        if (have >= need.size()) {
            annexes = Math.max(annexes, k);
            List<Task> out = new ArrayList<>();
            // Durchgang in der Wand davor, Innenraum frei räumen, Fremdes in Wänden/Boden/Dach weg
            List<BlockPos> passage = new ArrayList<>(List.of(origin.offset(4 * k, 1, 2), origin.offset(4 * k, 2, 2)));
            // im Hauptraum muss der Weg zum Durchgang (3,1,2) frei sein (dort kann eine Station stehen)
            if (k == 1) passage.addAll(List.of(origin.offset(3, 1, 2), origin.offset(3, 2, 2)));
            for (BlockPos p : passage) {
                BlockState s = sl.getBlockState(p);
                if (!s.isAir() && !s.canBeReplaced()) out.add(Task.breakAt(p, s.getBlock()));
            }
            for (int dy = 0; dy <= 4; dy++)
                for (int dx = 4 * k + 1; dx <= 4 * k + 4; dx++)
                    for (int dz = 0; dz < 5; dz++) {
                        BlockPos p = origin.offset(dx, dy, dz);
                        BlockState s = sl.getBlockState(p);
                        if (s.isAir() || s.canBeReplaced()) continue;
                        boolean inner = dy >= 1 && dy <= 3 && dz >= 1 && dz <= 3 && dx < 4 * k + 4;
                        if (inner || !isHouseBlock(s)) out.add(Task.breakAt(p, s.getBlock()));
                    }
            placeTasks(out, need, mats);
            e.replaceCurrent(out);
            e.tell(CompanionTexts.tr("entity.house.annex"));
            e.logInfo(EventKind.BUILD, CompanionTexts.tr("log.annex.blocks", k, need.size()));
            return;
        }
        if (t.tries() > 6) { e.problem(CompanionTexts.tr("log.problem.annex_material", have, need.size())); e.finishTask(); return; }
        int logs = (need.size() - have + 3) / 4;
        e.logInfo(EventKind.BUILD, CompanionTexts.tr("log.annex.material", have, need.size(), logs, t.tries() + 1));
        List<Task> list = new ArrayList<>();
        list.add(Task.gather(Resources.WOOD.key(), logs));
        list.add(Task.craft("#planks_from_logs", 0));
        list.add(new Task(new TaskSpec.Extend(k), t.tries() + 1));
        e.replaceCurrent(list);
    }

    // ------------------------------------------------------------------ Speichern

    void save(ValueOutput out) {
        out.putBoolean("HouseBuilt", built);
        out.putInt("HouseAnnexes", annexes);
        if (origin != null) out.store("HouseOrigin", BlockPos.CODEC, origin);
    }

    void load(ValueInput in) {
        built = in.getBooleanOr("HouseBuilt", false);
        annexes = Math.clamp(in.getIntOr("HouseAnnexes", 0), 0, MAX_ANNEXES); // Bereich der Lagerscans begrenzen
        origin = in.read("HouseOrigin", BlockPos.CODEC).orElse(null);
    }

    /** Für den Todeskern (Erinnerungen): dieselben Schlüssel wie früher direkt an der Entity. */
    void saveMemory(CompoundTag t) {
        if (origin != null) t.putLong("HouseOrigin", origin.asLong());
        t.putBoolean("HouseBuilt", built);
        t.putInt("HouseAnnexes", annexes);
    }

    void loadMemory(CompoundTag t) {
        if (t.contains("HouseOrigin")) origin = BlockPos.of(t.getLongOr("HouseOrigin", 0));
        built = t.getBooleanOr("HouseBuilt", false);
        annexes = Math.clamp(t.getIntOr("HouseAnnexes", 0), 0, MAX_ANNEXES);
    }
}
