package dev.begleiter;

import java.util.UUID;

import dev.begleiter.CompanionEntity.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Baustein Modus: Besitzer, Folgen/Warten/Frei spielen/Lagerverwalter/Daueraufgabe, Pause (Spieler und Debug),
 * Lagerkiste, Portal und Mine samt Speichern (gleiche Schlüssel wie zuvor) und den Abbrüchen durch den Spieler.
 * Die Entity behält die bisherigen öffentlichen Methoden als Weiterleitungen.
 */
public final class CompanionMode {
    private final CompanionEntity e;
    UUID owner;
    BlockPos home;
    boolean staying;
    boolean keeper;
    boolean paused;
    boolean autonomous = true;
    private String dutyDimension;
    /** Daueraufgabe an dutyPos oder im zugehörigen Arbeitsbereich; null = keine. */
    CompanionDuties.Duty duty;
    private BlockPos dutyPos;
    int dutyNextTick;
    /** Netherportal in der Oberwelt (Bauplatz-Ecke). */
    BlockPos portal;
    /**
     * Seine Mine: tiefster Stollenpunkt (Oberwelt), dauerhaft gemerkt – dahin geht er beim nächsten Graben zurück.
     * Gilt nur in der Welt, in der er sie gegraben hat (Seed): der Kern nimmt sein Gedächtnis in andere Welten mit.
     */
    private BlockPos mine;
    private long mineSeed;
    private boolean debugFrozen;
    int debugStepTicks;
    private Task trackedTask;
    private int trackedSince;
    /** Auftrag erhalten und anhalten, ohne einen Materialmangel zu behaupten. */
    Component pausedReason = Component.empty();

    CompanionMode(CompanionEntity e) { this.e = e; }

    private boolean inOverworld() { return e.level().dimension() == Level.OVERWORLD; }

    /** Lager, Haus und Portal liegen in der Oberwelt – in anderen Dimensionen gibt es sie nicht. */
    BlockPos home() { return inOverworld() ? home : null; }
    BlockPos portal() { return inOverworld() ? portal : null; }

    /** Pausiert (Spieler oder Debug-Menü); ein Debug-Einzelschritt hebt die Pause für seine Ticks auf. */
    boolean isPaused() { return paused || (debugFrozen && debugStepTicks <= 0); }
    boolean isDebugFrozen() { return debugFrozen; }
    void setDebugFrozen(boolean frozen) { debugFrozen = frozen; if (!frozen) debugStepTicks = 0; }
    void debugStep(int ticks) { debugFrozen = true; debugStepTicks = ticks; }

    /** Einmal je Server-Tick: Debug-Einzelschritt herunterzählen und merken, seit wann die aktuelle Aufgabe läuft. */
    void tick() {
        if (debugStepTicks > 0) debugStepTicks--;
        Task now = e.currentTask();
        if (now != trackedTask) { trackedTask = now; trackedSince = e.tickCount; }
    }

    /** Sekunden, die die aktuelle Aufgabe schon läuft. */
    int actionSeconds() { return (e.tickCount - trackedSince) / 20; }

    BlockPos mine() {
        // wie Lager und Portal nur in der Oberwelt: im Nether lägen dieselben Koordinaten woanders
        return mine != null && inOverworld() && e.level() instanceof ServerLevel sl && sl.getSeed() == mineSeed ? mine : null;
    }

    void setMine(BlockPos p) {
        if (p != null && !inOverworld()) return; // Stollen im Nether/End überschreibt die Oberwelt-Mine nicht
        mine = p == null ? null : p.immutable();
        if (p != null && e.level() instanceof ServerLevel sl) mineSeed = sl.getSeed();
    }

    // ------------------------------------------------------------------ Moduswechsel

    void follow() { e.journey.suspend(); e.work.suspend(); staying = false; keeper = false; paused = false; autonomous = false; duty = null; }
    void freeRoam() { e.journey.suspend(); e.work.suspend(); staying = false; keeper = false; paused = false; autonomous = true; duty = null; }
    void stay() { e.journey.suspend(); e.work.suspend(); staying = true; keeper = false; autonomous = false; duty = null; e.getNavigation().stop(); }
    CompanionDuties.Duty duty() { return e.work.active() ? e.work.duty() : duty; }

