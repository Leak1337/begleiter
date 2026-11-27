package dev.begleiter;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Baustein Aufheben: Items beim Darüberlaufen einsammeln, liegende Items verfolgen und aufgeben, was er nicht
 * erreicht. Nur Laufzeit.
 */
public final class CompanionPickup {
    private final CompanionEntity e;
    /** Items, an die er nicht herankam – die versucht er nicht wieder (Laufzeit, wird ab und zu geleert). */
    private final Set<UUID> ignored = new HashSet<>();
    /**
     * Item, dem er nachläuft, und wie viele Ticks er ihm schon nachgelaufen ist. Gilt über einzelne COLLECT-Aufgaben
     * hinweg: wird eine übersprungen und die nächste jagt dasselbe Item, läuft die Frist weiter, statt endlos neu zu
     * beginnen. Gezählt werden nur Ticks, in denen er wirklich jagt: Zeit mit anderen Aufgaben dazwischen (Basisdienst,
     * Kampf, unter Last verdrängte Aufgaben) ließ ihn sonst ein erreichbares Item sofort aufgeben und lange ignorieren.
     */
    private UUID chasedItem;
    private int chasedTicks;
    private int lastChaseTick = Integer.MIN_VALUE;

    CompanionPickup(CompanionEntity e) { this.e = e; }

    /** Dieses Item nicht wieder aufheben oder verfolgen (selbst weggeworfen, unerreichbar). */
    void ignore(ItemEntity ie) { ignored.add(ie.getUUID()); }

    /** Wie ein Spieler: Items, über die er läuft, landen im Inventar (nicht kurz nachdem er dir etwas gegeben hat). */
    void tick(ServerLevel sl) {
        if (e.tickCount % 4 == 0) pickUp(sl);
        if (e.tickCount % 6000 == 0) ignored.clear();
    }

    private void pickUp(ServerLevel sl) {
        if (e.tickCount < e.collectBlockedUntil || !e.isAlive()) return;
        for (ItemEntity ie : sl.getEntitiesOfClass(ItemEntity.class, e.getBoundingBox().inflate(1.0, 0.5, 1.0),
                item -> item.isAlive() && !item.hasPickUpDelay() && !ignored.contains(item.getUUID()))) {
            if (!e.journey.recoveryItem(ie)) continue;
            if ((e.work.enabled() || e.work.active()) && !CompanionZones.collectionAllowed(e, ie.blockPosition())) continue;
            ItemStack before = ie.getItem();
            ItemStack rest = e.getInventory().addItem(before.copy());
            // Voll, aber das hier ist wichtig: Müll wegwerfen und nochmal
            if (!e.work.active() && rest.getCount() == before.getCount() && e.items.makeRoomFor(before.getItem(), CompanionEntity.isPrecious(before.getItem())))
                rest = e.getInventory().addItem(before.copy());
            int taken = before.getCount() - rest.getCount();
            if (taken <= 0) continue;
            if (e.work.is(CompanionWork.Kind.ANIMALS) || e.work.is(CompanionWork.Kind.STORAGE)) e.work.progress(1);
            e.take(ie, taken);
            if (rest.isEmpty()) ie.discard(); else ie.setItem(rest);
        }
    }

    /** Diesem Item nachlaufen; true, wenn es ein neues Ziel ist (Frist beginnt). */
    public boolean chase(ItemEntity ie) {
        boolean fresh = !ie.getUUID().equals(chasedItem);
        if (fresh) {
            chasedItem = ie.getUUID();
            chasedTicks = 0;
        } else if (e.tickCount != lastChaseTick) chasedTicks++;
        lastChaseTick = e.tickCount;
        return fresh;
    }

    /** Wie viele Ticks er dem aktuellen Item schon nachgelaufen ist. */
    public int chaseTicks() { return chasedTicks; }

    /** Dieser Tick zählt nicht als vergeblicher Versuch (z. B. Wegsuche wartet auf Rechenzeit). */
    public void holdChase() { if (chasedTicks > 0) chasedTicks--; }

    /** Kommt nicht heran: liegen lassen und nicht wieder versuchen. */
    public void giveUpChase(ItemEntity ie) {
        ignored.add(ie.getUUID());
        chasedItem = null;
    }

    /** Lohnt es sich hinzulaufen? Nicht auf Blättern (Baumkronen), nicht hoch über ihm, nicht schon gescheitert. */
    public boolean collectible(ItemEntity ie) {
        if (!e.journey.recoveryItem(ie)) return false;
        if (!ie.isAlive() || ignored.contains(ie.getUUID()) || !CompanionZones.collectionAllowed(e, ie.blockPosition())) return false;
        if (ie.getY() - e.getY() > 3) return false;
        BlockPos p = ie.blockPosition();
        return !e.level().getBlockState(p).is(BlockTags.LEAVES) && !e.level().getBlockState(p.below()).is(BlockTags.LEAVES);
    }

    /** Liegen in der Nähe frische, erreichbare Items herum (nicht von ihm selbst gerade abgegeben)? */
    public boolean looseItemsNearby() {
        if (!(e.level() instanceof ServerLevel sl) || e.tickCount < e.collectBlockedUntil || !e.hasFreeSlot()) return false;
        return !sl.getEntitiesOfClass(ItemEntity.class, e.getBoundingBox().inflate(12),
                item -> item.getAge() < 6000 && collectible(item)).isEmpty();
    }
}
