package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/** Kisten sortieren ohne Itemverlust: Stapelgrenzen, Schreibfehler, Wiederherstellung, volle Kisten. */
public class ChestSortGameTests {

    /** Container mit eigener Stapelgrenze, der ab einem Zeitpunkt Schreibfehler auslöst oder Stapel kürzt. */
    static class TestContainer extends SimpleContainer {
        int limit = 99;
        /** Ab wie vielen weiteren Schreibvorgängen eine Ausnahme fliegt; negativ = nie. */
        int writesUntilFailure = -1;
        /** Jeder Schreibvorgang behält höchstens ein Item (auch beim Wiederherstellen). */
        boolean keepOnlyOne;

        TestContainer(int size) { super(size); }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (writesUntilFailure == 0) { // einmaliger Fehler; danach nimmt der Container wieder an
                writesUntilFailure = -1;
                throw new IllegalStateException("test write failure");
            }
            if (writesUntilFailure > 0) writesUntilFailure--;
            super.setItem(slot, keepOnlyOne && stack.getCount() > 1 ? stack.copyWithCount(1) : stack);
        }

        @Override
        public int getMaxStackSize() { return limit; }
    }

    private static ItemStack named(Item item, int count, String name) {
        ItemStack s = new ItemStack(item, count);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    private static ContainerRewrite.ItemTally tally(List<? extends Container> boxes) {
        return ContainerRewrite.ItemTally.of(ContainerRewrite.snapshot(boxes));
    }

    /** Zielbild: alles in umgekehrter Platzreihenfolge, damit jeder belegte Platz neu geschrieben wird. */
    private static ItemStack[][] reversed(List<? extends Container> boxes, List<ItemStack[]> before) {
        ItemStack[][] target = ContainerRewrite.emptyLayout(boxes);
        for (int b = 0; b < boxes.size(); b++)
            for (int i = 0; i < target[b].length; i++) target[b][i] = before.get(b)[target[b].length - 1 - i].copy();
        return target;
    }

    private static List<TestContainer> filled(int boxes, int size) {
        List<TestContainer> out = new ArrayList<>();
        Item[] items = {Items.IRON_PICKAXE, Items.BREAD, Items.IRON_INGOT, Items.OAK_LOG, Items.COBBLESTONE, Items.BONE};
        for (int b = 0; b < boxes; b++) {
            TestContainer c = new TestContainer(size);
            for (int i = 0; i < size - 2; i++) {
                Item item = items[(i + b) % items.length];
                int count = Math.min(item.getDefaultMaxStackSize(), 1 + (i * 5 + b) % 20);
                c.setItem(i, i % 4 == 0 ? named(item, count, "k-" + b + "-" + i) : new ItemStack(item, count));
            }
            out.add(c);
        }
        return out;
    }

    @GameTest
    public void rewriteRestoresEverythingAfterAWriteFailure(GameTestHelper h) {
        var boxes = filled(3, 9);
        var before = ContainerRewrite.snapshot(boxes);
        boxes.get(1).writesUntilFailure = 3; // mitten im zweiten Container
        var r = ContainerRewrite.apply(boxes, before, reversed(boxes, before));
        h.assertValueEqual(r.outcome(), ContainerRewrite.Outcome.RESTORED, "outcome");
        for (int b = 0; b < boxes.size(); b++)
            for (int i = 0; i < before.get(b).length; i++)
                h.assertTrue(ItemStack.matches(boxes.get(b).getItem(i), before.get(b)[i]),
                        "box " + b + " slot " + i + " not restored");
        h.succeed();
    }

    @GameTest
    public void rewriteRestoresWhenAContainerTruncatesStacks(GameTestHelper h) {
        // Kürzt genau einen Stapel stillschweigend (wie ein Container mit unerwarteter Grenze); das Rücklesen muss
        // das erkennen, die Wiederherstellung danach gelingt.
        var truncatingOnce = new TestContainer(9) {
            boolean armed;
            @Override public void setItem(int slot, ItemStack stack) {
                if (armed && stack.getCount() > 1) {
                    armed = false;
                    super.setItem(slot, stack.copyWithCount(1));
                } else super.setItem(slot, stack);
            }
        };
        var source = filled(1, 9).getFirst();
        for (int i = 0; i < 9; i++) truncatingOnce.setItem(i, source.getItem(i).copy());
        var boxes = List.<Container>of(filled(1, 9).getFirst(), truncatingOnce);
        var before = ContainerRewrite.snapshot(boxes);
        truncatingOnce.armed = true;
        var r = ContainerRewrite.apply(boxes, before, reversed(boxes, before));
        h.assertValueEqual(r.outcome(), ContainerRewrite.Outcome.RESTORED, "outcome");
        h.assertTrue(tally(boxes).equals(ContainerRewrite.ItemTally.of(before)), "items changed");
        h.succeed();
    }

    @GameTest
    public void failedRestoreIsReportedAndDropsOnlyTheMissingItems(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.setNoAi(true);
        var boxes = filled(2, 9);
        var before = ContainerRewrite.snapshot(boxes);
        boxes.get(0).keepOnlyOne = true; // schreibt und stellt wieder her, verliert aber jedes Mal den Rest
        var r = ContainerRewrite.apply(boxes, before, reversed(boxes, before));
        h.assertValueEqual(r.outcome(), ContainerRewrite.Outcome.RESTORE_FAILED, "outcome");
        h.assertTrue(!r.missing().isEmpty(), "missing items not listed");
        var msg = CompanionJobs.reportRewrite(c, h.getLevel(), r);
        h.assertTrue(msg != null, "failure not reported");
        var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(c.blockPosition()).inflate(4));
        List<ItemStack[]> after = new ArrayList<>(ContainerRewrite.snapshot(boxes));
        after.add(drops.stream().map(e -> e.getItem().copy()).toArray(ItemStack[]::new));
        h.assertTrue(ContainerRewrite.ItemTally.of(after).equals(ContainerRewrite.ItemTally.of(before)),
                "containers plus drops differ from the original contents");
        h.succeed();
    }

    @GameTest
    public void rewriteRefusesWhenContentsChangedOrTotalsDiffer(GameTestHelper h) {
        var boxes = filled(2, 9);
        var before = ContainerRewrite.snapshot(boxes);
        var target = reversed(boxes, before);
        boxes.get(1).setItem(8, new ItemStack(Items.DIAMOND)); // jemand hat etwas hineingelegt
        var stale = ContainerRewrite.apply(boxes, before, target);
        h.assertValueEqual(stale.outcome(), ContainerRewrite.Outcome.STALE, "changed contents");
        h.assertTrue(boxes.get(1).getItem(8).is(Items.DIAMOND), "foreign item touched");

        boxes.get(1).setItem(8, ItemStack.EMPTY);
        var wrong = reversed(boxes, before);
        wrong[0][0] = new ItemStack(Items.DIAMOND, 64);
        var mismatch = ContainerRewrite.apply(boxes, before, wrong);
        h.assertValueEqual(mismatch.outcome(), ContainerRewrite.Outcome.MISMATCH, "different totals");
        for (int b = 0; b < boxes.size(); b++)
            for (int i = 0; i < before.get(b).length; i++)
                h.assertTrue(ItemStack.matches(boxes.get(b).getItem(i), before.get(b)[i]), "written despite mismatch");
        h.succeed();
    }

    @GameTest
    public void placeSplitsStacksAtTheContainerLimit(GameTestHelper h) {
        var small = new TestContainer(5);
        small.limit = 16;
        var boxes = List.of(small);
        ItemStack[][] target = ContainerRewrite.emptyLayout(boxes);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 64);
        ContainerRewrite.placeAnywhere(boxes, target, cobble);
        h.assertTrue(cobble.isEmpty(), "64 cobblestone did not fit into 5 slots of 16");
        for (int i = 0; i < 4; i++) h.assertValueEqual(target[0][i].getCount(), 16, "slot " + i);
        h.assertTrue(target[0][4].isEmpty(), "fifth slot used");
        ItemStack more = new ItemStack(Items.COBBLESTONE, 20);
        ContainerRewrite.placeAnywhere(boxes, target, more);
        h.assertValueEqual(more.getCount(), 4, "rest that does not fit stays in the stack");
        h.succeed();
    }

    @GameTest
    public void writtenLayoutReadsBackUnderAContainerLimit(GameTestHelper h) {
        var small = new TestContainer(9);
        small.limit = 16;
        small.setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        small.setItem(3, new ItemStack(Items.COBBLESTONE, 16));
        small.setItem(5, new ItemStack(Items.COBBLESTONE, 10));
        var boxes = List.of(small);
        var before = ContainerRewrite.snapshot(boxes);
        ItemStack[][] target = ContainerRewrite.emptyLayout(boxes);
        ContainerRewrite.placeAnywhere(boxes, target, new ItemStack(Items.COBBLESTONE, 42));
        var r = ContainerRewrite.apply(boxes, before, target);
        h.assertValueEqual(r.outcome(), ContainerRewrite.Outcome.WRITTEN, "outcome");
        h.assertValueEqual(small.getItem(0).getCount(), 16, "slot 0");
        h.assertValueEqual(small.getItem(1).getCount(), 16, "slot 1");
        h.assertValueEqual(small.getItem(2).getCount(), 10, "slot 2");
        h.succeed();
    }

    /** Drei randvolle Fässer, nur Maximalstapel, alle Sorten und Komponenten: nichts verloren, nichts abgeworfen. */
    @GameTest
    public void sortByCategoryKeepsCompletelyFullBoxesWithMaxStacks(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.setNoAi(true);
        Item[] items = {Items.IRON_PICKAXE, Items.BREAD, Items.IRON_INGOT, Items.OAK_LOG, Items.WHEAT_SEEDS,
                Items.COBBLESTONE, Items.BONE, Items.GLASS, Items.ENDER_PEARL};
        List<Container> boxes = new ArrayList<>();
        for (int x : new int[]{1, 4, 7}) {
            var pos = h.absolutePos(new BlockPos(x, 1, 6));
            h.getLevel().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
            boxes.add(c.containerAt(h.getLevel(), pos));
        }
        for (int b = 0; b < boxes.size(); b++) {
            var box = boxes.get(b);
            for (int i = 0; i < box.getContainerSize(); i++) {
                Item item = items[(i * 2 + b) % items.length];
                int max = item.getDefaultMaxStackSize();
                box.setItem(i, i % 6 == 0 ? named(item, max, "m-" + b + "-" + i) : new ItemStack(item, max));
            }
        }
        var before = tally(boxes);
        var r = CompanionJobs.sortByCategory(c, h.getLevel(), c.blockPosition());
        h.assertTrue(!r.failed(), "sorting reported a failure: " + (r.failed() ? r.failure().getString() : ""));
        h.assertTrue(tally(boxes).equals(before), "items changed by sorting");
        var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(16));
        h.assertTrue(drops.isEmpty(), "sorting dropped " + drops.size() + " item entities");
        h.succeed();
    }
}
