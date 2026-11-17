package dev.begleiter.debug;

import java.util.List;
import java.util.UUID;

import com.mojang.blaze3d.platform.InputConstants;

import dev.begleiter.CompanionMod;
import dev.begleiter.client.gui.BotDebugScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Clientzustand des Bot-Debugs: letzter Server-Schnappschuss, Auswahl, Tasten und Abo (welches Detail der Server
 * schicken soll). Alles Anzeigen liest nur von hier – nie vom Server-Thread.
 */
public final class BotDebugClient {
    private static final long STALE_NANOS = 3_000_000_000L;

    public static KeyMapping menuKey, hudKey, pathKey;

    private static BotDebug.Snapshot snapshot;
    private static long received;
    private static UUID selected;
    private static BotDebug.Select lastSent;
    private static int sinceSent;

    private BotDebugClient() {}

    public static void register() {
        var category = KeyMapping.Category.register(CompanionMod.id("debug"));
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.begleiter.debug_menu", InputConstants.KEY_F8, category));
        hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.begleiter.debug_hud", InputConstants.KEY_F7, category));
        pathKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.begleiter.debug_path", InputConstants.KEY_F6, category));
        ClientPlayNetworking.registerGlobalReceiver(BotDebug.Snapshot.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
            snapshot = payload;
            received = System.nanoTime();
            boolean known = selected != null && payload.bots().stream().anyMatch(e -> e.id().equals(selected));
            if (!known) selected = payload.selected();
        }));
        ClientTickEvents.END_CLIENT_TICK.register(BotDebugClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
    }

    private static void reset() {
        snapshot = null;
        selected = null;
        lastSent = null;
    }

    private static void tick(Minecraft mc) {
        BotDebugConfig cfg = BotDebugConfig.get();
        while (hudKey.consumeClick()) { cfg.hud = !cfg.hud; cfg.save(); }
        while (pathKey.consumeClick()) { cfg.path = !cfg.path; cfg.save(); }
        while (menuKey.consumeClick())
            if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new BotDebugScreen());
        if (mc.player == null || mc.level == null) return;
        if (!ClientPlayNetworking.canSend(BotDebug.Select.TYPE)) return;
        boolean menu = mc.gui.screen() instanceof BotDebugScreen;
        int level = menu ? BotDebug.LEVEL_FULL
                : cfg.hud || cfg.esp != BotDebugConfig.Esp.OFF || cfg.lines || cfg.nameTags || cfg.path ? BotDebug.LEVEL_HUD : BotDebug.LEVEL_OFF;
        BotDebug.Select want = new BotDebug.Select(selected, level, cfg.path || (menu && ((BotDebugScreen) mc.gui.screen()).wantsPath()));
        // Nur bei Änderung senden, dazu alle 10 s als Lebenszeichen
        if (!want.equals(lastSent) || ++sinceSent > 200) {
            ClientPlayNetworking.send(want);
            lastSent = want;
            sinceSent = 0;
        }
    }

    public static void select(UUID id) {
        selected = id;
    }

    public static void send(int action, int x, int y, int z) {
        if (selected != null && ClientPlayNetworking.canSend(BotDebug.Command.TYPE))
            ClientPlayNetworking.send(new BotDebug.Command(selected, action, x, y, z));
    }

    /** Schreibt Eintrag und Detail des gewählten Bots als JSON, damit sich ein Fehler ohne Beschreibung auswerten lässt. */
    public static String dump() {
        BotDebug.Entry e = selectedEntry();
        BotDebug.Detail d = detail();
        if (e == null || d == null) return dev.begleiter.client.gui.GuiText.tr("debug.begleiter.control.none");
        try {
            java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("begleiter-debug-dump.json");
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("zeit", java.time.LocalDateTime.now().toString());
            out.put("bot", e);
            out.put("detail", d);
            // Anzeigetext und Item-ID ausgeben, statt per Reflection in Minecrafts Komponenten zu greifen.
            var gson = new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .registerTypeHierarchyAdapter(net.minecraft.network.chat.Component.class,
                            (com.google.gson.JsonSerializer<net.minecraft.network.chat.Component>) (text, type, context) ->
                                    new com.google.gson.JsonPrimitive(text.getString()))
                    .registerTypeHierarchyAdapter(net.minecraft.world.item.ItemStack.class,
                            (com.google.gson.JsonSerializer<net.minecraft.world.item.ItemStack>) (stack, type, context) -> {
                                var item = new com.google.gson.JsonObject();
                                item.addProperty("id", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                                item.addProperty("name", stack.isEmpty() ? "" : stack.getHoverName().getString());
                                item.addProperty("damage", stack.getDamageValue());
                                item.addProperty("maxDamage", stack.getMaxDamage());
                                return item;
                            }).create();
            java.nio.file.Files.writeString(file, gson.toJson(out));
            return dev.begleiter.client.gui.GuiText.tr("debug.begleiter.logs.dump.saved", file.getFileName());
        } catch (Exception ex) {
            return dev.begleiter.client.gui.GuiText.tr("debug.begleiter.logs.dump.failed", ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ Lesen

    /** Frischer Schnappschuss, sonst null (z. B. Server ohne Mod, Verbindung weg): dann bleibt nichts markiert. */
    private static BotDebug.Snapshot fresh() {
        return snapshot != null && System.nanoTime() - received < STALE_NANOS ? snapshot : null;
    }

    public static boolean hasData() { return fresh() != null; }
    public static boolean allowed() { BotDebug.Snapshot s = fresh(); return s != null && s.allowed(); }
    public static boolean denied() { BotDebug.Snapshot s = fresh(); return s != null && !s.allowed(); }
    public static UUID selected() { return selected; }
    public static String notice() { return snapshot == null ? "" : snapshot.notice(); }

    public static List<BotDebug.Entry> bots() {
        BotDebug.Snapshot s = fresh();
        return s == null || !s.allowed() ? List.of() : s.bots();
    }

    public static BotDebug.Entry selectedEntry() {
        for (BotDebug.Entry e : bots()) if (e.id().equals(selected)) return e;
        return null;
    }

    public static BotDebug.Detail detail() {
        BotDebug.Snapshot s = fresh();
        return s == null || s.detail() == null || !s.detail().id().equals(selected) ? null : s.detail();
    }
}
