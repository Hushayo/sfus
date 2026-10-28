# Custom Minecraft Bot (Java Edition, 26.3 / protocol 777)

A from-scratch Minecraft Java bot: hand-written TCP protocol (handshake, offline login,
compression, configuration phase, play keepalive/teleport handling, chunk parsing), a 3D
A\* pathfinder, block breaking, block scanning, combat reactions, and a chat AI that can
drive all of it. No Minecraft libraries, no protocol wrappers — just `java.net` and a lot
of byte parsing.

```
src/mcbot/
  net/     framing, compression, packet reader/writer, packet id table
  world/   chunk parser, block cache, block categories, block scanner, name table
  path/    A* over the client-side block cache
  bot/     connection lifecycle, module list, 20 Hz ticker
  modules/ movement, mining, gaze, survival, interaction, scanning, crafting, chat AI
  ai/      Ollama-compatible chat client with tool calling + web search
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

The chat model needs a key — pass it as the 5th argument, or set `OLLAMA_API_KEY`, or use
`-Dmcbot.ollama.key=...`. No key is compiled into the source. Without one, chat and web
search stay disabled and everything else (movement, mining, scanning, combat, gaze) still
works.

| Environment variable | Meaning |
| --- | --- |
| `BOT_GOTO` | optional startup path, e.g. `goto -315 64 -275` |
| `BOT_DEBUG` | log every raw packet id and length (how you find packet ids) |
| `OLLAMA_API_KEY` | key for the chat model |

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
| Attacker is a player | never swings unless `-Dmcbot.pvp=true`; flees instead |

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
item, and which slot sequence to click) is **not** implemented yet — see
[docs/ROADMAP.md](docs/ROADMAP.md).

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

Commands are matched anchored, so ordinary sentences ("I will go to the shop later")
never trigger movement. A pasted `x y z yaw pitch` macro still works: the trailing
rotation numbers are ignored.

## AI tools

The chat model can call: `come_to_player`, `walk_to_coordinates`, `stop_walking`,
`mine_block`, `mine_here`, `stop_mining`, `look_at_player`, `scan_blocks`,
`attack_nearest`, `use_block`, `web_search`. The deterministic commands above are handled
before the model ever sees the message.

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

`java -Dmcbot.pvp=true` enables swinging at players. See `src/mcbot/net/PacketIds.java`
for the full list, and **[docs/PROTOCOL.md](docs/PROTOCOL.md)** for which ids have real
captured evidence behind them, which are disproved, and how to re-derive them. If a
feature silently does nothing, run with `BOT_DEBUG=1` and check which id the server
actually sends.

## Protocol tooling

Packet ids are the fragile part of a hand-written client: a wrong id never
throws, the packet just never arrives and the feature built on it silently does
nothing. `tools/pktstat.py` measures them instead of guessing.

```bash
python tools/pktstat.py analyze live2.log --state PLAY   # what the server really sends
python tools/pktstat.py burst  live2.log --n 24          # join fingerprint
python tools/pktstat.py ids                              # what the code claims
python tools/pktstat.py probe --host 127.0.0.1 --port 25599 --out capture.log
```

`analyze` reads the logs the bot already writes with `BOT_DEBUG=1`; `probe`
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
- No inventory model: the bot cannot see what it is holding, so mining progress is
  detected by polling the world cache rather than by simulating block hardness.
- Recipe logic and GUI-aware slot sequences are not implemented (roadmap).
- Anti-cheat heavy servers may still correct the bot's position; rejected path edges are
  blacklisted and the route is recomputed around them.

## License

MIT — see [LICENSE](LICENSE).
