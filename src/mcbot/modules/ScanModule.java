package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketReader;
import mcbot.world.BlockNames;
import mcbot.world.BlockScanner;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Block scanning over the whole loaded world.
 *
 *   scan            count every block in every loaded chunk
 *   scan 32         only within 32 blocks
 *   find oak_log    nearest matches by name (needs blocks.txt) or by state id
 *   where           position, dimension, loaded chunks
 *
 * The scan itself is a straight char[] walk of the client-side cache, so it
 * sees exactly what the server has told us and nothing more.
 */
public class ScanModule implements BotModule {
    private static final Pattern SCAN = Pattern.compile("(?i)^\\s*scan(?:\\s+(\\d+))?\\s*$");
    private static final Pattern FIND = Pattern.compile("(?i)^\\s*find\\s+(\\S+)(?:\\s+(\\d+))?\\s*$");
    private static final Pattern WHERE = Pattern.compile("(?i)^\\s*(?:where|pos|position)\\s*$");

    private volatile MinecraftBot bot;
    private volatile boolean scanning = false;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    public static boolean isScanCommand(String message) {
        if (message == null) return false;
        return SCAN.matcher(message).matches()
                || FIND.matcher(message).matches()
                || WHERE.matcher(message).matches();
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
        Matcher m = SCAN.matcher(text);
        if (m.matches()) {
            int radius = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
            // A full-world scan walks tens of millions of blocks; never do that
            // on the packet thread or keepalives start to slip.
            new Thread(() -> {
                try {
                    bot.sendChat(runScan(radius));
                } catch (Exception e) {
                    System.out.println("[scan] error: " + e);
                }
            }, "scan").start();
            return;
        }
        m = FIND.matcher(text);
        if (m.matches()) {
            String what = m.group(1);
            int limit = m.group(2) == null ? 3 : Math.min(8, Integer.parseInt(m.group(2)));
            new Thread(() -> {
                try {
                    bot.sendChat(runFind(what, limit));
                } catch (Exception e) {
                    System.out.println("[scan] error: " + e);
                }
            }, "scan").start();
            return;
        }
        if (WHERE.matcher(text).matches()) {
            bot.sendChat(whereAmI());
        }
    }

    /** Human-readable summary, also used as an AI tool result. */
    public String runScan(int radiusBlocks) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return "I'm not in the world yet.";
        if (scanning) return "Already scanning.";
        scanning = true;
        try {
            double px, pz;
            synchronized (b.positionLock) {
                px = b.playerX;
                pz = b.playerZ;
            }
            BlockScanner.Summary s = BlockScanner.scan(b.world, px, pz, radiusBlocks);
            StringBuilder sb = new StringBuilder();
            sb.append("Scanned ").append(s.chunks).append(" chunks, ")
                    .append(s.nonAir).append(" blocks in ").append(s.elapsedMs).append(" ms");
            if (radiusBlocks > 0) sb.append(" (r=").append(radiusBlocks).append(")");
            sb.append(". ");

            List<int[]> top = s.top(6);
            for (int[] e : top) {
                String name = BlockNames.name(e[0]);
                sb.append(name == null ? "#" + e[0] : shortName(name))
                        .append('=').append(e[1]).append(' ');
            }
            if (top.isEmpty()) sb.append("nothing but air. ");

            System.out.println("[scan] " + sb);
            return trim(sb.toString().trim(), 250);
        } finally {
            scanning = false;
        }
    }

    public String runFind(String what, int limit) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return "I'm not in the world yet.";
        java.util.function.IntPredicate pred;
        String label;

        Integer exact = parseId(what);
        if (exact != null) {
            final int id = exact;
            pred = state -> state == id;
            label = BlockNames.name(id) == null ? "#" + id : shortName(BlockNames.name(id));
        } else if (BlockNames.loaded()) {
            List<Integer> ids = BlockNames.matching(what);
            if (ids.isEmpty()) return "I don't know a block called '" + what + "'.";
            java.util.Set<Integer> set = new java.util.HashSet<>(ids);
            pred = set::contains;
            label = what;
        } else {
            return "I need a block name table (blocks.txt) to look up names, or a state id: find 4123";
        }

        double px, py, pz;
        synchronized (b.positionLock) {
            px = b.playerX;
            py = b.playerY;
            pz = b.playerZ;
        }
        List<BlockScanner.Hit> hits = BlockScanner.find(b.world, pred, px, py, pz, 0, limit);
        if (hits.isEmpty()) return "No " + label + " in my loaded chunks.";
        StringBuilder sb = new StringBuilder("Nearest " + label + ": ");
        for (BlockScanner.Hit h : hits) {
            sb.append((int) h.distance()).append("m at ")
                    .append(h.x).append(' ').append(h.y).append(' ').append(h.z).append("; ");
        }
        System.out.println("[scan] " + sb);
        return trim(sb.toString(), 250);
    }

    public String whereAmI() {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return "I'm not in the world yet.";
        double x, y, z;
        float yaw, pitch;
        synchronized (b.positionLock) {
            x = b.playerX;
            y = b.playerY;
            z = b.playerZ;
            yaw = b.playerYaw;
            pitch = b.playerPitch;
        }
        return String.format(Locale.ROOT,
                "%s at %.1f %.1f %.1f (yaw %.0f pitch %.0f), %d chunks, %d entities, hp %.0f",
                b.world.dimensionKey(), x, y, z, yaw, pitch,
                b.world.chunkCount(), b.entityTracker.trackedCount(), b.health);
    }

    private static Integer parseId(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String shortName(String name) {
        int i = name.indexOf(':');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
