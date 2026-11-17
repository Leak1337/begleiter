package dev.begleiter.task;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionRecipes;
import dev.begleiter.CompanionTexts;
import dev.begleiter.ItemIds;

/** Führt ein gewähltes Vanilla-Rezept an seiner echten Station aus; Eingabe und Ergebnis werden atomar gebucht. */
final class ProcessHandler implements TaskHandler {
    private final TaskSpec.Process process;
    private int progress;
    private net.minecraft.core.BlockPos station;

    ProcessHandler(Task task) { process = (TaskSpec.Process) task.spec(); }

    @Override public Status tick(TaskContext ctx) {
        var recipe = CompanionRecipes.processingRecipe(ctx.level(), process.recipe());
        if (recipe == null || recipe.result() != ItemIds.resolve(process.item())) {
            ctx.mob().problem(CompanionTexts.tr("processing.invalid_recipe", CompanionTexts.item(process.item())));
            return Status.FAILED;
        }
        if (station == null) station = ctx.mob().findStation(recipe.station(), 24);
        if (station == null || !ctx.level().hasChunkAt(station)
                || !ctx.level().getBlockState(station).is(net.minecraft.world.level.block.Block.byItem(ItemIds.resolve(recipe.station())))) {
            ctx.mob().problem(CompanionTexts.tr("processing.missing_station", CompanionTexts.item(recipe.station())));
            return Status.FAILED;
        }
        if (!ctx.approach(station, 9.0)) return Status.RUNNING;
        if (++progress < 20) return Status.RUNNING;
        var result = CompanionRecipes.process(ctx.mob(), ctx.level(), process.recipe(), ItemIds.resolve(process.item()), process.count());
        if (result == CompanionRecipes.ProcessingResult.SUCCESS) { ctx.mob().swingArm(); return Status.DONE; }
        String key = switch (result) {
            case MISSING_INPUT -> "processing.missing_input";
            case INVENTORY_FULL -> "processing.inventory_full";
            case INVALID_RECIPE -> "processing.invalid_recipe";
            case SUCCESS -> throw new IllegalStateException("handled success");
        };
        ctx.mob().problem(CompanionTexts.tr(key, CompanionTexts.item(process.item())));
        return Status.FAILED;
    }
}
