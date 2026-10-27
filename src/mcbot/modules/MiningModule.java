package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;
import mcbot.world.BlockStateTable;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Block breaking ("mining").
 *
 * Chat commands:
 *   "mine x y z" / "dig x y z" / "break x y z"  -> that exact block
 *   "mine" / "mine here" / "mine this" / "dig that" ... -> the block the sender
 *        is looking at, or the block under their feet; the bot walks there first
 *   "mine ahead|forward|front" -> the first solid block in front of the bot
 *
 * No coordinates are needed for the "here" form, which is the normal way to use
 * it: the sender's entity position and rotation are tracked from the server's
 * entity packets, so the bot knows what they are aiming at.
 *
 * The actual break is server-authoritative: the bot sends player_action
 * START_DESTROY_BLOCK, the server accumulates destroy progress each tick from
 * the player's held item and the block hardness, and the block disappears when
 * progress reaches 1.0. We do not know the hardness or the held tool, so the
 * bot polls the client-side world for the block turning to air and, on each
 * failed attempt, retries STOP + START with a longer wait (exponential backoff).
 * Success is only reported when the block really changed in the world cache.
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

    // Serverbound play packet ids (protocol 777).
    private static final int PLAYER_ACTION = 0x29;
    private static final int PUNCH = 0x2E;

    private static final int ACTION_START_DESTROY_BLOCK = 0;
    private static final int ACTION_ABORT_DESTROY_BLOCK = 2;
    private static final int ACTION_STOP_DESTROY_BLOCK = 3;

    /** breakBlock outcomes. */
    private static final int BREAK_CANCELLED = -1;
    private static final int BREAK_FAILED = 0;
    private static final int BREAK_DONE = 1;

    private static final double REACH = 4.5;
    private static final double LOOK_REACH = 5.0;
    private static final double EYE_HEIGHT = 1.62;
    private static final long TICK_MS = 50L;
    private static final int MAX_ATTEMPTS = 7;

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
        if (packetId != 0x42) return; // ClientboundPlayerChatPacket
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
        if (BlockStateTable.passable(state)) {
            return "There's nothing to mine at " + x + " " + y + " " + z + ".";
        }
        if (BlockStateTable.category(state) == BlockStateTable.HAZARD) {
            return "I'm not mining a hazard block at " + x + " " + y + " " + z + ".";
        }

        stop();
        mining = true;
        long gen = ++generation;
        worker = new Thread(() -> runFixed(b, x, y, z, gen), "mining");
        worker.start();
        return "Mining " + x + " " + y + " " + z + ".";
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
                        + (target == null ? " -> no target" : " -> target " + target[0] + " " + target[1] + " " + target[2]));
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
     * START + poll + STOP, with a longer wait each attempt. Only reports success
     * when the block actually changed in the client-side world.
     */
    private int breakBlock(MinecraftBot b, int x, int y, int z, long gen) throws Exception {
        int dir = faceDirection(b, x, y, z);
        int original = b.world.getBlock(x, y, z);
        if (MinecraftBot.DEBUG) {
            double ex, ey, ez;
            synchronized (b.positionLock) {
                ex = b.playerX;
                ey = b.playerY + EYE_HEIGHT;
                ez = b.playerZ;
            }
            double dx = Math.max(0, Math.max((x) - ex, ex - (x + 1)));
            double dy = Math.max(0, Math.max((y) - ey, ey - (y + 1)));
            double dz = Math.max(0, Math.max((z) - ez, ez - (z + 1)));
            System.out.println("[mine] eye=" + ex + " " + ey + " " + ez
                    + " boxdr=" + dx + " " + dy + " " + dz
                    + " dist=" + Math.sqrt(dx * dx + dy * dy + dz * dz)
                    + " state=" + original + " dir=" + dir);
        }
        // The server accumulates destroy progress itself while a START is open:
        // progress = hardness-based speed * (serverTicks since START + 1). It is
        // committed by STOP (>= 0.7 destroys immediately, otherwise the server
        // finishes the break over the next ticks). So: START once, hold it for a
        // while, then STOP and poll for the block to actually turn to air. A
        // rejected START never sends a block_destruction packet, so there is
        // nothing to poll for and the round just times out.
        for (int round = 0; round < MAX_ATTEMPTS; round++) {
            if (gen != generation) return BREAK_CANCELLED;
            long holdMs = 2000L << Math.min(round, 3); // 2s .. 16s of progress
            sendAction(b, ACTION_START_DESTROY_BLOCK, x, y, z, dir);
            System.out.println("[mine] start " + x + " " + y + " " + z + " (round " + (round + 1)
                    + ", hold " + holdMs + " ms)");

            long holdUntil = System.currentTimeMillis() + holdMs;
            while (System.currentTimeMillis() < holdUntil) {
                if (gen != generation) {
                    sendAction(b, ACTION_ABORT_DESTROY_BLOCK, x, y, z, dir);
                    return BREAK_CANCELLED;
                }
                Thread.sleep(TICK_MS);
                sendPunch(b);
                if (b.world.getBlock(x, y, z) != original) return BREAK_DONE;
            }

            sendAction(b, ACTION_STOP_DESTROY_BLOCK, x, y, z, dir);
            long pollUntil = System.currentTimeMillis() + 12_000L;
            while (System.currentTimeMillis() < pollUntil) {
                if (gen != generation) return BREAK_CANCELLED;
                Thread.sleep(TICK_MS);
                if (b.world.getBlock(x, y, z) != original) return BREAK_DONE;
            }
        }
        sendAction(b, ACTION_ABORT_DESTROY_BLOCK, x, y, z, dir);
        Thread.sleep(TICK_MS * 2);
        return b.world.getBlock(x, y, z) != original ? BREAK_DONE : BREAK_FAILED;
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
        double ex, ey, ez;
        synchronized (b.positionLock) {
            ex = b.playerX;
            ey = b.playerY + EYE_HEIGHT;
            ez = b.playerZ;
        }
        double dx = (x + 0.5) - ex;
        double dy = (y + 0.5) - ey;
        double dz = (z + 0.5) - ez;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= REACH - 0.3;
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
        return BlockStateTable.passable(b.world.getBlock(x, y, z))
                && BlockStateTable.passable(b.world.getBlock(x, y + 1, z))
                && BlockStateTable.solid(b.world.getBlock(x, y - 1, z));
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
        if (!BlockStateTable.passable(under) && BlockStateTable.category(under) != BlockStateTable.HAZARD) {
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
        PacketWriter.longVal(body, blockPosLong(x, y, z));
        body.write(dir & 0xFF);
        PacketWriter.varInt(body, nextSequence());
        b.sendPlay(PLAYER_ACTION, body.toByteArray());
    }

    private void sendPunch(MinecraftBot b) throws Exception {
        b.sendPlay(PUNCH, new byte[0]);
    }

    private int nextSequence() {
        int s = sequence + 1;
        if (s <= 0 || s > 1_000_000) s = 1;
        sequence = s;
        return s;
    }

    /**
     * BlockPos.asLong() as of this protocol version: 26-bit x at bit 38, 12-bit
     * y at bit 0, 26-bit z at bit 12. (This is NOT the older
     * x << 38 | y << 26 | z layout: FriendlyByteBuf.writeBlockPos writes
     * BlockPos.asLong(), so the packing has to match exactly or the server
     * decodes a completely different position and silently rejects with
     * "too far".)
     */
    private static long blockPosLong(int x, int y, int z) {
        return (((long) (x & 0x3FFFFFF)) << 38)
                | ((long) (y & 0xFFF))
                | (((long) (z & 0x3FFFFFF)) << 12);
    }
}