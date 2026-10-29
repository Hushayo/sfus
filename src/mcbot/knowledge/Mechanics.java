package mcbot.knowledge;

import mcbot.inventory.Inventory;
import mcbot.registry.Blocks;
import mcbot.registry.Items;

import java.util.Locale;

/**
 * One place to ask "what does the game do here?".
 *
 * <p>The bot has no Minecraft library, so every answer it gives about the world
 * has to come from somewhere. This class is the join of all those sources:
 * block registry (states, collision, hardness), item components (tools, food,
 * armour, weapons), loot tables (drops), tags (what counts as planks) and the
 * curated mob table. Nothing here guesses; when a fact is unknown the answer
 * says so.
 *
 * <p>It is deliberately read-only and static: it can be called from a module
 * tick, a job thread, the chat parser or a test without any world state.
 */
public final class Mechanics {
    private Mechanics() {}

    /** Everything known about one block state. */
    public static final class BlockFacts {
        public final int state;
        public final String name;
        public final float hardness;
        public final String toolClass;
        public final String requiredTier;
        public final String guaranteedDrop;
        public final String dropSummary;
        public final boolean solid;
        public final boolean liquid;
        public final boolean hazard;
        public final boolean climbable;
        public final boolean unbreakable;

        BlockFacts(int state) {
            this.state = state;
            this.name = Blocks.shortName(state);
            this.hardness = Blocks.hardness(state);
            this.toolClass = Blocks.toolClass(state);
            this.requiredTier = Blocks.requiredTier(state);
            this.guaranteedDrop = name == null ? null : Loot.guaranteedDrop(name);
            this.dropSummary = name == null ? "unknown" : Loot.describeBlock(name);
            byte cat = Blocks.category(state);
            this.solid = cat == Blocks.SOLID;
            this.liquid = cat == Blocks.LIQUID;
            this.hazard = cat == Blocks.HAZARD;
            this.climbable = cat == Blocks.CLIMBABLE;
            this.unbreakable = Blocks.unbreakable(state);
        }

