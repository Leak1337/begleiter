package dev.begleiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import dev.begleiter.CompanionEntity.Task;

/**
 * Zielplaner "Wie komme ich an X?": probiert alle Wege (Inventar, Kiste, abbauen, schmelzen, craften, Mobs) mit
 * geschätzten Kosten (in Sekunden) und nimmt den günstigsten. Gerechnet wird mit einem gedachten Inventar, so dass
 * Zwischenprodukte geteilt werden (Bretter für Stöcke und Werkbank) und Reste nicht doppelt beschafft werden.
 * Bekannte Orte aus dem Gedächtnis machen Abbauen billiger, nötige Spitzhacken werden mit eingeplant.
 */
public final class CompanionPlanner {
    private CompanionPlanner() {}

    public record Plan(List<Task> tasks, double cost, List<String> steps) {
        public String explain() { return String.join(" → ", steps); }
    }

    /** Arbeit und genaue Lieferungen für einen Rezeptauftrag des Spielers; die Planung bewegt nie echte Items. */
    public record RequestPlan(Plan work, Map<Item, Integer> delivery) { }

    /** Route exclusions create alternatives without duplicating recipe/mob/mining code. */
    public enum RoutePolicy { ANY, AVOID_CHESTS, AVOID_MINING }

    public record Alternative(Plan plan, RoutePolicy policy) {}

    private static final int MAX_DEPTH = 14;
    /** Rechenbudget pro Planung (Server-Thread), damit tiefe Rezeptbäume nicht ausufern. */
    static final int BUDGET = 20000;
    /** Kisten-Suche ist teuer (Umkreis absuchen): pro Planung nur einmal je Item. */
    private static final double IMPOSSIBLE = Double.MAX_VALUE;

    /** Aufrufzustand darf nicht in die nächste Welt übernommen werden. */
    /**
     * Früher wurden hier statische Caches geleert. Jede Planung hat jetzt ihren eigenen {@link PlanContext}; es gibt
     * keinen veränderbaren statischen Planungszustand mehr. Bleibt für die Lebenszyklus-Aufrufe erhalten.
     */
    public static void reset() { }

    /**
     * Zustand genau einer Planung: Rechenbudget, Kisten- und Stationscache, Werkbank-Status, Anfrage-Kisten sowie
     * Begleiter und Welt. Wird mit dem {@link State} geteilt (auch über dessen Kopien), nie zwischen Planungen.
     */
    static final class PlanContext {
        final CompanionEntity entity;
        final ServerLevel level;
        int budget = BUDGET;
        final Map<Item, java.util.Optional<BlockPos>> chestCache = new HashMap<>();
        Boolean hasTable;
        final Map<Item, Boolean> stations = new HashMap<>();
        List<BlockPos> requestBoxes;
        /** Kisten im Umkreis 16, einmal je Planung gesucht (vorher: ganzer Würfel für jede Zutat erneut). */
        private List<BlockPos> nearbyContainers;

        List<BlockPos> nearbyContainers() {
            if (nearbyContainers == null) nearbyContainers = entity.storage.containerCandidates(16);
            return nearbyContainers;
        }

        PlanContext(CompanionEntity entity, ServerLevel level) {
            this.entity = entity;
            this.level = level;
        }
    }

    /** Gedachtes Inventar + bisher geplante Aufgaben. */
    private static final class State {
        final Map<Item, Integer> have;
        final List<Task> tasks;
        final List<String> steps;
        int pickTier;
        double fuelLeft;
        double cost;
        final RoutePolicy policy;
        boolean sourceAware;
        boolean prepared, valid = true;
        boolean playerRequest;
        BlockPos excludedChest;
        BlockPos destination;
        Item destinationItem;
        Item preferredFuel;
        final Map<BlockPos, Map<Item, Integer>> chestTaken = new HashMap<>();
        CompanionPrerequisites.Inventory inventory;
        int[] pickUses;
        final Set<Item> installed = new HashSet<>();
        /** Gemeinsamer Zustand dieser einen Planung (Budget, Caches); Kopien teilen ihn. */
        final PlanContext ctx;
        State(Map<Item, Integer> have, List<Task> tasks, List<String> steps, int pickTier, double cost, RoutePolicy policy,
              PlanContext ctx) {
            this.have = have; this.tasks = tasks; this.steps = steps;
            this.pickTier = pickTier; this.cost = cost; this.policy = policy; this.ctx = ctx;
        }
        State copy() {
            State copy = new State(new HashMap<>(have), new ArrayList<>(tasks), new ArrayList<>(steps), pickTier, cost, policy, ctx);
            copy.fuelLeft = fuelLeft;
            copy.sourceAware = sourceAware;
            copy.prepared = prepared;
            copy.valid = valid;
            copy.playerRequest = playerRequest;
            copy.excludedChest = excludedChest;
            copy.destination = destination;
            copy.destinationItem = destinationItem;
            copy.preferredFuel = preferredFuel;
            chestTaken.forEach((pos, items) -> copy.chestTaken.put(pos, new HashMap<>(items)));
            copy.inventory = inventory == null ? null : inventory.copy();
            copy.pickUses = pickUses == null ? null : pickUses.clone();
            copy.installed.addAll(installed);
            return copy;
        }
        int count(Item i) { return have.getOrDefault(i, 0); }
        void add(Item i, int n) {
            have.merge(i, n, Integer::sum);
            if (inventory != null && n > 0) {
                valid &= inventory.add(new ItemStack(i, n));
                int tier = CompanionPrerequisites.pickTier(i);
                if (tier > 0) pickUses[tier] += CompanionPrerequisites.uses(new ItemStack(i)) * n;
            }
        }
        void consume(Item i, int n) { if (inventory != null) valid &= inventory.consume(i, n); }
    }

