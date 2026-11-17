package dev.begleiter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Projektvorbereitung darf die Welt niemals verändern. */
public class CompanionProjektGameTests {
    @GameTest
    public void projektVorschauHatAbhaengigkeitenAberBautNicht(GameTestHelper h) {
        var spieler = h.makeMockServerPlayerInLevel();
        BlockPos start = h.absolutePos(new BlockPos(2, 1, 2));
        spieler.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        var schema = CompanionStructures.house(CompanionStructures.WOOD, 5, 5);
        var plan = CompanionSchematics.place(schema, spieler.blockPosition(),
                spieler.getDirection(), Rotation.NONE, true);
        Map<BlockPos, BlockState> vorher = new HashMap<>();
        for (BlockPos pos : plan.keySet()) {
            if (h.getLevel().hasChunkAt(pos)) vorher.put(pos, h.getLevel().getBlockState(pos));
        }
        var vorschau = CompanionProjektVorschau.planen(spieler);
        h.assertValueEqual(vorschau.schritte().size(), 5, "Projekt muss fünf Schritte haben");
        h.assertValueEqual(vorschau.schritte().getLast().voraussetzungen(), List.of(4),
                "Letzte Prüfung muss vom Bau abhängen");
        ServerTextGameTests.assertTranslatedContains(h, vorschau.beschreibenText(), "Nur Vorschau", "Preview only");
        h.assertTrue(!vorschau.material().isEmpty(), "Baumaterial wird nicht berechnet");
        for (var eintrag : vorher.entrySet())
            h.assertTrue(h.getLevel().getBlockState(eintrag.getKey()).equals(eintrag.getValue()),
                    "Die Vorschau hat einen Weltblock verändert");
        h.succeed();
    }

    @GameTest
    public void projektVorschauSchuetztVorhandeneKisten(GameTestHelper h) {
        var spieler = h.makeMockServerPlayerInLevel();
        BlockPos start = h.absolutePos(new BlockPos(2, 1, 2));
        spieler.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        var schema = CompanionStructures.house(CompanionStructures.WOOD, 5, 5);
        var plan = CompanionSchematics.place(schema, spieler.blockPosition(),
                spieler.getDirection(), Rotation.NONE, true);
        BlockPos ort = plan.keySet().iterator().next();
        h.getLevel().setBlock(ort, Blocks.CHEST.defaultBlockState(), 3);
        var vorschau = CompanionProjektVorschau.planen(spieler);
        h.assertTrue(!vorschau.sicher(), "Die Kiste im Projektgebiet wurde nicht erkannt");
        ServerTextGameTests.assertTranslatedContains(h, vorschau.hinweisText(), "Kiste", "chest");
        h.assertTrue(h.getLevel().getBlockState(ort).is(Blocks.CHEST),
                "Vorhandene Kiste wurde bei der Vorschau beschädigt");
        h.succeed();
    }

    @GameTest
    public void bestaetigungSpeichertNurEntwurfUndIstWiederherstellbar(GameTestHelper h) {
        var schritte = List.of(
                new CompanionProjektVorschau.Schritt(1, "Prüfen", List.of()),
                new CompanionProjektVorschau.Schritt(2, "Bauen", List.of(1)));
        var sichereVorschau = new CompanionProjektVorschau.Vorschau(
                schritte, Map.of(), 12, 3, true, "");
        var verwaltung = new CompanionProjektVerwaltung();

        var bestaetigt = verwaltung.bestaetigen(sichereVorschau,
                new BlockPos(4, 2, 4), Direction.EAST);
        h.assertTrue(bestaetigt.erfolgreich(), "Sichere Vorschau wurde nicht als Entwurf gespeichert");
        ServerTextGameTests.assertTranslatedContains(h, verwaltung.beschreibenText(),
                "Noch keine Ausführung freigegeben", "Execution has not been approved");

        var wiederhergestellt = new CompanionProjektVerwaltung();
        wiederhergestellt.laden(verwaltung.speichern());
        h.assertTrue(wiederhergestellt.vorhanden(), "Projektentwurf wurde nicht gespeichert");
        h.assertTrue(wiederhergestellt.beschreiben().contains("4, 2, 4"),
                "Projektstandort wurde nicht sicher wiederhergestellt");

        var zweites = verwaltung.bestaetigen(sichereVorschau, BlockPos.ZERO, Direction.NORTH);
        h.assertTrue(!zweites.erfolgreich(), "Ein zweiter Entwurf darf den ersten nicht ersetzen");
        h.assertTrue(verwaltung.verwerfen().erfolgreich(), "Entwurf konnte nicht verworfen werden");
        h.assertTrue(!verwaltung.vorhanden(), "Verwerfen hat den Entwurf nicht entfernt");
        h.succeed();
    }

