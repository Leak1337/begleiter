package dev.begleiter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/** Baustein Ausrüstung: Rüstung, Schild, Waffe und Werkzeug aus dem Inventar wählen; Abbauzeit. Ohne Zustand. */
public final class CompanionEquipment {
    private static Object swingDefault;
    private static boolean swingTried;

    private final CompanionEntity e;

    CompanionEquipment(CompanionEntity e) { this.e = e; }

    private SimpleContainer inv() { return e.getInventory(); }

    /** Die Schwing-Animation (SwingAnimation) hat in 26.x keinen bekannten Standardwert -> per Reflection suchen. */
    static Object swingAnimation() {
        if (swingTried) return swingDefault;
        swingTried = true;
        try {
            Class<?> c = net.minecraft.world.item.component.SwingAnimation.class;
            for (java.lang.reflect.Field f : c.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == c) {
                    swingDefault = f.get(null);
                    return swingDefault;
                }
            }
            Class<?> tc = net.minecraft.world.item.SwingAnimationType.class;
            Object type = null;
            Object[] consts = tc.getEnumConstants();
            for (Object o : consts) if (o.toString().equalsIgnoreCase("whack")) type = o;
            if (type == null) type = consts[0];
            for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                Class<?>[] pt = ctor.getParameterTypes();
                if (pt.length == 2 && pt[0] == tc && pt[1] == int.class) {
                    ctor.setAccessible(true);
                    swingDefault = ctor.newInstance(type, 6);
                    return swingDefault;
                }
            }
        } catch (Exception | LinkageError ex) {
            CompanionLog.LOG.warn("[Begleiter] Swing animation unavailable: {}", ex.toString());
        }
        return null;
    }

    private static double armorScore(ItemStack s, EquipmentSlot slot) {
        var m = s.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (m == null) return 0;
        return m.compute(Attributes.ARMOR, 0, slot) + 0.5 * m.compute(Attributes.ARMOR_TOUGHNESS, 0, slot);
    }

    private static double weaponScore(ItemStack s) {
        var m = s.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (m == null) return 0;
        // nur Bonus-Schaden zählt, sonst gilt jeder Block (Basis 1) als bessere Waffe als die leere Hand
        return m.compute(Attributes.ATTACK_DAMAGE, 1, EquipmentSlot.MAINHAND) - 1;
    }

    private void swapIn(EquipmentSlot slot, int invIndex) {
        ItemStack candidate = inv().getItem(invIndex).copy();
        ItemStack old = e.getItemBySlot(slot).copy();
        e.setItemSlot(slot, candidate);
        inv().setItem(invIndex, old);
    }

    void autoEquip() {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            int best = -1;
            double bestScore = e.getItemBySlot(slot).isEmpty() ? -1 : armorScore(e.getItemBySlot(slot), slot);
            for (int i = 0; i < inv().getContainerSize(); i++) {
                ItemStack s = inv().getItem(i);
                if (s.isEmpty() || e.reservedForRecipe(s)) continue;
                var eq = s.get(DataComponents.EQUIPPABLE);
                if (eq == null || eq.slot() != slot) continue;
                double sc = armorScore(s, slot);
                if (sc > bestScore) { bestScore = sc; best = i; }
            }
            if (best >= 0) swapIn(slot, best);
        }
        if (e.getOffhandItem().isEmpty()) {
            int shield = e.findItem(Items.SHIELD);
            if (shield >= 0 && !e.reservedForRecipe(inv().getItem(shield))) swapIn(EquipmentSlot.OFFHAND, shield);
        }
        if (e.getTarget() != null || !e.hasTasks()) equipBestWeapon();
    }

    public void equipBestWeapon() {
        int best = -1;
        double bestScore = weaponScore(e.getMainHandItem());
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack s = inv().getItem(i);
            if (s.isEmpty() || e.reservedForRecipe(s)) continue;
            double sc = weaponScore(s);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        if (best >= 0) swapIn(EquipmentSlot.MAINHAND, best);
    }

    /** Bewertung eines Werkzeugs für einen Block: richtiges Werkzeug zählt zuerst, dann die Abbaugeschwindigkeit. */
    private static double toolScore(ItemStack s, BlockState state) {
        double sc = s.getDestroySpeed(state);
        if (state.requiresCorrectToolForDrops() && s.isCorrectToolForDrops(state)) sc += 1000;
        // Werkzeug-Pflege: fast kaputtes Werkzeug nur, wenn es nicht anders geht (lieber Ersatz oder die Hand)
        if (s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 3) sc -= 500;
        return sc;
    }

    public void equipToolFor(BlockState state) {
        double best = toolScore(e.getMainHandItem(), state);
        int bestIdx = -1;
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack s = inv().getItem(i);
            if (s.isEmpty() || e.reservedForRecipe(s)) continue;
            double sc = toolScore(s, state);
            if (sc > best + 0.01) { best = sc; bestIdx = i; }
        }
        if (bestIdx >= 0) { swapIn(EquipmentSlot.MAINHAND, bestIdx); return; }
        // Kein Werkzeug hilft bei diesem Block (z.B. Erde ohne Schaufel): Hand freimachen statt Spitzhacke zu verschleißen
        ItemStack held = e.getMainHandItem();
        if (!held.isEmpty() && best <= 1.01 && held.has(DataComponents.TOOL)) {
            for (int i = 0; i < inv().getContainerSize(); i++) {
                if (inv().getItem(i).isEmpty()) {
                    inv().setItem(i, held.copy());
                    e.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    return;
                }
            }
        }
    }

    public boolean hasCorrectToolFor(BlockState s) {
        if (e.getMainHandItem().isCorrectToolForDrops(s)) return true;
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack st = inv().getItem(i);
            if (!st.isEmpty() && st.isCorrectToolForDrops(s)) return true;
        }
        return false;
    }

    public int breakTicks(ServerLevel sl, BlockPos p, BlockState s) {
        float hardness = s.getDestroySpeed(sl, p);
        ItemStack tool = e.getMainHandItem();
        float speed = Math.max(1f, tool.getDestroySpeed(s));
        boolean harvest = !s.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(s);
        return Math.max(5, (int) Math.ceil(hardness * (harvest ? 30 : 100) / speed));
    }
}
