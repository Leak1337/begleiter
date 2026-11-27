package dev.begleiter;

import java.util.UUID;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.chat.Sentence;
import dev.begleiter.task.TaskSpec;
import dev.begleiter.world.SafeLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.phys.AABB;

/** An explicit Survival objective. Plans are disposable; world progress and the player's goal survive reloads. */
public final class CompanionJourney {
    private enum Goal {
        IRON, DRAGON;
        Component title() { return CompanionTexts.tr("journey.goal." + name().toLowerCase(java.util.Locale.ROOT)); }
    }
    private enum Step {
        READY, FOOD, SHELTER, TOOLS, BASE, ARMOR, PORTAL, NETHER, RETURN, EYES, STRONGHOLD, EQUIP, END, RECOVER, SUPPLY, DONE,
        DIAMOND, BREW;
        Component title() { return CompanionTexts.tr("journey.step." + name().toLowerCase(java.util.Locale.ROOT)); }
    }

    private final CompanionEntity mob;
    private UUID owner;
    private Goal goal;
    private Step step = Step.READY;
    private boolean paused, complete, running, returning;
    private int failures, fortressSearch;
    private int mealTarget = 4;
    /** Nächster Zuchtversuch (Laufzeit; Tiere brauchen 5 min bis zur nächsten Paarung). */
    private long breedRetryAt;
    /** Seit wann er morgens im Haus auf das Abziehen der Monster wartet (-1 = nicht). */
    private long houseWaitSince = -1;
    /** Nächste Ernte am Haus frühestens dann (Laufzeit). */
    private long farmRetryAt;
    private long nightDirtAt = Long.MIN_VALUE;
    private BlockPos breakOutAt;
    private int breakOutTries;
    private long retryAt, foodRetryAt;
    private int foodFailures;
    /** Runden ohne Ertrag (Essen/Werkzeug gescheitert, nichts dazugewonnen); ab 2 zieht er weiträumig weg. */
    private int barren, lastWood = -1, lastMeals = -1, lastPick = -1;
    /** Fehlschläge im Nether auf dem Weg zu den Lohen (Laufzeit), siehe {@link #fail()}. */
    private int netherMisses;
    /** Gescheiterte Runden der Diamantausrüstung (Laufzeit); ab 6 zählt Eisen fürs End, siehe {@link #fail()}. */
    private int diamondMisses;
    /** Fehlschläge hintereinander an (fast) derselben Stelle (Laufzeit); ab 4 gräbt er sich nach oben frei. */
    private int stuckFails;
    private BlockPos lastFailPos;
    /** War es beim letzten review() schon dunkel? Für die einmalige Unterbrechung bei Einbruch der Nacht. */
    private boolean wasDark;
    /** Wann er an der Basis ein Feld angelegt hat und wann er zuletzt zur Ernte heimging (Spielzeit, Laufzeit). */
    private long fieldPlantedAt = -1, lastHarvestTrip = -1;
    private Step failedStep;
    private BlockPos rejectedFortress;
    private BlockPos recovery;
    private UUID recoverySoul;
    private BlockPos returnDestination;
    private String recoveryDimension;
    private long recoverUntil;

    CompanionJourney(CompanionEntity mob) { this.mob = mob; }

    private boolean owned() { return goal != null && owner != null && owner.equals(mob.getOwnerUuid()); }
    public boolean active() { return owned() && !paused && !complete; }
    public boolean running() { return running && active(); }
    /** Aktueller Schritt als Name (Tests). */
    String stepName() { return step.name(); }
    public boolean fightingDragon() {
        return active() && goal == Goal.DRAGON && mob.level().dimension() == Level.END;
    }
    public boolean isReturningTo(BlockPos pos) {
        return running() && returning && pos.equals(returnDestination);
    }

    private BlockPos homeAnchor() { return mob.houseInside() == null ? mob.getHome() : mob.houseInside(); }

    Component interpret(ServerPlayer player, String text) {
        if (player == null || !mob.isOwner(player) || text.length() > 160) return null;
        String command = Sentence.of(text).text();
        return switch (command) {
            case "spiel selbststaendig bis zum enderdrachen", "spiel bis zum enderdrachen",
                    "play survival to the dragon", "play solo to the dragon" -> start(Goal.DRAGON);
            case "spiel selbststaendig bis zur eisenruestung", "spiel bis zur eisenruestung",
                    "play survival to iron armor", "play solo to iron armor" -> start(Goal.IRON);
            case "spielziel", "survival goal" -> status();
            case "spielziel pausieren", "pause survival goal" -> {
                if (!owned()) yield status();
                mob.cancelAll();
                mob.stay();
                yield status();
            }
            case "spielziel weiter", "resume survival goal" -> {
                if (!owned() || complete) yield status();
                mob.cancelAll();
                mob.freeRoam();
                paused = false;
                retryAt = foodRetryAt = 0;
                mob.plannerCooldownUntil = mob.tickCount;
                yield status();
            }
            case "spielziel abbrechen", "clear survival goal" -> {
                if (owned()) {
                    mob.cancelAll();
                    mob.follow();
                    goal = null;
                }
                yield status();
            }
            default -> null;
        };
    }

    private Component start(Goal next) {
        mob.cancelAll();
        mob.freeRoam();
        owner = mob.getOwnerUuid();
        goal = next;
        paused = complete = returning = false;
        retryAt = foodRetryAt = 0;
        failures = foodFailures = fortressSearch = barren = 0;
        mealTarget = 4;
        returnDestination = null;
        rejectedFortress = null;
        failedStep = null;
        step = Step.READY;
        mob.plannerCooldownUntil = mob.tickCount;
        return status();
    }

    Component status() {
        if (!owned()) return CompanionTexts.tr("journey.none");
        var report = CompanionTexts.tr(complete ? "journey.done" : paused ? "journey.paused" : "journey.status",
                goal.title(), step.title());
        if (!paused && !complete && failedStep != null && retryAt > mob.level().getGameTime())
            report.append(" ").append(CompanionTexts.tr("journey.retry", failedStep.title(),
                    (retryAt - mob.level().getGameTime() + 19) / 20));
        return report;
    }

    boolean hasGoal() { return owned(); }
    void suspend() { if (owned() && !complete) paused = true; }
    void interruptPlan() { running = false; }

    void review() {
        if (!running()) return;
        if (!mob.hasTasks()) {
            running = false;
            return;
        }
        // Es wird dunkel: einmal unterbrechen, damit plan() Heimweg oder Nachtlager wählt, statt dass eine lange
        // Aufgabe (Hausbau, Jagd, Holz) einfach in die Nacht hineinläuft. Kein Fehlschlag, unter Tage nicht.
        boolean dark = mob.level().isDarkOutside();
        boolean nightfall = dark && !wasDark;
        wasDark = dark;
        if (nightfall && mob.inOverworld() && step != Step.RETURN && step != Step.SHELTER && step != Step.END) {
            BlockPos at = mob.blockPosition();
            if (mob.level().canSeeSky(at) || at.getY() >= mob.level().getSeaLevel() - 8) {
                mob.clearJourneyTasks();
                running = false;
                return;
            }
        }
        if (step == Step.FOOD || step == Step.RETURN || step == Step.SHELTER) return;
        if (mob.isRetreating() && step != Step.END) { fail(); return; }
        if (step == Step.RECOVER && mob.currentTask().type() == CompanionEntity.TaskType.COLLECT) return;
        boolean hungry = mob.food.level() <= 8 && mob.foodCount() == 0;
        // Wartet die Nahrungssuche ohnehin, kann Abbrechen nichts bringen: den Schritt (z.B. Holzsuche samt Erkunden) laufen lassen
        if (hungry && mob.inOverworld() && mob.level().getGameTime() >= foodRetryAt) fail();
    }

    /** Pausen-Ersatz beim Spielziel: auch wenn gerade kein Plan als laufend markiert ist, scheitern und neu planen. */
    void failAutonomous() {
        if (!active()) return;
        running = true;
        if (mob.build != null && mob.build.paused) mob.build = null; // halb bestätigten Bau nicht ewig festhalten
        fail();
    }

