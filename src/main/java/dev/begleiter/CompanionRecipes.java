package dev.begleiter;

import dev.begleiter.debug.BotDebug.EventKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

/**
 * Das Rezeptbuch von Minecraft für den Begleiter: alle Werkbank- und Ofen-Rezepte (auch Redstone-Teile wie
 * Verstärker, Kolben, Beobachter). Ergänzt die kleine feste Liste in {@link CompanionCraft}.
 */
public final class CompanionRecipes {
    private CompanionRecipes() {}

    /** Ein Werkbank-Rezept: Zutaten (je Feld eine Auswahl), Ergebnis-Anzahl, braucht es die 3x3-Werkbank? */
    public record Craft(List<Ingredient> ingredients, int count, boolean needsTable,
                        CraftingRecipe recipe, int width, int height, List<java.util.Optional<Ingredient>> layout) {}

    /** Ein Ofen-Rezept: Eingabe -> 1 Ergebnis. */
    public record Smelt(Ingredient input) {}

    /** A native station recipe; optional smithing slots retain their exact positions. */
    public record Processing(String id, String station, Item result, int count,
                             List<java.util.Optional<Ingredient>> layout, Recipe<?> recipe) {
        public List<Ingredient> ingredients() { return layout.stream().flatMap(java.util.Optional::stream).toList(); }
    }

    public enum ProcessingResult { SUCCESS, MISSING_INPUT, INVENTORY_FULL, INVALID_RECIPE }
    public static final int MAX_PROCESSING_BATCH = 4096;

    private static Map<Item, List<Craft>> crafts;
    private static Map<Item, Smelt> smelts;
    private static Map<Item, List<Smelt>> smeltsAll;
    private static Map<Item, List<Processing>> processing;
    private static Map<String, Processing> processingById;
    private static Object builtFor;

    /** Rezeptmanager und daraus erzeugte Daten nach dem Serverende freigeben. */
    public static void reset() {
        crafts = null;
        smelts = null;
        smeltsAll = null;
        processing = null;
        processingById = null;
        builtFor = null;
    }

    private static void ensure(ServerLevel sl) {
        var rm = sl.recipeAccess();
        if (builtFor == rm && crafts != null) return;
        crafts = new HashMap<>();
        smelts = new HashMap<>();
        smeltsAll = new HashMap<>();
        processing = new HashMap<>();
        processingById = new HashMap<>();
        builtFor = rm;
        for (RecipeHolder<?> h : rm.getRecipes()) {
            try {
                if (h.value() instanceof CraftingRecipe cr && !cr.isSpecial()) index(sl, cr);
                else if (h.value() instanceof StonecutterRecipe sr) {
                    indexProcessing(sl, h, "stonecutter", List.of(java.util.Optional.of(sr.input())));
                } else if (h.value() instanceof SmithingTransformRecipe sr) {
                    indexProcessing(sl, h, "smithing_table", List.of(sr.templateIngredient(),
                            java.util.Optional.of(sr.baseIngredient()), sr.additionIngredient()));
                } else if (h.value() instanceof SmeltingRecipe sr) {
                    ItemStack out = firstStack(sr.input())
                            .map(in -> ((SingleItemRecipe) sr).assemble(new SingleRecipeInput(in))).orElse(ItemStack.EMPTY);
                    if (!out.isEmpty()) {
                        smelts.putIfAbsent(out.getItem(), new Smelt(sr.input()));
                        smeltsAll.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(new Smelt(sr.input()));
                    }
                }
            } catch (RuntimeException ignored) {
                // exotische Rezepte (z.B. mit Komponenten) überspringen
            }
        }
    }

    private static java.util.Optional<ItemStack> firstStack(Ingredient ing) {
        return ing.items().findFirst().map(Holder::value).map(ItemStack::new);
    }