    @GameTest
    public void unsichereVorschauWirdNichtAlsEntwurfGespeichert(GameTestHelper h) {
        var unsichereVorschau = new CompanionProjektVorschau.Vorschau(
                List.of(new CompanionProjektVorschau.Schritt(1, "Prüfen", List.of())),
                Map.of(), 1, 1, false, "Flüssigkeit im Baugebiet");
        var verwaltung = new CompanionProjektVerwaltung();
        var ergebnis = verwaltung.bestaetigen(unsichereVorschau, BlockPos.ZERO, Direction.NORTH);
        h.assertTrue(!ergebnis.erfolgreich(), "Unsichere Vorschau wurde bestätigt");
        h.assertTrue(!verwaltung.vorhanden(), "Unsichere Vorschau hinterließ einen Entwurf");
        h.succeed();
    }

    @GameTest
    public void projektGraphLehntZyklenUndFehlendeVoraussetzungenAb(GameTestHelper h) {
        var zyklus = List.of(
                new CompanionProjektVorschau.Schritt(1, "A", List.of(2)),
                new CompanionProjektVorschau.Schritt(2, "B", List.of(1)));
        var fehlend = List.of(new CompanionProjektVorschau.Schritt(1, "A", List.of(9)));
        h.assertTrue(!CompanionProjektVorschau.graphPruefen(zyklus).gueltig(),
                "Zyklischer Projektgraph wurde akzeptiert");
        h.assertTrue(!CompanionProjektVorschau.graphPruefen(fehlend).gueltig(),
                "Fehlende Voraussetzung wurde akzeptiert");
        h.assertTrue(CompanionProjektVorschau.graphPruefen(List.of(
                new CompanionProjektVorschau.Schritt(1, "A", List.of()),
                new CompanionProjektVorschau.Schritt(2, "B", List.of(1)))).gueltig(),
                "Gültiger Projektgraph wurde abgelehnt");
        h.succeed();
    }

    @GameTest
    public void erneutePruefungErkenntZwischenzeitlicheKiste(GameTestHelper h) {
        var spieler = h.makeMockServerPlayerInLevel();
        BlockPos start = h.absolutePos(new BlockPos(2, 1, 2));
        spieler.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        var schema = CompanionStructures.house(CompanionStructures.WOOD, 5, 5);
        BlockPos standort = spieler.blockPosition();
        Direction ausrichtung = spieler.getDirection();
        var plan = CompanionSchematics.place(schema, standort, ausrichtung, Rotation.NONE, true);
        // Der Revalidierungstest braucht einen nachweislich sicheren Ausgangszustand;
        // die Teststruktur kann je nach GameTest-Umgebung geschützte Randblöcke enthalten.
        for (BlockPos pos : plan.keySet())
            h.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        var vorschau = CompanionProjektVorschau.planen(spieler);
        var verwaltung = new CompanionProjektVerwaltung();
        var dimension = h.getLevel().dimension().identifier().toString();
        var bestaetigung = verwaltung.bestaetigen(vorschau, dimension, standort, ausrichtung);
        h.assertTrue(bestaetigung.erfolgreich(),
                "Sichere Vorschau konnte nicht als Entwurf gespeichert werden: " + bestaetigung.nachricht());
        BlockPos ort = plan.keySet().iterator().next();
        h.getLevel().setBlock(ort, Blocks.CHEST.defaultBlockState(), 3);
        var pruefung = verwaltung.pruefen(h.getLevel());
        h.assertTrue(!pruefung.erfolgreich(), "Zwischenzeitliche Kiste wurde bei der Prüfung übersehen");
        ServerTextGameTests.assertTranslatedContains(h, pruefung.nachrichtText(), "Kiste", "chest");
        h.assertTrue(h.getLevel().getBlockState(ort).is(Blocks.CHEST),
                "Erneute Prüfung hat die Welt verändert");
        h.assertTrue(verwaltung.vorhanden(), "Blockierter Entwurf wurde unerwartet gelöscht");
        h.succeed();
    }
}
