package dev.begleiter;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Kampf-Verhalten wie ein Mitspieler: Kampfmodus (aggressiv/defensiv/passiv), den Besitzer verteidigen, Bedrohungen
 * nach Wichtigkeit wählen und nicht in den Besitzer schießen.
 */
public final class CompanionCombat {
    private CompanionCombat() {}

    public enum Stance {
        AGGRESSIVE("aggressiv"), DEFENSIVE("defensiv"), PASSIVE("passiv");

        /** Stabile ID für Menüaktionen ({@code stance_<label>}); angezeigt wird {@code CompanionTexts.stance}. */
        public final String label;
        Stance(String label) { this.label = label; }

        public static Stance byName(String name) {
            for (Stance s : values()) if (s.name().equals(name)) return s;
            return AGGRESSIVE;
        }
    }

    static String type(LivingEntity t) { return BuiltInRegistries.ENTITY_TYPE.getKey(t.getType()).toString(); }

    /** Nie angreifen: Spieler, andere Begleiter, zahme Tiere, Dorfbewohner, Golems. */
    public static boolean isFriend(CompanionEntity e, LivingEntity t) {
        if (t == e || t instanceof Player || t instanceof CompanionEntity) return true;
        if (t instanceof TamableAnimal ta && ta.isTame()) return true;
        if (t instanceof net.minecraft.world.entity.npc.villager.Villager) return true;
        String id = type(t);
        return id.equals("minecraft:iron_golem") || id.equals("minecraft:snow_golem")
                || id.equals("minecraft:wandering_trader") || id.equals("minecraft:allay");
    }

    /** Hat es dieses Wesen gerade auf den Besitzer oder den Begleiter abgesehen? */
    public static boolean threatens(CompanionEntity e, LivingEntity t) {
        if (!(t instanceof Mob m)) return false;
        LivingEntity target = m.getTarget();
        if (target == null) return false;
        Player owner = e.getOwner();
        return target == e || (owner != null && target == owner);
    }

    /** Darf er dieses Wesen von sich aus angreifen (je nach Kampfmodus)? Aufträge (Jagen, Kämpfen) zählen hier nicht. */
    public static boolean wantsToEngage(CompanionEntity e, LivingEntity t) {
        if (t == null || !t.isAlive() || isFriend(e, t)) return false;
        return switch (e.stance) {
            case PASSIVE -> false;
            case DEFENSIVE -> threatens(e, t);
            // neutrale Mobs (Endermen, Zombie-Piglins …) nicht reizen – außer sie greifen schon an
            // Piglins wie neutrale Mobs: ein Schlag macht die ganze Gruppe wütend (beobachtet: an der Lohenfestung
            // von Piglins von 40 auf 4 Leben geprügelt und auf der Flucht abgestürzt)
            case AGGRESSIVE -> !(t instanceof NeutralMob) && !type(t).equals("minecraft:piglin") || threatens(e, t);
        };
    }

    /** Wie gefährlich ist das Wesen gerade? Höher = zuerst bekämpfen. */
    public static double threat(CompanionEntity e, LivingEntity t) {
        Player owner = e.getOwner();
        double s = 40 - Math.sqrt(e.distanceToSqr(t));
        if (t instanceof Mob m) {
            if (owner != null && m.getTarget() == owner) s += 40;
            if (m.getTarget() == e) s += 25;
        }
        if (t instanceof RangedAttackMob) s += 15;
        if (t instanceof Creeper) {
            boolean nearOwner = owner != null && t.distanceToSqr(owner) < 5 * 5;
            s += e.hasBowAndArrows() ? 30 : nearOwner ? 20 : -15;
        }
        if (t.getHealth() <= 4) s += 5;
        return s;
    }

