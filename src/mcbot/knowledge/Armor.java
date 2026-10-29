package mcbot.knowledge;

import mcbot.inventory.Inventory;
import mcbot.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Armour: which slot a piece goes in, how much it protects, and how much damage
 * that actually removes.
 *
 * <p>Defence points and toughness are read from the item's own attribute
 * modifiers, so a piece the server rebalanced is described correctly. The
 * damage formula is vanilla's, not a linear approximation:
 *
 * <pre>
 *   f        = 2 + toughness / 4
 *   effective = min(20, max(defence / 5, defence - damage / f))
 *   taken     = damage * (1 - effective / 25)
 * </pre>
 *
 * <p>That curve is why diamond armour is worth more than the raw number
 * suggests: toughness makes big hits get reduced instead of punching through.
 */
public final class Armor {
    private Armor() {}

    public static final String[] SLOTS = {"head", "chest", "legs", "feet"};

    public static final class Piece {
        public final String name;
        public final String slot;
        public final int defense;
        public final float toughness;
        public final float knockbackResistance;
        public final int maxDamage;

        Piece(String name, String slot, int defense, float toughness,
              float knockbackResistance, int maxDamage) {
            this.name = name;
            this.slot = slot;
            this.defense = defense;
            this.toughness = toughness;
            this.knockbackResistance = knockbackResistance;
            this.maxDamage = maxDamage;
        }

        @Override
        public String toString() {
            return name + " [" + slot + " " + defense + " def"
                    + (toughness > 0 ? ", " + trim(toughness) + " tough" : "") + "]";
        }
    }

    private static final Map<String, Piece> BY_NAME = new HashMap<>();
    /** slot -> pieces, best first. */
    private static final Map<String, List<Piece>> BY_SLOT = new LinkedHashMap<>();

    static {
        for (String[] f : Tables.read("/mcbot/data/armor.tsv")) {
            if (f.length < 5) continue;
            Piece p = new Piece(Tables.strip(f[0]), Tables.col(f, 1, "chest"),
                    Math.round(Tables.floatCol(f, 2, 0f)),
                    Tables.floatCol(f, 3, 0f), Tables.floatCol(f, 4, 0f),
                    Tables.intCol(f, 5, 0));
            BY_NAME.put(p.name, p);
            BY_SLOT.computeIfAbsent(p.slot, k -> new ArrayList<>()).add(p);
        }
        for (List<Piece> list : BY_SLOT.values()) {
            list.sort((a, b) -> Float.compare(b.defense + b.toughness * 2,
                    a.defense + a.toughness * 2));
        }
        System.out.println("[armor] " + BY_NAME.size() + " pieces across " + BY_SLOT.size() + " slots");
    }

    public static Piece get(String name) {
        return BY_NAME.get(Tables.strip(name));
    }

    public static boolean isArmor(String name) {
        return BY_NAME.containsKey(Tables.strip(name));
    }

    /** Player-menu slot for an armour slot: 5 helmet, 6 chest, 7 legs, 8 boots. */
    public static int menuSlot(String slot) {
        switch (slot) {
            case "head": return 5;
            case "chest": return 6;
            case "legs": return 7;
            case "feet": return 8;
            default: return -1;
        }
    }

    /** Inventory index (playerSlots) for an armour slot: 5..8, helmet first. */
    public static int inventoryIndex(String slot) {
        switch (slot) {
            case "head": return 5;
            case "chest": return 6;
            case "legs": return 7;
            case "feet": return 8;
            default: return -1;
        }
    }

    /**
     * Damage taken after armour, ignoring enchantments and shields.
     *
     * @param damage raw damage before any reduction
     */
    public static double afterArmor(double damage, int defense, float toughness) {
        if (damage <= 0) return 0;
        double f = 2.0 + toughness / 4.0;
        double effective = Math.min(20.0, Math.max(defense / 5.0, defense - damage / f));
        return damage * (1.0 - effective / 25.0);
    }

    /** Protection enchantment: 4% per level, capped at 80%. */
    public static double afterProtection(double damage, int protectionLevel) {
        int epf = Math.min(20, protectionLevel * 4);
        return damage * (1.0 - epf / 25.0);
    }

    /** The best piece the inventory owns for a slot, or null. */
    public static Piece bestForSlot(String slot, Inventory inv) {
        Piece best = null;
        for (Map.Entry<String, Integer> e : inv.contents()) {
            if (e.getValue() <= 0) continue;
            Piece p = BY_NAME.get(e.getKey());
            if (p == null || !p.slot.equals(slot)) continue;
            if (best == null || p.defense > best.defense
                    || (p.defense == best.defense && p.toughness > best.toughness)) {
                best = p;
            }
        }
        return best;
    }

    /** What is actually worn right now. */
    public static Piece worn(Inventory inv, String slot) {
        int idx = inventoryIndex(slot);
        if (idx < 0) return null;
        ItemStack stack = inv.get(idx);
        if (stack.isEmpty()) return null;
        return BY_NAME.get(stack.name());
    }

    /** Total defence points currently worn. */
    public static int wornDefense(Inventory inv) {
        int total = 0;
        for (String slot : SLOTS) {
            Piece p = worn(inv, slot);
            if (p != null) total += p.defense;
        }
        return total;
    }

    public static float wornToughness(Inventory inv) {
        float total = 0;
        for (String slot : SLOTS) {
            Piece p = worn(inv, slot);
            if (p != null) total += p.toughness;
        }
        return total;
    }

    /** Pieces the bot owns but is not wearing, best per slot. */
    public static List<Piece> upgrades(Inventory inv) {
        List<Piece> out = new ArrayList<>();
        for (String slot : SLOTS) {
            Piece best = bestForSlot(slot, inv);
            Piece current = worn(inv, slot);
            if (best == null) continue;
            if (current == null || best.defense > current.defense) out.add(best);
        }
        return out;
    }

    /** Average damage reduction against a hit of this size, 0..1. */
    public static double reductionFraction(Inventory inv, double incoming) {
        int defense = wornDefense(inv);
        if (defense == 0) return 0;
        double after = afterArmor(incoming, defense, wornToughness(inv));
        return 1.0 - after / incoming;
    }

    public static String describe(Inventory inv) {
        StringBuilder sb = new StringBuilder();
        for (String slot : SLOTS) {
            Piece p = worn(inv, slot);
            if (sb.length() > 0) sb.append(", ");
            sb.append(slot).append('=').append(p == null ? "none" : p.name);
        }
        sb.append(" (").append(wornDefense(inv)).append(" defence)");
        return sb.toString();
    }

    public static List<Piece> all() {
        return new ArrayList<>(BY_NAME.values());
    }

    private static String trim(float v) {
        return v == Math.round(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
