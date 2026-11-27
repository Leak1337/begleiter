package dev.begleiter.task;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionPotions;
import dev.begleiter.CompanionTexts;
import dev.begleiter.Resources;

/**
 * BREW: am Braustand Tränke brauen wie ein Spieler. Wasserflaschen (aus Glasflaschen und Wassereimer, wie am Kessel),
 * Netherwarze zum Seltsamen Trank, dann die Zutat; Lohenstaub als Brennstoff. Ein Durchgang macht bis zu 3 Tränke und
 * dauert zweimal 20 s. Fehlt der Braustand, stellt er einen auf (Lohenrute und 3 Bruchstein).
 */
final class BrewHandler implements TaskHandler {
    private static final int STAGE_TICKS = 400;
    private final Task task;
    private final TaskSpec.Brew brew;
    private int progress;

    BrewHandler(Task task) {
        this.task = task;
        this.brew = (TaskSpec.Brew) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        CompanionPotions.Kind kind = CompanionPotions.Kind.byId(brew.potion());
        if (kind == null) return Status.FAILED;
        int have = mob.potions.count(kind);
        if (have >= brew.count()) return Status.DONE;
        BlockPos stand = mob.findStation("brewing_stand", 24);
        if (stand == null) {
            if (task.tries() >= 2) {
                mob.problem(CompanionTexts.tr("handler.brew.no_stand"));
                return failed(mob, kind);
            }
            List<Task> pre = new ArrayList<>();
            if (!CompanionCraft.owns(mob, "brewing_stand")) {
                if (CompanionCraft.count(mob, "blaze_rod") < 1) {
                    mob.problem(CompanionTexts.tr("handler.brew.no_stand"));
                    return failed(mob, kind);
                }
                if (CompanionCraft.count(mob, "#stone") < 3)
                    pre.add(Task.gather(Resources.STONE.key(), 4));
            }
            pre.add(Task.station("brewing_stand", false));
            pre.add(task.retry());
            mob.replaceCurrent(pre);
            return Status.REPLACED;
        }
        mob.potions.noteStand();
        int batch = Math.min(CompanionPotions.BATCH, brew.count() - have);
        // Wasserflaschen: Glasflaschen am Wassereimer füllen (ein Eimer reicht für drei, wie ein Kessel)
        int water = mob.potions.waterBottles();
        if (water < batch && CompanionCraft.count(mob, "glass_bottle") > 0) {
            int bucket = mob.findItem(Items.WATER_BUCKET);
            if (bucket < 0) {
                mob.problem(CompanionTexts.tr("handler.brew.no_water"));
                return failed(mob, kind);
            }
            int fill = Math.min(CompanionPotions.BATCH, CompanionCraft.count(mob, "glass_bottle"));
            CompanionCraft.consume(mob, "glass_bottle", fill);
            mob.getInventory().setItem(bucket, new ItemStack(Items.BUCKET));
            for (int i = 0; i < fill; i++) mob.giveToInventory(CompanionPotions.waterBottle());
            water = mob.potions.waterBottles();
        }
        batch = Math.min(batch, water);
        if (batch <= 0) {
            mob.problem(CompanionTexts.tr("handler.brew.no_water"));
            return failed(mob, kind);
        }
        if (CompanionCraft.count(mob, "nether_wart") < 1) {
            mob.problem(CompanionTexts.tr("handler.brew.no_wart"));
            return failed(mob, kind);
        }
        if (!ingredient(mob, kind)) {
            mob.problem(CompanionTexts.tr("handler.brew.no_ingredient", CompanionTexts.item(kind.ingredient)));
            return failed(mob, kind);
        }
        if (mob.potions.brewFuel <= 0 && !powder(mob, kind == CompanionPotions.Kind.STRENGTH ? 2 : 1)) {
            mob.problem(CompanionTexts.tr("handler.brew.no_fuel"));
            return failed(mob, kind);
        }
        if (!ctx.approach(stand, 6.25)) return Status.RUNNING;
        mob.getLookControl().setLookAt(Vec3.atCenterOf(stand));
        if (progress % 40 == 0) mob.useContainer(ctx.level(), stand);
        if (++progress < 2 * STAGE_TICKS) return Status.RUNNING;
        progress = 0;
        // Durchgang fertig: Brennstoff, Warze, Zutat und Wasserflaschen werden zu Tränken
        if (mob.potions.brewFuel <= 0) {
            CompanionCraft.consume(mob, "blaze_powder", 1);
            mob.potions.brewFuel = 20;
        }
        mob.potions.brewFuel -= 2; // zwei Brauvorgänge: Seltsamer Trank, dann die Zutat
        CompanionCraft.consume(mob, "nether_wart", 1);
        CompanionCraft.consume(mob, kind.ingredient, 1);
        int made = 0;
        for (int i = 0; i < mob.getInventory().getContainerSize() && made < batch; i++) {
            ItemStack s = mob.getInventory().getItem(i);
            while (CompanionPotions.isWaterBottle(s) && !s.isEmpty() && made < batch) {
                s.shrink(1);
                made++;
            }
        }
        for (int i = 0; i < made; i++) {
            ItemStack rest = mob.giveToInventory(CompanionPotions.potion(kind));
            if (!rest.isEmpty()) mob.spawnAtLocation(ctx.level(), rest);
        }
        mob.logInfo(CompanionTexts.tr("handler.brew.done", made, CompanionTexts.tr("potion." + kind.id())));
        return mob.potions.count(kind) >= brew.count() ? Status.DONE : Status.RUNNING;
    }

    /** Fehlschlag: diese Trankart eine Weile nicht wieder planen (sonst Schleife ohne Zutat). */
    private static Status failed(CompanionEntity mob, CompanionPotions.Kind kind) {
        mob.craftFailedUntil.put("#brew_" + kind.id(), mob.tickCount + 6000);
        return Status.FAILED;
    }

    /** Zutat dabei oder jetzt herstellbar (Magmacreme aus Schleimball + Lohenstaub, Glitzernde Melone, Lohenstaub). */
    private static boolean ingredient(CompanionEntity mob, CompanionPotions.Kind kind) {
        if (CompanionCraft.count(mob, kind.ingredient) > 0) return true;
        return switch (kind) {
            case STRENGTH -> powder(mob, 1);
            case FIRE -> CompanionCraft.count(mob, "slime_ball") > 0 && powder(mob, 1)
                    && CompanionCraft.craft(mob, "magma_cream", 1);
            case HEALING -> CompanionCraft.count(mob, "melon_slice") > 0 && CompanionCraft.craft(mob, "glistering_melon_slice", 1);
            default -> false;
        };
    }

    /** So viel Lohenstaub bereitlegen, notfalls aus einer Lohenrute. */
    private static boolean powder(CompanionEntity mob, int need) {
        while (CompanionCraft.count(mob, "blaze_powder") < need) {
            if (CompanionCraft.count(mob, "blaze_rod") < 1 || !CompanionCraft.craft(mob, "blaze_powder", 2)) return false;
        }
        return true;
    }
}
