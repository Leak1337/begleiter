<h1 align="center">⚒️ Begleiter</h1>

<p align="center">
  <strong>Ein zweiter Spieler für deine Minecraft-Welt.</strong><br>
  Ein Begleiter, der Aufträge annimmt, sammelt, herstellt, baut, überlebt und sich an die Welt erinnert.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.3-4CAF50" alt="Minecraft 26.3">
  <img src="https://img.shields.io/badge/Fabric-0.19.5%2B-2196F3" alt="Fabric Loader 0.19.5 oder neuer">
  <img src="https://img.shields.io/badge/Java-25-F89820" alt="Java 25">
  <img src="https://img.shields.io/badge/Status-Beta-orange" alt="Beta">
  <img src="https://img.shields.io/badge/Lizenz-MIT-lightgrey" alt="MIT-Lizenz">
</p>

<p align="center">
  <a href="https://github.com/Leak1337/begleiter/releases"><strong>Download</strong></a> ·
  <a href="README.md">English</a> ·
  <a href="#schnellstart">Schnellstart</a> ·
  <a href="#mit-dem-begleiter-sprechen">Chat</a> ·
  <a href="#control-center">Control Center</a> ·
  <a href="#bauen">Bauen</a> ·
  <a href="#grenzen">Grenzen</a> ·
  <a href="CHANGELOG.md#deutsch">Änderungen</a>
</p>

---

Begleiter ist eine **Fabric-Mod für Minecraft Java Edition 26.3**. Statt eines Haustiers bekommst du einen NPC mit eigenem Inventar, Werkzeugen, Gedächtnis und Aufgabenplanung. Er folgt dir, erledigt, was du ihm im Chat sagst, oder spielt selbstständig nach seinen Bedürfnissen und seinem Fortschritt.

Alles läuft nach echten Minecraft-Regeln: echte Blöcke, Rezepte, Werkzeuge, Öfen und Kisten – keine herbeigezauberten Gegenstände. Seine eigene Navigation baut Hindernisse ab, setzt Brücken und Säulen und simuliert Sprünge mit Minecraft-Physik.

> **Beta.** Einzelspieler und LAN sind die getesteten Hauptmodi; Dedicated Server gelten als Beta. Der Begleiter arbeitet überwiegend regelbasiert – er kann hängen bleiben, und der Weg bis zum Enderdrachen ist keine Garantie. **Sichere deine Welt**, bevor du große Bau- oder Geländeaufträge gibst.

## Highlights

- 💬 **Chat-Aufträge auf Deutsch und Englisch** – `@ Bring mir 10 Eisen`, `@ Crafte mir 2 Steinsägen`, `@ Bau ein großes Steinhaus`.
- 🧠 **Plant selbst** – beschafft Zutaten, stellt Zwischenprodukte und Werkzeuge her, stellt Werkbank und Ofen auf, schmilzt und liefert.
- 🏗️ **24 Farmen und Maschinen plus Häuser** mit 3D-Vorschau und verschiebbarer, drehbarer Weltvorschau vor dem Bau.
- 📐 **Schematics** – `.litematic`, `.schem`, `.nbt` und MCEdit-`.schematic`, Block für Block mit selbst gesammeltem Material.
- 🗺️ **Daueraufgaben mit Arbeitsbereichen** – Felder, Tiere, Basis, Lager, Erkunden, Tiere holen und Handel, Bereiche auf einer Karte markiert.
- ⚔️ **Überleben und Kampf** – isst, jagt, kocht, beschützt dich, nutzt Bogen und Schild, meidet Lava und tiefe Stürze.
- 💾 **Merkt sich** Erze, Kisten, Dörfer, Portale und Gefahren; behält alles über Speichern und Laden und sogar über den Tod hinaus.
- 🤖 **Optionales LLM** – standardmäßig aus; eingeschaltet versteht er auch freie Sätze. Die Mod funktioniert vollständig ohne.

## Schnellstart

