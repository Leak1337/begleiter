package dev.begleiter;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.chat.Sentence;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Owner-selected background work. Saved settings and results are independent of the transient task chain. */
public final class CompanionWork {
    public enum Kind {
        FARM(CompanionZones.Kind.FIELDS, CompanionDuties.Duty.FARM, 8, "feld"),
        ANIMALS(CompanionZones.Kind.ANIMALS, CompanionDuties.Duty.ANIMALS, 4, "tiere"),
        STORAGE(CompanionZones.Kind.STORAGE, CompanionDuties.Duty.STORAGE, 1, "lager"),
        STOCK(null, null, 1, "vorrat"),
        EXPLORE(CompanionZones.Kind.ROAM, CompanionDuties.Duty.EXPLORE, 1, "erkundung"),
        BUILD(CompanionZones.Kind.BASE, null, 16, "bau"),
        TRADE(CompanionZones.Kind.BASE, CompanionDuties.Duty.TRADE, 4, "handel");

        final CompanionZones.Kind area;
        final CompanionDuties.Duty duty;
        final int batch;
        final String german;

        Kind(CompanionZones.Kind area, CompanionDuties.Duty duty, int batch, String german) {
            this.area = area;
            this.duty = duty;
            this.batch = batch;
            this.german = german;
        }

        Component title() { return CompanionTexts.tr("work.kind." + name().toLowerCase(Locale.ROOT)); }

        static Kind parse(String name) {
            for (Kind kind : values()) if (kind.name().equalsIgnoreCase(name) || kind.german.equals(name)) return kind;
            return null;
        }
    }

    enum State {
        PENDING, IDLE, RUNNING, DONE, DANGER, AREA, HOME, MATERIAL, FAILED, PAUSED, OWNER, BUDGET;

        Component title() { return CompanionTexts.tr("work.state." + name().toLowerCase(Locale.ROOT)); }
    }

    private static final int MAX_RETRY = 3600;
    private static final class Entry {
        final Kind kind;
        final String dimension;
        final CompanionZones.Zone zone, roam;
        final BlockPos home;
        final CompanionWorkRecord record = new CompanionWorkRecord();
        int priority = 3;
        int limit;
        int failures, failedRuns, completed, actions;
        long next, lastRun;
        boolean paused;
        State state = State.PENDING;
        Item missingItem;
        int needed, supplyAtBlock;

        Entry(Kind kind, String dimension, CompanionZones.Zone zone, CompanionZones.Zone roam, BlockPos home) {
            this.kind = kind;
            this.dimension = dimension;
            this.zone = zone;
            this.roam = roam;
            this.home = home == null ? null : home.immutable();
            limit = kind.batch;
        }

        long score(long now) {
            // Even low-priority entries eventually run if a busy higher-priority entry keeps winning.
            return priority * 240L + Math.clamp((now - lastRun) / 10, 0, 1800);
        }
    }

    private final CompanionEntity mob;
    private final EnumMap<Kind, Entry> entries = new EnumMap<>(Kind.class);
    private UUID owner;
    private boolean enabled;
    private Entry active;
    private int worked;
    private boolean reachingHome;
    private boolean rawCollected;
    private long nextScan, nextSupplyCheck, started;
    private CompanionBuilder.BuildJob checkedBuild;
    private CompanionZones.Zone checkedBuildZone;
    private boolean buildFits;
    private boolean budgetDenied;

    CompanionWork(CompanionEntity mob) { this.mob = mob; }

    private boolean owned() { return owner != null && owner.equals(mob.getOwnerUuid()); }
    public boolean enabled() { return enabled && owned() && !entries.isEmpty(); }
    public boolean active() { return active != null; }
    public boolean is(Kind kind) { return active != null && active.kind == kind; }
    boolean manages(Kind kind) { return owned() && entries.containsKey(kind); }
    CompanionDuties.Duty duty() { return active == null ? null : active.kind.duty; }
    public CompanionZones.Zone zone() { return active == null ? null : active.zone; }
    public BlockPos approvedHome() { return active == null ? null : active.home; }
    public int remaining() { return active == null ? 0 : Math.max(0, active.limit - worked); }

    public int inputAllowance(Item item) { return active == null ? Integer.MAX_VALUE : active.record.allowance(item); }
    boolean hasInputBudgets() { return active != null && active.record.hasBudgets(); }

