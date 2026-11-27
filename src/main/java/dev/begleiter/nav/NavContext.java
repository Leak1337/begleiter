package dev.begleiter.nav;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import dev.begleiter.CompanionEntity;
import dev.begleiter.thirdparty.baritone.BetterWorldBorder;

/**
 * Schnappschuss des Begleiters für eine Suche: Werkzeuge (ganzes Inventar), Effekte, Müllblöcke, Hunger, Leben und
 * Fallschutz, geschützte Bereiche, Kosten-Faktoren je Position. Abbauzeiten rechnet er wie Vanilla (Spieler-Formel).
 */
public final class NavContext {
    public final ServerLevel level;
    public final BlockCache blocks;
    /** Stand zur Suche; die Ausführung frischt ihn vor jeder Bewegungsprüfung auf. */
    BetterWorldBorder worldBorder;

    public final boolean canSprint, inStronghold, nether, waterBucket;
    /** Was die Aufgabe erlaubt (Standard: alles). */
    public boolean allowBreak = true, allowPlace = true, allowParkour = true;
    /** Netherportal betreten (nur wenn das Ziel ein Portalblock ist). */
    public boolean allowPortal;
    BlockPos endPortalTarget;
    public final Item throwawayItem;
    public final int throwaway;
    public final float health;
    /** Höchste Fallhöhe, nach der noch mindestens {@link #MIN_HEALTH_AFTER_FALL} Leben bleiben. */
    public final int maxFall;
    /** Körper und Tempo für die Sprung-Simulation ({@link JumpSim}). */
    public final JumpSim.Body body;
    /** Begrenzter Merker je Suche; wird mit dem Blockstand zusammen geleert. */
    final java.util.Map<JumpSim.Key, JumpSim.Cached> jumpPlans = new java.util.HashMap<>();
    long jumpRevision = -1;
    public int jumpCacheHits, jumpCacheMisses;

    static final float MIN_HEALTH_AFTER_FALL = 8;

    private record Tool(ItemStack stack, int efficiency, boolean worn) {}
    private final List<Tool> tools = new ArrayList<>();
    private final float effectFactor;
    private final boolean aquaAffinity;
    private final float safeFall, fallMultiplier;
    private final int fallProtection; // Schutzpunkte gegen Fallschaden (Federfall 3/Stufe, Schutz 1/Stufe), max. 20
    private final Int2IntOpenHashMap ticksCache = new Int2IntOpenHashMap();
    private it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap miningCosts;
    private long miningRevision = -1;
    private int miningPolicy = -1;
    long miningCacheHits, miningCacheMisses;
    private final Long2DoubleOpenHashMap favoring = new Long2DoubleOpenHashMap();
    private final List<BlockPos> protectedCenters = new ArrayList<>();
    static final int PROTECTED_RADIUS = 5;
    private final dev.begleiter.CompanionMemory memory;
    /** Mitte des eigenen Hauses: Gefahrenpunkte dort (Creeper im Haus, Tod an der Tür) sperren nicht das Zuhause. */
    private final BlockPos houseCenter;
    private final boolean avoidKnownDanger;
    private final boolean indexedDangers;
    private final List<BlockPos> dangerCenters = new ArrayList<>();
    private it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<BlockPos>> dangerIndex;
    private boolean useDangerIndex;
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap dangerCache = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    /** Vorsichtiger Abstand um gemerkte Gefahren; die echte Kollision prüfen weiterhin Move/JumpSim. */
    static final int DANGER_RADIUS = 2;
    private static final int DANGER_INDEX_THRESHOLD = 64, DANGER_CELL_SHIFT = 3;

    public NavContext(CompanionEntity mob) {
        this(mob, mob.work.active() || mob.scopedStockWork() || dev.begleiter.ai.CompanionDecisionEngine.enabled()
                && mob.goal == dev.begleiter.CompanionNeeds.Need.SUPPLY);
    }

    public NavContext(CompanionEntity mob, boolean avoidKnownDanger) {
        this(mob, new BlockCache((ServerLevel) mob.level()), mob.getHealth(), mob.scaffoldCount(), avoidKnownDanger);
        if (mob.scopedStockWork() || mob.work.active()) {
            allowBreak = false;
            allowPlace = false;
            allowParkour = false;
        }
    }

