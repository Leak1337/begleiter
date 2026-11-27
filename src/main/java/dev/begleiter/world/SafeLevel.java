package dev.begleiter.world;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;

/**
 * Lesende Weltzugriffe, die nie einen Chunk laden oder erzeugen. {@code Level.getBlockState} lädt einen fehlenden
 * Chunk synchron nach; hier bleibt Unbekanntes unbekannt ({@code null}). Wer sucht, überspringt es; wer sehen will,
 * sieht nicht hindurch; wer Gefahr prüft, nimmt das Schlimmere an. Schreibende Zugriffe gehören nicht hierher.
 */
public final class SafeLevel {
    private SafeLevel() { }

    /** Bereits vollständig geladener Chunk an dieser Stelle oder {@code null}. */
    public static LevelChunk chunk(Level level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean loaded(Level level, BlockPos pos) {
        return chunk(level, pos) != null;
    }

    /** Alle Chunks, die der Quader (Grenzen einschließlich) berührt, sind geladen. */
    public static boolean loaded(Level level, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX()) >> 4, maxX = Math.max(a.getX(), b.getX()) >> 4;
        int minZ = Math.min(a.getZ(), b.getZ()) >> 4, maxZ = Math.max(a.getZ(), b.getZ()) >> 4;
        for (int cx = minX; cx <= maxX; cx++)
            for (int cz = minZ; cz <= maxZ; cz++)
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
        return true;
    }

    /** Blockzustand oder {@code null}, wenn der Chunk nicht geladen ist. Außerhalb der Bauhöhe: Leere wie im Spiel. */
    public static BlockState state(Level level, BlockPos pos) {
        LevelChunk chunk = chunk(level, pos);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    /** Flüssigkeit oder {@code null}, wenn der Chunk nicht geladen ist. */
    public static FluidState fluid(Level level, BlockPos pos) {
        LevelChunk chunk = chunk(level, pos);
        return chunk == null ? null : chunk.getFluidState(pos);
    }

    /** Geladen und von dieser Art; Unbekanntes ist nie ein Treffer. */
    public static boolean is(Level level, BlockPos pos, Block block) {
        BlockState s = state(level, pos);
        return s != null && s.is(block);
    }

    /** Geladen und vom Prädikat bestätigt; Unbekanntes ist nie ein Treffer. */
    public static boolean test(Level level, BlockPos pos, Predicate<BlockState> predicate) {
        BlockState s = state(level, pos);
        return s != null && predicate.test(s);
    }
}
