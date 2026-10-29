# Custom Minecraft Bot (Java Edition, 26.3 / protocol 777)

A from-scratch Minecraft Java bot: hand-written TCP protocol (handshake, offline login,
compression, configuration phase, play keepalive/teleport handling, chunk parsing), a 3D
A\* pathfinder, block breaking, block scanning, combat reactions, and a chat AI that can
drive all of it. No Minecraft libraries, no protocol wrappers — just `java.net` and a lot
of byte parsing.

It also knows the game: every block state, item, recipe, tag and loot table is generated
from the server's own reports, so "what does stone drop", "where do I get iron" and "can
you make a diamond pickaxe" are answered from data rather than memory. See
**[docs/MECHANICS.md](docs/MECHANICS.md)** for the full coverage and
**[docs/TESTING.md](docs/TESTING.md)** for the 142-test suite.

```
src/mcbot/
  net/        framing, compression, packet reader/writer, packet id table
  world/      chunk parser, block cache, block categories, block scanner, name table
  path/       A* over the client-side block cache
  bot/        connection lifecycle, module list, 20 Hz ticker
  modules/    movement, mining, gaze, survival, interaction, scanning, crafting,
              threats, loadout, chat AI
  registry/   blocks, items, tags, components, entity types, menu layouts
  knowledge/  drops, food, armour, weapons, mobs, enchantments, damage types
  crafting/   recipes and the dependency planner
  inventory/  stacks, window state, slot layouts
  api/        actions (dig, place, craft, eat, equip, build) and reasoning
  jobs/       mining, crafting, smelting, one-off actions
  ai/         Ollama-compatible chat client with tool calling + web search
  dev/test/   offline test suite (no server needed)
```

## Build

```powershell
.\build.ps1          # javac + jar -> sfus.jar
```

or with any JDK 21+:

```bash
javac -d out $(find src -name "*.java")
jar --create --file sfus.jar --main-class mcbot.Main -C out .
```

## Run

```powershell
.\run.ps1 -Username PathTest -Goto "goto 20 64 -20"
```

```bash
java -cp out mcbot.Main PathTest 51.195.191.92 10679 ChangeMe123
```

Arguments: `username`, `host`, `port`, `LoginSecurity password`, `optional Ollama API key`.

A dropped connection does not end the process. `Main` rebuilds the bot and
reconnects with a 2 s / 4 s / 8 s / 16 s / 30 s backoff, and a session that
lasted a minute resets the backoff so the first retry after a long run is
immediate. A `sfus.lock` file lock stops a second copy from starting on the same
account - the server would kick one of them as "already online" and the two would
fight. `BOT_RECONNECT=0` restores the old one-shot behaviour.

The chat model needs a key. It is looked up in this order, first hit wins:

| # | Source | How |
| --- | --- | --- |
| 1 | 5th command line argument | `java -cp out mcbot.Main user host port pass <key>` |
| 2 | `OLLAMA_API_KEY` | process environment |
| 3 | `-Dmcbot.ollama.key=...` | JVM system property |
| 4 | `ollama.key` file | next to the jar, or `data/ollama.key` |
| 5 | `OLLAMA_API_KEY` in the Windows registry | User scope, `HKCU\Environment` |

Sources 4 and 5 exist because an environment variable is read from the *process*,
not from wherever it is configured: a bot started from a terminal that was already
open when the variable was set (`setx`, the Environment Variables dialog) inherits
the old, empty block and reports **"chat is disabled" while the key is plainly
set**. The registry entry is read with `reg query`, which sees the new value, so
that launch works anyway. Start-up logs which source won:

```
[ai 15:12:32.316] Ollama key found via OLLAMA_API_KEY (Windows user environment)
[ai 15:12:32.316] no Ollama key: set OLLAMA_API_KEY, pass it as the 5th argument, put it in ollama.key, or -Dmcbot.ollama.key=... - chat is disabled
```

With no key at all, chat and web search stay disabled and everything else
(movement, mining, scanning, combat, gaze) still works. A UTF-8 BOM, wrapping
quotes and trailing newlines are stripped from whatever the key came from, so a
file written by `Set-Content -Encoding utf8` or a value pasted with quotes still
works.

| Environment variable | Meaning |
| --- | --- |
| `BOT_GOTO` | optional startup path, e.g. `goto -315 64 -275` |
| `BOT_DEBUG` | log every raw packet id and length (how you find packet ids) |
| `BOT_RECONNECT` | `0` disables the reconnect loop (one attempt, then exit) |
| `OLLAMA_API_KEY` | key for the chat model (see the table above) |
| `BOT_LOG` | log file; defaults to `logs/bot.log`, truncated per start |

