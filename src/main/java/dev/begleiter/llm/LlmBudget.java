package dev.begleiter.llm;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Schützt das Anfrage-Kontingent (Groq Free-Tier): Anfragen pro Minute und Tag, höchstens eine offene Anfrage je
 * Auftraggeber (Begleiter) und Pause nach einem 429. Thread-sicher; die Uhr ist für Tests austauschbar.
 */
public final class LlmBudget {
    public enum Verdict { OK, BUSY, MINUTE_LIMIT, DAY_LIMIT, BACKOFF }

    public record Snapshot(int lastMinute, int today, int inFlight, long backoffRemainingMs) {}

    private static final long MINUTE = 60_000L, DAY = 86_400_000L;
    private static final long FIRST_BACKOFF = 5_000L, MAX_BACKOFF = 300_000L;

    private final int perMinute, perDay;
    private final LongSupplier clock;
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    /** Identität statt equals: zwei Begleiter dürfen nie als derselbe Auftraggeber gelten. */
    private final Set<Object> inFlight = Collections.newSetFromMap(new IdentityHashMap<>());
    private long day = -1;
    private int today;
    private long backoffUntil;
    private long nextBackoff = FIRST_BACKOFF;

    public LlmBudget(int perMinute, int perDay, LongSupplier clock) {
        if (perMinute < 1 || perDay < 1) throw new IllegalArgumentException("limits must be positive");
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.clock = clock;
    }

    public LlmBudget(LlmConfig config) {
        this(config.maxRequestsPerMinute(), config.maxRequestsPerDay(), System::currentTimeMillis);
    }

    /** Bei {@link Verdict#OK} ist die Anfrage gebucht und muss mit {@link #release} wieder freigegeben werden. */
    public synchronized Verdict tryAcquire(Object owner) {
        long now = clock.getAsLong();
        prune(now);
        if (inFlight.contains(owner)) return Verdict.BUSY;
        if (now < backoffUntil) return Verdict.BACKOFF;
        if (today >= perDay) return Verdict.DAY_LIMIT;
        if (recent.size() >= perMinute) return Verdict.MINUTE_LIMIT;
        recent.addLast(now);
        today++;
        inFlight.add(owner);
        return Verdict.OK;
    }

    public synchronized void release(Object owner) { inFlight.remove(owner); }

    /** Server meldet 429: Pause nach Retry-After, sonst exponentiell (5 s, 10 s, … höchstens 5 min). */
    public synchronized void rateLimited(long retryAfterMs) {
        long wait = retryAfterMs > 0 ? Math.min(retryAfterMs, MAX_BACKOFF) : nextBackoff;
        backoffUntil = Math.max(backoffUntil, clock.getAsLong() + wait);
        nextBackoff = Math.min(nextBackoff * 2, MAX_BACKOFF);
    }

    /** Eine erfolgreiche Antwort setzt die exponentielle Pause zurück. */
    public synchronized void succeeded() { nextBackoff = FIRST_BACKOFF; }

    public synchronized Snapshot snapshot() {
        long now = clock.getAsLong();
        prune(now);
        return new Snapshot(recent.size(), today, inFlight.size(), Math.max(0, backoffUntil - now));
    }

    private void prune(long now) {
        while (!recent.isEmpty() && now - recent.peekFirst() >= MINUTE) recent.removeFirst();
        long currentDay = Math.floorDiv(now, DAY);
        if (currentDay != day) {
            day = currentDay;
            today = 0;
        }
    }
}
