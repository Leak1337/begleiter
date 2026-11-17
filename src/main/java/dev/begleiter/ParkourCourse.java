package dev.begleiter;

import java.util.List;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.nav.Move;
import dev.begleiter.nav.Movement;

/**
 * Parkour-Arena zum Vorführen (/companion parkourtest bauen | <bahn> | show | stop): 11 Bahnen nebeneinander
 * (je 8 Blöcke nach Süden), 30 Blöcke über dem Spieler. Start aus poliertem Andesit mit Bahnfarbe und Seelaterne,
 * Ziel aus glattem Quarz mit Goldblock in der Mitte (dort soll er landen), Schild je Bahn an der Aussicht.
 * Unter Sprung-Bahnen hellblaues Glas 5 tiefer als Netz; unter den Bahnen, auf denen er nicht springen darf, rotes
 * Glas 18 tiefer (zu tief zum Hinabsteigen – er bleibt oben stehen). "show" spielt alle Bahnen nacheinander ab.
 */
public final class ParkourCourse {
    private ParkourCourse() {}

    private static final int HEIGHT = 30, LANE_GAP = 8, LENGTH = 24, NET = 5, DEEP = 18;

    /** Eine Bahn: Schild-Zeilen, ob er springen soll, Ziel relativ zum Start (x, Höhe, z), Farbe. */
    private record Lane(String title, String sub, String expect, boolean jumps, int gx, int gy, int gz, DyeColor color) {
        String name() { return title + (sub.isEmpty() ? "" : " " + sub); }
    }

    private static final List<Lane> LANES = List.of(
            new Lane("Lücke 1", "", "springt", true, 8, 0, 0, DyeColor.WHITE),
            new Lane("Lücke 2", "", "springt", true, 9, 0, 0, DyeColor.ORANGE),
            new Lane("Lücke 3", "mit Sprint", "springt", true, 10, 0, 0, DyeColor.MAGENTA),
            new Lane("Lücke 4", "zu weit", "springt NICHT", false, 11, 0, 0, DyeColor.LIGHT_BLUE),
            new Lane("Stufe +1", "Lücke 1", "springt hoch", true, 8, 1, 0, DyeColor.YELLOW),
            new Lane("Stufe +1", "Lücke 2", "springt hoch", true, 9, 1, 0, DyeColor.LIME),
            new Lane("3 runter", "Lücke 2", "springt runter", true, 9, -3, 0, DyeColor.PINK),
            new Lane("Diagonal", "über Eck", "springt schräg", true, 8, 0, 4, DyeColor.GRAY),
            new Lane("Kette", "4 Sprünge", "ohne Anhalten", true, 14, 0, 0, DyeColor.CYAN),
            new Lane("Lava", "unter Lücke 2", "springt NICHT", false, 9, 0, 0, DyeColor.RED),
            new Lane("Decke", "zu niedrig", "springt NICHT", false, 9, 0, 0, DyeColor.BLACK));

    /** Ursprung der zuletzt gebauten Arena (Füße am Start von Bahn 1), oder null. */
    private static BlockPos origin;
    private static ServerLevel originLevel;

