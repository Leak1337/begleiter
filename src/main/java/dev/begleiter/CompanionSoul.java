package dev.begleiter;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Baustein Seele und Kern: die stabile Seelen-ID, was er über den Tod hinaus weiß (Kern-Gedächtnis), der Kern als
 * Item für den Besitzer und die Wiederbelebung zu Hause während einer Reise. Speichert nur die Seelen-ID ({@code Soul}).
 */
public final class CompanionSoul {
    private final CompanionEntity e;
    /** Stabile Seelen-ID über Tod und Wiederbelebung (gespeichert); Schlüssel im {@link CompanionMemoryStore}. */
    private UUID id;

    CompanionSoul(CompanionEntity e) { this.e = e; }

    UUID id() {
        if (id == null) id = UUID.randomUUID();
        return id;
    }

    void setId(UUID soul) { this.id = soul; }
    UUID savedId() { return id; }

    /** Kleine Kern-Referenz statt des ganzen Gedächtnisses: Seele, Name und Anzahl Orte (Tooltip-Daten). */
    static CompoundTag coreReference(UUID soul, CompoundTag full) {
        CompoundTag ref = new CompoundTag();
        ref.store("Soul", net.minecraft.core.UUIDUtil.CODEC, soul);
        if (full.contains("Name")) ref.putString("Name", full.getStringOr("Name", CompanionEntity.DEFAULT_NAME));
        ref.putInt("Places", full.getCompoundOrEmpty("Memory").getListOrEmpty("Places").size());
        return ref;
    }

    /**
     * Nach dem Fallenlassen der Sachen: wie ein Spieler, der respawnt, bleiben sie liegen, aber der Kern mit seinen
     * Erinnerungen geht an den Besitzer (oder er steht während einer Reise zu Hause wieder auf).
     */
    void afterDeathLoot(ServerLevel level, DamageSource source) {
        BlockPos at = e.blockPosition();
        e.memory.remember(CompanionMemory.Kind.DEATH, source.getMsgId(), level, at);
        // Gedächtnis weltgebunden ablegen, der Kern trägt nur die Seelen-ID. Ist der Store voll: wie früher im Kern.
        CompoundTag full = saveMemory();
        UUID soul = id();
        if (respawnForJourney(level, at, full, soul)) return;
        ItemStack core = new ItemStack(CompanionMod.COMPANION_CORE);
        boolean stored = CompanionMemoryStore.get(level.getServer()).put(soul, full);
        CustomData.set(DataComponents.CUSTOM_DATA, core, stored ? coreReference(soul, full) : full);
        e.tell(CompanionTexts.tr("entity.death", at.toShortString(), source.getLocalizedDeathMessage(e)));
        Player owner = e.getOwnerAnywhere();
        if (owner == null || !owner.getInventory().add(core)) {
            // Besitzer offline oder Inventar voll: Der Kern darf nicht nach 5 Minuten verschwinden.
            var dropped = e.spawnAtLocation(level, core);
            if (dropped != null) dropped.setUnlimitedLifetime();
        }
    }

