package dev.begleiter.task;

import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionBuilder;
import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.CompanionTerraform;
import dev.begleiter.nav.Goal;
import dev.begleiter.nav.MotionController;

/**
 * BUILD: den Bauauftrag (mob.build) Block für Block abarbeiten, etwa 5 Blöcke pro Sekunde, nur in Reichweite.
 * Survival: fehlt Material, wird es erst beschafft (Kiste, Abbau, Schmelzen, Craften) oder erfragt. Fortschritt und
 * Ausbesserungsrunden liegen im BuildJob (überlebt Neustart und Pause), nur der Standplatz gehört zu dieser Aufgabe.
 */
final class BuildHandler implements TaskHandler {
    private static final double REACH_SQR = 4.5 * 4.5;
    /** Bau-Bewegung TELEPORT: ab dieser Entfernung zur Baustelle springt er sofort hin. */
    private static final double TELEPORT_DISTANCE_SQR = 16 * 16;
    /** Bau-Bewegung GHOST: Reichweite, Flugtempo (Blöcke je Tick) und höchstens so viele Schritte pro Tick. */
    private static final double GHOST_REACH_SQR = 10 * 10;
    private static final double GHOST_SPEED = 1.5;
    private static final int GHOST_PER_TICK = 64;

    private int progress;
    private int stageAgain;
    /** Vor den Nutzpflanzen die fertig eingefasste Bewässerung anlaufen lassen. */
    private int plantsReadyAt = -1;
    /** Für welche Stelle der Standplatz gilt. */
    private BlockPos spotFor, spot;
    /** Kreativ: Schwebeplatz für welche Stelle, und alle Baustellen des Auftrags (dort nicht schweben). */
    private BlockPos hover, hoverFor;
    private java.util.Set<BlockPos> planned;

