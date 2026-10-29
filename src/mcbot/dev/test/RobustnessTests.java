package mcbot.dev.test;

import mcbot.inventory.Inventory;
import mcbot.inventory.ItemStack;
import mcbot.net.ChatText;
import mcbot.net.PacketReader;
import mcbot.world.ChunkParser;
import mcbot.world.World;

import java.util.Random;

/**
 * Robustness tests: garbage in, no crash.
 *
 * <p>A hand-written protocol parser is one malformed packet away from taking the
 * bot offline, and servers send malformed packets in normal operation - a
 * plugin with a custom payload, a chunk from a different version, a truncated
 * frame during a lag spike. The requirement is not "parse it"; it is "fail this
 * packet, keep the connection".
 *
 * <p>Every case here feeds random or hostile bytes to a real parser and only
 * requires that the failure is a bounded, catchable exception - not a hang, not
 * an OOM, not a corrupted parser state.
 */
public final class RobustnessTests {
    private RobustnessTests() {}

    public static void run() {
        TestRunner.suite("robustness");

        TestRunner.test("random bytes never hang the chunk parser", () -> {
            Random random = new Random(1234);
            World world = new World();
            long start = System.currentTimeMillis();
            for (int i = 0; i < 400; i++) {
                byte[] data = new byte[random.nextInt(600)];
                random.nextBytes(data);
                try {
                    ChunkParser.parse(data, world);
                } catch (RuntimeException expected) {
                    // A rejected packet is the correct outcome.
                }
            }
            TestRunner.check("finished in bounded time",
                    System.currentTimeMillis() - start < 10_000);
        });

        TestRunner.test("random bytes never hang the inventory parser", () -> {
            Random random = new Random(99);
            long start = System.currentTimeMillis();
            for (int i = 0; i < 400; i++) {
                byte[] data = new byte[random.nextInt(400)];
                random.nextBytes(data);
                Inventory inv = new Inventory();
                try {
                    inv.setContent(new PacketReader(data), Inventory.PLAYER_WINDOW, 0);
                } catch (RuntimeException expected) {
                    // fine
                }
                // Whatever happened, the inventory must still answer queries.
                inv.count("stone");
                inv.contents();
            }
            TestRunner.check("finished in bounded time",
                    System.currentTimeMillis() - start < 10_000);
        });

        TestRunner.test("a truncated stack is rejected, not guessed", () -> {
            byte[] truncated = {2, 0x01};   // count 2, then nothing
            try {
                ItemStack.readOptional(new PacketReader(truncated));
                TestRunner.fail("truncated stack should throw");
            } catch (RuntimeException expected) {
                // correct
            }
        });

        TestRunner.test("an absurd string length is rejected before allocating", () -> {
            byte[] huge = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07};
            try {
                new PacketReader(huge).readString();
                TestRunner.fail("huge string should throw");
            } catch (RuntimeException expected) {
                // correct
            }
        });

        TestRunner.test("random chat payloads are rejected or decoded", () -> {
            Random random = new Random(7);
            for (int i = 0; i < 300; i++) {
                byte[] data = new byte[random.nextInt(200)];
                random.nextBytes(data);
                try {
                    ChatText.read(new PacketReader(data));
                } catch (RuntimeException expected) {
                    // fine
                }
                try {
                    ChatText.whisper(data);
                } catch (RuntimeException expected) {
                    // fine
                }
            }
        });

        TestRunner.test("an empty packet does not crash any parser", () -> {
            try {
                ChunkParser.parse(new byte[0], new World());
            } catch (RuntimeException expected) {
                // fine
            }
            try {
                new Inventory().setContent(new PacketReader(new byte[0]),
                        Inventory.PLAYER_WINDOW, 0);
            } catch (RuntimeException expected) {
                // fine
            }
        });

        TestRunner.test("the inventory survives a rejected packet", () -> {
            Inventory inv = TestFixtures.packed("stone:7");
            try {
                inv.setContent(new PacketReader(new byte[]{(byte) 0xFF, (byte) 0xFF}), 0, 0);
            } catch (RuntimeException expected) {
                // fine
            }
            TestRunner.eq("still readable", 7, inv.count("stone"));
        });
    }
}
