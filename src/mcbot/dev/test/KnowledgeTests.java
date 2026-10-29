package mcbot.dev.test;

import mcbot.inventory.Inventory;
import mcbot.inventory.ItemStack;
import mcbot.knowledge.Armor;
import mcbot.knowledge.Combat;
import mcbot.knowledge.DamageTypes;
import mcbot.knowledge.Enchantments;
import mcbot.knowledge.Food;
import mcbot.knowledge.Loot;
import mcbot.knowledge.Mechanics;
import mcbot.knowledge.Mobs;
import mcbot.registry.Blocks;
import mcbot.registry.Items;

import java.util.List;
import java.util.Map;

/**
 * Knowledge tests: the mechanics tables, checked against values that are
 * independently known to be right.
 *
 * <p>These are the tests that catch a data generation bug. If the loot parser
 * silently starts reporting "oak leaves always drop oak leaves", nothing
 * crashes - the bot just plans a trip that yields nothing. A handful of
 * canonical cases (diamond ore, stone, grass, leaves) pin the whole pipeline.
 */
public final class KnowledgeTests {
    private KnowledgeTests() {}

    public static void run() {
        TestRunner.suite("knowledge: loot");

        TestRunner.test("diamond ore drops a diamond, silk touch drops the block", () -> {
            TestRunner.eq("guaranteed", "diamond", Loot.guaranteedDrop("diamond_ore"));
            boolean silk = false;
            for (Loot.Drop d : Loot.forBlock("diamond_ore")) {
                if (d.item.equals("diamond_ore") && d.silk) silk = true;
            }
            TestRunner.check("silk-touch branch exists", silk);
        });

        TestRunner.test("stone drops cobblestone, grass drops dirt", () -> {
            TestRunner.eq("stone", "cobblestone", Loot.guaranteedDrop("stone"));
            TestRunner.eq("grass_block", "dirt", Loot.guaranteedDrop("grass_block"));
            TestRunner.eq("cobblestone", "cobblestone", Loot.guaranteedDrop("cobblestone"));
        });

        TestRunner.test("leaves guarantee nothing", () -> {
            TestRunner.eq("oak leaves", null, Loot.guaranteedDrop("oak_leaves"));
            TestRunner.check("still lists probable drops", !Loot.probableDrops("oak_leaves").isEmpty());
        });

        TestRunner.test("rare drops keep their probability", () -> {
            Loot.Drop seeds = null;
            for (Loot.Drop d : Loot.forBlock("short_grass")) {
                if (d.item.equals("wheat_seeds")) seeds = d;
            }
            TestRunner.notNull("wheat seeds row", seeds);
            TestRunner.near("seed chance", 0.125, seeds.chance, 0.001);
        });

        TestRunner.test("ore drops are exact and guaranteed", () -> {
            TestRunner.eq("iron ore", "raw_iron", Loot.guaranteedDrop("iron_ore"));
            TestRunner.eq("deepslate iron", "raw_iron", Loot.guaranteedDrop("deepslate_iron_ore"));
            TestRunner.eq("coal ore", "coal", Loot.guaranteedDrop("coal_ore"));
            TestRunner.eq("redstone ore", "redstone", Loot.guaranteedDrop("redstone_ore"));
        });

        TestRunner.test("mob drops include the signature item", () -> {
            TestRunner.eq("zombie flesh", "rotten_flesh", Loot.forEntity("zombie").get(0).item);
            boolean bones = false;
            for (Loot.Drop d : Loot.forEntity("skeleton")) {
                if (d.item.equals("bone")) bones = true;
            }
            TestRunner.check("skeleton drops bones", bones);
        });

        TestRunner.test("every loot item resolves to a real item", () -> {
            int checked = 0;
            for (Map.Entry<String, List<Loot.Drop>> e : Loot.allBlocks().entrySet()) {
                for (Loot.Drop d : e.getValue()) {
                    if (d.item.startsWith("#") || d.item.startsWith("@")) continue;
                    TestRunner.check("unknown item " + d.item + " from " + e.getKey(),
                            Items.get(d.item).id != 0);
                    checked++;
                }
            }
            TestRunner.atLeast("drop rows checked", 500, checked);
        });

        TestRunner.test("sources of an item are real blocks", () -> {
            List<String> diamond = Loot.sourcesOf("diamond");
            TestRunner.check("diamond has a source", !diamond.isEmpty());
            for (String block : diamond) {
                TestRunner.check("unknown source block " + block, Blocks.defaultState(block) >= 0);
            }
        });

        TestRunner.suite("knowledge: food and armour");

        TestRunner.test("food values match vanilla", () -> {
            TestRunner.eq("apple nutrition", 4, Food.get("apple").nutrition);
            TestRunner.near("apple saturation", 2.4, Food.get("apple").saturation, 0.01);
            TestRunner.eq("cooked beef nutrition", 8, Food.get("cooked_beef").nutrition);
            TestRunner.near("cooked beef saturation", 12.8, Food.get("cooked_beef").saturation, 0.01);
            TestRunner.eq("bread nutrition", 5, Food.get("bread").nutrition);
        });

        TestRunner.test("golden apples can be eaten when full", () -> {
            TestRunner.check("always eat", Food.get("golden_apple").alwaysEat);
            TestRunner.check("can eat at 20", Food.canEat(20, Food.get("golden_apple")));
            TestRunner.check("apple cannot be eaten at 20", !Food.canEat(20, Food.get("apple")));
        });

        TestRunner.test("eating takes 1.6 s and the food list is complete", () -> {
            TestRunner.near("eat seconds", 1.6, Food.get("apple").eatSeconds, 0.01);
            TestRunner.atLeast("edible items", 35, Food.count());
        });

        TestRunner.test("golden apples are not ordinary food", () -> {
            var onlyGolden = TestFixtures.packed("enchanted_golden_apple:3");
            TestRunner.check("not chosen for hunger", Food.best(onlyGolden) == null);
            TestRunner.eq("chosen in an emergency", "enchanted_golden_apple",
                    Food.best(onlyGolden, true).name);

            var mixed = TestFixtures.packed("enchanted_golden_apple:3", "bread:5");
            TestRunner.eq("bread beats a golden apple", "bread", Food.best(mixed).name);
            TestRunner.check("precious is flagged", Food.precious("golden_apple"));
            TestRunner.check("bread is not precious", !Food.precious("bread"));
        });

        TestRunner.test("armour defence values match vanilla", () -> {
            TestRunner.eq("iron chestplate", 6, Armor.get("iron_chestplate").defense);
            TestRunner.eq("diamond helmet", 3, Armor.get("diamond_helmet").defense);
            TestRunner.near("diamond toughness", 2.0, Armor.get("diamond_helmet").toughness, 0.01);
            TestRunner.eq("netherite chestplate", 8, Armor.get("netherite_chestplate").defense);
            TestRunner.eq("slot", "feet", Armor.get("diamond_boots").slot);
        });

        TestRunner.test("armour damage formula matches vanilla", () -> {
            // Full diamond: 20 defence, 8 toughness.
            TestRunner.near("20 damage -> 8", 8.0, Armor.afterArmor(20, 20, 8f), 0.01);
            // Armour is weak against huge hits: the effective points floor at
            // defence/5, so 100 damage still gets through 84%.
            TestRunner.near("100 damage -> 84", 84.0, Armor.afterArmor(100, 20, 8f), 0.01);
            TestRunner.near("no armour", 7.0, Armor.afterArmor(7, 0, 0f), 0.01);
        });

        TestRunner.test("protection caps at 80 percent", () -> {
            TestRunner.near("four pieces of protection IV", 0.2,
                    Armor.afterProtection(1.0, 16) / 1.0, 0.01);
        });

        TestRunner.suite("knowledge: combat");

        TestRunner.test("weapon damage and speed match vanilla", () -> {
            TestRunner.near("diamond sword damage", 7.0, Combat.weapon("diamond_sword").damage, 0.01);
            TestRunner.near("diamond sword speed", 1.6, Combat.weapon("diamond_sword").speed, 0.01);
            TestRunner.near("netherite axe damage", 10.0, Combat.weapon("netherite_axe").damage, 0.01);
            TestRunner.eq("diamond sword cooldown", 13, Combat.weapon("diamond_sword").cooldownTicks());
        });

        TestRunner.test("swing damage scales with cooldown progress", () -> {
            Combat.Weapon sword = Combat.weapon("diamond_sword");
            TestRunner.near("full", 7.0, Combat.swingDamage(sword, 1.0, false), 0.01);
            TestRunner.near("half", 2.8, Combat.swingDamage(sword, 0.5, false), 0.01);
            TestRunner.near("panic click", 1.4, Combat.swingDamage(sword, 0.0, false), 0.01);
            TestRunner.near("critical", 10.5, Combat.swingDamage(sword, 1.0, true), 0.01);
        });

        TestRunner.test("criticals need a fall", () -> {
            TestRunner.check("falling mid-air", Combat.canCrit(true, false, false));
            TestRunner.check("not while sprinting", !Combat.canCrit(true, false, true));
            TestRunner.check("not on the ground", !Combat.canCrit(false, true, false));
        });

        TestRunner.test("shields block, axes partly get through", () -> {
            TestRunner.near("sword blocked", 0.0,
                    Combat.afterShield(7, true, true, false), 0.01);
            TestRunner.near("axe through shield", 2.0,
                    Combat.afterShield(7, true, true, true), 0.01);
            TestRunner.near("not blocking", 7.0,
                    Combat.afterShield(7, false, true, false), 0.01);
        });

        TestRunner.suite("knowledge: mobs and effects");

        TestRunner.test("mob stats and dispositions are right", () -> {
            TestRunner.eq("zombie health", 20f, Mobs.get("zombie").health);
            TestRunner.eq("zombie damage", 3f, Mobs.get("zombie").attack);
            TestRunner.eq("zombie disposition", Mobs.Disposition.HOSTILE, Mobs.get("zombie").disposition);
            TestRunner.eq("cow disposition", Mobs.Disposition.PASSIVE, Mobs.get("cow").disposition);
            TestRunner.eq("enderman disposition", Mobs.Disposition.NEUTRAL,
                    Mobs.get("enderman").disposition);
            TestRunner.eq("creeper health", 20f, Mobs.get("creeper").health);
        });

        TestRunner.test("threat scoring ignores passive mobs", () -> {
            TestRunner.near("cow at 2 blocks", 0.0, Mobs.threatScore("cow", 2), 0.01);
            TestRunner.check("zombie at 2 blocks", Mobs.threatScore("zombie", 2) > 0);
            TestRunner.check("skeleton is dangerous further out",
                    Mobs.threatScore("skeleton", 12) > Mobs.threatScore("zombie", 12));
            TestRunner.near("zombie very far", 0.0, Mobs.threatScore("zombie", 40), 0.01);
        });

        TestRunner.test("hits to kill are computed from health", () -> {
            TestRunner.eq("zombie with a diamond sword", 3,
                    Mobs.get("zombie").hitsToKill(7.0));
            TestRunner.eq("cow with a fist", 10, Mobs.get("cow").hitsToKill(1.0));
        });

        TestRunner.test("enchantments have sensible maxima", () -> {
            TestRunner.eq("sharpness", 5, Enchantments.maxLevel("sharpness"));
            TestRunner.eq("protection", 4, Enchantments.maxLevel("protection"));
            TestRunner.eq("unbreaking", 3, Enchantments.maxLevel("unbreaking"));
            TestRunner.check("sharpness applies to a sword",
                    Enchantments.appliesTo("sharpness", "diamond_sword"));
        });

        TestRunner.test("damage types are classified", () -> {
            TestRunner.check("fall is environmental", DamageTypes.isEnvironmental("fall"));
            TestRunner.check("lava is environmental", DamageTypes.isEnvironmental("lava"));
            TestRunner.check("drowning is environmental", DamageTypes.isEnvironmental("drowning"));
            TestRunner.check("mob attack is combat", DamageTypes.isCombat("mob_attack"));
        });

        TestRunner.suite("knowledge: mechanics");

        TestRunner.test("block descriptions carry the drop and the tool", () -> {
            String stone = Mechanics.describeBlock(Blocks.defaultState("stone"));
            TestRunner.contains("stone description", stone, "cobblestone");
            TestRunner.contains("stone description", stone, "pickaxe");
            String leaves = Mechanics.describeBlock(Blocks.defaultState("oak_leaves"));
            TestRunner.contains("leaves description", leaves, "oak_sapling");
        });

        TestRunner.test("hand mining times match vanilla", () -> {
            Inventory empty = new Inventory();
            TestRunner.near("stone by hand", 7.5,
                    Mechanics.breakSeconds(empty, Blocks.defaultState("stone")), 0.05);
            TestRunner.near("dirt by hand", 0.75,
                    Mechanics.breakSeconds(empty, Blocks.defaultState("dirt")), 0.05);
        });

        TestRunner.test("a missing tool is reported, not guessed", () -> {
            Inventory empty = new Inventory();
            String why = Mechanics.whyCannotMine(empty, Blocks.defaultState("stone"));
            TestRunner.notNull("reason", why);
            TestRunner.contains("reason names the tool", why, "pickaxe");
            TestRunner.check("dirt needs no tool",
                    Mechanics.whyCannotMine(empty, Blocks.defaultState("dirt")) == null);
        });

        TestRunner.test("item descriptions combine every component", () -> {
            String sword = Mechanics.describeItem("diamond_sword");
            TestRunner.contains("sword damage", sword, "7");
            TestRunner.contains("sword speed", sword, "1.6");
            String apple = Mechanics.describeItem("apple");
            TestRunner.contains("apple food", apple, "4");
            String chest = Mechanics.describeItem("iron_chestplate");
            TestRunner.contains("chestplate defence", chest, "6");
        });

        TestRunner.test("loadout line names the weapon and armour", () -> {
            String line = Mechanics.loadout(new Inventory());
            TestRunner.contains("weapon", line, "fist");
            TestRunner.contains("armour", line, "head=none");
        });

        TestRunner.test("summary counts every table", () -> {
            String summary = Mechanics.summary();
            TestRunner.contains("blocks", summary, "states");
            TestRunner.contains("loot", summary, "loot tables");
        });
    }
}