    void enterWorkMode() {
        e.journey.suspend();
        if (autonomous && e.goal != null) {
            e.planFeedback.completeIfIdle(e);
            e.goal = null;
        }
        staying = false;
        keeper = false;
        autonomous = false;
        duty = null;
    }

    BlockPos dutyPos() {
        if (e.work.active()) return e.work.duty() == null || e.work.zone() == null ? null : e.work.zone().center();
        if (duty == null || !(e.level() instanceof ServerLevel sl) || !CompanionMemory.dim(sl).equals(dutyDimension)) return null;
        var kind = CompanionZones.kindFor(duty);
        var z = kind == null ? null : e.zones.get(kind, sl);
        return z == null ? dutyPos : z.center();
    }

    /** Daueraufgabe übernehmen: bleibt an {@code pos} und arbeitet dort, sobald er frei ist. */
    void startDuty(CompanionDuties.Duty d, BlockPos pos) {
        e.journey.suspend();
        e.work.suspend();
        staying = false; keeper = false; paused = false; autonomous = false;
        duty = d; dutyPos = pos.immutable(); dutyDimension = CompanionMemory.dim((ServerLevel) e.level());
        dutyNextTick = e.tickCount; e.dutyHintGiven = false; e.dutyStorageFingerprint = Long.MIN_VALUE;
    }

    boolean makeKeeper() {
        if (home == null) return false;
        e.journey.suspend();
        e.work.suspend();
        keeper = true; staying = false; autonomous = false; duty = null; return true;
    }

    String text() {
        return keeper ? "storage keeper" : staying ? "waiting" : autonomous ? "playing freely"
                : duty != null ? "duty " + duty.name().toLowerCase(java.util.Locale.ROOT) : "following the owner";
    }

    // ------------------------------------------------------------------ Abbrechen, Pausieren, Fortsetzen

    /** Interne Unterbrechung: Warteschlange leeren, ein offener Bau bleibt für die spätere Wiederaufnahme erhalten. */
    void cancelAll() {
        e.journey.interruptPlan();
        e.work.interrupt();
        SchematicLoader.cancel(e); // noch ladendes Schematic gilt nicht mehr
        e.planFeedback.finish(e, true);
        e.activeSupplyResource = e.activeSupplyApproach = null;
        e.queue().clear(); paused = false; e.huntTarget = null; e.setTarget(null); e.scaffolding.reset(); e.goal = null;
        pausedReason = Component.empty();
        e.releaseFetch();
        e.getNavigation().stop();
    }

    /** Ausdrücklicher Abbruch durch den Spieler: Warteschlange leeren und einen offenen Bau verwerfen. */
    void cancelOrders() {
        e.requestDialogue.clear();
        cancelAll();
        e.build = null;
        e.buildRetryAt = 0;
        e.nav.stop();
    }

    /**
     * Moduswechsel durch den Spieler (Folgen, Warten, …): Warteschlange leeren und einen offenen Bau
     * anhalten, damit er nicht von selbst weiterläuft. Liefert einen Hinweis für die Antwort oder einen leeren Text.
     */
    Component holdOrders() {
        e.requestDialogue.clear();
        cancelAll();
        if (e.build == null || e.build.paused) return Component.empty();
        e.build.paused = true;
        return CompanionTexts.tr("reply.build.held", CompanionTexts.buildName(e.build.name));
    }

    void resume() {
        if (paused && e.hasRecipeRequest()) e.orders.restartBlockedRecipeSteps();
        paused = false;
        pausedReason = Component.empty();
    }

    void holdRecipeOrders(Component reason) {
        e.getNavigation().stop();
        e.nav.stop();
        paused = true;
        pausedReason = reason.copy();
    }

    void pauseWork(Component reason) {
        // Beim Spielziel sagt niemand "@ weiter": nie pausieren, sondern den Schritt scheitern lassen und neu planen
        if (e.journey.active()) {
            e.setUiFeedback(reason);
            e.logWarn(reason);
            e.journey.failAutonomous();
            return;
        }
        if (e.work.active()) {
            e.logWarn(reason);
            e.work.taskFailed();
            return;
        }
        paused = true;
        pausedReason = reason.copy();
        e.tell(reason);
    }

