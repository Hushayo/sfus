package mcbot.dev.test;

import mcbot.inventory.Inventory;
import mcbot.inventory.ItemStack;
import mcbot.inventory.MenuLayout;
import mcbot.registry.Blocks;
import mcbot.registry.Items;

/**
 * Inventory tests: parsing, slot mapping and the queries the rest of the bot
 * relies on.
 *
 * <p>The slot mapping is where the interesting bugs live. The player menu
 * numbers its slots 0-45 with the crafting grid at the front, the container
 * menus put the container first, and {@code set_player_inventory} uses a third
 * numbering. Getting any of them wrong puts a pickaxe in the helmet slot and
 * nothing complains.
 */
public final class InventoryTests {
    private InventoryTests() {}

    public static void run() {
        TestRunner.suite("inventory");

        TestRunner.test("content packets fill the right slots", () -> {
            Inventory inv = TestFixtures.packed("cobblestone:43", "stone_pickaxe:1");
            TestRunner.eq("cobblestone", 43, inv.count("cobblestone"));
            TestRunner.eq("pickaxe", 1, inv.count("stone_pickaxe"));
            TestRunner.eq("slot of cobblestone", 9, (int) inv.findSlots("cobblestone").get(0));
            TestRunner.eq("slot of pickaxe", 10, (int) inv.findSlots("stone_pickaxe").get(0));
        });

        TestRunner.test("hotbar and main inventory are distinct", () -> {
            Inventory inv = TestFixtures.atSlots("9:stone:5", "36:iron_pickaxe:1");
            TestRunner.eq("stone in main", 5, inv.count("stone"));
            TestRunner.eq("pickaxe in hotbar", 1, inv.count("iron_pickaxe"));
            TestRunner.eq("best tool slot", 0, inv.bestToolSlot(Blocks.defaultState("stone")));
            TestRunner.eq("player slot of tool", 36,
                    inv.bestToolPlayerSlot(Blocks.defaultState("stone")));
        });

        TestRunner.test("armour slots are addressed separately", () -> {
            Inventory inv = TestFixtures.atSlots("5:diamond_helmet:1", "8:iron_boots:1");
            TestRunner.eq("helmet", "diamond_helmet", inv.armour(0).name());
            TestRunner.eq("boots", "iron_boots", inv.armour(3).name());
            TestRunner.check("helmet is not in the main inventory",
                    inv.findSlots("diamond_helmet").isEmpty());
        });

        TestRunner.test("the held stack follows the selected slot", () -> {
            Inventory inv = TestFixtures.atSlots("36:stone_sword:1", "37:apple:3");
            inv.setHeldSlot(1);
            TestRunner.eq("held", "apple", inv.held().name());
            inv.setHeldSlot(0);
            TestRunner.eq("held", "stone_sword", inv.held().name());
            inv.setHeldSlot(99);
            TestRunner.eq("out of range is ignored", 0, inv.heldSlot());
        });

        TestRunner.test("tool queries respect class and tier", () -> {
            Inventory wooden = TestFixtures.packed("wooden_pickaxe:1");
            TestRunner.check("wooden pickaxe harvests stone",
                    wooden.hasToolFor(Blocks.defaultState("stone")));
            TestRunner.check("wooden pickaxe cannot harvest diamond ore",
                    !wooden.hasToolFor(Blocks.defaultState("diamond_ore")));
            Inventory iron = TestFixtures.packed("iron_pickaxe:1");
            TestRunner.check("iron pickaxe harvests diamond ore",
                    iron.hasToolFor(Blocks.defaultState("diamond_ore")));
        });

        TestRunner.test("a tool of the wrong class is never chosen", () -> {
            Inventory axes = TestFixtures.packed("diamond_axe:1");
            TestRunner.eq("no pickaxe for stone", -1,
                    axes.bestToolPlayerSlot(Blocks.defaultState("stone")));
            TestRunner.check("axe is still the right tool for wood",
                    axes.hasToolFor(Blocks.defaultState("oak_log")));
        });

        TestRunner.test("counts and space account for stacks", () -> {
            Inventory inv = TestFixtures.packed("cobblestone:64", "cobblestone:64");
            TestRunner.eq("total", 128, inv.count("cobblestone"));
            TestRunner.check("space for more", inv.hasSpaceFor("cobblestone"));
            TestRunner.atLeast("free space", 32 * 64, inv.freeSpace());
        });

        TestRunner.test("contents are aggregated by name", () -> {
            Inventory inv = TestFixtures.packed("cobblestone:10", "stick:4", "cobblestone:5");
            var contents = inv.contents();
            TestRunner.eq("distinct items", 2, contents.size());
            TestRunner.eq("largest first", "cobblestone", contents.get(0).getKey());
            TestRunner.eq("aggregated", 15, (int) contents.get(0).getValue());
        });

        TestRunner.test("set_player_inventory maps its own numbering", () -> {
            Inventory inv = new Inventory();
            inv.setPlayerSlot(0, new ItemStack(Items.id("apple"), 2, -1, false));
            TestRunner.eq("hotbar 0", "apple", inv.get(36).name());
            inv.setPlayerSlot(9, new ItemStack(Items.id("stone"), 1, -1, false));
            TestRunner.eq("main 9", "stone", inv.get(9).name());
            inv.setPlayerSlot(39, new ItemStack(Items.id("diamond_helmet"), 1, -1, false));
            TestRunner.eq("slot 39 is the helmet", "diamond_helmet", inv.armour(0).name());
            inv.setPlayerSlot(36, new ItemStack(Items.id("iron_boots"), 1, -1, false));
            TestRunner.eq("slot 36 is the boots", "iron_boots", inv.armour(3).name());
            inv.setPlayerSlot(40, new ItemStack(Items.id("shield"), 1, -1, false));
            TestRunner.eq("slot 40 is the offhand", "shield", inv.offhand().name());
        });

        TestRunner.test("state id is echoed, not invented", () -> {
            Inventory inv = TestFixtures.packed("stone:1");
            TestRunner.eq("state from content", 0, inv.stateId());
            // set_slot carries its own state id; the inventory must adopt it.
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            mcbot.net.PacketWriter.varInt(body, 0);   // count
            mcbot.net.PacketWriter.varInt(body, 7);   // item id
            mcbot.net.PacketWriter.varInt(body, 0);
            mcbot.net.PacketWriter.varInt(body, 0);
            inv.setSlot(new mcbot.net.PacketReader(body.toByteArray()), 0, 12, 10);
            TestRunner.eq("state updated", 12, inv.stateId());
        });

        TestRunner.test("menu layouts put the player inventory after the container", () -> {
            MenuLayout chest = MenuLayout.byName("generic_9x3");
            TestRunner.eq("container slots", 27, chest.containerSlots);
            TestRunner.eq("main start", 27, chest.mainStart);
            TestRunner.eq("hotbar start", 54, chest.hotbarStart);
            TestRunner.check("slot 5 is a container slot", chest.isContainerSlot(5));
            TestRunner.check("slot 30 is a player slot", chest.isPlayerSlot(30));
            MenuLayout player = MenuLayout.PLAYER;
            TestRunner.eq("player hotbar start", 36, player.hotbarStart);
            TestRunner.eq("player total", 46, player.totalSlots);
        });

        TestRunner.test("closing a container restores the player window", () -> {
            Inventory inv = TestFixtures.packed("stone:1");
            inv.open(3, 0);
            TestRunner.check("container open", inv.isOpen());
            inv.close(3);
            TestRunner.check("back to the player menu", !inv.isOpen());
            TestRunner.eq("player window", Inventory.PLAYER_WINDOW, inv.windowId());
        });

        TestRunner.test("a full inventory reports no space", () -> {
            String[] full = new String[36];
            for (int i = 0; i < full.length; i++) full[i] = "cobblestone:64";
            Inventory inv = TestFixtures.packed(full);
            TestRunner.eq("total", 36 * 64, inv.count("cobblestone"));
            TestRunner.check("no space for a new stack", !inv.hasSpaceFor("apple"));
        });

        TestRunner.test("description names the held item", () -> {
            Inventory inv = TestFixtures.atSlots("36:diamond_pickaxe:1");
            TestRunner.contains("held", inv.describe(), "diamond_pickaxe");
        });
    }
}
