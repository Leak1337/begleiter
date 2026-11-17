# Kompakter Survival-Kistenraum

Die größere Nachfolgeversion mit 24 Sorten und jeweils zwei Doppelkisten ist in [survival-kistenraum-24.md](survival-kistenraum-24.md) beschrieben. Der aktuelle Generator erzeugt diese erweiterte Version; die hier beschriebene kleine Schematic bleibt im Katalog erhalten.

17 × 16 Blöcke Grundfläche, 8 Blöcke hoch einschließlich Fundament und verdeckter Technik. Zwölf beschriftete Doppelkisten bieten je 54 Stacks Platz, insgesamt 41.472 Items bei 64er-Stacks. Der Raum besteht überwiegend aus Fichte und Stein, mit Laternen, Mittelgang und zugänglichen Kisten.

## Benutzung

Items in die erhöhte Doppelkiste an der Rückwand legen. Die Trichter sortieren automatisch Bruchstein, Bruchtiefenschiefer, Erde, Sand, Kies, Eichenstämme, Fichtenstämme, Eichenbretter, Kohle, Eisenbarren, Redstone und Knochenmehl ein. Entnehmen direkt aus den beschrifteten Kisten.

Andere Items und überschüssige Mengen landen in der Restekiste rechts unter dem Einwurf. Nichts wird verbrannt oder ausgeworfen. Ist auch diese voll, staut sich der Transport in den Trichtern und der Einwurfkiste. Nach dem Leeren läuft er automatisch weiter. Ein voller großer Einwurf braucht Zeit: Die Transportlinie schafft höchstens 2,5 Items pro Sekunde.

## Einmaliges Einrichten im Survival-Modus

Im Kreativmodus enthält die Schematic bereits die Filterbelegung. Beim Survival-Bau kopiert der Begleiter keine Kisteninhalte. Deshalb vor der ersten Benutzung jedes der zwölf Fächer einrichten:

1. Dachluke über dem Filter öffnen. Den Transporttrichter direkt darunter und den noch leeren Filtertrichter vorübergehend abbauen; ihre Ausrichtungen merken.
2. In den jetzt zugänglichen unteren Entnahmetrichter **1 Zielitem** legen. Keine anderen Slots belegen. Den Filtertrichter darüber in seiner ursprünglichen Ausrichtung wieder einsetzen.
3. In dessen ersten Slot **1 Item des gewünschten Materials** legen. Die anderen vier Slots mit **je 11 benannten Bruchsteinen** belegen. Die Bruchsteine vorher am Amboss eindeutig benennen, beispielsweise „Filter – nicht entnehmen“. Diese benannten Steine nicht in den Einwurf geben.
4. Transporttrichter mit seiner ursprünglichen Ausrichtung wieder einsetzen und Dachluke schließen.

Insgesamt werden 24 Zielitems und 528 benannte Bruchsteine als Betriebsreserve benötigt. Die zwölf Rahmen können zusätzlich je ein passendes Anzeigeitem aufnehmen. Die Reserven bleiben in der Technik; sie zählen nicht zur Lagerkapazität.

Der Bau verwendet 62 Trichter (310 Eisenbarren allein für deren Herstellung), zwölf Komparatoren, zwölf Verstärker und zwölf Redstonefackeln. Weitere Materialien und die Herstellung der Trichterkisten kommen hinzu. Keine Shulkerkisten, Taktgeber oder Itemaufzüge nötig. Die vollständigen Blockzahlen erzeugt `python tools/build_survival_storage.py` in `build/survival-storage/compact.json`.

## Erweitern

Ein Fach ist zwei Blöcke breit. Für zusätzliche Sorten eine vollständige Zweiersektion mit Kiste, Filter und eigener Schaltung kopieren, den Raum verlängern und die Transportlinie entsprechend weiterführen. Die Filter und Redstoneleitungen benachbarter Sektionen müssen weiterhin getrennt bleiben. Neue Zielitems und Beschriftungen passend einrichten.

Zusätzliche Lagerkisten lassen sich über Trichter anschließen; dabei Filterbelegung und Verriegelung der unteren Filtertrichter beibehalten. Die Restekiste muss am Ende der gemeinsamen Transportlinie bleiben.

## Prüfung und Testwelt

Mit dem echten Begleiter-Importer in allen vier Drehrichtungen getestet: alle zwölf Sorten, nicht stapelbare Items, volle Sortierkiste, volle Restekiste und Wiederanlauf nach dem Leeren. Die Tests prüfen die Itemmengen und dass keine losen Items entstehen. Kistendeckel und Rahmen wurden geprüft; Innenraum und Außenansicht zusätzlich im Spiel gerendert.

Die Testwelt `Schematic-Schauwelt-Survival12` enthält den Raum zusammen mit den bisherigen Schematics. Sie startet beim neuen Lager. Im Kreativmodus ist dessen Filterbelegung bereits fertig.

Teleport vor den Eingang: `/tp @s 672 -58 1677`. Der Einwurf liegt bei `671 -55 1702`, die Restekiste bei `673 -58 1702`.
