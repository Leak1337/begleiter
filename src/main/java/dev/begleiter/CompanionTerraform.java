package dev.begleiter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Terraforming: rechnet aus einer Zielform (Höhe je Spalte) einen Bauplan "Position -> Zustand" (Luft = abtragen,
 * Erde/Stein = auffüllen). Ausgeführt wird er vom normalen Bauplaner ({@link CompanionBuilder}): von oben abtragen,
 * von unten auffüllen, Material selbst besorgen. Abgetragen wird nur natürliches Gelände – Gebautes bleibt stehen.
 */
public final class CompanionTerraform {
    private CompanionTerraform() {}

    public enum Mode { FLATTEN, FILL, CUT, SMOOTH, PIT, HILL }

    public static final int MAX_SIZE = 33, DEFAULT_SIZE = 11;
    /** So hoch über/unter der Zielhöhe schaut er nach (höher: Bäume/Berge bleiben, tiefer: Schluchten nicht füllen). */
    static final int CUT_UP = 20, FILL_DOWN = 12;
    /** Mehr Blöcke plant er nicht auf einmal. */
    public static final int MAX_CHANGES = 30_000;

    public record Plan(Mode mode, Map<BlockPos, BlockState> blocks, int cut, int fill, int skipped, int targetY,
                       int sizeX, int sizeZ) {}

    // ------------------------------------------------------------------ Gelände erkennen

