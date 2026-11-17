package dev.begleiter.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Englische Chatbefehle mit derselben Prioritätsreihenfolge wie {@link LexiconDe}. Englische Wörter werden
 * als ganze Wörter (mit Plural-s) verglichen, weil Wortanfänge hier oft kollidieren ("cat" ↔ "catch").
 */
public final class LexiconEn implements Lexicon {
    public static final LexiconEn INSTANCE = new LexiconEn();
    private LexiconEn() {}

    static final String[] FETCH_VERBS = {"bring", "get", "fetch", "find", "catch", "lure", "lead"};
    static final String[] GATHER_VERBS = {"bring", "get", "fetch", "gather", "collect", "find", "search", "need",
            "chop", "cut", "mine", "dig", "farm", "grab", "obtain", "acquire", "procure"};
    static final String[] GIVE_VERBS = {"give", "hand"};
    static final String[] MAKE_VERBS = {"make", "craft", "create", "produce"};
    static final String[] BUILD_VERBS = {"build", "construct", "erect"};

    static final Map<String, String> RESOURCES = new LinkedHashMap<>();
    static final Map<String, String> MOBS = new LinkedHashMap<>();
    static final Map<String, String> MATERIALS = new LinkedHashMap<>();
    static final Map<String, String> TOOLS = new LinkedHashMap<>();
    /** Mehrwort-Items zuerst, dann einzelne Wörter. */
    static final Map<String, String> PHRASE_ITEMS = new LinkedHashMap<>();
    static final Map<String, String> ITEMS = new LinkedHashMap<>();
    static {
        String[][] resources = {{"wood", "holz"}, {"log", "holz"}, {"tree", "holz"}, {"timber", "holz"},
                {"stone", "stein"}, {"cobble", "stein"}, {"cobblestone", "stein"}, {"coal", "kohle"}, {"iron", "eisen"},
                {"gold", "gold"}, {"diamond", "diamant"}, {"redstone", "redstone"}, {"lapis", "lapis"},
                {"copper", "kupfer"}, {"emerald", "smaragd"}, {"sand", "sand"}, {"gravel", "kies"}, {"dirt", "erde"},
                {"soil", "erde"}, {"obsidian", "obsidian"}, {"seed", "samen"}};
        for (String[] r : resources) RESOURCES.put(r[0], r[1]);

        String[][] mobs = {{"sheep", "sheep"}, {"cow", "cow"}, {"cattle", "cow"}, {"pig", "pig"}, {"chicken", "chicken"},
                {"horse", "horse"}, {"donkey", "donkey"}, {"cat", "cat"}, {"wolf", "wolf"}, {"wolves", "wolf"},
                {"dog", "wolf"}, {"rabbit", "rabbit"}, {"bunny", "rabbit"}, {"goat", "goat"}, {"llama", "llama"},
                {"fox", "fox"}, {"zombie", "zombie"}, {"creeper", "creeper"}, {"skeleton", "skeleton"},
                {"spider", "spider"}, {"mob", "any"}, {"animal", "any"}, {"critter", "any"}};
        for (String[] m : mobs) MOBS.put(m[0], m[1]);

        String[][] materials = {{"wooden", "wooden"}, {"wood", "wooden"}, {"stone", "stone"}, {"iron", "iron"},
                {"gold", "golden"}, {"golden", "golden"}, {"diamond", "diamond"}, {"netherite", "netherite"},
                {"leather", "leather"}, {"chainmail", "chainmail"}, {"chain", "chainmail"}};
        for (String[] m : materials) MATERIALS.put(m[0], m[1]);
        String[][] tools = {{"pickaxe", "pickaxe"}, {"pick", "pickaxe"}, {"axe", "axe"}, {"sword", "sword"},
                {"shovel", "shovel"}, {"spade", "shovel"}, {"hoe", "hoe"}, {"helmet", "helmet"}, {"helm", "helmet"},
                {"chestplate", "chestplate"}, {"leggings", "leggings"}, {"pants", "leggings"},
                {"trousers", "leggings"}, {"boots", "boots"}};
        for (String[] t : tools) TOOLS.put(t[0], t[1]);

        String[][] phrases = {{"iron ingot", "iron_ingot"}, {"gold ingot", "gold_ingot"}, {"golden apple", "golden_apple"},
                {"stone brick", "stone_bricks"}, {"flint and steel", "flint_and_steel"}, {"ender pearl", "ender_pearl"},
                {"ender eye", "ender_eye"}, {"eye of ender", "ender_eye"}, {"blaze rod", "blaze_rod"},
                {"crafting table", "crafting_table"}, {"fishing rod", "fishing_rod"}, {"wheat seed", "wheat_seeds"}};
        for (String[] p : phrases) PHRASE_ITEMS.put(p[0], p[1]);
        String[][] items = {{"bread", "bread"}, {"diamond", "diamond"}, {"torch", "torch"}, {"coal", "coal"},
                {"apple", "apple"}, {"steak", "cooked_beef"}, {"arrow", "arrow"}, {"bow", "bow"}, {"pearl", "ender_pearl"},
                {"emerald", "emerald"}, {"wheat", "wheat"}, {"seed", "wheat_seeds"}, {"glass", "glass"},
                {"bucket", "bucket"}, {"redstone", "redstone"}, {"obsidian", "obsidian"}, {"chest", "chest"},
                {"furnace", "furnace"}, {"workbench", "crafting_table"}, {"ladder", "ladder"}, {"fence", "oak_fence"},
                {"door", "oak_door"}, {"shield", "shield"}, {"stick", "stick"}, {"plank", "oak_planks"},
                {"compass", "compass"}, {"shears", "shears"}, {"lighter", "flint_and_steel"}, {"piston", "piston"},
                {"repeater", "repeater"}, {"lever", "lever"}, {"hopper", "hopper"}, {"rail", "rail"},
                {"paper", "paper"}, {"book", "book"}, {"carrot", "carrot"}, {"potato", "potato"}, {"barrel", "barrel"},
                {"string", "string"}, {"feather", "feather"}, {"leather", "leather"}, {"flint", "flint"},
                {"bone", "bone"}};
        for (String[] i : items) ITEMS.put(i[0], i[1]);
    }

