package dev.begleiter.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;

/**
 * Eigenschaften je BlockState als Bitfeld, einmal berechnet und dann per ID nachgeschlagen (kein Tag-Vergleich im
 * Suchlauf). Alles hier hängt nur vom Zustand ab; was die Umgebung braucht (Flüssigkeit daneben, Rieselblock darüber)
 * prüft {@link NavContext}.
 */
public final class BlockFlags {
    private BlockFlags() {}

    /** Darauf stehen (feste Oberseite, Treppe, Stufe, Blätter …). */
    public static final int STAND = 1;
    /** Füße/Kopf können in diesem Block sein. */
    public static final int PASS = 1 << 1;
    public static final int AIR = 1 << 2;
    /** Nie hineinlaufen oder darauf stehen (Lava, Feuer, Magma, Kaktus, Pulverschnee …). */
    public static final int AVOID_INTO = 1 << 3;
    /** Nie abbauen (unzerstörbar, Eis, befallen, Spawner …). */
    public static final int AVOID_BREAK = 1 << 4;
    /** Natürlich gewachsen: darf für Wege abgebaut werden. */
    public static final int NATURAL = 1 << 5;
    public static final int WATER = 1 << 6;
    public static final int LAVA = 1 << 7;
    public static final int CLIMB = 1 << 8;
    /** Holztür: lässt sich öffnen. */
    public static final int DOOR = 1 << 9;
    public static final int GATE = 1 << 10;
    /** Rieselt nach, wenn darunter frei wird (Sand, Kies, Konkretpulver, Amboss, Tropfstein). */
    public static final int FALLING = 1 << 11;
    /** Geht beim Drauflanden kaputt oder kippt (Ackerboden, Schildkröteneier, Großes Tropfblatt). */
    public static final int FRAGILE = 1 << 12;
    /** Untere Stufe: man steht einen halben Block tiefer. */
    public static final int SLAB_BOTTOM = 1 << 13;
    /** Bremst (Seelensand). */
    public static final int SLOW = 1 << 14;
    /** Festungsmauer: nur in der Festung abbauen. */
    public static final int STRONGHOLD = 1 << 15;
    /** Chunk nicht geladen (nur von {@link BlockCache}). */
    public static final int UNLOADED = 1 << 16;
    /** Netherportal: begehbar nur mit {@link NavContext#allowPortal}. */
    public static final int PORTAL = 1 << 17;
    /** Wasser ohne Kollision (Wasser, Seegras, Kelp) – hier kann man schwimmen; geflutete Stufen/Zäune nicht. */
    public static final int SWIM = 1 << 18;

    public static final int LIQUID = WATER | LAVA;
    private static final int COMPUTED = 1 << 30;

    private static int[] table = new int[0];

