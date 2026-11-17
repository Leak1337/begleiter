package dev.begleiter;

import java.util.UUID;

import dev.begleiter.llm.ChatReply;
import dev.begleiter.llm.IntentTools;
import dev.begleiter.llm.Llm;
import dev.begleiter.llm.LlmResult;
import dev.begleiter.llm.LlmTrace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/**
 * LLM-Rückfall für Chatbefehle, die weder Lexikon noch Registry-Suche verstehen. Die Anfrage läuft im Hintergrund;
 * das Ergebnis wird auf dem Server-Thread erneut geprüft und über {@link CompanionChatActions} ausgeführt.
 */
final class CompanionLlmChat {
    private CompanionLlmChat() {}

    /** Registry-Prüfung für Item- und Mob-IDs aus der Modellantwort. */
    static final IntentTools.Ids REGISTRY_IDS = new IntentTools.Ids() {
        @Override public boolean item(String path) {
            var item = ItemIds.resolve(path);
            return item != null && item != Items.AIR;
        }

        @Override public boolean mob(String path) {
            return "any".equals(path) || BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.fromNamespaceAndPath("minecraft", path));
        }
    };

    /** @return true, wenn eine Anfrage gestartet wurde; die Antwort an den Spieler folgt dann später */
    static boolean start(ServerPlayer player, CompanionEntity c, String text, String language) {
        var config = Llm.config();
        if (!config.active()) return false;
        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        int serial = c.chatSerial;
        // Vor dem Senden: ein sofort fertiges Ergebnis (z.B. Budget erschöpft) läuft auf diesem Thread direkt weiter.
        c.tell(CompanionTexts.tr("reply.llm_thinking"));
        Llm.client().chat(IntentTools.request(c, config.intentModel(), text, language))
                .thenAccept(result -> server.execute(() -> finish(server, playerId, c, serial, text, language, result)));
        return true;
    }

    static void finish(MinecraftServer server, UUID playerId, CompanionEntity c, int serial, String text, String language,
            LlmResult result) {
        var translation = IntentTools.translate(result, language, REGISTRY_IDS);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        var dropped = dropReason(c, serial, player);
        LlmTrace.GLOBAL.record(Llm.config().intentModel(), result, text,
                dropped == null ? describe(translation) : "dropped (" + dropDescription(dropped) + ")",
                dropped == null ? describeOutcome(translation) : new LlmTrace.Outcome(dropped, "", java.util.List.of(), false));
        if (dropped != null) return;
        // Landet im Bot-Log und damit im F8-Menü und im Dump.
        Component log = logText("llm.log", result, text, describeComponent(translation));
        Component reply;
        switch (translation) {
            case IntentTools.Translation.Command command -> {
                try {
                    reply = CompanionChatActions.applyLlm(player, c, text, command);
                } catch (RuntimeException ex) {
                    c.logError(CompanionTexts.tr("llm.execution_error", log, CompanionTexts.literal(ex.toString())));
                    c.tell(CompanionTexts.tr("reply.error"));
                    return;
                }
                c.logInfo(CompanionTexts.tr("llm.executed", log, reply == null ? CompanionTexts.tr("llm.not_executable") : reply));
            }
            case IntentTools.Translation.Question question -> {
                reply = CompanionTexts.literal(question.text());
                c.llmHistory.add(text, question.text());
                c.logInfo(log);
            }
            case IntentTools.Translation.Chat chat -> {
                // Antwort folgt aus einer zweiten Anfrage mit Kontext; bis dahin nichts sagen.
                c.logInfo(log);
                startReply(server, playerId, c, serial, text, language, player);
                return;
            }
            case IntentTools.Translation.Rejected rejected -> {
                reply = null;
                c.logInfo(log);
            }
        }
        c.tell(reply != null ? reply : CompanionTexts.tr("reply.unknown"));
    }

    /** Freie Antwort: Zustand jetzt auf dem Server-Thread lesen, Antwort im Hintergrund holen. */
    static void startReply(MinecraftServer server, UUID playerId, CompanionEntity c, int serial, String text, String language,
            ServerPlayer player) {
        var config = Llm.config();
        String context = CompanionLlmContext.capture(c, player).render();
        String name = c.getCustomName() == null ? null : c.getCustomName().getString();
        Llm.client().chat(ChatReply.request(c, config.chatModel(), name, language, context, c.llmHistory.messages(), text))
                .thenAccept(result -> server.execute(() -> finishReply(server, playerId, c, serial, text, result)));
    }

    static void finishReply(MinecraftServer server, UUID playerId, CompanionEntity c, int serial, String text, LlmResult result) {
        String reply = result.ok() ? ChatReply.clean(result.content()) : null;
        var dropped = dropReason(c, serial, server.getPlayerList().getPlayer(playerId));
        String outcome = dropped != null ? "dropped (" + dropDescription(dropped) + ")"
                : reply != null ? "reply: " + reply : "no reply: " + result.status() + (result.error() == null ? "" : " " + result.error());
        LlmTrace.GLOBAL.record(Llm.config().chatModel(), result, text, outcome, new LlmTrace.Outcome(
                dropped != null ? dropped : reply != null ? LlmTrace.Outcome.Kind.REPLY : LlmTrace.Outcome.Kind.NO_REPLY,
                dropped != null ? "" : reply != null ? reply : result.error(), java.util.List.of(), false));
        if (dropped != null) return;
        Component outcomeText = reply != null ? CompanionTexts.tr("llm.reply", CompanionTexts.literal(reply))
                : CompanionTexts.tr("llm.no_reply", statusText(result.status()),
                        result.error() == null ? Component.empty() : Component.literal(" ").append(CompanionTexts.literal(result.error())));
        c.logInfo(logText("llm.chat_log", result, text, outcomeText));
        if (reply == null) {
            c.tell(CompanionTexts.tr("reply.unknown"));
            return;
        }
        c.llmHistory.add(text, reply);
        c.tell(CompanionTexts.literal(reply));
    }

    /** Begleiter weg, inzwischen ein neuer Befehl oder Spieler weg: eine späte Antwort darf nichts mehr auslösen. */
    private static LlmTrace.Outcome.Kind dropReason(CompanionEntity c, int serial, ServerPlayer player) {
        return c.isRemoved() || !c.isAlive() ? LlmTrace.Outcome.Kind.DROP_REMOVED : c.chatSerial != serial ? LlmTrace.Outcome.Kind.DROP_NEWER
                : player == null || !c.isOwner(player) ? LlmTrace.Outcome.Kind.DROP_OWNER : null;
    }

    private static String dropDescription(LlmTrace.Outcome.Kind reason) {
        return switch (reason) {
            case DROP_REMOVED -> "companion removed";
            case DROP_NEWER -> "newer command";
            case DROP_OWNER -> "player gone";
            default -> throw new IllegalArgumentException("Not a dropped response: " + reason);
        };
    }

    static Component statusText(LlmResult.Status status) {
        return CompanionTexts.tr("llm.status." + status.name().toLowerCase(java.util.Locale.ROOT));
    }

    static Component tokenText(int prompt, int completion) {
        return CompanionTexts.tr("llm.tokens", prompt, completion);
    }

    private static Component logText(String key, LlmResult result, String input, Component outcome) {
        return CompanionTexts.tr(key, statusText(result.status()), result.latencyMs(),
                result.ok() ? tokenText(result.promptTokens(), result.completionTokens()) : Component.empty(),
                CompanionTexts.literal(input), outcome);
    }

    static Component describeComponent(IntentTools.Translation translation) {
        return switch (translation) {
            case IntentTools.Translation.Command command -> CompanionTexts.literal(command.summary());
            case IntentTools.Translation.Question question -> CompanionTexts.tr("llm.question", CompanionTexts.literal(question.text()));
            case IntentTools.Translation.Chat chat -> CompanionTexts.tr("llm.chat");
            case IntentTools.Translation.Rejected rejected -> CompanionTexts.tr("llm.rejected", problemsComponent(rejected.problems()));
        };
    }

    private static Component problemsComponent(java.util.List<IntentTools.Problem> problems) {
        return CompanionTexts.join(problems.stream().map(problem -> {
            if (problem.code() == IntentTools.Problem.Code.LITERAL)
                return problem.value() == null ? Component.empty() : CompanionTexts.literal(problem.value());
            return CompanionTexts.tr("llm.validation." + problem.code().name().toLowerCase(java.util.Locale.ROOT),
                    CompanionTexts.literal(problem.field()), CompanionTexts.literal(problem.value()));
        }).toList(), "; ");
    }

    static LlmTrace.Outcome describeOutcome(IntentTools.Translation translation) {
        return switch (translation) {
            case IntentTools.Translation.Command command -> new LlmTrace.Outcome(LlmTrace.Outcome.Kind.COMMAND, command.summary(), java.util.List.of(), false);
            case IntentTools.Translation.Question question -> new LlmTrace.Outcome(LlmTrace.Outcome.Kind.QUESTION, question.text(), java.util.List.of(), false);
            case IntentTools.Translation.Chat chat -> new LlmTrace.Outcome(LlmTrace.Outcome.Kind.CHAT, "", java.util.List.of(), false);
            case IntentTools.Translation.Rejected rejected -> new LlmTrace.Outcome(LlmTrace.Outcome.Kind.REJECTED, "", rejected.problems(), false);
        };
    }

    static Component traceOutcomeComponent(LlmTrace.Entry entry) {
        var display = entry.display();
        if (display == null) return CompanionTexts.literal(entry.outcome());
        Component detail = CompanionTexts.literal(display.detail());
        Component text = switch (display.kind()) {
            case LITERAL, COMMAND -> detail;
            case QUESTION -> CompanionTexts.tr("llm.question", detail);
            case CHAT -> CompanionTexts.tr("llm.chat");
            case REJECTED -> CompanionTexts.tr("llm.rejected", problemsComponent(display.problems()));
            case REPLY -> CompanionTexts.tr("llm.reply", detail);
            case NO_REPLY -> CompanionTexts.tr("llm.no_reply", statusText(entry.status()), display.detail().isEmpty()
                    ? Component.empty() : Component.literal(" ").append(detail));
            case DROP_REMOVED -> CompanionTexts.tr("llm.dropped", CompanionTexts.tr("llm.drop.removed"));
            case DROP_NEWER -> CompanionTexts.tr("llm.dropped", CompanionTexts.tr("llm.drop.newer"));
            case DROP_OWNER -> CompanionTexts.tr("llm.dropped", CompanionTexts.tr("llm.drop.owner"));
        };
        return display.test() ? CompanionTexts.tr("llm.test", text) : text;
    }

    /** Kurzform einer Übersetzung für Log, Trace und {@code /begleiter llm test}. */
    static String describe(IntentTools.Translation translation) {
        return switch (translation) {
            case IntentTools.Translation.Command command -> command.summary();
            case IntentTools.Translation.Question question -> "question: " + question.text();
            case IntentTools.Translation.Chat chat -> "chat";
            case IntentTools.Translation.Rejected rejected -> "rejected: " + rejected.reason();
        };
    }
}
