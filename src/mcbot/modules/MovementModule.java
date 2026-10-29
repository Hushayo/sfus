package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketReader;
import mcbot.path.Pathfinder;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat-driven pathfinding: "goto x y z" / "path x y z" / "come" / "stop".
 *
 * Movement is sent one position packet per client tick. Every position packet
 * is immediately followed by client_tick_end, which clears the server's
 * per-tick "position received" flag. Without that flag reset a second position
 * packet in the same server tick disconnects the client with
 * multiplayer.disconnect.invalid_player_movement.
 *
 * Step up and step down are executed as separate vertical and horizontal
 * phases. The server resolves each packet's delta against real block
 * collisions, so a diagonal "up and forward" packet can be rejected while a
 * vertical packet followed by a horizontal packet is accepted.
 */
public class MovementModule implements BotModule {
    /**
     * Anchored on purpose. An unanchored pattern happily matched a message like
     * "goto -338 79 -261 757 29" (a macro that pastes coordinates *and*
     * rotation) and also matched any sentence that merely contained "go to".
     * The optional 4th/5th numbers are deliberately ignored: they are rotation,
     * not a target.
     */
    private static final Pattern GOTO = Pattern.compile(
            "(?i)^\\s*(?:goto|go\\s+to|move\\s+to|walk\\s+to|path\\s+to)\\s+" +
                    "(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)" +
                    "(?:\\s+(-?\\d+(?:\\.\\d+)?))?");

    private static final long MOVE_INTERVAL_MS = 50L;

    /**
     * Vanilla walking speed is 0.2158 blocks per tick (4.317 m/s). The old
     * value of 0.40 was 8 m/s - faster than a sprint - which is exactly the
     * kind of thing a proxy anti-cheat answers with a position correction, and
     * every correction forced a full replan.
     */
    private static final double HORIZONTAL_STEP = 0.2158;
    private static final double VERTICAL_UP_STEP = 0.42;
    private static final double VERTICAL_DOWN_STEP = 0.50;
    private static final double EPS = 0.02;
    private static final int MAX_REPLANS = 25;
    private static final int MAX_BLOCKED_EDGES = 512;

    /** A correction bigger than this is a teleport, not a rejected step. */
    private static final double TELEPORT_DISTANCE = 4.0;

    // Jump arc: apex ~1.15 (vanilla standing jump peaks at 1.2522), horizontal
    // speed 0.25 blocks/tick, at least 8 ticks airborne.
    private static final int JUMP_MIN_TICKS = 8;
    private static final int JUMP_TICKS_PER_BLOCK = 4;
    private static final double JUMP_APEX = 1.15;

    private volatile MinecraftBot bot;
    private volatile Thread worker;
    private volatile boolean moving = false;
    private volatile long generation = 0;
    private volatile boolean autoGotoScheduled = false;
    private volatile boolean announced = false;
    /** Set for AI / auto-goto walks so they do not spam chat on arrival. */
    private volatile boolean quiet = false;
    /** When true the path must end on the exact target cell (item pickup). */
    private volatile boolean tightGoal = false;

    /**
     * Directed path edges the server already rejected during the current goto.
     * Fed back into the pathfinder so a rejected jump is routed around instead
     * of being retried identically forever.
     */
    private final Set<Long> blockedEdges = ConcurrentHashMap.newKeySet();

    /** Last position we actually told the server about, for correction sizing. */
    private volatile double lastSentX;
    private volatile double lastSentY;
    private volatile double lastSentZ;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    /**
     * AI-driven "come here": walks to the player who sent the current message and
     * returns a short status line for the model to talk about.
     */
    public String comeTo(UUID sender) {
        MinecraftBot b = bot;
        if (b == null) return "Not connected yet.";
        if (sender == null) return "I need coordinates like 'goto x y z' - I can't see you.";
        double[] pos = b.entityTracker.positionOf(sender);
        if (pos == null) {
            return "I can't see that player's position; I need coordinates like 'goto x y z'.";
        }
        System.out.println("[move] ai come from " + sender + " at "
                + pos[0] + " " + pos[1] + " " + pos[2]);
        tightGoal = false;
        queueGoto(b, pos[0], pos[1], pos[2], true);
        return "Walking to the player at " + (int) pos[0] + " " + (int) pos[1]
                + " " + (int) pos[2] + ".";
    }

