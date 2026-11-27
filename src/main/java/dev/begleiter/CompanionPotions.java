package dev.begleiter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import dev.begleiter.CompanionEntity.Task;

/**
 * Tränke wie ein Spieler: Braustand, Netherwarze, Lohenstaub und Wasserflaschen für Feuerresistenz, Stärke, Heilung
 * und langsamen Fall. Getrunken wird in der Not (brennt, wenig Leben, tiefer Sturz) und vor harten Kämpfen
 * (Lohen, Drache). Gilt im freien Spiel genauso wie beim Spielziel.
 */
public final class CompanionPotions {
    /** Trankarten mit ihrer Zutat (nach dem Seltsamen Trank). */
    public enum Kind {
        FIRE("magma_cream", Potions.FIRE_RESISTANCE, Potions.LONG_FIRE_RESISTANCE),
        STRENGTH("blaze_powder", Potions.STRENGTH, Potions.LONG_STRENGTH, Potions.STRONG_STRENGTH),
        HEALING("glistering_melon_slice", Potions.HEALING, Potions.STRONG_HEALING),
        SLOW_FALL("phantom_membrane", Potions.SLOW_FALLING, Potions.LONG_SLOW_FALLING);

        public final String ingredient;
        private final Holder<Potion>[] potions;

        @SafeVarargs
        Kind(String ingredient, Holder<Potion>... potions) {
            this.ingredient = ingredient;
            this.potions = potions;
        }

        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

        public Holder<Potion> base() { return potions[0]; }

        boolean matches(ItemStack stack) {
            if (stack.isEmpty() || !stack.is(Items.POTION)) return false;
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents == null) return false;
            for (Holder<Potion> p : potions) if (contents.is(p)) return true;
            return false;
        }