    /** Gefährlichstes Monster im Umkreis, das er angreifen will (sichtbar oder hinter dem Besitzer her). */
    public static LivingEntity bestThreat(CompanionEntity e, double r) {
        List<Mob> mobs = e.level().getEntitiesOfClass(Mob.class, e.getBoundingBox().inflate(r),
                m -> m instanceof Enemy && m.isAlive() && wantsToEngage(e, m) && !e.isIgnoredTarget(m)
                        && (e.hasLineOfSight(m) || threatens(e, m)));
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Mob m : mobs) {
            double s = threat(e, m);
            if (s > bestScore) { bestScore = s; best = m; }
        }
        return best;
    }

    /** Steht der Besitzer in der Schusslinie zum Ziel? Dann nicht schießen. */
    public static boolean ownerInLine(CompanionEntity e, net.minecraft.world.entity.Entity target) {
        Player owner = e.getOwner();
        if (owner == null || owner.level() != e.level()) return false;
        Vec3 from = e.getEyePosition(), to = target.getBoundingBox().getCenter();
        Vec3 seg = to.subtract(from);
        double len2 = seg.lengthSqr();
        if (len2 < 1.0E-6) return false;
        Vec3 o = owner.getBoundingBox().getCenter();
        double t = o.subtract(from).dot(seg) / len2;
        if (t <= 0 || t >= 1) return false;
        Vec3 closest = from.add(seg.scale(t));
        return closest.distanceToSqr(o) < 1.3 * 1.3;
    }

    /** Kampfmodus aus dem Chat: "sei defensiv", "kämpf nicht", "greif alles an" … null = kein Kampfmodus-Befehl. */
    public static Stance stanceFrom(List<String> tok, String text) {
        boolean has = text.contains("kampfmodus") || text.contains("kampf modus") || text.contains("modus");
        if (text.contains("passiv") || text.contains("kaempf nicht") || text.contains("nicht kaempf")
                || text.contains("friedlich") || text.contains("greif nichts an") || text.contains("greif niemanden an")
                || text.contains("greif keinen an")) return Stance.PASSIVE;
        if (text.contains("defensiv") || text.contains("nur verteidig") || text.contains("verteidige dich nur")
                || text.contains("nur wenn du angegriffen")) return Stance.DEFENSIVE;
        if (text.contains("aggressiv") || text.contains("offensiv") || text.contains("greif alles an")
                || text.contains("greif alle an") || (has && text.contains("normal"))) return Stance.AGGRESSIVE;
        return null;
    }

    /**
     * Verteidigt den Besitzer: greift an, was ihn verletzt hat (ownersTargets = false) oder was er selbst angreift
     * (ownersTargets = true, nur im aggressiven Modus) – wie ein Wolf.
     */
    public static final class DefendOwnerGoal extends Goal {
        private final CompanionEntity e;
        private final boolean ownersTargets;
        private int lastStamp = Integer.MIN_VALUE;
        private LivingEntity found;

        public DefendOwnerGoal(CompanionEntity e, boolean ownersTargets) {
            this.e = e;
            this.ownersTargets = ownersTargets;
            setFlags(EnumSet.of(Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            if (e.stance == Stance.PASSIVE || (ownersTargets && e.stance != Stance.AGGRESSIVE)) return false;
            Player owner = e.getOwner();
            if (owner == null || owner.level() != e.level()) return false;
            LivingEntity t = ownersTargets ? owner.getLastHurtMob() : owner.getLastHurtByMob();
            int stamp = ownersTargets ? owner.getLastHurtMobTimestamp() : owner.getLastHurtByMobTimestamp();
            if (t == null || stamp == lastStamp || !t.isAlive() || isFriend(e, t) || e.isIgnoredTarget(t)) return false;
            if (e.distanceToSqr(t) > 32 * 32) return false;
            found = t;
            lastStamp = stamp;
            return true;
        }

        @Override
        public boolean canContinueToUse() { return false; }

        @Override
        public void start() {
            if (e.getTarget() == found) return;
            e.setTarget(found);
            e.logInfo(dev.begleiter.debug.BotDebug.EventKind.DANGER,
                    CompanionTexts.tr(ownersTargets ? "log.combat.assist_owner" : "log.combat.defend_owner", found.getName()));
        }
    }
}
