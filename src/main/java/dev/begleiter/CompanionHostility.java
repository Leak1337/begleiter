package dev.begleiter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * Feindliche Mobs behandeln den Begleiter wie einen Spieler: Jedes schlichte "nächsten Spieler angreifen"-Ziel eines
 * Mobs bekommt beim Laden einen Zwilling für Begleiter mit denselben Bedingungen (Priorität, Intervall, Sicht,
 * Erreichbarkeit, Selektor – Ertrunkene z.B. nur im Wasser). Gleiche Priorität, Spieler-Ziel zuerst eingetragen.
 *
 * Bewusst ausgelassen: Ziel-Unterklassen mit eigener Logik (Spinne nur im Dunkeln, Enderman nur bei Blickkontakt,
 * Hexe/Raider-Sonderziele) und Mobs mit Brain-KI (Piglin, Hoglin, Warden) – die verhalten sich wie bisher.
 *
 * targetSelector und die Goal-Felder sind geschützt und haben keinen Getter; 26.x läuft unverschleiert, die
 * Mojang-Namen gelten also auch zur Laufzeit. Fehlt ein Feld (künftige Version), wird das einmal geloggt und die
 * Funktion still abgeschaltet statt Mobs zu beschädigen.
 */
public final class CompanionHostility {
    private static final Logger LOG = LoggerFactory.getLogger("begleiter");

    private static Field targetSelector, targetType, randomInterval, targetConditions, mustSee, mustReach, selector;
    private static boolean broken;

    private CompanionHostility() { }

    /** Zwilling des Spieler-Ziels; eigene Klasse, damit er nie doppelt eingetragen wird. */
    static final class CompanionTarget extends NearestAttackableTargetGoal<CompanionEntity> {
        CompanionTarget(Mob mob, int interval, boolean see, boolean reach, TargetingConditions.Selector sel) {
            super(mob, CompanionEntity.class, interval, see, reach, sel);
        }
    }

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mob mob && entity instanceof Enemy && !(entity instanceof CompanionEntity)) addTargets(mob);
        });
    }

    /** Ergänzt für jedes schlichte Spieler-Ziel ein gleichwertiges Begleiter-Ziel; liefert die Anzahl neuer Ziele. */
    static int addTargets(Mob mob) {
        if (broken || !resolve()) return 0;
        try {
            GoalSelector selectorOf = (GoalSelector) targetSelector.get(mob);
            List<WrappedGoal> twins = new ArrayList<>();
            for (WrappedGoal w : selectorOf.getAvailableGoals()) {
                if (w.getGoal() instanceof CompanionTarget) return 0; // schon ergänzt (erneutes Laden)
                if (w.getGoal().getClass() != NearestAttackableTargetGoal.class) continue; // Unterklassen: eigene Logik
                if (targetType.get(w.getGoal()) != Player.class) continue;
                twins.add(w);
            }
            for (WrappedGoal w : twins) {
                var g = w.getGoal();
                TargetingConditions cond = (TargetingConditions) targetConditions.get(g);
                selectorOf.addGoal(w.getPriority(), new CompanionTarget(mob, randomInterval.getInt(g), mustSee.getBoolean(g),
                        mustReach.getBoolean(g), (TargetingConditions.Selector) selector.get(cond)));
            }
            return twins.size();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            disable(ex);
            return 0;
        }
    }

    private static boolean resolve() {
        if (targetSelector != null) return true;
        try {
            targetSelector = field(Mob.class, "targetSelector");
            targetType = field(NearestAttackableTargetGoal.class, "targetType");
            randomInterval = field(NearestAttackableTargetGoal.class, "randomInterval");
            targetConditions = field(NearestAttackableTargetGoal.class, "targetConditions");
            mustSee = field(TargetGoal.class, "mustSee");
            mustReach = field(TargetGoal.class, "mustReach");
            selector = field(TargetingConditions.class, "selector");
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            targetSelector = null;
            disable(ex);
            return false;
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void disable(Exception ex) {
        if (broken) return;
        broken = true;
        LOG.warn("[Begleiter] Mobs cannot target the companion (Minecraft internals changed?): {}", ex.toString());
    }
}
