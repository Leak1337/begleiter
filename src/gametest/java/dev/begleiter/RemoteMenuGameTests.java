package dev.begleiter;

import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Fernmenü bleibt an Besitzer und aktiven Container gebunden; normaler Nahzugriff bleibt unverändert. */
public class RemoteMenuGameTests {
    private static CompanionEntity bot(GameTestHelper h, BlockPos pos, ServerPlayer owner) {
        var c = h.spawn(CompanionMod.COMPANION, pos); c.setNoAi(true); c.stay(); c.setOwner(owner.getUUID()); return c;
    }
    private static ServerPlayer player(GameTestHelper h) {
        @SuppressWarnings("removal") var p = h.makeMockServerPlayerInLevel();
        p.setUUID(UUID.randomUUID());
        BlockPos at = h.absolutePos(new BlockPos(24, 1, 24)); p.snapTo(at.getX(), at.getY(), at.getZ(), 0, 0); return p;
    }
    @GameTest(structure = "begleiter-gametest:request_isolation")
    public void fernmenueOeffnetEigenenEntferntenBotUndFuehrtAuftragAus(GameTestHelper h) {
        var p = player(h); var c = bot(h, new BlockPos(2, 1, 2), p);
        h.assertTrue(!CompanionAccess.canManage(p, c), "Nahzugriff wurde aufgeweicht");
        c.openPanel(p, CompanionMenu.Page.MAIN);
        h.assertTrue(p.containerMenu == p.inventoryMenu, "Nahmenü aus Entfernung geöffnet");
        CompanionRemoteMenu.open(p);
        h.assertTrue(p.containerMenu instanceof CompanionMenu, "Fernmenü fehlt");
        var menu = (CompanionMenu) p.containerMenu;
        h.assertTrue(menu.stillValid(p) && menu.controlsRemotely(p, c), "Fernmenü schließt sofort");
        menu.clickMenuButton(p, CompanionMenu.ACTION_BUTTON);
        h.assertTrue(!c.isStaying(), "Fernauftrag nicht ausgeführt");
        h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation")
    public void fernmenueKannBereichskarteAusEntfernungOeffnen(GameTestHelper h) {
        var p = player(h); var c = bot(h, new BlockPos(2, 1, 2), p);
        h.assertTrue(CompanionZoneEditor.select(p, c) == null, "Karte ohne Berechtigung geöffnet");
        c.openPanel(p, CompanionMenu.Page.DUTIES, true);
        h.assertTrue(CompanionZoneEditor.select(p, c) != null, "Karte trotz gültigem Fernmenü blockiert");
        p.closeContainer();
        h.assertTrue(!CompanionAccess.canUseMenu(p, c), "Geschlossene Fernsitzung erlaubt Zugriff"); h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation")
    public void besitzerwechselSperrtFernmenueUndInventar(GameTestHelper h) {
        var p = player(h); var c = bot(h, new BlockPos(2, 1, 2), p);
        c.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5)); c.openPanel(p, CompanionMenu.Page.INVENTORY, true);
        var menu = (CompanionMenu) p.containerMenu;
        c.setOwner(UUID.randomUUID());
        h.assertTrue(!menu.stillValid(p) && !CompanionAccess.canUseMenu(p, c), "Besitzerwechsel ignoriert");
        menu.quickMoveStack(p, CompanionMenu.INVENTORY_FIRST);
        menu.clicked(CompanionMenu.INVENTORY_FIRST, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p);
        h.assertValueEqual(c.getInventory().getItem(0).getCount(), 5, "Inventar nach Besitzerwechsel verändert");
        menu.broadcastChanges(); h.assertTrue(p.containerMenu == p.inventoryMenu, "Ungültiges Fernmenü nicht geschlossen"); h.succeed();
    }
    @GameTest(structure = "begleiter-gametest:request_isolation")
    public void auswahlBevorzugtLetztenBotUndFaelltAufEigenenNaechstenZurueck(GameTestHelper h) {
        var p = player(h); var near = bot(h, new BlockPos(22, 1, 22), p); var far = bot(h, new BlockPos(2, 1, 2), p);
        h.assertTrue(CompanionRemoteMenu.find(p) == near, "Nächster eigener Bot nicht gewählt");
        CompanionRemoteMenu.remember(p, far);
        h.assertTrue(CompanionRemoteMenu.find(p) == far, "Letzter Bot nicht bevorzugt");
        far.setOwner(UUID.randomUUID()); h.assertTrue(CompanionRemoteMenu.find(p) == near, "Fremder gemerkter Bot gewählt");
        near.discard(); h.assertTrue(CompanionRemoteMenu.find(p) == null, "Entfernter oder fremder Bot gewählt"); h.succeed();
    }
}
