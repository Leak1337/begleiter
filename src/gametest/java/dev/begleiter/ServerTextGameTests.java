package dev.begleiter;

import java.util.HashMap;
import java.util.Map;
import dev.begleiter.CompanionEntity.Task;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Prüft den echten Komponenten-Codec und die Sprachauflösung ohne zweiten Client. */
public class ServerTextGameTests {
    /** Dieselbe Serverkomponente in beiden Sprachen prüfen, ohne eine globale Sprachänderung zu hinterlassen. */
    public static void assertTranslatedContains(GameTestHelper h, Component text, String german, String english) {
        h.assertTrue(text != null && text.getContents() instanceof TranslatableContents,
                "Expected a translated component: " + text);
        try {
            inLanguage("de_de", () -> h.assertTrue(text.getString().contains(german), text.getString()));
            inLanguage("en_us", () -> h.assertTrue(text.getString().contains(english), text.getString()));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot load test translations", ex);
        }
    }

    static void inLanguage(String locale, Runnable assertions) throws java.io.IOException {
        Language previous = Language.getInstance();
        Map<String, String> values = new HashMap<>();
        try (var stream = ServerTextGameTests.class.getResourceAsStream("/assets/begleiter/lang/" + locale + ".json")) {
            if (stream == null) throw new AssertionError("Missing locale " + locale);
            Language.loadFromJson(stream, values::put);
        }
        Language.inject(new Language() {
            public String getOrDefault(String key, String fallback) { return values.getOrDefault(key, previous.getOrDefault(key, fallback)); }
            public boolean has(String key) { return values.containsKey(key) || previous.has(key); }
            public boolean isDefaultRightToLeft() { return false; }
            public FormattedCharSequence getVisualOrder(FormattedText text) { return previous.getVisualOrder(text); }
        });
        try { assertions.run(); } finally { Language.inject(previous); }
    }

    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay(); c.setNoAi(true);
        return c;
    }

    private static CompanionSnapshot roundTrip(GameTestHelper h, CompanionSnapshot value) {
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            CompanionSnapshot.CODEC.encode(buffer, value);
            byte[] encoded = io.netty.buffer.ByteBufUtil.getBytes(buffer);
            var decoded = CompanionSnapshot.CODEC.decode(buffer);
            // Minecraft canonically encodes plain literal arguments as strings. Compare the wire form,
            // then check translated output below, rather than demanding identical in-memory argument classes.
            var canonical = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
            try {
                CompanionSnapshot.CODEC.encode(canonical, decoded);
                h.assertTrue(java.util.Arrays.equals(encoded, io.netty.buffer.ByteBufUtil.getBytes(canonical)),
                        "Component snapshot wire form changed after decoding");
            } finally { canonical.release(); }
            h.assertValueEqual(buffer.readableBytes(), 0, "Snapshot bytes left over");
            return decoded;
        } finally { buffer.release(); }
    }

    @GameTest
    public void snapshotResolvesTheSamePacketInBothLanguages(GameTestHelper h) throws Exception {
        var c = companion(h);
        c.setCustomName(Component.literal("Berta %s Ä"));
        c.addTask(Task.gather("eisen", 7));
        c.lastStage = "Mine Nord %s";
        c.nearestLava = c.blockPosition().east(3);
        c.problemText = "My custom problem %s";
        var snapshot = roundTrip(h, CompanionSnapshot.capture(c, 7, CompanionMenu.Page.STATUS));
        h.assertTrue(snapshot.activityText().getContents() instanceof TranslatableContents, "Activity flattened by codec");
        inLanguage("en_us", () -> {
            h.assertTrue(snapshot.activity().contains("searching for iron, 7 remaining"), snapshot.activity());
            h.assertTrue(snapshot.needs().contains("player order"), snapshot.needs());
            h.assertTrue(snapshot.hazard().contains("blocks away"), snapshot.hazard());
            h.assertTrue(snapshot.target().contains("No target"), snapshot.target());
        });
        inLanguage("de_de", () -> {
            h.assertTrue(snapshot.activity().contains("suche Eisen, noch 7"), snapshot.activity());
            h.assertTrue(snapshot.needs().contains("Auftrag vom Spieler"), snapshot.needs());
            h.assertTrue(snapshot.target().contains("Kein Ziel"), snapshot.target());
        });
        h.assertValueEqual(snapshot.name(), "Berta %s Ä", "Custom name translated");
        h.assertValueEqual(snapshot.stage(), "Mine Nord %s", "Custom stage formatted");
        h.assertTrue(snapshot.hasProblem() && snapshot.problemText().getString().contains("My custom problem %s"), "Explicit problem lost");
        h.assertTrue(c.currentTask().equals(Task.gather("eisen", 7)), "Capturing translated state changed task");
        h.succeed();
    }

    @GameTest
    public void chatAndMenuRepliesRetainTranslationsAndPauseBuilds(GameTestHelper h) throws Exception {
        var c = companion(h); var player = h.makeMockServerPlayerInLevel(); c.setOwner(player.getUUID());
        c.build = CompanionBuilder.create(h.getLevel(), "House %s", Map.of(c.blockPosition().east(), Blocks.STONE.defaultBlockState()));
        c.addTask(Task.build());
        Component reply = CompanionChatActions.interpretReply(player, c, "follow me", "en_us");
        h.assertTrue(c.build.paused && !c.hasTasks(), "Translated follow changed pause semantics");
        inLanguage("en_us", () -> h.assertTrue(reply.getString().equals("I am following you. Build \"House %s\" paused – say \"continue\" to resume."), reply.getString()));
        inLanguage("de_de", () -> h.assertTrue(reply.getString().startsWith("Ich folge dir. Bau „House %s“ pausiert"), reply.getString()));
        var resumed = CompanionBrain.resumeReply(c);
        h.assertTrue(!c.build.paused, "Translated resume did not resume");
        inLanguage("en_us", () -> h.assertTrue(resumed.getString().contains("Resuming \"House %s\""), resumed.getString()));
        var waiting = CompanionActions.get(1).handler().executeComponent(c, player, false);
        h.assertTrue(c.isStaying() && c.build.paused, "Menu wait changed its semantics");
        inLanguage("en_us", () -> h.assertTrue(waiting.getString().startsWith("I will wait here."), waiting.getString()));
        h.succeed();
    }

    @GameTest
    public void taskResourcesNeedsAndStatusHaveBothLanguages(GameTestHelper h) throws Exception {
        var c = companion(h);
        for (String locale : new String[]{"de_de", "en_us"}) inLanguage(locale, () -> {
            for (var type : CompanionEntity.TaskType.values())
                h.assertTrue(!CompanionTexts.taskLabel(type).getString().contains("server.begleiter."), "Missing task label " + type);
            for (var resource : Resources.ALL)
                h.assertTrue(!CompanionTexts.resource(resource.key()).getString().contains("server.begleiter."), "Missing resource " + resource.key());
            for (var need : CompanionNeeds.Need.values()) {
                c.goal = need; c.goalScore = 42; c.goalReason = "custom %s";
                h.assertTrue(!CompanionTexts.needs(c).getString().contains("server.begleiter."), "Missing need " + need);
            }
            String status = CompanionTexts.status(c).getString();
            h.assertTrue(status.contains(locale.equals("en_us") ? "storage: not set" : "Lager: nicht gesetzt"), status);
        });
        h.succeed();
    }

    @GameTest
    public void menuFeedbackDoesNotShowAStaleTranslation(GameTestHelper h) throws Exception {
        var c = companion(h);
        c.setUiFeedback(CompanionTexts.tr("reply.follow"));
        var snapshot = roundTrip(h, CompanionSnapshot.capture(c, 1, CompanionMenu.Page.MAIN));
        inLanguage("en_us", () -> h.assertValueEqual(snapshot.feedback(), "I am following you.", "Menu feedback flattened"));
        c.lastUiAction = "legacy feedback";
        var fresh = CompanionSnapshot.capture(c, 1, CompanionMenu.Page.MAIN);
        h.assertValueEqual(fresh.feedback(), "legacy feedback", "Old translated feedback overrode new legacy message");
        h.assertTrue(!fresh.hasProblem(), "A text prefix was mistaken for a real problem");
        h.succeed();
    }

    @GameTest
    public void newCoreUsesLeakAndRevivedCoreKeepsCustomName(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        for (int x : new int[]{2, 6}) {
            h.setBlock(x, 0, 2, Blocks.STONE);
            var stack = new ItemStack(CompanionMod.COMPANION_CORE);
            if (x == 6) {
                CompoundTag memory = new CompoundTag(); memory.putString("Name", "Berta %s Ä");
                CustomData.set(DataComponents.CUSTOM_DATA, stack, memory);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var ground = h.absolutePos(new BlockPos(x, 0, 2));
            CompanionMod.COMPANION_CORE.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false)));
            var c = h.getLevel().getEntitiesOfClass(CompanionEntity.class, new AABB(ground).inflate(1.5)).getFirst();
            h.assertValueEqual(c.getCustomName().getString(), x == 2 ? "Leak" : "Berta %s Ä", "Core name");
            h.assertTrue(c.isOwner(player) && stack.isEmpty(), "Core ownership/consumption changed");
        }
        h.succeed();
    }
}
