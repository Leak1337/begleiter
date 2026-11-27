package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Erinnerungen liegen nach dem Tod im Weltspeicher; der Kern trägt nur eine kleine Referenz. */
public class CoreMemoryGameTests {
    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    private static CompanionEntity companionWith500Places(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(1, 1, 1));
        c.setPersistenceRequired();
        var origin = h.absolutePos(BlockPos.ZERO);
        String[] ores = {"eisen", "kohle", "gold", "diamant"};
        // je Erzsorte 125 (unter der Obergrenze von 160 je Sorte), 10 Blöcke auseinander: keine Zusammenlegung
        for (int i = 0; i < 500; i++)
            c.memory.remember(CompanionMemory.Kind.ORE, ores[i % 4], h.getLevel(), origin.offset((i % 25) * 10, 0, (i / 25) * 10));
        h.assertValueEqual(c.memory.size(), 500, "Fixture must hold 500 places");
        return c;
    }

    private static ItemStack droppedCore(GameTestHelper h) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(new BlockPos(1, 1, 1))).inflate(4)).stream()
                .map(ItemEntity::getItem).filter(s -> s.is(CompanionMod.COMPANION_CORE)).findFirst().orElse(ItemStack.EMPTY);
    }

    private static InteractionResult use(GameTestHelper h, Player player, ItemStack core, BlockPos rel) {
        player.setItemInHand(InteractionHand.MAIN_HAND, core);
        BlockPos ground = h.absolutePos(rel);
        return CompanionMod.COMPANION_CORE.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false)));
    }

    @GameTest(maxTicks = 100)
    public void fiveHundredPlacesSurviveDeathAndReviveWithASmallCore(GameTestHelper h) {
        floor(h);
        var level = h.getLevel();
        var c = companionWith500Places(h);
        int places = c.memory.size();
        var soul = c.soulId();
        c.hurtServer(level, level.damageSources().generic(), 1000f);
        h.runAfterDelay(5, () -> {
            ItemStack core = droppedCore(h);
            h.assertTrue(!core.isEmpty(), "No core dropped");
            var data = core.get(DataComponents.CUSTOM_DATA).copyTag();
            int bytes = data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            h.assertTrue(bytes < 1024, "Core data too large: " + bytes + " bytes");
            var store = CompanionMemoryStore.get(level.getServer());
            h.assertTrue(store.peek(soul) != null, "Memories not in the world store");
            Player player = h.makeMockPlayer(GameType.SURVIVAL);
            var result = use(h, player, core.copy(), new BlockPos(4, 0, 4));
            h.assertTrue(result.consumesAction(), "Revive failed: " + result);
            var back = level.getEntitiesOfClass(CompanionEntity.class, new AABB(h.absolutePos(new BlockPos(4, 1, 4))).inflate(2),
                    CompanionEntity::isAlive).getFirst();
            // Todesort kommt dazu
            h.assertTrue(back.memory.size() >= places, "Places lost: " + back.memory.size() + " < " + places);
            h.assertTrue(soul.equals(back.soulId()), "Soul id changed on revive");
            h.assertTrue(store.peek(soul) == null, "Store entry not consumed after revive");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void duplicateCoreFailsClearlyAndIsKept(GameTestHelper h) {
        floor(h);
        var level = h.getLevel();
        var c = companionWith500Places(h);
        c.hurtServer(level, level.damageSources().generic(), 1000f);
        h.runAfterDelay(5, () -> {
            ItemStack core = droppedCore(h);
            Player player = h.makeMockPlayer(GameType.SURVIVAL);
            use(h, player, core.copy(), new BlockPos(4, 0, 4));
            ItemStack copy = core.copy();
            int before = level.getEntitiesOfClass(CompanionEntity.class, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(12)).size();
            var result = use(h, player, copy, new BlockPos(6, 0, 6));
            h.assertTrue(result == InteractionResult.FAIL, "Duplicate core revived again: " + result);
            h.assertTrue(player.getMainHandItem().is(CompanionMod.COMPANION_CORE) && player.getMainHandItem().getCount() == 1,
                    "Duplicate core destroyed");
            int after = level.getEntitiesOfClass(CompanionEntity.class, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(12)).size();
            h.assertValueEqual(after, before, "Companion spawned from a duplicate core");
            h.succeed();
        });
    }

    @GameTest
    public void legacyCoreWithEmbeddedMemoryStillRevives(GameTestHelper h) {
        floor(h);
        var home = h.absolutePos(new BlockPos(6, 1, 6));
        CompoundTag legacy = new CompoundTag();
        legacy.putLong("Home", home.asLong());
        legacy.putString("Name", "Altkern");
        ItemStack core = new ItemStack(CompanionMod.COMPANION_CORE);
        net.minecraft.world.item.component.CustomData.set(DataComponents.CUSTOM_DATA, core, legacy);
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        var result = use(h, player, core, new BlockPos(3, 0, 3));
        h.assertTrue(result.consumesAction(), "Legacy core rejected");
        var back = h.getLevel().getEntitiesOfClass(CompanionEntity.class, new AABB(h.absolutePos(new BlockPos(3, 1, 3))).inflate(2),
                CompanionEntity::isAlive).getFirst();
        h.assertValueEqual(back.getHome(), home, "Legacy home lost");
        h.succeed();
    }

    @GameTest
    public void storeSurvivesASaveAndLoadRoundTrip(GameTestHelper h) {
        var c = companionWith500Places(h);
        var store = new CompanionMemoryStore();
        var soul = java.util.UUID.randomUUID();
        store.put(soul, c.saveMemory());
        var tag = CompanionMemoryStore.TYPE.codec().encodeStart(NbtOps.INSTANCE, store).getOrThrow();
        var loaded = CompanionMemoryStore.TYPE.codec().parse(NbtOps.INSTANCE, tag).getOrThrow();
        var restored = new CompanionMemory();
        restored.load(loaded.peek(soul).getCompoundOrEmpty("Memory"));
        h.assertValueEqual(restored.size(), c.memory.size(), "Places lost across a save/load of the store");
        h.succeed();
    }
}
