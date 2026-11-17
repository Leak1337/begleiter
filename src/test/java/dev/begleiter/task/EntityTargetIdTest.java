package dev.begleiter.task;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityTargetIdTest {
    @Test void legacyV1TargetsCanonicalizeBeforeExecution() {
        var breed = (TaskSpec.Breed) TaskQueueCodec.v1Spec(dev.begleiter.CompanionEntity.TaskType.BREED, null, null, "cow", 2);
        var fight = (TaskSpec.Fight) TaskQueueCodec.v1Spec(dev.begleiter.CompanionEntity.TaskType.FIGHT, null, null, "blaze", 2);
        assertEquals("minecraft:cow", breed.species());
        assertEquals("minecraft:blaze", fight.mob());
        assertEquals("minecraft:blaze_rod", fight.lootItem());
    }

    @Test void entityTargetsUseCompleteIds() {
        assertEquals("minecraft:cow", new TaskSpec.Breed("cow", 2).species());
        assertEquals("other:cow", new TaskSpec.Breed("other:cow", 2).species());
        assertEquals("minecraft:cow", new TaskSpec.Fetch("cow").animal());
        assertEquals("other:cow", new TaskSpec.Fetch("other:cow").animal());
        assertEquals("minecraft:zombie", new TaskSpec.Fight(null, "zombie", null, 2).mob());
    }

    @Test void wildcardsAndVanillaLootKeepTheirMeaning() {
        assertNull(new TaskSpec.Breed(null, 2).species());
        assertNull(new TaskSpec.Fetch(null).animal());
        assertEquals("any", new TaskSpec.Fetch("any").animal());
        assertEquals("minecraft:blaze_rod", new TaskSpec.Fight(null, "blaze", null, 2).lootItem());
        assertNull(new TaskSpec.Fight(null, "other:blaze", null, 2).lootItem());
    }

    @Test void invalidEntityTargetIsNotChangedToWildcard() {
        assertEquals("bad:", new TaskSpec.Breed("bad:", 2).species());
        assertEquals("bad:", new TaskSpec.Fetch("bad:").animal());
    }
}
