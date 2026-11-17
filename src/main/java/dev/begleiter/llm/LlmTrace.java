package dev.begleiter.llm;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Die letzten LLM-Aufrufe serverweit, für {@code /begleiter llm status}. Nur im Speicher, ohne Key. */
public final class LlmTrace {
    public record Entry(long timeMs, String model, LlmResult.Status status, long latencyMs, int promptTokens,
            int completionTokens, String input, String outcome, Outcome display) {
        public Entry(long timeMs, String model, LlmResult.Status status, long latencyMs, int promptTokens,
                int completionTokens, String input, String outcome) {
            this(timeMs, model, status, latencyMs, promptTokens, completionTokens, input, outcome, Outcome.literal(outcome));
        }
    }

    /** Bounded, immutable display metadata; no Minecraft/client language dependency. */
    public record Outcome(Kind kind, String detail, List<IntentTools.Problem> problems, boolean test) {
        public enum Kind { LITERAL, COMMAND, QUESTION, CHAT, REJECTED, REPLY, NO_REPLY, DROP_REMOVED, DROP_NEWER, DROP_OWNER }
        public Outcome {
            detail = shorten(detail);
            problems = problems == null ? List.of() : problems.stream().limit(16)
                    .map(p -> new IntentTools.Problem(p.code(), shorten(p.field()), shorten(p.value()))).toList();
        }
        public static Outcome literal(String text) { return new Outcome(Kind.LITERAL, text, List.of(), false); }
        public Outcome asTest() { return new Outcome(kind, detail, problems, true); }
    }

    public static final LlmTrace GLOBAL = new LlmTrace(20, System::currentTimeMillis);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int MAX_TEXT = 80;

    private final int capacity;
    private final LongSupplier clock;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public LlmTrace(int capacity, LongSupplier clock) {
        if (capacity < 1) throw new IllegalArgumentException("capacity < 1");
        this.capacity = capacity;
        this.clock = clock;
    }

    public synchronized Entry record(String model, LlmResult result, String input, String outcome) {
        return record(model, result, input, outcome, Outcome.literal(outcome));
    }

    public synchronized Entry record(String model, LlmResult result, String input, String outcome, Outcome display) {
        Entry e = new Entry(clock.getAsLong(), model, result.status(), result.latencyMs(), result.promptTokens(),
                result.completionTokens(), shorten(input), shorten(outcome), display == null ? Outcome.literal(outcome) : display);
        entries.addLast(e);
        while (entries.size() > capacity) entries.removeFirst();
        return e;
    }

    /** Neueste zuerst. */
    public synchronized List<Entry> recent(int n) {
        List<Entry> out = new ArrayList<>(Math.min(n, entries.size()));
        var it = entries.descendingIterator();
        while (it.hasNext() && out.size() < n) out.add(it.next());
        return out;
    }

    public synchronized void clear() { entries.clear(); }

    /** Eine Zeile, z.B. {@code 14:03:12 OK 420 ms 812+31 tok gpt-oss-20b "grauen metall" -> GATHER resource=eisen}. */
    public static String format(Entry e) {
        String model = e.model() == null ? "?" : e.model().substring(e.model().lastIndexOf('/') + 1);
        String tokens = e.promptTokens() + e.completionTokens() > 0 ? " " + e.promptTokens() + "+" + e.completionTokens() + " tok" : "";
        return TIME.format(Instant.ofEpochMilli(e.timeMs())) + " " + e.status() + " " + e.latencyMs() + " ms" + tokens
                + " " + model + " \"" + e.input() + "\" -> " + e.outcome();
    }

    private static String shorten(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_TEXT ? flat : flat.substring(0, MAX_TEXT) + "…";
    }
}