    public void fail() {
        if (!running()) return;
        running = false;
        BlockPos at = mob.blockPosition();
        stuckFails = lastFailPos != null && lastFailPos.distManhattan(at) <= 2 ? stuckFails + 1 : 1;
        lastFailPos = at.immutable();
        // Scheitert alles an derselben Stelle sofort: nicht mehrmals pro Tick neu planen (beobachtet: 500 Fehlschläge in 80 s)
        if (stuckFails >= 3) retryAt = Math.max(retryAt, mob.level().getGameTime() + Math.min(600, 40L * stuckFails));
        if (step == Step.FOOD || step == Step.TOOLS) barren++;
        if (step == Step.BREW)
            for (var kind : CompanionPotions.Kind.values()) mob.craftFailedUntil.put("#brew_" + kind.id(), mob.tickCount + 6000);
        if (step == Step.DIAMOND && ++diamondMisses >= 6) {
            // Keine Diamanten zu finden: einen Spieltag lang mit Eisen weitermachen statt endlos zu graben
            diamondMisses = 0;
            mob.craftFailedUntil.put(CompanionNeeds.DIAMOND_GEAR, mob.tickCount + 24000);
            mob.logInfo(CompanionTexts.tr("log.journey.diamond_pause"));
        }
        if (step == Step.FOOD) {
            // Eigene Wartezeit: sonst setzen sich FOOD und der nächste Schritt gegenseitig zurück (Ping-Pong)
            foodFailures = Math.min(5, foodFailures + 1);
            foodRetryAt = mob.level().getGameTime() + Math.min(1200, 40L << foodFailures);
        } else {
            failedStep = step;
            failures = Math.min(5, failures + 1);
            retryAt = mob.level().getGameTime() + Math.min(1200, 40L << failures);
        }
        if (step == Step.FOOD && mob.currentTask() != null
                && mob.currentTask().spec() instanceof TaskSpec.Farm farm && (farm.forage() || farm.plantBerries())
                && mob.level() instanceof ServerLevel world) mob.noteGatherMiss(world, farm.pos());
        // Ein abgebrochener Kampf oder Weg ist noch kein Grund, die ganze Festung aufzugeben: erst nach drei Fehlschlägen heim
        if (mob.level().dimension() == Level.NETHER && step == Step.NETHER && ++netherMisses < 3) {
            mob.clearJourneyTasks();
            return;
        }
        if (mob.level().dimension() == Level.NETHER && step == Step.NETHER) {
            netherMisses = 0;
            // Festung mit bestätigtem Lohen-Spawner nicht verwerfen: heim, auffüllen, wiederkommen
            if (mob.blazeSpawnerPos == null) {
                rejectedFortress = mob.fortressPos;
                mob.fortressPos = null;
            } else mob.fortressPos = mob.blazeSpawnerPos;
            fortressSearch = Math.min(12, fortressSearch + 1);
            returning = true;
        }
        mob.clearJourneyTasks();
    }