    private static void indexProcessing(ServerLevel level, RecipeHolder<?> holder, String station,
                                        List<java.util.Optional<Ingredient>> layout) {
        List<ItemStack> sample = layout.stream()
                .map(ingredient -> ingredient.flatMap(CompanionRecipes::firstStack).orElse(ItemStack.EMPTY)).toList();
        ItemStack result = assembleProcessing(level, holder.value(), sample);
        if (result.isEmpty()) return;
        var indexed = new Processing(holder.id().identifier().toString(), "minecraft:" + station,
                result.getItem(), result.getCount(), List.copyOf(layout), holder.value());
        processing.computeIfAbsent(result.getItem(), key -> new ArrayList<>()).add(indexed);
        processingById.put(indexed.id(), indexed);
    }

    private static ItemStack assembleProcessing(ServerLevel level, Recipe<?> recipe, List<ItemStack> slots) {
        if (recipe instanceof StonecutterRecipe stonecutter) {
            var input = new SingleRecipeInput(slots.getFirst());
            return stonecutter.matches(input, level) ? stonecutter.assemble(input) : ItemStack.EMPTY;
        }
        if (recipe instanceof SmithingTransformRecipe smithing) {
            var input = new SmithingRecipeInput(slots.get(0), slots.get(1), slots.get(2));
            return smithing.matches(input, level) ? smithing.assemble(input) : ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    public static List<Processing> processingFor(ServerLevel level, Item result) {
        ensure(level);
        return List.copyOf(processing.getOrDefault(result, List.of()));
    }

    public static Processing processingRecipe(ServerLevel level, String id) {
        ensure(level);
        return processingById.get(RegistryIds.canonical(id));
    }

    /** Die ganze Charge mit ihren echten Komponenten simulieren, bevor etwas verbraucht wird. Nichts fällt über. */
    public static ProcessingResult process(CompanionEntity mob, ServerLevel level, String recipeId, Item result, int wanted) {
        Processing recipe = processingRecipe(level, recipeId);
        if (recipe == null || recipe.result() != result || wanted < 1 || wanted > MAX_PROCESSING_BATCH)
            return ProcessingResult.INVALID_RECIPE;
        List<ItemStack> remaining = new ArrayList<>(), products = new ArrayList<>();
        for (int slot = 0; slot < mob.getInventory().getContainerSize(); slot++)
            remaining.add(mob.getInventory().getItem(slot).copy());
        int times = (wanted + recipe.count() - 1) / recipe.count();
        int produced = 0;
        int[] budget = {32768};
        for (int n = 0; n < times; n++) {
            List<ItemStack> slots = new ArrayList<>();
            if (!allocate(recipe.layout(), remaining, 0, slots, budget)) return ProcessingResult.MISSING_INPUT;
            ItemStack output = assembleProcessing(level, recipe.recipe(), slots);
            if (output.isEmpty() || !output.is(result) || output.getCount() != recipe.count())
                return ProcessingResult.INVALID_RECIPE;
            products.add(output);
            produced += output.getCount();
        }
        var inventory = new CompanionPrerequisites.Inventory(remaining, true);
        for (ItemStack output : products) if (!inventory.add(output)) return ProcessingResult.INVENTORY_FULL;
        for (int slot = 0; slot < remaining.size(); slot++) mob.getInventory().setItem(slot, inventory.stack(slot));
        mob.logInfo(EventKind.CRAFT, CompanionTexts.tr("processing.done", produced,
                new ItemStack(result).getHoverName(), CompanionTexts.item(recipe.station())));
        return ProcessingResult.SUCCESS;
    }

    private static void index(ServerLevel sl, CraftingRecipe cr) {
        List<Ingredient> ings = cr.placementInfo().ingredients();
        if (ings.isEmpty() || cr.placementInfo().isImpossibleToPlace()) return;
        int w, hgt;
        List<ItemStack> grid = new ArrayList<>();
        List<java.util.Optional<Ingredient>> layout = new ArrayList<>();
        if (cr instanceof ShapedRecipe sr) {
            w = sr.getWidth();
            hgt = sr.getHeight();
            for (var opt : sr.getIngredients()) grid.add(opt.flatMap(CompanionRecipes::firstStack).orElse(ItemStack.EMPTY));
            layout.addAll(sr.getIngredients());
        } else {
            w = ings.size();
            hgt = 1;
            for (Ingredient ing : ings) grid.add(firstStack(ing).orElse(ItemStack.EMPTY));
            for (Ingredient ing : ings) layout.add(java.util.Optional.of(ing));
            if (w > 3) { w = 3; hgt = (ings.size() + 2) / 3; while (grid.size() < w * hgt) grid.add(ItemStack.EMPTY); }
            while (layout.size() < grid.size()) layout.add(java.util.Optional.empty());
        }
        CraftingInput input = CraftingInput.of(w, hgt, grid);
        if (!cr.matches(input, sl)) return;
        ItemStack out = cr.assemble(input);
        if (out.isEmpty()) return;
        boolean table = w > 2 || hgt > 2 || ings.size() > 4;
        crafts.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(
                new Craft(ings, out.getCount(), table, cr, w, hgt, List.copyOf(layout)));
    }

    public static List<Craft> craftsFor(ServerLevel sl, Item item) {
        ensure(sl);
        return crafts.getOrDefault(item, List.of());
    }

    public static Smelt smeltFor(ServerLevel sl, Item item) {
        ensure(sl);
        return smelts.get(item);
    }

    /** Alle Ofen-Rezepte für ein Ergebnis (z.B. Eisenbarren aus Roheisen, Eisenerz, Tiefenschiefer-Eisenerz). */
    public static List<Smelt> smeltsFor(ServerLevel sl, Item item) {
        ensure(sl);
        return smeltsAll.getOrDefault(item, List.of());
    }

    /** Wie viele der Zutat-Auswahl hat er im Inventar? */
    public static int have(CompanionEntity e, Ingredient ing) {
        int n = 0;
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!s.isEmpty() && ing.test(s)) n += s.getCount();
        }
        return n;
    }

