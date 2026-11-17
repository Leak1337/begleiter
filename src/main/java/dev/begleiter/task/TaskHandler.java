package dev.begleiter.task;

/**
 * Führt genau eine Aufgabe der Warteschlange aus. Pro Aktivierung der Aufgabe (sie steht vorn und das Ziel läuft) gibt
 * es eine neue Instanz: {@link #start} genau einmal, dann {@link #tick} bis zu einem Endstatus, zuletzt {@link #stop}
 * genau einmal – auch bei Unterbrechung (Kampf, Pause), Abbruch oder Ersetzung der Aufgabe. Zustand, der nur zu dieser
 * Aufgabe gehört, lebt in der Instanz und verschwindet mit ihr.
 */
public interface TaskHandler {
    enum Status {
        /** Läuft weiter; die Aufgabe bleibt vorn. */
        RUNNING,
        /** Ziel nachweislich erreicht: Aufgabe wird entfernt. */
        DONE,
        /** Aufgegeben (Ziel belegt, Besitzer weg, nichts zu geben …): Aufgabe wird entfernt. */
        FAILED,
        /** Der Handler hat die Warteschlange selbst umgebaut (ersetzt, vorne ergänzt, alles abgebrochen): nichts entfernen. */
        REPLACED
    }

    default void start(TaskContext ctx) { }

    Status tick(TaskContext ctx);

    default void stop(TaskContext ctx) { }
}
