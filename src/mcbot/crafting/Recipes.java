package mcbot.crafting;

import mcbot.inventory.Inventory;
import mcbot.registry.Blocks;
import mcbot.registry.Items;
import mcbot.registry.Tags;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The recipe book, and the planner that turns "craft me a pickaxe" into steps.
 *
 * Two jobs:
 *
 *  1. <b>Matching</b> - given a 3x3 grid of items, which recipe is that? Used
 *     when the bot opens a table and needs to know what it just built.
 *  2. <b>Planning</b> - given a target item and a count, what is the dependency
 *     tree? "A diamond pickaxe" becomes: mine 3 diamonds, mine 2 sticks worth of
 *     planks, craft the pickaxe. The planner reports steps in execution order,
 *     and stops at anything the bot can gather directly.
 *
 * Planning is deliberately conservative: if an ingredient cannot be obtained by
 * crafting, smelting or mining, the plan fails with the missing item named,
 * instead of producing a plan that dies halfway through.
 */
public final class Recipes {
    private static final List<Recipe> ALL = new ArrayList<>();
    private static final Map<String, List<Recipe>> BY_OUTPUT = new HashMap<>();

    /** Item -> the block that yields it. Until loot tables are parsed. */
    private static final Map<String, String> MINE_FOR = new HashMap<>();
    /** Item -> fuel value in ticks, for the furnace. */
    private static final Map<String, Integer> FUEL = new HashMap<>();

    static {
        load();
        // Ore and stone drops. Loot tables encode this properly (with fortune
        // and silk touch); this covers the blocks a bot actually mines, and is
        // the only place in the planner that is not derived from server data.
        mine("cobblestone", "stone");
        // Natural blocks that also have a smelting recipe (cobblestone -> stone,
        // sand -> glass): list them so the planner mines instead of building a
        // furnace to make stone out of stone.
        mine("stone", "stone");
        mine("deepslate", "deepslate");
        mine("sand", "sand");
        mine("red_sand", "red_sand");
        mine("gravel", "gravel");
        mine("dirt", "dirt");
        mine("netherrack", "netherrack");
        mine("end_stone", "end_stone");
        mine("andesite", "andesite");
        mine("diorite", "diorite");
        mine("granite", "granite");
        mine("tuff", "tuff");
        mine("calcite", "calcite");
        mine("blackstone", "blackstone");
        mine("basalt", "basalt");
        mine("cobbled_deepslate", "deepslate");
        mine("cobbled_deepslate", "deepslate");
        mine("raw_iron", "iron_ore");
        mine("raw_iron", "deepslate_iron_ore");
        mine("raw_gold", "gold_ore");
        mine("raw_gold", "deepslate_gold_ore");
        mine("raw_copper", "copper_ore");
        mine("raw_copper", "deepslate_copper_ore");
        mine("coal", "coal_ore");
        mine("coal", "deepslate_coal_ore");
        mine("diamond", "diamond_ore");
        mine("diamond", "deepslate_diamond_ore");
        mine("emerald", "emerald_ore");
        mine("emerald", "deepslate_emerald_ore");
        mine("lapis_lazuli", "lapis_ore");
        mine("lapis_lazuli", "deepslate_lapis_ore");
        mine("redstone", "redstone_ore");
        mine("redstone", "deepslate_redstone_ore");
        mine("quartz", "nether_quartz_ore");
        mine("ancient_debris", "ancient_debris");
        mine("obsidian", "obsidian");
        mine("sand", "sand");
        mine("gravel", "gravel");
        mine("clay_ball", "clay");
        mine("flint", "gravel");
        mine("wheat", "wheat");
        mine("sugar_cane", "sugar_cane");
        mine("bamboo", "bamboo");
        mine("nether_wart", "nether_wart");
        mine("cocoa_beans", "cocoa");
        mine("sweet_berries", "sweet_berry_bush");
        mine("kelp", "kelp");
        mine("cactus", "cactus");
        mine("pumpkin", "pumpkin");
        mine("melon_slice", "melon");
        mine("carrot", "carrots");
        mine("potato", "potatoes");
        mine("beetroot", "beetroots");
        mine("string", "cobweb");
        mine("honeycomb", "beehive");
        mine("amethyst_shard", "amethyst_cluster");
        mine("glowstone_dust", "glowstone");
        mine("prismarine_shard", "prismarine");
        mine("netherite_scrap", "ancient_debris");

        // Cooking fuel, in ticks. The item report only references a provider
        // ("cooking/time_wood_blocks") instead of a number, so these are the
        // vanilla values.
        fuel("coal", 1600);
        fuel("charcoal", 1600);
        fuel("coal_block", 16000);
        fuel("lava_bucket", 20000);
        fuel("oak_planks", 300);
        fuel("spruce_planks", 300);
        fuel("birch_planks", 300);
        fuel("jungle_planks", 300);
        fuel("acacia_planks", 300);
        fuel("dark_oak_planks", 300);
        fuel("oak_log", 300);
        fuel("spruce_log", 300);
        fuel("birch_log", 300);
        fuel("stick", 100);
        fuel("bamboo", 50);
        fuel("blaze_rod", 2400);
        fuel("dried_kelp_block", 4001);
    }

