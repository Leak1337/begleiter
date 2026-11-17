package dev.begleiter.nav;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMemory;
import dev.begleiter.CompanionMod;
import dev.begleiter.CompanionNeeds;
import dev.begleiter.CompanionPlanFeedback;
import dev.begleiter.ai.CompanionDecisionEngine;
import dev.begleiter.ai.DecisionOutcome;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Component proofs run in both modes; actual TAKE integration also runs with intelligence enabled. */
public class RouteSafetyTests {
    private static final String BENCH = "begleiter-gametest:bench";

    private static void arena(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 12; z++) {
            h.setBlock(x, 0, z, Blocks.STONE);
            if (x == 0 || x == 47 || z == 0 || z == 11)
                for (int y = 1; y < 14; y++) h.setBlock(x, y, z, Blocks.BARRIER);
        }
    }

    private static CompanionEntity companion(GameTestHelper h, BlockPos pos) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, pos);
        c.stay();
        c.setNoAi(true);
        c.setOnGround(true);
        c.getInventory().clearContent();
        c.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        c.memory.clear();
        return c;
    }

    private static RoundTripCheck audit(GameTestHelper h, CompanionEntity c, BlockPos source) {
        RoundTripCheck check = new RoundTripCheck(c, h.absolutePos(source), 1, 12000, 1_000_000_000L);
        for (int i = 0; i < 2000 && check.status() == RoundTripCheck.Status.CHECKING; i++) check.step();
        h.assertTrue(check.status() != RoundTripCheck.Status.CHECKING, "Pruefung endet nicht");
        return check;
    }

    @GameTest(structure = BENCH)
    public void flacherHinUndRueckwegWirdOhneWeltaenderungBestaetigt(GameTestHelper h) throws Exception {
        arena(h);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 6));
        var check = audit(h, c, new BlockPos(16, 1, 6));
        h.assertTrue(check.status() == RoundTripCheck.Status.SAFE, "Flache Runde: " + check.reason());
        dev.begleiter.HandlerTextGameTests.translated(h, check.reasonComponent(), "Hinweg und Rückweg geprüft", "outbound and return routes checked");
        h.assertTrue(check.outbound().complete && check.returning().complete, "Teilweg als sichere Runde gewertet");
        h.assertValueEqual(c.blockPosition(), h.absolutePos(new BlockPos(4, 1, 6)), "Pruefung bewegt Begleiter");
        c.nav.followChecked(check.outbound(), check.outboundContext(), check.outboundGoal());
        h.assertTrue(c.nav.executor() != null && c.nav.executor().path == check.outbound(), "Gepruefter Hinweg wird nicht wiederverwendet");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void erinnerteGefahrMittenImHinwegWirdUmlaufen(GameTestHelper h) {
        arena(h);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 6));
        c.memory.remember(CompanionMemory.Kind.DANGER, "test", h.getLevel(), h.absolutePos(new BlockPos(14, 1, 6)));
        var check = audit(h, c, new BlockPos(28, 1, 6));
        h.assertTrue(check.status() == RoundTripCheck.Status.SAFE, "Sicherer Umweg fehlt: " + check.reason());
        h.assertTrue(check.outbound().positions.stream().noneMatch(p -> c.memory.dangerous(h.getLevel(), p, 2)),
                "Nur Ziel statt kompletter Hinweg geprueft");
        h.assertTrue(check.returning().positions.stream().noneMatch(p -> c.memory.dangerous(h.getLevel(), p, 2)),
                "Rueckweg laeuft durch bekannte Gefahr");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void sichereQuelleReichtBeiGefaehrlichemRueckkehrbereichNichtAus(GameTestHelper h) {
        arena(h);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 6));
        BlockPos home = h.absolutePos(new BlockPos(40, 1, 6));
        c.setHome(home);
        c.memory.remember(CompanionMemory.Kind.DEATH, "test", h.getLevel(), home);
        var check = audit(h, c, new BlockPos(28, 1, 6));
        h.assertTrue(check.outbound() != null && check.outbound().complete, "Hinweg sollte erreichbar sein");
        h.assertTrue(check.status() != RoundTripCheck.Status.SAFE, "Sichere Quelle ohne sicheren Rueckweg freigegeben");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void einwegAbstiegOhneRueckstiegIstKeinSichererVorratsweg(GameTestHelper h) {
        arena(h);
        h.setBlock(4, 4, 6, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(4, 5, 6));
        var check = audit(h, c, new BlockPos(10, 1, 6));
        h.assertTrue(check.outbound() != null, "Hinweg ueber Abstieg fehlt");
        h.assertTrue(check.status() == RoundTripCheck.Status.UNPROVEN, "Einweg-Abstieg wurde als sichere Runde gewertet");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void geplanterTunnelWirdNurVirtuellFuerDenRueckwegGeoeffnet(GameTestHelper h) {
        arena(h);
        for (int z = 1; z < 11; z++) for (int y = 1; y <= 3; y++) h.setBlock(8, y, z, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 6));
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        var check = audit(h, c, new BlockPos(14, 1, 6));
        h.assertTrue(check.status() == RoundTripCheck.Status.SAFE, "Virtueller Tunnel fehlt: " + check.reason());
        h.assertTrue(check.returning().positions.stream().anyMatch(p -> p.getX() == h.absolutePos(new BlockPos(8, 1, 6)).getX()),
                "Rueckweg nutzt geplanten Durchgang nicht");
        for (int z = 1; z < 11; z++) for (int y = 1; y <= 3; y++)
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(8, y, z))).is(Blocks.STONE), "Pruefung baut echte Wand ab");
        h.assertTrue(!check.returnContext().allowBreak && !check.returnContext().allowPlace, "Rueckweg erfordert neue Arbeiten");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void geplanteBrueckeWirdNichtDoppeltAlsMaterialGerechnet(GameTestHelper h) {
        arena(h);
        for (int x = 1; x < 47; x++) for (int z = 1; z < 11; z++) h.setBlock(x, 8, z, Blocks.STONE);
        for (int x = 8; x <= 14; x++) for (int z = 1; z < 11; z++) {
            h.setBlock(x, 8, z, Blocks.AIR);
            h.setBlock(x, 0, z, Blocks.LAVA);
        }
        CompanionEntity c = companion(h, new BlockPos(4, 9, 6));
        c.giveToInventory(new ItemStack(Items.DIRT, 8));
        var check = audit(h, c, new BlockPos(20, 9, 6));
        h.assertTrue(check.status() == RoundTripCheck.Status.SAFE, "Virtuelle Bruecke fehlt: " + check.reason());
        h.assertTrue(check.returning().positions.stream().anyMatch(p -> h.getLevel().getBlockState(p.below()).isAir()),
                "Rueckweg nutzt die virtuelle Bruecke nicht");
        h.assertTrue(check.returnContext().throwaway < 8, "Hinwegmaterial wurde nicht reserviert");
        h.assertValueEqual(dev.begleiter.CompanionCraft.count(c, "dirt"), 8, "Pruefung verbraucht echte Erde");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void begrenzteOderTeilweiseSucheGiltNichtAlsSicher(GameTestHelper h) {
        arena(h);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 6));
        var check = new RoundTripCheck(c, h.absolutePos(new BlockPos(28, 1, 6)), 1, 1, 1_000_000_000L);
        for (int i = 0; i < 10 && check.status() == RoundTripCheck.Status.CHECKING; i++) check.step();
        h.assertTrue(check.status() == RoundTripCheck.Status.UNPROVEN, "Knotenlimit wird als sichere Route gewertet");
        var changing = new RoundTripCheck(c, h.absolutePos(new BlockPos(28, 1, 6)), 1, 12000, 1_000_000_000L);
        changing.step();
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        h.assertTrue(changing.step() == RoundTripCheck.Status.UNPROVEN, "Veraltete Ausruestungsannahme wird freigegeben");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void neueGefahrStopptEinenBereitsGesuchtenWegVorDerBewegung(GameTestHelper h) {
        arena(h);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 6));
        NavContext context = new NavContext(c, true);
        PathSearch search = new PathSearch(context, c.blockPosition(), new Goal.Block(h.absolutePos(new BlockPos(12, 1, 6))));
        search.step(1_000_000_000L);
        h.assertTrue(search.status() == PathSearch.Status.FOUND, "Testweg fehlt");
        Path path = search.path();
        c.memory.remember(CompanionMemory.Kind.DANGER, "neu", h.getLevel(), path.positions.get(1));
        PathExecutor executor = new PathExecutor(path, context);
        h.assertTrue(executor.tick(c) == PathExecutor.Status.FAILED, "Ausfuehrung ignoriert neue Gefahr");
        h.assertValueEqual(c.blockPosition(), path.start(), "Begleiter ist schon in Gefahr gelaufen");
        var goal = new Goal.Block(path.end());
        c.nav.followChecked(path, context, goal);
        h.assertTrue(c.nav.goTo(goal) == Navigator.Result.UNREACHABLE,
                "Navigator ersetzt abgelehnten geprueften Weg durch ungeprueften Weg");
        h.assertTrue(c.nav.executor() == null && !c.nav.searching(), "Ungepruefte Ersatzsuche wird trotzdem gestartet");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void gefaehrlichenStartVerlassenIstErlaubtSpielerwegeBleibenUnveraendert(GameTestHelper h) {
        arena(h);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 6));
        BlockPos p = c.blockPosition();
        c.memory.remember(CompanionMemory.Kind.DANGER, "test", h.getLevel(), p);
        NavContext guarded = new NavContext(c, true);
        h.assertTrue(guarded.safeSegment(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY(), p.getZ()), "Sicherer Ausstieg blockiert");
        h.assertTrue(!guarded.safeSegment(p.getX() + 1, p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ()), "Tiefer in Gefahr gelaufen");
        NavContext player = new NavContext(c); // goal == null, even in the enabled test process.
        h.assertTrue(player.safeSegment(p.getX() + 1, p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ()), "Spielerweg bekommt autonome Sperre");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void gesetztesLagerIstDasRueckkehrziel(GameTestHelper h) {
        arena(h);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 6));
        BlockPos home = h.absolutePos(new BlockPos(4, 1, 2));
        c.setHome(home);
        var check = audit(h, c, new BlockPos(20, 1, 6));
        h.assertTrue(check.status() == RoundTripCheck.Status.SAFE, "Lagerweg fehlt: " + check.reason());
        h.assertTrue(check.returning().end().distSqr(home) <= 4, "Rueckweg endet nicht am Lager");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void zertifikatLaeuftAbUndNeueZielgefahrWirdSofortErkannt(GameTestHelper h) {
        arena(h);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 6));
        BlockPos source = new BlockPos(20, 1, 6);
        var check = audit(h, c, source);
        h.assertTrue(check.status() == RoundTripCheck.Status.SAFE, "Ausgangsweg fehlt");
        c.setHealth(c.getHealth() - 1);
        h.assertTrue(check.step() == RoundTripCheck.Status.UNPROVEN, "Veralteter Lebenszustand wird freigegeben");
        check = audit(h, c, source);
        h.assertTrue(check.status() == RoundTripCheck.Status.SAFE, "Erneute Pruefung fehlt");
        c.tickCount += 100;
        h.assertTrue(check.expired(), "Veraltete Pruefung bleibt unbegrenzt gueltig");
        c.memory.remember(CompanionMemory.Kind.DANGER, "neu", h.getLevel(), h.absolutePos(source));
        h.assertTrue(check.step() == RoundTripCheck.Status.UNSAFE, "Neue Zielgefahr bleibt unberuecksichtigt");
        h.succeed();
    }

    @GameTest(maxTicks = 400, structure = BENCH)
    public void aktivierteKistenversorgungStartetKeinenEinwegAbstieg(GameTestHelper h) {
        arena(h);
        h.setBlock(4, 4, 6, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(4, 5, 6));
        c.setNoAi(false);
        BlockPos source = new BlockPos(8, 1, 6);
        h.setBlock(source, Blocks.CHEST);
        var box = c.containerAt(h.getLevel(), h.absolutePos(source));
        h.assertTrue(box != null, "Testkiste fehlt");
        box.setItem(0, new ItemStack(Items.COAL, 8));
        c.goal = CompanionNeeds.Need.SUPPLY;
        c.activeSupplyResource = "kohle";
        c.activeSupplyApproach = "chest";
        c.addTask(CompanionEntity.Task.take(h.absolutePos(source), "coal", 8));
        c.planFeedback.begin(c, CompanionNeeds.Need.SUPPLY, null);
        h.succeedWhen(() -> {
            var result = c.planFeedback.last();
            h.assertTrue(result != null, "Kistenplan nicht abgeschlossen");
            if (CompanionDecisionEngine.enabled()) {
                h.assertTrue(result.reason() == DecisionOutcome.Reason.ROUTE_UNPROVEN, "Unbestaetigter Rueckweg nicht erkannt");
                h.assertTrue(c.getBlockY() >= h.absolutePos(new BlockPos(4, 5, 6)).getY(), "Begleiter sprang vor Rueckwegpruefung");
                h.assertValueEqual(c.experiences.sources.size(), 0, "Suchunsicherheit als schlechte Quelle gelernt");
                h.assertValueEqual(c.experiences.penalty(CompanionNeeds.Need.SUPPLY, c.tickCount), 0, "Suchbudget bestraft ganze Ressource");
                h.assertValueEqual(c.experiences.learnedProfiles(), 0, "Suchbudget bestraft ganze Methode");
            } else {
                h.assertTrue(result.status() == CompanionPlanFeedback.Status.SUCCESS, "Standardmodus wurde veraendert");
            }
        });
    }

    @GameTest(maxTicks = 200, structure = BENCH)
    public void aktivierteKistenversorgungBeachtetNeueZielgefahrSchonInReichweite(GameTestHelper h) {
        arena(h);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 6));
        c.setNoAi(false);
        BlockPos source = new BlockPos(5, 1, 6);
        h.setBlock(source, Blocks.CHEST);
        var box = c.containerAt(h.getLevel(), h.absolutePos(source));
        h.assertTrue(box != null, "Testkiste fehlt");
        box.setItem(0, new ItemStack(Items.COAL, 8));
        c.memory.remember(CompanionMemory.Kind.DANGER, "neu", h.getLevel(), h.absolutePos(source));
        c.goal = CompanionNeeds.Need.SUPPLY;
        c.activeSupplyResource = "kohle";
        c.activeSupplyApproach = "chest";
        c.addTask(CompanionEntity.Task.take(h.absolutePos(source), "coal", 8));
        c.planFeedback.begin(c, CompanionNeeds.Need.SUPPLY, null);
        h.succeedWhen(() -> {
            var result = c.planFeedback.last();
            h.assertTrue(result != null, "Kistenplan nicht abgeschlossen");
            if (CompanionDecisionEngine.enabled()) {
                h.assertTrue(result.reason() == DecisionOutcome.Reason.ROUTE_UNSAFE, "Zielgefahr wird nicht erkannt");
                h.assertTrue(c.experiences.sources.blocked(dev.begleiter.CompanionSources.key(c, CompanionNeeds.Need.SUPPLY,
                        Items.COAL, h.absolutePos(source)), c.tickCount), "Unsicheres Ziel nicht voruebergehend ausgenommen");
                h.assertValueEqual(dev.begleiter.CompanionCraft.count(c, "coal"), 0, "Gefaehrliche Quelle trotzdem genommen");
            } else h.assertTrue(result.status() == CompanionPlanFeedback.Status.SUCCESS, "Standardmodus veraendert");
        });
    }
}
