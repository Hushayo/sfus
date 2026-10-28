package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.modules.EntityTrackerModule.Pose;
import mcbot.net.PacketIds;
import mcbot.net.PacketReader;

import java.util.Locale;

/**
 * Reacts to being hurt, and to dying.
 *
 * Damage sources, in order of trust:
 *   - damage_event  -> gives the attacker entity id directly (best)
 *   - entity_status -> hurt animation on our own entity id (no attacker)
 *   - set_health    -> a drop in the health value (last resort)
 *
 * The response is deliberately not "always fight":
 *
 *   health high  -> close the distance, turn to face the attacker, swing on the
 *                   vanilla 625 ms cooldown, and step back out of reach after
 *                   each hit (kiting: fewer hits land on us)
 *   health low   -> disengage and run, eating if food is available
 *   attacker is a player and PvP is off (default) -> never swing, only flee
 *
 * PvP is enabled with -Dmcbot.pvp=true.
 */
public class SurvivalModule implements BotModule {
    private static final double ATTACK_REACH = 3.0;
    private static final double RETREAT_HEALTH = 6.0f;
    private static final double CAUTIOUS_HEALTH = 10.0f;
    private static final long ATTACK_COOLDOWN_MS = 625L;
    private static final long REPATH_MS = 900L;
    private static final long COMBAT_MEMORY_MS = 12_000L;
    private static final long ENGAGE_TIMEOUT_MS = 20_000L;

    private final boolean pvp = Boolean.getBoolean("mcbot.pvp");

    private volatile MinecraftBot bot;
    private volatile int attackerId = -1;
    private volatile long attackerSeenMs = 0L;
    private volatile long combatUntilMs = 0L;
    private volatile long nextAttackMs = 0L;
    private volatile long nextRepathMs = 0L;
    private volatile long lastEatMs = 0L;
    private volatile boolean retreating = false;
    private volatile String attackerName = null;
    private volatile boolean healthKnown = false;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    public boolean inCombat() {
        return System.currentTimeMillis() < combatUntilMs;
    }

    /**
     * Starts a fight with the nearest non-player entity. Used by the chat AI
     * ("kill that zombie") and by the defend command.
     */
    public String engageNearest() {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return "I'm not in the world yet.";
        Pose p = nearestThreat(b, 16.0);
        if (p == null) return "I don't see anything hostile nearby.";
        attackerId = p.id;
        attackerSeenMs = System.currentTimeMillis();
        combatUntilMs = System.currentTimeMillis() + ENGAGE_TIMEOUT_MS;
        retreating = false;
        attackerName = "a mob";
        bot.look.alert(p.id, 5000L);
        System.out.println("[survival] engaging entity " + p.id + " at "
                + p.x + " " + p.y + " " + p.z);
        return "Attacking the nearest mob.";
    }

    public String statusLine() {
        if (!inCombat()) return "Not in combat.";
        return (retreating ? "Retreating from " : "Fighting ")
                + (attackerName == null ? "an attacker" : attackerName)
                + " (health " + String.format(Locale.ROOT, "%.0f", bot == null ? 20 : bot.health) + ")";
    }

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        if (packetId == PacketIds.SET_HEALTH) {
            PacketReader pr = new PacketReader(data);
            float health = pr.readFloat();
            int food = pr.readVarInt();
            float saturation = pr.readFloat();
            if (!healthKnown) {
                // The first health packet describes the state we are joining
                // with. Comparing it against the default 20 made every login
                // look like "lost 1 hp" and opened a phantom combat window.
                healthKnown = true;
                bot.health = health;
                bot.food = food;
                bot.saturation = saturation;
                if (health > 0) bot.dead = false;
                return;
            }
            float before = bot.health;
            bot.health = health;
            bot.food = food;
            bot.saturation = saturation;
            if (health > 0) bot.dead = false;
            if (health < before - 0.01f) {
                onDamaged(bot, -1, "lost " + String.format(Locale.ROOT, "%.1f", before - health) + " hp");
            }
            return;
        }

