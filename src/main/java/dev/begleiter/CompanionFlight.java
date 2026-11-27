package dev.begleiter;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Baustein Bau-Flug: Schweben beim Kreativ-Bau, Geist-Bau durch Blöcke und sanftes Landen danach. Der Bauauftrag
 * ruft {@link #keepCreativeFlight()} bzw. {@link #keepGhost()} jeden Tick, solange er fliegt. Nur Laufzeit.
 */
public final class CompanionFlight {
    private final CompanionEntity e;
    /** Letzter Tick, in dem der Kreativ-Bau ihn in der Luft halten wollte. */
    private int creativeFlightTick = Integer.MIN_VALUE / 2;
    /** Letzter Tick im Geist-Bau: solange fliegt er durch Blöcke. */
    private int ghostTick = Integer.MIN_VALUE / 2;

    CompanionFlight(CompanionEntity e) { this.e = e; }

    /** Kreativ-Bau: diesen Tick ohne Schwerkraft schweben. */
    public void keepCreativeFlight() {
        creativeFlightTick = e.tickCount;
        if (!e.isNoGravity()) e.setNoGravity(true);
        e.resetFallDistance();
    }

    /** Geist-Bau: diesen Tick durch Blöcke fliegen. */
    public void keepGhost() {
        ghostTick = e.tickCount;
        e.noPhysics = true;
        keepCreativeFlight();
    }

    void tick() {
        endGhost();
        landAfterCreativeFlight();
    }

    /** Geist vorbei: wieder fest; steckt er in einem Block, nach oben ins Freie setzen. */
    private void endGhost() {
        if (!e.noPhysics || e.tickCount - ghostTick <= 2) return;
        e.noPhysics = false;
        if (e.level() instanceof ServerLevel sl && !sl.noCollision(e)) {
            BlockPos p = e.blockPosition();
            for (int dy = 0; dy < 32; dy++) {
                BlockPos up = p.above(dy);
                if (sl.isOutsideBuildHeight(up)) break;
                if (sl.noCollision(e, e.getBoundingBox().move(0, up.getY() - e.getY(), 0))) {
                    e.snapTo(e.getX(), up.getY(), e.getZ(), e.getYRot(), e.getXRot());
                    break;
                }
            }
        }
    }

    /**
     * Fliegt er nicht mehr (Bau fertig, pausiert, andere Aufgabe, Neustart mitten im Flug): langsam senkrecht
     * landen, ohne Fallschaden, dann wieder mit Schwerkraft.
     */
    private void landAfterCreativeFlight() {
        if (!e.isNoGravity() || e.tickCount - creativeFlightTick <= 10) return;
        e.resetFallDistance();
        if (e.onGround() || e.isInWater() || e.isInLava()) { e.setNoGravity(false); return; }
        Vec3 v = e.getDeltaMovement();
        e.setDeltaMovement(v.x * 0.5, -0.4, v.z * 0.5);
    }
}
