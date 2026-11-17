package dev.begleiter.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.begleiter.CompanionSchematics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Eigene, voll ausgeleuchtete Blockansicht: liest und ändert nie Blöcke der Spielerwelt. */
public final class BuildPreviewModel implements BlockAndTintGetter {
    private static final int FULL_BRIGHT = net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;
    private final CompanionSchematics.Schematic plan;
    private final Map<RenderType, List<Quad>> quads = new LinkedHashMap<>();
    private final List<Special> specials = new ArrayList<>();
    private final List<FluidVertex> fluids = new ArrayList<>();
    private Object modelSet;
    private java.util.Iterator<Map.Entry<BlockPos, BlockState>> remaining;
    private ModelBlockRenderer renderer;
    private FluidRenderer fluidRenderer;
    private int preparedBlocks, revision;
    private boolean indexing;
    private int indexedBlocks;
    private BlockState[] denseBlocks;
    private it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> sparseBlocks;
    private final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
    private static final net.minecraft.core.Direction[] FACES = net.minecraft.core.Direction.values();
    /** Das Aufbereiten bleibt in einem kleinen Teil des Bildes, auch auf langsamen Rechnern. */
    private static final int BLOCKS_PER_FRAME = 65536;
    private static final long FRAME_BUDGET_NS = 6_000_000;
    private record Quad(float x, float y, float z, BakedQuad quad, QuadInstance light) {}
    private record Special(BlockPos pos, ItemStackRenderState item) {}
    private record FluidVertex(float x, float y, float z, int color, float u, float v) {}

    public BuildPreviewModel(CompanionSchematics.Schematic plan) { this.plan = plan; }
    public CompanionSchematics.Schematic plan() { return plan; }

    public boolean ready() { return remaining == null && modelSet != null; }
    public int revision() { return revision; }
    public int preparedBlocks() { return preparedBlocks; }
    public int quadCount() { return quads.values().stream().mapToInt(List::size).sum(); }
    public int progress() { return plan.blocks().isEmpty() ? 100
            : (int) ((10L * indexedBlocks + 90L * preparedBlocks) / plan.blocks().size()); }

    /** Je Bild ein Stück aufbereiten, statt ein Netz aus einer Million Blöcken in einem Zug zu bauen. */
    public void prepareFrame() { prepareFrame(FRAME_BUDGET_NS); }
    /** Vier sichtbare Karten teilen sich das Bild; jede bekommt weniger Zeit als die große Vorschau. */
    public void prepareThumbnailFrame() { prepareFrame(1_000_000); }
    private void prepareFrame(long budgetNs) {
        var mc = Minecraft.getInstance();
        var models = mc.getModelManager().getBlockStateModelSet();
        if (modelSet != models) {
            modelSet = models;
            quads.clear(); specials.clear(); fluids.clear();
            preparedBlocks = 0;
            revision++;
            remaining = plan.blocks().entrySet().iterator();
            if (indexing) indexedBlocks = 0;
            // BlockPos.hashCode kollidiert in großen, regelmäßigen Bauten stark. Für die vielen Nachbarabfragen
            // beim Ausblenden verdeckter Flächen direkte Indizes bzw. gepackte Schlüssel, vorab schrittweise aufgebaut.
            if (denseBlocks == null && sparseBlocks == null) {
                int volume = plan.sizeX() * plan.sizeY() * plan.sizeZ();
                if (volume <= 2_000_000 && volume <= 4L * plan.blocks().size()) denseBlocks = new BlockState[volume];
                else sparseBlocks = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(plan.blocks().size());
                indexing = true;
            }
            // Für freiliegende und nicht würfelförmige Blöcke das normale, formabhängige Ausblenden behalten
            renderer = new ModelBlockRenderer(false, true, mc.getBlockColors());
            fluidRenderer = new FluidRenderer(mc.getModelManager().getFluidStateModelSet());
        }
        if (remaining == null) return;
        long deadline = System.nanoTime() + budgetNs;
        if (indexing) {
            int indexed = 0;
            while (remaining.hasNext() && indexed < BLOCKS_PER_FRAME) {
                if (indexed != 0 && (indexed & 31) == 0 && System.nanoTime() >= deadline) return;
                var entry = remaining.next();
                var pos = entry.getKey();
                if (denseBlocks != null) denseBlocks[(pos.getY() * plan.sizeZ() + pos.getZ()) * plan.sizeX() + pos.getX()] = entry.getValue();
                else sparseBlocks.put(pos.asLong(), entry.getValue());
                indexed++; indexedBlocks++;
            }
            if (remaining.hasNext()) return;
            indexing = false;
            remaining = plan.blocks().entrySet().iterator();
            return;
        }
        int processed = 0;
        while (remaining.hasNext() && processed < BLOCKS_PER_FRAME) {
            if (processed != 0 && (processed & 31) == 0 && System.nanoTime() >= deadline) break;
            var entry = remaining.next();
            processed++;
            preparedBlocks++;
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (state.isAir() || enclosed(pos, state)) continue;
            if (state.getRenderShape() == RenderShape.MODEL) {
                renderer.tesselateBlock((x, y, z, quad, light) -> {
                    var copy = new QuadInstance();
                    for (int i = 0; i < 4; i++) {
                        copy.setColor(i, light.getColor(i));
                        copy.setLightCoords(i, FULL_BRIGHT);
                    }
                    copy.setOverlayCoords(OverlayTexture.NO_OVERLAY);
                    quads.computeIfAbsent(quad.materialInfo().itemRenderType(), key -> new ArrayList<>())
                            .add(new Quad(x, y, z, quad, copy));
                }, pos.getX(), pos.getY(), pos.getZ(), this, pos, state, models.get(state), state.getSeed(pos));
            } else if (state.hasBlockEntity() && !state.getBlock().asItem().getDefaultInstance().isEmpty()) {
                var item = new ItemStackRenderState();
                mc.getItemModelResolver().updateForTopItem(item, state.getBlock().asItem().getDefaultInstance(),
                        ItemDisplayContext.NONE, mc.level, null, 0);
                specials.add(new Special(pos, item));
            }
            if (!state.getFluidState().isEmpty()) {
                var output = new FluidOutput(pos);
                fluidRenderer.tesselate(this, pos, layer -> output, state, state.getFluidState());
                output.finish();
            }
        }
        if (!remaining.hasNext()) remaining = null;
        if (processed != 0) revision++;
    }

