package mcbot.path;

import mcbot.world.BlockStateTable;
import mcbot.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Small A* pathfinder over the blocks received from the server.
 *
 * Nodes are the block occupied by the player's feet. A node is walkable when
 * the feet and head blocks are passable and the block below is solid.
 */
public final class Pathfinder {
    private static final int MAX_NODES = 250_000;
    private static final int MAX_RANGE = 256;
    private static final int GOAL_RADIUS = 2;
    private static final int GOAL_VERTICAL = 8;
    private static final int GOAL_VERTICAL_TIGHT = 1;
    /**
     * Progressively wider vertical windows, in blocks. 64 is the compromise:
     * it covers "the player pasted their eye height" and "the target is on the
     * roof / in the floor below" without turning every request into "walk to
     * the surface" (which would silently ignore a real cave target).
     */
    private static final int[] WIDENING_VERTICAL = {GOAL_VERTICAL, 24, 64};
    /** Then wider horizontal rings, so "come to me" still lands on a floor. */
    private static final int[] WIDENING_RADIUS = {3, 4};
    private static final int GOAL_VERTICAL_WIDE = 96;
    private static final int MAX_JUMP = 4;
    // Walking is the 4 cardinal single steps. Jumps are NOT a fixed direction
    // set: any cell inside the MAX_JUMP box is considered, so shallow/skew
    // diagonals (3 across, 1 along, ...) are reachable too.
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};
    private static final int[] WALK_DY = {1, 0, -1, -2, -3};
    private static final int[] JUMP_DY = {1, 0, -1, -2, -3};

    private Pathfinder() {}

    public static final class Node {
        public final int x, y, z;
        double g;
        double f;
        Node parent;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static final class Result {
        public final List<Node> path;
        public final int goalX, goalY, goalZ;
        public final String error;

        private Result(List<Node> path, int goalX, int goalY, int goalZ, String error) {
            this.path = path;
            this.goalX = goalX;
            this.goalY = goalY;
            this.goalZ = goalZ;
            this.error = error;
        }

        public boolean ok() { return error == null && path != null && !path.isEmpty(); }
    }

    public static Result findPath(World world, double startX, double startY, double startZ,
                                  double targetX, double targetY, double targetZ) {
        return findPath(world, startX, startY, startZ, targetX, targetY, targetZ,
                Collections.<Long>emptySet());
    }

    /**
     * @param blockedEdges edges (encoded by {@link #edgeKey}) the caller has
     *        already tried and that the server rejected, so the search routes
     *        around them instead of recomputing the same failing move forever.
     */
    public static Result findPath(World world, double startX, double startY, double startZ,
                                  double targetX, double targetY, double targetZ,
                                  java.util.Set<Long> blockedEdges) {
        int sx = floor(startX);
        int sz = floor(startZ);
        int sy = floor(startY + 0.01);

        if (!world.isBlockLoaded(sx, sz)) {
            return new Result(null, sx, sy, sz, "chunks not loaded");
        }

        Node start = findStart(world, sx, sy, sz);
        if (start == null) {
            return new Result(null, sx, sy, sz, "no ground under start position");
        }

        int tx = floor(targetX);
        int tz = floor(targetZ);
        int ty = floor(targetY + 0.01);
        if (!world.isBlockLoaded(tx, tz)) {
            return new Result(null, tx, ty, tz, "target chunks not loaded");
        }

        // The requested Y is often not a feet level: players paste their eye
        // position, or stand on a roof while asking for the ground under them.
        // Search tightly first (so the bot lands next to the caller, not on the
        // roof), then widen the vertical window instead of giving up with
        // "no walkable spot near target".
        HashSet<Long> goals = collectGoals(world, tx, ty, tz, GOAL_RADIUS, GOAL_VERTICAL_TIGHT);
        for (int vertical : WIDENING_VERTICAL) {
            if (!goals.isEmpty()) break;
            goals = collectGoals(world, tx, ty, tz, GOAL_RADIUS, vertical);
        }
        for (int radius : WIDENING_RADIUS) {
            if (!goals.isEmpty()) break;
            goals = collectGoals(world, tx, ty, tz, radius, GOAL_VERTICAL_WIDE);
        }
        if (goals.isEmpty()) {
            System.out.println("[path] no goal near " + tx + "," + ty + "," + tz
                    + " column: " + columnReport(world, tx, ty, tz));
            return new Result(null, tx, ty, tz, "no walkable spot near target");
        }

        if (goals.contains(key(start.x, start.y, start.z))) {
            return new Result(Collections.singletonList(start), start.x, start.y, start.z, null);
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        HashMap<Long, Node> best = new HashMap<>();
        HashSet<Long> closed = new HashSet<>();

        start.g = 0;
        start.f = heuristic(start.x, start.y, start.z, tx, ty, tz);
        open.add(start);
        best.put(key(start.x, start.y, start.z), start);

        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node current = open.poll();
            long currentKey = key(current.x, current.y, current.z);
            if (!closed.add(currentKey)) continue;
            expanded++;

            if (goals.contains(currentKey)) {
                return new Result(reconstruct(current), current.x, current.y, current.z, null);
            }

            // 1) walking: the 4 cardinal single steps.
            for (int dir = 0; dir < 4; dir++) {
                int nx = current.x + DX[dir];
                int nz = current.z + DZ[dir];
                if (Math.abs(nx - start.x) + Math.abs(nz - start.z) > MAX_RANGE) continue;
                if (!world.isBlockLoaded(nx, nz)) continue;
                relax(world, current, nx, nz, false, best, open, tx, ty, tz, blockedEdges);
            }

            // 2) jumping: any cell inside the MAX_JUMP box that is at least two
            //    cells away (Chebyshev), including skew diagonals that no fixed
            //    8-way direction set can express.
            for (int jx = -MAX_JUMP; jx <= MAX_JUMP; jx++) {
                for (int jz = -MAX_JUMP; jz <= MAX_JUMP; jz++) {
                    if (Math.max(Math.abs(jx), Math.abs(jz)) < 2) continue;
                    int nx = current.x + jx;
                    int nz = current.z + jz;
                    if (Math.abs(nx - start.x) + Math.abs(nz - start.z) > MAX_RANGE) continue;
                    if (!world.isBlockLoaded(nx, nz)) continue;
                    if (!jumpClear(world, current.x, current.z, current.y, nx, nz)) continue;
                    relax(world, current, nx, nz, true, best, open, tx, ty, tz, blockedEdges);
                }
            }
        }

        System.out.println("[path] no path start=" + start.x + "," + start.y + "," + start.z
                + " target=" + tx + "," + ty + "," + tz + " goals=" + goals.size()
                + " expanded=" + expanded);
        String reason = expanded >= MAX_NODES ? "path search limit reached" : "no path found";
        return new Result(null, tx, ty, tz, reason);
    }

    private static Node findStart(World world, int x, int y, int z) {
        // Widen the search downwards: after a fall or a bad teleport the feet
        // block can be well below the last position the server reported.
        for (int dy = 2; dy >= -64; dy--) {
            int yy = y + dy;
            if (isStandable(world, x, yy, z)) return new Node(x, yy, z);
        }
        return null;
    }

    private static HashSet<Long> collectGoals(World world, int x, int y, int z,
                                              int radius, int vertical) {
        HashSet<Long> goals = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int xx = x + dx;
                int zz = z + dz;
                if (!world.isBlockLoaded(xx, zz)) continue;
                for (int dy = vertical; dy >= -vertical; dy--) {
                    int yy = y + dy;
                    if (isStandable(world, xx, yy, zz)) {
                        goals.add(key(xx, yy, zz));
                    }
                }
            }
        }
        return goals;
    }

    /**
     * Expands one destination cell into its reachable feet levels. The step cost
     * scales with the horizontal distance actually travelled, so a jump is never
     * cheaper than walking the same distance.
     */
    private static void relax(World world, Node current, int nx, int nz, boolean jump,
                              HashMap<Long, Node> best, PriorityQueue<Node> open,
                              int tx, int ty, int tz, java.util.Set<Long> blockedEdges) {
        int horiz = Math.max(Math.abs(nx - current.x), Math.abs(nz - current.z));
        if (horiz < 1) horiz = 1;
        for (int dy : (jump ? JUMP_DY : WALK_DY)) {
            int ny = current.y + dy;
            if (!blockedEdges.isEmpty()
                    && blockedEdges.contains(edgeKey(current.x, current.y, current.z, nx, ny, nz))) continue;
            if (!isStandable(world, nx, ny, nz)) continue;
            if (dy > 0 && !BlockStateTable.passable(
                    world.getBlock(current.x, current.y + 2, current.z))) continue;
            if (dy < 0 && !clearColumn(world, nx, ny + 1, current.y + 1, nz)) continue;

            double stepCost = horiz;
            if (dy > 0) stepCost += 0.5;
            else if (dy < 0) stepCost += 0.25 * (-dy);
            if (jump) stepCost += 0.6;

            double ng = current.g + stepCost;
            long nk = key(nx, ny, nz);
            Node old = best.get(nk);
            if (old != null && old.g <= ng) continue;

            Node next = new Node(nx, ny, nz);
            next.g = ng;
            next.f = ng + heuristic(nx, ny, nz, tx, ty, tz);
            next.parent = current;
            best.put(nk, next);
            open.add(next);
        }
    }

    /**
     * Air the hitbox must travel through for a jump edge of arbitrary slope. The
     * takeoff needs head room (the arc peaks ~1.15 blocks up) and every cell on
     * the straight line from the takeoff to the landing must be free at the
     * takeoff feet and head levels.
     */
    private static boolean jumpClear(World world, int x0, int z0, int y0, int x1, int z1) {
        if (!BlockStateTable.passable(world.getBlock(x0, y0 + 2, z0))) return false;
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int x = x0;
        int z = z0;
        int err = dx - dz;
        while (!(x == x1 && z == z1)) {
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 < dx) { err += dx; z += sz; }
            if (x == x1 && z == z1) break;
            if (!BlockStateTable.passable(world.getBlock(x, y0, z))) return false;
            if (!BlockStateTable.passable(world.getBlock(x, y0 + 1, z))) return false;
            // The arc peaks ~1.15 blocks up, so the cell above head height has
            // to be free too or the server's physics clip it and reject the jump.
            if (!BlockStateTable.passable(world.getBlock(x, y0 + 2, z))) return false;
        }
        return true;
    }

    /** Encodes a directed path edge so a caller can blacklist rejected moves. */
    public static long edgeKey(int x0, int y0, int z0, int x1, int y1, int z1) {
        return key(x0, y0, z0) ^ (key(x1, y1, z1) * 0x9E3779B97F4A7C15L);
    }

    private static String columnReport(World world, int x, int y, int z) {
        StringBuilder sb = new StringBuilder();
        for (int yy = y - GOAL_VERTICAL; yy <= y + GOAL_VERTICAL; yy++) {
            if (yy > y - GOAL_VERTICAL) sb.append(' ');
            sb.append(yy).append(':').append(categoryChar(world.getBlock(x, yy, z)));
        }
        return sb.toString();
    }

    private static char categoryChar(int stateId) {
        switch (BlockStateTable.category(stateId)) {
            case BlockStateTable.PASSABLE: return '.';
            case BlockStateTable.SOLID: return '#';
            case BlockStateTable.LIQUID: return '~';
            case BlockStateTable.HAZARD: return '!';
            case BlockStateTable.CLIMBABLE: return '^';
            default: return '?';
        }
    }

    private static boolean isStandable(World world, int x, int y, int z) {
        int feet = world.getBlock(x, y, z);
        int head = world.getBlock(x, y + 1, z);
        int ground = world.getBlock(x, y - 1, z);
        return BlockStateTable.passable(feet)
                && BlockStateTable.passable(head)
                && BlockStateTable.solid(ground);
    }

    private static boolean clearColumn(World world, int x, int fromY, int toY, int z) {
        if (fromY > toY) return true;
        for (int y = fromY; y <= toY; y++) {
            if (!BlockStateTable.passable(world.getBlock(x, y, z))) return false;
        }
        return true;
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        return Math.abs(x - gx) + Math.abs(z - gz) + 0.5 * Math.abs(y - gy);
    }

    private static List<Node> reconstruct(Node node) {
        List<Node> path = new ArrayList<>();
        while (node != null) {
            path.add(node);
            node = node.parent;
        }
        Collections.reverse(path);
        return path;
    }

    /** Internal hash key only (never sent on the wire); any injective packing works. */
    private static long key(int x, int y, int z) {
        return (((long) (x & 0x3FFFFFF)) << 38)
                | (((long) (y & 0xFFF)) << 26)
                | (z & 0x3FFFFFFL);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
