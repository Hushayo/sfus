package mcbot.registry;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The block registry: every state id the protocol can send, what block it is,
 * what its properties are, whether it collides, how hard it is and what tool
 * mines it.
 *
 * The table is generated from the server's own reports by
 * {@code tools/gen_data.py}, so the state ids match this exact build. That
 * matters more than it sounds: a state id is a *global* number assigned in
 * block registration order, so it changes whenever the server adds a block. A
 * hard-coded table from another version silently reports the wrong block for
 * thousands of ids.
 *
 * Storage: vanilla assigns all states of one block a contiguous id range, and
 * enumerates them as a mixed-radix product of the block's properties (last
 * property varies fastest). So the whole registry is 1286 rows of
 * {@code name, firstId, count, property:value,value;...} and a state id is
 * decoded arithmetically. 35,723 states, 86 KB on disk.
 */
public final class Blocks {
    private static final int MAX_STATES = 65_536;

    private static String[] nameByState = new String[0];
    /** Per state: index of the block row, for property decoding. */
    private static int[] rowByState = new int[0];
    /** Per state: offset inside the block's state range. */
    private static int[] offsetByState = new int[0];

    private static final List<Row> ROWS = new ArrayList<>();
    private static final Map<String, Integer> ROW_BY_NAME = new HashMap<>();

    /** block name -> tool class (pickaxe/axe/shovel/hoe/-). */
    private static final Map<String, String> TOOL = new HashMap<>();
    /** block name -> required tier (any/stone/iron/diamond). */
    private static final Map<String, String> TIER = new HashMap<>();
    /** block name -> hardness in seconds. */
    private static final Map<String, Double> HARDNESS = new HashMap<>();

    private static final Shape[] SHAPES = new Shape[MAX_STATES];
    private static final byte[] CATEGORY = new byte[MAX_STATES];

    public static final byte PASSABLE = 0;
    public static final byte SOLID = 1;
    public static final byte LIQUID = 2;
    public static final byte HAZARD = 3;
    public static final byte CLIMBABLE = 4;

    /** Vanilla default for a block whose hardness the report does not carry. */
    public static final float DEFAULT_HARDNESS = 2.0f;

    private static final class Row {
        String name;
        int firstId;
        int count;
        String[] propNames = new String[0];
        String[][] propValues = new String[0][];
        int[] radix = new int[0];
    }

    /**
     * Initialisation lives at the bottom of the file on purpose: the
     * classification sets it uses are static fields too, and a static block
     * near the top would run before they are assigned.
     */
    private static void bootstrap() {
        long t0 = System.currentTimeMillis();
        loadBlocks();
        loadToolTable();
        loadHardness();
        buildShapes();
        System.out.println("[blocks] " + ROWS.size() + " blocks, " + nameByState.length
                + " states, " + TOOL.size() + " tool entries, " + HARDNESS.size()
                + " hardness values (" + (System.currentTimeMillis() - t0) + " ms)");
    }

    private Blocks() {}

    // ------------------------------------------------------------------ load --

    private static void loadBlocks() {
        List<String[]> lines = read("/mcbot/data/blocks.tsv");
        int maxId = 0;
        for (String[] f : lines) maxId = Math.max(maxId, Integer.parseInt(f[1]) + Integer.parseInt(f[2]));
        nameByState = new String[maxId];
        rowByState = new int[maxId];
        offsetByState = new int[maxId];

        for (String[] f : lines) {
            Row row = new Row();
            row.name = f[0];
            row.firstId = Integer.parseInt(f[1]);
            row.count = Integer.parseInt(f[2]);
            String spec = f.length > 3 ? f[3] : "";
            if (!spec.isEmpty()) {
                String[] props = spec.split(";");
                row.propNames = new String[props.length];
                row.propValues = new String[props.length][];
                row.radix = new int[props.length];
                for (int i = 0; i < props.length; i++) {
                    int colon = props[i].indexOf(':');
                    row.propNames[i] = props[i].substring(0, colon);
                    row.propValues[i] = props[i].substring(colon + 1).split(",");
                    row.radix[i] = row.propValues[i].length;
                }
            }
            int index = ROWS.size();
            ROWS.add(row);
            ROW_BY_NAME.put(row.name, index);
            for (int i = 0; i < row.count; i++) {
                int id = row.firstId + i;
                if (id >= nameByState.length) break;
                nameByState[id] = row.name;
                rowByState[id] = index;
                offsetByState[id] = i;
            }
        }
    }

