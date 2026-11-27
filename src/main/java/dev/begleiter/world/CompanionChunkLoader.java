package dev.begleiter.world;

import dev.begleiter.CompanionMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Hält die Chunks um einen arbeitenden Begleiter geladen und simuliert, wie eine geworfene Enderperle: Er darf
 * in ungeladene Gegenden laufen und dort weiterarbeiten, statt am Rand der Spielersicht einzufrieren.
 * Geladen wird asynchron über das Ticket; Lesezugriffe ({@link SafeLevel}) laden weiterhin nie selbst nach und sehen
 * die neuen Chunks, sobald sie bereitstehen. Das Ticket läuft nach {@link #TIMEOUT} Ticks ohne Erneuerung ab und wird
 * nicht gespeichert: Wartet, pausiert oder stirbt der Begleiter, gibt er die Chunks von selbst frei.
 */
public final class CompanionChunkLoader {
    /** Radius 3: eigener Chunk und Nachbarn bewegen Entities, ein weiterer Ring tickt, der äußerste ist sichtbar. */
    public static final int RADIUS = 3;
    static final long TIMEOUT = 60;
    static final int RENEW_EVERY = 20;
    public static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, CompanionMod.id("companion"),
            new TicketType(TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    private CompanionChunkLoader() { }

    /** Beim Start aufrufen, damit der Tickettyp registriert ist, bevor die Registry eingefroren wird. */
    public static void register() {
        CompanionWakeList.WAKE_TICKET.hashCode(); // Weck-Tickettyp ebenfalls jetzt registrieren
    }

    /** Zustand pro Begleiter (nicht gespeichert). */
    public static final class State {
        ChunkPos chunk;
        long renewedAt = Long.MIN_VALUE;
        ServerLevel level;
        /** In der Weckliste eingetragen (dann beim Arbeitsende austragen). */
        boolean noted;
        long notedAt = Long.MIN_VALUE;

        public boolean active() { return chunk != null; }
        public ChunkPos chunk() { return chunk; }
    }

    /** So weit voraus muss die Welt schon bewegt werden, sonst nimmt er ein eigenes Ticket. */
    static final int LOOKAHEAD = 24;

    /**
     * Jeden Server-Tick. Nur nötig, wenn er arbeitet und die Gegend nicht schon von Spielern oder erzwungenen Chunks
     * bewegt wird; hat er einmal ein Ticket, behält er es bis zum Ende der Arbeit (sonst schaltete sein eigenes Ticket
     * es wieder ab). Beim Chunkwechsel sofort, sonst regelmäßig erneuern: Weil der eigene Chunk samt Nachbarn
     * Entities bewegt, erreicht er den nächsten Chunk nie außerhalb des Tickbereichs.
     */
    public static void tick(State state, ServerLevel level, BlockPos pos, boolean needed) {
        tick(state, level, pos, needed, null);
    }

    /**
     * Wie oben; mit Begleiter-ID wird die Arbeitsstelle außerdem in der {@link CompanionWakeList} gemerkt, damit er
     * nach einem Neustart weiterarbeitet, auch wenn kein Spieler in der Nähe ist.
     */
    public static void tick(State state, ServerLevel level, BlockPos pos, boolean needed, java.util.UUID id) {
        if (!needed) {
            if (state.noted && id != null) CompanionWakeList.get(level.getServer()).forget(id);
            state.noted = false;
            release(state);
            return;
        }
        if (id != null) {
            long now = level.getGameTime();
            if (!state.noted || now - state.notedAt >= RENEW_EVERY) {
                CompanionWakeList.get(level.getServer()).note(id, level, new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
                state.noted = true;
                state.notedAt = now;
            }
        }
        if (!state.active() && simulatedAround(level, pos)) return;
        tick(state, level, new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** Wird die Umgebung (eigene Stelle und {@link #LOOKAHEAD} Blöcke in alle acht Richtungen) schon bewegt? */
    public static boolean simulatedAround(ServerLevel level, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (!level.isPositionEntityTicking(pos.offset(dx * LOOKAHEAD, 0, dz * LOOKAHEAD))) return false;
        return true;
    }

    /** Ticket an dieser Stelle nehmen oder erneuern, ohne Vorprüfung. */
    public static void tick(State state, ServerLevel level, ChunkPos at) {
        long now = level.getGameTime();
        if (level == state.level && at.equals(state.chunk) && now - state.renewedAt < RENEW_EVERY) return;
        if (state.chunk != null && (level != state.level || !at.equals(state.chunk))) release(state);
        level.getChunkSource().addTicketWithRadius(TICKET, at, RADIUS);
        state.chunk = at;
        state.level = level;
        state.renewedAt = now;
    }

    /**
     * Begleiter ist endgültig weg (tot, entfernt): Ticket freigeben und nicht mehr wecken. Beim bloßen Entladen
     * (Speichern, Serverstopp) bleibt der Eintrag, sonst gäbe es nach dem Neustart nichts zu wecken.
     */
    public static void forget(State state, ServerLevel level, java.util.UUID id) {
        if (id != null && level != null) CompanionWakeList.get(level.getServer()).forget(id);
        state.noted = false;
        release(state);
    }

    /** Sofort freigeben (statt den Ablauf abzuwarten). */
    public static void release(State state) {
        if (state.chunk != null && state.level != null)
            state.level.getChunkSource().removeTicketWithRadius(TICKET, state.chunk, RADIUS);
        state.chunk = null;
        state.level = null;
        state.renewedAt = Long.MIN_VALUE;
    }
}
