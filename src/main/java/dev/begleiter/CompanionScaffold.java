package dev.begleiter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.begleiter.CompanionEntity.Pillar;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Baustein Gerüst: Hochbauen mit Müll-Blöcken und die gemerkten Gerüstblöcke, die er später wieder abbaut.
 * Hält den laufenden Sprung (nicht gespeichert) und die Gerüstliste (gespeichert unter {@code Scaffold}).
 */
public final class CompanionScaffold {
    /** Mehr Gerüstblöcke merkt er sich nicht; die ältesten bleiben dann einfach stehen. */
    static final int MAX_REMEMBERED = 96;

    private final CompanionEntity e;
    private final List<BlockPos> blocks = new ArrayList<>();
    private boolean active;
    private Pillar kind = Pillar.PATH;
    private int startY, x, z, ticks;
    private BlockPos obstacle;

    CompanionScaffold(CompanionEntity e) { this.e = e; }

    // ------------------------------------------------------------------ Gemerkte Gerüstblöcke

    public boolean any() { return !blocks.isEmpty(); }
    public int size() { return blocks.size(); }
    public boolean contains(BlockPos p) { return blocks.contains(p); }
    /** Nur lesend; ändern über {@link #remember}, {@link #forget} und {@link #clear}. */
    public List<BlockPos> positions() { return Collections.unmodifiableList(blocks); }
    public void remember(BlockPos p) {
        while (blocks.size() >= MAX_REMEMBERED) blocks.removeFirst();
        blocks.add(p.immutable());
    }
    public void forget(BlockPos p) { blocks.remove(p); }
    public void clear() { blocks.clear(); }

    public BlockPos nearest(BlockPos from) {
        return blocks.stream().min(Comparator.comparingDouble(p -> p.distSqr(from))).orElse(null);
    }

    /** Was nicht mehr steht (abgebaut, Luft), vergessen; Ungeladenes ebenfalls, ohne nachzuladen. */
    public void prune(ServerLevel sl) {
        blocks.removeIf(p -> !sl.hasChunkAt(p) || sl.getBlockState(p).isAir());
    }

    // ------------------------------------------------------------------ Material

    /** Der Müll-Block, von dem er am meisten dabei hat (so geht keine Sorte zuerst aus). */
    public Item item() {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!s.isEmpty() && !e.reservedForRecipe(s) && CompanionEntity.isJunk(s.getItem()))
                counts.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /**
     * Gerüst, das er später selbst wieder abbaut (TREE): nur Müll, den er mit seinem Werkzeug auch abbauen kann –
     * sonst sitzt er ohne Spitzhacke oben auf einer Bruchstein-Säule fest. Sonst wie {@link #item()}.
     */
    public Item item(Pillar purpose) {
        if (purpose != Pillar.TREE) return item();
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (s.isEmpty() || e.reservedForRecipe(s) || !CompanionEntity.isJunk(s.getItem())) continue;
            BlockState st = Block.byItem(s.getItem()).defaultBlockState();
            if (st.requiresCorrectToolForDrops() && !e.hasCorrectToolFor(st)) continue;
            counts.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** Wie viele Müll-Blöcke insgesamt (alle Sorten zusammen). */
    public int count() {
        int n = 0;
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            if (!s.isEmpty() && CompanionEntity.isJunk(s.getItem())) n += s.getCount();
        }
        return n;
    }

    // ------------------------------------------------------------------ Hochbauen

    public boolean active() { return active; }
    public void reset() { active = false; }
    /** Block über ihm, der den letzten Hochbau-Schritt verhindert hat (Ergebnis 2). */
    public BlockPos obstacle() { return obstacle; }

    // Auf stehende Mobs wirkt jeden Tick Schwerkraft (deltaMovement.y ~ -0.08), daher onGround statt Geschwindigkeit prüfen
    private boolean grounded() {
        return e.onGround() && e.getY() - Math.floor(e.getY()) < 0.02;
    }

