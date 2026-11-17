# Wegfindung des Begleiters (`dev.begleiter.nav`)

Stand: **01.11.2026**. Die Migration (Phasen 0–8) ist abgeschlossen. Diese Datei fasst Architektur, Entscheidungen und Erkenntnisse zusammen. Den ausführlichen Verlauf (Analyse, Phasenpläne, Messtabellen vom 31.10.2026) enthält die Git-Historie dieser Datei: `git log -- docs/wegfindung-plan.md`, ältester Stand vor dem Commit „Wegfindung-Doku verschlankt“.

Das Control Center hat Wegfindung und Speicherformat nicht verändert.

## Grundsätze

- **Aufgaben liefern nur Ziele (`Goal`), die Wegfindung entscheidet allein über den Weg.** Ein Pfad ist eine Aufgabe; Bewegungen sind Objekte (`Movement`).
- Baritone war Vorbild für die Wegfindung. Seit 02.11.2026 verwendet die Suche dessen direkt angepassten Heap und `BetterWorldBorder`-Bibliothek (LGPL-3.0-or-later). Unser Knotenspeicher und die Mob-Ausführung bleiben erhalten; der [Vergleich](baritone-vergleich.md) zeigt die Messwerte und Auswahlgründe. Unser eigener Code bleibt MIT; Herkunft und Änderungen stehen in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). Baritone steuert einen Spieler, der Begleiter ist ein Server-Mob: Ausführung läuft über `MoveControl`/`JumpControl`/`LookControl`, in der Luft mit gezielten `setDeltaMovement`-Korrekturen.
- Es gibt keinen `CompanionPathfinder`, keine `CompanionConfig` und keinen Umschalter mehr. `/companion wegfindung`, `-PnewPathing`, `-PoldPathing`, `-Dbegleiter.newPathing` existieren nicht mehr.
- Vanilla-Navigation bleibt nur für Nahkampf (`MeleeAttackGoal`) und als Notlösung (unter Wasser, Ziel unter Wasser, 5–20 s nach „unerreichbar“).

## Klassen

| Klasse | Aufgabe |
|---|---|
| `Costs` | Tick-Konstanten, Falltabelle aus Physik, Strafen |
| `BlockFlags`, `BlockCache` | Block-Eigenschaften als Bitfeld je BlockState-ID; Blockzugriff mit Chunk-Merker |
| `NavContext` | Schnappschuss pro Suche: Abbau-Ticks (ganzes Inventar, Effizienz, Eile, Haltbarkeit), Müllblöcke, sichere Fallhöhe (Leben/Rüstung), Wassereimer, geschützte Bereiche, Bedrohungen |
| `Node`, `NodeHeap`, `NodeMap` | A*-Open-Set (Binär-Heap) und Knoten-Map mit gepackten Positionen |
| `Move`, `MoveResult` | Bewegungen mit statischer Kostenfunktion |
| `Goal` (Block, Near, NearXZ, Adjacent, AnyOf …) | Heuristik und „im Ziel“ |
| `PathSearch` | A* in Zeitscheiben, Teilwege |
| `Path`, `Movement`, `PathExecutor` | Ergebnis, Ausführung je Bewegung, Tick-Ausführung |
| `Navigator` | Steuerung: Ziel annehmen, suchen, ausführen, vorausplanen, spleißen, sicher abbrechen |
| `RouteMemory` | Trampelpfade (pro Welt, gespeichert im Kern und in der Entity) |

## Suche

