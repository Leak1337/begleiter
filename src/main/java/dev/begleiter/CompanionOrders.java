package dev.begleiter;

import java.util.ArrayList;
import java.util.List;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Baustein Aufträge in der Warteschlange: Rezept- und Lageraufträge (Zwischenstände, Neuplanung, Reservierung der
 * Zutaten), Befehls-Aufträge (Plattform, Wand, Räumen, Aufräumen) und das Verwerfen veralteter Grabschritte. Ohne
 * eigenen Zustand; die Warteschlange selbst gehört der Entity und wird dort gespeichert.
 */
public final class CompanionOrders {
    private final CompanionEntity e;

    CompanionOrders(CompanionEntity e) { this.e = e; }

    public boolean enqueueHomeOrder(TaskSpec.Request order, boolean urgent) {
        e.work.interrupt();
        if (e.journey.running()) {
            e.journey.interruptPlan();
            e.clearJourneyTasks();
        }
        var queued = new ArrayList<>(e.queue());
        int firstWaiting = queued.size();
        int waiting = 0;
        int amount = order.count();
        for (int index = 0; index < queued.size(); index++) {
            if (!(queued.get(index).spec() instanceof TaskSpec.Request existing) || existing.phase() != 5) continue;
            firstWaiting = Math.min(firstWaiting, index);
            if (existing.item().equals(order.item()) && existing.pos().equals(order.pos())
                    && java.util.Objects.equals(existing.fuel(), order.fuel())) {
                amount = Math.max(amount, existing.count());
                queued.remove(index--);
            } else waiting++;
        }
        if (waiting >= 8) return false;
        if (e.goal != null || scopedStockWork()) {
            e.cancelAll();
            queued.clear();
            firstWaiting = 0;
        }
        var executor = e.nav.executor();
        if (urgent && e.currentTask() != null && e.currentTask().type() == TaskType.BUILD
                && (executor == null || executor.safeToCancel(e))) firstWaiting = 0;
        var combined = new TaskSpec.Request(order.item(), amount, false, 5, 0, null, order.pos(), order.fuel());
        queued.add(urgent ? firstWaiting : queued.size(), new Task(combined));
        e.queue().clear();
        e.queue().addAll(queued);
        return true;
    }

    /** Läuft ein Rezept-Auftrag? Seine gespeicherten Zwischenstände stehen hinter allen Vorbereitungsaufgaben. */
    boolean hasRecipeRequest() {
        for (Task task : e.queue()) {
            if (task.spec() instanceof TaskSpec.Request r)
                return r.phase() != 5 && r.phase() != 0 || task == e.currentTask();
        }
        return false;
    }

    public String preferredOrderFuel() {
        for (Task task : e.queue()) {
            if (task.spec() instanceof TaskSpec.Request r && r.phase() != 4) return r.fuel();
        }
        return null;
    }

    boolean scopedStockWork() {
        Task task = e.currentTask();
        return task != null && (task.spec() instanceof TaskSpec.Break b && b.scoped()
                || task.spec() instanceof TaskSpec.MoveTo m && m.stockReturn());
    }

    public boolean isPlanningHomeOrder() {
        for (Task task : e.queue()) {
            if (task.spec() instanceof TaskSpec.Request r && r.phase() != 4) return r.phase() == 6;
        }
        return false;
    }

