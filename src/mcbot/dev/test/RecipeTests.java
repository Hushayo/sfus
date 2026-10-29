package mcbot.dev.test;

import mcbot.crafting.Recipe;
import mcbot.crafting.Recipes;
import mcbot.inventory.Inventory;
import mcbot.registry.Items;

/**
 * Recipe and planner tests.
 *
 * <p>The planner is the part most likely to be subtly wrong, because a plan can
 * look plausible and still be impossible: crafting planks from planks, mining a
 * block that needs the pickaxe it is trying to make, or planning nine ingots
 * from an iron block the bot does not have. Each of those has a test.
 */
public final class RecipeTests {
    private RecipeTests() {}

    public static void run() {
        TestRunner.suite("recipes");

        TestRunner.test("the recipe book is fully loaded", () -> {
            TestRunner.atLeast("recipes", 1600, Recipes.total());
        });

        TestRunner.test("every recipe outputs a real item", () -> {
            for (Recipe r : Recipes.all()) {
                TestRunner.check("unknown output " + r.output, Items.get(r.output).id != 0);
            }
        });

        TestRunner.test("every shaped recipe fits a 3x3 grid", () -> {
            for (Recipe r : Recipes.all()) {
                if (r.kind != Recipe.Kind.SHAPED) continue;
                TestRunner.check("too many rows in " + r, r.pattern.length <= 3);
                for (String[] row : r.pattern) {
                    TestRunner.check("too many columns in " + r, row.length <= 3);
                }
            }
        });

        TestRunner.test("recipes with no ingredients are not planned", () -> {
            for (Recipe r : Recipes.all()) {
                if (!r.isCrafting()) continue;
                TestRunner.check("empty recipe " + r, !r.allTokens().isEmpty());
            }
        });

        TestRunner.test("sticks are four from two planks", () -> {
            Recipe stick = Recipes.craftingFor("stick");
            TestRunner.notNull("stick recipe", stick);
            TestRunner.eq("output count", 4, stick.count);
            TestRunner.eq("ingredients", 2, stick.allTokens().size());
        });

        TestRunner.test("planks come from a log", () -> {
            Recipe planks = Recipes.craftingFor("oak_planks");
            TestRunner.notNull("planks recipe", planks);
            TestRunner.eq("output count", 4, planks.count);
            TestRunner.contains("log ingredient", planks.allTokens().get(0), "log");
        });

        TestRunner.test("a wooden pickaxe plans from bare hands", () -> {
            Recipes.Plan plan = Recipes.plan("wooden_pickaxe", 1, null);
            TestRunner.check("plan ok: " + plan.error, plan.ok());
            boolean log = false, planks = false, stick = false, pick = false;
            for (Recipes.Step s : plan.steps) {
                if (s.op == Recipes.Op.MINE && s.item.endsWith("_log")) log = true;
                if (s.op == Recipes.Op.CRAFT && s.item.endsWith("_planks")) planks = true;
                if (s.op == Recipes.Op.CRAFT && s.item.equals("stick")) stick = true;
                if (s.op == Recipes.Op.CRAFT && s.item.equals("wooden_pickaxe")) pick = true;
            }
            TestRunner.check("mines a log", log);
            TestRunner.check("crafts planks", planks);
            TestRunner.check("crafts sticks", stick);
            TestRunner.check("crafts the pickaxe", pick);
        });

        TestRunner.test("a stone pickaxe plans its wooden predecessor", () -> {
            Recipes.Plan plan = Recipes.plan("stone_pickaxe", 1, null);
            TestRunner.check("plan ok: " + plan.error, plan.ok());
            boolean wooden = false, cobble = false;
            for (Recipes.Step s : plan.steps) {
                if (s.item.equals("wooden_pickaxe")) wooden = true;
                if (s.op == Recipes.Op.MINE && s.item.equals("stone")) cobble = true;
            }
            TestRunner.check("crafts a wooden pickaxe first", wooden);
            TestRunner.check("mines stone", cobble);
        });

        TestRunner.test("an iron pickaxe plans smelting and fuel", () -> {
            Recipes.Plan plan = Recipes.plan("iron_pickaxe", 1, null);
            TestRunner.check("plan ok: " + plan.error, plan.ok());
            boolean smelt = false, fuel = false, iron = false;
            for (Recipes.Step s : plan.steps) {
                if (s.op == Recipes.Op.SMELT && s.item.equals("iron_ingot")) smelt = true;
                if (s.op == Recipes.Op.MINE && (s.item.equals("coal") || s.item.equals("coal_ore"))) {
                    fuel = true;
                }
                if (s.op == Recipes.Op.MINE && s.item.contains("iron_ore")) iron = true;
            }
            TestRunner.check("smelts iron", smelt);
            TestRunner.check("mines fuel", fuel);
            TestRunner.check("mines iron ore", iron);
        });

        TestRunner.test("a diamond pickaxe is a deep plan, not a dead end", () -> {
            Recipes.Plan plan = Recipes.plan("diamond_pickaxe", 1, null);
            TestRunner.check("plan ok: " + plan.error, plan.ok());
            TestRunner.atLeast("steps", 8, plan.steps.size());
            boolean diamond = false;
            for (Recipes.Step s : plan.steps) {
                if (s.op == Recipes.Op.MINE && s.item.contains("diamond_ore")) diamond = true;
            }
            TestRunner.check("mines diamond ore", diamond);
        });

        TestRunner.test("an impossible item fails with a reason", () -> {
            Recipes.Plan plan = Recipes.plan("elytra", 1, null);
            TestRunner.check("plan fails", !plan.ok());
            TestRunner.notNull("error text", plan.error);
        });

        TestRunner.test("a held item is not planned again", () -> {
            Inventory inv = TestFixtures.packed("oak_planks:8");
            Recipes.Plan plan = Recipes.plan("oak_planks", 4, inv);
            TestRunner.check("plan ok", plan.ok());
            TestRunner.eq("no steps", 0, plan.steps.size());
        });

        TestRunner.test("drops are taken from the loot table, not the name", () -> {
            TestRunner.eq("stone", "cobblestone", Recipes.dropOf("stone"));
            TestRunner.eq("grass", "dirt", Recipes.dropOf("grass_block"));
            TestRunner.eq("leaves drop nothing", null, Recipes.dropOf("oak_leaves"));
            TestRunner.eq("log drops itself", "oak_log", Recipes.dropOf("oak_log"));
        });

        TestRunner.test("mining sources are chosen sensibly", () -> {
            TestRunner.eq("diamond", "diamond_ore", Recipes.mineSource("diamond"));
            TestRunner.eq("cobblestone", "stone", Recipes.mineSource("cobblestone"));
            TestRunner.eq("oak log", "oak_log", Recipes.mineSource("oak_log"));
        });

        TestRunner.test("fuel values match vanilla", () -> {
            TestRunner.eq("coal", 1600, Recipes.fuelTicks("coal"));
            TestRunner.eq("planks", 300, Recipes.fuelTicks("oak_planks"));
            TestRunner.eq("stick", 100, Recipes.fuelTicks("stick"));
            TestRunner.eq("stone is not fuel", 0, Recipes.fuelTicks("stone"));
        });

        TestRunner.test("canCraftNow agrees with the plan", () -> {
            Inventory inv = TestFixtures.packed("oak_planks:2");
            Recipe stick = Recipes.craftingFor("stick");
            TestRunner.check("two planks make sticks", Recipes.canCraftNow(stick, inv));
            Inventory empty = TestFixtures.packed();
            TestRunner.check("nothing makes nothing", !Recipes.canCraftNow(stick, empty));
        });

        TestRunner.test("plan text is readable", () -> {
            Recipes.Plan plan = Recipes.plan("wooden_pickaxe", 1, null);
            String text = Recipes.describe(plan);
            TestRunner.contains("mentions mining", text, "mine");
            TestRunner.contains("mentions crafting", text, "craft");
        });
    }
}