    private Recipes() {}

    private static void mine(String item, String block) {
        MINE_FOR.putIfAbsent(item, block);
    }

    private static void fuel(String item, int ticks) {
        FUEL.put(item, ticks);
    }

    private static void load() {
        try (InputStream in = Recipes.class.getResourceAsStream("/mcbot/data/recipes.tsv")) {
            if (in == null) {
                System.out.println("[recipes] missing resource /mcbot/data/recipes.tsv");
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                if (f.length < 4) continue;
                try {
                    Recipe recipe = parse(f[0], f[1], Integer.parseInt(f[2]), f[3]);
                    if (recipe == null) continue;
                    ALL.add(recipe);
                    BY_OUTPUT.computeIfAbsent(recipe.output, k -> new ArrayList<>()).add(recipe);
                } catch (RuntimeException e) {
                    // One bad row must not cost the whole recipe book.
                }
            }
        } catch (Exception e) {
            System.out.println("[recipes] load failed: " + e);
        }
        System.out.println("[recipes] " + ALL.size() + " recipes, "
                + BY_OUTPUT.size() + " distinct outputs");
    }

    private static Recipe parse(String type, String output, int count, String grid) {
        switch (type) {
            case "crafting_shaped": {
                String[] rows = grid.split(";", -1);
                String[][] pattern = new String[rows.length][];
                for (int i = 0; i < rows.length; i++) {
                    String[] cells = rows[i].split(",", -1);
                    pattern[i] = new String[cells.length];
                    for (int c = 0; c < cells.length; c++) {
                        String cell = cells[c].trim();
                        pattern[i][c] = cell.isEmpty() ? "" : cell;
                    }
                }
                return new Recipe(type, Recipe.Kind.SHAPED, output, count, pattern, List.of());
            }
            case "crafting_shapeless": {
                List<String> ing = new ArrayList<>();
                for (String s : grid.split(",", -1)) {
                    String t = s.trim();
                    if (!t.isEmpty()) ing.add(t);
                }
                return new Recipe(type, Recipe.Kind.SHAPELESS, output, count, null, ing);
            }
            case "smelting":
            case "blasting":
            case "smoking":
            case "campfire_cooking": {
                List<String> ing = new ArrayList<>();
                for (String s : grid.split(",", -1)) {
                    String t = s.trim();
                    if (!t.isEmpty()) ing.add(t);
                }
                return new Recipe(type, Recipe.Kind.COOKING, output, count, null, ing);
            }
            case "stonecutting": {
                List<String> ing = new ArrayList<>();
                for (String s : grid.split(",", -1)) {
                    String t = s.trim();
                    if (!t.isEmpty()) ing.add(t);
                }
                return new Recipe(type, Recipe.Kind.STONECUTTING, output, count, null, ing);
            }
            case "smithing_transform":
            case "smithing_trim": {
                List<String> ing = new ArrayList<>();
                for (String s : grid.split(",", -1)) {
                    String t = s.trim();
                    if (!t.isEmpty()) ing.add(t);
                }
                return new Recipe(type, Recipe.Kind.SMITHING, output, count, null, ing);
            }
            default:
                return null;
        }
    }

    // ------------------------------------------------------------- lookups --

    public static List<Recipe> forOutput(String item) {
        return BY_OUTPUT.getOrDefault(strip(item), List.of());
    }

    /** The simplest crafting recipe for an item, preferring 2x2 over 3x3. */
    public static Recipe craftingFor(String item) {
        List<Recipe> list = forOutput(item);
        Recipe best = null;
        for (Recipe r : list) {
            if (!r.isCrafting()) continue;
            if (best == null || cells(r) < cells(best)) best = r;
        }
        return best;
    }

