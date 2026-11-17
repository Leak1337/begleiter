package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;

/** MOVE_TO, GIVE und PLACE über Handler – Endstatus, Buchung erst nach Erfolg und Queue-Ersetzung. */
public class TaskHandlerGameTests {
    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    private static CompanionEntity companion(GameTestHelper h, BlockPos rel) {
        floor(h);
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, rel);
        c.setPersistenceRequired();
        c.stay();
        return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID());
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        return p;
    }

    private static int dropped(GameTestHelper h, net.minecraft.world.item.Item item) {
        int n = 0;
        AABB area = new AABB(h.absolutePos(BlockPos.ZERO)).inflate(16);
        for (ItemEntity ie : h.getLevel().getEntitiesOfClass(ItemEntity.class, area, ie -> ie.getItem().is(item)))
            n += ie.getItem().getCount();
        return n;
    }

    @GameTest(maxTicks = 200)
    public void placeSetztBlockUndVerbrauchtGenauEinen(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.giveToInventory(new ItemStack(Items.DIRT, 3));
        BlockPos target = h.absolutePos(new BlockPos(4, 1, 4));
        c.addTask(Task.place(target, Blocks.DIRT));
        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.DIRT), "Erde nicht gesetzt");
            h.assertValueEqual(c.taskCount(), 0, "Aufgabe offen");
            h.assertValueEqual(CompanionCraft.count(c, "dirt"), 2, "Erde im Inventar");
        });
    }

    /** Eine Wand gegenüber dem einzigen Ausgang darf den Bauenden nicht in der nächsten Setzstelle einsperren. */
    @GameTest(maxTicks = 100)
    public void buildWeichtAusEngerNischeAufFreienStandplatzAus(GameTestHelper h) {
        var c = companion(h, new BlockPos(3, 1, 3));
        c.buildModeOverride = false;
        for (int x = 2; x <= 6; x++) for (int y = 1; y <= 3; y++) {
            h.setBlock(x, y, 2, Blocks.BEDROCK);
            h.setBlock(x, y, 4, Blocks.BEDROCK);
        }
        for (int z = 0; z < 8; z++) for (int y = 1; y <= 5; y++) h.setBlock(2, y, z, Blocks.BEDROCK);
        BlockPos target = h.absolutePos(new BlockPos(3, 1, 3));
        // Die nächste Richtung weg vom Ziel ist eine Wand. Der einzige Ausgang liegt im Osten.
        c.snapTo(target.getX() + 0.25, target.getY(), target.getZ() + 0.5, 0, 0);
        c.giveToInventory(new ItemStack(Items.STONE));
        c.build = CompanionBuilder.create(h.getLevel(), "Nische", java.util.Map.of(target, Blocks.STONE.defaultBlockState()));
        c.addTask(Task.build());
        h.succeedWhen(() -> {
            h.assertTrue(c.build == null && h.getLevel().getBlockState(target).is(Blocks.STONE),
                    "Begleiter weicht nicht zum freien Standplatz aus: " + c.position());
            h.assertTrue(!c.getBoundingBox().intersects(new AABB(target)), "Begleiter hat sich eingemauert");
            h.assertValueEqual(CompanionCraft.count(c, "stone"), 0, "Material nicht genau einmal verbraucht");
            h.assertTrue(h.getBlockState(new BlockPos(2, 1, 3)).is(Blocks.BEDROCK), "Geschützte Wand verändert");
        });
    }

    /** Der Standplatz darf nicht als erreicht gelten, solange der Bau noch außer Reichweite ist. */
    @GameTest(maxTicks = 100)
    public void buildErreichtStandplatzOhneZehnSekundenPause(GameTestHelper h) {
        floor(h);
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, new BlockPos(6, 1, 6));
        c.setPersistenceRequired();
        c.stay();
        c.buildModeOverride = false;
        c.giveToInventory(new ItemStack(Items.REDSTONE_LAMP));
        c.giveToInventory(new ItemStack(Items.REDSTONE));
        c.giveToInventory(new ItemStack(Items.LEVER));
        BlockPos target = h.absolutePos(new BlockPos(3, 1, 2));
        var lever = Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                .setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true);
        c.build = CompanionBuilder.create(h.getLevel(), "Standplatz", java.util.Map.of(
                target, Blocks.REDSTONE_LAMP.defaultBlockState(),
                target.west(), Blocks.REDSTONE_WIRE.defaultBlockState(), target.west(2), lever));
        c.addTask(Task.build());
        h.succeedWhen(() -> {
            h.assertTrue(c.build == null, "Bau wartet vor dem Standplatz: " + c.position() + " " + c.nav.debugText());
            h.assertTrue(h.getLevel().getBlockState(target).getValue(
                    net.minecraft.world.level.block.RedstoneLampBlock.LIT), "Lampe leuchtet nicht");
            h.assertValueEqual(CompanionCraft.count(c, "redstone_lamp"), 0, "Materialverbrauch");
            h.assertValueEqual(CompanionCraft.count(c, "redstone"), 0, "Redstone-Verbrauch");
            h.assertValueEqual(CompanionCraft.count(c, "lever"), 0, "Hebel-Verbrauch");
        });
    }

    @GameTest(maxTicks = 100)
    public void placeAufBelegteStelleGibtAufOhneVerbrauch(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.giveToInventory(new ItemStack(Items.DIRT, 3));
        BlockPos target = h.absolutePos(new BlockPos(4, 1, 4));
        h.getLevel().setBlockAndUpdate(target, Blocks.OAK_PLANKS.defaultBlockState());
        c.addTask(Task.place(target, Blocks.DIRT));
        h.succeedWhen(() -> {
            h.assertValueEqual(c.taskCount(), 0, "Aufgabe offen");
            h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.OAK_PLANKS), "Belegter Block verändert");
            h.assertValueEqual(CompanionCraft.count(c, "dirt"), 3, "Erde verbraucht");
        });
    }

    @GameTest(maxTicks = 200)
    public void giveWirftGenauDieAngefragteMengeAb(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        owner(h, c);
        c.giveToInventory(new ItemStack(Items.DIRT, 5));
        c.addTask(Task.give("item:dirt", 3));
        h.succeedWhen(() -> {
            h.assertValueEqual(c.taskCount(), 0, "Aufgabe offen");
            h.assertValueEqual(CompanionCraft.count(c, "dirt"), 2, "Erde im Inventar");
            h.assertValueEqual(dropped(h, Items.DIRT), 3, "abgeworfene Erde");
        });
    }

    @GameTest(maxTicks = 100)
    public void giveOhneBesitzerOderVorratEndetOhneAbwurf(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.giveToInventory(new ItemStack(Items.DIRT, 5));
        c.addTask(Task.give("item:dirt", 3)); // kein Besitzer
        h.runAfterDelay(20, () -> {
            h.assertValueEqual(c.taskCount(), 0, "GIVE ohne Besitzer hängt");
            owner(h, c);
            c.addTask(Task.give("item:diamond", 1)); // nichts davon dabei
        });
        h.runAfterDelay(60, () -> {
            h.assertValueEqual(c.taskCount(), 0, "GIVE ohne Vorrat hängt");
            h.assertValueEqual(CompanionCraft.count(c, "dirt"), 5, "Erde abgegeben");
            h.assertValueEqual(dropped(h, Items.DIRT), 0, "Erde abgeworfen");
            h.succeed();
        });
    }

    /** Regression: TREE fand wegen eines überlaufenden Tick-Sentinels keine Stämme und war sofort "fertig". */
    @GameTest(maxTicks = 400)
    public void treeFaelltDenGanzenStamm(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        BlockPos base = new BlockPos(4, 1, 4);
        for (int y = 0; y < 3; y++) h.setBlock(base.above(y), Blocks.OAK_LOG);
        c.addTask(Task.tree(h.absolutePos(base), 3));
        h.succeedWhen(() -> {
            for (int y = 0; y < 3; y++)
                h.assertTrue(!h.getLevel().getBlockState(h.absolutePos(base.above(y))).is(Blocks.OAK_LOG), "Stamm steht noch bei y+" + y);
            h.assertValueEqual(c.taskCount(), 0, "Aufgabe offen");
        });
    }

    @GameTest(maxTicks = 400)
    public void vorgezogeneAufgabeUnterbrichtMoveToUndSetztDanachFort(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.DIRT, 1));
        BlockPos far = h.absolutePos(new BlockPos(6, 1, 6));
        BlockPos place = h.absolutePos(new BlockPos(1, 1, 4));
        Task move = Task.moveTo(far, 1);
        c.addTask(move);
        h.runAfterDelay(5, () -> {
            h.assertTrue(c.currentTask() == move, "MOVE_TO nicht aktiv");
            c.pushFront(Task.place(place, Blocks.DIRT)); // ersetzt die vorderste Aufgabe
        });
        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getBlockState(place).is(Blocks.DIRT), "vorgezogene Aufgabe nicht erledigt");
            h.assertValueEqual(c.taskCount(), 0, "MOVE_TO nicht fortgesetzt");
            h.assertTrue(c.blockPosition().distSqr(far) <= 2, "nicht am Ziel: " + c.blockPosition());
        });
    }
}
