package dev.begleiter;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Baustein Notunterschlupf: wie ein Spieler in der Not Müll-Blöcke um sich und über den Kopf setzen, drinnen
 * heilen und danach wieder abbauen. Nur zur Laufzeit; nach dem Neuladen bleiben die Blöcke einfach stehen.
 */
public final class CompanionShelter {
    private final CompanionEntity e;
    private final List<BlockPos> blocks = new ArrayList<>();
    private BlockPos feet;
    /** Nachtlager ohne Haus: bleibt bis zum Morgen stehen, egal wie gesund er ist. */
    private boolean untilMorning;
    /** Seit wann er bei Tag nur noch wegen Monstern davor drin bleibt (-1 = nicht). */
    private int dayWaitSince = -1;

    CompanionShelter(CompanionEntity e) { this.e = e; }

    public boolean active() { return !blocks.isEmpty() || untilMorning && feet != null; }

    /** Für die Nacht einmauern (kein Haus in Reichweite); morgens baut er sich wieder frei. */
    public boolean sealInUntilMorning(ServerLevel sl) {
        if (!sealIn(sl)) return false;
        untilMorning = true;
        return true;
    }

    /** Die freien Plätze um Füße und Kopf und über dem Kopf, die zugemauert werden müssen. */
    private static List<BlockPos> gaps(ServerLevel sl, BlockPos feet) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos head = feet.above();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            out.add(feet.relative(d));
            out.add(head.relative(d));
        }
        out.add(head.above());
        out.removeIf(p -> !sl.getBlockState(p).canBeReplaced());
        return out;
    }

    /** Einmauern; false = nicht möglich (in der Luft, zu wenig Blöcke, ein Mob steht in einer Lücke). */
    public boolean sealIn(ServerLevel sl) {
        if (!e.onGround() || active()) return false;
        // Im eigenen Haus nicht einmauern: nach dem Neuladen blieb so ein Erdklotz im Haus stehen
        if (e.isInsideHouse()) return false;
        BlockPos at = e.blockPosition();
        Vec3 centered = Vec3.atBottomCenterOf(at);
        if (!CompanionSafeTeleport.safe(e, sl, centered)) return false;
        if (!sl.getBlockState(at).canBeReplaced() || !sl.getBlockState(at.above()).canBeReplaced()) return false;
        List<BlockPos> gaps = gaps(sl, at);
        if (gaps.size() > e.scaffoldCount()) return false;
        for (BlockPos p : gaps)
            if (!sl.getEntitiesOfClass(LivingEntity.class, new AABB(p), other -> other != e).isEmpty()) return false;
        if (!CompanionSafeTeleport.move(e, sl, centered)) return false;
        for (BlockPos p : gaps) {
            Item item = e.scaffoldItem();
            int slot = item == null ? -1 : e.findItem(item);
            if (slot < 0) break;
            BlockState st = Block.byItem(item).defaultBlockState();
            if (!e.placeConfirmed(sl, p, st)) continue;
            e.getInventory().getItem(slot).shrink(1);
            blocks.add(p.immutable());
        }
        sl.playSound(null, at, net.minecraft.sounds.SoundEvents.STONE_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
        feet = at.immutable();
        e.stopRetreat();
        e.logInfo(CompanionTexts.tr("log.shelter.built", at.toShortString(), blocks.size()));
        return true;
    }

    /** Geheilt: Unterschlupf wieder abbauen (Blöcke sammelt er ein). Woanders hin geschickt: einfach vergessen. */
    void tick(ServerLevel sl) {
        if (untilMorning) {
            if (feet == null || !e.blockPosition().equals(feet)) {
                untilMorning = false;
                blocks.clear();
                return;
            }
            if (sl.isDarkOutside()) return;
            // Morgens warten, bis die Zombies verbrannt bzw. weg sind (beobachtet: mit 4 Leben raus, 10 s später tot)
            if (monstersWaiting(sl, 8)) return;
            untilMorning = false; // Morgen: wieder freibauen, auch wenn er noch nicht ganz geheilt ist
        } else {
            if (blocks.isEmpty()) return;
            if (feet == null || !e.blockPosition().equals(feet)) {
                blocks.clear();
                return;
            }
            // Ohne Essen heilt er nicht (beobachtet: 6 Minuten mit 1 Leben im Unterschlupf, dann doch tot): bei Tag raus
            boolean canHeal = e.food.level() >= 14 || e.foodCount() > 0;
            if (e.getHealth() < e.getMaxHealth() * 0.8f && (canHeal || sl.isDarkOutside())) return;
            // Geheilt, aber draußen ist Nacht oder Monster warten direkt davor: noch zu lassen (beobachtet: aufgemacht,
            // sofort angezündet und auf 4 Leben)
            if (sl.isDarkOutside() && feet.getY() >= sl.getSeaLevel() - 8) return;
            if (monstersWaiting(sl, 6)) return;
        }
        dayWaitSince = -1;
        // nur seine eigenen Müll-Blöcke abbauen; was dort inzwischen anderes steht, bleibt
        for (BlockPos p : blocks) {
            BlockState st = sl.getBlockState(p);
            if (!st.isAir() && CompanionEntity.isJunk(st.getBlock().asItem())) e.worldBreak(sl, p, true);
        }
        blocks.clear();
        e.logInfo(CompanionTexts.tr("log.shelter.healed", (int) e.getHealth()));
    }

    /**
     * Monster, die direkt vor dem Lager auf ihn warten: etwa auf seiner Höhe (beobachtet: zwei Tage im
     * Lager, weil Monster in einer Höhle darunter mitzählten). Bei Tag höchstens eine Minute warten, dann trotzdem raus.
     */
    private boolean monstersWaiting(ServerLevel sl, int radius) {
        boolean any = !sl.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, e.getBoundingBox().inflate(radius),
                m -> m.isAlive() && m.getType() != net.minecraft.world.entity.EntityTypes.ENDERMAN
                        && Math.abs(m.getY() - e.getY()) <= 3).isEmpty();
        if (!any || sl.isDarkOutside()) return any;
        if (dayWaitSince < 0 || e.tickCount < dayWaitSince) dayWaitSince = e.tickCount;
        return e.tickCount - dayWaitSince < 1200;
    }
}
