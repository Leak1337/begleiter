package dev.begleiter;

import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * Begrenzter Projektlebenszyklus: Entwurf, Prüfung und Verwerfen, ohne die Welt zu verändern.
 *
 * Ein bestätigter Eintrag ist ausdrücklich nur ein Entwurf. Er darf weder
 * Tasks starten noch Materialien reservieren oder die Welt verändern. Die
 * spätere Ausführung bekommt einen eigenen, separat abgenommenen Schritt.
 */
public final class CompanionProjektVerwaltung {
    private static final String PROJEKTNAME = "kleines Holzhaus";
    private static final int MAX_HINT_LENGTH = 256;

    public enum Zustand { ENTWURF }

    public record Entwurf(String name, String dimension, BlockPos standort, Direction ausrichtung,
                          int schritte, int blockpositionen, int naturbloecke,
                          boolean standortSicher, String hinweis) {
        public Entwurf {
            dimension = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension;
            standort = standort.immutable();
            ausrichtung = ausrichtung == null ? Direction.NORTH : ausrichtung;
            name = name == null || name.isBlank() ? PROJEKTNAME : name;
            hinweis = begrenzen(hinweis);
        }

        private static String begrenzen(String text) {
            if (text == null) return "";
            return text.length() <= MAX_HINT_LENGTH ? text : text.substring(0, MAX_HINT_LENGTH);
        }
    }

    public record Ergebnis(boolean erfolgreich, Component nachrichtText) {
        public Ergebnis(boolean erfolgreich, String nachricht) { this(erfolgreich, CompanionTexts.literal(nachricht)); }
        public String nachricht() { return nachrichtText.getString(); }
    }

    private Entwurf entwurf;

    public Entwurf entwurf() {
        return entwurf;
    }

    public boolean vorhanden() {
        return entwurf != null;
    }

    /**
     * Übernimmt nur eine sichere Vorschau als Entwurf. Diese Methode startet
     * bewusst keine Aufgaben und ruft keinen Bauausführer auf.
     */
    public Ergebnis bestaetigen(CompanionProjektVorschau.Vorschau vorschau,
                                BlockPos standort, Direction ausrichtung) {
        return bestaetigen(vorschau, "minecraft:overworld", standort, ausrichtung);
    }

    public Ergebnis bestaetigen(CompanionProjektVorschau.Vorschau vorschau, String dimension,
                                BlockPos standort, Direction ausrichtung) {
        if (entwurf != null)
            return new Ergebnis(false, CompanionTexts.tr("project.exists"));
        if (vorschau == null || !vorschau.sicher()) {
            Component grund = vorschau == null || vorschau.hinweis().isBlank()
                    ? CompanionTexts.tr("project.site.failed") : vorschau.hinweisText();
            return new Ergebnis(false, CompanionTexts.tr("project.not_saved", grund));
        }
        entwurf = new Entwurf(PROJEKTNAME, dimension, standort, ausrichtung, vorschau.schritte().size(),
                vorschau.blockpositionen(), vorschau.naturbloecke(), true, "");
        return new Ergebnis(true, CompanionTexts.tr("project.saved"));
    }

    /** Prüft den gespeicherten Standort erneut; der Entwurf wird dabei nicht verändert. */
    public Ergebnis pruefen(ServerLevel welt) {
        if (entwurf == null) return new Ergebnis(false, CompanionTexts.tr("project.no_recheck"));
        String dimension = welt.dimension().identifier().toString();
        if (!entwurf.dimension().equals(dimension))
            return new Ergebnis(false, CompanionTexts.tr("project.dimension"));
        var vorschau = CompanionProjektVorschau.planen(welt, entwurf.standort(), entwurf.ausrichtung());
        if (!vorschau.sicher())
            return new Ergebnis(false, CompanionTexts.tr("project.recheck_blocked", vorschau.hinweisText()));
        if (vorschau.blockpositionen() != entwurf.blockpositionen()
                || vorschau.naturbloecke() != entwurf.naturbloecke())
            return new Ergebnis(false, CompanionTexts.tr("project.changed"));
        return new Ergebnis(true, CompanionTexts.tr("project.verified"));
    }

    public Ergebnis verwerfen() {
        if (entwurf == null)
            return new Ergebnis(false, CompanionTexts.tr("project.no_discard"));
        entwurf = null;
        return new Ergebnis(true, CompanionTexts.tr("project.discarded"));
    }

    public String beschreiben() { return beschreibenText().getString(); }

    public Component beschreibenText() {
        if (entwurf == null) return CompanionTexts.tr("project.none");
        // Vorhandene ProjectV1-Spielstände nutzen diesen festen deutschen Namen; eigene Namen bleiben wörtlich.
        Component name = PROJEKTNAME.equals(entwurf.name()) ? CompanionTexts.tr("project.name") : CompanionTexts.literal(entwurf.name());
        return CompanionTexts.tr("project.draft", name, entwurf.standort().toShortString(), entwurf.schritte(),
                entwurf.blockpositionen(), entwurf.naturbloecke(), CompanionTexts.tr("project.state.draft"));
    }

    /** Speichert nur kleine, begrenzte Zustandsdaten; keine Tasks oder Inventarreservierungen. */
    public CompoundTag speichern() {
        CompoundTag tag = new CompoundTag();
        if (entwurf == null) return tag;
        tag.putString("Zustand", Zustand.ENTWURF.name());
        tag.putString("Name", entwurf.name());
        tag.putString("Dimension", entwurf.dimension());
        tag.putLong("Standort", entwurf.standort().asLong());
        tag.putString("Ausrichtung", entwurf.ausrichtung().name());
        tag.putInt("Schritte", entwurf.schritte());
        tag.putInt("Blockpositionen", entwurf.blockpositionen());
        tag.putInt("Naturbloecke", entwurf.naturbloecke());
        tag.putBoolean("StandortSicher", entwurf.standortSicher());
        tag.putString("Hinweis", entwurf.hinweis());
        return tag;
    }

    /** Ungültige oder fremde Zustände werden sicher als leerer Entwurf verworfen. */
    public void laden(CompoundTag tag) {
        entwurf = null;
        if (tag == null || !Zustand.ENTWURF.name().equals(tag.getStringOr("Zustand", ""))) return;
        if (!tag.contains("Standort")) return;
        Direction richtung;
        try {
            richtung = Direction.valueOf(tag.getStringOr("Ausrichtung", Direction.NORTH.name()));
        } catch (IllegalArgumentException ex) {
            richtung = Direction.NORTH;
        }
        entwurf = new Entwurf(
                tag.getStringOr("Name", PROJEKTNAME),
                tag.getStringOr("Dimension", "minecraft:overworld"),
                BlockPos.of(tag.getLongOr("Standort", 0)),
                richtung,
                Math.clamp(tag.getIntOr("Schritte", 0), 1, 128),
                Math.clamp(tag.getIntOr("Blockpositionen", 0), 0, 2048),
                Math.clamp(tag.getIntOr("Naturbloecke", 0), 0, 2048),
                tag.getBooleanOr("StandortSicher", false),
                tag.getStringOr("Hinweis", ""));
        if (!entwurf.standortSicher()) entwurf = null;
    }
}