    /** AI-driven "walk to x y z". Returns a short status line for the model. */
    public String goTo(double x, double y, double z) {
        MinecraftBot b = bot;
        if (b == null) return "Not connected yet.";
        System.out.println("[move] ai goto " + x + " " + y + " " + z);
        tightGoal = false;
        queueGoto(b, x, y, z, true);
        return "Walking to " + (int) x + " " + (int) y + " " + (int) z + ".";
    }

    /**
     * Walks to a specific cell rather than "somewhere near" it.
     *
     * Used for picking up a dropped item, where the server's collection radius
     * is about one block: the normal pathfinder is happy to stop two blocks away
     * and report success, which leaves the drop on the ground forever.
     */
    public String goToTight(double x, double y, double z) {
        MinecraftBot b = bot;
        if (b == null) return "Not connected yet.";
        tightGoal = true;
        queueGoto(b, x, y, z, true);
        return "Stepping to " + (int) x + " " + (int) y + " " + (int) z + ".";
    }

    /** AI-driven stop. Returns a short status line for the model. */
    public String stopMoving() {
        stop();
        return "Stopped moving.";
    }

    /** True while a walk is in progress (used by other modules to wait). */
    public boolean isMoving() {
        return moving;
    }

    /**
     * Turns to face a world point and reports it to the server. Used by the
     * combat module; it does not move the bot.
     */
    public void faceTowards(double tx, double ty, double tz) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return;
        synchronized (b.moveLock) {
            synchronized (b.positionLock) {
                double dx = tx - b.playerX;
                double dy = ty - (b.playerY + 1.62);
                double dz = tz - b.playerZ;
                b.playerYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                b.playerPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                try {
                    b.sendPositionAndTickEnd(true);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        // Movement is driven by intents (ChatCommands -> jobs) and by the AI
        // tool layer, not by chat parsing here. The only thing this module
        // watches for itself is the one-shot BOT_GOTO environment command,
        // which exists so a freshly spawned bot can walk somewhere on its own.
        maybeScheduleAutoGoto(bot);
    }

    /** Retained for the AI tool layer, which wants to know if a text is a walk request. */
    public static boolean isMovementCommand(String message) {
        if (message == null) return false;
        String lower = message.trim().toLowerCase(Locale.ROOT);
        return lower.equals("stop") || lower.equals("cancel")
                || lower.equals("stop pathing") || lower.equals("stop moving")
                || lower.equals("come") || lower.equals("come here")
                || GOTO.matcher(message).find();
    }

    private void maybeScheduleAutoGoto(MinecraftBot bot) {
        if (autoGotoScheduled) return;
        String command = bot.autoGoto;
        if (command == null || command.isBlank()) return;
        // Wait for both the position teleport and the chunk/player_loaded
        // handshake. The extra delay lets /register + /login finish first.
        if (!bot.playerLoaded || !bot.hasPlayerPosition) return;
        autoGotoScheduled = true;
        String cmd = command.trim();
        new Thread(() -> {
            try {
                Thread.sleep(4000L);
                queueGoto(bot, cmd);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "auto-goto").start();
    }

    private void queueGoto(MinecraftBot bot, String command) {
        Matcher m = GOTO.matcher(command);
        if (!m.find()) {
            System.out.println("[move] bad goto command: " + command);
            return;
        }
        double tx = Double.parseDouble(m.group(1));
        double ty;
        double tz;
        if (m.group(3) != null) {
            ty = Double.parseDouble(m.group(2));
            tz = Double.parseDouble(m.group(3));
        } else {
            ty = currentY(bot);
            tz = Double.parseDouble(m.group(2));
        }
        queueGoto(bot, tx, ty, tz, true);
    }

    private void queueGoto(MinecraftBot bot, double tx, double ty, double tz, boolean quiet) {
        stop();
        blockedEdges.clear();
        long gen = ++generation;
        moving = true;
        announced = false;
        this.quiet = quiet;
        worker = new Thread(() -> runPath(bot, tx, ty, tz, gen), "pathfinder");
        worker.start();
    }

    private void runPath(MinecraftBot bot, double tx, double ty, double tz, long gen) {
        try {
            long deadline = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < deadline && gen == generation
                    && (!bot.hasPlayerPosition || !bot.playerLoaded)) {
                Thread.sleep(100L);
            }
            if (gen != generation) return;
            if (!bot.hasPlayerPosition) {
                fail(bot, "No position from server.");
                return;
            }
            if (!bot.playerLoaded) {
                fail(bot, "Client load handshake incomplete.");
                return;
            }

            double startX;
            double startZ;
            synchronized (bot.positionLock) {
                startX = bot.playerX;
                startZ = bot.playerZ;
            }
            double distance = Math.hypot(tx - startX, tz - startZ);
            if (distance > 256.0) {
                fail(bot, "Target too far (max 256 blocks).");
                return;
            }

            int attempts = 0;
            int replans = 0;
            while (gen == generation) {
                if (replans > MAX_REPLANS) {
                    fail(bot, "Too many server corrections.");
                    return;
                }
                if (attempts++ > 60) {
                    fail(bot, "Timed out waiting for chunks.");
                    return;
                }

                double px;
                double py;
                double pz;
                long teleports;
                synchronized (bot.positionLock) {
                    px = bot.playerX;
                    py = bot.playerY;
                    pz = bot.playerZ;
                    teleports = bot.teleportCounter;
                }

                Pathfinder.Result result = tightGoal
                        ? Pathfinder.findPathTight(bot.world, px, py, pz, tx, ty, tz, blockedEdges)
                        : Pathfinder.findPath(bot.world, px, py, pz, tx, ty, tz, blockedEdges);
                if (gen != generation) return;

                if (result.ok()) {
                    System.out.println("[move] path: " + result.path.size() + " nodes -> "
                            + result.goalX + " " + result.goalY + " " + result.goalZ);
                    if (distance > 1.5 && !announced && !quiet) {
                        announced = true;
                        bot.sendChat("Pathing to " + (int) tx + " " + (int) ty + " " + (int) tz + "...");
                    }
                    if (followPath(bot, result.path, tx, ty, tz, gen, teleports)) {
                        replans++;
                        continue;
                    }
                    return;
                }

                if (result.error != null && result.error.contains("chunks not loaded")) {
                    Thread.sleep(500L);
                    continue;
                }
                fail(bot, result.error == null ? "No path." : result.error);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (gen == generation) {
                System.out.println("[move] error: " + e);
                fail(bot, "Pathfinding error.");
            }
        } finally {
            if (gen == generation) moving = false;
        }
    }

    /**
     * Executes one path. Returns true when the caller should recompute the path
     * (the server corrected/teleported us), false when finished or failed.
     */
    private boolean followPath(MinecraftBot bot, List<Pathfinder.Node> path,
                               double tx, double ty, double tz, long gen,
                               long startTeleports) throws Exception {
        if (path.size() <= 1) {
            arrived(bot, tx, ty, tz);
            return false;
        }

        int index = 1;
        long lastTeleports = startTeleports;
        long started = System.currentTimeMillis();
        long timeout = Math.max(20_000L, path.size() * 500L);

        // Progress watchdog. The previous version compared the *intended*
        // position with the previous intended position, which always differs by
        // one step, so it could never detect that the server was rejecting every
        // move. This tracks the distance to the current path node instead: if it
        // stops shrinking while no teleport arrives, the bot is wedged.
        int stalled = 0;
        double best = Double.MAX_VALUE;

        while (gen == generation && moving) {
            if (System.currentTimeMillis() - started > timeout) {
                fail(bot, "Movement timed out.");
                return false;
            }
            if (!bot.playerLoaded) {
                Thread.sleep(50L);
                continue;
            }

            double x;
            double y;
            double z;
            float yaw;
            float pitch;
            long teleports;
            synchronized (bot.positionLock) {
                x = bot.playerX;
                y = bot.playerY;
                z = bot.playerZ;
                yaw = bot.playerYaw;
                pitch = bot.playerPitch;
                teleports = bot.teleportCounter;
            }
            if (teleports != lastTeleports) {
                return replan(path, index, blockedEdges, bot);
            }

            Pathfinder.Node prev = path.get(index - 1);
            Pathfinder.Node node = path.get(index);

            double toNode = Math.hypot(node.x + 0.5 - x, node.z + 0.5 - z)
                    + Math.abs(node.y - y);
            if (toNode < best - 0.05) {
                best = toNode;
                stalled = 0;
            } else if (++stalled > 120) { // ~6 s without closing the gap
                fail(bot, "Stuck against a block.");
                return false;
            }

            int jumpDist = Math.abs(node.x - prev.x) + Math.abs(node.z - prev.z);
            if (jumpDist > 1 || node.y > prev.y) {
                // Jump edge. Settle on the takeoff block centre first (the jump
                // arc is scripted from there), then run the arc.
                double pcx = prev.x + 0.5;
                double pcz = prev.z + 0.5;
                double ph = Math.hypot(pcx - x, pcz - z);
                if (ph > 0.05) {
                    double step = Math.min(HORIZONTAL_STEP, ph);
                    if (!sendStep(bot, x + (pcx - x) / ph * step, prev.y,
                            z + (pcz - z) / ph * step, yawTo(pcx - x, pcz - z, yaw), pitch,
                            true, lastTeleports)) {
                        return replan(path, index, blockedEdges, bot);
                    }
                    Thread.sleep(MOVE_INTERVAL_MS);
                    continue;
                }
                if (jumpTo(bot, prev, node, yaw, lastTeleports, gen)) {
                    return replan(path, index, blockedEdges, bot);
                }
                index++;
                best = Double.MAX_VALUE;
                stalled = 0;
                if (index >= path.size()) {
                    arrived(bot, tx, ty, tz);
                    return false;
                }
                continue;
            }

            double cx = node.x + 0.5;
            double cz = node.z + 0.5;
            double dx = cx - x;
            double dz = cz - z;
            double dy = node.y - y;
            double horizontal = Math.hypot(dx, dz);

            // The pathfinder routes through doors, so somebody has to open them.
            // Without this the bot walks into a shut door and the server just
            // refuses the step, which reads as "no path" to everything after.
            openDoorIfNeeded(bot, node.x, node.y, node.z);

            if (horizontal < EPS && Math.abs(dy) < EPS) {
                if (!sendStep(bot, cx, node.y, cz, yaw, pitch, true, lastTeleports)) {
                    return replan(path, index, blockedEdges, bot);
                }
                index++;
                best = Double.MAX_VALUE;
                stalled = 0;
                if (index >= path.size()) {
                    arrived(bot, tx, ty, tz);
                    return false;
                }
                continue;
            }

            // Straight-line guard. Between two path nodes the bot should travel
            // along one world axis only. When it is offset perpendicular to that
            // axis (e.g. after a server correction or a jump landing), a direct
            // line to the node centre is diagonal and grazes the corner block of
            // the next column; the server then rejects the move and the bot looks
            // stuck. Slide back onto the previous node's centre line first.
            if (Math.abs(dx) > EPS || Math.abs(dz) > EPS) {
                double pcx = prev.x + 0.5;
                double pcz = prev.z + 0.5;
                boolean alongX = Math.abs(dx) >= Math.abs(dz);
                double align = alongX ? (pcz - z) : (pcx - x);
                if (Math.abs(align) > 0.06) {
                    double step = Math.min(HORIZONTAL_STEP, Math.abs(align));
                    double ax = alongX ? x : x + Math.signum(align) * step;
                    double az = alongX ? z + Math.signum(align) * step : z;
                    if (!sendStep(bot, ax, y, az, yawTo(ax - x, az - z, yaw), pitch,
                            true, lastTeleports)) {
                        return replan(path, index, blockedEdges, bot);
                    }
                    Thread.sleep(MOVE_INTERVAL_MS);
                    continue;
                }
            }

            double nx = x;
            double ny = y;
            double nz = z;
            boolean onGround = true;

            if (dy > EPS) {
                // Center inside the previous column before going up. The
                // vertical move then cannot clip a neighbouring column.
                double pcx = prev.x + 0.5;
                double pcz = prev.z + 0.5;
                double pdx = pcx - x;
                double pdz = pcz - z;
                double ph = Math.hypot(pdx, pdz);
                if (ph > 0.05) {
                    double step = Math.min(HORIZONTAL_STEP, ph);
                    nx = x + pdx / ph * step;
                    nz = z + pdz / ph * step;
                    yaw = yawTo(pdx, pdz, yaw);
                } else {
                    ny = Math.min(node.y, y + VERTICAL_UP_STEP);
                    yaw = yawTo(dx, dz, yaw);
                    onGround = false;
                }
            } else if (dy < -EPS) {
                // Walk to the target column first, then drop down it. The
                // ground we are standing on would block a vertical move made
                // before leaving the old column.
                if (horizontal > 0.05) {
                    double step = Math.min(HORIZONTAL_STEP, horizontal);
                    nx = x + dx / horizontal * step;
                    nz = z + dz / horizontal * step;
                    yaw = yawTo(dx, dz, yaw);
                } else {
                    ny = Math.max(node.y, y - VERTICAL_DOWN_STEP);
                    yaw = yawTo(dx, dz, yaw);
                    onGround = ny <= node.y + EPS;
                }
            } else {
                double step = Math.min(HORIZONTAL_STEP, horizontal);
                nx = x + dx / horizontal * step;
                nz = z + dz / horizontal * step;
                yaw = yawTo(dx, dz, yaw);
            }

            if (!sendStep(bot, nx, ny, nz, yaw, pitch, onGround, lastTeleports)) {
                return replan(path, index, blockedEdges, bot);
            }

            Thread.sleep(MOVE_INTERVAL_MS);
        }
        return false;
    }

    /**
     * Records that the move along the current edge did not stick and asks the
     * caller to recompute. Only a *correction* blacklists the edge: a real
     * teleport (plugin warp, portal, respawn) also bumps the teleport counter
     * but must not poison the graph, or the bot will refuse to use the only
     * path back.
     */
    private boolean replan(List<Pathfinder.Node> path, int index, Set<Long> blockedEdges,
                           MinecraftBot bot) {
        double dx = 0;
        double dy = 0;
        double dz = 0;
        synchronized (bot.positionLock) {
            dx = bot.playerX - lastSentX;
            dy = bot.playerY - lastSentY;
            dz = bot.playerZ - lastSentZ;
        }
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean realTeleport = moved > TELEPORT_DISTANCE;

        System.out.println("[move] server corrected position by " + String.format(Locale.ROOT, "%.2f", moved)
                + (realTeleport ? " (teleport, not blacklisting)" : " (replanning)"));

        if (!realTeleport && index >= 1 && index < path.size()) {
            Pathfinder.Node a = path.get(index - 1);
            Pathfinder.Node b = path.get(index);
            if (blockedEdges.size() < MAX_BLOCKED_EDGES) {
                blockedEdges.add(Pathfinder.edgeKey(a.x, a.y, a.z, b.x, b.y, b.z));
            }
            System.out.println("[move] blacklisted edge " + a.x + " " + a.y + " " + a.z
                    + " -> " + b.x + " " + b.y + " " + b.z
                    + " (blocked " + blockedEdges.size() + ")");
        }
        return true;
    }

    /**
     * Runs one jump arc from {@code prev} to {@code node}.
     *
     * The arc is integrated the way vanilla does it, not drawn as a symmetric
     * parabola: the jump starts at vy = 0.42 and each tick applies
     * {@code vy = (vy - 0.08) * 0.98}. That rise-then-fall shape is why a jump
     * covers about 2.4 blocks in 12 ticks, and why the old fixed parabola kept
     * landing short - the server simulated the real curve, disagreed with the
     * reported position, and sent a correction that forced a replan.
     *
     * Returns true when the server corrected our position and the path must be
     * recomputed.
     */
    private boolean jumpTo(MinecraftBot bot, Pathfinder.Node prev, Pathfinder.Node node,
                           float yaw, long expectedTeleports, long gen) throws Exception {
        double x0 = prev.x + 0.5;
        double z0 = prev.z + 0.5;
        double y0 = prev.y;
        double x1 = node.x + 0.5;
        double z1 = node.z + 0.5;
        double y1 = node.y;

        // Integrate the vertical arc first to find the airtime and the height
        // for each tick. Vanilla order: move by the velocity, then apply
        // gravity to it. Doing it the other way round loses the first tick's
        // full rise and tops the arc out at 0.83 blocks - below the block a
        // step-up is trying to climb, so every climb landed short.
        double[] arcY = new double[40];
        double vy = 0.42;
        double sy = y0;
        double clearAt = Math.max(y0, y1) + 0.05;
        int riseTicks = 0;
        int airtime = 0;
        for (int t = 1; t <= 39; t++) {
            sy += vy;
            vy = (vy - 0.08) * 0.98;
            arcY[t] = sy;
            if (riseTicks == 0 && sy >= clearAt) riseTicks = t;
            if (riseTicks > 0 && vy < 0 && sy <= y1) {
                airtime = t;
                break;
            }
        }
        if (airtime == 0) airtime = 12;
        if (riseTicks == 0 || riseTicks >= airtime) riseTicks = Math.max(1, airtime / 3);
        int moveTicks = Math.max(1, airtime - riseTicks);

        float jumpYaw = yawTo(x1 - x0, z1 - z0, yaw);
        System.out.println("[move] jump " + Math.abs(node.x - prev.x) + "x"
                + Math.abs(node.z - prev.z) + " dy=" + (int) Math.round(y1 - y0)
                + " -> " + node.x + " " + node.y + " " + node.z
                + " over " + airtime + " ticks");

        try {
            bot.sendInput(MinecraftBot.INPUT_FORWARD | MinecraftBot.INPUT_JUMP);
        } catch (Exception ignored) {}
        try {
            for (int i = 1; i <= airtime; i++) {
                if (gen != generation) return false;
                boolean last = i == airtime;
                // Rise in place until the feet are above both the takeoff and
                // the landing level, and only then travel sideways: moving
                // diagonally through the corner of the block being climbed is
                // what the server's collision check rejects.
                double t = i <= riseTicks ? 0.0 : (double) (i - riseTicks) / moveTicks;
                double nx = x0 + (x1 - x0) * t;
                double nz = z0 + (z1 - z0) * t;
                double ny = last ? y1 : arcY[i];
                float pitch;
                synchronized (bot.positionLock) {
                    pitch = bot.playerPitch;
                }
                if (!sendStep(bot, nx, ny, nz, jumpYaw, pitch, last, expectedTeleports)) {
                    return true;
                }
                if (i == 1) {
                    try {
                        bot.sendInput(MinecraftBot.INPUT_FORWARD);
                    } catch (Exception ignored) {}
                }
                Thread.sleep(MOVE_INTERVAL_MS);
            }
        } finally {
            try {
                bot.sendInput(0);
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Right-clicks a shut door or fence gate the path is about to walk through.
     *
     * The pathfinder treats closed doors as traversable because a player would
     * simply open one. That only works if the movement actually opens it: a shut
     * door is a solid block, so the step is refused by the server and the bot
     * looks stuck against a wall it decided to walk into.
     */
    private void openDoorIfNeeded(MinecraftBot bot, int x, int y, int z) {
        if (bot.interaction == null || !bot.hasPlayerPosition) return;
        int feet = bot.world.getBlock(x, y, z);
        if (!isShutDoor(feet)) return;
        if (!bot.interaction.inReach(x, y, z)) return;
        long now = System.currentTimeMillis();
        if (now - lastDoorMs < 600L) return;
        lastDoorMs = now;
        System.out.println("[move] opening door at " + x + " " + y + " " + z);
        bot.interaction.useBlock(x, y, z);
    }

    private static boolean isShutDoor(int state) {
        String name = mcbot.registry.Blocks.shortName(state);
        if (name == null) return false;
        if (!name.endsWith("_door") && !name.endsWith("_fence_gate")) return false;
        if (name.startsWith("iron_")) return false;
        return !mcbot.registry.Blocks.hasProperty(state, "open", "true");
    }

    private volatile long lastDoorMs = 0L;

    /**
     * Writes the new position and sends move_player_pos_rot + client_tick_end.
     * Returns false if a teleport was processed while we were waiting.
     *
     * The pitch is passed through instead of being forced to 0: the old code
     * reset it on every step, which meant the bot could never look up or down
     * and any gaze logic would be erased 20 times per second.
     */
    private boolean sendStep(MinecraftBot bot, double x, double y, double z,
                             float yaw, float pitch, boolean onGround,
                             long expectedTeleports) throws Exception {
        synchronized (bot.moveLock) {
            synchronized (bot.positionLock) {
                if (bot.teleportCounter != expectedTeleports) return false;
                lastSentX = x;
                lastSentY = y;
                lastSentZ = z;
                bot.playerX = x;
                bot.playerY = y;
                bot.playerZ = z;
                bot.playerYaw = yaw;
                bot.playerPitch = pitch;
                bot.hasPlayerPosition = true;
                bot.sendPositionAndTickEnd(onGround);
                return true;
            }
        }
    }

    private void arrived(MinecraftBot bot, double tx, double ty, double tz) throws Exception {
        moving = false;
        double x;
        double y;
        double z;
        synchronized (bot.positionLock) {
            x = bot.playerX;
            y = bot.playerY;
            z = bot.playerZ;
        }
        System.out.println("[move] arrived at " + x + " " + y + " " + z);
        // One grounded packet after the walk so the server does not leave the
        // bot flagged as falling.
        synchronized (bot.moveLock) {
            synchronized (bot.positionLock) {
                bot.sendPositionAndTickEnd(true);
            }
        }
        if (quiet) return;
        bot.sendChat("Arrived at " + (int) Math.floor(x) + " " + (int) Math.floor(y)
                + " " + (int) Math.floor(z));
    }

    private void fail(MinecraftBot bot, String reason) {
        moving = false;
        System.out.println("[move] failed: " + reason);
        try {
            bot.sendChat("Path failed: " + reason);
        } catch (Exception ignored) {}
    }

    public void stop() {
        moving = false;
        generation++;
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    private static double currentY(MinecraftBot bot) {
        synchronized (bot.positionLock) {
            return bot.playerY;
        }
    }

    private static float yawTo(double dx, double dz, float fallback) {
        if (Math.abs(dx) < 1.0E-6 && Math.abs(dz) < 1.0E-6) return fallback;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
