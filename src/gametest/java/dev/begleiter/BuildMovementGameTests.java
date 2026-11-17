package dev.begleiter;

import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import dev.begleiter.CompanionEntity.BuildMovement;

/** Bau-Bewegung Laufen / Fliegen / Fliegen+Teleport: unabhängig vom Material-Modus, im Menü umschaltbar, gespeichert. */
public class BuildMovementGameTests {
    private static final String TALL = "begleiter-gametest:tall";

    private static CompanionEntity bot(GameTestHelper h, BlockPos at) {
        for (int x = 0; x < 40; x++) for (int z = 0; z < 40; z++) h.setBlock(x, 0, z, Blocks.SMOOTH_STONE);
        var bot = h.spawn(CompanionMod.COMPANION, at);
        bot.setPersistenceRequired();
        bot.stay();
        return bot;
    }

    /** Standard bleibt wie bisher: Kreativ fliegt, Survival läuft. */
    @GameTest
    public void ohneAuswahlFliegtKreativUndSurvivalLaeuft(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(1, 1, 1));
        c.buildModeOverride = true;
        h.assertTrue(c.buildMovement() == BuildMovement.FLY, "Kreativ fliegt nicht mehr");
        c.buildModeOverride = false;
        h.assertTrue(c.buildMovement() == BuildMovement.NORMAL, "Survival fliegt");
        h.succeed();
    }

    /** Survival mit FLIEGEN: Block 6 Blöcke über dem Boden ohne Gerüst, mit eigenem Material. */
    @GameTest(maxTicks = 600, structure = TALL)
    public void survivalMitFliegenSetztBlockHochInDerLuft(GameTestHelper h) {
        var c = bot(h, new BlockPos(10, 1, 10));
        c.buildModeOverride = false;
        c.buildMovementOverride = BuildMovement.FLY;
        c.giveToInventory(new ItemStack(Items.STONE, 1));
        BlockPos target = h.absolutePos(new BlockPos(14, 8, 14));
        c.build = CompanionBuilder.create(h.getLevel(), "Luft", Map.of(target, Blocks.STONE.defaultBlockState()));
        c.addTask(CompanionEntity.Task.build());
        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "Block in der Luft fehlt");
            h.assertValueEqual(CompanionCraft.count(c, "stone"), 0, "Material nicht verbraucht");
        });
    }

    /** TELEPORT: weite Baustelle (über 16 Blöcke) ist in wenigen Ticks erreicht statt nach langem Flug. */
    @GameTest(maxTicks = 60, structure = TALL)
    public void teleportSpringtZurWeitenBaustelle(GameTestHelper h) {
        var c = bot(h, new BlockPos(2, 1, 2));
        c.buildModeOverride = true;
        c.buildMovementOverride = BuildMovement.TELEPORT;
        BlockPos target = h.absolutePos(new BlockPos(30, 4, 30));
        c.build = CompanionBuilder.create(h.getLevel(), "Weit", Map.of(target, Blocks.STONE.defaultBlockState()));
        c.addTask(CompanionEntity.Task.build());
        h.succeedWhen(() -> h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "nicht hingesprungen"));
    }

    /** Menü: Knopf und Rastersymbol schalten Laufen → Fliegen → Teleport → Laufen; die Wahl übersteht Speichern. */
    @GameTest
    public void menueSchaltetBewegungWeiterUndSpeichertSie(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(1, 1, 1));
        c.setNoAi(true); c.stay(); c.buildModeOverride = false;
        @SuppressWarnings("removal") var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID()); p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        var menu = new CompanionMenu(1, p.getInventory(), c, CompanionMenu.Page.BUILD);
        p.containerMenu = menu;
        h.assertValueEqual(menu.snapshot().buildMovement(), 0, "startet nicht mit Laufen");
        h.assertTrue(menu.clickMenuButton(p, CompanionMenu.BUILD_MOVEMENT_TOGGLE), "Knopf nicht verarbeitet");
        h.assertTrue(c.buildMovement() == BuildMovement.FLY && menu.snapshot().buildMovement() == 1, "nicht auf Fliegen");
        c.tickCount++;
        menu.clicked(4, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p); // Symbol im Raster
        h.assertTrue(c.buildMovement() == BuildMovement.TELEPORT, "Rastersymbol schaltet nicht weiter");

        var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        c.saveWithoutId(out);
        var copy = CompanionMod.COMPANION.create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
        copy.load(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertTrue(copy.buildMovementOverride == BuildMovement.TELEPORT, "Bewegung nach dem Laden verloren");

        menu.clickMenuButton(p, CompanionMenu.BUILD_MOVEMENT_TOGGLE);
        h.assertTrue(c.buildMovement() == BuildMovement.GHOST, "nicht auf Geist");
        menu.clickMenuButton(p, CompanionMenu.BUILD_MOVEMENT_TOGGLE);
        h.assertTrue(c.buildMovement() == BuildMovement.NORMAL, "nicht zurück auf Laufen");
        h.succeed();
    }

    /** Fremd-Schematics liegen nur im privaten Katalog; im öffentlichen Export fehlen sie (Lizenz). Dann null. */
    private static java.nio.file.Path schematicFile(String name) {
        var file = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(CompanionMod.MOD_ID)
                .flatMap(mod -> mod.findPath("data/begleiter/bundled_schematics/" + name)).orElse(null);
        if (file != null && java.nio.file.Files.isRegularFile(file)) return file;
        com.mojang.logging.LogUtils.getLogger().info("[GameTest] skipped: {} is not bundled in this build", name);
        return null;
    }

    /**
     * Aktuelle gebündelte Eisenfarm: die drei gespeicherten Dorfbewohner werden nach dem Kreativ-Bau übernommen.
     */
    @GameTest(maxTicks = 300, structure = TALL)
    public void eisenfarmUebernimmtGespeicherteDorfbewohner(GameTestHelper h) {
        var file = schematicFile("Kompakte Eisenfarm mit Zombieboot.litematic");
        if (file == null) { h.succeed(); return; }
        CompanionSchematics.Schematic plan;
        try { plan = CompanionSchematics.load(h.getLevel(), file); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
        long villagers = plan.entities().stream().filter(e -> "minecraft:villager".equals(e.tag().getStringOr("id", ""))).count();
        h.assertValueEqual(villagers, 3L, "gespeicherte Dorfbewohner");

        // Platzierung trägt die Lebewesen in den Weltplan
        var survival = CompanionSchematics.placeFull(plan, h.absolutePos(new BlockPos(30, 1, 2)), net.minecraft.core.Direction.SOUTH, net.minecraft.world.level.block.Rotation.NONE, false);
        h.assertValueEqual(survival.entities().stream().filter(e -> "minecraft:villager".equals(e.tag().getStringOr("id", ""))).count(),
                3L, "Dorfbewohner nicht in den Plan übernommen");

        var c = bot(h, new BlockPos(3, 1, 3));
        c.buildModeOverride = true;
        c.buildMovementOverride = BuildMovement.GHOST;
        var placed = CompanionSchematics.placeFull(plan, h.absolutePos(new BlockPos(14, 1, 10)), net.minecraft.core.Direction.SOUTH, net.minecraft.world.level.block.Rotation.CLOCKWISE_90, false);
        c.build = CompanionBuilder.create(h.getLevel(), "eisenfarm", placed.blocks());
        c.build.data.putAll(placed.data());
        c.build.entities.addAll(placed.entities());
        c.addTask(CompanionEntity.Task.build());
        var box = new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).inflate(48);
        h.succeedWhen(() -> {
            h.assertTrue(c.build == null, "Bau nicht fertig");
            var list = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, box);
            h.assertValueEqual(list.size(), 3, "Dorfbewohner");
            for (var v : list) {
                h.assertTrue(v.isPersistenceRequired(), "verschwindet wieder");
            }
        });
    }

    /** GEIST mit echtem Schematic über die Vorschau-Bestätigung, Bot folgt dem Besitzer: muss in wenigen Sekunden stehen. */
    @GameTest(maxTicks = 200, structure = TALL)
    public void geistBautEchteSchematicUeberVorschauSchnell(GameTestHelper h) {
        var file = schematicFile("Kompakte Moos-Knochenmehlfarm.litematic");
        if (file == null) { h.succeed(); return; }
        CompanionSchematics.Schematic plan;
        try { plan = CompanionSchematics.load(h.getLevel(), file); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
        var c = bot(h, new BlockPos(3, 1, 3));
        c.buildModeOverride = true;
        c.buildMovementOverride = BuildMovement.GHOST;
        @SuppressWarnings("removal") var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID()); p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        c.follow();
        var preview = CompanionPlacement.selectSchematic(p, c, plan, false, net.minecraft.world.level.block.Rotation.NONE);
        BlockPos center = h.absolutePos(new BlockPos(20, 1, 20));
        var result = CompanionPlacement.confirm(p, new CompanionPlacement.Confirm(preview.token(), center, 0, false, BuildMovement.GHOST.ordinal()));
        h.assertTrue(result.finished() && c.build != null, "nicht gestartet: " + result.message().getString());
        int start = h.getTick() == 0 ? 0 : (int) h.getTick();
        h.succeedWhen(() -> {
            h.assertTrue(c.build == null || c.build.index >= c.build.total(),
                    "Geist zu langsam: " + (c.build == null ? "?" : c.build.index + "/" + c.build.total())
                            + " nach " + (h.getTick() - start) + " Ticks, Bewegung " + c.buildMovement());
        });
    }

    /**
     * GEIST: eingemauert (Glaskasten) zu einer Baustelle hinter der Wand und 30 Blöcke in kurzer Zeit setzen;
     * danach steckt er nicht im Block.
     */
    @GameTest(maxTicks = 60, structure = TALL)
    public void geistFliegtDurchWaendeUndBautSofort(GameTestHelper h) {
        BlockPos start = new BlockPos(3, 1, 3);
        for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++)
            if (dx != 0 || dz != 0 || dy == 2) h.setBlock(start.offset(dx, dy, dz), Blocks.GLASS);
        var c = bot(h, start);
        c.buildModeOverride = true;
        c.buildMovementOverride = BuildMovement.GHOST;
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> plan = new java.util.HashMap<>();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 6; z++)
            plan.put(h.absolutePos(new BlockPos(20 + x, 1, 20 + z)), Blocks.STONE.defaultBlockState());
        c.build = CompanionBuilder.create(h.getLevel(), "Geist", plan);
        c.addTask(CompanionEntity.Task.build());
        h.succeedWhen(() -> {
            for (BlockPos p : plan.keySet())
                h.assertTrue(h.getLevel().getBlockState(p).is(Blocks.STONE), "fehlt: " + p.toShortString());
            h.assertTrue(c.build == null || c.build.index >= c.build.total(), "Auftrag nicht fertig");
        });
    }
}
