package dev.begleiter;

import java.util.List;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.task.TaskQueueCodec;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Planung, Aufgabenmigration und tatsächliche Materialentnahme mit vollständigen Item-IDs. */
public class QueueRegistryIdGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.setNoAi(true);
        c.getInventory().clearContent();
        c.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return c;
    }

    private static List<Task> itemTasks(BlockPos pos) {
        return List.of(new Task(new TaskSpec.Take(pos, "coal", 4), 2),
                Task.craft("bucket", 2), Task.smelt("iron_ingot", 3), Task.station("furnace", true),
                Task.trade("bread", 4), Task.give("item:coal", 3),
                new Task(new TaskSpec.GearUp(3, "iron_helmet", 2, 5)),
                new Task(new TaskSpec.Gather("holz", 4, 9, "birch_log")),
                Task.fight(null, "zombie", "rotten_flesh", 5));
    }

    @GameTest
    public void fullItemFieldsSurviveQueueReload(GameTestHelper h) {
        var tasks = itemTasks(h.absolutePos(new BlockPos(3, 1, 2)));
        var saved = TaskQueueCodec.save(tasks, true);
        var loaded = TaskQueueCodec.load(saved);
        h.assertTrue(loaded.problem() == null && loaded.paused() && loaded.tasks().equals(tasks),
                "Itemfelder, Pause, Zielbestände oder Versuche beim Reload verändert: " + loaded.problem());
        var entries = saved.getListOrEmpty("Tasks");
        h.assertTrue(entries.getCompoundOrEmpty(0).getStringOr("item", "").equals("minecraft:coal"), "Kurz-ID gespeichert");
        h.assertTrue(entries.getCompoundOrEmpty(5).getStringOr("what", "").equals("item:minecraft:coal"), "Abgabe-ID nicht vollständig");
        h.succeed();
    }

    @GameTest
    public void legacyV2ItemFieldsMigrateWithoutLosingOtherFields(GameTestHelper h) {
        var expected = itemTasks(h.absolutePos(new BlockPos(3, 1, 2)));
        var old = TaskQueueCodec.save(expected, true);
        for (var tag : old.getListOrEmpty("Tasks")) {
            var entry = (CompoundTag) tag;
            for (String field : List.of("item", "lastPiece", "exactItem", "loot")) {
                String value = entry.getStringOr(field, "");
                if (value.startsWith("minecraft:")) entry.putString(field, value.substring(10));
            }
            String what = entry.getStringOr("what", "");
            if (what.startsWith("item:minecraft:")) entry.putString("what", "item:" + what.substring(15));
        }
        var loaded = TaskQueueCodec.load(old);
        h.assertTrue(loaded.problem() == null && loaded.paused() && loaded.tasks().equals(expected), "V2-Migration verändert Auftrag");
        h.assertTrue(TaskQueueCodec.save(loaded.tasks(), loaded.paused()).getListOrEmpty("Tasks")
                .getCompoundOrEmpty(0).getStringOr("item", "").equals("minecraft:coal"), "V2 wieder als Kurz-ID gespeichert");
        h.succeed();
    }

    @GameTest
    public void legacyV1StillMigratesItemArguments(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(3, 1, 2));
        String[][] values = {{"TAKE", "coal"}, {"CRAFT", "bucket"}, {"SMELT", "iron_ingot"},
                {"STATION", "furnace"}, {"TRADE", "bread"}, {"GIVE", "item:coal"},
                {"GEARUP", "3:iron_helmet:2"}, {"FIGHT", "zombie|rotten_flesh"}};
        ListTag entries = new ListTag();
        for (String[] value : values) {
            var entry = new CompoundTag();
            entry.putString("T", value[0]);
            entry.putString("A", value[1]);
            entry.putInt("N", 1);
            entry.putLong("P", pos.asLong());
            entries.add(entry);
        }
        var old = new CompoundTag();
        old.putInt("V", 1);
        old.put("Tasks", entries);
        var loaded = TaskQueueCodec.load(old);
        h.assertTrue(loaded.problem() == null && loaded.tasks().size() == 8, "V1-Aufträge verloren: " + loaded.problem());
        h.assertTrue(((TaskSpec.Take) loaded.tasks().getFirst().spec()).item().equals("minecraft:coal"), "Altes TAKE nicht migriert");
        h.assertTrue(((TaskSpec.GearUp) loaded.tasks().get(6).spec()).lastPiece().equals("minecraft:iron_helmet"), "Altes GEARUP nicht migriert");
        h.assertTrue(((TaskSpec.Fight) loaded.tasks().get(7).spec()).loot().equals("minecraft:rotten_flesh"), "Alte Beute-ID nicht migriert");
        h.succeed();
    }

    @GameTest
    public void invalidOptionalItemsRejectTheWholeQueue(GameTestHelper h) {
        var specs = List.<TaskSpec>of(new TaskSpec.Trade("coal", 3), new TaskSpec.Smelt("iron_ingot", 3),
                new TaskSpec.GearUp(3, "iron_helmet", 1, 0), new TaskSpec.Fight(null, "zombie", "rotten_flesh", 3),
                new TaskSpec.Gather("holz", 3, 7, "birch_log"), new TaskSpec.Give("item:coal", 3));
        String[] fields = {"item", "item", "lastPiece", "loot", "exactItem", "what"};
        for (int i = 0; i < specs.size(); i++) for (String invalid : new String[]{"bad:", ":coal", "missing:coal", "minecraft:air"}) {
            var saved = TaskQueueCodec.save(List.of(new Task(specs.get(i)), Task.craft("bucket", 1)), true);
            saved.getListOrEmpty("Tasks").getCompoundOrEmpty(0).putString(fields[i], i == 5 ? "item:" + invalid : invalid);
            var loaded = TaskQueueCodec.load(saved);
            h.assertTrue(loaded.problem() != null && loaded.tasks().isEmpty(), "Ungültiges Item als andere Aktion geladen: " + specs.get(i));
        }
        var emptyBuy = TaskQueueCodec.save(List.of(Task.trade("coal", 3)), false);
        emptyBuy.getListOrEmpty("Tasks").getCompoundOrEmpty(0).putString("item", "");
        h.assertTrue(TaskQueueCodec.load(emptyBuy).problem() != null, "Leere Kauf-ID als Verkauf geladen");
        h.succeed();
    }

    @GameTest
    public void genericActionsAndSpecialRecipeRemainReadable(GameTestHelper h) {
        var tasks = List.of(Task.craft("#planks_from_logs", 4), Task.smelt(null, 0), Task.trade(null, 0), Task.give(null, 0));
        var loaded = TaskQueueCodec.load(TaskQueueCodec.save(tasks, false));
        h.assertTrue(loaded.problem() == null && loaded.tasks().equals(tasks), "Generische Aktion oder Sonderrezept verändert");
        h.succeed();
    }

    @GameTest
    public void foreignChestPlanExecutesExactItemAfterReload(GameTestHelper h) {
        var c = companion(h);
        BlockPos boxPos = h.absolutePos(new BlockPos(3, 1, 2));
        h.setBlock(3, 1, 2, Blocks.BARREL);
        c.setHome(boxPos);
        var box = c.containerAt(h.getLevel(), boxPos);
        box.setItem(0, new ItemStack(Items.COAL, 16));
        box.setItem(1, new ItemStack(RegistryIdTestItems.COAL, 9));
        c.giveToInventory(new ItemStack(Items.COAL, 32));
        var plan = CompanionPlanner.obtain(c, RegistryIdTestItems.COAL, 4);
        h.assertTrue(plan != null && plan.tasks().size() == 1 && plan.tasks().getFirst().spec() instanceof TaskSpec.Take,
                "Kein exakter Entnahmeplan für fremdes Item");
        var loaded = TaskQueueCodec.load(TaskQueueCodec.save(plan.tasks(), true));
        h.assertTrue(loaded.problem() == null && loaded.paused()
                && ((TaskSpec.Take) loaded.tasks().getFirst().spec()).item().equals("begleiter-test:coal"), "Namensraum nach Reload verloren");
        c.addTask(loaded.tasks().getFirst());
        var executor = new CompanionTaskGoal(c);
        executor.start();
        executor.tick();
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:coal"), 4, "Falsches Item entnommen");
        h.assertValueEqual(CompanionCraft.count(c, "minecraft:coal"), 32, "Vanilla-Inventar verändert");
        h.assertValueEqual(box.getItem(0).getCount(), 16, "Vanilla-Kiste verändert");
        h.assertValueEqual(box.getItem(1).getCount(), 5, "Fremde Restmenge");
        h.succeed();
    }

    @GameTest
    public void foreignItemsDoNotInventVanillaMiningRoutes(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.COAL, 32));
        h.assertTrue(CompanionBuilder.rawSource(RegistryIdTestItems.COAL) == null, "Fremde Kohle als Vanilla-Abbauquelle");
        h.assertTrue(CompanionPlanner.obtain(c, RegistryIdTestItems.COAL, 4, CompanionPlanner.RoutePolicy.AVOID_CHESTS) == null,
                "Vanilla-Kohleabbau als Beschaffung fremder Kohle geplant");
        h.assertTrue(CompanionPlanner.obtain(c, RegistryIdTestItems.OAK_LOG, 4, CompanionPlanner.RoutePolicy.AVOID_CHESTS) == null,
                "Nicht platzierbares Tag-Item als abbaubarer Stamm geplant");
        h.succeed();
    }

    @GameTest
    public void explicitObtainAndGivePreserveForeignItem(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.COAL, 7));
        c.giveToInventory(new ItemStack(RegistryIdTestItems.COAL, 4));
        CompanionBrain.orderObtain(c, RegistryIdTestItems.COAL, 3, true);
        h.assertTrue(c.currentTask() != null && c.currentTask().spec() instanceof TaskSpec.Give, "Abgabeauftrag fehlt");
        var give = (TaskSpec.Give) c.currentTask().spec();
        h.assertTrue(give.what().equals("item:begleiter-test:coal"), "Spielerauftrag verliert Namensraum");
        h.assertValueEqual(c.giveTo(h.makeMockPlayer(GameType.SURVIVAL), give.what(), give.max()), 3, "Exakte Abgabe fehlgeschlagen");
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:coal"), 1, "Fremde Abgabemenge");
        h.assertValueEqual(CompanionCraft.count(c, "minecraft:coal"), 7, "Falsches Vanilla-Item abgegeben");
        h.succeed();
    }

    @GameTest
    public void canonicalStationsWorkWithExistingCachesAndAliases(GameTestHelper h) {
        var c = companion(h);
        BlockPos table = h.absolutePos(new BlockPos(3, 1, 2));
        h.setBlock(3, 1, 2, Blocks.CRAFTING_TABLE);
        c.rememberStation("minecraft:crafting_table", table);
        h.assertValueEqual(c.findStation("minecraft:crafting_table", 4), table, "Vollständige Stations-ID");
        h.assertValueEqual(c.findStation("crafting_table", 4), table, "Alter Stations-Alias");
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 4));
        h.assertTrue(c.canCarryStation("minecraft:crafting_table"), "Kanonische Werkbank nicht als tragbar erkannt");
        h.assertTrue(!CompanionStations.isBlockId(Blocks.FURNACE.defaultBlockState(), "begleiter-test:furnace"),
                "Fremder Namensraum trifft Vanilla-Ofen");
        h.succeed();
    }

    @GameTest
    public void craftingCooldownUsesTheSameCanonicalItem(GameTestHelper h) {
        var c = companion(h);
        c.craftFailedUntil.put(new TaskSpec.Craft("iron_pickaxe", 1).item(), c.tickCount + 100);
        h.assertTrue(c.craftBlocked("iron_pickaxe") && c.craftBlocked("minecraft:iron_pickaxe"), "Alias umgeht Crafting-Sperre");
        h.assertTrue(!c.craftBlocked("begleiter-test:iron_pickaxe"), "Fremdes Item durch Vanilla-Sperre blockiert");
        c.craftFailedUntil.put("#repair", c.tickCount + 100);
        h.assertTrue(c.craftBlocked("#repair"), "Sondermarker normalisiert");
        h.succeed();
    }

    @GameTest
    public void foreignSpareDoesNotHideWornVanillaTool(GameTestHelper h) {
        var c = companion(h);
        var worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 1);
        c.giveToInventory(worn);
        c.giveToInventory(new ItemStack(RegistryIdTestItems.IRON_PICKAXE));
        var found = CompanionNeeds.wornTool(c);
        h.assertTrue(found != null && found.is(Items.IRON_PICKAXE), "Fremdes Item als gleichwertigen Ersatz gezählt");
        h.assertTrue(CompanionNeeds.replaceTool(c, found), "Werkzeugersatz nicht geplant");
        h.assertTrue(c.peekTasks(20).stream().anyMatch(t -> t.spec() instanceof TaskSpec.Craft craft
                && craft.item().equals("minecraft:iron_pickaxe")), "Ersatz verliert Item-ID");
        h.succeed();
    }
}
