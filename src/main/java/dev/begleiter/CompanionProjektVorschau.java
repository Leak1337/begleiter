package dev.begleiter;

import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Erste Phase der Projekt-KI: ausschließlich LESEN und PLANEN.
 * Kein Bauauftrag, keine Inventaränderung, keine Chunks laden.
 * Anzeigen werden als Components erst in der Sprache des Empfängers aufgelöst.
 */
public final class CompanionProjektVorschau {
    private CompanionProjektVorschau() {}

    public record Schritt(int nummer, Component titelText, List<Integer> voraussetzungen) {
        public Schritt(int nummer, String titel, List<Integer> voraussetzungen) {
            this(nummer, CompanionTexts.literal(titel), voraussetzungen);
        }
        public String titel() { return titelText.getString(); }
    }

    public record GraphPruefung(boolean gueltig, Component hinweisText) {
        public GraphPruefung(boolean gueltig, String hinweis) { this(gueltig, CompanionTexts.literal(hinweis)); }
        public String hinweis() { return hinweisText.getString(); }
    }

    public record Vorschau(List<Schritt> schritte, Map<Item, Integer> material,
                           int blockpositionen, int naturbloecke, boolean sicher, Component hinweisText) {
        public Vorschau(List<Schritt> schritte, Map<Item, Integer> material,
                        int blockpositionen, int naturbloecke, boolean sicher, String hinweis) {
            this(schritte, material, blockpositionen, naturbloecke, sicher, CompanionTexts.literal(hinweis));
        }
        public String hinweis() { return hinweisText.getString(); }
        public String beschreiben() { return beschreibenText().getString(); }

        public Component beschreibenText() {
            List<Component> plan = schritte.stream().map(s -> (Component) CompanionTexts.tr("project.step", s.nummer(), s.titelText())).toList();
            List<Component> bedarf = material.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<Item, Integer>>comparingInt(Map.Entry::getValue).reversed())
                    .limit(6).map(e -> (Component) CompanionTexts.tr("materials.entry", e.getValue(),
                            new net.minecraft.world.item.ItemStack(e.getKey()).getHoverName())).toList();
            return CompanionTexts.tr("project.preview", CompanionTexts.tr("project.name"), blockpositionen, naturbloecke,
                    sicher ? CompanionTexts.tr("project.site.ok") : CompanionTexts.tr("project.site.blocked", hinweisText),
                    CompanionTexts.join(plan, "; "), bedarf.isEmpty() ? CompanionTexts.tr("materials.none") : CompanionTexts.join(bedarf, ", "));
        }
    }

    /** Prüft den begrenzten Schrittgraphen, ohne Welt- oder Planerzugriffe. */
    public static GraphPruefung graphPruefen(List<Schritt> schritte) {
        if (schritte == null || schritte.isEmpty()) return new GraphPruefung(false, CompanionTexts.tr("project.graph.empty"));
        if (schritte.size() > 128) return new GraphPruefung(false, CompanionTexts.tr("project.graph.many_steps"));
        Set<Integer> ids = new HashSet<>();
        for (Schritt schritt : schritte) {
            if (schritt == null || schritt.nummer() <= 0) return new GraphPruefung(false, CompanionTexts.tr("project.graph.invalid_id"));
            if (!ids.add(schritt.nummer())) return new GraphPruefung(false, CompanionTexts.tr("project.graph.duplicate_id"));
            if (schritt.voraussetzungen() == null || schritt.voraussetzungen().size() > 16)
                return new GraphPruefung(false, CompanionTexts.tr("project.graph.many_dependencies"));
        }
        for (Schritt schritt : schritte) {
            Set<Integer> voraussetzungen = new HashSet<>();
            for (Integer voraussetzung : schritt.voraussetzungen()) {
                if (voraussetzung == null || voraussetzung == schritt.nummer())
                    return new GraphPruefung(false, CompanionTexts.tr("project.graph.self_dependency"));
                if (!ids.contains(voraussetzung)) return new GraphPruefung(false, CompanionTexts.tr("project.graph.missing_dependency"));
                if (!voraussetzungen.add(voraussetzung)) return new GraphPruefung(false, CompanionTexts.tr("project.graph.duplicate_dependency"));
            }
        }
        for (Schritt schritt : schritte) {
            if (zyklus(schritt.nummer(), schritte, new HashSet<>(), new HashSet<>()))
                return new GraphPruefung(false, CompanionTexts.tr("project.graph.cycle"));
        }
        return new GraphPruefung(true, Component.empty());
    }

    private static boolean zyklus(int id, List<Schritt> schritte, Set<Integer> besucht, Set<Integer> pfad) {
        if (!pfad.add(id)) return true;
        if (!besucht.add(id)) {
            pfad.remove(id);
            return false;
        }
        for (Schritt schritt : schritte) {
            if (schritt.nummer() == id) {
                for (Integer voraussetzung : schritt.voraussetzungen())
                    if (zyklus(voraussetzung, schritte, besucht, pfad)) return true;
                break;
            }
        }
        pfad.remove(id);
        return false;
    }

    /** Berechnet ein einziges kleines Beispielprojekt vor dem Spieler. */
    public static Vorschau planen(ServerPlayer spieler) {
        ServerLevel welt = spieler.level();
        return planen(welt, spieler.blockPosition(), spieler.getDirection());
    }

    /** Berechnet am gespeicherten Standort, ohne Chunks nachzuladen oder zu schreiben. */
    public static Vorschau planen(ServerLevel welt, BlockPos standort, Direction ausrichtung) {
        var vorlage = CompanionStructures.house(CompanionStructures.WOOD, 5, 5);
        var bloecke = CompanionSchematics.place(vorlage, standort,
                ausrichtung == null ? Direction.NORTH : ausrichtung, Rotation.NONE, true);

        List<Schritt> schritte = List.of(
                new Schritt(1, CompanionTexts.tr("project.step.1"), List.of()),
                new Schritt(2, CompanionTexts.tr("project.step.2"), List.of(1)),
                new Schritt(3, CompanionTexts.tr("project.step.3"), List.of(1, 2)),
                new Schritt(4, CompanionTexts.tr("project.step.4"), List.of(3)),
                new Schritt(5, CompanionTexts.tr("project.step.5"), List.of(4)));

        // Abhängigkeiten sind azyklisch, Schritte sind bewusst begrenzt und deterministisch.
        GraphPruefung graph = graphPruefen(schritte);
        Component problem = graph.gueltig() ? null
                : CompanionTexts.tr("project.site.graph", graph.hinweisText());
        int natur = 0;
        if (bloecke.size() > 2048) problem = CompanionTexts.tr("project.site.limit");
        if (problem == null) {
            for (var eintrag : bloecke.entrySet()) {
                BlockPos ort = eintrag.getKey();
                if (!welt.hasChunkAt(ort)) { problem = CompanionTexts.tr("project.site.unloaded"); break; }
                if (welt.getBlockEntity(ort) != null) {
                    problem = CompanionTexts.tr("project.site.container");
                    break;
                }
                BlockState vorhanden = welt.getBlockState(ort);
                if (vorhanden.equals(eintrag.getValue())) continue;
                if (!vorhanden.getFluidState().isEmpty()) {
                    problem = CompanionTexts.tr("project.site.fluid");
                    break;
                }
                if (!vorhanden.isAir()) {
                    if (!CompanionTerraform.natural(welt, ort, vorhanden)) {
                        problem = CompanionTexts.tr("project.site.protected");
                        break;
                    }
                    natur++;
                }
            }
        }

        // Bei ungeladenen Chunks keine Weltlese-Operationen mehr starten.
        Map<Item, Integer> material;
        if (problem == null) {
            var bauauftrag = CompanionBuilder.create("project-preview", bloecke);
            material = CompanionBuilder.remaining(welt, bauauftrag);
        } else {
            material = new HashMap<>();
            for (BlockState ziel : bloecke.values()) {
                Item item = CompanionBuilder.itemFor(ziel);
                if (item != null) material.merge(item, 1, Integer::sum);
            }
        }
        return new Vorschau(schritte, Map.copyOf(material), bloecke.size(),
                natur, problem == null, problem == null ? Component.empty() : problem);
    }
}
