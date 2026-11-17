package dev.begleiter.client.gui.pages;

import dev.begleiter.CompanionMenu;
import dev.begleiter.client.gui.CompanionScreen;
import dev.begleiter.client.gui.ControlPage;
import dev.begleiter.client.gui.ControlTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Bauzentrale: liest ausschließlich die vorhandenen, serverseitig synchronisierten
 * Anzeigeslots. Bauaktionen und Materialbeschaffung bleiben in CompanionMenu/Builder.
 */
public final class BuildPage implements ControlPage {
    private int materialOffset;
    /** Bau-Modus, mit dem der Umschalter beschriftet wurde. */
    private boolean shownCreative;
    private boolean settings;
    private String shownOptions = "";
    @Override public CompanionMenu.Page page() { return CompanionMenu.Page.BUILD; }
    @Override public Item icon() { return Items.BRICKS; }

    /** Bau-Bewegung (0 Normal, 1 Fliegen, 2 Fliegen/Teleport, 3 Geist), mit der der zweite Umschalter beschriftet wurde. */
    private int shownMovement;
    private static final String[] MOVEMENT = {"normal", "fly", "teleport", "ghost"};

    @Override public void init(CompanionScreen s) {
        s.button(110, 39, s.wideBuild() ? 166 : 112, s.tr(settings ? "gui.begleiter.build.catalogue" : "gui.begleiter.build.options"),
                net.minecraft.world.item.ItemStack.EMPTY, s.tr("gui.begleiter.build.heading") + "\n" + s.tr("gui.begleiter.build.options.tooltip"), () -> settings,
                () -> { settings = !settings; s.rebuildPage(); });
        shownOptions = optionState(s);
        if (settings) {
            int[] slots = {9, 10, 11, 12, 14, 13, 6, 7};
            String[] labels = {"wood", "stone", "birch", "size", "farm", "farm_large", "rotation", "air"};
            for (int i = 0; i < slots.length; i++) {
                int slot = slots[i];
                s.button(110 + i % 2 * (s.wideBuild() ? 158 : 96), (s.wideBuild() ? 70 : 62) + i / 2 * (s.wideBuild() ? 40 : 24), s.wideBuild() ? 150 : 92,
                        s.tr("gui.begleiter.build.option." + labels[i]), net.minecraft.world.item.ItemStack.EMPTY,
                        s.container().getSlot(slot).getItem().getHoverName().getString(), () -> false,
                        () -> s.send(CompanionMenu.BUILD_PANEL_BUTTON + slot));
            }
        } else {
            s.button(s.wideBuild() ? 366 : 250, 39, 22, "‹", net.minecraft.world.item.ItemStack.EMPTY,
                    s.tr("gui.begleiter.build.catalogue.previous"), () -> false,
                    () -> s.send(CompanionMenu.BUILD_PANEL_BUTTON + 15));
            s.button(s.wideBuild() ? 392 : 276, 39, 22, "›", net.minecraft.world.item.ItemStack.EMPTY,
                    s.tr("gui.begleiter.build.catalogue.next"), () -> false,
                    () -> s.send(CompanionMenu.BUILD_PANEL_BUTTON + 17));
            for (int i = 0; i < CompanionMenu.SCHEM_COUNT; i++)
                s.buildCard(i, 110 + i % 2 * (s.wideBuild() ? 158 : 96), (s.wideBuild() ? 65 : 62) + i / 2 * (s.wideBuild() ? 88 : 51));
        }
        s.button(110, s.wideBuild() ? 297 : 211, s.wideBuild() ? 98 : 60, s.tr("gui.begleiter.build.job.pause"), net.minecraft.world.item.ItemStack.EMPTY,
                s.tr("gui.begleiter.build.job.pause.tooltip"), () -> s.data().workType() == 3,
                () -> s.send(CompanionMenu.BUILD_PANEL_BUTTON + 2));
        s.button(s.wideBuild() ? 214 : 174, s.wideBuild() ? 297 : 211, s.wideBuild() ? 98 : 60, s.tr("gui.begleiter.build.job.cancel"), net.minecraft.world.item.ItemStack.EMPTY,
                s.tr("gui.begleiter.build.job.cancel.tooltip"), () -> false,
                () -> s.send(CompanionMenu.BUILD_PANEL_BUTTON + 3));
        s.button(s.wideBuild() ? 318 : 238, s.wideBuild() ? 297 : 211, s.wideBuild() ? 100 : 60, s.tr("gui.begleiter.build.option.refresh"), net.minecraft.world.item.ItemStack.EMPTY,
                s.tr("gui.begleiter.build.catalogue.refresh.tooltip"), () -> false,
                () -> s.send(CompanionMenu.BUILD_PANEL_BUTTON + 16));
        shownCreative = s.data().creativeBuild();
        shownMovement = Math.clamp(s.data().buildMovement(), 0, MOVEMENT.length - 1);
        dev.begleiter.CompanionPlacementClient.syncMovement(shownMovement); // offene Vorschau zeigt die neue Wahl
        s.button(s.wideBuild() ? 353 : 233, s.wideBuild() ? 245 : 163, 65, s.tr(shownCreative ? "gui.begleiter.build.mode.creative" : "gui.begleiter.build.mode.survival"),
                net.minecraft.world.item.ItemStack.EMPTY, s.tr("gui.begleiter.build.mode.tooltip"),
                () -> s.data().creativeBuild(), () -> s.send(CompanionMenu.BUILD_MODE_TOGGLE));
        // Bewegung beim Bauen: Normal (laufen) → Fliegen → Fliegen/Teleport, zum Ausbalancieren
        s.button(s.wideBuild() ? 353 : 233, s.wideBuild() ? 268 : 186, 65, s.tr("gui.begleiter.build.movement." + MOVEMENT[shownMovement]),
                net.minecraft.world.item.ItemStack.EMPTY, s.tr("gui.begleiter.build.movement.tooltip"),
                () -> false, () -> s.send(CompanionMenu.BUILD_MOVEMENT_TOGGLE));
        // Blättern nur bei laufendem Auftrag; sonst steht dort der Hinweistext
        shownActive = s.data().workType() != 0;
        if (!shownActive) return;
        s.button(s.wideBuild() ? 333 : 213, s.wideBuild() ? 274 : 190, 15, ">", net.minecraft.world.item.ItemStack.EMPTY,
                s.tr("gui.begleiter.build.materials.next"), () -> false,
                () -> { materialOffset += 2; s.rebuildPage(); });
        s.button(s.wideBuild() ? 316 : 196, s.wideBuild() ? 274 : 190, 15, "<", net.minecraft.world.item.ItemStack.EMPTY,
                s.tr("gui.begleiter.build.materials.previous"), () -> false,
                () -> { materialOffset = Math.max(0, materialOffset - 2); s.rebuildPage(); });
    }
    private boolean shownActive;

