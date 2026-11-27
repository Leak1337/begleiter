package dev.begleiter;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.material.Fluids;
import static dev.begleiter.TestScenarios.*;

/** Betrieb derselben Baupläne, die in normalen Welten über Menü und Bauplan-Befehl verfügbar sind. */
final class TestMachineScenarios {
    private TestMachineScenarios() {}

    private static Scenario machine(String id, Check check) {
        var blueprint = CompanionMachines.find(id);
        return new Scenario("farm_" + id, "redstone", blueprint.plan().get().name() + " bauen und betreiben", 240, x -> {
            var plan = blueprint.plan().get();
            var abs = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
            plan.blocks().forEach((pos, state) -> abs.put(x.c.offset(pos), state));
            var job = CompanionBuilder.create(x.sl, plan.name(), abs);
            var materials = CompanionBuilder.remaining(x.sl, job);
            materials.merge(Items.COBBLESTONE, 16, Integer::sum); // Gerüst für die normale Survival-Navigation
            materials.merge(Items.IRON_PICKAXE, 1, Integer::sum);
            var kit = new ArrayList<Object>();
            materials.forEach((item, count) -> {
                while (count > 0) {
                    int amount = Math.min(count, item.getDefaultMaxStackSize());
                    kit.add(item); kit.add(amount); count -= amount;
                }
            });
            place(x, new int[]{10, 0, 10}, new Kit(kit.toArray()));
            x.bot.build = job;
            x.bot.addTask(CompanionEntity.Task.build());
        }, x -> {
            if (x.bot.build != null) return pendingBuild(x);
            if (x.mark == 0) {
                x.mark = x.ticks;
                x.bot.stay();
                // Die Prüfung soll die Sammelanlage messen, ohne dass der Begleiter lose Ernte aufsammelt.
                x.bot.snapTo(x.at(11, 0, 11).getX() + 0.5, x.c.getY(), x.at(11, 0, 11).getZ() + 0.5, 0, 0);
                return null;
            }
            if (x.ticks - x.mark < 40) return null;
            return check.run(x);
        }, false);
    }

    private static void grow(Ctx x, boolean bamboo) {
        for (int column = 2; column <= 5; column++)
            for (int height = 3; height <= 4; height++)
                x.sl.setBlock(x.at(column, height, 3), (bamboo ? Blocks.BAMBOO : Blocks.SUGAR_CANE).defaultBlockState(), 3);
    }

    private static String inventory(Ctx x, int a, int y, int z) {
        var box = container(x, a, y, z);
        if (box == null) return "fehlt";
        var items = new ArrayList<String>();
        for (int i = 0; i < box.getContainerSize(); i++) if (!box.getItem(i).isEmpty()) items.add(box.getItem(i).toString());
        return items.toString();
    }

