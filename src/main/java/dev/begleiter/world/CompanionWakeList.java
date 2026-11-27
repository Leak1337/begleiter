package dev.begleiter.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.begleiter.CompanionMod;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Welche Begleiter beim Speichern gerade arbeiteten und wo (Dimension, Chunk). Das Arbeits-Ticket selbst wird nicht
 * gespeichert; nach einem Neustart stünde ein Begleiter fern vom Spieler sonst still, bis jemand vorbeikommt. Beim
 * Serverstart lädt {@link #wakeAll} diese Chunks kurz mit einem Weck-Ticket; der Begleiter lädt, arbeitet weiter und
 * übernimmt mit seinem eigenen Ticket. Wer sich in der Weckfrist nicht meldet (tot, entfernt, keine Arbeit mehr), wird
 * vergessen.
 */
public final class CompanionWakeList extends SavedData {
    public record Entry(Identifier dimension, long chunk) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("dim").forGetter(Entry::dimension),
                Codec.LONG.fieldOf("chunk").forGetter(Entry::chunk)).apply(i, Entry::new));
    }

    private static final Codec<CompanionWakeList> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC)
            .xmap(CompanionWakeList::new, list -> list.entries);
    public static final SavedDataType<CompanionWakeList> TYPE = new SavedDataType<>(
            CompanionMod.id("companion_wake"), CompanionWakeList::new, CODEC, null);
    /** Obergrenze gegen beschädigte Daten: mehr wird beim Laden nicht übernommen und nicht geweckt. */
    static final int MAX_ENTRIES = 256;
    /** So lange hält das Weck-Ticket die Chunks, und so lange darf sich ein Begleiter melden (Ticks). */
    public static final long WAKE_TICKS = 300;
    public static final TicketType WAKE_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, CompanionMod.id("companion_wake"),
            new TicketType(WAKE_TICKS, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    private final Map<UUID, Entry> entries = new HashMap<>();
    /** Seit dem letzten Wecken gemeldet (nicht gespeichert). */
    private final Set<UUID> seen = new HashSet<>();
    private long graceUntil = -1;

    public CompanionWakeList() { }

    private CompanionWakeList(Map<UUID, Entry> loaded) {
        for (var en : loaded.entrySet()) {
            if (entries.size() >= MAX_ENTRIES) break;
            entries.put(en.getKey(), en.getValue());
        }
    }

    public static CompanionWakeList get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Der Begleiter arbeitet hier (regelmäßig aufgerufen). Geschrieben wird nur bei neuem Chunk oder neuer Dimension. */
    public void note(UUID id, ServerLevel level, ChunkPos chunk) {
        seen.add(id);
        Entry now = new Entry(level.dimension().identifier(), chunk.pack());
        if (now.equals(entries.get(id))) return;
        if (!entries.containsKey(id) && entries.size() >= MAX_ENTRIES) return;
        entries.put(id, now);
        setDirty();
    }

    /** Arbeit beendet, Begleiter tot oder entfernt: nicht mehr wecken. */
    public void forget(UUID id) {
        seen.remove(id);
        if (entries.remove(id) != null) setDirty();
    }

    public Entry entry(UUID id) { return entries.get(id); }

    public int size() { return entries.size(); }

    /** Beim Serverstart: alle gemerkten Arbeitsstellen kurz laden. Unbekannte Dimensionen werden vergessen. */
    public void wakeAll(MinecraftServer server) {
        seen.clear();
        graceUntil = server.getTickCount() + WAKE_TICKS;
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, e.dimension()));
            if (level == null) {
                it.remove();
                setDirty();
                continue;
            }
            level.getChunkSource().addTicketWithRadius(WAKE_TICKET, ChunkPos.unpack(e.chunk()), CompanionChunkLoader.RADIUS);
        }
    }

    /** Jeden Server-Tick: nach Ablauf der Weckfrist alle vergessen, die sich nicht gemeldet haben. */
    public void tick(MinecraftServer server) {
        if (graceUntil < 0 || server.getTickCount() < graceUntil) return;
        graceUntil = -1;
        if (entries.keySet().removeIf(id -> !seen.contains(id))) setDirty();
    }

    /** Für Tests: die Weckfrist sofort ablaufen lassen. */
    public void endGraceNow(MinecraftServer server) {
        if (graceUntil >= 0) graceUntil = server.getTickCount();
        tick(server);
    }
}
