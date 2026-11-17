package dev.begleiter.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deutsche Chatbefehle. Bedingungen und Reihenfolge entsprechen der bisherigen Erkennung in
 * {@code CompanionBrain.interpret}, {@code CompanionTerraform.modeFrom}, {@code CompanionCombat.stanceFrom},
 * {@code Resources} und {@code CompanionBrain.itemWord}.
 */
public final class LexiconDe implements Lexicon {
    public static final LexiconDe INSTANCE = new LexiconDe();
    private LexiconDe() {}

    static final String[] FETCH_VERBS = {"bring", "bringe", "bringen", "hol", "hole", "holen", "such", "suche",
            "suchen", "finde", "finden", "fang", "fange", "fangen", "besorg", "besorge", "besorgen"};
    static final String[] GATHER_VERBS = {"bring", "bringe", "bringen", "hol", "hole", "holen", "besorg",
            "besorge", "besorgen", "sammle", "sammeln", "sammel", "such", "suche", "suchen", "finde", "finden",
            "brauche", "brauch", "fell", "faelle", "faellen", "abbauen", "hack", "hacke", "grab", "grabe", "mine",
            "farm", "farme", "beschaff", "beschaffe"};
    static final String[] GIVE_VERBS = {"gib", "gebe", "geb", "reich", "reiche", "uebergib"};
    static final String[] MAKE_VERBS = {"mach", "mache", "craft", "crafte", "stell", "stelle", "herstellen"};
    static final String[] BUILD_VERBS = {"bau", "baue", "bauen", "errichte", "errichten"};

