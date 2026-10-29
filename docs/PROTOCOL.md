# Protocol 777 — what is actually verified

The bot speaks raw protocol 777 with no Minecraft library, so **every packet id in
it is a guess**. A wrong id does not throw: the bot simply never sees the packet
and the feature built on it silently does nothing. That is the single biggest
reason a feature "works in code" and not on the server.

This document records which ids have evidence behind them, which have been
**disproved**, and which are still guesses. It is generated from captured
traffic, not from memory.

Tool: `tools/pktstat.py` (read-only with respect to `src/`).

```bash
python tools/pktstat.py analyze logs/bot.log --state PLAY --top 25   # what arrives
python tools/pktstat.py burst  logs/bot.log --n 24                   # join fingerprint
python tools/pktstat.py ids                                       # what the code claims
python tools/pktstat.py probe  --host 51.195.191.92 --port 10679 --out capture.log
```

`analyze` parses the `[pkt] in state=... id=0x.. len=..` lines the bot already
writes when `BOT_DEBUG=1`. `probe` produces the same format from a fresh session.

`probe` was verified against `tools/fake_server.py` (a local server that speaks
just enough of the handshake): it completes LOGIN -> CONFIG -> PLAY, turns
compression on at the right moment, and echoes `keep_alive` at `0x1C`. It has
**not** been run against the live server — that is a deliberate choice, since
joining it puts a bot in front of real players. Run it when you want the ids
re-derived:

```bash
python tools/fake_server.py 25599 &          # offline smoke test
python tools/pktstat.py probe --host 127.0.0.1 --port 25599 --seconds 4
```

---

## Evidence base

| | |
|---|---|
| Capture | `logs/bot.log` (`BOT_DEBUG=1`); counts below are from the original capture |
| Sessions | 1 complete login (LOGIN -> CONFIG -> PLAY) |
| Inbound packets | 516,866 |
| Distinct ids in PLAY | 57 |
| States | LOGIN 2, CONFIG 37, PLAY 516,866 |

One session is enough to disprove an id (a packet that must be sent at join and
is not), and enough to strongly suggest one (a packet that appears exactly once,
at join, with the exact body size the format implies). It is not enough to
confirm ids that only fire in situations the session never reached — combat,
containers, item use.

---

## Confirmed

Body size is arithmetic, not opinion. `player_abilities` is
`byte + float + float` = 9 bytes; `game_event` is `byte + float` = 5.

| id | packet | evidence |
|---|---|---|
| `0x32` | login (play) | first PLAY packet, 138 B, parses as the login record |
| `0x41` | player_abilities | exactly 9 B, 3 occurrences (join + game mode changes) |
| `0x27` | game_event | exactly 5 B, 8 occurrences, fires 4x at chunk load |
| `0x2E` | level_chunk_with_light | 506 pkts, 21 KB–50 KB, avg 35 KB |
| `0x08` | block_update | 2,640 pkts, 9–11 B (`long pos + VarInt state`) |
| `0x56` | section_blocks_update | 18,354 pkts, 17–117 B, multi-block records |
| `0x26` | forget_level_chunk | exactly 8 B (`ChunkPos` long) |
| `0x2D` | keep_alive | exactly 8 B, 453 pkts; echo at `0x1C` keeps the link up |
| `0x49` | player_position | 61 B; accept at `0x00` stops the server correcting us |
| `0x01` | add_entity | 531 pkts, 47–54 B (`id + UUID + type + pos`) |
| `0x23` | entity_position_sync | 27,943 pkts, 36–38 B (3 doubles + header) |
| `0x4E` | remove_entities | 427 pkts, 2–3 B (count + ids) |

## Disproved — these ids are wrong

| id in code | claimed | why it is wrong |
|---|---|---|
| `0x5B` | `SET_HEALTH` | **Never sent.** A join always syncs health. 0 packets in 516,866. |
| `0x18` | `DAMAGE_EVENT` | 2 pkts of 33 B and 94 B, both at the start of PLAY. A damage event is 4 VarInts (≤10 B); this is entity metadata. |

`SET_HEALTH` is almost certainly **`0x6A`**: exactly one packet per session, 9
bytes (`float health + VarInt food + float saturation`), arriving at join
immediately before `0x69` (6 B, `float + VarInt + VarInt` — experience). Both
sizes match the format exactly and both fire once per login, as they should.

> Anything reading health through `0x5B` will read `20.0` forever and never see
> damage. That includes any survival/auto-eat logic.

