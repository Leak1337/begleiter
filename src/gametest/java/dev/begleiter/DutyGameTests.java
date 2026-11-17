package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

/** Seite "Aufgaben": Daueraufgaben Felder, Tiere und Handel – Start, Ort, Arbeit im Leerlauf, Speichern. */
public class DutyGameTests {
    // Suchradien und Tiere bleiben innerhalb der eigenen, räumlich getrennten Testfläche.
    private static BlockPos rel(int x, int y, int z) { return new BlockPos(x + 18, y, z + 18); }

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 9; x++)
            for (int z = 0; z < 9; z++) h.setBlock(rel(x, 0, z), Blocks.GRASS_BLOCK);
    }

    private static CompanionEntity companion(GameTestHelper h) {
        floor(h);
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, rel(1, 1, 1));
        c.setPersistenceRequired();
        c.stay();
        return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c, BlockPos rel) {
        @SuppressWarnings("removal") ServerPlayer p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID());
        BlockPos at = h.absolutePos(rel);
        p.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        return p;
    }

    /** Reifer Weizen auf 3×3 Ackerland ab (3,0,3). */
    private static void ripeField(GameTestHelper h) {
        for (int x = 3; x < 6; x++)
            for (int z = 3; z < 6; z++) {
                h.setBlock(rel(x, 0, z), Blocks.FARMLAND);
                h.setBlock(rel(x, 1, z), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
            }
    }

    @GameTest(structure = "begleiter-gametest:request_isolation", maxTicks = 600)
    public void felderPflegenErntetVonSelbstUndSaetNach(GameTestHelper h) {
        CompanionEntity c = companion(h);
        ripeField(h);
        ServerPlayer p = owner(h, c, rel(4, 1, 2));
        var reply = CompanionActions.get(37).handler().executeComponent(c, p, false);
        h.assertTrue(c.duty() == CompanionDuties.Duty.FARM, "Daueraufgabe nicht gestartet: " + reply.getString());
        h.assertTrue(!c.isStaying() && !c.isAutonomous(), "Modus nicht umgestellt");
        h.succeedWhen(() -> {
            h.assertValueEqual(CompanionJobs.countMature(h.getLevel(), h.absolutePos(rel(4, 1, 4)), 3), 0, "noch reifer Weizen");
            h.assertTrue(h.getBlockState(rel(4, 1, 4)).is(Blocks.WHEAT), "nicht nachgepflanzt");
            h.assertTrue(CompanionCraft.count(c, "wheat") > 0, "keine Ernte im Inventar");
        });
    }

    @GameTest(structure = "begleiter-gametest:request_isolation")
    public void felderPflegenOhneAckerlandStartetNicht(GameTestHelper h) {
        CompanionEntity c = companion(h);
        ServerPlayer p = owner(h, c, rel(4, 1, 4));
        CompanionActions.get(37).handler().executeComponent(c, p, false);
        h.assertTrue(c.duty() == null && c.isStaying(), "ohne Feld darf sich der Modus nicht ändern");
        h.succeed();
    }

    @GameTest(structure = "begleiter-gametest:request_isolation", maxTicks = 600)
    public void tiereVersorgenFuettertEinPaar(GameTestHelper h) {
        CompanionEntity c = companion(h);
        var cows = new java.util.ArrayList<net.minecraft.world.entity.animal.Animal>();
        for (int i = 0; i < 2; i++) {
            var cow = h.spawn(EntityTypes.COW, rel(5 + i, 1, 5));
            cow.setPersistenceRequired();
            cows.add(cow);
        }
        c.giveToInventory(new ItemStack(Items.WHEAT, 4));
        ServerPlayer p = owner(h, c, rel(4, 1, 4));
        CompanionActions.get(38).handler().executeComponent(c, p, false);
        h.assertTrue(c.duty() == CompanionDuties.Duty.ANIMALS, "Daueraufgabe Tiere nicht gestartet");
        h.succeedWhen(() -> {
            for (var cow : cows) h.assertTrue(cow.isInLove() || cow.getAge() > 0, "Kuh nicht gefüttert");
        });
    }

    @GameTest(structure = "begleiter-gametest:request_isolation")
    public void handelOhneDorfStartetNicht(GameTestHelper h) {
        CompanionEntity c = companion(h);
        ServerPlayer p = owner(h, c, rel(4, 1, 4));
        CompanionActions.get(39).handler().executeComponent(c, p, false);
        h.assertTrue(c.duty() == null && c.isStaying(), "ohne Dorf darf sich der Modus nicht ändern");
        h.succeed();
    }

    /** Daueraufgabe übersteht Speichern/Laden; Folgen/Warten/Frei beenden sie. */
    @GameTest(structure = "begleiter-gametest:request_isolation")
    public void daueraufgabeWirdGespeichertUndVonModiBeendet(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos at = h.absolutePos(rel(4, 1, 4));
        c.startDuty(CompanionDuties.Duty.TRADE, at);
        var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,
                h.getLevel().registryAccess());
        c.saveWithoutId(out);
        CompanionEntity copy = CompanionMod.COMPANION.create(h.getLevel(), EntitySpawnReason.LOAD);
        h.assertTrue(copy != null, "Kopie nicht erzeugt");
        copy.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,
                h.getLevel().registryAccess(), out.buildResult()));
        h.assertTrue(copy.duty() == CompanionDuties.Duty.TRADE && at.equals(copy.dutyPos()), "Daueraufgabe nicht geladen");
        h.assertTrue(!copy.isAutonomous() && !copy.isStaying(), "geladener Modus falsch");
        h.assertValueEqual(CompanionSnapshot.capture(copy, 0, CompanionMenu.Page.DUTIES).mode(), 6, "Moduswert im Menü");
        copy.follow();
        h.assertTrue(copy.duty() == null, "Folgen beendet die Daueraufgabe nicht");
        c.freeRoam();
        h.assertTrue(c.duty() == null && c.dutyPos() == null, "Frei beendet die Daueraufgabe nicht");
        h.succeed();
    }
}
