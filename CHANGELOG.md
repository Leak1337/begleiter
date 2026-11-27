# Changelog

All notable changes to Begleiter are listed here. Versions follow [Semantic Versioning](https://semver.org/).

[Deutsch](#deutsch)

## [0.2.0] – autonomous survival

Released 2026-11-27.

Requires Minecraft Java Edition 26.3, Fabric Loader 0.19.5 or newer, Fabric API and Java 25. The server **and** every player need the mod. Worlds from 0.1.0 load as before.

### Added

- Potions: the companion builds a brewing stand, fills bottles from a water bucket and brews fire resistance, strength, healing and slow falling with nether wart, the ingredient and blaze powder as fuel. It drinks them when needed: fire resistance when burning or next to lava and blazes in the Nether, healing when badly hurt in a fight, strength against the dragon, slow falling on a deep fall without a water bucket. In the Nether it harvests nether wart and hunts magma cubes for magma cream.

- Dragon preparation for the survival goal: full diamond armor, sword and pickaxe before the End (and before the Nether after a death there), a bow with at least 32 arrows for the end crystals, a water bucket, 32 blocks and the potions it needs. It gives up on diamonds for a while after repeated misses and goes on with iron.

- Survival goal safety: at night it stays in its house or camps where it is instead of running far home or hunting, waits in the morning until monsters are gone, never recovers its gear at night without armor, swims to shore when attacked in water, runs from a hissing creeper even without a target, digs or breaks out when it is stuck, reopens a plugged doorway in the morning and keeps a breeding pair of animals near the house.

- Everything from the menu: the Control Center now offers every chat order as buttons, so nothing has to be typed. New pages: **Orders** (search any item, pick an amount and have it brought, crafted, its ingredients collected or stored in the home chest, plus stock rules with gathering on/off), **Work** (add, pause, resume and remove background jobs, priority, limit, budgets, history, why, schedule on/off, wood zone limit), **Adventure** (survival goal to iron armor or the dragon with status, pause, resume and end; fishing; terrain jobs with mode and size; door; project preview) and **Talk** (status, why, knowledge, where is iron/coal/…, schematics, talk less/more, protect me, yes/no answers, forget memory and a free command line for any order). **Waypoints** can now be saved, visited and forgotten by name, **Combat** has *Protect me*. Buttons run the same server code as the chat order.

- Fishing: `@ go fishing` (or `@ catch 10 fish`) has the companion make or fetch a fishing rod, find a safe shore next to a water surface and fish there. Catches come from the vanilla fishing loot tables with the vanilla wait time, Luck of the Sea and Lure; treasure only comes from open water, as in vanilla, and every catch wears the rod. Walking away, being attacked or the water disappearing loses the cast. When it is hungry, has no food and sees no animals, it goes fishing on its own. Fishing is simulated, no bobber is thrown.

- Habit counters, declined stock suggestions and the areas it has already searched underground are now saved with the world.

- More initiative: the companion now looks after you and its base on its own. While near you it hands you food when you are hungry and carry none, warns about creepers behind you, attacks monsters chasing you first when your health is low and passes you a golden apple in an emergency if it has two. After you die it fetches your dropped items and gives them back. At night in its house it cooks and smelts, repairs tools, makes torches, stores surplus and sorts the home chest instead of just waiting. After the third order for the same item it offers a stock rule (`@ yes` / `@ no`). With iron tools it lights dark spots around the house and lays out a wheat field; after the dragon it works toward full diamond gear. It reports sensed diamonds and a full storage, asks you for help after three failed attempts at the same goal, and at ore depth digs toward areas it has not searched yet.

- Autonomous Survival: `@ play survival to iron armor` has the companion work from its first tools to a full iron set, shield and iron pickaxe; `@ play survival to the dragon` continues through the Nether, the stronghold and the End portal to the Ender Dragon and back through the exit portal. It gathers food and supplies, sets up a home, turns back when supplies run low and replans from its inventory and the world after a reload. After death it respawns at its base when that is loaded and safe, otherwise the core revives it; it then recovers its dropped gear when it is safe. `@ survival goal` shows the current step, `@ pause survival goal`, `@ resume survival goal` and `@ clear survival goal` control it.

- Background jobs: `@ work farm add`, `@ work stock add` and so on run farm, animals, storage, stock, trade, build and explore jobs side by side in their marked zones. Jobs have a priority from 1 to 5 (waiting jobs gain precedence over time), a limit per turn and optional material budgets (`@ work trade budget 12 wheat`). `@ work list` shows pending work, blockers and retry times, `@ work build why` the missing materials and `@ work explore history` recent turns. Player orders always come first; settings, results and retry timers are saved. On dedicated servers, jobs that change the world or storage wait for the owner's permissions.

- Home storage orders: `@ bring 64 glass into storage` tops the home chest up to 64 glass once, using the home chest and chests in the marked storage zone for ingredients and delivering large amounts in batches. `urgent` moves the order ahead, `with charcoal` prefers that fuel, `@ pause` / `@ resume` hold and continue it, and `@ What are you doing?` reports progress or the reason for a pause.

- Stock rules can gather: `@ stock rule torches gather` lets the companion collect sand, naturally grown wood or coal ore for that rule inside the roam zone, checking the way there and back first; `@ stock rule torches local` turns it off. `@ harvest wood only in roam zone` limits wood harvesting for all tasks, `@ clear wood zone limit` lifts it.

- Stock rules: `@ keep 32 torches in stock` sets a target for the home chest (up to eight items). When idle nearby, the companion tops the targets up in turn with carried or crafted items, can craft intermediates such as sticks and smelt at a nearby furnace, and only uses stock above another rule's target as an ingredient. `@ stock rules` lists them, `@ clear stock rule for torches` or `@ clear stock rules` removes them.

- Before a long trip the companion packs what is missing from its home storage: just enough food for the trip, and a suitable pickaxe if its own would not last.

- A recipe order no longer gives up when a workstation is missing or items for the delivery are gone: it plans production again for only the missing amount and keeps what it already made. After three failed attempts it pauses with a reason instead.

- A companion that was working far from any player when the world was saved now continues after a restart: the world remembers where working companions were and briefly loads those chunks on start, so the companion wakes up and keeps its own chunks loaded again. Companions that died, were removed or have no work left are forgotten.

- The companion visibly handles things: chest and barrel lids really open while it stores, takes or sorts and close again shortly after; at a furnace it reaches in with smoke and crackling; items it throws away fly from its hand; handing items over and feeding animals show a hand motion, it looks at the target, and fed animals show food crumbs.

- A working companion keeps the chunks around itself loaded, like a thrown ender pearl. It can walk into and keep working in areas no player is near, instead of freezing at the edge of the loaded world. It only does this while it has tasks or a duty, is not paused and no player or forced chunk already keeps its surroundings running; the ticket is not saved and expires on its own.

### Changed

- The Control Center has a new look: header with page title, mode badge, health and hunger bars, a scrollable sidebar with all pages, rounded buttons with soft gradients, grouped cards on the overview and the companion's last answer shown as a speech bubble.

- Internal: the companion's code is split into smaller parts (mode, tick, orders, log, inventory, storage, gathering and more). Behavior and saved worlds are unchanged.

- When the companion takes items from the home chest for other work, it leaves the amounts set by stock rules in place.

- For orders and stock rules the companion only uses furnaces that are empty and not burning, so it no longer interferes with your own smelting.

- A recipe order that stalls for 20 seconds now pauses with the reason instead of being cancelled, so `@ continue` picks it up again.

- Planning is much faster: looking for chests and workstations skips whole world sections that cannot contain one, and a plan searches the area for chests only once instead of once per ingredient. In a test with five companions, a plan now takes under a millisecond on average instead of about 14 ms, and at most one companion starts planning on its own per tick, so several companions no longer cause a stutter together.

- The companion never changes the world where its owner could not: it stays inside the build height and world border, respects spawn protection, changes nothing while the owner is in adventure mode (spectating is fine), and asks protection mods through the same block-breaking check a player triggers. Refused changes cost no items and give no loot; the log names the spot.

- When gathering resources on its own, the companion now proves a safe way to an ore or animal and back before it goes, using the same check as for chest trips. Targets it cannot reach and return from safely are skipped one by one for a while instead of cancelling the whole plan; sensed ores go through the same check. Orders from players are unchanged.

- Loading a companion follows one fixed resume order and resolves contradictions instead of continuing blindly: a queued build whose plan could not be read is removed and the following orders are held, duplicate build orders are merged, and discarded orders or data from a newer mod version are shown in the menu, not only in the log. Companion data now carries a format version.

- Schematic files are read in the background instead of on the server thread, so large files no longer freeze the server. The companion confirms right away and starts building once the file is read. A result that arrives after a new order, the companion's death, a change of owner or world, or a server stop is discarded. Missing or oversized files are still reported immediately.

- The companion's memory keeps a spatial index by dimension and chunk. Looking up the nearest known ore, tree, chest or danger only checks nearby chunks instead of every remembered place; results are unchanged.

- The remaining building material is counted once per build and kept for up to ten seconds; placed blocks are deducted right away. Menus, chat and the builder share that count instead of walking the whole plan each time, which keeps large builds with an open menu smooth.

- What the companion treats as junk, trash, precious or always-keep is now defined by item tags (`begleiter:junk`, `begleiter:trash`, `begleiter:precious`, `begleiter:keep`), so datapacks can adjust it. The defaults match the previous lists.

- The companion remembers crafting tables, furnaces, anvils and other workstations it has found or placed, and goes back to them without searching the area again. Removed stations are forgotten, unreachable ones skipped, and all anvil states are searched in one pass.

- Planning what to gather is cheaper: the companion first checks places it remembers, scans the area only when that is not enough, verifies at most 12 paths per plan, and avoids targets that turned out unreachable for a minute.

- Server settings in `config/begleiter.json`: chat addressing (always, automatic for a lone singleplayer, or never) and its prefix, talkativeness, ore sense on/off with radius and diamond boost, the navigation time budget per tick and the gathering radius. Values are kept within safe limits, a broken file never replaces the running settings, and operators reload them with `/companion config reload`.

- Talkativeness: by default the companion now reports plan updates at most once a minute and never repeats one; "be quieter" / "talk more" (German: „sei leiser“ / „sprich mehr“) switches between quiet, normal and chatty per companion and is saved. Answers and problems are always shown. Build progress reports follow the same setting: quiet hides them, normal and chatty show them.

- When a companion dies, its memories are stored with the world and the dropped core only carries a small reference. Reviving reads them back and removes the stored entry only once the companion is really back; a core without matching memories (another world, a duplicate) is kept with a clear message. Old cores still work.

- Tool quality is judged by what a tool can do instead of its name: pickaxes by which blocks they can mine with drops, other tools and swords by durability. Modded tools in the matching item tags are recognised; the order of vanilla tools is unchanged.

- Crafting always uses the real recipes of the world, including datapack recipes: ingredients, results and leftovers such as empty buckets are booked exactly as at a crafting table. A door can be made from any wood the companion has instead of always asking for oak. What the companion smelts on its own is defined by the item tag `begleiter:auto_smelt` (raw ores, raw meat and fish by default), and the result always comes from the real furnace recipe.

### Fixed

- In the Nether the companion no longer looks for crafting table and furnace spots at the overworld coordinates of its house.
- A door no longer fails when the companion has mixed planks: it turns logs of one wood into planks until that wood alone is enough, and leaves other planks and logs alone.
- Searches, line-of-sight and hazard checks no longer load chunks synchronously. Unloaded blocks count as unknown: searches skip them, the companion cannot see through them, and hazard checks assume the worse case.
- Sorting chests can no longer lose items. The companion plans the new layout from copies, checks it before writing, writes only the slots that change and reads the result back. If a container refuses or shortens a stack, the previous contents are restored. If even that fails, the missing items are dropped next to the companion and the failure is reported. Containers with a smaller stack limit are filled within that limit. If everything only fits without category boundaries, categories share chests; if it does not fit at all, nothing is changed.
- A finished build is no longer paused as "not verified" because the world changed it: grass or mycelium spreading onto planned dirt, dirt under covered grass, and plain rails that a redstone switch or a neighbouring rail reshaped now count as normal operation instead of build errors.

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

## [0.2.0] – selbstständig Survival spielen

Veröffentlicht am 27.11.2026.

Benötigt Minecraft Java Edition 26.3, Fabric Loader 0.19.5 oder neuer, Fabric API und Java 25. Der Server **und** jeder Spieler brauchen die Mod. Welten aus 0.1.0 laden wie bisher.

### Hinzugefügt

- Tränke: Der Begleiter stellt einen Braustand auf, füllt Flaschen am Wassereimer und braut Feuerresistenz, Stärke, Heilung und langsamen Fall mit Netherwarze, Zutat und Lohenstaub als Brennstoff. Getrunken wird bei Bedarf: Feuerresistenz beim Brennen oder an Lava und Lohen im Nether, Heilung bei wenig Leben im Kampf, Stärke gegen den Drachen, langsamer Fall bei tiefem Sturz ohne Wassereimer. Im Nether erntet er Netherwarzen und jagt Magmawürfel für Magmacreme.

- Drachenvorbereitung beim Spielziel: volle Diamantrüstung, Diamantschwert und -spitzhacke vor dem End (nach einem Tod im Nether schon vorher), Bogen mit mindestens 32 Pfeilen für die Endkristalle, Wassereimer, 32 Blöcke und die nötigen Tränke. Findet er wiederholt keine Diamanten, macht er eine Weile mit Eisen weiter.

- Sicherheit beim Spielziel: Nachts bleibt er im Haus oder schlägt ein Lager auf, statt weit heimzurennen oder zu jagen, wartet morgens, bis Monster weg sind, holt seine Sachen nachts ohne Rüstung nicht, schwimmt angegriffen im Wasser ans Ufer, flieht vor zischenden Creepern auch ohne Ziel, gräbt oder bricht sich frei, wenn er festsitzt, öffnet morgens eine zugemauerte Tür wieder und hält ein Zuchtpaar am Haus.

- Alles über das Menü: Das Control Center bietet jetzt jeden Chatauftrag als Knopf, nichts muss mehr getippt werden. Neue Seiten: **Aufträge** (beliebiges Item suchen, Menge wählen und bringen, herstellen, Zutaten sammeln oder in die Heimatkiste einlagern lassen, dazu Vorratsregeln mit Sammeln an/aus), **Arbeit** (Hintergrundjobs hinzufügen, pausieren, fortsetzen und entfernen, Priorität, Limit, Budgets, Verlauf, Warum, Arbeitsplan an/aus, Holzgebiet), **Abenteuer** (Survival-Ziel bis Eisenrüstung oder Drache mit Stand, Pause, Weiter und Beenden; Angeln; Geländearbeiten mit Art und Größe; Tür; Projektvorschau) und **Gespräch** (Status, Warum, Wissen, wo ist Eisen/Kohle/…, Baupläne, leiser/gesprächiger, Beschütze mich, Ja/Nein-Antworten, Gedächtnis löschen und eine freie Befehlszeile für jeden Auftrag). **Wegpunkte** lassen sich jetzt mit Namen speichern, ansteuern und vergessen, **Kampf** hat *Beschütze mich*. Die Knöpfe nutzen denselben Servercode wie der Chatauftrag.

- Angeln: `@ geh angeln` (oder `@ fang 10 Fische`) lässt den Begleiter eine Angel herstellen oder besorgen, ein sicheres Ufer an einer Wasseroberfläche suchen und dort angeln. Die Beute kommt aus den Vanilla-Angeltabellen mit Vanilla-Wartezeit, Glück des Meeres und Köder; Schätze gibt es wie in Vanilla nur in offenem Wasser, und jeder Fang nutzt die Angel ab. Geht er weg, wird er angegriffen oder verschwindet das Wasser, ist der Wurf verloren. Hat er Hunger, nichts zu essen und keine Tiere in Sicht, geht er selbst angeln. Das Angeln ist simuliert, es fliegt kein Schwimmer.

- Gewohnheitszähler, abgelehnte Vorratsvorschläge und die unter Tage schon abgesuchten Gebiete werden jetzt mit der Welt gespeichert.

- Mehr Eigeninitiative: Der Begleiter kümmert sich jetzt von selbst um dich und seine Basis. In deiner Nähe gibt er dir Essen, wenn du Hunger und nichts dabei hast, warnt vor Creepern hinter dir, greift bei wenig Leben zuerst die Monster an, die hinter dir her sind, und gibt dir im Notfall einen goldenen Apfel, wenn er zwei hat. Nach deinem Tod holt er deine Sachen und gibt sie dir zurück. Nachts im Haus kocht und schmilzt er, repariert Werkzeug, stellt Fackeln her, lagert Überschuss ein und sortiert die Lagerkiste, statt nur zu warten. Nach der dritten Bestellung desselben Items bietet er eine Vorratsregel an (`@ ja` / `@ nein`). Mit Eisenwerkzeug leuchtet er dunkle Stellen ums Haus aus und legt ein Weizenfeld an; nach dem Drachen arbeitet er auf eine komplette Diamantausrüstung hin. Er meldet gespürte Diamanten und ein volles Lager, bittet nach drei gescheiterten Versuchen am selben Ziel um Hilfe und gräbt auf Erztiefe in Gebiete, die er noch nicht abgesucht hat.

- Selbstständig Survival spielen: Mit `@ Spiel selbstständig bis zur Eisenrüstung` arbeitet sich der Begleiter vom ersten Werkzeug bis zum Eisenset mit Schild und Eisenspitzhacke hoch; `@ Spiel selbstständig bis zum Enderdrachen` führt weiter über Nether, Festung und Endportal bis zum Enderdrachen und durch das Ausgangsportal zurück. Er sammelt Essen und Vorräte, baut ein Zuhause, kehrt bei knappen Vorräten um und plant nach dem Neuladen aus Inventar und Welt neu. Nach einem Tod startet er an seiner Basis neu, wenn sie geladen und sicher ist, sonst belebt ihn der Kern wieder; danach birgt er unter sicheren Bedingungen seine Ausrüstung. `@ Spielziel` zeigt den aktuellen Schritt, `@ Spielziel pausieren`, `@ Spielziel weiter` und `@ Spielziel abbrechen` steuern ihn.

- Dienste im Hintergrund: `@ Dienst Feld hinzufügen`, `@ Dienst Vorrat hinzufügen` usw. lassen Feld, Tiere, Lager, Vorrat, Handel, Bau und Erkundung nebeneinander in ihren markierten Zonen laufen. Dienste haben eine Priorität von 1 bis 5 (wartende kommen mit der Zeit dran), ein Limit pro Einsatz und optionale Materialbudgets (`@ Dienst Handel Budget 12 Weizen`). `@ Dienste` zeigt offene Arbeit, Hindernisse und Wartezeiten, `@ Dienst Bau warum` fehlende Baustoffe und `@ Dienst Erkundung Verlauf` die letzten Einsätze. Spieleraufträge haben immer Vorrang; Einstellungen, Ergebnisse und Wartezeiten werden gespeichert. Auf Dedicated Servern warten Dienste, die Welt oder Lager verändern, auf die Berechtigung des Besitzers.

- Lageraufträge: `@ Bring mir 64 Glas ins Lager` füllt die Heimatkiste einmalig auf 64 Glas auf. Zutaten kommen aus der Heimatkiste und Kisten im markierten Lagerbereich, große Mengen bringt er in Etappen. `Dringend` zieht den Auftrag vor, `mit Holzkohle` bevorzugt diesen Brennstoff, `@ Pause` / `@ Weiter` halten ihn an und setzen ihn fort, und `@ Was machst du gerade?` zeigt Fortschritt oder Pausengrund.

- Vorratsregeln können sammeln: Mit `@ Vorratsregel Fackeln sammeln` holt der Begleiter für diese Regel Sand, natürlich gewachsenes Holz oder Kohleerz im Freilauf-Bereich und prüft vorher Hin- und Rückweg; `@ Vorratsregel Fackeln lokal` schaltet das ab. `@ Holz nur im Freilauf` beschränkt den Holzabbau für alle Aufgaben, `@ Holzgebiet freigeben` hebt das auf.

- Vorratsregeln: `@ Halte hier immer 32 Fackeln auf Vorrat` setzt ein Ziel für die Lagerkiste (bis zu acht Gegenstände). Wenn er dort frei ist, füllt der Begleiter die Ziele der Reihe nach mit mitgebrachten oder hergestellten Gegenständen auf, kann Zwischenprodukte wie Stöcke herstellen und an einem Ofen in Reichweite schmelzen und nimmt als Zutat nur, was über dem Ziel einer anderen Regel liegt. `@ Vorratsregeln` zeigt sie, `@ Vorratsregel Fackeln löschen` oder `@ Vorratsregeln löschen` entfernt sie.

- Vor einer langen Reise packt der Begleiter Fehlendes aus seinem Lager ein: gerade genug Essen für die Reise und eine passende Spitzhacke, wenn seine eigene nicht reicht.

- Ein Rezeptauftrag bricht nicht mehr ab, wenn eine Werkbank oder ein Ofen fehlt oder Gegenstände für die Lieferung fehlen: Er plant nur die fehlende Menge neu und behält, was schon hergestellt ist. Nach drei Fehlversuchen pausiert er mit Begründung.

- Ein Begleiter, der beim Speichern fern von jedem Spieler arbeitete, macht nach einem Neustart weiter: Die Welt merkt sich, wo arbeitende Begleiter waren, und lädt diese Chunks beim Start kurz, sodass der Begleiter aufwacht und seine Chunks wieder selbst geladen hält. Gestorbene, entfernte oder arbeitslose Begleiter werden vergessen.

- Der Begleiter hantiert sichtbar: Kisten- und Fassdeckel öffnen sich echt, während er einlagert, entnimmt oder sortiert, und schließen kurz danach wieder; am Ofen greift er mit Rauch und Knistern hinein; weggeworfene Items fliegen aus seiner Hand; beim Abgeben und Füttern bewegt er den Arm, schaut zum Ziel, und gefütterte Tiere zeigen Futterkrümel.

- Ein arbeitender Begleiter hält die Chunks um sich geladen, wie eine geworfene Enderperle. Er kann so in Gegenden gehen und dort weiterarbeiten, in deren Nähe kein Spieler ist, statt am Rand der geladenen Welt einzufrieren. Das tut er nur, solange er Aufgaben oder eine Daueraufgabe hat, nicht pausiert und kein Spieler oder erzwungener Chunk seine Umgebung schon bewegt; das Ticket wird nicht gespeichert und läuft von selbst ab.

### Geändert

- Das Control Center sieht neu aus: Kopf mit Seitentitel, Modus-Plakette, Leben- und Hungerbalken, eine scrollbare Seitenleiste mit allen Seiten, abgerundete Knöpfe mit sanftem Verlauf, Karten auf der Übersicht und die letzte Antwort des Begleiters als Sprechblase.

- Intern: Der Code des Begleiters ist in kleinere Bausteine aufgeteilt (Modus, Tick, Aufträge, Log, Inventar, Lager, Sammeln und mehr). Verhalten und gespeicherte Welten bleiben gleich.

- Nimmt der Begleiter für andere Arbeit etwas aus der Heimatkiste, lässt er die Mengen der Vorratsregeln dort liegen.

- Für Aufträge und Vorratsregeln nutzt der Begleiter nur leere Öfen, die nicht brennen, und kommt deinem eigenen Schmelzen nicht mehr in die Quere.

- Ein Rezept-Auftrag, der 20 Sekunden lang feststeckt, pausiert jetzt mit Begründung, statt abgebrochen zu werden; `@ weiter` setzt ihn fort.

- Planen ist deutlich schneller: Die Suche nach Kisten und Arbeitsstationen überspringt ganze Weltabschnitte, in denen keine sein können, und eine Planung sucht die Umgebung nur noch einmal statt für jede Zutat nach Kisten ab. Im Test mit fünf Begleitern dauert eine Planung im Schnitt unter einer Millisekunde statt etwa 14 ms, und pro Tick beginnt höchstens ein Begleiter von sich aus zu planen, damit mehrere zusammen nicht ruckeln.

- Der Begleiter verändert die Welt nur dort, wo sein Besitzer es dürfte: innerhalb von Bauhöhe und Weltgrenze, mit Spawn-Schutz, im Abenteuermodus des Besitzers gar nicht (Zuschauen ist kein Hindernis), und Schutz-Mods werden über dieselbe Abbauprüfung gefragt wie bei einem Spieler. Abgelehnte Änderungen kosten nichts und bringen keine Beute; das Protokoll nennt die Stelle.

- Bei eigener Beschaffung weist der Begleiter vor dem Losgehen einen sicheren Hin- und Rückweg zu Erz oder Tier nach, mit derselben Prüfung wie beim Kistengang. Ziele ohne sicheren Rückweg überspringt er einzeln eine Weile, statt den ganzen Plan abzubrechen; gespürte Erze laufen durch dieselbe Prüfung. Aufträge von Spielern bleiben unverändert.

- Beim Laden nimmt der Begleiter seine Arbeit in fester Reihenfolge wieder auf und löst Widersprüche auf, statt blind weiterzumachen: Ein Bauauftrag, dessen Plan unlesbar war, wird entfernt und die folgenden Aufträge halten an; doppelte Bauaufträge werden zusammengelegt; verworfene Aufträge und Daten aus einer neueren Mod-Version stehen im Menü, nicht nur im Protokoll. Begleiterdaten tragen jetzt eine Formatversion.

- Hergestellt wird immer mit den echten Rezepten der Welt, auch mit Datapack-Rezepten: Zutaten, Ergebnis und Reste wie leere Eimer werden genau wie an der Werkbank gebucht. Eine Tür baut er aus dem Holz, das er hat, statt immer Eiche zu verlangen. Was er von selbst schmilzt, bestimmt das Item-Tag `begleiter:auto_smelt` (Vorgabe: Roherze, rohes Fleisch und Fisch); das Ergebnis kommt immer aus dem echten Ofenrezept.

- Schematic-Dateien werden im Hintergrund statt im Serverthread gelesen; große Dateien lassen den Server nicht mehr stocken. Der Begleiter bestätigt sofort und beginnt zu bauen, sobald die Datei gelesen ist. Ein Ergebnis, das erst nach einem neuen Auftrag, seinem Tod, einem Besitzer- oder Weltwechsel oder einem Serverstopp ankommt, wird verworfen. Fehlende oder zu große Dateien werden weiterhin sofort gemeldet.

- Das Gedächtnis des Begleiters führt einen räumlichen Index nach Dimension und Chunk. Die Suche nach dem nächsten bekannten Erz, Baum, der nächsten Kiste oder Gefahr prüft nur noch Chunks in der Nähe statt aller gemerkten Orte; die Ergebnisse bleiben gleich.

- Das restliche Baumaterial wird einmal pro Bau gezählt und bis zu zehn Sekunden gemerkt; gesetzte Blöcke werden sofort abgezogen. Menü, Chat und Bau teilen diese Zählung, statt jedes Mal den ganzen Plan durchzugehen; große Bauten mit offenem Menü laufen dadurch flüssig.

- Was der Begleiter als Müll, wegwerfbar, wertvoll oder immer behalten ansieht, legen jetzt Item-Tags fest (`begleiter:junk`, `begleiter:trash`, `begleiter:precious`, `begleiter:keep`); Datapacks können das anpassen. Die Vorgaben entsprechen den bisherigen Listen.

- Der Begleiter merkt sich gefundene oder selbst aufgestellte Werkbänke, Öfen, Ambosse und andere Arbeitsstationen und geht wieder hin, ohne die Umgebung erneut abzusuchen. Abgebaute Stationen vergisst er, unerreichbare überspringt er; alle Ambosszustände sucht er in einem Durchgang.

- Das Planen beim Sammeln ist günstiger: Der Begleiter prüft zuerst gemerkte Orte, durchsucht die Umgebung nur, wenn das nicht reicht, prüft höchstens 12 Wege pro Planung und meidet Ziele, die sich als unerreichbar erwiesen haben, eine Minute lang.

- Server-Einstellungen in `config/begleiter.json`: Ansprache im Chat (immer, automatisch im Einzelspieler allein oder nie) samt Präfix, Gesprächigkeit, Erz-Gespür an/aus mit Radius und Diamant-Verstärkung, Navigationsbudget pro Tick und Sammelradius. Werte bleiben in sicheren Grenzen, eine fehlerhafte Datei ersetzt nie die laufenden Einstellungen, Operatoren laden sie mit `/companion config reload` neu.

- Gesprächigkeit: Standardmäßig meldet der Begleiter Planschritte höchstens einmal pro Minute und wiederholt keine; „sei leiser“ / „sprich mehr“ schaltet pro Begleiter zwischen leise, normal und gesprächig und wird gespeichert. Antworten und Probleme erscheinen immer. Baufortschritt richtet sich ebenfalls danach: leise blendet ihn aus, normal und gesprächig zeigen ihn.

- Stirbt ein Begleiter, werden seine Erinnerungen mit der Welt gespeichert; der Kern trägt nur noch eine kleine Referenz. Beim Wiederbeleben werden sie zurückgelesen und erst entfernt, wenn der Begleiter wirklich zurück ist; ein Kern ohne passende Erinnerungen (andere Welt, Duplikat) bleibt mit einer klaren Meldung erhalten. Alte Kerne funktionieren weiter.

- Die Werkzeugqualität richtet sich nach dem, was ein Werkzeug kann, statt nach seinem Namen: Spitzhacken danach, welche Blöcke sie mit Beute abbauen, andere Werkzeuge und Schwerter nach Haltbarkeit. Modwerkzeuge in den passenden Item-Tags werden erkannt; die Reihenfolge der Vanilla-Werkzeuge bleibt gleich.

### Behoben

- Im Nether sucht der Begleiter Plätze für Werkbank und Ofen nicht mehr an den Oberwelt-Koordinaten seines Hauses.
- Eine Tür scheitert nicht mehr an gemischten Brettern: Der Begleiter macht aus Stämmen einer Holzart so viele Bretter, bis diese Sorte allein reicht, und lässt andere Bretter und Stämme liegen.
- Suchen, Sichtprüfungen und Gefahrenprüfungen laden keine Chunks mehr synchron nach. Ungeladene Blöcke gelten als unbekannt: Suchen überspringen sie, der Begleiter sieht nicht hindurch, und Gefahrenprüfungen nehmen das Schlimmere an.
- Beim Kistensortieren gehen keine Items mehr verloren. Der Begleiter plant die neue Aufteilung aus Kopien, prüft sie vor dem Schreiben, schreibt nur geänderte Plätze und liest das Ergebnis zurück. Nimmt ein Container einen Stapel nicht oder nur gekürzt an, stellt er den alten Inhalt wieder her. Gelingt auch das nicht, legt er die fehlenden Items neben sich ab und meldet den Fehler. Container mit kleinerer Stapelgrenze werden innerhalb dieser Grenze gefüllt. Passt alles nur ohne Sortengrenzen, teilen sich Sorten Kisten; passt es gar nicht, bleibt alles unverändert.
- Ein fertiger Bau wird nicht mehr als „nicht bestätigt“ angehalten, weil die Welt ihn verändert hat: Gras oder Myzel auf geplanter Erde, Erde unter bedecktem Gras und einfache Schienen, die eine Redstone-Weiche oder eine Nachbarschiene umgestellt hat, gelten jetzt als normaler Betrieb statt als Baufehler.

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