    /** Rohstoff-Schlüssel → Wortanfänge, in der Reihenfolge von {@code Resources.ALL}. */
    static final Map<String, String[]> RESOURCES = new LinkedHashMap<>();
    /** Wortanfang → Entity-Pfad, in der Reihenfolge von {@code Resources.MOBS}. */
    static final Map<String, String> MOBS = new LinkedHashMap<>();
    static final Map<String, String> MATERIALS = new LinkedHashMap<>();
    static final Map<String, String> TOOLS = new LinkedHashMap<>();
    /** Wortanfang → Item-Pfad; längere Schlüssel zuerst, damit die Zuordnung eindeutig ist. */
    static final Map<String, String> ITEMS = new LinkedHashMap<>();
    static {
        RESOURCES.put("holz", new String[]{"holz", "baum", "stamm", "staemm", "log"});
        RESOURCES.put("stein", new String[]{"stein", "cobble", "bruchstein"});
        RESOURCES.put("kohle", new String[]{"kohle"});
        RESOURCES.put("eisen", new String[]{"eisen"});
        RESOURCES.put("gold", new String[]{"gold"});
        RESOURCES.put("diamant", new String[]{"diamant"});
        RESOURCES.put("redstone", new String[]{"redstone", "rotstein"});
        RESOURCES.put("lapis", new String[]{"lapis"});
        RESOURCES.put("kupfer", new String[]{"kupfer"});
        RESOURCES.put("smaragd", new String[]{"smaragd", "emerald"});
        RESOURCES.put("sand", new String[]{"sand"});
        RESOURCES.put("kies", new String[]{"kies"});
        RESOURCES.put("erde", new String[]{"erde", "dreck"});
        RESOURCES.put("obsidian", new String[]{"obsidian"});
        RESOURCES.put("samen", new String[]{"samen", "saat"});

        String[][] mobs = {{"schaf", "sheep"}, {"kuh", "cow"}, {"kue", "cow"}, {"schwein", "pig"}, {"huhn", "chicken"},
                {"huehn", "chicken"}, {"pferd", "horse"}, {"esel", "donkey"}, {"katze", "cat"}, {"wolf", "wolf"},
                {"hund", "wolf"}, {"hase", "rabbit"}, {"ziege", "goat"}, {"lama", "llama"}, {"fuchs", "fox"},
                {"zombie", "zombie"}, {"creeper", "creeper"}, {"skelett", "skeleton"}, {"spinne", "spider"},
                {"mob", "any"}, {"tier", "any"}, {"viech", "any"}};
        for (String[] m : mobs) MOBS.put(m[0], m[1]);

        String[][] materials = {{"holz", "wooden"}, {"stein", "stone"}, {"eisen", "iron"}, {"eiser", "iron"},
                {"gold", "golden"}, {"diamant", "diamond"}, {"netherit", "netherite"}, {"leder", "leather"}};
        for (String[] m : materials) MATERIALS.put(m[0], m[1]);
        String[][] tools = {{"spitzhacke", "pickaxe"}, {"feldhacke", "hoe"}, {"axt", "axe"}, {"schwert", "sword"},
                {"schaufel", "shovel"}, {"helm", "helmet"}, {"brustpanzer", "chestplate"}, {"harnisch", "chestplate"},
                {"hose", "leggings"}, {"beinschutz", "leggings"}, {"stiefel", "boots"}};
        for (String[] t : tools) TOOLS.put(t[0], t[1]);

        String[][] items = {{"eisenbarren", "iron_ingot"}, {"goldbarren", "gold_ingot"}, {"goldapfel", "golden_apple"},
                {"steinziegel", "stone_bricks"}, {"feuerstein", "flint"}, {"feuerzeug", "flint_and_steel"},
                {"verstaerker", "repeater"}, {"enderauge", "ender_eye"}, {"lohenrute", "blaze_rod"},
                {"werkbank", "crafting_table"}, {"kartoffel", "potato"}, {"smaragde", "emerald"}, {"smaragd", "emerald"},
                {"diamant", "diamond"}, {"trichter", "hopper"}, {"kompass", "compass"}, {"knochen", "bone"},
                {"karotte", "carrot"}, {"schiene", "rail"}, {"papier", "paper"}, {"kolben", "piston"},
                {"schere", "shears"}, {"angel", "fishing_rod"}, {"weizen", "wheat"}, {"samen", "wheat_seeds"},
                {"fackel", "torch"}, {"kohle", "coal"}, {"apfel", "apple"}, {"aepfel", "apple"},
                {"steak", "cooked_beef"}, {"pfeil", "arrow"}, {"bogen", "bow"}, {"perle", "ender_pearl"},
                {"glas", "glass"}, {"eimer", "bucket"}, {"redstone", "redstone"}, {"obsidian", "obsidian"},
                {"kiste", "chest"}, {"ofen", "furnace"}, {"leiter", "ladder"}, {"zaun", "oak_fence"},
                {"tuer", "oak_door"}, {"schild", "shield"}, {"stock", "stick"}, {"stoeck", "stick"},
                {"brett", "oak_planks"}, {"hebel", "lever"}, {"buch", "book"}, {"buech", "book"},
                {"fass", "barrel"}, {"faden", "string"}, {"feder", "feather"}, {"leder", "leather"},
                {"brot", "bread"}};
        for (String[] i : items) ITEMS.put(i[0], i[1]);
    }

    private static final Set<String> WAYPOINT_FILLER = Set.of("der", "die", "das", "dem", "den", "meiner", "meinem",
            "meine", "unserer", "unserem", "unsere", "bitte", "begleiter", "wegpunkt", "hin", "gehen", "laufen",
            "zurueck");

    @Override public String language() { return "de"; }

