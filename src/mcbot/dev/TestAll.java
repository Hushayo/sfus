package mcbot.dev;

import mcbot.dev.test.AiTests;
import mcbot.dev.test.ApiTests;
import mcbot.dev.test.CodecTests;
import mcbot.dev.test.IntegrationTests;
import mcbot.dev.test.InventoryTests;
import mcbot.dev.test.KnowledgeTests;
import mcbot.dev.test.PhysicsTests;
import mcbot.dev.test.RecipeTests;
import mcbot.dev.test.RegistryTests;
import mcbot.dev.test.RobustnessTests;
import mcbot.dev.test.SweepTests;
import mcbot.dev.test.TestRunner;

/**
 * Runs every offline test suite.
 *
 * <pre>
 *   java -cp "out;src" mcbot.dev.TestAll
 * </pre>
 *
 * <p>Exits non-zero when anything fails, so it can be used as a build gate.
 * Nothing here needs a Minecraft server: the data tables, the planner, the
 * inventory parser, the physics integrator and the pathfinder are all testable
 * in isolation, and those are the parts where a silent wrong answer is worse
 * than a crash.
 */
public final class TestAll {
    private TestAll() {}

    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        System.out.println("mcbot offline test suite");
        System.out.println("java " + System.getProperty("java.version"));

        CodecTests.run();
        RegistryTests.run();
        KnowledgeTests.run();
        RecipeTests.run();
        InventoryTests.run();
        PhysicsTests.run();
        RobustnessTests.run();
        SweepTests.run();
        ApiTests.run();
        AiTests.run();
        IntegrationTests.run();

        int code = TestRunner.summary();
        System.out.println("elapsed " + (System.currentTimeMillis() - start) + " ms");
        System.exit(code);
    }
}
