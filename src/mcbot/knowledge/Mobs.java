package mcbot.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a mob is, how dangerous it is, and whether it will attack unprovoked.
 *
 * <p>Health and melee damage are the one thing no data report carries - they
 * are constants in each entity class - so they come from a curated table
 * generated alongside the rest. Everything else (name, id, drops) is from the
 * server.
 *
 * <p>The disposition is what combat and survival key off:
 *
 * <ul>
 *   <li>{@code hostile} - attacks on sight, always a threat
 *   <li>{@code neutral} - ignores you until provoked (enderman, piglin, wolf)
 *   <li>{@code passive} - never fights back (cow, chicken)
 *   <li>{@code boss} - a raid boss; not something to pick a fight with
 * </ul>
 */
public final class Mobs {
    private Mobs() {}

    public enum Disposition { HOSTILE, NEUTRAL, PASSIVE, BOSS, UNKNOWN }

    public static final class Mob {
        public final int id;
        public final String name;
        public final float health;
        public final float attack;
        public final Disposition disposition;
        public final boolean ranged;

        Mob(int id, String name, float health, float attack, Disposition disposition, boolean ranged) {
            this.id = id;
            this.name = name;
            this.health = health;
            this.attack = attack;
            this.disposition = disposition;
            this.ranged = ranged;
        }

        public boolean hostile() {
            return disposition == Disposition.HOSTILE || disposition == Disposition.BOSS;
        }

        /** Hits needed to kill it with a weapon of this damage. */
        public int hitsToKill(double damagePerHit) {
            if (health <= 0 || damagePerHit <= 0) return Integer.MAX_VALUE;
            return (int) Math.ceil(health / damagePerHit);
        }

        /** Hits the bot can survive from this mob, at its current armour. */
        public int hitsToKillBot(double rawDamage, int defense, float toughness) {
            double taken = Armor.afterArmor(rawDamage, defense, toughness);
            if (taken <= 0) return Integer.MAX_VALUE;
            return (int) Math.ceil(20.0 / taken);
        }

        @Override
        public String toString() {
            return name + " (" + trim(health) + " hp, " + trim(attack) + " dmg"
                    + (ranged ? ", ranged" : "") + ")";
        }
    }

    private static final Map<String, Mob> BY_NAME = new HashMap<>();
    private static final Map<Integer, Mob> BY_ID = new HashMap<>();

    static {
        for (String[] f : Tables.read("/mcbot/data/mobs.tsv")) {
            if (f.length < 5) continue;
            Mob m = new Mob(Tables.intCol(f, 0, -1), Tables.strip(f[1]),
                    Tables.floatCol(f, 2, 0f), Tables.floatCol(f, 3, 0f),
                    disposition(Tables.col(f, 4, "unknown")), "1".equals(Tables.col(f, 5, "0")));
            BY_NAME.put(m.name, m);
            if (m.id >= 0) BY_ID.put(m.id, m);
        }
        System.out.println("[mobs] " + BY_NAME.size() + " entity types classified");
    }

    private static Disposition disposition(String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "hostile": return Disposition.HOSTILE;
            case "neutral": return Disposition.NEUTRAL;
            case "passive": return Disposition.PASSIVE;
            case "boss": return Disposition.BOSS;
            default: return Disposition.UNKNOWN;
        }
    }

    /** Mob by entity name, with a name-based fallback for anything unlisted. */
    public static Mob get(String entityName) {
        String name = Tables.strip(entityName);
        Mob m = BY_NAME.get(name);
        if (m != null) return m;
        return new Mob(-1, name, 0, 0, guessDisposition(name), false);
    }

    public static Mob byTypeId(int typeId) {
        return BY_ID.get(typeId);
    }

    /**
     * Fallback classification for entity types the curated table does not
     * list. Name suffixes carry most of the signal: everything ending in
     * {@code _zombie}, {@code _skeleton} or containing {@code spider} fights on
     * sight, and the rest is treated as passive rather than as a threat - a bot
     * that panics at a cow is worse than one that ignores an unknown mob.
     */
    private static Disposition guessDisposition(String name) {
        if (name.equals("player")) return Disposition.NEUTRAL;
        if (name.contains("zombie") || name.contains("skeleton") || name.contains("spider")
                || name.contains("creeper") || name.contains("slime") || name.contains("blaze")
                || name.contains("ghast") || name.contains("witch") || name.contains("pillager")
                || name.contains("vindicator") || name.contains("evoker") || name.contains("vex")
                || name.contains("guardian") || name.contains("shulker") || name.contains("phantom")
                || name.contains("silverfish") || name.contains("endermite") || name.contains("breeze")
                || name.contains("creaking") || name.contains("warden")) {
            return Disposition.HOSTILE;
        }
        if (name.contains("dragon") || name.contains("wither")) return Disposition.BOSS;
        return Disposition.PASSIVE;
    }

    public static boolean isHostile(String entityName) {
        return get(entityName).hostile();
    }

    /** True for the mobs that hit back when you hit them. */
    public static boolean isNeutral(String entityName) {
        return get(entityName).disposition == Disposition.NEUTRAL;
    }

    /**
     * How much of a problem this mob is at a distance, 0 when it cannot reach
     * the bot. Ranged mobs stay dangerous much further out.
     */
    public static double threatScore(String entityName, double distance) {
        Mob m = get(entityName);
        if (!m.hostile() || m.health <= 0) return 0;
        double reach = m.ranged ? 24.0 : 3.5;
        if (distance > reach + 8) return 0;
        double proximity = Math.max(0, 1.0 - distance / (reach + 8));
        double strength = m.attack + m.health / 20.0;
        return proximity * strength;
    }

    /** Mobs that drop a given item, for "where do I get rotten flesh". */
    public static List<String> dropping(String item) {
        return Loot.mobSourcesOf(item);
    }

    /** Everything classified as hostile, for the bestiary and tests. */
    public static List<Mob> hostiles() {
        List<Mob> out = new ArrayList<>();
        for (Mob m : BY_NAME.values()) {
            if (m.hostile()) out.add(m);
        }
        out.sort((a, b) -> Float.compare(b.attack, a.attack));
        return out;
    }

    public static String describe(String entityName) {
        Mob m = get(entityName);
        return m + ", " + m.disposition.name().toLowerCase(Locale.ROOT)
                + ", drops " + Loot.describeEntity(m.name);
    }

    private static String trim(float v) {
        return v == Math.round(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