    @Override public List<ChatIntent> intents(Sentence s) {
        List<ChatIntent> out = new ArrayList<>();
        // Wegpunkte: "Merk dir das als Mine", "Vergiss den Wegpunkt Mine", "Welche Wegpunkte kennst du", "Geh zur Mine"
        if (s.anyStart("merk") && s.any("als")) out.add(ChatIntent.WAYPOINT_SAVE);
        if (s.anyStart("vergiss", "loesch", "entfern")) out.add(ChatIntent.WAYPOINT_FORGET);
        else {
            if (s.has("wegpunkt", "welche orte", "orte kennst")) out.add(ChatIntent.WAYPOINT_LIST);
            if (s.any("geh", "gehe", "lauf", "laufe", "renn", "renne", "fuehr", "fuehre", "bring", "zeig", "zeige",
                    "gehen", "reise") && s.any("zur", "zum", "zu", "nach", "hin", "in", "ins"))
                out.add(ChatIntent.WAYPOINT_GO);
        }
        if ((s.has("projekt") && s.anyStart("plan", "vorschau", "zeig")) || (s.anyStart("plan", "entwerf") && s.anyStart("basis", "haus")))
            out.add(ChatIntent.PROJECT_PREVIEW);
        if (terraform(s) != null) out.add(ChatIntent.TERRAFORM);
        if (stance(s) != null) out.add(ChatIntent.STANCE);
        if (s.anyStart("beschuetz", "verteidig") && s.any("mich", "uns")) out.add(ChatIntent.PROTECT);
        if (s.has("gedaechtnis") && s.anyStart("vergiss", "loesch", "leer")) out.add(ChatIntent.MEMORY_CLEAR);
        if (s.has("was weisst du", "was kennst du") || (s.has("gedaechtnis") && !s.any("bau", "baue")))
            out.add(ChatIntent.MEMORY_SUMMARY);
        if (s.any("wo", "wohin") && !s.any("bau", "baue", "bist")) out.add(ChatIntent.WHERE_IS);
        if (s.any("stopp", "stop", "abbrechen", "abbruch", "vergiss") || s.has("hoer auf", "lass das"))
            out.add(ChatIntent.CANCEL);
        if (s.any("pause", "pausier", "pausiere", "anhalten")) out.add(ChatIntent.PAUSE);
        if (s.any("weiter", "fortsetzen", "los")) out.add(ChatIntent.RESUME);
        if (s.any("warum", "wieso", "weshalb", "ziel", "beduerfnisse", "brauchst")) out.add(ChatIntent.EXPLAIN);
        if (s.any("status") || s.has("was machst du", "wie geht")) out.add(ChatIntent.STATUS);
        if (s.has("lager") && s.any("ist", "setz", "setze", "nimm", "nutze") && !s.anyStart("raeum", "aufraeum"))
            out.add(ChatIntent.SET_HOME);
        boolean farmWords = s.anyStart("feld", "farm", "acker", "garten", "weizenfeld");
        if (s.anyStart("ernte", "ernten", "pflanz", "saee", "saeen", "saeh")
                || ((s.has("kuemmer") || s.anyStart("pfleg")) && farmWords && !s.any("bau", "baue", "bauen")))
            out.add(ChatIntent.FARM);
        String breedMob = mob(s);
        if ("any".equals(breedMob)) breedMob = null;
        if (s.anyStart("zuecht", "zucht", "vermehr", "nachwuchs") || (s.has("kuemmer") && (s.anyStart("herde", "tiere") || breedMob != null)))
            out.add(ChatIntent.BREED);
        if (s.anyStart("reparier", "flick") || (s.anyStart("pfleg") && s.anyStart("werkzeug", "ruestung", "ausruestung")))
            out.add(ChatIntent.REPAIR);
        if (s.anyStart("handel", "tausch", "kauf", "verkauf", "eintausch")) out.add(ChatIntent.TRADE);
        if (!s.any(GIVE_VERBS) && (s.any("ruest", "rueste", "ruesten", "ausruesten", "aufruesten", "ruestet")
                || s.has("staerker") || (s.has("ruestung") && s.anyStart("voll", "komplett", "ganz"))))
            out.add(ChatIntent.GEAR_UP);
        if (s.has("kuemmer") || s.any("lagerverwalter", "verwalte") || s.has("auf das lager")) out.add(ChatIntent.KEEPER);
        if (s.anyStart("raeum", "aufraeum", "sortier", "ordne")) out.add(ChatIntent.SORT);
        if (s.anyStart("tuer") && s.any("bau", "baue", "setz", "setze", "einbauen", "ein", "platzier", "platziere"))
            out.add(ChatIntent.DOOR);
        if (s.has("schematic") && s.any("welche", "zeig", "liste", "list", "gibt")) out.add(ChatIntent.LIST_SCHEMATICS);
        if (s.any(BUILD_VERBS)) out.add(ChatIntent.BUILD_SCHEMATIC);
        // Zusammensetzungen wie "Steinhaus" oder "Holzhütte" zählen ebenfalls (bisher nur Wortanfänge).
        if (s.any("bau", "baue", "bauen", "errichte", "errichten", "leg", "lege", "anlegen")
                && (s.anyStart("haus", "huett", "unterkunft", "base", "farm", "feld", "weizen", "acker")
                    || s.tokens().stream().anyMatch(t -> t.endsWith("haus") || t.contains("huett"))))
            out.add(ChatIntent.BUILD_STRUCTURE);
        if (mob(s) != null && s.any(FETCH_VERBS)) out.add(ChatIntent.FETCH_ANIMAL);
        if (s.any(GIVE_VERBS)) out.add(ChatIntent.GIVE);
        if ((s.any(GATHER_VERBS) || s.any(MAKE_VERBS)) && item(s) != null) out.add(ChatIntent.OBTAIN);
        if (resource(s) != null && s.any(GATHER_VERBS)) out.add(ChatIntent.GATHER);
        if (s.has("mach was du willst", "spiel normal", "tu was du", "spiel selbst", "ueberleb")
                || s.any("selbststaendig", "frei", "spiel", "spiele"))
            out.add(ChatIntent.FREE_ROAM);
        if (s.any("folge", "folg", "komm", "mitkommen", "hinterher") || s.has("bei mir")) out.add(ChatIntent.FOLLOW);
        if (s.any("warte", "bleib", "bleibe", "steh")) out.add(ChatIntent.STAY);
        return out;
    }

