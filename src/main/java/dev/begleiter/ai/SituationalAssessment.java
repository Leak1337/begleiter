package dev.begleiter.ai;

import dev.begleiter.CompanionNeeds;

/** Begrenzte Faustregeln, keine kalibrierten Wahrscheinlichkeiten und kein Ersatz für die Wegprüfung. */
public final class SituationalAssessment {
    private SituationalAssessment() {}

    public record Context(int healthPercent, int hunger, int freeSlots, int capacityPercent,
                          int deficitPercent, int workUnits, int toolTier, int requiredToolTier,
                          int durabilityPercent, double distance, boolean knownTarget,
                          boolean knownDanger, boolean exposedAtNight) {
        public Context {
            healthPercent = clamp(healthPercent);
            hunger = Math.max(0, Math.min(20, hunger));
            freeSlots = Math.max(0, Math.min(36, freeSlots));
            capacityPercent = clamp(capacityPercent);
            deficitPercent = clamp(deficitPercent);
            workUnits = clamp(workUnits);
            toolTier = Math.max(0, Math.min(5, toolTier));
            requiredToolTier = Math.max(0, Math.min(5, requiredToolTier));
            durabilityPercent = clamp(durabilityPercent);
            distance = Double.isFinite(distance) ? Math.max(0, Math.min(128, distance)) : 128;
        }
    }

    public static StrategyCandidate assess(CompanionNeeds.Score need, Context c, boolean sameGoal) {
        int travel = (int) Math.ceil(c.knownTarget() ? c.distance() : 64);
        int weakness = 100 - c.healthPercent();
        int hunger = 20 - c.hunger();
        int toolGap = Math.max(0, c.requiredToolTier() - c.toolTier());
        int wear = c.toolTier() > 0 ? Math.max(0, 25 - c.durabilityPercent()) : 0;
        int inventoryPressure = (36 - c.freeSlots()) * 100 / 36;
        int benefit = Math.max(need.value(), need.need() == CompanionNeeds.Need.SUPPLY
                ? c.deficitPercent() : inventoryPressure);
        int success = 100 - travel / 4 - (c.knownTarget() ? 0 : 20)
                - toolGap * 30 - wear - weakness / 4 - hunger
                - (100 - c.capacityPercent()) / 2 - (c.knownDanger() ? 25 : 0)
                - (c.exposedAtNight() ? 10 : 0);
        int risk = weakness / 2 + hunger + travel / 8 + toolGap * 10 + wear
                + (c.knownDanger() ? 40 : 0) + (c.exposedAtNight() ? 20 : 0);
        int cost = travel / 2 + c.workUnits() * Math.max(1, 4 - c.toolTier())
                + toolGap * 15 + wear + (c.knownTarget() ? 0 : 20)
                + (100 - c.capacityPercent()) / 2;
        return new StrategyCandidate(need, benefit, success, sameGoal ? 100 : 50, risk, cost);
    }

    private static int clamp(int n) { return Math.max(0, Math.min(100, n)); }
}
