package dev.begleiter.llm;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LlmTraceTest {
    private final AtomicLong clock = new AtomicLong();
    private final LlmTrace trace = new LlmTrace(3, clock::get);

    private static LlmResult ok() { return LlmResult.ok("x", List.of(), 420, 812, 31); }

    @Test void keepsOnlyTheNewestEntriesNewestFirst() {
        for (int i = 1; i <= 5; i++) {
            clock.set(i);
            trace.record("openai/gpt-oss-20b", ok(), "satz " + i, "ergebnis " + i);
        }
        var recent = trace.recent(10);
        assertEquals(3, recent.size());
        assertEquals("satz 5", recent.get(0).input());
        assertEquals("satz 3", recent.get(2).input());
        assertEquals(1, trace.recent(1).size());
        trace.clear();
        assertTrue(trace.recent(5).isEmpty());
    }

    @Test void formatShowsStatusLatencyTokensAndShortModel() {
        String line = LlmTrace.format(trace.record("openai/gpt-oss-20b", ok(), "grauen metall", "GATHER resource=eisen"));
        assertTrue(line.endsWith("OK 420 ms 812+31 tok gpt-oss-20b \"grauen metall\" -> GATHER resource=eisen"), line);
        String failed = LlmTrace.format(trace.record("m", LlmResult.failure(LlmResult.Status.TIMEOUT, "zu langsam", 8000), "x", "abgelehnt"));
        assertTrue(failed.contains("TIMEOUT 8000 ms m \"x\""), failed);
        assertFalse(failed.contains("tok"));
    }

    @Test void longTextsAreShortenedAndFlattened() {
        var e = trace.record("m", ok(), "a\n\n b " + "x".repeat(200), null);
        assertTrue(e.input().startsWith("a b x"));
        assertEquals(81, e.input().length());
        assertEquals("", e.outcome());
    }

    @Test void structuredOutcomesKeepAnIndependentBoundedSnapshotAndLegacyText() {
        var problems = new java.util.ArrayList<IntentTools.Problem>();
        for (int i = 0; i < 20; i++) problems.add(new IntentTools.Problem(IntentTools.Problem.Code.INVALID_FIELD, "field " + i, "x".repeat(200)));
        var display = new LlmTrace.Outcome(LlmTrace.Outcome.Kind.REJECTED, "a\n b", problems, false);
        problems.clear();
        var entry = trace.record("m", ok(), "input", "Legacy diagnostic", display.asTest());
        assertEquals("Legacy diagnostic", entry.outcome());
        assertEquals(16, entry.display().problems().size());
        assertEquals(81, entry.display().problems().getFirst().value().length());
        assertEquals("a b", entry.display().detail());
        assertTrue(entry.display().test());
        assertThrows(UnsupportedOperationException.class, () -> entry.display().problems().clear());
        assertTrue(LlmTrace.format(entry).endsWith("-> Legacy diagnostic"));
    }
}
