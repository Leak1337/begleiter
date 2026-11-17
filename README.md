<h1 align="center">⚒️ Begleiter</h1>

<p align="center">
  <strong>A second player for your Minecraft world.</strong><br>
  A companion that takes orders, gathers, crafts, builds, survives and remembers the world.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.3-4CAF50" alt="Minecraft 26.3">
  <img src="https://img.shields.io/badge/Fabric-0.19.5%2B-2196F3" alt="Fabric Loader 0.19.5 or newer">
  <img src="https://img.shields.io/badge/Java-25-F89820" alt="Java 25">
  <img src="https://img.shields.io/badge/status-beta-orange" alt="Beta">
  <img src="https://img.shields.io/badge/License-MIT-lightgrey" alt="MIT license">
</p>

<p align="center">
  <a href="https://github.com/Leak1337/begleiter/releases"><strong>Download</strong></a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="#talking-to-your-companion">Chat</a> ·
  <a href="#control-center">Control Center</a> ·
  <a href="#building">Building</a> ·
  <a href="#limitations">Limitations</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

---

Begleiter ("companion" in German) is a **Fabric mod for Minecraft Java Edition 26.3**. Instead of a pet you get an NPC with its own inventory, tools, memory and task planning. It follows you, carries out what you ask in plain chat, or plays on its own based on its needs and progress.

Everything it does uses real Minecraft rules: real blocks, recipes, tools, furnaces and chests – no spawned items, no shortcuts. It has its own navigation that mines through obstacles, builds bridges and pillars and simulates jumps with Minecraft physics.

> **Beta.** Singleplayer and LAN are the tested main modes; dedicated servers are beta. The companion is mostly rule-based – it can get stuck, and the path to the Ender Dragon is no guarantee. **Back up your world** before large build or terraforming orders.

## Highlights

- 💬 **Plain chat orders in English and German** – `@ bring me 10 iron`, `@ craft me 2 stonecutters`, `@ build a large stone house`.
- 🧠 **Plans on its own** – gathers ingredients, crafts intermediate items and tools, places a crafting table and furnace, smelts and delivers.
- 🏗️ **24 farms and machines plus houses** with a 3D preview and a movable, rotatable world preview before anything is built.
- 📐 **Schematics** – `.litematic`, `.schem`, `.nbt` and MCEdit `.schematic`, built block by block with gathered materials.
- 🗺️ **Ongoing duties with work zones** – fields, animals, base, storage, exploring, fetching animals and trading, with zones drawn on a map.
- ⚔️ **Survival and combat** – eats, hunts, cooks, defends you, uses bow and shield, avoids lava and dangerous falls.
- 💾 **Remembers** ores, chests, villages, portals and hazards; keeps everything across save and load and even after death.
- 🤖 **Optional LLM** – off by default; when enabled, it also understands free-form sentences. The mod works fully without it.

## Quick start

