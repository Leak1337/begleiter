package dev.begleiter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

/** Namen und Bedienhinweise der mitgelieferten Dateien; eigene Schematics der Spieler behalten ihre Namen. */
public final class CompanionSchematicCatalogue {
    private CompanionSchematicCatalogue() {}
    public record Entry(String file, String id, String purpose, String operation, String requirement,
                        Map<String, String> legacyFiles) {
        public Component title() { return CompanionTexts.tr("schematic.catalogue." + id + ".name"); }
        public Component description() { return CompanionTexts.tr("schematic.catalogue." + id + ".description"); }
    }
    private static final Map<String, Entry> ENTRIES = load();

    private static Map<String, Entry> load() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        var mod = FabricLoader.getInstance().getModContainer(CompanionMod.MOD_ID);
        if (mod.isEmpty()) return Map.of();
        var file = mod.get().findPath("data/begleiter/schematic_catalogue.json");
        if (file.isEmpty()) return Map.of();
        try (var reader = Files.newBufferedReader(file.get())) {
            for (var value : JsonParser.parseReader(reader).getAsJsonArray()) {
                var o = value.getAsJsonObject();
                Map<String, String> legacy = new LinkedHashMap<>();
                o.getAsJsonObject("legacy_files").entrySet().forEach(e -> legacy.put(e.getKey(), e.getValue().getAsString()));
                var entry = new Entry(o.get("file").getAsString(), o.get("id").getAsString(),
                        o.get("purpose").getAsString(), o.get("operation").getAsString(), o.get("requirement").getAsString(), Map.copyOf(legacy));
                if (entries.put(CompanionSchematics.baseName(Path.of(entry.file())), entry) != null)
                    throw new IllegalArgumentException("Duplicate schematic catalogue name");
            }
            return java.util.Collections.unmodifiableMap(entries);
        } catch (Exception error) {
            com.mojang.logging.LogUtils.getLogger().error("Cannot load bundled schematic catalogue", error);
            return Map.of();
        }
    }

    public static Entry find(String baseName) { return ENTRIES.get(baseName); }
    public static Entry byId(String id) { return ENTRIES.values().stream().filter(e -> e.id().equals(id)).findFirst().orElse(null); }
    public static java.util.Collection<Entry> entries() { return ENTRIES.values(); }

    /** Nur byte-gleiche alte Kopien mitgelieferter Dateien aus dem flachen Katalog räumen; bearbeitete Spielerdateien bleiben. */
    public static void archiveLegacy(Path target) throws java.io.IOException {
        var archive = target.resolve(".begleiter-archiv");
        Map<String, String> known = new LinkedHashMap<>();
        for (var entry : entries()) known.putAll(entry.legacyFiles());
        var retired = FabricLoader.getInstance().getModContainer(CompanionMod.MOD_ID).orElseThrow()
                .findPath("data/begleiter/retired_schematics.json");
        if (retired.isPresent()) try (var reader = Files.newBufferedReader(retired.get())) {
            for (var value : JsonParser.parseReader(reader).getAsJsonArray()) {
                var o = value.getAsJsonObject(); known.put(o.get("file").getAsString(), o.get("sha256").getAsString());
            }
        }
        for (var old : known.entrySet()) {
            var file = target.resolve(old.getKey());
            if (!file.getParent().equals(target) || !Files.isRegularFile(file)) continue;
            String hash;
            try { hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            if (!hash.equalsIgnoreCase(old.getValue())) continue;
            Files.createDirectories(archive);
            var destination = archive.resolve(old.getKey());
            if (Files.exists(destination)) continue;
            Files.move(file, destination);
        }
    }
}
