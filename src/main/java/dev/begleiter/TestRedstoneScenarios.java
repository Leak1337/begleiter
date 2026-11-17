package dev.begleiter;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.material.Fluids;

import static dev.begleiter.TestScenarios.*;

/** Weitere Funktionsprüfungen auf derselben Redstone-Platte; auch von den Server-GameTests verwendet. */
final class TestRedstoneScenarios {
    private TestRedstoneScenarios() {}

    private static final Kit MACHINES = kit(Items.LEVER, 3, Items.REDSTONE, 32, Items.STONE, 32,
            Items.REDSTONE_TORCH, 4, Items.REPEATER, 6, Items.REDSTONE_LAMP, 3, Items.CHEST, 2,
            Items.HOPPER, 2, Items.COMPARATOR, 2, Items.DROPPER, 1, Items.DISPENSER, 1,
            Items.WATER_BUCKET, 1, Items.IRON_TRAPDOOR, 1, Items.POWERED_RAIL, 4,
            Items.STICKY_PISTON, 1, Items.SLIME_BLOCK, 1, Items.OBSIDIAN, 2, Items.COMPOSTER, 1,
            Items.IRON_PICKAXE, 1);

    private static BlockPos p(int x, int z) { return new BlockPos(x, 0, z); }
    private static Scenario machine(String id, String desc, Consumer<Map<BlockPos, BlockState>> plan, Check check) {
        return circuit(id, desc, MACHINES, plan, check);
    }

    /** Alle Kombinationen und Rückkehr zu 00 prüfen: ein dauerhaft falscher Ausgang darf nicht bestehen. */
    private static Check truthTable(int lampX, boolean... expected) {
        return x -> {
            if (x.phase > 0 && x.ticks - x.mark < 20) return null;
            if (lit(x, lampX, 5) != expected[x.phase])
                return fail("Eingang " + new String[]{"00", "10", "01", "11", "00"}[x.phase]
                        + ": Ausgang soll " + (expected[x.phase] ? "an" : "aus") + " sein");
            if (x.phase == expected.length - 1) return ok("alle vier Eingänge und Rücksetzen geprüft");
            x.phase++;
            flip(x, x.at(1, 0, 3), x.phase == 1 || x.phase == 3);
            flip(x, x.at(1, 0, 7), x.phase == 2 || x.phase == 3);
            return null;
        };
    }

    private static void fill(Container box, int stacks) {
        box.clearContent();
        for (int i = 0; i < stacks; i++) box.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        box.setChanged();
    }

