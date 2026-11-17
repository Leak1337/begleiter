package dev.begleiter;

import java.util.ArrayList;
import java.util.List;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.chat.ChatCount;
import dev.begleiter.chat.ChatIntent;
import dev.begleiter.chat.ChatParams;
import dev.begleiter.chat.ChatParser;
import dev.begleiter.chat.Sentence;
import dev.begleiter.llm.IntentTools;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Applies language-neutral parser results to the existing server actions. */
final class CompanionChatActions {
    private CompanionChatActions() {}

    static String interpret(ServerPlayer player, CompanionEntity c, String text, String language) {
        Component reply = interpretReply(player, c, text, language);
        return reply == null ? null : reply.getString();
    }

    /**
     * Herkunft eines Ergebnisses. Beim LLM gelten nur die geprüften Parameter: Wörter des Satzes dürfen dort
     * keine Items, Wegpunkte oder Bauarten nachliefern, die das Modell nicht genannt hat.
     */
    private record Source(boolean llm, boolean farm) {
        static final Source LEXICON = new Source(false, false);
    }

    static Component interpretReply(ServerPlayer player, CompanionEntity c, String text, String language) {
        Sentence sentence = Sentence.of(text);
        var parsed = ChatParser.parse(text, language);
        boolean control = parsed != null && parsed.intents().stream().anyMatch(intent -> java.util.Set.of(
                ChatIntent.CANCEL, ChatIntent.PAUSE, ChatIntent.RESUME, ChatIntent.FOLLOW, ChatIntent.STAY,
                ChatIntent.FREE_ROAM, ChatIntent.STATUS).contains(intent));
        if (!control) {
            Component continuation = c.requestDialogue.answer(player, c, text);
            if (continuation != null) return continuation;
        }
        if (parsed == null) {
            Component reply = registryObtain(c, sentence, null);
            if (reply != null) c.requestDialogue.clear();
            return reply == null ? c.requestDialogue.waitingReply(player, c) : reply;
        }
        long revision = c.requestDialogue.revision();
        Component reply = apply(player, c, parsed, sentence, Source.LEXICON);
        clearOldQuestion(c, parsed, reply, revision);
        return reply == null ? c.requestDialogue.waitingReply(player, c) : reply;
    }

    private static void clearOldQuestion(CompanionEntity c, ChatParser.Result parsed, Component reply, long revision) {
        if (reply != null && c.requestDialogue.revision() == revision && parsed.intents().stream().anyMatch(intent ->
                !java.util.Set.of(ChatIntent.STATUS, ChatIntent.EXPLAIN, ChatIntent.MEMORY_SUMMARY, ChatIntent.WHERE_IS,
                        ChatIntent.WAYPOINT_LIST).contains(intent))) c.requestDialogue.clear();
    }

    /** Führt eine vom LLM übersetzte Absicht über denselben Weg aus wie einen erkannten Satz. */
    static Component applyLlm(ServerPlayer player, CompanionEntity c, String text, IntentTools.Translation.Command command) {
        long revision = c.requestDialogue.revision();
        Component reply = apply(player, c, command.result(), Sentence.of(text), new Source(true, command.farm()));
        clearOldQuestion(c, command.result(), reply, revision);
        return reply;
    }

