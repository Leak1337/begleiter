package dev.begleiter;

import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Tränke brauen und trinken, Diamantausrüstung vor dem End, Bogen aus mitgeführtem Material. */
public class PotionGameTests {
    private static final String ARENA = "begleiter-gametest:request_isolation";
    private static final BlockPos START = new BlockPos(24, 1, 24);

    private record Fixture(CompanionEntity mob, ServerPlayer owner) {
        void command(String body) { CompanionBrain.onChat(owner, "@ " + body); }
    }

    private static Fixture fixture(GameTestHelper h) {
        for (int x = 18; x <= 30; x++) for (int z = 18; z <= 30; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var mob = h.spawn(CompanionMod.COMPANION, START);
        mob.stay();
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.buildModeOverride = false;
        @SuppressWarnings("removal") var player = h.makeMockServerPlayerInLevel();
        mob.setOwner(player.getUUID());
        player.snapTo(mob.getX(), mob.getY(), mob.getZ(), 0, 0);
        return new Fixture(mob, player);
    }

    /** Heile 5x5-Hülle mit Tür (wie in JourneyGameTests), sonst gilt das Haus als beschädigt. */
    private static void buildShell(GameTestHelper h, BlockPos origin) {
        var lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.NORTH);
        h.setBlock(origin.offset(2, 1, 0), lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
        h.setBlock(origin.offset(2, 2, 0), lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        for (int dy = 0; dy <= 4; dy++)
            for (int dx = 0; dx < 5; dx++)
                for (int dz = 0; dz < 5; dz++) {
                    boolean shell = dy == 0 || dy == 4 || dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    boolean doorGap = dz == 0 && dx == 2 && (dy == 1 || dy == 2);
                    if (shell && !doorGap) h.setBlock(origin.offset(dx, dy, dz), Blocks.OAK_PLANKS);
                }
    }

    private static void run(CompanionEntity mob, int ticks) {
        var task = new CompanionTaskGoal(mob);
        for (int i = 0; i < ticks && mob.hasTasks(); i++) {
            mob.tickCount++;
            task.tick();
        }
    }

    @GameTest(structure = ARENA)
    public void brews_strength_at_a_brewing_stand_from_bottles_and_a_water_bucket(GameTestHelper h) {
        var mob = fixture(h).mob();
        h.setBlock(START.east(), Blocks.BREWING_STAND);
        mob.giveToInventory(new ItemStack(Items.GLASS_BOTTLE, 3));
        mob.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        mob.giveToInventory(new ItemStack(Items.NETHER_WART, 2));
        mob.giveToInventory(new ItemStack(Items.BLAZE_POWDER, 2));
        mob.addTask(CompanionEntity.Task.brew("strength", 3));
        run(mob, 1000);
        h.assertValueEqual(mob.potions.count(CompanionPotions.Kind.STRENGTH), 3, "Strength potions brewed");
        h.assertValueEqual(CompanionCraft.count(mob, "nether_wart"), 1, "Nether wart left");
        h.assertTrue(CompanionCraft.owns(mob, "bucket") && !CompanionCraft.owns(mob, "water_bucket"),
                "The water bucket should be emptied into the bottles like a cauldron");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void brewing_without_nether_wart_fails_and_is_not_retried_at_once(GameTestHelper h) {
        var mob = fixture(h).mob();
        h.setBlock(START.east(), Blocks.BREWING_STAND);
        for (int i = 0; i < 3; i++) mob.giveToInventory(CompanionPotions.waterBottle());
        mob.giveToInventory(new ItemStack(Items.BLAZE_POWDER, 2));
        mob.addTask(CompanionEntity.Task.brew("strength", 3));
        run(mob, 100);
        h.assertTrue(!mob.hasTasks(), "Brewing without nether wart should give up");
        h.assertTrue(mob.craftBlocked("#brew_strength"), "A failed brew should block the same potion for a while");
        h.assertValueEqual(mob.potions.waterBottles(), 3, "Water bottles must not be used up");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void drinks_fire_resistance_when_burning_and_keeps_the_bottle(GameTestHelper h) {
        var mob = fixture(h).mob();
        mob.giveToInventory(CompanionPotions.potion(CompanionPotions.Kind.FIRE));
        mob.igniteForSeconds(5);
        mob.tickCount += 100;
        mob.potions.tick();
        h.assertTrue(mob.hasEffect(MobEffects.FIRE_RESISTANCE), "Burning companion did not drink fire resistance");
        h.assertValueEqual(mob.potions.count(CompanionPotions.Kind.FIRE), 0, "Potion used");
        h.assertTrue(CompanionCraft.owns(mob, "glass_bottle"), "Empty bottle kept for brewing");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void drinks_healing_only_when_hurt_in_a_fight(GameTestHelper h) {
        var mob = fixture(h).mob();
        mob.giveToInventory(CompanionPotions.potion(CompanionPotions.Kind.HEALING));
        mob.setHealth(20);
        mob.tickCount += 100;
        mob.potions.tick();
        h.assertValueEqual(mob.potions.count(CompanionPotions.Kind.HEALING), 1, "Healthy companion wasted a healing potion");
        mob.setHealth(5);
        mob.setLastHurtByMob(h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, START.north(3)));
        mob.tickCount += 100;
        mob.potions.tick();
        h.assertValueEqual(mob.potions.count(CompanionPotions.Kind.HEALING), 0, "Hurt companion did not drink healing");
        h.assertTrue(mob.getHealth() > 5, "Healing potion did not heal");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void journey_gets_full_diamond_gear_before_the_stronghold(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        for (var item : new net.minecraft.world.item.Item[]{Items.DIAMOND_PICKAXE, Items.IRON_SWORD, Items.SHIELD,
                Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS})
            mob.giveToInventory(new ItemStack(item));
        mob.giveToInventory(new ItemStack(Items.BREAD, 12));
        mob.giveToInventory(new ItemStack(Items.BLAZE_ROD, 8));
        mob.giveToInventory(new ItemStack(Items.COBBLESTONE, 64));
        var house = new CompoundTag();
        house.putBoolean("HouseBuilt", true);
        buildShell(h, START.offset(-9, -1, -9));
        house.putLong("HouseOrigin", h.absolutePos(START.offset(-9, -1, -9)).asLong());
        mob.house.loadMemory(house);
        mob.setHome(h.absolutePos(START.south()));
        mob.setPortalPos(h.absolutePos(START.west(3)));
        h.assertTrue(!CompanionBrain.needsBlaze(mob), "Eight rods are enough for the eyes");
        fixture.command("play survival to the dragon");
        long previousTime = h.getLevel().getDefaultClockTime();
        try {
            h.setTime(1000);
            h.getLevel().updateSkyBrightness();
            mob.journey.plan(h.getLevel());
        } finally {
            h.setTime(previousTime);
            h.getLevel().updateSkyBrightness();
        }
        h.assertValueEqual(mob.journey.stepName(), "DIAMOND", "Journey step before the stronghold");
        h.assertTrue(mob.currentTask().spec() instanceof TaskSpec.ResourceGoal goal && "diamant".equals(goal.resource()),
                "Diamond gear should start by mining diamonds, got " + mob.currentTask());
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void crafts_a_bow_from_carried_string(GameTestHelper h) {
        var mob = fixture(h).mob();
        h.setBlock(START.east(), Blocks.CRAFTING_TABLE);
        mob.giveToInventory(new ItemStack(Items.STRING, 3));
        mob.giveToInventory(new ItemStack(Items.STICK, 4));
        h.assertTrue(CompanionNeeds.rangedCraftable(mob), "Three string should allow a bow");
        h.assertTrue(CompanionNeeds.craftRangedFromInventory(mob), "No bow task planned");
        run(mob, 100);
        h.assertTrue(CompanionCraft.owns(mob, "bow"), "Bow not crafted");
        h.succeed();
    }
}
