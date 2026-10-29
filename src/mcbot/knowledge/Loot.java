package mcbot.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a block or a mob actually drops.
 *
 * <p>This is the difference between "mine stone" and "mine stone and end up with
 * cobblestone". The table is flattened from the server's own loot tables, so it
 * carries the parts that matter to a planner:
 *
 * <ul>
 *   <li>{@code min}..{@code max} from {@code set_count}
 *   <li>{@code chance} from {@code random_chance}, {@code table_bonus},
 *       {@code random_chance_with_enchanted_bonus}
 *   <li>{@code silk} / {@code shears} when the drop only appears with that tool
 *   <li>{@code conditional} for state- or entity-dependent drops
 *   <li>{@code smelt} when the drop arrives cooked (animals burning to death)
 * </ul>
 *
 * <p>The distinction that matters most is <b>guaranteed</b> versus
 * <b>possible</b>. A block that guarantees nothing (oak leaves) must not be
 * planned as "mine 64 oak leaves to get 64 oak leaves"; a block that guarantees
 * cobblestone is a valid source of cobblestone.
 */
public final class Loot {
    private Loot() {}

    /** One drop line: item, count range, probability, and the flags that gate it. */
    public static final class Drop {
        public final String item;
        public final int min;
        public final int max;
        public final float chance;
        public final boolean silk;
        public final boolean shears;
        public final boolean fortune;
        public final boolean smelt;
        public final boolean conditional;
        public final boolean playerOnly;
        public final int rolls;

        Drop(String item, int min, int max, float chance, String flags, int rolls) {
            this.item = item;
            this.min = min;
            this.max = max;
            this.chance = chance;
            this.rolls = Math.max(1, rolls);
            String f = flags == null ? "-" : flags;
            this.silk = has(f, "silk");
            this.shears = has(f, "shears");
            this.fortune = has(f, "fortune");
            this.smelt = has(f, "smelt");
            this.conditional = has(f, "conditional");
            this.playerOnly = has(f, "player_kill");
        }

        private static boolean has(String flags, String needle) {
            if (flags.isEmpty() || "-".equals(flags)) return false;
            for (String part : flags.split(",")) {
                if (part.equals(needle)) return true;
            }
            return false;
        }

        /** True when mining with an ordinary tool yields this every time. */
        public boolean guaranteed() {
            if (item.startsWith("@") || item.startsWith("#")) return false;
            return chance >= 0.999f && !silk && !shears && !conditional && !playerOnly;
        }

        /** Average number of items per block broken (fortune 0). */
        public double expected() {
            return chance * (min + max) / 2.0 * rolls;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(item);
            if (min == max && min == 1) sb.append(" x1");
            else sb.append(' ').append(min).append('-').append(max);
            if (chance < 0.999f) sb.append(' ').append(Math.round(chance * 100)).append('%');
            if (silk) sb.append(" [silk]");
            if (shears) sb.append(" [shears]");
            if (conditional) sb.append(" [conditional]");
            if (smelt) sb.append(" [smelted]");
            return sb.toString();
        }
    }

    private static final Map<String, List<Drop>> BLOCKS = new HashMap<>();
    private static final Map<String, List<Drop>> ENTITIES = new HashMap<>();

    static {
        int n = 0;
        for (String[] f : Tables.read("/mcbot/data/loot_blocks.tsv")) {
            if (f.length < 6) continue;
            Drop d = new Drop(Tables.strip(f[1]), Tables.intCol(f, 2, 1), Tables.intCol(f, 3, 1),
                    Tables.floatCol(f, 4, 1f), f[5], Tables.intCol(f, 6, 1));
            BLOCKS.computeIfAbsent(Tables.strip(f[0]), k -> new ArrayList<>()).add(d);
            n++;
        }
        int m = 0;
        for (String[] f : Tables.read("/mcbot/data/loot_entities.tsv")) {
            if (f.length < 6) continue;
            Drop d = new Drop(Tables.strip(f[1]), Tables.intCol(f, 2, 1), Tables.intCol(f, 3, 1),
                    Tables.floatCol(f, 4, 1f), f[5], Tables.intCol(f, 6, 1));
            ENTITIES.computeIfAbsent(Tables.strip(f[0]), k -> new ArrayList<>()).add(d);
            m++;
        }
        System.out.println("[loot] " + n + " block drops, " + m + " entity drops, "
                + BLOCKS.size() + " blocks, " + ENTITIES.size() + " mobs");
    }

