package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Jeder Menüknopf mit Chat-Befehl landet beim richtigen Serverauftrag (nicht bei "nicht verstanden"). */
public class MenuCommandGameTests {
    private record Fixture(CompanionEntity mob, ServerPlayer owner) {}

    private static Fixture fixture(GameTestHelper h) {
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var mob = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        mob.stay();
        mob.setNoAi(true);
        mob.buildModeOverride = false;
        @SuppressWarnings("removal") var player = h.makeMockServerPlayerInLevel();
        mob.setOwner(player.getUUID());
        player.snapTo(mob.getX(), mob.getY(), mob.getZ(), 0, 0);
        return new Fixture(mob, player);
    }

    private static Component run(GameTestHelper h, Fixture f, String command) {
        var reply = CompanionMenuCommands.execute(f.owner(), f.mob(), false, command);
        h.assertTrue(reply != null, "Menu command not understood: " + command);
        String key = key(reply);
        h.assertTrue(!"server.begleiter.reply.unknown".equals(key) && !"server.begleiter.request.ask_item".equals(key),
                "Menu command misread: " + command + " -> " + reply.getString());
        return reply;
    }

    private static void expect(GameTestHelper h, Fixture f, String command, String expected) {
        var reply = run(h, f, command);
        h.assertTrue(("server.begleiter." + expected).equals(key(reply)),
                "Menu command " + command + " answered " + key(reply) + ": " + reply.getString());
    }

