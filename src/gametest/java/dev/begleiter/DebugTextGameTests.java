package dev.begleiter;

import java.util.List;
import dev.begleiter.debug.BotDebug;
import dev.begleiter.debug.BotDebugServer;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Prüft den echten Debug-Erzeuger und sein Paket, unabhängig von der Anzeigesprache des Clients. */
public class DebugTextGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay(); c.setNoAi(true); c.setCustomName(Component.literal("Berta %s"));
        return c;
    }

    private static ServerPlayer player(GameTestHelper h, boolean op) {
        var p = h.makeMockServerPlayerInLevel();
        if (op) h.getLevel().getServer().getPlayerList().op(p.nameAndId(),
                java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.of(false));
        else h.getLevel().getServer().getPlayerList().deop(p.nameAndId());
        return p;
    }

    private static BotDebug.Snapshot roundTrip(GameTestHelper h, BotDebug.Snapshot value) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            BotDebug.Snapshot.CODEC.encode(buffer, value);
            byte[] wire = io.netty.buffer.ByteBufUtil.getBytes(buffer);
            var back = BotDebug.Snapshot.CODEC.decode(buffer);
            h.assertValueEqual(buffer.readableBytes(), 0, "Unread debug packet bytes");
            var canonical = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
            try {
                BotDebug.Snapshot.CODEC.encode(canonical, back);
                h.assertTrue(java.util.Arrays.equals(wire, io.netty.buffer.ByteBufUtil.getBytes(canonical)), "Debug wire form changed");
            } finally { canonical.release(); }
            return back;
        } finally { buffer.release(); }
    }

    @GameTest
    public void sameDebugPacketResolvesInBothLanguages(GameTestHelper h) throws Exception {
        var c = companion(h); var p = player(h, true);
        c.addTask(CompanionEntity.Task.gather("eisen", 7));
        c.lastStage = "My project %s"; c.problemText = "Custom warning %s";
        c.tickCount = 1250; c.clearLog(); c.logWarn("My log %s");
        ItemStack tool = new ItemStack(Items.IRON_PICKAXE); tool.setDamageValue(4);
        c.setItemSlot(EquipmentSlot.MAINHAND, tool);
        var notice = CompanionTexts.tr("debug.command.notice", c.getName(), CompanionTexts.tr("debug.command.paused"));
        var back = roundTrip(h, BotDebugServer.snapshot(p, c.getUUID(), BotDebug.LEVEL_FULL, false, notice, List.of(c)));
        var d = back.detail();
        h.assertTrue(d.actionText().getContents() instanceof TranslatableContents, "Debug action flattened");
        h.assertTrue(d.taskLines().getFirst().kind() == CompanionEntity.TaskType.GATHER, "Task type lost");
        h.assertTrue(d.equipmentLines().get(4).stack().is(Items.IRON_PICKAXE)
                && d.equipmentLines().get(4).stack().getDamageValue() == 4, "Native equipment metadata lost");
        ServerTextGameTests.inLanguage("de_de", () -> {
            h.assertTrue(d.action().contains("sucht Eisen (noch 7)"), d.action());
            h.assertTrue(d.equipment().get(4).startsWith("Haupthand:"), d.equipment().toString());
            h.assertTrue(d.logLines().getFirst().formatted().getString().contains("[01:02] WARNUNG: My log %s"), d.log().toString());
            h.assertTrue(back.notice().equals("Berta %s: KI pausiert"), back.notice());
        });
        ServerTextGameTests.inLanguage("en_us", () -> {
            h.assertTrue(d.action().contains("looking for iron (7 remaining)"), d.action());
            h.assertTrue(d.equipment().get(4).startsWith("Main hand:"), d.equipment().toString());
            h.assertTrue(d.logLines().getFirst().formatted().getString().contains("[01:02] WARNING: My log %s"), d.log().toString());
            h.assertTrue(back.notice().equals("Berta %s: AI paused"), back.notice());
            h.assertTrue(d.plan().equals("My project %s") && d.lastError().equals("Custom warning %s"), "Literal producer text formatted");
        });
        h.succeed();
    }

    @GameTest
    public void inventoryGroupsByNativeItemAndComponentsInsteadOfDisplayName(GameTestHelper h) {
        var c = companion(h); var p = player(h, true);
        var diamond = new ItemStack(Items.DIAMOND, 2);
        var iron = new ItemStack(Items.IRON_INGOT, 3);
        diamond.set(DataComponents.CUSTOM_NAME, Component.literal("Same name %s"));
        var privateData = new net.minecraft.nbt.CompoundTag(); privateData.putString("private", "Not needed by debug display");
        diamond.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(privateData));
        iron.set(DataComponents.CUSTOM_NAME, Component.literal("Same name %s"));
        c.getInventory().setItem(0, diamond); c.getInventory().setItem(1, iron);
        c.getInventory().setItem(2, diamond.copyWithCount(4));
        c.getInventory().setItem(3, new ItemStack(Items.DIAMOND, 5));
        var d = roundTrip(h, BotDebugServer.snapshot(p, c.getUUID(), BotDebug.LEVEL_FULL, false, "", List.of(c))).detail();
        h.assertValueEqual(d.inventoryLines().size(), 3, "Different native items/components collapsed by name");
        h.assertTrue(d.inventoryLines().get(0).stack().is(Items.DIAMOND) && d.inventoryLines().get(0).total() == 6, "Identical stacks not summed");
        h.assertTrue(d.inventoryLines().get(1).stack().is(Items.IRON_INGOT) && d.inventoryLines().get(1).total() == 3, "Item identity/count lost");
        h.assertTrue(d.inventoryLines().get(2).stack().get(DataComponents.CUSTOM_NAME) == null, "Custom name leaked between groups");
        h.assertTrue(d.inventoryLines().get(0).stack().get(DataComponents.CUSTOM_DATA) == null, "Debug packet includes unrelated custom data");
        h.assertTrue(c.getInventory().getItem(0).get(DataComponents.CUSTOM_DATA) != null, "Snapshot stripped real inventory data");
        h.assertValueEqual(c.getInventory().getItem(0).getCount(), 2, "Snapshot mutated inventory");
        h.succeed();
    }

    @GameTest
    public void hudRetainsStructuredRecentEventsAndRemainsSmall(GameTestHelper h) {
        var c = companion(h); var p = player(h, true);
        c.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
        c.clearLog();
        for (int i = 0; i < 10; i++) c.logInfo("Haus " + i);
        c.logInfo("Hergestellt %s"); c.logInfo("Hergestellt %s");
        var d = roundTrip(h, BotDebugServer.snapshot(p, c.getUUID(), BotDebug.LEVEL_HUD, false, "", List.of(c))).detail();
        h.assertTrue(d.inventoryLines().isEmpty() && d.equipmentLines().isEmpty() && d.path().length == 0, "HUD contains full detail");
        h.assertValueEqual(d.logLines().size(), BotDebug.MAX_RECENT, "HUD event limit changed");
        var latest = d.logLines().getFirst();
        h.assertTrue(latest.recent() && latest.kind() == BotDebug.EventKind.CRAFT && latest.repetitions() == 2 && latest.ageSec() == 0,
                "Event metadata lost during transport");
        // Gleicher Anzeigetext, verschiedene Ereignis-IDs: die Farbe darf nicht aus der Sprache abgeleitet werden.
        var other = new BotDebug.LogLine('I', 0, 0, 1, BotDebug.EventKind.DANGER, latest.text(), true);
        h.assertTrue(BotDebug.eventColor(latest.kind()) != BotDebug.eventColor(other.kind()), "Color depends on message text");
        h.succeed();
    }

    @GameTest
    public void malformedPacketCountsAndUnauthorizedSnapshotsAreRejected(GameTestHelper h) {
        for (int count : new int[]{-1, BotDebug.MAX_BOTS + 1}) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
            try {
                buffer.writeBoolean(true); ComponentSerialization.STREAM_CODEC.encode(buffer, Component.empty());
                buffer.writeBoolean(false); buffer.writeVarInt(count);
                boolean rejected = false;
                try { BotDebug.Snapshot.CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
                h.assertTrue(rejected, "Invalid debug bot count accepted: " + count);
            } finally { buffer.release(); }
        }
        var c = companion(h); var p = player(h, false);
        var denied = BotDebugServer.snapshot(p, c.getUUID(), BotDebug.LEVEL_FULL, true, "private notice", List.of(c));
        h.assertTrue(!denied.allowed() && denied.bots().isEmpty() && denied.detail() == null && denied.notice().isEmpty(), "Unauthorized snapshot leaked data");
        h.assertTrue(BotDebugServer.executeReply(p, new BotDebug.Command(c.getUUID(), BotDebug.A_PAUSE, 0, 0, 0)) == null
                && !c.isDebugFrozen(), "Translated command bypasses permissions");
        h.succeed();
    }
}