    /** Neue Herstellung nur für den Fehlbestand bis zum nächsten Lieferzwischenstand planen. */
    boolean recoverRecipeProduction(Component reason) {
        int preparation = 0;
        for (Task queued : e.queue()) {
            if (queued.spec() instanceof TaskSpec.Request request && request.phase() != 4) {
                if (request.phase() == 6) {
                    for (int i = 0; i < preparation; i++) e.queue().removeFirst();
                    var retry = new TaskSpec.Request(request.item(), request.count(), false, 6,
                            request.baseline(), null, request.pos(), request.fuel());
                    e.replaceCurrent(List.of(new Task(retry, queued.tries())));
                    e.getNavigation().stop();
                    e.nav.stop();
                    e.setUiFeedback(reason);
                    return true;
                }
                if (request.phase() != 1 || request.materials()) return false;
                int missing = request.count() + request.baseline() - CompanionCraft.count(e, request.item());
                List<Task> work = List.of();
                if (missing > 0) {
                    var plan = queued.tries() < 3
                            ? CompanionPlanner.request(e, ItemIds.resolve(request.item()), missing, false) : null;
                    if (plan == null || plan.work().tasks().isEmpty()) {
                        pauseRecipeRequest(CompanionTexts.tr("request.production_blocked", request.count(),
                                CompanionTexts.item(request.item()), reason));
                        return true;
                    }
                    work = plan.work().tasks();
                }
                for (int i = 0; i < preparation; i++) e.queue().removeFirst();
                if (missing > 0) {
                    List<Task> replacement = new ArrayList<>(work);
                    replacement.add(queued.retry());
                    e.replaceCurrent(replacement);
                    Component status = CompanionTexts.tr("request.replanning", missing, CompanionTexts.item(request.item()));
                    e.setUiFeedback(status);
                    e.logInfo(status);
                    e.tell(status);
                }
                e.getNavigation().stop();
                e.nav.stop();
                return true;
            }
            preparation++;
        }
        return false;
    }

    /** Zwischenstände behalten; nur der fehlende Vorrat wird aus dem tatsächlichen Inventar neu geplant. */
    public void recoverRecipeSource(String item, int target, BlockPos excludedSource) {
        int attempts = 0;
        String canonical = ItemIds.canonicalOrOriginal(item);
        for (Task queued : e.queue()) {
            if (queued.spec() instanceof TaskSpec.Request r && r.phase() == 4 && r.item().equals(canonical)) {
                attempts = Math.max(attempts, queued.tries());
                target = Math.max(target, r.count());
            }
        }
        e.replaceCurrent(List.of(new Task(new TaskSpec.Request(canonical, target, false, 4, 0, excludedSource), attempts)));
        e.getNavigation().stop();
        e.nav.stop();
        Component status = CompanionTexts.tr("request.replanning", Math.max(0, target - CompanionCraft.count(e, canonical)),
                CompanionTexts.item(canonical));
        e.setUiFeedback(status);
        e.logInfo(status);
        e.tell(status);
    }

    /** Ein Fehlschlag ist kein Abbruch: Warteschlange samt Herstell- und Lieferschritten bleibt erhalten. */
    void pauseRecipeRequest(Component reason) {
        e.getNavigation().stop();
        e.nav.stop();
        e.setUiFeedback(reason);
        e.logWarn(reason);
        e.pauseWork(reason);
    }

    /** Für einen offenen Rezept-Auftrag vorgesehene Items nicht von selbst anlegen, wegwerfen oder verbrauchen. */
    boolean reservedForRecipe(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (Task task : e.queue()) {
            if (task.spec() instanceof TaskSpec.Request request) {
                if ((request.phase() == 1 || request.phase() == 2 || request.phase() == 4 || request.homeOrder()
                        || !request.materials() && (request.phase() == 0 || request.phase() == 3))
                        && ItemIds.matches(stack, request.item())) return true;
                if (request.phase() == 0 && e.level() instanceof ServerLevel sl)
                    for (var recipe : CompanionRecipes.processingFor(sl, ItemIds.resolve(request.item())))
                        if (recipe.ingredients().stream().anyMatch(ingredient -> ingredient.test(stack))) return true;
            } else if (task.spec() instanceof TaskSpec.Process process && e.level() instanceof ServerLevel sl) {
                var recipe = CompanionRecipes.processingRecipe(sl, process.recipe());
                if (recipe != null && recipe.ingredients().stream().anyMatch(ingredient -> ingredient.test(stack))) return true;
            }
        }
        return false;
    }

    /** Während der Pause kann der Spieler die blockierte Quelle repariert haben: blockierte Rezeptschritte neu starten. */
    void restartBlockedRecipeSteps() {
        List<Task> resumed = e.queue().stream().map(task -> task.spec() instanceof TaskSpec.Request r && (r.phase() == 1 || r.phase() == 4 || r.homeOrder())
                ? new Task(new TaskSpec.Request(r.item(), r.count(), r.materials(), r.phase(), r.baseline(), null, r.pos(), r.fuel())) : task).toList();
        e.queue().clear();
        e.queue().addAll(resumed);
    }

