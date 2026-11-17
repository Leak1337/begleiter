package dev.begleiter;

import dev.begleiter.nav.Move;
import dev.begleiter.nav.Movement;
import dev.begleiter.nav.NavContext;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Echte Welt/Beute/Werkzeuge; nur die einzelne Weltänderung wird gezielt gestört. */
public class MiningActionGameTests {
    private enum Fault { NONE, DENIED, UNCHANGED, EXCEPTION }

    private static final class TestCompanion extends CompanionEntity {
        private final Fault fault;
        private int attempts;

        TestCompanion(ServerLevel level, Fault fault) {
            super(CompanionMod.COMPANION, level);
            this.fault = fault;
        }

        @Override protected boolean destroyMiningBlock(ServerLevel level, BlockPos pos) {
            attempts++;
            return switch (fault) {
                case NONE -> super.destroyMiningBlock(level, pos);
                case DENIED -> false;
                case UNCHANGED -> true;
                case EXCEPTION -> throw new IllegalStateException("simulierter Weltfehler");
            };
        }
    }

    private static TestCompanion companion(GameTestHelper h, Fault fault) {
        var c = new TestCompanion(h.getLevel(), fault);
        BlockPos pos = h.absolutePos(new BlockPos(2, 1, 2));
        c.snapTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        c.stay();
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        return c; // Nicht gespawnt: keine parallelen KI-/Pickup-Ticks im synchronen Test.
    }

    private static BlockPos stone(GameTestHelper h) {
        h.setBlock(3, 0, 2, Blocks.STONE);
        h.setBlock(3, 1, 2, Blocks.STONE);
        return h.absolutePos(new BlockPos(3, 1, 2));
    }