    private static void loadToolTable() {
        for (String[] f : read("/mcbot/data/block_tools.tsv")) {
            if (f.length < 3) continue;
            if (!"-".equals(f[1])) TOOL.put(f[0], f[1]);
            if (!"any".equals(f[2])) TIER.put(f[0], f[2]);
        }
    }

    private static void loadHardness() {
        for (String[] f : read("/mcbot/data/hardness.tsv")) {
            if (f.length < 2) continue;
            try {
                HARDNESS.put(f[0], Double.parseDouble(f[1]));
            } catch (NumberFormatException ignored) {}
        }
    }

    private static List<String[]> read(String resource) {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = Blocks.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.out.println("[blocks] missing resource " + resource);
                return out;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                out.add(line.split("\t", -1));
            }
        } catch (Exception e) {
            System.out.println("[blocks] " + resource + " load failed: " + e);
        }
        return out;
    }

    // ------------------------------------------------------------- accessors --

    public static int stateCount() { return nameByState.length; }
    public static int blockCount() { return ROWS.size(); }

    /** Block name for a state id, or null when the id is outside the registry. */
    public static String name(int state) {
        return state >= 0 && state < nameByState.length ? nameByState[state] : null;
    }

    public static String shortName(int state) {
        String n = name(state);
        if (n == null) return null;
        int colon = n.indexOf(':');
        return colon < 0 ? n : n.substring(colon + 1);
    }

    public static boolean isAir(int state) {
        String n = name(state);
        return n == null || "air".equals(n) || "cave_air".equals(n) || "void_air".equals(n);
    }

    /** True when the state is the given block ("stone", "minecraft:stone"). */
    public static boolean is(int state, String block) {
        String n = name(state);
        if (n == null) return false;
        String want = block.startsWith("minecraft:") ? block.substring(10) : block;
        return n.equals(want) || n.equals(block);
    }

    /** True when the block name ends with the given suffix (e.g. "_log"). */
    public static boolean nameEndsWith(int state, String suffix) {
        String n = name(state);
        return n != null && n.endsWith(suffix);
    }

    /** True when the block name contains the fragment. */
    public static boolean nameContains(int state, String fragment) {
        String n = name(state);
        return n != null && n.contains(fragment);
    }

    /** The default (first) state of a block, or -1 when the block is unknown. */
    public static int defaultState(String block) {
        Integer row = ROW_BY_NAME.get(strip(block));
        return row == null ? -1 : ROWS.get(row).firstId;
    }

    /**
     * Resolves a state by block name and properties given as
     * {@code key=value} pairs, e.g. {@code state("oak_stairs", "facing=east",
     * "half=bottom")}. Unknown properties or values are ignored, so a caller
     * can ask for "a waterlogged slab" without enumerating every state.
     */
    public static int state(String block, String... properties) {
        Integer idx = ROW_BY_NAME.get(strip(block));
        if (idx == null) return -1;
        Row row = ROWS.get(idx);
        if (row.propNames.length == 0 || properties.length == 0) return row.firstId;

        Map<String, String> want = new HashMap<>();
        for (String p : properties) {
            int eq = p.indexOf('=');
            if (eq > 0) want.put(p.substring(0, eq), p.substring(eq + 1));
        }
        int offset = 0;
        int stride = 1;
        for (int i = row.propNames.length - 1; i >= 0; i--) {
            String wantValue = want.get(row.propNames[i]);
            int chosen = 0;
            if (wantValue != null) {
                for (int v = 0; v < row.propValues[i].length; v++) {
                    if (row.propValues[i][v].equalsIgnoreCase(wantValue)) {
                        chosen = v;
                        break;
                    }
                }
            }
            offset += chosen * stride;
            stride *= row.radix[i];
        }
        return row.firstId + offset;
    }

    /** Value of one property of a state, or null when the block has no such property. */
    public static String property(int state, String property) {
        if (state < 0 || state >= nameByState.length) return null;
        Row row = ROWS.get(rowByState[state]);
        int offset = offsetByState[state];
        int stride = 1;
        for (int i = row.propNames.length - 1; i >= 0; i--) {
            if (row.propNames[i].equals(property)) {
                return row.propValues[i][(offset / stride) % row.radix[i]];
            }
            stride *= row.radix[i];
        }
        return null;
    }

    /** True when the given property has the given value. */
    public static boolean hasProperty(int state, String property, String value) {
        String v = property(state, property);
        return v != null && v.equalsIgnoreCase(value);
    }

    public static boolean isLiquid(int state) {
        String n = name(state);
        return n != null && (n.equals("water") || n.equals("lava")
                || n.equals("flowing_water") || n.equals("flowing_lava")
                || n.endsWith("_cauldron"));
    }

    // ------------------------------------------------------------- hardness --

    public static float hardness(int state) {
        String n = name(state);
        if (n == null) return DEFAULT_HARDNESS;
        Double h = HARDNESS.get(n);
        if (h != null) return h.floatValue();
        // Variants share their base block's hardness: "waxed_oxidized_cut_copper"
        // is cut copper, "stripped_oak_log" is oak_log.
        String base = strip(n);
        for (String prefix : new String[]{"waxed_", "stripped_", "smooth_", "polished_",
                "chiseled_", "cracked_", "mossy_", "cut_", "infested_", "dead_"}) {
            if (base.startsWith(prefix)) {
                Double b = HARDNESS.get(base.substring(prefix.length()));
                if (b != null) return b.floatValue();
            }
        }
        return DEFAULT_HARDNESS;
    }

    /** True for blocks that cannot be broken at all (bedrock, barrier, portal frame). */
    public static boolean unbreakable(int state) {
        return hardness(state) < 0;
    }

    /** Tool class that speeds this block up, or null when it is hand-mineable. */
    public static String toolClass(int state) {
        String n = name(state);
        return n == null ? null : TOOL.get(n);
    }

    /** Minimum tool tier needed for drops: any / stone / iron / diamond. */
    public static String requiredTier(int state) {
        String n = name(state);
        String t = n == null ? null : TIER.get(n);
        return t == null ? "any" : t;
    }

    /** Tier rank used for comparisons: wood 0, stone 1, iron 2, diamond 3, netherite 4. */
    public static int tierRank(String tier) {
        if (tier == null) return 0;
        switch (tier.toLowerCase(Locale.ROOT)) {
            case "stone": return 1;
            case "iron": return 2;
            case "diamond": return 3;
            case "netherite": return 4;
            default: return 0;
        }
    }

    // ------------------------------------------------------------ collision --

    public static Shape shape(int state) {
        if (state < 0 || state >= SHAPES.length) return Shape.EMPTY;
        Shape s = SHAPES[state];
        return s == null ? Shape.EMPTY : s;
    }

    /** PASSABLE / SOLID / LIQUID / HAZARD / CLIMBABLE, as the pathfinder wants it. */
    public static byte category(int state) {
        if (state < 0 || state >= CATEGORY.length) return SOLID;
        return CATEGORY[state];
    }

    public static boolean passable(int state) { return category(state) == PASSABLE; }
    public static boolean solid(int state) { return category(state) == SOLID; }

    /** Full height of the collision box, 0 for passable blocks. */
    public static double topHeight(int state) {
        return shape(state).topHeight();
    }

    // ------------------------------------------------------- classification --

    /**
     * Blocks a player can stand inside. Anything not listed here and not caught
     * by a suffix rule is treated as solid, which is the safe default: a
     * pathfinder that thinks a wall is walkable is worse than one that detours.
     */
    private static final Set<String> PASSABLE_EXACT = new HashSet<>(List.of(
            "air", "cave_air", "void_air", "light", "structure_void", "barrier_air",
            "water", "lava", "bubble_column", "fire", "soul_fire", "cobweb",
            "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "sweet_berry_bush", "nether_sprouts", "warped_roots", "crimson_roots",
            "kelp", "kelp_plant", "seagrass", "tall_seagrass", "lily_pad",
            "sugar_cane", "bamboo", "bamboo_sapling", "hanging_roots",
            "big_dripleaf_stem", "small_dripleaf", "spore_blossom", "glow_lichen",
            "sculk_vein", "vine", "ladder", "scaffolding", "tripwire", "string",
            "redstone_wire", "rail", "powered_rail", "detector_rail", "activator_rail",
            "torch", "wall_torch", "soul_torch", "soul_wall_torch", "redstone_torch",
            "redstone_wall_torch", "lantern", "soul_lantern", "end_rod", "chain",
            "flower_pot", "potted_oak_sapling", "brewing_stand", "cauldron",
            "water_cauldron", "lava_cauldron", "powder_snow_cauldron",
            "hopper", "composter", "stonecutter", "grindstone", "lectern",
            "bell", "conduit", "sea_pickle", "turtle_egg", "sniffer_egg",
            "frogspawn", "cocoa", "cake", "candle", "white_candle",
            "dragon_egg", "nether_wart", "wheat", "carrots", "potatoes",
            "beetroots", "pumpkin_stem", "melon_stem", "attached_pumpkin_stem",
            "attached_melon_stem", "torchflower_crop", "pitcher_crop",
            "soul_sand", "mud", "honey_block", "slime_block",
            "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus",
            "nether_wart_block", "warped_wart_block",
            "twisting_vines", "twisting_vines_plant",
            "weeping_vines", "weeping_vines_plant", "cave_vines", "cave_vines_plant",
            "soul_fire", "frosted_ice", "brown_mushroom_block_stem",
            "mangrove_roots", "muddy_mangrove_roots", "azalea_leaves_flowers",
            "resin_clump", "bush", "firefly_bush",
            // single-block flowers: no common suffix, so they are listed
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
            "oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower",
            "golden_dandelion", "open_eyeblossom", "closed_eyeblossom",
            "sunflower", "lilac", "peony", "pitcher_plant", "wildflowers",
            "red_shrub", "leaf_litter", "lever",
            // portals: you have to be able to stand inside them
            "nether_portal", "end_portal", "end_gateway"));

    /** Suffixes that mean "a plant or decoration you can walk through". */
    private static final String[] PASSABLE_SUFFIX = {
            "_sapling", "_flower", "_tulip", "_blossom", "_petals", "_sprouts",
            "_fungus", "_roots", "_lichen", "_vein", "_torch", "_button",
            "_pressure_plate", "_carpet", "_sign", "_banner", "_rail",
            "_coral_fan", "_coral_wall_fan", "_candle", "_bud", "_cluster",
            "_grass", "_fern", "_bush", "_wart", "_vines", "_vine",
            "_sprout", "_pickle", "_egg", "_mushroom", "_lily_pad", "_tripwire",
            "_hook", "_dust", "_wire", "_plate", "_frogspawn", "_web",
            "_propagule", "_bulb", "_cactus_flower"};

    /** Suffixes that mean "a liquid". */
    private static final String[] LIQUID_SUFFIX = {"_cauldron"};

    /** Blocks that damage or kill a player standing in them. */
    private static final Set<String> HAZARD_EXACT = new HashSet<>(List.of(
            "lava", "fire", "soul_fire", "magma_block", "cactus", "sweet_berry_bush",
            "wither_rose", "powder_snow", "campfire", "soul_campfire",
            "lava_cauldron", "pointed_dripstone", "bubble_column", "sulfur_spike"));

    /** Blocks that can be climbed. */
    private static final Set<String> CLIMBABLE_EXACT = new HashSet<>(List.of(
            "ladder", "vine", "scaffolding", "twisting_vines", "twisting_vines_plant",
            "weeping_vines", "weeping_vines_plant", "cave_vines", "cave_vines_plant"));

    private static void buildShapes() {
        for (int state = 0; state < nameByState.length; state++) {
            String name = nameByState[state];
            if (name == null) {
                SHAPES[state] = Shape.FULL;
                CATEGORY[state] = SOLID;
                continue;
            }
            Shape shape = classify(state, name);
            SHAPES[state] = shape;
            CATEGORY[state] = categoryOf(state, name, shape);
        }
    }

    private static Shape classify(int state, String name) {
        String n = strip(name);

        if (HAZARD_EXACT.contains(n)) return Shape.EMPTY;
        if (CLIMBABLE_EXACT.contains(n)) return Shape.EMPTY;
        if (PASSABLE_EXACT.contains(n)) return Shape.EMPTY;

        // Water and lava: no collision, but a real surface to swim in.
        if (n.equals("water") || n.equals("lava")) return Shape.EMPTY;

        if (n.endsWith("_slab")) {
            String type = property(state, "type");
            if ("double".equals(type)) return Shape.FULL;
            if ("top".equals(type)) return Shape.SLAB_TOP;
            return Shape.SLAB_BOTTOM;
        }
        if (n.endsWith("_stairs")) return Shape.STAIRS;
        if (n.endsWith("_fence") || n.endsWith("_fence_gate") || n.endsWith("_wall")) {
            return Shape.FENCE;
        }
        if (n.endsWith("_pane") || n.endsWith("_bars") || n.equals("iron_bars")
                || n.endsWith("_chain") || n.equals("chain")) {
            return Shape.FENCE;
        }
        if (n.endsWith("_door")) {
            // An open door has no collision; only the hinge post is solid.
            return hasProperty(state, "open", "true") ? Shape.EMPTY : Shape.DOOR;
        }
        if (n.endsWith("_trapdoor")) {
            if (hasProperty(state, "open", "true")) return Shape.EMPTY;
            return hasProperty(state, "half", "top") ? Shape.SLAB_TOP : Shape.SLAB_BOTTOM;
        }
        if (n.endsWith("_carpet") || n.endsWith("_moss")) return Shape.CARPET;
        if (n.equals("snow")) return Shape.SNOW_LAYER;

        for (String suffix : PASSABLE_SUFFIX) {
            if (n.endsWith(suffix)) return Shape.EMPTY;
        }
        for (String suffix : LIQUID_SUFFIX) {
            if (n.endsWith(suffix)) return Shape.EMPTY;
        }
        return Shape.FULL;
    }

    private static byte categoryOf(int state, String name, Shape shape) {
        String n = strip(name);
        // Hazard first: lava is a liquid *and* lethal, and the lethal part is
        // what the pathfinder and the mining module need to know about.
        if (HAZARD_EXACT.contains(n)) return HAZARD;
        if (CLIMBABLE_EXACT.contains(n)) return CLIMBABLE;
        if (n.equals("water") || n.equals("bubble_column") || n.endsWith("_cauldron")) {
            return LIQUID;
        }
        if (shape.blocksMovement()) return SOLID;
        // Carpets and snow layers are walkable but are not floors.
        return PASSABLE;
    }

    private static String strip(String s) {
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    /** All states of a block, in id order. */
    public static int[] statesOf(String block) {
        Integer idx = ROW_BY_NAME.get(strip(block));
        if (idx == null) return new int[0];
        Row row = ROWS.get(idx);
        int[] out = new int[row.count];
        for (int i = 0; i < row.count; i++) out[i] = row.firstId + i;
        return out;
    }

    /** Every block name in the registry, in state-id order. */
    public static List<String> blockNames() {
        List<String> out = new ArrayList<>(ROWS.size());
        for (Row row : ROWS) out.add(row.name);
        return out;
    }

    /** Property names of a block. */
    public static List<String> propertyNames(String block) {
        Integer idx = ROW_BY_NAME.get(strip(block));
        if (idx == null) return List.of();
        return Arrays.asList(ROWS.get(idx).propNames);
    }

    static {
        bootstrap();
    }
}
