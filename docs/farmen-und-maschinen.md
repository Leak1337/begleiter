# Farmen und Maschinen für normale Welten

Die Bauzentrale enthält **24 eingebaute Farmen und Maschinen** sowie die klassische Weizenfarm in zwei Größen.
Es werden keine zusätzlichen Schematic-Dateien benötigt.

## Farm auswählen und in der Welt platzieren

1. **Shift + Rechtsklick** auf den eigenen Begleiter → **Bauen**.
2. Einen eingebauten Bauplan anklicken. Mit dem Pfeil lässt sich der Katalog weiterblättern.
3. Das Menü schließt sich; eine durchscheinende Blockvorschau folgt dem anvisierten Bodenblock.
4. **R** dreht um 90°. **Bild auf / Bild ab** verändert die Höhe (−6 bis +16 Blöcke).
5. **Enter** bestätigt den Standort und startet den normalen Bauauftrag. **Rücktaste** bricht die Vorschau ab.

Die Tasten können unter Minecraft → Steuerung → Bauplatzierung geändert werden; die Anzeige nennt die
aktuell zugewiesenen Tasten. Der Rahmen markiert die Grundfläche. Rote Blöcke zeigen Hindernisse an.
Die unterste Schicht ersetzt den gewählten Boden, die Ausrichtung aus dem Menü gilt als Anfangsdrehung.
Die Auswahl ist fünf Minuten gültig. Zur Bestätigung muss der Besitzer beim Begleiter bleiben
(weniger als zwölf Blöcke Abstand); der anvisierte Standort darf bis zu 32 Blöcke entfernt sein.

Vor der Bestätigung wird nichts gebaut oder aus dem Inventar entnommen. Ein laufender Auftrag bleibt bei
Auswahl, Abbruch oder einer abgelehnten Platzierung erhalten. Der Server prüft Besitz, Standort, Bauhöhe,
Weltgrenze und geladene Chunks erneut. Kisten, andere Block-Entities, Gebäude und vorhandene Flüssigkeiten
blockieren den Platz. Natürliches Gelände innerhalb des Bauvolumens wird geräumt.
Das Fundament füllt freie Stellen bis zu sechs Blöcke tief auf. Die Vorschau zeigt den eigentlichen Bauplan;
die zusätzlichen Fundamentblöcke werden bei der Bestätigung anhand des Geländes berechnet.

Im Survival verbraucht der Begleiter die Materialien aus seinem Inventar oder beschafft sie über die normalen
Bauaufgaben. Im Kreativmodus gelten die vorhandenen Kreativ-Bauregeln. Schematic-Dateien aus dem
Bau-Menü bekommen dieselbe Vorschau (bis 100.000 Blöcke; größere und Chat-/Befehlsaufträge starten wie
bisher direkt vor dem Spieler).

## Bewährte Farmen (im Spiel getestet)

Hier landen Farmen, die sich im echten Spiel als gut erwiesen haben – eingebaut oder als Schematic-Datei.

| Farm | Quelle | Bewertung | Hinweise |
| --- | --- | --- | --- |
| Fast & Efficient Bonemeal Farm | `run/schematics/efficient-bonemeal-farm.litematic` (Design BasicRedstone, Nachbau ThC0mmand), 13×7×12 | **Richtig gut** (Spielertest 2026-11-04) | Moos + Werfer mit Knochenmehl, Crafter macht Knochenmehl. Der Crafter vorne braucht alle 9 Slots gesperrt – übernimmt der Begleiter aus der Datei. Werfer selbst mit Knochenmehl füllen (im Kreativ-Bau kommt der Inhalt aus der Datei mit). Survival: 8 Quarz, 1 Lohenrute und 1 Lavaeimer selbst mitbringen. |

## Eingebaute Baupläne

