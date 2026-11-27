package dev.begleiter;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Schreibt einen neuen Inhalt in mehrere Container, ohne Items zu verlieren.
 * <p>
 * Ablauf: Ausgangszustand als Kopie sichern, Zielbild nur aus Kopien berechnen, vor dem Schreiben prüfen, dass
 * sich nichts geändert hat und Ziel und Ausgang dieselben Items in derselben Menge enthalten, nur abweichende Plätze
 * schreiben, danach zurücklesen. Weicht das Ergebnis ab (Container kürzt Stapel, lehnt ab, wirft eine Ausnahme),
 * wird der Ausgangszustand zurückgeschrieben. Gelingt auch das nicht, nennt das Ergebnis die fehlenden Items, damit
 * der Aufrufer sie abwerfen und den Fehler melden kann. Eine atomare Garantie über beliebige Container gibt es nicht;
 * geprüft wird, was sich zurücklesen lässt.
 */
public final class ContainerRewrite {
    private ContainerRewrite() {}

    public enum Outcome {
        /** Zielbild geschrieben und zurückgelesen. */
        WRITTEN,
        /** Nichts zu tun: Zielbild gleich Ausgangszustand. */
        UNCHANGED,
        /** Nicht geschrieben: Inhalt hat sich seit der Sicherung geändert. */
        STALE,
        /** Nicht geschrieben: Zielbild enthält andere Items/Mengen als der Ausgang (Rechenfehler). */
        MISMATCH,
        /** Schreiben schlug fehl, Ausgangszustand vollständig wiederhergestellt. */
        RESTORED,
        /** Schreiben und Wiederherstellen schlugen fehl; {@link Result#missing()} nennt, was fehlt. */
        RESTORE_FAILED
    }

    /** @param missing Items, die nach einer fehlgeschlagenen Wiederherstellung in keinem Container mehr liegen. */
    public record Result(Outcome outcome, List<ItemStack> missing) {
        public boolean changed() { return outcome == Outcome.WRITTEN; }
        public boolean failed() { return outcome == Outcome.RESTORED || outcome == Outcome.RESTORE_FAILED
                || outcome == Outcome.MISMATCH || outcome == Outcome.STALE; }
    }

    /** Kopie jedes Platzes aller Container. */
    public static List<ItemStack[]> snapshot(List<? extends Container> boxes) {
        List<ItemStack[]> out = new ArrayList<>(boxes.size());
        for (Container c : boxes) {
            ItemStack[] slots = new ItemStack[c.getContainerSize()];
            for (int i = 0; i < slots.length; i++) slots[i] = c.getItem(i).copy();
            out.add(slots);
        }
        return out;
    }

    /** Leeres Zielbild in der Form der Container. */
    public static ItemStack[][] emptyLayout(List<? extends Container> boxes) {
        ItemStack[][] out = new ItemStack[boxes.size()][];
        for (int b = 0; b < out.length; b++) {
            out[b] = new ItemStack[boxes.get(b).getContainerSize()];
            java.util.Arrays.fill(out[b], ItemStack.EMPTY);
        }
        return out;
    }

    /**
     * Legt {@code stack} ab Platz {@code slot} in Container {@code box} des Zielbilds, unter Beachtung von
     * Stapelgrenze und {@link Container#canPlaceItem}. Teilt den Stapel auf freie Folgeplätze desselben Containers auf.
     *
     * @return erster Platz hinter dem zuletzt belegten; der Rest von {@code stack} bleibt im Stapel (leer = alles abgelegt)
     */
    public static int place(Container box, ItemStack[] target, int slot, ItemStack stack) {
        while (!stack.isEmpty() && slot < target.length) {
            if (target[slot].isEmpty() && box.canPlaceItem(slot, stack)) {
                int limit = Math.max(1, Math.min(stack.getMaxStackSize(), box.getMaxStackSize(stack)));
                target[slot] = stack.split(limit);
            }
            slot++;
        }
        return slot;
    }

    /** Legt {@code stack} in irgendeinen freien Platz irgendeines Containers; Rest bleibt im Stapel. */
    public static void placeAnywhere(List<? extends Container> boxes, ItemStack[][] target, ItemStack stack) {
        for (int b = 0; b < boxes.size() && !stack.isEmpty(); b++) place(boxes.get(b), target[b], 0, stack);
    }

