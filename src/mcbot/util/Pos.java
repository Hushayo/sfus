package mcbot.util;

/**
 * Position encodings used on the wire and in the block cache.
 *
 * These are easy to get subtly wrong and impossible to notice when you do: a
 * mis-shifted BlockPos makes the server reject a break with "too far", and a
 * mis-shifted SectionPos silently writes block updates into the wrong column.
 * Both bugs existed in this codebase at the same time, in three different
 * copies of the packing code, so the packing lives here once.
 *
 * <h2>BlockPos.asLong() — protocol 777</h2>
 * <pre>
 *   bit 38..63  x  (26-bit signed)
 *   bit  0..11  y  (12-bit signed)
 *   bit 12..37  z  (26-bit signed)
 * </pre>
 * This is the 1.21.5+ layout. The pre-1.21.5 layout was {@code x<<38 | y<<26 | z}
 * and using it makes the server decode a different position, then reject the
 * action as out of range.
 *
 * <h2>SectionPos.asLong()</h2>
 * <pre>
 *   bit 42..63  x  (22-bit signed)
 *   bit 20..41  y  (22-bit signed, 20 used)
 *   bit  0..19  z  (20-bit signed)
 * </pre>
 * Note that y is the *middle* field and z is the low one. Swapping them is the
 * single most destructive decode bug possible in a block cache, because
 * section_blocks_update is how every block change reaches the client.
 *
 * <h2>ChunkPos.asLong()</h2>
 * <pre>
 *   bit 32..63  z
 *   bit  0..31  x
 * </pre>
 */
public final class Pos {
    private Pos() {}

    private static final int BLOCK_X_BITS = 26;
    private static final int BLOCK_Y_BITS = 12;
    private static final int BLOCK_Z_BITS = 26;
    private static final int BLOCK_Y_SHIFT = 0;
    private static final int BLOCK_Z_SHIFT = 12;
    private static final int BLOCK_X_SHIFT = 38;

    /** {@code BlockPos.asLong()} for protocol 777. */
    public static long blockLong(int x, int y, int z) {
        return (((long) (x & 0x3FFFFFF)) << BLOCK_X_SHIFT)
                | ((long) (y & 0xFFF) << BLOCK_Y_SHIFT)
                | (((long) (z & 0x3FFFFFF)) << BLOCK_Z_SHIFT);
    }

    public static int blockX(long packed) {
        return (int) (packed >> BLOCK_X_SHIFT);
    }

    /** Sign-extends the 12-bit y field, so negative build heights survive. */
    public static int blockY(long packed) {
        return (int) (packed << (64 - BLOCK_Y_BITS - BLOCK_Y_SHIFT) >> (64 - BLOCK_Y_BITS));
    }

    public static int blockZ(long packed) {
        return (int) (packed << (64 - BLOCK_Z_BITS - BLOCK_Z_SHIFT)
                >> (64 - BLOCK_Z_BITS));
    }

    private static final int SECTION_X_SHIFT = 42;
    private static final int SECTION_Y_SHIFT = 20;
    private static final int SECTION_Z_SHIFT = 0;
    private static final int SECTION_X_BITS = 22;
    private static final int SECTION_Y_BITS = 20;
    private static final int SECTION_Z_BITS = 20;

    /** {@code SectionPos.asLong()}: x at 42, y at 20, z at 0. */
    public static long sectionLong(int sx, int sy, int sz) {
        return (((long) (sx & 0x3FFFFF)) << SECTION_X_SHIFT)
                | (((long) (sy & 0xFFFFF)) << SECTION_Y_SHIFT)
                | ((long) (sz & 0xFFFFF) << SECTION_Z_SHIFT);
    }

    public static int sectionX(long packed) {
        return (int) (packed >> SECTION_X_SHIFT);
    }

    public static int sectionY(long packed) {
        return (int) (packed << (64 - SECTION_Y_BITS - SECTION_Y_SHIFT)
                >> (64 - SECTION_Y_BITS));
    }

    public static int sectionZ(long packed) {
        return (int) (packed << (64 - SECTION_Z_BITS - SECTION_Z_SHIFT)
                >> (64 - SECTION_Z_BITS));
    }

    /** {@code ChunkPos.asLong()}: x in the low half, z in the high half. */
    public static long chunkLong(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    public static int chunkX(long packed) {
        return (int) packed;
    }

    public static int chunkZ(long packed) {
        return (int) (packed >> 32);
    }

    /** Floor division that rounds towards negative infinity, as MC's Mth.floorDiv. */
    public static int floorDiv(int a, int b) {
        int q = a / b;
        if ((a ^ b) < 0 && q * b != a) q--;
        return q;
    }

    public static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