| Du brauchst | Version |
| --- | --- |
| Minecraft Java Edition | **26.3** |
| [Fabric Loader](https://fabricmc.net/use/) | **0.19.5 oder neuer** |
| [Fabric API](https://modrinth.com/mod/fabric-api) | passend zu 26.3 |
| Java | **25 oder neuer** |

1. Fabric Loader für Minecraft 26.3 installieren.
2. **Fabric API** und `begleiter-mc26.3-<version>.jar` aus den [Releases](https://github.com/Leak1337/begleiter/releases) in den `mods`-Ordner legen.
3. Eine Welt starten und einen **Begleiter-Kern** herstellen:

   ```text
   E R E     E = Eisenbarren
   G D G     R = Redstone
   E E E     G = Goldbarren
             D = Diamant
   ```

4. Mit dem Kern auf einen Block rechtsklicken. Dein Begleiter **Leak** erscheint.
5. `@ Folge mir` probieren, dann `@ Bring mir 16 Holz`. **Shift + Rechtsklick** öffnet das Control Center.

Auf einem Server brauchen **Server und alle Spieler** die Mod. Stirbt der Begleiter, lässt er seinen Kern fallen; damit kommt er mit Namen und Wissen zurück.

## Mit dem Begleiter sprechen

Der Begleiter reagiert nur, wenn du ihn **ansprichst** – auch im Einzelspieler:

- `@` am Satzanfang oder die Wörter **Leak** bzw. **Begleiter** irgendwo im Satz sprechen deinen nächsten Begleiter an.
- **Bot** und **Companion** zählen nur am Satzanfang (`Bot, folge mir`) oder als `@Bot` – normaler Chat löst nichts aus.
- Sein Name spricht genau ihn an: `Max, folge mir` oder `@Max folge mir`.

| Nachricht | Wirkung |
| --- | --- |
| `@ Folge mir` / `@ Warte hier` / `@ Spiel selbstständig` | Modus wechseln |
| `@ Hör auf` / `@ Weiter` | Aufträge abbrechen / fortsetzen |
| `@ Bring mir 10 Fackeln` | Gegenstände beschaffen und dir bringen |
| `@ Mach mir eine Eisenspitzhacke` | Herstellen samt benötigter Materialien |
| `@ Bringe mir Ressourcen für 2 Steinsägen` | Zutaten beschaffen und dir ins Inventar geben |
| `@ Crafte mir 2 Steinsägen` | Neue Gegenstände herstellen und dir ins Inventar geben |
| `@ Bau ein großes Steinhaus` / `@ Bau eine Farm` | Bauen |
| `@ Ernte das Feld` / `@ Züchte Kühe` | Feld und Tiere |
| `@ Reparier dein Werkzeug` / `@ Rüste dich aus` | Ausrüstung pflegen und verbessern |
| `@ Kauf mir ein Buch` / `@ Verkauf dein Zeug` | Handel mit Dorfbewohnern |
| `@ Planier hier 15x15` | Gelände bearbeiten |
| `@ Merk dir das als Mine` / `@ Geh zur Mine` / `@ Vergiss den Wegpunkt Mine` | Wegpunkte |
| `@ Sei defensiv` / `@ Beschütz mich` | Kampfverhalten |
| `@ Das ist dein Lager` / `@ Räum die Kisten auf` | Lager festlegen und sortieren |
| `@ Was weißt du?` / `@ Wo ist Eisen?` | Weltwissen abfragen |
| `@ Warum machst du das?` / `@ Status` | Ziel und Zustand erklären |

Die Erkennung arbeitet mit festen Wörtern und Mustern; englische Befehle wie `@ follow me` gehen ebenfalls (siehe [README.md](README.md)). Ungewöhnliche oder stark verschachtelte Sätze versteht er nur mit der [optionalen LLM-Anbindung](#optionale-llm-anbindung) zuverlässig.

**Gegenstände und Rezepte.** Deutsche oder englische Itemnamen und vollständige IDs wie `minecraft:stonecutter` funktionieren; Namen aus installierten Mods über deren Sprachdateien. Der Begleiter nutzt zuerst Inventar und Kisten, kombiniert Teilmengen und beschafft den Rest. Unterstützt werden Werkbank-, Ofen- und Steinsägenrezepte sowie Aufwertungen am Schmiedetisch, auch aus passenden Mods und Datapacks – `@ Crafte mir ein Netheritschwert` klappt, sobald die Zutaten beschaffbar sind; Name, Verzauberungen und Schaden bleiben bei der Aufwertung erhalten. Brauen, Rüstungsverzierungen und andere Spezialrezepte gibt es noch nicht.

**Rückfragen.** Nach `@ Crafte mir eine Rüstung` beantwortet `@ Eisen` die Materialfrage und setzt denselben Auftrag fort. Pausiert die Arbeit nach einem Problem: Problem beheben und `@ weiter` sagen; `@ abbrechen` verwirft den Auftrag.

## Control Center

**Shift + Rechtsklick** auf den Begleiter oder **B** von überall in derselben Dimension (änderbar unter *Optionen → Steuerung*). Das Menü folgt deiner Minecraft-Sprache: Deutsch bei Deutsch, sonst Englisch.

| Seite | Inhalt |
| --- | --- |
| **Übersicht** | Leben, Hunger, Modus, aktueller Auftrag, Inventar und Ausrüstung |
| **Steuerung** | Sammeln, Lager, Bauen und einzelne Alltagsaufträge |
| **Aufgaben** | Grundmodi, sieben Daueraufgaben und die Bereichskarte |
| **Inventar** | 36 Plätze des Begleiters und deine; Shift-Klick überträgt |
| **Ausrüstung** | Rüstung und Hände (er wählt selbst aus seinem Inventar) |
| **Kampf** | Aktuelles Ziel und Kampfmodus: aggressiv, defensiv, passiv |
| **Status** | Bedarf, Basis und Wissen |
| **Bauen** | Häuser, Farmen und Maschinen, Schematics, Vorschauen, Material- und Bewegungsmodus, Fortschritt |
| **Diagnose** | Protokoll mit Filter und Blättern |
| **Debug** | Intelligence 2.0 serverweit an/aus (nur Admins) |

Rechtsklick wechselt zwischen Folgen und selbstständigem Spielen. **F7 / F8 / F6** zeigen Debug-HUD, Debug-Menü und Pfadanzeige für Admins und den Besitzer der Einzelspielerwelt.

## Daueraufgaben

Unter **Aufgaben** stehen sieben Arbeiten, die der Begleiter fortlaufend erledigt, bis du den Modus wechselst:

| Aufgabe | Was er tut |
| --- | --- |
| **Felder pflegen** | Erntet reife Pflanzen, sät nach und lagert die Ernte ein |
| **Tiere versorgen** | Sammelt lose Gegenstände und füttert Kühe, Schafe, Schweine und Hühner im Rahmen der Herdengröße |
| **Wandere herum und erkunde** | Besucht sichere Orte im Freilauf-Bereich und merkt sich, was er findet |
| **Kümmer dich um die Base** | Sammelt Gegenstände, pflegt Felder und Tiere, lagert Überschuss ein und sortiert das Lager |
| **Kümmer dich um Kisten** | Sammelt im Lagerbereich und sortiert Kisten, wenn sich der Inhalt ändert |
| **Hole Tiere zur Weide** | Führt ausgewachsene Tiere in den Tierbereich, bis jede Art zwei hat |
| **Dorfhandel** | Verkauft vorhandene Waren an passende Angebote |

Nichts wird erzeugt: Pflanzen müssen wachsen, Tiere vorhanden sein und der Bereich geladen bleiben. Daueraufgaben überstehen Speichern und Laden.

<details>
<summary><strong>Karte und Arbeitsbereiche</strong></summary>

**Aufgaben → Karte & Bereiche** zeigt eine lokale Geländekarte (Norden oben). **Base**, **Tiere**, **Felder**, **Lager** oder **Freilauf** wählen, ein Rechteck ziehen und **Speichern**. Mausrad zoomt, Pfeiltasten verschieben. Unbekanntes Gelände bleibt dunkel – die Karte lädt nie zusätzliche Chunks.

**In Welt markieren** wählt stattdessen zwei Bodenecken: **Z** markiert eine Ecke, **G** speichert, **K** führt zurück zur Karte, **Rücktaste** bricht ab. Beide Ecken müssen höchstens 96 Blöcke von dir entfernt und geladen sein.

Bereiche sind höchstens **64 × 64 Blöcke** groß und 32 hoch. Erkunden, Basepflege, Kistenpflege und Tiere holen brauchen ihren Bereich; Felder und Tiere bevorzugen einen. Bereiche begrenzen Arbeitsziele, nicht die Bewegung – Wege dürfen sie verlassen. Ändern oder Löschen eines Bereichs hält die aktive Daueraufgabe an.

</details>

## Bauen

**Bauen** zeigt Karten mit texturierten 3D-Vorschaubildern: Häuser (Holz, Stein oder Birke in drei Größen), die Weizenfarm, **24 Farmen und Maschinen** und deine eigenen Schematic-Dateien. Dazu bringt die Mod drei eigene Lager-Entwürfe mit: ein **Zentrallager für 128 Materialien mit Sortierung und Itemabruf** sowie **kompakte Survival-Kistenräume für 12 und 24 Materialien**.

1. Eine Karte wählen und in der 3D-Ansicht ansehen (ziehen dreht, Mausrad zoomt).
2. **Platzieren** öffnet eine durchscheinende Vorschau in der Welt, die dem angezielten Block folgt.
3. Anpassen und mit **Enter** den Bau starten.

| Taste | Weltvorschau |
| --- | --- |
| **R** | Um 90° drehen |
| **Bild ↑ / Bild ↓** | Höher (bis 16) oder tiefer (bis 6) |
| **M** | Bau-Bewegung: Normal → Fliegen → Fliegen/Teleport |
| **Enter** | Bestätigen und starten |
| **Rücktaste** | Abbrechen; der bisherige Auftrag bleibt |

Rote Vorschaublöcke sind Hindernisse. Der Server prüft Bauhöhe, Weltgrenze, geladene Chunks, Behälter und von Spielern gebaute Blöcke erneut; abgetragen wird nur natürliches Gelände, Lücken unter dem Fundament werden bis sechs Blöcke tief aufgefüllt.

**Materialmodus:** Im **Survival** kostet jeder Block ein Item – der Begleiter beschafft Fehlendes oder fragt dich, das Menü zeigt Fortschritt und fehlendes Material. **Kreativ** baut mit unbegrenztem Material und steht Kreativspielern, Admins und dem Besitzer der Einzelspielerwelt offen. Die **Bau-Bewegung** (Normal, Fliegen, Fliegen/Teleport) stellst du getrennt ein; Fliegen erzeugt nie Material.

<details>
<summary><strong>Farmen und Maschinen</strong></summary>

| Bauplan-ID | Aufbau und Betrieb |
| --- | --- |
| `zuckerrohrfarm`, `bambusfarm` | Beobachter, Kolben und Sammelrinne; automatisch bei geladenen Chunks |
| `kaktusfarm` | Neun Kakteen mit Wachstumsernte und Sammelrinne |
| `wasserernte`, `karottenfarm`, `kartoffelfarm` | Beleuchtete Felder mit Wasserernte: Wassereimer in den Werfer, Knopf zum Ernten und erneut zum Zurückholen, dann nachsäen |
| `ruebenfarm` | Zwei erhöhte Rübenbeete mit Mittelgang und abgedeckter Bewässerung |
| `kuerbisfarm` | Offener Kürbisgarten; Früchte ernten, die sechs Stiele stehen lassen |
| `melonenfarm` | Schmales Gewächshaus mit zwei Stielreihen und Erntegang |
| `kuhfarm` | Auslauf mit zwei überdachten Futterständen |
| `schaffarm` | Grasweide mit abtrennbarem Schergang |
| `schweinefarm` | Vier Zuchtbuchten mit Verbindungstoren |
| `kaninchenfarm` | Zwei Schlafboxen und ein hoch eingezäunter Auslauf |
| `huehnerfarm` | Geschlossener Stall; Eier fallen durch Teppiche in Trichter und eine Kiste |
| `schmelzofen`, `raeucherofen`, `hochofen` | Getrennte Eingaben für Rohstoffe und Brennstoff; Ausgabe vorne |
| `komposter` | Automatisches Kompostieren mit Knochenmehl-Ausgabe |
| `schaltlager`, `portionierer`, `itemsortierer` | Schaltbarer Trichtertransport, einzelne Portionen oder ein Itemfilter |
| `bruchsteingenerator` | Wasser und Lava erzeugen neuen Bruchstein |
| `lichtschalter`, `tresorschloss` | Flurlicht von zwei Seiten oder Eisentür mit Hebelkombination |

Tieranlagen erzeugen keine Tiere; führe sie selbst hinein oder nutze **Hole Tiere zur Weide**; **Tiere versorgen** füttert sie danach regelmäßig. Alle Pläne gehen auch mit `/begleiter bauplan <id>`, z. B. `/begleiter bauplan kuhfarm`. Ausführlich: [Farmen und Maschinen](docs/farmen-und-maschinen.md).

</details>

**Schematics** gehören in den Ordner `schematics` des Spielverzeichnisses oder nach `config/worldedit/schematics`. Dateien aus älteren Minecraft-Versionen werden wie alte Welten umgerechnet. Schildtexte und Crafter-Sperren werden übernommen, Behälterinhalte nur im Kreativ-Materialmodus, Befehle in Befehlsblöcken nie.

## Slash-Befehle

`/begleiter` steuert deinen nächsten eigenen Begleiter innerhalb von 64 Blöcken. Jeder Befehl hat ein englisches Gegenstück unter `/companion`.

<details>
<summary><strong>Befehlsliste</strong></summary>

```text
/begleiter folgen | warten | selbststaendig
/begleiter abbrechen | fortsetzen | status
/begleiter sammeln <block> [anzahl]
/begleiter bauen plattform <block> <breite> <tiefe>
/begleiter bauen wand <block> <breite> <hoehe>
/begleiter freiraeumen <breite> <hoehe> <tiefe>
/begleiter bauplaene
/begleiter bauplan <name> [luft]
/begleiter material
/begleiter lager | lagerverwalter | einlagern | sortieren
/begleiter protokoll [anzahl] | diagnose
/begleiter projekt [vorschau | bestaetigen | status | pruefen | verwerfen]
/begleiter llm [status | an | aus | neuladen | test <satz>]
```

- `bauplan <name> luft` räumt zusätzlich die vorgesehenen Luftblöcke frei.
- `projekt` zeigt eine reine Vorschau für ein kleines Holzhaus (Schritte, Material, Standortwarnungen); Projekte werden in dieser Version nicht automatisch gebaut.
- `lager` setzt die angesehene Kiste oder das angesehene Fass (bis 6 Blöcke entfernt) als Lager.

</details>

## Intelligence 2.0

Ein zusätzliches Entscheidungssystem, **standardmäßig an**. Es wählt Beschaffungs- und Arbeitsziele nach Lage (Leben, Hunger, Werkzeug, freier Platz, Gefahr, Wegkosten), kombiniert Teilmengen aus mehreren Kisten, merkt sich, welche Beschaffungswege funktioniert haben, und prüft Voraussetzungen vor dem Start. Admins bzw. der Besitzer der Einzelspielerwelt schalten es auf der Seite **Debug** serverweit aus (gespeichert in `config/begleiter-intelligence.json`); `-Dbegleiter.intelligence.enabled=false` hat beim Start Vorrang.

## Optionale LLM-Anbindung

**Standardmäßig aus.** Eingeschaltet gehen Sätze, die die feste Erkennung nicht versteht, an ein Sprachmodell. Es wählt einen der vorhandenen Aufträge, fragt bei Unklarheit nach oder plaudert.

| Du schreibst | Der Begleiter |
| --- | --- |
| `@ Ich hätte gern was von dem grauen Metall` | „Alles klar, ich hole dir 16 Eisen.“ |
| `@ Ich könnte echt 3 Diamantschwerter gebrauchen` | stellt drei Diamantschwerter her |
| `@ Hast du was zum Leuchten dabei?` | „Ja, ich habe 5 Fackeln.“ |

- Sätze, die die feste Erkennung kennt, erreichen das Modell nie.
- Das Modell kann **nur vorhandene Aufträge auslösen**; jedes Item, Tier, jede Menge und jeder Wegpunkt wird geprüft, Erfundenes verworfen.
- **Datenschutz:** Nur unverstandene Sätze werden gesendet – beim Plaudern zusammen mit einem kurzen Zustandsbericht (Modus, Leben, Hunger, Aufträge, Inventar, nahe Gefahren, Wegpunktnamen). Er enthält **keine Koordinaten**. Der API-Schlüssel bleibt in deiner Konfiguration und erscheint in keinem Protokoll. Auf einem Server gilt der Schlüssel des Servers für alle Spieler.

<details>
<summary><strong>Einrichtung und Einstellungen</strong></summary>

1. Einen API-Schlüssel anlegen; voreingestellt ist [Groq](https://console.groq.com). Jeder Dienst mit kompatibler Chat-Completions-Schnittstelle funktioniert.
2. Minecraft einmal starten – die Mod legt `config/begleiter-llm.json` an (ausgeschaltet).
3. Den Schlüssel unter `apiKey` eintragen und `/begleiter llm an` ausführen.
4. `/begleiter llm test <satz>` zeigt, wie ein Satz verstanden würde, ohne dass etwas passiert.

| Befehl | Wirkung |
| --- | --- |
| `/begleiter llm status` | An/aus, Modelle, verbrauchtes Kontingent, letzte Aufrufe (alle) |
| `/begleiter llm an` / `aus` | Schalten und speichern (Operatoren) |
| `/begleiter llm neuladen` | Konfiguration neu lesen (Operatoren) |
| `/begleiter llm test <satz>` | Verbindung und Übersetzung testen (Operatoren) |

| Feld | Standard | Bedeutung |
| --- | --- | --- |
| `enabled` | `false` | Schalter |
| `apiKey` | leer | Ohne Schlüssel bleibt alles aus |
| `baseUrl` | `https://api.groq.com/openai/v1` | Kompatibler Dienst; `http://` nur für `localhost` |
| `intentModel` / `chatModel` | `openai/gpt-oss-20b` | Aufträge / freie Antworten |
| `timeoutMs` | `8000` | Wartezeit je Anfrage |
| `maxRequestsPerMinute` / `maxRequestsPerDay` | `6` / `500` | Eigene Grenzen der Mod |

Bei Zeitüberschreitung, Verbindungsfehler oder erschöpftem Kontingent greift die feste Erkennung. Kosten und Grenzen hängen von Anbieter und Konto ab.

</details>

## Grenzen

- **Einzelspieler und LAN** sind die getesteten Hauptmodi; **Dedicated Server sind Beta**. Umfangreiche Mehrspieler-, Lag- und Verbindungsabbruch-Tests stehen noch aus.
- **Keine KI-Garantie:** Lange selbstständige Aufgaben und der Weg ins End können scheitern.
- **Weltänderungen:** Bauen, Planieren und Schematics verändern die Welt – vorher sichern. Schutzgebiete anderer Mods werden nicht beachtet.
- **Redstone:** Eingebaute Anlagen nutzen normale Mechanik. Importierte Schaltungen werden nachgebaut, aber nicht als funktionierende Maschine geprüft.
- **Deutsche Itemnamen auf Dedicated Servern:** Die Mod liefert keine Minecraft-Übersetzungen mit. Im Einzelspieler und LAN kommen die deutschen Itemnamen aus deinem installierten Spiel; ein Dedicated Server kennt nur die englischen Namen, die eingebauten deutschen Chat-Wörter und Item-IDs.
- **LLM (optional):** Modelle verstehen gelegentlich etwas falsch; ohne Internet oder nach dem Limit übernimmt die feste Erkennung. Der selbstständige Modus nutzt das LLM nicht.

Einen Fehler gefunden? Bitte ein [Issue](https://github.com/Leak1337/begleiter/issues) mit Minecraft-, Fabric- und Mod-Version und dem Protokoll des Begleiters (Seite **Diagnose**) anlegen.

## Aus dem Quellcode bauen

Benötigt **JDK 25**; die Zielversionen stehen in [gradle.properties](gradle.properties).

```bash
./gradlew assemble test compileGametestJava verifyPackagedMod
./gradlew runGameTest
./gradlew runClientGameTest
```

Unter Windows `.\gradlew.bat` verwenden. Die spielbare Mod liegt unter `build/libs/begleiter-mc26.3-<version>.jar` (`-sources.jar` ist Quellcode, nicht die Mod). `runGameTest -PtestFilter="begleiter-gametest:<name>*"` startet eine einzelne Testgruppe; `runClient` spielt direkt aus dem Quellcode.

<details>
<summary><strong>Testmap für die Entwicklung</strong></summary>

```bash
./gradlew runTestmap -PtestmapView=ausstellung
```

In der Testmap zeigt `/companion testmap ausstellung` die Farmen und Maschinen, `/companion testmap redstone` die Arbeitsfläche, und `/companion testmap szenario farmen_alle` prüft alle eingebauten Anlagen. `/companion testmap farmfeld` führt zur 128×128-Testwiese; `farmfeld reset`, `farmfeld items` und `farmfeld tiere` bauen sie neu auf oder räumen Gegenstände und Tiere weg. Diese Befehle ersetzen große Bereiche – nur in Testwelten.

</details>

Mehr dazu: [Farmen und Maschinen](docs/farmen-und-maschinen.md), [Speicherung und Serverende](docs/speicherung-und-serverende.md), [sichere Teleports](docs/sichere-teleports.md), [Zentrallager](docs/zentrallager-128.md), [Survival-Kistenräume](docs/survival-kistenraum-24.md).

## Lizenz

Der eigene Code steht unter **MIT** – siehe [LICENSE](LICENSE). Drei angepasste Baritone-Bestandteile (`dev.begleiter.nav.NodeHeap` und `dev.begleiter.thirdparty.baritone`) stehen unter **LGPL-3.0-or-later**; siehe [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Jede Mod-JAR enthält die Lizenztexte und ihren baubaren Quellcode in `META-INF/begleiter-sources.zip`.

Nicht mit Mojang oder Microsoft verbunden. Entwickelt von **Leak1337**.
