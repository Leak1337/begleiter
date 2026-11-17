package dev.begleiter;

import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Die vom Server erzeugten Menü-Items müssen Sprachschlüssel und Itemnamen aus dem Spiel behalten. */
public class MenuTextGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.setNoAi(true); c.stay(); c.buildModeOverride = false;
        return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID()); p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        return p;
    }

    private static void label(GameTestHelper h, CompanionMenu menu, int slot, String german, String english) throws Exception {
        Component name = menu.getSlot(slot).getItem().get(DataComponents.CUSTOM_NAME);
        h.assertTrue(name != null && name.getContents() instanceof TranslatableContents, "Literal tooltip in slot " + slot);
        ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(name.getString().contains(german), name.getString()));
        ServerTextGameTests.inLanguage("en_us", () -> {
            h.assertTrue(name.getString().contains(english), name.getString());
            h.assertTrue(!name.getString().contains("server.begleiter."), "Missing menu translation");
        });
    }

    @GameTest
    public void buildControlsTranslateAndBackgroundHasNoTooltip(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        var menu = new CompanionMenu(1, p.getInventory(), c, CompanionMenu.Page.BUILD);
        label(h, menu, 0, "Kein Bauauftrag", "No build order");
        label(h, menu, 5, "Survival", "Survival");
        label(h, menu, 6, "Drehung: 0", "rotation: 0");
        label(h, menu, 7, "aus", "off");
        label(h, menu, 8, "Boden anvisieren", "aim at ground");
        label(h, menu, 9, "Holzhaus", "wooden house");
        label(h, menu, 10, "Steinhaus", "stone house");
        label(h, menu, 11, "Birkenhaus", "birch house");
        label(h, menu, 12, "normal (7x7)", "normal (7x7)");
        label(h, menu, 14, "Weizenfarm", "wheat farm");
        label(h, menu, 17, "Seite 1", "page 1");
        label(h, menu, 40, "Materialliste", "Materials are listed");
        var background = menu.getSlot(45).getItem();
        // Der Bau-Katalog lässt fehlende Einträge wirklich leer (kein Hintergrund); sonst muss der Tooltip verborgen sein
        var display = background.get(DataComponents.TOOLTIP_DISPLAY);
        h.assertTrue(background.isEmpty() || display != null && display.hideTooltip(), "Background tooltip not hidden");
        h.assertTrue(background.get(DataComponents.CUSTOM_NAME) == null, "Background still uses an empty custom name");
        h.assertTrue(!c.hasTasks() && c.build == null, "Rendering menu started a task");
        h.succeed();
    }

    @GameTest
    public void translatedControlsKeepSelectionPauseAndCancelBehavior(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        var menu = new CompanionMenu(1, p.getInventory(), c, CompanionMenu.Page.BUILD);
        menu.clicked(6, 0, ContainerInput.PICKUP, p);
        label(h, menu, 6, "90", "90");
        menu.clicked(7, 0, ContainerInput.PICKUP, p);
        label(h, menu, 7, "an", "on");
        menu.clicked(12, 0, ContainerInput.PICKUP, p);
        label(h, menu, 12, "groß (9x7)", "large (9x7)");
        menu.clicked(9, 0, ContainerInput.PICKUP, p);
        h.assertTrue(c.build != null && c.hasTasks(), "House button stopped working");
        menu.clicked(2, 0, ContainerInput.PICKUP, p);
        h.assertTrue(c.build.paused && !c.hasTasks(), "Pause lost its semantics");
        label(h, menu, 0, "PAUSIERT", "PAUSED");
        label(h, menu, 2, "Weiterbauen", "Resume build");
        c.tickCount++;
        menu.clicked(2, 0, ContainerInput.PICKUP, p);
        h.assertTrue(!c.build.paused, "Resume stopped working");
        menu.clicked(3, 0, ContainerInput.PICKUP, p);
        h.assertTrue(c.build == null && !c.hasTasks(), "Cancel retained the build");
        label(h, menu, 0, "Kein Bauauftrag", "No build order");
        h.succeed();
    }

    @GameTest
    public void materialTooltipKeepsNativeItemAndCountsThroughVanillaCodec(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        c.giveToInventory(new ItemStack(Items.DIAMOND_BLOCK, 2));
        c.build = CompanionBuilder.create(h.getLevel(), "Berta %s Ä", Map.of(
                h.absolutePos(new BlockPos(4, 1, 4)), Blocks.DIAMOND_BLOCK.defaultBlockState(),
                h.absolutePos(new BlockPos(5, 1, 4)), Blocks.DIAMOND_BLOCK.defaultBlockState(),
                h.absolutePos(new BlockPos(6, 1, 4)), Blocks.DIAMOND_BLOCK.defaultBlockState()));
        var menu = new CompanionMenu(1, p.getInventory(), c, CompanionMenu.Page.BUILD);
        label(h, menu, 0, "Berta %s Ä", "Berta %s Ä");
        ItemStack material = menu.getSlot(36).getItem();
        h.assertTrue(material.is(Items.DIAMOND_BLOCK), "Wrong material item");
        var name = (TranslatableContents) material.get(DataComponents.CUSTOM_NAME).getContents();
        h.assertTrue(name.getArgs()[1] instanceof Component item && item.getContents() instanceof TranslatableContents,
                "Native item name was flattened");
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(buffer, material);
            var decoded = ItemStack.STREAM_CODEC.decode(buffer);
            ServerTextGameTests.inLanguage("en_us", () -> h.assertTrue(decoded.getHoverName().getString().startsWith("Still needed: 3x")
                    && decoded.getHoverName().getString().contains("carrying: 2"), decoded.getHoverName().getString()));
            ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(decoded.getHoverName().getString().startsWith("Noch nötig: 3x"), decoded.getHoverName().getString()));
        } finally { buffer.release(); }
        h.assertValueEqual(CompanionCraft.count(c, "diamond_block"), 2, "Material rendering consumed inventory");
        h.succeed();
    }

    @GameTest
    public void diagnosisControlsTranslateButRawLogContentStaysLiteral(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        c.logWarn("My custom log %s Ä");
        var menu = new CompanionMenu(1, p.getInventory(), c, CompanionMenu.Page.LOG);
        label(h, menu, CompanionMenu.LOG_FILTER, "nur Probleme", "problems only");
        label(h, menu, CompanionMenu.LOG_NEWER, "Neuere", "Newer");
        label(h, menu, CompanionMenu.LOG_OLDER, "Ältere", "Older");
        label(h, menu, CompanionMenu.LOG_CLEAR, "Log leeren", "Clear log");
        h.assertTrue(menu.getSlot(0).getItem().getHoverName().getString().contains("My custom log %s Ä"), "Raw log text was formatted");
        menu.quickMoveStack(p, CompanionMenu.LOG_FILTER);
        label(h, menu, CompanionMenu.LOG_FILTER, "alles", "all entries");
        menu.quickMoveStack(p, CompanionMenu.LOG_CLEAR);
        h.assertValueEqual(c.logSize(), 0, "Log clear stopped working");
        h.assertTrue(menu.getSlot(0).getItem().get(DataComponents.TOOLTIP_DISPLAY).hideTooltip(), "Empty log still has a tooltip");
        h.succeed();
    }

    @GameTest
    public void buildModeButtonSwitchesSurvivalAndCreativeAndIsSaved(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c); // Testspieler ist Kreativ -> darf Kreativ einschalten
        var menu = new CompanionMenu(1, p.getInventory(), c, CompanionMenu.Page.BUILD);
        p.containerMenu = menu;
        h.assertTrue(!menu.snapshot().creativeBuild(), "Starts in survival");
        h.assertTrue(menu.clickMenuButton(p, CompanionMenu.BUILD_MODE_TOGGLE), "Toggle request not handled");
        h.assertTrue(c.creativeHelper() && menu.snapshot().creativeBuild(), "Creative not switched on");
        label(h, menu, 5, "Kreativ", "Creative");
        ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(menu.snapshot().feedback().contains("Bau-Modus"), menu.snapshot().feedback()));

        var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        c.saveWithoutId(out);
        var copy = CompanionMod.COMPANION.create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
        copy.load(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertValueEqual(copy.buildModeOverride, Boolean.TRUE, "Build mode lost after loading");

        c.tickCount++;
        menu.clicked(5, 0, ContainerInput.PICKUP, p); // altes Symbol im Raster schaltet ebenfalls
        h.assertTrue(!c.creativeHelper() && !menu.snapshot().creativeBuild(), "Survival not switched back");
        label(h, menu, 5, "Survival", "Survival");
        h.succeed();
    }

    @GameTest
    public void survivalPlayerMayNotSwitchOnFreeBuilding(GameTestHelper h) throws Exception {
        var c = companion(h);
        var p = net.fabricmc.fabric.api.entity.FakePlayer.get(h.getLevel());
        c.setOwner(p.getUUID()); p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        h.assertTrue(!p.isCreative() && !dev.begleiter.debug.BotDebugServer.allowed(p), "Fake player is not a plain survival player");
        var menu = new CompanionMenu(1, p.getInventory(), c, CompanionMenu.Page.BUILD);
        p.containerMenu = menu;
        h.assertTrue(menu.clickMenuButton(p, CompanionMenu.BUILD_MODE_TOGGLE), "Toggle request not handled");
        h.assertTrue(!c.creativeHelper(), "Survival player switched on free building");
        ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(menu.snapshot().feedback().contains("Freies Bauen"), menu.snapshot().feedback()));
        h.succeed();
    }

    @GameTest
    public void deniedIntelligenceToggleTranslatesAndKeepsTheSetting(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        h.assertTrue(!dev.begleiter.debug.BotDebugServer.allowed(p), "Test player unexpectedly has operator permissions");
        boolean enabled = dev.begleiter.ai.CompanionDecisionEngine.enabled();
        var menu = new CompanionMenu(1, p.getInventory(), c, CompanionMenu.Page.DEBUG);
        p.containerMenu = menu;
        h.assertTrue(menu.clickMenuButton(p, CompanionMenu.INTELLIGENCE_TOGGLE), "Toggle request not handled");
        h.assertValueEqual(dev.begleiter.ai.CompanionDecisionEngine.enabled(), enabled, "Denied toggle changed server setting");
        ServerTextGameTests.inLanguage("en_us", () -> h.assertTrue(menu.snapshot().feedback().contains("only admins"), menu.snapshot().feedback()));
        ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(menu.snapshot().feedback().contains("nur Admins"), menu.snapshot().feedback()));
        h.succeed();
    }
}
