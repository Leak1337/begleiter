package dev.begleiter;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity.Task;

/**
 * Szenarien auf dem Testgelände ({@link TestMap}): Station neu aufbauen, Begleiter mit festem Inventar hinsetzen,
 * Auftrag geben, Ergebnis prüfen. Ergebnis in den Chat, ins Log ("[Testmap]") und angehängt an
 * {@value #RESULT_FILE} im Weltordner – so lässt sich ein Lauf auch ohne Zuschauen auswerten.
 * /companion testmap liste | szenario &lt;name&gt; | alle | stop
 */
public final class TestScenarios {
    private TestScenarios() {}

    private static final Logger LOG = LogUtils.getLogger();
    static final String RESULT_FILE = "begleiter-testmap-ergebnisse.txt";

    /** Laufender Zustand eines Szenarios. c = Ecke der Station, target = Ziel (absolut) bei Wege-Szenarien. */
    static final class Ctx {
        ServerLevel sl;
        ServerPlayer p;
        BlockPos o, c, target;
        CompanionEntity bot;
        int ticks;
        float startHealth, minHealth;
        boolean flag;
        /** Für mehrstufige Prüfungen (z.B. Schalter umlegen und Lampe prüfen): Stufe und Tick des letzten Wechsels. */
        int phase, mark;
        /** Eine BUILD-Aufgabe kann im nächsten 20-Tick-Arbeitstakt erneut eingereiht werden. */
        int buildIdleSince = -1;
        /** Soll-Zustände (Schematic-Szenarien, absolute Positionen). */
        java.util.Map<BlockPos, BlockState> expected;
        /** Läuft jeden Tick (die Prüfung nur alle 10): z.B. Lampenwechsel eines Taktgebers zählen. */
        java.util.function.Consumer<Ctx> sampler;
        int counter;
        boolean last;
        final List<Mob> mobs = new ArrayList<>();
        BlockPos at(int x, int y, int z) { return c.offset(x, y, z); }
    }

    @FunctionalInterface interface Setup { void run(Ctx x); }
    /** null = läuft noch, sonst Ergebnis ("OK …" oder Fehlergrund). */
    @FunctionalInterface interface Check { Result run(Ctx x); }

    record Result(boolean ok, String why) {}
    static Result ok(String why) { return new Result(true, why); }
    static Result fail(String why) { return new Result(false, why); }

    /** timeoutOk: wer bis zum Ende durchhält, hat bestanden (Überleben). */
    record Scenario(String id, String station, String desc, int seconds, Setup setup, Check check, boolean timeoutOk) {}

    /**
     * Startinventar als Paare (Item, Anzahl). ItemStacks erst beim Szenario-Start bauen: beim Laden der Klasse
     * (Mod-Start) sind die Item-Komponenten noch nicht gebunden.
     */
    record Kit(Object... a) {
        ItemStack[] stacks() {
            ItemStack[] out = new ItemStack[a.length / 2];
            for (int i = 0; i < out.length; i++) out[i] = new ItemStack((net.minecraft.world.item.Item) a[2 * i], (Integer) a[2 * i + 1]);
            return out;
        }
    }

    static Kit kit(Object... a) { return new Kit(a); }
    private static final Kit NONE = new Kit();

    /** Hinlaufen: von start nach goal (lokal in der Station), bestanden wenn er dort steht. */
    private static Scenario go(String id, String station, String desc, int seconds, int[] start, int[] goal) {
        return go(id, station, desc, seconds, start, goal, NONE);
    }

    private static Scenario go(String id, String station, String desc, int seconds, int[] start, int[] goal, Kit items) {
        return new Scenario(id, station, desc, seconds, x -> {
            place(x, start, items);
            x.target = x.at(goal[0], goal[1], goal[2]);
            x.bot.addTask(Task.moveTo(x.target, 2));
        }, TestScenarios::reached, false);
    }

    /** Angekommen = steht (nicht mitten im Sprung) auf der Zielhöhe, höchstens ein Block daneben. */
    private static Result reached(Ctx x) {
        BlockPos b = x.bot.blockPosition();
        int dx = b.getX() - x.target.getX(), dz = b.getZ() - x.target.getZ();
        if (x.bot.onGround() && b.getY() == x.target.getY() && dx * dx + dz * dz <= 2) return ok("angekommen");
        if (!x.bot.hasTasks() && x.ticks > 40) return fail("Aufgabe beendet, Ziel nicht erreicht");
        return null;
    }

    /** Auftrag: Inventar setzen, Aufgabe geben, fertig wenn die Bedingung stimmt. */
    private static Scenario job(String id, String station, String desc, int seconds, int[] start, java.util.function.Function<Ctx, Task> task,
                                java.util.function.Predicate<Ctx> done) {
        return job(id, station, desc, seconds, start, task, done, NONE);
    }

    private static Scenario job(String id, String station, String desc, int seconds, int[] start, java.util.function.Function<Ctx, Task> task,
                                java.util.function.Predicate<Ctx> done, Kit items) {
        return new Scenario(id, station, desc, seconds, x -> {
            place(x, start, items);
            x.bot.addTask(task.apply(x));
        }, x -> {
            if (done.test(x)) return ok("erledigt");
            if (!x.bot.hasTasks() && x.ticks > 40) return fail("Aufgabe beendet, Ziel nicht erfüllt");
            return null;
        }, false);
    }

    /** Kampf in der Arena: Mobs hinein, bestanden wenn alle tot sind und er noch mindestens minHp hat. */
    private static Scenario fight(String id, String desc, float minHp, List<EntityType<? extends Mob>> types, Kit items) {
        return new Scenario(id, "arena", desc, 90, x -> {
            place(x, new int[]{6, 0, 6}, items);
            int[][] spots = {{2, 0, 2}, {9, 0, 9}, {9, 0, 2}, {2, 0, 9}};
            for (int i = 0; i < types.size(); i++) {
                Mob m = types.get(i).spawn(x.sl, x.at(spots[i][0], spots[i][1], spots[i][2]), EntitySpawnReason.COMMAND);
                if (m != null) { m.setPersistenceRequired(); x.mobs.add(m); }
            }
        }, x -> {
            if (x.mobs.stream().anyMatch(Mob::isAlive)) return null;
            return x.bot.getHealth() >= minHp ? ok("alle besiegt") : fail("gewonnen, aber nur " + (int) x.bot.getHealth() + " Leben");
        }, false);
    }

    private static int[] p(int x, int y, int z) { return new int[]{x, y, z}; }

    // ------------------------------------------------------------------ Redstone

    private static final Kit REDSTONE_KIT = kit(Items.LEVER, 3, Items.REDSTONE, 32, Items.REPEATER, 2, Items.REDSTONE_LAMP, 2,
            Items.STICKY_PISTON, 2, Items.STONE, 32, Items.REDSTONE_TORCH, 2, Items.HOPPER, 2, Items.CHEST, 2,
            Items.COMPARATOR, 1, Items.DISPENSER, 1, Items.STONE_PRESSURE_PLATE, 1, Items.OBSERVER, 1,
            Items.DAYLIGHT_DETECTOR, 1);
    /** Für Gatter und Verzögerung: mehr Verstärker und Fackeln. */
    private static final Kit LOGIC_KIT = kit(Items.LEVER, 3, Items.REDSTONE, 32, Items.REPEATER, 6, Items.REDSTONE_LAMP, 2,
            Items.STONE, 16, Items.REDSTONE_TORCH, 4);

