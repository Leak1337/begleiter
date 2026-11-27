package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionGathering;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionMemory;
import dev.begleiter.CompanionPlanner;
import dev.begleiter.CompanionPrerequisites;
import dev.begleiter.Resources;
import dev.begleiter.nav.Goal;
import dev.begleiter.nav.Navigator;

/**
 * MINE: unter Tage eine Menge einer Ressource suchen (tries = Runden ohne Fund). Erze in Sicht werden
 * als Gang angesteuert, sonst gräbt die Wegfindung ein Stück Richtung Erz-Höhe (Treppe/Stollen ergeben sich). Jede Runde
 * ersetzt sich durch eine neue MINE-Aufgabe; Erz-Gang und Grabziel gehören nur zu dieser Runde.
 */
final class MineHandler implements TaskHandler {
    private static final double REACH_SQR = 4.5 * 4.5;

    private final Task task;
    private TaskSpec.Mine spec;
    /** Erze, die gerade gemeinsam angesteuert werden (A* nimmt das günstigste), und das Ziel dazu. */
    private List<BlockPos> oreRun;
    private Goal oreGoal;
    /** Grabziel ohne Erz in Sicht. */
    private BlockPos digTarget;
    /** Geschützter Modus (eigene Beschaffung): nachgewiesener Hin- und Rückweg zum nächsten Erz des Gangs. */
    private dev.begleiter.nav.RoundTripCheck oreTrip;
    private BlockPos oreTripTarget;
    private boolean oreTripInstalled;

