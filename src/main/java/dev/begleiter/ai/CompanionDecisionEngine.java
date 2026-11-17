package dev.begleiter.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionNeeds;
import dev.begleiter.CompanionSituation;

/**
 * Vorsichtige Einbindung ohne eigene Aufgabenausführung oder Navigation.
 * Im Schattenmodus werden beide Rangfolgen verglichen; eingeschaltet ändert sie nur die Reihenfolge
 * unkritischer ORDER- und SUPPLY-Bedürfnisse. Sicherheit und alle anderen behalten die klassische Reihenfolge.
 */
public final class CompanionDecisionEngine {
    private CompanionDecisionEngine() {}

    private static final String PROPERTY = "begleiter.intelligence.enabled";
    private static final boolean SHADOW = Boolean.getBoolean("begleiter.intelligence.shadow");
    /** null = noch nicht geladen. Serverweit; Standard an. */
    private static volatile Boolean enabled;

    /**
     * Intelligence 2.0 aktiv? Reihenfolge: JVM-Property (Tests/Server-Start) vor gespeicherter
     * Menü-Einstellung vor Standard (an).
     */
    public static boolean enabled() {
        Boolean value = enabled;
        if (value == null) enabled = value = load();
        return value;
    }

    /** Umschalten zur Laufzeit; persist schreibt die Einstellung für den nächsten Start. */
    public static void setEnabled(boolean on, boolean persist) {
        enabled = on;
        if (persist) save(on);
    }

    private static java.nio.file.Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("begleiter-intelligence.json");
    }

    private static boolean load() {
        String property = System.getProperty(PROPERTY);
        if (property != null) return Boolean.parseBoolean(property);
        try {
            var path = file();
            if (!java.nio.file.Files.isRegularFile(path) || java.nio.file.Files.size(path) > 4096) return true;
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
            return !json.has("enabled") || !json.get("enabled").isJsonPrimitive() || json.get("enabled").getAsBoolean();
        } catch (java.io.IOException | RuntimeException ex) {
            return true; // beschädigte Datei: Standard
        }
    }

    private static void save(boolean on) {
        try {
            var path = file();
            java.nio.file.Files.createDirectories(path.getParent());
            var json = new com.google.gson.JsonObject();
            json.addProperty("enabled", on);
            java.nio.file.Files.writeString(path, json + "\n");
        } catch (java.io.IOException | RuntimeException ex) {
            // ohne Schreibrechte gilt die Einstellung nur bis zum Neustart
        }
    }

    public static List<CompanionNeeds.Score> rank(CompanionEntity entity, List<CompanionNeeds.Score> legacy) {
        if (legacy.isEmpty()) return legacy;
        List<CompanionNeeds.Score> sorted = new ArrayList<>(legacy);
        sorted.sort(Comparator.comparingInt((CompanionNeeds.Score s) ->
                -(s.value() + (s.need() == entity.goal ? CompanionNeeds.STICKY : 0))));
        boolean on = enabled();
        if (!on && !SHADOW) return sorted;

        // Reorder only contiguous eligible groups. Never jump across critical needs.
        List<CompanionNeeds.Score> alternative = reorderEligible(sorted, score -> utility(entity, score));
        if (SHADOW && !sorted.equals(alternative) && entity.tickCount % 200 < 20) {
            entity.consoleDebug("Intelligence shadow: legacy=" + sorted.getFirst().need()
                    + ", candidate=" + alternative.getFirst().need());
        }
        return on ? alternative : sorted;
    }

    private static boolean eligible(CompanionNeeds.Score s) {
        return s.need() == CompanionNeeds.Need.ORDER || s.need() == CompanionNeeds.Need.SUPPLY;
    }

    private static double utility(CompanionEntity entity, CompanionNeeds.Score s) {
        return StrategyEvaluator.score(CompanionSituation.candidate(entity, s))
                - entity.experiences.penalty(s.need(), entity.tickCount);
    }
    /** Pure ranking helper, intentionally cannot move across protected needs. */
    public static List<CompanionNeeds.Score> reorderEligible(
            List<CompanionNeeds.Score> ranked, ToDoubleFunction<CompanionNeeds.Score> utility) {
        List<CompanionNeeds.Score> result = new ArrayList<>(ranked);
        for (int start = 0; start < result.size();) {
            if (!eligible(result.get(start))) { start++; continue; }
            int end = start + 1;
            while (end < result.size() && eligible(result.get(end))) end++;
            for (int i = start; i < end; i++) {
                for (int j = i + 1; j < end; j++) {
                    if (utility.applyAsDouble(result.get(j)) > utility.applyAsDouble(result.get(i)) + 5) {
                        CompanionNeeds.Score candidate = result.remove(j);
                        result.add(i, candidate);
                        break;
                    }
                }
            }
            start = end;
        }
        return result;
    }

    public record SupplyOption(String resourceKey, int missing) {}

    /** Nach jüngsten Fehlschlägen eine andere benötigte Ressource vorziehen, sonst klassische Reihenfolge. */
    public static SupplyOption chooseSupply(List<SupplyOption> options,
                                            ToIntFunction<SupplyOption> penalty) {
        SupplyOption best = null;
        int bestPenalty = Integer.MAX_VALUE;
        for (SupplyOption option : options) {
            int value = Math.max(0, penalty.applyAsInt(option));
            if (value < bestPenalty) {
                best = option;
                bestPenalty = value;
            }
        }
        // Everything has repeatedly failed: explore rather than immediately retrying.
        return bestPenalty >= 24 ? null : best;
    }

    /** Context-aware opt-in selection; the two-argument helper retains legacy tie ordering. */
    public static SupplyOption chooseSupply(List<SupplyOption> options,
                                            ToIntFunction<SupplyOption> penalty,
                                            ToDoubleFunction<SupplyOption> utility) {
        SupplyOption best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (SupplyOption option : options) {
            int failure = Math.max(0, penalty.applyAsInt(option));
            if (failure >= 24) continue;
            double value = utility.applyAsDouble(option) - failure * 2.0;
            if (Double.isFinite(value) && value > bestValue) {
                best = option;
                bestValue = value;
            }
        }
        return best;
    }

}