    // ------------------------------------------------------------------ Befehls-Aufträge

    public void queueTidy() {
        e.addTask(Task.collect(true));
        e.addTask(Task.deliver(false));
        e.setPaused(false);
    }

    public int queuePlatform(ServerPlayer p, Block b, int w, int d) {
        Direction f = p.getDirection(), r = f.getClockWise();
        BlockPos origin = p.blockPosition().below().relative(f, 2);
        for (int j = 0; j < d; j++)
            for (int i = 0; i < w; i++)
                e.addTask(Task.place(origin.relative(f, j).relative(r, i - w / 2), b));
        e.setPaused(false);
        return w * d;
    }

    public int queueWall(ServerPlayer p, Block b, int w, int h) {
        Direction f = p.getDirection(), r = f.getClockWise();
        BlockPos origin = p.blockPosition().relative(f, 2);
        for (int y = 0; y < h; y++)
            for (int i = 0; i < w; i++)
                e.addTask(Task.place(origin.above(y).relative(r, i - w / 2), b));
        e.setPaused(false);
        return w * h;
    }

    public int queueClear(ServerPlayer p, int w, int h, int d) {
        if (!(e.level() instanceof ServerLevel sl)) return 0;
        Direction f = p.getDirection(), r = f.getClockWise();
        BlockPos origin = p.blockPosition().relative(f, 1);
        int n = 0;
        for (int y = h - 1; y >= 0; y--)
            for (int j = 0; j < d; j++)
                for (int i = 0; i < w; i++) {
                    BlockPos pos = origin.above(y).relative(f, j).relative(r, i - w / 2);
                    BlockState found = sl.getBlockState(pos);
                    if (!found.isAir()) {
                        // Nur den Block abbauen, der beim Befehl dort stand: Was der Spieler danach hinstellt
                        // (z.B. eine Kiste), bleibt stehen.
                        e.addTask(Task.breakAt(pos, found.getBlock()));
                        n++;
                    }
                }
        if (n > 0) e.addTask(Task.deliver(false));
        e.setPaused(false);
        return n;
    }

    /**
     * Beim Graben in eine Höhle gefallen: liegen die nächsten Grab-/Laufschritte deutlich über ihm, passen sie nicht
     * mehr (sonst baut er sich zur alten Treppe hoch). Alles bis zur nächsten MINE-Aufgabe verwerfen -> plant von hier.
     */
    public boolean droppedBelowDigPlan() {
        var tasks = e.queue();
        Task cur = tasks.peekFirst();
        if (cur == null || cur.pos() == null || cur.pos().getY() <= e.getBlockY() + 3 || !e.onGround()) return false;
        if (cur.type() != TaskType.MOVE_TO && cur.type() != TaskType.BREAK && cur.type() != TaskType.PILLAR
                && cur.type() != TaskType.PLACE) return false;
        // gezielt angesteuertes Erz (auch über ihm) ist kein veralteter Grabschritt
        if (cur.type() == TaskType.BREAK && cur.block() != null && cur.block().defaultBlockState().is(BlockTags.ORES)) return false;
        boolean mine = false;
        for (Task q : tasks) if (q.type() == TaskType.MINE) { mine = true; break; }
        if (!mine) return false;
        // Gerüst/Bauen für den Hausbau o.ä. nicht anfassen: nur, wenn zwischen hier und MINE nichts anderes kommt
        for (Task q : tasks) {
            if (q.type() == TaskType.MINE) break;
            if (q.type() != TaskType.MOVE_TO && q.type() != TaskType.BREAK && q.type() != TaskType.PILLAR
                    && q.type() != TaskType.PLACE) return false;
        }
        while (tasks.peekFirst() != null && tasks.peekFirst().type() != TaskType.MINE) tasks.pollFirst();
        e.logInfo(CompanionTexts.tr("log.mine.fallen", e.getBlockY()));
        return true;
    }

    // ------------------------------------------------------------------ Speichern und Wiederaufnahme

