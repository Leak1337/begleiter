package dev.begleiter.ai;

/** Reine, deterministische Nutzwertung. Eingaben sind auf [0, 100] normiert. */
public final class StrategyEvaluator {
    private StrategyEvaluator() {}

    public static double score(StrategyCandidate c) {
        return 0.35 * c.need().value()
                + 0.25 * c.benefit()
                + 0.15 * c.successChance()
                + 0.10 * c.compatibility()
                - 0.10 * c.risk()
                - 0.05 * c.cost();
    }
}
