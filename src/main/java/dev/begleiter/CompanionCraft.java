package dev.begleiter;

import dev.begleiter.debug.BotDebug.EventKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Vereinfachtes Craften und Schmelzen des Begleiters: braucht keine Werkbank/keinen Ofen im Weltraum,
 * dauert aber ein paar Ticks und verbraucht echtes Material und Brennstoff.
 */
public final class CompanionCraft {
    private CompanionCraft() {}

    private record Ing(String id, int n) {}
    private record Recipe(int count, List<Ing> ing) {}

    private static final Map<Item, Recipe> RECIPES = new HashMap<>();

    private static void reg(String result, int count, Object... ing) {
        List<Ing> l = new ArrayList<>();
        for (int i = 0; i < ing.length; i += 2) l.add(new Ing((String) ing[i], (Integer) ing[i + 1]));
        RECIPES.put(item(result), new Recipe(count, l));
    }

    static {
        reg("stick", 4, "#planks", 2);
        reg("crafting_table", 1, "#planks", 4);
        reg("torch", 4, "#coals", 1, "stick", 1);
        for (String t : new String[]{"wooden", "stone", "iron", "diamond"}) {
            String m = t.equals("wooden") ? "#planks" : t.equals("stone") ? "#stone" : t.equals("iron") ? "iron_ingot" : "diamond";
            reg(t + "_pickaxe", 1, m, 3, "stick", 2);
            reg(t + "_axe", 1, m, 3, "stick", 2);
            reg(t + "_shovel", 1, m, 1, "stick", 2);
            reg(t + "_sword", 1, m, 2, "stick", 1);
        }
        reg("furnace", 1, "#stone", 8);
        reg("chest", 1, "#planks", 8);
        reg("oak_door", 3, "#planks", 6); // jede Bretter-Sorte reicht für eine Tür
        reg("iron_helmet", 1, "iron_ingot", 5);
        reg("iron_chestplate", 1, "iron_ingot", 8);
        reg("iron_leggings", 1, "iron_ingot", 7);
        reg("iron_boots", 1, "iron_ingot", 4);
        reg("shield", 1, "#planks", 6, "iron_ingot", 1);
        reg("bucket", 1, "iron_ingot", 3);
        reg("flint_and_steel", 1, "iron_ingot", 1, "flint", 1);
        reg("blaze_powder", 2, "blaze_rod", 1);
        reg("ender_eye", 1, "ender_pearl", 1, "blaze_powder", 1);
        reg("bow", 1, "stick", 3, "string", 3);
        reg("arrow", 4, "flint", 1, "stick", 1, "feather", 1);
    }

    private static final Map<Item, String> SMELT = new HashMap<>();
    static {
        SMELT.put(Items.RAW_IRON, "iron_ingot");
        SMELT.put(Items.RAW_GOLD, "gold_ingot");
        SMELT.put(Items.RAW_COPPER, "copper_ingot");
        SMELT.put(Items.BEEF, "cooked_beef");
        SMELT.put(Items.PORKCHOP, "cooked_porkchop");
        SMELT.put(Items.CHICKEN, "cooked_chicken");
        SMELT.put(Items.MUTTON, "cooked_mutton");
        SMELT.put(Items.RABBIT, "cooked_rabbit");
        SMELT.put(Items.COD, "cooked_cod");
        SMELT.put(Items.SALMON, "cooked_salmon");
    }

    /** Nur Stock, Bretter, Werkbank, Fackeln und Feuerzeug passen ins 2x2-Raster des Spieler-Inventars. */
    public static boolean needsTable(String id) {
        Item result = item(id);
        return !(result == Items.STICK || result == Items.CRAFTING_TABLE || result == Items.TORCH || result == Items.FLINT_AND_STEEL
                || result == Items.BLAZE_POWDER || result == Items.ENDER_EYE);
    }

    /** Wie needsTable, für Rezepte aus dem Rezeptbuch von Minecraft (kleinste passende Variante). */
    public static boolean needsTable(CompanionEntity e, String id) {
        if (RECIPES.containsKey(item(id)) || !(e.level() instanceof net.minecraft.server.level.ServerLevel sl)) return needsTable(id);
        var list = CompanionRecipes.craftsFor(sl, item(id));
        return list.isEmpty() || list.stream().allMatch(CompanionRecipes.Craft::needsTable);
    }

    public static Item item(String id) {
        return ItemIds.resolve(id);
    }

