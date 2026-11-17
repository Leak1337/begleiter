package dev.begleiter.ai;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import dev.begleiter.CompanionNeeds;

/**
 * Bounded, persistence-safe feedback for autonomous goals.
 * It never punishes player orders or explicit cancellations.
 */
public final class CompanionExperienceMemory {
    private static final int VERSION = 1;
    private static final int MAX_FAILURES = 5;
    private static final int PENALTY_TICKS = 6000;
    public final CompanionSourceMemory sources = new CompanionSourceMemory();
    private final Map<CompanionNeeds.Need, Failure> failures =
            new EnumMap<>(CompanionNeeds.Need.class);

    private record Failure(int count, int tick) {}

    /** Fehlschläge im Zusammenhang sind nach (Bedürfnis, Ziel) gespeichert, nie nach Weltkoordinaten. */
    private static final int MAX_TARGETS = 32;
    private final Map<String, Failure> targetFailures = new LinkedHashMap<>();
    private final Map<String, DecisionOutcome.Reason> lastReasons = new LinkedHashMap<>();
    /** Long-lived, bounded route preferences (no world coordinates or player chat). */
    private final Map<String, RouteRecord> routeRecords = new LinkedHashMap<>();
    private record RouteRecord(int successes, int failures) {}
    private static final List<String> METHODS = List.of("chest", "mining", "crafting", "hunting");

    private static boolean isMethod(String key) {
        return key.endsWith("_chest") || key.endsWith("_mining")
                || key.endsWith("_crafting") || key.endsWith("_hunting");
    }

    private void learnRoute(String key, boolean success) {
        if (!isMethod(key)) return;
        RouteRecord old = routeRecords.getOrDefault(key, new RouteRecord(0, 0));
        // Keep learning responsive after many successes/failures.
        if (old.successes() + old.failures() >= 16)
            old = new RouteRecord(old.successes() / 2, old.failures() / 2);
        if (!routeRecords.containsKey(key) && routeRecords.size() >= MAX_TARGETS)
            routeRecords.remove(routeRecords.keySet().iterator().next());
        routeRecords.put(key, new RouteRecord(
                Math.min(12, old.successes() + (success ? 1 : 0)),
                Math.min(12, old.failures() + (success ? 0 : 1))));
    }

    /** Nur eine kleine Langzeitvorliebe; jüngste Fehlschläge werden weiter getrennt behandelt. */
    public int learnedPenalty(CompanionNeeds.Need need, String target) {
        return Math.max(0, -learnedPreference(need, target));
    }

    /** Kleine Vorliebe mit Vorzeichen; nie ein harter Ausschluss und kein Ersatz für das aktuelle Risiko. */
    public int learnedPreference(CompanionNeeds.Need need, String target) {
        if (need == null || target == null) return 0;
        RouteRecord stats = routeRecords.get(targetKey(need, target));
        return stats == null ? 0 : Math.max(-5, Math.min(5, stats.successes() - stats.failures()));
    }

    public boolean hasRouteExperience(CompanionNeeds.Need need, String resource) {
        if (need == null || resource == null) return false;
        return METHODS.stream().anyMatch(method -> routeRecords.containsKey(targetKey(need, resource + "_" + method)));
    }

    /** Begrenzter Hinweis für die Ressourcenwahl; die tatsächlich möglichen Wege werden getrennt geprüft. */
    public int bestLearnedPreference(CompanionNeeds.Need need, String resource) {
        if (!hasRouteExperience(need, resource)) return 0;
        int best = -5;
        for (String method : METHODS) {
            String target = resource + "_" + method;
            if (routeRecords.containsKey(targetKey(need, target)))
                best = Math.max(best, learnedPreference(need, target));
        }
        return best;
    }

    public int learnedProfiles() { return routeRecords.size(); }

    private static String targetKey(CompanionNeeds.Need need, String target) {
        return need.name() + ":" + target;
    }

    public void record(DecisionOutcome outcome) {
        if (outcome == null || outcome.need() == null) return;
        if (outcome.reason() != null && (outcome.reason().preparationOnly() || outcome.reason().sourceOnly())) return;
        failed(outcome.need(), outcome.tick());
        recordTargetOnly(outcome);
    }

