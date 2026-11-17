package dev.begleiter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class CompanionMod implements ModInitializer {
    public static final String MOD_ID = "begleiter";

    public static EntityType<CompanionEntity> COMPANION;
    public static Item COMPANION_CORE;
    public static ExtendedMenuType<CompanionMenu, CompanionSnapshot> CONTROL_MENU;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        CONTROL_MENU = Registry.register(BuiltInRegistries.MENU, id("control_center"),
                new ExtendedMenuType<>(CompanionMenu::new, CompanionSnapshot.CODEC));
        PayloadTypeRegistry.clientboundPlay().register(CompanionSnapshot.TYPE, CompanionSnapshot.CODEC);
        CompanionPlacement.register();
        CompanionZoneEditor.register();
        CompanionRemoteMenu.register();
        CompanionSchematics.installBundled();
        SchematicShowcase.init();
        PayloadTypeRegistry.clientboundPlay().registerLarge(CompanionBuildPreview.TYPE, CompanionBuildPreview.CODEC,
                CompanionSchematics.MAX_PREVIEW_PACKET_BYTES);
        ResourceKey<EntityType<?>> entityKey = ResourceKey.create(Registries.ENTITY_TYPE, id("companion"));
        COMPANION = Registry.register(BuiltInRegistries.ENTITY_TYPE, entityKey,
                EntityType.Builder.of(CompanionEntity::new, MobCategory.CREATURE)
                        .sized(0.6f, 1.8f)
                        .eyeHeight(1.62f)
                        .clientTrackingRange(10)
                        .build(entityKey));
        FabricDefaultAttributeRegistry.register(COMPANION, CompanionEntity.createAttributes());

        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id("companion_core"));
        COMPANION_CORE = Registry.register(BuiltInRegistries.ITEM, itemKey,
                new CompanionCoreItem(new Item.Properties().setId(itemKey).stacksTo(1)));

        CommandRegistrationCallback.EVENT.register(CompanionCommands::register);
        ParkourCourse.register();
        TestMap.register();
        TestScenarios.register();
        dev.begleiter.nav.NavScheduler.register();
        dev.begleiter.debug.BotDebugServer.register();
        CompanionHostility.register(); // feindliche Mobs greifen den Begleiter an wie einen Spieler
        dev.begleiter.llm.Llm.reload(); // legt beim ersten Start config/begleiter-llm.json (aus) an
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ParkourCourse.reset();
            TestMap.reset();
            TestScenarios.reset();
            CompanionRecipes.reset();
            CompanionPlanner.reset();
            CompanionItemNames.reset();
            CompanionPlacement.reset();
            CompanionZoneEditor.reset();
            CompanionRemoteMenu.reset();
            dev.begleiter.debug.BotDebugServer.reset();
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                CompanionRecipes.reset();
                CompanionPlanner.reset();
            }
        });

        // Treffer des Begleiters (Nahkampf und Pfeile) zählen für den Besitzer – vor dem Schaden, damit auch der
        // tödliche Treffer "vom Spieler" ist (Lohenruten, Erfahrung)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
            if (source.getEntity() instanceof CompanionEntity c) c.creditOwner(victim);
            if (victim instanceof CompanionEntity c) c.rememberDanger(source, amount);
            return true;
        });

        // Normale Chat-Nachrichten an den Begleiter
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                CompanionBrain.onChat(sender, message.signedContent()));
    }
}