    private static String key(Component reply) {
        return reply.getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    @GameTest
    public void stockAndStorageButtonsReachTheirRules(GameTestHelper h) {
        var f = fixture(h);
        expect(h, f, CompanionMenuCommands.STOCK_LIST, "stock.none");
        expect(h, f, CompanionMenuCommands.keepStock(Items.TORCH, 32), "stock.no_home");
        h.setBlock(4, 1, 2, Blocks.CHEST);
        f.mob().setHome(h.absolutePos(new BlockPos(4, 1, 2)));
        expect(h, f, CompanionMenuCommands.keepStock(Items.TORCH, 32), "stock.active");
        expect(h, f, CompanionMenuCommands.stockGather(Items.TORCH, true), "stock.collect_on");
        expect(h, f, CompanionMenuCommands.stockGather(Items.TORCH, false), "stock.collect_off");
        expect(h, f, CompanionMenuCommands.clearStock(Items.TORCH), "stock.removed");
        expect(h, f, CompanionMenuCommands.STOCK_CLEAR, "stock.cleared");
        expect(h, f, CompanionMenuCommands.WOOD_SCOPED, "stock.wood_scoped");
        expect(h, f, CompanionMenuCommands.WOOD_FREE, "stock.wood_unscoped");
        expect(h, f, CompanionMenuCommands.store(Items.GLASS, 64, false), "order.home_accepted");
        expect(h, f, CompanionMenuCommands.store(Items.GLASS, 8, true), "order.home_accepted");
        h.succeed();
    }

    @GameTest
    public void workButtonsManageJobs(GameTestHelper h) {
        var f = fixture(h);
        expect(h, f, CompanionMenuCommands.work("farm", "add"), "duty.zone_required");
        expect(h, f, CompanionMenuCommands.work("trade", "pause"), "work.missing_entry");
        h.setBlock(4, 1, 2, Blocks.CHEST);
        f.mob().setHome(h.absolutePos(new BlockPos(4, 1, 2)));
        expect(h, f, CompanionMenuCommands.work("stock", "add"), "work.added");
        run(h, f, CompanionMenuCommands.workValue("stock", "priority", 4));
        run(h, f, CompanionMenuCommands.workValue("stock", "limit", 2));
        run(h, f, CompanionMenuCommands.workBudget("stock", Items.COAL, 0));
        run(h, f, CompanionMenuCommands.work("stock", "budget"));
        run(h, f, CompanionMenuCommands.workBudgetRemove("stock", Items.COAL));
        run(h, f, CompanionMenuCommands.work("stock", "history"));
        run(h, f, CompanionMenuCommands.work("stock", "why"));
        run(h, f, CompanionMenuCommands.work("stock", "pause"));
        run(h, f, CompanionMenuCommands.work("stock", "resume"));
        expect(h, f, CompanionMenuCommands.WORK_OFF, "work.disabled");
        expect(h, f, CompanionMenuCommands.WORK_ON, "work.enabled");
        run(h, f, CompanionMenuCommands.WORK_LIST);
        run(h, f, CompanionMenuCommands.work("stock", "remove"));
        h.succeed();
    }

    @GameTest
    public void adventureAndOrderButtonsStartWork(GameTestHelper h) {
        var f = fixture(h);
        expect(h, f, CompanionMenuCommands.SURVIVAL_STATUS, "journey.none");
        run(h, f, CompanionMenuCommands.SURVIVAL_IRON);
        h.assertTrue(f.mob().journey.active(), "Survival goal did not start");
        run(h, f, CompanionMenuCommands.SURVIVAL_PAUSE);
        run(h, f, CompanionMenuCommands.SURVIVAL_RESUME);
        run(h, f, CompanionMenuCommands.SURVIVAL_CLEAR);
        h.assertTrue(!f.mob().journey.active(), "Survival goal was not cleared");
        run(h, f, CompanionMenuCommands.SURVIVAL_DRAGON);
        run(h, f, CompanionMenuCommands.SURVIVAL_CLEAR);
        run(h, f, CompanionMenuCommands.catchFish(3));
        run(h, f, CompanionMenuCommands.GO_FISHING);
        run(h, f, CompanionMenuCommands.obtain(Items.STICK, 4));
        run(h, f, CompanionMenuCommands.craft(Items.STICK, 4));
        run(h, f, CompanionMenuCommands.materials(Items.STONECUTTER, 1));
        for (var mode : CompanionTerraform.Mode.values()) run(h, f, CompanionMenuCommands.terrain(mode, 5));
        h.succeed();
    }

    @GameTest
    public void talkAndWaypointButtonsAnswer(GameTestHelper h) {
        var f = fixture(h);
        run(h, f, CompanionMenuCommands.STATUS);
        run(h, f, CompanionMenuCommands.EXPLAIN);
        run(h, f, CompanionMenuCommands.KNOWLEDGE);
        for (String topic : CompanionMenuCommands.WHERE_TOPICS) run(h, f, CompanionMenuCommands.whereIs(topic));
        expect(h, f, CompanionMenuCommands.TALK_LESS, "reply.talk");
        expect(h, f, CompanionMenuCommands.TALK_MORE, "reply.talk");
        expect(h, f, CompanionMenuCommands.PROTECT, "reply.protect");
        run(h, f, CompanionMenuCommands.DOOR);
        run(h, f, CompanionMenuCommands.PROJECT_PREVIEW);
        run(h, f, CompanionMenuCommands.SCHEMATICS);
        expect(h, f, CompanionMenuCommands.WAYPOINT_LIST, "waypoint.empty");
        expect(h, f, CompanionMenuCommands.waypointSave("Mine 1"), "waypoint.saved");
        expect(h, f, CompanionMenuCommands.waypointGo("mine 1"), "waypoint.go");
        expect(h, f, CompanionMenuCommands.waypointForget("Mine 1"), "waypoint.forgotten");
        expect(h, f, CompanionMenuCommands.MEMORY_CLEAR, "memory.cleared");
        var free = CompanionMenuCommands.execute(f.owner(), f.mob(), true, "follow me");
        h.assertTrue(free != null && "server.begleiter.reply.follow".equals(key(free)), "Free command was not run like chat");
        h.assertTrue(CompanionMenuCommands.execute(f.owner(), f.mob(), false, "#obtain 4 minecraft:not_an_item") != null,
                "Unknown item was not reported");
        h.succeed();
    }
}