    NavContext(CompanionEntity mob, BlockCache blocks, float projectedHealth, int availableBlocks, boolean avoidKnownDanger) {
        this(mob, blocks, projectedHealth, availableBlocks, avoidKnownDanger, true);
    }

    /** Referenzmodus mit linearer Gefahrensuche, nur für Vergleichstests und Messungen. */
    NavContext(CompanionEntity mob, BlockCache blocks, float projectedHealth, int availableBlocks,
               boolean avoidKnownDanger, boolean indexedDangers) {
        this.level = (ServerLevel) mob.level();
        this.blocks = blocks;
        refreshWorldBorder();
        memory = mob.memory;
        houseCenter = mob.houseInside();
        this.avoidKnownDanger = avoidKnownDanger;
        this.indexedDangers = indexedDangers;
        refreshDangers();
        ticksCache.defaultReturnValue(Integer.MIN_VALUE);
        favoring.defaultReturnValue(1.0);

        tools.add(new Tool(ItemStack.EMPTY, 0, false)); // Hand
        addTool(mob.getMainHandItem());
        for (int i = 0; i < mob.getInventory().getContainerSize(); i++) addTool(mob.getInventory().getItem(i));

        float fx = 1;
        MobEffectInstance haste = mob.getEffect(MobEffects.HASTE), conduit = mob.getEffect(MobEffects.CONDUIT_POWER);
        int amp = Math.max(haste == null ? -1 : haste.getAmplifier(), conduit == null ? -1 : conduit.getAmplifier());
        if (amp >= 0) fx *= 1 + (amp + 1) * 0.2f;
        MobEffectInstance fatigue = mob.getEffect(MobEffects.MINING_FATIGUE);
        if (fatigue != null) fx *= switch (fatigue.getAmplifier()) {
            case 0 -> 0.3f;
            case 1 -> 0.09f;
            case 2 -> 0.0027f;
            default -> 8.1e-4f;
        };
        effectFactor = fx;
        aquaAffinity = enchantLevel(Enchantments.AQUA_AFFINITY, mob.getItemBySlot(EquipmentSlot.HEAD)) > 0;

        safeFall = (float) mob.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
        fallMultiplier = (float) mob.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER);
        int prot = 3 * enchantLevel(Enchantments.FEATHER_FALLING, mob.getItemBySlot(EquipmentSlot.FEET));
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
            prot += enchantLevel(Enchantments.PROTECTION, mob.getItemBySlot(s));
        fallProtection = Math.min(20, prot);
        health = projectedHealth;
        int mf = 0;
        // Stürze ohne Schaden (bis 3 Blöcke) immer erlaubt: sonst durfte er mit wenig Leben nicht einmal eine Stufe
        // hinab und kam weder aus dem Haus noch zur Ernte (beobachtet: tagelang mit 5 Leben "unerreichbar")
        while (mf < Costs.MAX_FALL_TABLE && (fallDamage(mf + 1) <= 0 || health - fallDamage(mf + 1) >= MIN_HEALTH_AFTER_FALL)) mf++;
        maxFall = mf;

