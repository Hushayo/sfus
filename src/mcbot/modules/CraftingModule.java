package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.inventory.Inventory;
import mcbot.inventory.ItemStack;
import mcbot.registry.Blocks;
import mcbot.world.BlockScanner;

import java.util.List;
import java.util.Set;

/**
 * Workstations: find a crafting table or a furnace, walk to it, open it.
 *
 * This is a helper module, not a command surface. The bot decides when to use
 * it (from a craft or smelt job); players ask for outcomes ("craft me a
 * pickaxe"), never for "use 120 64 -30".
 *
 * The previous version of this class also parsed chat commands (`craft x y z`,
 * `use x y z`, `click <slot>`, `container`, `close`) and exposed raw slot
 * clicking to anyone in chat. Those are gone on purpose: they were a way to
 * make the bot act without knowing what it was doing, and they bypassed the
 * inventory model that makes the same actions verifiable.
 */
public class CraftingModule implements BotModule {
    private static final int SEARCH_RADIUS = 32;
    /** How close to a point a station has to be to count as "the one right there". */
    private static final int NEAR_RADIUS = 10;

    private static final Set<String> TABLES = Set.of("crafting_table");
    private static final Set<String> FURNACES = Set.of("furnace", "blast_furnace", "smoker");

    private volatile MinecraftBot bot;
    /** Last table we placed or opened, so a follow-up job does not depend on a rescan. */
    private volatile int[] knownTable;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    /** Finds the nearest crafting table and opens it. False when there is none. */
    public boolean openTable() {
        MinecraftBot b = bot;
        if (b == null) return false;
        int[] known = knownTable;
        if (known != null && b.hasPlayerPosition
                && b.interaction.inReach(known[0], known[1], known[2])) {
            b.interaction.useBlock(known[0], known[1], known[2]);
            if (waitForOpen(1500L)) return true;
            // It may have been broken; fall through to a fresh scan.
            knownTable = null;
        }
        return openNearest(TABLES, "crafting table");
    }

    /** Finds the nearest furnace, blast furnace or smoker and opens it. */
    public boolean openFurnace() {
        return openNearest(FURNACES, "furnace");
    }

