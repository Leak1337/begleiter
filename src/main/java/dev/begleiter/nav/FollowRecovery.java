package dev.begleiter.nav;

/** Letzte Rettung beim Folgen: Teleport ab dieser Entfernung. 0 schaltet Teleports in derselben Dimension ganz ab. */
public final class FollowRecovery {
    private FollowRecovery() {}
    public static final int TELEPORT_DISTANCE = Math.max(0, Math.min(1024,
            Integer.getInteger("begleiter.follow.teleportDistance", 64)));
    public static boolean beyond(double distanceSquared, double minimum) {
        double distance = Math.max(TELEPORT_DISTANCE, minimum);
        return TELEPORT_DISTANCE > 0 && distanceSquared > distance * distance;
    }
}
