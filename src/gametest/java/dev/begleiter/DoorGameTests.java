package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;

/** GameTests für Türen: einsetzen, herstellen, hindurchgehen und hinter sich schließen. */
public class DoorGameTests {

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    private static CompanionEntity companion(GameTestHelper h, BlockPos rel) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, rel);
        c.setPersistenceRequired();
        return c;
    }

    @GameTest
    public void tuerEinsetzenBeideHaelften(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.SPRUCE_DOOR));
        BlockPos at = h.absolutePos(new BlockPos(4, 1, 4));
        int slot = CompanionDoors.doorSlot(c);
        h.assertTrue(slot >= 0, "Tür im Inventar nicht gefunden");
        h.assertTrue(CompanionDoors.place(h.getLevel(), c, at, Direction.NORTH, slot), "Tür nicht gesetzt");
        var lower = h.getLevel().getBlockState(at);
        var upper = h.getLevel().getBlockState(at.above());
        h.assertTrue(lower.is(Blocks.SPRUCE_DOOR) && lower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER, "Untere Hälfte fehlt");
        h.assertTrue(upper.is(Blocks.SPRUCE_DOOR) && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER, "Obere Hälfte fehlt");
        h.assertValueEqual(lower.getValue(DoorBlock.FACING), Direction.NORTH, "Richtung");
        h.assertValueEqual(CompanionDoors.doorSlot(c), -1, "Tür nicht verbraucht");
        h.assertTrue(CompanionDoors.setOpen(h.getLevel(), c, at.above(), true), "Öffnen über die obere Hälfte");
        h.assertTrue(CompanionDoors.isOpen(h.getLevel(), at), "Tür nicht offen");
        h.assertValueEqual(CompanionDoors.lower(h.getLevel(), at.above()), at, "Untere Hälfte bestimmen");
        h.succeed();
    }

    /** 64 Stämme werden zu 256 Brettern – nicht zu einem auf 64 gekappten Stapel (dann fehlte Holz für die Tür). */
    @GameTest
    public void stammeWerdenZuAllenBrettern(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 64));
        h.assertValueEqual(CompanionCraft.convertLogsToPlanks(c, Integer.MAX_VALUE), 256, "Gemeldete Bretter");
        h.assertValueEqual(CompanionCraft.count(c, "oak_planks"), 256, "Bretter im Inventar");
        h.assertValueEqual(CompanionCraft.count(c, "#logs"), 0, "Stämme übrig");
        h.succeed();
    }

    @GameTest
    public void eisentuerSetztErNicht(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.IRON_DOOR));
        h.assertValueEqual(CompanionDoors.doorSlot(c), -1, "Eisentür als Holztür gezählt");
        h.assertTrue(!CompanionDoors.canPlace(h.getLevel(), h.absolutePos(new BlockPos(3, 0, 3))), "Tür im Boden erlaubt");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void stelltTuerHerUndSetztSieEin(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.BIRCH_PLANKS, 8));
        c.giveToInventory(new ItemStack(Items.CRAFTING_TABLE));
        BlockPos at = h.absolutePos(new BlockPos(5, 1, 5));
        c.addTask(Task.door(at, "north"));
        h.succeedWhen(() -> {
            h.assertTrue(CompanionDoors.isDoor(h.getLevel(), at), "Keine Tür eingesetzt");
            h.assertTrue(c.currentTask() == null, "Aufgabe läuft noch");
            h.assertValueEqual(CompanionCraft.count(c, "oak_door"), 2, "Restliche Türen (3 hergestellt, 1 gesetzt)");
        });
    }

    /** Haus aus Stämmen und Bruchstein: am Ende steckt eine Tür in der Öffnung (Testgelände: er setzte keine). */
    @GameTest(maxTicks = 2400, structure = "begleiter-gametest:wide")
    public void hausBekommtEineTuer(GameTestHelper h) {
        for (int x = 0; x < 12; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(9, 1, 6));
        c.stay();
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 64));
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 64));
        BlockPos site = h.absolutePos(new BlockPos(1, 1, 1));
        c.addTask(Task.house(site));
        BlockPos door = site.offset(2, 1, 0);
        h.succeedWhen(() -> {
            h.assertTrue(!c.hasTasks(), "Noch bei der Arbeit: " + c.currentTaskText() + " (Bretter "
                    + CompanionCraft.count(c, "#planks") + ", Stämme " + CompanionCraft.count(c, "#logs") + ")");
            h.assertTrue(CompanionDoors.isDoor(h.getLevel(), door), "Keine Tür im Haus (dort: "
                    + h.getLevel().getBlockState(door).getBlock().getName().getString() + " / "
                    + h.getLevel().getBlockState(door.above()).getBlock().getName().getString() + ", darunter: "
                    + h.getLevel().getBlockState(door.below()).getBlock().getName().getString() + ", Tür bei "
                    + door.toShortString() + ", Haus bei " + c.getHouseOrigin() + ", Problem: " + c.problemText
                    + ", Bretter " + CompanionCraft.count(c, "#planks") + ", Stämme " + CompanionCraft.count(c, "#logs") + ")");
        });
    }

    @GameTest(maxTicks = 400)
    public void gehtDurchTuerUndSchliesstSie(GameTestHelper h) {
        floor(h);
        // Wand quer durch den Testbereich, nur eine (geschlossene) Holztür
        for (int x = 0; x < 8; x++)
            for (int y = 1; y <= 3; y++) h.setBlock(x, y, 4, Blocks.OBSIDIAN);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 1));
        c.giveToInventory(new ItemStack(Items.OAK_DOOR));
        h.setBlock(3, 1, 4, Blocks.AIR);
        h.setBlock(3, 2, 4, Blocks.AIR);
        BlockPos door = h.absolutePos(new BlockPos(3, 1, 4));
        h.assertTrue(CompanionDoors.place(h.getLevel(), c, door, Direction.NORTH, CompanionDoors.doorSlot(c)), "Testaufbau: Tür");
        BlockPos goal = h.absolutePos(new BlockPos(3, 1, 7));
        c.stay();
        c.addTask(Task.moveTo(goal, 1));
        boolean[] wasOpen = {false};
        h.onEachTick(() -> { if (CompanionDoors.isOpen(h.getLevel(), door)) wasOpen[0] = true; });
        h.succeedWhen(() -> {
            h.assertTrue(c.blockPosition().getZ() >= goal.getZ() - 1, "Nicht durch die Tür gegangen");
            h.assertTrue(wasOpen[0], "Tür nie geöffnet");
            h.assertTrue(CompanionDoors.isDoor(h.getLevel(), door), "Tür abgebaut statt geöffnet");
            h.assertTrue(!CompanionDoors.isOpen(h.getLevel(), door), "Tür hinter sich nicht geschlossen");
        });
    }
}
