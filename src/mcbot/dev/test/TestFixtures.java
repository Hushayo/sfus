package mcbot.dev.test;

import mcbot.inventory.Inventory;
import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;
import mcbot.registry.Items;

import java.io.ByteArrayOutputStream;

/**
 * Builds inventory state the way the server does: a {@code container_set_content}
 * payload, parsed by the real parser.
 *
 * <p>Going through the wire format instead of poking fields means these tests
 * also cover the stack decoder and the slot-layout mapping - the two places
 * where "43 cobblestone" used to end up in the wrong slot.
 */
final class TestFixtures {
    private TestFixtures() {}

    /**
     * Packs items into consecutive main-inventory slots (9, 10, ...).
     * Each entry is {@code "item:count"}.
     */
    static Inventory packed(String... itemCounts) {
        Inventory inv = new Inventory();
        fillInto(inv, itemCounts);
        return inv;
    }

    /** Same as {@link #packed} but into an inventory the caller already owns. */
    static void fillInto(Inventory inv, String... itemCounts) {
        String[] slots = new String[46];
        int next = 9;
        for (String spec : itemCounts) {
            if (next >= 45) break;
            slots[next++] = spec;
        }
        apply(inv, slots);
    }

    /** Puts items at explicit slots. Each entry is {@code "slot:item:count"}. */
    static Inventory atSlots(String... entries) {
        Inventory inv = new Inventory();
        String[] slots = new String[46];
        for (String entry : entries) {
            String[] parts = entry.split(":");
            slots[Integer.parseInt(parts[0])] = parts[1] + ":" + parts[2];
        }
        apply(inv, slots);
        return inv;
    }

    private static void apply(Inventory inv, String[] slots) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            PacketWriter.varInt(body, slots.length);
            for (String spec : slots) {
                if (spec == null) {
                    writeStack(body, null, 0);
                } else {
                    String[] parts = spec.split(":");
                    writeStack(body, parts[0], Integer.parseInt(parts[1]));
                }
            }
            writeStack(body, null, 0);   // carried stack
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        inv.setContent(new PacketReader(body.toByteArray()), Inventory.PLAYER_WINDOW, 0);
    }

    private static void writeStack(ByteArrayOutputStream out, String item, int count) throws Exception {
        if (item == null || count <= 0) {
            PacketWriter.varInt(out, 0);
            return;
        }
        PacketWriter.varInt(out, count);
        PacketWriter.varInt(out, Items.id(item));
        PacketWriter.varInt(out, 0);   // no added components
        PacketWriter.varInt(out, 0);   // no removed components
    }
}