    private static final Set<String> WAYPOINT_FILLER = Set.of("the", "a", "an", "my", "our", "please", "companion",
            "waypoint", "back", "here");

    @Override public String language() { return "en"; }

    @Override public List<ChatIntent> intents(Sentence s) {
        List<ChatIntent> out = new ArrayList<>();
        // Waypoints: "remember this as mine", "forget waypoint mine", "which waypoints do you know", "go to mine"
        if (s.anyStart("remember", "mark", "save", "note") && s.any("as")) out.add(ChatIntent.WAYPOINT_SAVE);
        if (s.anyStart("forget", "delet", "remov", "eras")) out.add(ChatIntent.WAYPOINT_FORGET);
        else {
            if (s.has("waypoint", "which places", "what places", "places do you know", "places you know"))
                out.add(ChatIntent.WAYPOINT_LIST);
            if (s.any("go", "walk", "run", "lead", "take", "show", "travel", "head") && s.any("to", "into", "towards", "toward"))
                out.add(ChatIntent.WAYPOINT_GO);
        }
        if ((s.has("project") && s.anyStart("plan", "preview", "show")) || (s.anyStart("plan", "design", "draft") && s.anyStart("base", "house")))
            out.add(ChatIntent.PROJECT_PREVIEW);
        if (terraform(s) != null) out.add(ChatIntent.TERRAFORM);
        if (stance(s) != null) out.add(ChatIntent.STANCE);
        if (s.anyStart("protect", "defend", "guard") && s.any("me", "us")) out.add(ChatIntent.PROTECT);
        if (s.has("memory") && s.anyStart("forget", "clear", "eras", "delet", "wipe", "reset")) out.add(ChatIntent.MEMORY_CLEAR);
        if (s.has("what do you know", "what do you remember", "what have you seen") || (s.has("memory") && !s.any("build")))
            out.add(ChatIntent.MEMORY_SUMMARY);
        if (s.any("where") && !s.any("build", "are")) out.add(ChatIntent.WHERE_IS);
        if (s.any("stop", "cancel", "abort", "halt") || s.phrase("forget it", "never mind", "nevermind", "knock it off", "quit it"))
            out.add(ChatIntent.CANCEL);
        if (s.any("pause")) out.add(ChatIntent.PAUSE);
        if (s.any("continue", "resume") || s.phrase("go on", "carry on", "keep going")) out.add(ChatIntent.RESUME);
        if (s.any("why", "goal", "goals", "needs") || s.phrase("what do you need")) out.add(ChatIntent.EXPLAIN);
        if (s.any("status") || s.has("what are you doing", "how are you", "what is up")) out.add(ChatIntent.STATUS);
        if (s.has("storage") && s.any("is", "set", "use", "take") && !s.anyStart("sort", "tidy", "organi"))
            out.add(ChatIntent.SET_HOME);
        boolean care = s.has("take care", "look after", "tend", "manage");
        boolean farmWords = word(s, "field", "farm", "crop", "garden", "wheat");
        if (s.anyStart("harvest", "plant", "sow") || (care && farmWords && !s.any("build")))
            out.add(ChatIntent.FARM);
        String breedMob = mob(s);
        if ("any".equals(breedMob)) breedMob = null;
        if (s.anyStart("breed", "multiply") || (care && (word(s, "herd", "animal") || breedMob != null)))
            out.add(ChatIntent.BREED);
        if (s.anyStart("repair", "fix", "mend") || (s.has("maintain") && word(s, "tool", "armor", "armour", "gear")))
            out.add(ChatIntent.REPAIR);
        if (s.anyStart("trade", "buy", "sell", "exchange", "swap", "barter")) out.add(ChatIntent.TRADE);
        if (!s.any(GIVE_VERBS) && (s.phrase("gear up", "suit up", "arm yourself", "equip yourself", "get stronger",
                "become stronger") || s.any("equip") || (word(s, "armor", "armour") && s.anyStart("full", "complete"))))
            out.add(ChatIntent.GEAR_UP);
        if (s.has("take care", "look after") || s.any("manage", "keeper")) out.add(ChatIntent.KEEPER);
        if (s.anyStart("sort", "organi", "arrang") || s.phrase("clean up", "tidy up")) out.add(ChatIntent.SORT);
        if (word(s, "door") && s.any("build", "put", "place", "set", "install", "add")) out.add(ChatIntent.DOOR);
        if (word(s, "schematic", "blueprint") && s.any("which", "what", "show", "list")) out.add(ChatIntent.LIST_SCHEMATICS);
        if (s.any(BUILD_VERBS)) out.add(ChatIntent.BUILD_SCHEMATIC);
        if ((s.any("build", "construct", "erect", "make", "create") || s.phrase("set up", "lay out"))
                && word(s, "house", "hut", "cabin", "shelter", "base", "home", "farm", "field", "wheat"))
            out.add(ChatIntent.BUILD_STRUCTURE);
        if (mob(s) != null && s.any(FETCH_VERBS)) out.add(ChatIntent.FETCH_ANIMAL);
        if (s.any(GIVE_VERBS)) out.add(ChatIntent.GIVE);
        if ((s.any(GATHER_VERBS) || s.any(MAKE_VERBS)) && item(s) != null) out.add(ChatIntent.OBTAIN);
        if (resource(s) != null && s.any(GATHER_VERBS)) out.add(ChatIntent.GATHER);
        if (s.has("do what you want", "on your own", "by yourself", "play normally", "survive")
                || s.any("autonomous", "autonomously", "free", "roam", "play"))
            out.add(ChatIntent.FREE_ROAM);
        if (s.any("follow", "come") || s.phrase("with me")) out.add(ChatIntent.FOLLOW);
        if (s.any("stay", "wait", "stand")) out.add(ChatIntent.STAY);
        return out;
    }

