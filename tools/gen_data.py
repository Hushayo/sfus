#!/usr/bin/env python3
"""Turn a vanilla server's data reports into the compact tables mcbot loads.

The bot has no Minecraft library, so it has no block/item/recipe knowledge of
its own. Everything it knows about the game comes from this script, which reads
what the *actual* server build says about itself:

    generated/reports/blocks.json          every block state and its id
    generated/reports/registries.json      block/item/entity protocol ids
    generated/reports/minecraft/components/item/*.json
                                           per-item components (stack size,
                                           damage, food, tool rules, ...)
    generated/data/minecraft/tags/**       block/item tags (tool classes, tiers)
    generated/data/minecraft/recipe/**     every crafting/cooking recipe

Produce those first:

    java -cp "server-26.3.jar;libraries/*" net.minecraft.data.Main --all
    # -> generated/

then:

    python tools/gen_data.py .tmp/gen/generated

Output goes to src/mcbot/data/ and is loaded by mcbot.registry.*. Nothing here
is hand-maintained except HARDNESS below, which the reports do not contain.

Why the extra work on property order: blocks.json lists each state's properties
explicitly, but lists the *property names* alphabetically. Vanilla assigns state
ids by iterating the block's StateDefinition, whose property order is insertion
order, not alphabetical. For ~230 blocks those differ, so a naive mixed-radix
decode returns the right block name with the wrong properties. The script
brute-forces the real order per block (at most 6 properties, so at most 720
permutations) and asserts that it reproduces every state exactly.
"""

import json
import os
import sys
from itertools import permutations