- Zeitscheiben auf dem Server-Tick: erste Scheibe bis **10 ms**, danach **4 ms pro Tick** (Uhr alle 64 Knoten). Ist das Ziel nach der ersten Scheibe nicht erreicht, läuft er den besten Teilweg (> 5 Blöcke), die Suche rechnet weiter und ersetzt den Pfad am nächsten sicheren Punkt (Spleißen). Gesamtgrenzen: 500 ms mit Fortschritt, 2000 ms ohne, Voraus-Suche 1000 ms.
- Heuristik: Oktil in XZ × 3,563 plus Y-Anteil (hoch 3,16/Block, runter 4,7/Block), **Gewicht 1,5** (gemessen: gleiche Wegkosten wie 1,0, deutlich weniger Knoten bei Schlucht und Parkour).
- Teilwege: Gewichte {1,5; 2; 2,5; 3; 4; 5; 10}, Mindestabstand 5 Blöcke, Kürzung auf 90 % (min. 30 Bewegungen).
- Nach der Suche werden Bewegungen aus der Kette neu erzeugt, Kosten = min(neu, gesucht), Unmögliches kürzt den Pfad. Vorausplanen bei Restkosten < 150 Ticks.
- Fehlstellen (Lava, Mob, unerreichbar) gelten mit Ablaufzeit als Kostenzuschlag (×3), nicht als Verbot.

## Kostenmodell (Einheit: Ticks)

Gehen 4,633 · Sprinten 3,564 (nur Hunger > 6, kein Abbau, kein Wasser) · Wasser 9,091 · Leiter hoch/runter 8,511/6,667 · Sprung ≈ 3,16 · Zusatz: Sprung +2, Abbauen +2/Block, Block setzen +20, Tür öffnen +5. Unmöglich = 1.000.000.

- Abbau-Ticks wie Vanilla (Effizienz, Eile, Abbaulähmung, ×5 in der Luft/unter Wasser) + 2 Strafe + 5 Ticks Abbau-Pause. Werkzeug unter 5 % Haltbarkeit ×1,5.
- Fallen: Zeit aus Tabelle + Schadensstrafe `max(0, Höhe − 3 − Federfall) × 25`, nur wenn danach ≥ 8 Leben bleiben. Wassereimer-Landung bis 40 Blöcke (nicht im Nether): Kosten Fallzeit + 20.
- Gerüst-Säulen rechnen ihre spätere Abbauzeit (≈ 6 Ticks/Block) mit ein.
- Faktoren: Trampelpfad ×0,7 (ab 3 Durchgängen), Bedrohung 1,5–3, Fehlstelle ×3.

## Bewegungen

Traverse (inkl. Brücke, Türen/Zauntore), Ascend, Descend, Fall (Wasser, Eimer, Leiter), Downward, Pillar, Diagonal (beide Ecken frei), Parkour (Lücke 1–3, Landung mit Überschieß-Prüfung), Schwimmen (nur Oberfläche), Leiter/Ranke hoch/runter. **Sprint-Springen wurde verworfen** (beim Mob langsamer).

## Blockregeln

- **Nie durchlaufen:** Lava, Feuer, Lagerfeuer, Magma, Kaktus, Beerenbusch, Spinnweben, Pulverschnee, Wither-Rose, Blasensäulen, Tropfstein, Honig. Netherportal nur, wenn das Ziel selbst der Portalblock ist (`PORTAL`, `NavContext.allowPortal`).
- **Nicht abbauen:** Unzerstörbares, Eis, befallene Blöcke, Blöcke mit Flüssigkeit oben/seitlich, **alles Nicht-Natürliche** (Häuser, Lager, Kisten, Wege) außer eigenem Gerüst und Festungen, Spawner, Budding-Amethyst, Bienenstöcke.
- **Rieselblöcke** (Sand, Kies, Konkretpulver, Amboss, Tropfstein): Abbaukosten rekursiv für alles, was nachrutscht.

## Ausführung (`PathExecutor`)

- Nächste Bewegung im selben Tick. Vom Weg: > 3 Blöcke sofort neu, > 2 mit Zähler (> 100 Ticks neu), beim Fallen nur waagerecht.
- Vor- und Zurückspulen, Zeitlimit je Bewegung (Schätzung + 60 Ticks), Kosten > +10 → neu, beim Start die nächsten 5 Bewegungen prüfen, Wächter „kein Fortschritt“.
- Beim Abbauen läuft er ab dem letzten Block schon los. Sprint über `navControlUntil` (die alte Sprint-Regel schaltete ihn jeden Tick ab).

