package dev.begleiter;

import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;

/** Schematics über dem alten 200.000er-Limit: laden ohne Luft, Vorschau-Pakete, Bauauftrag samt Speichern. */
public class LargeSchematicGameTests {
    @GameTest(maxTicks = 200)
    public void importierteBeobachterUhrenStartenInAllenDrehungen(GameTestHelper h) {
        var clocks = new java.util.ArrayList<java.util.List<BlockPos>>();
        var level = h.getLevel();
        for (var rotation : Rotation.values()) {
            int i = clocks.size();
            var map = java.util.Map.of(BlockPos.ZERO, Blocks.OBSERVER.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, net.minecraft.core.Direction.EAST),
                    new BlockPos(1, 0, 0), Blocks.OBSERVER.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, net.minecraft.core.Direction.WEST));
            var plan = new CompanionSchematics.Schematic("observer-clock", 2, 1, 1, map);
            var placed = CompanionSchematics.placeAtFull(plan, h.absolutePos(new BlockPos(2 + (i % 2) * 3, 1, 2 + (i / 2) * 3)), rotation, false);
            var job = CompanionBuilder.create(level, "observer-clock", placed.blocks());
            for (var p : job.order) { level.setBlock(p.pos(), p.state(), CompanionBuilder.PLACE_FLAGS); job.placed.add(p.pos()); }
            CompanionBuilder.finish(level, job);
            clocks.add(new java.util.ArrayList<>(placed.blocks().keySet()));
        }
        boolean[] pulsed = new boolean[4];
        h.onEachTick(() -> {
            for (int i = 0; i < clocks.size(); i++) for (var pos : clocks.get(i))
                pulsed[i] |= level.getBlockState(pos).getValue(net.minecraft.world.level.block.ObserverBlock.POWERED);
        });
        h.succeedWhen(() -> { for (boolean pulse : pulsed) h.assertTrue(pulse, "Importierte Beobachter-Uhr bleibt stehen"); });
    }

    @GameTest
    public void alteKatalogdateienWerdenNurBeiGleichemInhaltArchiviert(GameTestHelper h) throws Exception {
        // Alte Dateinamen gibt es nur bei Fremd-Schematics des privaten Katalogs; ohne sie nichts zu migrieren.
        var entry = CompanionSchematicCatalogue.entries().stream().filter(e -> e.legacyFiles().size() >= 2).findFirst().orElse(null);
        if (entry == null) { h.succeed(); return; }
        var source = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(CompanionMod.MOD_ID).orElseThrow()
                .findPath("data/begleiter/bundled_schematics/" + entry.file()).orElseThrow();
        var folder = h.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("catalogue-migration-" + java.util.UUID.randomUUID());
        Files.createDirectories(folder);
        var aliases = new java.util.ArrayList<>(entry.legacyFiles().keySet());
        var unchanged = folder.resolve(aliases.get(0)); var edited = folder.resolve(aliases.get(1));
        Files.copy(source, unchanged); Files.copy(source, edited);
        Files.writeString(edited, "player-edited schematic");
        CompanionSchematicCatalogue.archiveLegacy(folder);
        h.assertTrue(!Files.exists(unchanged) && Files.exists(folder.resolve(".begleiter-archiv").resolve(unchanged.getFileName())), "Original copy not archived");
        h.assertTrue(Files.readString(edited).equals("player-edited schematic"), "Edited player file touched");
        h.succeed();
    }

    @GameTest
    public void kreativBehaeltAuchEinzelItemsInTrichteruhren(GameTestHelper h) {
        var at = h.absolutePos(new BlockPos(2, 1, 2));
        h.getLevel().setBlock(at, Blocks.HOPPER.defaultBlockState(), CompanionBuilder.PLACE_FLAGS);
        var job = CompanionBuilder.create(h.getLevel(), "timer", java.util.Map.of(at, Blocks.HOPPER.defaultBlockState()));
        CompoundTag tag = new CompoundTag(); tag.putString("id", "minecraft:hopper");
        var items = new net.minecraft.nbt.ListTag(); var item = new CompoundTag();
        item.putString("id", "minecraft:iron_ingot"); item.putInt("count", 1); item.putByte("Slot", (byte) 0); items.add(item); tag.put("Items", items);
        job.data.put(at, CompanionSchematics.blockData(tag));
        h.assertValueEqual(CompanionBuilder.pendingContents(job), 1, "Einzelitem der Trichteruhr entfernt");
        CompanionBuilder.applyData(h.getLevel(), job, at, false);
        h.assertTrue(((net.minecraft.world.Container) h.getLevel().getBlockEntity(at)).isEmpty(), "Survival erhält kostenlose Uhr-Items");
        CompanionBuilder.applyContents(h.getLevel(), job);
        h.assertValueEqual(((net.minecraft.world.Container) h.getLevel().getBlockEntity(at)).countItem(net.minecraft.world.item.Items.IRON_INGOT), 1, "Kreativ-Uhr leer");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void kreativeBooteBehaltenMitfahrerUndDrehung(GameTestHelper h) {
        var level = h.getLevel();
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        CompoundTag boat = new CompoundTag();
        boat.putString("id", "minecraft:oak_boat"); boat.put("Pos", doubles(1.5, 0, 1.5));
        var heading = new net.minecraft.nbt.ListTag();
        heading.add(net.minecraft.nbt.FloatTag.valueOf(20)); heading.add(net.minecraft.nbt.FloatTag.valueOf(0));
        boat.put("Rotation", heading);
        CompoundTag zombie = new CompoundTag();
        zombie.putString("id", "minecraft:zombie");
        zombie.put("Pos", doubles(-500, 90, 400)); // Passenger still contains coordinates from the source world.
        zombie.putIntArray("UUID", new int[] {1, 2, 3, 4}); zombie.putBoolean("NoAI", true);
        var passengers = new net.minecraft.nbt.ListTag(); passengers.add(zombie); boat.put("Passengers", passengers);
        var source = new net.minecraft.nbt.ListTag(); source.add(boat);
        var entities = new java.util.ArrayList<CompanionSchematics.EntityPlan>();
        CompanionSchematics.readEntities(source, 0, 0, 0, entities);
        h.assertValueEqual(entities.size(), 1, "Boot wurde ausgelassen");
        h.assertTrue(!entities.getFirst().tag().getListOrEmpty("Passengers").getCompoundOrEmpty(0).contains("UUID"), "Mitfahrer-UUID behalten");
        var plan = new CompanionSchematics.Schematic("boat", 3, 1, 3,
                java.util.Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()), java.util.Map.of(), entities);
        int n = 0;
        var expectedHeadings = new java.util.HashMap<net.minecraft.world.phys.Vec3, Float>();
        for (Rotation rotation : Rotation.values()) {
            var center = h.absolutePos(new BlockPos(2 + n % 2 * 3, 1, 2 + n / 2 * 3)); n++;
            var placed = CompanionSchematics.placeAtFull(plan, center, rotation, false);
            expectedHeadings.put(placed.entities().getFirst().pos(), switch (rotation) {
                case NONE -> 20f; case CLOCKWISE_90 -> 110f; case CLOCKWISE_180 -> 200f; case COUNTERCLOCKWISE_90 -> 290f;
            });
            var job = CompanionBuilder.create(level, "boat", placed.blocks()); job.entities.addAll(placed.entities());
            var result = CompanionBuilder.spawnEntities(level, job, null);
            h.assertValueEqual(result.spawned(), 2, "Boot oder Zombie fehlt bei " + rotation);
            h.assertValueEqual(result.missing(), 0, "Entity konnte nicht gesetzt werden");
        }
        h.runAfterDelay(5, () -> {
            var box = new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).expandTowards(8, 8, 8);
            var boats = level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, box,
                    entity -> entity.getType() == net.minecraft.world.entity.EntityTypes.OAK_BOAT);
            h.assertValueEqual(boats.size(), 4, "Boot verloren");
            for (var expected : expectedHeadings.entrySet()) {
                var vehicle = boats.stream().min(java.util.Comparator.comparingDouble(e -> e.position().distanceToSqr(expected.getKey()))).orElseThrow();
                h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(vehicle.getYRot() - expected.getValue())) < 1,
                        "Boot falsch gedreht: " + vehicle.getYRot() + " statt " + expected.getValue());
            }
            for (var vehicle : boats) {
                h.assertValueEqual(vehicle.getPassengers().size(), 1, "Mitfahrer fehlt");
                var rider = vehicle.getPassengers().getFirst();
                h.assertTrue(rider.getType() == net.minecraft.world.entity.EntityTypes.ZOMBIE, "Falscher Mitfahrer");
                h.assertTrue(rider.distanceToSqr(vehicle) < 4, "Mitfahrer an alten Weltkoordinaten");
                h.assertTrue(((net.minecraft.world.entity.Mob) rider).isPersistenceRequired(), "Mitfahrer despawnt");
            }
            h.assertTrue(CompanionBuilder.allowedEntity(net.minecraft.world.entity.EntityTypes.WARDEN), "Warden ausgelassen");
            h.assertTrue(CompanionBuilder.allowedEntity(net.minecraft.world.entity.EntityTypes.ELDER_GUARDIAN), "Ältester Wächter ausgelassen");
            h.assertTrue(CompanionBuilder.allowedEntity(net.minecraft.world.entity.EntityTypes.WITHER), "Wither ausgelassen");
            h.succeed();
        });
    }

    /** 300 x 4 x 300 = 360.000 Volumen, unten eine Steinschicht: früher abgelehnt, jetzt dünn gespeichert. */
    private static CompanionSchematics.Schematic floor(GameTestHelper h) {
        int w = 300, hgt = 4, l = 300;
        CompoundTag t = new CompoundTag();
        t.putShort("Width", (short) w); t.putShort("Height", (short) hgt); t.putShort("Length", (short) l);
        CompoundTag palette = new CompoundTag();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:stone", 1);
        t.put("Palette", palette);
        byte[] data = new byte[w * hgt * l];
        for (int i = 0; i < w * l; i++) data[i] = 1; // y = 0
        t.putByteArray("BlockData", data);
        return CompanionSchematics.loadSponge(h.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK), "boden", t);
    }

    @GameTest
    public void grossesSchematicWirdOhneLuftGeladen(GameTestHelper h) {
        var plan = floor(h);
        h.assertValueEqual(plan.blocks().size(), 300 * 300, "Steinschicht unvollständig");
        h.assertTrue(plan.blocks().values().stream().noneMatch(s -> s.isAir()), "Luft gespeichert");
        h.assertTrue(CompanionSchematics.sparse(plan), "nicht als dünn erkannt");
        h.succeed();
    }

    @GameTest
    public void grosseVorschauPaketeUebertragenAlleBloecke(GameTestHelper h) {
        var plan = floor(h);
        var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            CompanionBuildPreview.CODEC.encode(buf, new CompanionBuildPreview(1, java.util.UUID.randomUUID(),
                    net.minecraft.network.chat.Component.literal("Boden"), plan));
            int bytes = buf.readableBytes();
            var menu = CompanionBuildPreview.CODEC.decode(buf);
            h.assertTrue(menu.plan().blocks().equals(plan.blocks()), "Menü-Vorschau verändert Blöcke");
            h.assertTrue(bytes < 4 * plan.blocks().size(), "Kodierung zu groß: " + bytes + " Bytes");
            buf.clear();
            var pre = new CompanionPlacement.SchematicPreview(java.util.UUID.randomUUID(), "boden", 0, 0, true, plan);
            CompanionPlacement.SchematicPreview.CODEC.encode(buf, pre);
            var placed = CompanionPlacement.SchematicPreview.CODEC.decode(buf);
            h.assertTrue(placed.plan().blocks().equals(plan.blocks()), "Platzier-Vorschau verändert Blöcke");
            h.assertTrue(placed.plan().sizeX() == 300 && placed.plan().sizeZ() == 300, "Maße verloren");
        } finally { buf.release(); }
        h.succeed();
    }

    @GameTest
    public void grosserBauauftragUeberstehtSpeichern(GameTestHelper h) {
        var plan = floor(h);
        var placed = CompanionSchematics.placeAtFull(plan, h.absolutePos(new BlockPos(1, 1, 1)), Rotation.NONE, true);
        var job = CompanionBuilder.create(h.getLevel(), "boden", placed.blocks());
        h.assertValueEqual(job.total(), 300 * 300, "Auftrag verliert Blöcke");
        var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        CompanionBuilder.save(out, job);
        var in = net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult());
        var loaded = CompanionBuilder.load(in);
        h.assertTrue(loaded != null && loaded.total() == job.total(), "Großer Auftrag nicht wieder geladen");
        h.assertTrue(job.contains(placed.blocks().keySet().iterator().next()), "Positionssuche findet Planblock nicht");
        h.succeed();
    }

    /** Litematica-artige Entities: Rahmen an einer Steinwand (Süd), ein Schaf, ein herumliegendes Item. */
    private static CompanionSchematics.Schematic framed(GameTestHelper h) {
        return framed(h, "minecraft:dark_oak_planks");
    }

    private static CompanionSchematics.Schematic framed(GameTestHelper h, String content) {
        var ents = new net.minecraft.nbt.ListTag();
        CompoundTag frame = new CompoundTag();
        frame.putString("id", "minecraft:item_frame");
        frame.put("Pos", doubles(0.5, 0.5, 2.03125));
        frame.putIntArray("block_pos", new int[] {0, 0, 2});
        frame.putByte("Facing", (byte) net.minecraft.core.Direction.SOUTH.get3DDataValue());
        CompoundTag item = new CompoundTag();
        item.putString("id", content); item.putInt("count", 1);
        frame.put("Item", item);
        ents.add(frame);
        CompoundTag sheep = new CompoundTag();
        sheep.putString("id", "minecraft:sheep");
        sheep.put("Pos", doubles(2.5, 0, 0.5));
        ents.add(sheep);
        CompoundTag loose = new CompoundTag();
        loose.putString("id", "minecraft:item");
        loose.put("Pos", doubles(1.5, 0, 1.5));
        ents.add(loose);
        var list = new java.util.ArrayList<CompanionSchematics.EntityPlan>();
        CompanionSchematics.readEntities(ents, 0, 0, 0, list);
        var blocks = new java.util.HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        blocks.put(new BlockPos(0, 0, 1), Blocks.STONE.defaultBlockState());
        blocks.put(new BlockPos(2, 0, 2), Blocks.STONE.defaultBlockState());
        return new CompanionSchematics.Schematic("rahmen", 3, 1, 3, blocks, java.util.Map.of(), list);
    }

    private static net.minecraft.nbt.ListTag doubles(double x, double y, double z) {
        var l = new net.minecraft.nbt.ListTag();
        l.add(net.minecraft.nbt.DoubleTag.valueOf(x)); l.add(net.minecraft.nbt.DoubleTag.valueOf(y)); l.add(net.minecraft.nbt.DoubleTag.valueOf(z));
        return l;
    }

    private static CompanionBuilder.BuildJob placeFramed(GameTestHelper h, CompanionSchematics.Schematic plan, BlockPos center) {
        var placed = CompanionSchematics.placeAtFull(plan, center, Rotation.CLOCKWISE_90, false);
        for (var en : placed.blocks().entrySet()) h.getLevel().setBlock(en.getKey(), en.getValue(), CompanionBuilder.PLACE_FLAGS);
        var job = CompanionBuilder.create(h.getLevel(), "rahmen", placed.blocks());
        job.entities.addAll(placed.entities());
        return job;
    }

    @GameTest
    public void rahmenUndTiereWerdenGedrehtMitGesetzt(GameTestHelper h) {
        var plan = framed(h);
        h.assertValueEqual(plan.entities().size(), 2, "Herumliegendes Item darf nicht in den Plan");
        BlockPos center = h.absolutePos(new BlockPos(4, 1, 4));
        var job = placeFramed(h, plan, center);
        int spawned = CompanionBuilder.spawnEntities(h.getLevel(), job);
        h.assertValueEqual(spawned, 2, "Rahmen und Schaf");
        // Rahmenblock (0,0,2) dreht im 3x3-Plan auf (0,0,0), Blick Süd -> West, Halt am Stein östlich davon
        BlockPos frameAt = center.offset(-1, 0, -1);
        var frames = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new net.minecraft.world.phys.AABB(frameAt).inflate(1));
        h.assertValueEqual(frames.size(), 1, "Rahmen fehlt");
        var f = frames.get(0);
        h.assertTrue(f.getPos().equals(frameAt), "Rahmen am falschen Block: " + f.getPos().toShortString());
        h.assertTrue(f.getDirection() == net.minecraft.core.Direction.WEST, "Rahmen falsch gedreht: " + f.getDirection());
        h.assertTrue(f.getItem().is(net.minecraft.world.item.Items.DARK_OAK_PLANKS), "Rahmeninhalt fehlt");
        h.assertTrue(!h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                new net.minecraft.world.phys.AABB(center).inflate(4), e -> e.getType() == net.minecraft.world.entity.EntityTypes.SHEEP).isEmpty(), "Schaf fehlt");
        // erneutes Setzen (zweiter Bau am selben Ort) hängt keinen zweiten Rahmen auf
        var again = placeFramed(h, plan, center);
        CompanionBuilder.spawnEntities(h.getLevel(), again);
        h.assertValueEqual(h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class,
                new net.minecraft.world.phys.AABB(frameAt).inflate(1)).size(), 1, "Rahmen doppelt");
        h.succeed();
    }

    @GameTest
    public void survivalRahmenKostenItemsUndTiereFehlen(GameTestHelper h) {
        var plan = framed(h);
        BlockPos center = h.absolutePos(new BlockPos(4, 1, 4));
        var job = placeFramed(h, plan, center);
        var bot = h.spawn(CompanionMod.COMPANION, new BlockPos(1, 1, 6));
        bot.setNoAi(true);
        bot.getInventory().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ITEM_FRAME, 1));
        var result = CompanionBuilder.spawnEntities(h.getLevel(), job, bot);
        h.assertValueEqual(result.spawned(), 1, "Rahmen aus dem Inventar");
        h.assertValueEqual(result.missing(), 1, "Schaf darf im Survival nicht erschaffen werden");
        h.assertValueEqual(result.emptyFrames(), 1, "Inhalt fehlte im Inventar");
        h.assertTrue(bot.findItem(net.minecraft.world.item.Items.ITEM_FRAME) < 0, "Rahmen nicht verbraucht");
        h.succeed();
    }

    /** Rahmen im Rahmen mit nur einem Rahmen im Inventar: einer hängt leer, kein zweiter entsteht aus dem Nichts. */
    @GameTest
    public void survivalRahmenImRahmenErschafftKeinItem(GameTestHelper h) {
        var plan = framed(h, "minecraft:item_frame");
        BlockPos center = h.absolutePos(new BlockPos(4, 1, 4));
        var job = placeFramed(h, plan, center);
        var bot = h.spawn(CompanionMod.COMPANION, new BlockPos(1, 1, 6));
        bot.setNoAi(true);
        bot.getInventory().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ITEM_FRAME, 1));
        var result = CompanionBuilder.spawnEntities(h.getLevel(), job, bot);
        h.assertValueEqual(result.spawned(), 1, "Rahmen aus dem Inventar");
        h.assertValueEqual(result.emptyFrames(), 1, "Für den Inhalt fehlte ein zweiter Rahmen");
        var frames = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class,
                new net.minecraft.world.phys.AABB(center).inflate(4));
        h.assertValueEqual(frames.size(), 1, "Rahmen fehlt");
        h.assertTrue(frames.get(0).getItem().isEmpty(), "Rahmeninhalt aus dem Nichts erschaffen");
        h.succeed();
    }

    @GameTest
    public void filterTrichterWerdenErstImAbschlussBefuellt(GameTestHelper h) {
        BlockPos at = h.absolutePos(new BlockPos(2, 1, 2));
        h.getLevel().setBlock(at, Blocks.HOPPER.defaultBlockState(), CompanionBuilder.PLACE_FLAGS);
        var job = CompanionBuilder.create(h.getLevel(), "filter", java.util.Map.of(at, Blocks.HOPPER.defaultBlockState()));
        CompoundTag data = new CompoundTag();
        var items = new net.minecraft.nbt.ListTag();
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "minecraft:chest"); stack.putInt("count", 41); stack.putByte("Slot", (byte) 0);
        items.add(stack);
        data.put("Items", items);
        job.data.put(at, data);
        var hopper = (net.minecraft.world.Container) h.getLevel().getBlockEntity(at);
        CompanionBuilder.applyData(h.getLevel(), job, at, true, false);
        h.assertTrue(hopper.isEmpty(), "Inhalt schon während des Baus eingefüllt");
        h.assertValueEqual(CompanionBuilder.applyContents(h.getLevel(), job), 1, "Abschluss füllt nicht");
        h.assertValueEqual(hopper.countItem(net.minecraft.world.item.Items.CHEST), 41, "Filter-Inhalt falsch");
        h.assertValueEqual(CompanionBuilder.applyContents(h.getLevel(), job), 0, "doppelt befüllt");
        hopper.clearContent();
        h.succeed();
    }

    /**
     * Litematica aus 1.19.4 (Datenversion 3337) mit negativer Regionsgröße: Entities zählen ab "Position" (pos1),
     * Block-Entities ab der kleinsten Ecke; altes Item-Format (Count als Byte) wird umgerechnet.
     */
    @GameTest
    public void alteLitematicMitNegativerRegionSetztTiereUndInhalteRichtig(GameTestHelper h) {
        CompoundTag region = new CompoundTag();
        CompoundTag position = new CompoundTag();
        position.putInt("x", 2); position.putInt("y", 0); position.putInt("z", 0);
        CompoundTag size = new CompoundTag();
        size.putInt("x", -3); size.putInt("y", 1); size.putInt("z", 1);
        region.put("Position", position);
        region.put("Size", size);
        var palette = new net.minecraft.nbt.ListTag();
        palette.add(state("minecraft:air", null));
        CompoundTag props = new CompoundTag();
        props.putString("facing", "north"); props.putString("triggered", "false");
        palette.add(state("minecraft:dispenser", props));
        palette.add(state("minecraft:stone", null));
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", new long[] {1L | 2L << 4}); // x0 Werfer, x1 Luft, x2 Stein (je 2 Bit)
        var tes = new net.minecraft.nbt.ListTag();
        CompoundTag te = new CompoundTag();
        te.putInt("x", 0); te.putInt("y", 0); te.putInt("z", 0);
        var items = new net.minecraft.nbt.ListTag();
        CompoundTag stack = new CompoundTag();
        stack.putByte("Slot", (byte) 0); stack.putString("id", "minecraft:arrow"); stack.putByte("Count", (byte) 5);
        items.add(stack);
        te.put("Items", items);
        tes.add(te);
        region.put("TileEntities", tes);
        var ents = new net.minecraft.nbt.ListTag();
        CompoundTag sheep = new CompoundTag();
        sheep.putString("id", "minecraft:sheep");
        sheep.put("Pos", doubles(-1.5, 0, 0.5)); // relativ zu Position x=2 -> Welt x 0.5
        ents.add(sheep);
        region.put("Entities", ents);
        CompoundTag regions = new CompoundTag();
        regions.put("alt", region);
        CompoundTag file = new CompoundTag();
        file.put("Regions", regions);
        file.putInt("MinecraftDataVersion", 3337);
        var plan = CompanionSchematics.loadLitematic(h.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK), "alt", file);
        h.assertTrue(plan.blocks().get(BlockPos.ZERO).is(Blocks.DISPENSER), "Werfer fehlt: " + plan.blocks().get(BlockPos.ZERO));
        h.assertValueEqual(plan.entities().size(), 1, "Schaf fehlt");
        h.assertTrue(Math.abs(plan.entities().get(0).pos().x - 0.5) < 1e-6, "Schaf versetzt: " + plan.entities().get(0).pos());
        var loaded = CompanionSchematics.blockData(plan.data().get(BlockPos.ZERO)).getListOrEmpty("Items");
        h.assertValueEqual(loaded.size(), 1, "Werferinhalt fehlt");
        h.assertValueEqual(loaded.getCompoundOrEmpty(0).getIntOr("count", 0), 5, "altes Item-Format nicht umgerechnet");
        h.succeed();
    }

    private static CompoundTag state(String name, CompoundTag props) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        if (props != null) t.put("Properties", props);
        return t;
    }

    /** MCEdit-.schematic (Zahlen-IDs, Block-Entity "Trap" = Werfer, Items mit Count/Damage). */
    @GameTest
    public void alteMcEditDateiWirdUmgerechnet(GameTestHelper h) {
        CompoundTag t = new CompoundTag();
        t.putShort("Width", (short) 2); t.putShort("Height", (short) 1); t.putShort("Length", (short) 1);
        t.putByteArray("Blocks", new byte[] {1, 23});   // Stein, Werfer
        t.putByteArray("Data", new byte[] {0, 3});      // Werfer nach Süden
        var tes = new net.minecraft.nbt.ListTag();
        CompoundTag te = new CompoundTag();
        te.putString("id", "Trap");
        te.putInt("x", 1); te.putInt("y", 0); te.putInt("z", 0);
        var items = new net.minecraft.nbt.ListTag();
        CompoundTag stack = new CompoundTag();
        stack.putByte("Slot", (byte) 4); stack.putString("id", "minecraft:diamond"); stack.putByte("Count", (byte) 3); stack.putShort("Damage", (short) 0);
        items.add(stack);
        te.put("Items", items);
        tes.add(te);
        t.put("TileEntities", tes);
        t.put("Entities", new net.minecraft.nbt.ListTag());
        var plan = CompanionSchematics.loadLegacy(h.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK), "alt", t);
        h.assertTrue(plan.blocks().get(BlockPos.ZERO).is(Blocks.STONE), "Stein fehlt: " + plan.blocks().get(BlockPos.ZERO));
        var dispenser = plan.blocks().get(new BlockPos(1, 0, 0));
        h.assertTrue(dispenser.is(Blocks.DISPENSER), "Werfer fehlt: " + dispenser);
        h.assertTrue(dispenser.getValue(net.minecraft.world.level.block.DispenserBlock.FACING) == net.minecraft.core.Direction.SOUTH,
                "Werfer falsch gedreht: " + dispenser);
        var loaded = CompanionSchematics.blockData(plan.data().get(new BlockPos(1, 0, 0))).getListOrEmpty("Items");
        h.assertValueEqual(loaded.size(), 1, "Werferinhalt fehlt");
        h.assertValueEqual(loaded.getCompoundOrEmpty(0).getStringOr("id", ""), "minecraft:diamond", "Item falsch");
        h.assertValueEqual(loaded.getCompoundOrEmpty(0).getIntOr("count", 0), 3, "Anzahl falsch");
        h.succeed();
    }

    /** Comparator auf einem Beobachter (der als Energiequelle spät kommt): erst der Beobachter, sonst fällt er ab. */
    @GameTest
    public void haltBrauchendeBloeckeKommenNachIhremUntergrund(GameTestHelper h) {
        BlockPos base = h.absolutePos(new BlockPos(2, 1, 2));
        var blocks = new java.util.HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        blocks.put(base, Blocks.OBSERVER.defaultBlockState().setValue(net.minecraft.world.level.block.ObserverBlock.FACING, net.minecraft.core.Direction.UP));
        blocks.put(base.above(), Blocks.COMPARATOR.defaultBlockState());
        blocks.put(base.east().above(), Blocks.COMPARATOR.defaultBlockState());
        blocks.put(base.east(), Blocks.STONE.defaultBlockState());
        var job = CompanionBuilder.create("halt", blocks);
        int observer = -1, comparator = -1;
        for (int i = 0; i < job.order.size(); i++) {
            if (job.order.get(i).pos().equals(base)) observer = i;
            if (job.order.get(i).pos().equals(base.above())) comparator = i;
        }
        h.assertTrue(observer >= 0 && comparator > observer, "Comparator vor seinem Beobachter: " + comparator + " < " + observer);
        // wie beim Bau: ohne Updates setzen, der Nachbar-Comparator darf den ersten nicht abwerfen
        for (var p : job.order) h.getLevel().setBlock(p.pos(), p.state(), CompanionBuilder.PLACE_FLAGS);
        h.assertTrue(h.getLevel().getBlockState(base.above()).is(Blocks.COMPARATOR), "Comparator abgefallen");
        h.assertTrue(h.getLevel().getBlockState(base.east().above()).is(Blocks.COMPARATOR), "zweiter Comparator abgefallen");
        h.succeed();
    }

    /** Die echte Datei aus dem Fehlerbericht (204x106x196, ~387.000 Blöcke), falls sie in run/schematics liegt. */
    @GameTest(maxTicks = 400)
    public void hugeStorageRoomLaedtUndPasstInsPaket(GameTestHelper h) {
        Path file = null;
        for (var dir = Path.of("").toAbsolutePath(); dir != null && file == null; dir = dir.getParent()) {
            var f = dir.resolve("run").resolve("schematics").resolve("Huge Storage Room.litematic");
            if (Files.exists(f)) file = f;
        }
        if (file == null) { LogUtils.getLogger().info("Huge Storage Room fehlt, übersprungen"); h.succeed(); return; }
        CompanionSchematics.Schematic plan;
        try { plan = CompanionSchematics.load(h.getLevel(), file); }
        catch (Exception e) { h.fail("Huge Storage Room lässt sich nicht laden: " + e); return; }
        h.assertTrue(plan.blocks().size() > 300_000, "zu wenige Blöcke: " + plan.blocks().size());
        var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            CompanionBuildPreview.CODEC.encode(buf, new CompanionBuildPreview(1, java.util.UUID.randomUUID(),
                    net.minecraft.network.chat.Component.literal("Huge"), plan));
            int bytes = buf.readableBytes();
            LogUtils.getLogger().info("Huge Storage Room: {}x{}x{}, {} Blöcke, {} Block-Daten, {} Lebewesen, Paket {} Bytes",
                    plan.sizeX(), plan.sizeY(), plan.sizeZ(), plan.blocks().size(), plan.data().size(), plan.entities().size(), bytes);
            h.assertTrue(bytes < CompanionSchematics.MAX_PREVIEW_PACKET_BYTES, "Paket zu groß: " + bytes);
            h.assertValueEqual(CompanionBuildPreview.CODEC.decode(buf).plan().blocks().size(), plan.blocks().size(), "Paket verliert Blöcke");
        } finally { buf.release(); }
        var placed = CompanionSchematics.placeFull(plan, h.absolutePos(new BlockPos(1, 1, 1)),
                net.minecraft.core.Direction.SOUTH, Rotation.NONE, false);
        var job = CompanionBuilder.create("huge", placed.blocks());
        h.assertValueEqual(job.total(), plan.blocks().size(), "Auftrag verliert Blöcke");
        long frames = plan.entities().stream().filter(e -> e.tag().getStringOr("id", "").equals("minecraft:item_frame")).count();
        long loose = plan.entities().stream().filter(e -> e.tag().getStringOr("id", "").equals("minecraft:item")).count();
        h.assertValueEqual((int) frames, 746, "Item-Rahmen fehlen im Plan");
        h.assertValueEqual((int) loose, 0, "herumliegende Items im Plan");
        long filters = plan.data().values().stream().filter(d -> !CompanionSchematics.blockData(d).getListOrEmpty("Items").isEmpty()).count();
        h.assertTrue(filters >= 746, "Filter-Trichter ohne Inhalt: " + filters);
        h.succeed();
    }
}