    public boolean permitsInput(Item item, int amount) {
        if (amount <= inputAllowance(item)) return true;
        budgetDenied = true;
        return false;
    }

    public void spent(Item item, int amount) { if (active != null) active.record.use(item, amount); }

    public void progress(int amount) {
        if (active != null && amount > 0) worked = Math.min(active.limit, worked + amount);
    }

    public void stockReturning() {
        if (is(Kind.STOCK)) {
            rawCollected = true;
            reachingHome = true;
        }
    }

    Component status(ServerLevel world) {
        if (!owned() || entries.isEmpty()) return CompanionTexts.tr("work.none");
        var message = CompanionTexts.tr(enabled ? "work.enabled" : "work.disabled");
        long now = world.getGameTime();
        for (Entry entry : entries.values()) {
            var state = entry.paused ? State.PAUSED : blocked(entry, world);
            if (state == null) state = entry.state;
            message.append("\n").append(CompanionTexts.tr("work.entry", entry.kind.title(), entry.priority, entry.limit,
                    state.title(), entry.completed, entry.actions));
            if (entry.failedRuns > 0) message.append(" ").append(CompanionTexts.tr("work.failures", entry.failedRuns));
            if (entry == active && worked > 0) message.append(" ").append(CompanionTexts.tr("work.progress", worked, entry.limit));
            if ((state == State.MATERIAL || state == State.BUDGET) && entry.missingItem != null)
                message.append(" ").append(CompanionTexts.tr("work.forecast.blocked", entry.needed, entry.missingItem.getDefaultInstance().getHoverName()));
            if (!entry.paused && entry.next > now && state != State.RUNNING)
                message.append(" ").append(CompanionTexts.tr("work.retry", (entry.next - now + 19) / 20));
        }
        return message;
    }

    Component interpret(ServerPlayer player, String text) {
        if (player == null || !mob.isOwner(player) || text.length() > 180 || !(mob.level() instanceof ServerLevel world)) return null;
        String command = Sentence.of(text).text();
        switch (command) {
            case "dienste", "work list" -> { return status(world); }
            case "dienste an", "work on" -> {
                if (!owned() || entries.isEmpty()) return CompanionTexts.tr("work.none");
                enable();
                return CompanionTexts.tr("work.enabled");
            }
            case "dienste aus", "work off" -> {
                if (!owned() || entries.isEmpty()) return CompanionTexts.tr("work.none");
                suspend();
                return CompanionTexts.tr("work.disabled");
            }
        }
        String[] words = command.split(" ");
        if (words.length < 3 || !("dienst".equals(words[0]) || "work".equals(words[0]))) return null;
        Kind kind = Kind.parse(words[1]);
        if (kind == null) return CompanionTexts.tr("work.unknown");
        String action = words[2];
        if (words.length == 3 && ("hinzufuegen".equals(action) || "add".equals(action))) return configure(world, kind);
        Entry entry = owned() ? entries.get(kind) : null;
        if (entry == null) return CompanionTexts.tr("work.missing_entry", kind.title());
        if ("budget".equals(action)) return budget(world, entry, text);
        if (words.length > 4) return CompanionTexts.tr("work.unknown");
        if (words.length == 4) {
            boolean priority = "prioritaet".equals(action) || "priority".equals(action);
            int max = priority ? 5 : 32;
            if ((!priority && !"limit".equals(action)) || !words[3].matches("[0-9]{1,2}"))
                return CompanionTexts.tr("work.bounds", max);
            int value = Integer.parseInt(words[3]);
            if (value < (!priority && kind == Kind.ANIMALS ? 2 : 1) || value > max)
                return CompanionTexts.tr("work.bounds", max);
            if (priority) entry.priority = value;
            else entry.limit = value;
            return status(world);
        }
        switch (action) {
            case "verlauf", "history" -> { return entry.record.timeline(world.getGameTime()); }
            case "warum", "why" -> {
                return kind == Kind.BUILD ? forecast(entry, world) : status(world).copy().append("\n").append(entry.record.budgetSummary(entry == active));
            }
            case "pausieren", "pause" -> {
                if (active == entry) finish(State.PAUSED, 0);
                entry.paused = true;
            }
            case "weiter", "resume" -> {
                entry.paused = false;
                entry.failures = 0;
                entry.next = 0;
                entry.state = State.PENDING;
                nextScan = 0;
            }
            case "loeschen", "remove" -> {
                if (active == entry) finish(State.PAUSED, 0);
                entries.remove(kind);
                if (entries.isEmpty()) enabled = false;
            }
            default -> { return CompanionTexts.tr("work.unknown"); }
        }
        return status(world);
    }