| Bauplan-ID | Anlage | Bedienung |
| --- | --- | --- |
| `zuckerrohrfarm` | Vier Zuckerrohrpflanzen, Beobachter, Kolben, Wasser-Sammelrinne und Kiste | Wächst und erntet automatisch, solange die Umgebung geladen ist. Kiste vorne links in der Vertiefung leeren. Ein Teil der Drops kann auf den Grundpflanzen liegen bleiben. |
| `bambusfarm` | Vier Bambuspflanzen mit derselben Ernte- und Sammeltechnik | Automatischer Betrieb bei geladenen Chunks. Kiste vorne links; auf den Grundpflanzen liegende Drops gelegentlich einsammeln. |
| `wasserernte` | Beleuchtetes 5×5-Weizenfeld, getrennte Bewässerung, Wasserwerfer, Sammeltrichter und Kiste | **Einen Wassereimer in den Werfer legen.** Bei reifem Weizen den Knopf hinten drücken. Nach der Ernte nochmals drücken, damit der Werfer das Wasser zurückholt. Anschließend neu säen oder den Begleiter mit der Feldpflege beauftragen. |
| `schmelzofen` | Ofen mit getrennten Kisten für Schmelzgut, Brennstoff und fertige Produkte | Schmelzgut oben einfüllen, Brennstoff in die linke Kiste. Die vordere Kiste enthält die Ausgabe. Ihre Lampe zeigt an, ob Produkte bereitliegen. |
| `komposter` | Komposter mit automatischer Zufuhr und Knochenmehl-Ausgabe | Kompostierbare Pflanzenreste oben einfüllen. Knochenmehl landet rechts. Die Lampe zeigt den Komposter-Füllstand an. Nicht kompostierbare Gegenstände müssen aus der Zufuhr entfernt werden. |
| `karottenfarm` | 5×5-Karottenfeld mit Wasserernte | Wie `wasserernte`, anschließend mit Karotten nachpflanzen. |
| `kartoffelfarm` | 5×5-Kartoffelfeld mit Wasserernte | Wie `wasserernte`, anschließend Kartoffeln nachpflanzen. |
| `kaktusfarm` | Neun Kakteen mit Zaun-Ernte und Sammelrinne | Wächst selbst und sammelt vorne links. Ein Teil der Drops kann am Kaktus verloren gehen. |
| `raeucherofen` | Automatischer Räucherofen | Rohe Nahrung oben, Brennstoff links, gegarte Nahrung vorne. |
| `hochofen` | Automatischer Hochofen | Erze oder geeignete Metallteile oben, Brennstoff links, Ausgabe vorne. |
| `schaltlager` | Schaltbares Trichterlager | Eingabe oben, Ausgabe rechts. Hebel an: gesperrt. Hebel aus: Transport frei. Beginnt gesperrt. |
| `portionierer` | Vorratskiste mit Dropper und Knopf | Vorrat oben einfüllen. Jeder Knopfdruck fördert genau ein Item in die rechte Kiste. |
| `itemsortierer` | Trichter-Filter mit Komparator, Umkehrung über Subtraktions-Komparator | Filter mit 18 Zielitems und 4 Einzelstücken einrichten. Gemischtes oben einfüllen: Zielitems unten vorne, Rest rechts. |
| `bruchsteingenerator` | Lava-Rinne trifft Wasser, Wasser fällt in ein Loch | Mittleren Block abbauen, er bildet sich sofort neu. Kein Obsidian. |
| `lichtschalter` | Zwei Knöpfe auf eine gewachste Kupferbirne, Komparator auf zwei Lampen | Flurlicht von beiden Enden umschalten. |
| `tresorschloss` | Zwei Fackel-Inverter und ein direkter Eingang auf eine NOR-Fackel vor der Eisentür | Öffnet nur bei Hebel 1 an, 2 aus, 3 an. |
| `ruebenfarm` | Zwei erhöhte Rübenbeete mit Mittelgang | Von Hand ernten und Rübensamen nachsäen. Abgedeckte Bewässerung zwischen Beeten und Gang. |
| `kuerbisfarm` | Offener Kürbisgarten mit sechs bewässerten Stielen | Nur Früchte ernten; Stiele stehen lassen. Keine Wasserernte und kein Neusäen nach jeder Frucht. |
| `melonenfarm` | Schmales Gewächshaus mit zwei Stielreihen und zentralem Erntegang | Melonen von Hand ernten. Wartungsleiter hinten verbindet den Innenraum mit dem Dach. |
| `kuhfarm` | Kuhstall mit großem Auslauf und zwei überdachten Futterständen | Kühe mit Weizen hineinführen, manuell züchten und mit Eimern melken. |
| `schaffarm` | Offene Grasweide mit schmalem Schergang | Schafe mit Weizen hineinführen. Gras ermöglicht Wollwachstum; Schafe im Schergang abtrennen und mit Schere scheren. |
| `schweinefarm` | Vier abgetrennte Zuchtbuchten mit Verbindungstoren | Schweine mit Karotten hineinführen, Gruppen trennen und manuell füttern. |
| `kaninchenfarm` | Zwei geschlossene Schlafboxen mit hohem, eingezäuntem Auslauf | Kaninchen mit Karotten hineinführen. Zum Betreten beide übereinanderliegenden Tore öffnen und wieder schließen. |
| `huehnerfarm` | Geschlossener Hühnerstall mit Teppichboden über Sammeltrichtern | Hühner mit Samen hineinführen. Gelegte Eier landen automatisch in der Kiste vorne links; Zucht bleibt manuell. |

Die Tieranlagen erzeugen keine Tiere und keine kostenlosen Vorräte. Grasblöcke werden erhalten, wenn am
Bauplatz bereits Rasen liegt; fehlende Grasblöcke müssen als Material vorhanden sein. Die Entwürfe erfüllen
unterschiedliche Aufgaben: Milch/Futterstände, nachwachsende Wolle, getrennte Zuchtgruppen, gesicherte
Kleintierhaltung und automatische Eiersammlung. Es sind keine automatischen Schlachtanlagen.

