package dev.begleiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.debug.BotDebug.EventKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Baustein Stationen: Werkbank, Ofen, Amboss … finden (gemerkt, Gedächtnis, ein Scan), unerreichbare meiden,
 * Platz für eine eigene finden und unterwegs aufgestellte wieder einpacken. Nur Laufzeit.
 */
public final class CompanionStations {
    /** Für Messungen und Tests: Blocklesungen der Stationssuche (Scan), seit Serverstart. */
    public static long scanReads;

    private final CompanionEntity e;
    private BlockPos tablePos;
    private BlockPos furnacePos;
    /** Stationen, zu denen er nicht hinkam (Position -> bis Tick): solange nimmt er lieber eine eigene. */
    private final Map<BlockPos, Integer> unreachable = new HashMap<>();
    /** Unterwegs aufgestellte Werkbänke/Öfen: nach Gebrauch wieder einpacken. */
    public final List<BlockPos> portable = new ArrayList<>();

    CompanionStations(CompanionEntity e) { this.e = e; }

    public static boolean isBlockId(BlockState s, String id) {
        return isBlockId(s, ItemIds.parse(id));
    }

    private static boolean isBlockId(BlockState s, Identifier id) {
        return id != null && BuiltInRegistries.BLOCK.getKey(s.getBlock()).equals(id);
    }

    void remember(String id, BlockPos pos) {
        String canonical = ItemIds.canonical(id);
        if ("minecraft:crafting_table".equals(canonical)) tablePos = pos.immutable();
        else if ("minecraft:furnace".equals(canonical)) furnacePos = pos.immutable();
        if (canonical != null && e.level() instanceof ServerLevel sl) e.memory.remember(CompanionMemory.Kind.STATION, canonical, sl, pos);
    }

    void markUnreachable(BlockPos p) {
        unreachable.put(p.immutable(), e.tickCount + 6000);
        if (p.equals(tablePos)) tablePos = null;
        if (p.equals(furnacePos)) furnacePos = null;
    }

    private boolean isUnreachable(BlockPos p) {
        Integer until = unreachable.get(p);
        if (until == null) return false;
        if (e.tickCount < until) return true;
        unreachable.remove(p);
        return false;
    }

    BlockPos findOrderStation(String id, int radius) {
        if (ItemIds.resolve(id) != Items.FURNACE || !(e.level() instanceof ServerLevel world)) return find(List.of(id), radius, pos -> true);
        return find(List.of(id), radius, pos -> availableFurnace(world, pos));
    }

    BlockPos findLocalStockStation(String id) {
        if (!(e.level() instanceof ServerLevel world)) return null;
        return find(List.of(id), 3, pos -> e.distanceToSqr(Vec3.atCenterOf(pos)) <= 9.0
                && e.seesBlock(world, pos) && (!id.equals("furnace") || availableFurnace(world, pos)));
    }

    private static boolean availableFurnace(ServerLevel world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof Container contents && contents.isEmpty()
                && !world.getBlockState(pos).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
    }

