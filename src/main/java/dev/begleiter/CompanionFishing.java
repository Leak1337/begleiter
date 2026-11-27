package dev.begleiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Angeln, <b>simuliert</b>: Es fliegt kein {@code FishingHook}. Der Begleiter steht an einem sicheren Ufer, „wirft“
 * auf eine Wasseroberfläche, wartet die Vanilla-Wartezeit ab und zieht die Beute aus den echten Loot-Tabellen
 * {@code gameplay/fishing/fish|junk|treasure}. Die Gruppe wählt {@link FishingRules} mit den Vanilla-Gewichten; Schätze
 * gibt es wie in Vanilla nur in offenem Wasser (gleiche Prüfung wie {@code FishingHook.calculateOpenWater}). Jeder Fang
 * kostet die Angel einen Haltbarkeitspunkt. Bewegt er sich, wird angegriffen oder verschwindet das Wasser, ist der Wurf
 * verloren (keine Beute, kein Verschleiß). Glück des Meeres und Köder wirken über die Verzauberungen der Angel.
 */
public final class CompanionFishing {
    /** Ufer bis zur Wurfstelle: waagerecht 1–4 Blöcke. */
    static final int MIN_CAST = 1, MAX_CAST = 4;
    /** So weit darf er sich vom Ufer bewegen, bevor der Wurf als abgebrochen gilt (Blöcke²). */
    static final double LEAVE_SQR = 1.5 * 1.5;

    private CompanionFishing() { }

    public record Spot(BlockPos stand, BlockPos water, boolean openWater) { }

    /** Zufall des Begleiters; GameTests geben der {@link Session} eine feste Quelle. */
    public static RandomSource random(CompanionEntity e) { return e.getRandom(); }

    /** Angel im Inventar (Slot) oder -1. */
    public static int rodSlot(CompanionEntity e) { return e.findItem(Items.FISHING_ROD); }

    /** Angel vorhanden oder sofort aus dem Inventar herstellbar (3 Stöcke bzw. Bretter, 2 Fäden)? */
    public static boolean hasOrCanMakeRod(CompanionEntity e) {
        return rodSlot(e) >= 0 || CompanionCraft.count(e, "string") >= 2
                && (CompanionCraft.count(e, "stick") >= 3 || CompanionCraft.count(e, "#planks") >= 2 || CompanionCraft.count(e, "#logs") >= 1);
    }

    /** Darf hier geangelt werden: Wasserquelle mit Luft (oder Seerose) darüber, also eine echte Oberfläche. */
    public static boolean validWater(ServerLevel sl, BlockPos w) {
        if (w == null || !sl.hasChunkAt(w)) return false;
        if (openWaterType(sl, w) != OpenWater.INSIDE) return false;
        return openWaterType(sl, w.above()) == OpenWater.ABOVE;
    }

    enum OpenWater { ABOVE, INSIDE, INVALID }

    /** Wie FishingHook.getOpenWaterTypeForBlock: Luft/Seerose darüber, volle Wasserquelle darin, sonst ungültig. */
    static OpenWater openWaterType(ServerLevel sl, BlockPos p) {
        BlockState st = sl.getBlockState(p);
        if (st.isAir() || st.is(Blocks.LILY_PAD)) return OpenWater.ABOVE;
        FluidState fluid = st.getFluidState();
        return fluid.is(FluidTags.WATER) && fluid.isSource() && st.getCollisionShape(sl, p).isEmpty()
                ? OpenWater.INSIDE : OpenWater.INVALID;
    }

    /**
     * Wie FishingHook.calculateOpenWater: Schichten −1 bis +2 um die Wurfstelle, je 5×5 einheitlich; Wasser darf nur
     * unter Luft liegen, nie darüber.
     */
    public static boolean openWater(ServerLevel sl, BlockPos w) {
        OpenWater prev = OpenWater.INVALID;
        for (int dy = -1; dy <= 2; dy++) {
            OpenWater layer = null;
            for (int dx = -2; dx <= 2 && layer != OpenWater.INVALID; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos p = w.offset(dx, dy, dz);
                    OpenWater t = sl.hasChunkAt(p) ? openWaterType(sl, p) : OpenWater.INVALID;
                    if (layer == null) layer = t;
                    else if (layer != t) { layer = OpenWater.INVALID; break; }
                }
            if (layer == null || layer == OpenWater.INVALID) return false;
            if (layer == OpenWater.ABOVE && prev == OpenWater.INVALID) return false; // Luft ganz unten
            if (layer == OpenWater.INSIDE && prev == OpenWater.ABOVE) return false; // Wasser über Luft
            prev = layer;
        }
        return true;
    }