    /** Zutaten gruppiert: gleiche Auswahl zusammengezählt (z.B. 3x Bruchstein, 2x Stock). */
    public static Map<Ingredient, Integer> grouped(Craft c) {
        return grouped(c.ingredients());
    }

    public static Map<Ingredient, Integer> grouped(List<Ingredient> ingredients) {
        Map<Ingredient, Integer> m = new java.util.LinkedHashMap<>();
        outer:
        for (Ingredient ing : ingredients) {
            for (var en : m.entrySet()) if (en.getKey().equals(ing)) { en.setValue(en.getValue() + 1); continue outer; }
            m.put(ing, 1);
        }
        return m;
    }

    /** Welches Item er für eine Zutat beschaffen würde: was er schon hat, sonst die erste Möglichkeit. */
    public static Item preferred(CompanionEntity e, Ingredient ing) {
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!s.isEmpty() && ing.test(s)) return s.getItem();
        }
        return ing.items().findFirst().map(Holder::value).orElse(Items.AIR);
    }

    /**
     * Mit einem Rezept aus dem Rezeptbuch herstellen (Zutaten im Inventar, keine Rekursion). false = fehlt etwas.
     */
    public static boolean craft(CompanionEntity e, ServerLevel sl, Item result, int wanted) {
        if (tryCraft(e, sl, result, wanted)) return true;
        e.setCraftProblem(CompanionTexts.tr("craft.no_ingredients", new ItemStack(result).getHoverName()));
        return false;
    }

    /** Wie {@link #craft}, aber ohne Problemmeldung: false = kein Rezept passt zum Inventar, nichts verbraucht. */
    public static boolean tryCraft(CompanionEntity e, ServerLevel sl, Item result, int wanted) {
        return tryCraft(e, sl, result, wanted, "log.craft.recipe_book");
    }

    /** Wie oben, mit eigenem Protokollschlüssel (Anzahl, Item). */
    public static boolean tryCraft(CompanionEntity e, ServerLevel sl, Item result, int wanted, String logKey) {
        return tryCraft(e, sl, result, wanted, logKey, true, null, 0, Map.of()) > 0;
    }

    /** Lokale Vorratsarbeit liefert bis zur Zielmenge direkt in die festgelegte Lagerkiste. */
    static int craftStock(CompanionEntity e, ServerLevel sl, Item result, int missing, boolean tableAvailable,
                          Container stock, Map<Item, Integer> reserves) {
        int batch = Math.min(16, missing);
        String logKey = "log.craft.recipe_book";
        int stored = tryCraft(e, sl, result, batch, logKey, tableAvailable, stock, missing, reserves);
        if (stored == 0 && batch > 1)
            stored = tryCraft(e, sl, result, 1, logKey, tableAvailable, stock, missing, reserves);
        if (stored > 0) return stored;
        var inputs = new StockInputs(e, stock, reserves);
        var shadow = inputs.remaining;
        List<ItemStack> products = new ArrayList<>();
        Map<Item, Integer> used = new HashMap<>();
        var output = craftLocal(sl, result, tableAvailable, shadow, products, new HashSet<>(), new int[]{512}, e.work, used);
        if (output.isEmpty()) return 0;
        products.add(output);
        int originalSize = inputs.original.size();
        for (int slot = originalSize; slot < shadow.size(); slot++) {
            if (!shadow.get(slot).isEmpty()) products.add(shadow.get(slot));
        }
        return inputs.commit(products, result, missing, used);
    }

    /** Work on copies until the full chain fits. Extra intermediate products stay in the material pool. */
    private static ItemStack craftLocal(ServerLevel level, Item result, boolean table, List<ItemStack> pool,
                                        List<ItemStack> remainders, Set<Item> visiting, int[] budget,
                                        CompanionWork workBudget, Map<Item, Integer> used) {
        if (visiting.size() >= 4 || budget[0] <= 0 || !visiting.add(result)) return ItemStack.EMPTY;
        try {
            for (Craft recipe : craftsFor(level, result)) {
                if (--budget[0] < 0) break;
                if (recipe.needsTable() && !table) continue;
                List<ItemStack> work = new ArrayList<>(pool.stream().map(ItemStack::copy).toList());
                List<ItemStack> rest = new ArrayList<>();
                List<ItemStack> grid = new ArrayList<>();
                Map<Item, Integer> spent = new HashMap<>(used);
                for (var ingredient : recipe.layout()) {
                    ItemStack part = ingredient.isEmpty() ? ItemStack.EMPTY
                            : takeLocal(level, ingredient.get(), table, work, rest, visiting, budget, workBudget, spent);
                    if (ingredient.isPresent() && part.isEmpty()) break;
                    grid.add(part);
                }
                if (grid.size() != recipe.layout().size()) continue;
                CraftingInput input = CraftingInput.of(recipe.width(), recipe.height(), grid);
                if (!recipe.recipe().matches(input, level)) continue;
                ItemStack output = recipe.recipe().assemble(input);
                if (!output.is(result)) continue;
                pool.clear();
                pool.addAll(work);
                used.clear();
                used.putAll(spent);
                remainders.addAll(rest);
                for (ItemStack stack : recipe.recipe().getRemainingItems(input))
                    if (!stack.isEmpty()) remainders.add(stack.copy());
                return output;
            }
            return ItemStack.EMPTY;
        } finally {
            visiting.remove(result);
        }
    }

    private static ItemStack takeLocal(ServerLevel level, Ingredient ingredient, boolean table, List<ItemStack> pool,
                                       List<ItemStack> remainders, Set<Item> visiting, int[] budget,
                                       CompanionWork workBudget, Map<Item, Integer> used) {
        for (ItemStack stack : pool) {
            if (stack.isEmpty() || visiting.contains(stack.getItem()) || !ingredient.test(stack)) continue;
            if (takeAllowance(workBudget, used, stack.getItem())) return stack.split(1);
        }
        for (Item item : ingredient.items().map(Holder::value).toList()) {
            if (--budget[0] < 0) break;
            if (craftsFor(level, item).isEmpty() || !workBudget.permitsInput(item, used.getOrDefault(item, 0) + 1)) continue;
            ItemStack made = craftLocal(level, item, table, pool, remainders, visiting, budget, workBudget, used);
            if (made.isEmpty()) continue;
            takeAllowance(workBudget, used, item);
            ItemStack part = made.split(1);
            if (!made.isEmpty()) pool.add(made);
            return part;
        }
        return ItemStack.EMPTY;
    }

    private static boolean takeAllowance(CompanionWork budget, Map<Item, Integer> used, Item item) {
        if (budget.inputAllowance(item) == Integer.MAX_VALUE) return true;
        int count = used.getOrDefault(item, 0) + 1;
        if (!budget.permitsInput(item, count)) return false;
        used.put(item, count);
        return true;
    }

    /** A local smelt commits input, fuel and output together. Reserved home stock stays untouched. */
    static int smeltStock(CompanionEntity mob, ServerLevel level, Item item, int missing,
                          Container stock, Map<Item, Integer> reserves) {
        var inputs = new StockInputs(mob, stock, reserves);
        var shadow = inputs.remaining;
        ItemStack output = ItemStack.EMPTY;
        for (ItemStack stack : shadow) {
            if (stack.isEmpty() || stack.is(item)) continue;
            ItemStack product = smeltResult(level, stack);
            if (product.is(item)) {
                output = product;
                stack.shrink(1);
                break;
            }
        }
        if (output.isEmpty()) {
            inputs.noteBudget(stack -> smeltResult(level, stack).is(item));
            return 0;
        }
        double credit = mob.fuelLeft;
        if (credit < 1) {
            for (ItemStack stack : shadow) {
                if (stack.isEmpty() || stack.is(item)) continue;
                if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) {
                    stack.shrink(1);
                    credit += 8;
                    break;
                }
            }
        }
        if (credit < 1) {
            for (ItemStack stack : shadow) {
                if (stack.isEmpty() || stack.is(item)) continue;
                if (stack.is(net.minecraft.tags.ItemTags.PLANKS) || stack.is(net.minecraft.tags.ItemTags.LOGS)) {
                    stack.shrink(1);
                    credit += 1.5;
                    break;
                }
            }
        }
        if (credit < 1) {
            inputs.noteBudget(stack -> !stack.is(item) && (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)
                    || stack.is(net.minecraft.tags.ItemTags.PLANKS) || stack.is(net.minecraft.tags.ItemTags.LOGS)));
            return 0;
        }
        int stored = inputs.commit(List.of(output), item, missing, null);
        if (stored > 0) mob.fuelLeft = credit - 1;
        return stored;
    }

    private static int tryCraft(CompanionEntity e, ServerLevel sl, Item result, int wanted, String logKey,
                                boolean tableAvailable, Container stock, int stockLimit, Map<Item, Integer> reserves) {
        var inventory = e.getInventory();
        int carriedSlots = inventory.getContainerSize();
        for (Craft c : craftsFor(sl, result)) {
            if (c.needsTable() && !tableAvailable) continue;
            int times = (Math.max(1, wanted) + c.count() - 1) / c.count();
            StockInputs inputs = stock == null ? null : new StockInputs(e, stock, reserves);
            List<ItemStack> remaining = inputs == null ? new ArrayList<>() : inputs.remaining;
            List<ItemStack> products = new ArrayList<>();
            if (inputs == null) for (int slot = 0; slot < carriedSlots; slot++) remaining.add(inventory.getItem(slot).copy());
            // Bei der Vorratsarbeit bleiben Exemplare des Ziel-Items für die Auffüllung reserviert.
            List<ItemStack> ingredients = stock == null ? remaining : remaining.stream().filter(stack -> !stack.is(result)).toList();
            boolean ok = true;
            for (int n = 0; n < times; n++) {
                List<ItemStack> grid = new ArrayList<>();
                if (!allocate(c.layout(), ingredients, 0, grid, new int[]{4096})) {
                    if (inputs != null) inputs.noteBudget(stack -> !stack.is(result)
                            && c.ingredients().stream().anyMatch(ingredient -> ingredient.test(stack)));
                    ok = false;
                    break;
                }
                CraftingInput input = CraftingInput.of(c.width(), c.height(), grid);
                if (!c.recipe().matches(input, sl)) { ok = false; break; }
                ItemStack output = c.recipe().assemble(input);
                if (output.isEmpty() || !output.is(result)) { ok = false; break; }
                products.add(output);
                for (ItemStack remainder : c.recipe().getRemainingItems(input))
                    if (!remainder.isEmpty()) products.add(remainder.copy());
            }
            if (!ok) continue;
            int crafted = c.count() * times;
            int stored = 0;
            if (stock != null) {
                stored = inputs.commit(products, result, stockLimit, null);
                if (stored == 0) continue;
            } else {
                for (int slot = 0; slot < carriedSlots; slot++) inventory.setItem(slot, remaining.get(slot));
                for (ItemStack product : products) {
                    ItemStack rest = inventory.addItem(product);
                    if (!rest.isEmpty()) e.spawnAtLocation(sl, rest);
                }
            }
            e.logInfo(EventKind.CRAFT, CompanionTexts.tr(logKey, crafted, new ItemStack(result).getHoverName()));
            return stock == null ? crafted : stored;
        }
        return 0;
    }

    /** Stage one kitchen transaction with a shared budget across the pack and the approved storage. */
    private static final class StockInputs {
        final CompanionEntity mob;
        final Container stock;
        final List<ItemStack> original = new ArrayList<>(), remaining = new ArrayList<>();
        final int[] hidden;
        final Set<Item> limited = new HashSet<>();

        StockInputs(CompanionEntity mob, Container stock, Map<Item, Integer> reserves) {
            this.mob = mob;
            this.stock = stock;
            Container inventory = mob.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) original.add(inventory.getItem(slot).copy());
            for (int slot = 0; slot < stock.getContainerSize(); slot++) original.add(stock.getItem(slot).copy());
            for (var stack : original) remaining.add(stack.copy());
            hidden = new int[original.size()];
            Map<Item, Integer> unassigned = new HashMap<>(reserves);
            for (int i = inventory.getContainerSize(); i < original.size(); i++) {
                ItemStack stack = remaining.get(i);
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                int reserve = unassigned.getOrDefault(item, 0);
                int held = Math.min(reserve, stack.getCount());
                hide(i, held);
                unassigned.put(item, reserve - held);
            }
            if (!mob.work.hasInputBudgets()) return;
            Map<Item, Integer> allowances = new HashMap<>();
            for (int i = 0; i < original.size(); i++) {
                var stack = remaining.get(i);
                if (stack.isEmpty()) continue;
                Item item = stack.getItem();
                int allowance = allowances.getOrDefault(item, mob.work.inputAllowance(item));
                if (allowance == Integer.MAX_VALUE) continue;
                int usable = Math.min(stack.getCount(), allowance);
                if (usable < stack.getCount()) limited.add(item);
                hide(i, stack.getCount() - usable);
                allowances.put(item, allowance - usable);
            }
        }

        private void hide(int slot, int count) {
            hidden[slot] += count;
            if (count > 0) remaining.get(slot).shrink(count);
        }

        void noteBudget(java.util.function.Predicate<ItemStack> ingredient) {
            for (Item item : limited) if (ingredient.test(item.getDefaultInstance()))
                mob.work.permitsInput(item, mob.work.inputAllowance(item) + 1);
        }

        int commit(List<ItemStack> products, Item result, int limit, Map<Item, Integer> recursiveCosts) {
            Map<Item, Integer> used = recursiveCosts == null ? new HashMap<>() : recursiveCosts;
            for (int i = 0; i < original.size(); i++) {
                ItemStack before = original.get(i);
                int count = remaining.get(i).getCount() + hidden[i];
                if (recursiveCosts == null && mob.work.hasInputBudgets() && before.getCount() > count)
                    used.merge(before.getItem(), before.getCount() - count, Integer::sum);
                if (hidden[i] > 0) remaining.set(i, before.copyWithCount(count));
            }
            int stored = storeCrafted(mob.getInventory(), stock, remaining.subList(0, original.size()), products, result, limit);
            if (stored > 0) used.forEach(mob.work::spent);
            return stored;
        }
    }

    /** Freigewordene Kistenplätze mitrechnen und alle Überschüsse und Rezeptreste sicher unterbringen. */
    private static int storeCrafted(Container inventory, Container stock, List<ItemStack> remaining,
                                    List<ItemStack> products, Item result, int stockLimit) {
        int carriedSlots = inventory.getContainerSize();
        List<ItemStack> storage = remaining.subList(carriedSlots, remaining.size());
        var pack = new CompanionPrerequisites.Inventory(remaining.subList(0, carriedSlots), true);
        int stored = 0;
        for (ItemStack product : products) {
            ItemStack rest = product.copy();
            if (rest.is(result) && stored < stockLimit) {
                int count = Math.min(rest.getCount(), stockLimit - stored);
                int moved = putInStock(stock, storage, rest.copyWithCount(count));
                stored += moved;
                rest.shrink(moved);
            }
            if (!rest.isEmpty() && !pack.add(rest)) return 0;
        }
        if (stored == 0) return 0;

        // Bis hierhin wurde nur auf Kopien gearbeitet. Erst jetzt beide Inventare aktualisieren.
        for (int slot = 0; slot < carriedSlots; slot++) {
            ItemStack updated = pack.stack(slot);
            if (!ItemStack.matches(inventory.getItem(slot), updated)) inventory.setItem(slot, updated);
        }
        for (int slot = 0; slot < storage.size(); slot++) {
            ItemStack updated = storage.get(slot);
            if (!ItemStack.matches(stock.getItem(slot), updated)) stock.setItem(slot, updated);
        }
        stock.setChanged();
        return stored;
    }

    private static int putInStock(Container stock, List<ItemStack> storage, ItemStack rest) {
        int amount = rest.getCount();
        int limit = stock.getMaxStackSize(rest);
        for (int slot = 0; slot < storage.size() && !rest.isEmpty(); slot++) {
            ItemStack current = storage.get(slot);
            if (!ItemStack.isSameItemSameComponents(current, rest) || !stock.canPlaceItem(slot, rest)) continue;
            int moved = Math.min(rest.getCount(), Math.max(0, limit - current.getCount()));
            current.grow(moved);
            rest.shrink(moved);
        }
        for (int slot = 0; slot < storage.size() && !rest.isEmpty(); slot++) {
            if (storage.get(slot).isEmpty() && stock.canPlaceItem(slot, rest)) storage.set(slot, rest.split(limit));
        }
        return amount - rest.getCount();
    }

    /**
     * Ergebnis des echten Ofenrezepts für genau diesen Stapel (Komponenten zählen mit), leer = keins. Liest den
     * Rezeptmanager der Welt, damit Datapacks und /reload gelten.
     */
    public static ItemStack smeltResult(ServerLevel sl, ItemStack input) {
        if (input.isEmpty()) return ItemStack.EMPTY;
        var single = new SingleRecipeInput(input.copyWithCount(1));
        return sl.recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMELTING, single, sl)
                .map(h -> h.value().assemble(single)).filter(s -> !s.isEmpty()).orElse(ItemStack.EMPTY);
    }

    /** Zutaten belegen echte Rasterplätze; dieselbe Einheit kann nicht zwei Zutaten bezahlen. */
    private static boolean allocate(List<java.util.Optional<Ingredient>> layout, List<ItemStack> inventory,
                                    int index, List<ItemStack> grid, int[] budget) {
        if (index == layout.size()) return true;
        if (--budget[0] < 0) return false;
        var ingredient = layout.get(index);
        if (ingredient.isEmpty()) {
            grid.add(ItemStack.EMPTY);
            if (allocate(layout, inventory, index + 1, grid, budget)) return true;
            grid.removeLast();
            return false;
        }
        List<ItemStack> tried = new ArrayList<>();
        for (ItemStack stack : inventory) {
            if (stack.isEmpty() || !ingredient.get().test(stack)
                    || tried.stream().anyMatch(s -> ItemStack.isSameItemSameComponents(s, stack))) continue;
            ItemStack one = stack.copyWithCount(1);
            tried.add(one);
            grid.add(one);
            stack.shrink(1);
            if (allocate(layout, inventory, index + 1, grid, budget)) return true;
            stack.grow(1);
            grid.removeLast();
        }
        return false;
    }
}
