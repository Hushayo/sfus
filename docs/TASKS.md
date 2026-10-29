# Tasks

Status of every subsystem, with the evidence for the claim. "Verified" means it
was exercised against the live server (`51.195.191.92:10679`) and the log shows
the expected result; "written" means the code exists but has not been proven
end to end; "missing" means it does not exist.

Legend: ✅ done · 🟡 partial · ❌ missing

---

## 1. Connection, login, configuration ✅

Handshake → login (offline mode) → configuration → play, with compression
negotiated from `login_compression`.

- Compression threshold honoured in both directions; frames larger than
  `MAX_FRAME` (8 MiB) rejected before allocation.
- `LoginSecurity` servers: the bot registers with `/register` and logs in with
  `/login` when the server asks.
- Disconnect reasons are decoded to text (`[LoginSecurity] This player is
  already online!`) instead of a hex dump, and the read loop exits cleanly
  instead of throwing `SocketException` on a closed socket.

## 2. Packet ids and framing ✅

Ids come from the server's own `generated/reports/packets.json`; layouts come
from `javap -c` on each packet's `STREAM_CODEC` initialiser.

- Six ids were wrong in the original table (`open_screen`, `container_close`,
  `set_held_item`, `entity_status`, `use_item_on`, `container_click`).
- `use_item_on` needs **two** trailing booleans (`inside`, `worldBorderHit`);
  the missing second one was an immediate decoder disconnect.
- VarInt/VarLong are length-capped, frames are size-capped, readers are
  bounds-checked. A malformed packet fails that module, not the connection.

## 3. World cache ✅

Chunks, block updates, section updates, unload, biomes.

- `section_blocks_update` was decoding **y and z swapped** (18k+ of those packets
  per session), so every block change landed in the wrong column. Fixed and
  centralised in `mcbot.util.Pos`.
- Light, heightmaps and biomes are skipped with the correct widths.

## 4. Block and item registries ✅

Generated from the server by `tools/gen_data.py` (1286 blocks / 35,723 states,
1658 items, 915 tool entries, 1681 recipes, tags, menus, entity types,
components).

- Break times match vanilla exactly: stone with a wooden pickaxe 1.15 s,
  obsidian with diamond 9.4 s, ancient debris 5.65 s.
- Collision categories are derived from block names and state properties and
  were diffed against the old hand-built table: the only differences are the
  ones that were wrong before (open doors, trapdoors and signs are passable).
- Hardness is the one hand-maintained table (`hardness.tsv`): it is not in any
  data report, it is a constructor argument in the block class.

## 5. Inventory and containers ✅

- Item stacks parse including the component patch for the fixed-size components
  (`damage`, `max_damage`, `repair_cost`, `rarity`, `unstackable`…).
- Window state ids are tracked and echoed on every click; the old code always
  sent 0, which made every click after the first look stale to the server.
- Verified: `inventory` reports `43 cobblestone, 1 stone pickaxe` correctly.
- ❌ `block_entity_data` carries NBT and cannot be skipped, so a stack with it
  marks the window opaque. Nothing is corrupted, but contents go unknown.

## 6. Movement and physics 🟡

A real integrator now sits under the scripted movement (`mcbot.physics.PlayerPhysics`):
0.08/tick gravity, 0.98 vertical drag, 0.6 ground / 0.91 air friction, and a
0.6 x 1.8 player box swept against the block cache in 0.05 substeps.

- **Knockback** is applied: `set_entity_motion` for our entity id sets velocity,
  cancels the current path, and the body carries the shove out. Previously the
  packet was ignored and the bot walked through hits.
- **Falling** happens: anything not driven along a path is integrated, so a bot
  left in the air (teleport, or the block under it mined) drops instead of
  hovering. Landings over 3 blocks are reported and stored for survival.
- **Jump arcs use vanilla's recurrence** (`vy = (vy - 0.08) * 0.98` from 0.42)
  instead of a symmetric parabola. The old curve landed short, the server
  disagreed with the reported position, and every landing came back as a
  correction plus a replan.
- Verified: a walk with four consecutive 2x2 jumps arrived with no corrections.
- 🟡 While a path runs, the movement module owns the position and physics yields;
  the two do not yet blend (no velocity-driven pathing).
- ❌ No sprint, no crouch-edge protection, no ladder climbing, no buoyancy.

## 7. Pathfinding 🟡

A* over standable cells with hazard, water and door costs.

- Slabs, stairs, carpets and snow layers count as floors (they are not "solid").
- Lava, fire, cactus and powder snow cost 12 so a dry detour wins.
- Closed wooden doors and fence gates are traversable and the mover opens them;
  iron doors are not.
