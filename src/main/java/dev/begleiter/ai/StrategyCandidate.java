package dev.begleiter.ai;

import dev.begleiter.CompanionNeeds;

/** Unveränderliche, begrenzte Eingabe für die Rangfolge der selbstständigen Bedürfnisse. */
public record StrategyCandidate(
        CompanionNeeds.Score need,
        int benefit,
        int successChance,
        int compatibility,
        int risk,
        int cost) {
    public StrategyCandidate {
        benefit = clamp(benefit);
        successChance = clamp(successChance);
        compatibility = clamp(compatibility);
        risk = clamp(risk);
        cost = clamp(cost);
    }
    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
