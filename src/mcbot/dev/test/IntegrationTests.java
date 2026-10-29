package mcbot.dev.test;

import mcbot.bot.MinecraftBot;
import mcbot.core.BotState;

import java.util.function.BooleanSupplier;

/**
 * End-to-end test against an in-process server.
 *
 * <p>Everything else is unit-tested; this checks that the real connection code
 * is wired together: handshake, login, compression, configuration, play login,
 * health, keepalive echo, and surviving a malformed packet. It is the test that
 * would have caught "the bot logs in but never reaches PLAY".
 */
public final class IntegrationTests {
    private IntegrationTests() {}

    public static void run() {
        TestRunner.suite("integration");

        TestRunner.test("a full login reaches PLAY with the right state", () -> {
            try (FakeServer server = new FakeServer()) {
                MinecraftBot bot = new MinecraftBot("127.0.0.1", server.port(), "TestBot", null);
                bot.installDefaults();
                Thread reader = new Thread(() -> {
                    try {
                        bot.run();
                    } catch (Throwable ignored) {
                        // the server closing is the end of the test
                    }
                }, "bot-reader");
                reader.setDaemon(true);
                reader.start();

                TestRunner.check("server sent the play login", server.awaitLogin(6000));
                TestRunner.check("bot reached PLAY",
                        waitFor(() -> bot.state() == BotState.PLAY, 5000));
                // PLAY is entered when configuration finishes; the play login
                // that carries our entity id arrives right after it.
                TestRunner.check("play login parsed", waitFor(() -> bot.entityId == 42, 3000));
                TestRunner.eq("state", BotState.PLAY, bot.state());
                TestRunner.eq("entity id", 42, bot.entityId);
                TestRunner.eq("dimension", "minecraft:overworld", bot.world.dimensionKey());
                TestRunner.eq("game mode", 0, bot.gameMode);
                TestRunner.check("compression negotiated", bot.connection.compressionEnabled);
                TestRunner.eq("compression threshold", 256, bot.connection.compressionThreshold);

                TestRunner.check("health packet applied", waitFor(() -> bot.health == 20.0f, 2000));
                TestRunner.eq("food", 20, bot.food);

                TestRunner.check("keepalive was echoed", server.awaitKeepAlive(3000));
                TestRunner.eq("echoed id", 0x5EED, server.keepAliveValue());

                TestRunner.check("player_loaded was sent", server.awaitPlayerLoaded(6000));

                // The packet that used to get the bot kicked after every meal:
                // a player_action body of only the action byte. The server here
                // parses the full layout and rejects trailing bytes, so a
                // truncated or over-long body fails the test.
                bot.interaction.releaseUseItem();
                TestRunner.check("release_use_item decodes", server.awaitPlayerAction(3000));

                // The server sent an unknown packet id and a truncated chunk.
                // Neither may disconnect us.
                Thread.sleep(400);
                TestRunner.eq("malformed packets sent", 1, server.malformedSent());
                TestRunner.check("connection survived malformed packets",
                        bot.connection.isConnected());
                TestRunner.check("no server-side failure", server.failure() == null);

                bot.connection.close();
            }
        });

        TestRunner.test("the bot reports its own state after login", () -> {
            try (FakeServer server = new FakeServer()) {
                MinecraftBot bot = new MinecraftBot("127.0.0.1", server.port(), "StateBot", null);
                bot.installDefaults();
                Thread reader = new Thread(() -> {
                    try {
                        bot.run();
                    } catch (Throwable ignored) {}
                }, "bot-reader");
                reader.setDaemon(true);
                reader.start();

                TestRunner.check("login", server.awaitLogin(6000));
                TestRunner.check("play", waitFor(() -> bot.state() == BotState.PLAY, 5000));
                Thread.sleep(300);

                String text = bot.actions.selfReport();
                TestRunner.contains("name", text, "StateBot");
                TestRunner.contains("dimension", text, "overworld");
                TestRunner.contains("health", text, "health 20");

                String reasoning = bot.reasoner.answer("what do you know");
                TestRunner.notNull("reasoning answer", reasoning);
                TestRunner.contains("states", reasoning, "states");

                bot.connection.close();
            }
        });
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