# ---------------------------------------------------------------------------
# Hardness (destroy time in seconds at hand speed) is NOT in any data report:
# it is a constructor argument in the block class. These are the vanilla values
# for the blocks a bot actually mines; anything missing falls back to
# DEFAULT_HARDNESS in Java. Tool speed and "correct tool for drops" ARE exact,
# from the item's minecraft:tool component.
# ---------------------------------------------------------------------------
HARDNESS = {
    # dirt-like
    "dirt": 0.5, "coarse_dirt": 0.5, "rooted_dirt": 0.5, "grass_block": 0.6,
    "podzol": 0.5, "mycelium": 0.6, "farmland": 0.6, "dirt_path": 0.65,
    "mud": 0.5, "clay": 0.6, "gravel": 0.6, "sand": 0.5, "red_sand": 0.5,
    "soul_sand": 0.5, "soul_soil": 0.5, "snow": 0.1, "snow_block": 0.2,
    "powder_snow": 0.25, "moss_block": 0.1, "moss_carpet": 0.1,
    # stone family
    "stone": 1.5, "cobblestone": 2.0, "mossy_cobblestone": 2.0,
    "granite": 1.5, "diorite": 1.5, "andesite": 1.5, "tuff": 1.5,
    "calcite": 0.75, "dripstone_block": 1.5, "smooth_stone": 2.0,
    "stone_bricks": 1.5, "mossy_stone_bricks": 1.5, "cracked_stone_bricks": 1.5,
    "chiseled_stone_bricks": 1.5, "bricks": 2.0, "deepslate": 3.0,
    "cobbled_deepslate": 3.5, "polished_deepslate": 3.5,
    "deepslate_bricks": 3.5, "deepslate_tiles": 3.5, "bedrock": -1.0,
    "obsidian": 50.0, "crying_obsidian": 50.0, "reinforced_deepslate": 55.0,
    "netherrack": 0.4, "nether_bricks": 2.0, "soul_sand": 0.5,
    "basalt": 1.25, "smooth_basalt": 1.25, "blackstone": 1.5,
    "end_stone": 3.0, "end_stone_bricks": 3.0, "purpur_block": 1.5,
    "sandstone": 0.8, "red_sandstone": 0.8, "smooth_sandstone": 2.0,
    "terracotta": 1.25, "hardened_clay": 1.25,
    "amethyst_block": 1.5, "budding_amethyst": 1.5,
    # ores (stone variants 3.0, deepslate variants 4.5)
    "coal_ore": 3.0, "iron_ore": 3.0, "copper_ore": 3.0, "gold_ore": 3.0,
    "redstone_ore": 3.0, "lapis_ore": 3.0, "diamond_ore": 3.0,
    "emerald_ore": 3.0, "nether_quartz_ore": 3.0, "nether_gold_ore": 3.0,
    "ancient_debris": 30.0,
    "deepslate_coal_ore": 4.5, "deepslate_iron_ore": 4.5,
    "deepslate_copper_ore": 4.5, "deepslate_gold_ore": 4.5,
    "deepslate_redstone_ore": 4.5, "deepslate_lapis_ore": 4.5,
    "deepslate_diamond_ore": 4.5, "deepslate_emerald_ore": 4.5,
    # wood
    "oak_log": 2.0, "spruce_log": 2.0, "birch_log": 2.0, "jungle_log": 2.0,
    "acacia_log": 2.0, "dark_oak_log": 2.0, "mangrove_log": 2.0,
    "cherry_log": 2.0, "pale_oak_log": 2.0, "crimson_stem": 2.0,
    "warped_stem": 2.0, "bamboo_block": 2.0,
    "oak_planks": 2.0, "spruce_planks": 2.0, "birch_planks": 2.0,
    "jungle_planks": 2.0, "acacia_planks": 2.0, "dark_oak_planks": 2.0,
    "mangrove_planks": 2.0, "cherry_planks": 2.0, "bamboo_planks": 2.0,
    "oak_slab": 2.0, "oak_stairs": 2.0, "oak_fence": 2.0, "oak_door": 3.0,
    "oak_trapdoor": 3.0, "crafting_table": 2.5, "chest": 2.5, "barrel": 2.5,
    "furnace": 3.5, "bookshelf": 1.5, "torch": 0.0, "wall_torch": 0.0,
    "ladder": 0.4, "scaffolding": 0.0, "glass": 0.3, "glass_pane": 0.3,
    "glowstone": 0.3, "sea_lantern": 0.3, "redstone_lamp": 0.3,
    # plants and soft blocks
    "oak_leaves": 0.2, "spruce_leaves": 0.2, "birch_leaves": 0.2,
    "jungle_leaves": 0.2, "acacia_leaves": 0.2, "dark_oak_leaves": 0.2,
    "grass": 0.0, "short_grass": 0.0, "tall_grass": 0.0, "fern": 0.0,
    "dandelion": 0.0, "poppy": 0.0, "wheat": 0.0, "carrots": 0.0,
    "potatoes": 0.0, "beetroots": 0.0, "sugar_cane": 0.0, "kelp": 0.0,
    "bamboo": 0.0, "cactus": 0.4, "vine": 0.2, "cobweb": 4.0,
    "hay_block": 0.5, "sponge": 0.6, "wool": 0.8, "white_wool": 0.8,
    "melon": 1.0, "pumpkin": 1.0, "carved_pumpkin": 1.0,
    "jack_o_lantern": 1.0, "nether_wart_block": 0.1,
    "shroomlight": 0.3, "honey_block": 0.0, "slime_block": 0.0,
    "ice": 0.5, "packed_ice": 0.5, "blue_ice": 2.8, "frosted_ice": 0.5,
    "magma_block": 0.5, "soul_fire": 0.0, "fire": 0.0,
    # functional
    "spawner": 5.0, "tnt": 0.0, "note_block": 0.8, "jukebox": 2.0,
    "piston": 1.5, "sticky_piston": 1.5, "observer": 3.0,
    "hopper": 3.0, "dispenser": 3.5, "dropper": 3.5, "lever": 0.5,
    "stone_button": 0.5, "oak_button": 0.5, "stone_pressure_plate": 0.5,
    "rail": 0.7, "powered_rail": 0.7, "anvil": 5.0, "enchanting_table": 5.0,
    "ender_chest": 22.5, "beacon": 3.0, "conduit": 3.0,
    "iron_block": 5.0, "gold_block": 3.0, "diamond_block": 5.0,
    "netherite_block": 50.0, "emerald_block": 5.0, "lapis_block": 3.0,
    "redstone_block": 5.0, "coal_block": 5.0, "copper_block": 3.0,
    "quartz_block": 0.8, "smooth_quartz": 2.0,
    "farmland": 0.6, "composter": 0.6, "cauldron": 2.0,
    "water": 100.0, "lava": 100.0,
}

DEFAULT_HARDNESS = 2.0


def load(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def strip(name):
    return name.replace("minecraft:", "", 1)


def find_order(props, states):
    """Return the property order whose mixed-radix decode reproduces `states`.

    Derived, not guessed: the stride of a property is the smallest state index
    at which its value changes. The fastest-varying property has stride 1, the
    next has stride = radix of that one, and so on. Sorting by stride gives the
    enumeration order directly; the mixed-radix decode is then verified against
    every state before the result is accepted.
    """
    names = list(props.keys())
    if not names:
        return []
    expected = [s.get("properties") or {} for s in states]
    first = expected[0]

    def stride(p):
        for i in range(1, len(expected)):
            if expected[i].get(p) != first.get(p):
                return i
        return len(expected)  # never changes: constant property

    def decode(order, idx):
        out = {}
        for name in reversed(order):
            vals = props[name]
            out[name] = vals[idx % len(vals)]
            idx //= len(vals)
        return out

    ordered = sorted(names, key=stride)  # slowest first, fastest last
    ok = all(decode(ordered, i) == expected[i] for i in range(len(expected)))
    if ok:
        return ordered

    # Stride derivation is ambiguous when two properties share a stride (both
    # constant, for instance). Fall back to trying permutations, cheapest first.
    if len(names) <= 8:
        for order in permutations(names):
            if all(decode(order, i) == expected[i] for i in range(len(expected))):
                return list(order)
    raise SystemExit("no property order reproduces " + str(names))


def gen_blocks(gen, out):
    blocks = load(os.path.join(gen, "reports", "blocks.json"))
    lines = []
    for name, d in sorted(blocks.items(), key=lambda kv: kv[1]["states"][0]["id"]):
        states = d["states"]
        start = states[0]["id"]
        props = d.get("properties") or {}
        order = find_order(props, states)
        spec = ";".join(p + ":" + ",".join(props[p]) for p in order)
        lines.append("%s\t%d\t%d\t%s" % (strip(name), start, len(states), spec))
    write(out, "blocks.tsv",
          "# name\tfirstStateId\tstateCount\tproperty:value,value;...  (last property varies fastest)",
          lines)
    return blocks


def load_tag_tree(folder):
    """All tag files under `folder` as {tag: [members]}, members may be '#other'."""
    tree = {}
    for root, _dirs, files in os.walk(folder):
        for fn in files:
            if not fn.endswith(".json"):
                continue
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, folder)[:-5].replace(os.sep, "/")
            d = load(full)
            tree[rel] = [v for v in d.get("values", []) if isinstance(v, str)]
    return tree