    private static Component apply(ServerPlayer player, CompanionEntity c, ChatParser.Result parsed, Sentence sentence, Source source) {
        ChatParams params = parsed.params();
        List<String> itemWords = source.llm() ? List.of() : sentence.tokens();
        Resources.Res resource = params.resource() == null ? null : Resources.byKey(params.resource());
        for (ChatIntent intent : parsed.intents()) {
            Component reply;
            switch (intent) {
                case WAYPOINT_SAVE, WAYPOINT_FORGET, WAYPOINT_LIST, WAYPOINT_GO -> {
                    // Wegpunkte sucht waypoint() im Satz; beim LLM ist der genannte Name der ganze "Satz".
                    reply = waypoint(player, c, intent, source.llm() ? Sentence.of(params.waypoint()) : sentence, params);
                    if (reply != null) return reply;
                }
                case PROJECT_PREVIEW -> { return CompanionProjektVorschau.planen(player).beschreibenText(); }
                case TERRAFORM -> {
                    return CompanionBrain.orderTerraformReply(player, c, CompanionTerraform.Mode.valueOf(params.terraform()),
                            sentence.tokens(), "cobblestone".equals(params.filler()) ? Blocks.COBBLESTONE : Blocks.DIRT);
                }
                case STANCE -> {
                    c.stance = CompanionCombat.Stance.valueOf(params.stance());
                    return CompanionTexts.tr("reply.stance", CompanionTexts.stance(c.stance));
                }
                case PROTECT -> {
                    if (c.stance == CompanionCombat.Stance.PASSIVE) c.stance = CompanionCombat.Stance.DEFENSIVE;
                    var held = c.holdOrdersComponent();
                    c.follow();
                    return CompanionTexts.tr("reply.protect", CompanionTexts.stance(c.stance)).append(held);
                }
                case MEMORY_CLEAR -> { c.memory.clear(); c.llmHistory.clear(); return CompanionTexts.tr("memory.cleared"); }
                case MEMORY_SUMMARY -> { return CompanionTexts.memorySummary(c); }
                case WHERE_IS -> {
                    reply = CompanionBrain.whereIsReply(player, c, sentence.tokens(), resource, item(params.item(), itemWords));
                    if (reply != null) return reply;
                }
                case CANCEL -> {
                    boolean hadBuild = c.build != null;
                    c.cancelOrders();
                    return CompanionTexts.tr(hadBuild ? "reply.cancel_build" : "reply.cancel");
                }
                case PAUSE -> { if (c.build != null) return CompanionBrain.pauseBuildReply(c, true); }
                case RESUME -> {
                    if (c.isPaused() || c.build != null && c.build.paused) return CompanionBrain.resumeReply(c);
                }
                case EXPLAIN -> { return CompanionBrain.explainNeedsReply(c); }
                case STATUS -> { return CompanionTexts.status(c); }
                case SET_HOME -> {
                    // "take care of the storage" enthält auch das allgemeine Lager-Verb "take".
                    // Ein ausdrücklicher Lagerverwalter-Auftrag darf die Lagerkiste nicht still neu setzen.
                    if (!parsed.intents().contains(ChatIntent.KEEPER)) return CompanionBrain.orderSetHomeReply(player, c);
                }
                case FARM -> { return CompanionBrain.orderFarmReply(player, c); }
                case BREED -> {
                    return CompanionBrain.orderBreedReply(c, "any".equals(params.mob()) ? null : params.mob(), params.countOr(0));
                }
                case REPAIR -> { return CompanionBrain.orderRepairReply(c); }
                case TRADE -> {
                    var what = sentence.after(parsed.language().equals("de") ? "gegen" : "for");
                    Item wanted = params.sell() ? null : item(params.tradeItem(), source.llm() ? List.of() : what.tokens());
                    if (wanted == Items.EMERALD) wanted = null;
                    return CompanionBrain.orderTradeReply(c, wanted, params.tradeCountOr(0), params.forOwner());
                }
                case GEAR_UP -> {
                    int tier = params.tier() == null ? CompanionJobs.gearTarget(c, List.of())
                            : switch (params.tier()) { case "netherite" -> 5; case "diamond" -> 4; default -> 3; };
                    return CompanionBrain.orderGearUpReply(c, tier);
                }
                case KEEPER -> {
                    var held = c.holdOrdersComponent();
                    return CompanionTexts.tr(c.makeKeeper() ? "reply.keeper" : "reply.no_home").append(held);
                }
                case SORT -> { return CompanionBrain.orderSortReply(player, c); }
                case DOOR -> { return CompanionBrain.orderDoorReply(player, c); }
                case LIST_SCHEMATICS -> { return CompanionBrain.listSchematicsReply(); }
                case BUILD_SCHEMATIC -> {
                    reply = CompanionBrain.orderSchematicReply(player, c, params.schematic(), params.withAir(), sentence.tokens());
                    if (reply != null) return reply;
                }
                case BUILD_STRUCTURE -> {
                    // Maschinen erkennt nur das Lexikon am Wort im Satz; das LLM hat dafür keinen Parameter.
                    var machine = source.llm() ? null
                            : CompanionMachines.ALL.stream().filter(b -> sentence.tokens().contains(b.id())).findFirst().orElse(null);
                    if (machine != null) return CompanionBrain.startMachineReply(player, c, machine, net.minecraft.world.level.block.Rotation.NONE);
                    boolean farm = source.llm() ? source.farm() : sentence.anyStart("farm", "feld", "weizen", "acker", "field", "wheat");
                    var style = "stone".equals(params.style()) ? CompanionStructures.STONE
                            : "birch".equals(params.style()) ? CompanionStructures.BIRCH : CompanionStructures.WOOD;
                    int[] size = "small".equals(params.size()) ? new int[]{5, 5}
                            : "large".equals(params.size()) ? new int[]{9, 7} : new int[]{7, 7};
                    return CompanionBrain.orderStructureReply(player, c, farm, style, size, "large".equals(params.size()));
                }
                case FETCH_ANIMAL -> { return CompanionBrain.orderFetchReply(c, params.mob()); }
                case GIVE -> {
                    if (resource == null) return CompanionBrain.orderGiveAllReply(c);
                    c.cancelAll();
                    c.addTask(Task.give(resource.key(), 0));
                    return CompanionTexts.tr("order.give.resource", CompanionTexts.resource(resource.key()));
                }
                case MATERIALS_FOR, CRAFT_FOR_OWNER -> {
                    return CompanionRequests.order(c, params, intent == ChatIntent.MATERIALS_FOR);
                }
                case OBTAIN -> {
                    Item wanted = item(params.item(), itemWords);
                    if (wanted != null && (resource == null || CompanionBuilder.rawSource(wanted) == null || CompanionBrain.isTool(wanted)))
                        return CompanionBrain.orderObtainReply(c, wanted, params.countOr(1), true);
                }
                case GATHER -> {
                    if (resource != null) return CompanionBrain.orderGatherReply(c, resource, params.countOr(16), params.toOwner());
                }
                case FREE_ROAM, FOLLOW, STAY -> {
                    // Der Registry-Rückfall hat in einer Anfrage weiter Vorrang vor Moduswörtern.
                    reply = source.llm() ? null : registryObtain(c, sentence, resource);
                    if (reply != null) return reply;
                    var held = c.holdOrdersComponent();
                    if (intent == ChatIntent.FREE_ROAM) { c.freeRoam(); return CompanionTexts.tr("reply.free").append(held); }
                    if (intent == ChatIntent.FOLLOW) { c.follow(); return CompanionTexts.tr("reply.follow").append(held); }
                    c.stay();
                    return CompanionTexts.tr("reply.stay").append(held);
                }
            }
        }
        return source.llm() ? null : registryObtain(c, sentence, resource);
    }