    @Override public ChatParams params(Sentence s) {
        Sentence what = s.after("gegen");
        boolean sell = s.anyStart("verkauf");
        String tradeItem = sell ? null : item(what);
        if ("emerald".equals(tradeItem)) tradeItem = null; // "gegen Smaragde" = verkaufen
        return new ChatParams(ChatCount.find(s.tokens()), resource(s), mob(s), item(s), stance(s), terraform(s),
                size(s), style(s), tier(s), s.anyStart("stein", "bruchstein") ? "cobblestone" : null, waypoint(s),
                s.any("bring", "bringe", "bringen", "hol", "hole", "holen", "besorg", "besorge", "besorgen",
                        "brauche", "brauch", "mir"),
                s.any("mir", "uns"), tradeItem, ChatCount.find(what.tokens()), sell,
                Sentence.query(s, SCHEMATIC_FILLER), s.any("luft"));
    }

    /** Wie {@code CompanionBrain.FILLER}: Wörter, die nicht zum Schematic-Namen gehören. */
    private static final Set<String> SCHEMATIC_FILLER = Set.of("bau", "baue", "bauen", "errichte", "errichten",
            "das", "die", "den", "ein", "eine", "mir", "uns", "hier", "bitte", "schematic", "schematics", "mit", "luft",
            "begleiter", "dort", "da", "gedreht", "drehung");

    static String resource(Sentence s) {
        for (String t : s.tokens())
            for (var r : RESOURCES.entrySet())
                for (String noun : r.getValue())
                    if (t.startsWith(noun)) return r.getKey();
        return null;
    }

    /** "kümmer" beginnt wie "kue" (Kuh) und zählt darum nicht als Tierwort. */
    static String mob(Sentence s) {
        for (String t : s.tokens()) {
            if (t.startsWith("kuemmer")) continue;
            for (var m : MOBS.entrySet()) if (t.startsWith(m.getKey())) return m.getValue();
        }
        return null;
    }

