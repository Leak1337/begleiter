package dev.begleiter;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.task.TaskSpec;
import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.ai.CompanionDecisionEngine;
import dev.begleiter.ai.DecisionOutcome.Reason;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Prepared-plan accounting plus real executor comparisons in both activation modes. */
public class PrerequisiteGameTests {
    private static final String BENCH = "begleiter-gametest:bench";

    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, new BlockPos(24, 1, 6));
        c.stay();
        c.setNoAi(true);
        c.setOnGround(true);
        c.getInventory().clearContent();
        c.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        c.memory.clear();
        c.tickCount = 1; // Manual executor ticks; no unrelated passing-ore scan.
        // Nearby parallel tests may own stations. Ignore them without modifying their worlds.
        for (String station : new String[]{"crafting_table", "furnace"}) {
            BlockPos p;
            while ((p = c.findStation(station, 24)) != null) c.markStationUnreachable(p);
        }
        return c;
    }

    private static void give(CompanionEntity c, Item item, int count) { c.giveToInventory(new ItemStack(item, count)); }
    private static void table(GameTestHelper h) { h.setBlock(25, 1, 6, Blocks.CRAFTING_TABLE); }
    private static void furnace(GameTestHelper h) { h.setBlock(25, 1, 7, Blocks.FURNACE); }
    private static void full(CompanionEntity c) {
        for (int i = 0; i < c.getInventory().getContainerSize(); i++) c.getInventory().setItem(i, new ItemStack(Items.DIAMOND, 64));
    }
    private static CompanionPlanner.Plan plan(GameTestHelper h, CompanionEntity c, Item item, int count) {
        var plan = CompanionPlanner.obtainPrepared(c, item, count, CompanionPlanner.RoutePolicy.AVOID_CHESTS);
        h.assertTrue(plan != null, "Vorbereiteter Plan fehlt: " + item);
        return plan;
    }
    private static int index(CompanionPlanner.Plan plan, TaskType type, String arg) {
        for (int i = 0; i < plan.tasks().size(); i++) {
            Task t = plan.tasks().get(i);
            String expected = type == TaskType.GATHER || type == TaskType.MINE ? arg : ItemIds.canonicalOrOriginal(arg);
            if (t.type() == type && expected.equals(subject(t))) return i;
        }
        return -1;
    }
    /** Item bzw. Ressource der Planschritte, die hier geprüft werden. */
    private static String subject(Task t) {
        return switch (t.spec()) {
            case TaskSpec.ResourceGoal g -> g.resource();
            case TaskSpec.Craft c -> c.item();
            case TaskSpec.Smelt s -> s.item();
            case TaskSpec.Station s -> s.item();
            default -> null;
        };
    }
    private static int count(Task t) {
        return switch (t.spec()) {
            case TaskSpec.ResourceGoal g -> g.count();
            case TaskSpec.Craft c -> c.count();
            default -> 0;
        };
    }
    private static void start(CompanionEntity c, Task task) {
        c.goal = Need.SUPPLY;
        c.activeSupplyResource = "kohle";
        c.activeSupplyApproach = "crafting";
        c.addTask(task);
        c.planFeedback.begin(c, Need.SUPPLY, null);
    }
    private static void noPenalty(GameTestHelper h, CompanionEntity c, Reason reason) {
        h.assertTrue(c.planFeedback.last() != null && c.planFeedback.last().reason() == reason, "Falscher Voraussetzungsmangel");
        h.assertValueEqual(c.experiences.sources.size(), 0, "Voraussetzung sperrt Quelle");
        h.assertValueEqual(c.experiences.learnedProfiles(), 0, "Voraussetzung bestraft Methode");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0, "Voraussetzung bestraft Beduerfnis");
    }

    @GameTest(structure = BENCH)
    public void werkbankWirdVorWerkzeugGeplantUndBezahlt(GameTestHelper h) {
        CompanionEntity c = companion(h);
        give(c, Items.IRON_INGOT, 3); give(c, Items.STICK, 2); give(c, Items.OAK_PLANKS, 4);
        var p = plan(h, c, Items.IRON_PICKAXE, 1);
        int station = index(p, TaskType.STATION, "crafting_table");
        h.assertTrue(station >= 0 && station < index(p, TaskType.CRAFT, "iron_pickaxe"), "Werkbank kommt zu spaet: " + p.explain());
        h.assertTrue(p.tasks().stream().noneMatch(t -> t.type() == TaskType.GATHER), "Vorhandenes Material wird doppelt gesammelt");
        h.assertValueEqual(CompanionCraft.count(c, "oak_planks"), 4, "Planung verbraucht echte Bretter");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void verschachtelteRezepteTeilenEineWerkbankUndEinenOfen(GameTestHelper h) {
        CompanionEntity c = companion(h);
        give(c, Items.RAW_IRON, 3); give(c, Items.COAL, 1); give(c, Items.COBBLESTONE, 8);
        give(c, Items.OAK_PLANKS, 4); give(c, Items.STICK, 2); give(c, Items.DIAMOND_PICKAXE, 1);
        var p = plan(h, c, Items.IRON_PICKAXE, 1);
        for (String id : new String[]{"crafting_table", "furnace"})
            h.assertValueEqual((int) p.tasks().stream().filter(t -> t.spec() instanceof TaskSpec.Station st && ("minecraft:" + id).equals(st.item())).count(), 1,
                    "Station doppelt/verloren: " + id + " " + p.explain());
        h.assertTrue(index(p, TaskType.STATION, "furnace") < index(p, TaskType.SMELT, "iron_ingot"), "Ofen kommt nach Schmelzen");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void aufgestellterOfenIstKeineKostenloseHochofenzutat(GameTestHelper h) {
        CompanionEntity c = companion(h); table(h);
        give(c, Items.FURNACE, 1); give(c, Items.RAW_IRON, 5); give(c, Items.COBBLESTONE, 3);
        give(c, Items.COAL, 2); give(c, Items.DIAMOND_PICKAXE, 1);
        var p = plan(h, c, Items.BLAST_FURNACE, 1);
        h.assertTrue(p.tasks().stream().filter(t -> t.type() == TaskType.GATHER && "stein".equals(subject(t))).mapToInt(PrerequisiteGameTests::count).sum() >= 8,
                "Aufgestellter Ofen wird gleichzeitig verbaut: " + p.explain());
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void vorhandeneStationenBrauchenKeineErsatzmaterialien(GameTestHelper h) {
        CompanionEntity c = companion(h); table(h); furnace(h);
        give(c, Items.RAW_IRON, 3); give(c, Items.COAL, 1);
        var p = plan(h, c, Items.IRON_INGOT, 3);
        h.assertTrue(p.tasks().stream().noneMatch(t -> t.type() == TaskType.STATION || t.type() == TaskType.GATHER),
                "Vorhandener Ofen ignoriert: " + p.explain());
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void werkbankbretterStehenNichtNochAlsBrennstoffZurVerfuegung(GameTestHelper h) {
        CompanionEntity c = companion(h);
        give(c, Items.RAW_IRON, 1); give(c, Items.COBBLESTONE, 8); give(c, Items.OAK_PLANKS, 4); give(c, Items.DIAMOND_PICKAXE, 1);
        var p = plan(h, c, Items.IRON_INGOT, 1);
        h.assertTrue(index(p, TaskType.GATHER, "kohle") >= 0, "Werkbankbretter werden nochmals verbrannt: " + p.explain());
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void brennstoffNutztGebrocheneHolzleistungOhneEchteMutation(GameTestHelper h) {
        CompanionEntity c = companion(h); furnace(h);
        give(c, Items.RAW_IRON, 3); give(c, Items.OAK_PLANKS, 2);
        var p = plan(h, c, Items.IRON_INGOT, 3);
        h.assertTrue(p.tasks().stream().noneMatch(t -> t.type() == TaskType.GATHER), "Zwei Bretter reichen fuer drei Schmelzvorgaenge");
        h.assertValueEqual(CompanionCraft.count(c, "oak_planks"), 2, "Planung verbraucht echten Brennstoff");
        h.assertTrue(c.fuelLeft == 0, "Planung aendert echte Restwaerme");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void verschlissenesWerkzeugErzeugtErsatzVorDemAbbau(GameTestHelper h) {
        CompanionEntity c = companion(h); table(h);
        ItemStack worn = new ItemStack(Items.WOODEN_PICKAXE); worn.setDamageValue(worn.getMaxDamage() - 4);
        c.giveToInventory(worn); give(c, Items.OAK_PLANKS, 8); give(c, Items.STICK, 2);
        var p = plan(h, c, Items.COAL, 8);
        h.assertTrue(index(p, TaskType.CRAFT, "wooden_pickaxe") >= 0
                && index(p, TaskType.CRAFT, "wooden_pickaxe") < index(p, TaskType.GATHER, "kohle"), "Ersatz kommt zu spaet: " + p.explain());
        h.assertValueEqual(worn.getDamageValue(), worn.getMaxDamage() - 4, "Planung verschleisst Werkzeug");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void haltbarkeitWirdUeberMehrereErsatzwerkzeugeBudgetiert(GameTestHelper h) {
        CompanionEntity c = companion(h); table(h);
        ItemStack worn = new ItemStack(Items.WOODEN_PICKAXE); worn.setDamageValue(worn.getMaxDamage() - 13);
        c.giveToInventory(worn); give(c, Items.OAK_PLANKS, 12); give(c, Items.STICK, 4);
        var p = plan(h, c, Items.COAL, 80);
        int replacements = p.tasks().stream().filter(t -> t.type() == TaskType.CRAFT && "minecraft:wooden_pickaxe".equals(subject(t))).mapToInt(PrerequisiteGameTests::count).sum();
        h.assertTrue(replacements >= 2, "Werkzeugbudget fuer 80 Abbauschritte fehlt: " + p.explain());
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void werkzeugInDerHandBrauchtKeinenZusaetzlichenInventarplatz(GameTestHelper h) {
        CompanionEntity c = companion(h); full(c);
        c.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        c.getInventory().setItem(0, new ItemStack(Items.COAL, 56));
        var p = plan(h, c, Items.COAL, 64);
        h.assertValueEqual(p.tasks().stream().filter(t -> t.type() == TaskType.GATHER).mapToInt(PrerequisiteGameTests::count).sum(), 8, "Teilstapel nicht nutzbar");
        h.assertTrue(p.tasks().stream().noneMatch(t -> t.type() == TaskType.CRAFT), "Trotz gutem Werkzeug Ersatz geplant");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void volleTaschenVerhindernUnaufnehmbareAusbeute(GameTestHelper h) {
        CompanionEntity c = companion(h); full(c);
        h.assertTrue(CompanionPlanner.obtainPrepared(c, Items.DIRT, 1, CompanionPlanner.RoutePolicy.AVOID_CHESTS) == null,
                "Plan sammelt ohne Platz");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void zwischenzutatenMuessenVorDemVerbrauchInsInventarPassen(GameTestHelper h) {
        CompanionEntity c = companion(h); full(c);
        c.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS));
        h.assertTrue(CompanionPlanner.obtainPrepared(c, Items.STICK, 4, CompanionPlanner.RoutePolicy.AVOID_CHESTS) == null,
                "Fehlender Stamm passt erst nach noch unmoeglichem Craften");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void verbrauchteZutatenGebenPlatzFuerDasErgebnisFrei(GameTestHelper h) {
        CompanionEntity c = companion(h); full(c);
        c.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        var p = plan(h, c, Items.STICK, 4);
        h.assertTrue(p.tasks().size() == 1 && p.tasks().getFirst().type() == TaskType.CRAFT, "Freier Ergebnisslot nicht erkannt");
        h.assertValueEqual(CompanionCraft.count(c, "oak_planks"), 2, "Virtueller Verbrauch veraendert echte Zutaten");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void entnahmePrueftKomponentenUndTeilstapel(GameTestHelper h) {
        CompanionEntity c = companion(h); full(c);
        h.setBlock(26, 1, 6, Blocks.CHEST);
        Container box = c.containerAt(h.getLevel(), h.absolutePos(new BlockPos(26, 1, 6)));
        box.setItem(0, new ItemStack(Items.COAL, 8));
        ItemStack stack = new ItemStack(Items.COAL, 56); c.getInventory().setItem(0, stack);
        h.assertTrue(CompanionPrerequisites.canTake(c, box, Items.COAL, 8), "Teilstapel wird abgelehnt");
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Reserviert"));
        h.assertTrue(!CompanionPrerequisites.canTake(c, box, Items.COAL, 8), "Verschiedene Komponenten werden gestapelt");
        h.assertValueEqual(box.getItem(0).getCount(), 8, "Vorpruefung entnimmt echte Items");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void echteEntnahmeStartetBeiZuWenigPlatzNichtTeilweise(GameTestHelper h) {
        CompanionEntity c = companion(h); full(c);
        c.getInventory().setItem(0, new ItemStack(Items.COAL, 60));
        h.setBlock(26, 1, 6, Blocks.CHEST);
        BlockPos pos = h.absolutePos(new BlockPos(26, 1, 6));
        Container box = c.containerAt(h.getLevel(), pos); box.setItem(0, new ItemStack(Items.COAL, 8));
        start(c, Task.take(pos, "coal", 8));
        new CompanionTaskGoal(c).tick();
        h.assertValueEqual(box.getItem(0).getCount(), CompanionDecisionEngine.enabled() ? 8 : 4, "Entnahme ignoriert Platzvorpruefung");
        noPenalty(h, c, Reason.INVENTORY_FULL);
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void verlorenerBrennstoffStopptAbhaengigeSchritte(GameTestHelper h) {
        CompanionEntity c = companion(h); furnace(h); give(c, Items.RAW_IRON, 1);
        start(c, Task.smelt("iron_ingot", 1));
        c.addTask(Task.craft("bucket", 1));
        new CompanionTaskGoal(c).tick();
        h.assertTrue(c.hasTasks() != CompanionDecisionEngine.enabled(), "Abhaengige Schritte laufen trotz fehlendem Brennstoff");
        if (CompanionDecisionEngine.enabled()) noPenalty(h, c, Reason.MISSING_FUEL);
        h.assertValueEqual(CompanionCraft.count(c, "raw_iron"), 1, "Fehlender Brennstoff verbraucht Eingabe");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void verschwundeneWerkbankStopptAbhaengigenPlan(GameTestHelper h) {
        CompanionEntity c = companion(h);
        give(c, Items.IRON_INGOT, 3); give(c, Items.STICK, 2); give(c, Items.OAK_PLANKS, 4);
        start(c, Task.craft("iron_pickaxe", 1));
        new CompanionTaskGoal(c).tick();
        h.assertTrue(c.hasTasks() != CompanionDecisionEngine.enabled(), "Fehlende Werkbank wird nicht als Voraussetzung behandelt");
        if (CompanionDecisionEngine.enabled()) noPenalty(h, c, Reason.MISSING_STATION);
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void brettkonvertierungVerbrauchtNurDieGeplantenStaemme(GameTestHelper h) {
        CompanionEntity c = companion(h); give(c, Items.OAK_LOG, 10);
        start(c, Task.craft("#planks_from_logs", 4));
        var executor = new CompanionTaskGoal(c);
        for (int i = 0; i < 20; i++) executor.tick();
        h.assertValueEqual(CompanionCraft.count(c, "oak_log"), CompanionDecisionEngine.enabled() ? 9 : 0, "Reservierte Staemme mitverarbeitet");
        h.assertValueEqual(CompanionCraft.count(c, "oak_planks"), CompanionDecisionEngine.enabled() ? 4 : 40, "Falsche Brettmenge");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void ersterSammelauftragBereitetVerschlissenesWerkzeugVor(GameTestHelper h) {
        CompanionEntity c = companion(h); table(h);
        ItemStack worn = new ItemStack(Items.WOODEN_PICKAXE); worn.setDamageValue(worn.getMaxDamage() - 4);
        c.giveToInventory(worn); give(c, Items.OAK_PLANKS, 8); give(c, Items.STICK, 2);
        start(c, Task.gather("kohle", 8));
        new CompanionTaskGoal(c).tick();
        h.assertTrue(c.peekTasks(c.taskCount()).stream().anyMatch(t -> t.spec() instanceof TaskSpec.Craft cr && "minecraft:wooden_pickaxe".equals(cr.item()))
                == CompanionDecisionEngine.enabled(), "Erster Sammelauftrag ignoriert Werkzeugzustand");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void nichtAuswaehlbaresNebenhandwerkzeugGiltNichtAlsBereit(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        h.assertTrue(!CompanionPrerequisites.readyTool(c, "kohle", 8), "Executor kann Werkzeug der Nebenhand nicht auswaehlen");
        c.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        h.assertTrue(CompanionPrerequisites.readyTool(c, "kohle", 8), "Hauptwerkzeug nicht erkannt");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void tiefenschieferbestandVerdecktKeinenWerkzeugmangel(GameTestHelper h) {
        CompanionEntity c = companion(h); table(h);
        give(c, Items.COBBLED_DEEPSLATE, 31); give(c, Items.OAK_PLANKS, 8); give(c, Items.STICK, 2);
        start(c, Task.gather("stein", 1));
        new CompanionTaskGoal(c).tick();
        if (CompanionDecisionEngine.enabled()) {
            h.assertTrue(c.currentTask() != null && c.currentTask().type() == TaskType.CRAFT
                    && "minecraft:wooden_pickaxe".equals(subject(c.currentTask())), "Normalisierter Bestand verhindert Werkzeugvorbereitung");
            h.assertTrue(c.peekTasks(c.taskCount()).stream().anyMatch(t -> t.type() == TaskType.GATHER
                    && t.spec().equals(new TaskSpec.Gather("stein", 1))), "Fehlbestand wird mehrfach oder gar nicht geplant");
        }
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void verlorenesWerkzeugBeimKonkretenAbbauschrittLerntKeineMethodenstrafe(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos pos = new BlockPos(26, 1, 6); h.setBlock(pos, Blocks.COAL_ORE);
        start(c, Task.breakAt(h.absolutePos(pos), Blocks.COAL_ORE));
        new CompanionTaskGoal(c).tick();
        h.assertTrue(!c.hasTasks(), "Abbau trotz fehlendem Werkzeug fortgesetzt");
        if (CompanionDecisionEngine.enabled()) noPenalty(h, c, Reason.MISSING_TOOL);
        h.assertTrue(h.getLevel().getBlockState(h.absolutePos(pos)).is(Blocks.COAL_ORE), "Erz ohne Werkzeug zerstoert");
        h.succeed();
    }
}