def expand_tag(tag, tree, seen=None):
    """Recursively resolve '#nested' references into plain member names."""
    seen = seen or set()
    tag = strip(tag.lstrip("#"))
    if tag in seen:
        return set()
    seen.add(tag)
    out = set()
    for v in tree.get(tag, []):
        if v.startswith("#"):
            out |= expand_tag(v[1:], tree, seen)
        else:
            out.add(strip(v))
    return out


def gen_tool_tables(gen, out):
    """block -> tool class, and block -> required tool tier.

    Both come from tags, and the tags nest heavily: mineable/axe lists
    "#minecraft:logs" rather than every log. Expanding nested references is what
    makes oak_log an axe block instead of an unknown one.
    """
    tags_dir = os.path.join(gen, "data", "minecraft", "tags", "block")
    tree = load_tag_tree(tags_dir)

    def members(name):
        return expand_tag(name, tree) if name in tree else set()

    tool_of = {}
    for tool in ("pickaxe", "axe", "shovel", "hoe"):
        for b in members("mineable/" + tool):
            tool_of[b] = tool

    tier_of = {}
    for tier, tag in (("stone", "needs_stone_tool"), ("iron", "needs_iron_tool"),
                      ("diamond", "needs_diamond_tool")):
        for b in members(tag):
            tier_of[b] = tier

    lines = []
    for b in sorted(set(tool_of) | set(tier_of)):
        lines.append("%s\t%s\t%s" % (b, tool_of.get(b, "-"), tier_of.get(b, "any")))
    write(out, "block_tools.tsv",
          "# block\ttoolClass(pickaxe|axe|shovel|hoe|-)\trequiredTier(any|stone|iron|diamond)",
          lines)


def gen_tags(gen, out):
    for kind, folder in (("block", os.path.join(gen, "data", "minecraft", "tags", "block")),
                         ("item", os.path.join(gen, "data", "minecraft", "tags", "item"))):
        if not os.path.isdir(folder):
            continue
        lines = []
        for root, _dirs, files in os.walk(folder):
            for fn in sorted(files):
                if not fn.endswith(".json"):
                    continue
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, folder)[:-5].replace(os.sep, "/")
                d = load(full)
                vals = []
                for v in d.get("values", []):
                    if isinstance(v, str):
                        # Keep nested references as "#tag" (namespace stripped)
                        # so Tags.expand can resolve them against these keys.
                        vals.append("#" + strip(v[1:]) if v.startswith("#") else strip(v))
                if vals:
                    lines.append("%s\t%s" % (rel, ",".join(vals)))
        lines.sort()
        write(out, "tags_%s.tsv" % kind, "# tag\tmember,member,...  (# prefix = nested tag)", lines)


TIER_ORDER = {"wooden": 0, "gold": 0, "stone": 1, "iron": 2, "diamond": 3, "netherite": 4}


def item_components(gen):
    """name -> components dict, for every item the server registers."""
    reg = load(os.path.join(gen, "reports", "registries.json"))["minecraft:item"]["entries"]
    comp_dir = os.path.join(gen, "reports", "minecraft", "components", "item")
    out = {}
    for name, info in reg.items():
        short = strip(name)
        comp = {}
        path = os.path.join(comp_dir, short + ".json")
        if os.path.isfile(path):
            comp = load(path).get("components", {})
        out[short] = (info["protocol_id"], comp)
    return out


def attribute(comp, kind, default=0.0):
    total = default
    for mod in comp.get("minecraft:attribute_modifiers", []) or []:
        if mod.get("type") == "minecraft:" + kind:
            total += float(mod.get("amount", 0))
    return total


