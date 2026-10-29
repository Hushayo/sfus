package mcbot.inventory;

import mcbot.net.PacketReader;
import mcbot.registry.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the bot is carrying, and what is inside whatever container is open.
 *
 * The important part is not the slot array, it is {@link #stateId}. Vanilla
 * validates every container_click against the state id it last sent; a click
 * with a stale id is rejected and the server resyncs the window. The previous
 * version of this bot sent state id 0 unconditionally, which is only correct
 * for the very first click after opening a window - every later click in a
 * sequence was silently dropped. Echoing the tracked id is what makes
 * "pick up stack, move it, place it" work at all.
 *
 * Window 0 is the player's own inventory and is always addressable, open or
 * not; containers get id 1..100 from open_screen.
 */
public final class Inventory {
    public static final int PLAYER_WINDOW = 0;

    // Click types, matching ServerboundContainerClickPacket.
    public static final int CLICK_PICKUP = 0;
    public static final int CLICK_QUICK_MOVE = 1;
    public static final int CLICK_SWAP = 2;
    public static final int CLICK_CLONE = 3;
    public static final int CLICK_THROW = 4;
    public static final int CLICK_QUICK_CRAFT = 5;
    public static final int CLICK_PICKUP_ALL = 6;

    private final ItemStack[] playerSlots = new ItemStack[46];
    private ItemStack[] containerSlots = new ItemStack[0];
    private ItemStack carried = ItemStack.EMPTY;

    private int windowId = PLAYER_WINDOW;
    private int menuType = -1;
    private MenuLayout layout = MenuLayout.PLAYER;
    private int stateId = 0;
    private int heldSlot = 0;
    private boolean synced = false;
    /** Set when a packet carried components we could not skip. */
    private boolean contentsOpaque = false;

    /**
     * The player menu's own state id, kept while a container window is in
     * front of it.
     *
     * The server runs one id counter per menu, and
     * {@code AbstractContainerMenu.transferState} copies the closed menu's
     * {@code remoteSlots} into the player menu without touching its id. So on
     * close the player menu keeps whatever id it had, and the value is only
     * approximately what we last saw. It is still far better than 0, and a
     * wrong guess is recoverable: see {@link #rejections}.
     */
    private int playerStateId = 0;

    /**
     * How the server has answered our clicks.
     *
     * An accepted click answers with {@code container_set_slot} for the slots
     * it changed; a click carrying a stale state id is rejected outright and
     * answered with a full {@code container_set_content}. Counting the two is
     * how a caller can tell "the click applied" from "the server resynced and
     * threw my click away" without guessing from the slot contents.
     */
    private volatile int slotUpdates = 0;
    private volatile int fullSyncs = 0;

    public Inventory() {
        java.util.Arrays.fill(playerSlots, ItemStack.EMPTY);
    }

    // -------------------------------------------------------------- window --

    public void open(int windowId, int menuType) {
        if (windowId != PLAYER_WINDOW && this.windowId == PLAYER_WINDOW) {
            // Remember where the player menu was, so the first click after the
            // container closes is not sent with a made-up id.
            playerStateId = this.stateId;
        }
        this.windowId = windowId;
        this.menuType = menuType;
        this.layout = windowId == PLAYER_WINDOW ? MenuLayout.PLAYER : MenuLayout.forType(menuType);
        this.containerSlots = new ItemStack[Math.max(0, layout.containerSlots)];
        java.util.Arrays.fill(containerSlots, ItemStack.EMPTY);
        this.carried = ItemStack.EMPTY;
        this.stateId = windowId == PLAYER_WINDOW ? playerStateId : 0;
        this.synced = false;
        this.contentsOpaque = false;
        System.out.println("[inv] window " + windowId + " opened, layout " + layout);
    }

    public void close(int windowId) {
        if (windowId != this.windowId) return;
        this.windowId = PLAYER_WINDOW;
        this.menuType = -1;
        this.layout = MenuLayout.PLAYER;
        // The player menu still owns slots 0..8 (result, 2x2 grid, armour).
        // Dropping them to a zero-length array here is what made every grid
        // slot ignore its set_slot update after a table was closed: put() saw
        // the slot as a container slot, found no array to write it into, and
        // discarded it - so the grid read back empty and the craft failed with
        // "slot has empty" even though the click had been accepted.
        this.containerSlots = new ItemStack[Math.max(0, layout.containerSlots)];
        java.util.Arrays.fill(containerSlots, ItemStack.EMPTY);
        this.carried = ItemStack.EMPTY;
        // The player menu has its own state id. Keeping the closed container's
        // id here makes the next click look stale to the server, which then
        // resyncs the window and drops the click.
        this.stateId = playerStateId;
        this.synced = true;
    }

    public int slotUpdates() { return slotUpdates; }
    public int fullSyncs() { return fullSyncs; }

    public int windowId() { return windowId; }
    public int menuType() { return menuType; }
    public MenuLayout layout() { return layout; }
    public int stateId() { return stateId; }
    public ItemStack carried() { return carried; }
    public boolean isOpen() { return windowId != PLAYER_WINDOW; }
    public boolean synced() { return synced; }
    public boolean contentsOpaque() { return contentsOpaque; }

    public void setHeldSlot(int slot) {
        if (slot >= 0 && slot < 9) heldSlot = slot;
    }

    public int heldSlot() { return heldSlot; }

    /** The stack in the selected hotbar slot. */
    public ItemStack held() {
        return playerSlots[36 + heldSlot];
    }

    /** The offhand stack (slot 45). */
    public ItemStack offhand() {
        return playerSlots[45];
    }

    /** Armour slots, top to bottom: helmet, chest, legs, boots. */
    public ItemStack armour(int i) {
        return (i >= 0 && i < 4) ? playerSlots[5 + i] : ItemStack.EMPTY;
    }

    // --------------------------------------------------------------- update --

    /**
     * Handles container_set_content.
     *
     * The stack list is length-prefixed but every element is a full stack with
     * a component patch, so a single unparseable element desyncs the rest. We
     * parse what we can, then check that the packet was consumed exactly; if it
     * was not, the window is marked opaque and keeps its previous contents
     * rather than showing a plausible but wrong inventory.
     */
    public void setContent(PacketReader pr, int windowId, int stateId) {
        if (windowId != this.windowId) return;
        int count = pr.readVarInt();
        if (count < 0 || count > 512) throw new IllegalArgumentException("bad slot count " + count);
        ItemStack[] parsed = new ItemStack[count];
        boolean opaque = false;
        for (int i = 0; i < count; i++) {
            int start = pr.pos();
            parsed[i] = ItemStack.readOptional(pr);
            if (mcbot.bot.MinecraftBot.DEBUG) {
                System.out.println("[inv] content slot " + i + " " + parsed[i]
                        + " bytes " + start + ".." + pr.pos());
            }
            if (parsed[i].componentsPresent) opaque = true;
        }
        int carriedStart = pr.pos();
        ItemStack carriedStack = ItemStack.readOptional(pr);
        if (mcbot.bot.MinecraftBot.DEBUG) {
            System.out.println("[inv] content carried " + carriedStack
                    + " bytes " + carriedStart + ".." + pr.pos());
        }
        if (carriedStack.componentsPresent) opaque = true;

        this.stateId = stateId;
        if (windowId == PLAYER_WINDOW) this.playerStateId = stateId;
        this.synced = true;
        this.contentsOpaque = opaque;
        this.carried = carriedStack;
        this.fullSyncs++;

        if (windowId == PLAYER_WINDOW) {
            applyPlayerContent(parsed);
        } else {
            applyContainerContent(parsed);
        }
        if (opaque) {
            System.out.println("[inv] window " + windowId + " has item components; "
                    + "slot contents may be incomplete");
        }
    }

    /**
     * In the player's own menu, slots 0..8 are the result slot, the 2x2 grid
     * and the armour. Everything else (9..45) is the plain inventory.
     *
     * They are split across two arrays: 0..4 live in {@code containerSlots}
     * because that is what {@link #get} / {@link #put} treat as "before the
     * player inventory", while the armour slots (5..8) are addressed by
     * {@link #armour} and by set_player_inventory in {@code playerSlots}.
     * {@link #applyPlayerContent} has to use the same split; writing the grid
     * into {@code playerSlots} made every grid slot read back empty after a
     * full window resync, which looked like "the click did nothing".
     */
    private static boolean isPlayerArmourSlot(MenuLayout layout, int slot) {
        return layout.playerInventory && slot >= 5 && slot <= 8;
    }

    private void applyPlayerContent(ItemStack[] parsed) {
        // The player inventory window sends 46 slots in the menu's own order:
        // 0 result, 1-4 crafting, 5-8 armour, 9-35 main, 36-44 hotbar, 45 offhand.
        for (int i = 0; i < playerSlots.length; i++) {
            ItemStack stack = i < parsed.length ? parsed[i] : ItemStack.EMPTY;
            if (i < containerSlots.length && !isPlayerArmourSlot(layout, i)) {
                containerSlots[i] = stack;
            } else {
                playerSlots[i] = stack;
            }
        }
    }

    private void applyContainerContent(ItemStack[] parsed) {
        int containerCount = layout.containerSlots;
        for (int i = 0; i < containerSlots.length && i < parsed.length; i++) {
            containerSlots[i] = parsed[i];
        }
        int mainStart = layout.mainStart;
        for (int i = 0; i < 27; i++) {
            int slot = mainStart + i;
            playerSlots[9 + i] = slot < parsed.length ? parsed[slot] : ItemStack.EMPTY;
        }
        for (int i = 0; i < 9; i++) {
            int slot = layout.hotbarStart + i;
            playerSlots[36 + i] = slot < parsed.length ? parsed[slot] : ItemStack.EMPTY;
        }
        if (containerCount == 0) return;
    }

    /** Handles container_set_slot. */
    public void setSlot(PacketReader pr, int windowId, int stateId, int slot) {
        ItemStack stack = ItemStack.readSingle(pr);
        if (windowId != this.windowId) return;
        this.stateId = stateId;
        if (windowId == PLAYER_WINDOW) this.playerStateId = stateId;
        this.synced = true;
        this.slotUpdates++;
        put(slot, stack);
    }

    /** Handles set_cursor_item. */
    public void setCarried(ItemStack stack) {
        this.carried = stack;
    }

    /**
     * Handles set_player_inventory, whose slot numbering is the *player's* own:
     * 0..8 hotbar, 9..35 main, 36..39 armour (boots first), 40 offhand.
     * This is a different numbering from the menu slots used everywhere else,
     * which is exactly the kind of thing that silently puts a pickaxe in the
     * helmet slot.
     */
    public void setPlayerSlot(int wireSlot, ItemStack stack) {
        if (wireSlot >= 0 && wireSlot <= 8) {
            playerSlots[36 + wireSlot] = stack;
        } else if (wireSlot >= 9 && wireSlot <= 35) {
            playerSlots[wireSlot] = stack;
        } else if (wireSlot >= 36 && wireSlot <= 39) {
            playerSlots[5 + (39 - wireSlot)] = stack; // 39 = helmet .. 36 = boots
        } else if (wireSlot == 40) {
            playerSlots[45] = stack;
        }
    }

    private void put(int slot, ItemStack stack) {
        if (slot < 0) return;
        if (isPlayerArmourSlot(layout, slot)) {
            playerSlots[slot] = stack;
            return;
        }
        if (slot < layout.containerSlots) {
            if (slot < containerSlots.length) containerSlots[slot] = stack;
            return;
        }
        if (slot >= layout.mainStart && slot < layout.hotbarStart) {
            playerSlots[9 + (slot - layout.mainStart)] = stack;
            return;
        }
        if (slot >= layout.hotbarStart && slot < layout.hotbarStart + 9) {
            playerSlots[36 + (slot - layout.hotbarStart)] = stack;
            return;
        }
        if (slot < playerSlots.length) playerSlots[slot] = stack;
    }

    // ------------------------------------------------------------- queries --

    public ItemStack get(int slot) {
        if (slot < 0) return ItemStack.EMPTY;
        if (isPlayerArmourSlot(layout, slot)) {
            return slot < playerSlots.length ? playerSlots[slot] : ItemStack.EMPTY;
        }
        if (slot < layout.containerSlots) {
            return slot < containerSlots.length ? containerSlots[slot] : ItemStack.EMPTY;
        }
        if (slot >= layout.mainStart && slot < layout.hotbarStart) {
            return playerSlots[9 + (slot - layout.mainStart)];
        }
        if (slot >= layout.hotbarStart && slot < layout.hotbarStart + 9) {
            return playerSlots[36 + (slot - layout.hotbarStart)];
        }
        return slot < playerSlots.length ? playerSlots[slot] : ItemStack.EMPTY;
    }

    /** Total number of an item across the player inventory. */
    public int count(int itemId) {
        int n = 0;
        for (ItemStack s : playerSlots) {
            if (!s.isEmpty() && s.itemId == itemId) n += s.count;
        }
        return n;
    }

    public int count(String itemName) {
        return count(Items.id(itemName));
    }

    public boolean has(String itemName) {
        return count(itemName) > 0;
    }

    public boolean has(String itemName, int n) {
        return count(itemName) >= n;
    }

    /**
     * Player-inventory slot indices holding an item (main inventory + hotbar).
     *
     * <p>Armour slots are excluded on purpose: a caller that wants to move,
     * drop or deposit an item means the loose one in the bag, not the piece
     * already being worn. Including slot 5 made {@code equip} shift-click the
     * helmet it was trying to put on.
     */
    public List<Integer> findSlots(String itemName) {
        int id = Items.id(itemName);
        List<Integer> out = new ArrayList<>();
        for (int i = 9; i <= 44; i++) {
            if (!playerSlots[i].isEmpty() && playerSlots[i].itemId == id) {
                // Convert back to menu slot indices so a click can use them.
                out.add(menuSlotOf(i));
            }
        }
        return out;
    }

    /** First empty main-inventory or hotbar slot as a menu slot index, or -1. */
    public int firstEmptySlot() {
        for (int i = 9; i < 45; i++) {
            if (playerSlots[i].isEmpty()) return menuSlotOf(i);
        }
        return -1;
    }

    /** Converts an internal player slot (9..44) to the current menu's slot index. */
    public int menuSlotOf(int playerSlot) {
        if (playerSlot >= 36 && playerSlot <= 44) {
            return layout.hotbarStart + (playerSlot - 36);
        }
        if (playerSlot >= 9 && playerSlot <= 35) {
            return layout.mainStart + (playerSlot - 9);
        }
        return playerSlot;
    }

    /** True when there is room for at least one more of this item. */
    public boolean hasSpaceFor(String itemName) {
        int id = Items.id(itemName);
        int max = Items.get(id).maxStack;
        for (int i = 9; i < 45; i++) {
            ItemStack s = playerSlots[i];
            if (s.isEmpty()) return true;
            if (s.itemId == id && s.count < max) return true;
        }
        return false;
    }

    /** Free space counted in items, used by the gather loop. */
    public int freeSpace() {
        int free = 0;
        for (int i = 9; i < 45; i++) {
            ItemStack s = playerSlots[i];
            free += s.isEmpty() ? 64 : Math.max(0, s.maxStack() - s.count);
        }
        return free;
    }

    /**
     * Everything the bot is carrying, aggregated by item name, biggest stack
     * first.
     *
     * This is built from the actual inventory rather than from a list of names
     * someone thought of in advance. The old summary was a hard-coded array,
     * so a diamond pickaxe simply did not exist as far as the report was
     * concerned - the bot said "1 iron pickaxe, 1 wooden pickaxe" while holding
     * a diamond one, which reads as "it is mining with the wrong tool".
     */
    public List<Map.Entry<String, Integer>> contents() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 9; i < 45; i++) {
            ItemStack s = playerSlots[i];
            if (s.isEmpty()) continue;
            counts.merge(s.name(), s.count, Integer::sum);
        }
        List<Map.Entry<String, Integer>> out = new ArrayList<>(counts.entrySet());
        out.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return out;
    }

    /** Item ids in the hotbar, in slot order. */
    public List<Items.Item> hotbarItems() {
        List<Items.Item> out = new ArrayList<>();
        for (int i = 36; i <= 44; i++) {
            if (!playerSlots[i].isEmpty()) out.add(playerSlots[i].item());
        }
        return out;
    }

    /**
     * Player slot (9..44) of the fastest tool for a block, or -1.
     *
     * The whole inventory is searched, not just the hotbar. Looking only at
     * slots 36..44 meant an iron pickaxe one row up was invisible, and the bot
     * happily mined stone with the stone pickaxe it happened to be holding.
     * Callers that need the item in hand swap it into the hotbar first.
     */
    public int bestToolPlayerSlot(int blockState) {
        String need = mcbot.registry.Blocks.toolClass(blockState);
        int best = -1;
        double bestTime = Double.MAX_VALUE;
        for (int i = 9; i <= 44; i++) {
            ItemStack s = playerSlots[i];
            if (s.isEmpty() || !s.item().isTool()) continue;
            // Restrict to the tool class the block actually wants: a shovel is
            // not faster than a hand on stone (different tool class), and a piece
            // of food is never the right tool even though the server treats both
            // as one-second-each.
            if (need != null && !need.equals(s.item().toolClass())) continue;
            double t = Items.breakSeconds(s.item(), blockState, false, true);
            if (t < bestTime) {
                bestTime = t;
                best = i;
            }
        }
        if (best >= 0) return best;
        // Nothing of the right class: fall back to any tool of correct ... or hand.
        /* expand to any tool only when the block did not ask for one */
        if (need == null) {
            for (int i = 9; i <= 44; i++) {
                ItemStack s = playerSlots[i];
                if (s.isEmpty() || !s.item().isTool()) continue;
                double t = Items.breakSeconds(s.item(), blockState, false, true);
                if (t < bestTime) {
                    bestTime = t;
                    best = i;
                }
            }
        }
        return best;
    }

    /** Best hotbar index (0..8) for a block, or -1 when the best tool is not in it. */
    public int bestToolSlot(int blockState) {
        int playerSlot = bestToolPlayerSlot(blockState);
        return playerSlot >= 36 ? playerSlot - 36 : -1;
    }

    /**
     * True when the inventory holds a tool of the right class and tier for the
     * block, so mining it will actually yield drops.
     *
     * Worth checking before walking anywhere: mining stone with an axe takes
     * seven seconds and drops nothing, which reads as "the bot is broken"
     * rather than "the bot has the wrong tool".
     */
    public boolean hasToolFor(int blockState) {
        String need = mcbot.registry.Blocks.toolClass(blockState);
        if (need == null) return true;                 // hand-mineable
        String tierName = mcbot.registry.Blocks.requiredTier(blockState);
        int tier = mcbot.registry.Blocks.tierRank(tierName);

        // Only stone-like blocks actually *require* a tool for drops. Dirt,
        // sand, gravel and wood drop by hand, just slower, so refusing to mine
        // them without the matching tool would be wrong.
        boolean requiresTool = "pickaxe".equals(need) || !"any".equals(tierName);
        if (!requiresTool) return true;

        for (ItemStack s : playerSlots) {
            if (s.isEmpty() || !s.item().isTool()) continue;
            if (!need.equals(s.item().toolClass())) continue;
            if (mcbot.registry.Blocks.tierRank(s.item().toolTier) >= tier) return true;
        }
        return false;
    }

    /** The tool class the inventory is missing for a block, for messages. */
    public String missingToolFor(int blockState) {
        String need = mcbot.registry.Blocks.toolClass(blockState);
        return need == null ? "nothing" : need;
    }

    /** Human-readable dump of the non-empty slots, for chat and the AI. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (isOpen()) {
            sb.append("window ").append(windowId).append(" (")
                    .append(layout.name).append(") ");
            int shown = 0;
            for (int i = 0; i < containerSlots.length; i++) {
                if (containerSlots[i].isEmpty()) continue;
                if (shown++ == 0) sb.append("contains ");
                sb.append('[').append(i).append("] ").append(containerSlots[i]).append(' ');
                if (shown >= 8) { sb.append("..."); break; }
            }
            if (shown == 0) sb.append("empty");
            sb.append("; ");
        }
        sb.append("holding ").append(held());
        sb.append(", ").append(count(Items.id("torch"))).append(" torches");
        return sb.toString();
    }
}
