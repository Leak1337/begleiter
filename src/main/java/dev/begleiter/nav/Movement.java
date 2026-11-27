package dev.begleiter.nav;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionTexts;
import net.minecraft.network.chat.Component;

/**
 * Führt eine Bewegung des Weges mit der Mob-Steuerung aus. Ablauf je Tick: erst ein Hindernis abbauen (von oben
 * nach unten, bestes Werkzeug), dann einen fehlenden Block setzen (Brücke/Stufe), dann laufen/springen.
 * Fertig, sobald die Füße im Zielblock sind (beim Fallen: am Boden) – keine Blockmitte nötig.
 */
public final class Movement {
    public enum Status { RUNNING, SUCCESS, FAILED }

    public final Move move;
    public final BlockPos src, dest;
    private final List<BlockPos> breaks;
    private final BlockPos place;
    private final int timeout;
    private int ticks;
    /** Ticks im Wasser (nur jeder dritte zählt fürs Zeitlimit). */
    private int waterTicks;
    private BlockPos breaking;
    private int breakDone, breakTotal;
    private int jumpTicks = -1;
    /** In dieser Bewegung schon abgebaut/gesetzt (dann nicht lossprinten). */
    private boolean worked;
    /** Wassereimer-Landung: Wasser liegt (wird nach der Landung wieder aufgenommen). */
    private boolean waterPlaced;
    private BlockPos waterAt;
    private ClutchPlan clutch;
    private boolean dropCommitted;
    private boolean allowClutchPlacement;

    /** Parkour-Ablauf: Absprung anfahren → abspringen → fliegen → Landung prüfen → abfangen. */
    public enum Phase { APPROACH, COMMIT, AIRBORNE, LAND, STABILIZE }
    public Phase phase = Phase.APPROACH;
    /** Sprungplan aus der Simulation (beim Absprung ab der echten Position neu gerechnet). */
    public JumpSim.Plan jump;
    /** Danach folgt gleich ein weiterer Sprung: Schwung mitnehmen statt anhalten. */
    public boolean chained;
    /** Danach geht es in dieselbe Richtung weiter (laufen oder springen): nach der Landung nicht abbremsen. */
    public boolean keepMomentum;
    /** Sprünge in Folge ab diesem (für die Anzeige, höchstens {@link PathExecutor#LOOKAHEAD}). */
    public int chainLength = 1;
    private boolean runUpDone;
    private int commitTries, stableTicks;
    private double takeoffX, takeoffZ, maxDev, actualReach = -1;
    public String failReason = "";
    private Component failureText = Component.empty();
    private String failureSource = "";
    public Component failureComponent() {
        return failReason.equals(failureSource) ? failureText.copy() : CompanionTexts.literal(failReason);
    }
    /** Tempo-Faktor wie bei der normalen Navigation. */
    static final double WALK_SPEED = 1.1;

    public Movement(Move move, BlockPos src, BlockPos dest, double cost) {
        this.move = move;
        this.src = src;
        this.dest = dest;
        this.timeout = (int) Math.min(cost, 2000) + 60;
        this.breaks = new ArrayList<>();
        BlockPos p = null;
        int x = src.getX(), y = src.getY(), z = src.getZ();
        switch (move.kind) {
            case TRAVERSE -> {
                breaks.add(dest.above());
                breaks.add(dest);
                p = dest.below();
            }
            case ASCEND -> {
                breaks.add(new BlockPos(x, y + 2, z));
                breaks.add(dest.above());
                breaks.add(dest);
                p = dest.below();
            }
            case DESCEND -> {
                BlockPos col = new BlockPos(dest.getX(), y, dest.getZ());
                breaks.add(col.above());
                breaks.add(col);
                if (dest.getY() == y - 1) breaks.add(dest);
            }
            case PILLAR -> {
                // pillarStep will auch y+3 frei haben (Kopf beim Sprung)
                breaks.add(new BlockPos(x, y + 3, z));
                breaks.add(new BlockPos(x, y + 2, z));
            }
            case DOWNWARD -> breaks.add(dest);
            default -> { }
        }
        this.place = p;
    }

    /** Dieselben geplanten Änderungen wie bei der Ausführung; Wegvorschauen verändern nie die echte Welt. */
    List<BlockPos> plannedBreaks() { return List.copyOf(breaks); }
    boolean hasWorked() { return worked; }

