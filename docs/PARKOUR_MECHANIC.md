# Baritone-Level-Parkour für den Begleiter

Die folgenden
Werte beschreiben den ursprünglichen Entwicklungsauftrag und sind keine Garantie.

Erweitere die bestehende A*-Wegfindung um eine präzise 3D-Parkour-Schicht. Die
Wegfindung selbst bleibt erhalten: `Move`, `PathSearch`, `Movement`, `PathExecutor`
und `NavContext` werden gezielt verbessert.

## Ausgangslage

Der aktuelle Code kann bereits Lücken von 1–3 Blöcken, `+1` Höhe, Sprint-Parkour,
Fallrisiko und Re-Planning. Die festen Werte in `Movement.parkour()` (`0.30/0.36`
horizontal, `0.42` vertikal) dürfen nicht länger die Sprungweite bestimmen.

## Ziel-Stats

Alle Werte müssen durch eine echte 20-Hz-Physiksimulation bestätigt werden. Nicht
erreichbare Kandidaten werden als `INF` verworfen; keine künstliche Sprungkraft und
kein Teleportieren.

| Profil | Ziel | Bedingung |
|---|---:|---|
| Precision Jump | 1–2 Block Lücke | normaler Anlauf, Landung mittig |
| Sprint Jump | 3 Block Lücke | voller Sprint, mindestens 2 freie Anlaufblöcke |
| Max-Reach Jump | 4 Block Lücke | nur wenn die Simulation mindestens 0,15 Block Reserve zeigt |
| Step-Up Jump | +1 Block | 2 Blöcke Kopf-/Körperfreiheit |
| Drop Jump | bis zur sicheren Fallgrenze | Fallschaden muss danach mindestens 8 Leben übrig lassen |
| Diagonal Jump | 1–3 Block Distanz | AABB-Sweep muss alle Ecken freihalten |
| Chain Parkour | 6 Sprünge Lookahead | Momentum und sichere Folge-Landung erhalten |

Die tatsächliche Maximalreichweite hängt von Mob-Größe, Sprint, Hunger, Effekten,
Inventar, Blockform und aktueller Geschwindigkeit ab. Die Tabelle definiert Tests,
nicht Freifahrtscheine.

## Planung

1. Erzeuge pro Knoten mehrere Kandidaten: gerade, diagonal, links/rechts versetzt,
   gleich hoch, `+1` und sichere Landung nach unten. Prüfe nicht nur den Zielblock,
   sondern die komplette Landefläche (mindestens 3×3 Samples um die Blockmitte).
2. Simuliere jeden Kandidaten tickgenau für bis zu 40 Ticks mit der echten Entity-
   AABB, Gravitation, Reibung, Sprint und Kollision. Zwischen den Ticks mindestens
   vier Kollisions-Sweeps; keine Blockkanten durchdringen.
3. Prüfe Start-, Flug-, Ziel- und Überschießbereich. Kopf, Füße und die gesamte
   Breite des Begleiters müssen frei sein. Ein Ziel ist nur gültig, wenn danach noch
   mindestens ein sicherer Folgeschritt existiert.
4. Simuliere zusätzlich die drei wahrscheinlichsten Fehlpfade (zu kurz, links,
   rechts). Wenn keiner davon eine sichere Rückfallposition oder geplante
   Wassereimer-Landung besitzt, ist der Sprung verboten.
5. Unterstütze Parkour-Ketten mit 6 Bewegungen Lookahead. Ketten dürfen Momentum
   nutzen, müssen aber nach jedem Sprung neu validiert und bei Weltänderung gesplittet
   werden.

## Kostenmodell

Erweitere die vorhandenen `Costs` um messbare Parkour-Kosten:

```text
Kosten = Flugzeit + Anlauf + Sprungstrafe
       + Korrekturaufwand + Fall-/Schadensrisiko
       + Unsicherheit + fehlende Landemarge
```

- Normales Gehen bleibt günstiger als ein sicherer Sprung; ein sicherer Umweg bleibt
  günstiger als ein Sprung mit tödlichem Fehlpfad.
- `landingMargin < 0.15` Block, unklare Kollision, ungeladener Chunk, Void, Lava,
  Feuer, Magma, Kaktus, Powder Snow oder `AVOID_INTO` ergeben `INF`.
- Belohne eine Landung nahe der Zielmitte und eine freie Folgebewegung; bestrafe
  Randlandungen, harte Richtungswechsel und wiederholte Fehlversuche.
- Rechne reale `NavContext`-Daten ein: Leben, Fallschaden, Federfall, Wasser-Eimer,
  Hunger, Sprintfähigkeit, Bedrohungen und RouteMemory.

## Ausführung (`Movement` / `PathExecutor`)

Implementiere eine Zustandsmaschine:

`APPROACH → COMMIT → AIRBORNE → LAND → STABILIZE`

- `APPROACH`: Absprungpunkt mit maximal `0.15` Block Positionsfehler anfahren.
- `COMMIT`: Anlaufgeschwindigkeit und Startvektor aus der simulierten Flugbahn setzen;
  kein fester `0.30/0.36`-Impuls.
- `AIRBORNE`: jede Tickposition gegen den Zielkorridor steuern, seitlich korrigieren,
  aber vertikale Vanilla-Physik nicht überschreiben.
