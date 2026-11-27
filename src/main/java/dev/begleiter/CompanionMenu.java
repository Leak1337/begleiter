package dev.begleiter;

import java.util.List;
import java.util.Map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Serverseitiger Container des Control Centers. Seitenwechsel erhalten dieselbe Slot-/Cursor-Sitzung. */
public class CompanionMenu extends AbstractContainerMenu {
    public static final class Page {
        private static final java.util.Map<Integer, Page> PAGES = new java.util.LinkedHashMap<>();
        public static final Page MAIN = register(0, "overview", "Overview");
        public static final Page INVENTORY = register(1, "inventory", "Inventory");
        public static final Page TASKS = register(2, "control", "Control");
        public static final Page BUILD = register(3, "build", "Build");
        public static final Page LOG = register(4, "log", "Diagnostics");
        public static final Page EQUIPMENT = register(5, "equipment", "Equipment");
        public static final Page COMBAT = register(6, "combat", "Combat");
        public static final Page STATUS = register(7, "status", "Status");
        public static final Page DEBUG = register(8, "debug", "Debug");
        public static final Page DUTIES = register(9, "duties", "Tasks");
        public static final Page WAYPOINTS = register(10, "waypoints", "Waypoints");
        public static final Page ORDERS = register(11, "orders", "Orders");
        public static final Page WORK = register(12, "work", "Work");
        public static final Page ADVENTURE = register(13, "adventure", "Adventure");
        public static final Page TALK = register(14, "talk", "Talk");
        public final int id;
        public final String key, title;
        private Page(int id, String key, String title) { this.id = id; this.key = key; this.title = title; }
        public static Page register(int id, String key, String title) {
            if (id < 0 || id >= 100 || PAGES.containsKey(id)
                    || PAGES.values().stream().anyMatch(p -> p.key.equals(key)))
                throw new IllegalArgumentException("Duplicate or invalid companion page: " + key);
            Page page = new Page(id, key, title);
            PAGES.put(id, page);
            return page;
        }
        public static java.util.List<Page> values() { return java.util.List.copyOf(PAGES.values()); }
        public static Page byId(int id) { return PAGES.get(id); }
        @Override public String toString() { return key; }
    }
    public static final int PAGE_BUTTON = 100, ACTION_BUTTON = 1000, STRONG_ACTION_BUTTON = 1100;
    /** Debug-Seite: Intelligence 2.0 serverweit an/aus (nur Admins bzw. Besitzer der Einzelspielerwelt). */
    public static final int INTELLIGENCE_TOGGLE = 99;
    /** Bauen-Seite: Survival (Material nötig) ⇄ Kreativ (freies Bauen) für diesen Begleiter. */
    public static final int BUILD_MODE_TOGGLE = 98;
    /** Bauen-Seite: Bau-Bewegung weiterschalten (Normal → Fliegen → Fliegen/Teleport). */
    public static final int BUILD_MOVEMENT_TOGGLE = 97;
    public static final int BUILD_PREVIEW_PLACE = 96;
    public static final int WAYPOINT_MAP_OPEN = 95;
    public static final int BUILD_PANEL_BUTTON = 2000, BUILD_THUMBNAIL_BUTTON = 2100;
    private CompanionMachines.Blueprint previewBlueprint;
    private CompanionSchematics.Schematic previewPlan;
    public static final int INVENTORY_FIRST = 54, PLAYER_FIRST = 90;
    /** Diagnose-Seite: Bedienelemente in der Anzeigefläche (0–43 sind Logzeilen). */
    public static final int LOG_FILTER = 44, LOG_NEWER = 46, LOG_OLDER = 48, LOG_CLEAR = 52;
    /** Bauen-Seite: Schematic-Dateien in den Slots 18–34, Material in 36–44. */
    public static final int SCHEM_FIRST = 18, SCHEM_COUNT = 4;
    private static final int MAT_FIRST = 36, MAT_COUNT = 9;
    private long thumbnailTick = -1;
    private int pendingThumbnails;
    private record CatalogueKey(String source, long modified, long size) {}
    private record CachedPlan(CompanionSchematics.Schematic plan, java.util.UUID modelId) {}
    private final java.util.LinkedHashMap<CatalogueKey, CachedPlan> cataloguePlans =
            new java.util.LinkedHashMap<>(8, .75f, true);
    private final java.util.LinkedHashMap<CatalogueKey, CompanionSchematics.Schematic> catalogueThumbnails =
            new java.util.LinkedHashMap<>(8, .75f, true);
    private int cachedPlanBlocks;
    private static final String[] SIZES = {"klein", "normal", "gross"};
    private static final net.minecraft.world.level.block.Rotation[] ROTATIONS = {
            net.minecraft.world.level.block.Rotation.NONE, net.minecraft.world.level.block.Rotation.CLOCKWISE_90,
            net.minecraft.world.level.block.Rotation.CLOCKWISE_180, net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90};