    /** Plan, um count Stück (insgesamt im Inventar) zu haben; null = geht nicht selbst. */
    public static Plan obtain(CompanionEntity e, Item item, int count) {
        return obtain(e, item, count, RoutePolicy.ANY, false,
                dev.begleiter.ai.CompanionDecisionEngine.enabled() && e.goal == CompanionNeeds.Need.SUPPLY);
    }

    /** Ein begrenzter vorbereiteter Plan, auch wenn einer ersten Sammelaufgabe das passende Werkzeug fehlt. */
    public static Plan obtainPrepared(CompanionEntity e, Item item, int count, RoutePolicy policy) {
        return obtain(e, item, count, policy, true, true);
    }

    /** Das oberste Rezept neu herstellen oder nur seine direkten Zutaten besorgen, ohne es selbst herzustellen. */
    public static RequestPlan request(CompanionEntity e, Item item, int count, boolean materials) {
        if (!(e.level() instanceof ServerLevel sl) || count <= 0 || count > dev.begleiter.chat.ChatCount.MAX) return null;
        Map<Item, Integer> inv = new HashMap<>();
        for (int slot = 0; slot < e.getInventory().getContainerSize(); slot++) {
            ItemStack stack = e.getInventory().getItem(slot);
            if (!stack.isEmpty()) inv.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        State st = new State(inv, new ArrayList<>(), new ArrayList<>(), CompanionCraft.tier(e, "pickaxe"), 0, RoutePolicy.ANY,
                new PlanContext(e, sl));
        st.prepared = st.playerRequest = true;
        st.inventory = new CompanionPrerequisites.Inventory(e, true);
        st.pickUses = CompanionPrerequisites.pickUses(e);
        st.fuelLeft = Math.max(0, e.fuelLeft);
        Set<Item> visiting = new HashSet<>();
        visiting.add(item);
        if (!materials) {
            // Vorhandener Endbestand darf ein umkehrbares Rezept nicht bezahlen (Barren -> Nuggets -> Barren).
            // Seine Slots bleiben belegt, er zählt aber nicht als verfügbare Zutat.
            st.have.remove(item);
            State out = craft(e, sl, st, item, count, 0, visiting);
            State smelted = smelt(e, sl, st, item, count, 0, visiting);
            if (smelted != null && smelted.valid && (out == null || smelted.cost < out.cost)) out = smelted;
            State processed = process(e, sl, st, item, count, 0, visiting);
            if (processed != null && processed.valid && (out == null || processed.cost < out.cost)) out = processed;
            return out == null || !out.valid ? null : new RequestPlan(new Plan(List.copyOf(out.tasks), out.cost,
                    List.copyOf(out.steps)), Map.of(item, count));
        }
        State best = null;
        Map<Item, Integer> delivery = null;
        for (var recipe : CompanionRecipes.craftsFor(sl, item)) {
            if (--st.ctx.budget < 0) break;
            State s = st.copy();
            Map<Item, Integer> inputs = new java.util.LinkedHashMap<>();
            int times = (count + recipe.count() - 1) / recipe.count();
            for (var ingredient : CompanionRecipes.grouped(recipe).entrySet()) {
                s = reserveIngredient(e, sl, s, ingredient.getKey(), ingredient.getValue() * times, 0, visiting, inputs);
                if (s == null) break;
            }
            if (s != null && s.valid && (best == null || s.cost < best.cost)) { best = s; delivery = inputs; }
        }
        if (best == null) {
            for (var recipe : CompanionRecipes.smeltsFor(sl, item)) {
                if (--st.ctx.budget < 0) break;
                Map<Item, Integer> inputs = new java.util.LinkedHashMap<>();
                State s = reserveIngredient(e, sl, st.copy(), recipe.input(), count, 0, visiting, inputs);
                if (s != null && s.valid && (best == null || s.cost < best.cost)) { best = s; delivery = inputs; }
            }
        }
        for (var recipe : CompanionRecipes.processingFor(sl, item)) {
            if (--st.ctx.budget < 0) break;
            State s = st.copy();
            Map<Item, Integer> inputs = new java.util.LinkedHashMap<>();
            int times = (count + recipe.count() - 1) / recipe.count();
            for (var ingredient : CompanionRecipes.grouped(recipe.ingredients()).entrySet()) {
                s = reserveIngredient(e, sl, s, ingredient.getKey(), ingredient.getValue() * times, 0, visiting, inputs);
                if (s == null) break;
            }
            if (s != null && s.valid && (best == null || s.cost < best.cost)) { best = s; delivery = inputs; }
        }
        return best == null ? null : new RequestPlan(new Plan(List.copyOf(best.tasks), best.cost, List.copyOf(best.steps)),
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(delivery)));
    }

    /** Reserve concrete ingredient units once, keeping their occupied inventory slots for later delivery. */
    private static State reserveIngredient(CompanionEntity e, ServerLevel sl, State st, Ingredient ingredient, int amount,
                                          int depth, Set<Item> visiting, Map<Item, Integer> reserved) {
        State s = st.copy();
        for (Item existing : new ArrayList<>(s.have.keySet())) {
            if (!ingredient.test(new ItemStack(existing))) continue;
            int used = Math.min(amount, s.count(existing));
            if (used <= 0) continue;
            s.add(existing, -used); reserved.merge(existing, used, Integer::sum); amount -= used;
        }
        // Erst passende Varianten aus Kisten zusammenlegen, bevor etwas neu besorgt wird.
        for (Holder<Item> option : (Iterable<Holder<Item>>) ingredient.items()::iterator) {
            if (amount == 0 || --st.ctx.budget < 0) break;
            Item input = option.value();
            State taken = requestChests(e, sl, s, input, amount);
            int used = Math.min(amount, taken.count(input));
            if (used <= 0) continue;
            s = taken; s.add(input, -used); reserved.merge(input, used, Integer::sum); amount -= used;
        }
        if (amount == 0) return s;
        Item input = choose(e, sl, s, ingredient, amount, depth, visiting);
        State out = input == null ? null : get(e, sl, s, input, amount, depth + 1, visiting);
        if (out != null) reserved.merge(input, amount, Integer::sum);
        return out;
    }

