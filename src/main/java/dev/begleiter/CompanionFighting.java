package dev.begleiter;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.debug.BotDebug.EventKind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Baustein Kampf: Rückzug, Zielwahl nach Bedrohung, aufgegebene Ziele, Schild, Essen im Kampf und Bogen.
 * Nur Laufzeitzustand (nicht gespeichert). Die Haltung ({@code stance}) und das Jagdziel bleiben an der Entity, weil
 * Menü, Befehle und Aufgaben sie setzen.
 */
public final class CompanionFighting {
    private static final Set<String> FLYING_TARGETS = RegistryIds.minecraftSet("blaze", "ender_dragon", "phantom", "ghast");

    private final CompanionEntity e;
    private int retreatUntil;
    private LivingEntity fleeFrom;
    private LivingEntity watchTarget;
    private int combatSince;
    private double watchBestDist;
    private float watchHealth;
    /** Mobs, an die er nicht herankam: bis zu diesem Tick nicht wieder angreifen. */
    private final Map<UUID, Integer> ignoredTargets = new HashMap<>();
    private int lastCombatEat = -1000;
    private int lastShotTick = -100;
    private int lastOverheadStrike = -100;

    CompanionFighting(CompanionEntity e) { this.e = e; }

    // ------------------------------------------------------------------ Zustand

    public boolean retreating() { return e.tickCount < retreatUntil; }

    /** In Gefahr: zieht sich gerade zurück oder wurde vor Kurzem getroffen. */
    public boolean inDanger() {
        return retreating() || (e.getLastHurtByMob() != null && e.tickCount - e.getLastHurtByMobTimestamp() < 100);
    }

    /** Dieses Wesen eine Weile nicht als Ziel nehmen (z.B. Rückweg nicht nachweisbar). */
    public void ignore(LivingEntity t, int ticks) {
        ignoredTargets.values().removeIf(u -> u < e.tickCount);
        ignoredTargets.put(t.getUUID(), e.tickCount + Math.max(1, ticks));
    }

    public boolean ignored(LivingEntity t) {
        Integer until = ignoredTargets.get(t.getUUID());
        return until != null && e.tickCount < until;
    }

    /** Rückzug beenden (eingemauert: keine Flucht und kein Ziel mehr). */
    void stopRetreat() {
        retreatUntil = 0;
        fleeFrom = null;
        e.setTarget(null);
    }

    private void startRetreat(LivingEntity from, int ticks, Component why) {
        fleeFrom = from;
        retreatUntil = e.tickCount + ticks;
        e.setTarget(null);
        e.logWarn(EventKind.DANGER, why);
    }

    /** Kämpft er gerade für einen Auftrag (Jagen, Kämpfen, Kristalle)? Dann entscheidet der Auftrag das Ziel. */
    boolean orderedFight() {
        Task t = e.currentTask();
        return e.huntTarget != null || (t != null && (t.type() == TaskType.FIGHT || t.type() == TaskType.HUNT
                || t.type() == TaskType.CRYSTALS || t.type() == TaskType.FETCH));
    }

    // ------------------------------------------------------------------ Bogen

    public boolean hasBowAndArrows() { return e.findItem(Items.BOW) >= 0 && e.findItem(Items.ARROW) >= 0; }

    public boolean canShoot() { return hasBowAndArrows() && e.tickCount - lastShotTick >= 20; }

