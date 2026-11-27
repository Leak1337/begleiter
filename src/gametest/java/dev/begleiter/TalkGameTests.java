package dev.begleiter;

import dev.begleiter.config.BegleiterConfig.Talk;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Freiwillige Planmeldungen je nach Gesprächigkeit; Antworten und Probleme bleiben unberührt. */
public class TalkGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.setNoAi(true);
        c.stay();
        return c;
    }

    /** Zwei Minuten lang in jedem Tick eine neue Planmeldung anbieten; zählt, wie viele in den Chat dürften. */
    private static void twoMinutes(GameTestHelper h, CompanionEntity c, Talk level, int expected) {
        c.talkOverride = level;
        int[] tick = {0}, allowed = {0};
        h.onEachTick(() -> {
            if (tick[0] < 2400 && c.mayChatter(Component.literal("plan " + tick[0]))) allowed[0]++;
            tick[0]++;
        });
        h.runAfterDelay(2405, () -> {
            h.assertValueEqual(allowed[0], expected, level + " messages in two minutes");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 2500)
    public void normalAllowsOnePlanMessagePerMinute(GameTestHelper h) { twoMinutes(h, companion(h), Talk.NORMAL, 2); }

    @GameTest(maxTicks = 2500)
    public void quietAllowsNoPlanMessages(GameTestHelper h) { twoMinutes(h, companion(h), Talk.QUIET, 0); }

    @GameTest(maxTicks = 2500)
    public void chattyKeepsEveryPlanMessage(GameTestHelper h) { twoMinutes(h, companion(h), Talk.CHATTY, 2400); }

    @GameTest(maxTicks = 1400)
    public void normalDoesNotRepeatTheSameMessage(GameTestHelper h) {
        var c = companion(h);
        c.talkOverride = Talk.NORMAL;
        h.assertTrue(c.mayChatter(Component.literal("Ich hole Holz")), "First message suppressed");
        h.runAfterDelay(CompanionTalk.CHATTER_INTERVAL + 10, () -> {
            h.assertTrue(!c.mayChatter(Component.literal("Ich hole Holz")), "Same message repeated after a minute");
            h.assertTrue(c.mayChatter(Component.literal("Ich baue")), "New message after a minute suppressed");
            h.succeed();
        });
    }

    @GameTest
    public void progressReportsFollowOnlyTheQuietSetting(GameTestHelper h) {
        var c = companion(h);
        c.talkOverride = Talk.QUIET;
        h.assertTrue(!c.mayReportProgress(), "QUIET must not report build progress");
        c.talkOverride = Talk.NORMAL;
        // Ein frisch verbrauchter Minutenabstand für Planmeldungen hält Fortschritt nicht zurück
        c.mayChatter(Component.literal("plan"));
        h.assertTrue(c.mayReportProgress(), "NORMAL must report build progress even right after a plan message");
        c.talkOverride = Talk.CHATTY;
        h.assertTrue(c.mayReportProgress(), "CHATTY must report build progress");
        h.succeed();
    }

    @GameTest
    public void chatChangesAndSavesTheCompanionsTalkativeness(GameTestHelper h) throws Exception {
        var c = companion(h);
        var p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        c.setOwner(p.getUUID());
        c.talkOverride = null;
        var less = CompanionChatActions.interpretReply(p, c, "sei leiser", "de_de");
        h.assertTrue(less != null && less.getContents() instanceof TranslatableContents t && t.getKey().endsWith("reply.talk"),
                "'sei leiser' not understood");
        h.assertTrue(c.talk() == Talk.QUIET, "NORMAL -> QUIET expected, got " + c.talk());
        CompanionChatActions.interpretReply(p, c, "talk more", "en_us");
        CompanionChatActions.interpretReply(p, c, "sprich mehr", "de_de");
        h.assertTrue(c.talk() == Talk.CHATTY, "Two steps up should reach CHATTY, got " + c.talk());
        var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        c.saveWithoutId(out);
        var copy = CompanionMod.COMPANION.create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
        copy.load(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertTrue(copy.talkOverride == Talk.CHATTY, "Talkativeness lost after save/load");
        h.succeed();
    }
}
