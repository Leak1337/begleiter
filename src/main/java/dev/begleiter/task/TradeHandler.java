package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionJobs;
import dev.begleiter.CompanionMemory;
import dev.begleiter.Resources;

/**
 * TRADE: Item zum Kaufen (Anzahl, 0 = 16), ohne Item: überzähliges Zeug gegen Smaragde verkaufen. Ohne Dorfbewohner
 * in der Nähe geht er zum gemerkten Dorf (tries zählt das); nach 2 Minuten ist Schluss.
 */
final class TradeHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Trade trade;
    private int since, traded, progress;

    TradeHandler(Task task) {
        this.task = task;
        this.trade = (TaskSpec.Trade) task.spec();
    }

    @Override
    public void start(TaskContext ctx) {
        since = ctx.mob().tickCount; // 2-Minuten-Grenze ab Beginn dieser Aufgabe
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        Item want = trade.item() == null ? null : CompanionCraft.item(trade.item());
        if (want == Items.AIR) {
            ctx.say(CompanionTexts.tr("jobs.trade.unknown", CompanionTexts.literal(trade.item())));
            return Status.FAILED;
        }
        var vs = CompanionJobs.villagers(sl, mob, 32).stream().filter(v -> mob.work.permitsTrade(sl, v.blockPosition())).toList();
        if (vs.isEmpty()) {
            if (mob.work.active()) return Status.FAILED;
            CompanionMemory.Place village = mob.memory.nearest(CompanionMemory.Kind.VILLAGE, null, sl, mob.blockPosition(), 2000);
            if (village != null && task.tries() < 2) {
                ctx.say(CompanionTexts.tr("jobs.trade.village", CompanionTexts.location(village, mob.blockPosition())));
                mob.replaceCurrent(List.of(Task.travel(village.pos),
                        Task.moveTo(village.pos, 64),
                        task.retry()));
                return Status.REPLACED;
            }
            ctx.say(CompanionTexts.tr("jobs.trade.none"));
            return Status.FAILED;
        }
        Villager partner = null;
        MerchantOffer offer = null;
        for (var v : vs) {
            var o = CompanionJobs.findOffer(mob, v, want);
            if (o != null) { partner = v; offer = o; break; }
        }
        if (partner == null || mob.tickCount - since > 2400) return finish(ctx, want, vs);
        if (!ctx.approachEntity(partner, 9.0)) {
            ctx.capStuck(60);
            return Status.RUNNING;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(partner);
        if (++progress < 10) return Status.RUNNING;
        progress = 0;
        ItemStack got = CompanionJobs.trade(mob, partner, offer);
        if (got.isEmpty() && mob.work.active()) return Status.FAILED;
        if (!got.isEmpty()) mob.work.progress(1);
        mob.logInfo(CompanionTexts.tr("jobs.trade.received", got.getCount(), got.getHoverName()));
        traded += want != null ? got.getCount() : 1;
        boolean enough = want != null ? traded >= (trade.count() > 0 ? trade.count() : 16) : traded >= 16;
        return enough ? finish(ctx, want, vs) : Status.RUNNING;
    }

    private Status finish(TaskContext ctx, Item want, List<Villager> vs) {
        CompanionEntity mob = ctx.mob();
        if (want != null) {
            Component name = new ItemStack(want).getHoverName();
            if (traded > 0) ctx.say(CompanionTexts.tr("jobs.trade.bought", traded, name));
            else if (!CompanionJobs.anyoneSells(vs, want)) ctx.say(CompanionTexts.tr("jobs.trade.not_sold", name));
            else ctx.say(CompanionTexts.tr("jobs.trade.no_payment", name));
        } else {
            int em = CompanionJobs.countOf(mob, Items.EMERALD);
            ctx.say(traded > 0 ? CompanionTexts.tr("jobs.trade.sold", traded, em)
                    : CompanionTexts.tr("jobs.trade.nothing"));
        }
        return traded > 0 ? Status.DONE : Status.FAILED;
    }
}