    /** Werkzeug/Rüstung mit Material ("Eisenspitzhacke", "eiserne Axt") oder ein bekanntes Item-Wort. */
    static String item(Sentence s) {
        List<String> tok = s.tokens();
        for (int i = 0; i < tok.size(); i++) {
            String t = tok.get(i);
            for (var tool : TOOLS.entrySet()) {
                int at = t.indexOf(tool.getKey());
                if (at < 0 || at + tool.getKey().length() < t.length() - 1) continue; // Plural "-n"/"-e" erlaubt
                String prefix = t.substring(0, at);
                String mat = null;
                for (var m : MATERIALS.entrySet()) if (!prefix.isEmpty() && prefix.startsWith(m.getKey())) mat = m.getValue();
                if (mat == null && i > 0) for (var m : MATERIALS.entrySet()) if (tok.get(i - 1).startsWith(m.getKey())) mat = m.getValue();
                String id = ChatItems.toolOrArmor(mat, tool.getValue());
                if (id != null) return id;
            }
        }
        for (String t : tok) {
            if (t.length() < 3) continue;
            for (var e : ITEMS.entrySet()) if (t.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    static String stance(Sentence s) {
        boolean mode = s.has("kampfmodus", "kampf modus", "modus");
        if (s.has("passiv", "kaempf nicht", "nicht kaempf", "friedlich", "greif nichts an", "greif niemanden an",
                "greif keinen an")) return "PASSIVE";
        if (s.has("defensiv", "nur verteidig", "verteidige dich nur", "nur wenn du angegriffen")) return "DEFENSIVE";
        if (s.has("aggressiv", "offensiv", "greif alles an", "greif alle an") || (mode && s.has("normal")))
            return "AGGRESSIVE";
        return null;
    }

    static String terraform(Sentence s) {
        boolean any = s.anyStart("planier", "ebne", "glaett", "terraform");
        if (s.anyStart("glaett")) return "SMOOTH";
        if (s.has("huegel") && (s.anyStart("schuett", "aufschuett", "mach") || s.any("bau", "baue"))) return "HILL";
        if ((s.anyStart("grab", "aushe") || s.any("heb", "hebe")) && s.anyStart("loch", "grube", "keller", "becken"))
            return "PIT";
        boolean terrain = s.anyStart("loch", "loech", "teich", "grube", "graben", "boden", "wasser", "krater", "huegel",
                "berg", "gelaende", "erde", "erhebung", "senke") || s.any("see");
        if (terrain && ((s.anyStart("fuell", "schuett") && s.any("auf")) || s.anyStart("auffuell", "zuschuett")))
            return "FILL";
        if (terrain && ((s.anyStart("trag") && s.any("ab")) || s.anyStart("abtrag"))) return "CUT";
        if (any || (s.has("boden", "gelaende", "flaeche") && s.any("eben", "flach", "gerade"))) return "FLATTEN";
        return null;
    }

    static String size(Sentence s) {
        for (String t : s.tokens()) {
            if (t.startsWith("klein")) return "small";
            if (t.startsWith("gross") || t.startsWith("riesig")) return "large";
        }
        return null;
    }

    static String style(Sentence s) {
        for (String t : s.tokens()) {
            if (t.startsWith("stein")) return "stone";
            if (t.startsWith("birk")) return "birch";
        }
        return null;
    }

    static String tier(Sentence s) {
        if (s.anyStart("netherit")) return "netherite";
        if (s.anyStart("diamant")) return "diamond";
        if (s.anyStart("eisen", "eiser")) return "iron";
        return null;
    }

    /** Name nach "als": "Merk dir das als meine Mine" → "mine". */
    static String waypoint(Sentence s) {
        List<String> tok = s.tokens();
        for (int i = 0; i < tok.size() - 1; i++)
            if (tok.get(i).equals("als")) {
                List<String> rest = new ArrayList<>(tok.subList(i + 1, tok.size()));
                rest.removeIf(WAYPOINT_FILLER::contains);
                if (!rest.isEmpty()) return String.join(" ", rest);
            }
        return null;
    }
}
