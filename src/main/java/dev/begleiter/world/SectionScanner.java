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
}
