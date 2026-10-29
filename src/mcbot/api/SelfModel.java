package mcbot.api;

import mcbot.bot.MinecraftBot;
import mcbot.knowledge.Armor;
import mcbot.knowledge.Combat;
import mcbot.knowledge.Food;
import mcbot.knowledge.Mechanics;
import mcbot.knowledge.Mobs;
import mcbot.modules.EntityTrackerModule;
import mcbot.registry.Entities;
import mcbot.registry.ItemNames;
import mcbot.registry.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bot's model of itself.
 *
 * <p>Everything here is derived from live state: the position the server last
 * accepted, the inventory the server last sent, the entities currently tracked,
 * the job currently running. Nothing is remembered from earlier sessions and
 * nothing is assumed - which is exactly what makes it useful as a prompt for a
 * language model or as the answer to "how are you doing?".
 *
 * <p>The capability list is honest about what is missing, too. A bot that
 * claims it can fly is worse than one that says it cannot.
 */
public final class SelfModel {
    private final MinecraftBot bot;

    public SelfModel(MinecraftBot bot) {
        this.bot = bot;
    }

    public String name() {
        return bot.username;
    }

    public String dimension() {
        return bot.world.dimensionKey();
    }

    public String gameMode() {
        switch (bot.gameMode) {
            case 1: return "creative";
            case 2: return "adventure";
            case 3: return "spectator";
            default: return "survival";
        }
    }

    public double[] position() {
        synchronized (bot.positionLock) {
            return new double[]{bot.playerX, bot.playerY, bot.playerZ};
        }
    }

    public float yaw() {
        synchronized (bot.positionLock) {
            return bot.playerYaw;
        }
    }

    /** What the bot is doing right now, in one phrase. */
    public String activity() {
        if (bot.dead) return "dead, waiting to respawn";
        if (bot.jobs.busy()) return bot.jobs.status();
        if (bot.mining.isMining()) return "mining";
        if (bot.movement.isMoving()) return "walking";
        if (bot.survival.inCombat()) return bot.survival.statusLine();
        return "idle";
    }

    /** Gear in one line: weapon, armour, food. */
    public String gear() {
        return Mechanics.loadout(bot.interaction.inventory);
    }

    /** Inventory in one line, biggest stack first. */
    public String inventorySummary() {
        List<Map.Entry<String, Integer>> contents = bot.interaction.inventory.contents();
        if (contents.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> e : contents) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(ItemNames.pretty(e.getKey()));
            if (++shown >= 10) {
                sb.append(", ...");
                break;
            }
        }
        return sb.toString();
    }

    /** Hostile entities within 24 blocks, most dangerous first. */
    public List<String> threats() {
        double x, y, z;
        synchronized (bot.positionLock) {
            x = bot.playerX;
            y = bot.playerY;
            z = bot.playerZ;
        }
        List<EntityTrackerModule.Pose> poses = bot.entityTracker.poses();
        List<Object[]> scored = new ArrayList<>();
        for (EntityTrackerModule.Pose p : poses) {
            if (p.id == bot.entityId) continue;
            String name = Entities.name(p.type);
            if (name == null) continue;
            double dist = p.distanceTo(x, y, z);
            double score = Mobs.threatScore(name, dist);
            if (score <= 0) continue;
            scored.add(new Object[]{name, dist, score, p.id});
        }
        scored.sort((a, b) -> Double.compare((double) b[2], (double) a[2]));
        List<String> out = new ArrayList<>();
        for (Object[] row : scored) {
            if (out.size() >= 5) break;
            out.add(String.format(Locale.ROOT, "%s %.1f blocks away", ItemNames.pretty((String) row[0]),
                    (Double) row[1]));
        }
        return out;
    }

    /**
     * What this build can actually do. Grouped by mechanic so a reader can see
     * the coverage at a glance, and so a missing capability is obvious.
     */
    public List<String> capabilities() {
        List<String> out = new ArrayList<>();
        out.add("survival: health/food tracking, auto-eat, retreat, respawn");
        out.add("defence: threat classification, melee on cooldown, armour and weapon equipping");
        out.add("mining: 3D A* to the block, correct tool, drops collected and counted");
        out.add("crafting: " + mcbot.crafting.Recipes.total() + " recipes, table and 2x2, smelting with fuel");
        out.add("building: single placements, walls, floors, shelters, beds");
        out.add("interaction: doors, levers, containers, deposit and withdraw");
        out.add("inventory: slot clicks with state-id recovery, hotbar swaps, armour slots");
        out.add("knowledge: blocks, items, drops, food, armour, weapons, mobs, enchantments");
        out.add("reasoning: recipe planning, why-can't-I explanations, threat ranking");
        out.add("chat AI: " + (bot.apiKey != null && !bot.apiKey.isBlank() ? "enabled" : "disabled (no key)"));
        return out;
    }

    /** Multi-line report. Kept under ~15 lines so it fits in a chat window. */
    public String toText() {
        double[] pos = position();
        StringBuilder sb = new StringBuilder();
        sb.append(name()).append(" in ").append(dimension()).append(" (").append(gameMode()).append(")\n");
        sb.append("at ").append(Math.round(pos[0])).append(' ').append(Math.round(pos[1]))
                .append(' ').append(Math.round(pos[2])).append(", facing ")
                .append(compass(yaw())).append('\n');
        sb.append("health ").append(Math.round(bot.health)).append("/20, food ").append(bot.food)
                .append(", saturation ").append(String.format(Locale.ROOT, "%.1f", bot.saturation)).append('\n');
        sb.append("doing: ").append(activity()).append('\n');
        sb.append("gear: ").append(gear()).append('\n');
        sb.append("carrying: ").append(inventorySummary()).append('\n');
        List<String> threats = threats();
        sb.append("threats: ").append(threats.isEmpty() ? "none nearby" : String.join("; ", threats)).append('\n');
        sb.append("next: ").append(Mechanics.survivalAdvice(bot.interaction.inventory,
                bot.health, bot.food));
        return sb.toString();
    }

    /** Compact single line, for logs. */
    public String toLine() {
        double[] pos = position();
        return name() + " hp" + Math.round(bot.health) + " food" + bot.food
                + " at " + Math.round(pos[0]) + " " + Math.round(pos[1]) + " " + Math.round(pos[2])
                + " " + activity();
    }

    private static String compass(float yaw) {
        double y = ((yaw % 360) + 360) % 360;
        if (y < 45 || y >= 315) return "south";
        if (y < 135) return "west";
        if (y < 225) return "north";
        return "east";
    }

    /** Weapon the bot would use right now. */
    public String weapon() {
        Combat.Weapon w = Combat.bestWeapon(bot.interaction.inventory);
        return w.name;
    }

    /** Food the bot would eat right now, or null. */
    public String food() {
        Food.FoodItem f = Food.best(bot.interaction.inventory);
        return f == null ? null : f.name;
    }

    /** Armour summary, for callers that only want the number. */
    public int defense() {
        return Armor.wornDefense(bot.interaction.inventory);
    }

    /** Item count, for callers that only want the number. */
    public int count(String item) {
        return bot.interaction.inventory.count(Items.id(item));
    }
}
