package dev.begleiter.llm;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.begleiter.llm.LlmBudget.Verdict.*;

class LlmBudgetTest {
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final LlmBudget budget = new LlmBudget(3, 5, clock::get);

    @Test void oneOpenRequestPerOwnerButOwnersAreIndependent() {
        Object a = new String("begleiter"), b = new String("begleiter");
        assertEquals(OK, budget.tryAcquire(a));
        assertEquals(BUSY, budget.tryAcquire(a));
        assertEquals(OK, budget.tryAcquire(b), "gleiche equals, aber anderer Begleiter");
        budget.release(a);
        assertEquals(OK, budget.tryAcquire(a));
    }

    @Test void minuteWindowSlides() {
        for (int i = 0; i < 3; i++) {
            Object owner = new Object();
            assertEquals(OK, budget.tryAcquire(owner));
            budget.release(owner);
            clock.addAndGet(10_000);
        }
        assertEquals(MINUTE_LIMIT, budget.tryAcquire(new Object()));
        clock.addAndGet(30_001); // erste Anfrage ist jetzt älter als 60 s
        assertEquals(OK, budget.tryAcquire(new Object()));
    }

    @Test void dayLimitResetsOnNextDay() {
        for (int i = 0; i < 5; i++) {
            Object owner = new Object();
            assertEquals(OK, budget.tryAcquire(owner));
            budget.release(owner);
            clock.addAndGet(61_000);
        }
        assertEquals(DAY_LIMIT, budget.tryAcquire(new Object()));
        clock.addAndGet(86_400_000L);
        assertEquals(OK, budget.tryAcquire(new Object()));
        assertEquals(1, budget.snapshot().today());
    }

    @Test void rateLimitBacksOffExponentiallyAndRespectsRetryAfter() {
        budget.rateLimited(0);
        assertEquals(BACKOFF, budget.tryAcquire(new Object()));
        assertEquals(5_000, budget.snapshot().backoffRemainingMs());
        clock.addAndGet(5_000);
        budget.rateLimited(0);
        assertEquals(10_000, budget.snapshot().backoffRemainingMs());
        clock.addAndGet(10_000);
        budget.succeeded();
        budget.rateLimited(0);
        assertEquals(5_000, budget.snapshot().backoffRemainingMs(), "Erfolg setzt die Pause zurück");
        clock.addAndGet(5_000);
        budget.rateLimited(42_000);
        assertEquals(42_000, budget.snapshot().backoffRemainingMs());
        budget.rateLimited(999_999_999L);
        assertEquals(300_000, budget.snapshot().backoffRemainingMs(), "Retry-After ist gedeckelt");
    }

    @Test void deniedRequestsDoNotCount() {
        Object busy = new Object();
        budget.tryAcquire(busy);
        for (int i = 0; i < 10; i++) assertEquals(BUSY, budget.tryAcquire(busy));
        assertEquals(1, budget.snapshot().today());
        assertEquals(1, budget.snapshot().inFlight());
    }

    @Test void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LlmBudget(0, 1, clock::get));
        assertThrows(IllegalArgumentException.class, () -> new LlmBudget(1, 0, clock::get));
    }
}
