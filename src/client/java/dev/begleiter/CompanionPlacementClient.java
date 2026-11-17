package dev.begleiter;

import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Bewegliche, durchscheinende Blockvorschau; kein Weltblock wird vor der Serverbestätigung gesetzt. */
public final class CompanionPlacementClient {
    private CompanionPlacementClient() {}
    private static KeyMapping rotate, confirm, cancel, up, down, movementKey;
    /** Bau-Bewegung in der Vorschau (0 Normal, 1 Fliegen, 2 Fliegen/Teleport, 3 Geist); wird mit der Bestätigung gesendet. */
    private static int movement;
    /**
     * Nur eine in der Vorschau mit M gewählte Bewegung wird mitgeschickt. Sonst gilt die des Begleiters – auch
     * wenn sie währenddessen im Menü umgestellt wurde (früher überschrieb der alte Vorschau-Wert die neue Wahl).
     */
    private static boolean movementChosen;

    /** Menü zeigt die aktuelle Bau-Bewegung des Begleiters: Vorschau-Anzeige nachziehen, solange nicht mit M gewählt. */
    public static void syncMovement(int current) {
        if (token != null && !movementChosen) movement = Math.clamp(current, 0, MOVEMENT.length - 1);
    }
    private static final String[] MOVEMENT = {"normal", "fly", "teleport", "ghost"};
    /** Freigabe des Servers (Maschine oder Schematic-Datei); null = keine Vorschau aktiv. */
    private static java.util.UUID token;
    private static Component title = Component.empty();
    private static boolean withAir = true;
    private static CompanionSchematics.Schematic plan;
    private static net.minecraft.client.multiplayer.ClientLevel dimension;
    private static int rotation, heightOffset, age;
    private static BlockPos center;
    private static Map<BlockPos, BlockState> blocks = Map.of();
    private static boolean valid, waiting;
    /**
     * Ab so vielen Blöcken zeigt die Vorschau nur den Umriss und die blockierten Stellen: einzelne Geisterblöcke
     * für Hunderttausende Positionen würden jedes Bild einbrechen lassen. Auch Neuberechnung und Prüfung laufen
     * dann gedrosselt statt jeden Tick.
     */
    private static final int DETAIL_LIMIT = 20_000;
    private static final int BLOCKED_SHOWN = 2_000;
    private static int lastLayout = -100, lastCheck = -100;
    private static int minX, minY, minZ, maxX, maxY, maxZ;
    private static List<BlockPos> blockedShown = List.of();
    /** Ergebnis der letzten Prüfung für kleine Pläne: das Zeichnen fragt nicht jedes Bild alle Blöcke neu ab. */
    private static java.util.Set<BlockPos> blockedAll = java.util.Set.of();
    private static Component error = Component.empty();

    static boolean active() { return token != null; }
    static BlockPos center() { return center; }
    static int rotation() { return rotation; }
    static boolean ready() { return valid && !waiting; }

