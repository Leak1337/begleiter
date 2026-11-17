# Changelog

All notable changes to Begleiter are listed here. Versions follow [Semantic Versioning](https://semver.org/).

[Deutsch](#deutsch)

## [0.1.0] – first public beta

Released 2026-11-17.

Requires Minecraft Java Edition 26.3, Fabric Loader 0.19.5 or newer, Fabric API and Java 25. The server **and** every player need the mod.

### Added

- Eight distinct farm designs: raised beetroot beds, pumpkin patch, melon greenhouse, cowshed, sheep pasture, four-pen piggery, rabbit hutch and chicken coop with automatic egg collection. Built-in plans selected in the bot menu now show a movable world preview with rotation, height adjustment and explicit build confirmation.

- **Companion "Leak":** crafted from a Companion Core (iron, gold, redstone, diamond) and placed with a right-click. It drops its core on death; the core revives it with its saved knowledge.
- **Modes:** follow, wait and play on its own; right-click toggles between following and playing on its own.
- **Control Center** (Shift + right-click): overview, controls, inventory, equipment, combat, status, debug, build and diagnostics pages. The language follows the Minecraft setting (German or English).
- **Chat orders in English and German:** the companion reacts to messages that address it (`@`, its name, "Leak", "Begleiter", or "Bot" at the start) – gathering, crafting, building, farming, trading, terraforming, waypoints, combat stance, storage and questions about its knowledge.
- **Slash commands:** `/companion` (English) and `/begleiter` (German) for modes, gathering, building, schematics, storage and the log.
- **Needs and planning:** eight needs with an urgency of 0–100; goal planning across inventory, known chests, mining, smelting, crafting and mob drops.
- **Intelligence 2.0:** additional decision system, enabled by default, switchable server-wide by admins.
- **Navigation:** own A* pathfinding with replanning, swimming, ladders and doors; mines obstacles, builds bridges, steps and pillars and simulates jumps.
- **Survival and combat:** eating, hunting and cooking; three combat stances, melee, bow and shield; avoids lava, fire and dangerous falls.
- **Everyday jobs:** harvesting and replanting, breeding, repairing tools, trading with villagers, setting up and sorting storage.
- **Building:** houses in three sizes and three materials, a wheat farm, walls, platforms, terraforming and schematics (`.litematic`, `.schem`, `.nbt`) with self-gathered materials.
- **Memory:** remembers ores, trees, water, lava, chests, portals, spawners, villages and hazards.
- **Progression:** chain up to the Nether portal, blaze rods, ender pearls, stronghold, End portal and dragon fight – without a guarantee of success.
- **Debug tools** for admins: F7 HUD and F8 debug menu.
- **Optional LLM connection (off by default):** with a [Groq](https://console.groq.com) API key or any OpenAI-compatible service, sentences the fixed recognition does not understand go to a language model. It can only pick existing chat orders (every value is checked), asks back when an order is unclear, and answers small talk and questions using a short status report of the companion. Set up in `config/begleiter-llm.json`, controlled with `/companion llm` and `/begleiter llm` (`status`, `on`/`off`, `reload`, `test`). Own limits per minute and day protect your quota.
- **Ongoing duties and work zones:** fields, animals, exploring, base care, chest care, bringing animals to the pen and trading, with zones drawn on a local map.
- **Recipe orders for you:** obtain ingredients or craft fresh items and deliver them, including stonecutting, smithing upgrades and compatible mod/data-pack recipes; follow-up questions continue the same order.
- **Separate building material and movement modes:** Survival/Creative and Normal, Fly or Fly/Teleport.
- **Replies in English and German:** chat replies, Control Center, status, log, plan reasons and debug displays follow the Minecraft language setting.
- **Three bundled storage designs:** a 128-material central storage with sorting and retrieval, and compact survival chest rooms for 12 and 24 materials.

### Known limitations

- Singleplayer and LAN are the tested main modes; **dedicated servers are beta**.
- Long autonomous tasks and the path to the End can fail.
- Protected areas from other mods are not respected.
- Build orders, flattening and schematics change the world – back up first.
- Schematics: size limits apply. Container contents are only copied in Creative material mode; commands in command blocks are never copied.
- Redstone circuits are rebuilt but not verified to work.
- Optional LLM: answers can be imprecise; without internet or once a limit is reached the companion falls back to its fixed recognition. With the connection on, unrecognized sentences (and for small talk a short status report without coordinates) are sent to the configured service.
- The mod ships no Minecraft translations: German item names come from the installed game in singleplayer and LAN; a dedicated server knows English names, the built-in German chat words and item IDs.
- This is a first beta: automated tests cover the server, client menus and a dedicated-server start, but broad playtesting is still ahead. Please report problems as issues.

---

## Deutsch

## [0.1.0] – erste öffentliche Beta

Veröffentlicht am 17.11.2026.

Benötigt Minecraft Java Edition 26.3, Fabric Loader 0.19.5 oder neuer, Fabric API und Java 25. Der Server **und** alle Spieler brauchen die Mod.

### Neu

- Acht unterschiedliche Farmen: Rüben-Hochbeete, Kürbisgarten, Melonen-Gewächshaus, Kuhstall, Schafweide, vier Schweinebuchten, Kaninchenstall und Hühnerstall mit Eiersammlung. Eingebaute Pläne aus dem Bot-Menü erscheinen zunächst als bewegliche Weltvorschau mit Drehung, Höhenänderung und Baubestätigung.

- **Begleiter „Leak“:** wird aus einem Begleiter-Kern (Eisen, Gold, Redstone, Diamant) hergestellt und per Rechtsklick gesetzt. Beim Tod lässt er den Kern fallen; der Kern belebt ihn mit seinem gespeicherten Wissen wieder.
- **Modi:** folgen, warten und selbst spielen; Rechtsklick wechselt zwischen Folgen und Selbstspielen.
- **Control Center** (Shift + Rechtsklick): Seiten Übersicht, Steuerung, Inventar, Ausrüstung, Kampf, Status, Debug, Bauen und Diagnose. Die Sprache folgt der Minecraft-Einstellung (Deutsch oder Englisch).
- **Chat-Aufträge auf Deutsch und Englisch:** Der Begleiter reagiert auf Nachrichten, die ihn ansprechen (`@`, sein Name, „Leak“, „Begleiter“ oder „Bot“ am Satzanfang) – Sammeln, Herstellen, Bauen, Landwirtschaft, Handel, Terraforming, Wegpunkte, Kampfhaltung, Lager und Fragen zu seinem Wissen.
- **Slash-Befehle:** `/begleiter` (Deutsch) und `/companion` (Englisch) für Modi, Sammeln, Bauen, Schematics, Lager und Protokoll.
- **Bedürfnisse und Planung:** acht Bedürfnisse mit Dringlichkeit 0–100; Zielplanung über Inventar, bekannte Kisten, Abbau, Schmelzen, Herstellen und Mob-Drops.
- **Intelligence 2.0:** zusätzliches Entscheidungssystem, standardmäßig an, von Admins serverweit schaltbar.
- **Navigation:** eigene A*-Wegsuche mit Neuplanung, Schwimmen, Leitern und Türen; baut Hindernisse ab, baut Brücken, Stufen und Säulen und simuliert Sprünge.
- **Überleben und Kampf:** Essen, Jagen und Braten; drei Kampfhaltungen, Nahkampf, Bogen und Schild; meidet Lava, Feuer und gefährliche Stürze.
- **Alltagsaufgaben:** Ernten und Nachpflanzen, Züchten, Werkzeuge reparieren, Handel mit Dorfbewohnern, Lager einrichten und sortieren.
- **Bauen:** Häuser in drei Größen und drei Materialien, Weizenfarm, Mauern, Plattformen, Terraforming und Schematics (`.litematic`, `.schem`, `.nbt`) mit selbst gesammeltem Material.
- **Gedächtnis:** merkt sich Erze, Bäume, Wasser, Lava, Kisten, Portale, Spawner, Dörfer und Gefahren.
- **Fortschritt:** Kette bis Netherportal, Lohenruten, Enderperlen, Festung, Endportal und Drachenkampf – ohne Erfolgsgarantie.
- **Debug-Werkzeuge** für Admins: F7-HUD und F8-Debugmenü.
- **Optionale LLM-Anbindung (standardmäßig aus):** Mit einem [Groq](https://console.groq.com)-API-Key oder einem anderen OpenAI-kompatiblen Dienst gehen Sätze, die die feste Erkennung nicht versteht, an ein Sprachmodell. Es kann nur vorhandene Chat-Befehle wählen (jeder Wert wird geprüft), fragt bei unklaren Aufträgen nach und beantwortet Smalltalk und Fragen mit einem kurzen Zustandsbericht des Begleiters. Einrichtung in `config/begleiter-llm.json`, Steuerung mit `/begleiter llm` und `/companion llm` (`status`, `an`/`aus`, `neuladen`, `test`). Eigene Limits pro Minute und Tag schützen dein Kontingent.
- **Dauerhafte Aufgaben und Arbeitsbereiche:** Felder, Tiere, Erkunden, Basis, Kisten, Tiere ins Gehege bringen und Handel, mit Bereichen auf einer lokalen Karte.
- **Rezeptaufträge für dich:** Zutaten besorgen oder Gegenstände neu herstellen und liefern, auch Steinsäge, Schmiede-Aufwertungen und passende Rezepte aus Mods/Datenpaketen; Rückfragen setzen denselben Auftrag fort.
- **Getrennte Material- und Bewegungsmodi beim Bauen:** Survival/Kreativ und Normal, Fliegen oder Fliegen/Teleport.
- **Rückmeldungen auf Deutsch und Englisch:** Chat-Antworten, Control Center, Status, Protokoll, Planbegründungen und Debuganzeigen folgen der Minecraft-Sprache.
- **Drei mitgelieferte Lager-Entwürfe:** ein Zentrallager für 128 Materialien mit Sortierung und Itemabruf sowie kompakte Survival-Kistenräume für 12 und 24 Materialien.

### Bekannte Grenzen

- Einzelspieler und LAN sind die geprüften Hauptmodi; **Dedicated Server sind Beta**.
- Lange selbstständige Aufgaben und der Weg ins End können scheitern.
- Schutzgebiete anderer Mods werden nicht beachtet.
- Bauaufträge, Einebnen und Schematics verändern die Welt – vorher sichern.
- Schematics: Größengrenzen. Behälterinhalte nur im Material-Modus Kreativ; Befehle in Befehlsblöcken werden nie übernommen.
- Redstone-Schaltungen werden nachgebaut, ihre Funktion aber nicht geprüft.
- Optionales LLM: Antworten können ungenau sein; ohne Internet oder bei erreichtem Limit fällt der Begleiter auf die feste Erkennung zurück. Bei eingeschalteter Anbindung gehen nicht erkannte Sätze (bei Smalltalk dazu ein kurzer Zustandsbericht ohne Koordinaten) an den eingetragenen Dienst.
- Die Mod liefert keine Minecraft-Übersetzungen mit: Deutsche Itemnamen kommen im Einzelspieler und LAN aus dem installierten Spiel; ein Dedicated Server kennt englische Namen, die eingebauten deutschen Chat-Wörter und Item-IDs.
- Dies ist eine erste Beta: Automatische Tests decken Server, Client-Menüs und einen Dedicated-Server-Start ab, breite Spieltests stehen noch aus. Probleme bitte als Issue melden.