    private Component budget(ServerLevel world, Entry entry, String text) {
        String[] raw = text.strip().split("\\s+", 5);
        if (raw.length == 3) return entry.record.budgetSummary(entry == active);
        if (raw.length != 5) return CompanionTexts.tr("work.budget.syntax", CompanionWorkRecord.MAX_AMOUNT);
        String operation = Sentence.of(raw[3]).text();
        boolean remove = "loeschen".equals(operation) || "remove".equals(operation);
        int count = operation.matches("[0-9]{1,3}") ? Integer.parseInt(operation) : -1;
        if (!remove && (count < 0 || count > CompanionWorkRecord.MAX_AMOUNT))
            return CompanionTexts.tr("work.budget.syntax", CompanionWorkRecord.MAX_AMOUNT);
        var options = CompanionItemNames.resolve(raw[4]);
        if (options.size() != 1) return CompanionTexts.tr("stock.invalid_item", CompanionTexts.literal(raw[4]));
        Item item = options.getFirst();
        if (remove) entry.record.forgetBudget(item);
        else if (!entry.record.budget(item, count)) return CompanionTexts.tr("work.budget.cap", CompanionWorkRecord.MAX_BUDGETS);
        if (active == entry) finish(State.PENDING, 0);
        if (entry.state == State.BUDGET || entry.state == State.MATERIAL) entry.state = State.PENDING;
        entry.next = world.getGameTime();
        nextScan = 0;
        return entry.record.budgetSummary(false);
    }

    private Container knownSupply(Entry entry, ServerLevel world) {
        return entry.home != null && entry.home.equals(mob.getHome()) && blocked(entry, world) == null
                && !mob.memory.dangerous(world, entry.home, 8) ? mob.orderStorage(world, entry.home) : null;
    }

    private Component forecast(Entry entry, ServerLevel world) {
        var message = CompanionTexts.tr("work.forecast");
        if (mob.build == null || blocked(entry, world) != null || !mob.build.inDimension(world))
            return message.append(" ").append(CompanionTexts.tr("work.forecast.none"));
        var upcoming = CompanionBuilder.upcomingMaterials(world, mob.build, 24);
        if (upcoming.isEmpty()) return message.append(" ").append(CompanionTexts.tr("work.forecast.none"));
        Container box = knownSupply(entry, world);
        if (box == null) message.append(" ").append(CompanionTexts.tr("stock.unverified"));
        int shown = 0;
        for (var need : upcoming.entrySet()) {
            if (shown++ >= 8) break;
            Item item = need.getKey();
            int carried = mob.getInventory().countItem(item);
            int surplus = box == null ? 0 : Math.max(0, box.countItem(item) - mob.stockReserveAt(world, entry.home, item));
            message.append("\n").append(CompanionTexts.tr("work.forecast.item", item.getDefaultInstance().getHoverName(),
                    need.getValue(), carried, surplus, Math.max(0, need.getValue() - carried - surplus)));
        }
        if (upcoming.size() > 8) message.append("\n").append(CompanionTexts.tr("list.more", upcoming.size() - 8));
        return message.append("\n").append(entry.record.budgetSummary(entry == active));
    }

    private Component configure(ServerLevel world, Kind kind) {
        var zone = kind.area == null ? null : mob.zones.get(kind.area, world);
        if (kind.area != null && zone == null) return CompanionTexts.tr("duty.zone_required", kind.area.title());
        BlockPos home = mob.getHome();
        if (kind == Kind.STOCK && home == null) return CompanionTexts.tr("stock.no_home");
        if (!owned()) {
            suspend();
            entries.clear();
            owner = mob.getOwnerUuid();
        }
        Entry previous = entries.get(kind);
        if (active != null && active == previous) finish(State.PAUSED, 0);
        var entry = new Entry(kind, CompanionMemory.dim(world), zone,
                kind == Kind.STOCK ? mob.zones.get(CompanionZones.Kind.ROAM, world) : null,
                kind == Kind.STOCK || kind == Kind.BUILD || kind == Kind.FARM || kind == Kind.ANIMALS ? home : null);
        if (previous != null) {
            entry.priority = previous.priority;
            entry.limit = previous.limit;
            entry.completed = previous.completed;
            entry.actions = previous.actions;
            entry.failedRuns = previous.failedRuns;
            entry.record.copySettings(previous.record, entry.dimension.equals(previous.dimension)
                    && java.util.Objects.equals(entry.zone, previous.zone));
        }
        entries.put(kind, entry);
        enable();
        return CompanionTexts.tr("work.added", kind.title());
    }

