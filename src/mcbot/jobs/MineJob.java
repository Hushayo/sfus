package mcbot.jobs;

import mcbot.bot.MinecraftBot;
import mcbot.crafting.Recipes;
import mcbot.registry.Blocks;
import mcbot.registry.ItemNames;
import mcbot.world.BlockScanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Mine 10 stone" - gather a number of a block type, wherever it is nearby.
 *
 * The job loop is deliberately simple and observable:
 *
 *   scan for the nearest matching block -> walk into reach -> break it -> count
 *
 * Blocks that fail to break are remembered and skipped for the rest of the job,
 * so one unbreakable block cannot make the bot retry forever. When nothing
 * matching is left in range the job rescans a few times (chunks may still be
 * arriving) and then stops with an honest partial result rather than hanging.
 */
public final class MineJob implements Job {
    private static final long MAX_RUNTIME_MS = 10 * 60_000L;
    private static final long RESCAN_WAIT_MS = 1500L;

    /**
     * Debug-only mining station: {@code BOT_MINE_AREA="x,y,z,radius"}.
     *
     * When set, the job refuses to mine anything outside that box, so a test
     * run cannot wander off into the world and chew through terrain. Unset by
     * default - production behaviour is unchanged.
     */
    private static final double[] STATION = parseStation();

    private static double[] parseStation() {
        String spec = System.getenv("BOT_MINE_AREA");
        if (spec == null || spec.isBlank()) return null;
        try {
            String[] parts = spec.split(",");
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            double z = Double.parseDouble(parts[2].trim());
            double r = parts.length > 3 ? Double.parseDouble(parts[3].trim()) : 8.0;
            System.out.println("[job] mining station: only " + x + " " + y + " " + z
                    + " +/- " + r + " blocks");
            return new double[]{x, y, z, r};
        } catch (RuntimeException e) {
            System.out.println("[job] bad BOT_MINE_AREA '" + spec + "', ignoring");
            return null;
        }
    }

    /** True when a block is inside the debug mining station (always true when unset). */
    public static boolean inStation(int x, int y, int z) {
        if (STATION == null) return true;
        double dx = (x + 0.5) - STATION[0];
        double dy = (y + 0.5) - STATION[1];
        double dz = (z + 0.5) - STATION[2];
        double r = STATION[3];
        return Math.abs(dx) <= r && Math.abs(dy) <= r && Math.abs(dz) <= r;
    }

    public static boolean stationEnabled() {
        return STATION != null;
    }

    private final MinecraftBot bot;
    private final String block;
    private final int target;
    private final int radius;
    /**
     * Where to search from. Null means "around the bot"; a player asking for
     * "mine 4 oak logs" while standing next to their own test trees expects the
     * search to start there, not wherever the bot happens to be.
     */
    private final double[] center;

    private volatile boolean cancelled;
    private volatile String result;
    private int mined;
    private final Set<Long> skipped = new HashSet<>();

    public MineJob(MinecraftBot bot, String block, int target, int radius) {
        this(bot, block, target, radius, null);
    }

    public MineJob(MinecraftBot bot, String block, int target, int radius, double[] center) {
        this.bot = bot;
        this.block = ItemNames.resolveBlock(block) != null ? ItemNames.resolveBlock(block) : block;
        this.target = Math.max(1, target);
        this.radius = radius <= 0 ? 24 : radius;
        this.center = center;
    }

    public String block() { return block; }

    public int mined() { return mined; }

    @Override
    public void cancel() { cancelled = true; }

    @Override
    public boolean cancelled() { return cancelled; }

    @Override
    public String describe() {
        return "mining " + mined + "/" + target + " " + ItemNames.pretty(block);
    }

    @Override
    public String result() { return result; }

