package dev.begleiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.task.TaskSpec;

/** GameTests für den Begleiter. Starten mit: .\gradlew runGameTest */
public class CompanionGameTests {

    /** Steinboden unter der ganzen Testfläche (8x8), damit der Begleiter laufen kann. */
    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
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

    // ------------------------------------------------------------------ Menü-Zugriffsgrenze

    @GameTest
    public void menueLehntFremdenBesitzerAb(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        var p = owner(h, c, GameType.SURVIVAL);
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        c.setOwner(java.util.UUID.randomUUID());
        h.assertTrue(!CompanionAccess.canManage(p, c), "Fremder Spieler darf verwalten");
        c.openPanel(p, CompanionMenu.Page.MAIN);
        h.assertTrue(p.containerMenu == p.inventoryMenu, "Fremder Spieler konnte Menü öffnen");
        h.succeed();
    }

    @GameTest
    public void menueBesitzerwechselSperrtAktionenUndInventar(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        var p = owner(h, c, GameType.SURVIVAL);
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        c.stay();
        c.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        c.openPanel(p, CompanionMenu.Page.INVENTORY);
        var inventoryMenu = (CompanionMenu) p.containerMenu;
        h.assertTrue(inventoryMenu.stillValid(p), "Eigenes Menü ungültig");
        var tasksMenu = new CompanionMenu(1, p.getInventory(), c, CompanionMenu.Page.TASKS);
        c.setOwner(java.util.UUID.randomUUID());
        tasksMenu.quickMoveStack(p, 0);
        h.assertTrue(c.isStaying(), "Ungültiger Zugriff hat Folgen ausgelöst");
        h.assertTrue(inventoryMenu.quickMoveStack(p, CompanionMenu.INVENTORY_FIRST).isEmpty(), "Ungültiger Shift-Transfer");
        inventoryMenu.clicked(CompanionMenu.INVENTORY_FIRST, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p);
        h.assertTrue(inventoryMenu.getCarried().isEmpty(), "Ungültiger Klick nahm Items auf");
        h.assertValueEqual(count(c.getInventory(), Items.DIAMOND), 5, "Inventar verändert");
        inventoryMenu.broadcastChanges();
        h.assertTrue(p.containerMenu == p.inventoryMenu, "Ungültiges Menü bleibt geöffnet");
        h.succeed();
    }

    @GameTest
    public void menueEntfernungUndEntfernterNpc(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        var p = owner(h, c, GameType.SURVIVAL);
        p.snapTo(c.getX() + 12, c.getY(), c.getZ(), 0, 0);
        h.assertTrue(!CompanionAccess.canManage(p, c), "Zwölf-Blöcke-Grenze nicht geprüft");
        c.openPanel(p, CompanionMenu.Page.MAIN);
        h.assertTrue(p.containerMenu == p.inventoryMenu, "Menü aus zu großer Distanz geöffnet");
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        c.openPanel(p, CompanionMenu.Page.MAIN);
        h.assertTrue(p.containerMenu instanceof CompanionMenu, "Gültiges Menü fehlt");
        var menu = p.containerMenu;
        c.discard();
        h.assertTrue(!menu.stillValid(p), "Entfernter NPC bleibt zugreifbar");
        menu.broadcastChanges();
        h.assertTrue(p.containerMenu == p.inventoryMenu, "Menü nach NPC-Entfernung offen");
        h.succeed();
    }

    @GameTest
    public void controlCenterSeitenwechselBehaeltCursorUndSitzung(GameTestHelper h) {
        var c = companion(h, new BlockPos(2, 1, 2));
        var p = owner(h, c, GameType.SURVIVAL);
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        c.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
        c.openPanel(p, CompanionMenu.Page.INVENTORY);
        var menu = (CompanionMenu) p.containerMenu;
        menu.clicked(CompanionMenu.INVENTORY_FIRST, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p);
        h.assertValueEqual(menu.getCarried().getCount(), 7, "Cursoraufnahme");
        h.assertTrue(menu.clickMenuButton(p, CompanionMenu.PAGE_BUTTON + CompanionMenu.Page.MAIN.id), "Seitenwechsel abgelehnt");
        h.assertTrue(p.containerMenu == menu, "Seitenwechsel ersetzt die Sitzung");
        h.assertValueEqual(menu.getCarried().getCount(), 7, "Cursor ging beim Wechsel verloren");
        h.assertTrue(!menu.getSlot(CompanionMenu.INVENTORY_FIRST).isActive(), "Inventar bleibt aktiv");
        menu.clicked(CompanionMenu.INVENTORY_FIRST, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p);
        h.assertValueEqual(menu.getCarried().getCount(), 7, "Versteckter Slot nimmt Cursor an");
        menu.clickMenuButton(p, CompanionMenu.PAGE_BUTTON + CompanionMenu.Page.INVENTORY.id);
        menu.clicked(CompanionMenu.INVENTORY_FIRST, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p);
        h.assertTrue(menu.getCarried().isEmpty(), "Zurücklegen fehlgeschlagen");
        h.assertValueEqual(count(c.getInventory(), Items.DIAMOND), 7, "Items verloren/dupliziert");
        p.closeContainer();
        h.succeed();
    }