    /** Die Lexika greifen bewusst nicht auf Registries zu; der Einzelwort-Rückfall für Items bleibt deshalb hier. */
    private static Item item(String id, List<String> tokens) {
        if (id != null) {
            Item known = ItemIds.resolve(id);
            if (known != null && known != Items.AIR) return known;
        }
        for (String token : tokens) {
            if (token.length() < 3) continue;
            Item known = ItemIds.resolve(token);
            if (known != null && known != Items.AIR) return known;
        }
        return null;
    }

    private static Component registryObtain(CompanionEntity c, Sentence s, Resources.Res resource) {
        // Ergänzt nur Nomen, die das Lexikon nicht kennt; normaler Chat braucht weiterhin ein Auftragsverb.
        if (!s.any("bring", "bringe", "bringen", "hol", "hole", "holen", "besorg", "besorge", "besorgen",
                "sammle", "sammeln", "sammel", "such", "suche", "suchen", "finde", "finden", "brauche", "brauch",
                "fell", "faelle", "faellen", "abbauen", "hack", "hacke", "grab", "grabe", "mine", "farm", "farme",
                "beschaff", "beschaffe", "mach", "mache", "craft", "crafte", "stell", "stelle", "herstellen",
                "get", "fetch", "gather", "collect", "find", "search", "need", "chop", "cut", "dig", "grab",
                "obtain", "acquire", "procure", "make", "create", "produce")) return null;
        Item wanted = item(null, s.tokens());
        if (wanted == null || resource != null && CompanionBuilder.rawSource(wanted) != null && !CompanionBrain.isTool(wanted)) return null;
        return CompanionBrain.orderObtainReply(c, wanted, ChatCount.parse(s.tokens(), 1), true);
    }

