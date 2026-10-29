package mcbot.registry;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The item registry: what each item id is, how it stacks, how much damage it
 * takes, what it feeds you, and what it mines.
 *
 * Generated from the server's per-item component reports, so tool speed and
 * tier are the values the server itself uses in its break-time formula rather
 * than a wiki table that drifts between versions.
 */
public final class Items {
    public static final class Item {
        public final int id;
        public final String name;
        public final int maxStack;
        public final int maxDamage;
        public final int nutrition;
        public final float saturation;
        /** Block tag the tool is fast on ("mineable/pickaxe"), or null. */
        public final String toolTag;
        public final float toolSpeed;
        /** wooden / stone / iron / diamond / netherite / gold, or null. */
        public final String toolTier;
        public final float attackDamage;
        /** Attacks per second: 4.0 is bare hands, a sword is 1.6. */
        public final float attackSpeed;

        Item(int id, String name, int maxStack, int maxDamage, int nutrition,
             float saturation, String toolTag, float toolSpeed, String toolTier,
             float attackDamage, float attackSpeed) {
            this.id = id;
            this.name = name;
            this.maxStack = maxStack;
            this.maxDamage = maxDamage;
            this.nutrition = nutrition;
            this.saturation = saturation;
            this.toolTag = toolTag;
            this.toolSpeed = toolSpeed;
            this.toolTier = toolTier;
            this.attackDamage = attackDamage;
            this.attackSpeed = attackSpeed;
        }

        public boolean isTool() { return toolTag != null; }
        public boolean isFood() { return nutrition > 0; }
        public boolean isDamageable() { return maxDamage > 0; }
        public boolean isBlockItem() { return Blocks.defaultState(name) >= 0; }

        /** "pickaxe" / "axe" / ... from the tool tag, or null. */
        public String toolClass() {
            if (toolTag == null) return null;
            int slash = toolTag.lastIndexOf('/');
            return slash < 0 ? toolTag : toolTag.substring(slash + 1);
        }

        @Override
        public String toString() {
            return name + "#" + id;
        }
    }

    private static final List<Item> BY_ID = new ArrayList<>();
    private static final Map<String, Item> BY_NAME = new HashMap<>();
    private static final Item AIR = new Item(0, "air", 64, 0, 0, 0f, null, 0f, null, 1f, 4f);

    static {
        int max = 0;
        List<String[]> lines = read("/mcbot/data/items.tsv");
        for (String[] f : lines) {
            if (f.length < 10) continue;
            max = Math.max(max, Integer.parseInt(f[0]));
        }
        for (int i = 0; i <= max; i++) BY_ID.add(null);
        for (String[] f : lines) {
            if (f.length < 10) continue;
            try {
                int id = Integer.parseInt(f[0]);
                Item item = new Item(id, f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                        Integer.parseInt(f[4]), Float.parseFloat(f[5]),
                        "-".equals(f[6]) ? null : f[6].substring(f[6].indexOf('/') + 1),
                        Float.parseFloat(f[7]),
                        "-".equals(f[8]) ? null : f[8],
                        Float.parseFloat(f[9]),
                        f.length > 10 ? Float.parseFloat(f[10]) : 4f);
                BY_ID.set(id, item);
                BY_NAME.put(item.name, item);
            } catch (RuntimeException e) {
                System.out.println("[items] bad row " + String.join(",", f) + ": " + e);
            }
        }
        System.out.println("[items] " + BY_NAME.size() + " items");
    }

    private Items() {}

