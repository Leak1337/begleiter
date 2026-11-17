package dev.begleiter.nav;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavBudgetTest {
    private static final long MS = 1_000_000L;
    private final AtomicLong clock = new AtomicLong();
    private final NavBudget budget = new NavBudget(6 * MS, clock::get);

    private NavBudget.Work spend(long nanos, boolean finished) {
        return deadline -> { clock.addAndGet(nanos); return finished; };
    }

    @Test void exhaustedBudgetNeverStartsAnotherSearch() {
        assertTrue(budget.run(new Object(), false, 10 * MS, deadline -> {
            assertEquals(6 * MS, deadline);
            clock.set(deadline);
            return false;
        }));
        assertFalse(budget.run(new Object(), false, MS, deadline -> { fail("Budget überschritten"); return true; }));
        assertEquals(6 * MS, budget.snapshot().usedNanos());
        assertEquals(1, budget.snapshot().deferred());
    }

    @Test void overshootingSearchesCannotStarveLaterCompanions() {
        Object[] actors = {new Object(), new Object(), new Object(), new Object(), new Object()};
        int[] progress = new int[actors.length];
        for (int tick = 0; tick < 15; tick++) {
            if (tick > 0) budget.beginTick();
            for (int i = 0; i < actors.length; i++) {
                final int actor = i;
                budget.run(actors[i], false, 6 * MS, deadline -> {
                    assertTrue(budget.snapshot().usedNanos() < 6 * MS, "Arbeit nach Budgetende begonnen");
                    progress[actor]++;
                    clock.addAndGet(7 * MS); // Einzelner Knoten ist nicht unterbrechbar.
                    return false;
                });
            }
            assertEquals(7 * MS, budget.snapshot().usedNanos());
            assertEquals(MS, budget.snapshot().overshootNanos());
        }
        for (int advances : progress) assertEquals(3, advances, "Feste Aufrufreihenfolge benachteiligt einen Begleiter");
        assertTrue(budget.snapshot().maxWaitTicks() <= actors.length);
    }

    @Test void reservationsProtectAllPreviouslyWaitingSearches() {
        Object[] actors = {new Object(), new Object(), new Object(), new Object(), new Object()};
        budget.run(actors[0], false, 6 * MS, spend(6 * MS, false));
        for (int i = 1; i < actors.length; i++) assertFalse(budget.run(actors[i], false, 6 * MS, spend(0, false)));
        budget.beginTick();
        assertFalse(budget.run(actors[0], false, 6 * MS, spend(0, false)));
        for (int i = 1; i < actors.length; i++) {
            assertTrue(budget.run(actors[i], false, 6 * MS, deadline -> {
                long slice = deadline - clock.get();
                assertTrue(slice >= MS && slice <= 3 * MS);
                clock.set(deadline);
                return false;
            }));
        }
        assertEquals(6 * MS, budget.snapshot().usedNanos());
    }

    @Test void prefetchYieldsToActiveWaitersEvenWhenCalledFirst() {
        Object active = new Object(), prefetch = new Object();
        budget.run(active, false, 6 * MS, spend(6 * MS, false));
        assertFalse(budget.run(prefetch, true, MS, spend(0, false)));
        budget.beginTick();
        assertFalse(budget.run(prefetch, true, MS, spend(0, false)));
        assertTrue(budget.run(active, false, MS, spend(MS, true)));
        assertTrue(budget.run(prefetch, true, MS, spend(MS, false)));
    }

    @Test void cancellationReleasesReservedTimeAndReferences() {
        Object first = new Object(), second = new Object();
        budget.run(first, false, 6 * MS, spend(6 * MS, false));
        budget.run(second, false, 6 * MS, spend(0, false));
        budget.beginTick();
        budget.cancel(first);
        assertTrue(budget.run(second, false, 6 * MS, deadline -> {
            assertEquals(6 * MS, deadline - clock.get());
            return true;
        }));
        assertEquals(0, budget.snapshot().tracked());
    }

    @Test void exceptionsStillConsumeTimeAndReleaseTheSearchScope() {
        Object failed = new Object(), waiting = new Object();
        assertThrows(IllegalStateException.class, () -> budget.run(failed, false, 6 * MS, deadline -> {
            clock.addAndGet(8 * MS);
            throw new IllegalStateException("Testfehler");
        }));
        assertEquals(2 * MS, budget.snapshot().overshootNanos());
        assertFalse(budget.run(waiting, false, MS, spend(0, true)));
        budget.beginTick();
        budget.cancel(failed);
        assertTrue(budget.run(waiting, false, MS, spend(MS, true)));
        assertEquals(8 * MS, budget.lastTick().usedNanos());
    }

    @Test void repeatedCallsCannotMultiplyOneRequestsTickAllowance() {
        Object token = new Object();
        assertTrue(budget.run(token, false, MS, spend(MS, false)));
        for (int i = 0; i < 100; i++) assertFalse(budget.run(token, false, MS, spend(MS, false)));
        assertEquals(MS, budget.snapshot().usedNanos());
        budget.beginTick();
        assertTrue(budget.run(token, false, MS, spend(MS, false)));
    }

    @Test void inactiveRequestsExpireAndDoNotBlockNewWork() {
        Object abandoned = new Object(), fresh = new Object();
        budget.run(abandoned, false, MS, spend(MS, false));
        budget.beginTick();
        budget.beginTick();
        assertTrue(budget.run(fresh, false, 6 * MS, spend(6 * MS, true)));
        budget.beginTick();
        assertEquals(0, budget.snapshot().tracked());
    }

    @Test void independentInstancesDoNotShareTimeOrWaiters() {
        var other = new NavBudget(6 * MS, clock::get);
        Object equalA = new String("token"), equalB = new String("token");
        budget.run(equalA, false, 6 * MS, spend(6 * MS, false));
        assertTrue(other.run(equalB, false, MS, spend(MS, false)));
        assertEquals(MS, other.snapshot().usedNanos());
        assertEquals(6 * MS, budget.snapshot().usedNanos());
        budget.clear();
        assertEquals(0, budget.snapshot().tracked());
        assertEquals(1, other.snapshot().tracked());
    }

    @Test void invalidAndZeroQuotasDoNotRunOrRetainWork() {
        assertThrows(IllegalArgumentException.class, () -> new NavBudget(0, clock::get));
        assertFalse(budget.run(new Object(), false, 0, spend(MS, false)));
        assertFalse(budget.run(new Object(), false, -1, spend(MS, false)));
        assertEquals(0, budget.snapshot().tracked());
        assertEquals(0, clock.get());
    }
}
