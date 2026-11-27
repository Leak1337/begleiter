package dev.begleiter;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.debug.BotDebug.EventKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Baustein Server-Tick: verteilt jeden KI-Tick auf die Bausteine (Wahrnehmung, Überleben, Wächter, Arbeit,
 * Planer) in fester Reihenfolge und holt ihn zum Besitzer zurück, auch über Dimensionen. Nur Laufzeit.
 */
final class CompanionTick {
    private final CompanionEntity e;
    /** Selbstständige Planung wurde wegen eines anderen Begleiters in diesem Tick aufgeschoben. */
    private boolean planDeferred;
    private int crossSince = -1;

    CompanionTick(CompanionEntity e) { this.e = e; }

    /** Gleiche Scanfrequenz, aber verschiedene Phasen für gleichzeitig erzeugte Begleiter. */
    private boolean perceptionTick(int interval) {
        return Math.floorMod((long) e.tickCount + e.getId(), interval) == 0;
    }

    void serverStep(ServerLevel level) {
        CompanionMode mode = e.mode;
        int tickCount = e.tickCount;
        e.flight.tick();
        mode.tick();
        // Bei Arbeit Chunks um sich laden: er darf dafür auch in ungeladene Gegenden gehen
        dev.begleiter.world.CompanionChunkLoader.tick(e.chunkLoader, level, e.blockPosition(), e.needsLoadedChunks(), e.getUUID());

        if (tickCount % 20 == 0) {
            e.gear.autoEquip();
            e.food.secondTick(); // ca. alle 30 s ein Hungerpunkt, bei Bedarf essen
            e.hazards.scan();
        }
        if (tickCount % 100 == 0 && e.getHealth() < e.getMaxHealth() && e.food.level() >= 14) e.heal(1.0f);
        if (tickCount % 5 == 0) e.hazards.tick();
        if (tickCount % 5 == 0) e.potions.tick(); // Feuerresistenz, Heilung, Stärke, langsamer Fall
        // Kreativ-Modus: unverwundbar (damit sehen Mobs ihn auch nicht als Feind und greifen ihn nicht an)
        if (tickCount % 5 == 0 && e.isPermanentlyInvulnerable() != e.creativeHelper()) e.setPermanentlyInvulnerable(e.creativeHelper());
        if (tickCount % 5 == 0) e.fighting.tick();
        if (tickCount % 20 == 0) e.shelter.tick(level);
        if (tickCount % 10 == 0) e.torches.tick(level);
        if (tickCount % 10 == 5) e.initiative.tick(level);
        if (tickCount % 5 == 0 && !e.openedDoors.isEmpty()) CompanionDoors.closeBehind(level, e);
        e.pickup.tick(level);
        e.swim.tick(level);
        e.hazards.sprintTick(level);
        if (tickCount % 20 == 0) e.stations.packUp(level);
        // Hintergrundscans je Entity versetzen; relative Fristen und Überlebenstimer bleiben unverändert.
        if (perceptionTick(5)) e.memory.scan(e, level);
        if (tickCount % 20 == 0) e.gathering.diamondSearchTick();
        if (perceptionTick(2)) e.memory.xrayScan(e, level);
        if (perceptionTick(100)) e.memory.verify(e, level);
        if (perceptionTick(200)) e.memory.checkVillage(e, level);
        if (tickCount % 40 == 0 && !mode.keeper && !e.work.enabled()) e.items.declutter(level);
        // Unterbrochener Bauauftrag (z.B. nach fehlgeschlagener Materialsuche): später weitermachen
        if (tickCount % 20 == 0 && e.build != null && !e.work.manages(CompanionWork.Kind.BUILD)
                && !e.build.paused && !e.hasTasks() && !e.isPaused() && e.getTarget() == null && tickCount >= e.buildRetryAt) {
            e.addTask(Task.build());
        }
        // Gerüst/Pfeiler wieder abbauen, sobald nichts mehr zu tun ist – in jedem Modus
        if (tickCount % 40 == 0 && !e.hasTasks() && e.scaffolding.any() && !mode.staying && e.getTarget() == null && !e.isPaused()) {
            e.addTask(Task.descend());
        }
        if (tickCount % 20 == 0) e.watchdog.progressWatch();
        if (tickCount % 40 == 0) followAcrossDimensions(level);

        // Zu weit vom Besitzer entfernt -> zurückholen
        if (tickCount % 100 == 0 && !e.journey.active() && !e.work.enabled() && !mode.keeper && !mode.staying && mode.duty == null) {
            Player o = e.getOwner();
            // mit Auftrag (z.B. unter Tage graben) darf er weiter weg sein; auf dem Nether-Ausflug ist er allein unterwegs
            double limit = mode.autonomous || e.hasTasks() ? 160.0 : 64.0;
            if (o != null && !e.hasRecipeRequest() && !e.netherTrip && dev.begleiter.nav.FollowRecovery.beyond(e.distanceToSqr(o), limit)) {
                Vec3 safe = CompanionSafeTeleport.nearOwner(e, level, o.position());
                if (safe != null && CompanionSafeTeleport.move(e, level, safe)) {
                    e.planFeedback.finish(e, true);
                    e.queue().clear();
                    e.nav.recoveryEvent(CompanionTexts.tr("nav.recovery.catchup"));
                }
            }
        }

        e.watchdog.targetTick();
        // Wachhund: wenn er lange nichts tut, im Chat sagen warum
        e.watchdog.idleTick(e.hasTasks() || e.getTarget() != null || mode.staying || mode.keeper,
                mode.autonomous && !mode.staying && !mode.keeper);

        try {
            e.work.tick(level);
        } catch (RuntimeException ex) {
            e.work.taskFailed();
            e.logError(CompanionTexts.tr("entity.error.duty", CompanionTexts.literal(ex.toString())));
        }
        if (!e.work.active() && !e.work.manages(CompanionWork.Kind.STOCK)) CompanionStockRoutine.tick(e, level);

        // Lagerverwalter
        if (mode.keeper && mode.home != null && !e.hasTasks() && tickCount % 400 == 0) {
            boolean loose = !level.getEntitiesOfClass(ItemEntity.class, e.getBoundingBox().inflate(16)).isEmpty();
            if (loose) e.addTask(Task.collect(true));
            if (loose || e.items.hasDepositable()) e.addTask(Task.deliver(false));
        }
        // Daueraufgabe (Feld, Tiere, Handel): im Leerlauf am Ort nach Arbeit schauen
        if (mode.duty != null && !e.work.enabled() && !e.isPaused() && !e.hasTasks() && e.getTarget() == null && tickCount >= mode.dutyNextTick) {
            try {
                mode.dutyNextTick = tickCount + CompanionDuties.tick(e, level);
            } catch (RuntimeException ex) {
                e.logError(CompanionTexts.tr("entity.error.duty", CompanionTexts.literal(ex.toString())));
                mode.dutyNextTick = tickCount + 1200;
            }
        }

        e.planFeedback.completeIfIdle(e);
        if (tickCount % 20 == 0) e.journey.review();
        // Dringenderes Bedürfnis (Nacht, wenig Leben, Hunger, Werkzeug kaputt) unterbricht eine Planer-Aufgabe
        if (playingFreely() && tickCount % 40 == 0 && e.getTarget() == null) {
            try { CompanionNeeds.checkInterrupt(e); } catch (RuntimeException ex) { e.logError(CompanionTexts.tr("entity.error.needs", CompanionTexts.literal(ex.toString()))); }
        }
        // Selbstständig spielen, wenn nichts zu tun ist
        if (playingFreely() && !e.hasTasks() && e.getTarget() == null && !e.isRetreating()
                && (tickCount % 10 == 0 || planDeferred) && tickCount >= e.plannerCooldownUntil) {
            // höchstens eine selbstständige Planung je Server-Tick; abgewiesen = nächster Tick mit Vorrang
            planDeferred = !dev.begleiter.ai.PlanGate.tryEnter(level.getServer().getTickCount(), e.getUUID());
            if (!planDeferred) try {
                CompanionBrain.plan(e);
            } catch (RuntimeException ex) {
                e.logError(CompanionTexts.tr("entity.error.planner", CompanionTexts.literal(ex.toString())));
                e.plannerCooldownUntil = e.tickCount + 400;
            }
        }
        e.nav.sampleMotion(); // erst nachdem Schwimmen/Kampf entschieden haben, wer in diesem Tick steuert
    }

