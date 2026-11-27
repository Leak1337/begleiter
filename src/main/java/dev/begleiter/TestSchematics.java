package dev.begleiter;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Test-Bauten für die Schematic-Szenarien ({@link TestScenarios}): als echte Dateien in "schematics/" geschrieben
 * (.nbt wie vom Strukturblock, .schem wie von WorldEdit/Sponge v2) und dann über {@link CompanionSchematics} geladen –
 * so läuft derselbe Weg wie bei einem Schematic des Spielers.
 */
final class TestSchematics {
    private TestSchematics() {}

    /** Hütte 5 × 4 × 5: Bretterboden, Bruchsteinwände, Tür (beide Hälften), Fenster, Wandfackel, Stufe, Plattendach. */
    static Map<BlockPos, BlockState> hut() {
        Map<BlockPos, BlockState> m = new LinkedHashMap<>();
        for (int x = 0; x < 5; x++)
            for (int z = 0; z < 5; z++) {
                m.put(new BlockPos(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
                m.put(new BlockPos(x, 3, z), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                for (int y = 1; y <= 2; y++) if (edge) m.put(new BlockPos(x, y, z), Blocks.COBBLESTONE.defaultBlockState());
            }
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        m.put(new BlockPos(2, 1, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        m.put(new BlockPos(2, 2, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        m.put(new BlockPos(0, 2, 2), Blocks.GLASS.defaultBlockState());
        m.put(new BlockPos(4, 2, 2), Blocks.GLASS.defaultBlockState());
        m.put(new BlockPos(2, 2, 3), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH));
        m.put(new BlockPos(2, 3, 0), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
        return m;
    }

    /** Turm 3 × 6 × 3: Bruchstein-Ring, innen eine Leiter an der Nordwand, oben Steinplatten. */
    static Map<BlockPos, BlockState> tower() {
        Map<BlockPos, BlockState> m = new LinkedHashMap<>();
        for (int y = 0; y <= 4; y++)
            for (int x = 0; x < 3; x++)
                for (int z = 0; z < 3; z++)
                    if (x != 1 || z != 1) m.put(new BlockPos(x, y, z), Blocks.COBBLESTONE.defaultBlockState());
                    else m.put(new BlockPos(x, y, z), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        for (int x = 0; x < 3; x++)
            for (int z = 0; z < 3; z++) m.put(new BlockPos(x, 5, z), Blocks.STONE_SLAB.defaultBlockState());
        return m;
    }

    /**
     * Zweistöckiges Haus 7 × 8 × 7: Stammecken, Bretterwände, Glasscheiben, Tür, Zwischendecke mit Leiter-Durchstieg,
     * Einrichtung (Werkbank, Ofen, Kiste, Tisch aus Zaun und Druckplatte, Bücherregal, Teppich, Laterne, Wandfackeln)
     * und Plattendach mit Treppenkante vorne.
     */
    static Map<BlockPos, BlockState> house() {
        Map<BlockPos, BlockState> m = new LinkedHashMap<>();
        for (int x = 0; x < 7; x++)
            for (int z = 0; z < 7; z++) {
                boolean edge = x == 0 || x == 6 || z == 0 || z == 6, corner = (x == 0 || x == 6) && (z == 0 || z == 6);
                m.put(new BlockPos(x, 0, z), Blocks.COBBLESTONE.defaultBlockState());
                m.put(new BlockPos(x, 4, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
                m.put(new BlockPos(x, 7, z), Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                if (!edge) continue;
                for (int y : new int[]{1, 2, 3, 5, 6})
                    m.put(new BlockPos(x, y, z), (corner ? Blocks.OAK_LOG : Blocks.OAK_PLANKS).defaultBlockState());
            }
        // Fenster
        for (int[] w : new int[][]{{0, 2, 3}, {6, 2, 3}, {1, 2, 0}, {5, 2, 0}, {0, 5, 3}, {6, 5, 3}, {3, 5, 0}, {3, 5, 6}})
            m.put(new BlockPos(w[0], w[1], w[2]), Blocks.GLASS_PANE.defaultBlockState());
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        m.put(new BlockPos(3, 1, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        m.put(new BlockPos(3, 2, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // Leiter an der Rückwand durch die Decke (Loch bei 5,4,5) bis oben
        for (int y = 1; y <= 5; y++) m.put(new BlockPos(5, y, 5), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        // Einrichtung unten
        m.put(new BlockPos(1, 1, 5), Blocks.CRAFTING_TABLE.defaultBlockState());
        m.put(new BlockPos(1, 1, 4), Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.FurnaceBlock.FACING, Direction.EAST));
        m.put(new BlockPos(1, 1, 3), Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, Direction.EAST));
        m.put(new BlockPos(4, 1, 3), Blocks.OAK_FENCE.defaultBlockState());
        m.put(new BlockPos(4, 2, 3), Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
        m.put(new BlockPos(2, 2, 5), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH));
        // Einrichtung oben
        m.put(new BlockPos(1, 5, 5), Blocks.BOOKSHELF.defaultBlockState());
        m.put(new BlockPos(2, 5, 5), Blocks.BOOKSHELF.defaultBlockState());
        m.put(new BlockPos(3, 5, 3), Blocks.MOSS_CARPET.defaultBlockState());
        m.put(new BlockPos(1, 5, 1), Blocks.LANTERN.defaultBlockState());
        m.put(new BlockPos(4, 6, 5), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH));
        // Treppenkante über der Tür
        for (int x = 0; x < 7; x++)
            m.put(new BlockPos(x, 7, 0), Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        return m;
    }

    /** Schaltung: Hebel (an) -> Staub -> Lampe, in einer Reihe. */
    static Map<BlockPos, BlockState> lampCircuit() {
        Map<BlockPos, BlockState> m = new LinkedHashMap<>();
        m.put(new BlockPos(0, 0, 0), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.POWERED, true));
        m.put(new BlockPos(1, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState());
        m.put(new BlockPos(2, 0, 0), Blocks.REDSTONE_LAMP.defaultBlockState());
        return m;
    }

    private static int[] size(Map<BlockPos, BlockState> m) {
        int sx = 0, sy = 0, sz = 0;
        for (BlockPos p : m.keySet()) { sx = Math.max(sx, p.getX() + 1); sy = Math.max(sy, p.getY() + 1); sz = Math.max(sz, p.getZ() + 1); }
        return new int[]{sx, sy, sz};
    }

    private static Path file(String name) throws IOException {
        Path dir = CompanionSchematics.folders().getFirst();
        Files.createDirectories(dir);
        // Die gleich geschriebene Datei soll sofort im Bauplan-Katalog stehen, nicht erst nach Ablauf des Listen-Caches
        CompanionSchematics.forgetList();
        return dir.resolve(name);
    }

    /** Als Strukturblock-Datei (.nbt): size, palette, blocks. Leere Stellen bleiben weg (= nicht Teil des Baus). */
    static Path writeStructure(String name, Map<BlockPos, BlockState> m) throws IOException {
        int[] s = size(m);
        List<BlockState> palette = new ArrayList<>();
        ListTag blocks = new ListTag();
        for (var en : m.entrySet()) {
            int idx = palette.indexOf(en.getValue());
            if (idx < 0) { idx = palette.size(); palette.add(en.getValue()); }
            CompoundTag b = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(IntTag.valueOf(en.getKey().getX())); pos.add(IntTag.valueOf(en.getKey().getY())); pos.add(IntTag.valueOf(en.getKey().getZ()));
            b.put("pos", pos);
            b.putInt("state", idx);
            blocks.add(b);
        }
        ListTag pal = new ListTag();
        for (BlockState st : palette) pal.add(NbtUtils.writeBlockState(st));
        ListTag sz = new ListTag();
        for (int v : s) sz.add(IntTag.valueOf(v));
        CompoundTag t = new CompoundTag();
        t.put("size", sz);
        t.put("palette", pal);
        t.put("blocks", blocks);
        Path f = file(name + ".nbt");
        NbtIo.writeCompressed(t, f);
        return f;
    }

    /** Als WorldEdit-Datei (.schem, Sponge v2): Palette "Zustand -> Nummer", Blockdaten als VarInts (y, z, x). */
    static Path writeSponge(String name, Map<BlockPos, BlockState> m) throws IOException {
        int[] s = size(m);
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put(BlockStateParser.serialize(Blocks.AIR.defaultBlockState()), 0);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int y = 0; y < s[1]; y++)
            for (int z = 0; z < s[2]; z++)
                for (int x = 0; x < s[0]; x++) {
                    BlockState st = m.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    int id = palette.computeIfAbsent(BlockStateParser.serialize(st), k -> palette.size());
                    while ((id & ~0x7F) != 0) { data.write((id & 0x7F) | 0x80); id >>>= 7; }
                    data.write(id);
                }
        CompoundTag pal = new CompoundTag();
        palette.forEach(pal::putInt);
        CompoundTag t = new CompoundTag();
        t.putInt("Version", 2);
        t.putShort("Width", (short) s[0]);
        t.putShort("Height", (short) s[1]);
        t.putShort("Length", (short) s[2]);
        t.putInt("PaletteMax", palette.size());
        t.put("Palette", pal);
        t.putByteArray("BlockData", data.toByteArray());
        Path f = file(name + ".schem");
        NbtIo.writeCompressed(t, f);
        return f;
    }
}
