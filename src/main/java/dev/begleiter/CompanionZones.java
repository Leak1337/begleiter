package dev.begleiter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** Dimensionsgebundene Arbeitsbereiche je Begleiter. Abfragen laden niemals Chunks nach. */
public final class CompanionZones {
    public enum Kind {
        BASE(0xFF70BFFF), ANIMALS(0xFFFFC86A), FIELDS(0xFF8EDD74), STORAGE(0xFFC39BFF), ROAM(0xFF64DFD2);
        public final int color;
        Kind(int color) { this.color = color; }
        public String key() { return name().toLowerCase(java.util.Locale.ROOT); }
        public Component title() { return CompanionTexts.tr("zone.kind." + key()); }
    }

    public static final int MAX_SIDE = 64, MAX_HEIGHT = 32;
    public record Zone(Kind kind, String dimension, BlockPos min, BlockPos max) {
        public static final Codec<Zone> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.comapFlatMap(name -> {
                    try { return com.mojang.serialization.DataResult.success(Kind.valueOf(name)); }
                    catch (IllegalArgumentException ex) { return com.mojang.serialization.DataResult.error(() -> "Unknown zone kind: " + name); }
                }, Kind::name).fieldOf("kind").forGetter(Zone::kind),
                Codec.STRING.fieldOf("dimension").forGetter(Zone::dimension),
                BlockPos.CODEC.fieldOf("min").forGetter(Zone::min),
                BlockPos.CODEC.fieldOf("max").forGetter(Zone::max)).apply(i, Zone::new));
        public Zone { min = min.immutable(); max = max.immutable(); }
        public boolean valid() {
            return span(min.getX(), max.getX()) <= MAX_SIDE && span(min.getZ(), max.getZ()) <= MAX_SIDE
                    && span(min.getY(), max.getY()) <= MAX_HEIGHT;
        }
        private static long span(int a, int b) { return b < a ? Long.MAX_VALUE : (long) b - a + 1; }
        public boolean contains(BlockPos p) {
            return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY()
                    && p.getY() <= max.getY() && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
        }
        public BlockPos center() { return new BlockPos(min.getX() + (max.getX() - min.getX()) / 2,
                min.getY() + Math.min(3, max.getY() - min.getY()), min.getZ() + (max.getZ() - min.getZ()) / 2); }
        public AABB bounds() { return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1., max.getY() + 1., max.getZ() + 1.); }
    }
    private final EnumMap<Kind, Zone> zones = new EnumMap<>(Kind.class);
    public List<Zone> all() { return List.copyOf(zones.values()); }
    public Zone get(Kind kind, ServerLevel level) {
        Zone z = zones.get(kind);
        return z != null && z.dimension.equals(CompanionMemory.dim(level)) ? z : null;
    }
    public void put(Zone z) { if (z.valid()) zones.put(z.kind, z); }
    public void remove(Kind kind) { zones.remove(kind); }
    public void load(List<Zone> saved) { zones.clear(); saved.stream().limit(Kind.values().length).forEach(this::put); }

    /** Die Bodenmarkierung umfasst auch Pflanzen, Tiere und niedrige Geländeunterschiede. */
    public static Zone between(Kind kind, String dimension, BlockPos a, BlockPos b) {
        return new Zone(kind, dimension, new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()) - 3,
                Math.min(a.getZ(), b.getZ())), new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()) + 4,
                Math.max(a.getZ(), b.getZ())));
    }

    public static Kind kindFor(CompanionDuties.Duty duty) {
        if (duty == null) return null;
        return switch (duty) {
            case FARM -> Kind.FIELDS;
            case ANIMALS, FETCH -> Kind.ANIMALS;
            case BASE -> Kind.BASE;
            case STORAGE -> Kind.STORAGE;
            case EXPLORE -> Kind.ROAM;
            default -> null;
        };
    }

    /** Einschränkung gilt während Daueraufgaben; einzelne Spieleraufträge bleiben unabhängig. */
    public static Zone work(CompanionEntity c, Kind kind) {
        if (c.work.active()) {
            Zone bound = c.work.zone();
            return bound != null && bound.kind == kind ? bound : null;
        }
        if (c.duty() == null || !(c.level() instanceof ServerLevel sl)) return null;
        if (c.duty() != CompanionDuties.Duty.BASE && kind != kindFor(c.duty())) return null;
        Zone z = c.zones.get(kind, sl);
        return z != null ? z : c.duty() == CompanionDuties.Duty.BASE ? c.zones.get(Kind.BASE, sl) : null;
    }
    public static List<Zone> collectionZones(CompanionEntity c) {
        if (c.duty() == null || !(c.level() instanceof ServerLevel sl)) return List.of();
        if (c.duty() == CompanionDuties.Duty.BASE) return c.zones.all().stream()
                .filter(z -> z.kind != Kind.ROAM && z.dimension.equals(CompanionMemory.dim(sl))).toList();
        Zone z = work(c, kindFor(c.duty()));
        return z == null ? List.of() : List.of(z);
    }
    public static boolean collectionAllowed(CompanionEntity c, BlockPos p) {
        if (c.work.active()) {
            Zone zone = c.work.zone();
            return zone != null && (zone.kind == Kind.FIELDS || zone.kind == Kind.ANIMALS || zone.kind == Kind.STORAGE)
                    && zone.contains(p) && c.level() instanceof ServerLevel world && c.work.runnable(world);
        }
        if (c.work.enabled() && !c.hasTasks()) return false;
        var zones = collectionZones(c);
        return zones.isEmpty() || zones.stream().anyMatch(z -> z.contains(p));
    }
    public static boolean allows(CompanionEntity c, Kind kind, BlockPos p) {
        Zone z = work(c, kind);
        return z == null || z.contains(p);
    }
}
