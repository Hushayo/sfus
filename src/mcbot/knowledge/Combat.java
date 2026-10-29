package mcbot.knowledge;

import mcbot.inventory.Inventory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Melee combat maths: weapon damage, the 1.9 attack cooldown, criticals and
 * what actually lands after armour.
 *
 * <p>The cooldown is the part bots usually get wrong. A sword swings at 1.6
 * attacks per second, so a second click 200 ms after the first deals a fraction
 * of its damage - spam-clicking is *worse* than waiting. The formula is
 * vanilla's:
 *
 * <pre>
 *   progress = min(1, ticksSinceLastSwing / cooldownTicks)
 *   damage  *= 0.2 + progress^2 * 0.8
 * </pre>
 *
 * <p>Everything here is derived from the item component report, so the numbers
 * match this server build rather than a remembered wiki table.
 */
public final class Combat {
    private Combat() {}

    public static final class Weapon {
        public final String name;
        public final float damage;
        public final float speed;
        public final int maxDamage;
        public final int enchantability;

        Weapon(String name, float damage, float speed, int maxDamage, int enchantability) {
            this.name = name;
            this.damage = damage;
            this.speed = speed;
            this.maxDamage = maxDamage;
            this.enchantability = enchantability;
        }

        /** Ticks between full-strength hits (vanilla: 20 / attack speed). */
        public int cooldownTicks() {
            return Math.max(1, (int) Math.ceil(20.0 / Math.max(0.1, speed)));
        }

        public int cooldownMs() {
            return cooldownTicks() * 50;
        }

        /** Damage per second at perfect timing, before armour. */
        public double dps() {
            return damage * speed;
        }

        @Override
        public String toString() {
            return name + " (" + trim(damage) + " dmg, " + trim(speed) + " atk/s)";
        }
    }

    private static final Map<String, Weapon> BY_NAME = new HashMap<>();
    /** Bare hands: 1 damage at 4 attacks per second. */
    public static final Weapon FIST = new Weapon("fist", 1f, 4f, 0, 0);

    static {
        for (String[] f : Tables.read("/mcbot/data/combat.tsv")) {
            if (f.length < 4) continue;
            Weapon w = new Weapon(Tables.strip(f[0]), Tables.floatCol(f, 1, 1f),
                    Tables.floatCol(f, 2, 4f), Tables.intCol(f, 3, 0), Tables.intCol(f, 4, 0));
            BY_NAME.put(w.name, w);
        }
        System.out.println("[combat] " + BY_NAME.size() + " weapons");
    }

    public static Weapon weapon(String name) {
        Weapon w = BY_NAME.get(Tables.strip(name));
        return w == null ? FIST : w;
    }

    public static boolean isWeapon(String name) {
        return BY_NAME.containsKey(Tables.strip(name));
    }

    /**
     * Damage of a swing that is {@code progress} of the way through the
     * cooldown. 1.0 is a full-strength hit; 0.5 is a panic click.
     */
    public static double swingDamage(Weapon weapon, double progress, boolean critical) {
        double p = Math.max(0, Math.min(1, progress));
        double damage = weapon.damage * (0.2 + p * p * 0.8);
        if (critical && p >= 0.9) damage *= 1.5;
        return damage;
    }

    /**
     * Vanilla critical condition: falling, not on the ground, not sprinting,
     * and not climbing.
     */
    public static boolean canCrit(boolean falling, boolean onGround, boolean sprinting) {
        return falling && !onGround && !sprinting;
    }

    /** Damage after armour and protection, the number the health bar loses. */
    public static double afterDefense(double raw, int defense, float toughness, int protectionLevel) {
        return Armor.afterProtection(Armor.afterArmor(raw, defense, toughness), protectionLevel);
    }

    /**
     * The best weapon the bot owns, ranked by damage per second.
     *
     * <p>DPS rather than raw damage: a netherite axe hits for 10 but swings
     * once a second, while a sword hits for 7 twice as often. For a bot that
     * fights on a timer, throughput is what keeps it alive.
     */
    public static Weapon bestWeapon(Inventory inv) {
        Weapon best = null;
        for (Map.Entry<String, Integer> e : inv.contents()) {
            if (e.getValue() <= 0) continue;
            Weapon w = BY_NAME.get(e.getKey());
            if (w == null) continue;
            if (best == null || w.dps() > best.dps()) best = w;
        }
        return best == null ? FIST : best;
    }

    /** True when the item is a weapon worth switching to. */
    public static boolean betterThanHeld(Inventory inv, String heldName) {
        Weapon best = bestWeapon(inv);
        return !best.name.equals(Tables.strip(heldName));
    }

    /** Shield: blocks all of a hit from the front, 5 damage still gets through an axe. */
    public static double afterShield(double damage, boolean blocking, boolean attackerInFront,
                                     boolean axe) {
        if (!blocking || !attackerInFront) return damage;
        return axe ? Math.max(0, damage - 5.0) : 0.0;
    }

    /** Knockback strength from a hit, in blocks/tick-ish units. */
    public static double knockback(boolean sprinting, int knockbackLevel) {
        double base = sprinting ? 0.5 : 0.4;
        return base + knockbackLevel * 0.5;
    }

    public static String describe(String item) {
        Weapon w = BY_NAME.get(Tables.strip(item));
        if (w == null) return item + " is not a weapon";
        return w + ", " + w.cooldownMs() + " ms cooldown, " + trim(w.dps()) + " dps";
    }

    public static int count() { return BY_NAME.size(); }

    /** Every weapon name, for sweeps and tests. */
    public static java.util.List<String> all() {
        java.util.List<String> out = new java.util.ArrayList<>(BY_NAME.keySet());
        java.util.Collections.sort(out);
        return out;
    }

    private static String trim(double v) {
        return v == Math.round(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