    /**
     * A recipe for {@code item} that can actually be made from what the bot is
     * carrying right now.
     *
     * The problem this solves: a plan asks for oak planks, the miner chops a
     * jungle tree because oak was not nearby, and the exact oak recipe then
     * reports "missing 1x oak_log" even though four jungle planks are one click
     * away. When {@code allowEquivalent} is true and the requested item is a
     * plank variant, another wood type is a valid substitute - the recipes that
     * consume planks use {@code #minecraft:planks}, not one specific wood.
     *
     * The final item the player asked for is never substituted; only
     * intermediate ingredients are.
     */
    public static Recipe craftingFor(String item, Inventory inv, boolean allowEquivalent) {
        Recipe exact = craftingFor(item);
        if (exact != null && canCraftNow(exact, inv)) return exact;
        if (allowEquivalent) {
            for (String variant : equivalentOutputs(item)) {
                if (variant.equals(strip(item))) continue;
                Recipe r = craftingFor(variant);
                if (r != null && canCraftNow(r, inv)) return r;
            }
        }
        return exact;
    }

    /** True when every ingredient of the recipe is in the inventory now. */
    public static boolean canCraftNow(Recipe recipe, Inventory inv) {
        if (recipe == null || inv == null) return false;
        Map<String, Integer> required = new LinkedHashMap<>();
        for (String token : recipe.allTokens()) {
            String concrete = resolveToken(token, inv);
            if (concrete == null) return false;
            required.merge(concrete, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : required.entrySet()) {
            if (inv.count(e.getKey()) < e.getValue()) return false;
        }
        return !required.isEmpty();
    }

    /** Outputs that are interchangeable with {@code item} for intermediate steps. */
    private static List<String> equivalentOutputs(String item) {
        String name = strip(item);
        if (name.endsWith("_planks")) {
            return new ArrayList<>(Tags.items("planks"));
        }
        return List.of();
    }

    /**
     * A furnace recipe for an item. Several can exist ("iron ingot from
     * deepslate ore" and "from iron ore"), so the one with the easiest
     * ingredient wins rather than whichever the file order happened to put
     * first.
     */
    public static Recipe cookingFor(String item) {
        List<Recipe> candidates = new ArrayList<>();
        for (Recipe r : forOutput(item)) {
            if (r.kind == Recipe.Kind.COOKING) candidates.add(r);
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator
                .comparingInt((Recipe r) -> "smelting".equals(strip(r.type)) ? 0 : 1)
                .thenComparingInt(r -> r.ingredients.isEmpty() ? 99
                        : preference(strip(r.ingredients.get(0)))));
        return candidates.get(0);
    }

    private static int cells(Recipe r) {
        return r.kind == Recipe.Kind.SHAPED ? r.pattern.length * r.pattern[0].length
                : r.ingredients.size();
    }

    /**
     * The block that yields an item when mined, or null.
     *
     * <p>Three sources, in order of trust: the explicit table above (which
     * covers the cases where a name does not lead to its own block), the
     * server's loot tables (which cover everything else, including blocks that
     * drop a different item than their name), and finally the block of the same
     * name, which is how most building blocks drop themselves.
     */
    public static String mineSource(String item) {
        String name = strip(item);
        String mapped = MINE_FOR.get(name);
        if (mapped != null) return mapped;
        String fromLoot = bestLootSource(name);
        if (fromLoot != null) return fromLoot;
        return Blocks.defaultState(name) >= 0 ? name : null;
    }

    /**
     * The most sensible block to mine for an item, from the loot tables.
     *
     * <p>Ranking matters: "dirt" is dropped by dirt, grass_block, podzol and
     * mycelium, and a bot that walks to mycelium because it sorted first is
     * wasting a trip. Same-name wins, then the shallowest variant, then the
     * shortest name.
     */
    private static String bestLootSource(String item) {
        List<String> sources = mcbot.knowledge.Loot.sourcesOf(item);
        if (sources.isEmpty()) return null;
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String block : sources) {
            int score = 0;
            if (block.equals(item)) score -= 100;
            if (block.startsWith("deepslate_") || block.contains("_deepslate_")) score += 10;
            if (block.startsWith("infested_")) score += 20;
            score += block.length();
            if (score < bestScore) {
                bestScore = score;
                best = block;
            }
        }
        return best;
    }

