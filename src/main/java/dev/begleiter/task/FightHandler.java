package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import dev.begleiter.debug.BotDebug.EventKind;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;

/**
 * FIGHT: Mobs einer Art bekämpfen, bis genug Beute da ist (Art, Beute und Menge, optionaler Sammelpunkt wie
 * ein Spawner). Ist keiner da, wartet er am Sammelpunkt bzw. sieht sich um (tries zählt Runden).
 */
final class FightHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Fight fight;
    /** Wartezeit ohne Gegner, bevor er woanders sucht. */
    private int waited;
    private int nextDragonStrike;
    private int nextStrike;
    private int pillars;

    FightHandler(Task task) {
        this.task = task;
        this.fight = (TaskSpec.Fight) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        String id = fight.mob();
        boolean dragon = "minecraft:ender_dragon".equals(id);
        if (dragon && mob.journey.fightingDragon()) return fightDragon(ctx);
        String loot = fight.lootItem();
        if (loot != null && CompanionCraft.count(mob, loot) >= fight.lootCount()) {
            mob.huntTarget = null;
            return Status.DONE;
        }
        Mob h = mob.huntTarget;
        if (h != null && h.isAlive()) {
            mob.setTarget(h);
            // Schwebende Lohe knapp über ihm: wie ein Spieler hochschlagen (die Nahkampf-KI erreicht nur auf
            // Körperhöhe; beobachtet: 32-mal "Lohe nicht erreichbar" direkt unter dem Spawner)
            if (h.getY() > mob.getY() + 1 && mob.getEyePosition().distanceToSqr(h.getX(), h.getY(0.5), h.getZ()) <= 3.6 * 3.6
                    && mob.hasLineOfSight(h) && mob.tickCount >= nextStrike) {
                mob.gear.equipBestWeapon();
                mob.getLookControl().setLookAt(h);
                mob.doHurtTarget(ctx.level(), h);
                mob.swingArm();
                nextStrike = mob.tickCount + 12;
            }
            // Unter dem Spawner-Podest (beobachtet: 3 Blöcke tiefer, jede Lohe "nicht erreichbar"): auf Spawnerhöhe hochbauen
            BlockPos at = fight.pos();
            if (at != null && pillars < 2 && mob.onGround() && mob.hasScaffold() && mob.getBlockY() < at.getY() - 1
                    && h.getY() > mob.getY() + 2 && mob.blockPosition().distToCenterSqr(at.getX() + .5, mob.getY(), at.getZ() + .5) <= 6 * 6) {
                pillars++;
                mob.pushFront(Task.pillar(mob.blockPosition().atY(at.getY())));
                return Status.REPLACED;
            }
            return Status.RUNNING;
        }
        if (h != null) { // erlegt: Beute einsammeln, weiter kämpfen
            mob.huntTarget = null;
            mob.replaceCurrent(List.of(Task.collect(true),
                    new Task(fight)));
            return Status.REPLACED;
        }
        if (dragon && dev.begleiter.CompanionBrain.dragonDefeated(mob)) return Status.DONE;
        Mob next = mob.expedition.nearestOfType(id, dragon ? 200 : 32, fight.pos());
        if (next != null && !dragon && mob.fighting.ignored(next)) next = null;
        if (next != null && !dragon && HuntHandler.walkCloser(ctx, next)) return Status.RUNNING;
        if (next != null) {
            mob.huntTarget = next;
            mob.setTarget(next);
            mob.logInfo(EventKind.DANGER, CompanionTexts.tr("handler.fight.target", next.getName(), next.blockPosition().toShortString()));
            return Status.RUNNING;
        }
        // keiner da: am Sammelpunkt warten bzw. umsehen
        if (fight.pos() != null && !ctx.approach(fight.pos(), 25)) return Status.RUNNING;
        if (++waited < 100) return Status.RUNNING;
        if (task.tries() >= 12) {
            mob.logInfo(CompanionTexts.tr("handler.fight.none", CompanionTexts.animal(id)));
            return Status.FAILED;
        }
        Task ex = fight.pos() == null ? mob.explorePoint() : null;
        List<Task> next2 = new ArrayList<>();
        if (ex != null) next2.add(ex);
        next2.add(task.retry());
        mob.replaceCurrent(next2);
        return Status.REPLACED;
    }

    private Status fightDragon(TaskContext ctx) {
        var mob = ctx.mob();
        var world = ctx.level();
        if (dev.begleiter.CompanionBrain.dragonDefeated(mob)) return Status.DONE;
        var target = mob.expedition.nearestOfType(fight.mob(), 200, null);
        var dragon = target instanceof EnderDragon boss ? boss : null;
        if (dragon != null && !dragon.isAlive()) return Status.RUNNING;
        var cloud = world.getEntitiesOfClass(AreaEffectCloud.class, mob.getBoundingBox().inflate(3),
                effect -> effect.getOwner() instanceof EnderDragon).stream().findFirst().orElse(null);
        if (cloud != null) {
            var away = mob.position().subtract(cloud.position()).multiply(1, 0, 1);
            if (away.lengthSqr() < 0.1) away = new net.minecraft.world.phys.Vec3(1, 0, 0);
            var safe = BlockPos.containing(cloud.position().add(away.normalize().scale(cloud.getRadius() + 4)));
            approachEndSurface(ctx, safe, 2.25);
            return Status.RUNNING;
        }
        if (mob.getHealth() < 12) {
            // Nicht jeden Tick: satt heilt er von selbst, Essen als Heiltrank nur in der Not (beobachtet: 5 Mahlzeiten in 30 s)
            if (mob.food.level() < 18 || mob.getHealth() < 8) mob.food.eatThrottled();
            return Status.RUNNING;
        }
        if (dragon != null) {
            var head = dragon.head;
            double distance = mob.distanceToSqr(head);
            boolean perched = dragon.getPhaseManager().getCurrentPhase().isSitting();
            if (perched && distance <= 4.5 * 4.5 && mob.hasLineOfSight(head)) {
                if (mob.tickCount >= nextDragonStrike) {
                    mob.gear.equipBestWeapon();
                    mob.getLookControl().setLookAt(head);
                    mob.nav.stop();
                    mob.doHurtTarget(world, head);
                    mob.swingArm();
                    nextDragonStrike = mob.tickCount + 20;
                }
                return Status.RUNNING;
            }
            if (!perched && distance <= 48 * 48 && mob.hasLineOfSight(head) && mob.hasBowAndArrows()) {
                mob.nav.stop();
                if (!dev.begleiter.CompanionCombat.ownerInLine(mob, head)) mob.shootArrow(head);
                return Status.RUNNING;
            }
            if (perched && approachEndSurface(ctx, head.blockPosition(), 4)) return Status.RUNNING;
        }
        // Wait within striking distance of the fountain, away from the column the dragon perches on.
        approachEndSurface(ctx, new BlockPos(12, mob.blockPosition().getY(), 4), 16);
        return Status.RUNNING;
    }

    private static boolean approachEndSurface(TaskContext ctx, BlockPos column, double reachSqr) {
        var world = ctx.level();
        if (!world.hasChunkAt(column)) return false;
        int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        var ground = column.atY(y);
        if (y <= world.getMinY() || ctx.mob().hazardAround(world, ground, false)) return false;
        ctx.approach(ground, reachSqr);
        return true;
    }
}
