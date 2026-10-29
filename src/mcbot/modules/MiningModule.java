package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketIds;
import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;
import mcbot.registry.Blocks;
import mcbot.registry.Items;
import mcbot.util.Pos;
import mcbot.world.BlockStateTable;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Block breaking.
 *
 * Chat commands:
 *   "mine x y z" / "dig x y z" / "break x y z"  -> that exact block
 *   "mine" / "mine here" / "mine this"          -> the block the sender is
 *        looking at, or the block under their feet; the bot walks there first
 *   "mine ahead|forward|front"                  -> first solid block in front
 *
 * Break time is now computed instead of guessed. The registry knows each
 * block's hardness, tool class and required tier, and each item's speed
 * multiplier, so the hold time comes from the same formula the server uses:
 *
 * <pre>
 *   damage per tick = toolSpeed / hardness / (correctTool ? 30 : 100)
 *   ticks           = ceil(1 / damage per tick)
 * </pre>
 *
 * The bot also switches to the best tool in its hotbar before starting, which
 * is the difference between 0.4 s and 7 s on stone, and between "drops cobble"
 * and "drops nothing" on iron ore.
 *
 * The server stays authoritative: success is only reported when the block
 * actually changes in the client-side world, so a rejected START is detected
 * rather than assumed.
 */
public class MiningModule implements BotModule {
    private static final Pattern MINE = Pattern.compile(
            "(?i)(?:^|\\s)(?:mine|dig|break|mine\\s+block)\\s+" +
                    "(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)");
    /** Bot-relative: mine the block the bot is facing. */
    private static final Pattern MINE_AHEAD = Pattern.compile(
            "(?i)^\\s*(?:mine|dig|break)(?:\\s+(?:ahead|forward|front|in\\s+front(?:\\s+of\\s+(?:me|you))?))?" +
                    "\\s*$");
    /** Sender-relative: mine where the asking player is looking / standing. */
    private static final Pattern MINE_HERE = Pattern.compile(
            "(?i)^\\s*(?:mine|dig|break|chop)\\s+" +
                    "(?:here|this|that|it|there|around|nearby|the\\s+block(?:\\s+here)?|" +
                    "this\\s+block|that\\s+block)\\s*$");

    private static final int ACTION_START_DESTROY_BLOCK = 0;
    /** 1 is CHANGE_DESTROY_DIRECTION in 26.3; ABORT is 2 and STOP is 3. */
    private static final int ACTION_ABORT_DESTROY_BLOCK = 2;
    /** The action that actually breaks the block; see breakBlock. */
    private static final int ACTION_STOP_DESTROY_BLOCK = 3;

    /** breakBlock outcomes. */
    private static final int BREAK_CANCELLED = -1;
    private static final int BREAK_FAILED = 0;
    private static final int BREAK_DONE = 1;

    private static final double REACH = 4.5;
    private static final double LOOK_REACH = 5.0;
    private static final double EYE_HEIGHT = 1.62;
    private static final long TICK_MS = 50L;
    private static final int MAX_ATTEMPTS = 4;

    private volatile MinecraftBot bot;
    private volatile Thread worker;
    private volatile boolean mining = false;
    private volatile long generation = 0;
    private volatile int sequence = 0;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    /** True for a message this module answers itself (so the AI stays quiet). */
    public static boolean isMiningCommand(String message) {
        if (message == null) return false;
        return MINE.matcher(message).find()
                || MINE_AHEAD.matcher(message).matches()
                || MINE_HERE.matcher(message).matches();
    }

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        if (packetId != PacketIds.PLAYER_CHAT) return;
        PacketReader pr = new PacketReader(data);
        pr.readVarInt();
        UUID sender = pr.readUUID();
        pr.readVarInt();
        if (pr.readByte() != 0) pr.readBytes(256);
        String message = pr.readString();
        if (message == null || message.isBlank()) return;
        if (sender.equals(bot.uuid)) return;

