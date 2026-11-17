package dev.begleiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.debug.BotDebug.EventKind;
import dev.begleiter.nav.Goal;
import dev.begleiter.task.MiningKnowledge;
import dev.begleiter.task.TaskContext;
import dev.begleiter.task.TaskHandler;
import dev.begleiter.task.TaskHandlers;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Echte Handler-Ausgaben und Nebenwirkungen, mit Kontext in Reichweite und echten Welt-/Inventar-Schnittstellen. */
public class HandlerTextGameTests {
    static final class Speaker extends CompanionEntity {
        final List<Component> messages = new ArrayList<>();
        Speaker(ServerLevel level) { super(CompanionMod.COMPANION, level); }
        @Override public void tell(Component message) { messages.add(message.copy()); }
    }
    static Speaker companion(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = new Speaker(h.getLevel()); var pos = h.absolutePos(new BlockPos(2, 1, 2));
        c.snapTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0); h.getLevel().addFreshEntity(c);
        c.stay(); c.setNoAi(true); c.buildModeOverride = false; c.setOnGround(true); return c;
    }
    static class Nearby implements TaskContext {
        final CompanionEntity c; final ServerLevel world; final MiningKnowledge mining;
        Nearby(CompanionEntity c, ServerLevel world) { this.c = c; this.world = world; mining = new MiningKnowledge(c); }
        public CompanionEntity mob() { return c; }
        public ServerLevel level() { return world; }
        public boolean approach(BlockPos p, double range) { return c.position().distanceToSqr(Vec3.atCenterOf(p)) <= range; }
        public boolean approachEntity(Entity e, double range) { return c.distanceToSqr(e) <= range; }
        public boolean approachGoal(Goal goal, BlockPos key) { return goal.isInGoal(c.getBlockX(), c.getBlockY(), c.getBlockZ()); }
        public boolean useNewPathing(BlockPos p) { return false; }
        public void trackMove() { }
        public void resetStuck() { }
        public void capStuck(int max) { }
        public boolean grabPassingOres(Task task) { return false; }
        public boolean guardedSupply() { return false; } // These fixtures are explicit player tasks.
        public void abortAutonomousPlan(dev.begleiter.ai.DecisionOutcome.Reason reason) { throw new AssertionError("Player task became autonomous: " + reason); }
        public void failChain() { throw new AssertionError("Unexpected chain failure"); }
        public MiningKnowledge mining() { return mining; }
    }
    static TaskHandler handler(CompanionEntity c, Task task, TaskContext context) {
        c.addTask(task); var handler = TaskHandlers.create(task); handler.start(context); return handler;
    }
    public static void translated(GameTestHelper h, Component text, String de, String en) throws Exception {
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        Component back;
        try {
            ComponentSerialization.STREAM_CODEC.encode(buffer, text); back = ComponentSerialization.STREAM_CODEC.decode(buffer);
            h.assertValueEqual(buffer.readableBytes(), 0, "Unread handler component bytes");
        } finally { buffer.release(); }
        h.assertTrue(back.getContents() instanceof TranslatableContents, "Handler output flattened");
        ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(back.getString().contains(de), back.getString()));
        ServerTextGameTests.inLanguage("en_us", () -> {
            h.assertTrue(back.getString().contains(en), back.getString());
            h.assertTrue(!back.getString().contains("server.begleiter."), "Missing handler translation");
        });
    }

    @GameTest
    public void guardedWithdrawalPreservesUnsafeAbortAndTranslatedRouteReason(GameTestHelper h) throws Exception {
        var c = companion(h); var chestPos = h.absolutePos(new BlockPos(4, 1, 2));
        h.getLevel().setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        var chest = (Container) h.getLevel().getBlockEntity(chestPos); chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        c.memory.remember(CompanionMemory.Kind.DANGER, "Custom danger %s Ä", h.getLevel(), chestPos);
        var aborted = new java.util.concurrent.atomic.AtomicReference<dev.begleiter.ai.DecisionOutcome.Reason>();
        var ctx = new Nearby(c, h.getLevel()) {
            @Override public boolean guardedSupply() { return true; }
            @Override public void abortAutonomousPlan(dev.begleiter.ai.DecisionOutcome.Reason reason) { aborted.set(reason); }
        };
        var job = handler(c, Task.take(chestPos, "iron_ingot", 3), ctx); var at = c.position();
        h.assertTrue(job.tick(ctx) == TaskHandler.Status.REPLACED
                && aborted.get() == dev.begleiter.ai.DecisionOutcome.Reason.ROUTE_UNSAFE, "Unsafe supply route changed its abort reason/status");
        h.assertTrue(c.usedSlots() == 0 && chest.getItem(0).getCount() == 5 && c.position().equals(at)
                && c.nav.executor() == null, "Rejected route moved companion, installed a path or took items");
        translated(h, c.getLog(0, 1, false).getFirst().textComponent(), "bekannte Gefahr am Ziel", "known danger at the destination");
        job.stop(ctx); h.succeed();
    }

    @GameTest
    public void miningKeepsNativeBlockToolAndTagBasedEventIdentity(GameTestHelper h) throws Exception {
        var c = companion(h); var ctx = new Nearby(c, h.getLevel()); var pos = h.absolutePos(new BlockPos(4, 1, 2));
        c.giveToInventory(new ItemStack(Items.IRON_PICKAXE)); h.getLevel().setBlock(pos, Blocks.IRON_ORE.defaultBlockState(), 3);
        var handler = handler(c, Task.breakAt(pos, Blocks.IRON_ORE), ctx);
        var status = handler.tick(ctx);
        h.assertTrue(status == TaskHandler.Status.RUNNING && h.getLevel().getBlockState(pos).is(Blocks.IRON_ORE), "Mining completed before its work time");
        var entry = c.getLog(0, 1, false).getFirst();
        h.assertTrue(entry.kind() == EventKind.LOOT, "Ore tag not reflected in event metadata");
        var args = ((TranslatableContents) entry.textComponent().getContents()).getArgs();
        h.assertTrue(args[0] instanceof Component block && block.getContents() instanceof TranslatableContents
                && args[1] instanceof Component tool && tool.getContents() instanceof TranslatableContents, "Native block/tool names flattened");
        translated(h, entry.textComponent(), "Abbau:", "Mining:");
        for (int tick = 0; status == TaskHandler.Status.RUNNING && tick < 100; tick++) { c.tickCount++; status = handler.tick(ctx); }
        handler.stop(ctx);
        h.assertTrue(status == TaskHandler.Status.DONE && h.getLevel().getBlockState(pos).isAir(), "Translated mining did not finish");
        h.assertValueEqual(CompanionCraft.count(c, "raw_iron"), 1, "Mining changed the actual drop count");
        c.cancelOrders(); c.clearLog(); h.getLevel().setBlock(pos, RegistryIdTestItems.COPPER_ORE.defaultBlockState(), 3);
        var foreign = handler(c, Task.breakAt(pos, RegistryIdTestItems.COPPER_ORE), ctx); foreign.tick(ctx); foreign.stop(ctx);
        h.assertTrue(c.getLog(0, 1, false).getFirst().kind() == EventKind.NONE, "Foreign ore suffix was treated as a vanilla ore tag");
        h.succeed();
    }

    /** Ein als unerreichbar gesperrtes Erz wird nicht über den Einzel-Abbau-Umweg wieder eingereiht. */
    @GameTest
    public void mineSkipsOreMarkedUnreachable(GameTestHelper h) {
        var c = companion(h); var ctx = new Nearby(c, h.getLevel()); var pos = h.absolutePos(new BlockPos(4, 1, 2));
        c.giveToInventory(new ItemStack(Items.IRON_PICKAXE)); h.getLevel().setBlock(pos, Blocks.IRON_ORE.defaultBlockState(), 3);
        ctx.mining.markUnreachable(List.of(pos));
        var handler = handler(c, Task.mine(Resources.IRON.key(), 1), ctx);
        handler.tick(ctx);
        h.assertTrue(c.peekTasks(64).stream().noneMatch(t -> t.type() == CompanionEntity.TaskType.BREAK && pos.equals(t.pos())),
                "Gesperrtes Erz erneut eingereiht: " + c.peekTasks(64));
        handler.stop(ctx); h.succeed();
    }

    @GameTest
    public void missingGatherToolPreservesFailureAndPlayerTask(GameTestHelper h) throws Exception {
        var c = companion(h); var ctx = new Nearby(c, h.getLevel());
        var task = new Task(Task.gather("diamant", 3).spec(), 4); var handler = handler(c, task, ctx);
        h.assertTrue(handler.tick(ctx) == TaskHandler.Status.FAILED, "Missing-tool failure became a new order");
        translated(h, c.problemComponent(), "passende Spitzhacke", "appropriate pickaxe");
        h.assertTrue(c.currentTask() == task && !c.isPaused() && c.usedSlots() == 0 && c.build == null, "Failure display changed work/inventory");
        handler.stop(ctx); h.succeed();
    }

    @GameTest
    public void materialPauseKeepsOrderAndResumesWithExactConsumption(GameTestHelper h) throws Exception {
        var c = companion(h); var ctx = new Nearby(c, h.getLevel()); var pos = h.absolutePos(new BlockPos(4, 1, 2));
        var task = Task.place(pos, Blocks.DIRT); var handler = handler(c, task, ctx);
        h.assertTrue(handler.tick(ctx) == TaskHandler.Status.RUNNING && c.isPaused() && c.currentTask() == task, "Material pause lost its order");
        translated(h, c.messages.getLast(), "Mir fehlt Material", "I am missing material");
        h.assertTrue(h.getLevel().getBlockState(pos).isAir() && c.usedSlots() == 0, "Pause placed a block or invented material");
        c.giveToInventory(new ItemStack(Items.DIRT, 2)); c.resume();
        h.assertTrue(handler.tick(ctx) == TaskHandler.Status.DONE && h.getLevel().getBlockState(pos).is(Blocks.DIRT), "Resume did not place the block");
        h.assertValueEqual(CompanionCraft.count(c, "dirt"), 1, "Resumed placement did not consume exactly one item");
        handler.stop(ctx); h.succeed();
    }

    @GameTest
    public void buildingKeepsLiteralNameAndWithdrawalKeepsNamespacedItem(GameTestHelper h) throws Exception {
        var c = companion(h); var ctx = new Nearby(c, h.getLevel()); var pos = h.absolutePos(new BlockPos(4, 1, 2));
        c.giveToInventory(new ItemStack(Items.DIRT, 2));
        c.build = CompanionBuilder.create(h.getLevel(), "My build %s Ä", Map.of(pos, Blocks.DIRT.defaultBlockState())); c.build.lastReported = -25;
        var build = handler(c, Task.build(), ctx); var status = TaskHandler.Status.RUNNING;
        for (int tick = 0; status == TaskHandler.Status.RUNNING && tick < 40; tick++) { c.tickCount++; status = build.tick(ctx); }
        build.stop(ctx);
        h.assertTrue(status == TaskHandler.Status.DONE && c.build == null && h.getLevel().getBlockState(pos).is(Blocks.DIRT), "Build did not finish");
        translated(h, c.messages.getFirst(), "My build %s Ä", "My build %s Ä");
        translated(h, c.messages.getLast(), "Fertig gebaut", "Finished building");
        h.assertValueEqual(CompanionCraft.count(c, "dirt"), 1, "Build consumed the wrong amount");
        c.cancelOrders(); c.clearLog(); var chestPos = h.absolutePos(new BlockPos(4, 1, 4)); h.getLevel().setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        var chest = (Container) h.getLevel().getBlockEntity(chestPos); chest.setItem(0, new ItemStack(RegistryIdTestItems.DIAMOND, 5));
        String id = ItemIds.of(chest.getItem(0)); var take = handler(c, Task.take(chestPos, id, 3), ctx);
        h.assertTrue(take.tick(ctx) == TaskHandler.Status.DONE, "Withdrawal did not finish");
        h.assertTrue(chest.getItem(0).getCount() == 2 && CompanionJobs.countOf(c, RegistryIdTestItems.DIAMOND) == 3
                && CompanionJobs.countOf(c, Items.DIAMOND) == 0, "Translated withdrawal mixed item namespaces or counts");
        var entry = c.getLog(0, 1, false).getFirst();
        h.assertTrue(entry.kind() == EventKind.LOOT, "Withdrawal event kind missing");
        translated(h, entry.textComponent(), "Aus der Kiste genommen", "Taken from the chest");
        take.stop(ctx); h.succeed();
    }
}