    /**
     * Opens a furnace close to a point, falling back to the nearest one anywhere.
     *
     * A player asking for a smelt means the furnace at their own feet, not
     * whichever one a 32-block scan ranks first: that scan happily picks a
     * furnace five blocks underground in a cave, and the bot then pathfinds
     * across the base while the player watches it walk away from the one they
     * are standing next to.
     */
    public boolean openFurnaceNear(double[] near) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return false;
        if (near != null) {
            BlockScanner.Hit hit = findNearest(FURNACES, near[0], near[1], near[2], NEAR_RADIUS);
            if (hit != null) return open(hit, FURNACES, "furnace");
        }
        return openNearest(FURNACES, "furnace");
    }

    /**
     * The player's own 2x2 grid needs no block: window 0 is always addressable.
     * Kept as a method so callers read the same either way.
     */
    public void openInventory() {
        MinecraftBot b = bot;
        if (b == null) return;
        if (b.interaction.inventory.isOpen()) {
            b.interaction.closeWindow();
            // The close packet and the next click are two server-bound
            // packets; clicking window 0 before the server has processed the
            // close gets the click rejected as a stale container.
            sleep(250);
        }
    }

    private boolean openNearest(Set<String> names, String label) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return false;

        BlockScanner.Hit hit = findNearest(names);
        if (hit == null) {
            System.out.println("[station] no " + label + " within " + SEARCH_RADIUS + " blocks");
            return false;
        }
        return open(hit, names, label);
    }

    /** Walks to a station if needed, opens it, and waits for the window. */
    private boolean open(BlockScanner.Hit hit, Set<String> names, String label) {
        MinecraftBot b = bot;
        if (b == null) return false;
        if (!b.interaction.inReach(hit.x, hit.y, hit.z)) {
            System.out.println("[station] walking to " + label + " at "
                    + hit.x + " " + hit.y + " " + hit.z);
            approach(b, hit.x, hit.y, hit.z);
        }
        if (!b.interaction.inReach(hit.x, hit.y, hit.z)) {
            System.out.println("[station] could not get close enough to the " + label
                    + " at " + hit.x + " " + hit.y + " " + hit.z);
            return false;
        }
        b.interaction.useBlock(hit.x, hit.y, hit.z);
        // The window id arrives in open_screen, a few ticks later.
        if (waitForOpen(3000L)) {
            if (names == TABLES) knownTable = new int[]{hit.x, hit.y, hit.z};
            return true;
        }
        return false;
    }

    /**
     * Walks until the block is in reach.
     *
     * A single {@code goTo} to the block's own cell is not enough: the cell is
     * occupied by the block, so the path stops next to it, and "next to it" can
     * still be more than the interaction range away when the workstation sits
     * against a wall or on a ledge. Each side is tried in turn, at the block's
     * level and one below, which is where a player would stand.
     *
     * Every leg is judged by the condition that actually matters - being within
     * interaction range - rather than by "movement finished". A multi-leg path
     * briefly reports not-moving between legs, which made the old check give up
     * while the bot was still one step from the furnace.
     */
    private void approach(MinecraftBot b, int x, int y, int z) {
        if (walkUntilInReach(b, x, y, z, x, y, z, 20_000L)) return;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : sides) {
            for (int dy = 0; dy >= -1; dy--) {
                if (walkUntilInReach(b, x, y, z, x + d[0], y + dy, z + d[1], 10_000L)) return;
            }
        }
    }

    /** Walks to (gx,gy,gz) and returns true once the target block is in reach. */
    private boolean walkUntilInReach(MinecraftBot b, int x, int y, int z,
                                     int gx, int gy, int gz, long timeoutMs) {
        if (b.interaction.inReach(x, y, z)) return true;
        b.movement.goToTight(gx, gy, gz);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (b.interaction.inReach(x, y, z)) return true;
            sleep(100);
        }
        return b.interaction.inReach(x, y, z);
    }

    /**
     * Waits for a container window to open and for its first content packet.
     *
     * A right click on a block is answered by {@code open_screen} a few ticks
     * later, so the click returning is not the same as the window being there.
     */
    private boolean waitForOpen(long timeoutMs) {
        MinecraftBot b = bot;
        if (b == null) return false;
        Inventory inv = b.interaction.inventory;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (inv.isOpen()) return true;
            sleep(50);
        }
        return inv.isOpen();
    }

    /** Nearest block whose name is one of the given names. */
    public BlockScanner.Hit findNearest(Set<String> names) {
        MinecraftBot b = bot;
        if (b == null) return null;
        double px, py, pz;
        synchronized (b.positionLock) {
            px = b.playerX;
            py = b.playerY;
            pz = b.playerZ;
        }
        return findNearest(names, px, py, pz, SEARCH_RADIUS);
    }

    /** Nearest such block to an arbitrary point, searched within a radius. */
    public BlockScanner.Hit findNearest(Set<String> names, double px, double py, double pz, int radius) {
        MinecraftBot b = bot;
        if (b == null) return null;
        List<BlockScanner.Hit> hits = BlockScanner.find(b.world,
                state -> {
                    String n = Blocks.shortName(state);
                    return n != null && names.contains(n);
                },
                px, py, pz, radius, 1);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** Waits until a usable window is open and its contents have arrived. */
    public boolean waitForWindow(long timeoutMs) {
        MinecraftBot b = bot;
        if (b == null) return false;
        Inventory inv = b.interaction.inventory;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!inv.isOpen()) return true;          // player 2x2 grid
            if (inv.synced()) return true;
            sleep(50);
        }
        return inv.isOpen() && inv.synced();
    }

    // ------------------------------------------------------------ placement --

    /**
     * Puts a block from the inventory on the ground next to the bot.
     *
     * Used to place a crafting table the bot just made, which is the difference
     * between "craft me a pickaxe" working in a forest and only working in a
     * base that already has a table.
     *
     * Tries the four horizontal neighbours at feet level: the target cell must
     * be free and the cell below it solid, then the block is placed against the
     * top face of that solid cell (face 1 = up).
     */
    public boolean placeNearby(String itemName) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return false;
        if (!holdItem(itemName)) {
            System.out.println("[station] no " + itemName + " to place");
            return false;
        }
        double px, py, pz;
        synchronized (b.positionLock) {
            px = b.playerX;
            py = b.playerY;
            pz = b.playerZ;
        }
        int bx = (int) Math.floor(px);
        int by = (int) Math.floor(py);
        int bz = (int) Math.floor(pz);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int tx = bx + d[0];
            int tz = bz + d[1];
            if (!b.world.isBlockLoaded(tx, tz)) continue;
            if (!Blocks.passable(b.world.getBlock(tx, by, tz))) continue;
            if (!Blocks.solid(b.world.getBlock(tx, by - 1, tz))) continue;
            b.interaction.placeBlock(tx, by - 1, tz, 1);
            // The block only shows up in our own cache once the server's
            // block_update arrives; poll instead of guessing a fixed delay.
            String placed = null;
            long deadline = System.currentTimeMillis() + 1500L;
            while (System.currentTimeMillis() < deadline) {
                sleep(80);
                placed = Blocks.shortName(b.world.getBlock(tx, by, tz));
                if (placed != null && placed.equals(itemName)) break;
            }
            if (placed != null && placed.equals(itemName)) {
                System.out.println("[station] placed " + itemName + " at " + tx + " " + by + " " + tz);
                if (TABLES.contains(itemName)) knownTable = new int[]{tx, by, tz};
                return true;
            }
        }
        System.out.println("[station] no free spot to place " + itemName);
        return false;
    }

    /** Puts an item in the main hand, moving it to the hotbar if necessary. */
    public boolean holdItem(String itemName) {
        MinecraftBot b = bot;
        if (b == null) return false;
        Inventory inv = b.interaction.inventory;
        int id = mcbot.registry.Items.id(itemName);
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.get(inv.menuSlotOf(36 + i));
            if (s.itemId == id && !s.isEmpty()) {
                if (inv.heldSlot() != i) b.interaction.selectSlot(i);
                sleep(120);
                return true;
            }
        }
        for (int slot = 9; slot < 45; slot++) {
            int menu = inv.menuSlotOf(slot);
            ItemStack s = inv.get(menu);
            if (s.isEmpty() || s.itemId != id) continue;
            b.interaction.swapWithHotbar(menu, inv.heldSlot());
            sleep(200);
            return true;
        }
        return false;
    }

    /** Short status line for chat. */
    public String status() {
        MinecraftBot b = bot;
        if (b == null) return "not connected";
        return b.interaction.status();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
