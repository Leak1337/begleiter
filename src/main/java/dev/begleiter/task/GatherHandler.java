package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionGathering;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.CompanionMemory;
import dev.begleiter.CompanionPlanner;
import dev.begleiter.CompanionPrerequisites;
import dev.begleiter.Resources;
import dev.begleiter.ai.DecisionOutcome;

/**
 * GATHER: eine Menge einer Ressource besorgen. Erst passendes Werkzeug, dann sichtbare Blöcke als
 * BREAK-Aufgaben; sonst Erinnerung, Erz-Gespür, unter Tage graben (MINE) oder die Gegend erkunden (tries zählt mit).
 */
final class GatherHandler implements TaskHandler {
    private final Task task;
    private TaskSpec.Gather gather;

    GatherHandler(Task task) {
        this.task = task;
        this.gather = (TaskSpec.Gather) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        Resources.Res res = Resources.byKey(gather.resource());
        if (res == null) {
            mob.logWarn(CompanionTexts.tr("handler.gather.unknown", CompanionTexts.literal(gather.resource())));
            return Status.FAILED;
        }
        if (gather.targetTotal() == 0)
            gather = gather.withTarget(held(mob, res) + Math.max(0, gather.count()));
        int remaining = Math.max(0, gather.targetTotal() - held(mob, res));
        if (remaining == 0) return Status.DONE;
        gather = gather.withCount(remaining);
        if (ctx.guardedSupply()) {
            Item output = gather.exactItem() == null ? CompanionPrerequisites.gatheredItem(res.key()) : exactItem();
            if (!CompanionPrerequisites.readyTool(mob, res.key(), gather.count())) {
                var plan = output == Items.AIR ? null : CompanionPlanner.obtainPrepared(mob, output,
                        CompanionPrerequisites.heldCount(mob, output) + Math.max(1, gather.count()), CompanionPlanner.RoutePolicy.AVOID_CHESTS);
                if (plan == null || plan.tasks().isEmpty()) {
                    ctx.abortAutonomousPlan(DecisionOutcome.Reason.MISSING_TOOL);
                } else {
                    mob.logInfo(CompanionTexts.tr("handler.gather.prepared", CompanionTexts.plan(plan)));
                    mob.replaceCurrent(plan.tasks());
                }
                return Status.REPLACED;
            }
            if (output != Items.AIR && !CompanionPrerequisites.fits(mob, output, Math.max(1, gather.count()))) {
                ctx.abortAutonomousPlan(DecisionOutcome.Reason.INVENTORY_FULL);
                return Status.REPLACED;
            }
        }

        // Erst das passende Werkzeug besorgen
        int need = CompanionPrerequisites.requiredTier(res.key());
        int have = CompanionCraft.tier(mob, "pickaxe");
        if (have < need) return toolFirst(mob, res, need, have);

        boolean surface = res == Resources.WOOD || res == Resources.SAND || res == Resources.GRAVEL || res == Resources.DIRT
                || res == Resources.SEEDS;
        List<Task> list = res == Resources.WOOD && gather.exactItem() != null
                ? mob.buildGather(state -> state.is(net.minecraft.world.level.block.Block.byItem(exactItem())), gather.count(), true)
                : res == Resources.WOOD ? mob.gathering.buildTrees(gather.count()) : mob.buildGather(res.block(), gather.count(), surface);
        mob.logInfo(CompanionTexts.tr("handler.gather.search", CompanionTexts.resource(res.key()), gather.count(), list.size(), task.tries() + 1));
        if (!list.isEmpty()) {
            List<Task> all = new ArrayList<>(list);
            if (res != Resources.WOOD) all.addAll(ctx.mining().bonusOres(sl, res)); // Erze nebenan mitnehmen
            all.add(new Task(gather, task.tries() + 1));
            mob.replaceCurrent(all);
            return Status.REPLACED;
        }
        // Schon mal gesehen? Dann gezielt dorthin statt blind zu suchen (neue Instanz: neuer Handler-Lauf).
        Task again = new Task(gather, task.tries());
        if (res == Resources.WOOD && ctx.mining().goRemembered(sl, CompanionMemory.Kind.TREE, null, 128, again)) return Status.REPLACED;
        if ((CompanionMemory.isOre(res) || res == Resources.GRAVEL)
                && ctx.mining().mineKnownOre(sl, res, gather.count(), again, false)) return Status.REPLACED;
        // Erz und Stein: unter Tage danach graben wie ein Spieler (Treppe + Stollen)
        if (!surface) {
            mob.logInfo(CompanionTexts.tr("handler.gather.mine", CompanionTexts.resource(res.key()), CompanionGathering.oreLevel(res.key())));
            mob.replaceCurrent(List.of(new Task(new TaskSpec.Mine(gather.resource(), gather.count(), gather.targetTotal()))));
            return Status.REPLACED;
        }
        if (task.tries() >= 5) {
            mob.problem(CompanionTexts.tr("handler.gather.not_found", CompanionTexts.resource(res.key())));
            ctx.failChain();
            return Status.REPLACED;
        }
        Task ex = mob.explorePoint();
        if (ex == null) {
            ctx.failChain();
            return Status.REPLACED;
        }
        mob.replaceCurrent(List.of(ex, new Task(gather, task.tries() + 1)));
        return Status.REPLACED;
    }