    private final CompanionEntity comp;
    private boolean remote;
    private Page page;
    private CompanionSnapshot snapshot;
    private final Inventory playerInv;
    private final SimpleContainer panel = new SimpleContainer(54);
    private int tick;
    private int logOffset;
    private boolean errorsOnly = true;
    private int sizeIdx = 1;
    private int rotIdx;
    private boolean withAir;
    private int schemOffset;
    private List<java.nio.file.Path> schematics = List.of();
    private Map<Item, Integer> materials = Map.of();
    /** Komplettes Restmaterial des Bauauftrags, gemeinsam für Anzeige und Schnappschuss. */
    private Map<Item, Integer> remaining = Map.of();
    private int remainingTick;
    private boolean remainingStale = true;
    private static final int REMAINING_INTERVAL = 40;

    /** Reine Darstellung; Bedienung der bisherigen Bau-/Logseite läuft über clicked. */
    private final class ButtonSlot extends Slot {
        ButtonSlot(SimpleContainer c, int index, int x, int y) { super(c, index, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
        @Override public boolean isActive() { return page == Page.LOG; }
    }

    private int lastPressTick = -1;
    private int lastPressSlot = -1;

    private void press(int slot, boolean strong, ServerPlayer p) {
        if (!stillValid(p)) return;
        if (comp.tickCount == lastPressTick && slot == lastPressSlot) return; // Doppel-Auslösung vermeiden
        lastPressTick = comp.tickCount;
        lastPressSlot = slot;
        comp.consoleDebug("Menu click: page " + page + ", slot " + slot + (strong ? " (shift)" : ""));
        try {
            handleButton(slot, strong, p);
        } catch (RuntimeException ex) {
            comp.logError(CompanionTexts.tr("log.menu.error", slot, CompanionTexts.literal(String.valueOf(ex))));
        }
    }

    public CompanionMenu(int id, Inventory playerInv, CompanionEntity comp, Page page) {
        this(id, playerInv, comp, page, CompanionSnapshot.capture(comp, id, page));
    }

    CompanionMenu(int id, Inventory playerInv, CompanionEntity comp, Page page, boolean remote) {
        this(id, playerInv, comp, page);
        this.remote = remote;
    }
    public boolean controlsRemotely(Player player, CompanionEntity companion) {
        return remote && comp == companion && player == playerInv.player && player.containerMenu == this
                && CompanionAccess.canControl(player, companion);
    }

    /** Client: ausschließlich synchronisierte Anzeigedaten und Vanilla-Slotspiegel. */
    public CompanionMenu(int id, Inventory playerInv, CompanionSnapshot opening) {
        this(id, playerInv, null, Page.byId(opening.page()) == null ? Page.MAIN : Page.byId(opening.page()), opening);
    }

    private CompanionMenu(int id, Inventory playerInv, CompanionEntity comp, Page page, CompanionSnapshot snapshot) {
        super(CompanionMod.CONTROL_MENU, id);
        this.comp = comp;
        this.page = page;
        this.snapshot = snapshot;
        this.playerInv = playerInv;
        for (int i = 0; i < 54; i++)
            addSlot(new ButtonSlot(panel, i, 122 + (i % 9) * 18, 54 + (i / 9) * 18));
        SimpleContainer inventory = comp == null ? new SimpleContainer(36) : comp.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            addSlot(new Slot(inventory, i, 122 + (i % 9) * 18, 54 + (i / 9) * 18) {
                @Override public boolean isActive() { return CompanionMenu.this.page == Page.INVENTORY; }
                @Override public boolean mayPlace(ItemStack stack) { return isActive(); }
                @Override public boolean mayPickup(Player player) { return isActive(); }
            });
        }
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++) addPlayerSlot(c + r * 9 + 9, 122 + c * 18, 142 + r * 18);
        for (int c = 0; c < 9; c++) addPlayerSlot(c, 122 + c * 18, 202);
        if (comp != null) {
            if (page == Page.BUILD) schematics = CompanionSchematics.list();
            refresh();
        }
    }

    private void addPlayerSlot(int index, int x, int y) {
        addSlot(new Slot(playerInv, index, x, y) {
            @Override public boolean isActive() { return page == Page.INVENTORY; }
            @Override public boolean mayPlace(ItemStack stack) { return isActive(); }
            @Override public boolean mayPickup(Player player) { return isActive(); }
        });
    }

