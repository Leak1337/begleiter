package dev.begleiter;

import java.util.List;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.task.TaskQueueCodec;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

/** Verschiedene registrierte Entity-Typen mit demselben Pfad dürfen nicht zusammenfallen. */
public class EntityRegistryIdGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.setNoAi(true);
        return c;
    }

    @GameTest
    public void nearestTypeKeepsNamespacesAndVanillaAliases(GameTestHelper h) {
        var c = companion(h);
        var foreign = h.spawn(RegistryIdTestItems.COW, new BlockPos(3, 1, 2)); foreign.setNoAi(true);
        var vanilla = h.spawn(EntityTypes.COW, new BlockPos(4, 1, 2)); vanilla.setNoAi(true);
        h.assertTrue(c.expedition.nearestOfType("cow", 4, null) == vanilla, "Vanilla-Alias findet fremde Kuh");
        h.assertTrue(c.expedition.nearestOfType("minecraft:cow", 4, null) == vanilla, "Volle Vanilla-ID findet falsches Tier");
        h.assertTrue(c.expedition.nearestOfType("begleiter-test:cow", 4, null) == foreign, "Fremde Entity-ID verloren");
        h.succeed();
    }

    @GameTest
    public void herdCountsDoNotCombineDifferentNamespaces(GameTestHelper h) {
        var center = h.absolutePos(new BlockPos(4, 1, 4));
        var foreign = h.spawn(RegistryIdTestItems.COW, new BlockPos(3, 1, 4)); foreign.setNoAi(true); foreign.setAge(0);
        var vanilla = h.spawn(EntityTypes.COW, new BlockPos(5, 1, 4)); vanilla.setNoAi(true); vanilla.setAge(0);
        h.assertValueEqual(CompanionJobs.adults(h.getLevel(), center, 2, "cow"), 1, "Vanilla-Herde mit Fremdtier aufgefüllt");
        h.assertValueEqual(CompanionJobs.adults(h.getLevel(), center, 2, "begleiter-test:cow"), 1, "Fremde Herde mit Vanilla-Tier aufgefüllt");
        h.succeed();
    }

    @GameTest
    public void foreignNameIsNeitherVanillaFriendNorFoodAnimal(GameTestHelper h) {
        var c = companion(h);
        var namedGolem = h.spawn(RegistryIdTestItems.IRON_GOLEM, new BlockPos(3, 1, 2)); namedGolem.setNoAi(true);
        var foreignCow = h.spawn(RegistryIdTestItems.COW, new BlockPos(4, 1, 2)); foreignCow.setNoAi(true);
        h.assertTrue(!CompanionCombat.isFriend(c, namedGolem), "Fremder Name erhält Vanilla-Golemrolle");
        var food = c.nearestFoodAnimal();
        h.assertTrue(food != namedGolem && food != foreignCow, "Fremdes Tier als Vanilla-Nutztier zum Jagen gewählt");
        h.succeed();
    }

    @GameTest
    public void fetchHandlerSelectsTheRequestedNamespace(GameTestHelper h) {
        Player owner = h.makeMockPlayer(GameType.SURVIVAL);
        var c = new CompanionEntity(CompanionMod.COMPANION, h.getLevel()) {
            @Override public Player getOwner() { return owner; }
        };
        var pos = h.absolutePos(new BlockPos(2, 1, 2));
        c.snapTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        c.stay(); c.setNoAi(true);
        var foreign = h.spawn(RegistryIdTestItems.COW, new BlockPos(3, 1, 2)); foreign.setNoAi(true);
        var vanilla = h.spawn(EntityTypes.COW, new BlockPos(4, 1, 2)); vanilla.setNoAi(true);
        c.addTask(Task.fetch("cow"));
        var executor = new CompanionTaskGoal(c); executor.start(); executor.tick();
        h.assertTrue(c.fetchTarget == vanilla, "FETCH verwechselt den Vanilla-Alias");
        c.cancelOrders();
        c.addTask(Task.fetch("begleiter-test:cow"));
        executor = new CompanionTaskGoal(c); executor.start(); executor.tick();
        h.assertTrue(c.fetchTarget == foreign, "FETCH verliert fremden Namensraum");
        c.cancelOrders();
        h.succeed();
    }

    @GameTest
    public void entityTargetsMigrateAndRoundTrip(GameTestHelper h) {
        var tasks = List.of(Task.fight(null, "cow", null, 1), Task.breed("cow", 2));
        var legacy = TaskQueueCodec.save(tasks, true);
        legacy.getListOrEmpty("Tasks").getCompoundOrEmpty(0).putString("mob", "cow");
        legacy.getListOrEmpty("Tasks").getCompoundOrEmpty(1).putString("species", "cow");
        var loaded = TaskQueueCodec.load(legacy);
        h.assertTrue(loaded.problem() == null && loaded.paused() && loaded.tasks().equals(tasks), "Alte Entity-Ziele nicht migriert");
        var foreign = List.of(Task.fight(null, "begleiter-test:cow", null, 1), Task.breed("begleiter-test:cow", 2));
        h.assertTrue(TaskQueueCodec.load(TaskQueueCodec.save(foreign, false)).tasks().equals(foreign), "Fremde Entity-Ziele verloren");
        legacy.getListOrEmpty("Tasks").getCompoundOrEmpty(1).putString("species", "missing:cow");
        h.assertTrue(TaskQueueCodec.load(legacy).problem() != null, "Unbekannter Tierauftrag geladen");
        h.succeed();
    }

    @GameTest
    public void spawnerMemoryMigratesAndSeparatesEntityIds(GameTestHelper h) {
        var memory = new CompanionMemory();
        var a = h.absolutePos(new BlockPos(3, 1, 3));
        var b = h.absolutePos(new BlockPos(5, 1, 3));
        memory.remember(CompanionMemory.Kind.SPAWNER, "cow", h.getLevel(), a);
        memory.remember(CompanionMemory.Kind.SPAWNER, "begleiter-test:cow", h.getLevel(), b);
        var saved = memory.save();
        saved.getListOrEmpty("Places").getCompoundOrEmpty(0).putString("Key", "cow");
        var loaded = new CompanionMemory(); loaded.load(saved);
        h.assertTrue(loaded.nearest(CompanionMemory.Kind.SPAWNER, "cow", h.getLevel(), a, 4).pos.equals(a), "Alter Spawner-Alias verloren");
        h.assertTrue(loaded.find(CompanionMemory.Kind.SPAWNER, "begleiter-test:cow", h.getLevel(), a).size() == 1,
                "Fremde Spawner-Erinnerung vermischt");
        h.assertTrue(loaded.save().getListOrEmpty("Places").getCompoundOrEmpty(0).getStringOr("Key", "").equals("minecraft:cow"),
                "Spawner wieder ohne Namensraum gespeichert");
        h.succeed();
    }
}
