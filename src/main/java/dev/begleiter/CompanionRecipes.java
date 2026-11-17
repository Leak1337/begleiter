package dev.begleiter;

import dev.begleiter.debug.BotDebug.EventKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
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
        for (Craft c : craftsFor(sl, result)) {
            int times = (Math.max(1, wanted) + c.count() - 1) / c.count();
            List<ItemStack> remaining = new ArrayList<>(), products = new ArrayList<>();
            for (int slot = 0; slot < e.getInventory().getContainerSize(); slot++)
                remaining.add(e.getInventory().getItem(slot).copy());
            boolean ok = true;
            for (int n = 0; n < times; n++) {
                List<ItemStack> grid = new ArrayList<>();
                if (!allocate(c.layout(), remaining, 0, grid, new int[]{4096})) { ok = false; break; }
                CraftingInput input = CraftingInput.of(c.width(), c.height(), grid);
                if (!c.recipe().matches(input, sl)) { ok = false; break; }
                ItemStack output = c.recipe().assemble(input);
                if (output.isEmpty() || !output.is(result)) { ok = false; break; }
                products.add(output);
                for (ItemStack remainder : c.recipe().getRemainingItems(input))
                    if (!remainder.isEmpty()) products.add(remainder.copy());
            }
            if (!ok) continue;
            // Erst nach erfolgreicher Simulation der gesamten Charge Zutaten buchen.
            for (int slot = 0; slot < remaining.size(); slot++) e.getInventory().setItem(slot, remaining.get(slot));
            for (ItemStack product : products) {
                ItemStack rest = e.getInventory().addItem(product);
                if (!rest.isEmpty()) e.spawnAtLocation(sl, rest);
            }
            e.logInfo(EventKind.CRAFT, CompanionTexts.tr("log.craft.recipe_book", c.count() * times, new ItemStack(result).getHoverName()));
            return true;
        }
        e.setCraftProblem(CompanionTexts.tr("craft.no_ingredients", new ItemStack(result).getHoverName()));
        return false;
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
