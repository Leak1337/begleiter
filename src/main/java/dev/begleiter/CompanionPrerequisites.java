package dev.begleiter;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Nebenwirkungsfreie Voraussetzungen, gemeinsam für Planung und laufende Versorgung. */
public final class CompanionPrerequisites {
    private CompanionPrerequisites() {}

    public static int pickTier(Item item) {
        if (item == Items.WOODEN_PICKAXE || item == Items.GOLDEN_PICKAXE) return 1;
        if (item == Items.STONE_PICKAXE || item == Items.COPPER_PICKAXE) return 2;
        if (item == Items.IRON_PICKAXE) return 3;
        if (item == Items.DIAMOND_PICKAXE) return 4;
        if (item == Items.NETHERITE_PICKAXE) return 5;
        return 0;
    }

    /** Drei Nutzungen übrig lassen: die Ausführung meidet fast kaputte Werkzeuge. Haltbarkeit-Verzauberung zählt nicht. */
    static int uses(ItemStack stack) {
        return stack.isDamageableItem() ? Math.max(0, stack.getMaxDamage() - stack.getDamageValue() - 3) : 0;
    }

    static int[] pickUses(CompanionEntity e) {
        int[] uses = new int[6];
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) addUses(uses, e.getInventory().getItem(i));
        // equipToolFor wählt aus Haupthand und Inventar, tauscht aber nicht aus Rüstung oder Nebenhand.
        addUses(uses, e.getItemBySlot(EquipmentSlot.MAINHAND));
        return uses;
    }

    private static void addUses(int[] uses, ItemStack stack) {
        int tier = pickTier(stack.getItem());
        if (tier > 0) uses[tier] += uses(stack);
    }

    public static int requiredTier(String key) {
        return switch (key) {
            case "stein", "kohle" -> 1;
            case "eisen", "kupfer", "lapis" -> 2;
            case "gold", "diamant", "redstone", "smaragd" -> 3;
            case "obsidian" -> 4;
            default -> 0;
        };
    }

    public static boolean readyTool(CompanionEntity e, String key, int units) {
        int tier = requiredTier(key);
        return tier == 0 || availablePickUses(e, tier) >= Math.max(1, units);
    }

    static int availablePickUses(CompanionEntity e, int tier) {
        int available = 0;
        int[] uses = pickUses(e);
        for (int i = tier; i < uses.length; i++) available += uses[i];
        return available;
    }

    public static Item gatheredItem(String key) {
        return switch (key) {
            case "holz" -> Items.OAK_LOG;
            case "stein" -> Items.COBBLESTONE;
            case "kohle" -> Items.COAL;
            case "eisen" -> Items.RAW_IRON;
            case "kupfer" -> Items.RAW_COPPER;
            case "gold" -> Items.RAW_GOLD;
            case "lapis" -> Items.LAPIS_LAZULI;
            case "redstone" -> Items.REDSTONE;
            case "diamant" -> Items.DIAMOND;
            case "smaragd" -> Items.EMERALD;
            case "sand" -> Items.SAND;
            case "kies" -> Items.GRAVEL;
            case "erde" -> Items.DIRT;
            case "obsidian" -> Items.OBSIDIAN;
            case "samen" -> Items.WHEAT_SEEDS;
            default -> Items.AIR;
        };
    }

    /** Planersicht: wie viele dieses Items der Begleiter schon hat (Varianten desselben Items zählen mit). */
    public static int heldCount(CompanionEntity e, Item item) {
        return new Inventory(e).count(item);
    }

    /** Ob noch amount Stück dieses Items ins Inventar passen, ohne überzulaufen. */
    public static boolean fits(CompanionEntity e, Item item, int amount) {
        return new Inventory(e).add(new ItemStack(item, amount));
    }

    /** Genaue Stapel-/Komponentenprüfung für den Inhalt, den TAKE tatsächlich übertragen würde. */
    public static boolean canTake(CompanionEntity e, Container box, Item item, int amount) {
        Inventory preview = new Inventory(e);
        return preview.addFrom(box, item, amount) >= 0; // Empty/partial sources have their own failure reason.
    }

    /** Echte Stapel bleiben reserviert, bis die verbrauchende Aufgabe angehängt ist. */
    static final class Inventory {
        private final List<ItemStack> slots;
        private final boolean exact;

        Inventory(CompanionEntity e) { this(e, false); }

        Inventory(CompanionEntity e, boolean exact) {
            this.exact = exact;
            slots = new ArrayList<>();
            for (int i = 0; i < e.getInventory().getContainerSize(); i++) slots.add(e.getInventory().getItem(i).copy());
        }

        Inventory(List<ItemStack> original, boolean exact) {
            this.exact = exact;
            slots = new ArrayList<>();
            for (ItemStack stack : original) slots.add(stack.copy());
        }

        Inventory copy() { return new Inventory(slots, exact); }

        long freeSlots() { return slots.stream().filter(ItemStack::isEmpty).count(); }

        ItemStack stack(int slot) { return slots.get(slot).copy(); }

        private Item identity(ItemStack stack) { return exact ? stack.getItem() : CompanionPlanner.canon(stack.getItem()); }

        int count(Item item) {
            int count = 0;
            for (ItemStack stack : slots)
                if (!stack.isEmpty() && identity(stack) == item) count += stack.getCount();
            return count;
        }

        /** Remaining source deficit, or -1 when any transferred stack would overflow. */
        int addFrom(Container box, Item item, int amount) {
            int left = amount;
            for (int i = 0; i < box.getContainerSize() && left > 0; i++) {
                ItemStack stack = box.getItem(i);
                if (!stack.is(item)) continue;
                int n = Math.min(left, stack.getCount());
                if (!add(stack.copyWithCount(n))) return -1;
                left -= n;
            }
            return left;
        }

        boolean add(ItemStack input) {
            ItemStack rest = input.copy();
            for (ItemStack stack : slots) {
                if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, rest)) {
                    int n = Math.min(rest.getCount(), Math.max(0, stack.getMaxStackSize() - stack.getCount()));
                    stack.grow(n);
                    rest.shrink(n);
                    if (rest.isEmpty()) return true;
                }
            }
            for (int i = 0; i < slots.size() && !rest.isEmpty(); i++) {
                if (!slots.get(i).isEmpty()) continue;
                int n = Math.min(rest.getCount(), rest.getMaxStackSize());
                slots.set(i, rest.copyWithCount(n));
                rest.shrink(n);
            }
            return rest.isEmpty();
        }

        boolean consume(Item item, int amount) {
            for (ItemStack stack : slots) {
                if (stack.isEmpty() || identity(stack) != item) continue;
                int n = Math.min(amount, stack.getCount());
                stack.shrink(n);
                amount -= n;
                if (amount == 0) return true;
            }
            return amount == 0;
        }
    }
}