    void cancel(CompanionEntity mob) {
        if (breaking != null) mob.level().destroyBlockProgress(mob.getId(), breaking, -1);
        breaking = null;
    }
    BlockPos plannedPlacement() { return move.kind == Move.Kind.PILLAR ? src : place; }

    private static boolean pass(ServerLevel sl, BlockPos p) {
        return BlockFlags.has(BlockFlags.of(sl.getBlockState(p)), BlockFlags.PASS);
    }

    private static boolean standable(ServerLevel sl, BlockPos p) {
        int f = BlockFlags.of(sl.getBlockState(p));
        return BlockFlags.has(f, BlockFlags.STAND) && !BlockFlags.has(f, BlockFlags.AVOID_INTO);
    }

    private static Component blockInfo(ServerLevel sl, BlockPos p) {
        BlockState s = sl.getBlockState(p);
        return CompanionTexts.tr("nav.diagnostic.block", s.getBlock().getName(),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(), Integer.toHexString(BlockFlags.of(s)));
    }

    private Status fail(Component why) {
        failureText = why.copy(); failReason = why.getString(); failureSource = failReason;
        return Status.FAILED;
    }

    /** Darf abgebrochen werden, ohne dass er in der Luft hängt oder halb gesprungen ist (an der Leiter hält er sich fest). */
    public boolean safeToCancel(CompanionEntity mob) {
        return (mob.onGround() || mob.isInWater() || mob.onClimbable()) && jumpTicks < 0 && !waterPlaced && !mob.isPillarActive();
    }

    /** Harte Obergrenze über dem Zeitlimit: greift auch ohne sicheren Abbruchzustand, damit er nie endlos hängt. */
    static final int HARD_EXTRA_TICKS = 200;