    private void enable() {
        enabled = true;
        nextScan = 0;
        if (!mob.hasTasks()) mob.enterWorkMode();
    }

    void suspend() {
        if (active != null) finish(State.PAUSED, 0);
        enabled = false;
    }

    void interrupt() {
        if (active != null) finish(State.PENDING, 80);
    }

    public boolean runnable(ServerLevel world) {
        return active == null || enabled() && !active.paused && worked < active.limit
                && blocked(active, world) == null && !danger(world);
    }

    private boolean danger(ServerLevel world) {
        return mob.getTarget() != null || mob.isRetreating() || mob.getHealth() < 8
                || mob.memory.dangerous(world, mob.blockPosition(), 3);
    }

    private State blocked(Entry entry, ServerLevel world) {
        if (!entry.dimension.equals(CompanionMemory.dim(world))) return State.AREA;
        if (entry.zone != null && !entry.zone.equals(mob.zones.get(entry.zone.kind(), world))) return State.AREA;
        if (entry.kind == Kind.STOCK && !entry.home.equals(mob.getHome())) return State.HOME;
        if (entry.kind != Kind.EXPLORE && (mob.getOwnerAnywhere() == null && !world.getServer().isSingleplayer()
                || !dev.begleiter.world.BuildPermission.ownerMayBuild(mob))) return State.OWNER;
        return null;
    }

    boolean permitsStockGather(ServerLevel world) {
        return is(Kind.STOCK) && active.roam != null && active.roam.equals(mob.zones.get(CompanionZones.Kind.ROAM, world));
    }

    public boolean permitsChange(ServerLevel world, BlockPos pos) {
        if (active == null) return true;
        if (!runnable(world)) return false;
        return switch (active.kind) {
            case FARM, BUILD -> active.zone.contains(pos);
            case STOCK -> mob.currentTask() != null && mob.currentTask().spec() instanceof TaskSpec.Break raw
                    && raw.scoped() && raw.pos().equals(pos) && permitsStockGather(world);
            default -> false;
        };
    }

    boolean permitsStorage(ServerLevel world, BlockPos pos) {
        if (active == null) return true;
        if (blocked(active, world) != null) return false;
        return switch (active.kind) {
            case STORAGE -> active.zone.contains(pos);
            case STOCK, BUILD, FARM, ANIMALS -> pos.equals(active.home) && pos.equals(mob.getHome());
            default -> false;
        };
    }

    public boolean permitsTrade(ServerLevel world, BlockPos pos) {
        return !is(Kind.TRADE) || runnable(world) && active.zone.contains(pos);
    }

    BlockPos farmCandidate(ServerLevel world, BlockPos pos) {
        return is(Kind.FARM) && (active.record.avoids(pos, world.getGameTime()) || mob.gatherMissed(world, pos)
                || mob.memory.dangerous(world, pos, 3)) ? null : pos;
    }

    public void missFarm(ServerLevel world, BlockPos pos) {
        if (is(Kind.FARM)) active.record.miss(pos, world.getGameTime());
    }

    double explorationCost(ServerLevel world, BlockPos pos) {
        if (!is(Kind.EXPLORE)) return 0;
        if (active.record.avoids(pos, world.getGameTime()) || mob.memory.dangerous(world, pos, 5))
            return Double.POSITIVE_INFINITY;
        return active.record.recentVisitCost(pos, world.getGameTime()) + Math.min(64, mob.routes.count(pos)) * 2;
    }

    public void reachedExploration(BlockPos pos) {
        if (!is(Kind.EXPLORE)) return;
        active.record.visit(pos, mob.level().getGameTime());
        progress(1);
    }

    public void taskFailed() {
        if (is(Kind.EXPLORE) && mob.currentTask() != null && mob.currentTask().pos() != null)
            active.record.miss(mob.currentTask().pos(), mob.level().getGameTime());
        if (active != null) finish(State.FAILED, 200);
    }

