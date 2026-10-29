package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketReader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the packets we cannot name yet, by their shape instead of their id.
 *
 * Why this exists: `set_health` at 0x6A was inferred from a single 9-byte
 * packet at join, and `entity_status` / `damage_event` are still guesses. If the
 * guess is wrong the bot never notices it is being hit, which looks exactly like
 * "the bot ignores me".
 *
 * The probe logs only packets that look like they are about *us*:
 *
 *   - body starts with our own entity id (damage_event, entity_status, ...)
 *   - exactly 9 bytes whose first float is in 0..20 (set_health)
 *
 * and only while -Dmcbot.probe=true. Hit the bot, read the log, and the real id
 * is in there. Throttled per id so a chatty server cannot flood the log.
 */
public class PacketProbeModule implements BotModule {
    private static final long THROTTLE_MS = 20_000L;
    private static final int MAX_BODY_BYTES = 24;

    private final boolean enabled = Boolean.getBoolean("mcbot.probe");
    private final Map<String, Long> lastLog = new ConcurrentHashMap<>();
    private final Map<String, Integer> hits = new ConcurrentHashMap<>();

    @Override
    public void install(MinecraftBot bot) {
        if (enabled) {
            System.out.println("[probe] enabled - logging packets that look like they concern us");
        }
    }

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) {
        if (!enabled || data == null || data.length < 2 || data.length > MAX_BODY_BYTES) return;

        String reason = null;
        if (bot.entityId > 0) {
            try {
                PacketReader pr = new PacketReader(data);
                int first = pr.readVarInt();
                if (first == bot.entityId) reason = "self";
            } catch (RuntimeException ignored) {}
        }
        if (reason == null && data.length == 9) {
            float f = Float.intBitsToFloat(((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                    | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF));
            // Tightened after the first run: 0x36 and 0x73 are 9 bytes long too
            // and produced thousands of false "health?" lines. A real set_health
            // is float health (multiple of 0.5, 0..20) + VarInt food (0..20) +
            // float saturation (0..20).
            if (f >= 0.0f && f <= 20.0f && Math.abs(f * 2 - Math.round(f * 2)) < 1e-4) {
                PacketReader pr = new PacketReader(data);
                pr.readFloat();
                int food = pr.readVarInt();
                float sat = pr.readFloat();
                if (food >= 0 && food <= 20 && sat >= 0.0f && sat <= 20.0f) reason = "health";
            }
        }
        if (reason == null) return;

        String key = Integer.toHexString(packetId) + ":" + reason;
        int count = hits.merge(key, 1, Integer::sum);
        long now = System.currentTimeMillis();
        Long last = lastLog.get(key);
        if (last != null && now - last < THROTTLE_MS) return;
        lastLog.put(key, now);

        System.out.println("[probe] id=0x" + Integer.toHexString(packetId)
                + " len=" + data.length + " looks=" + reason
                + " hits=" + count + " body=" + hex(data));
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString().trim();
    }
}
