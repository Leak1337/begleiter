# Zentrallager 128 mit Sortierung und Itemabruf

Minecraft Java 26.3. Die Anlage arbeitet mit Vanilla-Redstone, Trichtern, Wasser und Shulkerboxen.
Sie benötigt keine Befehlsblöcke und kein laufendes Inventar-Skript.

## Bedienung

1. **Lose Items:** die untere Eingangstreppe benutzen und das Eingabefass befüllen.
2. **Gefüllte oder leere Shulkerboxen:** die obere Eingangstreppe benutzen. Die Anlage entlädt Boxen automatisch.
3. **Abrufen:** den beschrifteten Materialknopf in der Galerie drücken. Ein Abruf liefert die vorhandene Box,
   bei kleinen Beständen auch eine teilweise gefüllte Box. Wiederholte Leerabrufe geben keine Leerbox aus.
4. **Zentrale Ausgabe:** Hebel AUS entlädt die bestellten Boxen in die Ausgabedoppelkiste.
   Hebel EIN hält ganze Boxen in der oberen Ausgabekiste bereit. Nach dem Ausschalten läuft die Entladung weiter.
5. **Leerboxen:** automatisch entleerte Boxen laufen zurück zu den Beladern.
   Mitgenommene Boxen nach Gebrauch über den Boxeneingang zurückgeben.
6. **Eingangslampe:** Items ohne passenden Filter oder bei überfülltem Fach gelangen ins Restefass am Kanalende.
   Der Eingang pausiert, solange dort Items liegen. Restefass leeren, dann läuft die Anlage weiter.

Es sind genau die unten aufgelisteten 128 Materialtypen konfiguriert. Umbenannte Items, andere Datenkomponenten
und nicht konfigurierte Items sind keine passenden Filtertreffer. Der Abruf bestellt Boxen; er dosiert keine
exakte Wunschmenge wie „64 Eisen“.

## Aufstellung und Betriebsfüllung

Ein freier, flacher Bauplatz ist nötig. Der gesamte Bau muss während des Betriebs geladen bleiben.
Die Vorlage ist für Creative vollständig vorgefüllt. Im Survival-Modus setzt der Begleiter die Betriebsfüllung
nicht kostenlos ein: beide Filter je Material, Filterfüller und der Leerboxkreislauf müssen manuell gefüllt werden.
Die Anlage ist groß und materialintensiv; sie ist kein günstiges Anfangslager.

Jedes Material verwendet zwei Filter mit insgesamt 84 reservierten Materialitems. Dazu kommen 1.024 benannte
Papier-Filterfüller. Die Creative-Vorlage enthält 13.642 leere Boxen als Betriebsbestand in Beladern und
Transportpuffern sowie 128 bereits aufgestellte Ladeboxen. Die Reserven nicht als normale Lagerbeute entnehmen.
Eine Ladebox fasst bis zu 27 Stacks. Weitere Items warten im Fass und in Transportpuffern.

Der gemeinsame Eingang wird durch einen Trichter dosiert: nominell höchstens 2,5 Items/s bei 20 TPS.
Die tatsächliche Rate und Abrufzeit hängen vom Takt, Transportweg und der Serverleistung ab.
Dies ist ein vollständig geprüftes Abruflager, kein Versprechen eines 16-fach-Hochgeschwindigkeitssortierers.

## Funktionsnachweis

Alle 128 Materialtypen wurden nach dem tatsächlichen Bot-Import einsortiert und zentral wieder ausgegeben.
Zusätzlich geprüft: beide Ausgabemodi, gefüllte und leere Eingangsboxen, Wiederanlauf nach dem Leeren des
Restefasses, Leerabrufe, automatisch ersetzte Ladeboxen und erhaltene Itemrahmen.
Die Abschlussbilanz prüft jedes Material, die unveränderten Filterreserven und sämtliche Leerboxen;
es dürfen keine losen Item-Entities übrig bleiben. Die Tests verschieben keine Items zwischen Lagerzellen.
Sie simulieren lediglich das Befüllen des Eingangs, Knopf-/Hebelbetätigung und die Abholung an der Ausgabe.

- 0 Grad, vier Zellen, endgültige Beschriftung: bestanden.
- 90 Grad, alle 128 Materialien: bestanden.
- 180 Grad, vier Zellen: bestanden.
- 270 Grad, vier Zellen: bestanden.

Ausgelieferte Schematic-SHA-256: `8d75213e2bb87defb5c96ce4fd04362320193e2f7d77410f7edc82ed643126c5`.

