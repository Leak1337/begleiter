package dev.begleiter;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.nav.BlockFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Baustein Erkunden: Ziele in einer gleichbleibenden Richtung, begehbarer Boden unter Laub und im Nether. Nur Laufzeit. */
public final class CompanionExplore {
    private final CompanionEntity e;
    private double angle = Double.NaN;
    /** Wo das letzte Ziel vergeben wurde: steht er beim nächsten Aufruf noch dort, war das Ziel unerreichbar. */
    private BlockPos lastFrom;
    /** Weites Wegziehen aus einer kargen Gegend: feste Richtung, wachsende Ausweichwinkel bei Blockade. */
    private double travelAngle = Double.NaN;
    private BlockPos lastFarFrom;
    private int farBlocked;

    CompanionExplore(CompanionEntity e) { this.e = e; }

    void reset() { angle = Double.NaN; lastFrom = null; travelAngle = Double.NaN; lastFarFrom = null; farBlocked = 0; }

    /** Die Besitzer-Leine gilt nur ohne eigenes Spielziel: mit Spielziel zieht er allein los wie ein Spieler. */
    private boolean leashed(Player o, int x, int z) {
        return o != null && !e.journey.active() && o.distanceToSqr(x, o.getY(), z) > 80.0 * 80.0;
    }

    /**
     * Ein weiter Schritt in fester Richtung (karge Gegend verlassen). Eis und Schnee sind Boden; Wasser nur, wenn
     * keine trockene Richtung in der Nähe des Kurses frei ist (dann wird geschwommen). null = nichts Geladenes frei.
     */
    Task far() {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        var random = e.getRandom();
        if (Double.isNaN(travelAngle)) travelAngle = Double.isNaN(angle) ? random.nextDouble() * Math.PI * 2 : angle;
        if (lastFarFrom != null && lastFarFrom.distSqr(e.blockPosition()) < 8 * 8) {
            farBlocked++;
            travelAngle += (farBlocked % 2 == 1 ? 1 : -1) * 0.6 * farBlocked; // abwechselnd links/rechts, immer weiter
        } else farBlocked = 0;
        lastFarFrom = e.blockPosition();
        Player o = e.getOwner();
        double[] offsets = {0, 0.35, -0.35, 0.7, -0.7, 1.05, -1.05, 1.4, -1.4};
        for (int pass = 0; pass < 2; pass++) {
            for (double off : offsets) {
                double ang = travelAngle + off;
                for (int d : new int[] {40, 28}) {
                    int x = (int) Math.floor(e.getX() + Math.cos(ang) * d), z = (int) Math.floor(e.getZ() + Math.sin(ang) * d);
                    if (leashed(o, x, z) || !sl.hasChunkAt(new BlockPos(x, 64, z))) continue;
                    BlockPos target;
                    int y = explorationSurfaceY(sl, x, z);
                    if (y != Integer.MIN_VALUE) {
                        BlockPos ground = new BlockPos(x, y - 1, z);
                        BlockState gs = sl.getBlockState(ground);
                        if (gs.is(BlockTags.LEAVES) || gs.getCollisionShape(sl, ground).isEmpty()) continue;
                        target = new BlockPos(x, y, z);
                    } else if (pass == 1) {
                        int top = sl.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                        if (sl.getFluidState(new BlockPos(x, top - 1, z)).isEmpty()) continue;
                        target = new BlockPos(x, top, z); // Wasseroberfläche: hinüberschwimmen
                    } else continue;
                    if (e.hazardAround(sl, target, pass == 1)) continue; // im zweiten Durchgang ist Wasser erlaubt
                    travelAngle = ang;
                    return Task.moveTo(target, 9);
                }
            }
        }
        return null;
    }

    /** Mit Decke (Nether): die Heightmap zeigt aufs Dach. Stattdessen nahe der eigenen Höhe einen Boden mit 2 Luft darüber suchen. */
    private int floorBelowCeiling(ServerLevel sl, int x, int z) {
        int base = e.getBlockY();
        for (int dy = 0; dy <= 12; dy++) {
            for (int y : new int[] { base + dy, base - dy }) {
                BlockPos p = new BlockPos(x, y, z);
                if (sl.getBlockState(p).isAir() && sl.getBlockState(p.above()).isAir()
                        && !sl.getBlockState(p.below()).getCollisionShape(sl, p.below()).isEmpty()) return y;
                if (dy == 0) break;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Die Heightmap endet auch auf Dschungelstämmen und Bambus: den begehbaren Boden unter dem Blätterdach suchen. */
    public int explorationSurfaceY(ServerLevel sl, int x, int z) {
        if (sl.dimensionType().hasCeiling()) return floorBelowCeiling(sl, x, z);
        int top = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int bottom = Math.max(sl.getMinY() + 1, top - 48);
        for (int y = top; y >= bottom; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            BlockState ground = sl.getBlockState(feet.below());
            if (ground.is(BlockTags.LEAVES) || ground.is(BlockTags.LOGS)
                    || ground.is(Blocks.RED_MUSHROOM_BLOCK) || ground.is(Blocks.BROWN_MUSHROOM_BLOCK)
                    || ground.is(Blocks.MUSHROOM_STEM)) continue;
            int support = BlockFlags.of(ground);
            if (!BlockFlags.has(support, BlockFlags.STAND)) continue;
            int low = BlockFlags.of(sl.getBlockState(feet));
            int high = BlockFlags.of(sl.getBlockState(feet.above()));
            if (BlockFlags.has(low, BlockFlags.PASS) && BlockFlags.has(high, BlockFlags.PASS)) return y;
            // nicht durch Gelände/Wasser hindurch nach einer fremden Höhle darunter suchen
            return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /** Erkundungsziel: behält die Richtung bei (wie ein Spieler, der in eine Richtung läuft), meidet Wasser und Lava. */
    Task point() {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        Player o = e.getOwner();
        var random = e.getRandom();
        if (Double.isNaN(angle)) angle = random.nextDouble() * Math.PI * 2;
        // Kaum von der Stelle gekommen: die Richtung war versperrt, nicht immer wieder dieselbe probieren
        if (lastFrom != null && lastFrom.distSqr(e.blockPosition()) < 4 * 4) angle += 2.1 + random.nextDouble() * 0.6;
        lastFrom = e.blockPosition();
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = angle + (random.nextDouble() - 0.5) * 0.6;
            double d = 14 + random.nextInt(12);
            int x = (int) (e.getX() + Math.cos(ang) * d), z = (int) (e.getZ() + Math.sin(ang) * d);
            boolean ok = !leashed(o, x, z);
            if (ok && !sl.hasChunkAt(new BlockPos(x, 64, z))) ok = false;
            if (ok) {
                int y = explorationSurfaceY(sl, x, z);
                if (y == Integer.MIN_VALUE) { angle += 0.9; continue; }
                BlockPos ground = new BlockPos(x, y - 1, z);
                BlockState gs = sl.getBlockState(ground);
                if (!gs.getFluidState().isEmpty() || gs.is(BlockTags.LEAVES) || gs.getCollisionShape(sl, ground).isEmpty()
                        || e.hazardAround(sl, ground.above(), false)) ok = false;
                if (ok) {
                    angle = ang;
                    return Task.moveTo(new BlockPos(x, y, z));
                }
            }
            angle += 0.9; // anderes Gelände probieren (Wasser, Hang ...)
            if (attempt == 6 && o != null) angle = Math.atan2(o.getZ() - e.getZ(), o.getX() - e.getX());
        }
        return null;
    }
}
