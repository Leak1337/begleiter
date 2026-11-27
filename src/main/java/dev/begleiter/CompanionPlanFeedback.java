package dev.begleiter;

import java.util.function.Supplier;

import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.ai.AdaptiveAcquisition;
import dev.begleiter.ai.DecisionOutcome;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;

/** Eine begrenzte, nur zur Laufzeit gehaltene Beobachtung einer vollständigen selbstständigen Aufgabenkette. */
public final class CompanionPlanFeedback {
    public enum Status { SUCCESS, PARTIAL, FAILURE, CANCELLED }
    public record Result(Need need, Status status, String target, int durationTicks,
                         int remainingBefore, int remainingAfter, float healthChange,
                         DecisionOutcome.Reason reason, dev.begleiter.ai.CompanionSourceMemory.Key source) {}
    private record Measure(int remaining, int progress) {}
    public record Objective(Supplier<Measure> observe, Measure before) {
        private Objective(Supplier<Measure> observe) { this(observe, observe.get()); }
    }

    private Need need;
    private Objective objective;
    private String target, approach, failedApproach;
    private Object dimension;
    private int started;
    private float health;
    private DecisionOutcome.Reason failure;
    private Result last;
    private boolean managed;
    private dev.begleiter.nav.Goal returnGoal;
    public dev.begleiter.nav.Goal returnGoal() { return active() ? returnGoal : null; }
    private dev.begleiter.ai.CompanionSourceMemory.Key failedSource;
    private final java.util.Set<dev.begleiter.ai.CompanionSourceMemory.Key> confirmedSources = new java.util.LinkedHashSet<>();

    public boolean active() { return objective != null; }
    public Result last() { return last; }

    /** Das ursprüngliche Bedürfnis festhalten, bevor seine Aktion Welt oder Inventar verändert. */
    public static Objective prepare(CompanionEntity e, Need need) {
        return switch (need) {
            case SUPPLY -> null; // Resource selection happens in actSupply; no inventory changes there.
            case SURVIVAL -> survival(e);
            case HUNGER -> e.food.level() <= 12 && e.foodCount() < 4
                    ? deficit(() -> Math.max(0, 4 - e.foodCount()))
                    : CompanionCraft.rawFood(e) >= 3 && CompanionCraft.canSmelt(e)
                        ? deficit(() -> CompanionCraft.rawFood(e))
                        : deficit(() -> Math.max(0, 4 - e.foodCount()));
            case ORDER -> order(e);
            case GEAR -> gear(e);
            case BASE -> base(e);
            case PROGRESS -> progress(e);
            case CARE -> care(e);
        };
    }

    private static Objective deficit(java.util.function.IntSupplier remaining) {
        return new Objective(() -> new Measure(Math.max(0, remaining.getAsInt()), 0));
    }

    private static Objective milestone(java.util.function.BooleanSupplier done,
                                        java.util.function.IntSupplier progress) {
        return new Objective(() -> new Measure(done.getAsBoolean() ? 0 : 1, progress.getAsInt()));
    }

    private static Objective survival(CompanionEntity e) {
        // Unter Beschuss darf der Plan ihn an Ort und Stelle einmauern: geschützt zählt wie das Haus als sicher.
        if (e.isSheltered() || e.inDanger())
            return milestone(() -> e.isSheltered() || e.isInsideHouse() || e.getHealth() >= e.getMaxHealth() * .8f,
                    () -> (int) e.getHealth());
        BlockPos inside = e.houseInside();
        if (inside != null && !e.isInsideHouse() && inside.distSqr(e.blockPosition()) < 96 * 96) {
            // A retreat plan promises shelter, not instant recovery to 40% health.
            return milestone(e::isInsideHouse,
                    () -> -(int) Math.ceil(Math.sqrt(inside.distSqr(e.blockPosition()))));
        }
        return e.getHealth() < e.getMaxHealth() * .4f
                ? deficit(() -> (int) Math.ceil(Math.max(0, e.getMaxHealth() * .4f - e.getHealth()) * 10))
                : deficit(() -> e.isInsideHouse() ? 0 : 1);
    }