    public Page page() { return page; }
    public CompanionSnapshot snapshot() { return snapshot; }
    public void acceptSnapshot(CompanionSnapshot update) {
        if (comp != null || update.containerId() != containerId || !snapshot.npc().equals(update.npc())) return;
        Page next = Page.byId(update.page());
        if (next == null) return;
        snapshot = update;
        page = next;
    }

    private void syncSnapshot() {
        if (comp == null) return;
        snapshot = CompanionSnapshot.capture(comp, containerId, page, page == Page.BUILD ? remaining() : Map.of());
        if (playerInv.player instanceof ServerPlayer sp && sp.containerMenu == this
                && ServerPlayNetworking.canSend(sp, CompanionSnapshot.TYPE)) ServerPlayNetworking.send(sp, snapshot);
    }

    @Override public boolean clickMenuButton(Player player, int button) {
        if (comp == null || !stillValid(player) || !(player instanceof ServerPlayer sp)
                || sp.containerMenu != this) return false;
        Page next = Page.byId(button - PAGE_BUTTON);
        if (button == WAYPOINT_MAP_OPEN) return page == Page.WAYPOINTS && CompanionWaypointMap.open(sp, comp);
        if (button >= BUILD_THUMBNAIL_BUTTON && button < BUILD_THUMBNAIL_BUTTON + SCHEM_COUNT) {
            if (page != Page.BUILD) return false;
            // Nach einem Lag können Pakete gebündelt ankommen: jeden angeforderten Slot behalten statt verwerfen.
            pendingThumbnails |= 1 << (button - BUILD_THUMBNAIL_BUTTON);
            return true;
        } else if (button >= BUILD_PANEL_BUTTON && button < BUILD_PANEL_BUTTON + 54) {
            if (page != Page.BUILD) return false;
            press(button - BUILD_PANEL_BUTTON, false, sp);
        } else if (button == BUILD_PREVIEW_PLACE) {
            if (page != Page.BUILD) return false;
            if (previewBlueprint != null)
                report(CompanionPlacement.begin(sp, comp, previewBlueprint, ROTATIONS[rotIdx]));
            else if (previewPlan != null)
                report(CompanionPlacement.beginSchematic(sp, comp, previewPlan, withAir, ROTATIONS[rotIdx]));
            previewBlueprint = null;
            previewPlan = null;
        } else if (button == INTELLIGENCE_TOGGLE) {
            if (!dev.begleiter.debug.BotDebugServer.allowed(sp)) {
                report(CompanionTexts.tr("panel.intelligence.denied"));
            } else {
                boolean on = !dev.begleiter.ai.CompanionDecisionEngine.enabled();
                dev.begleiter.ai.CompanionDecisionEngine.setEnabled(on, true);
                report(CompanionTexts.tr("panel.intelligence.changed", CompanionTexts.tr(on ? "panel.on" : "panel.off")));
            }
        } else if (button == BUILD_MODE_TOGGLE) {
            report(toggleBuildMode(sp));
            refresh();
        } else if (button == BUILD_MOVEMENT_TOGGLE) {
            report(cycleBuildMovement());
            refresh();
        } else if (next != null) {
            go(next);
        } else {
            boolean strong = button >= STRONG_ACTION_BUTTON;
            var action = CompanionActions.get(button - (strong ? STRONG_ACTION_BUTTON : ACTION_BUTTON));
            if (action == null) return false;
            try {
                report(action.handler().executeComponent(comp, sp, strong));
            } catch (RuntimeException ex) {
                comp.setUiFeedback(CompanionTexts.tr("reply.error"));
                comp.logError(CompanionTexts.tr("log.action.error", action.id(), CompanionTexts.literal(String.valueOf(ex))));
            }
        }
        remainingStale = true; // Aktionen können einen Bau starten oder abbrechen
        syncSnapshot();
        broadcastChanges();
        return true;
    }

