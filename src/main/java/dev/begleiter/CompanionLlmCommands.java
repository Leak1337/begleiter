package dev.begleiter;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import dev.begleiter.chat.ChatText;
import dev.begleiter.llm.IntentTools;
import dev.begleiter.llm.Llm;
import dev.begleiter.llm.LlmConfig;
import dev.begleiter.llm.LlmTrace;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /begleiter llm} bzw. {@code /companion llm}:
 *   status                     Zustand, Budget, letzte Aufrufe (für alle)
 *   an|on / aus|off            Schalter in config/begleiter-llm.json setzen und neu laden (Operator)
 *   neuladen|reload            Config-Datei neu lesen (Operator)
 *   test <satz>                Satz übersetzen lassen, ohne ihn auszuführen (Operator)
 */
final class CompanionLlmCommands {
    private CompanionLlmCommands() {}

    /** Für die Konsole: ein fester Budget-Besitzer statt eines Spielers. */
    private static final Object CONSOLE = new Object();
    private static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault());

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("llm")
                .executes(CompanionLlmCommands::status)
                .then(Commands.literal("status").executes(CompanionLlmCommands::status))
                .then(admin("an").executes(c -> toggle(c, true)))
                .then(admin("on").executes(c -> toggle(c, true)))
                .then(admin("aus").executes(c -> toggle(c, false)))
                .then(admin("off").executes(c -> toggle(c, false)))
                .then(admin("neuladen").executes(CompanionLlmCommands::reload))
                .then(admin("reload").executes(CompanionLlmCommands::reload))
                .then(admin("test").then(Commands.argument("satz", StringArgumentType.greedyString())
                        .executes(CompanionLlmCommands::test)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
        return Commands.literal(name).requires(source -> source.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER));
    }

    static List<String> statusLines() {
        return statusTexts().stream().map(Component::getString).toList();
    }

    static List<Component> statusTexts() {
        LlmConfig config = Llm.config();
        var budget = Llm.budget().snapshot();
        List<Component> lines = new ArrayList<>();
        lines.add(CompanionTexts.tr("llm.config.state", CompanionTexts.tr(config.active() ? "llm.config.active"
                : config.enabled() ? "llm.config.no_key" : "llm.config.off"), CompanionTexts.literal(config.baseUrl())));
        lines.add(CompanionTexts.tr("llm.config.models", CompanionTexts.literal(config.intentModel()), CompanionTexts.literal(config.chatModel()),
                CompanionTexts.literal(config.plannerModel()), CompanionTexts.literal(config.planner().name().toLowerCase(java.util.Locale.ROOT))));
        lines.add(CompanionTexts.tr("llm.config.budget", budget.lastMinute(), config.maxRequestsPerMinute(), budget.today(),
                config.maxRequestsPerDay(), budget.inFlight(), budget.backoffRemainingMs() > 0
                        ? CompanionTexts.tr("llm.config.backoff", (budget.backoffRemainingMs() + 999) / 1000) : Component.empty()));
        var recent = LlmTrace.GLOBAL.recent(5);
        if (recent.isEmpty()) lines.add(CompanionTexts.tr("llm.config.no_calls"));
        else {
            lines.add(CompanionTexts.tr("llm.config.recent"));
            for (var entry : recent) lines.add(Component.literal("  ").append(traceText(entry)));
        }
        return lines;
    }

    static Component traceText(LlmTrace.Entry entry) {
        String model = entry.model() == null ? "?" : entry.model().substring(entry.model().lastIndexOf('/') + 1);
        return CompanionTexts.tr("llm.trace", TIME.format(java.time.Instant.ofEpochMilli(entry.timeMs())),
                CompanionLlmChat.statusText(entry.status()), entry.latencyMs(), entry.promptTokens() + entry.completionTokens() > 0
                        ? CompanionLlmChat.tokenText(entry.promptTokens(), entry.completionTokens()) : Component.empty(),
                CompanionTexts.literal(model), CompanionTexts.literal(entry.input()), CompanionLlmChat.traceOutcomeComponent(entry));
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        statusTexts().forEach(line -> send(c.getSource(), line));
        return 1;
    }

    private static int toggle(CommandContext<CommandSourceStack> c, boolean on) {
        try {
            LlmConfig.writeEnabled(LlmConfig.defaultFile(), on);
        } catch (java.io.IOException ex) {
            send(c.getSource(), CompanionTexts.tr("llm.config.write_error", CompanionTexts.literal(LlmConfig.FILE_NAME), CompanionTexts.literal(ex.getMessage())));
            return 0;
        }
        LlmConfig config = Llm.reload();
        send(c.getSource(), statusTexts().getFirst());
        if (on && !config.active()) send(c.getSource(), CompanionTexts.tr("llm.config.key_hint", CompanionTexts.literal(LlmConfig.FILE_NAME)));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        Llm.reload();
        send(c.getSource(), statusTexts().getFirst());
        return 1;
    }

    private static int test(CommandContext<CommandSourceStack> c) {
        CommandSourceStack source = c.getSource();
        LlmConfig config = Llm.config();
        if (!config.active()) {
            send(source, statusTexts().getFirst());
            return 0;
        }
        // Dieselbe Normalisierung wie im Chat, damit der Test genau das sendet, was ein Chatbefehl senden würde.
        String text = ChatText.normalize(StringArgumentType.getString(c, "satz"));
        ServerPlayer player = source.getPlayer();
        String language = player == null ? "de_de" : player.clientInformation().language();
        Object owner = player == null ? CONSOLE : player;
        var server = source.getServer();
        send(source, CompanionTexts.tr("llm.config.asking", CompanionTexts.literal(config.intentModel())));
        Llm.client().chat(IntentTools.request(owner, config.intentModel(), text, language)).thenAccept(result -> server.execute(() -> {
            var translation = IntentTools.translate(result, language, CompanionLlmChat.REGISTRY_IDS);
            String outcome = CompanionLlmChat.describe(translation);
            send(source, traceText(LlmTrace.GLOBAL.record(config.intentModel(), result, text, "Test: " + outcome,
                    CompanionLlmChat.describeOutcome(translation).asTest())));
        }));
        return 1;
    }

    private static void send(CommandSourceStack source, Component line) {
        source.sendSystemMessage(CompanionTexts.prefixed(line));
    }
}
