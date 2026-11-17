package dev.begleiter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompanionRequestDialogueTest {
    private static CompoundTag question() {
        var tag = new CompoundTag();
        tag.putString("Kind", "ARMOR");
        tag.putString("Owner", "10000000-0000-0000-0000-000000000001");
        tag.putString("Dimension", "minecraft:overworld");
        tag.putString("Query", "armor");
        tag.putInt("Count", 2);
        tag.putInt("Remaining", 100);
        tag.putBoolean("Materials", true);
        return tag;
    }

    @Test
    void savesOnlyRemainingLifetimeAndPreservesOrderContext() {
        var dialogue = new CompanionRequestDialogue();
        dialogue.load(question(), 500);
        var saved = dialogue.save(540);
        assertEquals(60, saved.getIntOr("Remaining", -1));
        assertEquals(2, saved.getIntOr("Count", -1));
        assertTrue(saved.getBooleanOr("Materials", false));
        assertEquals("armor", saved.getStringOr("Query", ""));
        assertEquals(question().getStringOr("Owner", ""), saved.getStringOr("Owner", ""));
        var restored = new CompanionRequestDialogue();
        restored.load(saved, 0);
        assertTrue(restored.pending());
        assertTrue(restored.save(60).isEmpty());
    }

    @Test
    void rejectsCorruptOrOversizedContextWithoutKeepingPreviousQuestion() {
        var dialogue = new CompanionRequestDialogue();
        for (String field : new String[] {"Kind", "Owner", "Dimension"}) {
            dialogue.load(question(), 0);
            var broken = question(); broken.putString(field, "invalid value");
            dialogue.load(broken, 0);
            assertFalse(dialogue.pending(), field);
        }
        var oversized = question(); oversized.putString("Query", "x".repeat(257));
        dialogue.load(oversized, 0); assertFalse(dialogue.pending());
        for (String field : new String[] {"Count", "Remaining", "Materials"}) {
            var wrongType = question(); wrongType.putString(field, "true");
            dialogue.load(wrongType, 0); assertFalse(dialogue.pending(), field);
        }
        for (int lifetime : new int[] {-1, 0, 1201, Integer.MAX_VALUE}) {
            var broken = question(); broken.putInt("Remaining", lifetime);
            dialogue.load(broken, 0); assertFalse(dialogue.pending(), "lifetime " + lifetime);
        }
    }

    @Test
    void boundsChoiceListsAndRejectsUnknownChoiceIds() {
        var dialogue = new CompanionRequestDialogue();
        var valid = question(); valid.putString("Kind", "ITEM");
        var choices = new ListTag(); choices.add(StringTag.valueOf("minecraft:oak_boat"));
        valid.put("Choices", choices);
        dialogue.load(valid, 0); assertTrue(dialogue.pending());
        assertEquals(1, dialogue.save(0).getListOrEmpty("Choices").size());
        choices.add(StringTag.valueOf("invalid id"));
        dialogue.load(valid, 0); assertFalse(dialogue.pending());
        choices.clear();
        for (int i = 0; i < 7; i++) choices.add(StringTag.valueOf("minecraft:oak_boat"));
        dialogue.load(valid, 0); assertFalse(dialogue.pending());
        choices.clear();
        dialogue.load(valid, 0); assertFalse(dialogue.pending());
    }
}
