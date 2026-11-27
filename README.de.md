<h1 align="center">⚒️ Begleiter</h1>

<p align="center">
  <strong>Ein zweiter Spieler für deine Minecraft-Welt.</strong><br>
  Ein NPC-Begleiter, der Aufträge im normalen Chat annimmt, sammelt, herstellt, baut, kämpft und allein Survival spielen kann – alles nach echten Minecraft-Regeln.
</p>

<p align="center">
  <a href="https://github.com/Leak1337/begleiter/releases/latest"><img src="https://img.shields.io/github/v/release/Leak1337/begleiter?include_prereleases&label=Download&color=2ea44f" alt="Download"></a>
  <img src="https://img.shields.io/badge/Minecraft-26.3-4CAF50" alt="Minecraft 26.3">
  <img src="https://img.shields.io/badge/Fabric-0.19.5%2B-2196F3" alt="Fabric Loader 0.19.5 oder neuer">
  <img src="https://img.shields.io/badge/Java-25-F89820" alt="Java 25">
  <img src="https://img.shields.io/badge/Lizenz-Apache%202.0-lightgrey" alt="Apache-2.0-Lizenz">
</p>

<p align="center">
  <a href="https://github.com/Leak1337/begleiter/releases/download/v0.2.0/begleiter-mc26.3-0.2.0.jar"><strong>JAR herunterladen</strong></a> ·
  <a href="README.md"><strong>English</strong></a> ·
  <a href="#schnellstart">Schnellstart</a> ·
  <a href="#mit-dem-begleiter-sprechen">Chat-Befehle</a> ·
  <a href="#control-center">Control Center</a> ·
  <a href="#faq-und-grenzen">FAQ</a> ·
  <a href="CHANGELOG.md#deutsch">Änderungen</a>
</p>

---

**Begleiter** ist eine Fabric-Mod für Minecraft Java Edition 26.3. Statt eines Haustiers bekommst du einen Begleiter mit eigenem Inventar, Werkzeugen, Gedächtnis und Planung. Sag `@ Bring mir 10 Eisen`, und er kümmert sich um den Rest: Erz finden, Spitzhacke herstellen, abbauen, schmelzen und liefern. Lässt du ihn allein, hält er seine Basis am Laufen, passt auf dich auf oder spielt bis zum Enderdrachen.

Nichts wird herbeigezaubert. Er nutzt echte Blöcke, Rezepte, Öfen und Kisten, findet seine Wege selbst und gräbt, brückt und baut Säulen wie ein Spieler.

> [!NOTE]
> **Beta.** Einzelspieler und LAN sind die getesteten Hauptmodi; Dedicated Server gelten als Beta. Sichere deine Welt vor großen Bau- oder Geländeaufträgen.

## Funktionen

| | |
| --- | --- |
| 💬 **Normaler Chat auf Deutsch und Englisch** | `@ Bring mir 10 Eisen`, `@ Crafte mir 2 Steinsägen`, `@ Bau ein großes Steinhaus` – keine Befehle lernen. |
| 🧠 **Plant wie ein Spieler** | Prüft Inventar und Kisten, beschafft Zutaten, stellt Zwischenprodukte und Werkzeuge her, stellt Werkbank und Ofen auf. |
| 🐉 **Spielt allein Survival** | `@ Spiel selbstständig bis zum Enderdrachen`: vom Holzwerkzeug über Eisen, Diamanten, Tränke und Nether bis zum Enderdrachen. |
| 🤝 **Eigeninitiative** | Reicht dir Essen, warnt vor Creepern, bringt dir nach dem Tod deine Sachen zurück und arbeitet nachts an seiner Basis. |
| 🏗️ **Baut** | Häuser, eine Weizenfarm, 24 Farmen und Maschinen und deine eigenen Schematics, mit 3D-Vorschau und verschiebbarer Weltvorschau. |
| 📋 **Dienste im Hintergrund** | Feld, Tiere, Lager, Vorrat, Handel, Bau und Erkundung nebeneinander, mit Prioritäten, Limits und Materialbudgets. |
| ⚔️ **Überlebt und kämpft** | Isst, jagt, kocht, braut Tränke, nutzt Bogen und Schild, beschützt dich und meidet Lava und tiefe Stürze. |
| 💾 **Merkt sich alles** | Erze, Kisten, Dörfer, Portale und Gefahren – über Speichern und Laden und sogar über den Tod hinaus. |
| 🖱️ **Alles per Knopf** | Das Control Center bietet jeden Chatauftrag auch als Knopf. |
| 🤖 **Optionales LLM** | Standardmäßig aus. Eingeschaltet versteht er auch freie Sätze. |

