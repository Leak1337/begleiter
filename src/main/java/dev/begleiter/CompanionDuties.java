package dev.begleiter;

import dev.begleiter.CompanionEntity.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Daueraufgaben aus dem Menü "Aufgaben": Der Begleiter bleibt an einem festen Ort (Feld, Weide, Dorf) und
 * erledigt dort regelmäßig von selbst die Arbeit – ernten und säen, Tiere füttern, Überschuss verkaufen.
 * Geprüft wird nur, wenn er sonst nichts zu tun hat; ohne Arbeit wartet er still am Ort.
 */
public final class CompanionDuties {
    public enum Duty {
        FARM(4), ANIMALS(5), TRADE(6), EXPLORE(7), BASE(8), STORAGE(9), FETCH(10);

        /** Moduswert im Menü-Schnappschuss (0–3 sind Folgen, Warten, Selbstständig, Lager). */
        public final int mode;
        Duty(int mode) { this.mode = mode; }

        public static Duty byName(String name) {
            for (Duty d : values()) if (d.name().equals(name)) return d;
            return null;
        }
    }

    /** Umkreis um den Ort, in dem er nach Tieren und Dorfbewohnern schaut. */
    static final int ANIMAL_RADIUS = 24, VILLAGER_RADIUS = 32;
    private static final String[] SPECIES = {"cow", "sheep", "pig", "chicken"};

    private CompanionDuties() {}

    // ------------------------------------------------------------------ Starten (aus dem Menü)

    public static Component startFarm(ServerPlayer p, CompanionEntity c) {
        ServerLevel sl = (ServerLevel) c.level();
        BlockPos center = p != null && CompanionJobs.hasFarmland(sl, p.blockPosition(), 12) ? p.blockPosition()
                : CompanionJobs.farmCenter(c);
        var zone = c.zones.get(CompanionZones.Kind.FIELDS, sl);
        if (zone != null) center = zone.center();
        if (zone == null && !CompanionJobs.hasFarmland(sl, center, CompanionJobs.FARM_RADIUS)) return CompanionTexts.tr("order.farm.none");
        Component held = c.holdOrdersComponent();
        c.startDuty(Duty.FARM, center);
        return CompanionTexts.tr("duty.farm.start", center.toShortString()).append(held);
    }

    public static Component startAnimals(ServerPlayer p, CompanionEntity c) {
        ServerLevel sl = (ServerLevel) c.level();
        BlockPos center = p != null ? p.blockPosition() : c.blockPosition();
        if (CompanionJobs.animals(sl, center, ANIMAL_RADIUS, null).stream().noneMatch(CompanionDuties::farmAnimal)
                && c.getHome() != null) center = c.getHome();
        var zone = c.zones.get(CompanionZones.Kind.ANIMALS, sl);
        if (zone != null) center = zone.center();
        if (zone == null && CompanionJobs.animals(sl, center, ANIMAL_RADIUS, null).stream().noneMatch(CompanionDuties::farmAnimal))
            return CompanionTexts.tr("duty.animals.none");
        Component held = c.holdOrdersComponent();
        c.herdKeeping = true;
        c.startDuty(Duty.ANIMALS, center);
        return CompanionTexts.tr("duty.animals.start", center.toShortString()).append(held);
    }

    public static Component startTrade(ServerPlayer p, CompanionEntity c) {
        ServerLevel sl = (ServerLevel) c.level();
        BlockPos center = null;
        if (!CompanionJobs.villagers(sl, c, VILLAGER_RADIUS).isEmpty()) center = c.blockPosition();
        else {
            var village = c.memory.nearest(CompanionMemory.Kind.VILLAGE, null, sl, c.blockPosition(), 2000);
            if (village != null) center = village.pos;
        }
        if (center == null) return CompanionTexts.tr("duty.trade.none");
        Component held = c.holdOrdersComponent();
        c.startDuty(Duty.TRADE, center);
        return CompanionTexts.tr("duty.trade.start", center.toShortString()).append(held);
    }

