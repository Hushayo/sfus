package mcbot.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Damage types: what hurt the bot, and whether it was avoidable.
 *
 * <p>A death message is only useful if it changes behaviour. Grouping the
 * sources into "environmental" (fall, lava, drowning, starving) and "combat"
 * is what lets survival answer "do I need to eat, run, or climb out".
 */
public final class DamageTypes {
    private DamageTypes() {}

    public static final class Type {
        public final String name;
        public final String messageId;
        public final String scaling;
        public final float exhaustion;

        Type(String name, String messageId, String scaling, float exhaustion) {
            this.name = name;
            this.messageId = messageId;
            this.scaling = scaling;
            this.exhaustion = exhaustion;
        }

        @Override
        public String toString() {
            return name + " (" + messageId + ")";
        }
    }

    private static final Map<String, Type> BY_NAME = new HashMap<>();

    /** Sources the bot can do something about without fighting. */
    private static final Set<String> ENVIRONMENTAL = Set.of(
            "fall", "lava", "hot_floor", "on_fire", "in_fire", "campfire", "fireball",
            "drowning", "starve", "freeze", "cactus", "sweet_berry_bush", "thorns",
            "out_of_world", "generic", "magic", "wither", "fly_into_wall",
            "falling_block", "falling_anvil", "falling_stalactite", "stalagmite",
            "lightning_bolt", "explosion", "bad_respawn_point", "outside_border");

    static {
        for (String[] f : Tables.read("/mcbot/data/damage_types.tsv")) {
            if (f.length < 2) continue;
            Type t = new Type(Tables.strip(f[0]), Tables.col(f, 1, f[0]),
                    Tables.col(f, 2, "when_caused_by_living_non_player"),
                    Tables.floatCol(f, 3, 0f));
            BY_NAME.put(t.name, t);
        }
        System.out.println("[damage] " + BY_NAME.size() + " damage types");
    }

    public static Type get(String name) {
        return BY_NAME.get(Tables.strip(name));
    }

    public static boolean isEnvironmental(String name) {
        return ENVIRONMENTAL.contains(Tables.strip(name));
    }

    /** True when the source is another living entity. */
    public static boolean isCombat(String name) {
        return !isEnvironmental(name);
    }

    public static List<Type> all() {
        return new ArrayList<>(BY_NAME.values());
    }

    public static String describe(String name) {
        Type t = get(name);
        if (t == null) return name;
        return t + (isEnvironmental(name) ? " [environmental]" : " [combat]");
    }

    public static int count() { return BY_NAME.size(); }

    public static String summary() {
        return String.format(Locale.ROOT, "%d damage types, %d environmental",
                BY_NAME.size(), ENVIRONMENTAL.size());
    }
}
