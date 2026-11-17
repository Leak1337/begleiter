package dev.begleiter;

import java.util.List;
import java.util.UUID;

import dev.begleiter.chat.ChatCount;
import dev.begleiter.chat.ChatIntent;
import dev.begleiter.chat.ItemRequest;
import dev.begleiter.chat.Sentence;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Eine begrenzte Rückfrage an den Besitzer. Laufende Arbeit wird erst ersetzt, wenn die Antwort die normale Auftragsprüfung besteht. */
public final class CompanionRequestDialogue {
    public enum Kind { TARGET, ARMOR, ITEM, COUNT }
    private static final int TTL = 1200;
    private record Pending(Kind kind, UUID owner, String dimension, String query, int count,
                           boolean materials, List<String> choices, long expires) { }
    private Pending pending;
    private long revision;

    public long revision() { return revision; }
    public boolean pending() { return pending != null; }
    public void clear() { pending = null; revision++; }

    public void ask(CompanionEntity mob, Kind kind, String query, int count, boolean materials, List<String> choices) {
        var owner = mob.getOwnerAnywhere();
        if (owner == null || query != null && query.length() > 256) return;
        pending = new Pending(kind, owner.getUUID(), mob.level().dimension().identifier().toString(),
                query == null ? "" : query, count, materials, choices.stream().limit(6).toList(), mob.level().getGameTime() + TTL);
        revision++;
    }

    /** Nur nach ausdrücklicher Anrede des Begleiters; vollständige Befehle gehen weiter über den normalen Parser. */
    public Component answer(ServerPlayer player, CompanionEntity mob, String text) {
        var question = pending;
        if (question == null || !question.owner().equals(player.getUUID()) || !mob.isOwner(player)) return null;
        if (mob.level().getGameTime() >= question.expires()
                || !mob.level().dimension().identifier().toString().equals(question.dimension())) { clear(); return null; }
        if (text == null || text.length() > 256 || ItemRequest.parse(text) != null) return null;
        String query = text.strip();
        int count = question.count();
        if (question.kind() == Kind.COUNT) {
            var tokens = Sentence.of(text).tokens();
            if (tokens.size() != 1) return null;
            count = ChatCount.find(tokens);
            if (count == ChatCount.NONE) return null;
            if (count <= 0) return CompanionTexts.tr("dialog.ask_count", ChatCount.MAX);
            query = question.query();
        } else if (question.kind() == Kind.ARMOR) {
            var items = CompanionRequests.armorSet(query);
            if (items == null || items.isEmpty()) {
                query = text.strip() + " armor";
                items = CompanionRequests.armorSet(query);
            }
            if (items == null || items.isEmpty()) return null;
        } else {
            var items = CompanionItemNames.resolve(query);
            if (question.kind() == Kind.ITEM) items = items.stream()
                    .filter(item -> question.choices().contains(BuiltInRegistries.ITEM.getKey(item).toString())).toList();
            if (items.size() != 1) return null;
            query = BuiltInRegistries.ITEM.getKey(items.getFirst()).toString();
        }
        clear();
        var intent = question.materials() ? ChatIntent.MATERIALS_FOR : ChatIntent.CRAFT_FOR_OWNER;
        return CompanionRequests.order(mob, new ItemRequest(intent, query, count, player.clientInformation().language()).params(), question.materials());
    }

    /** Eine ungültige Antwort darf nicht als fremder LLM-Auftrag weiterlaufen. */
    public Component waitingReply(ServerPlayer player, CompanionEntity mob) {
        var question = pending;
        if (question == null || !question.owner().equals(player.getUUID()) || !mob.isOwner(player)
                || mob.level().getGameTime() >= question.expires()
                || !mob.level().dimension().identifier().toString().equals(question.dimension())) return null;
        return switch (question.kind()) {
            case COUNT -> CompanionTexts.tr("dialog.ask_count", ChatCount.MAX);
            case ARMOR -> CompanionTexts.tr("request.ask_armor");
            case TARGET -> CompanionTexts.tr("request.ask_item");
            case ITEM -> CompanionTexts.tr("request.ambiguous_item", Component.literal(question.query()),
                    CompanionTexts.join(question.choices().stream().map(id -> (Component) Component.literal(id)).toList(), ", "));
        };
    }

    public CompoundTag save(long now) {
        var tag = new CompoundTag();
        if (pending == null || pending.expires() <= now) return tag;
        tag.putString("Kind", pending.kind().name());
        tag.putString("Owner", pending.owner().toString());
        tag.putString("Dimension", pending.dimension());
        tag.putString("Query", pending.query());
        tag.putInt("Count", pending.count());
        tag.putBoolean("Materials", pending.materials());
        tag.putInt("Remaining", (int) Math.min(TTL, pending.expires() - now));
        var choices = new ListTag();
        pending.choices().forEach(choice -> choices.add(StringTag.valueOf(choice)));
        tag.put("Choices", choices);
        return tag;
    }

    public void load(CompoundTag tag, long now) {
        clear();
        try {
            if (tag.getString("Query").isEmpty() || tag.getInt("Count").isEmpty()
                    || tag.getInt("Remaining").isEmpty() || tag.getBoolean("Materials").isEmpty()) return;
            var kind = Kind.valueOf(tag.getStringOr("Kind", ""));
            var owner = UUID.fromString(tag.getStringOr("Owner", ""));
            String query = tag.getStringOr("Query", ""), dimension = tag.getStringOr("Dimension", "");
            int count = tag.getIntOr("Count", 0), remaining = tag.getIntOr("Remaining", 0);
            if (query.length() > 256 || RegistryIds.parse(dimension) == null || dimension.length() > 128
                    || count < 0 || count > ChatCount.MAX || remaining <= 0 || remaining > TTL) return;
            var choices = new java.util.ArrayList<String>();
            var rows = tag.getListOrEmpty("Choices");
            if (rows.size() > 6) return;
            for (int i = 0; i < rows.size(); i++) {
                String choice = rows.getStringOr(i, "");
                if (choice.length() > 256 || RegistryIds.parse(choice) == null) return;
                choices.add(choice);
            }
            if (kind == Kind.ITEM && choices.isEmpty()) return;
            pending = new Pending(kind, owner, dimension, query, count, tag.getBooleanOr("Materials", false), List.copyOf(choices), now + remaining);
        } catch (IllegalArgumentException ignored) { }
    }
}