    /** Menüknopf mit Chat-Befehl (siehe {@link CompanionMenuCommands}); Rückmeldung wie bei jedem Menüknopf. */
    void runCommand(ServerPlayer sp, boolean free, String command) {
        if (comp == null || !stillValid(sp) || sp.containerMenu != this) return;
        if (comp.tickCount == lastPressTick && lastPressSlot == -2) return; // Doppelklick im selben Tick
        lastPressTick = comp.tickCount;
        lastPressSlot = -2;
        comp.consoleDebug("Menu command: page " + page + (free ? " (free)" : "") + ": " + command);
        comp.chatSerial++;
        try {
            Component reply = CompanionMenuCommands.execute(sp, comp, free, command);
            if (reply != null) report(reply);
            else if (free && command != null && !command.isBlank() && command.length() <= CompanionMenuCommands.MAX_LENGTH
                    && CompanionLlmChat.start(sp, comp, command.strip(), sp.clientInformation().language()))
                comp.setUiFeedback(CompanionTexts.tr("menu.command.thinking"));
            else report(CompanionTexts.tr(free ? "menu.command.unknown" : "menu.command.none"));
        } catch (RuntimeException ex) {
            comp.setUiFeedback(CompanionTexts.tr("reply.error"));
            comp.logError(CompanionTexts.tr("log.menu.error", -2, CompanionTexts.literal(String.valueOf(ex))));
        }
        remainingStale = true;
        syncSnapshot();
        broadcastChanges();
    }

    /**
     * Kreativ heißt Blöcke aus dem Nichts: Einschalten dürfen nur Spieler, die selbst im Kreativmodus sind,
     * Admins oder der Besitzer der Einzelspielerwelt. Zurück auf Survival darf jeder, der ihn verwalten darf.
     */
    private Component toggleBuildMode(ServerPlayer sp) {
        boolean creative = !comp.creativeHelper();
        if (creative && !sp.isCreative() && !dev.begleiter.debug.BotDebugServer.allowed(sp))
            return CompanionTexts.tr("panel.build.mode.denied");
        comp.buildModeOverride = creative;
        return CompanionTexts.tr("panel.build.mode.changed",
                CompanionTexts.tr(creative ? "panel.build.creative" : "panel.build.survival"));
    }

    /** Bau-Bewegung weiterschalten; das ist eine Balance-Einstellung des Besitzers, kein Rechte-Thema wie Kreativ. */
    private Component cycleBuildMovement() {
        CompanionEntity.BuildMovement next = comp.buildMovement().next();
        comp.buildMovementOverride = next;
        return CompanionTexts.tr("panel.build.movement.changed", CompanionTexts.buildMovement(next));
    }

    private void report(String reply) {
        report(CompanionTexts.literal(reply));
    }

    private void report(Component reply) {
        if (reply == null) return;
        comp.setUiFeedback(reply);
        comp.logInfo(CompanionTexts.tr("log.menu", reply));
        comp.tell(reply);
    }