    /** Rechtsklick des Spielers: Besitz übernehmen (Einzelspieler), Menü öffnen oder zwischen Folgen und Frei spielen wechseln. */
    InteractionResult interact(Player player, InteractionHand hand) {
        // Client kennt den Besitzer nicht: trotzdem SUCCESS, sonst wird die Interaktion für die zweite Hand wiederholt
        if (e.level().isClientSide()) return InteractionResult.SUCCESS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        // Einzelspieler: ist der alte Besitzer nicht da (z.B. anderer Name im Test-Client), übernimmt der Spieler ihn.
        // Nicht in einer für LAN geöffneten Welt: dort könnte sonst ein Gast den Begleiter eines anderen übernehmen.
        if (!e.isOwner(player) && e.getOwnerAnywhere() == null && e.level() instanceof ServerLevel sl
                && sl.getServer().isSingleplayer() && !sl.getServer().isPublished()) {
            e.setOwner(player.getUUID());
            player.sendSystemMessage(CompanionTexts.prefixed(CompanionTexts.tr("reply.owned")));
        }
        if (!e.isOwner(player)) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer sp) e.openPanel(sp, CompanionMenu.Page.MAIN);
        } else if (staying || keeper || autonomous || duty != null) {
            Component held = holdOrders();
            follow();
            player.sendSystemMessage(CompanionTexts.prefixed(CompanionTexts.tr("reply.follow")).append(held));
        } else {
            Component held = holdOrders();
            freeRoam();
            player.sendSystemMessage(CompanionTexts.prefixed(CompanionTexts.tr("reply.free")).append(held));
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    // ------------------------------------------------------------------ Speichern

    void save(ValueOutput out) {
        if (owner != null) out.store("Owner", UUIDUtil.CODEC, owner);
        if (home != null) out.store("Home", BlockPos.CODEC, home);
        out.putBoolean("Staying", staying);
        out.putBoolean("Keeper", keeper);
        out.putBoolean("Autonomous", autonomous);
        if (duty != null && dutyPos != null) { out.putString("Duty", duty.name()); out.store("DutyPos", BlockPos.CODEC, dutyPos); out.putString("DutyDimension", dutyDimension); }
        if (mine != null) { out.store("Mine", BlockPos.CODEC, mine); out.putLong("MineSeed", mineSeed); }
        if (portal != null) out.store("Portal", BlockPos.CODEC, portal);
    }

    void load(ValueInput in) {
        owner = in.read("Owner", UUIDUtil.CODEC).orElse(null);
        home = in.read("Home", BlockPos.CODEC).orElse(null);
        staying = in.getBooleanOr("Staying", false);
        keeper = in.getBooleanOr("Keeper", false);
        autonomous = in.getBooleanOr("Autonomous", true);
        dutyDimension = in.getStringOr("DutyDimension", e.level().dimension().identifier().toString());
        dutyPos = in.read("DutyPos", BlockPos.CODEC).orElse(null);
        duty = dutyPos == null ? null : CompanionDuties.Duty.byName(in.getStringOr("Duty", ""));
        if (duty != null) { autonomous = false; staying = false; keeper = false; }
        mine = in.read("Mine", BlockPos.CODEC).orElse(null);
        mineSeed = in.getLongOr("MineSeed", 0);
        portal = in.read("Portal", BlockPos.CODEC).orElse(null);
    }

    /** Teil des Kern-Gedächtnisses (überdauert den Tod): Lager, Portal, Mine, Modus. */
    void saveMemory(CompoundTag t) {
        if (home != null) t.putLong("Home", home.asLong());
        if (portal != null) t.putLong("Portal", portal.asLong());
        if (mine != null) { t.putLong("Mine", mine.asLong()); t.putLong("MineSeed", mineSeed); }
        t.putBoolean("Autonomous", autonomous);
        t.putBoolean("Keeper", keeper);
    }

    void loadMemory(CompoundTag t) {
        if (t.contains("Home")) home = BlockPos.of(t.getLongOr("Home", 0));
        if (t.contains("Portal")) portal = BlockPos.of(t.getLongOr("Portal", 0));
        if (t.contains("Mine")) { mine = BlockPos.of(t.getLongOr("Mine", 0)); mineSeed = t.getLongOr("MineSeed", 0); }
        autonomous = t.getBooleanOr("Autonomous", true);
        keeper = t.getBooleanOr("Keeper", false) && home != null;
    }
}