    /** Einen veränderten Behälter gegen das echte Inventar neu planen; der übrige Auftrag bleibt erhalten. */
    public static Plan refillRequest(CompanionEntity e, Item item, int total) {
        return refillRequest(e, item, total, null);
    }

    /** Eine gescheiterte Quelle nur für diese Wiederaufnahme ausschließen, samt der anderen Doppelkistenhälfte. */
    public static Plan refillRequest(CompanionEntity e, Item item, int total, BlockPos excludedSource) {
        return refillRequest(e, item, total, excludedSource, null, null, null);
    }

    public static Plan refillHomeOrder(CompanionEntity e, Item item, int total, BlockPos home, String fuel) {
        return refillRequest(e, item, total, null, home, item, fuel == null ? null : ItemIds.resolve(fuel));
    }

    /** If a second waiting order can use the same stop, pack both items on the first visit. */
    public static Task bundleHomePickup(CompanionEntity mob, ServerLevel world, Plan plan,
                                        dev.begleiter.task.TaskSpec.Request current, net.minecraft.world.Container home) {
        if (plan.tasks().size() != 1 || !(plan.tasks().getFirst().spec() instanceof dev.begleiter.task.TaskSpec.Take first)) return null;
        var source = mob.orderStorage(world, first.pos());
        if (source == null) return null;
        for (Task task : mob.peekTasks(32)) {
            if (task == mob.currentTask()) continue;
            if (!(task.spec() instanceof dev.begleiter.task.TaskSpec.Request next) || next.phase() != 5
                    || !next.pos().equals(current.pos()) || next.item().equals(current.item())) return null;
            Item item = ItemIds.resolve(next.item());
            int missing = next.count() - home.countItem(item) - mob.getInventory().countItem(item);
            int surplus = source.countItem(item) - mob.stockReserveAt(world, first.pos(), item);
            if (missing <= 0 || missing > 64 || surplus < missing) return null;
            var preview = new CompanionPrerequisites.Inventory(mob, true);
            if (preview.addFrom(source, ItemIds.resolve(first.item()), first.count()) != 0
                    || preview.addFrom(source, item, missing) != 0) return null;
            return Task.take(first.pos(), next.item(), missing);
        }
        return null;
    }

