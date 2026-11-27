package dev.begleiter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.begleiter.chat.ChatCount;
import dev.begleiter.chat.Sentence;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Eine vom Besitzer gesetzte Vorratsregel für das Heimatlager. */
record CompanionStockRoutine(Identifier item, int target, UUID owner, BlockPos home, boolean collect) {
    CompanionStockRoutine(Identifier item, int target, UUID owner, BlockPos home) {
        this(item, target, owner, home, false);
    }

    static final Codec<CompanionStockRoutine> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("item").forGetter(CompanionStockRoutine::item),
            Codec.intRange(1, ChatCount.MAX).fieldOf("target").forGetter(CompanionStockRoutine::target),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(CompanionStockRoutine::owner),
            BlockPos.CODEC.fieldOf("home").forGetter(CompanionStockRoutine::home),
            Codec.BOOL.optionalFieldOf("collect", false).forGetter(CompanionStockRoutine::collect)
    ).apply(instance, CompanionStockRoutine::new));

    static final int MAX_RULES = 8;
    static final Codec<List<CompanionStockRoutine>> RULES_CODEC = CODEC.listOf(0, MAX_RULES);
    private static final Pattern SET = Pattern.compile(
            "^(?:halte (?:hier )?(?:immer )?(?:mindestens )?(\\d+) (.+?) auf vorrat|"
                    + "keep (?:at least )?(\\d+) (.+?) in stock)[.!?]?$");
    private static final Pattern REMOVE = Pattern.compile(
            "^(?:vorratsregel (.+?) (?:l\u00f6schen|loeschen)|clear stock rule for (.+?))[.!?]?$");
    private static final Pattern COLLECT = Pattern.compile(
            "^(?:vorratsregel (.+?) (sammeln|lokal)|stock rule (.+?) (gather|local))$");

    static Component interpret(ServerPlayer player, CompanionEntity mob, String text) {
        if (player == null || !mob.isOwner(player) || text.length() > 320) return null;
        String normalized = Sentence.of(text).text();
        UUID owner = player.getUUID();
        var rules = mob.stockRules;
        switch (normalized) {
            case "holz nur im freilauf", "holz nur im streifgebiet", "harvest wood only in roam zone" -> {
                mob.woodZoneOwner = owner;
                return CompanionTexts.tr("stock.wood_scoped");
            }
            case "holzgebiet freigeben", "clear wood zone limit" -> {
                mob.woodZoneOwner = null;
                return CompanionTexts.tr("stock.wood_unscoped");
            }
        }
        if (normalized.equals("vorratsregel") || normalized.equals("vorratsregeln")
                || normalized.equals("stock rule") || normalized.equals("stock rules")) {
            var owned = rules.stream().filter(rule -> rule.owner.equals(owner)).toList();
            if (owned.isEmpty()) return CompanionTexts.tr("stock.none");
            var storage = CompanionTripPreparation.homeOrderStorage(mob, (ServerLevel) mob.level(), mob.getHome());
            if (owned.size() == 1) return owned.getFirst().status(mob, storage);
            var reply = CompanionTexts.tr("stock.list");
            for (var rule : owned) reply.append("\n").append(rule.status(mob, storage));
            return reply;
        }
        if (normalized.equals("vorratsregel loeschen") || normalized.equals("vorratsregeln loeschen")
                || normalized.equals("clear stock rule") || normalized.equals("clear stock rules")) {
            rules.removeIf(rule -> rule.owner.equals(owner));
            return CompanionTexts.tr("stock.cleared");
        }
        String command = text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        var collect = COLLECT.matcher(normalized);
        if (collect.matches()) {
            String name = collect.group(1) == null ? collect.group(3) : collect.group(1);
            Identifier item = resolve(name);
            if (item == null) return CompanionTexts.tr("stock.invalid_item", CompanionTexts.literal(name));
            boolean enabled = "sammeln".equals(collect.group(2)) || "gather".equals(collect.group(4));
            for (int i = 0; i < rules.size(); i++) {
                var rule = rules.get(i);
                if (rule.owner.equals(owner) && rule.item.equals(item)) {
                    rules.set(i, new CompanionStockRoutine(item, rule.target, owner, rule.home, enabled));
                    return CompanionTexts.tr(enabled ? "stock.collect_on" : "stock.collect_off", CompanionTexts.item(item.toString()));
                }
            }
            return CompanionTexts.tr("stock.missing", CompanionTexts.item(item.toString()));
        }
        var remove = REMOVE.matcher(command);
        if (remove.matches()) {
            String name = remove.group(1) == null ? remove.group(2) : remove.group(1);
            Identifier item = resolve(name);
            if (item == null) return CompanionTexts.tr("stock.invalid_item", CompanionTexts.literal(name));
            var label = CompanionTexts.item(item.toString());
            return rules.removeIf(rule -> rule.owner.equals(owner) && rule.item.equals(item))
                    ? CompanionTexts.tr("stock.removed", label) : CompanionTexts.tr("stock.missing", label);
        }
        var match = SET.matcher(command);
        if (!match.matches()) return null;
        String quantity = match.group(1) == null ? match.group(3) : match.group(1);
        int count = quantity.length() > 3 ? ChatCount.MAX + 1 : Integer.parseInt(quantity);
        if (count < 1 || count > ChatCount.MAX) return CompanionTexts.tr("stock.invalid_count", ChatCount.MAX);
        String name = match.group(2) == null ? match.group(4) : match.group(2);
        Identifier item = resolve(name);
        if (item == null) return CompanionTexts.tr("stock.invalid_item", CompanionTexts.literal(name));
        return addRule(owner, mob, item, count);
    }

    /** Vorratsregel für das aktuelle Heimatlager setzen oder ändern (Chatbefehl und angenommener Vorschlag). */
    static Component addRule(UUID owner, CompanionEntity mob, Identifier item, int count) {
        var rules = mob.stockRules;
        BlockPos home = mob.getHome();
        if (home == null) return CompanionTexts.tr("stock.no_home");
        var rule = new CompanionStockRoutine(item, count, owner, home);
        rules.removeIf(existing -> !existing.owner.equals(owner));
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).item.equals(item)) {
                rules.set(i, new CompanionStockRoutine(item, count, owner, home, rules.get(i).collect && rules.get(i).home.equals(home)));
                return rule.description();
            }
        }
        if (rules.size() >= MAX_RULES) return CompanionTexts.tr("stock.limit", MAX_RULES);
        rules.add(rule);
        return rule.description();
    }

    private static Identifier resolve(String name) {
        var options = CompanionItemNames.resolve(name);
        return options.size() == 1 ? BuiltInRegistries.ITEM.getKey(options.getFirst()) : null;
    }

    private Component description() {
        return CompanionTexts.tr("stock.active", target, CompanionTexts.item(item.toString()), home.toShortString());
    }

    private Component status(CompanionEntity mob, Container box) {
        if (box == null || !home.equals(mob.getHome())) return description().copy().append(" ").append(CompanionTexts.tr("stock.unverified"));
        Item wanted = ItemIds.resolve(item.toString());
        int count = box.countItem(wanted);
        var reply = CompanionTexts.tr("stock.count", CompanionTexts.item(item.toString()), count, target);
        if (count < target) {
            String reason = mob.hasTasks() || mob.isPaused() || mob.build != null ? "stock.wait_work"
                    : collect && mob.zones.get(CompanionZones.Kind.ROAM, (ServerLevel) mob.level()) == null ? "stock.wait_zone"
                    : "stock.wait_material";
            reply.append(" ").append(CompanionTexts.tr(reason));
        }
        return reply;
    }

    static void tick(CompanionEntity mob, ServerLevel world) {
        if (mob.tickCount % 200 != 0 || mob.hasTasks() || mob.build != null || mob.isPaused()
                || mob.getTarget() != null || mob.isRetreating() || mob.stockRules.isEmpty()) return;
        run(mob, world, true);
    }

    enum Result { IDLE, FILLED, GATHERING, MATERIAL, STORAGE }

    static Result run(CompanionEntity mob, ServerLevel world, boolean collectAllowed) {
        var rules = mob.stockRules;
        BlockPos home = mob.getHome();
        UUID owner = mob.getOwnerUuid();
        if (home == null || owner == null) return Result.STORAGE;
        Map<Item, Integer> reserves = new HashMap<>();
        for (var rule : rules) {
            if (!rule.owner.equals(owner) || !rule.home.equals(home)) continue;
            Item item = ItemIds.resolve(rule.item.toString());
            if (item != Items.AIR) reserves.put(item, rule.target);
        }
        if (reserves.isEmpty()) return Result.IDLE;
        Container box = CompanionTripPreparation.reachableHomeStorage(mob, world, home);
        if (box == null) return Result.STORAGE;
        boolean missing = false;
        for (int offset = 0; offset < rules.size(); offset++) {
            int index = (mob.nextStockRule + offset) % rules.size();
            var rule = rules.get(index);
            if (!rule.owner.equals(owner) || !rule.home.equals(home)) continue;
            Item wanted = ItemIds.resolve(rule.item.toString());
            if (wanted == Items.AIR || box.countItem(wanted) >= rule.target) continue;
            missing = true;
            if (rule.refill(mob, world, box, wanted, reserves)) {
                mob.nextStockRule = (index + 1) % rules.size();
                return Result.FILLED;
            }
        }
        if (!missing) return Result.IDLE;
        if (!collectAllowed || !mob.hasFreeSlot() || mob.isStaying() || mob.getHealth() < 12 || mob.food.level() < 12)
            return Result.MATERIAL;
        var zone = mob.zones.get(CompanionZones.Kind.ROAM, world);
        if (zone == null || !hasSpace(box)) return Result.MATERIAL;
        for (int offset = 0; offset < rules.size(); offset++) {
            int index = (mob.nextStockRule + offset) % rules.size();
            var rule = rules.get(index);
            if (!rule.collect || !rule.owner.equals(owner) || !rule.home.equals(home)) continue;
            Item wanted = ItemIds.resolve(rule.item.toString());
            if (wanted == Items.AIR || box.countItem(wanted) >= rule.target) continue;
            BlockPos source = rule.findSource(mob, world, box, zone, wanted, reserves);
            if (source == null) continue;
            mob.addTask(new CompanionEntity.Task(new TaskSpec.Break(source, world.getBlockState(source).getBlock(), rule.item.toString())));
            mob.nextStockRule = (index + 1) % rules.size();
            return Result.GATHERING;
        }
        return Result.MATERIAL;
    }

    private boolean refill(CompanionEntity mob, ServerLevel world, Container box, Item wanted, Map<Item, Integer> reserves) {
        int missing = target - box.countItem(wanted);
        if (missing <= 0) return false;

        int added = deposit(mob, box, wanted, missing);
        int stillMissing = missing - added;
        if (stillMissing > 0 && mob.getInventory().countItem(wanted) == 0) {
            added += CompanionRecipes.craftStock(mob, world, wanted, stillMissing, mob.findLocalStockStation("crafting_table") != null, box, reserves);
            if (added == 0 && !CompanionRecipes.smeltsFor(world, wanted).isEmpty() && mob.findLocalStockStation("furnace") != null)
                added += CompanionRecipes.smeltStock(mob, world, wanted, stillMissing, box, reserves);
        }
        if (added == 0) return false;
        CompanionTripPreparation.recordStorageUse(mob, world, home, box);
        mob.logInfo(CompanionTexts.tr("stock.filled", added, CompanionTexts.item(item.toString()), target - missing + added, target));
        return true;
    }

    private static int deposit(CompanionEntity mob, Container box, Item wanted, int missing) {
        int added = 0;
        var inventory = mob.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && added < missing; slot++) {
            ItemStack held = inventory.getItem(slot);
            if (!held.is(wanted)) continue;
            int count = Math.min(held.getCount(), missing - added);
            ItemStack remainder = HopperBlockEntity.addItem(null, box, held.copyWithCount(count), null);
            int moved = count - remainder.getCount();
            held.shrink(moved);
            added += moved;
        }
        if (added > 0) inventory.setChanged();
        return added;
    }

    private record Stations(boolean table, boolean furnace) {}

    private BlockPos findSource(CompanionEntity mob, ServerLevel world, Container box, CompanionZones.Zone zone,
                                Item wanted, Map<Item, Integer> reserves) {
        Set<Item> needed = new LinkedHashSet<>();
        var stations = new Stations(mob.findLocalStockStation("crafting_table") != null, mob.findLocalStockStation("furnace") != null);
        missingRaw(mob, world, box, wanted, reserves, needed, new HashSet<>(), new int[]{32}, stations);
        if (needed.isEmpty()) return null;
        BlockPos center = mob.blockPosition();
        BlockPos min = new BlockPos(Math.max(zone.min().getX(), center.getX() - 24), Math.max(zone.min().getY(), center.getY() - 8),
                Math.max(zone.min().getZ(), center.getZ() - 24));
        BlockPos max = new BlockPos(Math.min(zone.max().getX(), center.getX() + 24), Math.min(zone.max().getY(), center.getY() + 8),
                Math.min(zone.max().getZ(), center.getZ() + 24));
        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) return null;
        BlockPos[] best = {null};
        double[] score = {Double.MAX_VALUE};
        dev.begleiter.world.SectionScanner.scanBox(world, min, max, s -> needed.contains(rawItem(s)), (x, y, z, state) -> {
            BlockPos pos = new BlockPos(x, y, z);
            double distance = pos.distSqr(center) * mob.routes.sourcePreference(world, pos);
            if (distance < score[0] && !mob.gatherMissed(world, pos) && !mob.memory.dangerous(world, pos, 8)
                    && rawBlock(world, pos, state, mob)) {
                best[0] = pos;
                score[0] = distance;
            }
            return true;
        });
        return best[0];
    }

    private static boolean hasSpace(Container box) {
        for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).isEmpty()) return true;
        return false;
    }

    private static void missingRaw(CompanionEntity mob, ServerLevel world, Container box, Item item, Map<Item, Integer> reserves,
                                   Set<Item> needed, Set<Item> visiting, int[] budget, Stations stations) {
        if (visiting.size() >= 5 || --budget[0] < 0 || !visiting.add(item)) return;
        try {
            if (rawItem(Block.byItem(item).defaultBlockState()) == item || item == Items.COAL) {
                if (visiting.size() > 1 && !mob.work.permitsInput(item, 1)) return;
                int held = mob.getInventory().countItem(item);
                int stock = Math.max(0, box.countItem(item) - reserves.getOrDefault(item, 0));
                if (held + stock < (visiting.size() == 1 ? 1 : 4)) needed.add(item);
                return;
            }
            for (var recipe : CompanionRecipes.craftsFor(world, item)) {
                if (recipe.needsTable() && !stations.table()) continue;
                for (var entry : CompanionRecipes.grouped(recipe).entrySet()) {
                    var options = entry.getKey().items().map(net.minecraft.core.Holder::value).toList();
                    int total = options.stream().mapToInt(part -> mob.getInventory().countItem(part)
                            + Math.max(0, box.countItem(part) - reserves.getOrDefault(part, 0))).sum();
                    if (total < entry.getValue()) for (Item opt : options)
                        missingRaw(mob, world, box, opt, reserves, needed, visiting, budget, stations);
                }
            }
            if (stations.furnace()) {
                for (var recipe : CompanionRecipes.smeltsFor(world, item)) {
                    for (var part : recipe.input().items().map(net.minecraft.core.Holder::value).toList())
                        missingRaw(mob, world, box, part, reserves, needed, visiting, budget, stations);
                }
                if (!CompanionRecipes.smeltsFor(world, item).isEmpty() && mob.fuelLeft < 1)
                    missingRaw(mob, world, box, Items.COAL, reserves, needed, visiting, budget, stations);
            }
        } finally {
            visiting.remove(item);
        }
    }

    private static Item rawItem(BlockState state) {
        if (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)) return Items.COAL;
        if (state.is(BlockTags.LOGS) || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) return state.getBlock().asItem();
        return Items.AIR;
    }

    static boolean rawBlock(ServerLevel world, BlockPos pos, BlockState state, CompanionEntity mob) {
        return rawItem(state) != Items.AIR && state.getFluidState().isEmpty() && CompanionGathering.exposed(world, pos)
                && !mob.hazardAround(world, pos, false) && (!state.requiresCorrectToolForDrops() || mob.hasCorrectToolFor(state))
                && (!state.is(BlockTags.LOGS) || CompanionTerraform.natural(world, pos, state));
    }
}