    public static int of(BlockState s) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(s);
        if (id < 0) return compute(s);
        if (id >= table.length) table = java.util.Arrays.copyOf(table, Math.max(id + 1, Block.BLOCK_STATE_REGISTRY.size()));
        int f = table[id];
        if (f == 0) table[id] = f = compute(s) | COMPUTED;
        return f & ~COMPUTED;
    }

    public static boolean has(int flags, int bits) { return (flags & bits) != 0; }

    static int compute(BlockState s) {
        int f = 0;
        FluidState fs = s.getFluidState();
        if (fs.is(FluidTags.WATER)) f |= WATER;
        if (fs.is(FluidTags.LAVA)) f |= LAVA;
        if (s.isAir()) f |= AIR;

        boolean avoid = has(f, LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(BlockTags.CAMPFIRES)
                || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.COBWEB)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.BUBBLE_COLUMN)
                || s.is(Blocks.END_PORTAL) || s.is(Blocks.END_GATEWAY)
                || s.is(Blocks.POINTED_DRIPSTONE) || s.is(Blocks.HONEY_BLOCK);
        if (avoid) f |= AVOID_INTO;

        // Netherportal: nur betreten, wenn es das Ziel ist (sonst landet er aus Versehen im Nether)
        if (s.is(Blocks.NETHER_PORTAL)) return f | PORTAL;
        boolean noCollision = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        if (has(f, WATER) && noCollision) f |= SWIM;
        boolean thin = s.getBlock() instanceof CarpetBlock
                || (s.getBlock() instanceof SnowLayerBlock && s.getValue(SnowLayerBlock.LAYERS) <= 2);
        // Leitern/Ranken: dünne Kollision an der Wand, man steht im Block
        boolean climbable = s.is(BlockTags.CLIMBABLE) && !s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (!avoid && fs.isEmpty() && (noCollision || thin || climbable)) f |= PASS;

        if (!avoid && fs.isEmpty()) {
            boolean stand = s.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP)
                    || s.getBlock() instanceof StairBlock || s.getBlock() instanceof SlabBlock || s.is(BlockTags.LEAVES)
                    || s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST)
                    || s.is(Blocks.ENDER_CHEST);
            if (stand && !has(f, PASS)) f |= STAND;
            if (s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) f |= SLAB_BOTTOM;
        }

        if (s.is(BlockTags.CLIMBABLE)) f |= CLIMB;
        if (s.getBlock() instanceof DoorBlock && DoorBlock.isWoodenDoor(s)) f |= DOOR;
        if (s.getBlock() instanceof FenceGateBlock) f |= GATE;
        if (s.getBlock() instanceof FallingBlock || s.is(Blocks.POINTED_DRIPSTONE)) f |= FALLING;
        if (s.is(Blocks.FARMLAND) || s.is(Blocks.TURTLE_EGG) || s.is(Blocks.SNIFFER_EGG) || s.is(Blocks.BIG_DRIPLEAF)) f |= FRAGILE;
        if (s.is(Blocks.SOUL_SAND)) f |= SLOW;
        if (natural(s)) f |= NATURAL;
        if (strongholdBlock(s)) f |= STRONGHOLD;

        if (s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0 || s.is(Blocks.ICE) || s.is(Blocks.FROSTED_ICE)
                || s.getBlock() instanceof InfestedBlock || s.is(Blocks.SPAWNER) || s.is(Blocks.TRIAL_SPAWNER)
                || s.is(Blocks.VAULT) || s.is(Blocks.BUDDING_AMETHYST) || s.is(Blocks.BEEHIVE) || s.is(Blocks.BEE_NEST))
            f |= AVOID_BREAK;
        return f;
    }

    /**
     * Natürlich gewachsene Blöcke. Wie beim Terraforming nicht: entrindete Stämme, Holz-/Hyphenblöcke und glasierte
     * Keramik (erzeugt die Welt fast nie) sowie von Hand gesetztes Laub (persistent). Ob ein Stamm zu einem Baum
     * gehört, hängt von der Umgebung ab: das prüft {@link NavContext#mayBreakAt}.
     */
    private static boolean natural(BlockState s) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (path.startsWith("stripped_") || path.endsWith("_wood") || path.endsWith("_hyphae")
                || path.endsWith("_glazed_terracotta")) return false;
        if (s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                && s.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) return false;
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.DIRT)
                || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM) || s.is(BlockTags.SAND) || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.COBBLESTONE) || s.is(Blocks.COBBLED_DEEPSLATE) || s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.SNOW) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(Blocks.CALCITE) || s.is(Blocks.SOUL_SAND) || s.is(Blocks.SOUL_SOIL) || s.is(Blocks.END_STONE)
                || s.is(BlockTags.NYLIUM) || s.is(BlockTags.ORES) || s.is(Blocks.DRIPSTONE_BLOCK);
    }

    private static boolean strongholdBlock(BlockState s) {
        return s.is(Blocks.STONE_BRICKS) || s.is(Blocks.MOSSY_STONE_BRICKS) || s.is(Blocks.CRACKED_STONE_BRICKS)
                || s.is(Blocks.CHISELED_STONE_BRICKS) || s.is(Blocks.OAK_PLANKS) || s.is(Blocks.IRON_BARS)
                || s.is(Blocks.STONE_BRICK_STAIRS) || s.is(Blocks.STONE_BRICK_SLAB) || s.is(Blocks.SMOOTH_STONE_SLAB);
    }
}