        canSprint = mob.food.level() > 6;
        // Sprung-Körper: Grundtempo ohne Sprint-Bonus (+30 %), Tempo-Faktor wie in der Ausführung
        double base = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1);
        body = new JumpSim.Body(mob.getBbWidth() / 2, mob.getBbHeight(), base * Movement.WALK_SPEED,
                base * 1.3 * Movement.WALK_SPEED, mob.navJumpPower(), mob.getGravity());
        nether = level.dimension() == Level.NETHER;
        // Auf dem Lohenausflug Festungsmauern aufbrechen wie ein Spieler (beobachtet: Weg zum Spawner "unerreichbar")
        inStronghold = mob.inStronghold() || nether && mob.netherTrip;
        waterBucket = mob.findItem(Items.WATER_BUCKET) >= 0;
        throwawayItem = mob.scaffoldItem();
        throwaway = throwawayItem == null ? 0 : Math.max(0, availableBlocks);
        if (mob.getHome() != null) protectedCenters.add(mob.getHome());
        // Fehlstellen (dort zuletzt gescheitert): teurer statt verboten
        for (var e : mob.badSteps.entrySet()) if (e.getValue() > mob.tickCount) favoring.put(e.getKey().asLong(), BAD_STEP_FACTOR);
        addThreats(mob);
        mob.routes.applyTo(favoring, RouteMemory.worldKey(level));
        // Darf der Besitzer nicht bauen (Abenteuer), plant auch die Suche weder Abbau noch Brücken/Säulen
        if (!dev.begleiter.world.BuildPermission.ownerMayBuild(mob)) { allowBreak = false; allowPlace = false; }
    }

    static final int THREAT_SCAN = 24;

    /**
     * Bedrohungskarte: rund um feindliche Mobs wird der Weg teurer (Creeper r5 ×3, Skelett r6 ×2, Zombie/Spinne r4
     * ×1,5, andere Monster r3 ×1,5). Bei jeder neuen Suche frisch, also mit den aktuellen Positionen.
     */
    private void addThreats(CompanionEntity mob) {
        for (var m : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, mob.getBoundingBox().inflate(THREAT_SCAN))) {
            if (!m.isAlive()) continue;
            int r;
            double factor;
            if (m instanceof net.minecraft.world.entity.monster.Creeper) { r = 5; factor = 3; }
            else if (m instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) { r = 6; factor = 2; }
            else if (m instanceof net.minecraft.world.entity.monster.zombie.Zombie || m instanceof net.minecraft.world.entity.monster.spider.Spider) { r = 4; factor = 1.5; }
            else { r = 3; factor = 1.5; }
            BlockPos c = m.blockPosition();
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        long k = BlockPos.asLong(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                        favoring.put(k, favoring.get(k) * factor);
                    }
                }
        }
    }

    static final double BAD_STEP_FACTOR = 3;

    private void addTool(ItemStack s) {
        if (s.isEmpty() || !s.isDamageableItem()) return;
        for (Tool t : tools) if (ItemStack.isSameItemSameComponents(t.stack, s)) return;
        boolean worn = s.getMaxDamage() - s.getDamageValue() < s.getMaxDamage() * 0.05;
        tools.add(new Tool(s, enchantLevel(Enchantments.EFFICIENCY, s), worn));
    }

    private int enchantLevel(ResourceKey<Enchantment> key, ItemStack s) {
        if (s.isEmpty()) return 0;
        return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key)
                .map(h -> EnchantmentHelper.getItemEnchantmentLevel(h, s)).orElse(0);
    }

    // ------------------------------------------------------------------ Abbauen

    /** Vanilla: Fortschritt pro Tick → Ticks bis abgebaut (0 = sofort). */
    public static int ticksFor(float progressPerTick) {
        if (progressPerTick <= 0) return Integer.MAX_VALUE;
        return progressPerTick >= 1 ? 0 : (int) Math.ceil(1 / progressPerTick);
    }

    /** Abbau-Fortschritt pro Tick mit diesem Werkzeug (Spieler-Formel: Effizienz, Eile, Abbaulähmung, Wasser, Luft). */
    private float progress(Tool t, BlockState s, boolean inWater, boolean onGround) {
        float hardness = s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness < 0) return 0;
        float speed = t.stack.isEmpty() ? 1 : t.stack.getDestroySpeed(s);
        if (speed > 1 && t.efficiency > 0) speed += t.efficiency * t.efficiency + 1;
        speed *= effectFactor;
        if (inWater && !aquaAffinity) speed *= 0.2f;
        if (!onGround) speed /= 5;
        boolean correct = !s.requiresCorrectToolForDrops() || (!t.stack.isEmpty() && t.stack.isCorrectToolForDrops(s));
        return speed / hardness / (correct ? 30 : 100);
    }

    /** Darf er das ernten (bei Blöcken, die das passende Werkzeug brauchen)? */
    public boolean canHarvest(BlockState s) {
        if (s == null) return false; // ungeladener Chunk: lieber nicht abbauen (sonst NullPointerException in der Wegfindung)
        if (!s.requiresCorrectToolForDrops()) return true;
        for (Tool t : tools) if (!t.stack.isEmpty() && t.stack.isCorrectToolForDrops(s)) return true;
        return false;
    }

    /** Ticks mit dem besten Werkzeug, am Boden und trocken (Vanilla-Wert, abgenutzte Werkzeuge ×1,5 bei der Wahl). */
    public int breakTicks(BlockState s) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(s);
        int cached = ticksCache.get(id);
        if (cached != Integer.MIN_VALUE) return cached;
        int t = breakTicks(s, false, true);
        ticksCache.put(id, t);
        return t;
    }

    public int breakTicks(BlockState s, boolean inWater, boolean onGround) {
        double best = Double.MAX_VALUE;
        int bestTicks = Integer.MAX_VALUE;
        for (Tool t : tools) {
            int ticks = ticksFor(progress(t, s, inWater, onGround));
            double rated = t.worn ? ticks * 1.5 : ticks;
            if (rated < best) { best = rated; bestTicks = ticks; }
        }
        return bestTicks;
    }

    /** Regeln ohne Umgebung: nicht verboten, natürlich (oder Festung, wenn er drin ist), passendes Werkzeug da. */
    public boolean mayBreak(BlockState s, int flags) {
        if (BlockFlags.has(flags, BlockFlags.AVOID_BREAK | BlockFlags.LIQUID)) return false;
        if (!BlockFlags.has(flags, BlockFlags.NATURAL) && !(inStronghold && BlockFlags.has(flags, BlockFlags.STRONGHOLD))) return false;
        return canHarvest(s);
    }

    /**
     * Wie {@link #mayBreak}, dazu Stämme nur, wenn sie zu einem gewachsenen Baum gehören (Laub daran): Wände einer
     * Blockhütte oder Pfosten aus Stämmen gräbt er nicht weg. Liest nur geladene Chunks.
     */
    public boolean mayBreakAt(int x, int y, int z, BlockState s, int flags) {
        if (!mayBreak(s, flags)) return false;
        return !s.is(net.minecraft.tags.BlockTags.LOGS) || dev.begleiter.CompanionTerraform.natural(level, new BlockPos(x, y, z), s);
    }

    /**
     * Kosten, um die Zelle frei zu bekommen: 0 = schon frei, {@link Costs#INF} = geht nicht (verboten, Flüssigkeit
     * daneben/darüber, geschützt, nicht geladen). Rieselblöcke darüber fallen nach und zählen mit.
     */
    public double breakCost(int x, int y, int z) {
        return breakCost(x, y, z, 0);
    }

    private double memoizedSolidCost(int x, int y, int z, BlockState state) {
        int policy = (allowBreak ? 1 : 0) | (allowPortal ? 2 : 0);
        if (miningCosts == null) {
            miningCosts = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap(1024);
            miningCosts.defaultReturnValue(Double.NaN);
        }
        if (miningRevision != blocks.revision() || miningPolicy != policy) {
            miningCosts.clear();
            miningRevision = blocks.revision();
            miningPolicy = policy;
        }
        long key = BlockPos.asLong(x, y, z);
        double cached = miningCosts.get(key);
        if (!Double.isNaN(cached)) { miningCacheHits++; return cached; }
        miningCacheMisses++;
        double result = solidBreakCost(x, y, z, state, 0);
        // Speicher auch bei sehr langen Wegen begrenzen; die Tiefe bei Rieselblöcken bleibt davon unberührt.
        if (miningCosts.size() >= 8192) miningCosts.clear();
        miningCosts.put(key, result);
        return result;
    }

    void finishSearchCaches() { miningCosts = null; }

    private double breakCost(int x, int y, int z, int depth) {
        if (!worldBorder.entirelyContains(x, z)) return Costs.INF;
        int f = blocks.flags(x, y, z);
        if (BlockFlags.has(f, BlockFlags.UNLOADED)) return Costs.INF;
        if (BlockFlags.has(f, BlockFlags.PASS)) return 0;
        if (BlockFlags.has(f, BlockFlags.PORTAL)) return allowPortal ? 0 : Costs.INF;
        if (BlockFlags.has(f, BlockFlags.END_PORTAL)) return canEnterEndPortal(x, y, z) ? 0 : Costs.INF;
        if (!allowBreak) return Costs.INF;
        BlockState s = blocks.get(x, y, z);
        if (!mayBreakAt(x, y, z, s, f) || isProtected(x, y, z)) return Costs.INF;
        if (depth == 0 && blocks.searching()) return memoizedSolidCost(x, y, z, s);
        return solidBreakCost(x, y, z, s, depth);
    }

    private double solidBreakCost(int x, int y, int z, BlockState s, int depth) {
        if (liquidAt(x, y + 1, z) || liquidAt(x + 1, y, z) || liquidAt(x - 1, y, z) || liquidAt(x, y, z + 1) || liquidAt(x, y, z - 1))
            return Costs.INF;
        int ticks = breakTicks(s);
        if (ticks == Integer.MAX_VALUE) return Costs.INF;
        double cost = ticks + Costs.BREAK_PENALTY + Costs.BREAK_COOLDOWN;
        if (BlockFlags.has(blocks.flags(x, y + 1, z), BlockFlags.FALLING)) {
            if (depth >= 16) return Costs.INF;
            double above = breakCost(x, y + 1, z, depth + 1);
            if (above >= Costs.INF) return Costs.INF;
            cost += above;
        }
        return cost;
    }

    private boolean liquidAt(int x, int y, int z) {
        return BlockFlags.has(blocks.flags(x, y, z), BlockFlags.LIQUID);
    }

    /** Nahe am Lager (über dem Boden) wird nichts weggegraben. */
    public boolean isProtected(int x, int y, int z) {
        for (BlockPos c : protectedCenters) {
            long dx = (long) x - c.getX(), dy = (long) y - c.getY(), dz = (long) z - c.getZ();
            if (dy >= 0 && dx * dx + dy * dy + dz * dz <= PROTECTED_RADIUS * PROTECTED_RADIUS) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ Fallen

    /** Fallschaden nach h Blöcken (Vanilla: sichere Höhe, Multiplikator, Federfall/Schutz – Rüstung hilft nicht). */
    public float fallDamage(int h) {
        float raw = (float) Math.ceil((h - safeFall) * fallMultiplier);
        if (raw <= 0) return 0;
        return raw * (1 - fallProtection / 25f);
    }

    /** Zeit plus Schadens-Strafe; unmöglich, wenn danach zu wenig Leben bleibt. */
    public double fallCost(int h) {
        if (h > maxFall || h > Costs.MAX_FALL_TABLE) return Costs.INF;
        return Costs.FALL[h] + fallDamage(h) * Costs.DAMAGE;
    }

    // ------------------------------------------------------------------ Kosten-Faktoren

    /** Faktor je Position: Standard 1, Fehlstellen {@link #BAD_STEP_FACTOR}. */
    public double favor(int x, int y, int z) {
        return favoring.isEmpty() ? 1.0 : favoring.get(BlockPos.asLong(x, y, z));
    }

    public void setFavor(BlockPos p, double factor) { favoring.put(p.asLong(), factor); }

    void refreshWorldBorder() {
        if (miningCosts != null) miningCosts.clear();
        var border = level.getWorldBorder();
        worldBorder = new BetterWorldBorder(border.getMinX(), border.getMaxX(), border.getMinZ(), border.getMaxZ());
    }

    /** Vor der Ausführung auffrischen: ein neuer Gefahren-Eintrag muss einen schon gesuchten Weg ungültig machen. */
    public void refreshDangers() {
        dangerCenters.clear();
        dangerCache.clear();
        dangerIndex = null;
        useDangerIndex = false;
        if (!avoidKnownDanger) return;
        String dimension = level.dimension().identifier().toString();
        for (var p : memory.all())
            if ((p.kind == dev.begleiter.CompanionMemory.Kind.DANGER || p.kind == dev.begleiter.CompanionMemory.Kind.DEATH)
                    && !mobDeath(p.key)
                    && p.dim.equals(dimension) && !nearHouse(p.pos)) dangerCenters.add(p.pos);
        useDangerIndex = indexedDangers && dangerCenters.size() > DANGER_INDEX_THRESHOLD;
    }

    /**
     * Beobachtet: eine Creeper-Explosion im Haus machte das Innere zur "Gefahr", danach war jeder Weg aus dem und ins Haus
     * "unerreichbar" (auch 2 Blöcke weit). Am eigenen Haus zählen gemerkte Gefahren deshalb nicht.
     */
    /**
     * Von Monstern getötet (Zombie, Pfeil, Creeper ...): der Ort selbst ist nicht gefährlich. Beobachtet: viele solche Tode
     * zwischen Haus und Feld sperrten den Weg zur reifen Ernte, während er hungerte. Gelände-Tode (Lava, Sturz, Feuer,
     * Ertrinken, Ersticken) bleiben gesperrt.
     */
    private static boolean mobDeath(String cause) {
        if (cause == null) return false;
        return cause.startsWith("mob") || cause.startsWith("player") || cause.startsWith("arrow") || cause.startsWith("explosion")
                || cause.startsWith("thrown") || cause.startsWith("trident") || cause.startsWith("sting")
                || cause.startsWith("fireball") || cause.startsWith("witherSkull") || cause.startsWith("sonic");
    }

    private boolean nearHouse(BlockPos p) {
        return houseCenter != null && Math.abs(p.getX() - houseCenter.getX()) <= 6 && Math.abs(p.getZ() - houseCenter.getZ()) <= 6
                && Math.abs(p.getY() - houseCenter.getY()) <= 4;
    }

    /** Veränderliche Regeln für eine über mehrere Scheiben verteilte Abschlussprüfung. */
    boolean canEnterEndPortal(int x, int y, int z) {
        return endPortalTarget != null && endPortalTarget.getX() == x && endPortalTarget.getY() == y && endPortalTarget.getZ() == z;
    }

    record VerificationPolicy(boolean allowBreak, boolean allowPlace, boolean allowParkour, boolean allowPortal, BlockPos endPortalTarget,
                              List<BlockPos> dangers) {}

    VerificationPolicy verificationPolicy() {
        return new VerificationPolicy(allowBreak, allowPlace, allowParkour, allowPortal, endPortalTarget, List.copyOf(dangerCenters));
    }

    private int dangerDistance(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        int cached = dangerCache.getOrDefault(key, -1);
        if (cached >= 0) return cached;
        int distance = DANGER_RADIUS + 1;
        if (useDangerIndex) {
            if (dangerIndex == null) {
                dangerIndex = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
                for (BlockPos p : dangerCenters) {
                    long cell = BlockPos.asLong(p.getX() >> DANGER_CELL_SHIFT, p.getY() >> DANGER_CELL_SHIFT, p.getZ() >> DANGER_CELL_SHIFT);
                    dangerIndex.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(p);
                }
            }
            // Radius 2 trifft bei 8er-Zellen höchstens zwei Zellen je Achse.
            int minX = (int) (((long) x - DANGER_RADIUS) >> DANGER_CELL_SHIFT), maxX = (int) (((long) x + DANGER_RADIUS) >> DANGER_CELL_SHIFT);
            int minY = (int) (((long) y - DANGER_RADIUS) >> DANGER_CELL_SHIFT), maxY = (int) (((long) y + DANGER_RADIUS) >> DANGER_CELL_SHIFT);
            int minZ = (int) (((long) z - DANGER_RADIUS) >> DANGER_CELL_SHIFT), maxZ = (int) (((long) z + DANGER_RADIUS) >> DANGER_CELL_SHIFT);
            for (int cx = minX; cx <= maxX && distance > 0; cx++)
                for (int cy = minY; cy <= maxY && distance > 0; cy++)
                    for (int cz = minZ; cz <= maxZ && distance > 0; cz++) {
                        var candidates = dangerIndex.get(BlockPos.asLong(cx, cy, cz));
                        if (candidates != null) distance = nearestDanger(candidates, x, y, z, distance);
                    }
        } else {
            distance = nearestDanger(dangerCenters, x, y, z, distance);
        }
        dangerCache.put(key, distance);
        return distance;
    }

    private static int nearestDanger(List<BlockPos> candidates, int x, int y, int z, int distance) {
        for (BlockPos p : candidates) {
            long d = Math.abs((long) p.getX() - x) + Math.abs((long) p.getY() - y) + Math.abs((long) p.getZ() - z);
            if (d < distance) distance = (int) d;
            if (distance == 0) break;
        }
        return distance;
    }

    /** Ein gemerkter Gefahrenpunkt auf einem langen Sprung/Fall zählt, auch wenn Start und Ziel frei sind. */
    public boolean safeSegment(int x, int y, int z, int tx, int ty, int tz) {
        if (!avoidKnownDanger || dangerCenters.isEmpty()) return true;
        int initial = dangerDistance(x, y, z), previous = initial;
        int steps = Math.max(Math.abs(tx - x), Math.max(Math.abs(ty - y), Math.abs(tz - z)));
        for (int i = 0; i <= steps; i++) {
            double fraction = steps == 0 ? 0 : i / (double) steps;
            int distance = dangerDistance((int) Math.round(x + (tx - x) * fraction), (int) Math.round(y + (ty - y) * fraction),
                    (int) Math.round(z + (tz - z) * fraction));
            if (initial > DANGER_RADIUS ? distance <= DANGER_RADIUS : distance < previous) return false;
            previous = distance;
        }
        return initial > DANGER_RADIUS || previous > initial; // aus einem schon gefährlichen Start herauslaufen erlaubt
    }
}