    private static void unchanged(GameTestHelper h, TestCompanion c, BlockPos p, BlockState state) {
        h.assertTrue(h.getLevel().getBlockState(p).equals(state), "Block verändert");
        h.assertTrue(c.getInventory().isEmpty(), "Künstliche Beute im Inventar");
        h.assertValueEqual(c.getMainHandItem().getDamageValue(), 0, "Werkzeug beschädigt");
        h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(p)).isEmpty(), "Künstliche Weltbeute");
    }

    @GameTest
    public void erfolgreicherAbbauBuchtGenauEinmal(GameTestHelper h) {
        var c = companion(h, Fault.NONE);
        BlockPos p = stone(h);
        BlockState state = h.getLevel().getBlockState(p);
        h.assertTrue(c.mineBlock(h.getLevel(), p, state), "Abbau nicht bestätigt");
        h.assertTrue(h.getLevel().getBlockState(p).isAir(), "Stein nicht entfernt");
        h.assertValueEqual(CompanionCraft.count(c, "cobblestone"), 1, "Falsche Beute");
        h.assertValueEqual(c.getMainHandItem().getDamageValue(), 1, "Falscher Verschleiß");
        h.assertTrue(!c.mineBlock(h.getLevel(), p, state), "Doppelter Abbau bestätigt");
        h.assertValueEqual(CompanionCraft.count(c, "cobblestone"), 1, "Doppelte Beute");
        h.assertValueEqual(c.getMainHandItem().getDamageValue(), 1, "Doppelter Verschleiß");
        h.assertValueEqual(c.attempts, 1, "Weltaktion erneut ausgeführt");
        h.succeed();
    }

    @GameTest
    public void verweigerterAbbauErzeugtAuchBeiWiederholungKeineBeute(GameTestHelper h) {
        var c = companion(h, Fault.DENIED);
        BlockPos p = stone(h);
        BlockState state = h.getLevel().getBlockState(p);
        for (int i = 0; i < 3; i++) h.assertTrue(!c.mineBlock(h.getLevel(), p, state), "Ablehnung ignoriert");
        unchanged(h, c, p, state);
        h.assertValueEqual(c.attempts, 3, "Ablehnung nicht tatsächlich ausgeführt");
        h.succeed();
    }

    @GameTest
    public void wahrerRueckgabewertOhneWeltaenderungIstKeinErfolg(GameTestHelper h) {
        var c = companion(h, Fault.UNCHANGED);
        BlockPos p = stone(h);
        BlockState state = h.getLevel().getBlockState(p);
        h.assertTrue(!c.mineBlock(h.getLevel(), p, state), "Unveränderter Block als Erfolg verbucht");
        unchanged(h, c, p, state);
        h.succeed();
    }

    @GameTest
    public void veralteterBlockzustandWirdNichtAbgebaut(GameTestHelper h) {
        var c = companion(h, Fault.NONE);
        BlockPos p = stone(h);
        h.setBlock(3, 1, 2, Blocks.DIAMOND_BLOCK);
        h.assertTrue(!c.mineBlock(h.getLevel(), p, Blocks.STONE.defaultBlockState()), "Fremder Block abgebaut");
        unchanged(h, c, p, Blocks.DIAMOND_BLOCK.defaultBlockState());
        h.assertValueEqual(c.attempts, 0, "Veraltete Weltaktion ausgeführt");
        h.succeed();
    }

    @GameTest
    public void veraenderteBlockEigenschaftenWerdenErneutGeprueft(GameTestHelper h) {
        var c = companion(h, Fault.NONE);
        h.setBlock(3, 0, 2, Blocks.FARMLAND);
        BlockState young = Blocks.WHEAT.defaultBlockState();
        h.setBlock(3, 1, 2, young);
        BlockPos p = h.absolutePos(new BlockPos(3, 1, 2));
        h.assertTrue(!c.mineBlock(h.getLevel(), p, young.setValue(CropBlock.AGE, 7)), "Alter Pflanzenzustand benutzt");
        unchanged(h, c, p, young);
        h.succeed();
    }

    @GameTest
    public void luftUndUnzerstoerbaresGebenKeineBeute(GameTestHelper h) {
        var c = companion(h, Fault.NONE);
        BlockPos p = stone(h);
        h.setBlock(3, 1, 2, Blocks.AIR);
        h.assertTrue(!c.mineBlock(h.getLevel(), p, Blocks.AIR.defaultBlockState()), "Luft abgebaut");
        h.setBlock(3, 1, 2, Blocks.BEDROCK);
        h.assertTrue(!c.mineBlock(h.getLevel(), p, Blocks.BEDROCK.defaultBlockState()), "Grundgestein abgebaut");
        unchanged(h, c, p, Blocks.BEDROCK.defaultBlockState());
        h.assertValueEqual(c.attempts, 0, "Ungültige Weltaktion ausgeführt");
        h.succeed();
    }

    @GameTest
    public void ungeladenesZielLaedtKeinenChunk(GameTestHelper h) {
        var c = companion(h, Fault.NONE);
        BlockPos p = new BlockPos(29_000_000, 64, 29_000_000);
        var chunks = h.getLevel().getChunkSource();
        h.assertTrue(chunks.getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null, "Fixture bereits geladen");
        h.assertTrue(!c.mineBlock(h.getLevel(), p, Blocks.STONE.defaultBlockState()), "Unbekanntes Ziel abgebaut");
        h.assertTrue(chunks.getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null, "Chunk nachgeladen");
        h.assertTrue(c.getInventory().isEmpty(), "Beute aus ungeladenem Ziel");
        h.assertValueEqual(c.attempts, 0, "Weltaktion in ungeladenem Chunk");
        h.succeed();
    }

    @GameTest
    public void falscheDimensionWirdAbgelehnt(GameTestHelper h) {
        var c = companion(h, Fault.NONE);
        ServerLevel other = h.getLevel().getServer().getLevel(Level.NETHER);
        h.assertTrue(other != null, "Nether fehlt");
        h.assertTrue(!c.mineBlock(other, stone(h), Blocks.STONE.defaultBlockState()), "Andere Dimension akzeptiert");
        h.assertValueEqual(c.attempts, 0, "Fremde Weltaktion ausgeführt");
        h.succeed();
    }

    private static void rejectedTask(GameTestHelper h, Fault fault, boolean autonomous) {
        var c = companion(h, fault);
        BlockPos p = stone(h);
        var first = CompanionEntity.Task.breakAt(p, Blocks.STONE);
        c.addTask(first);
        c.addTask(CompanionEntity.Task.craft("stick", 1));
        if (autonomous) c.goal = CompanionNeeds.Need.ORDER;
        var executor = new CompanionTaskGoal(c);
        executor.start();
        for (int i = 0; i < 100 && executor.canUse(); i++) executor.tick();
        unchanged(h, c, p, Blocks.STONE.defaultBlockState());
        h.assertValueEqual(c.attempts, 1, "Verweigerte Aktion unbeschränkt wiederholt");
        if (autonomous) {
            h.assertTrue(!c.hasTasks(), "Abhängige autonome Schritte nicht verworfen");
            h.assertTrue(c.plannerCooldownUntil > c.tickCount, "Kein Planungs-Cooldown");
        } else {
            h.assertTrue(c.isPaused(), "Besitzerauftrag nicht pausiert");
            h.assertTrue(c.currentTask() == first && c.taskCount() == 2, "Fehler als Taskfortschritt gebucht");
        }
        h.succeed();
    }

    @GameTest public void verweigerterAuftragPausiertOhneFortschritt(GameTestHelper h) { rejectedTask(h, Fault.DENIED, false); }
    @GameTest public void weltfehlerPausiertAuftragOhneFortschritt(GameTestHelper h) { rejectedTask(h, Fault.EXCEPTION, false); }
    @GameTest public void verweigerterAutonomerAbbauStopptFolgeschritte(GameTestHelper h) { rejectedTask(h, Fault.DENIED, true); }

    @GameTest
    public void verweigertesHindernisMachtNavigationNichtErfolgreich(GameTestHelper h) {
        var c = companion(h, Fault.DENIED);
        BlockPos p = stone(h);
        var step = new Movement(Move.TRAVERSE_E, c.blockPosition(), p, 100);
        var context = new NavContext(c);
        Movement.Status result = Movement.Status.RUNNING;
        for (int i = 0; i < 100 && result == Movement.Status.RUNNING; i++) result = step.tick(c, context);
        h.assertTrue(result == Movement.Status.FAILED, "Hindernis nicht als fehlgeschlagen gemeldet");
        unchanged(h, c, p, Blocks.STONE.defaultBlockState());
        h.assertValueEqual(c.attempts, 1, "Hindernis endlos erneut abgebaut");
        h.succeed();
    }

    @GameTest
    public void navigationBautErzNachWerkzeugverlustNichtAb(GameTestHelper h) {
        var c = companion(h, Fault.NONE);
        h.setBlock(3, 0, 2, Blocks.STONE);
        h.setBlock(3, 1, 2, Blocks.DIAMOND_ORE);
        BlockPos p = h.absolutePos(new BlockPos(3, 1, 2));
        var step = new Movement(Move.TRAVERSE_E, c.blockPosition(), p, 100);
        var context = new NavContext(c); // geplant mit Eisenspitzhacke
        Movement.Status result = Movement.Status.RUNNING;
        for (int i = 0; i < 3 && result == Movement.Status.RUNNING; i++) result = step.tick(c, context);
        h.assertTrue(result == Movement.Status.RUNNING, "Abbau nicht begonnen: " + result);
        // Während des Abbaus wird die Spitzhacke gegen eine zu schwache getauscht
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
        for (int i = 0; i < 100 && result == Movement.Status.RUNNING; i++) result = step.tick(c, context);
        h.assertTrue(result == Movement.Status.FAILED, "Abbau ohne passendes Werkzeug nicht abgebrochen");
        h.assertTrue(h.getLevel().getBlockState(p).is(Blocks.DIAMOND_ORE), "Erz ohne Ertrag zerstört");
        h.assertValueEqual(c.attempts, 0, "Weltaktion ohne passendes Werkzeug ausgeführt");
        h.assertValueEqual(CompanionCraft.count(c, "diamond"), 0, "Künstliche Beute");
        h.succeed();
    }

    @GameTest
    public void verweigerteErnteZaehltNichtUndVerbrauchtKeineSaat(GameTestHelper h) {
        var c = companion(h, Fault.DENIED);
        h.setBlock(3, 0, 2, Blocks.FARMLAND);
        BlockState ripe = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
        h.setBlock(3, 1, 2, ripe);
        c.giveToInventory(new ItemStack(Items.WHEAT_SEEDS, 4));
        BlockPos p = h.absolutePos(new BlockPos(3, 1, 2));
        h.assertValueEqual(CompanionJobs.workFarmSpot(h.getLevel(), c, p), 0, "Fehlerhafte Ernte gezählt");
        h.assertTrue(h.getLevel().getBlockState(p).equals(ripe), "Pflanze ersetzt");
        h.assertValueEqual(CompanionCraft.count(c, "wheat_seeds"), 4, "Saat verbraucht/erzeugt");
        h.assertValueEqual(CompanionCraft.count(c, "wheat"), 0, "Künstlicher Ertrag");
        h.succeed();
    }

    @GameTest
    public void erfolgreicheErnteWirdGenauEinmalGebucht(GameTestHelper h) {
        var c = companion(h, Fault.NONE);
        h.setBlock(3, 0, 2, Blocks.FARMLAND);
        h.setBlock(3, 1, 2, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        c.giveToInventory(new ItemStack(Items.WHEAT_SEEDS, 4));
        BlockPos p = h.absolutePos(new BlockPos(3, 1, 2));
        h.assertValueEqual(CompanionJobs.workFarmSpot(h.getLevel(), c, p), 1, "Ernte nicht bestätigt");
        h.assertValueEqual(CompanionCraft.count(c, "wheat"), 1, "Falscher Ertrag");
        h.assertTrue(h.getLevel().getBlockState(p).equals(Blocks.WHEAT.defaultBlockState()), "Nicht nachgepflanzt");
        h.assertValueEqual(CompanionJobs.workFarmSpot(h.getLevel(), c, p), 0, "Junge Pflanze erneut geerntet");
        h.assertValueEqual(CompanionCraft.count(c, "wheat"), 1, "Doppelter Ertrag");
        h.succeed();
    }
}