    static Component waypoint(ServerPlayer player, CompanionEntity c, ChatIntent intent, Sentence s, ChatParams params) {
        var level = (ServerLevel) c.level();
        var memory = c.memory;
        if (intent == ChatIntent.WAYPOINT_SAVE) {
            if (params.waypoint() == null) return CompanionTexts.tr("waypoint.ask_name");
            String name = params.waypoint();
            memory.setWaypoint(name, level, player.blockPosition());
            return CompanionTexts.tr("waypoint.saved", CompanionTexts.literal(name), player.blockPosition().toShortString(), CompanionTexts.literal(name));
        }
        if (intent == ChatIntent.WAYPOINT_FORGET) {
            var point = memory.waypointIn(s.text(), level);
            if (point == null && s.has("wegpunkt", "waypoint")) {
                // Keep the existing explicit-waypoint shorthand: 'mine' may name 'iron mine'.
                String name = CompanionJobs.waypointName(s.tokens(), "wegpunkt", "waypoint");
                if (name != null) {
                    name = String.join(" ", List.of(name.split(" ")).stream()
                            .filter(word -> !java.util.Set.of("the", "my", "our", "please", "companion").contains(word)).toList());
                    point = memory.waypoint(name, level);
                }
            }
            if (point == null) return s.has("wegpunkt", "waypoint") ? CompanionTexts.tr("waypoint.unknown") : null;
            memory.removeWaypoint(point.key);
            return CompanionTexts.tr("waypoint.forgotten", CompanionTexts.literal(point.key));
        }
        if (intent == ChatIntent.WAYPOINT_LIST) {
            var all = memory.waypoints();
            if (all.isEmpty()) return CompanionTexts.tr("waypoint.empty");
            List<Component> parts = new ArrayList<>();
            String here = CompanionMemory.dim(level);
            for (var point : all) parts.add(CompanionTexts.tr("waypoint.entry", CompanionTexts.literal(point.key),
                    point.dim.equals(here) ? CompanionTexts.location(point, player.blockPosition()) : CompanionTexts.tr("waypoint.other_dimension")));
            return CompanionTexts.tr("waypoint.list", CompanionTexts.join(parts, ", "));
        }
        if (intent == ChatIntent.WAYPOINT_GO) {
            var point = memory.waypointIn(s.text(), level);
            if (point == null) return null;
            int distance = (int) Math.sqrt(point.pos.distSqr(c.blockPosition()));
            c.cancelAll();
            c.stay();
            if (distance > 24) c.addTask(Task.travel(point.pos));
            c.addTask(Task.moveTo(point.pos, 4));
            return CompanionTexts.tr("waypoint.go", CompanionTexts.literal(point.key), distance);
        }
        return null;
    }
}
