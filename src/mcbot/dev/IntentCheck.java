package mcbot.dev;

import mcbot.crafting.Recipe;
import mcbot.crafting.Recipes;
import mcbot.inventory.Inventory;
import mcbot.inventory.ItemStack;
import mcbot.registry.Blocks;
import mcbot.registry.ItemNames;
import mcbot.registry.Items;

/**
 * Offline check of the natural-language layer.
 *
 * <pre>
 *   java -cp out mcbot.dev.IntentCheck
 * </pre>
 *
 * It answers the questions that decide whether "mine 10 stone" or "craft me a
 * pickaxe" will do anything sensible, without needing a server:
 *
 *  - does the phrase resolve to an item/block that exists?
 *  - does the recipe planner produce a plan, and in what order?
 *  - does the block have a drop, a tool, a hardness?
 */
public final class IntentCheck {
    private IntentCheck() {}

    public static void main(String[] args) {
        System.out.println("=== phrase resolution ===");
        for (String phrase : new String[]{
                "stone", "stones", "oak log", "oak logs", "iron ore", "diamonds",
                "cobblestone", "pickaxe", "diamond pickaxe", "wooden pickaxe",
                "crafting table", "sticks", "torch", "furnace", "iron ingot",
                "raw iron", "wheat", "bread", "chest", "boat", "shovel"}) {
            String block = ItemNames.resolveBlock(phrase);
            String item = ItemNames.resolveItem(phrase);
            System.out.printf("  %-16s block=%-18s item=%-18s%n",
                    phrase, block == null ? "-" : block, item == null ? "-" : item);
        }

        System.out.println();
        System.out.println("=== block facts ===");
        for (String block : new String[]{"stone", "oak_log", "iron_ore", "diamond_ore"}) {
            int state = Blocks.defaultState(block);
            System.out.printf("  %-12s state=%-6d hardness=%.2f tool=%s tier=%s drop=%s%n",
                    block, state, Blocks.hardness(state), Blocks.toolClass(state),
                    Blocks.requiredTier(state), Recipes.dropOf(block));
        }

        System.out.println();
        System.out.println("=== plans (empty inventory) ===");
        for (String[] target : new String[][]{
                {"pickaxe", "1"}, {"wooden_pickaxe", "1"}, {"stone_pickaxe", "1"},
                {"crafting_table", "1"}, {"torch", "4"}, {"chest", "1"},
                {"iron_pickaxe", "1"}, {"stick", "8"}}) {
            String item = ItemNames.resolveItem(target[0]);
            int count = Integer.parseInt(target[1]);
            Recipes.Plan plan = Recipes.plan(item, count, null);
            System.out.printf("  %-18s -> %s%n", item,
                    plan.ok() ? Recipes.describe(plan) : "FAILED: " + plan.error);
        }

        System.out.println();
        System.out.println("=== recipe matching (3x3 grid) ===");
        checkGrid("stick", new String[][]{
                {"oak_planks", null, null}, {"oak_planks", null, null}, {null, null, null}});
        checkGrid("crafting_table", new String[][]{
                {"oak_planks", "oak_planks", null}, {"oak_planks", "oak_planks", null},
                {null, null, null}});
        checkGrid("torch", new String[][]{
                {"coal", null, null}, {"stick", null, null}, {null, null, null}});

        System.out.println();
        System.out.println("=== item facts ===");
        for (String name : new String[]{"iron_pickaxe", "apple", "coal", "oak_planks"}) {
            System.out.println("  " + Items.describe(Items.id(name)));
        }

        System.out.println();
        System.out.println("=== goal checks (the bot should not need to be babysat) ===");
        planChecks();
        if (failures > 0) {
            System.out.println("  " + failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("  all checks passed");
    }

    private static int failures;

    /** Plans that must contain their prerequisites, in the right order. */
    private static void planChecks() {
        // From bare hands: the whole tool ladder is in one plan.
        check("stone pickaxe builds its wooden pickaxe first",
                Recipes.plan("stone_pickaxe", 1, null),
                p -> before(p, Recipes.Op.CRAFT, "wooden_pickaxe",
                        Recipes.Op.MINE, "stone"));

        check("iron pickaxe builds stone pickaxe before mining iron ore",
                Recipes.plan("iron_pickaxe", 1, null),
                p -> before(p, Recipes.Op.CRAFT, "stone_pickaxe",
                        Recipes.Op.MINE, "iron_ore"));

        check("iron pickaxe mines fuel before smelting",
                Recipes.plan("iron_pickaxe", 1, null),
                p -> before(p, Recipes.Op.MINE, "coal_ore",
                        Recipes.Op.SMELT, "iron_ingot"));

        check("smelting plans the ore and the coal",
                Recipes.plan("iron_ingot", 3, null),
                p -> has(p, Recipes.Op.MINE, "iron_ore")
                        && before(p, Recipes.Op.MINE, "coal_ore",
                                  Recipes.Op.SMELT, "iron_ingot"));

        check("furnace plans the stone it is made of",
                Recipes.plan("furnace", 1, null),
                p -> before(p, Recipes.Op.MINE, "stone", Recipes.Op.CRAFT, "furnace"));

        check("diamond pickaxe plans an iron pickaxe for the diamonds",
                Recipes.plan("diamond_pickaxe", 1, null),
                p -> before(p, Recipes.Op.CRAFT, "iron_pickaxe",
                        Recipes.Op.MINE, "diamond_ore"));

        // With a stone pickaxe already in hand, the ladder must not repeat.
        Inventory have = new Inventory();
        have.open(Inventory.PLAYER_WINDOW, -1);
        have.setPlayerSlot(0, new ItemStack(Items.id("stone_pickaxe"), 1, 0, false));
        Recipes.Plan stocked = Recipes.plan("iron_pickaxe", 1, have);
        check("a held stone pickaxe is not crafted again", stocked,
                p -> !has(p, Recipes.Op.CRAFT, "wooden_pickaxe")
                        && !has(p, Recipes.Op.CRAFT, "stone_pickaxe")
                        && has(p, Recipes.Op.CRAFT, "iron_pickaxe"));
    }

    private static void check(String label, Recipes.Plan plan,
                              java.util.function.Predicate<Recipes.Plan> ok) {
        boolean pass = plan.ok() && ok.test(plan);
        System.out.printf("  %-4s %s%n", pass ? "PASS" : "FAIL", label);
        if (!pass) {
            failures++;
            System.out.println("         plan: "
                    + (plan.ok() ? Recipes.describe(plan) : "FAILED: " + plan.error));
        }
    }

    private static boolean has(Recipes.Plan plan, Recipes.Op op, String item) {
        for (Recipes.Step s : plan.steps) if (s.op == op && s.item.equals(item)) return true;
        return false;
    }

    /** True when the first (op,item) step appears before the second. */
    private static boolean before(Recipes.Plan plan, Recipes.Op first, String firstItem,
                                  Recipes.Op second, String secondItem) {
        int a = -1, b = -1;
        for (int i = 0; i < plan.steps.size(); i++) {
            Recipes.Step s = plan.steps.get(i);
            if (a < 0 && s.op == first && s.item.equals(firstItem)) a = i;
            if (b < 0 && s.op == second && s.item.equals(secondItem)) b = i;
        }
        return a >= 0 && b >= 0 && a < b;
    }

    private static void checkGrid(String expect, String[][] grid) {
        for (Recipe r : Recipes.forOutput(expect)) {
            if (r.matches(grid)) {
                System.out.printf("  grid matches %-14s (%s, %d out)%n", expect, r.type, r.count);
                return;
            }
        }
        System.out.printf("  NO MATCH for %s%n", expect);
    }
}
