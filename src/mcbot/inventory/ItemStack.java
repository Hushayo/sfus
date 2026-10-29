package mcbot.inventory;

import mcbot.net.PacketReader;
import mcbot.registry.Items;

/**
 * One item stack, as the protocol sends it.
 *
 * Wire format for 1.20.5+ (protocol 777):
 * <pre>
 *   VarInt count            (0 = empty stack, nothing else follows)
 *   VarInt itemId
 *   VarInt addedComponents   { VarInt typeId, payload } ...
 *   VarInt removedComponents { VarInt typeId } ...
 * </pre>
 *
 * The component payload is type-specific, so skipping it needs a codec table
 * for every component type. This class deliberately does not try: it reads the
 * count and id, and if the component patch is empty (the common case for
 * blocks, food, ingots and undamaged tools) the stack parses exactly. If a
 * patch is present, {@link #readOptional} reports it via
 * {@link ItemStack#componentsPresent} and the caller can still use the count
 * and id, but the byte position is no longer trustworthy.
 *
 * That is why {@link Inventory} validates its parse against the packet length
 * instead of assuming success: a window with an enchanted item in it degrades
 * to "contents unknown" rather than to silently wrong slot contents.
 */
public final class ItemStack {
    public static final ItemStack EMPTY = new ItemStack(0, 0, -1, false);

    public final int itemId;
    public final int count;
    /** Damage value if the packet carried one, else -1 (unknown). */
    public final int damage;
    /** True when the packet had a component patch this parser did not consume. */
    public final boolean componentsPresent;

    public ItemStack(int itemId, int count, int damage, boolean componentsPresent) {
        this.itemId = itemId;
        this.count = count;
        this.damage = damage;
        this.componentsPresent = componentsPresent;
    }

    public boolean isEmpty() { return count <= 0 || itemId == 0; }

    public Items.Item item() { return Items.get(itemId); }

    public String name() { return Items.name(itemId); }

    public int maxStack() { return item().maxStack; }

    /** Remaining durability, or -1 when the item does not take damage. */
    public int remainingDurability() {
        int max = item().maxDamage;
        if (max <= 0) return -1;
        if (damage < 0) return max; // unknown damage: assume pristine
        return Math.max(0, max - damage);
    }

    public ItemStack withCount(int newCount) {
        return new ItemStack(itemId, newCount, damage, componentsPresent);
    }

    /**
     * Reads one optional stack. Returns {@link #EMPTY} for count 0.
     *
     * @throws RuntimeException when the bytes are not a stack at all, which is
     *         how a mis-identified packet shows up.
     */
    public static ItemStack readOptional(PacketReader pr) {
        int count = pr.readVarInt();
        if (count <= 0) return EMPTY;
        int id = pr.readVarInt();
        // DataComponentPatch is two counts first, then the entries. Reading
        // the added count and jumping straight into payloads (the old order)
        // treats the removed count as a component type id, which is exactly
        // how a damaged pickaxe turned the rest of the window into garbage.
        int added = pr.readVarInt();
        int removed = pr.readVarInt();
        int damage = -1;
        boolean opaque = false;
        for (int i = 0; i < added; i++) {
            int type = pr.readVarInt();
            if (!mcbot.registry.Components.skip(pr, type)) {
                // Unknown payload size: stop here rather than guess and lose
                // every following slot in the window.
                opaque = true;
                break;
            }
        }
        if (!opaque) {
            for (int i = 0; i < removed; i++) pr.readVarInt();
        }
        return new ItemStack(id, count, damage, opaque);
    }

    /**
     * Reads a stack from a packet whose remaining bytes belong to that stack
     * only (container_set_slot, set_cursor_item). Any component payload is
     * skipped wholesale by consuming the rest of the packet.
     */
    public static ItemStack readSingle(PacketReader pr) {
        int count = pr.readVarInt();
        if (count <= 0) return EMPTY;
        int id = pr.readVarInt();
        int damage = -1;
        boolean opaque = false;
        int added = pr.readVarInt();
        int removed = pr.readVarInt();
        if (added > 0) {
            opaque = true;
            pr.skip(pr.remaining()); // rest of packet is the patch
        } else {
            for (int i = 0; i < removed; i++) pr.readVarInt();
        }
        return new ItemStack(id, count, damage, opaque);
    }

    /** Writes an empty stack (count 0). Used for click packets. */
    public static void writeEmpty(java.io.OutputStream out) throws java.io.IOException {
        mcbot.net.PacketWriter.varInt(out, 0);
    }

    @Override
    public String toString() {
        if (isEmpty()) return "empty";
        return count + "x " + name() + (componentsPresent ? " (+components)" : "");
    }
}
