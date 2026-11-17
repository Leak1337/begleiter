package dev.begleiter;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import dev.begleiter.client.gui.ZoneMapScreen;
import dev.begleiter.CompanionZones.Kind;
import dev.begleiter.CompanionZones.Zone;

/** Karte und Auswahl in der Welt teilen denselben serverseitigen Auswahlvorgang. */
public final class CompanionZoneClient {
    private CompanionZoneClient() {}
    private static CompanionZoneEditor.Open session;
    private static net.minecraft.client.multiplayer.ClientLevel level;
    private static Kind kind = Kind.BASE;
    private static BlockPos first, second, cursor;
    private static boolean world, waiting;
    private static int age;
    private static Component error = Component.empty();
    private static KeyMapping cornerKey, saveKey, cancelKey, mapKey;
    public static CompanionZoneEditor.Open session() { return session; }
    public static Kind kind() { return kind; }
    public static void kind(Kind k) { kind = k; first = second = null; error = Component.empty(); }
    public static BlockPos first() { return first; }
    public static BlockPos second() { return second; }
    public static Component error() { return error; }
    public static boolean waiting() { return waiting; }
    public static void corners(BlockPos a, BlockPos b) { if (!waiting) { first = a; second = b; error = Component.empty(); } }
    public static boolean ready() { return session != null && first != null && second != null
            && CompanionZones.between(kind, level.dimension().identifier().toString(), first, second).valid() && !waiting; }
    public static void register() {
        var category = KeyMapping.Category.register(CompanionMod.id("zones"));
        cornerKey = key("key.begleiter.zone_corner", InputConstants.KEY_Z, category);
        saveKey = key("key.begleiter.zone_save", InputConstants.KEY_G, category);
        cancelKey = key("key.begleiter.zone_cancel", InputConstants.KEY_BACKSPACE, category);
        mapKey = key("key.begleiter.zone_map", InputConstants.KEY_K, category);
        ClientPlayNetworking.registerGlobalReceiver(CompanionZoneEditor.Open.TYPE, (p, ctx) -> ctx.client().execute(() -> {
            clear(); session = p; level = ctx.client().level; kind = Kind.BASE;
            ctx.client().gui.setScreen(new ZoneMapScreen());
        }));
        ClientPlayNetworking.registerGlobalReceiver(CompanionZoneEditor.Feedback.TYPE, (p, ctx) -> ctx.client().execute(() -> {
            if (session == null || !session.token().equals(p.token())) return;
            waiting = false;
            if (p.finished()) {
                ctx.client().gui.chatListener().handleSystemMessage(CompanionTexts.prefixed(p.message()), false);
                clear();
                if (ctx.client().gui.screen() instanceof ZoneMapScreen) ctx.client().gui.setScreen(null);
            } else error = p.message();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> clear());
        ClientPlayConnectionEvents.JOIN.register((h, sender, mc) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(CompanionZoneClient::tick);
        LevelRenderEvents.END_EXTRACTION.register(ctx -> draw());
        HudElementRegistry.addLast(CompanionMod.id("zone_editor"), (g, delta) -> {
            var mc = Minecraft.getInstance();
            if (session == null || !world || mc.gui.screen() != null) return;
            g.fill(8, 8, 315, 64, 0xDC15202B);
            g.text(mc.font, Component.translatable("gui.begleiter.zones.world.title", kind.title()), 14, 14, kind.color, true);
            g.text(mc.font, Component.translatable("gui.begleiter.zones.world.keys", cornerKey.getTranslatedKeyMessage(),
                    saveKey.getTranslatedKeyMessage(), mapKey.getTranslatedKeyMessage(), cancelKey.getTranslatedKeyMessage()), 14, 28, 0xFFFFFFFF, true);
            g.text(mc.font, !error.getString().isEmpty() ? error : Component.translatable(waiting ? "gui.begleiter.zones.wait"
                    : first == null ? "gui.begleiter.zones.first" : second == null ? "gui.begleiter.zones.second" : "gui.begleiter.zones.selected"), 14, 44, 0xFFFFFFFF, true);
        });
    }
    private static KeyMapping key(String name, int code, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, code, category));
    }
    private static void clear() { session = null; level = null; first = second = cursor = null; world = waiting = false; age = 0; error = Component.empty(); }
    public static void inWorld() { if (waiting) return; world = true; Minecraft.getInstance().gui.setScreen(null); }
    public static void send(int operation) {
        if (session == null || waiting || operation == 0 && !ready()) return;
        waiting = true;
        ClientPlayNetworking.send(new CompanionZoneEditor.Edit(session.token(), kind,
                first == null ? BlockPos.ZERO : first, second == null ? BlockPos.ZERO : second, operation));
    }
    private static void tick(Minecraft mc) {
        boolean mark = cornerKey.consumeClick(), save = saveKey.consumeClick(), cancel = cancelKey.consumeClick(), map = mapKey.consumeClick();
        if (session == null) return;
        if (mc.level != level || mc.player == null || !mc.player.isAlive() || ++age > 6000) {
            clear(); if (mc.gui.screen() instanceof ZoneMapScreen) mc.gui.setScreen(null); return;
        }
        if (!world || mc.gui.screen() != null) return;
        if (cancel) { send(2); return; }
        if (map) { world = false; mc.gui.setScreen(new ZoneMapScreen()); return; }
        HitResult hit = mc.player.pick(32, 0, false);
        cursor = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos().above() : null;
        if (mark && cursor != null && !waiting) {
            if (first == null || second != null) { first = cursor; second = null; } else second = cursor;
            error = Component.empty();
        }
        if (save && ready()) send(0);
    }
    private static void draw() {
        if (session == null || !world || Minecraft.getInstance().level != level) return;
        String dimension = level.dimension().identifier().toString();
        for (Zone z : session.zones()) if (z.dimension().equals(dimension))
            Gizmos.cuboid(z.bounds(), GizmoStyle.stroke(z.kind().color, 1.5f)).setAlwaysOnTop();
        BlockPos end = second != null ? second : cursor;
        if (first != null && end != null) {
            Zone z = CompanionZones.between(kind, dimension, first, end);
            Gizmos.cuboid(z.bounds(), GizmoStyle.strokeAndFill(z.valid() ? kind.color : 0xFFFF5555, 2f,
                    0x20000000 | kind.color & 0xFFFFFF)).setAlwaysOnTop();
        }
    }
}