    private boolean respawnForJourney(ServerLevel deathWorld, BlockPos death, CompoundTag memory, UUID soul) {
        var owner = e.getOwnerAnywhere();
        BlockPos homePos = e.savedHome();
        if (!e.journey.active() || owner == null || homePos == null) return false;
        var world = deathWorld.getServer().overworld();
        if (world == deathWorld && homePos.distSqr(death) <= 16 * 16) return false;
        // Zuhause meist außer Sichtweite: Chunks dort laden und kurz simulieren (Weckticket), statt den Kern abzugeben.
        // Danach hält er die Gegend mit seinem eigenen Ticket, sobald er weiterarbeitet.
        var homeChunk = new net.minecraft.world.level.ChunkPos(homePos.getX() >> 4, homePos.getZ() >> 4);
        world.getChunkSource().addTicketWithRadius(dev.begleiter.world.CompanionWakeList.WAKE_TICKET, homeChunk,
                dev.begleiter.world.CompanionChunkLoader.RADIUS);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) world.getChunkAt(homePos.offset(dx * 16, 0, dz * 16));
        if (!world.hasChunkAt(homePos)) return false;
        var revived = CompanionMod.COMPANION.create(world, EntitySpawnReason.TRIGGERED);
        if (revived == null) return false;
        revived.setOwner(owner.getUUID());
        revived.setSoulId(soul);
        revived.loadMemory(memory);
        var anchor = revived.houseInside();
        if (anchor == null) return false;
        var landing = CompanionSafeTeleport.find(revived, world, Vec3.atBottomCenterOf(anchor), 1, 1, 1);
        if (landing == null || revived.memory.dangerous(world, BlockPos.containing(landing), 8)) return false;
        revived.snapTo(landing.x, landing.y, landing.z, e.getYRot(), 0);
        var nearby = new AABB(BlockPos.containing(landing)).inflate(8);
        if (!world.getEntitiesOfClass(Mob.class, nearby, mob -> mob.isAlive()
                && mob instanceof net.minecraft.world.entity.monster.Enemy
                && mob.hasLineOfSight(revived)).isEmpty()) return false;
        revived.setPersistenceRequired();
        if (!world.addFreshEntity(revived)) return false;
        revived.tell(CompanionTexts.tr("journey.respawned"));
        return true;
    }

    /** Was er nach dem Tod noch weiß: Lager, Haus, Modus, Name. */
    CompoundTag saveMemory() {
        CompoundTag t = new CompoundTag();
        e.mode.saveMemory(t);
        e.house.saveMemory(t);
        if (e.journey.hasGoal()) {
            if (e.fortressPos != null) t.putLong("JourneyFortress", e.fortressPos.asLong());
            if (e.endRoomPos != null) t.putLong("JourneyEndRoom", e.endRoomPos.asLong());
            if (e.endPortalPos != null) t.putLong("JourneyEndPortal", e.endPortalPos.asLong());
        }
        if (e.getCustomName() != null) t.putString("Name", e.getCustomName().getString());
        t.put("Memory", e.memory.save());
        t.put("Routes", e.routes.save());
        t.put("ExperienceV1", e.experiences.save(e.tickCount));
        var journeyData = e.journey.save(e.level().getGameTime());
        if (!journeyData.isEmpty()) t.put("JourneyV1", journeyData);
        if (e.projekt.vorhanden()) t.put("ProjectV1", e.projekt.speichern());
        return t;
    }

    void loadMemory(CompoundTag t) {
        // Wiederbelebung wie bisher: Der Kern enthält Erinnerungen, keinen Hungerzustand.
        e.food.reset();
        e.mode.loadMemory(t);
        e.house.loadMemory(t);
        if (t.contains("JourneyFortress")) e.fortressPos = BlockPos.of(t.getLongOr("JourneyFortress", 0));
        if (t.contains("JourneyEndRoom")) e.endRoomPos = BlockPos.of(t.getLongOr("JourneyEndRoom", 0));
        if (t.contains("JourneyEndPortal")) e.endPortalPos = BlockPos.of(t.getLongOr("JourneyEndPortal", 0));
        if (t.contains("Name")) e.setCustomName(Component.literal(CompanionEntity.loadedName(t.getStringOr("Name", CompanionEntity.DEFAULT_NAME))));
        e.memory.load(t.getCompoundOrEmpty("Memory"));
        if (t.contains("Routes")) e.routes.load(t.getCompoundOrEmpty("Routes"));
        e.experiences.load(t.getCompoundOrEmpty("ExperienceV1"), e.tickCount);
        e.projekt.laden(t.getCompoundOrEmpty("ProjectV1"));
        if (t.contains("JourneyV1")) e.journey.load(t.getCompoundOrEmpty("JourneyV1"), e.level().getGameTime());
    }
}