    public static void register() {
        var category = KeyMapping.Category.register(CompanionMod.id("building"));
        rotate = key("key.begleiter.build_rotate", InputConstants.KEY_R, category);
        confirm = key("key.begleiter.build_confirm", InputConstants.KEY_RETURN, category);
        cancel = key("key.begleiter.build_cancel", InputConstants.KEY_BACKSPACE, category);
        up = key("key.begleiter.build_up", InputConstants.KEY_PAGEUP, category);
        down = key("key.begleiter.build_down", InputConstants.KEY_PAGEDOWN, category);
        movementKey = key("key.begleiter.build_movement", InputConstants.KEY_M, category);
        ClientPlayNetworking.registerGlobalReceiver(CompanionPlacement.Preview.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    clear();
                    var blueprint = CompanionMachines.find(payload.blueprint());
                    if (blueprint == null || payload.rotation() < 0 || payload.rotation() > 3) return;
                    token = payload.token(); title = blueprint.title(); withAir = true;
                    plan = blueprint.plan().get(); rotation = payload.rotation();
                    movement = Math.clamp(payload.movement(), 0, MOVEMENT.length - 1);
                    dimension = ctx.client().level;
                }));
        ClientPlayNetworking.registerGlobalReceiver(CompanionPlacement.SchematicPreview.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    clear();
                    if (payload.rotation() < 0 || payload.rotation() > 3) return;
                    token = payload.token(); title = CompanionTexts.buildName(payload.name()); withAir = payload.withAir();
                    plan = payload.plan(); rotation = payload.rotation();
                    movement = Math.clamp(payload.movement(), 0, MOVEMENT.length - 1);
                    dimension = ctx.client().level;
                }));
        ClientPlayNetworking.registerGlobalReceiver(CompanionPlacement.Feedback.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    if (token == null || !token.equals(payload.token())) return;
                    waiting = false;
                    if (payload.finished()) {
                        if (ctx.client().player != null)
                            ctx.client().gui.chatListener().handleSystemMessage(CompanionTexts.prefixed(payload.message()), false);
                        clear();
                    } else error = payload.message();
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(CompanionPlacementClient::tick);
        LevelRenderEvents.END_EXTRACTION.register(context -> drawWorld());
        HudElementRegistry.addLast(CompanionMod.id("build_preview"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (token == null || mc.player == null || mc.gui.screen() != null) return;
            List<Component> lines = List.of(
                    Component.translatable("gui.begleiter.placement.title", title, rotation * 90, heightOffset),
                    Component.translatable("gui.begleiter.placement.keys", confirm.getTranslatedKeyMessage(),
                            rotate.getTranslatedKeyMessage(), cancel.getTranslatedKeyMessage()),
                    Component.translatable("gui.begleiter.placement.height", up.getTranslatedKeyMessage(), down.getTranslatedKeyMessage()),
                    Component.translatable("gui.begleiter.placement.movement",
                            Component.translatable("gui.begleiter.build.movement." + MOVEMENT[movement]), movementKey.getTranslatedKeyMessage()),
                    !error.getString().isEmpty() ? error : Component.translatable(waiting ? "gui.begleiter.placement.wait"
                            : valid ? "gui.begleiter.placement.ready" : "gui.begleiter.placement.blocked"));
            int width = lines.stream().mapToInt(mc.font::width).max().orElse(200) + 16;
            g.fill(8, 8, width + 8, 76, 0xD015202B);
            for (int i = 0; i < lines.size(); i++)
                g.text(mc.font, lines.get(i), 16, 14 + i * 12, i == lines.size() - 1 && !valid ? 0xFFFF7777 : 0xFFFFFFFF, true);
        });
    }

    private static KeyMapping key(String name, int code, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, code, category));
    }

    private static void clear() {
        token = null; title = Component.empty(); withAir = true; movementChosen = false; plan = null; dimension = null; center = null; blocks = Map.of();
        error = Component.empty(); waiting = false; valid = false; heightOffset = 0; age = 0;
        lastLayout = -100; lastCheck = -100; blockedShown = List.of(); blockedAll = java.util.Set.of();
    }

    private static boolean large() { return blocks.size() > DETAIL_LIMIT; }

    /** Prüft alle Positionen; merkt sich Umriss und (begrenzt) die blockierten Stellen für die Anzeige. */
    private static void check(Minecraft mc) {
        boolean ok = true;
        var shown = new java.util.ArrayList<BlockPos>();
        var all = new java.util.HashSet<BlockPos>();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (var e : blocks.entrySet()) {
            BlockPos p = e.getKey();
            x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
            if (!safe(mc, p, e.getValue())) {
                ok = false;
                if (shown.size() < BLOCKED_SHOWN) shown.add(p);
                if (!large()) all.add(p);
            }
        }
        valid = ok && !blocks.isEmpty();
        blockedShown = shown;
        blockedAll = all;
        if (blocks.isEmpty() && center != null) { x0 = x1 = center.getX(); y0 = y1 = center.getY(); z0 = z1 = center.getZ(); }
        minX = x0; minY = y0; minZ = z0; maxX = x1; maxY = y1; maxZ = z1;
        lastCheck = age;
    }

    private static void tick(Minecraft mc) {
        // Auch außerhalb des Modus verbrauchen, damit alte Tastendrücke keinen neuen Plan bestätigen.
        boolean turn = rotate.consumeClick(), accept = confirm.consumeClick(), abort = cancel.consumeClick();
        int shift = (up.consumeClick() ? 1 : 0) - (down.consumeClick() ? 1 : 0);
        boolean nextMovement = movementKey.consumeClick();
        if (token == null) return;
        if (mc.level != dimension || mc.player == null || !mc.player.isAlive() || ++age > 20 * 300) {
            clear(); return;
        }
        if (mc.gui.screen() != null) return;
        if (abort) {
            ClientPlayNetworking.send(new CompanionPlacement.Confirm(token, BlockPos.ZERO, 0, true));
            mc.gui.chatListener().handleSystemMessage(CompanionTexts.prefixed(CompanionTexts.tr("placement.cancelled")), false);
            clear(); return;
        }
        if (waiting) return;
        if (turn) rotation = (rotation + 1) % 4;
        if (nextMovement) { movement = (movement + 1) % MOVEMENT.length; movementChosen = true; }
        heightOffset = Math.clamp(heightOffset + shift, -6, 16);
        HitResult hit = mc.player.pick(CompanionPlacement.REACH, 0, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            center = null; blocks = Map.of(); valid = false; return;
        }
        BlockPos target = blockHit.getBlockPos().above(heightOffset);
        boolean changed = !target.equals(center) || turn || shift != 0;
        // Große Pläne: höchstens alle 5 Ticks neu anordnen (Hunderttausende Positionen kosten spürbar Zeit)
        if (changed && (!large() || turn || shift != 0 || center == null || age - lastLayout >= 5)) {
            center = target;
            blocks = CompanionSchematics.placeAt(plan, center, CompanionPlacement.ROTATIONS[rotation], withAir);
            error = Component.empty();
            lastLayout = age;
            check(mc);
        } else if (!large() || age - lastCheck >= 20 || accept) check(mc);
        if (accept && valid) {
            waiting = true;
            ClientPlayNetworking.send(new CompanionPlacement.Confirm(token, center, rotation, false, movementChosen ? movement : -1));
        }
    }

    private static boolean safe(Minecraft mc, BlockPos pos, BlockState target) {
        return !mc.level.isOutsideBuildHeight(pos) && mc.level.getWorldBorder().isWithinBounds(pos)
                && mc.level.hasChunkAt(pos) && mc.level.getBlockEntity(pos) == null
                && CompanionPlacement.replaceable(mc.level, pos, mc.level.getBlockState(pos), target);
    }

    private static void drawWorld() {
        Minecraft mc = Minecraft.getInstance();
        if (token == null || mc.level == null || mc.level != dimension || center == null || blocks.isEmpty()) return;
        Gizmos.cuboid(new AABB(minX, center.getY() + 1.01, minZ, maxX + 1, center.getY() + 1.04, maxZ + 1),
                GizmoStyle.stroke(valid ? 0xFF66EEAA : 0xFFFF5555, 2f)).setAlwaysOnTop();
        if (large()) {
            // Umriss des ganzen Baus statt einzelner Geisterblöcke, dazu die (ersten) blockierten Stellen
            Gizmos.cuboid(new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1),
                    GizmoStyle.strokeAndFill(valid ? 0xFF66EEAA : 0xFFFF5555, 2f, valid ? 0x1866EEAA : 0x18FF5555));
            var red = GizmoStyle.strokeAndFill(0xFFFF5555, 1.5f, 0x50FF5555);
            for (BlockPos pos : blockedShown) Gizmos.cuboid(new AABB(pos).deflate(0.002), red);
            return;
        }
        for (var entry : blocks.entrySet()) {
            BlockState state = entry.getValue();
            BlockPos pos = entry.getKey();
            boolean blocked = blockedAll.contains(pos);
            if (state.isAir() && !blocked) continue;
            int rgb = blocked ? 0xFF5555 : state.getMapColor(mc.level, pos).col;
            if (rgb == 0) rgb = 0x66DDEE;
            var style = GizmoStyle.strokeAndFill(0xFF000000 | rgb, 1.5f, 0x50000000 | rgb);
            var shape = state.isAir() ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : state.getShape(mc.level, pos).toAabbs();
            if (shape.isEmpty()) shape = List.of(new AABB(0, 0, 0, 1, 1, 1));
            for (AABB box : shape) Gizmos.cuboid(box.move(pos).deflate(0.002), style);
        }
    }
}