def gen_items(gen, out):
    items = item_components(gen)
    lines = []
    for short, (protocol_id, comp) in sorted(items.items(), key=lambda kv: kv[1][0]):
        stack = comp.get("minecraft:max_stack_size", 64)
        damage = comp.get("minecraft:max_damage", 0)
        food = comp.get("minecraft:food") or {}
        nutrition = food.get("nutrition", 0)
        saturation = food.get("saturation", 0)
        # The player's base attack damage is 1; a weapon adds to it.
        attack = attribute(comp, "attack_damage", 1.0)
        # Base attack speed is 4.0; the cooldown is 20 / speed ticks.
        attack_speed = attribute(comp, "attack_speed", 4.0)
        tool_tag, speed, tier = "-", 0.0, "-"
        tool = comp.get("minecraft:tool") or {}
        for rule in tool.get("rules", []) or []:
            blocks = rule.get("blocks", "")
            if rule.get("correct_for_drops") and blocks.startswith("#"):
                tag = blocks[1:]
                if "incorrect_for_" not in tag:
                    tool_tag = tag
                    speed = float(rule.get("speed", 1.0))
        for rule in tool.get("rules", []) or []:
            blocks = rule.get("blocks", "")
            if "incorrect_for_" in blocks:
                tier = blocks.split("incorrect_for_")[1].replace("_tool", "")
        lines.append("%d\t%s\t%d\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s" % (
            protocol_id, short, stack, damage, nutrition, saturation,
            tool_tag, speed, tier, attack, round(attack_speed, 4)))
    write(out, "items.tsv",
          "# id\tname\tmaxStack\tmaxDamage\tnutrition\tsaturation\ttoolTag\ttoolSpeed\ttoolTier\tattackDamage\tattackSpeed",
          lines)
    return items


def gen_armor(gen, out, items):
    """Armour pieces: slot, defence points, toughness, knockback resistance.

    All of it comes from the item's own component report, so a modded or
    rebalanced piece of armour is described by whatever the server says, not by
    a wiki table.
    """
    lines = []
    for name, (_pid, comp) in items.items():
        eq = comp.get("minecraft:equippable") or {}
        slot = eq.get("slot")
        if slot not in ("head", "chest", "legs", "feet", "offhand"):
            continue
        defense = attribute(comp, "armor")
        toughness = attribute(comp, "armor_toughness")
        knockback = attribute(comp, "knockback_resistance")
        if slot == "offhand" and defense == 0 and toughness == 0:
            # Shields and totems live in the offhand but are not armour.
            continue
        lines.append("%s\t%s\t%s\t%s\t%s\t%d" % (
            name, slot, round(defense, 2), round(toughness, 2), round(knockback, 3),
            comp.get("minecraft:max_damage", 0)))
    lines.sort()
    write(out, "armor.tsv",
          "# item\tslot(head|chest|legs|feet)\tdefense\ttoughness\tknockbackResistance\tmaxDamage",
          lines)


def gen_food(gen, out, items):
    """Food values plus what eating it does besides filling the bar."""
    lines = []
    for name, (_pid, comp) in items.items():
        food = comp.get("minecraft:food")
        if not food:
            continue
        nutrition = int(food.get("nutrition", 0))
        saturation = float(food.get("saturation", 0))
        always = "1" if food.get("can_always_eat") else "0"
        effects = []
        for eff in (comp.get("minecraft:consumable") or {}).get("on_consume_effects", []) or []:
            if eff.get("type") != "minecraft:apply_effects":
                continue
            for e in eff.get("effects", []):
                effects.append("%s:%s:%s" % (
                    strip(e.get("id", "")),
                    int(e.get("duration", 0)),
                    int(e.get("amplifier", 0))))
        eat_seconds = float((comp.get("minecraft:consumable") or {}).get("consume_seconds", 1.6))
        lines.append("%s\t%d\t%s\t%s\t%s\t%s" % (
            name, nutrition, round(saturation, 2), round(eat_seconds, 2), always,
            ",".join(effects) if effects else "-"))
    lines.sort()
    write(out, "food.tsv",
          "# item\tnutrition\tsaturation\teatSeconds\talwaysEat\teffects(id:ticks:amplifier,...)",
          lines)


def gen_combat(gen, out, items):
    """Weapon reach is fixed; what varies is damage, speed and enchantability."""
    lines = []
    for name, (_pid, comp) in items.items():
        if not comp.get("minecraft:weapon") and attribute(comp, "attack_damage", 0.0) == 0.0:
            continue
        dmg = attribute(comp, "attack_damage", 1.0)
        speed = attribute(comp, "attack_speed", 4.0)
        lines.append("%s\t%s\t%s\t%s\t%s" % (
            name, round(dmg, 2), round(speed, 3),
            comp.get("minecraft:max_damage", 0),
            int((comp.get("minecraft:enchantable") or {}).get("value", 0))))
    lines.sort()
    write(out, "combat.tsv",
          "# item\tattackDamage\tattackSpeed\tmaxDamage\tenchantability",
          lines)


