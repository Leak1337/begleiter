package dev.begleiter;

import net.minecraft.server.level.ServerPlayer;

/** Brücke nur für GameTests; nie in der ausgelieferten Mod-JAR. */
public final class ParkourTestHelper {
    private ParkourTestHelper() {}

    public static String build(ServerPlayer mockPlayer) {
        return ParkourCourse.buildArena(mockPlayer);
    }
}
