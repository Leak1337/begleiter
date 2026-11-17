package dev.begleiter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.llm.IntentTools;
import dev.begleiter.llm.Llm;
import dev.begleiter.llm.LlmConfig;
import dev.begleiter.llm.LlmRequest;
import dev.begleiter.llm.LlmResult;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** LLM-Rückfall im Chat mit festem Stub statt Netzwerk. */
public class LlmChatGameTests {
    private static final LlmConfig ACTIVE = LlmConfig.parse("{\"enabled\": true, \"apiKey\": \"test\"}");

    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            h.setBlock(x, 0, z, Blocks.STONE);
            h.setBlock(x, 1, z, Blocks.DIRT);
        }
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 2, 2));
        c.setNoAi(true);
        c.stay();
        c.setCustomName(Component.literal("Testbot"));
        return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID());
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        return p;
    }

    private static LlmResult command(String arguments) {
        return LlmResult.ok(null, List.of(new LlmResult.ToolCall("c1", IntentTools.COMMAND_TOOL, arguments)), 5, 0, 0);
    }

    private static IntentTools.Translation.Command translated(GameTestHelper h, String arguments) {
        var t = IntentTools.translate(command(arguments), "de_de", CompanionLlmChat.REGISTRY_IDS);
        if (!(t instanceof IntentTools.Translation.Command c)) throw new IllegalStateException("Nicht übersetzt: " + t);
        return c;
    }

    @GameTest
    public void unknownSentenceGoesToLlmAndRunsTheOrderLater(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        var pending = new CompletableFuture<LlmResult>();
        LlmRequest[] sent = new LlmRequest[1];
        String sentence = "ich haette gern ein bisschen von dem grauen metall";
        h.assertTrue(dev.begleiter.chat.ChatParser.parse(sentence, "de_de") == null, "Testsatz versteht schon das Lexikon");
        // Der globale Stub gilt nur während dieses synchronen Aufrufs; andere Tests laufen auf demselben Thread.
        Llm.useForTests(ACTIVE, request -> { sent[0] = request; return pending; });
        try {
            CompanionBrain.onChat(p, "@ " + sentence);
        } finally {
            Llm.useForTests(null, null);
        }
        h.assertTrue(sent[0] != null, "LLM nicht gefragt");
        h.assertTrue(sent[0].owner() == c, "Budget-Besitzer ist nicht der Begleiter");
        h.assertTrue(sent[0].messages().get(1).content().contains("grauen metall"), "Satz nicht gesendet");
        h.assertTrue(!c.hasTasks(), "Auftrag vor der Antwort");
        h.runAfterDelay(2, () -> pending.complete(command("{\"intent\":\"GATHER\",\"resource\":\"eisen\",\"count\":8,\"to_owner\":true}")));
        h.succeedWhen(() -> h.assertTrue(Task.gather("eisen", 8).equals(c.currentTask()), "Kein Eisenauftrag: " + c.currentTask()));
    }

    @GameTest
    public void knownSentencesNeverAskTheLlm(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        boolean[] asked = {false};
        Llm.useForTests(ACTIVE, request -> { asked[0] = true; return new CompletableFuture<>(); });
        try {
            CompanionBrain.onChat(p, "@ bring mir 5 holz");
        } finally {
            Llm.useForTests(null, null);
        }
        h.assertTrue(!asked[0], "Lexikon-Satz ging ans LLM");
        h.assertTrue(c.hasTasks(), "Lexikon-Auftrag fehlt");
        h.succeed();
    }

    @GameTest
    public void staleOrForeignAnswersAreDropped(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        var server = h.getLevel().getServer();
        int serial = c.chatSerial;
        c.chatSerial++; // Spieler hat inzwischen etwas Neues gesagt
        CompanionLlmChat.finish(server, p.getUUID(), c, serial, "hol eisen", "de_de",
                command("{\"intent\":\"GATHER\",\"resource\":\"eisen\"}"));
        h.assertTrue(!c.hasTasks(), "Veraltete Antwort ausgeführt");
        var stranger = h.makeMockServerPlayerInLevel();
        CompanionLlmChat.finish(server, stranger.getUUID(), c, c.chatSerial, "hol eisen", "de_de",
                command("{\"intent\":\"GATHER\",\"resource\":\"eisen\"}"));
        h.assertTrue(!c.hasTasks(), "Antwort für fremden Spieler ausgeführt");
        c.discard();
        CompanionLlmChat.finish(server, p.getUUID(), c, c.chatSerial, "hol eisen", "de_de",
                command("{\"intent\":\"GATHER\",\"resource\":\"eisen\"}"));
        h.assertTrue(!c.hasTasks(), "Antwort für entfernten Begleiter ausgeführt");
        h.succeed();
    }

    @GameTest
    public void questionsAndRejectionsChangeNothing(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        var server = h.getLevel().getServer();
        CompanionLlmChat.finish(server, p.getUUID(), c, c.chatSerial, "geh dahin", "de_de", LlmResult.ok(null,
                List.of(new LlmResult.ToolCall("q", IntentTools.QUESTION_TOOL, "{\"frage\":\"Wohin genau?\"}")), 5, 0, 0));
        CompanionLlmChat.finish(server, p.getUUID(), c, c.chatSerial, "hol laser", "de_de",
                command("{\"intent\":\"OBTAIN\",\"item\":\"laser_gun\"}"));
        CompanionLlmChat.finish(server, p.getUUID(), c, c.chatSerial, "hol eisen", "de_de",
                LlmResult.failure(LlmResult.Status.TIMEOUT, "zu langsam", 8000));
        h.assertTrue(!c.hasTasks() && c.isStaying(), "Rückfrage oder Ablehnung hat den Begleiter verändert");
        h.succeed();
    }

    @GameTest
    public void llmParametersWinOverWordsInTheSentence(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        // Der Satz nennt eine Fackel; der Registry-Rückfall würde sie holen. Das Modell hat FOLLOW gewählt.
        var follow = CompanionChatActions.applyLlm(p, c, "hol eine fackel und komm", translated(h, "{\"intent\":\"FOLLOW\"}"));
        h.assertTrue(follow != null && !c.hasTasks() && !c.isStaying(), "FOLLOW wurde zum Item-Auftrag");
        // Wegpunkte kommen aus dem Parameter, nicht aus dem Satz.
        CompanionChatActions.applyLlm(p, c, "merk dir das hier mal", translated(h, "{\"intent\":\"WAYPOINT_SAVE\",\"waypoint\":\"Eisenmine\"}"));
        h.assertTrue(c.memory.waypoint("eisenmine", h.getLevel()) != null, "Wegpunkt nicht gespeichert");
        var go = CompanionChatActions.applyLlm(p, c, "bring mich da wieder hin", translated(h, "{\"intent\":\"WAYPOINT_GO\",\"waypoint\":\"eisenmine\"}"));
        h.assertTrue(go != null && c.hasTasks(), "Weg zum Wegpunkt fehlt");
        h.succeed();
    }

    @GameTest
    public void traceRecordsResultsAndDroppedAnswers(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        var server = h.getLevel().getServer();
        CompanionLlmChat.finish(server, p.getUUID(), c, c.chatSerial, "spur eins", "de_de",
                command("{\"intent\":\"FOLLOW\"}"));
        var done = dev.begleiter.llm.LlmTrace.GLOBAL.recent(20).stream().filter(e -> e.input().equals("spur eins")).findFirst();
        h.assertTrue(done.isPresent() && done.get().outcome().equals("FOLLOW"), "Ausgeführter Aufruf fehlt im Trace: " + done);
        CompanionLlmChat.finish(server, p.getUUID(), c, c.chatSerial - 1, "spur zwei", "de_de",
                command("{\"intent\":\"FOLLOW\"}"));
        var dropped = dev.begleiter.llm.LlmTrace.GLOBAL.recent(20).stream().filter(e -> e.input().equals("spur zwei")).findFirst();
        h.assertTrue(dropped.isPresent() && dropped.get().outcome().equals("dropped (newer command)"), "Verworfener Aufruf: " + dropped);
        h.succeed();
    }

    @GameTest
    public void llmCommandIsRegisteredAndStatusShowsState(GameTestHelper h) {
        var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
        for (String root : new String[]{"begleiter", "companion"}) {
            var llm = dispatcher.getRoot().getChild(root).getChild("llm");
            h.assertTrue(llm != null, "/" + root + " llm fehlt");
            for (String sub : new String[]{"status", "an", "on", "aus", "off", "neuladen", "reload", "test"})
                h.assertTrue(llm.getChild(sub) != null, "/" + root + " llm " + sub + " fehlt");
        }
        Llm.useForTests(ACTIVE, request -> new CompletableFuture<>());
        try {
            var lines = CompanionLlmCommands.statusTexts();
            ServerTextGameTests.assertTranslatedContains(h, lines.getFirst(), "LLM: aktiv", "LLM: active");
            ServerTextGameTests.assertTranslatedContains(h, lines.get(2), "Budget: 0/6 pro Minute", "Budget: 0/6 per minute");
        } finally {
            Llm.useForTests(null, null);
        }
        Llm.useForTests(LlmConfig.DEFAULTS, request -> new CompletableFuture<>());
        try {
            ServerTextGameTests.assertTranslatedContains(h, CompanionLlmCommands.statusTexts().getFirst(), "LLM: aus", "LLM: off");
        } finally {
            Llm.useForTests(null, null);
        }
        h.succeed();
    }

    @GameTest
    public void contextSnapshotReadsTheCompanionWithoutPlayerCoordinates(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        p.snapTo(c.getX() + 3, c.getY(), c.getZ(), 0, 0);
        c.getInventory().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH, 5));
        c.getInventory().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 20));
        c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
        c.memory.setWaypoint("eisenmine", h.getLevel(), c.blockPosition());
        var zombie = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, new BlockPos(5, 2, 5));
        zombie.setNoAi(true);
        c.addTask(Task.gather("eisen", 8));

        String text = CompanionLlmContext.capture(c, p).render();
        h.assertTrue(text.contains("inventory: cobblestone 20, torch 5"), text);
        h.assertTrue(text.contains("equipment: stone_pickaxe"), text);
        h.assertTrue(text.contains("hostile nearby: zombie 1"), text);
        h.assertTrue(text.contains("waypoints: eisenmine"), text);
        h.assertTrue(text.contains("tasks (1): GATHER looking for eisen, 8 left"), text);
        h.assertTrue(text.contains("player distance: 3"), text);
        h.assertTrue(!text.contains(String.valueOf(p.getBlockX())) || !text.contains(String.valueOf(p.getBlockZ())),
                "Spielerkoordinaten im Kontext: " + text);
        h.assertTrue(text.length() <= dev.begleiter.llm.ContextSnapshot.MAX_CHARS, "zu lang: " + text.length());
        h.assertTrue(!CompanionLlmContext.capture(c, null).render().contains("player distance"), "Entfernung ohne Besitzer");
        h.succeed();
    }

    @GameTest
    public void smallTalkGetsAFreeReplyWithContext(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        c.getInventory().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH, 5));
        var server = h.getLevel().getServer();
        var pending = new CompletableFuture<LlmResult>();
        LlmRequest[] sent = new LlmRequest[1];
        // finish() startet die zweite Anfrage synchron; nur dafür gilt der Stub.
        Llm.useForTests(ACTIVE, request -> { sent[0] = request; return pending; });
        try {
            CompanionLlmChat.finish(server, p.getUUID(), c, c.chatSerial, "hast du eigentlich licht dabei", "de_de",
                    LlmResult.ok(null, List.of(new LlmResult.ToolCall("p", IntentTools.CHAT_TOOL, "{}")), 5, 0, 0));
        } finally {
            Llm.useForTests(null, null);
        }
        h.assertTrue(sent[0] != null && sent[0].tools() == null, "Keine freie Antwort-Anfrage");
        String system = sent[0].messages().getFirst().content();
        h.assertTrue(system.contains("You are Testbot") && system.contains("inventory: torch 5"), system);
        h.assertTrue(sent[0].messages().getLast().content().equals("hast du eigentlich licht dabei"), "Spielersatz fehlt");
        h.assertTrue(!c.hasTasks(), "Plaudern hat einen Auftrag ausgelöst");
        pending.complete(LlmResult.ok("**Klar**, ich hab 5 Fackeln dabei!", List.of(), 5, 300, 20));
        h.succeedWhen(() -> {
            var history = c.llmHistory.messages();
            h.assertTrue(history.size() == 2 && history.get(1).content().equals("Klar, ich hab 5 Fackeln dabei!"),
                    "Antwort nicht im Verlauf: " + history);
            h.assertTrue(!c.hasTasks(), "Antwort hat einen Auftrag ausgelöst");
        });
    }

    @GameTest
    public void lateFreeRepliesAreDropped(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        var server = h.getLevel().getServer();
        int serial = c.chatSerial;
        c.chatSerial++;
        CompanionLlmChat.finishReply(server, p.getUUID(), c, serial, "wie gehts", LlmResult.ok("Gut!", List.of(), 5, 0, 0));
        h.assertTrue(c.llmHistory.messages().isEmpty(), "Veraltete Antwort gespeichert");
        CompanionLlmChat.finishReply(server, p.getUUID(), c, c.chatSerial, "wie gehts",
                LlmResult.failure(LlmResult.Status.RATE_LIMITED, "429", 5));
        h.assertTrue(c.llmHistory.messages().isEmpty(), "Fehler im Verlauf gespeichert");
        h.succeed();
    }

    @GameTest
    public void commandLogRetainsTranslatedReplyAndActualExecution(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c); String input = "C16 follow %s Ä";
        CompanionLlmChat.finish(h.getLevel().getServer(), p.getUUID(), c, c.chatSerial, input, "de_de",
                command("{\"intent\":\"FOLLOW\"}"));
        h.assertTrue(!c.isStaying() && !c.hasTasks() && c.usedSlots() == 0, "Translated log changed FOLLOW execution");
        var log = c.getLog(0, 1, false).getFirst().textComponent();
        HandlerTextGameTests.translated(h, log, "Ich folge dir.", "I am following you.");
        HandlerTextGameTests.translated(h, log, input, input);
        var trace = dev.begleiter.llm.LlmTrace.GLOBAL.recent(20).stream().filter(e -> e.input().equals(input)).findFirst();
        h.assertTrue(trace.isPresent() && trace.get().outcome().equals("FOLLOW"), "Technical trace outcome changed"); h.succeed();
    }

    @GameTest
    public void freeReplyLogsPreserveLiteralHistoryAndDropLateAnswers(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c); var server = h.getLevel().getServer();
        String input = "C16 chat %s Ä", answer = "Hallo %s Ä"; int serial = c.chatSerial;
        CompanionLlmChat.finishReply(server, p.getUUID(), c, serial, input, LlmResult.ok(answer, List.of(), 17, 3, 4));
        h.assertTrue(c.llmHistory.messages().size() == 2 && c.llmHistory.messages().get(1).content().equals(answer)
                && !c.hasTasks() && c.isStaying(), "Translated reply changed history or orders");
        var log = c.getLog(0, 1, false).getFirst().textComponent();
        HandlerTextGameTests.translated(h, log, "Antwort: " + answer, "Reply: " + answer);
        HandlerTextGameTests.translated(h, log, "3+4 tok", "3+4 tok");
        HandlerTextGameTests.translated(h, CompanionLlmCommands.traceText(dev.begleiter.llm.LlmTrace.GLOBAL.recent(1).getFirst()),
                "Antwort: " + answer, "Reply: " + answer);
        int logSize = c.getLog(0, 100, false).size(); c.chatSerial++;
        CompanionLlmChat.finishReply(server, p.getUUID(), c, serial, input, LlmResult.ok("Late", List.of(), 1, 0, 0));
        h.assertTrue(c.llmHistory.messages().size() == 2 && c.getLog(0, 100, false).size() == logSize, "Late answer added history/log output");
        var dropped = dev.begleiter.llm.LlmTrace.GLOBAL.recent(1).getFirst();
        h.assertTrue(dropped.display().kind() == dev.begleiter.llm.LlmTrace.Outcome.Kind.DROP_NEWER
                && dropped.display().detail().isEmpty(), "Late reply content retained in display metadata");
        HandlerTextGameTests.translated(h, CompanionLlmCommands.traceText(dropped), "verworfen (neuerer Befehl)", "discarded (newer command)");
        CompanionLlmChat.finishReply(server, p.getUUID(), c, c.chatSerial, input,
                LlmResult.failure(LlmResult.Status.TIMEOUT, "provider %s Ä", 8000));
        h.assertValueEqual(c.llmHistory.messages().size(), 2, "Failed answer entered history");
        var failure = c.getLog(0, 1, false).getFirst().textComponent();
        HandlerTextGameTests.translated(h, failure, "keine Antwort: Zeitlimit", "no reply: timeout");
        HandlerTextGameTests.translated(h, failure, "provider %s Ä", "provider %s Ä"); h.succeed();
    }

    @GameTest
    public void validationDetailsReachLogAndTraceWithoutChangingRejectedOrders(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c); var server = h.getLevel().getServer();
        record Case(LlmResult result, String de, String en) {}
        var cases = List.of(
                new Case(command("[]"), "kein JSON-Objekt", "not a JSON object"),
                new Case(command("{\"intent\":\"LASER_%s\"}"), "unbekannter Intent", "unknown intent"),
                new Case(command("{\"intent\":\"GATHER\",\"resource\":\"laser\",\"count\":\"bad %s Ä\"}"), "count ungültig", "count invalid"),
                new Case(command("{\"intent\":\"OBTAIN\",\"item\":\"laser_%s\"}"), "item unbekannt", "item unknown"),
                new Case(command("{\"intent\":\"GATHER\"}"), "ohne resource", "without resource"),
                new Case(command("{\"intent\":\"WHERE_IS\"}"), "ohne resource oder item", "without resource or item"),
                new Case(LlmResult.ok("No tool", List.of(), 5, 0, 0), "kein passender Tool-Call", "no matching tool call"),
                new Case(LlmResult.ok(null, List.of(new LlmResult.ToolCall("q", IntentTools.QUESTION_TOOL, "{}")), 5, 0, 0), "leere Rückfrage", "empty clarification"),
                new Case(LlmResult.failure(LlmResult.Status.TIMEOUT, "Provider %s Ä", 9), "TIMEOUT: Provider %s Ä", "TIMEOUT: Provider %s Ä"));
        for (var sample : cases) {
            CompanionLlmChat.finish(server, p.getUUID(), c, c.chatSerial, "C18 input %s Ä", "de_de", sample.result());
            h.assertTrue(c.isStaying() && !c.hasTasks() && c.llmHistory.messages().isEmpty() && c.usedSlots() == 0,
                    "Translated rejection changed orders/history/inventory");
            HandlerTextGameTests.translated(h, c.getLog(0, 1, false).getFirst().textComponent(), sample.de(), sample.en());
            var entry = dev.begleiter.llm.LlmTrace.GLOBAL.recent(1).getFirst();
            h.assertTrue(entry.display().kind() == dev.begleiter.llm.LlmTrace.Outcome.Kind.REJECTED, "Rejection lost structured trace metadata");
            HandlerTextGameTests.translated(h, CompanionLlmCommands.traceText(entry), sample.de(), sample.en());
            var testEntry = new dev.begleiter.llm.LlmTrace.Entry(entry.timeMs(), entry.model(), entry.status(), entry.latencyMs(),
                    entry.promptTokens(), entry.completionTokens(), entry.input(), entry.outcome(), entry.display().asTest());
            HandlerTextGameTests.translated(h, CompanionLlmCommands.traceText(testEntry), "Test:", "Test:");
        }
        h.succeed();
    }

    @GameTest
    public void statusDisplayKeepsBudgetAndTechnicalTraceWithoutRequests(GameTestHelper h) throws Exception {
        Llm.useForTests(ACTIVE, request -> { throw new AssertionError("Status display sent an LLM request"); });
        try {
            var before = Llm.budget().snapshot(); var lines = CompanionLlmCommands.statusTexts();
            HandlerTextGameTests.translated(h, lines.getFirst(), "LLM: aktiv", "LLM: active");
            HandlerTextGameTests.translated(h, lines.get(1), "Modelle: Befehle", "Models: commands");
            HandlerTextGameTests.translated(h, lines.get(2), "pro Minute", "per minute");
            h.assertTrue(before.equals(Llm.budget().snapshot()), "Status display consumed budget");
            var localTrace = new dev.begleiter.llm.LlmTrace(2, () -> 0L);
            var entry = localTrace.record("vendor/model_%s", LlmResult.failure(LlmResult.Status.TIMEOUT, "detail", 9), "Input %s Ä", "Technical %s Ä");
            HandlerTextGameTests.translated(h, CompanionLlmCommands.traceText(entry), "Zeitlimit", "timeout");
            HandlerTextGameTests.translated(h, CompanionLlmCommands.traceText(entry), "Technical %s Ä", "Technical %s Ä");
            h.assertTrue(dev.begleiter.llm.LlmTrace.format(entry).contains("TIMEOUT 9 ms model_%s"), "Technical trace formatter changed");
        } finally { Llm.useForTests(null, null); }
        h.succeed();
    }

    @GameTest
    public void registryIdsCheckRealItemsAndMobs(GameTestHelper h) {
        var ids = CompanionLlmChat.REGISTRY_IDS;
        h.assertTrue(ids.item("torch") && ids.item("diamond_sword"), "Bekanntes Item abgelehnt");
        h.assertTrue(!ids.item("laser_gun"), "Erfundenes Item angenommen");
        h.assertTrue(ids.mob("mooshroom") && ids.mob("sheep") && ids.mob("any"), "Bekannter Mob abgelehnt");
        h.assertTrue(!ids.mob("dragon"), "Erfundener Mob angenommen");
        h.succeed();
    }
}
