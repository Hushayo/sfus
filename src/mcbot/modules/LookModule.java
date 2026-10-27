package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.modules.EntityTrackerModule.Pose;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Where the bot is looking.
 *
 * Three behaviours, in priority order:
 *
 *  1. ALERT - something just hit us. Snap the camera onto the attacker so the
 *     combat module and the player can both see what the bot is dealing with.
 *  2. WATCH - another entity's gaze ray actually intersects our hitbox. The bot
 *     turns and looks back at them, eye to eye. This is the "if you look at me,
 *     I look at you" behaviour.
 *  3. IDLE  - nothing has happened for a while. The camera wanders: sky, then
 *     horizon, then the ground, occasionally settling on a nearby entity, so an
 *     AFK bot looks alive instead of frozen at pitch 0.
 *
 * The pathfinder owns rotation while it walks, so this module stands down the
 * moment {@link MovementModule#isMoving()} is true.
 */
public class LookModule implements BotModule {
    private static final double EYE_HEIGHT = 1.62;
    private static final double BOT_HEIGHT = 1.8;
    private static final double BOT_HALF_WIDTH = 0.35;
    private static final double MAX_WATCH_DISTANCE = 48.0;

    /** ~360 deg/s when returning a stare, ~110 deg/s when idly drifting. */
    private static final double WATCH_TURN_PER_TICK = 18.0;
    private static final double WANDER_TURN_PER_TICK = 5.5;

    private static final long IDLE_AFTER_MS = 6_000L;
    private static final long SEND_INTERVAL_MS = 90L;
    private static final long HEARTBEAT_MS = 1_000L;

    private final Random random = new Random();

    private volatile MinecraftBot bot;
    private volatile long lastInteractionMs = System.currentTimeMillis();
    private volatile long lastSendMs = 0L;

    private volatile int alertEntityId = -1;
    private volatile long alertUntilMs = 0L;

    private volatile UUID glanceUuid = null;
    private volatile long glanceUntilMs = 0L;

    private float gazeYaw;
    private float gazePitch;
    private float targetYaw;
    private float targetPitch;
    private boolean primed = false;
    private long nextWanderMs = 0L;
    private long lastPoseSeenMs = 0L;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    /** Called when someone speaks to the bot: turn towards them. */
    public void glanceAt(UUID uuid, long durationMs) {
        this.glanceUuid = uuid;
        this.glanceUntilMs = System.currentTimeMillis() + durationMs;
        noteInteraction();
    }

    /** Called by the combat module when something attacks us. */
    public void alert(int entityId, long durationMs) {
        this.alertEntityId = entityId;
        this.alertUntilMs = System.currentTimeMillis() + durationMs;
        noteInteraction();
    }

    public void noteInteraction() {
        lastInteractionMs = System.currentTimeMillis();
    }

    /** Current gaze, for diagnostics and for the AI prompt. */
    public float yaw() {
        return gazeYaw;
    }

    public float pitch() {
        return gazePitch;
    }

    @Override
    public void onTick(MinecraftBot bot) {
        if (bot == null || !bot.playerLoaded || !bot.hasPlayerPosition || bot.dead) return;
        if (bot.movement.isMoving()) {
            // The pathfinder is driving rotation; adopt its heading so we do not
            // snap back to a stale gaze when the walk ends.
            synchronized (bot.positionLock) {
                gazeYaw = bot.playerYaw;
                gazePitch = bot.playerPitch;
            }
            primed = true;
            return;
        }

        long now = System.currentTimeMillis();
        if (!primed) {
            synchronized (bot.positionLock) {
                gazeYaw = bot.playerYaw;
                gazePitch = bot.playerPitch;
            }
            targetYaw = gazeYaw;
            targetPitch = gazePitch;
            primed = true;
        }

        double maxStep = WANDER_TURN_PER_TICK;
        boolean watching = false;

        Pose alertPose = alertEntityId >= 0 && now < alertUntilMs
                ? bot.entityTracker.pose(alertEntityId) : null;
        Pose glancePose = glanceUuid != null && now < glanceUntilMs
                ? poseOf(bot, glanceUuid) : null;

        if (alertPose != null) {
            aimAt(bot, alertPose.x, alertPose.y + EYE_HEIGHT, alertPose.z);
            maxStep = WATCH_TURN_PER_TICK * 1.6;
            watching = true;
        } else if (glancePose != null) {
            aimAt(bot, glancePose.x, glancePose.y + EYE_HEIGHT, glancePose.z);
            maxStep = WATCH_TURN_PER_TICK;
            watching = true;
        } else {
            Pose watcher = findWatcher(bot);
            if (watcher != null) {
                aimAt(bot, watcher.x, watcher.y + EYE_HEIGHT, watcher.z);
                maxStep = WATCH_TURN_PER_TICK;
                watching = true;
                lastInteractionMs = now; // being looked at counts as attention
                if (now - lastPoseSeenMs > 1500L) {
                    lastPoseSeenMs = now;
                    System.out.println("[look] " + watcher.uuid + " is looking at me, looking back");
                }
            }
        }

        if (!watching) {
            if (now - lastInteractionMs > IDLE_AFTER_MS) {
                wander(bot, now);
            } else {
                // Recently busy: settle on the horizon rather than staring.
                targetPitch = 0f;
                targetYaw = gazeYaw;
            }
        }

        boolean changed = stepTowards(maxStep);
        if (!changed && now - lastSendMs < HEARTBEAT_MS) return;
        if (now - lastSendMs < SEND_INTERVAL_MS && !changed) return;

        synchronized (bot.moveLock) {
            if (bot.movement.isMoving() || bot.dead || !bot.playerLoaded) return;
            synchronized (bot.positionLock) {
                bot.playerYaw = gazeYaw;
                bot.playerPitch = gazePitch;
                try {
                    bot.sendLook();
                    lastSendMs = now;
                } catch (Exception e) {
                    System.out.println("[look] send failed: " + e);
                }
            }
        }
    }

    /** Moves gaze towards the target by at most maxStep degrees. */
    private boolean stepTowards(double maxStep) {
        double dyaw = wrapDegrees(targetYaw - gazeYaw);
        double dpitch = targetPitch - gazePitch;
        boolean changed = Math.abs(dyaw) > 0.25 || Math.abs(dpitch) > 0.25;
        if (!changed) {
            gazeYaw = targetYaw;
            gazePitch = targetPitch;
            return false;
        }
        gazeYaw = (float) wrapDegrees(gazeYaw + clamp(dyaw, maxStep));
        gazePitch = (float) clamp(gazePitch + clamp(dpitch, maxStep), -90, 90);
        return true;
    }

    /** Idle camera drift: sky, horizon, ground, or whatever is nearby. */
    private void wander(MinecraftBot bot, long now) {
        if (now < nextWanderMs) return;
        double r = random.nextDouble();

        if (r < 0.38) {
            // Up at the sky / clouds.
            targetPitch = (float) (-30 - random.nextDouble() * 32);
            targetYaw = (float) wrapDegrees(gazeYaw + (random.nextDouble() * 2 - 1) * 70);
        } else if (r < 0.62) {
            // Sweep the horizon.
            targetPitch = (float) (-6 + random.nextDouble() * 12);
            targetYaw = (float) wrapDegrees(gazeYaw + (random.nextDouble() * 2 - 1) * 150);
        } else if (r < 0.80) {
            // Down at the ground in front of the feet.
            targetPitch = (float) (28 + random.nextDouble() * 28);
            targetYaw = (float) wrapDegrees(gazeYaw + (random.nextDouble() * 2 - 1) * 100);
        } else {
            Pose near = randomNearby(bot);
            if (near != null) {
                aimAt(bot, near.x, near.y + EYE_HEIGHT, near.z);
            } else {
                targetPitch = (float) (-20 + random.nextDouble() * 30);
                targetYaw = (float) wrapDegrees(gazeYaw + (random.nextDouble() * 2 - 1) * 120);
            }
        }
        nextWanderMs = now + 1_800L + (long) (random.nextDouble() * 3_400);
    }

    private void aimAt(MinecraftBot bot, double tx, double ty, double tz) {
        double ex, ey, ez;
        synchronized (bot.positionLock) {
            ex = bot.playerX;
            ey = bot.playerY + EYE_HEIGHT;
            ez = bot.playerZ;
        }
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
    }

    /**
     * The closest entity whose look direction actually intersects our hitbox.
     * No entity-type table needed: any tracked entity with a rotation can look.
     */
    private Pose findWatcher(MinecraftBot bot) {
        List<Pose> poses = bot.entityTracker.poses();
        if (poses.isEmpty()) return null;
        double bx, by, bz;
        synchronized (bot.positionLock) {
            bx = bot.playerX;
            by = bot.playerY;
            bz = bot.playerZ;
        }
        Pose best = null;
        double bestDist = Double.MAX_VALUE;
        for (Pose p : poses) {
            if (p.id == bot.entityId) continue;
            double d = p.distanceTo(bx, by + EYE_HEIGHT * 0.5, bz);
            if (d > MAX_WATCH_DISTANCE || d < 0.5) continue;
            if (!gazeHits(p, bx, by, bz)) continue;
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private Pose randomNearby(MinecraftBot bot) {
        List<Pose> poses = bot.entityTracker.poses();
        if (poses.isEmpty()) return null;
        double bx, by, bz;
        synchronized (bot.positionLock) {
            bx = bot.playerX;
            by = bot.playerY;
            bz = bot.playerZ;
        }
        Pose pick = null;
        for (Pose p : poses) {
            if (p.id == bot.entityId) continue;
            if (p.distanceTo(bx, by, bz) > 16) continue;
            if (random.nextBoolean()) pick = p;
        }
        return pick;
    }

    private Pose poseOf(MinecraftBot bot, UUID uuid) {
        for (Pose p : bot.entityTracker.poses()) {
            if (uuid.equals(p.uuid)) return p;
        }
        return null;
    }

    /**
     * Ray/AABB slab test from the entity's eyes along its yaw/pitch, against the
     * bot's player box. Returns true when the ray passes through the box within
     * {@link #MAX_WATCH_DISTANCE}.
     */
    private static boolean gazeHits(Pose e, double bx, double by, double bz) {
        double ex = e.x;
        double ey = e.y + EYE_HEIGHT;
        double ez = e.z;
        double yawRad = Math.toRadians(e.yaw);
        double pitchRad = Math.toRadians(e.pitch);
        double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * Math.cos(pitchRad);

        double tmin = 0.0;
        double tmax = MAX_WATCH_DISTANCE;

        double[] r = slab(ex, dx, bx - BOT_HALF_WIDTH, bx + BOT_HALF_WIDTH, tmin, tmax);
        if (r == null) return false;
        tmin = r[0];
        tmax = r[1];
        r = slab(ey, dy, by - 0.05, by + BOT_HEIGHT, tmin, tmax);
        if (r == null) return false;
        tmin = r[0];
        tmax = r[1];
        r = slab(ez, dz, bz - BOT_HALF_WIDTH, bz + BOT_HALF_WIDTH, tmin, tmax);
        if (r == null) return false;
        return r[1] >= 0.0;
    }

    private static double[] slab(double origin, double dir, double min, double max,
                                 double tmin, double tmax) {
        if (Math.abs(dir) < 1e-9) {
            if (origin < min || origin > max) return null;
            return new double[]{tmin, tmax};
        }
        double t1 = (min - origin) / dir;
        double t2 = (max - origin) / dir;
        if (t1 > t2) {
            double tmp = t1;
            t1 = t2;
            t2 = tmp;
        }
        tmin = Math.max(tmin, t1);
        tmax = Math.min(tmax, t2);
        if (tmin > tmax) return null;
        return new double[]{tmin, tmax};
    }

    private static double wrapDegrees(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    private static double clamp(double v, double max) {
        return Math.max(-max, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
