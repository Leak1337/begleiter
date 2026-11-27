package dev.begleiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Der räumliche Index liefert dieselben Antworten wie die frühere lineare Suche, auch bei Gleichstand. */
public class MemoryIndexGameTests {
    private static final CompanionMemory.Kind[] KINDS = {CompanionMemory.Kind.ORE, CompanionMemory.Kind.TREE,
            CompanionMemory.Kind.CHEST, CompanionMemory.Kind.DANGER, CompanionMemory.Kind.DEATH, CompanionMemory.Kind.WATER};
    private static final String[] ORE_KEYS = {"eisen", "kohle", "gold"};
    private static final Item[] ITEMS = {Items.IRON_INGOT, Items.BREAD, Items.TORCH};
    private static final int[] RADII = {0, 1, 5, 15, 16, 17, 40, 100, 300, 50_000};

    // ---------------------------------------------------------------- lineare Referenz (alte Semantik)

    private static CompanionMemory.Place refNearest(CompanionMemory m, String dim, CompanionMemory.Kind kind, String key,
                                                    BlockPos from, int maxDist) {
        CompanionMemory.Place best = null;
        double bd = (double) maxDist * maxDist;
        for (var p : m.all()) {
            if (p.kind != kind || !p.dim.equals(dim) || (key != null && !p.key.equals(key))) continue;
            double dd = p.pos.distSqr(from);
            if (dd < bd) { bd = dd; best = p; }
        }
        return best;
    }

    private static CompanionMemory.Place refChest(CompanionMemory m, String dim, Item item, BlockPos from, int maxDist) {
        CompanionMemory.Place best = null;
        double bd = (double) maxDist * maxDist;
        for (var p : m.all()) {
            if (p.kind != CompanionMemory.Kind.CHEST || !p.dim.equals(dim) || p.itemCount(item) <= 0) continue;
            double dd = p.pos.distSqr(from);
            if (dd < bd) { bd = dd; best = p; }
        }
        return best;
    }

    private static boolean refDangerous(CompanionMemory m, String dim, BlockPos pos, int r) {
        for (var p : m.all())
            if ((p.kind == CompanionMemory.Kind.DANGER || p.kind == CompanionMemory.Kind.DEATH) && p.dim.equals(dim)
                    && p.pos.distManhattan(pos) <= r) return true;
        return false;
    }

    // ---------------------------------------------------------------- Aufbau

    private static BlockPos randomPos(Random rnd, BlockPos origin) {
        return origin.offset(rnd.nextInt(601) - 300, rnd.nextInt(41) - 20, rnd.nextInt(601) - 300);
    }