    @Override public ChatParams params(Sentence s) {
        Sentence what = s.after("for");
        boolean sell = s.anyStart("sell");
        String tradeItem = sell ? null : item(what);
        if ("emerald".equals(tradeItem)) tradeItem = null; // "for emeralds" = verkaufen
        return new ChatParams(ChatCount.find(s.tokens()), resource(s), mob(s), item(s), stance(s), terraform(s),
                size(s), style(s), tier(s), word(s, "stone", "cobble", "cobblestone") ? "cobblestone" : null, waypoint(s),
                s.any("bring", "get", "fetch", "need", "me"), s.any("me", "us"), tradeItem,
                ChatCount.find(what.tokens()), sell, Sentence.query(s, SCHEMATIC_FILLER), s.any("air"));
    }

    private static final Set<String> SCHEMATIC_FILLER = Set.of("build", "construct", "erect", "the", "a", "an", "me",
            "us", "here", "please", "schematic", "schematics", "with", "air", "companion", "there", "rotated",
            "rotation", "for");

    /** Ganzes Wort oder dessen Plural ("cow", "cows"; "torch", "torches"). */
    private static boolean matches(String token, String word) {
        return token.equals(word) || token.equals(word + "s") || token.equals(word + "es");
    }

    static boolean word(Sentence s, String... words) {
        for (String t : s.tokens()) for (String w : words) if (matches(t, w)) return true;
        return false;
    }

