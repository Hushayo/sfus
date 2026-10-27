package mcbot.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Reads every block in every loaded chunk.
 *
 * The bot only knows block *state ids*: the world cache stores what the server
 * sent, and there is no name registry on the wire. {@link BlockNames} maps ids
 * to names when a registry dump is available, which is what makes "find the
 * nearest oak log" possible.
 *
 * A full overworld chunk is 16 x 384 x 16 = 98,304 blocks; a normal view of
 * ~380 chunks is ~37M block reads, which is a couple of hundred milliseconds of
 * plain char[] scanning. Scans therefore run on their own worker thread, never
 * on the tick thread.
 */
public final class BlockScanner {
    private BlockScanner() {}

    public static final class Hit {
        public final int x, y, z, state;
        public final double distSq;

        Hit(int x, int y, int z, int state, double distSq) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
            this.distSq = distSq;
        }

        public double distance() {
            return Math.sqrt(distSq);
        }

        @Override
        public String toString() {
            String name = BlockNames.name(state);
            return (name == null ? "state " + state : name)
                    + " @ " + x + " " + y + " " + z;
        }
    }

    /** Per-state histogram over the scanned region. */
    public static final class Summary {
        /** Non-air blocks counted (the sum of every count). */
        public final long nonAir;
        /** Air blocks counted; only exact when the scan had no radius limit. */
        public final long air;
        public final int[] counts;
        public final int chunks;
        public final int minY, maxY;
        public final long elapsedMs;

        Summary(long nonAir, long air, int[] counts, int chunks, int minY, int maxY, long elapsedMs) {
            this.nonAir = nonAir;
            this.air = air;
            this.counts = counts;
            this.chunks = chunks;
            this.minY = minY;
            this.maxY = maxY;
            this.elapsedMs = elapsedMs;
        }

        public long count(int state) {
            return state >= 0 && state < counts.length ? counts[state] : 0;
        }

        /** Highest-count states, air excluded, as {state, count} pairs. */
        public List<int[]> top(int n) {
            List<int[]> all = new ArrayList<>();
            for (int state = 0; state < counts.length; state++) {
                if (counts[state] > 0 && state != 0) all.add(new int[]{state, counts[state]});
            }
            all.sort(Comparator.comparingInt((int[] a) -> a[1]).reversed());
            return all.size() > n ? all.subList(0, n) : all;
        }
    }

    /**
     * @param radiusBlocks 0 scans every loaded chunk, otherwise only blocks
     *                     within this horizontal distance of the player.
     */
    public static Summary scan(World world, double px, double pz, int radiusBlocks) {
        long t0 = System.currentTimeMillis();
        int[] counts = new int[1 << 16];
        long[] stats = new long[4]; // total, air, chunks, (unused)
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        long r2 = radiusBlocks <= 0 ? Long.MAX_VALUE : (long) radiusBlocks * radiusBlocks;

        for (Chunk chunk : world.chunks()) {
            int baseX = chunk.chunkX << 4;
            int baseZ = chunk.chunkZ << 4;
            if (radiusBlocks > 0
                    && (Math.abs(baseX + 8 - px) > radiusBlocks + 16
                    || Math.abs(baseZ + 8 - pz) > radiusBlocks + 16)) continue;
            stats[2]++;

            for (int s = 0; s < chunk.sectionCount; s++) {
                char[] section = chunk.section(s);
                int baseY = chunk.minY + (s << 4);
                if (section == null) {
                    if (radiusBlocks <= 0) stats[1] += 4096;
                    continue;
                }
                for (int i = 0; i < 4096; i++) {
                    int state = section[i];
                    if (state == 0) {
                        if (radiusBlocks <= 0) stats[1]++;
                        continue;
                    }
                    int localY = i >> 8;
                    int localZ = (i >> 4) & 15;
                    int localX = i & 15;
                    if (radiusBlocks > 0) {
                        double dx = baseX + localX - px;
                        double dz = baseZ + localZ - pz;
                        if (dx * dx + dz * dz > r2) continue;
                    }
                    stats[0]++;
                    counts[state]++;
                    int y = baseY + localY;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (minY == Integer.MAX_VALUE) {
            minY = 0;
            maxY = 0;
        }
        return new Summary(stats[0], stats[1], counts, (int) stats[2], minY, maxY,
                System.currentTimeMillis() - t0);
    }

    /** Every block matching the predicate, nearest first, capped at {@code limit}. */
    public static List<Hit> find(World world, IntPredicate predicate,
                                 double px, double py, double pz,
                                 int radiusBlocks, int limit) {
        List<Hit> hits = new ArrayList<>();
        long r2 = radiusBlocks <= 0 ? Long.MAX_VALUE : (long) radiusBlocks * radiusBlocks;

        for (Chunk chunk : world.chunks()) {
            int baseX = chunk.chunkX << 4;
            int baseZ = chunk.chunkZ << 4;
            if (radiusBlocks > 0
                    && (Math.abs(baseX + 8 - px) > radiusBlocks + 16
                    || Math.abs(baseZ + 8 - pz) > radiusBlocks + 16)) continue;

            for (int s = 0; s < chunk.sectionCount; s++) {
                char[] section = chunk.section(s);
                if (section == null) continue;
                int baseY = chunk.minY + (s << 4);
                for (int i = 0; i < 4096; i++) {
                    int state = section[i];
                    if (state == 0 || !predicate.test(state)) continue;
                    int localY = i >> 8;
                    int localZ = (i >> 4) & 15;
                    int localX = i & 15;
                    double dx = baseX + localX - px;
                    double dz = baseZ + localZ - pz;
                    if (dx * dx + dz * dz > r2) continue;
                    double dy = baseY + localY - py;
                    hits.add(new Hit(baseX + localX, baseY + localY, baseZ + localZ,
                            state, dx * dx + dy * dy + dz * dz));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(h -> h.distSq));
        if (hits.size() > limit) return new ArrayList<>(hits.subList(0, limit));
        return hits;
    }

    /** The single closest match, or null. */
    public static Hit nearest(World world, IntPredicate predicate,
                              double px, double py, double pz, int radiusBlocks) {
        List<Hit> hits = find(world, predicate, px, py, pz, radiusBlocks, 1);
        return hits.isEmpty() ? null : hits.get(0);
    }
}