    /** Nur Spieleraufträge überdauern den Neustart; eigene Pläne (goal != null) plant er danach neu. */
    void save(ValueOutput out) {
        var tasks = e.queue();
        if (e.goal == null && !e.work.active() && !e.journey.running() && !tasks.isEmpty()) {
            CompoundTag queue = dev.begleiter.task.TaskQueueCodec.save(tasks, e.mode.paused);
            if (queue != null) out.store("TaskQueueV2", CompoundTag.CODEC, queue);
            else e.logWarn(CompanionTexts.tr("queue.save.too_many", tasks.size()));
        }
        if (e.mode.paused) out.store("PausedReason", ComponentSerialization.CODEC, e.mode.pausedReason);
    }

    void load(ValueInput in) {
        e.queue().clear();
        // V1 (alte Welten) bleibt lesbar; gespeichert wird nur noch V2
        e.loadPart("TaskQueue", () -> in.read("TaskQueueV2", CompoundTag.CODEC).or(() -> in.read("TaskQueueV1", CompoundTag.CODEC))
                .ifPresent(this::restore));
        e.mode.pausedReason = Component.empty();
        e.loadPart("PausedReason", () -> e.mode.pausedReason = in.read("PausedReason", ComponentSerialization.CODEC).orElse(Component.empty()));
    }

    /** Gespeicherte Spieleraufträge übernehmen; die vorderste Aufgabe startet frisch und prüft die Welt selbst. */
    private void restore(CompoundTag queue) {
        var loaded = dev.begleiter.task.TaskQueueCodec.load(queue);
        if (loaded.problem() != null) {
            resumeProblem(CompanionTexts.tr("queue.load.rejected", loaded.problemText()));
            return;
        }
        var tasks = e.queue();
        tasks.addAll(loaded.tasks());
        e.mode.paused = loaded.paused();
        if (loaded.skipped() > 0) e.logInfo(CompanionTexts.tr("queue.load.skipped", loaded.skipped()));
        if (!tasks.isEmpty()) e.logInfo(CompanionTexts.tr("queue.load.resumed", tasks.size(), e.mode.paused ? CompanionTexts.tr("queue.load.paused") : Component.empty()));
    }

    /**
     * Gemeinsame Wiederaufnahme nach dem Laden, in fester Reihenfolge nach Inventar, Bauauftrag, Gedächtnis und Queue:
     * Widersprüche werden aufgelöst statt blind weitergearbeitet. Unklare Zustände halten sichtbar an (pausiert, mit
     * Meldung im Protokoll und in der Menüzeile); es wird nichts erfunden und kein Schritt doppelt gebucht – laufende
     * Handler werden nie gespeichert, jede Aufgabe prüft die Welt beim Start selbst.
     */
    void reconcileAfterLoad(int dataVersion) {
        var tasks = e.queue();
        if (dataVersion > CompanionEntity.DATA_VERSION) {
            if (!tasks.isEmpty() || e.build != null) e.mode.paused = true;
            resumeProblem(CompanionTexts.tr("resume.newer_data", dataVersion, CompanionEntity.DATA_VERSION));
        }
        // Bauaufgaben ohne Bauplan (Bauteil unlesbar): nicht stillschweigend überspringen und Folgeschritte ausführen
        boolean hadBuild = tasks.removeIf(t -> t.type() == TaskType.BUILD && e.build == null);
        if (hadBuild) {
            if (!tasks.isEmpty()) e.mode.paused = true;
            resumeProblem(CompanionTexts.tr("resume.build_lost"));
        }
        // höchstens eine Bauaufgabe: doppelte aus alten Ständen würden denselben Plan mehrfach anstoßen
        boolean seenBuild = false;
        for (var it = tasks.iterator(); it.hasNext(); ) {
            if (it.next().type() != TaskType.BUILD) continue;
            if (seenBuild) it.remove();
            seenBuild = true;
        }
    }

    /** Wiederaufnahmeproblem: ins Protokoll und in die Menüzeile, ohne beim Laden den Chat zu fluten. */
    private void resumeProblem(Component message) {
        e.logWarn(message);
        e.setUiFeedback(message);
    }
}
