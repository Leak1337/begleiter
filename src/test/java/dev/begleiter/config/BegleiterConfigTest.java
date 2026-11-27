package dev.begleiter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Laden, Grenzen und Fehlerverhalten der Serverkonfiguration ohne Minecraft-Start. */
class BegleiterConfigTest {
    @TempDir Path dir;

    @BeforeEach @AfterEach
    void defaults() {
        BegleiterConfig.set(new BegleiterConfig());
    }

    private Path file(String json) throws IOException {
        Path f = dir.resolve(BegleiterConfig.FILE_NAME);
        Files.writeString(f, json);
        return f;
    }

    @Test
    void validFileReplacesTheRunningSettings() throws IOException {
        assertNull(BegleiterConfig.reload(file("{\"talk\":\"QUIET\",\"prefix\":\"!\",\"gatherRadius\":30}")));
        assertEquals(BegleiterConfig.Talk.QUIET, BegleiterConfig.get().talk);
        assertEquals("!", BegleiterConfig.get().prefix);
        assertEquals(30, BegleiterConfig.get().gatherRadius);
    }

    @Test
    void missingKeysKeepTheirDefaults() throws IOException {
        assertNull(BegleiterConfig.reload(file("{\"xray\":false}")));
        BegleiterConfig c = BegleiterConfig.get();
        assertEquals(false, c.xray);
        assertEquals(BegleiterConfig.Address.ALWAYS, c.address);
        assertEquals(32, c.xrayRadius);
        assertEquals(6.0, c.navBudgetMillis);
    }

    @Test
    void outOfRangeValuesAreClampedNotRejected() throws IOException {
        assertNull(BegleiterConfig.reload(file(
                "{\"xrayRadius\":1000,\"navBudgetMillis\":0.01,\"gatherRadius\":-5,\"maxCompanionsPerPlayer\":99}")));
        BegleiterConfig c = BegleiterConfig.get();
        assertEquals(64, c.xrayRadius);
        assertEquals(1.0, c.navBudgetMillis);
        assertEquals(8, c.gatherRadius);
        assertEquals(16, c.maxCompanionsPerPlayer);
    }

    @Test
    void unknownEnumValuesFallBackToDefaults() throws IOException {
        assertNull(BegleiterConfig.reload(file("{\"address\":\"SOMETIMES\",\"talk\":\"LOUD\"}")));
        assertEquals(BegleiterConfig.Address.ALWAYS, BegleiterConfig.get().address);
        assertEquals(BegleiterConfig.Talk.NORMAL, BegleiterConfig.get().talk);
    }

    @Test
    void prefixesThatWouldEatNormalWordsAreReplaced() throws IOException {
        for (String bad : new String[]{"\"\"", "\"   \"", "\"a\"", "\"#1\"", "\"@@@@@\""}) {
            assertNull(BegleiterConfig.reload(file("{\"prefix\":" + bad + "}")), bad);
            assertEquals("@", BegleiterConfig.get().prefix, bad);
        }
    }

    @Test
    void brokenFileKeepsTheRunningSettings() throws IOException {
        assertNull(BegleiterConfig.reload(file("{\"talk\":\"CHATTY\"}")));
        BegleiterConfig before = BegleiterConfig.get();
        assertNotNull(BegleiterConfig.reload(file("{\"talk\": CHATTY,,}")));
        assertNotNull(BegleiterConfig.reload(file("{\"gatherRadius\":\"weit\"}")));
        assertSame(before, BegleiterConfig.get());
        assertEquals(BegleiterConfig.Talk.CHATTY, BegleiterConfig.get().talk);
    }

    @Test
    void emptyOrMissingFileIsAnErrorNotAReset() throws IOException {
        BegleiterConfig before = BegleiterConfig.get();
        assertNotNull(BegleiterConfig.reload(file("")));
        assertNotNull(BegleiterConfig.reload(dir.resolve("does-not-exist.json")));
        assertSame(before, BegleiterConfig.get());
    }

    @Test
    void writtenDefaultsReadBackUnchanged() throws IOException {
        Path f = dir.resolve("sub").resolve(BegleiterConfig.FILE_NAME);
        BegleiterConfig.write(f, new BegleiterConfig());
        assertNull(BegleiterConfig.reload(f));
        assertEquals(new BegleiterConfig().toJson(), BegleiterConfig.get().toJson());
    }

    @Test
    void navBudgetIsConvertedToNanos() {
        BegleiterConfig c = new BegleiterConfig();
        c.navBudgetMillis = 2.5;
        BegleiterConfig.set(c);
        assertEquals(2_500_000L, BegleiterConfig.get().navBudgetNanos());
    }
}
