package dev.begleiter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Abnahme der Sprachdateien ohne Spielstart: gleiche Schlüssel, Platzhalter und Abdeckung. */
class LangFilesTest {
    private static final Path LANG = Path.of("src/main/resources/assets/begleiter/lang");
    private static final Path MAIN = Path.of("src/main/java/dev/begleiter");
    private static final Path CLIENT = Path.of("src/client/java/dev/begleiter");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?([sd%])");
    private static final Pattern KEY_LINE = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:");

    private static Map<String, String> load(String file) throws IOException {
        JsonObject json = JsonParser.parseString(Files.readString(LANG.resolve(file), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> result = new TreeMap<>();
        json.entrySet().forEach(e -> result.put(e.getKey(), e.getValue().getAsString()));
        return result;
    }

    @Test
    void beideSprachenHabenDieselbenSchluessel() throws IOException {
        var de = load("de_de.json");
        var en = load("en_us.json");
        Set<String> onlyDe = new TreeSet<>(de.keySet());
        onlyDe.removeAll(en.keySet());
        Set<String> onlyEn = new TreeSet<>(en.keySet());
        onlyEn.removeAll(de.keySet());
        assertTrue(onlyDe.isEmpty(), "Nur in de_de: " + onlyDe);
        assertTrue(onlyEn.isEmpty(), "Nur in en_us: " + onlyEn);
    }

    @Test
    void platzhalterStimmenUeberein() throws IOException {
        var de = load("de_de.json");
        var en = load("en_us.json");
        List<String> wrong = new ArrayList<>();
        for (String key : de.keySet())
            if (en.containsKey(key) && !placeholders(de.get(key)).equals(placeholders(en.get(key))))
                wrong.add(key + ": " + placeholders(de.get(key)) + " / " + placeholders(en.get(key)));
        assertTrue(wrong.isEmpty(), "Abweichende Platzhalter: " + wrong);
    }

    /** Normalform: Liste der Argumentnummern; %s ohne Nummer zählt fortlaufend wie in Minecraft. */
    private static List<Integer> placeholders(String value) {
        List<Integer> result = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(value);
        int next = 1;
        while (m.find()) {
            if (m.group(2).equals("%")) continue;
            result.add(m.group(1) != null ? Integer.parseInt(m.group(1)) : next++);
        }
        result.sort(Integer::compare);
        return result;
    }

    @Test
    void keineLeerenOderUnuebersetztenWerte() throws IOException {
        for (String file : new String[] {"de_de.json", "en_us.json"})
            load(file).forEach((key, value) -> {
                assertFalse(value.isBlank(), file + ": leerer Wert für " + key);
                assertNotEquals(key, value, file + ": Wert gleich Schlüssel bei " + key);
            });
    }

    @Test
    void englischOhneUmlaute() throws IOException {
        List<String> wrong = new ArrayList<>();
        load("en_us.json").forEach((key, value) -> { if (value.matches(".*[äöüÄÖÜß].*")) wrong.add(key); });
        assertTrue(wrong.isEmpty(), "Vermutlich unübersetzt: " + wrong);
    }

    @Test
    void keineDoppeltenSchluessel() throws IOException {
        for (String file : new String[] {"de_de.json", "en_us.json"}) {
            Set<String> seen = new TreeSet<>();
            for (String line : Files.readAllLines(LANG.resolve(file), StandardCharsets.UTF_8)) {
                Matcher m = KEY_LINE.matcher(line);
                if (m.find()) assertTrue(seen.add(m.group(1)), file + ": doppelter Schlüssel " + m.group(1));
            }
        }
    }

    /** Alle Klassen, die feste Oberflächentexte zeigen (Control Center, F8-Menü, F7-HUD). */
    private static List<Path> clientSources() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CLIENT)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
        }
        return out;
    }

    @Test
    void jederGuiSchluesselImCodeExistiert() throws IOException {
        var de = load("de_de.json");
        Pattern literal = Pattern.compile("\"((?:gui|debug)\\.begleiter\\.[a-z0-9_.]+[a-z0-9_])\"");
        List<String> missing = new ArrayList<>();
        int found = 0;
        for (Path file : clientSources()) {
            Matcher m = literal.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                found++;
                if (!de.containsKey(m.group(1))) missing.add(file.getFileName() + ": " + m.group(1));
            }
        }
        assertTrue(found > 150, "Zu wenige Schlüssel im Code gefunden: " + found);
        assertTrue(missing.isEmpty(), "Fehlende Schlüssel: " + missing);
    }

    /** Schalter im F8-Menü bilden ihren Tooltip als {@code <key>.tooltip}. */
    @Test
    void jederDebugSchalterHatEinenTooltip() throws IOException {
        var de = load("de_de.json");
        String screen = Files.readString(CLIENT.resolve("client/gui/BotDebugScreen.java"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("toggle\\((?:cx\\(\\)|x2), y, w, \"(debug\\.begleiter\\.[a-z_.]+)\"").matcher(screen);
        int toggles = 0;
        List<String> missing = new ArrayList<>();
        while (m.find()) {
            toggles++;
            if (!de.containsKey(m.group(1) + ".tooltip")) missing.add(m.group(1) + ".tooltip");
        }
        assertEquals(6, toggles, "Schalter im F8-Menü");
        assertTrue(missing.isEmpty(), "Fehlende Tooltips: " + missing);
    }

    private static String sources(Path root) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList())
                all.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
        }
        return all.toString();
    }

    private static final String SERVER = "server.begleiter.";

    /** Jeder feste Serverschlüssel existiert; fehlende fielen sonst erst im Spiel auf ({@code CompanionTexts.tr} wirft). */
    @Test
    void jederServerSchluesselImCodeExistiert() throws IOException {
        var de = load("de_de.json");
        Matcher m = Pattern.compile("\\btr\\(\\s*\"([a-z0-9_.]+)\"\\s*[,)]").matcher(sources(MAIN));
        List<String> missing = new ArrayList<>();
        int found = 0;
        while (m.find()) {
            found++;
            if (!de.containsKey(SERVER + m.group(1))) missing.add(m.group(1));
        }
        assertTrue(found > 500, "Zu wenige Serverschlüssel gefunden: " + found);
        assertTrue(missing.isEmpty(), "Fehlende Serverschlüssel: " + new TreeSet<>(missing));
    }

    /**
     * Jeder Schlüssel in den Dateien wird benutzt: wörtlich, als Serverkurzform oder über ein im Code
     * zusammengesetztes Präfix ({@code "need." + name}). Registry-Namen vergibt Minecraft selbst.
     */
    @Test
    void jederSchluesselWirdBenutzt() throws IOException {
        String code = sources(MAIN) + sources(CLIENT);
        Set<String> strings = new TreeSet<>();
        Matcher s = Pattern.compile("\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"").matcher(code);
        while (s.find()) strings.add(s.group(1));
        Set<String> prefixes = new TreeSet<>();
        Matcher p = Pattern.compile("\"([a-z0-9_.]+\\.)\"\\s*\\+").matcher(code);
        while (p.find()) prefixes.add(p.group(1));
        List<String> unused = new ArrayList<>();
        for (String key : load("de_de.json").keySet()) {
            // Von Minecraft bzw. Mod Menu gelesen, nicht aus dem eigenen Code
            if (key.startsWith("item.begleiter.") || key.startsWith("entity.begleiter.") || key.startsWith("key.category.begleiter.")
                    || key.startsWith("modmenu."))
                continue;
            String base = key.endsWith(".tooltip") ? key.substring(0, key.length() - ".tooltip".length()) : key;
            List<String> forms = new ArrayList<>(List.of(key, base));
            if (key.startsWith(SERVER)) { forms.add(key.substring(SERVER.length())); forms.add(base.substring(SERVER.length())); }
            boolean used = forms.stream().anyMatch(f -> strings.contains(f) || prefixes.stream().anyMatch(f::startsWith));
            if (!used) unused.add(key);
        }
        assertTrue(unused.isEmpty(), "Ungenutzte Schlüssel: " + unused);
    }

    /** Deutsche Satz-Literale nur in der Chat-Erkennung und den Entwickler-Testkarten. */
    private static final Pattern GERMAN = Pattern.compile(
            "\"[^\"\\\\]*([äöüÄÖÜß]|\\b(ich|der|die|das|und|nicht|kein|mein|dein)\\b)[^\"\\\\]*\"");
    /** Testarena und Testkarten: nur für Operatoren, bewusst nicht übersetzt. */
    private static final Set<String> GERMAN_FILES = Set.of("LexiconDe.java", "ItemRequest.java", "TestScenarios.java", "TestMap.java",
            "TestMachineScenarios.java", "TestRedstoneScenarios.java", "TestMapExpansion.java", "ParkourCourse.java",
            "SelfPlayRun.java"); // Entwickler-Protokoll des Selbstspiel-Laufs, nie im Spiel sichtbar
    /** Eingabeseite: Umlaut-Normalisierung, deutsche Füllwörter der Erkennung, Einordnung alter Log-Einträge. */
    private static final List<String> GERMAN_LINES = List.of(
            "chat/ChatText.java: return text.replace(\"ä\", \"ae\")",
            "CompanionBrain.java: \"das\", \"die\", \"den\", \"ein\", \"eine\"",
            "CompanionJobs.java: rest.removeIf(w -> Set.of(\"der\", \"die\", \"das\"",
            "CompanionCombat.java: if (text.contains(\"passiv\")",
            "CompanionActions.java: List<String> command = List.of(\"merk\", \"dir\", \"das\"",
            "debug/BotDebug.java: if (s.startsWith(\"Nehme\")",
            "debug/BotDebug.java: if (s.startsWith(\"Ich erinnere mich\")");

    @Test
    void keineDeutschenSatzLiteraleImModCode() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                if (GERMAN_FILES.contains(file.getFileName().toString())) continue;
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
                    int comment = line.indexOf(" // ");
                    if (comment >= 0) line = line.substring(0, comment);
                    if (!GERMAN.matcher(line).find()) continue;
                    String where = rel + ": " + line;
                    if (GERMAN_LINES.stream().noneMatch(where::startsWith)) found.add(rel + ":" + (i + 1) + ": " + line);
                }
            }
        }
        assertTrue(found.isEmpty(), "Deutsche Literale (in de_de.json verschieben):\n" + String.join("\n", found));
    }

    /** {@code Component.literal} mit festem Text nur für Satzzeichen; sonst Namen, Zahlen, Koordinaten. */
    @Test
    void componentLiteralNurFuerNamenUndZeichen() throws IOException {
        Set<String> allowed = Set.of("-", " ", "  ", ".", " (x");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                if (GERMAN_FILES.contains(file.getFileName().toString())) continue;
                Matcher m = Pattern.compile("Component\\.literal\\(\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"\\s*[)+]")
                        .matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) if (!allowed.contains(m.group(1))) found.add(file.getFileName() + ": \"" + m.group(1) + "\"");
            }
        }
        assertTrue(found.isEmpty(), "Feste Texte in Component.literal: " + found);
    }

    /** Seiten und Aktionen werden über ihre stabilen IDs übersetzt; eine neue Serveraktion braucht einen Eintrag. */
    @Test
    void jedeSeiteUndAktionHatEinenSchluessel() throws IOException {
        var de = load("de_de.json");
        List<String> missing = new ArrayList<>();
        String menu = Files.readString(MAIN.resolve("CompanionMenu.java"), StandardCharsets.UTF_8);
        Matcher pages = Pattern.compile("register\\(\\d+, \"([a-z_]+)\", \"").matcher(menu);
        int pageCount = 0;
        while (pages.find()) {
            pageCount++;
            if (!de.containsKey("gui.begleiter.page." + pages.group(1))) missing.add("page." + pages.group(1));
        }
        assertTrue(pageCount >= 9, "Seiten in CompanionMenu nicht gefunden");

        String actions = Files.readString(MAIN.resolve("CompanionActions.java"), StandardCharsets.UTF_8);
        Set<String> ids = new TreeSet<>();
        Matcher direct = Pattern.compile("register\\(\\d+, \"([a-z_]+)\"").matcher(actions);
        while (direct.find()) ids.add(direct.group(1));
        Matcher gather = Pattern.compile("String\\[] ids = \\{([^}]*)}").matcher(actions);
        assertTrue(gather.find(), "Sammel-IDs in CompanionActions nicht gefunden");
        for (String id : gather.group(1).replace("\"", "").split(",\\s*")) ids.add("gather_" + id.trim());
        String combat = Files.readString(MAIN.resolve("CompanionCombat.java"), StandardCharsets.UTF_8);
        Matcher stance = Pattern.compile("(?:AGGRESSIVE|DEFENSIVE|PASSIVE)\\(\"([a-z]+)\"").matcher(combat);
        int stances = 0;
        while (stance.find()) { ids.add("stance_" + stance.group(1)); stances++; }
        assertEquals(3, stances, "Kampfmodi in CompanionCombat nicht gefunden");
        assertTrue(ids.size() > 30, "Zu wenige Aktionen erkannt: " + ids);
        for (String id : ids) if (!de.containsKey("gui.begleiter.action." + id)) missing.add("action." + id);
        assertTrue(missing.isEmpty(), "Ohne Übersetzung: " + missing);
    }
}
