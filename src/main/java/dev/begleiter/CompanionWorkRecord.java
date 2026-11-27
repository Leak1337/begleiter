package dev.begleiter;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Settings and bounded observations for one service and its approved area. */
final class CompanionWorkRecord {
    static final int MAX_BUDGETS = 16;
    static final int MAX_AMOUNT = 512;
    private static final int HISTORY = 8;
    private static final int PLACES = 16;
    private static final int AVOID_TICKS = 1200;

    private record Turn(CompanionWork.State state, int actions, int duration, long at) {}
    private final Map<Item, Integer> budgets = new LinkedHashMap<>();
    private final Map<Item, Integer> spent = new LinkedHashMap<>();
    private final ArrayDeque<Turn> history = new ArrayDeque<>();
    private final Map<BlockPos, Long> misses = new LinkedHashMap<>();
    private final Map<BlockPos, Long> visits = new LinkedHashMap<>();

    boolean hasBudgets() { return !budgets.isEmpty(); }

    boolean budget(Item item, int count) {
        if (!budgets.containsKey(item) && budgets.size() >= MAX_BUDGETS) return false;
        budgets.put(item, count);
        return true;
    }

    void forgetBudget(Item item) { budgets.remove(item); }
    void start() { spent.clear(); }

    int allowance(Item item) {
        Integer ceiling = budgets.get(item);
        return ceiling == null ? Integer.MAX_VALUE : Math.max(0, ceiling - spent.getOrDefault(item, 0));
    }

    void use(Item item, int count) {
        if (count > 0 && budgets.containsKey(item)) spent.merge(item, count, Integer::sum);
    }

    void copySettings(CompanionWorkRecord previous, boolean sameArea) {
        budgets.putAll(previous.budgets);
        history.addAll(previous.history);
        if (sameArea) {
            misses.putAll(previous.misses);
            visits.putAll(previous.visits);
        }
    }

    Component budgetSummary(boolean active) {
        var message = CompanionTexts.tr("work.budget.list");
        if (budgets.isEmpty()) return message.append(" ").append(CompanionTexts.tr("work.budget.none"));
        budgets.forEach((item, count) -> message.append("\n").append(CompanionTexts.tr("work.budget.entry",
                item.getDefaultInstance().getHoverName(), count, active ? allowance(item) : count)));
        return message;
    }

    void turn(CompanionWork.State state, int actions, long duration, long now) {
        if (actions == 0 && (state == CompanionWork.State.IDLE || state == CompanionWork.State.PENDING)) return;
        if (history.size() == HISTORY) history.removeFirst();
        history.addLast(new Turn(state, actions, (int) Math.clamp(duration, 0, 1800), now));
    }

    Component timeline(long now) {
        var message = CompanionTexts.tr("work.history");
        if (history.isEmpty()) return message.append(" ").append(CompanionTexts.tr("work.history.none"));
        for (var turn : history.reversed()) {
            message.append("\n").append(CompanionTexts.tr("work.history.turn", turn.state.title(), turn.actions,
                    seconds(turn.duration), seconds(now - turn.at)));
        }
        return message;
    }

    private static long seconds(long ticks) { return (Math.max(0, ticks) + 19) / 20; }

    void miss(BlockPos pos, long now) { remember(misses, pos, now + AVOID_TICKS); }
    void visit(BlockPos pos, long now) { remember(visits, pos, now); }
    boolean avoids(BlockPos pos, long now) { return misses.getOrDefault(pos, Long.MIN_VALUE) > now; }

    double recentVisitCost(BlockPos pos, long now) {
        double cost = 0;
        for (var visit : visits.entrySet()) {
            if (now - visit.getValue() > 6000) continue;
            cost += Math.max(0, 36 - pos.distSqr(visit.getKey()));
        }
        return cost;
    }

    private static void remember(Map<BlockPos, Long> places, BlockPos pos, long time) {
        places.remove(pos);
        if (places.size() >= PLACES) places.remove(places.keySet().iterator().next());
        places.put(pos.immutable(), time);
    }

