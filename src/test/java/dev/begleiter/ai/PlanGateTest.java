package dev.begleiter.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** höchstens eine selbstständige Planung je Tick, Wartende kommen der Reihe nach dran. */
class PlanGateTest {
    private final UUID a = new UUID(0, 1), b = new UUID(0, 2), c = new UUID(0, 3);

    @BeforeEach
    void reset() { PlanGate.reset(); }

    @Test
    void onlyOnePlanPerTick() {
        assertTrue(PlanGate.tryEnter(100, a));
        assertFalse(PlanGate.tryEnter(100, b));
        assertFalse(PlanGate.tryEnter(100, c));
        assertFalse(PlanGate.tryEnter(100, a), "same companion twice in one tick");
    }

    @Test
    void theFirstDeferredCompanionGoesNextEvenIfOthersAskFirst() {
        assertTrue(PlanGate.tryEnter(1, a));
        assertFalse(PlanGate.tryEnter(1, b)); // b wartet jetzt
        assertFalse(PlanGate.tryEnter(2, c), "c overtook the waiting b");
        assertTrue(PlanGate.tryEnter(2, b));
        assertTrue(PlanGate.tryEnter(3, c));
    }

    @Test
    void aWaiterThatStopsAskingLosesItsTurn() {
        assertTrue(PlanGate.tryEnter(1, a));
        assertFalse(PlanGate.tryEnter(1, b));
        // b fragt in Tick 2 und 3 nicht mehr (hat einen Auftrag bekommen)
        assertTrue(PlanGate.tryEnter(4, c));
    }

    @Test
    void fiveCompanionsAllGetATurnWithinFiveTicks() {
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) all.add(new UUID(1, i));
        List<UUID> served = new ArrayList<>();
        for (long tick = 10; tick < 15; tick++)
            for (UUID who : all)
                if (!served.contains(who) && PlanGate.tryEnter(tick, who)) served.add(who);
        assertTrue(served.containsAll(all), "not everyone planned: " + served.size());
    }
}
