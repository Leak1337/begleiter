package dev.begleiter;

import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;

/** Echte Chat-Aufrufe dürfen nur ausdrücklich angesprochene eigene Begleiter verändern. */
public class ChatGameTests {
    private static CompanionEntity companion(GameTestHelper h, BlockPos pos) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, pos);
        c.setPersistenceRequired();
        c.setCustomName(Component.literal("Max"));
        c.stay();
        return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID());
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        return p;
    }

    @GameTest
    public void alltagschatLaesstModusAuftragUndLogUnveraendert(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        ServerPlayer p = owner(h, c);
        Task task = Task.give(null, 0);
        c.addTask(task);
        int logs = c.logSize();
        CompanionBrain.onChat(p, "ich spiele gleich");
        CompanionBrain.onChat(p, "komm schon");
        CompanionBrain.onChat(p, "stop");
        CompanionBrain.onChat(p, "maximal frei spielen");
        h.assertTrue(c.isStaying(), "Alltagschat hat den Modus verändert");
        h.assertTrue(c.currentTask() == task && c.taskCount() == 1, "Alltagschat hat den Auftrag verändert");
        h.assertValueEqual(c.logSize(), logs, "Alltagschat wurde als Befehl protokolliert");
        h.succeed();
    }

    @GameTest
    public void praefixStartetFolgen(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        ServerPlayer p = owner(h, c);
        c.addTask(Task.give(null, 0));
        CompanionBrain.onChat(p, "@ folge mir");
        h.assertTrue(!c.isStaying() && !c.isAutonomous(), "Präfix hat Folgen nicht aktiviert");
        h.assertValueEqual(c.taskCount(), 0, "Alter Auftrag wurde nicht abgebrochen");
        h.succeed();
    }

    @GameTest
    public void begleiterAnredeStartetFolgen(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        ServerPlayer p = owner(h, c);
        CompanionBrain.onChat(p, "Begleiter, folge mir!");
        h.assertTrue(!c.isStaying() && !c.isAutonomous(), "Begleiter-Anrede nicht erkannt");
        h.succeed();
    }

    @GameTest
    public void nameWirdNichtAlsModusbefehlGelesen(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        ServerPlayer p = owner(h, c);
        c.setCustomName(Component.literal("Spiel"));
        CompanionBrain.onChat(p, "Spiel, folge mir");
        h.assertTrue(!c.isStaying() && !c.isAutonomous(), "Name wurde als Selbstständig-Befehl gelesen");
        h.succeed();
    }

    @GameTest
    public void nameSteuertNurDenGenanntenBegleiter(GameTestHelper h) {
        CompanionEntity near = companion(h, new BlockPos(1, 1, 1));
        ServerPlayer p = owner(h, near);
        CompanionEntity far = companion(h, new BlockPos(5, 1, 5));
        far.setOwner(p.getUUID());
        far.setCustomName(Component.literal("Moritz"));
        CompanionBrain.onChat(p, "Moritz, folge mir");
        h.assertTrue(near.isStaying(), "Nicht angesprochener Begleiter wurde verändert");
        h.assertTrue(!far.isStaying() && !far.isAutonomous(), "Genannter Begleiter folgt nicht");
        h.succeed();
    }

    @GameTest
    public void fremderBegleiterReagiertNicht(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        ServerPlayer p = owner(h, c);
        c.setOwner(UUID.randomUUID());
        int logs = c.logSize();
        CompanionBrain.onChat(p, "Max folge mir");
        CompanionBrain.onChat(p, "@ folge mir");
        h.assertTrue(c.isStaying(), "Fremder Begleiter wurde gesteuert");
        h.assertValueEqual(c.logSize(), logs, "Fremder Chat wurde protokolliert");
        h.succeed();
    }
}
