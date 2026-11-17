package dev.begleiter;

import java.util.List;
import java.util.UUID;

import dev.begleiter.debug.BotDebug;
import dev.begleiter.debug.BotDebugServer;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** GameTests für das Bot-Debug-Menü (Berechtigung, Steuerung, Schnappschuss). Starten mit: .\gradlew runGameTest */
public class BotDebugGameTests {
    private static final String NOT_ALLOWED = "Ohne Berechtigung wurde die Aktion trotzdem ausgeführt";

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    private static CompanionEntity bot(GameTestHelper h, BlockPos rel) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, rel);
        c.setPersistenceRequired();
        return c;
    }

    private static ServerPlayer admin(GameTestHelper h, boolean op) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        var list = h.getLevel().getServer().getPlayerList();
        if (op) list.op(p.nameAndId(), java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER),
                java.util.Optional.of(false));
        else list.deop(p.nameAndId());
        return p;
    }

    private static BotDebug.Command cmd(CompanionEntity c, int action, int x, int y, int z) {
        return new BotDebug.Command(c.getUUID(), action, x, y, z);
    }

    /** Intelligence-2.0-Knopf im Control Center: serverweit, darum nur für Admins; Zustand landet im Snapshot. */
    @GameTest
    public void intelligenceSchalterNurFuerAdmins(GameTestHelper h) {
        floor(h);
        boolean original = dev.begleiter.ai.CompanionDecisionEngine.enabled();
        var config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("begleiter-intelligence.json");
        byte[] savedConfig = null;
        try {
            if (java.nio.file.Files.exists(config)) savedConfig = java.nio.file.Files.readAllBytes(config);
        } catch (java.io.IOException ex) {
            h.fail("Config nicht lesbar: " + ex);
        }
        try {
            for (boolean op : new boolean[]{false, true}) {
                CompanionEntity c = bot(h, new BlockPos(2, 1, 2));
                ServerPlayer p = admin(h, op);
                c.setOwner(p.getUUID());
                p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
                c.openPanel(p, CompanionMenu.Page.DEBUG);
                var menu = (CompanionMenu) p.containerMenu;
                boolean before = dev.begleiter.ai.CompanionDecisionEngine.enabled();
                h.assertTrue(menu.clickMenuButton(p, CompanionMenu.INTELLIGENCE_TOGGLE), "Knopf abgelehnt (op=" + op + ")");
                boolean after = dev.begleiter.ai.CompanionDecisionEngine.enabled();
                h.assertTrue(after == (op ? !before : before), op ? "Admin konnte nicht umschalten" : NOT_ALLOWED);
                h.assertTrue(menu.snapshot().intelligenceEnabled() == after, "Snapshot zeigt alten Zustand");
                p.closeContainer();
                c.discard();
            }
        } finally {
            dev.begleiter.ai.CompanionDecisionEngine.setEnabled(original, false);
            try { // Test darf die Einstellung späterer Läufe nicht verändern
                if (savedConfig == null) java.nio.file.Files.deleteIfExists(config);
                else java.nio.file.Files.write(config, savedConfig);
            } catch (java.io.IOException ignored) { }
        }
        h.succeed();
    }

    @GameTest
    public void ohneAdminrechteGeschiehtNichts(GameTestHelper h) {
        floor(h);
        CompanionEntity c = bot(h, new BlockPos(2, 1, 2));
        c.hunger = 15;
        ServerPlayer p = admin(h, false);
        h.assertTrue(!BotDebugServer.allowed(p), "Spieler ohne Rechte gilt als berechtigt");
        for (int action = BotDebug.A_PAUSE; action <= BotDebug.A_HUNGER; action++)
            h.assertTrue(BotDebugServer.execute(p, cmd(c, action, 1, 70, 1)) == null, NOT_ALLOWED + " (Aktion " + action + ")");
        h.assertTrue(!c.isPaused() && !c.isDebugFrozen(), NOT_ALLOWED);
        h.assertTrue(c.hunger == 15 && !c.hasTasks(), NOT_ALLOWED);
        h.succeed();
    }

    @GameTest
    public void adminKannPausierenEinzelschrittUndFortsetzen(GameTestHelper h) {
        floor(h);
        CompanionEntity c = bot(h, new BlockPos(2, 1, 2));
        ServerPlayer p = admin(h, true);
        h.assertTrue(BotDebugServer.allowed(p), "Admin nicht berechtigt");
        h.assertTrue(BotDebugServer.execute(p, cmd(c, BotDebug.A_PAUSE, 0, 0, 0)) != null, "Pause abgewiesen");
        h.assertTrue(c.isPaused(), "Pause wirkt nicht");
        BotDebugServer.execute(p, cmd(c, BotDebug.A_STEP, 0, 0, 0));
        h.assertTrue(!c.isPaused(), "Einzelschritt hebt die Pause nicht auf");
        // nach den Schritt-Ticks wieder pausiert
        h.succeedWhen(() -> {
            h.assertTrue(c.isPaused(), "Nach dem Einzelschritt nicht wieder pausiert");
            BotDebugServer.execute(p, cmd(c, BotDebug.A_RESUME, 0, 0, 0));
            h.assertTrue(!c.isPaused() && !c.isDebugFrozen(), "Fortsetzen wirkt nicht");
        });
    }

    @GameTest
    public void adminZielAbbrechenLebenHungerUndTeleport(GameTestHelper h) {
        floor(h);
        CompanionEntity c = bot(h, new BlockPos(1, 1, 1));
        ServerPlayer p = admin(h, true);
        p.snapTo(net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(new BlockPos(6, 1, 6))));
        BlockPos goal = h.absolutePos(new BlockPos(5, 1, 5));
        BotDebugServer.execute(p, cmd(c, BotDebug.A_GOTO, goal.getX(), goal.getY(), goal.getZ()));
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == CompanionEntity.TaskType.MOVE_TO
                && goal.equals(c.currentTask().pos()), "Ziel nicht gesetzt");
        BotDebugServer.execute(p, cmd(c, BotDebug.A_CANCEL, 0, 0, 0));
        h.assertTrue(!c.hasTasks(), "Abbrechen lässt Aufgaben stehen");
        BotDebugServer.execute(p, cmd(c, BotDebug.A_HEALTH, 9999, 0, 0));
        h.assertTrue(c.getHealth() <= c.getMaxHealth(), "Leben über Maximum");
        BotDebugServer.execute(p, cmd(c, BotDebug.A_HEALTH, 12, 0, 0));
        h.assertTrue(c.getHealth() == 12, "Leben nicht gesetzt: " + c.getHealth());
        BotDebugServer.execute(p, cmd(c, BotDebug.A_HUNGER, 99, 0, 0));
        h.assertTrue(c.hunger == 20, "Hunger nicht begrenzt: " + c.hunger);
        BotDebugServer.execute(p, cmd(c, BotDebug.A_HUNGER, 5, 0, 0));
        h.assertTrue(c.hunger == 5, "Hunger nicht gesetzt");
        BotDebugServer.execute(p, cmd(c, BotDebug.A_TELEPORT, 0, 0, 0));
        h.assertTrue(c.distanceToSqr(p) < 4, "Teleport zum Spieler fehlgeschlagen");
        h.assertTrue(BotDebugServer.execute(p, new BotDebug.Command(UUID.randomUUID(), BotDebug.A_PAUSE, 0, 0, 0)) != null,
                "Unbekannter Bot sollte eine Meldung ergeben");
        h.succeed();
    }

    @GameTest
    public void schnappschussIstBegrenztUndUeberlebtDieUebertragung(GameTestHelper h) {
        floor(h);
        CompanionEntity c = bot(h, new BlockPos(2, 1, 2));
        ServerPlayer p = admin(h, true);
        for (int i = 0; i < 36; i++) c.getInventory().setItem(i, new ItemStack(i % 2 == 0 ? Items.DIAMOND : Items.COBBLESTONE, 1 + i));
        for (int i = 0; i < 40; i++) c.logInfo("Eintrag " + i);
        for (int i = 0; i < 30; i++) c.addTask(CompanionEntity.Task.moveTo(BlockPos.ZERO.offset(i, 0, 0)));
        BotDebug.Snapshot hud = BotDebugServer.snapshot(p, c.getUUID(), BotDebug.LEVEL_HUD, false, "", List.of(c));
        h.assertTrue(hud.detail() != null && hud.detail().inventory().isEmpty() && hud.detail().log().isEmpty(),
                "HUD-Schnappschuss enthält Inventar oder Log");
        h.assertTrue(hud.detail().path().length == 0, "Pfad ohne Anforderung gesendet");
        BotDebug.Snapshot full = BotDebugServer.snapshot(p, c.getUUID(), BotDebug.LEVEL_FULL, true, "ok", List.of(c));
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        BotDebug.Snapshot.CODEC.encode(buf, full);
        BotDebug.Snapshot back = BotDebug.Snapshot.CODEC.decode(buf);
        h.assertTrue(back.allowed() && back.bots().size() == 1 && back.selected().equals(c.getUUID()), "Bots nach Übertragung");
        BotDebug.Detail d = back.detail();
        h.assertTrue(d.tasks().size() <= BotDebug.MAX_TASKS && d.taskTotal() == 30, "Aufgaben nicht begrenzt: " + d.tasks().size());
        h.assertTrue(d.log().size() <= BotDebug.MAX_LOG && !d.log().isEmpty(), "Log nicht begrenzt: " + d.log().size());
        h.assertTrue(d.inventory().size() <= BotDebug.MAX_INV, "Inventar nicht begrenzt");
        h.assertTrue(d.equipment().size() == 6, "Ausrüstung fehlt");
        h.assertTrue(buf.readableBytes() == 0, "Unvollständig gelesen");
        h.succeed();
    }

    @GameTest
    public void schnappschussEnthaeltNurBegleiterUndWaehltNaechstenBot(GameTestHelper h) {
        floor(h);
        CompanionEntity a = bot(h, new BlockPos(1, 1, 1));
        CompanionEntity b = bot(h, new BlockPos(6, 1, 6));
        ServerPlayer p = admin(h, true);
        p.snapTo(b.position());
        BotDebug.Snapshot s = BotDebugServer.snapshot(p, null, BotDebug.LEVEL_HUD, false, "", List.of(a, b));
        h.assertTrue(s.bots().size() == 2, "Beide Bots erwartet");
        h.assertTrue(b.getUUID().equals(s.selected()), "Nächster Bot sollte vorausgewählt sein");
        h.assertTrue(b.getUUID().equals(s.bots().getFirst().id()), "Liste nicht nach Entfernung sortiert");
        BotDebug.Snapshot sel = BotDebugServer.snapshot(p, a.getUUID(), BotDebug.LEVEL_HUD, false, "", List.of(a, b));
        h.assertTrue(a.getUUID().equals(sel.selected()) && sel.detail().id().equals(a.getUUID()), "Auswahl wird nicht beachtet");
        h.succeed();
    }
}
