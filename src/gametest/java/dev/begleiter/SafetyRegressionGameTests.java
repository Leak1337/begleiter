package dev.begleiter;

import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Regressionen für sichere Weltänderungen und die Erhaltung von Material und Auftragszielen. */
public class SafetyRegressionGameTests {
    private CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.buildModeOverride = false;
        return c;
    }

    /**
     * Brücke an einer Stelle mit Redstone-Staub eines Spielers: Die Wegausführung darf den Staub nicht wegräumen
     * (nur Natürliches wie Blumen), sondern bricht ab und lässt neu planen.
     */
    @GameTest
    public void bridgePlacementKeepsPlayerRedstone(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(3, 1, 2, Blocks.AIR);
        h.setBlock(3, 0, 2, Blocks.STONE);
        h.setBlock(3, 1, 2, Blocks.REDSTONE_WIRE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 2, 2));
        c.stay(); c.buildModeOverride = false;
        c.giveToInventory(new ItemStack(Items.DIRT, 4));
        BlockPos src = h.absolutePos(new BlockPos(2, 2, 2)), dest = h.absolutePos(new BlockPos(3, 2, 2));
        var move = new dev.begleiter.nav.Movement(dev.begleiter.nav.Move.TRAVERSE_E, src, dest, 10);
        var status = move.tick(c, new dev.begleiter.nav.NavContext(c));
        h.assertBlockPresent(Blocks.REDSTONE_WIRE, new BlockPos(3, 1, 2));
        h.assertTrue(status == dev.begleiter.nav.Movement.Status.FAILED, "Brücke nicht abgebrochen: " + status);
        h.assertValueEqual(CompanionCraft.count(c, "dirt"), 4, "Erde verbraucht");
        h.succeed();
    }

    /**
     * Stamm als Zutat und als Brennstoff: Der letzte Stamm darf nicht gleichzeitig verbrannt und zu Holzkohle werden
     * (vorher entstand aus einem Stamm eine Holzkohle plus Restbrennwert).
     */
    @GameTest
    public void smeltingCannotUseTheSameLogAsFuelAndInput(GameTestHelper h) {
        var c = companion(h);
        var logs = net.minecraft.world.item.crafting.Ingredient.of(Items.OAK_LOG);
        c.giveToInventory(new ItemStack(Items.OAK_LOG));
        boolean smelted = CompanionCraft.smeltTarget(c, Items.CHARCOAL, logs);
        h.assertFalse(smelted, "Smelting reported success without a second log");
        h.assertValueEqual(CompanionCraft.count(c, "charcoal"), 0, "Charcoal from a log that was burnt as fuel");
        // Der verbrannte Stamm bleibt als Brennwert erhalten: mit einem neuen Stamm entsteht genau eine Holzkohle.
        c.giveToInventory(new ItemStack(Items.OAK_LOG));
        h.assertTrue(CompanionCraft.smeltTarget(c, Items.CHARCOAL, logs), "Stored fuel was lost");
        h.assertValueEqual(CompanionCraft.count(c, "charcoal"), 1, "Charcoal after the second log");
        h.assertValueEqual(CompanionCraft.count(c, "#logs"), 0, "Logs left");
        h.succeed();
    }

    /**
     * Holz sammeln und Stollen graben: Eine Wand aus Stämmen ohne Laub (Blockhütte) ist kein Baum. Sie wird weder
     * als Holzquelle eingeplant noch durchgegraben; ein Baum mit natürlichem Laub schon.
     */
    @GameTest
    public void logCabinWallIsNotWoodOrTunnel(GameTestHelper h) {
        var c = companion(h);
        BlockPos wallLow = new BlockPos(5, 1, 2), wallHigh = new BlockPos(5, 2, 2), tree = new BlockPos(2, 1, 5);
        h.setBlock(wallLow, Blocks.OAK_LOG);
        h.setBlock(wallHigh, Blocks.OAK_LOG);
        h.setBlock(tree, Blocks.OAK_LOG);
        h.setBlock(tree.above(), Blocks.OAK_LEAVES);
        BlockPos low = h.absolutePos(wallLow), high = h.absolutePos(wallHigh), trunk = h.absolutePos(tree);
        h.runAfterDelay(10, () -> { // erst stehen lassen: die Erreichbarkeitsprüfung braucht einen gelandeten Mob
            var wood = c.gathering.buildTrees(4);
            h.assertTrue(wood.stream().noneMatch(t -> low.equals(t.pos()) || high.equals(t.pos())),
                    "Stämme der Hüttenwand als Holz eingeplant: " + wood);
            h.assertTrue(wood.stream().anyMatch(t -> trunk.equals(t.pos())), "Baum mit Laub nicht als Holz gefunden: " + wood);
            var tunnel = c.gathering.tunnel(net.minecraft.core.Direction.EAST, 5);
            h.assertTrue(tunnel.stream().noneMatch(t -> low.equals(t.pos()) || high.equals(t.pos())),
                    "Stollen gräbt durch die Hüttenwand: " + tunnel);
            h.succeed();
        });
    }

    /**
     * Festgehangen im Wasser: Er gräbt sich nur durch gewachsenes Gelände heraus. Eine Glaswand (Farmkanal) bleibt
     * stehen, statt dessen baut er sich mit einem Block heraus; eine Erdwand darf er öffnen.
     */
    @GameTest
    public void swimEscapeKeepsBuiltWalls(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.DIRT, 4));
        h.setBlock(2, 1, 2, Blocks.WATER);
        h.setBlock(3, 1, 2, Blocks.GLASS);
        h.setBlock(3, 2, 2, Blocks.GLASS);
        c.snapTo(h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 1, 2.5)));
        c.swim.escape(h.getLevel(), h.absolutePos(new BlockPos(7, 1, 2)));
        h.assertBlockPresent(Blocks.GLASS, new BlockPos(3, 1, 2));
        h.assertBlockPresent(Blocks.GLASS, new BlockPos(3, 2, 2));
        h.setBlock(3, 1, 2, Blocks.DIRT);
        h.setBlock(3, 2, 2, Blocks.DIRT);
        c.snapTo(h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 1, 2.5)));
        c.swim.escape(h.getLevel(), h.absolutePos(new BlockPos(7, 1, 2)));
        h.assertBlockNotPresent(Blocks.DIRT, new BlockPos(3, 1, 2));
        h.succeed();
    }

    /** Haus- und Portalplatz: nur gewachsener Boden, nichts Gebautes darüber (kein Dach, keine Fackel, kein Redstone). */
    @GameTest
    public void buildSiteNeedsNaturalGroundAndNothingBuiltAbove(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos corner = h.absolutePos(new BlockPos(1, 0, 1));
        java.util.function.Consumer<net.minecraft.world.level.block.Block> floor = b -> {
            for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) h.setBlock(x, 0, z, b);
        };
        // Die Testumgebung deckt jeden Test mit Barrieren ab; die Höhenkarte soll den eigenen Boden sehen.
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) for (int y = 1; y <= 24; y++)
            level.setBlock(corner.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        floor.accept(Blocks.GRASS_BLOCK);
        h.setBlock(2, 1, 2, Blocks.SHORT_GRASS); // Gras und Blumen stören nicht
        h.assertTrue(CompanionHouse.buildSiteAt(level, corner.getX(), corner.getZ()) != null, "Grass rejected");
        h.setBlock(3, 1, 3, Blocks.TORCH);
        h.assertTrue(CompanionHouse.buildSiteAt(level, corner.getX(), corner.getZ()) == null, "Torch on the site accepted");
        h.setBlock(3, 1, 3, Blocks.REDSTONE_WIRE);
        h.assertTrue(CompanionHouse.buildSiteAt(level, corner.getX(), corner.getZ()) == null, "Redstone on the site accepted");
        h.setBlock(3, 1, 3, Blocks.AIR);
        h.setBlock(2, 1, 2, Blocks.AIR);
        floor.accept(Blocks.OAK_PLANKS);
        h.assertTrue(CompanionHouse.buildSiteAt(level, corner.getX(), corner.getZ()) == null, "Plank floor (roof) accepted");
        h.succeed();
    }

    /** Station im engen Gang: Platz nur in gewachsenem Fels/Boden schaffen, nie in gebauten Wänden. */
    @GameTest
    public void stationNeverDigsIntoBuiltWalls(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos p = h.absolutePos(new BlockPos(3, 1, 3));
        for (var b : java.util.List.of(Blocks.OAK_PLANKS, Blocks.GLASS, Blocks.STONE_BRICKS, Blocks.BRICKS)) {
            h.setBlock(3, 1, 3, b);
            h.assertFalse(CompanionStations.mayDigForStation(level, p, level.getBlockState(p)), b + " would be dug out");
        }
        for (var b : java.util.List.of(Blocks.STONE, Blocks.DIRT, Blocks.DEEPSLATE, Blocks.NETHERRACK)) {
            h.setBlock(3, 1, 3, b);
            h.assertTrue(CompanionStations.mayDigForStation(level, p, level.getBlockState(p)), b + " not usable");
        }
        h.succeed();
    }

    /** Doppelstufe kostet zwei Stufen, auch wenn sie in zwei verschiedenen Inventarplätzen liegen. */
    @GameTest(maxTicks = 100)
    public void doubleSlabCostsTwoSlabsFromSeparateStacks(GameTestHelper h) {
        var c = companion(h);
        var target = h.absolutePos(new BlockPos(4, 1, 4));
        c.getInventory().setItem(0, new ItemStack(Items.OAK_SLAB));
        c.getInventory().setItem(5, new ItemStack(Items.OAK_SLAB));
        var slab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,
                net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
        c.build = CompanionBuilder.create("slab-probe", Map.of(target, slab));
        c.addTask(CompanionEntity.Task.build());
        h.runAfterDelay(60, () -> {
            h.assertTrue(h.getLevel().getBlockState(target).equals(slab), "Double slab not placed");
            h.assertValueEqual(CompanionCraft.count(c, "oak_slab"), 0, "Slabs left after placing a double slab");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void cancelMustStopBuild(GameTestHelper h) {
        var c = companion(h);
        var target = h.absolutePos(new BlockPos(4, 1, 4));
        c.giveToInventory(new ItemStack(Items.DIRT));
        c.build = CompanionBuilder.create("cancel-probe", Map.of(target, Blocks.DIRT.defaultBlockState()));
        c.addTask(CompanionEntity.Task.build());
        c.cancelOrders(); // ausdrücklicher Abbruch; cancelAll() ist die interne Unterbrechung und behält den Bau
        h.runAfterDelay(60, () -> {
            h.assertTrue(h.getLevel().getBlockState(target).isAir(), "Cancelled build changed the world");
            h.succeed();
        });
    }

    @GameTest
    public void fourCandlesMustCostFourItems(GameTestHelper h) {
        var state = Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 4);
        h.assertValueEqual(CompanionBuilder.itemCount(state), 4, "four-candle material cost");
        h.succeed();
    }

    @GameTest
    public void birchPlanMustNotCountOakAsBirch(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 8));
        var plan = CompanionPlanner.obtain(c, Items.BIRCH_PLANKS, 8);
        h.assertTrue(plan == null || !plan.tasks().isEmpty(), "Planner claims birch is already available with oak only");
        h.succeed();
    }

    @GameTest
    public void cakeCraftMustReturnBuckets(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.MILK_BUCKET));
        c.giveToInventory(new ItemStack(Items.MILK_BUCKET));
        c.giveToInventory(new ItemStack(Items.MILK_BUCKET));
        c.giveToInventory(new ItemStack(Items.SUGAR, 2));
        c.giveToInventory(new ItemStack(Items.EGG));
        c.giveToInventory(new ItemStack(Items.WHEAT, 3));
        h.assertTrue(CompanionRecipes.craft(c, h.getLevel(), Items.CAKE, 1), "Cake recipe not executed");
        h.assertValueEqual(CompanionCraft.count(c, "bucket"), 3, "returned buckets");
        h.succeed();
    }

    /** Wie beim Terraforming: Freiräumen baut nur ab, was beim Befehl dort stand. */
    @GameTest(maxTicks = 200)
    public void clearMustPreserveChestPlacedAfterCommand(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.IRON_PICKAXE));
        @SuppressWarnings("removal")
        var player = h.makeMockServerPlayerInLevel();
        BlockPos start = h.absolutePos(new BlockPos(2, 1, 1));
        player.snapTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0f, 0f); // Blick nach Süden (+z)
        c.setOwner(player.getUUID());
        BlockPos dirt = start.south(2), chest = start.south(3);
        h.getLevel().setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
        h.getLevel().setBlockAndUpdate(chest, Blocks.STONE.defaultBlockState());
        int queued = c.orders.queueClear(player, 1, 1, 3);
        h.assertTrue(queued == 2, "Expected dirt and stone queued, got " + queued);
        h.getLevel().setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        h.runAfterDelay(160, () -> {
            h.assertTrue(h.getLevel().getBlockState(dirt).isAir(), "Unchanged block was not cleared");
            h.assertTrue(h.getLevel().getBlockState(chest).is(Blocks.CHEST), "Clear destroyed a chest placed after the command");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void terraformMustPreserveNewChest(GameTestHelper h) {
        var c = companion(h);
        var target = h.absolutePos(new BlockPos(4, 1, 4));
        h.getLevel().setBlockAndUpdate(target, Blocks.DIRT.defaultBlockState());
        var plan = CompanionTerraform.level(h.getLevel(), CompanionTerraform.Mode.CUT,
                target, 1, 1, target.getY() - 1, Blocks.DIRT);
        c.build = CompanionBuilder.createTerrain(h.getLevel(), "terraform-probe", plan.blocks());
        h.getLevel().setBlockAndUpdate(target, Blocks.CHEST.defaultBlockState());
        c.addTask(CompanionEntity.Task.build());
        h.runAfterDelay(100, () -> {
            h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.CHEST), "Terraform destroyed a chest placed after planning");
            h.succeed();
        });
    }

    @GameTest
    public void gatherMustKeepRemainingDeficit(GameTestHelper h) {
        var target = h.absolutePos(new BlockPos(4, 1, 4));
        // Controlled perception seam: exactly one visible/reachable ore, no dependence on vanilla path initialization.
        var c = new CompanionEntity(CompanionMod.COMPANION, h.getLevel()) {
            @Override public java.util.List<Task> buildGather(
                    java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> pred,
                    int count, boolean surface) {
                return java.util.List.of(Task.breakAt(target, Blocks.COAL_ORE));
            }
        };
        var feet = h.absolutePos(new BlockPos(2, 1, 2));
        c.snapTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0, 0);
        c.stay();
        c.giveToInventory(new ItemStack(Items.IRON_PICKAXE));
        h.setBlock(4, 1, 4, Blocks.COAL_ORE);
        c.addTask(CompanionEntity.Task.gather(Resources.COAL.key(), 5));
        var executor = new CompanionTaskGoal(c);
        executor.start();
        executor.tick();
        boolean remaining = c.peekTasks(100).stream().anyMatch(t ->
                t.spec() instanceof dev.begleiter.task.TaskSpec.ResourceGoal goal && goal.count() >= 4);
        h.assertTrue(remaining, "GATHER dropped the remaining deficit after finding only one coal ore");
        h.succeed();
    }

    @GameTest
    public void navigationMustRecheckLostTool(GameTestHelper h) {
        var c = companion(h);
        c.setNoAi(true);
        c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        var ctx = new dev.begleiter.nav.NavContext(c);
        c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        var src = c.blockPosition();
        var dest = src.east();
        h.getLevel().setBlockAndUpdate(dest, Blocks.DIAMOND_ORE.defaultBlockState());
        var move = dev.begleiter.nav.Move.TRAVERSE_E;
        var movement = new dev.begleiter.nav.Movement(move, src, dest, 200);
        for (int i = 0; i < 100; i++) movement.tick(c, ctx);
        h.assertTrue(h.getLevel().getBlockState(dest).is(Blocks.DIAMOND_ORE),
                "Navigation destroyed diamond ore after losing the correct tool");
        h.succeed();
    }

    @GameTest
    public void structurePositionsMustStayWithinDeclaredSize(GameTestHelper h) {
        var root = new net.minecraft.nbt.CompoundTag();
        var size = new net.minecraft.nbt.ListTag();
        for (int v : new int[]{1, 1, 1}) size.add(net.minecraft.nbt.IntTag.valueOf(v));
        root.put("size", size);
        var palette = new net.minecraft.nbt.ListTag();
        var entry = new net.minecraft.nbt.CompoundTag();
        entry.putString("Name", "minecraft:stone");
        palette.add(entry);
        root.put("palette", palette);
        var blocks = new net.minecraft.nbt.ListTag();
        var block = new net.minecraft.nbt.CompoundTag();
        block.putInt("state", 0);
        var position = new net.minecraft.nbt.ListTag();
        for (int v : new int[]{0, 1_000_000, 0}) position.add(net.minecraft.nbt.IntTag.valueOf(v));
        block.put("pos", position);
        blocks.add(block);
        root.put("blocks", blocks);
        boolean rejected = false;
        CompanionSchematics.Schematic schematic = null;
        try {
            schematic = CompanionSchematics.loadStructure(h.getLevel().registryAccess().lookupOrThrow(
                    net.minecraft.core.registries.Registries.BLOCK), "bounds", root);
        } catch (IllegalArgumentException ex) { rejected = true; }
        h.assertTrue(rejected || schematic.blocks().keySet().stream().allMatch(p ->
                p.getX() >= 0 && p.getX() < 1 && p.getY() >= 0 && p.getY() < 1 && p.getZ() >= 0 && p.getZ() < 1),
                "1x1x1 structure accepted block at y=1000000");
        h.succeed();
    }

    @GameTest
    public void successfulMaterialRequestsMustNotExhaustRetryLimit(GameTestHelper h) {
        var c = companion(h);
        c.setNoAi(true);
        var first = h.absolutePos(new BlockPos(4, 1, 4));
        var next = first.east();
        c.build = CompanionBuilder.create(h.getLevel(), "restock", Map.of(
                first, Blocks.COBBLESTONE.defaultBlockState(), next, Blocks.COBBLESTONE.defaultBlockState()));
        c.build.attempts.put(Items.COBBLESTONE, 3);
        c.giveToInventory(new ItemStack(Items.COBBLESTONE));
        c.giveToInventory(new ItemStack(Items.IRON_PICKAXE));
        c.addTask(CompanionEntity.Task.build());
        var executor = new CompanionTaskGoal(c);
        executor.start();
        for (int i = 0; i < 5; i++) executor.tick();
        h.assertTrue(h.getLevel().getBlockState(first).is(Blocks.COBBLESTONE), "successful delivery was not built");
        h.assertTrue(!c.isPaused(), "Material request after a successful batch paused the build");
        h.assertValueEqual(c.build.attempts.getOrDefault(Items.COBBLESTONE, 0), 1, "new failure series");
        h.succeed();
    }

    @GameTest
    public void sortMustUseAvailableCapacityBeforeDropping(GameTestHelper h) {
        var c = companion(h);
        c.setNoAi(true);
        var first = h.absolutePos(new BlockPos(3, 1, 4));
        var second = h.absolutePos(new BlockPos(5, 1, 4));
        h.getLevel().setBlockAndUpdate(first, Blocks.BARREL.defaultBlockState());
        h.getLevel().setBlockAndUpdate(second, Blocks.BARREL.defaultBlockState());
        var a = c.containerAt(h.getLevel(), first);
        var b = c.containerAt(h.getLevel(), second);
        a.setItem(0, new ItemStack(Items.IRON_PICKAXE));
        for (int i = 1; i < 54; i++) {
            var stack = new ItemStack(Items.BREAD);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("bread-" + i));
            (i < 27 ? a : b).setItem(i < 27 ? i : i - 27, stack);
        }
        CompanionJobs.sortByCategory(c, h.getLevel(), first);
        int retained = 0;
        for (var box : java.util.List.of(a, b))
            for (int slot = 0; slot < box.getContainerSize(); slot++) retained += box.getItem(slot).getCount();
        h.assertValueEqual(retained, 54, "items retained in 54 available container slots");
        h.succeed();
    }

    private static final net.minecraft.world.item.Item[] EVERY_CATEGORY = {
            Items.IRON_PICKAXE, Items.BREAD, Items.IRON_INGOT, Items.OAK_LOG,
            Items.WHEAT_SEEDS, Items.COBBLESTONE, Items.BONE, Items.GLASS};

    /** Drei fast volle Fässer mit allen Sorten; Sortieren darf weder Items verlieren noch abwerfen. */
    private void sortKeepsEveryItem(GameTestHelper h, boolean byCategory) {
        var c = companion(h);
        c.setNoAi(true);
        java.util.List<net.minecraft.world.Container> boxes = new java.util.ArrayList<>();
        for (int x : new int[]{1, 4, 7}) {
            var pos = h.absolutePos(new BlockPos(x, 1, 6));
            h.getLevel().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
            boxes.add(c.containerAt(h.getLevel(), pos));
        }
        java.util.Set<CompanionJobs.Category> seen = java.util.EnumSet.noneOf(CompanionJobs.Category.class);
        for (int b = 0; b < boxes.size(); b++) {
            var box = boxes.get(b);
            for (int i = 0; i < box.getContainerSize() - 1; i++) { // je ein Platz frei: "fast voll"
                var item = EVERY_CATEGORY[(i + b * 3) % EVERY_CATEGORY.length];
                var stack = new ItemStack(item, Math.min(item.getDefaultMaxStackSize(), 1 + (i * 7 + b) % 40));
                if (i % 5 == 0) stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                        net.minecraft.network.chat.Component.literal("u-" + b + "-" + i));
                box.setItem(i, stack);
                seen.add(CompanionJobs.category(stack));
            }
        }
        h.assertValueEqual(seen.size(), CompanionJobs.Category.values().length, "test covers every category");
        var before = contents(boxes);
        if (byCategory) CompanionJobs.sortByCategory(c, h.getLevel(), c.blockPosition());
        else c.storage.sortChests(h.getLevel(), c.blockPosition());
        var after = contents(boxes);
        h.assertValueEqual(after.size(), before.size(), "distinct item kinds after sorting");
        for (int i = 0; i < before.size(); i++) {
            var want = before.get(i);
            var got = after.stream().filter(e -> ItemStack.isSameItemSameComponents(e.getKey(), want.getKey())).findFirst();
            h.assertTrue(got.isPresent(), "lost " + want.getKey().getHoverName().getString());
            h.assertValueEqual(got.get().getValue(), want.getValue(), "count of " + want.getKey().getHoverName().getString());
        }
        var drops = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).inflate(16));
        h.assertTrue(drops.isEmpty(), "sorting dropped " + drops.size() + " item entities");
        h.succeed();
    }

    private static java.util.List<java.util.Map.Entry<ItemStack, Integer>> contents(
            java.util.List<net.minecraft.world.Container> boxes) {
        java.util.List<java.util.Map.Entry<ItemStack, Integer>> out = new java.util.ArrayList<>();
        for (var box : boxes)
            for (int i = 0; i < box.getContainerSize(); i++) {
                var s = box.getItem(i);
                if (s.isEmpty()) continue;
                int at = -1;
                for (int j = 0; j < out.size(); j++)
                    if (ItemStack.isSameItemSameComponents(out.get(j).getKey(), s)) { at = j; break; }
                if (at < 0) out.add(java.util.Map.entry(s.copyWithCount(1), s.getCount()));
                else out.set(at, java.util.Map.entry(out.get(at).getKey(), out.get(at).getValue() + s.getCount()));
            }
        return out;
    }

    @GameTest
    public void sortByCategoryKeepsEveryItemInThreeFullBoxes(GameTestHelper h) { sortKeepsEveryItem(h, true); }

    @GameTest
    public void sortChestsKeepsEveryItemInThreeFullBoxes(GameTestHelper h) { sortKeepsEveryItem(h, false); }

    @GameTest
    public void pauseAndResumePreserveBuild(GameTestHelper h) {
        var c = companion(h);
        var job = CompanionBuilder.create(h.getLevel(), "pause", Map.of(
                h.absolutePos(new BlockPos(4, 1, 4)), Blocks.DIRT.defaultBlockState()));
        c.build = job;
        CompanionBrain.pauseBuild(c, true);
        h.assertTrue(c.build == job && job.paused && !c.hasTasks(), "Pause lost the build or left tasks running");
        CompanionBrain.pauseBuild(c, false);
        h.assertTrue(c.build == job && !job.paused && !c.isPaused(), "Resume lost the build");
        h.succeed();
    }

    @GameTest
    public void terrainAndDimensionSurviveSaving(GameTestHelper h) {
        var job = CompanionBuilder.createTerrain(h.getLevel(), "persist", Map.of(
                h.absolutePos(new BlockPos(4, 1, 4)), Blocks.AIR.defaultBlockState()));
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        CompanionBuilder.save(output, job);
        var input = net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), output.buildResult());
        var loaded = CompanionBuilder.load(input);
        h.assertTrue(loaded != null && loaded.terrainOnly && loaded.inDimension(h.getLevel()), "Build policy lost on load");
        var other = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        h.assertTrue(other != null && !loaded.inDimension(other), "Build accepted a different dimension");
        h.succeed();
    }

    @GameTest
    public void resourceTargetsSurviveQueueSaving(GameTestHelper h) {
        var gather = new CompanionEntity.Task(new dev.begleiter.task.TaskSpec.Gather("kohle", 4, 9));
        var mine = new CompanionEntity.Task(new dev.begleiter.task.TaskSpec.Mine("diamant", 2, 5));
        var timber = new CompanionEntity.Task(new dev.begleiter.task.TaskSpec.Gather("holz", 3, 8, "birch_log"));
        var saved = dev.begleiter.task.TaskQueueCodec.save(java.util.List.of(gather, mine, timber), true);
        var loaded = dev.begleiter.task.TaskQueueCodec.load(saved);
        h.assertTrue(loaded.problem() == null && loaded.paused()
                && loaded.tasks().equals(java.util.List.of(gather, mine, timber)), "Resource target changed after queue reload");
        h.succeed();
    }

    @GameTest
    public void gatherStopsAtActualTarget(GameTestHelper h) {
        var c = companion(h);
        c.setNoAi(true);
        c.giveToInventory(new ItemStack(Items.COAL, 5));
        c.addTask(new CompanionEntity.Task(new dev.begleiter.task.TaskSpec.Gather("kohle", 4, 5)));
        var executor = new CompanionTaskGoal(c);
        executor.start();
        executor.tick();
        h.assertTrue(!c.hasTasks(), "Already fulfilled GATHER continued collecting");
        h.assertValueEqual(CompanionCraft.count(c, "coal"), 5, "Existing coal changed");
        h.succeed();
    }

    @GameTest
    public void exactBirchRecipeUsesBirchLogs(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 8));
        c.giveToInventory(new ItemStack(Items.BIRCH_LOG, 2));
        var plan = CompanionPlanner.obtain(c, Items.BIRCH_PLANKS, 8, CompanionPlanner.RoutePolicy.AVOID_CHESTS);
        h.assertTrue(plan != null && plan.tasks().stream().anyMatch(t ->
                t.spec() instanceof dev.begleiter.task.TaskSpec.Craft craft && craft.item().equals("minecraft:birch_planks")),
                "Exact birch recipe missing");
        h.assertTrue(CompanionCraft.craft(c, "birch_planks", 8), "Birch crafting failed");
        h.assertValueEqual(CompanionCraft.count(c, "birch_planks"), 8, "Birch output");
        h.assertValueEqual(CompanionCraft.count(c, "oak_planks"), 8, "Unrelated oak consumed");
        h.succeed();
    }

    @GameTest
    public void taggedRecipeCombinesWoodVariants(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS));
        c.giveToInventory(new ItemStack(Items.BIRCH_PLANKS));
        var plan = CompanionPlanner.obtainPrepared(c, Items.STICK, 4, CompanionPlanner.RoutePolicy.AVOID_CHESTS);
        h.assertTrue(plan != null && plan.tasks().stream().noneMatch(t ->
                t.type() == CompanionEntity.TaskType.GATHER), "Mixed valid planks caused unnecessary gathering");
        h.assertTrue(CompanionRecipes.craft(c, h.getLevel(), Items.STICK, 4), "Mixed plank recipe failed");
        h.assertValueEqual(CompanionCraft.count(c, "stick"), 4, "Stick output");
        h.succeed();
    }

    @GameTest
    public void failedCraftBatchKeepsIngredients(GameTestHelper h) {
        var c = companion(h);
        for (int i = 0; i < 3; i++) c.giveToInventory(new ItemStack(Items.MILK_BUCKET));
        c.giveToInventory(new ItemStack(Items.SUGAR, 2));
        c.giveToInventory(new ItemStack(Items.EGG));
        c.giveToInventory(new ItemStack(Items.WHEAT, 3));
        h.assertTrue(!CompanionRecipes.craft(c, h.getLevel(), Items.CAKE, 2), "Incomplete batch reported success");
        h.assertValueEqual(CompanionCraft.count(c, "milk_bucket"), 3, "Failed batch consumed milk");
        h.assertValueEqual(CompanionCraft.count(c, "wheat"), 3, "Failed batch consumed wheat");
        h.assertValueEqual(CompanionCraft.count(c, "cake"), 0, "Failed batch produced cake");
        h.succeed();
    }

    @GameTest
    public void repeatedFailedRestockingStillPauses(GameTestHelper h) {
        var c = companion(h);
        c.setNoAi(true);
        c.build = CompanionBuilder.create(h.getLevel(), "failed restock", Map.of(
                h.absolutePos(new BlockPos(4, 1, 4)), Blocks.COBBLESTONE.defaultBlockState()));
        c.build.attempts.put(Items.COBBLESTONE, 3);
        c.addTask(CompanionEntity.Task.build());
        var executor = new CompanionTaskGoal(c);
        executor.start();
        executor.tick();
        h.assertTrue(c.isPaused(), "Repeated failed acquisition was not bounded");
        h.succeed();
    }

    @GameTest
    public void exactWoodSearchDoesNotAcceptOtherSpecies(GameTestHelper h) {
        var c = companion(h);
        c.setNoAi(true);
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 10));
        c.giveToInventory(new ItemStack(Items.BIRCH_LOG, 2));
        var task = new CompanionEntity.Task(new dev.begleiter.task.TaskSpec.Gather("holz", 1, 3, "birch_log"));
        c.addTask(task);
        var executor = new CompanionTaskGoal(c);
        executor.start();
        executor.tick();
        h.assertTrue(c.hasTasks(), "Oak logs incorrectly completed the exact birch target");
        h.assertValueEqual(CompanionCraft.count(c, "birch_log"), 2, "Search mutated the birch stock");
        h.succeed();
    }
}