    /** Einen Pfeil auf ein Ziel schießen (auch auf Endkristalle, die keine Mobs sind). */
    public boolean shoot(Entity target) {
        if (!(e.level() instanceof ServerLevel sl) || !canShoot()) return false;
        ItemStack bow = e.getInventory().getItem(e.findItem(Items.BOW));
        var arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(sl, e, new ItemStack(Items.ARROW), bow.copy());
        double dx = target.getX() - e.getX(), dy = target.getY(0.5) - arrow.getY(), dz = target.getZ() - e.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + d * 0.12, dz, 2.6f, 1.5f);
        sl.addFreshEntity(arrow);
        e.getInventory().getItem(e.findItem(Items.ARROW)).shrink(1);
        bow.hurtAndBreak(1, sl, null, item -> {});
        e.getLookControl().setLookAt(target);
        sl.playSound(null, e.blockPosition(), net.minecraft.sounds.SoundEvents.ARROW_SHOOT, net.minecraft.sounds.SoundSource.NEUTRAL, 1f, 1f);
        lastShotTick = e.tickCount;
        return true;
    }

    // ------------------------------------------------------------------ Kampf

    /** Kommt er seinem Kampfziel 8 s lang nicht näher und trifft es nicht (Höhle, hinter Wand): aufgeben. */
    private void watch(LivingEntity tgt) {
        if (tgt == null || !tgt.isAlive()) { watchTarget = null; return; }
        double d = e.distanceToSqr(tgt);
        if (tgt != watchTarget) {
            watchTarget = tgt; combatSince = e.tickCount; watchBestDist = d; watchHealth = tgt.getHealth();
            return;
        }
        // Fortschritt: näher gekommen oder selbst getroffen (Sonnenbrand o.ä. zählt nicht)
        boolean hitByMe = tgt.getHealth() < watchHealth && tgt.getLastHurtByMob() == e;
        if (d < watchBestDist - 1.0 || hitByMe) {
            watchBestDist = Math.min(watchBestDist, d); watchHealth = tgt.getHealth(); combatSince = e.tickCount;
            return;
        }
        boolean overhead = FLYING_TARGETS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(tgt.getType()).toString())
                && e.getEyePosition().distanceToSqr(tgt.getX(), tgt.getY(0.5), tgt.getZ()) <= 5 * 5;
        if (e.tickCount - combatSince > 160 && !overhead && !(canShoot() && e.hasLineOfSight(tgt))) {
            e.logWarn(EventKind.DANGER, CompanionTexts.tr("log.combat.unreachable", tgt.getName()));
            ignoredTargets.put(tgt.getUUID(), e.tickCount + 600);
            ignoredTargets.values().removeIf(u -> u < e.tickCount);
            e.setTarget(null);
            if (e.huntTarget == tgt) e.huntTarget = null;
            e.getNavigation().stop();
            watchTarget = null;
        }
    }

    /**
     * Alle fünf Ticks: wie ein Spieler bei wenig Leben zurückziehen und essen, vor zischenden Creepern weg,
     * Schild gegen Fernkämpfer, Bogen gegen Fernes und Fliegendes.
     */
    void tick() {
        LivingEntity tgt = e.getTarget();
        boolean ordered = orderedFight();
        // Passiv: nicht von selbst kämpfen; wer ihn angreift, vor dem flieht er (zum Besitzer)
        if (e.stance == CompanionCombat.Stance.PASSIVE && !ordered) {
            if (tgt != null) { e.setTarget(null); tgt = null; }
            LivingEntity attacker = e.getLastHurtByMob();
            if (attacker != null && attacker.isAlive() && e.tickCount - e.getLastHurtByMobTimestamp() < 10 && !retreating())
                startRetreat(attacker, 60, CompanionTexts.tr("log.retreat.attacked"));
        }
        // Zischender Creeper direkt daneben: immer weg, auch ohne Ziel und im Haus (beobachtet: dreimal im/am Haus
        // nur gewarnt und stehen geblieben, einmal tot)
        if (!retreating() && e.tickCount % 5 == 0 && e.level() instanceof net.minecraft.server.level.ServerLevel) {
            for (Creeper cr : e.level().getEntitiesOfClass(Creeper.class, e.getBoundingBox().inflate(5),
                    c -> c.isAlive() && (c.getSwellDir() > 0 || c.isIgnited()))) {
                startRetreat(cr, 60, CompanionTexts.tr("log.retreat.creeper_hiss"));
                break;
            }
        }
        // Im Notunterschlupf: nicht kämpfen, nur heilen (die Wände halten Pfeile und Zombies ab)
        if (e.isSheltered() && !retreating()) {
            if (tgt != null) e.setTarget(null);
            return;
        }
        // Wichtigere Bedrohung? (z.B. ein Skelett, das auf den Besitzer schießt, statt der Spinne neben ihm)
        // Nicht mit wenig Leben: sonst greift er nach jedem Rückzug wieder an und wird erneut getroffen
        if (!retreating() && !ordered && e.stance != CompanionCombat.Stance.PASSIVE && e.tickCount % 10 == 0 && e.getHealth() >= 12) {
            LivingEntity best = CompanionCombat.bestThreat(e, 16);
            if (best != null && best != tgt) {
                double cur = tgt == null || !tgt.isAlive() ? Double.NEGATIVE_INFINITY : CompanionCombat.threat(e, tgt);
                if (CompanionCombat.threat(e, best) > cur + 20) {
                    if (tgt != null) e.logInfo(EventKind.DANGER, CompanionTexts.tr("log.combat.priority", best.getName(), tgt.getName()));
                    e.setTarget(best);
                }
            }
        }
        if (!retreating()) watch(e.getTarget());
        tgt = e.getTarget();
        // Creeper: mit Bogen auf Abstand gehen und schießen, ohne Bogen zuschlagen und gleich zurückspringen
        if (tgt instanceof Creeper cr && !retreating() && e.getHealth() >= 12) {
            double d2 = e.distanceToSqr(cr);
            if (hasBowAndArrows() && d2 < 6 * 6) startRetreat(cr, 20, CompanionTexts.tr("log.retreat.creeper_close"));
            else if (!hasBowAndArrows() && cr.hurtTime > 0 && e.getLastHurtMob() == cr && d2 < 9) startRetreat(cr, 15, CompanionTexts.tr("log.retreat.creeper_hit"));
        }
        if (tgt != null && !retreating()) {
            if (e.getHealth() < 12) {
                startRetreat(tgt, 120, CompanionTexts.tr("log.retreat.low_health", (int) e.getHealth()));
                if (e.food.level() < 18 || e.getHealth() < 8) e.food.eatThrottled();
            } else if (tgt instanceof Creeper cr && e.distanceToSqr(cr) < 36 && (cr.getSwellDir() > 0 || cr.isIgnited())) {
                // die Explosion reicht etwa 6 Blöcke weit: früh und lange genug zurückweichen
                startRetreat(tgt, 60, CompanionTexts.tr("log.retreat.creeper_hiss"));
            }
        }
        if (retreating()) {
            e.motion.claim(dev.begleiter.nav.MotionController.Owner.COMBAT);
            e.nav.stop();
            if (e.getTarget() != null) e.setTarget(null);
            if (fleeFrom != null && fleeFrom.isAlive()) {
                // Zum Besitzer fliehen, wenn der weiter weg von der Gefahr steht; sonst einfach weg
                Player owner = e.getOwner();
                if (owner != null && owner.level() == e.level() && owner.distanceToSqr(e) < 32 * 32
                        && owner.distanceToSqr(e) > 9 && owner.distanceToSqr(fleeFrom) > e.distanceToSqr(fleeFrom) + 4) {
                    e.getNavigation().moveTo(owner, 1.3);
                } else {
                    Vec3 away = e.position().subtract(fleeFrom.position());
                    if (away.lengthSqr() < 1.0E-4) away = new Vec3(1, 0, 0);
                    away = away.normalize().scale(8);
                    e.getNavigation().moveTo(e.getX() + away.x, e.getY(), e.getZ() + away.z, 1.3);
                }
            }
        } else {
            // Erholt (oder nur kurz auf Abstand gegangen): weiterkämpfen
            if (fleeFrom != null && fleeFrom.isAlive() && e.getTarget() == null && e.getHealth() >= 12
                    && e.stance != CompanionCombat.Stance.PASSIVE && e.distanceToSqr(fleeFrom) < 20 * 20 && !ignored(fleeFrom))
                e.setTarget(fleeFrom);
            fleeFrom = null;
        }

        // Im Kampf heilen: bei wenig Leben goldener Apfel, sonst essen, wenn gerade niemand direkt dran ist
        if ((e.getTarget() != null || retreating()) && e.getHealth() < e.getMaxHealth() * 0.5f && e.tickCount - lastCombatEat >= 40) {
            LivingEntity near = retreating() ? fleeFrom : e.getTarget();
            boolean safe = near == null || e.distanceToSqr(near) > 6 * 6 || retreating();
            if (e.getHealth() < 8 && e.food.eatGoldenApple()) lastCombatEat = e.tickCount;
            // satt heilt er ohnehin nach und nach; Vorrat nur angreifen, wenn es wirklich eng wird
            else if (safe && e.foodCount() > 0 && (e.food.level() < 14 || e.getHealth() < e.getMaxHealth() * 0.3f)) {
                e.food.eat();
                lastCombatEat = e.tickCount;
            }
        }

        // Schild: gegen Pfeile hochnehmen, für den Nahkampf wieder runter
        boolean shield = e.getOffhandItem().is(Items.SHIELD);
        LivingEntity threat = retreating() ? fleeFrom : e.getTarget();
        boolean block = shield && threat != null && threat.isAlive() && e.distanceToSqr(threat) < 24 * 24
                && ((threat instanceof RangedAttackMob && e.distanceToSqr(threat) > 9)
                || (threat instanceof Creeper cr && cr.getSwellDir() > 0));
        if (block && !e.isUsingItem()) e.startUsingItem(InteractionHand.OFF_HAND);
        else if (!block && e.isUsingItem() && e.getUsedItemHand() == InteractionHand.OFF_HAND) e.stopUsingItem();

        // Schwebendes Ziel knapp über ihm (Lohe am Spawner): hochschlagen wie ein Spieler; die Nahkampf-KI trifft nur
        // auf Körperhöhe (beobachtet: 26-mal "Lohe nicht erreichbar" 3 Blöcke unter dem Spawner)
        LivingEntity over = e.getTarget();
        if (over != null && over.isAlive() && !retreating() && over.getY() > e.getY() + 1
                && e.getEyePosition().distanceToSqr(over.getX(), over.getY(0.5), over.getZ()) <= 3.6 * 3.6
                && e.hasLineOfSight(over) && e.tickCount - lastOverheadStrike >= 12
                && e.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            e.gear.equipBestWeapon();
            e.getLookControl().setLookAt(over);
            e.doHurtTarget(sl, over);
            e.swingArm();
            lastOverheadStrike = e.tickCount;
        }
        // Bogen: Ziel weiter weg oder in der Luft (Lohe, Drache, Phantom, Ghast)
        LivingEntity aim = e.getTarget();
        if (aim != null && aim.isAlive() && canShoot() && e.hasLineOfSight(aim)) {
            double d2 = e.distanceToSqr(aim);
            boolean flying = FLYING_TARGETS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(aim.getType()).toString());
            if (d2 < 48 * 48 && (d2 > 6 * 6 || (flying && aim.getY() > e.getY() + 2)) && !CompanionCombat.ownerInLine(e, aim)) shoot(aim);
        }
    }
}