    public static int lanes() { return LANES.size(); }
    /** Für Tests: Bahnen wie erwartet in der letzten Vorführung (-1 = keine fertig). */
    public static int lastShowOk() { return lastShowOk; }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ParkourCourse::tick);
    }

    /** Weltreferenzen und Vorführstatus nach dem Serverende vollständig freigeben. */
    public static void reset() {
        show = null;
        origin = null;
        originLevel = null;
        lastShowOk = -1;
    }

    private static BlockState wool(DyeColor c) { return Blocks.WOOL.pick(c).defaultBlockState(); }
    private static BlockState glass(DyeColor c) { return Blocks.STAINED_GLASS.pick(c).defaultBlockState(); }

    // ------------------------------------------------------------------ Bauen

    /** Arena neben dem Spieler bauen und ihn auf die Aussicht setzen. */
    public static String build(ServerPlayer p) {
        // Also enforce permissions here in case a future command calls build directly.
        if (!dev.begleiter.debug.BotDebugServer.allowed(p))
            return "Nur Administratoren dürfen die Parkour-Testarena bauen.";
        return buildArena(p);
    }

    /**
     * Interne Arena für die serverseitige GameTest-Anbindung.
     * Not exposed as a player command or network action.
     */
    static String buildArena(ServerPlayer p) {
        ServerLevel sl = p.level();
        stopShow(null);
        BlockPos o = p.blockPosition().offset(6, HEIGHT, 0);
        int zMax = (LANES.size() - 1) * LANE_GAP + 6;
        fill(sl, o.offset(-7, -DEEP - 2, -4), o.offset(LENGTH, 6, zMax), Blocks.AIR.defaultBlockState());
        for (int i = 0; i < LANES.size(); i++) buildLane(sl, o.offset(0, 0, i * LANE_GAP), i);
        buildDeck(sl, o, zMax);
        origin = o;
        originLevel = sl;
        p.teleportTo(sl, o.getX() - 3.5, o.getY() + 4, o.getZ() + 0.5, java.util.Set.of(), -90f, 15f, true);
        StringBuilder sb = new StringBuilder("Parkour-Arena gebaut (Bahnen von Norden nach Süden):");
        for (int i = 0; i < LANES.size(); i++)
            sb.append("\n ").append(i + 1).append(": ").append(LANES.get(i).name()).append(" – ").append(LANES.get(i).expect);
        sb.append("\nEinzeln: /companion parkourtest <nummer>   Alle vorführen: /companion parkourtest show");
        return sb.toString();
    }

    private static void buildLane(ServerLevel sl, BlockPos s, int i) {
        Lane l = LANES.get(i);
        BlockState start = Blocks.POLISHED_ANDESITE.defaultBlockState(), target = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        BlockState pillar = Blocks.QUARTZ_BRICKS.defaultBlockState();
        int top = -1; // Oberkante der Plattformen (Füße bei 0)
        int widen = l.gz > 0 ? 4 : 0;
        // Netz: hellblau 5 tiefer (Sprung-Bahnen) bzw. rot 18 tiefer (darf nicht springen: dort bleibt er oben)
        int net = l.jumps ? -NET - 1 : -DEEP - 1;
        fill(sl, s.offset(-1, net, -2), s.offset(LENGTH - 4, net, 2 + widen), glass(l.jumps ? DyeColor.LIGHT_BLUE : DyeColor.RED));
        // Start: x 0..4, z -1..1; hinten Bahnfarbe mit Seelaterne in der Mitte
        fill(sl, s.offset(0, top, -1), s.offset(4, top, 1), start);
        fill(sl, s.offset(0, top, -1), s.offset(0, top, 1), wool(l.color));
        sl.setBlock(s.offset(0, top, 0), Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockPos goal = s.offset(l.gx, l.gy, l.gz);
        switch (i) {
            case 7 -> fill(sl, s.offset(6, top, 3), s.offset(10, top, 5), target); // Diagonal: Ziel schräg hinter der Ecke
            case 8 -> { // Kette: drei Trittsteine (1 lang), dann Ziel
                for (int x = 6; x <= 10; x += 2) fill(sl, s.offset(x, top, -1), s.offset(x, top, 1), pillar);
                fill(sl, s.offset(12, top, -1), s.offset(16, top, 1), target);
            }
            default -> {
                int gx = l.gx - 2, ty = top + l.gy;
                fill(sl, s.offset(gx, ty, -1), s.offset(gx + 4, ty, 1), target);
                // Stufe hoch: darunter auffüllen (echte Stufe); runter: Säule bis zum Netz
                if (l.gy != 0) fill(sl, s.offset(gx, net + 1, -1), s.offset(gx + 4, ty - 1, 1), pillar);
            }
        }
        sl.setBlock(goal.below(), Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS); // hier soll er landen
        if (i == 9) { // Lava 5 unter der Lücke, ringsum Glas
            BlockState g = Blocks.GLASS.defaultBlockState();
            fill(sl, s.offset(4, -NET - 1, -3), s.offset(7, -NET, 3), g);
            fill(sl, s.offset(5, -NET, -2), s.offset(6, -NET, 2), Blocks.LAVA.defaultBlockState());
        }
        if (i == 10) fill(sl, s.offset(5, 2, -1), s.offset(6, 2, 1), Blocks.GLASS.defaultBlockState()); // Decke über der Lücke
    }

    /** Aussicht aus Quarz links der Starts, Laternen, je Bahn ein Schild. */
    private static void buildDeck(ServerLevel sl, BlockPos o, int zMax) {
        fill(sl, o.offset(-6, 3, -3), o.offset(-2, 3, zMax - 3), Blocks.SMOOTH_QUARTZ.defaultBlockState());
        for (int z = -3; z <= zMax - 3; z += 4) sl.setBlock(o.offset(-6, 4, z), Blocks.LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int i = 0; i < LANES.size(); i++) {
            Lane l = LANES.get(i);
            BlockPos at = o.offset(-2, 4, i * LANE_GAP);
            // Schild an der Kante, Schrift zum Zuschauer (nach Westen)
            sl.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 4), Block.UPDATE_ALL);
            if (sl.getBlockEntity(at) instanceof SignBlockEntity sign) {
                List<Component> lines = List.of(
                        Component.literal("Bahn " + (i + 1)).withStyle(ChatFormatting.BOLD),
                        Component.literal(l.title),
                        Component.literal(l.sub),
                        Component.literal(l.expect).withStyle(l.jumps ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED));
                sign.setText(new SignText(lines, lines, DyeColor.BLACK, false), SignTextSlot.FRONT);
                sign.setWaxed(true);
                sign.setChanged();
                sl.sendBlockUpdated(at, sign.getBlockState(), sign.getBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static void fill(ServerLevel sl, BlockPos a, BlockPos b, BlockState st) {
        for (BlockPos q : BlockPos.betweenClosed(a, b)) sl.setBlock(q, st, Block.UPDATE_CLIENTS);
    }

    // ------------------------------------------------------------------ Einzelne Bahn

    private static BlockPos laneStart(int lane) { return origin.offset(0, 0, (lane - 1) * LANE_GAP); }
    private static BlockPos laneGoal(int lane) {
        Lane l = LANES.get(lane - 1);
        return laneStart(lane).offset(l.gx, l.gy, l.gz);
    }

    /** Begleiter an den Start einer Bahn setzen und zum Ziel (Goldblock) schicken. */
    public static String run(ServerPlayer p, CompanionEntity c, int lane) {
        if (origin == null || originLevel != p.level()) return "Erst bauen: /companion parkourtest bauen";
        if (lane < 1 || lane > LANES.size()) return "Bahnen 1 bis " + LANES.size();
        stopShow(null);
        start(c, lane);
        Lane l = LANES.get(lane - 1);
        return "Bahn " + lane + ": " + l.name() + " – erwartet: " + l.expect + " (F6: Pfad, F7: HUD)";
    }

    private static void start(CompanionEntity c, int lane) {
        BlockPos s = laneStart(lane);
        c.cancelAll();
        c.getNavigation().stop();
        c.setDeltaMovement(Vec3.ZERO);
        c.teleportTo(s.getX() + 1.5, s.getY(), s.getZ() + 0.5);
        c.fallDistance = 0;
        c.stay();
        c.addTask(CompanionEntity.Task.moveTo(laneGoal(lane), 1));
    }

    // ------------------------------------------------------------------ Vorführung

    private static final int PAUSE = 30, WAIT_NO_JUMP = 120, TIMEOUT = 400;

    /** Laufende Vorführung (nur eine gleichzeitig). */
    private static final class Show {
        ServerPlayer player;
        CompanionEntity companion;
        int lane = 1, ticks, pause = 20, ok;
        boolean jumped;
        String jumpInfo = "";
    }

    private static Show show;
    /** Ergebnis der letzten fertigen Vorführung (Bahnen wie erwartet), -1 = noch keine (für Tests). */
    static int lastShowOk = -1;

    public static String startShow(ServerPlayer p, CompanionEntity c) {
        if (origin == null || originLevel != p.level()) return "Erst bauen: /companion parkourtest bauen";
        show = new Show();
        lastShowOk = -1;
        show.player = p;
        show.companion = c;
        return "Vorführung: alle " + LANES.size() + " Bahnen nacheinander. Abbrechen: /companion parkourtest stop";
    }

    public static String stopShow(ServerPlayer p) {
        boolean was = show != null;
        show = null;
        return was ? "Vorführung abgebrochen." : "Es läuft keine Vorführung.";
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("ParkourCourse");

    private static void say(String s) {
        LOG.info("[Parkour-Show] {}", s);
        if (show != null) show.player.sendSystemMessage(CompanionTexts.prefixed(Component.literal(s)));
    }

    private static void tick(MinecraftServer server) {
        Show sh = show;
        if (sh == null) return;
        CompanionEntity c = sh.companion;
        if (!c.isAlive() || sh.player.isRemoved() || c.level() != originLevel) {
            say("Vorführung beendet (Begleiter oder Spieler nicht mehr da).");
            show = null;
            return;
        }
        if (sh.pause > 0) {
            if (--sh.pause == 0) {
                Lane l = LANES.get(sh.lane - 1);
                BlockPos s = laneStart(sh.lane);
                // Zuschauer mitnehmen: auf die Aussicht gegenüber der Bahn
                sh.player.teleportTo(originLevel, origin.getX() - 3.5, origin.getY() + 4, s.getZ() + 0.5, java.util.Set.of(), -90f, 20f, true);
                start(c, sh.lane);
                sh.ticks = 0;
                sh.jumped = false;
                sh.jumpInfo = "";
                say("Bahn " + sh.lane + ": " + l.name() + " – erwartet: " + l.expect);
            }
            return;
        }
        sh.ticks++;
        Lane l = LANES.get(sh.lane - 1);
        var ex = c.nav.executor();
        Movement m = ex == null ? null : ex.current();
        if (m != null && m.move.kind == Move.Kind.PARKOUR && m.phase != Movement.Phase.APPROACH) {
            sh.jumped = true;
            if (m.jump != null) sh.jumpInfo = m.jump.describe();
        }
        BlockPos goal = laneGoal(sh.lane);
        boolean arrived = c.onGround() && c.getBlockY() == goal.getY()
                && Math.abs(c.getBlockX() - goal.getX()) <= 1 && Math.abs(c.getBlockZ() - goal.getZ()) <= 1;
        String result = null;
        if (l.jumps && arrived) result = "✔ angekommen" + (sh.jumpInfo.isEmpty() ? "" : " (" + sh.jumpInfo + ")");
        else if (l.jumps && sh.ticks > TIMEOUT) result = "✘ nicht angekommen (bei " + c.blockPosition().subtract(laneStart(sh.lane)).toShortString()
                + ", " + c.nav.debugText() + ")";
        else if (!l.jumps && sh.jumped) result = "✘ ist gesprungen";
        else if (!l.jumps && sh.ticks > WAIT_NO_JUMP) result = "✔ nicht gesprungen";
        if (result == null) return;
        if (result.startsWith("✔")) sh.ok++;
        say("Bahn " + sh.lane + ": " + result);
        if (sh.lane >= LANES.size()) {
            say("Vorführung fertig: " + sh.ok + " von " + LANES.size() + " Bahnen wie erwartet.");
            lastShowOk = sh.ok;
            c.cancelAll();
            show = null;
            return;
        }
        sh.lane++;
        sh.pause = PAUSE;
    }
}
