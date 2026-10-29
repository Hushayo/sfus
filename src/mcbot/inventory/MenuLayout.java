package mcbot.inventory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Slot layouts for the menus a bot actually opens.
 *
 * The server tells us the menu *type id* in open_screen, but not how many slots
 * it has or where the player inventory starts. Every container packet refers to
 * slots by index into that layout, so without this table a click either lands
 * on the wrong slot or is rejected as out of range.
 *
 * Vanilla layout convention, which every menu follows:
 *
 * <pre>
 *   [ container slots ][ player main inventory: 27 ][ hotbar: 9 ]
 * </pre>
 *
 * The player's own inventory (window 0) is the exception: it has the 2x2
 * crafting grid and armour at the front.
 */
public final class MenuLayout {
    public final String name;
    /** Slots owned by the container itself, before the player inventory. */
    public final int containerSlots;
    /** First slot of the 27-slot main inventory. */
    public final int mainStart;
    /** First slot of the 9-slot hotbar. */
    public final int hotbarStart;
    /** Total slots in the menu. */
    public final int totalSlots;
    /** True for the player's own inventory (window 0), which has no container. */
    public final boolean playerInventory;

    MenuLayout(String name, int containerSlots, int mainStart, int hotbarStart,
               int totalSlots, boolean playerInventory) {
        this.name = name;
        this.containerSlots = containerSlots;
        this.mainStart = mainStart;
        this.hotbarStart = hotbarStart;
        this.totalSlots = totalSlots;
        this.playerInventory = playerInventory;
    }

    public int hotbarSlot(int index) {
        return hotbarStart + index;
    }

    public boolean isContainerSlot(int slot) {
        return slot >= 0 && slot < containerSlots;
    }

    public boolean isPlayerSlot(int slot) {
        return slot >= mainStart && slot < hotbarStart + 9;
    }

    // ------------------------------------------------------------- registry --

    private static final Map<String, MenuLayout> BY_NAME = new HashMap<>();
    private static final Map<Integer, String> NAME_BY_ID = new HashMap<>();

    /** The player's own inventory: 0 result, 1-4 craft, 5-8 armour, 9-35 main, 36-44 hotbar, 45 offhand. */
    public static final MenuLayout PLAYER = new MenuLayout(
            "player", 9, 9, 36, 46, true);

    static {
        loadNames();
        BY_NAME.put("player", PLAYER);
        put("generic_9x1", 9);
        put("generic_9x2", 18);
        put("generic_9x3", 27);
        put("generic_9x4", 36);
        put("generic_9x5", 45);
        put("generic_9x6", 54);
        put("generic_3x3", 9);      // dispenser / dropper
        put("crafter_3x3", 9);
        put("crafting", 10);        // 1 result + 3x3 grid
        put("furnace", 3);          // input, fuel, output
        put("blast_furnace", 3);
        put("smoker", 3);
        put("anvil", 3);
        put("enchantment", 2);
        put("brewing_stand", 5);
        put("beacon", 1);
        put("hopper", 5);
        put("shulker_box", 27);
        put("grindstone", 3);
        put("stonecutter", 2);
        put("loom", 4);
        put("smithing", 4);
        put("cartography_table", 3);
        put("merchant", 3);
        put("lectern", 1);
        put("beacon", 1);
    }

    private static void put(String name, int containerSlots) {
        BY_NAME.put(name, new MenuLayout(name, containerSlots,
                containerSlots, containerSlots + 27, containerSlots + 36, false));
    }

    private static void loadNames() {
        try (InputStream in = MenuLayout.class.getResourceAsStream("/mcbot/data/menus.tsv")) {
            if (in == null) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                if (f.length < 2) continue;
                NAME_BY_ID.put(Integer.parseInt(f[0]), f[1]);
            }
        } catch (Exception e) {
            System.out.println("[menu] names load failed: " + e);
        }
    }

    /** Layout by menu name ("furnace", "generic_9x6"), or null. */
    public static MenuLayout byName(String name) {
        return BY_NAME.get(name);
    }

    public static String nameOf(int menuTypeId) {
        return NAME_BY_ID.getOrDefault(menuTypeId, "generic_9x3");
    }

    /** Layout for a menu type id; falls back to a 3-row chest, the most common. */
    public static MenuLayout forType(int menuTypeId) {
        String name = nameOf(menuTypeId);
        MenuLayout layout = BY_NAME.get(name);
        if (layout != null) return layout;
        // Unknown menus are almost always a variant chest. Guessing 27 container
        // slots is wrong for a barrel-sized menu but harmless: the player slots
        // are the ones the bot touches.
        System.out.println("[menu] unknown menu type " + menuTypeId + " (" + name + "), assuming 9x3");
        return BY_NAME.get("generic_9x3");
    }

    @Override
    public String toString() {
        return name + "[container=" + containerSlots + " main=" + mainStart
                + " hotbar=" + hotbarStart + " total=" + totalSlots + "]";
    }
}