    @Override
    public void run() {
        int state = Blocks.defaultState(block);
        if (state < 0) {
            result = "I don't know what " + ItemNames.pretty(block) + " is.";
            return;
        }
        if (Blocks.unbreakable(state)) {
            result = ItemNames.pretty(block) + " can't be broken.";
            return;
        }
        if (!ensureTool(state)) {
            System.out.println("[job] " + result);
            return;
        }
        System.out.println("[job] mine " + target + "x " + block + " radius " + radius);

        long deadline = System.currentTimeMillis() + MAX_RUNTIME_MS;
        int fruitlessPasses = 0;

        while (!cancelled && mined < target && System.currentTimeMillis() < deadline) {
            if (!bot.playerLoaded || !bot.hasPlayerPosition) {
                sleep(200);
                continue;
            }
            List<BlockScanner.Hit> hits = scan();

            int attempted = 0;
            for (BlockScanner.Hit hit : hits) {
                if (cancelled || mined >= target) break;
                long key = key(hit.x, hit.y, hit.z);
                if (skipped.contains(key)) continue;
                attempted++;
                if (!canMineFrom(hit.x, hit.y, hit.z)) {
                    if (!walkTo(hit.x, hit.y, hit.z)) {
                        skipped.add(key);
                        continue;
                    }
                }
                // The block in front may be a different ore variant, but it is
                // also worth re-checking after walking: the held item is what
                // decides whether the drop exists at all.
                bot.interaction.equipBestTool(hit.state);
                if (bot.mining.breakAt(hit.x, hit.y, hit.z)) {
                    mined++;
                    System.out.println("[job] mined " + hit + " (" + mined + "/" + target + ")");
                    collectDrop(hit);
                } else {
                    skipped.add(key);
                    System.out.println("[job] could not break " + hit + ", skipping");
                }
                sleep(80); // let the block update land before the next scan
            }

            // "No progress" is one condition, not two: nothing in range, or
            // everything in range already blacklisted. Counting them separately
            // (and resetting the counter between) is what made this loop spin
            // forever while the bot stood still.
            if (attempted == 0) {
                if (++fruitlessPasses > 3) {
                    result = mined == 0
                            ? "I couldn't find " + ItemNames.pretty(block)
                              + " I can reach nearby. " + nearbySummary()
                            : "Mined " + mined + " of " + target + " "
                              + ItemNames.pretty(block) + "; the rest wouldn't break.";
                    System.out.println("[job] " + result);
                    return;
                }
                sleep(RESCAN_WAIT_MS);
            } else {
                fruitlessPasses = 0;
            }
        }

        if (cancelled) {
            result = "Stopped after " + mined + " " + ItemNames.pretty(block) + ".";
        } else if (mined >= target) {
            result = "Mined " + mined + " " + ItemNames.pretty(block) + ".";
        } else {
            result = "Ran out of time after " + mined + " " + ItemNames.pretty(block) + ".";
        }
        System.out.println("[job] " + result);
    }

    private List<BlockScanner.Hit> scan() {
        double px, py, pz;
        if (center != null) {
            px = center[0];
            py = center[1];
            pz = center[2];
        } else {
            synchronized (bot.positionLock) {
                px = bot.playerX;
                py = bot.playerY;
                pz = bot.playerZ;
            }
        }
        int want = Math.min(24, target - mined);
        boolean[] wanted = wantedStates();
        List<BlockScanner.Hit> hits = BlockScanner.find(bot.world,
                s -> s >= 0 && s < wanted.length && wanted[s],
                px, py, pz, radius, want * 3);
        if (STATION == null) return hits;
        // Station mode: keep only blocks inside the test box.
        List<BlockScanner.Hit> inside = new ArrayList<>(hits.size());
        for (BlockScanner.Hit hit : hits) {
            if (inStation(hit.x, hit.y, hit.z)) inside.add(hit);
        }
        return inside;
    }

    /**
     * Which state ids count as this job's target, as a lookup table.
     *
     * Exact block first. The fallback only widens to things that yield the
     * same drop: any log for oak_log, and the deepslate variant of the same
     * ore (deepslate_iron_ore for iron_ore). Ores are deliberately not a
     * family across different metals, because mining diamond ore when asked
     * for iron ore wastes a rare block and drops nothing useful - but refusing
     * to mine deepslate iron while standing on it is just a bug.
     */
    private boolean[] wantedStates() {
        if (wantedCache != null) return wantedCache;
        boolean[] table = new boolean[Math.max(1, Blocks.stateCount())];
        boolean anyExact = false;
        for (String name : Blocks.blockNames()) {
            if (!name.equals(block)) continue;
            anyExact = true;
            for (int state : Blocks.statesOf(name)) {
                if (state >= 0 && state < table.length) table[state] = true;
            }
        }
        if (!anyExact) {
            for (String name : Blocks.blockNames()) {
                if (!sameFamily(name) && !sameOre(name)) continue;
                for (int state : Blocks.statesOf(name)) {
                    if (state >= 0 && state < table.length) table[state] = true;
                }
            }
        }
        wantedCache = table;
        return table;
    }

    private volatile boolean[] wantedCache;

    /** Only wood types substitute for each other; ores and the rest are exact. */
    private boolean sameFamily(String name) {
        if (block.endsWith("_log") && name.endsWith("_log")) return true;
        if (block.endsWith("_planks") && name.endsWith("_planks")) return true;
        if (block.endsWith("_wood") && name.endsWith("_wood")) return true;
        return false;
    }

