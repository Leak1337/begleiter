package dev.begleiter;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StemBlock;

/** Echte Weltprüfung für Standortfreigabe, gedrehte Gehege und den Sammelweg der Eier. */
public class FarmPlacementGameTests {
    @GameTest
    public void katalogVorschauUebertraegtZustaendeOhneLuftUndBlockDaten(GameTestHelper h) {
        var source = CompanionMachines.find("wasserernte").plan().get();
        var packet = new CompanionBuildPreview(42, java.util.UUID.randomUUID(),
                CompanionMachines.find("wasserernte").title(), source, -1, java.util.UUID.randomUUID());
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            CompanionBuildPreview.CODEC.encode(buffer, packet);
            var decoded = CompanionBuildPreview.CODEC.decode(buffer);
            h.assertTrue(decoded.containerId() == 42 && decoded.npc().equals(packet.npc()), "Menübindung verloren");
            h.assertTrue(decoded.modelId().equals(packet.modelId()), "Model cache identity lost");
            h.assertValueEqual(decoded.catalogueSlot(), -1, "Modal preview became a thumbnail");
            h.assertTrue(decoded.title().equals(packet.title()), "Übersetzbarer Titel verloren");
            h.assertTrue(decoded.plan().sizeX() == source.sizeX() && decoded.plan().sizeY() == source.sizeY()
                    && decoded.plan().sizeZ() == source.sizeZ(), "Maße verloren");
            var expected = new java.util.HashMap<>(source.blocks());
            expected.values().removeIf(net.minecraft.world.level.block.state.BlockState::isAir);
            h.assertTrue(decoded.plan().blocks().equals(expected), "Blöcke oder Ausrichtung verändert");
            h.assertTrue(decoded.plan().data().isEmpty() && decoded.plan().entities().isEmpty(), "Weltinhalte übertragen");
        } finally { buffer.release(); }
        h.succeed();
    }

    @GameTest
    public void katalogVorschauLehntUebergrosseDimensionenAb(GameTestHelper h) {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            buffer.writeVarInt(1); buffer.writeUUID(java.util.UUID.randomUUID());
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buffer, net.minecraft.network.chat.Component.literal("invalid"));
            buffer.writeVarInt(-1); // full preview, not a catalogue thumbnail
            buffer.writeUUID(java.util.UUID.randomUUID());
            buffer.writeVarInt(1024); buffer.writeVarInt(1024); buffer.writeVarInt(1024); buffer.writeVarInt(0);
            boolean rejected = false;
            try { CompanionBuildPreview.CODEC.decode(buffer); }
            catch (IllegalArgumentException expected) { rejected = true; }
            h.assertTrue(rejected, "Übergroßes Vorschaumodell akzeptiert");
        } finally { buffer.release(); }
        h.succeed();
    }
    private static final String ARENA = "begleiter-gametest:bench";
    private record Setup(CompanionEntity bot, ServerPlayer player, CompanionPlacement.Preview preview) {}

    private Setup setup(GameTestHelper h) {
        var bot = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        bot.setNoAi(true); bot.stay();
        var player = h.makeMockServerPlayerInLevel();
        bot.setOwner(player.getUUID()); player.snapTo(bot.getX(), bot.getY(), bot.getZ(), 0, 0);
        bot.addTask(CompanionEntity.Task.moveTo(bot.blockPosition(), 1));
        return new Setup(bot, player, CompanionPlacement.select(player, bot, CompanionMachines.find("kuhfarm"), Rotation.NONE));
    }

    @GameTest(structure = ARENA)
    public void auswahlAbbruchUndAltePaketeErhaltenAuftrag(GameTestHelper h) {
        var s = setup(h);
        var task = s.bot.currentTask();
        h.assertTrue(s.preview != null && s.bot.build == null && task != null, "Auswahl verändert Auftrag");
        var newer = CompanionPlacement.select(s.player, s.bot, CompanionMachines.find("huehnerfarm"), Rotation.NONE);
        var old = CompanionPlacement.confirm(s.player, new CompanionPlacement.Confirm(s.preview.token(), h.absolutePos(new BlockPos(6, 1, 6)), 0, false));
        h.assertTrue(old.finished() && s.bot.build == null && s.bot.currentTask() == task, "Alte Auswahl akzeptiert");
        var result = CompanionPlacement.confirm(s.player, new CompanionPlacement.Confirm(newer.token(), BlockPos.ZERO, 0, true));
        h.assertTrue(result.finished() && s.bot.currentTask() == task && s.bot.build == null, "Abbruch verändert Auftrag");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void bestaetigungBautExaktAmGedrehtenVorschaustandort(GameTestHelper h) {
        var s = setup(h);
        BlockPos center = h.absolutePos(new BlockPos(6, 1, 6));
        var expected = CompanionSchematics.placeAt(CompanionMachines.find("kuhfarm").plan().get(), center, Rotation.CLOCKWISE_90, true);
        h.assertTrue(CompanionPlacement.prepare(h.getLevel(), expected) == null, "Testplatz blockiert");
        var result = CompanionPlacement.confirm(s.player, new CompanionPlacement.Confirm(s.preview.token(), center, 1, false));
        h.assertTrue(result.finished() && s.bot.build != null, "Bestätigung verworfen: " + result.message().getString());
        var actual = new java.util.HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (var placement : s.bot.build.order) actual.put(placement.pos(), placement.state());
        h.assertTrue(actual.equals(expected), "Gebauter Plan weicht von gedrehter Vorschau ab");
        var job = s.bot.build;
        CompanionPlacement.confirm(s.player, new CompanionPlacement.Confirm(s.preview.token(), center.above(), 0, false));
        h.assertTrue(s.bot.build == job, "Doppelte Bestätigung ersetzt Bau");
        h.succeed();
    }

    /** Schematic-Datei: Plan reist per Paket zum Client, Bestätigung baut genau die Vorschau samt Block-Daten. */
    @GameTest(structure = ARENA)
    public void schematicVorschauReistZumClientUndBautGenauDort(GameTestHelper h) {
        var s = setup(h);
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new java.util.HashMap<>();
        blocks.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(1, 0, 0), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 1, 1), Blocks.CRAFTER.defaultBlockState());
        var data = new net.minecraft.nbt.CompoundTag();
        data.putIntArray("disabled_slots", new int[]{0, 4, 8});
        var plan = new CompanionSchematics.Schematic("Probe", 3, 2, 2, blocks, Map.of(new BlockPos(2, 1, 1), data));
        var preview = CompanionPlacement.selectSchematic(s.player, s.bot, plan, false, Rotation.NONE);
        h.assertTrue(preview != null && s.bot.build == null, "keine Vorschau oder schon gebaut");

        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        CompanionPlacement.SchematicPreview.CODEC.encode(buf, preview);
        var received = CompanionPlacement.SchematicPreview.CODEC.decode(buf);
        h.assertValueEqual(received.plan().blocks().size(), 2, "Luft darf ohne 'luft' nicht mitreisen");
        h.assertTrue(received.plan().blocks().get(new BlockPos(2, 1, 1)).is(Blocks.CRAFTER), "Zustand falsch übertragen");
        h.assertValueEqual(received.plan().sizeX(), 3, "Größe");

        BlockPos center = h.absolutePos(new BlockPos(6, 1, 6));
        var expected = CompanionSchematics.placeAt(received.plan(), center, Rotation.CLOCKWISE_90, false);
        var result = CompanionPlacement.confirm(s.player, new CompanionPlacement.Confirm(preview.token(), center, 1, false));
        h.assertTrue(result.finished() && s.bot.build != null, "Bestätigung verworfen: " + result.message().getString());
        for (var en : expected.entrySet())
            h.assertTrue(s.bot.build.order.stream().anyMatch(p -> p.pos().equals(en.getKey()) && p.state().equals(en.getValue())),
                    "Vorschau-Block fehlt im Auftrag: " + en.getKey().toShortString());
        BlockPos crafterAt = expected.entrySet().stream().filter(e -> e.getValue().is(Blocks.CRAFTER)).findFirst().orElseThrow().getKey();
        h.assertTrue(s.bot.build.data.containsKey(crafterAt), "Block-Daten nicht im Auftrag");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void fremdeSpielerUndUngueltigeAnfragenWerdenAbgewiesen(GameTestHelper h) {
        var s = setup(h);
        var stranger = h.makeMockServerPlayerInLevel();
        stranger.snapTo(s.player.getX(), s.player.getY(), s.player.getZ(), 0, 0);
        s.bot.setOwner(UUID.randomUUID());
        h.assertTrue(CompanionPlacement.select(stranger, s.bot, CompanionMachines.find("kuhfarm"), Rotation.NONE) == null,
                "Fremder Spieler kann Plan auswählen");
        s.bot.setOwner(s.player.getUUID());
        BlockPos center = h.absolutePos(new BlockPos(6, 1, 6));
        for (var request : java.util.List.of(
                new CompanionPlacement.Confirm(UUID.randomUUID(), center, 0, false),
                new CompanionPlacement.Confirm(s.preview.token(), center, -1, false),
                new CompanionPlacement.Confirm(s.preview.token(), center, 4, false),
                new CompanionPlacement.Confirm(s.preview.token(), center.offset(100, 0, 0), 0, false))) {
            CompanionPlacement.confirm(s.player, request);
            h.assertTrue(s.bot.build == null && s.bot.currentTask() != null, "Ungültiges Paket startet Bau");
        }
        s.bot.setOwner(UUID.randomUUID());
        CompanionPlacement.confirm(s.player, new CompanionPlacement.Confirm(s.preview.token(), center, 0, false));
        h.assertTrue(s.bot.build == null, "Nach Besitzerwechsel gebaut");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void kistenGebaeudeUndFluessigkeitenBlockierenOhneAuftragsverlust(GameTestHelper h) {
        var s = setup(h);
        var task = s.bot.currentTask();
        BlockPos center = h.absolutePos(new BlockPos(6, 1, 6));
        BlockPos occupied = center.above();
        for (var block : java.util.List.of(Blocks.CHEST, Blocks.OAK_PLANKS, Blocks.LAVA)) {
            h.getLevel().setBlock(occupied, block.defaultBlockState(), CompanionBuilder.PLACE_FLAGS);
            if (h.getLevel().getBlockEntity(occupied) instanceof Container chest) chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
            var result = CompanionPlacement.confirm(s.player, new CompanionPlacement.Confirm(s.preview.token(), center, 0, false));
            h.assertTrue(!result.finished() && s.bot.build == null && s.bot.currentTask() == task, "Blockierter Platz ersetzt Auftrag");
            h.assertTrue(h.getLevel().getBlockState(occupied).is(block), "Hindernis verändert");
            if (h.getLevel().getBlockEntity(occupied) instanceof Container chest)
                h.assertValueEqual(chest.getItem(0).getCount(), 3, "Kisteninhalt verändert");
        }
        h.succeed();
    }

    @GameTest
    public void ungeladeneUndZuTiefePlaetzeLadenKeineChunks(GameTestHelper h) {
        BlockPos remote = new BlockPos(1_000_000, 100, 1_000_000);
        h.assertTrue(!h.getLevel().hasChunkAt(remote), "Testchunk bereits geladen");
        h.assertTrue(CompanionPlacement.prepare(h.getLevel(), new java.util.HashMap<>(Map.of(remote, Blocks.STONE.defaultBlockState()))) != null,
                "Ungeladener Standort freigegeben");
        h.assertTrue(!h.getLevel().hasChunkAt(remote), "Chunk durch Prüfung geladen");
        BlockPos low = h.absolutePos(BlockPos.ZERO).atY(h.getLevel().getMinY() - 1);
        h.assertTrue(CompanionPlacement.prepare(h.getLevel(), new java.util.HashMap<>(Map.of(low, Blocks.STONE.defaultBlockState()))) != null,
                "Unter Baugrenze freigegeben");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void neueFarmenHabenUnterschiedlicheGrundrisseUndDrehbareMaterialien(GameTestHelper h) {
        var signatures = new HashSet<Map<BlockPos, net.minecraft.world.level.block.state.BlockState>>();
        for (String id : java.util.List.of("ruebenfarm", "kuerbisfarm", "melonenfarm", "kuhfarm", "schaffarm", "schweinefarm", "kaninchenfarm", "huehnerfarm")) {
            var plan = CompanionMachines.find(id).plan().get();
            h.assertTrue(signatures.add(plan.blocks()), "Nur umbenannte Farm: " + id);
            var materials = new java.util.HashMap<net.minecraft.world.item.Item, Integer>();
            for (var state : plan.blocks().values()) {
                if (state.isAir()) continue;
                var item = CompanionBuilder.itemFor(state);
                h.assertTrue(item != null, "Kein Material für " + id + ": " + state);
                materials.merge(item, CompanionBuilder.itemCount(state), Integer::sum);
            }
            for (var rotation : CompanionPlacement.ROTATIONS) {
                var rotated = CompanionSchematics.placeAt(plan, h.absolutePos(new BlockPos(6, 1, 6)), rotation, true);
                var counts = new java.util.HashMap<net.minecraft.world.item.Item, Integer>();
                for (var state : rotated.values()) {
                    var item = CompanionBuilder.itemFor(state);
                    if (item != null) counts.merge(item, CompanionBuilder.itemCount(state), Integer::sum);
                }
                h.assertTrue(materials.equals(counts), "Drehung ändert Materialbedarf: " + id);
            }
        }
        h.succeed();
    }

    private void place(GameTestHelper h, String id) {
        TestMapExpansion.placeExhibit(h.getLevel(), h.absolutePos(new BlockPos(0, 1, 0)), CompanionMachines.find(id).plan().get());
    }

    @GameTest(maxTicks = 300, structure = ARENA)
    public void huehnerstallSammeltEierDurchTeppichBisInKiste(GameTestHelper h) {
        place(h, "huehnerfarm");
        h.runAfterDelay(45, () -> {
            BlockPos drop = h.absolutePos(new BlockPos(5, 3, 6));
            var egg = new ItemEntity(h.getLevel(), drop.getX() + .5, drop.getY() + .2, drop.getZ() + .5, new ItemStack(Items.EGG));
            egg.setDeltaMovement(0, 0, 0); h.getLevel().addFreshEntity(egg);
        });
        h.succeedWhen(() -> {
            var chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(1, 2, 0)));
            h.assertTrue(chest != null && chest.countItem(Items.EGG) == 1, "Ei erreicht Kiste nicht");
        });
    }

    @GameTest(maxTicks = 120, structure = ARENA)
    public void neuePflanzenUeberlebenBewasserungUndBlockupdates(GameTestHelper h) {
        place(h, "ruebenfarm");
        h.runAfterDelay(70, () -> {
            int crops = 0;
            for (var entry : CompanionMachines.find("ruebenfarm").plan().get().blocks().entrySet()) {
                if (!(entry.getValue().getBlock() instanceof CropBlock)) continue;
                BlockPos pos = h.absolutePos(new BlockPos(0, 1, 0)).offset(entry.getKey());
                h.assertTrue(h.getLevel().getBlockState(pos).is(Blocks.BEETROOTS), "Rübe durch Wasser/Untergrund zerstört");
                crops++;
            }
            h.assertValueEqual(crops, 30, "Rübenzahl");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void kuhstallWirdMitSurvivalMaterialGebaut(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_kuhfarm"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void kaninchenstallWirdMitSurvivalMaterialGebaut(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_kaninchenfarm"); }

    // 648 Blöcke im Survival-Bau, ~100 Blöcke je 300 Ticks: 3000 Ticks waren ohne Puffer (einzeln/unter Last zu knapp)
    @GameTest(maxTicks = 4800, structure = ARENA)
    public void melonenhausWirdMitSurvivalMaterialGebaut(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_melonenfarm"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void schafweideWirdMitSurvivalMaterialGebaut(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_schaffarm"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void schweinebuchtenWerdenMitSurvivalMaterialGebaut(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_schweinefarm"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void ruebenbeeteWerdenMitSurvivalMaterialGebaut(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_ruebenfarm"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void kuerbisgartenWirdMitSurvivalMaterialGebaut(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_kuerbisfarm"); }

    private void fruit(GameTestHelper h, String id, net.minecraft.world.level.block.Block fruit) {
        place(h, id);
        h.runAfterDelay(65, () -> {
            var plan = CompanionMachines.find(id).plan().get();
            var origin = h.absolutePos(new BlockPos(0, 1, 0));
            var random = net.minecraft.util.RandomSource.create(42L);
            for (var entry : plan.blocks().entrySet()) {
                if (!(entry.getValue().getBlock() instanceof StemBlock)) continue;
                BlockPos pos = origin.offset(entry.getKey());
                h.assertTrue(h.getLevel().getBlockState(pos).is(entry.getValue().getBlock()), "Stiel durch Aufbau zerstört");
                h.getLevel().setBlockAndUpdate(pos, entry.getValue().setValue(StemBlock.AGE, 7));
                for (int i = 0; i < 150; i++) {
                    var state = h.getLevel().getBlockState(pos);
                    if (state.getBlock() instanceof StemBlock) state.randomTick(h.getLevel(), pos, random);
                }
            }
            boolean found = plan.blocks().keySet().stream().anyMatch(pos -> h.getLevel().getBlockState(origin.offset(pos)).is(fruit));
            h.assertTrue(found, "Keine Frucht trotz reifer Stiele und Wachstums-Ticks: " + id);
            h.succeed();
        });
    }

    @GameTest(maxTicks = 150, structure = ARENA)
    public void kuerbisgartenErzeugtEchteFruechte(GameTestHelper h) { fruit(h, "kuerbisfarm", Blocks.PUMPKIN); }

    @GameTest(maxTicks = 150, structure = ARENA)
    public void melonenhausErzeugtEchteFruechte(GameTestHelper h) { fruit(h, "melonenfarm", Blocks.MELON); }
}
