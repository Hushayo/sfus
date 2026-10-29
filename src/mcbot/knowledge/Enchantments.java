package mcbot.knowledge;

import mcbot.registry.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enchantment metadata: max level, rarity weight, and what it can go on.
 *
 * <p>The bot cannot enchant without an enchanting table flow, but knowing the
 * table matters for two things it *can* do: read the enchantments on a tool it
 * picks up (and prefer the better one), and answer "what is this worth".
 *
 * <p>{@code supported_items} is usually a tag ({@code #minecraft:swords}), so
 * applicability is resolved through the same tag table the recipe planner uses.
 */
public final class Enchantments {
    private Enchantments() {}

    public static final class Enchantment {
        public final String name;
        public final int maxLevel;
        public final int weight;
        public final int anvilCost;
        public final List<String> slots;
        public final String supportedItems;

        Enchantment(String name, int maxLevel, int weight, int anvilCost,
                    List<String> slots, String supportedItems) {
            this.name = name;
            this.maxLevel = maxLevel;
            this.weight = weight;
            this.anvilCost = anvilCost;
            this.slots = slots;
            this.supportedItems = supportedItems;
        }

        /** Rarity from the weight: 10 common, 5 uncommon, 2 rare, 1 very rare. */
        public String rarity() {
            if (weight >= 10) return "common";
            if (weight >= 5) return "uncommon";
            if (weight >= 2) return "rare";
            return "very rare";
        }

        @Override
        public String toString() {
            return name + " (max " + maxLevel + ", " + rarity() + ")";
        }
    }

    private static final Map<String, Enchantment> BY_NAME = new HashMap<>();

    static {
        for (String[] f : Tables.read("/mcbot/data/enchantments.tsv")) {
            if (f.length < 4) continue;
            List<String> slots = new ArrayList<>();
            String raw = Tables.col(f, 4, "-");
            if (!"-".equals(raw)) {
                for (String s : raw.split(",")) if (!s.isBlank()) slots.add(s.trim());
            }
            Enchantment e = new Enchantment(Tables.strip(f[0]), Tables.intCol(f, 1, 1),
                    Tables.intCol(f, 2, 1), Tables.intCol(f, 3, 1), slots,
                    Tables.col(f, 5, "-"));
            BY_NAME.put(e.name, e);
        }
        System.out.println("[ench] " + BY_NAME.size() + " enchantments");
    }

    public static Enchantment get(String name) {
        return BY_NAME.get(Tables.strip(name));
    }

    public static int maxLevel(String name) {
        Enchantment e = get(name);
        return e == null ? 0 : e.maxLevel;
    }

    /** True when the enchantment can be applied to the item. */
    public static boolean appliesTo(String enchantment, String itemName) {
        Enchantment e = get(enchantment);
        if (e == null) return false;
        String spec = e.supportedItems;
        if (spec == null || spec.isEmpty() || "-".equals(spec)) return false;
        if (spec.startsWith("#")) return Tags.itemIn(itemName, spec.substring(1));
        for (String part : spec.split("\\|")) {
            if (Tables.strip(part).equals(Tables.strip(itemName))) return true;
        }
        return false;
    }

    public static List<Enchantment> all() {
        return new ArrayList<>(BY_NAME.values());
    }

    public static int count() { return BY_NAME.size(); }

    public static String describe(String name) {
        Enchantment e = get(name);
        if (e == null) return "unknown enchantment " + name;
        return e + ", slots " + String.join("/", e.slots) + ", on " + e.supportedItems;
    }
}