def gen_enchantments(gen, out):
    folder = os.path.join(gen, "data", "minecraft", "enchantment")
    lines = []
    if not os.path.isdir(folder):
        write(out, "enchantments.tsv", "# name\tmaxLevel\tweight\tanvilCost\tslots\tsupportedItems", [])
        return
    for fn in sorted(os.listdir(folder)):
        if not fn.endswith(".json"):
            continue
        d = load(os.path.join(folder, fn))
        name = fn[:-5]
        lines.append("%s\t%s\t%s\t%s\t%s\t%s" % (
            name,
            d.get("max_level", 1),
            d.get("weight", 1),
            d.get("anvil_cost", 1),
            ",".join(d.get("slots", []) or []) or "-",
            str(d.get("supported_items", "-")).replace("minecraft:", "")))
    write(out, "enchantments.tsv",
          "# name\tmaxLevel\tweight\tanvilCost\tslots\tsupportedItems", lines)


def gen_damage_types(gen, out):
    folder = os.path.join(gen, "data", "minecraft", "damage_type")
    lines = []
    if not os.path.isdir(folder):
        write(out, "damage_types.tsv", "# name\tmessageId\tscaling\texhaustion", [])
        return
    for fn in sorted(os.listdir(folder)):
        if not fn.endswith(".json"):
            continue
        d = load(os.path.join(folder, fn))
        lines.append("%s\t%s\t%s\t%s" % (
            fn[:-5], d.get("message_id", fn[:-5]), d.get("scaling", "when_caused_by_living_non_player"),
            d.get("exhaustion", 0.0)))
    write(out, "damage_types.tsv", "# name\tmessageId\tscaling\texhaustion", lines)


# ---------------------------------------------------------------------------
# Loot tables. The format nests: pools -> entries -> (alternatives|group|
# sequence) -> item, with conditions and functions attached at every level.
# The bot needs a flat "block -> what comes out" answer, so this walks the tree
# and records each leaf with the flags that change whether it drops at all.
# ---------------------------------------------------------------------------

def cond_list(entry):
    c = entry.get("condition")
    out = list(entry.get("conditions", []) or [])
    if c:
        out.append(c)
    return out


def func_list(entry):
    f = entry.get("function") or entry.get("modifier")
    out = list(entry.get("functions", []) or [])
    if f:
        out.append(f)
    return out


def cond_type(c):
    if isinstance(c, str):
        return c
    if isinstance(c, dict):
        return c.get("type", "")
    return ""


def count_range(v, default=(1, 1)):
    """set_count accepts an int, {"min","max"} or a provider object."""
    if isinstance(v, (int, float)):
        return int(v), int(v)
    if isinstance(v, dict):
        if "min" in v or "max" in v:
            return int(v.get("min", 1)), int(v.get("max", 1))
        if v.get("type") == "minecraft:uniform":
            return int(v.get("min", 1)), int(v.get("max", 1))
        if v.get("type") == "minecraft:constant":
            return int(v.get("value", 1)), int(v.get("value", 1))
    return default


def walk_conditions(conds, flags, chance):
    """Fold a condition tree into (flags, chance).

    The tree nests (any_of / all_of / inverted / table_bonus), and the
    difference between "drops with shears" and "drops 5% of the time" is
    exactly what a mining planner needs, so both are recorded rather than
    flattened to a single boolean.

    Returns the accumulated chance: a nested ``all_of`` has to multiply the
    caller's chance, and a float passed by value would silently lose that.
    """
    for c in conds:
        t = cond_type(c)
        if not isinstance(c, dict):
            # A bare string condition ("minecraft:tool/can_silk_touch").
            if "can_silk_touch" in t:
                flags.add("silk")
            elif "can_shear" in t:
                flags.add("shears")
            elif "killed_by_player" in t:
                flags.add("player_kill")
            continue
        if t.endswith("any_of"):
            # Alternatives: the union of the branches. Record every flag so a
            # caller can see that the drop is tool-gated either way.
            for term in c.get("terms", []) or []:
                sub_flags = set()
                walk_conditions([term], sub_flags, 1.0)
                flags |= sub_flags
            continue
        if t.endswith("all_of"):
            chance = walk_conditions(c.get("terms", []) or [], flags, chance)
            continue
        if t.endswith("inverted"):
            term = c.get("term")
            sub_flags = set()
            walk_conditions([term], sub_flags, 1.0)
            for f in sub_flags:
                flags.add("unless:" + f)
            continue
        if t.endswith("can_silk_touch"):
            flags.add("silk")
        elif t.endswith("can_shear"):
            flags.add("shears")
        elif t.endswith("table_bonus"):
            chances = c.get("chances") or [1.0]
            flags.add("fortune")
            chance *= float(chances[0])
        elif t.endswith("random_chance_with_enchanted_bonus"):
            chance *= float(c.get("unenchanted_chance", 1.0))
        elif t.endswith("random_chance_with_luck"):
            chance *= float(c.get("chance", 1.0))
        elif t.endswith("random_chance"):
            chance *= float(c.get("chance", 1.0))
        elif t.endswith("killed_by_player"):
            flags.add("player_kill")
        elif t.endswith("entity_properties") or t.endswith("location_check") \
                or t.endswith("block_state_property") or t.endswith("weather_check"):
            # Rare conditional drops ("zombie riding a zombie horse"). Kept in
            # the table but excluded from "what does this always drop".
            flags.add("conditional")
        elif t.endswith("match_tool"):
            pred = c.get("predicate") or {}
            for it in pred.get("items", []) or []:
                flags.add("tool:" + strip(it if isinstance(it, str) else it.get("id", "")))
        elif t.endswith("survives_explosion"):
            pass
    return chance


