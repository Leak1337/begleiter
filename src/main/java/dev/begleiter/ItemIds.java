package dev.begleiter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Exakte Item-Identität; alte Pfad-Eingaben sind ausschließlich Minecraft-Aliase. */
public final class ItemIds {
    private ItemIds() { }

    public static Identifier parse(String raw) {
        return RegistryIds.parse(raw);
    }

    public static String canonical(String raw) {
        return RegistryIds.canonical(raw);
    }

    /** Ungültige optionale Angaben nicht zu null machen: null kann eine andere Aktion bedeuten. */
    public static String canonicalOrOriginal(String raw) {
        return RegistryIds.canonicalOrOriginal(raw);
    }

    public static String of(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static Item resolve(String raw) {
        Identifier id = parse(raw);
        return id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
    }

    public static boolean matches(ItemStack stack, String raw) {
        Identifier id = parse(raw);
        return !stack.isEmpty() && id != null && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id);
    }
}