    private static boolean matches(String ing, ItemStack s) {
        if (s.isEmpty()) return false;
        return switch (ing) {
            case "#planks" -> s.is(ItemTags.PLANKS);
            case "#logs" -> s.is(ItemTags.LOGS);
            case "#stone" -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.BLACKSTONE);
            case "#coals" -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
            default -> ItemIds.matches(s, ing);
        };
    }

    public static int count(CompanionEntity e, String ing) {
        int n = 0;
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (matches(ing, s)) n += s.getCount();
        }
        return n;
    }

    private static void remove(CompanionEntity e, String ing, int amount) {
        for (int i = 0; i < e.getInventory().getContainerSize() && amount > 0; i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!matches(ing, s)) continue;
            int take = Math.min(amount, s.getCount());
            s.shrink(take);
            amount -= take;
        }
    }

    private static void give(CompanionEntity e, ItemStack stack) {
        // In normalen Stapeln: ein Stapel über der Höchstmenge (z.B. 256 Bretter aus 64 Stämmen) wird sonst auf 64
        // gekappt und der Rest ist weg
        while (!stack.isEmpty()) {
            ItemStack part = stack.split(stack.getMaxStackSize());
            ItemStack rest = e.getInventory().addItem(part);
            if (!rest.isEmpty() && e.level() instanceof net.minecraft.server.level.ServerLevel sl) e.spawnAtLocation(sl, rest);
        }
    }

    /** Logs -> Bretter (4 pro Stamm). Gibt die erzeugte Brettermenge zurück. */
    public static int convertLogsToPlanks(CompanionEntity e, int maxPlanks) {
        int made = 0;
        for (int i = 0; i < e.getInventory().getContainerSize() && made < maxPlanks; i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (s.isEmpty() || !s.is(ItemTags.LOGS)) continue;
            Identifier logId = BuiltInRegistries.ITEM.getKey(s.getItem());
            String wood = logId.getPath().replace("stripped_", "").replace("_log", "").replace("_wood", "")
                    .replace("_stem", "").replace("_hyphae", "");
            Item planks = item(Identifier.fromNamespaceAndPath(logId.getNamespace(), wood + "_planks").toString());
            if (planks == Items.AIR) continue;
            int logs = Math.min(s.getCount(), (int) Math.ceil((maxPlanks - made) / 4.0));
            s.shrink(logs);
            give(e, new ItemStack(planks, logs * 4));
            made += logs * 4;
        }
        return made;
    }

    private static void ensure(CompanionEntity e, String id, int need) {
        int have = count(e, id);
        if (have >= need) return;
        if (id.equals("#planks")) convertLogsToPlanks(e, need - have);
        else if (id.equals("stick")) craft(e, "stick", need - have);
        else if (id.equals("blaze_powder") && count(e, "blaze_rod") > 0) craft(e, "blaze_powder", need - have);
    }

    public static boolean craft(CompanionEntity e, String id, int wanted) {
        Item result = item(id);
        Recipe r = RECIPES.get(result);
        if (r == null && result != Items.AIR && e.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            return CompanionRecipes.craft(e, sl, result, wanted); // alle übrigen Rezepte aus dem Rezeptbuch
        }
        if (r == null || result == Items.AIR) {
            e.setCraftProblem(CompanionTexts.tr("log.craft.unknown", CompanionTexts.literal(id)));
            e.logWarn(e.craftProblemComponent());
            return false;
        }
        int times = (int) Math.ceil(wanted / (double) r.count());
        for (Ing ing : r.ing()) ensure(e, ing.id(), ing.n() * times);
        // Stöcke entstehen aus Brettern und können schon bereitgelegte Bretter verbraucht haben: nachfüllen, erst dann prüfen
        for (Ing ing : r.ing()) ensure(e, ing.id(), ing.n() * times);
        for (Ing ing : r.ing()) {
            int have = count(e, ing.id());
            if (have < ing.n() * times) {
                e.setCraftProblem(CompanionTexts.tr("craft.missing_ingredient", CompanionTexts.craftIngredient(ing.id()), have, ing.n() * times));
                e.logWarn(CompanionTexts.tr("log.craft.failed", new ItemStack(result).getHoverName(), e.craftProblemComponent()));
                return false;
            }
        }
        for (Ing ing : r.ing()) remove(e, ing.id(), ing.n() * times);
        give(e, new ItemStack(result, times * r.count()));
        e.logInfo(EventKind.CRAFT, CompanionTexts.tr("log.craft.made", times * r.count(), new ItemStack(result).getHoverName()));
        return true;
    }

    // ------------------------------------------------------------------ Besitz-Abfragen

    public static boolean owns(CompanionEntity e, String itemId) {
        for (int i = 0; i < e.getInventory().getContainerSize(); i++)
            if (ItemIds.matches(e.getInventory().getItem(i), itemId)) return true;
        for (EquipmentSlot slot : EquipmentSlot.values())
            if (ItemIds.matches(e.getItemBySlot(slot), itemId)) return true;
        return false;
    }

    /** 0 = keins, 1 = Holz/Gold, 2 = Stein/Kupfer, 3 = Eisen, 4 = Diamant, 5 = Netherit. */
    public static int tier(CompanionEntity e, String kind) {
        String[] prefix = {"wooden_", "golden_", "stone_", "copper_", "iron_", "diamond_", "netherite_"};
        int[] tiers = {1, 1, 2, 2, 3, 4, 5};
        int best = 0;
        for (int k = 0; k < prefix.length; k++)
            if (owns(e, prefix[k] + kind)) best = Math.max(best, tiers[k]);
        return best;
    }

    /** Das beste Werkzeug dieser Art (Inventar oder in der Hand), leer wenn keins. */
    public static ItemStack bestTool(CompanionEntity e, String kind) {
        String[] prefix = {"wooden_", "golden_", "stone_", "copper_", "iron_", "diamond_", "netherite_"};
        int[] tiers = {1, 1, 2, 2, 3, 4, 5};
        ItemStack best = ItemStack.EMPTY;
        int bestTier = 0;
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) all.add(e.getInventory().getItem(i));
        for (EquipmentSlot slot : EquipmentSlot.values()) all.add(e.getItemBySlot(slot));
        for (ItemStack s : all) {
            for (int k = 0; k < prefix.length; k++)
                if (ItemIds.matches(s, prefix[k] + kind) && tiers[k] > bestTier) { bestTier = tiers[k]; best = s; }
        }
        return best;
    }

    /** Wie viele Exemplare dieses Items er hat (Inventar und Hände/Rüstung). */
    public static int countAll(CompanionEntity e, String itemId) {
        int n = count(e, itemId);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = e.getItemBySlot(slot);
            if (ItemIds.matches(stack, itemId)) n += stack.getCount();
        }
        return n;
    }

    // ------------------------------------------------------------------ Schmelzen

    /** Rohes Fleisch/Fisch, das sich braten lässt. */
    public static int rawFood(CompanionEntity e) {
        int n = 0;
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            String out = s.isEmpty() ? null : SMELT.get(s.getItem());
            if (out != null && out.startsWith("cooked_")) n += s.getCount();
        }
        return n;
    }

    public static boolean hasSmeltable(CompanionEntity e) {
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!s.isEmpty() && SMELT.containsKey(s.getItem())) return true;
        }
        return false;
    }

    /** Ein Item gezielt schmelzen (Rezept aus dem Rezeptbuch). */
    public static boolean smeltTarget(CompanionEntity e, Item target, net.minecraft.world.item.crafting.Ingredient input) {
        if (findInput(e, input) == null) return false;
        // Erst den Brennstoff nehmen, dann die Zutat suchen: Stämme sind beides, und der letzte Stamm darf nicht
        // verbrannt und zugleich geschmolzen werden.
        if (!takeFuel(e)) return false;
        ItemStack s = findInput(e, input);
        if (s == null) {
            e.fuelLeft += 1; // Brennwert bleibt für den nächsten Versuch erhalten
            return false;
        }
        s.shrink(1);
        give(e, new ItemStack(target, 1));
        return true;
    }

    private static ItemStack findInput(CompanionEntity e, net.minecraft.world.item.crafting.Ingredient input) {
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!s.isEmpty() && input.test(s)) return s;
        }
        return null;
    }

    public static boolean hasFuel(CompanionEntity e) {
        return e.fuelLeft >= 1 || count(e, "#coals") > 0 || count(e, "#planks") > 0 || count(e, "#logs") > 0;
    }

    public static boolean canSmelt(CompanionEntity e) {
        return hasSmeltable(e) && hasFuel(e);
    }

    private static boolean takeFuel(CompanionEntity e) {
        if (e.fuelLeft < 1) {
            if (count(e, "#coals") > 0) { remove(e, "#coals", 1); e.fuelLeft += 8; }
            else if (count(e, "#planks") > 0) { remove(e, "#planks", 1); e.fuelLeft += 1.5; }
            else if (count(e, "#logs") > 0) { remove(e, "#logs", 1); e.fuelLeft += 1.5; }
            else return false;
        }
        e.fuelLeft -= 1;
        return true;
    }

    /** Schmilzt bis zu max Items; gibt die Anzahl zurück. */
    public static int smeltStep(CompanionEntity e, int max) {
        int done = 0;
        for (int i = 0; i < e.getInventory().getContainerSize() && done < max; i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            String out = SMELT.get(s.getItem());
            if (out == null) continue;
            while (!s.isEmpty() && done < max) {
                if (!takeFuel(e)) return done;
                s.shrink(1);
                give(e, new ItemStack(item(out), 1));
                done++;
            }
        }
        return done;
    }
}
