package dev.begleiter;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchematicCatalogueFilesTest {
    @Test
    void catalogueMatchesUniqueBundledFilesAndBothTranslations() throws Exception {
        Path root = Path.of("src/main/resources");
        var entries = JsonParser.parseString(Files.readString(root.resolve("data/begleiter/schematic_catalogue.json"))).getAsJsonArray();
        var de = JsonParser.parseString(Files.readString(root.resolve("assets/begleiter/lang/de_de.json"))).getAsJsonObject();
        var en = JsonParser.parseString(Files.readString(root.resolve("assets/begleiter/lang/en_us.json"))).getAsJsonObject();
        Set<String> names = new HashSet<>(), ids = new HashSet<>(), hashes = new HashSet<>();
        Set<String> userChecked = new HashSet<>();
        for (var value : entries) {
            var entry = value.getAsJsonObject();
            String name = entry.get("file").getAsString(), id = entry.get("id").getAsString();
            assertEquals(Path.of(name).getFileName().toString(), name, "Catalogue filename contains a path");
            assertTrue(names.add(name), "Duplicate filename"); assertTrue(ids.add(id), "Duplicate ID");
            byte[] bytes = Files.readAllBytes(root.resolve("data/begleiter/bundled_schematics").resolve(name));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(entry.get("sha256").getAsString().toLowerCase(Locale.ROOT), hash, name);
            assertTrue(hashes.add(hash), "Byte-identical duplicate retained: " + name);
            for (String suffix : List.of("name", "description")) {
                String key = "server.begleiter.schematic.catalogue." + id + "." + suffix;
                assertTrue(de.has(key) && en.has(key), "Missing catalogue translation: " + key);
                assertFalse(de.get(key).getAsString().isBlank()); assertFalse(en.get(key).getAsString().isBlank());
            }
            if (entry.get("checked_by_user").getAsBoolean()) userChecked.add(id);
        }
        try (var files = Files.list(root.resolve("data/begleiter/bundled_schematics"))) {
            assertEquals(names, files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        // Die vom Nutzer geprüften Pläne sind Fremd-Entwürfe: privat alle vorhanden, im öffentlichen Export keiner.
        // Nur ein teilweiser Verlust wäre ein Fehler.
        Set<String> approved = Set.of("wool_colours", "wool_row", "bonemeal_compact", "iron_crafter", "item_finder",
                "storage_medieval", "lava", "regear");
        assertTrue(userChecked.equals(approved) || (userChecked.isEmpty() && Collections.disjoint(ids, approved)),
                "Previously user-approved schematics must remain available: " + userChecked);
    }

    @Test
    void retiredSchematicsHaveReasonsAndAreNotBundled() throws Exception {
        Path root = Path.of("src/main/resources/data/begleiter");
        for (var value : JsonParser.parseString(Files.readString(root.resolve("retired_schematics.json"))).getAsJsonArray()) {
            var entry = value.getAsJsonObject();
            assertFalse(entry.get("reason").getAsString().isBlank());
            assertFalse(Files.exists(root.resolve("bundled_schematics").resolve(entry.get("file").getAsString())));
        }
    }
}
