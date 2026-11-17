package dev.begleiter;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.AABB;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.task.TaskSpec;

/** GameTests für die Alltags-Arbeiten: Feld, Kisten nach Sorten, Zucht, Werkzeug-Pflege, Handel, Wegpunkte, Ausrüstung. */
public class JobsGameTests {

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    /** Boden plus Barriere-Rand, damit Tiere nicht weglaufen. */
    private static void pen(GameTestHelper h) {
        floor(h);
        for (int i = 0; i < 8; i++)
            for (int y = 1; y <= 2; y++) {
                h.setBlock(i, y, 0, Blocks.BARRIER);
                h.setBlock(i, y, 7, Blocks.BARRIER);
                h.setBlock(0, y, i, Blocks.BARRIER);
                h.setBlock(7, y, i, Blocks.BARRIER);
            }
    }

    private static CompanionEntity companion(GameTestHelper h, BlockPos rel) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, rel);
        c.setPersistenceRequired();
        return c;
    }

    private static Container chest(GameTestHelper h, CompanionEntity c, BlockPos rel) {
        h.setBlock(rel, Blocks.CHEST);
        Container box = c.containerAt(h.getLevel(), h.absolutePos(rel));
        h.assertTrue(box != null, "Kiste hat keinen Container");
        return box;
    }

    private static int count(Container box, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(item)) n += box.getItem(i).getCount();
        return n;
    }

    private static int countAll(CompanionEntity c, net.minecraft.world.item.Item item) {
        int n = count(c.getInventory(), item);
        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) if (c.getItemBySlot(slot).is(item)) n += c.getItemBySlot(slot).getCount();
        return n;
    }

    private static ItemStack damagedTo(net.minecraft.world.item.Item item, double healthLeft) {
        ItemStack s = new ItemStack(item);
        s.setDamageValue(s.getMaxDamage() - (int) Math.round(s.getMaxDamage() * healthLeft));
        return s;
    }

    private static net.minecraft.server.level.ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID());
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        return p;
    }

    private static List<String> tok(String text) {
        return List.of(CompanionBrain.normalize(text).split(" "));
    }

    /** Antworttext in deutscher Sprache auflösen (Antworten sind übersetzte Komponenten). */
    private static String german(java.util.function.Supplier<String> reply) throws Exception {
        String[] out = new String[1];
        ServerTextGameTests.inLanguage("de_de", () -> out[0] = reply.get());
        return out[0];
    }

    // ------------------------------------------------------------------ 1. Feld

    @GameTest(maxTicks = 600)
    public void feldErntenUndNeuPflanzen(GameTestHelper h) {
        floor(h);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        for (int x = 2; x <= 5; x++) {
            h.setBlock(x, 0, 4, Blocks.FARMLAND);
            h.setBlock(new BlockPos(x, 1, 4), wheat.getStateForAge(wheat.getMaxAge()));
        }
        h.setBlock(6, 0, 4, Blocks.FARMLAND); // leer: soll besät werden
        h.setBlock(4, 0, 5, Blocks.WATER); // Ackerland darf vor dem Besaeen nicht zufaellig austrocknen.
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.WHEAT_SEEDS, 5)); // Auch ohne zufaellige Samenbeute alle fuenf Felder bepflanzen.
        c.addTask(Task.farm(h.absolutePos(new BlockPos(3, 1, 4))));
        h.succeedWhen(() -> {
            h.assertTrue(c.currentTask() == null, "Feld-Aufgabe läuft noch");
            for (int x = 2; x <= 6; x++) {
                var s = h.getBlockState(new BlockPos(x, 1, 4));
                h.assertTrue(s.is(Blocks.WHEAT), "Bei x=" + x + " nicht (neu) gepflanzt: " + s);
            }
            h.assertTrue(count(c.getInventory(), Items.WHEAT) >= 4, "Weizen nicht geerntet");
        });
    }

    @GameTest
    public void feldZaehltReifePflanzen(GameTestHelper h) {
        floor(h);
        CropBlock carrots = (CropBlock) Blocks.CARROTS;
        h.setBlock(2, 0, 2, Blocks.FARMLAND);
        h.setBlock(3, 0, 2, Blocks.FARMLAND);
        h.setBlock(new BlockPos(2, 1, 2), carrots.getStateForAge(carrots.getMaxAge()));
        h.setBlock(new BlockPos(3, 1, 2), carrots.getStateForAge(1));
        ServerLevel sl = h.getLevel();
        h.assertValueEqual(CompanionJobs.countMature(sl, h.absolutePos(new BlockPos(3, 1, 3)), 4), 1, "Reife Karotten");
        h.assertTrue(CompanionJobs.hasFarmland(sl, h.absolutePos(new BlockPos(3, 1, 3)), 4), "Ackerland nicht erkannt");
        h.succeed();
    }

    // ------------------------------------------------------------------ 2. Kisten nach Sorten

    @GameTest
    public void kistenNachSortenOrdnen(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        Container a = chest(h, c, new BlockPos(2, 1, 4));
        Container b = chest(h, c, new BlockPos(5, 1, 4));
        a.setItem(0, new ItemStack(Items.COBBLESTONE, 12));
        a.setItem(3, new ItemStack(Items.BREAD, 3));
        a.setItem(9, new ItemStack(Items.IRON_INGOT, 2));
        b.setItem(1, new ItemStack(Items.IRON_INGOT, 3));
        b.setItem(4, new ItemStack(Items.OAK_LOG, 10));
        b.setItem(6, new ItemStack(Items.COBBLESTONE, 8));
        b.setItem(8, new ItemStack(Items.STONE_PICKAXE));

        var r = CompanionJobs.sortByCategory(c, h.getLevel(), h.absolutePos(new BlockPos(3, 1, 4)));

        h.assertValueEqual(r.boxes(), 2, "Anzahl Kisten");
        // nächste Kiste zur Mitte (a): Werkzeug, Essen, Erze – andere Kiste (b): Holz, Stein
        h.assertValueEqual(count(a, Items.STONE_PICKAXE), 1, "Werkzeug in Kiste 1");
        h.assertValueEqual(count(a, Items.BREAD), 3, "Essen in Kiste 1");
        h.assertValueEqual(count(a, Items.IRON_INGOT), 5, "Eisen zusammengelegt in Kiste 1");
        h.assertValueEqual(count(b, Items.OAK_LOG), 10, "Holz in Kiste 2");
        h.assertValueEqual(count(b, Items.COBBLESTONE), 20, "Bruchstein zusammengelegt in Kiste 2");
        h.assertValueEqual(count(a, Items.COBBLESTONE), 0, "Bruchstein in der Werkzeugkiste");
        ServerTextGameTests.assertTranslatedContains(h, r.lineTexts().getFirst(), "Werkzeug", "Tools & armor");
        h.succeed();
    }

    @GameTest
    public void sortierenWirftBeiFreiemPlatzNichtsAb(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        Container[] boxes = new Container[2];
        BlockPos[] at = {new BlockPos(2, 1, 4), new BlockPos(5, 1, 4)};
        for (int i = 0; i < 2; i++) {
            h.setBlock(at[i], Blocks.BARREL);
            boxes[i] = c.containerAt(h.getLevel(), h.absolutePos(at[i]));
        }
        // Beide Fässer randvoll: ein Werkzeug und 53 nicht zusammenlegbare Brotstapel
        boxes[0].setItem(0, new ItemStack(Items.STONE_PICKAXE));
        int n = 0;
        for (Container box : boxes)
            for (int i = 0; i < box.getContainerSize(); i++) {
                if (!box.getItem(i).isEmpty()) continue;
                ItemStack bread = new ItemStack(Items.BREAD, 2);
                bread.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                        net.minecraft.network.chat.Component.literal("Brot " + n++));
                box.setItem(i, bread);
            }
        h.assertValueEqual(n, 53, "Testaufbau");

        CompanionJobs.sortByCategory(c, h.getLevel(), h.absolutePos(new BlockPos(3, 1, 4)));

        int stacks = 0, bread = 0, picks = 0;
        for (Container box : boxes)
            for (int i = 0; i < box.getContainerSize(); i++) {
                ItemStack s = box.getItem(i);
                if (s.isEmpty()) continue;
                stacks++;
                if (s.is(Items.BREAD)) bread += s.getCount();
                if (s.is(Items.STONE_PICKAXE)) picks++;
            }
        h.assertValueEqual(stacks, 54, "Stapel in den Fässern");
        h.assertValueEqual(bread, 106, "Brot in den Fässern");
        h.assertValueEqual(picks, 1, "Werkzeug in den Fässern");
        h.assertTrue(h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new AABB(h.absolutePos(BlockPos.ZERO)).inflate(16)).isEmpty(), "Gegenstände abgeworfen");
        h.succeed();
    }

    @GameTest
    public void sortenWerdenErkannt(GameTestHelper h) {
        h.assertValueEqual(CompanionJobs.category(new ItemStack(Items.IRON_SWORD)), CompanionJobs.Category.TOOLS, "Schwert");
        h.assertValueEqual(CompanionJobs.category(new ItemStack(Items.DIAMOND_HELMET)), CompanionJobs.Category.TOOLS, "Helm");
        h.assertValueEqual(CompanionJobs.category(new ItemStack(Items.COOKED_BEEF)), CompanionJobs.Category.FOOD, "Steak");
        h.assertValueEqual(CompanionJobs.category(new ItemStack(Items.RAW_IRON)), CompanionJobs.Category.ORES, "Roheisen");
        h.assertValueEqual(CompanionJobs.category(new ItemStack(Items.BIRCH_PLANKS)), CompanionJobs.Category.WOOD, "Bretter");
        h.assertValueEqual(CompanionJobs.category(new ItemStack(Items.WHEAT_SEEDS)), CompanionJobs.Category.FARM, "Samen");
        h.assertValueEqual(CompanionJobs.category(new ItemStack(Items.COBBLED_DEEPSLATE)), CompanionJobs.Category.STONE, "Tiefenschiefer");
        h.assertValueEqual(CompanionJobs.category(new ItemStack(Items.ROTTEN_FLESH)), CompanionJobs.Category.MOB, "Fleisch");
        h.succeed();
    }

    // ------------------------------------------------------------------ 3. Zucht

    @GameTest(maxTicks = 900)
    public void zuchtFuettertPaarUndGibtNachwuchs(GameTestHelper h) {
        pen(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(4, 1, 4));
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(5, 1, 5));
        c.giveToInventory(new ItemStack(Items.WHEAT, 4));
        c.addTask(Task.breed("cow", 1));
        AABB area = new AABB(h.absolutePos(new BlockPos(0, 0, 0))).expandTowards(8, 4, 8);
        h.succeedWhen(() -> {
            long babies = h.getLevel().getEntitiesOfClass(Animal.class, area, Animal::isBaby).size();
            h.assertTrue(babies >= 1, "Kein Kalb");
            h.assertValueEqual(count(c.getInventory(), Items.WHEAT), 2, "Futter verbraucht");
            h.assertTrue(c.herdKeeping, "Herde halten nicht aktiv");
        });
    }

    @GameTest(structure = "begleiter-gametest:herd_isolation")
    public void herdeHaltenSchontZuchtpaar(GameTestHelper h) {
        // mayHunt counts within 16 blocks; nearestFoodAnimal searches within 40.
        // Beide Abfragen in diesem Test halten, damit Herden der Nachbartests nicht als Überschuss zählen.
        for (int x = 46; x <= 53; x++)
            for (int z = 46; z <= 53; z++) h.setBlock(x, 0, z, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(48, 1, 48));
        var cow1 = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(50, 1, 50));
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(51, 1, 51));
        h.assertTrue(CompanionJobs.mayHunt(c, cow1), "Ohne Herdehalten darf er jagen");
        c.herdKeeping = true;
        h.assertTrue(!CompanionJobs.mayHunt(c, cow1), "Letztes Zuchtpaar gejagt");
        h.assertTrue(c.nearestFoodAnimal() == null, "Zuchtpaar als Jagdziel");
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(49, 1, 51));
        h.assertTrue(CompanionJobs.mayHunt(c, cow1), "Überschuss nicht freigegeben");
        var calf = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(51, 1, 49));
        calf.setAge(-24000);
        h.assertTrue(!CompanionJobs.mayHunt(c, calf), "Kalb als Jagdziel");
        h.succeed();
    }

    /** Benannte, angeleinte und gezähmte Tiere gehören jemandem: weder jagen noch fremde holen. */
    @GameTest
    public void besitzTiereWerdenWederGejagtNochGeholt(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        @SuppressWarnings("removal")
        var owner = h.makeMockServerPlayerInLevel();
        @SuppressWarnings("removal")
        var stranger = h.makeMockServerPlayerInLevel();
        var free = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(3, 1, 3));
        var named = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(4, 1, 3));
        named.setCustomName(net.minecraft.network.chat.Component.literal("Bessie"));
        var leashed = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(5, 1, 3));
        leashed.setLeashedTo(stranger, true);
        var horse = h.spawn(net.minecraft.world.entity.EntityTypes.HORSE, new BlockPos(3, 1, 5));
        horse.tameWithName(stranger);
        h.assertTrue(CompanionJobs.mayHunt(c, free), "Freie Kuh nicht jagbar");
        h.assertTrue(!CompanionJobs.mayHunt(c, named), "Benannte Kuh gejagt");
        h.assertTrue(!CompanionJobs.mayHunt(c, leashed), "Angeleinte Kuh gejagt");
        h.assertTrue(CompanionJobs.mayFetch(free, owner), "Freie Kuh nicht holbar");
        h.assertTrue(!CompanionJobs.mayFetch(leashed, owner), "Angeleinte Kuh geholt");
        h.assertTrue(!CompanionJobs.mayFetch(horse, owner), "Fremdes Pferd geholt");
        h.assertTrue(CompanionJobs.mayFetch(horse, stranger), "Eigenes Pferd nicht holbar");
        h.succeed();
    }

    // ------------------------------------------------------------------ 4. Werkzeug-Pflege

    @GameTest(maxTicks = 400)
    public void werkzeugZusammenlegenUndAmbossReparieren(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        h.setBlock(4, 1, 4, Blocks.ANVIL);
        c.giveToInventory(damagedTo(Items.IRON_PICKAXE, 0.10));
        c.giveToInventory(damagedTo(Items.IRON_PICKAXE, 0.10));
        c.giveToInventory(damagedTo(Items.IRON_SWORD, 0.40));
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 8));
        c.addTask(Task.repair(90));
        h.succeedWhen(() -> {
            h.assertTrue(c.currentTask() == null, "Reparatur läuft noch");
            h.assertValueEqual(countAll(c, Items.IRON_PICKAXE), 1, "Spitzhacken nicht zusammengelegt");
            for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
                ItemStack s = c.getInventory().getItem(i);
                if (s.is(Items.IRON_PICKAXE) || s.is(Items.IRON_SWORD))
                    h.assertValueEqual(s.getDamageValue(), 0, Resources.id(s) + " nicht repariert");
            }
            h.assertValueEqual(countAll(c, Items.IRON_INGOT), 1, "Eisen für die Reparatur");
        });
    }

    @GameTest
    public void fastKaputtesWerkzeugWirdGeschont(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 2);
        c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, worn);
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        c.equipToolFor(Blocks.STONE.defaultBlockState());
        h.assertTrue(c.getMainHandItem().is(Items.STONE_PICKAXE), "Nimmt das fast kaputte Werkzeug statt Ersatz");
        h.succeed();
    }

    // ------------------------------------------------------------------ 5. Handel

    private static net.minecraft.world.entity.npc.villager.Villager trader(GameTestHelper h, MerchantOffer offer) {
        var v = h.spawn(net.minecraft.world.entity.EntityTypes.VILLAGER, new BlockPos(5, 1, 5));
        v.setNoAi(true);
        MerchantOffers offers = v.getOffers(); // overrideOffers ist am Dorfbewohner wirkungslos – Liste direkt füllen
        offers.clear();
        offers.add(offer);
        return v;
    }

    @GameTest(maxTicks = 400)
    public void handelKauftBuecherMitSmaragden(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        trader(h, new MerchantOffer(new ItemCost(Items.EMERALD, 1), Optional.empty(), new ItemStack(Items.BOOK), 12, 1, 0.05f));
        c.giveToInventory(new ItemStack(Items.EMERALD, 5));
        c.addTask(Task.trade("book", 2));
        h.succeedWhen(() -> {
            h.assertTrue(c.currentTask() == null, "Handel läuft noch");
            h.assertValueEqual(count(c.getInventory(), Items.BOOK), 2, "Bücher gekauft");
            h.assertValueEqual(count(c.getInventory(), Items.EMERALD), 3, "Smaragde bezahlt");
        });
    }

    @GameTest(maxTicks = 400)
    public void handelVerkauftUeberschuss(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        trader(h, new MerchantOffer(new ItemCost(Items.WHEAT, 20), Optional.empty(), new ItemStack(Items.EMERALD), 12, 1, 0.05f));
        c.giveToInventory(new ItemStack(Items.WHEAT, 40));
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 5));
        c.addTask(Task.trade(null, 0));
        h.succeedWhen(() -> {
            h.assertTrue(c.currentTask() == null, "Handel läuft noch");
            h.assertValueEqual(count(c.getInventory(), Items.EMERALD), 2, "Smaragde für Weizen");
            h.assertValueEqual(count(c.getInventory(), Items.WHEAT), 0, "Weizen verkauft");
            h.assertValueEqual(count(c.getInventory(), Items.IRON_INGOT), 5, "Eisen darf er nicht verkaufen");
        });
    }

    // ------------------------------------------------------------------ 6. Wegpunkte

    @GameTest(maxTicks = 300)
    public void wegpunktMerkenUndHingehen(GameTestHelper h) throws Exception {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        var p = owner(h, c);
        BlockPos spot = h.absolutePos(new BlockPos(6, 1, 6));
        p.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 0);
        // Antworten sind übersetzt; die Prüfungen beziehen sich auf den deutschen Text.
        String reply = german(() -> CompanionBrain.waypointCommand(p, c, tok("Merk dir das als Eisen Mine"), CompanionBrain.normalize("Merk dir das als Eisen Mine")));
        h.assertTrue(reply != null && reply.startsWith("Gemerkt"), "Antwort: " + reply);
        var w = c.memory.waypoint("eisen mine", h.getLevel());
        h.assertTrue(w != null && w.pos.equals(spot), "Wegpunkt falsch gespeichert");
        h.assertTrue(c.memory.waypoint("mine", h.getLevel()) != null, "Teilname findet den Wegpunkt nicht");

        // Speichern/Laden
        CompanionMemory copy = new CompanionMemory();
        copy.load(c.memory.save());
        h.assertTrue(copy.waypoint("eisen mine", h.getLevel()) != null, "Wegpunkt nach dem Laden weg");

        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        String go = german(() -> CompanionBrain.waypointCommand(p, c, tok("Geh zur Eisen Mine"), CompanionBrain.normalize("Geh zur Eisen Mine")));
        h.assertTrue(go != null && go.startsWith("Ich gehe"), "Hingehen: " + go);
        h.assertTrue(c.isStaying(), "Soll dort warten");
        h.succeedWhen(() -> h.assertTrue(c.blockPosition().distSqr(spot) <= 4, "Nicht beim Wegpunkt angekommen"));
    }

    @GameTest
    public void wegpunktAuflistenUndVergessen(GameTestHelper h) throws Exception {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        var p = owner(h, c);
        c.memory.setWaypoint("basis", h.getLevel(), h.absolutePos(new BlockPos(3, 1, 3)));
        String list = german(() -> CompanionBrain.waypointCommand(p, c, tok("Welche Wegpunkte kennst du"), "welche wegpunkte kennst du"));
        h.assertTrue(list != null && list.contains("basis"), "Liste: " + list);
        String forget = german(() -> CompanionBrain.waypointCommand(p, c, tok("Vergiss die Basis"), "vergiss die basis"));
        h.assertTrue(forget != null && forget.contains("vergessen"), "Vergessen: " + forget);
        h.assertTrue(c.memory.waypoints().isEmpty(), "Wegpunkt noch da");
        h.succeed();
    }

    // ------------------------------------------------------------------ 7. Ausrüstungsziele

    @GameTest
    public void ausruestungNaechstesStueck(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        h.assertValueEqual(CompanionJobs.nextGearPiece(c, 3, 0), "iron_sword", "Erst die Waffe");
        c.giveToInventory(new ItemStack(Items.IRON_SWORD));
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE)); // besser als Eisen zählt mit
        h.assertValueEqual(CompanionJobs.nextGearPiece(c, 3, 0), "iron_chestplate", "Dann der Brustpanzer");
        h.assertValueEqual(CompanionJobs.nextGearPiece(c, 3, 1 << 2), "iron_leggings", "Übersprungenes Stück");
        h.assertValueEqual(CompanionJobs.gearProgress(c, 3), "2/7", "Fortschritt");
        h.assertValueEqual(CompanionJobs.gearTarget(c, List.of("hol", "dir", "diamantruestung")), 4, "Diamant aus dem Satz");
        h.succeed();
    }

    @GameTest(maxTicks = 1600)
    public void ausruestungszielStelltEisenruestungHer(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 32));
        c.giveToInventory(new ItemStack(Items.STICK, 8));
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 16));
        c.giveToInventory(new ItemStack(Items.CRAFTING_TABLE));
        c.addTask(CompanionJobs.gearTask(3, 0, null, 0, 0));
        h.succeedWhen(() -> {
            h.assertTrue(c.currentTask() == null, "Ausrüsten läuft noch: " + (c.currentTask() == null ? "" : CompanionEntity.describe(c.currentTask())));
            h.assertTrue(CompanionJobs.nextGearPiece(c, 3, 0) == null, "Es fehlt noch " + CompanionJobs.nextGearPiece(c, 3, 0));
        });
    }

    // ------------------------------------------------------------------ Control Center

    @GameTest
    public void controlCenterKnoepfeFuerAlltag(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        var p = owner(h, c);
        c.openPanel(p, CompanionMenu.Page.TASKS);
        var menu = (CompanionMenu) p.containerMenu;
        h.assertTrue(menu.clickMenuButton(p, CompanionMenu.ACTION_BUTTON + 28), "Reparieren abgelehnt");
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == TaskType.REPAIR, "Reparieren nicht eingeplant");
        menu.clickMenuButton(p, CompanionMenu.ACTION_BUTTON + 27);
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == TaskType.BREED, "Züchten nicht eingeplant");
        menu.clickMenuButton(p, CompanionMenu.ACTION_BUTTON + 29);
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == TaskType.TRADE, "Handel nicht eingeplant");
        menu.clickMenuButton(p, CompanionMenu.STRONG_ACTION_BUTTON + 32);
        h.assertTrue(c.currentTask() != null && c.currentTask().spec() instanceof dev.begleiter.task.TaskSpec.GearUp gear
                && gear.tier() == 4, "Diamant-Ausrüstung nicht eingeplant");
        menu.clickMenuButton(p, CompanionMenu.ACTION_BUTTON + 30);
        menu.clickMenuButton(p, CompanionMenu.ACTION_BUTTON + 30);
        h.assertValueEqual(c.memory.waypoints().size(), 2, "Zwei Orte gemerkt");
        String prefix = CompanionActions.spotPrefix(p.clientInformation().language());
        h.assertTrue(c.memory.waypoint(prefix + " 2", h.getLevel()) != null, "Zweiter Ort fehlt");
        h.assertTrue(menu.clickMenuButton(p, CompanionMenu.ACTION_BUTTON + 31), "Wegpunkte abgelehnt");
        h.assertTrue(c.lastUiAction.contains(prefix + " 1"), "Liste: " + c.lastUiAction);
        h.assertValueEqual(CompanionActions.spotPrefix("de_de"), "ort", "Deutscher Name");
        h.assertValueEqual(CompanionActions.spotPrefix("en_us"), "spot", "Englischer Name");
        p.closeContainer();
        h.succeed();
    }

    // ------------------------------------------------------------------ Chat

    @GameTest
    public void chatVerstehtAlltagsBefehle(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        var p = owner(h, c);
        CompanionBrain.onChat(p, "@ Züchte 2 Kühe");
        h.assertTrue(c.currentTask() != null && c.currentTask().spec().equals(new TaskSpec.Breed("cow", 2)), "Züchten nicht verstanden");
        CompanionBrain.onChat(p, "@ Kauf mir 3 Bücher");
        h.assertTrue(c.currentTask() != null && c.currentTask().spec().equals(new TaskSpec.Trade("book", 3)), "Kaufen nicht verstanden");
        CompanionBrain.onChat(p, "@ Tausch Weizen gegen Smaragde");
        h.assertTrue(c.currentTask() != null && c.currentTask().spec() instanceof TaskSpec.Trade tr && tr.item() == null,
                "Verkaufen nicht verstanden");
        CompanionBrain.onChat(p, "@ Reparier dein Werkzeug");
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == TaskType.REPAIR, "Reparieren nicht verstanden");
        CompanionBrain.onChat(p, "@ Rüste dich aus");
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == TaskType.GEARUP, "Ausrüsten nicht verstanden");
        CompanionBrain.onChat(p, "@ Stopp");
        CompanionBrain.onChat(p, "@ Kümmer dich um das Lager");
        h.assertTrue(c.currentTask() == null || c.currentTask().type() != TaskType.BREED, "Lager als Kuh verstanden");
        h.succeed();
    }
}