    /** iron_ore and deepslate_iron_ore are the same ore, different stone. */
    private boolean sameOre(String name) {
        String a = block.startsWith("deepslate_") ? block.substring("deepslate_".length()) : block;
        String b = name.startsWith("deepslate_") ? name.substring("deepslate_".length()) : name;
        return a.equals(b) && a.endsWith("_ore");
    }

    /**
     * Makes sure a tool that can actually harvest the block is in hand.
     *
     * A bare "mine 10 iron" from a fresh spawn used to be answered with "I
     * need a stone pickaxe" and nothing else; the player then had to ask for
     * logs, planks, sticks, a wooden pickaxe and stone in the right order.
     * The planner already knows that tree, so the job runs it and then mines.
     */
    private boolean ensureTool(int state) {
        mcbot.inventory.Inventory inv = bot.interaction.inventory;
        if (inv.hasToolFor(state)) {
            bot.interaction.equipBestTool(state);
            return true;
        }
        String tool = Recipes.requiredToolFor(block);
        if (tool == null) return true;
        Recipes.Plan plan = Recipes.plan(tool, 1, inv);
        if (!plan.ok()) {
            result = "I need a " + ItemNames.pretty(tool) + " to mine "
                    + ItemNames.pretty(block) + " and can't make one: " + plan.error + ".";
            return false;
        }
        System.out.println("[job] I need a " + ItemNames.pretty(tool) + " first: "
                + Recipes.describe(plan));
        CraftJob make = new CraftJob(bot, tool, 1);
        make.run();
        if (cancelled) {
            result = "Stopped before I could make a " + ItemNames.pretty(tool) + ".";
            return false;
        }
        if (!inv.hasToolFor(state)) {
            result = "I couldn't make the " + ItemNames.pretty(tool) + " I need for "
                    + ItemNames.pretty(block) + " (" + (make.result() == null ? "no result" : make.result()) + ")";
            return false;
        }
        bot.interaction.equipBestTool(state);
        return true;
    }

    /**
     * Walks onto the dropped item so the server picks it up.
     *
     * Breaking a block does not put anything in the inventory: the server
     * spawns an item entity at the block's centre and only collects it when a
     * player is within about one block. Mining from the edge of reach leaves the
     * drop sitting on the ground, which looks exactly like "mining does
     * nothing".
     *
     * The check is the inventory count of the expected drop, not the presence of
     * an item entity: that is the only thing that proves the pickup happened.
     */
    private void collectDrop(BlockScanner.Hit hit) {
        // The drop belongs to the block that was actually broken, which may be a
        // different wood type than the one requested.
        String actual = Blocks.shortName(hit.state);
        String drop = Recipes.dropOf(actual == null ? block : actual);
        if (drop == null) drop = Recipes.dropOf(block);
        if (drop == null) return;
        int before = bot.interaction.inventory.count(drop);
        sleep(250);                                  // item spawn + pickup delay
        if (bot.interaction.inventory.count(drop) > before) return;

        // The drop is a tracked entity, so walk to where it actually is. Items
        // bounce when they land, and on a slope they roll a block or two away
        // from the block that produced them - which is why walking to the
        // block's own cell misses often enough to matter on a real server.
        for (int attempt = 0; attempt < 4 && !cancelled; attempt++) {
            mcbot.modules.EntityTrackerModule.Pose item =
                    bot.entityTracker.nearestItem(hit.x + 0.5, hit.y + 0.5, hit.z + 0.5, 6.0);
            if (item == null) break;
            bot.movement.goToTight((int) Math.floor(item.x),
                    (int) Math.floor(item.y), (int) Math.floor(item.z));
            long itemDeadline = System.currentTimeMillis() + 3000L;
            while (!cancelled && System.currentTimeMillis() < itemDeadline) {
                sleep(120);
                if (bot.interaction.inventory.count(drop) > before) {
                    System.out.println("[job] picked up " + ItemNames.pretty(drop));
                    return;
                }
            }
        }

        // The block's own cell is now air and is usually the closest place to
        // the drop. Walking into it is both the shortest path and the one that
        // puts the item inside the pickup radius; the old code only tried the
        // eight neighbours, which are ~1.4 blocks from a corner drop and often
        // unreachable under a canopy.
        bot.movement.goToTight(hit.x, hit.y, hit.z);
        long centerDeadline = System.currentTimeMillis() + 4000L;
        while (!cancelled && System.currentTimeMillis() < centerDeadline) {
            sleep(120);
            if (bot.interaction.inventory.count(drop) > before) {
                System.out.println("[job] picked up " + ItemNames.pretty(drop));
                return;
            }
        }

        // Step onto a cell adjacent to the block. Diagonal neighbours are ~1.4
        // blocks from the drop, which is outside the pickup radius, so the
        // straight ones are tried in order and each is verified.
        for (int[] spot : approachSpots(hit.x, hit.y, hit.z)) {
            if (cancelled) return;
            bot.movement.goToTight(spot[0], spot[1], spot[2]);
            long deadline = System.currentTimeMillis() + 4000L;
            while (!cancelled && System.currentTimeMillis() < deadline) {
                sleep(120);
                if (bot.interaction.inventory.count(drop) > before) {
                    System.out.println("[job] picked up " + ItemNames.pretty(drop));
                    return;
                }
            }
        }
        System.out.println("[job] the " + ItemNames.pretty(drop) + " drop is still on the ground");
    }