    public void noBuildMaterial(Item item, int cost, boolean budget) {
        if (active != null) {
            active.missingItem = item;
            active.needed = cost;
            active.supplyAtBlock = !budget && mob.level() instanceof ServerLevel world
                    ? buildSupply(active, world, item) : 0;
            finish(budget ? State.BUDGET : State.MATERIAL, 200);
        }
    }

    public boolean planBuildMaterial(ServerLevel world, Item item, int amount) {
        return queueSupply(world, stack -> stack.is(item), amount, amount);
    }

    boolean queueSupply(ServerLevel world, Predicate<ItemStack> suitable, int minimum, int maximum) {
        BlockPos home = approvedHome();
        Container stock = home == null ? null : mob.workStorage(world, home);
        if (stock == null || mob.memory.dangerous(world, home, 8)) return false;
        for (int slot = 0; slot < stock.getContainerSize(); slot++) {
            ItemStack stack = stock.getItem(slot);
            if (stack.isEmpty() || !suitable.test(stack)) continue;
            Item item = stack.getItem();
            int amount = Math.min(maximum, stock.countItem(item) - mob.stockReserveAt(world, home, item));
            if (amount < minimum || !permitsInput(item, minimum)) continue;
            amount = Math.min(amount, Math.max(0, inputAllowance(item) - mob.getInventory().countItem(item)));
            if (amount < minimum) {
                permitsInput(item, minimum + mob.getInventory().countItem(item));
                continue;
            }
            if (!CompanionPrerequisites.canTake(mob, stock, item, amount)) continue;
            mob.pushFront(Task.take(home, CompanionJobs.id(item), amount));
            return true;
        }
        return false;
    }

    public void buildFinished() {
        if (is(Kind.BUILD) && worked == 0) progress(1);
    }

    private void finish(State state, long delay) {
        Entry entry = active;
        if (entry == null) return;
        if (budgetDenied && (state == State.IDLE || state == State.MATERIAL || state == State.FAILED
                || state == State.DONE && worked < entry.limit))
            state = State.BUDGET;
        if (state == State.BUDGET) delay = worked > 0 ? 100 : 400;
        active = null;
        reachingHome = false;
        rawCollected = false;
        if (worked > 0) {
            entry.completed = Math.min(1_000_000, entry.completed + 1);
            entry.actions = Math.min(1_000_000, entry.actions + worked);
            entry.failures = 0;
        }
        if (state == State.FAILED || state == State.MATERIAL) {
            entry.failedRuns = Math.min(1_000_000, entry.failedRuns + 1);
            entry.failures = Math.min(5, entry.failures + 1);
            delay = Math.min(MAX_RETRY, delay << entry.failures);
        }
        entry.state = state;
        long now = mob.level().getGameTime();
        entry.record.turn(state, worked, now - started, now);
        entry.next = now + delay;
        if (worked > 0) {
            if (entry.kind == Kind.FARM || entry.kind == Kind.STORAGE) wakeMaterialCheck(Kind.STOCK, now);
            if (entry.kind == Kind.STORAGE) {
                wakeMaterialCheck(Kind.BUILD, now);
                wakeMaterialCheck(Kind.FARM, now);
                wakeMaterialCheck(Kind.ANIMALS, now);
            }
        }
        nextScan = now + 10;
        mob.clearManagedTasks();
    }

    private void wakeMaterialCheck(Kind kind, long now) {
        Entry waiting = entries.get(kind);
        if (waiting != null && waiting.state == State.MATERIAL) waiting.next = Math.min(waiting.next, now + 40);
    }

    private void checkNewBuildSupply(ServerLevel world, long now) {
        Entry build = entries.get(Kind.BUILD);
        if (now < nextSupplyCheck || build == null || build.paused || build.state != State.MATERIAL
                || build.missingItem == null || mob.build == null || mob.build.paused) return;
        nextSupplyCheck = now + 80;
        int available = buildSupply(build, world, build.missingItem);
        if (available > build.supplyAtBlock && available >= build.needed) {
            build.supplyAtBlock = available;
            build.next = Math.min(now, build.next);
        }
    }

    private int buildSupply(Entry build, ServerLevel world, Item item) {
        int available = mob.getInventory().countItem(item);
        Container box = knownSupply(build, world);
        if (box != null) available += Math.max(0, box.countItem(item) - mob.stockReserveAt(world, build.home, item));
        return available;
    }