    /**
     * Nächste Station einer der Arten (z. B. alle Ambosszustände) im Quader ±r waagerecht, ±5 senkrecht. Reihenfolge:
     * gemerkte Werkbank/Ofen, bestätigte Gedächtnisfunde (stimmt der Block nicht mehr, wird er vergessen), erst dann
     * ein einziger Scan für alle Arten. Unerreichbare Stationen werden übersprungen.
     */
    BlockPos find(List<String> ids, int r, Predicate<BlockPos> usable) {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        Set<Identifier> wanted = new java.util.LinkedHashSet<>();
        for (String id : ids) { var parsed = ItemIds.parse(id); if (parsed != null) wanted.add(parsed); }
        if (wanted.isEmpty()) return null;
        BlockPos c = e.blockPosition();
        for (var stationId : wanted) {
            String id = stationId.toString();
            BlockPos cached = id.equals("minecraft:crafting_table") ? tablePos : id.equals("minecraft:furnace") ? furnacePos : null;
            if (cached != null && sl.hasChunkAt(cached) && isBlockId(sl.getBlockState(cached), stationId) && !isUnreachable(cached)
                    && cached.distSqr(c) <= (double) r * r * 4 && usable.test(cached)) return cached;
        }
        BlockPos remembered = remembered(sl, wanted, c, r, usable);
        if (remembered != null) return remembered;
        // Ein Scan für alle Arten; Abschnitte ohne passende Station in ihrer Palette werden übersprungen.
        // Gleichstand im Abstand: wie früher der zuerst durchlaufene Block (z, dann y, dann x aufsteigend).
        BlockPos[] best = {null};
        Identifier[] bestId = {null};
        double[] bestD = {Double.MAX_VALUE};
        var result = dev.begleiter.world.SectionScanner.scanBox(sl, c.offset(-r, -5, -r), c.offset(r, 5, r),
                st -> wanted.contains(BuiltInRegistries.BLOCK.getKey(st.getBlock())), (x, y, z, st) -> {
                    BlockPos p = new BlockPos(x, y, z);
                    if (isUnreachable(p) || !usable.test(p)) return true;
                    double d = p.distSqr(c);
                    if (d < bestD[0] || d == bestD[0] && scanOrder(p, best[0]) < 0) {
                        bestD[0] = d; best[0] = p; bestId[0] = BuiltInRegistries.BLOCK.getKey(st.getBlock());
                    }
                    return true;
                });
        scanReads += result.blockReads();
        if (best[0] != null) remember(bestId[0].toString(), best[0]);
        return best[0];
    }

    /** Reihenfolge von {@code BlockPos.betweenClosed}: z, dann y, dann x aufsteigend (null zählt als letzter). */
    static int scanOrder(BlockPos a, BlockPos b) {
        if (b == null) return -1;
        if (a.getZ() != b.getZ()) return Integer.compare(a.getZ(), b.getZ());
        if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
        return Integer.compare(a.getX(), b.getX());
    }