| `BOT_LOG` | where the one log file goes (default `logs/bot.log`) |
| `BOT_LOG_APPEND` | set to anything to keep history instead of truncating on start |

Everything the bot prints - stdout and stderr - is mirrored into that single
file, so no launcher and no ad-hoc run has to invent its own `.log`/`.err`. The
console and the systemd journal still receive the same lines.

## What the bot does

### Movement

`goto x y z`, `goto x z`, `come` / `come here`, `stop`. Paths are 3D A\* over loaded
chunks, limited to 256 blocks, with one-block step-up and up-to-three-block drops. Each
step is axis-aligned: the bot slides back onto the previous node's centre line before
stepping, so it never attempts a diagonal that the server's collision would reject.

Movement is one position packet per client tick, each followed by `client_tick_end`. The
speed is capped at vanilla walking (0.2158 blocks/tick): the old value was 0.40, i.e.
8 m/s, which is faster than sprinting and is exactly what gets a bot corrected by a
proxy anti-cheat.

### Gaze

The bot has a head, and it uses it.

- **If you look at it, it looks back.** Every tracked entity's yaw/pitch is ray-cast
  against the bot's hitbox (slab test, 48 block range). On a hit the bot turns and looks
  at your eyes.
- **Idle, it looks around.** After ~6 seconds without interaction the camera wanders:
  up at the sky, along the horizon, down at the ground, occasionally settling on a nearby
  entity. Rotations are interpolated, not snapped.
- **When hit, it snaps onto the attacker.**
- While pathfinding, the pathfinder owns rotation and the gaze module stands down.

### Reactions to damage and death

Health, damage events and death are tracked. On damage the bot identifies the source
(`damage_event` attacker id, falling back to the hurt animation on its own entity id, then
to a health drop) and decides:

| Situation | Response |
| --- | --- |
| Healthy | close the distance, face the target, swing on the vanilla 625 ms cooldown |
| Health below 14 | after each hit, step back out of reach (kiting = fewer hits land) |
| Health ≤ 6, or ≤ 10 with no food | disengage, run, eat if food is available |
| Attacker is a player | fights back like any other attacker; `-Dmcbot.pvp=false` restores the old "flee" behaviour |

Death stops movement and mining, says so in chat, and hands off to the auto-respawn
module.

### Block scanning

`scan` walks **every block** in every loaded chunk (a 384-high chunk is 98,304 blocks;
a normal view is ~37M reads, a couple hundred milliseconds) and reports a histogram.
`find oak_log` returns the nearest matches. Scans run on their own thread.

Block ids on the wire are *state ids*, not names. Drop a `blocks.txt` next to the jar to
get real names — see below.

### Crafting tables, furnaces, containers

`craft` finds the nearest crafting table, walks to it and opens it. `furnace` does the
same for furnaces. `use x y z` right-clicks any block. `container`, `click <slot>`,
`slot <n>`, `close` talk to whatever window the server opened.

This is the plumbing, and it works: block interaction, entity attack, arm swing, hotbar
selection, container open/close and slot clicks. Recipe logic (which layout makes which
item, and which slot sequence to click) now drives CraftJob: a request such as
"craft me an iron pickaxe" plans the whole ladder - logs into planks and sticks, a
wooden pickaxe, three stone, a stone pickaxe, three iron ore, a coal for smelting
fuel, then the ingots and the pickaxe - and runs each step in order, making and
placing a crafting table or furnace when one is missing. The bot no longer stops at
"you need planks" when four more are one log away.

**Status of the serverbound ids.** Sending a packet with the wrong id does not fail
quietly — it gets the bot kicked. That happened once: a guessed `interact` at `0x36` was
decoded by the server as `set_carried_item` and disconnected us with *"found 2 bytes
extra"*. So the unverified serverbound packets (`interact`, `swing`, `use_item`,
`use_item_on`, `container_click`) default to **disabled**, and survival keeps its distance
instead of fighting. Hotbar selection is enabled: `0x36` is now a *known* id, and it takes
a short, not a VarInt.

To turn them back on once you have evidence:

```
java -Dmcbot.packet.interact=0x.. -Dmcbot.packet.use_item_on=0x.. ...
```

## What the bot knows

Every table is generated from the running server build (`tools/gen_data.py`), not typed
in: 1,286 blocks / 35,723 states with properties, collision, hardness and tool rules;
1,658 items with stack size, durability, food, armour, weapon and tool components; 1,681
recipes of all five types; 296 block tags and 236 item tags; 1,318 block loot tables and
138 mob loot tables; 44 foods; 38 armour pieces; 42 weapons; 161 entity types classified
hostile / neutral / passive / boss; 43 enchantments; 51 damage types.

What that buys, concretely:

