package dev.begleiter;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** GameTests für das Kampf-Verhalten: Besitzer verteidigen, Kampfmodus, Bedrohungen, Creeper, Heilen. */
public class CombatGameTests {

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    private static CompanionEntity companion(GameTestHelper h, BlockPos rel) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, rel);
        c.setPersistenceRequired();
        // Der Test-Spieler ist im Kreativ-Modus; dort kämpft der Begleiter bewusst nicht von selbst. Hier: Survival.
        c.buildModeOverride = false;
        return c;
    }

    private static net.minecraft.server.level.ServerPlayer owner(GameTestHelper h, CompanionEntity c, BlockPos rel) {
        var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID());
        BlockPos a = h.absolutePos(rel);
        p.snapTo(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
        return p;
    }

    /** Zombie ohne eigene KI (steht still, behält ein gesetztes Ziel). */
    private static net.minecraft.world.entity.Mob dummy(GameTestHelper h, BlockPos rel) {
        var z = h.spawn(EntityTypes.ZOMBIE, rel);
        z.setNoAi(true);
        return z;
    }

    /** Feindliche Mobs wählen den Begleiter von selbst als Ziel wie einen Spieler (kein Zwilling doppelt). */
    @GameTest(maxTicks = 200)
    public void zombieGreiftBegleiterWieSpielerAn(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stay();
        c.stance = CompanionCombat.Stance.PASSIVE; // er greift nicht zuerst an: das Ziel kommt nicht aus Vergeltung
        var zombie = h.spawn(EntityTypes.ZOMBIE, new BlockPos(5, 1, 5));
        h.assertValueEqual(CompanionHostility.addTargets(zombie), 0, "Begleiter-Ziel doppelt eingetragen");
        h.succeedWhen(() -> h.assertTrue(zombie.getTarget() == c, "Zombie wählt den Begleiter nicht als Ziel: " + zombie.getTarget()));
    }

    @GameTest(maxTicks = 100)
    public void verteidigtBesitzerGegenAngreifer(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stance = CompanionCombat.Stance.DEFENSIVE;
        var p = owner(h, c, new BlockPos(2, 1, 2));
        var zombie = dummy(h, new BlockPos(6, 1, 6));
        p.setLastHurtByMob(zombie);
        h.succeedWhen(() -> h.assertTrue(c.getTarget() == zombie, "Verteidigt den Besitzer nicht"));
    }

    @GameTest(maxTicks = 100)
    public void hilftBeimAngriffDesBesitzers(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        var p = owner(h, c, new BlockPos(2, 1, 2));
        var cow = h.spawn(EntityTypes.COW, new BlockPos(6, 1, 6));
        cow.setNoAi(true);
        p.setLastHurtMob(cow);
        h.succeedWhen(() -> h.assertTrue(c.getTarget() == cow, "Hilft nicht beim Angriff des Besitzers"));
    }

    @GameTest(maxTicks = 80)
    public void defensivLaesstUnbeteiligteInRuhe(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stance = CompanionCombat.Stance.DEFENSIVE;
        owner(h, c, new BlockPos(2, 1, 2));
        var zombie = dummy(h, new BlockPos(5, 1, 5));
        h.assertTrue(!CompanionCombat.wantsToEngage(c, zombie), "Defensiv greift Unbeteiligte an");
        c.stance = CompanionCombat.Stance.AGGRESSIVE;
        h.assertTrue(CompanionCombat.wantsToEngage(c, zombie), "Aggressiv greift Monster nicht an");
        c.stance = CompanionCombat.Stance.DEFENSIVE;
        h.runAfterDelay(60, () -> {
            h.assertTrue(c.getTarget() == null, "Defensiv: hat trotzdem ein Ziel");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void passivFliehtStattZuKaempfen(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stance = CompanionCombat.Stance.PASSIVE;
        var p = owner(h, c, new BlockPos(6, 1, 1));
        var zombie = dummy(h, new BlockPos(2, 1, 3));
        p.setLastHurtByMob(zombie);
        c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1f);
        h.succeedWhen(() -> {
            h.assertTrue(c.isRetreating(), "Passiv: kein Rückzug nach Angriff");
            h.assertTrue(c.getTarget() == null, "Passiv: greift an");
        });
    }

    @GameTest
    public void bedrohungDesBesitzersGehtVor(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        var p = owner(h, c, new BlockPos(6, 1, 6));
        var near = dummy(h, new BlockPos(2, 1, 1));
        var hunter = dummy(h, new BlockPos(6, 1, 4));
        // Testspieler lassen sich nicht als Mob-Ziel setzen – ein Mob, der den Begleiter angreift, zählt genauso als Bedrohung
        hunter.setTarget(c);
        h.assertTrue(hunter.getTarget() == c, "Testaufbau: Zombie hat den Begleiter nicht im Ziel");
        h.assertTrue(CompanionCombat.threat(c, hunter) > CompanionCombat.threat(c, near), "Angreifer zählt nicht mehr");
        h.assertTrue(CompanionCombat.bestThreat(c, 16) == hunter, "Falsches wichtigstes Ziel");
        h.succeed();
    }

    @GameTest
    public void schiesstNichtDurchDenBesitzer(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        var p = owner(h, c, new BlockPos(3, 1, 1));
        var zombie = dummy(h, new BlockPos(6, 1, 1));
        h.assertTrue(CompanionCombat.ownerInLine(c, zombie), "Besitzer in der Schusslinie nicht erkannt");
        BlockPos side = h.absolutePos(new BlockPos(3, 1, 6));
        p.snapTo(side.getX() + 0.5, side.getY(), side.getZ() + 0.5, 0, 0);
        h.assertTrue(!CompanionCombat.ownerInLine(c, zombie), "Freie Schusslinie blockiert");
        h.succeed();
    }

    @GameTest(maxTicks = 60)
    public void creeperMitBogenAufAbstand(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.giveToInventory(new ItemStack(Items.BOW));
        c.giveToInventory(new ItemStack(Items.ARROW, 16));
        var creeper = h.spawn(EntityTypes.CREEPER, new BlockPos(3, 1, 3));
        creeper.setNoAi(true);
        c.setTarget(creeper);
        h.succeedWhen(() -> h.assertTrue(c.isRetreating(), "Geht beim Creeper nicht auf Abstand"));
    }

    @GameTest(maxTicks = 60)
    public void goldenerApfelImNotfall(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.setHealth(5f);
        c.giveToInventory(new ItemStack(Items.GOLDEN_APPLE));
        c.setTarget(dummy(h, new BlockPos(6, 1, 6)));
        h.succeedWhen(() -> {
            h.assertTrue(c.findItem(Items.GOLDEN_APPLE) < 0, "Goldener Apfel nicht gegessen");
            h.assertTrue(c.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION), "Keine Regeneration");
        });
    }

    /** Wenig Leben unter Beschuss (Skelett in der Höhle): einmauern statt heimlaufen, geheilt wieder öffnen. */
    @GameTest(maxTicks = 120)
    public void mauertSichUnterBeschussEin(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 3));
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 16));
        var skeleton = h.spawn(EntityTypes.SKELETON, new BlockPos(3, 1, 7));
        skeleton.setNoAi(true);
        BlockPos feet = new BlockPos(3, 1, 3);
        boolean[] sealed = {false};
        h.runAfterDelay(10, () -> { // erst gelandet (in der Luft mauert er sich nicht ein)
            c.setHealth(5f);
            c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(skeleton), 1f);
            h.assertTrue(c.inDanger(), "Treffer zählt nicht als Gefahr");
            // Ohne Werkzeug wäre Ausrüstung dringender – hier geht es nur ums Überleben
            var top = CompanionNeeds.evaluate(c).stream().filter(s -> s.need() == CompanionNeeds.Need.SURVIVAL).findFirst();
            h.assertTrue(top.isPresent(), "Kein Überlebensbedürfnis bei " + (int) c.getHealth() + " Leben");
            h.assertTrue(CompanionNeeds.act(c, top.get()), "Überleben plant nichts");
            h.assertTrue(c.isSheltered(), "Mauert sich nicht ein");
            h.assertTrue(!c.hasTasks(), "Läuft trotz Unterschlupf los: " + c.currentTaskText());
            h.assertValueEqual(c.blockPosition(), h.absolutePos(feet), "Steht nicht mehr auf seinem Platz");
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                h.assertBlockPresent(Blocks.COBBLESTONE, feet.relative(d));
                h.assertBlockPresent(Blocks.COBBLESTONE, feet.above().relative(d));
            }
            h.assertBlockPresent(Blocks.COBBLESTONE, feet.above(2));
            h.assertValueEqual(CompanionCraft.count(c, "cobblestone"), 16 - 9, "Blöcke nicht verbraucht");
            var inside = CompanionNeeds.evaluate(c).getFirst();
            h.assertTrue(inside.need() == CompanionNeeds.Need.SURVIVAL, "Verlässt den Unterschlupf zu früh: " + inside);
            c.setHealth(c.getMaxHealth());
            skeleton.discard(); // Schütze weg: der Unterschlupf bleibt sonst zu, solange ein Monster davor steht
            h.setTime(1000); // bei Tag (nachts an der Oberfläche bleibt er bis zum Morgen drin)
            h.getLevel().updateSkyBrightness();
            sealed[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(sealed[0], "Noch nicht eingemauert");
            h.assertTrue(!c.isSheltered(), "Öffnet den Unterschlupf nach dem Heilen nicht");
            h.assertBlockNotPresent(Blocks.COBBLESTONE, feet.above(2));
        });
    }

    /** Mit wenig Leben sucht er sich keine neue, „wichtigere“ Bedrohung – sonst greift er nach jedem Rückzug wieder an. */
    @GameTest(maxTicks = 60)
    public void wechseltMitWenigLebenNichtAufNeueBedrohung(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        c.stance = CompanionCombat.Stance.DEFENSIVE;
        owner(h, c, new BlockPos(2, 1, 2));
        var hunter = dummy(h, new BlockPos(6, 1, 4));
        hunter.setTarget(c);
        c.setHealth(8f);
        h.runAfterDelay(40, () -> {
            h.assertTrue(c.getTarget() != hunter, "Nimmt mit 8 Leben trotzdem die Bedrohung ins Ziel");
            h.succeed();
        });
    }

    @GameTest
    public void kampfmodusPerChatUndSpeichern(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        var p = owner(h, c, new BlockPos(2, 1, 2));
        CompanionBrain.onChat(p, "@ Sei defensiv");
        h.assertValueEqual(c.stance, CompanionCombat.Stance.DEFENSIVE, "Defensiv");
        CompanionBrain.onChat(p, "@ Kämpf nicht");
        h.assertValueEqual(c.stance, CompanionCombat.Stance.PASSIVE, "Passiv");
        CompanionBrain.onChat(p, "@ Beschütz mich");
        h.assertValueEqual(c.stance, CompanionCombat.Stance.DEFENSIVE, "Beschützen hebt Passiv auf");
        CompanionBrain.onChat(p, "@ Greif alles an");
        h.assertValueEqual(c.stance, CompanionCombat.Stance.AGGRESSIVE, "Aggressiv");
        h.assertTrue(CompanionCombat.stanceFrom(List.of("bring", "mir", "holz"), "bring mir holz") == null, "Falscher Kampfbefehl");

        c.stance = CompanionCombat.Stance.PASSIVE;
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        c.saveWithoutId(out);
        CompoundTag tag = out.buildResult();
        CompanionEntity copy = CompanionMod.COMPANION.create(h.getLevel(), EntitySpawnReason.LOAD);
        copy.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        h.assertValueEqual(copy.stance, CompanionCombat.Stance.PASSIVE, "Kampfmodus nach dem Laden");
        h.succeed();
    }

    @GameTest
    public void freundeWerdenNieAngegriffen(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        var villager = h.spawn(EntityTypes.VILLAGER, new BlockPos(5, 1, 5));
        var other = companion(h, new BlockPos(3, 1, 3));
        h.assertTrue(CompanionCombat.isFriend(c, villager), "Dorfbewohner kein Freund");
        h.assertTrue(CompanionCombat.isFriend(c, other), "Anderer Begleiter kein Freund");
        h.assertTrue(!CompanionCombat.wantsToEngage(c, villager), "Will Dorfbewohner angreifen");
        h.succeed();
    }
}
