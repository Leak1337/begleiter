package dev.begleiter;

import dev.begleiter.debug.BotDebug.EventKind;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Baustein Essen: Hunger (0–20), sein Takt und was er isst. Speichert {@code Hunger}/{@code HungerTimer} wie
 * zuvor direkt an der Entity. Der Todeskern enthält keinen Hunger; Wiederbeleben setzt ihn satt zurück.
 */
public final class CompanionFood {
    public static final int MAX = 20;
    /** Hungerpunkt alle so viele Sekunden (Sprinten zählt doppelt). */
    static final int SECONDS_PER_POINT = 30;

    private final CompanionEntity e;
    private int level = MAX;
    private int timer;
    /** Sättigung wie beim Spieler: wird vor dem Hunger verbraucht, höchstens so hoch wie der Hunger. */
    private float saturation = 5;

    CompanionFood(CompanionEntity e) { this.e = e; }

    private static final java.util.Set<String> FOOD_ANIMALS = RegistryIds.minecraftSet("cow", "pig", "sheep", "chicken");

    /** Nächstes jagdbares Nutztier (Kuh, Schwein, Schaf, Huhn) im Umkreis von 40 Blöcken; null = keins. */
    public net.minecraft.world.entity.Mob nearestAnimal() {
        if (!(e.level() instanceof net.minecraft.server.level.ServerLevel sl)) return null;
        var l = sl.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, e.getBoundingBox().inflate(40),
                m -> m.isAlive() && !e.isIgnoredTarget(m)
                        && FOOD_ANIMALS.contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString())
                        && CompanionJobs.mayHunt(e, m));
        return l.stream().min(java.util.Comparator.comparingDouble(e::distanceToSqr)).orElse(null);
    }

    public int level() { return level; }
    /** Direkt setzen (Tests, Befehle), in Grenzen 0–20. */
    public void set(int value) { level = Math.clamp(value, 0, MAX); saturation = Math.min(saturation, level); }
    /** Satt und Takt von vorn (Wiederbeleben). */
    void reset() { level = MAX; timer = 0; saturation = 5; }

    /** Einmal pro Sekunde: Hunger zählen und bei Bedarf essen. */
    void secondTick() {
        if (e.isSprinting()) timer++; // Sprinten macht doppelt so schnell hungrig
        if (++timer >= SECONDS_PER_POINT) {
            timer = 0;
            if (saturation >= 1) saturation -= 1;
            else if (level > 0) level--;
        }
        // Vorrat schonen (beobachtet: ganze Ernte an einem Tag bei Hunger 13–16 gegessen): ab 14 heilt er von selbst
        if (level <= 8 || (level < 14 && e.getHealth() < e.getMaxHealth() - 8)
                || (level < MAX && e.getHealth() < 16)) eat();
    }

    private static boolean isGoldenApple(ItemStack st) {
        return st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE);
    }

    /** Goldener Apfel für den Notfall: Regeneration und Absorption. */
    public boolean eatGoldenApple() {
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack st = e.getInventory().getItem(i);
            if (!isGoldenApple(st)) continue;
            eatApple(st);
            return true;
        }
        return false;
    }

    private void eatApple(ItemStack st) {
        boolean enchanted = st.is(Items.ENCHANTED_GOLDEN_APPLE);
        st.shrink(1);
        e.addEffect(new MobEffectInstance(MobEffects.REGENERATION, enchanted ? 400 : 100, 1));
        e.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, enchanted ? 3 : 0));
        e.heal(2f);
        level = Math.min(MAX, level + 4);
        saturation = Math.min(level, saturation + 9.6f);
        e.logInfo(EventKind.FOOD, CompanionTexts.tr("log.food.apple", (int) e.getHealth()));
    }

    private int lastEatTick = -1000;

    /** Höchstens alle 2 Sekunden essen (Essen dauert auch beim Spieler). */
    public void eatThrottled() {
        if (e.tickCount - lastEatTick < 40) return;
        lastEatTick = e.tickCount;
        eat();
    }

    /** Vor einem langen Weg essen, mit den üblichen Regeln und Auftragsreservierungen. */
    public void eatBeforeTrip() { if (level < 12) eat(); }

    /** Das Nahrhafteste essen, was nicht für einen Auftrag reserviert ist; goldene Äpfel nur im Notfall. */
    public void eat() {
        int best = -1, bestN = -1;
        boolean emergency = e.getHealth() < 8;
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack st = e.getInventory().getItem(i);
            var food = st.get(DataComponents.FOOD);
            if (st.isEmpty() || food == null || e.reservedForRecipe(st)) continue;
            // goldene Äpfel nur im Notfall (oder wenn es nichts anderes gibt)
            int score = isGoldenApple(st) ? (emergency ? 100 : -1) : food.nutrition();
            if (score > bestN) { best = i; bestN = score; }
        }
        if (best < 0) return;
        ItemStack st = e.getInventory().getItem(best);
        if (isGoldenApple(st)) { eatApple(st); return; }
        var props = st.get(DataComponents.FOOD);
        int nutrition = props.nutrition();
        Component foodName = st.getHoverName();
        level = Math.min(MAX, level + nutrition);
        saturation = Math.min(level, saturation + props.saturation());
        e.heal(nutrition / 2.0f);
        st.shrink(1);
        e.logInfo(EventKind.FOOD, CompanionTexts.tr("log.food.eaten", foodName, level));
    }

    void save(ValueOutput out) {
        out.putInt("Hunger", level);
        out.putInt("HungerTimer", timer);
        out.putFloat("Saturation", saturation);
    }

    void load(ValueInput in) {
        level = Math.clamp(in.getIntOr("Hunger", MAX), 0, MAX);
        timer = Math.clamp(in.getIntOr("HungerTimer", 0), 0, SECONDS_PER_POINT - 1);
        saturation = Math.clamp(in.getFloatOr("Saturation", 0f), 0f, level);
    }
}
