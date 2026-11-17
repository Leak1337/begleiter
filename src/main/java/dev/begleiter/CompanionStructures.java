package dev.begleiter;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * Eigene Bauten als Bauaufträge: Häuser in mehreren Stilen und Größen (mit Fundament, Fenstern, Tür, Satteldach
 * aus Treppen und Einrichtung) und eine Weizenfarm. Erzeugt Schematics in lokalen Koordinaten: +z zeigt vom
 * Spieler weg, die Tür ist auf der Seite z=0 (zum Spieler hin).
 */
public final class CompanionStructures {
    private CompanionStructures() {}

    public record Style(String name, Block wall, Block corner, Block floor, Block roof, Block roofTop, Block glass, Block door) {}

    public static final Style WOOD = new Style("wood", Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.SPRUCE_PLANKS,
            Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.GLASS_PANE, Blocks.OAK_DOOR);
    public static final Style STONE = new Style("stone", Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.OAK_PLANKS,
            Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB, Blocks.GLASS_PANE, Blocks.SPRUCE_DOOR);
    public static final Style BIRCH = new Style("birch", Blocks.BIRCH_PLANKS, Blocks.BIRCH_LOG, Blocks.OAK_PLANKS,
            Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS_PANE, Blocks.BIRCH_DOOR);

    /** Stil aus Chat-Wörtern ("stein", "birke", sonst Holz). */
    public static Style styleFor(java.util.List<String> tok) {
        for (String t : tok) {
            if (t.startsWith("stein")) return STONE;
            if (t.startsWith("birk")) return BIRCH;
        }
        return WOOD;
    }

    /** Größe aus Chat-Wörtern: {Breite, Tiefe}. */
    public static int[] sizeFor(java.util.List<String> tok) {
        for (String t : tok) {
            if (t.startsWith("klein")) return new int[]{5, 5};
            if (t.startsWith("gross") || t.startsWith("riesig")) return new int[]{9, 7};
        }
        return new int[]{7, 7};
    }

    // ------------------------------------------------------------------ Haus

    /**
     * Haus mit Grundfläche w x d (Wände inklusive), Boden auf lokaler Höhe 0, Wände 1..3, Dach darüber.
     * Innen wird freigeräumt (Luft), damit z.B. Gras oder Bäume nicht im Haus stehen.
     */
    public static CompanionSchematics.Schematic house(Style s, int w, int d) {
        Map<BlockPos, BlockState> m = new HashMap<>();
        int wallTop = 3;
        // Boden und Innenraum freiräumen
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                m.put(new BlockPos(x, 0, z), s.floor().defaultBlockState());
                for (int y = 1; y <= wallTop; y++) m.put(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
        int doorX = w / 2;
        // Wände: Eckbalken, Wandblöcke, Fenster auf Höhe 2
        for (int y = 1; y <= wallTop; y++)
            for (int x = 0; x < w; x++)
                for (int z = 0; z < d; z++) {
                    boolean edgeX = x == 0 || x == w - 1, edgeZ = z == 0 || z == d - 1;
                    if (!edgeX && !edgeZ) continue;
                    BlockPos p = new BlockPos(x, y, z);
                    if (edgeX && edgeZ) {
                        m.put(p, axisY(s.corner()));
                        continue;
                    }
                    boolean doorSpot = z == 0 && x == doorX && y <= 2;
                    if (doorSpot) continue;
                    int along = edgeZ ? x : z, len = edgeZ ? w : d;
                    boolean window = y == 2 && along > 1 && along < len - 2 && along % 2 == 0
                            && !(z == 0 && Math.abs(x - doorX) <= 1);
                    m.put(p, window ? s.glass().defaultBlockState() : s.wall().defaultBlockState());
                }
        // Tür (zum Spieler hin, z=0), zwei Hälften
        BlockState door = s.door().defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        m.put(new BlockPos(doorX, 1, 0), door);
        m.put(new BlockPos(doorX, 2, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // Satteldach entlang x, mit Überstand
        int layers = (d + 1) / 2 + 1;
        for (int i = 0; i < layers; i++) {
            int y = wallTop + 1 + i;
            int zFront = -1 + i, zBack = d - i;
            if (zFront > zBack) break;
            for (int x = -1; x <= w; x++) {
                if (zFront == zBack) {
                    m.put(new BlockPos(x, y, zFront), s.roofTop().defaultBlockState());
                    continue;
                }
                m.put(new BlockPos(x, y, zFront), stairs(s.roof(), Direction.SOUTH));
                m.put(new BlockPos(x, y, zBack), stairs(s.roof(), Direction.NORTH));
            }
            // Giebel: Wand zwischen den Dachreihen an den Seiten schließen
            for (int z = zFront + 1; z < zBack; z++) {
                if (z < 0 || z >= d) continue;
                m.put(new BlockPos(0, y, z), s.wall().defaultBlockState());
                m.put(new BlockPos(w - 1, y, z), s.wall().defaultBlockState());
                for (int x = 1; x < w - 1; x++) m.putIfAbsent(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
        // Einrichtung an der Rückwand
        int back = d - 2;
        // Bett: FACING zeigt vom Fußteil zum Kopfteil (Kopf an der Rückwand)
        m.put(new BlockPos(1, 1, back), Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.HEAD));
        m.put(new BlockPos(1, 1, back - 1), Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH));
        m.put(new BlockPos(w - 2, 1, back), Blocks.CRAFTING_TABLE.defaultBlockState());
        if (w - 3 > 1) m.put(new BlockPos(w - 3, 1, back), Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, Direction.NORTH));
        if (w - 4 > 1) m.put(new BlockPos(w - 4, 1, back), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        // Licht: Fackeln an den Seitenwänden innen
        m.put(new BlockPos(1, 2, d / 2), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST));
        m.put(new BlockPos(w - 2, 2, d / 2), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.WEST));
        // Fackel außen neben der Tür
        m.put(new BlockPos(doorX + 1, 2, -1), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH));
        return CompanionSchematics.normalize(CompanionTexts.STRUCTURE_JOB + s.name(), m);
    }

    private static BlockState axisY(Block b) {
        BlockState st = b.defaultBlockState();
        return st.hasProperty(RotatedPillarBlock.AXIS) ? st.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y) : st;
    }

