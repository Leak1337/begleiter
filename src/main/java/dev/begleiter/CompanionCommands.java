package dev.begleiter;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Befehle an den Begleiter (der nächste eigene Begleiter im Umkreis von 64 Blöcken):
 *   /companion follow | stay | status | cancel | resume | sort | tidy | keeper
 *   /companion home                       (Kiste anschauen, die als Lager dient)
 *   /companion gather <block> [count]    (z.B. minecraft:iron_ore 16)
 *   /companion build platform <block> <width> <depth>
 *   /companion build wall <block> <width> <height>
 *   /companion clear <width> <height> <depth>
 *   /companion project [preview | confirm | status | check | discard]
 *   /companion parkourtest [build] | <lane> | show | stop   (Parkour-Arena, siehe {@link ParkourCourse})
 *   /companion testmap [liste | alle | stop | kamera | szenario <name[,name]> | neu <station>]
 *                                         (Testgelände und Szenarien, siehe {@link TestMap}, {@link TestScenarios})
 *   /companion llm [status | on | off | reload | test <satz>]   (siehe {@link CompanionLlmCommands})
 */
public final class CompanionCommands {
    private CompanionCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx,
                                Commands.CommandSelection selection) {
        d.register(Commands.literal("companion")
                .then(CompanionLlmCommands.node())
                .then(simple("follow", (p, c) -> { c.follow(); return msg(p, CompanionTexts.tr("reply.follow")); }))
                .then(Commands.literal("log")
                        .executes(c -> with(c, (p, comp) -> printLog(p, comp, 12)))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(c -> {
                                    int n = IntegerArgumentType.getInteger(c, "count");
                                    return with(c, (p, comp) -> printLog(p, comp, n));
                                })))
                .then(simple("debug", (p, c) -> {
                    c.debugComponents().forEach(p::sendSystemMessage);
                    return 1;
                }))
                .then(simple("free", (p, c) -> { c.freeRoam(); return msg(p, CompanionTexts.tr("reply.free")); }))
                .then(simple("stay", (p, c) -> { c.stay(); return msg(p, CompanionTexts.tr("reply.stay")); }))
                .then(simple("cancel", (p, c) -> { c.cancelOrders(); return msg(p, CompanionTexts.tr("reply.cancel")); }))
                .then(simple("resume", (p, c) -> msg(p, CompanionBrain.resumeReply(c))))
                .then(simple("status", (p, c) -> msg(p, CompanionTexts.status(c))))
                .then(Commands.literal("project")
                        .executes(c -> previewProject(c.getSource().getPlayerOrException()))
                        .then(Commands.literal("preview")
                                .executes(c -> previewProject(c.getSource().getPlayerOrException())))
                        .then(Commands.literal("confirm")
                                .executes(c -> with(c, CompanionCommands::confirmProject)))
                        .then(Commands.literal("status")
                                .executes(c -> with(c, CompanionCommands::projectStatus)))
                        .then(Commands.literal("check")
                                .executes(c -> with(c, CompanionCommands::verifyProject)))
                        .then(Commands.literal("discard")
                                .executes(c -> with(c, CompanionCommands::discardProject))))
                .then(simple("schematics", (p, c) -> msg(p, CompanionBrain.listSchematicsReply())))
                .then(Commands.literal("schematic")
                        .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(c -> schematic(c, false))
                                .then(Commands.literal("air").executes(c -> schematic(c, true)))
                                // Alte Schreibweise bleibt für gespeicherte Befehlsblöcke und Kurzbefehle.
                                .then(Commands.literal("luft").executes(c -> schematic(c, true)))))
                .then(simple("material", (p, c) -> c.build == null ? fail(p, CompanionTexts.tr("build.none"))
                        : msg(p, materialReply(c))))
                .then(Commands.literal("parkourtest")
                        // Dieser Befehl räumt und ersetzt Tausende Weltblöcke.
                        // Nur für Weltbesitzer/Operatoren, nicht für gewöhnliche Begleiter-Besitzer.
                        .requires(source -> source.permissions().hasPermission(
                                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            return msg(p, ParkourCourse.build(p));
                        })
                        .then(Commands.literal("build").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            return msg(p, ParkourCourse.build(p));
                        }))
                        .then(Commands.literal("bauen").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            return msg(p, ParkourCourse.build(p));
                        }))
                        .then(simple("show", (p, comp) -> msg(p, ParkourCourse.startShow(p, comp))))
                        .then(Commands.literal("stop").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            return msg(p, ParkourCourse.stopShow(p));
                        }))
                        .then(Commands.argument("lane", IntegerArgumentType.integer(1, ParkourCourse.lanes()))
                                .executes(c -> {
                                    int lane = IntegerArgumentType.getInteger(c, "lane");
                                    return with(c, (p, comp) -> msg(p, ParkourCourse.run(p, comp, lane)));
                                })))
                .then(Commands.literal("testmap")
                        // Ersetzt eine Fläche von 56 × 56 um den Spieler: nur Operatoren, wie parkourtest.
                        .requires(source -> source.permissions().hasPermission(
                                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            return msg(p, TestMap.build(p));
                        })
                        .then(Commands.literal("liste").executes(c ->
                                msg(c.getSource().getPlayerOrException(), TestScenarios.list())))
                        .then(Commands.literal("ausstellung").executes(c ->
                                msg(c.getSource().getPlayerOrException(), TestMapExpansion.visit(c.getSource().getPlayerOrException(), true))))
                        .then(Commands.literal("farmfeld").executes(c ->
                                msg(c.getSource().getPlayerOrException(), TestMapExpansion.visitField(c.getSource().getPlayerOrException())))
                                // Auch per Knopf/Befehlsblock an der Wand am Feld (dann ohne Spieler als Quelle)
                                .then(fieldReset(TestMapExpansion.FieldReset.ALLES))
                                .then(fieldReset(TestMapExpansion.FieldReset.ITEMS))
                                .then(fieldReset(TestMapExpansion.FieldReset.TIERE)))
                        .then(Commands.literal("redstone").executes(c ->
                                msg(c.getSource().getPlayerOrException(), TestMapExpansion.visit(c.getSource().getPlayerOrException(), false))))
                        .then(Commands.literal("alle").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            return msg(p, TestScenarios.start(p, TestScenarios.allDistinct()));
                        }))
                        .then(Commands.literal("stop").executes(c ->
                                msg(c.getSource().getPlayerOrException(), TestScenarios.stop(null))))
                        .then(Commands.literal("kamera").executes(c ->
                                msg(c.getSource().getPlayerOrException(), TestScenarios.toggleCamera())))
                        .then(Commands.literal("szenario")
                                .then(Commands.argument("name", StringArgumentType.greedyString()) // Kommas erlaubt
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(
                                                TestScenarios.GROUPS.stream(),
                                                TestScenarios.all().stream().map(TestScenarios.Scenario::id)), b))
                                        .executes(c -> {
                                            ServerPlayer p = c.getSource().getPlayerOrException();
                                            // Mehrere mit Komma, auch Gruppen: "sprung1,sprung2" oder "redstone,mlg"
                                            List<String> unknown = new java.util.ArrayList<>();
                                            var picked = TestScenarios.pick(StringArgumentType.getString(c, "name"), unknown);
                                            if (!unknown.isEmpty()) return fail(p, "Unbekanntes Szenario: "
                                                    + String.join(", ", unknown) + " – /companion testmap liste");
                                            return msg(p, TestScenarios.start(p, picked));
                                        })))
                        .then(Commands.literal("neu")
                                .then(Commands.argument("station", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                TestMap.STATIONS.stream().map(TestMap.Station::id), b))
                                        .executes(c -> {
                                            ServerPlayer p = c.getSource().getPlayerOrException();
                                            TestMap.Station st = TestMap.station(StringArgumentType.getString(c, "station"));
                                            var o = TestMap.origin(p.level().getServer());
                                            if (st == null || o == null) return fail(p, "Unknown station or no test map built.");
                                            TestMap.rebuild(p.level(), o, st);
                                            return msg(p, "Station " + st.id() + " rebuilt.");
                                        }))))
                .then(simple("sort", (p, c) -> msg(p, CompanionBrain.orderSortReply(p, c))))
                .then(simple("tidy", (p, c) -> { c.queueTidy(); return msg(p, CompanionTexts.tr("command.tidy")); }))
                .then(simple("keeper", (p, c) -> c.makeKeeper()
                        ? msg(p, CompanionTexts.tr("reply.keeper"))
                        : fail(p, CompanionTexts.tr("reply.no_home"))))
                .then(simple("home", (p, c) -> {
                    HitResult hit = p.pick(6.0, 0f, false);
                    if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
                            && c.containerAt(p.level(), bhr.getBlockPos()) != null) {
                        c.setHome(bhr.getBlockPos());
                        return msg(p, CompanionTexts.tr("order.home.set", bhr.getBlockPos().toShortString()));
                    }
                    return fail(p, CompanionTexts.tr("command.home.look"));
                }))
                .then(Commands.literal("gather")
                        .then(Commands.argument("block", BlockStateArgument.block(ctx))
                                .executes(c -> gather(c, 16))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                                        .executes(c -> gather(c, IntegerArgumentType.getInteger(c, "count"))))))
                .then(Commands.literal("build")
                        .then(Commands.literal("platform")
                                .then(Commands.argument("block", BlockStateArgument.block(ctx))
                                        .then(Commands.argument("width", IntegerArgumentType.integer(1, 32))
                                                .then(Commands.argument("depth", IntegerArgumentType.integer(1, 32))
                                                        .executes(c -> build(c, true, "width", "depth"))))))
                        .then(Commands.literal("wall")
                                .then(Commands.argument("block", BlockStateArgument.block(ctx))
                                        .then(Commands.argument("width", IntegerArgumentType.integer(1, 32))
                                                .then(Commands.argument("height", IntegerArgumentType.integer(1, 32))
                                                        .executes(c -> build(c, false, "width", "height")))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("width", IntegerArgumentType.integer(1, 16))
                                .then(Commands.argument("height", IntegerArgumentType.integer(1, 16))
                                        .then(Commands.argument("depth", IntegerArgumentType.integer(1, 16))
                                                .executes(c -> clear(c, "width", "height", "depth")))))));
        // Deutschsprachige Slash-Befehle. Alte /companion-Befehle bleiben kompatibel.
        d.register(Commands.literal("begleiter")
                .then(CompanionLlmCommands.node())
                .then(simple("folgen", (p, c) -> { c.follow(); return msg(p, CompanionTexts.tr("reply.follow")); }))
                .then(simple("warten", (p, c) -> { c.stay(); return msg(p, CompanionTexts.tr("reply.stay")); }))
                .then(simple("selbststaendig", (p, c) -> { c.freeRoam(); return msg(p, CompanionTexts.tr("reply.free")); }))
                .then(simple("abbrechen", (p, c) -> { c.cancelOrders(); return msg(p, CompanionTexts.tr("reply.cancel")); }))
                .then(simple("fortsetzen", (p, c) -> msg(p, CompanionBrain.resumeReply(c))))
                .then(simple("status", (p, c) -> msg(p, CompanionTexts.status(c))))
                .then(Commands.literal("projekt")
                        .executes(c -> previewProject(c.getSource().getPlayerOrException()))
                        .then(Commands.literal("vorschau")
                                .executes(c -> previewProject(c.getSource().getPlayerOrException())))
                        .then(Commands.literal("bestaetigen")
                                .executes(c -> with(c, CompanionCommands::confirmProject)))
                        .then(Commands.literal("status")
                                .executes(c -> with(c, CompanionCommands::projectStatus)))
                        .then(Commands.literal("pruefen")
                                .executes(c -> with(c, CompanionCommands::verifyProject)))
                        .then(Commands.literal("verwerfen")
                                .executes(c -> with(c, CompanionCommands::discardProject))))
                .then(simple("bauplaene", (p, c) -> msg(p, CompanionBrain.listSchematicsReply())))
                .then(simple("sortieren", (p, c) -> msg(p, CompanionBrain.orderSortReply(p, c))))
                .then(simple("einlagern", (p, c) -> { c.queueTidy(); return msg(p, CompanionTexts.tr("command.store")); }))
                .then(simple("diagnose", (p, c) -> {
                    c.debugComponents().forEach(p::sendSystemMessage);
                    return 1;
                }))
                .then(simple("material", (p, c) -> c.build == null ? fail(p, CompanionTexts.tr("build.none"))
                        : msg(p, materialReply(c))))
                .then(Commands.literal("protokoll")
                        .executes(a -> with(a, (p, c) -> printLog(p, c, 12)))
                        .then(Commands.argument("anzahl", IntegerArgumentType.integer(1, 50))
                                .executes(a -> with(a, (p, c) ->
                                        printLog(p, c, IntegerArgumentType.getInteger(a, "anzahl"))))))
                .then(Commands.literal("bauplan")
                        .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(a -> schematic(a, false))
                                .then(Commands.literal("luft").executes(a -> schematic(a, true)))))
                .then(simple("lager", (p, c) -> {
                    HitResult hit = p.pick(6.0, 0f, false);
                    if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                            && c.containerAt(p.level(), block.getBlockPos()) != null) {
                        c.setHome(block.getBlockPos());
                        return msg(p, CompanionTexts.tr("order.home.set", block.getBlockPos().toShortString()));
                    }
                    return fail(p, CompanionTexts.tr("command.home.look"));
                }))
                .then(simple("lagerverwalter", (p, c) -> c.makeKeeper()
                        ? msg(p, CompanionTexts.tr("reply.keeper")) : fail(p, CompanionTexts.tr("reply.no_home"))))
                .then(Commands.literal("sammeln")
                        .then(Commands.argument("block", BlockStateArgument.block(ctx))
                                .executes(c -> gather(c, 16))
                                .then(Commands.argument("anzahl", IntegerArgumentType.integer(1, 256))
                                        .executes(c -> gather(c, IntegerArgumentType.getInteger(c, "anzahl"))))))
                .then(Commands.literal("bauen")
                        .then(Commands.literal("plattform")
                                .then(Commands.argument("block", BlockStateArgument.block(ctx))
                                        .then(Commands.argument("breite", IntegerArgumentType.integer(1, 32))
                                                .then(Commands.argument("tiefe", IntegerArgumentType.integer(1, 32))
                                                        .executes(c -> build(c, true))))))
                        .then(Commands.literal("wand")
                                .then(Commands.argument("block", BlockStateArgument.block(ctx))
                                        .then(Commands.argument("breite", IntegerArgumentType.integer(1, 32))
                                                .then(Commands.argument("hoehe", IntegerArgumentType.integer(1, 32))
                                                        .executes(c -> build(c, false)))))))
                .then(Commands.literal("freiraeumen")
                        .then(Commands.argument("breite", IntegerArgumentType.integer(1, 16))
                                .then(Commands.argument("hoehe", IntegerArgumentType.integer(1, 16))
                                        .then(Commands.argument("tiefe", IntegerArgumentType.integer(1, 16))
                                                .executes(c -> clear(c, "breite", "hoehe", "tiefe")))))));
    }

    @FunctionalInterface
    private interface Action { int run(ServerPlayer p, CompanionEntity c); }

    private static int schematic(CommandContext<CommandSourceStack> ctx, boolean withAir) throws CommandSyntaxException {
        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name");
        return with(ctx, (p, c) -> {
            var machine = CompanionMachines.find(name);
            if (machine != null) return msg(p, CompanionBrain.startMachineReply(p, c, machine, net.minecraft.world.level.block.Rotation.NONE));
            java.nio.file.Path file = CompanionSchematics.find(name);
            if (file == null) return fail(p, CompanionTexts.tr("schematic.missing", CompanionTexts.literal(name), CompanionBrain.listSchematicsReply()));
            return msg(p, CompanionBrain.startSchematicReply(p, c, file, withAir, net.minecraft.world.level.block.Rotation.NONE));
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> simple(String name, Action a) {
        return Commands.literal(name).executes(c -> with(c, a));
    }

    private static int with(CommandContext<CommandSourceStack> c, Action a) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        CompanionEntity comp = find(p);
        if (comp == null) return fail(p, CompanionTexts.tr("command.no_companion"));
        return a.run(p, comp);
    }

    private static CompanionEntity find(ServerPlayer p) {
        List<CompanionEntity> list = p.level().getEntitiesOfClass(CompanionEntity.class,
                p.getBoundingBox().inflate(64), e -> e.isOwner(p));
        return list.stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
    }

    private static int printLog(ServerPlayer p, CompanionEntity comp, int n) {
        List<CompanionEntity.LogEntry> lines = comp.getLog(0, n, false);
        for (int i = lines.size() - 1; i >= 0; i--)
            p.sendSystemMessage(CompanionEntity.formatLogText(lines.get(i)));
        return lines.size();
    }

    private static int previewProject(ServerPlayer p) {
        return msg(p, CompanionProjektVorschau.planen(p).beschreibenText());
    }

    private static int confirmProject(ServerPlayer p, CompanionEntity comp) {
        var vorschau = CompanionProjektVorschau.planen(p);
        String dimension = p.level().dimension().identifier().toString();
        var ergebnis = comp.projekt.bestaetigen(vorschau, dimension, p.blockPosition(), p.getDirection());
        return ergebnis.erfolgreich() ? msg(p, ergebnis.nachrichtText()) : fail(p, ergebnis.nachrichtText());
    }

    private static int projectStatus(ServerPlayer p, CompanionEntity comp) {
        return msg(p, comp.projekt.beschreibenText());
    }

    private static int verifyProject(ServerPlayer p, CompanionEntity comp) {
        var ergebnis = comp.projekt.pruefen(p.level());
        return ergebnis.erfolgreich() ? msg(p, ergebnis.nachrichtText()) : fail(p, ergebnis.nachrichtText());
    }

    private static int discardProject(ServerPlayer p, CompanionEntity comp) {
        var ergebnis = comp.projekt.verwerfen();
        return ergebnis.erfolgreich() ? msg(p, ergebnis.nachrichtText()) : fail(p, ergebnis.nachrichtText());
    }

    /**
     * {@code /companion testmap farmfeld reset|items|tiere}: vom Spieler oder vom Befehlsblock an der Wand.
     * Beim Knopf bekommen die Spieler in der Nähe der Wand die Rückmeldung.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> fieldReset(TestMapExpansion.FieldReset what) {
        return Commands.literal(what.id).executes(c -> {
            var source = c.getSource();
            String reply = TestMapExpansion.resetField(source.getLevel(), what);
            if (source.getPlayer() != null) return msg(source.getPlayer(), reply);
            for (ServerPlayer p : source.getLevel().players())
                if (p.position().closerThan(source.getPosition(), 24)) msg(p, reply);
            return 1;
        });
    }

    private static int msg(ServerPlayer p, String s) { return msg(p, CompanionTexts.literal(s)); }

    private static int msg(ServerPlayer p, Component message) {
        p.sendSystemMessage(CompanionTexts.prefixed(message));
        return 1;
    }

    private static int fail(ServerPlayer p, String s) { return fail(p, CompanionTexts.literal(s)); }

    private static int fail(ServerPlayer p, Component message) {
        p.sendSystemMessage(CompanionTexts.prefixed(message));
        return 0;
    }

    private static Component materialReply(CompanionEntity c) {
        return CompanionTexts.tr("command.materials", CompanionTexts.buildName(c.build.name), CompanionTexts.materials(
                CompanionBuilder.remaining((net.minecraft.server.level.ServerLevel) c.level(), c.build)));
    }

    private static Block block(CommandContext<CommandSourceStack> c) {
        return BlockStateArgument.getBlock(c, "block").getState().getBlock();
    }

    private static int gather(CommandContext<CommandSourceStack> c, int count) throws CommandSyntaxException {
        Block b = block(c);
        return with(c, (p, comp) -> {
            int n = comp.queueGather(b, count);
            return n == 0 ? fail(p, CompanionTexts.tr("command.gather.none", b.getName()))
                    : msg(p, CompanionTexts.tr("command.gather", n, b.getName()));
        });
    }

    private static int build(CommandContext<CommandSourceStack> c, boolean platform) throws CommandSyntaxException {
        return build(c, platform, "breite", platform ? "tiefe" : "hoehe");
    }

    private static int build(CommandContext<CommandSourceStack> c, boolean platform, String width, String extent)
            throws CommandSyntaxException {
        Block b = block(c);
        int w = IntegerArgumentType.getInteger(c, width);
        int other = IntegerArgumentType.getInteger(c, extent);
        return with(c, (p, comp) -> {
            int n = platform ? comp.queuePlatform(p, b, w, other) : comp.queueWall(p, b, w, other);
            return msg(p, CompanionTexts.tr("command.build", n, b.getName()));
        });
    }

    private static int clear(CommandContext<CommandSourceStack> c, String width, String height, String depth)
            throws CommandSyntaxException {
        int w = IntegerArgumentType.getInteger(c, width);
        int h = IntegerArgumentType.getInteger(c, height);
        int d = IntegerArgumentType.getInteger(c, depth);
        return with(c, (p, comp) -> {
            int n = comp.queueClear(p, w, h, d);
            return n == 0 ? fail(p, CompanionTexts.tr("command.clear.none")) : msg(p, CompanionTexts.tr("command.clear", n));
        });
    }
}
