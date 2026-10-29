package mcbot.dev.test;

import mcbot.crafting.Recipes;
import mcbot.knowledge.Armor;
import mcbot.knowledge.Combat;
import mcbot.knowledge.Food;
import mcbot.knowledge.Loot;
import mcbot.knowledge.Mechanics;
import mcbot.registry.Blocks;
import mcbot.registry.ItemNames;
import mcbot.registry.Items;

/**
 * A sweep over the whole registry: every item planned, every block described.
 *
 * <p>The point is coverage, not depth. A table can be 99% correct and still
 * throw on the one entry nobody looked at - a block with no properties, an item
 * whose name collides with a tag, a recipe that outputs itself. Walking all
 * 1,658 items and all 35,723 states catches that class of bug in two seconds.
 */
public final class SweepTests {
    private SweepTests() {}

    public static void run() {
        TestRunner.suite("sweep: every item");

        TestRunner.test("every item can be described", () -> {
            int described = 0;
            for (String name : Items.allNames()) {
                String text = Mechanics.describeItem(name);
                TestRunner.check("empty description for " + name, text != null && !text.isBlank());
                described++;
            }
            TestRunner.atLeast("items described", 1500, described);
        });

        TestRunner.test("every item can be planned without throwing", () -> {
            int ok = 0;
            int impossible = 0;
            int longest = 0;
            long start = System.currentTimeMillis();
            for (String name : Items.allNames()) {
                if (name.equals("air")) continue;
                Recipes.Plan plan = Recipes.plan(name, 1, null);
                if (plan.ok()) {
                    ok++;
                    longest = Math.max(longest, plan.steps.size());
                    TestRunner.check("plan for " + name + " is too long", plan.steps.size() <= 64);
                } else {
                    impossible++;
                    TestRunner.check("failed plan must explain " + name, plan.error != null);
                }
            }
            TestRunner.check("sweep finished in bounded time",
                    System.currentTimeMillis() - start < 20_000);
            TestRunner.atLeast("plannable items", 700, ok);
            TestRunner.atLeast("deepest plan is sane", 1, longest);
            System.out.println("        " + ok + " plannable, " + impossible
                    + " not, deepest plan " + longest + " steps");
        });

        TestRunner.test("food, armour and weapons agree with the item registry", () -> {
            for (String name : Food.all()) {
                Items.Item item = Items.get(name);
                TestRunner.check("food is an item: " + name, item.id != 0);
                TestRunner.check("food has nutrition: " + name, item.nutrition > 0);
            }
            for (Armor.Piece piece : Armor.all()) {
                TestRunner.check("armour is an item: " + piece.name,
                        Items.get(piece.name).id != 0);
                TestRunner.check("armour has a slot: " + piece.name,
                        piece.slot.equals("head") || piece.slot.equals("chest")
                                || piece.slot.equals("legs") || piece.slot.equals("feet"));
            }
            for (String name : Combat.all()) {
                TestRunner.check("weapon is an item: " + name, Items.get(name).id != 0);
            }
        });

        TestRunner.suite("sweep: every block");

        TestRunner.test("every block state can be described", () -> {
            int count = 0;
            for (int state = 0; state < Blocks.stateCount(); state++) {
                String text = Mechanics.describeBlock(state);
                TestRunner.check("empty description for state " + state, text != null);
                count++;
            }
            TestRunner.atLeast("states described", 30_000, count);
        });

        TestRunner.test("every block has a coherent drop answer", () -> {
            for (String block : Blocks.blockNames()) {
                String drop = Loot.guaranteedDrop(block);
                if (drop != null) {
                    TestRunner.check("drop is an item: " + drop + " from " + block,
                            Items.get(drop).id != 0);
                }
                // describeBlock is what chat prints; it must never be null.
                TestRunner.check("no drop description for " + block,
                        Loot.describeBlock(block) != null);
            }
        });

        TestRunner.test("every block resolves through the name table", () -> {
            for (String block : Blocks.blockNames()) {
                String resolved = ItemNames.resolveBlock(block);
                TestRunner.eq("resolve " + block, block, resolved);
            }
        });

        TestRunner.test("collision classification is total", () -> {
            for (int state = 0; state < Blocks.stateCount(); state++) {
                byte category = Blocks.category(state);
                TestRunner.check("bad category at state " + state,
                        category >= Blocks.PASSABLE && category <= Blocks.CLIMBABLE);
            }
        });
    }
}
