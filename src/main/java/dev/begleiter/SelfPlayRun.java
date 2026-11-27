package dev.begleiter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Beobachteter Selbstspiel-Lauf auf einem Dedicated Server ohne Client ({@code .\gradlew runSelfplay},
 * {@code -Dbegleiter.selfplay=true}). Ein Bot-Spieler "Spieler" wird wie ein echter Spieler in die Spielerliste gesetzt
 * (wie GameTestHelper#makeMockServerPlayerInLevel), setzt den Begleiter mit dem Kern und gibt ihm per Chat das Spielziel.
 * <p>
 * Kompromiss "echter Spieler": Der Bot ist im Überlebensmodus (sonst baut der Begleiter mit Kreativ-Material), aber
 * unverwundbar und schwebt in der Nähe des Begleiters. Dadurch spawnen Mobs um ihn wie um einen Spieler, Chunks sind
 * geladen, Bauerlaubnis und Wiederbelebung funktionieren wie mit anwesendem Besitzer; feindliche Mobs ignorieren ihn
 * (Player#canBeSeenAsEnemy). Der Bot greift nur ein, wo ein Spieler es auch müsste: Kern nach einem Tod benutzen.
 * Jeder Eingriff steht als EINGRIFF im Protokoll {@code selfplay-log.txt} im Laufordner.
 */
public final class SelfPlayRun {
    static final boolean ENABLED = Boolean.getBoolean("begleiter.selfplay");
    static final String GOAL = System.getProperty("begleiter.selfplay.goal", "Spiel selbstständig bis zum Enderdrachen");
    /** Abbruch nach so vielen Spielticks (Standard 40 Spieltage). */
    static final long MAX_TICKS = Long.getLong("begleiter.selfplay.maxTicks", 40L * 24000L);
    static final boolean SPRINT = !"false".equals(System.getProperty("begleiter.selfplay.sprint"));
    static final int STATUS_EVERY = Integer.getInteger("begleiter.selfplay.statusEvery", 1200);
    static final String BOT_NAME = "Spieler";

    private static ServerPlayer bot;
    private static EmbeddedChannel channel;
    private static Path logFile;
    private static long startTick = -1, startNanos, lastStatusNanos, lastStatusTick;
    private static int phase; // 0 warten, 1 Begleiter gesetzt, 2 Ziel gegeben, 3 fertig
    private static long phaseAt, missingSince = -1, pausedSince = -1, stuckSince = -1, reviveWaitSince = -1;
    private static String lastJourney = "", lastTask = "", lastDim = "";
    private static BlockPos lastStuckPos;
    /** Zuletzt bekannte Basis (Inneres des Hauses, sonst Lager); dort setzt der Spieler den Kern wieder ein. */
    private static BlockPos lastHomeInside, lastHome;
    private static int deaths, revives, interventions, sprints, goalRepeats;
    private static boolean dragonLogged, savedNether, savedEnd;

    private SelfPlayRun() {}

    /** Welt speichern und nach run-selfplay-saves/NAME-tagN kopieren (ohne session.lock). */
    private static void snapshot(MinecraftServer server, String name) {
        try {
            server.saveEverything(true, true, true);
            Path world = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
            long day = server.overworld().getGameTime() / 24000;
            Path target = world.getParent().getParent().resolve("run-selfplay-saves").resolve(name + "-tag" + day).resolve("world");
            try (var files = java.nio.file.Files.walk(world)) {
                for (Path from : (Iterable<Path>) files::iterator) {
                    if (from.getFileName().toString().equals("session.lock")) continue;
                    Path to = target.resolve(world.relativize(from).toString());
                    if (java.nio.file.Files.isDirectory(from)) java.nio.file.Files.createDirectories(to);
                    else java.nio.file.Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            CompanionEntity c = companion(server);
            if (c != null) java.nio.file.Files.writeString(target.getParent().resolve("selfplay-state.txt"),
                    c.level().dimension().identifier() + " " + c.getBlockX() + " " + c.getBlockY() + " " + c.getBlockZ());
            log("WELTSTAND", "gesichert: " + target + " (zum Fortsetzen selfplay-state.txt neben world kopieren)");
        } catch (Exception ex) {
            log("WELTSTAND", "Sichern fehlgeschlagen: " + ex);
        }
    }

    public static void register() {
        if (!ENABLED) return;
        ServerLifecycleEvents.SERVER_STARTED.register(SelfPlayRun::start);
        ServerTickEvents.END_SERVER_TICK.register(SelfPlayRun::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            log("ENDE", summary(server));
            CompanionEntity c = bot == null ? null : companion(server);
            if (c != null) writeState(server, c);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof CompanionEntity c && bot != null && bot.getUUID().equals(c.getOwnerUuid())) {
                deaths++;
                log("TOD", "#" + deaths + " " + source.getLocalizedDeathMessage(c).getString() + " bei " + where(c)
                        + " | Ziel: " + journey(c) + " | Aufgabe: " + task(c));
            }
        });
    }

    // ------------------------------------------------------------------ Start

    private static void start(MinecraftServer server) {
        logFile = server.getServerDirectory().resolve("selfplay-log.txt");
        startNanos = lastStatusNanos = System.nanoTime();
        ServerLevel world = server.overworld();
        log("START", "Seed " + world.getSeed() + ", Ziel \"" + GOAL + "\", max " + MAX_TICKS + " Ticks, Sprint " + SPRINT);

        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(BOT_NAME), BOT_NAME);
        ClientInformation d = ClientInformation.createDefault();
        ClientInformation info = new ClientInformation("de_de", d.viewDistance(), d.chatVisibility(), d.chatColors(),
                d.modelCustomisation(), d.mainHand(), d.textFilteringEnabled(), d.allowsListing(), d.particleStatus());
        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, info, false);
        bot = new BotPlayer(server, world, profile, info);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, bot, cookie);

        bot.setGameMode(GameType.SURVIVAL);
        var abilities = bot.getAbilities();
        abilities.invulnerable = true;
        abilities.mayfly = true;
        abilities.flying = true;
        bot.onUpdateAbilities();
        startTick = world.getGameTime();
        phase = 0;
        phaseAt = startTick;
        log("BOT", "Spieler verbunden bei " + where(bot) + " (Überleben, unverwundbar, schwebt)");
    }

    // ------------------------------------------------------------------ Jeden Tick

    private static void tick(MinecraftServer server) {
        if (bot == null || phase == 3) return;
        long now = server.overworld().getGameTime();
        if (now % 20 == 0 && channel != null) channel.releaseOutbound(); // Pakete an den Bot verwerfen

        if (SPRINT && now % 100 == 0 && !server.tickRateManager().isSprinting()) {
            sprints++;
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                    "tick sprint 24000");
        }

        switch (phase) {
            case 0 -> {
                // Fortsetzen: zur zuletzt gemerkten Stelle des Begleiters, damit sein Chunk lädt und er sichtbar wird
                if (now - phaseAt == 20) goToSavedCompanion(server);
                if (now - phaseAt >= 100 && companion(server) == null && savedState != null && now - phaseAt < 400) break;
                if (now - phaseAt >= 100) {
                    // Gespeicherte Welt fortsetzen (Weltstand eines früheren Laufs): vorhandenen Begleiter übernehmen
                    CompanionEntity existing = companion(server);
                    if (existing != null) {
                        phase = 2;
                        phaseAt = now;
                        log("FORTSETZUNG", "Begleiter aus gespeicherter Welt übernommen bei " + where(existing) + " | " + journey(existing));
                    } else placeCompanion(server, now);
                }
            }
            case 1 -> {
                // Erst sprechen, wenn der Begleiter in Chat-Reichweite sichtbar ist (onChat sucht im Umkreis 64)
                CompanionEntity c = companion(server);
                if (now - phaseAt >= 40 && c != null && bot.level() == c.level() && bot.distanceToSqr(c) < 48 * 48) {
                    say(GOAL);
                    phase = 2;
                    phaseAt = now;
                }
            }
            default -> watch(server, now);
        }
        if (phase >= 1 && now % 20 == 0) follow(server, now);
        if (now - startTick >= MAX_TICKS) {
            log("ABBRUCH", "Höchstdauer erreicht");
            finish(server);
        }
    }

    /** Letzte bekannte Stelle des Begleiters (Dimension x y z), wird bei jedem Speichern geschrieben. */
    private static String[] savedState;

    private static Path stateFile(MinecraftServer server) {
        return server.getServerDirectory().resolve("selfplay-state.txt");
    }

    private static void writeState(MinecraftServer server, CompanionEntity c) {
        try {
            java.nio.file.Files.writeString(stateFile(server), c.level().dimension().identifier() + " "
                    + c.getBlockX() + " " + c.getBlockY() + " " + c.getBlockZ());
        } catch (java.io.IOException ignored) {
        }
    }

    private static void goToSavedCompanion(MinecraftServer server) {
        try {
            Path file = stateFile(server);
            if (!java.nio.file.Files.exists(file)) return;
            String[] parts = java.nio.file.Files.readString(file).trim().split(" ");
            if (parts.length != 4) return;
            ServerLevel level = null;
            for (ServerLevel l : server.getAllLevels()) if (l.dimension().identifier().toString().equals(parts[0])) level = l;
            if (level == null) return;
            int x = Integer.parseInt(parts[1]), y = Integer.parseInt(parts[2]), z = Integer.parseInt(parts[3]);
            savedState = parts;
            level.getChunk(x >> 4, z >> 4);
            bot.teleportTo(level, x + 0.5, Math.min(y + 6, level.getMaxY() - 2), z + 0.5, Set.of(), 0f, 0f, true);
            level.getChunkSource().move(bot);
            log("BOT", "zur gemerkten Stelle des Begleiters " + parts[0] + " " + x + ", " + y + ", " + z);
        } catch (Exception ex) {
            log("WARNUNG", "Stand nicht lesbar: " + ex);
        }
    }

    private static void placeCompanion(MinecraftServer server, long now) {
        ServerLevel world = server.overworld();
        world.getChunk(bot.getBlockX() >> 4, bot.getBlockZ() >> 4);
        BlockPos ground = world.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bot.blockPosition()).below();
        bot.teleportTo(world, ground.getX() + 0.5, ground.getY() + 1, ground.getZ() + 2.5, Set.of(), 0f, 0f, true);
        if (useCore(world, ground)) {
            phase = 1;
            phaseAt = now;
            log("BEGLEITER", "mit dem Kern gesetzt bei " + ground.above().toShortString());
        } else {
            phaseAt = now; // nächster Versuch in 100 Ticks
            log("WARNUNG", "Kern ließ sich nicht benutzen bei " + ground.toShortString());
        }
    }

    /** Wie ein Rechtsklick des Spielers mit dem Kern auf die Oberseite des Blocks. */
    private static boolean useCore(ServerLevel level, BlockPos block) {
        ItemStack core = findCore();
        if (core == null) core = new ItemStack(CompanionMod.COMPANION_CORE);
        bot.setItemInHand(InteractionHand.MAIN_HAND, core);
        var hit = new BlockHitResult(Vec3.atCenterOf(block).add(0, 0.5, 0), Direction.UP, block, false);
        var result = core.useOn(new UseOnContext(bot, InteractionHand.MAIN_HAND, hit));
        return result.consumesAction();
    }

    private static ItemStack findCore() {
        var inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(CompanionMod.COMPANION_CORE)) {
                inv.setItem(i, ItemStack.EMPTY);
                return s;
            }
        }
        return null;
    }

    /** Chat wie ein Spieler: derselbe Weg wie ServerMessageEvents.CHAT_MESSAGE in CompanionMod. */
    private static void say(String text) {
        log("CHAT", "<" + BOT_NAME + "> @ " + text);
        CompanionBrain.onChat(bot, "@ " + text);
    }

    // ------------------------------------------------------------------ Beobachten

    private static CompanionEntity companion(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            List<? extends CompanionEntity> found = level.getEntities(EntityTypeTest.forClass(CompanionEntity.class),
                    c -> c.isAlive() && bot.getUUID().equals(c.getOwnerUuid()));
            if (!found.isEmpty()) return found.get(0);
        }
        return null;
    }

    /** Der Bot schwebt über dem Begleiter (auch durch Portale), damit um ihn herum Mobs spawnen wie um einen Spieler. */
    private static void follow(MinecraftServer server, long now) {
        CompanionEntity c = companion(server);
        if (c == null) return;
        ServerLevel level = (ServerLevel) c.level();
        if (bot.level() != level || bot.distanceToSqr(c) > 20 * 20) {
            bot.teleportTo(level, c.getX(), Math.min(c.getY() + 6, level.getMaxY() - 2), c.getZ(), Set.of(), 0f, 0f, true);
            level.getChunkSource().move(bot);
        }
    }

    private static void watch(MinecraftServer server, long now) {
        CompanionEntity c = companion(server);
        if (c == null) {
            if (missingSince < 0) missingSince = now;
            if (now - missingSince == 20) log("FEHLT", "Begleiter nicht gefunden; Kern im Inventar: " + (hasCore()));
            if (now - missingSince >= 200 && hasCore()) revive(server, now);
            if (now - missingSince >= 24000 && (now - missingSince) % 24000 == 0)
                log("WARNUNG", "Begleiter seit " + (now - missingSince) / 24000 + " Tag(en) verschwunden");
            return;
        }
        missingSince = -1;
        if (c.houseInside() != null) lastHomeInside = c.houseInside().immutable();
        if (c.getHome() != null) lastHome = c.getHome().immutable();

        String dim = c.level().dimension().identifier().toString();
        if (!dim.equals(lastDim)) {
            log("DIMENSION", lastDim.isEmpty() ? dim : lastDim + " -> " + dim + " bei " + where(c));
            // Meilenstein-Weltstände sichern, damit sich Nether und End später gezielt mit neuem Code testen lassen
            if (!lastDim.isEmpty() && dim.endsWith("the_nether") && !savedNether) {
                savedNether = true;
                snapshot(server, "nether");
            } else if (!lastDim.isEmpty() && dim.endsWith("the_end") && !savedEnd) {
                savedEnd = true;
                snapshot(server, "end");
            }
            lastDim = dim;
        }
        String j = journey(c);
        // Countdown-Zahlen ("in 3 Sekunden") nicht als neues Ziel protokollieren
        if (!j.replaceAll("\\d+", "#").equals(lastJourney.replaceAll("\\d+", "#"))) {
            log("ZIEL", j);
            lastJourney = j;
        }
        String t = task(c);
        if (!t.equals(lastTask)) lastTask = t;

        // Stillstand: 3 Spielminuten an fast derselben Stelle mit Ziel
        BlockPos pos = c.blockPosition();
        // Nachts im Haus oder eingemauert warten ist kein Stillstand
        boolean nightWait = c.level().isDarkOutside() && (c.isInsideHouse() || c.isSheltered());
        if (lastStuckPos == null || lastStuckPos.distManhattan(pos) > 6 || nightWait) { lastStuckPos = pos; stuckSince = now; }
        else if (now - stuckSince == 3600) log("STILLSTAND", "seit 3 Min. bei " + where(c) + " | " + j + " | Aufgabe: " + t);
        else if (now - stuckSince > 3600 && (now - stuckSince) % 12000 == 0)
            log("STILLSTAND", "seit " + (now - stuckSince) / 1200 + " Min. bei " + where(c) + " | Aufgabe: " + t);

        // Kam das Ziel nicht an, sagt der Spieler es noch einmal (höchstens 5-mal)
        if (!c.journey.hasGoal() && goalRepeats < 5 && now - phaseAt >= 200) {
            goalRepeats++;
            interventions++;
            phaseAt = now;
            log("EINGRIFF", "Begleiter hat kein Spielziel, Spieler sagt es erneut (" + goalRepeats + ")");
            say(GOAL);
        }

        // Ein Spieler würde ein pausiertes Ziel nach einem Spieltag weiterlaufen lassen
        if (c.journey.hasGoal() && !c.journey.active() && j.toLowerCase(Locale.ROOT).contains("paus")) {
            if (pausedSince < 0) pausedSince = now;
            if (now - pausedSince >= 24000) {
                interventions++;
                log("EINGRIFF", "Ziel war einen Tag pausiert, Spieler sagt weiter");
                say("spielziel weiter");
                pausedSince = -1;
            }
        } else pausedSince = -1;

        if (!dragonLogged && server.getLevel(Level.END) != null) {
            var fight = server.getLevel(Level.END).getDragonFight();
            if (fight != null && fight.hasPreviouslyKilledDragon()) {
                dragonLogged = true;
                log("DRACHE", "Enderdrache besiegt! " + where(c));
            }
        }
        if (now % STATUS_EVERY == 0) status(server, c, now);
        // Regelmäßig speichern, damit sich ein Weltstand (z.B. nach dem Portal) kopieren und fortsetzen lässt
        if (now % 6000 == 0) {
            server.saveEverything(true, false, true);
            writeState(server, c); // passend zum gespeicherten Weltstand, damit Fortsetzen den Begleiter findet
        }
        if (j.contains(CompanionTexts.tr("journey.step.done").getString()) && dragonLogged) {
            log("ZIEL ERREICHT", j);
            finish(server);
        }
    }

    private static boolean hasCore() {
        var inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(CompanionMod.COMPANION_CORE)) return true;
        return false;
    }

    /**
     * Wie ein Spieler nach dem Tod seines Begleiters: zur Basis gehen und den Kern im Haus einsetzen (ohne bekannte
     * Basis am Weltspawn).
     */
    private static void revive(MinecraftServer server, long now) {
        ServerLevel world = server.overworld();
        BlockPos ground;
        if (lastHomeInside != null) {
            world.getChunkAt(lastHomeInside);
            ground = lastHomeInside.below();
        } else {
            BlockPos spawn = lastHome != null ? lastHome.east(2) : world.getRespawnData().pos();
            world.getChunkAt(spawn); // Chunk laden, sonst liefert die Heightmap den Weltboden
            ground = world.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn).below();
        }
        if (ground.getY() <= world.getMinY()) {
            log("WARNUNG", "Kein Boden bei " + ground.toShortString() + ", neuer Versuch");
            missingSince = now - 100;
            return;
        }
        // Erst hingehen und schauen (die Gegend muss geladen sein, damit Mobs sichtbar sind), dann entscheiden
        if (bot.level() != world || bot.distanceToSqr(Vec3.atCenterOf(ground)) > 8 * 8) {
            bot.teleportTo(world, ground.getX() + 0.5, ground.getY() + 1, ground.getZ() + 2.5, Set.of(), 0f, 0f, true);
            world.getChunkSource().move(bot);
            missingSince = now - 100; // in 100 Ticks prüfen
            return;
        }
        // Wie ein Spieler: nachts nicht mitten zwischen die Monster setzen, sondern warten (alle 100 Ticks neu prüfen)
        int hostiles = world.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(ground).inflate(16),
                m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy).size();
        if (world.isDarkOutside() && hostiles > 0) {
            if (reviveWaitSince < 0) {
                reviveWaitSince = now;
                log("WARTEN", hostiles + " Monster bei " + ground.above().toShortString() + "; Kern erst bei Tag oder ohne Monster");
            }
            missingSince = now - 100;
            return;
        }
        String waited = reviveWaitSince < 0 ? "" : String.format(Locale.ROOT, " nach %.1f Min. Warten", (now - reviveWaitSince) / 1200.0);
        reviveWaitSince = -1;
        interventions++;
        if (useCore(world, ground)) {
            revives++;
            log("EINGRIFF", "Kern benutzt, Begleiter wiederbelebt bei " + ground.above().toShortString() + waited);
            missingSince = -1;
            // Ziel steht im Kern-Gedächtnis; fehlt es, gibt der Spieler es neu
            CompanionEntity c = companion(server);
            if (c != null && !c.journey.hasGoal()) say(GOAL);
            else if (c != null && !c.journey.active()) say("spielziel weiter");
        } else log("WARNUNG", "Wiederbelebung mit dem Kern fehlgeschlagen");
    }

    private static void status(MinecraftServer server, CompanionEntity c, long now) {
        double realSec = (System.nanoTime() - lastStatusNanos) / 1e9;
        double tps = realSec <= 0 ? 0 : (now - lastStatusTick) / realSec;
        lastStatusNanos = System.nanoTime();
        lastStatusTick = now;
        long played = now - startTick;
        double mspt = server.getAverageTickTimeNanos() / 1e6;
        log("STATUS", String.format(Locale.ROOT,
                "Tag %.2f | %.0f Ticks/s, %.1f ms/Tick | %s | HP %.0f/%.0f Hunger %d Essen %d | %s | Aufgabe: %s | %s | Tode %d",
                played / 24000.0, tps, mspt, where(c), c.getHealth(), c.getMaxHealth(), c.food.level(), c.foodCount(),
                journey(c), task(c), keyItems(c), deaths));
    }

    private static String keyItems(CompanionEntity c) {
        var inv = c.getInventory();
        String[] ids = {"iron_ingot", "diamond", "obsidian", "blaze_rod", "ender_pearl", "ender_eye", "bread", "cooked_beef",
                "arrow", "nether_wart", "potion", "water_bucket"};
        int[] counts = new int[ids.length];
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            for (int k = 0; k < ids.length; k++) if (ids[k].equals(id)) counts[k] += s.getCount();
        }
        List<String> parts = new ArrayList<>();
        for (int k = 0; k < ids.length; k++) if (counts[k] > 0) parts.add(ids[k] + " " + counts[k]);
        var hand = c.getMainHandItem();
        // Rüstung als Stufen Kopf/Brust/Hose/Füße (E = Eisen, D = Diamant, N = Netherit, - = nichts), dazu der Bogen
        StringBuilder armor = new StringBuilder();
        for (String kind : new String[]{"helmet", "chestplate", "leggings", "boots"}) {
            int tier = CompanionJobs.pieceTier(c, kind);
            armor.append(tier >= 5 ? 'N' : tier == 4 ? 'D' : tier == 3 ? 'E' : tier > 0 ? 'L' : '-');
        }
        parts.add("Rüstung " + armor + (CompanionCraft.owns(c, "bow") ? ", Bogen" : ""));
        parts.add("Hand " + (hand.isEmpty() ? "-" : hand.getItem().toString()));
        return String.join(", ", parts);
    }

    private static String journey(CompanionEntity c) { return c.journey.status().getString(); }
    private static String task(CompanionEntity c) {
        CompanionEntity.Task t = c.currentTask();
        return t == null ? "keine" : CompanionEntity.describe(t);
    }
    private static String where(net.minecraft.world.entity.Entity e) {
        return e.level().dimension().identifier().getPath() + " " + e.blockPosition().toShortString();
    }

    private static String summary(MinecraftServer server) {
        long played = startTick < 0 ? 0 : server.overworld().getGameTime() - startTick;
        return String.format(Locale.ROOT, "%.2f Spieltage in %.0f Min. echt | Ziel: %s | Tode %d, Wiederbelebungen %d, "
                        + "Eingriffe %d, Sprints %d, Drache %s", played / 24000.0, (System.nanoTime() - startNanos) / 6e10,
                lastJourney, deaths, revives, interventions, sprints, dragonLogged ? "besiegt" : "nicht besiegt");
    }

    private static void finish(MinecraftServer server) {
        phase = 3;
        log("FERTIG", summary(server));
        server.halt(false);
    }

    static synchronized void log(String kind, String text) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " [" + kind + "] " + text;
        CompanionLog.LOG.info("[Selbstspiel] {}", line);
        if (logFile == null) return;
        try {
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    /** Fängt alles ab, was der Begleiter seinem Besitzer schreibt. */
    private static final class BotPlayer extends ServerPlayer {
        BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
            super(server, level, profile, info);
        }

        @Override
        public void sendSystemMessage(Component message, boolean overlay) {
            if (!overlay) log("SAGT", message.getString());
            super.sendSystemMessage(message, overlay);
        }
    }
}
