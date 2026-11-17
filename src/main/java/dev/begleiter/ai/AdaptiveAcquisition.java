package dev.begleiter.ai;

import java.util.List;
import java.util.function.ToIntFunction;
import java.util.function.ToDoubleFunction;

import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.CompanionPlanner;

/**
 * Wählt einen Weg aus dem Ressourcenplaner anhand aktueller Fehlschläge und Erfahrung.
 * Hier wird nichts ausgeführt. Eine kürzlich gescheiterte Beschaffungsart wird gemieden.
 */
public final class AdaptiveAcquisition {
    private AdaptiveAcquisition() {}

    public record Choice(CompanionPlanner.Plan plan, String approach, double adjustedCost) {}

    /** A task chain can combine methods; the first acquisition operation identifies its initial approach. */
    public static String approach(CompanionPlanner.Plan plan) {
        for (var task : plan.tasks()) {
            TaskType type = task.type();
            if (type == TaskType.TAKE) return "chest";
            if (type == TaskType.GATHER || type == TaskType.MINE || type == TaskType.TREE
                    || type == TaskType.BREAK) return "mining";
            if (type == TaskType.FIGHT || type == TaskType.HUNT) return "hunting";
        }
        return "crafting";
    }

    /**
     * Einen gescheiterten Beschaffungsschritt der Art zuordnen, die wirklich gescheitert ist.
     * Vorbereitungs- und Bewegungsaufgaben sagen nichts aus: die geplante Art bleibt,
     * statt der KI einen erfundenen Fehlschlag beizubringen.
     */
    public static String failedApproach(dev.begleiter.CompanionEntity.Task task, String plannedApproach) {
        if (task == null) return plannedApproach;
        return switch (task.type()) {
            case TAKE -> "chest";
            case GATHER, MINE, TREE, BREAK -> "mining";
            case HUNT, FIGHT -> "hunting";
            case CRAFT, SMELT, STATION -> "crafting";
            default -> plannedApproach;
        };
    }

    /** Returns null if every available route is temporarily disfavored. */
    public static Choice choose(List<CompanionPlanner.Alternative> alternatives,
                                ToIntFunction<String> failurePenalty) {
        return choose(alternatives, failurePenalty, plan -> 0);
    }

    /** Ein hartes Risiko ab 100 verbietet den Weg; kleinere Risiken erhöhen die geschätzten Kosten. */
    public static Choice choose(List<CompanionPlanner.Alternative> alternatives,
                                ToIntFunction<String> failurePenalty,
                                ToDoubleFunction<CompanionPlanner.Plan> riskPenalty) {
        return choose(alternatives, failurePenalty, method -> 0, riskPenalty);
    }

    /** Die Langzeitvorliebe ist auf ±5 begrenzt und kann weder eine Art verbieten noch ein hartes Risiko aufheben. */
    public static Choice choose(List<CompanionPlanner.Alternative> alternatives,
                                ToIntFunction<String> failurePenalty,
                                ToIntFunction<String> learnedPreference,
                                ToDoubleFunction<CompanionPlanner.Plan> riskPenalty) {
        Choice best = null;
        for (var alternative : alternatives) {
            String approach = approach(alternative.plan());
            int penalty = Math.max(0, failurePenalty.applyAsInt(approach));
            if (penalty >= 12) continue;
            double risk = Math.max(0, riskPenalty.applyAsDouble(alternative.plan()));
            if (!Double.isFinite(risk) || risk >= 100) continue;
            int preference = Math.max(-5, Math.min(5, learnedPreference.applyAsInt(approach)));
            double adjusted = Math.max(0, alternative.plan().cost() + penalty * 20.0 + risk - preference * 2.0);
            if (!Double.isFinite(adjusted)) continue;
            if (best == null || adjusted < best.adjustedCost()) {
                best = new Choice(alternative.plan(), approach, adjusted);
            }
        }
        return best;
    }
}
