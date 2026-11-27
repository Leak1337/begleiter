<h1 align="center">⚒️ Begleiter</h1>

<p align="center">
  <strong>A second player for your Minecraft world.</strong><br>
  An NPC companion that takes orders in plain chat, gathers, crafts, builds, fights and can play survival on its own – all by real Minecraft rules.
</p>

<p align="center">
  <a href="https://github.com/Leak1337/begleiter/releases/latest"><img src="https://img.shields.io/github/v/release/Leak1337/begleiter?include_prereleases&label=Download&color=2ea44f" alt="Download"></a>
  <img src="https://img.shields.io/badge/Minecraft-26.3-4CAF50" alt="Minecraft 26.3">
  <img src="https://img.shields.io/badge/Fabric-0.19.5%2B-2196F3" alt="Fabric Loader 0.19.5 or newer">
  <img src="https://img.shields.io/badge/Java-25-F89820" alt="Java 25">
  <img src="https://img.shields.io/badge/License-Apache%202.0-lightgrey" alt="Apache 2.0 license">
</p>

<p align="center">
  <a href="https://github.com/Leak1337/begleiter/releases/download/v0.2.0/begleiter-mc26.3-0.2.0.jar"><strong>Download JAR</strong></a> ·
  <a href="README.de.md"><strong>Deutsch</strong></a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="#talking-to-your-companion">Chat orders</a> ·
  <a href="#control-center">Control Center</a> ·
  <a href="#faq-and-limitations">FAQ</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

---

**Begleiter** (German for *companion*) is a Fabric mod for Minecraft Java Edition 26.3. Instead of a pet you get a companion with its own inventory, tools, memory and planning. Tell it `@ bring me 10 iron` and it works out the rest: find ore, craft a pickaxe, mine, smelt and deliver. Leave it alone and it keeps its base running, looks after you, or plays all the way to the Ender Dragon.

Nothing is spawned or faked. It uses real blocks, recipes, furnaces and chests, finds its own paths and digs, bridges and pillars like a player.

> [!NOTE]
> **Beta.** Singleplayer and LAN are the tested main modes; dedicated servers are beta. Back up your world before large build or terraforming orders.

## Features

| | |
| --- | --- |
| 💬 **Plain chat in English and German** | `@ bring me 10 iron`, `@ craft me 2 stonecutters`, `@ build a large stone house` – no commands to learn. |
| 🧠 **Plans like a player** | Checks inventory and chests, gathers ingredients, crafts intermediates and tools, sets up a crafting table and furnace. |
| 🐉 **Plays survival on its own** | `@ play survival to the dragon`: from wooden tools through iron, diamonds, potions and the Nether to the Ender Dragon. |
| 🤝 **Initiative** | Hands you food, warns about creepers, returns your items after death and works on its base at night. |
| 🏗️ **Builds** | Houses, a wheat farm, 24 farms and machines and your own schematics, with a 3D preview and a movable world preview. |
| 📋 **Background jobs** | Farm, animals, storage, stock, trade, build and explore side by side, with priorities, limits and material budgets. |
| ⚔️ **Survives and fights** | Eats, hunts, cooks, brews potions, uses bow and shield, protects you and avoids lava and deep falls. |
| 💾 **Remembers** | Ores, chests, villages, portals and hazards – across saves and even after death. |
| 🖱️ **Everything by button** | The Control Center offers every chat order as a button. |
| 🤖 **Optional LLM** | Off by default. When enabled it also understands free-form sentences. |

## Quick start

