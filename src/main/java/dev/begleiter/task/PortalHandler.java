package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;
import dev.begleiter.debug.BotDebug.EventKind;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.Resources;

/**
 * PORTAL: Netherportal bauen (pos = Bauplatz-Ecke, null = Platz suchen). Innenraum freiräumen, fehlenden Obsidian-Rahmen
 * setzen (oder erst Obsidian besorgen), mit dem Feuerzeug anzünden; tries zählt Bau-Runden.
 */
final class PortalHandler implements TaskHandler {
    private static final double REACH_SQR = 4.5 * 4.5;

    private final Task task;
    private final TaskSpec.Portal portal;

    PortalHandler(Task task) {
        this.task = task;
        this.portal = (TaskSpec.Portal) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        if (!mob.inOverworld()) {
            mob.logInfo(EventKind.PORTAL, CompanionTexts.tr("handler.portal.wrong_dimension"));
            return Status.FAILED;
        }
        BlockPos site = portal.pos();
        if (site == null) {
            BlockPos home = mob.getHome();
            site = mob.house.findBuildSite();
            if (site == null) return fail(ctx, CompanionTexts.tr("handler.portal.no_site"));
            mob.logInfo(EventKind.PORTAL, CompanionTexts.tr("handler.portal.site", site.toShortString(), home == null ? Component.empty() : CompanionTexts.tr("handler.portal.home", home.toShortString())));
        }
        List<Task> build = new ArrayList<>();
        for (BlockPos p : dev.begleiter.CompanionPortals.inside(site)) {
            BlockState st = sl.getBlockState(p);
            // Nur den vorgefundenen Block abbauen; was bis zum Abbau dazukommt, bleibt stehen.
            if (!st.isAir() && !st.is(Blocks.NETHER_PORTAL) && !st.canBeReplaced()) build.add(Task.breakAt(p, st.getBlock()));
        }
        int missing = 0;
        for (BlockPos p : dev.begleiter.CompanionPortals.frame(site)) {
            BlockState st = sl.getBlockState(p);
            if (st.is(Blocks.OBSIDIAN)) continue;
            missing++;
            if (!st.isAir() && !st.canBeReplaced()) build.add(Task.breakAt(p, st.getBlock()));
            build.add(Task.place(p, Blocks.OBSIDIAN));
        }
        if (missing > CompanionCraft.count(mob, "obsidian")) {
            if (task.tries() >= 3) return fail(ctx, CompanionTexts.tr("handler.portal.no_obsidian"));
            build.clear();
            build.add(Task.gather(Resources.OBSIDIAN.key(), missing - CompanionCraft.count(mob, "obsidian")));
        }
        if (!build.isEmpty()) {
            build.add(new Task(new TaskSpec.Portal(site), task.tries() + 1));
            mob.replaceCurrent(build);
            return Status.REPLACED;
        }
        BlockPos first = dev.begleiter.CompanionPortals.inside(site).getFirst();
        if (sl.getBlockState(first).is(Blocks.NETHER_PORTAL)) {
            mob.setPortalPos(site);
            mob.tell(CompanionTexts.tr("handler.portal.done", first.toShortString()));
            return Status.DONE;
        }
        if (mob.findItem(Items.FLINT_AND_STEEL) < 0 && !CompanionCraft.craft(mob, "flint_and_steel", 1))
            return fail(ctx, CompanionTexts.tr("handler.portal.no_lighter", mob.craftProblemComponent()));
        if (!ctx.approach(first, REACH_SQR)) return Status.RUNNING;
        if (!mob.portals.light(sl, site)) return fail(ctx, CompanionTexts.tr("handler.portal.bad_frame"));
        mob.setPortalPos(site);
        mob.tell(CompanionTexts.tr("handler.portal.lit", first.toShortString()));
        return Status.DONE;
    }

    private static Status fail(TaskContext ctx, Component problem) {
        ctx.mob().problem(EventKind.PORTAL, problem);
        ctx.failChain();
        return Status.REPLACED;
    }
}
