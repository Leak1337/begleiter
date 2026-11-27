package dev.begleiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.begleiter.world.SafeLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Baustein Expedition: Wissen und Handgriffe für Netherfestung, Endfestung und End (Strukturen finden,
 * Enderaugen werfen und einsetzen, Spawner, Kristalle, Mobs einer Art). Kein eigener gespeicherter Zustand; die
 * Ziele (Festung, Portalraum, Endportal) liegen weiter an der Entity, weil Planer und Aufgaben sie setzen.
 */
public final class CompanionExpedition {
    private final CompanionEntity e;

    CompanionExpedition(CompanionEntity e) { this.e = e; }

    /** Nächste Netherfestung (wie ein erfahrener Spieler, der weiß, wo er suchen muss). */
    public BlockPos locateFortress() {
        return locateFortress(e.blockPosition());
    }

    public BlockPos alternateFortress(BlockPos rejected, int attempt) {
        BlockPos anchor = e.netherPortal == null ? e.blockPosition() : e.netherPortal;
        Direction direction = Direction.from2DDataValue(attempt & 3);
        BlockPos found = locateFortress(anchor.relative(direction, 128 * (1 + (attempt / 4) % 3)));
        return found != null && found.distSqr(rejected) >= 64 * 64 ? found : null;
    }

