package dev.begleiter.nav;

import net.minecraft.core.BlockPos;

import dev.begleiter.CompanionEntity;

/**
 * Test-Treiber ohne Navigator: suchen, Weg ablaufen, bei Fehlschlag oder Ende
 * eines Teilweges neu planen. Jeden Tick {@link #tick()} aufrufen.
 */
public final class NavDriver {
    private final CompanionEntity mob;
    private final Goal goal;
    private PathExecutor exec;
    public int searches, failures;
    public long searchNanos;
    public String lastFail = "";
    public net.minecraft.network.chat.Component lastFailureText = net.minecraft.network.chat.Component.empty();
    public boolean arrived;

    public NavDriver(CompanionEntity mob, Goal goal) {
        this.mob = mob;
        this.goal = goal;
    }

    public PathExecutor executor() { return exec; }

    public void tick() {
        if (arrived) return;
        if (goal.isInGoal(mob.getBlockX(), mob.getBlockY(), mob.getBlockZ()) && mob.onGround()) {
            arrived = true;
            mob.setSprinting(false);
            return;
        }
        if (exec != null && exec.status() == PathExecutor.Status.FAILED) {
            failures++;
            lastFail = exec.failReason;
            lastFailureText = exec.failureComponent();
            exec = null;
        }
        if (exec != null && exec.status() == PathExecutor.Status.DONE) exec = null;
        if (exec == null) {
            if (!mob.onGround()) return;
            NavContext ctx = new NavContext(mob);
            BlockPos start = PathSearch.startFor(ctx, mob);
            PathSearch s = new PathSearch(ctx, start, goal);
            s.step(50_000_000L);
            searches++;
            searchNanos += s.nanos();
            Path p = s.path();
            if (p == null || p.length() == 0) return;
            exec = new PathExecutor(p.trimPartial(), ctx);
        }
        exec.tick(mob);
    }

    public String describe() {
        return "Suchen " + searches + ", Fehlschläge " + failures + (lastFail.isEmpty() ? "" : " (zuletzt: " + lastFail + ")")
                + (exec == null ? "" : ", Schritt " + exec.index() + "/" + exec.path.length()
                + (exec.current() == null ? "" : " " + exec.current().move));
    }
}
