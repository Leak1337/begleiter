package dev.begleiter.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.logging.LogUtils;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Serverseite des Bot-Debugs: prüft die Berechtigung bei JEDER Anfrage, sammelt begrenzte Schnappschüsse und führt
 * Steueraktionen aus. Nur {@link CompanionEntity} dieser Mod sind sichtbar und steuerbar.
 */
public final class BotDebugServer {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final int INTERVAL = 10;

    private static final class Sub {
        UUID selected;
        int level;
        boolean path;
        Component notice = Component.empty();
        boolean deniedSent;
    }

    private static final Map<UUID, Sub> SUBS = new HashMap<>();

    private BotDebugServer() {}

    /** Auch bei einem Serverende ohne regulären Disconnect alle Abonnements verwerfen. */
    public static void reset() {
        SUBS.clear();
    }

    /** Admins (Gamemaster-Rechte) und der Besitzer einer Einzelspielerwelt. */
    public static boolean allowed(ServerPlayer p) {
        MinecraftServer server = p.level().getServer();
        return p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                || server.isSingleplayerOwner(p.nameAndId());
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(BotDebug.Snapshot.TYPE, BotDebug.Snapshot.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BotDebug.Select.TYPE, BotDebug.Select.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BotDebug.Command.TYPE, BotDebug.Command.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BotDebug.Select.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer p = ctx.player();
            if (payload.level() <= BotDebug.LEVEL_OFF) { SUBS.remove(p.getUUID()); return; }
            Sub s = SUBS.computeIfAbsent(p.getUUID(), k -> new Sub());
            s.selected = payload.selected();
            s.level = Math.min(payload.level(), BotDebug.LEVEL_FULL);
            s.path = payload.path();
        }));
        ServerPlayNetworking.registerGlobalReceiver(BotDebug.Command.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            try {
                command(ctx.player(), payload);
            } catch (RuntimeException ex) {
                LOG.error("[Begleiter-Debug] command {} from {} failed", payload.action(), ctx.player().getName().getString(), ex);
            }
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SUBS.remove(handler.getPlayer().getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(BotDebugServer::tick);
    }

    // ------------------------------------------------------------------ Schnappschüsse

    private static void tick(MinecraftServer server) {
        if (SUBS.isEmpty() || server.getTickCount() % INTERVAL != 0) return;
        List<CompanionEntity> all = null;
        for (var it = SUBS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) { it.remove(); continue; }
            Sub s = e.getValue();
            if (!allowed(p)) {
                if (!s.deniedSent) {
                    s.deniedSent = true;
                    ServerPlayNetworking.send(p, new BotDebug.Snapshot(false, "", null, List.of(), null));
                }
                continue;
            }
            s.deniedSent = false;
            if (all == null) all = collect(server);
            // Ein fehlerhafter Schnappschuss darf den Server-Tick nicht abbrechen; das Abo endet, der Spieler
            // kann das Debug-Menü neu öffnen.
            try {
                send(p, s, all);
            } catch (RuntimeException ex) {
                LOG.error("[Begleiter-Debug] snapshot for {} failed, subscription dropped", p.getName().getString(), ex);
                it.remove();
            }
        }
    }

    private static List<CompanionEntity> collect(MinecraftServer server) {
        List<CompanionEntity> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels())
            for (Entity e : level.getAllEntities())
                if (e instanceof CompanionEntity c && c.isAlive() && !c.isRemoved()) out.add(c);
        return out;
    }

    private static void send(ServerPlayer p, Sub s, List<CompanionEntity> all) {
        Component notice = s.notice;
        s.notice = Component.empty();
        ServerPlayNetworking.send(p, snapshot(p, s.selected, s.level, s.path, notice, all));
    }

    /** Begrenzter Schnappschuss für einen berechtigten Spieler (öffentlich für Tests). */
    public static BotDebug.Snapshot snapshot(ServerPlayer p, UUID wanted, int level, boolean wantPath, String notice,
            List<CompanionEntity> all) {
        return snapshot(p, wanted, level, wantPath, Component.literal(notice), all);
    }

    public static BotDebug.Snapshot snapshot(ServerPlayer p, UUID wanted, int level, boolean wantPath, Component notice,
            List<CompanionEntity> all) {
        if (!allowed(p)) return new BotDebug.Snapshot(false, Component.empty(), null, List.of(), null);
        List<CompanionEntity> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble((CompanionEntity c) -> c.level() == p.level() ? 0 : 1e12)
                .thenComparingDouble(c -> c.level() == p.level() ? c.distanceToSqr(p) : 0)
                .thenComparing(c -> c.getUUID()));
        if (sorted.size() > BotDebug.MAX_BOTS) sorted = sorted.subList(0, BotDebug.MAX_BOTS);
        List<BotDebug.Entry> entries = new ArrayList<>(sorted.size());
        CompanionEntity selected = null;
        for (CompanionEntity c : sorted) {
            entries.add(entry(c));
            if (c.getUUID().equals(wanted)) selected = c;
        }
        if (selected == null && !sorted.isEmpty()) selected = sorted.getFirst();
        BotDebug.Detail detail = selected == null ? null : detail(selected, level >= BotDebug.LEVEL_FULL, wantPath);
        return new BotDebug.Snapshot(true, notice, selected == null ? null : selected.getUUID(), entries, detail);
    }

    private static int state(CompanionEntity c) {
        if (c.tickCount - c.problemTick < 20 * 30 && !c.problemText.isEmpty()) return BotDebug.STATE_ERROR;
        return c.isPaused() ? BotDebug.STATE_PAUSED : BotDebug.STATE_ACTIVE;
    }

    private static BotDebug.Entry entry(CompanionEntity c) {
        Component status = c.isPaused() ? CompanionTexts.tr("activity.paused")
                : c.currentTask() == null ? CompanionTexts.mode(c) : explain(c, c.currentTask());
        return new BotDebug.Entry(c.getUUID(), c.getId(), clip(c.getName().getString(), 200),
                c.level().dimension().identifier().toString(), c.getX(), c.getY(), c.getZ(), c.getHealth(),
                c.getMaxHealth(), c.hunger, state(c), status);
    }

    private static BotDebug.Detail detail(CompanionEntity c, boolean full, boolean wantPath) {
        var nav = c.nav;
        var exec = nav.executor();
        boolean pathOn = exec != null;
        int index = pathOn ? exec.index() : 0, length = pathOn ? exec.path.length() : 0;
        String move = "-";
        float step = 0;
        if (pathOn && exec.current() != null) {
            move = String.valueOf(exec.current().move);
            String jump = exec.current().debugInfo();
            if (!jump.isEmpty()) move += " · " + jump;
            if (index < length) step = (float) Math.sqrt(exec.path.positions.get(index).distSqr(exec.path.positions.get(index + 1)));
        }
        long goalPos = BotDebug.Detail.NO_POS;
        var task = c.currentTask();
        if (task != null && task.pos() != null) goalPos = task.pos().asLong();
        else if (pathOn) goalPos = exec.path.end().asLong();

        long[] path = new long[0];
        if (wantPath && pathOn) {
            List<BlockPos> pts = exec.path.positions;
            int from = Math.max(0, index - 8), to = Math.min(pts.size(), from + BotDebug.MAX_PATH - 1);
            path = new long[to - from + 1];
            path[0] = from; // erste Zahl: Index des ersten Punkts
            for (int i = from; i < to; i++) path[i - from + 1] = pts.get(i).asLong();
        }
        List<Long> bad = new ArrayList<>();
        for (var e : c.badSteps.entrySet()) {
            if (c.tickCount >= e.getValue()) continue;
            bad.add(e.getKey().asLong());
            if (bad.size() >= BotDebug.MAX_OBSTACLES) break;
        }
        long[] obstacles = bad.stream().mapToLong(Long::longValue).toArray();

        List<BotDebug.TaskLine> tasks = new ArrayList<>();
        for (var t : c.peekTasks(BotDebug.MAX_TASKS)) tasks.add(new BotDebug.TaskLine(t.type(), CompanionTexts.task(t)));

        List<BotDebug.ItemLine> inventory = new ArrayList<>(), equipment = new ArrayList<>();
        List<BotDebug.LogLine> log = new ArrayList<>();
        if (full) {
            var inv = c.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                int matching = -1;
                for (int j = 0; j < inventory.size(); j++)
                    if (ItemStack.isSameItemSameComponents(inventory.get(j).stack(), stack)) { matching = j; break; }
                if (matching >= 0) {
                    var prior = inventory.get(matching);
                    inventory.set(matching, new BotDebug.ItemLine(prior.stack(), prior.total() + stack.getCount(), -1));
                } else if (inventory.size() < BotDebug.MAX_INV) {
                    inventory.add(new BotDebug.ItemLine(stack, stack.getCount(), -1));
                }
            }
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
            for (int i = 0; i < slots.length; i++) {
                ItemStack stack = c.getItemBySlot(slots[i]);
                equipment.add(new BotDebug.ItemLine(stack, stack.getCount(), i));
            }
            for (var e : c.getLog(0, BotDebug.MAX_LOG, false)) log.add(logLine(c, e, false));
        } else {
            // Das HUD behält nur jüngste besondere Ereignisse; Alter, Wiederholungen und Farbart werden getrennt übertragen.
            for (var e : c.getLog(0, 80, false)) {
                int ago = Math.max(0, (c.tickCount - e.tick()) / 20);
                if (ago > BotDebug.RECENT_SEC || log.size() >= BotDebug.MAX_RECENT) break;
                if (e.kind() != BotDebug.EventKind.NONE) log.add(logLine(c, e, true));
            }
        }
        String goalText = nav.goal() == null ? "-" : nav.goal().toString();
        int errorAge = (c.tickCount - c.problemTick) / 20;
        return new BotDebug.Detail(c.getUUID(), CompanionTexts.mode(c),
                c.goal != null ? CompanionTexts.tr("debug.mission", CompanionTexts.tr("need." + c.goal.name().toLowerCase(java.util.Locale.ROOT)), c.goalScore)
                        : c.hasTasks() ? CompanionTexts.tr("needs.player_order") : Component.literal("-"),
                c.lastStage.isEmpty() ? Component.literal("-") : c.stageComponent(),
                task == null ? CompanionTexts.tr(c.isPaused() ? "activity.paused" : "activity.idle") : explain(c, task),
                c.actionSeconds(), c.tickCount / 20,
                c.goal != null ? c.goalReasonComponent() : Component.empty(), CompanionTexts.needs(c), CompanionTexts.hazards(c),
                c.problemComponent(), c.problemText.isEmpty() ? 0 : Math.max(0, errorAge),
                CompanionCraft.count(c, "diamond"), CompanionCraft.count(c, "iron_ingot") + CompanionCraft.count(c, "raw_iron"),
                CompanionCraft.count(c, "coal"), c.mine() == null ? "" : c.mine().toShortString(), c.usedSlots(),
                nav.active(), index, length, pathOn && !exec.path.complete, clip(move, 200), step, nav.lastNodes, (float) nav.lastMs,
                nav.eventComponent(), clip(goalText, 100), nav.failures(), goalPos,
                tasks, c.taskCount(), path, obstacles, inventory, equipment, log);
    }

    private static BotDebug.LogLine logLine(CompanionEntity c, CompanionEntity.LogEntry e, boolean recent) {
        return new BotDebug.LogLine(e.level(), Math.max(0, (c.tickCount - e.tick()) / 20), Math.max(0, e.tick() / 20),
                Math.max(1, e.count()), e.kind(), e.textComponent(), recent);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Übersetzte aktuelle Aktion mit Item-/Blocknamen aus dem Spiel und strukturierten Aufgabendaten. */
    private static Component explain(CompanionEntity c, CompanionEntity.Task t) {
        BlockPos at = c.blockPosition();
        Component where = t.pos() == null ? Component.empty()
                : CompanionTexts.tr("debug.action.distance", (int) Math.sqrt(t.pos().distSqr(at)));
        Component block = t.block() == null ? CompanionTexts.tr("debug.action.block") : t.block().getName();
        return switch (t.spec()) {
            case TaskSpec.Mine m -> CompanionTexts.tr("debug.action.mine", CompanionTexts.resource(m.resource()),
                    CompanionEntity.oreLevel(m.resource()), at.getY(), t.tries());
            case TaskSpec.Gather g -> CompanionTexts.tr("debug.action.gather", CompanionTexts.resource(g.resource()), g.count());
            case TaskSpec.Break b -> CompanionTexts.tr(b.block() != null && isOre(BuiltInRegistries.BLOCK.getKey(b.block()).getPath())
                    ? "debug.action.ore" : "debug.action.break", block, where);
            case TaskSpec.Place p -> CompanionTexts.tr("debug.action.place", block, where);
            case TaskSpec.MoveTo m -> CompanionTexts.tr("debug.action.move", where);
            case TaskSpec.Pillar p -> CompanionTexts.tr("debug.action.pillar", p.pos() == null ? "?" : p.pos().getY());
            case TaskSpec.Descend d -> CompanionTexts.tr("debug.action.descend");
            case TaskSpec.Climb c2 -> CompanionTexts.tr("debug.action.climb", where);
            case TaskSpec.Craft cr -> CompanionTexts.tr("debug.action.craft", CompanionTexts.item(cr.item()), cr.count());
            case TaskSpec.Smelt sm -> sm.item() == null ? CompanionTexts.tr("debug.action.smelt_any")
                    : CompanionTexts.tr("debug.action.smelt", CompanionTexts.item(sm.item()));
            case TaskSpec.Station st -> CompanionTexts.tr("debug.action.station", CompanionTexts.item(st.item()));
            case TaskSpec.Deliver d -> CompanionTexts.tr("debug.action.deliver");
            case TaskSpec.Collect c2 -> CompanionTexts.tr("debug.action.collect");
            case TaskSpec.Tree tr -> CompanionTexts.tr("debug.action.tree", where);
            case TaskSpec.Hunt hu -> CompanionTexts.tr("debug.action.hunt");
            case TaskSpec.Fight f -> f.mob() == null ? CompanionTexts.tr("debug.action.hunt")
                    : CompanionTexts.tr("debug.action.fight", CompanionTexts.animal(f.mob()));
            case TaskSpec.Travel tr -> CompanionTexts.tr("debug.action.travel", where);
            case TaskSpec.Pour p -> CompanionTexts.tr("debug.action.pour", where);
            case TaskSpec.Water w -> CompanionTexts.tr("debug.action.water");
            case TaskSpec.House ho -> CompanionTexts.tr("debug.action.house");
            case TaskSpec.Extend ex -> CompanionTexts.tr("debug.action.extend");
            default -> CompanionTexts.task(t);
        };
    }

    private static boolean isOre(String id) {
        return id.endsWith("_ore") || id.equals("ancient_debris") || id.equals("obsidian");
    }

    // ------------------------------------------------------------------ Steuerung

    private static CompanionEntity find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels())
            if (level.getEntity(id) instanceof CompanionEntity c && c.isAlive() && !c.isRemoved()) return c;
        return null;
    }

    private static void command(ServerPlayer p, BotDebug.Command cmd) {
        Component notice = executeReply(p, cmd);
        if (notice == null) return;
        Sub s = SUBS.computeIfAbsent(p.getUUID(), k -> new Sub());
        s.notice = notice;
        if (s.level > BotDebug.LEVEL_OFF) send(p, s, collect(p.level().getServer()));
    }

    /** Führt eine Steueraktion aus. Ergebnis: Meldung für den Spieler, oder null wenn ohne Berechtigung abgewiesen. */
    public static String execute(ServerPlayer p, BotDebug.Command cmd) {
        Component reply = executeReply(p, cmd);
        return reply == null ? null : reply.getString();
    }

    public static Component executeReply(ServerPlayer p, BotDebug.Command cmd) {
        if (!allowed(p)) {
            LOG.warn("[Begleiter-Debug] {} ohne Berechtigung abgewiesen (Aktion {})", p.getName().getString(), cmd.action());
            return null;
        }
        CompanionEntity c = find(p.level().getServer(), cmd.bot());
        if (c == null) return CompanionTexts.tr("debug.command.missing");
        Component what;
        switch (cmd.action()) {
            case BotDebug.A_PAUSE -> { c.setDebugFrozen(true); c.nav.stop(); c.getNavigation().stop(); what = CompanionTexts.tr("debug.command.paused"); }
            case BotDebug.A_RESUME -> { c.setDebugFrozen(false); c.resume(); what = CompanionTexts.tr("debug.command.resumed"); }
            case BotDebug.A_STEP -> { c.debugStep(20); what = CompanionTexts.tr("debug.command.step"); }
            case BotDebug.A_CANCEL -> { c.cancelOrders(); c.nav.stop(); what = CompanionTexts.tr("debug.command.cancelled"); }
            case BotDebug.A_GOTO -> {
                int y = Math.clamp(cmd.y(), c.level().getMinY(), c.level().getMaxY());
                BlockPos target = new BlockPos(cmd.x(), y, cmd.z());
                if (!c.level().getWorldBorder().isWithinBounds(target)) return CompanionTexts.tr("debug.command.outside");
                c.cancelAll();
                c.nav.stop();
                c.addTask(CompanionEntity.Task.moveTo(target));
                what = CompanionTexts.tr("debug.command.goto", target.toShortString());
            }
            case BotDebug.A_TELEPORT -> {
                if (!(p.level() instanceof ServerLevel to)) return CompanionTexts.tr("debug.command.no_level");
                c.nav.stop();
                c.getNavigation().stop();
                c.teleportTo(to, p.getX(), p.getY(), p.getZ(), Set.of(), c.getYRot(), c.getXRot(), true);
                what = CompanionTexts.tr("debug.command.teleported");
            }
            case BotDebug.A_HEALTH -> {
                c.setHealth(Math.clamp(cmd.x(), 1, (int) c.getMaxHealth()));
                what = CompanionTexts.tr("debug.command.health", (int) c.getHealth());
            }
            case BotDebug.A_HUNGER -> { c.hunger = Math.clamp(cmd.x(), 0, 20); what = CompanionTexts.tr("debug.command.hunger", c.hunger); }
            default -> { return CompanionTexts.tr("debug.command.unknown"); }
        }
        c.logInfo(CompanionTexts.tr("log.debug", p.getName(), what));
        LOG.info("[Begleiter-Debug] {} -> {}: {}", p.getName().getString(), c.getName().getString(), what.getString());
        return CompanionTexts.tr("debug.command.notice", c.getName(), what);
    }
}
