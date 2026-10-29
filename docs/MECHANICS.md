# Mechanics

What this bot knows about Minecraft, where each fact comes from, and what it can
actually do with it. Everything below is for **26.3 / protocol 777** only: the
state ids, item ids, recipe list and loot tables are all generated from that
exact server build.

The rule throughout: **no fact is typed in by hand if the server can be asked**.
`tools/gen_data.py` reads the server's own reports and data pack, and the Java
side only reads the resulting tables. The one exception is mob health and melee
damage, which no data report contains - they are constants in each entity class -
and that table is marked as curated.

```
java -cp "server-26.3.jar;libraries/*" net.minecraft.data.Main --all   # -> generated/
python tools/gen_data.py .tmp/gen/generated                            # -> src/mcbot/data/
```

## Where each mechanic comes from

| Mechanic | Source | Loaded by |
| --- | --- | --- |
| Block states, properties, ids | `reports/blocks.json` | `registry.Blocks` |
| Block hardness | curated (constructor argument) | `registry.Blocks` |
| Tool class / required tier | `data/minecraft/tags/block/mineable/*`, `needs_*_tool` | `registry.Blocks` |
| Item ids, stack size, durability | `reports/registries.json`, item components | `registry.Items` |
| Tool speed and tier | `minecraft:tool` component | `registry.Items` |
| Recipes (all 1,681) | `data/minecraft/recipe/**` | `crafting.Recipes` |
| Tags (nested expanded) | `data/minecraft/tags/**` | `registry.Tags` |
| Block and mob drops | `data/minecraft/loot_table/**` | `knowledge.Loot` |
| Food values and effects | `minecraft:food`, `minecraft:consumable` | `knowledge.Food` |
| Armour slot, defence, toughness | `minecraft:equippable`, attribute modifiers | `knowledge.Armor` |
| Weapon damage and attack speed | attribute modifiers | `knowledge.Combat` |
| Enchantments | `data/minecraft/enchantment/**` | `knowledge.Enchantments` |
| Damage types | `data/minecraft/damage_type/**` | `knowledge.DamageTypes` |
| Mob health / damage / disposition | curated table + entity registry | `knowledge.Mobs` |
| Window layouts | `reports/registries.json` (menu) | `inventory.MenuLayout` |
| Packet ids | `reports/packets.json` | `net.PacketIds` |

## Survival

Tracked from `set_health` (health, food, saturation), `damage_event`,
`hurt_animation`, `entity_status` and `player_combat_kill`.

- **Damage source identification**: `damage_event` gives the attacker id first;
  falling back to the hurt animation on our own entity, then to a health drop.
- **Retreat rules**: health <= 6 always; health <= 10 with food <= 6; a player
  attacker when PvP is disabled (the default).
- **Kiting**: below 14 health the bot steps out of reach after each hit.
- **Death**: stops movement and mining, says so, and hands off to respawn.
- **Fall distance** is accumulated by the physics integrator, so a landing is a
  fact rather than a guess.

## Eating

`knowledge.Food` holds nutrition, saturation, eat time and on-consume effects for
all 44 edible items.

- The bot eats when food < 18 or (food < 20 and health < 20) - vanilla only
  regenerates at food >= 18, so eating at 17 is worth it and at 20 is waste.
- It picks the best *value* (nutrition + saturation), not the first edible item.
- Golden apples are deprioritised for ordinary hunger: they are a combat item,
  and the absorption is wasted at 17 food.
- The eat animation is per item (`eat_seconds`), plus the four-tick consume
  delay; releasing early leaves the food half-eaten, which used to read as
  "eating does nothing".
- `Food.canEat` mirrors vanilla: full hunger blocks everything except food
  marked `can_always_eat`.

## Defence and combat

`knowledge.Armor` and `knowledge.Combat`, plus the mob table.

- **Damage formula** (vanilla, not linear):
  `f = 2 + toughness/4`, `effective = min(20, max(defence/5, defence - damage/f))`,
  `taken = damage * (1 - effective/25)`. Full diamond turns 20 damage into 8;
  a 100-damage hit still gets 84 through, which is why armour alone is not a
  plan.
- **Attack cooldown**: `20 / attack_speed` ticks. A diamond sword is 13 ticks
  (625 ms); clicking faster deals `0.2 + progress^2 * 0.8` of the damage.
- **Criticals**: falling, not on the ground, not sprinting - 1.5x, and only at
  full charge.
- **Shields**: block all of a hit from the front; an axe still does 5.
- **Threat model**: every tracked entity is classified hostile / neutral /
  passive / boss. A cow is never a threat, an enderman is neutral until hit, and
  an unknown entity type is treated as dangerous only when nothing classified is
  in range.
- **Loadout**: armour upgrades are equipped automatically when the bot is idle,
  and a weapon plus food are kept in the hotbar so a fight needs no inventory
  click.

## Mining

- **Break time** is computed from hardness, tool speed and the harvest flag, and
  matches vanilla: stone by hand 7.5 s, stone with a wooden pickaxe 1.15 s,
  obsidian with diamond 9.4 s.
