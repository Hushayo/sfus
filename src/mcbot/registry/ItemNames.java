package mcbot.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns what a player typed into an item or block name.
 *
 * Players do not type registry ids. They type "pickaxe", "10 stones", "oak
 * logs", "iron ore". The mapping has to survive plurals, spaces, missing
 * namespaces, and ambiguity - "pickaxe" is six different items.
 *
 * Ambiguity is resolved by cost: wooden before stone before iron, so
 * "craft me a pickaxe" produces something the bot can actually make from a
 * tree. If the caller wants a specific one it says so ("diamond pickaxe").
 */
public final class ItemNames {
    private ItemNames() {}

    /** Tier order used to break ties: cheapest first. */
    private static final String[] TIER_PREFIX = {
            "wooden_", "golden_", "stone_", "copper_", "iron_", "diamond_", "netherite_"
    };

    /** Normalises "Oak Logs" / "oak_logs" / "minecraft:oak_log" to "oak_log". */
    public static String normalize(String text) {
        if (text == null) return "";
        String s = text.trim().toLowerCase(Locale.ROOT);
        s = s.replace("minecraft:", "");
        s = s.replaceAll("[^a-z0-9_ ]", "");
        s = s.replace(' ', '_');
        while (s.contains("__")) s = s.replace("__", "_");
        return s;
    }

    /** Exact item match, else a plural-tolerant one, else null. */
    public static String exactItem(String text) {
        String n = normalize(text);
        if (n.isEmpty()) return null;
        if (isItem(n)) return n;
        String singular = singular(n);
        if (!singular.equals(n) && isItem(singular)) return singular;
        String plural = n + "s";
        if (isItem(plural)) return plural;
        return null;
    }

    public static boolean isItem(String name) {
        return Items.get(name).id != 0 || "air".equals(name);
    }

    public static boolean isBlock(String name) {
        return Blocks.defaultState(name) >= 0;
    }

    /**
     * Resolves a free-form phrase to an item name.
     *
     * Order: exact item, exact block, suffix match ("pickaxe" -> wooden_pickaxe),
     * containment ("iron pick" -> iron_pickaxe). Returns null when nothing
     * plausible exists, so the caller can say so instead of guessing wildly.
     */
    public static String resolveItem(String text) {
        String n = normalize(text);
        if (n.isEmpty()) return null;

        String exact = exactItem(n);
        if (exact != null) return exact;

        // "pickaxe" should match "wooden_pickaxe": try the phrase as a suffix.
        List<String> suffixHits = new ArrayList<>();
        for (Items.Item item : allItems()) {
            if (item.name.endsWith("_" + n)) suffixHits.add(item.name);
        }
        if (!suffixHits.isEmpty()) return cheapest(suffixHits);

        // "oak log" already normalises to oak_log; "logs" -> log handled above.
        String singular = singular(n);
        if (!singular.equals(n)) {
            for (Items.Item item : allItems()) {
                if (item.name.endsWith("_" + singular)) suffixHits.add(item.name);
            }
            if (!suffixHits.isEmpty()) return cheapest(suffixHits);
        }

        // Last resort: containment, longest match wins ("raw iron" -> raw_iron).
        String best = null;
        for (Items.Item item : allItems()) {
            if (!item.name.contains(n) && !item.name.contains(singular)) continue;
            if (best == null || item.name.length() < best.length()) best = item.name;
        }
        return best;
    }

    /** Resolves a phrase to a *block* name, for mining targets. */
    public static String resolveBlock(String text) {
        String n = normalize(text);
        if (n.isEmpty()) return null;
        if (isBlock(n)) return n;
        String singular = singular(n);
        if (isBlock(singular)) return singular;

        String best = null;
        for (String block : Blocks.blockNames()) {
            if (block.endsWith("_" + n) || block.endsWith("_" + singular)) {
                if (best == null || block.length() < best.length()) best = block;
            }
        }
        if (best != null) return best;
        // Containment only on word boundaries: "sticks" must not become
        // "sticky_piston" just because the letters are in there.
        for (String block : Blocks.blockNames()) {
            if (containsWord(block, n) || containsWord(block, singular)) {
                if (best == null || block.length() < best.length()) best = block;
            }
        }
        return best;
    }

    /** True when `word` appears in `name` as a whole underscore-separated part. */
    private static boolean containsWord(String name, String word) {
        if (word.isEmpty()) return false;
        int from = 0;
        while (true) {
            int at = name.indexOf(word, from);
            if (at < 0) return false;
            boolean leftOk = at == 0 || name.charAt(at - 1) == '_';
            int end = at + word.length();
            boolean rightOk = end == name.length() || name.charAt(end) == '_';
            if (leftOk && rightOk) return true;
            from = at + 1;
        }
    }

    /** Crude English singulariser, good enough for item names. */
    public static String singular(String n) {
        if (n.endsWith("ies") && n.length() > 4) return n.substring(0, n.length() - 3) + "y";
        if (n.endsWith("ses") || n.endsWith("xes") || n.endsWith("ches") || n.endsWith("shes")) {
            return n.substring(0, n.length() - 2);
        }
        if (n.endsWith("s") && !n.endsWith("ss") && n.length() > 3) {
            return n.substring(0, n.length() - 1);
        }
        return n;
    }

    /** Cheapest candidate by tier prefix, then alphabetically. */
    private static String cheapest(List<String> names) {
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (String name : names) {
            int rank = 50;
            for (int i = 0; i < TIER_PREFIX.length; i++) {
                if (name.startsWith(TIER_PREFIX[i])) {
                    rank = i;
                    break;
                }
            }
            if (rank < bestRank || (rank == bestRank && (best == null || name.compareTo(best) < 0))) {
                bestRank = rank;
                best = name;
            }
        }
        return best;
    }

    private static List<Items.Item> ALL_CACHE;

    private static List<Items.Item> allItems() {
        if (ALL_CACHE == null) {
            List<Items.Item> list = new ArrayList<>();
            for (int id = 1; id < 4096; id++) {
                Items.Item item = Items.get(id);
                if (item.id == 0) continue;
                list.add(item);
            }
            ALL_CACHE = list;
        }
        return ALL_CACHE;
    }

    /** Pretty name for chat: "oak_log" -> "oak log". */
    public static String pretty(String name) {
        return name == null ? "?" : name.replace('_', ' ');
    }
}