    /**
     * The item a block drops, or null when it is not in the drop table.
     * Inverse of {@link #mineSource}; used for "do I already have enough" checks
     * before starting a mining job.
     *
     * <p>The loot table is authoritative: it knows stone drops cobblestone,
     * grass drops dirt, and diamond ore drops a diamond. The curated table
     * stays as a fallback for the handful of blocks the generator could not
     * classify, and a block with a table but no guaranteed drop (oak leaves)
     * returns null rather than pretending it drops itself.
     */
    public static String dropOf(String block) {
        String b = strip(block);
        if (mcbot.knowledge.Loot.hasTable(b)) {
            return mcbot.knowledge.Loot.guaranteedDrop(b);
        }
        if (b.equals("stone")) return "cobblestone";
        String mapped = null;
        for (Map.Entry<String, String> e : MINE_FOR.entrySet()) {
            if (!e.getValue().equals(b)) continue;
            if (e.getKey().equals(b)) return b;
            if (mapped == null) mapped = e.getKey();
        }
        if (mapped != null) return mapped;
        if (b.startsWith("deepslate_")) {
            String base = b.substring("deepslate_".length());
            String baseDrop = dropOf(base);
            if (baseDrop != null) return baseDrop;
        }
        return Blocks.defaultState(b) >= 0 && Items.get(b).id != 0 ? b : null;
    }

    /**
     * The tool item needed to mine a block and get its drop, or null when the
     * block is hand-mineable.
     *
     * Mirrors {@code Inventory.hasToolFor}: a pickaxe is always required for
     * stone-like blocks, everything else only when the registry names a tier.
     * That is why "mine oak logs" needs no axe step, but "mine coal" needs a
     * wooden pickaxe and "mine iron" a stone one.
     */
    public static String requiredToolFor(String block) {
        int state = Blocks.defaultState(strip(block));
        if (state < 0) return null;
        String cls = Blocks.toolClass(state);
        if (cls == null) return null;
        int rank = Blocks.tierRank(Blocks.requiredTier(state));
        boolean requiresTool = "pickaxe".equals(cls) || rank > 0;
        if (!requiresTool) return null;
        return toolName(cls, rank);
    }

    /** "pickaxe" + rank 1 -> "stone_pickaxe". */
    private static String toolName(String cls, int rank) {
        String tier;
        if (rank >= 4) tier = "netherite";
        else if (rank == 3) tier = "diamond";
        else if (rank == 2) tier = "iron";
        else if (rank == 1) tier = "stone";
        else tier = "wooden";
        return tier + "_" + cls;
    }

    /** Tool class of a tool item name ("stone_pickaxe" -> "pickaxe"). */
    private static String toolClassOf(String tool) {
        int at = tool.indexOf('_');
        return at < 0 ? tool : tool.substring(at + 1);
    }

    /** Tier rank of a tool item name: wooden 0 .. netherite 4. */
    private static int toolRankOf(String tool) {
        if (tool.startsWith("netherite_")) return 4;
        if (tool.startsWith("diamond_")) return 3;
        if (tool.startsWith("iron_")) return 2;
        if (tool.startsWith("stone_")) return 1;
        return 0;
    }

    /**
     * True when a tool of the right class and at least the required tier is
     * available now (inventory) or later in the plan (produced). A stone
     * pickaxe satisfies a wooden-pickaxe requirement.
     */
    private static boolean toolAvailable(String cls, int rank, Inventory inv,
                                         Map<String, Integer> produced) {
        for (Map.Entry<String, Integer> e : produced.entrySet()) {
            if (e.getValue() > 0 && isToolFor(e.getKey(), cls, rank)) return true;
        }
        if (inv != null) {
            for (Map.Entry<String, Integer> e : inv.contents()) {
                if (e.getValue() > 0 && isToolFor(e.getKey(), cls, rank)) return true;
            }
        }
        return false;
    }

    private static boolean isToolFor(String itemName, String cls, int rank) {
        Items.Item item = Items.get(itemName);
        return item.id != 0 && item.isTool()
                && cls.equals(item.toolClass())
                && Blocks.tierRank(item.toolTier) >= rank;
    }

    /** Fuel value in ticks, 0 when the item is not fuel. */
    public static int fuelTicks(String item) {
        return FUEL.getOrDefault(strip(item), 0);
    }