    @GameTest
    public void controlCenterTransferUndSchliessenBehaltenItems(GameTestHelper h) {
        var c = companion(h, new BlockPos(2, 1, 2));
        var p = owner(h, c, GameType.SURVIVAL);
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        c.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 9));
        c.openPanel(p, CompanionMenu.Page.INVENTORY);
        var menu = (CompanionMenu) p.containerMenu;
        menu.quickMoveStack(p, CompanionMenu.INVENTORY_FIRST);
        h.assertValueEqual(count(p.getInventory(), Items.DIAMOND), 9, "Shift-Transfer zum Spieler");
        h.assertValueEqual(count(c.getInventory(), Items.DIAMOND), 0, "Transfer dupliziert");
        for (int i = CompanionMenu.PLAYER_FIRST; i < menu.slots.size(); i++)
            if (menu.getSlot(i).getItem().is(Items.DIAMOND)) { menu.quickMoveStack(p, i); break; }
        h.assertValueEqual(count(c.getInventory(), Items.DIAMOND), 9, "Shift-Transfer zum NPC");
        menu.clicked(CompanionMenu.INVENTORY_FIRST, 1, net.minecraft.world.inventory.ContainerInput.PICKUP, p);
        h.assertValueEqual(menu.getCarried().getCount(), 5, "Halber Stack am Cursor");
        p.closeContainer();
        h.assertValueEqual(count(c.getInventory(), Items.DIAMOND) + count(p.getInventory(), Items.DIAMOND), 9, "Schließen verliert Items");
        h.succeed();
    }

    @GameTest
    public void controlCenterAktionenPruefenSitzungUndParameter(GameTestHelper h) {
        var c = companion(h, new BlockPos(2, 1, 2));
        var p = owner(h, c, GameType.SURVIVAL);
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        c.stay();
        c.openPanel(p, CompanionMenu.Page.MAIN);
        var menu = (CompanionMenu) p.containerMenu;
        h.assertTrue(!menu.clickMenuButton(p, -1), "Ungültiger Button angenommen");
        h.assertTrue(!menu.clickMenuButton(p, Integer.MAX_VALUE), "Überlauf-Button angenommen");
        h.assertTrue(!menu.clickMenuButton(p, CompanionMenu.PAGE_BUTTON + 99), "Unbekannte Seite angenommen");
        h.assertTrue(menu.clickMenuButton(p, CompanionMenu.ACTION_BUTTON), "Folgen abgelehnt");
        h.assertTrue(!c.isStaying() && !c.isAutonomous(), "Folgen wirkt nicht");
        menu.clickMenuButton(p, CompanionMenu.ACTION_BUTTON + 1);
        h.assertTrue(c.isStaying(), "Warten wirkt nicht");
        c.setOwner(java.util.UUID.randomUUID());
        h.assertTrue(!menu.clickMenuButton(p, CompanionMenu.ACTION_BUTTON), "Besitzerwechsel ignoriert");
        h.assertTrue(c.isStaying(), "Ungültige Aktion ausgeführt");
        c.setOwner(p.getUUID());
        p.closeContainer();
        h.assertTrue(!menu.clickMenuButton(p, CompanionMenu.ACTION_BUTTON), "Geschlossene Sitzung akzeptiert");
        h.succeed();
    }

    @GameTest
    public void controlCenterAnzeigeItemsSindNichtEntnehmbar(GameTestHelper h) {
        var c = companion(h, new BlockPos(2, 1, 2));
        var p = owner(h, c, GameType.CREATIVE);
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        c.openPanel(p, CompanionMenu.Page.BUILD);
        var menu = (CompanionMenu) p.containerMenu;
        menu.clicked(10, 2, net.minecraft.world.inventory.ContainerInput.CLONE, p);
        h.assertTrue(menu.getCarried().isEmpty(), "Anzeigeitem konnte geklont werden");
        h.assertTrue(!menu.getSlot(10).mayPickup(p) && !menu.getSlot(10).mayPlace(new ItemStack(Items.DIRT)), "Anzeigeslot offen");
        h.assertTrue(c.build == null, "Klonen löst Bauauftrag aus");
        p.closeContainer();
        h.succeed();
    }

    @GameTest
    public void controlCenterSnapshotCodec(GameTestHelper h) {
        var c = companion(h, new BlockPos(2, 1, 2));
        var snapshot = CompanionSnapshot.capture(c, 17, CompanionMenu.Page.STATUS);
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            CompanionSnapshot.CODEC.encode(buf, snapshot);
            h.assertValueEqual(CompanionSnapshot.CODEC.decode(buf), snapshot, "Snapshot-Netzwerkformat");
        } finally { buf.release(); }
        h.succeed();
    }

    // ------------------------------------------------------------------ Sprache

    @GameTest
    public void chatWoerterWerdenErkannt(GameTestHelper h) {
        h.assertValueEqual(CompanionBrain.normalize("Bring mir Holz!"), "bring mir holz", "normalize");
        h.assertValueEqual(CompanionBrain.normalize("Räum die Kisten auf, Begleiter."),
                "raeum die kisten auf begleiter", "normalize Umlaute");
        h.assertValueEqual(Resources.byNoun(List.of("hol", "10", "eisen")), Resources.IRON, "Eisen");
        h.assertValueEqual(Resources.byNoun(List.of("bring", "mir", "holzstaemme")), Resources.WOOD, "Holz");
        h.assertValueEqual(Resources.byNoun(List.of("sammle", "kies")), Resources.GRAVEL, "Kies");
        h.assertValueEqual(Resources.mobFor(List.of("such", "ein", "schaf")), "minecraft:sheep", "Schaf");
        h.assertValueEqual(Resources.mobFor(List.of("hol", "die", "kuh")), "minecraft:cow", "Kuh");
        h.assertTrue(Resources.byNoun(List.of("folge", "mir")) == null, "\"folge mir\" ist keine Ressource");
        h.succeed();
    }

    // ------------------------------------------------------------------ Kern

    @GameTest
    public void kernErzeugtBegleiter(GameTestHelper h) {
        floor(h);
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CompanionMod.COMPANION_CORE));

        BlockPos ground = h.absolutePos(new BlockPos(2, 0, 2));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false);
        CompanionMod.COMPANION_CORE.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));

        h.assertEntityPresent(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        CompanionEntity c = h.getLevel().getEntitiesOfClass(CompanionEntity.class,
                new net.minecraft.world.phys.AABB(ground).inflate(2)).getFirst();
        h.assertTrue(c.isOwner(player), "Spieler ist nicht Besitzer");
        h.assertValueEqual(c.getCustomName().getString(), "Leak", "Name");
        h.assertTrue(player.getMainHandItem().isEmpty(), "Kern wurde nicht verbraucht");
        h.succeed();
    }

    // ------------------------------------------------------------------ Inventar / Lager

    @GameTest
    public void einlagernBehaeltAusruestung(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        Container box = chest(h, c, new BlockPos(3, 1, 3));
        c.giveToInventory(new ItemStack(Items.DIRT, 10));
        c.giveToInventory(new ItemStack(Items.DIAMOND_SWORD));
        c.giveToInventory(new ItemStack(Items.BREAD, 3));

        c.depositInto(box, false);

        h.assertValueEqual(count(box, Items.DIRT), 10, "Erde in der Kiste");
        h.assertValueEqual(c.findItem(Items.DIRT), -1, "Erde noch im Inventar");
        h.assertTrue(c.findItem(Items.DIAMOND_SWORD) >= 0, "Schwert wurde eingelagert");
        h.assertTrue(c.findItem(Items.BREAD) >= 0, "Essen wurde eingelagert");
        h.succeed();
    }

    @GameTest
    public void einlagernMitVorratBehaeltQuote(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        Container box = chest(h, c, new BlockPos(3, 1, 3));
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 40));

        c.depositInto(box, true);

        h.assertValueEqual(count(box, Items.COBBLESTONE), 8, "Bruchstein in der Kiste (40 - 32 Vorrat)");
        h.succeed();
    }

    @GameTest
    public void kistenSortierenFuehrtZusammen(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        Container a = chest(h, c, new BlockPos(2, 1, 4));
        Container b = chest(h, c, new BlockPos(5, 1, 4));
        a.setItem(0, new ItemStack(Items.STONE, 10));
        a.setItem(5, new ItemStack(Items.DIRT, 5));
        b.setItem(3, new ItemStack(Items.DIRT, 20));
        b.setItem(7, new ItemStack(Items.APPLE, 3));

        int n = c.storage.sortChests(h.getLevel(), h.absolutePos(new BlockPos(3, 1, 4)));

        h.assertValueEqual(n, 2, "Anzahl sortierter Kisten");
        List<ItemStack> all = new ArrayList<>();
        for (Container box : List.of(a, b))
            for (int i = 0; i < box.getContainerSize(); i++) if (!box.getItem(i).isEmpty()) all.add(box.getItem(i));
        h.assertValueEqual(all.size(), 3, "Stapel nach dem Sortieren");
        h.assertValueEqual(count(a, Items.DIRT) + count(b, Items.DIRT), 25, "Erde gesamt");
        h.assertValueEqual(count(a, Items.STONE) + count(b, Items.STONE), 10, "Stein gesamt");
        h.assertValueEqual(count(a, Items.APPLE) + count(b, Items.APPLE), 3, "Äpfel gesamt");
        h.succeed();
    }

    @GameTest
    public void speichernUndLaden(GameTestHelper h) {
        floor(h);
        ServerLevel level = h.getLevel();
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        c.setOwner(player.getUUID());
        c.setHome(h.absolutePos(new BlockPos(4, 1, 4)));
        c.stay();
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 12));
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 30));

        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        c.saveWithoutId(out);
        CompoundTag tag = out.buildResult();
        CompanionEntity copy = CompanionMod.COMPANION.create(level, EntitySpawnReason.LOAD);
        copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));

        h.assertTrue(copy.isOwner(player), "Besitzer ging verloren");
        h.assertValueEqual(copy.getHome(), c.getHome(), "Lager");
        h.assertTrue(copy.isStaying(), "Modus \"warten\" ging verloren");
        h.assertTrue(!copy.isAutonomous(), "Modus wurde autonom");
        h.assertValueEqual(count(copy.getInventory(), Items.IRON_INGOT), 12, "Eisen im Inventar");
        h.assertValueEqual(count(copy.getInventory(), Items.OAK_LOG), 30, "Holz im Inventar");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void nimmtNurWaffenInDieHand(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 16));
        c.giveToInventory(new ItemStack(Items.DIRT, 8));

        // autoEquip läuft alle 20 Ticks
        h.runAfterDelay(45, () -> {
            h.assertTrue(c.getMainHandItem().isEmpty(), "Block in der Hand: " + c.getMainHandItem());
            c.giveToInventory(new ItemStack(Items.IRON_SWORD));
            h.runAfterDelay(25, () -> {
                h.assertTrue(c.getMainHandItem().is(Items.IRON_SWORD), "Schwert nicht in der Hand: " + c.getMainHandItem());
                h.succeed();
            });
        });
    }

    @GameTest
    public void rechtsklickWechseltModusUndShiftOeffnetMenue(GameTestHelper h) {
        floor(h);
        var player = h.makeMockServerPlayerInLevel();
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.setOwner(player.getUUID());
        // Eine echte Interaktion findet in Reichweite statt; der Mock startet sonst am Weltursprung.
        player.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        boolean autoBefore = c.isAutonomous();

        var r = player.interactOn(c, InteractionHand.MAIN_HAND, c.position());
        h.assertTrue(r.consumesAction(), "Rechtsklick wurde nicht verarbeitet: " + r);
        h.assertTrue(c.isAutonomous() != autoBefore, "Modus nicht gewechselt");

        player.setShiftKeyDown(true);
        var r2 = player.interactOn(c, InteractionHand.MAIN_HAND, c.position());
        h.assertTrue(r2.consumesAction(), "Shift-Rechtsklick wurde nicht verarbeitet: " + r2);
        h.assertTrue(player.containerMenu != player.inventoryMenu, "Bedienfeld nicht geöffnet");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void hebtItemsBeimDrueberlaufenAuf(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        BlockPos at = h.absolutePos(new BlockPos(2, 1, 2));
        h.getLevel().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(h.getLevel(),
                at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5, new ItemStack(Items.COBBLESTONE, 5)));

        h.succeedWhen(() -> h.assertValueEqual(CompanionCraft.count(c, "#stone"), 5, "Bruchstein aufgehoben"));
    }

    @GameTest
    public void ignoriertItemsAufBlaettern(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        h.setBlock(5, 1, 5, Blocks.OAK_LEAVES);
        h.setBlock(5, 2, 5, Blocks.OAK_LEAVES);
        BlockPos at = h.absolutePos(new BlockPos(5, 3, 5));
        var ie = new net.minecraft.world.entity.item.ItemEntity(h.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                new ItemStack(Items.APPLE));
        ie.setNoGravity(true);
        h.getLevel().addFreshEntity(ie);
        h.assertTrue(!c.pickup.collectible(ie), "Apfel auf dem Baum gilt als erreichbar");
        h.assertTrue(!c.pickup.looseItemsNearby(), "Planer würde zum Apfel auf dem Baum laufen");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void bautZielSaeuleWiederAb(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 3));
        c.giveToInventory(new ItemStack(Items.DIRT, 4));
        // hohes Ziel direkt über ihm: Säule hoch (Pillar.TREE), danach wieder abbauen
        c.addTask(Task.climb(h.absolutePos(new BlockPos(3, 8, 3))));
        BlockPos top = h.absolutePos(new BlockPos(3, 3, 3));
        boolean[] wasUp = {false};
        h.onEachTick(() -> { if (c.blockPosition().getY() >= top.getY()) wasUp[0] = true; });

        h.succeedWhen(() -> {
            h.assertTrue(wasUp[0], "Nicht hochgebaut");
            h.assertTrue(!c.hasScaffold(), "Pfeiler noch gemerkt");
            for (int y = 1; y <= 2; y++)
                h.assertTrue(!h.getLevel().getBlockState(h.absolutePos(new BlockPos(3, y, 3))).is(Blocks.DIRT),
                        "Erdpfeiler steht noch (y=" + y + ")");
            h.assertValueEqual(CompanionCraft.count(c, "dirt"), 4, "Erde nicht zurück im Inventar");
        });
    }

    @GameTest(maxTicks = 400)
    public void normalerPfeilerBleibtStehen(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 3));
        c.giveToInventory(new ItemStack(Items.DIRT, 4));
        BlockPos top = h.absolutePos(new BlockPos(3, 3, 3));
        c.addTask(Task.pillar(top));
        h.succeedWhen(() -> {
            h.assertTrue(c.blockPosition().getY() >= top.getY(), "Nicht hochgebaut: " + c.blockPosition());
            h.assertTrue(!c.hasScaffold(), "Weg-Pfeiler als Gerüst gemerkt");
            h.assertBlockPresent(Blocks.DIRT, new BlockPos(3, 1, 3));
            h.assertBlockPresent(Blocks.DIRT, new BlockPos(3, 2, 3));
        });
    }

    @GameTest(maxTicks = 100)
    public void hebtAbgegebenesNichtSofortWiederAuf(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.DIRT, 4));
        c.giveTo(h.makeMockPlayer(GameType.SURVIVAL), null); // wirft die Erde vor sich ab
        h.runAfterDelay(60, () -> {
            h.assertValueEqual(CompanionCraft.count(c, "dirt"), 0, "Abgegebene Erde wieder eingesammelt");
            h.succeed();
        });
    }

    /** Lava vor dem Besitzer -> die Items landen auf sicherem Boden (seine Füße), nicht in der Lava. */
    @GameTest(maxTicks = 100)
    public void abgabeWirftNichtInLava(GameTestHelper h) {
        floor(h);
        h.setBlock(4, 0, 5, Blocks.LAVA); // Lavaloch direkt vor dem Besitzer (Blick nach Süden)
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        Player owner = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos feet = h.absolutePos(new BlockPos(4, 1, 4));
        owner.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
        c.giveToInventory(new ItemStack(Items.DIAMOND, 3));
        h.assertValueEqual(c.items.safeDropSpot(h.getLevel(), owner), feet, "Abwurfstelle");
        h.assertValueEqual(c.giveTo(owner, "item:minecraft:diamond", 3), 3, "Abgabe");
        h.runAfterDelay(40, () -> {
            int lying = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(feet).inflate(3), e -> e.getItem().is(Items.DIAMOND))
                    .stream().mapToInt(e -> e.getItem().getCount()).sum();
            h.assertValueEqual(lying, 3, "Diamanten verbrannt oder verschwunden");
            h.succeed();
        });
    }

    /** Nirgends sicherer Boden (nur Magma) -> nichts abwerfen, Items behalten. */
    @GameTest(maxTicks = 40)
    public void abgabeOhneSichereStelleBehaeltItems(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.MAGMA_BLOCK);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        Player owner = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos feet = h.absolutePos(new BlockPos(4, 1, 4));
        owner.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
        c.giveToInventory(new ItemStack(Items.DIAMOND, 3));
        h.assertValueEqual(c.giveTo(owner, "item:minecraft:diamond", 3), 0, "Trotz unsicherer Stelle abgeworfen");
        h.assertValueEqual(CompanionCraft.count(c, "diamond"), 3, "Items weg");
        h.succeed();
    }

    // ------------------------------------------------------------------ Überleben

    @GameTest(maxTicks = 200)
    public void setztFackelImDunkeln(GameTestHelper h) {
        // geschlossene Steinkammer: kein Himmels- und kein Blocklicht
        for (int x = 0; x < 6; x++)
            for (int y = 0; y <= 4; y++)
                for (int z = 0; z < 6; z++) {
                    boolean shell = x == 0 || x == 5 || y == 0 || y == 4 || z == 0 || z == 5;
                    h.setBlock(x, y, z, shell ? Blocks.STONE : Blocks.AIR);
                }
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.giveToInventory(new ItemStack(Items.COAL, 1));
        c.giveToInventory(new ItemStack(Items.STICK, 1));

        h.succeedWhen(() -> {
            boolean torch = false;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(1, 1, 1)), h.absolutePos(new BlockPos(4, 3, 4))))
                if (h.getLevel().getBlockState(p).is(Blocks.TORCH)) torch = true;
            h.assertTrue(torch, "Keine Fackel gesetzt");
            h.assertValueEqual(CompanionCraft.count(c, "torch"), 3, "Fackeln übrig (4 hergestellt, 1 gesetzt)");
        });
    }

    @GameTest(maxTicks = 200)
    public void ziehtSichBeiWenigLebenZurueck(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.setHealth(8f);
        var zombie = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, new BlockPos(5, 1, 5));
        c.setTarget(zombie);

        h.succeedWhen(() -> {
            h.assertTrue(c.isRetreating(), "Kein Rückzug bei " + (int) c.getHealth() + " Leben");
            h.assertTrue(c.getTarget() == null, "Greift trotz Rückzug an");
        });
    }

    @GameTest(maxTicks = 200)
    public void nimmtSchildGegenSkelettHoch(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.SHIELD));
        // auf einer Säule: er kommt nicht in Nahkampf-Reichweite, also bleibt der Schild oben
        for (int y = 1; y <= 4; y++) h.setBlock(6, y, 6, Blocks.STONE);
        var skeleton = h.spawn(net.minecraft.world.entity.EntityTypes.SKELETON, new BlockPos(6, 5, 6));
        c.setTarget(skeleton);

        h.succeedWhen(() -> {
            h.assertTrue(c.getOffhandItem().is(Items.SHIELD), "Schild nicht in der Nebenhand");
            h.assertTrue(c.isUsingItem(), "Schild nicht hochgenommen");
        });
    }

    @GameTest(maxTicks = 100)
    public void kernNachDemTodBehaeltErinnerung(GameTestHelper h) {
        floor(h);
        ServerLevel level = h.getLevel();
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        BlockPos home = h.absolutePos(new BlockPos(6, 1, 6));
        BlockPos house = h.absolutePos(new BlockPos(3, 1, 3));
        CompoundTag mem = new CompoundTag();
        mem.putLong("Home", home.asLong());
        mem.putLong("HouseOrigin", house.asLong());
        mem.putBoolean("HouseBuilt", true);
        c.loadMemory(mem);
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 5));

        c.hurtServer(level, level.damageSources().generic(), 1000f);

        h.runAfterDelay(5, () -> {
            var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(h.absolutePos(new BlockPos(1, 1, 1))).inflate(3));
            h.assertTrue(drops.stream().anyMatch(d -> d.getItem().is(Items.IRON_INGOT)), "Eisen nicht fallen gelassen");
            ItemStack core = drops.stream().map(net.minecraft.world.entity.item.ItemEntity::getItem)
                    .filter(s -> s.is(CompanionMod.COMPANION_CORE)).findFirst().orElse(ItemStack.EMPTY);
            h.assertTrue(!core.isEmpty(), "Kein Kern fallen gelassen");
            h.assertTrue(drops.stream().filter(d -> d.getItem().is(CompanionMod.COMPANION_CORE))
                    .allMatch(d -> d.getAge() == Short.MIN_VALUE), "Kern verschwindet nach 5 Minuten");

            Player player = h.makeMockPlayer(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, core.copy());
            BlockPos ground = h.absolutePos(new BlockPos(4, 0, 1));
            CompanionMod.COMPANION_CORE.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false)));
            CompanionEntity back = level.getEntitiesOfClass(CompanionEntity.class,
                    new net.minecraft.world.phys.AABB(ground).inflate(2), CompanionEntity::isAlive).getFirst();
            h.assertValueEqual(back.getHome(), home, "Lager vergessen");
            h.assertTrue(back.isHouseBuilt(), "Haus vergessen");
            h.assertValueEqual(back.houseInside(), house.offset(2, 1, 2), "Hausposition vergessen");
            h.succeed();
        });
    }

    /**
     * Ausrüstung fällt auch ohne Spielertreffer (Mob, Lava, Sturz) und ohne Haltbarkeitsschaden – bei einem neuen
     * Begleiter und bei einem aus einer alten Welt, die noch Drop-Chance 1.0 gespeichert hat.
     */
    @GameTest(maxTicks = 100)
    public void ausruestungFaelltBeimTodImmerUnbeschaedigt(GameTestHelper h) {
        floor(h);
        ServerLevel level = h.getLevel();
        CompanionEntity fresh = companion(h, new BlockPos(1, 1, 1));
        CompanionEntity old = companion(h, new BlockPos(6, 1, 6));
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values())
            old.setDropChance(slot, 1.0f);
        var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
        old.saveWithoutId(out);
        old.load(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), out.buildResult()));
        for (CompanionEntity c : List.of(fresh, old)) {
            c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            c.hurtServer(level, level.damageSources().generic(), 1000f);
        }
        h.runAfterDelay(5, () -> {
            for (BlockPos at : List.of(new BlockPos(1, 1, 1), new BlockPos(6, 1, 6))) {
                var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(h.absolutePos(at)).inflate(2)).stream()
                        .map(net.minecraft.world.entity.item.ItemEntity::getItem).toList();
                for (var item : List.of(Items.IRON_HELMET, Items.IRON_SWORD)) {
                    ItemStack stack = drops.stream().filter(s -> s.is(item)).findFirst().orElse(ItemStack.EMPTY);
                    h.assertTrue(!stack.isEmpty(), item + " bei " + at + " verloren");
                    h.assertValueEqual(stack.getDamageValue(), 0, item + " bei " + at + " beschädigt");
                }
            }
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ Wegplaner

    /** Unzerstörbarer Ring auf dem Rand der Testfläche (x/z = 0 und 7), damit er nicht außen herum laufen kann. */
    private static void ring(GameTestHelper h, int fromY) {
        for (int i = 0; i <= 7; i++)
            for (int y = fromY; y <= 7; y++) {
                h.setBlock(i, y, 0, Blocks.BARRIER);
                h.setBlock(i, y, 7, Blocks.BARRIER);
                h.setBlock(0, y, i, Blocks.BARRIER);
                h.setBlock(7, y, i, Blocks.BARRIER);
            }
    }

    @GameTest(maxTicks = 1000)
    public void bautBrueckeUeberSchlucht(GameTestHelper h) {
        // Boden auf Höhe 3, Schlucht x=3..4 bis ganz nach unten (tiefer als 3 Blöcke)
        for (int x = 1; x <= 6; x++)
            for (int z = 1; z <= 6; z++) if (x < 3 || x > 4) h.setBlock(x, 3, z, Blocks.STONE);
        ring(h, 0);
        CompanionEntity c = companion(h, new BlockPos(1, 4, 3));
        c.stay(); // nicht selbstständig losplanen
        c.giveToInventory(new ItemStack(Items.DIRT, 16));
        BlockPos goal = h.absolutePos(new BlockPos(6, 4, 3));
        c.addTask(Task.moveTo(goal, 2));

        h.succeedWhen(() -> {
            h.assertTrue(c.blockPosition().distSqr(goal) <= 2,
                    "Nicht drüben (bei " + c.blockPosition().toShortString() + ", Ziel " + goal.toShortString() + ", Aufgabe " + c.currentTaskText() + ")");
            h.assertTrue(CompanionCraft.count(c, "dirt") <= 14, "Keine Brücke gebaut");
        });
    }

    @GameTest(maxTicks = 1000)
    public void bautSichAufPlateauHoch(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        for (int x = 5; x <= 6; x++)
            for (int y = 1; y <= 3; y++)
                for (int z = 1; z <= 6; z++) h.setBlock(x, y, z, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        c.stay();
        c.giveToInventory(new ItemStack(Items.DIRT, 8));
        BlockPos goal = h.absolutePos(new BlockPos(5, 4, 3));
        c.addTask(Task.moveTo(goal, 2));

        h.succeedWhen(() -> h.assertTrue(c.blockPosition().distSqr(goal) <= 2,
                "Nicht oben (bei " + c.blockPosition().toShortString() + ", Ziel " + goal.toShortString() + ", Aufgabe " + c.currentTaskText() + ")"));
    }

    @GameTest(maxTicks = 1000)
    public void graebtSichDurchErdwand(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        for (int y = 1; y <= 7; y++)
            for (int z = 1; z <= 6; z++) h.setBlock(4, y, z, Blocks.DIRT);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        c.stay();
        BlockPos goal = h.absolutePos(new BlockPos(6, 1, 3));
        c.addTask(Task.moveTo(goal, 2));

        h.succeedWhen(() -> h.assertTrue(c.blockPosition().distSqr(goal) <= 2,
                "Nicht durch (bei " + c.blockPosition().toShortString() + ", Ziel " + goal.toShortString() + ", Aufgabe " + c.currentTaskText() + ")"));
    }

    // ------------------------------------------------------------------ Nether-Vorbereitung

    @GameTest(maxTicks = 800)
    public void wasserAufLavaGibtObsidian(GameTestHelper h) {
        floor(h);
        BlockPos lava = new BlockPos(5, 0, 5);
        h.setBlock(lava, Blocks.LAVA);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        c.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        c.addTask(Task.gather(Resources.OBSIDIAN.key(), 1));

        h.succeedWhen(() -> {
            h.assertTrue(c.findItem(Items.OBSIDIAN) >= 0, "Kein Obsidian (Aufgabe " + c.currentTaskText()
                    + ", Block " + h.getLevel().getBlockState(h.absolutePos(lava)).getBlock() + ")");
            h.assertTrue(c.findItem(Items.WATER_BUCKET) >= 0, "Wassereimer weg");
        });
    }

    @GameTest(maxTicks = 800)
    public void bautUndZuendetNetherportal(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(6, 1, 6));
        c.giveToInventory(new ItemStack(Items.OBSIDIAN, 10));
        c.giveToInventory(new ItemStack(Items.FLINT_AND_STEEL));
        BlockPos site = h.absolutePos(new BlockPos(1, 1, 1));
        c.addTask(Task.portal(site));

        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getBlockState(CompanionPortals.inside(site).getFirst()).is(Blocks.NETHER_PORTAL),
                    "Kein Portal (Aufgabe " + c.currentTaskText() + ", Obsidian übrig " + CompanionCraft.count(c, "obsidian") + ")");
            h.assertValueEqual(c.getPortalPos(), site, "Portal nicht gemerkt");
        });
    }

    // ------------------------------------------------------------------ Nether-Ausflug

    @GameTest
    public void trefferZaehltFuerBesitzer(GameTestHelper h) {
        floor(h);
        var player = h.makeMockServerPlayerInLevel();
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.setOwner(player.getUUID());
        var zombie = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, new BlockPos(2, 1, 1));

        c.doHurtTarget(h.getLevel(), zombie);

        h.assertTrue(zombie.getLastHurtByPlayer() == player, "Treffer zählt nicht für den Besitzer (Beute wie Lohenruten fehlt sonst)");
        h.succeed();
    }

    @GameTest(maxTicks = 800)
    public void kaempftGegenLohe(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) h.setBlock(x, 4, z, Blocks.BARRIER); // Decke: Lohe bleibt unten
        var player = h.makeMockServerPlayerInLevel();
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.setOwner(player.getUUID());
        c.stay();
        c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        var blaze = h.spawn(net.minecraft.world.entity.EntityTypes.BLAZE, new BlockPos(5, 1, 5));
        c.addTask(Task.fight(null, "blaze", null, 1));

        h.succeedWhen(() -> h.assertTrue(!blaze.isAlive(), "Lohe lebt noch (Leben " + (int) blaze.getHealth()
                + ", Aufgabe " + c.currentTaskText() + ")"));
    }

    // ------------------------------------------------------------------ Festung und Endportal

    @GameTest
    public void stelltEnderaugenHer(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.BLAZE_ROD, 1));
        c.giveToInventory(new ItemStack(Items.ENDER_PEARL, 2));

        h.assertTrue(CompanionCraft.craft(c, "ender_eye", 2), "Craften fehlgeschlagen: " + c.lastCraftProblem);
        h.assertValueEqual(CompanionCraft.count(c, "ender_eye"), 2, "Enderaugen");
        h.assertValueEqual(CompanionCraft.count(c, "blaze_rod"), 0, "Lohenrute nicht zu Staub verarbeitet");
        h.assertValueEqual(CompanionCraft.count(c, "ender_pearl"), 0, "Enderperlen übrig");
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void aktiviertEndportal(GameTestHelper h) {
        floor(h);
        // 5x5-Ring aus 12 Rahmen (ohne Ecken), alle nach innen gerichtet
        for (int i = 2; i <= 4; i++) {
            frame(h, new BlockPos(i, 1, 1), Direction.SOUTH);
            frame(h, new BlockPos(i, 1, 5), Direction.NORTH);
            frame(h, new BlockPos(1, 1, i), Direction.EAST);
            frame(h, new BlockPos(5, 1, i), Direction.WEST);
        }
        CompanionEntity c = companion(h, new BlockPos(6, 1, 6));
        c.stay();
        c.giveToInventory(new ItemStack(Items.ENDER_EYE, 12));
        c.endRoomPos = h.absolutePos(new BlockPos(3, 1, 3));
        c.addTask(Task.stronghold());

        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(3, 1, 3))).is(Blocks.END_PORTAL),
                    "Kein Endportal (Augen übrig " + CompanionCraft.count(c, "ender_eye") + ", Aufgabe " + c.currentTaskText() + ")");
            h.assertTrue(c.endPortalPos != null, "Portal nicht gemerkt");
        });
    }

    private static void frame(GameTestHelper h, BlockPos p, Direction facing) {
        h.setBlock(p, Blocks.END_PORTAL_FRAME.defaultBlockState()
                .setValue(net.minecraft.world.level.block.EndPortalFrameBlock.FACING, facing));
    }

    // ------------------------------------------------------------------ Fernkampf und End

    @GameTest
    public void stelltBogenUndPfeileHer(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.STRING, 3));
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 4));
        c.giveToInventory(new ItemStack(Items.FLINT, 2));
        c.giveToInventory(new ItemStack(Items.FEATHER, 2));

        h.assertTrue(CompanionCraft.craft(c, "bow", 1), "Bogen: " + c.lastCraftProblem);
        h.assertTrue(CompanionCraft.craft(c, "arrow", 8), "Pfeile: " + c.lastCraftProblem);
        h.assertValueEqual(CompanionCraft.count(c, "arrow"), 8, "Pfeile");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void pfeilTrifftUndZaehltFuerBesitzer(GameTestHelper h) {
        floor(h);
        var player = h.makeMockServerPlayerInLevel();
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.setOwner(player.getUUID());
        c.giveToInventory(new ItemStack(Items.BOW));
        c.giveToInventory(new ItemStack(Items.ARROW, 4));
        var zombie = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, new BlockPos(1, 1, 6));
        zombie.setNoAi(true);

        h.runAfterDelay(2, () -> h.assertTrue(c.shootArrow(zombie), "Pfeil nicht geschossen"));
        h.succeedWhen(() -> {
            h.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "Zombie nicht getroffen");
            h.assertTrue(zombie.getLastHurtByPlayer() == player, "Pfeiltreffer zählt nicht für den Besitzer");
            h.assertValueEqual(CompanionCraft.count(c, "arrow"), 3, "Pfeil nicht verbraucht");
        });
    }

    @GameTest(maxTicks = 400)
    public void schiesstEndkristallAb(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        c.giveToInventory(new ItemStack(Items.BOW));
        c.giveToInventory(new ItemStack(Items.ARROW, 16));
        h.setBlock(6, 1, 6, Blocks.OBSIDIAN);
        BlockPos at = h.absolutePos(new BlockPos(6, 2, 6));
        var crystal = new net.minecraft.world.entity.boss.enderdragon.EndCrystal(h.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        crystal.setShowBottom(false);
        h.getLevel().addFreshEntity(crystal);
        c.addTask(Task.crystals());

        h.succeedWhen(() -> h.assertTrue(!crystal.isAlive(), "Kristall steht noch (Pfeile " + CompanionCraft.count(c, "arrow") + ")"));
    }

    @GameTest(maxTicks = 100)
    public void laesstEndermenInRuhe(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        var enderman = h.spawn(net.minecraft.world.entity.EntityTypes.ENDERMAN, new BlockPos(4, 1, 4));
        enderman.setNoAi(true);
        h.runAfterDelay(60, () -> {
            h.assertTrue(c.getTarget() == null, "Greift Enderman von sich aus an");
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ Bauen nach Plan (B1/B2)

    private static net.minecraft.server.level.ServerPlayer owner(GameTestHelper h, CompanionEntity c, GameType mode) {
        var p = h.makeMockServerPlayerInLevel(); // Testspieler ist immer Kreativ -> Modus am Begleiter festlegen
        c.buildModeOverride = mode == GameType.CREATIVE;
        c.setOwner(p.getUUID());
        return p;
    }

    private static void startBuild(GameTestHelper h, CompanionEntity c, String name, Map<BlockPos, BlockState> rel) {
        Map<BlockPos, BlockState> abs = new java.util.HashMap<>();
        rel.forEach((p, s) -> abs.put(h.absolutePos(p), s));
        c.build = CompanionBuilder.create(name, abs);
        c.addTask(Task.build());
    }

    // ------------------------------------------------------------------ Wasser: schwimmen, tauchen, rauskommen

    /** Becken: Steinwände x/z = 0 und 7, Wasser innen bis y = depth. */
    private static void pool(GameTestHelper h, int depth) {
        floor(h);
        for (int y = 1; y <= depth + 1; y++)
            for (int i = 0; i < 8; i++) {
                h.setBlock(i, y, 0, Blocks.STONE); h.setBlock(i, y, 7, Blocks.STONE);
                h.setBlock(0, y, i, Blocks.STONE); h.setBlock(7, y, i, Blocks.STONE);
            }
        for (int y = 1; y <= depth; y++)
            for (int x = 1; x < 7; x++) for (int z = 1; z < 7; z++) h.setBlock(x, y, z, Blocks.WATER);
    }

    @GameTest
    public void tiefesErzNurImNotfall(GameTestHelper h) {
        pool(h, 4);
        h.setBlock(new BlockPos(5, 1, 5), Blocks.IRON_ORE);
        CompanionEntity c = companion(h, new BlockPos(2, 4, 2));
        c.stay();
        h.runAfterDelay(20, () -> {
            h.assertTrue(c.buildGather(Resources.IRON.block(), 1, false).isEmpty(), "Taucht ohne Not nach Erz");
            h.assertTrue(!c.buildGather(Resources.IRON.block(), 1, false, true).isEmpty(), "Im Notfall kein Erz unter Wasser");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 600)
    public void bautErzImFlachenWasserAb(GameTestHelper h) {
        pool(h, 2);
        h.setBlock(new BlockPos(5, 1, 5), Blocks.IRON_ORE); // eine Schicht Wasser darüber: Kopf bleibt draußen
        CompanionEntity c = companion(h, new BlockPos(2, 2, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        c.addTask(Task.gather(Resources.IRON.key(), 1));
        h.succeedWhen(() -> h.assertBlockNotPresent(Blocks.IRON_ORE, new BlockPos(5, 1, 5)));
    }

    @GameTest(maxTicks = 600)
    public void tauchtImNotfallZumErzAmSeegrund(GameTestHelper h) {
        pool(h, 4);
        h.setBlock(new BlockPos(5, 1, 5), Blocks.IRON_ORE);
        CompanionEntity c = companion(h, new BlockPos(2, 4, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        c.addTask(new Task(new TaskSpec.Mine(Resources.IRON.key(), 1), 10)); // lange gegraben = Notfall
        h.succeedWhen(() -> {
            h.assertBlockNotPresent(Blocks.IRON_ORE, new BlockPos(5, 1, 5));
            h.assertTrue(c.findItem(Items.RAW_IRON) >= 0 || !h.getLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO))
                            .inflate(10)).isEmpty(), "Kein Roheisen");
        });
    }

    @GameTest(maxTicks = 400)
    public void tauchtZumLuftholenAuf(GameTestHelper h) {
        pool(h, 4);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 3));
        c.stay();
        c.setAirSupply(40);
        h.succeedWhen(() -> {
            h.assertTrue(c.getAirSupply() >= 250, "Keine Luft geholt: " + c.getAirSupply());
            h.assertTrue(c.getHealth() >= c.getMaxHealth() - 2, "Ertrinkt fast");
        });
    }

    @GameTest(maxTicks = 600)
    public void schwimmtGegenDieStroemungAnsUfer(GameTestHelper h) {
        floor(h);
        // Kanal z = 3, Wände z = 2/4, Quelle bei x = 5 fließt nach x = 1; Ufer-Stufe bei x = 6
        for (int x = 0; x < 8; x++) {
            h.setBlock(x, 1, 2, Blocks.STONE); h.setBlock(x, 2, 2, Blocks.STONE);
            h.setBlock(x, 1, 4, Blocks.STONE); h.setBlock(x, 2, 4, Blocks.STONE);
        }
        h.setBlock(0, 1, 3, Blocks.STONE);
        h.setBlock(6, 1, 3, Blocks.STONE);
        h.setBlock(5, 1, 3, Blocks.WATER);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        c.stay();
        h.runAfterDelay(20, () -> c.addTask(Task.moveTo(h.absolutePos(new BlockPos(6, 2, 3)), 1)));
        h.succeedWhen(() -> {
            h.assertTrue(c.blockPosition().equals(h.absolutePos(new BlockPos(6, 2, 3))) || c.getY() >= h.absolutePos(BlockPos.ZERO).getY() + 2
                    && c.getX() >= h.absolutePos(BlockPos.ZERO).getX() + 5.5, "Nicht am Ufer: " + c.blockPosition());
        });
    }

    @GameTest(maxTicks = 800)
    public void kommtAusDerEckeEinesBeckens(GameTestHelper h) {
        floor(h);
        // Becken mit hohen Wänden (x/z 1..6), Wasser innen 2 tief; Ziel draußen in der Ecke
        for (int y = 1; y <= 3; y++)
            for (int i = 1; i <= 6; i++) {
                h.setBlock(i, y, 1, Blocks.STONE); h.setBlock(i, y, 6, Blocks.STONE);
                h.setBlock(1, y, i, Blocks.STONE); h.setBlock(6, y, i, Blocks.STONE);
            }
        for (int y = 1; y <= 2; y++) for (int x = 2; x <= 5; x++) for (int z = 2; z <= 5; z++) h.setBlock(x, y, z, Blocks.WATER);
        CompanionEntity c = companion(h, new BlockPos(2, 2, 2));
        c.stay();
        BlockPos goal = h.absolutePos(new BlockPos(0, 1, 0));
        h.runAfterDelay(10, () -> c.addTask(Task.moveTo(goal, 4)));
        h.succeedWhen(() -> h.assertTrue(c.distanceToSqr(Vec3.atCenterOf(goal)) < 9 && !c.isInWater(),
                "Hängt im Becken: " + c.blockPosition() + " Wasser " + c.isInWater()));
    }

    @GameTest
    public void graebtUndBautSichAusDemWasser(GameTestHelper h) {
        pool(h, 2);
        ServerLevel sl = h.getLevel();
        // Wand in Zielrichtung: rausgraben
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        c.stay();
        c.swim.escape(sl, h.absolutePos(new BlockPos(-5, 1, 3)));
        h.assertBlockNotPresent(Blocks.STONE, new BlockPos(0, 1, 3));
        h.assertBlockNotPresent(Blocks.STONE, new BlockPos(0, 2, 3));
        // Offenes Wasser: Block setzen und draufsteigen
        CompanionEntity d = companion(h, new BlockPos(4, 2, 4));
        d.stay();
        d.giveToInventory(new ItemStack(Items.DIRT, 4));
        d.swim.escape(sl, h.absolutePos(new BlockPos(4, 2, 1)));
        h.assertTrue(sl.getBlockState(h.absolutePos(new BlockPos(4, 1, 4))).is(Blocks.DIRT)
                || sl.getBlockState(h.absolutePos(new BlockPos(4, 2, 4))).is(Blocks.DIRT), "Kein Block zum Rausbauen gesetzt");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void gibtUnerreichbaresKampfzielAuf(GameTestHelper h) {
        floor(h);
        // Zombie in einem Glaskasten: sichtbar, aber nicht erreichbar
        for (int x = 4; x <= 6; x++) for (int y = 1; y <= 3; y++) for (int z = 4; z <= 6; z++)
            if (x != 5 || z != 5 || y == 3) h.setBlock(x, y, z, Blocks.GLASS);
        var zombie = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, new BlockPos(5, 1, 5));
        zombie.setPersistenceRequired();
        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); // kein Sonnenbrand
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        c.setTarget(zombie);
        h.succeedWhen(() -> {
            h.assertTrue(c.isIgnoredTarget(zombie), "Hält am unerreichbaren Ziel fest");
            h.assertTrue(c.getTarget() != zombie, "Ziel nicht aufgegeben");
        });
    }

    @GameTest
    public void graebtNichtDurchSeineKiste(GameTestHelper h) {
        floor(h);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) for (int y = 1; y <= 3; y++)
            if (x > 2) h.setBlock(x, y, z, Blocks.STONE);
        h.setBlock(new BlockPos(4, 1, 3), Blocks.CHEST); // im Weg der Treppe nach Osten
        CompanionEntity c = companion(h, new BlockPos(2, 1, 3));
        c.stay();
        h.runAfterDelay(5, () -> {
            var dig = c.gathering.tunnel(Direction.EAST, 6);
            BlockPos chest = h.absolutePos(new BlockPos(4, 1, 3));
            h.assertTrue(dig.stream().anyMatch(t -> t.type() == TaskType.BREAK), "Gräbt gar nicht (Test kaputt)");
            h.assertTrue(dig.stream().noneMatch(t -> chest.equals(t.pos())), "Plant die Kiste wegzugraben");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 600, structure = "begleiter-gametest:herd_isolation")
    public void stelltWerkbankAufUndPacktSieEin(GameTestHelper h) {
        // Stationssuche (24) und Itemsuche (40) bleiben vollständig in der eigenen Testfläche.
        for (int x = 46; x <= 53; x++)
            for (int z = 46; z <= 53; z++) h.setBlock(x, 0, z, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(48, 1, 48));
        c.stay();
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 7));
        c.giveToInventory(new ItemStack(Items.STICK, 2));
        c.addTask(Task.craft("wooden_pickaxe", 1));
        h.succeedWhen(() -> {
            h.assertTrue(c.findItem(Items.WOODEN_PICKAXE) >= 0, "Keine Spitzhacke");
            h.assertTrue(c.findItem(Items.CRAFTING_TABLE) >= 0, "Werkbank nicht wieder eingepackt");
            h.assertTrue(c.findStation("crafting_table", 8) == null, "Werkbank steht noch");
        });
    }

    @GameTest(maxTicks = 600)
    public void nimmtErzImVorbeigehenMit(GameTestHelper h) {
        floor(h);
        h.setBlock(new BlockPos(5, 1, 5), Blocks.STONE);
        h.setBlock(new BlockPos(5, 1, 3), Blocks.COAL_ORE);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        c.addTask(Task.gather(Resources.STONE.key(), 1));
        h.succeedWhen(() -> h.assertBlockNotPresent(Blocks.COAL_ORE, new BlockPos(5, 1, 3)));
    }

    @GameTest(maxTicks = 600)
    public void nimmtErzUnterwegsMit(GameTestHelper h) {
        floor(h);
        // Erz erst am Ende des Weges in Reichweite (am Start > 5 Blöcke weg)
        h.setBlock(new BlockPos(7, 1, 3), Blocks.IRON_ORE);
        CompanionEntity c = companion(h, new BlockPos(0, 1, 0));
        c.stay();
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        c.addTask(Task.moveTo(h.absolutePos(new BlockPos(7, 1, 0)), 1));
        c.addTask(Task.mine(Resources.DIAMOND.key(), 0)); // "ist am Graben"
        h.succeedWhen(() -> h.assertBlockNotPresent(Blocks.IRON_ORE, new BlockPos(7, 1, 3)));
    }

    @GameTest(maxTicks = 600)
    public void wirftMuellWegFuerErz(GameTestHelper h) {
        floor(h);
        h.setBlock(new BlockPos(4, 1, 4), Blocks.IRON_ORE);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        c.giveToInventory(new ItemStack(Items.ROTTEN_FLESH, 5));
        while (c.hasFreeSlot()) c.giveToInventory(new ItemStack(Items.COBBLESTONE, 64));
        c.addTask(Task.breakAt(h.absolutePos(new BlockPos(4, 1, 4)), Blocks.IRON_ORE));
        h.succeedWhen(() -> {
            h.assertTrue(CompanionCraft.count(c, "raw_iron") >= 1, "Kein Roheisen");
            h.assertValueEqual(CompanionCraft.count(c, "rotten_flesh"), 0, "Abfall nicht weggeworfen");
            h.assertTrue(CompanionCraft.count(c, "cobblestone") >= 32, "Gerüst-Vorrat weggeworfen");
        });
    }

    /** Zwei massive Plateaus (bis y=3) mit 2 Blöcken Lücke, darunter 3 tief eine Grube; keine Blöcke für eine Brücke dabei. */
    private static CompanionEntity parkourCourse(GameTestHelper h) {
        floor(h);
        for (int x = 0; x < 8; x++)
            for (int y = 1; y <= 3; y++)
                for (int z = 0; z < 8; z++)
                    if (x != 2 && x != 3) h.setBlock(x, y, z, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(0, 4, 3));
        c.stay();
        c.addTask(Task.moveTo(h.absolutePos(new BlockPos(6, 4, 3)), 2));
        return c;
    }

    @GameTest(maxTicks = 600)
    public void springtUeberLuecke(GameTestHelper h) {
        CompanionEntity c = parkourCourse(h);
        h.succeedWhen(() -> {
            BlockPos p = c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO));
            h.assertTrue(p.getX() >= 4 && p.getY() == 4, "Nicht rübergesprungen: " + p);
        });
    }

    @GameTest(maxTicks = 400)
    public void springtNichtUeberLava(GameTestHelper h) {
        CompanionEntity c = parkourCourse(h);
        for (int x = 2; x <= 3; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 1, z, Blocks.LAVA);
        h.runAtTickTime(300, () -> {
            BlockPos p = c.blockPosition().subtract(h.absolutePos(BlockPos.ZERO));
            h.assertTrue(p.getX() < 2 && p.getY() == 4, "Über Lava gesprungen oder reingefallen: " + p);
            h.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void merktSichKisteNachEinlagern(GameTestHelper h) {
        floor(h);
        h.setBlock(new BlockPos(3, 1, 3), Blocks.CHEST);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        c.stay();
        BlockPos chest = h.absolutePos(new BlockPos(3, 1, 3));
        c.setHome(chest);
        c.giveToInventory(new ItemStack(Items.RAW_GOLD, 7));
        c.addTask(Task.deliver(false));
        h.succeedWhen(() -> {
            h.assertValueEqual(CompanionCraft.count(c, "raw_gold"), 0, "Nicht eingelagert");
            CompanionMemory.Place p = c.memory.chestWith(Items.RAW_GOLD, h.getLevel(), chest, 4);
            h.assertTrue(p != null && p.itemCount(Items.RAW_GOLD) == 7, "Kisteninhalt nicht gemerkt: " + (p == null ? null : p.items));
        });
    }

    @GameTest(maxTicks = 3600)
    public void bautEimerHoltWasserFuerObsidian(GameTestHelper h) {
        floor(h);
        // Eimer-Herstellung lokal halten: Nachbartests können unerreichbare Werkbänke im normalen
        // Suchradius für Stationen zeigen. Das Aufstellen der Werkbank wird getrennt geprüft.
        h.setBlock(new BlockPos(2, 1, 1), Blocks.CRAFTING_TABLE);
        for (int x = 3; x <= 5; x++)
            for (int y = 1; y <= 2; y++)
                for (int z = 3; z <= 5; z++) h.setBlock(x, y, z, Blocks.STONE);
        h.setBlock(new BlockPos(4, 1, 4), Blocks.LAVA);
        // Wasserquelle in einer Mulde im Boden
        h.setBlock(new BlockPos(1, 0, 6), Blocks.WATER);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 3));
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 8));
        h.runAtTickTime(120, () -> c.addTask(Task.gather(Resources.OBSIDIAN.key(), 1)));
        h.succeedWhen(() -> {
            h.assertTrue(CompanionCraft.count(c, "obsidian") >= 1, "Kein Obsidian");
            h.assertTrue(CompanionCraft.owns(c, "water_bucket") || CompanionCraft.owns(c, "bucket"), "Kein Eimer gebaut");
        });
    }

    @GameTest(maxTicks = 1600)
    public void machtObsidianAusGespuerterLava(GameTestHelper h) {
        floor(h);
        // Lava-Quelle rundum in Stein eingeschlossen (nicht sichtbar, nur zu spüren)
        for (int x = 3; x <= 5; x++)
            for (int y = 1; y <= 2; y++)
                for (int z = 3; z <= 5; z++) h.setBlock(x, y, z, Blocks.STONE);
        h.setBlock(new BlockPos(4, 1, 4), Blocks.LAVA);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        c.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        // erst spüren lassen (eine Runde Erz-/Lava-Gespür), dann Obsidian holen
        h.runAtTickTime(120, () -> c.addTask(Task.gather(Resources.OBSIDIAN.key(), 1)));
        h.succeedWhen(() -> h.assertTrue(CompanionCraft.count(c, "obsidian") >= 1, "Kein Obsidian"));
    }

    @GameTest
    public void vorratZaehltNichtAlsEinlagern(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 10));   // Holz-Vorrat
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 20)); // Stein-Vorrat
        c.giveToInventory(new ItemStack(Items.COAL, 5));        // Kohle-Vorrat
        for (int i = 0; i < 28; i++) c.giveToInventory(new ItemStack(Items.TORCH, 64));
        h.assertValueEqual(c.depositableSlots(), 0, "Nur Vorrat/Ausrüstung");
        c.giveToInventory(new ItemStack(Items.RAW_GOLD, 3));
        h.assertValueEqual(c.depositableSlots(), 1, "Rohgold einlagern");
        h.succeed();
    }

    private static void fillChest(GameTestHelper h, CompanionEntity c, BlockPos rel) {
        net.minecraft.world.Container box = c.containerAt(h.getLevel(), h.absolutePos(rel));
        for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, new ItemStack(Items.DIRT, 64));
    }

    @GameTest(maxTicks = 400)
    public void volleKisteWirdDoppelkiste(GameTestHelper h) {
        floor(h);
        h.setBlock(new BlockPos(3, 1, 3), Blocks.CHEST);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 1));
        c.stay();
        BlockPos chest = h.absolutePos(new BlockPos(3, 1, 3));
        c.setHome(chest);
        fillChest(h, c, new BlockPos(3, 1, 3));
        c.giveToInventory(new ItemStack(Items.RAW_GOLD, 7));
        c.giveToInventory(new ItemStack(Items.CHEST));
        c.addTask(Task.deliver(false));
        h.succeedWhen(() -> {
            h.assertValueEqual(CompanionCraft.count(c, "raw_gold"), 0, "Nicht eingelagert");
            net.minecraft.world.Container box = c.containerAt(h.getLevel(), chest);
            h.assertValueEqual(box.getContainerSize(), 54, "Keine Doppelkiste");
        });
    }

    /** Aufsammeln beachtet die Abhebeverzögerung (frisch geworfene Items) wie ein Spieler. */
    @GameTest(maxTicks = 200)
    public void aufsammelnWartetAbhebeverzoegerungAb(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 3));
        c.stay();
        var item = new net.minecraft.world.entity.item.ItemEntity(h.getLevel(), c.getX(), c.getY(), c.getZ(), new ItemStack(Items.EMERALD));
        item.setPickUpDelay(60);
        h.getLevel().addFreshEntity(item);
        c.addTask(Task.collect(true));
        h.runAtTickTime(30, () -> h.assertTrue(item.isAlive() && CompanionCraft.count(c, "emerald") == 0,
                "Item trotz Abhebeverzögerung aufgehoben"));
        h.runAfterDelay(31, () -> h.succeedWhen(() ->
                h.assertValueEqual(CompanionCraft.count(c, "emerald"), 1, "Nach der Verzögerung nicht aufgehoben")));
    }

    @GameTest(maxTicks = 400)
    public void volleDoppelkisteNeueKiste(GameTestHelper h) {
        floor(h);
        // Doppelkiste nach Norden: linke Hälfte (3,1,3), rechte im Uhrzeigersinn daneben (4,1,3)
        h.setBlock(new BlockPos(3, 1, 3), Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT));
        h.setBlock(new BlockPos(4, 1, 3), Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        CompanionEntity c = companion(h, new BlockPos(3, 1, 1));
        c.stay();
        BlockPos chest = h.absolutePos(new BlockPos(3, 1, 3));
        c.setHome(chest);
        h.assertValueEqual(c.containerAt(h.getLevel(), chest).getContainerSize(), 54, "Test-Doppelkiste");
        fillChest(h, c, new BlockPos(3, 1, 3));
        c.giveToInventory(new ItemStack(Items.RAW_GOLD, 7));
        c.giveToInventory(new ItemStack(Items.CHEST));
        c.addTask(Task.deliver(false));
        h.succeedWhen(() -> {
            h.assertValueEqual(CompanionCraft.count(c, "raw_gold"), 0, "Nicht eingelagert");
            h.assertTrue(c.memory.chestWith(Items.RAW_GOLD, h.getLevel(), chest, 8) != null, "Neue Kiste nicht gemerkt");
            h.assertTrue(c.storage.storageChests(h.getLevel()).size() >= 2, "Keine weitere Kiste");
        });
    }

    /** Steht neben dem Haus etwas Gebautes des Spielers (Kiste), baut er dort keinen Anbau und reißt nichts ab. */
    @GameTest(maxTicks = 200, structure = "begleiter-gametest:wide")
    public void anbauReisstFremdesNichtAb(GameTestHelper h) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z < 5; z++) h.setBlock(x, 0, z, Blocks.STONE);
        for (int y = 1; y <= 3; y++)
            for (int x = 0; x < 5; x++)
                for (int z = 0; z < 5; z++)
                    if ((x == 0 || x == 4 || z == 0 || z == 4) && !(z == 0 && x == 2 && y <= 2)) h.setBlock(x, y, z, Blocks.OAK_PLANKS);
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) h.setBlock(x, 4, z, Blocks.OAK_PLANKS);
        BlockPos playerChest = new BlockPos(6, 1, 2);
        h.setBlock(playerChest, Blocks.CHEST);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        CompoundTag mem = new CompoundTag();
        mem.putLong("HouseOrigin", h.absolutePos(BlockPos.ZERO).asLong());
        mem.putBoolean("HouseBuilt", true);
        c.loadMemory(mem);
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 64));
        c.addTask(Task.extend(0));
        h.runAfterDelay(60, () -> {
            h.assertBlockPresent(Blocks.CHEST, playerChest);
            h.assertValueEqual(c.houseAnnexes(), 0, "Anbau trotz fremder Kiste");
            h.assertTrue(h.getBlockState(new BlockPos(4, 1, 2)).is(Blocks.OAK_PLANKS), "Hauswand für den Durchgang geöffnet");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 3000, structure = "begleiter-gametest:wide")
    public void vollesHausWirdAngebaut(GameTestHelper h) {
        // Haus 5x5 bei (0,0,0), Boden reicht für einen Anbau (x 5..8); alle Kistenplätze im Haus belegt und voll
        for (int x = 0; x <= 8; x++) for (int z = 0; z < 5; z++) h.setBlock(x, 0, z, Blocks.STONE);
        for (int y = 1; y <= 3; y++)
            for (int x = 0; x < 5; x++)
                for (int z = 0; z < 5; z++)
                    if ((x == 0 || x == 4 || z == 0 || z == 4) && !(z == 0 && x == 2 && y <= 2)) h.setBlock(x, y, z, Blocks.OAK_PLANKS);
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) h.setBlock(x, 4, z, Blocks.OAK_PLANKS);
        BlockPos[] spots = {new BlockPos(1, 1, 3), new BlockPos(1, 1, 2), new BlockPos(1, 1, 1), new BlockPos(3, 1, 3), new BlockPos(3, 1, 1)};
        for (BlockPos s : spots) h.setBlock(s, Blocks.CHEST);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        CompoundTag mem = new CompoundTag();
        mem.putLong("Home", h.absolutePos(spots[0]).asLong());
        mem.putLong("HouseOrigin", h.absolutePos(BlockPos.ZERO).asLong());
        mem.putBoolean("HouseBuilt", true);
        c.loadMemory(mem);
        for (BlockPos s : spots) fillChest(h, c, s);
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 64));
        c.giveToInventory(new ItemStack(Items.RAW_GOLD, 7));
        c.giveToInventory(new ItemStack(Items.CHEST));
        c.addTask(Task.deliver(false));
        h.succeedWhen(() -> {
            h.assertValueEqual(c.houseAnnexes(), 1, "Kein Anbau");
            h.assertTrue(h.getBlockState(new BlockPos(4, 1, 2)).isAir(), "Kein Durchgang");
            StringBuilder missing = new StringBuilder();
            for (int y = 1; y <= 4; y++)
                for (int x = 5; x <= 8; x++)
                    for (int z = 0; z < 5; z++)
                        if ((y == 4 || x == 8 || z == 0 || z == 4) && !h.getBlockState(new BlockPos(x, y, z)).is(Blocks.OAK_PLANKS))
                            missing.append(" ").append(x).append(",").append(y).append(",").append(z);
            h.assertTrue(missing.isEmpty(), "Anbau unfertig:" + missing + " / Aufgaben " + c.peekTasks(3) + " Bretter " + CompanionCraft.count(c, "oak_planks"));
            h.assertValueEqual(CompanionCraft.count(c, "raw_gold"), 0, "Nicht eingelagert");
            boolean annexChest = false;
            for (int x = 5; x <= 7; x++)
                for (int z : new int[]{1, 3}) annexChest |= h.getBlockState(new BlockPos(x, 1, z)).is(Blocks.CHEST);
            h.assertTrue(annexChest, "Keine Kiste im Anbau");
        });
    }

    @GameTest(maxTicks = 400)
    public void bautErsatzSpitzhackeVorDemBrechen(GameTestHelper h) {
        floor(h);
        h.setBlock(new BlockPos(5, 1, 5), Blocks.CRAFTING_TABLE);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 3));
        c.stay();
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 20); // nur noch 20 Schläge
        c.giveToInventory(worn);
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 3));
        c.giveToInventory(new ItemStack(Items.STICK, 2));
        c.addTask(Task.mine(Resources.DIAMOND.key(), 1));
        h.succeedWhen(() -> h.assertTrue(CompanionCraft.countAll(c, "iron_pickaxe") >= 2, "Keine Ersatz-Spitzhacke"));
    }

    @GameTest(maxTicks = 60)
    public void verwirftAlteGrabschritteNachSturz(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        // Treppe war oben geplant (y+6), er ist aber unten in der "Höhle" gelandet
        c.addTask(Task.moveTo(h.absolutePos(new BlockPos(5, 7, 2)), 1));
        c.addTask(Task.breakAt(h.absolutePos(new BlockPos(6, 7, 2)), null));
        c.addTask(new Task(new TaskSpec.Mine(Resources.DIAMOND.key(), 1), 3));
        h.succeedWhen(() -> {
            CompanionEntity.Task cur = c.currentTask();
            h.assertTrue(cur == null || cur.type() != TaskType.MOVE_TO || cur.pos().getY() <= c.getBlockY() + 3,
                    "Alter Grabschritt oben noch dran");
        });
    }

    @GameTest
    public void mineGiltNurInIhrerWelt(GameTestHelper h) {
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        BlockPos mine = h.absolutePos(new BlockPos(3, 1, 3));
        CompoundTag mem = new CompoundTag();
        mem.putLong("Mine", mine.asLong());
        mem.putLong("MineSeed", h.getLevel().getSeed());
        c.loadMemory(mem);
        h.assertValueEqual(c.mine(), mine, "Mine dieser Welt");
        CompoundTag saved = c.saveMemory();
        h.assertValueEqual(saved.getLongOr("MineSeed", 1), h.getLevel().getSeed(), "Seed mitgespeichert");
        CompoundTag other = new CompoundTag();
        other.putLong("Mine", mine.asLong());
        other.putLong("MineSeed", h.getLevel().getSeed() + 1); // Kern aus einer anderen Welt
        c.loadMemory(other);
        h.assertTrue(c.mine() == null, "Mine einer anderen Welt benutzt");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void stelltWerkbankAufSaeuleAuf(GameTestHelper h) {
        // oben auf einer 1x1-Säule: kein Platz mit Boden in 2-4 Blöcken -> direkt seitlich daneben
        for (int y = 0; y <= 3; y++) h.setBlock(4, y, 4, Blocks.COBBLESTONE);
        CompanionEntity c = companion(h, new BlockPos(4, 4, 4));
        c.stay();
        c.giveToInventory(new ItemStack(Items.CRAFTING_TABLE));
        c.addTask(Task.station("crafting_table", true));
        h.succeedWhen(() -> h.assertTrue(c.findStation("crafting_table", 4) != null, "Keine Werkbank aufgestellt"));
    }

    @GameTest(maxTicks = 600)
    public void besorgtFehlendeZutatBeimHerstellen(GameTestHelper h) {
        floor(h);
        h.setBlock(new BlockPos(5, 1, 5), Blocks.CRAFTING_TABLE);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 3));
        c.stay();
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 2));
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 1)); // nur Stamm, keine Stöcke
        c.addTask(Task.craft("iron_sword", 1));
        h.succeedWhen(() -> h.assertTrue(CompanionCraft.count(c, "iron_sword") >= 1 || c.getMainHandItem().is(Items.IRON_SWORD),
                "Kein Eisenschwert"));
    }

    @GameTest(maxTicks = 400)
    public void bautMitJedemMuellBlock(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.DIRT, 1));
        c.giveToInventory(new ItemStack(Items.ANDESITE, 3));
        c.giveToInventory(new ItemStack(Items.COBBLED_DEEPSLATE, 5));
        c.giveToInventory(new ItemStack(Items.DIAMOND, 2)); // Wertvolles nie verbauen
        h.assertValueEqual(c.scaffoldCount(), 9, "Müll-Blöcke zusammen");
        h.assertValueEqual(c.scaffoldItem(), Items.COBBLED_DEEPSLATE, "Größter Vorrat zuerst");
        // Weg-Block "Erde" geplant, aber Erde ist aus -> andere wertlose Sorte
        c.getInventory().removeItemType(Items.DIRT, 1);
        c.addTask(Task.place(h.absolutePos(new BlockPos(4, 1, 4)), Blocks.DIRT));
        h.succeedWhen(() -> {
            BlockState s = h.getLevel().getBlockState(h.absolutePos(new BlockPos(4, 1, 4)));
            h.assertTrue(s.is(Blocks.COBBLED_DEEPSLATE) || s.is(Blocks.ANDESITE), "Nicht mit anderem Müll gebaut: " + s);
            h.assertValueEqual(CompanionCraft.count(c, "diamond"), 2, "Diamanten");
        });
    }

    @GameTest(maxTicks = 400)
    public void bautSichUeberFackelHoch(GameTestHelper h) {
        floor(h);
        h.setBlock(new BlockPos(3, 1, 3), Blocks.TORCH);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 3));
        c.stay();
        c.giveToInventory(new ItemStack(Items.DIRT, 8));
        c.addTask(Task.pillar(h.absolutePos(new BlockPos(3, 3, 3))));
        h.succeedWhen(() -> {
            h.assertTrue(c.blockPosition().getY() >= h.absolutePos(new BlockPos(3, 3, 3)).getY(), "Nicht hochgebaut: " + c.blockPosition());
            h.assertBlockPresent(Blocks.DIRT, new BlockPos(3, 1, 3));
        });
    }

    @GameTest(maxTicks = 600)
    public void bautHochUndGraebtSteinUeberKopfWeg(GameTestHelper h) {
        floor(h);
        // Stein drei über den Füßen: zum Springen muss er erst weg
        h.setBlock(new BlockPos(3, 4, 3), Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 3));
        c.stay();
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 8));
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        c.addTask(Task.pillar(h.absolutePos(new BlockPos(3, 2, 3))));
        h.succeedWhen(() -> {
            h.assertTrue(c.blockPosition().getY() >= h.absolutePos(new BlockPos(3, 2, 3)).getY(), "Nicht hochgebaut: " + c.blockPosition());
            h.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 4, 3));
        });
    }

    @GameTest(maxTicks = 800)
    public void tragbarerOfenStelltAuchWerkbankHierAuf(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 4));
        c.stay();
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 8));
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 8));
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        c.addTask(Task.station("furnace", true));
        h.succeedWhen(() -> {
            BlockPos at = c.blockPosition();
            h.assertTrue(c.findStation("furnace", 8) != null, "Kein Ofen in der Nähe von " + at);
            h.assertTrue(c.findStation("crafting_table", 8) != null, "Keine Werkbank in der Nähe von " + at);
        });
    }

    @GameTest(maxTicks = 600)
    public void schmilztRohkupferZuBarren(GameTestHelper h) {
        floor(h);
        h.setBlock(new BlockPos(4, 1, 4), Blocks.FURNACE);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.RAW_COPPER, 2));
        c.giveToInventory(new ItemStack(Items.COAL, 1));
        c.addTask(Task.smelt("copper_ingot", 2));
        h.succeedWhen(() -> h.assertTrue(CompanionCraft.count(c, "copper_ingot") >= 2,
                "Keine Kupferbarren (" + CompanionCraft.count(c, "copper_ingot") + ")"));
    }

    @GameTest
    public void kupferwerkzeug(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        h.assertTrue(!CompanionNeeds.wantsCopper(c), "Will Kupfer ohne Kupfer");
        c.giveToInventory(new ItemStack(Items.COPPER_INGOT, 3));
        h.assertTrue(CompanionNeeds.wantsCopper(c), "Will kein Kupferwerkzeug");
        var plan = CompanionPlanner.obtain(c, Items.COPPER_PICKAXE, 1);
        h.assertTrue(plan != null && plan.tasks().getLast().type() == TaskType.CRAFT, "Kein Plan für Kupferspitzhacke");
        c.giveToInventory(new ItemStack(Items.COPPER_PICKAXE));
        h.assertValueEqual(CompanionCraft.tier(c, "pickaxe"), 2, "Stufe Kupfer");
        h.succeed();
    }

    // ------------------------------------------------------------------ G3: Zielplaner

    @GameTest
    public void planerTeiltZwischenprodukte(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 3)); // 12 Bretter: reicht für Spitzhacke + Stöcke
        var plan = CompanionPlanner.obtain(c, Items.WOODEN_PICKAXE, 1);
        h.assertTrue(plan != null, "Kein Plan");
        h.assertTrue(plan.tasks().stream().noneMatch(t -> t.type() == TaskType.GATHER), "Sammelt unnötig Holz: " + plan.explain());
        var last = plan.tasks().getLast();
        h.assertTrue(last.spec() instanceof TaskSpec.Craft cr && "minecraft:wooden_pickaxe".equals(cr.item()), "Letzter Schritt: " + plan.explain());
        h.succeed();
    }

    @GameTest
    public void planerNimmtKisteStattAbbauen(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        Container box = chest(h, c, new BlockPos(5, 1, 5));
        box.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        var plan = CompanionPlanner.obtain(c, Items.IRON_INGOT, 3);
        h.assertTrue(plan != null && plan.tasks().getFirst().type() == TaskType.TAKE, "Nicht aus der Kiste: "
                + (plan == null ? "null" : plan.explain()));
        h.succeed();
    }

    @GameTest
    public void planerFindetWegZuFackelnUndEisenwerkzeug(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        var torch = CompanionPlanner.obtain(c, Items.TORCH, 8);
        h.assertTrue(torch != null && torch.tasks().getLast().type() == TaskType.CRAFT, "Fackeln: "
                + (torch == null ? "null" : torch.explain()));
        h.assertTrue(torch.tasks().stream().anyMatch(t -> t.spec() instanceof TaskSpec.Gather g && Resources.COAL.key().equals(g.resource())),
                "Keine Kohle geplant: " + torch.explain());
        var pick = CompanionPlanner.obtain(c, Items.IRON_PICKAXE, 1);
        h.assertTrue(pick != null, "Kein Plan für Eisenspitzhacke");
        String ex = pick.explain();
        h.assertTrue(ex.contains("stone_pickaxe") && ex.contains("raw_iron") || ex.contains("iron_ingot"), "Weg: " + ex);
        // Eisen: schmelzen – oder aus einer Kiste in der Nähe (andere Tests stehen daneben)
        h.assertTrue(pick.tasks().stream().anyMatch(t -> t.type() == TaskType.SMELT || t.type() == TaskType.TAKE), "Kein Eisen: " + ex);
        h.succeed();
    }

    @GameTest
    public void chatVerstehtGegenstaende(GameTestHelper h) {
        h.assertValueEqual(CompanionBrain.itemWord(List.of("hol", "mir", "eine", "eisenspitzhacke")), Items.IRON_PICKAXE, "Eisenspitzhacke");
        h.assertValueEqual(CompanionBrain.itemWord(List.of("mach", "eine", "eiserne", "axt")), Items.IRON_AXE, "eiserne Axt");
        h.assertValueEqual(CompanionBrain.itemWord(List.of("bring", "mir", "10", "fackeln")), Items.TORCH, "Fackeln");
        h.assertValueEqual(CompanionBrain.itemWord(List.of("ich", "brauche", "einen", "diamanthelm")), Items.DIAMOND_HELMET, "Diamanthelm");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void besorgtWerkbankUndBringtSie(GameTestHelper h) throws Exception {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        owner(h, c, GameType.SURVIVAL);
        c.giveToInventory(new ItemStack(Items.BIRCH_LOG, 2));
        // Antworten sind übersetzt; die Prüfung bezieht sich auf den deutschen Text.
        String[] reply = new String[1];
        ServerTextGameTests.inLanguage("de_de", () -> reply[0] = CompanionBrain.orderObtain(c, Items.CRAFTING_TABLE, 1, false));
        h.assertTrue(reply[0].startsWith("Ich besorge"), "Antwort: " + reply[0]);
        h.succeedWhen(() -> h.assertTrue(c.findItem(Items.CRAFTING_TABLE) >= 0, "Keine Werkbank hergestellt ("
                + c.currentTaskText() + ")"));
    }

    // ------------------------------------------------------------------ G2: Bedürfnisse

    @GameTest
    public void ohneSpitzhackeIstAusruestungAmDringendsten(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 3));
        c.stay();
        var top = CompanionNeeds.evaluate(c).getFirst();
        h.assertValueEqual(top.need(), CompanionNeeds.Need.GEAR, "Dringendstes Bedürfnis");
        ServerTextGameTests.assertTranslatedContains(h, top.reasonText(), "Spitzhacke", "pickaxe");
        h.succeed();
    }

    @GameTest
    public void hungerUnterbrichtPlanerAberKeinenBefehl(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(5, 1, 5));
        c.food.set(4);

        // Spieler-Befehl: nie unterbrechen
        c.addTask(Task.gather(Resources.STONE.key(), 5));
        h.assertTrue(!CompanionNeeds.checkInterrupt(c), "Spieler-Befehl wurde unterbrochen");

        // Planer-Aufgabe mit niedrigerer Dringlichkeit: Hunger unterbricht
        c.goal = CompanionNeeds.Need.SUPPLY;
        c.goalScore = 22;
        h.assertTrue(CompanionNeeds.checkInterrupt(c), "Hunger hat nicht unterbrochen");
        h.assertTrue(!c.hasTasks(), "Aufgaben nicht abgebrochen");
        CompanionNeeds.decide(c);
        h.assertValueEqual(c.goal, CompanionNeeds.Need.HUNGER, "Neues Ziel");
        h.assertValueEqual(c.currentTask().type(), TaskType.HUNT, "Neue Aufgabe");
        h.succeed();
    }

    @GameTest
    public void nichtUmsetzbaresBeduerfnisUnterbrichtNichtStaendig(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        c.giveToInventory(new ItemStack(Items.BREAD, 2));
        c.food.set(4); // hungrig, aber keine Tiere und etwas Brot: Jagen geht nicht
        CompanionNeeds.decide(c);
        h.assertTrue(c.goal != CompanionNeeds.Need.HUNGER, "Hunger-Ziel ohne Tiere gewählt");
        h.assertTrue(c.hasTasks(), "Kein Ersatz-Ziel");
        h.assertTrue(!CompanionNeeds.checkInterrupt(c), "Unterbricht für etwas, das nicht geht");
        h.succeed();
    }

    @GameTest
    public void ersetztFastKaputtesWerkzeug(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        ItemStack pick = new ItemStack(Items.STONE_PICKAXE);
        pick.setDamageValue(pick.getMaxDamage() - 3);
        c.giveToInventory(pick);
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 8));
        c.giveToInventory(new ItemStack(Items.STICK, 4));
        var top = CompanionNeeds.evaluate(c).getFirst();
        h.assertValueEqual(top.need(), CompanionNeeds.Need.GEAR, "Bedürfnis");
        ServerTextGameTests.assertTranslatedContains(h, top.reasonText(), "fast kaputt", "almost broken");
        CompanionNeeds.decide(c);
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == TaskType.CRAFT
                && c.currentTask().spec() instanceof TaskSpec.Craft cr && "minecraft:stone_pickaxe".equals(cr.item()), "Kein Ersatz geplant: " + c.currentTaskText());
        h.succeed();
    }

    // ------------------------------------------------------------------ G1: Gedächtnis

    @GameTest(maxTicks = 300)
    public void spuertErzDurchWaende(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        h.setBlock(new BlockPos(5, 1, 5), Blocks.IRON_ORE);
        // Diamant komplett eingemauert: das Erz-Gespür findet ihn trotzdem (Umkreis 32)
        BlockPos dia = new BlockPos(6, 1, 2);
        h.setBlock(dia, Blocks.DIAMOND_ORE);
        for (BlockPos n : new BlockPos[]{dia.east(), dia.west(), dia.north(), dia.south(), dia.above()}) h.setBlock(n, Blocks.STONE);
        ServerLevel sl = h.getLevel();
        h.succeedWhen(() -> {
            var iron = c.memory.find(CompanionMemory.Kind.ORE, "eisen", sl, c.blockPosition());
            h.assertTrue(iron.stream().anyMatch(p -> p.pos.distManhattan(h.absolutePos(new BlockPos(5, 1, 5))) <= 3), "Eisenerz nicht gemerkt");
            h.assertTrue(c.memory.find(CompanionMemory.Kind.ORE, "diamant", sl, c.blockPosition()).stream()
                    .anyMatch(p -> p.pos.distManhattan(h.absolutePos(dia)) <= 3), "Eingemauerten Diamant nicht gespürt");
        });
    }

    @GameTest(maxTicks = 800)
    public void graebtZuGespuertemErz(GameTestHelper h) {
        floor(h);
        // Erz mitten in einem Steinblock (3x3x3), von außen nicht zu sehen
        for (int x = 4; x <= 6; x++) for (int y = 1; y <= 3; y++) for (int z = 4; z <= 6; z++) h.setBlock(x, y, z, Blocks.STONE);
        h.setBlock(new BlockPos(5, 2, 5), Blocks.IRON_ORE);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        h.runAfterDelay(120, () -> c.addTask(Task.gather(Resources.IRON.key(), 1)));
        h.succeedWhen(() -> h.assertBlockNotPresent(Blocks.IRON_ORE, new BlockPos(5, 2, 5)));
    }

    @GameTest
    public void vergisstAbgebautesErzUndUeberlebtSpeichern(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        ServerLevel sl = h.getLevel();
        BlockPos ore = h.absolutePos(new BlockPos(4, 1, 4));
        h.setBlock(new BlockPos(4, 1, 4), Blocks.COAL_ORE);
        c.memory.remember(CompanionMemory.Kind.ORE, "kohle", sl, ore);
        c.memory.remember(CompanionMemory.Kind.DEATH, "lava", sl, h.absolutePos(new BlockPos(2, 1, 6)));

        // Speichern/Laden (Entity) und Kern nach dem Tod
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, sl.registryAccess());
        c.saveWithoutId(out);
        CompanionEntity copy = CompanionMod.COMPANION.create(sl, EntitySpawnReason.LOAD);
        copy.load(TagValueInput.create(ProblemReporter.DISCARDING, sl.registryAccess(), out.buildResult()));
        h.assertValueEqual(copy.memory.size(), c.memory.size(), "Gedächtnis nach dem Laden");
        CompanionEntity revived = CompanionMod.COMPANION.create(sl, EntitySpawnReason.LOAD);
        revived.loadMemory(c.saveMemory());
        h.assertTrue(revived.memory.nearest(CompanionMemory.Kind.DEATH, null, sl, ore, 20) != null, "Todesort im Kern vergessen");

        h.setBlock(new BlockPos(4, 1, 4), Blocks.AIR);
        c.memory.verify(c, sl);
        h.assertTrue(c.memory.nearest(CompanionMemory.Kind.ORE, "kohle", sl, ore, 5) == null, "Abgebautes Erz nicht vergessen");
        h.assertTrue(c.memory.dangerous(sl, h.absolutePos(new BlockPos(2, 1, 5)), 3), "Todesort nicht als Gefahr erkannt");
        h.succeed();
    }

    @GameTest
    public void findetKisteAusDemGedaechtnis(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        var p = owner(h, c, GameType.SURVIVAL);
        Container box = chest(h, c, new BlockPos(6, 1, 6));
        box.setItem(0, new ItemStack(Items.BREAD, 12));
        c.containerAt(h.getLevel(), h.absolutePos(new BlockPos(6, 1, 6))); // hineinschauen
        // Umkreis 2 sieht die Kiste nicht – das Gedächtnis schon
        h.assertValueEqual(c.findContainerWith(Items.BREAD, 2), h.absolutePos(new BlockPos(6, 1, 6)), "Kiste aus dem Gedächtnis");
        String reply = CompanionBrain.whereIs(p, c, List.of("wo", "ist", "brot"));
        h.assertTrue(reply != null && reply.startsWith("12x"), "Antwort auf 'wo ist Brot': " + reply);
        c.memory.remember(CompanionMemory.Kind.ORE, "eisen", h.getLevel(), h.absolutePos(new BlockPos(3, 1, 3)));
        var oreReply = CompanionBrain.whereIsReply(p, c, List.of("wo", "ist", "eisen"));
        ServerTextGameTests.assertTranslatedContains(h, oreReply, "Ich kenne Eisen", "I know of iron");
        h.succeed();
    }

    // ------------------------------------------------------------------ B6: Speichern, Pause, Menü

    @GameTest
    public void bauauftragUeberstehtSpeichern(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(6, 1, 6));
        c.stay();
        Map<BlockPos, BlockState> plan = new java.util.LinkedHashMap<>();
        for (int x = 1; x <= 4; x++) plan.put(new BlockPos(x, 1, 2), Blocks.STONE_BRICKS.defaultBlockState());
        plan.put(new BlockPos(2, 2, 2), Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.WEST));
        plan.put(new BlockPos(3, 5, 2), Blocks.AIR.defaultBlockState());
        startBuild(h, c, "Speichertest", plan);
        c.build.index = 3;
        c.build.paused = true;

        ServerLevel level = h.getLevel();
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        c.saveWithoutId(out);
        CompanionEntity copy = CompanionMod.COMPANION.create(level, EntitySpawnReason.LOAD);
        copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), out.buildResult()));

        h.assertTrue(copy.build != null, "Bauauftrag ging verloren");
        h.assertValueEqual(copy.build.name, "Speichertest", "Name");
        h.assertValueEqual(copy.build.order, c.build.order, "Blöcke/Reihenfolge");
        h.assertValueEqual(copy.build.index, 3, "Fortschritt");
        h.assertTrue(copy.build.paused, "Pause ging verloren");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void pausierterBauWirdFortgesetzt(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(6, 1, 6));
        c.stay();
        owner(h, c, GameType.CREATIVE);
        BlockPos target = new BlockPos(2, 1, 2);
        startBuild(h, c, "Pausentest", Map.of(target, Blocks.GOLD_BLOCK.defaultBlockState()));
        CompanionBrain.pauseBuild(c, true);
        h.runAfterDelay(80, () -> {
            h.assertBlockNotPresent(Blocks.GOLD_BLOCK, target);
            h.assertTrue(c.build != null && c.build.paused, "Pausierter Auftrag verschwunden");
            CompanionBrain.pauseBuild(c, false);
            h.succeedWhen(() -> {
                h.assertBlockPresent(Blocks.GOLD_BLOCK, target);
                h.assertTrue(c.build == null, "Bau nicht abgeschlossen");
            });
        });
    }

    @GameTest(maxTicks = 200)
    public void abbrechenBeendetBauDauerhaft(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(6, 1, 6));
        c.stay();
        var p = owner(h, c, GameType.CREATIVE);
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0); // Chat erreicht nur Begleiter in der Nähe
        BlockPos chat = new BlockPos(2, 1, 2), menu = new BlockPos(3, 1, 2), command = new BlockPos(4, 1, 2);
        startBuild(h, c, "Chat-Abbruch", Map.of(chat, Blocks.GOLD_BLOCK.defaultBlockState()));
        CompanionBrain.onChat(p, "@ Hör auf");
        h.assertTrue(c.build == null, "Chat-Abbruch lässt Bau bestehen");
        startBuild(h, c, "Menü-Abbruch", Map.of(menu, Blocks.GOLD_BLOCK.defaultBlockState()));
        CompanionActions.get(6).handler().execute(c, p, false);
        h.assertTrue(c.build == null, "Menü-Abbruch lässt Bau bestehen");
        startBuild(h, c, "Befehl-Abbruch", Map.of(command, Blocks.GOLD_BLOCK.defaultBlockState()));
        c.cancelOrders(); // /companion cancel, /begleiter abbrechen, F8 Abbrechen
        h.runAfterDelay(80, () -> {
            h.assertBlockNotPresent(Blocks.GOLD_BLOCK, chat);
            h.assertBlockNotPresent(Blocks.GOLD_BLOCK, menu);
            h.assertBlockNotPresent(Blocks.GOLD_BLOCK, command);
            h.assertTrue(c.build == null && !c.hasTasks(), "Abgebrochener Bau wieder aufgenommen");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void moduswechselPausiertBau(GameTestHelper h) throws Exception {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(6, 1, 6));
        var p = owner(h, c, GameType.CREATIVE);
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0); // Chat erreicht nur Begleiter in der Nähe
        BlockPos target = new BlockPos(2, 1, 2);
        startBuild(h, c, "Modustest", Map.of(target, Blocks.GOLD_BLOCK.defaultBlockState()));
        String[] reply = new String[1];
        ServerTextGameTests.inLanguage("de_de", () -> reply[0] = CompanionActions.get(1).handler().execute(c, p, false)); // Menü "Warten"
        h.assertTrue(reply[0].contains("pausiert"), "Antwort nennt pausierten Bau nicht: " + reply[0]);
        h.runAfterDelay(80, () -> {
            h.assertBlockNotPresent(Blocks.GOLD_BLOCK, target);
            h.assertTrue(c.build != null && c.build.paused, "Bau nach Warten verworfen oder weitergeführt");
            CompanionBrain.onChat(p, "@ weiter");
            h.succeedWhen(() -> {
                h.assertBlockPresent(Blocks.GOLD_BLOCK, target);
                h.assertTrue(c.build == null, "Bau nicht abgeschlossen");
            });
        });
    }

    @GameTest
    public void menueBauenSeite(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(4, 1, 4));
        c.stay();
        var p = owner(h, c, GameType.SURVIVAL);
        Vec3 at = h.absoluteVec(new Vec3(4.5, 1, 2.5));
        p.snapTo(at.x, at.y, at.z, 0, 0);
        CompanionMenu menu = new CompanionMenu(0, p.getInventory(), c, CompanionMenu.Page.BUILD);

        menu.quickMoveStack(p, 10); // Steinhaus
        h.assertTrue(c.build != null, "Kein Bauauftrag aus dem Menü");
        menu.quickMoveStack(p, 2); // Pause
        h.assertTrue(c.build.paused, "Pause-Knopf wirkt nicht");
        ItemStack mat = menu.getSlot(36).getItem();
        h.assertTrue(!mat.isEmpty(), "Keine Materialliste");
        ServerTextGameTests.assertTranslatedContains(h, mat.getHoverName(), "Noch nötig", "Still needed");
        menu.quickMoveStack(p, 3); // Abbrechen
        h.assertTrue(c.build == null, "Abbrechen-Knopf wirkt nicht");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void kreativBautBlockzustaendeUndRedstone(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(6, 1, 6));
        c.stay();
        owner(h, c, GameType.CREATIVE);
        var BSP = net.minecraft.world.level.block.state.properties.BlockStateProperties.class;
        Map<BlockPos, BlockState> plan = new java.util.LinkedHashMap<>();
        plan.put(new BlockPos(1, 1, 5), Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.EAST));
        plan.put(new BlockPos(3, 1, 5), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.SOUTH));
        plan.put(new BlockPos(3, 2, 5), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.SOUTH)
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        plan.put(new BlockPos(5, 1, 4), Blocks.BED.red().defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.SOUTH));
        plan.put(new BlockPos(5, 1, 5), Blocks.BED.red().defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.SOUTH)
                .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        // Hebel (an) -> Staub -> Lampe: nach dem Bau muss die Lampe leuchten
        plan.put(new BlockPos(1, 1, 2), Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                .setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true));
        plan.put(new BlockPos(2, 1, 2), Blocks.REDSTONE_WIRE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RedstoneWireBlock.EAST, net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE)
                .setValue(net.minecraft.world.level.block.RedstoneWireBlock.WEST, net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE));
        plan.put(new BlockPos(3, 1, 2), Blocks.REDSTONE_LAMP.defaultBlockState());
        startBuild(h, c, "Test", plan);

        h.succeedWhen(() -> {
            h.assertTrue(c.build == null, "Bau nicht fertig (" + c.currentTaskText() + ")");
            BlockState stairs = h.getLevel().getBlockState(h.absolutePos(new BlockPos(1, 1, 5)));
            h.assertTrue(stairs.is(Blocks.OAK_STAIRS) && stairs.getValue(net.minecraft.world.level.block.StairBlock.FACING) == Direction.EAST,
                    "Treppe falsch: " + stairs);
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(3, 2, 5))).is(Blocks.OAK_DOOR), "Obere Türhälfte fehlt");
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(5, 1, 5))).is(Blocks.BED.red()), "Kopfteil Bett fehlt");
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(3, 1, 2)))
                    .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "Lampe leuchtet nicht");
        });
    }

    @GameTest(maxTicks = 800)
    public void survivalHoltGlasAusDerKiste(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        owner(h, c, GameType.SURVIVAL);
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 2));
        Container box = chest(h, c, new BlockPos(6, 1, 1));
        box.setItem(0, new ItemStack(Items.GLASS, 5));
        startBuild(h, c, "Fenster", Map.of(new BlockPos(3, 1, 4), Blocks.COBBLESTONE.defaultBlockState(),
                new BlockPos(4, 1, 4), Blocks.GLASS.defaultBlockState(), new BlockPos(5, 1, 4), Blocks.COBBLESTONE.defaultBlockState()));

        h.succeedWhen(() -> {
            h.assertTrue(c.build == null, "Bau nicht fertig (" + c.currentTaskText() + ")");
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(4, 1, 4))).is(Blocks.GLASS), "Glas fehlt");
            h.assertValueEqual(count(box, Items.GLASS), 4, "Glas aus der Kiste");
            h.assertValueEqual(CompanionCraft.count(c, "cobblestone"), 0, "Bruchstein nicht verbaut");
        });
    }

    @GameTest(maxTicks = 800)
    public void survivalCraftetBretterFuerDenBau(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        owner(h, c, GameType.SURVIVAL);
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 1));
        Map<BlockPos, BlockState> plan = new java.util.HashMap<>();
        for (int x = 2; x <= 5; x++) plan.put(new BlockPos(x, 1, 4), Blocks.OAK_PLANKS.defaultBlockState());
        startBuild(h, c, "Boden", plan);

        h.succeedWhen(() -> {
            h.assertTrue(c.build == null, "Bau nicht fertig (" + c.currentTaskText() + ", Problem: " + c.problemText + ")");
            for (int x = 2; x <= 5; x++)
                h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, 1, 4))).is(Blocks.OAK_PLANKS), "Brett fehlt bei x=" + x);
        });
    }

    @GameTest
    public void rezeptbuchCraftetVerstaerker(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.REDSTONE_TORCH, 2));
        c.giveToInventory(new ItemStack(Items.REDSTONE, 1));
        c.giveToInventory(new ItemStack(Items.STONE, 3));
        h.assertTrue(CompanionCraft.craft(c, "repeater", 1), "Verstärker: " + c.lastCraftProblem);
        h.assertValueEqual(CompanionCraft.count(c, "repeater"), 1, "Verstärker im Inventar");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void survivalFragtNachUnbeschaffbaremMaterial(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        owner(h, c, GameType.SURVIVAL);
        startBuild(h, c, "Schleim", Map.of(new BlockPos(4, 1, 4), Blocks.SLIME_BLOCK.defaultBlockState()));
        h.succeedWhen(() -> {
            h.assertTrue(c.isPaused(), "Nicht pausiert (" + c.currentTaskText() + ")");
            h.assertTrue(c.build != null, "Bauauftrag verworfen");
        });
    }

    // ------------------------------------------------------------------ Eigene Bauten (B5)

    @GameTest
    public void hausHatTuerBettDachUndEinrichtung(GameTestHelper h) {
        var s = CompanionStructures.house(CompanionStructures.WOOD, 7, 7);
        int doors = 0, beds = 0, stairs = 0, panes = 0, chests = 0, torches = 0;
        for (BlockState st : s.blocks().values()) {
            if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) doors++;
            if (st.getBlock() instanceof net.minecraft.world.level.block.BedBlock) beds++;
            if (st.getBlock() instanceof net.minecraft.world.level.block.StairBlock) stairs++;
            if (st.is(Blocks.GLASS_PANE)) panes++;
            if (st.is(Blocks.CHEST)) chests++;
            if (st.is(Blocks.WALL_TORCH)) torches++;
        }
        h.assertValueEqual(doors, 2, "Türhälften");
        h.assertValueEqual(beds, 2, "Bettteile");
        h.assertTrue(stairs >= 18, "Dach zu klein: " + stairs + " Treppen");
        h.assertTrue(panes >= 4, "Zu wenige Fenster: " + panes);
        h.assertValueEqual(chests, 1, "Kiste");
        h.assertTrue(torches >= 3, "Licht fehlt");
        // Bett richtig herum: Fußteil zeigt zum Kopfteil
        for (var en : s.blocks().entrySet()) {
            BlockState st = en.getValue();
            if (st.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                    && st.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.FOOT) {
                BlockState head = s.blocks().get(en.getKey().relative(st.getValue(net.minecraft.world.level.block.BedBlock.FACING)));
                h.assertTrue(head != null && head.getBlock() instanceof net.minecraft.world.level.block.BedBlock, "Kopfteil nicht vor dem Fußteil");
            }
        }
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void kreativBautWeizenfarm(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(7, 1, 3)); // freie Spalte neben der Farm (nicht über die Kante)
        c.stay();
        owner(h, c, GameType.CREATIVE);
        var s = CompanionStructures.wheatFarm(5); // 7x7 mit Zaun
        startBuild(h, c, "Farm", s.blocks());

        h.succeedWhen(() -> {
            h.assertTrue(c.build == null, "Bau nicht fertig (" + c.currentTaskText() + ", " + (c.build == null ? ""
                    : c.build.index + "/" + c.build.total() + " " + (c.build.index < c.build.total()
                    ? c.build.order.get(c.build.index) : "")) + ", pausiert " + c.isPaused() + ", Log "
                    + c.getLog(0, 6, false).stream().map(e -> CompanionEntity.formatLogText(e).getString()).toList() + ")");
            int farmland = 0, wheat = 0;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(BlockPos.ZERO), h.absolutePos(new BlockPos(6, 2, 6)))) {
                BlockState st = h.getLevel().getBlockState(p);
                if (st.is(Blocks.FARMLAND)) farmland++;
                if (st.is(Blocks.WHEAT)) wheat++;
            }
            h.assertValueEqual(farmland, 24, "Ackerland");
            h.assertValueEqual(wheat, 24, "Weizen");
            h.assertTrue(h.getLevel().getFluidState(h.absolutePos(new BlockPos(3, 0, 3))).isSourceOfType(
                    net.minecraft.world.level.material.Fluids.WATER), "Wasser in der Mitte fehlt");
        });
    }

    @GameTest
    public void survivalPlantWassereimer(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 3));
        var plan = CompanionBuilder.acquire(c, h.getLevel(), Items.WATER_BUCKET, 1);
        h.assertTrue(plan != null, "Kein Plan für den Wassereimer");
        h.assertTrue(plan.stream().anyMatch(t -> t.spec() instanceof TaskSpec.Craft cr && "minecraft:bucket".equals(cr.item())), "Eimer wird nicht gecraftet: " + plan);
        h.assertValueEqual(plan.getLast().type(), TaskType.WATER, "Zuletzt Eimer füllen");
        h.succeed();
    }

    // ------------------------------------------------------------------ Redstone (B4)

    @GameTest(maxTicks = 600)
    public void redstoneVerstaerkerSchaltungFunktioniert(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(6, 1, 6));
        c.stay();
        owner(h, c, GameType.CREATIVE);
        Map<BlockPos, BlockState> plan = new java.util.LinkedHashMap<>();
        // Hebel (an) -> Staub (ohne Verbindungen, wie bei eigenen Bauten) -> Verstärker -> Staub -> Lampe
        plan.put(new BlockPos(1, 1, 3), Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                .setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true));
        plan.put(new BlockPos(2, 1, 3), Blocks.REDSTONE_WIRE.defaultBlockState());
        plan.put(new BlockPos(3, 1, 3), Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.WEST));
        plan.put(new BlockPos(4, 1, 3), Blocks.REDSTONE_WIRE.defaultBlockState());
        plan.put(new BlockPos(5, 1, 3), Blocks.REDSTONE_LAMP.defaultBlockState());
        startBuild(h, c, "Verstärker", plan);

        h.succeedWhen(() -> {
            h.assertTrue(c.build == null, "Bau nicht fertig (" + c.currentTaskText() + ")");
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(5, 1, 3)))
                    .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "Lampe hinter dem Verstärker leuchtet nicht");
        });
    }

    /** Echte Ein-/Ausgangsprüfung: Schaltung nach dem Bau umschalten, nicht nur Blöcke vergleichen. */
    @GameTest(maxTicks = 600)
    public void redstoneSchaltungReagiertAufSchalterwechsel(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(6, 1, 6));
        c.stay();
        owner(h, c, GameType.CREATIVE);
        Map<BlockPos, BlockState> plan = new java.util.LinkedHashMap<>();
        plan.put(new BlockPos(1, 1, 3), Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                .setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true));
        plan.put(new BlockPos(2, 1, 3), Blocks.REDSTONE_WIRE.defaultBlockState());
        plan.put(new BlockPos(3, 1, 3), Blocks.REDSTONE_LAMP.defaultBlockState());
        startBuild(h, c, "Redstone-Ein-Aus-Test", plan);

        BlockPos lever = h.absolutePos(new BlockPos(1, 1, 3));
        BlockPos lamp = h.absolutePos(new BlockPos(3, 1, 3));
        final int[] phase = {0};
        final int[] switchedAt = {0};
        h.onEachTick(() -> {
            if (phase[0] == 3) return;
            if (phase[0] == 0) {
                if (c.build != null) return;
                h.assertTrue(h.getLevel().getBlockState(lamp)
                        .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                        "Redstone-Lampe nach Bau nicht aktiv");
                var state = h.getLevel().getBlockState(lever);
                h.getLevel().setBlock(lever, state.setValue(net.minecraft.world.level.block.LeverBlock.POWERED, false), 3);
                switchedAt[0] = c.tickCount;
                phase[0] = 1;
            } else if (phase[0] == 1 && c.tickCount - switchedAt[0] >= 10) {
                h.assertTrue(!h.getLevel().getBlockState(lamp)
                        .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                        "Lampe bleibt trotz ausgeschaltetem Hebel an");
                var state = h.getLevel().getBlockState(lever);
                h.getLevel().setBlock(lever, state.setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true), 3);
                switchedAt[0] = c.tickCount;
                phase[0] = 2;
            } else if (phase[0] == 2 && c.tickCount - switchedAt[0] >= 10) {
                h.assertTrue(h.getLevel().getBlockState(lamp)
                        .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                        "Lampe bleibt trotz eingeschaltetem Hebel aus");
                phase[0] = 3;
                h.succeed();
            }
        });
    }

    @GameTest(maxTicks = 600)
    public void kolbenLoestErstNachDemBauAus(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(6, 1, 1));
        c.stay();
        owner(h, c, GameType.CREATIVE);
        Map<BlockPos, BlockState> plan = new java.util.LinkedHashMap<>();
        plan.put(new BlockPos(2, 1, 5), Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                .setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true));
        plan.put(new BlockPos(3, 1, 5), Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.EAST));
        plan.put(new BlockPos(4, 1, 5), Blocks.STONE.defaultBlockState());
        startBuild(h, c, "Kolben", plan);
        BlockPos piston = h.absolutePos(new BlockPos(3, 1, 5));
        boolean[] early = {false};
        h.onEachTick(() -> {
            BlockState s = h.getLevel().getBlockState(piston);
            if (c.build != null && s.is(Blocks.STICKY_PISTON) && s.getValue(net.minecraft.world.level.block.piston.PistonBaseBlock.EXTENDED))
                early[0] = true;
        });

        h.succeedWhen(() -> {
            h.assertTrue(!early[0], "Kolben hat schon während des Bauens ausgelöst");
            h.assertTrue(c.build == null, "Bau nicht fertig (" + c.currentTaskText() + ")");
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(5, 1, 5))).is(Blocks.STONE),
                    "Kolben hat den Stein nach dem Bau nicht geschoben");
        });
    }

    @GameTest
    public void kolbenBewegungAusnahmeIstBegrenzt(GameTestHelper h) {
        Map<BlockPos, BlockState> plan = new java.util.LinkedHashMap<>();
        BlockPos piston = new BlockPos(2, 2, 2);
        plan.put(piston, Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.EAST));
        plan.put(piston.east(1), Blocks.STONE.defaultBlockState());
        plan.put(piston.east(12), Blocks.STONE.defaultBlockState());
        plan.put(piston.east(14), Blocks.STONE.defaultBlockState());
        plan.put(piston.north(), Blocks.REDSTONE_LAMP.defaultBlockState());
        CompanionBuilder.BuildJob job = CompanionBuilder.create("Kolben-Test", plan);
        h.assertTrue(!CompanionBuilder.mayBeMovedByPiston(job, piston), "Kolben selbst muss geprüft werden");
        h.assertTrue(CompanionBuilder.mayBeMovedByPiston(job, piston.east()), "Block vor Kolben kann sich bewegen");
        h.assertTrue(CompanionBuilder.mayBeMovedByPiston(job, piston.east(12)), "Schubstrecke muss berücksichtigt werden");
        h.assertTrue(!CompanionBuilder.mayBeMovedByPiston(job, piston.east(14)), "Ferne Blöcke dürfen nicht übersprungen werden");
        h.assertTrue(!CompanionBuilder.mayBeMovedByPiston(job, piston.north()), "Redstone neben dem Kolben muss geprüft werden");
        h.succeed();
    }

    @GameTest
    public void redstoneDiagnoseErkenntFalscheBauteile(GameTestHelper h) {
        BlockPos lamp = h.absolutePos(new BlockPos(3, 2, 3));
        BlockPos lever = h.absolutePos(new BlockPos(4, 2, 3));
        Map<BlockPos, BlockState> plan = new java.util.LinkedHashMap<>();
        plan.put(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        plan.put(lever, Blocks.LEVER.defaultBlockState());
        CompanionBuilder.BuildJob job = CompanionBuilder.create("Redstone-Diagnose", plan);
        h.getLevel().setBlock(lamp, Blocks.STONE.defaultBlockState(), 3);
        h.getLevel().setBlock(lever, Blocks.LEVER.defaultBlockState(), 3);
        h.assertTrue(CompanionBuilder.redstoneMismatches(h.getLevel(), job).contains(lamp),
                "Falsche Lampe wurde nicht erkannt");
        h.assertTrue(!CompanionBuilder.redstoneMismatches(h.getLevel(), job).contains(lever),
                "Korrekt gesetzter Hebel wurde als Fehler gemeldet");
        h.succeed();
    }

    @GameTest
    public void vergleichIgnoriertBetriebszustaende(GameTestHelper h) {
        BlockState off = Blocks.REDSTONE_LAMP.defaultBlockState();
        BlockState on = off.setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true);
        h.assertTrue(CompanionBuilder.matches(on, off), "Leuchtende Lampe gilt als falsch");
        BlockState east = Blocks.OAK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.EAST);
        h.assertTrue(!CompanionBuilder.matches(Blocks.OAK_STAIRS.defaultBlockState(), east), "Falsch gedrehte Treppe gilt als richtig");
        h.succeed();
    }

    // ------------------------------------------------------------------ Schematics (B3)

    private static net.minecraft.core.HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blockLookup(GameTestHelper h) {
        return h.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
    }

    private static net.minecraft.nbt.CompoundTag spongeV2() {
        var t = new net.minecraft.nbt.CompoundTag();
        t.putInt("Version", 2);
        t.putShort("Width", (short) 3);
        t.putShort("Height", (short) 1);
        t.putShort("Length", (short) 1);
        var pal = new net.minecraft.nbt.CompoundTag();
        pal.putInt("minecraft:stone", 0);
        pal.putInt("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", 1);
        pal.putInt("minecraft:air", 2);
        t.put("Palette", pal);
        t.putByteArray("BlockData", new byte[]{0, 1, 2});
        return t;
    }

    @GameTest
    public void liestSchemV2UndV3(GameTestHelper h) {
        var s2 = CompanionSchematics.loadSponge(blockLookup(h), "v2", spongeV2());
        h.assertTrue(s2.blocks().get(BlockPos.ZERO).is(Blocks.STONE), "v2: Stein fehlt");
        BlockState st = s2.blocks().get(new BlockPos(1, 0, 0));
        h.assertTrue(st.is(Blocks.OAK_STAIRS) && st.getValue(net.minecraft.world.level.block.StairBlock.FACING) == Direction.EAST,
                "v2: Treppe falsch: " + st);

        var v3 = new net.minecraft.nbt.CompoundTag();
        var inner = spongeV2();
        inner.putInt("Version", 3);
        var blocks = new net.minecraft.nbt.CompoundTag();
        blocks.put("Palette", inner.getCompoundOrEmpty("Palette"));
        blocks.putByteArray("Data", new byte[]{1, 0, 2});
        inner.put("Blocks", blocks);
        v3.put("Schematic", inner);
        var s3 = CompanionSchematics.loadSponge(blockLookup(h), "v3", v3);
        h.assertTrue(s3.blocks().get(BlockPos.ZERO).is(Blocks.OAK_STAIRS), "v3: Treppe an Position 0 fehlt");
        h.assertValueEqual(s3.sizeX(), 3, "v3 Breite");
        h.succeed();
    }

    @GameTest
    public void liestStrukturblockNbt(GameTestHelper h) {
        var t = new net.minecraft.nbt.CompoundTag();
        var size = new net.minecraft.nbt.ListTag();
        for (int v : new int[]{2, 1, 1}) size.add(net.minecraft.nbt.IntTag.valueOf(v));
        t.put("size", size);
        var pal = new net.minecraft.nbt.ListTag();
        for (String n : new String[]{"minecraft:stone", "minecraft:glass"}) {
            var e = new net.minecraft.nbt.CompoundTag();
            e.putString("Name", n);
            pal.add(e);
        }
        t.put("palette", pal);
        var blocks = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < 2; i++) {
            var b = new net.minecraft.nbt.CompoundTag();
            var pos = new net.minecraft.nbt.ListTag();
            for (int v : new int[]{i, 0, 0}) pos.add(net.minecraft.nbt.IntTag.valueOf(v));
            b.put("pos", pos);
            b.putInt("state", i);
            blocks.add(b);
        }
        t.put("blocks", blocks);
        var s = CompanionSchematics.loadStructure(blockLookup(h), "nbt", t);
        h.assertTrue(s.blocks().get(new BlockPos(1, 0, 0)).is(Blocks.GLASS), "Glas fehlt");
        h.succeed();
    }

    /** 26.x-Strukturblock: Palette mit "id"/"properties", so wie NbtUtils.writeBlockState sie heute schreibt. */
    @GameTest
    public void liestStrukturblockNbtNeuesFormat(GameTestHelper h) {
        var t = new net.minecraft.nbt.CompoundTag();
        var size = new net.minecraft.nbt.ListTag();
        for (int v : new int[]{1, 1, 1}) size.add(net.minecraft.nbt.IntTag.valueOf(v));
        t.put("size", size);
        var pal = new net.minecraft.nbt.ListTag();
        pal.add(net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.OAK_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP)));
        t.put("palette", pal);
        var blocks = new net.minecraft.nbt.ListTag();
        var b = new net.minecraft.nbt.CompoundTag();
        var pos = new net.minecraft.nbt.ListTag();
        for (int v : new int[]{0, 0, 0}) pos.add(net.minecraft.nbt.IntTag.valueOf(v));
        b.put("pos", pos);
        b.putInt("state", 0);
        blocks.add(b);
        t.put("blocks", blocks);
        var s = CompanionSchematics.loadStructure(blockLookup(h), "nbt", t);
        var st = s.blocks().get(BlockPos.ZERO);
        h.assertTrue(st != null && st.is(Blocks.OAK_SLAB), "Platte fehlt: " + st);
        h.assertTrue(st.getValue(net.minecraft.world.level.block.SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.TOP,
                "Eigenschaft nicht gelesen: " + st);
        h.succeed();
    }

    /** Werte dicht in longs packen wie Litematica (Einträge dürfen über long-Grenzen reichen). */
    private static long[] pack(int[] values, int bits) {
        long[] arr = new long[(values.length * bits + 63) / 64];
        for (int i = 0; i < values.length; i++) {
            long start = (long) i * bits;
            int si = (int) (start >> 6), off = (int) (start & 63);
            arr[si] |= ((long) values[i]) << off;
            if (off + bits > 64) arr[si + 1] |= ((long) values[i]) >>> (64 - off);
        }
        return arr;
    }

    @GameTest
    public void liestLitematicaBitPackung(GameTestHelper h) {
        int[] vals = new int[20];
        for (int i = 0; i < vals.length; i++) vals[i] = (i * 7) % 31;
        long[] packed = pack(vals, 5);
        for (int i = 0; i < vals.length; i++)
            h.assertValueEqual(CompanionSchematics.bitAt(packed, i, 5), (long) vals[i], "Eintrag " + i);

        var region = new net.minecraft.nbt.CompoundTag();
        var pos = new net.minecraft.nbt.CompoundTag();
        pos.putInt("x", 0); pos.putInt("y", 0); pos.putInt("z", 0);
        var size = new net.minecraft.nbt.CompoundTag();
        size.putInt("x", -3); size.putInt("y", 1); size.putInt("z", 1); // negative Größe wie bei Litematica möglich
        region.put("Position", pos);
        region.put("Size", size);
        var pal = new net.minecraft.nbt.ListTag();
        for (String n : new String[]{"minecraft:air", "minecraft:stone", "minecraft:glass"}) {
            var e = new net.minecraft.nbt.CompoundTag();
            e.putString("Name", n);
            pal.add(e);
        }
        region.put("BlockStatePalette", pal);
        region.putLongArray("BlockStates", pack(new int[]{1, 0, 2}, 2));
        var regions = new net.minecraft.nbt.CompoundTag();
        regions.put("Haupt", region);
        var root = new net.minecraft.nbt.CompoundTag();
        root.put("Regions", regions);
        var s = CompanionSchematics.loadLitematic(blockLookup(h), "lit", root);
        h.assertTrue(s.blocks().get(new BlockPos(0, 0, 0)).is(Blocks.STONE), "Stein an x=0");
        h.assertTrue(s.blocks().get(new BlockPos(2, 0, 0)).is(Blocks.GLASS), "Glas an x=2");
        h.succeed();
    }

    @GameTest
    public void litematicUebernimmtCrafterSperreUndSchildtext(GameTestHelper h) {
        var region = new net.minecraft.nbt.CompoundTag();
        var pos = new net.minecraft.nbt.CompoundTag();
        pos.putInt("x", 2); pos.putInt("y", 0); pos.putInt("z", 0);
        var size = new net.minecraft.nbt.CompoundTag();
        size.putInt("x", -3); size.putInt("y", 1); size.putInt("z", 1);
        region.put("Position", pos);
        region.put("Size", size);
        var pal = new net.minecraft.nbt.ListTag();
        for (String n : new String[]{"minecraft:air", "minecraft:crafter", "minecraft:oak_sign", "minecraft:dispenser"}) {
            var e = new net.minecraft.nbt.CompoundTag();
            e.putString("Name", n);
            pal.add(e);
        }
        region.put("BlockStatePalette", pal);
        region.putLongArray("BlockStates", pack(new int[]{1, 2, 3}, 2));
        var tes = new net.minecraft.nbt.ListTag();
        var crafter = new net.minecraft.nbt.CompoundTag();
        crafter.putInt("x", 0); crafter.putInt("y", 0); crafter.putInt("z", 0);
        crafter.putString("id", "minecraft:crafter");
        crafter.putIntArray("disabled_slots", new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 99});
        tes.add(crafter);
        var sign = new net.minecraft.nbt.CompoundTag();
        sign.putInt("x", 1); sign.putInt("y", 0); sign.putInt("z", 0);
        var front = new net.minecraft.nbt.CompoundTag();
        var lines = new net.minecraft.nbt.ListTag();
        lines.add(net.minecraft.nbt.StringTag.valueOf("Put Bonemeal"));
        var evil = new net.minecraft.nbt.CompoundTag();
        evil.putString("text", "Here");
        var click = new net.minecraft.nbt.CompoundTag();
        click.putString("action", "run_command");
        click.putString("command", "/op @a");
        evil.put("click_event", click);
        lines.add(evil);
        front.put("messages", lines);
        sign.put("front_text", front);
        tes.add(sign);
        var chest = new net.minecraft.nbt.CompoundTag();
        chest.putInt("x", 2); chest.putInt("y", 0); chest.putInt("z", 0);
        chest.putString("id", "minecraft:dispenser"); // Eingabe der Anlage: kreativ mit Inhalt
        var items = new net.minecraft.nbt.ListTag();
        var dia = new net.minecraft.nbt.CompoundTag();
        dia.putString("id", "minecraft:diamond"); dia.putInt("count", 64);
        items.add(dia);
        chest.put("Items", items);
        tes.add(chest);
        region.put("TileEntities", tes);
        var regions = new net.minecraft.nbt.CompoundTag();
        regions.put("Farm", region);
        var root = new net.minecraft.nbt.CompoundTag();
        root.put("Regions", regions);

        var s = CompanionSchematics.loadLitematic(blockLookup(h), "farm", root);
        h.assertValueEqual(s.data().size(), 3, "Crafter, Schild und Werfer");
        var outputChest = new net.minecraft.nbt.CompoundTag();
        outputChest.putString("id", "minecraft:chest"); outputChest.put("Items", items.copy());
        // Kreativ übernimmt Inventare (ein Item kann einen Takt kodieren); Survival bekommt nie Inhalte, s. Werfer unten.
        h.assertTrue(CompanionSchematics.blockData(outputChest).contains("Items"), "Kreativ: Kisteninhalt bleibt in den Plandaten");
        var craftData = s.data().get(new BlockPos(0, 0, 0));
        h.assertTrue(craftData != null && craftData.getIntArray("disabled_slots").orElseThrow().length == 9,
                "ungültiger Slot 99 muss weg sein");
        var signText = s.data().get(new BlockPos(1, 0, 0)).getCompoundOrEmpty("front_text")
                .getListOrEmpty("messages");
        h.assertValueEqual(signText.getStringOr(1, "?"), "Here", "Klick-Befehl muss als reiner Text ankommen");

        // gedreht platzieren: die Daten wandern mit ihrem Block
        BlockPos feet = h.absolutePos(new BlockPos(1, 1, 1));
        var placed = CompanionSchematics.placeFull(s, feet, Direction.EAST, net.minecraft.world.level.block.Rotation.NONE, false);
        for (var en : placed.data().entrySet())
            h.assertTrue(placed.blocks().get(en.getKey()).hasBlockEntity(), "Daten ohne passenden Block");
        BlockPos craftAt = placed.blocks().entrySet().stream().filter(e -> e.getValue().is(Blocks.CRAFTER))
                .findFirst().orElseThrow().getKey();
        h.assertTrue(placed.data().containsKey(craftAt), "Crafter-Daten nicht mitgedreht");

        // beim Bauen übernehmen; Speichern/Laden des Auftrags behält sie
        var job = CompanionBuilder.create("farm", placed.blocks());
        job.data.putAll(placed.data());
        var out = net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
        CompanionBuilder.save(out, job);
        var loaded = CompanionBuilder.load(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertValueEqual(loaded.data.size(), 3, "Daten nach Laden");
        h.getLevel().setBlock(craftAt, placed.blocks().get(craftAt), CompanionBuilder.PLACE_FLAGS);
        CompanionBuilder.applyData(h.getLevel(), loaded, craftAt, false);
        var be = (net.minecraft.world.level.block.entity.CrafterBlockEntity) h.getLevel().getBlockEntity(craftAt);
        for (int i = 0; i < 9; i++) h.assertTrue(be.isSlotDisabled(i), "Slot " + i + " nicht gesperrt (Survival)");

        // Werfer: Survival nur Einstellungen (keine Gratis-Items), kreativ sein Inhalt
        BlockPos chestAt = placed.blocks().entrySet().stream().filter(e -> e.getValue().is(Blocks.DISPENSER))
                .findFirst().orElseThrow().getKey();
        h.getLevel().setBlock(chestAt, placed.blocks().get(chestAt), CompanionBuilder.PLACE_FLAGS);
        var box = (net.minecraft.world.Container) h.getLevel().getBlockEntity(chestAt);
        CompanionBuilder.applyData(h.getLevel(), loaded, chestAt, false);
        h.assertTrue(box.isEmpty(), "Survival darf den Werferinhalt nicht erschaffen");
        CompanionBuilder.applyData(h.getLevel(), loaded, chestAt, true);
        h.assertValueEqual(box.countItem(net.minecraft.world.item.Items.DIAMOND), 64, "kreativ: Werferinhalt");
        box.clearContent();
        h.succeed();
    }

    @GameTest
    public void liestUnkomprimiertesNbt(GameTestHelper h) {
        try {
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("Probe", "ok");
            var raw = java.nio.file.Files.createTempFile("begleiter-raw", ".litematic");
            var gz = java.nio.file.Files.createTempFile("begleiter-gz", ".litematic");
            try {
                net.minecraft.nbt.NbtIo.write(tag, raw);
                net.minecraft.nbt.NbtIo.writeCompressed(tag, gz);
                h.assertValueEqual(CompanionSchematics.readNbt(raw).getStringOr("Probe", ""), "ok", "unkomprimiert");
                h.assertValueEqual(CompanionSchematics.readNbt(gz).getStringOr("Probe", ""), "ok", "gzip");
            } finally {
                java.nio.file.Files.deleteIfExists(raw);
                java.nio.file.Files.deleteIfExists(gz);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        h.succeed();
    }

    @GameTest
    public void platziertGedrehtVorDemSpieler(GameTestHelper h) {
        var s = CompanionSchematics.loadSponge(blockLookup(h), "v2", spongeV2());
        BlockPos feet = new BlockPos(100, 64, 100);
        var placed = CompanionSchematics.place(s, feet, Direction.EAST, net.minecraft.world.level.block.Rotation.NONE, false);
        h.assertValueEqual(placed.size(), 2, "Luft darf ohne 'luft' nicht dabei sein");
        for (BlockPos p : placed.keySet()) {
            h.assertTrue(p.getX() >= feet.getX() + 2, "Nicht vor dem Spieler: " + p.toShortString());
            h.assertValueEqual(p.getY(), feet.getY(), "Höhe");
        }
        BlockState stairs = placed.values().stream().filter(b -> b.is(Blocks.OAK_STAIRS)).findFirst().orElseThrow();
        h.assertValueEqual(stairs.getValue(net.minecraft.world.level.block.StairBlock.FACING), Direction.NORTH,
                "Treppe nicht mitgedreht (Blick Osten = gegen den Uhrzeigersinn)");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void bautSchematicAusDatei(GameTestHelper h) throws Exception {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        owner(h, c, GameType.CREATIVE);
        java.nio.file.Path file = java.nio.file.Files.createTempFile("begleiter", ".schem");
        net.minecraft.nbt.NbtIo.writeCompressed(spongeV2(), file);
        var s = CompanionSchematics.load(h.getLevel(), file);
        java.nio.file.Files.deleteIfExists(file);
        var blocks = CompanionSchematics.place(s, h.absolutePos(new BlockPos(1, 1, 3)), Direction.SOUTH,
                net.minecraft.world.level.block.Rotation.NONE, false);
        c.build = CompanionBuilder.create(s.name(), blocks);
        c.addTask(Task.build());

        h.succeedWhen(() -> {
            h.assertTrue(c.build == null, "Bau nicht fertig (" + c.currentTaskText() + ")");
            for (var en : blocks.entrySet())
                h.assertValueEqual(h.getLevel().getBlockState(en.getKey()), en.getValue(), "Block bei " + en.getKey().toShortString());
        });
    }

    // ------------------------------------------------------------------ Aufgaben (KI läuft echt)

    @GameTest(maxTicks = 1200)
    public void graebtStollenUndFindetVerstecktesEisen(GameTestHelper h) {
        floor(h);
        // massiver Stein, nur eine kleine Nische für den Begleiter; Eisen versteckt in der Stollenwand
        for (int x = 0; x < 8; x++)
            for (int y = 1; y <= 3; y++)
                for (int z = 0; z < 8; z++) h.setBlock(x, y, z, Blocks.STONE);
        h.setBlock(1, 1, 1, Blocks.AIR);
        h.setBlock(1, 2, 1, Blocks.AIR);
        h.setBlock(5, 1, 2, Blocks.IRON_ORE);

        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        c.mineDir = Direction.EAST;
        c.addTask(Task.gather(Resources.IRON.key(), 1));

        h.succeedWhen(() -> {
            h.assertTrue(c.findItem(Items.RAW_IRON) >= 0, "Kein Roheisen (Aufgabe " + c.currentTaskText()
                    + ", bei " + h.relativePos(c.blockPosition()).toShortString() + ")");
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(5, 1, 2))).isAir(), "Erz steht noch");
        });
    }

    @GameTest(maxTicks = 400)
    public void baumstammAbbauen(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        BlockPos log = new BlockPos(4, 1, 1);
        h.setBlock(log, Blocks.OAK_LOG);
        c.addTask(Task.breakAt(h.absolutePos(log), null));

        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(log)).isAir(), "Stamm steht noch");
            h.assertTrue(c.findItem(Items.OAK_LOG) >= 0, "Stamm nicht im Inventar (Hand: " + c.getMainHandItem() + ")");
            h.assertTrue(!c.hasTasks(), "Aufgabe nicht abgeschlossen");
        });
    }

    /** Hoher Baum mit Gerüst: von oben abgebaute Stämme fallen hinunter – am Ende alle im Inventar, nichts liegt herum. */
    @GameTest(maxTicks = 1200, structure = "begleiter-gametest:bench")
    public void hoherBaumAlleStaemmeEingesammelt(GameTestHelper h) {
        for (int x = 0; x < 12; x++)
            for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        for (int y = 8; y <= 10; y++)
            for (int x = 4; x <= 8; x++)
                for (int z = 4; z <= 8; z++) h.setBlock(new BlockPos(x, y, z), leaves);
        for (int y = 1; y <= 10; y++) h.setBlock(new BlockPos(6, y, 6), Blocks.OAK_LOG);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.DIRT, 32));
        c.addTask(Task.tree(h.absolutePos(new BlockPos(6, 1, 6)), 10));
        h.succeedWhen(() -> {
            h.assertTrue(!c.hasTasks(), "Noch bei der Arbeit: " + c.currentTaskText());
            int loose = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    c.getBoundingBox().inflate(16), i -> i.getItem().is(Items.OAK_LOG)).stream().mapToInt(i -> i.getItem().getCount()).sum();
            h.assertValueEqual(loose, 0, "Stämme liegen noch herum");
            List<Integer> standing = new java.util.ArrayList<>();
            for (int y = 1; y <= 10; y++) if (h.getBlockState(new BlockPos(6, y, 6)).is(Blocks.OAK_LOG)) standing.add(y);
            h.assertTrue(standing.isEmpty(), "Stämme stehen noch auf Höhe " + standing + " (er steht bei "
                    + h.relativePos(c.blockPosition()).toShortString() + ")");
            h.assertValueEqual(CompanionCraft.count(c, "oak_log"), 10, "Stämme im Inventar");
        });
    }

    /**
     * Nur Bruchstein, keine Spitzhacke: daraus kein Baum-Gerüst bauen (das er nicht wieder abbauen könnte – er saß dann
     * oben fest), sondern erst Erde holen. Am Ende steht er wieder unten, ohne Gerüst.
     */
    @GameTest(maxTicks = 1600, structure = "begleiter-gametest:bench")
    public void baumGeruestNurAusAbbaubarenBloecken(GameTestHelper h) {
        for (int x = 0; x < 12; x++)
            for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.DIRT);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        for (int y = 6; y <= 7; y++)
            for (int x = 4; x <= 8; x++)
                for (int z = 4; z <= 8; z++) h.setBlock(new BlockPos(x, y, z), leaves);
        for (int y = 1; y <= 7; y++) h.setBlock(new BlockPos(6, y, 6), Blocks.OAK_LOG);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 2));
        c.stay();
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 32));
        h.assertTrue(c.scaffoldItem(CompanionEntity.Pillar.TREE) == null, "Bruchstein ohne Spitzhacke als abbaubares Gerüst gezählt");
        c.addTask(Task.tree(h.absolutePos(new BlockPos(6, 1, 6)), 7));
        h.succeedWhen(() -> {
            h.assertTrue(!c.hasTasks(), "Noch bei der Arbeit: " + c.currentTaskText());
            h.assertTrue(!c.hasScaffold(), "Gerüst steht noch (" + c.scaffolding.size() + " Blöcke)");
            h.assertValueEqual(h.relativePos(c.blockPosition()).getY(), 1, "Steht nicht wieder unten");
            h.assertValueEqual(CompanionCraft.count(c, "oak_log"), 7, "Stämme im Inventar");
        });
    }

    // Beide Varianten: Minecraft tickt KI-Ziele je nach Entity-ID in geraden oder ungeraden Ticks.
    @GameTest(maxTicks = 400)
    public void zumLagerLaufenGeradeId(GameTestHelper h) {
        zumLagerLaufenUndEinlagern(h, 0);
    }

    @GameTest(maxTicks = 400)
    public void zumLagerLaufenUngeradeId(GameTestHelper h) {
        zumLagerLaufenUndEinlagern(h, 1);
    }

    private void zumLagerLaufenUndEinlagern(GameTestHelper h, int idParity) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        if (Math.floorMod(c.getId(), 2) != idParity) {
            c.discard();
            c = companion(h, new BlockPos(1, 1, 1));
        }
        h.assertValueEqual(Math.floorMod(c.getId(), 2), idParity, "Entity-ID-Parität");
        zumLager(h, c);
    }

    private void zumLager(GameTestHelper h, CompanionEntity c) {
        BlockPos home = new BlockPos(6, 1, 6);
        Container box = chest(h, c, home);
        c.setHome(h.absolutePos(home));
        c.giveToInventory(new ItemStack(Items.DIRT, 5));
        c.addTask(Task.deliver(false));
        List<String> trace = new ArrayList<>();
        h.onEachTick(() -> {
            if (c.tickCount % 40 != 0) return;
            var path = c.getNavigation().getPath();
            trace.add(h.relativePos(c.blockPosition()).toShortString()
                    + (c.getTarget() != null ? " ziel=" + c.getTarget().getType().toShortString() : "")
                    + (path == null ? " kein-pfad" : " pfad->" + h.relativePos(path.getTarget()).toShortString()
                    + (path.canReach() ? "" : "(unerreichbar)")));
        });

        h.succeedWhen(() -> {
            h.assertValueEqual(count(box, Items.DIRT), 5, "Erde in der Lagerkiste (Aufgabe " + c.currentTaskText()
                    + ", Weg " + trace + ")");
            h.assertTrue(!c.hasTasks(), "Aufgabe nicht abgeschlossen");
        });
    }
}