    private static boolean farmAnimal(net.minecraft.world.entity.animal.Animal a) {
        String type = CompanionJobs.type(a);
        for (String s : SPECIES) if (type.equals("minecraft:" + s)) return true;
        return false;
    }

    // ------------------------------------------------------------------ Arbeiten (im Leerlauf)

    /**
     * Ein Prüfgang, wenn er frei ist. Liefert die Ticks bis zum nächsten Prüfgang.
     * Arbeit wird als normale Aufgaben eingereiht; die Handler melden selbst, was sie geschafft haben.
     */
    static int tick(CompanionEntity c, ServerLevel sl) {
        Duty duty = c.duty();
        BlockPos at = c.dutyPos();
        if (duty == null || at == null) return 200;
        // Erst am Ort ankommen (das Folgeziel bringt ihn hin); unterwegs nichts anfangen.
        var zone = CompanionZones.work(c, CompanionZones.kindFor(duty));
        if (zone == null && at.distSqr(c.blockPosition()) > 20 * 20) return 40;
        return switch (duty) {
            case FARM -> farm(c, sl, at);
            case ANIMALS -> animals(c, sl, at);
            case TRADE -> trade(c, sl, at);
            case EXPLORE -> wander(c, sl, c.zones.get(CompanionZones.Kind.ROAM, sl));
            case BASE -> base(c, sl, at);
            case STORAGE -> storage(c, sl, at);
            case FETCH -> fetch(c, sl, at);
        };
    }

    private static int farm(CompanionEntity c, ServerLevel sl, BlockPos at) {
        if (CompanionJobs.nextFarmTarget(sl, c, at, java.util.Set.of()) == null) return 600;
        c.setStage(CompanionTexts.tr("duty.farm.stage", CompanionJobs.countMature(sl, at, CompanionJobs.FARM_RADIUS)));
        c.addTask(Task.farm(at));
        c.addTask(Task.collect(false));
        if ((c.getHome() != null || CompanionZones.work(c, CompanionZones.Kind.STORAGE) != null) && c.usedSlots() >= 24) c.addTask(Task.deliver(true));
        return 600;
    }

    private static int animals(CompanionEntity c, ServerLevel sl, BlockPos at) {
        if (collect(c, sl, at, CompanionZones.Kind.ANIMALS)) return 200;
        boolean hungry = false;
        for (String species : SPECIES) {
            var animalZone = CompanionZones.work(c, CompanionZones.Kind.ANIMALS);
            int adults = (int) CompanionJobs.animals(sl, at, 48, species).stream()
                    .filter(a -> !a.isBaby() && (animalZone == null || animalZone.contains(a.blockPosition()))).count();
            if (adults < 2 || adults >= CompanionJobs.HERD_MAX) continue;
            if (CompanionJobs.nextToFeed(sl, c, species) != null) {
                c.setStage(CompanionTexts.tr("duty.animals.stage", CompanionTexts.animal(species)));
                c.addTask(Task.breed(species, 0));
                c.dutyHintGiven = false;
                return 600;
            }
            if (!anyFood(c, sl, at, species)) hungry = true;
        }
        if (hungry && !c.dutyHintGiven) {
            c.dutyHintGiven = true;
            c.tell(CompanionTexts.tr("duty.animals.no_food"));
        }
        return 1200;
    }

    private static boolean anyFood(CompanionEntity c, ServerLevel sl, BlockPos at, String species) {
        for (var a : CompanionJobs.animals(sl, at, 48, species))
            if (CompanionZones.allows(c, CompanionZones.Kind.ANIMALS, a.blockPosition()))
            if (CompanionJobs.feedSlot(c, a) >= 0) return true;
        return false;
    }

