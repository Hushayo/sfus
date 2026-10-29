package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.registry.Blocks;
import mcbot.world.BlockScanner;

import java.util.List;
import java.util.Locale;

/**
 * Block scanning over the loaded world - a module, not a command.
 *
 * Players do not ask the bot to scan; they ask it to mine something and the
 * miner scans to find it. The previous version exposed {@code scan}, {@code find}
 * and {@code where} as chat commands, which put an internal search primitive in
 * front of every player on the server.
 *
 * The scan itself is a straight char[] walk of the client-side cache, so it sees
 * exactly what the server has told us and nothing more.
 */
public class ScanModule implements BotModule {
    private volatile MinecraftBot bot;
    private volatile boolean scanning = false;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
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
                String name = Blocks.shortName(e[0]);
                sb.append(name == null ? "#" + e[0] : name)
                        .append('=').append(e[1]).append(' ');
            }
            if (top.isEmpty()) sb.append("nothing but air. ");

            System.out.println("[scan] " + sb);
            return trim(sb.toString().trim(), 250);
        } finally {
            scanning = false;
        }
    }

    /** Nearest matches for a block name, used by the miner and by the AI tools. */
    public String runFind(String what, int limit) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return "I'm not in the world yet.";
        List<BlockScanner.Hit> hits = find(what, limit, 0);
        if (hits.isEmpty()) return "No " + what + " in my loaded chunks.";
        StringBuilder sb = new StringBuilder("Nearest " + what + ": ");
        for (BlockScanner.Hit h : hits) {
            sb.append((int) h.distance()).append("m at ")
                    .append(h.x).append(' ').append(h.y).append(' ').append(h.z).append("; ");
        }
        return trim(sb.toString(), 250);
    }

    /** Structured search: the primitive every job uses instead of a chat command. */
    public List<BlockScanner.Hit> find(String what, int limit, int radiusBlocks) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return List.of();
        String block = mcbot.registry.ItemNames.resolveBlock(what);
        if (block == null) return List.of();
        double px, py, pz;
        synchronized (b.positionLock) {
            px = b.playerX;
            py = b.playerY;
            pz = b.playerZ;
        }
        return BlockScanner.find(b.world, state -> Blocks.is(state, block),
                px, py, pz, radiusBlocks, limit);
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

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