Die automatische Wasserernte ersetzt bei Weizen, Karotten und Kartoffeln die Ernte, nicht das Nachsäen. Die beiden hohen Pflanzenfarmen
lassen ihren Grundstock stehen und wachsen ohne erneutes Pflanzen weiter. Die Anlagen verwenden normale
Minecraft-Blöcke; nach dem Bau brauchen sie keine dauernden Begleiter-Aufträge für ihre Redstone-Funktion.

## Befehle

```text
/begleiter bauplaene
/begleiter bauplan zuckerrohrfarm
/begleiter bauplan bambusfarm
/begleiter bauplan wasserernte
/begleiter bauplan schmelzofen
/begleiter bauplan komposter
/begleiter bauplan karottenfarm
/begleiter bauplan kartoffelfarm
/begleiter bauplan kaktusfarm
/begleiter bauplan raeucherofen
/begleiter bauplan hochofen
/begleiter bauplan schaltlager
/begleiter bauplan portionierer
```

Englische Befehle: `/companion schematics` und `/companion schematic <name>`.
Auch die englischen IDs `sugar_cane_farm`, `bamboo_farm`, `wheat_flush_farm`, `auto_smelter` und `auto_composter`
werden erkannt. Im Chat zum Beispiel: `@ Bau eine Bambusfarm`.

Auf einem freien, möglichst ebenen Platz bauen. Im gesamten Bauvolumen werden vorgesehene Freiräume geräumt;
die unterste Schicht ersetzt den Boden. Das Fundament gleicht bis zu sechs Blöcke Höhenunterschied aus.

## Ausstellung und größere Redstone-Ecke

Die vorhandene Testwelt wird beim nächsten Testmap-Start einmalig erweitert: **32×32 Blöcke Arbeitsfläche**
und daneben **26 beschriftete Ausstellungsparzellen**. Dort stehen alle 24 eingebauten Anlagen sowie
beide Größen der klassischen Weizenfarm. Wege verbinden die Parzellen. Die Ausstellung liegt außerhalb
der zurückgesetzten Teststation und bleibt bei laufenden Szenarien erhalten.

```powershell
.\gradlew.bat runTestmap -PtestmapView=ausstellung
```

```text
/companion testmap ausstellung
/companion testmap redstone
```

Die Ausstellung enthält Testvorräte, befüllte Wasserwerfer und reife Feldfrüchte zum Ausprobieren.
Nur diese Vorführanlagen erhalten kostenlose Vorräte; in normalen Welten gelten Materialkosten und
manuelles Befüllen weiterhin. Neue Baupläne erscheinen automatisch auch in der Ausstellung.

## Vorhandene Test-Umgebung

Die Gruppe `farmen` baut und prüft die unterschiedlichen Produktionspläne auf der vorhandenen Redstone-Testplatte:

```powershell
.\gradlew.bat runTestmap -PtestmapRun=farmen
```

In einer bereits geöffneten Testmap:

```text
/companion testmap szenario farmen
/companion testmap szenario farm_zuckerrohrfarm,farm_bambusfarm
/companion testmap szenario farm_wasserernte
```

Mit `farmen_alle` werden alle 24 Anlagen einschließlich der älteren Varianten gebaut.
Die Gruppe `redstone` enthält die Farmen ebenfalls. Die Prüfläufe verwenden die echten Baupläne aus dem Menü,
ein Survival-Material-Kit und die normale Bauausführung. Die Tests simulieren anschließend Pflanzenwachstum,
Knopfbetätigung oder das Befüllen der Eingabekisten. Ernte, Wasserströmung, Kolben, Trichter, Schmelzen und
Kompostieren führt Minecraft selbst aus. Es werden keine Produkte in die Ausgabekisten eingefügt.

Server-GameTests:

```powershell
.\gradlew.bat runGameTest '-PtestFilter=begleiter-gametest:machine_blueprint_game_tests*'
```

Prüfungen: zwei Ernten mit erhaltenem Pflanzen-Grundstock, Ernte in der Sammelkiste, Wasser zurückholen und
Feldfrüchte nachpflanzen, Kakteen ernten, echte Eisenbarren und gegarte Nahrung, Knochenmehl aus Kompost,
Trichtersperre, einzelne Dropper-Portionen, Menü-/Befehlszugang,
Materialerhalt beim Drehen und Erhalt vorhandener Container bei abgelehnten Bauaufträgen.


## Prüfungen für die neuen Farmen und die Platzierung

```powershell
.\gradlew.bat runGameTest '-PtestFilter=begleiter-gametest:farm_placement_game_tests*'
.\gradlew.bat runClientGameTest
```

Die Serverprüfungen bauen jede neue Anlage mit Survival-Materialien. Sie prüfen außerdem echtes
Kürbis-/Melonenwachstum, Rüben nach den Wasser-Updates, den Weg eines Eis durch Teppich und Trichter
in die Kiste, Drehung und Materialmengen sowie den Erhalt vorhandener Aufträge und Kisten bei
abgewiesenen Platzierungen. Der Clienttest bedient das Bot-Menü, dreht und hebt die echte Weltvorschau,
bestätigt den Bau und bricht eine zweite Auswahl ab. Bildschirmfotos landen bei den Client-Testausgaben.
