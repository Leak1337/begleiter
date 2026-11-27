package dev.begleiter.nav;

import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Gemeinsame, weiche 6-ms-Grenze für Haupt-, Voraus- und Rundenplanung aller Dimensionen eines Servers. */
public final class NavScheduler {
    public enum Priority { ACTIVE, PREFETCH }
    public static final long BUDGET_NANOS = 6_000_000L;
    /** Nur der Zugriff auf die Registry ist synchronisiert; jedes Budget gehört seinem Server-Thread. */
    private static final Map<MinecraftServer, NavScheduler> SERVERS = new IdentityHashMap<>();
    private final NavBudget budget = new NavBudget(BUDGET_NANOS, System::nanoTime);

    private NavScheduler() {}

    public static void register() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            synchronized (SERVERS) {
                NavScheduler scheduler = SERVERS.get(server);
                if (scheduler != null) {
                    scheduler.budget.setLimit(dev.begleiter.config.BegleiterConfig.get().navBudgetNanos());
                    scheduler.budget.beginTick();
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(NavScheduler::reset);
    }

    static NavScheduler forLevel(ServerLevel level) {
        synchronized (SERVERS) { return SERVERS.computeIfAbsent(level.getServer(), server -> new NavScheduler()); }
    }

    static boolean run(ServerLevel level, Object token, Priority priority, long requested, NavBudget.Work work) {
        return forLevel(level).budget.run(token, priority == Priority.PREFETCH, requested, work);
    }

    static void cancel(ServerLevel level, Object token) {
        synchronized (SERVERS) {
            NavScheduler scheduler = SERVERS.get(level.getServer());
            if (scheduler != null) scheduler.budget.cancel(token);
        }
    }

    static long remaining(long deadline) { return Math.max(0, deadline - System.nanoTime()); }
    NavBudget.Snapshot snapshot() { return budget.snapshot(); }
    NavBudget.Snapshot lastTick() { return budget.lastTick(); }

    public static void reset(MinecraftServer server) {
        synchronized (SERVERS) {
            NavScheduler removed = SERVERS.remove(server);
            if (removed != null) removed.budget.clear();
        }
    }
}
