# Baritone und Begleiter: Vergleich vom 02.11.2026

Verglichen wurde Baritone-Revision `c55a9c5d9a333c9f0336179a96c13a178fcbc2d4`
mit unserem bestehenden Heap und Knotenspeicher. Die Entscheidung gilt für diese
Bausteine und Testdaten, nicht als allgemeines Urteil über beide Projekte.

## Gemessene Bausteine

JDK 25 unter Windows; je Fall zwölf Aufwärmläufe und elf Messpaare beim Heap,
zehn Aufwärmläufe und elf Messpaare beim Knotenspeicher. Die Reihenfolge wechselt
zwischen den Messpaaren, angegeben ist der Median. Dieselben echten Begleiter-
`Node`-Objekte und deterministischen Eingaben werden verwendet. Der Heap-Test
mischt Kostenverringerung, Entnahme und Wiederaufnahme bei 200.000 Durchläufen.
Es handelt sich um einen lokalen Mikrobenchmark, nicht um eine FPS-Messung.

| Fall | Unser Code | Baritone direkt angepasst | Entscheidung |
|---|---:|---:|---|
| Heap, 128 Knoten | 21,878 ms | 21,878 ms | Direkten Heap übernommen; kleine Warteschlangen gleich schnell |
| Heap, 2.048 Knoten | 34,421 ms | 31,717 ms | Direkten Heap übernommen; hier 7,9 % weniger Zeit |
| Heap, 32.768 Knoten | 61,892 ms | 56,846 ms | Direkten Heap übernommen; hier 8,2 % weniger Zeit |
| Knotenspeicher, lokal verteilt | 8,131 ms | 5,226 ms | Baritone hier schneller, aber kein genereller Ersatz |
| Knotenspeicher, dünn besetzter langer Korridor | 8,087 ms | 15,257 ms | Unser Speicher bleibt |

Baritones Zellenspeicher reserviert im Korridor 6.400.000 Referenzplätze für
50.000 Knoten. Unser Speicher legt nur vorhandene Knoten in einer Hash-Tabelle ab.
Eine allgemeine Übernahme wäre damit auf diesem Fall langsamer und deutlich
verschwenderischer. Bei der lokalen Verteilung gibt es 39.563 unterschiedliche
Knoten und 147.456 Referenzplätze in Baritones Zellen.

Eine zuerst ausprobierte generische Heap-Schnittstelle war im ersten Messlauf
26–50 % langsamer und wurde entfernt. Der Vergleich oben verwendet deshalb
zusätzlich die direkte Portierung von Baritones Heap, nicht diese Schnittstelle.
Die Prozentwerte verschiedener Läufe sind wegen Systemlast nicht unmittelbar
miteinander vergleichbar; es wird kein allgemeiner Geschwindigkeitsgewinn behauptet.

Im ersten direkten Messlauf lag Baritones Heap nur 1–3 % vorne; deshalb wurde er
zunächst nicht übernommen. Die wiederholte Messung oben zeigt einen stärkeren
Vorteil bei größeren Warteschlangen und keinen Nachteil bei kleinen. Übernommen
wird die direkte Anpassung ohne generische Schnittstelle. Das ist ein Vorteil
bei Heap-Operationen, kein belegter Gewinn von 8 % für die gesamte Wegsuche.

Nachmessen: `./gradlew --init-script tools/compare-pathing.gradle compareBaritone`
bzw. `.\gradlew.bat --init-script tools/compare-pathing.gradle compareBaritone`.
Der Treiber prüft außerdem, dass beide Heap-Implementierungen dieselben Knoten
entnehmen. Die Baritone-Vergleichsklassen sind nur Testwerkzeuge und werden nicht
in die Mod kompiliert.

## Verhalten auf unseren bestehenden Spielstrecken

Eine getrennte Quellkopie mit unserem bisherigen Heap wurde gegen die zunächst
eingebundene Baritone-Heap-Variante geprüft. Auf sechs Strecken sind Wegkosten,
Knotenanzahl und die für die Bewegung benötigten Spielticks identisch:

