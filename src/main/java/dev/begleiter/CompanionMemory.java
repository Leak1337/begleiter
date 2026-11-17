package dev.begleiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import dev.begleiter.world.SectionScanner;

/**
 * Gedächtnis des Begleiters: Orte, die er selbst gesehen hat (Erze, Bäume, Wasser, Lava, Spawner, Portale,
 * Dörfer), Kisteninhalte und gefährliche Stellen – pro Dimension. Einträge werden beim Vorbeikommen
 * überprüft und vergessen, wenn sie nicht mehr stimmen; sehr alte Einträge verblassen.
 */
public final class CompanionMemory {

    public enum Kind {
        ORE(2), TREE(8), WATER(12), LAVA(6), SPAWNER(0), PORTAL(4), VILLAGE(64), CHEST(0), DANGER(4), DEATH(2),
        /** Vom Spieler benannte Orte ("Merk dir das als Mine"); Schlüssel = Name. Werden nie vergessen. */
        WAYPOINT(0);

        /** Einträge gleicher Art und Schlüssel, die näher als das beieinander liegen, werden zusammengelegt. */
        final int merge;
        Kind(int merge) { this.merge = merge; }
    }

    public static final class Place {
        public final Kind kind;
        /** Erz: Resources-Schlüssel ("eisen"), Gefahr: Grund, sonst leer. */
        public final String key;
        public final String dim;
        public final BlockPos pos;
        /** Wie viele Blöcke hier (Erzader, Bäume ...). */
        public int count = 1;
        public long seen;
        /** Kisten: Inhalt als vollständige Registry-ID (mit Namensraum) -> Anzahl. */
        public final Map<String, Integer> items = new LinkedHashMap<>();

        public int itemCount(Item item) {
            return items.getOrDefault(BuiltInRegistries.ITEM.getKey(item).toString(), 0);
        }

        Place(Kind kind, String key, String dim, BlockPos pos, long seen) {
            this.kind = kind;
            this.key = canonicalKey(kind, key);
            this.dim = dim;
            this.pos = pos.immutable();
            this.seen = seen;
        }
    }

    /** Höchstzahlen je Art, damit das Gedächtnis nicht endlos wächst (älteste fliegen zuerst raus). */
    private static final int MAX_PER_KIND = 160;
    /** Nach dieser Zeit ohne Bestätigung gilt ein Eintrag als vergessen (10 Ingame-Tage). */
    private static final long FORGET_AFTER = 240_000L;

    private static final Resources.Res[] ORES = {Resources.COAL, Resources.IRON, Resources.GOLD, Resources.DIAMOND,
            Resources.REDSTONE, Resources.LAPIS, Resources.COPPER, Resources.EMERALD, Resources.OBSIDIAN};

    private final List<Place> places = new ArrayList<>();
    private int scanLayer;

    private static String canonicalKey(Kind kind, String key) {
        if (key == null) return "";
        return kind == Kind.SPAWNER && !key.isEmpty() ? RegistryIds.canonicalOrOriginal(key) : key;
    }

    public static boolean isOre(Resources.Res res) {
        for (Resources.Res r : ORES) if (r == res) return true;
        return false;
    }

    public static String dim(ServerLevel sl) { return sl.dimension().identifier().toString(); }

    public List<Place> all() { return places; }
    public int size() { return places.size(); }
    public void clear() { places.clear(); }

    // ------------------------------------------------------------------ Merken / Vergessen

    public Place remember(Kind kind, String key, ServerLevel sl, BlockPos pos) {
        String d = dim(sl);
        long now = sl.getGameTime();
        String k = canonicalKey(kind, key);
        for (Place p : places) {
            if (p.kind != kind || !p.dim.equals(d) || !p.key.equals(k)) continue;
            if (p.pos.equals(pos)) { p.seen = now; return p; }
            if (kind.merge > 0 && p.pos.distManhattan(pos) <= kind.merge) {
                p.seen = now;
                if (kind == Kind.ORE || kind == Kind.TREE) p.count = Math.min(64, p.count + 1);
                return p;
            }
        }
        Place np = new Place(kind, k, d, pos, now);
        places.add(np);
        trim(kind, k);
        return np;
    }

