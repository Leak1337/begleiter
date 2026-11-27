package dev.begleiter.task;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionFishing;
import dev.begleiter.CompanionTexts;
import dev.begleiter.FishingRules;

/**
 * FISH: Angel besorgen, sicheren Uferplatz suchen, hinlaufen und simuliert angeln ({@link CompanionFishing}). Nach jedem
 * Fang wird die Aufgabe mit der Restmenge ersetzt, damit eine Unterbrechung (Kampf, Pause, Neuladen) nichts doppelt
 * zählt; ein unterbrochener Wurf selbst bringt nichts. tries zählt Angel-Beschaffung und Platzsuche.
 */
final class FishHandler implements TaskHandler {
    private static final int SEARCH_RADIUS = 24;
    private static final int MAX_INTERRUPTIONS = 5;

    private final Task task;
    private final TaskSpec.Fish fish;
    private CompanionFishing.Session session;
    private int startedAt = -1, interruptions;

    FishHandler(Task task) {
        this.task = task;
        this.fish = (TaskSpec.Fish) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        if (startedAt < 0) startedAt = mob.tickCount;
        int left = FishingRules.catches(fish.catches());
        if (CompanionFishing.rodSlot(mob) < 0 && !CompanionCraft.craft(mob, "fishing_rod", 1)) {
            if (task.tries() == 0 && !ctx.guardedSupply() && mob.goal == null) {
                // Spielerauftrag: Angel über die Rezeptbeschaffung holen (Fäden, Stöcke), danach weiter angeln
                mob.logInfo(CompanionTexts.tr("handler.fish.get_rod"));
                mob.replaceCurrent(List.of(Task.request("minecraft:fishing_rod", 1, false),
                        new Task(new TaskSpec.Fish(fish.pos(), fish.catches()), 1)));
                return Status.REPLACED;
            }
            ctx.say(CompanionTexts.tr("handler.fish.no_rod"));
            return Status.FAILED;
        }
        if (session == null) {
            BlockPos water = fish.pos();
            CompanionFishing.Spot spot = null;
            if (water != null && CompanionFishing.validWater(sl, water)) {
                BlockPos stand = CompanionFishing.standFor(mob, sl, water, true);
                if (stand != null) spot = new CompanionFishing.Spot(stand, water, CompanionFishing.openWater(sl, water));
            }
            if (spot == null) spot = CompanionFishing.findSpot(mob, sl, SEARCH_RADIUS);
            if (spot == null) {
                if (task.tries() >= 3) {
                    ctx.say(CompanionTexts.tr("handler.fish.no_water"));
                    return Status.FAILED;
                }
                Task explore = mob.explorePoint();
                if (explore == null) {
                    ctx.say(CompanionTexts.tr("handler.fish.no_water"));
                    return Status.FAILED;
                }
                mob.replaceCurrent(List.of(explore, new Task(new TaskSpec.Fish(null, fish.catches()), task.tries() + 1)));
                return Status.REPLACED;
            }
            session = new CompanionFishing.Session(mob, spot.stand(), spot.water(), CompanionFishing.random(mob));
            mob.logInfo(CompanionTexts.tr(spot.openWater() ? "handler.fish.spot_open" : "handler.fish.spot",
                    spot.water().toShortString()));
        }
        if (!ctx.approach(session.stand, 1.0)) {
            ctx.capStuck(100);
            return Status.RUNNING;
        }
        ctx.resetStuck();
        if (mob.tickCount - startedAt > FishingRules.GIVE_UP_TICKS + 600) {
            ctx.say(CompanionTexts.tr("handler.fish.nothing_bites"));
            return Status.FAILED;
        }
        var result = session.tick(sl, mob.tickCount);
        switch (result.outcome()) {
            case WAITING -> { return Status.RUNNING; }
            case CAUGHT -> {
                var first = result.loot().getFirst();
                mob.logInfo(CompanionTexts.tr("handler.fish.caught", first.getCount(), first.getHoverName()));
                mob.work.progress(1);
                if (left <= 1) {
                    ctx.say(CompanionTexts.tr("handler.fish.done"));
                    return Status.DONE;
                }
                mob.replaceCurrent(List.of(new Task(new TaskSpec.Fish(session.water, left - 1), task.tries())));
                return Status.REPLACED;
            }
            case INTERRUPTED -> {
                mob.logInfo(CompanionTexts.tr("handler.fish.interrupted"));
                if (++interruptions >= MAX_INTERRUPTIONS) {
                    ctx.say(CompanionTexts.tr("handler.fish.too_many_interruptions"));
                    return Status.FAILED;
                }
                return Status.RUNNING;
            }
            case INVALID_WATER -> {
                mob.logInfo(CompanionTexts.tr("handler.fish.water_gone"));
                session = null;
                mob.replaceCurrent(List.of(new Task(new TaskSpec.Fish(null, fish.catches()), task.tries() + 1)));
                return Status.REPLACED;
            }
            case NO_ROD -> {
                // Angel ist zerbrochen: nächste Runde stellt eine neue her oder gibt auf
                session = null;
                return Status.RUNNING;
            }
        }
        return Status.RUNNING;
    }
}