    private static Plan refillRequest(CompanionEntity e, Item item, int total, BlockPos excludedSource,
                                      BlockPos destination, Item destinationItem, Item preferredFuel) {
        if (!(e.level() instanceof ServerLevel sl) || total < 1 || total > (1 << 20)) return null;
        Map<Item, Integer> inv = new HashMap<>();
        for (int slot = 0; slot < e.getInventory().getContainerSize(); slot++) {
            ItemStack stack = e.getInventory().getItem(slot);
            if (!stack.isEmpty()) inv.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        State st = new State(inv, new ArrayList<>(), new ArrayList<>(), CompanionCraft.tier(e, "pickaxe"), 0, RoutePolicy.ANY,
                new PlanContext(e, sl));
        st.prepared = st.playerRequest = true;
        st.inventory = new CompanionPrerequisites.Inventory(e, true);
        st.pickUses = CompanionPrerequisites.pickUses(e);
        st.fuelLeft = Math.max(0, e.fuelLeft);
        st.excludedChest = excludedSource;
        st.destination = destination;
        st.destinationItem = destinationItem;
        st.preferredFuel = preferredFuel;
        State out = produce(e, sl, st, item, total, 0, new HashSet<>());
        return out == null || !out.valid ? null : new Plan(List.copyOf(out.tasks), out.cost, List.copyOf(out.steps));
    }

    /** One bounded container scan per recipe request, shared by its entire dependency tree. */
    private static State requestChests(CompanionEntity e, ServerLevel sl, State st, Item item, int missing) {
        if (st.ctx.requestBoxes == null && st.destination != null && e.zones.get(CompanionZones.Kind.STORAGE, sl) == null)
            st.ctx.requestBoxes = sl.hasChunkAt(st.destination) ? List.of(st.destination) : List.of();
        if (st.ctx.requestBoxes == null) {
            java.util.Set<BlockPos> boxes = new java.util.LinkedHashSet<>();
            if (e.getHome() != null && sl.hasChunkAt(e.getHome())) boxes.add(e.getHome());
            for (var place : e.memory.find(CompanionMemory.Kind.CHEST, null, sl, e.blockPosition())) {
                if (boxes.size() >= 64) break;
                if (place.pos.distSqr(e.blockPosition()) <= 24 * 24 && sl.hasChunkAt(place.pos)) boxes.add(place.pos);
            }
            BlockPos center = e.blockPosition();
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -8, -16), center.offset(16, 8, 16))) {
                if (boxes.size() >= 64) break;
                if (!sl.hasChunkAt(pos)) continue;
                var block = sl.getBlockState(pos).getBlock();
                if (block instanceof net.minecraft.world.level.block.ChestBlock || block instanceof net.minecraft.world.level.block.BarrelBlock)
                    boxes.add(pos.immutable());
            }
            st.ctx.requestBoxes = boxes.stream().sorted(java.util.Comparator.comparingDouble(p ->
                    p.distSqr(center) * e.routes.sourcePreference(sl, p))).toList();
        }
        State s = st.copy();
        int left = missing;
        // Eine Doppelkiste ist eine Quelle, auch wenn sie über beide Blockentities gefunden wurde.
        java.util.Set<BlockPos> visited = new HashSet<>();
        for (BlockPos pos : st.ctx.requestBoxes) {
            if (left <= 0) break;
            if (!visited.add(pos) || e.memory.dangerous(sl, pos, 8) || st.destination != null && e.gatherMissed(sl, pos)) continue;
            if (pos.equals(st.excludedChest) || item == st.destinationItem && pos.equals(st.destination)) continue;
            var state = sl.getBlockState(pos);
            BlockPos key = pos;
            if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                    && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                BlockPos other = pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
                visited.add(other);
                if (other.equals(st.excludedChest) || item == st.destinationItem && other.equals(st.destination)) continue;
                if (st.destination != null && e.gatherMissed(sl, other)) continue;
                if (other.asLong() < key.asLong()) key = other;
            }
            var contents = st.destination == null ? e.containerAt(sl, pos) : e.orderStorage(sl, pos);
            if (contents == null) continue;
            int available = 0;
            for (int slot = 0; slot < contents.getContainerSize(); slot++)
                if (contents.getItem(slot).is(item)) available += contents.getItem(slot).getCount();
            available -= s.chestTaken.getOrDefault(key, Map.of()).getOrDefault(item, 0);
            available -= e.stockReserveAt(sl, pos, item);
            int take = Math.min(left, Math.max(0, available));
            if (take == 0) continue;
            if (!s.inventory.add(new ItemStack(item, take))) { s.valid = false; return s; }
            s.have.merge(item, take, Integer::sum);
            s.chestTaken.computeIfAbsent(key.immutable(), p -> new HashMap<>()).merge(item, take, Integer::sum);
            s.tasks.add(Task.take(pos, id(item), take));
            s.steps.add(take + "x " + id(item) + " from chest");
            s.cost += 8 + Math.sqrt(pos.distSqr(e.blockPosition())) / 4;
            left -= take;
        }
        return s;
    }

    /** At most three bounded runs for autonomous retries or learned route preferences. */
    public static List<Alternative> alternatives(CompanionEntity e, Item item, int count) {
        List<Alternative> result = new ArrayList<>();
        for (RoutePolicy policy : RoutePolicy.values()) {
            Plan plan = obtain(e, item, count, policy, true, dev.begleiter.ai.CompanionDecisionEngine.enabled());
            if (plan == null || plan.tasks().isEmpty()) continue;
            boolean duplicate = result.stream().anyMatch(existing -> existing.plan().tasks().equals(plan.tasks()));
            if (!duplicate) result.add(new Alternative(plan, policy));
        }
        return List.copyOf(result);
    }

    static Plan obtain(CompanionEntity e, Item item, int count, RoutePolicy policy) {
        return obtain(e, item, count, policy, false, false);
    }

    private static Plan obtain(CompanionEntity e, Item item, int count, RoutePolicy policy, boolean sourceAware, boolean prepared) {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        Map<Item, Integer> inv = new HashMap<>();
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!s.isEmpty()) inv.merge(canon(s.getItem()), s.getCount(), Integer::sum);
        }
        ItemStack hand = e.getMainHandItem();
        if (!prepared && !hand.isEmpty()) inv.merge(canon(hand.getItem()), hand.getCount(), Integer::sum);
        State st = new State(inv, new ArrayList<>(), new ArrayList<>(), CompanionCraft.tier(e, "pickaxe"), 0, policy,
                new PlanContext(e, sl));
        st.fuelLeft = Math.max(0, e.fuelLeft);
        st.sourceAware = sourceAware;
        st.prepared = prepared;
        if (prepared) {
            st.inventory = new CompanionPrerequisites.Inventory(e);
            st.pickUses = CompanionPrerequisites.pickUses(e);
        }
        long t0 = System.nanoTime();
        // Was er schon hat, zählt mit: count = Gesamtbestand
        Item c = canon(item);
        int need = count - st.count(c);
        if (need <= 0) return new Plan(List.of(), 0, List.of("already there"));
        State out = produce(e, sl, st, c, count, 0, new HashSet<>());
        e.consoleDebug("Goal planner " + id(c) + " x" + need + ": " + (out == null ? "no route" : out.steps.size() + " steps")
                + ", " + (BUDGET - st.ctx.budget) + " nodes, " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        if (out == null) return null;
        return new Plan(out.tasks, out.cost, out.steps);
    }

    /** Holzarten bleiben exakt; Austauschbarkeit ergibt sich nur aus der jeweiligen Rezeptzutat. */
    static Item canon(Item i) {
        if (i == Items.COBBLED_DEEPSLATE) return Items.COBBLESTONE;
        if (i == Items.CHARCOAL) return Items.COAL;
        return i;
    }

    private static String id(Item i) { return BuiltInRegistries.ITEM.getKey(i).toString(); }

    /**
     * need Stück von item bereitstellen und dann verbrauchen (sie stehen danach nicht mehr im gedachten
     * Inventar). Rückgabe: neuer Zustand mit Aufgaben, oder null.
     */
    private static State get(CompanionEntity e, ServerLevel sl, State st, Item item, int need, int depth, Set<Item> visiting) {
        State s = produce(e, sl, st, item, need, depth, visiting);
        if (s == null) return null;
        s.add(item, -need);
        return s;
    }

    /** Sorgt dafür, dass mindestens need Stück im gedachten Inventar sind (verbraucht nichts). */
    private static State produce(CompanionEntity e, ServerLevel sl, State st, Item item, int need, int depth, Set<Item> visiting) {
        int missing = need - st.count(item);
        if (missing <= 0) return st.copy();
        if (depth > MAX_DEPTH || --st.ctx.budget < 0 || !visiting.add(item)) return null;
        try {
            if (st.playerRequest) {
                st = requestChests(e, sl, st, item, missing);
                missing = need - st.count(item);
                if (missing <= 0) return st.valid ? st : null;
            }
            State best = null;
            for (State cand : new State[]{
                    st.playerRequest ? null : fromChest(e, sl, st, item, missing),
                    st.playerRequest ? null : fromKnownChests(e, sl, st, item, missing),
                    special(e, sl, st, item, missing, depth, visiting),
                    mine(e, sl, st, item, missing, depth, visiting),
                    fromMobs(e, st, item, missing),
                    smelt(e, sl, st, item, missing, depth, visiting),
                    craft(e, sl, st, item, missing, depth, visiting),
                    st.playerRequest ? process(e, sl, st, item, missing, depth, visiting) : null}) {
                if (cand != null && cand.valid && (best == null || cand.cost < best.cost)) best = cand;
            }
            return best;
        } finally {
            visiting.remove(item);
        }
    }

    // ------------------------------------------------------------------ Wege

    private static State fromChest(CompanionEntity e, ServerLevel sl, State st, Item item, int missing) {
        if (st.policy == RoutePolicy.AVOID_CHESTS) return null;
        // Unknown chest tool durability cannot count as a fresh crafted tool.
        if (st.prepared && CompanionPrerequisites.pickTier(item) > 0) return null;
        Item real = item;
        BlockPos box = st.ctx.chestCache.computeIfAbsent(real, k -> java.util.Optional.ofNullable(st.sourceAware
                ? e.findContainerWith(k, st.ctx.nearbyContainers(), p -> CompanionSources.allowed(e, k, p))
                : e.findContainerWith(k, st.ctx.nearbyContainers(), p -> true))).orElse(null);
        if (box == null) return null;
        CompanionMemory.Place known = e.memory.chestWith(real, sl, box, 1);
        int avail = (known == null ? missing : known.itemCount(real)) - e.stockReserveAt(sl, box, real);
        if (avail < missing) return null; // Teilmengen: lieber anders komplett
        State s = st.copy();
        s.tasks.add(Task.take(box, id(real), missing));
        s.steps.add(missing + "x " + id(real) + " from chest");
        if (s.prepared) {
            var contents = sl.hasChunkAt(box) ? e.containerAt(sl, box) : null;
            if (contents == null || s.inventory.addFrom(contents, real, missing) != 0) return null;
            s.have.merge(item, missing, Integer::sum);
        } else s.add(item, missing);
        s.cost += 8 + Math.sqrt(box.distSqr(e.blockPosition())) / 4;
        return s;
    }

    /**
     * Kleine Mengen aus höchstens vier geprüften, erinnerten Kisten zusammenlegen.
     * Nur vorbereitete Planung auf Anforderung: keine ungesehenen Chunks, keine veralteten Zählungen,
     * keine wiederholten Behältersuchen in der ganzen Welt und keine Änderung an Spielerbefehlen.
     */
    private static State fromKnownChests(CompanionEntity e, ServerLevel sl, State st, Item item, int missing) {
        if (!st.prepared || !st.sourceAware || st.policy == RoutePolicy.AVOID_CHESTS || missing < 2
                || item == Items.OAK_LOG || item == Items.OAK_PLANKS) return null;
        State s = st.copy();
        int left = missing;
        int visited = 0, sources = 0;
        double distanceCost = 0;
        for (CompanionMemory.Place remembered : e.memory.find(
                CompanionMemory.Kind.CHEST, null, sl, e.blockPosition())) {
            if (++visited > 16 || sources == 4 || left == 0) break;
            BlockPos pos = remembered.pos;
            if (pos.distSqr(e.blockPosition()) > 24 * 24 || !sl.hasChunkAt(pos)
                    || e.memory.dangerous(sl, pos, 8) || !CompanionSources.allowed(e, item, pos)) continue;
            net.minecraft.world.Container container = e.containerAt(sl, pos);
            if (container == null) continue;
            int available = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(item)) available += stack.getCount();
            }
            int take = Math.min(left, Math.max(0, available - e.stockReserveAt(sl, pos, item)));
            if (take == 0) continue;
            // Eine gescheiterte Inventarsimulation kann schon Teilstapel eingefügt haben.
            // Jede Kiste auf einer Kopie bewerten, damit verworfene Quellen den Plan nicht verfälschen.
            var trialInventory = s.inventory.copy();
            if (trialInventory.addFrom(container, item, take) != 0) continue;
            s.inventory = trialInventory;
            s.have.merge(item, take, Integer::sum);
            s.tasks.add(Task.take(pos, id(item), take));
            s.steps.add(take + "x " + id(item) + " from known chest");
            left -= take;
            sources++;
            distanceCost += 8 + Math.sqrt(pos.distSqr(e.blockPosition())) / 4;
        }
        if (left > 0 || sources < 2) return null;
        s.cost += distanceCost;
        return s;
    }

    private static State special(CompanionEntity e, ServerLevel sl, State st, Item item, int missing, int depth, Set<Item> visiting) {
        if (item == Items.WATER_BUCKET) {
            State s = get(e, sl, st, Items.BUCKET, 1, depth + 1, visiting);
            if (s == null) return null;
            s.consume(Items.BUCKET, 1);
            s.tasks.add(Task.water(null));
            s.steps.add("fill bucket with water");
            s.add(Items.WATER_BUCKET, 1);
            CompanionMemory.Place w = e.memory.nearest(CompanionMemory.Kind.WATER, null, sl, e.blockPosition(), 200);
            s.cost += w == null ? 40 : 10 + Math.sqrt(w.pos.distSqr(e.blockPosition())) / 4;
            return missing > 1 ? null : s;
        }
        if (item == Items.WHEAT_SEEDS) {
            State s = st.copy();
            s.tasks.add(Task.gather(Resources.SEEDS.key(), missing));
            s.steps.add("break grass for " + missing + " seeds");
            s.add(item, missing);
            s.cost += missing * 8;
            return s;
        }
        return null;
    }

    /** Sekunden pro Stück beim Abbauen (grob, inkl. Suchen) und nötige Spitzhacke. */
    private record MineInfo(Resources.Res res, double perUnit, int pick, int perDrop) {}

    private static MineInfo mineInfo(Item item) {
        String id = id(item);
        return switch (id) {
            case "minecraft:oak_log" -> new MineInfo(Resources.WOOD, 3, 0, 1);
            case "minecraft:cobblestone" -> new MineInfo(Resources.STONE, 1.5, 1, 1);
            case "minecraft:dirt" -> new MineInfo(Resources.DIRT, 1, 0, 1);
            case "minecraft:sand", "minecraft:red_sand" -> new MineInfo(Resources.SAND, 1.5, 0, 1);
            case "minecraft:gravel" -> new MineInfo(Resources.GRAVEL, 2, 0, 1);
            case "minecraft:flint" -> new MineInfo(Resources.GRAVEL, 2, 0, 10); // jeder ~10. Kies
            case "minecraft:coal" -> new MineInfo(Resources.COAL, 6, 1, 1);
            case "minecraft:raw_iron" -> new MineInfo(Resources.IRON, 10, 2, 1);
            case "minecraft:raw_copper" -> new MineInfo(Resources.COPPER, 6, 2, 1);
            case "minecraft:raw_gold" -> new MineInfo(Resources.GOLD, 20, 3, 1);
            case "minecraft:redstone" -> new MineInfo(Resources.REDSTONE, 4, 3, 1);
            case "minecraft:lapis_lazuli" -> new MineInfo(Resources.LAPIS, 4, 2, 1);
            case "minecraft:diamond" -> new MineInfo(Resources.DIAMOND, 45, 3, 1);
            case "minecraft:emerald" -> new MineInfo(Resources.EMERALD, 80, 3, 1);
            case "minecraft:obsidian" -> new MineInfo(Resources.OBSIDIAN, 30, 4, 1);
            default -> null;
        };
    }

    private static final String[] PICKS = {null, "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe"};

    private static State mine(CompanionEntity e, ServerLevel sl, State st, Item item, int missing, int depth, Set<Item> visiting) {
        if (st.policy == RoutePolicy.AVOID_MINING) return null;
        if (new ItemStack(item).is(ItemTags.LOGS)) {
            if (net.minecraft.world.level.block.Block.byItem(item) == net.minecraft.world.level.block.Blocks.AIR) return null;
            // Ein exaktes Bau-/Rezeptziel darf nicht durch GATHER einer beliebigen Holzart erfüllt werden.
            State s = st.copy();
            s.tasks.add(new Task(new dev.begleiter.task.TaskSpec.Gather(Resources.WOOD.key(), missing, 0, id(item))));
            s.steps.add(missing + "x " + id(item) + " mine");
            s.add(item, missing);
            s.cost += 5 + missing * 3;
            return s.valid ? s : null;
        }
        MineInfo mi = mineInfo(item);
        if (mi == null) return null;
        State s = st.copy();
        // Werkzeug: fehlende Spitzhacke(n) mit einplanen
        int units = missing * mi.perDrop();
        if (s.prepared && mi.pick() > 0) {
            int available = 0;
            for (int tier = mi.pick(); tier < s.pickUses.length; tier++) available += s.pickUses[tier];
            if (available < units) {
                Item pick = CompanionCraft.item(PICKS[mi.pick()]);
                int perPick = CompanionPrerequisites.uses(new ItemStack(pick));
                int replacements = (units - available + perPick - 1) / perPick;
                s = produce(e, sl, s, pick, s.count(pick) + replacements, depth + 1, visiting);
                if (s == null) return null;
            }
            int remaining = units;
            for (int tier = s.pickUses.length - 1; tier >= mi.pick(); tier--) {
                int used = Math.min(remaining, s.pickUses[tier]);
                s.pickUses[tier] -= used;
                remaining -= used;
            }
            if (remaining > 0) return null;
        } else if (mi.pick() > s.pickTier) {
            Item pick = CompanionCraft.item(PICKS[mi.pick()]);
            State t = produce(e, sl, s, pick, 1, depth + 1, visiting);
            if (t == null) return null;
            s = t;
            s.pickTier = mi.pick();
        }
        double per = mi.perUnit();
        double travel = 0;
        if (CompanionMemory.isOre(mi.res()) || mi.res() == Resources.WOOD) {
            CompanionMemory.Place known = e.memory.nearest(mi.res() == Resources.WOOD ? CompanionMemory.Kind.TREE
                    : CompanionMemory.Kind.ORE, mi.res() == Resources.WOOD ? null : mi.res().key(), sl, e.blockPosition(), 200);
            if (known != null) { per *= 0.5; travel = Math.sqrt(known.pos.distSqr(e.blockPosition())) / 4; }
        }
        s.tasks.add(Task.gather(mi.res().key(), units));
        s.steps.add("mine " + mi.res().key() + " (" + units + ")");
        s.add(item, missing);
        s.cost += 5 + travel + per * units;
        return s;
    }

    /** Beute von Mobs, die er gezielt bekämpft. */
    private static State fromMobs(CompanionEntity e, State st, Item item, int missing) {
        String mob = switch (id(item)) {
            case "minecraft:string" -> "spider";
            case "minecraft:feather" -> "chicken";
            case "minecraft:leather", "minecraft:beef" -> "cow";
            case "minecraft:white_wool", "minecraft:mutton" -> "sheep";
            case "minecraft:porkchop" -> "pig";
            case "minecraft:ender_pearl" -> "enderman";
            case "minecraft:bone" -> "skeleton";
            default -> null;
        };
        if (mob == null) return null;
        boolean nightMob = mob.equals("spider") || mob.equals("enderman") || mob.equals("skeleton");
        State s = st.copy();
        // arg "mob|beute": der Kampf endet, wenn genug von genau dieser Beute da ist
        s.tasks.add(Task.fight(null, mob, id(item), missing + CompanionCraft.count(e, id(item))));
        s.steps.add("hunt " + mob + " for " + missing + "x " + id(item));
        s.add(item, missing);
        s.cost += 15 + missing * (nightMob ? 25 : 10) + (nightMob && !e.level().isDarkOutside() ? 300 : 0);
        return s;
    }

    private static State smelt(CompanionEntity e, ServerLevel sl, State st, Item item, int missing, int depth, Set<Item> visiting) {
        if (CompanionRecipes.smeltsFor(sl, item).isEmpty()) return null;
        if (st.prepared) {
            st = station(e, sl, st, Items.FURNACE, depth, visiting);
            if (st == null) return null;
        }
        // Eingabe: über alle Ofen-Rezepte die günstigste (Roheisen statt Eisenerz, das er nicht als Block bekommt)
        Item in = null;
        double bc = IMPOSSIBLE;
        for (CompanionRecipes.Smelt sm : CompanionRecipes.smeltsFor(sl, item)) {
            Item cand = choose(e, sl, st, sm.input(), missing, depth, visiting);
            if (cand == null) continue;
            if (st.count(cand) >= missing) { in = cand; break; }
            State probe = produce(e, sl, st, cand, missing, depth + 1, visiting);
            if (probe != null && probe.cost < bc) { bc = probe.cost; in = cand; }
        }
        if (in == null) return null;
        State s = get(e, sl, st, in, missing, depth + 1, visiting);
        if (s == null) return null;
        s = consumeFuel(e, sl, s, missing, depth, visiting);
        if (s == null) return null;
        s.consume(in, missing);
        s.tasks.add(Task.smelt(id(item), missing + (s.prepared ? s.inventory.count(item) : CompanionCraft.count(e, id(item)))));
        s.steps.add(missing + "x " + id(item) + " smelt");
        s.add(item, missing);
        s.cost += 5 + missing * 10;
        return s;
    }

    /** Mirror CompanionCraft.takeFuel, including fractional wood fuel and leftover burn time. */
    private static State consumeFuel(CompanionEntity e, ServerLevel sl, State st, int amount,
                                     int depth, Set<Item> visiting) {
        State s = st.copy();
        Map<Item, Integer> consumed = new HashMap<>();
        for (int i = 0; i < amount; i++) {
            if (s.fuelLeft < 1 && s.preferredFuel != null && s.count(s.preferredFuel) == 0) {
                State preferred = requestChests(e, sl, s, s.preferredFuel, 1);
                if (preferred.valid) s = preferred;
            }
            Item coal = coalFuel(s, consumed);
            Item wood = woodFuel(s, consumed);
            if (s.fuelLeft < 1 && coal == null && wood == null) {
                State f = produce(e, sl, s, Items.COAL, 1, depth + 1, visiting);
                if (f == null) f = produce(e, sl, s, Items.OAK_PLANKS, 1, depth + 1, visiting);
                if (f == null) return null;
                s = f;
                coal = coalFuel(s, consumed);
                wood = woodFuel(s, consumed);
            }
            if (s.fuelLeft < 1) {
                Item fuel = coal == null ? wood : coal;
                if (fuel == null) return null;
                if (s.prepared) {
                    // Die Ausführung verbrennt zuerst Kohle, auch wenn ein äußeres Rezept sie reserviert hat.
                    // Widersprüchliche Reservierungen ablehnen, statt darauf zu setzen, dass sie übersprungen werden.
                    if (s.count(fuel) <= 0) return null;
                }
                s.add(fuel, -1);
                consumed.merge(fuel, 1, Integer::sum);
                s.fuelLeft += fuel == Items.COAL || fuel == Items.CHARCOAL ? 8 : 1.5;
            }
            s.fuelLeft -= 1;
        }
        for (var fuel : consumed.entrySet()) s.consume(fuel.getKey(), fuel.getValue());
        return s;
    }

    private static Item coalFuel(State state, Map<Item, Integer> consumed) {
        if (state.preferredFuel != null && fuelCount(state, state.preferredFuel, consumed) > 0) return state.preferredFuel;
        if (fuelCount(state, Items.COAL, consumed) > 0) return Items.COAL;
        return fuelCount(state, Items.CHARCOAL, consumed) > 0 ? Items.CHARCOAL : null;
    }

    private static int fuelCount(State state, Item item, Map<Item, Integer> consumed) {
        return state.prepared ? state.inventory.count(item) - consumed.getOrDefault(item, 0) : state.count(item);
    }

    private static Item woodFuel(State s, Map<Item, Integer> consumed) {
        for (var tag : List.of(ItemTags.PLANKS, ItemTags.LOGS))
            for (Item item : s.have.keySet()) {
                if (!new ItemStack(item).is(tag)) continue;
                int available = s.prepared ? s.inventory.count(item) - consumed.getOrDefault(item, 0) : s.count(item);
                if (available > 0) return item;
            }
        return null;
    }

    private static State craft(CompanionEntity e, ServerLevel sl, State st, Item item, int missing, int depth, Set<Item> visiting) {
        State best = null;
        int tried = 0;
        for (CompanionRecipes.Craft c : CompanionRecipes.craftsFor(sl, item)) {
            if (++tried > 4 && !st.playerRequest || st.ctx.budget <= 0) break;
            int times = (missing + c.count() - 1) / c.count();
            State s = st.copy();
            if (s.prepared && c.needsTable()) {
                s = station(e, sl, s, Items.CRAFTING_TABLE, depth, visiting);
                if (s == null) continue;
            }
            boolean ok = true;
            Map<Item, Integer> consumed = new HashMap<>();
            for (var en : CompanionRecipes.grouped(c).entrySet()) {
                int amount = en.getValue() * times;
                if (s.playerRequest) {
                    s = reserveIngredient(e, sl, s, en.getKey(), amount, depth, visiting, consumed);
                    if (s == null) { ok = false; break; }
                    continue;
                }
                // Zulässige Varianten einer Tag-Zutat dürfen gemeinsam reichen (z.B. Eiche + Birke).
                for (Item existing : new ArrayList<>(s.have.keySet())) {
                    if (!en.getKey().test(new ItemStack(existing))) continue;
                    int used = Math.min(amount, s.count(existing));
                    if (used <= 0) continue;
                    s.add(existing, -used);
                    consumed.merge(existing, used, Integer::sum);
                    amount -= used;
                }
                if (amount == 0) continue;
                Item in = choose(e, sl, s, en.getKey(), amount, depth, visiting);
                State t = in == null ? null : get(e, sl, s, in, amount, depth + 1, visiting);
                if (t == null) { ok = false; break; }
                s = t;
                consumed.merge(in, amount, Integer::sum);
            }
            if (!ok) continue;
            for (var input : consumed.entrySet()) s.consume(input.getKey(), input.getValue());
            s.tasks.add(Task.craft(id(item), missing));
            s.steps.add(c.count() * times + "x " + id(item) + " craft");
            s.add(item, c.count() * times);
            if (!s.prepared && c.needsTable() && st.ctx.hasTable == null) st.ctx.hasTable = e.findStation("crafting_table", 24) != null;
            s.cost += 2 + (!s.prepared && c.needsTable() && !st.ctx.hasTable ? 6 : 0);
            if (s.valid && (best == null || s.cost < best.cost)) best = s;
        }
        return best;
    }

    private static State process(CompanionEntity e, ServerLevel sl, State st, Item item, int missing, int depth, Set<Item> visiting) {
        if (!st.playerRequest || missing > CompanionRecipes.MAX_PROCESSING_BATCH) return null;
        State best = null;
        for (var recipe : CompanionRecipes.processingFor(sl, item)) {
            if (--st.ctx.budget < 0) break;
            State s = station(e, sl, st.copy(), CompanionCraft.item(recipe.station()), depth, visiting);
            if (s == null) continue;
            int times = (missing + recipe.count() - 1) / recipe.count();
            Map<Item, Integer> consumed = new HashMap<>();
            for (var ingredient : CompanionRecipes.grouped(recipe.ingredients()).entrySet()) {
                s = reserveIngredient(e, sl, s, ingredient.getKey(), ingredient.getValue() * times, depth, visiting, consumed);
                if (s == null) break;
            }
            if (s == null) continue;
            for (var input : consumed.entrySet()) s.consume(input.getKey(), input.getValue());
            s.tasks.add(Task.process(id(item), missing, recipe.id()));
            s.steps.add(recipe.station() + ": " + (recipe.count() * times) + "x " + id(item));
            s.add(item, recipe.count() * times);
            s.cost += 2;
            if (s.valid && (best == null || s.cost < best.cost)) best = s;
        }
        return best;
    }

    private static State station(CompanionEntity e, ServerLevel sl, State st, Item item, int depth, Set<Item> visiting) {
        if (st.installed.contains(item)) return st.copy();
        if (st.ctx.stations.computeIfAbsent(item, k -> (st.playerRequest ? e.findOrderStation(id(k), 24) : e.findStation(id(k), 24)) != null)) {
            State s = st.copy();
            s.installed.add(item);
            return s;
        }
        State s = get(e, sl, st, item, 1, depth + 1, visiting);
        if (s == null) return null;
        s.consume(item, 1);
        s.installed.add(item);
        s.tasks.add(Task.station(id(item), false));
        s.steps.add("place " + id(item));
        s.cost += 6;
        return s.valid ? s : null;
    }

    /** Welche Variante einer Zutat? Was im gedachten Inventar reicht, sonst die günstigste der ersten Varianten. */
    private static Item choose(CompanionEntity e, ServerLevel sl, State st, Ingredient ing, int amount, int depth, Set<Item> visiting) {
        List<Item> options = new ArrayList<>();
        for (Holder<Item> h : (Iterable<Holder<Item>>) ing.items()::iterator) {
            Item c = st.playerRequest ? h.value() : canon(h.value());
            if (!options.contains(c)) options.add(c);
        }
        for (Item o : options) if (st.count(o) >= amount) return o;
        if (options.size() == 1) return visiting.contains(options.getFirst()) ? null : options.getFirst();
        Item best = null;
        double bc = IMPOSSIBLE;
        for (int i = 0; i < (st.playerRequest ? options.size() : Math.min(3, options.size())); i++) {
            if (st.ctx.budget <= 0) break;
            Item o = options.get(i);
            if (visiting.contains(o)) continue;
            State s = produce(e, sl, st, o, amount, depth + 1, visiting);
            if (s != null && s.cost < bc) { bc = s.cost; best = o; }
        }
        return best;
    }
}
