package dev.begleiter.client.gui;

import dev.begleiter.CompanionZoneClient;
import dev.begleiter.CompanionMod;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import dev.begleiter.CompanionZones;
import dev.begleiter.CompanionZones.Kind;
import dev.begleiter.CompanionZones.Zone;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import com.mojang.blaze3d.platform.InputConstants;

/** Lokale Geländekarte aus bereits geladenen Chunks, mit Rechteckauswahl und farbigen Arbeitszonen. */
public final class ZoneMapScreen extends Screen {
    private static final int CELLS = 96;
    private final int[] heights = new int[CELLS * CELLS];
    private final Identifier textureId = CompanionMod.id("zone_map/" + java.util.UUID.randomUUID());
    private DynamicTexture texture;
    private int sampleCursor;
    private static final int SAMPLE_BATCH = 768;
    private static final long SAMPLE_BUDGET_NS = 1_500_000;
    public boolean sampling() { return sampleCursor < CELLS * CELLS; }
    public Identifier mapTexture() { return textureId; }
    private final boolean[] loaded = new boolean[CELLS * CELLS];
    private int px, py, ph, mapX, mapY, mapSize, centerX, centerZ, span = 96;
    private boolean dragging;
    private BlockPos start;
    private ControlButton save, remove;
    public ZoneMapScreen() {
        super(Component.translatable("gui.begleiter.zones.title"));
        var s = CompanionZoneClient.session();
        if (s != null) { centerX = s.anchor().getX(); centerZ = s.anchor().getZ(); }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override protected void init() {
        int pw = Math.min(334, width - 8);
        ph = Math.min(270, height - 8); px = (width - pw) / 2; py = (height - ph) / 2;
        mapSize = Math.min(192, Math.min(pw - 138, ph - 70)); mapX = px + 8; mapY = py + 28;
        int sx = mapX + mapSize + 8, sw = px + pw - sx - 8;
        for (Kind k : Kind.values()) {
            var button = new ControlButton(sx, py + 28 + k.ordinal() * 21, sw, k.title().getString(), ItemStack.EMPTY,
                    GuiText.tr("gui.begleiter.zones.kind.tooltip", k.title()), () -> CompanionZoneClient.kind() == k,
                    () -> { dragging = false; CompanionZoneClient.kind(k); });
            addRenderableWidget(button);
        }
        int bottom = py + ph - 94;
        save = button(sx, bottom, sw, "gui.begleiter.zones.save", () -> CompanionZoneClient.send(0));
        remove = button(sx, bottom + 22, sw, "gui.begleiter.zones.remove", () -> CompanionZoneClient.send(1));
        button(sx, bottom + 44, sw, "gui.begleiter.zones.world", CompanionZoneClient::inWorld);
        button(sx, bottom + 66, sw, "gui.begleiter.close", this::onClose);
        if (texture == null) {
            texture = new DynamicTexture("Companion zone map", CELLS, CELLS, false);
            minecraft.getTextureManager().register(textureId, texture);
            sample();
        }
    }
    @Override public void removed() {
        if (texture != null) { minecraft.getTextureManager().release(textureId); texture = null; }
        super.removed();
    }
    private ControlButton button(int x, int y, int w, String key, Runnable action) {
        var b = new ControlButton(x, y, w, GuiText.tr(key), ItemStack.EMPTY, GuiText.tr(key + ".tooltip"), () -> false, action);
        addRenderableWidget(b); return b;
    }
    /** Neue Ansicht sofort entwerten; Gelände anschließend mit festem Budget über mehrere Ticks einlesen. */
    private void sample() {
        sampleCursor = 0;
        java.util.Arrays.fill(loaded, false);
        var pixels = texture.getPixels();
        for (int z = 0; z < CELLS; z++) for (int x = 0; x < CELLS; x++)
            pixels.setPixel(x, z, (x / 8 + z / 8) % 2 == 0 ? 0xFF18222C : 0xFF202C37);
        texture.upload();
    }
    private void sampleBatch() {
        if (minecraft.level == null || texture == null || !sampling()) return;
        long started = System.nanoTime();
        int end = Math.min(CELLS * CELLS, sampleCursor + SAMPLE_BATCH);
        var pixels = texture.getPixels();
        do {
            int i = sampleCursor++, x = i % CELLS, z = i / CELLS;
            int wx = centerX - span / 2 + x * span / CELLS, wz = centerZ - span / 2 + z * span / CELLS;
            loaded[i] = minecraft.level.getChunkSource().hasChunk(wx >> 4, wz >> 4);
            if (loaded[i]) {
                int y = minecraft.level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                heights[i] = y;
                BlockPos p = new BlockPos(wx, y - 1, wz);
                int rgb = minecraft.level.getBlockState(p).getMapColor(minecraft.level, p).col;
                pixels.setPixel(x, z, 0xFF000000 | (rgb == 0 ? 0x46525F : rgb));
            }
        } while (sampleCursor < end && System.nanoTime() - started < SAMPLE_BUDGET_NS);
        texture.upload();
    }
    @Override public void tick() {
        if (CompanionZoneClient.session() == null) { minecraft.gui.setScreen(null); return; }
        sampleBatch();
        save.active = CompanionZoneClient.ready();
        remove.active = !CompanionZoneClient.waiting() && CompanionZoneClient.session().zones().stream()
                .anyMatch(z -> z.kind() == CompanionZoneClient.kind() && z.dimension().equals(minecraft.level.dimension().identifier().toString()));
    }
    private boolean over(double x, double y) { return x >= mapX && y >= mapY && x < mapX + mapSize && y < mapY + mapSize; }
    private BlockPos position(double x, double y) {
        int cx = Math.clamp((int) ((x - mapX) * CELLS / mapSize), 0, CELLS - 1);
        int cz = Math.clamp((int) ((y - mapY) * CELLS / mapSize), 0, CELLS - 1);
        int i = cz * CELLS + cx;
        return loaded[i] ? new BlockPos(centerX - span / 2 + cx * span / CELLS, heights[i], centerZ - span / 2 + cz * span / CELLS) : null;
    }
    @Override public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
        if (over(e.x(), e.y()) && !CompanionZoneClient.waiting()) {
            start = position(e.x(), e.y()); dragging = start != null;
            if (dragging) CompanionZoneClient.corners(start, start);
            return true;
        }
        return super.mouseClicked(e, twice);
    }
    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging) { update(e); return true; }
        return super.mouseDragged(e, dx, dy);
    }
    private void update(MouseButtonEvent e) {
        BlockPos end = over(e.x(), e.y()) ? position(e.x(), e.y()) : null;
        // Eine Auswahl über unbekanntem Gelände kann nicht versehentlich den letzten gültigen Endpunkt speichern.
        CompanionZoneClient.corners(start, end);
    }
    @Override public boolean mouseReleased(MouseButtonEvent e) {
        if (dragging) { update(e); dragging = false; return true; }
        return super.mouseReleased(e);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (over(x, y) && !dragging && vertical != 0) {
            int next = Math.clamp(span + (vertical > 0 ? -48 : 48), 48, 192);
            if (next != span) { span = next; sample(); }
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean keyPressed(KeyEvent e) {
        if (!dragging && (e.key() == InputConstants.KEY_LEFT || e.key() == InputConstants.KEY_RIGHT
                || e.key() == InputConstants.KEY_UP || e.key() == InputConstants.KEY_DOWN)) {
            if (e.key() == InputConstants.KEY_LEFT) centerX -= 16;
            if (e.key() == InputConstants.KEY_RIGHT) centerX += 16;
            if (e.key() == InputConstants.KEY_UP) centerZ -= 16;
            if (e.key() == InputConstants.KEY_DOWN) centerZ += 16;
            sample(); return true;
        }
        return super.keyPressed(e);
    }
    @Override public void onClose() { if (!CompanionZoneClient.waiting()) CompanionZoneClient.send(2); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xB0081018);
        g.fill(px, py, px + Math.min(334, width - 8), py + ph, ControlTheme.BACKGROUND);
        g.text(font, title, px + 8, py + 8, ControlTheme.TEXT, false);
        // Gelände wird als eine zwischengespeicherte Textur gezeichnet, unabhängig von der Pixelzahl.
        if (texture != null) g.blit(textureId, mapX, mapY, mapX + mapSize, mapY + mapSize, 0, 1, 0, 1);
        g.enableScissor(mapX, mapY, mapX + mapSize, mapY + mapSize);
        var session = CompanionZoneClient.session();
        if (session != null && minecraft.level != null) {
            for (Zone zone : session.zones()) if (zone.dimension().equals(minecraft.level.dimension().identifier().toString())) box(g, zone, false);
            if (CompanionZoneClient.first() != null && CompanionZoneClient.second() != null)
                box(g, CompanionZones.between(CompanionZoneClient.kind(), "", CompanionZoneClient.first(), CompanionZoneClient.second()), true);
            if (minecraft.player != null) {
                int x = mapX + (int) ((minecraft.player.getX() - centerX + span / 2.) * mapSize / span);
                int z = mapY + (int) ((minecraft.player.getZ() - centerZ + span / 2.) * mapSize / span);
                g.fill(x - 2, z - 2, x + 3, z + 3, 0xFF111111); g.fill(x - 1, z - 1, x + 2, z + 2, 0xFFFFFFFF);
            }
        }
        g.disableScissor();
        int textY = mapY + mapSize + 5;
        for (var line : font.split(Component.translatable("gui.begleiter.zones.hint"), mapSize)) {
            g.text(font, line, mapX, textY, ControlTheme.MUTED, false); textY += 9;
        }
        Component notice = Component.translatable(sampling() ? "gui.begleiter.zones.loading" : CompanionZoneClient.waiting() ? "gui.begleiter.zones.wait"
                : CompanionZoneClient.first() != null && CompanionZoneClient.second() != null && !CompanionZoneClient.ready()
                ? "gui.begleiter.zones.limit" : "gui.begleiter.zones.legend");
        for (var line : font.split(notice, mapSize)) {
            if (textY + 8 >= py + ph) break;
            g.text(font, line, mapX, textY, ControlTheme.TEXT, false); textY += 9;
        }
        if (!CompanionZoneClient.error().getString().isEmpty()) {
            var lines = font.split(CompanionZoneClient.error(), mapSize - 8);
            g.fill(mapX, mapY, mapX + mapSize, mapY + 8 + lines.size() * 9, 0xEE17212A);
            for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), mapX + 4, mapY + 4 + i * 9, 0xFFFFAAAA, false);
        }
        if (over(mx, my) && CompanionZoneClient.error().getString().isEmpty()) {
            BlockPos pos = position(mx, my);
            if (pos != null) g.text(font, pos.getX() + ", " + pos.getZ(), mapX + 3, mapY + 3, 0xFFFFFFFF, true);
        }
        save.active = CompanionZoneClient.ready();
        super.extractRenderState(g, mx, my, delta);
        int sx = mapX + mapSize + 8, sw = px + Math.min(334, width - 8) - sx - 8;
        for (Kind k : Kind.values()) g.fill(sx + sw - 5, py + 33 + k.ordinal() * 21, sx + sw - 2,
                py + 43 + k.ordinal() * 21, k.color);
    }
    private void box(GuiGraphicsExtractor g, Zone zone, boolean selected) {
        if (zone.max().getX() < centerX - span / 2 || zone.min().getX() > centerX + span / 2
                || zone.max().getZ() < centerZ - span / 2 || zone.min().getZ() > centerZ + span / 2) return;
        int x = mapX + (zone.min().getX() - centerX + span / 2) * mapSize / span;
        int z = mapY + (zone.min().getZ() - centerZ + span / 2) * mapSize / span;
        int right = mapX + (zone.max().getX() + 1 - centerX + span / 2) * mapSize / span;
        int bottom = mapY + (zone.max().getZ() + 1 - centerZ + span / 2) * mapSize / span;
        int color = selected && !zone.valid() ? 0xFFFF6666 : zone.kind().color;
        g.fill(x, z, right, bottom, (selected ? 0x50000000 : 0x28000000) | color & 0xFFFFFF);
        g.outline(x, z, Math.max(1, right - x), Math.max(1, bottom - z), color);
    }
}
