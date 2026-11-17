package dev.begleiter.llm;

/** Serverweiter Zugang: aktuelle Config, Budget und Client. {@link #reload()} liest die Config-Datei neu. */
public final class Llm {
    private Llm() {}

    private record State(LlmConfig config, LlmBudget budget, LlmClient client) {}

    private static volatile State state;

    public static LlmConfig config() { return state().config; }

    public static LlmBudget budget() { return state().budget; }

    public static LlmClient client() { return state().client; }

    /** Liest {@code config/begleiter-llm.json} neu (legt beim ersten Mal eine ausgeschaltete Vorlage an). */
    public static LlmConfig reload() {
        LlmConfig config = LlmConfig.load(LlmConfig.defaultFile());
        LlmBudget budget = new LlmBudget(config);
        state = new State(config, budget, new OpenAiCompatibleClient(config, budget));
        return config;
    }

    /** Für Tests: festen Client statt Netzwerk verwenden; null stellt den normalen Zustand wieder her. */
    public static void useForTests(LlmConfig config, LlmClient client) {
        state = config == null ? null : new State(config, new LlmBudget(config), client);
    }

    private static State state() {
        State s = state;
        if (s == null) {
            synchronized (Llm.class) {
                if (state == null) reload();
                s = state;
            }
        }
        return s;
    }
}