    /** Best fuel in an inventory, by burn time. */
    public static String bestFuel(Inventory inv) {
        String best = null;
        int bestTicks = 0;
        for (String candidate : FUEL.keySet()) {
            if (inv.count(candidate) <= 0) continue;
            int ticks = FUEL.get(candidate);
            if (ticks > bestTicks) {
                bestTicks = ticks;
                best = candidate;
            }
        }
        return best;
    }

    /** True when the item can be obtained without crafting (mined or already held). */
    public static boolean isGatherable(String item) {
        return MINE_FOR.containsKey(strip(item))
                || Blocks.defaultState(item) >= 0;
    }

    public static int total() { return ALL.size(); }

    /** Every loaded recipe, for reasoning and tests. */
    public static List<Recipe> all() { return java.util.Collections.unmodifiableList(ALL); }

    // -------------------------------------------------------------- planning --

    public enum Op { MINE, CRAFT, SMELT }

    public static final class Step {
        public final Op op;
        public final String item;
        public final int count;

        Step(Op op, String item, int count) {
            this.op = op;
            this.item = item;
            this.count = count;
        }

        @Override
        public String toString() {
            return op + " " + count + " " + item;
        }
    }

    public static final class Plan {
        public final List<Step> steps;
        public final String error;

        Plan(List<Step> steps, String error) {
            this.steps = steps;
            this.error = error;
        }

        public boolean ok() { return error == null; }
    }

    /**
     * Builds an execution plan for {@code count} of {@code item}.
     *
     * @param inv current inventory, used to skip steps for items already held
     */
    public static Plan plan(String item, int count, Inventory inv) {
        List<Step> steps = new ArrayList<>();
        Map<String, Integer> produced = new HashMap<>();
        String error = planInto(strip(item), count, inv, steps, produced, new HashSet<>(), 0);
        return new Plan(steps, error);
    }

    /**
     * @param produced what earlier steps in this plan will have made, so a
     *        second ingredient does not re-mine the log the first one already
     *        turned into planks
     */
    private static String planInto(String item, int count, Inventory inv, List<Step> steps,
                                   Map<String, Integer> produced, Set<String> visiting,
                                   int depth) {
        // A diamond pickaxe is eight levels deep from bare hands:
        // pickaxe -> diamond -> diamond_ore -> iron pickaxe -> iron ore ->
        // stone pickaxe -> stone -> wooden pickaxe -> planks -> log. The old
        // limit of 8 cut the chain in half.
        if (depth > 16) return "recipe tree too deep at " + item;
        int have = (inv == null ? 0 : inv.count(item)) + produced.getOrDefault(item, 0);
        int need = count - have;
        if (need <= 0) return null;

        // 1) Gather it if the world can provide it. An explicit drop mapping
        //    beats crafting: "get 10 coal" should not be answered with
        //    "9 coal blocks", and diamond ore beats "9 diamonds from a block".
        //    Mining a block that needs a tool plans that tool first, so the
        //    whole ladder (logs -> planks -> sticks -> wooden pickaxe -> stone)
        //    is part of one plan instead of an error the player has to babysit.
        String explicit = MINE_FOR.get(item);
        if (explicit != null) {
            String toolErr = ensureTool(explicit, inv, steps, produced, visiting, depth);
            if (toolErr != null) return toolErr;
            steps.add(new Step(Op.MINE, explicit, need));
            produced.merge(item, need, Integer::sum);
            return null;
        }

        // 2) Crafting. Every candidate is tried in turn: some recipes for an
        //    item are decompression ("9 ingots from a block"), which is useless
        //    when you have no block, and the next candidate may be the real one.
        for (Recipe craft : craftingCandidates(item)) {
            if (!visiting.add(item)) return "recipe loop at " + item;
            int crafts = (need + craft.count - 1) / craft.count;
            Map<String, Integer> trial = new HashMap<>(produced);
            int mark = steps.size();
            String failure = null;
            Map<String, Integer> needed = new LinkedHashMap<>();
            for (String token : craft.allTokens()) {
                String concrete = resolveToken(token, inv);
                if (concrete == null) {
                    failure = "no concrete item for " + token;
                    break;
                }
                needed.merge(concrete, 1, Integer::sum);
            }
            if (failure == null) {
                for (Map.Entry<String, Integer> e : needed.entrySet()) {
                    int qty = e.getValue() * crafts;
                    failure = planInto(e.getKey(), qty, inv, steps, trial,
                            visiting, depth + 1);
                    if (failure != null) break;
                    // The recipe consumes these ingredients. Without this the
                    // planner double-counts planks: 4 planks become sticks and
                    // are still "available" for the pickaxe head.
                    trial.merge(e.getKey(), -qty, Integer::sum);
                }
            }
            visiting.remove(item);
            if (failure == null) {
                produced.clear();
                produced.putAll(trial);
                steps.add(new Step(Op.CRAFT, item, crafts * craft.count));
                produced.merge(item, crafts * craft.count, Integer::sum);
                return null;
            }
            // Roll the failed branch out of the plan and try the next recipe.
            while (steps.size() > mark) steps.remove(steps.size() - 1);
        }

        // 3) Smelting/cooking.
        Recipe cook = cookingFor(item);
        if (cook != null && !cook.ingredients.isEmpty()) {
            if (!visiting.add(item)) return "recipe loop at " + item;
            String raw = resolveToken(cook.ingredients.get(0), inv);
            if (raw == null) {
                visiting.remove(item);
                return "no concrete item for " + cook.ingredients.get(0);
            }
            int crafts = (need + cook.count - 1) / cook.count;
            int rawCount = crafts * cook.count;
            String err = planInto(raw, rawCount, inv, steps, produced, visiting, depth + 1);
            if (err != null) {
                visiting.remove(item);
                return err;
            }
            produced.merge(raw, -rawCount, Integer::sum);
            // A furnace without fuel produces nothing; coal is the fuel the
            // bot can always mine, and planning it drags in the pickaxe it
            // needs in turn. Without this, "craft an iron pickaxe" died at
            // "I have no fuel to smelt with" halfway through the plan.
            err = ensureFuel(rawCount, inv, steps, produced, visiting, depth);
            if (err != null) {
                visiting.remove(item);
                return err;
            }
            steps.add(new Step(Op.SMELT, item, crafts * cook.count));
            produced.merge(item, crafts * cook.count, Integer::sum);
            visiting.remove(item);
            return null;
        }

        // 4) A placeable block with no recipe at all: mine it directly.
        if (Blocks.defaultState(item) >= 0) {
            String toolErr = ensureTool(item, inv, steps, produced, visiting, depth);
            if (toolErr != null) return toolErr;
            steps.add(new Step(Op.MINE, item, need));
            produced.merge(item, need, Integer::sum);
            return null;
        }

        return "don't know how to get " + item;
    }