    /**
     * Älteste Einträge vergessen, wenn es zu viele werden. Erze je Sorte: sonst verdrängen die vielen Kohle-/Eisenadern
     * des Erz-Gespürs den einen seltenen Diamanten.
     */
    private void trim(Kind kind, String key) {
        boolean perKey = kind == Kind.ORE;
        int max = MAX_PER_KIND;
        java.util.function.Predicate<Place> same0 = p -> p.kind == kind && (!perKey || p.key.equals(key));
        long count = places.stream().filter(same0).count();
        if (count <= max) return;
        List<Place> same = places.stream().filter(same0).sorted(Comparator.comparingLong(p -> p.seen)).toList();
        for (int i = 0; i < same.size() - max; i++) places.remove(same.get(i));
    }

    public void forget(Place p) { places.remove(p); }

    /** Als falsch erkannte Orte (hingegangen, nichts Brauchbares): eine Weile nicht wieder merken, sonst Endlosschleife. */
    private final List<Place> rejected = new ArrayList<>();
    private static final long REJECT_FOR = 6000L; // 5 Minuten

    /** Vergessen und so lange nicht neu lernen, bis {@link #REJECT_FOR} vorbei ist. */
    public void reject(Place p, ServerLevel sl) {
        places.remove(p);
        rejected.removeIf(r -> sl.getGameTime() - r.seen > REJECT_FOR);
        rejected.add(new Place(p.kind, p.key, p.dim, p.pos, sl.getGameTime()));
    }

    private boolean isRejected(Kind kind, String key, String dim, BlockPos pos, long now) {
        String k = canonicalKey(kind, key);
        for (Place r : rejected)
            if (r.kind == kind && r.key.equals(k) && r.dim.equals(dim) && now - r.seen <= REJECT_FOR
                    && r.pos.distManhattan(pos) <= Math.max(1, kind.merge)) return true;
        return false;
    }

    public void forgetNear(Kind kind, String key, ServerLevel sl, BlockPos pos, int r) {
        String d = dim(sl);
        String wanted = key == null ? null : canonicalKey(kind, key);
        places.removeIf(p -> p.kind == kind && p.dim.equals(d) && (wanted == null || p.key.equals(wanted))
                && p.pos.distManhattan(pos) <= r);
    }

