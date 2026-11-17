package dev.begleiter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import dev.begleiter.client.gui.CompanionScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Echter Client + Server: Öffnungsdaten, Navigation, Aktionen, Skalierung und Screenshots in DE und EN. */
public final class CompanionControlClientTest implements FabricClientGameTest {
    private static final String[] LANGUAGES = {"de_de", "en_us"};

    @Override public void runTest(ClientGameTestContext context) {
        context.runOnClient(mc -> CompanionDebugOverlay.enabled = false);
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksDownload();
            // Deutsche Item-Namen kommen im echten Spiel vom Client (keine mitgelieferte Kopie, keine Testeigenschaft).
            world.getServer().runOnServer(s -> {
                CompanionItemNames.reset();
                if (CompanionItemNames.resolve("Steinsäge").isEmpty() || CompanionItemNames.resolve("Stonecutter").isEmpty())
                    throw new AssertionError("Item-Namen aus dem Spiel fehlen");
            });
            for (String language : LANGUAGES) {
                setLanguage(context, language);
                exercise(context, world.getServer(), "singleplayer-" + language);
            }
        }
        // Einzelspieler ist das Release-Ziel. Dedicated Server bleibt eine optionale Zusatzprüfung.
        if (Boolean.getBoolean("begleiter.testDedicated")) {
            try (var server = context.worldBuilder().createServer(); var connection = server.connect()) {
                connection.waitForChunksDownload();
                for (String language : LANGUAGES) {
                    setLanguage(context, language);
                    exercise(context, server, "multiplayer-" + language);
                }
            }
        }
        setLanguage(context, "en_us");
        System.out.println("[CompanionControlClientTest] Lauftext: " + SCROLLING);
    }

    /** Wie im Optionsmenü: Sprache setzen und Ressourcen neu laden; das Menü wird danach neu geöffnet. */
    private static void setLanguage(ClientGameTestContext context, String code) {
        AtomicReference<CompletableFuture<Void>> reload = new AtomicReference<>();
        context.runOnClient(mc -> {
            mc.options.languageCode = code;
            mc.getLanguageManager().setSelected(code);
            reload.set(mc.reloadResourcePacks());
        });
        // Erst wenn der Ladebildschirm verschwunden ist, zeigen Screenshots das Menü.
        context.waitFor(mc -> reload.get().isDone() && mc.gui.overlay() == null);
        String overview = label(context, "gui.begleiter.page.overview");
        String expected = code.startsWith("de_") ? "Übersicht" : "Overview";
        if (!expected.equals(overview))
            throw new AssertionError("Sprache " + code + " nicht geladen: " + overview);
    }

    private static String label(ClientGameTestContext context, String key) {
        return context.computeOnClient(mc -> I18n.get(key));
    }

    private static void exercise(ClientGameTestContext context, TestServerContext server, String prefix) {
        server.runOnServer(mcServer -> {
            var player = mcServer.getPlayerList().getPlayers().getFirst();
            var c = CompanionMod.COMPANION.create(player.level(), EntitySpawnReason.COMMAND);
            if (c == null) throw new AssertionError("Begleiter konnte nicht erzeugt werden");
            c.setOwner(player.getUUID());
            c.snapTo(player.getX() + 2, player.getY(), player.getZ(), 0, 0);
            c.stay();
            c.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 12));
            c.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, 32));
            c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            player.level().addFreshEntity(c);
            c.openPanel(player, CompanionMenu.Page.MAIN);
        });
        context.waitForScreen(CompanionScreen.class);
        context.waitTicks(3);
        assertNoRawKeys(context, "overview");
        context.takeScreenshot(prefix + "-overview");
        context.clickScreenButton(label(context, "gui.begleiter.action.stay"));
        context.waitFor(mc -> mc.player.containerMenu instanceof CompanionMenu m && m.snapshot().mode() == 1);
        context.clickScreenButton(label(context, "gui.begleiter.action.follow"));
        context.waitFor(mc -> mc.player.containerMenu instanceof CompanionMenu m && m.snapshot().mode() == 0);
        for (var page : CompanionMenu.Page.values()) {
            context.runOnClient(mc -> ((CompanionScreen) mc.gui.screen()).send(CompanionMenu.PAGE_BUTTON + page.id));
            context.waitFor(mc -> mc.player.containerMenu instanceof CompanionMenu m && m.page() == page);
            context.waitTicks(2);
            assertNoRawKeys(context, page.key);
            context.takeScreenshot(prefix + "-" + page.key);
            if (page == CompanionMenu.Page.EQUIPMENT) {
                // Echte Ausrüstung vom Begleiter, nicht die Hintergrundscheiben des Panels
                context.runOnClient(mc -> {
                    var screen = (CompanionScreen) mc.gui.screen();
                    if (!screen.equipment(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)
                            || !screen.equipment(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty())
                        throw new AssertionError("Ausrüstungsseite zeigt nicht die Ausrüstung des Begleiters");
                });
                // Leerer Helmplatz: Tooltip nennt den Platz in der Spielsprache
                double[] cursor = context.computeOnClient(mc -> {
                    var screen = mc.gui.screen();
                    double scale = mc.getWindow().getGuiScale();
                    return new double[] {((screen.width - 310) / 2 + 120) * scale, ((screen.height - 238) / 2 + 77) * scale};
                });
                context.getInput().setCursorPos(cursor[0], cursor[1]);
                context.waitTicks(2);
                context.takeScreenshot(prefix + "-equipment-empty-tooltip");
                context.getInput().setCursorPos(0, 0);
            }
            if (page == CompanionMenu.Page.INVENTORY) {
                // Vergleich: echter Inventarplatz mit Diamanten, Tooltip kommt von Vanilla
                double[] cursor = context.computeOnClient(mc -> {
                    var screen = (CompanionScreen) mc.gui.screen();
                    var slot = screen.container().getSlot(0);
                    double scale = mc.getWindow().getGuiScale();
                    return new double[] {((screen.width - 310) / 2 + slot.x + 8) * scale, ((screen.height - 238) / 2 + slot.y + 8) * scale};
                });
                context.getInput().setCursorPos(cursor[0], cursor[1]);
                context.waitTicks(2);
                context.takeScreenshot(prefix + "-inventory-item-tooltip");
                context.getInput().setCursorPos(0, 0);
            }
            if (page == CompanionMenu.Page.TASKS) {
                // Alle Aktionsseiten durchblättern (je 12 Aktionen), damit jede Beschriftung geprüft wird
                String next = label(context, "gui.begleiter.actions.next");
                for (int i = 1; i * 12 < CompanionActions.all().size(); i++) {
                    context.clickScreenButton(next);
                    context.waitTicks(2);
                    assertNoRawKeys(context, page.key + "-" + i);
                }
                context.clickScreenButton(next); // zurück zur ersten Seite
                context.waitTicks(2);
            }
            if (page == CompanionMenu.Page.STATUS) {
                for (String section : new String[] {"base", "knowledge", "needs"}) {
                    context.clickScreenButton(label(context, "gui.begleiter.status.tab." + section));
                    context.waitTicks(2);
                    assertNoRawKeys(context, "status-" + section);
                    context.takeScreenshot(prefix + "-status-" + section);
                }
            }
            if (page == CompanionMenu.Page.LOG) {
                String cancel = label(context, "gui.begleiter.cancel");
                clickPanel(context, 52);
                context.waitTicks(2);
                assertNoRawKeys(context, "log-confirm");
                context.takeScreenshot(prefix + "-log-confirm");
                context.clickScreenButton(cancel);
                clickPanel(context, 52);
                context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
                context.waitForScreen(CompanionScreen.class);
                if (context.tryClickScreenButton(cancel)) throw new AssertionError("Escape verwirft den Logdialog nicht");
                clickPanel(context, 52);
                context.clickScreenButton(label(context, "gui.begleiter.log.clear"));
                context.waitTicks(2);
            }
        }
        checkWindowSizes(context, prefix);
        context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
        context.waitFor(mc -> mc.gui.screen() == null);
        server.waitFor(mcServer -> {
            var player = mcServer.getPlayerList().getPlayers().getFirst();
            return player.containerMenu == player.inventoryMenu;
        });
    }

    /** Fenstergrößen jeweils mit kleinster und größter passender GUI-Skalierung. */
    private static final int[][] WINDOWS = {{640, 480, 2}, {1280, 720, 3}, {1920, 1080, 4}};
    private static final int PANEL_W = 310, PANEL_H = 238;

    private static void checkWindowSizes(ClientGameTestContext context, String prefix) {
        CompanionMenu.Page[] pages = {CompanionMenu.Page.MAIN, CompanionMenu.Page.TASKS, CompanionMenu.Page.INVENTORY};
        for (int[] window : WINDOWS) {
            context.getInput().resizeWindow(window[0], window[1]);
            for (int scale : new int[] {1, window[2]}) {
                context.runOnClient(mc -> { mc.options.guiScale().set(scale); mc.resizeGui(); });
                context.waitTicks(3);
                for (var page : pages) {
                    context.runOnClient(mc -> ((CompanionScreen) mc.gui.screen()).send(CompanionMenu.PAGE_BUTTON + page.id));
                    context.waitFor(mc -> mc.player.containerMenu instanceof CompanionMenu m && m.page() == page);
                    context.waitTicks(2);
                    String where = page.key + "-" + window[0] + "x" + window[1] + "-gui" + scale;
                    context.runOnClient(mc -> {
                        var screen = mc.gui.screen();
                        if (screen.width < PANEL_W || screen.height < PANEL_H)
                            throw new AssertionError("Control Center passt nicht ins Fenster: " + where
                                    + " (" + screen.width + "x" + screen.height + " GUI-Pixel)");
                    });
                    assertNoRawKeys(context, where);
                    context.takeScreenshot(prefix + "-" + where);
                }
            }
        }
        // Zurück auf die Standardgröße des Testclients, damit folgende Tests unverändert laufen
        context.getInput().resizeWindow(854, 480);
        context.runOnClient(mc -> { mc.options.guiScale().set(0); mc.resizeGui(); });
        context.waitTicks(3);
    }

    /** Kein Knopf zeigt einen unübersetzten Schlüssel; zu lange Beschriftungen laufen als Lauftext und werden notiert. */
    private static void assertNoRawKeys(ClientGameTestContext context, String where) {
        context.runOnClient(mc -> {
            for (var child : mc.gui.screen().children())
                if (child instanceof AbstractWidget widget) {
                    String text = widget.getMessage().getString();
                    if (text.contains("gui.begleiter.") || text.contains("begleiter."))
                        throw new AssertionError("Rohschlüssel sichtbar auf " + where + ": " + text);
                    if (widget instanceof dev.begleiter.client.gui.ControlButton b && !b.labelFits())
                        SCROLLING.add(mc.options.languageCode + " " + where + ": " + text);
                }
        });
    }

    /** Beschriftungen, die als Lauftext erscheinen (Übersicht für die Textprüfung, kein Fehler). */
    private static final java.util.Set<String> SCROLLING = new java.util.LinkedHashSet<>();

    private static void clickPanel(ClientGameTestContext context, int index) {
        context.runOnClient(mc -> {
            var screen = (CompanionScreen) mc.gui.screen();
            var slot = screen.container().getSlot(index);
            var event = new net.minecraft.client.input.MouseButtonEvent(
                    (screen.width - 310) / 2.0 + slot.x + 8,
                    (screen.height - 238) / 2.0 + slot.y + 8,
                    new net.minecraft.client.input.MouseButtonInfo(1, 0));
            screen.mouseClicked(event, false);
            screen.mouseReleased(event);
        });
    }
}