def flatten_entry(entry, inherited, out, depth=0):
    """Append (item, min, max, chance, flags) leaves for one entry subtree."""
    if depth > 12 or not isinstance(entry, dict):
        return
    etype = entry.get("type", "")
    flags = set(inherited)
    chance = walk_conditions(cond_list(entry), flags, 1.0)

    lo, hi = 1, 1
    for f in func_list(entry):
        t = cond_type(f)
        if t.endswith("set_count"):
            lo, hi = count_range(f.get("count", 1))
        elif t.endswith("apply_bonus") or t.endswith("enchanted_count_increase"):
            flags.add("fortune")
        elif t.endswith("furnace_smelt"):
            flags.add("smelt")
        elif t.endswith("set_damage"):
            flags.add("damaged")
        elif t.endswith("enchant_randomly") or t.endswith("enchant_with_levels"):
            flags.add("enchanted")

    if etype.endswith("item"):
        name = strip(entry.get("name", ""))
        if name:
            out.append((name, lo, hi, chance, flags))
    elif etype.endswith("alternatives") or etype.endswith("group") or etype.endswith("sequence"):
        for child in entry.get("children", []) or []:
            flatten_entry(child, flags, out, depth + 1)
    elif etype.endswith("tag"):
        name = strip(entry.get("name", ""))
        if name:
            out.append(("#" + name, 1, 1, chance, flags | {"tag"}))
    elif etype.endswith("loot_table"):
        out.append(("@" + strip(entry.get("value", "")), 1, 1, chance, flags | {"nested"}))
    elif etype.endswith("dynamic"):
        out.append(("@" + strip(entry.get("name", "dynamic")), 1, 1, chance, flags | {"dynamic"}))


def flatten_loot(d):
    out = []
    for pool in d.get("pools", []) or []:
        pool_flags = set()
        pool_chance = walk_conditions(cond_list(pool), pool_flags, 1.0)
        rolls_lo, rolls_hi = count_range(pool.get("rolls", 1))
        for entry in pool.get("entries", []) or []:
            before = len(out)
            flatten_entry(entry, pool_flags, out)
            for i in range(before, len(out)):
                item, lo, hi, chance, flags = out[i]
                out[i] = (item, lo, hi, chance * pool_chance, flags, max(rolls_lo, rolls_hi))
    # Deduplicate: the same item can appear via several pools.
    seen = {}
    for item, lo, hi, chance, flags, rolls in out:
        key = (item, tuple(sorted(flags)))
        if key in seen:
            a = seen[key]
            seen[key] = (item, a[1] + lo, a[2] + hi, min(1.0, a[3] + chance), flags, a[5])
        else:
            seen[key] = (item, lo, hi, chance, flags, rolls)
    return list(seen.values())


def gen_loot(gen, out):
    for kind, folder, fname in (
            ("block", os.path.join(gen, "data", "minecraft", "loot_table", "blocks"), "loot_blocks.tsv"),
            ("entity", os.path.join(gen, "data", "minecraft", "loot_table", "entities"), "loot_entities.tsv")):
        if not os.path.isdir(folder):
            continue
        lines = []
        for fn in sorted(os.listdir(folder)):
            if not fn.endswith(".json"):
                continue
            name = fn[:-5]
            try:
                d = load(os.path.join(folder, fn))
            except Exception:
                continue
            for item, lo, hi, chance, flags, rolls in flatten_loot(d):
                lines.append("%s\t%s\t%d\t%d\t%.4f\t%s\t%d" % (
                    name, item, lo, hi, chance,
                    ",".join(sorted(flags)) if flags else "-", rolls))
        write(out, fname,
              "# %s\titem\tmin\tmax\tchance\tflags(silk,shears,fortune,smelt,player_kill,state:..)\trolls"
              % kind, lines)