    /** Fehlschlag einer Beschaffungsart festhalten, ohne das Scheitern des ganzen Ziels doppelt zu zählen. */
    public void recordTargetOnly(DecisionOutcome outcome) {
        if (outcome != null && outcome.reason() != null && (outcome.reason().preparationOnly() || outcome.reason().sourceOnly())) return;
        if (outcome == null || outcome.need() == null
                || outcome.target() == null || outcome.target().isBlank()) return;
        // Gespeichert werden nur einfache, begrenzte Kennungen (Ressourcenschlüssel, kein Chattext).
        String target = outcome.target();
        if (target.length() > 64 || !target.matches("[a-z0-9_]+")) return;
        String key = targetKey(outcome.need(), target);
        Failure old = targetFailures.get(key);
        int count = old == null || outcome.tick() - old.tick > PENALTY_TICKS
                ? 1 : Math.min(MAX_FAILURES, old.count + 1);
        if (!targetFailures.containsKey(key) && targetFailures.size() >= MAX_TARGETS) {
            String oldest = targetFailures.keySet().iterator().next();
            targetFailures.remove(oldest);
            lastReasons.remove(oldest);
        }
        targetFailures.put(key, new Failure(count, outcome.tick()));
        lastReasons.put(key, outcome.reason());
        learnRoute(key, false);
    }

    public int penalty(CompanionNeeds.Need need, String target, int tick) {
        if (need == null || target == null) return 0;
        return penaltyFor(targetFailures.get(targetKey(need, target)), tick);
    }

    public DecisionOutcome.Reason lastReason(CompanionNeeds.Need need, String target) {
        if (need == null || target == null) return null;
        return lastReasons.get(targetKey(need, target));
    }

    public void succeeded(CompanionNeeds.Need need, String target) {
        succeeded(need);
        if (need != null && target != null) {
            String key = targetKey(need, target);
            targetFailures.remove(key);
            lastReasons.remove(key);
            learnRoute(key, true);
        }
    }

    private static int penaltyFor(Failure f, int tick) {
        if (f == null || tick < f.tick || tick - f.tick >= PENALTY_TICKS) return 0;
        int remaining = PENALTY_TICKS - (tick - f.tick);
        return (int) ((long) f.count * 12 * remaining / PENALTY_TICKS);
    }

    public void failed(CompanionNeeds.Need need, int tick) {
        if (need == null) return;
        Failure old = failures.get(need);
        int count = old == null || tick - old.tick > PENALTY_TICKS
                ? 1 : Math.min(MAX_FAILURES, old.count + 1);
        failures.put(need, new Failure(count, tick));
    }

    public void succeeded(CompanionNeeds.Need need) {
        if (need != null) failures.remove(need);
    }

    /** Penalty fades automatically, so the NPC will eventually retry. */
    public int penalty(CompanionNeeds.Need need, int tick) {
        return penaltyFor(failures.get(need), tick);
    }
    /**
     * Store remaining penalty duration, not absolute entity tickCount.
     * Entity tickCount restarts after respawn/load; raw tick timestamps would
     * cause penalties to disappear immediately or become artificially permanent.
     */
    public CompoundTag save(int now) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", VERSION);
        ListTag general = new ListTag();
        for (var entry : failures.entrySet()) {
            int remaining = remaining(entry.getValue(), now);
            if (remaining == 0) continue;
            CompoundTag row = new CompoundTag();
            row.putString("Need", entry.getKey().name());
            row.putInt("Count", entry.getValue().count());
            row.putInt("Remaining", remaining);
            general.add(row);
        }
        root.put("General", general);

        ListTag targets = new ListTag();
        for (var entry : targetFailures.entrySet()) {
            int remaining = remaining(entry.getValue(), now);
            if (remaining == 0) continue;
            CompoundTag row = new CompoundTag();
            row.putString("Key", entry.getKey());
            row.putInt("Count", entry.getValue().count());
            row.putInt("Remaining", remaining);
            var reason = lastReasons.get(entry.getKey());
            if (reason != null) row.putString("Reason", reason.name());
            targets.add(row);
        }
        root.put("Targets", targets);