| You need | Version |
| --- | --- |
| Minecraft Java Edition | **26.3** |
| [Fabric Loader](https://fabricmc.net/use/) | **0.19.5** or newer |
| [Fabric API](https://modrinth.com/mod/fabric-api) | for 26.3 |
| Java | **25** or newer |

1. Install the Fabric Loader for Minecraft 26.3.
2. Put **Fabric API** and the [Begleiter JAR](https://github.com/Leak1337/begleiter/releases/download/v0.2.0/begleiter-mc26.3-0.2.0.jar) into your `mods` folder. Older versions and the source code are on the [releases page](https://github.com/Leak1337/begleiter/releases).
3. Start a world and craft a **Companion Core**:

   ```text
   I R I     I = Iron Ingot
   G D G     R = Redstone
   I I I     G = Gold Ingot
             D = Diamond
   ```

4. Right-click a block with the core. Your companion **Leak** appears.
5. Say `@ follow me`, then `@ bring me 16 wood`. Press **B** or **Shift + right-click** the companion to open the Control Center.

On a server, the server **and every player** need the mod. If the companion dies it drops its core; place the core to revive it with its name and knowledge.

## Talking to your companion

The companion only reacts when you **address** it, also in singleplayer:

- Start with `@`, or say **Leak** or **Begleiter** anywhere in the message.
- **Bot** and **Companion** only count at the start (`Bot, follow me`) or as `@Bot`, so normal chat never triggers it.
- A companion's own name addresses exactly that one: `Max, follow me`.

| Category | Examples |
| --- | --- |
| **Modes** | `@ follow me` · `@ wait here` · `@ play on your own` · `@ stop` · `@ continue` |
| **Items** | `@ bring me 10 iron` · `@ make me an iron pickaxe` · `@ craft me 2 stonecutters` · `@ bring me resources for 2 stonecutters` |
| **Building** | `@ build a large stone house` · `@ build a farm` · `@ flatten here 15x15` |
| **Farming** | `@ harvest the field` · `@ breed cows` · `@ go fishing` · `@ catch 10 fish` |
| **Gear and trade** | `@ repair your tools` · `@ gear up` · `@ buy me a book` · `@ sell your stuff` |
| **Storage** | `@ this is your storage` · `@ sort the chests` · `@ keep 32 torches in stock` · `@ bring 64 glass into storage` |
| **Places** | `@ remember this as mine` · `@ go to mine` · `@ forget waypoint mine` |
| **Combat** | `@ be defensive` · `@ protect me` |
| **Survival goal** | `@ play survival to iron armor` · `@ play survival to the dragon` · `@ survival goal` |
| **Questions** | `@ what do you know?` · `@ where is iron?` · `@ why are you doing that?` · `@ status` |

German works the same way, for example `@ Bring mir 10 Eisen` (see [README.de.md](README.de.md)). Item names can be English, German or IDs such as `minecraft:stonecutter`; names from other mods work through their language files.

<details>
<summary><strong>Items, recipes and follow-up questions</strong></summary>

The companion uses its inventory and known chests first, combines partial amounts and obtains the rest. It supports crafting-table, furnace, stonecutter and smithing-upgrade recipes, including compatible mod and data-pack recipes. `@ craft me a netherite sword` works once the ingredients can be obtained; upgrades keep name, enchantments and damage. Brewing orders for you, armor trims and other special recipes are not supported yet.

After `@ craft me armor`, answer `@ iron` to continue the same order. If work pauses after a problem, fix it and say `@ continue`; `@ cancel` discards the order.

</details>

<details>
<summary><strong>Stock rules and storage orders</strong></summary>

`@ keep 32 torches in stock` sets one of up to eight targets for the home chest. While idle nearby, the companion tops the targets up in turn, crafting intermediates such as sticks and smelting at a nearby furnace. It only uses surpluses of other stock items as ingredients. `@ stock rules` shows the counts, `@ clear stock rule for torches` removes one target and `@ clear stock rules` removes all.

`@ stock rule torches gather` lets it collect supplies for that target in the roam zone (accessible sand, naturally grown wood or coal ore, depending on the recipe); it checks the way there and back before longer trips. `@ stock rule torches local` turns gathering off again. `@ harvest wood only in roam zone` limits wood harvesting for all tasks; `@ clear wood zone limit` lifts it.

`@ bring 64 glass into storage` tops the home chest up to 64 glass once, using the home chest and chests in the storage zone for ingredients and delivering large amounts in batches. `@ urgent …` moves the order ahead, `with charcoal` prefers that fuel, `@ pause` / `@ resume` hold and continue it, and `@ what are you doing?` reports progress or the reason for a pause.

</details>

<details>
<summary><strong>Background jobs</strong></summary>

Mark the work areas on the zone map and add jobs, for example `@ work farm add` and `@ work stock add`. **Farm**, **animals**, **storage** and **explore** use the fields, animals, storage and roam zones. **Trade** sells surplus in the base zone, **build** carries out building orders there, and **stock** uses your home chest and stock rules.

| Order | Effect |
| --- | --- |
| `@ work farm priority 5` | Priority 1–5; waiting jobs gain precedence over time |
| `@ work build limit 8` | At most eight placements per turn (other jobs count harvests, feedings, trades or passes) |
| `@ work farm pause` / `resume` / `remove` | Manage one job |
| `@ work off` / `@ work on` | Pause or resume the whole schedule |
| `@ work list` | Pending work, blockers, retry times and results |
| `@ work trade budget 12 wheat` | Use at most twelve wheat per turn (`@ work stock budget` lists budgets) |
| `@ work build why` | Missing materials for the next build positions |
| `@ work explore history` | Recent turns with outcome, progress and duration |

Player orders always come first. Settings, results and retry timers survive saves. If you change a zone or the home chest, add the affected job again to approve it. On dedicated servers, jobs that change the world or storage wait for the owner's permissions.

</details>

## Control Center

Press **B** anywhere in the same dimension or **Shift + right-click** the companion (rebind under *Options → Controls*). The menu follows your Minecraft language. Every chat order is also a button, so you never have to type.

| Page | What you find there |
| --- | --- |
| **Overview** | Health, hunger, mode, current task, inventory and equipment |
| **Tasks** | Basic modes, seven ongoing duties and the zone map |
| **Orders** | Search any item and pick an amount: bring, craft, collect ingredients, store; stock rules |
| **Controls** | Gathering, storage, building and everyday orders |
| **Work** | Background jobs with priority, limit, budgets and history |
| **Adventure** | Survival goal, fishing, terrain jobs, door, project preview |
| **Build** | Houses, farms, machines and schematics with previews and progress |
| **Waypoints** | Waypoint map; save, visit and forget places by name |
| **Inventory** / **Equipment** | The companion's 36 slots and gear; shift-click transfers |
| **Combat** | Target, stance (aggressive, defensive, passive) and *Protect me* |
| **Status** / **Talk** | Needs, base and knowledge; questions, talkativeness and a free command line |
| **Diagnostics** / **Debug** | Log with filter; Intelligence 2.0 on/off for admins |

Right-click the companion to switch between following and playing on its own. Admins and the singleplayer world owner get a debug HUD, debug menu and path overlay on **F7**, **F8** and **F6**.

## What it does on its own

### Ongoing duties

Under **Tasks** you pick one of seven jobs the companion keeps doing until you switch mode: **tend fields**, **tend animals**, **wander and explore**, **look after the base**, **look after chests**, **bring animals to the pen** and **village trade**. Work zones are drawn on a local map (**Tasks → Map & zones**) or marked in the world, up to 64 × 64 blocks each. Nothing is spawned: crops must grow, animals must exist and the area must stay loaded.

<details>
<summary><strong>Map and work zones</strong></summary>

Select **Base**, **Animals**, **Fields**, **Storage** or **Roaming**, drag a rectangle and click **Save**. The mouse wheel zooms and the arrow keys pan; unknown terrain stays dark because the map never loads extra chunks.

**Mark in world** selects two ground corners instead: **Z** marks a corner, **G** saves, **K** returns to the map, **Backspace** cancels. Both corners must be within 96 blocks of you and loaded.

Zones are at most 64 × 64 blocks and 32 high. Exploring, base care, chest care and fetching animals need their zone; field and animal duties prefer one. Zones limit work targets, not movement. Editing or deleting a zone stops the active duty.

</details>

### Autonomous survival

`@ play survival to iron armor` has the companion work from its first tools to a full iron set, shield and iron pickaxe. `@ play survival to the dragon` continues through the Nether, the stronghold and the End. Before the End it prepares like a player would: full diamond armor, sword and pickaxe, a bow with at least 32 arrows for the end crystals, a water bucket, building blocks and potions it brews itself (fire resistance, strength, healing, slow falling). After the dragon it looks for the exit portal.

`@ survival goal` shows the current step; `@ pause survival goal`, `@ resume survival goal` and `@ clear survival goal` control it. After a reload it replans from its inventory and the world. After death it respawns at its base when that is loaded and safe (otherwise revive it with its core) and recovers its gear when it is safe.

### Initiative

- **For you:** hands you food when you are hungry and carry none, warns about creepers behind you, attacks monsters chasing you when your health is low and passes you a golden apple in an emergency. After you die it collects your items and gives them back.
- **At night:** cooks and smelts in its house, repairs tools, makes torches, stores surplus and sorts the home chest.
- **Habits:** after the third order for the same item it offers to keep it in stock (`@ yes` / `@ no`).
- **Base:** lights up dark spots around the house, lays out a wheat field and, after the dragon, works toward full diamond gear.
- **Reports:** tells you about sensed diamonds and a full storage and asks for help after three failed attempts at the same goal.

`@ talk less` / `@ talk more` control how often it speaks; on *quiet* only warnings remain.

## Building

The **Build** page shows cards with textured 3D thumbnails: houses (wood, stone or birch in three sizes), the wheat farm, **24 farms and machines**, three storage designs (a 128-material central storage with sorting and retrieval and compact survival chest rooms for 12 and 24 materials) and your own schematic files.

1. Pick a card and inspect it in the 3D view (drag to rotate, scroll to zoom).
2. **Place** shows a translucent preview in the world that follows the block you aim at.
3. **R** rotates, **Page Up / Page Down** raises or lowers, **M** switches building movement, **Enter** starts and **Backspace** cancels.

Red preview blocks are obstacles. Only natural terrain is cleared, and gaps under the foundation are filled up to six blocks deep. In **Survival** material mode every block costs an item – the companion gathers what is missing or asks you. **Creative** material mode is available to creative players, admins and the singleplayer world owner.

Schematics (`.litematic`, `.schem`, `.nbt`, MCEdit `.schematic`) go into the `schematics` folder of your game directory or `config/worldedit/schematics`. Files from older versions are upgraded like old worlds; sign texts and crafter locks are kept, container contents only in Creative material mode, command-block commands never.

<details>
<summary><strong>Farm and machine catalog</strong></summary>

| Blueprint ID | Layout and operation |
| --- | --- |
| `zuckerrohrfarm`, `bambusfarm` | Observers, pistons and a collection channel; automatic while chunks are loaded |
| `kaktusfarm` | Nine cacti with growth harvesting and a collection channel |
| `wasserernte`, `karottenfarm`, `kartoffelfarm` | Lit fields with water harvesting: water bucket into the dispenser, press the button to harvest and again to retrieve the water, then replant |
| `ruebenfarm` | Two raised beetroot beds with a walkway and covered irrigation |
| `kuerbisfarm` | Open pumpkin patch; harvest fruit, leave the six stems |
| `melonenfarm` | Narrow greenhouse with two stem rows and a harvest aisle |
| `kuhfarm` | Cow paddock with two covered feeding stalls |
| `schaffarm` | Grass pasture with a separate shearing chute |
| `schweinefarm` | Four breeding pens with connecting gates |
| `kaninchenfarm` | Two sleeping hutches and a tall fenced run |
| `huehnerfarm` | Closed coop; eggs drop through carpet into hoppers and a chest |
| `schmelzofen`, `raeucherofen`, `hochofen` | Separate inputs for items and fuel; output at the front |
| `komposter` | Automatic composting with bone-meal output |
| `schaltlager`, `portionierer`, `itemsortierer` | Switchable hopper transport, single-item portions or an item filter |
| `bruchsteingenerator` | Water and lava make new cobblestone |
| `lichtschalter`, `tresorschloss` | Two-way hallway light or an iron door with a lever combination |

Animal facilities do not spawn animals; lead them in or use the **Bring animals to the pen** duty. Every plan also works with `/companion schematic <id>`; English aliases such as `cow_farm` or `melon_farm` are recognized. Details (German): [farms and machines](docs/farmen-und-maschinen.md).

</details>

## More tools

- **Whistle:** craft it from two copper ingots and a stick. Right-click calls your nearest loaded companion within 128 blocks; sneak + right-click switches between following and waiting.
- **Waypoint map:** the **Waypoints** page shows the current dimension. Drag to pan, scroll to zoom, double-click a marker to send the companion there.
- **Slash commands:** `/companion` (English) and `/begleiter` (German) control your nearest own companion within 64 blocks.

<details>
<summary><strong>Command list</strong></summary>

```text
/companion follow | stay | free
/companion cancel | resume | status
/companion gather <block> [count]
/companion build platform <block> <width> <depth>
/companion build wall <block> <width> <height>
/companion clear <width> <height> <depth>
/companion schematics
/companion schematic <name> [air]
/companion material
/companion home | keeper | tidy | sort
/companion log [count] | debug
/companion project [preview | confirm | status | check | discard]
/companion llm [status | on | off | reload | test <sentence>]
```

- `schematic <name> air` also clears the planned air blocks.
- `home` sets the chest or barrel you look at (up to 6 blocks away) as storage.
- `project` previews a small wooden house (steps, materials, site warnings) without building it.
- `parkourtest` (operators) builds a large test course and **replaces many blocks** – test worlds only.

</details>

## Configuration

| File | What it controls |
| --- | --- |
| `config/begleiter.json` | Server settings, for example `"realFurnaces": true` to smelt in real furnace slots instead of virtually |
| `config/begleiter-intelligence.json` | **Intelligence 2.0** – the extra decision system (on by default). Admins switch it on the **Debug** page; `-Dbegleiter.intelligence.enabled=false` takes precedence at startup |
| `config/begleiter-llm.json` | The optional LLM connection (off by default) |

<details>
<summary><strong>Optional LLM connection</strong></summary>

When enabled, sentences the fixed recognition does not understand go to a language model. It can **only trigger existing orders** – every item, animal, amount and waypoint is checked and anything invented is discarded – asks back when unclear, or chats.

| You write | The companion |
| --- | --- |
| `@ I'd like some of that grey metal` | "Alright, I'll get you 16 iron." |
| `@ I could really use 3 diamond swords` | crafts three diamond swords |
| `@ do you have any light with you?` | "Yes, I've got 5 torches." |

**Privacy:** only unrecognized sentences are sent, for small talk together with a short status report (mode, health, hunger, tasks, inventory, nearby dangers, waypoint names) without coordinates. The API key stays in your config and never appears in logs. On a server the server's key is used for everyone.

**Setup:** create an API key (the default is [Groq](https://console.groq.com); any compatible Chat Completions service works), start Minecraft once, enter the key under `apiKey` in `config/begleiter-llm.json` and run `/companion llm on`. `/companion llm test <sentence>` shows how a sentence would be understood without doing anything.

| Field | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Switch |
| `apiKey` | empty | Without a key everything stays off |
| `baseUrl` | `https://api.groq.com/openai/v1` | Compatible service; `http://` only for `localhost` |
| `intentModel` / `chatModel` | `openai/gpt-oss-20b` | Orders / free answers |
| `timeoutMs` | `8000` | Wait per request |
| `maxRequestsPerMinute` / `maxRequestsPerDay` | `6` / `500` | The mod's own limits |

On timeout, connection failure or an exhausted quota the companion falls back to fixed recognition. Autonomous play never uses the LLM.

</details>

## FAQ and limitations

<details>
<summary><strong>Does it work on servers?</strong></summary>

Yes, but singleplayer and LAN are the tested main modes and dedicated servers are beta. The server and every player need the mod. A dedicated server only knows English item names, the built-in German chat words and item IDs, because the mod ships no Minecraft translations.

</details>

<details>
<summary><strong>Will it really beat the Ender Dragon?</strong></summary>

Every step of the way is covered by automated game tests, including real portal travel and recovering dropped gear. A complete unassisted run in a freshly generated world up to the defeated dragon has not succeeded yet, and long autonomous tasks can fail.

</details>

<details>
<summary><strong>Can it damage my world?</strong></summary>

Building, flattening and schematics change the world, so back up first. Protection mods are asked through the same block-breaking check a player triggers; areas protected in other ways may not be respected. Imported redstone circuits are rebuilt but not validated as working machines.

</details>

Found a bug? Please open an [issue](https://github.com/Leak1337/begleiter/issues) with your Minecraft, Fabric and mod versions and the companion's log from the **Diagnostics** page.

## Building from source

Requires **JDK 25**; target versions are in [gradle.properties](gradle.properties).

```bash
./gradlew assemble test compileGametestJava verifyPackagedMod   # build and check
./gradlew runGameTest                                           # server game tests
./gradlew runClientGameTest                                     # Control Center tests
./gradlew runClient                                             # play from source
```

Use `.\gradlew.bat` on Windows. The mod ends up in `build/libs/begleiter-mc26.3-<version>.jar`. `runGameTest -PtestFilter="begleiter-gametest:<name>*"` runs a single suite.

<details>
<summary><strong>Test map for development</strong></summary>

```bash
./gradlew runTestmap -PtestmapView=ausstellung
```

In the test map, `/companion testmap ausstellung` visits the farm and machine exhibition, `/companion testmap redstone` the work area and `/companion testmap szenario farmen_alle` tests every built-in facility. `/companion testmap farmfeld` visits a 128×128 meadow; `farmfeld reset`, `farmfeld items` and `farmfeld tiere` rebuild it or clear items and creatures. These commands replace large areas – test worlds only.

</details>

Further reading (German): [farms and machines](docs/farmen-und-maschinen.md) · [saving and server shutdown](docs/speicherung-und-serverende.md) · [safe catch-up positions](docs/sichere-teleports.md) · [central storage](docs/zentrallager-128.md) · [survival chest rooms](docs/survival-kistenraum-24.md)

## License

Begleiter's own code is **Apache 2.0** – see [LICENSE](LICENSE). Three adapted Baritone components (`dev.begleiter.nav.NodeHeap` and `dev.begleiter.thirdparty.baritone`) are **LGPL-3.0-or-later**; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Every mod JAR contains the license texts and its buildable source in `META-INF/begleiter-sources.zip`.

Not affiliated with Mojang or Microsoft. Made by **Leak1337**.

## License and attribution

Code authored by Leak1337 in this repository is available under the [Apache License 2.0](LICENSE). When redistributing the code or a derivative, include a copy of the license and preserve the applicable origin notice from [NOTICE](NOTICE) as required by section 4. Separately licensed material remains subject to its own terms.