    private static CompanionMemory filled(ServerLevel sl, BlockPos origin, Random rnd, int n) {
        var m = new CompanionMemory();
        String here = CompanionMemory.dim(sl);
        List<BlockPos> used = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            var kind = KINDS[rnd.nextInt(KINDS.length)];
            String key = kind == CompanionMemory.Kind.ORE ? ORE_KEYS[rnd.nextInt(ORE_KEYS.length)] : "";
            // Gleichstände erzwingen: manche Orte doppelt, manche gespiegelt um den Ursprung
            BlockPos pos;
            int pick = rnd.nextInt(10);
            if (pick == 0 && !used.isEmpty()) pos = used.get(rnd.nextInt(used.size()));
            else if (pick == 1 && !used.isEmpty()) {
                var other = used.get(rnd.nextInt(used.size()));
                pos = new BlockPos(2 * origin.getX() - other.getX(), other.getY(), 2 * origin.getZ() - other.getZ());
            } else pos = randomPos(rnd, origin);
            used.add(pos);
            String dim = rnd.nextInt(8) == 0 ? "minecraft:the_nether" : here;
            var place = new CompanionMemory.Place(kind, key, dim, pos, rnd.nextInt(1000));
            if (kind == CompanionMemory.Kind.CHEST)
                for (Item item : ITEMS) if (rnd.nextBoolean()) place.items.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(), 1 + rnd.nextInt(9));
            m.add(place);
        }
        return m;
    }

    private static void compare(GameTestHelper h, CompanionMemory m, ServerLevel sl, BlockPos origin, Random rnd, String phase) {
        String dim = CompanionMemory.dim(sl);
        h.assertTrue(m.indexConsistent(), phase + ": index and list differ");
        List<BlockPos> queries = new ArrayList<>();
        queries.add(origin);
        for (var p : m.all()) if (queries.size() < 40) queries.add(p.pos); // genau auf einem Eintrag
        for (int i = 0; i < 160; i++) queries.add(randomPos(rnd, origin));
        for (BlockPos q : queries) {
            for (int r : RADII) {
                for (var kind : KINDS) {
                    String key = kind == CompanionMemory.Kind.ORE ? ORE_KEYS[rnd.nextInt(ORE_KEYS.length)] : null;
                    var want = refNearest(m, dim, kind, key, q, r);
                    var got = m.nearest(kind, key, sl, q, r);
                    h.assertTrue(want == got, phase + ": nearest " + kind + "/" + key + " at " + q.toShortString() + " r=" + r
                            + " expected " + (want == null ? null : want.pos.toShortString()) + " got " + (got == null ? null : got.pos.toShortString()));
                }
                Item item = ITEMS[rnd.nextInt(ITEMS.length)];
                h.assertTrue(refChest(m, dim, item, q, r) == m.chestWith(item, sl, q, r), phase + ": chestWith differs at " + q.toShortString() + " r=" + r);
                h.assertTrue(refDangerous(m, dim, q, r) == m.dangerous(sl, q, r), phase + ": dangerous differs at " + q.toShortString() + " r=" + r);
            }
        }
    }

    @GameTest(maxTicks = 400)
    public void spatialIndexMatchesTheLinearReference(GameTestHelper h) {
        var sl = h.getLevel();
        var origin = h.absolutePos(BlockPos.ZERO);
        var rnd = new Random(1717);
        var m = filled(sl, origin, rnd, 1600);
        h.assertTrue(m.size() == 1600, "Fixture size");
        compare(h, m, sl, origin, rnd, "filled");

        // Vergessen: einzeln, im Umkreis und als Wegpunkt
        List<CompanionMemory.Place> snapshot = new ArrayList<>(m.all());
        for (int i = 0; i < snapshot.size(); i += 3) m.forget(snapshot.get(i));
        m.forgetNear(CompanionMemory.Kind.TREE, null, sl, origin, 120);
        m.setWaypoint("lager", sl, origin.east(5));
        m.setWaypoint("lager", sl, origin.west(5)); // verschoben, nicht doppelt
        h.assertTrue(m.waypoints().size() == 1, "Moved waypoint duplicated");
        h.assertTrue(m.removeWaypoint("lager"), "Waypoint not removed");
        compare(h, m, sl, origin, rnd, "after forgetting");

        // Speichern und Laden: gleiche Reihenfolge, Index neu aufgebaut
        var loaded = new CompanionMemory();
        loaded.load(m.save());
        compare(h, loaded, sl, origin, rnd, "after save/load");
        loaded.clear();
        h.assertTrue(loaded.size() == 0 && loaded.indexConsistent(), "Cleared memory keeps index entries");

        // Liste ist von außen nicht veränderbar
        boolean immutable;
        try { m.all().clear(); immutable = false; } catch (UnsupportedOperationException expected) { immutable = true; }
        h.assertTrue(immutable && m.indexConsistent(), "all() exposed the mutable list");
        h.succeed();
    }

    @GameTest
    public void rememberMergesLikeBeforeAndKeepsTheIndexConsistent(GameTestHelper h) {
        var sl = h.getLevel();
        var origin = h.absolutePos(new BlockPos(1, 1, 1));
        var m = new CompanionMemory();
        // Chunkgrenze: Ader auf beiden Seiten wird zu einem Eintrag zusammengelegt
        var a = m.remember(CompanionMemory.Kind.ORE, "eisen", sl, new BlockPos((origin.getX() & ~15) + 15, origin.getY(), origin.getZ()));
        var b = m.remember(CompanionMemory.Kind.ORE, "eisen", sl, new BlockPos((origin.getX() & ~15) + 16, origin.getY(), origin.getZ()));
        h.assertTrue(a == b && a.count == 2 && m.size() == 1, "Vein across a chunk border not merged");
        var other = m.remember(CompanionMemory.Kind.ORE, "kohle", sl, a.pos);
        h.assertTrue(other != a && m.size() == 2, "Different ore merged");
        var village1 = m.remember(CompanionMemory.Kind.VILLAGE, "", sl, origin);
        var village2 = m.remember(CompanionMemory.Kind.VILLAGE, "", sl, origin.offset(40, 0, 20));
        h.assertTrue(village1 == village2, "Village within merge distance not merged across chunks");
        for (int i = 0; i < 400; i++) m.remember(CompanionMemory.Kind.TREE, "", sl, origin.offset(i * 20, 0, 0));
        h.assertTrue(m.counts().get(CompanionMemory.Kind.TREE) == 160 && m.indexConsistent(), "Trim broke the index");
        h.succeed();
    }
}