# ---------------------------------------------------------------------------
# Mob stats. Health and melee damage are NOT in any data report - they are
# constants in each entity class. The values below are vanilla's, for the mobs
# a survival bot actually meets; anything not listed is classified by name
# (monster/mob tags and the usual suffixes) and treated as "unknown strength".
# ---------------------------------------------------------------------------
MOBS = {
    # name:            health, attack, disposition, ranged
    "zombie":          (20, 3, "hostile", 0),
    "husk":            (20, 3, "hostile", 0),
    "drowned":         (20, 3, "hostile", 1),
    "zombie_villager": (20, 3, "hostile", 0),
    "skeleton":        (20, 2, "hostile", 1),
    "stray":           (20, 2, "hostile", 1),
    "bogged":          (16, 2, "hostile", 1),
    "wither_skeleton": (20, 5, "hostile", 0),
    "creeper":         (20, 0, "hostile", 0),
    "spider":          (16, 2, "hostile", 0),
    "cave_spider":     (12, 2, "hostile", 0),
    "silverfish":      (8, 1, "hostile", 0),
    "endermite":       (8, 2, "hostile", 0),
    "enderman":        (40, 7, "neutral", 0),
    "witch":           (26, 6, "hostile", 1),
    "slime":           (4, 2, "hostile", 0),
    "magma_cube":      (16, 4, "hostile", 0),
    "blaze":           (20, 6, "hostile", 1),
    "ghast":           (10, 0, "hostile", 1),
    "phantom":         (20, 2, "hostile", 0),
    "shulker":         (30, 4, "hostile", 1),
    "guardian":        (30, 6, "hostile", 1),
    "elder_guardian":  (80, 8, "hostile", 1),
    "evoker":          (24, 6, "hostile", 1),
    "vindicator":      (24, 13, "hostile", 0),
    "pillager":        (24, 5, "hostile", 1),
    "ravager":         (100, 12, "hostile", 0),
    "vex":             (14, 9, "hostile", 0),
    "piglin":          (16, 5, "neutral", 0),
    "piglin_brute":    (50, 7, "hostile", 0),
    "zombified_piglin": (20, 5, "neutral", 0),
    "hoglin":          (40, 6, "neutral", 0),
    "zoglin":          (40, 6, "hostile", 0),
    "wither":          (300, 8, "boss", 1),
    "ender_dragon":    (200, 10, "boss", 0),
    "warden":          (500, 30, "boss", 0),
    "iron_golem":      (100, 15, "neutral", 0),
    "snow_golem":      (4, 0, "neutral", 1),
    "wolf":            (8, 4, "neutral", 0),
    "polar_bear":      (30, 6, "neutral", 0),
    "llama":           (15, 2, "neutral", 0),
    "trader_llama":    (15, 2, "neutral", 0),
    "panda":           (20, 6, "passive", 0),
    "bee":             (10, 2, "neutral", 0),
    "goat":            (10, 2, "neutral", 0),
    "dolphin":         (10, 3, "neutral", 0),
    "squid":           (10, 0, "passive", 0),
    "glow_squid":      (10, 0, "passive", 0),
    "bat":             (6, 0, "passive", 0),
    "chicken":         (4, 0, "passive", 0),
    "cow":             (10, 0, "passive", 0),
    "mooshroom":       (10, 0, "passive", 0),
    "pig":             (10, 0, "passive", 0),
    "sheep":           (8, 0, "passive", 0),
    "rabbit":          (3, 0, "passive", 0),
    "horse":           (15, 0, "passive", 0),
    "donkey":          (15, 0, "passive", 0),
    "mule":            (15, 0, "passive", 0),
    "camel":           (32, 0, "passive", 0),
    "villager":        (20, 0, "passive", 0),
    "wandering_trader": (20, 0, "passive", 0),
    "axolotl":         (14, 2, "passive", 0),
    "turtle":          (30, 0, "passive", 0),
    "fox":             (10, 2, "passive", 0),
    "ocelot":          (10, 0, "passive", 0),
    "cat":             (10, 0, "passive", 0),
    "parrot":          (6, 0, "passive", 0),
    "frog":            (10, 0, "passive", 0),
    "allay":           (20, 0, "passive", 0),
    "sniffer":         (14, 0, "passive", 0),
    "armadillo":       (12, 0, "passive", 0),
    "breeze":          (30, 3, "hostile", 1),
    "bogged":          (16, 2, "hostile", 1),
    "creaking":        (1, 0, "hostile", 0),
    "happy_ghast":     (20, 0, "passive", 0),
}


def gen_mobs(gen, out):
    reg = load(os.path.join(gen, "reports", "registries.json")).get("minecraft:entity_type", {})
    lines = []
    for name, info in sorted(reg.get("entries", {}).items(), key=lambda kv: kv[1]["protocol_id"]):
        short = strip(name)
        health, attack, disposition, ranged = MOBS.get(short, (0, 0, "unknown", 0))
        lines.append("%d\t%s\t%d\t%d\t%s\t%d" % (
            info["protocol_id"], short, health, attack, disposition, ranged))
    write(out, "mobs.tsv", "# id\tname\thealth\tattack\tdisposition(hostile|neutral|passive|boss|unknown)\tranged", lines)


