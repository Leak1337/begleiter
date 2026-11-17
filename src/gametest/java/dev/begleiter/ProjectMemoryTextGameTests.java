package dev.begleiter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Echte Projekt-, Gedächtnis- und Menüausgaben müssen ihre Komponenten durch den Vanilla-Netzcodec behalten. */
public class ProjectMemoryTextGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay(); c.setNoAi(true); return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID()); p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0); return p;
    }

    private static Component wire(GameTestHelper h, Component text) {
        h.assertTrue(text != null && text.getContents() instanceof TranslatableContents, "Producer flattened translated text");
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            ComponentSerialization.STREAM_CODEC.encode(buffer, text);
            var back = ComponentSerialization.STREAM_CODEC.decode(buffer);
            h.assertValueEqual(buffer.readableBytes(), 0, "Unread component bytes"); return back;
        } finally { buffer.release(); }
    }

    private static void translated(GameTestHelper h, Component text, String german, String english) throws Exception {
        var back = wire(h, text);
        ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(back.getString().contains(german), back.getString()));
        ServerTextGameTests.inLanguage("en_us", () -> {
            h.assertTrue(back.getString().contains(english), back.getString());
            h.assertTrue(!back.getString().contains("server.begleiter."), "Missing translation: " + back.getString());
        });
    }

    private static boolean containsKey(Component text, String key) {
        if (text.getContents() instanceof TranslatableContents tr) {
            if (tr.getKey().equals(key)) return true;
            for (Object arg : tr.getArgs()) if (arg instanceof Component child && containsKey(child, key)) return true;
        }
        return text.getSiblings().stream().anyMatch(child -> containsKey(child, key));
    }

    private static Map<BlockPos, BlockState> emptySite(GameTestHelper h, ServerPlayer p) {
        var schema = CompanionStructures.house(CompanionStructures.WOOD, 5, 5);
        var plan = CompanionSchematics.place(schema, p.blockPosition(), p.getDirection(), Rotation.NONE, true);
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (var pos : plan.keySet()) {
            h.assertTrue(h.getLevel().hasChunkAt(pos), "Test site chunk not loaded");
            h.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            before.put(pos, h.getLevel().getBlockState(pos));
        }
        return before;
    }

    @GameTest
    public void chatPreviewKeepsNativeMaterialsAndDoesNotBuild(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c); var before = emptySite(h, p);
        c.giveToInventory(new ItemStack(Items.DIAMOND, 7));
        var task = CompanionEntity.Task.gather("eisen", 3); c.addTask(task);
        var preview = CompanionProjektVorschau.planen(p);
        h.assertTrue(preview.sicher(), "Fixture site should be safe: " + preview.hinweis());
        var reply = wire(h, CompanionChatActions.interpretReply(p, c, "preview project", "en_us"));
        translated(h, reply, "Nur Vorschau", "Preview only");
        h.assertTrue(containsKey(reply, "server.begleiter.project.step.1"), "Project steps flattened");
        var material = preview.material().entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        h.assertTrue(containsKey(reply, material.getDescriptionId()), "Preview lost native material translation");
        h.assertTrue(c.currentTask().equals(task) && c.build == null && !c.projekt.vorhanden(), "Preview started/replaced work");
        h.assertValueEqual(CompanionCraft.count(c, "diamond"), 7, "Preview consumed inventory");
        for (var entry : before.entrySet()) h.assertTrue(h.getLevel().getBlockState(entry.getKey()).equals(entry.getValue()), "Preview changed the world");
        h.succeed();
    }

    @GameTest
    public void projectLifecycleTranslatesWithoutChangingSavedDraftOrProtectedSite(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c); var before = emptySite(h, p);
        var preview = CompanionProjektVorschau.planen(p);
        var saved = c.projekt.bestaetigen(preview, CompanionMemory.dim(h.getLevel()), p.blockPosition(), p.getDirection());
        h.assertTrue(saved.erfolgreich(), "Safe preview rejected");
        translated(h, saved.nachrichtText(), "Noch keine Ausführung", "Execution has not been approved");
        var tag = c.projekt.speichern();
        translated(h, c.projekt.beschreibenText(), "kleines Holzhaus", "small wooden house");
        h.assertTrue(c.projekt.speichern().equals(tag), "Changing display language changed save data");
        var loaded = new CompanionProjektVerwaltung(); loaded.laden(tag);
        h.assertTrue(loaded.speichern().equals(tag), "ProjectV1 draft changed on reload");
        translated(h, loaded.beschreibenText(), "Status: ENTWURF", "Status: DRAFT");
        var duplicate = loaded.bestaetigen(preview, BlockPos.ZERO, Direction.NORTH);
        h.assertTrue(!duplicate.erfolgreich() && loaded.speichern().equals(tag), "Duplicate confirmation replaced the draft");
        translated(h, duplicate.nachrichtText(), "bereits", "already exists");
        var customTag = tag.copy(); customTag.putString("Name", "Berta %s Ä");
        var custom = new CompanionProjektVerwaltung(); custom.laden(customTag);
        translated(h, custom.beschreibenText(), "Berta %s Ä", "Berta %s Ä");
        BlockPos target = before.keySet().iterator().next(); h.getLevel().setBlock(target, Blocks.CHEST.defaultBlockState(), 3);
        var blocked = c.projekt.pruefen(h.getLevel());
        h.assertTrue(!blocked.erfolgreich() && c.projekt.speichern().equals(tag), "Blocked check changed the draft");
        translated(h, blocked.nachrichtText(), "Kiste", "chest");
        h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.CHEST), "Recheck destroyed protected chest");
        var rejected = new CompanionProjektVerwaltung().bestaetigen(CompanionProjektVorschau.planen(p), p.blockPosition(), p.getDirection());
        h.assertTrue(!rejected.erfolgreich(), "Blocked site became executable");
        translated(h, rejected.nachrichtText(), "nicht als Entwurf", "not saved as a draft");
        translated(h, c.projekt.verwerfen().nachrichtText(), "verworfen", "discarded");
        h.assertTrue(!c.projekt.vorhanden() && !c.hasTasks() && c.build == null, "Draft lifecycle executed work");
        var cycle = CompanionProjektVorschau.graphPruefen(List.of(
                new CompanionProjektVorschau.Schritt(1, "My step %s", List.of(2)),
                new CompanionProjektVorschau.Schritt(2, "Other step", List.of(1))));
        h.assertTrue(!cycle.gueltig(), "Cycle became valid after translation");
        translated(h, cycle.hinweisText(), "Zyklus", "cycle");
        h.succeed();
    }

    @GameTest
    public void locationQueriesKeepCountsNativeItemsDimensionsAndExistingWork(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c); var from = p.blockPosition();
        c.addTask(CompanionEntity.Task.gather("holz", 3)); var queued = c.peekTasks(10);
        var iron = c.memory.remember(CompanionMemory.Kind.ORE, "eisen", h.getLevel(), from.east(10)); iron.count = 4;
        c.memory.remember(CompanionMemory.Kind.ORE, "eisen", h.getLevel(), from.east(40));
        c.memory.all().add(new CompanionMemory.Place(CompanionMemory.Kind.ORE, "eisen", "minecraft:the_nether", from.east(1), 0));
        var known = wire(h, CompanionChatActions.interpretReply(p, c, "where is iron", "en_us"));
        translated(h, known, "Ich kenne Eisen: 4 Blöcke", "I know of iron: 4 blocks");
        ServerTextGameTests.inLanguage("en_us", () -> h.assertTrue(known.getString().contains("2 locations in total")
                && known.getString().contains("east") && known.getString().contains(iron.pos.toShortString()), known.getString()));
        var box = new SimpleContainer(9); box.setItem(0, new ItemStack(Items.BREAD, 12));
        c.memory.noteChest(h.getLevel(), from.south(6), box);
        var chest = wire(h, CompanionChatActions.interpretReply(p, c, "where is bread", "en_us"));
        translated(h, chest, "12x", "are in the chest");
        h.assertTrue(containsKey(chest, Items.BREAD.getDescriptionId()), "Native chest item flattened");
        translated(h, CompanionBrain.whereIsReply(p, c, List.of("where", "is", "gold"), Resources.GOLD, null), "noch nicht gesehen", "Not seen here yet");
        translated(h, CompanionBrain.whereIsReply(p, c, List.of("where"), null, Items.DIAMOND), "keiner Kiste", "do not know of a chest");
        c.memory.setWaypoint("iron mine", h.getLevel(), from.west(8));
        translated(h, CompanionBrain.whereIsReply(p, c, List.of("where", "is", "iron", "mine")), "\"iron mine\" ist bei", "\"iron mine\" is at");
        h.assertTrue(CompanionBrain.whereIsReply(p, c, List.of("unrecognized"), null, null) == null, "Unknown query no longer falls through");
        h.assertTrue(c.peekTasks(10).equals(queued) && box.getItem(0).getCount() == 12 && c.build == null, "Read-only query changed work/inventory");
        h.succeed();
    }

    @GameTest
    public void knowledgeAndBaseTabsReceiveTranslatedDataInsteadOfBackgroundGlass(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        c.memory.remember(CompanionMemory.Kind.ORE, "eisen", h.getLevel(), p.blockPosition().east(6));
        var menu = new CompanionMenu(1, p.getInventory(), c, CompanionMenu.Page.STATUS);
        for (int slot : new int[]{36, 8, 37, 38, 39, 40, 28, 29, 30, 31, 33}) {
            var stack = menu.getSlot(slot).getItem();
            h.assertTrue(!stack.is(CompanionCraft.item("black_stained_glass_pane")), "Status tab still reads background slot " + slot);
            h.assertTrue(stack.get(DataComponents.CUSTOM_NAME) != null, "Status data missing in slot " + slot);
            wire(h, stack.getHoverName());
        }
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(buffer, menu.getSlot(36).getItem());
            var knowledge = ItemStack.STREAM_CODEC.decode(buffer);
            translated(h, knowledge.getHoverName(), "Eisen", "iron");
        } finally { buffer.release(); }
        translated(h, menu.getSlot(8).getItem().getHoverName(), "Alles in Ordnung", "Everything is fine");
        translated(h, menu.getSlot(38).getItem().getHoverName(), "Lager: nicht gesetzt", "Storage: not set");
        h.assertTrue(!c.hasTasks() && c.build == null && c.memory.size() == 1 && !c.projekt.vorhanden(), "Rendering status tabs changed state");
        h.succeed();
    }
}
