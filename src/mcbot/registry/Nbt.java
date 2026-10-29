package mcbot.registry;

import mcbot.net.PacketReader;

/**
 * Skips one network-NBT tag without interpreting it.
 *
 * Item components are the reason this exists: {@code custom_data},
 * {@code block_entity_data} and friends carry arbitrary NBT, and a parser that
 * cannot skip it loses its position for every following slot in a container.
 * That is what made the bot report an empty inventory while holding items - the
 * first stack with custom data desynchronised the remaining 45.
 *
 * The wire format is the standard named-root NBT that
 * {@code NbtIo.readAnyTag} reads: a type byte, a UTF-8 name, then the payload.
 * Payloads are self-describing, so skipping is a matter of knowing the size of
 * each primitive and walking compounds/lists recursively.
 *
 * This is a skipper, not a reader: it does not allocate arrays or build a tree,
 * and it refuses lengths that cannot fit in the remaining packet instead of
 * trusting a corrupt count.
 */
public final class Nbt {
    private static final int MAX_DEPTH = 64;

    private Nbt() {}

    /**
     * Skips one tag. Returns false when the bytes are not a plausible NBT tag,
     * so the caller can mark the stack opaque rather than read garbage.
     */
    public static boolean skip(PacketReader pr) {
        int before = pr.pos();
        try {
            int type = pr.readUnsignedByte();
            if (type == 0) return true;               // TAG_End, no name/payload
            skipString(pr);                            // root name
            skipPayload(pr, type, 0);
            return true;
        } catch (RuntimeException e) {
            pr.pos = before;
            return false;
        }
    }

    private static void skipPayload(PacketReader pr, int type, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("NBT too deep");
        switch (type) {
            case 0:                                    // TAG_End
                return;
            case 1:                                    // TAG_Byte
                pr.skip(1);
                return;
            case 2:                                    // TAG_Short
                pr.skip(2);
                return;
            case 3:                                    // TAG_Int
                pr.skip(4);
                return;
            case 4:                                    // TAG_Long
                pr.skip(8);
                return;
            case 5:                                    // TAG_Float
                pr.skip(4);
                return;
            case 6:                                    // TAG_Double
                pr.skip(8);
                return;
            case 7: {                                  // TAG_Byte_Array
                int n = pr.readInt();
                skipChecked(pr, n, 1);
                return;
            }
            case 8:                                    // TAG_String
                skipString(pr);
                return;
            case 9: {                                  // TAG_List
                int elem = pr.readUnsignedByte();
                int n = pr.readInt();
                if (n < 0) throw new IllegalArgumentException("negative NBT list length");
                if (elem == 0) return;                 // empty list has no elements
                for (int i = 0; i < n; i++) skipPayload(pr, elem, depth + 1);
                return;
            }
            case 10: {                                 // TAG_Compound
                while (true) {
                    int child = pr.readUnsignedByte();
                    if (child == 0) return;
                    skipString(pr);
                    skipPayload(pr, child, depth + 1);
                }
            }
            case 11: {                                 // TAG_Int_Array
                int n = pr.readInt();
                skipChecked(pr, n, 4);
                return;
            }
            case 12: {                                 // TAG_Long_Array
                int n = pr.readInt();
                skipChecked(pr, n, 8);
                return;
            }
            default:
                throw new IllegalArgumentException("unknown NBT type " + type);
        }
    }

    private static void skipString(PacketReader pr) {
        int len = pr.readUnsignedShort();
        pr.skip(len);
    }

    private static void skipChecked(PacketReader pr, int count, int width) {
        if (count < 0) throw new IllegalArgumentException("negative NBT length");
        long bytes = (long) count * width;
        if (bytes > pr.remaining()) {
            throw new IllegalArgumentException("NBT length " + count + "x" + width
                    + " exceeds packet (" + pr.remaining() + " bytes left)");
        }
        pr.skip((int) bytes);
    }
}