    /** True when the server ships a loot table for this block at all. */
    public static boolean hasTable(String block) {
        return BLOCKS.containsKey(Tables.strip(block));
    }

    public static List<Drop> forBlock(String block) {
        return BLOCKS.getOrDefault(Tables.strip(block), List.of());
    }

    public static List<Drop> forEntity(String entity) {
        return ENTITIES.getOrDefault(Tables.strip(entity), List.of());
    }

    /**
     * The item a block always drops when broken with the right tool, or null.
     *
     * <p>Falls back to the block itself when the registry has no loot table for
     * it: vanilla blocks without a table (most building blocks) drop themselves,
     * and a bot that says "I don't know what stone drops" is less useful than
     * one that is right about the common case.
     */
    public static String guaranteedDrop(String block) {
        String name = Tables.strip(block);
        // Air has no loot table and drops nothing; the "no table means it drops
        // itself" fallback must not turn air into an item.
        if (name.equals("air") || name.equals("cave_air") || name.equals("void_air")) return null;
        List<Drop> drops = BLOCKS.get(name);
        if (drops == null) {
            // No loot table: vanilla blocks drop themselves, but only if they
            // have an item form. Water, lava and fire do not.
            return mcbot.registry.Items.get(name).id != 0 ? name : null;
        }
        Drop best = null;
        for (Drop d : drops) {
            if (!d.guaranteed()) continue;
            if (best == null || d.expected() > best.expected()) best = d;
        }
        return best == null ? null : best.item;
    }

    /** Every item this block can yield, most likely first. */
    public static List<Drop> probableDrops(String block) {
        List<Drop> drops = new ArrayList<>(forBlock(block));
        drops.sort((a, b) -> Double.compare(b.expected(), a.expected()));
        return drops;
    }

    /** Average items per block, counting only guaranteed drops. */
    public static double expectedGuaranteed(String block) {
        double sum = 0;
        for (Drop d : forBlock(block)) {
            if (d.guaranteed()) sum += d.expected();
        }
        return sum;
    }

    /** True when the block yields something without silk touch or shears. */
    public static boolean dropsWithoutSilk(String block) {
        for (Drop d : forBlock(block)) {
            if (!d.silk && !d.shears) return true;
        }
        return BLOCKS.get(Tables.strip(block)) == null;
    }

    /** Human-readable drop summary: "cobblestone x1" or "oak_sapling 5%, stick 2%". */
    public static String describeBlock(String block) {
        List<Drop> drops = probableDrops(block);
        if (drops.isEmpty()) {
            String guaranteed = guaranteedDrop(block);
            return guaranteed == null ? "nothing" : guaranteed + " x1";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Drop d : drops) {
            if (d.silk || d.shears) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(d);
            if (++shown >= 4) break;
        }
        return sb.length() == 0 ? "nothing without silk touch or shears" : sb.toString();
    }

