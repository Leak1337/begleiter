package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.Resources;
import dev.begleiter.nav.Goal;

/**
 * COLLECT: herumliegende Items aufheben, bis keine mehr da sind. anyAge: alles im Umkreis 24 (auch alte Items, auch
 * direkt nach dem Abgeben); sonst nur frische im Umkreis 12.
 */
final class CollectHandler implements TaskHandler {
    private final TaskSpec.Collect collect;

    CollectHandler(Task task) {
        this.collect = (TaskSpec.Collect) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        boolean any = collect.anyAge();
        if (!any && mob.tickCount < mob.collectBlockedUntil) return Status.DONE; // gerade abgegeben: nicht zurückholen
        var zones = dev.begleiter.CompanionZones.collectionZones(mob);
        java.util.function.Predicate<ItemEntity> eligible = ie -> (any || ie.getAge() < 6000) && mob.collectible(ie);
        // Getrennte Bereiche einzeln abfragen: auch weit auseinanderliegende Zonen erzeugen keine riesige Suchbox.
        var found = new java.util.LinkedHashSet<ItemEntity>();
        if (zones.isEmpty()) found.addAll(ctx.level().getEntitiesOfClass(ItemEntity.class,
                mob.getBoundingBox().inflate(any ? 24 : 12), eligible));
        else for (var zone : zones) found.addAll(ctx.level().getEntitiesOfClass(ItemEntity.class, zone.bounds(), eligible));
        List<ItemEntity> items = new java.util.ArrayList<>(found);
        if (items.isEmpty() || !mob.hasFreeSlot()) return Status.DONE;
        items.sort(Comparator.comparingDouble(mob::distanceToSqr));
        ItemEntity item = items.get(0);
        if (mob.chase(item)) ctx.resetStuck();
        if (mob.chaseTicks() > 200) { // kommt nicht ran (z.B. hinter Wasser/in einer Mulde): merken, nächstes
            mob.giveUpChase(item);
            mob.logInfo(CompanionTexts.tr("handler.collect.unreachable", item.getItem().getHoverName(), item.blockPosition().toShortString()));
            return Status.RUNNING;
        }
        if (mob.distanceToSqr(item) > 2.25) {
            BlockPos ip = item.blockPosition();
            if (ctx.useNewPathing(ip)) {
                ctx.approachGoal(new Goal.Near(ip.getX(), ip.getY(), ip.getZ(), 1), ip);
                // Warten auf Rechenzeit (viele Begleiter) ist kein Zeichen, dass das Item unerreichbar ist.
                if (mob.nav.waitingForBudget()) mob.holdChase();
                return Status.RUNNING;
            }
            if (mob.tickCount % 10 == 0) mob.getNavigation().moveTo(item, 1.2);
            ctx.trackMove();
            return Status.RUNNING;
        }
        if (item.hasPickUpDelay()) return Status.RUNNING; // frisch geworfen/gefallen: wie ein Spieler warten
        ItemStack rest = mob.giveToInventory(item.getItem().copy());
        if (rest.isEmpty()) item.discard(); else item.setItem(rest);
        ctx.resetStuck();
        return Status.RUNNING;
    }
}