    /** Sicherer Uferplatz: fester Boden, zwei Blöcke Luft, kein Wasser/Lava, keine bekannte Gefahr in der Nähe. */
    static boolean safeStand(CompanionEntity e, ServerLevel sl, BlockPos s) {
        if (!sl.hasChunkAt(s)) return false;
        BlockState feet = sl.getBlockState(s), head = sl.getBlockState(s.above()), ground = sl.getBlockState(s.below());
        if (!feet.getCollisionShape(sl, s).isEmpty() || !head.getCollisionShape(sl, s.above()).isEmpty()) return false;
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) return false;
        if (!ground.isFaceSturdy(sl, s.below(), Direction.UP) || !ground.getFluidState().isEmpty()) return false;
        if (ground.is(Blocks.MAGMA_BLOCK) || ground.is(Blocks.CAMPFIRE) || ground.is(Blocks.SOUL_CAMPFIRE)) return false;
        for (BlockPos p : BlockPos.betweenClosed(s.offset(-2, -1, -2), s.offset(2, 1, 2)))
            if (sl.getFluidState(p).is(FluidTags.LAVA) || sl.getBlockState(p).is(Blocks.FIRE)) return false;
        return !e.memory.dangerous(sl, s, 4);
    }

    /** Uferplatz zur Wurfstelle w: 1–4 Blöcke waagerecht, gleiche Höhe bis zwei darüber; nächster zuerst. */
    public static BlockPos standFor(CompanionEntity e, ServerLevel sl, BlockPos w, boolean checkReach) {
        List<BlockPos> stands = new ArrayList<>();
        for (int dx = -MAX_CAST; dx <= MAX_CAST; dx++)
            for (int dz = -MAX_CAST; dz <= MAX_CAST; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 < MIN_CAST * MIN_CAST || d2 > MAX_CAST * MAX_CAST) continue;
                for (int dy = 1; dy <= 3; dy++) {
                    BlockPos s = w.offset(dx, dy, dz);
                    if (safeStand(e, sl, s)) { stands.add(s); break; }
                }
            }
        BlockPos at = e.blockPosition();
        stands.sort(Comparator.comparingDouble(s -> s.distSqr(at)));
        for (BlockPos s : stands) if (!checkReach || s.distSqr(at) <= 2 || e.gathering.reachable(s)) return s;
        return null;
    }

    /** Angelplatz im Umkreis r: Wasseroberfläche mit Ufer; offenes Wasser bevorzugt, sonst die nächste. */
    public static Spot findSpot(CompanionEntity e, ServerLevel sl, int r) {
        BlockPos c = e.blockPosition();
        List<BlockPos> water = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -6, -r), c.offset(r, 3, r)))
            if (sl.hasChunkAt(p) && validWater(sl, p)) water.add(p.immutable());
        water.sort(Comparator.comparingDouble(p -> p.distSqr(c)));
        Spot fallback = null;
        int checked = 0;
        for (BlockPos w : water) {
            if (++checked > 48) break;
            BlockPos stand = standFor(e, sl, w, true);
            if (stand == null) continue;
            boolean open = openWater(sl, w);
            if (open) return new Spot(stand, w, true);
            if (fallback == null) fallback = new Spot(stand, w, false);
        }
        return fallback;
    }

    // ------------------------------------------------------------------ ein Wurf

    public enum Outcome { WAITING, CAUGHT, INTERRUPTED, INVALID_WATER, NO_ROD }

    public record Result(Outcome outcome, List<ItemStack> loot) {
        static Result of(Outcome o) { return new Result(o, List.of()); }
    }

    /**
     * Ein Wurf von stand nach water. tick(now) liefert WAITING bis zum Biss, dann einmal CAUGHT samt Beute (schon im
     * Inventar bzw. vor ihm abgelegt) und wirft neu aus. Abbruchgründe liefern keine Beute und lassen die Angel heil.
     */
    public static final class Session {
        private final CompanionEntity e;
        public final BlockPos stand, water;
        private final RandomSource rng;
        private int biteAt = -1;
        private int caught;

        public Session(CompanionEntity e, BlockPos stand, BlockPos water, RandomSource rng) {
            this.e = e;
            this.stand = stand;
            this.water = water;
            this.rng = rng;
        }

        public int caught() { return caught; }
        public boolean casting() { return biteAt >= 0; }

        public Result tick(ServerLevel sl, int now) {
            int slot = rodSlot(e);
            if (slot < 0) { biteAt = -1; return Result.of(Outcome.NO_ROD); }
            if (!validWater(sl, water)) { biteAt = -1; return Result.of(Outcome.INVALID_WATER); }
            if (interrupted()) {
                boolean wasCasting = biteAt >= 0;
                biteAt = -1;
                return wasCasting ? Result.of(Outcome.INTERRUPTED) : Result.of(Outcome.WAITING);
            }
            ItemStack rod = e.getInventory().getItem(slot);
            e.getLookControl().setLookAt(Vec3.atCenterOf(water));
            if (biteAt < 0) {
                int lure = lureLevel(sl, rod, e);
                biteAt = now + FishingRules.waitTicks(rng.nextInt(FishingRules.WAIT_SPREAD + 1), lure);
                e.swingArm();
                sl.playSound(null, stand, SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5f, 0.4f / (rng.nextFloat() * 0.4f + 0.8f));
                return Result.of(Outcome.WAITING);
            }
            if (now < biteAt) return Result.of(Outcome.WAITING);
            biteAt = -1;
            List<ItemStack> loot = roll(sl, rod);
            e.swingArm();
            sl.playSound(null, stand, SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 1f, 0.4f / (rng.nextFloat() * 0.4f + 0.8f));
            sl.sendParticles(ParticleTypes.FISHING, water.getX() + 0.5, water.getY() + 1.0, water.getZ() + 0.5, 6, 0.2, 0, 0.2, 0.05);
            rod.hurtAndBreak(1, sl, null, item -> { });
            for (ItemStack stack : loot) {
                ItemStack left = e.giveToInventory(stack.copy());
                if (!left.isEmpty()) e.spawnAtLocation(sl, left);
            }
            caught++;
            return new Result(Outcome.CAUGHT, loot);
        }

        /** Vom Ufer weg, im Kampf oder gerade getroffen: Wurf verloren. */
        private boolean interrupted() {
            Vec3 at = Vec3.atBottomCenterOf(stand);
            return e.position().distanceToSqr(at) > LEAVE_SQR || e.getTarget() != null || e.hurtTime > 0;
        }

        private List<ItemStack> roll(ServerLevel sl, ItemStack rod) {
            float luck = luck(sl, rod, e);
            boolean open = openWater(sl, water);
            int total = FishingRules.totalWeight(luck, open);
            FishingRules.Catch group = FishingRules.pick(total <= 0 ? 0 : rng.nextInt(total), luck, open);
            var key = switch (group) {
                case FISH -> BuiltInLootTables.FISHING_FISH;
                case JUNK -> BuiltInLootTables.FISHING_JUNK;
                case TREASURE -> BuiltInLootTables.FISHING_TREASURE;
            };
            LootTable table = sl.getServer().reloadableRegistries().getLootTable(key);
            LootParams params = new LootParams.Builder(sl)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(water))
                    .withParameter(LootContextParams.TOOL, rod)
                    .withLuck(luck)
                    .create(LootContextParamSets.FISHING);
            List<ItemStack> out = new ArrayList<>();
            for (ItemStack s : table.getRandomItems(params, rng.nextLong())) if (!s.isEmpty()) out.add(s);
            if (out.isEmpty()) out.add(new ItemStack(Items.COD)); // leere Tabelle (Datapack): wenigstens ein Kabeljau
            return out;
        }
    }

    /** Köder: Vanilla liefert die Verkürzung in Sekunden (5 je Stufe). */
    static int lureLevel(ServerLevel sl, ItemStack rod, CompanionEntity e) {
        return Math.max(0, Math.round(EnchantmentHelper.getFishingTimeReduction(sl, rod, e) / 5f));
    }

    static float luck(ServerLevel sl, ItemStack rod, CompanionEntity e) {
        return EnchantmentHelper.getFishingLuckBonus(sl, rod, e);
    }
}