    boolean plan(ServerLevel world) {
        if (!active()) return false;
        running = false;
        if (mob.isRetreating() || mob.isSheltered()) {
            set(Step.SHELTER);
            mob.watchdog.noteWaiting();
            return true;
        }
        if (world.dimension() == Level.END) {
            if (CompanionBrain.dragonDefeated(mob)) {
                returning = true;
                BlockPos exit = findEndExit(world);
                if (exit != null) {
                    portal(world, exit, true, Step.RETURN);
                } else set(Step.RETURN);
            } else if (world.getGameTime() >= retryAt) {
                if (mob.getHealth() < 14 && mob.foodCount() > 0) {
                    mob.food.eat();
                    set(Step.SHELTER);
                } else {
                    set(Step.END);
                    CompanionBrain.endPlan(mob);
                }
            }
            running = mob.hasTasks();
            return true;
        }
        if (world.dimension() == Level.NETHER) {
            if (!returning && recover(world)) return begin();
            nether(world);
            running = mob.hasTasks();
            return true;
        }
        if (!mob.inOverworld()) return true;
        if (goal == Goal.DRAGON && CompanionBrain.dragonDefeated(mob)) {
            finish();
            return true;
        }
        if (goal == Goal.IRON && CompanionCraft.tier(mob, "pickaxe") >= 3 && CompanionNeeds.armorIngotsNeeded(mob) == 0) {
            finish();
            return true;
        }

        if (returning) {
            returning = false;
            mob.netherTrip = false;
        }
        if (stuckFails >= 4 && (digUpward(world) || breakOut(world))) return begin();
        // Hier klappt nichts (beobachtet: im Packeis kein Stein, kein Essen, alles scheitert an derselben Stelle): heim
        BlockPos homeBase = homeAnchor();
        if (stuckFails >= 3 && homeBase != null && homeBase.distSqr(mob.blockPosition()) > 64 * 64 && !world.isDarkOutside()
                && !mob.memory.dangerous(world, homeBase, 3)) {
            queueReturn(homeBase); // stuckFails läuft weiter: scheitert auch das, greift der Durchbruch
            return begin();
        }
        if (stuckFails >= 3 && world.getGameTime() < retryAt) { mob.watchdog.noteWaiting(); return true; }
        // Tagsüber rundum eingemauert ohne laufenden Unterschlupf (Reste nach Neuladen/Tod): sofort freigraben
        if (!world.isDarkOutside() && !mob.isSheltered() && (enclosed(world) || pit(world)) && breakOut(world)) return begin();
        netherMisses = 0;
        if (mob.getHealth() < 14 && mob.foodCount() > 0 && (mob.food.level() < 18 || mob.getHealth() < 8)) mob.food.eat();
        if (recover(world)) return begin();
        boolean huntAtNight = goal == Goal.DRAGON && world.isDarkOutside() && mob.getHealth() >= 14
                && mob.foodCount() >= 2 && (CompanionBrain.wantsPearlHunt(mob)
                || mob.endPortalPos != null && !CompanionCraft.owns(mob, "bow"));
        boolean nearbyEnd = goal == Goal.DRAGON && mob.endPortalPos != null
                && mob.endPortalPos.distSqr(mob.blockPosition()) <= 24 * 24 && CompanionNeeds.readyForDragon(mob);
        if (nightCamp(world, huntAtNight, nearbyEnd)) return true;
        // Morgens im Haus warten, solange Monster direkt draußen sind (Skelette im Schatten, Creeper an der Tür);
        // Hunger schadet ihm nicht. Höchstens 2 Minuten, dann trotzdem los
        if (world.isDarkOutside()) houseWaitSince = -1;
        else if (mob.isInsideHouse() && !world.getEntitiesOfClass(
                net.minecraft.world.entity.monster.Monster.class, mob.getBoundingBox().inflate(14),
                m -> m.isAlive() && m.getType() != net.minecraft.world.entity.EntityTypes.ENDERMAN && !m.isOnFire()).isEmpty()) {
            if (houseWaitSince < 0) houseWaitSince = world.getGameTime();
            if (world.getGameTime() - houseWaitSince < 2400) {
                set(Step.SHELTER);
                mob.watchdog.noteWaiting();
                return true;
            }
        } // Frist nur nachts zurücksetzen: sonst beginnt sie bei jeder kurzen Lücke neu (beobachtet: ganzer Tag im Haus)
        // Morgens zuerst die Türöffnung frei machen, bevor er zum Essen oder Werkzeug raus will
        if (!world.isDarkOutside() && mob.isHouseBuilt() && mob.houseInside() != null
                && mob.houseInside().distSqr(mob.blockPosition()) <= 16 * 16
                && CompanionBrain.nightStep(mob, CompanionCraft.tier(mob, "pickaxe"))) {
            set(Step.BASE);
            return begin();
        }
        // Nachts mit heilem Haus erst heim (Essen sucht er bei Tag), außer er verhungert gleich
        // Hunger schadet ihm nicht (CompanionFood): nachts nie zum Essensuchen raus
        if (world.isDarkOutside() && mob.isHouseBuilt() && (!mob.houseDamaged(world) || mob.isInsideHouse())
                && !huntAtNight && !nearbyEnd) {
            if (CompanionBrain.nightStep(mob, CompanionCraft.tier(mob, "pickaxe"))) {
                set(Step.SHELTER);
                return begin();
            }
            // Schon drin (auch bei angekratztem Haus): bis zum Morgen bleiben statt zum Jagen/Bergen rauszugehen
            // (beobachtet: drei Tode in einer Nacht durch Rausgehen aus dem Haus)
            if (mob.isInsideHouse()) {
                // Loch in der Wand (Creeper, eigenes Durchbrechen): erst flicken, sonst laufen Creeper herein
                if (mob.houseDamaged(world)) {
                    set(Step.BASE);
                    CompanionNeeds.actJourney(mob, CompanionNeeds.Need.BASE);
                    if (mob.hasTasks()) return begin();
                }
                set(Step.SHELTER);
                mob.watchdog.noteWaiting();
                return true;
            }
        }
        // Nachts draußen an der Oberfläche (z.B. vom Creeper aus dem Haus geblasen): heim, wenn nah, sonst einmauern
        // oder eingraben – aber nicht zum Essensuchen weiterlaufen (beobachtet: 70 Blöcke weiter in den Tod gelaufen)
        BlockPos here = mob.blockPosition();
        if (world.isDarkOutside() && !huntAtNight && !nearbyEnd
                && (world.canSeeSky(here) || here.getY() >= world.getSeaLevel() - 8)) {
            set(Step.SHELTER);
            BlockPos inside = mob.houseInside();
            if (inside != null && inside.distSqr(here) <= 64 * 64 && !mob.isInsideHouse()) {
                mob.addTask(Task.moveTo(inside, 1));
                return begin();
            }
            if (CompanionBrain.nightCamp(mob, world)) return begin();
            // Keine Blöcke fürs Lager: ein paar Erdblöcke direkt hier holen, dann einmauern
            // höchstens einmal pro Minute: auf einem Eisberg gibt es keine Erde (beobachtet: 115x "0 sichtbar")
            if (mob.scaffoldCount() < 9 && world.getGameTime() >= nightDirtAt) {
                nightDirtAt = world.getGameTime() + 1200;
                mob.addTask(Task.gather(Resources.DIRT.key(), 9));
                return begin();
            }
            mob.watchdog.noteWaiting();
            return true;
        }
        boolean forage = mob.foodCount() < mealTarget && (mob.food.level() <= 12 || CompanionCraft.tier(mob, "pickaxe") >= 2);
        if (!forage) foodFailures = 0;
        trackYield();
        // Wild wird knapp: wie ein Spieler an der Basis ein Weizenfeld anlegen (Ernte und Brot macht food() schon)
        BlockPos inside = mob.houseInside();
        if (forage && foodFailures >= 2 && mob.isHouseBuilt() && inside != null
                && inside.distSqr(mob.blockPosition()) <= 64 * 64 && mob.initiative.base.wantsField(world)) {
            set(Step.BASE);
            if (mob.initiative.base.startField(world)) {
                fieldPlantedAt = world.getGameTime();
                return begin();
            }
        }
        // Karge Gegend (zweimal nichts gefunden, keine Bäume/Tiere bekannt): weit in eine Richtung ziehen statt im Kreis
        // Nur wenn wirklich etwas fehlt (fast kein Essen bzw. Werkzeug) und nicht endlos weg vom Haus: er zog mit
        // 4 bis 7 Mahlzeiten 1800 Blöcke nach Osten, weil der Vorrat für den Nether noch nicht reichte
        BlockPos base = homeAnchor();
        boolean nearBase = base == null || base.distSqr(mob.blockPosition()) <= 192 * 192;
        // Nicht wegziehen, solange zu Hause reife Ernte steht (beobachtet: 55 reife Weizen ungeerntet, 17-mal heim und
        // gleich wieder weit hinaus)
        if (barren >= 2 && mob.getHealth() >= 9 && (!forage || mob.foodCount() < 3) && nearBase
                && !resourcesNearby(world, forage) && CompanionNeeds.matureCrops(mob) < 3) {
            Task hop = mob.relocatePoint();
            if (hop != null) {
                set(forage ? Step.FOOD : Step.TOOLS);
                mob.addTask(hop);
                return begin();
            }
        }
        if (forage) {
            if (food(world, mealTarget)) return begin();
            if (world.getGameTime() >= foodRetryAt) noteFoodMiss(world); // nichts zu tun gilt wie gescheitert
        }
        // Wenig Leben: drinnen abwarten nur, wenn er dabei auch heilt (Essen dabei oder satt) oder es Nacht ist; sonst
        // verhungert er tagelang mit 5 Leben im Haus, statt die reife Ernte vor der Tür zu holen
        if (mob.getHealth() < 9 && (mob.foodCount() > 0 || mob.food.level() >= 14 || world.isDarkOutside())) {
            set(Step.SHELTER);
            if (!mob.isInsideHouse() && mob.inDanger() && mob.sealIn(world)) return true;
            if (!mob.isInsideHouse() && homeAnchor() != null && safeHome(world, homeAnchor())) queueReturn(homeAnchor());
            else mob.watchdog.noteWaiting();
            return begin();
        }
        if (mob.isHouseBuilt() && !huntAtNight && !nearbyEnd
                && CompanionBrain.nightStep(mob, CompanionCraft.tier(mob, "pickaxe"))) {
            set(Step.SHELTER);
            return begin();
        }
        if (world.getGameTime() < retryAt) return true;
        if (!world.isDarkOutside() && world.getGameTime() >= retryAt && planBerryBed(world)) return begin();
        int pick = CompanionCraft.tier(mob, "pickaxe");
        // Werkzeug fast kaputt: rechtzeitig ersetzen (am Amboss reparieren, wenn einer da ist)
        ItemStack worn = pick >= 2 ? CompanionNeeds.wornTool(mob) : null;
        if (worn != null) {
            set(Step.TOOLS);
            if (CompanionJobs.canRepairNow(mob, 0.08)) mob.addTask(Task.repair(8));
            else CompanionNeeds.replaceTool(mob, worn);
            if (mob.hasTasks()) return begin();
        }
        // Faden, Feuerstein und Federn dabei: gleich Bogen und Pfeile (gegen Skelette, Creeper, Ghasts, Kristalle)
        if (pick >= 3 && CompanionNeeds.armorIngotsNeeded(mob) == 0 && CompanionNeeds.rangedCraftable(mob)
                && CompanionNeeds.craftRangedFromInventory(mob)) {
            set(Step.EQUIP);
            return begin();
        }
        if (pick < 2) {
            starter(pick);
        } else if (mob.usedSlots() >= 32 && mob.getHome() != null && safeHome(world, mob.getHome())) {
            queueReturn(homeAnchor());
            mob.addTask(Task.deliver(true));
        } else if (!mob.isHouseBuilt() || mob.getHome() == null || mob.houseDamaged(world)
                || !mob.doorClosed && mob.houseNeedsDoor() && !mob.craftBlocked("#door")) {
            set(Step.BASE);
            // Haus steht weit weg (z.B. nach einer langen Essenssuche): erst hin, sonst endet der Bauauftrag sofort
            BlockPos houseAt = mob.houseInside();
            if (mob.isHouseBuilt() && houseAt != null && houseAt.distSqr(mob.blockPosition()) > 32 * 32
                    && !mob.memory.dangerous(world, homeAnchor(), 5)) queueReturn(homeAnchor());
            CompanionNeeds.actJourney(mob, CompanionNeeds.Need.BASE);
        } else if (pick < 3 || CompanionNeeds.armorIngotsNeeded(mob) > 0) {
            set(Step.ARMOR);
            CompanionNeeds.journeyIronGear(mob);
        } else if (goal == Goal.IRON) {
            finish();
        } else if (CompanionCraft.tier(mob, "sword") < 3) {
            set(Step.EQUIP);
            CompanionNeeds.journeyIronSword(mob);
        } else if (mob.endPortalPos != null) {
            if (!validateEndPortal(world)) return begin();
            if (wantsDiamondGear()) {
                set(Step.DIAMOND);
                CompanionNeeds.diamondGearStep(mob);
            } else if (!world.isDarkOutside() && CompanionNeeds.potionStep(mob, true,
                    CompanionBrain.deathsIn(mob, Level.NETHER), CompanionBrain.deathsIn(mob, Level.END))) {
                set(Step.BREW);
            } else if (!CompanionNeeds.readyForDragon(mob)) {
                set(Step.EQUIP);
                CompanionNeeds.actJourney(mob, CompanionNeeds.Need.PROGRESS);
            } else if (dragonReady(world)) {
                portal(world, mob.endPortalPos, true, Step.END);
            }
        } else if (pick < 4) {
            set(Step.TOOLS);
            CompanionNeeds.actJourney(mob, CompanionNeeds.Need.GEAR);
        } else if (mob.getPortalPos() == null) {
            set(Step.PORTAL);
            CompanionNeeds.actJourney(mob, CompanionNeeds.Need.PROGRESS);
        } else if (CompanionBrain.needsBlaze(mob) && wantsDiamondGear()
                && CompanionBrain.deathsIn(mob, Level.NETHER) >= 1) {
            // Stirbt er im Nether zu schnell: erst volle Diamantausrüstung, dann der nächste Ausflug
            set(Step.DIAMOND);
            CompanionNeeds.diamondGearStep(mob);
        } else if (CompanionBrain.needsBlaze(mob) && !world.isDarkOutside() && CompanionNeeds.potionStep(mob, false,
                CompanionBrain.deathsIn(mob, Level.NETHER), CompanionBrain.deathsIn(mob, Level.END))) {
            set(Step.BREW); // Feuerresistenz für den Nether, wenn die Zutaten schon da sind (zweiter Ausflug)
        } else if (CompanionBrain.needsBlaze(mob) && mob.scaffoldCount() < 24) {
            // Vorrat für den Abschnitt: Blöcke zum Brücken, Einmauern und gegen Lava im Nether
            set(Step.SUPPLY);
            mob.addTask(supplyTask(40));
        } else if (CompanionBrain.needsBlaze(mob)) {
            set(Step.NETHER);
            BlockPos house = mob.houseInside();
            // Angefangenes Feld (vom Creeper unterbrochen) erst fertig bauen
            if (mob.build != null && !mob.build.paused && !world.isDarkOutside() && house != null
                    && house.distSqr(mob.blockPosition()) <= 64 * 64) {
                set(Step.BASE);
                mob.addTask(Task.build());
                return begin();
            }
            if (mob.foodCount() < netherMeals() && !world.isDarkOutside() && mob.isHouseBuilt() && house != null
                    && house.distSqr(mob.blockPosition()) <= 64 * 64 && mob.initiative.base.wantsMoreField(world)
                    && mob.initiative.base.startField(world)) {
                set(Step.BASE); // mehr Ernte für den Reisevorrat
                fieldPlantedAt = world.getGameTime();
                return begin();
            }
            int meals = netherMeals();
            if (mob.foodCount() < meals && mob.tickCount % 1200 < 20)
                mob.consoleDebug("nether trip waits: meals " + mob.foodCount() + "/" + meals + " hunger " + mob.food.level()
                        + " hp " + (int) mob.getHealth());
            if (readyToLeave(world, meals)) { // Puffer: unterwegs isst er, die Reiseprüfung im Nether darf nicht kippen
                BlockPos entrance = mob.portals.nearest(8);
                if (entrance == null) entrance = CompanionPortals.inside(mob.getPortalPos()).getFirst();
                if (world.hasChunkAt(entrance) && !SafeLevel.is(world, entrance, Blocks.NETHER_PORTAL)) {
                    mob.setPortalPos(null);
                } else {
                    mob.netherTrip = true;
                    mob.netherPortal = null;
                    portal(world, entrance, false, Step.NETHER);
                }
            }
        } else if (wantsDiamondGear()) {
            // Vor Festung und End: volle Diamantausrüstung (Schwert, Spitzhacke, vier Rüstungsteile)
            set(Step.DIAMOND);
            CompanionNeeds.diamondGearStep(mob);
        } else if (!world.isDarkOutside() && CompanionNeeds.potionStep(mob, false,
                CompanionBrain.deathsIn(mob, Level.NETHER), CompanionBrain.deathsIn(mob, Level.END))) {
            set(Step.BREW);
        } else if (CompanionBrain.pearlsAndEyes(mob) < CompanionBrain.PEARLS) {
            set(Step.EYES);
            if (world.isDarkOutside() && readyToLeave(world, 2)) CompanionNeeds.actJourney(mob, CompanionNeeds.Need.PROGRESS);
            else if (!world.isDarkOutside()) seekRenewableFood(world);
        } else {
            set(Step.STRONGHOLD);
            CompanionNeeds.actJourney(mob, CompanionNeeds.Need.PROGRESS);
        }
        if (!mob.hasTasks() && !complete && world.getGameTime() >= retryAt) {
            mob.plannerCooldownUntil = Math.max(mob.plannerCooldownUntil, mob.tickCount + 100);
        }
        return begin();
    }

