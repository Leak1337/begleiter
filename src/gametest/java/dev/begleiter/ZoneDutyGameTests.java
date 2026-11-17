package dev.begleiter;

import dev.begleiter.CompanionDuties.Duty;
import dev.begleiter.CompanionZones.Kind;
import dev.begleiter.CompanionZones.Zone;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

/** Kartenfreigabe, räumliche Arbeitsgrenzen und tatsächlich ausgeführte Daueraufgaben. */
public class ZoneDutyGameTests {
    // Suchradien und Tiere bleiben innerhalb der eigenen, räumlich getrennten Testfläche.
    private static BlockPos rel(int x, int y, int z) { return new BlockPos(x + 18, y, z + 18); }

    private static CompanionEntity bot(GameTestHelper h) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            h.setBlock(rel(x, 0, z), Blocks.GRASS_BLOCK);
            for (int y = 1; y <= 4; y++) h.setBlock(rel(x, y, z), Blocks.AIR);
        }
        var c = h.spawn(CompanionMod.COMPANION, rel(1, 1, 1));
        c.setNoAi(true); c.stay(); c.setPersistenceRequired(); return c;
    }
    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        @SuppressWarnings("removal") var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID()); p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0); return p;
    }
    private static Zone zone(GameTestHelper h, Kind kind, int x1, int z1, int x2, int z2) {
        return CompanionZones.between(kind, CompanionMemory.dim(h.getLevel()), h.absolutePos(rel(x1, 1, z1)), h.absolutePos(rel(x2, 1, z2)));
    }
    private static CompanionZoneEditor.Edit edit(CompanionZoneEditor.Open open, Kind kind, BlockPos a, BlockPos b, int op) {
        return new CompanionZoneEditor.Edit(open.token(), kind, a, b, op);
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void rechteckNormalisiertUmgekehrteEckenUndGrenzen(GameTestHelper h) {
        var z = zone(h, Kind.BASE, 8, 8, 2, 2);
        h.assertTrue(z.valid() && z.contains(h.absolutePos(rel(2, 1, 2)))
                && z.contains(h.absolutePos(rel(8, 1, 8))) && !z.contains(h.absolutePos(rel(9, 1, 8))), "Grenzen falsch");
        h.assertTrue(!new Zone(Kind.BASE, "test", BlockPos.ZERO, new BlockPos(64, 7, 3)).valid(), "65 Blöcke erlaubt");
        h.assertTrue(!new Zone(Kind.BASE, "test", BlockPos.ZERO, new BlockPos(Integer.MAX_VALUE, 1, 1)).valid(), "Überlauf erlaubt");
        h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void karteSpeichertNurEinmalMitEigenerFreigabe(GameTestHelper h) {
        var c = bot(h); var p = owner(h, c); var open = CompanionZoneEditor.select(p, c);
        BlockPos a = h.absolutePos(rel(2, 1, 2)), b = h.absolutePos(rel(7, 1, 7));
        var request = edit(open, Kind.BASE, a, b, 0);
        h.assertTrue(CompanionZoneEditor.edit(p, request).finished(), "nicht gespeichert");
        h.assertTrue(c.zones.get(Kind.BASE, h.getLevel()).contains(b), "Zone fehlt");
        c.zones.remove(Kind.BASE); CompanionZoneEditor.edit(p, request);
        h.assertTrue(c.zones.all().isEmpty(), "Wiederholtes Paket hat Zone verändert"); h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void fremderSpielerUndEntzogeneBerechtigungWerdenAbgelehnt(GameTestHelper h) {
        var c = bot(h); var p = owner(h, c);
        @SuppressWarnings("removal") var other = h.makeMockServerPlayerInLevel();
        other.setUUID(UUID.randomUUID()); other.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        h.assertTrue(CompanionZoneEditor.select(other, c) == null, "Fremder darf Karte ändern");
        var open = CompanionZoneEditor.select(p, c); c.setOwner(other.getUUID());
        CompanionZoneEditor.edit(p, edit(open, Kind.BASE, c.blockPosition(), c.blockPosition(), 0));
        h.assertTrue(c.zones.all().isEmpty(), "Alte Freigabe nach Besitzerwechsel akzeptiert"); h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void abbrechenUndUngueltigeAuswahlErhaltenBisherigeZone(GameTestHelper h) {
        var c = bot(h); var p = owner(h, c); var old = zone(h, Kind.FIELDS, 2, 2, 3, 3); c.zones.put(old);
        var open = CompanionZoneEditor.select(p, c); var at = c.blockPosition();
        h.assertTrue(!CompanionZoneEditor.edit(p, edit(open, Kind.FIELDS, at, at.offset(64, 0, 0), 0)).finished(), "Zu große Auswahl beendet");
        h.assertTrue(c.zones.get(Kind.FIELDS, h.getLevel()).equals(old), "Fehler hat Zone ersetzt");
        CompanionZoneEditor.edit(p, edit(open, Kind.FIELDS, at, at, 2));
        h.assertTrue(c.zones.get(Kind.FIELDS, h.getLevel()).equals(old), "Abbruch hat Zone entfernt"); h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void loeschenStopptDaueraufgabeAberErhaeltAndereZonen(GameTestHelper h) {
        var c = bot(h); var p = owner(h, c);
        c.zones.put(zone(h, Kind.BASE, 1, 1, 8, 8)); c.zones.put(zone(h, Kind.FIELDS, 2, 2, 3, 3));
        CompanionDuties.startZoned(c, Duty.BASE); c.addTask(CompanionEntity.Task.collect(false));
        var open = CompanionZoneEditor.select(p, c);
        CompanionZoneEditor.edit(p, edit(open, Kind.FIELDS, BlockPos.ZERO, BlockPos.ZERO, 1));
        h.assertTrue(c.zones.get(Kind.BASE, h.getLevel()) != null && c.zones.get(Kind.FIELDS, h.getLevel()) == null, "Falsche Zone gelöscht");
        h.assertTrue(c.duty() == null && c.isStaying() && !c.hasTasks(), "Alte Arbeit läuft weiter"); h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void unbekannteChunksUndWeltgrenzeLadenNichtsNach(GameTestHelper h) {
        var sl = h.getLevel(); var far = new BlockPos(20_000_000, 80, 20_000_000);
        h.assertTrue(!sl.hasChunkAt(far), "Testchunk bereits geladen");
        h.assertTrue(!CompanionZoneEditor.loaded(sl, new Zone(Kind.ROAM, CompanionMemory.dim(sl), far, far.offset(3, 3, 3))), "Unbekannter Chunk akzeptiert");
        h.assertTrue(!sl.hasChunkAt(far), "Auswahl lädt Chunk nach");
        var low = h.absolutePos(BlockPos.ZERO).atY(sl.getMinY() - 1);
        h.assertTrue(!CompanionZoneEditor.loaded(sl, new Zone(Kind.BASE, CompanionMemory.dim(sl), low, low.above(7))), "Außerhalb Bauhöhe akzeptiert"); h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void zonenUndNeueDaueraufgabenUeberstehenSpeichern(GameTestHelper h) {
        var c = bot(h);
        for (Kind k : Kind.values()) c.zones.put(zone(h, k, 2, 2, 7, 7));
        for (Duty duty : new Duty[] {Duty.EXPLORE, Duty.BASE, Duty.STORAGE, Duty.FETCH}) {
            CompanionDuties.startZoned(c, duty);
            var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
            c.saveWithoutId(out);
            var copy = CompanionMod.COMPANION.create(h.getLevel(), EntitySpawnReason.LOAD);
            copy.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
            h.assertTrue(copy.zones.all().equals(c.zones.all()) && copy.duty() == duty && c.dutyPos().equals(copy.dutyPos()), "Speichern/Laden: " + duty);
            h.assertValueEqual(CompanionSnapshot.capture(copy, 0, CompanionMenu.Page.DUTIES).mode(), duty.mode, "Menümodus");
        }
        h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void zonenAndererDimensionWerdenNichtVerwendet(GameTestHelper h) {
        var c = bot(h); var at = c.blockPosition();
        c.zones.put(new Zone(Kind.BASE, "minecraft:the_nether", at, at.offset(3, 3, 3)));
        CompanionDuties.startZoned(c, Duty.BASE);
        h.assertTrue(c.duty() == null && c.isStaying(), "Fremde Dimension verwendet"); h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void neueJobsOhneBereichStartenNicht(GameTestHelper h) {
        var c = bot(h); var p = owner(h, c);
        for (int action = 40; action <= 43; action++) {
            CompanionActions.get(action).handler().executeComponent(c, p, false);
            h.assertTrue(c.duty() == null && c.isStaying(), "Job ohne Zone: " + action);
        }
        h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation", maxTicks = 400) public void felderJobErntetNurInnerhalbDerZone(GameTestHelper h) {
        var c = bot(h); var p = owner(h, c); c.setNoAi(false);
        for (int x : new int[] {3, 6}) {
            h.setBlock(rel(x, 0, 3), Blocks.FARMLAND);
            h.setBlock(rel(x, 1, 3), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        }
        c.zones.put(zone(h, Kind.FIELDS, 2, 2, 4, 4)); c.giveToInventory(new ItemStack(Items.WHEAT_SEEDS, 4));
        CompanionActions.get(37).handler().executeComponent(c, p, false);
        h.succeedWhen(() -> {
            h.assertTrue(!CompanionJobs.isMatureCrop(h.getBlockState(rel(3, 1, 3))), "Inneres Feld nicht geerntet");
            h.assertTrue(h.getBlockState(rel(3, 1, 3)).is(Blocks.WHEAT), "Nicht nachgepflanzt");
            h.assertTrue(CompanionJobs.isMatureCrop(h.getBlockState(rel(6, 1, 3))), "Äußeres Feld verändert");
            h.assertTrue(CompanionCraft.count(c, "wheat") > 0, "Keine Ernte");
        });
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void tierpflegeIgnoriertTiereAusserhalbDerWeide(GameTestHelper h) {
        var c = bot(h); c.zones.put(zone(h, Kind.ANIMALS, 2, 2, 4, 4));
        c.startDuty(Duty.ANIMALS, c.blockPosition()); c.giveToInventory(new ItemStack(Items.WHEAT, 8));
        var a = h.spawn(EntityTypes.COW, rel(6, 1, 3)); var b = h.spawn(EntityTypes.COW, rel(7, 1, 3));
        a.setNoAi(true); b.setNoAi(true);
        h.assertTrue(CompanionJobs.nextToFeed(h.getLevel(), c, "cow") == null, "Tier außerhalb ausgewählt");
        h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void lagerSortiertNurMarkierteKisten(GameTestHelper h) {
        var c = bot(h); h.setBlock(rel(3, 1, 3), Blocks.BARREL); h.setBlock(rel(7, 1, 3), Blocks.BARREL);
        var inside = c.containerAt(h.getLevel(), h.absolutePos(rel(3, 1, 3)));
        var outside = c.containerAt(h.getLevel(), h.absolutePos(rel(7, 1, 3)));
        inside.setItem(4, new ItemStack(Items.DIAMOND)); outside.setItem(4, new ItemStack(Items.EMERALD));
        c.zones.put(zone(h, Kind.STORAGE, 2, 2, 4, 4)); CompanionDuties.startZoned(c, Duty.STORAGE);
        h.assertValueEqual(CompanionJobs.sortByCategory(c, h.getLevel(), c.dutyPos()).boxes(), 1, "Kistenanzahl");
        h.assertTrue(inside.getItem(0).is(Items.DIAMOND) && outside.getItem(4).is(Items.EMERALD) && outside.getItem(0).isEmpty(), "Kistengrenze verletzt"); h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void baseSammeltNurInArbeitsbereichen(GameTestHelper h) {
        var c = bot(h); c.zones.put(zone(h, Kind.BASE, 1, 1, 4, 4)); c.zones.put(zone(h, Kind.ANIMALS, 6, 2, 7, 4));
        CompanionDuties.startZoned(c, Duty.BASE);
        var inside = new ItemEntity(h.getLevel(), c.getX(), c.getY(), c.getZ(), new ItemStack(Items.EGG));
        var outside = new ItemEntity(h.getLevel(), h.absolutePos(rel(8, 1, 8)).getX(), c.getY(), h.absolutePos(rel(8, 1, 8)).getZ(), new ItemStack(Items.EGG));
        h.assertTrue(c.collectible(inside) && !c.collectible(outside), "Sammelgrenze verletzt");
        h.assertTrue(CompanionZones.collectionAllowed(c, h.absolutePos(rel(6, 1, 3))), "Zusätzliche Weide ignoriert"); h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation") public void erkundenPlantSichereZieleInnerhalbDesBereichs(GameTestHelper h) {
        var c = bot(h); var zone = zone(h, Kind.ROAM, 2, 2, 8, 8); c.zones.put(zone); CompanionDuties.startZoned(c, Duty.EXPLORE);
        for (int i = 0; i < 20; i++) {
            BlockPos target = CompanionDuties.wanderTarget(c, h.getLevel(), zone);
            h.assertTrue(target != null && zone.contains(target) && CompanionSafeTeleport.safe(c, h.getLevel(), net.minecraft.world.phys.Vec3.atBottomCenterOf(target)), "Unsicheres Ziel");
        }
        CompanionDuties.tick(c, h.getLevel());
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == CompanionEntity.TaskType.MOVE_TO, "Kein Erkundungsauftrag"); h.succeed();
    }

    @GameTest(structure = "begleiter-gametest:request_isolation", maxTicks = 900) public void tiereHolenBringtTierInZoneStattZumSpieler(GameTestHelper h) {
        var c = bot(h); var p = owner(h, c); c.setNoAi(false);
        var pen = zone(h, Kind.ANIMALS, 2, 2, 7, 7); c.zones.put(pen);
        var cow = h.spawn(EntityTypes.COW, rel(9, 1, 4)); cow.setPersistenceRequired();
        // Der Besitzer steht absichtlich außerhalb der Weide.
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        CompanionDuties.startZoned(c, Duty.FETCH);
        h.succeedWhen(() -> {
            h.assertTrue(pen.contains(cow.blockPosition()) && !cow.isLeashed() && c.fetchTarget == null, "Tier nicht im Zielbereich abgeliefert: " + cow.position());
        });
    }

    @GameTest(structure = "begleiter-gametest:request_isolation", maxTicks = 350) public void kistenpflegeLagertFundEinOhneHeimatkisteZuSetzen(GameTestHelper h) {
        var c = bot(h); c.setNoAi(false);
        h.setBlock(rel(4, 1, 4), Blocks.BARREL);
        c.zones.put(zone(h, Kind.STORAGE, 2, 2, 7, 7));
        c.giveToInventory(new ItemStack(Items.DIAMOND, 3));
        CompanionDuties.startZoned(c, Duty.STORAGE);
        h.succeedWhen(() -> {
            var box = c.containerAt(h.getLevel(), h.absolutePos(rel(4, 1, 4)));
            int count = 0;
            for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(Items.DIAMOND)) count += box.getItem(i).getCount();
            h.assertValueEqual(count, 3, "Diamanten nicht eingelagert");
            h.assertValueEqual(CompanionCraft.count(c, "diamond"), 0, "Doppeltes Inventar");
            h.assertTrue(c.getHome() == null, "Zone überschreibt Lagerpunkt");
        });
    }

    @GameTest(structure = "begleiter-gametest:request_isolation") public void unveraenderteKistenWerdenNichtDauerndNeuSortiert(GameTestHelper h) {
        var c = bot(h); h.setBlock(rel(4, 1, 4), Blocks.BARREL);
        c.zones.put(zone(h, Kind.STORAGE, 2, 2, 7, 7)); CompanionDuties.startZoned(c, Duty.STORAGE);
        c.dutyStorageFingerprint = CompanionDuties.storageFingerprint(c, h.getLevel(), c.dutyPos());
        CompanionDuties.tick(c, h.getLevel()); h.assertTrue(!c.hasTasks(), "Unverändertes Lager neu sortiert");
        c.containerAt(h.getLevel(), h.absolutePos(rel(4, 1, 4))).setItem(3, new ItemStack(Items.EMERALD));
        CompanionDuties.tick(c, h.getLevel());
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == CompanionEntity.TaskType.SORT, "Änderung nicht erkannt"); h.succeed();
    }

    @GameTest(structure = "begleiter-gametest:request_isolation") public void doppelkisteUeberBereichsgrenzeWirdAusgelassen(GameTestHelper h) {
        var c = bot(h);
        var left = Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.NORTH)
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        var right = left.setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT);
        h.setBlock(rel(3, 1, 3), left); h.setBlock(rel(4, 1, 3), right);
        c.zones.put(zone(h, Kind.STORAGE, 2, 2, 3, 4)); CompanionDuties.startZoned(c, Duty.STORAGE);
        h.assertTrue(c.storageBoxes(h.getLevel(), c.dutyPos()).isEmpty(), "Grenzüberschreitende Doppelkiste verwendet"); h.succeed();
    }

    // Im vollen Lauf teilen sich alle Tests das Navigationsbudget der Welt; allein reichen ~200 Ticks.
    @GameTest(structure = "begleiter-gametest:request_isolation", maxTicks = 1200)
    public void baseSammeltAusTeilbereichenUndLaesstAussenliegendeItemsLiegen(GameTestHelper h) {
        var c = bot(h); c.setNoAi(false);
        c.zones.put(zone(h, Kind.BASE, 1, 1, 4, 4)); c.zones.put(zone(h, Kind.ANIMALS, 6, 2, 8, 4));
        CompanionDuties.startZoned(c, Duty.BASE);
        var inPos = h.absolutePos(rel(7, 1, 3)); var outPos = h.absolutePos(rel(10, 1, 10));
        var inside = new ItemEntity(h.getLevel(), inPos.getX() + .5, inPos.getY(), inPos.getZ() + .5, new ItemStack(Items.EGG));
        var outside = new ItemEntity(h.getLevel(), outPos.getX() + .5, outPos.getY(), outPos.getZ() + .5, new ItemStack(Items.EGG));
        h.getLevel().addFreshEntity(inside); h.getLevel().addFreshEntity(outside);
        c.addTask(CompanionEntity.Task.collect(true));
        h.onEachTick(() -> {
            if (outside.isRemoved()) h.fail("Gegenstand außerhalb der Bereiche eingesammelt");
        });
        h.succeedWhen(() -> {
            h.assertTrue(inside.isRemoved(), "Gegenstand im Tierbereich noch nicht eingesammelt");
            h.assertValueEqual(CompanionCraft.count(c, "egg"), 1, "Ei nicht genau einmal aufgenommen");
        });
    }
}
