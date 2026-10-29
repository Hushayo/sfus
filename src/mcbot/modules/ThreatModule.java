package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.knowledge.Mobs;
import mcbot.registry.Entities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Classifies everything nearby into "will hurt me", "might hurt me" and
 * "food", and ranks the dangerous ones.
 *
 * <p>Before this, survival treated every non-player entity as a threat and
 * "nearest entity" as the target - which meant it would square up to a cow and
 * ignore the skeleton shooting it from behind a tree. The tracker already knows
 * each entity's type id; the mob table knows what that type does. Joining them
 * is the missing input for combat, retreat and looting.
 *
 * <p>Runs at 5 Hz, not 20: mob positions change slower than that, and the
 * ranking allocates.
 */
public class ThreatModule implements BotModule {
    /** One entity, classified and scored. */
    public static final class Threat {
        public final int entityId;
        public final String name;
        public final double distance;
        public final double score;
        public final boolean ranged;
        public final boolean hostile;

        Threat(int entityId, String name, double distance, double score,
               boolean ranged, boolean hostile) {
            this.entityId = entityId;
            this.name = name;
            this.distance = distance;
            this.score = score;
            this.ranged = ranged;
            this.hostile = hostile;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s (%.1f blocks%s)", name, distance, ranged ? ", ranged" : "");
        }
    }

    private static final double SCAN_RADIUS = 32.0;

    private volatile MinecraftBot bot;
    private volatile List<Threat> threats = List.of();
    private volatile long lastScanMs = 0L;
    private int tickCounter = 0;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    @Override
    public void onTick(MinecraftBot bot) {
        if (bot == null || !bot.playerLoaded || !bot.hasPlayerPosition) return;
        if (++tickCounter % 4 != 0) return;      // 5 Hz
        scan(bot);
    }

    private void scan(MinecraftBot bot) {
        double px, py, pz;
        synchronized (bot.positionLock) {
            px = bot.playerX;
            py = bot.playerY;
            pz = bot.playerZ;
        }
        List<Threat> found = new ArrayList<>();
        for (EntityTrackerModule.Pose p : bot.entityTracker.poses()) {
            if (p.id == bot.entityId) continue;
            String name = Entities.name(p.type);
            if (name == null || name.equals("player")) continue;
            double dist = p.distanceTo(px, py, pz);
            if (dist > SCAN_RADIUS) continue;
            Mobs.Mob mob = Mobs.get(name);
            double score = Mobs.threatScore(name, dist);
            if (!mob.hostile() && dist > 8) continue;   // passives only matter up close (food)
            found.add(new Threat(p.id, name, dist, score, mob.ranged, mob.hostile()));
        }
        found.sort(Comparator.comparingDouble((Threat t) -> -t.score));
        threats = found;
        lastScanMs = System.currentTimeMillis();
    }

    /** Every classified entity in range, most dangerous first. */
    public List<Threat> threats() {
        return threats;
    }

    /** The most dangerous hostile, or null. */
    public Threat nearestHostile() {
        for (Threat t : threats) {
            if (t.hostile && t.score > 0) return t;
        }
        return null;
    }

    /** The nearest passive mob, which is what "get food" wants. */
    public Threat nearestPrey() {
        Threat best = null;
        for (Threat t : threats) {
            if (t.hostile) continue;
            if (best == null || t.distance < best.distance) best = t;
        }
        return best;
    }

    /** True when something hostile is close enough to matter. */
    public boolean inDanger(double radius) {
        Threat t = nearestHostile();
        return t != null && t.distance <= radius;
    }

    public long lastScanMs() { return lastScanMs; }

    /** One line for status and the AI prompt. */
    public String describe() {
        if (threats.isEmpty()) return "nothing nearby";
        List<String> hostile = new ArrayList<>();
        int passive = 0;
        for (Threat t : threats) {
            if (t.hostile) hostile.add(t.toString());
            else passive++;
        }
        if (hostile.isEmpty()) return passive + " passive mobs nearby";
        String line = "hostile: " + String.join(", ", hostile.subList(0, Math.min(3, hostile.size())));
        if (passive > 0) line += "; " + passive + " passive";
        return line;
    }
}