    /** Ganz verdeckte Würfel haben keine sichtbare Fläche: Modellwahl und Zerlegung ganz auslassen. */
    private boolean enclosed(BlockPos pos, BlockState state) {
        if (!state.isSolidRender()) return false;
        for (var face : FACES) {
            neighbour.setWithOffset(pos, face);
            if (!getBlockState(neighbour).isSolidRender()) return false;
        }
        return true;
    }

    public void submit(PoseStack pose, SubmitNodeCollector collector) {
        for (var group : quads.entrySet()) {
            collector.submitCustomGeometry(pose, group.getKey(), (transform, consumer) -> {
                var translated = transform.copy();
                for (var q : group.getValue()) {
                    translated.set(transform);
                    translated.translate(q.x, q.y, q.z);
                    consumer.putBakedQuad(translated, q.quad, q.light);
                }
            });
        }
        if (!fluids.isEmpty()) collector.submitCustomGeometry(pose, RenderTypes.translucentMovingBlock(), (transform, consumer) -> {
            for (var v : fluids) consumer.addVertex(transform, v.x, v.y, v.z).setColor(v.color).setUv(v.u, v.v)
                    .setLight(FULL_BRIGHT).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setNormal(transform, 0, 1, 0);
        });
        for (var s : specials) {
            pose.pushPose();
            pose.translate(s.pos.getX() + .5f, s.pos.getY() + .5f, s.pos.getZ() + .5f);
            var state = getBlockState(s.pos);
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING))
                pose.rotateDegrees(com.mojang.math.Axis.YP, 180 - state.getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING).toYRot());
            s.item.submit(pose, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
    }

    /** FluidRenderer liefert Punkte relativ zum Chunk-Abschnitt; hier zurück auf ihre Lage im Schematic. */
    private final class FluidOutput implements VertexConsumer {
        private final int offsetX, offsetY, offsetZ;
        private float x, y, z, u, v;
        private int color = -1;
        private boolean pending;
        FluidOutput(BlockPos pos) {
            offsetX = Math.floorDiv(pos.getX(), 16) * 16;
            offsetY = Math.floorDiv(pos.getY(), 16) * 16;
            offsetZ = Math.floorDiv(pos.getZ(), 16) * 16;
        }
        void finish() {
            if (pending) fluids.add(new FluidVertex(x + offsetX, y + offsetY, z + offsetZ, color, u, v));
            pending = false;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            finish(); this.x = x; this.y = y; this.z = z; pending = true; return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return setColor(a << 24 | r << 16 | g << 8 | b); }
        @Override public VertexConsumer setColor(int color) { this.color = color; return this; }
        @Override public VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        if (indexing || denseBlocks == null && sparseBlocks == null)
            return plan.blocks().getOrDefault(pos, Blocks.AIR.defaultBlockState());
        BlockState state;
        if (denseBlocks != null) {
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            if (x < 0 || y < 0 || z < 0 || x >= plan.sizeX() || y >= plan.sizeY() || z >= plan.sizeZ())
                return Blocks.AIR.defaultBlockState();
            state = denseBlocks[(y * plan.sizeZ() + z) * plan.sizeX() + x];
        } else state = sparseBlocks.get(pos.asLong());
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public int getHeight() { return plan.sizeY(); }
    @Override public int getMinY() { return 0; }
    @Override public LevelLightEngine getLightEngine() { return BlockAndTintGetter.EMPTY.getLightEngine(); }
    @Override public int getBrightness(LightLayer layer, BlockPos pos) { return 15; }
    @Override public int getRawBrightness(BlockPos pos, int amount) { return 15; }
    @Override public CardinalLighting cardinalLighting() { return CardinalLighting.DEFAULT; }
    @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return resolver == net.minecraft.client.renderer.BiomeColors.WATER_COLOR_RESOLVER ? 0x3F76E4 : 0x91BD59;
    }
}
