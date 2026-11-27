package dev.begleiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import dev.begleiter.debug.BotDebug.EventKind;
import dev.begleiter.task.TaskSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Baustein Kisten und Lager: Lagerkisten finden und erweitern, Kisten nach Items durchsuchen, sortieren, die
 * Freigaben für Auftrags- und Arbeitslager, Vorratsregeln, und das sichtbare Öffnen einer Kiste. Nur Laufzeit.
 */
public final class CompanionStorage {
    /** Wie lange eine Kiste nach einem Zugriff offen bleibt (Deckel sichtbar auf), in Ticks. */
    public static final int CONTAINER_OPEN_TICKS = 30;

    private final CompanionEntity e;
    private BlockPos openedPos;
    private Container opened;
    private int openedUntil;

    CompanionStorage(CompanionEntity e) { this.e = e; }

    static boolean isStorage(BlockState s) {
        return s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock;
    }

    /** Seine Lager-Kisten: die Lagerkiste und alle Kisten/Fässer in 6 Blöcken darum (Lagerkiste zuerst). */
    public List<BlockPos> storageChests(ServerLevel sl) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos homePos = e.savedHome();
        if (homePos == null) return out;
        out.add(homePos);
        List<BlockPos> area = new ArrayList<>();
        BlockPos.betweenClosed(homePos.offset(-6, -2, -6), homePos.offset(6, 3, 6)).forEach(p -> area.add(p.immutable()));
        // dazu das ganze Haus samt Anbauten (die Lagerkiste kann auch außerhalb stehen)
        if (e.house.origin() != null)
            for (BlockPos p : e.house.floorArea())
                if (Math.abs(p.getX() - homePos.getX()) > 6 || Math.abs(p.getZ() - homePos.getZ()) > 6
                        || p.getY() - homePos.getY() > 3 || homePos.getY() - p.getY() > 2) area.add(p.immutable());
        for (BlockPos p : area) {
            // Lager weit weg (ungeladen): nur die Lagerkiste selbst nennen, nichts nachladen
            if (p.equals(homePos) || !sl.hasChunkAt(p) || !isStorage(sl.getBlockState(p))) continue;
            BlockState bs = sl.getBlockState(p);
            // Doppelkiste nur einmal (die andere Hälfte zählt mit)
            if (bs.getBlock() instanceof ChestBlock && bs.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = p.relative(ChestBlock.getConnectedDirection(bs));
                if (out.contains(other)) continue;
            }
            out.add(p.immutable());
        }
        out.sort(Comparator.comparingDouble(p -> p.equals(homePos) ? -1 : p.distSqr(homePos)));
        return out;
    }

    /**
     * Lager voll: neue Kiste daneben stellen. Erst eine einzelne Kiste zur Doppelkiste erweitern, sonst eine neue
     * Kiste neben die anderen. Braucht eine Kiste im Inventar. Rückgabe: Position oder null (kein Platz/keine Kiste).
     */
    public BlockPos placeStorageChest(ServerLevel sl) {
        int slot = e.findItem(Items.CHEST);
        if (slot < 0 || e.savedHome() == null) return null;
        List<BlockPos> chests = storageChests(sl);
        // Mit Haus: nur auf feste Plätze im Haus (Gänge bleiben frei); ohne Haus irgendwo neben die anderen
        Map<BlockPos, Direction> inHouse = e.house.origin() == null ? null : e.house.chestSpots();
        BlockPos spot = null;
        BlockState place = Blocks.CHEST.defaultBlockState();
        BlockPos partner = null; // vorhandene Kiste, die erst NACH dem bestätigten Setzen zur Hälfte wird
        BlockState partnerState = null;
        // 1. Einzelne Kiste -> Doppelkiste
        for (BlockPos c : chests) {
            BlockState bs = sl.getBlockState(c);
            if (!(bs.getBlock() instanceof ChestBlock) || bs.getValue(ChestBlock.TYPE) != ChestType.SINGLE) continue;
            Direction facing = bs.getValue(ChestBlock.FACING);
            for (boolean cw : new boolean[]{true, false}) {
                BlockPos p = c.relative(cw ? facing.getClockWise() : facing.getCounterClockWise());
                if (!freeChestSpot(sl, p) || (inHouse != null && !inHouse.containsKey(p))) continue;
                // Hälfte, deren Partner im Uhrzeigersinn liegt = LEFT
                partner = c;
                partnerState = bs.setValue(ChestBlock.TYPE, cw ? ChestType.LEFT : ChestType.RIGHT);
                place = place.setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, cw ? ChestType.RIGHT : ChestType.LEFT);
                spot = p;
                break;
            }
            if (spot != null) break;
        }
        // 2. Sonst eine neue einzelne Kiste (setBlock verbindet nicht von selbst mit Nachbarkisten)
        if (spot == null && inHouse != null) {
            for (Map.Entry<BlockPos, Direction> entry : inHouse.entrySet())
                if (freeChestSpot(sl, entry.getKey())) {
                    spot = entry.getKey();
                    place = place.setValue(ChestBlock.FACING, entry.getValue());
                    break;
                }
        } else if (spot == null) {
            outer:
            for (int r = 1; r <= 3; r++)
                for (BlockPos c : chests)
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos p = c.relative(d, r);
                        if (!freeChestSpot(sl, p)) continue;
                        spot = p;
                        BlockState ref = sl.getBlockState(c);
                        if (ref.getBlock() instanceof ChestBlock) place = place.setValue(ChestBlock.FACING, ref.getValue(ChestBlock.FACING));
                        break outer;
                    }
        }
        if (spot == null || !e.placeConfirmed(sl, spot, place)) return null;
        if (partner != null && sl.getBlockState(partner).is(Blocks.CHEST)) sl.setBlock(partner, partnerState, 3);
        sl.playSound(null, spot, place.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
        e.getInventory().getItem(slot).shrink(1);
        e.swingArm();
        e.logInfo(EventKind.BUILD, CompanionTexts.tr("log.storage.expanded", spot.toShortString()));
        return spot;
    }

    private boolean freeChestSpot(ServerLevel sl, BlockPos p) {
        return sl.getBlockState(p).isAir() && sl.getBlockState(p.above()).isAir()
                && sl.getBlockState(p.below()).isFaceSturdy(sl, p.below(), Direction.UP)
                && !e.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p));
    }

    public Container containerAt(ServerLevel sl, BlockPos pos) {
        if (e.work.active()) return workStorage(sl, pos);
        if (!sl.hasChunkAt(pos)) return null; // gemerkte Kiste in ungeladenem Chunk: unbekannt, nicht nachladen
        BlockState bs = sl.getBlockState(pos);
        BlockEntity be = sl.getBlockEntity(pos);
        Container box = bs.getBlock() instanceof ChestBlock cb ? ChestBlock.getContainer(cb, bs, sl, pos, true)
                : be instanceof Container c ? c : null;
        if (box != null) e.memory.noteChest(sl, pos, box); // hineingeschaut -> Inhalt merken
        return box;
    }

    /** Mit Quellenfilter für eigene Pläne; Suchen im Auftrag des Spielers nehmen die Variante ohne Filter. */
    public BlockPos findContainerWith(Item item, int r, Predicate<BlockPos> allowed) {
        if (!(e.level() instanceof ServerLevel)) return null;
        return findContainerWith(item, containerCandidates(r), allowed);
    }

    /**
     * Kisten/Fässer im Würfel um ihn, Lager zuerst, dann nach Abstand. Teuer (ganzer Würfel); eine Planung fragt es
     * deshalb einmal ab und prüft danach nur diese Liste für jede Zutat ({@link #findContainerWith(Item, List, Predicate)}).
     */
    public List<BlockPos> containerCandidates(int r) {
        List<BlockPos> candidates = new ArrayList<>();
        if (!(e.level() instanceof ServerLevel sl)) return candidates;
        BlockPos home = e.getHome();
        if (home != null) candidates.add(home);
        BlockPos c = e.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        dev.begleiter.world.SectionScanner.scanBox(sl, c.offset(-r, -r / 2, -r), c.offset(r, r / 2, r),
                CompanionStorage::isStorage, (x, y, z, st) -> found.add(new BlockPos(x, y, z)));
        found.sort(CompanionStations::scanOrder); // gleiche Reihenfolge wie früher, damit Gleichstände gleich ausgehen
        candidates.addAll(found);
        candidates.sort(Comparator.comparingDouble(p -> p.equals(e.getHome()) ? -1 : p.distSqr(c)));
        return candidates;
    }

    /** Wie {@link #findContainerWith(Item, int, Predicate)}, mit schon gesuchten Kandidaten. */
    public BlockPos findContainerWith(Item item, List<BlockPos> candidates, Predicate<BlockPos> allowed) {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        BlockPos c = e.blockPosition();
        for (BlockPos p : candidates) {
            if (!allowed.test(p)) continue;
            Container box = containerAt(sl, p);
            if (box == null) continue;
            if (box.countItem(item) > stockReserveAt(sl, p, item)) return p;
        }
        // Weiter weg: Kisten, in die er schon mal geschaut hat
        for (int guard = 0; guard < 8; guard++) {
            CompanionMemory.Place known = e.memory.chestWith(item, sl, c, 48, allowed);
            if (known == null) return null;
            if (!sl.hasChunkAt(known.pos)) return known.pos;
            Container box = containerAt(sl, known.pos); // frischt den gemerkten Inhalt auf
            if (box == null) { e.memory.forget(known); continue; }
            if (box.countItem(item) > stockReserveAt(sl, known.pos, item)) return known.pos;
        }
        return null;
    }

    public BlockPos findNearestContainer(BlockPos center, int r) {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -r / 2, -r), center.offset(r, r / 2, r))) {
            if (!sl.hasChunkAt(p)) continue;
            if (!isStorage(sl.getBlockState(p))) continue;
            double d = p.distSqr(center);
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    /** Kisten/Fässer im Umkreis (Doppelkisten nur einmal), sortiert nach Abstand zur Mitte. */
    public List<BlockPos> storageBoxes(ServerLevel sl, BlockPos center) {
        List<BlockPos> out = new ArrayList<>();
        var zone = CompanionZones.work(e, CompanionZones.Kind.STORAGE);
        BlockPos from = zone == null ? center.offset(-8, -4, -8) : zone.min();
        BlockPos to = zone == null ? center.offset(8, 4, 8) : zone.max();
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            if (!sl.hasChunkAt(p)) continue;
            BlockState bs = sl.getBlockState(p);
            if (!isStorage(bs)) continue;
            if (bs.getBlock() instanceof ChestBlock && bs.getValue(ChestBlock.TYPE) == ChestType.RIGHT) continue;
            if (zone != null && bs.getBlock() instanceof ChestBlock && bs.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                    && !zone.contains(p.relative(ChestBlock.getConnectedDirection(bs)))) continue;
            if (containerAt(sl, p.immutable()) != null) out.add(p.immutable());
        }
        out.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(center)).thenComparingLong(BlockPos::asLong));
        return out;
    }

    /** Sortiert alle Kisten/Fässer im Umkreis: zusammenführen und alphabetisch nach Item-ID füllen. */
    public int sortChests(ServerLevel sl, BlockPos center) {
        List<Container> boxes = new ArrayList<>();
        for (BlockPos p : storageBoxes(sl, center)) {
            Container c = containerAt(sl, p);
            if (c != null) boxes.add(c);
        }
        if (boxes.isEmpty()) return 0;
        List<ItemStack[]> before = ContainerRewrite.snapshot(boxes);
        List<ItemStack> all = new ArrayList<>();
        for (ItemStack[] box : before) for (ItemStack s : box) if (!s.isEmpty()) all.add(s.copy());
        all.sort(Comparator.comparing((ItemStack s) -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()));
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack s : all) {
            ItemStack rest = s.copy();
            for (ItemStack m : merged) {
                if (rest.isEmpty()) break;
                if (ItemStack.isSameItemSameComponents(m, rest) && m.getCount() < m.getMaxStackSize()) {
                    int move = Math.min(rest.getCount(), m.getMaxStackSize() - m.getCount());
                    m.grow(move);
                    rest.shrink(move);
                }
            }
            if (!rest.isEmpty()) merged.add(rest);
        }
        ItemStack[][] target = ContainerRewrite.emptyLayout(boxes);
        for (ItemStack s : merged) {
            ContainerRewrite.placeAnywhere(boxes, target, s);
            if (!s.isEmpty()) { // passt nicht (Stapelgrenzen der Container): nichts verändern
                e.logError(CompanionTexts.tr("jobs.sort.no_room"));
                return 0;
            }
        }
        if (CompanionJobs.reportRewrite(e, sl, ContainerRewrite.apply(boxes, before, target)) != null) return 0;
        return boxes.size();
    }

    // ------------------------------------------------------------------ Freigaben und Vorratsregeln

    public boolean stockHarvestAllowed(ServerLevel world, TaskSpec.Break task) {
        var zone = e.zones.get(CompanionZones.Kind.ROAM, world);
        BlockPos home = e.getHome();
        return (!e.work.active() || e.work.permitsStockGather(world))
                && zone != null && zone.contains(task.pos()) && home != null && !e.memory.dangerous(world, task.pos(), 8)
                && e.stockRules.stream().anyMatch(rule -> rule.collect() && rule.home().equals(home)
                        && rule.owner().equals(e.getOwnerUuid()) && rule.item().toString().equals(task.stockItem()));
    }

    public boolean harvestAllowed(ServerLevel world, BlockPos pos, BlockState state) {
        if (!state.is(BlockTags.LOGS) || e.woodZoneOwner == null || !e.woodZoneOwner.equals(e.getOwnerUuid())) return true;
        var zone = e.zones.get(CompanionZones.Kind.ROAM, world);
        return zone != null && zone.contains(pos);
    }

    public Container orderStorage(ServerLevel world, BlockPos pos) {
        var area = e.zones.get(CompanionZones.Kind.STORAGE, world);
        BlockPos home = e.getHome();
        return safeStorage(world, pos, candidate -> candidate.equals(home) || area != null && area.contains(candidate),
                home, false);
    }

    public Container workStorage(ServerLevel world, BlockPos pos) {
        return safeStorage(world, pos, candidate -> e.work.permitsStorage(world, candidate), e.work.approvedHome(), true);
    }

    private Container safeStorage(ServerLevel world, BlockPos pos, Predicate<BlockPos> allowed, BlockPos home,
                                  boolean requireOwnerPermission) {
        if (!world.hasChunkAt(pos)) return null;
        BlockState state = world.getBlockState(pos);
        BlockPos other = state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                ? ChestBlock.getConnectedBlockPos(pos, state) : null;
        boolean designated = home != null && (pos.equals(home) || home.equals(other)) && allowed.test(home);
        if (!designated && (!allowed.test(pos) || other != null && !allowed.test(other))) return null;
        if ((requireOwnerPermission || e.getOwnerAnywhere() != null)
                && (!e.mayUse(world, pos) || other != null && !e.mayUse(world, other))) return null;
        if (state.getBlock() instanceof ChestBlock chest) {
            if (!CompanionTripPreparation.availableStorage(world, pos) || other != null && !CompanionTripPreparation.availableStorage(world, other))
                return null;
            return ChestBlock.getContainer(chest, state, world, pos, false);
        }
        return world.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity box
                && !box.isLocked() && box.getLootTable() == null ? box : null;
    }

    public int stockReserveAt(ServerLevel world, BlockPos pos, Item item) {
        if (e.stockRules.isEmpty()) return 0;
        BlockPos home = e.getHome();
        if (home == null || !world.hasChunkAt(pos)) return 0;
        if (!pos.equals(home)) {
            BlockState s = world.getBlockState(pos);
            if (!(s.getBlock() instanceof ChestBlock) || s.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                    || !ChestBlock.getConnectedBlockPos(pos, s).equals(home)) return 0;
        }
        for (var rule : e.stockRules) {
            if (rule.home().equals(home) && rule.owner().equals(e.getOwnerUuid()) && ItemIds.resolve(rule.item().toString()) == item)
                return rule.target();
        }
        return 0;
    }

    boolean protectedStockStorage(ServerLevel world, BlockPos pos) {
        if (!e.work.is(CompanionWork.Kind.STORAGE)) return false;
        for (var rule : e.stockRules)
            if (stockReserveAt(world, pos, ItemIds.resolve(rule.item().toString())) > 0) return true;
        return false;
    }

    // ------------------------------------------------------------------ Kiste sichtbar öffnen

    boolean isOpen(BlockPos pos) {
        // Doppelkiste: beide Hälften gelten als offen
        return openedPos != null && e.tickCount < openedUntil && pos.distManhattan(openedPos) <= 1;
    }

    /** Die gerade sichtbar geöffnete Kiste (null = keine). Für Tests und Anzeige. */
    public BlockPos openedContainerPos() { return openedPos; }

    /**
     * Wie ein Spieler an eine Kiste, ein Fass oder einen Ofen greifen: hinsehen, Arm bewegen und – wo der Block das kann –
     * den Deckel öffnen (echter Öffner wie der Kupfergolem, Deckel und Geräusch kommen vom Block). Nach
     * {@link #CONTAINER_OPEN_TICKS} schließt er wieder. Rein sichtbar: Inhalt und Buchung bleiben unberührt.
     */
    public void useContainer(ServerLevel sl, BlockPos pos) {
        e.getLookControl().setLookAt(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        e.swingArm();
        if (!sl.hasChunkAt(pos)) return;
        if (pos.equals(openedPos) && opened != null) {
            openedUntil = e.tickCount + CONTAINER_OPEN_TICKS; // noch offen: nur verlängern
            return;
        }
        closeContainer();
        BlockState bs = sl.getBlockState(pos);
        BlockEntity be = sl.getBlockEntity(pos);
        Container box = bs.getBlock() instanceof ChestBlock cb ? ChestBlock.getContainer(cb, bs, sl, pos, true)
                : be instanceof Container c ? c : null;
        if (box == null) return;
        openedPos = pos.immutable();
        opened = box;
        openedUntil = e.tickCount + CONTAINER_OPEN_TICKS;
        try {
            box.startOpen(e);
        } catch (RuntimeException ex) {
            CompanionLog.LOG.debug("[Begleiter] Container open animation failed at {}: {}", pos, ex.toString());
        }
        if (bs.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock) furnaceEffects(sl, pos, bs);
    }

    /** Ofen hat keinen Deckel: Rauch und Knistern an der Vorderseite, solange er daran arbeitet. */
    public void furnaceEffects(ServerLevel sl, BlockPos pos, BlockState bs) {
        Direction front = bs.hasProperty(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING)
                ? bs.getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING) : Direction.NORTH;
        double x = pos.getX() + 0.5 + front.getStepX() * 0.52, y = pos.getY() + 0.4, z = pos.getZ() + 0.5 + front.getStepZ() * 0.52;
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, x, y, z, 3, 0.15, 0.1, 0.15, 0.0);
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, x, y, z, 1, 0.1, 0.05, 0.1, 0.0);
        sl.playSound(null, pos, net.minecraft.sounds.SoundEvents.FURNACE_FIRE_CRACKLE, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 1f);
    }

    /** Geöffnete Kiste wieder schließen (Zeit abgelaufen, anderer Zugriff, Tod/Entfernen). */
    public void closeContainer() {
        var box = opened;
        opened = null;
        openedPos = null;
        if (box == null) return;
        try {
            box.stopOpen(e);
        } catch (RuntimeException ex) {
            CompanionLog.LOG.debug("[Begleiter] Container close animation failed: {}", ex.toString());
        }
    }

    void tickOpened() {
        if (opened != null && e.tickCount >= openedUntil) closeContainer();
    }
}
