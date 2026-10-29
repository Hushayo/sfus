package mcbot.physics;

import mcbot.registry.Blocks;
import mcbot.registry.Shape;
import mcbot.world.World;

/**
 * Player physics: gravity, drag and collision against the block cache.
 *
 * Until now the bot had no velocity at all. It moved by writing a position and
 * sending it, so two things could not happen:
 *
 *  - <b>knockback</b>: the server sends {@code set_entity_motion} when a player
 *    is hit, and with no velocity to apply, the bot ignored it and kept walking
 *    as if nothing had happened;
 *  - <b>falling</b>: a bot left in the air (after a teleport, or after the block
 *    under it was mined) stayed there, because nothing integrated gravity.
 *
 * The constants are vanilla's: 0.08 blocks/tick² of gravity, 0.98 vertical
 * drag, 0.6 ground friction, 0.91 air drag, and a 0.6 x 1.8 player box.
 *
 * Collision moves one axis at a time in small substeps and stops at the first
 * contact, which is the standard way to keep a fast-moving box from tunnelling
 * through a wall. It is not a voxel-perfect vanilla port - the server still
 * reconciles - but it is close enough that a knockback looks and feels right and
 * a floating bot falls.
 */
public final class PlayerPhysics {
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    public static final double GROUND_FRICTION = 0.6;
    public static final double AIR_DRAG = 0.91;
    public static final double WIDTH = 0.6;
    public static final double HEIGHT = 1.8;
    /** Largest step taken before re-testing for a collision. */
    private static final double SUBSTEP = 0.05;
    /** No legitimate shove moves a player faster than this, in blocks/tick. */
    private static final double MAX_MOTION = 4.0;

    public double x;
    public double y;
    public double z;
    public double vx;
    public double vy;
    public double vz;
    public boolean onGround;
    /** Blocks fallen since leaving the ground, for fall damage. */
    public double fallDistance;

    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Velocity from {@code set_entity_motion}, already in blocks/tick. */
    public void setMotion(double vx, double vy, double vz) {
        // A player can be flung, but not by more than a few blocks per tick.
        // This is a guard against a decoding mistake, not a gameplay rule: when
        // set_entity_motion was read as three shorts the bot was launched into
        // the sky on the first hit it took, and nothing downstream questioned
        // the number.
        this.vx = clampMotion(vx);
        this.vy = clampMotion(vy);
        this.vz = clampMotion(vz);
        // Any upward or horizontal shove means we are no longer resting.
        if (Math.abs(this.vx) > 1e-4 || Math.abs(this.vz) > 1e-4 || this.vy > 1e-4) onGround = false;
    }

    private static double clampMotion(double v) {
        if (Double.isNaN(v)) return 0;
        if (v > MAX_MOTION) return MAX_MOTION;
        if (v < -MAX_MOTION) return -MAX_MOTION;
        return v;
    }

    public double horizontalSpeed() {
        return Math.sqrt(vx * vx + vz * vz);
    }

    public boolean isMoving() {
        return horizontalSpeed() > 0.003 || Math.abs(vy) > 0.003 || !onGround;
    }

    /**
     * Advances one tick. Returns true when the position changed.
     *
     * <p>Order matters and follows vanilla's {@code LivingEntity.travel}:
     * horizontal friction acts on the velocity you already had, the body moves
     * with that velocity, and <b>then</b> gravity and vertical drag are applied.
     *
     * <p>Applying gravity before the move (what this used to do) makes a 0.42
     * jump peak at 0.83 blocks instead of vanilla's 1.2522. That is not a
     * cosmetic difference: the bot then cannot clear a one-block gap that a
     * player clears, and every failed jump comes back as a server correction.
     */
    public boolean tick(World world) {
        double startX = x;
        double startY = y;
        double startZ = z;

        if (onGround) {
            vx *= GROUND_FRICTION;
            vz *= GROUND_FRICTION;
        } else {
            vx *= AIR_DRAG;
            vz *= AIR_DRAG;
        }

        boolean wasOnGround = onGround;
        onGround = false;

        double moveVy = vy;
        moveY(world, vy);
        moveX(world, vx);
        moveZ(world, vz);

        // Gravity and vertical drag after the move: vanilla's order.
        vy = (vy - GRAVITY) * VERTICAL_DRAG;

        if (!onGround) {
            if (moveVy < 0) fallDistance += -moveVy;
        } else if (!wasOnGround) {
            // Landed: fallDistance is read by survival for fall damage.
        }

        // Stop the jitter of a body resting exactly on a surface.
        if (onGround) {
            if (Math.abs(vx) < 0.003) vx = 0;
            if (Math.abs(vz) < 0.003) vz = 0;
        }
        return Math.abs(x - startX) > 1e-6 || Math.abs(y - startY) > 1e-6
                || Math.abs(z - startZ) > 1e-6;
    }

    /** Called on landing so survival can act on the fall. */
    public double consumeFallDistance() {
        double d = fallDistance;
        fallDistance = 0;
        return d;
    }

    // ------------------------------------------------------------ collision --

    private void moveY(World world, double delta) {
        if (delta == 0) return;
        double remaining = delta;
        while (Math.abs(remaining) > 1e-5) {
            double step = Math.max(-SUBSTEP, Math.min(SUBSTEP, remaining));
            if (!collides(world, x, y + step, z)) {
                y += step;
                remaining -= step;
                continue;
            }
            // Contact. Landing on something means the fall is over.
            if (step < 0) {
                onGround = true;
                y = Math.floor(y * 16.0) / 16.0;   // settle onto the surface
            }
            vy = 0;
            return;
        }
    }

    private void moveX(World world, double delta) {
        if (delta == 0) return;
        double remaining = delta;
        while (Math.abs(remaining) > 1e-5) {
            double step = Math.max(-SUBSTEP, Math.min(SUBSTEP, remaining));
            if (!collides(world, x + step, y, z)) {
                x += step;
                remaining -= step;
                continue;
            }
            vx = 0;
            return;
        }
    }

    private void moveZ(World world, double delta) {
        if (delta == 0) return;
        double remaining = delta;
        while (Math.abs(remaining) > 1e-5) {
            double step = Math.max(-SUBSTEP, Math.min(SUBSTEP, remaining));
            if (!collides(world, x, y, z + step)) {
                z += step;
                remaining -= step;
                continue;
            }
            vz = 0;
            return;
        }
    }

    /** True when the player box at (px,py,pz) overlaps any block collision box. */
    public boolean collides(World world, double px, double py, double pz) {
        double minX = px - WIDTH / 2;
        double maxX = px + WIDTH / 2;
        double minY = py;
        double maxY = py + HEIGHT;
        double minZ = pz - WIDTH / 2;
        double maxZ = pz + WIDTH / 2;

        int x0 = (int) Math.floor(minX);
        int x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY);
        int y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ);
        int z1 = (int) Math.floor(maxZ);

        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (!world.isBlockLoaded(bx, bz)) continue;
                    int state = world.getBlock(bx, by, bz);
                    Shape shape = Blocks.shape(state);
                    if (shape.empty()) continue;
                    for (double[] box : shape.boxes()) {
                        if (minX < bx + box[3] && maxX > bx + box[0]
                                && minY < by + box[4] && maxY > by + box[1]
                                && minZ < bz + box[5] && maxZ > bz + box[2]) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** True when there is a solid surface directly under the feet. */
    public boolean groundBelow(World world) {
        return collides(world, x, y - 0.02, z);
    }
}