- **Drops** come from the loot table, so the bot knows stone yields cobblestone,
  grass yields dirt, diamond ore yields a diamond, and oak leaves guarantee
  nothing. A block with no guaranteed drop is never planned as a source.
- **Tool selection** searches the whole inventory, restricts to the tool class
  the block asks for, and refuses to mine when drops would not happen rather
  than spending seven seconds for nothing.

## Crafting and smelting

1,681 recipes, all five types (shaped, shapeless, cooking, stonecutting,
smithing), with tag ingredients resolved against the live inventory.

The planner builds an execution plan and refuses to emit one that cannot finish:

- gathers an item when the world can provide it, else crafts it, else smelts it;
- plans the tool needed to mine an ingredient (the whole ladder from logs to a
  wooden pickaxe to stone to iron);
- plans fuel for a smelt, and prefers held fuel over burning the planks the rest
  of the plan needs;
- filters decompression recipes ("9 ingots from an iron block") that would
  otherwise plan to already own the thing being made;
- terminates on recipe loops and reports the missing item by name.

## Building

`api.Actions` places blocks by choosing a support face explicitly - a placement
is always "right-click this face", so a target with no solid neighbour fails
locally instead of on the server. Walls, floors and a minimal shelter are built
from whichever bulk block the bot owns (cobblestone, dirt, planks), one verified
placement at a time.

## Interaction and containers

- Right-click on blocks (tables, furnaces, chests, doors, buttons, beds).
- Window tracking with the real menu layout: container slots first, then the 27
  main slots, then the hotbar. The player's own menu is the exception (result,
  2x2 grid, armour, main, hotbar, offhand).
- **Click state ids are echoed**, never invented. A rejected click is detected
  because the server answers with a full resync instead of a slot update, and
  the click is retried once with the corrected id.
- Deposit and withdraw are shift-clicks, so the server chooses the destination
  slot.

## Inventory

- Stacks parse from the wire including the component patch; a patch this parser
  cannot skip marks the window opaque instead of corrupting the rest.
- Three different slot numberings are kept apart: menu slots, the player's own
  0..45 menu, and `set_player_inventory`'s 0..40 wire numbering.
- Queries: count, find slots, free space, best tool for a block, armour worn,
  contents aggregated by name.

## Movement and physics

- 3D A* over the loaded block cache, with step-up, drops, hazard costs, doors
  and no pathing through unloaded chunks.
- The integrator follows vanilla's `LivingEntity.travel` order: friction, move,
  then gravity and drag. That is what makes a 0.42 jump peak at 1.2522 blocks
  instead of 0.83 - the difference between clearing a gap and not.
- Collision is swept in 0.05 substeps against the block cache, so a wall stops
  the body and a floor lands it exactly on the surface.

## Self-knowledge and reasoning

`api.SelfModel` reports position, dimension, game mode, health, hunger, gear,
inventory, threats and current activity from live state.

`api.Reasoner` answers, deterministically and without a language model:

| Question | Answer source |
| --- | --- |
| what can you make? | every recipe checked against the inventory |
| can you make X? | the planner, with the missing step named |
| where do I get X? | loot tables (blocks, then mobs), then recipes and smelting |
| what is X? | block facts, item components, or the mob table |
| what should you do? | hunger, health, armour upgrades, threats |
| what do you know? | the coverage summary |
| how are you? | the self model |

Anything it cannot answer exactly returns null, and only then is the chat model
asked. That keeps the cheap path cheap and the model's answers grounded: it has
a `game_knowledge` tool that routes back into this class instead of recalling a
recipe.

## Action API

`mcbot.api.Actions` is the layer a script or the chat model uses. It checks
preconditions, performs the action through the modules, and verifies the result.

| Action | Notes |
| --- | --- |
| `gotoPosition`, `gotoAndWait`, `gotoBlock` | the waiting variants are blocking |
| `dig`, `digBlock`, `collect` | `collect` mines, crafts or smelts as needed |
| `craft`, `smelt` | starts a job |
| `equip`, `equipBestArmor`, `equipBestWeapon` | armour via shift-click, tools to the hand |
| `eat` | blocking for the real eat time |
| `drop` | whole stacks or a count |
| `openContainer`, `deposit`, `withdraw`, `closeContainer` | shift-click transfers |
| `attack`, `defend`, `flee` | `defend` equips first |
| `place`, `wall`, `platform`, `shelter` | verified placements |
| `sleep` | finds a bed and uses it |
| `inspect`, `selfReport` | knowledge queries |

## What is not covered

Honest list, so nobody assumes more than exists:

- No online-mode login (offline UUID only).
- No enchanting table flow, anvil, brewing, trading, fishing, boats, minecarts,
  elytra or redstone logic.
- No nether portal construction or dimension travel beyond what the server
  sends.
- No chest memory between sessions (nothing is persisted).
- Mob health and damage are curated, not generated; a mob missing from the table
  is classified by name and treated as unknown strength.
- Enchantments on items the bot picks up are not decoded (the component patch is
  skipped), so a Sharpness V sword is valued as a plain one.
