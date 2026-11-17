package dev.begleiter;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Beobachtungslauf (kein Bestanden/Nicht-bestanden): der Bot spielt selbstständig aus einer neuen Survival-Welt
 * Richtung Enderdrache. Der Spieler ist Zuschauer und wird zum Bot bewegt, damit dessen Chunks geladen bleiben.
 * Fortschritt auf stdout: [PlayObs] Zustand alle 1200 Ticks, [CombatObs] bei Lebensverlust, [LoopObs] Fortschritts-
 * und Ziellosigkeitsanalyse (Weglänge gegen Nettostrecke, Inventaränderung, Aufgabenwechsel, Stufendauer) mit AIMLESS.
 */
public final class DragonFightClientTest implements FabricClientGameTest {
    private int id;
    private float lastHp;
    // --- aimlessness tracking (sampled every 40 ticks)
    private String stage = "";
    private int stageStartTick, stageStartInv;
    private Vec3 lastPos, windowStart;
    private double walked;
    private double minX, maxX, minZ, maxZ, minY, maxY;
    private int taskSwitches, windowStartInv;
    private String lastTask = "";

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().setUseConsistentSettings(false).create()) {
            world.getConnection().waitForChunksDownload();
            context.runOnClient(mc -> { var cfg = dev.begleiter.debug.BotDebugConfig.get(); cfg.esp = dev.begleiter.debug.BotDebugConfig.Esp.OFF; cfg.lines = false; cfg.nameTags = false; });
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var level = player.level();
                var bot = CompanionMod.COMPANION.create(level, EntitySpawnReason.COMMAND);
                bot.setOwner(player.getUUID()); bot.nav.trace.enable();
                bot.snapTo(player.getX() + 2, player.getY(), player.getZ(), 0, 0);
                level.addFreshEntity(bot);
                id = bot.getId();
                player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                server.tickRateManager().setTickRate(200f);
                System.out.println("[PlayObs] start");
            });
            for (int round = 0; round < 400; round++) {
                world.getServer().runOnServer(s -> s.tickRateManager().setTickRate(200f));
                context.runOnClient(mc -> mc.level.tickRateManager().setTickRate(200f));
                for (int i = 0; i < 30; i++) {
                    context.waitTicks(40);
                    world.getServer().runOnServer(this::sample);
                }
                boolean[] done = new boolean[1];
                world.getServer().runOnServer(server -> done[0] = report(server));
                if (done[0]) { System.out.println("[PlayObs] FINISHED (dragon defeated or bot gone)"); break; }
            }
        }
    }

    private static int invTotal(CompanionEntity c) {
        int n = 0;
        var inv = c.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) n += inv.getItem(i).getCount();
        return n;
    }

    private CompanionEntity bot(net.minecraft.server.MinecraftServer s) {
        for (ServerLevel l : s.getAllLevels()) if (l.getEntity(id) instanceof CompanionEntity b) return b;
        return null;
    }

    /** Every 40 ticks: combat logging, night skip and movement/progress bookkeeping. */
    private void sample(net.minecraft.server.MinecraftServer s) {
        CompanionEntity b = bot(s);
        if (b == null) return;
        ServerLevel l = (ServerLevel) b.level();
        String st = b.stageComponent().getString();
        if (b.getHealth() < lastHp || b.getHealth() < 30 || b.inDanger()) {
            var mobs = l.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, b.getBoundingBox().inflate(24));
            StringBuilder sb = new StringBuilder();
            for (var m : mobs) sb.append(m.getType().toShortString()).append("@").append((int) m.distanceTo(b)).append(m.hasLineOfSight(b) ? "+" : "-").append(" ");
            System.out.println("[CombatObs] t=" + s.getTickCount() + " hp=" + Math.round(b.getHealth()) + "/" + Math.round(b.getMaxHealth())
                    + " pos=" + b.blockPosition().toShortString() + " retreat=" + b.isRetreating() + " sheltered=" + b.isSheltered()
                    + " target=" + b.getTarget() + " hurtBy=" + b.getLastHurtByMob() + " task=" + b.currentTask() + " stage=" + st + " mobs=" + sb);
        }
        lastHp = b.getHealth();
        if (st.contains("daylight") || st.contains("night")) {
            try { s.getCommands().getDispatcher().execute("time set day", s.createCommandSourceStack()); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        }
        // progress bookkeeping
        Vec3 p = b.position();
        if (lastPos == null) { lastPos = p; windowStart = p; resetWindow(b, p); }
        walked += p.distanceTo(lastPos);
        lastPos = p;
        minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x); minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
        minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
        String task = String.valueOf(b.currentTask());
        if (!task.equals(lastTask)) { taskSwitches++; lastTask = task; }
        if (!st.equals(stage)) { stage = st; stageStartTick = s.getTickCount(); stageStartInv = invTotal(b); }
    }

    private void resetWindow(CompanionEntity b, Vec3 p) {
        walked = 0; taskSwitches = 0; windowStart = p; windowStartInv = invTotal(b);
        minX = maxX = p.x; minZ = maxZ = p.z; minY = maxY = p.y;
    }

    /** Alle 1200 Ticks: Zustandszeile und Ziellosigkeitsanalyse des letzten Fensters. */
    private boolean report(net.minecraft.server.MinecraftServer server) {
        CompanionEntity c = bot(server);
        if (c == null) { System.out.println("[PlayObs] t=" + server.getTickCount() + " bot GONE"); return true; }
        ServerLevel where = (ServerLevel) c.level();
        var player = server.getPlayerList().getPlayers().getFirst();
        player.teleportTo(where, c.getX(), c.getY(), c.getZ(), java.util.Set.of(), 0, 0, true);
        player.setCamera(c); // Spieler sieht durch die Augen des Bots
        System.out.println("[PlayObs] t=" + server.getTickCount() + " dim=" + where.dimension().identifier()
                + " hp=" + Math.round(c.getHealth()) + " pos=" + c.blockPosition().toShortString()
                + " stage=" + c.stageComponent().getString() + " task=" + c.currentTask()
                + " target=" + c.getTarget() + " ground=" + c.onGround() + " below=" + where.getBlockState(c.blockPosition().below()).getBlock()
                + " at=" + where.getBlockState(c.blockPosition()).getBlock() + " head=" + where.getBlockState(c.blockPosition().above()).getBlock());
        Vec3 now = c.position();
        double net = now.distanceTo(windowStart);
        double box = Math.hypot(maxX - minX, maxZ - minZ);
        int invDelta = invTotal(c) - windowStartInv;
        int stageTicks = server.getTickCount() - stageStartTick;
        int stageInvDelta = invTotal(c) - stageStartInv;
        // Ziellos: viel Laufen ohne Nettofortschritt, kleiner Bereich ohne Gewinn oder sehr lange Stufe ohne Gewinn.
        boolean circling = walked > 60 && net < 15 && invDelta <= 0;
        boolean wandering = walked > 150 && box < 40 && invDelta <= 0;
        boolean taskSpam = taskSwitches > 40 && invDelta <= 0;
        boolean stale = stageTicks > 4800 && stageInvDelta <= 0;
        String flag = circling ? "AIMLESS(circling)" : wandering ? "AIMLESS(small-area)" : taskSpam ? "AIMLESS(task-spam)" : stale ? "AIMLESS(stage-no-gain)" : "ok";
        System.out.println("[LoopObs] t=" + server.getTickCount() + " " + flag + " walked=" + Math.round(walked) + " net=" + Math.round(net)
                + " area=" + Math.round(box) + " dY=" + Math.round(maxY - minY) + " invDelta=" + invDelta + " taskSwitches=" + taskSwitches
                + " stageTicks=" + stageTicks + " stageInvDelta=" + stageInvDelta + " stage=" + c.stageComponent().getString()
                + " " + c.nav.trace.metrics());
        resetWindow(c, now);
        var end = server.getLevel(Level.END);
        return end != null && end.getDragonFight() != null && end.getDragonFight().hasPreviouslyKilledDragon();
    }
}
