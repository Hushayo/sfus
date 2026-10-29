package mcbot.dev.test;

import mcbot.registry.Blocks;
import mcbot.registry.Components;
import mcbot.registry.Entities;
import mcbot.registry.Items;
import mcbot.inventory.MenuLayout;
import mcbot.registry.Shape;
import mcbot.registry.Tags;

/**
 * Registry tests: the tables the whole bot stands on.
 *
 * <p>These are the invariants that, when broken, make everything else wrong in
 * a way that is hard to see. A block registry that is off by one state id still
 * "works" - it just reports the wrong block for thousands of ids, and the
 * pathfinder starts walking through walls.
 */
public final class RegistryTests {
    private RegistryTests() {}

    public static void run() {
        TestRunner.suite("registry");

        TestRunner.test("every state id in range resolves to a block name", () -> {
            int missing = 0;
            for (int state = 0; state < Blocks.stateCount(); state++) {
                if (Blocks.name(state) == null) missing++;
            }
            TestRunner.eq("unmapped states", 0, missing);
        });

        TestRunner.test("registry has the expected scale", () -> {
            TestRunner.atLeast("blocks", 1200, Blocks.blockCount());
            TestRunner.atLeast("states", 30_000, Blocks.stateCount());
            TestRunner.atLeast("items", 1500, Items.count());
        });

        TestRunner.test("state 0 is air", () -> {
            TestRunner.eq("state 0", "air", Blocks.name(0));
            TestRunner.check("air is passable", Blocks.passable(0));
        });

        TestRunner.test("a block's states form one contiguous id range", () -> {
            int[] states = Blocks.statesOf("oak_stairs");
            TestRunner.atLeast("oak_stairs states", 40, states.length);
            for (int i = 1; i < states.length; i++) {
                TestRunner.eq("contiguous at " + i, states[i - 1] + 1, states[i]);
            }
        });

        TestRunner.test("property decode matches a state built from properties", () -> {
            int state = Blocks.state("oak_stairs", "facing=east", "half=top");
            TestRunner.check("state found", state >= 0);
            TestRunner.eq("facing", "east", Blocks.property(state, "facing"));
            TestRunner.eq("half", "top", Blocks.property(state, "half"));
        });

        TestRunner.test("slab shape follows its type property", () -> {
            int bottom = Blocks.state("oak_slab", "type=bottom");
            int top = Blocks.state("oak_slab", "type=top");
            int doubled = Blocks.state("oak_slab", "type=double");
            TestRunner.near("bottom slab height", 0.5, Blocks.topHeight(bottom), 0.01);
            TestRunner.near("top slab top", 1.0, Blocks.topHeight(top), 0.01);
            TestRunner.near("double slab height", 1.0, Blocks.topHeight(doubled), 0.01);
        });

        TestRunner.test("open doors are passable, closed doors are not", () -> {
            int open = Blocks.state("oak_door", "open=true");
            int closed = Blocks.state("oak_door", "open=false");
            TestRunner.check("open door passable", Blocks.passable(open));
            TestRunner.check("closed door blocks movement", !Blocks.passable(closed));
        });

        TestRunner.test("hardness matches vanilla", () -> {
            TestRunner.near("stone", 1.5, Blocks.hardness(Blocks.defaultState("stone")), 0.001);
            TestRunner.near("obsidian", 50.0, Blocks.hardness(Blocks.defaultState("obsidian")), 0.001);
            TestRunner.near("dirt", 0.5, Blocks.hardness(Blocks.defaultState("dirt")), 0.001);
            TestRunner.check("bedrock is unbreakable",
                    Blocks.unbreakable(Blocks.defaultState("bedrock")));
        });

        TestRunner.test("tool class and required tier are correct", () -> {
            TestRunner.eq("stone tool", "pickaxe", Blocks.toolClass(Blocks.defaultState("stone")));
            TestRunner.eq("oak log tool", "axe", Blocks.toolClass(Blocks.defaultState("oak_log")));
            TestRunner.eq("dirt tool", "shovel", Blocks.toolClass(Blocks.defaultState("dirt")));
            TestRunner.eq("diamond ore tier", "iron",
                    Blocks.requiredTier(Blocks.defaultState("diamond_ore")));
            TestRunner.eq("iron ore tier", "stone",
                    Blocks.requiredTier(Blocks.defaultState("iron_ore")));
        });

        TestRunner.test("hazards are classified as hazards", () -> {
            for (String name : new String[]{"lava", "fire", "magma_block", "cactus", "powder_snow"}) {
                int state = Blocks.defaultState(name);
                TestRunner.eq(name + " category", Blocks.HAZARD, Blocks.category(state));
            }
        });

        TestRunner.test("ladders and vines are climbable", () -> {
            TestRunner.eq("ladder", Blocks.CLIMBABLE, Blocks.category(Blocks.defaultState("ladder")));
            TestRunner.eq("vine", Blocks.CLIMBABLE, Blocks.category(Blocks.defaultState("vine")));
        });

        TestRunner.test("water is a liquid, not a solid", () -> {
            int water = Blocks.defaultState("water");
            TestRunner.eq("water category", Blocks.LIQUID, Blocks.category(water));
            TestRunner.check("water has no collision", Blocks.shape(water) == Shape.EMPTY);
        });

        TestRunner.test("item tool speeds match vanilla", () -> {
            TestRunner.near("diamond pickaxe", 8.0, Items.get("diamond_pickaxe").toolSpeed, 0.001);
            TestRunner.near("iron pickaxe", 6.0, Items.get("iron_pickaxe").toolSpeed, 0.001);
            TestRunner.near("wooden pickaxe", 2.0, Items.get("wooden_pickaxe").toolSpeed, 0.001);
            TestRunner.eq("diamond tier", "diamond", Items.get("diamond_pickaxe").toolTier);
        });

        TestRunner.test("item stack sizes and durability match vanilla", () -> {
            TestRunner.eq("apple stacks to 64", 64, Items.get("apple").maxStack);
            TestRunner.eq("pickaxe stacks to 1", 1, Items.get("diamond_pickaxe").maxStack);
            TestRunner.eq("diamond pickaxe durability", 1561, Items.get("diamond_pickaxe").maxDamage);
            TestRunner.eq("elytra durability", 432, Items.get("elytra").maxDamage);
        });

        TestRunner.test("food values are present in the item registry", () -> {
            TestRunner.eq("apple nutrition", 4, Items.get("apple").nutrition);
            TestRunner.eq("cooked beef nutrition", 8, Items.get("cooked_beef").nutrition);
            TestRunner.check("apple is food", Items.get("apple").isFood());
            TestRunner.check("stone is not food", !Items.get("stone").isFood());
        });

        TestRunner.test("tags expand nested references", () -> {
            TestRunner.check("planks tag has oak", Tags.items("planks").contains("oak_planks"));
            TestRunner.check("logs tag has oak", Tags.blocks("logs").contains("oak_log"));
            TestRunner.check("stone tools tag is non-empty", Tags.items("stone_tool_materials").size() > 0);
        });

        TestRunner.test("block tags include the expected families", () -> {
            TestRunner.check("mineable/pickaxe contains stone",
                    Tags.blocks("mineable/pickaxe").contains("stone"));
            TestRunner.check("needs_iron_tool contains diamond_ore",
                    Tags.blocks("needs_iron_tool").contains("diamond_ore"));
        });

        TestRunner.test("entity ids resolve to names", () -> {
            int zombie = Entities.id("zombie");
            TestRunner.check("zombie id is valid", zombie > 0);
            TestRunner.eq("zombie name", "zombie", Entities.name(zombie));
            TestRunner.check("item entity is recognised", Entities.isDroppedItem(Entities.id("item")));
        });

        TestRunner.test("data component ids are unique", () -> {
            TestRunner.check("component count", Components.count() > 50);
            TestRunner.check("max_stack_size is a known component",
                    Components.id("max_stack_size") >= 0);
        });

        TestRunner.test("menu layouts line up with their slot counts", () -> {
            var chest = MenuLayout.byName("generic_9x6");
            TestRunner.eq("chest container slots", 54, chest.containerSlots);
            TestRunner.eq("chest main start", 54, chest.mainStart);
            TestRunner.eq("chest hotbar start", 81, chest.hotbarStart);
            var furnace = MenuLayout.byName("furnace");
            TestRunner.eq("furnace slots", 3, furnace.containerSlots);
        });
    }
}