    private static BlockState stairs(Block b, Direction facing) {
        return b.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM);
    }

    // ------------------------------------------------------------------ Weizenfarm

    /** Feld size x size (ungerade) mit Wasser in der Mitte, Weizen gepflanzt, Zaun mit Tor, Fackeln an den Ecken. */
    public static CompanionSchematics.Schematic wheatFarm(int size) {
        Map<BlockPos, BlockState> m = new HashMap<>();
        int c = size / 2;
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++) {
                BlockPos ground = new BlockPos(x + 1, 0, z + 1);
                if (x == c && z == c) {
                    m.put(ground, Blocks.WATER.defaultBlockState());
                    m.put(ground.above(), Blocks.AIR.defaultBlockState());
                } else {
                    m.put(ground, Blocks.FARMLAND.defaultBlockState());
                    m.put(ground.above(), Blocks.WHEAT.defaultBlockState());
                }
            }
        int n = size + 2;
        for (int i = 0; i < n; i++) {
            for (BlockPos p : new BlockPos[]{new BlockPos(i, 1, 0), new BlockPos(i, 1, n - 1), new BlockPos(0, 1, i), new BlockPos(n - 1, 1, i)}) {
                m.put(p, Blocks.OAK_FENCE.defaultBlockState());
                m.put(p.below(), Blocks.DIRT.defaultBlockState());
            }
        }
        m.put(new BlockPos(n / 2, 1, 0), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH));
        for (BlockPos p : new BlockPos[]{new BlockPos(0, 2, 0), new BlockPos(n - 1, 2, 0), new BlockPos(0, 2, n - 1), new BlockPos(n - 1, 2, n - 1)})
            m.put(p, Blocks.TORCH.defaultBlockState());
        return CompanionSchematics.normalize(CompanionTexts.STRUCTURE_JOB + "farm", m);
    }

    // ------------------------------------------------------------------ Gelände

    /**
     * Fundament: unter jeder Bodenspalte (unterste Schicht des Bauauftrags) bis zum festen Boden auffüllen,
     * damit nichts in der Luft hängt. Höchstens 6 Blöcke tief.
     */
    public static void addFoundation(ServerLevel sl, Map<BlockPos, BlockState> blocks, Block fill) {
        int minY = Integer.MAX_VALUE;
        for (BlockPos p : blocks.keySet()) minY = Math.min(minY, p.getY());
        Map<BlockPos, BlockState> extra = new HashMap<>();
        for (var en : blocks.entrySet()) {
            BlockPos p = en.getKey();
            if (p.getY() != minY || en.getValue().isAir()) continue;
            for (int k = 1; k <= 6; k++) {
                BlockPos q = p.below(k);
                if (sl.isOutsideBuildHeight(q) || !sl.hasChunkAt(q)) break;
                BlockState st = sl.getBlockState(q);
                if (!st.isAir() && !st.canBeReplaced() && st.getFluidState().isEmpty()) break;
                extra.put(q, fill.defaultBlockState());
            }
        }
        extra.forEach(blocks::putIfAbsent);
    }
}