- Unloaded chunks are never pathed through.
- ❌ No ladders, no scaffolding, no parkour beyond a 2-block jump.

## 8. Mining ✅

- One `START_DESTROY_BLOCK`, then wait: the server owns the progress. The old
  code re-sent START each round, which reset progress to zero every time — that
  was the "no strength" and the "cancels just before it breaks".
- Reach is 3.5 from the eye to the block centre (4.5 was outside what servers
  accept) and the job walks to an adjacent cell first.
- Line of sight is raycast before breaking, so it cannot mine through walls.
- Best hotbar tool is selected automatically; wrong-tool mining is refused with
  a message instead of taking seven seconds for nothing.
- Drops are collected by stepping onto the item with a tight path goal, verified
  by inventory count.
- Exact block matching: asking for iron ore no longer mines diamond ore.
- Verified: `mine 5 iron ore` → 5/5, correct ore, `picked up raw iron` each time,
  zero disconnects.
- Debug-only: `BOT_MINE_AREA="x,y,z,radius"` confines mining to a box.

## 9. Crafting 🟡 (owned by the SfusCraft agent)

- Recipe data and the planner are done: `craft me a pickaxe` resolves to
  `mine 1 oak_log → craft 4 oak_planks → craft 4 stick → craft 1 wooden_pickaxe`,
  including tag resolution and decompression-recipe filtering.
- Grid placement, table crafting/placement and result taking are written but not
  verified past opening a table.
- The packet that was killing sessions (`use_item_on`) is fixed.

## 10. Smelting 🟡

`SmeltJob` loads input and fuel, polls the output slot and takes the result.
Written, not yet exercised.

## 11. Combat 🟡

- `attack` (0x01) is a verified packet and is sent when survival decides to
  fight; damage and death are tracked from `damage_event`, `hurt_animation` and
  `player_combat_kill`.
- ❌ No cooldown timing, no crits, no shield use, no per-mob tactics.

## 12. Survival 🟡

- Health, food and saturation are tracked; low health triggers a retreat.
- Eating: selects a food item, holds `use_item`, releases after the animation.
- ❌ No fall-damage prediction, no lava escape, no drowning response.

## 13. Entity tracking 🟡

Add, move, remove, equipment and pose are tracked; names resolve through
`entity_types.tsv`. ❌ No mob classification into threat/passive/prey yet.

## 14. Chat and natural language ✅

One parser (`ChatCommands`), natural language only:

```
mine 10 stone | mine 5 iron ore here | get 32 oak logs
craft me a pickaxe | smelt 10 iron ore | come here | stop
where are you | inventory | help
```

- Removed as commands: `scan`, `find`, `where`, `use`, `click`, `slot`,
  `container`, `close`. Those are things the bot does for an intent, not a
  command surface for players.
- Every intent checks the inventory first (`I already have 43 cobblestone`) and
  the tools (`I need a pickaxe to mine stone, and I don't have one`).
- Headless control: `BOT_JOB="mine 10 stone"` at startup, and `bot.cmd` polled
  once a second for a 24/7 bot.

## 15. AI integration 🟡

An Ollama-backed chat module answers non-command messages with world state in the
prompt. Tool calls into movement/mining exist but are thin.

- Key resolution no longer depends on environment inheritance: argument →
  `OLLAMA_API_KEY` → `-Dmcbot.ollama.key` → `ollama.key` → Windows User
  registry. A BOM, quotes and trailing newlines are stripped from the value.
- Verified live (2026-10-28): the bot process had **no** `OLLAMA_API_KEY` in its
  environment — the terminal predated `setx` — and still answered a real player
  in 5.2 s, resolving via "OLLAMA_API_KEY (Windows user environment)". Before
  this, the same launch logged `no OLLAMA_API_KEY set - chat is disabled` and the
  bot stayed silent, which read as the bot ignoring chat.
- The start-up line now names the source, so a missing key is visible at boot
  instead of one silent message at a time.


## 16. Perception (scanning) ✅

A block scan over the loaded chunks with a precomputed state-id table. The first
version allocated a String per block — about ten million allocations per scan,
which turned a 32-block search into minutes of GC thrash. ❌ No entity or
threat scan feeding the planner yet.

## 17. Persistence ❌

Nothing survives a restart: no remembered base, chests, or in-progress goals.

## 18. Resilience ✅

- Every module is isolated: an exception is logged once per packet id and the
  connection stays up. Before this, one unexpected packet killed the session.