    private static List<String[]> read(String resource) {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = Items.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.out.println("[items] missing resource " + resource);
                return out;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                out.add(line.split("\t", -1));
            }
        } catch (Exception e) {
            System.out.println("[items] " + resource + " load failed: " + e);
        }
        return out;
    }

    public static Item get(int id) {
        if (id < 0 || id >= BY_ID.size()) return AIR;
        Item item = BY_ID.get(id);
        return item == null ? AIR : item;
    }

    public static Item get(String name) {
        if (name == null) return AIR;
        String n = name.startsWith("minecraft:") ? name.substring(10) : name;
        Item item = BY_NAME.get(n);
        return item == null ? AIR : item;
    }

    public static int id(String name) {
        return get(name).id;
    }

    public static String name(int id) {
        return get(id).name;
    }

    public static int count() { return BY_NAME.size(); }

    /** Every item name in the registry, for sweeps and tooling. */
    public static java.util.List<String> allNames() {
        java.util.List<String> out = new java.util.ArrayList<>(BY_NAME.keySet());
        java.util.Collections.sort(out);
        return out;
    }

    // ------------------------------------------------------------ mining math --

    /**
     * True when this item is the correct tool class for the block, i.e. it gets
     * the speed multiplier and (with enough tier) drops.
     */
    public static boolean isCorrectTool(Item item, int blockState) {
        String need = Blocks.toolClass(blockState);
        if (need == null) return true;      // hand-mineable
        if (item == null || !item.isTool()) return false;
        return need.equals(item.toolClass());
    }

    /**
     * True when breaking the block with this item yields drops.
     *
     * <p>A wrong tool class still breaks the block, just slowly and with no
     * drops - but only for blocks that *require* a tool. Dirt, sand, gravel and
     * wood drop by hand: they are in {@code mineable/shovel} or
     * {@code mineable/axe} for the speed bonus, not because a shovel is
     * mandatory. Treating the tag as a requirement made hand-mining dirt take
     * 2.5 s instead of the vanilla 0.75 s.
     */
    public static boolean canHarvest(Item item, int blockState) {
        String need = Blocks.toolClass(blockState);
        String tier = Blocks.requiredTier(blockState);
        boolean requiresTool = "pickaxe".equals(need) || Blocks.tierRank(tier) > 0;
        if (!requiresTool) return true;
        if (!isCorrectTool(item, blockState)) return false;
        int needRank = Blocks.tierRank(tier);
        int have = item == null ? 0 : Blocks.tierRank(item.toolTier);
        // Shears and other non-tiered tools have tier null; they harvest what
        // their class covers (leaves, wool) without a tier requirement.
        return have >= needRank;
    }

    /**
     * Vanilla break time in seconds.
     *
     * <pre>
     *   damage per tick = speed / hardness / (canHarvest ? 30 : 100)
     *   ticks           = ceil(1 / damage per tick)
     * </pre>
     * with the vanilla penalties for being in water (no Aqua Affinity) and for
     * being off the ground.
     */
    public static double breakSeconds(Item item, int blockState, boolean inWater, boolean onGround) {
        float hardness = Blocks.hardness(blockState);
        if (hardness < 0) return Double.POSITIVE_INFINITY;
        if (hardness == 0) return 0.0;

        float speed = 1.0f;
        if (item != null && item.isTool() && isCorrectTool(item, blockState)) {
            speed = item.toolSpeed;
        }
        boolean harvest = canHarvest(item, blockState);
        double damagePerTick = speed / hardness / (harvest ? 30.0 : 100.0);
        if (inWater) damagePerTick /= 5.0;
        if (!onGround) damagePerTick /= 5.0;
        if (damagePerTick <= 0) return Double.POSITIVE_INFINITY;
        double ticks = Math.ceil(1.0 / damagePerTick);
        return ticks / 20.0;
    }

    /** Best tool in an inventory for a block, by break speed. */
    public static Item bestToolFor(java.util.Collection<Item> candidates, int blockState) {
        Item best = null;
        double bestTime = Double.MAX_VALUE;
        for (Item item : candidates) {
            if (item == null || !item.isTool()) continue;
            double t = breakSeconds(item, blockState, false, true);
            if (t < bestTime) {
                bestTime = t;
                best = item;
            }
        }
        return best;
    }

    public static String describe(int id) {
        Item i = get(id);
        StringBuilder sb = new StringBuilder(i.name);
        if (i.maxStack > 1) sb.append(" x").append(i.maxStack);
        if (i.isDamageable()) sb.append(" (").append(i.maxDamage).append(" dur)");
        if (i.isFood()) sb.append(" food ").append(i.nutrition).append('/').append(i.saturation);
        if (i.isTool()) sb.append(' ').append(i.toolClass()).append(' ').append(i.toolSpeed)
                .append('x').append(i.toolTier == null ? "?" : i.toolTier);
        return sb.toString();
    }

    static {
        // A tiny sanity check at class load: the ids the bot hard-codes for
        // common items must resolve to the names it expects.
        check("diamond_pickaxe", 8.0f);
        check("iron_pickaxe", 6.0f);
        check("wooden_pickaxe", 2.0f);
        check("apple", 0f);
    }

    private static void check(String name, float expectedSpeed) {
        Item i = BY_NAME.get(name);
        if (i == null) {
            System.out.println("[items] WARNING: " + name + " missing from registry");
            return;
        }
        if (Math.abs(i.toolSpeed - expectedSpeed) > 0.001f) {
            System.out.println("[items] WARNING: " + name + " tool speed "
                    + i.toolSpeed + ", expected " + expectedSpeed);
        }
    }

    public static String tierName(String tier) {
        return tier == null ? "none" : tier.toLowerCase(Locale.ROOT);
    }
}