    private boolean begin() { running = mob.hasTasks(); return true; }

    /**
     * Festgefahren unter der Oberfläche (z.B. im eigenen Schacht unter dem Haus): wie ein Spieler die Decke
     * aufbrechen und hochbauen, statt Heimweg und Jagd im Wechsel scheitern zu lassen. Nicht unter Flüssigkeiten.
     */
    private boolean digUpward(ServerLevel world) {
        BlockPos at = mob.blockPosition();
        // Nur wirklich unter Tage (beobachtet: auf Meereshöhe im Wasser/unter Laub 3x "hochgraben" ohne Erfolg)
        if (world.canSeeSky(at) || CompanionCraft.tier(mob, "pickaxe") < 1 || mob.isInWater()
                || at.getY() >= world.getSeaLevel() - 3) return false;
        for (int k = 2; k <= 4; k++) {
            BlockPos p = at.above(k);
            if (!world.getFluidState(p).isEmpty() || !world.getFluidState(p.above()).isEmpty()) return false;
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL)
                if (!world.getFluidState(p.relative(d)).isEmpty()) return false;
        }
        stuckFails = 0;
        mob.logInfo(CompanionTexts.tr("log.journey.dig_up", at.toShortString()));
        for (int k = 2; k <= 4; k++)
            if (!world.getBlockState(at.above(k)).isAir()) mob.addTask(Task.breakAt(at.above(k), null));
        mob.addTask(Task.pillar(at.above(2)));
        return true;
    }

    /**
     * Festgefahren an der Oberfläche (beobachtet: nach dem Tod in einer 1-Block-Notunterkunft bzw. im kaputten Haus, jeder
     * Weg "unerreichbar"): wie ein Spieler eine Wand aufschlagen, notfalls mit der Hand. Bevorzugt die Seite, hinter der
     * Luft ist; nicht neben Flüssigkeiten.
     */
    private boolean breakOut(ServerLevel world) {
        BlockPos at = mob.blockPosition();
        // Am eigenen Haus nicht Wände oder die Tür einschlagen (er brach so seine Birkentür auf); die zugemauerte
        // Türöffnung macht nightStep morgens frei
        BlockPos home = mob.houseInside();
        boolean atHouse = home != null && home.distSqr(at) <= 6 * 6;
        // Nicht endlos an derselben Stelle (beobachtet: im Packeis 61 Durchbrüche, die alle am Wasser daneben scheiterten)
        breakOutTries = at.equals(breakOutAt) ? breakOutTries + 1 : 1;
        breakOutAt = at.immutable();
        if (breakOutTries > 3) {
            if (breakOutTries == 4) mob.problem(CompanionTexts.tr("log.journey.break_out_failed"));
            retryAt = world.getGameTime() + 400;
            return false;
        }
        int surface = Integer.MIN_VALUE; // höchste Oberfläche ringsum (im Schacht selbst liegt sie am Boden)
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos n = at.relative(d);
            surface = Math.max(surface, world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, n.getX(), n.getZ()));
        }
        if (!atHouse && mob.scaffoldCount() == 0 && surface > at.getY() + 1) {
            // Offener Schacht ohne Blöcke zum Hochbauen (beobachtet: 4 tief im Packeis, überall Wassereinschlüsse): eine
            // Stufe in die Wand schlagen und hinaufsteigen, Wasser im Eis oberhalb des Meeresspiegels ist harmlos
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos step = at.relative(d), s1 = step.above(), s2 = step.above(2);
                if (!blocks(world, step) || lavaNear(world, s1) || lavaNear(world, s2)) continue;
                if (blocks(world, s1) && !breakable(world, s1) || blocks(world, s2) && !breakable(world, s2)) continue;
                if (blocks(world, at.above(2)) && !breakable(world, at.above(2))) continue;
                stuckFails = 0;
                mob.logInfo(CompanionTexts.tr("log.journey.dig_up", at.toShortString()));
                if (blocks(world, at.above(2))) mob.worldBreak(world, at.above(2), true);
                if (blocks(world, s2)) mob.worldBreak(world, s2, true);
                if (blocks(world, s1)) mob.worldBreak(world, s1, true);
                mob.addTask(Task.moveTo(s1, 0));
                return true;
            }
        }
        net.minecraft.core.Direction best = null;
        int bestScore = -1;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos feet = at.relative(d), head = feet.above();
            if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(head).isEmpty()
                    || !world.getFluidState(feet.relative(d)).isEmpty()) continue;
            boolean blocked = blocks(world, feet) || blocks(world, head);
            if (!blocked) continue;
            if (!breakable(world, feet) || !breakable(world, head) || wet(world, feet) || wet(world, head)) continue;
            if (CompanionDoors.isDoor(world, feet) || CompanionDoors.isDoor(world, head)) continue;
            // Am Haus nur eigene Müll-Blöcke (Erde/Bruchstein eines alten Notunterschlupfs), keine Hauswände
            if (atHouse && (!junkOrAir(world, feet) || !junkOrAir(world, head))) continue;
            int score = (world.getBlockState(feet.relative(d)).isAir() ? 2 : 0)
                    + (world.getBlockState(head.relative(d)).isAir() ? 1 : 0)
                    + (blocks(world, feet.below()) ? 1 : 0);
            if (score > bestScore) { bestScore = score; best = d; }
        }
        if (best == null) {
            // Seitlich nur Wasser dahinter: nach oben durchbrechen und hochbauen
            BlockPos up = at.above(2);
            // Letzter Versuch: die Decke trotz Wasser daneben aufbrechen und hochschwimmen (Packeis auf Meereshöhe;
            // nur Wasser, keine Lava)
            if (breakOutTries == 3 && !atHouse && blocks(world, up) && breakable(world, up) && !lavaNear(world, up)) {
                stuckFails = 0;
                mob.logInfo(CompanionTexts.tr("log.journey.dig_up", at.toShortString()));
                mob.worldBreak(world, up, true);
                return true;
            }
            if (blocks(world, up) && breakable(world, up) && !wet(world, up) && !atHouse) {
                stuckFails = 0;
                mob.logInfo(CompanionTexts.tr("log.journey.dig_up", at.toShortString()));
                mob.addTask(Task.breakAt(up, null));
                mob.addTask(Task.pillar(up));
                return true;
            }
            return false;
        }
        stuckFails = 0;
        BlockPos feet = at.relative(best), head = feet.above();
        mob.logInfo(CompanionTexts.tr("log.journey.break_out", CompanionTexts.tr("direction." + best.getName())));
        if (blocks(world, head)) mob.addTask(Task.breakAt(head, null));
        if (blocks(world, feet)) mob.addTask(Task.breakAt(feet, null));
        BlockPos beyond = feet.relative(best);
        boolean beyondOk = !atHouse || junkOrAir(world, beyond) && junkOrAir(world, beyond.above());
        if (beyondOk && blocks(world, beyond.above()) && breakable(world, beyond.above())) mob.addTask(Task.breakAt(beyond.above(), null));
        if (beyondOk && blocks(world, beyond) && breakable(world, beyond)) mob.addTask(Task.breakAt(beyond, null));
        return true;
    }

    /** Unten in einem schmalen Schacht zum Himmel: ringsum Wand (oder Wasser) in Fuß- oder Kopfhöhe, kein Blockvorrat. */
    private boolean pit(ServerLevel world) {
        BlockPos at = mob.blockPosition();
        if (!world.canSeeSky(at.above()) || mob.scaffoldCount() > 0 || mob.isInWater()) return false;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos f = at.relative(d), hd = f.above();
            boolean closed = blocks(world, f) && (blocks(world, hd) || blocks(world, hd.above()))
                    || !world.getFluidState(f).isEmpty() || !world.getFluidState(hd).isEmpty();
            if (!closed) return false;
        }
        return blocks(world, at.below());
    }

    /** Rundum zu (Füße, Kopf, Decke): ein Nachtlager oder eine Notunterkunft. */
    private boolean enclosed(ServerLevel world) {
        BlockPos at = mob.blockPosition();
        if (!blocks(world, at.above(2))) return false;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL)
            if (!blocks(world, at.relative(d)) || !blocks(world, at.above().relative(d))) return false;
        return true;
    }

    /** Flüssigkeit direkt daneben (der Abbau würde Wasser/Lava hereinlassen; der Abbau-Auftrag lehnt das ab). */
    private static boolean wet(ServerLevel world, BlockPos p) {
        for (var d : net.minecraft.core.Direction.values())
            if (!world.getFluidState(p.relative(d)).isEmpty()) return true;
        return false;
    }

    private static boolean lavaNear(ServerLevel world, BlockPos p) {
        for (var d : net.minecraft.core.Direction.values())
            if (world.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return true;
        return false;
    }

    private static boolean junkOrAir(ServerLevel world, BlockPos p) {
        var st = world.getBlockState(p);
        return !blocks(world, p) || CompanionEntity.isJunk(st.getBlock().asItem()) || st.is(Blocks.GRASS_BLOCK);
    }

    private static boolean blocks(ServerLevel world, BlockPos p) {
        return !world.getBlockState(p).getCollisionShape(world, p).isEmpty();
    }

    private static boolean breakable(ServerLevel world, BlockPos p) {
        var st = world.getBlockState(p);
        return st.getDestroySpeed(world, p) >= 0 && st.getDestroySpeed(world, p) < 30 && st.getFluidState().isEmpty();
    }

    /** Volle Diamantausrüstung fehlt noch und die Suche ist nicht gerade wegen wiederholter Fehlschläge pausiert. */
    private boolean wantsDiamondGear() {
        return !CompanionNeeds.fullDiamondGear(mob) && !mob.craftBlocked(CompanionNeeds.DIAMOND_GEAR);
    }

    /**
     * Nachts ohne heiles Haus in der Nähe: wie ein Spieler an Ort und Stelle einmauern und bis zum Morgen warten,
     * statt durch die Dunkelheit zu laufen oder zu jagen. Unter Tage arbeitet er wie bisher weiter.
     */
    private boolean nightCamp(ServerLevel world, boolean huntAtNight, boolean nearbyEnd) {
        if (huntAtNight || nearbyEnd || !CompanionBrain.needsNightCamp(mob, world)) return false;
        // Schon eingemauert (z.B. nach Neuladen, beobachtet: ging mitten in der Nacht hungrig raus und starb): drin bleiben
        if (enclosed(world)) {
            set(Step.SHELTER);
            mob.watchdog.noteWaiting();
            return true;
        }
        if (!CompanionBrain.nightCamp(mob, world)) return false;
        set(Step.SHELTER);
        return begin();
    }

    /**
     * Mahlzeiten für den Netherbesuch: Puffer über mealTarget und, wenn die Festung schon bekannt ist, der Vorrat,
     * den die Reiseprüfung für den Weg vom Portal dorthin verlangt (sonst kehrt er drüben gleich wieder um).
     */
    private int netherMeals() {
        // Nicht mealTarget: das bleibt nach einem Ausflug zur 840 Blöcke entfernten Festung hoch (beobachtet: 9 Brote
        // reichten nie, "Lohenruten holen" stand still); der Bedarf für die aktuelle Festung kommt unten dazu
        int need = 6;
        BlockPos portal = mob.getPortalPos();
        if (mob.fortressPos == null && portal != null && rejectedFortress == null)
            mob.fortressPos = mob.expedition.fortressFromOverworld(portal);
        if (mob.fortressPos != null && portal != null) {
            var portalInNether = new BlockPos(portal.getX() / 8, mob.fortressPos.getY(), portal.getZ() / 8);
            need = Math.max(need, CompanionTripPreparation.foodReserveFor(Math.sqrt(mob.fortressPos.distSqr(portalInNether))) + 2);
        }
        return Math.min(need, 14);
    }

    private void noteFoodMiss(ServerLevel world) {
        foodFailures = Math.min(5, foodFailures + 1);
        foodRetryAt = world.getGameTime() + Math.min(1200, 40L << foodFailures);
        barren++;
    }

    /** Neuer Ertrag (Holz, Essen, bessere Spitzhacke) beendet das Wegziehen. */
    private void trackYield() {
        int wood = CompanionCraft.count(mob, "#logs") * 4 + CompanionCraft.count(mob, "#planks");
        int meals = mob.foodCount(), pick = CompanionCraft.tier(mob, "pickaxe");
        if (wood > lastWood && lastWood >= 0 || meals > lastMeals && lastMeals >= 0 || pick > lastPick && lastPick >= 0) barren = 0;
        lastWood = wood;
        lastMeals = meals;
        lastPick = pick;
    }

    /** Lohnt es sich zu bleiben? Wer Essen sucht, braucht Tiere; Bäume allein machen nicht satt. */
    private boolean resourcesNearby(ServerLevel world, boolean needFood) {
        if (mob.hasFoodAnimalNearby()) return true;
        return !needFood && mob.memory.nearest(CompanionMemory.Kind.TREE, null, world, mob.blockPosition(), 48) != null;
    }

    /** Runden ohne Ertrag (Tests). */
    int barrenRounds() { return barren; }

    private void starter(int pick) {
        set(Step.TOOLS);
        if (pick == 0) {
            if (CompanionCraft.count(mob, "#stone") >= 3) {
                mob.addTask(Task.craft("stone_pickaxe", 1));
                return;
            }
            int wood = CompanionCraft.count(mob, "#planks") + 4 * CompanionCraft.count(mob, "#logs");
            if (wood < 9) mob.addTask(Task.gather(Resources.WOOD.key(), (9 - wood + 3) / 4));
            mob.addTask(Task.craft("wooden_pickaxe", 1));
        } else {
            if (CompanionCraft.count(mob, "stick") < 2 && CompanionCraft.count(mob, "#planks") < 2
                    && CompanionCraft.count(mob, "#logs") == 0) mob.addTask(Task.gather(Resources.WOOD.key(), 1));
            int stone = CompanionCraft.count(mob, "#stone");
            if (stone < 3) mob.addTask(Task.gather(Resources.STONE.key(), 3 - stone));
            mob.addTask(Task.craft("stone_pickaxe", 1));
        }
    }

    private boolean food(ServerLevel world, int target) {
        if (world.getGameTime() < foodRetryAt) return false;
        set(Step.FOOD);
        // Ohne Schwert nicht auf Nahrungssuche: erst eins bauen (Stein, sonst Holz)
        if (CompanionCraft.tier(mob, "sword") == 0) {
            String sword = CompanionCraft.count(mob, "#stone") >= 2 ? "stone_sword"
                    : CompanionCraft.count(mob, "#planks") + 4 * CompanionCraft.count(mob, "#logs") >= 3 ? "wooden_sword" : null;
            if (sword != null && !mob.craftBlocked(sword)) {
                mob.addTask(Task.craft(sword, 1));
                return true;
            }
        }
        // Weizen aus der eigenen Kiste zu Brot machen (beobachtet: 21 Weizen in der Kiste, er hungerte daneben)
        if (CompanionCraft.count(mob, "wheat") < 3 && mob.getHome() != null
                && mob.getHome().distSqr(mob.blockPosition()) <= 24 * 24 && mob.hasFreeSlot() && !mob.craftBlocked("#take_wheat")) {
            BlockPos chest = mob.findContainerWith(Items.WHEAT, 24);
            var stored = chest == null ? null : mob.orderStorage(world, chest);
            if (stored != null && stored.countItem(Items.WHEAT) >= 3) {
                mob.craftFailedUntil.put("#take_wheat", mob.tickCount + 2400); // höchstens alle 2 min
                mob.addTask(Task.take(chest, "wheat", Math.min(27, stored.countItem(Items.WHEAT) / 3 * 3)));
                return true;
            }
        }
        if (CompanionCraft.count(mob, "wheat") >= 3 && mob.findStation("crafting_table", 24) != null) {
            mob.addTask(Task.craft("bread", Math.min(4, CompanionCraft.count(mob, "wheat") / 3)));
            return true;
        }
        BlockPos home = mob.getHome();
        if (CompanionTripPreparation.resupplyMeals(mob, world, target)) return true;
        if (home != null && safeHome(world, home) && mob.orderStorage(world, home) != null
                && mob.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(home)) > 20.25
                && homeContainsFood(world, home)) {
            queueReturn(home);
            return true;
        }
        if (CompanionCraft.rawFood(mob) > 0 && CompanionCraft.canSmelt(mob) && mob.findStation("furnace", 24) != null) {
            mob.addTask(Task.smeltAnything());
            return true;
        }
        // Rohes Essen dabei, aber kein Ofen in der Nähe: wie ein Spieler unterwegs einen aufstellen (gebraten zählt
        // es mehr als doppelt; er aß jeden Fisch roh und blieb bei Hunger 0)
        if (CompanionCraft.rawFood(mob) >= 1 && CompanionCraft.canSmelt(mob) && !world.isDarkOutside()
                && (CompanionCraft.owns(mob, "furnace") || CompanionCraft.count(mob, "#stone") >= 8)) {
            mob.addTask(Task.station("furnace", true));
            mob.addTask(Task.smeltAnything());
            return true;
        }
        if (seekRenewableFood(world)) return true;
        // Ein Feld wächst an der Basis: von unterwegs zur Ernte heimgehen (dort erntet seekRenewableFood)
        if (home != null && fieldPlantedAt >= 0 && world.getGameTime() - fieldPlantedAt >= 12000
                && (lastHarvestTrip < 0 || world.getGameTime() - lastHarvestTrip >= 6000)
                && home.distSqr(mob.blockPosition()) > 24 * 24 && home.distSqr(mob.blockPosition()) <= 160 * 160
                && !mob.memory.dangerous(world, home, 5)) { // aus der Ferne ist der Heim-Chunk meist nicht geladen
            lastHarvestTrip = world.getGameTime();
            queueReturn(home);
            set(Step.FOOD);
            return true;
        }
        // Wie ein Spieler eine Herde am Haus halten: immer ein Paar stehen lassen und mit Weizen/Karotten/Samen
        // nachzüchten (beobachtet: Tiere ums Haus ausgerottet, Jagd 100–300 Blöcke weit, Tode in der Dämmerung)
        mob.herdKeeping = true;
        String species = CompanionNeeds.breedable(mob);
        if (species != null && world.getGameTime() >= breedRetryAt) {
            breedRetryAt = world.getGameTime() + 6000;
            mob.addTask(Task.breed(species, 2));
            return true;
        }
        // Angeschlagen: lieber in der Nähe angeln als quer durchs Gelände jagen
        if (mob.getHealth() < 14 && (CompanionFishing.rodSlot(mob) >= 0 || CompanionCraft.count(mob, "string") >= 2)) {
            mob.addTask(Task.fish(Math.max(3, target - mob.foodCount())));
            return true;
        }
        if (mob.hasFoodAnimalNearby()) {
            // Nach dem Tod ohne Schwert: erst eins aus dem Bruchstein machen, nicht mit Fäusten durch die Gegend
            if (CompanionCraft.tier(mob, "sword") == 0 && CompanionCraft.count(mob, "#stone") >= 2)
                mob.addTask(Task.craft("stone_sword", 1));
            mob.addTask(Task.hunt(target));
            return true;
        }
        // Smaragde dabei und ein Dorf in Reichweite: wie ein Spieler beim Bauern Brot kaufen
        if (CompanionCraft.count(mob, "emerald") >= 1 && !mob.craftBlocked("#trade_bread")
                && mob.memory.nearest(CompanionMemory.Kind.VILLAGE, null, world, mob.blockPosition(), 160) != null) {
            mob.craftFailedUntil.put("#trade_bread", mob.tickCount + 6000);
            mob.addTask(Task.trade("bread", Math.max(3, target - mob.foodCount())));
            return true;
        }
        // Kein Wild: angeln, wenn er eine Angel hat oder bauen kann (die Aufgabe sucht selbst ein Ufer in der Nähe)
        if (CompanionFishing.rodSlot(mob) >= 0 || CompanionCraft.count(mob, "string") >= 2) {
            mob.addTask(Task.fish(Math.max(3, target - mob.foodCount())));
            return true;
        }
        // Angeschlagen und weit weg: heim zu Feld und Herde, statt untätig herumzustehen (beobachtet: 240 Blöcke vor der
        // Haustür mit 10 Leben und Hunger 0 "untätig")
        BlockPos homeAt = homeAnchor();
        // Auch gesund, aber ohne jedes Essen (beobachtet: 20 Minuten bei Hunger 0 und vollem Leben am Meer "untätig")
        if ((mob.getHealth() < 20 || mob.foodCount() == 0) && homeAt != null && homeAt.distSqr(mob.blockPosition()) > 64 * 64
                && !mob.memory.dangerous(world, homeAt, 3) && !world.isDarkOutside()) {
            queueReturn(homeAt);
            set(Step.FOOD);
            return true;
        }
        // Angeschlagen (Hunger hält ihn ohne Essen nicht am Leben, heilt aber nicht): nicht weit in fremdes Gelände
        if (mob.food.level() <= 12 && mob.getHealth() >= 20) {
            var explore = mob.explorePoint();
            if (explore != null) {
                mob.addTask(explore);
                return true;
            }
        }
        return false;
    }

    private boolean homeContainsFood(ServerLevel world, BlockPos home) {
        var storage = mob.orderStorage(world, home);
        if (storage == null) return false;
        for (int i = 0; i < storage.getContainerSize(); i++) {
            var stack = storage.getItem(i);
            if (stack.has(net.minecraft.core.component.DataComponents.FOOD)
                    && !stack.is(Items.GOLDEN_APPLE) && !stack.is(Items.ENCHANTED_GOLDEN_APPLE)
                    && storage.countItem(stack.getItem()) > mob.stockReserveAt(world, home, stack.getItem())) return true;
        }
        return false;
    }

    private boolean seekRenewableFood(ServerLevel world) {
        BlockPos center = mob.blockPosition();
        BlockPos[] best = {null};
        dev.begleiter.world.SectionScanner.scanBox(world, center.offset(-16, -4, -16), center.offset(16, 4, 16),
                CompanionJourney::ripeBerries, (x, y, z, state) -> {
                    var p = new BlockPos(x, y, z);
                    if ((best[0] == null || p.distSqr(center) < best[0].distSqr(center))
                            && !mob.gatherMissed(world, p) && !mob.memory.dangerous(world, p, 4)) best[0] = p;
                    return true;
                });
        if (best[0] != null) {
            set(Step.FOOD);
            mob.addTask(new Task(new TaskSpec.Farm(best[0], true)));
            return true;
        }
        BlockPos home = mob.getHome();
        if (home != null && home.distSqr(center) <= 24 * 24 && !world.isDarkOutside()
                && world.getGameTime() >= farmRetryAt && CompanionNeeds.matureCrops(mob) >= 3) {
            // Nicht alle paar Sekunden neu (beobachtet: unerreichbares Feld, Ernte-Schleife alle 6 s)
            farmRetryAt = world.getGameTime() + 2400;
            set(Step.FOOD);
            BlockPos field = fieldCenter(world, home);
            // Eigene Werkbank/Ofen vor dem Feldtor: erst abbauen (er sperrte sich so von der reifen Ernte aus)
            BlockPos blocker = stationBlockingGate(world, field);
            if (blocker != null) mob.addTask(Task.breakAt(blocker, null));
            mob.addTask(Task.farm(field));
            return true;
        }
        return false;
    }

    /**
     * Mitte der reifen Ernte im Umkreis von 24 Blöcken ums Zuhause (beobachtet: das Feld lag 17 Blöcke vor der Tür, die
     * Ernte um die Kiste herum sah nur die erste Reihe). Ohne reife Pflanzen das Zuhause selbst.
     */
    private static BlockPos fieldCenter(ServerLevel world, BlockPos home) {
        long sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(home.offset(-24, -4, -24), home.offset(24, 4, 24))) {
            if (!world.hasChunkAt(p) || !CompanionJobs.isMatureCrop(world.getBlockState(p))) continue;
            sx += p.getX(); sy += p.getY(); sz += p.getZ(); n++;
        }
        return n == 0 ? home : new BlockPos((int) (sx / n), (int) (sy / n), (int) (sz / n));
    }

    private static BlockPos stationBlockingGate(ServerLevel world, BlockPos field) {
        for (BlockPos p : BlockPos.betweenClosed(field.offset(-10, -2, -10), field.offset(10, 2, 10))) {
            if (!world.hasChunkAt(p) || !(world.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)) continue;
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos q = p.relative(d);
                var b = world.getBlockState(q).getBlock();
                if (b == Blocks.CRAFTING_TABLE || b instanceof net.minecraft.world.level.block.AbstractFurnaceBlock) return q.immutable();
            }
        }
        return null;
    }

    private boolean planBerryBed(ServerLevel world) {
        var home = mob.getHome();
        if (home == null || !mob.isHouseBuilt() || home.distSqr(mob.blockPosition()) > 12 * 12
                || mob.foodCount() < mealTarget || CompanionCraft.count(mob, "sweet_berries") < 3) return false;
        var bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState();
        int planted = 0;
        BlockPos candidate = null;
        for (var cursor : BlockPos.betweenClosed(home.offset(-8, -1, -8), home.offset(8, 1, 8))) {
            if (!world.hasChunkAt(cursor)) continue;
            var state = world.getBlockState(cursor);
            if (state.is(Blocks.SWEET_BERRY_BUSH) && ++planted >= 3) return false;
            if (state.isAir() && world.canSeeSky(cursor) && bush.canSurvive(world, cursor)
                    && CompanionZones.allows(mob, CompanionZones.Kind.FIELDS, cursor)
                    && !mob.gatherMissed(world, cursor) && !mob.memory.dangerous(world, cursor, 4)
                    && cursor.distSqr(home) > 9 && cursor.distSqr(mob.blockPosition()) > 4
                    && (candidate == null || cursor.distSqr(mob.blockPosition()) < candidate.distSqr(mob.blockPosition())))
                candidate = cursor.immutable();
        }
        if (candidate == null) return false;
        set(Step.FOOD);
        mob.addTask(new Task(new TaskSpec.Farm(candidate, false, true)));
        return true;
    }

    public static boolean ripeBerries(net.minecraft.world.level.block.state.BlockState state) {
        return state != null && state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(SweetBerryBushBlock.AGE) >= 2;
    }

    private boolean readyToLeave(ServerLevel world, int food) {
        mob.food.eatBeforeTrip();
        if (mob.getHealth() < 14 || mob.food.level() < 12 || mob.foodCount() < food) {
            if (!food(world, food) && mob.foodCount() < food && world.getGameTime() >= foodRetryAt) {
                Task search = mob.explorePoint();
                if (search != null) mob.addTask(search);
                else noteFoodMiss(world); // nichts gefunden: Pause wachsen lassen statt jede Runde leer zu planen
            }
            // Nichts zu tun und weit weg: heim zu Feld und Herde statt draußen zu warten (beobachtet: 300 Blöcke vor der
            // Tür "untätig" und dabei den Vorrat aufgegessen)
            BlockPos homeAt = homeAnchor();
            if (!mob.hasTasks() && homeAt != null && homeAt.distSqr(mob.blockPosition()) > 64 * 64
                    && !world.isDarkOutside() && !mob.memory.dangerous(world, homeAt, 3)) queueReturn(homeAt);
            return false;
        }
        return true;
    }

    private boolean dragonReady(ServerLevel world) {
        if (mob.usedSlots() >= 35 && homeAnchor() != null && mob.getHome() != null && safeHome(world, homeAnchor())) {
            queueReturn(homeAnchor());
            mob.addTask(Task.deliver(true));
            return false;
        }
        if (mob.scaffoldCount() < 32) {
            // Blöcke fürs End: Brücken zur Insel, Einmauern bei wenig Leben, Türme zu den Kristallen
            set(Step.SUPPLY);
            mob.addTask(supplyTask(48));
            return false;
        }
        return readyToLeave(world, Math.max(6, mealTarget));
    }

    /** Blockvorrat: Bruchstein, nach zwei Fehlschlägen (kein Stein in der Gegend) Erde. */
    private Task supplyTask(int n) {
        boolean stoneFailed = failedStep == Step.SUPPLY && failures >= 2;
        return Task.gather((stoneFailed ? Resources.DIRT : Resources.STONE).key(), n);
    }

    private boolean validateEndPortal(ServerLevel world) {
        if (!world.hasChunkAt(mob.endPortalPos) || SafeLevel.is(world, mob.endPortalPos, Blocks.END_PORTAL)) return true;
        mob.endPortalPos = null;
        set(Step.STRONGHOLD);
        mob.addTask(Task.stronghold());
        return false;
    }

    private void nether(ServerLevel world) {
        if (mob.netherPortal == null) mob.netherPortal = mob.portals.nearest(16);
        if (returning || !CompanionBrain.needsBlaze(mob) || mob.foodCount() < 2 || mob.getHealth() < 12) {
            if (world.getGameTime() < retryAt && failedStep == Step.RETURN) return;
            returning = true;
            set(Step.RETURN);
            if (mob.netherPortal != null && (SafeLevel.is(world, mob.netherPortal, Blocks.NETHER_PORTAL)
                    || !world.hasChunkAt(mob.netherPortal))) portal(world, mob.netherPortal, false, Step.RETURN);
            return;
        }
        if (world.getGameTime() < retryAt) return;
        if (coverPortalLava(world)) {
            set(Step.NETHER);
            return;
        }
        set(Step.NETHER);
        mob.netherTrip = true;
        if (mob.blazeSpawnerPos != null && (mob.fortressPos == null || !mob.fortressPos.closerThan(mob.blazeSpawnerPos, 128)))
            mob.fortressPos = mob.blazeSpawnerPos; // bekannte Lohen gehen vor jeder neuen Festung
        if (mob.fortressPos == null && rejectedFortress != null) {
            BlockPos candidate = null;
            // Mehrere Richtungen gleich durchprobieren statt für jede einen eigenen Netherbesuch zu verbrauchen
            for (int tries = 0; tries < 6 && candidate == null && fortressSearch < 12; tries++) {
                candidate = mob.expedition.alternateFortress(rejectedFortress, fortressSearch);
                if (candidate == null) fortressSearch++;
            }
            // Keine andere Festung: die abgelehnte noch einmal versuchen statt alle paar Sekunden durchs Portal hin und
            // zurück zu pendeln
            mob.fortressPos = candidate != null ? candidate : rejectedFortress;
            if (candidate == null) {
                rejectedFortress = null;
                fortressSearch = 0;
            }
        }
        CompanionBrain.netherPlan(mob);
        if (!mob.netherTrip) returning = true;
    }

    /** Ankunftsportale, an denen er die Lava schon abgedeckt hat (Laufzeit). */
    private final java.util.Set<BlockPos> coveredPortals = new java.util.HashSet<>();

    /**
     * Lava direkt am Ankunftsportal mit Blöcken abdecken, wie ein Spieler es tut: sonst fängt er bei jeder Ankunft
     * Feuer, und Weglaufen vor dem Feuer reißt ihn in tiefere Lava (Läufe 8 und 21).
     */
    private boolean coverPortalLava(ServerLevel world) {
        BlockPos portal = mob.netherPortal;
        if (portal == null || !coveredPortals.add(portal.immutable()) || mob.scaffoldItem() == null) return false;
        var block = net.minecraft.world.level.block.Block.byItem(mob.scaffoldItem());
        int queued = 0;
        for (BlockPos p : BlockPos.betweenClosed(portal.offset(-4, -2, -4), portal.offset(4, 1, 4))) {
            if (queued >= Math.min(16, mob.scaffoldCount())) break;
            if (!world.hasChunkAt(p) || !world.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)) continue;
            mob.addTask(Task.place(p.immutable(), block));
            queued++;
        }
        if (queued > 0) mob.logInfo(CompanionTexts.tr("log.journey.cover_lava", queued));
        return queued > 0;
    }

    private void portal(ServerLevel world, BlockPos target, boolean end, Step phase) {
        set(phase);
        if (phase == Step.RETURN) returnDestination = target;
        if (target.distSqr(mob.blockPosition()) > 24 * 24) mob.addTask(Task.travel(target));
        mob.addTask(new Task(new TaskSpec.MoveTo(target, 1, false, end ? "minecraft:end_portal" : "minecraft:nether_portal")));
    }

    private BlockPos findEndExit(ServerLevel world) {
        BlockPos center = mob.blockPosition();
        BlockPos[] best = {null};
        dev.begleiter.world.SectionScanner.scanBox(world, center.offset(-96, -32, -96), center.offset(96, 32, 96),
                state -> state.is(Blocks.END_PORTAL), (x, y, z, state) -> {
                    var p = new BlockPos(x, y, z);
                    if (best[0] == null || p.distSqr(center) < best[0].distSqr(center)) best[0] = p;
                    return true;
                });
        return best[0];
    }

    private boolean safeHome(ServerLevel world, BlockPos home) {
        return world.hasChunkAt(home) && !mob.memory.dangerous(world, home, 5);
    }

    private void queueReturn(BlockPos home) {
        returning = true;
        returnDestination = home;
        set(Step.RETURN);
        if (home.distSqr(mob.blockPosition()) > 24 * 24) mob.addTask(Task.travel(home));
        mob.addTask(Task.moveTo(home, 9));
    }

    public boolean handleTravelBlock(BlockPos target, CompanionTripPreparation.Check readiness) {
        // Beim Spielziel gibt es niemanden, der "@ weiter" sagt: nie pausieren, sondern neu planen
        if (!active()) return false;
        running = true;
        if (step == Step.RECOVER) {
            // Bergen lohnt den langen, unsicheren Weg nicht (beobachtet: 270 Zeilen Reise-Abbruch in einer Minute,
            // Bergen und Essen im Wechsel): die Sachen aufgeben
            recovery = null;
            running = false;
            mob.clearJourneyTasks();
            return true;
        }
        if (readiness.reason() == dev.begleiter.ai.DecisionOutcome.Reason.MISSING_INPUT)
            mealTarget = Math.max(mealTarget, CompanionTripPreparation.foodReserve(mob, target));
        if (target.equals(mob.fortressPos) && mob.level().dimension() == Level.NETHER) {
            if (readiness.reason() == dev.begleiter.ai.DecisionOutcome.Reason.MISSING_INPUT) {
                // Zu wenig Essen für den Weg: heim und Vorrat holen, die Festung aber behalten (er verwarf sie)
                returning = true;
                running = false;
                mob.clearJourneyTasks();
                return true;
            }
            fail();
            return true;
        }
        if (readiness.reason() == dev.begleiter.ai.DecisionOutcome.Reason.MISSING_INPUT) set(Step.FOOD);
        fail();
        return true;
    }

    private void set(Step next) {
        if (next == Step.SHELTER && step != Step.SHELTER && mob.level() instanceof ServerLevel sl && !sl.isDarkOutside()) {
            var caller = Thread.currentThread().getStackTrace();
            mob.consoleDebug("shelter by day from line " + (caller.length > 2 ? caller[2].getLineNumber() : -1)
                    + " hp=" + (int) mob.getHealth() + " hunger=" + mob.food.level());
        }
        // FOOD schiebt sich zwischen die Schritte; das darf den Backoff des gescheiterten Schritts nicht zurücksetzen
        if (next != step && next != Step.FOOD && failedStep != next && mob.level().getGameTime() >= retryAt) failures = 0;
        step = next;
        mob.setStage(CompanionTexts.tr("journey.now", next.title()));
    }

    private void finish() {
        set(Step.DONE);
        complete = true;
        running = false;
        returning = false;
        mob.tell(status());
        // Continue in ordinary Survival mode, which already maintains the base and seeks supplies.
        mob.plannerCooldownUntil = mob.tickCount + 100;
    }

    void rememberDeath(ServerLevel world, BlockPos pos) {
        if (!owned() || complete) return;
        // Beim Bergen gestorben: dort lauert, was ihn schon einmal getötet hat. Nicht ohne Ausrüstung zurück in die
        // Todesfalle (beobachtet: viermal hintereinander in derselben Höhle), lieber neu ausrüsten.
        if (step == Step.RECOVER && recovery != null) {
            recovery = null;
            mob.memory.remember(CompanionMemory.Kind.DANGER, "recovery", world, pos);
            return;
        }
        recovery = pos.immutable();
        recoverySoul = mob.soulId();
        recoveryDimension = CompanionMemory.dim(world);
        // Items verschwinden nach 5 Minuten geladener Zeit; ungeladen altern sie nicht, daher etwas länger merken
        recoverUntil = world.getGameTime() + 12000;
    }

    String recoveryTag() { return "begleiter-recovery-" + (recoverySoul == null ? mob.soulId() : recoverySoul); }

    boolean recoveryItem(ItemEntity item) {
        return !running() || step != Step.RECOVER || item.entityTags().contains(recoveryTag());
    }

    private boolean recover(ServerLevel world) {
        if (recovery == null) return false;
        if (world.getGameTime() >= recoverUntil) { recovery = null; return false; }
        // An der Oberfläche braucht er zum Bergen keine Spitzhacke, unter Tage mindestens eine aus Holz
        boolean deep = recovery.getY() < world.getSeaLevel() - 8;
        if (!CompanionMemory.dim(world).equals(recoveryDimension)
                || recovery.distSqr(mob.blockPosition()) > 320 * 320 || mob.getHealth() < 14
                || deep && CompanionCraft.tier(mob, "pickaxe") < 1 || mob.foodCount() == 0 && mob.food.level() < 12
                ) return false;
        // Nachts ohne Rüstung und Schwert gar nicht an der Oberfläche raus (beobachtet: zweimal in 22 s vom selben
        // Skelett an der Fundstelle erschossen, obwohl sie nur 42 Blöcke vom Haus lag)
        boolean armed = CompanionJobs.pieceTier(mob, "chestplate") >= 3 && CompanionCraft.tier(mob, "sword") >= 2;
        if (world.isDarkOutside() && !deep && !armed) return false;
        boolean loaded = world.hasChunkAt(recovery);
        if (loaded && (mob.memory.knownHazard(world, recovery, 8) || mob.hazardAround(world, recovery, false))) return false;
        // Monster an der Fundstelle (Skelette schießen von weit her): ohne Rüstung warten, mit Rüstung und Schwert hin
        if (loaded && !armed && !world.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                new AABB(recovery).inflate(24), m -> m.isAlive()).isEmpty()) return false;
        if (loaded) {
            var ownedDrops = world.getEntitiesOfClass(ItemEntity.class, new AABB(recovery).inflate(6),
                    item -> item.isAlive() && item.entityTags().contains(recoveryTag()));
            if (ownedDrops.isEmpty()) { recovery = null; return false; }
        }
        // Ungeladen altern die Items nicht: hingehen und nachsehen (höchstens dreimal, siehe fail())
        if (world.getGameTime() < retryAt && failedStep == Step.RECOVER) return false;
        if (failedStep == Step.RECOVER && failures >= 3) { recovery = null; return false; }
        set(Step.RECOVER);
        if (recovery.distSqr(mob.blockPosition()) > 24 * 24) mob.addTask(Task.travel(recovery));
        mob.addTask(Task.moveTo(recovery, 9));
        mob.addTask(Task.collect(true));
        return true;
    }

    CompoundTag save(long now) {
        var tag = new CompoundTag();
        if (!owned()) return tag;
        tag.store("Owner", UUIDUtil.CODEC, owner);
        tag.putString("Goal", goal.name());
        tag.putString("Step", step.name());
        tag.putBoolean("Paused", paused);
        tag.putBoolean("Complete", complete);
        tag.putBoolean("Returning", returning);
        tag.putInt("Failures", failures);
        tag.putInt("FortressSearch", fortressSearch);
        tag.putInt("MealTarget", mealTarget);
        if (rejectedFortress != null) tag.store("RejectedFortress", BlockPos.CODEC, rejectedFortress);
        if (failedStep != null) tag.putString("FailedStep", failedStep.name());
        tag.putLong("Retry", Math.clamp(retryAt - now, 0, 1200));
        tag.putInt("FoodFailures", foodFailures);
        if (fieldPlantedAt >= 0) tag.putLong("FieldPlantedAgo", Math.max(0, now - fieldPlantedAt));
        tag.putLong("FoodRetry", Math.clamp(foodRetryAt - now, 0, 1200));
        if (recovery != null && recoverUntil > now) {
            tag.store("Recovery", BlockPos.CODEC, recovery);
            if (recoverySoul != null) tag.store("RecoverySoul", UUIDUtil.CODEC, recoverySoul);
            tag.putString("RecoveryDimension", recoveryDimension);
            tag.putLong("RecoverFor", Math.clamp(recoverUntil - now, 0, 12000));
        }
        return tag;
    }

    void load(CompoundTag tag, long now) {
        owner = tag.read("Owner", UUIDUtil.CODEC).orElse(null);
        goal = enumValue(Goal.class, tag.getStringOr("Goal", ""), null);
        step = enumValue(Step.class, tag.getStringOr("Step", ""), Step.READY);
        running = false;
        paused = tag.getBooleanOr("Paused", false);
        complete = tag.getBooleanOr("Complete", false);
        returning = tag.getBooleanOr("Returning", false);
        failures = Math.clamp(tag.getIntOr("Failures", 0), 0, 5);
        fortressSearch = Math.clamp(tag.getIntOr("FortressSearch", 0), 0, 12);
        mealTarget = Math.clamp(tag.getIntOr("MealTarget", 4), 4, 12);
        returnDestination = null;
        rejectedFortress = tag.read("RejectedFortress", BlockPos.CODEC).orElse(null);
        failedStep = enumValue(Step.class, tag.getStringOr("FailedStep", ""), null);
        retryAt = now + Math.clamp(tag.getLongOr("Retry", 0), 0, 1200);
        foodFailures = Math.clamp(tag.getIntOr("FoodFailures", 0), 0, 5);
        long plantedAgo = tag.getLongOr("FieldPlantedAgo", -1);
        fieldPlantedAt = plantedAgo < 0 ? -1 : now - plantedAgo;
        foodRetryAt = now + Math.clamp(tag.getLongOr("FoodRetry", 0), 0, 1200);
        recovery = tag.read("Recovery", BlockPos.CODEC).orElse(null);
        recoverySoul = tag.read("RecoverySoul", UUIDUtil.CODEC).orElse(null);
        recoveryDimension = tag.getStringOr("RecoveryDimension", "");
        recoverUntil = now + Math.clamp(tag.getLongOr("RecoverFor", 0), 0, 12000);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String text, E fallback) {
        try { return Enum.valueOf(type, text); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }
}