        /** Break time in seconds with the given tool, in air, on the ground. */
        public double breakSeconds(Items.Item tool) {
            return Items.breakSeconds(tool, state, false, true);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static BlockFacts block(int state) {
        return new BlockFacts(state);
    }

    public static BlockFacts block(String name) {
        int state = Blocks.defaultState(name);
        return state < 0 ? null : new BlockFacts(state);
    }

    // ------------------------------------------------------------- describe --

    public static String describeBlock(int state) {
        BlockFacts f = new BlockFacts(state);
        if (f.name == null) return "state " + state + " (not in the registry)";
        StringBuilder sb = new StringBuilder(f.name);
        sb.append(": ");
        if (f.unbreakable) {
            sb.append("unbreakable");
        } else {
            sb.append(String.format(Locale.ROOT, "%.2fs by hand", f.hardness));
        }
        if (f.toolClass != null) {
            sb.append(", ").append(f.toolClass);
            if (!"any".equals(f.requiredTier)) sb.append(" (").append(f.requiredTier).append("+)");
        }
        sb.append(", drops ").append(f.dropSummary);
        if (f.hazard) sb.append(", HAZARD");
        if (f.liquid) sb.append(", liquid");
        if (f.climbable) sb.append(", climbable");
        if (!f.solid) sb.append(", passable");
        return sb.toString();
    }

    public static String describeItem(String name) {
        String item = Tables.strip(name);
        Items.Item it = Items.get(item);
        if (it.id == 0) return "unknown item " + name;
        StringBuilder sb = new StringBuilder(item);
        sb.append(": stack ").append(it.maxStack);
        if (it.isDamageable()) sb.append(", durability ").append(it.maxDamage);
        if (Food.isFood(item)) sb.append(", ").append(Food.describe(item));
        Armor.Piece armor = Armor.get(item);
        if (armor != null) sb.append(", ").append(armor);
        if (Combat.isWeapon(item)) sb.append(", ").append(Combat.describe(item));
        if (it.isTool()) sb.append(", ").append(it.toolClass()).append(" speed ")
                .append(it.toolSpeed).append("x");
        if (it.isBlockItem()) {
            int state = Blocks.defaultState(item);
            sb.append(", places ").append(Blocks.shortName(state));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- mining --

    /**
     * Why the bot cannot get drops from this block right now, or null when it
     * can. "Cannot" means "no tool of the right class and tier", which is a
     * fact about the inventory, not about intent.
     */
    public static String whyCannotMine(Inventory inv, int state) {
        BlockFacts f = new BlockFacts(state);
        if (f.name == null) return "I don't know that block.";
        if (f.unbreakable) return f.name + " can't be broken.";
        String need = f.toolClass;
        if (need == null) return null;
        int needRank = Blocks.tierRank(f.requiredTier);
        boolean requiresTool = "pickaxe".equals(need) || needRank > 0;
        if (!requiresTool) return null;
        Items.Item best = bestTool(inv, state);
        if (best == null || !need.equals(best.toolClass())) {
            return "I need a " + need + " for " + f.name + ".";
        }
        if (Blocks.tierRank(best.toolTier) < needRank) {
            return "I need a " + f.requiredTier + " " + need + " for " + f.name + ".";
        }
        return null;
    }

    public static boolean canMine(Inventory inv, int state) {
        return whyCannotMine(inv, state) == null;
    }

    /** Fastest tool in the inventory for a block, or null. */
    public static Items.Item bestTool(Inventory inv, int state) {
        Items.Item best = null;
        double bestTime = Double.MAX_VALUE;
        for (var entry : inv.contents()) {
            if (entry.getValue() <= 0) continue;
            Items.Item item = Items.get(entry.getKey());
            if (!item.isTool()) continue;
            double t = Items.breakSeconds(item, state, false, true);
            if (t < bestTime) {
                bestTime = t;
                best = item;
            }
        }
        return best;
    }

    /** Break time with the best tool owned, or with the hand when none. */
    public static double breakSeconds(Inventory inv, int state) {
        Items.Item tool = bestTool(inv, state);
        return Items.breakSeconds(tool, state, false, true);
    }

    /** How many of this block the bot could mine in a minute, one at a time. */
    public static int blocksPerMinute(Inventory inv, int state) {
        double seconds = breakSeconds(inv, state);
        if (seconds <= 0 || Double.isInfinite(seconds)) return 0;
        return (int) Math.floor(60.0 / (seconds + 0.25));  // + travel/aim overhead
    }

    // -------------------------------------------------------------- survival --

    /**
     * The next thing survival should care about, in priority order.
     *
     * <p>Deliberately a single line with one action: a bot that lists five
     * problems at once does none of them.
     */
    public static String survivalAdvice(Inventory inv, float health, int food) {
        if (health <= 0) return "I'm dead; respawn first.";
        if (health <= 6) {
            Food.FoodItem f = Food.best(inv);
            return f == null ? "Health critical and no food: retreat."
                    : "Health critical: eat " + f.name + " and retreat.";
        }
        if (food <= 6) {
            Food.FoodItem f = Food.best(inv);
            return f == null ? "Starving and no food: find animals or crops."
                    : "Starving: eat " + f.name + ".";
        }
        if (food <= 17 && health < 20) {
            Food.FoodItem f = Food.best(inv);
            if (f != null) return "Healing is slow at food " + food + ": eat " + f.name + ".";
        }
        var upgrades = Armor.upgrades(inv);
        if (!upgrades.isEmpty()) return "Better armour available: equip " + upgrades.get(0).name + ".";
        return "Nothing urgent.";
    }

    /** One-line gear report: weapon, armour, food. */
    public static String loadout(Inventory inv) {
        Combat.Weapon weapon = Combat.bestWeapon(inv);
        Food.FoodItem food = Food.best(inv);
        return "weapon " + weapon.name + " (" + trim(weapon.dps()) + " dps), "
                + Armor.describe(inv) + ", food " + (food == null ? "none" : food.name);
    }

    // ----------------------------------------------------------------- facts --

    public static String summary() {
        return "blocks " + Blocks.blockCount() + "/" + Blocks.stateCount() + " states, items "
                + Items.count() + ", recipes " + mcbot.crafting.Recipes.total() + ", food "
                + Food.count() + ", armour " + Armor.all().size() + ", weapons " + Combat.count()
                + ", mobs " + Mobs.hostiles().size() + " hostile, enchantments "
                + Enchantments.count() + ", " + Loot.summary() + ", " + DamageTypes.summary();
    }

    private static String trim(double v) {
        return v == Math.round(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
