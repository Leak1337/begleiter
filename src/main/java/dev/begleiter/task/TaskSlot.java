package dev.begleiter.task;

import java.util.function.Function;

/**
 * Bindet höchstens einen Handler an die vorderste Aufgabe. Wechselt die Aufgabe (andere Instanz vorn), wird der alte
 * Handler gestoppt, bevor der neue startet; liefert die Fabrik keinen Handler, bleibt der Platz leer.
 *
 * @param <T> Aufgabentyp; Identität zählt, nicht Gleichheit (zwei gleiche Aufträge sind zwei Aufgaben)
 */
public final class TaskSlot<T> {
    private final Function<? super T, ? extends TaskHandler> factory;
    private T task;
    private TaskHandler handler;

    public TaskSlot(Function<? super T, ? extends TaskHandler> factory) {
        this.factory = factory;
    }

    /** Handler für die aktuelle Aufgabe (beim Wechsel alten stoppen, neuen starten); null = kein Handler zuständig. */
    public TaskHandler sync(T current, TaskContext ctx) {
        if (task != current) release(ctx);
        if (handler == null && current != null) {
            TaskHandler h = factory.apply(current);
            if (h == null) return null;
            // erst eintragen, dann starten: wirft start, stoppt der nächste release ihn trotzdem genau einmal
            task = current;
            handler = h;
            h.start(ctx);
        }
        return handler;
    }

    /** Laufenden Handler genau einmal stoppen und vergessen (Ende, Ersetzung, Unterbrechung). */
    public void release(TaskContext ctx) {
        TaskHandler h = handler;
        handler = null;
        task = null;
        if (h != null) h.stop(ctx);
    }

    /** Aufgabe, zu der der laufende Handler gehört (null ohne Handler). */
    public T task() {
        return task;
    }

    public boolean active() {
        return handler != null;
    }
}
