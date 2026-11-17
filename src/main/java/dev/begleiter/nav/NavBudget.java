package dev.begleiter.nav;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Zeitvergabe auf einem Server-Thread; Uhr und Arbeit sind für deterministische Tests austauschbar. */
final class NavBudget {
    @FunctionalInterface interface Work { boolean run(long deadline); }
    record Snapshot(long tick, long usedNanos, long overshootNanos, int grants, int deferred,
                    long maxWaitTicks, int tracked) {}

    private static final class Request {
        long lastRequested, waitingSince, lastServed = Long.MIN_VALUE, requested, reserved;
        boolean prefetch;
        Request(long tick) { lastRequested = waitingSince = tick; }
    }

    private final Map<Object, Request> requests = new IdentityHashMap<>();
    private final long limit;
    private final LongSupplier clock;
    private long tick, used, reserved, maxWait;
    private int grants, deferred;
    private Snapshot lastTick = new Snapshot(-1, 0, 0, 0, 0, 0, 0);

    NavBudget(long limit, LongSupplier clock) {
        if (limit <= 0) throw new IllegalArgumentException("Navigationsbudget muss positiv sein");
        this.limit = limit;
        this.clock = Objects.requireNonNull(clock);
    }

    void beginTick() {
        lastTick = snapshot();
        tick++;
        used = reserved = maxWait = 0;
        grants = deferred = 0;
        requests.values().removeIf(r -> r.lastRequested < tick - 2);
        int waiting = 0;
        for (Request r : requests.values()) {
            r.reserved = 0;
            if (!r.prefetch && r.lastRequested == tick - 1) waiting++;
        }
        if (waiting == 0) return;
        long share = limit / waiting;
        for (Request r : requests.values()) {
            if (!r.prefetch && r.lastRequested == tick - 1) {
                r.reserved = Math.min(share, r.requested);
                reserved += r.reserved;
            }
        }
    }

    boolean run(Object token, boolean prefetch, long requested, Work work) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(work);
        if (requested <= 0) return false;
        Request own = requests.computeIfAbsent(token, key -> new Request(tick));
        own.prefetch = prefetch;
        own.lastRequested = tick;
        own.requested = requested;
        maxWait = Math.max(maxWait, Math.max(0, tick - own.waitingSince));
        if (own.lastServed == tick || used >= limit || mustWait(own)) return defer(own);

        release(own);
        long grant = Math.min(requested, Math.max(0, limit - used - reserved));
        if (grant == 0) return defer(own);
        long before = clock.getAsLong();
        boolean finished = false;
        own.lastServed = tick;
        grants++;
        try {
            finished = work.run(before + grant);
            return true;
        } finally {
            // Auch ein überlanger Knoten oder eine Ausnahme verbraucht echtes Budget.
            used += Math.max(0, clock.getAsLong() - before);
            own.waitingSince = tick + 1;
            if (finished) requests.remove(token);
        }
    }

    private boolean mustWait(Request own) {
        for (Request other : requests.values()) {
            if (other == own || other.prefetch || other.lastRequested < tick - 1 || other.lastServed == tick) continue;
            // Nach Überziehungen erhalten bisher ausgelassene Suchen zuerst eine Chance.
            if (own.prefetch || other.waitingSince < own.waitingSince) return true;
        }
        return false;
    }

    private boolean defer(Request own) {
        release(own);
        deferred++;
        return false;
    }

    private void release(Request own) { reserved -= own.reserved; own.reserved = 0; }

    void cancel(Object token) {
        Request removed = requests.remove(token);
        if (removed != null) release(removed);
    }

    void clear() { requests.clear(); reserved = 0; }

    Snapshot snapshot() { return new Snapshot(tick, used, Math.max(0, used - limit), grants, deferred, maxWait, requests.size()); }
    Snapshot lastTick() { return lastTick; }
}
