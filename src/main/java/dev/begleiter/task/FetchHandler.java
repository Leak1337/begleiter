package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.Comparator;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;

/**
 * FETCH: ein Tier (Typ-ID, "any"/null = irgendein Nutztier) anleinen und zum Besitzer bringen. Tier und Leine
 * stehen in mob.fetchTarget/fetchLeashed, damit Abbruch und Überspringen sie überall freigeben können.
 */
final class FetchHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Fetch fetch;
    private net.minecraft.core.BlockPos destination;

    FetchHandler(Task task) {
        this.task = task;
        this.fetch = (TaskSpec.Fetch) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        Player owner = mob.getOwner();
        if (owner == null) {
            mob.releaseFetch();
            mob.logInfo(CompanionTexts.tr("handler.fetch.no_owner"));
            return Status.FAILED;
        }
        var pen = mob.duty() == dev.begleiter.CompanionDuties.Duty.FETCH
                ? mob.zones.get(dev.begleiter.CompanionZones.Kind.ANIMALS, ctx.level()) : null;
        if (mob.duty() == dev.begleiter.CompanionDuties.Duty.FETCH && pen == null) { mob.releaseFetch(); return Status.FAILED; }
        var roaming = pen == null ? null : mob.zones.get(dev.begleiter.CompanionZones.Kind.ROAM, ctx.level());
        Mob target = mob.fetchTarget;
        if (target == null) {
            String wanted = fetch.animal();
            List<Mob> cands = ctx.level().getEntitiesOfClass(Mob.class, (pen == null ? mob.getBoundingBox().inflate(48) : pen.bounds().inflate(48)),
                    e -> e != mob && !(e instanceof CompanionEntity) && e.isAlive() && matchesType(e, wanted)
                            && dev.begleiter.CompanionJobs.mayFetch(e, owner)
                            && (pen == null || (!pen.contains(e.blockPosition()) && !e.hasCustomName()
                                    && e instanceof net.minecraft.world.entity.animal.Animal a && !a.isBaby()
                                    && (roaming == null || roaming.contains(e.blockPosition())))));
            target = cands.stream().min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
            if (target == null) {
                mob.tell(CompanionTexts.tr("handler.fetch.none"));
                return Status.FAILED;
            }
            mob.fetchTarget = target;
            mob.fetchLeashed = false;
            mob.logInfo(CompanionTexts.tr("handler.fetch.found", target.getName(), target.blockPosition().toShortString()));
        }
        if (!target.isAlive() || target.level() != mob.level()) {
            mob.releaseFetch();
            mob.tell(CompanionTexts.tr("handler.fetch.dead"));
            return Status.FAILED;
        }
        if (!mob.fetchLeashed) {
            if (!dev.begleiter.CompanionJobs.mayFetch(target, owner)) { mob.releaseFetch(); return Status.FAILED; }
            if (!ctx.approachEntity(target, 6.25)) return Status.RUNNING;
            if (!(target instanceof Leashable l)) {
                mob.logWarn(CompanionTexts.tr("handler.fetch.unleashable", target.getName()));
                mob.tell(CompanionTexts.tr("handler.fetch.no_leash"));
                mob.releaseFetch();
                return Status.FAILED;
            }
            l.setLeashedTo(mob, true);
            mob.fetchLeashed = true;
            mob.logInfo(CompanionTexts.tr("handler.fetch.leashed"));
            ctx.resetStuck();
            return Status.RUNNING;
        }
        if (target instanceof Leashable l && l.getLeashHolder() != mob) { mob.releaseFetch(); return Status.FAILED; }
        if (pen != null) {
            if (destination == null) destination = dev.begleiter.CompanionDuties.deliveryTarget(mob, ctx.level(), pen);
            if (destination == null) { mob.releaseFetch(); return Status.FAILED; }
            // Den Fußblock wirklich erreichen; ein großzügiges Nähe-Ziel kann noch vor der Weide enden.
            if (!ctx.approachGoal(new dev.begleiter.nav.Goal.Block(destination), destination)) return Status.RUNNING;
            if (!pen.contains(target.blockPosition())) { ctx.capStuck(60); return Status.RUNNING; }
        } else if (!ctx.approachEntity(owner, 12.25)) return Status.RUNNING;
        mob.releaseFetch();
        mob.tell(CompanionTexts.tr(pen == null ? "handler.fetch.done" : "duty.fetch.delivered"));
        return Status.DONE;
    }

    private static boolean matchesType(Mob e, String wanted) {
        if (wanted == null || wanted.equals("any")) return e.getType().getCategory() == MobCategory.CREATURE;
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(wanted);
    }
}