- Jobs are cancellable and single-owner, so "stop" means one thing.
- Failures are reported with context (`around me I see deepslate, stone,
  andesite`) rather than a bare "failed".

## 19. Observability 🟡

Structured log prefixes (`[bot] [world] [mine] [job] [move] [craft] [cmd]
[pkt]`), `BOT_DEBUG=1` packet tracing, and two offline checkers
(`mcbot.dev.RegistryCheck`, `mcbot.dev.IntentCheck`). ❌ No metrics or replay.

## 20. Build and tooling ✅

- `tools/gen_data.py` regenerates every registry table from a vanilla server dump
  (`net.minecraft.data.Main --all`), so a version bump is a data regeneration,
  not a code change.
- `build.sh` compiles and packages `sfus.jar` with the data resources; `javac`
  alone produces a jar that loads zero blocks, which is an easy trap.

---

## 21. Mechanics knowledge ✅

Every gameplay table the bot uses is generated from the server build by
`tools/gen_data.py`; nothing is typed in except mob health/damage and block
hardness (neither is in a data report).

- `loot_blocks.tsv` (1,318 rows) and `loot_entities.tsv` (138): item, min/max,
  chance and the flags that gate the drop (silk touch, shears, fortune, smelted,
  conditional, player kill). Nested `all_of`/`any_of`/`inverted`/`table_bonus`
  conditions are folded correctly, so oak saplings are 5% rather than 100%.
- `food.tsv` (44), `armor.tsv` (38), `combat.tsv` (42), `enchantments.tsv` (43),
  `damage_types.tsv` (51), `mobs.tsv` (161 classified).
- `knowledge.Loot/Food/Armor/Combat/Mobs/Enchantments/DamageTypes` expose them;
  `knowledge.Mechanics` joins them into block/item/loadout answers.
- The planner now takes drops from the loot table, so "mine 10 stone" knows it
  will get cobblestone and "get diamonds" plans diamond ore.

## 22. Action API ✅

`mcbot.api.Actions` — dig, digBlock, collect, craft, smelt, equip, equipBestArmor,
eat, drop, openContainer, deposit, withdraw, attack, defend, flee, place, wall,
platform, shelter, sleep, inspect. Each checks preconditions and verifies the
result; blocking calls have timeouts.

`mcbot.api.Reasoner` answers where-to-get / what-is / what-can-I-make /
what-should-I-do from the tables, and `SelfModel` reports live state. Anything
they cannot answer exactly returns null, and only then is the chat model used.

## 23. Behaviour modules ✅

- `ThreatModule` classifies every tracked entity (5 Hz) into hostile / neutral /
  passive / boss and ranks them; survival now ignores cows and prefers the
  skeleton that is actually shooting.
- `LoadoutModule` equips armour upgrades and keeps a weapon and food in the
  hotbar, but only when idle — never during a mining job.
- Survival eating now uses the food table (best value, real eat time, shared
  eating flag), and the threat list feeds target selection.
- Building: verified single placements, walls, floors, shelters, beds.

## 24. Testing ✅

142 tests in 19 suites, runnable with `./tools/test.sh`, no server needed.
Includes a sweep over all 1,658 items and all 35,723 block states, fuzzed
parsers, and an end-to-end login against an in-process protocol-777 server that
also sends a malformed packet to prove the session survives it.

Bugs the suite caught: jump height 0.83 instead of 1.25 (gravity applied before
the move), hand-mining dirt 2.5 s instead of 0.75 s (`mineable/shovel` treated
as a requirement), `findSlots` returning armour slots, air/water "dropping
themselves", dynamic loot entries in the drop list, and all loot probabilities
collapsing to 1.0.

## 25. Reconnect and single instance ✅

- `Main` runs one process with many sessions: every disconnect rebuilds the bot
  (world, inventory, jobs and module state all belong to a connection) and
  retries with 2/4/8/16/30 s backoff. A session over a minute resets the
  backoff; `BOT_RECONNECT=0` restores the one-shot behaviour.
- `sfus.lock` (a `FileLock`, released by the OS on crash) stops a second copy
  from starting on the same account.
- Verified: a closed port produces `reconnecting in 2s`, then `4s`, without
  exiting.

## Next, in order

1. **Crafting execution against a live server** — the planner and the click
   sequence are tested; the round trip is not.
2. **Enchantment decoding** — the component patch is currently skipped, so an
   enchanted tool is valued as a plain one.
3. **Persistence** — remember the base, the last job and known containers so a
   restart is not a reset.
4. **Trading and enchanting** — the two remaining interaction surfaces with real
   game mechanics behind them.
