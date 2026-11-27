package dev.begleiter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Wartezeit und Beutegruppen des simulierten Angelns, ohne Spielstart. */
class FishingRulesTest {
    @Test
    void wartezeitFolgtVanillaUndKoederVerkuerzt() {
        assertEquals(100, FishingRules.waitTicks(0, 0));
        assertEquals(600, FishingRules.waitTicks(500, 0));
        assertEquals(600, FishingRules.waitTicks(9999, 0), "Wurf über dem Bereich wird begrenzt");
        assertEquals(400, FishingRules.waitTicks(500, 2));
        assertEquals(FishingRules.FLOOR_WAIT, FishingRules.waitTicks(0, 3), "nie kürzer als die Untergrenze");
        assertEquals(100, FishingRules.waitTicks(-5, -1), "negative Werte zählen als 0");
    }

    @Test
    void ohneGlueckGeltenDieVanillaGewichte() {
        assertEquals(100, FishingRules.totalWeight(0, true));
        assertEquals(95, FishingRules.totalWeight(0, false), "ohne offenes Wasser fällt der Schatz weg");
        assertEquals(FishingRules.Catch.FISH, FishingRules.pick(0, 0, true));
        assertEquals(FishingRules.Catch.FISH, FishingRules.pick(84, 0, true));
        assertEquals(FishingRules.Catch.JUNK, FishingRules.pick(85, 0, true));
        assertEquals(FishingRules.Catch.JUNK, FishingRules.pick(94, 0, true));
        assertEquals(FishingRules.Catch.TREASURE, FishingRules.pick(95, 0, true));
        assertEquals(FishingRules.Catch.TREASURE, FishingRules.pick(99, 0, true));
    }

    @Test
    void geschlossenesWasserLiefertNieSchaetze() {
        for (float luck : new float[]{0, 1, 2, 3})
            for (int roll = 0; roll < 200; roll++)
                assertNotEquals(FishingRules.Catch.TREASURE, FishingRules.pick(roll, luck, false), "Glück " + luck + ", Wurf " + roll);
    }

    @Test
    void glueckDesMeeresVerschiebtZuSchaetzen() {
        // Stufe 3: Fisch 82, Schrott 4, Schatz 11 (wie in Vanilla)
        assertEquals(82, FishingRules.fishWeight(3));
        assertEquals(4, FishingRules.junkWeight(3));
        assertEquals(11, FishingRules.treasureWeight(3, true));
        assertEquals(0, FishingRules.junkWeight(6), "Gewicht nie negativ");
        int treasure = 0;
        for (int roll = 0; roll < FishingRules.totalWeight(3, true); roll++)
            if (FishingRules.pick(roll, 3, true) == FishingRules.Catch.TREASURE) treasure++;
        assertEquals(11, treasure);
    }

    @Test
    void fangmengeHatVorgabeUndObergrenze() {
        assertEquals(FishingRules.DEFAULT_CATCHES, FishingRules.catches(0));
        assertEquals(FishingRules.DEFAULT_CATCHES, FishingRules.catches(-3));
        assertEquals(5, FishingRules.catches(5));
        assertEquals(FishingRules.MAX_CATCHES, FishingRules.catches(1000));
    }
}