    private static BlockState comparator(boolean subtract) {
        return Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.MODE_COMPARATOR, subtract ? ComparatorMode.SUBTRACT : ComparatorMode.COMPARE);
    }

    private static int output(Ctx x) {
        BlockState s = x.sl.getBlockState(x.at(5, 0, 5));
        return s.is(Blocks.REDSTONE_WIRE) ? s.getValue(BlockStateProperties.POWER) : -1;
    }

    private static Scenario analog(boolean subtract) {
        return machine(subtract ? "rs_subtraktion" : "rs_vergleich",
                subtract ? "Komparator subtrahiert Seitensignal" : "Komparator vergleicht zwei Signalstärken", r -> {
                    r.put(p(3, 5), Blocks.CHEST.defaultBlockState());
                    r.put(p(4, 5), comparator(subtract));
                    r.put(p(4, 4), repeater(Direction.NORTH, 1));
                    r.put(p(4, 3), lever(false));
                    r.put(p(5, 5), wire());
                    r.put(p(6, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
                }, x -> {
                    Container box = container(x, 3, 0, 5);
                    if (box == null) return fail("Signalkiste fehlt");
                    if (x.phase > 0 && x.ticks - x.mark < 20) return null;
                    int[] expected = {0, 15, subtract ? 0 : 15, 0, 4, 0};
                    if (output(x) != expected[x.phase])
                        return fail("Stufe " + x.phase + ": Signal " + output(x) + " statt " + expected[x.phase]);
                    switch (x.phase++) {
                        case 0 -> fill(box, 27);
                        case 1 -> flip(x, x.at(4, 0, 3), true);
                        case 2 -> fill(box, 6);
                        case 3 -> flip(x, x.at(4, 0, 3), false);
                        case 4 -> fill(box, 0);
                        default -> { return ok("Signale 0/15, gleicher und stärkerer Seiteneingang, Rücksetzen geprüft"); }
                    }
                    x.mark = x.ticks;
                    return null;
                });
    }

    private static final List<Scenario> ALL = List.of(
            machine("rs_nor", "NOR-Gatter: keiner an, Ausgang an", r -> {
                for (int z : new int[]{3, 7}) {
                    r.put(p(1, z), lever(false));
                    r.put(p(2, z), wire());
                    r.put(p(3, z), wire());
                }
                for (int z = 4; z <= 6; z++) r.put(p(3, z), wire());
                r.put(p(4, 5), wire());
                r.put(p(5, 5), Blocks.STONE.defaultBlockState());
                r.put(p(6, 5), wallTorch(Direction.EAST));
                r.put(p(7, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, truthTable(7, true, false, false, false, true)),
            machine("rs_nand", "NAND-Gatter: nur beide Eingänge zusammen schalten aus", r -> {
                for (int z : new int[]{3, 7}) {
                    r.put(p(1, z), lever(false));
                    r.put(p(2, z), wire());
                    r.put(p(3, z), Blocks.STONE.defaultBlockState());
                    r.put(p(4, z), wallTorch(Direction.EAST));
                    r.put(p(5, z), wire());
                }
                for (int z = 4; z <= 6; z++) r.put(p(5, z), wire());
                r.put(p(6, 5), wire());
                r.put(p(7, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, truthTable(7, true, true, true, false, true)),
            analog(false), analog(true),
            machine("rs_speicher", "Gesperrter Verstärker speichert aus und an", r -> {
                r.put(p(3, 5), lever(false));
                r.put(p(4, 5), repeater(Direction.WEST, 1));
                r.put(p(5, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
                r.put(p(4, 4), repeater(Direction.NORTH, 1));
                r.put(p(4, 3), lever(false));
            }, x -> {
                if (x.phase > 0 && x.ticks - x.mark < 20) return null;
                boolean[] expected = {false, false, false, true, true, true, false};
                if (lit(x, 5, 5) != expected[x.phase]) return fail("Speicherzustand falsch in Stufe " + x.phase);
                switch (x.phase++) {
                    case 0, 3 -> flip(x, x.at(4, 0, 3), true);
                    case 1 -> flip(x, x.at(3, 0, 5), true);
                    case 2, 5 -> flip(x, x.at(4, 0, 3), false);
                    case 4 -> flip(x, x.at(3, 0, 5), false);
                    default -> { return ok("hält beide Zustände während der Sperre und folgt nach Entriegeln"); }
                }
                return null;
            }),
            machine("rs_flanke", "Dropper gibt je Einschaltflanke genau ein Item ab", r -> {
                r.put(p(1, 5), lever(false));
                r.put(p(2, 5), Blocks.DROPPER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
                r.put(p(3, 5), Blocks.CHEST.defaultBlockState());
            }, x -> {
                Container from = container(x, 2, 0, 5), to = container(x, 3, 0, 5);
                if (from == null || to == null) return fail("Dropper oder Zielkiste fehlt");
                if (x.phase > 0 && x.ticks - x.mark < 40) return null;
                if (x.phase == 0) {
                    from.setItem(0, new ItemStack(Items.COBBLESTONE, 8));
                    x.phase = 1; x.mark = x.ticks; return null;
                }
                int want = new int[]{0, 0, 1, 1, 1, 2}[x.phase];
                if (count(to, Items.COBBLESTONE) != want || count(from, Items.COBBLESTONE) != 8 - want)
                    return fail("Stufe " + x.phase + ": erwartet " + want + " von insgesamt 8 Items im Ziel");
                switch (x.phase++) {
                    case 1, 4 -> flip(x, x.at(1, 0, 5), true);
                    case 2 -> x.mark = x.ticks; // dauerhaft an: kein zweites Item
                    case 3 -> flip(x, x.at(1, 0, 5), false);
                    default -> { return ok("zwei Flanken, genau zwei Items; Dauersignal und Ausschalten fördern nichts"); }
                }
                return null;
            }),
            machine("rs_trichtersperre", "Trichter zweimal sperren und freigeben", r -> {
                r.put(p(1, 5), lever(true));
                r.put(p(2, 5), Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, Direction.EAST));
                r.put(p(3, 5), Blocks.CHEST.defaultBlockState());
            }, x -> {
                Container from = container(x, 2, 0, 5), to = container(x, 3, 0, 5);
                if (from == null || to == null) return fail("Trichter oder Zielkiste fehlt");
                if (x.phase == 0) {
                    from.setItem(0, new ItemStack(Items.COAL, 8));
                    x.phase = 1; x.mark = x.ticks; return null;
                }
                if (x.ticks - x.mark < (x.phase % 2 == 1 ? 40 : 100)) return null;
                int want = new int[]{0, 0, 8, 8, 13}[x.phase];
                int total = x.phase <= 2 ? 8 : 13;
                if (count(to, Items.COAL) != want || count(from, Items.COAL) != total - want)
                    return fail("Sperrstufe " + x.phase + ": Transport oder Itemzahl falsch");
                switch (x.phase++) {
                    case 1, 3 -> flip(x, x.at(1, 0, 5), false);
                    case 2 -> {
                        flip(x, x.at(1, 0, 5), true);
                        from.setItem(0, new ItemStack(Items.COAL, 5));
                    }
                    default -> { return ok("gesperrt kein Transport, freigegeben alle 13 Items ohne Verlust"); }
                }
                return null;
            }),
            machine("rs_wasserwerfer", "Werfer setzt Wasser und holt es wieder zurück", r -> {
                r.put(p(2, 5), lever(false));
                r.put(p(3, 5), Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
                for (BlockPos wall : List.of(p(4, 4), p(4, 6), p(5, 5))) r.put(wall, Blocks.STONE.defaultBlockState());
            }, x -> {
                Container dispenser = container(x, 3, 0, 5);
                if (dispenser == null) return fail("Werfer fehlt");
                if (x.phase > 0 && x.ticks - x.mark < 20) return null;
                switch (x.phase++) {
                    case 0 -> {
                        dispenser.setItem(0, new ItemStack(Items.WATER_BUCKET));
                        flip(x, x.at(2, 0, 5), true);
                    }
                    case 1 -> {
                        if (!x.sl.getFluidState(x.at(4, 0, 5)).isSourceOfType(Fluids.WATER)
                                || count(dispenser, Items.BUCKET) != 1) return fail("Wasserquelle oder leerer Eimer fehlt");
                        flip(x, x.at(2, 0, 5), false);
                    }
                    case 2 -> flip(x, x.at(2, 0, 5), true);
                    default -> {
                        return x.sl.getFluidState(x.at(4, 0, 5)).isEmpty() && count(dispenser, Items.WATER_BUCKET) == 1
                                ? ok("Wasser gesetzt und aufgenommen, derselbe Eimer wieder voll") : fail("Wasser nicht zurückgeholt");
                    }
                }
                return null;
            }),
            machine("rs_falltuere", "Eisenfalltür öffnet und schließt elektrisch", r -> {
                r.put(p(2, 5), lever(false));
                r.put(p(3, 5), Blocks.IRON_TRAPDOOR.defaultBlockState());
            }, x -> {
                if (x.phase > 0 && x.ticks - x.mark < 10) return null;
                BlockState door = x.sl.getBlockState(x.at(3, 0, 5));
                if (!door.is(Blocks.IRON_TRAPDOOR)) return fail("Eisenfalltür fehlt");
                if (door.getValue(BlockStateProperties.OPEN) != (x.phase == 1)) return fail("Falltürzustand falsch");
                if (x.phase == 2) return ok("zu, auf, wieder zu");
                flip(x, x.at(2, 0, 5), x.phase++ == 0);
                return null;
            }),
            machine("rs_schienen", "Vier Antriebsschienen leiten Strom weiter und schalten ab", r -> {
                r.put(p(1, 5), lever(false));
                for (int rail = 2; rail <= 5; rail++) r.put(p(rail, 5), Blocks.POWERED_RAIL.defaultBlockState()
                        .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, RailShape.EAST_WEST));
            }, x -> {
                if (x.phase > 0 && x.ticks - x.mark < 20) return null;
                for (int rail = 2; rail <= 5; rail++) {
                    BlockState state = x.sl.getBlockState(x.at(rail, 0, 5));
                    if (!state.is(Blocks.POWERED_RAIL) || state.getValue(BlockStateProperties.POWERED) != (x.phase == 1))
                        return fail("Antriebsschiene " + rail + " schaltet falsch");
                }
                if (x.phase == 2) return ok("alle vier Schienen aus, an, wieder aus");
                flip(x, x.at(1, 0, 5), x.phase++ == 0);
                return null;
            }),
            machine("rs_schleim", "Klebekolben schiebt und zieht einen Schleimblock mit Seitenlast", r -> {
                r.put(p(2, 5), lever(false));
                r.put(p(3, 5), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
                r.put(p(4, 5), Blocks.SLIME_BLOCK.defaultBlockState());
                r.put(p(4, 6), Blocks.STONE.defaultBlockState());
                // Schleim darf nicht am Stationsboden kleben: Unterlage an seiner Strecke aus Obsidian.
                r.put(new BlockPos(4, -1, 5), Blocks.OBSIDIAN.defaultBlockState());
                r.put(new BlockPos(5, -1, 5), Blocks.OBSIDIAN.defaultBlockState());
            }, x -> {
                if (x.phase > 0 && x.ticks - x.mark < 20) return null;
                int sx = x.phase == 1 ? 5 : 4;
                if (!x.sl.getBlockState(x.at(sx, 0, 5)).is(Blocks.SLIME_BLOCK)
                        || !x.sl.getBlockState(x.at(sx, 0, 6)).is(Blocks.STONE)
                        || !x.sl.getBlockState(x.at(sx == 4 ? 5 : 4, 0, 6)).isAir())
                    return fail("Schleim oder Seitenlast nicht an der erwarteten Stelle");
                if (x.phase == 2) return ok("Schleim und seitlicher Stein gemeinsam vorgeschoben und zurückgezogen");
                flip(x, x.at(2, 0, 5), x.phase++ == 0);
                return null;
            }),
            machine("rs_obsidian", "Obsidian blockiert einen Kolben ohne Blöcke zu verschlucken", r -> {
                r.put(p(2, 5), lever(false));
                r.put(p(3, 5), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
                r.put(p(4, 5), Blocks.OBSIDIAN.defaultBlockState());
            }, x -> {
                if (x.phase > 0 && x.ticks - x.mark < 20) return null;
                BlockState piston = x.sl.getBlockState(x.at(3, 0, 5));
                if (!piston.is(Blocks.STICKY_PISTON) || piston.getValue(BlockStateProperties.EXTENDED)
                        || !x.sl.getBlockState(x.at(4, 0, 5)).is(Blocks.OBSIDIAN)
                        || !x.sl.getBlockState(x.at(5, 0, 5)).isAir()) return fail("blockierter Kolben hat die Welt falsch verändert");
                if (x.phase == 2) return ok("Kolben bleibt bei Strom und nach Abschalten eingefahren, Obsidian unverändert");
                flip(x, x.at(2, 0, 5), x.phase++ == 0);
                return null;
            }),
            machine("rs_komposter", "Komparator liest mehrere Komposter-Füllstände", r -> {
                r.put(p(3, 5), Blocks.COMPOSTER.defaultBlockState());
                r.put(p(4, 5), comparator(false));
                r.put(p(5, 5), wire());
            }, x -> {
                if (x.phase > 0 && x.ticks - x.mark < 20) return null;
                int[] levels = {0, 3, 6, 0};
                if (output(x) != levels[x.phase]) return fail("Komposter: Signal " + output(x) + " statt " + levels[x.phase]);
                if (++x.phase == levels.length) return ok("Komposter-Signal 0, 3, 6 und wieder 0");
                x.sl.setBlock(x.at(3, 0, 5), Blocks.COMPOSTER.defaultBlockState()
                        .setValue(BlockStateProperties.LEVEL_COMPOSTER, levels[x.phase]), 3);
                x.mark = x.ticks;
                return null;
            })
    );

    static List<Scenario> all() { return ALL; }
}
