package dev.begleiter;

import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

public class JourneyGameTests {
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

    /** Heile 5x5-Hülle des Standardhauses (Boden, Wände ohne Türlücke, Dach), relativ zur Teststruktur. */
    private static void buildShell(GameTestHelper h, BlockPos origin) {
        buildShell(h, origin, true);
    }

    private static void buildShell(GameTestHelper h, BlockPos origin, boolean door) {
        if (door) {
            var lower = Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.NORTH);
            h.setBlock(origin.offset(2, 1, 0), lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
            h.setBlock(origin.offset(2, 2, 0), lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        }
        for (int dy = 0; dy <= 4; dy++)
            for (int dx = 0; dx < 5; dx++)
                for (int dz = 0; dz < 5; dz++) {
                    boolean shell = dy == 0 || dy == 4 || dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    boolean doorGap = dz == 0 && dx == 2 && (dy == 1 || dy == 2);
                    if (shell && !doorGap) h.setBlock(origin.offset(dx, dy, dz), Blocks.OAK_PLANKS);
                }
    }

    /** Tag einstellen: nachts geht er an der Oberfläche nicht mehr raus (Nachtlager statt Arbeit). */
    private static void daytime(GameTestHelper h) {
        h.setTime(1000);
        h.getLevel().updateSkyBrightness();
    }

    private static CompanionEntity restore(GameTestHelper h, CompanionEntity original) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        original.saveWithoutId(output);
        var restored = new CompanionEntity(CompanionMod.COMPANION, h.getLevel());
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), output.buildResult()));
        return restored;
    }

    private static void run(CompanionEntity mob, int ticks) {
        var task = new CompanionTaskGoal(mob);
        for (int i = 0; i < ticks && mob.hasTasks(); i++) {
            mob.tickCount++;
            task.tick();
        }
    }

    @GameTest(structure = ARENA)
    public void journey_starts_from_empty_and_crafts_both_picks(GameTestHelper h) {
        daytime(h); // nachts plant er an der Oberfläche nur noch Unterschlupf
        var fixture = fixture(h);
        var mob = fixture.mob();
        fixture.command("Spiel selbstständig bis zur Eisenrüstung");
        h.assertTrue(mob.journey.active() && mob.journey.plan(h.getLevel()), "Owner could not start the Survival objective");
        h.assertTrue(mob.currentTask().spec() instanceof TaskSpec.Gather, "Empty inventory did not start with gathering");
        mob.clearJourneyTasks();
        h.setBlock(START.east(), Blocks.CRAFTING_TABLE);
        mob.giveToInventory(new ItemStack(Items.OAK_PLANKS, 12));
        mob.giveToInventory(new ItemStack(Items.COBBLESTONE, 2));
        mob.journey.plan(h.getLevel());
        run(mob, 60);
        h.assertTrue(CompanionCraft.owns(mob, "wooden_pickaxe"), "Survival objective did not craft the wooden pickaxe");
        mob.giveToInventory(new ItemStack(Items.COBBLESTONE));
        mob.journey.plan(h.getLevel());
        run(mob, 60);
        h.assertTrue(CompanionCraft.owns(mob, "stone_pickaxe"), "Survival objective did not craft the stone pickaxe");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void journey_restores_goal_without_replaying_stale_tasks(GameTestHelper h) {
        daytime(h); // nachts plant er an der Oberfläche nur noch Unterschlupf
        var fixture = fixture(h);
        fixture.command("play survival to the dragon");
        var mob = fixture.mob();
        mob.journey.plan(h.getLevel());
        var loaded = restore(h, mob);
        h.assertTrue(loaded.journey.active() && !loaded.hasTasks(), "Save did not separate the objective from its old queue");
        loaded.journey.plan(h.getLevel());
        h.assertTrue(loaded.journey.running() && loaded.hasTasks(), "Restored objective did not replan");
        fixture.command("Spielziel pausieren");
        h.assertTrue(!mob.journey.active() && mob.isStaying() && !mob.hasTasks(), "Pause did not stop the player's goal");
        h.assertTrue(!restore(h, mob).journey.active(), "Pause was lost after a reload");
        fixture.command("Spielziel weiter");
        h.assertTrue(mob.journey.active(), "Owner could not resume the Survival objective");
        fixture.command("Spielziel abbrechen");
        h.assertTrue(!mob.journey.hasGoal() && !mob.hasTasks(), "Cancel left the objective running");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void journey_harvests_berries_without_destroying_the_bush(GameTestHelper h) {
        daytime(h); // nachts plant er an der Oberfläche nur noch Unterschlupf
        var fixture = fixture(h);
        BlockPos bush = START.east();
        h.setBlock(bush.below(), Blocks.DIRT);
        h.setBlock(bush, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
        var mob = fixture.mob();
        mob.food.set(10);
        fixture.command("play survival to iron armor");
        mob.journey.plan(h.getLevel());
        h.assertTrue(mob.currentTask().spec() instanceof TaskSpec.Farm farm && farm.forage(), "Hungry companion did not choose nearby food");
        run(mob, 10);
        h.assertTrue(mob.foodCount() >= 2, "Harvest did not feed the companion");
        var state = h.getLevel().getBlockState(h.absolutePos(bush));
        h.assertTrue(state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(SweetBerryBushBlock.AGE) == 1,
                "Harvest did not preserve the plant");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void journey_food_and_tools_failures_do_not_reset_each_others_backoff(GameTestHelper h) {
        var fixture = fixture(h);
        BlockPos bush = START.east();
        h.setBlock(bush.below(), Blocks.DIRT);
        h.setBlock(bush, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
        var mob = fixture.mob();
        mob.food.set(10);
        fixture.command("play survival to iron armor");
        long previousTime = h.getLevel().getDefaultClockTime();
        try {
            h.setTime(1000);
            h.getLevel().updateSkyBrightness();
            mob.journey.plan(h.getLevel());
            h.assertValueEqual(mob.journey.stepName(), "FOOD", "Hungry companion did not start with food");
            mob.journey.fail();
            mob.journey.plan(h.getLevel());
            h.assertValueEqual(mob.journey.stepName(), "TOOLS", "A failed food step blocked the next step");
            h.assertTrue(mob.journey.running(), "Tools step did not start while food waits");
            mob.journey.fail();
            var start = mob.blockPosition();
            mob.journey.plan(h.getLevel());
            // Beide Schritte warten; erlaubt ist nur das weite Wegziehen aus der kargen Gegend, keine neue Nahsuche
            h.assertTrue(!mob.hasTasks() || mob.currentTask().spec() instanceof TaskSpec.MoveTo move
                    && move.pos().distSqr(start) >= 24 * 24, "Plan restarted a nearby search while both failed steps should wait");
            h.assertTrue(!mob.hasTasks() || mob.journey.barrenRounds() >= 2, "Unexpected work after two failures");
        } finally {
            h.setTime(previousTime);
            h.getLevel().updateSkyBrightness();
        }
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void journey_hunger_does_not_abort_tools_while_food_waits(GameTestHelper h) {
        var fixture = fixture(h);
        BlockPos bush = START.east();
        h.setBlock(bush.below(), Blocks.DIRT);
        h.setBlock(bush, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
        var mob = fixture.mob();
        mob.food.set(5);
        fixture.command("play survival to iron armor");
        long previousTime = h.getLevel().getDefaultClockTime();
        try {
            h.setTime(1000);
            h.getLevel().updateSkyBrightness();
            mob.journey.plan(h.getLevel());
            h.assertValueEqual(mob.journey.stepName(), "FOOD", "Hungry companion did not start with food");
            mob.journey.fail();
            mob.journey.plan(h.getLevel());
            h.assertValueEqual(mob.journey.stepName(), "TOOLS", "Tools step did not start while food waits");
            mob.journey.review();
            h.assertTrue(mob.journey.running(), "Hunger aborted the wood search although food could not do anything");
        } finally {
            h.setTime(previousTime);
            h.getLevel().updateSkyBrightness();
        }
        h.succeed();
    }

    @GameTest(structure = ARENA, skyAccess = true)
    public void journey_barren_area_moves_far_in_one_direction(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        mob.food.set(5);
        fixture.command("play survival to iron armor");
        long previousTime = h.getLevel().getDefaultClockTime();
        try {
            h.setTime(1000);
            h.getLevel().updateSkyBrightness();
            var start = mob.blockPosition();
            BlockPos first = null;
            for (int round = 0; round < 6 && first == null; round++) {
                mob.journey.plan(h.getLevel());
                if (mob.hasTasks() && mob.currentTask().spec() instanceof TaskSpec.MoveTo move
                        && move.pos().distSqr(start) >= 26 * 26) first = move.pos();
                else if (mob.journey.running()) mob.journey.fail();
            }
            h.assertTrue(first != null, "Two barren rounds did not start a far relocation (barren="
                    + mob.journey.barrenRounds() + ")");
            // Er kam nicht von der Stelle (blockiert): der nächste weite Schritt weicht in eine andere Richtung aus.
            // Ob dort überhaupt etwas frei ist, hängt vom zufälligen Gelände um die Arena ab.
            var next = mob.relocatePoint();
            h.assertTrue(next == null || next.spec() instanceof TaskSpec.MoveTo move && !move.pos().equals(first),
                    "A blocked relocation did not turn to another far target");
        } finally {
            h.setTime(previousTime);
            h.getLevel().updateSkyBrightness();
        }
        h.succeed();
    }

    @GameTest(structure = ARENA, skyAccess = true, environment = "begleiter-gametest:night")
    public void journey_seals_in_at_night_without_a_house_and_leaves_in_the_morning(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        mob.giveToInventory(new ItemStack(Items.DIRT, 16));
        mob.giveToInventory(new ItemStack(Items.BREAD, 8));
        mob.setOnGround(true);
        fixture.owner().snapTo(mob.getX() + 4, mob.getY(), mob.getZ(), 0, 0); // der Besitzer steht daneben, nicht im Lager
        fixture.command("play survival to iron armor");
        long previousTime = h.getLevel().getDefaultClockTime();
        try {
            h.getLevel().updateSkyBrightness(); // eigene Testumgebung "night": nicht die gemeinsame Uhr verstellen
            mob.journey.plan(h.getLevel());
            h.assertTrue(mob.isSheltered(), "Without a house the companion did not seal himself in for the night");
            h.assertValueEqual(mob.journey.stepName(), "SHELTER", "Night camp is not reported as shelter");
            var feet = mob.blockPosition();
            h.assertTrue(!h.getLevel().getBlockState(feet.north()).canBeReplaced()
                    && !h.getLevel().getBlockState(feet.above(2)).canBeReplaced(), "The night camp has gaps");
            mob.setHealth(mob.getMaxHealth());
            mob.shelter.tick(h.getLevel());
            h.assertTrue(mob.isSheltered(), "Healthy companion broke out while it was still night");
            h.setTime(1000);
            h.getLevel().updateSkyBrightness();
            mob.setHealth(mob.getMaxHealth() * 0.5f);
            mob.shelter.tick(h.getLevel());
            h.assertTrue(!mob.isSheltered() && h.getLevel().getBlockState(feet.north()).canBeReplaced(),
                    "The companion did not dig himself out in the morning");
        } finally {
            h.setTime(previousTime);
            h.getLevel().updateSkyBrightness();
        }
        h.succeed();
    }

    @GameTest(structure = ARENA, skyAccess = true, environment = "begleiter-gametest:night")
    public void free_play_seals_in_at_night_without_a_house(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        mob.freeRoam(); // freies Spiel ohne Spielziel
        mob.giveToInventory(new ItemStack(Items.DIRT, 16));
        mob.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        mob.giveToInventory(new ItemStack(Items.BREAD, 8));
        mob.setOnGround(true);
        fixture.owner().snapTo(mob.getX() + 4, mob.getY(), mob.getZ(), 0, 0);
        h.getLevel().updateSkyBrightness();
        h.assertTrue(!mob.journey.active(), "Test setup: a survival goal is active");
        CompanionNeeds.decide(mob);
        h.assertTrue(mob.isSheltered(), "In free play without a house the companion did not take shelter at night");
        h.succeed();
    }

    @GameTest(structure = ARENA, skyAccess = true, environment = "begleiter-gametest:night")
    public void journey_digs_in_at_night_without_blocks(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        h.setBlock(START.below(), Blocks.DIRT);
        h.setBlock(START.below(2), Blocks.DIRT);
        h.setBlock(START.below(3), Blocks.STONE);
        mob.giveToInventory(new ItemStack(Items.BREAD, 8)); // satt, keine Müll-Blöcke
        mob.setOnGround(true);
        fixture.owner().snapTo(mob.getX() + 4, mob.getY(), mob.getZ(), 0, 0);
        fixture.command("play survival to iron armor");
        h.getLevel().updateSkyBrightness();
        mob.journey.plan(h.getLevel());
        h.assertValueEqual(mob.journey.stepName(), "SHELTER", "Without blocks the companion did not take shelter at night");
        var tasks = mob.peekTasks(4);
        h.assertTrue(tasks.size() >= 2 && tasks.get(0).spec() instanceof TaskSpec.Break first
                && first.pos().equals(h.absolutePos(START.below())) && tasks.get(1).spec() instanceof TaskSpec.Break second
                && second.pos().equals(h.absolutePos(START.below(2))), "The companion did not dig in two blocks: " + tasks);
        h.succeed();
    }

    @GameTest(structure = ARENA, skyAccess = true, environment = "begleiter-gametest:night")
    public void journey_goes_home_at_night_instead_of_foraging(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        var origin = START.offset(3, -1, -2);
        buildShell(h, origin);
        var house = new CompoundTag();
        house.putBoolean("HouseBuilt", true);
        house.putLong("HouseOrigin", h.absolutePos(origin).asLong());
        mob.house.loadMemory(house);
        mob.setHome(h.absolutePos(origin.offset(2, 1, 3)));
        mob.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        mob.food.set(10); // hungrig, aber nicht am Verhungern
        fixture.command("play survival to iron armor");
        h.getLevel().updateSkyBrightness();
        mob.journey.plan(h.getLevel());
        h.assertValueEqual(mob.journey.stepName(), "SHELTER", "At night the companion went foraging instead of home");
        h.assertTrue(mob.currentTask() != null && mob.currentTask().spec() instanceof TaskSpec.MoveTo move
                && move.pos().equals(mob.houseInside()), "The night plan did not lead into the house");
        h.succeed();
    }

    @GameTest(structure = ARENA, skyAccess = true, environment = "begleiter-gametest:night")
    public void journey_plugs_a_doorless_house_with_planks_at_night(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        var origin = START.offset(-2, -1, -2);
        buildShell(h, origin, false); // Türlücke bleibt offen, Tür fehlt
        var house = new CompoundTag();
        house.putBoolean("HouseBuilt", true);
        house.putLong("HouseOrigin", h.absolutePos(origin).asLong());
        mob.house.loadMemory(house);
        mob.setHome(h.absolutePos(START.south()));
        mob.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        mob.giveToInventory(new ItemStack(Items.OAK_PLANKS, 10)); // keine Müll-Blöcke, keine Tür
        fixture.owner().snapTo(mob.getX() + 8, mob.getY(), mob.getZ(), 0, 0);
        fixture.command("play survival to iron armor");
        h.getLevel().updateSkyBrightness();
        mob.journey.plan(h.getLevel());
        h.assertTrue(mob.doorClosed && mob.hasTasks(), "Without junk blocks the open doorway stayed open at night");
        run(mob, 40);
        var gap = h.absolutePos(origin.offset(2, 1, 0));
        h.assertTrue(!h.getLevel().getBlockState(gap).canBeReplaced() && !h.getLevel().getBlockState(gap.above()).canBeReplaced(),
                "The doorway was not plugged");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void journey_repairs_a_damaged_house_before_iron(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        var origin = START.offset(-2, -1, -2);
        for (int dy = 0; dy <= 4; dy++)
            for (int dx = 0; dx < 5; dx++)
                for (int dz = 0; dz < 5; dz++) {
                    boolean shell = dy == 0 || dy == 4 || dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    boolean blownAway = dz == 0 && dy <= 3; // Vorderseite weg (Creeper)
                    if (shell && !blownAway) h.setBlock(origin.offset(dx, dy, dz), Blocks.OAK_PLANKS);
                }
        var house = new CompoundTag();
        house.putBoolean("HouseBuilt", true);
        house.putLong("HouseOrigin", h.absolutePos(origin).asLong());
        mob.house.loadMemory(house);
        mob.setHome(h.absolutePos(START.south()));
        mob.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        mob.giveToInventory(new ItemStack(Items.BREAD, 8));
        mob.giveToInventory(new ItemStack(Items.OAK_PLANKS, 32));
        h.assertTrue(mob.houseDamaged(h.getLevel()), "An open house was not recognised as damaged");
        fixture.command("play survival to iron armor");
        long previousTime = h.getLevel().getDefaultClockTime();
        try {
            h.setTime(1000);
            h.getLevel().updateSkyBrightness();
            mob.journey.plan(h.getLevel());
            h.assertValueEqual(mob.journey.stepName(), "BASE", "A damaged house did not send the companion back to the base step");
            h.assertTrue(mob.currentTask() != null && mob.currentTask().spec() instanceof TaskSpec.House rebuild
                    && h.absolutePos(origin).equals(rebuild.pos()), "The repair did not target the existing house");
        } finally {
            h.setTime(previousTime);
            h.getLevel().updateSkyBrightness();
        }
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void journey_smelts_partial_iron_and_finishes_with_equipment(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        var home = new CompoundTag();
        home.putBoolean("HouseBuilt", true);
        buildShell(h, START.offset(-2, -1, -2));
        home.putLong("HouseOrigin", h.absolutePos(START.offset(-2, -1, -2)).asLong());
        mob.house.loadMemory(home);
        mob.setHome(h.absolutePos(START.south()));
        h.setBlock(START.east(), Blocks.CRAFTING_TABLE);
        h.setBlock(START.north(), Blocks.FURNACE);
        mob.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        mob.giveToInventory(new ItemStack(Items.RAW_IRON, 28));
        mob.giveToInventory(new ItemStack(Items.CHARCOAL, 4));
        mob.giveToInventory(new ItemStack(Items.OAK_PLANKS, 16));
        mob.giveToInventory(new ItemStack(Items.BREAD, 8));
        fixture.command("play survival to iron armor");
        long previousTime = h.getLevel().getDefaultClockTime();
        try {
            h.setTime(1000);
            h.getLevel().updateSkyBrightness();
            mob.journey.plan(h.getLevel());
            h.assertTrue(mob.currentTask().spec() instanceof TaskSpec.Smelt smelt && smelt.count() == 3,
                    "The first iron upgrade waited for the entire armor set");
            for (int phase = 0; phase < 15 && mob.journey.active(); phase++) {
                run(mob, 600);
                mob.journey.plan(h.getLevel());
            }
        } finally {
            h.setTime(previousTime);
            h.getLevel().updateSkyBrightness();
        }
        h.assertTrue(CompanionCraft.tier(mob, "pickaxe") >= 3 && CompanionNeeds.armorIngotsNeeded(mob) == 0,
                "Companion did not craft its full iron equipment");
        h.assertTrue(mob.journey.hasGoal() && !mob.journey.active(), "Iron gear did not finish the Survival goal");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void journey_recovery_after_reload_chooses_only_its_own_drops(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        fixture.command("play survival to iron armor");
        mob.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        mob.giveToInventory(new ItemStack(Items.BREAD, 8));
        var pos = h.absolutePos(START.east());
        mob.journey.rememberDeath(h.getLevel(), pos);
        var own = new ItemEntity(h.getLevel(), pos.getX() + .5, pos.getY(), pos.getZ() + .5, new ItemStack(Items.APPLE));
        own.addTag(mob.journey.recoveryTag());
        var other = new ItemEntity(h.getLevel(), pos.getX() + .5, pos.getY(), pos.getZ() + .5, new ItemStack(Items.GOLD_INGOT));
        h.getLevel().addFreshEntity(own);
        h.getLevel().addFreshEntity(other);
        var loaded = restore(h, mob);
        long previousTime = h.getLevel().getDefaultClockTime();
        try {
            h.setTime(1000); // nachts geht er ohne Rüstung nicht bergen
            h.getLevel().updateSkyBrightness();
            loaded.journey.plan(h.getLevel());
        } finally {
            h.setTime(previousTime);
            h.getLevel().updateSkyBrightness();
        }
        h.assertTrue(loaded.currentTask().spec() instanceof TaskSpec.MoveTo move && pos.equals(move.pos()),
                "The saved death location did not become a recovery trip");
        h.assertTrue(loaded.journey.recoveryItem(own) && !loaded.journey.recoveryItem(other),
                "Recovery picked up a nearby drop which belonged to somebody else");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void journey_respawns_at_a_safe_loaded_base_without_duplicating_the_soul(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        var home = h.absolutePos(START);
        var house = new CompoundTag();
        house.putBoolean("HouseBuilt", true);
        house.putLong("HouseOrigin", home.offset(-2, -1, -2).asLong());
        mob.house.loadMemory(house);
        mob.setHome(home.south());
        fixture.command("play survival to iron armor");
        var soul = mob.soulId();
        var death = h.absolutePos(START.west(20));
        mob.snapTo(death.getX() + .5, death.getY(), death.getZ() + .5, 0, 0);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        mob.kill(h.getLevel());
        var living = h.getLevel().getEntitiesOfClass(CompanionEntity.class, new AABB(home).inflate(3),
                companion -> companion.isAlive() && companion.soulId().equals(soul));
        h.assertValueEqual(living.size(), 1, "Death did not yield exactly one successor at the base");
        h.assertTrue(living.getFirst().journey.active(), "The successor lost its owner or Survival goal");
        h.assertTrue(fixture.owner().getInventory().countItem(CompanionMod.COMPANION_CORE) == 0,
                "A soul core was emitted alongside automatic respawn");
        h.assertTrue(!h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(death).inflate(2),
                item -> item.getItem().is(Items.IRON_SWORD) && item.entityTags().contains(mob.journey.recoveryTag())).isEmpty(),
                "Equipment worn at death was not marked for recovery");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void journey_respawns_at_a_distant_unloaded_base(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        var level = h.getLevel();
        var start = h.absolutePos(START);
        int x = start.getX() + 640, z = start.getZ();
        // Bodenhöhe vom Generator, ohne den Chunk zu laden
        int y = level.getChunkSource().getGenerator().getBaseHeight(x, z,
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, level, level.getChunkSource().randomState());
        var home = new BlockPos(x, y, z);
        h.assertTrue(!level.hasChunkAt(home), "Test setup: the distant base was already loaded");
        var house = new CompoundTag();
        house.putBoolean("HouseBuilt", true);
        house.putLong("HouseOrigin", home.offset(-2, -1, -2).asLong());
        mob.house.loadMemory(house);
        mob.setHome(home.south());
        fixture.command("play survival to iron armor");
        var soul = mob.soulId();
        mob.kill(level);
        h.assertTrue(fixture.owner().getInventory().countItem(CompanionMod.COMPANION_CORE) == 0,
                "A soul core was emitted although the base could be loaded");
        h.assertTrue(level.hasChunkAt(home), "The respawn did not load the distant base");
        // Das Ticket wirkt erst nach ein paar Ticks; bis dahin sind Entities dort noch nicht sichtbar
        h.succeedWhen(() -> {
            var living = level.getEntitiesOfClass(CompanionEntity.class, new AABB(home).inflate(3),
                    companion -> companion.isAlive() && companion.soulId().equals(soul));
            h.assertValueEqual(living.size(), 1, "Death far from an unloaded base did not respawn there");
            h.assertTrue(living.getFirst().journey.active(), "The successor lost its owner or Survival goal");
            living.getFirst().discard();
        });
    }

    @GameTest(structure = ARENA, maxTicks = 600)
    public void journey_rechecks_nether_supplies_and_enters_only_a_live_portal(GameTestHelper h) {
        var fixture = fixture(h);
        var mob = fixture.mob();
        for (var item : new net.minecraft.world.item.Item[]{
                Items.DIAMOND_PICKAXE, Items.DIAMOND_SWORD, Items.BOW, Items.SHIELD, Items.DIAMOND_HELMET,
                Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Items.WATER_BUCKET})
            mob.giveToInventory(new ItemStack(item));
        mob.giveToInventory(new ItemStack(Items.ARROW, 32));
        mob.giveToInventory(new ItemStack(Items.BREAD, 12));
        mob.giveToInventory(new ItemStack(Items.COBBLESTONE, 48)); // Blöcke fürs End
        mob.giveToInventory(new ItemStack(Items.BLAZE_POWDER, 12));
        h.assertTrue(CompanionBrain.needsBlaze(mob), "A successful trip needs powder for the reserve eyes too");
        mob.giveToInventory(new ItemStack(Items.BLAZE_ROD));
        h.assertTrue(!CompanionBrain.needsBlaze(mob), "Existing powder should count toward the Nether objective");
        var house = new CompoundTag();
        house.putBoolean("HouseBuilt", true);
        buildShell(h, START.offset(-9, -1, -9));
        house.putLong("HouseOrigin", h.absolutePos(START.offset(-9, -1, -9)).asLong());
        mob.house.loadMemory(house);
        mob.setHome(h.absolutePos(START.south()));
        fixture.command("play survival to the dragon");
        BlockPos portal = START.east(2);
        mob.endPortalPos = h.absolutePos(portal);
        mob.journey.plan(h.getLevel());
        h.assertTrue(mob.currentTask().spec() instanceof TaskSpec.Stronghold, "Broken portal should trigger stronghold recovery");
        mob.clearJourneyTasks();
        h.setBlock(portal, Blocks.END_PORTAL);
        mob.endPortalPos = h.absolutePos(portal);
        mob.journey.plan(h.getLevel());
        h.assertTrue(mob.currentTask().spec() instanceof TaskSpec.MoveTo move
                && "minecraft:end_portal".equals(move.portal()) && h.absolutePos(portal).equals(move.pos()),
                "Supplied companion did not choose to enter the End portal");
        mob.setNoAi(false);
        var end = h.getLevel().getServer().getLevel(Level.END);
        var uuid = mob.getUUID();
        h.succeedWhen(() -> {
            h.assertTrue(end != null && end.getEntity(uuid) instanceof CompanionEntity && !mob.isAlive(),
                    "Companion has not walked through the End portal");
            var arrived = (CompanionEntity) end.getEntity(uuid);
            h.assertTrue(arrived.journey.fightingDragon(), "The objective did not survive the portal transition");
            var dragon = EntityTypes.ENDER_DRAGON.create(end, EntitySpawnReason.COMMAND);
            h.assertTrue(dragon != null, "Could not create a dragon to exercise vanilla damage");
            dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
            float health = dragon.getHealth();
            dragon.hurt(end, dragon.head, end.damageSources().mobAttack(arrived), 6);
            h.assertTrue(dragon.getHealth() < health, "Vanilla rejected damage from the companion");
            arrived.discard();
        });
    }
}