    /** Natürliches Gelände, das er abtragen darf (Erde, Stein, Sand, Kies, Bäume, Pflanzen, Schnee …). */
    public static boolean natural(BlockState s) {
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty()) return false; // Wasser/Lava nicht abtragen
        // Entrindete Stämme, Holz-/Hyphenblöcke und glasierte Keramik erzeugt die Welt (fast) nie: das baut ein Spieler
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (path.startsWith("stripped_") || path.endsWith("_wood") || path.endsWith("_hyphae")
                || path.endsWith("_glazed_terracotta")) return false;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND)
                || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.SNOW)
                || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.ICE)) return true;
        if (s.canBeReplaced()) return true; // Gras, Farn, Schnee-Schicht …
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock());
        // Name-based vanilla exceptions must neither lose their namespace nor classify a foreign namesake.
        if (!"minecraft".equals(key.getNamespace())) return false;
        String id = key.getPath();
        return id.endsWith("_ore") || id.equals("gravel") || id.equals("clay") || id.equals("sandstone")
                || id.equals("red_sandstone") || id.equals("terracotta") || id.endsWith("_terracotta") || id.equals("cobblestone")
                || id.equals("mossy_cobblestone") || id.equals("calcite") || id.equals("dripstone_block")
                || id.equals("pointed_dripstone") || id.equals("mushroom_stem") || id.endsWith("_mushroom_block")
                || id.equals("vine") || id.equals("sweet_berry_bush") || id.equals("cactus") || id.equals("sugar_cane")
                || id.equals("bamboo") || id.equals("pumpkin") || id.equals("melon") || id.equals("bee_nest")
                || id.equals("moss_block") || id.equals("moss_carpet") || id.equals("obsidian") || id.equals("magma_block");
    }

    /**
     * Wie {@link #natural(BlockState)}, aber Stämme nur, wenn sie zu einem gewachsenen Baum gehören: An ihnen oder an
     * den mit ihnen verbundenen Stämmen hängt natürliches Laub (nicht „persistent“) bzw. bei Riesenpilzen Warzenblöcke.
     * Von Hand gesetzte Stämme (Blockhütte, Zaunpfosten) haben das nicht und bleiben stehen.
     */
    public static boolean natural(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState s) {
        if (!natural(s)) return false;
        return !s.is(BlockTags.LOGS) || grownTree(level, pos);
    }

    /** Höchstens so viele verbundene Stämme durchsucht er (Riesenbaum); mehr ohne Laub = kein Baum. */
    static final int TREE_SEARCH = 160;

    static boolean grownTree(net.minecraft.world.level.LevelReader level, BlockPos start) {
        java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        open.add(start.immutable());
        seen.add(start.immutable());
        while (!open.isEmpty()) {
            BlockPos p = open.poll();
            // auch schräg: Akazien- und Eichenäste hängen über Eck am Stamm
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos q = p.offset(dx, dy, dz);
                        if (!level.hasChunkAt(q)) continue;
                        BlockState st = level.getBlockState(q);
                        if (st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                                && !st.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) return true;
                        if (st.is(BlockTags.WART_BLOCKS)) return true;
                        if (st.is(BlockTags.LOGS) && seen.size() < TREE_SEARCH && seen.add(q)) open.add(q);
                    }
        }
        return false;
    }

    /** Fester Boden (zählt als Oberfläche): kein Baum, keine Pflanze, keine Flüssigkeit. */
    static boolean ground(ServerLevel sl, BlockPos p, BlockState s) {
        if (s.isAir() || s.canBeReplaced() || !s.getFluidState().isEmpty()) return false;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) return false;
        // Barrieren o.ä. (unzerstörbar, kein Grundgestein) sind keine Oberfläche
        if (s.getDestroySpeed(sl, p) < 0 && !s.is(Blocks.BEDROCK)) return false;
        return s.isFaceSturdy(sl, p, Direction.UP);
    }

    /** Höchster fester Boden in der Spalte zwischen from (oben) und to (unten); Integer.MIN_VALUE = keiner. */
    static int surface(ServerLevel sl, int x, int z, int from, int to) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = from; y >= to; y--) {
            p.set(x, y, z);
            if (ground(sl, p, sl.getBlockState(p))) return y;
        }
        return Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ Planen

    /**
     * Plan für ein Rechteck (x0..x0+sx-1, z0..z0+sz-1) mit Zielhöhe je Spalte (height[i][k] = oberster Block).
     * cut/fill: ob abgetragen bzw. aufgefüllt werden darf.
     */
    static Plan build(ServerLevel sl, Mode mode, int x0, int z0, int sx, int sz, int[][] height, boolean cut, boolean fill,
                      Block filler, int refY) {
        Map<BlockPos, BlockState> m = new HashMap<>();
        int cuts = 0, fills = 0, skipped = 0;
        BlockState fillState = filler.defaultBlockState();
        BlockState top = filler == Blocks.DIRT ? Blocks.DIRT.defaultBlockState() : fillState;
        columns:
        for (int i = 0; i < sx; i++)
            for (int k = 0; k < sz; k++) {
                int x = x0 + i, z = z0 + k, h = height[i][k];
                if (cut) {
                    for (int y = h + 1; y <= h + CUT_UP; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState s = sl.getBlockState(p);
                        if (s.isAir()) continue;
                        if (s.getDestroySpeed(sl, p) < 0 || !s.getFluidState().isEmpty()) continue; // Grundgestein, Barrieren, Wasser
                        if (!natural(sl, p, s)) { skipped++; continue; }
                        if (s.canBeReplaced()) continue; // Gras/Blumen: fallen mit dem Block darunter von selbst weg
                        m.put(p, Blocks.AIR.defaultBlockState());
                        cuts++;
                    }
                }
                if (fill) {
                    int g = surface(sl, x, z, h, h - FILL_DOWN);
                    int from = g == Integer.MIN_VALUE ? h - FILL_DOWN : g + 1;
                    for (int y = from; y <= h; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState s = sl.getBlockState(p);
                        if (ground(sl, p, s)) continue;
                        m.put(p, y == h ? top : fillState);
                        fills++;
                    }
                }
                if (cuts + fills > MAX_CHANGES) break columns; // ganze Planung beenden, nicht nur diese Reihe
            }
        return new Plan(mode, m, cuts, fills, skipped, refY, sx, sz);
    }

    static int[][] constant(int sx, int sz, int y) {
        int[][] h = new int[sx][sz];
        for (int[] row : h) Arrays.fill(row, y);
        return h;
    }

    /** Planieren/Auffüllen/Abtragen auf Höhe targetY (oberster Block), Fläche um center. */
    public static Plan level(ServerLevel sl, Mode mode, BlockPos center, int sx, int sz, int targetY, Block filler) {
        int x0 = center.getX() - sx / 2, z0 = center.getZ() - sz / 2;
        boolean cut = mode != Mode.FILL, fill = mode != Mode.CUT;
        return build(sl, mode, x0, z0, sx, sz, constant(sx, sz, targetY), cut, fill, filler, targetY);
    }

    /** Glätten: jede Spalte auf den Median der 5x5-Nachbarschaft, höchstens 3 Blöcke Änderung. */
    public static Plan smooth(ServerLevel sl, BlockPos center, int sx, int sz, Block filler) {
        int x0 = center.getX() - sx / 2, z0 = center.getZ() - sz / 2, r = 2;
        int cy = center.getY();
        int[][] surf = new int[sx + 2 * r][sz + 2 * r];
        for (int i = 0; i < sx + 2 * r; i++)
            for (int k = 0; k < sz + 2 * r; k++) {
                int s = surface(sl, x0 - r + i, z0 - r + k, cy + CUT_UP, cy - FILL_DOWN);
                surf[i][k] = s == Integer.MIN_VALUE ? cy - 1 : s;
            }
        int[][] target = new int[sx][sz];
        int[] window = new int[(2 * r + 1) * (2 * r + 1)];
        for (int i = 0; i < sx; i++)
            for (int k = 0; k < sz; k++) {
                int n = 0;
                for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) window[n++] = surf[i + r + a][k + r + b];
                Arrays.sort(window);
                int own = surf[i + r][k + r], med = window[window.length / 2];
                target[i][k] = Math.max(own - 3, Math.min(own + 3, med));
            }
        return build(sl, Mode.SMOOTH, x0, z0, sx, sz, target, true, true, filler, cy - 1);
    }

    /** Grube: Rechteck depth Blöcke tief unter die Oberfläche (je Spalte) ausheben – ohne Gebautes. */
    public static Plan pit(ServerLevel sl, BlockPos center, int sx, int sz, int depth) {
        int x0 = center.getX() - sx / 2, z0 = center.getZ() - sz / 2;
        int top = center.getY() - 1;
        Map<BlockPos, BlockState> m = new HashMap<>();
        int cuts = 0, skipped = 0;
        for (int i = 0; i < sx; i++)
            for (int k = 0; k < sz; k++) {
                int x = x0 + i, z = z0 + k;
                int s = surface(sl, x, z, top + 4, top - 8);
                int from = s == Integer.MIN_VALUE ? top : s;
                for (int y = from; y > from - depth; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState st = sl.getBlockState(p);
                    if (st.isAir()) continue;
                    if (st.getDestroySpeed(sl, p) < 0 || !st.getFluidState().isEmpty()) continue;
                    if (!natural(sl, p, st)) { skipped++; continue; }
                    m.put(p, Blocks.AIR.defaultBlockState());
                    cuts++;
                }
            }
        return new Plan(Mode.PIT, m, cuts, 0, skipped, top - depth, sx, sz);
    }

    /** Runder Hügel: in der Mitte height Blöcke über dem Boden, zum Rand hin flacher (nur auffüllen). */
    public static Plan hill(ServerLevel sl, BlockPos center, int size, int height, Block filler) {
        int x0 = center.getX() - size / 2, z0 = center.getZ() - size / 2;
        double radius = size / 2.0;
        int[][] target = new int[size][size];
        for (int i = 0; i < size; i++)
            for (int k = 0; k < size; k++) {
                int x = x0 + i, z = z0 + k;
                int base = surface(sl, x, z, center.getY() + 4, center.getY() - FILL_DOWN);
                if (base == Integer.MIN_VALUE) base = center.getY() - 1;
                double d = Math.sqrt(Math.pow(x + 0.5 - (center.getX() + 0.5), 2) + Math.pow(z + 0.5 - (center.getZ() + 0.5), 2)) / radius;
                double f = d >= 1 ? 0 : (Math.cos(d * Math.PI) + 1) / 2; // sanfter Verlauf
                target[i][k] = base + (int) Math.round(height * f);
            }
        return build(sl, Mode.HILL, x0, z0, size, size, target, false, true, filler, center.getY() - 1 + height);
    }

    // ------------------------------------------------------------------ Sprache

    /** Größe aus dem Satz: "20x20", "20 mal 15", "15" (quadratisch); Grube: dritte Zahl = Tiefe. */
    public static int[] size(List<String> tok, int def) {
        java.util.List<Integer> nums = new java.util.ArrayList<>();
        for (String t : tok) {
            if (t.matches("\\d{1,3}x\\d{1,3}(x\\d{1,3})?")) for (String n : t.split("x")) nums.add(Integer.parseInt(n));
            else if (t.matches("\\d{1,3}")) nums.add(Integer.parseInt(t));
        }
        int sx = nums.isEmpty() ? def : nums.get(0);
        int sz = nums.size() > 1 ? nums.get(1) : sx;
        int depth = nums.size() > 2 ? nums.get(2) : 0;
        return new int[]{Math.max(1, Math.min(MAX_SIZE, sx)), Math.max(1, Math.min(MAX_SIZE, sz)), Math.max(0, Math.min(24, depth))};
    }

    /** Welcher Terraforming-Befehl steckt im Satz? null = keiner. */
    public static Mode modeFrom(List<String> tok, String text) {
        boolean any = false;
        for (String t : tok) if (t.startsWith("planier") || t.startsWith("ebne") || t.startsWith("glaett") || t.startsWith("terraform")) any = true;
        if (tok.stream().anyMatch(t -> t.startsWith("glaett"))) return Mode.SMOOTH;
        if (text.contains("huegel") && tok.stream().anyMatch(t -> t.startsWith("schuett") || t.equals("bau") || t.equals("baue")
                || t.startsWith("aufschuett") || t.startsWith("mach"))) return Mode.HILL;
        if (tok.stream().anyMatch(t -> t.startsWith("grab") || t.startsWith("aushe") || t.equals("heb") || t.equals("hebe"))
                && tok.stream().anyMatch(t -> t.startsWith("loch") || t.startsWith("grube") || t.startsWith("keller") || t.startsWith("becken")))
            return Mode.PIT;
        boolean terrain = tok.stream().anyMatch(t -> t.startsWith("loch") || t.startsWith("loech") || t.equals("see")
                || t.startsWith("teich") || t.startsWith("grube") || t.startsWith("graben") || t.startsWith("boden")
                || t.startsWith("wasser") || t.startsWith("krater") || t.startsWith("huegel") || t.startsWith("berg")
                || t.startsWith("gelaende") || t.startsWith("erde") || t.startsWith("erhebung") || t.startsWith("senke"));
        if (terrain && (tok.stream().anyMatch(t -> t.startsWith("fuell") || t.startsWith("schuett")) && tok.contains("auf")
                || tok.stream().anyMatch(t -> t.startsWith("auffuell") || t.startsWith("zuschuett")))) return Mode.FILL;
        if (terrain && ((tok.stream().anyMatch(t -> t.startsWith("trag")) && tok.contains("ab")) || tok.stream().anyMatch(t -> t.startsWith("abtrag"))))
            return Mode.CUT;
        if (any || ((text.contains("boden") || text.contains("gelaende") || text.contains("flaeche"))
                && tok.stream().anyMatch(t -> t.equals("eben") || t.equals("flach") || t.equals("gerade")))) return Mode.FLATTEN;
        return null;
    }

    /** Plan für einen Befehl am Spieler: Fläche um ihn (Grube/Hügel vor ihm). */
    public static Plan planFor(ServerLevel sl, Mode mode, BlockPos feet, Direction facing, List<String> tok) {
        Block filler = tok.stream().anyMatch(t -> t.startsWith("stein") || t.startsWith("bruchstein")) ? Blocks.COBBLESTONE : Blocks.DIRT;
        return planFor(sl, mode, feet, facing, tok, filler);
    }

    static Plan planFor(ServerLevel sl, Mode mode, BlockPos feet, Direction facing, List<String> tok, Block filler) {
        int ground = feet.getY() - 1;
        return switch (mode) {
            case FLATTEN, FILL, CUT -> {
                int[] s = size(tok, DEFAULT_SIZE);
                yield level(sl, mode, feet, s[0], s[1], ground, filler);
            }
            case SMOOTH -> {
                int[] s = size(tok, 15);
                yield smooth(sl, feet, s[0], s[1], filler);
            }
            case PIT -> {
                int[] s = size(tok, 5);
                int depth = s[2] > 0 ? s[2] : 3;
                BlockPos c = feet.relative(facing, s[1] / 2 + 2);
                yield pit(sl, c, s[0], s[1], depth);
            }
            case HILL -> {
                int[] s = size(tok, 11);
                int size = Math.max(5, s[0]);
                BlockPos c = feet.relative(facing, size / 2 + 2);
                yield hill(sl, c, size, Math.max(2, size / 3), filler);
            }
        };
    }

}