    private static Objective base(CompanionEntity e) {
        if (!e.isHouseBuilt()) return milestone(() -> e.isHouseBuilt(), () -> e.build == null ? 0 : e.build.index);
        if (e.getHome() == null) return milestone(() -> e.getHome() != null, () -> 0);
        if (!e.doorClosed && e.houseNeedsDoor()) return milestone(() -> !e.houseNeedsDoor(), () -> 0);
        if (e.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            var care = e.initiative.base;
            if (care.lightingWanted(sl) > 0) return deficit(() -> care.darkSpots(sl, true).size());
            if (care.wantsField(sl))
                return milestone(() -> care.hasField(sl, true), () -> e.build == null ? 0 : e.build.index);
        }
        return milestone(() -> !e.houseNeedsDoor(), () -> 0);
    }

    private static Objective order(CompanionEntity e) {
        if (e.hasScaffold()) return deficit(() -> e.scaffolding.size());
        if (e.pickup.looseItemsNearby()) {
            // Ursprüngliche Items behalten: ein unerreichbarer Drop, der ausgelassen wird, zählt nicht als eingesammelt.
            var drops = e.level().getEntitiesOfClass(ItemEntity.class, e.getBoundingBox().inflate(12),
                    item -> item.isAlive() && e.pickup.collectible(item));
            return deficit(() -> drops.stream().filter(ItemEntity::isAlive)
                    .mapToInt(item -> item.getItem().getCount()).sum());
        }
        return deficit(() -> Math.max(0, e.usedSlots() - 27));
    }

    private static Objective gear(CompanionEntity e) {
        int pick = CompanionCraft.tier(e, "pickaxe");
        if (pick < 2) {
            int wanted = pick == 0 ? 1 : 2;
            return deficit(() -> Math.max(0, wanted - CompanionCraft.tier(e, "pickaxe")));
        }
        if (CompanionNeeds.wornTool(e) != null)
            return milestone(() -> CompanionNeeds.wornTool(e) == null,
                    () -> -CompanionJobs.damaged(e, .08).stream().mapToInt(s -> s.getDamageValue()).sum());
        if (CompanionNeeds.wantsCopper(e)) {
            for (String id : new String[]{"copper_pickaxe", "copper_sword", "copper_axe"}) {
                if (!CompanionCraft.owns(e, id) && !e.craftBlocked(id))
                    return milestone(() -> CompanionCraft.owns(e, id), () -> CompanionCraft.count(e, "copper_ingot"));
            }
        }
        if (pick < 3) return deficit(() -> Math.max(0, 3 - CompanionCraft.tier(e, "pickaxe"))
                + CompanionNeeds.armorIngotsNeeded(e));
        if (CompanionNeeds.armorIngotsNeeded(e) > 0)
            return deficit(() -> CompanionNeeds.armorIngotsNeeded(e));
        return deficit(() -> CompanionNeeds.diamondsNeeded(e));
    }

    private static Objective progress(CompanionEntity e) {
        if (e.getPortalPos() == null)
            return milestone(() -> e.getPortalPos() != null, () -> CompanionCraft.count(e, "obsidian"));
        if (CompanionBrain.needsBlaze(e))
            return deficit(() -> CompanionBrain.rawBlazeTarget(e) - CompanionCraft.count(e, "blaze_rod"));
        if (CompanionBrain.wantsPearlHunt(e))
            return deficit(() -> Math.max(0, CompanionBrain.PEARLS - CompanionBrain.pearlsAndEyes(e)));
        if (e.endPortalPos == null)
            return milestone(() -> e.endPortalPos != null, () -> CompanionCraft.count(e, "ender_eye"));
        if (CompanionBrain.dragonDefeated(e))
            return deficit(() -> CompanionJobs.nextGearPiece(e, 4, 0) == null ? 0 : 1);
        return deficit(() -> (CompanionCraft.owns(e, "bow") ? 0 : 1)
                + Math.max(0, CompanionBrain.ARROWS - CompanionCraft.count(e, "arrow")));
    }

    private static Objective care(CompanionEntity e) {
        if (CompanionNeeds.matureCrops(e) >= CompanionNeeds.HARVEST_MIN) {
            BlockPos center = e.getHome() != null ? e.getHome() : e.getHouseOrigin();
            return deficit(() -> e.level() instanceof ServerLevel sl
                    ? Math.max(0, CompanionJobs.countMature(sl, center, CompanionJobs.FARM_RADIUS)
                        - CompanionNeeds.HARVEST_MIN + 1) : 1);
        }
        if (CompanionJobs.canRepairNow(e, .25))
            return deficit(() -> CompanionJobs.damaged(e, .25).stream().mapToInt(s -> s.getDamageValue()).sum());
        String species = CompanionNeeds.breedable(e);
        BlockPos center = e.getHome();
        if (species == null || center == null || !(e.level() instanceof ServerLevel sl)) return null;
        int babies = (int) CompanionJobs.animals(sl, center, 24, species).stream().filter(a -> a.isBaby()).count();
        return deficit(() -> {
            var animals = CompanionJobs.animals(sl, center, 24, species);
            long newBabies = animals.stream().filter(a -> a.isBaby()).count() - babies;
            long loving = animals.stream().filter(a -> a.isInLove()).count();
            return (int) Math.max(0, 2 - newBabies * 2 - loving);
        });
    }