    public Status tick(CompanionEntity mob, NavContext ctx) {
        ServerLevel sl = ctx.level;
        allowClutchPlacement = ctx.allowPlace;
        if (++ticks > timeout + HARD_EXTRA_TICKS) {
            // z. B. vom Clutch-Wasser weggestoßen: Wasser bleibt liegen, aber der Weg wird wieder frei
            if (waterPlaced) mob.logInfo(CompanionTexts.tr("nav.diagnostic.clutch_left", waterAt.toShortString()));
            waterPlaced = false;
            jumpTicks = -1;
            mob.setSprinting(false);
            return fail(CompanionTexts.tr("nav.failure.hard_timeout"));
        }
        // ein Sprung wird nie in der Luft abgebrochen: das Zeitlimit gilt nur bis zum Absprung
        if (ticks > timeout && safeToCancel(mob) && (move.kind != Move.Kind.PARKOUR || phase == Phase.APPROACH)) {
            mob.logInfo(CompanionTexts.tr("nav.diagnostic.timeout", move.kind.name(),
                    String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", mob.getX(), mob.getY(), mob.getZ()),
                    CompanionTexts.tr(mob.onGround() ? "nav.diagnostic.ground" : "nav.diagnostic.air"),
                    mob.isInWater() ? CompanionTexts.tr("nav.diagnostic.water") : Component.empty(),
                    blockInfo(sl, dest), blockInfo(sl, dest.above()), blockInfo(sl, dest.below()), blockInfo(sl, src.above(2)),
                    breaks.size(), place == null ? CompanionTexts.tr("nav.diagnostic.no_placement") : CompanionTexts.literal(place.toShortString())));
            return fail(CompanionTexts.tr("nav.failure.timeout"));
        }
        mob.getNavigation().stop();

        // 0. Holztür/Zauntor im Weg: öffnen
        if (move.kind == Move.Kind.TRAVERSE) {
            open(mob, sl, dest);
            open(mob, sl, dest.above());
        }

        // 1. Hindernisse abbauen (eins nach dem anderen); Türen, Leitern und Wasser bleiben
        for (BlockPos b : breaks) {
            if (pass(sl, b) || openable(sl, b) || !sl.getFluidState(b).isEmpty()
                    || BlockFlags.has(BlockFlags.of(sl.getBlockState(b)), BlockFlags.PORTAL | BlockFlags.END_PORTAL)) continue;
            worked = true;
            return breakStep(mob, ctx, sl, b);
        }
        breaking = null;

        // 2. fehlenden Block setzen (nur wo die Suche eine Brücke/Stufe geplant hat: Luft darunter, kein Wasser, keine Leiter)
        if (place != null && !standable(sl, place) && (move.kind == Move.Kind.TRAVERSE || move.kind == Move.Kind.ASCEND)
                && pass(sl, place) && sl.getFluidState(place).isEmpty() && !climb(sl, place) && !climb(sl, dest)
                && sl.getFluidState(dest).isEmpty()) {
            BlockState there = sl.getBlockState(place);
            if (!there.canBeReplaced()) {
                // Blume, Gras, Fackel: ohne Kollision und sofort abbaubar – erst wegnehmen (fällt als Item heraus).
                // Redstone-Teile oder anderes Gebautes eines Spielers bleiben, die Suche plant dann neu.
                if (there.getDestroySpeed(sl, place) == 0 && there.getCollisionShape(sl, place).isEmpty()
                        && (ctx.mayBreak(there, BlockFlags.of(there)) || plainTorch(there))) {
                    if (!mob.worldBreak(sl, place, true)) return fail(CompanionTexts.tr("nav.failure.placement_blocked"));
                    return Status.RUNNING;
                }
                return fail(CompanionTexts.tr("nav.failure.placement_blocked"));
            }
            if (mob.getBoundingBox().intersects(new AABB(place))) {
                walkTo(mob, src, 0.6);
                return Status.RUNNING;
            }
            Item item = mob.scaffoldItem();
            int slot = item == null ? -1 : mob.findItem(item);
            if (slot < 0) return fail(CompanionTexts.tr("nav.failure.no_blocks"));
            worked = true;
            BlockState st = Block.byItem(item).defaultBlockState();
            mob.getLookControl().setLookAt(Vec3.atCenterOf(place));
            mob.swingArm();
            if (!mob.placeConfirmed(sl, place, st)) return fail(CompanionTexts.tr("nav.failure.placement_blocked"));
            sl.playSound(null, place, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 1f);
            mob.getInventory().getItem(slot).shrink(1);
            return Status.RUNNING;
        }

        // 2b. im Wasser/Wasserfall schwimmen: hochschwimmen, gegen die Strömung drücken, zum tieferen Ziel abtauchen
        if (mob.isInWater() && (move.kind == Move.Kind.TRAVERSE || move.kind == Move.Kind.DIAGONAL || move.kind == Move.Kind.ASCEND)) {
            swim(mob);
            if (waterTicks++ % 3 != 0) ticks--; // im Wasser kommt er langsamer voran: dreifache Zeit
        }

        // 3. bewegen
        return switch (move.kind) {
            case TRAVERSE, DIAGONAL -> {
                mob.setSprinting(ctx.canSprint && !worked);
                walkTo(mob, dest, WALK_SPEED);
                yield inDest(mob) ? Status.SUCCESS : Status.RUNNING;
            }
            case ASCEND -> {
                mob.setSprinting(false);
                walkTo(mob, dest, WALK_SPEED);
                double hx = dest.getX() + 0.5 - mob.getX(), hz = dest.getZ() + 0.5 - mob.getZ();
                boolean canJump = mob.onGround() || mob.isInWater() || mob.onClimbable();
                if (canJump && dest.getY() > mob.getY() + 0.5 && hx * hx + hz * hz < 2.5) mob.getJumpControl().jump();
                yield inDest(mob) && mob.getY() >= dest.getY() - 0.2 ? Status.SUCCESS : Status.RUNNING;
            }
            case DESCEND, DOWNWARD -> {
                // Inventar, Leben und Ziel erneut prüfen, bevor er von der Kante tritt.
                if (!dropCommitted && mob.onGround()) {
                    NavContext live = new NavContext(mob);
                    live.allowPlace = ctx.allowPlace;
                    int drop = src.getY() - dest.getY();
                    // Landung im Wasser braucht keinen Eimer
                    if (drop > live.maxFall && !sl.getFluidState(dest).is(net.minecraft.tags.FluidTags.WATER)) {
                        clutch = ClutchPlan.water(live, dest, drop);
                        if (clutch == null) {
                            mob.setSprinting(false);
                            mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
                            yield fail(CompanionTexts.tr("nav.failure.clutch_unsafe"));
                        }
                    }
                }
                if (!mob.onGround()) dropCommitted = true;
                mob.setSprinting(false);
                walkTo(mob, dest, 1.0);
                if (!mob.onGround() && !mob.isInWater() && mob.getY() < src.getY() - 0.3) {
                    // im Fallen waagerecht auf die Landestelle lenken (sonst trägt ihn der Schwung vom Rand weiter)
                    Vec3 rem = Vec3.atBottomCenterOf(dest).subtract(mob.position()).multiply(1, 0, 1);
                    Vec3 hv = rem.lengthSqr() > 1e-4 ? rem.normalize().scale(Math.min(0.2, rem.length() * 0.5)) : Vec3.ZERO;
                    mob.setDeltaMovement(hv.x, mob.getDeltaMovement().y, hv.z);
                }
                if (clutch != null && !mob.onGround()) bucketCatch(mob, sl, dest);
                if (waterPlaced && (mob.onGround() || mob.isInWater()) && mob.getY() < dest.getY() + 0.5) bucketPickUp(mob, sl);
                if ((mob.onGround() || mob.isInWater()) && mob.getBlockX() == dest.getX() && mob.getBlockZ() == dest.getZ()) {
                    if (mob.getY() < dest.getY() + 0.5) {
                        bucketPickUp(mob, sl);
                        if (waterPlaced) yield Status.RUNNING;
                        yield Status.SUCCESS;
                    }
                } else if (mob.onGround() && mob.getY() < dest.getY() - 0.5) yield fail(CompanionTexts.tr("nav.failure.landed_low"));
                yield Status.RUNNING;
            }
            case PILLAR -> {
                if (mob.getBlockY() >= dest.getY() && !mob.isPillarActive() && mob.onGround()) yield Status.SUCCESS;
                int r = mob.pillarStep(sl, CompanionEntity.Pillar.PATH);
                yield r < 0 ? fail(CompanionTexts.tr("nav.failure.cannot_pillar")) : r == 2 ? fail(CompanionTexts.tr("nav.failure.head_blocked")) : Status.RUNNING;
            }
            case PARKOUR -> parkour(mob, ctx);
            case CLIMB_UP -> {
                mob.setSprinting(false);
                walkTo(mob, dest, 0.6);
                mob.getJumpControl().jump(); // springen an der Leiter = hochklettern
                yield mob.getBlockY() >= dest.getY() ? Status.SUCCESS : Status.RUNNING;
            }
            case SWIM_UP -> {
                mob.setSprinting(false);
                walkTo(mob, dest, 0.6);
                swim(mob);
                yield mob.getY() >= dest.getY() - 0.2 ? Status.SUCCESS : Status.RUNNING;
            }
            case CLIMB_DOWN -> {
                mob.setSprinting(false);
                walkTo(mob, dest, 0.6);
                yield mob.getBlockY() <= dest.getY() ? Status.SUCCESS : Status.RUNNING;
            }
        };
    }

