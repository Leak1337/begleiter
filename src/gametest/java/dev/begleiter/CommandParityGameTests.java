package dev.begleiter;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** Beide öffentlichen Befehlsbäume müssen dieselben Befehle und Argumentgrenzen haben. */
public class CommandParityGameTests {
    private static final Map<String, String> ENGLISH = Map.ofEntries(
            Map.entry("folgen", "follow"), Map.entry("warten", "stay"), Map.entry("selbststaendig", "free"),
            Map.entry("abbrechen", "cancel"), Map.entry("fortsetzen", "resume"), Map.entry("projekt", "project"),
            Map.entry("vorschau", "preview"), Map.entry("bestaetigen", "confirm"), Map.entry("pruefen", "check"),
            Map.entry("verwerfen", "discard"), Map.entry("bauplaene", "schematics"), Map.entry("bauplan", "schematic"),
            Map.entry("luft", "air"), Map.entry("sortieren", "sort"), Map.entry("einlagern", "tidy"),
            Map.entry("diagnose", "debug"), Map.entry("protokoll", "log"), Map.entry("anzahl", "count"),
            Map.entry("lager", "home"), Map.entry("lagerverwalter", "keeper"), Map.entry("sammeln", "gather"),
            Map.entry("bauen", "build"), Map.entry("plattform", "platform"), Map.entry("wand", "wall"),
            Map.entry("breite", "width"), Map.entry("hoehe", "height"), Map.entry("tiefe", "depth"),
            Map.entry("freiraeumen", "clear"),
            Map.entry("einstellungen", "config"), Map.entry("aktualisieren", "reload"));

    private static CommandNode<CommandSourceStack> root(GameTestHelper h, String name) {
        return h.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild(name);
    }

