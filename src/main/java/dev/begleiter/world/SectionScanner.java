package dev.begleiter.world;

import java.util.function.Predicate;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Nur lesende Suche in einer waagerechten Schicht. Fordert nie einen Chunk an und erzeugt keinen. */
public final class SectionScanner {
    private SectionScanner() { }

    @FunctionalInterface
    public interface Visitor {
        /** false beendet sofort. Der Besucher darf die durchsuchte Welt nicht verändern. */
        boolean visit(int x, int y, int z, BlockState state);
    }

    /** Block reads exclude palette checks; chunks are looked up at most once per scan. */
    public record Result(long blockReads, int chunksChecked, int sectionsSkipped, boolean completed) { }

    /**
     * Grenzen einschließlich, Reihenfolge erst x, dann z, wie in der Wahrnehmung.
     * Luft wird immer übersprungen: zum Finden freier Bauplätze ist diese Schnittstelle ungeeignet.
     * Das Prädikat darf nur vom Blockzustand abhängen (es filtert auch die Palette).
     */
    public static Result scanLayer(ServerLevel level, int minX, int maxX, int y, int minZ, int maxZ,
                                   Predicate<BlockState> predicate, Visitor visitor) {
        if (minX > maxX || minZ > maxZ || level.isOutsideBuildHeight(y)) return new Result(0, 0, 0, true);
        int firstZ = minZ >> 4, lastZ = maxZ >> 4;
        var sections = new LevelChunkSection[lastZ - firstZ + 1];
        long reads = 0;
        int chunks = 0, skipped = 0;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = firstZ; cz <= lastZ; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                chunks++;
                LevelChunkSection section = chunk == null ? null : chunk.getSection(level.getSectionIndex(y));
                if (section != null && (section.hasOnlyAir() || !section.maybeHas(predicate))) {
                    section = null;
                    skipped++;
                }
                sections[cz - firstZ] = section;
            }
            int firstX = Math.max(minX, cx << 4), lastX = Math.min(maxX, (cx << 4) | 15);
            for (int x = firstX; x <= lastX; x++) {
                for (int cz = firstZ; cz <= lastZ; cz++) {
                    var section = sections[cz - firstZ];
                    if (section == null) continue;
                    int startZ = Math.max(minZ, cz << 4), endZ = Math.min(maxZ, (cz << 4) | 15);
                    for (int z = startZ; z <= endZ; z++) {
                        BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                        reads++;
                        if (!state.isAir() && predicate.test(state) && !visitor.visit(x, y, z, state))
                            return new Result(reads, chunks, skipped, false);
                    }
                }
            }
        }
        return new Result(reads, chunks, skipped, true);
    }

    /**
     * Quader (Grenzen einschließlich) nach Blöcken durchsuchen, die das Prädikat erfüllen. Ungeladene Chunks bleiben
     * ungeladen und werden übersprungen; Abschnitte ohne passenden Eintrag in ihrer Palette werden mit einer einzigen
     * Prüfung übersprungen. Die Besuchsreihenfolge ist nicht festgelegt: Wer nach Abstand wählt, muss Gleichstände
     * selbst auflösen. Luft wird nie gemeldet; das Prädikat darf nur vom Blockzustand abhängen.
     */
    public static Result scanBox(ServerLevel level, net.minecraft.core.BlockPos a, net.minecraft.core.BlockPos b,
                                 Predicate<BlockState> predicate, Visitor visitor) {
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        int minY = Math.max(Math.min(a.getY(), b.getY()), level.getMinY());
        int maxY = Math.min(Math.max(a.getY(), b.getY()), level.getMaxY());
        long reads = 0;
        int chunks = 0, skipped = 0;
        if (minY > maxY) return new Result(0, 0, 0, true);
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                chunks++;
                if (chunk == null) continue;
                int x0 = Math.max(minX, cx << 4), x1 = Math.min(maxX, (cx << 4) | 15);
                int z0 = Math.max(minZ, cz << 4), z1 = Math.min(maxZ, (cz << 4) | 15);
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
                    if (section.hasOnlyAir() || !section.maybeHas(predicate)) { skipped++; continue; }
                    int y0 = Math.max(minY, sy << 4), y1 = Math.min(maxY, (sy << 4) | 15);
                    for (int y = y0; y <= y1; y++)
                        for (int z = z0; z <= z1; z++)
                            for (int x = x0; x <= x1; x++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                reads++;
                                if (!state.isAir() && predicate.test(state) && !visitor.visit(x, y, z, state))
                                    return new Result(reads, chunks, skipped, false);
                            }
                }
            }
        }
        return new Result(reads, chunks, skipped, true);
    }
}