    /** Schwimmen wie ein Spieler: Sprung = hochschwimmen (Wasserfall), sonst bei tieferem Ziel absinken. */
    private void swim(CompanionEntity mob) {
        mob.motion.notePathWaterGoal(dest);
        if (dest.getY() >= mob.getY() - 0.2) mob.getJumpControl().jump();
    }

    /** Wassereimer-Landung: kurz vor dem Aufprall Wasser auf den Landeblock (Füße bei land) setzen. */
    private void bucketCatch(CompanionEntity mob, ServerLevel sl, BlockPos land) {
        if (waterPlaced) return;
        double height = mob.getY() - land.getY();
        if (mob.getDeltaMovement().y >= 0 || height > Math.max(2.5, -mob.getDeltaMovement().y * 2 + 0.5)) return;
        NavContext live = new NavContext(mob);
        live.allowPlace = allowClutchPlacement;
        int fall = Math.max(1, (int) Math.ceil(mob.fallDistance + Math.max(0, mob.getY() - land.getY())));
        ClutchPlan plan = ClutchPlan.water(live, land, fall);
        if (plan == null || !plan.inPlacementWindow(mob.position(), mob.getDeltaMovement(),
                mob.getEyeHeight(), mob.getBbWidth() / 2)) return;
        int slot = mob.findItem(net.minecraft.world.item.Items.WATER_BUCKET);
        if (slot < 0) return;
        // Reichweite und Sichtlinie beachten, auch ein Hindernis, das während des Falls dazukam.
        var hit = sl.clip(new net.minecraft.world.level.ClipContext(mob.getEyePosition(),
                new Vec3(land.getX() + 0.5, land.getY() - 0.01, land.getZ() + 0.5),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mob));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(land.below()) || hit.getDirection() != net.minecraft.core.Direction.UP) return;
        if (!mob.placeConfirmed(sl, land, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState())) return;
        mob.getInventory().setItem(slot, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BUCKET));
        mob.swingArm();
        waterPlaced = true;
        waterAt = land;
    }

    /** Nach der Landung das Wasser wieder in den Eimer. */
    private void bucketPickUp(CompanionEntity mob, ServerLevel sl) {
        if (!waterPlaced) return;
        // Warten, bis der Wasserkontakt den Fallschaden zurückgesetzt hat, bevor das Netz entfernt wird.
        if (!mob.onGround() && (!mob.isInWater() || mob.fallDistance > 0)) return;
        int slot = mob.findItem(net.minecraft.world.item.Items.BUCKET);
        if (!ClutchPlan.ownSource(new BlockCache(sl), waterAt)) { waterPlaced = false; return; }
        if (slot < 0 || mob.position().distanceToSqr(Vec3.atBottomCenterOf(waterAt)) > ClutchPlan.REACH * ClutchPlan.REACH) return;
        if (!mob.placeConfirmed(sl, waterAt, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())) return;
        waterPlaced = false;
        mob.getInventory().getItem(slot).shrink(1);
        // Eimerstapel > 1 und Inventar sonst voll: der volle Eimer findet keinen Platz – abwerfen statt verlieren
        var rest = mob.giveToInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
        if (!rest.isEmpty()) mob.spawnAtLocation(sl, rest);
    }

    /** Licht-Fackel (auch Wand-/Seelenfackel), keine Redstone-Fackel. */
    private static boolean plainTorch(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.BaseTorchBlock
                && !(s.getBlock() instanceof net.minecraft.world.level.block.RedstoneTorchBlock);
    }

    private static boolean climb(ServerLevel sl, BlockPos p) {
        return BlockFlags.has(BlockFlags.of(sl.getBlockState(p)), BlockFlags.CLIMB);
    }

    private static boolean openable(ServerLevel sl, BlockPos p) {
        return BlockFlags.has(BlockFlags.of(sl.getBlockState(p)), BlockFlags.DOOR | BlockFlags.GATE);
    }

    /** Geschlossene Holztür/Zauntor öffnen (wie ein Spieler per Rechtsklick). */
    private static void open(CompanionEntity mob, ServerLevel sl, BlockPos p) {
        if (!mob.mayUse(sl, p)) return;
        BlockState s = sl.getBlockState(p);
        if (s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door && openable(sl, p)
                && !s.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) {
            door.setOpen(mob, sl, s, p, true);
            mob.swingArm();
            mob.noteOpenedDoor(p);
        } else if (s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                && !s.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN)) {
            sl.setBlock(p, s.setValue(net.minecraft.world.level.block.FenceGateBlock.OPEN, true), 10);
            mob.swingArm();
            mob.noteOpenedDoor(p);
        }
    }

    private static void walkTo(CompanionEntity mob, BlockPos p, double speed) {
        mob.motion.move(MotionController.Owner.NAVIGATION, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, speed, mob.isSprinting());
    }

    /** Füße im Zielblock; in der Luft (Sprint-Sprung) darf er bis gut einen Block darüber sein. */
    private boolean inDest(CompanionEntity mob) {
        double dy = mob.getY() - dest.getY();
        return mob.getBlockX() == dest.getX() && mob.getBlockZ() == dest.getZ()
                && dy > -0.6 && dy < (mob.onGround() ? 0.6 : mob.isInWater() ? 1.8 : 1.3);
    }

    private Status breakStep(CompanionEntity mob, NavContext ctx, ServerLevel sl, BlockPos b) {
        BlockState s = sl.getBlockState(b);
        int f = BlockFlags.of(s);
        if (!ctx.mayBreakAt(b.getX(), b.getY(), b.getZ(), s, f)) return fail(CompanionTexts.tr("nav.failure.break_forbidden", s.getBlock().getName()));
        mob.setSprinting(false);
        if (!b.equals(breaking)) {
            breaking = b;
            breakDone = 0;
            mob.equipToolFor(s);
            breakTotal = Math.max(1, ctx.breakTicks(s));
        }
        // Die Werkzeuge im Kontext stammen aus der Planung. Ist das Werkzeug inzwischen weg, kaputt oder getauscht,
        // nicht ohne Ertrag abbauen (Erz wäre verloren), sondern abbrechen und neu planen lassen.
        if (s.requiresCorrectToolForDrops() && !mob.getMainHandItem().isCorrectToolForDrops(s)) {
            mob.equipToolFor(s);
            if (!mob.getMainHandItem().isCorrectToolForDrops(s)) {
                sl.destroyBlockProgress(mob.getId(), b, -1);
                breaking = null;
                return fail(CompanionTexts.tr("nav.failure.no_tool", s.getBlock().getName()));
            }
        }
        mob.getLookControl().setLookAt(Vec3.atCenterOf(b));
        if (breakDone % 6 == 0) mob.swingArm();
        sl.destroyBlockProgress(mob.getId(), b, Math.min(9, breakDone * 10 / breakTotal));
        if (++breakDone >= breakTotal) {
            try {
                if (!mob.mineBlock(sl, b, s)) return fail(CompanionTexts.tr("nav.failure.break_unconfirmed"));
            } catch (RuntimeException ex) {
                mob.logError(CompanionTexts.tr("nav.diagnostic.break_error", b.toShortString(), CompanionTexts.literal(ex.toString())));
                return fail(CompanionTexts.tr("nav.failure.break_error"));
            }
            breaking = null;
        }
        return Status.RUNNING;
    }

    /** Höchstens so weit (Blöcke) darf er beim Absprung von der geplanten Stelle weg sein. */
    static final double TAKEOFF_TOLERANCE = 0.15;
    private static final int MAX_COMMIT_TRIES = 3;

    /** Für die Debug-Anzeige: Profil, Flug, Abweichung, Landemarge, Fehlpfad-Risiko, Reichweite. */
    public String debugInfo() {
        if (move.kind != Move.Kind.PARKOUR || jump == null) return "";
        StringBuilder sb = new StringBuilder(jump.describe()).append(" · ").append(phase);
        if (jumpTicks >= 0) sb.append(" t").append(jumpTicks);
        sb.append(String.format(java.util.Locale.ROOT, " · Abw %.2f", maxDev));
        if (actualReach >= 0) sb.append(String.format(java.util.Locale.ROOT, " · echt %.1f", actualReach));
        if (chainLength > 1) sb.append(" · Kette ").append(chainLength);
        return sb.toString();
    }

    /**
     * Parkour wie ein Spieler, Physik wie Vanilla: APPROACH fährt den simulierten Absprungpunkt an (nötigenfalls mit
     * Anlauf), COMMIT rechnet den Sprung ab der echten Stelle mit dem echten Schwung neu und springt mit Vanilla-Kraft,
     * AIRBORNE hält die Eingabe in Sprungrichtung und lenkt nur seitlich zurück in den Korridor, LAND prüft Spalte,
     * Höhe und Boden (sonst neu planen), STABILIZE bremst – außer es folgt gleich der nächste Sprung.
     */
    private Status parkour(CompanionEntity mob, NavContext ctx) {
        ServerLevel sl = ctx.level;
        if (jump == null) {
            jump = JumpSim.plan(ctx, src.getX(), src.getY(), src.getZ(), dest.getX(), dest.getY(), dest.getZ());
            if (jump == null) return fail(CompanionTexts.tr("nav.failure.jump_unsafe"));
        }
        double dirX = jump.dirX, dirZ = jump.dirZ;
        double ax = mob.getX() - jump.takeoffX, az = mob.getZ() - jump.takeoffZ;
        double along = ax * dirX + az * dirZ, lateral = -ax * dirZ + az * dirX;
        Vec3 v = mob.getDeltaMovement();
        double speed = v.x * dirX + v.z * dirZ;
        switch (phase) {
            case APPROACH -> {
                if (!mob.onGround()) {
                    walkPoint(mob, jump.takeoffX, jump.takeoffZ, 0.8);
                    return Status.RUNNING;
                }
                // Anlauf: erst nachdem ein Absprung mit dem echten Schwung nicht aufging (COMMIT) zum Anlauf-Start zurück
                if (commitTries > 0 && jump.runUp > 0 && !runUpDone) {
                    double rx = jump.takeoffX - dirX * jump.runUp, rz = jump.takeoffZ - dirZ * jump.runUp;
                    double ex = mob.getX() - rx, ez = mob.getZ() - rz;
                    if (ex * ex + ez * ez > 0.2 * 0.2 && along > -jump.runUp) {
                        mob.setSprinting(false);
                        walkPoint(mob, rx, rz, 0.8);
                        return Status.RUNNING;
                    }
                    runUpDone = true;
                }
                boolean inWindow = Math.abs(lateral) <= TAKEOFF_TOLERANCE && Math.abs(along) <= TAKEOFF_TOLERANCE;
                // mit Schwung springt er sonst über das Fenster hinweg: dann im letzten Tick davor
                boolean passing = Math.abs(lateral) <= TAKEOFF_TOLERANCE + 0.05 && along >= -2 * TAKEOFF_TOLERANCE
                        && along + Math.max(speed, 0) > TAKEOFF_TOLERANCE;
                if (!inWindow && !passing) {
                    if (along > TAKEOFF_TOLERANCE) {
                        // am Absprung vorbei (steht an der Kante): zurück und neu anlaufen
                        if (++commitTries > MAX_COMMIT_TRIES) return fail(CompanionTexts.tr("nav.failure.takeoff_missed"));
                        runUpDone = false;
                    }
                    // weit hinten: auf den Punkt zu; nah dran und auf der Linie: geradeaus hindurch (Schwung behalten);
                    // nah dran, aber seitlich daneben: langsam auf den Punkt (nicht über die Kante laufen)
                    boolean aligned = Math.abs(lateral) <= TAKEOFF_TOLERANCE;
                    double ahead = along < -0.6 || !aligned ? 0 : 2;
                    // Sprint im Anlauf, wenn er darf: zu viel Schwung nimmt der Absprung zurück (neu gerechnet, gebremst)
                    mob.setSprinting(ctx.canSprint && aligned);
                    walkPoint(mob, jump.takeoffX + dirX * ahead, jump.takeoffZ + dirZ * ahead, aligned || along < -1 ? WALK_SPEED : 0.6);
                    return Status.RUNNING;
                }
                phase = Phase.COMMIT;
                return parkour(mob, ctx);
            }
            case COMMIT -> {
                // ab der echten Stelle mit dem echten Schwung neu prüfen (Welt kann sich geändert haben)
                ctx.blocks.clearCache();
                JumpSim.Plan now = JumpSim.plan(ctx, src.getX(), src.getY(), src.getZ(), dest.getX(), dest.getY(), dest.getZ(),
                        mob.getX(), mob.getZ(), Math.max(0, speed) * 1.1 + 0.02);
                if (now == null) {
                    phase = Phase.APPROACH;
                    runUpDone = false;
                    if (++commitTries > MAX_COMMIT_TRIES) return fail(CompanionTexts.tr("nav.failure.takeoff_unsafe", String.format(java.util.Locale.ROOT, "%.2f/%.2f", along, lateral),
                            String.format(java.util.Locale.ROOT, "%.2f", speed)));
                    walkPoint(mob, jump.takeoffX - dirX * 0.5, jump.takeoffZ - dirZ * 0.5, 0.8);
                    return Status.RUNNING;
                }
                jump = now;
                float yaw = (float) (Math.atan2(dirZ, dirX) * 180 / Math.PI) - 90f;
                mob.setYRot(yaw);
                mob.setYBodyRot(yaw);
                mob.setSprinting(now.sprint);
                double vx = dirX * now.vRun, vz = dirZ * now.vRun;
                if (now.sprint) { vx += dirX * JumpSim.SPRINT_BOOST; vz += dirZ * JumpSim.SPRINT_BOOST; }
                // Absprung wie LivingEntity.jumpFromGround: Sprungkraft nach oben, Sprint-Schub in Blickrichtung
                mob.setDeltaMovement(vx, Math.max(ctx.body.jumpPower(), v.y), vz);
                mob.needsSync = true;
                walkPoint(mob, mob.getX() + dirX * 8, mob.getZ() + dirZ * 8, WALK_SPEED);
                mob.getLookControl().setLookAt(Vec3.atBottomCenterOf(dest).add(0, 1, 0));
                takeoffX = mob.getX();
                takeoffZ = mob.getZ();
                jumpTicks = 0;
                phase = Phase.AIRBORNE;
                return Status.RUNNING;
            }
            case AIRBORNE -> {
                jumpTicks++;
                double lx = mob.getX() - takeoffX, lz = mob.getZ() - takeoffZ;
                double lat = -lx * dirZ + lz * dirX;
                maxDev = Math.max(maxDev, Math.abs(lat));
                if ((mob.onGround() || mob.isInWater()) && jumpTicks >= 2) {
                    phase = Phase.LAND;
                    return parkour(mob, ctx);
                }
                // Eingabe weiter in Sprungrichtung (wie simuliert); nur quer zur Richtung zurück in den Korridor lenken
                mob.setSprinting(jump.sprint);
                walkPoint(mob, mob.getX() + dirX * 8, mob.getZ() + dirZ * 8, WALK_SPEED);
                double vLat = -v.x * dirZ + v.z * dirX, vAl = v.x * dirX + v.z * dirZ;
                double wantLat = Math.max(-0.05, Math.min(0.05, -lat * 0.3));
                double nLat = vLat + Math.max(-0.02, Math.min(0.02, wantLat - vLat));
                mob.setDeltaMovement(dirX * vAl - dirZ * nLat, v.y, dirZ * vAl + dirX * nLat);
                // verfehlt und es geht tief runter: Wassereimer kurz vor dem Aufprall
                if (mob.getY() < Math.min(src.getY(), dest.getY()) - 1 && v.y < 0) missCatch(mob, sl, ctx);
                return jumpTicks > 200 ? fail(CompanionTexts.tr("nav.failure.jump_stalled")) : Status.RUNNING;
            }
            case LAND -> {
                actualReach = Math.sqrt((mob.getX() - takeoffX) * (mob.getX() - takeoffX) + (mob.getZ() - takeoffZ) * (mob.getZ() - takeoffZ));
                if (waterPlaced) bucketPickUp(mob, sl);
                if (waterPlaced) return Status.RUNNING;
                boolean column = mob.getBlockX() == dest.getX() && mob.getBlockZ() == dest.getZ();
                boolean height = Math.abs(mob.getY() - dest.getY()) < 0.6;
                if (!column || !height || !mob.onGround() || !standable(sl, mob.getOnPos())) {
                    mob.setSprinting(false);
                    return fail(CompanionTexts.tr("nav.failure.landing_missed", String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f",
                            mob.getX(), mob.getY(), mob.getZ()), String.format(java.util.Locale.ROOT, "%.1f", actualReach),
                            String.format(java.util.Locale.ROOT, "%.1f", jump.reach)));
                }
                phase = Phase.STABILIZE;
                return parkour(mob, ctx);
            }
            default -> {
                // STABILIZE: Sprint lösen, auf der Landestelle abbremsen; in einer Kette oder geradeaus mit Schwung weiter
                mob.setSprinting(false);
                if (chained || keepMomentum) return Status.SUCCESS;
                walkTo(mob, dest, 0.6);
                return v.horizontalDistanceSqr() < 0.08 * 0.08 || ++stableTicks > 6 ? Status.SUCCESS : Status.RUNNING;
            }
        }
    }

    private static void walkPoint(CompanionEntity mob, double x, double z, double speed) {
        mob.motion.move(MotionController.Owner.SPECIAL, x, mob.getY(), z, speed, mob.isSprinting());
    }

    /** Fehlsprung über einem tiefen Fall: Boden darunter suchen und mit dem Wassereimer abfangen. */
    private void missCatch(CompanionEntity mob, ServerLevel sl, NavContext ctx) {
        if (waterPlaced || ctx.nether) return;
        BlockPos p = mob.blockPosition();
        for (int k = 0; k < Costs.MAX_FALL_TABLE; k++) {
            BlockPos q = p.below(k);
            if (!pass(sl, q) || !sl.getFluidState(q).isEmpty()) {
                if (k == 0 || !sl.getFluidState(q).isEmpty()) return; // Wasser fängt ihn ohnehin
                BlockPos land = q.above();
                double fall = mob.fallDistance + (mob.getY() - land.getY());
                if (ctx.health - ctx.fallDamage((int) Math.ceil(fall)) >= NavContext.MIN_HEALTH_AFTER_FALL) return;
                bucketCatch(mob, sl, land);
                return;
            }
        }
    }
}