    static String resource(Sentence s) {
        for (String t : s.tokens()) for (var r : RESOURCES.entrySet()) if (matches(t, r.getKey())) return r.getValue();
        return null;
    }

    static String mob(Sentence s) {
        for (String t : s.tokens()) for (var m : MOBS.entrySet()) if (matches(t, m.getKey())) return m.getValue();
        return null;
    }

    static String item(Sentence s) {
        List<String> tok = s.tokens();
        for (int i = 0; i < tok.size(); i++)
            for (var tool : TOOLS.entrySet()) {
                if (!matches(tok.get(i), tool.getKey())) continue;
                String mat = i > 0 ? MATERIALS.get(tok.get(i - 1)) : null;
                String id = ChatItems.toolOrArmor(mat, tool.getValue());
                if (id != null) return id;
            }
        for (var p : PHRASE_ITEMS.entrySet())
            if (s.phrase(p.getKey()) || s.phrase(p.getKey() + "s")) return p.getValue();
        for (String t : tok) for (var e : ITEMS.entrySet()) if (matches(t, e.getKey())) return e.getValue();
        return null;
    }

    static String stance(Sentence s) {
        boolean mode = s.has("stance", "mode");
        if (s.has("passive", "do not fight", "dont fight", "peaceful", "attack nothing", "attack nobody",
                "attack no one", "stop fighting")) return "PASSIVE";
        if (s.has("defensive", "only defend", "defend yourself only", "only when attacked", "only if attacked"))
            return "DEFENSIVE";
        if (s.has("aggressive", "offensive", "attack everything", "attack all") || (mode && s.has("normal")))
            return "AGGRESSIVE";
        return null;
    }

    static String terraform(Sentence s) {
        boolean any = s.anyStart("flatten", "terraform") || s.any("level");
        if (s.anyStart("smooth")) return "SMOOTH";
        if (word(s, "hill", "mound") && (s.any("build", "make", "pile", "raise", "heap"))) return "HILL";
        if (s.anyStart("dig", "excavat") && word(s, "hole", "pit", "basement", "cellar", "pool")) return "PIT";
        boolean terrain = word(s, "hole", "pit", "pond", "lake", "ditch", "ground", "water", "crater", "hill",
                "mountain", "terrain", "dirt", "mound", "dip");
        if (terrain && s.anyStart("fill")) return "FILL";
        if (terrain && (s.any("remove", "cut", "clear") || s.phrase("take down", "dig away"))) return "CUT";
        if (any || (word(s, "ground", "terrain", "area") && s.any("flat", "even"))) return "FLATTEN";
        return null;
    }

    static String size(Sentence s) {
        for (String t : s.tokens()) {
            if (Set.of("small", "tiny", "little").contains(t)) return "small";
            if (Set.of("large", "big", "huge", "giant").contains(t)) return "large";
        }
        return null;
    }

    static String style(Sentence s) {
        for (String t : s.tokens()) {
            if (t.equals("stone") || t.startsWith("cobble")) return "stone";
            if (t.equals("birch")) return "birch";
        }
        return null;
    }

    static String tier(Sentence s) {
        if (s.any("netherite")) return "netherite";
        if (word(s, "diamond")) return "diamond";
        if (s.any("iron")) return "iron";
        return null;
    }

    /** Name after "as": "remember this as my mine" → "mine". */
    static String waypoint(Sentence s) {
        List<String> tok = s.tokens();
        for (int i = 0; i < tok.size() - 1; i++)
            if (tok.get(i).equals("as")) {
                List<String> rest = new ArrayList<>(tok.subList(i + 1, tok.size()));
                rest.removeIf(WAYPOINT_FILLER::contains);
                if (!rest.isEmpty()) return String.join(" ", rest);
            }
        return null;
    }
}