    private boolean playingFreely() {
        return e.mode.autonomous && !e.mode.staying && !e.mode.keeper && !e.isPaused();
    }

    /** Besitzer in einer anderen Dimension: zum Portal laufen und durchgehen; klappt das nicht, direkt nachkommen. */
    private void followAcrossDimensions(ServerLevel level) {
        CompanionMode mode = e.mode;
        ServerPlayer o = e.getOwnerAnywhere();
        if (o == null || o.level() == level || e.journey.active() || e.work.enabled() || mode.keeper || mode.staying
                || mode.duty != null || e.netherTrip || e.hasRecipeRequest()) { crossSince = -1; return; }
        if (crossSince < 0) {
            crossSince = e.tickCount;
            e.tell(CompanionTexts.tr("entity.portal.follow"));
        }
        // Netherportal nur zwischen Oberwelt und Nether; ins/aus dem End kommt er direkt nach
        boolean viaNether = level.dimension() == Level.NETHER || o.level().dimension() == Level.NETHER;
        BlockPos portal = viaNether && e.tickCount - crossSince < 1200 ? e.portals.nearest(16) : null;
        if (portal != null) {
            Task t = e.currentTask();
            if (t == null || t.type() != TaskType.MOVE_TO || !portal.equals(t.pos())) {
                e.cancelAll();
                e.addTask(Task.moveTo(portal, 1));
            }
            return;
        }
        if (e.tickCount - crossSince >= 200) {
            ServerLevel destination = o.level();
            Vec3 safe = CompanionSafeTeleport.nearOwner(e, destination, o.position());
            if (safe == null) return;
            var moved = e.teleport(new net.minecraft.world.level.portal.TeleportTransition(destination, safe,
                    Vec3.ZERO, e.getYRot(), e.getXRot(), net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            if (moved instanceof CompanionEntity arrived) {
                CompanionSafeTeleport.resetMotion(arrived);
                arrived.logInfo(EventKind.PORTAL, CompanionTexts.tr("log.portal.direct"));
            }
        }
    }
}
