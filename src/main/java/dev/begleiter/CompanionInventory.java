package dev.begleiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Baustein Inventar: was er behält, wegwirft, einlagert und dem Besitzer gibt. Der Container selbst bleibt an der
 * Entity ({@link CompanionEntity#getInventory()}) und wird dort gespeichert; dieser Baustein hat keinen eigenen Zustand.
 */
public final class CompanionInventory {
    /** So viele Müll-Blöcke behält er immer zum Hochbauen/Zumauern (außer für Diamanten & Co.). */
    private static final int KEEP_SCAFFOLD = 32;

    private final CompanionEntity e;

    CompanionInventory(CompanionEntity e) { this.e = e; }

    private SimpleContainer inv() { return e.getInventory(); }

    /** Welchen Stapel er für {@code wanted} wegwerfen würde (-1 = keinen). */
    private int trashSlotFor(Item wanted, boolean precious) {
        if (CompanionEntity.isTrash(wanted)) return -1; // Müll gegen Müll tauschen lohnt nicht
        int best = -1, bestScore = Integer.MAX_VALUE, junk = e.scaffolding.count();
        Item mainJunk = e.scaffolding.item();
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack s = inv().getItem(i);
            if (s.isEmpty() || !CompanionEntity.isTrash(s.getItem()) || (CompanionEntity.isJunk(s.getItem()) && keep(s))) continue; // Gammelfleisch o.ä. darf weg
            // echter Abfall zuerst, dann Müll-Blöcke (kleinste Stapel, Hauptsorte zuletzt); Gerüst-Vorrat nur für Wertvolles
            int score = s.getCount() + (CompanionEntity.isJunk(s.getItem()) ? 100 : 0) + (s.is(mainJunk) ? 100 : 0);
            if (CompanionEntity.isJunk(s.getItem()) && junk - s.getCount() < KEEP_SCAFFOLD && !precious) continue;
            if (score < bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    /**
     * Wie ein Spieler: Inventar (fast) voll -> Abfall und Müll-Blöcke über einen Vorrat von 64 hinaus wegwerfen,
     * statt deswegen zurück zum Lager zu laufen.
     */
    void declutter(ServerLevel sl) {
        if (e.usedSlots() < inv().getContainerSize() - 1 || e.build != null) return; // Bauauftrag braucht evtl. alles
        for (int i : clutterSlots()) {
            ItemStack out = inv().removeItemNoUpdate(i);
            ItemEntity dropped = throwFromHand(sl, out.copy());
            if (dropped == null) dropped = e.spawnAtLocation(sl, out);
            if (dropped != null) e.pickup.ignore(dropped);
            e.logInfo(CompanionTexts.tr("log.inventory.discard", out.getCount(), out.getHoverName()));
        }
    }

    /** Stapel, die declutter wegwerfen würde: Abfall und Müll-Blöcke über einen Vorrat von 64 (Hauptsorte 128) hinaus. */
    private List<Integer> clutterSlots() {
        List<Integer> out = new ArrayList<>();
        int junk = e.scaffolding.count();
        Item mainJunk = e.scaffolding.item();
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack s = inv().getItem(i);
            if (s.isEmpty() || !CompanionEntity.isTrash(s.getItem()) || (CompanionEntity.isJunk(s.getItem()) && keep(s))) continue;
            if (CompanionEntity.isJunk(s.getItem()) && (junk - s.getCount() < 64 || (s.is(mainJunk) && junk - s.getCount() < 128))) continue;
            if (CompanionEntity.isJunk(s.getItem())) junk -= s.getCount();
            out.add(i);
        }
        return out;
    }

    /** Belegte Plätze ohne das, was er ohnehin wegwerfen würde – dafür lohnt kein Weg zum Lager. */
    public int usedSlotsWithoutClutter() {
        return e.usedSlots() - clutterSlots().size();
    }

    public boolean canMakeRoomFor(Item wanted, boolean precious) {
        return e.hasFreeSlot() || trashSlotFor(wanted, precious) >= 0;
    }

    /** Inventar voll, aber {@code wanted} ist wichtig: einen Stapel Abfall/Müll wegwerfen (und nicht wieder aufheben). */
    public boolean makeRoomFor(Item wanted, boolean precious) {
        if (e.hasFreeSlot()) return true;
        if (!(e.level() instanceof ServerLevel sl)) return false;
        int slot = trashSlotFor(wanted, precious);
        if (slot < 0) return false;
        ItemStack s = inv().removeItemNoUpdate(slot);
        ItemEntity dropped = throwFromHand(sl, s.copy());
        if (dropped == null) dropped = e.spawnAtLocation(sl, s);
        if (dropped != null) e.pickup.ignore(dropped);
        e.logInfo(CompanionTexts.tr("log.inventory.replace", s.getCount(), s.getHoverName(), new ItemStack(wanted).getHoverName()));
        return true;
    }

    /** Ausrüstung, Essen, Auftragsmaterial … behält er immer (nie wegwerfen, einlagern oder abgeben). */
    boolean keep(ItemStack s) {
        return e.reservedForRecipe(s) || s.has(DataComponents.EQUIPPABLE) || s.has(DataComponents.TOOL) || s.has(DataComponents.FOOD)
                || s.is(CompanionEntity.KEEP) || e.hasPlaceTaskFor(s.getItem()) || (!e.hasPortal() && s.is(Items.OBSIDIAN));
    }

    /**
     * Wie viele Stapel das Einlagern (mit Vorrat: 16 Holz, 32 Stein, 8 Kohle, 16 Eisen) wirklich leeren würde.
     * Ausrüstung, Essen, Fackeln ... und der Vorrat bleiben bei ihm.
     */
    public int depositableSlots() {
        Map<String, Integer> quota = new HashMap<>(Map.of("wood", 16, "stone", 32, "coal", 8, "iron", 16));
        int n = 0;
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack s = inv().getItem(i);
            if (s.isEmpty() || keep(s)) continue;
            String key = quotaKey(s);
            int keep = 0;
            if (key != null) {
                int q = quota.get(key);
                keep = Math.min(q, s.getCount());
                quota.put(key, q - keep);
            }
            if (keep == 0) n++; // ganz leer (teilweise geleerte Stapel machen keinen Platz frei)
        }
        return n;
    }

    public boolean hasDepositable() {
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack s = inv().getItem(i);
            if (!s.isEmpty() && !keep(s)) return true;
        }
        return false;
    }

    private static String quotaKey(ItemStack s) {
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) return "wood";
        if (s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE)) return "stone";
        if (s.is(Items.COAL) || s.is(Items.CHARCOAL)) return "coal";
        if (s.is(Items.RAW_IRON) || s.is(Items.IRON_INGOT)) return "iron";
        return null;
    }

    /**
     * Lagert ein; Rückgabe = wie viele Items nicht mehr hineinpassten (0 = alles drin). keepQuota: behält einen kleinen
     * Vorrat (Holz, Stein, Kohle, Eisen) für sich – wie ein Spieler.
     */
    public int depositInto(Container target, boolean keepQuota) {
        int left = 0;
        Map<String, Integer> quota = new HashMap<>(Map.of("wood", 16, "stone", 32, "coal", 8, "iron", 16));
        for (int i = 0; i < inv().getContainerSize(); i++) {
            ItemStack s = inv().getItem(i);
            if (s.isEmpty() || keep(s)) continue;
            int keep = 0;
            String key = keepQuota ? quotaKey(s) : null;
            if (key != null) {
                int q = quota.get(key);
                keep = Math.min(q, s.getCount());
                quota.put(key, q - keep);
            }
            int move = s.getCount() - keep;
            if (move <= 0) continue;
            ItemStack part = s.copy();
            part.setCount(move);
            ItemStack rest = HopperBlockEntity.addItem(null, target, part, null);
            s.shrink(move - rest.getCount());
            left += rest.getCount();
        }
        return left;
    }

    /**
     * Einen Stapel wie ein Spieler aus der Hand werfen: Arm schwingen, Item fliegt aus Augenhöhe in Blickrichtung.
     * Liefert das Item (null = Welt hat es abgelehnt, der Aufrufer behält den Stapel).
     */
    public ItemEntity throwFromHand(ServerLevel sl, ItemStack stack) {
        if (stack.isEmpty()) return null;
        e.swingArm();
        var look = net.minecraft.world.phys.Vec3.directionFromRotation(e.getXRot(), e.getYHeadRot());
        ItemEntity ie = new ItemEntity(sl, e.getX(), e.getEyeY() - 0.3, e.getZ(), stack);
        var random = e.getRandom();
        ie.setDeltaMovement(look.x * 0.3 + (random.nextFloat() - 0.5) * 0.04, look.y * 0.3 + 0.1,
                look.z * 0.3 + (random.nextFloat() - 0.5) * 0.04);
        ie.setPickUpDelay(40);
        if (!sl.addFreshEntity(ie)) return null;
        return ie;
    }

    /**
     * key: Resources-Schlüssel, "item:<id>" (genau dieses Item, höchstens max Stück) oder null (alles Überflüssige).
     * Gibt zurück, wie viele Items er abgeworfen hat.
     */
    public int giveTo(Player owner, String key, int max) {
        if (!(e.level() instanceof ServerLevel sl)) return 0;
        Item exact = key != null && key.startsWith("item:") ? CompanionCraft.item(key.substring(5)) : null;
        Resources.Res res = key == null || exact != null ? null : Resources.byKey(key);
        if (exact == null) max = Integer.MAX_VALUE;
        BlockPos drop = safeDropSpot(sl, owner);
        int given = 0;
        for (int i = 0; i < inv().getContainerSize() && given < max; i++) {
            ItemStack s = inv().getItem(i);
            if (s.isEmpty()) continue;
            boolean take = exact != null ? s.is(exact) : res != null ? res.item().test(s) : !keep(s);
            if (!take) continue;
            if (drop == null) { // nirgends sicher: lieber behalten als in Lava/Abgrund werfen
                e.tell(CompanionTexts.tr("entity.give.unsafe"));
                return 0;
            }
            int n = Math.min(s.getCount(), max - given);
            if (given == 0) {
                if (owner != null) e.getLookControl().setLookAt(owner);
                e.swingArm();
            }
            ItemEntity ie = new ItemEntity(sl, drop.getX() + 0.5, drop.getY() + 0.05, drop.getZ() + 0.5, s.copyWithCount(n), 0, 0.12, 0);
            ie.setDefaultPickUpDelay();
            if (!sl.addFreshEntity(ie)) continue; // abgelehnt: behalten statt verlieren
            given += n;
            s.shrink(n);
        }
        e.collectBlockedUntil = e.tickCount + 300;
        e.tell(given > 0 ? CompanionTexts.tr("entity.give.done", given) : CompanionTexts.tr("entity.give.none"));
        return given;
    }

    /**
     * Wohin abgegebene Items fallen. Zuerst ein Block vor dem Besitzer (Blickrichtung), dann seine Füße und die
     * Felder neben ihm, zuletzt die eigenen Füße. Nur Stellen, an denen die Items auf festem Boden liegen bleiben:
     * keine Lava, kein Feuer, kein Kaktus, kein Wasser (treibt weg), kein tiefer Abgrund. null = nirgends sicher.
     */
    public BlockPos safeDropSpot(ServerLevel sl, Player owner) {
        List<BlockPos> spots = new ArrayList<>();
        if (owner != null && owner.level() == sl) {
            BlockPos feet = owner.blockPosition();
            spots.add(feet.relative(owner.getDirection()));
            spots.add(feet);
            for (Direction d : Direction.Plane.HORIZONTAL) spots.add(feet.relative(d));
        }
        spots.add(e.blockPosition());
        for (BlockPos p : spots) if (dropSafe(sl, p)) return p;
        return null;
    }

    private static boolean dropSafe(ServerLevel sl, BlockPos p) {
        if (!sl.hasChunkAt(p)) return false;
        for (int d = 0; d <= 6; d++) {
            BlockPos q = p.below(d);
            if (q.getY() < sl.getMinY()) return false;
            BlockState st = sl.getBlockState(q);
            if (dropHazard(st) || !st.getFluidState().isEmpty()) return false;
            for (Direction side : Direction.Plane.HORIZONTAL) { // Lava/Feuer/Kaktus direkt daneben greift beim Fallen
                BlockState n = sl.getBlockState(q.relative(side));
                if (dropHazard(n) || n.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return false;
            }
            var shape = st.getCollisionShape(sl, q);
            if (!shape.isEmpty()) return d == 0 && shape.max(Direction.Axis.Y) < 1; // Stufe/Teppich ja, volle Wand nein
            BlockPos under = q.below();
            BlockState below = sl.getBlockState(under);
            if (!below.getCollisionShape(sl, under).isEmpty()) return !dropHazard(below) && below.getFluidState().isEmpty();
        }
        return false; // tiefer als 6 Blöcke: Abgrund
    }

    private static boolean dropHazard(BlockState st) {
        return st.is(BlockTags.FIRE) || st.is(BlockTags.CAMPFIRES) || st.is(Blocks.CACTUS) || st.is(Blocks.MAGMA_BLOCK)
                || st.is(Blocks.LAVA_CAULDRON) || st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }
}