    private static int trade(CompanionEntity c, ServerLevel sl, BlockPos at) {
        for (var v : CompanionJobs.villagers(sl, c, VILLAGER_RADIUS)) {
            if (CompanionJobs.findOffer(c, v, null) != null) {
                c.setStage(CompanionTexts.tr("duty.trade.stage"));
                c.addTask(Task.trade(null, 0));
                c.dutyHintGiven = false;
                return 1200;
            }
        }
        if (!c.dutyHintGiven) {
            c.dutyHintGiven = true;
            c.tell(CompanionTexts.tr("duty.trade.nothing"));
        }
        return 1200;
    }

    public static Component startZoned(CompanionEntity c, Duty duty) {
        var sl = (ServerLevel) c.level();
        var kind = CompanionZones.kindFor(duty);
        var zone = c.zones.get(kind, sl);
        if (zone == null) return CompanionTexts.tr("duty.zone_required", kind.title());
        var held = c.holdOrdersComponent();
        c.startDuty(duty, zone.center());
        return CompanionTexts.tr("duty.zoned.start", CompanionTexts.mode(c)).append(held);
    }

    private static int wander(CompanionEntity c, ServerLevel sl, CompanionZones.Zone zone) {
        if (zone == null) return 200;
        BlockPos target = wanderTarget(c, sl, zone);
        if (target != null) {
            c.setStage(CompanionTexts.tr("duty.explore.stage"));
            c.addTask(Task.moveTo(target, 1));
        }
        return 160;
    }

    public static BlockPos wanderTarget(CompanionEntity c, ServerLevel sl, CompanionZones.Zone zone) {
        for (int i = 0; i < 24; i++) {
            int x = zone.min().getX() + c.getRandom().nextInt(zone.max().getX() - zone.min().getX() + 1);
            int z = zone.min().getZ() + c.getRandom().nextInt(zone.max().getZ() - zone.min().getZ() + 1);
            for (int y = zone.max().getY() - 1; y > zone.min().getY(); y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (pos.distSqr(c.blockPosition()) < 4 || !sl.hasChunkAt(pos)) continue;
                if (CompanionSafeTeleport.safe(c, sl, net.minecraft.world.phys.Vec3.atBottomCenterOf(pos))) return pos;
            }
        }
        return null;
    }