    public static String describeEntity(String entity) {
        List<Drop> drops = new ArrayList<>(forEntity(entity));
        if (drops.isEmpty()) return "nothing";
        drops.sort((a, b) -> Double.compare(b.expected(), a.expected()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Drop d : drops) {
            if (d.conditional) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(d);
            if (++shown >= 4) break;
        }
        return sb.length() == 0 ? "nothing reliable" : sb.toString();
    }

    /**
     * Blocks that drop this item, easiest first.
     *
     * <p>Ranking matters for the answer "where do I get diamond": both
     * {@code diamond_ore} and {@code deepslate_diamond_ore} drop it, but one is
     * near the surface and the other is at y=-50. Deepslate variants and
     * infested blocks are pushed to the back; ties break on name length so the
     * plain block wins over a decorative variant.
     */
    public static List<String> sourcesOf(String item) {
        String want = Tables.strip(item);
        List<Drop> best = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        for (Map.Entry<String, List<Drop>> e : BLOCKS.entrySet()) {
            Drop chosen = null;
            for (Drop d : e.getValue()) {
                if (!d.item.equals(want)) continue;
                if (d.silk || d.shears || d.conditional) continue;
                if (chosen == null || d.chance > chosen.chance) chosen = d;
            }
            if (chosen != null) {
                blocks.add(e.getKey());
                best.add(chosen);
            }
        }
        Integer[] order = new Integer[blocks.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int cmp = Float.compare(best.get(b).chance, best.get(a).chance);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(blockPenalty(blocks.get(a)), blockPenalty(blocks.get(b)));
            if (cmp != 0) return cmp;
            return blocks.get(a).compareTo(blocks.get(b));
        });
        List<String> out = new ArrayList<>();
        for (int i : order) out.add(blocks.get(i));
        return out;
    }

    private static int blockPenalty(String block) {
        int penalty = 0;
        if (block.startsWith("deepslate_") || block.contains("_deepslate_")) penalty += 10;
        if (block.startsWith("infested_")) penalty += 20;
        return penalty + block.length() / 4;
    }

    /**
     * Mobs that drop this item, most reliable first.
     *
     * <p>Ties are broken by how ordinary the mob is: rotten flesh comes from
     * zombie, husk, drowned and a dozen variants, and "kill a zombie" is the
     * answer a player expects - not "kill a camel husk" because it sorted
     * first alphabetically.
     */
    public static List<String> mobSourcesOf(String item) {
        String want = Tables.strip(item);
        List<String> mobs = new ArrayList<>();
        List<Float> chances = new ArrayList<>();
        for (Map.Entry<String, List<Drop>> e : ENTITIES.entrySet()) {
            Drop chosen = null;
            for (Drop d : e.getValue()) {
                if (!d.item.equals(want) || d.conditional) continue;
                if (chosen == null || d.chance > chosen.chance) chosen = d;
            }
            if (chosen != null) {
                mobs.add(e.getKey());
                chances.add(chosen.chance);
            }
        }
        Integer[] order = new Integer[mobs.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int cmp = Float.compare(chances.get(b), chances.get(a));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(commonness(mobs.get(a)), commonness(mobs.get(b)));
            if (cmp != 0) return cmp;
            return mobs.get(a).compareTo(mobs.get(b));
        });
        List<String> out = new ArrayList<>();
        for (int i : order) out.add(mobs.get(i));
        return out;
    }

    /** Lower is more ordinary: the mobs a player meets in the first hour. */
    private static int commonness(String mob) {
        switch (mob) {
            case "zombie": return 0;
            case "skeleton": return 1;
            case "creeper": return 2;
            case "spider": return 3;
            case "cow": return 4;
            case "pig": return 5;
            case "sheep": return 6;
            case "chicken": return 7;
            case "enderman": return 8;
            case "slime": return 9;
            default:
                // A variant ("husk", "drowned", "camel husk") is less ordinary
                // than its base mob, and a longer name is usually a variant.
                return 20 + Math.min(20, mob.length());
        }
    }

    public static int blockTableCount() { return BLOCKS.size(); }
    public static int entityTableCount() { return ENTITIES.size(); }

    /** One-line knowledge dump for chat and the reasoner. */
    public static String summary() {
        return String.format(Locale.ROOT, "%d block loot tables, %d mob loot tables",
                BLOCKS.size(), ENTITIES.size());
    }

    /** Unmodifiable view for tooling that wants to walk every drop. */
    public static Map<String, List<Drop>> allBlocks() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(BLOCKS));
    }
}