- `LAND`: Zielmitte, Y-Höhe, Boden und AABB erneut prüfen; bei Abweichung sofort
  `PathExecutor`-Re-Planning auslösen.
- `STABILIZE`: Sprint lösen, Geschwindigkeit kontrolliert abbauen und erst danach
  die nächste Kettenbewegung starten.

Sprünge dürfen nicht mitten in der Luft abgebrochen werden. Bei Blockänderung,
unerwarteter Kollision, fehlendem Fortschritt oder gefährlichem Fehlpfad wird nach
der Landung bzw. beim sicheren Fallen neu geplant; niemals blind weiterlaufen.

## Abnahme / Benchmarks

- 100 Wiederholungen je Profil mit identischem Seed: 1–2 Block mindestens 98 %,
  3 Block mindestens 95 %, 4 Block nur bei nachgewiesener Simulationsreserve.
- 0 tödliche Unfälle bei Lava, Void, Feuer, Powder Snow, niedriger Decke und
  bewegten Hindernissen.
- 0 Durchdringungen von Blöcken; keine festhängenden Sprünge länger als 40 Ticks.
- Parkour-Benchmark darf den bestehenden Wert von **77 Ticks** nicht verschlechtern;
  Suchbudget und Teilweg-/Spleißlogik aus `wegfindung-plan.md` bleiben aktiv.
- GameTests für alle Profile, diagonale/enge Landungen, Chain Parkour, Fehlpfade,
  Weltänderung während des Sprungs und Eimer-Fallback ergänzen.
- Debug-Anzeige um Profil, Flugticks, Zielabweichung, Landemarge, Fehlpfad-Risiko,
  Re-Plan-Grund und tatsächliche Reichweite erweitern.

Keine Änderungen an der vorhandenen A*-Heuristik ohne Benchmark-Nachweis. Die
Parkour-Schicht muss server-authoritativ, deterministisch und mit der bestehenden
Pfadvisualisierung kompatibel bleiben.

## Stand der Umsetzung (01.11.2026)

- `JumpSim`: Vanilla-Physik des Mobs (Eingabe = Tempo ohne ×0,98, am Boden ×Tempo, in
  der Luft ×0,02; Reibung 0,546/0,91; Schwerkraft, ×0,98), echte AABB gegen die
  Kollisionsformen, 4 Teilschritte je Tick. Jede Berührung außer der Landung von oben
  verwirft die Flugbahn (Kanten, niedrige Decke). Kalibriert per GameTest: Gehtempo
  0,1310 = 0,1310, Sprint-Sprung Abweichung 0,000 Blöcke, 12/12 Flugticks.
- `JumpSim.plan`: Präzision (gehen) vor Sprint, Absprung 0,3 / 0,45 / 0 von der Mitte,
  Anlauf-Tempo per Halbierung auf die Zielmitte, Marge ≥ 0,15, 3×3-Landefläche,
  Folgeschritt, drei Fehlpfade (70 % Schwung; seitlich 1 Block daneben). Alle drei
  unsicher → verboten, sonst Kosten (15 je unsicher, 60 je tödlich).
- `Move`: 16 Parkour-Richtungen (gerade, schräg, links/rechts versetzt), gleich hoch,
  +1 (Lücke 1–2) und tiefer (nur höher als der Grund der Lücke). Nur an Kanten.
- `Movement`: `APPROACH → COMMIT → AIRBORNE → LAND → STABILIZE`. COMMIT rechnet ab der
  echten Stelle mit dem echten Schwung neu (Welt neu gelesen); geht das nicht, Anlauf
  und neuer Versuch (höchstens 3). In der Luft nur seitliche Korrektur, Zeitlimit erst
  nach der Landung, Wassereimer bei Fehlsprung über tiefem Fall.
- Ketten: `PathExecutor` prüft 6 Bewegungen voraus; folgt ein Sprung oder geht es
  geradeaus weiter, wird nicht abgebremst.
- Debug: Profil, Flugticks, Marge, Fehlpfade, Weite (geplant/echt), Abweichung, Kette
  hängen am Bewegungsnamen im HUD; Re-Plan-Grund wie bisher unter „Weg gescheitert“.

Messwerte (`ParkourTests`, 100 Wiederholungen mit `PARKOUR_REPS=100`, sonst 20):

| Profil | Erfolg | Ø Ticks |
|---|---:|---:|
| Lücke 1 / 2 / 3 | 100 % / 100 % / 100 % | 30 / 28 / 29 |
| Stufe hoch (Lücke 1 / 2) | 100 % / 100 % | 44 / 24 |
| Runter (Lücke 2, −3) | 100 % | 32 |
| Diagonal | 100 % | 29 |
| Kette (4 Sprünge) | 100 % | 69 |

Parkour-Benchmark 76 Ticks (vorher 77), übrige Strecken unverändert.

Abweichungen und Grenzen:

- Lücke 4: Mit Mob-Tempo zeigt die Simulation keine Reserve – der Sprung wird nicht
  geplant (Test prüft genau das).
- Absprungfenster: Bei Sprint-Tempo (≈ 0,4 Blöcke/Tick) kann er das ±0,15-Fenster
  überspringen; dann springt er im Tick davor (bis 0,3 dahinter) – sicher, weil der
  Sprung ab der echten Stelle neu gerechnet wird.
- Die Test-Fläche „bench“ hat bei 14 eine Decke; hohe Aufbauten stoßen dort an.
