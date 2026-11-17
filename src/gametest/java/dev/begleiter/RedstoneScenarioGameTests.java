package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Dieselben Szenarien wie in der sichtbaren Testmap, mit echtem Survival-Bau und echten Redstone-Ticks. */
public class RedstoneScenarioGameTests {
    private static final String ARENA = "begleiter-gametest:bench";

    @GameTest
    public void auswahlEnthaeltNeueSzenarienOhneDuplikate(GameTestHelper h) {
        var unknown = new java.util.ArrayList<String>();
        var extra = TestScenarios.pick("redstone_extra", unknown);
        h.assertValueEqual(extra.size(), 13, "Anzahl neuer Szenarien");
        h.assertTrue(unknown.isEmpty(), "neue Gruppe unbekannt");
        h.assertTrue(TestScenarios.pick("redstone", unknown).containsAll(extra), "redstone enthält neue Tests nicht");
        h.assertValueEqual(TestScenarios.pick("redstone_extra,rs_nor,redstone_extra", unknown).size(), extra.size(),
                "Mehrfachauswahl startet Szenarien doppelt");
        var ids = TestScenarios.all().stream().map(TestScenarios.Scenario::id).toList();
        h.assertValueEqual(new java.util.HashSet<>(ids).size(), ids.size(), "doppelte Szenario-IDs");
        h.succeed();
    }

    static void run(GameTestHelper h, String id) {
        var scenario = TestScenarios.byId(id);
        h.assertTrue(scenario != null, "Szenario fehlt: " + id);
        for (int x = 0; x < TestMap.CELL; x++)
            for (int z = 0; z < TestMap.CELL; z++) h.setBlock(x, 0, z, Blocks.SMOOTH_STONE);
        var x = new TestScenarios.Ctx();
        x.sl = h.getLevel();
        x.c = h.absolutePos(new BlockPos(0, 1, 0));
        x.bot = h.spawn(CompanionMod.COMPANION, new BlockPos(6, 1, 9));
        x.bot.setPersistenceRequired();
        x.bot.buildModeOverride = false;
        scenario.setup().run(x);
        h.onEachTick(() -> {
            x.ticks++;
            if (x.ticks == 2900) com.mojang.logging.LogUtils.getLogger().warn(
                    "[Redstone-Szenario] {} still running: {} phase={} mark={} task={} nav={} problem={} log={}",
                    id, TestScenarios.buildState(x), x.phase, x.mark, x.bot.currentTaskText(), x.bot.nav.debugText(),
                    x.bot.problemText, x.bot.getLog(0, 6, false).stream().map(e -> CompanionEntity.formatLogText(e).getString()).toList());
            h.assertTrue(x.bot.isAlive(), id + ": Begleiter gestorben");
            h.assertTrue(!x.bot.isPaused(), id + ": " + x.bot.problemText + " "
                    + (x.bot.build == null ? "" : "Ziel " + x.bot.build.index + "/" + x.bot.build.total() + " "
                    + x.bot.build.order.get(Math.min(x.bot.build.index, x.bot.build.total() - 1)))
                    + x.bot.getLog(0, 4, false).stream().map(e -> CompanionEntity.formatLogText(e).getString()).toList());
            if (x.sampler != null) x.sampler.accept(x);
            if (x.ticks % 10 != 0) return;
            var result = scenario.check().run(x);
            if (result != null) {
                h.assertTrue(result.ok(), id + ": " + result.why());
                com.mojang.logging.LogUtils.getLogger().info("[Redstone-Szenario] {}: {} ({} Ticks)", id, result.why(), x.ticks);
                h.succeed();
            }
        });
    }

    @GameTest(maxTicks = 100, structure = ARENA)
    public void bauOhneReichweitenPause(GameTestHelper h) { run(h, "rs_lampe"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void norWahrheitstabelle(GameTestHelper h) { run(h, "rs_nor"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void nandWahrheitstabelle(GameTestHelper h) { run(h, "rs_nand"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void komparatorVergleich(GameTestHelper h) { run(h, "rs_vergleich"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void komparatorSubtraktion(GameTestHelper h) { run(h, "rs_subtraktion"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void verstaerkerSpeichertBeideZustaende(GameTestHelper h) { run(h, "rs_speicher"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void dropperNurBeiEinschaltflanke(GameTestHelper h) { run(h, "rs_flanke"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void trichterSperrtOhneItemverlust(GameTestHelper h) { run(h, "rs_trichtersperre"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void werferSetztUndHoltWasser(GameTestHelper h) { run(h, "rs_wasserwerfer"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void eisenfalltuerSchaltet(GameTestHelper h) { run(h, "rs_falltuere"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void antriebsschienenLeitenStrom(GameTestHelper h) { run(h, "rs_schienen"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void schleimNimmtSeitenlastMit(GameTestHelper h) { run(h, "rs_schleim"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void obsidianBlockiertKolben(GameTestHelper h) { run(h, "rs_obsidian"); }

    @GameTest(maxTicks = 900, structure = ARENA)
    public void komposterSignalstaerken(GameTestHelper h) { run(h, "rs_komposter"); }
}
