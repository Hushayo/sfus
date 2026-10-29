package mcbot.dev.test;

import mcbot.path.Pathfinder;
import mcbot.physics.PlayerPhysics;
import mcbot.registry.Blocks;
import mcbot.world.World;

/**
 * Physics and pathfinding tests against a synthetic world.
 *
 * <p>No server needed: the world cache is a plain map, so a floor, a wall and a
 * box can be built in a few lines and the integrator can be run tick by tick.
 * That is how the jump arc was checked against vanilla's 1.25-block height
 * instead of against "it looked about right in game".
 */
public final class PhysicsTests {
    private PhysicsTests() {}

    private static final int FLOOR_Y = 63;

    public static void run() {
        TestRunner.suite("physics");

        TestRunner.test("gravity accelerates downward", () -> {
            PlayerPhysics p = new PlayerPhysics();
            p.setPosition(0.5, 100, 0.5);
            World empty = new World();
            for (int i = 0; i < 20; i++) p.tick(empty);
            TestRunner.check("fell", p.y < 100);
            TestRunner.check("velocity is downward", p.vy < 0);
        });

        TestRunner.test("a jump peaks near 1.25 blocks", () -> {
            PlayerPhysics p = new PlayerPhysics();
            p.setPosition(0.5, 64, 0.5);
            p.onGround = true;
            p.vy = 0.42;
            World empty = new World();
            double peak = p.y;
            for (int i = 0; i < 40; i++) {
                p.tick(empty);
                peak = Math.max(peak, p.y);
            }
            TestRunner.near("jump height", 1.25, peak - 64, 0.12);
        });

        TestRunner.test("a body lands on a floor and stays there", () -> {
            World world = flatWorld(8);
            PlayerPhysics p = new PlayerPhysics();
            p.setPosition(0.5, FLOOR_Y + 4, 0.5);
            for (int i = 0; i < 60; i++) p.tick(world);
            TestRunner.near("feet on the surface", FLOOR_Y + 1.0, p.y, 0.01);
            TestRunner.check("on the ground", p.onGround);
            // Resting still means gravity keeps pulling; what must not happen
            // is the body sinking or bouncing.
            double restingY = p.y;
            for (int i = 0; i < 20; i++) p.tick(world);
            TestRunner.near("stays on the surface", restingY, p.y, 0.001);
            TestRunner.check("still on the ground", p.onGround);
        });

        TestRunner.test("fall distance is accumulated for fall damage", () -> {
            World world = flatWorld(8);
            PlayerPhysics p = new PlayerPhysics();
            p.setPosition(0.5, FLOOR_Y + 10, 0.5);
            for (int i = 0; i < 80; i++) p.tick(world);
            TestRunner.check("fall distance recorded", p.consumeFallDistance() > 5.0);
        });

        TestRunner.test("a wall stops horizontal motion", () -> {
            World world = flatWorld(8);
            int stone = Blocks.defaultState("stone");
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 2; y++) {
                for (int z = -4; z <= 4; z++) world.setBlock(2, y, z, stone);
            }
            PlayerPhysics p = new PlayerPhysics();
            p.setPosition(0.5, FLOOR_Y + 1, 0.5);
            p.onGround = true;
            p.vx = 0.3;
            for (int i = 0; i < 20; i++) p.tick(world);
            TestRunner.check("did not pass through the wall", p.x < 2.0);
        });

        TestRunner.suite("pathfinding");

        TestRunner.test("a path exists across open ground", () -> {
            World world = flatWorld(24);
            Pathfinder.Result r = Pathfinder.findPath(world, 0.5, FLOOR_Y + 1, 0.5,
                    6.5, FLOOR_Y + 1, 0.5);
            TestRunner.check("path found: " + r.error, r.ok());
            TestRunner.atLeast("path length", 4, r.path.size());
        });

        TestRunner.test("a path routes around a wall", () -> {
            World world = flatWorld(24);
            int stone = Blocks.defaultState("stone");
            for (int z = -6; z <= 6; z++) {
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 2; y++) world.setBlock(3, y, z, stone);
            }
            Pathfinder.Result r = Pathfinder.findPath(world, 0.5, FLOOR_Y + 1, 0.5,
                    6.5, FLOOR_Y + 1, 0.5);
            TestRunner.check("path found around the wall: " + r.error, r.ok());
            TestRunner.atLeast("path is longer than the direct line", 8, r.path.size());
        });

        TestRunner.test("an enclosed box has no path out", () -> {
            World world = flatWorld(24);
            int stone = Blocks.defaultState("stone");
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 2; y++) {
                for (int x = -2; x <= 2; x++) {
                    world.setBlock(x, y, -2, stone);
                    world.setBlock(x, y, 2, stone);
                }
                for (int z = -2; z <= 2; z++) {
                    world.setBlock(-2, y, z, stone);
                    world.setBlock(2, y, z, stone);
                }
            }
            Pathfinder.Result r = Pathfinder.findPath(world, 0.5, FLOOR_Y + 1, 0.5,
                    8.5, FLOOR_Y + 1, 0.5);
            TestRunner.check("no path out of a sealed box", !r.ok());
        });

        TestRunner.test("a one-block step is climbable", () -> {
            World world = flatWorld(24);
            int stone = Blocks.defaultState("stone");
            for (int x = 3; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    world.setBlock(x, FLOOR_Y + 1, z, stone);
                }
            }
            Pathfinder.Result r = Pathfinder.findPath(world, 0.5, FLOOR_Y + 1, 0.5,
                    6.5, FLOOR_Y + 2, 0.5);
            TestRunner.check("path up the step: " + r.error, r.ok());
        });

        TestRunner.test("lava is avoided when a dry route exists", () -> {
            World world = flatWorld(24);
            int lava = Blocks.defaultState("lava");
            for (int z = -2; z <= 2; z++) world.setBlock(3, FLOOR_Y, z, lava);
            Pathfinder.Result r = Pathfinder.findPath(world, 0.5, FLOOR_Y + 1, 0.5,
                    6.5, FLOOR_Y + 1, 0.5);
            TestRunner.check("path found: " + r.error, r.ok());
            for (Pathfinder.Node n : r.path) {
                TestRunner.check("never steps into lava", n.x != 3 || Math.abs(n.z) > 2);
            }
        });
    }

    /** A stone floor from -radius..radius, with air above. */
    private static World flatWorld(int radius) {
        World world = new World();
        int stone = Blocks.defaultState("stone");
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                world.getOrCreateChunk(cx, cz);
            }
        }
        for (int x = -radius * 2; x <= radius * 2; x++) {
            for (int z = -radius * 2; z <= radius * 2; z++) {
                world.setBlock(x, FLOOR_Y, z, stone);
            }
        }
        return world;
    }
}
