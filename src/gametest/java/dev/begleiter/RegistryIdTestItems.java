package dev.begleiter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Nur im Test-Mod: echte Items mit gleichen Pfaden wie Vanilla, aber anderem Namensraum. */
public final class RegistryIdTestItems implements ModInitializer {
    public static final Item COAL = register("coal");
    public static final Item RAW_IRON = register("raw_iron");
    public static final Item IRON_PICKAXE = register("iron_pickaxe");
    public static final Item STICK = register("stick");
    public static final Item OAK_LOG = register("oak_log");
    public static final Item DIRT = register("dirt");
    public static final Item ROTTEN_FLESH = register("rotten_flesh");
    public static final Item COBBLESTONE = register("cobblestone");
    public static final Item DIAMOND = register("diamond");
    public static final CropBlock WHEAT = registerCrop();
    public static final Block GRAVEL = registerBlock("gravel");
    public static final Block COPPER_ORE = registerBlock("copper_ore");
    public static final EntityType<Cow> COW = registerCow("cow");
    public static final EntityType<Cow> IRON_GOLEM = registerCow("iron_golem");

    private static Item register(String path) {
        var key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("begleiter-test", path));
        return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key)));
    }

    private static CropBlock registerCrop() {
        var key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("begleiter-test", "wheat"));
        return Registry.register(BuiltInRegistries.BLOCK, key, new TestCrop(BlockBehaviour.Properties.of().setId(key)));
    }

    private static Block registerBlock(String path) {
        var key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("begleiter-test", path));
        return Registry.register(BuiltInRegistries.BLOCK, key, new Block(BlockBehaviour.Properties.of().setId(key)));
    }

    private static final class TestCrop extends CropBlock {
        TestCrop(BlockBehaviour.Properties properties) { super(properties); }
    }

    private static EntityType<Cow> registerCow(String path) {
        var key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("begleiter-test", path));
        EntityType<Cow> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                EntityType.Builder.of(Cow::new, MobCategory.CREATURE).sized(.9f, 1.4f).build(key));
        FabricDefaultAttributeRegistry.register(type, Cow.createAttributes());
        return type;
    }

    @Override
    public void onInitialize() { }
}