- **Drops are exact.** Stone yields cobblestone, grass yields dirt, diamond ore yields a
  diamond, oak leaves guarantee nothing (5% sapling, 2% stick, 0.5% apple). The planner
  never sends the bot to mine something that drops nothing it wants.
- **Break times match vanilla.** Stone by hand 7.5 s, stone with a wooden pickaxe 1.15 s,
  obsidian with diamond 9.4 s — computed from hardness, tool speed and the harvest flag.
- **Food is chosen, not grabbed.** Nutrition, saturation, eat time and effects decide what
  to eat and when; vanilla only regenerates at food ≥ 18.
- **Armour and weapons are valued properly.** The vanilla damage formula (with toughness),
  the 1.9 attack cooldown, criticals and shields.
- **Mobs are classified.** A cow is not a threat; an enderman is neutral until hit; a
  skeleton 12 blocks away outranks a zombie at the same distance.
- **Questions get real answers.** "Where do I get iron", "what is obsidian", "what can you
  make right now", "why can't you mine that" are answered from the tables and the live
  inventory, without a language model.

## Actions

`mcbot.api.Actions` is the verb layer — the part a script or the chat model uses. It
checks preconditions, acts through the modules, and verifies the result instead of
assuming the packet worked.

| Action | What it does |
| --- | --- |
| `gotoAndWait`, `gotoBlock` | walks and waits for arrival, with a timeout |
| `dig`, `digBlock`, `collect` | one block, N blocks, or "get me this item however it takes" |
| `craft`, `smelt` | plans and runs the whole ladder |
| `equip`, `equipBestArmor`, `equipBestWeapon` | armour by shift-click, tools into the hand |
| `eat` | best food, real eat time |
| `drop`, `deposit`, `withdraw` | inventory and container transfers |
| `attack`, `defend`, `flee` | `defend` gears up first |
| `place`, `wall`, `platform`, `shelter` | verified placements against a support face |
| `sleep` | finds a bed and uses it |
| `selfReport`, `inspect` | what it knows about itself and the world |

## Chat commands

| Command | Module |
| --- | --- |
| `goto x y z`, `go to`, `move to`, `walk to`, `path to` | movement |
| `goto x z` (keeps current Y) | movement |
| `come`, `come here` | movement |
| `stop`, `cancel`, `stop moving` | movement |
| `mine x y z`, `dig`, `break` | mining |
| `mine` / `mine here` / `mine this` — where the sender looks or stands | mining |
| `mine ahead` / `mine forward` | mining |
| `scan [radius]` | scanning |
| `find <name or state id> [limit]` | scanning |
| `where` | scanning |
| `craft [x y z]`, `table` | crafting |
| `furnace [x y z]`, `smelt` | crafting |
| `use x y z` | interaction |
| `container`, `click <slot> [button] [type]`, `slot <0-8>`, `close` | interaction |
| `eat`, `eat something` | survival |
| `gear up`, `equip <item>`, `wear <item>` | loadout |
| `defend`, `fight`, `attack`, `kill` | combat |
| `flee`, `run away`, `retreat` | survival |
| `build a wall`, `build a shelter`, `build a platform` | building |
| `sleep`, `go to bed` | interaction |
| `drop 10 dirt`, `throw away cobblestone` | inventory |
| `where do I get iron`, `what is obsidian`, `what can you make` | reasoning |
| `how are you`, `what do you know`, `what should you do` | self knowledge |

Commands are matched anchored, so ordinary sentences ("I will go to the shop later")
never trigger movement. A pasted `x y z yaw pitch` macro still works: the trailing
rotation numbers are ignored.

## AI tools

The chat model can call 22 tools: `come_to_player`, `walk_to_coordinates`,
`stop_walking`, `follow_player`, `mine_block`, `mine_here`, `stop_mining`,
`look_at_player`, `scan_blocks`, `attack_nearest`, `use_block`, `craft_item`,
`smelt_item`, `gather_item`, `list_inventory`, `eat_food`, `equip_best_armour`,
`defend`, `build`, `self_status`, `game_knowledge`, `web_search`. The
deterministic commands above are handled before the model ever sees the message,
and `game_knowledge` routes questions back into the same reasoning code instead
of letting the model recall a recipe.

The schema is a 5 KB hand-built JSON string, so a test parses it on every run:
one missing brace would make every request fail with a 400 the bot never sees.

## Testing

```
./tools/test.sh
```

142 tests in 19 suites, no server required: codecs, registries, loot, food,
armour, combat, mobs, recipes and the planner, inventory parsing and slot
mapping, physics, pathfinding, fuzzed parsers, a sweep over all 1,658 items and
all 35,723 block states, the reasoning layer, the AI tool schema, and a full
login against an in-process protocol-777 server.

See **[docs/TESTING.md](docs/TESTING.md)** for the suite table and the bugs the
tests caught.