def ingredient_name(v):
    if isinstance(v, str):
        return v if v.startswith("#") else strip(v)
    if isinstance(v, dict):
        return strip(v.get("item") or v.get("id") or v.get("tag", ""))
    if isinstance(v, list):
        # A list ingredient is "any of these". "|" is the separator because ","
        # already separates shaped-recipe cells.
        return "|".join(ingredient_name(x) for x in v)
    return "?"


def gen_recipes(gen, out):
    folder = os.path.join(gen, "data", "minecraft", "recipe")
    lines = []
    for fn in sorted(os.listdir(folder)):
        if not fn.endswith(".json"):
            continue
        d = load(os.path.join(folder, fn))
        rtype = strip(d.get("type", ""))
        result = d.get("result") or d.get("output") or {}
        if isinstance(result, str):
            out_id, count = strip(result), 1
        else:
            out_id = strip(result.get("id", result.get("item", "?")))
            count = int(result.get("count", 1))
        if rtype == "crafting_shaped":
            key = {k: ingredient_name(v) for k, v in (d.get("key") or {}).items()}
            grid = ";".join(",".join(key.get(c, " ") for c in row) for row in d.get("pattern", []))
            lines.append("%s\t%s\t%d\t%s" % (rtype, out_id, count, grid))
        elif rtype == "crafting_shapeless":
            grid = ",".join(ingredient_name(v) for v in d.get("ingredients", []))
            lines.append("%s\t%s\t%d\t%s" % (rtype, out_id, count, grid))
        elif rtype in ("smelting", "blasting", "smoking", "campfire_cooking"):
            ing = ingredient_name(d.get("ingredient"))
            lines.append("%s\t%s\t%d\t%s" % (rtype, out_id, count, ing))
        elif rtype == "stonecutting":
            ing = ingredient_name(d.get("ingredient"))
            lines.append("%s\t%s\t%d\t%s" % (rtype, out_id, count, ing))
        elif rtype == "smithing_transform":
            base = ingredient_name(d.get("base") or d.get("template"))
            add = ingredient_name(d.get("addition"))
            lines.append("%s\t%s\t%d\t%s,%s" % (rtype, out_id, count, base, add))
    write(out, "recipes.tsv",
          "# type\toutput\tcount\tgrid(shaped: rows by ';', cells by ','; shapeless/cooking: ingredients by ',')",
          lines)
    return len(lines)


def gen_components(gen, out):
    """Data component type ids, so item-stack component patches can be skipped."""
    reg = load(os.path.join(gen, "reports", "registries.json")).get("minecraft:data_component_type")
    if not reg:
        return
    lines = ["%d\t%s" % (v["protocol_id"], strip(k))
             for k, v in sorted(reg["entries"].items(), key=lambda kv: kv[1]["protocol_id"])]
    write(out, "components.tsv", "# id\tname", lines)


def gen_menus(gen, out):
    """Menu (window) type ids, so open_screen can be mapped to a slot layout."""
    reg = load(os.path.join(gen, "reports", "registries.json")).get("minecraft:menu")
    if not reg:
        return
    lines = ["%d\t%s" % (v["protocol_id"], strip(k))
             for k, v in sorted(reg["entries"].items(), key=lambda kv: kv[1]["protocol_id"])]
    write(out, "menus.tsv", "# id\tname", lines)


def gen_entities(gen, out):
    """Entity type ids, so add_entity can be mapped to a name (mob tactics)."""
    reg = load(os.path.join(gen, "reports", "registries.json")).get("minecraft:entity_type")
    if not reg:
        return
    lines = ["%d\t%s" % (v["protocol_id"], strip(k))
             for k, v in sorted(reg["entries"].items(), key=lambda kv: kv[1]["protocol_id"])]
    write(out, "entity_types.tsv", "# id\tname", lines)


def gen_hardness(out):
    lines = ["%s\t%s" % (k, v) for k, v in sorted(HARDNESS.items())]
    write(out, "hardness.tsv", "# block\thardness in seconds (vanilla destroy time)", lines)


def write(out_dir, name, header, lines):
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, name)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(header + "\n")
        for line in lines:
            f.write(line + "\n")
    size = os.path.getsize(path)
    print("  %-18s %6d lines  %7.1f KB" % (name, len(lines), size / 1024.0))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(2)
    gen = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src", "mcbot", "data")
    print("generating mcbot data from %s -> %s" % (gen, out))
    gen_blocks(gen, out)
    items = gen_items(gen, out)
    gen_tool_tables(gen, out)
    gen_tags(gen, out)
    gen_components(gen, out)
    gen_menus(gen, out)
    gen_entities(gen, out)
    n = gen_recipes(gen, out)
    gen_hardness(out)
    gen_armor(gen, out, items)
    gen_food(gen, out, items)
    gen_combat(gen, out, items)
    gen_enchantments(gen, out)
    gen_damage_types(gen, out)
    gen_loot(gen, out)
    gen_mobs(gen, out)
    print("  recipes parsed: %d" % n)
    print("done")


if __name__ == "__main__":
    main()
