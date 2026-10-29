package mcbot.dev;

import mcbot.registry.Blocks;
import mcbot.registry.Items;
import mcbot.registry.Tags;
import mcbot.world.BlockStateTable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostic entry point for the generated registry tables.
 *
 * <pre>
 *   java -cp out mcbot.dev.RegistryCheck
 * </pre>
 *
 * It answers the questions that come up every time the data is regenerated for
 * a new Minecraft version:
 *
 *  - did the tables load, and how many blocks/items/states do they cover?
 *  - does the name-driven collision classifier agree with the hand-maintained
 *    {@link BlockStateTable}? Every disagreement is a bug in one of them.
 *  - do the tool and hardness tables actually know the blocks a bot mines?
 *
 * Run it after {@code python tools/gen_data.py} and before trusting the
 * pathfinder on a new version.
 */
public final class RegistryCheck {
    private RegistryCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("--dump-categories")) {
            dumpCategories(args[1]);
            return;
        }
        System.out.println("=== registry ===");
        System.out.println("blocks        " + Blocks.blockCount());
        System.out.println("states        " + Blocks.stateCount());
        System.out.println("items         " + Items.count());
        System.out.println("block tags    " + Tags.blockTagCount());
        System.out.println("item tags     " + Tags.itemTagCount());

        System.out.println();
        System.out.println("=== collision classifier vs BlockStateTable ===");
        Map<String, Integer> disagreements = new TreeMap<>();
        int agree = 0;
        int total = 0;
        for (int state = 0; state < Blocks.stateCount(); state++) {
            String name = Blocks.shortName(state);
            if (name == null) continue;
            total++;
            byte oldCat = BlockStateTable.category(state);
            byte newCat = Blocks.category(state);
            if (oldCat == newCat) {
                agree++;
            } else {
                disagreements.merge(name + " old=" + oldCat + " new=" + newCat, 1, Integer::sum);
            }
        }
        System.out.println("states compared " + total + ", agree " + agree
                + ", disagree " + (total - agree));
        if (!disagreements.isEmpty()) {
            System.out.println("disagreeing blocks (name, old, new -> state count):");
            int shown = 0;
            for (Map.Entry<String, Integer> e : disagreements.entrySet()) {
                System.out.println("  " + e.getKey() + " x" + e.getValue());
                if (++shown >= 60) {
                    System.out.println("  ... " + (disagreements.size() - shown) + " more");
                    break;
                }
            }
        }

        System.out.println();
        System.out.println("=== mining knowledge ===");
        String[] probe = {"stone", "deepslate", "oak_log", "diamond_ore", "ancient_debris",
                "obsidian", "dirt", "sand", "netherrack", "end_stone", "cobblestone",
                "iron_ore", "deepslate_iron_ore", "coal_ore", "gravel"};
        Map<String, String> rows = new LinkedHashMap<>();
        for (String block : probe) {
            int state = Blocks.defaultState(block);
            if (state < 0) {
                rows.put(block, "MISSING");
                continue;
            }
            rows.put(block, String.format("state=%d hardness=%.2f tool=%s tier=%s",
                    state, Blocks.hardness(state), Blocks.toolClass(state),
                    Blocks.requiredTier(state)));
        }
        rows.forEach((k, v) -> System.out.printf("  %-20s %s%n", k, v));

        System.out.println();
        System.out.println("=== break time with the right tool (seconds) ===");
        String[] tools = {"wooden_pickaxe", "stone_pickaxe", "iron_pickaxe",
                "diamond_pickaxe", "netherite_pickaxe"};
        String[] blocks = {"stone", "iron_ore", "diamond_ore", "obsidian", "ancient_debris"};
        System.out.printf("  %-16s", "block");
        for (String t : tools) System.out.printf("%-18s", t);
        System.out.println();
        for (String b : blocks) {
            int state = Blocks.defaultState(b);
            System.out.printf("  %-16s", b);
            for (String t : tools) {
                double s = Items.breakSeconds(Items.get(t), state, false, true);
                System.out.printf("%-18s", Double.isInfinite(s) ? "never" : String.format("%.2f", s));
            }
            System.out.println();
        }

        System.out.println();
        System.out.println("=== state property decode spot checks ===");
        checkProps("oak_stairs", "facing=east", "half=top");
        checkProps("oak_door", "facing=west", "open=true");
        checkProps("oak_slab", "type=top");
        checkProps("oak_fence", "west=true", "north=false");
        checkProps("chest", "type=left", "facing=south");
    }

    /**
     * Writes one line per state: {@code id TAB name TAB category}. Used to
     * diff the name-driven classifier against a previous table when the data
     * is regenerated for a new Minecraft version.
     */
    private static void dumpCategories(String path) throws Exception {
        try (java.io.PrintWriter w = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(path),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            for (int state = 0; state < Blocks.stateCount(); state++) {
                w.println(state + "\t" + Blocks.shortName(state) + "\t" + Blocks.category(state));
            }
        }
        System.out.println("wrote " + Blocks.stateCount() + " rows to " + path);
    }

    private static void checkProps(String block, String... props) {
        int state = Blocks.state(block, props);
        StringBuilder sb = new StringBuilder();
        for (String p : props) {
            String k = p.substring(0, p.indexOf('='));
            sb.append(k).append('=').append(Blocks.property(state, k)).append(' ');
        }
        System.out.printf("  %-14s state=%-6d %s%n", block, state, sb.toString().trim());
    }
}