        String text = message.trim();
        Matcher m = MINE.matcher(text);
        if (m.find()) {
            int x = Integer.parseInt(m.group(1));
            int y = Integer.parseInt(m.group(2));
            int z = Integer.parseInt(m.group(3));
            System.out.println("[mine] command from " + sender + ": " + text);
            bot.sendChat(mine(x, y, z));
            return;
        }
        if (MINE_HERE.matcher(text).matches()) {
            System.out.println("[mine] here command from " + sender + ": " + text);
            bot.sendChat(mineNear(sender));
            return;
        }
        if (MINE_AHEAD.matcher(text).matches()) {
            System.out.println("[mine] ahead command from " + sender + ": " + text);
            int[] target = blockInFront(bot);
            if (target == null) {
                bot.sendChat("I can't see a block in front of me.");
            } else {
                bot.sendChat(mine(target[0], target[1], target[2]));
            }
        }
    }

    /**
     * Queues a break of the block at x y z. Returns a short status line. The
     * break itself runs on a worker thread so the packet loop never blocks.
     */
    public String mine(int x, int y, int z) {
        MinecraftBot b = bot;
        if (b == null) return "Not connected yet.";
        if (!b.playerLoaded || !b.hasPlayerPosition) return "I'm not in the world yet.";
        if (!b.world.isBlockLoaded(x, z)) return "That chunk isn't loaded for me.";

        int state = b.world.getBlock(x, y, z);
        if (Blocks.isAir(state) || BlockStateTable.passable(state)) {
            return "There's nothing to mine at " + x + " " + y + " " + z + ".";
        }
        byte cat = BlockStateTable.category(state);
        if (cat == BlockStateTable.HAZARD) {
            return "I'm not mining a hazard block at " + x + " " + y + " " + z + ".";
        }
        if (Blocks.unbreakable(state)) {
            return Blocks.shortName(state) + " can't be broken.";
        }

        stop();
        mining = true;
        long gen = ++generation;
        worker = new Thread(() -> runFixed(b, x, y, z, gen), "mining");
        worker.start();

        String name = Blocks.shortName(state);
        String tier = Blocks.requiredTier(state);
        String tool = Blocks.toolClass(state);
        String detail = name == null ? "block" : name;
        if (tool != null) {
            detail += " (" + tool + ", needs " + tier + ")";
        }
        return "Mining " + detail + " at " + x + " " + y + " " + z + ".";
    }

    /**
     * Mines where the given player is, without coordinates: the block they are
     * looking at, or the block under their feet. If they are out of range or
     * their chunks are not loaded, the bot walks to them first.
     */
    public String mineNear(UUID sender) {
        MinecraftBot b = bot;
        if (b == null) return "Not connected yet.";
        if (!b.playerLoaded || !b.hasPlayerPosition) return "I'm not in the world yet.";
        if (sender == null) return "I don't know who is asking.";

        stop();
        mining = true;
        long gen = ++generation;
        worker = new Thread(() -> runNear(b, sender, gen), "mining");
        worker.start();
        return "Mining the block near you.";
    }

    /** Aborts any break in progress. */
    public void stop() {
        mining = false;
        generation++;
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    public boolean isMining() {
        return mining;
    }

    // ------------------------------------------------- server dig progress --
    //
    // Two server signals describe a dig in progress:
    //
    //   level_event 2019/2020  - the block-crack event, sent every tick for a
    //                            break the server is ticking, to everyone within
    //                            64 blocks including the digger. This is the
    //                            heartbeat that proves a START was accepted.
    //   block_destruction 0x05 - the crack stage 0..9, sent to *other* players
    //                            only (the digger's client predicts its own), so
    //                            it is useless for our own digs but fine for
    //                            noticing someone else's.
    //
    // A START the server refuses - spawn protection, world border, adventure
    // mode, out of range - is answered with nothing at all. The old code just
    // waited out its five second ceiling and called it a failure, which is why
    // a refused dig looked like a bot with no strength.

    private final Object progressLock = new Object();
    private int progressX = Integer.MIN_VALUE;
    private int progressY = Integer.MIN_VALUE;
    private int progressZ = Integer.MIN_VALUE;
    private boolean accepted = false;
    private long lastActivityMs = 0L;

    /** Called from the packet thread for every level_event 2019/2020. */
    public void onServerActivity(int x, int y, int z) {
        synchronized (progressLock) {
            if (x != progressX || y != progressY || z != progressZ) return;
            accepted = true;
            lastActivityMs = System.currentTimeMillis();
        }
    }

    /** Called from the packet thread for every block_destruction packet. */
    public void onServerProgress(int breakerId, int x, int y, int z, int stage) {
        MinecraftBot b = bot;
        if (b == null) return;
        // Our own digs are reported through level_event instead.
        if (breakerId == b.entityId) return;
        synchronized (progressLock) {
            if (x != progressX || y != progressY || z != progressZ) return;
            accepted = true;
            lastActivityMs = System.currentTimeMillis();
        }
    }

    /** Starts watching one block. */
    private void watchProgress(int x, int y, int z) {
        synchronized (progressLock) {
            progressX = x;
            progressY = y;
            progressZ = z;
            accepted = false;
            lastActivityMs = 0L;
        }
    }

    private boolean serverAccepted() {
        synchronized (progressLock) {
            return accepted;
        }
    }

    /** Milliseconds since the last sign of life from the server, or -1. */
    private long activityAgeMs() {
        synchronized (progressLock) {
            return lastActivityMs == 0L ? -1L : System.currentTimeMillis() - lastActivityMs;
        }
    }

    /**
     * True when the bot can actually see the block it is about to mine.
     *
     * The server does not check this - it only checks distance - so without it
     * the bot happily breaks blocks through walls, floors and other players.
     * A player cannot, and a bot that does looks like it is cheating; it also
     * means the break happens somewhere the player cannot see, so the block
     * just vanishes with no digging animation on screen.
     *
     * The ray marches from the eye to the block centre in 0.1-block steps and
     * fails as soon as it enters a block that is neither the target nor
     * passable.
     */
    public boolean hasLineOfSight(int x, int y, int z) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return false;
        double ex, ey, ez;
        synchronized (b.positionLock) {
            ex = b.playerX;
            ey = b.playerY + EYE_HEIGHT;
            ez = b.playerZ;
        }
        double tx = x + 0.5;
        double ty = y + 0.5;
        double tz = z + 0.5;
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.01) return true;
        int steps = (int) Math.ceil(dist / 0.1);
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int bx = (int) Math.floor(ex + dx * t);
            int by = (int) Math.floor(ey + dy * t);
            int bz = (int) Math.floor(ez + dz * t);
            if (bx == lastX && by == lastY && bz == lastZ) continue;
            lastX = bx;
            lastY = by;
            lastZ = bz;
            if (bx == x && by == y && bz == z) return true;   // reached the target
            int state = b.world.getBlock(bx, by, bz);
            if (!BlockStateTable.passable(state)) return false;
        }
        return true;
    }

    /**
     * Breaks one block synchronously, on the caller's thread.
     *
     * This is what jobs use: a mining job owns the loop (find, walk, break,
     * count) and needs the break itself to be a plain call it can wait on,
     * not a thread with its own generation counter. It returns true only when
     * the block really changed in the world cache.
     */
    public boolean breakAt(int x, int y, int z) {
        MinecraftBot b = bot;
        if (b == null || !b.playerLoaded || !b.hasPlayerPosition) return reject(x, y, z, "not in world");
        if (!b.world.isBlockLoaded(x, z)) return reject(x, y, z, "chunk not loaded");
        if (!b.interaction.inReach(x, y, z)) return reject(x, y, z, "out of reach");
        if (!hasLineOfSight(x, y, z)) return reject(x, y, z, "no line of sight");
        int state = b.world.getBlock(x, y, z);
        if (Blocks.isAir(state) || BlockStateTable.passable(state)) {
            return reject(x, y, z, "nothing there (" + Blocks.shortName(state) + ")");
        }
        if (Blocks.unbreakable(state)) return reject(x, y, z, "unbreakable");
        try {
            face(b, x, y, z);
            Thread.sleep(60L);
            return breakBlock(b, x, y, z, generation) == BREAK_DONE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            System.out.println("[mine] breakAt failed: " + e);
            return false;
        }
    }

    private static boolean reject(int x, int y, int z, String why) {
        System.out.println("[mine] cannot break " + x + " " + y + " " + z + ": " + why);
        return false;
    }

    /** Waits for a walk, then mines a known block. */
    private void runFixed(MinecraftBot b, int x, int y, int z, long gen) {
        try {
            waitForMovement(b, gen, 30_000L);
            if (gen != generation) return;

            if (!withinReach(b, x, y, z)) {
                int[] spot = findApproach(b, x, y, z);
                if (spot == null) {
                    finish(b, gen, "I can't reach " + x + " " + y + " " + z + ".");
                    return;
                }
                System.out.println("[mine] walking to " + spot[0] + " " + spot[1] + " " + spot[2]
                        + " to reach " + x + " " + y + " " + z);
                b.movement.goTo(spot[0], spot[1], spot[2]);
                waitForMovement(b, gen, 60_000L);
                if (gen != generation) return;
                if (!withinReach(b, x, y, z)) {
                    finish(b, gen, "I couldn't get close enough to " + x + " " + y + " " + z + ".");
                    return;
                }
            }

            face(b, x, y, z);
            int result = breakBlock(b, x, y, z, gen);
            if (result == BREAK_CANCELLED) return;
            if (result == BREAK_FAILED) {
                finish(b, gen, "I couldn't break " + x + " " + y + " " + z + ".");
                return;
            }
            finish(b, gen, "Mined " + x + " " + y + " " + z + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (gen == generation) {
                System.out.println("[mine] error: " + e);
                try {
                    finish(b, gen, "Mining failed.");
                } catch (Exception ignored) {}
            }
        } finally {
            if (gen == generation) mining = false;
        }
    }

    /**
     * Resolves the sender's position/rotation into a target block, walking to
     * them first when they are out of range or their chunks are unloaded.
     */
    private void runNear(MinecraftBot b, UUID sender, long gen) {
        try {
            waitForMovement(b, gen, 30_000L);
            if (gen != generation) return;

            int[] target = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                if (gen != generation) return;
                double[] pose = b.entityTracker.poseOf(sender);
                if (pose == null) {
                    System.out.println("[mine] " + sender + " not in range yet, walking over");
                    b.movement.comeTo(sender);
                    waitForMovement(b, gen, 90_000L);
                    continue;
                }
                int bx = (int) Math.floor(pose[0]);
                int bz = (int) Math.floor(pose[2]);
                if (!b.world.isBlockLoaded(bx, bz)) {
                    System.out.println("[mine] chunks around " + sender + " not loaded, walking over");
                    b.movement.comeTo(sender);
                    waitForMovement(b, gen, 90_000L);
                    continue;
                }
                target = lookTarget(b, pose[0], pose[1], pose[2], pose[3], pose[4]);
                System.out.println("[mine] sender pose " + pose[0] + " " + pose[1] + " " + pose[2]
                        + " yaw=" + pose[3] + " pitch=" + pose[4]
                        + (target == null ? " -> no target"
                        : " -> target " + target[0] + " " + target[1] + " " + target[2]));
                if (target != null) break;
                finish(b, gen, "I can't see a block near you to mine.");
                return;
            }
            if (gen != generation) return;
            if (target == null) {
                finish(b, gen, "I can't find a block near you to mine.");
                return;
            }

            System.out.println("[mine] targeting " + target[0] + " " + target[1] + " " + target[2]
                    + " near " + sender);
            runFixed(b, target[0], target[1], target[2], gen);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (gen == generation) {
                System.out.println("[mine] error: " + e);
                try {
                    finish(b, gen, "Mining failed.");
                } catch (Exception ignored) {}
            }
        } finally {
            if (gen == generation) mining = false;
        }
    }

    /**
     * Breaks one block: one START, then one STOP at the right moment.
     *
     * The server owns the progress, and it is emphatic about it:
     * {@code ServerPlayerGameMode.tick()} accumulates {@code getDestroyProgress}
     * and sends the 2019/2020 crack events, but it never destroys the block
     * itself. The block is destroyed only in {@code handleBlockBreakAction}'s
     * STOP_DESTROY_BLOCK branch, and only once the accumulated progress has
     * reached 0.7 of 1.0:
     *
     * <pre>
     *   f = state.getDestroyProgress(player, level, pos) * (elapsed + 1)
     *   f &gt;= 0.7  -&gt; destroy the block and drop its items
     *   f &lt;  0.7  -&gt; "delayed destroy": the server keeps the block and
     *                 finishes it on a later tick
     * </pre>
     *
     * So the client's whole job is to send STOP once roughly 70% of the dig
     * time has passed. The old code sent it only after its five second ceiling,
     * which made every block - stone with a stone pickaxe included - take five
     * seconds and then break "by itself".
     *
     * The predicted time has to match the server's maths, which divides mining
     * speed by five while {@code onGround} is false. That flag is whatever the
     * last movement packet we sent said, so the ETA is taken from
     * {@link MinecraftBot#sentOnGround}, not from the local body.
     */
    private int breakBlock(MinecraftBot b, int x, int y, int z, long gen) throws Exception {
        int dir = faceDirection(b, x, y, z);
        int original = b.world.getBlock(x, y, z);
        String name = Blocks.shortName(original);

        if (Blocks.unbreakable(original)) {
            System.out.println("[mine] " + name + " is unbreakable");
            return BREAK_FAILED;
        }

        boolean swapped = b.interaction.equipBestTool(original);
        if (swapped) Thread.sleep(150L);

        Items.Item held = b.interaction.inventory.held().item();
        boolean correct = Items.isCorrectTool(held, original);
        boolean harvest = Items.canHarvest(held, original);
        // The server tests isEyeInFluid(WATER), so the eye is what counts: shallow
        // water with dry eyes does not slow a dig down.
        int eyeState = b.world.getBlock(
                (int) Math.floor(b.playerX), (int) Math.floor(b.playerY + EYE_HEIGHT),
                (int) Math.floor(b.playerZ));
        boolean inWater = BlockStateTable.category(eyeState) == BlockStateTable.LIQUID;
        // Same flag the server will use. It divides mining speed by 5 while it
        // believes we are airborne, so predicting from a hardcoded "true" made
        // every ETA up to five times too optimistic.
        boolean onGround = b.sentOnGround;
        double seconds = Items.breakSeconds(held, original, inWater, onGround);
        // Ceiling only. The STOP below is self-correcting: an early STOP makes
        // the server fall back to its delayed destroy and finish the block a
        // tick later, so this only has to cover a genuinely stuck dig.
        long maxWaitMs = (long) Math.max(6000.0, seconds * 1000.0 * 3.0 + 2000.0);

        System.out.println("[mine] " + name + " hardness=" + Blocks.hardness(original)
                + " tool=" + held.name + " correct=" + correct + " harvest=" + harvest
                + " onGround=" + onGround + " inWater=" + inWater
                + " expected=" + String.format(java.util.Locale.ROOT, "%.2f", seconds)
                + "s wait=" + maxWaitMs + "ms");

        if (!correct && Blocks.toolClass(original) != null) {
            System.out.println("[mine] WARNING: " + Blocks.toolClass(original)
                    + " recommended for " + name + ", holding " + held.name);
        }

        if (!Double.isFinite(seconds)) {
            System.out.println("[mine] " + name + " cannot be broken with "
                    + (held == null ? "an empty hand" : held.name));
            return BREAK_FAILED;
        }

        // Tell the server we are standing on the ground before the dig starts, if
        // that is what the body is actually doing. The flag costs one packet and
        // is worth 5x on every block: Player.getDestroySpeed divides by five
        // while the server thinks the player is airborne.
        if (b.physics.onGround != b.sentOnGround) {
            b.sendPositionAndTickEnd(b.physics.onGround);
            Thread.sleep(60L);
        }

        sendAction(b, ACTION_START_DESTROY_BLOCK, x, y, z, dir);
        b.interaction.swing();   // arm animation, same as a player's
        watchProgress(x, y, z);

        long started = System.currentTimeMillis();
        // When to send STOP_DESTROY_BLOCK. The server accepts it once
        // getDestroyProgress * (elapsed + 1) >= 0.7, i.e. just past 70% of the
        // full dig time, so 75% of the prediction has margin for a late packet
        // while still finishing the block as fast as the game allows.
        long stopAfterMs = (long) Math.max(60.0, seconds * 1000.0 * 0.75);
        long deadline = started + maxWaitMs;
        boolean stopped = false;

        while (System.currentTimeMillis() < deadline) {
            if (gen != generation) {
                sendAction(b, ACTION_ABORT_DESTROY_BLOCK, x, y, z, dir);
                return BREAK_CANCELLED;
            }
            Thread.sleep(TICK_MS);
            if (b.world.getBlock(x, y, z) != original) {
                System.out.println("[mine] " + name + " broke after "
                        + (System.currentTimeMillis() - started) + " ms (expected "
                        + String.format(java.util.Locale.ROOT, "%.0f", seconds * 1000.0) + " ms)");
                return BREAK_DONE;
            }

            if (!stopped && System.currentTimeMillis() - started >= stopAfterMs) {
                // Commit the dig. This is the packet that actually breaks the
                // block; without it the server never destroys anything.
                sendAction(b, ACTION_STOP_DESTROY_BLOCK, x, y, z, dir);
                stopped = true;
                continue;
            }

            // A dig the server accepted is answered with a crack event every tick.
            // Total silence means the START was refused (spawn protection, world
            // border, adventure mode, out of range) and no amount of waiting
            // will change that, so say so instead of burning the whole ceiling
            // on every block.
            if (!serverAccepted()) {
                if (System.currentTimeMillis() - started > 1500L) {
                    System.out.println("[mine] " + name + " at " + x + " " + y + " " + z
                            + " was refused by the server (no crack events) - aborting");
                    sendAction(b, ACTION_ABORT_DESTROY_BLOCK, x, y, z, dir);
                    return BREAK_FAILED;
                }
            } else if (!stopped && activityAgeMs() > 1500L) {
                // Accepted, then the server went quiet before we committed: the
                // dig was aborted server-side (block changed, player moved out of
                // range, a plugin cancelled it). Waiting longer cannot help.
                System.out.println("[mine] " + name + " at " + x + " " + y + " " + z
                        + " stopped progressing on the server - aborting");
                sendAction(b, ACTION_ABORT_DESTROY_BLOCK, x, y, z, dir);
                return BREAK_FAILED;
            }
        }

        // STOP was sent and the block still did not change. Refuse it and abort
        // so the server does not keep accumulating in the background.
        System.out.println("[mine] " + name + " did not break within " + maxWaitMs
                + " ms (serverAccepted=" + serverAccepted() + ", stopped=" + stopped + ") - aborting");
        sendAction(b, ACTION_ABORT_DESTROY_BLOCK, x, y, z, dir);
        return BREAK_FAILED;
    }

    private void finish(MinecraftBot b, long gen, String message) throws Exception {
        if (gen != generation) return;
        mining = false;
        System.out.println("[mine] " + message);
        b.sendChat(message);
    }

    private void waitForMovement(MinecraftBot b, long gen, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (gen == generation && b.movement.isMoving()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        Thread.sleep(200L);
    }

    private boolean withinReach(MinecraftBot b, int x, int y, int z) {
        return b.interaction.inReach(x, y, z);
    }

    /** Standable neighbour block closest to the bot, at the target's level or just below. */
    private int[] findApproach(MinecraftBot b, int x, int y, int z) {
        double px, pz;
        synchronized (b.positionLock) {
            px = b.playerX;
            pz = b.playerZ;
        }
        int[] best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = x + dx;
                int nz = z + dz;
                if (!b.world.isBlockLoaded(nx, nz)) continue;
                for (int dy = 1; dy >= -2; dy--) {
                    int ny = y + dy;
                    if (!standable(b, nx, ny, nz)) continue;
                    double d = Math.hypot(nx + 0.5 - px, nz + 0.5 - pz);
                    if (d < bestDist) {
                        bestDist = d;
                        best = new int[]{nx, ny, nz};
                    }
                    break; // closest level for this column
                }
            }
        }
        return best;
    }

    private static boolean standable(MinecraftBot b, int x, int y, int z) {
        int ground = b.world.getBlock(x, y - 1, z);
        return BlockStateTable.passable(b.world.getBlock(x, y, z))
                && BlockStateTable.passable(b.world.getBlock(x, y + 1, z))
                && (BlockStateTable.solid(ground) || Blocks.topHeight(ground) > 0.0);
    }

    /**
     * The block a player is aiming at (ray from their eyes along their look
     * direction, up to LOOK_REACH), else the block under their feet.
     */
    private static int[] lookTarget(MinecraftBot b, double px, double py, double pz,
                                    double yawDeg, double pitchDeg) {
        double ex = px;
        double ey = py + EYE_HEIGHT;
        double ez = pz;
        double yr = Math.toRadians(yawDeg);
        double pr = Math.toRadians(pitchDeg);
        double dx = -Math.sin(yr) * Math.cos(pr);
        double dy = -Math.sin(pr);
        double dz = Math.cos(yr) * Math.cos(pr);
        for (double t = 0.25; t <= LOOK_REACH; t += 0.1) {
            int bx = (int) Math.floor(ex + dx * t);
            int by = (int) Math.floor(ey + dy * t);
            int bz = (int) Math.floor(ez + dz * t);
            int state = b.world.getBlock(bx, by, bz);
            if (BlockStateTable.passable(state)) continue;
            if (BlockStateTable.category(state) == BlockStateTable.HAZARD) continue;
            return new int[]{bx, by, bz};
        }
        // Not aiming at anything breakable: use the floor they stand on.
        int fx = (int) Math.floor(px);
        int fz = (int) Math.floor(pz);
        int fy = (int) Math.floor(py) - 1;
        int under = b.world.getBlock(fx, fy, fz);
        if (!BlockStateTable.passable(under)
                && BlockStateTable.category(under) != BlockStateTable.HAZARD) {
            return new int[]{fx, fy, fz};
        }
        return null;
    }

    /** First non-passable block along the bot's current facing, within reach. */
    private static int[] blockInFront(MinecraftBot b) {
        double ex, ey, ez;
        float yaw, pitch;
        synchronized (b.positionLock) {
            ex = b.playerX;
            ey = b.playerY + EYE_HEIGHT;
            ez = b.playerZ;
            yaw = b.playerYaw;
            pitch = b.playerPitch;
        }
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double dx = -Math.sin(yr) * Math.cos(pr);
        double dy = -Math.sin(pr);
        double dz = Math.cos(yr) * Math.cos(pr);
        for (double t = 0.2; t <= REACH; t += 0.1) {
            int bx = (int) Math.floor(ex + dx * t);
            int by = (int) Math.floor(ey + dy * t);
            int bz = (int) Math.floor(ez + dz * t);
            int state = b.world.getBlock(bx, by, bz);
            if (!BlockStateTable.passable(state)) return new int[]{bx, by, bz};
        }
        return null;
    }

    /** Points the bot at the block and sends the rotation to the server. */
    private void face(MinecraftBot b, int x, int y, int z) throws Exception {
        synchronized (b.positionLock) {
            double ex = b.playerX;
            double ey = b.playerY + EYE_HEIGHT;
            double ez = b.playerZ;
            double dx = (x + 0.5) - ex;
            double dy = (y + 0.5) - ey;
            double dz = (z + 0.5) - ez;
            double horizontal = Math.hypot(dx, dz);
            b.playerYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            b.playerPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
            b.sendPositionAndTickEnd(true);
        }
    }

    private static int faceDirection(MinecraftBot b, int x, int y, int z) {
        double ex, ey, ez;
        synchronized (b.positionLock) {
            ex = b.playerX;
            ey = b.playerY + EYE_HEIGHT;
            ez = b.playerZ;
        }
        // NGK direction ids: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
        double dx = ex - (x + 0.5);
        double dy = ey - (y + 0.5);
        double dz = ez - (z + 0.5);
        double adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
        if (ady >= adx && ady >= adz) return dy > 0 ? 1 : 0;
        if (adx >= adz) return dx > 0 ? 5 : 4;
        return dz > 0 ? 3 : 2;
    }

    private void sendAction(MinecraftBot b, int action, int x, int y, int z, int dir) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        PacketWriter.varInt(body, action);
        PacketWriter.longVal(body, Pos.blockLong(x, y, z));
        body.write(dir & 0xFF);
        PacketWriter.varInt(body, nextSequence());
        b.sendPlay(PacketIds.SB_PLAYER_ACTION, body.toByteArray());
    }

    private int nextSequence() {
        int s = sequence + 1;
        if (s <= 0 || s > 1_000_000) s = 1;
        sequence = s;
        return s;
    }
}
