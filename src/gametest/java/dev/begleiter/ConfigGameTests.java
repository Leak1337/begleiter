package dev.begleiter;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import dev.begleiter.chat.ChatAddress;
import dev.begleiter.config.BegleiterConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * Grenzen, kaputte Datei, abgeschalteter Röntgenblick, Sammelradius, Präfix/Ansprache und Berechtigung.
 * Die Konfiguration ist serverweit: jeder Test setzt sie nur innerhalb eines Ticks und stellt sie sofort zurück.
 */
public class ConfigGameTests {
    private static void with(BegleiterConfig temp, Runnable body) {
        var before = BegleiterConfig.get();
        BegleiterConfig.set(temp);
        try { body.run(); } finally { BegleiterConfig.set(before); }
    }

    @GameTest
    public void valuesAreClampedToSafeLimits(GameTestHelper h) {
        var c = new BegleiterConfig();
        c.xrayRadius = 1000; c.gatherRadius = 0; c.navBudgetMillis = 500; c.maxCompanionsPerPlayer = -2;
        c.prefix = "abc"; c.address = null; c.talk = null;
        var before = BegleiterConfig.get();
        BegleiterConfig.set(c);
        var now = BegleiterConfig.get();
        BegleiterConfig.set(before);
        h.assertValueEqual(now.xrayRadius, 64, "xray radius");
        h.assertValueEqual(now.gatherRadius, 8, "gather radius");
        h.assertValueEqual(now.navBudgetNanos(), 20_000_000L, "nav budget");
        h.assertValueEqual(now.maxCompanionsPerPlayer, 1, "companion limit");
        h.assertTrue(now.prefix.equals("@") && now.address == BegleiterConfig.Address.ALWAYS
                && now.talk == BegleiterConfig.Talk.NORMAL, "Invalid prefix/enums not replaced by defaults");
        h.succeed();
    }

    @GameTest
    public void brokenFileKeepsTheRunningConfigAndValidFileApplies(GameTestHelper h) throws Exception {
        var dir = Files.createTempDirectory("begleiter-config");
        var file = dir.resolve(BegleiterConfig.FILE_NAME);
        var before = BegleiterConfig.get();
        try {
            Files.writeString(file, "{ \"xray\": false, \"gatherRadius\": ");
            String error = BegleiterConfig.reload(file);
            h.assertTrue(error != null && BegleiterConfig.get() == before, "Broken file replaced the running config");
            Files.writeString(file, "{ \"gatherRadius\": \"far\" }");
            h.assertTrue(BegleiterConfig.reload(file) != null && BegleiterConfig.get() == before, "Wrong type accepted");
            Files.writeString(file, "{ \"xray\": false, \"gatherRadius\": 12, \"address\": \"NEVER\", \"talk\": \"LOUD\" }");
            h.assertTrue(BegleiterConfig.reload(file) == null, "Valid file rejected");
            var loaded = BegleiterConfig.get();
            h.assertTrue(!loaded.xray && loaded.gatherRadius == 12 && loaded.address == BegleiterConfig.Address.NEVER
                    && loaded.talk == BegleiterConfig.Talk.NORMAL, "Valid values not applied or unknown enum kept");
        } finally {
            BegleiterConfig.set(before);
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
        h.succeed();
    }

    @GameTest
    public void disabledXrayFindsNoHiddenOre(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) for (int y = 0; y < 4; y++) h.setBlock(x, y, z, Blocks.STONE);
        h.setBlock(4, 1, 4, Blocks.DIAMOND_ORE); // ringsum Stein: nur mit Röntgenblick zu spüren
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(1, 4, 1));
        c.setNoAi(true);
        var off = new BegleiterConfig(); off.xray = false;
        with(off, () -> { for (int i = 0; i < 60; i++) c.memory.xrayScan(c, h.getLevel()); });
        h.assertTrue(c.memory.counts().getOrDefault(CompanionMemory.Kind.ORE, 0) == 0, "Disabled x-ray still sensed ore");
        with(new BegleiterConfig(), () -> { for (int i = 0; i < 60; i++) c.memory.xrayScan(c, h.getLevel()); });
        h.assertTrue(c.memory.counts().getOrDefault(CompanionMemory.Kind.ORE, 0) > 0, "Enabled x-ray did not sense ore");
        h.succeed();
    }

    @GameTest
    public void prefixAndAddressModeDecideWhoIsSpokenTo(GameTestHelper h) {
        var a = new ChatAddress.Candidate(UUID.randomUUID(), "", 4);
        var far = new ChatAddress.Candidate(UUID.randomUUID(), "", 400);
        var candidates = List.of(far, a);
        h.assertTrue(ChatAddress.parse("hol holz", candidates, "@") == null, "Plain line addressed without mode");
        var bang = ChatAddress.parse("! hol holz", candidates, "!");
        h.assertTrue(bang != null && bang.target().equals(a.id()) && bang.text().contains("holz"), "Custom prefix ignored");
        h.assertTrue(ChatAddress.parse("@ hol holz", candidates, "!") == null, "Old prefix still active after change");
        var plain = ChatAddress.unaddressed("hol holz", candidates);
        h.assertTrue(plain != null && plain.target().equals(a.id()), "Unaddressed line not sent to the nearest companion");
        var p = h.makeMockServerPlayerInLevel();
        var always = new BegleiterConfig();
        var never = new BegleiterConfig(); never.address = BegleiterConfig.Address.NEVER;
        h.assertTrue(CompanionBrain.requiresAddress(p, always) && !CompanionBrain.requiresAddress(p, never), "ALWAYS/NEVER wrong");
        h.succeed();
    }

    @GameTest
    public void configReloadNeedsOperatorRights(GameTestHelper h) {
        var p = h.makeMockServerPlayerInLevel();
        var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
        var parse = dispatcher.parse("companion config reload", p.createCommandSourceStack());
        h.assertTrue(parse.getReader().canRead() || !parse.getExceptions().isEmpty(), "Ordinary player may reload the config");
        var console = h.getLevel().getServer().createCommandSourceStack();
        var asOp = dispatcher.parse("companion config reload", console);
        h.assertTrue(!asOp.getReader().canRead() && asOp.getExceptions().isEmpty(), "Console cannot reload the config");
        h.succeed();
    }

    @GameTest(structure = "begleiter-gametest:bench")
    public void gatherRadiusLimitsTheSearch(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 4; z++) h.setBlock(x, 0, z, Blocks.STONE);
        h.setBlock(13, 1, 2, Blocks.IRON_ORE); // 11 Blöcke entfernt
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.setPersistenceRequired();
        c.stay();
        h.runAfterDelay(10, () -> {
            var small = new BegleiterConfig(); small.gatherRadius = 8;
            var plans = new java.util.ArrayList<List<CompanionEntity.Task>>();
            with(small, () -> plans.add(c.buildGather(s -> s.is(Blocks.IRON_ORE), 1)));
            with(new BegleiterConfig(), () -> plans.add(c.buildGather(s -> s.is(Blocks.IRON_ORE), 1)));
            h.assertTrue(plans.get(0).isEmpty(), "Ore outside the configured gather radius planned");
            h.assertTrue(plans.get(1).size() == 1, "Ore inside the default radius not planned");
            h.succeed();
        });
    }
}