    /** Nächste gemerkte Station im Suchquader, die noch steht und erreichbar ist; Verschwundenes wird vergessen. */
    private BlockPos remembered(ServerLevel sl, Set<Identifier> wanted, BlockPos c, int r, Predicate<BlockPos> usable) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (var stationId : wanted) {
            for (CompanionMemory.Place place : e.memory.find(CompanionMemory.Kind.STATION, stationId.toString(), sl, c)) {
                BlockPos p = place.pos;
                double d = p.distSqr(c);
                if (d >= bestD) break; // sortiert: weiter weg wird es nicht besser
                if (Math.abs(p.getX() - c.getX()) > r || Math.abs(p.getZ() - c.getZ()) > r || Math.abs(p.getY() - c.getY()) > 5) continue;
                if (!sl.hasChunkAt(p) || isUnreachable(p)) continue;
                if (!isBlockId(sl.getBlockState(p), stationId)) { e.memory.forget(place); continue; } // abgebaut oder ersetzt
                if (!usable.test(p)) continue;
                place.seen = sl.getGameTime();
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Freier Platz für Werkbank/Ofen/Kiste: im Haus (hintere Reihe), sonst neben dem Begleiter. here = true: tragbare Station direkt neben sich. */
    /**
     * Neben einer Tür oder einem Zauntor (auch eine Stufe tiefer/höher) keine Werkbank: er stellte eine direkt vor
     * das Tor seines eingezäunten Feldes und kam danach nicht mehr an die Ernte.
     */
    private static boolean nextToPassage(ServerLevel sl, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL)
            for (int dy = -1; dy <= 1; dy++) {
                var b = sl.getBlockState(p.relative(d).above(dy)).getBlock();
                if (b instanceof net.minecraft.world.level.block.FenceGateBlock || b instanceof net.minecraft.world.level.block.DoorBlock)
                    return true;
            }
        return false;
    }

    public BlockPos stationSpot(boolean here) {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        BlockPos houseOrigin = e.house.origin(); // nur in der Oberwelt: im Nether gibt es sein Haus nicht
        if (houseOrigin != null && !here) {
            // (3,1,2) bleibt frei: Weg zum Anbau
            int[][] spots = {{1, 1, 3}, {2, 1, 3}, {3, 1, 3}, {1, 1, 2}, {1, 1, 1}, {3, 1, 1}};
            for (int[] d : spots) {
                BlockPos p = houseOrigin.offset(d[0], d[1], d[2]);
                if (sl.getBlockState(p).isAir() && !sl.getBlockState(p.below()).getCollisionShape(sl, p.below()).isEmpty())
                    return p;
            }
        }
        BlockPos c = e.blockPosition();
        // Nie in die Haustür-Öffnung: sonst steht die Werkbank für die Tür genau dort, wo die Tür hin soll
        List<BlockPos> doorway = e.house.door();
        for (int r = 2; r <= 4; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = c.offset(dx, 0, dz);
                    if (doorway.contains(p) || nextToPassage(sl, p)) continue;
                    if (sl.getBlockState(p).isAir() && sl.getBlockState(p.above()).isAir()
                            && !sl.getBlockState(p.below()).getCollisionShape(sl, p.below()).isEmpty()) return p;
                }
        // Eng (auf einer Säule, im Tunnel): direkt neben sich in die Luft (seitlich an den eigenen Block) ...
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = c.relative(d);
            if (sl.getBlockState(p).isAir() && !e.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p))) return p;
        }
        // ... oder in die Wand (der Block wird abgebaut)
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = c.relative(d);
            if (mayDigForStation(sl, p, sl.getBlockState(p))) return p;
        }
        return null;
    }

    /** Darf für eine Station aus der Wand weichen? Nur gewachsener Fels/Boden, nie Gebautes (Bretter, Glas, Wolle). */
    public static boolean mayDigForStation(ServerLevel sl, BlockPos p, BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty() && s.getDestroySpeed(sl, p) >= 0 && s.getDestroySpeed(sl, p) < 5
                && !(s.getBlock() instanceof net.minecraft.world.level.block.EntityBlock) && !CompanionEntity.isPrecious(s.getBlock().asItem())
                && CompanionHouse.naturalGround(s);
    }

    /** Hat er die Station dabei oder kann sie sofort herstellen (Werkbank aus Holz, Ofen aus 8 Stein)? */
    boolean canCarry(String id) {
        Item item = CompanionCraft.item(id);
        if (item == Items.AIR) return false;
        if (e.findItem(item) >= 0) return true;
        if (item == Items.CRAFTING_TABLE) return CompanionCraft.count(e, "#planks") >= 4;
        if (item == Items.FURNACE) return CompanionCraft.count(e, "#stone") >= 8 && e.findStation("crafting_table", 8) != null;
        return false;
    }

    /** Werkbank/Ofen, die er unterwegs aufgestellt hat, wieder abbauen und einsammeln, wenn er sie nicht mehr braucht. */
    void packUp(ServerLevel sl) {
        if (portable.isEmpty()) return;
        Task cur = e.currentTask();
        for (Task t : e.queue())
            if (t.type() == TaskType.CRAFT || t.type() == TaskType.SMELT || t.type() == TaskType.STATION || t.type() == TaskType.PROCESS) return;
        if (cur != null && cur.type() == TaskType.BREAK) return;
        var it = portable.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            BlockState st = sl.getBlockState(p);
            boolean station = st.is(Blocks.CRAFTING_TABLE) || st.is(Blocks.FURNACE);
            if (!station || p.distSqr(e.blockPosition()) > 32 * 32) { it.remove(); continue; }
            if (st.is(Blocks.FURNACE) && e.containerAt(sl, p) instanceof Container c && !c.isEmpty()) continue; // noch Sachen drin
            it.remove();
            e.logInfo(EventKind.CRAFT, CompanionTexts.tr("log.station.pack", st.getBlock().getName()));
            e.pushFront(Task.collect(false));
            e.pushFront(Task.breakAt(p, st.getBlock()));
            return;
        }
    }
}
