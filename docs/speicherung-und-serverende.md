# Speichern und Serverende

## Normaler Spielstand und Wiederbelebung

Beim normalen Speichern schreibt der Begleiter neben Inventar, Aufträgen, Bauprojekt, Lager, Zonen und Gedächtnis auch `Hunger` und `HungerTimer`. Beim Laden wird Hunger auf 0–20 und der Timer auf 0–29 begrenzt; fehlende oder beschädigte Werte aus alten Spielständen ergeben 20/0. Ein normales Entladen und Laden füllt den Hunger nicht auf.

Der Begleiter-Kern ist ein Erinnerungsspeicher: Er behält Name und Wissen, aber keinen Hunger. Nach Tod und Wiederbelebung beginnt der Begleiter mit Hunger 20.

`PersistenceGameTests` prüfen Speichern/Laden, alte Spielstände ohne diese Felder, negative und zu große Werte, falsche Datentypen und die Wiederbelebung aus dem Kern.

## Aufräumen beim Serverende

Ein gemeinsamer `ServerLifecycleEvents.SERVER_STOPPED`-Listener in `CompanionMod` gibt beim tatsächlichen Serverende allen weltgebundenen statischen Zustand frei. Die Aufräummethoden lassen sich mehrfach aufrufen.

| Bereich | Freigegebener Zustand |
| --- | --- |
| `CompanionRecipes` | Rezeptmanager-Referenz und alle Rezeptindizes |
| `CompanionPlanner` | Budget, Kisten-/Stationscache und Werkbankstatus |
| `ParkourCourse` | Arena-Ursprung, Level, laufende Vorführung und letztes Ergebnis |
| `BotDebugServer` | Abonnements einschließlich verbliebener Meldungen und Auswahl |

Globale Registrierungen und unveränderliche Daten bleiben erhalten. Clientseitige Debug-Anzeigen werden beim Verbindungswechsel verworfen. Mehrere unabhängige Serverinstanzen im selben Prozess werden nicht unterstützt.

## Lebenszyklustest

`ServerLifecycleClientTest` startet und schließt nacheinander zwei echte Einzelspielerwelten. In jeder Welt werden Rezeptindizes, Planer-Caches, eine Parkour-Vorführung und ein Debug-Abonnement belegt. Nach jedem Serverstopp müssen alle diese Zustände leer bzw. auf Ausgangswerten sein; danach wird mehrfaches Aufräumen geprüft.