        ListTag profiles = new ListTag();
        for (var entry : routeRecords.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString("Key", entry.getKey());
            row.putInt("Successes", entry.getValue().successes());
            row.putInt("Failures", entry.getValue().failures());
            profiles.add(row);
        }
        root.put("Profiles", profiles);
        root.put("Sources", sources.save(now));
        return root;
    }

    /** Optionales NBT, auch ältere Spielstände ohne Experience-Schlüssel, wird sicher gelesen. */
    public void load(CompoundTag root, int now) {
        failures.clear();
        targetFailures.clear();
        lastReasons.clear();
        routeRecords.clear();
        sources.load(new ListTag(), now);
        if (root.getIntOr("Version", 0) != VERSION) return;
        int inspectedGeneral = 0;
        for (Tag tag : root.getListOrEmpty("General")) {
            if (++inspectedGeneral > CompanionNeeds.Need.values().length * 4) break;
            if (!(tag instanceof CompoundTag row)) continue;
            try {
                var need = CompanionNeeds.Need.valueOf(row.getStringOr("Need", ""));
                Failure failure = restored(row, now);
                if (failure != null) failures.put(need, failure);
            } catch (IllegalArgumentException ignored) {
                // Never trust external NBT enum values.
            }
        }
        int inspectedTargets = 0;
        for (Tag tag : root.getListOrEmpty("Targets")) {
            if (++inspectedTargets > MAX_TARGETS * 4 || targetFailures.size() >= MAX_TARGETS) break;
            if (!(tag instanceof CompoundTag row)) continue;
            String key = row.getStringOr("Key", "");
            int divider = key.indexOf(':');
            if (divider <= 0 || divider == key.length() - 1) continue;
            try {
                CompanionNeeds.Need.valueOf(key.substring(0, divider));
            } catch (IllegalArgumentException ignored) { continue; }
            String target = key.substring(divider + 1);
            if (target.length() > 64 || !target.matches("[a-z0-9_]+")) continue;
            Failure failure = restored(row, now);
            if (failure == null) continue;
            targetFailures.put(key, failure);
            try {
                lastReasons.put(key, DecisionOutcome.Reason.valueOf(row.getStringOr("Reason", "")));
            } catch (IllegalArgumentException ignored) {
                // Older versions may not have a reason.
            }
        }
        restoreProfiles(root);
        sources.load(root.getListOrEmpty("Sources"), now);
    }

    // Gespeicherte Vorlieben kommen dazu und fehlen in älteren Spielständen einfach.
    /** Langzeitergebnisse von Wegen mit denselben strengen Grenzen laden wie kurzfristige Fehlschläge. */
    private void restoreProfiles(CompoundTag root) {
        int inspectedProfiles = 0;
        for (Tag tag : root.getListOrEmpty("Profiles")) {
            if (++inspectedProfiles > MAX_TARGETS * 4 || routeRecords.size() >= MAX_TARGETS) break;
            if (!(tag instanceof CompoundTag row)) continue;
            String key = row.getStringOr("Key", "");
            int split = key.indexOf(':');
            if (split <= 0 || split == key.length() - 1 || !isMethod(key)) continue;
            try {
                CompanionNeeds.Need.valueOf(key.substring(0, split));
            } catch (IllegalArgumentException ignored) { continue; }
            String target = key.substring(split + 1);
            if (target.length() > 64 || !target.matches("[a-z0-9_]+")) continue;
            int wins = Math.max(0, Math.min(12, row.getIntOr("Successes", 0)));
            int losses = Math.max(0, Math.min(12, row.getIntOr("Failures", 0)));
            if (wins > 0 || losses > 0) routeRecords.put(key, new RouteRecord(wins, losses));
        }
    }

    public int rememberedTargets() { return targetFailures.size(); }

    private static int remaining(Failure failure, int now) {
        if (failure == null || now < failure.tick()) return 0;
        return Math.max(0, PENALTY_TICKS - (now - failure.tick()));
    }

    private static Failure restored(CompoundTag row, int now) {
        int duration = row.getIntOr("Remaining", 0);
        if (duration <= 0) return null;
        int remaining = Math.min(PENALTY_TICKS, duration);
        int count = Math.max(1, Math.min(MAX_FAILURES, row.getIntOr("Count", 1)));
        return new Failure(count, now - (PENALTY_TICKS - remaining));
    }

}
