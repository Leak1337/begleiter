package dev.begleiter.nav;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MotionTraceTest {
    private MotionTrace.Sample sample(int tick, double speed, float angle, String state) {
        return new MotionTrace.Sample(tick, tick, 0, 0, speed, angle, angle, 0, 0, "NAVIGATION", state, 0, 0, "", 0, 0, 0);
    }
    @Test void boundedAndDisabled() {
        var trace = new MotionTrace();
        trace.record(sample(0, 0, 0, "IDLE"));
        assertTrue(trace.samples().isEmpty());
        trace.enable();
        for (int i = 0; i < 1500; i++) trace.record(sample(i, 1, 0, "WALK"));
        assertEquals(MotionTrace.CAPACITY, trace.samples().size());
        assertEquals(476, trace.samples().getFirst().tick());
        trace.record(sample(1499, 2, 0, "WALK"));
        assertEquals(MotionTrace.CAPACITY, trace.samples().size());
        assertEquals(2, trace.samples().getLast().speed());
    }
    @Test void detectsInsertedStopAndWrapsAngles() {
        var trace = new MotionTrace(); trace.enable();
        trace.record(sample(0, 0.2, 179, "WALK"));
        trace.record(sample(1, 0, -179, "WALK"));
        trace.record(sample(2, 0, -178, "WALK"));
        trace.record(sample(3, 0, -177, "WORK"));
        assertEquals(1, trace.metrics().pauses());
        assertEquals(2, trace.metrics().pauseTicks());
        assertEquals(2, trace.metrics().yawP95());
    }
}
