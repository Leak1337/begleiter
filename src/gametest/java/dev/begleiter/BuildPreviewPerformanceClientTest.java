package dev.begleiter;

import dev.begleiter.client.gui.BuildPreviewModel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Misst das erste große Modell im echten Client, nachdem die Ressourcen geladen sind. */
public final class BuildPreviewPerformanceClientTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var model = context.computeOnClient(mc -> {
            var blocks = new java.util.HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
            for (int x = 0; x < 80; x++) for (int y = 0; y < 80; y++) for (int z = 0; z < 80; z++)
                blocks.put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
            return new BuildPreviewModel(new CompanionSchematics.Schematic("large-preview", 80, 80, 80, blocks));
        });
        long[] timing = new long[3];
        context.waitFor(mc -> {
            long start = System.nanoTime();
            model.prepareFrame();
            long elapsed = System.nanoTime() - start;
            timing[0] += elapsed; timing[1] = Math.max(timing[1], elapsed); timing[2]++;
            return model.ready();
        });
        context.runOnClient(mc -> {
            if (timing[2] < 2 || model.preparedBlocks() != 512000 || model.progress() != 100)
                throw new AssertionError("Large preview was not built completely in bounded slices");
            if (model.quadCount() != 6 * 80 * 80)
                throw new AssertionError("Hidden-face optimization removed visible cube surfaces: " + model.quadCount());
            int revision = model.revision();
            model.submit(new com.mojang.blaze3d.vertex.PoseStack(), new net.minecraft.client.renderer.SubmitNodeStorage());
            model.prepareFrame();
            if (model.revision() != revision) throw new AssertionError("Finished model was rebuilt");
            com.mojang.logging.LogUtils.getLogger().info("PREVIEW_BENCH blocks=512000 meshMs={} longestSliceMs={} slices={}",
                    timing[0] / 1_000_000.0, timing[1] / 1_000_000.0, timing[2]);
            var corner = new BlockPos(1023, 15, 1023);
            var sparse = new BuildPreviewModel(new CompanionSchematics.Schematic("sparse-preview", 1024, 16, 1024,
                    java.util.Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), corner, Blocks.GLASS.defaultBlockState())));
            while (!sparse.ready()) sparse.prepareFrame();
            if (sparse.quadCount() != 12 || !sparse.getBlockState(corner).is(Blocks.GLASS)
                    || !sparse.getBlockState(corner.above()).isAir())
                throw new AssertionError("Sparse preview lost blocks or leaked them beyond its bounds");
        });
    }
}