## Integration

- MOVE_TO → `Goal.Near`, TRAVEL → `Goal.NearXZ` (Teilwege stückeln selbst), BREAK/PLACE → `Goal.Adjacent`, COLLECT → `Goal.Near(item, 1)`, Folgen und Hingehen zu Wesen über den Navigator (Neuplanen erst ab 3 Blöcken Bewegung).
- **Bergbau:** sichtbare sichere Erze (max. 64) als `Goal.AnyOf(Adjacent …)`, A* wählt das günstigste. Unerreichbar → 10 Minuten schwarze Liste. Ohne Erz in Sicht: Grabziel 8 voraus und bis 8 tiefer, die Wegfindung gräbt selbst.
- **Bedrohungskarte** pro Suche frisch: Creeper r5 ×3, Skelett r6 ×2, Zombie/Spinne r4 ×1,5, sonst r3 ×1,5.
- Fackeln nicht mitten in Sprung/Säule und nicht in der Luft setzen.

## Messwerte (Ticks bis zum Ziel, letzter Stand 31.10.2026)

| Strecke | alt (vor Umbau) | neu |
|---|---|---|
| flach40 | 111 | 99 |
| treppe10 | 140 | 107 |
| schlucht | 164 | 71 |
| tunnel20 | 676 | 574 |
| parkour | 118 | 76 (Sprung-Simulation, docs/PARKOUR_MECHANIC.md) |
| hoehle | 300 | 174 |
| reise | > 600 (hing) | 107 |
| bergbau | 483 | 156 |

Suchzeit nach dem Tuning etwa 8–80 % des alten Planers (Parkour 8 %, Schlucht 46 %, flach40 82 %).

## Fallstricke aus der Entwicklung

- Schräg an Mauerecken blieb er hängen: Diagonale nur mit beiden Ecken frei.
- Hin-und-her zwischen zwei Wegpunkten hob das Zeitlimit auf: Wächter „kein Fortschritt“.
- Netherportal wurde als „nie betreten“ behandelt: eigenes Flag `PORTAL`.
- Fackeln/Blumen vor dem Brückenbau wegnehmen („Block im Weg beim Setzen“).
- Wackelige Tests (stören sich über benachbarte Testflächen mit Lava/Wasser, suchen im Umkreis 24): `stelltWerkbankAufUndPacktSieEin`, `bautEimerHoltWasserFuerObsidian`. Beim Wiederholen grün.
- Stand der Regression: 182 GameTests grün (01.11.2026, inkl. 14 Parkour-Tests); davor 161 GameTests grün (`.\gradlew build`), dazu `runClientGameTest`.

## Offene Ideen (unverbindlich)

Ergänzung vom 02.11.2026: begrenzter Baritone-Positionscache nur während der Suche,
Memoisierung geeigneter Abbaukosten und Wiederverwendung exakt gleicher Sprungflüge.
Die zusätzliche Zustandstabelle wird erst nach mindestens 1.024 direkten Zustandsabfragen bei hoher Wiederholungsrate angelegt;
Flag-Abfragen besitzen bereits einen eigenen Cache. Live-Prüfungen umgehen den neuen
Suchcache, Invalidierung setzt auch den zuletzt gelesenen Chunk zurück.
Messmethodik, Vorher/Nachher-Ergebnisse und Grenzen: [Baritone-Vergleich](baritone-vergleich.md).
Mehrere Begleiter teilen sich ein gemeinsames Rechenbudget pro Tick; die Lastabnahme bleibt offen.

Seitengänge im Streifenbergbau (alle 3 Blöcke), Gitter-Index für große `AnyOf`-Zielmengen, feinere Unterteilung teurer Suchoperationen, Haltbarkeits-/Inventar-bewusstere Kosten.