    /** Kisteninhalt merken (wenn er hineinschaut). */
    public void noteChest(ServerLevel sl, BlockPos pos, Container box) {
        Place p = remember(Kind.CHEST, "", sl, pos);
        p.items.clear();
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (!s.isEmpty()) p.items.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
        }
    }

    // ------------------------------------------------------------------ Abfragen

    /** Nächster bekannter Ort dieser Art (key null = egal) in derselben Dimension, höchstens maxDist Blöcke weit. */
    public Place nearest(Kind kind, String key, ServerLevel sl, BlockPos from, int maxDist) {
        String d = dim(sl);
        String wanted = key == null ? null : canonicalKey(kind, key);
        Place best = null;
        double bd = (double) maxDist * maxDist;
        for (Place p : places) {
            if (p.kind != kind || !p.dim.equals(d) || (wanted != null && !p.key.equals(wanted))) continue;
            double dd = p.pos.distSqr(from);
            if (dd < bd) { bd = dd; best = p; }
        }
        return best;
    }

    public List<Place> find(Kind kind, String key, ServerLevel sl, BlockPos from) {
        String d = dim(sl);
        String wanted = key == null ? null : canonicalKey(kind, key);
        return places.stream().filter(p -> p.kind == kind && p.dim.equals(d) && (wanted == null || p.key.equals(wanted)))
                .sorted(Comparator.comparingDouble(p -> p.pos.distSqr(from))).toList();
    }

    /** Bekannte Kiste mit diesem Item (laut letztem Hineinschauen). */
    public Place chestWith(Item item, ServerLevel sl, BlockPos from, int maxDist) {
        return chestWith(item, sl, from, maxDist, p -> true);
    }

    public Place chestWith(Item item, ServerLevel sl, BlockPos from, int maxDist,
                           java.util.function.Predicate<BlockPos> allowed) {
        String d = dim(sl);
        Place best = null;
        double bd = (double) maxDist * maxDist;
        for (Place p : places) {
            if (p.kind != Kind.CHEST || !p.dim.equals(d) || p.itemCount(item) <= 0) continue;
            if (!allowed.test(p.pos)) continue;
            double dd = p.pos.distSqr(from);
            if (dd < bd) { bd = dd; best = p; }
        }
        return best;
    }

    /** Ist diese Stelle als gefährlich bekannt (Gefahr oder Todesort im Umkreis r)? */
    public boolean dangerous(ServerLevel sl, BlockPos pos, int r) {
        String d = dim(sl);
        for (Place p : places)
            if ((p.kind == Kind.DANGER || p.kind == Kind.DEATH) && p.dim.equals(d) && p.pos.distManhattan(pos) <= r) return true;
        return false;
    }

    // ------------------------------------------------------------------ Wahrnehmung

    /**
     * Eine Schicht der Umgebung ansehen (verteilt über viele Ticks: alle 21 Schichten in ~5 s). Gemerkt wird nur,
     * was er wirklich sieht (freie Seite + Sichtlinie), wie ein Spieler ohne Röntgenblick.
     */
    public void scan(CompanionEntity e, ServerLevel sl) {
        int dy = scanLayer - 10;
        scanLayer = (scanLayer + 1) % 21;
        BlockPos c = e.blockPosition();
        int r = 20;
        int y = c.getY() + dy;
        if (y < sl.getMinY() || y > sl.getMaxY()) return;
        int sightChecks = 0, bulkChecks = 0;
        // Zuletzt bestätigter Eintrag: In einem See oder Wald liegen die meisten Treffer in seinem Umkreis, dann
        // entfällt die lineare Suche über das ganze Gedächtnis (bis zu einige Tausend Einträge je Block).
        Place lastKnown = null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                m.set(c.getX() + x, y, c.getZ() + z);
                if (!sl.hasChunkAt(m)) continue;
                BlockState s = sl.getBlockState(m);
                if (s.isAir()) continue;
                Kind kind = null;
                String key = null;
                if (s.is(BlockTags.LOGS)) {
                    kind = Kind.TREE;
                } else if (s.is(Blocks.SPAWNER)) {
                    kind = Kind.SPAWNER;
                    key = spawnerType(sl, m);
                } else if (s.is(Blocks.NETHER_PORTAL)) {
                    kind = Kind.PORTAL;
                } else if (s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock) {
                    kind = Kind.CHEST;
                } else if (s.getFluidState().isSourceOfType(Fluids.WATER)) {
                    kind = Kind.WATER;
                } else if (s.getFluidState().isSourceOfType(Fluids.LAVA)) {
                    kind = Kind.LAVA;
                } else {
                    for (Resources.Res res : ORES) if (res.block().test(s)) { kind = Kind.ORE; key = res.key(); break; }
                }
                if (kind == null) continue;
                // Schon bekannt? Nur auffrischen, keine teure Sichtprüfung.
                if (lastKnown != null && lastKnown.kind == kind && kind.merge > 0
                        && lastKnown.key.equals(canonicalKey(kind, key)) && lastKnown.pos.distManhattan(m) <= kind.merge) continue;
                BlockPos p = m.immutable();
                Place known = nearest(kind, key, sl, p, Math.max(1, kind.merge + 1));
                if (known != null && (kind.merge > 0 || known.pos.equals(p))) {
                    known.seen = sl.getGameTime();
                    lastKnown = known;
                    continue;
                }
                if (isRejected(kind, key, dim(sl), p, sl.getGameTime())) continue;
                // Sichtprüfungen begrenzen; Wasser/Lava/Bäume haben ein eigenes kleines Kontingent, damit ein See
                // nicht die Prüfungen für Erze und Kisten aufbraucht
                boolean bulk = kind == Kind.WATER || kind == Kind.LAVA || kind == Kind.TREE;
                if (bulk ? ++bulkChecks > 12 : ++sightChecks > 40) continue;
                if (!e.seesBlock(sl, p)) continue;
                if (kind == Kind.TREE && !hasLeaves(sl, p)) continue; // Holzhaus ist kein Baum
                remember(kind, key, sl, p);
            }
        }
    }

    /** Erz-Gespür ("Röntgenblick" nur für Erze): Umkreis waagerecht und senkrecht. Nicht die halbe Welt. */
    public static final int XRAY_RADIUS = 32, XRAY_HEIGHT = 24;
    private int xrayLayer;

    /**
     * Erze auch durch Wände spüren, aber nur im Umkreis von 32 Blöcken (±24 Höhe). Eine Schicht pro Aufruf,
     * eine komplette Runde dauert so etwa 5 Sekunden.
     */
    public void xrayScan(CompanionEntity e, ServerLevel sl) {
        int dy = xrayLayer - XRAY_HEIGHT;
        xrayLayer = (xrayLayer + 1) % (XRAY_HEIGHT * 2 + 1);
        BlockPos c = e.blockPosition();
        int y = c.getY() + dy;
        if (y < sl.getMinY() || y > sl.getMaxY()) return;
        // Lange erfolglose Diamantsuche: weiterer Ring, in dem er nur Diamanten spürt
        int r = Math.max(XRAY_RADIUS, e.diamondSenseRadius());
        SectionScanner.scanLayer(sl, c.getX() - r, c.getX() + r, y, c.getZ() - r, c.getZ() + r,
                CompanionMemory::xrayTarget, (x, scanY, z, s) -> {
                if (Math.abs(x - c.getX()) > XRAY_RADIUS || Math.abs(z - c.getZ()) > XRAY_RADIUS) {
                    if (!Resources.DIAMOND.block().test(s)) return true;
                    BlockPos p = new BlockPos(x, scanY, z);
                    Place known = nearest(Kind.ORE, Resources.DIAMOND.key(), sl, p, Kind.ORE.merge + 1);
                    if (known != null) known.seen = sl.getGameTime();
                    else remember(Kind.ORE, Resources.DIAMOND.key(), sl, p);
                    return true;
                }
                // Lava spürt er auch (für Obsidian: Wasser drauf); ein Eintrag pro See reicht
                if (s.getFluidState().isSourceOfType(Fluids.LAVA)) {
                    BlockPos p = new BlockPos(x, scanY, z);
                    Place known = nearest(Kind.LAVA, null, sl, p, Kind.LAVA.merge + 1);
                    if (known != null) known.seen = sl.getGameTime();
                    else remember(Kind.LAVA, "", sl, p);
                    return true;
                }
                if (!s.getFluidState().isEmpty()) return true;
                for (Resources.Res res : ORES) {
                    if (res == Resources.OBSIDIAN || !res.block().test(s)) continue;
                    BlockPos p = new BlockPos(x, scanY, z);
                    Place known = nearest(Kind.ORE, res.key(), sl, p, Kind.ORE.merge + 1);
                    if (known != null) known.seen = sl.getGameTime();
                    else remember(Kind.ORE, res.key(), sl, p);
                    break;
                }
                return true;
        });
    }

    private static boolean xrayTarget(BlockState state) {
        // Der erweiterte Ring prüft Diamantblöcke unabhängig vom Fluidzustand.
        if (Resources.DIAMOND.block().test(state)) return true;
        if (state.getFluidState().isSourceOfType(Fluids.LAVA)) return true;
        if (!state.getFluidState().isEmpty()) return false;
        for (Resources.Res res : ORES)
            if (res != Resources.OBSIDIAN && res.block().test(state)) return true;
        return false;
    }

    /** Dorf in der Nähe? (über die Dorf-Punkte des Spiels: Glocke, Betten, Arbeitsplätze). */
    public void checkVillage(CompanionEntity e, ServerLevel sl) {
        if (sl.isVillage(e.blockPosition())) remember(Kind.VILLAGE, "", sl, e.blockPosition());
    }

    /** Einträge in der Nähe nachprüfen: stimmt nicht mehr -> vergessen. Uralte Einträge verblassen. */
    public void verify(CompanionEntity e, ServerLevel sl) {
        String d = dim(sl);
        BlockPos c = e.blockPosition();
        long now = sl.getGameTime();
        places.removeIf(p -> {
            if (p.kind == Kind.WAYPOINT) return false;
            if (p.kind != Kind.DEATH && p.kind != Kind.CHEST && now - p.seen > FORGET_AFTER) return true;
            if (!p.dim.equals(d) || p.pos.distSqr(c) > 24 * 24 || !sl.hasChunkAt(p.pos)) return false;
            return !stillThere(sl, p);
        });
    }

    private static boolean stillThere(ServerLevel sl, Place p) {
        BlockState s = sl.getBlockState(p.pos);
        return switch (p.kind) {
            case ORE -> {
                Resources.Res res = Resources.byKey(p.key);
                if (res == null) yield false;
                // Ader: irgendein Block dieser Art rund um den gemerkten Punkt
                for (BlockPos q : BlockPos.betweenClosed(p.pos.offset(-2, -2, -2), p.pos.offset(2, 2, 2)))
                    if (res.block().test(sl.getBlockState(q))) yield true;
                yield false;
            }
            case TREE -> {
                for (BlockPos q : BlockPos.betweenClosed(p.pos.offset(-3, -3, -3), p.pos.offset(3, 6, 3)))
                    if (sl.getBlockState(q).is(BlockTags.LOGS)) yield true;
                yield false;
            }
            case WATER -> sl.getFluidState(p.pos).isSourceOfType(Fluids.WATER) || nearbyFluid(sl, p.pos, true);
            case LAVA -> sl.getFluidState(p.pos).isSourceOfType(Fluids.LAVA) || nearbyFluid(sl, p.pos, false);
            case SPAWNER -> s.is(Blocks.SPAWNER);
            case PORTAL -> s.is(Blocks.NETHER_PORTAL);
            case CHEST -> s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock;
            case VILLAGE, DANGER, DEATH, WAYPOINT -> true;
        };
    }

    private static boolean nearbyFluid(ServerLevel sl, BlockPos c, boolean water) {
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-3, -2, -3), c.offset(3, 2, 3)))
            if (sl.getFluidState(q).isSourceOfType(water ? Fluids.WATER : Fluids.LAVA)) return true;
        return false;
    }

    private static boolean hasLeaves(ServerLevel sl, BlockPos p) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, 0, -2), p.offset(2, 6, 2)))
            if (sl.getBlockState(q).is(BlockTags.LEAVES) && ++n >= 3) return true;
        return false;
    }

    private static String spawnerType(ServerLevel sl, BlockPos p) {
        if (sl.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity sp) {
            var ent = sp.getSpawner().getOrCreateDisplayEntity(sl, p);
            if (ent != null) return BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).toString();
        }
        return "";
    }

    // ------------------------------------------------------------------ Wegpunkte

    /** Benannten Ort setzen (gleicher Name in dieser Dimension wird verschoben). */
    public Place setWaypoint(String name, ServerLevel sl, BlockPos pos) {
        String d = dim(sl);
        places.removeIf(p -> p.kind == Kind.WAYPOINT && p.key.equals(name) && p.dim.equals(d));
        Place np = new Place(Kind.WAYPOINT, name, d, pos, sl.getGameTime());
        places.add(np);
        return np;
    }

    /** Wegpunkt mit diesem Namen (auch Teilwort, z.B. "mine" findet "eisen mine"), zuerst in dieser Dimension. */
    public Place waypoint(String name, ServerLevel sl) {
        if (name == null || name.isBlank()) return null;
        String d = dim(sl);
        Place partial = null, other = null;
        for (Place p : places) {
            if (p.kind != Kind.WAYPOINT) continue;
            boolean same = p.dim.equals(d);
            if (p.key.equals(name)) { if (same) return p; other = p; }
            else if ((p.key.contains(name) || name.contains(p.key)) && same && partial == null) partial = p;
        }
        return partial != null ? partial : other;
    }

    /** Ein Wegpunkt, dessen Name im Satz vorkommt (für "geh zur mine"); längster Name gewinnt. */
    public Place waypointIn(String text, ServerLevel sl) {
        Place best = null;
        String padded = " " + text + " ";
        for (Place p : places) {
            if (p.kind != Kind.WAYPOINT || !p.dim.equals(dim(sl))) continue;
            if (padded.contains(" " + p.key + " ") && (best == null || p.key.length() > best.key.length())) best = p;
        }
        return best;
    }

    public boolean removeWaypoint(String name) {
        return places.removeIf(p -> p.kind == Kind.WAYPOINT && p.key.equals(name));
    }

    /** Alle erinnerten Orte, nur lesend (z.B. für den LLM-Kontext). */
    public List<Place> places() {
        return java.util.Collections.unmodifiableList(places);
    }

    public List<Place> waypoints() {
        return places.stream().filter(p -> p.kind == Kind.WAYPOINT).toList();
    }


    // ------------------------------------------------------------------ Speichern

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("V", 1);
        ListTag list = new ListTag();
        for (Place p : places) {
            CompoundTag pt = new CompoundTag();
            pt.putString("K", p.kind.name());
            if (!p.key.isEmpty()) pt.putString("Key", p.key);
            pt.putString("D", p.dim);
            pt.putLong("P", p.pos.asLong());
            pt.putInt("N", p.count);
            pt.putLong("T", p.seen);
            if (!p.items.isEmpty()) {
                CompoundTag it = new CompoundTag();
                p.items.forEach(it::putInt);
                pt.put("Items", it);
            }
            list.add(pt);
        }
        t.put("Places", list);
        return t;
    }

    /** Kaputte oder übergroße Daten dürfen die Grenzen beim Merken nicht umgehen. */
    public void load(CompoundTag t) {
        places.clear();
        int version = t.getIntOr("V", 0);
        if (version < 0 || version > 1) return;
        final int maxLoaded = 4096;
        final int maxChestItems = 128;
        Map<String, Integer> counts = new HashMap<>();
        int inspected = 0;
        for (Tag tag : t.getListOrEmpty("Places")) {
            if (++inspected > maxLoaded * 2 || places.size() >= maxLoaded) break;
            if (!(tag instanceof CompoundTag pt)) continue;
            Kind kind;
            try { kind = Kind.valueOf(pt.getStringOr("K", "")); } catch (IllegalArgumentException ex) { continue; }
            String key = canonicalKey(kind, pt.getStringOr("Key", ""));
            if (kind == Kind.SPAWNER && !key.isEmpty() && RegistryIds.parse(key) == null) continue;
            String dimension = pt.getStringOr("D", "minecraft:overworld");
            if (key.length() > 128 || dimension.length() > 128 || dimension.isBlank()) continue;
            String group = kind.name() + ":" + (kind == Kind.ORE ? key : "");
            int count = counts.getOrDefault(group, 0);
            if (count >= MAX_PER_KIND) continue;
            Place p = new Place(kind, key, dimension,
                    BlockPos.of(pt.getLongOr("P", 0)), pt.getLongOr("T", 0));
            p.count = Math.clamp(pt.getIntOr("N", 1), 1, 64);
            if (kind == Kind.CHEST) {
                CompoundTag items = pt.getCompoundOrEmpty("Items");
                int seen = 0;
                for (String id : items.keySet()) {
                    if (seen >= maxChestItems) break;
                    if (id.isEmpty() || id.length() > 128 || id.startsWith(":") || id.endsWith(":")) continue;
                    Identifier parsed = Identifier.tryParse(id.indexOf(':') < 0 ? "minecraft:" + id : id);
                    if (parsed == null || parsed.toString().length() > 128) continue;
                    int amount = items.getIntOr(id, 0);
                    if (amount <= 0) continue;
                    // Alte und vollständige Schreibweise sind dieselbe Beobachtung, kein zusätzlicher Vorrat.
                    p.items.merge(parsed.toString(), Math.min(4096, amount), Math::max);
                    seen++;
                }
            }
            places.add(p);
            counts.put(group, count + 1);
        }
    }

    /** Für Tests: Anzahl Einträge je Art. */
    public Map<Kind, Integer> counts() {
        Map<Kind, Integer> m = new HashMap<>();
        for (Place p : places) m.merge(p.kind, 1, Integer::sum);
        return m;
    }
}