    void save(CompoundTag row, long now, int activeActions, long activeDuration) {
        ListTag limits = new ListTag();
        budgets.forEach((item, count) -> {
            var limit = new CompoundTag();
            limit.putString("Item", CompanionJobs.id(item));
            limit.putInt("Limit", count);
            limits.add(limit);
        });
        if (!limits.isEmpty()) row.put("Budgets", limits);
        ListTag turns = new ListTag();
        int skip = activeActions > 0 && history.size() >= HISTORY ? 1 : 0;
        for (var turn : history) {
            if (skip-- > 0) continue;
            turns.add(saveTurn(turn, now));
        }
        if (activeActions > 0) turns.add(saveTurn(new Turn(CompanionWork.State.PENDING, activeActions,
                (int) Math.clamp(activeDuration, 0, 1800), now), now));
        if (!turns.isEmpty()) row.put("History", turns);
        savePlaces(row, "Misses", misses, now, true);
        savePlaces(row, "Visits", visits, now, false);
    }

    private static CompoundTag saveTurn(Turn turn, long now) {
        var tag = new CompoundTag();
        tag.putString("State", turn.state.name());
        tag.putInt("Actions", turn.actions);
        tag.putInt("Duration", turn.duration);
        tag.putLong("Age", Math.clamp(now - turn.at, 0, 240_000));
        return tag;
    }

    void load(CompoundTag row, long now) {
        int count = 0;
        for (var tag : row.getListOrEmpty("Budgets")) {
            if (++count > MAX_BUDGETS) break;
            if (!(tag instanceof CompoundTag limit)) continue;
            Identifier id = ItemIds.parse(limit.getStringOr("Item", ""));
            if (id == null) continue;
            Item item = ItemIds.resolve(id.toString());
            int max = limit.getIntOr("Limit", -1);
            if (item != Items.AIR && max >= 0 && max <= MAX_AMOUNT) budgets.putIfAbsent(item, max);
        }
        count = 0;
        for (var tag : row.getListOrEmpty("History")) {
            if (++count > HISTORY) break;
            if (!(tag instanceof CompoundTag turn)) continue;
            try {
                var state = CompanionWork.State.valueOf(turn.getStringOr("State", ""));
                history.addLast(new Turn(state, Math.clamp(turn.getIntOr("Actions", 0), 0, 32),
                        Math.clamp(turn.getIntOr("Duration", 0), 0, 1800), now - Math.clamp(turn.getLongOr("Age", 0), 0, 240_000)));
            } catch (IllegalArgumentException ignored) { }
        }
        loadPlaces(row, "Misses", misses, now, true);
        loadPlaces(row, "Visits", visits, now, false);
    }

    private static void savePlaces(CompoundTag row, String key, Map<BlockPos, Long> places, long now, boolean future) {
        ListTag stored = new ListTag();
        for (var place : places.entrySet()) {
            long offset = future ? place.getValue() - now : now - place.getValue();
            if (offset < 0 || offset > (future ? AVOID_TICKS : 6000)) continue;
            var tag = new CompoundTag();
            tag.putLong("Pos", place.getKey().asLong());
            tag.putLong("Time", offset);
            stored.add(tag);
        }
        if (!stored.isEmpty()) row.put(key, stored);
    }

    private static void loadPlaces(CompoundTag row, String key, Map<BlockPos, Long> places, long now, boolean future) {
        int count = 0;
        for (var tag : row.getListOrEmpty(key)) {
            if (++count > PLACES) break;
            if (!(tag instanceof CompoundTag place) || !place.contains("Pos")) continue;
            long offset = Math.clamp(place.getLongOr("Time", 0), 0, future ? AVOID_TICKS : 6000);
            places.put(BlockPos.of(place.getLongOr("Pos", 0)), future ? now + offset : now - offset);
        }
    }
}