        if (PacketIds.DAMAGE_EVENT >= 0 && packetId == PacketIds.DAMAGE_EVENT) {
            PacketReader pr = new PacketReader(data);
            int entityId = pr.readVarInt();
            pr.readVarInt(); // damage type id
            int causeId = pr.readVarInt();
            int directId = pr.readVarInt();
            if (entityId != bot.entityId) return;
            int source = directId != 0 ? directId : causeId;
            onDamaged(bot, source, "damage_event");
            return;
        }

        if (packetId == PacketIds.ENTITY_STATUS) {
            PacketReader pr = new PacketReader(data);
            int entityId = pr.readVarInt();
            int status = pr.readUnsignedByte();
            if (entityId == bot.entityId && status == 2) { // hurt animation
                onDamaged(bot, -1, "hurt animation");
            }
            return;
        }

        if (packetId == 0x45) { // player combat kill / death
            PacketReader pr = new PacketReader(data);
            int playerId = pr.readVarInt();
            int killerId = pr.readVarInt();
            String message = printable(data);
            if (playerId != bot.entityId) return;
            onDeath(bot, killerId, message);
        }
    }

    private void onDamaged(MinecraftBot bot, int sourceId, String why) {
        long now = System.currentTimeMillis();
        bot.lastDamageMs = now;
        combatUntilMs = now + COMBAT_MEMORY_MS;

        if (sourceId > 0) {
            attackerId = sourceId;
            attackerSeenMs = now;
            Pose p = bot.entityTracker.pose(sourceId);
            attackerName = p == null ? ("entity " + sourceId)
                    : (isPlayer(bot, p) ? "player " + shortId(p) : "a mob");
        } else if (attackerId >= 0 && now - attackerSeenMs < 4000L) {
            // keep the previous attacker: a hit without a source id is usually
            // the same mob that is already on us
            attackerSeenMs = now;
        } else {
            Pose nearest = nearestThreat(bot, 6.0);
            if (nearest != null) {
                attackerId = nearest.id;
                attackerSeenMs = now;
                attackerName = isPlayer(bot, nearest) ? "player " + shortId(nearest) : "a mob";
            }
        }

        double threshold = CAUTIOUS_HEALTH;
        retreating = bot.health <= RETREAT_HEALTH
                || (bot.health <= threshold && bot.food <= 6);

        System.out.println("[survival] damaged (" + why + "), health="
                + String.format(Locale.ROOT, "%.1f", bot.health)
                + " attacker=" + attackerId + " retreat=" + retreating);

        if (attackerId >= 0) bot.look.alert(attackerId, 4000L);
    }

    private void onDeath(MinecraftBot bot, int killerId, String message) {
        bot.dead = true;
        bot.lastDeathMessage = message;
        bot.health = 0;
        attackerId = -1;
        retreating = false;
        combatUntilMs = 0;
        bot.movement.stop();
        bot.mining.stop();
        System.out.println("[survival] died: " + message);
        try {
            bot.sendChat("I died. Respawning...");
        } catch (Exception ignored) {}
    }

    @Override
    public void onTick(MinecraftBot bot) {
        if (bot == null || bot.dead || !bot.playerLoaded || !bot.hasPlayerPosition) return;
        long now = System.currentTimeMillis();

        if (now > combatUntilMs) {
            if (attackerId >= 0) {
                System.out.println("[survival] disengaging");
                attackerId = -1;
                retreating = false;
            }
            return;
        }

        // Keep fighting only while the target is still around.
        Pose target = attackerId >= 0 ? bot.entityTracker.pose(attackerId) : null;
        if (target != null) attackerSeenMs = now;
        if (target == null && now - attackerSeenMs > 3000L) {
            attackerId = -1;
            retreating = false;
            return;
        }

        boolean player = target != null && isPlayer(bot, target);
        if (player && !pvp) {
            // Server rules come first: never swing at a player.
            if (!retreating) {
                System.out.println("[survival] attacked by a player, pvp disabled -> fleeing");
                retreating = true;
            }
        }

        // Without a verified attack packet the bot cannot win a fight, so it
        // should not stand in reach taking hits. Keep distance instead.
        if (!bot.interaction.canAttack() && !retreating) {
            retreating = true;
            System.out.println("[survival] attack packet id unknown on this server"
                    + " -> keeping distance instead of fighting");
        }

        double myX, myY, myZ;
        synchronized (bot.positionLock) {
            myX = bot.playerX;
            myY = bot.playerY;
            myZ = bot.playerZ;
        }

        if (retreating) {
            retreat(bot, target, myX, myY, myZ, now);
            return;
        }

        if (target == null) {
            // Hit by something we cannot see. Look around, keep the guard up.
            if (now > nextRepathMs) {
                nextRepathMs = now + 1500L;
                bot.look.noteInteraction();
            }
            return;
        }

        double dist = target.distanceTo(myX, myY + 1.0, myZ);

        if (dist > ATTACK_REACH) {
            if (now > nextRepathMs) {
                nextRepathMs = now + REPATH_MS;
                bot.movement.goTo(target.x, target.y, target.z);
            }
            return;
        }

        // In reach: face it and swing on cooldown.
        bot.look.alert(attackerId, 1500L);
        bot.movement.faceTowards(target.x, target.y + 1.0, target.z);
        if (now >= nextAttackMs) {
            nextAttackMs = now + ATTACK_COOLDOWN_MS;
            bot.interaction.attack(attackerId);
            // Step back out of its reach after each hit: taking one hit every
            // other second instead of every tick is the whole point.
            if (bot.health < 14.0f) {
                double awayX = myX + (myX - target.x);
                double awayZ = myZ + (myZ - target.z);
                bot.movement.goTo(awayX, myY, awayZ);
            }
        }
    }

    private void retreat(MinecraftBot bot, Pose target, double myX, double myY, double myZ, long now) {
        if (bot.food < 20 && now - lastEatMs > 3000L && bot.health < 16.0f) {
            lastEatMs = now;
            bot.interaction.useItem();
        }
        if (now < nextRepathMs) return;
        nextRepathMs = now + 1500L;
        if (target == null) {
            // No idea where the damage came from: run in a random direction.
            double ang = Math.random() * Math.PI * 2;
            bot.movement.goTo(myX + Math.cos(ang) * 14, myY, myZ + Math.sin(ang) * 14);
            return;
        }
        double dx = myX - target.x;
        double dz = myZ - target.z;
        double len = Math.hypot(dx, dz);
        if (len < 0.01) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        bot.movement.goTo(myX + dx / len * 16, myY, myZ + dz / len * 16);
    }

    /** Nearest tracked entity that is not us and not a known player. */
    private Pose nearestThreat(MinecraftBot bot, double maxDist) {
        double bx, by, bz;
        synchronized (bot.positionLock) {
            bx = bot.playerX;
            by = bot.playerY;
            bz = bot.playerZ;
        }
        Pose best = null;
        double bestDist = maxDist;
        for (Pose p : bot.entityTracker.poses()) {
            if (p.id == bot.entityId) continue;
            if (isPlayer(bot, p)) continue;
            double d = p.distanceTo(bx, by, bz);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** Chat senders are players; everything else is assumed to be a mob. */
    private static boolean isPlayer(MinecraftBot bot, Pose p) {
        return p.uuid != null && bot.knownPlayers.contains(p.uuid);
    }

    private static String shortId(Pose p) {
        return p.uuid == null ? String.valueOf(p.id) : p.uuid.toString().substring(0, 8);
    }

    private static String printable(byte[] data) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (byte b : data) {
            int c = b & 0xFF;
            if (c >= 32 && c < 127) {
                run.append((char) c);
            } else {
                if (run.length() >= 4) out.append(run).append(' ');
                run.setLength(0);
            }
        }
        if (run.length() >= 4) out.append(run);
        return out.toString().trim();
    }
}