    @Override
    public boolean stillValid(Player player) {
        return player == playerInv.player && (comp == null ? player.level().isClientSide()
                : remote ? CompanionAccess.canControl(player, comp) : CompanionAccess.canManage(player, comp));
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (!stillValid(player) || slot >= slots.size()) return;
        if (slot >= 0 && slot < INVENTORY_FIRST) {
            if (comp != null && player instanceof ServerPlayer sp && (page == Page.BUILD || page == Page.LOG)
                    && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)) {
                press(slot, input == ContainerInput.QUICK_MOVE, sp);
                syncSnapshot();
            }
            return;
        }
        if (page != Page.INVENTORY) return;
        super.clicked(slot, button, input, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        if (index < INVENTORY_FIRST) {
            if (player instanceof ServerPlayer sp && comp != null && (page == Page.BUILD || page == Page.LOG))
                press(index, true, sp);
            return ItemStack.EMPTY;
        }
        if (page != Page.INVENTORY) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack orig = slot.getItem();
        ItemStack copy = orig.copy();
        if (index < PLAYER_FIRST) {
            if (!moveItemStackTo(orig, PLAYER_FIRST, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(orig, INVENTORY_FIRST, PLAYER_FIRST, false)) return ItemStack.EMPTY;
        if (orig.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, orig);
        return copy;
    }

    @Override
    public void broadcastChanges() {
        if (comp == null) { super.broadcastChanges(); return; }
        if (!stillValid(playerInv.player)) {
            if (playerInv.player instanceof ServerPlayer sp && sp.containerMenu == this) sp.closeContainer();
            return;
        }
        if (++tick % 10 == 0) { refresh(); syncSnapshot(); }
        if (page == Page.BUILD && pendingThumbnails != 0 && thumbnailTick != comp.level().getGameTime()
                && playerInv.player instanceof ServerPlayer sp && sp.containerMenu == this) {
            thumbnailTick = comp.level().getGameTime();
            int slot = Integer.numberOfTrailingZeros(pendingThumbnails);
            pendingThumbnails &= ~(1 << slot);
            thumbnail(sp, slot);
        }
        super.broadcastChanges();
    }

    // ------------------------------------------------------------------ Anzeige

    private void set(int slot, String itemId, String name) {
        set(slot, itemId, Component.literal(name));
    }

    private void set(int slot, String itemId, Component name) {
        Item it = CompanionCraft.item(itemId);
        if (it == Items.AIR) it = CompanionCraft.item("paper");
        ItemStack st = new ItemStack(it, 1);
        st.set(DataComponents.CUSTOM_NAME, name);
        panel.setItem(slot, st);
    }

    private void background() {
        if (page == Page.BUILD) return; // der Bau-Katalog lässt fehlende Einträge wirklich leer
        for (int i = 0; i < 54; i++) {
            Item it = CompanionCraft.item("black_stained_glass_pane");
            if (it == Items.AIR) continue;
            ItemStack st = new ItemStack(it, 1);
            st.set(DataComponents.TOOLTIP_DISPLAY, new net.minecraft.world.item.component.TooltipDisplay(true, new java.util.LinkedHashSet<>()));
            panel.setItem(i, st);
        }
    }

    private void refresh() {
        panel.clearContent();
        background();
        if (page == Page.BUILD) buildPage();
        else if (page == Page.LOG) logPage();
        else if (page == Page.STATUS) statusPage();
    }

    /** Nur lesende Daten für die Wissens-/Basis-Reiter; keine Suche nach Stationen in der Umgebung. */
    private void statusPage() {
        set(36, "knowledge_book", CompanionTexts.memorySummary(comp));
        boolean hasProblem = comp.tickCount - comp.problemTick < 3000 && !comp.problemText.isEmpty();
        set(8, hasProblem ? "red_dye" : "lime_dye", hasProblem
                ? CompanionTexts.tr("problem", comp.problemComponent()) : CompanionTexts.tr("panel.status.ok"));
        set(37, "oak_door", CompanionTexts.tr("panel.status.house", CompanionTexts.tr(comp.isHouseBuilt() ? "status.yes" : "status.no")));
        set(38, "ender_chest", CompanionTexts.tr("panel.status.home", comp.getHome() == null
                ? CompanionTexts.tr("status.no_home") : CompanionTexts.literal(comp.getHome().toShortString())));
        set(39, "compass", CompanionTexts.tr("panel.status.mode", CompanionTexts.mode(comp)));
        set(40, "iron_pickaxe", CompanionTexts.tr("panel.status.mine", comp.mine() == null
                ? CompanionTexts.tr("status.no_home") : CompanionTexts.literal(comp.mine().toShortString())));
        set(28, "clock", CompanionTexts.tr("panel.status.activity", CompanionTexts.activity(comp)));
        set(29, "paper", CompanionTexts.tr("panel.status.tasks", comp.taskCount()));
        set(30, "bread", CompanionTexts.tr("panel.status.food", comp.foodCount(), comp.food.level()));
        set(31, "chest", CompanionTexts.tr("panel.status.inventory", comp.usedSlots()));
        set(33, "writable_book", CompanionTexts.tr("panel.status.project", CompanionTexts.tr(comp.projekt.vorhanden()
                ? "project.state.draft" : "project.state.none")));
    }

    private void logPage() {
        List<CompanionLog.Entry> lines = comp.getLog(logOffset, 44, errorsOnly);
        for (int i = 0; i < lines.size(); i++) {
            CompanionLog.Entry e = lines.get(i);
            set(i, e.level() == 'E' ? "red_dye" : e.level() == 'W' ? "orange_dye" : "paper", CompanionEntity.formatLogText(e));
        }
        set(LOG_FILTER, errorsOnly ? "redstone_torch" : "torch",
                CompanionTexts.tr("panel.log.filter", CompanionTexts.tr(errorsOnly ? "panel.log.problems" : "panel.log.all")));
        set(LOG_NEWER, "spectral_arrow", CompanionTexts.tr("panel.log.newer"));
        set(LOG_OLDER, "arrow", CompanionTexts.tr("panel.log.older", comp.logSize(), logOffset + 1));
        set(LOG_CLEAR, "lava_bucket", CompanionTexts.tr("panel.log.clear"));
    }

    /** Bauen: Status + Pause, eigene Bauten, Schematics zum Anklicken, Materialliste. */
    private void buildPage() {
        CompanionBuilder.BuildJob job = comp.build;
        boolean creative = comp.creativeHelper();
        if (job == null) {
            set(0, "scaffolding", CompanionTexts.tr("panel.build.none"));
        } else {
            set(0, "scaffolding", CompanionTexts.tr("panel.build.progress", CompanionTexts.buildName(job.name), job.index, job.total(), job.percent(),
                        job.paused ? CompanionTexts.tr("panel.build.paused") : Component.empty()));
            if (job.paused) set(2, "lime_dye", CompanionTexts.tr("panel.build.resume"));
            else set(2, "yellow_dye", CompanionTexts.tr("panel.build.pause"));
            set(3, "barrier", CompanionTexts.tr("panel.build.cancel"));
        }
        set(4, comp.buildMovement().icon, CompanionTexts.tr("panel.build.movement", CompanionTexts.buildMovement(comp.buildMovement())));
        set(5, creative ? "command_block" : "iron_pickaxe",
                CompanionTexts.tr(creative ? "panel.build.creative" : "panel.build.survival"));
        set(6, rotIdx == 0 ? "compass" : "recovery_compass",
                CompanionTexts.tr("panel.build.rotation", rotIdx * 90));
        set(7, withAir ? "glass" : "tinted_glass",
                CompanionTexts.tr("panel.build.air", CompanionTexts.tr(withAir ? "panel.on" : "panel.off")));
        set(8, "map", CompanionTexts.tr("panel.build.direction"));

        set(9, "oak_planks", CompanionTexts.tr("panel.build.house", CompanionTexts.tr("panel.style.wood"), sizeLabel()));
        set(10, "stone_bricks", CompanionTexts.tr("panel.build.house", CompanionTexts.tr("panel.style.stone"), sizeLabel()));
        set(11, "birch_planks", CompanionTexts.tr("panel.build.house", CompanionTexts.tr("panel.style.birch"), sizeLabel()));
        set(12, "ladder", CompanionTexts.tr("panel.build.size", sizeLabel()));
        set(14, "wheat", CompanionTexts.tr("panel.build.farm"));
        set(16, "bookshelf", CompanionTexts.tr("blueprint.catalog", CompanionMachines.ALL.size(), schematics.size()));
        set(17, "spectral_arrow", CompanionTexts.tr("panel.build.next", schemOffset / SCHEM_COUNT + 1));

        for (int i = 0; i < SCHEM_COUNT && schemOffset + i < CompanionMachines.ALL.size() + schematics.size(); i++) {
            int index = schemOffset + i;
            if (index < CompanionMachines.ALL.size()) {
                var blueprint = CompanionMachines.ALL.get(index);
                set(SCHEM_FIRST + i, blueprint.icon(), CompanionTexts.tr("blueprint.button", blueprint.title()));
                continue;
            }
            java.nio.file.Path f = schematics.get(index - CompanionMachines.ALL.size());
            String ext = f.getFileName().toString().substring(f.getFileName().toString().lastIndexOf('.') + 1);
            set(SCHEM_FIRST + i, "nbt".equals(ext) ? "structure_block" : "litematic".equals(ext) ? "writable_book" : "map",
                    CompanionTexts.tr("panel.build.file", CompanionTexts.buildName(CompanionSchematics.baseName(f)), CompanionTexts.literal(ext)));
            var entry = CompanionSchematicCatalogue.find(CompanionSchematics.baseName(f));
            if (entry != null) panel.getItem(SCHEM_FIRST + i).set(DataComponents.LORE,
                    new net.minecraft.world.item.component.ItemLore(List.of(entry.description())));
        }

        if (job == null) {
            materials = Map.of();
            set(MAT_FIRST + 4, "paper", CompanionTexts.tr("panel.materials.waiting"));
        } else if (creative) {
            materials = Map.of();
            set(MAT_FIRST + 4, "command_block", CompanionTexts.tr("panel.materials.creative"));
        } else {
            materials = CompanionBrain.top(remaining(), MAT_COUNT);
            if (materials.isEmpty()) set(MAT_FIRST + 4, "lime_dye", CompanionTexts.tr("panel.materials.ready"));
            int i = 0;
            for (var en : materials.entrySet()) {
                int have = countItem(en.getKey());
                ItemStack st = new ItemStack(en.getKey(), Math.max(1, Math.min(en.getKey().getDefaultMaxStackSize(), en.getValue())));
                Component itemName = st.getHoverName();
                st.set(DataComponents.CUSTOM_NAME, CompanionTexts.tr("panel.materials.item", en.getValue(), itemName, have));
                panel.setItem(MAT_FIRST + i++, st);
            }
        }
    }

    /** Restmaterial höchstens alle {@link #REMAINING_INTERVAL} Menü-Ticks neu berechnen (großer Bauplan = teuer). */
    private Map<Item, Integer> remaining() {
        if (remainingStale || tick - remainingTick >= REMAINING_INTERVAL) {
            remaining = CompanionSnapshot.remaining(comp);
            remainingTick = tick;
            remainingStale = false;
        }
        return remaining;
    }

    private Component sizeLabel() {
        return CompanionTexts.tr("panel.size." + sizeIdx);
    }

    private int countItem(Item item) {
        int n = 0;
        for (int i = 0; i < comp.getInventory().getContainerSize(); i++) {
            ItemStack s = comp.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    // ------------------------------------------------------------------ Bedienung

    private void handleButton(int slot, boolean strong, ServerPlayer p) {
        if (page == Page.BUILD) buildButton(slot, strong, p);
        else if (page == Page.LOG) logButton(slot);
    }

    private void buildButton(int slot, boolean shift, ServerPlayer p) {
        Component reply = null;
        if (slot >= SCHEM_FIRST && slot < SCHEM_FIRST + SCHEM_COUNT) {
            int i = schemOffset + slot - SCHEM_FIRST;
            if (i < CompanionMachines.ALL.size())
                reply = preview(p, CompanionMachines.ALL.get(i), null);
            else if (i - CompanionMachines.ALL.size() < schematics.size())
                reply = preview(p, null, schematics.get(i - CompanionMachines.ALL.size()));
        } else {
            switch (slot) {
                case 2 -> { if (comp.build != null) reply = CompanionBrain.pauseBuildReply(comp, !comp.build.paused); }
                case 3 -> { if (comp.build != null) reply = CompanionBrain.cancelBuildReply(comp); }
                case 4 -> reply = cycleBuildMovement();
                case 5 -> reply = toggleBuildMode(p);
                case 6 -> rotIdx = (rotIdx + 1) % ROTATIONS.length;
                case 7 -> withAir = !withAir;
                case 9 -> reply = CompanionBrain.orderStructureReply(p, comp, List.of("holz", SIZES[sizeIdx]));
                case 10 -> reply = CompanionBrain.orderStructureReply(p, comp, List.of("stein", SIZES[sizeIdx]));
                case 11 -> reply = CompanionBrain.orderStructureReply(p, comp, List.of("birke", SIZES[sizeIdx]));
                case 12 -> sizeIdx = (sizeIdx + 1) % SIZES.length;
                case 13 -> reply = preview(p, CompanionMachines.find("weizenfarm_gross"), null);
                case 14 -> reply = preview(p, CompanionMachines.find(shift ? "weizenfarm_gross" : "weizenfarm"), null);
                case 15 -> { pendingThumbnails = 0; schemOffset = Math.max(0, schemOffset - SCHEM_COUNT); }
                case 16 -> {
                    pendingThumbnails = 0;
                    cataloguePlans.clear(); catalogueThumbnails.clear(); cachedPlanBlocks = 0;
                    schematics = CompanionSchematics.list(); schemOffset = 0;
                }
                case 17 -> {
                    pendingThumbnails = 0;
                    schemOffset = schemOffset + SCHEM_COUNT < CompanionMachines.ALL.size() + schematics.size() ? schemOffset + SCHEM_COUNT : 0;
                }
                default -> { return; }
            }
        }
        materials = Map.of();
        remainingStale = true; // neuer oder geänderter Auftrag: sofort neu rechnen
        report(reply);
        refresh();
    }

    private void go(Page np) {
        if (np == page) return;
        resetQuickCraft();
        page = np;
        pendingThumbnails = 0;
        previewBlueprint = null;
        previewPlan = null;
        remainingStale = true;
        if (page == Page.BUILD) schematics = CompanionSchematics.list();
        refresh();
    }

    private Component preview(ServerPlayer player, CompanionMachines.Blueprint blueprint, java.nio.file.Path file) {
        previewBlueprint = null;
        previewPlan = null;
        if (!ServerPlayNetworking.canSend(player, CompanionBuildPreview.TYPE))
            return blueprint != null ? CompanionPlacement.begin(player, comp, blueprint, ROTATIONS[rotIdx])
                    : CompanionPlacement.beginSchematic(player, comp, file, withAir, ROTATIONS[rotIdx]);
        try {
            var key = catalogueKey(blueprint, file);
            var plan = cataloguePlan(key, blueprint, file);
            if (plan.blocks().values().stream().allMatch(net.minecraft.world.level.block.state.BlockState::isAir))
                return CompanionTexts.tr("schematic.empty", CompanionTexts.buildName(plan.name()));
            previewBlueprint = blueprint;
            previewPlan = plan;
            ServerPlayNetworking.send(player, new CompanionBuildPreview(containerId, comp.getUUID(),
                    blueprint != null ? blueprint.title() : CompanionTexts.buildName(plan.name()), plan, -1,
                    cataloguePlans.get(key).modelId()));
            return null;
        } catch (Exception ex) {
            comp.logError(CompanionTexts.literal("Schematic preview: " + ex));
            return CompanionTexts.tr("panel.build.preview_error", CompanionTexts.literal(String.valueOf(ex.getMessage())));
        }
    }

    /** Vorschaubilder ersetzen nie den vollständigen Plan, der zum Platzieren gewählt ist. */
    private void thumbnail(ServerPlayer player, int slot) {
        int index = schemOffset + slot;
        if (index >= CompanionMachines.ALL.size() + schematics.size()
                || !ServerPlayNetworking.canSend(player, CompanionBuildPreview.TYPE)) return;
        try {
            var blueprint = index < CompanionMachines.ALL.size() ? CompanionMachines.ALL.get(index) : null;
            var file = blueprint == null ? schematics.get(index - CompanionMachines.ALL.size()) : null;
            var key = catalogueKey(blueprint, file);
            var small = catalogueThumbnails.get(key);
            if (small == null) {
                var plan = cataloguePlan(key, blueprint, file);
                // Die Voxel-Verkleinerung begrenzt Geometrie und Netzverkehr der Vorschau, auch bei Importen mit Millionen Blöcken.
                int step = Math.max(1, (int) Math.ceil(Math.max(plan.sizeX(), Math.max(plan.sizeY(), plan.sizeZ())) / 24.0));
                var blocks = new java.util.LinkedHashMap<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>();
                for (var e : plan.blocks().entrySet()) if (!e.getValue().isAir())
                    blocks.putIfAbsent(new net.minecraft.core.BlockPos(e.getKey().getX() / step,
                            e.getKey().getY() / step, e.getKey().getZ() / step), e.getValue());
                small = new CompanionSchematics.Schematic(plan.name(), (plan.sizeX() + step - 1) / step,
                        (plan.sizeY() + step - 1) / step, (plan.sizeZ() + step - 1) / step, blocks, java.util.Map.of());
                catalogueThumbnails.put(key, small);
                while (catalogueThumbnails.size() > 8) catalogueThumbnails.pollFirstEntry();
            }
            ServerPlayNetworking.send(player, new CompanionBuildPreview(containerId, comp.getUUID(),
                    panel.getItem(SCHEM_FIRST + slot).getHoverName(), small, slot));
        } catch (Exception ex) {
            comp.logError(CompanionTexts.literal("Catalogue thumbnail: " + ex));
        }
    }

    private CatalogueKey catalogueKey(CompanionMachines.Blueprint blueprint, java.nio.file.Path file) throws java.io.IOException {
        if (blueprint != null) return new CatalogueKey("builtin:" + blueprint.id(), 0, 0);
        var attributes = java.nio.file.Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
        return new CatalogueKey(file.toAbsolutePath().normalize().toString(), attributes.lastModifiedTime().toMillis(), attributes.size());
    }

    /** Vorschaubild und volle Vorschau teilen sich das Einlesen; gehaltene Pläne sind nach Anzahl und Blockzahl begrenzt. */
    private CompanionSchematics.Schematic cataloguePlan(CatalogueKey key, CompanionMachines.Blueprint blueprint,
            java.nio.file.Path file) throws java.io.IOException {
        var cached = cataloguePlans.get(key);
        if (cached != null) return cached.plan();
        var plan = blueprint != null ? blueprint.plan().get()
                : CompanionSchematics.load((net.minecraft.server.level.ServerLevel) comp.level(), file);
        cataloguePlans.put(key, new CachedPlan(plan, java.util.UUID.randomUUID()));
        cachedPlanBlocks += plan.blocks().size();
        while (cataloguePlans.size() > 8 || cachedPlanBlocks > CompanionSchematics.MAX_BLOCKS)
            cachedPlanBlocks -= cataloguePlans.pollFirstEntry().getValue().plan().blocks().size();
        return plan;
    }

    private void logButton(int slot) {
        switch (slot) {
            case LOG_FILTER -> { errorsOnly = !errorsOnly; logOffset = 0; }
            case LOG_NEWER -> logOffset = Math.max(0, logOffset - 22);
            case LOG_OLDER -> { if (logOffset + 22 < comp.logSize()) logOffset += 22; }
            case LOG_CLEAR -> { comp.clearLog(); logOffset = 0; }
            default -> { return; }
        }
        refresh();
    }
}