    void tick(ServerLevel world) {
        long now = world.getGameTime();
        if (active != null) {
            State stopped = blocked(active, world);
            if (!enabled() || active.paused) finish(State.PAUSED, 0);
            else if (stopped != null) finish(stopped, 200);
            else if (danger(world)) finish(State.DANGER, 100);
            else if (worked >= active.limit) finish(State.DONE, 80);
            else if (now - started >= 1800) finish(State.FAILED, 200);
            else if (!mob.hasTasks()) {
                if (reachingHome) {
                    reachingHome = false;
                    runStock(world, false);
                } else finish(worked > 0 ? State.DONE : State.FAILED, worked > 0 ? 80 : 200);
            }
            return;
        }
        if (!enabled() || mob.hasTasks() || mob.isPaused() || now < nextScan) return;
        mob.enterWorkMode();
        nextScan = now + 20;
        if (danger(world)) return;
        checkNewBuildSupply(world, now);
        var ordered = entries.values().stream().filter(e -> !e.paused && e.next <= now)
                .sorted(Comparator.<Entry>comparingLong(e -> e.score(now)).reversed().thenComparingLong(e -> e.lastRun)).toList();
        for (Entry entry : ordered) {
            State blocked = blocked(entry, world);
            if (blocked != null) {
                entry.state = blocked;
                entry.next = now + 200;
                continue;
            }
            active = entry;
            worked = 0;
            budgetDenied = false;
            entry.record.start();
            reachingHome = false;
            rawCollected = false;
            started = now;
            entry.lastRun = now;
            entry.state = State.RUNNING;
            if (entry.kind == Kind.STOCK) runStock(world, true);
            else if (entry.kind == Kind.BUILD) runBuild(world);
            else runDuty(world);
            if (active != null || entry.state == State.DONE) return;
        }
    }

    private void runStock(ServerLevel world, boolean moveAllowed) {
        BlockPos home = active.home;
        if (!world.hasChunkAt(home) || mob.memory.dangerous(world, home, 8) || mob.workStorage(world, home) == null) {
            finish(State.HOME, 200);
            return;
        }
        if (mob.distanceToSqr(Vec3.atCenterOf(home)) > 12.25) {
            if (!moveAllowed) finish(State.FAILED, 200);
            else {
                reachingHome = true;
                mob.addTask(Task.moveTo(home, 9));
            }
            return;
        }
        var result = CompanionStockRoutine.run(mob, world, !rawCollected && permitsStockGather(world));
        if (rawCollected && result != CompanionStockRoutine.Result.STORAGE) {
            progress(1);
            finish(State.DONE, 80);
            return;
        }
        switch (result) {
            case FILLED -> { progress(1); finish(State.DONE, 80); }
            case GATHERING -> { }
            case IDLE -> finish(State.IDLE, 200);
            case STORAGE -> finish(State.HOME, 200);
            case MATERIAL -> finish(State.MATERIAL, 200);
        }
    }

    private void runBuild(ServerLevel world) {
        var job = mob.build;
        if (job == null || job.paused) { finish(State.IDLE, 200); return; }
        if (job != checkedBuild || !active.zone.equals(checkedBuildZone)) {
            checkedBuild = job;
            checkedBuildZone = active.zone;
            buildFits = job.inDimension(world) && job.order.stream().allMatch(part -> active.zone.contains(part.pos()))
                    && job.entities.stream().allMatch(entity -> active.zone.contains(BlockPos.containing(entity.pos())));
        }
        if (!buildFits) { finish(State.AREA, 200); return; }
        active.missingItem = null;
        active.needed = 0;
        mob.addTask(Task.build());
    }

    private void runDuty(ServerLevel world) {
        State outcome = CompanionDuties.planManaged(mob, world, active.kind, active.zone, active.limit);
        if (outcome != null) finish(outcome, outcome == State.MATERIAL ? 200 : 160);
    }

