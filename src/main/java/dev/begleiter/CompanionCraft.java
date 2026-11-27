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
        reg("fishing_rod", 1, "stick", 3, "string", 2);
        reg("arrow", 4, "flint", 1, "stick", 1, "feather", 1);
    }

    /**
     * Was er ungefragt schmilzt (Roherze, rohes Fleisch/Fisch). Per Datapack erweiterbar; das Ergebnis kommt immer aus
     * dem echten Ofenrezept, ohne passendes Rezept bleibt der Stapel liegen.
     */
    public static final net.minecraft.tags.TagKey<Item> AUTO_SMELT = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.ITEM, CompanionMod.id("auto_smelt"));

    /** Ofenergebnis eines Stapels, den er von selbst schmelzen würde; leer = nicht (Tag oder Rezept fehlt). */
    static ItemStack autoSmeltResult(CompanionEntity e, ItemStack s) {
        if (s.isEmpty() || !s.is(AUTO_SMELT) || !(e.level() instanceof net.minecraft.server.level.ServerLevel sl))
            return ItemStack.EMPTY;
        return CompanionRecipes.smeltResult(sl, s);
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

    /** Zutat aus dem Inventar entfernen (z.B. beim Brauen). */
    public static void consume(CompanionEntity e, String ing, int amount) { remove(e, ing, amount); }

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
            Item planks = planksOf(s.getItem());
            if (planks == Items.AIR) continue;
            int logs = Math.min(s.getCount(), (int) Math.ceil((maxPlanks - made) / 4.0));
            s.shrink(logs);
            give(e, new ItemStack(planks, logs * 4));
            made += logs * 4;
        }
        return made;
    }

    private static int plankNeed(Recipe r) {
        int n = 0;
        for (Ing ing : r.ing()) if (ing.id().equals("#planks")) n += ing.n();
        return n;
    }

    /** Bretter, die ein Stamm ergibt (gleicher Namensraum, Holzname ohne Stamm-/Entrindet-Zusatz), oder AIR. */
    static Item planksOf(Item log) {
        Identifier logId = BuiltInRegistries.ITEM.getKey(log);
        String wood = logId.getPath().replace("stripped_", "").replace("_log", "").replace("_wood", "")
                .replace("_stem", "").replace("_hyphae", "");
        return item(Identifier.fromNamespaceAndPath(logId.getNamespace(), wood + "_planks").toString());
    }

    /**
     * Sorgt dafür, dass eine einzige Brettersorte mindestens {@code need} Stück hat, indem nur Stämme genau dieser
     * Sorte umgewandelt werden. Gewählt wird die Sorte mit dem meisten Holz (Bretter + 4 je Stamm). Reicht keine
     * Sorte, bleibt alles unverändert; das Herstellen meldet dann das Fehlen.
     */
    static void topUpSingleWood(CompanionEntity e, int need) {
        if (need <= 0) return;
        Map<Item, Integer> wood = new HashMap<>(), planks = new HashMap<>();
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            if (s.is(ItemTags.PLANKS)) {
                planks.merge(s.getItem(), s.getCount(), Integer::sum);
                wood.merge(s.getItem(), s.getCount(), Integer::sum);
            } else if (s.is(ItemTags.LOGS)) {
                Item p = planksOf(s.getItem());
                if (p != Items.AIR) wood.merge(p, s.getCount() * 4, Integer::sum);
            }
        }
        Item best = null;
        for (var en : wood.entrySet())
            if (planks.getOrDefault(en.getKey(), 0) >= need) return; // eine Sorte reicht schon
            else if (en.getValue() >= need && (best == null || en.getValue() > wood.get(best))) best = en.getKey();
        if (best == null) return;
        int missing = need - planks.getOrDefault(best, 0);
        for (int i = 0; i < e.getInventory().getContainerSize() && missing > 0; i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (s.isEmpty() || !s.is(ItemTags.LOGS) || planksOf(s.getItem()) != best) continue;
            int logs = Math.min(s.getCount(), (missing + 3) / 4);
            s.shrink(logs);
            give(e, new ItemStack(best, logs * 4));
            missing -= logs * 4;
        }
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
        // Die feste Liste bereitet nur vor (Bretter, Stöcke); hergestellt wird mit dem echten Rezept der Welt, damit
        // Zutaten, Ergebnis und Restgegenstände genau so gebucht werden wie an der Werkbank.
        if (!(e.level() instanceof net.minecraft.server.level.ServerLevel sl)) return false;
        // Austauschbare Holzgruppen (Türen) verlangen eine einzige Brettersorte: gemischte Bretter reichen dafür nicht,
        // also die Sorte mit dem meisten Holz aus ihren eigenen Stämmen auffüllen
        if (!variants(result).isEmpty()) topUpSingleWood(e, plankNeed(r) * times);
        if (CompanionRecipes.tryCraft(e, sl, result, wanted, "log.craft.made")) return true;
        for (Item variant : variants(result))
            if (CompanionRecipes.tryCraft(e, sl, variant, wanted, "log.craft.made")) return true;
        e.setCraftProblem(CompanionTexts.tr("craft.no_ingredients", new ItemStack(result).getHoverName()));
        e.logWarn(CompanionTexts.tr("log.craft.failed", new ItemStack(result).getHoverName(), e.craftProblemComponent()));
        return false;
    }

    /**
     * Gleichwertige Varianten, wenn das genannte Item nicht passt: Eine Tür ist eine Tür, egal aus welchem Holz. Nur
     * für ausdrücklich austauschbare Gruppen, nie still ein anderes Material für beliebige Rezepte.
     */
    static List<Item> variants(Item result) {
        if (!result.builtInRegistryHolder().is(ItemTags.WOODEN_DOORS)) return List.of();
        List<Item> out = new ArrayList<>();
        for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.WOODEN_DOORS))
            if (holder.value() != result) out.add(holder.value());
        return out;
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
    /** Beste Stufe (0 = keins) eines Werkzeugs dieser Art, siehe {@link #toolTier}. */
    public static int tier(CompanionEntity e, String kind) {
        int best = 0;
        for (ItemStack s : ownedStacks(e)) best = Math.max(best, toolTier(s, kind));
        return best;
    }

    /** Das beste Werkzeug dieser Art (Inventar oder in der Hand), leer wenn keins. */
    public static ItemStack bestTool(CompanionEntity e, String kind) {
        ItemStack best = ItemStack.EMPTY;
        int bestTier = 0;
        for (ItemStack s : ownedStacks(e)) {
            int t = toolTier(s, kind);
            if (t > bestTier) { bestTier = t; best = s; }
        }
        return best;
    }

    private static List<ItemStack> ownedStacks(CompanionEntity e) {
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) all.add(e.getInventory().getItem(i));
        for (EquipmentSlot slot : EquipmentSlot.values()) all.add(e.getItemBySlot(slot));
        return all;
    }

    /** Werkzeugart -> Item-Tag (auch Modwerkzeuge zählen, wenn sie im passenden Tag stehen). */
    static net.minecraft.tags.TagKey<Item> toolTag(String kind) {
        return switch (kind) {
            case "pickaxe" -> ItemTags.PICKAXES;
            case "axe" -> ItemTags.AXES;
            case "shovel" -> ItemTags.SHOVELS;
            case "hoe" -> ItemTags.HOES;
            case "sword" -> ItemTags.SWORDS;
            default -> null;
        };
    }

    /** Prüfblöcke der Spitzhacke: Stufe = höchster Block, dessen Abbau Beute liefert. */
    private static final net.minecraft.world.level.block.Block[] PICK_CHECKS = {
            net.minecraft.world.level.block.Blocks.STONE, net.minecraft.world.level.block.Blocks.IRON_ORE,
            net.minecraft.world.level.block.Blocks.DIAMOND_ORE, net.minecraft.world.level.block.Blocks.OBSIDIAN};

    /**
     * Stufe 1–5 nach Fähigkeit statt Name (0 = nicht diese Werkzeugart). Spitzhacke: was sie mit Beute abbauen kann
     * (Stein 1, Eisenerz 2, Diamanterz 3, Obsidian 4); unter den Obsidian-tauglichen gilt eine haltbarere als 5
     * (Netherit). Andere Werkzeuge und Schwerter nach Haltbarkeit, nicht nach Erzfähigkeit; die Grenzen liegen zwischen
     * den Vanilla-Materialien (Holz/Gold &lt; Stein/Kupfer &lt; Eisen &lt; Diamant &lt; Netherit), damit schnelles,
     * zerbrechliches Gold nicht über Eisen steht.
     */
    public static int toolTier(ItemStack s, String kind) {
        var tag = toolTag(kind);
        if (s.isEmpty() || tag == null || !s.is(tag)) return 0;
        int durability = s.getMaxDamage();
        if ("pickaxe".equals(kind)) {
            int tier = 0;
            for (int i = 0; i < PICK_CHECKS.length; i++)
                if (s.isCorrectToolForDrops(PICK_CHECKS[i].defaultBlockState())) tier = i + 1;
            if (tier == 4 && durability > Items.DIAMOND_PICKAXE.getDefaultInstance().getMaxDamage()) tier = 5;
            return Math.max(1, tier);
        }
        return durability < 100 ? 1 : durability < 220 ? 2 : durability < 1000 ? 3 : durability < 1800 ? 4 : 5;
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
            if (autoSmeltResult(e, s).has(net.minecraft.core.component.DataComponents.FOOD)) n += s.getCount();
        }
        return n;
    }

    public static boolean hasSmeltable(CompanionEntity e) {
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!autoSmeltResult(e, s).isEmpty()) return true;
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
            String preferred = e.orders.preferredOrderFuel();
            if (preferred != null && count(e, preferred) > 0) { remove(e, preferred, 1); e.fuelLeft += 8; }
            else if (count(e, "coal") > 0) { remove(e, "coal", 1); e.fuelLeft += 8; }
            else if (count(e, "charcoal") > 0) { remove(e, "charcoal", 1); e.fuelLeft += 8; }
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
            ItemStack out = autoSmeltResult(e, s);
            if (out.isEmpty()) continue;
            while (!s.isEmpty() && done < max) {
                if (!takeFuel(e)) return done;
                s.shrink(1);
                give(e, out.copy());
                done++;
            }
        }
        return done;
    }
}
