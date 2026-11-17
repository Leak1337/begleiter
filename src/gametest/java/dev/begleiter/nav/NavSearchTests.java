package dev.begleiter.nav;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMod;

/** Wegfindung: Heap, Bewegungen, Ziele, Suche mit Teilwegen, Nachprüfung. */
public class NavSearchTests {

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    private static void box(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) h.setBlock(x, y, z, b);
    }

    /** Barriere auf dem Rand (x/z = 0 und 7). */
    private static void ring(GameTestHelper h, int fromY) {
        for (int i = 0; i <= 7; i++)
            for (int y = fromY; y <= 7; y++) {
                h.setBlock(i, y, 0, Blocks.BARRIER);
                h.setBlock(i, y, 7, Blocks.BARRIER);
                h.setBlock(0, y, i, Blocks.BARRIER);
                h.setBlock(7, y, i, Blocks.BARRIER);
            }
    }

    private static CompanionEntity companion(GameTestHelper h, BlockPos rel) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, rel);
        c.setPersistenceRequired();
        c.stay();
        c.getInventory().clearContent();
        c.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return c;
    }

    private static PathSearch search(GameTestHelper h, NavContext ctx, BlockPos fromRel, BlockPos toRel) {
        PathSearch s = new PathSearch(ctx, h.absolutePos(fromRel), new Goal.Block(h.absolutePos(toRel)));
        s.step(1_000_000_000L);
        return s;
    }

    /** Relativ zur Testfl�che (GameTestHelper.relativePos liefert in 26.3 gespiegelte Werte). */
    private static BlockPos rel(GameTestHelper h, BlockPos abs) {
        return abs.subtract(h.absolutePos(BlockPos.ZERO));
    }

    private static String describe(PathSearch s) {
        Path p = s.path();
        return s.status() + ", " + s.nodes() + " Knoten" + (p == null ? "" : ", " + p.moves);
    }

    @GameTest
    public void heapGibtKleinsteZuerst(GameTestHelper h) {
        NodeHeap heap = new NodeHeap();
        NodeMap map = new NodeMap();
        Goal g = new Goal.Block(0, 0, 0);
        java.util.Random rnd = new java.util.Random(42);
        java.util.List<Node> all = new java.util.ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            Node n = map.getOrCreate(i, 0, 0, g);
            n.f = rnd.nextDouble() * 1000;
            heap.add(n);
            all.add(n);
        }
        for (int i = 0; i < 300; i++) { // Kosten sinken bei einigen
            Node n = all.get(rnd.nextInt(all.size()));
            n.f = n.f * 0.5;
            heap.update(n);
        }
        double last = -1;
        int count = 0;
        while (!heap.isEmpty()) {
            Node n = heap.poll();
            h.assertTrue(n.f >= last, "Reihenfolge falsch bei " + count + ": " + n.f + " < " + last);
            h.assertTrue(!heap.contains(n), "Entnommener Knoten noch im Heap");
            last = n.f;
            count++;
        }
        h.assertValueEqual(count, 3000, "Anzahl");
        h.succeed();
    }

    @GameTest
    public void heapBleibtKorrektBeimWiederverwenden(GameTestHelper h) {
        NodeHeap heap = new NodeHeap();
        java.util.List<Node> active = new java.util.ArrayList<>();
        java.util.Random rnd = new java.util.Random(1337);
        // Die Anfangskapazität überschreiten und beide Felder beim Wachsen prüfen.
        for (int i = 0; i < 2200; i++) {
            Node n = new Node(i, 0, 0, 0);
            n.f = rnd.nextInt(64); // Many ties, including zero.
            heap.add(n);
            active.add(n);
        }
        for (int i = 0; i < 1200; i++) {
            Node cheaper = active.get(rnd.nextInt(active.size()));
            cheaper.f = Math.max(0, cheaper.f - 17);
            heap.update(cheaper);
            double expected = active.stream().mapToDouble(n -> n.f).min().orElseThrow();
            Node taken = heap.poll();
            h.assertTrue(taken.f == expected, "Falsches Minimum nach Kostenänderung");
            h.assertTrue(!heap.contains(taken), "Entnommener Knoten noch aktiv");
            active.remove(taken);
            if ((i & 1) == 0) {
                // A removed node can be opened again with an entirely new priority.
                taken.f = 100 + rnd.nextInt(64);
                heap.add(taken);
                active.add(taken);
            }
            h.assertValueEqual(heap.size(), active.size(), "Heap-Größe nach Wiederaufnahme");
        }
        double previous = -1;
        while (!heap.isEmpty()) {
            Node n = heap.poll();
            h.assertTrue(n.f >= previous, "Reihenfolge nach Wiederaufnahme falsch");
            h.assertTrue(!heap.contains(n), "Heap-Index nach Entnahme nicht zurückgesetzt");
            previous = n.f;
        }
        boolean rejected = false;
        try { heap.poll(); } catch (IllegalStateException expected) { rejected = true; }
        h.assertTrue(rejected && heap.size() == 0, "Leere Entnahme muss Größe erhalten");
        Node single = active.getFirst();
        single.f = 3;
        heap.add(single);
        h.assertTrue(heap.poll() == single && heap.isEmpty(), "Heap nach Leeren nicht wiederverwendbar");
        h.succeed();
    }

    @GameTest
    public void sucheBleibtInnerhalbDerWeltgrenze(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        NavContext ctx = new NavContext(c);
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        ctx.worldBorder = new dev.begleiter.thirdparty.baritone.BetterWorldBorder(
                origin.getX(), origin.getX() + 4, origin.getZ(), origin.getZ() + 8);
        PathSearch s = search(h, ctx, new BlockPos(1, 1, 3), new BlockPos(6, 1, 3));
        h.assertTrue(s.status() != PathSearch.Status.FOUND, "Weg durch die Weltgrenze geplant");
        Path p = s.path();
        if (p != null) for (BlockPos at : p.positions)
            h.assertTrue(at.getX() < origin.getX() + 4, "Teilweg außerhalb der Weltgrenze");
        h.succeed();
    }

    @GameTest
    public void setztKeineBrueckeAmWeltgrenzenrand(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        c.giveToInventory(new ItemStack(Items.DIRT, 8));
        NavContext ctx = new NavContext(c);
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        ctx.worldBorder = new dev.begleiter.thirdparty.baritone.BetterWorldBorder(
                origin.getX(), origin.getX() + 4, origin.getZ(), origin.getZ() + 8);
        h.setBlock(3, 0, 3, Blocks.AIR);
        BlockPos from = h.absolutePos(new BlockPos(2, 1, 3));
        MoveResult r = new MoveResult();
        Move.TRAVERSE_E.cost(ctx, from.getX(), from.getY(), from.getZ(), r);
        h.assertTrue(r.cost >= Costs.INF, "Brückenblock direkt am Weltgrenzenrand erlaubt");
        h.succeed();
    }

    @GameTest
    public void flachSchraegZumZiel(GameTestHelper h) {
        floor(h);
        PathSearch s = search(h, new NavContext(companion(h, new BlockPos(1, 1, 1))), new BlockPos(1, 1, 1), new BlockPos(6, 1, 6));
        h.assertTrue(s.status() == PathSearch.Status.FOUND, "Kein Weg: " + describe(s));
        Path p = s.path();
        h.assertValueEqual(p.length(), 5, "Schritte (5 schräg)");
        h.assertValueEqual(p.count(Move.Kind.DIAGONAL), 5, "Diagonalen");
        h.succeed();
    }

    @GameTest
    public void bautBrueckeMitBloecken(GameTestHelper h) {
        for (int x = 1; x <= 6; x++) for (int z = 1; z <= 6; z++) if (x < 3 || x > 4) h.setBlock(x, 3, z, Blocks.STONE);
        ring(h, 0);
        CompanionEntity c = companion(h, new BlockPos(1, 4, 3));
        c.giveToInventory(new ItemStack(Items.DIRT, 16));
        NavContext ctx = new NavContext(c);
        ctx.allowParkour = false;
        PathSearch s = search(h, ctx, new BlockPos(1, 4, 3), new BlockPos(6, 4, 3));
        h.assertTrue(s.status() == PathSearch.Status.FOUND, "Kein Weg: " + describe(s));
        boolean overGap = false;
        for (BlockPos p : s.path().positions) {
            BlockPos rel = rel(h, p);
            overGap |= rel.getY() == 4 && (rel.getX() == 3 || rel.getX() == 4);
        }
        h.assertTrue(overGap, "Nicht über die Schlucht: " + describe(s));
        h.succeed();
    }

    @GameTest
    public void ohneBloeckeKeineBruecke(GameTestHelper h) {
        for (int x = 1; x <= 6; x++) for (int z = 1; z <= 6; z++) if (x < 3 || x > 4) h.setBlock(x, 3, z, Blocks.STONE);
        ring(h, 0);
        CompanionEntity c = companion(h, new BlockPos(1, 4, 3));
        NavContext ctx = new NavContext(c);
        ctx.allowParkour = false;
        PathSearch s = search(h, ctx, new BlockPos(1, 4, 3), new BlockPos(6, 4, 3));
        h.assertTrue(s.status() != PathSearch.Status.FOUND, "Weg ohne Blöcke: " + describe(s));
        h.succeed();
    }

    @GameTest
    public void graebtNurWennErlaubt(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 4, 1, 1, 4, 7, 6, Blocks.DIRT);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        NavContext ctx = new NavContext(c);
        PathSearch s = search(h, ctx, new BlockPos(1, 1, 3), new BlockPos(6, 1, 3));
        h.assertTrue(s.status() == PathSearch.Status.FOUND, "Kein Weg durch die Erde: " + describe(s));
        h.assertTrue(s.path().totalCost() > 2 * NavContext.ticksFor(1f / 15) - 1, "Abbauen nicht bezahlt: " + s.path().totalCost());
        NavContext noBreak = new NavContext(c);
        noBreak.allowBreak = false;
        PathSearch s2 = search(h, noBreak, new BlockPos(1, 1, 3), new BlockPos(6, 1, 3));
        h.assertTrue(s2.status() != PathSearch.Status.FOUND, "Weg ohne Abbauen: " + describe(s2));
        h.succeed();
    }

    @GameTest
    public void bautSichHoch(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 5, 1, 1, 6, 3, 6, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        c.giveToInventory(new ItemStack(Items.DIRT, 8));
        PathSearch s = search(h, new NavContext(c), new BlockPos(1, 1, 3), new BlockPos(5, 4, 3));
        h.assertTrue(s.status() == PathSearch.Status.FOUND, "Kein Weg nach oben: " + describe(s));
        h.assertTrue(s.path().count(Move.Kind.PILLAR) + s.path().count(Move.Kind.ASCEND) >= 3, "Nicht hochgebaut: " + describe(s));
        c.getInventory().clearContent();
        PathSearch s2 = search(h, new NavContext(c), new BlockPos(1, 1, 3), new BlockPos(5, 4, 3));
        h.assertTrue(s2.status() != PathSearch.Status.FOUND, "Hoch ohne Blöcke/Spitzhacke: " + describe(s2));
        h.succeed();
    }

    @GameTest
    public void meidetLava(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        for (int z = 1; z <= 5; z++) h.setBlock(3, 0, z, Blocks.LAVA);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        c.giveToInventory(new ItemStack(Items.DIRT, 16));
        PathSearch s = search(h, new NavContext(c), new BlockPos(1, 1, 3), new BlockPos(5, 1, 3));
        h.assertTrue(s.status() == PathSearch.Status.FOUND, "Kein Weg: " + describe(s));
        boolean around = false;
        for (BlockPos p : s.path().positions) {
            BlockPos rel = rel(h, p);
            h.assertTrue(!(rel.getX() == 3 && rel.getZ() <= 5), "Über die Lava: " + rel.toShortString());
            around |= rel.getX() == 3 && rel.getZ() == 6;
        }
        h.assertTrue(around, "Nicht außen herum: " + describe(s));
        h.succeed();
    }

    @GameTest
    public void fallHoeheNachLeben(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 3, 1, 3, 3, 5, 3, Blocks.STONE); // Säule, oben steht er auf y=6 (5 tief)
        CompanionEntity c = companion(h, new BlockPos(3, 6, 3));
        c.setHealth(c.getMaxHealth());
        PathSearch s = search(h, new NavContext(c), new BlockPos(3, 6, 3), new BlockPos(6, 1, 3));
        h.assertTrue(s.status() == PathSearch.Status.FOUND, "Gesund nicht runter: " + describe(s));
        c.setHealth(9); // 5 tief = 2 Schaden, dann blieben nur 7
        PathSearch s2 = search(h, new NavContext(c), new BlockPos(3, 6, 3), new BlockPos(6, 1, 3));
        h.assertTrue(s2.status() != PathSearch.Status.FOUND, "Mit 9 Leben 5 tief gesprungen: " + describe(s2));
        h.succeed();
    }

    @GameTest
    public void springtUeberLuecke(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 1, 1, 1, 2, 3, 6, Blocks.STONE);
        box(h, 5, 1, 1, 6, 3, 6, Blocks.STONE); // Lücke x=3..4, 3 tief
        CompanionEntity c = companion(h, new BlockPos(1, 4, 3));
        PathSearch s = search(h, new NavContext(c), new BlockPos(1, 4, 3), new BlockPos(6, 4, 3));
        h.assertTrue(s.status() == PathSearch.Status.FOUND, "Kein Sprung: " + describe(s));
        h.assertValueEqual(s.path().count(Move.Kind.PARKOUR), 1, "Sprünge");
        NavContext noJump = new NavContext(c);
        noJump.allowParkour = false;
        h.assertTrue(search(h, noJump, new BlockPos(1, 4, 3), new BlockPos(6, 4, 3)).status() != PathSearch.Status.FOUND,
                "ohne Parkour drüben");
        h.succeed();
    }

    @GameTest(structure = "begleiter-gametest:bench")
    public void teilwegWennZielUnerreichbar(GameTestHelper h) {
        box(h, 0, 0, 0, 44, 0, 11, Blocks.STONE);
        box(h, 40, 1, 4, 44, 4, 8, Blocks.BARRIER);
        box(h, 41, 1, 5, 43, 3, 7, Blocks.AIR); // Ziel in geschlossener Kammer
        CompanionEntity c = companion(h, new BlockPos(2, 1, 6));
        PathSearch s = new PathSearch(new NavContext(c), h.absolutePos(new BlockPos(2, 1, 6)),
                new Goal.Block(h.absolutePos(new BlockPos(42, 1, 6))));
        s.maxNodes = 2000;
        s.step(1_000_000_000L);
        h.assertTrue(s.status() == PathSearch.Status.PARTIAL, "Status " + describe(s));
        Path p = s.path();
        h.assertTrue(!p.complete, "Teilweg als komplett markiert");
        h.assertTrue(rel(h, p.end()).getX() >= 30, "Teilweg kommt nicht voran: " + rel(h, p.end()).toShortString());
        Path trimmed = p.trimPartial();
        h.assertTrue(trimmed.length() <= p.length() && trimmed.length() >= Math.min(30, p.length()), "Kürzen falsch");
        h.succeed();
    }

    @GameTest
    public void nachpruefungKuerztVersperrtenWeg(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        NavContext ctx = new NavContext(c);
        ctx.allowBreak = false;
        PathSearch s = search(h, ctx, new BlockPos(1, 1, 3), new BlockPos(6, 1, 3));
        h.assertTrue(s.status() == PathSearch.Status.FOUND, "Kein Weg: " + describe(s));
        h.assertTrue(s.path().verify(ctx).complete, "Unveränderter Weg nicht mehr gültig");
        box(h, 4, 1, 1, 4, 2, 6, Blocks.BARRIER);
        Path v = s.path().verify(ctx);
        h.assertTrue(!v.complete && v.length() < s.path().length(), "Versperrter Weg nicht gekürzt: " + v.moves);
        h.succeed();
    }
}