    /** Spitzhacke der nötigen Stufe vorschalten (Holz → Stein → Eisen → Diamant), dann diese Aufgabe erneut. */
    private Status toolFirst(CompanionEntity mob, Resources.Res res, int need, int have) {
        if (task.tries() >= 4) {
            mob.problem(CompanionTexts.tr("handler.gather.no_tool", CompanionTexts.resource(res.key())));
            mob.reportAutonomousFailure(DecisionOutcome.Reason.MISSING_TOOL, task);
            return Status.FAILED;
        }
        List<Task> pre = new ArrayList<>();
        if (have < 1) {
            if (CompanionCraft.count(mob, "#logs") + CompanionCraft.count(mob, "#planks") / 4 < 3)
                pre.add(Task.gather(Resources.WOOD.key(), 6));
            pre.add(Task.craft("wooden_pickaxe", 1));
        } else if (need >= 2 && have < 2) {
            if (CompanionCraft.count(mob, "#stone") < 3)
                pre.add(Task.gather(Resources.STONE.key(), 8));
            pre.add(Task.craft("stone_pickaxe", 1));
        } else if (need >= 4 && have >= 3) {
            int dia = CompanionCraft.count(mob, "diamond");
            if (dia < 3) pre.add(Task.gather(Resources.DIAMOND.key(), 3 - dia));
            pre.add(Task.craft("diamond_pickaxe", 1));
        } else {
            int iron = CompanionCraft.count(mob, "raw_iron") + CompanionCraft.count(mob, "iron_ingot");
            if (iron < 3) pre.add(Task.gather(Resources.IRON.key(), 3 - iron));
            if (CompanionCraft.count(mob, "#coals") < 1)
                pre.add(Task.gather(Resources.COAL.key(), 2));
            pre.add(Task.smeltAnything());
            pre.add(Task.craft("iron_pickaxe", 1));
        }
        pre.add(new Task(gather, task.tries() + 1));
        mob.logInfo(CompanionTexts.tr("handler.gather.tool_first", have + 1, CompanionTexts.resource(res.key())));
        mob.replaceCurrent(pre);
        return Status.REPLACED;
    }

    private int held(CompanionEntity mob, Resources.Res res) {
        if (gather.exactItem() == null) return Resources.count(mob, res);
        int count = 0;
        Item wanted = exactItem();
        for (int slot = 0; slot < mob.getInventory().getContainerSize(); slot++) {
            var stack = mob.getInventory().getItem(slot);
            if (stack.is(wanted)) count += stack.getCount();
        }
        return count;
    }

    private Item exactItem() {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.Identifier.parse(gather.exactItem()));
    }
}