    /**
     * Festung schon von der Oberwelt aus abschätzen (Netherkoordinaten am Portal = Oberwelt / 8), damit er genug
     * Essen für den ganzen Weg mitnimmt statt drüben an der Reiseprüfung umzukehren.
     */
    public BlockPos fortressFromOverworld(BlockPos overworldPortal) {
        var server = e.level().getServer();
        ServerLevel nether = server == null ? null : server.getLevel(Level.NETHER);
        if (nether == null || overworldPortal == null) return null;
        var reg = nether.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var fortress = reg.get(net.minecraft.world.level.levelgen.structure.BuiltinStructures.FORTRESS);
        if (fortress.isEmpty()) return null;
        BlockPos origin = new BlockPos(overworldPortal.getX() / 8, 64, overworldPortal.getZ() / 8);
        var set = net.minecraft.core.HolderSet.direct(fortress.get());
        // Die Suche nach "der nächsten" liefert nicht immer die nächste: von vier versetzten Punkten aus suchen, nächste nehmen
        BlockPos best = nether.findNearestMapStructure(set, origin, 40, false);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos found = nether.findNearestMapStructure(set, origin.relative(d, 160), 24, false);
            if (found != null && (best == null || found.distSqr(origin) < best.distSqr(origin))) best = found;
        }
        return best;
    }

    private BlockPos locateFortress(BlockPos origin) {
        if (!(e.level() instanceof ServerLevel sl) || sl.dimension() != Level.NETHER) return null;
        var reg = sl.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var fortress = reg.get(net.minecraft.world.level.levelgen.structure.BuiltinStructures.FORTRESS);
        if (fortress.isEmpty()) return null;
        return sl.findNearestMapStructure(net.minecraft.core.HolderSet.direct(fortress.get()), origin, 40, false);
    }

    /**
     * Festung, zu der ein Enderauge fliegen würde, und darin der Portalraum. Den Portalraum liest er aus der
     * Weltstruktur (statt die ganze Festung abzusuchen). Gibt die Mitte des Portalraums zurück. Lädt bewusst den
     * Chunk am Festungsstart, um die Strukturdaten zu lesen (einziger absichtlicher Chunk-Ladevorgang).
     */
    public BlockPos locateEndRoom() {
        if (!(e.level() instanceof ServerLevel sl) || !e.inOverworld()) return null;
        BlockPos start = sl.findNearestMapStructure(net.minecraft.tags.StructureTags.EYE_OF_ENDER_LOCATED, e.blockPosition(), 100, false);
        if (start == null) return null;
        var reg = sl.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var stronghold = reg.get(net.minecraft.world.level.levelgen.structure.BuiltinStructures.STRONGHOLD);
        if (stronghold.isPresent()) {
            sl.getChunk(start); // Struktur-Daten laden
            var ss = sl.structureManager().getStructureAt(start, stronghold.get().value());
            if (ss != null && ss.isValid()) {
                for (var piece : ss.getPieces())
                    if (piece instanceof net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces.PortalRoom)
                        return piece.getBoundingBox().getCenter();
            }
        }
        return new BlockPos(start.getX(), 30, start.getZ()); // Fallback: Festungsmitte, Tiefe geschätzt
    }

    /** Ein Enderauge in Richtung Ziel werfen (wie ein Spieler; es kann zerbrechen oder liegen bleiben). */
    public void throwEye(BlockPos target) {
        if (!(e.level() instanceof ServerLevel sl)) return;
        int slot = e.findItem(Items.ENDER_EYE);
        if (slot < 0) return;
        var eye = new net.minecraft.world.entity.projectile.EyeOfEnder(sl, e.getX(), e.getY(0.5), e.getZ());
        eye.setItem(e.getInventory().getItem(slot).copyWithCount(1));
        eye.signalTo(Vec3.atLowerCornerOf(target));
        sl.addFreshEntity(eye);
        e.getInventory().getItem(slot).shrink(1);
        sl.playSound(null, e.blockPosition(), net.minecraft.sounds.SoundEvents.ENDER_EYE_LAUNCH,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.5f, 0.4f);
        e.swingArm();
    }

    /** Endportal-Rahmen in der Nähe (ungeladene Chunks werden übersprungen). */
    public List<BlockPos> portalFrames(int r) {
        List<BlockPos> out = new ArrayList<>();
        if (!(e.level() instanceof ServerLevel sl)) return out;
        BlockPos c = e.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -12, -r), c.offset(r, 12, r)))
            if (SafeLevel.is(sl, p, Blocks.END_PORTAL_FRAME)) out.add(p.immutable());
        return out;
    }

    public static boolean frameHasEye(ServerLevel sl, BlockPos p) {
        BlockState s = sl.getBlockState(p);
        return s.is(Blocks.END_PORTAL_FRAME) && s.getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE);
    }

    /** Enderauge über die normale Item-Logik einsetzen – das letzte Auge öffnet das Portal wie beim Spieler. */
    public boolean placeEye(ServerLevel sl, BlockPos frame) {
        int slot = e.findItem(Items.ENDER_EYE);
        if (slot < 0 || frameHasEye(sl, frame)) return false;
        ItemStack one = e.getInventory().getItem(slot).copyWithCount(1);
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(frame).add(0, 0.5, 0), Direction.UP, frame, false);
        var ctx = new net.minecraft.world.item.context.UseOnContext(sl, null, InteractionHand.MAIN_HAND, one, hit) {};
        Items.ENDER_EYE.useOn(ctx);
        if (one.isEmpty()) e.getInventory().getItem(slot).shrink(1);
        e.swingArm();
        return frameHasEye(sl, frame);
    }

    /** Endkristalle (keine Mobs, nur mit Pfeilen zu treffen), nächste zuerst. */
    public List<EndCrystal> endCrystals(int r) {
        if (!(e.level() instanceof ServerLevel sl)) return List.of();
        List<EndCrystal> l = new ArrayList<>(sl.getEntitiesOfClass(EndCrystal.class, e.getBoundingBox().inflate(r, 128, r),
                net.minecraft.world.entity.Entity::isAlive));
        l.sort(Comparator.comparingDouble(e::distanceToSqr));
        return l;
    }

    /** Nächster Mob dieser Art (Entity-ID wie "blaze"), optional nur nahe center. */
    public Mob nearestOfType(String id, int r, BlockPos center) {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        var wanted = RegistryIds.parse(id);
        if (wanted == null) return null;
        Mob best = null;
        double bd = Double.MAX_VALUE;
        for (Mob m : sl.getEntitiesOfClass(Mob.class, e.getBoundingBox().inflate(r), Mob::isAlive)) {
            if (!BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).equals(wanted)) continue;
            if (center != null && m.blockPosition().distSqr(center) > 48 * 48) continue;
            double d = e.distanceToSqr(m);
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    /** Nächster Monster-Spawner (in Festungen: Lohen-Spawner). */
    public BlockPos nearestSpawner(int r) {
        return e.level() instanceof ServerLevel sl ? nearestSpawner(sl, e.blockPosition(), r) : null;
    }

    /** Nächster Spawner im Würfel um c; ungeladene Chunks werden übersprungen, nicht geladen. */
    public static BlockPos nearestSpawner(ServerLevel sl, BlockPos c, int r) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -r / 2, -r), c.offset(r, r / 2, r))) {
            if (!SafeLevel.is(sl, p, Blocks.SPAWNER) || !fortressSpawner(sl, p)) continue;
            double d = p.distSqr(c);
            if (d < bd) { bd = d; best = p.immutable(); }
        }
        return best;
    }

    /** Nächster Spawner in den geladenen Chunks im Umkreis r (Festungen sind größer als der Blockscan von nearestSpawner). */
    public BlockPos nearestLoadedSpawner(int r) {
        if (!(e.level() instanceof ServerLevel sl)) return null;
        BlockPos c = e.blockPosition(), best = null;
        double bd = Double.MAX_VALUE;
        for (int cx = (c.getX() - r) >> 4; cx <= (c.getX() + r) >> 4; cx++) {
            for (int cz = (c.getZ() - r) >> 4; cz <= (c.getZ() + r) >> 4; cz++) {
                var chunk = sl.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockPos p : chunk.getBlockEntities().keySet()) {
                    if (!sl.getBlockState(p).is(Blocks.SPAWNER) || !fortressSpawner(sl, p)) continue;
                    double d = p.distSqr(c);
                    if (d < bd) { bd = d; best = p.immutable(); }
                }
            }
        }
        return best;
    }

    /**
     * Lohen-Spawner stehen in Festungen aus Netherziegeln; der Magmawürfel-Spawner einer Bastion steht in Schwarzstein
     * (beobachtet: jeder Ausflug ging zum Bastion-Spawner, 0 Lohenruten, Piglins und Lava).
     */
    static boolean fortressSpawner(ServerLevel sl, BlockPos spawner) {
        for (BlockPos q : BlockPos.betweenClosed(spawner.offset(-4, -2, -4), spawner.offset(4, 2, 4)))
            if (SafeLevel.is(sl, q, Blocks.NETHER_BRICKS)) return true;
        return false;
    }
}
