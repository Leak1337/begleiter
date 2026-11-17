package dev.begleiter.task;

import dev.begleiter.CompanionTexts;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import dev.begleiter.CompanionCraft;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.Resources;

/**
 * DELIVER: Inventar ins Lager bringen (keepStock: Vorrat/Quoten behalten), ohne Lager dem Besitzer vor die Füße. Ist
 * das Lager voll, baut er eine weitere Kiste oder sein Haus an; tries zählt diese Versuche.
 */
final class DeliverHandler implements TaskHandler {
    private final Task task;
    private final TaskSpec.Deliver deliver;

    DeliverHandler(Task task) {
        this.task = task;
        this.deliver = (TaskSpec.Deliver) task.spec();
    }

    @Override
    public Status tick(TaskContext ctx) {
        CompanionEntity mob = ctx.mob();
        ServerLevel sl = ctx.level();
        var zone = dev.begleiter.CompanionZones.work(mob, dev.begleiter.CompanionZones.Kind.STORAGE);
        if (zone != null) {
            var boxes = mob.storageBoxes(sl, zone.center());
            if (boxes.isEmpty()) return Status.FAILED;
            if (!ctx.approach(boxes.getFirst(), 12.25)) return Status.RUNNING;
            int before = mob.usedSlots();
            for (BlockPos pos : boxes) {
                var box = mob.containerAt(sl, pos);
                if (box != null) { mob.depositInto(box, true); mob.memory.noteChest(sl, pos, box); }
            }
            if (mob.usedSlots() >= before) mob.storageFullUntil = mob.tickCount + 1200;
            return Status.DONE;
        }
        BlockPos home = mob.getHome();
        if (home == null) {
            Player owner = mob.getOwner();
            if (owner == null) {
                mob.logInfo(CompanionTexts.tr("handler.deliver.no_target"));
                return Status.FAILED;
            }
            if (!ctx.approachEntity(owner, 9)) return Status.RUNNING;
            mob.dropToOwner(owner);
            return Status.DONE;
        }
        if (!ctx.approach(home, 3.5 * 3.5)) return Status.RUNNING;
        Container c = mob.containerAt(sl, home);
        if (c == null) {
            // Kiste weg: Lager vergessen, dann stellt er (selbstständig) eine neue auf statt endlos hinzulaufen
            mob.tell(CompanionTexts.tr("handler.deliver.rebuild"));
            mob.clearHome();
            return Status.FAILED;
        }
        boolean keepQuota = deliver.keepStock();
        mob.swingArm();
        int usedBefore = mob.usedSlots();
        int left = mob.depositInto(c, keepQuota);
        mob.memory.noteChest(sl, home, c); // Inhalt nach dem Einlagern merken
        // Lagerkiste voll: in die anderen Lager-Kisten daneben
        for (BlockPos other : mob.storageChests(sl)) {
            if (left == 0) break;
            if (other.equals(home)) continue;
            Container oc = mob.containerAt(sl, other);
            if (oc == null) continue;
            left = mob.depositInto(oc, keepQuota);
            mob.memory.noteChest(sl, other, oc);
        }
        // Alles voll: eine neue Kiste dazustellen (vorher herstellen, wenn keine dabei)
        if (left > 0) {
            if (mob.findItem(Items.CHEST) < 0 && task.tries() < 2) {
                mob.tell(CompanionTexts.tr("handler.deliver.more_chests"));
                List<Task> pre = new ArrayList<>();
                if (CompanionCraft.count(mob, "#planks") + CompanionCraft.count(mob, "#logs") * 4 < 8)
                    pre.add(Task.gather(Resources.WOOD.key(), 3));
                pre.add(Task.craft("chest", 1));
                pre.add(task.retry());
                mob.replaceCurrent(pre);
                return Status.REPLACED;
            }
            BlockPos added = mob.placeStorageChest(sl);
            Container nc = added == null ? null : mob.containerAt(sl, added);
            if (nc != null) {
                mob.depositInto(nc, keepQuota);
                mob.memory.noteChest(sl, added, nc);
            } else if (mob.getHouseOrigin() != null && mob.findItem(Items.CHEST) >= 0 && task.tries() < 3
                    && mob.houseAnnexes() < CompanionEntity.MAX_ANNEXES) {
                // Kein Platz mehr im Haus: anbauen, dann die Kiste dort aufstellen
                mob.tell(CompanionTexts.tr("handler.deliver.annex"));
                mob.replaceCurrent(List.of(Task.extend(0), task.retry()));
                return Status.REPLACED;
            } else {
                mob.problem(CompanionTexts.tr("handler.deliver.full"));
                mob.storageFullUntil = mob.tickCount + 6000; // 5 min nicht wieder deswegen hinlaufen
            }
        }
        // Hat nichts gebracht (nur Vorrat/Ausrüstung dabei): nicht sofort wieder herkommen
        if (mob.usedSlots() >= usedBefore) mob.storageFullUntil = Math.max(mob.storageFullUntil, mob.tickCount + 1200);
        return Status.DONE;
    }
}
