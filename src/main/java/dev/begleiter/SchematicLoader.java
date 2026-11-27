package dev.begleiter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Schematic-Dateien ohne Serverstillstand laden: Lesen und Dekomprimieren laufen auf einem kleinen, begrenzten
 * IO-Pool, alles Weltgebundene (Registry, Umrechnung in Blockzustände, Bauauftrag) im Serverthread. Pro Begleiter gilt
 * nur die jüngste Anfrage; ein verspätetes Ergebnis verfällt nach neuem Auftrag, Tod, Besitzer- oder Weltwechsel und
 * nach Serverstopp. Die Grenzen aus {@link CompanionSchematics} (16 MiB komprimiert, 32 MiB NBT, Blockzahl ...) gelten
 * unverändert.
 */
public final class SchematicLoader {
    static final int THREADS = 2;
    static final int QUEUE = 4;
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE), r -> {
                Thread t = new Thread(r, "Begleiter-Schematic-IO-" + THREAD_IDS.incrementAndGet());
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    static {
        IO.allowCoreThreadTimeOut(true);
    }

    private SchematicLoader() { }

    /** Zu viele gleichzeitige Leseaufträge. */
    static final class Busy extends IOException {
        Busy() { super("schematic loader busy"); }
    }

    /** Datei lesen und entpacken (nur IO, keine Weltdaten). Begrenzter Pool: voll bedeutet sofortiger Fehlschlag. */
    static CompletableFuture<CompoundTag> readAsync(Path file) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return CompanionSchematics.readChecked(file);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, IO);
        } catch (RejectedExecutionException busy) {
            return CompletableFuture.failedFuture(new Busy());
        }
    }

    /**
     * Schematic für diesen Begleiter laden und danach im Serverthread {@code onLoaded} ausführen; dessen Antwort geht an
     * den Spieler. Liefert die sofortige Antwort: Fehler, die ohne Lesen erkennbar sind, oder "wird geladen".
     */
    public static Component request(ServerPlayer player, CompanionEntity c, Path file,
                                    BiFunction<ServerPlayer, CompanionSchematics.Schematic, Component> onLoaded) {
        String fileName = file.getFileName().toString();
        try {
            CompanionSchematics.checkFile(file);
        } catch (IOException ex) {
            return unreadable(c, fileName, ex);
        }
        if (!(c.level() instanceof ServerLevel level)) return null;
        MinecraftServer server = level.getServer();
        int token = ++c.schematicRequest;
        UUID owner = c.getOwnerUuid();
        UUID playerId = player.getUUID();
        readAsync(file).whenComplete((tag, error) -> {
            try {
                // immer einreihen (auch wenn schon fertig gelesen): Übergabe erst im nächsten Abarbeiten, nie mitten im Auftrag
                server.schedule(server.wrapRunnable(() -> deliver(server, c, level, owner, token, playerId, file, tag, error, onLoaded)));
            } catch (RejectedExecutionException stopped) {
                // Server fährt herunter: Ergebnis verwerfen
            }
        });
        return CompanionTexts.tr("schematic.loading", CompanionTexts.literal(fileName));
    }

    private static void deliver(MinecraftServer server, CompanionEntity c, ServerLevel level, UUID owner, int token,
                                UUID playerId, Path file, CompoundTag tag, Throwable error,
                                BiFunction<ServerPlayer, CompanionSchematics.Schematic, Component> onLoaded) {
        if (!current(server, c, level, owner, token)) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        String fileName = file.getFileName().toString();
        Throwable cause = error instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : error;
        Component reply;
        if (cause instanceof Busy) {
            reply = CompanionTexts.tr("schematic.busy");
        } else if (cause != null) {
            reply = unreadable(c, fileName, cause);
        } else {
            CompanionSchematics.Schematic plan;
            try {
                plan = CompanionSchematics.parse(level, file, tag);
            } catch (IOException | RuntimeException ex) {
                send(player, unreadable(c, fileName, ex));
                return;
            }
            reply = onLoaded.apply(player, plan);
        }
        send(player, reply);
    }

    /** Gilt die Anfrage noch? Sonst wurde sie durch Neues, Tod, Besitzer-/Weltwechsel oder Serverstopp überholt. */
    static boolean current(MinecraftServer server, CompanionEntity c, ServerLevel level, UUID owner, int token) {
        return server.isRunning() && c.isAlive() && !c.isRemoved() && c.level() == level
                && c.schematicRequest == token && Objects.equals(c.getOwnerUuid(), owner);
    }

    /** Laufende Anfrage dieses Begleiters verwerfen (neuer Auftrag, Abbruch). */
    public static void cancel(CompanionEntity c) {
        c.schematicRequest++;
    }

    private static void send(ServerPlayer player, Component reply) {
        if (reply != null) player.sendSystemMessage(CompanionTexts.prefixed(reply));
    }

    static Component unreadable(CompanionEntity c, String fileName, Throwable ex) {
        c.logError(CompanionTexts.tr("log.schematic.error", CompanionTexts.literal(fileName), CompanionTexts.literal(String.valueOf(ex))));
        return CompanionTexts.tr("schematic.unreadable", CompanionTexts.literal(fileName), CompanionTexts.literal(String.valueOf(ex.getMessage())));
    }

    /** Für Tests: noch nicht abgeschlossene Leseaufträge. */
    static int pending() {
        return IO.getActiveCount() + IO.getQueue().size();
    }
}
