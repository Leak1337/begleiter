package dev.begleiter.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kompakter Zustand eines Begleiters als Kontext für das LLM. Reine Daten ohne Minecraft-Abhängigkeit; die Welt
 * liest {@code CompanionLlmContext}. Groqs Free-Tier erlaubt 8000 Tokens pro Minute, daher hart begrenzt:
 * Listen gekürzt, leere Zeilen entfallen, Gesamttext höchstens {@link #MAX_CHARS} Zeichen. Keine Koordinaten
 * anderer Spieler, nur Entfernung und Richtung zum Besitzer.
 *
 * @param inventory Item-ID ohne Namensraum → Anzahl (wird nach Anzahl sortiert und gekürzt)
 * @param threats   Mob-ID → Anzahl feindlicher Mobs in der Nähe
 * @param places    erinnerte Orte je Art, z.B. "Erz eisen" → 3
 */
public record ContextSnapshot(String mode, int health, int maxHealth, int hunger, String dimension, boolean night,
        boolean rain, int ownerDistance, List<String> tasks, int taskCount, Map<String, Integer> inventory,
        List<String> equipment, Map<String, Integer> threats, String hazard, boolean hasHome, String build,
        List<String> waypoints, Map<String, Integer> places, String lastPlan, String problem) {

    public static final int MAX_CHARS = 900;
    static final int MAX_TASKS = 3, MAX_ITEMS = 12, MAX_LIST = 6, MAX_TEXT = 80;

    public ContextSnapshot {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        inventory = inventory == null ? Map.of() : Map.copyOf(inventory);
        equipment = equipment == null ? List.of() : List.copyOf(equipment);
        threats = threats == null ? Map.of() : Map.copyOf(threats);
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
        places = places == null ? Map.of() : Map.copyOf(places);
    }

    /** Eine Zeile je Angabe, englisch; Namen aus dem Spiel (Wegpunkte, Rohstoff-IDs) bleiben wie gespeichert. */
    public String render() {
        List<String> lines = new ArrayList<>();
        lines.add("mode: " + text(mode));
        lines.add("health " + health + "/" + maxHealth + ", hunger " + hunger + "/20, " + text(dimension)
                + (night ? ", night" : ", day") + (rain ? ", rain" : ""));
        if (ownerDistance >= 0) lines.add("player distance: " + ownerDistance);
        if (taskCount > 0) {
            List<String> shown = tasks.stream().limit(MAX_TASKS).map(ContextSnapshot::text).toList();
            lines.add("tasks (" + taskCount + "): " + String.join("; ", shown) + (taskCount > shown.size() ? "; …" : ""));
        } else {
            lines.add("tasks: none");
        }
        lines.add("inventory: " + (inventory.isEmpty() ? "empty" : counts(inventory, MAX_ITEMS)));
        if (!equipment.isEmpty()) lines.add("equipment: " + joined(equipment));
        if (!threats.isEmpty()) lines.add("hostile nearby: " + counts(threats, MAX_LIST));
        if (hazard != null && !hazard.isBlank()) lines.add("hazard: " + text(hazard));
        lines.add("home storage: " + (hasHome ? "set" : "not set"));
        if (build != null && !build.isBlank()) lines.add("building: " + text(build));
        if (!waypoints.isEmpty()) lines.add("waypoints: " + joined(waypoints));
        if (!places.isEmpty()) lines.add("remembered: " + counts(places, MAX_LIST));
        if (lastPlan != null && !lastPlan.isBlank()) lines.add("last own plan: " + text(lastPlan));
        if (problem != null && !problem.isBlank()) lines.add("problem: " + text(problem));

        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() + line.length() + 1 > MAX_CHARS) break; // spätere Zeilen sind die unwichtigeren
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String joined(List<String> values) {
        List<String> shown = values.stream().limit(MAX_LIST).map(ContextSnapshot::text).toList();
        return String.join(", ", shown) + (values.size() > shown.size() ? ", …" : "");
    }

    /** "torch 32, cobblestone 20, …": größte Mengen zuerst, bei Gleichstand alphabetisch (stabile Ausgabe). */
    static String counts(Map<String, Integer> values, int max) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(values.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));
        Map<String, Integer> shown = new LinkedHashMap<>();
        for (var e : sorted) if (shown.size() < max) shown.put(e.getKey(), e.getValue());
        List<String> parts = new ArrayList<>();
        shown.forEach((k, v) -> parts.add(text(k) + " " + v));
        return String.join(", ", parts) + (sorted.size() > shown.size() ? ", …" : "");
    }

    /** Koordinaten in Aufgaben-, Plan- und Problemtexten ("12, 64, -30", "12 64 -30", "BlockPos{x=…}"). */
    private static final java.util.regex.Pattern COORDINATES = java.util.regex.Pattern.compile(
            "BlockPos\\{[^}]{0,60}}|(?<![\\w.-])-?\\d{1,8}(?:, ?| )-?\\d{1,8}(?:, ?| )-?\\d{1,8}(?![\\w.])");

    /** Freitext ohne Koordinaten: der Dienst bekommt nie Positionen, nur die Entfernung zum Besitzer. */
    static String withoutCoordinates(String value) {
        return COORDINATES.matcher(value).replaceAll("(pos)");
    }

    private static String text(String value) {
        if (value == null) return "-";
        String flat = withoutCoordinates(value.replaceAll("\\s+", " ")).strip();
        return flat.length() <= MAX_TEXT ? flat : flat.substring(0, MAX_TEXT) + "…";
    }
}