    /** Schreibt das Zielbild (siehe Klassenbeschreibung). */
    public static Result apply(List<? extends Container> boxes, List<ItemStack[]> before, ItemStack[][] target) {
        if (!sameShape(boxes, before, target)) return new Result(Outcome.STALE, List.of());
        for (int b = 0; b < boxes.size(); b++)
            if (!matches(boxes.get(b), before.get(b))) return new Result(Outcome.STALE, List.of());
        if (!ItemTally.of(before).equals(ItemTally.of(java.util.Arrays.asList(target))))
            return new Result(Outcome.MISMATCH, List.of());
        boolean differs = false;
        for (int b = 0; b < boxes.size() && !differs; b++) differs = !matches(boxes.get(b), target[b]);
        if (!differs) return new Result(Outcome.UNCHANGED, List.of());

        try {
            for (int b = 0; b < boxes.size(); b++) write(boxes.get(b), target[b]);
        } catch (RuntimeException ex) {
            // weiter mit dem Zurücklesen: dort entscheidet sich, ob wiederhergestellt werden muss
        }
        boolean ok = true;
        for (int b = 0; b < boxes.size() && ok; b++) ok = matches(boxes.get(b), target[b]);
        if (ok) {
            boxes.forEach(ContainerRewrite::markChanged);
            return new Result(Outcome.WRITTEN, List.of());
        }

        for (int b = 0; b < boxes.size(); b++) {
            Container c = boxes.get(b);
            for (int i = 0; i < c.getContainerSize(); i++) {
                try {
                    if (!ItemStack.matches(c.getItem(i), before.get(b)[i])) c.setItem(i, before.get(b)[i].copy());
                } catch (RuntimeException ex) {
                    // einzelner Platz lässt sich nicht schreiben; der Abgleich unten findet die Lücke
                }
            }
            markChanged(c);
        }
        boolean restored = true;
        for (int b = 0; b < boxes.size() && restored; b++) restored = matches(boxes.get(b), before.get(b));
        if (restored) return new Result(Outcome.RESTORED, List.of());
        return new Result(Outcome.RESTORE_FAILED,
                ItemTally.of(before).minus(ItemTally.of(snapshot(boxes))).stacks());
    }

    private static void write(Container c, ItemStack[] target) {
        for (int i = 0; i < target.length; i++)
            if (!ItemStack.matches(c.getItem(i), target[i])) c.setItem(i, target[i].copy());
    }

    private static void markChanged(Container c) {
        try {
            c.setChanged();
        } catch (RuntimeException ignored) {
            // Speichern-Markierung ist kein Inhalt
        }
    }

    private static boolean matches(Container c, ItemStack[] want) {
        if (c.getContainerSize() != want.length) return false;
        for (int i = 0; i < want.length; i++) if (!ItemStack.matches(c.getItem(i), want[i])) return false;
        return true;
    }

    private static boolean sameShape(List<? extends Container> boxes, List<ItemStack[]> before, ItemStack[][] target) {
        if (boxes.size() != before.size() || boxes.size() != target.length) return false;
        for (int b = 0; b < boxes.size(); b++) {
            int n = boxes.get(b).getContainerSize();
            if (before.get(b).length != n || target[b].length != n) return false;
        }
        return true;
    }

    /** Menge je Item samt Komponenten. */
    static final class ItemTally {
        private final List<ItemStack> kinds = new ArrayList<>();
        private final List<Long> counts = new ArrayList<>();

        static ItemTally of(List<ItemStack[]> slots) {
            ItemTally t = new ItemTally();
            for (ItemStack[] box : slots) for (ItemStack s : box) t.add(s, s.getCount());
            return t;
        }

        void add(ItemStack s, long n) {
            if (s.isEmpty() || n == 0) return;
            for (int i = 0; i < kinds.size(); i++) {
                if (ItemStack.isSameItemSameComponents(kinds.get(i), s)) {
                    counts.set(i, counts.get(i) + n);
                    return;
                }
            }
            kinds.add(s.copyWithCount(1));
            counts.add(n);
        }

        long count(ItemStack kind) {
            for (int i = 0; i < kinds.size(); i++)
                if (ItemStack.isSameItemSameComponents(kinds.get(i), kind)) return counts.get(i);
            return 0;
        }

        /** Was in {@code this} mehr vorhanden ist als in {@code other}. */
        ItemTally minus(ItemTally other) {
            ItemTally t = new ItemTally();
            for (int i = 0; i < kinds.size(); i++) {
                long d = counts.get(i) - other.count(kinds.get(i));
                if (d > 0) t.add(kinds.get(i), d);
            }
            return t;
        }

        /** Als Stapel in normaler Stapelgröße. */
        List<ItemStack> stacks() {
            List<ItemStack> out = new ArrayList<>();
            for (int i = 0; i < kinds.size(); i++) {
                long left = counts.get(i);
                int max = Math.max(1, kinds.get(i).getMaxStackSize());
                while (left > 0) {
                    int n = (int) Math.min(left, max);
                    out.add(kinds.get(i).copyWithCount(n));
                    left -= n;
                }
            }
            return out;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ItemTally t) || t.kinds.size() != kinds.size()) return false;
            for (int i = 0; i < kinds.size(); i++) if (t.count(kinds.get(i)) != counts.get(i)) return false;
            return true;
        }

        @Override
        public int hashCode() { return kinds.size(); }
    }
}
