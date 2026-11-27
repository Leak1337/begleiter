package dev.begleiter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Erinnerungen gestorbener Begleiter, weltgebunden gespeichert (beim Overworld). Der Kern trägt nur noch eine kleine
 * Referenz (Seelen-ID) statt des ganzen Gedächtnisses. Ein Eintrag wird erst entfernt, wenn die Wiederbelebung
 * bestätigt ist. Gelöscht wird nie allein wegen des Alters: Ein gelagerter Kern oder ein Besitzer offline beweisen
 * nicht, dass der Kern nicht mehr existiert.
 */
public final class CompanionMemoryStore extends SavedData {
    private static final Codec<CompanionMemoryStore> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, CompoundTag.CODEC)
            .xmap(CompanionMemoryStore::new, store -> store.souls);
    public static final SavedDataType<CompanionMemoryStore> TYPE = new SavedDataType<>(
            CompanionMod.id("companion_memories"), CompanionMemoryStore::new, CODEC, null);
    /** Obergrenze, damit beschädigte oder missbrauchte Daten den Store nicht unbegrenzt wachsen lassen. */
    static final int MAX_SOULS = 4096;

    private final Map<UUID, CompoundTag> souls;

    public CompanionMemoryStore() {
        this.souls = new HashMap<>();
    }

    private CompanionMemoryStore(Map<UUID, CompoundTag> loaded) {
        this.souls = new HashMap<>();
        for (var en : loaded.entrySet()) {
            if (souls.size() >= MAX_SOULS) break;
            souls.put(en.getKey(), en.getValue().copy());
        }
    }

    public static CompanionMemoryStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Erinnerungen einer Seele ablegen (ersetzt einen älteren Stand derselben Seele). */
    public boolean put(UUID soul, CompoundTag memory) {
        if (!souls.containsKey(soul) && souls.size() >= MAX_SOULS) return false;
        souls.put(soul, memory.copy());
        setDirty();
        return true;
    }

    /** Lesen, ohne zu verbrauchen. null = unbekannte Seele (anderer Server, schon wiederbelebt, Daten fehlen). */
    public CompoundTag peek(UUID soul) {
        CompoundTag t = souls.get(soul);
        return t == null ? null : t.copy();
    }

    /** Nach bestätigter Wiederbelebung entfernen. */
    public void consume(UUID soul) {
        if (souls.remove(soul) != null) setDirty();
    }

    public int size() { return souls.size(); }
}