| Strecke | Knoten | Wegkosten, gerundet | Spielticks |
|---|---:|---:|---:|
| Flach, 40 Blöcke | 41 | 143 | 101 |
| Zehn Treppenstufen | 17 | 88 | 110 |
| Schlucht | 838 | 198 | 74 |
| Tunnel | 174 | 895 | 576 |
| Höhle | 45 | 185 | 177 |
| Parkour | 27 | 117 | 79 |

Die alte Variante bestand alle 20 Strecken-Tests, die erste Heap-Variante alle
321 damaligen Spieltests. Einzelne gemessene Suchzeiten dieser getrennten
Testläufe sind wegen unterschiedlicher Aufwärmung und Systemlast kein belastbarer
Geschwindigkeitsvergleich. Unser physikbasierter Mob-Parkour und unsere
Inventar-, Gefahren- und Rückwegprüfungen werden nicht durch Baritones
spielerbezogene Ausführung ersetzt.

## Übernommene Verbesserungen

- Direkt angepasster Heap mit zusammenhängendem Kostenarray; die ursprüngliche
  Implementierung bleibt ausschließlich als Vergleichsbasis unter `tools/benchmarks`.
- `BetterWorldBorder`: Baritones Grenz- und Platzierungsprüfung ist übernommen.
  Sie verhindert Suchschritte außerhalb der Weltgrenze und Gerüstblöcke direkt
  am ungeeigneten Rand. Quelle und Lizenz stehen in `THIRD_PARTY_NOTICES.md`.
- Wie bei Baritones `PathExecutor` wird auch eine bereits laufende, sicher
  abbrechbare Bewegung erneut gegen die Welt geprüft. Neu auftauchende Lava,
  ein fehlender Boden oder eine verschobene Weltgrenze können dadurch den Schritt
  abbrechen. Bereits erreichte Landepunkte werden erst regulär abgeschlossen,
  damit etwa das Aufnehmen des Wassereimers erhalten bleibt.
- Zusätzliche Regressionstests decken Weltgrenzen, Brücken am Grenzrand, neue Lava
  nach Bewegungsbeginn sowie Heap-Wachstum und Wiederaufnahme ab.

Nachweis: Der neue Lava-Test scheitert im bisherigen Code mit „Laufende Bewegung
läuft weiter in neue Lava“. Die angepasste Version besteht alle 60
Wegfindungstests, einschließlich dieses Tests und der Wassereimer-Landung.

Unser eigener Code bleibt MIT. Nur die ausdrücklich gekennzeichneten übernommenen
Dateien stehen unter LGPL-3.0-or-later.

## Fortsetzung: Suchcaches und identische Flüge

