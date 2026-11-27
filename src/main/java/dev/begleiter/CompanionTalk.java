package dev.begleiter;

import dev.begleiter.config.BegleiterConfig;
import net.minecraft.network.chat.Component;

/**
 * Baustein Gesprächigkeit: wie oft er freiwillig etwas sagt. Die pro Begleiter gewählte Stufe steht als
 * {@code talkOverride} an der Entity (gespeichert); hier liegen nur die Laufzeit-Merker.
 */
public final class CompanionTalk {
    /** Höchstens eine freiwillige Planmeldung je so viele Ticks bei NORMAL. */
    public static final int CHATTER_INTERVAL = 1200;

    private final CompanionEntity e;
    /** Spielzeit der letzten freiwilligen Meldung und die zuletzt gesagten. */
    private long lastChatterAt = Long.MIN_VALUE;
    private final java.util.ArrayDeque<String> recentChatter = new java.util.ArrayDeque<>();

    CompanionTalk(CompanionEntity e) { this.e = e; }

    BegleiterConfig.Talk level() {
        return e.talkOverride != null ? e.talkOverride : BegleiterConfig.get().talk;
    }

    /** Eine Stufe gesprächiger (+1) oder leiser (-1); liefert die neue Stufe. */
    public BegleiterConfig.Talk change(int step) {
        var levels = BegleiterConfig.Talk.values();
        e.talkOverride = levels[Math.clamp(level().ordinal() + step, 0, levels.length - 1)];
        return e.talkOverride;
    }

    /**
     * Darf diese freiwillige Meldung (Planstufe, kein Befehl, kein Problem) in den Chat? QUIET: nie. NORMAL: höchstens
     * eine pro Minute und keine der zuletzt gesagten erneut. CHATTY: immer. Das Protokoll bleibt davon unberührt.
     */
    boolean mayChatter(Component msg) {
        var talk = level();
        if (talk == BegleiterConfig.Talk.CHATTY) return true;
        if (talk == BegleiterConfig.Talk.QUIET) return false;
        String text = msg.getString();
        long now = e.level().getGameTime();
        if (lastChatterAt != Long.MIN_VALUE && now - lastChatterAt < CHATTER_INTERVAL) return false;
        if (recentChatter.contains(text)) return false;
        lastChatterAt = now;
        recentChatter.addLast(text);
        while (recentChatter.size() > 8) recentChatter.removeFirst();
        return true;
    }

    /**
     * Fortschrittsmeldung zu einem laufenden Auftrag (z. B. Bau bei 25/50/75 %): freiwillig, aber vom Spieler erwartet.
     * QUIET schweigt, NORMAL und CHATTY melden ohne Minutenabstand, weil die Meldungen ohnehin selten sind.
     */
    public void tellProgress(Component msg) {
        if (mayReportProgress()) e.tell(msg);
    }

    boolean mayReportProgress() {
        return level() != BegleiterConfig.Talk.QUIET;
    }
}
