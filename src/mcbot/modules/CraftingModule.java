package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketReader;
import mcbot.world.BlockNames;
import mcbot.world.BlockScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crafting table / furnace / container basics.
 *
 * What works here is the *plumbing*: find the workstation block, walk to it,
 * right-click it, then talk to whatever window the server opens. Recipe logic
 * (which 2x2/3x3 layout produces which item, and which of the 45 slots to click
 * in what order) is not in yet - see docs/ROADMAP.md for the design.
 *
 *   craft                 nearest crafting table, walk there, open it
 *   craft x y z           open the table at that block
 *   furnace               nearest furnace / blast furnace / smoker
 *   furnace x y z
 *   use x y z             right-click any block (doors, levers, chests, ...)
 *   container             what window is open right now
 *   click <slot> [button] [type]   click a slot in the open window
 *   slot <0-8>            select a hotbar slot
 *   close                 close the open window
 */
public class CraftingModule implements BotModule {
    private static final Pattern CRAFT = Pattern.compile(
            "(?i)^\\s*(?:craft|table)(?:\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+))?\\s*$");
    private static final Pattern FURNACE = Pattern.compile(
            "(?i)^\\s*(?:furnace|smelt)(?:\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+))?\\s*$");
    private static final Pattern USE = Pattern.compile(
            "(?i)^\\s*use\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s*$");
    private static final Pattern CLICK = Pattern.compile(
            "(?i)^\\s*click\\s+(\\d+)(?:\\s+(\\d+))?(?:\\s+(\\d+))?\\s*$");
    private static final Pattern SLOT = Pattern.compile("(?i)^\\s*slot\\s+(\\d+)\\s*$");
    private static final Pattern CONTAINER = Pattern.compile(
            "(?i)^\\s*(?:container|inv|inventory|slots)\\s*$");
    private static final Pattern CLOSE = Pattern.compile("(?i)^\\s*close(?:\\s+window)?\\s*$");

    private static final int SEARCH_RADIUS = 32;
    private static final double REACH = 4.0;

    private static final String[] TABLE_NAMES = {"crafting_table"};
    private static final String[] FURNACE_NAMES = {"furnace", "blast_furnace", "smoker"};

    private volatile MinecraftBot bot;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    public static boolean isCraftCommand(String message) {
        if (message == null) return false;
        return CRAFT.matcher(message).matches()
                || FURNACE.matcher(message).matches()
                || USE.matcher(message).matches()
                || CLICK.matcher(message).matches()
                || SLOT.matcher(message).matches()
                || CONTAINER.matcher(message).matches()
                || CLOSE.matcher(message).matches();
    }

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        if (packetId != 0x42) return;
        PacketReader pr = new PacketReader(data);
        pr.readVarInt();
        UUID sender = pr.readUUID();
        pr.readVarInt();
        if (pr.readByte() != 0) pr.readBytes(256);
        String message = pr.readString();
        if (message == null || message.isBlank()) return;
        if (sender.equals(bot.uuid)) return;
        bot.knownPlayers.add(sender);

        String text = message.trim();

        Matcher m = CRAFT.matcher(text);
        if (m.matches()) {
            if (m.group(1) != null) {
                bot.sendChat(bot.interaction.useBlock(
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3))));
            } else {
                openNearest("crafting table", TABLE_NAMES);
            }
            return;
        }
        m = FURNACE.matcher(text);
        if (m.matches()) {
            if (m.group(1) != null) {
                bot.sendChat(bot.interaction.useBlock(
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3))));
            } else {
                openNearest("furnace", FURNACE_NAMES);
            }
            return;
        }
        m = USE.matcher(text);
        if (m.matches()) {
            bot.sendChat(bot.interaction.useBlock(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))));
            return;
        }
        m = CLICK.matcher(text);
        if (m.matches()) {
            int slot = Integer.parseInt(m.group(1));
            int button = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            int type = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            bot.sendChat(bot.interaction.clickSlot(slot, button, type));
            return;
        }
        m = SLOT.matcher(text);
        if (m.matches()) {
            bot.sendChat(bot.interaction.selectSlot(Integer.parseInt(m.group(1))));
            return;
        }
        if (CONTAINER.matcher(text).matches()) {
            bot.sendChat(bot.interaction.status());
            return;
        }
        if (CLOSE.matcher(text).matches()) {
            bot.sendChat(bot.interaction.closeWindow());
        }
    }

    /** Walks to the nearest workstation and opens it, on a worker thread. */
    private void openNearest(String label, String[] names) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) {
            chat("I'm not in the world yet.");
            return;
        }
        if (!BlockNames.loaded()) {
            chat("I can't find a " + label + " without a block name table. "
                    + "Give me coordinates: craft x y z");
            return;
        }
        List<Integer> ids = new ArrayList<>();
        for (String n : names) ids.addAll(BlockNames.matching(n));
        if (ids.isEmpty()) {
            chat("My block table has no " + label + " in it.");
            return;
        }
        java.util.Set<Integer> set = new java.util.HashSet<>(ids);

        new Thread(() -> {
            try {
                double px, py, pz;
                synchronized (b.positionLock) {
                    px = b.playerX;
                    py = b.playerY;
                    pz = b.playerZ;
                }
                BlockScanner.Hit hit = BlockScanner.nearest(b.world,
                        set::contains, px, py, pz, SEARCH_RADIUS);
                if (hit == null) {
                    chat("I don't see a " + label + " within " + SEARCH_RADIUS + " blocks.");
                    return;
                }
                chat("Walking to the " + label + " at " + hit.x + " " + hit.y + " " + hit.z + ".");
                if (!inReach(b, hit.x, hit.y, hit.z)) {
                    b.movement.goTo(hit.x, hit.y, hit.z);
                    long deadline = System.currentTimeMillis() + 40_000L;
                    while (b.movement.isMoving() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(100L);
                    }
                }
                if (!inReach(b, hit.x, hit.y, hit.z)) {
                    chat("I couldn't get close enough to the " + label + ".");
                    return;
                }
                chat(b.interaction.useBlock(hit.x, hit.y, hit.z));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.out.println("[craft] error: " + e);
            }
        }, "craft").start();
    }

    private boolean inReach(MinecraftBot b, int x, int y, int z) {
        double ex, ey, ez;
        synchronized (b.positionLock) {
            ex = b.playerX;
            ey = b.playerY + 1.62;
            ez = b.playerZ;
        }
        double dx = (x + 0.5) - ex;
        double dy = (y + 0.5) - ey;
        double dz = (z + 0.5) - ez;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= REACH;
    }

    private void chat(String message) {
        MinecraftBot b = bot;
        if (b == null) return;
        System.out.println("[craft] " + message);
        try {
            b.sendChat(message);
        } catch (Exception ignored) {}
    }

    /** Short status used by the AI tool layer. */
    public String describe() {
        MinecraftBot b = bot;
        if (b == null) return "Not connected.";
        return String.format(Locale.ROOT, "hotbar %d, %s", b.interaction.heldSlot(),
                b.interaction.status());
    }
}