    @Override public void render(CompanionScreen s, GuiGraphicsExtractor g, int mx, int my) {
        if (!settings && s.wideBuild()) s.text(g, s.tr("gui.begleiter.build.catalogue.page",
                s.container().getSlot(17).getItem().getHoverName().getString().replaceAll("[^0-9]", "")),
                282, 46, 78, ControlTheme.MUTED);
        int workType = s.data().workType();
        boolean active = workType != 0;
        boolean paused = workType == 3;
        s.text(g, s.tr(active ? switch (workType) {
                    case 2 -> "gui.begleiter.build.state.materials";
                    case 3 -> "gui.begleiter.build.state.paused";
                    case 1 -> "gui.begleiter.build.state.building";
                    default -> "gui.begleiter.build.state.active";
                } : "gui.begleiter.build.state.none"),
                110, s.wideBuild() ? 245 : 163, s.wideBuild() ? 238 : 118, paused ? 0xFFF0C674 : ControlTheme.ACCENT);

        if (active) {
            int percent = Math.clamp(s.data().buildPercent(), 0, 100);
            s.progressBar(g, 110, s.wideBuild() ? 258 : 176, s.wideBuild() ? 238 : 118, percent, paused ? 0xFFF0C674 : ControlTheme.ACCENT);
            s.text(g, s.tr("gui.begleiter.build.summary", percent, s.data().materialTypes(), s.data().missingItems()),
                    110, s.wideBuild() ? 263 : 181, s.wideBuild() ? 238 : 118, ControlTheme.TEXT);
            var rows = s.data().buildMaterials();
            materialOffset = Math.min(materialOffset, Math.max(0, rows.size() - 2));
            for (int i = 0; i < Math.min(2, rows.size() - materialOffset); i++) {
                var row = rows.get(materialOffset + i);
                Item material = BuiltInRegistries.ITEM.getValue(Identifier.parse(row.itemId()));
                String name = material.getDefaultInstance().getHoverName().getString();
                int missing = Math.max(0, row.needed() - row.owned());
                String label = name + " " + row.owned() + "/" + row.needed();
                s.text(g, label, 110, (s.wideBuild() ? 274 : 192) + i * 10, s.wideBuild() ? 200 : 84,
                        missing == 0 ? ControlTheme.ACCENT : 0xFFF0C674);
            }
        } else {
            s.paragraph(g, s.tr("gui.begleiter.build.hint"), 110, s.wideBuild() ? 263 : 181, s.wideBuild() ? 238 : 118, 2, ControlTheme.TEXT);
        }

    }

    private String optionState(CompanionScreen s) {
        return java.util.stream.IntStream.of(6, 7, 12)
                .mapToObj(i -> s.container().getSlot(i).getItem().getHoverName().getString())
                .collect(java.util.stream.Collectors.joining("|"));
    }

    @Override public boolean stale(CompanionScreen s) {
        return settings && !shownOptions.equals(optionState(s)) || s.data().creativeBuild() != shownCreative || s.data().buildMovement() != shownMovement
                || (s.data().workType() != 0) != shownActive;
    }

}
