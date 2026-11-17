package dev.begleiter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.begleiter.chat.ChatText;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A server-safe, bilingual name index over the actual item registry, including installed mods. */
public final class CompanionItemNames {
    private static Map<String, Set<Item>> names;
    private static final List<java.util.function.Supplier<List<JsonObject>>> SOURCES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private CompanionItemNames() { }

    public static void reset() { names = null; }

    /** Weitere Sprachdateien (z. B. die deutschen Namen des installierten Spiels); wird beim nächsten Aufbau gelesen. */
    public static void addLanguageSource(java.util.function.Supplier<List<JsonObject>> source) {
        SOURCES.add(source);
        reset();
    }

    private static void add(Map<String, Set<Item>> index, String word, Item item) {
        String normalized = ChatText.normalize(word);
        if (!normalized.isBlank()) index.computeIfAbsent(normalized, k -> new LinkedHashSet<>()).add(item);
    }

    private static void translations(JsonObject json, Map<String, List<String>> translated) {
        json.entrySet().forEach(entry -> {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString())
                translated.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue().getAsString());
        });
    }

    private static Map<String, Set<Item>> index() {
        if (names != null) return names;
        Map<String, List<String>> translated = new LinkedHashMap<>();
        // Die Mod liefert keine Minecraft-Übersetzungen mit. Deutsch kommt vom installierten Spiel (Client, siehe
        // CompanionClient) oder in Entwicklung/Tests aus den Gradle-Assets; Englisch steht in jeder Spiel-JAR.
        for (var source : SOURCES) {
            try { source.get().forEach(json -> translations(json, translated)); }
            catch (RuntimeException ignored) { /* fehlende Sprache: dann nur IDs und die übrigen Namen */ }
        }
        String extra = System.getProperty("begleiter.languageFiles", "");
        for (String path : extra.split(java.io.File.pathSeparator)) {
            if (path.isBlank()) continue;
            try (var reader = Files.newBufferedReader(java.nio.file.Path.of(path), StandardCharsets.UTF_8)) {
                translations(JsonParser.parseReader(reader).getAsJsonObject(), translated);
            } catch (IOException | RuntimeException ignored) { }
        }
        // Sprachdateien der Mods und des Spiels selbst gibt es auch auf Dedicated Servern; keine Client-Klassen, kein HTTP.
        for (var mod : FabricLoader.getInstance().getAllMods()) for (var root : mod.getRootPaths()) {
            var assets = root.resolve("assets");
            if (!Files.isDirectory(assets)) continue;
            try (var namespaces = Files.list(assets)) {
                for (var namespace : namespaces.toList()) for (String locale : List.of("de_de", "en_us")) {
                    var file = namespace.resolve("lang").resolve(locale + ".json");
                    if (!Files.isRegularFile(file)) continue;
                    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        translations(JsonParser.parseReader(reader).getAsJsonObject(), translated);
                    } catch (IOException | RuntimeException ignored) {
                        // Eine kaputte Übersetzung einer Mod schaltet weder IDs noch andere Kataloge ab.
                    }
                }
            } catch (IOException ignored) { }
        }
        Map<String, Set<Item>> index = new LinkedHashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            var id = BuiltInRegistries.ITEM.getKey(item);
            add(index, id.toString(), item);
            if (id.getNamespace().equals("minecraft")) add(index, id.getPath(), item);
            var contents = new ItemStack(item).getHoverName().getContents();
            if (contents instanceof TranslatableContents t)
                for (String word : translated.getOrDefault(t.getKey(), List.of())) add(index, word, item);
        }
        dev.begleiter.chat.ChatParser.itemAliases().forEach((word, id) -> {
            Item item = ItemIds.resolve(id);
            if (item != null && item != Items.AIR && !index.containsKey(ChatText.normalize(word))) add(index, word, item);
        });
        add(index, "boot", Items.OAK_BOAT);
        add(index, "boat", Items.OAK_BOAT);
        add(index, "pisten", Items.PISTON);
        names = index;
        return names;
    }

    /** Exakte Namen zuerst; Pluralendungen nur, wenn der ganze Name keinen exakten Treffer hat. */
    public static List<Item> resolve(String query) {
        if (query == null || query.isBlank() || query.length() > 256) return List.of();
        // Eine ausdrückliche ID mit Namensraum fällt nie auf einen anderen normalisierten Namen zurück.
        if (query.contains(":")) {
            Item item = ItemIds.resolve(query.strip());
            return item == null || item == Items.AIR ? List.of() : List.of(item);
        }
        String word = ChatText.normalize(query);
        Set<Item> exact = index().get(word);
        if (exact != null) return List.copyOf(exact);
        Set<Item> options = new LinkedHashSet<>();
        for (String ending : List.of("s", "es", "n", "en", "e", "er")) {
            if (!word.endsWith(ending) || word.length() <= ending.length() + 2) continue;
            options.addAll(index().getOrDefault(word.substring(0, word.length() - ending.length()), Set.of()));
        }
        return List.copyOf(options);
    }
}
