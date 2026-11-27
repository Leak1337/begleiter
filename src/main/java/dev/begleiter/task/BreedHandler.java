package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionJobs;

/** BREED: je zwei erwachsene Tiere einer Art mit ihrem Futter füttern (Paare, 0 = so viele wie möglich). */
final class BreedHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Breed breed;
    private Animal target;
    private int since, fed, unreachable;

    BreedHandler(Task task) {
        this.task = task;
        this.breed = (TaskSpec.Breed) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        int pairs = breed.pairs();
        if (target == null || !dev.begleiter.CompanionZones.allows(mob, dev.begleiter.CompanionZones.Kind.ANIMALS, target.blockPosition()) || !CompanionJobs.breedable(target) || CompanionJobs.feedSlot(mob, target) < 0) {
            if (pairs > 0 && fed >= pairs * 2) return finish(ctx, sl);
            target = CompanionJobs.nextToFeed(sl, mob, breed.species());
            since = mob.tickCount;
            if (target == null) return finish(ctx, sl);
        }
        if (mob.tickCount - since > 400) { // nicht erreichbar
            mob.logInfo(CompanionTexts.tr("jobs.breed.unreachable"));
            target = null;
            return ++unreachable >= 3 ? finish(ctx, sl) : Status.RUNNING;
        }
        if (!ctx.approachEntity(target, 6.25)) {
            ctx.capStuck(60);
            return Status.RUNNING;
        }
        CompanionJobs.feed(mob, target);
        mob.logInfo(CompanionTexts.tr("jobs.breed.fed", target.getName(), target.blockPosition().toShortString()));
        fed++;
        mob.work.progress(1);
        target = null;
        return Status.RUNNING;
    }

    private Status finish(TaskContext ctx, ServerLevel sl) {
        CompanionEntity mob = ctx.mob();
        Component what = speciesLabel(breed.species());
        mob.herdKeeping = true;
        if (fed > 0) {
            ctx.say(CompanionTexts.tr("jobs.breed.done", fed, what));
            return Status.DONE;
        }
        var animals = CompanionJobs.animals(sl, mob.blockPosition(), 24, breed.species());
        if (animals.isEmpty()) {
            ctx.say(CompanionTexts.tr("jobs.breed.none", what));
        } else {
            boolean anyFood = false;
            for (var a : animals)
                if (CompanionJobs.feedSlot(mob, a) >= 0) { anyFood = true; break; }
            ctx.say(anyFood ? CompanionTexts.tr("jobs.breed.rest", what)
                    : CompanionTexts.tr("jobs.breed.no_food", what, CompanionJobs.foodText(breed.species())));
        }
        return Status.FAILED;
    }

    private static Component speciesLabel(String species) {
        return species == null ? CompanionTexts.tr("jobs.breed.animals") : CompanionTexts.animals(species);
    }
}
