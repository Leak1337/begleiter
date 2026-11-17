package dev.begleiter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/** Vanilla-Anlagen für normale Welten. Dieselben Pläne werden im Baumenü und auf der Testmap benutzt. */
public final class CompanionMachines {
    private CompanionMachines() {}

    public record Blueprint(String id, String icon, Supplier<CompanionSchematics.Schematic> plan) {
        public net.minecraft.network.chat.Component title() { return CompanionTexts.tr("blueprint." + id + ".name"); }
        public net.minecraft.network.chat.Component help() { return CompanionTexts.tr("blueprint." + id + ".help"); }
    }

    private static final Blueprint WHEAT = new Blueprint("weizenfarm", "wheat", () -> CompanionStructures.wheatFarm(7));
    private static final Blueprint LARGE_WHEAT = new Blueprint("weizenfarm_gross", "wheat", () -> CompanionStructures.wheatFarm(9));

    public static final List<Blueprint> ALL = List.of(
            new Blueprint("zuckerrohrfarm", "sugar_cane", () -> stalkFarm(false)),
            new Blueprint("bambusfarm", "bamboo", () -> stalkFarm(true)),
            new Blueprint("wasserernte", "wheat", CompanionMachines::waterHarvest),
            new Blueprint("schmelzofen", "furnace", CompanionMachines::smelter),
            new Blueprint("komposter", "composter", CompanionMachines::composter),
            new Blueprint("karottenfarm", "carrot", () -> waterHarvest(Blocks.CARROTS, "karottenfarm")),
            new Blueprint("kartoffelfarm", "potato", () -> waterHarvest(Blocks.POTATOES, "kartoffelfarm")),
            new Blueprint("kaktusfarm", "cactus", CompanionMachines::cactusFarm),
            new Blueprint("raeucherofen", "smoker", () -> smelter(Blocks.SMOKER, "raeucherofen")),
            new Blueprint("hochofen", "blast_furnace", () -> smelter(Blocks.BLAST_FURNACE, "hochofen")),
            new Blueprint("schaltlager", "hopper", CompanionMachines::switchableStorage),
            new Blueprint("portionierer", "dropper", CompanionMachines::portioner),
            new Blueprint("itemsortierer", "comparator", CompanionMachines::itemSorter),
            new Blueprint("bruchsteingenerator", "cobblestone", CompanionMachines::cobbleGenerator),
            new Blueprint("lichtschalter", "redstone_lamp", CompanionMachines::toggleLight),
            new Blueprint("tresorschloss", "iron_door", CompanionMachines::combinationLock),
            new Blueprint("ruebenfarm", "beetroot", CompanionMachines::beetBeds),
            new Blueprint("kuerbisfarm", "pumpkin", CompanionMachines::pumpkinPatch),
            new Blueprint("melonenfarm", "melon_slice", CompanionMachines::melonGreenhouse),
            new Blueprint("kuhfarm", "leather", () -> animalPen("kuhfarm")),
            new Blueprint("schaffarm", "white_wool", () -> animalPen("schaffarm")),
            new Blueprint("schweinefarm", "porkchop", () -> animalPen("schweinefarm")),
            new Blueprint("kaninchenfarm", "rabbit_hide", CompanionMachines::rabbitHutch),
            new Blueprint("huehnerfarm", "egg", CompanionMachines::chickenCoop));

