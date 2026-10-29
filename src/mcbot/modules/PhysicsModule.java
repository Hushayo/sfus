package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketIds;
import mcbot.net.PacketReader;
import mcbot.physics.PlayerPhysics;

/**
 * Runs player physics between path steps, and applies what the server shoves.
 *
 * Two jobs:
 *
 *  1. <b>Knockback.</b> When something hits the bot the server sends
 *     {@code set_entity_motion} for its entity id. That packet used to be
 *     ignored, so the bot kept walking through hits as if they had not landed.
 *     Now the velocity is applied, the current path is cancelled, and the body
 *     carries the shove out - which is also what makes a fight look like a fight.
 *
 *  2. <b>Gravity.</b> Whenever the bot is not being driven along a path, this
 *     integrates velocity and gravity against the block cache. A bot left in the
 *     air (after a teleport, or after the block under it was mined) now falls
 *     instead of hovering.
 *
 * While a path is running, {@link mcbot.modules.MovementModule} owns the
 * position; physics yields and just follows it. Two owners writing the same
 * coordinates would fight each other every tick.
 */
public class PhysicsModule implements BotModule {
    private volatile MinecraftBot bot;
    private volatile long lastMotionMs = 0L;
    private boolean wasOnGround = true;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
        PlayerPhysics p = bot.physics;
        synchronized (bot.positionLock) {
            p.setPosition(bot.playerX, bot.playerY, bot.playerZ);
        }
    }

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) {
        if (packetId != PacketIds.SET_ENTITY_MOTION) return;
        PacketReader pr = new PacketReader(data);
        int entityId = pr.readVarInt();
        if (entityId != bot.entityId) return;
        // The velocity is a packed LpVec3, not three shorts. Decoding it as
        // shorts produced a garbage vector large enough to fire the bot into
        // the sky the first time it was hit.
        double[] motion = mcbot.net.LpVec3Codec.read(pr);
        double vx = motion[0];
        double vy = motion[1];
        double vz = motion[2];
        if (Math.abs(vx) < 1e-4 && Math.abs(vy) < 1e-4 && Math.abs(vz) < 1e-4) return;

        PlayerPhysics p = bot.physics;
        synchronized (bot.positionLock) {
            p.setPosition(bot.playerX, bot.playerY, bot.playerZ);
        }
        p.setMotion(vx, vy, vz);
        lastMotionMs = System.currentTimeMillis();
        if (bot.movement.isMoving()) {
            // Being shoved interrupts a walk: the path no longer describes where
            // the body is going, and continuing it would fight the physics.
            System.out.println("[phys] knocked back (" + fmt(vx) + ", " + fmt(vy)
                    + ", " + fmt(vz) + "), dropping the current path");
            bot.movement.stop();
        } else {
            System.out.println("[phys] motion applied (" + fmt(vx) + ", " + fmt(vy)
                    + ", " + fmt(vz) + ")");
        }
    }

    @Override
    public void onTick(MinecraftBot bot) {
        if (!bot.playerLoaded || !bot.hasPlayerPosition || bot.dead) return;
        PlayerPhysics p = bot.physics;

        if (bot.movement.isMoving()) {
            // The path owns the position. Keep the physics body in sync so that
            // a knockback starting mid-walk begins from the right place.
            synchronized (bot.positionLock) {
                p.setPosition(bot.playerX, bot.playerY, bot.playerZ);
            }
            p.vx = 0;
            p.vy = 0;
            p.vz = 0;
            return;
        }

        // Resting on the ground with no velocity: nothing to integrate. But the
        // server still has to be told we are on the ground, and it only learns
        // that from a movement packet. After a teleport (or a jump that lands
        // without moving) the body sits still with onGround=true while the
        // server still believes it is airborne, which makes every block take
        // five times as long to break and looks like the bot has no strength.
        // So: skip the integration, never skip the flag.
        boolean resting = p.onGround && p.horizontalSpeed() < 0.003 && Math.abs(p.vy) < 0.003;
        if (resting) {
            if (p.onGround == bot.sentOnGround) return;
            try {
                synchronized (bot.moveLock) {
                    synchronized (bot.positionLock) {
                        p.setPosition(bot.playerX, bot.playerY, bot.playerZ);
                        publishGround(bot, true);
                    }
                }
                wasOnGround = true;
            } catch (Exception e) {
                System.out.println("[phys] ground sync failed: " + e);
            }
            return;
        }

        boolean moved;
        double landedFrom = 0;
        synchronized (bot.positionLock) {
            p.setPosition(bot.playerX, bot.playerY, bot.playerZ);
            boolean airborne = !p.onGround;
            moved = p.tick(bot.world);
            if (p.onGround && !wasOnGround && airborne) {
                landedFrom = p.consumeFallDistance();
            }
            if (moved) {
                bot.playerX = p.x;
                bot.playerY = p.y;
                bot.playerZ = p.z;
            }
            wasOnGround = p.onGround;
        }

        if (landedFrom > 3.0) {
            System.out.println("[phys] landed after falling " + fmt(landedFrom) + " blocks");
            bot.lastFallBlocks = landedFrom;
        }

        // A tick that changed nothing still has news for the server when the
        // ground state flipped (landing without moving).
        if (!moved && p.onGround == bot.sentOnGround) return;
        try {
            synchronized (bot.moveLock) {
                publishGround(bot, p.onGround);
            }
        } catch (Exception e) {
            System.out.println("[phys] send failed: " + e);
        }
    }

    /**
     * Sends the position with an explicit onGround flag and says so when the
     * flag changes, because a wrong one costs 5x on every dig.
     */
    private static void publishGround(MinecraftBot bot, boolean onGround) throws Exception {
        boolean changed = bot.sentOnGround != onGround;
        bot.sendPositionAndTickEnd(onGround);
        if (changed) {
            System.out.println("[phys] server told onGround=" + onGround);
        }
    }

    public boolean onGround() {
        return bot != null && bot.physics.onGround;
    }

    /** Milliseconds since the server last pushed the bot. */
    public long sinceMotion() {
        return System.currentTimeMillis() - lastMotionMs;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