To see the coverage and a few real answers without connecting:

```
java -cp sfus.jar mcbot.dev.KnowledgeDump
```

To see the coverage and a few real answers without connecting:

```
java -cp sfus.jar mcbot.dev.KnowledgeDump
```

`web_search` uses DuckDuckGo's HTML endpoint and Wikipedia — both free, key-less and
quota-free. There is deliberately **no paid search fallback**: if both miss, the tool
returns "no results" rather than billing an API call. The only metered call the bot makes
is the chat completion itself.

## Block name table (optional)

The protocol sends block *state ids*. To get names, drop a dump next to the jar:

```
# blocks.txt
1            minecraft:stone
14           minecraft:granite
4123         minecraft:oak_log
```

`4123 minecraft:oak_log`, `minecraft:oak_log 4123` and `"minecraft:oak_log": 4123,` are
all accepted, so a registry dump can be copied in nearly as-is. Load order:
`/blocks.txt` on the classpath, `./blocks.txt`, `./data/blocks.txt`, then
`-Dmcbot.blocks=/path/to/blocks.txt`.

## Packet id overrides

The ids needed to stay alive (login, keepalive, chunk, movement, block break) are verified
against a live server. The optional feature modules (health, damage, attack, containers)
carry best-effort ids that can be overridden without recompiling:

```
java -Dmcbot.packet.entity_status=0x1D -Dmcbot.packet.interact=0x36 -Dmcbot.packet.use_item_on=0x3A ...
```

PvP is **on** by default; `-Dmcbot.pvp=false` turns it off and makes the bot flee
from players instead of fighting. See `src/mcbot/net/PacketIds.java`
for the full list, and **[docs/PROTOCOL.md](docs/PROTOCOL.md)** for which ids have real
captured evidence behind them, which are disproved, and how to re-derive them. If a
feature silently does nothing, run with `BOT_DEBUG=1` and check which id the server
actually sends.

## Protocol tooling

Packet ids are the fragile part of a hand-written client: a wrong id never
throws, the packet just never arrives and the feature built on it silently does
nothing. `tools/pktstat.py` measures them instead of guessing.

```bash
python tools/pktstat.py analyze logs/bot.log --state PLAY   # what the server really sends
python tools/pktstat.py burst  logs/bot.log --n 24          # join fingerprint
python tools/pktstat.py ids                              # what the code claims
python tools/pktstat.py probe --host 127.0.0.1 --port 25599 --out capture.log
```

`analyze` reads the log the bot already writes with `BOT_DEBUG=1` (`logs/bot.log`
by default, override with `BOT_LOG`); `probe`
captures a fresh session. Both join the capture against the ids declared in
`PacketIds.java` and the modules, so the tool never drifts from the source.
`tools/fake_server.py` speaks enough of the handshake to exercise either one
offline.

See [docs/PROTOCOL.md](docs/PROTOCOL.md) for what is confirmed, what is
disproved, and what is still a guess.

## Protocol notes

- `BlockPos` on the wire is `BlockPos.asLong()`: 26-bit x at bit 38, 12-bit y at bit 0,
  26-bit z at bit 12 (`x << 38 | y | z << 12`). This is **not** the older
  `x << 38 | y << 26 | z` layout; using the old packing makes the server decode a
  different position and silently reject a block break with `too far`.
- Breaking a block needs `player_action` with `START_DESTROY_BLOCK` (ordinal 0), the
  packed `BlockPos`, the face direction and a sequence, then `STOP_DESTROY_BLOCK`
  (ordinal 3) to commit the accumulated progress.
- Two position packets in one server tick disconnect the client with
  `multiplayer.disconnect.invalid_player_movement` unless a `client_tick_end` separates
  them.
- `player_loaded` must be sent even if the server never sends `chunk_batch_finished`,
  otherwise movement is ignored until the load timeout expires.

## Limitations

- Offline-mode login only (no encryption / Microsoft auth).
- Anti-cheat heavy servers may still correct the bot's position; rejected path
  edges are blacklisted and the route is recomputed around them.
- The open-packet ids for interactly mining ores far from the bot are a world
  constraint, not a code one: no ore within the 32-block scan radius means the
  job stops honestly instead of walking to infinity.
- Not implemented: enchanting table flow, anvil, brewing, trading, fishing,
  boats, minecarts, elytra, redstone logic, and anything that persists between
  restarts (no remembered base or chests).
- Enchantments on picked-up items are not decoded (the component patch is
  skipped), so a Sharpness V sword is valued as a plain one.
- Mob health and melee damage are the one curated table: no data report carries
  them. A mob missing from it is classified by name and treated as unknown
  strength.

## License

MIT — see [LICENSE](LICENSE).
