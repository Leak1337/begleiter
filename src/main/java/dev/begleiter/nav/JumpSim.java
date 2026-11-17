package dev.begleiter.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Sprung-Simulation wie Vanilla (LivingEntity.jumpFromGround/travel, 20 Hz): Absprung mit Sprungkraft und
 * Sprint-Schub, Bodenreibung im Absprung-Tick, danach Luft (×0,91, Schwerkraft, ×0,98) mit dem kleinen Luft-Antrieb
 * eines Mobs. Kollision mit der echten AABB gegen die Kollisionsformen der Blöcke, je Tick in {@link #SUBSTEPS}
 * Teilschritten. Jede Berührung außer der Landung von oben macht die Flugbahn ungültig (keine Kanten streifen).
 * <p>
 * {@link #plan} wählt für einen Sprung src → dest Profil, Absprungpunkt und Anlauf-Tempo so, dass er möglichst mittig
 * landet, prüft Landefläche, Folgeschritt und drei Fehlpfade (zu kurz, links, rechts) und rechnet die Kosten.
 */
public final class JumpSim {
    private JumpSim() {}

    public static final int MAX_TICKS = 40;
    static final int SUBSTEPS = 4;
    /** So weit muss die Landung mindestens von der Kante des Zielblocks weg sein. */
    public static final double MIN_MARGIN = 0.15;
    static final double GROUND_FRICTION = 0.6 * 0.91, AIR_FRICTION = 0.91, SPRINT_BOOST = 0.2;
    /** Fehlpfade: Absprung-Schwung (Anlauf und Sprint-Schub) zu klein; seitlich so verdreht, dass er einen Block daneben ankäme. */
    static final double MISS_SPEED = 0.7, MISS_SIDE = 1.0;
    /**
     * Absprungpunkte: so weit von der Blockmitte Richtung Ziel (Mob bleibt mit der AABB sicher auf dem Block); aus
     * der Mitte bleibt bei einer Stufe hoch mehr Weg zum Steigen, bevor er die Blockseite erreicht.
     */
    private static final double[] TAKEOFF = {0.3, 0.45, 0};
    private static final double EPS = 1e-7;

    /** Warum der letzte {@link #plan} null war (Debug, nur Server-Thread). */
    public static String lastReject = "";
    record Key(long from, long to, boolean allowPlace) {}
    record Cached(Plan plan, String rejection) {}
    static final int CACHE_LIMIT = 2048;

    public enum Profile {
        PRECISION("precision"), SPRINT("sprint"), MAX_REACH("max reach"), STEP_UP("step up"), DROP("drop"),
        DIAGONAL("diagonal");
        public final String label;
        Profile(String label) { this.label = label; }
    }

    /** Körper und Tempo des Begleiters (Schnappschuss in {@link NavContext}). */
    public record Body(double halfWidth, double height, double walkSpeed, double sprintSpeed, double jumpPower, double gravity) {
        double speed(boolean sprint) { return sprint ? sprintSpeed : walkSpeed; }
        /**
         * Mob: MoveControl setzt die Eingabe (zza) = Tempo erst nach applyInput (kein ×0,98); am Boden mal Tempo
         * (Reibung 0,6), in der Luft mal 0,02.
         */
        double groundAccel(boolean sprint) { double s = speed(sprint); return s * s; }
        double airAccel(boolean sprint) { return speed(sprint) * 0.02; }
        /** Gespeicherte Geschwindigkeit nach vollem Anlauf (nach der Bodenreibung). */
        public double runSpeed(boolean sprint) { return groundAccel(sprint) * GROUND_FRICTION / (1 - GROUND_FRICTION); }
    }

    /** Eine simulierte Flugbahn. */
    public static final class Flight {
        public boolean landed, bumped, unclear, liquid, hazard;
        public double x, y, z, peak, along;
        public int ticks;
    }

    /** Gewählter Sprung (für Kosten, Ausführung und Debug-Anzeige). */
    public static final class Plan {
        public Profile profile;
        public boolean sprint;
        public double vRun, takeoffX, takeoffY, takeoffZ, dirX, dirZ, runUp;
        public int flightTicks;
        public double landX, landY, landZ, margin, reach;
        /** Fehlpfade ohne sichere Rückfallposition (0–3) und davon tödlich. */
        public int unsafeMisses, deadlyMisses;
        public double cost;

        public String describe() {
            return String.format(java.util.Locale.ROOT, "%s · flight %d · margin %.2f · misses %d/3 · reach %.1f",
                    profile.label, flightTicks, margin, unsafeMisses, reach);
        }
    }

    // ------------------------------------------------------------------ Kollisionsformen

    private static AABB[][] shapes = new AABB[0][];
    private static final AABB[] NONE = new AABB[0], FULL = {new AABB(0, 0, 0, 1, 1, 1)};

    private static AABB[] shape(BlockState s) {
        if (s == null) return FULL;
        int id = Block.BLOCK_STATE_REGISTRY.getId(s);
        if (id < 0) return s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs().toArray(NONE);
        if (id >= shapes.length) shapes = java.util.Arrays.copyOf(shapes, Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size()));
        AABB[] a = shapes[id];
        if (a == null) shapes[id] = a = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs().toArray(NONE);
        return a;
    }

    /** Oberkante des Bodens unter den Füßen (Stufe, Seelensand …), sonst y. */
    static double floorTop(BlockCache b, int x, int y, int z) {
        double top = 0;
        for (AABB a : shape(b.get(x, y - 1, z))) top = Math.max(top, a.maxY);
        return y - 1 + (top == 0 ? 1 : top);
    }

    // ------------------------------------------------------------------ Simulation

    /** Boxen im Bereich (Weltkoordinaten), wiederverwendet. */
    private static final class Boxes {
        double[] v = new double[6 * 64];
        int n;
        boolean unloaded, hazard, liquid;
        final java.util.List<AABB> hazards = new java.util.ArrayList<>();

        void collect(BlockCache b, double x0, double y0, double z0, double x1, double y1, double z1) {
            n = 0;
            unloaded = hazard = liquid = false;
            hazards.clear();
            for (int x = (int) Math.floor(x0 - EPS); x <= (int) Math.floor(x1 + EPS); x++)
                for (int y = (int) Math.floor(y0 - EPS) - 1; y <= (int) Math.floor(y1 + EPS); y++)
                    for (int z = (int) Math.floor(z0 - EPS); z <= (int) Math.floor(z1 + EPS); z++) {
                        BlockState s = b.get(x, y, z);
                        if (s == null) unloaded = true;
                        else if (BlockFlags.has(BlockFlags.of(s), BlockFlags.AVOID_INTO | BlockFlags.PORTAL))
                            hazards.add(new AABB(x, y, z, x + 1, y + 1, z + 1));
                        for (AABB a : shape(s)) {
                            if (n * 6 + 6 > v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
                            v[n * 6] = x + a.minX; v[n * 6 + 1] = y + a.minY; v[n * 6 + 2] = z + a.minZ;
                            v[n * 6 + 3] = x + a.maxX; v[n * 6 + 4] = y + a.maxY; v[n * 6 + 5] = z + a.maxZ;
                            n++;
                        }
                    }
        }
    }

    private static final ThreadLocal<Boxes> BOXES = ThreadLocal.withInitial(Boxes::new);

    /**
     * Fliegt ab dem Absprung (Füße x/y/z am Boden, gespeicherte Anlauf-Geschwindigkeit vRun in Richtung dir) bis zur
     * Landung oder {@link #MAX_TICKS}. Die Eingabe zeigt die ganze Zeit in Sprungrichtung (wie die Ausführung).
     */
    public static Flight fly(BlockCache b, Body body, double x, double y, double z, double dirX, double dirZ,
            double vRun, boolean sprint, Flight out) {
        return fly(b, body, x, y, z, dirX, dirZ, vRun, sprint, sprint ? SPRINT_BOOST : 0, out);
    }

    /** Wie oben, mit eigenem Sprint-Schub (Fehlpfad „zu kurz“). */
    static Flight fly(BlockCache b, Body body, double x, double y, double z, double dirX, double dirZ,
            double vRun, boolean sprint, double boost, Flight out) {
        Flight f = out == null ? new Flight() : out;
        f.landed = f.bumped = f.unclear = f.liquid = f.hazard = false;
        double x0 = x, z0 = z;
        double vx = dirX * (vRun + boost), vz = dirZ * (vRun + boost), vy = body.jumpPower();
        double hw = body.halfWidth(), h = body.height();
        boolean ground = true;
        f.peak = y;
        Boxes bx = BOXES.get();
        int t;
        for (t = 0; t < MAX_TICKS; t++) {
            // Vanilla nullt Kleinstwerte vor der KI; den Absprung setzt die Ausführung erst danach
            if (t > 0 && Math.abs(vx) < 0.003) vx = 0;
            if (t > 0 && Math.abs(vz) < 0.003) vz = 0;
            double acc = ground ? body.groundAccel(sprint) : body.airAccel(sprint);
            vx += dirX * acc;
            vz += dirZ * acc;
            // ganze Tick-Bewegung einmal einsammeln, dann in Teilschritten verschieben
            bx.collect(b, Math.min(x, x + vx) - hw, Math.min(y, y + vy), Math.min(z, z + vz) - hw,
                    Math.max(x, x + vx) + hw, Math.max(y, y + vy) + h, Math.max(z, z + vz) + hw);
            if (bx.unloaded) f.unclear = true;
            boolean hitY = false, hitX = false, hitZ = false, down = vy < 0;
            for (int s = 0; s < SUBSTEPS; s++) {
                double dx = hitX ? 0 : vx / SUBSTEPS, dy = hitY ? 0 : vy / SUBSTEPS, dz = hitZ ? 0 : vz / SUBSTEPS;
                double cy = clip(bx, 1, x - hw, y, z - hw, x + hw, y + h, z + hw, dy);
                if (cy != dy) hitY = true;
                y += cy;
                if (Math.abs(dx) < Math.abs(dz)) {
                    double cz = clip(bx, 2, x - hw, y, z - hw, x + hw, y + h, z + hw, dz);
                    if (cz != dz) hitZ = true;
                    z += cz;
                    double cx = clip(bx, 0, x - hw, y, z - hw, x + hw, y + h, z + hw, dx);
                    if (cx != dx) hitX = true;
                    x += cx;
                } else {
                    double cx = clip(bx, 0, x - hw, y, z - hw, x + hw, y + h, z + hw, dx);
                    if (cx != dx) hitX = true;
                    x += cx;
                    double cz = clip(bx, 2, x - hw, y, z - hw, x + hw, y + h, z + hw, dz);
                    if (cz != dz) hitZ = true;
                    z += cz;
                }
                // Eine Körperseite kann Feuer/Spinnennetz streifen, während die Mitte in einer sicheren Zelle bleibt
                for (AABB danger : bx.hazards)
                    if (danger.intersects(x - hw + EPS, y + EPS, z - hw + EPS, x + hw - EPS, y + h - EPS, z + hw - EPS))
                        f.hazard = true;
            }
            if (hitX) { vx = 0; f.bumped = true; }
            if (hitZ) { vz = 0; f.bumped = true; }
            if (hitY && !down) f.bumped = true; // Kopf an der Decke
            boolean landedNow = hitY && down;
            if (hitY) vy = 0;
            f.peak = Math.max(f.peak, y);
            double fr = ground ? GROUND_FRICTION : AIR_FRICTION;
            vx *= fr;
            vz *= fr;
            vy = (vy - body.gravity()) * 0.98;
            ground = landedNow;
            int bxI = (int) Math.floor(x), byI = (int) Math.floor(y + 0.01), bzI = (int) Math.floor(z);
            int feet = b.flags(bxI, byI, bzI);
            if (BlockFlags.has(feet | b.flags(bxI, byI + 1, bzI), BlockFlags.AVOID_INTO | BlockFlags.LAVA)) f.hazard = true;
            if (BlockFlags.has(feet, BlockFlags.WATER) && !landedNow) {
                f.liquid = true; // ins Wasser: weich, aber kein geplanter Sprung
                break;
            }
            if (landedNow && t > 0) {
                f.landed = true;
                BlockState under = b.get(bxI, (int) Math.floor(y - 0.01), bzI);
                if (under != null && (under.is(Blocks.SLIME_BLOCK) || under.is(BlockTags.BEDS))) f.unclear = true; // federt
                break;
            }
        }
        f.ticks = t + 1;
        f.x = x;
        f.y = y;
        f.z = z;
        f.along = (x - x0) * dirX + (z - z0) * dirZ;
        return f;
    }

    /** Bewegung auf einer Achse (0 x, 1 y, 2 z) gegen die Boxen kürzen. */
    private static double clip(Boxes bx, int axis, double minX, double minY, double minZ, double maxX, double maxY,
            double maxZ, double d) {
        if (d == 0) return 0;
        double[] v = bx.v;
        for (int i = 0; i < bx.n; i++) {
            int o = i * 6;
            double bMinX = v[o], bMinY = v[o + 1], bMinZ = v[o + 2], bMaxX = v[o + 3], bMaxY = v[o + 4], bMaxZ = v[o + 5];
            switch (axis) {
                case 0 -> {
                    if (bMaxY <= minY + EPS || bMinY >= maxY - EPS || bMaxZ <= minZ + EPS || bMinZ >= maxZ - EPS) continue;
                    if (d > 0 && bMinX >= maxX - EPS) d = Math.min(d, bMinX - maxX);
                    else if (d < 0 && bMaxX <= minX + EPS) d = Math.max(d, bMaxX - minX);
                }
                case 1 -> {
                    if (bMaxX <= minX + EPS || bMinX >= maxX - EPS || bMaxZ <= minZ + EPS || bMinZ >= maxZ - EPS) continue;
                    if (d > 0 && bMinY >= maxY - EPS) d = Math.min(d, bMinY - maxY);
                    else if (d < 0 && bMaxY <= minY + EPS) d = Math.max(d, bMaxY - minY);
                }
                default -> {
                    if (bMaxX <= minX + EPS || bMinX >= maxX - EPS || bMaxY <= minY + EPS || bMinY >= maxY - EPS) continue;
                    if (d > 0 && bMinZ >= maxZ - EPS) d = Math.min(d, bMinZ - maxZ);
                    else if (d < 0 && bMaxZ <= minZ + EPS) d = Math.max(d, bMaxZ - minZ);
                }
            }
        }
        return d;
    }

    // ------------------------------------------------------------------ Planung

    /** Landet die Flugbahn auf dem Zielblock (Füße in dessen Spalte, Höhe passt)? */
    private static boolean onTarget(Flight f, int tx, int ty, int tz) {
        return f.landed && !f.bumped && !f.unclear && !f.hazard && (int) Math.floor(f.x) == tx && (int) Math.floor(f.z) == tz
                && Math.abs(f.y - ty) <= 0.55;
    }

    private static double margin(Flight f, int tx, int tz) {
        return 0.5 - Math.max(Math.abs(f.x - (tx + 0.5)), Math.abs(f.z - (tz + 0.5)));
    }

    /** Freie Anlauf-Blöcke hinter dem Absprung (0–2): gleich hoch begehbar. */
    private static int runUpBlocks(NavContext c, int sx, int sy, int sz, int stepX, int stepZ) {
        int n = 0;
        for (int k = 1; k <= 2; k++) {
            int x = sx - stepX * k, z = sz - stepZ * k;
            int below = c.blocks.flags(x, sy - 1, z);
            if (!BlockFlags.has(below, BlockFlags.STAND) || BlockFlags.has(below, BlockFlags.AVOID_INTO)
                    || !BlockFlags.has(c.blocks.flags(x, sy, z), BlockFlags.PASS)
                    || !BlockFlags.has(c.blocks.flags(x, sy + 1, z), BlockFlags.PASS)) break;
            n++;
        }
        return n;
    }

    /**
     * Bester Sprung von src nach dest oder null (nicht sicher machbar). Probiert Präzision (gehen) und Sprint, zwei
     * Absprungpunkte und per Halbierung das Anlauf-Tempo, mit dem er der Zielmitte am nächsten landet.
     */
    public static Plan plan(NavContext c, int sx, int sy, int sz, int tx, int ty, int tz) {
        return plan(c, sx, sy, sz, tx, ty, tz, null, 0, 0, null);
    }

    /** Cachetreffer kosten keine neue Simulation; bei Zeitende wird kein unvollständiges Ergebnis gespeichert. */
    static Plan plan(NavContext c, int sx, int sy, int sz, int tx, int ty, int tz,
            java.util.function.LongSupplier clock, long started, long budget, MoveResult result) {
        if (c.jumpRevision != c.blocks.revision()) {
            c.jumpPlans.clear();
            c.jumpRevision = c.blocks.revision();
        }
        Key key = new Key(BlockPos.asLong(sx, sy, sz), BlockPos.asLong(tx, ty, tz), c.allowPlace);
        Cached cached = c.jumpPlans.get(key);
        if (cached != null) {
            c.jumpCacheHits++;
            lastReject = cached.rejection();
            return cached.plan();
        }
        if (clock != null && clock.getAsLong() - started >= budget) {
            result.yielded = true;
            return null;
        }
        c.jumpCacheMisses++;
        Plan plan = plan(c, sx, sy, sz, tx, ty, tz, Double.NaN, Double.NaN, 0);
        if (c.jumpPlans.size() >= CACHE_LIMIT) c.jumpPlans.clear();
        c.jumpPlans.put(key, new Cached(plan, lastReject));
        return plan;
    }

    /**
     * Wie {@link #plan(NavContext, int, int, int, int, int, int)}, aber ab der Stelle, an der er gerade steht
     * (Ausführung, neu prüfen): Anlauf-Tempo höchstens vCap (was er tatsächlich an Schwung hat).
     */
    public static Plan plan(NavContext c, int sx, int sy, int sz, int tx, int ty, int tz, double fromX, double fromZ, double vCap) {
        lastReject = "";
        Body body = c.body;
        double ddx = tx - sx, ddz = tz - sz, len = Math.sqrt(ddx * ddx + ddz * ddz);
        if (len < 1e-6) return null;
        double dirX = ddx / len, dirZ = ddz / len;
        int stepX = Integer.signum(tx - sx), stepZ = Integer.signum(tz - sz);
        if (Math.abs(ddx) > Math.abs(ddz) * 2) stepZ = 0;
        else if (Math.abs(ddz) > Math.abs(ddx) * 2) stepX = 0;
        int run = runUpBlocks(c, sx, sy, sz, stepX, stepZ);
        double fy = floorTop(c.blocks, sx, sy, sz);
        Flight f = new Flight();
        Plan best = null;
        boolean fixed = !Double.isNaN(fromX);
        for (int p = 0; p < 2; p++) {
            // Planung: erst Präzision (gehen); beim Absprung erst Sprint (den echten Schwung behalten statt bremsen)
            boolean sprint = fixed ? p == 0 : p == 1;
            if (sprint && !c.canSprint) continue;
            // voller Anlauf nur mit Platz dahinter (Sprint 2 Blöcke, Gehen 1); ohne Platz: Sprint-Sprung aus dem Stand
            double vMax = fixed ? Math.min(body.runSpeed(sprint), vCap)
                    : body.runSpeed(sprint) * (run >= (sprint ? 2 : 1) ? 1 : run >= 1 ? (sprint ? 0.6 : 0.8) : sprint ? 0 : 0.6);
            for (double off : fixed ? new double[]{0} : TAKEOFF) {
                double ox = fixed ? fromX : sx + 0.5 + dirX * off, oz = fixed ? fromZ : sz + 0.5 + dirZ * off;
                double want = (tx + 0.5 - ox) * dirX + (tz + 0.5 - oz) * dirZ;
                // Halbierung: Landeweite wächst mit dem Anlauf-Tempo
                double lo = 0, hi = vMax, v;
                boolean needsFinalFlight = false;
                fly(c.blocks, body, ox, fy, oz, dirX, dirZ, hi, sprint, f);
                if (f.along < want) v = hi;
                else {
                    fly(c.blocks, body, ox, fy, oz, dirX, dirZ, lo, sprint, f);
                    if (f.along > want) v = lo;
                    else {
                        for (int i = 0; i < 7; i++) {
                            double mid = (lo + hi) / 2;
                            fly(c.blocks, body, ox, fy, oz, dirX, dirZ, mid, sprint, f);
                            if (f.along < want) lo = mid; else hi = mid;
                        }
                        v = (lo + hi) / 2;
                        needsFinalFlight = true;
                    }
                }
                // An einer Tempogrenze enthält f schon genau diesen Flug. Außerhalb der Suche immer vollständig
                // rechnen; eine andere Flugbahn wird nie übersprungen.
                if (needsFinalFlight || !c.blocks.searching())
                    fly(c.blocks, body, ox, fy, oz, dirX, dirZ, v, sprint, f);
                else c.blocks.reusedFlights++;
                if (!onTarget(f, tx, ty, tz)) {
                    lastReject = String.format(java.util.Locale.ROOT, "%s: lands %s at %.2f %.2f %.2f%s%s%s",
                            sprint ? "sprint" : "walk", f.landed ? "" : "not", f.x, f.y, f.z, f.bumped ? " (bumps)" : "",
                            f.unclear ? " (unclear)" : "", f.hazard ? " (hazard)" : "");
                    continue;
                }
                double m = margin(f, tx, tz);
                int fall = (int) Math.ceil(f.peak - f.y - 1e-3);
                if (c.health - c.fallDamage(fall) < NavContext.MIN_HEALTH_AFTER_FALL) continue;
                if (m < MIN_MARGIN || !landingClear(c, f, body)) {
                    lastReject = String.format(java.util.Locale.ROOT, "margin %.2f / landing area", m);
                    continue;
                }
                if (best != null && m <= best.margin + 0.02) continue;
                Plan candidate = new Plan();
                candidate.sprint = sprint;
                candidate.vRun = v;
                candidate.takeoffX = ox;
                candidate.takeoffY = fy;
                candidate.takeoffZ = oz;
                candidate.dirX = dirX;
                candidate.dirZ = dirZ;
                candidate.runUp = v <= 0.01 ? 0 : Math.min(sprint ? 1.6 : 1.0, run + 0.5 + off - 0.15);
                candidate.flightTicks = f.ticks;
                candidate.landX = f.x;
                candidate.landY = f.y;
                candidate.landZ = f.z;
                candidate.margin = m;
                candidate.reach = Math.sqrt((f.x - ox) * (f.x - ox) + (f.z - oz) * (f.z - oz));
                misses(c, candidate, sy);
                if (candidate.deadlyMisses > 0 || candidate.unsafeMisses >= 3) {
                    lastReject = "deadly or unsecured miss path";
                    continue;
                }
                candidate.cost = cost(c, candidate, sy - ty);
                best = candidate;
                if (m >= 0.3) break;
            }
            if (best != null && best.margin >= 0.3) break;
        }
        if (best == null) return null;
        if (!hasFollowUp(c, tx, ty, tz)) {
            lastReject = "no safe follow-up step";
            return null;
        }
        int gap = (int) Math.round(len) - 1;
        boolean diagonal = tx != sx && tz != sz;
        best.profile = ty < sy ? Profile.DROP : ty > sy ? Profile.STEP_UP : diagonal ? Profile.DIAGONAL
                : gap >= 4 ? Profile.MAX_REACH : best.sprint ? Profile.SPRINT : Profile.PRECISION;
        best.cost = cost(c, best, sy - ty);
        return best;
    }

    /**
     * Kosten = Flugzeit + Anlauf + Sprungstrafe + Korrekturaufwand + Fall-/Schadensrisiko + Unsicherheit + fehlende
     * Landemarge.
     */
    private static double cost(NavContext c, Plan p, int drop) {
        double runUp = p.vRun <= 0.01 ? 0 : p.sprint ? 6 : 3;
        double correction = (0.5 - p.margin) * 12;
        double fall = drop > 0 ? c.fallDamage(drop + 1) * Costs.DAMAGE : 0;
        double uncertainty = (p.unsafeMisses - p.deadlyMisses) * 15 + p.deadlyMisses * 60;
        return p.flightTicks + runUp + Costs.JUMP_PENALTY + correction + fall + uncertainty + (p.margin < 0.3 ? 8 : 0);
    }

    /** Landefläche: 3×3 Proben unter der AABB, keine Gefahr; die Mitte steht auf festem Grund. */
    private static boolean landingClear(NavContext c, Flight f, Body body) {
        int y = (int) Math.floor(f.y + 0.01);
        for (int i = -1; i <= 1; i++)
            for (int j = -1; j <= 1; j++) {
                int x = (int) Math.floor(f.x + i * body.halfWidth() * 0.95), z = (int) Math.floor(f.z + j * body.halfWidth() * 0.95);
                int below = c.blocks.flags(x, (int) Math.floor(f.y - 0.01), z);
                if (BlockFlags.has(below | c.blocks.flags(x, y, z) | c.blocks.flags(x, y + 1, z), BlockFlags.AVOID_INTO | BlockFlags.LAVA))
                    return false;
            }
        int below = c.blocks.flags((int) Math.floor(f.x), (int) Math.floor(f.y - 0.01), (int) Math.floor(f.z));
        return BlockFlags.has(below, BlockFlags.STAND) || BlockFlags.has(below, BlockFlags.SLAB_BOTTOM);
    }

    /** Nach der Landung geht es weiter: ein Nachbar ist begehbar (gleich hoch, eine Stufe oder sicher tiefer). */
    private static boolean hasFollowUp(NavContext c, int x, int y, int z) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0], nz = z + d[1];
            boolean feet = BlockFlags.has(c.blocks.flags(nx, y, nz), BlockFlags.PASS);
            boolean head = BlockFlags.has(c.blocks.flags(nx, y + 1, nz), BlockFlags.PASS);
            if (!feet && head && BlockFlags.has(c.blocks.flags(nx, y + 2, nz), BlockFlags.PASS)
                    && BlockFlags.has(c.blocks.flags(nx, y, nz), BlockFlags.STAND)) return true; // Stufe hoch
            if (!feet || !head) continue;
            for (int k = 1; k <= Math.max(1, c.maxFall) + 1; k++) {
                int f = c.blocks.flags(nx, y - k, nz);
                if (BlockFlags.has(f, BlockFlags.AVOID_INTO | BlockFlags.UNLOADED)) break;
                if (BlockFlags.has(f, BlockFlags.STAND | BlockFlags.WATER)) return true;
                if (!BlockFlags.has(f, BlockFlags.PASS)) break;
            }
        }
        return false;
    }

    /** Drei Fehlpfade (zu kurz, links, rechts): wo landet er, ist das sicher (oder fängt ihn der Eimer)? */
    private static void misses(NavContext c, Plan p, int sy) {
        Flight f = new Flight();
        p.unsafeMisses = p.deadlyMisses = 0;
        for (int k = 0; k < 3; k++) {
            double dx = p.dirX, dz = p.dirZ, v = p.vRun, boost = p.sprint ? SPRINT_BOOST : 0;
            if (k == 0) {
                v *= MISS_SPEED;
                boost *= MISS_SPEED;
            } else {
                double a = Math.atan2(MISS_SIDE, Math.max(1, p.reach)) * (k == 1 ? 1 : -1), cos = Math.cos(a), sin = Math.sin(a);
                dx = p.dirX * cos - p.dirZ * sin;
                dz = p.dirX * sin + p.dirZ * cos;
            }
            fly(c.blocks, c.body, p.takeoffX, p.takeoffY, p.takeoffZ, dx, dz, v, p.sprint, boost, f);
            int outcome = outcome(c, f);
            if (outcome > 0) {
                p.unsafeMisses++;
                if (outcome > 1) p.deadlyMisses++;
            }
        }
    }

    /** 0 sicher, 1 zu tief (verletzt, ohne Eimer), 2 tödlich (Lava, Leere, Feuer …). */
    private static int outcome(NavContext c, Flight f) {
        if (f.hazard || f.unclear) return 2;
        if (f.liquid) return 0;
        double landY = f.y;
        if (!f.landed) {
            int x = (int) Math.floor(f.x), z = (int) Math.floor(f.z);
            int y = (int) Math.floor(f.y);
            for (int k = 0; ; k++) {
                if (k > Costs.MAX_FALL_TABLE) return 2;
                int fl = c.blocks.flags(x, y - k, z);
                if (BlockFlags.has(fl, BlockFlags.AVOID_INTO | BlockFlags.UNLOADED | BlockFlags.LAVA)) return 2;
                if (BlockFlags.has(fl, BlockFlags.WATER)) return 0;
                if (!BlockFlags.has(fl, BlockFlags.PASS)) { landY = y - k + 1; break; }
            }
        } else {
            int under = c.blocks.flags((int) Math.floor(f.x), (int) Math.floor(f.y - 0.01), (int) Math.floor(f.z));
            if (BlockFlags.has(under, BlockFlags.AVOID_INTO)) return 2;
        }
        int fall = (int) Math.ceil(f.peak - landY - 1e-3);
        if (c.health - c.fallDamage(fall) >= NavContext.MIN_HEALTH_AFTER_FALL) return 0;
        // Ein Eimer im Inventar beweist nicht, dass das Wassersetzen hier klappt
        BlockPos landing = new BlockPos((int) Math.floor(f.x), (int) Math.round(landY), (int) Math.floor(f.z));
        if (ClutchPlan.water(c, landing, fall) != null) return 0;
        return c.health - c.fallDamage(fall) > 0 ? 1 : 2;
    }
}
