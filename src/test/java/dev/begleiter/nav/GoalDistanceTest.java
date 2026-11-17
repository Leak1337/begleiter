package dev.begleiter.nav;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoalDistanceTest {
    @Test void distantTravelTargetCannotBeReachedThroughIntegerOverflow() {
        var target = new Goal.NearXZ(29_000_000, 29_000_000, 1);
        assertFalse(target.isInGoal(-6_942_420, 4, 12_426_311));
        assertTrue(target.heuristic(-6_942_420, 4, 12_426_311) > 1_000_000);
    }

    @Test void distantThreeDimensionalTargetCannotBeReachedThroughOverflow() {
        var target = new Goal.Near(29_000_000, 4, 29_000_000, 1);
        assertFalse(target.isInGoal(-6_942_420, 4, 12_426_311));
    }

    @Test void radiusBoundaryAndVerticalRulesRemainExact() {
        var flat = new Goal.NearXZ(0, 0, 5);
        assertTrue(flat.isInGoal(3, 999, 4));
        assertFalse(flat.isInGoal(3, 0, 5));
        var sphere = new Goal.Near(0, 0, 0, 25);
        assertTrue(sphere.isInGoal(3, 4, 0));
        assertFalse(sphere.isInGoal(3, 4, 1));
    }

    @Test void legalWorldCoordinatesAgreeWithLongDistanceReference() {
        var random = new Random(1337);
        for (int i = 0; i < 2000; i++) {
            int tx = random.nextInt(-29_999_000, 29_999_000), tz = random.nextInt(-29_999_000, 29_999_000);
            int x = i % 2 == 0 ? tx + random.nextInt(-80, 81) : random.nextInt(-29_999_000, 29_999_000);
            int z = i % 2 == 0 ? tz + random.nextInt(-80, 81) : random.nextInt(-29_999_000, 29_999_000);
            int radius = random.nextInt(0, 65);
            long dx = (long) x - tx, dz = (long) z - tz;
            assertEquals(dx * dx + dz * dz <= (long) radius * radius,
                    new Goal.NearXZ(tx, tz, radius).isInGoal(x, 0, z));
        }
    }

    @Test void travelHeuristicAndRadiusHandleExtremeInputWithoutWrapping() {
        var target = new Goal.NearXZ(Integer.MAX_VALUE, Integer.MAX_VALUE, 50_000);
        assertFalse(target.isInGoal(Integer.MIN_VALUE, 0, Integer.MIN_VALUE));
        assertTrue(Double.isFinite(target.heuristic(Integer.MIN_VALUE, 0, Integer.MIN_VALUE)));
        assertTrue(target.isInGoal(Integer.MAX_VALUE - 40_000, 0, Integer.MAX_VALUE));
    }
}