        public static Kind byId(String id) {
            for (Kind k : values()) if (k.id().equals(id)) return k;
            return null;
        }
    }

    /** Ein Durchgang am Braustand: 3 Flaschen, wie in Vanilla. */
    public static final int BATCH = 3;

    private final CompanionEntity e;
    private int nextDrink;
    /** Restliche Braugänge aus dem letzten Lohenstaub (Vanilla: 20 je Staub). Laufzeit. */
    public int brewFuel;
    /** Nächste Suche nach Netherwarzen/Magmawürfeln im Nether (Laufzeit). */
    public int extrasUntil;

    CompanionPotions(CompanionEntity e) { this.e = e; }

    public static ItemStack potion(Kind kind) { return PotionContents.createItemStack(Items.POTION, kind.base()); }

    public static ItemStack waterBottle() { return PotionContents.createItemStack(Items.POTION, Potions.WATER); }

    public static boolean isWaterBottle(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.POTION)) return false;
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.is(Potions.WATER);
    }

    public int count(Kind kind) {
        int n = 0;
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (kind.matches(s)) n += s.getCount();
        }
        return n;
    }

    public int waterBottles() {
        int n = 0;
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (isWaterBottle(s)) n += s.getCount();
        }
        return n;
    }

    /** Trinkt einen Trank dieser Art; false = keiner dabei. Die leere Flasche behält er für den nächsten Brauvorgang. */
    public boolean drink(Kind kind) {
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!kind.matches(s)) continue;
            PotionContents contents = s.get(DataComponents.POTION_CONTENTS);
            boolean strong = contents != null && (contents.is(Potions.STRONG_STRENGTH) || contents.is(Potions.STRONG_HEALING));
            boolean longer = contents != null && (contents.is(Potions.LONG_FIRE_RESISTANCE) || contents.is(Potions.LONG_STRENGTH)
                    || contents.is(Potions.LONG_SLOW_FALLING));
            s.shrink(1);
            apply(kind, strong, longer);
            e.giveToInventory(new ItemStack(Items.GLASS_BOTTLE));
            nextDrink = e.tickCount + 40; // Trinken dauert, nicht jeden Tick einen neuen
            e.logInfo(CompanionTexts.tr("log.potion.drink", CompanionTexts.tr("potion." + kind.id()), (int) e.getHealth()));
            return true;
        }
        return false;
    }

    private void apply(Kind kind, boolean strong, boolean longer) {
        switch (kind) {
            case FIRE -> e.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, longer ? 9600 : 3600, 0));
            case STRENGTH -> e.addEffect(new MobEffectInstance(MobEffects.STRENGTH, strong ? 1800 : longer ? 9600 : 3600, strong ? 1 : 0));
            case HEALING -> e.heal(strong ? 8f : 4f);
            case SLOW_FALL -> e.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, longer ? 4800 : 1800, 0));
        }
    }

    /** Alle 5 Ticks: in der Not oder vor einem harten Kampf den passenden Trank trinken. */
    void tick() {
        if (e.tickCount < nextDrink || !(e.level() instanceof ServerLevel sl)) return;
        boolean magma = e.onGround() && e.getBlockStateOn().is(Blocks.MAGMA_BLOCK);
        boolean hot = e.isInLava() || e.isOnFire() || magma;
        if (hot && !e.hasEffect(MobEffects.FIRE_RESISTANCE) && drink(Kind.FIRE)) return;
        LivingEntity target = e.getTarget();
        boolean nether = sl.dimension() == Level.NETHER;
        // Vor dem Lohenkampf oder direkt neben Lava im Nether vorbeugend (nur mit Reserve)
        if (nether && !e.hasEffect(MobEffects.FIRE_RESISTANCE) && count(Kind.FIRE) >= 2
                && (target instanceof net.minecraft.world.entity.monster.Blaze && e.distanceToSqr(target) < 24 * 24
                    || e.nearestLava != null && e.nearestLava.distSqr(e.blockPosition()) <= 9)) {
            if (drink(Kind.FIRE)) return;
        }
        if (e.getHealth() < 8 && (target != null || hot || e.getLastHurtByMob() != null) && drink(Kind.HEALING)) return;
        boolean dragon = target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                || sl.dimension() == Level.END && target != null && e.journey.fightingDragon();
        if (dragon && !e.hasEffect(MobEffects.STRENGTH) && drink(Kind.STRENGTH)) return;
        if (e.fallDistance > 12 && !e.isInWater() && !e.hasEffect(MobEffects.SLOW_FALLING)
                && e.findItem(Items.WATER_BUCKET) < 0 && drink(Kind.SLOW_FALL)) return;
    }

    // ------------------------------------------------------------------ Brauen planen

    /** Hat er schon einen Braustand (getragen oder in der Nähe der Basis)? */
    public boolean hasStand() {
        if (CompanionCraft.owns(e, "brewing_stand")) return true;
        if (standKnown) return true;
        if (e.tickCount - standChecked < 200 && standChecked != 0) return false;
        standChecked = e.tickCount;
        standKnown = e.findStation("brewing_stand", 24) != null
                || e.getHome() != null && e.level() instanceof ServerLevel sl && nearStand(sl, e.getHome());
        return standKnown;
    }

    /** Ein aufgestellter Braustand wurde gefunden (bleibt bis zum Neuladen gemerkt); standChecked = letzte Suche. */
    private boolean standKnown;
    private int standChecked;

    /** Nach dem Aufstellen (Brau-Aufgabe) sofort merken. */
    public void noteStand() { standKnown = true; }

    private static boolean nearStand(ServerLevel sl, BlockPos home) {
        if (!sl.hasChunkAt(home)) return false;
        for (BlockPos p : BlockPos.betweenClosed(home.offset(-8, -3, -8), home.offset(8, 3, 8)))
            if (sl.getBlockState(p).is(Blocks.BREWING_STAND)) return true;
        return false;
    }

    /** Wie viele Tränke dieser Art er gern dabei hätte. */
    public int wanted(Kind kind, boolean dragonSoon, int netherDeaths, int endDeaths) {
        return switch (kind) {
            case FIRE -> CompanionBrain.dragonDefeated(e) ? 0 : 3;
            case STRENGTH -> dragonSoon || endDeaths > 0 ? 3 : 0;
            case HEALING -> endDeaths > 0 || netherDeaths >= 2 ? 3 : 0;
            case SLOW_FALL -> endDeaths > 0 ? 3 : 0;
        };
    }

    /** Zutat für diese Art vorhanden oder ohne neue Reise herstellbar? */
    public boolean ingredientAvailable(Kind kind) {
        if (CompanionCraft.count(e, kind.ingredient) > 0) return true;
        return switch (kind) {
            case FIRE -> CompanionCraft.count(e, "slime_ball") > 0 && powderAvailable(2);
            case STRENGTH -> powderAvailable(2);
            case HEALING -> CompanionCraft.count(e, "melon_slice") > 0
                    && CompanionCraft.count(e, "gold_nugget") + 9 * CompanionCraft.count(e, "gold_ingot") >= 8;
            case SLOW_FALL -> false;
        };
    }

    /** Lohenstaub (direkt oder aus Ruten), ohne die Ruten für die Enderaugen anzugreifen. */
    private boolean powderAvailable(int need) {
        int spareRods = CompanionCraft.count(e, "blaze_rod") - CompanionBrain.eyeRodTarget(e) - (hasStand() ? 0 : 1);
        return CompanionCraft.count(e, "blaze_powder") + 2 * Math.max(0, spareRods) >= need;
    }

    /**
     * Nächster Schritt zum Brauen: Braustand, Flaschen, Wasser, Zutat, dann der Brauvorgang selbst.
     * false = gerade nichts sinnvoll machbar (fehlt Netherwarze oder Zutat).
     */
    public boolean planBrew(Kind kind, int want) {
        int have = count(kind);
        if (have >= want) return false;
        if (CompanionCraft.count(e, "nether_wart") < 1 || !ingredientAvailable(kind)) return false;
        if (!hasStand() && CompanionCraft.count(e, "blaze_rod") < 1) return false;
        int bottles = Math.min(BATCH, want - have);
        int water = waterBottles(), glass = CompanionCraft.count(e, "glass_bottle");
        if (water + glass < bottles) {
            var plan = CompanionPlanner.obtain(e, Items.GLASS_BOTTLE, bottles - water - glass);
            if (plan == null) return false;
            for (Task t : plan.tasks()) e.addTask(t);
        }
        if (water < bottles && !CompanionCraft.owns(e, "water_bucket")) {
            if (!CompanionCraft.owns(e, "bucket") && !CompanionCraft.owns(e, "water_bucket")) {
                var plan = CompanionPlanner.obtain(e, Items.BUCKET, 1);
                if (plan == null) return false;
                for (Task t : plan.tasks()) e.addTask(t);
            }
            e.addTask(Task.water(null));
        }
        e.addTask(Task.brew(kind.id(), have + bottles));
        return true;
    }
}
