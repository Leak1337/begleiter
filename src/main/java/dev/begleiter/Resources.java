package dev.begleiter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Wörterbuch: deutsche Wörter -> Blöcke/Items/Mobs, die der Begleiter versteht. */
public final class Resources {
    private Resources() {}

    public record Res(String key, String[] nouns,
                      Predicate<BlockState> block, Predicate<ItemStack> item) {}

    public static final List<Res> ALL = new ArrayList<>();
    public static final Res WOOD, STONE, COAL, IRON, GOLD, DIAMOND, REDSTONE, LAPIS, COPPER, EMERALD, SAND, GRAVEL, DIRT,
            OBSIDIAN, SEEDS;

    public static String id(ItemStack s) {
        return ItemIds.of(s);
    }

    private static Predicate<ItemStack> ids(String... ids) {
        Set<String> set = java.util.Arrays.stream(ids).map(ItemIds::canonical)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return s -> !s.isEmpty() && set.contains(ItemIds.of(s));
    }

    private static boolean ore(BlockState s, String name) {
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        return id.equals("minecraft:" + name + "_ore") || id.equals("minecraft:deepslate_" + name + "_ore");
    }

    private static Res add(String key, String[] nouns, Predicate<BlockState> b, Predicate<ItemStack> i) {
        Res r = new Res(key, nouns, b, i);
        ALL.add(r);
        return r;
    }

    static {
        WOOD = add("holz", new String[]{"holz", "baum", "stamm", "staemm", "log"},
                s -> s.is(BlockTags.LOGS), s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS));
        STONE = add("stein", new String[]{"stein", "cobble", "bruchstein"},
                s -> s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.DEEPSLATE),
                ids("cobblestone", "cobbled_deepslate", "stone"));
        COAL = add("kohle", new String[]{"kohle"}, s -> ore(s, "coal"), ids("coal", "charcoal"));
        IRON = add("eisen", new String[]{"eisen"}, s -> ore(s, "iron"),
                ids("raw_iron", "iron_ingot", "iron_block"));
        GOLD = add("gold", new String[]{"gold"}, s -> ore(s, "gold"),
                ids("raw_gold", "gold_ingot", "gold_block"));
        DIAMOND = add("diamant", new String[]{"diamant"}, s -> ore(s, "diamond"), ids("diamond"));
        REDSTONE = add("redstone", new String[]{"redstone", "rotstein"},
                s -> ore(s, "redstone"), ids("redstone"));
        LAPIS = add("lapis", new String[]{"lapis"}, s -> ore(s, "lapis"), ids("lapis_lazuli"));
        COPPER = add("kupfer", new String[]{"kupfer"}, s -> ore(s, "copper"),
                ids("raw_copper", "copper_ingot"));
        EMERALD = add("smaragd", new String[]{"smaragd", "emerald"}, s -> ore(s, "emerald"), ids("emerald"));
        SAND = add("sand", new String[]{"sand"}, s -> s.is(BlockTags.SAND), ids("sand", "red_sand"));
        GRAVEL = add("kies", new String[]{"kies"}, s -> s.is(Blocks.GRAVEL), ids("gravel"));
        DIRT = add("erde", new String[]{"erde", "dreck"}, s -> s.is(BlockTags.DIRT), ids("dirt"));
        OBSIDIAN = add("obsidian", new String[]{"obsidian"}, s -> s.is(Blocks.OBSIDIAN), ids("obsidian"));
        SEEDS = add("samen", new String[]{"samen", "saat"}, s -> s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS),
                ids("wheat_seeds"));
    }

    public static Res byKey(String key) {
        for (Res r : ALL) if (r.key().equals(key)) return r;
        return null;
    }

    /** Tatsächlicher Bestand statt der Zahl geplanter Abbaublöcke. */
    public static int count(CompanionEntity companion, Res resource) {
        int count = 0;
        var inventory = companion.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (resource.item().test(stack)) count += stack.getCount();
        }
        return count;
    }

    public static Res byNoun(List<String> tokens) {
        for (String t : tokens)
            for (Res r : ALL)
                for (String n : r.nouns())
                    if (t.startsWith(n)) return r;
        return null;
    }

    // ------------------------------------------------------------------ Mobs
    public static final Map<String, String> MOBS = new LinkedHashMap<>();
    static {
        MOBS.put("schaf", "sheep");   MOBS.put("kuh", "cow");       MOBS.put("kue", "cow");
        MOBS.put("schwein", "pig");   MOBS.put("huhn", "chicken");  MOBS.put("huehn", "chicken");
        MOBS.put("pferd", "horse");   MOBS.put("esel", "donkey");   MOBS.put("katze", "cat");
        MOBS.put("wolf", "wolf");     MOBS.put("hund", "wolf");     MOBS.put("hase", "rabbit");
        MOBS.put("ziege", "goat");    MOBS.put("lama", "llama");    MOBS.put("fuchs", "fox");
        MOBS.put("zombie", "zombie"); MOBS.put("creeper", "creeper"); MOBS.put("skelett", "skeleton");
        MOBS.put("spinne", "spider");
        MOBS.put("mob", "any");       MOBS.put("tier", "any");      MOBS.put("viech", "any");
    }

    /** Liefert die Entity-ID ("sheep", ..., "any") oder null. */
    public static String mobFor(List<String> tokens) {
        for (String t : tokens)
            for (Map.Entry<String, String> e : MOBS.entrySet())
                if (t.startsWith(e.getKey())) return "any".equals(e.getValue()) ? "any" : RegistryIds.canonical(e.getValue());
        return null;
    }
}
