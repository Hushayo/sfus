package mcbot.net;

/**
 * Decoder for {@code Vec3.LP_STREAM_CODEC}, the compact vector encoding used by
 * {@code set_entity_motion} and a few entity packets.
 *
 * Reading this as three shorts looks plausible and is completely wrong: the
 * real format packs three 15-bit components and a shared integer scale into a
 * 48-bit word, so the shorts are just fragments of that word. The symptom was
 * a knockback that launched the bot into the sky - the "velocity" was garbage
 * from the middle of the packed bits, and nothing bounds-checks a velocity.
 *
 * Layout, from {@code LpVec3.read}:
 *
 * <pre>
 *   b0 = unsigned byte; b0 == 0 means the zero vector
 *   b1 = unsigned byte
 *   u  = unsigned 32-bit int
 *   data  = (u &lt;&lt; 16) | (b1 &lt;&lt; 8) | b0        // 48 bits
 *   scale = b0 &amp; 3, plus (varInt &lt;&lt; 2) when (b0 &amp; 4) != 0
 *   x = unpack(data &gt;&gt;&gt;  3) * scale
 *   y = unpack(data &gt;&gt;&gt; 18) * scale
 *   z = unpack(data &gt;&gt;&gt; 33) * scale
 *
 *   unpack(v) = min(v &amp; 32767, 32766) * 2 / 32766 - 1
 * </pre>
 *
 * The result is in the packet's own units, which for motion is blocks per tick.
 */
public final class LpVec3Codec {
    private static final double MAX_QUANTIZED = 32766.0;
    private static final int COMPONENT_MASK = 32767;

    private LpVec3Codec() {}

    /** Reads a vector. Returns {0,0,0} for the zero vector. */
    public static double[] read(PacketReader pr) {
        int b0 = pr.readUnsignedByte();
        if (b0 == 0) return new double[]{0, 0, 0};
        int b1 = pr.readUnsignedByte();
        long u = Integer.toUnsignedLong(pr.readInt());
        long data = (u << 16) | ((long) b1 << 8) | b0;

        long scale = b0 & 3L;
        if ((b0 & 4) != 0) {
            scale |= (Integer.toUnsignedLong(pr.readVarInt())) << 2;
        }
        return new double[]{
                unpack(data >>> 3) * scale,
                unpack(data >>> 18) * scale,
                unpack(data >>> 33) * scale
        };
    }

    /** Skips a vector without allocating. */
    public static void skip(PacketReader pr) {
        int b0 = pr.readUnsignedByte();
        if (b0 == 0) return;
        pr.readUnsignedByte();
        pr.readInt();
        if ((b0 & 4) != 0) pr.readVarInt();
    }

    private static double unpack(long v) {
        return Math.min(v & COMPONENT_MASK, MAX_QUANTIZED) * 2.0 / MAX_QUANTIZED - 1.0;
    }
}