    private boolean reachable(int x, int y, int z) {
        return bot.interaction.inReach(x, y, z);
    }

    /**
     * What is actually around the bot, for a failure message that helps.
     *
     * "I can't find dirt" is useless if the bot is standing on a stone platform;
     * "around me I see stone, sand and gravel" tells the player what to ask for
     * instead.
     */
    private String nearbySummary() {
        try {
            double px, pz;
            synchronized (bot.positionLock) {
                px = bot.playerX;
                pz = bot.playerZ;
            }
            BlockScanner.Summary summary = BlockScanner.scan(bot.world, px, pz, 12);
            List<int[]> top = summary.top(3);
            if (top.isEmpty()) return "I can't see any blocks around me at all.";
            StringBuilder sb = new StringBuilder("Around me I see ");
            int shown = 0;
            for (int[] entry : top) {
                String name = Blocks.shortName(entry[0]);
                if (name == null) continue;
                if (shown++ > 0) sb.append(", ");
                sb.append(ItemNames.pretty(name));
            }
            sb.append('.');
            return sb.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Walks to a spot from which the block is both in reach and visible.
     *
     * "Visible" matters: mining through a wall is something the server allows
     * but a player cannot do, and the block just disappears with no digging
     * animation anywhere on screen. Trying several approach points is what lets
     * the bot step around a corner instead of giving up on the block.
     */
    private boolean walkTo(int x, int y, int z) {
        if (canMineFrom(x, y, z)) return true;
        for (int[] spot : approachSpots(x, y, z)) {
            if (cancelled) return false;
            bot.movement.goTo(spot[0], spot[1], spot[2]);
            long deadline = System.currentTimeMillis() + 20_000L;
            while (!cancelled && bot.movement.isMoving() && System.currentTimeMillis() < deadline) {
                sleep(100);
            }
            sleep(150);
            if (canMineFrom(x, y, z)) return true;
        }
        return false;
    }

    private boolean canMineFrom(int x, int y, int z) {
        return bot.interaction.inReach(x, y, z) && bot.mining.hasLineOfSight(x, y, z);
    }

    /**
     * Standable cells around the block, nearest to the bot first.
     *
     * Only the eight horizontal neighbours are considered, at the block's own
     * level or one below it. Mining from two blocks below (the old behaviour)
     * put the block outside the server's interaction range, so the dig never
     * started even though the client thought it was in reach.
     */
    private List<int[]> approachSpots(int x, int y, int z) {
        double px, pz;
        synchronized (bot.positionLock) {
            px = bot.playerX;
            pz = bot.playerZ;
        }
        List<int[]> spots = new ArrayList<>();
        for (int dy : new int[]{0, -1, 1}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = x + dx;
                    int nz = z + dz;
                    int ny = y + dy;
                    if (!bot.world.isBlockLoaded(nx, nz)) continue;
                    if (!standable(nx, ny, nz)) continue;
                    spots.add(new int[]{nx, ny, nz});
                }
            }
            if (!spots.isEmpty()) break; // same level beats any other
        }
        spots.sort((a, b) -> Double.compare(
                Math.hypot(a[0] + 0.5 - px, a[2] + 0.5 - pz),
                Math.hypot(b[0] + 0.5 - px, b[2] + 0.5 - pz)));
        return spots;
    }

    private boolean standable(int x, int y, int z) {
        int ground = bot.world.getBlock(x, y - 1, z);
        return Blocks.passable(bot.world.getBlock(x, y, z))
                && Blocks.passable(bot.world.getBlock(x, y + 1, z))
                && (Blocks.solid(ground) || Blocks.topHeight(ground) > 0.0);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) | ((long) z & 0x3FFFFFF) << 12;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
