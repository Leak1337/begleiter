package dev.begleiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.begleiter.llm.ContextSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;

/** Liest den Zustand eines Begleiters für das LLM; Aufruf nur auf dem Server-Thread. */
final class CompanionLlmContext {
    private CompanionLlmContext() {}

    /** Feindliche Mobs in diesem Umkreis zählen als "in der Nähe". */
    static final int THREAT_RADIUS = 16;

    private static final EquipmentSlot[] GEAR = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** @param owner Besitzer für die Entfernung oder null (dann ohne Entfernung) */
    static ContextSnapshot capture(CompanionEntity c, ServerPlayer owner) {
        ServerLevel level = (ServerLevel) c.level();

        List<String> tasks = new ArrayList<>();
        for (var task : c.peekTasks(3)) tasks.add(CompanionEntity.describe(task));

        Map<String, Integer> inventory = new HashMap<>();
        for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
            ItemStack stack = c.getInventory().getItem(i);
            if (!stack.isEmpty()) inventory.merge(path(ItemIds.of(stack)), stack.getCount(), Integer::sum);
        }

        List<String> equipment = new ArrayList<>();
        for (EquipmentSlot slot : GEAR) {
            ItemStack stack = c.getItemBySlot(slot);
            if (!stack.isEmpty()) equipment.add(path(ItemIds.of(stack)));
        }

        Map<String, Integer> threats = new HashMap<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(THREAT_RADIUS),
                m -> m instanceof Enemy && m.isAlive())) {
            threats.merge(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath(), 1, Integer::sum);
        }

        // Erinnerte Orte nur gezählt: Art (+ Erzsorte), nie Koordinaten.
        Map<String, Integer> places = new HashMap<>();
        String here = CompanionMemory.dim(level);
        for (var place : c.memory.places()) {
            if (place.kind == CompanionMemory.Kind.WAYPOINT || !place.dim.equals(here)) continue;
            String key = place.kind.name().toLowerCase(java.util.Locale.ROOT)
                    + (place.kind == CompanionMemory.Kind.ORE && !place.key.isEmpty() ? " " + place.key : "");
            places.merge(key, 1, Integer::sum);
        }
        List<String> waypoints = c.memory.waypoints().stream().map(p -> p.key).toList();

        String build = c.build == null ? null : c.build.name + (c.build.paused ? " (paused)" : "");
        var lastPlan = c.planFeedback.last();
        String hazard = c.hazards.text();
        int ownerDistance = owner == null || owner.level() != level ? -1 : (int) Math.sqrt(owner.distanceToSqr(c));

        return new ContextSnapshot(c.modeText() + (c.isPaused() ? " (paused)" : ""), (int) c.getHealth(),
                (int) c.getMaxHealth(), c.food.level(), level.dimension().identifier().getPath(), level.isDarkOutside(),
                level.isRaining(), ownerDistance, tasks, c.taskCount(), inventory, equipment,
                threats, CompanionHazards.NO_HAZARD.equals(hazard) ? null : hazard, c.getHome() != null, build, waypoints,
                places, lastPlan == null ? null : lastPlan.toString(), c.problemText);
    }

    private static String path(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 && id.startsWith("minecraft:") ? id.substring(colon + 1) : id;
    }
}