    CompoundTag save(long now) {
        CompoundTag root = new CompoundTag();
        if (!owned() || entries.isEmpty()) return root;
        root.store("Owner", UUIDUtil.CODEC, owner);
        root.putBoolean("Enabled", enabled);
        ListTag saved = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag row = new CompoundTag();
            row.putString("Kind", entry.kind.name());
            row.putString("Dimension", entry.dimension);
            if (entry.zone != null) row.store("Zone", CompanionZones.Zone.CODEC, entry.zone);
            if (entry.roam != null) row.store("Roam", CompanionZones.Zone.CODEC, entry.roam);
            if (entry.home != null) row.store("Home", BlockPos.CODEC, entry.home);
            row.putInt("Priority", entry.priority);
            row.putInt("Limit", entry.limit);
            row.putBoolean("Paused", entry.paused);
            row.putString("State", entry == active ? State.PENDING.name() : entry.state.name());
            row.putInt("Failures", entry.failures);
            row.putInt("Completed", Math.min(1_000_000, entry.completed + (entry == active && worked > 0 ? 1 : 0)));
            row.putInt("Actions", Math.min(1_000_000, entry.actions + (entry == active ? worked : 0)));
            row.putInt("FailedRuns", entry.failedRuns);
            row.putLong("Age", Math.clamp(now - entry.lastRun, 0, 20_000));
            row.putLong("Retry", Math.clamp(entry.next - now, 0, MAX_RETRY));
            if (entry.missingItem != null) {
                row.putString("MissingItem", CompanionJobs.id(entry.missingItem));
                row.putInt("Needed", entry.needed);
                row.putInt("SupplyAtBlock", entry.supplyAtBlock);
            }
            entry.record.save(row, now, entry == active ? worked : 0, now - started);
            saved.add(row);
        }
        root.put("Entries", saved);
        return root;
    }

    void load(CompoundTag root, long now) {
        entries.clear();
        active = null;
        reachingHome = false;
        checkedBuild = null;
        nextScan = 0;
        nextSupplyCheck = 0;
        owner = root.read("Owner", UUIDUtil.CODEC).orElse(null);
        enabled = owned() && root.getBooleanOr("Enabled", false);
        if (!owned()) return;
        int rows = 0;
        for (var tag : root.getListOrEmpty("Entries")) {
            if (++rows > Kind.values().length) break;
            if (!(tag instanceof CompoundTag row)) continue;
            Kind kind = Kind.parse(row.getStringOr("Kind", ""));
            String dimension = row.getStringOr("Dimension", "");
            if (kind == null || RegistryIds.parse(dimension) == null) continue;
            var zone = row.read("Zone", CompanionZones.Zone.CODEC)
                    .filter(z -> z.valid() && z.kind() == kind.area && z.dimension().equals(dimension)).orElse(null);
            if (kind.area != null && zone == null) continue;
            var roam = row.read("Roam", CompanionZones.Zone.CODEC)
                    .filter(z -> z.valid() && z.kind() == CompanionZones.Kind.ROAM && z.dimension().equals(dimension)).orElse(null);
            BlockPos home = row.read("Home", BlockPos.CODEC).orElse(null);
            if (kind == Kind.STOCK && home == null) continue;
            Entry entry = new Entry(kind, dimension, zone, roam, home);
            entry.priority = Math.clamp(row.getIntOr("Priority", 3), 1, 5);
            entry.limit = Math.clamp(row.getIntOr("Limit", kind.batch), kind == Kind.ANIMALS ? 2 : 1, 32);
            entry.paused = row.getBooleanOr("Paused", false);
            try { entry.state = State.valueOf(row.getStringOr("State", "PENDING")); }
            catch (IllegalArgumentException ignored) { entry.state = State.PENDING; }
            if (entry.state == State.RUNNING) entry.state = State.PENDING;
            entry.failures = Math.clamp(row.getIntOr("Failures", 0), 0, 5);
            entry.completed = Math.clamp(row.getIntOr("Completed", 0), 0, 1_000_000);
            entry.failedRuns = Math.clamp(row.getIntOr("FailedRuns", 0), 0, 1_000_000);
            entry.actions = Math.clamp(row.getIntOr("Actions", 0), 0, 1_000_000);
            entry.next = now + Math.clamp(row.getLongOr("Retry", 0), 0, MAX_RETRY);
            entry.lastRun = now - Math.clamp(row.getLongOr("Age", 0), 0, 20_000);
            Item missing = ItemIds.resolve(row.getStringOr("MissingItem", ""));
            entry.needed = Math.clamp(row.getIntOr("Needed", 0), 0, 64);
            entry.supplyAtBlock = Math.clamp(row.getIntOr("SupplyAtBlock", 0), 0, 1_000_000);
            entry.missingItem = missing != Items.AIR && entry.needed > 0 ? missing : null;
            entry.record.load(row, now);
            entries.putIfAbsent(kind, entry);
        }
    }
}
