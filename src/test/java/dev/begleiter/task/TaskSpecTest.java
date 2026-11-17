package dev.begleiter.task;

import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import dev.begleiter.CompanionEntity.TaskType;

import static org.junit.jupiter.api.Assertions.*;

class TaskSpecTest {
    /** Je Record ein Beispiel mit gesetzten Feldern (Blöcke bleiben null: ohne Registry). */
    private static List<TaskSpec> samples() throws ReflectiveOperationException {
        List<TaskSpec> out = new ArrayList<>();
        Deque<Class<?>> open = new ArrayDeque<>(List.of(TaskSpec.class));
        while (!open.isEmpty()) {
            Class<?> c = open.pop();
            if (c.isInterface()) { open.addAll(List.of(c.getPermittedSubclasses())); continue; }
            RecordComponent[] fields = c.getRecordComponents();
            Class<?>[] types = new Class<?>[fields.length];
            Object[] values = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                types[i] = fields[i].getType();
                values[i] = types[i] == int.class ? 7 : types[i] == boolean.class ? true
                        : types[i] == String.class ? "x" + i : types[i] == BlockPos.class ? new BlockPos(1, -2, 3) : null;
            }
            out.add((TaskSpec) c.getDeclaredConstructor(types).newInstance(values));
        }
        return out;
    }

    @Test
    void jederTypHatGenauEinenRecord() throws ReflectiveOperationException {
        Set<TaskType> seen = EnumSet.noneOf(TaskType.class);
        for (TaskSpec s : samples()) assertTrue(seen.add(s.type()), "zwei Records für " + s.type());
        assertEquals(EnumSet.allOf(TaskType.class), seen);
    }

    @Test
    void v2SpeichertBenannteFelderVerlustfrei() throws Exception {
        for (TaskSpec s : samples()) assertEquals(s, TaskQueueCodec.decode(s.type(), TaskQueueCodec.encode(s)), s.type().name());
        CompoundTag craft = TaskQueueCodec.encode(new TaskSpec.Craft("bucket", 2));
        assertEquals("minecraft:bucket", craft.getStringOr("item", ""));
        assertEquals(2, craft.getIntOr("count", 0));
        assertTrue(TaskQueueCodec.encode(new TaskSpec.Collect(false)).isEmpty(), "Standardwerte entfallen");
    }

    @Test
    void v2VerwirftFalschenWerttyp() {
        CompoundTag e = new CompoundTag();
        e.putString("count", "zehn");
        assertThrows(TaskQueueCodec.Bad.class, () -> TaskQueueCodec.decode(TaskType.GATHER, e));
    }

    @Test
    void recoverySourceSurvivesCodecAndOlderRequestsDefaultToNoExclusion() throws Exception {
        var recovery = new TaskSpec.Request("iron_ingot", 8, false, 4, 0, new BlockPos(-5010, 70, 4000));
        assertEquals(recovery, TaskQueueCodec.decode(TaskType.REQUEST, TaskQueueCodec.encode(recovery)));
        var legacy = TaskQueueCodec.encode(new TaskSpec.Request("iron_ingot", 8, false, 4, 0));
        legacy.remove("excludedSource");
        assertEquals(new TaskSpec.Request("iron_ingot", 8, false, 4, 0), TaskQueueCodec.decode(TaskType.REQUEST, legacy));
        legacy.putString("excludedSource", "broken");
        assertThrows(TaskQueueCodec.Bad.class, () -> TaskQueueCodec.decode(TaskType.REQUEST, legacy));
    }

    @Test
    void v1UebersetztJedenTyp() {
        for (TaskType type : TaskType.values())
            assertEquals(type, TaskQueueCodec.v1Spec(type, null, null, "x", 1).type(), type.name());
        assertEquals(new TaskSpec.Extend(3), TaskQueueCodec.v1Spec(TaskType.EXTEND, null, null, null, 3));
        assertEquals(new TaskSpec.Station("furnace", true), TaskQueueCodec.v1Spec(TaskType.STATION, null, null, "furnace", 1));
    }

    @Test
    void ausruestungsZielLiestAlteForm() {
        var gear = (TaskSpec.GearUp) TaskQueueCodec.v1Spec(TaskType.GEARUP, null, null, "4:iron_helmet:2", 5);
        assertEquals(new TaskSpec.GearUp(4, "iron_helmet", 2, 5), gear);
        assertEquals(new TaskSpec.GearUp(3, null, 0, 0), TaskQueueCodec.v1Spec(TaskType.GEARUP, null, null, null, 0));
        assertEquals(new TaskSpec.GearUp(3, null, 0, 0), TaskQueueCodec.v1Spec(TaskType.GEARUP, null, null, "kaputt::x", 0));
    }

    @Test
    void kampfzielTrenntArtUndBeute() {
        assertEquals(new TaskSpec.Fight(null, "zombie", "rotten_flesh", 4),
                TaskQueueCodec.v1Spec(TaskType.FIGHT, null, null, "zombie|rotten_flesh", 4));
        var blaze = (TaskSpec.Fight) TaskQueueCodec.v1Spec(TaskType.FIGHT, null, null, "blaze", 6);
        assertNull(blaze.loot());
        assertEquals("minecraft:blaze_rod", blaze.lootItem());
    }
}
