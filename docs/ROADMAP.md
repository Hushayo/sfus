# Roadmap

The order below is deliberate: each step removes a piece of "the bot doesn't know
something the client would", which is what blocks everything after it.

## 1. Inventory awareness (blocking)

Right now the bot has no model of its own inventory. It cannot see what it is holding,
which is why mining polls the world cache instead of computing block hardness, and why
crafting cannot decide whether a recipe is even possible.

Needed:

- parse `set_content` / `container_set_content` item stacks (count, item id, components)
- track `set_slot`, `set_held_item`, `set_player_inventory`
- expose `Inventory.count(itemId)`, `Inventory.heldItem()`, `Inventory.hasSpace()`

Item stacks in 1.21.5+ are `count` + `item id` + added/removed component patch sets. The
component payload is the awkward part; ignoring it and reading only `count`/`item id` is
enough for crafting decisions.

## 2. Recipe knowledge

A `RecipeBook` loaded from a data file:

```
oak_planks  <- oak_log                     (shapeless, 1)
stick       <- oak_planks x2               (shaped, 2 vertical)
crafting_table <- oak_planks x4            (shaped, 2x2)
```

Source options, in order of preference:

1. a small hand-written table for the ~30 recipes that actually matter to a bot
   (planks, sticks, table, furnace, tools, torch, chest)
2. a dumped recipe json from the server's data pack
3. server-side recipe book packets, if the server enables them (`place_recipe`)

Keep it to a data file: recipes change between versions and should not require a rebuild.

## 3. Crafting execution

- walk to the table, `use_item_on` it, wait for `open_screen`
- 2x2 in the player inventory (window 0, slots 1..4) vs 3x3 in the table (slots 1..9)
- click sequence per slot: `click(slot, 0, 1)` to place one, `click(slot, 1, 1)` for right
  click; then shift-click the result slot (`click(resultSlot, 0, 5)`)
- verify by re-reading the window contents, retry once on desync

Known risk: the changed-slot list is sent empty, so the server may reject a click it
considers impossible. Reading the window after each click and retrying is the safe path.

## 4. Furnaces

- open the furnace, put fuel in slot 1 and input in slot 0
- poll `container_set_content` for the output slot and the burn/progress properties
- the furnace menu carries extra data (burn time, cook progress) via `container_set_data`;
  parse it to know when the smelt is done instead of guessing

## 5. Better combat

- shield blocking (hold `use_item` while a shield is in the offhand)
- critical hits: jump, then attack on the way down
- strafe around the target instead of standing still
- eat timing: stop attacking, back off, eat, resume
- per-mob tactics (creeper: keep 4+ blocks and retreat after the swing; skeleton: close
  fast; enderman: do not look at it unless fighting)

## 6. Pathfinding polish

- parkour jumps over gaps wider than one block
- water/lava avoidance weighting instead of treating liquid as impassable
- doors and gates (right-click then path through)
- mine-through when the only route is blocked and the bot has a pickaxe

## 7. Multi-agent

- shared blackboard of explored chunks so several bots do not re-scan the same area
- simple job queue (mine / build / follow) with claim/complete semantics