## Schnellstart

| Du brauchst | Version |
| --- | --- |
| Minecraft Java Edition | **26.3** |
| [Fabric Loader](https://fabricmc.net/use/) | **0.19.5** oder neuer |
| [Fabric API](https://modrinth.com/mod/fabric-api) | für 26.3 |
| Java | **25** oder neuer |

1. Fabric Loader für Minecraft 26.3 installieren.
2. **Fabric API** und die [Begleiter-JAR](https://github.com/Leak1337/begleiter/releases/download/v0.2.0/begleiter-mc26.3-0.2.0.jar) in den `mods`-Ordner legen. Ältere Versionen und der Quellcode liegen auf der [Release-Seite](https://github.com/Leak1337/begleiter/releases).
3. Eine Welt starten und einen **Begleiter-Kern** herstellen:

   ```text
   E R E     E = Eisenbarren
   G D G     R = Redstone
   E E E     G = Goldbarren
             D = Diamant
   ```

4. Mit dem Kern auf einen Block rechtsklicken. Dein Begleiter **Leak** erscheint.
5. `@ Folge mir` sagen, dann `@ Bring mir 16 Holz`. **B** drücken oder **Shift + Rechtsklick** auf den Begleiter öffnet das Control Center.

Auf einem Server brauchen **Server und alle Spieler** die Mod. Stirbt der Begleiter, lässt er seinen Kern fallen; setzt du ihn, kommt er mit Namen und Wissen zurück.

## Mit dem Begleiter sprechen

Der Begleiter reagiert nur, wenn du ihn **ansprichst**, auch im Einzelspieler:

- Mit `@` beginnen oder **Leak** bzw. **Begleiter** irgendwo im Satz sagen.
- **Bot** und **Companion** zählen nur am Satzanfang (`Bot, folge mir`) oder als `@Bot`, normaler Chat löst also nie etwas aus.
- Der eigene Name eines Begleiters spricht genau ihn an: `Max, folge mir`.

| Bereich | Beispiele |
| --- | --- |
| **Modus** | `@ Folge mir` · `@ Warte hier` · `@ Spiel selbstständig` · `@ Hör auf` · `@ Weiter` |
| **Gegenstände** | `@ Bring mir 10 Eisen` · `@ Mach mir eine Eisenspitzhacke` · `@ Crafte mir 2 Steinsägen` · `@ Bringe mir Ressourcen für 2 Steinsägen` |
| **Bauen** | `@ Bau ein großes Steinhaus` · `@ Bau eine Farm` · `@ Planier hier 15x15` |
| **Landwirtschaft** | `@ Ernte das Feld` · `@ Züchte Kühe` · `@ Geh angeln` · `@ Fang 10 Fische` |
| **Ausrüstung und Handel** | `@ Reparier dein Werkzeug` · `@ Rüste dich aus` · `@ Kauf mir ein Buch` · `@ Verkauf dein Zeug` |
| **Lager** | `@ Das ist dein Lager` · `@ Räum die Kisten auf` · `@ Halte hier immer 32 Fackeln auf Vorrat` · `@ Bring mir 64 Glas ins Lager` |
| **Orte** | `@ Merk dir das als Mine` · `@ Geh zur Mine` · `@ Vergiss den Wegpunkt Mine` |
| **Kampf** | `@ Sei defensiv` · `@ Beschütz mich` |
| **Spielziel** | `@ Spiel selbstständig bis zur Eisenrüstung` · `@ Spiel selbstständig bis zum Enderdrachen` · `@ Spielziel` |
| **Fragen** | `@ Was weißt du?` · `@ Wo ist Eisen?` · `@ Warum machst du das?` · `@ Status` |

Englisch funktioniert genauso, zum Beispiel `@ bring me 10 iron` (siehe [README.md](README.md)). Itemnamen gehen auf Deutsch, Englisch oder als ID wie `minecraft:stonecutter`; Namen aus anderen Mods über deren Sprachdateien.

<details>
<summary><strong>Gegenstände, Rezepte und Rückfragen</strong></summary>

Der Begleiter nutzt zuerst sein Inventar und bekannte Kisten, kombiniert Teilmengen und beschafft den Rest. Unterstützt werden Werkbank-, Ofen- und Steinsägenrezepte sowie Aufwertungen am Schmiedetisch, auch aus passenden Mods und Datapacks. `@ Crafte mir ein Netheritschwert` klappt, sobald die Zutaten beschaffbar sind; Name, Verzauberungen und Schaden bleiben bei der Aufwertung erhalten. Brauaufträge für dich, Rüstungsverzierungen und andere Spezialrezepte gibt es noch nicht.

Nach `@ Crafte mir eine Rüstung` beantwortet `@ Eisen` die Rückfrage und setzt denselben Auftrag fort. Pausiert die Arbeit nach einem Problem: beheben und `@ weiter` sagen; `@ abbrechen` verwirft den Auftrag.

</details>

<details>
<summary><strong>Vorratsregeln und Lageraufträge</strong></summary>

`@ Halte hier immer 32 Fackeln auf Vorrat` legt eines von bis zu acht Zielen für die Heimatkiste fest. Ist der Begleiter in der Nähe frei, füllt er die Bestände reihum auf, stellt Zwischenprodukte wie Stöcke her und schmilzt an einem Ofen in Reichweite. Zutaten aus anderen Vorratsregeln nutzt er nur oberhalb ihrer Mindestmenge. `@ Vorratsregeln` zeigt den Bestand, `@ Vorratsregel Fackeln löschen` entfernt ein Ziel, `@ Vorratsregeln löschen` alle.

`@ Vorratsregel Fackeln sammeln` erlaubt ihm, für dieses Ziel im Freilauf zu sammeln (erreichbarer Sand, natürlich gewachsenes Holz oder Kohleerz, je nach Rezept); vor weiteren Wegen prüft er Hin- und Rückweg. `@ Vorratsregel Fackeln lokal` schaltet das wieder ab. `@ Holz nur im Freilauf` beschränkt den Holzabbau für alle Aufgaben, `@ Holzgebiet freigeben` hebt das auf.

`@ Bring mir 64 Glas ins Lager` füllt die Heimatkiste einmalig auf 64 Glas auf. Zutaten holt er aus der Heimatkiste und Kisten im Lagerbereich, große Mengen bringt er in Etappen. `@ Dringend …` zieht den Auftrag vor, `mit Holzkohle` bevorzugt diesen Brennstoff, `@ Pause` / `@ Weiter` halten ihn an und setzen ihn fort, und `@ Was machst du gerade?` zeigt Fortschritt oder den Grund einer Pause.

</details>

<details>
<summary><strong>Dienste im Hintergrund</strong></summary>

Markiere die Arbeitsbereiche in der Zonenkarte und füge Dienste hinzu, etwa `@ Dienst Feld hinzufügen` und `@ Dienst Vorrat hinzufügen`. **Feld**, **Tiere**, **Lager** und **Erkundung** nutzen die Zonen Felder, Tiere, Lager und Freilauf. **Handel** verkauft Überschuss in der Basis, **Bau** führt dort Bauaufträge aus, und **Vorrat** nutzt Heimatkiste und Vorratsregeln.

| Auftrag | Wirkung |
| --- | --- |
| `@ Dienst Feld Priorität 5` | Priorität 1–5; wartende Dienste kommen mit der Zeit trotzdem dran |
| `@ Dienst Bau Limit 8` | Höchstens acht Platzierungen pro Einsatz (andere Dienste zählen Ernten, Fütterungen, Handel oder Arbeitsgänge) |
| `@ Dienst Feld pausieren` / `weiter` / `löschen` | Einen Dienst verwalten |
| `@ Dienste aus` / `@ Dienste an` | Den ganzen Arbeitsplan anhalten oder fortsetzen |
| `@ Dienste` | Anstehende Arbeit, Hindernisse, Wartezeiten und Ergebnisse |
| `@ Dienst Handel Budget 12 Weizen` | Höchstens zwölf Weizen pro Einsatz (`@ Dienst Vorrat Budget` listet die Budgets) |
| `@ Dienst Bau warum` | Fehlendes Material für die nächsten Baupositionen |
| `@ Dienst Erkundung Verlauf` | Letzte Einsätze mit Ergebnis, Fortschritt und Dauer |

Spieleraufträge haben immer Vorrang. Einstellungen, Ergebnisse und Wartezeiten bleiben beim Speichern erhalten. Änderst du eine Zone oder die Heimatkiste, füge den betroffenen Dienst erneut hinzu, um die Freigabe zu bestätigen. Auf Dedicated Servern warten Dienste, die Welt oder Lager verändern, auf die Berechtigung des Besitzers.

</details>

## Control Center

**B** von überall in derselben Dimension oder **Shift + Rechtsklick** auf den Begleiter (änderbar unter *Optionen → Steuerung*). Das Menü folgt deiner Minecraft-Sprache. Jeder Chatauftrag ist auch ein Knopf, du musst also nie tippen.

| Seite | Was du dort findest |
| --- | --- |
| **Übersicht** | Leben, Hunger, Modus, aktueller Auftrag, Inventar und Ausrüstung |
| **Aufgaben** | Grundmodi, sieben Daueraufgaben und die Bereichskarte |
| **Aufträge** | Beliebiges Item suchen und Menge wählen: bringen, herstellen, Zutaten sammeln, einlagern; Vorratsregeln |
| **Steuerung** | Sammeln, Lager, Bauen und Alltagsaufträge |
| **Arbeit** | Hintergrunddienste mit Priorität, Limit, Budgets und Verlauf |
| **Abenteuer** | Spielziel, Angeln, Geländearbeiten, Tür, Projektvorschau |
| **Bauen** | Häuser, Farmen, Maschinen und Schematics mit Vorschau und Fortschritt |
| **Wegpunkte** | Wegpunktkarte; Orte mit Namen speichern, ansteuern und vergessen |
| **Inventar** / **Ausrüstung** | Die 36 Plätze des Begleiters und seine Ausrüstung; Shift-Klick überträgt |
| **Kampf** | Ziel, Kampfmodus (aggressiv, defensiv, passiv) und *Beschütze mich* |
| **Status** / **Gespräch** | Bedarf, Basis und Wissen; Fragen, Gesprächigkeit und eine freie Befehlszeile |
| **Diagnose** / **Debug** | Protokoll mit Filter; Intelligence 2.0 an/aus für Admins |

Rechtsklick auf den Begleiter wechselt zwischen Folgen und selbstständigem Spielen. Admins und der Besitzer der Einzelspielerwelt haben auf **F7**, **F8** und **F6** ein Debug-HUD, ein Debug-Menü und eine Pfadanzeige.

## Was er von selbst macht

### Daueraufgaben

Unter **Aufgaben** wählst du eine von sieben Arbeiten, die er fortlaufend erledigt, bis du den Modus wechselst: **Felder pflegen**, **Tiere versorgen**, **herumwandern und erkunden**, **um die Basis kümmern**, **um Kisten kümmern**, **Tiere zur Weide holen** und **Dorfhandel**. Arbeitsbereiche zeichnest du auf einer lokalen Karte (**Aufgaben → Karte & Bereiche**) oder markierst sie in der Welt, je bis 64 × 64 Blöcke. Nichts wird erzeugt: Pflanzen müssen wachsen, Tiere vorhanden sein und der Bereich geladen bleiben.

<details>
<summary><strong>Karte und Arbeitsbereiche</strong></summary>

**Basis**, **Tiere**, **Felder**, **Lager** oder **Freilauf** wählen, ein Rechteck ziehen und **Speichern**. Mausrad zoomt, Pfeiltasten verschieben; unbekanntes Gelände bleibt dunkel, weil die Karte nie zusätzliche Chunks lädt.

**In Welt markieren** wählt stattdessen zwei Bodenecken: **Z** markiert eine Ecke, **G** speichert, **K** führt zur Karte zurück, **Rücktaste** bricht ab. Beide Ecken müssen höchstens 96 Blöcke von dir entfernt und geladen sein.

Bereiche sind höchstens 64 × 64 Blöcke groß und 32 hoch. Erkunden, Basispflege, Kistenpflege und Tiere holen brauchen ihren Bereich; Felder und Tiere bevorzugen einen. Bereiche begrenzen Arbeitsziele, nicht die Bewegung. Ändern oder Löschen eines Bereichs hält die aktive Daueraufgabe an.

</details>

### Selbstständig Survival spielen

Mit `@ Spiel selbstständig bis zur Eisenrüstung` arbeitet sich der Begleiter vom ersten Werkzeug bis zum Eisenset mit Schild und Eisenspitzhacke hoch. `@ Spiel selbstständig bis zum Enderdrachen` geht weiter über Nether, Festung und End. Vor dem End rüstet er sich wie ein Spieler aus: volle Diamantrüstung, Schwert und Spitzhacke, ein Bogen mit mindestens 32 Pfeilen für die Endkristalle, ein Wassereimer, Baublöcke und selbst gebraute Tränke (Feuerresistenz, Stärke, Heilung, Sanfter Fall). Nach dem Drachen sucht er das Ausgangsportal.

`@ Spielziel` zeigt den aktuellen Schritt; `@ Spielziel pausieren`, `@ Spielziel weiter` und `@ Spielziel abbrechen` steuern es. Nach dem Neuladen plant er aus Inventar und Welt neu. Nach einem Tod startet er an seiner Basis neu, wenn sie geladen und sicher ist (sonst mit dem Kern wiederbeleben), und holt seine Ausrüstung zurück, sobald es sicher ist.

### Eigeninitiative

- **Für dich:** reicht dir Essen, wenn du Hunger und nichts dabei hast, warnt vor Creepern hinter dir, greift bei wenig Leben die Monster an, die dich verfolgen, und gibt dir im Notfall einen goldenen Apfel. Nach deinem Tod sammelt er deine Sachen ein und gibt sie dir zurück.
- **Nachts:** kocht und schmilzt im Haus, repariert Werkzeug, stellt Fackeln her, lagert Überschuss ein und sortiert die Heimatkiste.
- **Gewohnheiten:** nach der dritten Bestellung desselben Items bietet er an, es auf Vorrat zu halten (`@ ja` / `@ nein`).
- **Basis:** leuchtet dunkle Stellen ums Haus aus, legt ein Weizenfeld an und arbeitet nach dem Drachen auf volle Diamantausrüstung hin.
- **Meldungen:** meldet gespürte Diamanten und ein volles Lager und bittet nach drei gescheiterten Versuchen am selben Ziel um Hilfe.

`@ Sei leiser` / `@ Sprich mehr` steuern, wie oft er redet; auf *leise* bleiben nur Warnungen.

## Bauen

Die Seite **Bauen** zeigt Karten mit texturierten 3D-Vorschaubildern: Häuser (Holz, Stein oder Birke in drei Größen), die Weizenfarm, **24 Farmen und Maschinen**, drei Lager-Entwürfe (ein Zentrallager für 128 Materialien mit Sortierung und Itemabruf sowie kompakte Survival-Kistenräume für 12 und 24 Materialien) und deine eigenen Schematic-Dateien.

1. Eine Karte wählen und in der 3D-Ansicht ansehen (ziehen dreht, Mausrad zoomt).
2. **Platzieren** zeigt eine durchscheinende Vorschau in der Welt, die dem angezielten Block folgt.
3. **R** dreht, **Bild ↑ / Bild ↓** hebt oder senkt, **M** wechselt die Bau-Bewegung, **Enter** startet, **Rücktaste** bricht ab.

Rote Vorschaublöcke sind Hindernisse. Abgetragen wird nur natürliches Gelände, Lücken unter dem Fundament werden bis sechs Blöcke tief aufgefüllt. Im Materialmodus **Survival** kostet jeder Block ein Item – der Begleiter beschafft Fehlendes oder fragt dich. **Kreativ** steht Kreativspielern, Admins und dem Besitzer der Einzelspielerwelt offen.

Schematics (`.litematic`, `.schem`, `.nbt`, MCEdit-`.schematic`) gehören in den Ordner `schematics` des Spielverzeichnisses oder nach `config/worldedit/schematics`. Dateien aus älteren Versionen werden wie alte Welten umgerechnet; Schildtexte und Crafter-Sperren bleiben erhalten, Behälterinhalte nur im Kreativ-Materialmodus, Befehle in Befehlsblöcken nie.

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

Tieranlagen erzeugen keine Tiere; führe sie selbst hinein oder nutze **Tiere zur Weide holen**. Jeder Plan geht auch mit `/begleiter bauplan <id>`, z. B. `/begleiter bauplan kuhfarm`. Ausführlich: [Farmen und Maschinen](docs/farmen-und-maschinen.md).

</details>

## Weitere Werkzeuge

- **Rufpfeife:** aus zwei Kupferbarren und einem Stock. Rechtsklick ruft den nächsten eigenen geladenen Begleiter im Umkreis von 128 Blöcken; Schleichen + Rechtsklick wechselt zwischen Folgen und Warten.
- **Wegpunktkarte:** Die Seite **Wegpunkte** zeigt die aktuelle Dimension. Ziehen verschiebt, Mausrad zoomt, ein Doppelklick auf einen Marker schickt den Begleiter dorthin.
- **Slash-Befehle:** `/begleiter` (Deutsch) und `/companion` (Englisch) steuern deinen nächsten eigenen Begleiter innerhalb von 64 Blöcken.

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
- `lager` setzt die angesehene Kiste oder das angesehene Fass (bis 6 Blöcke entfernt) als Lager.
- `projekt` zeigt eine Vorschau für ein kleines Holzhaus (Schritte, Material, Standortwarnungen), ohne es zu bauen.
- `/companion parkourtest` (Operatoren) baut einen großen Testparcours und **ersetzt viele Blöcke** – nur in Testwelten.

</details>

## Einstellungen

| Datei | Was sie steuert |
| --- | --- |
| `config/begleiter.json` | Servereinstellungen, z. B. `"realFurnaces": true`, damit er in echten Ofenslots statt virtuell schmilzt |
| `config/begleiter-intelligence.json` | **Intelligence 2.0** – das zusätzliche Entscheidungssystem (standardmäßig an). Admins schalten es auf der Seite **Debug**; `-Dbegleiter.intelligence.enabled=false` hat beim Start Vorrang |
| `config/begleiter-llm.json` | Die optionale LLM-Anbindung (standardmäßig aus) |

<details>
<summary><strong>Optionale LLM-Anbindung</strong></summary>

Eingeschaltet gehen Sätze, die die feste Erkennung nicht versteht, an ein Sprachmodell. Es kann **nur vorhandene Aufträge auslösen** – jedes Item, Tier, jede Menge und jeder Wegpunkt wird geprüft, Erfundenes verworfen –, fragt bei Unklarheit nach oder plaudert.

| Du schreibst | Der Begleiter |
| --- | --- |
| `@ Ich hätte gern was von dem grauen Metall` | „Alles klar, ich hole dir 16 Eisen.“ |
| `@ Ich könnte echt 3 Diamantschwerter gebrauchen` | stellt drei Diamantschwerter her |
| `@ Hast du was zum Leuchten dabei?` | „Ja, ich habe 5 Fackeln.“ |

**Datenschutz:** Nur unverstandene Sätze werden gesendet, beim Plaudern mit einem kurzen Zustandsbericht (Modus, Leben, Hunger, Aufträge, Inventar, nahe Gefahren, Wegpunktnamen) ohne Koordinaten. Der API-Schlüssel bleibt in deiner Konfiguration und erscheint in keinem Protokoll. Auf einem Server gilt der Schlüssel des Servers für alle.

**Einrichtung:** einen API-Schlüssel anlegen (voreingestellt ist [Groq](https://console.groq.com); jeder kompatible Chat-Completions-Dienst geht), Minecraft einmal starten, den Schlüssel unter `apiKey` in `config/begleiter-llm.json` eintragen und `/begleiter llm an` ausführen. `/begleiter llm test <satz>` zeigt, wie ein Satz verstanden würde, ohne dass etwas passiert.

| Feld | Standard | Bedeutung |
| --- | --- | --- |
| `enabled` | `false` | Schalter |
| `apiKey` | leer | Ohne Schlüssel bleibt alles aus |
| `baseUrl` | `https://api.groq.com/openai/v1` | Kompatibler Dienst; `http://` nur für `localhost` |
| `intentModel` / `chatModel` | `openai/gpt-oss-20b` | Aufträge / freie Antworten |
| `timeoutMs` | `8000` | Wartezeit je Anfrage |
| `maxRequestsPerMinute` / `maxRequestsPerDay` | `6` / `500` | Eigene Grenzen der Mod |

Bei Zeitüberschreitung, Verbindungsfehler oder erschöpftem Kontingent greift die feste Erkennung. Das selbstständige Spielen nutzt das LLM nie.

</details>

## FAQ und Grenzen

<details>
<summary><strong>Läuft er auf Servern?</strong></summary>

Ja, aber Einzelspieler und LAN sind die getesteten Hauptmodi, Dedicated Server sind Beta. Server und alle Spieler brauchen die Mod. Ein Dedicated Server kennt nur englische Itemnamen, die eingebauten deutschen Chat-Wörter und Item-IDs, weil die Mod keine Minecraft-Übersetzungen mitliefert.

</details>

<details>
<summary><strong>Besiegt er wirklich den Enderdrachen?</strong></summary>

Jeder Schritt des Weges ist durch automatische Spieltests abgedeckt, auch echter Portalwechsel und das Bergen der Ausrüstung. Ein kompletter Lauf ohne Hilfe in einer frisch erzeugten Welt bis zum besiegten Drachen ist noch nicht gelungen, und lange selbstständige Aufgaben können scheitern.

</details>

<details>
<summary><strong>Kann er meine Welt beschädigen?</strong></summary>

Bauen, Planieren und Schematics verändern die Welt, also vorher sichern. Schutz-Mods werden über dieselbe Abbauprüfung gefragt, die ein Spieler auslöst; anders geschützte Bereiche werden eventuell nicht beachtet. Importierte Redstone-Schaltungen werden nachgebaut, aber nicht als funktionierende Maschine geprüft.

</details>

Einen Fehler gefunden? Bitte ein [Issue](https://github.com/Leak1337/begleiter/issues) mit Minecraft-, Fabric- und Mod-Version und dem Protokoll des Begleiters von der Seite **Diagnose** anlegen.

## Aus dem Quellcode bauen

Benötigt **JDK 25**; die Zielversionen stehen in [gradle.properties](gradle.properties).

```bash
./gradlew assemble test compileGametestJava verifyPackagedMod   # bauen und prüfen
./gradlew runGameTest                                           # Server-Spieltests
./gradlew runClientGameTest                                     # Control-Center-Tests
./gradlew runClient                                             # aus dem Quellcode spielen
```

Unter Windows `.\gradlew.bat` verwenden. Die Mod liegt danach unter `build/libs/begleiter-mc26.3-<version>.jar`. `runGameTest -PtestFilter="begleiter-gametest:<name>*"` startet eine einzelne Testgruppe.

<details>
<summary><strong>Testmap für die Entwicklung</strong></summary>

```bash
./gradlew runTestmap -PtestmapView=ausstellung
```

In der Testmap zeigt `/companion testmap ausstellung` die Farmen und Maschinen, `/companion testmap redstone` die Arbeitsfläche, und `/companion testmap szenario farmen_alle` prüft alle eingebauten Anlagen. `/companion testmap farmfeld` führt zur 128×128-Testwiese; `farmfeld reset`, `farmfeld items` und `farmfeld tiere` bauen sie neu auf oder räumen Gegenstände und Tiere weg. Diese Befehle ersetzen große Bereiche – nur in Testwelten.

</details>

Mehr dazu: [Farmen und Maschinen](docs/farmen-und-maschinen.md) · [Speicherung und Serverende](docs/speicherung-und-serverende.md) · [sichere Teleports](docs/sichere-teleports.md) · [Zentrallager](docs/zentrallager-128.md) · [Survival-Kistenräume](docs/survival-kistenraum-24.md)

## Lizenz

Der eigene Code steht unter **Apache 2.0** – siehe [LICENSE](LICENSE). Drei angepasste Baritone-Bestandteile (`dev.begleiter.nav.NodeHeap` und `dev.begleiter.thirdparty.baritone`) stehen unter **LGPL-3.0-or-later**; siehe [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Jede Mod-JAR enthält die Lizenztexte und ihren baubaren Quellcode in `META-INF/begleiter-sources.zip`.

Nicht mit Mojang oder Microsoft verbunden. Entwickelt von **Leak1337**.

## Lizenz und Namensnennung

Der in diesem Repository von Leak1337 geschriebene Code steht unter der [Apache License 2.0](LICENSE). Wenn du den Code oder eine bearbeitete Fassung weitergibst, lege den Lizenztext bei und übernimm den zutreffenden Herkunftshinweis aus [NOTICE](NOTICE) gemäß Abschnitt 4. Für separat lizenziertes Material gelten dessen eigene Bedingungen.
