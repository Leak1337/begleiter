package dev.begleiter.chat;

import java.util.Set;

/** Sprachneutrale Zusammensetzung von Werkzeug- und Rüstungs-IDs ("iron" + "pickaxe" → "iron_pickaxe"). */
final class ChatItems {
    private ChatItems() {}

    private static final Set<String> ARMOR = Set.of("helmet", "chestplate", "leggings", "boots");
    private static final Set<String> ARMOR_MATERIALS = Set.of("leather", "chainmail", "iron", "golden", "diamond", "netherite");
    private static final Set<String> TOOL_MATERIALS = Set.of("wooden", "stone", "iron", "golden", "diamond", "netherite");

    /** Ohne Material: Rüstung aus Eisen, Werkzeug aus Stein; Leder bei Werkzeug wird Holz. Ungültige Paare → null. */
    static String toolOrArmor(String material, String piece) {
        boolean armor = ARMOR.contains(piece);
        String mat = material == null ? (armor ? "iron" : "stone") : material;
        if (mat.equals("leather") && !armor) mat = "wooden";
        return (armor ? ARMOR_MATERIALS : TOOL_MATERIALS).contains(mat) ? mat + "_" + piece : null;
    }
}
