# Testing

142 offline tests across 19 suites, no server required.

```
./tools/test.sh
# or
javac -d out $(find src -name "*.java")
java -cp "out;src" mcbot.dev.TestAll
```

The runner prints one line per test and exits non-zero on any failure, so it
works as a build gate. It needs nothing but a JDK: the harness is ~150 lines in
`src/mcbot/dev/test/TestRunner.java`, deliberately not JUnit, because the bot
ships as a single jar with no test dependencies.

Last run: **142 passed, 0 failed, 0 skipped, ~8 s** (JDK 25, Windows).

## Suites

| Suite | Tests | What it pins down |
| --- | ---: | --- |
| codec | 12 | VarInt (incl. negative and over-long), UTF-8 strings, BlockPos 26/12/26 packing, UUIDs, packet framing, bounds checks |
| registry | 20 | Every state resolves; contiguous state ranges; property decode; slab/door shapes; vanilla hardness; tool class and tier; hazards; tags; menu layouts |
| knowledge: loot | 8 | Diamond ore → diamond, stone → cobblestone, grass → dirt, leaves → nothing; rare-drop probabilities; every drop is a real item |
| knowledge: food and armour | 6 | Food values, always-eat, eat time; armour points and toughness; the vanilla damage formula; protection cap |
| knowledge: combat | 4 | Weapon damage/speed, 13-tick sword cooldown, damage scaling with charge, criticals, shields |
| knowledge: mobs and effects | 5 | Mob stats and dispositions, threat scoring, hits-to-kill, enchantment maxima, damage-type classification |
| knowledge: mechanics | 6 | Block descriptions carry tool and drop; hand-mining times; missing-tool explanations; item descriptions; loadout line |
| recipes | 17 | All 1,681 recipes valid; shaped recipes fit 3x3; the pickaxe ladders (wood → stone → iron → diamond); fuel; decompression filtering; readable plans |
| inventory | 14 | Wire parsing, slot mapping (menu / player / wire), armour slots, held slot, tool queries, stack aggregation, state ids |
| physics | 5 | Gravity, 1.25-block jump, landing, fall distance, wall collision |
| pathfinding | 5 | Open path, routing around a wall, sealed box has no path, one-block step, lava avoidance |
| robustness | 7 | Random bytes into the chunk, inventory, stack and chat parsers; truncated packets; absurd lengths; state survives a rejected packet |
| sweep: every item | 3 | All 1,658 items described; all planned without throwing (1,312 plannable, deepest 26 steps) |
| sweep: every block | 4 | All 35,723 states described; every drop answer coherent; every block resolves by name; collision classification total |
| api: actions | 2 | Actions refuse cleanly when not in the world |
| api: reconnect | 3 | Backoff doubles and caps, a healthy session resets it, enabled by default |
| api: self knowledge | 3 | Self model text, capability list, offline inventory |
| api: reasoning | 10 | Question routing, where-to-get, explain, craftable-now, advice priority |
| ai tools | 5 | The 22-tool JSON schema parses, names are unique, required fields exist |
| integration | 2 | Full handshake → login → compression → config → PLAY against an in-process server; keepalive echo; player_loaded; survival of a malformed packet |

## What the tests found

Every one of these was a real bug, not a hypothetical:

- **Jump height was 0.83 blocks, not 1.25.** The integrator applied gravity
  before the move; vanilla applies it after (`LivingEntity.travel`). A bot that
  cannot clear a one-block gap is not a bot that can path.
- **Hand-mining dirt took 2.5 s instead of 0.75 s.** `canHarvest` treated the
  `mineable/shovel` tag as a requirement. Only blocks in `needs_*_tool` require
  the tool; the rest just get a speed bonus.
- **`findSlots` returned armour slots.** Equipping a helmet shift-clicked the
  helmet slot it was trying to fill. It now reports main inventory and hotbar
  only.
- **Air and water "dropped themselves".** The no-loot-table fallback needs to
  check that the block has an item form at all.
- **Dynamic loot entries leaked into the drop list** (`@sherds` from a decorated
  pot). Dynamic and tag entries are no longer treated as guaranteed drops.
- **Loot probabilities were all 1.0.** Chance conditions nest inside `all_of` /
  `any_of` / `inverted` / `table_bonus`, and a float passed by value lost the
  multiplication. Oak saplings are 5%, sticks 2%, apples 0.5% - now they are.
- **`explain("zombie")` described a zombie head.** Fuzzy block resolution ran
  before the mob table; exact names now win, and mobs are checked first.
- **"Where do I get rotten flesh" answered "camel husk".** Sources are now
  ranked by drop chance, then how ordinary the mob is.
- **The chat tool schema is now parsed in a test.** A single missing brace in
  that 5 KB string would make every model request fail with a 400 the bot never
  sees.

## Integration test

`FakeServer` is a protocol-777 server in ~250 lines: handshake, login success,
compression at 256 bytes, configuration, play login with a real spawn block, an
empty chunk batch, a health packet, a keepalive, an unknown packet id and a
truncated chunk packet.

It asserts that the bot reaches PLAY, reads its entity id, negotiates
compression, applies health, echoes the keepalive with the same value, sends
`player_loaded`, and **stays connected** after the malformed packets. That last
one is the module-isolation guarantee: a parser that throws costs that packet,
not the session.

## Not covered by tests

- Anything that needs a live server with terrain: chunk parsing against a real
  chunk, block breaking, container clicks. The packet layouts are covered, the
  round trip is not.
- The language model path: the schema is validated, the HTTP call is not.
- Long-run stability (hours of play, memory growth).
