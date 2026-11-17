package dev.begleiter.chat;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemRequestTest {
    @Test void ingredientOrdersTakePrecedenceInBothGameLanguages() {
        for (String locale : new String[]{"de_de", "en_us"}) {
            var de = ChatParser.parse("Bringe mir Materialien für zwei Kolben", locale);
            var en = ChatParser.parse("Bring me ingredients for two pistons", locale);
            assertEquals(ChatIntent.MATERIALS_FOR, de.first());
            assertEquals(de.first(), en.first());
            assertEquals(2, de.params().count());
            assertEquals(2, en.params().count());
            assertEquals("Kolben", de.params().itemQuery());
            assertEquals("pistons", en.params().itemQuery());
        }
    }

    @Test void targetsAreGeneralAndPreserveNamespacedIdentifiers() {
        assertEquals("Schwarzeichenholztreppe", ItemRequest.parse("Crafte mir 7 Schwarzeichenholztreppe").target());
        var request = ItemRequest.parse("Please craft me 3 another.mod:custom_item.");
        assertEquals("another.mod:custom_item", request.target());
        assertEquals(3, request.count());
        assertEquals("Rote Netherziegel", ItemRequest.parse("Stelle mir Rote Netherziegel her").target());
        var id = java.util.UUID.randomUUID();
        var addressed = ChatAddress.parse("@ craft me 5 begleiter-test:stick", List.of(new ChatAddress.Candidate(id, "Leak", 0)));
        assertEquals("begleiter-test:stick", ItemRequest.parse(addressed.sourceText()).target());
        assertNull(ChatAddress.parse("craft me begleiter-test:stick", List.of(new ChatAddress.Candidate(id, "Leak", 0))));
    }

    @Test void craftingDoesNotBecomeOwnEquipmentOrOrdinaryAcquisition() {
        for (String locale : new String[]{"de_de", "en_us"}) {
            assertEquals(ChatIntent.CRAFT_FOR_OWNER, ChatParser.parse("Crafte mir eine komplette Eisenrüstung", locale).first());
            assertEquals(ChatIntent.CRAFT_FOR_OWNER, ChatParser.parse("craft me full iron armor", locale).first());
            assertNotEquals(ChatIntent.CRAFT_FOR_OWNER, ChatParser.parse("gear up", locale).first());
            assertNotEquals(ChatIntent.CRAFT_FOR_OWNER, ChatParser.parse("bring me a piston", locale).first());
        }
        assertNull(ItemRequest.parse("mach was du willst"));
        assertNull(ItemRequest.parse("make yourself stronger"));
        assertNull(ItemRequest.parse("give me resources for a boat"));
        assertNull(ItemRequest.parse("where are materials for a boat"));
    }

    @Test void amountsAreBoundedAndDoNotReadNumbersInsideTheTarget() {
        assertEquals(256, ItemRequest.parse("craft me 999999999 boats").count());
        assertEquals(0, ItemRequest.parse("craft me 0 boats").count());
        assertEquals(1, ItemRequest.parse("craft me mod:item_12").count());
        assertEquals("mod:item_12", ItemRequest.parse("craft me mod:item_12").target());
        assertEquals("", ItemRequest.parse("crafte mir").target());
    }
}
