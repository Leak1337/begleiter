package dev.begleiter.llm;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatReplyTest {
    @Test void requestCarriesContextHistoryAndNoTools() {
        var history = new ChatHistory();
        history.add("wie gehts", "Gut, danke!");
        var r = ChatReply.request(new Object(), "openai/gpt-oss-20b", "Leak", "de_de", "inventory: torch 5\n",
                history.messages(), "hast du fackeln?");
        assertNull(r.tools());
        assertEquals("low", r.reasoningEffort());
        assertEquals(4, r.messages().size());
        String system = r.messages().getFirst().content();
        assertTrue(system.startsWith("You are Leak,"), system);
        assertTrue(system.contains("Reply in German"));
        assertTrue(system.endsWith("Your state:\ninventory: torch 5\n"));
        assertEquals(List.of("system", "user", "assistant", "user"), r.messages().stream().map(LlmRequest.Message::role).toList());
        assertEquals("hast du fackeln?", r.messages().getLast().content());
        assertNull(ChatReply.request(new Object(), "qwen/qwen3.8-27b", null, "en_us", "", List.of(), "hi").reasoningEffort());
        assertTrue(ChatReply.request(new Object(), "m", " ", "en_us", "", List.of(), "hi").messages().getFirst().content()
                .startsWith("You are Leak,"));
    }

    @Test void cleanMakesOneShortChatLine() {
        assertEquals("Mir geht's gut! Ich hab 5 Fackeln.", ChatReply.clean("  **Mir geht's gut!**\n\nIch hab 5 Fackeln. "));
        assertEquals("Klar doch", ChatReply.clean("\"Klar doch\""));
        assertEquals("Klar doch", ChatReply.clean("„Klar doch“"));
        assertNull(ChatReply.clean("  \n "));
        assertNull(ChatReply.clean(null));
        String sentences = "Das ist ein ziemlich langer erster Satz über Eisen und Höhlen und Zombies. ".repeat(4);
        String cut = ChatReply.clean(sentences);
        assertTrue(cut.length() <= ChatReply.MAX_REPLY && cut.endsWith("."), cut);
        String noBreak = ChatReply.clean("x".repeat(500));
        assertEquals(ChatReply.MAX_REPLY + 1, noBreak.length());
        assertTrue(noBreak.endsWith("…"));
    }

    @Test void historyKeepsOnlyTheLastMessages() {
        var h = new ChatHistory();
        for (int i = 1; i <= 5; i++) h.add("frage " + i, "antwort " + i);
        var m = h.messages();
        assertEquals(ChatHistory.MAX_MESSAGES, m.size());
        assertEquals("frage 3", m.getFirst().content());
        assertEquals("antwort 5", m.getLast().content());
        h.add("x".repeat(1000), null);
        assertEquals(ChatHistory.MAX_CHARS + 1, h.messages().get(4).content().length());
        assertEquals("", h.messages().get(5).content());
        h.clear();
        assertTrue(h.messages().isEmpty());
    }
}