    private static void collect(CommandNode<CommandSourceStack> node, String path, boolean german,
                                Map<String, String> result) {
        String signature = node.getClass().getSimpleName() + ":executable=" + (node.getCommand() != null);
        if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> arg) {
            signature += ":" + arg.getType().getClass().getName();
            if (arg.getType() instanceof IntegerArgumentType integer)
                signature += ":" + integer.getMinimum() + ":" + integer.getMaximum();
        }
        result.put(path, signature);
        for (var child : node.getChildren()) {
            // Nur der englische Baum enthält die Testwerkzeuge für Operatoren und den alten Alias "luft".
            if (!german && (path.isEmpty() && (child.getName().equals("parkourtest") || child.getName().equals("testmap"))
                    || path.equals("/schematic/name") && child.getName().equals("luft"))) continue;
            String name = german ? ENGLISH.getOrDefault(child.getName(), child.getName()) : child.getName();
            collect(child, path + "/" + name, german, result);
        }
    }

    @GameTest
    public void publicTreesHaveEqualOperationsTypesAndLimits(GameTestHelper h) {
        Map<String, String> de = new TreeMap<>(), en = new TreeMap<>();
        collect(root(h, "begleiter"), "", true, de);
        collect(root(h, "companion"), "", false, en);
        h.assertTrue(de.equals(en), "Command trees differ: DE=" + de + " EN=" + en);
        h.succeed();
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        var player = h.makeMockServerPlayerInLevel();
        c.setOwner(player.getUUID());
        player.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        return player;
    }

    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.setNoAi(true);
        c.stay();
        return c;
    }

    private static int run(GameTestHelper h, ServerPlayer player, String command) throws Exception {
        return h.getLevel().getServer().getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
    }

    @GameTest
    public void buildsAndClearQueueTheSameTasksInBothLanguages(GameTestHelper h) throws Exception {
        var c = companion(h);
        var player = owner(h, c);
        for (String[] pair : new String[][] {
                {"build platform minecraft:stone 2 3", "bauen plattform minecraft:stone 2 3"},
                {"build wall minecraft:stone 2 3", "bauen wand minecraft:stone 2 3"},
                {"clear 2 2 2", "freiraeumen 2 2 2"}}) {
            if (pair[0].startsWith("clear")) {
                BlockPos target = player.blockPosition().relative(player.getDirection());
                h.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 3);
            }
            run(h, player, "companion " + pair[0]);
            var english = c.peekTasks(100);
            h.assertTrue(!english.isEmpty(), "English command queued no tasks: " + pair[0]);
            c.cancelOrders();
            run(h, player, "begleiter " + pair[1]);
            h.assertTrue(english.equals(c.peekTasks(100)), "Different tasks for " + pair[0]);
            c.cancelOrders();
        }
        h.succeed();
    }

    @GameTest
    public void projectAndSortUseTheExistingActions(GameTestHelper h) throws Exception {
        var c = companion(h);
        var player = owner(h, c);
        for (String[] pair : new String[][] {
                {"project status", "projekt status"}, {"project check", "projekt pruefen"},
                {"project discard", "projekt verwerfen"}, {"sort", "sortieren"}}) {
            int en = run(h, player, "companion " + pair[0]);
            var tasks = c.peekTasks(100);
            c.cancelOrders();
            int de = run(h, player, "begleiter " + pair[1]);
            h.assertValueEqual(en, de, "Different action result: " + pair[0]);
            h.assertTrue(tasks.equals(c.peekTasks(100)), "Different action tasks: " + pair[0]);
            c.cancelOrders();
        }
        h.succeed();
    }

    @GameTest
    public void bothTreesRejectForeignCompanionsAndKeepParkourRestricted(GameTestHelper h) throws Exception {
        var c = companion(h);
        var player = owner(h, c);
        c.setOwner(UUID.randomUUID());
        for (String command : new String[] {"companion follow", "begleiter folgen", "companion sort",
                "begleiter sortieren", "companion project confirm", "begleiter projekt bestaetigen",
                "companion clear 1 1 1", "begleiter freiraeumen 1 1 1"}) {
            h.assertValueEqual(run(h, player, command), 0, "Foreign companion accepted " + command);
            h.assertTrue(c.isStaying() && !c.hasTasks(), "Foreign command changed companion");
        }
        h.assertTrue(!root(h, "companion").getChild("parkourtest").canUse(player.createCommandSourceStack()),
                "Parkour command lost operator restriction");
        h.assertTrue(!root(h, "companion").getChild("testmap").canUse(player.createCommandSourceStack()),
                "Testmap command lost operator restriction");
        h.succeed();
    }

    @GameTest
    public void invalidDimensionsAndCountsCannotQueueWork(GameTestHelper h) throws Exception {
        var c = companion(h);
        var player = owner(h, c);
        for (String command : new String[] {"companion build platform minecraft:stone 0 1",
                "begleiter bauen plattform minecraft:stone 33 1", "companion build wall minecraft:stone 1 33",
                "begleiter bauen wand minecraft:stone 1 0", "companion clear 17 1 1",
                "begleiter freiraeumen 1 0 1", "companion gather minecraft:stone 257",
                "begleiter sammeln minecraft:stone 0", "companion log 51", "begleiter protokoll 0"}) {
            boolean rejected = false;
            try { run(h, player, command); }
            catch (CommandSyntaxException expected) { rejected = true; }
            h.assertTrue(rejected && !c.hasTasks(), "Out-of-range command accepted: " + command);
        }
        h.succeed();
    }

    @GameTest
    public void legacyAirAndParkourAliasesRemainParseable(GameTestHelper h) {
        var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
        var source = h.getLevel().getServer().createCommandSourceStack();
        for (String command : new String[] {"companion schematic example air", "companion schematic example luft",
                "companion parkourtest build", "companion parkourtest bauen", "companion parkourtest 1"}) {
            var parsed = dispatcher.parse(command, source);
            h.assertTrue(!parsed.getReader().canRead() && parsed.getContext().getCommand() != null,
                    "Alias did not fully parse: " + command);
        }
        h.succeed();
    }
}
