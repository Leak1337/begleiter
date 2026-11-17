# Survival-Kistenraum: 24 Sorten, jeweils zwei Doppelkisten

Erweiterte Version des kleinen Kistenraums: **17 × 28 Blöcke Grundfläche, 9 Blöcke hoch** einschließlich Fundament und Technik. Pro Sorte stehen zwei automatisch befüllte Doppelkisten übereinander. Das sind 108 Stacks pro Sorte und insgesamt **165.888 Items** bei 64er-Stacks. Die beiden Kisten stehen direkt übereinander; die Verteilungstrichter sitzen dahinter. Der Itemrahmen mit dem Materialsymbol hängt an der unteren Kiste. Beide Kisten werden automatisch befüllt; eine feste Füllreihenfolge ist nicht vorgesehen. Beide lassen sich direkt aus dem Mittelgang öffnen.

## Materialien

Bruchstein, Bruchtiefenschiefer, Erde, Sand, Kies, Eichenstämme, Fichtenstämme, Eichenbretter, Kohle, Eisenbarren, Redstone, Knochenmehl, Stein, Glas, Kupferbarren, Goldbarren, Diamanten, Smaragde, Lapislazuli, Netherquarz, Netherrack, Verrottetes Fleisch, Schwarzpulver und Fäden.

## Benutzen

Items in die erhöhte Einwurf-Doppelkiste an der Rückwand legen; zum Öffnen nach oben schauen. Die Sortierung läuft automatisch. Items direkt aus den beschrifteten Lagerkisten entnehmen.

Andere Materialien und überschüssige Mengen landen in der Restekiste rechts unter dem Einwurf. Es wird nichts entsorgt. Sind Lager und Restekiste voll, staut sich der Transport sicher in den Trichtern und der Einwurfkiste. Nach dem Leeren geht es automatisch weiter. Die gemeinsame Trichterlinie transportiert höchstens 2,5 Items pro Sekunde.

## Survival: Filter einmal einrichten

Im Kreativbau sind die Filter bereits befüllt. Im Survival-Modus kopiert der Begleiter keine Inventare. Vor Benutzung deshalb für jedes Fach:

1. Über die Dachluke zur Technik hinabsteigen; Gerüst oder vorübergehende Trittblöcke für den Rückweg mitnehmen. Den Transporttrichter und den noch leeren Filtertrichter darunter vorübergehend entfernen. Ihre Ausrichtungen merken.
2. **1 Zielitem** in den unteren, verriegelten Entnahmetrichter legen. Dieser sitzt hinter der oberen Lagerkiste. Den Filtertrichter darüber wieder in seiner ursprünglichen Ausrichtung einsetzen.
3. In den ersten Slot des Filtertrichters **1 Zielitem**, in die vier übrigen Slots **je 11 benannte Bruchsteine** legen. Am Amboss einen eindeutigen Namen wie „Filter – nicht entnehmen“ verwenden. Diese benannten Füllsteine nicht in den Einwurf geben.
4. Den Transporttrichter mit seiner ursprünglichen Ausrichtung wieder einsetzen und die Luke schließen.

Insgesamt: **48 Zielitems plus 1.056 benannte Bruchsteine** als Betriebsreserve. Optional je ein weiteres Zielitem für die 24 Anzeigerahmen. Den zusätzlichen Verteilungstrichter hinter der unteren Lagerkiste leer lassen.

134 Trichter werden verbaut; allein dafür sind 670 Eisenbarren und 134 Kisten nötig. Dazu kommen die eigentlichen Lagerkisten, 24 Komparatoren, 24 Verstärker, 24 Redstonefackeln und Baumaterialien. Keine Shulkerkisten, Taktgeber oder Itemaufzüge. Vollständige Blockzahlen: `python tools/build_survival_storage.py`, Ausgabe in `build/survival-storage/compact.json`.

## Erweitern

Ein Fach belegt zwei Blöcke entlang der Raumlänge. Eine vollständige Sektion einschließlich beider Kisten, Verbindungstrichter, Filter und eigener Schaltung kopieren. Raum und Transportlinie verlängern, die Restekiste ans Ende setzen und die neuen Filter sowie Beschriftungen anpassen. Die Redstoneleitungen benachbarter Sektionen getrennt halten.

## Testwelt

`Schematic-Schauwelt-Survival24-Kompakt` ist eine separate flache Testwelt nur mit dem erweiterten Lager. Die kleinere Zwölf-Sorten-Version bleibt als eigene Schematic und in ihrer bisherigen Testwelt erhalten.

Teleport vor den Eingang: `/tp @s 160 -58 135`. Einwurf: `159 -54 172`; Restekiste: `161 -57 172`.

Die Betriebsprüfung verwendet die echte Redstone- und Trichterlogik: alle 24 Sorten, Öffnen beider Kisten, Befüllen der oberen bei voller unterer Kiste und umgekehrt, beide Kisten voll, unbekannte und nicht stapelbare Items, volle Restekiste und Wiederanlauf. Itemmengen und Filterfüllungen werden kontrolliert.
