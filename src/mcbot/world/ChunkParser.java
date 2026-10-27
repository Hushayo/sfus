package mcbot.world;

import mcbot.net.PacketReader;

/**
 * Parses the 26.3 (protocol 777) Chunk Data and Update Light packet.
 *
 * Packet layout (after packet id):
 *   int chunkX, int chunkZ,
 *   heightmaps: VarInt count { VarInt type, VarInt longCount, long[] },
 *   data: VarInt byteCount, byte[] (sections),
 *   block entities / light (ignored).
 *
 * Each section:
 *   unsigned short blockCount, unsigned short fluidCount,
 *   paletted block states (4096 entries), paletted biomes (64 entries).
 */
public final class ChunkParser {
    private ChunkParser() {}

    public static Chunk parse(byte[] data, World world) {
        PacketReader pr = new PacketReader(data);
        int chunkX = pr.readInt();
        int chunkZ = pr.readInt();

        int heightmapCount = pr.readVarInt();
        for (int i = 0; i < heightmapCount; i++) {
            pr.readVarInt(); // heightmap type
            int longCount = pr.readVarInt();
            for (int j = 0; j < longCount; j++) pr.readLong();
        }

        int dataLength = pr.readVarInt();
        byte[] sectionData = pr.readBytes(dataLength);
        PacketReader sr = new PacketReader(sectionData);

        Chunk chunk = new Chunk(chunkX, chunkZ, world.minY(), world.height());
        for (int sectionIndex = 0; sectionIndex < chunk.sectionCount; sectionIndex++) {
            sr.readUnsignedShort(); // non-air block count
            sr.readUnsignedShort(); // fluid count
            char[] blocks = readPaletted(sr, 4096, true);
            readPaletted(sr, 64, false); // biomes
            chunk.setSection(sectionIndex, blocks);
        }

        return chunk;
    }

    private static char[] readPaletted(PacketReader pr, int entryCount, boolean keep) {
        int bitsPerEntry = pr.readUnsignedByte();
        if (bitsPerEntry == 0) {
            int value = pr.readVarInt();
            char[] out = keep ? new char[entryCount] : null;
            if (out != null) java.util.Arrays.fill(out, (char) value);
            return out;
        }

        int[] palette = null;
        if (bitsPerEntry <= 8) {
            int paletteLength = pr.readVarInt();
            palette = new int[paletteLength];
            for (int i = 0; i < paletteLength; i++) palette[i] = pr.readVarInt();
        }

        int entriesPerLong = 64 / bitsPerEntry;
        int longCount = (entryCount + entriesPerLong - 1) / entriesPerLong;
        long[] values = new long[longCount];
        for (int i = 0; i < longCount; i++) values[i] = pr.readLong();

        char[] out = keep ? new char[entryCount] : null;
        long mask = (1L << bitsPerEntry) - 1L;
        for (int i = 0; i < entryCount; i++) {
            int longIndex = i / entriesPerLong;
            int bitIndex = (i % entriesPerLong) * bitsPerEntry;
            int value = (int) ((values[longIndex] >>> bitIndex) & mask);
            if (palette != null) {
                if (value < 0 || value >= palette.length) value = 0;
                else value = palette[value];
            }
            if (out != null) out[i] = (char) value;
        }
        return out;
    }
}
