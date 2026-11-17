package dev.begleiter.task;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.begleiter.CompanionEntity.TaskType;

import static org.junit.jupiter.api.Assertions.*;

/** Lebenszyklus der Handler ohne Minecraft-Start: start/stop genau einmal, Wechsel und Ersetzung. */
class TaskSlotTest {
    private final List<String> events = new ArrayList<>();

    private final class Recorder implements TaskHandler {
        final String name;
        boolean failStart, failStop;

        Recorder(String name) { this.name = name; }

        @Override public void start(TaskContext ctx) {
            events.add("start " + name);
            if (failStart) throw new IllegalStateException("start");
        }
        @Override public Status tick(TaskContext ctx) { events.add("tick " + name); return Status.RUNNING; }
        @Override public void stop(TaskContext ctx) {
            events.add("stop " + name);
            if (failStop) throw new IllegalStateException("stop");
        }
    }

    private TaskSlot<String> slot() {
        return new TaskSlot<>(t -> t.startsWith("-") ? null : new Recorder(t));
    }

    @Test
    void startUndStopGenauEinmalProAufgabe() {
        TaskSlot<String> s = slot();
        String a = "a";
        TaskHandler h = s.sync(a, null);
        h.tick(null);
        assertSame(h, s.sync(a, null), "gleiche Aufgabe darf keinen neuen Handler bekommen");
        h.tick(null);
        s.release(null);
        s.release(null);
        assertEquals(List.of("start a", "tick a", "tick a", "stop a"), events);
        assertFalse(s.active());
        assertNull(s.task());
    }

    @Test
    void wechselStopptAltenVorDemNeuen() {
        TaskSlot<String> s = slot();
        s.sync("a", null);
        s.sync("b", null);
        assertEquals(List.of("start a", "stop a", "start b"), events);
        assertEquals("b", s.task());
    }

    @Test
    void gleicheAberAndereAufgabeIstNeuerAuftrag() {
        TaskSlot<String> s = slot();
        String first = new String("a"), second = new String("a");
        TaskHandler h1 = s.sync(first, null);
        TaskHandler h2 = s.sync(second, null);
        assertNotSame(h1, h2);
        assertEquals(List.of("start a", "stop a", "start a"), events);
    }

    @Test
    void aufgabeOhneHandlerBeendetDenAlten() {
        TaskSlot<String> s = slot();
        s.sync("a", null);
        assertNull(s.sync("-alt", null), "Aufgabe aus dem alten Switch bekommt keinen Handler");
        assertFalse(s.active());
        assertEquals(List.of("start a", "stop a"), events);
    }

    @Test
    void leereWarteschlangeBeendetHandler() {
        TaskSlot<String> s = slot();
        s.sync("a", null);
        assertNull(s.sync(null, null));
        assertEquals(List.of("start a", "stop a"), events);
    }

    @Test
    void unterbrechungStartetDanachFrisch() {
        TaskSlot<String> s = slot();
        String a = "a";
        TaskHandler before = s.sync(a, null);
        s.release(null); // Kampf/Pause
        TaskHandler after = s.sync(a, null);
        assertNotSame(before, after, "nach Unterbrechung kein alter Zustand");
        assertEquals(List.of("start a", "stop a", "start a"), events);
    }

    @Test
    void fehlerImStartWirdTrotzdemGestoppt() {
        Recorder r = new Recorder("a");
        r.failStart = true;
        TaskSlot<String> s = new TaskSlot<>(t -> r);
        assertThrows(IllegalStateException.class, () -> s.sync("a", null));
        s.release(null);
        assertEquals(List.of("start a", "stop a"), events);
    }

    @Test
    void fehlerImStopGibtSlotTrotzdemFrei() {
        Recorder r = new Recorder("a");
        r.failStop = true;
        TaskSlot<String> s = new TaskSlot<>(t -> r);
        s.sync("a", null);
        assertThrows(IllegalStateException.class, () -> s.release(null));
        assertFalse(s.active());
        s.release(null);
        assertEquals(List.of("start a", "stop a"), events, "kein zweiter Stop");
    }

    @Test
    void jederAufgabentypHatEinenHandler() {
        // ein neuer TaskType ohne Handler würde sonst erst im Spiel als "Interner Fehler" auffallen
        for (TaskType type : TaskType.values()) assertTrue(TaskHandlers.handles(type), type.name());
    }
}