    private static Scenario stalk(String id, boolean bamboo) {
        return machine(id, x -> {
            Item item = bamboo ? Items.BAMBOO : Items.SUGAR_CANE;
            if (x.phase == 0) { grow(x, bamboo); x.phase = 1; x.mark = x.ticks; return null; }
            for (int column = 2; column <= 5; column++) {
                if (!x.sl.getBlockState(x.at(column, 2, 3)).is(bamboo ? Blocks.BAMBOO : Blocks.SUGAR_CANE))
                    return fail("Grundpflanze " + column + " wurde zerstört");
                // Eine zweite Stufe darf inzwischen natürlich nachgewachsen sein. Die dritte, welche die
                // Ernte auslöst, muss verschwunden sein; zusätzlich wird neue Ernte in der Kiste verlangt.
                if (!x.sl.getBlockState(x.at(column, 4, 3)).isAir())
                    return x.ticks - x.mark > 100 ? fail("Beobachter/Kolben ernten nicht vollständig") : null;
            }
            int collected = count(container(x, 0, 0, 2), item);
            if (collected <= x.counter) return x.ticks - x.mark > 200 ? fail("Ernte erreicht Sammelkiste nicht: Trichter "
                    + inventory(x, 1, 0, 2) + ", Wasser " + x.sl.getFluidState(x.at(1, 1, 2)) + ", lose "
                    + x.sl.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(x.c).inflate(12)).stream()
                            .filter(e -> e.getItem().is(item)).map(e -> e.position().subtract(net.minecraft.world.phys.Vec3.atLowerCornerOf(x.c)).toString()).toList()) : null;
            if (x.phase == 2) return ok("zwei automatische Ernten, Grundpflanzen erhalten, " + collected + " Items in Sammelkiste");
            x.counter = collected;
            grow(x, bamboo); x.phase = 2; x.mark = x.ticks;
            return null;
        });
    }

    private static void button(Ctx x, boolean powered) {
        BlockPos pos = x.at(4, 2, 10);
        x.sl.setBlock(pos, x.sl.getBlockState(pos).setValue(ButtonBlock.POWERED, powered), 3);
        x.sl.updateNeighborsAt(x.at(4, 2, 9), Blocks.DISPENSER, null);
        x.mark = x.ticks;
    }

    private static Scenario waterCrop(String id, net.minecraft.world.level.block.Block crop, Item produce) {
        return machine(id, x -> {
                var dispenser = container(x, 4, 2, 9);
                if (dispenser == null) return fail("Erntewerfer fehlt");
                switch (x.phase) {
                    case 0 -> {
                        for (int a = 2; a <= 6; a++) for (int z = 3; z <= 7; z++)
                            x.sl.setBlock(x.at(a, 2, z), crop.defaultBlockState().setValue(CropBlock.AGE, 7), 3);
                        dispenser.setItem(0, new ItemStack(Items.WATER_BUCKET));
                        button(x, true); x.phase = 1;
                    }
                    case 1 -> {
                        if (x.ticks - x.mark < 140) return null;
                        for (int a = 2; a <= 6; a++) for (int z = 3; z <= 7; z++)
                            if (x.sl.getBlockState(x.at(a, 2, z)).is(crop)) return fail("Ernte nicht vollständig abgeerntet");
                        // gesammelt = in der Kiste oder schon in der Trichterrinne (die liefert erst die Samen)
                        int collected = count(container(x, 1, 0, 2), produce);
                        for (int a = 2; a <= 6; a++) collected += count(container(x, a, 0, 2), produce);
                        if (collected < 1) {
                            var hoppers = new ArrayList<String>();
                            for (int a = 2; a <= 6; a++) hoppers.add(inventory(x, a, 0, 2));
                            return fail("Ernte landet nicht in der Kiste: Kiste " + inventory(x, 1, 0, 2) + ", Trichter " + hoppers
                                    + ", lose " + x.sl.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                                            new net.minecraft.world.phys.AABB(x.c).inflate(14)).stream()
                                            .map(e -> e.getItem() + "@" + e.blockPosition().subtract(x.c).toShortString()).toList());
                        }
                        button(x, false); x.phase = 2;
                    }
                    case 2 -> { button(x, true); x.phase = 3; }
                    default -> {
                        if (x.ticks - x.mark < 140) return null;
                        if (count(dispenser, Items.WATER_BUCKET) != 1) return fail("Wasser wurde nicht zurückgeholt");
                        for (int a = 2; a <= 6; a++) for (int z = 3; z <= 7; z++) {
                            if (!x.sl.getBlockState(x.at(a, 1, z)).is(Blocks.FARMLAND)
                                    || !x.sl.getFluidState(x.at(a, 2, z)).isEmpty()) return fail("Feld nach Ernte nicht wieder bepflanzbar");
                            x.sl.setBlock(x.at(a, 2, z), crop.defaultBlockState(), 3);
                        }
                        return ok("25 Pflanzen abgeerntet, Ernte gesammelt, Wasser zurückgeholt, neu gesät");
                    }
                }
                return null;
            });
    }

    private static Scenario smelt(String id, Item raw, Item product) {
        return machine(id, x -> {
                var input = container(x, 4, 4, 3);
                var fuel = container(x, 3, 3, 3);
                if (input == null || fuel == null) return fail("Eingabekisten fehlen");
                if (x.phase == 0) {
                    input.setItem(0, new ItemStack(raw, 3));
                    fuel.setItem(0, new ItemStack(Items.COAL, 2));
                    x.phase = 1; x.mark = x.ticks; return null;
                }
                int iron = count(container(x, 4, 1, 2), product);
                if (iron < 3) return x.ticks - x.mark > 850 ? fail("nur " + iron + "/3 Produkte; Eingang "
                        + inventory(x, 4, 4, 3) + ", Einlauf " + inventory(x, 4, 3, 3) + ", Brennstoff "
                        + inventory(x, 3, 3, 3) + ", Ofen " + inventory(x, 4, 2, 3) + ", Auslauf " + inventory(x, 4, 1, 3)) : null;
                if (!x.sl.getBlockState(x.at(4, 1, 0)).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT))
                    return x.ticks - x.mark > 870 ? fail("Ausgabelampe bleibt aus") : null;
                return ok("3 Eingaben über obere Kiste geschmolzen, seitlicher Brennstoff, 3 Produkte in Ausgabe, Lampe an");
            });
    }

    private static final List<Scenario> ALL = List.of(
            stalk("zuckerrohrfarm", false), stalk("bambusfarm", true),
            machine("kaktusfarm", x -> {
                if (x.phase == 0 || x.ticks - x.mark >= 60 && x.counter < 3) {
                    for (int a : new int[]{2, 4, 6}) for (int z : new int[]{2, 4, 6}) {
                        BlockPos top = x.at(a, 3, z);
                        x.sl.setBlock(top, Blocks.CACTUS.defaultBlockState(), 3);
                        x.sl.scheduleTick(top, Blocks.CACTUS, 1); // Überlebensprüfung wie nach natürlichem Wachstum
                    }
                    x.phase = 1; x.mark = x.ticks; x.counter++; return null;
                }
                if (x.counter < 3 || x.ticks - x.mark < 100) return null;
                for (int a : new int[]{2, 4, 6}) for (int z : new int[]{2, 4, 6})
                    if (!x.sl.getBlockState(x.at(a, 2, z)).is(Blocks.CACTUS)
                            || x.sl.getBlockState(x.at(a, 3, z)).is(Blocks.CACTUS)) return fail("Kaktus-Grundstock oder Erntehöhe falsch");
                int harvested = count(container(x, 0, 0, 1), Items.CACTUS);
                return harvested > 0 ? ok("9 Grundpflanzen erhalten, 3 Wachstumswellen, " + harvested + " Kakteen in Sammelkiste")
                        : x.ticks - x.mark > 250 ? fail("Kakteen erreichen Ausgabekiste nicht") : null;
            }),
            machine("schaltlager", x -> {
                var input = container(x, 3, 2, 3);
                var output = container(x, 4, 1, 3);
                if (input == null || output == null) return fail("Lagerkisten fehlen");
                if (x.phase == 0) { input.setItem(0, new ItemStack(Items.COBBLESTONE, 8)); x.mark = x.ticks; x.phase = 1; return null; }
                if (x.ticks - x.mark < 100) return null;
                int expected = x.phase == 1 ? 0 : 8;
                if (count(output, Items.COBBLESTONE) != expected) return fail("Schaltlager fördert im falschen Zustand");
                if (x.phase == 3) return ok("Sperre hält Items, Freigabe transportiert genau 8, erneute Sperre hält Nachschub");
                flip(x, x.at(2, 1, 3), x.phase == 2);
                if (x.phase == 2) input.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
                x.phase++; return null;
            }),
            machine("portionierer", x -> {
                var input = container(x, 3, 3, 3);
                var output = container(x, 4, 1, 3);
                if (input == null || output == null) return fail("Portionierer-Kisten fehlen");
                BlockPos button = x.at(2, 1, 3);
                if (x.phase == 0) { input.setItem(0, new ItemStack(Items.COBBLESTONE, 8)); x.mark = x.ticks; x.phase = 1; return null; }
                if (x.ticks - x.mark < 100) return null;
                int expected = x.phase == 1 ? 0 : x.phase <= 3 ? 1 : 2;
                if (count(output, Items.COBBLESTONE) != expected) return fail("Portionierer gibt falsche Itemzahl aus");
                if (x.phase == 4) return ok("zwei Knopfimpulse, genau zwei Items, kein Dauertransport");
                x.sl.setBlock(button, x.sl.getBlockState(button).setValue(ButtonBlock.POWERED, x.phase != 2), 3);
                x.mark = x.ticks; x.phase++; return null;
            }),
            waterCrop("wasserernte", Blocks.WHEAT, Items.WHEAT),
            waterCrop("karottenfarm", Blocks.CARROTS, Items.CARROT),
            waterCrop("kartoffelfarm", Blocks.POTATOES, Items.POTATO),
            smelt("schmelzofen", Items.RAW_IRON, Items.IRON_INGOT),
            smelt("raeucherofen", Items.BEEF, Items.COOKED_BEEF),
            smelt("hochofen", Items.RAW_IRON, Items.IRON_INGOT),
            machine("komposter", x -> {
                var input = container(x, 3, 4, 3);
                if (input == null) return fail("Eingabekiste fehlt");
                if (x.phase == 0) {
                    input.setItem(0, new ItemStack(Items.PUMPKIN_PIE, 7)); // 100 % Kompostchance, kein Zufallstest
                    x.phase = 1; x.mark = x.ticks; return null;
                }
                int boneMeal = count(container(x, 4, 1, 3), Items.BONE_MEAL);
                return boneMeal == 1 ? ok("7 Kürbiskuchen automatisch kompostiert, 1 Knochenmehl in Ausgabekiste")
                        : x.ticks - x.mark > 240 ? fail("Knochenmehl fehlt oder wurde vervielfacht") : null;
            }),
            machine("itemsortierer", x -> {
                var input = container(x, 3, 4, 3);
                var filter = container(x, 3, 2, 3);
                if (input == null || filter == null) return fail("Eingabekiste oder Filtertrichter fehlt");
                if (x.phase == 0) {
                    TestMapExpansion.primeSorter(filter);
                    input.setItem(0, new ItemStack(Items.IRON_INGOT, 8));
                    input.setItem(1, new ItemStack(Items.COBBLESTONE, 8));
                    x.phase = 1; x.mark = x.ticks; return null;
                }
                // das zuletzt freigegebene Zielitem wartet im gesperrten Auslass, bis das nächste es nachschiebt
                int sortedIron = count(container(x, 3, 1, 2), Items.IRON_INGOT) + count(container(x, 3, 1, 3), Items.IRON_INGOT);
                int sortedCobble = count(container(x, 3, 1, 2), Items.COBBLESTONE) + count(container(x, 3, 1, 3), Items.COBBLESTONE);
                int restIron = count(container(x, 4, 3, 3), Items.IRON_INGOT), restCobble = count(container(x, 4, 3, 3), Items.COBBLESTONE);
                if (sortedCobble > 0) return fail(sortedCobble + " Bruchstein in der Sortierkiste");
                if (restIron > 0) return fail(restIron + " Eisen am Filter vorbei in den Rest");
                if (sortedIron < 8 || restCobble < 8) return x.ticks - x.mark > 600 ? fail("sortiert " + sortedIron + "/8 Eisen, Rest "
                        + restCobble + "/8 Bruchstein; Filter " + inventory(x, 3, 2, 3) + ", Auslass " + inventory(x, 3, 1, 3)) : null;
                if (count(filter, Items.IRON_INGOT) != 18) return fail("Filter verliert Zielitems: " + inventory(x, 3, 2, 3)
                        + "; Umkehrer " + x.sl.getBlockState(x.at(1, 2, 6)) + ", Auslass " + x.sl.getBlockState(x.at(3, 1, 3))
                        + ", Staub " + x.sl.getBlockState(x.at(3, 1, 4)) + " / " + x.sl.getBlockState(x.at(1, 2, 4)));
                return ok("8 Eisen aussortiert (" + count(container(x, 3, 1, 3), Items.IRON_INGOT) + " wartet im Auslass), 8 Bruchstein durchgelassen, Filter hält 18 + 4");
            }),
            machine("bruchsteingenerator", x -> {
                BlockPos spot = x.at(3, 2, 2);
                for (BlockPos q : BlockPos.betweenClosed(x.at(0, 0, 0), x.at(6, 4, 4)))
                    if (x.sl.getBlockState(q).is(Blocks.OBSIDIAN)) return fail("Obsidian entstanden – Wasser erreicht die Lavaquelle");
                if (!x.sl.getBlockState(spot).is(Blocks.COBBLESTONE))
                    return x.ticks - x.mark > 300 ? fail("kein Bruchstein nach " + (x.ticks - x.mark) / 20 + " s (Stelle: "
                            + x.sl.getBlockState(spot).getBlock().getName().getString() + ")") : null;
                if (x.counter == 3) return ok("Bruchstein dreimal abgebaut und jedes Mal neu entstanden, Lavaquelle erhalten");
                x.sl.destroyBlock(spot, false); // wie Abbauen
                x.counter++; x.mark = x.ticks;
                return null;
            }),
            machine("lichtschalter", x -> {
                // links, rechts, rechts, links: jeder Druck wechselt, egal an welchem Ende
                int[] order = {0, 8, 8, 0};
                if (x.ticks - x.mark < 12) return null;
                if (x.phase == 1) { // loslassen
                    BlockPos button = x.at(order[x.counter], 1, 2);
                    x.sl.setBlock(button, x.sl.getBlockState(button).setValue(ButtonBlock.POWERED, false), 3);
                    x.sl.updateNeighborsAt(button.below(), Blocks.STONE_BUTTON, null);
                    x.counter++; x.phase = 0; x.mark = x.ticks;
                    return null;
                }
                boolean want = x.counter % 2 == 1;
                for (int a : new int[]{1, 7}) {
                    boolean on = x.sl.getBlockState(x.at(a, 1, 4)).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT);
                    if (on != want) return fail("nach " + x.counter + " Drücken Lampe bei x " + a + " " + (on ? "an" : "aus")
                            + "; Birne " + x.sl.getBlockState(x.at(4, 1, 2)));
                }
                if (x.counter == order.length) return ok("Licht von beiden Enden geschaltet: an, aus, an, aus");
                BlockPos button = x.at(order[x.counter], 1, 2);
                x.sl.setBlock(button, x.sl.getBlockState(button).setValue(ButtonBlock.POWERED, true), 3);
                x.sl.updateNeighborsAt(button.below(), Blocks.STONE_BUTTON, null);
                x.phase = 1; x.mark = x.ticks;
                return null;
            }),
            machine("tresorschloss", x -> {
                // Hebel 1, 2, 3 – nur 1 an, 2 aus, 3 an öffnet
                boolean[][] tries = {{true, false, false}, {true, true, true}, {false, true, false}, {false, false, true},
                        {true, false, true}, {false, false, false}};
                int[] zs = {1, 3, 5};
                if (x.phase > 0 && x.ticks - x.mark < 12) return null;
                if (x.phase > 0) {
                    boolean[] t = tries[x.phase - 1];
                    boolean want = t[0] && !t[1] && t[2];
                    boolean open = x.sl.getBlockState(x.at(9, 1, 3)).getValue(net.minecraft.world.level.block.DoorBlock.OPEN);
                    if (open != want) return fail("Hebel " + (t[0] ? 1 : 0) + (t[1] ? 1 : 0) + (t[2] ? 1 : 0) + ": Tür "
                            + (open ? "offen" : "zu") + ", soll " + (want ? "offen" : "zu"));
                }
                if (x.phase == tries.length) return ok("nur Kombination 1-0-1 öffnet, alle " + tries.length + " Versuche richtig");
                boolean[] next = tries[x.phase];
                for (int i = 0; i < 3; i++) flip(x, x.at(1, 1, zs[i]), next[i]);
                x.phase++; x.mark = x.ticks;
                return null;
            })
    );

    private static final List<String> NEW_FARMS = List.of("ruebenfarm", "kuerbisfarm", "melonenfarm",
            "kuhfarm", "schaffarm", "schweinefarm", "kaninchenfarm", "huehnerfarm");

    /** Neue manuelle Anlagen: nach echtem Survival-Bau müssen Pflanzen, Gehege und Lager erhalten bleiben. */
    private static final List<Scenario> EXPANDED = java.util.stream.Stream.concat(ALL.stream(), NEW_FARMS.stream()
            .map(id -> machine(id, x -> {
                var plan = CompanionMachines.find(id).plan().get();
                for (var entry : plan.blocks().entrySet()) {
                    var target = entry.getValue();
                    if (target.isAir()) continue;
                    var actual = x.sl.getBlockState(x.c.offset(entry.getKey()));
                    // Gras auf geplanter Erde u. Ä. ist Welt, kein fehlendes Bauteil (wie in der Bau-Nachkontrolle)
                    if (!CompanionBuilder.matches(actual, target) && !CompanionBuilder.naturalChange(target, actual))
                        return fail("Farmbauteil fehlt: " + id + " " + entry.getKey().toShortString() + " soll " + target + " ist " + actual);
                }
                return ok("Farm vollständig gebaut; Pflanzen, Gehege und Lager bleiben nach Updates erhalten");
            }))).toList();

    static List<Scenario> all() { return EXPANDED; }

    /**
     * Abwandlungen mit demselben Prinzip wie ein anderer Bauplan (Bambus wie Zuckerrohr, Karotte/Kartoffel wie Weizen,
     * Räucher-/Hochofen wie Schmelzofen): nicht in den Gruppen, nur einzeln oder über "farmen_alle".
     */
    private static final java.util.Set<String> VARIANTS = java.util.Set.of("farm_bambusfarm", "farm_karottenfarm",
            "farm_kartoffelfarm", "farm_raeucherofen", "farm_hochofen");

    static boolean variant(Scenario s) { return VARIANTS.contains(s.id()); }
}
