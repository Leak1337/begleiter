package dev.begleiter;

import java.util.ArrayList;
import java.util.List;
import dev.begleiter.debug.BotDebug;
import dev.begleiter.debug.BotDebug.EventKind;
import dev.begleiter.debug.BotDebugServer;
import dev.begleiter.nav.Move;
import dev.begleiter.nav.Movement;
import dev.begleiter.nav.NavContext;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Tests translated log history, event metadata, problem throttling and real movement failure output. */
public class LogTextGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        h.setBlock(2, 0, 2, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        prepare(c); return c;
    }
    private static void prepare(CompanionEntity c) { c.stay(); c.setNoAi(true); c.buildModeOverride = false; c.clearLog(); }
    private static ServerPlayer admin(GameTestHelper h, CompanionEntity c) {
        var p = h.makeMockServerPlayerInLevel(); c.setOwner(p.getUUID()); p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        h.getLevel().getServer().getPlayerList().op(p.nameAndId(),
                java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.of(false));
        return p;
    }
    private static Component wire(GameTestHelper h, Component text) {
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            ComponentSerialization.STREAM_CODEC.encode(buffer, text);
            var back = ComponentSerialization.STREAM_CODEC.decode(buffer);
            h.assertValueEqual(buffer.readableBytes(), 0, "Unread log component bytes"); return back;
        } finally { buffer.release(); }
    }
    private static void translated(GameTestHelper h, Component text, String de, String en) throws Exception {
        var back = wire(h, text);
        h.assertTrue(back.getContents() instanceof TranslatableContents, "Log component flattened");
        ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(back.getString().contains(de), back.getString()));
        ServerTextGameTests.inLanguage("en_us", () -> {
            h.assertTrue(back.getString().contains(en), back.getString());
            h.assertTrue(!back.getString().contains("server.begleiter."), "Missing log translation");
        });
    }
    private static BotDebug.Snapshot debug(GameTestHelper h, CompanionEntity c, ServerPlayer p, int level) {
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            BotDebug.Snapshot.CODEC.encode(buffer, BotDebugServer.snapshot(p, c.getUUID(), level, false, "", List.of(c)));
            var back = BotDebug.Snapshot.CODEC.decode(buffer);
            h.assertValueEqual(buffer.readableBytes(), 0, "Unread debug bytes"); return back;
        } finally { buffer.release(); }
    }

    @GameTest
    public void actualCraftAndFoodLogsStayTranslatedInHudAndMenu(GameTestHelper h) throws Exception {
        var c = companion(h); var p = admin(h, c);
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 4));
        h.assertTrue(CompanionCraft.craft(c, "crafting_table", 1), "Craft fixture failed");
        h.assertValueEqual(CompanionCraft.count(c, "crafting_table"), 1, "Craft result changed");
        h.assertTrue(c.getLog(0, 1, false).getFirst().kind() == EventKind.CRAFT, "Craft event kind missing");
        var craftName = (TranslatableContents) c.getLog(0, 1, false).getFirst().textComponent().getContents();
        h.assertTrue(craftName.getArgs()[1] instanceof Component item && item.getContents() instanceof TranslatableContents, "Native crafted item flattened");
        c.giveToInventory(new ItemStack(Items.BREAD, 2)); c.food.set(8); c.tickCount = 20;
        c.customServerAiStep(h.getLevel());
        h.assertTrue(c.food.level() == 13 && CompanionCraft.count(c, "bread") == 1, "Eating behavior changed");
        var full = debug(h, c, p, BotDebug.LEVEL_FULL).detail();
        var hud = debug(h, c, p, BotDebug.LEVEL_HUD).detail();
        h.assertTrue(full.logLines().getFirst().kind() == EventKind.FOOD && hud.logLines().size() == 2,
                "Typed events were filtered by their display text");
        translated(h, full.logLines().getFirst().text(), "Gegessen:", "Eaten:");
        translated(h, full.logLines().get(1).text(), "Hergestellt:", "Crafted:");
        var menu = new CompanionMenu(1, p.getInventory(), c, CompanionMenu.Page.LOG);
        // Die Diagnoseseite zeigt anfangs nur Probleme; Essen ist ein Info-Eintrag
        menu.clicked(CompanionMenu.LOG_FILTER, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p);
        translated(h, menu.getSlot(0).getItem().getHoverName(), "Gegessen:", "Eaten:");
        h.assertTrue(hud.inventoryLines().isEmpty() && hud.equipmentLines().isEmpty(), "HUD grew full detail");
        h.succeed();
    }

    @GameTest
    public void logMergingUsesTextKindAndSeverityAndKeepsTheRetentionLimit(GameTestHelper h) throws Exception {
        var c = companion(h);
        for (int i = 0; i < 2; i++) {
            c.tickCount = 500 + i;
            ServerTextGameTests.inLanguage(i == 0 ? "de_de" : "en_us", () -> c.logInfo(EventKind.CRAFT,
                    CompanionTexts.tr("log.craft.made", 1, new ItemStack(Items.TORCH).getHoverName())));
        }
        h.assertTrue(c.logSize() == 1 && c.getLog(0, 1, false).getFirst().count() == 2
                && c.getLog(0, 1, false).getFirst().tick() == 501, "Locale changed merging or latest timestamp");
        c.logInfo(EventKind.LOOT, CompanionTexts.tr("log.craft.made", 1, new ItemStack(Items.TORCH).getHoverName()));
        c.logWarn(EventKind.CRAFT, CompanionTexts.tr("log.craft.made", 1, new ItemStack(Items.TORCH).getHoverName()));
        h.assertTrue(c.logSize() == 3 && c.getLog(0, 150, true).size() == 1, "Different kinds/levels merged or filter changed");
        var mutable = CompanionTexts.tr("log.menu", Component.literal("My custom %s Ä"));
        c.logInfo(mutable); mutable.append(" MUTATED");
        ServerTextGameTests.inLanguage("en_us", () -> h.assertTrue(c.getLog(0, 1, false).getFirst().text().equals("Menu: My custom %s Ä"), "Caller mutated recorded history"));
        c.logInfo("Literal %s Ä");
        h.assertTrue(c.getLog(0, 1, false).getFirst().text().equals("Literal %s Ä"), "Legacy string was formatted");
        for (int i = 0; i < 151; i++) c.logInfo("entry " + i);
        h.assertTrue(c.logSize() == 150 && c.getLog(0, 150, false).getLast().text().equals("entry 1"), "Log retention changed");
        c.clearLog(); h.assertValueEqual(c.logSize(), 0, "Clear no longer removes history");
        h.succeed();
    }

    private static final class TrackingCompanion extends CompanionEntity {
        final List<Component> notices = new ArrayList<>();
        TrackingCompanion(ServerLevel level) { super(CompanionMod.COMPANION, level); }
        @Override public void tell(Component text) { notices.add(text.copy()); }
    }

    @GameTest
    public void problemThrottlingSurvivesLocaleChangesAndBothSnapshotsKeepComponents(GameTestHelper h) throws Exception {
        var c = new TrackingCompanion(h.getLevel()); var pos = h.absolutePos(new BlockPos(2, 1, 2));
        c.snapTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0); h.getLevel().addFreshEntity(c); prepare(c);
        var p = admin(h, c);
        for (int tick : new int[]{1000, 1001, 2200, 2201}) {
            c.tickCount = tick;
            ServerTextGameTests.inLanguage(tick % 2 == 0 ? "de_de" : "en_us", () -> c.problem(EventKind.DANGER,
                    CompanionTexts.tr("log.problem.loop", CompanionTexts.tr("planner.stage.wood_tools"))));
            h.assertValueEqual(c.notices.size(), tick <= 2200 ? 1 : 2, "Problem notice throttle changed with locale/time");
        }
        h.assertTrue(c.problemTick == 2201 && c.getLog(0, 1, true).getFirst().count() == 4, "Problem timestamp/repetitions changed");
        translated(h, c.notices.getFirst(), "drehe mich im Kreis", "repeating");
        var panelBuffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            CompanionSnapshot.CODEC.encode(panelBuffer, CompanionSnapshot.capture(c, 1, CompanionMenu.Page.STATUS));
            var panel = CompanionSnapshot.CODEC.decode(panelBuffer);
            h.assertTrue(panel.hasProblem(), "Problem flag lost");
            translated(h, panel.problemText(), "drehe mich im Kreis", "repeating");
        } finally { panelBuffer.release(); }
        translated(h, debug(h, c, p, BotDebug.LEVEL_FULL).detail().lastErrorText(), "drehe mich im Kreis", "repeating");
        c.problemText = "My literal problem %s Ä";
        ServerTextGameTests.inLanguage("en_us", () -> h.assertTrue(c.problemComponent().getString().equals("My literal problem %s Ä"), "Stale structured problem hid literal update"));
        h.succeed();
    }

    @GameTest
    public void diagnosticComponentsKeepTaskStageProblemAndNavigationState(GameTestHelper h) throws Exception {
        var c = companion(h); var at = c.blockPosition();
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        c.addTask(CompanionEntity.Task.gather("eisen", 3)); c.setStage(CompanionTexts.tr("task.craft"));
        c.problem(CompanionTexts.tr("route.known_danger"));
        c.nav.goTo(new dev.begleiter.nav.Goal.Block(at.east(3)));
        c.nav.recoveryEvent(CompanionTexts.tr("nav.event.failed", CompanionTexts.tr("route.known_danger")));
        long generation = c.nav.generation(); var executor = c.nav.executor(); boolean searching = c.nav.searching();
        var tasks = c.peekTasks(10); var position = c.position(); var lines = c.watchdog.debugComponents();
        for (var line : lines) {
            if (!(line.getContents() instanceof TranslatableContents tr)) continue;
            switch (tr.getKey()) {
                case "server.begleiter.diagnostic.planner" -> translated(h, line, "Herstellen", "Craft");
                case "server.begleiter.diagnostic.task" -> translated(h, line, "Eisen", "iron");
                case "server.begleiter.diagnostic.hazard" -> translated(h, line, "bekannte Gefahr am Ziel", "known danger at the destination");
                case "server.begleiter.diagnostic.route" -> translated(h, line, "Weg gescheitert", "Path failed");
                default -> { }
            }
        }
        h.assertTrue(lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents tr
                && tr.getKey().equals("server.begleiter.diagnostic.route")), "Active route diagnostic missing");
        h.assertTrue(c.peekTasks(10).equals(tasks) && c.position().equals(position) && c.usedSlots() == 0
                && c.nav.generation() == generation && c.nav.executor() == executor && c.nav.searching() == searching,
                "Reading diagnostics changed work or navigation");
        c.nav.stop(); h.succeed();
    }

    @GameTest
    public void ownerCombatLogsKeepTargetSelectionAndNativeName(GameTestHelper h) throws Exception {
        var c = companion(h); var p = h.makeMockServerPlayerInLevel(); c.setOwner(p.getUUID());
        var zombie = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, new BlockPos(4, 1, 2)); zombie.setNoAi(true);
        c.addTask(CompanionEntity.Task.give(null, 0)); c.stance = CompanionCombat.Stance.AGGRESSIVE;
        p.setLastHurtMob(zombie); var assist = new CompanionCombat.DefendOwnerGoal(c, true);
        h.assertTrue(assist.canUse(), "Owner attack target no longer accepted"); assist.start();
        h.assertTrue(c.getTarget() == zombie && c.taskCount() == 1, "Assist log changed target/orders");
        var entry = c.getLog(0, 1, false).getFirst();
        h.assertTrue(entry.kind() == EventKind.DANGER && entry.textComponent().getContents() instanceof TranslatableContents tr
                && tr.getArgs()[0] instanceof Component name && name.getContents() instanceof TranslatableContents nativeName
                && nativeName.getKey().equals("entity.minecraft.zombie"), "Combat event/name flattened");
        translated(h, entry.textComponent(), "Ich helfe dir gegen", "I am helping you against");
        c.setTarget(null); c.stance = CompanionCombat.Stance.DEFENSIVE; p.setLastHurtByMob(zombie);
        var defend = new CompanionCombat.DefendOwnerGoal(c, false);
        h.assertTrue(defend.canUse(), "Owner defence target no longer accepted"); defend.start();
        h.assertTrue(c.getTarget() == zombie && c.taskCount() == 1, "Defence log changed target/orders");
        translated(h, c.getLog(0, 1, false).getFirst().textComponent(), "Ich verteidige dich gegen", "I am defending you against");
        int size = c.getLog(0, 100, false).size(); defend.start();
        h.assertValueEqual(c.getLog(0, 100, false).size(), size, "Already selected target logged twice"); h.succeed();
    }

    @GameTest
    public void realMovementTimeoutKeepsPositionAndTranslatedBlockDiagnostics(GameTestHelper h) throws Exception {
        var c = companion(h); var level = h.getLevel(); var start = c.blockPosition();
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) h.setBlock(x, 0, z, Blocks.STONE);
        level.setBlock(start.east(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(start.east().above(), Blocks.AIR.defaultBlockState(), 3);
        var movement = new Movement(Move.TRAVERSE_E, start, start.east(), 5); var context = new NavContext(c);
        var status = Movement.Status.RUNNING; var before = c.position();
        for (int tick = 0; tick < 70 && status == Movement.Status.RUNNING; tick++) {
            c.setOnGround(true); status = movement.tick(c, context);
        }
        h.assertTrue(status == Movement.Status.FAILED && c.position().equals(before) && c.usedSlots() == 0 && !c.hasTasks(),
                "Timeout diagnostics changed movement/orders/inventory");
        translated(h, movement.failureComponent(), "Zeitlimit", "Time limit");
        var log = c.getLog(0, 1, false).getFirst().textComponent();
        translated(h, log, "Zeitlimit-Diagnose", "Timeout diagnostic");
        translated(h, log, "minecraft:air", "minecraft:air"); h.succeed();
    }

    @GameTest
    public void actualMovementFailureAndNavigationEventRemainTranslated(GameTestHelper h) throws Exception {
        var c = companion(h); var p = admin(h, c); var pos = c.blockPosition();
        h.getLevel().setBlock(pos.above(2), Blocks.AIR.defaultBlockState(), 3);
        h.getLevel().setBlock(pos.above(3), Blocks.AIR.defaultBlockState(), 3);
        c.setOnGround(true);
        var movement = new Movement(Move.PILLAR, pos, pos.above(), 100);
        h.assertTrue(movement.tick(c, new NavContext(c)) == Movement.Status.FAILED, "Empty-inventory pillar should fail");
        translated(h, movement.failureComponent(), "kann nicht hochbauen", "cannot pillar up");
        c.nav.recoveryEvent(CompanionTexts.tr("nav.event.failed", movement.failureComponent()));
        translated(h, debug(h, c, p, BotDebug.LEVEL_FULL).detail().navEventText(), "Weg gescheitert", "Path failed");
        movement.failReason = "My literal failure %s"; c.nav.lastEvent = "My literal event %s";
        h.assertTrue(movement.failureComponent().getString().equals("My literal failure %s")
                && c.nav.eventComponent().getString().equals("My literal event %s"), "Legacy diagnostic updates hidden");
        h.assertTrue(!c.hasTasks() && !c.isPillarActive(), "Failure text changed task/placement state");
        h.succeed();
    }
}