    private static int stock(CompanionEntity e, String resource) {
        return switch (resource) {
            case "holz" -> CompanionCraft.count(e, "#logs") + CompanionCraft.count(e, "#planks") / 4;
            case "stein" -> CompanionCraft.count(e, "#stone");
            case "kohle" -> CompanionCraft.count(e, "#coals");
            default -> 0;
        };
    }

    public void begin(CompanionEntity e, Need selected, Objective prepared) {
        if (active()) throw new IllegalStateException("Previous autonomous plan has not ended");
        managed = true;
        if (!e.hasTasks()) return; // Absichtliches Warten ist kein gescheiterter Plan.
        if (selected == Need.SUPPLY) {
            if (e.activeSupplyResource == null || e.activeSupplyApproach == null) return; // Exploration.
            String resource = e.activeSupplyResource;
            int wanted = switch (resource) { case "holz" -> 16; case "stein" -> 32; default -> 8; };
            prepared = deficit(() -> Math.max(0, wanted - stock(e, resource)));
        } else if (selected == Need.HUNGER && e.peekTasks(e.taskCount()).stream()
                .allMatch(t -> t.type() == CompanionEntity.TaskType.MOVE_TO || t.type() == CompanionEntity.TaskType.TRAVEL)) {
            return; // Tiere suchen ist Vorbereitung, keine gescheiterte Jagd.
        }
        if (prepared == null) return;
        if (selected == Need.GEAR) {
            // Der Ausrüstungsplan kann mehrere Werkzeuge herstellen; eine neue Spitzhacke allein ist nicht das ganze Ziel.
            var equipment = e.peekTasks(e.taskCount()).stream()
                    .map(t -> t.spec() instanceof TaskSpec.Craft craft ? craft.item() : null)
                    .filter(java.util.Objects::nonNull).distinct().toList();
            Objective primary = prepared;
            prepared = new Objective(() -> {
                Measure m = primary.observe().get();
                int missing = (int) equipment.stream().filter(id -> !CompanionCraft.owns(e, id)).count();
                return new Measure(m.remaining() + missing, m.progress());
            });
        }
        need = selected;
        objective = prepared;
        target = selected == Need.SUPPLY ? e.activeSupplyResource : null;
        approach = selected == Need.SUPPLY ? e.activeSupplyApproach : null;
        dimension = e.level().dimension();
        started = e.tickCount;
        health = e.getHealth();
        returnGoal = e.getHome() == null ? new dev.begleiter.nav.Goal.Block(e.blockPosition())
                : new dev.begleiter.nav.Goal.Near(e.getHome(), 2);
        failure = null;
        failedApproach = null;
        failedSource = null;
        confirmedSources.clear();
    }

    /** Erst lernen, wenn die ganze Kette endet, damit wiederholte Schrittfehler nur einmal zählen. */
    public boolean failed(CompanionEntity e, DecisionOutcome.Reason reason, CompanionEntity.Task task) {
        if (!active()) return managed; // Deliberate exploration/waiting has no failure objective.
        if (failure == null || failure.generic() && reason != null && !reason.generic()) {
            failure = reason;
            failedApproach = AdaptiveAcquisition.failedApproach(task, approach);
            failedSource = CompanionSources.failureSource(e, need, reason, task);
        }
        return true;
    }

    /** Keep confirmation until plan completion so a player cancellation stays neutral. */
    public void sourceSucceeded(CompanionEntity e, CompanionEntity.Task task) {
        if (!active() || need != Need.SUPPLY || task == null || !(task.spec() instanceof TaskSpec.Take take)
                || take.item() == null || confirmedSources.size() >= 32) return;
        var key = CompanionSources.key(e, need, CompanionCraft.item(take.item()), take.pos());
        if (key != null) confirmedSources.add(key);
    }

