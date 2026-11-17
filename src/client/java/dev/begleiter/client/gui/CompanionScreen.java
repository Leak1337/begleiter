package dev.begleiter.client.gui;

import dev.begleiter.CompanionActions;
import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionMenu;
import dev.begleiter.CompanionSnapshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Nur Layout und Darstellung; alle Änderungen werden vom offenen Servermenü verarbeitet. */
public final class CompanionScreen extends AbstractContainerScreen<CompanionMenu> {
    private int panelWidth = 310, panelHeight = 238;
    private CompanionMenu.Page displayed;
    private boolean confirmLogClear;
    private int mouseX, mouseY;
    private int navigationOffset;
    private BuildPreviewPanel buildPreview;
    private boolean draggingPreview;
    private final BuildPreviewModel[] thumbnails = new BuildPreviewModel[CompanionMenu.SCHEM_COUNT];
    private final String[] thumbnailTitles = new String[CompanionMenu.SCHEM_COUNT];
    private final int[] thumbnailRequests = new int[CompanionMenu.SCHEM_COUNT];
    private final java.util.LinkedHashMap<String, BuildPreviewModel> thumbnailCache =
            new java.util.LinkedHashMap<>(8, .75f, true);
    private int catalogueTick;
    private final java.util.LinkedHashMap<java.util.UUID, BuildPreviewModel> previewModels =
            new java.util.LinkedHashMap<>(4, .75f, true);
    private int previewModelBlocks;
    private BuildPreviewModel previewModel(dev.begleiter.CompanionBuildPreview preview) {
        var model = previewModels.get(preview.modelId());
        if (model != null) return model;
        model = new BuildPreviewModel(preview.plan());
        if (preview.modelId().equals(new java.util.UUID(0, 0))) return model;
        previewModels.put(preview.modelId(), model);
        previewModelBlocks += model.plan().blocks().size();
        while (previewModels.size() > 4 || previewModelBlocks > dev.begleiter.CompanionSchematics.MAX_BLOCKS)
            previewModelBlocks -= previewModels.pollFirstEntry().getValue().plan().blocks().size();
        return model;
    }
    public BuildPreviewModel currentBuildPreviewModel() { return buildPreview == null ? null : buildPreview.model(); }
    public CompanionScreen(CompanionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 310, 238);
    }
    @Override protected void init() {
        super.init();
        buildPage();
    }
    private void buildPage() {
        boolean wide = menu.page() == CompanionMenu.Page.BUILD && width >= 440 && height >= 332;
        panelWidth = wide ? 430 : 310;
        panelHeight = wide ? 322 : 238;
        leftPos = (width - panelWidth) / 2;
        topPos = (height - panelHeight) / 2;
        clearWidgets();
        clearFocus();
        displayed = menu.page();
        if (buildPreview != null && displayed == CompanionMenu.Page.BUILD) {
            buildPreview.init(this);
            return;
        }
        buildPreview = null;
        if (confirmLogClear) {
            button(112, 147, 88, tr("gui.begleiter.cancel"), ItemStack.EMPTY, tr("gui.begleiter.log.keep"), () -> false,
                    () -> { confirmLogClear = false; buildPage(); });
            button(207, 147, 88, tr("gui.begleiter.log.clear"), ItemStack.EMPTY, tr("gui.begleiter.log.clear.tooltip"), () -> false, () -> {
                confirmLogClear = false;
                if (minecraft.gameMode != null && minecraft.player != null)
                    minecraft.gameMode.handleContainerInput(menu.containerId, CompanionMenu.LOG_CLEAR, 0,
                            net.minecraft.world.inventory.ContainerInput.PICKUP, minecraft.player);
                buildPage();
            });
            return;
        }
        int row = 0;
        for (var page : ControlPages.all().stream().skip(navigationOffset).limit(8).toList()) {
            String title = GuiText.page(page.page());
            button(8, 43 + row++ * 21, 92, title, new ItemStack(page.icon()),
                    title, () -> menu.page() == page.page(),
                    () -> send(CompanionMenu.PAGE_BUTTON + page.page().id));
        }
        // Die Sprache folgt Minecraft; der Fuß unter der achten Navigationszeile (endet bei y=210) bleibt für Schließen.
        button(8, panelHeight - 24, 92, tr("gui.begleiter.close"), ItemStack.EMPTY, tr("gui.begleiter.close.tooltip"),
                () -> false, this::onClose);
        var page = ControlPages.get(displayed);
        if (page != null) page.init(this);
        // Die Aufgaben-Seite hat Folgen/Warten/Frei schon als große Knöpfe
        if (displayed != CompanionMenu.Page.INVENTORY && displayed != CompanionMenu.Page.DUTIES
                && displayed != CompanionMenu.Page.BUILD) {
            actionButton(108, 211, 62, CompanionActions.get(0), false);
            actionButton(173, 211, 62, CompanionActions.get(1), false);
            actionButton(238, 211, 62, CompanionActions.get(2), false);
        }
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (menu.page() == CompanionMenu.Page.BUILD && buildPreview == null) {
            catalogueTick++;
            for (int i = 0; i < thumbnails.length; i++) {
                var stack = menu.getSlot(CompanionMenu.SCHEM_FIRST + i).getItem();
                String title = stack.isEmpty() ? "" : stack.getHoverName().getString();
                if (!title.equals(thumbnailTitles[i])) {
                    thumbnailTitles[i] = title;
                    thumbnails[i] = thumbnailCache.get(title);
                    thumbnailRequests[i] = 0;
                }
                if (!title.isEmpty() && thumbnails[i] == null && catalogueTick >= thumbnailRequests[i]) {
                    thumbnailRequests[i] = catalogueTick + 40;
                    send(CompanionMenu.BUILD_THUMBNAIL_BUTTON + i);
                }
            }
        }
        if (displayed != menu.page()) {
            confirmLogClear = false;
            isQuickCrafting = false;
            quickCraftSlots.clear();
            buildPage();
        } else if (!confirmLogClear && buildPreview == null) {
            var page = ControlPages.get(displayed);
            if (page != null && page.stale(this)) buildPage();
        }
    }
    public void rebuildPage() { buildPage(); }
    public boolean hasBuildPreview() { return buildPreview != null; }
    public void showBuildPreview(dev.begleiter.CompanionBuildPreview preview) {
        if (menu.page() != CompanionMenu.Page.BUILD || menu.containerId != preview.containerId()
                || !data().npc().equals(preview.npc())) return;
        if (preview.catalogueSlot() >= 0) {
            int i = preview.catalogueSlot();
            if (i < thumbnails.length) {
                String title = preview.title().getString();
                var model = thumbnailCache.get(title);
                if (model == null || !model.plan().equals(preview.plan())) {
                    model = new BuildPreviewModel(preview.plan());
                    thumbnailCache.put(title, model);
                    while (thumbnailCache.size() > 8) thumbnailCache.pollFirstEntry();
                }
                if (title.equals(thumbnailTitles[i])) thumbnails[i] = model;
            }
            return;
        }
        buildPreview = new BuildPreviewPanel(preview, previewModel(preview));
        draggingPreview = false;
        buildPage();
    }
    public void closeBuildPreview() {
        buildPreview = null;
        draggingPreview = false;
        buildPage();
    }
    public CompanionSnapshot data() { return menu.snapshot(); }
    public BuildPreviewModel buildThumbnail(int index) {
        var stack = menu.getSlot(CompanionMenu.SCHEM_FIRST + index).getItem();
        return !stack.isEmpty() && stack.getHoverName().getString().equals(thumbnailTitles[index]) ? thumbnails[index] : null;
    }
    public void buildCard(int index, int x, int y) {
        addRenderableWidget(new BuildCard(this, index, leftPos + x, topPos + y));
    }
    public CompanionMenu container() { return menu; }
    public boolean wideBuild() { return panelWidth == 430; }
    public int panelX() { return leftPos; }
    public int panelY() { return topPos; }
    /** Gemeinsamer Fortschrittsbalken für Control-Center-Seiten (relative GUI-Koordinaten). */
    public void progressBar(GuiGraphicsExtractor g, int x, int y, int width, int percent, int color) {
        int left = leftPos + x, top = topPos + y;
        int filled = Math.round(width * Math.clamp(percent, 0, 100) / 100f);
        g.fill(left, top, left + width, top + 4, ControlTheme.LINE);
        if (filled > 0) g.fill(left, top, left + filled, top + 4, color);
    }
    public void send(int button) {
        if (button == CompanionMenu.BUILD_PANEL_BUTTON + 16) {
            previewModels.clear();
            previewModelBlocks = 0;
            thumbnailCache.clear();
            java.util.Arrays.fill(thumbnails, null);
            java.util.Arrays.fill(thumbnailTitles, null);
            java.util.Arrays.fill(thumbnailRequests, 0);
        }
        if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }
    public void button(int x, int y, int width, String label, ItemStack icon, String tooltip,
            java.util.function.BooleanSupplier selected, Runnable action) {
        addRenderableWidget(new ControlButton(leftPos + x, topPos + y, width,
                label, icon, tooltip, selected, action));
    }
    public void actionButton(int x, int y, int width, CompanionActions.Action action, boolean strong) {
        String title = GuiText.action(action);
        String label = width < 80 && action.mode() == 2 ? tr("gui.begleiter.action.free.short") : title;
        button(x, y, width, label, width < 80 ? ItemStack.EMPTY : new ItemStack(CompanionCraft.item(action.icon())),
                title + (action.id().startsWith("gather_")
                        ? " · " + tr("gui.begleiter.amount", strong ? 64 : 16)
                        : action.id().equals("gear") ? " · " + tr(strong ? "gui.begleiter.gear.diamond" : "gui.begleiter.gear.iron") : ""),
                () -> action.mode() >= 0 && data().mode() == action.mode(),
                () -> send((strong ? CompanionMenu.STRONG_ACTION_BUTTON : CompanionMenu.ACTION_BUTTON) + action.button()));
    }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        // Das eigentliche Layout wird vor den Vanilla-Widgets gezeichnet.
    }
    @Override public void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        g.fill(0, 0, width, height, 0x99060B10);
        int x = leftPos, y = topPos;
        g.fill(x, y, x + panelWidth, y + panelHeight, ControlTheme.BACKGROUND);
        g.outline(x, y, panelWidth, panelHeight, ControlTheme.LINE);
        if (buildPreview != null) {
            buildPreview.render(this, g);
            hoveredSlot = null;
            for (var child : children()) if (child instanceof net.minecraft.client.gui.components.Renderable renderable)
                renderable.extractRenderState(g, mx, my, delta);
            return;
        }
        g.fill(x + 1, y + 1, x + panelWidth - 1, y + 36, ControlTheme.PANEL);
        g.fill(x + 103, y + 40, x + 104, y + panelHeight - 6, ControlTheme.LINE);
        text(g, data().name(), 10, 7, 188, ControlTheme.TEXT);
        text(g, GuiText.mode(data().mode()) + (data().paused() ? " · " + tr("gui.begleiter.paused") : ""), 10, 21, 185, ControlTheme.ACCENT);
        text(g, tr("gui.begleiter.health", Math.round(data().health()), Math.round(data().maxHealth())), panelWidth - 105, 9, 94, ControlTheme.TEXT);
        g.fill(x + panelWidth - 105, y + 23, x + panelWidth - 11, y + 27, ControlTheme.LINE);
        int life = (int) (94 * Math.clamp(data().health() / Math.max(1, data().maxHealth()), 0f, 1f));
        int healthColor = data().health() / Math.max(1f, data().maxHealth()) <= 0.25f ? 0xFFE87979 : ControlTheme.ACCENT;
        g.fill(x + panelWidth - 105, y + 23, x + panelWidth - 105 + life, y + 27, healthColor);
        var page = ControlPages.get(menu.page());
        if (confirmLogClear) {
            text(g, tr("gui.begleiter.log.confirm.title"), 112, 77, 184, ControlTheme.TEXT);
            paragraph(g, tr("gui.begleiter.log.confirm.text"), 112, 94, 184, 4, ControlTheme.MUTED);
        } else if (page != null) page.render(this, g, mx, my);
        for (var slot : menu.slots) if (!confirmLogClear && slot.isActive()) {
            g.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, ControlTheme.LINE);
            g.fill(x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16, 0xFF101A24);
        }
        if (confirmLogClear) {
            hoveredSlot = null;
            for (var child : children()) if (child instanceof net.minecraft.client.gui.components.Renderable renderable)
                renderable.extractRenderState(g, mx, my, delta);
        } else super.extractContents(g, mx, my, delta);
    }
    @Override protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {}
    @Override protected void extractSlots(GuiGraphicsExtractor g, int mx, int my) {
        if (!confirmLogClear && buildPreview == null) super.extractSlots(g, mx, my);
    }
    @Override protected void slotClicked(net.minecraft.world.inventory.Slot slot, int index, int button,
            net.minecraft.world.inventory.ContainerInput input) {
        if (menu.page() == CompanionMenu.Page.LOG && index == CompanionMenu.LOG_CLEAR) {
            confirmLogClear = true;
            hoveredSlot = null;
            buildPage();
            return;
        }
        super.slotClicked(slot, index, button, input);
    }
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (buildPreview != null) {
            if (buildPreview.contains(this, event.x(), event.y())) { draggingPreview = true; return true; }
            for (var child : children()) if (child.mouseClicked(event, doubleClick)) return true;
            return true;
        }
        if (confirmLogClear) {
            for (var child : children()) if (child.mouseClicked(event, doubleClick)) return true;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (buildPreview != null) {
            if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) closeBuildPreview();
            else if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_TAB
                    || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN
                    || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_SPACE
                    || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_LEFT
                    || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT
                    || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_UP
                    || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_DOWN) return super.keyPressed(event);
            return true;
        }
        if (confirmLogClear && event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
            confirmLogClear = false; buildPage(); return true;
        }
        return super.keyPressed(event);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (buildPreview != null) {
            if (buildPreview.contains(this, x, y)) buildPreview.scroll(vertical);
            return true;
        }
        if (!confirmLogClear && vertical != 0 && x >= leftPos && x < leftPos + 104
                && y >= topPos + 40 && y < topPos + 212 && ControlPages.all().size() > 8) {
            navigationOffset = Math.clamp(navigationOffset + (vertical < 0 ? 1 : -1), 0, ControlPages.all().size() - 8);
            buildPage();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        if (buildPreview != null) {
            if (draggingPreview) buildPreview.drag(dx, dy);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (buildPreview != null) {
            draggingPreview = false;
            for (var child : children()) child.mouseReleased(event);
            return true;
        }
        return super.mouseReleased(event);
    }
    /** Fester Oberflächentext in der Minecraft-Sprache; Java-Logik und Task-IDs bleiben sprachneutral. */
    public String tr(String key, Object... args) { return GuiText.tr(key, args); }

    /** Zeichnet bereits fertigen Text; feste Texte vorher über {@link #tr} holen. */
    public void text(GuiGraphicsExtractor g, String value, int x, int y, int maxWidth, int color) {
        String clipped = font.plainSubstrByWidth(value, maxWidth);
        g.text(font, clipped, leftPos + x, topPos + y, color, false);
        if (!clipped.equals(value) && hover(x, y, maxWidth, 10))
            g.setTooltipForNextFrame(font, Component.literal(value), mouseX, mouseY);
    }
    public void paragraph(GuiGraphicsExtractor g, String value, int x, int y, int maxWidth, int maxLines, int color) {
        var lines = font.split(Component.literal(value), maxWidth);
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++)
            g.text(font, lines.get(i), leftPos + x, topPos + y + i * 11, color, false);
        if (lines.size() > maxLines && hover(x, y, maxWidth, maxLines * 11))
            g.setTooltipForNextFrame(font, Component.literal(value), mouseX, mouseY);
    }
    private boolean hover(int x, int y, int width, int height) {
        return mouseX >= leftPos + x && mouseX < leftPos + x + width
                && mouseY >= topPos + y && mouseY < topPos + y + height;
    }
    public void item(GuiGraphicsExtractor g, ItemStack stack, int x, int y, int mx, int my) {
        item(g, stack, x, y, mx, my, "");
    }
    /** Ausrüstung des Begleiters vom Client-Entity (vom Server synchronisiert); leer, solange er nicht geladen ist. */
    public ItemStack equipment(net.minecraft.world.entity.EquipmentSlot slot) {
        var level = minecraft == null ? null : minecraft.level;
        return level != null && level.getEntity(data().npc()) instanceof net.minecraft.world.entity.LivingEntity e
                ? e.getItemBySlot(slot) : ItemStack.EMPTY;
    }
    /** Wie {@link #item}, zeigt bei leerem Platz aber {@code emptyTip} als Tooltip (fertig übersetzt). */
    public void item(GuiGraphicsExtractor g, ItemStack stack, int x, int y, int mx, int my, String emptyTip) {
        g.fill(leftPos + x - 2, topPos + y - 2, leftPos + x + 18, topPos + y + 18, ControlTheme.PANEL);
        g.item(stack, leftPos + x, topPos + y);
        if (mx < leftPos + x || mx >= leftPos + x + 16 || my < topPos + y || my >= topPos + y + 16) return;
        if (!stack.isEmpty()) g.setTooltipForNextFrame(font, stack, mx, my);
        else if (!emptyTip.isEmpty()) g.setTooltipForNextFrame(font, Component.literal(emptyTip), mx, my);
    }
    public void feedback(GuiGraphicsExtractor g, int y) {
        paragraph(g, data().feedback().isEmpty() ? tr("gui.begleiter.feedback.ready") : data().feedback(),
                110, y, 188, 2, ControlTheme.ACCENT);
    }
}
