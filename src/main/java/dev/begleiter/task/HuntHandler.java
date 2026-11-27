package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;

/**
 * HUNT: Tiere jagen, bis genug Essen da ist (Zielmenge, mindestens 2). Das Tier steht in mob.huntTarget, weil der
 * Kampf selbst das Aufgabenziel unterbricht; nach jedem erlegten Tier erst einsammeln, dann weiter (tries zählt Runden).
 */
final class HuntHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Hunt hunt;
    /** Geschützter Modus: Rückwegprüfung zum gewählten Tier und die Stelle, für die sie galt. */
    private dev.begleiter.nav.RoundTripCheck trip;
    private Mob tripFor;
    private net.minecraft.core.BlockPos checkedAt;
    /** So weit darf sich ein geprüftes Tier entfernen, bevor neu geprüft wird (Blöcke). */
    static final int MOVED_RECHECK = 8;
    /** Tier ohne nachgewiesenen Rückweg so lange nicht wieder wählen (Ticks). */
    static final int SKIP_TICKS = 600;

    HuntHandler(Task task) {
        this.task = task;
        this.hunt = (TaskSpec.Hunt) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        int goal = Math.max(2, hunt.food());
        Mob h = mob.huntTarget;
        if (h != null && h.isAlive()) {
            // geprüftes Tier ist weit weggelaufen: den Nachweis nicht stillschweigend weiterverwenden
            if (ctx.guardedSupply() && checkedAt != null && h.blockPosition().distSqr(checkedAt) > MOVED_RECHECK * MOVED_RECHECK) {
                mob.logInfo(CompanionTexts.tr("handler.hunt.moved", h.getName()));
                mob.huntTarget = null;
                mob.setTarget(null);
                checkedAt = null;
                return Status.RUNNING;
            }
            mob.setTarget(h);
            return Status.RUNNING;
        }
        if (h == null && mob.foodCount() >= goal) return finish(mob, Status.DONE);
        if (h != null) { // erlegt (oder verschwunden): Beute einsammeln, dann weiter
            mob.huntTarget = null;
            mob.replaceCurrent(List.of(Task.collect(false),
                    new Task(new TaskSpec.Hunt(goal), task.tries() + 1)));
            return Status.REPLACED;
        }
        if (task.tries() >= 6) {
            mob.problem(CompanionTexts.tr("handler.hunt.unreachable"));
            return finish(mob, Status.FAILED);
        }
        Mob nearest = mob.nearestFoodAnimal();
        if (nearest == null) {
            mob.logInfo(CompanionTexts.tr("handler.hunt.none"));
            return finish(mob, Status.DONE);
        }
        if (walkCloser(ctx, nearest)) return Status.RUNNING;
        if (ctx.guardedSupply()) {
            Status checked = checkTrip(ctx, nearest);
            if (checked != null) return checked;
        }
        mob.huntTarget = nearest;
        mob.setTarget(nearest);
        mob.logInfo(CompanionTexts.tr("handler.hunt.target", nearest.getName(), nearest.blockPosition().toShortString()));
        return Status.RUNNING;
    }

    /**
     * Geschützter Modus: Hin- und Rückweg zum Tier nachweisen, bevor er es angreift. Ohne Nachweis (UNSAFE oder
     * UNPROVEN) bleibt dieses Tier eine Weile außen vor; die Jagd als Methode wird nicht bestraft. null = freigegeben.
     */
    private Status checkTrip(TaskContext ctx, Mob animal) {
        CompanionEntity mob = ctx.mob();
        if (trip == null || tripFor != animal) {
            mob.getNavigation().stop();
            mob.nav.stop();
            trip = new dev.begleiter.nav.RoundTripCheck(mob, animal.blockPosition(), 3 * 3);
            tripFor = animal;
        }
        var status = trip.step();
        if (status == dev.begleiter.nav.RoundTripCheck.Status.CHECKING) return Status.RUNNING;
        var result = trip;
        trip = null;
        tripFor = null;
        if (status != dev.begleiter.nav.RoundTripCheck.Status.SAFE) {
            mob.logInfo(CompanionTexts.tr(status == dev.begleiter.nav.RoundTripCheck.Status.UNSAFE
                    ? "handler.hunt.route_unsafe" : "handler.hunt.route_unproven", animal.getName(), result.reasonComponent()));
            mob.ignoreTarget(animal, SKIP_TICKS);
            return Status.RUNNING; // nächster Tick: nächstes Tier
        }
        checkedAt = animal.blockPosition().immutable();
        return null;
    }

    /** Jagd vorbei: mit Ofen in der Nähe das rohe Fleisch gleich braten. */
    private static Status finish(CompanionEntity mob, Status status) {
        if (mob.findStation("furnace", 24) == null || !CompanionCraft.hasSmeltable(mob)) return status;
        mob.replaceCurrent(List.of(Task.smeltAnything()));
        return Status.REPLACED;
    }

    /**
     * Weit entferntes Tier/Monster: erst hinlaufen, dann angreifen – sonst wird das Ziel sofort wieder fallen gelassen
     * (Ziele über 24 Blöcke) und er bleibt stehen. true = läuft noch.
     */
    static boolean walkCloser(TaskContext ctx, Mob m) {
        if (ctx.mob().distanceToSqr(m) <= 14 * 14) return false;
        ctx.approach(m.blockPosition(), 10 * 10);
        return true;
    }
}
