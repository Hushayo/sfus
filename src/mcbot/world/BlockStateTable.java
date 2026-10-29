package mcbot.world;

import mcbot.registry.Blocks;

/**
 * Block-state collision categories.
 *
 * This used to be a 35,723-character string of digits, one per state id, with
 * the comment "generated from the vanilla block registry report". The report
 * does not contain collision data, so it was really hand-maintained, and it
 * could not survive a server version that inserts a single block.
 *
 * It is now a thin view over {@link Blocks}, whose classification is derived
 * from block names and state properties (see {@code Blocks.classify}). The
 * constants stay because the pathfinder and scanner were written against them.
 */
public final class BlockStateTable {
    public static final byte PASSABLE = Blocks.PASSABLE;
    public static final byte SOLID = Blocks.SOLID;
    public static final byte LIQUID = Blocks.LIQUID;
    public static final byte HAZARD = Blocks.HAZARD;
    public static final byte CLIMBABLE = Blocks.CLIMBABLE;

    private BlockStateTable() {}

    public static byte category(int stateId) {
        return Blocks.category(stateId);
    }

    public static boolean passable(int stateId) {
        return Blocks.passable(stateId);
    }

    public static boolean solid(int stateId) {
        return Blocks.solid(stateId);
    }

    public static char categoryChar(int stateId) {
        switch (category(stateId)) {
            case PASSABLE: return '.';
            case SOLID: return '#';
            case LIQUID: return '~';
            case HAZARD: return '!';
            case CLIMBABLE: return '^';
            default: return '?';
        }
    }
}
