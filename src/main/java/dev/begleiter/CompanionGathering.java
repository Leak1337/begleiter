package dev.begleiter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.debug.BotDebug.EventKind;
import dev.begleiter.nav.BlockFlags;
import dev.begleiter.task.TaskSpec;
import dev.begleiter.world.SafeLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Baustein Sammeln: sichtbare, erreichbare Blöcke als Abbau-Aufgaben planen (Gedächtnis zuerst, dann ein Scan),
 * unerreichbare Ziele meiden, Treppe und Stollen graben, ganze Bäume fällen und das wachsende Diamanten-Gespür. Nur
 * Laufzeit.
 */
public final class CompanionGathering {
    /** Höchstens so viele (teure) Vanilla-Wegprüfungen je Sammelplanung. */
    public static final int PATH_CHECKS = 12;
    /** So lange meidet er ein Ziel, das sich als unerreichbar erwiesen hat. */
    static final int MISS_TICKS = 1200;
    private static final int MISS_LIMIT = 256;
    /** Für Messungen und Tests: Blocklesungen des Sammel-Scans und Wegprüfungen, seit Serverstart. */
    public static long scanReads, pathChecks;

    private final CompanionEntity e;
    /** Unerreichbare Sammelziele -> Spielzeit, bis zu der sie gemieden werden (begrenzt, älteste fallen heraus). */
    private final java.util.LinkedHashMap<BlockPos, Long> misses = new java.util.LinkedHashMap<>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<BlockPos, Long> eldest) { return size() > MISS_LIMIT; }
    };
    private Level missLevel;
    /** Seit wann (Tick) er Diamanten sucht, ohne einen zu bekommen; -1 = sucht gerade keine. */
    private int diamondSearchSince = -1;
    private int diamondsHad;

    CompanionGathering(CompanionEntity e) { this.e = e; }

    public static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) if (open(level, p.relative(d))) return true;
        return false;
    }

    /** Luft oder Wasser: dort kann er hinsehen/hinschwimmen. */
    private static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = SafeLevel.state(level, p); // ungeladen: keine sichtbare Seite
        return s != null && (s.isAir() || s.getBlock() == Blocks.WATER);
    }

    /** Sichtlinie von den Augen zu einer freien Seite des Blocks (kein Röntgenblick). */
    boolean canSee(ServerLevel sl, BlockPos target) {
        Vec3 from = e.getEyePosition();
        for (Direction d : Direction.values()) {
            BlockPos n = target.relative(d);
            if (!open(sl, n)) continue;
            Vec3 delta = Vec3.atCenterOf(n).subtract(from);
            int steps = Math.max(1, (int) Math.ceil(delta.length() * 2));
            boolean clear = true;
            for (int i = 1; i < steps; i++) {
                BlockPos bp = BlockPos.containing(from.add(delta.scale(i / (double) steps)));
                if (bp.equals(n)) break;
                BlockState bs = SafeLevel.state(sl, bp); // durch Ungeladenes sieht er nicht
                if (bs == null || !bs.getCollisionShape(sl, bp).isEmpty()) { clear = false; break; }
            }
            if (clear) return true;
        }
        return false;
    }

    boolean reachable(BlockPos target) {
        var path = e.getNavigation().createPath(target, 1);
        if (path == null) return false;
        if (path.canReach()) return true;
        var end = path.getEndNode();
        return end != null && target.distSqr(new BlockPos(end.x, end.y, end.z)) <= 20.0;
    }

    /** Standplatz unter einem Block: erster fester Boden darunter (Stämme und Laub zählen nicht), höchstens 12 tief. */
    private static BlockPos standBelow(ServerLevel sl, BlockPos pos) {
        BlockPos.MutableBlockPos m = pos.mutable();
        for (int i = 0; i < 12; i++) {
            m.move(Direction.DOWN);
            BlockState st = sl.getBlockState(m);
            if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.getCollisionShape(sl, m).isEmpty()) continue;
            return m.above().immutable();
        }
        return pos;
    }

    /** Über dem Block mindestens zwei Wasser: er müsste tauchen. */
    public static boolean deepWater(ServerLevel sl, BlockPos p) {
        BlockState one = SafeLevel.state(sl, p.above()), two = SafeLevel.state(sl, p.above(2));
        return one == null || two == null || one.is(Blocks.WATER) && two.is(Blocks.WATER); // unbekannt: als tief annehmen
    }

    boolean missed(ServerLevel sl, BlockPos pos) {
        if (missLevel != sl) { misses.clear(); missLevel = sl; } // andere Welt: alles neu
        Long until = misses.get(pos);
        if (until == null) return false;
        if (sl.getGameTime() < until) return true;
        misses.remove(pos);
        return false;
    }

    void noteMiss(ServerLevel sl, BlockPos pos) {
        if (missLevel != sl) { misses.clear(); missLevel = sl; }
        misses.put(pos.immutable(), sl.getGameTime() + MISS_TICKS);
    }

    /**
     * BREAK-Aufgaben für Blöcke, die er wirklich sehen kann. surface: Holz/Sand/Erde - hier ist Wasser in der Nähe okay,
     * Lava nie. allowDive: auch Blöcke tief unter Wasser (Notfall – langsam, Ertrinkungsgefahr).
     */
    List<Task> gather(Predicate<BlockState> pred, int count, boolean surface, boolean allowDive) {
        List<Task> out = new ArrayList<>();
        if (!(e.level() instanceof ServerLevel sl)) return out;
        BlockPos c = e.blockPosition();
        int rh = dev.begleiter.config.BegleiterConfig.get().gatherRadius, below = -8, above = surface ? 10 : 5;
        int[] checks = {0};
        Set<BlockPos> tried = new HashSet<>();
        // Zuerst bestätigte Gedächtnisfunde im Suchbereich: reicht das, entfällt der Scan über ~45.000 Blöcke
        List<BlockPos> remembered = new ArrayList<>();
        for (CompanionMemory.Place place : e.memory.all()) {
            if (place.kind == CompanionMemory.Kind.WAYPOINT || place.kind == CompanionMemory.Kind.DANGER
                    || place.kind == CompanionMemory.Kind.DEATH || place.kind == CompanionMemory.Kind.VILLAGE) continue;
            BlockPos p = place.pos;
            if (!place.dim.equals(CompanionMemory.dim(sl)) || Math.abs(p.getX() - c.getX()) > rh || Math.abs(p.getZ() - c.getZ()) > rh
                    || p.getY() - c.getY() < below || p.getY() - c.getY() > above) continue;
            BlockState s = SafeLevel.state(sl, p);
            if (s != null && pred.test(s) && exposed(sl, p)) remembered.add(p);
        }
        remembered.sort(Comparator.comparingDouble(p -> p.distSqr(c)));
        gatherFrom(sl, remembered, out, tried, count, surface, allowDive, checks);
        if (out.size() < count && checks[0] < PATH_CHECKS) {
            List<BlockPos> found = new ArrayList<>();
            // An der Oberfläche auch höher suchen (Stammreste hoch im Baum, Hang), so weit wie das Gedächtnis schaut.
            // Abschnitte ohne passenden Block in ihrer Palette werden übersprungen.
            var result = dev.begleiter.world.SectionScanner.scanBox(sl, c.offset(-rh, below, -rh), c.offset(rh, above, rh),
                    pred, (x, y, z, st) -> {
                        BlockPos p = new BlockPos(x, y, z);
                        if (exposed(sl, p)) found.add(p);
                        return true;
                    });
            scanReads += result.blockReads();
            found.sort(CompanionStations::scanOrder); // Gleichstände wie früher in Durchlaufreihenfolge
            found.sort(Comparator.comparingDouble(p -> p.distSqr(c)));
            gatherFrom(sl, found, out, tried, count, surface, allowDive, checks);
        }
        if (!out.isEmpty()) e.explore.reset();
        return out;
    }

    /** Kandidaten (nach Entfernung sortiert) prüfen und Abbau-Aufgaben anhängen; gemeinsames Wegprüfungs-Budget. */
    private void gatherFrom(ServerLevel sl, List<BlockPos> found, List<Task> out, Set<BlockPos> tried, int count,
                            boolean surface, boolean allowDive, int[] checks) {
        int checked = 0;
        for (BlockPos pos : found) {
            if (out.size() >= count || ++checked > 300 || checks[0] >= PATH_CHECKS) break;
            if (!tried.add(pos) || missed(sl, pos)) continue;
            // Stämme nur von gewachsenen Bäumen: Blockhütten und Pfosten aus Stämmen baut er nicht ab
            BlockState candidate = sl.getBlockState(pos);
            if (!e.storage.harvestAllowed(sl, pos, candidate)) continue;
            if (candidate.is(BlockTags.LOGS) && !CompanionTerraform.natural(sl, pos, candidate)) continue;
            if (!canSee(sl, pos)) continue;
            // Wasser daneben flutet sonst den Stollen – am Grund eines Gewässers ist es egal. Tief unter Wasser
            // (Kopf unter Wasser) dauert Abbauen lange und er kann ertrinken: nur im Notfall (allowDive).
            boolean seabed = sl.getBlockState(pos.above()).is(Blocks.WATER);
            if (seabed && deepWater(sl, pos) && !allowDive) continue;
            if (e.hazardAround(sl, pos, surface || seabed)) continue;
            checks[0]++;
            pathChecks++;
            // Stamm hoch oben (Rest eines halb gefällten Baums): reicht, wenn er darunter stehen kann – TREE baut hoch
            if (!reachable(pos)) {
                if (!sl.getBlockState(pos).is(BlockTags.LOGS)) { noteMiss(sl, pos); continue; }
                checks[0]++;
                pathChecks++;
                if (!reachable(standBelow(sl, pos))) { noteMiss(sl, pos); continue; }
            }
            out.add(Task.breakAt(pos, sl.getBlockState(pos).getBlock()));
        }
    }

    /** Befehls-Variante: hängt BREAK-Aufgaben + Abliefern an. */
    public int queueGather(Block block, int count) {
        List<Task> list = e.buildGather(s -> s.is(block), count, false);
        for (Task t : list) e.addTask(t);
        if (!list.isEmpty()) e.addTask(Task.deliver(false));
        e.setPaused(false);
        return list.size();
    }

    /** Treppe schräg nach unten graben in fester Richtung; endet mit MOVE_TO auf die unterste Stufe. */
    public List<Task> staircase(int steps, Direction dir) {
        List<Task> out = new ArrayList<>();
        if (!(e.level() instanceof ServerLevel sl)) return out;
        BlockPos p = e.blockPosition();
        BlockPos last = null;
        for (int i = 1; i <= steps; i++) {
            p = p.relative(dir).below();
            BlockPos below = p.below();
            BlockState bs = sl.getBlockState(below);
            if (!bs.getFluidState().isEmpty() || bs.isAir()) break;
            boolean stop = false;
            List<BlockPos> cells = new ArrayList<>();
            for (BlockPos q : new BlockPos[]{p.above(2), p.above(), p}) {
                BlockState st = sl.getBlockState(q);
                // nur Natürliches: Kisten, Bretter, Glas, Blockhütten-Stämme ... (Haus, Lager) werden nie durchgegraben
                if (!st.getFluidState().isEmpty() || st.getDestroySpeed(sl, q) < 0 || e.hazardAround(sl, q, false)
                        || (!st.isAir() && !st.canBeReplaced() && !naturalAt(sl, q, st))) { stop = true; break; }
                if (!st.isAir()) cells.add(q);
            }
            if (stop) break;
            for (BlockPos q : cells) out.add(Task.breakAt(q, null));
            last = p.immutable();
        }
        if (!out.isEmpty()) out.add(Task.moveTo(last));
        return out;
    }

    /** Stollen (2 hoch) geradeaus graben; endet mit MOVE_TO ans Ende. Stoppt vor Wasser, Lava und Löchern. */
    public List<Task> tunnel(Direction dir, int len) {
        List<Task> out = new ArrayList<>();
        if (!(e.level() instanceof ServerLevel sl)) return out;
        BlockPos p = e.blockPosition();
        BlockPos last = null;
        for (int i = 1; i <= len; i++) {
            p = p.relative(dir);
            BlockState floor = sl.getBlockState(p.below());
            if (!floor.getFluidState().isEmpty() || floor.getCollisionShape(sl, p.below()).isEmpty()) break;
            boolean stop = false;
            List<BlockPos> cells = new ArrayList<>();
            for (BlockPos q : new BlockPos[]{p.above(), p}) {
                BlockState st = sl.getBlockState(q);
                // nur Natürliches: Kisten, Bretter, Glas, Blockhütten-Stämme ... (Haus, Lager) werden nie durchgegraben
                if (!st.getFluidState().isEmpty() || st.getDestroySpeed(sl, q) < 0 || e.hazardAround(sl, q, false)
                        || (!st.isAir() && !st.canBeReplaced() && !naturalAt(sl, q, st))) { stop = true; break; }
                if (!st.isAir()) cells.add(q);
            }
            if (stop) break;
            for (BlockPos q : cells) out.add(Task.breakAt(q, null));
            last = p.immutable();
        }
        if (last != null) out.add(Task.moveTo(last));
        return out;
    }

    /** Natürlich gewachsen (wie die Wegfindung); Stämme nur an einem gewachsenen Baum, nie Blockhütten. */
    public static boolean naturalAt(ServerLevel sl, BlockPos p, BlockState st) {
        return BlockFlags.has(BlockFlags.of(st), BlockFlags.NATURAL)
                && (!st.is(BlockTags.LOGS) || CompanionTerraform.natural(sl, p, st));
    }

    /** Höhe, auf der ein Spieler nach diesem Erz gräbt. */
    public static int oreLevel(String key) {
        return switch (key) {
            case "kohle", "kupfer" -> 48;
            case "eisen", "smaragd" -> 16;
            case "lapis" -> 0;
            case "gold" -> -16;
            case "diamant", "redstone" -> -56;
            case "obsidian" -> -50; // Lavaseen tief unten
            default -> 40;
        };
    }

    // ------------------------------------------------------------------ Bäume komplett fällen

    /** Alle zusammenhängenden Stämme eines Baums (inkl. Äste). Ohne Blätter in der Nähe: nur der eine Block. */
    public List<BlockPos> treeLogs(ServerLevel sl, BlockPos seed) {
        BlockPos start = null;
        if (sl.getBlockState(seed).is(BlockTags.LOGS)) start = seed;
        else {
            double bd = Double.MAX_VALUE;
            // Nach oben so weit wie der Baum selbst (+45): ist der Fuß schon gefällt, hängen die obersten Stämme
            // bei hohen Bäumen weit über ihm – sonst gilt der Baum nach 7 Stämmen als fertig
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -3; dy <= 45; dy++) {
                BlockPos q = seed.offset(dx, dy, dz);
                if (sl.getBlockState(q).is(BlockTags.LOGS) && q.distSqr(seed) < bd) { bd = q.distSqr(seed); start = q; }
            }
        }
        if (start == null) return List.of();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> out = new ArrayList<>();
        queue.add(start);
        seen.add(start);
        int leaves = 0;
        while (!queue.isEmpty() && out.size() < 200) {
            BlockPos p = queue.poll();
            out.add(p);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos n = p.offset(dx, dy, dz);
                if (Math.abs(n.getX() - start.getX()) > 12 || Math.abs(n.getZ() - start.getZ()) > 12
                        || n.getY() < start.getY() - 1 || n.getY() > start.getY() + 45) continue;
                if (!seen.add(n.immutable())) continue;
                BlockState st = sl.getBlockState(n);
                if (st.is(BlockTags.LOGS)) queue.add(n.immutable());
                else if (st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                        && !st.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) leaves++; // gesetztes Laub zählt nicht
            }
        }
        if (leaves < 3) return List.of(start); // kein Baum (z.B. Holzhütte): nur diesen Block
        return out;
    }

    /** Statt einzelner Stämme: ganze Bäume als TREE-Aufgaben. */
    public List<Task> buildTrees(int count) {
        List<Task> out = new ArrayList<>();
        if (!(e.level() instanceof ServerLevel sl)) return out;
        List<Task> singles = e.buildGather(st -> st.is(BlockTags.LOGS), 40, true);
        Set<BlockPos> claimed = new HashSet<>();
        int total = 0;
        for (Task bt : singles) {
            if (total >= count) break;
            if (claimed.contains(bt.pos())) continue;
            List<BlockPos> cluster = treeLogs(sl, bt.pos());
            claimed.addAll(cluster);
            claimed.add(bt.pos());
            if (cluster.size() <= 1) { out.add(bt); total += 1; continue; }
            BlockPos base = cluster.get(0);
            for (BlockPos q : cluster) if (q.getY() < base.getY()) base = q;
            out.add(Task.tree(base, cluster.size()));
            total += cluster.size();
        }
        return out;
    }

    // ------------------------------------------------------------------ Diamanten-Gespür

    /** Diamanten-Suche verfolgen: Ziel in der Warteschlange? Neuer Diamant -> Zähler von vorn. */
    void diamondSearchTick() {
        boolean seeking = false;
        for (Task t : e.queue())
            if (t.spec() instanceof TaskSpec.ResourceGoal goal && Resources.DIAMOND.key().equals(goal.resource())) { seeking = true; break; }
        int have = CompanionCraft.count(e, "diamond");
        // erst ab Diamanten-Tiefe zählen (der Abstieg vom Berg ist keine erfolglose Suche)
        boolean deep = e.getBlockY() <= 16 && e.level().dimension() == Level.OVERWORLD;
        if (!seeking || (!deep && diamondSearchSince < 0)) { diamondSearchSince = -1; diamondsHad = have; return; }
        if (diamondSearchSince < 0 || have > diamondsHad) {
            if (diamondSearchSince >= 0 && e.diamondSenseRadius() > CompanionMemory.xrayRadius()) e.logInfo(EventKind.LOOT, CompanionTexts.tr("log.sense.found"));
            diamondSearchSince = e.tickCount;
        }
        int before = senseRadius(e.tickCount - 20);
        diamondsHad = have;
        int now = e.diamondSenseRadius();
        if (now > before) e.logInfo(EventKind.LOOT, CompanionTexts.tr("log.sense.expanded", now));
    }

    /** Reichweite des Erz-Gespürs für Diamanten: wächst, je länger er erfolglos sucht (2/4/6 min -> 48/64/96). */
    int senseRadius(int at) {
        int base = CompanionMemory.xrayRadius();
        if (diamondSearchSince < 0 || !dev.begleiter.config.BegleiterConfig.get().diamondBoost) return base;
        int min = (at - diamondSearchSince) / (20 * 60);
        return Math.max(base, min >= 6 ? 96 : min >= 4 ? 64 : min >= 2 ? 48 : base);
    }
}