    public static Blueprint find(String query) {
        if (query == null) return null;
        String name = CompanionBrain.normalize(query).replace(" ", "").replace("-", "").replace("_", "");
        if (name.equals("weizenfarm") || name.equals("wheatfarm")) return WHEAT;
        if (name.equals("weizenfarmgross") || name.equals("largewheatfarm")) return LARGE_WHEAT;
        name = switch (name) {
            case "sugarcanefarm", "zuckerrohr" -> "zuckerrohrfarm";
            case "bamboofarm", "bambus" -> "bambusfarm";
            case "wheatflushfarm", "weizenwasserfarm", "wasserfarm" -> "wasserernte";
            case "autosmelter", "automatischerofen" -> "schmelzofen";
            case "autocomposter", "composter" -> "komposter";
            case "carrotfarm", "carrots" -> "karottenfarm";
            case "potatofarm", "potatoes" -> "kartoffelfarm";
            case "cactusfarm", "cactus" -> "kaktusfarm";
            case "autosmoker", "smoker" -> "raeucherofen";
            case "autoblastfurnace", "blastfurnace" -> "hochofen";
            case "switchablestorage", "hopperlock" -> "schaltlager";
            case "itemportioner", "portioner" -> "portionierer";
            case "itemsorter", "sorter", "sortierer" -> "itemsortierer";
            case "cobblestonegenerator", "cobblegenerator", "bruchsteinmaschine" -> "bruchsteingenerator";
            case "togglelight", "tflipflop", "wechselschalter", "flipflop" -> "lichtschalter";
            case "combinationlock", "kombinationsschloss", "tresor" -> "tresorschloss";
            case "beetrootfarm", "beetfarm", "rotebetefarm" -> "ruebenfarm";
            case "pumpkinfarm", "kuerbis" -> "kuerbisfarm";
            case "melonfarm", "melone" -> "melonenfarm";
            case "cowfarm", "cattlefarm", "rinderfarm", "kuhstall" -> "kuhfarm";
            case "sheepfarm", "woolfarm", "wollfarm", "schafstall" -> "schaffarm";
            case "pigfarm", "schweinestall" -> "schweinefarm";
            case "rabbitfarm", "hasenfarm" -> "kaninchenfarm";
            case "chickenfarm", "eggfarm", "eierfarm", "huehnerstall" -> "huehnerfarm";
            default -> name;
        };
        String id = name;
        return ALL.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }

    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }
    private static void put(Map<BlockPos, BlockState> m, int x, int y, int z, Block block) {
        m.put(p(x, y, z), block.defaultBlockState());
    }
    private static void hopper(Map<BlockPos, BlockState> m, int x, int y, int z, Direction facing) {
        m.put(p(x, y, z), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing));
    }
    private static Map<BlockPos, BlockState> base(int width, int height, int depth) {
        Map<BlockPos, BlockState> m = new LinkedHashMap<>();
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            put(m, x, 0, z, Blocks.COBBLESTONE);
            for (int y = 1; y < height; y++) put(m, x, y, z, Blocks.AIR);
        }
        return m;
    }

    /** Dauerhafte Stiele, freie benachbarte Erde für Früchte; manuelle Ernte ohne Redstone. */
    private static CompanionSchematics.Schematic pumpkinPatch() {
        var m = base(9, 5, 9);
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 7; z++)
            put(m, x, 0, z, Blocks.DIRT);
        for (int z : new int[]{2, 4, 6}) {
            put(m, 4, 0, z, Blocks.WATER);
            put(m, 4, 1, z, Blocks.OAK_SLAB);
            for (int x : new int[]{2, 6}) {
                m.put(p(x, 0, z), Blocks.FARMLAND.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.MOISTURE, 7));
                put(m, x, 1, z, Blocks.PUMPKIN_STEM);
            }
        }
        for (int x : new int[]{0, 8}) for (int z : new int[]{0, 8}) {
            for (int y = 1; y <= 3; y++) put(m, x, y, z, Blocks.OAK_FENCE);
            put(m, x, 4, z, Blocks.GLOWSTONE);
        }
        put(m, 4, 1, 0, Blocks.BARREL);
        return CompanionSchematics.normalize("kuerbisfarm", m);
    }

    /** Zwei erhöhte Beete, ein breiter Mittelgang und seitliche, abgedeckte Bewässerung. */
    private static CompanionSchematics.Schematic beetBeds() {
        var m = base(11, 5, 9);
        for (int x : new int[]{1, 2, 3, 7, 8, 9}) for (int z = 2; z <= 6; z++) {
            m.put(p(x, 1, z), Blocks.FARMLAND.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.MOISTURE, 7));
            put(m, x, 2, z, Blocks.BEETROOTS);
        }
        for (int x : new int[]{0, 4, 6, 10}) for (int z = 1; z <= 7; z++) put(m, x, 1, z, Blocks.OAK_LOG);
        for (int x : new int[]{4, 6}) {
            put(m, x, 1, 4, Blocks.WATER);
            put(m, x, 2, 4, Blocks.OAK_SLAB);
        }
        for (int x = 1; x <= 9; x++) for (int z : new int[]{1, 7}) put(m, x, 1, z, Blocks.OAK_LOG);
        for (int z = 1; z <= 7; z++) put(m, 5, 1, z, Blocks.OAK_PLANKS);
        put(m, 5, 1, 0, Blocks.COBBLESTONE_STAIRS);
        put(m, 5, 1, 8, Blocks.BARREL);
        for (int x : new int[]{0, 10}) for (int z : new int[]{1, 7}) {
            put(m, x, 2, z, Blocks.OAK_FENCE);
            put(m, x, 3, z, Blocks.GLOWSTONE);
        }
        return CompanionSchematics.normalize("ruebenfarm", m);
    }

    /** Schmales Gewächshaus: zwei Stielreihen, feste Fruchtstreifen und ein mittlerer Erntegang. */
    private static CompanionSchematics.Schematic melonGreenhouse() {
        var m = base(9, 6, 12);
        for (int z = 1; z <= 10; z++) {
            for (int x : new int[]{1, 2, 3, 5, 6, 7}) put(m, x, 0, z, Blocks.DIRT);
            put(m, 4, 0, z, Blocks.OAK_PLANKS);
            for (int x : new int[]{0, 8}) for (int y = 1; y <= 3; y++) put(m, x, y, z, Blocks.GLASS);
            for (int x = 0; x <= 8; x++) put(m, x, 4, z, Blocks.GLASS);
        }
        for (int z : new int[]{2, 5, 8}) for (int x : new int[]{2, 6}) {
            m.put(p(x, 0, z), Blocks.FARMLAND.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.MOISTURE, 7));
            put(m, x, 1, z, Blocks.MELON_STEM);
        }
        for (int x : new int[]{1, 7}) {
            put(m, x, 0, 5, Blocks.WATER);
            put(m, x, 1, 5, Blocks.OAK_SLAB);
        }
        for (int x = 0; x <= 8; x++) for (int z : new int[]{0, 11}) for (int y = 1; y <= 4; y++)
            if (x != 4 || y > 2) put(m, x, y, z, Blocks.GLASS);
        put(m, 4, 4, 3, Blocks.GLOWSTONE);
        put(m, 4, 4, 8, Blocks.GLOWSTONE);
        put(m, 3, 1, 0, Blocks.BARREL);
        // Wartungsaufstieg durch eine Dachöffnung: der Bauhelfer kann nach dem Glasbau wieder hinunter.
        for (int y = 1; y <= 4; y++) {
            put(m, 4, y, 11, Blocks.GLASS);
            m.put(p(4, y, 10), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        }
        return CompanionSchematics.normalize("melonenfarm", m);
    }

    /** Grasgehege mit Tor, beleuchtetem Unterstand und Vorratsfass; Tiere werden regulär hineingeführt. */
    private static CompanionSchematics.Schematic animalPen(String name) {
        var m = base(11, 5, 11);
        for (int x = 1; x <= 9; x++) for (int z = 1; z <= 9; z++) put(m, x, 0, z, Blocks.GRASS_BLOCK);
        for (int x = 1; x <= 9; x++) for (int z = 1; z <= 9; z++) {
            if (x != 1 && x != 9 && z != 1 && z != 9) continue;
            put(m, x, 1, z, Blocks.OAK_FENCE);
        }
        m.put(p(5, 1, 1), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH));
        for (int x : new int[]{1, 9}) for (int z : new int[]{6, 9})
            for (int y = 1; y <= 3; y++) put(m, x, y, z, Blocks.OAK_FENCE);
        for (int x = 1; x <= 9; x++) for (int z = 6; z <= 9; z++) put(m, x, 4, z, Blocks.OAK_PLANKS);
        put(m, 5, 4, 7, Blocks.GLOWSTONE);
        put(m, 0, 1, 0, Blocks.BARREL);
        if (name.equals("kuhfarm")) {
            // Zwei überdachte Futter-/Melkstände am hinteren Rand, vorn ein großer Auslauf.
            for (int x = 2; x <= 8; x++) put(m, x, 1, 6, Blocks.OAK_FENCE);
            for (int x : new int[]{3, 7}) {
                m.put(p(x, 1, 6), Blocks.OAK_FENCE_GATE.defaultBlockState());
                put(m, x, 1, 7, Blocks.HAY_BLOCK);
                put(m, x, 0, 7, Blocks.DIRT);
            }
            for (int z = 7; z <= 8; z++) put(m, 5, 1, z, Blocks.OAK_FENCE);
        } else if (name.equals("schaffarm")) {
            // Offene Grasweide: freie Regeneration des Rasens plus schmaler Scher-/Sortiergang.
            for (int x = 1; x <= 9; x++) for (int z = 6; z <= 9; z++) put(m, x, 4, z, Blocks.AIR);
            for (int x : new int[]{1, 9}) for (int z : new int[]{6, 9})
                for (int y = 2; y <= 3; y++) put(m, x, y, z, Blocks.AIR);
            for (int x : new int[]{3, 5}) for (int z = 6; z <= 8; z++) put(m, x, 1, z, Blocks.OAK_FENCE);
            for (int z : new int[]{6, 8}) m.put(p(4, 1, z), Blocks.OAK_FENCE_GATE.defaultBlockState());
            for (int x : new int[]{1, 9}) {
                for (int y = 2; y <= 3; y++) put(m, x, y, 1, Blocks.OAK_FENCE);
                put(m, x, 4, 1, Blocks.GLOWSTONE);
            }
        } else if (name.equals("schweinefarm")) {
            // Vier getrennte Zuchtbuchten mit Durchgangstoren, robuster Boden statt Grasweide.
            for (int x = 2; x <= 8; x++) for (int z = 2; z <= 8; z++)
                put(m, x, 0, z, (x + z) % 2 == 0 ? Blocks.COARSE_DIRT : Blocks.PODZOL);
            for (int n = 2; n <= 8; n++) {
                put(m, 5, 1, n, Blocks.OAK_FENCE);
                put(m, n, 1, 5, Blocks.OAK_FENCE);
            }
            for (int n : new int[]{3, 7}) {
                m.put(p(n, 1, 5), Blocks.OAK_FENCE_GATE.defaultBlockState());
                m.put(p(5, 1, n), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.EAST));
            }
            put(m, 5, 1, 1, Blocks.OAK_FENCE);
            m.put(p(3, 1, 1), Blocks.OAK_FENCE_GATE.defaultBlockState());
        }
        return CompanionSchematics.normalize(name, m);
    }

    /** Zwei geschlossene Schlafboxen und davor ein doppelt eingezäunter Auslauf. */
    private static CompanionSchematics.Schematic rabbitHutch() {
        var m = base(9, 5, 10);
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 8; z++) {
            put(m, x, 0, z, Blocks.GRASS_BLOCK);
            if (x == 1 || x == 7 || z == 1 || z == 8) for (int y = 1; y <= 2; y++)
                put(m, x, y, z, z >= 6 ? Blocks.OAK_PLANKS : Blocks.OAK_FENCE);
            if (z >= 6) put(m, x, 3, z, Blocks.OAK_PLANKS);
        }
        for (int z = 6; z <= 7; z++) for (int y = 1; y <= 2; y++) put(m, 4, y, z, Blocks.OAK_PLANKS);
        for (int x = 1; x <= 7; x++) for (int y = 1; y <= 2; y++) put(m, x, y, 6, Blocks.OAK_PLANKS);
        for (int x : new int[]{2, 6}) put(m, x, 1, 6, Blocks.AIR); // Kaninchen passen durch die niedrigen Öffnungen
        for (int y = 1; y <= 2; y++) m.put(p(4, y, 1), Blocks.OAK_FENCE_GATE.defaultBlockState());
        put(m, 2, 3, 7, Blocks.GLOWSTONE);
        put(m, 6, 3, 7, Blocks.GLOWSTONE);
        put(m, 0, 1, 0, Blocks.BARREL);
        // Unter geschlossenen Holzwänden stirbt Gras ab; diese Plätze von Anfang an als Erde planen.
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 8; z++)
            if (m.get(p(x, 1, z)).is(Blocks.OAK_PLANKS)) put(m, x, 0, z, Blocks.DIRT);
        return CompanionSchematics.normalize("kaninchenfarm", m);
    }

    /** Geschlossener Stall: Eier fallen durch Teppiche in die Trichter, alle Bodenplätze führen zur Kiste. */
    private static CompanionSchematics.Schematic chickenCoop() {
        var m = base(7, 6, 9);
        for (int x = 0; x <= 6; x++) for (int z = 1; z <= 7; z++) {
            put(m, x, 1, z, Blocks.COBBLESTONE);
            if (x == 0 || x == 6 || z == 1 || z == 7) {
                put(m, x, 2, z, Blocks.GLASS);
                put(m, x, 3, z, Blocks.GLASS);
                put(m, x, 4, z, Blocks.GLASS);
            }
            put(m, x, 5, z, Blocks.OAK_SLAB);
        }
        for (int x = 1; x <= 5; x++) for (int z = 2; z <= 6; z++) {
            hopper(m, x, 1, z, x == 1 ? Direction.NORTH : Direction.WEST);
            put(m, x, 2, z, Blocks.CARPET.white());
        }
        hopper(m, 1, 1, 1, Direction.NORTH);
        put(m, 1, 1, 0, Blocks.CHEST);
        m.put(p(3, 2, 1), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH));
        put(m, 3, 3, 1, Blocks.AIR);
        put(m, 3, 5, 4, Blocks.GLOWSTONE);
        m.put(p(3, 1, 0), Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        return CompanionSchematics.normalize("huehnerfarm", m);
    }

    /** Vier Pflanzen, Beobachter auf der dritten Wachstumshöhe, Kolben ernten nur oberhalb des Grundstocks. */
    private static CompanionSchematics.Schematic stalkFarm(boolean bamboo) {
        var m = base(7, 6, 8);
        for (int x = 1; x <= 6; x++) for (int y = 1; y <= 4; y++) put(m, x, y, 1, Blocks.GLASS);
        for (int x : new int[]{1, 6}) for (int z = 2; z <= 5; z++)
            for (int y = 1; y <= 4; y++) put(m, x, y, z, Blocks.GLASS);
        for (int x = 1; x <= 6; x++) for (int z = 1; z <= 5; z++) put(m, x, 5, z, Blocks.GLASS);
        put(m, 3, 5, 3, Blocks.GLOWSTONE);
        for (int x = 2; x <= 5; x++) {
            for (int y = 1; y <= 2; y++) for (int z = 4; z <= 5; z++) put(m, x, y, z, Blocks.COBBLESTONE);
            put(m, x, 1, 3, bamboo ? Blocks.DIRT : Blocks.SAND);
            put(m, x, 2, 3, bamboo ? Blocks.BAMBOO : Blocks.SUGAR_CANE);
            m.put(p(x, 3, 4), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.NORTH));
            m.put(p(x, 4, 4), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.NORTH));
            put(m, x, 3, 5, Blocks.COBBLESTONE);
            put(m, x, 4, 5, Blocks.REDSTONE_WIRE);
        }
        // Eine Quelle, Strömung nach links, Trichter im tiefsten Ende, Kiste außerhalb der Einhausung.
        put(m, 5, 1, 2, Blocks.WATER);
        hopper(m, 1, 0, 2, Direction.WEST);
        put(m, 1, 1, 2, Blocks.AIR); // Durchlass durch die linke Glaswand zur Sammelstelle
        m.put(p(0, 0, 2), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        put(m, 0, 1, 2, Blocks.GLASS);
        put(m, 0, 0, 1, Blocks.AIR); // Wartungsöffnung vor der abgesenkten Sammelkiste
        m.put(p(3, 1, 7), Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
        m.put(p(3, 2, 6), Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
        put(m, 3, 1, 6, Blocks.COBBLESTONE);
        return CompanionSchematics.normalize(bamboo ? "bambusfarm" : "zuckerrohrfarm", m);
    }

    /** 5×5 Weizen: Knopf schaltet die Wasserernte, zweiter Druck holt die Quelle zurück; anschließend neu säen. */
    private static CompanionSchematics.Schematic waterHarvest() {
        return waterHarvest(Blocks.WHEAT, "wasserernte");
    }

    private static CompanionSchematics.Schematic waterHarvest(Block crop, String name) {
        var m = base(9, 5, 11);
        for (int x = 1; x <= 7; x++) for (int z = 2; z <= 9; z++) put(m, x, 1, z, Blocks.COBBLESTONE);
        for (int x = 2; x <= 6; x++) for (int z = 3; z <= 7; z++) {
            m.put(p(x, 1, z), Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.MOISTURE, 7));
            put(m, x, 2, z, crop);
        }
        for (int x : new int[]{1, 7}) {
            put(m, x, 1, 5, Blocks.WATER); // dauerhafte Bewässerung, getrennt vom Erntewasser
            put(m, x == 1 ? 0 : 8, 1, 5, Blocks.COBBLESTONE);
            for (int z = 2; z <= 9; z++) put(m, x, 2, z, Blocks.GLASS);
        }
        for (int x = 1; x <= 7; x++) {
            put(m, x, 2, 9, Blocks.COBBLESTONE);
            put(m, x, 1, 1, Blocks.GLASS);
        }
        // Sammelrinne liegt eine Schicht tiefer als das Feld, damit das Wasser über die Kante fließt.
        for (int x = 2; x <= 6; x++) {
            put(m, x, 1, 2, Blocks.AIR);
            hopper(m, x, 0, 2, Direction.WEST);
        }
        m.put(p(1, 0, 2), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        put(m, 1, 1, 2, Blocks.GLASS);
        put(m, 1, 0, 1, Blocks.AIR);
        put(m, 1, 1, 1, Blocks.AIR);
        m.put(p(4, 2, 9), Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.NORTH));
        m.put(p(4, 2, 10), Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.WALL)
                .setValue(ButtonBlock.FACING, Direction.SOUTH));
        put(m, 4, 4, 5, Blocks.GLOWSTONE);
        return CompanionSchematics.normalize(name, m);
    }

    private static CompanionSchematics.Schematic smelter() {
        return smelter(Blocks.FURNACE, "schmelzofen");
    }

    private static CompanionSchematics.Schematic smelter(Block furnace, String name) {
        var m = base(7, 6, 6);
        m.put(p(4, 2, 3), furnace.defaultBlockState().setValue(FurnaceBlock.FACING, Direction.NORTH));
        hopper(m, 4, 1, 3, Direction.NORTH);
        put(m, 4, 1, 2, Blocks.CHEST); // fertige Produkte vorne
        hopper(m, 4, 3, 3, Direction.DOWN);
        put(m, 4, 4, 3, Blocks.CHEST); // Schmelzgut oben
        hopper(m, 3, 2, 3, Direction.EAST);
        put(m, 3, 3, 3, Blocks.CHEST); // Brennstoff links, oberhalb des seitlichen Trichters
        for (int x = 2; x <= 3; x++) put(m, x, 1, 3, Blocks.COBBLESTONE);
        m.put(p(4, 1, 1), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.SOUTH));
        put(m, 4, 1, 0, Blocks.REDSTONE_LAMP);
        return CompanionSchematics.normalize(name, m);
    }

    private static CompanionSchematics.Schematic cactusFarm() {
        var m = base(9, 5, 11);
        for (int x = 0; x <= 8; x++) for (int y = 1; y <= 2; y++) {
            put(m, x, y, 0, Blocks.GLASS);
            put(m, x, y, 9, Blocks.GLASS);
        }
        for (int z = 1; z <= 8; z++) for (int y = 1; y <= 2; y++) {
            put(m, 0, y, z, Blocks.GLASS);
            put(m, 8, y, z, Blocks.GLASS);
        }
        for (int x : new int[]{2, 4, 6}) for (int z : new int[]{2, 4, 6}) {
            put(m, x, 1, z, Blocks.SAND);
            put(m, x, 2, z, Blocks.CACTUS);
            put(m, x + 1, 3, z, Blocks.OAK_FENCE);
        }
        for (int x : new int[]{1, 4, 7}) put(m, x, 1, 8, Blocks.WATER);
        for (int x = 1; x <= 7; x++) hopper(m, x, 0, 1, Direction.WEST);
        put(m, 0, 0, 1, Blocks.CHEST);
        put(m, 0, 0, 0, Blocks.AIR);
        put(m, 0, 1, 0, Blocks.AIR);
        m.put(p(4, 1, 10), Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
        put(m, 4, 4, 5, Blocks.GLOWSTONE);
        return CompanionSchematics.normalize("kaktusfarm", m);
    }

    private static void outputIndicator(Map<BlockPos, BlockState> m) {
        m.put(p(5, 1, 3), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST));
        put(m, 6, 1, 3, Blocks.REDSTONE_LAMP);
    }

    private static CompanionSchematics.Schematic switchableStorage() {
        var m = base(7, 5, 6);
        put(m, 3, 2, 3, Blocks.CHEST);
        hopper(m, 3, 1, 3, Direction.EAST);
        put(m, 4, 1, 3, Blocks.CHEST);
        m.put(p(2, 1, 3), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.POWERED, true));
        outputIndicator(m);
        return CompanionSchematics.normalize("schaltlager", m);
    }

    private static CompanionSchematics.Schematic portioner() {
        var m = base(7, 5, 6);
        put(m, 3, 3, 3, Blocks.CHEST);
        hopper(m, 3, 2, 3, Direction.DOWN);
        m.put(p(3, 1, 3), Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        put(m, 4, 1, 3, Blocks.CHEST);
        m.put(p(2, 1, 3), Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.WALL)
                .setValue(ButtonBlock.FACING, Direction.WEST));
        outputIndicator(m);
        return CompanionSchematics.normalize("portionierer", m);
    }

    private static CompanionSchematics.Schematic composter() {
        var m = base(7, 6, 6);
        put(m, 3, 2, 3, Blocks.COMPOSTER);
        hopper(m, 3, 1, 3, Direction.EAST);
        put(m, 4, 1, 3, Blocks.CHEST); // Knochenmehl rechts
        hopper(m, 3, 3, 3, Direction.DOWN);
        put(m, 3, 4, 3, Blocks.CHEST); // kompostierbare Reste oben
        put(m, 2, 1, 3, Blocks.COBBLESTONE);
        m.put(p(2, 2, 3), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.EAST));
        put(m, 1, 2, 3, Blocks.REDSTONE_LAMP);
        return CompanionSchematics.normalize("komposter", m);
    }

    /**
     * Ein Filterplatz eines Hopper-Sortierers. Laufband oben (Eingabekiste darüber, Rest nach rechts), darunter der
     * Filtertrichter (zeigt ins Leere und hält). Mit 18 Zielitems + 4 Einzelstücken liest der Komparator Stärke 1, die
     * nach zwei Staub-Blöcken verlischt; ab dem 19. Zielitem kommt Stärke 2 an und gibt den unteren Trichter frei, der
     * genau eines in die Sortierkiste zieht. Umkehrung ohne Fackel (die brennt bei einem Item alle 8 Ticks durch):
     * Verstärker auf die Seite eines Subtraktions-Komparators, hinten ein Redstoneblock.
     */
    private static CompanionSchematics.Schematic itemSorter() {
        var m = base(7, 6, 8);
        put(m, 3, 4, 3, Blocks.CHEST);                // Eingabe
        hopper(m, 3, 3, 3, Direction.EAST);           // Laufband
        put(m, 4, 3, 3, Blocks.CHEST);                // Rest
        hopper(m, 3, 2, 3, Direction.SOUTH);          // Filter (zeigt auf keinen Behälter)
        hopper(m, 3, 1, 3, Direction.NORTH);          // gesperrter Auslass
        put(m, 3, 1, 2, Blocks.CHEST);                // Sortiertes
        put(m, 2, 1, 3, Blocks.COBBLESTONE);
        // FACING = Eingangsseite: liest den Filter östlich, gibt nach Westen aus
        m.put(p(2, 2, 3), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.EAST));
        for (int z = 3; z <= 4; z++) {
            put(m, 1, 1, z, Blocks.COBBLESTONE);
            put(m, 1, 2, z, Blocks.REDSTONE_WIRE);
        }
        for (int z = 5; z <= 6; z++) put(m, 1, 1, z, Blocks.COBBLESTONE);
        m.put(p(1, 2, 5), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.NORTH));
        m.put(p(1, 2, 6), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST)
                .setValue(ComparatorBlock.MODE, net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT));
        put(m, 0, 2, 6, Blocks.REDSTONE_BLOCK);
        // 15 − Verstärker: ohne Filtersignal Strom, der den Auslass sperrt
        for (int x = 2; x <= 3; x++) {
            put(m, x, 1, 6, Blocks.COBBLESTONE);
            put(m, x, 2, 6, Blocks.REDSTONE_WIRE);
        }
        put(m, 3, 1, 5, Blocks.REDSTONE_WIRE);
        put(m, 3, 1, 4, Blocks.REDSTONE_WIRE);
        return CompanionSchematics.normalize("itemsortierer", m);
    }

    /**
     * Lava fließt in der Rinne auf Wasser und wird dort zu Bruchstein. Das Wasser stürzt in ein Loch, damit es die
     * Lavaquelle nie erreicht (sonst Obsidian); abgebauter Bruchstein bildet sich sofort neu.
     */
    private static CompanionSchematics.Schematic cobbleGenerator() {
        var m = base(7, 5, 5);
        for (int x = 0; x < 7; x++) for (int z = 0; z < 5; z++) {
            put(m, x, 1, z, Blocks.COBBLESTONE);
            if (z != 2 || x == 0 || x == 6) put(m, x, 2, z, Blocks.COBBLESTONE);
        }
        put(m, 1, 2, 2, Blocks.LAVA);
        put(m, 1, 3, 2, Blocks.GLASS);                // Deckel über der Lavaquelle
        put(m, 4, 1, 2, Blocks.AIR);                  // Wasserloch
        put(m, 5, 2, 2, Blocks.WATER);
        return CompanionSchematics.normalize("bruchsteingenerator", m);
    }

    private static BlockState floorLever() {
        return Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR);
    }

    /**
     * Flur-Wechselschaltung: je ein Knopf an beiden Enden, beide Leitungen auf dieselbe Kupferbirne (wechselt bei jeder
     * steigenden Flanke, ein T-Flipflop). Ein Komparator liest die Birne und schaltet zwei Flurlampen mit. (Zwei Dropper,
     * die ineinander zeigen, gehen nicht: beide feuern im selben Tick nacheinander und schieben das Item zurück.)
     */
    private static CompanionSchematics.Schematic toggleLight() {
        var m = base(9, 4, 6);
        for (int x : new int[]{0, 8}) m.put(p(x, 1, 2), Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR));
        for (int x : new int[]{1, 2, 3, 5, 6, 7}) put(m, x, 1, 2, Blocks.REDSTONE_WIRE);
        put(m, 4, 1, 2, Blocks.COPPER_BULB.waxed().unaffected()); // gewachst: oxidiert nicht
        m.put(p(4, 1, 3), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.NORTH));
        for (int x = 2; x <= 6; x++) put(m, x, 1, 4, Blocks.REDSTONE_WIRE);
        put(m, 1, 1, 4, Blocks.REDSTONE_LAMP);
        put(m, 7, 1, 4, Blocks.REDSTONE_LAMP);
        return CompanionSchematics.normalize("lichtschalter", m);
    }

    /**
     * Drei Hebel, die Eisentür öffnet nur bei 1 an, 2 aus, 3 an: Hebel 1 und 3 werden über Fackeln umgekehrt, Hebel 2
     * geht direkt auf dieselbe ODER-Leitung; die Fackel dahinter (NICHT-ODER) ist nur an, wenn alle drei Eingänge aus
     * sind.
     */
    private static CompanionSchematics.Schematic combinationLock() {
        var m = base(11, 5, 7);
        for (int z : new int[]{1, 5}) {
            m.put(p(1, 1, z), floorLever());
            put(m, 2, 1, z, Blocks.REDSTONE_WIRE);
            put(m, 3, 1, z, Blocks.COBBLESTONE);
            m.put(p(4, 1, z), Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(RedstoneWallTorchBlock.FACING, Direction.EAST));
        }
        m.put(p(1, 1, 3), floorLever());
        for (int x = 2; x <= 4; x++) put(m, x, 1, 3, Blocks.REDSTONE_WIRE);
        for (int z = 1; z <= 5; z++) put(m, 5, 1, z, Blocks.REDSTONE_WIRE);   // ODER-Leitung
        put(m, 6, 1, 3, Blocks.REDSTONE_WIRE);
        put(m, 7, 1, 3, Blocks.COBBLESTONE);
        m.put(p(8, 1, 3), Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(RedstoneWallTorchBlock.FACING, Direction.EAST));
        // Mauer mit Eisentür
        for (int z = 0; z < 7; z++) for (int y = 1; y <= 3; y++)
            if (z != 3 || y == 3) put(m, 9, y, z, Blocks.STONE_BRICKS);
        BlockState door = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        m.put(p(9, 1, 3), door.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
        m.put(p(9, 2, 3), door.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        return CompanionSchematics.normalize("tresorschloss", m);
    }
}
