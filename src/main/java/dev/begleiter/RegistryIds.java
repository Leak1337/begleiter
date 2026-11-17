package dev.begleiter;

import java.util.Set;
import net.minecraft.resources.Identifier;

/** Vollständige Registry-Identität; alte Kurzformen bezeichnen ausschließlich Minecraft-Inhalte. */
public final class RegistryIds {
    private RegistryIds() { }

    public static Identifier parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 256 || raw.startsWith(":") || raw.endsWith(":")) return null;
        Identifier id = Identifier.tryParse(raw.indexOf(':') < 0 ? "minecraft:" + raw : raw);
        return id == null || id.toString().length() > 256 ? null : id;
    }

    public static String canonical(String raw) {
        Identifier id = parse(raw);
        return id == null ? null : id.toString();
    }

    public static String canonicalOrOriginal(String raw) {
        String id = canonical(raw);
        return id == null ? raw : id;
    }

    public static Set<String> minecraftSet(String... paths) {
        return java.util.Arrays.stream(paths).map(path -> "minecraft:" + path)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