    BuildHandler(Task task) { }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        CompanionBuilder.BuildJob job = mob.build;
        if (job == null || mob.buildMovement() != CompanionEntity.BuildMovement.GHOST) return step(ctx);
        // Geist: so viele Schritte pro Tick, wie etwas passiert (setzen/wegräumen), höchstens GHOST_PER_TICK
        Status st = Status.RUNNING;
        for (int i = 0; i < GHOST_PER_TICK; i++) {
            if (mob.work.active() && mob.work.remaining() <= 0) break;
            int before = job.index;
            BlockPos at = before < job.order.size() ? job.order.get(before).pos() : null;
            BlockState was = at != null && ctx.level().hasChunkAt(at) ? ctx.level().getBlockState(at) : null;
            st = step(ctx);
            if (st != Status.RUNNING || mob.build != job || job.paused) break;
            boolean changed = job.index != before
                    || at != null && was != null && ctx.level().getBlockState(at) != was;
            if (!changed) break;
        }
        return st;
    }

    private Status step(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        CompanionBuilder.BuildJob job = mob.build;
        if (job == null || job.paused) return Status.FAILED; // Auftrag weg/pausiert: die Entity reiht BUILD wieder ein
        if (!job.inDimension(sl)) {
            mob.pauseWork(CompanionTexts.tr("handler.build.dimension"));
            return Status.RUNNING;
        }
        boolean creative = mob.creativeHelper();
        if (!creative && mob.tickCount >= stageAgain) {
            stageAgain = mob.tickCount + 80;
            CompanionBuilder.stageBuildMaterials(mob, sl, job);
        }
        // Baustelle in ungeladenem Chunk: nichts lesen (das würde ihn laden), nur hinlaufen
        if (job.index < job.order.size() && !sl.hasChunkAt(job.order.get(job.index).pos())) {
            ctx.approach(job.order.get(job.index).pos(), REACH_SQR);
            return Status.RUNNING;
        }

        // Was schon richtig steht, überspringen. Beim Ausbessern Strömung in vorgesehenen Freiräumen erhalten.
        while (job.index < job.order.size() && sl.hasChunkAt(job.order.get(job.index).pos())
                && ((job.repairRounds > 0 && job.order.get(job.index).state().isAir()
                        && !sl.getFluidState(job.order.get(job.index).pos()).isEmpty())
                    || CompanionBuilder.matches(sl.getBlockState(job.order.get(job.index).pos()), job.order.get(job.index).state())))
            job.index++;
        if (job.index >= job.order.size()) return finish(mob, sl, job);
        if (!sl.hasChunkAt(job.order.get(job.index).pos())) return Status.RUNNING; // nächster Tick läuft hin

        int pct = job.index * 100 / job.total();
        if (pct >= job.lastReported + 25) {
            job.lastReported = pct - pct % 25;
            mob.talk.tellProgress(CompanionTexts.tr("handler.build.progress", CompanionTexts.buildName(job.name), job.index, job.total(), pct));
        }

        CompanionBuilder.Placement p = job.order.get(job.index);
        Item item = CompanionBuilder.itemFor(p.state());
        int cost = CompanionBuilder.itemCount(p.state());
        if (!creative && item != null && !mob.work.permitsInput(item, cost)) {
            mob.work.noBuildMaterial(item, cost, true);
            return Status.REPLACED;
        }
        BlockState cur = sl.getBlockState(p.pos());

        // Acker wie mit der Hacke: Gras/Erde an Ort und Stelle umgraben statt abbauen (beobachtet: die vier Felder neben dem
        // Wasser in der Mitte ließen sich wegen "Wasser daneben" nicht abbauen und blieben Gras)
        if (p.state().is(net.minecraft.world.level.block.Blocks.FARMLAND) && sl.getBlockState(p.pos().above()).isAir()
                && (cur.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || cur.is(net.minecraft.world.level.block.Blocks.DIRT)
                || cur.is(net.minecraft.world.level.block.Blocks.DIRT_PATH))) {
            if (!approachForBuild(ctx, sl, job, p.pos())) return Status.RUNNING;
            sl.setBlock(p.pos(), net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState(), 3);
            sl.playSound(null, p.pos(), net.minecraft.sounds.SoundEvents.HOE_TILL.value(), net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
            mob.swingArm();
            job.index++;
            return Status.RUNNING;
        }
        // Im Weg: erst abbauen (Kreativ: sofort, sobald in Reichweite)
        if (!cur.isAir() && !cur.canBeReplaced() && !CompanionBuilder.matches(cur, p.state()) || (p.state().isAir() && !cur.isAir())) {
            if (cur.getDestroySpeed(sl, p.pos()) < 0) {
                mob.logWarn(CompanionTexts.tr("handler.build.unbreakable", cur.getBlock().getName()));
                job.index++;
                return Status.RUNNING;
            }
            if (job.terrainOnly && (sl.getBlockEntity(p.pos()) != null || !CompanionTerraform.natural(sl, p.pos(), cur))) {
                mob.logInfo(CompanionTexts.tr("handler.build.protected", cur.getBlock().getName(), p.pos().toShortString()));
                job.index++;
                return Status.RUNNING;
            }
            if (!creative) {
                // Lässt sich die Stelle auch nach mehreren Anläufen nicht freiräumen (z.B. Lava daneben, kein Eimer),
                // überspringen statt endlos neu anzusetzen; der Rest des Bauwerks wird trotzdem fertig.
                if (job.breakIndex != job.index) { job.breakIndex = job.index; job.breakTries = 0; }
                if (++job.breakTries > 4) {
                    mob.logWarn(CompanionTexts.tr("handler.build.failed", p.pos().toShortString(), p.state().getBlock().getName()));
                    job.index++;
                    return Status.RUNNING;
                }
                // Nur genau den geprüften Block abbauen; steht dort beim Abbau etwas anderes, bricht er ab
                mob.pushFront(Task.breakAt(p.pos(), cur.getBlock()));
                return Status.REPLACED;
            }
            if (!approachForBuild(ctx, sl, job, p.pos())) return Status.RUNNING;
            mob.worldBreak(sl, p.pos(), false);
            mob.swingArm();
            return Status.RUNNING;
        }
        if (p.state().isAir()) {
            job.index++;
            return Status.RUNNING;
        }

        // Material (Doppelstufe kostet 2: dafür reicht ein einzelnes Item nicht)
        if (!creative && item != null && CompanionCraft.count(mob, BuiltInRegistries.ITEM.getKey(item).toString()) < cost) {
            if (mob.work.is(dev.begleiter.CompanionWork.Kind.BUILD)) {
                if (!mob.work.planBuildMaterial(sl, item, cost - mob.getInventory().countItem(item))) mob.work.noBuildMaterial(item, cost, false);
                return Status.REPLACED;
            }
            int total = Math.min(64, CompanionBuilder.remaining(sl, job).getOrDefault(item, cost));
            int tries = job.attempts.merge(item, 1, Integer::sum);
            List<Task> get = tries <= 3 ? CompanionBuilder.acquire(mob, sl, item, total) : null;
            if (get == null || get.isEmpty()) {
                mob.pause(CompanionTexts.tr("handler.build.missing", new ItemStack(item).getHoverName(), total));
                return Status.RUNNING;
            }
            mob.logInfo(CompanionTexts.tr("handler.build.acquire", total, new ItemStack(item).getHoverName(), get.size()));
            for (int i = get.size() - 1; i >= 0; i--) mob.pushFront(get.get(i));
            return Status.REPLACED;
        }

        if (p.state().getBlock() instanceof net.minecraft.world.level.block.CropBlock
                || p.state().getBlock() instanceof net.minecraft.world.level.block.StemBlock
                || p.state().getBlock() instanceof net.minecraft.world.level.block.SugarCaneBlock
                || p.state().getBlock() instanceof net.minecraft.world.level.block.BambooStalkBlock) {
            if (plantsReadyAt < 0) {
                boolean fluids = false;
                for (BlockPos placedPos : job.placed) {
                    BlockState state = sl.getBlockState(placedPos);
                    if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                        sl.scheduleTick(placedPos, state.getFluidState().getType(), state.getFluidState().getType().getTickDelay(sl));
                        fluids = true;
                    }
                }
                plantsReadyAt = mob.tickCount + (fluids ? 40 : 0);
            }
            if (mob.tickCount < plantsReadyAt) return Status.RUNNING;
        }
        if (!approachForBuild(ctx, sl, job, p.pos())) return Status.RUNNING;
        boolean ghost = mob.buildMovement() == CompanionEntity.BuildMovement.GHOST;
        // nicht in sich selbst bauen (der Geist fliegt durch Blöcke, ihn stört das nicht)
        if (!ghost && !p.state().getCollisionShape(sl, p.pos()).isEmpty() && mob.getBoundingBox().intersects(new AABB(p.pos()))) {
            if (mob.buildMovement() != CompanionEntity.BuildMovement.NORMAL && mob.isNoGravity()) { // schwebend: einfach zur Seite fliegen
                fly(mob, sl, job, p.pos());
                return Status.RUNNING;
            }
            if (!p.pos().equals(spotFor) || spot == null || mob.tickCount % 40 == 0) {
                spotFor = p.pos().immutable();
                spot = standSpot(mob, sl, p.pos());
            }
            if (spot != null) {
                if (mob.blockPosition().equals(spot) && mob.onGround()) {
                    // Am Fußblock angekommen kann der Körper noch in die Setzstelle ragen. Den kurzen Schritt
                    // zur Mitte nur beenden, wenn der ganze überstrichene Körper in die Welt passt.
                    double dx = spot.getX() + 0.5 - mob.getX(), dz = spot.getZ() + 0.5 - mob.getZ();
                    mob.nav.stop();
                    mob.getNavigation().stop();
                    if (sl.noCollision(mob, mob.getBoundingBox().expandTowards(dx, 0, dz)))
                        mob.motion.move(MotionController.Owner.NAVIGATION,
                                spot.getX() + 0.5, mob.getY(), spot.getZ() + 0.5, 1.0, false);
                } else if (ctx.useNewPathing(spot)) ctx.approachGoal(new Goal.Block(spot), spot);
                else ctx.approach(spot, 0.75);
            }
            ctx.trackMove();
            return Status.RUNNING;
        }
        if (!ghost && ++progress % 4 != 0) return Status.RUNNING; // etwa 5 Blöcke pro Sekunde (Geist: sofort)
        mob.getLookControl().setLookAt(Vec3.atCenterOf(p.pos()));
        // Den Auftrag erst nach einer tatsächlich erfolgreichen Platzierung fortschreiben. Insbesondere bei
        // Redstone/mehrteiligen Blöcken keine Items verbrauchen, wenn die Welt den Block ablehnt.
        boolean placed = mob.worldSet(sl, p.pos(), p.state(), CompanionBuilder.PLACE_FLAGS);
        if (!placed || !CompanionBuilder.matches(sl.getBlockState(p.pos()), p.state())) {
            mob.logWarn(CompanionTexts.tr("handler.build.failed", p.pos().toShortString(), p.state().getBlock().getName()));
            mob.pause(CompanionTexts.tr("handler.build.pause", p.pos().toShortString()));
            return Status.RUNNING;
        }
        CompanionBuilder.applyData(sl, job, p.pos(), creative, false); // Inhalte erst im Abschluss
        sl.playSound(null, p.pos(), p.state().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 1f);
        mob.swingArm();
        if (!creative && item != null) {
            int slot = mob.findItem(item);
            if (slot >= 0 && (mob.getInventory().getItem(slot).is(Items.WATER_BUCKET)
                    || mob.getInventory().getItem(slot).is(Items.LAVA_BUCKET))) {
                mob.getInventory().setItem(slot, new ItemStack(Items.BUCKET));
            } else {
                // Über alle Plätze abbuchen: Eine Doppelstufe kostet 2, die Stufen können getrennt liegen.
                int need = cost;
                for (int i = 0; i < mob.getInventory().getContainerSize() && need > 0; i++) {
                    ItemStack s = mob.getInventory().getItem(i);
                    if (!s.is(item)) continue;
                    int n = Math.min(need, s.getCount());
                    s.shrink(n);
                    need -= n;
                }
            }
            mob.work.spent(item, cost);
        }
        job.placed.add(p.pos());
        CompanionBuilder.notePlaced(job, p); // Restmaterial ohne neue Zählung aktuell halten
        if (item != null) job.attempts.remove(item); // erfolgreicher Einbau beendet eine erfolglose Beschaffungsserie
        job.index++;
        mob.work.progress(1);
        return Status.RUNNING;
    }

    /**
     * Zum Bauen nicht auf die Stelle selbst laufen (Fackel auf einem Zaun, Block in der Luft sind unerreichbar),
     * sondern auf einen Standplatz, von dem aus die Stelle in Reichweite ist.
     */
    private boolean approachForBuild(TaskContext ctx, ServerLevel sl, CompanionBuilder.BuildJob job, BlockPos target) {
        CompanionEntity mob = ctx.mob();
        double d = mob.distanceToSqr(Vec3.atCenterOf(target));
        boolean creative = mob.creativeHelper();
        CompanionEntity.BuildMovement move = mob.buildMovement();
        if (move == CompanionEntity.BuildMovement.GHOST) return ghostFly(mob, target, d);
        boolean flying = move != CompanionEntity.BuildMovement.NORMAL;
        // Kreativ: wie ein Kreativspieler etwas weiter reichen (die Reichweite hängt am Material-Modus, nicht am Fliegen)
        if (d <= (creative ? 7 * 7 : REACH_SQR)) {
            if (flying && mob.isNoGravity()) mob.keepCreativeFlight(); // in der Luft bleiben, solange er dort baut
            return true;
        }
        if (!target.equals(job.approachFor)) { job.approachFor = target.immutable(); job.approachSince = mob.tickCount; spot = null; }
        int waited = mob.tickCount - job.approachSince;
        // Teleport: weite Wege oder festgefahren -> direkt an einen freien Platz neben der Baustelle
        if (move == CompanionEntity.BuildMovement.TELEPORT && (d > TELEPORT_DISTANCE_SQR || waited > 80)
                && teleportTo(mob, sl, job, target)) return false;
        // Fliegen wie ein Spieler: zu hohen Stellen, wenn er schon schwebt oder am Boden nicht hinkommt
        if (flying && (target.getY() > mob.getBlockY() + 2 || mob.isNoGravity() || waited > 60)) {
            fly(mob, sl, job, target);
            return false;
        }
        // Kommt er lange nicht näher (Zaun, Wasser, enge Stelle): aus etwas größerer Entfernung setzen
        // (zu Fuß: gestreckt bis 6 Blöcke)
        if (!flying && waited > 200 && d <= 36) return true;
        if (!target.equals(spotFor) || mob.tickCount % 40 == 0) {
            spotFor = target.immutable();
            spot = standSpot(mob, sl, target);
        }
        if (spot == null) return ctx.approach(target, REACH_SQR);
        // Near(spot, 1) hält schon im Nachbarblock an. Dort kann das Bauziel noch außerhalb der
        // Reichweite liegen; dann wartet der Bau bis zum 200-Tick-Fallback, obwohl der Weg frei ist.
        // Den tatsächlichen Fußblock ansteuern; oben wird bei erreichbarem Bauziel sofort gebaut.
        if (ctx.useNewPathing(spot)) ctx.approachGoal(new Goal.Block(spot), spot);
        else ctx.approach(spot, 0.75);
        return false;
    }

    /**
     * Bau-Bewegung GHOST: in Reichweite (10 Blöcke) sofort bauen, weiter weg direkt über die Stelle teleportieren.
     * Ziel ist 2 Blöcke über der Stelle, damit er beim Bauen sichtbar über dem Werk schwebt.
     */
    private boolean ghostFly(CompanionEntity mob, BlockPos target, double distSqr) {
        mob.keepGhost();
        mob.nav.stop();
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(Vec3.atCenterOf(target));
        if (distSqr <= GHOST_REACH_SQR) { mob.setDeltaMovement(Vec3.ZERO); return true; }
        // Ab 10 Blöcken Abstand nicht fliegen, sondern sofort über die Stelle springen (er kann in Blöcken stehen)
        Vec3 dest = Vec3.atBottomCenterOf(target.above(2));
        if (mob.level() instanceof ServerLevel sl) {
            sl.playSound(null, mob.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.5f, 1.2f);
            mob.snapTo(dest.x, dest.y, dest.z, mob.getYRot(), mob.getXRot());
            mob.setDeltaMovement(Vec3.ZERO);
            sl.playSound(null, target, SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.5f, 1.2f);
            return true;
        }
        Vec3 delta = dest.subtract(mob.position());
        double len = delta.length();
        mob.setDeltaMovement(len < 0.05 ? Vec3.ZERO : delta.scale(Math.min(GHOST_SPEED, len) / len));
        return false;
    }

    /**
     * Bau-Bewegung TELEPORT: sofort an einen freien Schwebeplatz in Reichweite der Stelle (mit Enderman-Geräusch).
     * Liefert false, wenn dort kein Platz frei ist; dann fliegt er normal weiter.
     */
    private boolean teleportTo(CompanionEntity mob, ServerLevel sl, CompanionBuilder.BuildJob job, BlockPos target) {
        BlockPos at = hoverSpot(mob, sl, job, target);
        if (at == null) return false;
        mob.keepCreativeFlight();
        mob.nav.stop();
        mob.getNavigation().stop();
        sl.playSound(null, mob.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.5f, 1.2f);
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, mob.getYRot(), mob.getXRot());
        mob.setDeltaMovement(Vec3.ZERO);
        sl.playSound(null, at, SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.5f, 1.2f);
        hover = at;
        hoverFor = target.immutable();
        job.approachSince = mob.tickCount;
        return true;
    }

    /**
     * Kreativ-Flug: ohne Schwerkraft geradlinig zu einem freien Schwebeplatz in Reichweite der Stelle (höchstens
     * 0,6 Blöcke je Tick). Ist der gerade Weg verbaut, setzt er sich direkt hin, wenn der Platz frei ist – wie ein
     * Spieler, der um die Wand herumfliegt. Landen übernimmt die Entity, sobald er nicht mehr fliegt.
     */
    private void fly(CompanionEntity mob, ServerLevel sl, CompanionBuilder.BuildJob job, BlockPos target) {
        mob.keepCreativeFlight();
        mob.nav.stop();
        mob.getNavigation().stop();
        if (hover == null || !target.equals(hoverFor) || mob.tickCount % 20 == 0) {
            hover = hoverSpot(mob, sl, job, target);
            hoverFor = target.immutable();
        }
        mob.getLookControl().setLookAt(Vec3.atCenterOf(target));
        if (hover == null) { mob.setDeltaMovement(Vec3.ZERO); return; }
        Vec3 goal = Vec3.atBottomCenterOf(hover), delta = goal.subtract(mob.position());
        double len = delta.length();
        if (len < 0.1) { mob.setDeltaMovement(Vec3.ZERO); return; }
        Vec3 step = delta.scale(Math.min(0.6, len) / len);
        if (!sl.noCollision(mob, mob.getBoundingBox().move(step))) {
            if (sl.noCollision(mob, mob.getBoundingBox().move(delta))) mob.snapTo(goal.x, goal.y, goal.z, mob.getYRot(), mob.getXRot());
            mob.setDeltaMovement(Vec3.ZERO);
            return;
        }
        mob.setDeltaMovement(step);
    }

    /** Freier Platz für den ganzen Körper nahe der Stelle, nicht auf einer noch offenen Baustelle, ohne Flüssigkeit. */
    private BlockPos hoverSpot(CompanionEntity mob, ServerLevel sl, CompanionBuilder.BuildJob job, BlockPos target) {
        if (planned == null) {
            planned = new java.util.HashSet<>();
            for (var pl : job.order) if (!pl.state().isAir()) planned.add(pl.pos());
        }
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        Vec3 tc = Vec3.atCenterOf(target);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 3; dy++) {
            BlockPos s = target.offset(dx, dy, dz);
            if (tc.distanceToSqr(s.getX() + 0.5, s.getY() + 1, s.getZ() + 0.5) > REACH_SQR * 0.8) continue;
            if (planned.contains(s) || planned.contains(s.above()) || s.equals(target) || s.above().equals(target)) continue;
            if (!sl.getFluidState(s).isEmpty() || !sl.getFluidState(s.above()).isEmpty()) continue;
            if (!sl.noCollision(mob, mob.getBoundingBox().move(s.getX() + .5 - mob.getX(), s.getY() - mob.getY(), s.getZ() + .5 - mob.getZ())))
                continue;
            // nah am Bot (ruhiger Flug), lieber über als unter der Baustelle (er baut von unten nach oben)
            double score = s.distSqr(mob.blockPosition()) + (dy < 1 ? 6 : 0);
            if (score < bd) { bd = score; best = s.immutable(); }
        }
        return best;
    }

    /** Nächster begehbarer Platz (Boden darunter, 2 Blöcke frei, keine Flüssigkeit) in Reichweite der Stelle. */
    private static BlockPos standSpot(CompanionEntity mob, ServerLevel sl, BlockPos target) {
        BlockPos best = null, me = mob.blockPosition();
        double bd = Double.MAX_VALUE;
        Vec3 tc = Vec3.atCenterOf(target);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -3; dy <= 1; dy++) {
            BlockPos s = target.offset(dx, dy, dz);
            if (s.equals(target) || s.above().equals(target)) continue;
            if (tc.distanceToSqr(s.getX() + 0.5, s.getY(), s.getZ() + 0.5) > REACH_SQR * 0.85) continue;
            BlockPos below = s.below();
            if (sl.getBlockState(below).is(net.minecraft.world.level.block.Blocks.FARMLAND)) continue;
            if (sl.getBlockState(below).getCollisionShape(sl, below).isEmpty()) continue;
            BlockState feet = sl.getBlockState(s);
            // Teppiche liegen im Fußblock und sind begehbar. Sonst bleibt beim Verlegen des letzten
            // Teppichs über Trichtern kein Standplatz übrig, obwohl die fertigen Nachbarplätze frei sind.
            boolean carpet = feet.getBlock() instanceof net.minecraft.world.level.block.CarpetBlock;
            if ((!feet.getCollisionShape(sl, s).isEmpty() && !carpet)
                    || !sl.getBlockState(s.above()).getCollisionShape(sl, s.above()).isEmpty()) continue;
            double standY = s.getY() + (carpet ? feet.getCollisionShape(sl, s).max(net.minecraft.core.Direction.Axis.Y) : 0);
            if (!sl.noCollision(mob, mob.getBoundingBox().move(s.getX() + .5 - mob.getX(),
                    standY - mob.getY(), s.getZ() + .5 - mob.getZ()))) continue;
            if (!sl.getFluidState(s).isEmpty() || !sl.getFluidState(below).isEmpty()) continue;
            // Plätze, für die er mehr als eine Stufe hoch müsste, nur als letzte Wahl: oben auf dem halbfertigen
            // Bau liegt oft ein naher Platz, den er ohne Gerüst nicht erreicht, ebenerdig gleich daneben einer.
            double dd = s.distSqr(me) + (s.getY() > me.getY() + 1 ? 400 : 0);
            if (dd < bd) { bd = dd; best = s.immutable(); }
        }
        return best;
    }

    /** Fertig: Formen + Updates (Redstone startet), Nachkontrolle mit bis zu 2 Ausbesserungs-Runden, dann melden. */
    private static Status finish(CompanionEntity mob, ServerLevel sl, CompanionBuilder.BuildJob job) {
        var verification = CompanionBuilder.finishChecked(sl, job);
        int wrong = verification.mismatches();
        var redstoneErrors = CompanionBuilder.redstoneMismatches(sl, job);
        if (!redstoneErrors.isEmpty())
            mob.logWarn(CompanionTexts.tr("handler.build.redstone", redstoneErrors.size(), redstoneErrors.get(0).toShortString()));
        if (wrong > 0 && job.repairRounds < 2) {
            job.repairRounds++;
            job.index = 0; // was stimmt, wird übersprungen
            mob.logInfo(CompanionTexts.tr("handler.build.repair", wrong));
            return Status.RUNNING;
        }
        if (!verification.complete() || !redstoneErrors.isEmpty()) {
            job.paused = true;
            job.index = 0;
            mob.pauseWork(CompanionTexts.tr("build.verification.blocked", wrong, verification.unknown(), redstoneErrors.size()));
            return Status.RUNNING;
        }
        // Inhalte und Entities kommen erst, wenn das feste Ergebnis bestätigt ist.
        if (mob.creativeHelper()) CompanionBuilder.applyContents(sl, job);
        if (verification.preserved() > 0)
            mob.tell(CompanionTexts.tr("build.verification.preserved", verification.preserved()));
        mob.tell(CompanionTexts.tr("handler.build.done", CompanionTexts.buildName(job.name), job.total(), wrong > 0 ? CompanionTexts.tr("handler.build.mismatches", wrong) : Component.literal(".")));
        if (!mob.creativeHelper()) {
            int contents = CompanionBuilder.pendingContents(job);
            if (contents > 0) mob.tell(CompanionTexts.tr("handler.build.contents.survival", contents));
        }
        if (!job.entities.isEmpty()) {
            boolean creative = mob.creativeHelper();
            // Survival: Lebewesen nicht erschaffen; Rahmen, Gemälde & Co. aus dem eigenen Inventar
            long villagers = job.entities.stream().filter(e -> "minecraft:villager".equals(e.tag().getStringOr("id", ""))).count();
            var result = CompanionBuilder.spawnEntities(sl, job, creative ? null : mob);
            if (result.spawned() > 0) mob.tell(CompanionTexts.tr("handler.build.entities", result.spawned()));
            if (!creative && result.missing() > 0)
                mob.tell(CompanionTexts.tr("handler.build.entities.survival", result.missing(), villagers));
            if (result.emptyFrames() > 0) mob.tell(CompanionTexts.tr("handler.build.frames.empty", result.emptyFrames()));
            if (creative && result.missing() > 0) mob.logWarn(CompanionTexts.tr("handler.build.entities.failed", result.missing()));
        }
        mob.build = null;
        mob.work.buildFinished();
        return Status.DONE;
    }
}
