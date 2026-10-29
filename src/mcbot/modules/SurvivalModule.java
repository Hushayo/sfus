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
    /** Fallback swing interval when the held item has no attack speed. */
    private static final long ATTACK_COOLDOWN_MS = 625L;
    private static final long REPATH_MS = 900L;
    private static final long COMBAT_MEMORY_MS = 12_000L;
    private static final long ENGAGE_TIMEOUT_MS = 20_000L;

    /**
     * Whether the bot swings back at players.
     *
     * <p>On by default: a bot that runs away from every player is defenceless,
     * and "defend yourself" is a reasonable thing to ask one. Servers that
     * forbid PvP can turn it off with {@code -Dmcbot.pvp=false}.
     */
    private final boolean pvp = pvpEnabled();

    /** True unless the operator passed {@code -Dmcbot.pvp=false}. */
    public static boolean pvpEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("mcbot.pvp", "true"));
    }

    private volatile MinecraftBot bot;
    private volatile int attackerId = -1;
    private volatile long attackerSeenMs = 0L;
    private volatile long combatUntilMs = 0L;
    private volatile long nextAttackMs = 0L;
    private volatile long nextRepathMs = 0L;
    private volatile long lastEatMs = 0L;
    private volatile boolean retreating = false;
    private volatile String attackerName = null;
    private volatile boolean attackerIsPlayer = false;
    private volatile boolean healthKnown = false;
    private volatile long lastOwMs = 0L;
    private volatile boolean eating = false;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
        System.out.println("[survival] pvp " + (pvp ? "enabled" : "disabled")
                + ", swings use the held weapon's cooldown");
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
        Pose p = null;
        ThreatModule.Threat threat = b.threats == null ? null : b.threats.nearestHostile();
        if (threat != null && threat.distance <= 16.0) p = b.entityTracker.pose(threat.entityId);
        if (p == null) p = nearestThreat(b, 16.0);
        if (p == null && pvp) {
            // No mob in range: if a player is standing there, they are the
            // target. This is what makes "defend" work in a fight with a
            // person instead of answering "nothing hostile nearby".
            p = nearestPlayer(b, 16.0);
        }
        if (p == null) return "I don't see anything to fight nearby.";
        boolean isPlayer = isPlayer(b, p);
        attackerId = p.id;
        attackerSeenMs = System.currentTimeMillis();
        combatUntilMs = System.currentTimeMillis() + ENGAGE_TIMEOUT_MS;
        retreating = false;
        attackerIsPlayer = isPlayer;
        attackerName = isPlayer ? "player " + shortId(p) : "a mob";
        bot.look.alert(p.id, 5000L);
        System.out.println("[survival] engaging entity " + p.id + " at "
                + p.x + " " + p.y + " " + p.z + (isPlayer ? " (player)" : ""));
        return isPlayer ? "Attacking the player." : "Attacking the nearest mob.";
    }

    /** Attacks the nearest player specifically, for "fight <player>". */
    public String engageNearestPlayer() {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return "I'm not in the world yet.";
        if (!pvp) return "PvP is disabled on this bot.";
        Pose p = nearestPlayer(b, 24.0);
        if (p == null) return "I don't see a player nearby.";
        attackerId = p.id;
        attackerSeenMs = System.currentTimeMillis();
        combatUntilMs = System.currentTimeMillis() + ENGAGE_TIMEOUT_MS;
        retreating = false;
        attackerIsPlayer = true;
        String known = b.playerList == null ? null : b.playerList.nameOf(p.uuid);
        attackerName = known == null ? "player " + shortId(p) : known;
        bot.look.alert(p.id, 5000L);
        System.out.println("[survival] engaging player entity " + p.id
                + (known == null ? "" : " (" + known + ")"));
        return "Attacking " + attackerName + ".";
    }

    /**
     * Attacks a named player, resolved through the tab list.
     *
     * <p>This is what makes "attack ManalaHush" mean that person rather than
     * "whatever is closest". The name comes from {@code player_info_update};
     * the position comes from the entity tracker, joined on UUID.
     */
    public String engagePlayer(String name) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return "I'm not in the world yet.";
        if (!pvp) return "PvP is disabled on this bot.";
        java.util.UUID uuid = b.playerList == null ? null : b.playerList.uuidOf(name);
        if (uuid == null) {
            return "I don't see " + name + " in the player list.";
        }
        Pose p = b.entityTracker.poseByUuid(uuid);
        if (p == null) return name + " isn't in range.";
        if (p.id == b.entityId) return "That's me.";
        attackerId = p.id;
        attackerSeenMs = System.currentTimeMillis();
        combatUntilMs = System.currentTimeMillis() + ENGAGE_TIMEOUT_MS;
        retreating = false;
        attackerIsPlayer = true;
        attackerName = name;
        bot.look.alert(p.id, 5000L);
        System.out.println("[survival] engaging " + name + " (entity " + p.id + ")");
        return "Attacking " + name + ".";
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
            // entityId, damageType holder, causeId, directId, optional source pos.
            // The optional position means the body can be shorter than expected,
            // so every field after the entity id is read defensively.
            PacketReader pr = new PacketReader(data);
            int entityId = pr.readVarInt();
            if (entityId != bot.entityId) return;
            int source = -1;
            try {
                pr.readVarInt();                       // damage type id
                int causeId = pr.remaining() > 0 ? pr.readVarInt() : -1;
                int directId = pr.remaining() > 0 ? pr.readVarInt() : -1;
                source = directId > 0 ? directId : causeId;
            } catch (RuntimeException ignored) {
                // short body: fall back to the nearest threat
            }
            onDamaged(bot, source, "damage_event");
            return;
        }

        if (packetId == PacketIds.HURT_ANIMATION) {
            PacketReader pr = new PacketReader(data);
            int entityId = pr.readVarInt();
            if (entityId == bot.entityId) onDamaged(bot, -1, "hurt animation");
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

        if (packetId == PacketIds.PLAYER_COMBAT_KILL) {
            PacketReader pr = new PacketReader(data);
            int playerId = pr.readVarInt();
            int killerId = pr.readVarInt();
            // The death message is a chat component, not a string. Dumping the
            // raw bytes produced lines like "with click_event command /tell
            // AIBot ... translate death.attack.mace_smash" in the log and in
            // chat; decoding it gives "AIBot was smashed by Nottbuz".
            String message;
            try {
                message = mcbot.net.ChatText.read(pr);
            } catch (RuntimeException e) {
                message = printable(data);
            }
            if (message == null || message.isBlank()) message = "unknown";
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
            attackerIsPlayer = p != null && isPlayer(bot, p);
            attackerName = p == null ? ("entity " + sourceId)
                    : (attackerIsPlayer ? "player " + shortId(p) : "a mob");
        } else if (attackerId >= 0 && now - attackerSeenMs < 4000L) {
            // keep the previous attacker: a hit without a source id is usually
            // the same mob that is already on us
            attackerSeenMs = now;
        } else {
            Pose nearest = nearestThreat(bot, 6.0);
            if (nearest != null) {
                attackerId = nearest.id;
                attackerSeenMs = now;
                attackerIsPlayer = isPlayer(bot, nearest);
                attackerName = attackerIsPlayer ? "player " + shortId(nearest) : "a mob";
            }
        }

        double threshold = CAUTIOUS_HEALTH;
        retreating = bot.health <= RETREAT_HEALTH
                || (bot.health <= threshold && bot.food <= 6);

        System.out.println("[survival] damaged (" + why + "), health="
                + String.format(Locale.ROOT, "%.1f", bot.health)
                + " attacker=" + attackerId + " retreat=" + retreating);

        if (attackerId >= 0) bot.look.alert(attackerId, 4000L);

        // Being hit by a person is a social event, so say something. Mobs hit
        // constantly and would turn chat into noise, so they stay silent.
        if (attackerIsPlayer && now - lastOwMs > 15_000L) {
            lastOwMs = now;
            String[] lines = {"Ow.", "Hey! Watch it.", "That was me you hit.",
                    "I felt that.", "Careful with that thing."};
            try {
                bot.sendChat(lines[(int) (Math.random() * lines.length)]);
            } catch (Exception ignored) {}
        }
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
            eatIfHungry(bot, now);
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
            // Only when the operator turned PvP off; otherwise a player is a
            // target like any other.
            if (!retreating) {
                System.out.println("[survival] attacked by a player, pvp disabled -> fleeing");
                retreating = true;
            }
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

        // In reach: face it and swing on the weapon's own cooldown.
        bot.look.alert(attackerId, 1500L);
        bot.movement.faceTowards(target.x, target.y + 1.0, target.z);
        if (now >= nextAttackMs) {
            // A sword and a pickaxe do not swing at the same rate: 1.6/s vs
            // 1.2/s. Swinging on a fixed 625 ms timer wastes the sword and
            // under-uses the pickaxe.
            var weapon = mcbot.knowledge.Combat.weapon(bot.interaction.inventory.held().name());
            nextAttackMs = now + Math.max(200L, weapon.cooldownMs());
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

    /**
     * Eats when hungry enough for it to matter.
     *
     * Vanilla regen only runs while food >= 18 (or >= 6 with saturation), so
     * eating at 17 is worth it and eating at 20 is a waste. The held slot is
     * switched to a food item, use_item is held for the 1.6 s eat animation,
     * then released - which is exactly what a player does.
     */
    private void eatIfHungry(MinecraftBot bot, long now) {
        if (eating || bot.eating || bot.food >= 18 || bot.health >= 20f) return;
        if (now - lastEatMs < 4000L) return;
        // Golden apples are only on the menu when this is about survival, not
        // about topping up the bar: one enchanted apple costs eight gold blocks.
        int slot = mcbot.knowledge.Food.bestHotbarSlot(bot.interaction.inventory, emergency(bot));
        if (slot < 0) return;
        lastEatMs = now;
        eating = true;
        new Thread(() -> {
            try {
                bot.interaction.selectSlot(slot);
                Thread.sleep(150L);
                bot.interaction.useItem();
                // The eat animation is per item (1.6 s for ordinary food), and
                // releasing early leaves the food half-eaten and the hunger bar
                // unchanged - which reads as "eating does nothing".
                var stack = bot.interaction.inventory.get(36 + slot);
                var food = mcbot.knowledge.Food.get(stack.name());
                int ticks = food == null ? 36 : food.eatTicks();
                Thread.sleep(ticks * 50L);
                bot.interaction.releaseUseItem();
                System.out.println("[survival] ate " + stack.name() + " from hotbar slot " + slot);
            } catch (Exception e) {
                System.out.println("[survival] eat failed: " + e);
            } finally {
                eating = false;
            }
        }, "eat").start();
    }

    /** Eats now, on a worker thread. Used by the eat command and the AI. */
    public String eatNow() {
        MinecraftBot b = bot;
        if (b == null) return "I'm not connected.";
        if (b.eating) return "I'm already eating.";
        var food = mcbot.knowledge.Food.best(b.interaction.inventory, emergency(b));
        if (food == null) return "I have no food.";
        if (!mcbot.knowledge.Food.canEat(b.food, food)) return "I'm not hungry.";
        new Thread(() -> {
            var result = new mcbot.api.Actions(b).eat();
            System.out.println("[survival] " + result);
        }, "eat").start();
        return "Eating " + mcbot.registry.ItemNames.pretty(food.name) + ".";
    }

    /** Low enough that a golden apple is no longer a waste. */
    private static boolean emergency(MinecraftBot bot) {
        return bot.health <= 8.0f || bot.food <= 6;
    }

    private void retreat(MinecraftBot bot, Pose target, double myX, double myY, double myZ, long now) {
        if (bot.food < 20 && now - lastEatMs > 3000L && bot.health < 16.0f) {
            lastEatMs = now;
            int slot = mcbot.knowledge.Food.bestHotbarSlot(bot.interaction.inventory, true);
            if (slot >= 0) {
                bot.interaction.selectSlot(slot);
                bot.interaction.useItem();
            }
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

    /**
     * Nearest tracked entity that is actually dangerous.
     *
     * <p>The mob table decides: a cow is not a threat even when it is the
     * closest entity. An entity type the table does not know is used only when
     * nothing classified is in range - cautious about an unknown mob is cheaper
     * than ignoring a hostile one.
     */
    private Pose nearestThreat(MinecraftBot bot, double maxDist) {
        double bx, by, bz;
        synchronized (bot.positionLock) {
            bx = bot.playerX;
            by = bot.playerY;
            bz = bot.playerZ;
        }
        Pose best = null;
        double bestDist = maxDist;
        Pose unknown = null;
        double unknownDist = maxDist;
        for (Pose p : bot.entityTracker.poses()) {
            if (p.id == bot.entityId) continue;
            if (isPlayer(bot, p)) continue;
            double d = p.distanceTo(bx, by, bz);
            String name = mcbot.registry.Entities.name(p.type);
            if (name != null && mcbot.knowledge.Mobs.isHostile(name)) {
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                }
            } else if (name == null && d < unknownDist) {
                unknownDist = d;
                unknown = p;
            }
        }
        return best != null ? best : unknown;
    }

    /**
     * True when the tracked entity is a player.
     *
     * <p>The entity type is the reliable signal: a player who has never spoken
     * in chat is not in {@code knownPlayers}, and the old UUID-only check
     * treated them as a mob - which meant the bot would happily square up to a
     * stranger but call the same person "a mob" in its own logs.
     */
    private static boolean isPlayer(MinecraftBot bot, Pose p) {
        if (p.type == mcbot.registry.Entities.id("player")) return true;
        return p.uuid != null && bot.knownPlayers.contains(p.uuid);
    }

    /** Nearest tracked player, or null. */
    private Pose nearestPlayer(MinecraftBot bot, double maxDist) {
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
            if (!isPlayer(bot, p)) continue;
            double d = p.distanceTo(bx, by, bz);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
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