| You need | Version |
| --- | --- |
| Minecraft Java Edition | **26.3** |
| [Fabric Loader](https://fabricmc.net/use/) | **0.19.5 or newer** |
| [Fabric API](https://modrinth.com/mod/fabric-api) | matching 26.3 |
| Java | **25 or newer** |

1. Install the Fabric Loader for Minecraft 26.3.
2. Put **Fabric API** and `begleiter-mc26.3-<version>.jar` from [Releases](https://github.com/Leak1337/begleiter/releases) into your `mods` folder.
3. Start a world and craft a **Companion Core**:

   ```text
   I R I     I = Iron Ingot
   G D G     R = Redstone
   I I I     G = Gold Ingot
             D = Diamond
   ```

4. Right-click a block with the core. Your companion **Leak** appears.
5. Try `@ follow me`, then `@ bring me 16 wood`. **Shift + right-click** opens the Control Center.

On a server, the server **and every player** need the mod. If the companion dies, it drops its core; placing the core revives it with its name and knowledge.

## Talking to your companion

The companion only reacts when you **address** it – also in singleplayer:

- `@` at the start of a message, or the words **Leak** or **Begleiter** anywhere, address your nearest companion.
- **Bot** and **Companion** only count at the start (`Bot, follow me`) or as `@Bot`, so ordinary chat triggers nothing.
- Its name addresses exactly that companion: `Max, follow me` or `@Max follow me`.

| Message | Effect |
| --- | --- |
| `@ follow me` / `@ wait here` / `@ play on your own` | Switch mode |
| `@ stop` / `@ continue` | Cancel / resume orders |
| `@ bring me 10 iron` / `@ bring me a stack of wood` | Gather and bring items to you |
| `@ make me an iron pickaxe` | Craft it, including the materials it needs |
| `@ bring me resources for 2 stonecutters` | Collect the ingredients and put them in your inventory |
| `@ craft me 2 stonecutters` | Craft new items and put them in your inventory |
| `@ build a large stone house` / `@ build a farm` | Build |
| `@ harvest the field` / `@ breed cows` | Fields and animals |
| `@ repair your tools` / `@ gear up` | Maintain and improve equipment |
| `@ buy me a book` / `@ sell your stuff` | Trade with villagers |
| `@ flatten here 15x15` | Terraform |
| `@ remember this as mine` / `@ go to mine` / `@ forget waypoint mine` | Waypoints |
| `@ be defensive` / `@ protect me` | Combat behavior |
| `@ this is your storage` / `@ sort the chests` | Set up and sort storage |
| `@ what do you know?` / `@ where is iron?` | Ask about its world knowledge |
| `@ why are you doing that?` / `@ status` | Explain goal and state |

Recognition uses fixed words and patterns; German phrases work too (see [README.de.md](README.de.md)). Unusual or deeply nested sentences are not understood reliably unless the [optional LLM connection](#optional-llm-connection) is on.

**Items and recipes.** Use English or German item names or full IDs such as `minecraft:stonecutter`; names from installed mods work through their language files. The companion checks its inventory and chests first, combines partial amounts and obtains the rest. Crafting-table, furnace, stonecutter and smithing-upgrade recipes are supported, including compatible mod and data-pack recipes – `@ craft me a netherite sword` works when the ingredients can be obtained, and upgrades keep name, enchantments and damage. Brewing, armor trims and other special recipes are not supported yet.

**Follow-up questions.** After `@ craft me armor`, answer `@ iron` to continue the same order. If work pauses after a problem, fix it and say `@ continue`; `@ cancel` discards the order.

## Control Center

**Shift + right-click** the companion, or press **B** anywhere in the same dimension (rebindable under *Options → Controls*). The menu follows your Minecraft language: German for German, English otherwise.

| Page | Contents |
| --- | --- |
| **Overview** | Health, hunger, mode, current task, inventory and equipment |
| **Controls** | Gathering, storage, building and one-off everyday orders |
| **Tasks** | Basic modes, seven ongoing duties and the zone map |
| **Inventory** | The companion's 36 slots and yours; shift-click transfers |
| **Equipment** | Armor and hands (it chooses from its own inventory) |
| **Combat** | Current target and stance: aggressive, defensive, passive |
| **Status** | Needs, base and knowledge |
| **Build** | Houses, farms and machines, schematics, previews, material and movement modes, progress |
| **Diagnostics** | Log with filter and paging |
| **Debug** | Intelligence 2.0 on/off for the whole server (admins only) |

Right-click toggles between following and playing on its own. **F7 / F8 / F6** show a debug HUD, debug menu and path overlay for admins and the singleplayer world owner.

## Ongoing duties

**Tasks** offers seven jobs the companion keeps doing until you switch mode:

| Duty | What it does |
| --- | --- |
| **Tend fields** | Harvests ripe crops, replants and stores the harvest |
| **Tend animals** | Collects loose items and feeds cows, sheep, pigs and chickens within herd limits |
| **Wander and explore** | Visits safe places in the roaming zone and remembers what it finds |
| **Look after the base** | Collects items, tends fields and animals, deposits surplus and sorts storage |
| **Look after chests** | Collects items in the storage zone and sorts chests when contents change |
| **Bring animals to the pen** | Leads adult animals into the animal zone until each kind has two |
| **Village trade** | Sells available goods to matching offers |

Nothing is spawned: crops must grow, animals must exist and the area must stay loaded. Duties survive save and load.

<details>
<summary><strong>Map and work zones</strong></summary>

Open **Tasks → Map & zones** for a local terrain map (north at the top). Select **Base**, **Animals**, **Fields**, **Storage** or **Roaming**, drag a rectangle and click **Save**. The mouse wheel zooms and arrow keys pan. Unknown terrain stays dark – the map never loads extra chunks.

**Mark in world** selects two ground corners instead: **Z** marks a corner, **G** saves, **K** returns to the map, **Backspace** cancels. Both corners must be within 96 blocks of you and in loaded terrain.

Zones can be at most **64 × 64 blocks** and 32 high. Exploring, base care, storage care and fetching animals need their zone; field and animal duties prefer one. Zones limit work targets, not movement – routes may leave them. Editing or deleting a zone stops the active duty.

</details>

## Building

**Build** shows cards with textured 3D thumbnails: houses (wood, stone or birch in three sizes), the wheat farm, **24 farms and machines** and your own schematic files. The mod also bundles three storage designs of its own: a **128-material central storage with sorting and retrieval** and **compact survival chest rooms for 12 and 24 materials**.

1. Pick a card and inspect it in the 3D view (drag to rotate, scroll to zoom).
2. **Place** opens a translucent preview in the world that follows the block you aim at.
3. Adjust it, then press **Enter** to start building.

| Key | World preview |
| --- | --- |
| **R** | Rotate by 90° |
| **Page Up / Page Down** | Raise (up to 16) or lower (up to 6) |
| **M** | Building movement: Normal → Fly → Fly/Teleport |
| **Enter** | Confirm and start |
| **Backspace** | Cancel; the previous order is kept |

Red preview blocks are obstacles. The server re-checks height, world border, loaded chunks, containers and player-built blocks; only natural terrain is cleared, and gaps below the foundation are filled up to six blocks deep.

**Material mode:** in **Survival** every block costs an item – the companion gathers what is missing or asks you, and the menu shows progress and missing materials. **Creative** building has unlimited materials and is available to creative players, admins and the singleplayer world owner. **Building movement** (Normal, Fly, Fly/Teleport) is set separately; flying never creates materials.

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

Animal facilities do not spawn animals; lead them in yourself or use the **Bring animals to the pen** duty; **Tend animals** then feeds them regularly. All plans also work with `/companion schematic <id>`; English aliases such as `cow_farm`, `rabbit_farm`, `melon_farm` and `egg_farm` are recognized. Details (German): [farms and machines](docs/farmen-und-maschinen.md).

</details>

**Schematics** go into the `schematics` folder of your game directory or `config/worldedit/schematics`. Files from older Minecraft versions are upgraded like old worlds. Sign texts and crafter locks are copied; container contents only in Creative material mode; command-block commands never.

## Slash commands

`/companion` controls your nearest own companion within 64 blocks. Every command except `parkourtest` also exists in German under `/begleiter`.

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
- `project` previews a small wooden house (steps, materials, site warnings) without changing anything; projects are not built automatically in this version.
- `home` sets the chest or barrel you look at (up to 6 blocks away) as storage.
- `parkourtest` (operators) builds a large test course and **replaces many blocks** – test worlds only.

</details>

## Intelligence 2.0

An additional decision system, **on by default**. It picks supply and work goals by situation (health, hunger, tools, free space, danger, travel cost), combines partial amounts from several chests, remembers which ways of getting items worked and checks prerequisites before starting. Admins or the singleplayer world owner can switch it off server-wide on the **Debug** page (saved in `config/begleiter-intelligence.json`); `-Dbegleiter.intelligence.enabled=false` takes precedence at startup.

## Optional LLM connection

**Off by default.** When enabled, sentences the fixed recognition does not understand go to a language model, which picks one of the existing orders, asks back when unclear, or chats.

| You write | The companion |
| --- | --- |
| `@ I'd like some of that grey metal` | "Alright, I'll get you 16 iron." |
| `@ I could really use 3 diamond swords` | crafts three diamond swords |
| `@ do you have any light with you?` | "Yes, I've got 5 torches." |

- Sentences the fixed recognition knows never reach the model.
- The model can **only trigger existing orders**; every item, animal, amount and waypoint is checked, anything invented is discarded.
- **Privacy:** only unrecognized sentences are sent – for small talk together with a short status report (mode, health, hunger, tasks, inventory, nearby dangers, waypoint names). It contains **no coordinates**. The API key stays in your config and never appears in logs. On a server, the server's key is used for all players.

<details>
<summary><strong>Setup and settings</strong></summary>

1. Create an API key; the default configuration uses [Groq](https://console.groq.com). Any service with a compatible Chat Completions interface works.
2. Start Minecraft once – the mod creates `config/begleiter-llm.json` (switched off).
3. Enter the key under `apiKey` and run `/companion llm on`.
4. `/companion llm test <sentence>` shows how a sentence would be understood without doing anything.

| Command | Effect |
| --- | --- |
| `/companion llm status` | On/off, models, used quota, recent calls (everyone) |
| `/companion llm on` / `off` | Switch and save (operators) |
| `/companion llm reload` | Re-read the config (operators) |
| `/companion llm test <sentence>` | Test connection and translation (operators) |

| Field | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Switch |
| `apiKey` | empty | Without a key everything stays off |
| `baseUrl` | `https://api.groq.com/openai/v1` | Compatible service; `http://` only for `localhost` |
| `intentModel` / `chatModel` | `openai/gpt-oss-20b` | Orders / free answers |
| `timeoutMs` | `8000` | Wait per request |
| `maxRequestsPerMinute` / `maxRequestsPerDay` | `6` / `500` | The mod's own limits |

On timeout, connection failure or an exhausted quota the companion falls back to fixed recognition. Costs and limits depend on your provider and account.

</details>

## Limitations

- **Singleplayer and LAN** are the tested main modes; **dedicated servers are beta**. Extensive multiplayer, lag and disconnect testing is still pending.
- **No AI guarantee:** long autonomous tasks and the path to the End can fail.
- **World changes:** building, flattening and schematics modify the world – back up first. Protected areas from other mods are not respected.
- **Redstone:** built-in facilities use ordinary mechanics. Imported circuits are rebuilt but not validated as working machines.
- **German item names on dedicated servers:** the mod ships no Minecraft translations. In singleplayer and LAN, German item names come from your installed game; a dedicated server only knows the English names, the built-in German chat words and item IDs.
- **LLM (optional):** models occasionally misunderstand; without internet or after the limit, fixed recognition takes over. Autonomous mode does not use the LLM.

Found a bug? Please open an [issue](https://github.com/Leak1337/begleiter/issues) with your Minecraft, Fabric and mod versions and the companion's log (**Diagnostics** page).

## Building from source

Requires **JDK 25**; target versions are in [gradle.properties](gradle.properties).

```bash
./gradlew assemble test compileGametestJava verifyPackagedMod
./gradlew runGameTest
./gradlew runClientGameTest
```

Use `.\gradlew.bat` on Windows. The playable mod is `build/libs/begleiter-mc26.3-<version>.jar` (`-sources.jar` is source code, not the mod). `runGameTest -PtestFilter="begleiter-gametest:<name>*"` runs a single suite; `runClient` plays from source.

<details>
<summary><strong>Test map for development</strong></summary>

```bash
./gradlew runTestmap -PtestmapView=ausstellung
```

In the test map, `/companion testmap ausstellung` visits the farm and machine exhibition, `/companion testmap redstone` the work area and `/companion testmap szenario farmen_alle` tests every built-in facility. `/companion testmap farmfeld` visits a 128×128 meadow for experiments; `farmfeld reset`, `farmfeld items` and `farmfeld tiere` rebuild it or clear items and creatures. These commands replace large areas – test worlds only.

</details>

More (German): [farms and machines](docs/farmen-und-maschinen.md), [saving and server shutdown](docs/speicherung-und-serverende.md), [safe catch-up positions](docs/sichere-teleports.md), [central storage](docs/zentrallager-128.md), [survival chest rooms](docs/survival-kistenraum-24.md).

## License

Begleiter's own code is **MIT** – see [LICENSE](LICENSE). Three adapted Baritone components (`dev.begleiter.nav.NodeHeap` and `dev.begleiter.thirdparty.baritone`) are **LGPL-3.0-or-later**; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Every mod JAR contains the license texts and its buildable source in `META-INF/begleiter-sources.zip`.

Not affiliated with Mojang or Microsoft. Developed by **Leak1337**.
