package dev.begleiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.begleiter.chat.ChatCount;
import dev.begleiter.chat.ChatParams;
import dev.begleiter.chat.ChatText;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Validates a player recipe order before replacing any existing work. */
public final class CompanionRequests {
    private CompanionRequests() { }

    public static Component order(CompanionEntity mob, ChatParams params, boolean materials) {
        String query = params.itemQuery() != null ? params.itemQuery() : params.item();
        if (query == null || query.isBlank()) {
            mob.requestDialogue.ask(mob, CompanionRequestDialogue.Kind.TARGET, "", params.countOr(1), materials, List.of());
            return CompanionTexts.tr("request.ask_item");
        }
        int count = params.countOr(1);
        if (count < 1 || count > ChatCount.MAX) {
            mob.requestDialogue.ask(mob, CompanionRequestDialogue.Kind.COUNT, query, 0, materials, List.of());
            return CompanionTexts.tr("dialog.ask_count", ChatCount.MAX);
        }
        List<Item> targets = armorSet(query);
        if (targets == null) {
            mob.requestDialogue.ask(mob, CompanionRequestDialogue.Kind.ARMOR, query, count, materials, List.of());
            return CompanionTexts.tr("request.ask_armor");
        }
        if (targets.isEmpty()) {
            targets = CompanionItemNames.resolve(query);
            if (targets.isEmpty()) return CompanionTexts.tr("request.unknown_item", CompanionTexts.literal(query));
            if (targets.size() > 1) {
                mob.requestDialogue.ask(mob, CompanionRequestDialogue.Kind.ITEM, query, count, materials,
                        targets.stream().limit(6).map(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString()).toList());
                return CompanionTexts.tr("request.ambiguous_item", CompanionTexts.literal(query),
                    CompanionTexts.join(targets.stream().limit(6).map(item -> CompanionTexts.literal(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString())).toList(), ", "));
            }
        }
        ServerLevel level = (ServerLevel) mob.level();
        for (Item item : targets)
            if (CompanionRecipes.craftsFor(level, item).isEmpty() && CompanionRecipes.smeltsFor(level, item).isEmpty()
                    && CompanionRecipes.processingFor(level, item).isEmpty())
                return CompanionTexts.tr("request.no_recipe", new ItemStack(item).getHoverName());
        List<CompanionEntity.Task> tasks = new ArrayList<>();
        for (Item item : targets) tasks.add(CompanionEntity.Task.request(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(), count, materials));
        mob.cancelOrders();
        tasks.forEach(mob::addTask);
        return CompanionTexts.tr(materials ? "request.accepted.materials" : "request.accepted.craft", count,
                CompanionTexts.join(targets.stream().map(item -> new ItemStack(item).getHoverName()).toList(), ", "));
    }

    /** null: Rüstung ohne Material angefragt; leer: gewöhnlicher Itemname. */
    static List<Item> armorSet(String query) {
        String name = ChatText.normalize(query).replaceFirst("^(?:full|complete|komplette|vollstaendige) ", "");
        String material = null;
        for (String suffix : List.of("ruestung", "armor", "armour")) {
            if (!name.endsWith(suffix)) continue;
            material = name.substring(0, name.length() - suffix.length()).strip();
            break;
        }
        if (material == null) return List.of();
        if (material.isBlank()) return null;
        String tier = Map.ofEntries(Map.entry("eisen", "iron"), Map.entry("iron", "iron"),
                Map.entry("diamant", "diamond"), Map.entry("diamond", "diamond"),
                Map.entry("gold", "golden"), Map.entry("golden", "golden"),
                Map.entry("leder", "leather"), Map.entry("leather", "leather"),
                Map.entry("netherit", "netherite"), Map.entry("netherite", "netherite"),
                Map.entry("kupfer", "copper"), Map.entry("copper", "copper"),
                Map.entry("ketten", "chainmail"), Map.entry("chainmail", "chainmail")).get(material);
        if (tier == null) return List.of();
        List<Item> items = new ArrayList<>();
        for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
            Item item = ItemIds.resolve(tier + "_" + piece);
            if (item == null || item == Items.AIR) return List.of();
            items.add(item);
        }
        return List.copyOf(items);
    }
}