    /**
     * Makes sure the plan can actually mine {@code block}.
     *
     * A missing tool is not a failure: it is one more recipe. Planning
     * "stone_pickaxe" to mine the stone that the stone pickaxe is made of
     * terminates at the wooden pickaxe, whose only ingredient is a tree.
     */
    private static String ensureTool(String block, Inventory inv, List<Step> steps,
                                     Map<String, Integer> produced, Set<String> visiting,
                                     int depth) {
        String tool = requiredToolFor(block);
        if (tool == null) return null;
        String cls = toolClassOf(tool);
        int rank = toolRankOf(tool);
        if (toolAvailable(cls, rank, inv, produced)) return null;
        String err = planInto(tool, 1, inv, steps, produced, visiting, depth + 1);
        if (err != null) {
            return "I need a " + tool + " to mine " + block + " and can't make one: " + err;
        }
        if (!toolAvailable(cls, rank, inv, produced)) {
            return "I can't get a " + tool + " to mine " + block;
        }
        return null;
    }

    /**
     * Makes sure there is something to burn before a smelting step.
     *
     * Held fuel wins, because using it costs nothing. Otherwise the plan mines
     * coal - not the planks the pickaxe left over: those are building material
     * for the rest of the plan, and burning them is how "craft an iron pickaxe"
     * used to end with a furnace full of the planks it still needed.
     *
     * The fuel is taken back out of the produced map because the furnace
     * consumes it; otherwise a second smelt would think the first batch's fuel
     * is still sitting in the inventory.
     */
    private static String ensureFuel(int items, Inventory inv, List<Step> steps,
                                     Map<String, Integer> produced, Set<String> visiting,
                                     int depth) {
        if (inv != null && bestFuel(inv) != null) return null;
        int coal = Math.max(1, (items + 7) / 8);      // coal burns 8 items
        String err = planInto("coal", coal, inv, steps, produced, visiting, depth + 1);
        if (err == null) {
            produced.merge("coal", -coal, Integer::sum);
            return null;
        }
        // No coal run possible: fall back to fuel earlier steps already make.
        String planned = plannedFuel(produced);
        if (planned != null) {
            int perFuel = Math.max(1, FUEL.get(planned) / 200);
            int need = Math.max(1, (items + perFuel - 1) / perFuel);
            produced.merge(planned, -need, Integer::sum);
            return null;
        }
        return "I need fuel to smelt and can't get any: " + err;
    }