Baritone-Quelle weiterhin die oben festgehaltene Revision `c55a9c5`.
Aus [BlockStateInterface](https://github.com/cabaletta/baritone/blob/c55a9c5d9a333c9f0336179a96c13a178fcbc2d4/src/main/java/baritone/utils/BlockStateInterface.java)
ist die direkte Positionstabelle angepasst: 4.096 feste Plätze, Verdrängung bei
Kollision und Generationen für Invalidierung. Alle gepackten Koordinaten sind
gültig; auch ein unbekannter Chunk (`null`) kann ein Treffer sein.

Die Tabelle gilt nur während `PathSearch.step`, wird nach Suchende freigegeben
und erst bei mindestens 1.024 direkten Zustandsabfragen mit hoher Wiederholungsrate
angelegt (mehr als doppelt so viele Abfragen wie bereits bekannte Flag-Zellen).
Einmalige Flag-Abfragen durchlaufen nicht zusätzlich die Zustandstabelle.
Live-Abfragen umgehen den neuen Zustandscache. `clearCache` invalidiert auch den
zuletzt gelesenen Chunk, einschließlich eines negativen Chunk-Treffers.

Geeignete feste Blöcke verwenden zusätzlich eine eigene, auf 8.192 Einträge
begrenzte Abbaukostentabelle nach Baritones Suchcache-Idee. Leere und verbotene
Zellen nutzen weiterhin die direkten Prüfungen. Weltrevision, Weltgrenze und
Abbauregeln invalidieren die Ergebnisse. Rekursive Rieselblockkosten verwenden
keine Ergebnisse mit anderer Rekursionstiefe. Bei Sprüngen entfällt ausschließlich
eine exakt identische zweite Simulation am bereits geprüften Geschwindigkeitslimit.
Andere Flugbahnen, Fehlversuche, Risiko und Sicherheitsabstände werden vollständig
berechnet. Die Deadline wird weiterhin an jedem Knoten geprüft.

### Reproduzierbarer Vergleich

```powershell
.\gradlew.bat runGameTest '-PtestFilter=begleiter-gametest:nav_benchmarks*' -PnavBenchmark=true
```

`NavSearchComparison` misst vollständige Suchen einschließlich Kontext, Cache-
Allokation und Freigabe auf denselben sechs bestehenden Spielstrecken. Die
Referenz verwendet denselben Code mit ausgeschalteten neuen Suchcaches und
vollständiger erneuter Flugberechnung. Gemeinsame Änderungen wie das Überspringen
einer leeren Vorschau-Map sind in beiden Varianten enthalten. Pro Strecke zwölf
Aufwärmpaare und elf Messpaare mit wechselnder Reihenfolge; angegeben werden die
Mediane. Jedes Paar muss dieselben Positionen, Bewegungen, Kosten und erweiterten
Knoten liefern. Normale Testläufe aktivieren die Zeitmessung nicht.

Erste Versuche mit sofortiger Allokation erzeugten Zusatzaufwand auf kleinen
Strecken. Die endgültige Variante legt die Zustandstabelle daher bedarfsgesteuert
an und vermeidet doppelte Cache-Abfragen für Flags. Die Messung ist lokal unter
Windows/JDK 25; JVM-Aufwärmung und parallele Systemlast beeinflussen die Zeiten.
Es handelt sich um Suchrechenzeit, weder um FPS noch um schnelleres Laufen des Mobs.

### Messung des endgültigen Suchcodes

Vollständiger Prüflauf vom 02.11.2026:

| Strecke | Referenz | Optimiert | Zeitänderung | Welt-Blocklesungen vorher → nachher |
|---|---:|---:|---:|---:|
| Flach, 40 Blöcke | 0,494 ms | 0,499 ms | +1,1 % | 1.088 → 1.088 |
| Zehn Treppenstufen | 0,258 ms | 0,260 ms | +0,6 % | 691 → 691 |
| Schlucht | 3,014 ms | 2,970 ms | −1,5 % | 4.396 → 4.396 |
| Tunnel | 1,359 ms | 1,122 ms | −17,5 % | 4.701 → 2.143 |
| Höhle | 0,201 ms | 0,201 ms | 0,0 % | 1.066 → 1.066 |
| Parkour | 9,986 ms | 5,725 ms | −42,7 % | 154.156 → 3.031 |

Parkour vermeidet zusätzlich 235 identische Flugsimulationen; im Tunnel werden
2.541 vollständige Abbaukosten wiederverwendet. Auf diesen sechs Strecken sind
Positionen, Bewegungen, Kosten und Knotenzahlen in jedem Vergleichspaar identisch.
Kleine Zeitdifferenzen bei kurzen Wegen sind kein belegter praktischer Gewinn.
Ein zuvor gemessener Entwurf ohne Wiederholungsprüfung aktivierte den Zusatzcache
auch in der Schlucht und brauchte dort bis zu 11,3 % mehr Zeit; diese Variante
wurde verworfen. Deshalb gilt das Ergebnis ausschließlich für die hier beschriebene
bedarfsgesteuerte Variante, nicht als Versprechen für jede Welt und Systemlast.

Der endgültige Code besteht lokal **384 erforderliche Server-GameTests**, darunter
acht neue Cache-/Planvergleichstests, sowie **57 Unit-Tests** und **65 zusätzliche
Tests im klassischen KI-Modus**. Ein neuer Kies-Test wärmt kürzere Abbaufolgen vor
und prüft, dass gecachte Kosten die Rekursionstiefengrenze nicht umgehen.
Der echte Minecraft-Client mit Control-Center-, Debug- und Weltlebenszyklustests
ist ebenfalls erfolgreich geprüft. Dies ist ein lokaler Nachweis;
die GitHub-Abnahme bleibt getrennt zu prüfen.