    public void completeIfIdle(CompanionEntity e) {
        if (!e.hasTasks() && (e.build == null || e.build.paused) && e.getTarget() == null)
            finish(e, false);
    }

    /** cancelled steht nur für Abbruch durch den Spieler, Unterbrechungen und Dimensionswechsel. */
    public void finish(CompanionEntity e, boolean cancelled) {
        managed = false;
        if (!active()) return;
        cancelled |= !dimension.equals(e.level().dimension());
        Measure after = cancelled ? objective.before() : objective.observe().get();
        Measure before = objective.before();
        Status status = cancelled ? Status.CANCELLED
                : after.remaining() == 0 && failure == null ? Status.SUCCESS
                : after.remaining() < before.remaining() || after.progress() > before.progress()
                    ? Status.PARTIAL : Status.FAILURE;
        DecisionOutcome.Reason reason = failure != null ? failure
                : status == Status.FAILURE ? DecisionOutcome.Reason.PLAN_FAILED : null;
        last = new Result(need, status, target, Math.max(0, e.tickCount - started),
                before.remaining(), after.remaining(), e.getHealth() - health, reason, failedSource);
        objective = null; // Exactly one outcome, including reentrant cancellation.
        if (status == Status.SUCCESS) {
            e.experiences.succeeded(need, target);
            if (target != null && approach != null) e.experiences.succeeded(need, target + "_" + approach);
        } else if (status != Status.CANCELLED && failedSource != null) {
            e.experiences.sources.failed(failedSource, reason, e.tickCount);
        } else if (status != Status.CANCELLED) {
            if (status == Status.FAILURE) e.experiences.record(new DecisionOutcome(need, reason, target, e.tickCount));
            String method = failedApproach != null ? failedApproach : status == Status.FAILURE ? approach : null;
            if (target != null && method != null)
                e.experiences.recordTargetOnly(new DecisionOutcome(need, reason, target + "_" + method, e.tickCount));
        }
        if (status != Status.CANCELLED) confirmedSources.forEach(e.experiences.sources::succeeded);
        confirmedSources.clear();
        e.activeSupplyResource = null;
        e.activeSupplyApproach = null;
        e.consoleDebug("Plan outcome: " + last);
    }

    /** Fehlschläge nur aus eigenen Plänen merken; Befehle des Besitzers bringen ihm nie ein schlechtes Ergebnis bei. */
    void reportAutonomousFailure(CompanionEntity e, DecisionOutcome.Reason reason, CompanionEntity.Task failedTask) {
        var goal = e.goal;
        if (goal == null) return;
        if (failed(e, reason, failedTask)) return;
        var source = CompanionSources.failureSource(e, goal, reason, failedTask);
        if (source != null) {
            e.experiences.sources.failed(source, reason, e.tickCount);
            return;
        }
        String target = goal == Need.SUPPLY ? e.activeSupplyResource
                : failedTask == null ? null : failureTarget(failedTask.spec());
        e.experiences.record(new DecisionOutcome(goal, reason, target, e.tickCount));
        if (goal == Need.SUPPLY && target != null && e.activeSupplyApproach != null) {
            // Ein mehrstufiger Plan baut vielleicht erst ab und geht dann zur Kiste, oder craftet erst Werkzeug:
            // bestraft wird der Schritt, der wirklich scheiterte
            String actualMethod = AdaptiveAcquisition.failedApproach(failedTask, e.activeSupplyApproach);
            e.experiences.recordTargetOnly(new DecisionOutcome(goal, reason, target + "_" + actualMethod, e.tickCount));
        }
        e.consoleDebug("Intelligence: " + goal + " failed (" + reason
                + (target == null ? "" : ", " + target) + ")");
    }

    /** Worauf sich ein Fehlschlag bezieht (Ressource, Item, Tierart) – Schlüssel der Erfahrung; null = nichts Benennbares. */
    private static String failureTarget(TaskSpec spec) {
        return switch (spec) {
            case TaskSpec.ResourceGoal g -> g.resource();
            case TaskSpec.Craft c -> c.item();
            case TaskSpec.Smelt s -> s.item();
            case TaskSpec.Station s -> s.item();
            case TaskSpec.Take tk -> tk.item();
            case TaskSpec.Give g -> g.what();
            case TaskSpec.Trade tr -> tr.item();
            case TaskSpec.Fight f -> f.mob();
            case TaskSpec.Breed b -> b.species();
            case TaskSpec.Fetch f -> f.animal();
            default -> null;
        };
    }
}
