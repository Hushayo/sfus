package mcbot.path;

import mcbot.world.BlockStateTable;
import mcbot.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private static final int GOAL_VERTICAL_TIGHT = 1;
    /**
     * Goal windows, tried in this order, first non-empty one wins.
     *
     * Narrow-first matters more than it looks. Starting at a 2-block radius
     * meant "come to me" collected a goal cell the bot was already standing on
     * whenever the caller was one block up a step: the search ran zero steps,
     * reported "arrived", and the bot stayed at the bottom of the pillar the
     * player was standing on. Same level first, widening only when there is no
     * standable cell at the target's own height.
     *
     * 64 is the compromise for the vertical stages: it covers "the player
     * pasted their eye height" and "the target is on the roof / in the floor
     * below" without turning every request into "walk to the surface" (which
     * would silently ignore a real cave target).
     */
    private static final int[][] GOAL_WINDOWS = {
            {1, 0}, {2, 0}, {0, 0}, {1, 1}, {2, 1}, {2, 3},
            {2, 8}, {2, 24}, {2, 64}, {3, 96}, {4, 96}
    };
    /**
     * Maximum horizontal jump, in blocks.
     *
     * Vanilla walking-jump covers about 2.6 blocks, sprint-jump about 3.6. The
     * old value of 4 produced routes the server refused: the bot scripted a
     * 4-block arc, the server's own physics landed it short, and every landing
     * came back as a position correction. Two blocks is always achievable at
     * walking speed, so the pathfinder no longer proposes moves it cannot make.
     */
    private static final int MAX_JUMP = 2;
    // Walking is the 4 cardinal single steps. Jumps are NOT a fixed direction
    // set: any cell inside the MAX_JUMP box is considered, so shallow/skew
    // diagonals (3 across, 1 along, ...) are reachable too.
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};
    private static final int[] WALK_DY = {1, 0, -1, -2, -3};
    private static final int[] JUMP_DY = {1, 0, -1, -2, -3};
    /** A one-cell hop exists to climb one block, nothing else. */
    private static final int[] JUMP_UP_DY = {1};
    /** A jump is slower and clumsier than walking the same distance. */
    private static final double JUMP_COST = 0.6;

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
        return findPath(world, startX, startY, startZ, targetX, targetY, targetZ,
                blockedEdges, GOAL_RADIUS, GOAL_VERTICAL_TIGHT);
    }

    /**
     * Path to a specific cell, for the cases where "within a few blocks" is not
     * good enough.
     *
     * Picking up a dropped item is the motivating case: the server only
     * collects it within about one block, so a goal set two blocks wide lets
     * the pathfinder decide the bot has already arrived and it never moves.
     */
    public static Result findPathTight(World world, double startX, double startY, double startZ,
                                       double targetX, double targetY, double targetZ,
                                       java.util.Set<Long> blockedEdges) {
        return findPath(world, startX, startY, startZ, targetX, targetY, targetZ,
                blockedEdges, 0, 1);
    }

    public static Result findPath(World world, double startX, double startY, double startZ,
                                  double targetX, double targetY, double targetZ,
                                  java.util.Set<Long> blockedEdges,
                                  int goalRadius, int goalVertical) {
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
        // Search the close, same-level cells first, then widen instead of
        // giving up with "no walkable spot near target".
        HashSet<Long> goals = new HashSet<>();
        if (goalRadius == 0) {
            // Tight goal (item pickup): the exact column, nothing else.
            goals = collectGoals(world, tx, ty, tz, goalRadius, goalVertical);
        } else {
            for (int[] window : GOAL_WINDOWS) {
                goals = collectGoals(world, tx, ty, tz, window[0], window[1]);
                if (!goals.isEmpty()) break;
            }
        }
        if (goals.isEmpty()) {
            System.out.println("[path] no goal near " + tx + "," + ty + "," + tz
                    + " column: " + columnReport(world, tx, ty, tz));
            return new Result(null, tx, ty, tz, "no walkable spot near target");
        }

        if (goals.contains(key(start.x, start.y, start.z))) {
            return new Result(Collections.singletonList(start), start.x, start.y, start.z, null);
        }

        if (System.getenv("BOT_PATH_DEBUG") != null) {
            debugArea(world, start.x, start.z, tx, tz, start.y);
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
                relax(world, current, nx, nz, WALK_DY, 0.0, best, open, tx, ty, tz, blockedEdges);
            }

            // 2) jumping: any cell inside the MAX_JUMP box that is at least two
            //    cells away (Chebyshev), including skew diagonals that no fixed
            //    8-way direction set can express.
            for (int jx = -MAX_JUMP; jx <= MAX_JUMP; jx++) {
                for (int jz = -MAX_JUMP; jz <= MAX_JUMP; jz++) {
                    int cheb = Math.max(Math.abs(jx), Math.abs(jz));
                    if (cheb == 0) continue;
                    int nx = current.x + jx;
                    int nz = current.z + jz;
                    if (Math.abs(nx - start.x) + Math.abs(nz - start.z) > MAX_RANGE) continue;
                    if (!world.isBlockLoaded(nx, nz)) continue;
                    if (cheb < 2) {
                        // One cell away: the only jump worth having is up onto
                        // the cell above (a diagonal onto a stone corner, or a
                        // step onto a pillar). Without this a block whose four
                        // cardinal neighbours are walls had no edge at all.
                        if (!isStandable(world, nx, current.y + 1, nz)) continue;
                        if (!BlockStateTable.passable(
                                world.getBlock(current.x, current.y + 2, current.z))) continue;
                        relax(world, current, nx, nz, JUMP_UP_DY, JUMP_COST,
                                best, open, tx, ty, tz, blockedEdges);
                        continue;
                    }
                    if (!jumpClear(world, current.x, current.z, current.y, nx, nz)) continue;
                    relax(world, current, nx, nz, JUMP_DY, JUMP_COST,
                            best, open, tx, ty, tz, blockedEdges);
                }
            }
        }

        System.out.println("[path] no path start=" + start.x + "," + start.y + "," + start.z
                + " target=" + tx + "," + ty + "," + tz + " goals=" + goals.size()
                + " expanded=" + expanded);
        String reason = expanded >= MAX_NODES ? "path search limit reached" : "no path found";
        return new Result(null, tx, ty, tz, reason);
    }

    /**
     * Heightmap of the corridor between the start and the target, printed only
     * when {@code BOT_PATH_DEBUG} is set. A failed search is a terrain problem
     * or an edge-model problem, and the terrain is the half of that the log
     * cannot otherwise show.
     */
    private static void debugArea(World world, int sx, int sz, int tx, int tz, int y) {
        int x0 = Math.min(sx, tx) - 2;
        int x1 = Math.max(sx, tx) + 2;
        int z0 = Math.min(sz, tz) - 2;
        int z1 = Math.max(sz, tz) + 2;
        System.out.println("[path] debug heightmap rows x=" + x0 + ".." + x1
                + " cols z=" + z0 + ".." + z1 + " (feet level " + y + ")");
        for (int z = z0; z <= z1; z++) {
            StringBuilder sb = new StringBuilder("[path] z=" + z + " ");
            for (int x = x0; x <= x1; x++) {
                int best = Integer.MIN_VALUE;
                for (int yy = y - 8; yy <= y + 8; yy++) {
                    if (isStandable(world, x, yy, z)) best = yy;
                }
                if (best == Integer.MIN_VALUE) sb.append("  .");
                else sb.append(String.format(Locale.ROOT, "%3d", best));
            }
            System.out.println(sb);
        }
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
    private static void relax(World world, Node current, int nx, int nz, int[] dys,
                              double extraCost, HashMap<Long, Node> best, PriorityQueue<Node> open,
                              int tx, int ty, int tz, java.util.Set<Long> blockedEdges) {
        int horiz = Math.max(Math.abs(nx - current.x), Math.abs(nz - current.z));
        if (horiz < 1) horiz = 1;
        for (int dy : dys) {
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
            stepCost += extraCost;
            // Standing next to lava or fire is legal but should be a last
            // resort, not a shortcut.
            if (hazardous(world, nx, ny, nz)) stepCost += 12.0;
            // Swimming is slow and costs air, so a dry detour is usually better.
            if (isWater(world.getBlock(nx, ny, nz))) stepCost += 4.0;
            // Opening a door costs a tick and a click.
            if (isOpenable(world.getBlock(nx, ny, nz))) stepCost += 1.5;

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
        for (int yy = y - 8; yy <= y + 8; yy++) {
            if (yy > y - 8) sb.append(' ');
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

    /**
     * A cell is standable when the feet and head are free and there is a
     * surface to stand on.
     *
     * "Surface" is not the same as "solid": a slab, a stair, a carpet and a
     * snow layer all have a walkable top without being full blocks. Testing
     * {@code solid()} alone made the bot refuse to path across a carpeted
     * floor or up a slab staircase, which is most of a real base.
     */
    private static boolean isStandable(World world, int x, int y, int z) {
        int feet = world.getBlock(x, y, z);
        int head = world.getBlock(x, y + 1, z);
        int ground = world.getBlock(x, y - 1, z);

        // Swimming: a player crosses a river, so the pathfinder has to as well.
        // Water is not "standable" in the floor sense, but it is traversable, so
        // it gets its own branch and a heavy cost in relax().
        if (isWater(feet) && isWater(head)) return true;

        // A closed door or fence gate blocks movement but a player just opens
        // it. Treating these as walls made the bot refuse to enter any room.
        if (isOpenable(feet)) {
            return BlockStateTable.passable(head) || isWater(head);
        }

        return BlockStateTable.passable(feet)
                && BlockStateTable.passable(head)
                && standableSurface(ground);
    }

    private static boolean isWater(int state) {
        return BlockStateTable.category(state) == BlockStateTable.LIQUID;
    }

    /** Doors and fence gates: impassable while shut, but the bot can open them. */
    private static boolean isOpenable(int state) {
        String name = mcbot.registry.Blocks.shortName(state);
        if (name == null) return false;
        if (!name.endsWith("_door") && !name.endsWith("_fence_gate")) return false;
        // Iron doors cannot be opened by hand.
        if (name.startsWith("iron_")) return false;
        return !mcbot.registry.Blocks.hasProperty(state, "open", "true");
    }

    private static boolean standableSurface(int ground) {
        byte cat = BlockStateTable.category(ground);
        if (cat == BlockStateTable.SOLID) return true;
        if (cat == BlockStateTable.HAZARD) return false;
        // Slabs, stairs, carpets, snow layers: partial but walkable.
        return mcbot.registry.Blocks.topHeight(ground) > 0.0
                && !mcbot.registry.Blocks.shape(ground).blocksMovement();
    }

    /** True when standing in this cell hurts: lava, fire, cactus, powder snow. */
    private static boolean hazardous(World world, int x, int y, int z) {
        return BlockStateTable.category(world.getBlock(x, y, z)) == BlockStateTable.HAZARD
                || BlockStateTable.category(world.getBlock(x, y - 1, z)) == BlockStateTable.HAZARD;
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