    static BlockState wallTorch(net.minecraft.core.Direction facing) {
        return Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RedstoneWallTorchBlock.FACING, facing);
    }

    static BlockState repeater(net.minecraft.core.Direction facing, int delay) {
        return Blocks.REPEATER.defaultBlockState().setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, facing)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, delay);
    }

    static BlockState wire() { return Blocks.REDSTONE_WIRE.defaultBlockState(); }

    static void flip(Ctx x, BlockPos lever, boolean on) {
        x.sl.setBlock(lever, x.sl.getBlockState(lever).setValue(net.minecraft.world.level.block.LeverBlock.POWERED, on), 3);
        x.mark = x.ticks;
    }

    private static boolean isBlock(Ctx x, int lx, int ly, int lz, net.minecraft.world.level.block.Block b) {
        return x.sl.getBlockState(x.at(lx, ly, lz)).is(b);
    }

    static net.minecraft.world.Container container(Ctx x, int lx, int ly, int lz) {
        return x.sl.getBlockEntity(x.at(lx, ly, lz)) instanceof net.minecraft.world.Container ct ? ct : null;
    }

    static int count(net.minecraft.world.Container ct, net.minecraft.world.item.Item item) {
        int n = 0;
        if (ct != null) for (int i = 0; i < ct.getContainerSize(); i++) if (ct.getItem(i).is(item)) n += ct.getItem(i).getCount();
        return n;
    }

    static BlockState lever(boolean on) {
        return Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                .setValue(net.minecraft.world.level.block.LeverBlock.POWERED, on);
    }

    static boolean lit(Ctx x, int lx, int lz) {
        BlockState s = x.sl.getBlockState(x.at(lx, 0, lz));
        return s.is(Blocks.REDSTONE_LAMP) && s.getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT);
    }

    /**
     * Schaltung auf der Redstone-Platte bauen lassen (Überleben, Material aus dem Kit), dann prüfen.
     * plan: lokale Position (x, z auf Bodenhöhe) -> Zustand. done läuft erst, wenn der Bau fertig ist.
     */
    private static Scenario circuit(String id, String desc, java.util.function.Consumer<java.util.Map<BlockPos, BlockState>> plan, Check done) {
        return circuit(id, desc, REDSTONE_KIT, plan, done);
    }

    static Scenario circuit(String id, String desc, Kit kit, java.util.function.Consumer<java.util.Map<BlockPos, BlockState>> plan, Check done) {
        return new Scenario(id, "redstone", desc, 150, x -> {
            place(x, p(6, 0, 9), kit);
            java.util.Map<BlockPos, BlockState> rel = new java.util.LinkedHashMap<>();
            plan.accept(rel);
            java.util.Map<BlockPos, BlockState> abs = new java.util.LinkedHashMap<>();
            rel.forEach((pos, st) -> abs.put(x.c.offset(pos), st));
            x.bot.build = CompanionBuilder.create("Testmap " + id, abs);
            x.bot.addTask(Task.build());
        }, x -> {
            if (x.bot.build != null) return pendingBuild(x);
            // Nach dem Bau 1 s warten: Kolben fahren aus, Fackeln schalten, Lampen gehen an
            if (x.phase == 0 && x.mark == 0) { x.mark = x.ticks; return null; }
            if (x.phase == 0 && x.ticks - x.mark < 20) return null;
            return done.run(x);
        }, false);
    }

    // ------------------------------------------------------------------ Schematics

    @FunctionalInterface interface SchematicWriter { java.nio.file.Path write(String name, java.util.Map<BlockPos, BlockState> m) throws java.io.IOException; }

    /**
     * Schematic als Datei schreiben, wie ein Spieler-Schematic laden und vor (6,0,1) nach Süden platzieren; Material
     * genau nach Plan (+2 je Sorte) ins Kit. Bestanden, wenn danach jeder Block samt Ausrichtung stimmt (und extra).
     */
    private static Scenario schematic(String id, String desc, java.util.function.Supplier<java.util.Map<BlockPos, BlockState>> design,
                                      SchematicWriter writer, net.minecraft.world.level.block.Rotation rotation, Check extra) {
        // große Bauten brauchen länger: ab 120 Blöcken 2 s je Block
        return new Scenario(id, "schematic", desc, Math.max(240, design.get().size() * 2), x -> {
            String name = "testmap_" + id;
            CompanionSchematics.Schematic s;
            try {
                writer.write(name, design.get());
                java.nio.file.Path f = CompanionSchematics.find(name);
                if (f == null) throw new IllegalStateException("Schematic " + name + " nicht gefunden");
                s = CompanionSchematics.load(x.sl, f);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Schematic schreiben/lesen: " + e.getMessage(), e);
            }
            x.expected = CompanionSchematics.place(s, x.at(6, 0, 1), net.minecraft.core.Direction.SOUTH, rotation, false);
            java.util.Map<net.minecraft.world.item.Item, Integer> need = new java.util.LinkedHashMap<>();
            for (BlockState st : x.expected.values()) {
                var item = CompanionBuilder.itemFor(st);
                if (item != null) need.merge(item, CompanionBuilder.itemCount(st), Integer::sum);
            }
            // Gerüst zum Hochbauen (Erde: baut er ohne Werkzeug wieder ab), wie bei den Farm-Bauplänen
            need.merge(Items.DIRT, 32, Integer::sum);
            List<Object> pairs = new ArrayList<>();
            need.forEach((item, n) -> {
                for (int left = n + 2; left > 0; left -= item.getDefaultMaxStackSize()) {
                    pairs.add(item); pairs.add(Math.min(left, item.getDefaultMaxStackSize()));
                }
            });
            place(x, p(10, 0, 11), new Kit(pairs.toArray()));
            x.bot.build = CompanionBuilder.create(x.sl, s.name(), x.expected);
            x.bot.addTask(Task.build());
        }, x -> {
            if (x.expected.isEmpty()) return fail("Schematic ohne Blöcke geladen (Palette nicht gelesen?)");
            if (x.bot.build != null) return pendingBuild(x);
            if (x.mark == 0) { x.mark = x.ticks; return null; }
            if (x.ticks - x.mark < 20) return null;
            int wrong = 0;
            String first = "";
            for (var en : x.expected.entrySet()) {
                BlockState world = x.sl.getBlockState(en.getKey());
                if (CompanionBuilder.matches(world, en.getValue())) continue;
                if (wrong++ == 0) first = " – erster bei " + en.getKey().subtract(x.c).toShortString() + ": ist "
                        + BlockStateParser.serialize(world) + ", soll " + BlockStateParser.serialize(en.getValue());
            }
            if (wrong > 0) return fail(wrong + " von " + x.expected.size() + " Blöcken falsch" + first);
            return extra == null ? ok("alle " + x.expected.size() + " Blöcke stimmen") : extra.run(x);
        }, false);
    }

    /** Allow the entity's normal 20-tick BUILD retry, but reject a persistently empty queue. */
    static Result pendingBuild(Ctx x) {
        if (x.bot.isPaused()) return fail("Bau pausiert: " + x.bot.problemText);
        if (x.bot.hasTasks()) x.buildIdleSince = -1;
        else {
            if (x.buildIdleSince < 0) x.buildIdleSince = x.ticks;
            if (x.ticks - x.buildIdleSince >= 40)
                return fail("Bau hat keinen Auftrag mehr " + buildState(x));
        }
        return null;
    }

    /** Wo ein Bau steht: Fortschritt, nächster Block (lokal), Stelle des Bots. */
    static String buildState(Ctx x) {
        var job = x.bot.build;
        if (job == null) return "";
        String next = job.index < job.order.size() ? job.order.get(job.index).pos().subtract(x.c).toShortString() + " "
                + BlockStateParser.serialize(job.order.get(job.index).state()) : "-";
        return "[" + job.index + "/" + job.total() + ", nächster " + next + ", Bot bei "
                + x.bot.blockPosition().subtract(x.c).toShortString() + "]";
    }

    // ------------------------------------------------------------------ MLG

    /** Vom 28 hohen Turm hinunter, mit nur 20 Leben (ein ungebremster Sturz wäre tödlich). */
    private static Scenario mlg(String id, String desc, int[] goal, float maxDamage, int seconds, Kit kit) {
        return new Scenario(id, "mlg", desc, seconds, x -> {
            place(x, p(5, TestMap.MLG_TOP, 5), kit);
            x.bot.setHealth(20);
            x.target = x.at(goal[0], goal[1], goal[2]);
            x.bot.addTask(Task.moveTo(x.target, 2));
        }, x -> {
            if (x.startHealth - x.minHealth > maxDamage) return fail("Schaden " + (int) (x.startHealth - x.minHealth));
            Result r = reached(x);
            if (r == null || !r.ok()) return r;
            boolean water = x.bot.findItem(Items.WATER_BUCKET) >= 0;
            return ok("unten, Schaden " + (int) (x.startHealth - x.minHealth)
                    + (kit == NONE ? "" : ", Wassereimer " + (water ? "wieder voll" : "leer/weg")));
        }, false);
    }

    /** Hebel bei (1,5) einmal umlegen (Testlauf "schalten"). */
    private static void flip(Ctx x, boolean on) {
        BlockPos lever = x.at(1, 0, 5);
        x.sl.setBlock(lever, x.sl.getBlockState(lever).setValue(net.minecraft.world.level.block.LeverBlock.POWERED, on), 3);
        x.mark = x.ticks;
    }

    private static final List<Scenario> BASE = List.of(
            // --- Wege
            go("schwimmen", "wasser", "durchs oder ums Becken", 60, p(5, 0, 1), p(5, 0, 10)),
            go("lava", "lava", "über den Lavagraben – ohne Lava", 60, p(5, 0, 1), p(5, 0, 10)),
            go("treppe", "klettern", "Treppe hoch auf die Plattform", 45, p(0, 0, 5), p(9, 6, 1)),
            go("grube", "klettern", "aus der Grube (Leiter)", 45, p(7, -4, 7), p(4, 0, 10)),
            go("hindernisse", "hindernisse", "Seelensand, Netze, Tür, Tunnel", 90, p(5, 0, 0), p(5, 0, 11)),
            go("sprung1", "spruenge", "Lücke 1", 30, p(5, 0, 0), p(5, 0, 3)),
            go("sprung2", "spruenge", "Lücke 2", 30, p(5, 0, 4), p(5, 0, 7)),
            go("sprung3", "spruenge", "Lücke 3 (Sprint)", 30, p(5, 0, 7), p(5, 0, 11)),
            go("schlucht", "schlucht", "über die Schlucht", 90, p(5, 0, 1), p(5, 0, 10), kit(Items.COBBLESTONE, 32)),
            go("labyrinth", "labyrinth", "durchs Labyrinth", 90, p(1, 0, 1), p(1, 0, 10)),
            go("turm", "turm", "auf den Obsidian-Turm", 90, p(1, 0, 1), p(5, 8, 5), kit(Items.DIRT, 32)),
            go("tunnel", "tunnel", "durch den dunklen Tunnel", 60, p(-1, 0, 2), p(11, 0, 9), kit(Items.TORCH, 16)),
            go("wasserfall", "wasserfall", "auf die Klippe am Wasserfall", 120, p(10, 0, 5), p(2, 8, 5), kit(Items.DIRT, 32)),
            go("quer", "start", "quer übers Gelände zur Freifläche", 180, p(6, 0, 6), p(6 + 2 * TestMap.STEP, 0, 6 + 2 * TestMap.STEP)),
            new Scenario("klippe", "klippe", "10 hoch runter, ohne viel Schaden", 60, x -> {
                place(x, p(2, 10, 5), kit(Items.DIRT, 16));
                x.target = x.at(10, 0, 5);
                x.bot.addTask(Task.moveTo(x.target, 2));
            }, x -> {
                if (x.startHealth - x.minHealth > 6) return fail("Fallschaden " + (int) (x.startHealth - x.minHealth));
                return reached(x);
            }, false),
            // --- Aufträge
            job("holz", "wald", "8 Holz sammeln", 120, p(6, 0, 11), x -> Task.gather("holz", 8),
                    x -> CompanionCraft.count(x.bot, "#logs") >= 8),
            job("hoherbaum", "wald", "den 10 hohen Baum fällen", 150, p(6, 0, 11), x -> Task.tree(TestMap.tallTree(x.o), 10),
                    x -> CompanionCraft.count(x.bot, "#logs") >= 8, kit(Items.DIRT, 32)),
            job("eisen", "erze", "3 Eisenerz abbauen", 120, p(6, 0, 0), x -> Task.gather("eisen", 3),
                    x -> CompanionCraft.count(x.bot, "raw_iron") >= 3, kit(Items.STONE_PICKAXE, 1)),
            job("kohle", "erze", "3 Kohle abbauen", 90, p(6, 0, 0), x -> Task.gather("kohle", 3),
                    x -> CompanionCraft.count(x.bot, "coal") >= 3, kit(Items.WOODEN_PICKAXE, 1)),
            job("sand", "sand", "8 Sand graben", 90, p(0, 0, 0), x -> Task.gather("sand", 8),
                    x -> CompanionCraft.count(x.bot, "sand") >= 8, kit(Items.WOODEN_SHOVEL, 1)),
            job("jagen", "tiere", "Fleisch jagen", 90, p(6, 0, 6), x -> Task.hunt(3),
                    x -> CompanionCraft.rawFood(x.bot) >= 2, kit(Items.IRON_SWORD, 1)),
            job("farm", "farm", "Weizen ernten", 150, p(0, 0, 0), x -> Task.farm(TestMap.farmCenter(x.o)),
                    x -> CompanionCraft.count(x.bot, "wheat") >= 5),
            job("craften", "werkstatt", "Holzspitzhacke herstellen", 60, p(5, 0, 6), x -> Task.craft("wooden_pickaxe", 1),
                    // die fertige Spitzhacke nimmt er gleich in die Hand
                    x -> CompanionCraft.count(x.bot, "wooden_pickaxe") + (x.bot.getMainHandItem().is(Items.WOODEN_PICKAXE) ? 1 : 0) >= 1,
                    kit(Items.OAK_LOG, 3)),
            job("schmelzen", "werkstatt", "3 Eisen schmelzen", 120, p(5, 0, 6), x -> Task.smelt("iron_ingot", 3),
                    x -> CompanionCraft.count(x.bot, "iron_ingot") >= 3, kit(Items.RAW_IRON, 3, Items.COAL, 3)),
            new Scenario("sortieren", "lager", "Kiste sortieren", 90, x -> {
                place(x, p(5, 0, 8), NONE);
                x.bot.addTask(Task.sort(TestMap.storageChest(x.o)));
            }, x -> !x.bot.hasTasks() && x.ticks > 40
                    ? (x.bot.problemText.isEmpty() ? ok("fertig") : fail("Problem: " + x.bot.problemText)) : null, false),
            job("haus", "hausplatz", "Haus bauen", 600, p(0, 0, 0), x -> {
                        // Ein schon gebautes Haus vergessen, sonst ist das Szenario sofort "erledigt"
                        x.bot.forgetHouse();
                        return Task.house(TestMap.houseSite(x.o));
                    // isHouseBuilt gilt schon, sobald der Bau eingeplant ist: fertig erst, wenn alles gesetzt ist
                    }, x -> x.bot.isHouseBuilt() && !x.bot.hasTasks() && !x.bot.houseNeedsDoor(),
                    kit(Items.OAK_LOG, 64, Items.COBBLESTONE, 64)),
            // --- Kampf und Überleben
            fight("zombies", "3 Zombies", 10, List.of(EntityTypes.ZOMBIE, EntityTypes.ZOMBIE, EntityTypes.ZOMBIE),
                    kit(Items.IRON_SWORD, 1, Items.IRON_CHESTPLATE, 1, Items.BREAD, 8)),
            fight("skelette", "2 Skelette", 10, List.of(EntityTypes.SKELETON, EntityTypes.SKELETON),
                    kit(Items.IRON_SWORD, 1, Items.SHIELD, 1, Items.BREAD, 8)),
            fight("creeper", "Creeper mit Bogen", 20, List.of(EntityTypes.CREEPER),
                    kit(Items.BOW, 1, Items.ARROW, 32, Items.IRON_SWORD, 1)),
            // --- Neue Stationen
            go("abgrund", "abgrund", "über den 7 breiten Abgrund", 120, p(5, 0, 1), p(5, 0, 11), kit(Items.COBBLESTONE, 64)),
            go("eis", "eis", "übers Eis, am Pulverschnee vorbei", 60, p(5, 0, 0), p(5, 0, 11)),
            go("fluss", "fluss", "quer durch die Strömung", 60, p(5, 0, 1), p(5, 0, 10)),
            go("dunkelwald", "dunkelwald", "unter dem Laubdach durch", 60, p(5, 0, 0), p(5, 0, 11)),
            go("schacht", "schacht", "aus dem Schacht (Leiter)", 60, p(6, -12, 6), p(6, 0, 8)),
            new Scenario("kaktus", "kaktus", "durchs Kakteenfeld ohne Schaden", 60, x -> {
                place(x, p(5, 0, 0), NONE);
                x.target = x.at(5, 0, 11);
                x.bot.addTask(Task.moveTo(x.target, 2));
            }, x -> {
                if (x.startHealth - x.minHealth > 2) return fail("Schaden " + (int) (x.startHealth - x.minHealth));
                return reached(x);
            }, false),
            job("tuer", "tuerhaus", "Tür in die Hütte setzen", 60, p(5, 0, 0), x -> Task.door(TestMap.hutDoor(x.o), "north"),
                    x -> CompanionDoors.isDoor(x.sl, TestMap.hutDoor(x.o)), kit(Items.OAK_DOOR, 1)),
            job("tuerbauen", "tuerhaus", "Tür herstellen und einsetzen", 90, p(5, 0, 0), x -> Task.door(TestMap.hutDoor(x.o), "north"),
                    x -> CompanionDoors.isDoor(x.sl, TestMap.hutDoor(x.o)), kit(Items.OAK_LOG, 4)),
            job("diamant", "tiefmine", "Diamant 12 tief abbauen", 180, p(6, 0, 3), x -> Task.gather("diamant", 1),
                    x -> CompanionCraft.count(x.bot, "diamond") >= 1, kit(Items.IRON_PICKAXE, 1, Items.COBBLESTONE, 32, Items.TORCH, 16)),
            job("wasserholen", "eimer", "Eimer mit Wasser füllen", 60, p(6, 0, 0), x -> Task.water(TestMap.waterSource(x.o)),
                    x -> x.bot.findItem(Items.WATER_BUCKET) >= 0, kit(Items.BUCKET, 1)),
            job("loeschen", "eimer", "Lava mit Wasser löschen", 60, p(6, 0, 0), x -> Task.pour(TestMap.lavaSource(x.o)),
                    x -> !x.sl.getFluidState(TestMap.lavaSource(x.o)).is(net.minecraft.tags.FluidTags.LAVA), kit(Items.WATER_BUCKET, 1)),
            job("holen", "kiste", "5 Eisen aus der Kiste holen", 60, p(1, 0, 1), x -> Task.take(TestMap.supplyChest(x.o), "iron_ingot", 5),
                    x -> CompanionCraft.count(x.bot, "iron_ingot") >= 5),
            // Nach dem Füttern dauert es ein paar Sekunden bis zum Kalb: nicht beim Auftragsende abbrechen
            new Scenario("zucht", "zucht", "Kühe vermehren", 60, x -> {
                place(x, p(5, 0, 5), kit(Items.WHEAT, 8));
                x.bot.addTask(Task.breed("cow", 1));
            }, x -> x.sl.getEntitiesOfClass(net.minecraft.world.entity.AgeableMob.class, new net.minecraft.world.phys.AABB(x.c)
                    .inflate(TestMap.CELL), m -> m.getType() == EntityTypes.COW && m.isBaby()).isEmpty() ? null : ok("Kalb geboren"), false),
            // --- Redstone (Schaltungen in Reihe z 5, Hebel immer bei x 1)
            circuit("rs_lampe", "Hebel, Staub, Lampe bauen – Lampe leuchtet", r -> {
                r.put(new BlockPos(1, 0, 5), lever(true));
                r.put(new BlockPos(2, 0, 5), Blocks.REDSTONE_WIRE.defaultBlockState());
                r.put(new BlockPos(3, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> lit(x, 3, 5) ? ok("Lampe an") : fail("Lampe aus")),
            circuit("rs_verstaerker", "Schaltung mit Verstärker", r -> {
                r.put(new BlockPos(1, 0, 5), lever(true));
                r.put(new BlockPos(2, 0, 5), Blocks.REDSTONE_WIRE.defaultBlockState());
                r.put(new BlockPos(3, 0, 5), Blocks.REPEATER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, net.minecraft.core.Direction.WEST));
                r.put(new BlockPos(4, 0, 5), Blocks.REDSTONE_WIRE.defaultBlockState());
                r.put(new BlockPos(5, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> lit(x, 5, 5) ? ok("Lampe hinter dem Verstärker an") : fail("Lampe hinter dem Verstärker aus")),
            circuit("rs_schalten", "nach dem Bau aus- und wieder einschalten", r -> {
                r.put(new BlockPos(1, 0, 5), lever(true));
                r.put(new BlockPos(2, 0, 5), Blocks.REDSTONE_WIRE.defaultBlockState());
                r.put(new BlockPos(3, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> {
                switch (x.phase) {
                    case 0 -> {
                        if (!lit(x, 3, 5)) return fail("Lampe nach dem Bau aus");
                        flip(x, false); x.phase = 1;
                    }
                    case 1 -> {
                        if (x.ticks - x.mark < 10) return null;
                        if (lit(x, 3, 5)) return fail("Lampe bleibt an, obwohl der Hebel aus ist");
                        flip(x, true); x.phase = 2;
                    }
                    default -> {
                        if (x.ticks - x.mark < 10) return null;
                        return lit(x, 3, 5) ? ok("schaltet sauber aus und an") : fail("Lampe geht nicht wieder an");
                    }
                }
                return null;
            }),
            circuit("rs_inverter", "Redstone-Fackel als Inverter: Hebel an, Lampe aus", r -> {
                r.put(new BlockPos(1, 0, 5), lever(true));
                r.put(new BlockPos(2, 0, 5), Blocks.REDSTONE_WIRE.defaultBlockState());
                r.put(new BlockPos(3, 0, 5), Blocks.STONE.defaultBlockState());
                r.put(new BlockPos(4, 0, 5), Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.RedstoneWallTorchBlock.FACING, net.minecraft.core.Direction.EAST));
                r.put(new BlockPos(5, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> !lit(x, 5, 5) ? ok("Fackel aus, Lampe aus") : fail("Lampe leuchtet trotz Inverter")),
            circuit("rs_kolben", "Klebekolben schiebt nach dem Bau den Stein", r -> {
                r.put(new BlockPos(1, 0, 5), lever(true));
                r.put(new BlockPos(2, 0, 5), Blocks.STICKY_PISTON.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, net.minecraft.core.Direction.EAST));
                r.put(new BlockPos(3, 0, 5), Blocks.STONE.defaultBlockState());
            }, x -> x.sl.getBlockState(x.at(4, 0, 5)).is(Blocks.STONE)
                    ? ok("Stein geschoben") : fail("Stein nicht geschoben")),
            // --- Redstone-Maschinen
            // Kolbentür 1 × 2: zwei Klebekolben übereinander schieben Steine in die Öffnung (x 5). Der Hebel sitzt auf
            // dem Stein hinter dem unteren Kolben: er speist den oberen direkt, den unteren über den Stein.
            circuit("rs_kolbentuer", "Kolbentür 1×2: zu, auf, zu", r -> {
                var east = net.minecraft.core.Direction.EAST;
                r.put(new BlockPos(2, 0, 5), Blocks.STONE.defaultBlockState());
                r.put(new BlockPos(2, 1, 5), lever(false));
                for (int y = 0; y <= 1; y++) {
                    r.put(new BlockPos(3, y, 5), Blocks.STICKY_PISTON.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, east));
                    r.put(new BlockPos(4, y, 5), Blocks.STONE.defaultBlockState());
                }
            }, x -> {
                BlockPos lever = x.at(2, 1, 5);
                boolean open =!isBlock(x, 5, 0, 5, Blocks.STONE) && !isBlock(x, 5, 1, 5, Blocks.STONE);
                boolean shut = isBlock(x, 5, 0, 5, Blocks.STONE) && isBlock(x, 5, 1, 5, Blocks.STONE);
                switch (x.phase) {
                    case 0 -> { if (!open) return fail("Tür nach dem Bau schon zu"); flip(x, lever, true); x.phase = 1; }
                    case 1 -> {
                        if (x.ticks - x.mark < 20) return null;
                        if (!shut) return fail("Hebel an, aber die Tür schließt nicht ganz (unten "
                                + x.sl.getBlockState(x.at(5, 0, 5)).getBlock().getName().getString() + ", oben "
                                + x.sl.getBlockState(x.at(5, 1, 5)).getBlock().getName().getString() + ")");
                        flip(x, lever, false); x.phase = 2;
                    }
                    default -> {
                        if (x.ticks - x.mark < 20) return null;
                        return open ? ok("schließt und öffnet, beide Steine zurückgezogen") : fail("Tür geht nicht wieder auf");
                    }
                }
                return null;
            }),
            // Bewegungsmelder: Druckplatte in einem 1 breiten Gang -> Staub -> Lampe; er läuft selbst einmal hindurch
            circuit("rs_druckplatte", "Druckplatte im Gang schaltet Licht, wenn er drüberläuft", r -> {
                for (int z = 3; z <= 7; z++)
                    for (int y = 0; y <= 1; y++) {
                        r.put(new BlockPos(1, y, z), Blocks.STONE.defaultBlockState());
                        if (z != 5 || y != 0) r.put(new BlockPos(3, y, z), Blocks.STONE.defaultBlockState());
                    }
                r.put(new BlockPos(2, 0, 5), Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
                r.put(new BlockPos(3, 0, 5), wire());
                r.put(new BlockPos(4, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> {
                switch (x.phase) {
                    case 0 -> {
                        if (lit(x, 4, 5)) return fail("Lampe an, obwohl niemand auf der Platte steht");
                        // erst an den Nordeingang, dann ganz durch den Gang nach Süden
                        x.bot.addTask(Task.moveTo(x.at(2, 0, 2), 1));
                        x.bot.addTask(Task.moveTo(x.at(2, 0, 8), 1));
                        x.mark = x.ticks; x.phase = 1;
                    }
                    case 1 -> {
                        x.flag |= lit(x, 4, 5);
                        if (x.bot.hasTasks()) return x.ticks - x.mark > 600 ? fail("kommt nicht durch den Gang") : null;
                        if (!x.flag) return fail("Lampe ging beim Durchlaufen nie an (steht bei "
                                + x.bot.blockPosition().subtract(x.c).toShortString() + ")");
                        x.mark = x.ticks; x.phase = 2;
                    }
                    default -> {
                        if (x.ticks - x.mark < 20) return null;
                        return !lit(x, 4, 5) ? ok("an beim Drüberlaufen, aus danach") : fail("Lampe bleibt an, nachdem er weg ist");
                    }
                }
                return null;
            }),
            // Trichterkette: Kiste -> Trichter -> Trichter -> Kiste; danach 16 Kohle oben hinein
            circuit("rs_trichter", "Trichterkette transportiert Items", r -> {
                r.put(new BlockPos(2, 0, 5), Blocks.HOPPER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.HopperBlock.FACING, net.minecraft.core.Direction.EAST));
                r.put(new BlockPos(2, 1, 5), Blocks.CHEST.defaultBlockState());
                r.put(new BlockPos(3, 0, 5), Blocks.HOPPER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.HopperBlock.FACING, net.minecraft.core.Direction.EAST));
                r.put(new BlockPos(4, 0, 5), Blocks.CHEST.defaultBlockState());
            }, x -> {
                if (x.phase == 0) {
                    var top = container(x, 2, 1, 5);
                    if (top == null) return fail("obere Kiste fehlt");
                    top.setItem(0, new ItemStack(Items.COAL, 16));
                    x.mark = x.ticks; x.phase = 1;
                    return null;
                }
                int arrived = count(container(x, 4, 0, 5), Items.COAL);
                if (arrived >= 16) return ok("16 Kohle angekommen");
                return x.ticks - x.mark > 400 ? fail("nur " + arrived + "/16 Kohle angekommen") : null;
            }),
            // Füllstandsanzeige: Kiste -> Komparator -> Lampe; leer aus, gefüllt an
            circuit("rs_komparator", "Komparator zeigt den Kisteninhalt an", r -> {
                r.put(new BlockPos(1, 0, 5), Blocks.CHEST.defaultBlockState());
                r.put(new BlockPos(2, 0, 5), Blocks.COMPARATOR.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.ComparatorBlock.FACING, net.minecraft.core.Direction.WEST));
                r.put(new BlockPos(3, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> {
                switch (x.phase) {
                    case 0 -> {
                        if (lit(x, 3, 5)) return fail("Lampe an, obwohl die Kiste leer ist");
                        var ct = container(x, 1, 0, 5);
                        if (ct == null) return fail("Kiste fehlt");
                        for (int i = 0; i < 6; i++) ct.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
                        ct.setChanged();
                        x.mark = x.ticks; x.phase = 1;
                    }
                    default -> {
                        if (lit(x, 3, 5)) return ok("leer aus, gefüllt an");
                        if (x.ticks - x.mark > 40) return fail("Lampe bleibt trotz voller Kiste aus");
                    }
                }
                return null;
            }),
            // Pfeilwerfer: Hebel -> Werfer (nach Osten); beim Umlegen fliegt ein Pfeil
            circuit("rs_werfer", "Werfer schießt beim Umlegen einen Pfeil", r -> {
                r.put(new BlockPos(1, 0, 5), lever(false));
                r.put(new BlockPos(2, 0, 5), Blocks.DISPENSER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.DispenserBlock.FACING, net.minecraft.core.Direction.EAST));
            }, x -> {
                var ct = container(x, 2, 0, 5);
                switch (x.phase) {
                    case 0 -> {
                        if (ct == null) return fail("Werfer fehlt");
                        ct.setItem(0, new ItemStack(Items.ARROW, 8));
                        flip(x, x.at(1, 0, 5), true); x.phase = 1;
                    }
                    default -> {
                        if (count(ct, Items.ARROW) < 8) return ok("Pfeil abgefeuert (" + count(ct, Items.ARROW) + " übrig)");
                        if (x.ticks - x.mark > 40) return fail("kein Pfeil abgefeuert");
                    }
                }
                return null;
            }),
            // Lange Leitung um zwei Ecken: 21 Blöcke Staub, ohne den Verstärker in der Mitte käme am Ende nichts an
            circuit("rs_weit", "lange Leitung mit Verstärker (Signalstärke)", r -> {
                r.put(new BlockPos(1, 0, 1), lever(true));
                for (int z = 2; z <= 10; z++) r.put(new BlockPos(1, 0, z), wire());
                r.put(new BlockPos(2, 0, 10), Blocks.REPEATER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, net.minecraft.core.Direction.WEST));
                for (int x0 = 3; x0 <= 10; x0++) r.put(new BlockPos(x0, 0, 10), wire());
                for (int z = 9; z >= 6; z--) r.put(new BlockPos(10, 0, z), wire());
                r.put(new BlockPos(10, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> lit(x, 10, 5) ? ok("Lampe nach 21 Blöcken Leitung an") : fail("Lampe am Ende der Leitung aus")),
            // ODER-Gatter: zwei Hebel auf eine Leitung; jeder allein schaltet die Lampe
            circuit("rs_oder", "ODER-Gatter mit zwei Hebeln", r -> {
                r.put(new BlockPos(1, 0, 3), lever(false));
                r.put(new BlockPos(2, 0, 3), wire());
                r.put(new BlockPos(3, 0, 3), wire());
                r.put(new BlockPos(1, 0, 7), lever(false));
                r.put(new BlockPos(2, 0, 7), wire());
                r.put(new BlockPos(3, 0, 7), wire());
                for (int z = 4; z <= 6; z++) r.put(new BlockPos(3, 0, z), wire());
                r.put(new BlockPos(4, 0, 5), wire());
                r.put(new BlockPos(5, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> {
                BlockPos a = x.at(1, 0, 3), b = x.at(1, 0, 7);
                switch (x.phase) {
                    case 0 -> { if (lit(x, 5, 5)) return fail("Lampe an ohne Hebel"); flip(x, a, true); x.phase = 1; }
                    case 1 -> {
                        if (x.ticks - x.mark < 10) return null;
                        if (!lit(x, 5, 5)) return fail("Hebel A allein schaltet nicht");
                        flip(x, a, false); flip(x, b, true); x.phase = 2;
                    }
                    case 2 -> {
                        if (x.ticks - x.mark < 10) return null;
                        if (!lit(x, 5, 5)) return fail("Hebel B allein schaltet nicht");
                        flip(x, b, false); x.phase = 3;
                    }
                    default -> {
                        if (x.ticks - x.mark < 10) return null;
                        return lit(x, 5, 5) ? fail("Lampe bleibt an, beide Hebel aus") : ok("A oder B schaltet, keiner: aus");
                    }
                }
                return null;
            }),
            // Taktgeber: Fackel an einem Stein, Leitung im Kreis über einen Verstärker (4 Ticks) zurück in den Stein.
            // Die Lampe neben der Fackel muss blinken – jeden Tick gezählt.
            new Scenario("rs_takt", "redstone", "Taktgeber: Lampe blinkt von selbst", 120, x -> {
                place(x, p(6, 0, 9), REDSTONE_KIT);
                java.util.Map<BlockPos, BlockState> abs = new java.util.LinkedHashMap<>();
                abs.put(x.at(2, 0, 5), Blocks.STONE.defaultBlockState());
                abs.put(x.at(3, 0, 5), Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.RedstoneWallTorchBlock.FACING, net.minecraft.core.Direction.EAST));
                abs.put(x.at(3, 0, 4), Blocks.REDSTONE_LAMP.defaultBlockState());
                for (int[] w : new int[][]{{4, 5}, {4, 6}, {4, 7}, {3, 7}, {2, 7}}) abs.put(x.at(w[0], 0, w[1]), wire());
                abs.put(x.at(2, 0, 6), Blocks.REPEATER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, net.minecraft.core.Direction.SOUTH)
                        .setValue(net.minecraft.world.level.block.RepeaterBlock.DELAY, 4));
                x.bot.build = CompanionBuilder.create("Testmap Taktgeber", abs);
                x.bot.addTask(Task.build());
                x.sampler = c -> {
                    if (c.bot.build != null) return;
                    boolean now = lit(c, 3, 4);
                    if (now != c.last) { c.counter++; c.last = now; }
                };
            }, x -> {
                if (x.bot.build != null) return x.ticks > 40 && !x.bot.hasTasks() ? fail("Bau hängt (keine Aufgabe mehr)") : null;
                if (x.mark == 0) { x.mark = x.ticks; x.counter = 0; return null; }
                if (x.counter >= 6) return ok("blinkt (" + x.counter + " Wechsel in " + (x.ticks - x.mark) / 20 + " s)");
                return x.ticks - x.mark > 100 ? fail("blinkt nicht (" + x.counter + " Wechsel in 5 s)") : null;
            }, false),
            // Eisentür am Hebel: zu, auf, zu
            // Eisentür gehört nicht zum Redstone-Kit: ohne sie gräbt er erst nach Eisen und läuft in die Zeit
            circuit("rs_eisentuer", "Eisentür mit Hebel öffnen und schließen", kit(Items.LEVER, 1, Items.IRON_DOOR, 1), r -> {
                r.put(new BlockPos(2, 0, 5), lever(false));
                BlockState d = Blocks.IRON_DOOR.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.NORTH);
                r.put(new BlockPos(3, 0, 5), d);
                r.put(new BlockPos(3, 1, 5), d.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
            }, x -> {
                BlockState door = x.sl.getBlockState(x.at(3, 0, 5));
                if (!door.is(Blocks.IRON_DOOR)) return fail("keine Eisentür (" + door.getBlock().getName().getString() + ")");
                boolean open = door.getValue(net.minecraft.world.level.block.DoorBlock.OPEN);
                switch (x.phase) {
                    case 0 -> { if (open) return fail("Tür nach dem Bau offen"); flip(x, x.at(2, 0, 5), true); x.phase = 1; }
                    case 1 -> {
                        if (x.ticks - x.mark < 10) return null;
                        if (!open) return fail("Hebel an, Tür bleibt zu");
                        flip(x, x.at(2, 0, 5), false); x.phase = 2;
                    }
                    default -> {
                        if (x.ticks - x.mark < 10) return null;
                        return open ? fail("Hebel aus, Tür bleibt offen") : ok("öffnet und schließt");
                    }
                }
                return null;
            }),
            // Beleuchtung: ein Hebel, Leitung mit vier Abzweigen, fünf Lampen
            circuit("rs_beleuchtung", "ein Hebel, fünf Lampen über Abzweige", kit(Items.LEVER, 1, Items.REDSTONE, 16, Items.REDSTONE_LAMP, 5), r -> {
                r.put(new BlockPos(1, 0, 5), lever(true));
                for (int x0 = 2; x0 <= 6; x0++) r.put(new BlockPos(x0, 0, 5), wire());
                for (int x0 : new int[]{3, 5}) {
                    r.put(new BlockPos(x0, 0, 4), wire());
                    r.put(new BlockPos(x0, 0, 3), Blocks.REDSTONE_LAMP.defaultBlockState());
                    r.put(new BlockPos(x0, 0, 6), wire());
                    r.put(new BlockPos(x0, 0, 7), Blocks.REDSTONE_LAMP.defaultBlockState());
                }
                r.put(new BlockPos(7, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> {
                int on = 0;
                for (int[] l : new int[][]{{3, 3}, {5, 3}, {3, 7}, {5, 7}, {7, 5}}) if (lit(x, l[0], l[1])) on++;
                return on == 5 ? ok("alle 5 Lampen an") : fail("nur " + on + "/5 Lampen an");
            }),
            // UND-Gatter aus drei Fackeln: jeder Eingang über eine Fackel invertiert, zusammengeführt, nochmal invertiert
            circuit("rs_und", "UND-Gatter: nur A und B zusammen schalten die Lampe", LOGIC_KIT, r -> {
                var east = net.minecraft.core.Direction.EAST;
                for (int z : new int[]{3, 7}) {
                    r.put(new BlockPos(1, 0, z), lever(false));
                    r.put(new BlockPos(2, 0, z), wire());
                    r.put(new BlockPos(3, 0, z), Blocks.STONE.defaultBlockState());
                    r.put(new BlockPos(4, 0, z), wallTorch(east));
                    r.put(new BlockPos(5, 0, z), wire());
                }
                for (int z = 4; z <= 6; z++) r.put(new BlockPos(5, 0, z), wire());
                r.put(new BlockPos(6, 0, 5), wire());
                r.put(new BlockPos(7, 0, 5), Blocks.STONE.defaultBlockState());
                r.put(new BlockPos(8, 0, 5), wallTorch(east));
                r.put(new BlockPos(9, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> {
                BlockPos a = x.at(1, 0, 3), b = x.at(1, 0, 7);
                // Stufen: 0 = beide aus, 1 = nur A, 2 = nur B, 3 = beide an
                boolean[] want = {false, false, false, true};
                String[] name = {"keiner", "nur A", "nur B", "A und B"};
                if (x.phase > 0 && x.ticks - x.mark < 16) return null;
                int step = x.phase;
                if (lit(x, 9, 5) != want[step])
                    return fail(name[step] + ": Lampe " + (lit(x, 9, 5) ? "an" : "aus") + ", soll " + (want[step] ? "an" : "aus"));
                if (step == 3) return ok("Wahrheitstabelle stimmt (00 01 10 11)");
                flip(x, a, step + 1 == 1 || step + 1 == 3);
                flip(x, b, step + 1 >= 2);
                x.phase = step + 1;
                return null;
            }),
            // NICHT-Gatter, live: Hebel aus -> Lampe an, Hebel an -> Lampe aus
            circuit("rs_nicht", "NICHT-Gatter umschalten", LOGIC_KIT, r -> {
                r.put(new BlockPos(1, 0, 5), lever(false));
                r.put(new BlockPos(2, 0, 5), wire());
                r.put(new BlockPos(3, 0, 5), Blocks.STONE.defaultBlockState());
                r.put(new BlockPos(4, 0, 5), wallTorch(net.minecraft.core.Direction.EAST));
                r.put(new BlockPos(5, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> {
                switch (x.phase) {
                    case 0 -> { if (!lit(x, 5, 5)) return fail("Hebel aus, Lampe aus (soll an)"); flip(x, x.at(1, 0, 5), true); x.phase = 1; }
                    case 1 -> {
                        if (x.ticks - x.mark < 10) return null;
                        if (lit(x, 5, 5)) return fail("Hebel an, Lampe bleibt an");
                        flip(x, x.at(1, 0, 5), false); x.phase = 2;
                    }
                    default -> {
                        if (x.ticks - x.mark < 10) return null;
                        return lit(x, 5, 5) ? ok("invertiert in beide Richtungen") : fail("Lampe geht nicht wieder an");
                    }
                }
                return null;
            }),
            // Beobachter: schaut nach Westen auf (2,5), gibt hinten (Osten) einen Impuls an die Lampe
            new Scenario("rs_beobachter", "redstone", "Beobachter meldet eine Blockänderung", 120, x -> {
                place(x, p(6, 0, 9), REDSTONE_KIT);
                java.util.Map<BlockPos, BlockState> abs = new java.util.LinkedHashMap<>();
                abs.put(x.at(3, 0, 5), Blocks.OBSERVER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.ObserverBlock.FACING, net.minecraft.core.Direction.WEST));
                abs.put(x.at(4, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
                x.bot.build = CompanionBuilder.create("Testmap Beobachter", abs);
                x.bot.addTask(Task.build());
                x.sampler = c -> { if (c.phase == 1 && lit(c, 4, 5)) c.flag = true; };
            }, x -> {
                if (x.bot.build != null) return x.ticks > 40 && !x.bot.hasTasks() ? fail("Bau hängt (keine Aufgabe mehr)") : null;
                if (x.mark == 0) { x.mark = x.ticks; return null; }
                if (x.phase == 0) {
                    if (x.ticks - x.mark < 20) return null;
                    if (lit(x, 4, 5)) return fail("Lampe an, ohne dass sich etwas geändert hat");
                    x.sl.setBlock(x.at(2, 0, 5), Blocks.STONE.defaultBlockState(), 3);
                    x.mark = x.ticks; x.phase = 1;
                    return null;
                }
                if (x.ticks - x.mark < 20) return null;
                if (!x.flag) return fail("kein Impuls, als vor dem Beobachter ein Block gesetzt wurde");
                return !lit(x, 4, 5) ? ok("Impuls bei Änderung, danach wieder aus") : fail("Lampe bleibt dauerhaft an");
            }, false),
            // Verzögerung: Hebel -> 4 Verstärker auf Stufe 4 (zusammen 32 Ticks) -> Lampe
            circuit("rs_verzoegerung", "4 Verstärker verzögern das Signal", LOGIC_KIT, r -> {
                r.put(new BlockPos(1, 0, 5), lever(false));
                for (int x0 = 2; x0 <= 5; x0++) r.put(new BlockPos(x0, 0, 5), repeater(net.minecraft.core.Direction.WEST, 4));
                r.put(new BlockPos(6, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> {
                switch (x.phase) {
                    case 0 -> { if (lit(x, 6, 5)) return fail("Lampe vor dem Einschalten an"); flip(x, x.at(1, 0, 5), true); x.phase = 1; }
                    case 1 -> {
                        // nach 10 Ticks muss sie noch aus sein, sonst verzögern die Verstärker nicht
                        if (x.ticks - x.mark < 10) return null;
                        if (lit(x, 6, 5)) return fail("Lampe schon nach 0,5 s an – Verzögerung fehlt (Stufen verloren?)");
                        x.phase = 2;
                    }
                    default -> {
                        if (lit(x, 6, 5)) return ok("Lampe nach " + (x.ticks - x.mark) + " Ticks an (≥ 32 erwartet)");
                        if (x.ticks - x.mark > 80) return fail("Lampe geht gar nicht an");
                    }
                }
                return null;
            }),
            // Tageslichtsensor -> Lampe: tagsüber an, nachts aus
            circuit("rs_tageslicht", "Tageslichtsensor schaltet nach Tageszeit", r -> {
                r.put(new BlockPos(3, 0, 5), Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
                r.put(new BlockPos(4, 0, 5), Blocks.REDSTONE_LAMP.defaultBlockState());
            }, x -> {
                if (x.ticks - x.mark < 60) return null; // der Sensor misst nur alle 20 Ticks
                boolean day = !x.sl.isDarkOutside();
                return lit(x, 4, 5) == day ? ok((day ? "Tag" : "Nacht") + ": Lampe " + (day ? "an" : "aus"))
                        : fail((day ? "Tag" : "Nacht") + ", aber Lampe " + (lit(x, 4, 5) ? "an" : "aus"));
            }),
            // --- Schematics (als Datei geschrieben, über den normalen Schematic-Weg geladen und nachgebaut)
            schematic("schem_huette_nbt", "Hütte aus .nbt (Tür, Fackel, Treppe, Platten)", TestSchematics::hut,
                    TestSchematics::writeStructure, net.minecraft.world.level.block.Rotation.NONE, null),
            schematic("schem_turm_schem", "Turm aus .schem (WorldEdit) mit Leiter", TestSchematics::tower,
                    TestSchematics::writeSponge, net.minecraft.world.level.block.Rotation.NONE, null),
            schematic("schem_gedreht", "Hütte aus .nbt, 90° gedreht (Ausrichtungen mitdrehen)", TestSchematics::hut,
                    TestSchematics::writeStructure, net.minecraft.world.level.block.Rotation.CLOCKWISE_90, null),
            schematic("schem_haus", "zweistöckiges Haus aus .nbt (Leiter, Scheiben, Einrichtung, Treppendach)", TestSchematics::house,
                    TestSchematics::writeStructure, net.minecraft.world.level.block.Rotation.NONE, null),
            schematic("schem_redstone", "Schaltung aus .schem – Lampe muss danach leuchten", TestSchematics::lampCircuit,
                    TestSchematics::writeSponge, net.minecraft.world.level.block.Rotation.NONE, x -> {
                        for (var en : x.expected.entrySet())
                            if (en.getValue().is(Blocks.REDSTONE_LAMP)) {
                                boolean lit = x.sl.getBlockState(en.getKey()).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT);
                                return lit ? ok("Schaltung gebaut, Lampe an") : fail("Blöcke stimmen, aber die Lampe ist aus");
                            }
                        return fail("keine Lampe im Plan");
                    }),
            // --- MLG (Turm 28 hoch, nur 20 Leben: ungebremst wäre der Sturz tödlich)
            mlg("mlg_eimer", "mit Wassereimer runter (Wasser kurz vor dem Aufprall)", p(10, 0, 5), 3, 60, kit(Items.WATER_BUCKET, 1)),
            mlg("mlg_ohne", "ohne Eimer sicher runter (abbauen oder Umweg)", p(10, 0, 5), 6, 180,
                    kit(Items.STONE_PICKAXE, 1, Items.DIRT, 32)),
            mlg("mlg_becken", "ohne Eimer, unten ein Wasserbecken", p(1, 0, 2), 3, 60, NONE),
            go("eisentuer", "eisentuer", "durch die Eisentür mit Druckplatten", 60, p(5, 0, 1), p(5, 0, 10)),
            job("redstoneerz", "erze", "4 Redstone abbauen", 90, p(6, 0, 0), x -> Task.gather("redstone", 4),
                    x -> CompanionCraft.count(x.bot, "redstone") >= 4, kit(Items.IRON_PICKAXE, 1)),
            new Scenario("unterschlupf", "hoehle", "10 Leben, Skelette in der Höhle: 60 s überleben", 60, x -> {
                BlockPos room = TestMap.caveRoom(x.o);
                place(x, new int[]{room.getX() - x.c.getX(), room.getY() - x.c.getY(), room.getZ() - x.c.getZ()},
                        kit(Items.COBBLESTONE, 32, Items.STONE_SWORD, 1, Items.BREAD, 4));
                x.bot.freeRoam(); // der Planer entscheidet selbst (Rückzug, Einmauern)
                x.bot.setHealth(10);
                for (int i = 0; i < 2; i++) {
                    Mob m = EntityTypes.SKELETON.spawn(x.sl, room.offset(3, 0, -2 + 4 * i), EntitySpawnReason.COMMAND);
                    if (m != null) { m.setPersistenceRequired(); x.mobs.add(m); }
                }
            }, x -> {
                x.flag |= x.bot.isSheltered();
                return null;
            }, true));

    static final List<Scenario> ALL = java.util.stream.Stream.concat(
            java.util.stream.Stream.concat(BASE.stream(), TestRedstoneScenarios.all().stream()),
            TestMachineScenarios.all().stream()).toList();

    public static List<Scenario> all() { return ALL; }

    static Scenario byId(String id) {
        for (Scenario s : ALL) if (s.id.equals(id)) return s;
        return null;
    }

    /**
     * Gruppen: alle, redstone (inkl. Extras und Farmen), redstone_extra, farmen, farmen_alle, schematic, mlg. Farmen mit
     * gleichem Prinzip laufen in den Gruppen nur einmal (ein Vertreter), farmen_alle nimmt auch die Abwandlungen.
     */
    static final List<String> GROUPS = List.of("alle", "redstone", "redstone_extra", "farmen", "farmen_alle", "schematic", "mlg");

    /** Was "/companion testmap alle" startet: alles außer den Farm-Abwandlungen. */
    static List<Scenario> allDistinct() { return pick("alle", new ArrayList<>()); }

    private static boolean inGroup(Scenario s, String group) {
        if (group.equals("farmen_alle")) return TestMachineScenarios.all().contains(s);
        if (TestMachineScenarios.variant(s)) return false;
        return switch (group) {
            case "alle" -> true;
            case "redstone" -> s.id.startsWith("rs_") || s.id.equals("schem_redstone") || s.id.equals("redstoneerz")
                    || s.id.equals("eisentuer") || TestMachineScenarios.all().contains(s);
            case "redstone_extra" -> TestRedstoneScenarios.all().contains(s);
            case "farmen" -> TestMachineScenarios.all().contains(s);
            case "schematic" -> s.id.startsWith("schem_");
            case "mlg" -> s.id.startsWith("mlg_");
            default -> false;
        };
    }

    /** "name,name,gruppe" -> Szenarien (Reihenfolge wie angegeben, keine doppelt); unbekannter Name -> Fehlertext. */
    static List<Scenario> pick(String spec, List<String> unknown) {
        java.util.LinkedHashSet<Scenario> out = new java.util.LinkedHashSet<>();
        for (String raw : spec.split(",")) {
            String n = raw.trim();
            if (n.isEmpty()) continue;
            if (GROUPS.contains(n)) { for (Scenario s : ALL) if (inGroup(s, n)) out.add(s); continue; }
            Scenario s = byId(n);
            if (s == null) unknown.add(n); else out.add(s);
        }
        return new ArrayList<>(out);
    }

    public static String list() {
        StringBuilder sb = new StringBuilder("Szenarien (" + ALL.size() + "):");
        for (Scenario s : ALL) sb.append("\n ").append(s.id).append(" – ").append(s.desc).append(" (").append(s.station).append(")");
        return sb.append("\n/companion testmap szenario <name[,name]> (auch Gruppen: redstone, redstone_extra, farmen, farmen_alle, schematic, mlg)  |  alle  |  stop")
                .toString();
    }

    // ------------------------------------------------------------------ Ablauf

    private static final ArrayDeque<Scenario> queue = new ArrayDeque<>();
    private static Ctx run;
    private static Scenario current;
    private static ServerPlayer runner;
    private static int pause;
    private static final List<String> summary = new ArrayList<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TestScenarios::tick);
    }

    /** Nach dem Serverende keine Spieler- oder Weltreferenzen behalten. */
    public static void reset() {
        stop(null);
        runner = null;
        summary.clear();
    }

    // ------------------------------------------------------------------ Kamera: der Spieler schaut als Zuschauer zu

    /** Kamera mitführen (Standard an; /companion testmap kamera schaltet um). */
    static boolean camera = true;
    /** Spielmodus vor dem Lauf, null = Kamera gerade nicht aktiv. */
    private static net.minecraft.world.level.GameType prevMode;

    public static String toggleCamera() {
        camera = !camera;
        if (!camera) restoreCamera();
        return "Kamera beim Testen: " + (camera ? "an (Zuschauermodus, einmal pro Station über die Station)" : "aus");
    }

    private static void startCamera(ServerPlayer p) {
        if (!camera || prevMode != null) return;
        cameraStation = null;
        prevMode = p.gameMode.getGameModeForPlayer();
        p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
    }

    /** Station, über die die Kamera zuletzt gesetzt wurde: nur beim Wechsel neu teleportieren, sonst frei umsehen. */
    private static String cameraStation;

    /** Schräg von oben vor die Station, Blick auf ihre Mitte – einmal pro Stationswechsel. */
    private static void showStation(Ctx x, Scenario s) {
        if (prevMode == null || runner == null || s.station.equals(cameraStation)) return;
        cameraStation = s.station;
        Vec3 b = Vec3.atCenterOf(x.at(TestMap.CELL / 2, 0, TestMap.CELL / 2));
        Vec3 eye = new Vec3(b.x, b.y + 12, b.z - 12);
        double dx = b.x - eye.x, dy = b.y - eye.y, dz = b.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        runner.teleportTo(x.sl, eye.x, eye.y, eye.z, java.util.Set.of(), yaw, pitch, true);
    }

    /** Alten Spielmodus zurück, wieder auf den Start stellen (nicht in der Luft oder im Stein stehen lassen). */
    private static void restoreCamera() {
        if (prevMode == null || runner == null) { prevMode = null; return; }
        ServerLevel sl = runner.level();
        BlockPos o = TestMap.origin(sl.getServer());
        if (o != null) runner.teleportTo(sl, o.getX() + 0.5, o.getY(), o.getZ() + 0.5, java.util.Set.of(), 180f, 10f, true);
        runner.setGameMode(prevMode);
        prevMode = null;
        cameraStation = null;
    }

    public static String start(ServerPlayer p, List<Scenario> scenarios) {
        if (TestMap.origin(p.level().getServer()) == null) return "Erst das Gelände bauen: /companion testmap";
        stop(null);
        queue.addAll(scenarios);
        runner = p;
        startCamera(p);
        pause = 60; // Welt und Chunks erst zur Ruhe kommen lassen
        summary.clear();
        log(p.level().getServer(), "=== Lauf: " + scenarios.size() + " Szenario(s) ===");
        return scenarios.size() == 1 ? "Starte Szenario " + scenarios.getFirst().id + "." : "Starte " + scenarios.size() + " Szenarien nacheinander.";
    }

    public static String stop(ServerPlayer p) {
        boolean was = run != null || !queue.isEmpty();
        if (run != null && run.bot != null) run.bot.cancelOrders();
        queue.clear();
        run = null;
        current = null;
        restoreCamera();
        return was ? "Szenarien abgebrochen." : "Es läuft kein Szenario.";
    }

    private static void tick(MinecraftServer server) {
        if (runner == null) return;
        if (runner.isRemoved() || runner.hasDisconnected()) { runner = null; stop(null); return; }
        if (run == null) {
            if (queue.isEmpty()) {
                if (!summary.isEmpty()) finishAll(server);
                return;
            }
            if (pause > 0) { pause--; return; }
            begin(queue.poll());
            return;
        }
        Ctx x = run;
        x.ticks++;
        if (!x.bot.isAlive()) { end(fail("gestorben")); return; }
        x.minHealth = Math.min(x.minHealth, x.bot.getHealth());
        if (x.sampler != null) x.sampler.accept(x);
        if (current.id.equals("lava") && (x.bot.isInLava() || x.bot.isOnFire())) { end(fail("in Lava/brennt")); return; }
        if (x.ticks % 10 == 0) {
            Result r = current.check.run(x);
            if (r != null) { end(r); return; }
        }
        if (x.ticks >= current.seconds * 20)
            end(current.timeoutOk ? ok("durchgehalten, " + (int) x.bot.getHealth() + " Leben, eingemauert: " + (x.flag ? "ja" : "nein"))
                    : fail("Zeit abgelaufen"));
    }

    private static void begin(Scenario s) {
        ServerLevel sl = runner.level();
        BlockPos o = TestMap.origin(sl.getServer());
        Ctx x = new Ctx();
        x.sl = sl; x.p = runner; x.o = o;
        TestMap.Station st = TestMap.station(s.station);
        x.c = TestMap.corner(o, st);
        TestMap.rebuild(sl, o, st);
        x.bot = companion(sl, runner);
        current = s;
        run = x;
        try {
            s.setup.run(x);
        } catch (RuntimeException e) {
            LOG.error("[Testmap] Aufbau von {} fehlgeschlagen", s.id, e);
            end(fail("Aufbau fehlgeschlagen: " + e));
            return;
        }
        x.startHealth = x.minHealth = x.bot.getHealth();
        showStation(x, s);
        runner.sendSystemMessage(Component.literal("[Testmap] " + s.id + ": " + s.desc + " (max. " + s.seconds + " s)"));
    }

    private static void end(Result r) {
        Ctx x = run;
        Scenario s = current;
        run = null;
        current = null;
        pause = 40;
        String pos = x.bot.blockPosition().subtract(x.c).toShortString();
        int loose = x.sl.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(x.c).inflate(TestMap.CELL + 2, 20, TestMap.CELL + 2))
                .stream().mapToInt(i -> i.getItem().getCount()).sum();
        String line = (r.ok ? "OK    " : "FEHLER") + " " + s.id + " – " + r.why + " (" + x.ticks / 20 + " s, Leben "
                + (int) x.bot.getHealth() + "/" + (int) x.bot.getMaxHealth() + ", Stelle " + pos + ", Aufgabe: "
                + x.bot.currentTaskText() + (loose == 0 ? "" : ", liegen: " + loose + " Items")
                + (x.bot.problemText.isEmpty() ? "" : ", Problem: " + x.bot.problemText) + ")";
        if (x.bot.isAlive()) {
            x.bot.cancelOrders();
            x.bot.stay();
        }
        for (Mob m : x.mobs) if (m.isAlive()) m.discard();
        summary.add((r.ok ? "OK " : "FEHLER ") + s.id);
        log(x.sl.getServer(), line);
        runner.sendSystemMessage(Component.literal("[Testmap] " + line));
    }

    private static void finishAll(MinecraftServer server) {
        long ok = summary.stream().filter(l -> l.startsWith("OK")).count();
        String line = "=== " + ok + "/" + summary.size() + " bestanden"
                + (ok == summary.size() ? "" : "; fehlgeschlagen: " + String.join(", ",
                summary.stream().filter(l -> !l.startsWith("OK")).map(l -> l.substring(7)).toList())) + " ===";
        log(server, line);
        if (runner != null) runner.sendSystemMessage(Component.literal("[Testmap] " + line));
        summary.clear();
        restoreCamera();
        runner = null;
    }

    private static void log(MinecraftServer server, String line) {
        LOG.info("[Testmap] {}", line);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try {
            Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve(RESULT_FILE), stamp + " " + line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException ignored) {}
    }

    // ------------------------------------------------------------------ Begleiter vorbereiten

    /** Der Begleiter des Spielers; gibt es keinen, wird einer erzeugt. */
    private static CompanionEntity companion(ServerLevel sl, ServerPlayer p) {
        for (CompanionEntity c : sl.getEntities(CompanionMod.COMPANION, c -> c.isAlive() && c.isOwner(p))) return c;
        CompanionEntity c = CompanionMod.COMPANION.create(sl, EntitySpawnReason.COMMAND);
        c.snapTo(p.getX(), p.getY(), p.getZ(), 0, 0);
        c.setOwner(p.getUUID());
        c.setCustomName(Component.literal(CompanionEntity.DEFAULT_NAME));
        sl.addFreshEntity(c);
        return c;
    }

    /** Aufträge weg, Inventar und Ausrüstung leeren, volle Leben, satt, an die Startstelle (lokal) setzen. */
    static void place(Ctx x, int[] start, Kit kit) {
        ItemStack[] items = kit.stacks();
        CompanionEntity b = x.bot;
        b.cancelOrders();
        x.buildIdleSince = -1;
        b.stay();
        b.stance = CompanionCombat.Stance.AGGRESSIVE;
        b.getInventory().clearContent();
        for (EquipmentSlot slot : EquipmentSlot.values()) b.setItemSlot(slot, ItemStack.EMPTY);
        for (ItemStack s : items) b.giveToInventory(s.copy());
        b.removeAllEffects();
        b.clearFire();
        b.setHealth(b.getMaxHealth());
        b.hunger = 20;
        b.problemText = "";
        // bleibt sonst vom Zucht-Szenario (oder einem früheren Spiel) gespeichert an und schont die Tiere beim Jagen
        b.herdKeeping = false;
        BlockPos at = x.at(start[0], start[1], start[2]);
        if (!x.sl.getBlockState(at.below()).isSolid()) x.sl.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
        b.nav.stop();
        b.getNavigation().stop();
        b.setDeltaMovement(Vec3.ZERO);
        b.resetFallDistance();
        b.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, b.getYRot(), 0f);
    }
}
