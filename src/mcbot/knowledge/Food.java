package mcbot.knowledge;

import mcbot.inventory.Inventory;
import mcbot.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Food: what it restores, how long eating takes, and whether it can be eaten
 * when the bar is full.
 *
 * <p>Values come from each item's own {@code minecraft:food} component, so
 * "cooked beef is 8 and 12.8 saturation" is the server's number, not a table
 * typed from memory. That matters for the auto-eat decision: vanilla only
 * regenerates health while food is at least 18, and saturation lets it keep
 * regenerating below that, so the bot wants the best *value* it has, not the
 * first edible thing in the hotbar.
 */
public final class Food {
    private Food() {}

    public static final class FoodItem {
        public final String name;
        public final int nutrition;
        /** Final saturation restored (already includes the ×2 and the modifier). */
        public final float saturation;
        public final float eatSeconds;
        public final boolean alwaysEat;
        /** "regeneration:100:1" style entries; empty when the food is plain. */
        public final List<String> effects;

        FoodItem(String name, int nutrition, float saturation, float eatSeconds,
                 boolean alwaysEat, List<String> effects) {
            this.name = name;
            this.nutrition = nutrition;
            this.saturation = saturation;
            this.eatSeconds = eatSeconds;
            this.alwaysEat = alwaysEat;
            this.effects = effects;
        }

        /** How much hunger + saturation this restores, for ranking. */
        public float value() {
            return nutrition + saturation;
        }

        /** Eating time in ticks, plus the four-tick consume delay vanilla adds. */
        public int eatTicks() {
            return Math.round(eatSeconds * 20f) + 4;
        }

        public boolean hasEffects() { return !effects.isEmpty(); }

        @Override
        public String toString() {
            return name + " (" + nutrition + " food, " + trim(saturation) + " sat"
                    + (alwaysEat ? ", anytime" : "") + ")";
        }

        private static String trim(float v) {
            return v == Math.round(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v);
        }
    }

    private static final Map<String, FoodItem> BY_NAME = new HashMap<>();

    static {
        for (String[] f : Tables.read("/mcbot/data/food.tsv")) {
            if (f.length < 5) continue;
            List<String> effects = new ArrayList<>();
            String raw = Tables.col(f, 5, "-");
            if (!"-".equals(raw) && !raw.isEmpty()) {
                for (String e : raw.split(",")) {
                    if (!e.isBlank()) effects.add(e.trim());
                }
            }
            FoodItem item = new FoodItem(Tables.strip(f[0]), Tables.intCol(f, 1, 0),
                    Tables.floatCol(f, 2, 0f), Tables.floatCol(f, 3, 1.6f),
                    "1".equals(Tables.col(f, 4, "0")), effects);
            BY_NAME.put(item.name, item);
        }
        System.out.println("[food] " + BY_NAME.size() + " edible items");
    }

    public static FoodItem get(String name) {
        return BY_NAME.get(Tables.strip(name));
    }

    public static boolean isFood(String name) {
        return BY_NAME.containsKey(Tables.strip(name));
    }

    /** True when the food can be eaten at this hunger level. */
    public static boolean canEat(int foodLevel, FoodItem item) {
        if (item == null) return false;
        return item.alwaysEat || foodLevel < 20;
    }

    /** True when eating now would actually help (vanilla regen needs 18). */
    public static boolean worthEating(int foodLevel, int health) {
        return foodLevel < 18 || (foodLevel < 20 && health < 20);
    }

    /**
     * The best food in the player inventory by value, or null.
     *
     * <p>Golden apples are deliberately not preferred for ordinary hunger:
     * they are a combat item, and eating one because the bar is at 17 wastes
     * the absorption. They are only considered when {@code allowPrecious} is
     * set, which callers do when the bot is about to die.
     */
    public static FoodItem best(Inventory inv) {
        return best(inv, false);
    }

    public static FoodItem best(Inventory inv, boolean allowPrecious) {
        FoodItem best = null;
        for (Map.Entry<String, Integer> e : inv.contents()) {
            FoodItem item = BY_NAME.get(e.getKey());
            if (item == null || e.getValue() <= 0) continue;
            if (!allowPrecious && precious(item.name)) continue;
            if (best == null || score(item) > score(best)) best = item;
        }
        return best;
    }

    /** The best food already in the hotbar, so eating needs no inventory click. */
    public static FoodItem bestInHotbar(Inventory inv) {
        return bestInHotbar(inv, false);
    }

    public static FoodItem bestInHotbar(Inventory inv, boolean allowPrecious) {
        FoodItem best = null;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.get(36 + i);
            if (stack.isEmpty()) continue;
            FoodItem item = BY_NAME.get(stack.name());
            if (item == null) continue;
            if (!allowPrecious && precious(item.name)) continue;
            if (best == null || score(item) > score(best)) best = item;
        }
        return best;
    }

    /** Hotbar index holding the best food, or -1. */
    public static int bestHotbarSlot(Inventory inv) {
        return bestHotbarSlot(inv, false);
    }

    public static int bestHotbarSlot(Inventory inv, boolean allowPrecious) {
        int bestSlot = -1;
        float bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.get(36 + i);
            if (stack.isEmpty()) continue;
            FoodItem item = BY_NAME.get(stack.name());
            if (item == null) continue;
            if (!allowPrecious && precious(item.name)) continue;
            float score = score(item);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /**
     * Food that is worth more as a potion than as a meal.
     *
     * <p>An enchanted golden apple restores 4 hunger. It also grants absorption
     * and regeneration, and costs eight gold blocks - eating one at food 17 is
     * the worst trade in the game.
     */
    public static boolean precious(String name) {
        return "enchanted_golden_apple".equals(name) || "golden_apple".equals(name);
    }

    private static float score(FoodItem item) {
        // Absorption and regeneration are combat value, not hunger value.
        float penalty = 0;
        for (String e : item.effects) {
            if (e.startsWith("absorption") || e.startsWith("regeneration")) penalty += 6;
        }
        return item.value() - penalty;
    }

    /** Item names of every food, for tooling and tests. */
    public static List<String> all() {
        return new ArrayList<>(BY_NAME.keySet());
    }

    public static String describe(String name) {
        FoodItem item = get(name);
        if (item == null) return "not food";
        StringBuilder sb = new StringBuilder(item.toString());
        if (!item.effects.isEmpty()) sb.append(" effects ").append(String.join(", ", item.effects));
        return sb.toString();
    }

    public static int count() { return BY_NAME.size(); }
}
