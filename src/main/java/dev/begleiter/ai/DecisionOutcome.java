package dev.begleiter.ai;

import dev.begleiter.CompanionNeeds;

/**
 * Typisiertes Fehlersignal aus der Aufgabenausführung.
 * Ein Abbruch durch den Besitzer ist bewusst weder Fehler noch Lernsignal.
 */
public record DecisionOutcome(
        CompanionNeeds.Need need,
        Reason reason,
        String target,
        int tick) {
    public enum Reason {
        STALLED,
        UNREACHABLE,
        PLAN_FAILED,
        SOURCE_EMPTY,
        SOURCE_PARTIAL,
        SOURCE_MISSING,
        INVENTORY_FULL,
        MISSING_TOOL,
        MISSING_INPUT,
        MISSING_FUEL,
        MISSING_STATION,
        ROUTE_UNSAFE,
        ROUTE_UNPROVEN;

        /** Fehlende eigene Voraussetzungen sagen nichts über Ressource oder Beschaffungsart aus. */
        public boolean preparationOnly() {
            return this == INVENTORY_FULL || this == MISSING_TOOL || this == MISSING_INPUT || this == MISSING_FUEL || this == MISSING_STATION
                    || this == ROUTE_UNPROVEN;
        }

        /** Scheitert eine bestimmte Quelle, sind Ressource und Beschaffungsart deshalb nicht schlecht. */
        public boolean sourceOnly() {
            return this == SOURCE_EMPTY || this == SOURCE_PARTIAL || this == SOURCE_MISSING;
        }

        public boolean generic() { return this == PLAN_FAILED || this == STALLED; }
    }
}