    /** Tiere zur sicheren Stelle möglichst weit im Inneren führen, statt am Zonenrand stehen zu bleiben. */
    public static BlockPos deliveryTarget(CompanionEntity c, ServerLevel sl, CompanionZones.Zone zone) {
        // Spalten von der Mitte nach außen: die erste Spalte mit sicherer Stelle gewinnt. Vorher wurde jede Position
        // der Zone (bis 64×64×32) mit der teuren Sicherheitsprüfung angesehen, bevor das Ergebnis feststand.
        BlockPos center = zone.center();
        java.util.List<long[]> columns = new java.util.ArrayList<>();
        for (int x = zone.min().getX(); x <= zone.max().getX(); x++)
            for (int z = zone.min().getZ(); z <= zone.max().getZ(); z++) {
                long dx = x - center.getX(), dz = z - center.getZ();
                columns.add(new long[] {dx * dx + dz * dz, x, z});
            }
        columns.sort(java.util.Comparator.comparingLong(col -> col[0]));
        for (long[] col : columns) {
            BlockPos best = null;
            int bestDy = Integer.MAX_VALUE;
            for (int y = zone.min().getY(); y <= zone.max().getY(); y++) {
                BlockPos p = new BlockPos((int) col[1], y, (int) col[2]);
                int dy = Math.abs(y - center.getY());
                if (dy >= bestDy || !sl.hasChunkAt(p)) continue;
                if (CompanionSafeTeleport.safe(c, sl, net.minecraft.world.phys.Vec3.atBottomCenterOf(p))) { best = p; bestDy = dy; }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static boolean collect(CompanionEntity c, ServerLevel sl, BlockPos at, CompanionZones.Kind kind) {
        if (!c.hasFreeSlot() || c.tickCount < c.collectBlockedUntil) return false;
        var zone = CompanionZones.work(c, kind);
        var bounds = zone == null ? new net.minecraft.world.phys.AABB(at).inflate(12) : zone.bounds();
        if (sl.getEntitiesOfClass(ItemEntity.class, bounds, item -> c.collectible(item)
                && CompanionZones.allows(c, kind, item.blockPosition())).isEmpty()) return false;
        c.addTask(Task.collect(true));
        return true;
    }

    private static int base(CompanionEntity c, ServerLevel sl, BlockPos at) {
        if (collect(c, sl, at, CompanionZones.Kind.BASE)) return 100;
        BlockPos field = c.zones.get(CompanionZones.Kind.FIELDS, sl) == null ? at : c.zones.get(CompanionZones.Kind.FIELDS, sl).center();
        if (CompanionJobs.nextFarmTarget(sl, c, field, java.util.Set.of()) != null) return farm(c, sl, field);
        BlockPos pen = c.zones.get(CompanionZones.Kind.ANIMALS, sl) == null ? at : c.zones.get(CompanionZones.Kind.ANIMALS, sl).center();
        animals(c, sl, pen);
        if (c.hasTasks()) return 600;
        storage(c, sl, at);
        if (c.hasTasks()) return 600;
        return wander(c, sl, c.zones.get(CompanionZones.Kind.BASE, sl));
    }

    public static long storageFingerprint(CompanionEntity c, ServerLevel sl, BlockPos at) {
        long result = 1;
        for (BlockPos pos : c.storageBoxes(sl, at)) {
            result = result * 31 + pos.asLong();
            var box = c.containerAt(sl, pos);
            for (int i = 0; i < box.getContainerSize(); i++) {
                var s = box.getItem(i);
                result = result * 31 + s.getItem().hashCode();
                result = result * 31 + s.getCount();
                result = result * 31 + s.getComponents().hashCode();
            }
        }
        return result;
    }

    private static int storage(CompanionEntity c, ServerLevel sl, BlockPos at) {
        var boxes = c.storageBoxes(sl, at);
        if (boxes.isEmpty()) return 600;
        if (c.hasDepositable() && c.tickCount >= c.storageFullUntil) { c.addTask(Task.deliver(true)); return 600; }
        long fingerprint = storageFingerprint(c, sl, at);
        if (fingerprint != c.dutyStorageFingerprint) {
            c.dutyStorageFingerprint = fingerprint;
            c.addTask(Task.sort(boxes.getFirst()));
            return 1200;
        }
        collect(c, sl, at, CompanionZones.Kind.STORAGE);
        return 1200;
    }

    private static int fetch(CompanionEntity c, ServerLevel sl, BlockPos at) {
        var pen = c.zones.get(CompanionZones.Kind.ANIMALS, sl);
        if (pen == null) return 600;
        for (String species : SPECIES) {
            long count = CompanionJobs.animals(sl, at, 48, species).stream().filter(a -> !a.isBaby() && pen.contains(a.blockPosition())).count();
            if (count >= 2) continue;
            var owner = c.getOwner();
            if (owner == null) return 600;
            boolean found = CompanionJobs.animals(sl, at, 48, species).stream().anyMatch(a -> !a.isBaby()
                    && !pen.contains(a.blockPosition()) && !a.hasCustomName() && CompanionJobs.mayFetch(a, owner)
                    && (c.zones.get(CompanionZones.Kind.ROAM, sl) == null || c.zones.get(CompanionZones.Kind.ROAM, sl).contains(a.blockPosition())));
            if (found) { c.addTask(Task.fetch(species)); return 600; }
        }
        if (!c.dutyHintGiven) { c.dutyHintGiven = true; c.tell(CompanionTexts.tr("duty.fetch.wait")); }
        return 1200;
    }
}
