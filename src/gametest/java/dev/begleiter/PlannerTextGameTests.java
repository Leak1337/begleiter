package dev.begleiter;

import java.util.List;
import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.debug.BotDebug;
import dev.begleiter.debug.BotDebugServer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Übersetzte Planer-Ausgaben müssen Entscheidungen, Schleifenerkennung und die echten Netzwerkkomponenten erhalten. */
public class PlannerTextGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay(); c.setNoAi(true); c.food.set(20); return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c, boolean op) {
        var p = h.makeMockServerPlayerInLevel(); c.setOwner(p.getUUID());
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        if (op) h.getLevel().getServer().getPlayerList().op(p.nameAndId(),
                java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.of(false));
        return p;
    }

    private static Component wire(GameTestHelper h, Component text) {
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            ComponentSerialization.STREAM_CODEC.encode(buffer, text);
            var back = ComponentSerialization.STREAM_CODEC.decode(buffer);
            h.assertValueEqual(buffer.readableBytes(), 0, "Unread planner component bytes"); return back;
        } finally { buffer.release(); }
    }

    private static void translated(GameTestHelper h, Component text, String de, String en) throws Exception {
        var back = wire(h, text);
        h.assertTrue(back.getContents() instanceof TranslatableContents, "Planner text flattened");
        ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(back.getString().contains(de), back.getString()));
        ServerTextGameTests.inLanguage("en_us", () -> {
            h.assertTrue(back.getString().contains(en), back.getString());
            h.assertTrue(!back.getString().contains("server.begleiter."), "Unresolved planner key");
        });
    }

    @GameTest
    public void reasonComponentsKeepUrgencyAndSelectedTasks(GameTestHelper h) throws Exception {
        var c = companion(h); c.food.set(4);
        var hunger = CompanionNeeds.score(c, Need.HUNGER);
        h.assertTrue(hunger != null && hunger.need() == Need.HUNGER && hunger.value() == 87, "Hunger urgency changed");
        translated(h, hunger.reasonText(), "Hunger 4/20, nur 0", "hunger 4/20, carrying only 0");
        var gear = CompanionNeeds.score(c, Need.GEAR);
        h.assertTrue(gear != null && gear.value() == 96, "Missing-tool urgency changed");
        translated(h, gear.reasonText(), "keine Spitzhacke", "no pickaxe");
        CompanionNeeds.decide(c);
        h.assertTrue(c.goal == Need.GEAR && c.goalScore == 96, "Protected need changed after localization");
        h.assertTrue(c.peekTasks(5).equals(List.of(CompanionEntity.Task.gather("holz", 8),
                CompanionEntity.Task.craft("wooden_pickaxe", 1), CompanionEntity.Task.craft("wooden_axe", 1),
                CompanionEntity.Task.craft("wooden_sword", 1), CompanionEntity.Task.craft("wooden_shovel", 1))), "Equipment task chain changed");
        translated(h, c.goalReasonComponent(), "keine Spitzhacke", "no pickaxe");
        translated(h, c.stageComponent(), "sammle Holz", "gathering wood");
        h.succeed();
    }

    @GameTest
    public void nativeToolReasonsAndStagesReachBothSnapshots(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c, true);
        var tool = new ItemStack(Items.STONE_PICKAXE); tool.setDamageValue(tool.getMaxDamage() - 1);
        c.getInventory().setItem(0, tool);
        var score = CompanionNeeds.score(c, Need.GEAR);
        h.assertTrue(score != null && score.value() == 74, "Worn-tool priority changed");
        var reason = (TranslatableContents) score.reasonText().getContents();
        h.assertTrue(reason.getArgs()[0] instanceof Component item && item.getContents() instanceof TranslatableContents, "Native tool name flattened");
        h.assertTrue(CompanionNeeds.act(c, score), "Worn-tool replacement not planned");
        c.goal = score.need(); c.goalScore = score.value(); c.setGoalReason(score.reasonText());
        // Steine, Holz für die Stiele (keine Stöcke, Bretter oder Stämme im Inventar) und das Werkzeug selbst
        h.assertTrue(c.isStage("planner.stage.replace_worn") && c.taskCount() == 3, "Replacement stage/queue changed");
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        CompanionSnapshot panel;
        try {
            CompanionSnapshot.CODEC.encode(buffer, CompanionSnapshot.capture(c, 1, CompanionMenu.Page.STATUS));
            panel = CompanionSnapshot.CODEC.decode(buffer);
            h.assertValueEqual(buffer.readableBytes(), 0, "Unread panel bytes");
        } finally { buffer.release(); }
        var debugBuffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        BotDebug.Detail debug;
        try {
            BotDebug.Snapshot.CODEC.encode(debugBuffer, BotDebugServer.snapshot(p, c.getUUID(), BotDebug.LEVEL_FULL, false, "", List.of(c)));
            debug = BotDebug.Snapshot.CODEC.decode(debugBuffer).detail();
            h.assertValueEqual(debugBuffer.readableBytes(), 0, "Unread debug bytes");
        } finally { debugBuffer.release(); }
        translated(h, panel.stageText(), "Ersatz", "replacement");
        translated(h, panel.needsText(), "fast kaputt", "almost broken");
        translated(h, debug.planText(), "Ersatz", "replacement");
        translated(h, debug.reasonsText(), "fast kaputt", "almost broken");
        // Existing callers may still write literal fields after a structured planner result.
        c.lastStage = "Custom plan %s Ä"; c.goalReason = "Custom reason %s Ä";
        ServerTextGameTests.inLanguage("en_us", () -> {
            h.assertTrue(c.stageComponent().getString().equals("Custom plan %s Ä"), "Stale translated stage hid literal update");
            h.assertTrue(c.goalReasonComponent().getString().equals("Custom reason %s Ä"), "Stale translated reason hid literal update");
            h.assertTrue(!c.isStage("planner.stage.replace_worn"), "Literal update retained stale stage ID");
        });
        h.assertValueEqual(tool.getDamageValue(), tool.getMaxDamage() - 1, "Rendering repaired the actual tool");
        h.succeed();
    }

    @GameTest
    public void stageSuppressionAndStallDetectionDoNotDependOnLocale(GameTestHelper h) throws Exception {
        var c = companion(h); c.clearLog();
        for (int i = 0; i < 5; i++) {
            ServerTextGameTests.inLanguage(i % 2 == 0 ? "de_de" : "en_us", () ->
                    CompanionBrain.stage(c, CompanionTexts.tr("planner.stage.wood_tools")));
        }
        h.assertValueEqual(c.logSize(), 1, "Changing locale repeated the stage announcement");
        h.assertTrue(c.problemText.isEmpty(), "Stage stalled before five repeats");
        h.assertTrue(c.isStage("planner.stage.wood_tools"), "Translated stage ID lost");
        ServerTextGameTests.inLanguage("en_us", () -> CompanionBrain.stage(c, CompanionTexts.tr("planner.stage.wood_tools")));
        h.assertTrue(c.problemComponent().getContents() instanceof TranslatableContents problem
                && problem.getKey().equals("server.begleiter.log.problem.loop"), "Changing locale disabled repeated-plan detection");
        c.setStage(CompanionTexts.tr("planner.stage.ready_dragon", "1, 2, 3"));
        ServerTextGameTests.inLanguage("en_us", () -> h.assertTrue(c.isStage("planner.stage.ready_dragon"), "Dragon readiness depends on German prefix"));
        c.setStage(CompanionTexts.tr("planner.stage.dragon_defeated"));
        ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(c.isStage("planner.stage.dragon_defeated"), "Dragon completion depends on display language"));
        h.succeed();
    }

    @GameTest
    public void explainChatIsReadOnlyAndInterruptStillProtectsPlayerOrders(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c, false); c.food.set(4);
        c.goal = Need.SUPPLY; c.goalScore = 20; c.setGoalReason(CompanionTexts.tr("planner.reason.wood", 0));
        c.addTask(CompanionEntity.Task.gather("holz", 3)); var before = c.peekTasks(10);
        var reply = CompanionChatActions.interpretReply(p, c, "why are you doing that", "en_us");
        translated(h, reply, "Am dringendsten", "Most urgent");
        ServerTextGameTests.inLanguage("en_us", () -> h.assertTrue(reply.getString().contains("no pickaxe"), reply.getString()));
        h.assertTrue(c.peekTasks(10).equals(before) && c.goal == Need.SUPPLY && c.goalScore == 20, "Explanation changed the plan");
        h.assertTrue(CompanionNeeds.checkInterrupt(c) && c.goal == null && !c.hasTasks() && c.plannerCooldownUntil == 0,
                "Critical equipment need no longer interrupts autonomous supply work");
        c.addTask(CompanionEntity.Task.gather("holz", 3));
        h.assertTrue(!CompanionNeeds.checkInterrupt(c) && c.peekTasks(10).equals(before), "Critical need interrupted a player order");
        h.succeed();
    }
}