    /** Fackeln, Blumen, Gras u.ä. (ohne Kollision, sofort abbaubar) wegnehmen – sie stehen beim Hochbauen im Weg. */
    private void clearSoft(ServerLevel sl, BlockPos p) {
        BlockState st = sl.getBlockState(p);
        if (st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty()) return;
        if (!st.getCollisionShape(sl, p).isEmpty() || st.getDestroySpeed(sl, p) != 0) return;
        e.worldBreak(sl, p, true);
    }

    /**
     * Ein Hochbau-Schritt: springen, Block unter sich setzen. 1 = Block gesetzt, 0 = läuft, 2 = Hindernis
     * ({@link #obstacle()}), -1 = nicht möglich. purpose zählt beim Absprung; ein laufender Sprung behält seine Art,
     * auch wenn ihn ein anderer Aufrufer zu Ende führt.
     */
    public int step(ServerLevel sl, Pillar purpose) {
        Item item = item(active ? kind : purpose);
        if (item == null) return -1;
        BlockPos feet = e.blockPosition();
        if (!active) {
            if (!grounded()) return 0;
            for (int up = 2; up <= 3; up++) {
                BlockPos o = feet.above(up);
                clearSoft(sl, o);
                BlockState st = sl.getBlockState(o);
                if (!st.isAir() && !st.canBeReplaced()) {
                    if (st.getDestroySpeed(sl, o) < 0 || e.hazardAround(sl, o, true)) return -1;
                    obstacle = o.immutable();
                    return 2;
                }
            }
            // Erst genau auf die Blockmitte, sonst rutscht er beim Springen vom eigenen Pfeiler
            double cx = feet.getX() + 0.5 - e.getX(), cz = feet.getZ() + 0.5 - e.getZ();
            e.getNavigation().stop();
            if (cx * cx + cz * cz > 0.04) {
                e.getMoveControl().setWantedPosition(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.6);
                return 0;
            }
            startY = feet.getY();
            kind = purpose;
            active = true;
            ticks = 0;
            x = feet.getX();
            z = feet.getZ();
            e.getJumpControl().jump();
            return 0;
        }
        ticks++;
        // In der Luft mittig über dem Pfeiler bleiben
        e.setDeltaMovement((x + 0.5 - e.getX()) * 0.5, e.getDeltaMovement().y, (z + 0.5 - e.getZ()) * 0.5);
        e.getMoveControl().setWantedPosition(x + 0.5, e.getY(), z + 0.5, 0.0);
        if (e.getY() >= startY + 1.0) {
            BlockPos under = new BlockPos(x, startY, z);
            clearSoft(sl, under); // eigene Fackel im Schacht o.ä.: erst wegnehmen
            BlockState cur = sl.getBlockState(under);
            active = false;
            if (cur.isAir() || cur.canBeReplaced()) {
                int slot = e.findItem(item);
                if (slot < 0) return -1;
                BlockState place = Block.byItem(item).defaultBlockState();
                if (!e.placeConfirmed(sl, under, place)) return 0; // abgelehnt: nichts abbuchen, neuer Sprung
                sl.playSound(null, under, place.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
                e.getInventory().getItem(slot).shrink(1);
                // Sehr viele Gerüstblöcke: die ältesten nicht mehr zum Abbauen vormerken (bleiben stehen)
                if (kind == Pillar.TREE) remember(under);
                return 1;
            }
            return 0;
        }
        if (ticks > 30) active = false; // Sprung hat nicht geklappt -> nochmal
        return 0;
    }

    // ------------------------------------------------------------------ Speichern

    void save(ValueOutput out) {
        if (!blocks.isEmpty()) out.store("Scaffold", BlockPos.CODEC.listOf(), blocks);
    }

    void load(ValueInput in) {
        blocks.clear();
        in.read("Scaffold", BlockPos.CODEC.listOf()).ifPresent(list -> {
            for (BlockPos p : list) remember(p);
        });
    }
}