    MineHandler(Task task) {
        this.task = task;
        this.spec = (TaskSpec.Mine) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        MiningKnowledge mining = ctx.mining();
        Resources.Res res = Resources.byKey(spec.resource());
        if (res == null || spec.count() <= 0) return Status.DONE;
        if (spec.targetTotal() == 0)
            spec = spec.withTarget(Resources.count(mob, res) + spec.count());
        int remaining = Math.max(0, spec.targetTotal() - Resources.count(mob, res));
        if (remaining == 0) return Status.DONE;
        spec = spec.withCount(remaining);
        if (task.tries() >= 40) {
            mob.problem(CompanionTexts.tr("handler.mine.not_found", CompanionTexts.resource(res.key())));
            ctx.failChain();
            return Status.REPLACED;
        }
        // Spitzhacke unterwegs kaputt und die übrige reicht nicht für dieses Erz: erst (hier unten) eine passende bauen,
        // GATHER erledigt das stufenweise – statt mit Holz-/Steinhacke weiterzugraben und am Erz zu scheitern
        if (CompanionCraft.tier(mob, "pickaxe") < CompanionPrerequisites.requiredTier(res.key())) {
            mob.logInfo(CompanionTexts.tr("handler.mine.no_tool", CompanionTexts.resource(res.key())));
            mob.replaceCurrent(List.of(new Task(new TaskSpec.Gather(spec.resource(), spec.count(), spec.targetTotal()))));
            return Status.REPLACED;
        }
        if (sparePickaxe(mob)) return Status.REPLACED;
        // laufender Erz-Gang bzw. Grabstück wird erst zu Ende gebracht
        if (oreRun != null) return continueOreRun(ctx, res);
        if (digTarget != null) return continueDig(ctx, res);
        // Notfall: lange nichts gefunden -> auch Erz tief unter Wasser (Tauchen) nehmen
        boolean emergency = task.tries() >= 10;
        List<Task> ores = mob.buildGather(res.block(), spec.count(), false, emergency);
        // Gerade als unerreichbar gesperrte Erze nicht über den Umweg als Einzel-Abbau wieder einreihen (sonst
        // 40 Runden am selben Erz, statt woanders zu graben)
        ores.removeIf(o -> o.pos() != null && mining.blacklisted(o.pos()));
        if (!ores.isEmpty() && startOreRun(mob, sl, mining, ores)) return continueOreRun(ctx, res);
        if (emergency && !ores.isEmpty() && CompanionGathering.deepWater(sl, ores.getFirst().pos()))
            mob.logInfo(CompanionTexts.tr("handler.mine.dive", CompanionTexts.resource(res.key())));
        // Geschützter Modus: auch gespürte Erze nur über den geprüften Erz-Gang, nie als ungeprüfte Einzel-Abbauten
        if (ctx.guardedSupply()) {
            if (ores.isEmpty()) {
                var vein = mining.knownVein(sl, res, emergency);
                if (!vein.isEmpty() && startOreRunAt(mob, sl, mining, vein)) return continueOreRun(ctx, res);
            } else {
                mob.replaceCurrent(List.of(new Task(spec, task.tries() + 1))); // alle mit Gefahr daneben: neu suchen
                return Status.REPLACED;
            }
        }
        // Beim Graben gespürtes Erz in der Nähe: direkt hingraben statt weiter zu suchen
        if (ores.isEmpty() && mining.mineKnownOre(sl, res, spec.count(),
                new Task(spec, task.tries() + 1), emergency))
            return Status.REPLACED;
        if (!ores.isEmpty()) {
            List<Task> next = new ArrayList<>(ores);
            next.add(new Task(spec, task.tries() + 1));
            mob.replaceCurrent(next);
            return Status.REPLACED;
        }
        if (res == Resources.OBSIDIAN) {
            Status s = obsidian(ctx, mob, sl);
            if (s != null) return s;
        }
        int targetY = Math.max(mob.level().getMinY() + 6, CompanionGathering.oreLevel(res.key()));
        boolean overworld = mob.level().dimension() == Level.OVERWORLD;
        // Vor dem Abstieg Holz einpacken (Werkbank + Stöcke für neue Werkzeuge unten): sonst baut er sich für einen
        // einzigen Stamm 100 Blöcke wieder hoch
        if (task.tries() == 0 && targetY <= 0 && mob.blockPosition().getY() > 40 && mob.level().canSeeSky(mob.blockPosition().above())) {
            int wood = CompanionCraft.count(mob, "#logs") * 4 + CompanionCraft.count(mob, "#planks") + CompanionCraft.count(mob, "stick") / 2;
            if (wood < 12 && !mining.woodForMineTried) {
                mining.woodForMineTried = true;
                mob.logInfo(CompanionTexts.tr("handler.mine.pack_wood"));
                mob.replaceCurrent(List.of(Task.gather(Resources.WOOD.key(), 3),
                        new Task(spec)));
                return Status.REPLACED;
            }
        }
        if (task.tries() > 0) mining.woodForMineTried = false;
        // Von der Mine-Reise zurück: angekommen? Zweimal nicht hingekommen -> Mine vergessen
        if (mining.mineTrip) {
            mining.mineTrip = false;
            BlockPos m = mob.mine();
            if (m != null && m.distSqr(mob.blockPosition()) > 24 * 24) {
                if (++mob.mineTripFails >= 2) {
                    mob.logInfo(CompanionTexts.tr("handler.mine.unreachable", m.toShortString()));
                    mob.setMine(null);
                    mob.mineTripFails = 0;
                }
            } else mob.mineTripFails = 0;
        }
        // Wie ein Spieler: in die eigene Mine zurück (durch den alten Schacht) statt jedes Mal neu von oben zu graben
        BlockPos mine = overworld ? mob.mine() : null;
        // lohnt sich, wenn die Mine deutlich tiefer liegt als er gerade ist (spart den Abstieg)
        if (task.tries() == 0 && mine != null && mob.blockPosition().getY() > targetY + 8 && mine.getY() < mob.getBlockY() - 16
                && mine.getY() >= targetY - 4) {
            double hd = Math.sqrt(Math.pow(mine.getX() - mob.getX(), 2) + Math.pow(mine.getZ() - mob.getZ(), 2));
            if (hd < 256) {
                mob.logInfo(CompanionTexts.tr("handler.mine.return", mine.toShortString(), (int) hd));
                List<Task> trip = new ArrayList<>();
                if (hd > 20) trip.add(Task.travel(mine));
                trip.add(Task.moveTo(mine, 3));
                trip.add(new Task(spec, 1));
                mob.mineDir = null;
                mining.mineTrip = true;
                mob.replaceCurrent(trip);
                return Status.REPLACED;
            }
        }
        // Nicht direkt an der Basis (Haus, Lager) nach unten graben: erst ein Stück weggehen
        if (task.tries() == 0) {
            BlockPos base = mob.getHome() != null ? mob.getHome() : mob.getHouseOrigin();
            if (base != null && base.distSqr(mob.blockPosition()) < 10 * 10) {
                Vec3 away = mob.position().subtract(Vec3.atCenterOf(base)).multiply(1, 0, 1);
                if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
                away = away.normalize().scale(14);
                BlockPos spot = BlockPos.containing(mob.getX() + away.x, mob.getY(), mob.getZ() + away.z);
                // auf den Boden an dieser Stelle (Krater, Hang): sonst ist das Ziel Luft und "unerreichbar"
                if (sl.hasChunkAt(spot))
                    spot = new BlockPos(spot.getX(), sl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            spot.getX(), spot.getZ()), spot.getZ());
                mob.logInfo(CompanionTexts.tr("handler.mine.leave_base"));
                mob.mineDir = Direction.getApproximateNearest(away.x, 0, away.z);
                mob.replaceCurrent(List.of(Task.moveTo(spot, 9),
                        new Task(spec, 1)));
                return Status.REPLACED;
            }
        }
        if (mob.mineDir == null) mob.mineDir = mob.getDirection();
        // Nicht in Richtung bekannter Gefahren (Todesort, Explosion, Lava-Unfall) graben
        for (int turn = 0; turn < 3 && mob.memory.dangerous(sl, mob.blockPosition().relative(mob.mineDir, 8), 6); turn++)
            mob.mineDir = mob.mineDir.getClockWise();
        // Nicht ins Wasser hinein graben (beobachtet: schaute vom Angeln aufs Meer, der Abstieg endete sofort, ohne Meldung)
        for (int turn = 0; turn < 3 && waterAhead(sl, mob.blockPosition(), mob.mineDir); turn++)
            mob.mineDir = mob.mineDir.getClockWise();
        int y = mob.blockPosition().getY();
        // tiefster Punkt seines Schachts (ab y=16, nur bei tiefen Erzen) ist seine Mine; neue erst, wenn die alte weit weg ist
        if (overworld && targetY <= 0 && y <= 16 && !mob.level().canSeeSky(mob.blockPosition())) {
            BlockPos old = mob.mine();
            boolean far = old == null || Math.abs(old.getX() - mob.getBlockX()) + Math.abs(old.getZ() - mob.getBlockZ()) > 96;
            if (far || y < old.getY() || (y == old.getY() && y <= targetY + 1)) {
                if (far) mob.logInfo(CompanionTexts.tr("handler.mine.established", mob.blockPosition().toShortString()));
                mob.setMine(mob.blockPosition());
            }
        }
        // Auf Erztiefe: in Richtung noch nicht abgesuchter Felder graben statt durch schon abgesuchtes Gebiet
        if (overworld && Math.abs(y - targetY) <= 3) {
            Direction chosen = mob.initiative.mining.direction(mob, sl, mob.mineDir);
            if (chosen != mob.mineDir) mob.logInfo(CompanionTexts.tr("handler.mine.new_area", CompanionTexts.literal(chosen.getSerializedName())));
            mob.mineDir = chosen;
        }
        // Die Wegfindung gräbt selbst: bis zu 8 vor und 8 tiefer (Treppe ergibt sich), auf Zielhöhe geradeaus
        int down = Math.max(0, Math.min(8, y - targetY));
        digTarget = mob.blockPosition().relative(mob.mineDir, 8).below(down).immutable();
        return continueDig(ctx, res);
    }

    /**
     * Spitzhacke fast hin und keine Ersatz-Spitzhacke dabei: jetzt eine neue bauen, solange die alte noch hält (sonst
     * bricht sie kurz vor den Diamanten und er muss ganz nach oben). true = Warteschlange ersetzt.
     */
    private boolean sparePickaxe(CompanionEntity mob) {
        ItemStack pick = CompanionCraft.bestTool(mob, "pickaxe");
        if (pick.isEmpty() || !pick.isDamageableItem() || pick.getMaxDamage() - pick.getDamageValue() >= pick.getMaxDamage() / 5) return false;
        String pid = dev.begleiter.ItemIds.of(pick);
        if (CompanionCraft.countAll(mob, pid) >= 2 || mob.craftBlocked(pid)) return false;
        // aus dem, was er dabei hat (Stöcke besorgt CRAFT notfalls selbst); sonst kümmert sich später der Planer
        List<Task> pre = new ArrayList<>();
        int ingots = CompanionCraft.count(mob, "iron_ingot"), raw = CompanionCraft.count(mob, "raw_iron");
        boolean ok = switch (pid) {
            case "minecraft:iron_pickaxe" -> {
                if (ingots < 3 && ingots + raw >= 3) pre.add(Task.smelt("iron_ingot", 3));
                yield ingots + raw >= 3;
            }
            case "minecraft:diamond_pickaxe" -> CompanionCraft.count(mob, "diamond") >= 3;
            case "minecraft:copper_pickaxe" -> CompanionCraft.count(mob, "copper_ingot") >= 3;
            case "minecraft:stone_pickaxe" -> CompanionCraft.count(mob, "#stone") >= 3;
            default -> false;
        };
        if (!ok) return false;
        mob.logInfo(CompanionTexts.tr("handler.mine.spare", pick.getMaxDamage() - pick.getDamageValue(), CompanionTexts.item(pid)));
        mob.craftFailedUntil.put(pid, mob.tickCount + 2400); // nicht in einer Schleife immer wieder planen
        pre.add(Task.craft(pid, 1));
        pre.add(new Task(spec, task.tries()));
        mob.replaceCurrent(pre);
        return true;
    }

    /** Obsidian: Wasser auf Lava gießen, dann abbauen. null = keine Lava bekannt, normal weitergraben. */
    private Status obsidian(TaskContext ctx, CompanionEntity mob, ServerLevel sl) {
        Task again = new Task(spec, task.tries() + 1);
        // Ohne Wassereimer kein Obsidian: erst Wasser holen (Eimer dabei) statt sinnlos zu graben
        if (mob.findItem(Items.WATER_BUCKET) < 0) {
            if (task.tries() >= 3) {
                mob.problem(CompanionTexts.tr("handler.mine.no_water_bucket"));
                ctx.failChain();
                return Status.REPLACED;
            }
            List<Task> pre = new ArrayList<>();
            if (mob.findItem(Items.BUCKET) < 0) {
                // Erst einen Eimer bauen (3 Eisen; der Zielplaner holt/schmilzt, was fehlt)
                CompanionPlanner.Plan plan = CompanionPlanner.obtain(mob, Items.BUCKET, 1);
                if (plan == null) {
                    mob.problem(CompanionTexts.tr("handler.mine.no_bucket_iron"));
                    ctx.failChain();
                    return Status.REPLACED;
                }
                mob.logInfo(CompanionTexts.tr("handler.mine.make_bucket"));
                pre.addAll(plan.tasks());
            } else {
                mob.logInfo(CompanionTexts.tr("handler.mine.fill_bucket"));
            }
            pre.add(Task.water(null));
            pre.add(again);
            mob.replaceCurrent(pre);
            return Status.REPLACED;
        }
        List<BlockPos> lava = mob.portals.lavaSources(24);
        if (!lava.isEmpty()) {
            mob.replaceCurrent(List.of(Task.pour(lava.getFirst()),
                    new Task(new TaskSpec.Gather(spec.resource(), spec.count(), spec.targetTotal()))));
            return Status.REPLACED;
        }
        // Gespürte/gesehene Lava: hingraben (bis in Reichweite) und Wasser drauf
        CompanionMemory.Place lp = mob.memory.nearest(CompanionMemory.Kind.LAVA, null, sl, mob.blockPosition(), 160);
        if (lp == null) return null;
        mob.logInfo(CompanionTexts.tr("handler.mine.lava", CompanionTexts.location(lp, mob.blockPosition())));
        List<Task> next = new ArrayList<>();
        if (lp.pos.distSqr(mob.blockPosition()) > 40 * 40) next.add(Task.travel(lp.pos));
        next.add(Task.pour(lp.pos));
        next.add(again);
        mob.replaceCurrent(next);
        return Status.REPLACED;
    }

    private boolean startOreRun(CompanionEntity mob, ServerLevel sl, MiningKnowledge mining, List<Task> ores) {
        List<BlockPos> at = new ArrayList<>();
        for (Task o : ores) if (o.pos() != null) at.add(o.pos());
        return startOreRunAt(mob, sl, mining, at);
    }

    private boolean startOreRunAt(CompanionEntity mob, ServerLevel sl, MiningKnowledge mining, List<BlockPos> ores) {
        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos o : ores)
            if (!mining.blacklisted(o) && !mob.hazardAround(sl, o, false)) targets.add(o);
        if (targets.isEmpty()) return false;
        if (targets.size() > 64) targets = new ArrayList<>(targets.subList(0, 64));
        oreRun = targets;
        oreGoal = oreGoal(targets);
        return true;
    }

    private static Goal oreGoal(List<BlockPos> ores) {
        return new Goal.AnyOf(ores.stream().map(p -> (Goal) new Goal.Adjacent(p)).toList());
    }

    /** Zum günstigsten Erz gehen; dort alles in Reichweite abbauen, dann weitersuchen. */
    private Status continueOreRun(TaskContext ctx, Resources.Res res) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        if (oreRun.removeIf(p -> !res.block().test(sl.getBlockState(p)))) {
            if (oreRun.isEmpty()) { oreRun = null; return Status.RUNNING; }
            oreGoal = oreGoal(oreRun); // abgebaute Erze nicht weiter ansteuern
        }
        List<BlockPos> inReach = oreRun.stream()
                .filter(p -> mob.getEyePosition().distanceToSqr(Vec3.atCenterOf(p)) <= REACH_SQR).toList();
        Navigator.Result r;
        if (!inReach.isEmpty()) r = Navigator.Result.ARRIVED;
        else if (ctx.guardedSupply()) {
            Status checked = checkOreTrip(ctx, res);
            if (checked != null) return checked;
            r = mob.nav.goTo(oreTrip.outboundGoal());
        } else r = mob.nav.goTo(oreGoal);
        if (r == Navigator.Result.RUNNING) return Status.RUNNING;
        if (r == Navigator.Result.UNREACHABLE) {
            ctx.mining().markUnreachable(oreRun);
            mob.logInfo(CompanionTexts.tr("handler.mine.ore_unreachable", oreRun.size(), CompanionTexts.resource(res.key())));
            oreRun = null;
            return Status.RUNNING;
        }
        if (inReach.isEmpty()) // angekommen, aber zu weit für die Augen: nächstes Mal neu bewerten
            inReach = oreRun.stream().filter(p -> mob.getEyePosition().distanceToSqr(Vec3.atCenterOf(p)) <= 6 * 6).toList();
        List<Task> next = new ArrayList<>();
        for (BlockPos p : inReach) if (next.size() < spec.count()) next.add(Task.breakAt(p, sl.getBlockState(p).getBlock()));
        boolean none = next.isEmpty();
        next.add(new Task(spec, task.tries() + (none ? 1 : 0)));
        oreRun = null;
        resetOreTrip();
        mob.replaceCurrent(next);
        return Status.REPLACED;
    }

    private void resetOreTrip() {
        oreTrip = null;
        oreTripTarget = null;
        oreTripInstalled = false;
    }

    /**
     * Geschützter Modus: vor dem Erz-Gang Hin- und Rückweg zum nächsten Erz nachweisen (dieselbe Prüfung wie beim
     * Kistengang, keine zweite Wegsuche). Der Rückweg darf nichts abbauen oder setzen, also kein Einweg-Abstieg ohne
     * Ausweg. UNSAFE und UNPROVEN sperren nur dieses Erz, nicht die Methode: Unsicherheit der Suche bestraft das
     * Bergbauen nicht pauschal. null = Weg nachgewiesen und installiert, weiterlaufen.
     */
    private Status checkOreTrip(TaskContext ctx, Resources.Res res) {
        CompanionEntity mob = ctx.mob();
        var running = mob.nav.executor();
        if (oreTrip != null && oreTripTarget != null && !oreRun.contains(oreTripTarget)) {
            if (running != null && !running.safeToCancel(mob)) return Status.RUNNING; // erst sicher landen
            mob.nav.stop();
            resetOreTrip(); // Ziel weg (abgebaut, kein Erz mehr): neu prüfen
        }
        if (oreTrip != null && oreTripInstalled && running != null && !running.safeToCancel(mob)) return null;
        if (oreTrip != null && oreTrip.expired()) {
            if (running != null && !running.safeToCancel(mob)) return null;
            mob.nav.stop();
            resetOreTrip();
        }
        if (oreTrip == null) {
            BlockPos from = mob.blockPosition();
            oreTripTarget = oreRun.stream().min(java.util.Comparator.comparingDouble(p -> p.distSqr(from))).orElseThrow();
            mob.getNavigation().stop();
            mob.nav.stop();
            oreTrip = new dev.begleiter.nav.RoundTripCheck(mob, oreTripTarget, REACH_SQR);
        }
        var status = oreTrip.step();
        if (status == dev.begleiter.nav.RoundTripCheck.Status.CHECKING) return Status.RUNNING;
        if (status != dev.begleiter.nav.RoundTripCheck.Status.SAFE) {
            mob.logInfo(CompanionTexts.tr(status == dev.begleiter.nav.RoundTripCheck.Status.UNSAFE
                    ? "handler.mine.route_unsafe" : "handler.mine.route_unproven",
                    CompanionTexts.resource(res.key()), oreTrip.reasonComponent()));
            ctx.mining().markUnreachable(List.of(oreTripTarget));
            oreRun.remove(oreTripTarget);
            resetOreTrip();
            if (oreRun.isEmpty()) oreRun = null;
            else oreGoal = oreGoal(oreRun);
            return Status.RUNNING;
        }
        if (!oreTripInstalled) {
            mob.nav.followChecked(oreTrip.outbound(), oreTrip.outboundContext(), oreTrip.outboundGoal());
            oreTripInstalled = true;
        }
        return null;
    }

    /** Ein Grabstück Richtung Zielhöhe; danach neu umsehen (Erze am Weg nimmt grabPassingOres mit). */
    private static boolean waterAhead(ServerLevel sl, BlockPos from, Direction dir) {
        for (int i = 1; i <= 8; i++)
            for (int dy = -3; dy <= 1; dy++)
                if (sl.getFluidState(from.relative(dir, i).above(dy)).is(net.minecraft.tags.FluidTags.WATER)) return true;
        return false;
    }

    private Status continueDig(TaskContext ctx, Resources.Res res) {
        CompanionEntity mob = ctx.mob();
        var r = mob.nav.goTo(new Goal.Block(digTarget));
        if (r == Navigator.Result.RUNNING) return Status.RUNNING;
        if (r == Navigator.Result.UNREACHABLE) {
            // In alle Richtungen zweimal versperrt (beobachtet: im Packeis 80 Mal in 40 s): aufgeben statt im Kreis drehen
            if (++ctx.mining().blockedTurns >= 8) {
                ctx.mining().blockedTurns = 0;
                mob.problem(CompanionTexts.tr("handler.mine.not_found", CompanionTexts.resource(res.key())));
                ctx.failChain();
                return Status.REPLACED;
            }
            mob.mineDir = mob.mineDir.getClockWise();
            mob.logInfo(CompanionTexts.tr("handler.mine.turn", CompanionTexts.tr("direction." + mob.mineDir.getName())));
        } else ctx.mining().blockedTurns = 0;
        digTarget = null;
        List<Task> next = new ArrayList<>(ctx.mining().bonusOres(ctx.level(), res));
        next.add(new Task(spec, task.tries() + 1));
        mob.replaceCurrent(next);
        return Status.REPLACED;
    }
}