Der vollständige 128er-Funktionslauf verwendet den Technikstand `0e8a91a367f224bd3cdb45dfffe580e4ff2115763abc18f4474b9ddc493f0d1c`. Danach wurden ausschließlich die 128 Hinweisschilder und ihre nicht benötigten Träger versetzt, damit die Itemrahmen frei sichtbar bleiben. Ein Vergleich aller Blöcke und Inventare bestätigt unveränderte Betriebstechnik; der endgültige kleine Aufbau wurde nochmals vollständig geprüft.

Nachbau: `python tools/build_request_storage.py` oder Gradle-Task `generateRequestStorage`.
Die Materialbeschriftungen sind in `tools/request_storage_materials.json` gespeichert.

Der Boxentlader basiert auf [BU003 von misterkartoffel und sergyd](https://storagecatalog.org/archives/BU003-Small-1x-Box-Unloader/).
Sortierung, Abruf, Leitungen, Eingangstakt, Rückführung und Gesamtaufbau sind für diese Vorlage zusammengesetzt.

## Materialbelegung

Die Galerie hat acht Spalten. Die Nummern entsprechen den Beschriftungen an den Knöpfen.

| Nummer | Material | Reihe | Spalte |
| --- | --- | --- | --- |
| 001 | Stein | 1 | 1 |
| 002 | Bruchstein | 1 | 2 |
| 003 | Glatter Stein | 1 | 3 |
| 004 | Tiefenschiefer | 1 | 4 |
| 005 | Bruchtiefenschiefer | 1 | 5 |
| 006 | Polierter Tiefenschiefer | 1 | 6 |
| 007 | Tuffstein | 1 | 7 |
| 008 | Kalzit | 1 | 8 |
| 009 | Granit | 2 | 1 |
| 010 | Polierter Granit | 2 | 2 |
| 011 | Diorit | 2 | 3 |
| 012 | Polierter Diorit | 2 | 4 |
| 013 | Andesit | 2 | 5 |
| 014 | Polierter Andesit | 2 | 6 |
| 015 | Erde | 2 | 7 |
| 016 | Grasblock | 2 | 8 |
| 017 | Grobe Erde | 3 | 1 |
| 018 | Wurzelerde | 3 | 2 |
| 019 | Schlamm | 3 | 3 |
| 020 | Ton | 3 | 4 |
| 021 | Sand | 3 | 5 |
| 022 | Roter Sand | 3 | 6 |
| 023 | Kies | 3 | 7 |
| 024 | Sandstein | 3 | 8 |
| 025 | Roter Sandstein | 4 | 1 |
| 026 | Netherrack | 4 | 2 |
| 027 | Basalt | 4 | 3 |
| 028 | Schwarzstein | 4 | 4 |
| 029 | Endstein | 4 | 5 |
| 030 | Obsidian | 4 | 6 |
| 031 | Weinender Obsidian | 4 | 7 |
| 032 | Glas | 4 | 8 |
| 033 | Eichenstamm | 5 | 1 |
| 034 | Fichtenstamm | 5 | 2 |
| 035 | Birkenstamm | 5 | 3 |
| 036 | Tropenbaumstamm | 5 | 4 |
| 037 | Akazienstamm | 5 | 5 |
| 038 | Schwarzeichenstamm | 5 | 6 |
| 039 | Mangrovenstamm | 5 | 7 |
| 040 | Kirschstamm | 5 | 8 |
| 041 | Blasseichenstamm | 6 | 1 |
| 042 | Bambusblock | 6 | 2 |
| 043 | Karmesinstiel | 6 | 3 |
| 044 | Wirrstiel | 6 | 4 |
| 045 | Eichenholzbretter | 6 | 5 |
| 046 | Fichtenholzbretter | 6 | 6 |
| 047 | Birkenholzbretter | 6 | 7 |
| 048 | Tropenholzbretter | 6 | 8 |
| 049 | Akazienholzbretter | 7 | 1 |
| 050 | Schwarzeichenholzbretter | 7 | 2 |
| 051 | Mangrovenholzbretter | 7 | 3 |
| 052 | Kirschholzbretter | 7 | 4 |
| 053 | Blasseichenholzbretter | 7 | 5 |
| 054 | Bambusbretter | 7 | 6 |
| 055 | Karmesinbretter | 7 | 7 |
| 056 | Wirrbretter | 7 | 8 |
| 057 | Eisenbarren | 8 | 1 |
| 058 | Goldbarren | 8 | 2 |
| 059 | Kupferbarren | 8 | 3 |
| 060 | Roheisen | 8 | 4 |
| 061 | Rohgold | 8 | 5 |
| 062 | Rohkupfer | 8 | 6 |
| 063 | Kohle | 8 | 7 |
| 064 | Holzkohle | 8 | 8 |
| 065 | Diamant | 9 | 1 |
| 066 | Smaragd | 9 | 2 |
| 067 | Lapislazuli | 9 | 3 |
| 068 | Redstone-Staub | 9 | 4 |
| 069 | Netherquarz | 9 | 5 |
| 070 | Amethystscherbe | 9 | 6 |
| 071 | Netheritplatten | 9 | 7 |
| 072 | Netheritbarren | 9 | 8 |
| 073 | Redstone-Fackel | 10 | 1 |
| 074 | Redstone-Block | 10 | 2 |
| 075 | Redstone-Verstärker | 10 | 3 |
| 076 | Redstone-Komparator | 10 | 4 |
| 077 | Beobachter | 10 | 5 |
| 078 | Kolben | 10 | 6 |
| 079 | Klebriger Kolben | 10 | 7 |
| 080 | Trichter | 10 | 8 |
| 081 | Spender | 11 | 1 |
| 082 | Werfer | 11 | 2 |
| 083 | Hebel | 11 | 3 |
| 084 | Fackel | 11 | 4 |
| 085 | Schiene | 11 | 5 |
| 086 | Antriebsschiene | 11 | 6 |
| 087 | Zielblock | 11 | 7 |
| 088 | Werker | 11 | 8 |
| 089 | Weizenpflanze | 12 | 1 |
| 090 | Weizenkörner | 12 | 2 |
| 091 | Rote Bete | 12 | 3 |
| 092 | Rote-Bete-Samen | 12 | 4 |
| 093 | Karotte | 12 | 5 |
| 094 | Kartoffel | 12 | 6 |
| 095 | Zuckerrohr | 12 | 7 |
| 096 | Bambus | 12 | 8 |
| 097 | Kaktus | 13 | 1 |
| 098 | Seetang | 13 | 2 |
| 099 | Getrockneter Seetang | 13 | 3 |
| 100 | Knochenmehl | 13 | 4 |
| 101 | Knochen | 13 | 5 |
| 102 | Schwarzpulver | 13 | 6 |
| 103 | Faden | 13 | 7 |
| 104 | Verrottetes Fleisch | 13 | 8 |
| 105 | Pfeil | 14 | 1 |
| 106 | Feuerstein | 14 | 2 |
| 107 | Leder | 14 | 3 |
| 108 | Papier | 14 | 4 |
| 109 | Buch | 14 | 5 |
| 110 | Schleimball | 14 | 6 |
| 111 | Honigwabe | 14 | 7 |
| 112 | Leuchtsteinstaub | 14 | 8 |
| 113 | Weiße Wolle | 15 | 1 |
| 114 | Orange Wolle | 15 | 2 |
| 115 | Magenta Wolle | 15 | 3 |
| 116 | Hellblaue Wolle | 15 | 4 |
| 117 | Gelbe Wolle | 15 | 5 |
| 118 | Hellgrüne Wolle | 15 | 6 |
| 119 | Rosa Wolle | 15 | 7 |
| 120 | Graue Wolle | 15 | 8 |
| 121 | Hellgraue Wolle | 16 | 1 |
| 122 | Türkise Wolle | 16 | 2 |
| 123 | Violette Wolle | 16 | 3 |
| 124 | Blaue Wolle | 16 | 4 |
| 125 | Braune Wolle | 16 | 5 |
| 126 | Grüne Wolle | 16 | 6 |
| 127 | Rote Wolle | 16 | 7 |
| 128 | Schwarze Wolle | 16 | 8 |

## Ausgelieferte Schauwelt

`Schematic-Schauwelt-Lager128` enthält alle 38 Anlagen. Der neue Bauplatz ist über `/tp @s 416 -58 1604` erreichbar; der Weltstart liegt bereits davor.

Die zentrale Itemausgabe steht bei `420 -57 1639`, der Ausgabehebel bei `420 -55 1635`. Alle Materialknöpfe stehen in `docs/zentrallager-128-koordinaten.md` und in der Weltdatei `ZENTRALLAGER-128.md`.

Abschließende Prüfungen: 322 Unit-Tests, 14 Import-GameTests, Paketprüfung und die acht bisherigen GUT-Betriebstests bestanden. Der Weltexport meldet bei allen 38 Bauten keine statischen Platzierungsfehler und keine fehlenden geplanten Entities.
