package mcbot.world;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side block cache built from chunk packets.
 */
public final class World {
    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();

    private volatile int minY = -64;
    private volatile int height = 384;
    private volatile String dimensionKey = "minecraft:overworld";
    private volatile int dimensionType = 0;

    public int minY() { return minY; }
    public int height() { return height; }
    public int sectionCount() { return height / 16; }
    public String dimensionKey() { return dimensionKey; }
    public int dimensionType() { return dimensionType; }

    /**
     * Uses the dimension resource key when available; falls back to the
     * dimension type registry id. Unknown/custom dimensions keep the
     * overworld height as a safe default.
     */
    public void setDimension(String key, int type) {
        this.dimensionKey = key == null ? "minecraft:overworld" : key;
        this.dimensionType = type;
        if (this.dimensionKey.endsWith("the_nether") || this.dimensionKey.endsWith("the_end")
                || type == 2 || type == 3) {
            minY = 0;
            height = 256;
        } else {
            minY = -64;
            height = 384;
        }
    }

    public void setDimensionType(int type) {
        setDimension(dimensionKey, type);
    }

    public void clear() {
        chunks.clear();
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** Live view of the loaded chunks. Read-only iteration; safe to copy. */
    public java.util.Collection<Chunk> chunks() {
        return chunks.values();
    }

    public static long key(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    public Chunk getChunk(int chunkX, int chunkZ) {
        return chunks.get(key(chunkX, chunkZ));
    }

    public Chunk getOrCreateChunk(int chunkX, int chunkZ) {
        return chunks.computeIfAbsent(key(chunkX, chunkZ),
                k -> new Chunk(chunkX, chunkZ, minY, height));
    }

    public void putChunk(Chunk chunk) {
        chunks.put(key(chunk.chunkX, chunk.chunkZ), chunk);
    }

    public void unloadChunk(int chunkX, int chunkZ) {
        chunks.remove(key(chunkX, chunkZ));
    }

    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return chunks.containsKey(key(chunkX, chunkZ));
    }

    public boolean isBlockLoaded(int x, int z) {
        return isChunkLoaded(x >> 4, z >> 4);
    }

    public int getBlock(int x, int y, int z) {
        Chunk chunk = getChunk(x >> 4, z >> 4);
        if (chunk == null) return 0;
        return chunk.getBlock(x & 15, y, z & 15);
    }

    public void setBlock(int x, int y, int z, int state) {
        Chunk chunk = getChunk(x >> 4, z >> 4);
        if (chunk != null) chunk.setBlock(x & 15, y, z & 15, state);
    }

    public static int floorDiv(int a, int b) {
        int q = a / b;
        if ((a ^ b) < 0 && q * b != a) q--;
        return q;
    }
}