    /** The best fuel item the plan itself will have produced, or null. */
    private static String plannedFuel(Map<String, Integer> produced) {
        String best = null;
        int bestTicks = 0;
        for (Map.Entry<String, Integer> e : produced.entrySet()) {
            if (e.getValue() <= 0) continue;
            int ticks = FUEL.getOrDefault(e.getKey(), 0);
            if (ticks > bestTicks) {
                bestTicks = ticks;
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * Crafting recipes for an item, cheapest first, minus the ones that only
     * unpack a block into its components ("9 iron ingots from an iron block").
     * Those are real recipes, but planning them means planning to own nine of
     * the thing you are trying to make.
     */
    private static List<Recipe> craftingCandidates(String item) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : forOutput(item)) {
            if (!r.isCrafting()) continue;
            List<String> tokens = r.allTokens();
            // "9 ingots from an iron block", "9 coal from a coal block": real
            // recipes, but planning them means planning to already own nine of
            // the thing being made.
            boolean decompression = tokens.size() == 1
                    && r.count >= 4
                    && strip(tokens.get(0)).endsWith("_block");
            if (decompression) continue;
            out.add(r);
        }
        out.sort(Comparator.comparingInt(Recipes::cells));
        return out;
    }

    /**
     * Picks one concrete item for an ingredient token.
     *
     * Tags and alternative lists are resolved by preference: something already
     * in the inventory, then oak (the wood a bot most often has), then anything
     * that can be gathered directly rather than crafted, then the first member.
     */
    public static String resolveToken(String token, Inventory inv) {
        if (token == null || token.isEmpty()) return null;
        if (token.indexOf('|') >= 0) {
            String[] parts = token.split("\\|");
            for (String p : parts) {
                if (inv != null && inv.count(p) > 0) return p;
            }
            for (String p : parts) {
                if (MINE_FOR.containsKey(p)) return p;
            }
            return parts[0];
        }
        if (!token.startsWith("#")) return token;
        Set<String> members = Tags.items(token.substring(1));
        if (members.isEmpty()) return null;
        if (inv != null) {
            for (String m : members) {
                if (inv.count(m) > 0) return m;
            }
        }
        List<String> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparingInt(Recipes::preference));
        return sorted.get(0);
    }

    private static int preference(String item) {
        // Tags are backed by a HashSet, so ties would otherwise resolve to a
        // random member. Cobblestone is the stone-tool material that is
        // actually near the surface; deepslate variants come last.
        if (item.equals("cobblestone")) return 0;
        if (item.startsWith("oak_")) return 0;
        if (item.startsWith("spruce_")) return 1;
        if (item.startsWith("birch_")) return 2;
        if (item.endsWith("_planks")) return 3;
        if (item.endsWith("_log")) return 4;
        // Deepslate variants are deeper and slower to reach than the stone ones.
        if (item.startsWith("deepslate_") || item.contains("_deepslate_")) return 9;
        if (item.equals("blackstone") || item.equals("cobbled_deepslate")) return 9;
        // Directly gatherable beats something that needs a furnace first:
        // "#coals" should pick coal, not charcoal.
        if (MINE_FOR.containsKey(item)) return 5;
        return 10;
    }

    /** Human-readable plan, for chat and the AI. */
    public static String describe(Plan plan) {
        if (!plan.ok()) return "I can't do that: " + plan.error;
        if (plan.steps.isEmpty()) return "I already have it.";
        StringBuilder sb = new StringBuilder();
        for (Step s : plan.steps) {
            if (sb.length() > 0) sb.append(", then ");
            switch (s.op) {
                case MINE: sb.append("mine ").append(s.count).append(' ').append(s.item); break;
                case CRAFT: sb.append("craft ").append(s.count).append(' ').append(s.item); break;
                case SMELT: sb.append("smelt ").append(s.count).append(' ').append(s.item); break;
            }
        }
        return sb.toString();
    }

    private static String strip(String s) {
        if (s == null) return "";
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }
}