**Fixed in code:** `PacketIds.SET_HEALTH` now defaults to `0x6A`, and
`PacketIds.DAMAGE_EVENT` is disabled (`-1`) rather than parsing entity metadata
as a damage event. Survival falls back to the health drop and the hurt animation
until a combat capture proves a real damage-event id.

## Still guesses — never exercised in the capture

These were not refuted, only never triggered. Do not build on them without a
capture that exercises them.

| id | claimed | how to exercise it |
|---|---|---|
| `0x1D` | `entity_status` (hurt) | take damage from a mob or player |
| `0x33` | `open_screen` | right-click a chest / crafting table |
| `0x13` | `container_close` | open then close a container |
| `0x4A` | `set_held_item` | switch hotbar slot 1 -> 2 |
| `0x12` | `container_set_content` | 2 pkts of 50 B at join = own inventory; plausible, unconfirmed |

## Unclaimed traffic — the expansion surface

Inbound ids the bot does not parse at all, by volume. This is what any new
feature (combat, inventory, crafting, chat UI) has to be built on.

| id | count | size | shape note |
|---|---|---|---|
| `0x67` | 187,197 | 3–8 B | most frequent packet in the session, 8 B when saturated |
| `0x55` | 72,451 | 2–3 B | very high rate, tiny body |
| `0x07` | 11,122 | exactly 12 B | fires in bursts of 4 around chunk loads |
| `0x8D` | 5,049 | 38–49 B | |
| `0x22` | 1,251 | exactly 5 B | |
| `0x86` | 1,135 | 13–80 B | |
| `0x00` | 1,062 | 0 B | empty body; interleaves with entity traffic |
| `0x65` | 1,060 | 6–23 B | 9 B variant is common — **not** health, too frequent |
| `0x73` | 467 | 9–33 B | |
| `0x47` | 264 | 35–462 B | |
| `0x77` | 166 | exactly 31 B | |
| `0x68` | 126 | exactly 8 B | |
| `0x6A` | 1 | exactly 9 B | **health** — see above |
| `0x69` | 1 | exactly 6 B | **experience** (likely) |
| `0x31` | 33 | 2–8 KB | |
| `0x85` | 1 | 24,117 B | largest single packet, fires at join |

`pktstat analyze` prints the full list (37 ids in this capture).

---

## Join fingerprint

The first 24 inbound packets of a session. Any change here means the server
build moved and every id in this document must be re-derived.

```
  1  PLAY   0x32     138 B   login (play)
  2  PLAY   0x18      33 B   (claimed damage_event, size says otherwise)
  3  PLAY   0x0A       2 B   ?
  4  PLAY   0x41       9 B   player_abilities
  5  PLAY   0x6B       1 B   ?
  6  PLAY   0x88    3761 B   ?
  7  PLAY   0x22       5 B   ?
  8  PLAY   0x4D       8 B   ?
  9  PLAY   0x4B     208 B   ?
 10  PLAY   0x49      61 B   player_position
 11  PLAY   0x58    7324 B   ?
 12  PLAY   0x2C      40 B   ?
 13  PLAY   0x73      33 B   ?
 14  PLAY   0x63      36 B   ?
 15  PLAY   0x27       5 B   game_event
 16  PLAY   0x27       5 B   game_event
 17  PLAY   0x27       5 B   game_event
 18  PLAY   0x27       5 B   game_event
 19  PLAY   0x82       5 B   ?
 20  PLAY   0x83       1 B   ?
 21  PLAY   0x8D      48 B   ?
 22  PLAY   0x12      50 B   container_set_content (own inventory)
 23  PLAY   0x14       5 B   ?
 24  PLAY   0x18      94 B   ?
```

## Re-deriving after a server upgrade

1. `BOT_DEBUG=1 java -cp out mcbot.Main ...` for one session (it writes
   `logs/bot.log`), or `pktstat.py probe --out logs/probe.log`.
2. `pktstat.py analyze logs/bot.log --state PLAY` — compare against the tables
   above; a changed `login`/`chunk`/`keep_alive` size means ids shifted.
3. `pktstat.py burst logs/bot.log --n 24` — diff against the fingerprint.
4. Only then update `PacketIds.java`, and prefer `-Dmcbot.packet.<name>=0x..`
   over a recompile while you are still guessing.

## Rules that came out of this

- Never trust an id because the packet name sounded right. Look for the packet
  in a capture.
- An id that "should" fire at join but never appears is disproved, not untested.
- Health, food and inventory all have a fixed size. If the size does not match
  the format you assumed, the id is wrong even if the packet rate looks right.
